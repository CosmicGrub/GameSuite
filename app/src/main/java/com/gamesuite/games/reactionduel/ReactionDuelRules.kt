package com.gamesuite.games.reactionduel

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The three ways to play Reaction Duel. */
enum class DuelVariant(val id: String) {
    /** Press the moment the go-signal appears; pressing before it is a false start. */
    CLASSIC("classic"),

    /** A shared stream of shapes runs in every zone; press only when the TARGET shape appears. Decoys always differ in SHAPE. */
    COLOUR("colour"),

    /** Simon-style: watch a shared sequence of four shapes, then be the first to tap it back correctly. */
    ECHO("echo");

    companion object {
        fun fromId(id: String?): DuelVariant = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** A shape (0 circle, 1 square, 2 triangle, 3 diamond, 4 star) in one of four colours. Shape alone identifies it. */
data class DuelSymbol(val shape: Int, val colour: Int) {
    companion object {
        const val SHAPES = 5
        const val COLOURS = 4
        /** Echo only uses the first four shapes, one per pad. */
        const val ECHO_PADS = 4
    }
}

object DuelRules {
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4

    val BEST_OF_CHOICES = listOf(3, 5, 7)
    const val DEFAULT_BEST_OF = 3

    /** "Get ready" before every round, then the random wait starts. */
    const val COUNTDOWN_MS = 2_000L
    const val FIRST_COUNTDOWN_MS = 2_800L
    const val WAIT_MIN_MS = 1_500
    const val WAIT_MAX_MS = 4_500

    /** After the signal, how long everybody has to answer before the round is void. */
    const val TIMEOUT_MS = 3_000L
    const val TIMEOUT_RELAXED_MS = 7_000L

    /** After the first valid press, wait this long (engine time) for presses with earlier event times that are still in flight. */
    const val GATHER_MS = 80L

    /** The two fastest valid presses within this many ms of each other are a dead heat: the round is replayed. */
    const val TIE_MS = 2L

    const val RESULT_MS = 2_600L

    /** Rounds played at most: best-of times this many attempts (void rounds count), then the leader wins. */
    const val ATTEMPT_CAP_FACTOR = 3

    // ---- colour match ----
    const val COLOUR_DWELL_MS = 900
    const val COLOUR_DWELL_RELAXED_MS = 1_500
    /** Every decoy is blank for this long at the end of its slot so two in a row never blur together. */
    const val COLOUR_GAP_MS = 200
    const val COLOUR_DECOYS_MIN = 2
    const val COLOUR_DECOYS_MAX = 5
    const val COLOUR_DECOYS_MAX_RELAXED = 3
    const val COLOUR_LEAD_MIN_MS = 800
    const val COLOUR_LEAD_MAX_MS = 1_800

    // ---- echo ----
    const val ECHO_SHOW_MS = 650
    const val ECHO_SHOW_RELAXED_MS = 1_000
    const val ECHO_GAP_MS = 250
    const val ECHO_LEAD_MS = 800
    const val ECHO_YOUR_TURN_DELAY_MS = 500
    const val ECHO_MAX_LEN = 7
    const val ECHO_MAX_LEN_RELAXED = 4
    /** How long the scanning cursor rests on each pad in one-switch mode. */
    const val SCAN_STEP_MS = 900

    fun target(best: Int): Int = best / 2 + 1
    fun attemptCap(best: Int): Int = best * ATTEMPT_CAP_FACTOR

    fun echoLength(roundsPlayed: Int, relaxed: Boolean, oneSwitch: Boolean): Int {
        val cap = if (relaxed || oneSwitch) ECHO_MAX_LEN_RELAXED else ECHO_MAX_LEN
        return (3 + roundsPlayed).coerceAtMost(cap)
    }

    fun echoTimeoutMs(len: Int, relaxed: Boolean, oneSwitch: Boolean): Long =
        if (oneSwitch) len * 4L * SCAN_STEP_MS + 3_000L
        else (if (relaxed) 2L else 1L) * (4_000L + len * 1_200L)

    /** Which of the four echo pads the one-switch cursor is on, [sinceInputMs] after the input opened. */
    fun scanPad(sinceInputMs: Long): Int = ((sinceInputMs.coerceAtLeast(0L) / SCAN_STEP_MS) % DuelSymbol.ECHO_PADS).toInt()

    /**
     * How the computer reacts at each level. [meanMs]/[sdMs]: reaction time after the signal (never below [floorMs]:
     * nobody is faster than that); [falseStart]: chance per round of jumping the gun; [wrong]: chance per decoy
     * (colour) or per tap (echo) of getting it wrong; [echoStepMs]: the gap between its taps in echo.
     */
    class CpuProfile(
        val meanMs: Int,
        val sdMs: Int,
        val floorMs: Int,
        val falseStart: Double,
        val wrong: Double,
        val echoStepMs: Int
    )

    fun profile(level: CpuDifficulty): CpuProfile = when (level) {
        CpuDifficulty.EASY -> CpuProfile(meanMs = 520, sdMs = 110, floorMs = 200, falseStart = 0.10, wrong = 0.12, echoStepMs = 520)
        CpuDifficulty.MEDIUM -> CpuProfile(meanMs = 340, sdMs = 60, floorMs = 180, falseStart = 0.05, wrong = 0.05, echoStepMs = 340)
        CpuDifficulty.HARD -> CpuProfile(meanMs = 245, sdMs = 35, floorMs = 170, falseStart = 0.02, wrong = 0.015, echoStepMs = 230)
    }

    /** A normally distributed value (Box-Muller). */
    fun gauss(random: Random, mean: Double, sd: Double): Double {
        val u1 = (1.0 - random.nextDouble()).coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return mean + sd * sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    /** A per-round (and per-purpose) random stream derived from the match seed, so the same seed replays the same round. */
    fun stream(seed: Long, attempt: Int, salt: Int): Random {
        var h = seed xor (attempt.toLong() * -7046029254386353131L) xor (salt.toLong() * 6364136223846793005L)
        h = (h xor (h ushr 33)) * -49064778989728563L
        h = (h xor (h ushr 29)) * -4265267296055464877L
        return Random(h xor (h ushr 32))
    }
}
