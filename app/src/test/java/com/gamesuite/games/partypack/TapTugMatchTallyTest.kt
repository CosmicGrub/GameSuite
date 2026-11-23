package com.gamesuite.games.partypack

import com.gamesuite.games.partypack.TapTugMatchTally.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TapTugMatchTallyTest {
    @Test fun bestOfOneEndsOnTheFirstWin() {
        assertEquals(Outcome.Won(1), TapTugMatchTally(2, 1).record(1))
    }

    @Test fun bestOfThreeNeedsTwoWins() {
        val t = TapTugMatchTally(2, 3)
        assertEquals(Outcome.Continue, t.record(0))
        assertEquals(Outcome.Continue, t.record(1))
        assertEquals(Outcome.Won(0), t.record(0))
        assertEquals(2, t.needed)
    }

    @Test fun bestOfFiveNeedsThree() {
        val t = TapTugMatchTally(3, 5)
        assertEquals(3, t.needed)
        listOf(0, 1, 2, 0, 1).dropLast(1).forEach { assertEquals(Outcome.Continue, t.record(it)) }
        assertEquals(Outcome.Continue, t.record(1)) // 2-2-1 after five rounds: leaders tie
        assertEquals(5, t.played)
    }

    @Test fun aTieAfterAllRoundsGoesToTiebreaksThenADraw() {
        val t = TapTugMatchTally(2, 1)
        assertEquals(Outcome.Continue, t.record(-1))
        assertEquals(Outcome.Continue, t.record(-1))
        assertEquals(Outcome.Continue, t.record(-1))
        assertEquals(Outcome.Drawn, t.record(-1))
        assertEquals(3, t.tiebreaks)
    }

    @Test fun aTiebreakWinnerTakesIt() {
        val t = TapTugMatchTally(2, 3)
        t.record(0); t.record(1); t.record(-1) // 1-1 after three
        assertEquals(Outcome.Won(1), t.record(1))
    }

    @Test fun aDrawnRoundCountsAsPlayed() {
        val t = TapTugMatchTally(2, 3)
        t.record(-1); t.record(0)
        assertEquals(2, t.played)
        assertEquals(Outcome.Won(0), t.record(-1)) // three played, strict leader
    }

    @Test fun everyRandomMatchEndsWithinTheCap() {
        val random = Random(7)
        repeat(2000) {
            val sides = 2 + random.nextInt(3)
            val rounds = listOf(1, 3, 5)[random.nextInt(3)]
            val t = TapTugMatchTally(sides, rounds)
            var ended = false
            var n = 0
            while (!ended) {
                n++
                assertTrue("never ends", n <= rounds + TapTugMatchTally.MAX_TIEBREAKS)
                val w = if (random.nextInt(4) == 0) -1 else random.nextInt(sides)
                ended = t.record(w) != Outcome.Continue
                if (!ended) assertTrue(t.isValidUnfinished())
            }
        }
    }

    @Test fun validationRejectsImpossibleTallies() {
        assertFalse(TapTugMatchTally(2, 3, listOf(2, 0), 2).isValidUnfinished()) // already won
        assertFalse(TapTugMatchTally(2, 3, listOf(1, 3), 3).isValidUnfinished())
        assertFalse(TapTugMatchTally(2, 3, listOf(-1, 0), 1).isValidUnfinished())
        assertFalse(TapTugMatchTally(2, 3, listOf(1, 1), 1).isValidUnfinished()) // more wins than rounds played
        assertFalse(TapTugMatchTally(2, 3, listOf(1, 0), 3).isValidUnfinished()) // three played with a strict leader: decided
        assertTrue(TapTugMatchTally(2, 3, listOf(1, 1), 2).isValidUnfinished())
        assertTrue(TapTugMatchTally(2, 3).isValidUnfinished())
    }
}
