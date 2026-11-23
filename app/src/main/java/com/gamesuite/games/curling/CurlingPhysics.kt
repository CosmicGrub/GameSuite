package com.gamesuite.games.curling

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** A stone lying on the ice. [id] is unique within an end (team * 8 + the team's stone number). Units are centimetres. */
data class CurlingStone(val id: Int, val team: Int, val x: Double, val y: Double)

/**
 * One delivery. [aim] is where the line points on the tee line, in tenths of a centimetre either side of the centre line;
 * [weight] 0..1000 is how hard it is thrown; [curl] is the handle: +1 curls toward the thrower's right, -1 to the left.
 * [sweeps] is a flat list of tick pairs (start, end) during which the thrown stone was being swept (240 ticks a second).
 */
@Serializable
data class CurlingThrow(val aim: Int, val weight: Int, val curl: Int, val sweeps: List<Int> = emptyList())

/** The sheet. y runs up the ice from the near hog line (0) where the stone is released; x is across, 0 on the centre line. */
object CurlingSheet {
    const val HALF_WIDTH = 237.5
    const val STONE_R = 14.5
    const val HOG_FAR = 1100.0
    const val TEE_Y = 1420.0
    const val BACK_Y = 1603.0
    const val HOUSE_R = 183.0
    const val RING_8 = 122.0
    const val RING_4 = 61.0
    const val BUTTON_R = 15.0

    /** The area a stone can be in play in: the far hog line out to the back line. */
    fun distanceToButton(x: Double, y: Double) = sqrt(x * x + (y - TEE_Y) * (y - TEE_Y))
    fun inHouse(x: Double, y: Double) = distanceToButton(x, y) < HOUSE_R + STONE_R

    fun speedOf(weight: Int): Double = CurlingPhysics.MIN_SPEED + CurlingPhysics.SPEED_RANGE * weight.coerceIn(0, 1000) / 1000.0

    fun validSweeps(s: List<Int>): Boolean {
        if (s.size % 2 != 0 || s.size > 2 * CurlingPhysics.MAX_SWEEPS) return false
        var last = 0
        for (i in s.indices step 2) {
            if (s[i] < last || s[i + 1] <= s[i] || s[i + 1] > CurlingPhysics.MAX_STEPS) return false
            last = s[i + 1]
        }
        return true
    }

    fun inRange(t: CurlingThrow) = t.aim in -2200..2200 && t.weight in 0..1000 && (t.curl == 1 || t.curl == -1) && validSweeps(t.sweeps)

    fun sweptAt(sweeps: List<Int>, tick: Int): Boolean {
        for (i in sweeps.indices step 2) if (tick >= sweeps[i] && tick < sweeps[i + 1]) return true
        return false
    }

    /** A throw that is swept the whole way. */
    val fullSweep: List<Int> = listOf(0, CurlingPhysics.MAX_STEPS)
}

class CurlingSimEvent(val step: Int, val kind: Int, val speed: Float, val x: Float, val y: Float) {
    companion object {
        const val HIT = 0
        const val OUT = 1
    }
}

class CurlingSimResult(
    /** The stones that stayed in play, in the order given with the thrown stone last. */
    val stones: List<CurlingStone>,
    /** Whether the thrown stone itself is still in play. */
    val thrownInPlay: Boolean,
    /** True when the thrown stone stopped short of the far hog line (and was taken off). */
    val hogged: Boolean,
    /** True when the thrown stone touched another stone. */
    val hitSomething: Boolean,
    /** Ids of other stones that left play. */
    val removedIds: List<Int>,
    val steps: Int,
    val events: List<CurlingSimEvent>,
    /** Ids per frame slot (the stones given, then the thrown one). */
    val ids: IntArray,
    /** x, y, alive per stone every [CurlingPhysics.FRAME_STEPS] ticks; empty unless recorded. */
    val frames: List<FloatArray>
)

/**
 * Deterministic ice physics at 240 ticks a second. A stone slows at a constant rate (less while swept), drifts sideways
 * in the direction of its handle more as it slows (less while swept), and collides elastically-ish with other stones.
 * A stone that touches a side line or goes fully over the back line leaves play; at the end, any stone stopped short
 * of the far hog line is taken off. No randomness and no clock.
 */
object CurlingPhysics {
    const val DT = 1.0 / 240.0
    const val MAX_STEPS = 240 * 16
    const val FRAME_STEPS = 4
    const val MAX_SWEEPS = 16
    const val FRICTION = 100.0
    const val SWEEP_FRICTION = 0.82
    const val CURL_ACCEL = 8.0
    const val SWEEP_CURL = 0.45
    const val CURL_REF_SPEED = 700.0
    const val E_STONE = 0.9
    const val V_STOP = 3.0
    const val MIN_SPEED = 300.0
    const val SPEED_RANGE = 650.0

    fun simulate(stones: List<CurlingStone>, team: Int, throwId: Int, t: CurlingThrow, record: Boolean = false): CurlingSimResult =
        CurlingSim(stones, team, throwId, t).runToEnd(record)

    /** A lone stone's whole path (no other stones), as points every few ticks: for the aiming preview and the solver. */
    fun lonePath(t: CurlingThrow): List<DoubleArray> {
        val sim = CurlingSim(emptyList(), 0, 0, t)
        val out = ArrayList<DoubleArray>()
        out += doubleArrayOf(sim.x[0], sim.y[0])
        while (sim.steps < MAX_STEPS && sim.isMoving()) {
            sim.step(CurlingSheet.sweptAt(t.sweeps, sim.steps))
            if (sim.steps % 6 == 0) out += doubleArrayOf(sim.x[0], sim.y[0])
        }
        out += doubleArrayOf(sim.x[0], sim.y[0])
        return out
    }

    /**
     * The thrown stone's path among the stones already on the ice, up to the moment it first touches one (or to where it stops):
     * points every few ticks, and whether it touched something. This is what the aiming preview draws.
     */
    fun previewPath(stones: List<CurlingStone>, team: Int, throwId: Int, t: CurlingThrow): Pair<List<DoubleArray>, Boolean> {
        val sim = CurlingSim(stones, team, throwId, t)
        val k = sim.n - 1
        val out = ArrayList<DoubleArray>()
        out += doubleArrayOf(sim.x[k], sim.y[k])
        while (sim.steps < MAX_STEPS && sim.isMoving() && sim.alive[k]) {
            sim.step(CurlingSheet.sweptAt(t.sweeps, sim.steps))
            if (sim.thrownHit) { out += doubleArrayOf(sim.x[k], sim.y[k]); return out to true }
            if (sim.steps % 6 == 0) out += doubleArrayOf(sim.x[k], sim.y[k])
        }
        out += doubleArrayOf(sim.x[k], sim.y[k])
        return out to false
    }

    /** Where a lone stone comes to rest, ignoring other stones and side lines. */
    fun loneStop(t: CurlingThrow): DoubleArray {
        val sim = CurlingSim(emptyList(), 0, 0, t)
        while (sim.steps < MAX_STEPS && sim.isMoving()) sim.step(CurlingSheet.sweptAt(t.sweeps, sim.steps), ignoreBounds = true)
        return doubleArrayOf(sim.x[0], sim.y[0])
    }

    /** The x a lone stone has when it first reaches y = [y], or null if it stops before. */
    fun loneXAt(t: CurlingThrow, y: Double): Double? {
        val sim = CurlingSim(emptyList(), 0, 0, t)
        while (sim.steps < MAX_STEPS && sim.isMoving()) {
            val py = sim.y[0]
            sim.step(CurlingSheet.sweptAt(t.sweeps, sim.steps), ignoreBounds = true)
            if (py < y && sim.y[0] >= y) {
                val f = (y - py) / (sim.y[0] - py)
                return sim.px + (sim.x[0] - sim.px) * f
            }
        }
        return null
    }

    /**
     * The throw that sends a lone stone along a line passing (tx, ty) at the given [weight]: the aim is adjusted until the stone's
     * x at height ty matches tx. Returns null when the stone cannot get that far.
     */
    fun solveLine(tx: Double, ty: Double, weight: Int, curl: Int, sweeps: List<Int> = emptyList()): CurlingThrow? {
        var aim = (tx * 10).toInt().coerceIn(-2200, 2200)
        var best: CurlingThrow? = null
        repeat(5) {
            val t = CurlingThrow(aim, weight, curl, sweeps)
            val x = loneXAt(t, ty) ?: return null
            best = t
            val err = tx - x
            if (abs(err) < 0.8) return t
            aim = (aim + err * 10).toInt().coerceIn(-2200, 2200)
        }
        return best
    }

    /** The throw that makes a lone stone come to rest at (tx, ty): weight by bisection on the stopping height, aim by [solveLine]. */
    fun solveDraw(tx: Double, ty: Double, curl: Int, sweeps: List<Int> = emptyList()): CurlingThrow? {
        var lo = 0
        var hi = 1000
        var t: CurlingThrow? = null
        repeat(5) {
            // the line is solved at the target height; after the weight changes, solve again
            for (k in 0 until 11) {
                val mid = (lo + hi) / 2
                val s = solveLine(tx, ty, mid, curl, sweeps)
                val stopY = s?.let { loneStop(it)[1] } ?: -1.0
                if (stopY < ty) lo = mid + 1 else hi = mid
                if (lo >= hi) break
            }
            t = solveLine(tx, ty, lo.coerceIn(0, 1000), curl, sweeps)
            return t
        }
        return t
    }
}

/** A steppable ice simulation: stone 0..n-2 are the ones given, the last is the thrown one. */
class CurlingSim(stones: List<CurlingStone>, val team: Int, throwId: Int, private val t: CurlingThrow) {
    val n = stones.size + 1
    val ids = IntArray(n)
    val teams = IntArray(n)
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    val spin = IntArray(n)
    val alive = BooleanArray(n)
    var steps = 0
        private set
    var px = 0.0
        private set
    private val removed = ArrayList<Int>()
    var thrownHit = false
        private set
    private val events = ArrayList<CurlingSimEvent>()

    /** Collisions and exits so far (the live delivery plays their sounds). */
    val eventList: List<CurlingSimEvent> get() = events

    init {
        stones.forEachIndexed { i, s -> ids[i] = s.id; teams[i] = s.team; x[i] = s.x; y[i] = s.y; alive[i] = true }
        val k = n - 1
        ids[k] = throwId; teams[k] = team; x[k] = 0.0; y[k] = 0.0; alive[k] = true
        val dx = t.aim / 10.0
        val dy = CurlingSheet.TEE_Y
        val len = sqrt(dx * dx + dy * dy)
        val v = CurlingSheet.speedOf(t.weight)
        vx[k] = dx / len * v; vy[k] = dy / len * v
        spin[k] = t.curl
    }

    fun isMoving(): Boolean {
        for (i in 0 until n) if (alive[i] && (vx[i] != 0.0 || vy[i] != 0.0)) return true
        return false
    }

    fun kineticEnergy(): Double {
        var e = 0.0
        for (i in 0 until n) if (alive[i]) e += 0.5 * (vx[i] * vx[i] + vy[i] * vy[i])
        return e
    }

    fun step(sweeping: Boolean, ignoreBounds: Boolean = false) {
        steps++
        val dt = CurlingPhysics.DT
        px = x[n - 1]
        for (i in 0 until n) {
            if (!alive[i]) continue
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (sp == 0.0) continue
            val swept = sweeping && i == n - 1
            val fr = CurlingPhysics.FRICTION * (if (swept) CurlingPhysics.SWEEP_FRICTION else 1.0)
            var ns = sp - fr * dt
            // sideways drift of a handled stone, building as it slows
            var nvx = vx[i]
            var nvy = vy[i]
            if (spin[i] != 0 && ns > 0) {
                val ramp = 0.25 + 0.75 * (1.0 - minOf(sp / CurlingPhysics.CURL_REF_SPEED, 1.0))
                val fade = minOf(1.0, sp / 25.0)
                val a = spin[i] * CurlingPhysics.CURL_ACCEL * ramp * fade * (if (swept) CurlingPhysics.SWEEP_CURL else 1.0)
                nvx += (vy[i] / sp) * a * dt
                nvy += (-vx[i] / sp) * a * dt
                val m = sqrt(nvx * nvx + nvy * nvy)
                if (m > 0) { nvx = nvx / m * sp; nvy = nvy / m * sp }
            }
            if (ns < CurlingPhysics.V_STOP) { ns = 0.0 }
            val k = if (sp > 0) ns / sp else 0.0
            vx[i] = nvx * k; vy[i] = nvy * k
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
        }
        val r2 = 2 * CurlingSheet.STONE_R
        for (i in 0 until n) {
            if (!alive[i]) continue
            for (j in i + 1 until n) {
                if (!alive[j]) continue
                if (vx[i] == 0.0 && vy[i] == 0.0 && vx[j] == 0.0 && vy[j] == 0.0) continue
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= r2 * r2) continue
                val d = sqrt(d2)
                val nx = if (d < 1e-9) 1.0 else dx / d
                val ny = if (d < 1e-9) 0.0 else dy / d
                val rvn = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                if (rvn < 0) {
                    val imp = -(1.0 + CurlingPhysics.E_STONE) * rvn / 2.0
                    vx[i] -= imp * nx; vy[i] -= imp * ny
                    vx[j] += imp * nx; vy[j] += imp * ny
                    spin[i] = 0; spin[j] = 0
                    if (events.size < 200) events += CurlingSimEvent(steps, CurlingSimEvent.HIT, (-rvn).toFloat(), (x[i] + dx / 2).toFloat(), (y[i] + dy / 2).toFloat())
                    if (i == n - 1 || j == n - 1) thrownHit = true
                }
                val pen = (r2 - d + 1e-7) / 2.0
                x[i] -= nx * pen; y[i] -= ny * pen
                x[j] += nx * pen; y[j] += ny * pen
            }
        }
        if (!ignoreBounds) {
            for (i in 0 until n) {
                if (!alive[i]) continue
                val out = abs(x[i]) + CurlingSheet.STONE_R > CurlingSheet.HALF_WIDTH || y[i] - CurlingSheet.STONE_R > CurlingSheet.BACK_Y
                if (out) {
                    alive[i] = false
                    vx[i] = 0.0; vy[i] = 0.0
                    if (i != n - 1) removed += ids[i]
                    if (events.size < 200) events += CurlingSimEvent(steps, CurlingSimEvent.OUT, 0f, x[i].toFloat(), y[i].toFloat())
                }
            }
        }
    }

    private fun frame(): FloatArray {
        val f = FloatArray(n * 3)
        for (i in 0 until n) { f[i * 3] = x[i].toFloat(); f[i * 3 + 1] = y[i].toFloat(); f[i * 3 + 2] = if (alive[i]) 1f else 0f }
        return f
    }

    fun runToEnd(record: Boolean): CurlingSimResult {
        val frames = ArrayList<FloatArray>()
        if (record) frames += frame()
        while (steps < CurlingPhysics.MAX_STEPS && isMoving()) {
            step(CurlingSheet.sweptAt(t.sweeps, steps))
            if (record && steps % CurlingPhysics.FRAME_STEPS == 0) frames += frame()
        }
        for (i in 0 until n) { vx[i] = 0.0; vy[i] = 0.0 }
        return finish(frames, record)
    }

    /** Wraps up a simulation that was stepped by hand (the live delivery). */
    fun finish(frames: MutableList<FloatArray> = ArrayList(), record: Boolean = false): CurlingSimResult {
        for (i in 0 until n) { vx[i] = 0.0; vy[i] = 0.0 }
        val k = n - 1
        // stones stopped short of the far hog line come off the ice
        var hogged = false
        for (i in 0 until n) {
            if (alive[i] && y[i] - CurlingSheet.STONE_R < CurlingSheet.HOG_FAR) {
                alive[i] = false
                if (i == k) hogged = true else removed += ids[i]
            }
        }
        if (record) frames += frame()
        val rest = ArrayList<CurlingStone>()
        for (i in 0 until n) if (alive[i]) rest += CurlingStone(ids[i], teams[i], x[i], y[i])
        return CurlingSimResult(rest, alive[k], hogged, thrownHit, removed.toList(), steps, events.toList(), ids.copyOf(), frames)
    }
}
