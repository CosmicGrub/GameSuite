package com.gamesuite.games.tugofwar

import kotlin.math.sqrt

/** How the players are split. A "side" is what pulls together: a team, or one player in a free-for-all. */
enum class TugLayout(val id: String) {
    /** Two even teams: 1 v 1, then 1 v 2 with three people and 2 v 2 with four. */
    TEAMS("teams"),

    /** One person against everyone else: 1 v 1, 1 v 2, 1 v 3. */
    LONE("lone"),

    /** Everybody for themselves: the marker is pulled across a small arena toward each player's corner. */
    FFA("ffa");

    companion object {
        fun fromId(id: String?): TugLayout = entries.firstOrNull { it.id == id } ?: TEAMS
    }
}

/** Which side each seat is on, and which way each side pulls. Pure, so the layouts are tested. */
object TugSides {
    /** The side of every seat for [seats] (2..4) players. Seats of one side are contiguous, side 0 first. */
    fun sideOf(layout: TugLayout, seats: Int): IntArray {
        val n = seats.coerceIn(2, 4)
        if (n == 2) return intArrayOf(0, 1)
        return when (layout) {
            TugLayout.FFA -> IntArray(n) { it }
            TugLayout.LONE -> IntArray(n) { if (it == 0) 0 else 1 }
            TugLayout.TEAMS -> IntArray(n) { if (it < n / 2) 0 else 1 }
        }
    }

    fun sideCount(layout: TugLayout, seats: Int): Int = sideOf(layout, seats).max() + 1

    /** Seats per side, e.g. [2, 2] or [1, 3]. */
    fun sizes(layout: TugLayout, seats: Int): List<Int> {
        val of = sideOf(layout, seats)
        return List(of.max() + 1) { s -> of.count { it == s } }
    }

    /** "1v1", "2v2", "1v3", "1v2", or "ffa3" and "ffa4". */
    fun label(layout: TugLayout, seats: Int): String =
        if (sideCount(layout, seats) > 2) "ffa$seats" else sizes(layout, seats).joinToString("v")

    /**
     * The unit direction each side pulls the marker in, in screen coordinates (x right, y down). Two sides pull along
     * the rope: side 0 to the right and side 1 to the left (each player sees "their" goal on their right, because the
     * far row is turned half a turn). A free-for-all pulls toward each player's corner of the arena.
     */
    fun directions(layout: TugLayout, seats: Int): List<Pair<Double, Double>> {
        val n = seats.coerceIn(2, 4)
        val sides = sideCount(layout, n)
        if (sides == 2) return listOf(1.0 to 0.0, -1.0 to 0.0)
        val d = sqrt(0.5)
        return if (n == 3) listOf(0.0 to 1.0, -d to -d, d to -d) else listOf(-d to d, d to d, -d to -d, d to -d)
    }

    /** How hard each side pulls so unequal teams stay playable: the square root of the size ratio, 1 for equal sides and free-for-all. */
    fun boosts(layout: TugLayout, seats: Int): List<Double> {
        val sizes = sizes(layout, seats)
        if (sizes.size != 2) return sizes.map { 1.0 }
        return listOf(sqrt(sizes[1].toDouble() / sizes[0]), sqrt(sizes[0].toDouble() / sizes[1]))
    }
}

/** What a computer puller is like at one level. Press rates stay well under the human limit at every level. */
data class TugBotProfile(
    /** Seconds from GO to the first pull, before the +-25 percent variation. */
    val reactionMs: Int,
    /** Easy mashes at this many pulls a second and ignores its stamina; Medium taps at it until tired. */
    val tapsPerSecond: Double,
    /** Stamina below which Medium stops to brace. */
    val tiredBelow: Double,
    /** How long a brace-and-heave holds (before variation). */
    val holdMs: Int,
    /** Hard: chance a cycle is wasted on a panicked burst of taps. */
    val panicChance: Double,
    /** Hard: chance of a lapse (a pause) between cycles. */
    val lapseChance: Double
)

/**
 * The numbers of Tug of War. Pulling costs STAMINA, and a pull is only as strong as the stamina behind it, so
 * mashing a pad runs dry in about two seconds and then pulls at a fifth of its strength. A finger HELD on the pad
 * braces (stamina comes back fast) and charges the next pull, and releasing it HEAVES: the pull is up to four
 * times stronger. The rhythm that wins is hold, release, tap, hold, release, tap.
 */
object TugRules {
    const val STEP_MS = 20L
    const val MAX_CATCHUP_STEPS = 150
    const val COUNTDOWN_MS = 3_000L
    const val BETWEEN_MS = 2_500L
    const val ROUND_LIMIT_MS = 45_000L
    const val RELAXED_ROUND_LIMIT_MS = 60_000L
    const val MIN_TAP_GAP_MS = 45L
    const val DEFAULT_ROUNDS = 3
    val ROUND_CHOICES = listOf(1, 3, 5)

    const val MAX_STAMINA = 100.0
    const val TAP_COST = 7.0

    /** How far one unit of pull power moves the marker; the goal is [GOAL] away from the centre. */
    const val UNIT = 0.025
    const val GOAL = 1.0
    const val MAX_RADIUS = 1.5
    const val DRAW_EPSILON = 0.02

    const val REST_DELAY_MS = 250L
    const val REST_REGEN_PER_S = 10.0
    const val BRACE_REGEN_PER_S = 30.0
    const val BRACE_AFTER_MS = 200L
    const val HEAVE_BONUS = 3.0

    /** Hold to play: a finger held [HOLD_START_MS] pulls by itself every [HOLD_INTERVAL_MS] at [HOLD_POWER]. */
    const val HOLD_START_MS = 250L
    const val HOLD_INTERVAL_MS = 333L
    const val HOLD_POWER = 0.8

    fun costOf(relaxed: Boolean): Double = if (relaxed) TAP_COST * 0.5 else TAP_COST
    fun floorOf(relaxed: Boolean): Double = if (relaxed) 0.45 else 0.20
    fun chargeFullMs(relaxed: Boolean): Long = if (relaxed) 450L else 700L
    fun roundLimitMs(relaxed: Boolean): Long = if (relaxed) RELAXED_ROUND_LIMIT_MS else ROUND_LIMIT_MS

    /** The strength of one pull at [stamina] (0..100): the floor when empty, 1 when full. */
    fun pullPower(stamina: Double, relaxed: Boolean): Double {
        val f = floorOf(relaxed)
        return f + (1.0 - f) * (stamina.coerceIn(0.0, MAX_STAMINA) / MAX_STAMINA)
    }

    /** 0..1: how charged a finger held for [heldMs] is. */
    fun charge(heldMs: Long, relaxed: Boolean): Double =
        if (heldMs < BRACE_AFTER_MS) 0.0 else (heldMs.toDouble() / chargeFullMs(relaxed)).coerceIn(0.0, 1.0)

    fun bot(level: com.gamesuite.settings.CpuDifficulty): TugBotProfile = when (level) {
        com.gamesuite.settings.CpuDifficulty.EASY -> TugBotProfile(650, 4.2, 0.0, 0, 0.0, 0.0)
        com.gamesuite.settings.CpuDifficulty.MEDIUM -> TugBotProfile(400, 3.3, 35.0, 650, 0.0, 0.0)
        com.gamesuite.settings.CpuDifficulty.HARD -> TugBotProfile(250, 3.0, 0.0, 700, 0.12, 0.10)
    }
}
