package com.gamesuite.games.carrom

import kotlin.math.sqrt

/** One coin lying on the board. [id] 0..8 white, 9..17 black, 18 the queen. Positions are millimetres. */
data class CarromCoin(val id: Int, val x: Double, val y: Double)

/**
 * The geometry and constants of the board. Everything is in millimetres on a 740 x 740 playing
 * surface with the origin at the top-left corner (y grows downward). Pockets are in the four corners.
 */
object CarromBoard {
    const val SIZE = 740.0
    const val CENTER = SIZE / 2
    const val COIN_R = 15.1
    const val STRIKER_R = 20.6
    const val POCKET_R = 26.0
    const val POCKET_INSET = 5.0

    const val COINS_PER_TEAM = 9
    const val QUEEN_ID = 18
    const val STRIKER_ID = 19
    const val COIN_COUNT = 19

    /** Distance from the board edge to the striker's baseline, and the half length of the part of it a striker may stand on. */
    const val BASE_OFFSET = 105.0
    const val BASE_HALF = 220.0

    const val MAX_SHOTS = 240

    val pocketX = doubleArrayOf(POCKET_INSET, SIZE - POCKET_INSET, POCKET_INSET, SIZE - POCKET_INSET)
    val pocketY = doubleArrayOf(POCKET_INSET, POCKET_INSET, SIZE - POCKET_INSET, SIZE - POCKET_INSET)

    /** 0 white, 1 black, 2 queen. */
    fun colourOf(id: Int): Int = if (id < COINS_PER_TEAM) 0 else if (id < 2 * COINS_PER_TEAM) 1 else 2

    fun idsOf(team: Int): IntRange = if (team == 0) 0..8 else 9..17

    /** Rotates the board-frame vector (dx, dy) by [quarters] x 90 degrees anticlockwise as seen on screen (y down). */
    fun rotate(quarters: Int, dx: Double, dy: Double): Pair<Double, Double> {
        var x = dx
        var y = dy
        repeat(((quarters % 4) + 4) % 4) {
            val nx = y
            val ny = -x
            x = nx
            y = ny
        }
        return x to y
    }

    /** The striker's centre for [side] (0 bottom, 1 right, 2 top, 3 left) at placement [t] (0..1000, left to right as that player sees it). */
    fun strikerStart(side: Int, t: Int): Pair<Double, Double> {
        val lx = -BASE_HALF + 2 * BASE_HALF * t.coerceIn(0, 1000) / 1000.0
        val ly = -(CENTER - BASE_OFFSET) // the near baseline, in the shooter's frame (forward is +ly)
        val (bx, by) = rotate(side, lx, -ly)
        return (CENTER + bx) to (CENTER + by)
    }

    /** The unit board-frame direction of a shot aimed [aim] hundredths of a degree to the right of straight ahead for [side]. */
    fun shotDirection(side: Int, aim: Int): Pair<Double, Double> {
        val a = Math.toRadians(aim / 100.0)
        val lx = StrictMath.sin(a)
        val lf = StrictMath.cos(a)
        return rotate(side, lx, -lf)
    }

    /** The striker speed (mm/s) of a shot with [power] 1..1000. */
    fun speedOf(power: Int): Double = CarromPhysics.MIN_SPEED + (CarromPhysics.MAX_SPEED - CarromPhysics.MIN_SPEED) * power.coerceIn(0, 1000) / 1000.0

    /** The nearest-to-centre free spot (no coin closer than [clearance] to it) around the centre, for returning a coin. */
    fun freeSpot(coins: List<CarromCoin>, clearance: Double = 2 * COIN_R + 0.5): Pair<Double, Double> {
        fun free(x: Double, y: Double) = coins.none { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < clearance * clearance }
        if (free(CENTER, CENTER)) return CENTER to CENTER
        for (ring in 1..12) {
            val radius = ring * (clearance + 0.5)
            val count = 6 * ring
            for (k in 0 until count) {
                val a = 2.0 * Math.PI * k / count + 0.5
                val x = CENTER + StrictMath.cos(a) * radius
                val y = CENTER + StrictMath.sin(a) * radius
                if (free(x, y)) return x to y
            }
        }
        return CENTER to CENTER
    }

    /** The opening rosette: the queen in the middle, six coins around her, twelve in the outer ring, white and black alternating. */
    fun openingCoins(): List<CarromCoin> {
        val gap = 2 * COIN_R + 0.3
        val out = ArrayList<CarromCoin>()
        out += CarromCoin(QUEEN_ID, CENTER, CENTER)
        var white = 0
        var black = 9
        fun add(colour: Int, x: Double, y: Double) {
            out += CarromCoin(if (colour == 0) white++ else black++, x, y)
        }
        for (k in 0 until 6) {
            val a = Math.PI / 2 + k * Math.PI / 3
            add(k % 2, CENTER + StrictMath.cos(a) * gap, CENTER + StrictMath.sin(a) * gap)
        }
        val pattern = intArrayOf(0, 1, 1, 0)
        for (k in 0 until 12) {
            val a = Math.PI / 2 + k * Math.PI / 6
            val radius = if (k % 2 == 0) 2 * gap else gap * sqrt(3.0)
            add(pattern[k % 4], CENTER + StrictMath.cos(a) * radius, CENTER + StrictMath.sin(a) * radius)
        }
        return out.sortedBy { it.id }
    }
}

class CarromSimEvent(val step: Int, val kind: Int, val id: Int, val other: Int, val x: Float, val y: Float, val speed: Float) {
    companion object {
        const val HIT = 0
        const val WALL = 1
        const val POCKET = 2
    }
}

class CarromSimResult(
    /** The coins still on the board when everything stopped, in id order. */
    val coins: List<CarromCoin>,
    /** Ids of the coins that fell into a pocket, in order. */
    val pocketed: List<Int>,
    val strikerPocketed: Boolean,
    val steps: Int,
    val events: List<CarromSimEvent>,
    /** The id of each disc in a frame, in order: the coins as given, then the striker. */
    val discIds: IntArray,
    /** Positions sampled every [CarromPhysics.FRAME_STEPS] steps: x, y, alive (1 or 0) per disc. Empty unless recorded. */
    val frames: List<FloatArray>
)

/**
 * Deterministic disc physics: a fixed timestep of 1/480 s, constant sliding friction plus a little
 * viscous drag, elastic disc collisions with restitution below one, bouncing walls, and corner
 * pockets. Nothing here is random and nothing depends on the clock, so the same board and shot always
 * play out the same. The step cannot let a disc cross another: the fastest disc moves 5 mm a step,
 * a third of a coin's radius.
 */
object CarromPhysics {
    const val DT = 1.0 / 480.0
    const val MAX_STEPS = 480 * 12
    const val FRAME_STEPS = 8
    const val DECEL = 900.0
    const val VISCOUS = 0.35
    const val V_STOP = 6.0
    const val E_DISC = 0.94
    const val E_WALL = 0.72
    const val MIN_SPEED = 250.0
    const val MAX_SPEED = 2400.0
    const val MASS_COIN = 1.0
    const val MASS_STRIKER = 3.0

    fun simulate(coins: List<CarromCoin>, startX: Double, startY: Double, vx: Double, vy: Double, record: Boolean = false): CarromSimResult {
        val sim = CarromSim(coins, startX, startY, vx, vy)
        return sim.runToEnd(record)
    }
}

/** A steppable simulation, so tests (and the replay) can look at every step. */
class CarromSim(coins: List<CarromCoin>, startX: Double, startY: Double, startVx: Double, startVy: Double) {
    val n = coins.size + 1
    val ids = IntArray(n)
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    val radius = DoubleArray(n)
    val invMass = DoubleArray(n)
    val alive = BooleanArray(n)
    var steps = 0
        private set
    private val pocketed = ArrayList<Int>()
    var strikerPocketed = false
        private set
    private val events = ArrayList<CarromSimEvent>()

    init {
        coins.forEachIndexed { i, c ->
            ids[i] = c.id; x[i] = c.x; y[i] = c.y
            radius[i] = CarromBoard.COIN_R
            invMass[i] = 1.0 / CarromPhysics.MASS_COIN
            alive[i] = true
        }
        val s = n - 1
        ids[s] = CarromBoard.STRIKER_ID
        x[s] = startX; y[s] = startY; vx[s] = startVx; vy[s] = startVy
        radius[s] = CarromBoard.STRIKER_R
        invMass[s] = 1.0 / CarromPhysics.MASS_STRIKER
        alive[s] = true
    }

    fun kineticEnergy(): Double {
        var e = 0.0
        for (i in 0 until n) if (alive[i]) e += 0.5 / invMass[i] * (vx[i] * vx[i] + vy[i] * vy[i])
        return e
    }

    fun isMoving(): Boolean {
        for (i in 0 until n) if (alive[i] && (vx[i] != 0.0 || vy[i] != 0.0)) return true
        return false
    }

    /** The deepest overlap (mm) between two discs still on the board; 0 when none overlap. */
    fun deepestOverlap(): Double {
        var worst = 0.0
        for (i in 0 until n) {
            if (!alive[i]) continue
            for (j in i + 1 until n) {
                if (!alive[j]) continue
                val d = sqrt((x[j] - x[i]) * (x[j] - x[i]) + (y[j] - y[i]) * (y[j] - y[i]))
                val pen = radius[i] + radius[j] - d
                if (pen > worst) worst = pen
            }
        }
        return worst
    }

    fun step() {
        steps++
        val dt = CarromPhysics.DT
        for (i in 0 until n) {
            if (!alive[i]) continue
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (sp == 0.0) continue
            var ns = (sp - CarromPhysics.DECEL * dt) * (1.0 - CarromPhysics.VISCOUS * dt)
            if (ns < CarromPhysics.V_STOP) ns = 0.0
            val k = ns / sp
            vx[i] *= k; vy[i] *= k
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
        for (i in 0 until n) if (alive[i] && (vx[i] != 0.0 || vy[i] != 0.0)) wall(i)
        for (pass in 0 until 2) {
            for (i in 0 until n) {
                if (!alive[i]) continue
                for (j in i + 1 until n) {
                    if (!alive[j]) continue
                    if (vx[i] == 0.0 && vy[i] == 0.0 && vx[j] == 0.0 && vy[j] == 0.0) continue
                    pair(i, j, pass == 0)
                }
            }
        }
        for (i in 0 until n) if (alive[i] && (vx[i] != 0.0 || vy[i] != 0.0)) wall(i)
        for (i in 0 until n) {
            if (!alive[i]) continue
            for (p in 0 until 4) {
                val dx = x[i] - CarromBoard.pocketX[p]
                val dy = y[i] - CarromBoard.pocketY[p]
                if (dx * dx + dy * dy < CarromBoard.POCKET_R * CarromBoard.POCKET_R) {
                    alive[i] = false
                    if (i == n - 1) strikerPocketed = true else pocketed += ids[i]
                    vx[i] = 0.0; vy[i] = 0.0
                    addEvent(CarromSimEvent.POCKET, ids[i], p, x[i], y[i], 0.0)
                    break
                }
            }
        }
    }

    private fun addEvent(kind: Int, id: Int, other: Int, px: Double, py: Double, speed: Double) {
        if (events.size < 600) events += CarromSimEvent(steps, kind, id, other, px.toFloat(), py.toFloat(), speed.toFloat())
    }

    private fun wall(i: Int) {
        val r = radius[i]
        val lo = r
        val hi = CarromBoard.SIZE - r
        var hit = 0.0
        if (x[i] < lo) { x[i] = lo; if (vx[i] < 0) { hit = -vx[i]; vx[i] = -vx[i] * CarromPhysics.E_WALL } }
        else if (x[i] > hi) { x[i] = hi; if (vx[i] > 0) { hit = vx[i]; vx[i] = -vx[i] * CarromPhysics.E_WALL } }
        if (y[i] < lo) { y[i] = lo; if (vy[i] < 0) { hit = maxOf(hit, -vy[i]); vy[i] = -vy[i] * CarromPhysics.E_WALL } }
        else if (y[i] > hi) { y[i] = hi; if (vy[i] > 0) { hit = maxOf(hit, vy[i]); vy[i] = -vy[i] * CarromPhysics.E_WALL } }
        if (hit > 30.0) addEvent(CarromSimEvent.WALL, ids[i], -1, x[i], y[i], hit)
    }

    private fun pair(i: Int, j: Int, report: Boolean) {
        val dx = x[j] - x[i]
        val dy = y[j] - y[i]
        val rr = radius[i] + radius[j]
        val d2 = dx * dx + dy * dy
        if (d2 >= rr * rr) return
        val d = sqrt(d2)
        val nx: Double
        val ny: Double
        if (d < 1e-9) { nx = 1.0; ny = 0.0 } else { nx = dx / d; ny = dy / d }
        val rvn = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
        val sum = invMass[i] + invMass[j]
        if (rvn < 0) {
            val imp = -(1.0 + CarromPhysics.E_DISC) * rvn / sum
            vx[i] -= imp * invMass[i] * nx; vy[i] -= imp * invMass[i] * ny
            vx[j] += imp * invMass[j] * nx; vy[j] += imp * invMass[j] * ny
            if (report && -rvn > 30.0) addEvent(CarromSimEvent.HIT, ids[i], ids[j], x[i] + dx / 2, y[i] + dy / 2, -rvn)
        }
        val pen = rr - d + 1e-7
        x[i] -= nx * pen * invMass[i] / sum; y[i] -= ny * pen * invMass[i] / sum
        x[j] += nx * pen * invMass[j] / sum; y[j] += ny * pen * invMass[j] / sum
    }

    private fun frame(): FloatArray {
        val f = FloatArray(n * 3)
        for (i in 0 until n) {
            f[i * 3] = x[i].toFloat(); f[i * 3 + 1] = y[i].toFloat(); f[i * 3 + 2] = if (alive[i]) 1f else 0f
        }
        return f
    }

    fun runToEnd(record: Boolean): CarromSimResult {
        val frames = ArrayList<FloatArray>()
        if (record) frames += frame()
        while (steps < CarromPhysics.MAX_STEPS && isMoving()) {
            step()
            if (record && steps % CarromPhysics.FRAME_STEPS == 0) frames += frame()
        }
        for (i in 0 until n) { vx[i] = 0.0; vy[i] = 0.0 }
        if (record) frames += frame()
        val rest = ArrayList<CarromCoin>()
        for (i in 0 until n - 1) if (alive[i]) rest += CarromCoin(ids[i], x[i], y[i])
        rest.sortBy { it.id }
        return CarromSimResult(rest, pocketed.toList(), strikerPocketed, steps, events.toList(), ids.copyOf(), frames)
    }
}
