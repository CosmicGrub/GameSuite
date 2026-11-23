package com.gamesuite.games.lighttrail

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer riders. One decision per rider per step, by level:
 *  - EASY sees a wall in time but misses one in seven, wanders now and then, and acts on what it saw
 *    [reactionSteps] step ago, so it clips walls and drives itself into corners.
 *  - MEDIUM never drives straight into a wall and prefers the side with more open floor behind it (a flood fill),
 *    but it does not reason about its rivals and reacts one step late.
 *  - HARD avoids traps and cuts rivals off: it picks the move that maximises the floor it reaches first
 *    (a Voronoi count over all riders) and reacts at once.
 * Pure: the board, the rider, the level and an injected Random decide everything.
 */
object LightTrailCpu {
    /** Steps between seeing the board and steering on it. */
    fun reactionSteps(level: CpuDifficulty): Int = when (level) {
        CpuDifficulty.EASY -> 1
        CpuDifficulty.MEDIUM -> 1
        CpuDifficulty.HARD -> 0
    }

    /** The turn to queue now (null = keep going straight). */
    fun decide(engine: LightTrailEngine, riderId: Int, level: CpuDifficulty, random: Random): LtTurn? {
        val r = engine.riders[riderId]
        if (!r.alive) return null
        val straight = r.dir
        val left = r.dir.ccw()
        val right = r.dir.cw()
        fun free(d: LtDir): Boolean {
            val x = r.x + d.dx; val y = r.y + d.dy
            return engine.inBounds(x, y) && engine.cell(x, y) == 0
        }
        val sFree = free(straight); val lFree = free(left); val rFree = free(right)
        // Acting reactionSteps late, a rider must start turning while that many free cells are still ahead of it.
        fun clearAhead(d: LtDir, n: Int): Boolean {
            for (k in 1..n) {
                val x = r.x + d.dx * k; val y = r.y + d.dy * k
                if (!engine.inBounds(x, y) || engine.cell(x, y) != 0) return false
            }
            return true
        }
        val look = reactionSteps(level) + 1
        val sClear = clearAhead(straight, look)
        when (level) {
            CpuDifficulty.EASY -> {
                if (!sClear) {
                    if (random.nextInt(100) < 15) return null // did not notice
                    return when {
                        lFree && rFree -> {
                            // It does pick the roomier side, but only by a quick look (a short flood fill).
                            val lA = area(engine, r.x + left.dx, r.y + left.dy, 40)
                            val rA = area(engine, r.x + right.dx, r.y + right.dy, 40)
                            if (lA > rA) LtTurn.LEFT else if (rA > lA) LtTurn.RIGHT else if (random.nextBoolean()) LtTurn.LEFT else LtTurn.RIGHT
                        }
                        lFree -> LtTurn.LEFT
                        rFree -> LtTurn.RIGHT
                        else -> null
                    }
                }
                if (random.nextInt(100) < 3) {
                    // A wander: turn toward a free side, or unluckily into whatever.
                    return if (random.nextBoolean()) LtTurn.LEFT else LtTurn.RIGHT
                }
                return null
            }
            CpuDifficulty.MEDIUM -> {
                val sA = if (sFree) area(engine, r.x + straight.dx, r.y + straight.dy, 140) else -1
                val lA = if (lFree) area(engine, r.x + left.dx, r.y + left.dy, 140) else -1
                val rA = if (rFree) area(engine, r.x + right.dx, r.y + right.dy, 140) else -1
                val best = maxOf(sA, lA, rA)
                if (best < 0) return null
                if (sClear && sA + 12 >= best) return null
                return if (lA > rA) LtTurn.LEFT else if (rA > lA) LtTurn.RIGHT else if (random.nextBoolean()) LtTurn.LEFT else LtTurn.RIGHT
            }
            CpuDifficulty.HARD -> {
                var bestScore = Int.MIN_VALUE
                var bestTurn: LtTurn? = null
                var found = false
                for ((turn, d) in listOf<Pair<LtTurn?, LtDir>>(null to straight, LtTurn.LEFT to left, LtTurn.RIGHT to right)) {
                    if (!free(d)) continue
                    val score = voronoiScore(engine, riderId, r.x + d.dx, r.y + d.dy) + if (turn == null) 3 else 0
                    if (!found || score > bestScore) { bestScore = score; bestTurn = turn; found = true }
                }
                return if (found) bestTurn else null
            }
        }
    }

    /** Open cells reachable from (x,y), counted up to [cap]. */
    private fun area(engine: LightTrailEngine, x: Int, y: Int, cap: Int): Int {
        val n = engine.size
        val seen = BooleanArray(n * n)
        val queue = IntArray(n * n)
        var head = 0; var tail = 0
        queue[tail++] = y * n + x; seen[y * n + x] = true
        var count = 0
        while (head < tail && count < cap) {
            val c = queue[head++]; count++
            val cx = c % n; val cy = c / n
            for (d in LtDir.entries) {
                val nx = cx + d.dx; val ny = cy + d.dy
                if (nx !in 0 until n || ny !in 0 until n) continue
                val k = ny * n + nx
                if (seen[k] || engine.cells[k] != 0) continue
                seen[k] = true; queue[tail++] = k
            }
        }
        return count
    }

    /**
     * Cells this rider would reach first minus the best rival's, after stepping to (nx, ny). A rider boxed into a
     * tiny pocket scores very low, so Hard walks away from traps and toward cutting rivals off.
     */
    private fun voronoiScore(engine: LightTrailEngine, me: Int, nx: Int, ny: Int): Int {
        val n = engine.size
        val total = n * n
        val dist = IntArray(total) { -1 }
        val owner = IntArray(total) { -1 } // -1 unreached, -2 contested
        val queue = IntArray(total)
        var head = 0; var tail = 0
        val myCell = ny * n + nx
        dist[myCell] = 0; owner[myCell] = me; queue[tail++] = myCell
        for (r in engine.riders) {
            if (!r.alive || r.id == me) continue
            val c = r.y * n + r.x
            dist[c] = 0; owner[c] = r.id; queue[tail++] = c
        }
        val counts = IntArray(engine.riders.size)
        // Multi-source breadth-first search: the first rider to reach an open cell owns it; a tie in distance is shared (nobody).
        while (head < tail) {
            val c = queue[head++]
            val o = owner[c]
            val cx = c % n; val cy = c / n
            for (d in LtDir.entries) {
                val x = cx + d.dx; val y = cy + d.dy
                if (x !in 0 until n || y !in 0 until n) continue
                val k = y * n + x
                if (engine.cells[k] != 0 && k != myCell) continue
                if (k == myCell) continue
                if (dist[k] == -1) {
                    dist[k] = dist[c] + 1; owner[k] = o; queue[tail++] = k
                } else if (dist[k] == dist[c] + 1 && owner[k] != o && owner[k] != -2) {
                    owner[k] = -2
                }
            }
        }
        for (k in 0 until total) { val o = owner[k]; if (o >= 0 && dist[k] > 0) counts[o]++ }
        var bestOther = 0
        for (r in engine.riders) if (r.alive && r.id != me && counts[r.id] > bestOther) bestOther = counts[r.id]
        val mine = counts[me]
        // Mostly "my territory"; a smaller weight for starving the rivals; a dead end is penalised hard.
        return mine * 3 - bestOther * 2 - if (mine < 6) 200 else 0
    }
}
