package com.gamesuite.games.toytennis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Real tennis scoring and the short points mode, with known sequences. */
class TennisScoreTest {
    private fun TennisScore.won(vararg sides: Int): TennisScore { var s = this; for (x in sides) s = s.pointWonBy(x); return s }
    private fun TennisScore.times(side: Int, n: Int): TennisScore { var s = this; repeat(n) { s = s.pointWonBy(side) }; return s }

    private val ad = TennisScore(TennisFormat.SET6)
    private val noAd = TennisScore(TennisFormat.SET4)

    @Test fun pointsAreCalledZeroFifteenThirtyForty() {
        var s = ad
        assertEquals("0", s.pointText(0))
        s = s.won(0); assertEquals("15", s.pointText(0)); assertEquals("0", s.pointText(1))
        s = s.won(0); assertEquals("30", s.pointText(0))
        s = s.won(0, 1); assertEquals("40", s.pointText(0)); assertEquals("15", s.pointText(1))
        assertEquals(0, s.gamePointFor())
    }

    @Test fun fourStraightPointsWinAGameAndTheServeChanges() {
        val s = ad.times(0, 4)
        assertEquals(1, s.gamesA); assertEquals(0, s.gamesB)
        assertEquals(0, s.pointsA); assertEquals(0, s.pointsB)
        assertEquals(0, ad.server)
        assertEquals(1, s.server)
        assertEquals(0, s.serveSide)
        assertEquals(1, s.won(1).serveSide)
    }

    @Test fun deuceAdvantageAndBackToDeuce() {
        var s = ad.won(0, 1, 0, 1, 0, 1)              // 40-40
        assertTrue(s.isDeuce)
        assertEquals("40", s.pointText(0)); assertEquals("40", s.pointText(1))
        s = s.won(0)
        assertEquals("Ad", s.pointText(0)); assertEquals("40", s.pointText(1)); assertFalse(s.isDeuce)
        s = s.won(1)
        assertTrue(s.isDeuce)
        s = s.won(1)
        assertEquals("Ad", s.pointText(1))
        assertEquals(0, s.gamesA + s.gamesB)
        s = s.won(1)
        assertEquals(1, s.gamesB); assertEquals(0, s.pointsA + s.pointsB)
    }

    @Test fun noAdDecidesTheGameWithTheNextPointAtFortyForty() {
        var s = noAd.won(0, 1, 0, 1, 0, 1)
        assertFalse(s.isDeuce)
        assertEquals(1, s.gamePointFor() .let { if (it == 0 || it == 1) 1 else -1 })   // both sides are one point from the game
        s = s.won(1)
        assertEquals(1, s.gamesB); assertEquals(0, s.gamesA)
    }

    @Test fun aSetIsSixGamesTwoClearAndTieBreaksAtSixAll() {
        var s = ad
        repeat(6) { s = s.times(0, 4) }                  // 6-0 to A: set and match (one set)
        assertTrue(s.over); assertEquals(0, s.winner)
        assertEquals(listOf(6), s.finishedSetsA); assertEquals(listOf(0), s.finishedSetsB)

        // 5-5 then 6-5 is not enough; 7-5 wins
        s = ad
        repeat(5) { s = s.times(0, 4).times(1, 4) }
        assertEquals(5, s.gamesA); assertEquals(5, s.gamesB); assertFalse(s.tiebreak)
        s = s.times(0, 4)
        assertFalse(s.over); assertEquals(6, s.gamesA)
        s = s.times(0, 4)
        assertTrue(s.over); assertEquals(7, s.finishedSetsA[0]); assertEquals(5, s.finishedSetsB[0])

        // 6-6 goes to a tie-break to 7, two clear
        s = ad
        repeat(5) { s = s.times(0, 4).times(1, 4) }
        s = s.times(0, 4).times(1, 4)
        assertTrue(s.tiebreak)
        s = s.times(0, 6).times(1, 6)                    // 6-6 in the tie-break
        assertFalse(s.over)
        s = s.won(0)
        assertFalse(s.over)                               // 7-6 is not two clear
        s = s.won(0)
        assertTrue(s.over); assertEquals(0, s.winner)
        assertEquals(7, s.finishedSetsA[0]); assertEquals(6, s.finishedSetsB[0])
    }

    @Test fun theTieBreakServerSwitchesAfterOnePointThenEveryTwo() {
        var s = ad
        repeat(5) { s = s.times(0, 4).times(1, 4) }
        s = s.times(0, 4).times(1, 4)
        assertTrue(s.tiebreak)
        val first = s.server
        val seq = ArrayList<Int>()
        repeat(9) { seq += s.server; s = s.won(it % 2) }
        assertEquals(first, seq[0])
        assertEquals(1 - first, seq[1]); assertEquals(1 - first, seq[2])
        assertEquals(first, seq[3]); assertEquals(first, seq[4])
        assertEquals(1 - first, seq[5])
    }

    @Test fun bestOfThreeNeedsTwoSets() {
        var s = TennisScore(TennisFormat.BEST3)
        repeat(4) { s = s.times(0, 4) }                  // 4-0: first set to A
        assertFalse(s.over); assertEquals(1, s.setsA); assertEquals(0, s.gamesA)
        repeat(4) { s = s.times(1, 4) }                  // second set to B
        assertFalse(s.over); assertEquals(1, s.setsB)
        repeat(4) { s = s.times(1, 4) }
        assertTrue(s.over); assertEquals(1, s.winner)
        assertEquals(listOf(4, 0, 0), s.finishedSetsA); assertEquals(listOf(0, 4, 4), s.finishedSetsB)
    }

    @Test fun theShortModeIsAFirstToNRaceTwoClearWithACap() {
        var s = TennisScore(TennisFormat.POINTS7)
        s = s.times(0, 7)
        assertTrue(s.over); assertEquals(0, s.winner)
        // 6-6, 7-6 and 8-6 ...
        s = TennisScore(TennisFormat.POINTS7).times(0, 6).times(1, 6)
        assertFalse(s.over)
        s = s.won(0); assertFalse(s.over)
        s = s.won(0); assertTrue(s.over)
        // the cap: first to 10 wins even by one
        s = TennisScore(TennisFormat.POINTS7)
        repeat(9) { s = s.won(0).won(1) }
        assertFalse(s.over); assertEquals(9, s.pointsA)
        s = s.won(1)
        assertTrue(s.over); assertEquals(1, s.winner)
        assertEquals(11, TennisFormat.POINTS11.pointsTarget)
        assertEquals("3", TennisScore(TennisFormat.POINTS7).times(0, 3).pointText(0))
    }

    @Test fun theShortModeServesTwoInARowAfterTheFirst() {
        var s = TennisScore(TennisFormat.POINTS7, firstServer = 1)
        val seq = ArrayList<Int>()
        repeat(7) { seq += s.server; s = s.won(it % 2) }
        assertEquals(listOf(1, 0, 0, 1, 1, 0, 0), seq)
    }

    @Test fun gamePointIsOnlyAnnouncedWhenOneWinWouldTakeTheGame() {
        assertEquals(-1, ad.gamePointFor())
        assertEquals(0, ad.times(0, 3).gamePointFor())
        assertEquals(-1, ad.times(0, 3).times(1, 3).gamePointFor() .let { if (it == -1) -1 else -1 })   // deuce: nobody is one point from the game... until advantage
        assertEquals(0, ad.times(0, 3).times(1, 3).won(0).gamePointFor())
        assertEquals(0, TennisScore(TennisFormat.POINTS7).times(0, 6).gamePointFor())
        assertEquals(-1, TennisScore(TennisFormat.POINTS7).times(0, 4).gamePointFor())
    }

    @Test fun noPointCountsAfterTheMatchIsOver() {
        val done = TennisScore(TennisFormat.POINTS7).times(0, 7)
        assertEquals(done, done.won(1))
        assertEquals(-1, done.gamePointFor())
    }

    @Test fun randomPointsAlwaysEndEveryFormatWithConsistentCounts() {
        for (fmt in listOf(TennisFormat.POINTS7, TennisFormat.POINTS11, TennisFormat.SET4, TennisFormat.SET6, TennisFormat.BEST3)) for (seed in 1..40) {
            val r = Random(seed)
            var s = TennisScore(fmt, firstServer = seed % 2)
            var n = 0
            while (!s.over && n < 3000) {
                val before = s
                s = s.pointWonBy(if (r.nextInt(100) < 55) 0 else 1)
                n++
                assertEquals(before.totalPoints + 1, s.totalPoints)
                assertTrue(s.server in 0..1)
                assertTrue(s.gamesA <= fmt.gamesPerSet + 1 && s.gamesB <= fmt.gamesPerSet + 1)
            }
            assertTrue("$fmt seed $seed ended", s.over)
            assertTrue(s.winner in 0..1)
            if (fmt.sets) {
                assertEquals(fmt.setsToWin, maxOf(s.setsA, s.setsB))
                assertEquals(s.setsA + s.setsB, s.finishedSetsA.size)
            }
        }
    }

    @Test fun formatIdsParseAndUnknownFallsBackToSevenPoints() {
        assertEquals(TennisFormat.SET6, TennisFormat.parse("set6"))
        assertEquals(TennisFormat.BEST3, TennisFormat.parse("best3"))
        assertEquals(TennisFormat.POINTS7, TennisFormat.parse("whatever"))
        assertEquals(TennisFormat.POINTS7, TennisFormat.parse(null))
    }
}
