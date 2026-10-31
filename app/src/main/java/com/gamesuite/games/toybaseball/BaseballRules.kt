package com.gamesuite.games.toybaseball

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.random.Random

/**
 * The pitch types of Toy Baseball. [speed] orders them from fastest (0) to slowest (3); the batter's timing
 * choice is "be ready for pitch X", so a timing error is the distance between two speeds. [movement] is how
 * much late movement costs the batter in contact quality.
 */
enum class PitchType(val speed: Int, val movement: Int) {
    FASTBALL(0, 0),
    SLIDER(1, 8),
    CURVEBALL(2, 10),
    CHANGEUP(3, 4)
}

/**
 * What the batter chooses before the pitch is revealed: either TAKE (no swing) or a swing aimed at [row],
 * [col] (each 0..2, the 3x3 strike zone, top-left origin), timed for [timing] (which pitch they are ready
 * for), as a [power] swing (bigger hits when it connects, but it needs a better guess) or a contact swing.
 */
data class BaseballSwing(
    val take: Boolean,
    val row: Int = 1,
    val col: Int = 1,
    val timing: PitchType = PitchType.FASTBALL,
    val power: Boolean = false
) {
    companion object {
        val TAKE = BaseballSwing(take = true)
    }
}

/** What happened on a pitch. */
object BaseballOutcome {
    const val CALLED_STRIKE = "called_strike"
    const val BALL = "ball"
    const val SWING_MISS = "swing_miss"
    const val FOUL = "foul"
    const val SINGLE = "single"
    const val DOUBLE = "double"
    const val HOME_RUN = "home_run"
    const val GROUND_OUT = "ground_out"
    const val FLY_OUT = "fly_out"
    const val LINE_OUT = "line_out"
    const val DOUBLE_PLAY = "double_play"
    const val SAC_FLY = "sac_fly"
    const val WALK = "walk"
    const val STRIKEOUT_SWINGING = "strikeout_swinging"
    const val STRIKEOUT_LOOKING = "strikeout_looking"
}

/** The two random numbers the contact model uses. A test can script them; the game draws them from its Random. */
interface BaseballRolls {
    /** Variance in the contact quality, -6..6. */
    fun variance(): Int

    /** Where the ball lands among the fielders, 0..99. */
    fun placement(): Int
}

class RandomRolls(private val random: Random) : BaseballRolls {
    override fun variance(): Int = random.nextInt(13) - 6
    override fun placement(): Int = random.nextInt(100)
}

/**
 * The pure rules and the deterministic contact model of Toy Baseball.
 *
 * Contact: the pitch lands on a 5x5 grid whose middle 3x3 is the strike zone. A swing's quality is
 * `100 - 24*timingError - 22*locationError - movement - corner/ball penalties +- swing type + variance`
 * (see [quality]); the quality picks the result band (miss, foul, weak out, in play, solid, hard) and a
 * placement roll picks the field outcome inside the band ([contactOutcome]).
 */
object BaseballRules {
    const val MIN_INNINGS = 3
    const val MAX_INNINGS = 9
    const val DEFAULT_INNINGS = 3
    const val MAX_EXTRA_INNINGS = 3
    const val BALLS_FOR_WALK = 4
    const val STRIKES_FOR_OUT = 3
    const val OUTS_PER_HALF = 3
    const val GRID = 5
    const val ZONE_FROM = 1
    const val ZONE_TO = 3
    const val HISTORY_KEPT = 24

    const val TIMING_PENALTY = 24
    const val LOCATION_PENALTY = 22
    const val CORNER_PENALTY = 6
    const val OUTSIDE_PENALTY = 14
    const val CONTACT_BONUS = 6
    const val POWER_PENALTY = 6
    const val OUTSIDE_CAP = 74

    fun inZone(row: Int, col: Int): Boolean = row in ZONE_FROM..ZONE_TO && col in ZONE_FROM..ZONE_TO

    private fun corner(row: Int, col: Int) = (row == ZONE_FROM || row == ZONE_TO) && (col == ZONE_FROM || col == ZONE_TO)

    /** The contact quality of [swing] against a [type] pitch landing at ([row], [col]) of the 5x5 grid. */
    fun quality(type: PitchType, row: Int, col: Int, swing: BaseballSwing, variance: Int): Int {
        val timingErr = abs(swing.timing.speed - type.speed)
        val locErr = maxOf(abs(swing.row + 1 - row), abs(swing.col + 1 - col))
        var q = 100 - TIMING_PENALTY * timingErr - LOCATION_PENALTY * locErr - type.movement
        if (inZone(row, col) && corner(row, col)) q -= CORNER_PENALTY
        val inside = inZone(row, col)
        if (!inside) q -= OUTSIDE_PENALTY
        q += if (swing.power) -POWER_PENALTY else CONTACT_BONUS
        q += variance
        if (!inside) q = minOf(q, OUTSIDE_CAP)
        return q
    }

    /** The result of a swing at quality [q]: one of the [BaseballOutcome] swing results. [placement] is 0..99. */
    fun contactOutcome(q: Int, power: Boolean, placement: Int, row: Int): String {
        val weakOut = when {
            row >= 3 -> BaseballOutcome.GROUND_OUT
            row == 2 -> BaseballOutcome.LINE_OUT
            else -> BaseballOutcome.FLY_OUT
        }
        return when {
            q < 25 -> BaseballOutcome.SWING_MISS
            q < 40 -> BaseballOutcome.FOUL
            q < 55 -> weakOut
            q < 75 -> if (placement < (if (power) 40 else 50)) BaseballOutcome.SINGLE else weakOut
            q < 88 -> when {
                placement < (if (power) 35 else 15) -> BaseballOutcome.DOUBLE
                placement < 75 -> BaseballOutcome.SINGLE
                else -> weakOut
            }
            q < 108 -> when {
                power && placement < 60 -> BaseballOutcome.HOME_RUN
                power && placement < 85 -> BaseballOutcome.DOUBLE
                !power && placement < 35 -> BaseballOutcome.DOUBLE
                else -> BaseballOutcome.SINGLE
            }
            else -> BaseballOutcome.HOME_RUN
        }
    }

    /** What a pitch is worth to the BATTER (used by the computer to plan); a rough expectation, not a rule. */
    fun swingValue(q: Int, power: Boolean, row: Int, strikes: Int): Double {
        var sum = 0.0
        for (p in 0 until 100) sum += outcomeValue(contactOutcome(q, power, p, row), strikes)
        return sum / 100.0
    }

    fun outcomeValue(outcome: String, strikes: Int): Double = when (outcome) {
        BaseballOutcome.SWING_MISS -> if (strikes >= 2) -1.0 else -0.35
        BaseballOutcome.FOUL -> if (strikes >= 2) 0.0 else -0.2
        BaseballOutcome.GROUND_OUT, BaseballOutcome.FLY_OUT, BaseballOutcome.LINE_OUT -> -0.45
        BaseballOutcome.SINGLE -> 1.0
        BaseballOutcome.DOUBLE -> 1.9
        BaseballOutcome.HOME_RUN -> 3.2
        else -> 0.0
    }

    fun takeValue(row: Int, col: Int, balls: Int, strikes: Int): Double =
        if (inZone(row, col)) (if (strikes >= 2) -1.0 else -0.35) else (if (balls >= 3) 0.9 else 0.12)

    /**
     * How the computer plays at each level. [adapt] is how strongly it reads the opponent's habits (0 = not at
     * all, 1 = fully); [wild] the chance a pitcher misses its spot by a cell; [zoneBias] how often a pitcher
     * stays in the strike zone when not forced; [noise] the chance it just picks at random; [thinkMs] the base
     * pause before it acts.
     */
    class CpuProfile(val adapt: Double, val wild: Double, val noise: Double, val thinkMs: Int, val takeRate: Double)

    fun profile(level: CpuDifficulty): CpuProfile = when (level) {
        CpuDifficulty.EASY -> CpuProfile(adapt = 0.0, wild = 0.3, noise = 0.8, thinkMs = 900, takeRate = 0.1)
        CpuDifficulty.MEDIUM -> CpuProfile(adapt = 0.5, wild = 0.1, noise = 0.4, thinkMs = 750, takeRate = 0.25)
        CpuDifficulty.HARD -> CpuProfile(adapt = 1.0, wild = 0.0, noise = 0.08, thinkMs = 600, takeRate = 0.3)
    }
}
