package com.gamesuite.games.gomoku

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** Edge length of the Gomoku board (the classic 15 x 15 intersections). */
const val GOMOKU_SIZE = 15

/** Stones in a row that win. */
const val GOMOKU_WIN_LENGTH = 5

/**
 * The pure Gomoku rules and CPU search for one rule set. Immutable after construction, so a search can run
 * on a background thread against the engine it captured. A board is an [IntArray] of [n] * [n] entries:
 * [EMPTY] (-1) or the owning seat (0 or 1).
 *
 * RULES: players alternate placing one stone on an empty intersection; the first to own an unbroken line of
 * five (row, column or diagonal) wins. With [exactFive] false (free-style) six or more in a row also wins;
 * with it true only a line of EXACTLY five wins, so an overline does not count. A full board with no winner
 * is a draw.
 *
 * CPU: negamax alpha-beta over a pruned candidate list (empty points near existing stones, ranked by a
 * pattern heuristic), with a window-scan evaluation at the leaves. [CpuDifficulty.EASY] is a one-ply
 * pattern player that blocks only some of the time and picks among its top choices at random, so a person
 * can beat it; MEDIUM looks two plies ahead; HARD four, under a time cap.
 */
internal class GomokuEngine(val n: Int = GOMOKU_SIZE, val exactFive: Boolean = false) {
    val cellCount: Int = n * n

    fun emptyBoard(): IntArray = IntArray(cellCount) { EMPTY }

    fun toBoard(cells: List<Int?>): IntArray = IntArray(cellCount) { cells[it] ?: EMPTY }

    /** Cells of the unbroken run of [player] stones through [idx] along ([dr], [dc]) (both ways), in order. */
    private fun run(board: IntArray, idx: Int, player: Int, dr: Int, dc: Int): List<Int> {
        val row = idx / n
        val col = idx % n
        var r = row
        var c = col
        while (r - dr in 0 until n && c - dc in 0 until n && board[(r - dr) * n + (c - dc)] == player) {
            r -= dr
            c -= dc
        }
        val out = ArrayList<Int>(6)
        while (r in 0 until n && c in 0 until n && board[r * n + c] == player) {
            out += r * n + c
            r += dr
            c += dc
        }
        return out
    }

    /**
     * The winning line [player] owns through [idx] (which must hold their stone), or null. The line is the
     * whole run, so for an overline in free-style play it is longer than five.
     */
    fun winLine(board: IntArray, idx: Int, player: Int): List<Int>? {
        if (idx !in 0 until cellCount || board[idx] != player) return null
        for ((dr, dc) in DIRECTIONS) {
            val line = run(board, idx, player, dr, dc)
            if (if (exactFive) line.size == GOMOKU_WIN_LENGTH else line.size >= GOMOKU_WIN_LENGTH) return line
        }
        return null
    }

    fun isFull(board: IntArray): Boolean = board.none { it == EMPTY }

    /** True when [player] playing [idx] would win right now (leaves the board unchanged). */
    private fun wins(board: IntArray, idx: Int, player: Int): Boolean {
        board[idx] = player
        val w = winLine(board, idx, player) != null
        board[idx] = EMPTY
        return w
    }

    // ---- heuristics ----

    /** Pattern value of [player] stone at [idx] (empty) from the contiguous run it would form, per direction. */
    private fun patternValue(board: IntArray, idx: Int, player: Int): Int {
        val row = idx / n
        val col = idx % n
        var total = 0
        for ((dr, dc) in DIRECTIONS) {
            var count = 1
            var open = 0
            var r = row + dr
            var c = col + dc
            while (r in 0 until n && c in 0 until n && board[r * n + c] == player) { count++; r += dr; c += dc }
            if (r in 0 until n && c in 0 until n && board[r * n + c] == EMPTY) open++
            r = row - dr
            c = col - dc
            while (r in 0 until n && c in 0 until n && board[r * n + c] == player) { count++; r -= dr; c -= dc }
            if (r in 0 until n && c in 0 until n && board[r * n + c] == EMPTY) open++
            total += when {
                count >= 5 -> 100_000
                count == 4 -> if (open == 2) 10_000 else if (open == 1) 1_000 else 0
                count == 3 -> if (open == 2) 1_000 else if (open == 1) 100 else 0
                count == 2 -> if (open == 2) 100 else if (open == 1) 10 else 0
                else -> 1
            }
        }
        return total
    }

    /** How good [idx] looks for [me] to take: their own gain plus the opponent's, which blocking removes. */
    private fun rank(board: IntArray, idx: Int, me: Int): Int =
        patternValue(board, idx, me) + patternValue(board, idx, 1 - me) * 9 / 10 + centrality(idx)

    private fun centrality(idx: Int): Int {
        val h = n / 2
        return (h - kotlin.math.abs(idx / n - h) + h - kotlin.math.abs(idx % n - h)) / 2
    }

    /** Empty points within two steps of any stone, best first by [rank]; the centre when the board is empty. */
    fun candidates(board: IntArray, me: Int, limit: Int): List<Int> {
        val near = BooleanArray(cellCount)
        var any = false
        for (i in 0 until cellCount) {
            if (board[i] == EMPTY) continue
            any = true
            val r0 = i / n
            val c0 = i % n
            for (r in maxOf(0, r0 - 2)..minOf(n - 1, r0 + 2)) {
                for (c in maxOf(0, c0 - 2)..minOf(n - 1, c0 + 2)) near[r * n + c] = true
            }
        }
        if (!any) return listOf((n / 2) * n + n / 2)
        val list = (0 until cellCount).filter { near[it] && board[it] == EMPTY }
        if (list.isEmpty()) return (0 until cellCount).filter { board[it] == EMPTY }.take(limit)
        return list.sortedByDescending { rank(board, it, me) }.take(limit)
    }

    /** Window-scan evaluation from [me]'s view: every 5-window holding only one side's stones scores by count. */
    fun evaluate(board: IntArray, me: Int): Int {
        var score = 0
        for ((dr, dc) in DIRECTIONS) {
            for (r in 0 until n) {
                for (c in 0 until n) {
                    val er = r + 4 * dr
                    val ec = c + 4 * dc
                    if (er !in 0 until n || ec !in 0 until n) continue
                    var mine = 0
                    var theirs = 0
                    for (k in 0 until 5) {
                        when (board[(r + k * dr) * n + (c + k * dc)]) {
                            me -> mine++
                            EMPTY -> {}
                            else -> theirs++
                        }
                    }
                    if (mine > 0 && theirs == 0) score += WINDOW[mine]
                    else if (theirs > 0 && mine == 0) score -= WINDOW[theirs] * 11 / 10
                }
            }
        }
        return score
    }

    // ---- search ----

    private fun negamax(
        board: IntArray, toMove: Int, depth: Int, alpha: Int, beta: Int, width: Int, deadline: Long
    ): Int {
        if (depth == 0 || System.nanoTime() > deadline) return evaluate(board, toMove)
        val moves = candidates(board, toMove, width)
        if (moves.isEmpty() || board.none { it == EMPTY }) return 0
        var a = alpha
        var best = Int.MIN_VALUE + 1
        for (m in moves) {
            board[m] = toMove
            val score = if (winLine(board, m, toMove) != null) WIN + depth
            else -negamax(board, 1 - toMove, depth - 1, -beta, -a, width, deadline)
            board[m] = EMPTY
            if (score > best) best = score
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    /**
     * The CPU's move for [me] on [board] (not modified), or null when the board is full. [random] only
     * matters for EASY. [deadlineNanos] stops deeper work: the best root move found so far is returned.
     */
    fun chooseMove(
        board: IntArray, me: Int, difficulty: CpuDifficulty,
        deadlineNanos: Long = Long.MAX_VALUE, random: Random = Random.Default
    ): Int? {
        val work = board.copyOf()
        if (work.none { it == EMPTY }) return null
        val params = paramsFor(difficulty)
        val roots = candidates(work, me, params.rootWidth)
        if (roots.isEmpty()) return null
        // Take a win outright; EASY sometimes overlooks a threat, the stronger levels never do.
        roots.firstOrNull { wins(work, it, me) }?.let { return it }
        val blockCells = roots.filter { wins(work, it, 1 - me) }
        if (blockCells.isNotEmpty() && random.nextFloat() < params.blockChance) return blockCells.first()
        if (difficulty == CpuDifficulty.EASY) {
            val pool = roots.take(3)
            return pool[random.nextInt(pool.size)]
        }
        var best: Int? = null
        var bestScore = Int.MIN_VALUE
        var alpha = Int.MIN_VALUE + 1
        for (m in roots) {
            if (best != null && System.nanoTime() > deadlineNanos) break
            work[m] = me
            val score = -negamax(work, 1 - me, params.depth - 1, Int.MIN_VALUE + 1, -alpha, params.width, deadlineNanos)
            work[m] = EMPTY
            if (score > bestScore) {
                bestScore = score
                best = m
                alpha = maxOf(alpha, score)
            }
        }
        return best ?: roots.first()
    }

    private class Params(val depth: Int, val width: Int, val rootWidth: Int, val blockChance: Float)

    private fun paramsFor(d: CpuDifficulty) = when (d) {
        CpuDifficulty.EASY -> Params(1, 8, 10, 0.7f)
        CpuDifficulty.MEDIUM -> Params(2, 10, 14, 1f)
        CpuDifficulty.HARD -> Params(4, 9, 14, 1f)
    }

    companion object {
        const val EMPTY = -1
        const val WIN = 1_000_000
        val DIRECTIONS = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        private val WINDOW = intArrayOf(0, 1, 10, 100, 1_500, 100_000)
    }
}
