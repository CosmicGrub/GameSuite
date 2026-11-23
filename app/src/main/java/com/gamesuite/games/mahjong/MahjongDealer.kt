package com.gamesuite.games.mahjong

import java.util.BitSet
import kotlin.random.Random

/**
 * A dealt board: [types] per slot (-1 = no tile) and the [removalOrder] (slot pairs) that clears it,
 * which is the proof the deal is winnable: replaying it through the free-tile rule always works.
 */
class MahjongDeal(val types: IntArray, val removalOrder: List<Pair<Int, Int>>)

/**
 * Deals SOLVABLE boards. The trick is to decide the solution first: starting from the full layout,
 * repeatedly remove two currently-free slots (never leaving a board with fewer than two free tiles
 * while tiles remain), which yields an explicit clearing order of slot pairs. Matching tile pairs
 * are then dealt onto those slot pairs, so the clearing order is a legal playthrough by
 * construction. Deterministic for a given [Random].
 */
object MahjongDealer {
    private const val MAX_ATTEMPTS = 400
    private const val PAIR_TRIES = 14
    private const val CANDIDATES = 3

    /** A solvable deal for the whole layout of [geo] (an even number of slots). */
    fun newDeal(geo: MahjongGeometry, random: Random): MahjongDeal {
        require(geo.size % 2 == 0) { "A layout needs an even number of tiles" }
        val all = BooleanArray(geo.size) { true }
        val order = firstOrder(geo, all, random)
            ?: fallbackOrder(geo, all)
            ?: error("No clearing order for the layout")
        val pool = MahjongTiles.pairPool(random).shuffled(random).take(geo.size / 2)
        return MahjongDeal(assign(geo.size, order, pool, random), order)
    }

    /**
     * Re-deals the tiles still on the board ([types] >= 0) onto their slots, as a solvable
     * arrangement when one exists, else any arrangement that has at least one move. Null when no
     * arrangement offers a move (a dead end). The multiset of tiles never changes.
     */
    fun reshuffle(geo: MahjongGeometry, types: IntArray, random: Random): IntArray? {
        val alive = BooleanArray(geo.size) { types[it] >= 0 }
        val pool = ArrayList<Pair<Int, Int>>()
        val byGroup = Array(MahjongTiles.GROUP_COUNT) { ArrayList<Int>() }
        for (i in 0 until geo.size) if (types[i] >= 0) byGroup[MahjongTiles.group(types[i])].add(types[i])
        for (g in byGroup) {
            if (g.size % 2 != 0) return null
            g.shuffle(random)
            var k = 0
            while (k < g.size) {
                pool.add(g[k] to g[k + 1])
                k += 2
            }
        }
        val order = firstOrder(geo, alive, random) ?: fallbackOrder(geo, alive)
        if (order != null) {
            return assign(geo.size, order, pool.shuffled(random), random)
        }
        // No clearing order exists for these slots; settle for any arrangement with a move.
        val slots = (0 until geo.size).filter { alive[it] }
        val tiles = slots.map { types[it] }.toMutableList()
        repeat(200) {
            tiles.shuffle(random)
            val out = IntArray(geo.size) { -1 }
            for (k in slots.indices) out[slots[k]] = tiles[k]
            if (MahjongSolver.availablePairs(geo, out).isNotEmpty()) return out
        }
        return null
    }

    private fun assign(size: Int, order: List<Pair<Int, Int>>, pool: List<Pair<Int, Int>>, random: Random): IntArray {
        val out = IntArray(size) { -1 }
        for (k in order.indices) {
            val (a, b) = order[k]
            val (ta, tb) = pool[k]
            if (random.nextBoolean()) {
                out[a] = ta
                out[b] = tb
            } else {
                out[a] = tb
                out[b] = ta
            }
        }
        return out
    }

    private fun firstOrder(geo: MahjongGeometry, start: BooleanArray, random: Random): List<Pair<Int, Int>>? {
        repeat(MAX_ATTEMPTS) {
            val order = randomOrder(geo, start, random)
            if (order != null) return order
        }
        return null
    }

    /** One random clearing attempt; null if it painted itself into a corner. */
    private fun randomOrder(geo: MahjongGeometry, start: BooleanArray, random: Random): List<Pair<Int, Int>>? {
        val alive = start.copyOf()
        var remaining = alive.count { it }
        val out = ArrayList<Pair<Int, Int>>(remaining / 2)
        while (remaining > 0) {
            val free = geo.freeSlots(alive)
            if (free.size < 2) return null
            val cand = ArrayList<IntArray>(CANDIDATES) // a, b, free tiles left afterwards
            var tries = 0
            while (tries < PAIR_TRIES && cand.size < CANDIDATES) {
                tries++
                val ai = random.nextInt(free.size)
                var bi = random.nextInt(free.size - 1)
                if (bi >= ai) bi++
                val a = free[ai]
                val b = free[bi]
                alive[a] = false
                alive[b] = false
                val left = if (remaining - 2 == 0) Int.MAX_VALUE else geo.countFree(alive, Int.MAX_VALUE)
                alive[a] = true
                alive[b] = true
                if (left >= 2) cand.add(intArrayOf(a, b, left))
            }
            if (cand.isEmpty()) return null
            // Half the time keep the most options open; otherwise any acceptable pair.
            val pick = if (random.nextBoolean()) cand.maxByOrNull { it[2] }!! else cand[random.nextInt(cand.size)]
            alive[pick[0]] = false
            alive[pick[1]] = false
            out.add(pick[0] to pick[1])
            remaining -= 2
        }
        return out
    }

    /** Deterministic peel from the top layer down; used only if every random attempt failed. */
    internal fun fallbackOrder(geo: MahjongGeometry, start: BooleanArray): List<Pair<Int, Int>>? {
        val alive = start.copyOf()
        var remaining = alive.count { it }
        val out = ArrayList<Pair<Int, Int>>(remaining / 2)
        val pos = geo.positions
        while (remaining > 0) {
            val free = geo.freeSlots(alive)
                .sortedWith(compareBy({ -pos[it].z }, { pos[it].y }, { pos[it].x }))
            if (free.size < 2) return null
            val a = free[0]
            val b = free[1]
            alive[a] = false
            alive[b] = false
            out.add(a to b)
            remaining -= 2
        }
        return out
    }
}

enum class MahjongSolve { SOLVABLE, UNSOLVABLE, UNKNOWN }

class MahjongSolveResult(val outcome: MahjongSolve, val line: List<Pair<Int, Int>>)

/** Move generation and a bounded depth-first solver over the free-tile and matching rules. */
object MahjongSolver {

    /** Every free, matching pair on the board, best looking first (highest layers first). */
    fun availablePairs(geo: MahjongGeometry, types: IntArray): List<Pair<Int, Int>> =
        availablePairs(geo, types, BooleanArray(geo.size) { types[it] >= 0 })

    internal fun availablePairs(geo: MahjongGeometry, types: IntArray, alive: BooleanArray): List<Pair<Int, Int>> {
        val free = geo.freeSlots(alive)
        val out = ArrayList<Pair<Int, Int>>()
        for (x in free.indices) {
            for (y in x + 1 until free.size) {
                if (MahjongTiles.matches(types[free[x]], types[free[y]])) out.add(free[x] to free[y])
            }
        }
        val pos = geo.positions
        out.sortByDescending { pos[it.first].z + pos[it.second].z }
        return out
    }

    /**
     * Searches for a clearing line within [maxNodes] search nodes. SOLVABLE comes with the line;
     * UNSOLVABLE only when the whole tree was exhausted; UNKNOWN when the budget ran out first.
     */
    fun solve(geo: MahjongGeometry, types: IntArray, maxNodes: Int = 20_000): MahjongSolveResult {
        val alive = BooleanArray(geo.size) { types[it] >= 0 }
        val failed = HashSet<BitSet>()
        val line = ArrayList<Pair<Int, Int>>()
        var nodes = 0
        var exhausted = false

        fun dfs(left: Int): Boolean {
            if (left == 0) return true
            if (nodes >= maxNodes) {
                exhausted = true
                return false
            }
            nodes++
            val key = BitSet(geo.size)
            for (i in 0 until geo.size) if (alive[i]) key.set(i)
            if (key in failed) return false
            for ((a, b) in availablePairs(geo, types, alive)) {
                alive[a] = false
                alive[b] = false
                line.add(a to b)
                if (dfs(left - 2)) return true
                line.removeAt(line.size - 1)
                alive[a] = true
                alive[b] = true
                if (exhausted) return false
            }
            failed.add(key)
            return false
        }

        val total = alive.count { it }
        val ok = total % 2 == 0 && dfs(total)
        return when {
            ok -> MahjongSolveResult(MahjongSolve.SOLVABLE, line.toList())
            exhausted -> MahjongSolveResult(MahjongSolve.UNKNOWN, emptyList())
            else -> MahjongSolveResult(MahjongSolve.UNSOLVABLE, emptyList())
        }
    }
}
