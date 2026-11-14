package com.gamesuite.games.flickrink

import kotlin.math.ceil

/**
 * Every number of Flick Rink in one place (docs/design/kids-games/flick-rink.md, SCRIPT > Physics constants).
 * Changing any physics number means bumping [PHYSICS_VERSION]: saves and replays from another version are rejected.
 * Only pure helpers live here (power curve, caps, stars); the simulation itself belongs to [FlickRinkEngine].
 */
object FlickRinkRules {
    const val PHYSICS_VERSION = 1
    const val SAVE_VERSION = 1

    // geometry (rink units)
    const val RINK_W = 600
    const val RINK_H = 960
    const val PAL_R = 34.0
    const val FISH_R = 18.0
    const val WALL_HALF = 7.0
    const val HOME_X = 300
    const val HOME_Y = 850
    const val HOME_R = 110
    const val HOME_R_RELAXED = 140
    const val PICKUP_R = 58.0
    const val PICKUP_R_RELAXED = 87.0
    const val DISC_R = 28.0

    // simulation clock
    const val SIM_HZ = 120
    const val DT = 1.0 / SIM_HZ
    const val MAX_STEPS = 900
    const val FRAME_EVERY = 2
    const val MAX_CATCHUP_STEPS = 4

    // motion
    const val V_MIN = 140.0
    const val V_MAX = 900.0
    const val DAMPING_PER_SECOND = 1.10
    const val COULOMB_DECEL = 20.0
    const val DRIFT_DAMPING_FACTOR = 3.0
    const val STOP_SPEED = 8.0
    const val STOP_HOLD_STEPS = 12

    // collisions
    const val E_WALL = 0.80
    const val E_WALL_RELAXED = 0.60
    const val E_POST = 0.90
    const val E_PAL = 0.92
    const val RELAY_MIN_BUMP_SPEED = 30.0

    // drag to flick
    const val DRAG_DEAD = 16.0
    const val DRAG_MAX = 200.0

    // bullseye
    const val BULL_R = 40.0
    const val MID_R = 90.0
    const val OUT_R = 150.0
    const val DISCS_PER_PLAYER = 3

    // match shape
    const val FISH_PER_RINK = 3
    const val MAX_SEATS = 4
    /** Solo and Relaxed have no flick cap; a rink still ends after this many flicks of one seat (safety valve). */
    const val SAFETY_FLICKS_PER_SEAT = 60
    const val COOP_RETRY_BONUS = 2
    const val COOP_RETRY_BONUS_CAP = 6

    /** The flick power curve: speed in units per second for [power] 0..100. Integer math in, no pow(), so it is exact everywhere. */
    fun speedForPower(power: Int): Double {
        val p = power.coerceIn(0, 100)
        return V_MIN + (V_MAX - V_MIN) * (p * p) / 10_000.0
    }

    /** Drag length in rink units to power 0..100, or -1 for "too small, cancel the flick". */
    fun dragToPower(dragUnits: Double): Int {
        if (dragUnits < DRAG_DEAD) return -1
        val t = ((dragUnits - DRAG_DEAD) / (DRAG_MAX - DRAG_DEAD)).coerceIn(0.0, 1.0)
        return Math.round(t * 100.0).toInt()
    }

    fun isInRange(input: FlickInput): Boolean = input.angleDeci in 0..3599 && input.power in 0..100

    /** Flicks each player may use on one rink in a turn-taking match: 12, 8 and 6 for 2, 3 and 4 players (about 24 in all). */
    fun flickCapPerPlayer(players: Int): Int = when {
        players <= 2 -> 12
        players == 3 -> 8
        else -> 6
    }

    /** The team's flick budget on a rink in co-op: par x band multiplier x budget feel, rounded up, plus [retryBonus]. */
    fun coopBudget(par: Int, band: FlickBand, budget: String, retryBonus: Int): Int {
        val feel = when (budget) {
            "plenty" -> 1.25
            "tight" -> 0.8
            else -> 1.0
        }
        return ceil(par * band.coopBudgetMultiplier * feel).toInt() + retryBonus.coerceIn(0, COOP_RETRY_BONUS_CAP)
    }

    /** Bonus flicks after [failedTries] failed tries of the same rink: +2 each, capped at +6. */
    fun retryBonus(failedTries: Int): Int = (failedTries * COOP_RETRY_BONUS).coerceIn(0, COOP_RETRY_BONUS_CAP)

    /** Solo stars for clearing a rink in [flicks]: 3 at ceil(par x 1.25), 2 at ceil(par x 1.75), otherwise 1. */
    fun soloStars(flicks: Int, par: Int): Int = when {
        flicks <= ceil(par * 1.25).toInt() -> 3
        flicks <= ceil(par * 1.75).toInt() -> 2
        else -> 1
    }

    /** Bullseye ring points for a disc resting with its centre [distance] from the target centre: 5, 3, 1 or 0. */
    fun bullseyePoints(distance: Double): Int = when {
        distance <= BULL_R -> 5
        distance <= MID_R -> 3
        distance <= OUT_R -> 1
        else -> 0
    }

    /** The seats that get the aim helper for an `assistFor` value ("none", "all", "p1".."p4"). */
    fun assistSeats(assistFor: String, seatCount: Int): Set<Int> = when {
        assistFor == "all" -> (0 until seatCount).toSet()
        assistFor.length == 2 && assistFor[0] == 'p' && assistFor[1] in '1'..'4' ->
            setOf(assistFor[1] - '1').filter { it < seatCount }.toSet()
        else -> emptySet()
    }
}
