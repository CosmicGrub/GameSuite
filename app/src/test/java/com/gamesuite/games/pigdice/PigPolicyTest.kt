package com.gamesuite.games.pigdice

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The optimal policy is checked against a published value, against its own Bellman equation, and against the
 * other computer levels by EXACT policy evaluation (no sampling): the chance that one fixed way of playing
 * beats another is solved the same way the policy itself is, just without the max.
 */
class PigPolicyTest {
    private val classic: PigPolicy by lazy { PigPolicy.get(PigVariant.CLASSIC) }

    @Test fun theFirstPlayerOfClassicPigToHundredWinsAbout53Percent() {
        // Neller and Presser, "Optimal Play of the Dice Game Pig": 0.5306 for the player who rolls first.
        assertEquals(0.5306, classic.winProbability(0, 0), 0.002)
    }

    @Test fun optimalPlayHoldsAtTwentyOneWhenBothScoresAreZero() {
        // value of rolling vs holding at (0,0): 20 -> 0.6199 vs 0.6193 (roll), 21 -> 0.6262 vs 0.6269 (hold)
        assertTrue(classic.shouldRoll(0, 0, 20))
        for (turn in 21..40) assertFalse("turn $turn", classic.shouldRoll(0, 0, turn))
        assertTrue("a turn must start with a roll", classic.shouldRoll(0, 0, 0))
    }

    @Test fun whenTheRivalIsOneRollFromWinningTheOptimalPlayerNeverHolds() {
        for (turn in 1 until 99) assertTrue("turn $turn", classic.shouldRoll(0, 99, turn))
    }

    @Test fun theOptimalPlayerBanksAWinningTotal() {
        assertFalse(classic.shouldRoll(80, 10, 20)) // 100 reached
        assertFalse(classic.shouldRoll(60, 10, 40))
    }

    @Test fun winProbabilityObeysTheBellmanEquationAndIsMonotone() {
        val r = Random(5)
        repeat(300) {
            val i = r.nextInt(0, 100); val j = r.nextInt(0, 100)
            val v = classic.values(i, j, 0)
            assertEquals(classic.winProbability(i, j), v.roll, 1e-6)
            if (i < 99) assertTrue(classic.winProbability(i + 1, j) >= classic.winProbability(i, j) - 1e-7)
            if (j < 99) assertTrue(classic.winProbability(i, j + 1) <= classic.winProbability(i, j) + 1e-7)
        }
        // holding is worth 1 - P(opp, me + turn): the table's own definition
        val v = classic.values(30, 40, 12)
        assertEquals(1.0 - classic.winProbability(40, 42), v.hold, 1e-9)
    }

    // ---- exact evaluation of two fixed ways of playing --------------------------------------------

    private class Style(val rollProb: (me: Int, opp: Int, turn: Int) -> Double)

    /** win[x][i][j]: chance that the player to move wins, when x = 0 plays [a] and x = 1 plays [b]. */
    private fun exact(variant: PigVariant, target: Int, a: Style, b: Style): Array<Array<DoubleArray>> {
        val t = target
        val styles = arrayOf(a, b)
        val win = Array(2) { Array(t) { DoubleArray(t) { 0.5 } } }
        val q = Array(2) { x -> Array(t) { i -> Array(t) { j -> DoubleArray(t - i) { k -> if (k == 0) 1.0 else styles[x].rollProb(i, j, k) } } } }
        val outs = Array(t + 1) { PigRules.outcomes(variant, it) }
        val phat = DoubleArray(t + 1)
        for (sweep in 0 until 800) {
            var diff = 0.0
            for (x in 0..1) {
                val me = win[x]; val rival = win[1 - x]
                for (i in t - 1 downTo 0) for (j in t - 1 downTo 0) {
                    val bustVal = 1.0 - rival[j][i]
                    for (k in t - i - 1 downTo 0) {
                        var roll = 0.0
                        for (o in outs[k]) roll += o.probability * if (o.bust) bustVal else {
                            val total = i + k + o.points
                            when {
                                total >= t -> 1.0
                                o.autoBank -> 1.0 - rival[j][total]
                                else -> phat[k + o.points]
                            }
                        }
                        val hold = if (k > 0) 1.0 - rival[j][i + k] else 0.0
                        val p = q[x][i][j][k]
                        phat[k] = p * roll + (1 - p) * hold
                    }
                    diff = maxOf(diff, Math.abs(phat[0] - me[i][j]))
                    me[i][j] = phat[0]
                }
            }
            if (diff < 1e-10) break
        }
        return win
    }

    /** Chance that [a] beats [b] when who starts is a fair coin. */
    private fun beats(variant: PigVariant, a: Style, b: Style): Double {
        val w = exact(variant, 100, a, b)
        return 0.5 * (w[0][0][0] + 1.0 - w[1][0][0])
    }

    private val hardClassicTable: Array<Array<BooleanArray>> by lazy {
        Array(100) { i -> Array(100) { j -> BooleanArray(100 - i) { k -> classic.shouldRoll(i, j, k) } } }
    }
    private fun hard(variant: PigVariant): Style {
        check(variant == PigVariant.CLASSIC)
        return Style { me, opp, turn -> if (hardClassicTable[me][opp][turn]) 1.0 else 0.0 }
    }
    private fun fixed(limit: Int) = Style { me, _, turn -> if (me + turn >= 100) 0.0 else if (turn < limit) 1.0 else 0.0 }
    private fun medium(variant: PigVariant) = Style { me, opp, turn ->
        if (PigBot.choose(CpuDifficulty.MEDIUM, variant, 100, listOf(me, opp), 0, turn, Random(0)) is PigMove.Roll) 1.0 else 0.0
    }

    /** Easy ignores the score, so its chance of rolling at each turn total is measured once by sampling. */
    private fun easy(variant: PigVariant): Style {
        val r = Random(99)
        val prob = DoubleArray(100) { turn ->
            if (turn == 0) 1.0 else {
                val n = 6000
                (0 until n).count { PigBot.choose(CpuDifficulty.EASY, variant, 100, listOf(0, 0), 0, turn, r) is PigMove.Roll }.toDouble() / n
            }
        }
        return Style { me, _, turn -> if (me + turn >= 100) 0.0 else prob[turn] }
    }

    @Test fun theOptimalPlayerBeatsTheFixedHoldAtTwentyBot() {
        val p = beats(PigVariant.CLASSIC, hard(PigVariant.CLASSIC), fixed(20))
        assertTrue("optimal vs hold-at-20: $p", p > 0.5)
        assertTrue("hold-at-20 is a strong rule, it should not be crushed: $p", p < 0.6)
    }

    @Test fun theComputerLevelsAreOrderedButNoneIsInvincible() {
        val v = PigVariant.CLASSIC
        val hm = beats(v, hard(v), medium(v))
        val me = beats(v, medium(v), easy(v))
        val he = beats(v, hard(v), easy(v))
        assertTrue("hard vs medium $hm", hm > 0.5)
        assertTrue("medium vs easy $me", me > 0.6 && me < 0.97)
        assertTrue("hard vs easy $he", he > 0.7)
        assertTrue("hard vs medium $hm, medium vs easy $me, hard vs easy $he", hm < he)
        assertTrue("hard vs easy must not be a certainty: $he", he < 0.97)
        assertTrue("easy can win: ${1 - he}", 1 - he > 0.03)
    }

    @Test fun otherVariantsHaveSaneTablesWithAFirstPlayerEdge() {
        for (v in listOf(PigVariant.BIG_PIG, PigVariant.TWENTY_ONE)) {
            val p = PigPolicy.get(v).winProbability(0, 0)
            assertTrue("$v first-player edge $p", p > 0.48 && p < 0.7)
        }
    }

    @Test fun hogPolicyPicksAHandfulOfDiceAndAFairStart() {
        val hog = PigPolicy.get(PigVariant.HOG)
        assertTrue("dice ${hog.hogDice(0, 0)}", hog.hogDice(0, 0) in 3..7)
        val p = hog.winProbability(0, 0)
        assertTrue("hog first-player chance $p", p > 0.45 && p < 0.65)
        for (n in 1..PigRules.HOG_MAX_DICE) assertTrue(hog.hogValue(0, 0, n) <= hog.winProbability(0, 0) + 1e-9)
    }

    @Test fun computerChoicesAreLegalAndDeterministicForASeed() {
        val r1 = Random(7); val r2 = Random(7)
        for (level in CpuDifficulty.values()) for (v in PigVariant.values()) {
            val a = PigBot.choose(level, v, 100, listOf(20, 35), 0, if (v.singleRoll) 0 else 8, r1)
            val b = PigBot.choose(level, v, 100, listOf(20, 35), 0, if (v.singleRoll) 0 else 8, r2)
            assertEquals(a, b)
            if (a is PigMove.Roll) assertTrue(PigRules.legalDiceCount(v, a.count))
        }
    }

    @Test fun theHintIsTheOptimalMove() {
        val (move, chance) = PigBot.hint(PigVariant.CLASSIC, 100, listOf(0, 0), 0, 0)
        assertEquals(PigMove.Roll(1), move)
        assertEquals(classic.winProbability(0, 0), chance, 1e-9)
        assertEquals(PigMove.Hold, PigBot.hint(PigVariant.CLASSIC, 100, listOf(0, 0), 0, 30).first)
    }
}
