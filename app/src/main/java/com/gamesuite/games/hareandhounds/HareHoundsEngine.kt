package com.gamesuite.games.hareandhounds

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The classic Hare and Hounds board: 11 points on a 5 by 3 lattice. A left apex (0), three columns
 * of three points (1-3, 4-6, 7-9) and a right apex (10). Lines join orthogonal neighbours; points
 * whose column plus row is odd also carry the diagonals, which gives the traditional crossing
 * pattern.
 *
 *     1 - 4 - 7
 *   / | X | X | \
 *  0 -2 - 5 - 8 - 10
 *   \ | X | X | /
 *     3 - 6 - 9
 */
object HoundsBoard {
    const val POINTS = 11
    val COLS: IntArray = intArrayOf(0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4)
    val ROWS: IntArray = intArrayOf(1, 0, 1, 2, 0, 1, 2, 0, 1, 2, 1)

    val ADJACENT: List<IntArray> = List(POINTS) { a ->
        (0 until POINTS).filter { b ->
            if (a == b) return@filter false
            val dc = kotlin.math.abs(COLS[a] - COLS[b])
            val dr = kotlin.math.abs(ROWS[a] - ROWS[b])
            dc + dr == 1 || (dc == 1 && dr == 1 && (COLS[a] + ROWS[a]) % 2 == 1)
        }.toIntArray()
    }

    /** The traditional start: hounds on the left apex and above and below it; the hare on the right apex. */
    val START_HOUNDS: List<Int> = listOf(0, 1, 3)
    const val START_HARE = 10

    /** The hounds must trap the hare within this many hound moves or the hare wins. */
    const val MAX_HOUND_MOVES = 20
}

/** A single step of one piece. */
@Serializable
data class HoundsMove(val from: Int, val to: Int)

/** Who is on the move and who can win. */
object HoundsRole {
    const val HOUNDS = 0
    const val HARE = 1
    const val NONE = -1
}

/** [hounds] is a bit mask of the three hound points, [turn] the role to move, [houndMoves] the hound moves made so far. */
data class HoundsPosition(val hounds: Int, val hare: Int, val turn: Int, val houndMoves: Int) {
    fun houndPoints(): List<Int> = (0 until HoundsBoard.POINTS).filter { hounds and (1 shl it) != 0 }
}

object HoundsEngine {
    private const val WIN = 1000

    fun initial(): HoundsPosition =
        HoundsPosition(HoundsBoard.START_HOUNDS.fold(0) { m, p -> m or (1 shl p) }, HoundsBoard.START_HARE, HoundsRole.HOUNDS, 0)

    fun isOccupied(pos: HoundsPosition, p: Int): Boolean = p == pos.hare || pos.hounds and (1 shl p) != 0

    fun legalMoves(pos: HoundsPosition): List<HoundsMove> {
        val out = ArrayList<HoundsMove>()
        if (pos.turn == HoundsRole.HARE) {
            for (n in HoundsBoard.ADJACENT[pos.hare]) if (!isOccupied(pos, n)) out += HoundsMove(pos.hare, n)
        } else {
            for (h in pos.houndPoints()) {
                for (n in HoundsBoard.ADJACENT[h]) {
                    // Hounds never step back: forward (to the right) or straight up and down only.
                    if (!isOccupied(pos, n) && HoundsBoard.COLS[n] >= HoundsBoard.COLS[h]) out += HoundsMove(h, n)
                }
            }
        }
        return out
    }

    /** Applies [m] without validating it. */
    fun apply(pos: HoundsPosition, m: HoundsMove): HoundsPosition =
        if (pos.turn == HoundsRole.HARE) {
            HoundsPosition(pos.hounds, m.to, HoundsRole.HOUNDS, pos.houndMoves)
        } else {
            HoundsPosition((pos.hounds and (1 shl m.from).inv()) or (1 shl m.to), pos.hare, HoundsRole.HARE, pos.houndMoves + 1)
        }

    fun hasEscaped(pos: HoundsPosition): Boolean =
        HoundsBoard.COLS[pos.hare] <= pos.houndPoints().minOf { HoundsBoard.COLS[it] }

    /**
     * [HoundsRole.NONE] while play goes on, otherwise the winning role and why ([reasonOf]).
     * The hare wins by getting past every hound, by leaving the hounds without a move, or by
     * surviving [HoundsBoard.MAX_HOUND_MOVES] hound moves; the hounds win by leaving it no move.
     */
    fun winner(pos: HoundsPosition): Int = when {
        hasEscaped(pos) -> HoundsRole.HARE
        pos.turn == HoundsRole.HARE && legalMoves(pos).isEmpty() -> HoundsRole.HOUNDS
        pos.turn == HoundsRole.HOUNDS && legalMoves(pos).isEmpty() -> HoundsRole.HARE
        pos.turn == HoundsRole.HOUNDS && pos.houndMoves >= HoundsBoard.MAX_HOUND_MOVES -> HoundsRole.HARE
        else -> HoundsRole.NONE
    }

    /** "escaped", "trapped", "stuck" or "timeout" for a finished position; null while play goes on. */
    fun reasonOf(pos: HoundsPosition): String? = when {
        winner(pos) == HoundsRole.NONE -> null
        hasEscaped(pos) -> "escaped"
        pos.turn == HoundsRole.HARE -> "trapped"
        legalMoves(pos).isEmpty() -> "stuck"
        else -> "timeout"
    }

    // ---- exact solution ------------------------------------------------------------------------

    private val memo = ConcurrentHashMap<Int, Int>()

    private fun key(pos: HoundsPosition): Int =
        pos.hare or (pos.hounds shl 4) or (pos.turn shl 15) or (pos.houndMoves shl 16)

    /**
     * The game-theoretic value from the hounds' side: +(1000 - plies) when the hounds force a win,
     * -(1000 - plies) when the hare does. The state space is tiny (a few tens of thousands), so
     * this is solved exactly and memoised; it makes the HARD level (and hints) perfect.
     */
    fun solve(pos: HoundsPosition): Int {
        when (winner(pos)) {
            HoundsRole.HOUNDS -> return WIN
            HoundsRole.HARE -> return -WIN
        }
        val k = key(pos)
        memo[k]?.let { return it }
        val moves = legalMoves(pos)
        var best = if (pos.turn == HoundsRole.HOUNDS) Int.MIN_VALUE else Int.MAX_VALUE
        for (m in moves) {
            val v = solve(apply(pos, m)).let { if (it > 0) it - 1 else it + 1 }
            best = if (pos.turn == HoundsRole.HOUNDS) maxOf(best, v) else minOf(best, v)
        }
        memo[k] = best
        return best
    }

    /** The best move by the exact solution (the quickest win, or the longest defence). */
    fun bestMove(pos: HoundsPosition): HoundsMove? {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return null
        val scored = moves.map { it to solve(apply(pos, it)) }
        return if (pos.turn == HoundsRole.HOUNDS) scored.maxByOrNull { it.second }?.first else scored.minByOrNull { it.second }?.first
    }

    // ---- heuristic play for the lower levels -----------------------------------------------------

    /** Hounds' point of view: higher is better for the hounds. */
    fun evaluate(pos: HoundsPosition): Int {
        when (winner(pos)) {
            HoundsRole.HOUNDS -> return WIN
            HoundsRole.HARE -> return -WIN
        }
        val houndPoints = pos.houndPoints()
        val minCol = houndPoints.minOf { HoundsBoard.COLS[it] }
        // Flood the hare's reachable region, treating hounds as walls.
        val seen = BooleanArray(HoundsBoard.POINTS)
        val stack = ArrayDeque<Int>()
        stack.addLast(pos.hare)
        seen[pos.hare] = true
        var region = 0
        var leaks = false
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            region++
            if (HoundsBoard.COLS[p] <= minCol) leaks = true
            for (n in HoundsBoard.ADJACENT[p]) {
                if (!seen[n] && pos.hounds and (1 shl n) == 0) {
                    seen[n] = true
                    stack.addLast(n)
                }
            }
        }
        val hareMoves = HoundsBoard.ADJACENT[pos.hare].count { !isOccupied(pos, it) }
        var score = 300 - 22 * region - 9 * hareMoves + 4 * houndPoints.sumOf { HoundsBoard.COLS[it] }
        if (leaks) score -= 250
        score -= 2 * pos.houndMoves
        return score
    }

    private fun search(pos: HoundsPosition, depth: Int, alphaIn: Int, betaIn: Int): Int {
        if (winner(pos) != HoundsRole.NONE || depth == 0) return evaluate(pos)
        var alpha = alphaIn
        var beta = betaIn
        val houndsToMove = pos.turn == HoundsRole.HOUNDS
        var best = if (houndsToMove) Int.MIN_VALUE else Int.MAX_VALUE
        for (m in legalMoves(pos)) {
            val v = search(apply(pos, m), depth - 1, alpha, beta)
            if (houndsToMove) {
                best = maxOf(best, v); alpha = maxOf(alpha, best)
            } else {
                best = minOf(best, v); beta = minOf(beta, best)
            }
            if (beta <= alpha) break
        }
        return best
    }

    private fun scoreMoves(pos: HoundsPosition, depth: Int): List<Pair<HoundsMove, Int>> =
        legalMoves(pos).map { it to search(apply(pos, it), depth - 1, Int.MIN_VALUE, Int.MAX_VALUE) }

    /**
     * The CPU's reply for whoever is on the move. EASY mostly takes a one-move-deep best guess but
     * regularly plays a random move; MEDIUM looks four plies ahead on a heuristic; HARD plays the
     * exact solution of the game.
     */
    fun chooseMove(pos: HoundsPosition, difficulty: CpuDifficulty, rng: Random = Random.Default): HoundsMove? {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moves[0]
        val houndsToMove = pos.turn == HoundsRole.HOUNDS
        fun pick(scored: List<Pair<HoundsMove, Int>>, slack: Int): HoundsMove {
            val best = if (houndsToMove) scored.maxOf { it.second } else scored.minOf { it.second }
            val pool = scored.filter { if (houndsToMove) it.second >= best - slack else it.second <= best + slack }
            return pool[rng.nextInt(pool.size)].first
        }
        return when (difficulty) {
            CpuDifficulty.EASY -> if (rng.nextInt(100) < 40) moves[rng.nextInt(moves.size)] else pick(scoreMoves(pos, 1), 25)
            CpuDifficulty.MEDIUM -> pick(scoreMoves(pos, 4), 6)
            CpuDifficulty.HARD -> bestMove(pos)
        }
    }
}

/** Why a tap did nothing, so the screen can say so. */
enum class HoundsWhy {
    GAME_OVER, NOT_YOUR_TURN, CPU_THINKING, NOT_YOUR_PIECE, BLOCKED_PIECE, TOO_FAR, NO_BACKWARDS, OCCUPIED, PICK_PIECE_FIRST
}
