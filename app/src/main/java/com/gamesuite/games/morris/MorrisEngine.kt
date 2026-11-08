package com.gamesuite.games.morris

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * The Nine Men's Morris board: 24 points on three nested squares joined at the midpoints of their
 * sides. Points are numbered ring by ring (outer 0-7, middle 8-15, inner 16-23), clockwise from the
 * top-left corner of each ring: 0 top-left, 1 top, 2 top-right, 3 right, 4 bottom-right, 5 bottom,
 * 6 bottom-left, 7 left.
 */
object MorrisBoard {
    const val POINTS = 24

    /** Grid position (x, y) of each point on a 7 by 7 lattice, for drawing. */
    val COORDS: List<Pair<Int, Int>> = listOf(
        0 to 0, 3 to 0, 6 to 0, 6 to 3, 6 to 6, 3 to 6, 0 to 6, 0 to 3,
        1 to 1, 3 to 1, 5 to 1, 5 to 3, 5 to 5, 3 to 5, 1 to 5, 1 to 3,
        2 to 2, 3 to 2, 4 to 2, 4 to 3, 4 to 4, 3 to 4, 2 to 4, 2 to 3
    )

    val ADJACENT: List<IntArray> = run {
        val adj = List(POINTS) { mutableListOf<Int>() }
        fun link(a: Int, b: Int) {
            adj[a] += b
            adj[b] += a
        }
        for (r in 0..2) for (k in 0..7) link(r * 8 + k, r * 8 + (k + 1) % 8)
        for (r in 0..1) for (k in intArrayOf(1, 3, 5, 7)) link(r * 8 + k, (r + 1) * 8 + k)
        adj.map { it.sorted().toIntArray() }
    }

    /** The 16 mills: four sides of each of the three squares plus the four spokes. */
    val MILLS: List<IntArray> = buildList {
        for (r in 0..2) for (s in 0..3) add(intArrayOf(r * 8 + 2 * s, r * 8 + 2 * s + 1, r * 8 + (2 * s + 2) % 8))
        for (k in intArrayOf(1, 3, 5, 7)) add(intArrayOf(k, 8 + k, 16 + k))
    }

    val MILLS_OF: List<List<IntArray>> = List(POINTS) { p -> MILLS.filter { p in it } }

    /** 0..2 = outer, middle, inner. */
    fun ringOf(point: Int): Int = point / 8

    /** 0..7 = top-left, top, top-right, right, bottom-right, bottom, bottom-left, left. */
    fun slotOf(point: Int): Int = point % 8
}

/**
 * One turn. [from] is -1 for a placement; [remove] is -1 unless the move closed a mill and so
 * takes an opponent's man off the board.
 */
@Serializable
data class MorrisMove(val from: Int, val to: Int, val remove: Int = -1) {
    val isPlacement: Boolean get() = from < 0
    val formsMill: Boolean get() = remove >= 0
}

/** The numbers of men a game can start with and the Hub's option ids for them. */
object MorrisMen {
    const val SIX = 6
    const val NINE = 9
    val ALL: List<Int> = listOf(SIX, NINE)
    fun fromOptionId(id: String?): Int = id?.toIntOrNull()?.takeIf { it in ALL } ?: NINE
}

/** points: -1 empty, else the owning player index. [hand] is men still to be placed. */
class MorrisPosition(val points: IntArray, val hand: IntArray, val side: Int, val quiet: Int)

/** Rules and search. Immutable, so a search may run on another thread against a copied position. */
class MorrisEngine(val men: Int = MorrisMen.NINE, val flying: Boolean = true) {

    fun initial(first: Int) = MorrisPosition(IntArray(MorrisBoard.POINTS) { -1 }, intArrayOf(men, men), first, 0)

    fun count(pos: MorrisPosition, p: Int): Int = pos.points.count { it == p }

    fun total(pos: MorrisPosition, p: Int): Int = count(pos, p) + pos.hand[p]

    fun canFly(pos: MorrisPosition, p: Int): Boolean = flying && pos.hand[p] == 0 && count(pos, p) == 3

    private fun inMill(points: IntArray, point: Int, p: Int): Boolean =
        MorrisBoard.MILLS_OF[point].any { m -> m.all { points[it] == p } }

    fun legalMoves(pos: MorrisPosition): List<MorrisMove> {
        val p = pos.side
        val out = ArrayList<MorrisMove>()
        val pts = pos.points
        if (pos.hand[p] > 0) {
            for (to in 0 until MorrisBoard.POINTS) if (pts[to] == -1) addWithRemovals(pos, -1, to, out)
        } else {
            val fly = canFly(pos, p)
            for (from in 0 until MorrisBoard.POINTS) {
                if (pts[from] != p) continue
                if (fly) {
                    for (to in 0 until MorrisBoard.POINTS) if (pts[to] == -1) addWithRemovals(pos, from, to, out)
                } else {
                    for (to in MorrisBoard.ADJACENT[from]) if (pts[to] == -1) addWithRemovals(pos, from, to, out)
                }
            }
        }
        return out
    }

    private fun addWithRemovals(pos: MorrisPosition, from: Int, to: Int, out: MutableList<MorrisMove>) {
        val p = pos.side
        val pts = pos.points.copyOf()
        if (from >= 0) pts[from] = -1
        pts[to] = p
        if (!inMill(pts, to, p)) {
            out += MorrisMove(from, to)
            return
        }
        val opp = 1 - p
        val free = (0 until MorrisBoard.POINTS).filter { pts[it] == opp && !inMill(pts, it, opp) }
        val candidates = free.ifEmpty { (0 until MorrisBoard.POINTS).filter { pts[it] == opp } }
        if (candidates.isEmpty()) out += MorrisMove(from, to) else candidates.forEach { out += MorrisMove(from, to, it) }
    }

    /** Applies [m] without validating it; callers check it against [legalMoves] first. */
    fun apply(pos: MorrisPosition, m: MorrisMove): MorrisPosition {
        val pts = pos.points.copyOf()
        val hand = pos.hand.copyOf()
        val p = pos.side
        if (m.from < 0) hand[p]-- else pts[m.from] = -1
        pts[m.to] = p
        if (m.remove >= 0) pts[m.remove] = -1
        val quiet = if (m.from < 0 || m.remove >= 0) 0 else pos.quiet + 1
        return MorrisPosition(pts, hand, 1 - p, quiet)
    }

    /** [NONE] while play goes on, [DRAW], or the winning player index. */
    fun outcome(pos: MorrisPosition): Int {
        for (p in 0..1) if (total(pos, p) < 3) return 1 - p
        if (pos.hand[pos.side] == 0 && legalMoves(pos).isEmpty()) return 1 - pos.side
        if (pos.quiet >= QUIET_LIMIT) return DRAW
        return NONE
    }

    /** Men of [p] with no empty neighbour (meaningful once nobody is flying). */
    fun blocked(pos: MorrisPosition, p: Int): Int =
        (0 until MorrisBoard.POINTS).count { i ->
            pos.points[i] == p && MorrisBoard.ADJACENT[i].none { pos.points[it] == -1 }
        }

    fun evaluate(pos: MorrisPosition, me: Int): Int {
        val opp = 1 - me
        var s = 100 * (total(pos, me) - total(pos, opp))
        for (m in MorrisBoard.MILLS) {
            var mine = 0
            var theirs = 0
            for (i in m) {
                if (pos.points[i] == me) mine++ else if (pos.points[i] == opp) theirs++
            }
            if (mine == 3) s += 18
            if (theirs == 3) s -= 18
            if (mine == 2 && theirs == 0) s += 9
            if (theirs == 2 && mine == 0) s -= 9
        }
        if (pos.hand[0] == 0 && pos.hand[1] == 0) s += 7 * (blocked(pos, opp) - blocked(pos, me))
        return s
    }

    private class Ctx(val deadline: Long) {
        var nodes = 0
        var aborted = false
        fun tick() {
            if ((++nodes and 255) == 0 && System.nanoTime() > deadline) aborted = true
        }
    }

    private fun ordered(moves: List<MorrisMove>): List<MorrisMove> =
        moves.sortedByDescending { (if (it.remove >= 0) 4 else 0) + (if (it.from < 0) 1 else 0) }

    private fun negamax(pos: MorrisPosition, depth: Int, alphaIn: Int, beta: Int, ctx: Ctx): Int {
        ctx.tick()
        if (ctx.aborted) return 0
        val out = outcome(pos)
        if (out != NONE) return if (out == DRAW) 0 else if (out == pos.side) WIN + depth else -(WIN + depth)
        if (depth == 0) return evaluate(pos, pos.side)
        var alpha = alphaIn
        var best = Int.MIN_VALUE + 1
        for (m in ordered(legalMoves(pos))) {
            val v = -negamax(apply(pos, m), depth - 1, -beta, -alpha, ctx)
            if (ctx.aborted) return 0
            if (v > best) best = v
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** Scores every root move at [depth]; null when the deadline interrupted the pass. */
    private fun scoreRoot(pos: MorrisPosition, moves: List<MorrisMove>, depth: Int, ctx: Ctx): List<Pair<MorrisMove, Int>>? {
        val scored = ArrayList<Pair<MorrisMove, Int>>(moves.size)
        for (m in moves) {
            val v = -negamax(apply(pos, m), depth - 1, -Int.MAX_VALUE, Int.MAX_VALUE, ctx)
            if (ctx.aborted) return null
            scored += m to v
        }
        return scored
    }

    /**
     * The CPU's reply. EASY looks one move ahead and often plays a near-best or plain random move,
     * so it is beatable and makes human-looking slips; MEDIUM searches three plies; HARD deepens
     * iteratively (up to six plies) inside [timeBudgetMs].
     */
    fun chooseMove(
        pos: MorrisPosition,
        difficulty: CpuDifficulty,
        rng: Random = Random.Default,
        timeBudgetMs: Long = 1400L
    ): MorrisMove? {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moves[0]
        val ctx = Ctx(System.nanoTime() + timeBudgetMs * 1_000_000L)
        return when (difficulty) {
            CpuDifficulty.EASY -> {
                if (rng.nextInt(100) < 25) return moves[rng.nextInt(moves.size)]
                val scored = scoreRoot(pos, moves, 1, Ctx(Long.MAX_VALUE)) ?: return quickBest(pos, moves, rng)
                val best = scored.maxOf { it.second }
                scored.filter { it.second >= best - 40 }.let { it[rng.nextInt(it.size)].first }
            }
            CpuDifficulty.MEDIUM -> {
                val scored = scoreRoot(pos, ordered(moves), 3, ctx) ?: return quickBest(pos, moves, rng) // timed out: still a sensible move
                val best = scored.maxOf { it.second }
                scored.filter { it.second >= best - 3 }.let { it[rng.nextInt(it.size)].first }
            }
            CpuDifficulty.HARD -> deepest(pos, moves, 6, ctx, rng)
        }
    }

    /** The best one-ply move (uninterruptible and cheap): the fallback when a deeper search runs out of time. */
    private fun quickBest(pos: MorrisPosition, moves: List<MorrisMove>, rng: Random?): MorrisMove {
        val scored = scoreRoot(pos, moves, 1, Ctx(Long.MAX_VALUE)) ?: return moves[0]
        val top = scored.maxOf { it.second }
        val pool = scored.filter { it.second >= top }
        return if (rng != null) pool[rng.nextInt(pool.size)].first else pool[0].first
    }

    private fun deepest(pos: MorrisPosition, moves: List<MorrisMove>, maxDepth: Int, ctx: Ctx, rng: Random?): MorrisMove {
        var best: MorrisMove = quickBest(pos, moves, rng) // what we play if even depth 2 is cut off
        var ordering = ordered(moves)
        for (depth in 2..maxDepth) {
            val scored = scoreRoot(pos, ordering, depth, ctx) ?: break
            val top = scored.maxOf { it.second }
            val pool = scored.filter { it.second >= top }
            best = if (rng != null) pool[rng.nextInt(pool.size)].first else pool[0].first
            ordering = scored.sortedByDescending { it.second }.map { it.first }
            if (top >= WIN) break
        }
        return best
    }

    /** The strongest move found within about [timeBudgetMs], regardless of CPU level; deterministic. */
    fun hint(pos: MorrisPosition, timeBudgetMs: Long = 700L): MorrisMove? {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moves[0]
        return deepest(pos, moves, 5, Ctx(System.nanoTime() + timeBudgetMs * 1_000_000L), null)
    }

    companion object {
        const val NONE = -2
        const val DRAW = -1
        const val WIN = 100_000

        /** Movement plies without a placement or a mill after which the game is drawn. */
        const val QUIET_LIMIT = 100
    }
}

/** Why a tap did nothing, so the screen can say so instead of staying silent. */
enum class MorrisWhy {
    GAME_OVER, NOT_YOUR_TURN, CPU_THINKING, POINT_TAKEN, ENEMY_MAN, BLOCKED_MAN, TOO_FAR,
    PICK_OWN_MAN, REMOVE_OWN_MAN, PROTECTED_MAN, NOTHING_THERE
}
