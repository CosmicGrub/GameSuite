package com.gamesuite.games.backgammon

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * A hand-weighted position evaluation, used by every CPU level above EASY and by Hint.
 *
 * Each side is scored on its own features and the answer is the difference ([perspective] minus the
 * other side), so a good position for one side is exactly as bad for the other:
 *
 *  - **Pip count**: one point per pip still to travel (the race).
 *  - **Made points**: two or more checkers on a point, worth more on the key home-board and bar
 *    points and on the opponent's 5 and 4 points (anchors), with a penalty for piling too many on one.
 *  - **Primes**: a run of three or more consecutive made points earns a growing bonus.
 *  - **Blots**: a lone checker costs the pips it would lose when hit, scaled by the share of the 36
 *    rolls that hit it (direct and combined shots, ignoring blocking), plus a flat tempo cost and
 *    more when the opponent's home board is strong. The side that is about to roll is exposed far
 *    less than the side that just moved, because it can still tidy up.
 *  - **Bar**: a checker on the bar is penalised on top of its pips, more against a strong home board.
 *  - **Bear-off progress**: a small bonus per checker already off.
 *
 * Once the two armies have passed each other (no contact) only the pip count and bear-off progress
 * are left: blots and points no longer matter. A finished game scores a huge win or loss scaled by
 * its result kind (gammon and backgammon count double and triple).
 */
object BackgammonEvaluator {
    private const val WIN_SCORE = 100_000.0

    /** Own point number (index 1..24) to the value of holding it with two or more checkers. */
    private val POINT_VALUE = doubleArrayOf(
        0.0,
        2.0, 2.0, 2.5, 5.0, 7.0, 6.0,
        6.0, 4.0, 2.5, 2.5, 2.5, 2.5,
        3.0, 1.5, 1.5, 1.5, 1.5, 1.5,
        2.0, 4.5, 5.0, 2.0, 2.0, 2.0
    )

    /**
     * For a hit distance d (1..24), the set of the 36 dice rolls (bit = (a-1)*6 + (b-1)) that can
     * move a checker exactly d pips: either die alone, both dice together, or a double's multiples.
     */
    private val HIT_MASK: LongArray = LongArray(25).also { masks ->
        for (r in 0 until 36) {
            val a = r / 6 + 1
            val b = r % 6 + 1
            val reach = HashSet<Int>()
            reach.add(a)
            reach.add(b)
            reach.add(a + b)
            if (a == b) {
                reach.add(3 * a)
                reach.add(4 * a)
            }
            for (d in reach) if (d in 1..24) masks[d] = masks[d] or (1L shl r)
        }
    }

    /**
     * The value of [board] for [perspective] (higher is better for it). [onRoll] is the side about
     * to roll: its blots are only lightly penalised.
     */
    fun evaluate(board: BackgammonBoard, perspective: Int, onRoll: Int): Double {
        val winner = BackgammonRules.winner(board)
        if (winner != null) {
            val value = WIN_SCORE * BackgammonRules.resultKind(board, winner).points
            return if (winner == perspective) value else -value
        }
        val contact = hasContact(board)
        return sideScore(board, perspective, onRoll, contact) - sideScore(board, 1 - perspective, onRoll, contact)
    }

    /** True while some checker of one side still has to get past a checker of the other. */
    fun hasContact(board: BackgammonBoard): Boolean {
        var rearmostBlack = Int.MAX_VALUE
        if (board.bar(1) > 0) {
            rearmostBlack = -1
        } else {
            for (i in 0 until BackgammonRules.POINTS) if (board.count(1, i) > 0) { rearmostBlack = i; break }
        }
        var rearmostWhite = -1
        if (board.bar(0) > 0) {
            rearmostWhite = BackgammonRules.POINTS
        } else {
            for (i in BackgammonRules.POINTS - 1 downTo 0) if (board.count(0, i) > 0) { rearmostWhite = i; break }
        }
        return rearmostBlack < rearmostWhite
    }

    private fun sideScore(board: BackgammonBoard, s: Int, onRoll: Int, contact: Boolean): Double {
        val opp = 1 - s
        var score = -board.pips(s).toDouble() + board.off(s) * 2.0
        if (!contact) return score

        var run = 0
        var bestRun = 0
        for (n in 1..24) {
            val c = board.count(s, BackgammonRules.indexOf(s, n))
            if (c >= 2) {
                score += POINT_VALUE[n]
                if (c > 4) score -= 1.5 * (c - 4)
                run++
                if (run > bestRun) bestRun = run
            } else {
                run = 0
            }
        }
        if (bestRun >= 3) score += bestRun * bestRun - 4.0

        var oppHomePoints = 0
        for (n in 1..6) if (board.count(opp, BackgammonRules.indexOf(opp, n)) >= 2) oppHomePoints++

        val exposure = if (onRoll == s) 0.35 else 1.0
        val oppDir = BackgammonRules.direction(opp)
        val oppBarIndex = if (opp == 0) BackgammonRules.POINTS else -1
        for (n in 1..24) {
            val i = BackgammonRules.indexOf(s, n)
            if (board.count(s, i) != 1) continue
            var mask = 0L
            for (j in 0 until BackgammonRules.POINTS) {
                if (board.count(opp, j) == 0) continue
                val dist = (i - j) * oppDir
                if (dist in 1..24) mask = mask or HIT_MASK[dist]
            }
            if (board.bar(opp) > 0) {
                val dist = (i - oppBarIndex) * oppDir
                if (dist in 1..24) mask = mask or HIT_MASK[dist]
            }
            val shots = mask.countOneBits()
            if (shots == 0) continue
            score -= exposure * (shots / 36.0) * ((25 - n) + 6.0 + 2.5 * oppHomePoints)
        }
        score -= board.bar(s) * (8.0 + 3.0 * oppHomePoints)
        return score
    }
}

/**
 * The CPU's choice of a whole turn, by difficulty:
 *
 *  - **EASY**: a uniformly random legal turn.
 *  - **MEDIUM**: the legal turn whose resulting position scores best under [BackgammonEvaluator]
 *    (ties broken at random).
 *  - **HARD**: the same evaluation with a 2-ply expectimax over the dice: the most promising
 *    candidates (ranked by the static evaluation) are each averaged over all 21 distinct opponent
 *    rolls, the opponent answering each with the reply that is worst for us. The search runs under a
 *    wall-clock budget (about a second): candidates are expanded best-first and the answer is the
 *    best fully-evaluated one when time runs out.
 *
 * Pure: it reads no engine state, so it is safe to run on a background thread.
 */
object BackgammonBot {
    /** The wall-clock budget of a HARD search and of a Hint. */
    const val DEFAULT_BUDGET_MS = 900L

    private const val TOP_CANDIDATES = 14

    /**
     * Picks the moves of a whole turn for [side] with the unused dice in [remaining]; empty when
     * nothing can be played. The moves are in a legal order. [nanoClock] and [budgetMs] bound a
     * HARD search (injectable for tests).
     */
    fun chooseTurn(
        board: BackgammonBoard,
        side: Int,
        remaining: List<Int>,
        difficulty: CpuDifficulty,
        random: Random,
        nanoClock: () -> Long = System::nanoTime,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): List<BackgammonMove> {
        val turns = BackgammonRules.generateTurns(board, side, remaining)
        if (turns.isEmpty()) return emptyList()
        if (turns.size == 1) return turns[0].moves
        return when (difficulty) {
            CpuDifficulty.EASY -> turns[random.nextInt(turns.size)].moves
            CpuDifficulty.MEDIUM -> bestStatic(turns, side, random).moves
            CpuDifficulty.HARD -> searchBest(turns, side, nanoClock, budgetMs).moves
        }
    }

    /** The first move of the turn the HARD search likes best, or null when nothing can be played. */
    fun hintMove(
        board: BackgammonBoard,
        side: Int,
        remaining: List<Int>,
        random: Random,
        nanoClock: () -> Long = System::nanoTime,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): BackgammonMove? =
        chooseTurn(board, side, remaining, CpuDifficulty.HARD, random, nanoClock, budgetMs).firstOrNull()

    private fun bestStatic(turns: List<BackgammonTurn>, side: Int, random: Random): BackgammonTurn {
        val scores = turns.map { BackgammonEvaluator.evaluate(it.result, side, 1 - side) }
        val top = scores.maxOrNull() ?: 0.0
        val best = turns.indices.filter { scores[it] >= top - 1e-9 }
        return turns[best[random.nextInt(best.size)]]
    }

    private fun searchBest(turns: List<BackgammonTurn>, side: Int, nanoClock: () -> Long, budgetMs: Long): BackgammonTurn {
        val ranked = turns
            .map { it to BackgammonEvaluator.evaluate(it.result, side, 1 - side) }
            .sortedByDescending { it.second }
        val deadline = nanoClock() + budgetMs * 1_000_000L
        var best = ranked[0].first
        var bestValue = Double.NEGATIVE_INFINITY
        val limit = minOf(ranked.size, TOP_CANDIDATES)
        for (k in 0 until limit) {
            if (k > 0 && nanoClock() > deadline) break
            val value = expectedValue(ranked[k].first.result, side, nanoClock, deadline, mustFinish = k == 0) ?: break
            if (value > bestValue) {
                bestValue = value
                best = ranked[k].first
            }
        }
        return best
    }

    /**
     * The average, over the 36 rolls, of the worst reply the opponent has for [side]. Null when the
     * deadline passes before the average is complete (never when [mustFinish]).
     */
    private fun expectedValue(
        board: BackgammonBoard,
        side: Int,
        nanoClock: () -> Long,
        deadline: Long,
        mustFinish: Boolean
    ): Double? {
        if (BackgammonRules.gameOver(board)) return BackgammonEvaluator.evaluate(board, side, side)
        val opp = 1 - side
        var total = 0.0
        for (a in 1..6) {
            for (b in a..6) {
                if (!mustFinish && nanoClock() > deadline) return null
                val dice = if (a == b) listOf(a, a, a, a) else listOf(a, b)
                val weight = if (a == b) 1.0 else 2.0
                val replies = BackgammonRules.generateTurns(board, opp, dice)
                val value = if (replies.isEmpty()) {
                    BackgammonEvaluator.evaluate(board, side, side)
                } else {
                    var worst = Double.POSITIVE_INFINITY
                    for (reply in replies) {
                        val v = BackgammonEvaluator.evaluate(reply.result, side, side)
                        if (v < worst) worst = v
                    }
                    worst
                }
                total += weight * value
            }
        }
        return total / 36.0
    }
}
