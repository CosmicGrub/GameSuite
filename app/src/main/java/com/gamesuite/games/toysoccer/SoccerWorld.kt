package com.gamesuite.games.toysoccer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Toy Soccer's table: constants, formation and the fixed-timestep physics. Pure Kotlin, no clock and
 * no random numbers, so the same flicks always give the same game (the tests replay known sequences,
 * the host's snapshots are enough for a guest to carry on the simulation by itself).
 *
 * The pitch is [W] by [L] units, y grows downwards. Team 0 defends the BOTTOM goal (y = [L]) and
 * attacks the top one, team 1 the other way round. Every body is a circle: index 0 is the ball, then
 * the figures of team 0, then those of team 1 (figure 0 of a team is its keeper).
 */
object SoccerRules {
    const val W = 1000.0
    const val L = 1600.0
    const val CX = W / 2

    const val STEPS_PER_SECOND = 120
    const val DT = 1.0 / STEPS_PER_SECOND
    const val STEP_MS = 1000.0 / STEPS_PER_SECOND

    /** Half the goal mouth between the posts. */
    const val GOAL_HALF = 150.0
    const val GOAL_DEPTH = 90.0
    const val POST_R = 9.0
    const val BALL_R = 24.0
    const val FIG_R = 44.0
    const val KEEPER_R = 50.0
    const val BALL_MASS = 1.0
    const val FIG_MASS = 6.0
    const val KEEPER_MASS = 8.0

    const val MAX_SPEED = 2400.0
    const val MAX_FLICK = 1400.0
    const val FLICK_GAIN = 6.0
    const val MIN_PULL = 24.0
    const val MAX_PULL = MAX_FLICK / FLICK_GAIN

    /** A figure can be flicked once it is slower than this and its recovery is over. */
    const val READY_SPEED = 45.0
    const val COOLDOWN_TICKS = 36
    const val SETTLE_SPEED = 25.0

    const val FIG_DAMP = 2.8
    const val FIG_COULOMB = 220.0
    const val BALL_DAMP = 1.25
    const val BALL_COULOMB = 55.0
    const val KEEPER_DAMP = 2.0
    const val KEEPER_COULOMB = 40.0
    const val KEEPER_K = 34.0
    const val KEEPER_C = 11.0

    const val BALL_WALL_E = 0.80
    const val FIG_WALL_E = 0.45
    const val BALL_FIG_E = 0.80
    const val FIG_FIG_E = 0.55
    const val POST_E = 0.70

    const val COUNTDOWN_TICKS = 270
    const val RESET_TICKS = 50
    const val GOAL_TICKS = 240
    const val TURN_TIMEOUT_TICKS = 900
    const val SETTLE_TICKS = 24

    /** Figures per team: 3 in a one-against-one match, 4 when a team has two players. */
    fun figuresFor(seats: Int): Int = if (seats >= 3) 4 else 3

    fun teamOfSeat(seat: Int): Int = seat % 2

    /** The seats that play for [team] in a roster of [seats] players, in seat order. */
    fun membersOf(team: Int, seats: Int): List<Int> = (0 until seats).filter { it % 2 == team }

    /**
     * The seat that flicks figure [figure] of [team]. With two players on a team the back pair
     * (keeper and first defender) belongs to the first, the front pair to the second. Seats in
     * [gone] (left the match) hand their figures to the team mate that is still there.
     */
    fun controllerSeat(team: Int, figure: Int, seats: Int, gone: Set<Int> = emptySet()): Int {
        val members = membersOf(team, seats)
        if (members.isEmpty()) return -1
        val preferred = if (members.size >= 2 && figuresFor(seats) == 4 && figure >= 2) members[1] else members[0]
        if (preferred !in gone) return preferred
        return members.firstOrNull { it !in gone } ?: preferred
    }

    /** The figures (local indices) seat [seat] flicks. */
    fun figuresOfSeat(seat: Int, seats: Int, gone: Set<Int> = emptySet()): List<Int> =
        (0 until figuresFor(seats)).filter { controllerSeat(teamOfSeat(seat), it, seats, gone) == seat }
}

class SoccerWorld(val figuresPerTeam: Int) {
    val count: Int = 1 + 2 * figuresPerTeam
    val x = DoubleArray(count)
    val y = DoubleArray(count)
    val vx = DoubleArray(count)
    val vy = DoubleArray(count)
    val cooldown = IntArray(count)

    // What the last [step] bumped into, for sound and haptics (0 = nothing). Speeds are along the contact normal.
    var ballFigureImpact = 0.0; private set
    var ballWallImpact = 0.0; private set
    var figureFigureImpact = 0.0; private set
    var impactX = SoccerRules.CX; private set

    init { kickoff() }

    fun team(i: Int): Int = if (i == 0) -1 else (i - 1) / figuresPerTeam
    fun figure(i: Int): Int = if (i == 0) -1 else (i - 1) % figuresPerTeam
    fun isKeeper(i: Int): Boolean = i > 0 && figure(i) == 0
    fun index(team: Int, figure: Int): Int = 1 + team * figuresPerTeam + figure
    fun radius(i: Int): Double = if (i == 0) SoccerRules.BALL_R else if (isKeeper(i)) SoccerRules.KEEPER_R else SoccerRules.FIG_R
    private fun mass(i: Int): Double = if (i == 0) SoccerRules.BALL_MASS else if (isKeeper(i)) SoccerRules.KEEPER_MASS else SoccerRules.FIG_MASS
    fun speed(i: Int): Double = hypot(vx[i], vy[i])
    fun isReady(i: Int): Boolean = i > 0 && cooldown[i] == 0 && speed(i) < SoccerRules.READY_SPEED

    /** True when nothing moves faster than [SoccerRules.SETTLE_SPEED]. */
    fun allSettled(): Boolean = (0 until count).all { speed(it) < SoccerRules.SETTLE_SPEED }

    fun homeX(i: Int): Double {
        if (i == 0) return SoccerRules.CX
        val (hx, _) = baseHome(figure(i))
        return if (team(i) == 0) hx else SoccerRules.W - hx
    }

    fun homeY(i: Int): Double {
        if (i == 0) return SoccerRules.L / 2
        val (_, hy) = baseHome(figure(i))
        return if (team(i) == 0) hy else SoccerRules.L - hy
    }

    /** Where figure [f] of team 0 stands at kick-off (team 1 is the point mirror). */
    private fun baseHome(f: Int): Pair<Double, Double> {
        val c = SoccerRules.CX
        val l = SoccerRules.L
        return if (figuresPerTeam == 3) {
            when (f) { 0 -> c to (l - 72); 1 -> (c - 230) to (l - 420); else -> (c + 230) to (l - 420) }
        } else {
            when (f) { 0 -> c to (l - 72); 1 -> (c - 250) to (l - 330); 2 -> (c + 250) to (l - 330); else -> c to (l - 560) }
        }
    }

    /** Everything back on its starting spot, at rest. */
    fun kickoff() {
        for (i in 0 until count) {
            x[i] = homeX(i); y[i] = homeY(i); vx[i] = 0.0; vy[i] = 0.0; cooldown[i] = 0
        }
        clearImpacts()
    }

    /** One tick of the post-goal reset: bodies slide to their start spots. True when all have arrived. */
    fun glideToKickoff(): Boolean {
        var done = true
        for (i in 0 until count) {
            val dx = homeX(i) - x[i]; val dy = homeY(i) - y[i]
            if (abs(dx) < 0.6 && abs(dy) < 0.6) { x[i] = homeX(i); y[i] = homeY(i) } else {
                x[i] += dx * 0.14; y[i] += dy * 0.14; done = false
            }
            vx[i] = 0.0; vy[i] = 0.0
            if (cooldown[i] > 0) cooldown[i]--
        }
        return done
    }

    /**
     * Flicks figure [i] by the pull vector ([px], [py] units: the launch is in that direction, harder
     * the longer the pull). False when the figure is not ready or the pull is too small or not a number.
     */
    fun flick(i: Int, px: Double, py: Double): Boolean {
        if (i <= 0 || i >= count || !px.isFinite() || !py.isFinite() || !isReady(i)) return false
        val len = hypot(px, py)
        if (len < SoccerRules.MIN_PULL) return false
        val use = minOf(len, SoccerRules.MAX_PULL)
        val speed = use * SoccerRules.FLICK_GAIN
        vx[i] = px / len * speed
        vy[i] = py / len * speed
        cooldown[i] = SoccerRules.COOLDOWN_TICKS
        return true
    }

    private fun clearImpacts() {
        ballFigureImpact = 0.0; ballWallImpact = 0.0; figureFigureImpact = 0.0
    }

    /**
     * Advances one fixed tick. Returns the team that scored this tick (the ball is over the goal line
     * between the posts), or -1. With [detectGoal] false a guest's extrapolation never decides a goal.
     */
    fun step(detectGoal: Boolean = true): Int {
        clearImpacts()
        val dt = SoccerRules.DT
        for (i in 0 until count) {
            if (cooldown[i] > 0) cooldown[i]--
            if (isKeeper(i)) {
                // A keeper hangs on a rubber band around its starting spot.
                vx[i] += (SoccerRules.KEEPER_K * (homeX(i) - x[i]) - SoccerRules.KEEPER_C * vx[i]) * dt
                vy[i] += (SoccerRules.KEEPER_K * (homeY(i) - y[i]) - SoccerRules.KEEPER_C * vy[i]) * dt
            }
            val sp = speed(i)
            if (sp > 0.0) {
                val damp = if (i == 0) SoccerRules.BALL_DAMP else if (isKeeper(i)) SoccerRules.KEEPER_DAMP else SoccerRules.FIG_DAMP
                val coul = if (i == 0) SoccerRules.BALL_COULOMB else if (isKeeper(i)) SoccerRules.KEEPER_COULOMB else SoccerRules.FIG_COULOMB
                var ns = sp * (1.0 - damp * dt) - coul * dt
                if (ns > SoccerRules.MAX_SPEED) ns = SoccerRules.MAX_SPEED
                if (ns <= 0.0) { vx[i] = 0.0; vy[i] = 0.0 } else { val f = ns / sp; vx[i] *= f; vy[i] *= f }
            }
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
        for (a in 0 until count) for (b in a + 1 until count) collide(a, b)
        collidePosts()
        for (i in 0 until count) wall(i)
        if (!detectGoal) return -1
        val bx = x[0]
        if (abs(bx - SoccerRules.CX) < SoccerRules.GOAL_HALF) {
            if (y[0] < -SoccerRules.BALL_R * 0.35) return 0
            if (y[0] > SoccerRules.L + SoccerRules.BALL_R * 0.35) return 1
        }
        return -1
    }

    private fun collide(a: Int, b: Int) {
        val dx = x[b] - x[a]; val dy = y[b] - y[a]
        val rr = radius(a) + radius(b)
        val d2 = dx * dx + dy * dy
        if (d2 >= rr * rr) return
        val d = sqrt(d2)
        val nx: Double; val ny: Double
        if (d < 1e-9) { nx = 1.0; ny = 0.0 } else { nx = dx / d; ny = dy / d }
        val ia = 1.0 / mass(a); val ib = 1.0 / mass(b); val w = ia + ib
        val overlap = rr - d
        x[a] -= nx * overlap * ia / w; y[a] -= ny * overlap * ia / w
        x[b] += nx * overlap * ib / w; y[b] += ny * overlap * ib / w
        val rvn = (vx[b] - vx[a]) * nx + (vy[b] - vy[a]) * ny
        if (rvn >= 0.0) return
        val e = if (a == 0) SoccerRules.BALL_FIG_E else SoccerRules.FIG_FIG_E
        val j = -(1.0 + e) * rvn / w
        vx[a] -= j * ia * nx; vy[a] -= j * ia * ny
        vx[b] += j * ib * nx; vy[b] += j * ib * ny
        val impact = -rvn
        if (a == 0) {
            if (impact > ballFigureImpact) { ballFigureImpact = impact; impactX = x[0] }
        } else if (impact > figureFigureImpact) figureFigureImpact = impact
    }

    private fun collidePosts() {
        val c = SoccerRules.CX
        val gh = SoccerRules.GOAL_HALF
        for (k in 0 until 4) {
            val px = if (k % 2 == 0) c - gh else c + gh
            val py = if (k < 2) 0.0 else SoccerRules.L
            for (i in 0 until count) {
                val dx = x[i] - px; val dy = y[i] - py
                val minD = radius(i) + SoccerRules.POST_R
                val d2 = dx * dx + dy * dy
                if (d2 >= minD * minD || d2 < 1e-12) continue
                val d = sqrt(d2)
                val nx = dx / d; val ny = dy / d
                x[i] = px + nx * minD; y[i] = py + ny * minD
                val vn = vx[i] * nx + vy[i] * ny
                if (vn < 0.0) {
                    vx[i] -= (1.0 + SoccerRules.POST_E) * vn * nx
                    vy[i] -= (1.0 + SoccerRules.POST_E) * vn * ny
                    if (i == 0 && -vn > ballWallImpact) { ballWallImpact = -vn; impactX = x[0] }
                }
            }
        }
    }

    private fun bounceX(i: Int, edge: Double, wallIsLow: Boolean, e: Double) {
        x[i] = edge
        if ((wallIsLow && vx[i] < 0.0) || (!wallIsLow && vx[i] > 0.0)) {
            if (i == 0 && abs(vx[i]) > ballWallImpact) { ballWallImpact = abs(vx[i]); impactX = x[0] }
            vx[i] = -vx[i] * e
        }
    }

    private fun bounceY(i: Int, edge: Double, wallIsLow: Boolean, e: Double) {
        y[i] = edge
        if ((wallIsLow && vy[i] < 0.0) || (!wallIsLow && vy[i] > 0.0)) {
            if (i == 0 && abs(vy[i]) > ballWallImpact) { ballWallImpact = abs(vy[i]); impactX = x[0] }
            vy[i] = -vy[i] * e
        }
    }

    private fun wall(i: Int) {
        val r = radius(i)
        val e = if (i == 0) SoccerRules.BALL_WALL_E else SoccerRules.FIG_WALL_E
        val w = SoccerRules.W; val l = SoccerRules.L; val c = SoccerRules.CX; val gh = SoccerRules.GOAL_HALF
        val inMouth = i == 0 && abs(x[0] - c) < gh
        if (inMouth && (y[0] < r || y[0] > l - r)) {
            // Inside a goal pocket: side walls and a back wall, nothing else.
            val lim = gh - r
            if (x[0] < c - lim) bounceX(0, c - lim, true, 0.3)
            if (x[0] > c + lim) bounceX(0, c + lim, false, 0.3)
            if (y[0] < -SoccerRules.GOAL_DEPTH + r) bounceY(0, -SoccerRules.GOAL_DEPTH + r, true, 0.3)
            if (y[0] > l + SoccerRules.GOAL_DEPTH - r) bounceY(0, l + SoccerRules.GOAL_DEPTH - r, false, 0.3)
            return
        }
        if (x[i] < r) bounceX(i, r, true, e)
        if (x[i] > w - r) bounceX(i, w - r, false, e)
        if (y[i] < r) bounceY(i, r, true, e)
        if (y[i] > l - r) bounceY(i, l - r, false, e)
    }

    fun copyFrom(o: SoccerWorld) {
        require(o.count == count)
        for (i in 0 until count) {
            x[i] = o.x[i]; y[i] = o.y[i]; vx[i] = o.vx[i]; vy[i] = o.vy[i]; cooldown[i] = o.cooldown[i]
        }
    }

    /** A cheap fingerprint of the whole table (positions, speeds, recoveries) for determinism tests. */
    fun fingerprint(): Long {
        var h = 1125899906842597L
        for (i in 0 until count) {
            h = 31 * h + x[i].toRawBits()
            h = 31 * h + y[i].toRawBits()
            h = 31 * h + vx[i].toRawBits()
            h = 31 * h + vy[i].toRawBits()
            h = 31 * h + cooldown[i]
        }
        return h
    }
}
