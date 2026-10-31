package com.gamesuite.games.minigolf

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random

/**
 * How far each spot of a hole is from the cup along the shortest walkable route (a breadth-first flood
 * from the cup over a 1.5-unit grid, around walls and water). The computer and the hint score where a
 * stroke ends by this, which is what lets them plan the corner of a dogleg rather than just aim at the cup.
 */
class GolfNav(val hole: GolfHole) {
    private val cell = 1.5
    private val cols = (GolfCourse.FIELD_W / cell).toInt() + 1
    private val rows = (GolfCourse.FIELD_H / cell).toInt() + 1
    private val dist = IntArray(cols * rows) { UNREACHED }

    init {
        val free = BooleanArray(cols * rows)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val x = cx * cell; val y = cy * cell
            free[cy * cols + cx] = hole.isPlayable(x, y) && !hole.inWater(x, y) && wallClearance(x, y) >= GolfPhysics.BALL_R * 0.9
        }
        val start = indexOf(hole.cup.x, hole.cup.y)
        val queue = ArrayDeque<Int>()
        dist[start] = 0; queue.addLast(start)
        free[start] = true
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val cx = c % cols; val cy = c / cols
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until cols || ny !in 0 until rows) continue
                val ni = ny * cols + nx
                if (!free[ni] || dist[ni] != UNREACHED) continue
                // a diagonal step may not cut a corner of a blocked cell
                if (dx != 0 && dy != 0 && (!free[cy * cols + nx] || !free[ny * cols + cx])) continue
                dist[ni] = dist[c] + (if (dx != 0 && dy != 0) 3 else 2)
                queue.addLast(ni)
            }
        }
    }

    private fun indexOf(x: Double, y: Double): Int {
        val cx = Math.round(x / cell).toInt().coerceIn(0, cols - 1)
        val cy = Math.round(y / cell).toInt().coerceIn(0, rows - 1)
        return cy * cols + cx
    }

    private fun wallClearance(x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        for (w in hole.walls) {
            val sx = w[2] - w[0]; val sy = w[3] - w[1]
            val l2 = sx * sx + sy * sy
            val t = if (l2 < 1e-12) 0.0 else (((x - w[0]) * sx + (y - w[1]) * sy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(x - (w[0] + sx * t), y - (w[1] + sy * t)))
        }
        return best
    }

    /** Route length (in half-cells, so 2 per cell step) from (x, y) to the cup; a large number when unreachable. */
    fun distanceTo(x: Double, y: Double): Double {
        // look at the nearest few cells so a ball hugging a wall still gets an answer
        val cx0 = Math.round(x / cell).toInt(); val cy0 = Math.round(y / cell).toInt()
        var best = Int.MAX_VALUE
        for (dx in -1..1) for (dy in -1..1) {
            val cx = (cx0 + dx).coerceIn(0, cols - 1); val cy = (cy0 + dy).coerceIn(0, rows - 1)
            val d = dist[cy * cols + cx]
            if (d != UNREACHED && d < best) best = d
        }
        return if (best == Int.MAX_VALUE) 1000.0 else best * (cell / 2.0)
    }

    companion object {
        const val UNREACHED = -1
        private val cache = HashMap<Int, GolfNav>()

        @Synchronized
        fun forHole(hole: GolfHole): GolfNav = cache.getOrPut(hole.number) { GolfNav(hole) }
    }
}

/**
 * The computer golfer. Easy aims at the cup (or the corner) with a wide error and uneven power, Medium takes
 * the best of a coarse sweep of angles and powers with a small error, and Hard sweeps finely, refines the best
 * line and re-checks it by simulating it (pure and thread-safe: it is meant to run off the main thread).
 */
object GolfBot {
    /** [exact] skips Hard's small human-like error (used for the hint, which should be the best line there is). */
    fun chooseShot(hole: GolfHole, x: Double, y: Double, level: CpuDifficulty, random: Random, exact: Boolean = false): GolfShot = when (level) {
        CpuDifficulty.EASY -> {
            val best = search(hole, x, y, angleStepDeg = 12.0, powers = doubleArrayOf(0.35, 0.55, 0.75, 0.95), refine = false)
            GolfShot(best.angle + gauss(random) * 7.0 * PI / 180.0, (best.power * (1.0 + gauss(random) * 0.28)).coerceIn(0.1, 1.0))
        }
        CpuDifficulty.MEDIUM -> {
            val best = search(hole, x, y, angleStepDeg = 4.0, powers = doubleArrayOf(0.2, 0.35, 0.5, 0.65, 0.8, 0.95), refine = false)
            GolfShot(best.angle + gauss(random) * 2.0 * PI / 180.0, (best.power * (1.0 + gauss(random) * 0.10)).coerceIn(0.1, 1.0))
        }
        CpuDifficulty.HARD -> {
            val best = search(hole, x, y, angleStepDeg = 2.0, powers = HARD_POWERS, refine = true)
            // even the best computer is a hair off: under a degree and 4% on the power
            if (exact) best else GolfShot(best.angle + gauss(random) * 0.8 * PI / 180.0, (best.power * (1.0 + gauss(random) * 0.04)).coerceIn(0.05, 1.0))
        }
    }

    private val HARD_POWERS = doubleArrayOf(0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.0)

    /** How good a stroke is (lower is better). */
    fun cost(hole: GolfHole, nav: GolfNav, sim: GolfSim): Double = when (sim.end) {
        GolfEnd.CUP -> -1000.0
        GolfEnd.WATER -> 1500.0
        else -> nav.distanceTo(sim.x, sim.y) + (if (hole.inSand(sim.x, sim.y)) 12.0 else 0.0) + sim.steps * 0.002
    }

    fun search(hole: GolfHole, x: Double, y: Double, angleStepDeg: Double, powers: DoubleArray, refine: Boolean): GolfShot {
        val nav = GolfNav.forHole(hole)
        var best = GolfShot(0.0, 0.5)
        var bestCost = Double.MAX_VALUE
        var a = -180.0
        while (a < 180.0) {
            val ang = a * PI / 180.0
            for (p in powers) {
                val shot = GolfShot(ang, p)
                val c = cost(hole, nav, GolfPhysics.simulate(hole, x, y, shot))
                if (c < bestCost) { bestCost = c; best = shot }
            }
            a += angleStepDeg
        }
        if (refine) {
            // narrow in on the best line: +/- 2 degrees in 0.25 steps, powers +/- 12% in 4% steps
            var da = -2.0
            while (da <= 2.0 + 1e-9) {
                var dp = -0.12
                while (dp <= 0.12 + 1e-9) {
                    val shot = GolfShot(best.angle + da * PI / 180.0, (best.power + dp).coerceIn(0.05, 1.0))
                    val c = cost(hole, nav, GolfPhysics.simulate(hole, x, y, shot))
                    if (c < bestCost - 1e-9) { bestCost = c; best = shot }
                    dp += 0.04
                }
                da += 0.25
            }
        }
        return best
    }

    private fun gauss(random: Random): Double {
        var s = 0.0
        repeat(4) { s += random.nextDouble() - 0.5 }
        return s * Math.sqrt(3.0)
    }
}
