package com.gamesuite.games.tankbattle

import kotlinx.serialization.Serializable
import kotlin.random.Random

/*
 * Tank Battle rules: a deterministic artillery simulation on a destructible height-map.
 *
 * Everything here is pure Kotlin (no Android, no Compose) and uses only IEEE arithmetic plus
 * StrictMath for trigonometry, so the same inputs give bit-identical results on every device. In a
 * networked match only the host runs it anyway: guests receive the finished shot (its path, its
 * craters and the new terrain), they never re-simulate.
 *
 * World: x runs 0 until W (one terrain column per unit), y runs up from 0 to H. A tank stands on its
 * column's height. Angles are degrees, 0 = right, 90 = straight up, 180 = left. Power is 5..100 percent
 * of the muzzle speed.
 */
object TankWorld {
    const val W = 320
    const val H = 240
    const val TANK_R = 7.0
    const val TANK_RISE = 4.0
    const val GRAVITY = 70.0
    const val V_MAX = 200.0
    const val DT = 1.0 / 60.0
    const val MAX_STEPS = 2400
    const val WIND_MAX = 8
    const val WIND_ACCEL = 1.5
    const val START_HP = 100
    const val MUZZLE = 9.0
    const val POWER_MIN = 5
    const val POWER_MAX = 100
    const val ANGLE_MAX = 180
    const val SELF_IMMUNE_STEPS = 10
    const val MAX_PATH_POINTS = 1300
    const val MAX_IMPACTS = 6

    /** The wind of turn [turn]: a whole number from -WIND_MAX to WIND_MAX, a function of the match seed alone. */
    fun windFor(seed: Long, turn: Int): Int =
        Random(seed * 31L + turn.toLong() * 7919L + 17L).nextInt(-WIND_MAX, WIND_MAX + 1)

    fun rad(deg: Double): Double = deg * (StrictMath.PI / 180.0)
}

/** The three shells. */
object TankWeapons {
    const val SHELL = "shell"
    const val BIG = "big"
    const val BOUNCER = "bouncer"
    val ALL = listOf(SHELL, BIG, BOUNCER)

    fun radius(w: String): Double = when (w) { BIG -> 30.0; BOUNCER -> 14.0; else -> 16.0 }
    fun maxDamage(w: String): Int = when (w) { BIG -> 58; BOUNCER -> 30; else -> 34 }
    fun maxBounces(w: String): Int = if (w == BOUNCER) 3 else 0

    /** Shots a tank starts with; -1 means unlimited. */
    fun startAmmo(w: String): Int = when (w) { BIG -> 2; BOUNCER -> 3; else -> -1 }
}

@Serializable
data class TankImpact(
    /** Position times ten (so the wire carries integers). */
    val x10: Int,
    val y10: Int,
    val radius: Int,
    /** "ground", "tank", "bounce" or "lost" (left the world without hitting anything). */
    val kind: String
) {
    companion object {
        const val GROUND = "ground"
        const val TANK = "tank"
        const val BOUNCE = "bounce"
        const val LOST = "lost"
        val KINDS = setOf(GROUND, TANK, BOUNCE, LOST)
    }
}

/** What one fired shot did, in a form the screen can animate and a guest can receive. */
@Serializable
data class TankShot(
    val seat: Int,
    val weapon: String,
    val angle: Int,
    val power: Int,
    val wind: Int,
    /** x*4, y*4, x*4, y*4 ... sampled every second physics step. */
    val path: List<Int>,
    val impacts: List<TankImpact>,
    /** Hit points each tank lost (index = seat). */
    val damage: List<Int>,
    /** Seats whose tank was destroyed by this shot. */
    val killed: List<Int> = emptyList()
)

object TankTerrain {
    /**
     * A new battlefield from [seed]: rolling hills (a few sine waves, smoothed), and a flat pad under
     * each tank. Returns the terrain and, per seat, the column its tank stands on. Seats are spread across
     * the field in a seed-dependent order, so seat 0 is not always on the left.
     */
    fun generate(seed: Long, tankCount: Int): Pair<List<Int>, List<Int>> {
        val rng = Random(seed)
        val base = 78 + rng.nextInt(36)
        val amp = DoubleArray(3) { (18 + rng.nextInt(18)).toDouble() / (it + 1) }
        val freq = DoubleArray(3) { (it + 1) * (1.0 + rng.nextInt(16) / 10.0) * 2.0 * StrictMath.PI / TankWorld.W }
        val phase = DoubleArray(3) { rng.nextInt(628) / 100.0 }
        val raw = IntArray(TankWorld.W) { x ->
            var h = base.toDouble()
            for (k in 0 until 3) h += amp[k] * StrictMath.sin(freq[k] * x + phase[k])
            h.toInt().coerceIn(30, 170)
        }
        // two box-blur passes soften the stair-steps so slopes read as slopes
        var h = raw
        repeat(2) {
            val src = h
            h = IntArray(TankWorld.W) { x ->
                var sum = 0
                for (d in -2..2) sum += src[(x + d).coerceIn(0, TankWorld.W - 1)]
                sum / 5
            }
        }
        val n = tankCount.coerceAtLeast(1)
        val slot = TankWorld.W / n
        val order = (0 until n).toMutableList().also { it.shuffle(rng) }
        val columns = IntArray(n)
        for (seat in 0 until n) {
            val s = order[seat]
            val jitter = if (n == 1) 0 else rng.nextInt(-14, 15)
            val cx = (slot * s + slot / 2 + jitter).coerceIn(16, TankWorld.W - 17)
            columns[seat] = cx
            val pad = h[cx]
            for (d in -6..6) h[cx + d] = pad
        }
        return h.toList() to columns.toList()
    }

    /** Removes a circular crater of radius [r] centred at ([cx], [cy]). Everything above the bowl drops into it. */
    fun carve(h: IntArray, cx: Double, cy: Double, r: Double) {
        val lo = StrictMath.floor(cx - r).toInt().coerceAtLeast(0)
        val hi = StrictMath.ceil(cx + r).toInt().coerceAtMost(TankWorld.W - 1)
        for (ix in lo..hi) {
            val dx = ix - cx
            val q = r * r - dx * dx
            if (q < 0) continue
            val bottom = cy - StrictMath.sqrt(q)
            if (h[ix] > bottom) h[ix] = StrictMath.floor(bottom).toInt().coerceIn(0, TankWorld.H)
        }
    }
}

/** One tank as the simulation needs it: a column and whether it is still in the fight. */
class TankBody(val x: Int, val alive: Boolean)

class SimResult(val path: IntArray, val impacts: List<TankImpact>, val damage: IntArray, val killed: List<Int>)

object TankSim {
    /**
     * The first [steps] physics steps of a shot, ignoring tanks and ground, sampled every [every] steps: the
     * short dotted preview the aim shows (wind included, so it teaches the wind without giving the shot away).
     */
    fun preview(terrain: IntArray, tankX: Int, angle: Int, power: Int, wind: Int, steps: Int = 36, every: Int = 4): List<Pair<Double, Double>> {
        val a = TankWorld.rad(angle.toDouble())
        val speed = TankWorld.V_MAX * power / 100.0
        val dirX = StrictMath.cos(a)
        val dirY = StrictMath.sin(a)
        var x = tankX.toDouble() + dirX * TankWorld.MUZZLE
        var y = terrain[tankX] + TankWorld.TANK_RISE + dirY * TankWorld.MUZZLE + 3.0
        var vx = dirX * speed
        var vy = dirY * speed
        val ax = wind * TankWorld.WIND_ACCEL
        val out = ArrayList<Pair<Double, Double>>()
        for (step in 1..steps) {
            vx += ax * TankWorld.DT
            vy -= TankWorld.GRAVITY * TankWorld.DT
            x += vx * TankWorld.DT
            y += vy * TankWorld.DT
            if (step % every == 0) out += x to y
        }
        return out
    }

    /**
     * Fires one shell from the tank [shooter] and returns what happened. [terrain] is changed in place
     * (the crater). [hp] is each tank's hit points BEFORE the shot (used only to decide who dies); set
     * [record] false to skip building the path (bots simulate thousands of candidates).
     */
    fun simulate(
        terrain: IntArray, tanks: List<TankBody>, hp: IntArray, shooter: Int,
        weapon: String, angle: Int, power: Int, wind: Int, record: Boolean = true
    ): SimResult {
        val n = tanks.size
        val damage = IntArray(n)
        val impacts = ArrayList<TankImpact>(2)
        val path = if (record) ArrayList<Int>(256) else null
        // tank centres are fixed at the moment of firing
        val cxs = DoubleArray(n) { tanks[it].x.toDouble() }
        val cys = DoubleArray(n) { terrain[tanks[it].x] + TankWorld.TANK_RISE }
        val a = TankWorld.rad(angle.toDouble())
        val speed = TankWorld.V_MAX * power / 100.0
        val dirX = StrictMath.cos(a)
        val dirY = StrictMath.sin(a)
        var x = cxs[shooter] + dirX * TankWorld.MUZZLE
        var y = cys[shooter] + dirY * TankWorld.MUZZLE + 3.0
        var vx = dirX * speed
        var vy = dirY * speed
        val ax = wind * TankWorld.WIND_ACCEL
        var bounces = 0
        val maxBounces = TankWeapons.maxBounces(weapon)
        val blastR = TankWeapons.radius(weapon)
        val dmax = TankWeapons.maxDamage(weapon)
        var finished = false

        fun explode(ex: Double, ey: Double, kind: String) {
            finished = true
            impacts += TankImpact(StrictMath.round(ex * 10.0).toInt(), StrictMath.round(ey * 10.0).toInt(), blastR.toInt(), kind)
            for (k in 0 until n) {
                if (!tanks[k].alive) continue
                val d = StrictMath.hypot(cxs[k] - ex, cys[k] - ey)
                if (d <= blastR + TankWorld.TANK_R) {
                    val dmg = StrictMath.round(dmax * (1.0 - d / (blastR + TankWorld.TANK_R))).toInt().coerceAtLeast(1)
                    damage[k] += dmg
                }
            }
            TankTerrain.carve(terrain, ex, ey, blastR)
        }

        fun lost(lx: Double, ly: Double) {
            finished = true
            impacts += TankImpact(StrictMath.round(lx * 10.0).toInt(), StrictMath.round(ly * 10.0).toInt(), 0, TankImpact.LOST)
        }

        if (path != null) { path += StrictMath.round(x * 4.0).toInt(); path += StrictMath.round(y * 4.0).toInt() }
        var step = 0
        while (step < TankWorld.MAX_STEPS && !finished) {
            step++
            vx += ax * TankWorld.DT
            vy -= TankWorld.GRAVITY * TankWorld.DT
            x += vx * TankWorld.DT
            y += vy * TankWorld.DT
            if (path != null && step % 2 == 0 && path.size < TankWorld.MAX_PATH_POINTS * 2) {
                path += StrictMath.round(x * 4.0).toInt(); path += StrictMath.round(y * 4.0).toInt()
            }
            if (x < -20.0 || x > TankWorld.W + 20.0 || y < -60.0) { lost(x, y); break }
            // a direct hit on a tank (the shooter is safe for the first few steps while the shell clears the barrel)
            var struck = false
            for (k in 0 until n) {
                if (!tanks[k].alive) continue
                if (k == shooter && step < TankWorld.SELF_IMMUNE_STEPS) continue
                if (StrictMath.hypot(x - cxs[k], y - cys[k]) <= TankWorld.TANK_R) { struck = true; break }
            }
            if (struck) { explode(x, y, TankImpact.TANK); break }
            if (x >= -0.5 && x <= TankWorld.W - 0.5) {
                val ix = StrictMath.round(x).toInt().coerceIn(0, TankWorld.W - 1)
                if (y <= terrain[ix]) {
                    if (bounces < maxBounces) {
                        val gx = (terrain[(ix - 2).coerceAtLeast(0)] - terrain[(ix + 2).coerceAtMost(TankWorld.W - 1)]) / 4.0
                        val len = StrictMath.sqrt(gx * gx + 1.0)
                        val nx = gx / len
                        val ny = 1.0 / len
                        val dot = vx * nx + vy * ny
                        if (dot < 0.0) {
                            vx = (vx - 2.0 * dot * nx) * 0.55
                            vy = (vy - 2.0 * dot * ny) * 0.55
                            y = terrain[ix] + 0.6
                            bounces++
                            impacts += TankImpact(StrictMath.round(x * 10.0).toInt(), StrictMath.round(y * 10.0).toInt(), 3, TankImpact.BOUNCE)
                            if (StrictMath.hypot(vx, vy) < 25.0) { explode(x, y, TankImpact.GROUND); break }
                        }
                    } else {
                        explode(x, y, TankImpact.GROUND)
                        break
                    }
                }
            }
        }
        // ran out of steps (a shell lobbed so high it never came back within the cap): treat as lost
        if (!finished) lost(x, y)
        val killed = (0 until n).filter { tanks[it].alive && damage[it] >= hp[it] }
        return SimResult(path?.toIntArray() ?: IntArray(0), impacts.take(TankWorld.MAX_IMPACTS), damage, killed)
    }
}
