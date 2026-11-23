package com.gamesuite.games.spider

import kotlin.random.Random

/** A candidate move with its heuristic worth, shared by the hint and the solver's move ordering. */
internal class ScoredMove(val move: SpiderMove, val score: Int)

/**
 * Move ranking shared by [SpiderHints] and [SpiderSolver]. It deliberately skips moves that cannot
 * help: shifting a whole column onto an empty column, and re-seating a run that already sits on a
 * legal card unless the new home is the same suit (which joins two suited chains).
 */
internal object SpiderMoveRanking {
    fun candidates(s: SpiderState, jitter: Random? = null, allowAttached: Boolean = false, minScore: Int = Int.MIN_VALUE): List<ScoredMove> {
        val out = ArrayList<ScoredMove>()
        for (from in 0 until SPIDER_COLUMNS) {
            val cards = s.columns[from]
            if (cards.isEmpty()) continue
            val start = SpiderRules.runStart(s, from)
            for (index in start until cards.size) {
                for (to in 0 until SPIDER_COLUMNS) {
                    if (!SpiderRules.canDrop(s, from, index, to)) continue
                    var score = score(s, SpiderMove(from, index, to), allowAttached) ?: continue
                    if (score < minScore) continue
                    if (jitter != null) score += jitter.nextInt(0, 40)
                    out += ScoredMove(SpiderMove(from, index, to), score)
                }
            }
        }
        out.sortByDescending { it.score }
        return out
    }

    /** Null means "never worth playing". */
    fun score(s: SpiderState, m: SpiderMove, allowAttached: Boolean = false): Int? {
        val suits = s.suits
        val src = s.columns[m.from]
        val dst = s.columns[m.to]
        val card = src[m.index]
        val moved = src.size - m.index
        val down = s.faceDown[m.from]
        val destTop = dst.lastOrNull()
        val joins = destTop != null && SpiderRules.suitOf(destTop, suits) == SpiderRules.suitOf(card, suits)
        val below = if (m.index > down) src[m.index - 1] else null
        val attached = below != null && SpiderRules.rankOf(below) == SpiderRules.rankOf(card) + 1
        val belowJoined = attached && SpiderRules.suitOf(below!!, suits) == SpiderRules.suitOf(card, suits)
        val wholeColumn = m.index == 0 && down == 0

        if (destTop == null && wholeColumn) return null
        val pointlessReseat = attached && !(joins && !belowJoined)
        if (pointlessReseat && !allowAttached) return null

        var score = if (pointlessReseat) -30 else 0
        if (joins) score += 60 + 4 * moved
        else if (destTop != null) score -= 10
        if (belowJoined) score -= 70
        if (m.index == down && down > 0) score += 40 // the card underneath turns face up
        if (wholeColumn && destTop != null) score += 30 // clears a column
        if (destTop == null) score -= 5 // spends an empty column
        // Does the destination end up as a finished King-to-Ace run?
        val chain = moved + if (joins) SpiderRules.suitedChainAtTop(dst, suits, s.faceDown[m.to]) else 0
        if (joins && chain >= SPIDER_RUN_LENGTH) score += 500
        score += moved
        return score
    }
}

object SpiderHints {
    /**
     * The best next step, or null when nothing is left (no useful move and no row to deal). A row is
     * suggested only when no move scores above zero, which matches how strong players use the stock.
     */
    fun suggest(s: SpiderState): SpiderHint? {
        if (s.won) return null
        val best = SpiderMoveRanking.candidates(s).firstOrNull()
        if (best != null && best.score > 0) return SpiderHint.Move(best.move)
        if (SpiderRules.canDeal(s)) return SpiderHint.Deal
        return best?.let { SpiderHint.Move(it.move) }
    }
}

/**
 * A budgeted depth-first solver. It plays by the real rules ([SpiderRules]) with heuristic move
 * ordering and a visited-set over column-order-independent positions. It can PROVE a deal winnable
 * (a full line of play is found) but cannot prove one unwinnable: running out of [nodeBudget] just
 * means "not shown to be winnable". One-suit deals are normally solved in a few thousand nodes,
 * two-suit deals often within tens of thousands, four-suit deals rarely within a phone-friendly
 * budget, so for those [SpiderDealer] falls back to the candidate that got furthest.
 */
class SpiderSolver(
    private val nodeBudget: Int,
    private val restarts: Int = 1,
    private val seed: Long = 0L,
    /** Polled every [STOP_POLL_NODES] nodes and between restarts; true abandons the search ("not shown winnable"). */
    private val shouldStop: () -> Boolean = { false }
) {
    /** [firstStep] is the first move/deal of the winning line, or of the line that finished the most runs; null when no line made progress. */
    class Result(
        val solved: Boolean,
        val bestRuns: Int,
        val nodes: Int,
        val firstStep: SpiderHint? = null,
        /** The whole winning line (a null entry is "deal a row"); empty unless [solved]. */
        val line: List<SpiderMove?> = emptyList()
    )

    private class Frame(val state: SpiderState, val moves: List<SpiderMove?>) {
        var next = 0
    }

    fun solve(start: SpiderState): Result {
        if (start.won) return Result(true, SPIDER_RUNS_TO_WIN, 0)
        val per = (nodeBudget / restarts.coerceAtLeast(1)).coerceAtLeast(1)
        var nodes = 0
        var bestRuns = start.completedRuns
        var bestStep: SpiderHint? = null
        for (r in 0 until restarts.coerceAtLeast(1)) {
            if (shouldStop()) break
            val rng = if (r == 0) null else Random(seed * 31 + r)
            val res = dfs(start, per, rng, allowAttached = r > 0 && r % 2 == 0, minScore = if (r % 2 == 1) 1 else Int.MIN_VALUE)
            nodes += res.nodes
            if (res.solved) return Result(true, SPIDER_RUNS_TO_WIN, nodes, res.firstStep, res.line)
            if (res.bestRuns > bestRuns) {
                bestRuns = res.bestRuns
                bestStep = res.firstStep
            }
        }
        return Result(false, bestRuns, nodes, bestStep)
    }

    private companion object {
        const val STOP_POLL_NODES = 256
    }

    private fun asHint(step: SpiderMove?): SpiderHint = if (step == null) SpiderHint.Deal else SpiderHint.Move(step)

    private fun dfs(start: SpiderState, budget: Int, rng: Random?, allowAttached: Boolean, minScore: Int): Result {
        val seen = HashSet<Long>()
        seen += hash(start)
        var nodes = 0
        var bestRuns = start.completedRuns
        var bestFirst: SpiderHint? = null
        val stack = ArrayList<Frame>()
        stack += Frame(start, orderedMoves(start, rng, allowAttached, minScore))
        while (stack.isNotEmpty()) {
            val frame = stack[stack.size - 1]
            if (frame.next >= frame.moves.size) {
                stack.removeAt(stack.size - 1)
                continue
            }
            val move = frame.moves[frame.next++]
            val outcome = (if (move == null) SpiderRules.applyDeal(frame.state) else SpiderRules.applyMove(frame.state, move)) ?: continue
            val child = outcome.state
            if (!seen.add(hash(child))) continue
            nodes++
            val first = asHint(stack[0].moves[stack[0].next - 1])
            if (child.won) return Result(true, SPIDER_RUNS_TO_WIN, nodes, first, stack.map { it.moves[it.next - 1] })
            if (child.completedRuns > bestRuns) {
                bestRuns = child.completedRuns
                bestFirst = first
            }
            if (nodes >= budget) return Result(false, bestRuns, nodes, bestFirst)
            if (nodes % STOP_POLL_NODES == 0 && shouldStop()) return Result(false, bestRuns, nodes, bestFirst)
            stack += Frame(child, orderedMoves(child, rng, allowAttached, minScore))
        }
        return Result(false, bestRuns, nodes, bestFirst)
    }

    /** Ranked moves; a null entry is "deal a row", tried last. */
    private fun orderedMoves(s: SpiderState, rng: Random?, allowAttached: Boolean, minScore: Int): List<SpiderMove?> {
        val out = ArrayList<SpiderMove?>()
        for (c in SpiderMoveRanking.candidates(s, rng, allowAttached, minScore)) out += c.move
        if (SpiderRules.canDeal(s)) out += null
        return out
    }

    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Position hash that ignores the left-to-right order of the columns (they are interchangeable). */
    private fun hash(s: SpiderState): Long {
        var total = mix(s.stock.size.toLong() * 131 + s.completedRuns)
        for (c in 0 until SPIDER_COLUMNS) {
            var h = mix(s.faceDown[c].toLong() + 17)
            for (card in s.columns[c]) h = mix(h * 31 + card)
            total += h
        }
        return total
    }
}

/**
 * Builds opening deals. A plain deal is a seeded shuffle. The "winnable" option produces a deal whose
 * winnability is PROVEN: the winning line is replayed with the real rules ([SpiderRules]) before the
 * deal is returned, and [Deal.line] carries it so a test (or a curious player) can replay it too.
 *
 * Construction ([constructWithLine]): play a game forward with a greedy policy while the hidden cards
 * (the face-down tableau cards and the stock) are still undecided; each time one is revealed, pick it
 * from the cards not yet used so as to favour the player's chain-building (biased, not always, so the
 * deal still plays like Spider). Once the stock is gone every remaining hidden card is fixed at random
 * and [SpiderSolver] finishes the game. If that yields a won game, every hidden slot has its card and
 * the recorded line wins. Failing that, ordinary shuffles are searched with the solver, and failing
 * that the plain deal is returned unproven (`provenWinnable = false`), so the UI never claims more than
 * is known. Everything is deterministic in `(seed, suits)`, so the daily deal is identical for everyone.
 */
object SpiderDealer {
    class Deal(val state: SpiderState, val provenWinnable: Boolean, val line: List<SpiderMove?> = emptyList())

    private const val PLACEHOLDER = 1000

    fun shuffledDeck(random: Random): List<Int> = (0 until SPIDER_DECK_SIZE).toList().shuffled(random)

    fun plain(random: Random, suits: Int): SpiderState = SpiderRules.openingState(shuffledDeck(random), suits)

    fun seededRandom(seed: Long, suits: Int, attempt: Int = 0): Random =
        Random(seed * 1_000_003L + suits * 7919L + attempt * 104_729L)

    /** True when playing [line] from [start] with the real rules wins. */
    fun replayWins(start: SpiderState, line: List<SpiderMove?>): Boolean {
        var s = start
        for (step in line) {
            s = (if (step == null) SpiderRules.applyDeal(s) else SpiderRules.applyMove(s, step))?.state ?: return false
        }
        return s.won
    }

    /** Wall-clock safety cap on the whole winnable search. The node budgets already bound it, but a 4-suit worst
     *  case on a slow phone is minutes; past this the unproven plain deal is returned (the UI never claims more than is known). */
    const val SEARCH_CAP_MS = 12_000L

    /**
     * [isCancelled] is polled throughout (leaving the screen stops the search); [capMs] bounds it by wall clock.
     * Either way the answer is the plain, honestly-unproven deal for `(seed, suits)`.
     */
    fun winnable(
        seed: Long, suits: Int, attempts: Int = 30,
        capMs: Long = SEARCH_CAP_MS,
        isCancelled: () -> Boolean = { false },
        nowNanos: () -> Long = System::nanoTime
    ): Deal {
        val deadline = nowNanos() + capMs * 1_000_000L
        val stop = { isCancelled() || nowNanos() >= deadline }
        for (attempt in 0 until attempts) {
            if (stop()) break
            val bias = ((when (suits) { 1 -> 0.5; 2 -> 0.65; else -> 0.8 }) + attempt * 0.005).coerceAtMost(0.95)
            val built = constructWithLine(seededRandom(seed, suits, attempt), suits, bias, stop)
            if (built != null) return Deal(built.state, provenWinnable = true, line = built.line)
        }
        for (attempt in 0 until 6) {
            if (stop()) break
            val state = plain(seededRandom(seed, suits, 1000 + attempt), suits)
            val res = SpiderSolver(20_000, 4, seed, stop).solve(state)
            if (res.solved && replayWins(state, res.line)) return Deal(state, provenWinnable = true, line = res.line)
        }
        return Deal(plain(seededRandom(seed, suits), suits), provenWinnable = false)
    }

    /** A constructed deal and the winning line (a null entry is "deal a row") that proves it can be won. */
    class Constructed(val state: SpiderState, val line: List<SpiderMove?>)

    /** Why the last [constructWithLine] attempt failed (diagnostics for tests). */
    @Volatile
    internal var lastFailure: String = ""

    private fun fail(reason: String): Constructed? {
        lastFailure = reason
        return null
    }

    fun construct(rng: Random, suits: Int, bias: Double): SpiderState? = constructWithLine(rng, suits, bias)?.state

    private fun productive(s: SpiderState, m: SpiderMove): Boolean {
        val card = s.columns[m.from][m.index]
        val destTop = s.columns[m.to].lastOrNull()
        val joins = destTop != null && SpiderRules.suitOf(destTop, s.suits) == SpiderRules.suitOf(card, s.suits)
        val flips = m.index == s.faceDown[m.from] && s.faceDown[m.from] > 0
        return joins || flips
    }

    fun constructWithLine(rng: Random, suits: Int, bias: Double, shouldStop: () -> Boolean = { false }): Constructed? {
        val pool = (0 until SPIDER_DECK_SIZE).toMutableList()
        val deck = IntArray(SPIDER_DECK_SIZE) { -1 }
        var cursor = 0
        val columns = List(SPIDER_COLUMNS) { c ->
            val n = if (c < 4) 6 else 5
            List(n) { PLACEHOLDER + cursor++ }
        }
        var s = SpiderState(
            suits = suits,
            columns = columns,
            faceDown = columns.map { it.size - 1 },
            stock = (cursor until SPIDER_DECK_SIZE).map { PLACEHOLDER + it }
        )

        fun choose(tops: List<Int?>): Int {
            if (rng.nextDouble() < bias) {
                val wanted = HashSet<Long>()
                for (t in tops) {
                    if (t == null) continue
                    val r = SpiderRules.rankOf(t)
                    val su = SpiderRules.suitOf(t, suits)
                    if (r > 1) wanted += su * 100L + (r - 1)
                    if (r < SPIDER_RUN_LENGTH) wanted += su * 100L + (r + 1)
                }
                val good = pool.filter { wanted.contains(SpiderRules.suitOf(it, suits) * 100L + SpiderRules.rankOf(it)) }
                if (good.isNotEmpty()) return good[rng.nextInt(good.size)].also { pool.remove(it) }
            }
            return pool.removeAt(rng.nextInt(pool.size))
        }

        fun realTops(state: SpiderState): List<Int?> = state.columns.mapIndexed { c, col ->
            val top = col.lastOrNull()
            if (top == null || top >= PLACEHOLDER || !state.isFaceUp(c, col.lastIndex)) null else top
        }

        // Reveal the ten cards that start face up.
        val firstUp = s.columns.map { it.last() }
        val assignedTops = ArrayList<Int>()
        for (c in 0 until SPIDER_COLUMNS) {
            val card = choose(assignedTops)
            deck[firstUp[c] - PLACEHOLDER] = card
            assignedTops += card
        }
        s = s.copy(columns = s.columns.mapIndexed { c, col -> col.dropLast(1) + assignedTops[c] })

        fun reveal(state: SpiderState): SpiderState {
            var changed = false
            val cols = state.columns.mapIndexed { c, col ->
                val top = col.lastOrNull()
                if (top != null && top >= PLACEHOLDER) {
                    changed = true
                    val others = state.columns.mapIndexedNotNull { j, other ->
                        val t = other.lastOrNull()
                        if (j == c || t == null || t >= PLACEHOLDER) null else t
                    }
                    val card = choose(others)
                    deck[top - PLACEHOLDER] = card
                    col.dropLast(1) + card
                } else col
            }
            return if (changed) state.copy(columns = cols) else state
        }

        // Fixes every still-hidden card at random (only called once the stock is gone).
        fun fixHidden(state: SpiderState): SpiderState = state.copy(
            columns = state.columns.map { col ->
                col.map { id ->
                    if (id >= PLACEHOLDER) {
                        val card = pool.removeAt(rng.nextInt(pool.size))
                        deck[id - PLACEHOLDER] = card
                        card
                    } else id
                }
            }
        )

        val line = ArrayList<SpiderMove?>()
        var plies = 0
        var finishedBySolver = false
        while (!s.won && plies < 800) {
            if (shouldStop()) return fail("stopped")
            plies++
            if (s.stock.isEmpty()) {
                s = fixHidden(s)
                val res = SpiderSolver(30_000, 4, rng.nextLong(), shouldStop).solve(s)
                if (!res.solved) return fail("endgame unsolved: runs=${s.completedRuns} cols=${s.columns.map { it.size }}")
                line += res.line
                finishedBySolver = true
                break
            }
            val candidates = SpiderMoveRanking.candidates(s, null, false, Int.MIN_VALUE)
            val best = candidates.firstOrNull { productive(s, it.move) }
            if (best != null) {
                val out = SpiderRules.applyMove(s, best.move) ?: return fail("illegal candidate move")
                line += best.move
                s = reveal(out.state)
            } else if (SpiderRules.canDeal(s)) {
                // Decide the whole row before it lands, so completion checks never see a placeholder.
                val rowStart = s.stock.size - SPIDER_COLUMNS
                val rowTops = realTops(s)
                val newRow = s.stock.subList(rowStart, s.stock.size).map { slot ->
                    val card = choose(rowTops)
                    deck[slot - PLACEHOLDER] = card
                    card
                }
                s = s.copy(stock = s.stock.subList(0, rowStart) + newRow)
                val out = SpiderRules.applyDeal(s) ?: return fail("deal refused")
                line += null
                s = reveal(out.state)
            } else {
                // An empty column blocks the deal and nothing productive is left: fill it with a
                // move that leaves its source non-empty.
                val fill = SpiderMoveRanking.candidates(s, rng, allowAttached = true).firstOrNull { s.columns[it.move.to].isEmpty() }
                    ?: return fail("stuck: nothing to fill the empty column with, columns=${s.columns.map { it.size }} stock=${s.stock.size}")
                val out = SpiderRules.applyMove(s, fill.move) ?: return fail("fallback move illegal")
                line += fill.move
                s = reveal(out.state)
            }
        }
        if (!(s.won || finishedBySolver) || deck.any { it < 0 }) {
            return fail("ended without a win: runs=${s.completedRuns} stock=${s.stock.size} cols=${s.columns.map { it.size }}")
        }

        // Proof: replay the recorded line on the finished deal with the real rules.
        val state = SpiderRules.openingState(deck.toList(), suits)
        return if (replayWins(state, line)) Constructed(state, line) else fail("replay diverged")
    }
}
