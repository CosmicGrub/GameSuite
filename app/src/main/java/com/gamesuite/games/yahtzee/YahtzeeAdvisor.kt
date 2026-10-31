package com.gamesuite.games.yahtzee

import kotlin.math.min

/** What to do with the current dice: [holds] (per die position) to keep and reroll the rest, or null to stop and score [category]. */
internal class YahtzeePlan(val holds: List<Boolean>?, val category: YahtzeeCategory)

/** Every way [n] dice can land as a multiset of faces, with its exact probability. */
internal class YahtzeeOutcome(val counts: IntArray, val probability: Double)

internal object YahtzeeOutcomes {
    /** table[n] = all multisets of n dice (counts indexed by face 1..6) with their probabilities. */
    val table: Array<List<YahtzeeOutcome>> = Array(YahtzeeScoring.DICE + 1) { build(it) }

    private fun factorial(n: Int): Double {
        var r = 1.0
        for (i in 2..n) r *= i
        return r
    }

    private fun build(n: Int): List<YahtzeeOutcome> {
        val out = ArrayList<YahtzeeOutcome>()
        val counts = IntArray(7)
        var total = 1.0
        repeat(n) { total *= 6.0 }
        fun rec(face: Int, remaining: Int) {
            if (face == 6) {
                counts[6] = remaining
                var ways = factorial(n)
                for (f in 1..6) ways /= factorial(counts[f])
                out.add(YahtzeeOutcome(counts.copyOf(), ways / total))
                return
            }
            for (c in 0..remaining) {
                counts[face] = c
                rec(face + 1, remaining - c)
            }
            counts[face] = 0
        }
        rec(1, n)
        return out
    }
}

/**
 * Expected-value planner for one decision on one scorecard. Values are "points above what the box
 * is worth on average": a box is valued at its score minus an opportunity cost (what that box is
 * expected to be worth at the end of a whole game, scaled by [costWeight]) plus an upper-section
 * par adjustment ([upperWeight] per point above or below three of a kind). [depth] is how many
 * rerolls ahead the search looks: 1 is greedy (one more roll), 2 is the full turn.
 */
internal class YahtzeeAdvisor(
    private val card: YahtzeeScorecard,
    private val costWeight: Double,
    private val upperWeight: Double,
    private val depth: Int
) {
    private val valueMemo = HashMap<Int, Double>()
    private val rollMemo = HashMap<Int, Double>()

    fun categoryValue(category: YahtzeeCategory, dice: List<Int>): Double {
        val joker = YahtzeeScoring.jokerApplies(dice, card)
        val points = YahtzeeScoring.score(category, dice, joker)
        var value = points.toDouble() + YahtzeeScoring.bonusFor(dice, card)
        value -= costWeight * EXPECTED[category.ordinal]
        if (category.isUpper) value += upperWeight * (points - 3 * category.face)
        return value
    }

    /** The best legal box for [dice] and its value; null only when the card is already full. */
    fun bestCategory(dice: List<Int>): Pair<YahtzeeCategory, Double>? {
        var best: Pair<YahtzeeCategory, Double>? = null
        for (category in YahtzeeScoring.legalCategories(dice, card)) {
            val v = categoryValue(category, dice)
            if (best == null || v > best.second) best = category to v
        }
        return best
    }

    fun plan(dice: List<Int>, rollsLeft: Int): YahtzeePlan {
        val stop = bestCategory(dice) ?: return YahtzeePlan(null, YahtzeeCategory.CHANCE)
        if (rollsLeft <= 0) return YahtzeePlan(null, stop.first)
        val r = min(rollsLeft, depth).coerceAtLeast(1)
        var bestKept: IntArray? = null
        var bestEv = Double.NEGATIVE_INFINITY
        for (kept in subMultisets(countsOf(dice))) {
            if (size(kept) >= YahtzeeScoring.DICE) continue
            val ev = rollValue(kept, r)
            if (ev > bestEv + EPS) {
                bestEv = ev
                bestKept = kept
            }
        }
        if (bestKept == null || bestEv <= stop.second + EPS) return YahtzeePlan(null, stop.first)
        return YahtzeePlan(positionsFor(dice, bestKept), stop.first)
    }

    /** Best achievable value standing on [counts] with [r] rerolls left (stop and score, or reroll some dice). */
    private fun value(counts: IntArray, r: Int): Double {
        val key = code(counts) * 4 + r
        valueMemo[key]?.let { return it }
        var best = bestCategory(diceOf(counts))?.second ?: 0.0
        if (r > 0) {
            for (kept in subMultisets(counts)) {
                if (size(kept) >= YahtzeeScoring.DICE) continue
                val ev = rollValue(kept, r)
                if (ev > best) best = ev
            }
        }
        valueMemo[key] = best
        return best
    }

    /** Expected value of keeping [kept] and rolling the other dice once, with [r] rerolls available now. */
    private fun rollValue(kept: IntArray, r: Int): Double {
        val key = code(kept) * 4 + r
        rollMemo[key]?.let { return it }
        var sum = 0.0
        val merged = IntArray(7)
        for (outcome in YahtzeeOutcomes.table[YahtzeeScoring.DICE - size(kept)]) {
            for (f in 1..6) merged[f] = kept[f] + outcome.counts[f]
            sum += outcome.probability * value(merged, r - 1)
        }
        rollMemo[key] = sum
        return sum
    }

    private fun size(counts: IntArray): Int {
        var n = 0
        for (f in 1..6) n += counts[f]
        return n
    }

    private fun code(counts: IntArray): Int {
        var acc = 0
        for (f in 1..6) repeat(counts[f]) { acc = acc * 7 + f }
        return acc
    }

    private fun countsOf(dice: List<Int>): IntArray {
        val c = IntArray(7)
        for (d in dice) c[d]++
        return c
    }

    private fun diceOf(counts: IntArray): List<Int> {
        val out = ArrayList<Int>(YahtzeeScoring.DICE)
        for (f in 1..6) repeat(counts[f]) { out.add(f) }
        return out
    }

    private fun subMultisets(counts: IntArray): List<IntArray> {
        var acc: List<IntArray> = listOf(IntArray(7))
        for (f in 1..6) {
            if (counts[f] == 0) continue
            val next = ArrayList<IntArray>()
            for (base in acc) {
                for (c in 0..counts[f]) {
                    val copy = base.copyOf()
                    copy[f] = c
                    next.add(copy)
                }
            }
            acc = next
        }
        return acc
    }

    /** Which die positions realise the kept multiset (first matching dice win). */
    private fun positionsFor(dice: List<Int>, kept: IntArray): List<Boolean> {
        val left = kept.copyOf()
        return dice.map { d ->
            if (left[d] > 0) {
                left[d]--
                true
            } else {
                false
            }
        }
    }

    companion object {
        private const val EPS = 1e-9

        /** What each box is worth at the end of a whole game under good play (ACES..CHANCE). */
        val EXPECTED = doubleArrayOf(
            2.11, 5.28, 8.57, 12.16, 15.69, 19.19, // upper
            21.66, 13.10, 22.59, 29.46, 32.71, 16.87, 22.01 // 3K, 4K, FH, SS, LS, YAHTZEE, CHANCE
        )
    }
}
