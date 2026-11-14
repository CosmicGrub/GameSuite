package com.gamesuite.games.holdem

import kotlin.math.ceil
import kotlin.random.Random

/** Monte-Carlo hand equity and a few cheap hand-strength measures the computer players and the hint use. */
object HoldemEquity {

    /**
     * The share of the pot [hole] wins on average (a tie counts as its fraction) against [opponents] random
     * hands, over the cards still to come after [board]. [dead] are cards known to be out of play. Pure CPU
     * work: callers on the UI thread should run it on a background dispatcher.
     */
    fun estimate(
        hole: List<Int>,
        board: List<Int>,
        opponents: Int,
        iterations: Int,
        random: Random,
        dead: List<Int> = emptyList()
    ): Double {
        require(hole.size == 2)
        val known = BooleanArray(HoldemCards.DECK_SIZE)
        hole.forEach { known[it] = true }
        board.forEach { known[it] = true }
        dead.forEach { if (HoldemCards.isValid(it)) known[it] = true }
        val remaining = (0 until HoldemCards.DECK_SIZE).filter { !known[it] }.toIntArray()
        val boardNeed = 5 - board.size
        var opps = opponents.coerceAtLeast(1)
        while (opps > 1 && boardNeed + 2 * opps > remaining.size) opps--
        val need = boardNeed + 2 * opps
        if (need > remaining.size || iterations <= 0) return 0.5
        val deck = remaining.copyOf()
        val heroCards = IntArray(7)
        val oppCards = IntArray(7)
        heroCards[0] = hole[0]; heroCards[1] = hole[1]
        oppCards[0] = 0
        for (i in board.indices) { heroCards[2 + i] = board[i]; oppCards[2 + i] = board[i] }
        var total = 0.0
        repeat(iterations) {
            for (i in 0 until need) {
                val j = i + random.nextInt(deck.size - i)
                val t = deck[i]; deck[i] = deck[j]; deck[j] = t
            }
            for (i in 0 until boardNeed) {
                val c = deck[i]
                heroCards[2 + board.size + i] = c
                oppCards[2 + board.size + i] = c
            }
            val hero = HandEvaluator.evaluate(heroCards, 7)
            var better = false
            var ties = 0
            for (o in 0 until opps) {
                oppCards[0] = deck[boardNeed + 2 * o]
                oppCards[1] = deck[boardNeed + 2 * o + 1]
                val v = HandEvaluator.evaluate(oppCards, 7)
                if (v > hero) { better = true; break }
                if (v == hero) ties++
            }
            if (!better) total += 1.0 / (ties + 1)
        }
        return total / iterations
    }

    /** Bill Chen's starting-hand score, rounded up: 20 for aces, about 5 for the weakest hands that still play, negative below. */
    fun chen(hole: List<Int>): Double {
        val r1 = HoldemCards.rank(hole[0])
        val r2 = HoldemCards.rank(hole[1])
        val hi = maxOf(r1, r2)
        val lo = minOf(r1, r2)
        fun points(r: Int): Double = when (r) {
            14 -> 10.0; 13 -> 8.0; 12 -> 7.0; 11 -> 6.0; else -> r / 2.0
        }
        var score = points(hi)
        if (hi == lo) return ceil(maxOf(5.0, score * 2))
        if (HoldemCards.suit(hole[0]) == HoldemCards.suit(hole[1])) score += 2
        val gap = hi - lo - 1
        score -= when (gap) { 0 -> 0.0; 1 -> 1.0; 2 -> 2.0; 3 -> 4.0; else -> 5.0 }
        if (gap <= 1 && hi < 12) score += 1
        return ceil(score)
    }

    /** A quick 0..1 read of how good the made hand (plus obvious draws) is on [board]; no simulation. */
    fun madeStrength(hole: List<Int>, board: List<Int>): Double {
        if (board.size < 3) return (chen(hole) / 20.0).coerceIn(0.0, 1.0)
        val all = hole + board
        val score = HandEvaluator.evaluate(all.toIntArray(), all.size)
        val cat = HandEvaluator.category(score)
        val boardRanks = board.map { HoldemCards.rank(it) }
        val base = when (cat) {
            HandCategory.HIGH_CARD -> 0.12 + HoldemCards.rank(hole.maxByOrNull { HoldemCards.rank(it) }!!) / 140.0
            HandCategory.PAIR -> {
                val pr = HandEvaluator.rankAt(score, 0)
                val topBoard = boardRanks.max()
                when {
                    hole.none { HoldemCards.rank(it) == pr } -> 0.2
                    pr > topBoard -> 0.6
                    pr == topBoard -> 0.5
                    pr > boardRanks.sorted()[boardRanks.size / 2] -> 0.42
                    else -> 0.34
                }
            }
            HandCategory.TWO_PAIR -> 0.66
            HandCategory.TRIPS -> 0.76
            HandCategory.STRAIGHT -> 0.83
            HandCategory.FLUSH -> 0.87
            HandCategory.FULL_HOUSE -> 0.93
            HandCategory.QUADS -> 0.98
            HandCategory.STRAIGHT_FLUSH -> 1.0
        }
        return (base + if (board.size < 5) drawBonus(all) else 0.0).coerceIn(0.0, 1.0)
    }

    /** Extra value for four to a flush or an open-ended straight draw while a card is still to come. */
    private fun drawBonus(all: List<Int>): Double {
        val suitCounts = IntArray(4)
        all.forEach { suitCounts[HoldemCards.suit(it)]++ }
        var bonus = 0.0
        if (suitCounts.any { it == 4 }) bonus += 0.16
        var mask = 0
        all.forEach { mask = mask or (1 shl HoldemCards.rank(it)) }
        if (mask and (1 shl 14) != 0) mask = mask or 2
        for (low in 1..10) {
            if (((mask shr low) and 15) == 15 && ((mask shr low) and 31) != 31 && ((mask shr (low - 1)) and 31) != 31) { bonus += 0.1; break }
        }
        return bonus
    }
}
