package com.gamesuite.games.chinesecheckers

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.abs
import kotlin.random.Random

/**
 * The star board of Chinese Checkers: 121 holes, six 10-hole corner triangles around a 61-hole
 * hexagon, laid out on axial coordinates (q, r) with s = -q - r. A hole is on the star iff the
 * smallest of (q, r, s) is at least -4 or the largest is at most 4 (two big overlapping triangles).
 *
 * Corners are numbered clockwise starting at the "s >= 5" tip, so a corner's opposite is always
 * `(corner + 3) % 6`:  0: s>=5, 1: q<=-5, 2: r>=5, 3: s<=-5, 4: q>=5, 5: r<=-5.
 * Every neighbour / jump table is precomputed once; nothing here allocates during a search.
 */
object CCBoard {
    const val CELLS = 121
    const val CORNERS = 6
    const val MAX_SEATS = 6
    const val STANDARD_PIECES = 10
    const val SHORT_PIECES = 6

    /** Axial directions; opposite directions differ by 3. */
    private val DQ = intArrayOf(1, 1, 0, -1, -1, 0)
    private val DR = intArrayOf(0, -1, -1, 0, 1, 1)

    val q = IntArray(CELLS)
    val r = IntArray(CELLS)
    private val lookup = IntArray(17 * 17) { -1 }

    init {
        var n = 0
        for (rr in -8..8) for (qq in -8..8) {
            if (onStar(qq, rr)) {
                q[n] = qq; r[n] = rr
                lookup[(qq + 8) * 17 + (rr + 8)] = n
                n++
            }
        }
        check(n == CELLS) { "star has $n holes" }
    }

    private fun onStar(qq: Int, rr: Int): Boolean {
        val ss = -qq - rr
        return minOf(qq, rr, ss) >= -4 || maxOf(qq, rr, ss) <= 4
    }

    fun indexOf(qq: Int, rr: Int): Int =
        if (qq < -8 || qq > 8 || rr < -8 || rr > 8) -1 else lookup[(qq + 8) * 17 + (rr + 8)]

    /** neighbors[cell * 6 + dir] is the adjacent hole or -1 off the board. */
    val neighbors = IntArray(CELLS * 6)

    /** jumps[cell * 6 + dir] is the hole two steps away or -1. */
    val jumps = IntArray(CELLS * 6)

    init {
        for (c in 0 until CELLS) for (d in 0..5) {
            neighbors[c * 6 + d] = indexOf(q[c] + DQ[d], r[c] + DR[d])
            jumps[c * 6 + d] = indexOf(q[c] + 2 * DQ[d], r[c] + 2 * DR[d])
        }
    }

    fun s(cell: Int) = -q[cell] - r[cell]

    /** The corner triangle [cell] belongs to, or -1 for the central hexagon. */
    fun cornerOf(cell: Int): Int {
        val qq = q[cell]; val rr = r[cell]; val ss = s(cell)
        return when {
            ss >= 5 -> 0
            qq <= -5 -> 1
            rr >= 5 -> 2
            ss <= -5 -> 3
            qq >= 5 -> 4
            rr <= -5 -> 5
            else -> -1
        }
    }

    /** 0 at a corner's tip, 3 at its base row. */
    fun depthIn(cell: Int, corner: Int): Int = when (corner) {
        0 -> 8 - s(cell)
        1 -> 8 + q[cell]
        2 -> 8 - r[cell]
        3 -> 8 + s(cell)
        4 -> 8 - q[cell]
        else -> 8 + r[cell]
    }

    fun opposite(corner: Int) = (corner + 3) % 6

    private val cornerLists: Array<Array<IntArray>> = Array(CORNERS) { corner ->
        Array(5) { rows ->
            (0 until CELLS).filter { cornerOf(it) == corner && depthIn(it, corner) < rows }.toIntArray()
        }
    }

    /** The holes of [corner] to a depth of [rows] rows (4 rows = 10 holes, 3 rows = 6). */
    fun cornerCells(corner: Int, rows: Int): IntArray = cornerLists[corner][rows]

    fun rowsFor(pieces: Int): Int = if (pieces == SHORT_PIECES) 3 else 4

    /** The tip hole of [corner]. */
    fun tip(corner: Int): Int = cornerCells(corner, 1)[0]

    /** Home corner of each seat for a table of [seats] (2..6). Seats are in turn order around the star. */
    fun layout(seats: Int): List<Int> = when (seats) {
        2 -> listOf(0, 3)
        3 -> listOf(0, 2, 4)
        4 -> listOf(1, 2, 4, 5)
        5 -> listOf(0, 1, 2, 3, 4)
        6 -> listOf(0, 1, 2, 3, 4, 5)
        else -> throw IllegalArgumentException("Chinese Checkers needs 2 to 6 seats, got $seats")
    }

    fun distance(a: Int, b: Int): Int =
        maxOf(abs(q[a] - q[b]), abs(r[a] - r[b]), abs(s(a) - s(b)))

    /** Screen position of [cell] in hole-pitch units (x right, y down). */
    fun x(cell: Int): Float = q[cell] + r[cell] / 2f
    fun y(cell: Int): Float = r[cell] * 0.8660254f

    /** True iff [seats] is a table size the engine supports. */
    fun validSeatCount(seats: Int) = seats in 2..MAX_SEATS
}

/** One full move: the piece on [from] ends on [to] after a step or a chain of hops. */
data class CCMove(val from: Int, val to: Int)

/** Pure move generation and win rules over an owners array (-1 = empty, else the seat index). */
object CCRules {
    /** Every hole the piece on [from] can finish on: one step to an empty neighbour, or a chain of hops. */
    fun destinations(owners: IntArray, from: Int): Set<Int> {
        val out = LinkedHashSet<Int>()
        val nb = CCBoard.neighbors
        for (d in 0..5) {
            val n = nb[from * 6 + d]
            if (n >= 0 && owners[n] == -1) out += n
        }
        val seen = HashSet<Int>()
        seen += from
        val stack = ArrayDeque<Int>()
        stack.addLast(from)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            for (d in 0..5) {
                val over = nb[c * 6 + d]
                if (over < 0 || over == from || owners[over] == -1) continue
                val land = CCBoard.jumps[c * 6 + d]
                if (land < 0 || owners[land] != -1 || land in seen) continue
                seen += land
                out += land
                stack.addLast(land)
            }
        }
        return out
    }

    /** A route of holes from [from] to [to] (steps or hops), or just both ends when none exists. */
    fun path(owners: IntArray, from: Int, to: Int): List<Int> {
        if (from == to) return listOf(from)
        val nb = CCBoard.neighbors
        for (d in 0..5) if (nb[from * 6 + d] == to && owners[to] == -1) return listOf(from, to)
        val parent = HashMap<Int, Int>()
        parent[from] = -1
        val queue = ArrayDeque<Int>()
        queue.addLast(from)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for (d in 0..5) {
                val over = nb[c * 6 + d]
                if (over < 0 || over == from || owners[over] == -1) continue
                val land = CCBoard.jumps[c * 6 + d]
                if (land < 0 || owners[land] != -1 || land in parent) continue
                parent[land] = c
                if (land == to) {
                    val route = ArrayList<Int>()
                    var cur = to
                    while (cur != -1) { route += cur; cur = parent[cur]!! }
                    return route.reversed()
                }
                queue.addLast(land)
            }
        }
        return listOf(from, to)
    }

    fun movesFor(owners: IntArray, seat: Int): List<CCMove> {
        val out = ArrayList<CCMove>()
        for (c in owners.indices) if (owners[c] == seat) destinations(owners, c).forEach { out += CCMove(c, it) }
        return out
    }

    fun hasMove(owners: IntArray, seat: Int): Boolean {
        for (c in owners.indices) if (owners[c] == seat && destinations(owners, c).isNotEmpty()) return true
        return false
    }

    fun apply(owners: IntArray, move: CCMove): IntArray {
        val copy = owners.copyOf()
        copy[move.to] = copy[move.from]
        copy[move.from] = -1
        return copy
    }

    fun goalCells(homeCorner: Int, pieces: Int): IntArray =
        CCBoard.cornerCells(CCBoard.opposite(homeCorner), CCBoard.rowsFor(pieces))

    /** True when every one of [seat]'s pieces sits in the corner opposite its home. */
    fun hasWon(owners: IntArray, seat: Int, homeCorner: Int, pieces: Int): Boolean {
        val goal = goalCells(homeCorner, pieces)
        var inGoal = 0
        for (c in goal) if (owners[c] == seat) inGoal++
        return inGoal == pieces
    }

    /** Sum of every piece's hex distance to the goal tip: lower is further ahead. */
    fun remaining(owners: IntArray, seat: Int, homeCorner: Int): Int {
        val tip = CCBoard.tip(CCBoard.opposite(homeCorner))
        var sum = 0
        for (c in owners.indices) if (owners[c] == seat) sum += CCBoard.distance(c, tip)
        return sum
    }

    fun initialOwners(homes: List<Int>, pieces: Int): IntArray {
        val owners = IntArray(CCBoard.CELLS) { -1 }
        val rows = CCBoard.rowsFor(pieces)
        homes.forEachIndexed { seat, corner -> CCBoard.cornerCells(corner, rows).forEach { owners[it] = seat } }
        return owners
    }
}

/**
 * The computer player. All levels pick from the same move list; they differ in how they rank it:
 *  - EASY: a quarter of its moves are plain random, the rest a random pick among its four best, so it
 *    drifts forward but wastes tempo and leaves stragglers.
 *  - MEDIUM: the move that closes the most distance to the goal tip, rear-most piece first.
 *  - HARD: also looks one of its own moves further ahead (the best follow-up hop chain it creates)
 *    and penalizes stragglers, so it builds ladders and brings the last pieces along.
 * Nothing here touches shared state, so it is safe to run on a background dispatcher.
 */
object CCBot {
    fun choose(
        owners: IntArray,
        seat: Int,
        homeCorner: Int,
        difficulty: CpuDifficulty,
        random: Random,
        avoidReverse: CCMove? = null
    ): CCMove? {
        val tip = CCBoard.tip(CCBoard.opposite(homeCorner))
        val all = CCRules.movesFor(owners, seat)
        if (all.isEmpty()) return null
        val moves = all.filterNot { avoidReverse != null && it.from == avoidReverse.to && it.to == avoidReverse.from }
            .ifEmpty { all }
        if (difficulty == CpuDifficulty.EASY && random.nextInt(4) == 0) return moves[random.nextInt(moves.size)]

        fun gain(m: CCMove) = CCBoard.distance(m.from, tip) - CCBoard.distance(m.to, tip)
        val scored = moves.map { m ->
            var value = gain(m) * 10.0
            // Prefer moving the piece furthest behind: it is the one that costs the game.
            value += CCBoard.distance(m.from, tip) * 0.25
            if (difficulty == CpuDifficulty.HARD) {
                val after = CCRules.apply(owners, m)
                var follow = 0
                var worst = 0
                for (c in after.indices) if (after[c] == seat) {
                    worst = maxOf(worst, CCBoard.distance(c, tip))
                    for (d in CCRules.destinations(after, c)) {
                        val g = CCBoard.distance(c, tip) - CCBoard.distance(d, tip)
                        if (g > follow) follow = g
                    }
                }
                value += follow * 6.0
                value -= worst * 1.5
            }
            m to value
        }
        val ranked = scored.sortedByDescending { it.second }
        return when (difficulty) {
            CpuDifficulty.EASY -> ranked.take(4).random(random).first
            else -> {
                val best = ranked[0].second
                ranked.filter { it.second >= best - 1e-9 }.random(random).first
            }
        }
    }
}
