package com.gamesuite.games.mahjong

import kotlin.math.abs

/**
 * One tile slot of a layout on the half-tile grid: a tile is 2 units wide and 2 units tall, so two
 * tiles on the same layer touch when their x (or y) differ by exactly 2, and a tile on layer z + 1
 * that is offset by 1 sits half over its neighbours (the classic turtle's centre tile).
 */
data class MahjongPos(val x: Int, val y: Int, val z: Int)

/**
 * The pure geometry of a layout: which slots cover, and which touch on the left or right. Knows
 * nothing about tile types, so the free-tile rule is testable on hand-made coordinate lists.
 *
 * A tile is FREE when it is still on the board, no remaining tile lies on top of it (a tile on a
 * higher layer whose footprint overlaps it, even by half a tile), and its left side OR its right
 * side has no remaining neighbour on the same layer (a neighbour overlapping the tile's rows by
 * even half a tile counts).
 */
class MahjongGeometry(val positions: List<MahjongPos>) {
    val size: Int = positions.size

    private val above: Array<IntArray>
    private val left: Array<IntArray>
    private val right: Array<IntArray>

    init {
        val a = Array(size) { ArrayList<Int>() }
        val l = Array(size) { ArrayList<Int>() }
        val r = Array(size) { ArrayList<Int>() }
        for (i in 0 until size) {
            val p = positions[i]
            for (j in 0 until size) {
                if (i == j) continue
                val q = positions[j]
                val dx = q.x - p.x
                val dy = q.y - p.y
                if (abs(dy) >= 2) continue
                if (q.z > p.z) {
                    if (abs(dx) < 2) a[i].add(j)
                } else if (q.z == p.z) {
                    if (dx < 0 && dx >= -2) l[i].add(j) else if (dx > 0 && dx <= 2) r[i].add(j)
                }
            }
        }
        above = Array(size) { a[it].toIntArray() }
        left = Array(size) { l[it].toIntArray() }
        right = Array(size) { r[it].toIntArray() }
    }

    /** The slots lying on top of [i] (any higher layer, overlapping footprint). */
    fun coveredBy(i: Int): IntArray = above[i]

    /** Same-layer slots touching [i]'s left / right side. */
    fun leftNeighbours(i: Int): IntArray = left[i]
    fun rightNeighbours(i: Int): IntArray = right[i]

    /** The free-tile rule over the [alive] mask. */
    fun isFree(i: Int, alive: BooleanArray): Boolean {
        if (!alive[i]) return false
        for (j in above[i]) if (alive[j]) return false
        var leftOpen = true
        for (j in left[i]) if (alive[j]) { leftOpen = false; break }
        if (leftOpen) return true
        for (j in right[i]) if (alive[j]) return false
        return true
    }

    fun freeSlots(alive: BooleanArray): IntArray {
        val out = ArrayList<Int>()
        for (i in 0 until size) if (isFree(i, alive)) out.add(i)
        return out.toIntArray()
    }

    /** Free slots counted up to [limit] (cheap lookahead). */
    fun countFree(alive: BooleanArray, limit: Int): Int {
        var n = 0
        for (i in 0 until size) {
            if (isFree(i, alive)) {
                n++
                if (n >= limit) return n
            }
        }
        return n
    }

    /** Index pairs that occupy the same space (same layer, footprints overlapping). A valid layout has none. */
    fun overlappingPairs(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until size) for (j in i + 1 until size) {
            val p = positions[i]
            val q = positions[j]
            if (p.z == q.z && abs(p.x - q.x) < 2 && abs(p.y - q.y) < 2) out.add(i to j)
        }
        return out
    }

    val maxX: Int get() = positions.maxOfOrNull { it.x } ?: 0
    val maxY: Int get() = positions.maxOfOrNull { it.y } ?: 0
    val maxZ: Int get() = positions.maxOfOrNull { it.z } ?: 0
}
