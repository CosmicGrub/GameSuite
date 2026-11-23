package com.gamesuite.games.gemcascade

/** Evaluation, hints, the bot and the quest solver. All pure and deterministic given their seeds. */
object GemSolver {
    /** Goal progress in 0..1 (average over goals), with a gradient for pieces that still have to travel. */
    fun progress(e: GemEngine): Double {
        if (e.goals.isEmpty()) return 0.0
        val b = e.board
        var sum = 0.0
        for (g in e.goals) {
            val t = g.target.coerceAtLeast(1).toDouble()
            var cur = g.current.toDouble()
            when (g.type) {
                GoalType.COLLECT_TREASURE -> {
                    var depth = 0.0
                    for (p in b.positionsOf(PieceType.TREASURE)) depth += p.r.toDouble() / (b.rows - 1)
                    cur += depth * 0.6
                }
                GoalType.ESCAPE_BUTTERFLIES -> {
                    var h = 0.0
                    for (p in b.positionsOf(PieceType.BUTTERFLY)) h += (b.rows - 1 - p.r).toDouble() / (b.rows - 1)
                    cur += h * 0.6
                }
                else -> Unit
            }
            sum += (cur / t).coerceAtMost(1.0)
        }
        return sum / e.goals.size + 0.003 * e.obstacleHits + 0.00001 * e.score
    }

    /** How good the move from [before] to [after] was. */
    fun gain(before: GemEngine, after: GemEngine): Double {
        if (after.status == GemStatus.WON) return 1e9
        if (after.status == GemStatus.OVER) return -1e6
        return (after.score - before.score).toDouble() + 3000.0 * (progress(after) - progress(before)) + 15.0 * (after.specialsMade - before.specialsMade)
    }

    class Scored(val action: GemAction, val value: Double, val after: GemEngine)

    /** Every legal action simulated on a copy, best first (stable by board order). */
    fun scoredMoves(e: GemEngine): List<Scored> {
        val out = ArrayList<Scored>()
        for (a in e.legalActions()) {
            val copy = e.copy()
            val r = copy.apply(a)
            if (!r.accepted) continue
            out += Scored(a, gain(e, copy), copy)
        }
        return out.sortedByDescending { it.value }
    }

    /** The hint: the best single move by [gain], or null when there is none. */
    fun hint(e: GemEngine): GemAction? = scoredMoves(e).firstOrNull()?.action

    /**
     * Beam search for a winning move list of a quest level. Returns null when none is found within the level's
     * moves. [seed] only breaks ties, so the result is deterministic.
     */
    fun solve(level: GemLevel, width: Int = 6, seed: Long = 1L): List<GemAction>? {
        val tie = GemRng(seed)
        class Node(val e: GemEngine, val path: List<GemAction>, val h: Double)
        var beam = listOf(Node(GemEngine.forLevel(level), emptyList(), 0.0))
        for (depth in 0 until level.moves + 2) {
            val next = ArrayList<Node>()
            val seen = HashSet<String>()
            for (n in beam) {
                val actions = n.e.legalActions().toMutableList()
                for (i in actions.size - 1 downTo 1) {
                    val j = tie.nextInt(i + 1)
                    val t = actions[i]; actions[i] = actions[j]; actions[j] = t
                }
                for (a in actions) {
                    val c = n.e.copy()
                    if (!c.apply(a).accepted) continue
                    val path = n.path + a
                    if (c.status == GemStatus.WON) return path
                    if (c.status == GemStatus.OVER) continue
                    val key = c.board.encode() + c.rng.state + c.movesLeft + c.score
                    if (!seen.add(key)) continue
                    next += Node(c, path, progress(c))
                }
            }
            if (next.isEmpty()) return null
            beam = next.sortedByDescending { it.h }.take(width)
        }
        return null
    }

    /** Tries ever wider beams. */
    fun solveWithin(level: GemLevel, seed: Long = 1L): List<GemAction>? {
        for (w in intArrayOf(4, 10, 24)) {
            solve(level, w, seed + w)?.let { return it }
        }
        return null
    }
}

enum class BotLevel { EASY, MEDIUM, HARD }

/** A computer player for the arena and for tests. */
class GemBot(val level: BotLevel, seed: Long) {
    private val rng = GemRng(seed)

    fun choose(e: GemEngine): GemAction? {
        val legal = e.legalActions()
        if (legal.isEmpty()) return null
        return when (level) {
            BotLevel.EASY -> legal[rng.nextInt(legal.size)]
            BotLevel.MEDIUM -> {
                if (rng.nextInt(10) < 3) legal[rng.nextInt(legal.size)] else GemSolver.scoredMoves(e).firstOrNull()?.action ?: legal[0]
            }
            BotLevel.HARD -> {
                val first = GemSolver.scoredMoves(e)
                if (first.isEmpty()) return legal[0]
                var best = first[0]
                var bestV = Double.NEGATIVE_INFINITY
                for (s in first.take(4)) {
                    val follow = if (s.after.status == GemStatus.PLAYING) GemSolver.scoredMoves(s.after).firstOrNull()?.value ?: 0.0 else 0.0
                    val v = s.value + 0.5 * follow
                    if (v > bestV) { bestV = v; best = s }
                }
                best.action
            }
        }
    }
}
