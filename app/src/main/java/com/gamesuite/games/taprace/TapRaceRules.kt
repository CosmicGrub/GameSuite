package com.gamesuite.games.taprace

import com.gamesuite.settings.CpuDifficulty

/** The three ways to race. */
enum class RaceVariant(val id: String) {
    /** First to a number of taps. */
    SPRINT("sprint"),

    /** Tap only on the beat: perfect and good taps move you on, early, late and extra taps set you back. */
    RHYTHM("rhythm"),

    /** Sprint, but on a red light a tap costs you ground and freezes your pad for a moment. */
    BRAKE("brake");

    companion object {
        fun fromId(id: String?): RaceVariant = entries.firstOrNull { it.id == id } ?: SPRINT
    }
}

/** The traffic light of Mash and Brake (and NONE for the other variants). */
enum class RaceLight { NONE, GREEN, AMBER, RED }

/** How a tap was judged. The screen words, colours, sounds and announces each. */
object RaceJudge {
    const val NONE = ""
    const val PERFECT = "perfect"
    const val GOOD = "good"
    const val EARLY = "early"
    const val LATE = "late"
    const val DOUBLE = "double"
    const val FALSE_START = "false_start"
    const val RED = "red"
}

/** What the last change to the race was (a round or match boundary, or a light change). The screen animates and sounds it. */
object RaceEvent {
    const val START = "start"
    const val GO = "go"
    const val AMBER = "amber"
    const val RED = "red"
    const val GREEN = "green"
    const val ROUND_WIN = "round_win"
    const val ROUND_DRAW = "round_draw"
    const val MATCH_WIN = "match_win"
    const val MATCH_DRAW = "match_draw"
}

/** Why a tap did nothing. [NONE] means it counted. */
enum class TapReject { NONE, NOT_RACING, FALSE_START, LOCKED, TOO_FAST, BOT_SEAT, BAD_SEAT }

/** What a computer racer is like at one level (all human-plausible: a person mashes at about 4 to 8 taps a second). */
data class RaceBotProfile(
    /** Sustained taps per second in Sprint and Mash and Brake. */
    val tapsPerSecond: Double,
    /** Time from GO (or a green light) to the first tap, before the +-25 percent variation. */
    val reactionMs: Int,
    /** Chance after a tap of losing rhythm for a moment. */
    val stumbleChance: Double,
    /** Rhythm: spread (standard deviation) of how far from the beat a tap lands. */
    val beatSpreadMs: Double,
    /** Rhythm: chance of sitting a beat out. */
    val beatSkipChance: Double,
    /** Brake: chance of not noticing the amber light at all. */
    val missAmberChance: Double
)

/**
 * The numbers of Tap Race. The race is a fixed-step simulation ([STEP_MS]) driven by an injected clock, so tests
 * replay a whole race with known tap times and no sleeping. Everything a human might rely on for comfort
 * (target size, rhythm windows, light warnings, penalties) has a RELAXED value for players with motor
 * difficulties; the anti-cheese limits do not change.
 */
object TapRaceRules {
    const val STEP_MS = 20L
    const val MAX_CATCHUP_STEPS = 150
    const val COUNTDOWN_MS = 3_000L
    const val BETWEEN_MS = 2_500L
    const val ROUND_LIMIT_MS = 60_000L

    /** The engine's own last line against cheese: no seat counts two taps closer than this (about 22 a second). */
    const val MIN_TAP_GAP_MS = 45L

    /** A tap during the countdown freezes the pad until this long after GO. */
    const val FALSE_START_LOCK_MS = 500L

    const val DEFAULT_ROUNDS = 3
    val ROUND_CHOICES = listOf(1, 3, 5)

    /** Hold to play: a finger held this long starts tapping for you at [HOLD_INTERVAL_MS]. */
    const val HOLD_START_MS = 250L
    const val HOLD_INTERVAL_MS = 333L

    fun target(variant: RaceVariant, relaxed: Boolean): Int = when (variant) {
        RaceVariant.SPRINT -> if (relaxed) 25 else 40
        RaceVariant.RHYTHM -> if (relaxed) 24 else 36
        RaceVariant.BRAKE -> if (relaxed) 25 else 40
    }

    // ---- rhythm ----
    fun beatMs(relaxed: Boolean): Long = if (relaxed) 857L else 667L
    fun perfectWindowMs(relaxed: Boolean): Long = if (relaxed) 110L else 70L
    fun goodWindowMs(relaxed: Boolean): Long = if (relaxed) 220L else 130L
    const val PERFECT_POINTS = 3
    const val GOOD_POINTS = 2
    const val OFF_BEAT_POINTS = -2
    const val DOUBLE_POINTS = -1

    // ---- mash and brake ----
    fun amberMs(relaxed: Boolean): Long = if (relaxed) 1_600L else 800L
    fun redGraceMs(relaxed: Boolean): Long = if (relaxed) 600L else 220L
    fun redPenalty(relaxed: Boolean): Int = if (relaxed) 3 else 6
    fun redLockMs(relaxed: Boolean): Long = if (relaxed) 400L else 700L
    const val GREEN_MIN_MS = 1_800
    const val GREEN_MAX_MS = 3_600
    const val FIRST_GREEN_EXTRA_MS = 800
    const val RED_MIN_MS = 1_100
    const val RED_MAX_MS = 1_900

    fun bot(level: CpuDifficulty): RaceBotProfile = when (level) {
        CpuDifficulty.EASY -> RaceBotProfile(3.8, 650, 0.10, 95.0, 0.22, 0.20)
        CpuDifficulty.MEDIUM -> RaceBotProfile(5.0, 380, 0.05, 60.0, 0.08, 0.04)
        CpuDifficulty.HARD -> RaceBotProfile(6.3, 230, 0.02, 32.0, 0.02, 0.0)
    }
}
