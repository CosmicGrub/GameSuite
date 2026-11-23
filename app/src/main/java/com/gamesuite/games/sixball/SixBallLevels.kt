package com.gamesuite.games.sixball

import java.util.concurrent.ConcurrentHashMap

/**
 * The levels of Six-Ball Puzzle. Nothing is hand-typed: a level is GENERATED from a fixed recipe
 * and a seed, then VERIFIED by construction: the generator explores the tray breadth-first from the
 * start position and takes, as the target arrangement, a position whose shortest distance from the
 * start is exactly the wanted par. So every level is solvable and its par is the true optimum.
 * (SixBallLevelsTest re-solves all of them independently.)
 *
 * The campaign has [CAMPAIGN_COUNT] levels from par 3 on a small tray up to par 20 on a 7 x 7 one.
 * The daily puzzle is generated from the day number, so every player gets the same tray that day.
 * The generator uses its own tiny PRNG (not kotlin.random) so a level never changes with a library
 * update, which would silently re-map a saved star count to a different puzzle.
 */
object SixBallLevels {
    const val CAMPAIGN_COUNT = 48

    /** The recipe of campaign level [n] (1-based). */
    data class Recipe(val width: Int, val height: Int, val walls: Int, val par: Int)

    fun recipe(n: Int): Recipe {
        require(n in 1..CAMPAIGN_COUNT) { "level $n out of range" }
        val par = 3 + ((n - 1) * 17 + 23) / 47 // 3 at level 1 .. 20 at level 48, never decreasing
        return when {
            n <= 12 -> Recipe(5, 5, 10, par)
            n <= 30 -> Recipe(6, 6, if (n % 2 == 0) 12 else 10, par)
            else -> Recipe(7, 7, if (n % 2 == 0) 16 else 22, par)
        }
    }

    fun campaignId(n: Int) = "c$n"
    fun dailyId(epochDay: Long) = "d$epochDay"

    /** The campaign number of an id like "c12", or null. */
    fun campaignNumber(id: String): Int? =
        if (id.startsWith("c")) id.substring(1).toIntOrNull()?.takeIf { it in 1..CAMPAIGN_COUNT } else null

    fun dailyDay(id: String): Long? = if (id.startsWith("d")) id.substring(1).toLongOrNull() else null

    private val cache = ConcurrentHashMap<String, SixBallLevel>()

    /** Generates (once, then remembers) the level with this id; null for an id that names no level. */
    fun byId(id: String): SixBallLevel? {
        cache[id]?.let { return it }
        val level = campaignNumber(id)?.let { campaign(it) } ?: dailyDay(id)?.let { daily(it) } ?: return null
        return level
    }

    fun campaign(n: Int): SixBallLevel = cache.getOrPut(campaignId(n)) {
        val r = recipe(n)
        generate(campaignId(n), seed = 0x5B1L * 1_000_003L + n, width = r.width, height = r.height, walls = r.walls, par = r.par)
    }

    fun daily(epochDay: Long): SixBallLevel = cache.getOrPut(dailyId(epochDay)) {
        val weekday = Math.floorMod(epochDay, 7L).toInt()
        val par = 6 + weekday * 2
        val big = par > 12
        generate(
            dailyId(epochDay), seed = epochDay * 7919L + 0xD41L,
            width = if (big) 7 else 6, height = if (big) 7 else 6, walls = if (big) 16 else 12, par = par
        )
    }

    /** SplitMix64: small, fixed forever, good enough for a puzzle generator. */
    private class Mix(var s: Long) {
        fun next(): Long {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        fun below(n: Int): Int = ((next() ushr 1) % n).toInt()
    }

    /**
     * Builds one level: walls and a start position from [seed]; the target is a position exactly
     * [par] tilts from the start (at least four balls must have moved, so no level is a
     * one-ball problem). Tries successive attempts until one works; the first attempt that finds a
     * suitable position wins, so the result is a pure function of the arguments.
     */
    fun generate(id: String, seed: Long, width: Int, height: Int, walls: Int, par: Int): SixBallLevel {
        require(width * height <= 64 && width * height - walls >= SIXBALL_BALLS + 2)
        for (attempt in 0 until 2000) {
            val rnd = Mix(seed * 31L + attempt * 1_000_003L + 12345L)
            val cells = MutableList(width * height) { it }
            for (i in cells.size - 1 downTo 1) { val j = rnd.below(i + 1); val t = cells[i]; cells[i] = cells[j]; cells[j] = t }
            val wallCells = cells.take(walls).sorted()
            val start = cells.drop(walls).take(SIXBALL_BALLS)
            val probe = SixBallLevel(id, width, height, wallCells, start, start, par)
            val ex = SixBallRules.explore(probe, start.toIntArray(), maxDepth = par, maxNodes = 150_000)
            if (!ex.complete) continue
            val candidates = ArrayList<Int>()
            val scratch = IntArray(SIXBALL_BALLS)
            for (i in 0 until ex.size) {
                if (ex.depth[i] != par) continue
                SixBallRules.unpack(ex.states[i], scratch)
                if ((0 until SIXBALL_BALLS).count { scratch[it] != start[it] } >= 4) candidates += i
            }
            if (candidates.isEmpty()) continue
            val pick = candidates[rnd.below(candidates.size)]
            val target = SixBallRules.unpack(ex.states[pick]).toList()
            return SixBallLevel(id, width, height, wallCells, start, target, par)
        }
        error("could not generate $id")
    }
}
