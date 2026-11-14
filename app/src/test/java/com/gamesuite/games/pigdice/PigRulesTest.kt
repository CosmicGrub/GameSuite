package com.gamesuite.games.pigdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PigRulesTest {
    private fun roll(v: PigVariant, vararg dice: Int, turn: Int = 0) = PigRules.evaluate(v, dice.toList(), turn)

    @Test fun classicAOneBustsAndEverythingElseAdds() {
        assertTrue(roll(PigVariant.CLASSIC, 1).bust)
        for (d in 2..6) {
            val r = roll(PigVariant.CLASSIC, d)
            assertFalse(r.bust); assertEquals(d, r.points); assertFalse(r.autoBank)
        }
    }

    @Test fun bigPigScoring() {
        assertEquals(PigRules.Note.SNAKE_EYES, roll(PigVariant.BIG_PIG, 1, 1).note)
        assertEquals(25, roll(PigVariant.BIG_PIG, 1, 1).points)
        assertFalse(roll(PigVariant.BIG_PIG, 1, 1).bust)
        assertTrue(roll(PigVariant.BIG_PIG, 1, 5).bust)
        assertTrue(roll(PigVariant.BIG_PIG, 6, 1).bust)
        assertEquals(2 * (4 + 4), roll(PigVariant.BIG_PIG, 4, 4).points)
        assertEquals(PigRules.Note.DOUBLES, roll(PigVariant.BIG_PIG, 2, 2).note)
        assertEquals(3 + 6, roll(PigVariant.BIG_PIG, 3, 6).points)
    }

    @Test fun hogRollsOnceAndAnyOneScoresNothing() {
        val safe = roll(PigVariant.HOG, 2, 3, 6)
        assertEquals(11, safe.points); assertTrue(safe.autoBank); assertFalse(safe.bust)
        val bust = roll(PigVariant.HOG, 2, 1, 6)
        assertTrue(bust.bust); assertTrue(bust.autoBank); assertEquals(0, bust.points)
    }

    @Test fun twentyOnePlusBanksAnExactTwentyOneWithABonus() {
        val hit = roll(PigVariant.TWENTY_ONE, 4, turn = 17)
        assertTrue(hit.autoBank); assertEquals(4 + PigRules.EXACT_BONUS, hit.points); assertEquals(PigRules.Note.EXACT, hit.note)
        val miss = roll(PigVariant.TWENTY_ONE, 4, turn = 16)
        assertFalse(miss.autoBank); assertEquals(4, miss.points)
        assertFalse(roll(PigVariant.TWENTY_ONE, 6, turn = 17).autoBank) // 23 is not 21
    }

    @Test fun outcomeProbabilitiesSumToOneAndMatchTheDice() {
        for (v in listOf(PigVariant.CLASSIC, PigVariant.BIG_PIG, PigVariant.TWENTY_ONE)) {
            for (turn in listOf(0, 10, 17, 40)) {
                val outs = PigRules.outcomes(v, turn)
                assertEquals("$v $turn", 1.0, outs.sumOf { it.probability }, 1e-12)
            }
        }
        val classic = PigRules.outcomes(PigVariant.CLASSIC, 0)
        assertEquals(1.0 / 6, classic.first { it.bust }.probability, 1e-12)
        val big = PigRules.outcomes(PigVariant.BIG_PIG, 0)
        assertEquals(10.0 / 36, big.first { it.bust }.probability, 1e-12)          // exactly one 1: 2 * 5
        assertEquals(1.0 / 36, big.first { it.points == 25 }.probability, 1e-12)   // snake eyes
        // classic expected points of one roll (bust counts 0): (2+3+4+5+6)/6
        assertEquals(20.0 / 6, classic.sumOf { it.probability * it.points }, 1e-12)
    }

    @Test fun diceCountsPerVariant() {
        assertTrue(PigRules.legalDiceCount(PigVariant.CLASSIC, 1)); assertFalse(PigRules.legalDiceCount(PigVariant.CLASSIC, 2))
        assertTrue(PigRules.legalDiceCount(PigVariant.BIG_PIG, 2)); assertFalse(PigRules.legalDiceCount(PigVariant.BIG_PIG, 1))
        assertTrue(PigRules.legalDiceCount(PigVariant.HOG, 1)); assertTrue(PigRules.legalDiceCount(PigVariant.HOG, PigRules.HOG_MAX_DICE))
        assertFalse(PigRules.legalDiceCount(PigVariant.HOG, 0)); assertFalse(PigRules.legalDiceCount(PigVariant.HOG, PigRules.HOG_MAX_DICE + 1))
    }

    @Test fun variantIdsRoundTripAndUnknownFallsBackToClassic() {
        for (v in PigVariant.values()) assertEquals(v, PigVariant.fromId(v.id))
        assertEquals(PigVariant.CLASSIC, PigVariant.fromId("nonsense"))
        assertEquals(PigVariant.CLASSIC, PigVariant.fromId(null))
    }
}
