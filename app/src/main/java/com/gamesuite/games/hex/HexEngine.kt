package com.gamesuite.games.hex

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** The supported board edge lengths and the Hub's "board" option ids that select them. */
object HexBoardSizes {
    const val SMALL = 9
    const val STANDARD = 11
    const val LARGE = 13
    val ALL: List<Int> = listOf(SMALL, STANDARD, LARGE)

    /** Hub option id ("9" / "11" / "13") to an edge length; anything else is 11. */
    fun fromOptionId(id: String?): Int = id?.toIntOrNull()?.takeIf { it in ALL } ?: STANDARD
}

/**
 * The pure Hex rules and CPU search for one board edge length ([n] x [n] rhombus of hexagons, row-major
 * index = row * n + col). Immutable, so a search can run on a background thread. A board is an [IntArray]
 * holding [EMPTY] (-1) or the owning seat (0 or 1).
 *
 * GEOMETRY: cell (r, c) touches (r, c-1), (r, c+1), (r-1, c), (r+1, c), (r-1, c+1) and (r+1, c-1).
 * Seat 0 must connect the top edge (row 0) to the bottom edge (row n-1); seat 1 the left edge (col 0) to the
 * right edge (col n-1). Exactly one player can win and there are no draws.
 *
 * PIE RULE: the transpose (r, c) to (c, r) is a symmetry of the board that swaps the two players' goals, so
 * "swap" re-owns the opening stone to the second player at its transposed cell ([swapCell]).
 *
 * CPU: negamax alpha-beta over the cheapest-looking candidates; the evaluation is the difference of the two
 * players' shortest-path distances (empty cells to fill, 0-1 BFS). EASY looks one move ahead and picks among
 * its top choices at random, MEDIUM two moves, HARD four under a time cap.
 */
internal class HexEngine(val n: Int) {
    val cellCount: Int = n * n

    private val neighbors: Array<IntArray> = Array(cellCount) { idx ->
        val r = idx / n
        val c = idx % n
        val out = ArrayList<Int>(6)
        for ((dr, dc) in NEIGHBOR_DELTAS) {
            val nr = r + dr
            val nc = c + dc
            if (nr in 0 until n && nc in 0 until n) out += nr * n + nc
        }
        out.toIntArray()
    }

    fun emptyBoard(): IntArray = IntArray(cellCount) { EMPTY }
    fun toBoard(cells: List<Int?>): IntArray = IntArray(cellCount) { cells[it] ?: EMPTY }
    fun neighborsOf(idx: Int): IntArray = neighbors[idx]

    /** The transposed cell the opening stone moves to when the second player swaps. */
    fun swapCell(idx: Int): Int = (idx % n) * n + idx / n

    private fun startsEdge(p: Int, idx: Int) = if (p == 0) idx / n == 0 else idx % n == 0
    private fun endsEdge(p: Int, idx: Int) = if (p == 0) idx / n == n - 1 else idx % n == n - 1

    /** Shortest chain of [p]'s stones joining their two edges, or null when they are not connected. */
    fun winPath(board: IntArray, p: Int): List<Int>? {
        val parent = IntArray(cellCount) { -2 }
        val queue = IntArray(cellCount)
        var head = 0
        var tail = 0
        for (i in 0 until cellCount) if (board[i] == p && startsEdge(p, i)) { parent[i] = -1; queue[tail++] = i }
        while (head < tail) {
            val v = queue[head++]
            if (endsEdge(p, v)) {
                val path = ArrayList<Int>()
                var x = v
                while (x >= 0) { path += x; x = parent[x] }
                return path.reversed()
            }
            for (w in neighbors[v]) if (board[w] == p && parent[w] == -2) { parent[w] = v; queue[tail++] = w }
        }
        return null
    }

    fun connected(board: IntArray, p: Int): Boolean = winPath(board, p) != null

    /** Fewest empty cells [p] still has to fill to connect, treating the opponent's stones as walls; [BLOCKED] if none. */
    fun distance(board: IntArray, p: Int): Int {
        val dist = IntArray(cellCount) { BLOCKED }
        val ring = IntArray(RING)
        var head = 0
        var tail = 0
        fun cost(i: Int) = if (board[i] == p) 0 else 1
        for (i in 0 until cellCount) {
            if (!startsEdge(p, i) || board[i] == 1 - p) continue
            dist[i] = cost(i)
            if (dist[i] == 0) { head = (head - 1) and (RING - 1); ring[head] = i } else { ring[tail] = i; tail = (tail + 1) and (RING - 1) }
        }
        var best = BLOCKED
        while (head != tail) {
            val v = ring[head]
            head = (head + 1) and (RING - 1)
            if (dist[v] >= best) continue
            if (endsEdge(p, v)) { best = dist[v]; continue }
            for (w in neighbors[v]) {
                if (board[w] == 1 - p) continue
                val nd = dist[v] + cost(w)
                if (nd < dist[w]) {
                    dist[w] = nd
                    if (cost(w) == 0) { head = (head - 1) and (RING - 1); ring[head] = w } else { ring[tail] = w; tail = (tail + 1) and (RING - 1) }
                }
            }
        }
        return best
    }

    /** Opponent distance minus own distance, from [me]'s view; huge when one side is already connected. */
    fun evaluate(board: IntArray, me: Int): Int {
        val mine = distance(board, me)
        val theirs = distance(board, 1 - me)
        if (mine == 0) return WIN
        if (theirs == 0) return -WIN
        return (theirs - mine) * 100 + centrality(board, me)
    }

    private fun centrality(board: IntArray, me: Int): Int {
        val h = n / 2
        var s = 0
        for (i in 0 until cellCount) {
            val o = board[i]
            if (o == EMPTY) continue
            val bonus = h - maxOf(kotlin.math.abs(i / n - h), kotlin.math.abs(i % n - h))
            s += if (o == me) bonus else -bonus
        }
        return s
    }

    /** Empty cells within two steps of any stone (or the centre), best first by a one-move look-ahead when [byEval]. */
    private fun candidates(board: IntArray, me: Int, width: Int, byEval: Boolean): List<Int> {
        val near = BooleanArray(cellCount)
        var any = false
        for (i in 0 until cellCount) {
            if (board[i] == EMPTY) continue
            any = true
            val r0 = i / n
            val c0 = i % n
            for (r in maxOf(0, r0 - 2)..minOf(n - 1, r0 + 2)) for (c in maxOf(0, c0 - 2)..minOf(n - 1, c0 + 2)) near[r * n + c] = true
        }
        if (!any) return listOf((n / 2) * n + n / 2)
        val list = (0 until cellCount).filter { near[it] && board[it] == EMPTY }
        if (list.isEmpty()) return (0 until cellCount).filter { board[it] == EMPTY }.take(width)
        val h = n / 2
        val scored = if (byEval) {
            list.map { m ->
                board[m] = me
                val s = evaluate(board, me)
                board[m] = EMPTY
                m to s
            }
        } else {
            list.map { m ->
                val adj = neighbors[m].count { board[it] != EMPTY }
                m to adj * 10 - maxOf(kotlin.math.abs(m / n - h), kotlin.math.abs(m % n - h))
            }
        }
        return scored.sortedByDescending { it.second }.take(width).map { it.first }
    }

    private fun negamax(board: IntArray, toMove: Int, depth: Int, alpha: Int, beta: Int, width: Int, deadline: Long): Int {
        if (depth == 0 || System.nanoTime() > deadline) return evaluate(board, toMove)
        if (connected(board, 1 - toMove)) return -WIN - depth
        if (distance(board, toMove) == 1) return WIN + depth // one empty cell completes the chain
        val moves = candidates(board, toMove, width, byEval = depth >= 2)
        if (moves.isEmpty()) return 0
        var a = alpha
        var best = Int.MIN_VALUE + 1
        for (m in moves) {
            board[m] = toMove
            val score = if (connected(board, toMove)) WIN + depth else -negamax(board, 1 - toMove, depth - 1, -beta, -a, width, deadline)
            board[m] = EMPTY
            if (score > best) best = score
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    /** The CPU's move for [me] on [board] (not modified), or null when the board is full. */
    fun chooseMove(
        board: IntArray, me: Int, difficulty: CpuDifficulty,
        deadlineNanos: Long = Long.MAX_VALUE, random: Random = Random.Default
    ): Int? {
        val work = board.copyOf()
        if (work.none { it == EMPTY }) return null
        val p = paramsFor(difficulty)
        val roots = candidates(work, me, p.rootWidth, byEval = true)
        if (roots.isEmpty()) return null
        // Complete a connection outright; the stronger levels also stop a one-move win.
        for (m in roots) { work[m] = me; val w = connected(work, me); work[m] = EMPTY; if (w) return m }
        if (difficulty == CpuDifficulty.EASY) {
            val pool = roots.take(if (random.nextInt(4) == 0) 5 else 3)
            return pool[random.nextInt(pool.size)]
        }
        var best: Int? = null
        var bestScore = Int.MIN_VALUE
        var alpha = Int.MIN_VALUE + 1
        for (m in roots) {
            if (best != null && System.nanoTime() > deadlineNanos) break
            work[m] = me
            val score = -negamax(work, 1 - me, p.depth - 1, Int.MIN_VALUE + 1, -alpha, p.width, deadlineNanos)
            work[m] = EMPTY
            if (score > bestScore) { bestScore = score; best = m; alpha = maxOf(alpha, score) }
        }
        return best ?: roots.first()
    }

    /** Whether a CPU second player should take over the opening stone at [cell]: anything not hugging the border. */
    fun shouldSwap(cell: Int): Boolean {
        val r = cell / n
        val c = cell % n
        return minOf(r, c, n - 1 - r, n - 1 - c) >= 2
    }

    /** A modest opening for a CPU that must expect a swap: a stone one step in from the middle of an edge. */
    fun pieOpening(random: Random): Int {
        val h = n / 2
        return when (random.nextInt(4)) {
            0 -> 1 * n + h
            1 -> h * n + 1
            2 -> (n - 2) * n + h
            else -> h * n + (n - 2)
        }
    }

    private class Params(val depth: Int, val width: Int, val rootWidth: Int)

    private fun paramsFor(d: CpuDifficulty) = when (d) {
        CpuDifficulty.EASY -> Params(1, 6, 8)
        CpuDifficulty.MEDIUM -> Params(2, 8, 12)
        CpuDifficulty.HARD -> Params(4, 7, 12)
    }

    companion object {
        const val EMPTY = -1
        const val WIN = 1_000_000
        const val BLOCKED = 10_000
        private const val RING = 8192
        val NEIGHBOR_DELTAS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0, -1 to 1, 1 to -1)
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, HexEngine>()

        /** The shared engine for [size]; [size] must be one of [HexBoardSizes.ALL]. */
        fun of(size: Int): HexEngine {
            require(size in HexBoardSizes.ALL) { "unsupported Hex board size $size" }
            return cache.getOrPut(size) { HexEngine(size) }
        }
    }
}
