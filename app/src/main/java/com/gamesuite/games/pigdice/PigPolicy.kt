package com.gamesuite.games.pigdice

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The optimal Pig player, found by value iteration (the method of Neller and Presser, "Optimal Play
 * of the Dice Game Pig"). For two players, P(me, opp) is the chance that the player ABOUT TO ROLL wins
 * when banked scores are me and opp. Inside a turn, with turn total k, the player picks the better of
 *
 *     hold:  1 - P(opp, me + k)
 *     roll:  sum over outcomes of  p * (bust ? 1 - P(opp, me) : next state's value)
 *
 * and P(me, opp) is the value at k = 0 (a turn must contain a roll). The loop is swept until no value
 * moves by more than 1e-9; it converges because every roll either moves a score up or hands the turn
 * over. The same table drives the Hard computer, the hint, and the tests ("the first player of
 * classic Pig to 100 wins 53.06% of the time" is a published value this reproduces).
 *
 * With more than two players the table is used against the best-placed opponent, which is a strong
 * and standard approximation. Hog (one roll of a chosen number of dice per turn) gets its own table
 * of win probability and best dice count.
 */
class PigPolicy private constructor(
    val variant: PigVariant,
    val target: Int,
    private val win: DoubleArray,
    private val outs: Array<List<PigRules.Outcome>>?,
    private val bestDice: IntArray?
) {
    /** Chance that the player about to roll wins from banked scores [me] and [opp]. */
    fun winProbability(me: Int, opp: Int): Double = win[me.coerceIn(0, target - 1) * target + opp.coerceIn(0, target - 1)]

    /** The values of rolling and of holding with [turn] already in the pot (hold is -1 when it is not allowed). */
    class Values(val roll: Double, val hold: Double)

    fun values(me: Int, opp: Int, turn: Int): Values {
        require(!variant.singleRoll) { "Hog has no turn total" }
        val i = me.coerceIn(0, target - 1)
        val j = opp.coerceIn(0, target - 1)
        val k = turn.coerceIn(0, target - i - 1)
        val roll = DoubleArray(target - i)
        val hold = DoubleArray(target - i)
        fillRow(variant, target, win, outs!!, i, j, DoubleArray(target - i), roll, hold)
        return Values(roll[k], hold[k])
    }

    /** True when rolling is at least marginally better than holding. A turn with nothing in the pot always rolls. */
    fun shouldRoll(me: Int, opp: Int, turn: Int): Boolean {
        if (turn <= 0) return true
        if (me + turn >= target) return false
        val v = values(me, opp, turn)
        return v.roll > v.hold + 1e-12
    }

    /** Hog: the number of dice to roll that gives the best chance. */
    fun hogDice(me: Int, opp: Int): Int {
        val table = bestDice ?: return 1
        return table[me.coerceIn(0, target - 1) * target + opp.coerceIn(0, target - 1)]
    }

    /** Hog: the win chance of rolling [count] dice. */
    fun hogValue(me: Int, opp: Int, count: Int): Double = hogRollValue(me.coerceIn(0, target - 1), opp.coerceIn(0, target - 1), count, win, target)

    companion object {
        private val cache = HashMap<Pair<PigVariant, Int>, PigPolicy>()

        /** The (memoised) policy; the first call for a variant does the iteration (well under a second to a few seconds). */
        @Synchronized
        fun get(variant: PigVariant, target: Int = PigRules.DEFAULT_TARGET): PigPolicy =
            cache.getOrPut(variant to target) { compute(variant, target) }

        fun compute(variant: PigVariant, target: Int): PigPolicy {
            require(target in 10..200) { "target out of range" }
            return if (variant.singleRoll) computeHog(variant, target) else computeRepeated(variant, target)
        }

        private fun computeRepeated(variant: PigVariant, target: Int): PigPolicy {
            val t = target
            val win = DoubleArray(t * t) { 0.5 }
            val outs = Array(t + 1) { PigRules.outcomes(variant, it) }
            val phat = DoubleArray(t + 1)
            val roll = DoubleArray(t + 1)
            val hold = DoubleArray(t + 1)
            for (sweep in 0 until 600) {
                var maxDiff = 0.0
                for (i in t - 1 downTo 0) {
                    for (j in t - 1 downTo 0) {
                        fillRow(variant, t, win, outs, i, j, phat, roll, hold)
                        val v = phat[0]
                        val d = Math.abs(v - win[i * t + j])
                        if (d > maxDiff) maxDiff = d
                        win[i * t + j] = v
                    }
                }
                if (maxDiff < 1e-9) break
            }
            return PigPolicy(variant, target, win, outs, null)
        }

        private fun computeHog(variant: PigVariant, target: Int): PigPolicy {
            val t = target
            val win = DoubleArray(t * t) { 0.5 }
            val best = IntArray(t * t) { 1 }
            for (sweep in 0 until 800) {
                var maxDiff = 0.0
                for (i in t - 1 downTo 0) {
                    for (j in t - 1 downTo 0) {
                        var bv = -1.0
                        var bn = 1
                        for (n in 1..PigRules.HOG_MAX_DICE) {
                            val v = hogRollValue(i, j, n, win, t)
                            if (v > bv + 1e-12) { bv = v; bn = n }
                        }
                        val d = Math.abs(bv - win[i * t + j])
                        if (d > maxDiff) maxDiff = d
                        win[i * t + j] = bv
                        best[i * t + j] = bn
                    }
                }
                if (maxDiff < 1e-9) break
            }
            return PigPolicy(variant, target, win, null, best)
        }

        private val hogSums: Array<DoubleArray> by lazy {
            // hogSums[n][s]: probability that n dice all show 2..6 and total s
            val maxSum = PigRules.HOG_MAX_DICE * 6
            val out = Array(PigRules.HOG_MAX_DICE + 1) { DoubleArray(maxSum + 1) }
            out[0][0] = 1.0
            for (n in 1..PigRules.HOG_MAX_DICE) {
                for (s in 0..maxSum) {
                    val prev = out[n - 1][s]
                    if (prev == 0.0) continue
                    for (face in 2..6) out[n][s + face] += prev / 6.0
                }
            }
            out
        }

        private fun hogRollValue(i: Int, j: Int, n: Int, win: DoubleArray, t: Int): Double {
            val sums = hogSums[n]
            var safeMass = 0.0
            var v = 0.0
            for (s in 2..n * 6) {
                val p = sums[s]
                if (p == 0.0) continue
                safeMass += p
                v += p * (if (i + s >= t) 1.0 else 1.0 - win[j * t + i + s])
            }
            return v + (1.0 - safeMass) * (1.0 - win[j * t + i])
        }

        /**
         * Fills, for banked scores (i, j), the value of rolling ([roll]), holding ([hold]) and the best of
         * the two ([phat]) for every turn total k = 0..t-i-1, from the top down.
         */
        private fun fillRow(
            variant: PigVariant, t: Int, win: DoubleArray, outs: Array<List<PigRules.Outcome>>,
            i: Int, j: Int, phat: DoubleArray, roll: DoubleArray, hold: DoubleArray
        ) {
            val bustVal = 1.0 - win[j * t + i]
            for (k in t - i - 1 downTo 0) {
                var r = 0.0
                for (o in outs[k]) {
                    r += o.probability * if (o.bust) bustVal else {
                        val total = i + k + o.points
                        when {
                            total >= t -> 1.0
                            o.autoBank -> 1.0 - win[j * t + total]
                            else -> phat[k + o.points]
                        }
                    }
                }
                val h = if (k > 0) 1.0 - win[j * t + i + k] else -1.0
                roll[k] = r
                hold[k] = h
                phat[k] = if (r > h) r else h
            }
        }
    }
}

/** What a computer player does: roll [count] dice (always the variant's own count except in Hog) or hold. */
sealed class PigMove {
    data class Roll(val count: Int) : PigMove()
    data object Hold : PigMove()
}

/**
 * The computer players. HARD plays the optimal table. MEDIUM plays the textbook "hold at 20" rule (25 or
 * 30 in Big Pig) and goes for the win near the end. EASY plays badly on purpose, the way a casual human
 * does: it banks early at a random small total, now and then presses on with no reason, and in Hog
 * rolls a random handful of dice.
 */
object PigBot {
    fun choose(
        level: CpuDifficulty, variant: PigVariant, target: Int, scores: List<Int>, me: Int, turn: Int, random: Random
    ): PigMove {
        val mine = scores[me]
        val opp = scores.filterIndexed { i, _ -> i != me }.maxOrNull() ?: 0
        if (variant.singleRoll) return PigMove.Roll(hogCount(level, variant, target, mine, opp, random))
        val count = variant.diceCount
        if (turn == 0) return PigMove.Roll(count)
        if (mine + turn >= target) return PigMove.Hold
        val roll = when (level) {
            CpuDifficulty.HARD -> PigPolicy.get(variant, target).shouldRoll(mine, opp, turn)
            CpuDifficulty.MEDIUM -> {
                val base = when (variant) { PigVariant.BIG_PIG -> 30; else -> 20 }
                val needed = target - mine
                val desperate = opp >= target - 20 || mine >= target - 20
                val limit = if (desperate) minOf(needed, 45) else base
                turn < limit
            }
            CpuDifficulty.EASY -> {
                // a coin-flip mood decides how greedy this turn is, and sometimes it just keeps going
                val mood = random.nextInt(5, 13)
                if (random.nextInt(100) < 18) turn < 40 else turn < mood
            }
        }
        return if (roll) PigMove.Roll(count) else PigMove.Hold
    }

    private fun hogCount(level: CpuDifficulty, variant: PigVariant, target: Int, mine: Int, opp: Int, random: Random): Int = when (level) {
        CpuDifficulty.HARD -> PigPolicy.get(variant, target).hogDice(mine, opp)
        CpuDifficulty.MEDIUM -> if (target - mine <= 12) 4 else 5
        CpuDifficulty.EASY -> if (random.nextBoolean()) random.nextInt(1, 3) else random.nextInt(7, PigRules.HOG_MAX_DICE + 1)
    }

    /** The move a hint should suggest: the optimal one, with its win chance. */
    fun hint(variant: PigVariant, target: Int, scores: List<Int>, me: Int, turn: Int): Pair<PigMove, Double> {
        val policy = PigPolicy.get(variant, target)
        val mine = scores[me]
        val opp = scores.filterIndexed { i, _ -> i != me }.maxOrNull() ?: 0
        if (variant.singleRoll) {
            val n = policy.hogDice(mine, opp)
            return PigMove.Roll(n) to policy.hogValue(mine, opp, n)
        }
        if (turn == 0) return PigMove.Roll(variant.diceCount) to policy.winProbability(mine, opp)
        val v = policy.values(mine, opp, turn)
        return if (mine + turn < target && v.roll > v.hold + 1e-12) PigMove.Roll(variant.diceCount) to v.roll else PigMove.Hold to v.hold
    }
}
