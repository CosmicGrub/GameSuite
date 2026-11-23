package com.gamesuite.games.minigolf

import org.junit.Assert.assertEquals
import org.junit.Test

class GolfRulesTest {
    @Test fun scoreNamesAreRelativeToPar() {
        assertEquals(GolfScoreName.HOLE_IN_ONE, GolfRules.scoreName(1, 3))
        assertEquals(GolfScoreName.HOLE_IN_ONE, GolfRules.scoreName(1, 2))
        assertEquals(GolfScoreName.EAGLE, GolfRules.scoreName(2, 4))
        assertEquals(GolfScoreName.BIRDIE, GolfRules.scoreName(2, 3))
        assertEquals(GolfScoreName.PAR, GolfRules.scoreName(3, 3))
        assertEquals(GolfScoreName.BOGEY, GolfRules.scoreName(4, 3))
        assertEquals(GolfScoreName.DOUBLE_BOGEY, GolfRules.scoreName(5, 3))
        assertEquals(GolfScoreName.TRIPLE_BOGEY, GolfRules.scoreName(6, 3))
        assertEquals(GolfScoreName.OVER, GolfRules.scoreName(8, 3))
    }

    @Test fun relativeTextUsesEvenPlusAndMinus() {
        assertEquals("E", GolfRules.relativeText(27, 27))
        assertEquals("+3", GolfRules.relativeText(30, 27))
        assertEquals("-2", GolfRules.relativeText(25, 27))
    }

    @Test fun theUniqueLowestTotalWinsAndATieIsADraw() {
        assertEquals(listOf(1), GolfRules.winners(listOf(30, 26, 28), 27, 9))
        assertEquals(emptyList<Int>(), GolfRules.winners(listOf(26, 26, 31), 27, 9))
        assertEquals(listOf(0), GolfRules.winners(listOf(20, 21), 27, 9))
    }

    @Test fun aSoloRoundWinsWithinOneStrokeAHolePerPar() {
        assertEquals(listOf(0), GolfRules.winners(listOf(36), 27, 9))
        assertEquals(emptyList<Int>(), GolfRules.winners(listOf(37), 27, 9))
        assertEquals(listOf(0), GolfRules.winners(listOf(11), 8, 3))
        assertEquals(emptyList<Int>(), GolfRules.winners(listOf(12), 8, 3))
    }

    @Test fun turnsRotateAndSkipFinishedPlayers() {
        assertEquals(1, GolfRules.nextToPutt(0, listOf(false, false, false)))
        assertEquals(2, GolfRules.nextToPutt(0, listOf(false, true, false)))
        assertEquals(0, GolfRules.nextToPutt(2, listOf(false, true, false)))
        assertEquals(0, GolfRules.nextToPutt(0, listOf(false)))
        assertEquals(0, GolfRules.nextToPutt(0, listOf(false, true)))
        assertEquals(-1, GolfRules.nextToPutt(1, listOf(true, true)))
    }

    @Test fun theLowestScoreOnAHoleHasTheHonor() {
        assertEquals(2, GolfRules.honor(listOf(4, 3, 2)))
        assertEquals(0, GolfRules.honor(listOf(3, 3, 5)))
    }

    @Test fun theStrokeLimitCapsTheCountEvenWithAPenalty() {
        assertEquals(3, GolfRules.addStroke(2, 0, 6))
        assertEquals(4, GolfRules.addStroke(2, 1, 6))
        assertEquals(6, GolfRules.addStroke(5, 1, 6))
        assertEquals(6, GolfRules.addStroke(6, 1, 6))
    }

    @Test fun theCourseHasNinePars() {
        assertEquals(listOf(2, 3, 2, 3, 3, 4, 3, 3, 4), GolfCourse.holes.map { it.par })
        assertEquals(27, GolfCourse.holes.sumOf { it.par })
    }
}
