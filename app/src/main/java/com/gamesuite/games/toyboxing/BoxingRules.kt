package com.gamesuite.games.toyboxing

import com.gamesuite.settings.CpuDifficulty

/** The timed moves of Toy Boxing. Times are milliseconds from the moment the move starts. */
enum class BoxingMove(
    /** Time from the start to the moment the punch lands (the "tell" a defender can react to). */
    val windupMs: Int,
    /** Time from the start until the fighter can act again. */
    val totalMs: Int,
    val damage: Int,
    val cost: Int,
    /** Judges' points for a clean hit. */
    val points: Int,
    /** How long a landed hit freezes the target. */
    val hitStaggerMs: Int,
    /** Fraction of the damage that gets through a raised guard. */
    val guardPass: Double,
    /** Stamina a guard loses absorbing it. */
    val guardCost: Int
) {
    JAB(windupMs = 100, totalMs = 300, damage = 6, cost = 8, points = 1, hitStaggerMs = 150, guardPass = 0.15, guardCost = 4),
    HOOK(windupMs = 450, totalMs = 850, damage = 18, cost = 16, points = 3, hitStaggerMs = 500, guardPass = 0.5, guardCost = 14)
}

/** Everything a player can ask their fighter to do. */
enum class BoxingAction {
    JAB, HOOK, DODGE,

    /** Real time: raise the guard and hold it. Turn-based: choose to block the incoming punch. */
    BLOCK,

    /** Real time: lower the guard. */
    GUARD_DOWN,

    /** Real time: scramble to get up during a knockdown count (any action does this too). */
    TAP,

    /** Turn-based: rest to recover stamina (when attacking) or take the punch with no defence (when defending). */
    REST
}

/**
 * The pure numbers of Toy Boxing. The fight is a fixed-step simulation ([STEP_MS]); a punch "lands" at the
 * end of its wind-up, so a defender has [MoveTimes] to read the tell and answer:
 *  - a JAB is quick (100 ms) and cancels an opponent's still-winding-up punch;
 *  - a HOOK has a 450 ms tell and hits hard, but a well-timed DODGE makes it whiff and opens the attacker;
 *  - a raised GUARD blocks nearly all of a jab, half of a hook, and tires when hit;
 *  - mashing runs out of STAMINA: a tired fighter cannot punch and hits softly.
 */
object BoxingRules {
    const val STEP_MS = 50L
    const val MAX_CATCHUP_STEPS = 12
    const val MAX_HP = 100
    const val MAX_STAMINA = 100

    const val COUNTDOWN_MS = 3_000L
    const val ROUND_MS = 60_000L
    const val BREAK_MS = 4_000L
    const val BREAK_HEAL = 25

    const val DODGE_COST = 12
    const val DODGE_TOTAL_MS = 650
    const val DODGE_SAFE_FROM_MS = 100
    const val DODGE_SAFE_TO_MS = 400
    const val BLOCK_RAISE_MS = 100
    const val GUARD_BREAK_STAGGER_MS = 800
    const val WHIFF_PENALTY_MS = 300
    const val COUNTER_WINDOW_MS = 1_200
    const val COUNTER_MULT = 1.5
    const val DIZZY_MULT = 1.25
    const val TIRED_BELOW = 20

    /** Hits on the same boxer within this long of each other form a combo; later hits in a combo do less. */
    const val COMBO_WINDOW_MS = 800L

    /** Damage multiplier of the n-th hit of a combo (1 = the first): 100, 70, 40, then 25 percent. Stops a button-masher's jab chain. */
    fun comboMultiplier(n: Int): Double = (1.0 - 0.3 * (n - 1).coerceAtLeast(0)).coerceAtLeast(0.25)

    /** Judges only score the first [COMBO_SCORED_HITS] hits of a combo. */
    const val COMBO_SCORED_HITS = 2
    const val TIRED_MULT = 0.6

    /** A jab started within this long of the previous jab ending is part of a jab streak. */
    const val JAB_STREAK_GAP_MS = 500L

    /** Extra stamina a jab costs on top of [BoxingMove.cost] when it is the [streak]-th in a row (1 = the first): +2 each, at most +10. Mashing burns out. */
    fun jabSurcharge(streak: Int): Int = (2 * (streak - 1)).coerceIn(0, 10)

    /** A punch that lands while the target is still recovering from a punch of its own catches it off balance and counts as a counter. */
    const val CATCH_OFF_BALANCE = true

    const val REGEN_PER_SEC_IDLE = 20.0
    const val REGEN_PER_SEC_GUARD = 8.0

    const val KNOCKDOWN_POINTS = 5
    const val COUNT_STEP_MS = 650L
    const val COUNT_MAX = 10
    const val COUNT_MIN_TO_RISE = 3
    const val TKO_KNOCKDOWNS = 3

    /** Get-up taps needed on the n-th knockdown of a round (0-based). */
    fun riseNeeded(knockdownsThisRound: Int): Int = 4 + 2 * knockdownsThisRound.coerceAtLeast(0)
    fun riseHp(knockdownsThisRound: Int): Int = (35 - 5 * knockdownsThisRound.coerceAtLeast(0)).coerceAtLeast(15)

    // ---- turn-based assist ----
    const val ASSIST_EXCHANGES = 8
    const val ASSIST_REGEN = 10
    const val ASSIST_REST_REGEN = 25
    const val ASSIST_RISE_HP = 25

    val ROUND_CHOICES = listOf(1, 3, 5)
    const val DEFAULT_ROUNDS = 3

    /** The damage of a landed punch after every modifier. */
    fun damage(move: BoxingMove, counter: Boolean, tiredAfterCost: Boolean, dizzy: Boolean): Int {
        var d = move.damage.toDouble()
        if (counter) d *= COUNTER_MULT
        if (dizzy) d *= DIZZY_MULT
        if (tiredAfterCost) d *= TIRED_MULT
        return Math.round(d).toInt().coerceAtLeast(1)
    }

    fun points(move: BoxingMove, counter: Boolean): Int = move.points + if (counter) 1 else 0

    /** The best answer to an announced punch in the turn-based mode, given the defender's stamina. */
    fun bestDefence(move: BoxingMove, defenderStamina: Int): BoxingAction = when {
        move == BoxingMove.HOOK && defenderStamina >= DODGE_COST -> BoxingAction.DODGE
        move == BoxingMove.JAB && defenderStamina >= DODGE_COST + 8 -> BoxingAction.DODGE
        defenderStamina >= 1 -> BoxingAction.BLOCK
        else -> BoxingAction.REST
    }

    /**
     * How the computer fights at each level. Times are base values the engine varies by [JITTER] percent.
     * [reactMs]: how long it takes to notice an opponent's wind-up and answer it; [reactAccuracy]: how often
     * it answers correctly when it does; [decisionMs]: the gap between its own decisions; [hookRate]: how
     * often it throws a hook from neutral; [guardRate]: how often it sits behind a raised guard instead of
     * attacking; [staminaFloor]: it will not start a punch below this much stamina unless the target is stunned;
     * [tapMs]: its get-up tap rate; [assistAccuracy]: in turn-based play how often it picks the best option.
     */
    class CpuProfile(
        val reactMs: Int,
        val reactAccuracy: Double,
        val decisionMs: Int,
        val hookRate: Double,
        val guardRate: Double,
        val staminaFloor: Int,
        /** How often it throws an un-cancellable hook at a rival too tired to jab it out of the wind-up, and how well it answers a jab streak. */
        val exploitRate: Double,
        val tapMs: Int,
        val assistAccuracy: Double,
        val thinkMs: Int
    )

    const val JITTER = 0.25

    fun profile(level: CpuDifficulty): CpuProfile = when (level) {
        CpuDifficulty.EASY -> CpuProfile(reactMs = 620, reactAccuracy = 0.45, decisionMs = 1_300, hookRate = 0.45, guardRate = 0.15, staminaFloor = 0, exploitRate = 0.0, tapMs = 700, assistAccuracy = 0.35, thinkMs = 900)
        CpuDifficulty.MEDIUM -> CpuProfile(reactMs = 300, reactAccuracy = 0.7, decisionMs = 600, hookRate = 0.2, guardRate = 0.3, staminaFloor = 14, exploitRate = 0.4, tapMs = 430, assistAccuracy = 0.7, thinkMs = 700)
        CpuDifficulty.HARD -> CpuProfile(reactMs = 170, reactAccuracy = 0.92, decisionMs = 260, hookRate = 0.06, guardRate = 0.35, staminaFloor = 30, exploitRate = 0.9, tapMs = 270, assistAccuracy = 0.95, thinkMs = 550)
    }
}
