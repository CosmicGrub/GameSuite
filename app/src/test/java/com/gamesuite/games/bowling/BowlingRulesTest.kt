package com.gamesuite.games.bowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BowlingRulesTest {
    private fun cum(vararg rolls: Int) = BowlingRules.cumulative(rolls.toList())

    @Test fun aPerfectGameIs300() {
        val rolls = List(12) { 10 }
        assertEquals((1..10).map { it * 30 }, BowlingRules.cumulative(rolls))
        assertEquals(300, BowlingRules.total(rolls))
        assertTrue(BowlingRules.isComplete(rolls))
        assertTrue(BowlingRules.isValid(rolls))
        assertEquals(BowlingRules.MAX_SCORE, BowlingRules.total(rolls))
    }

    @Test fun aGutterGameIsZero() {
        val rolls = List(20) { 0 }
        assertEquals(List(10) { 0 }, BowlingRules.cumulative(rolls))
        assertEquals(0, BowlingRules.total(rolls))
        assertTrue(BowlingRules.isComplete(rolls))
    }

    @Test fun allSparesWithFivesIs150() {
        val rolls = List(21) { 5 }
        assertEquals((1..10).map { it * 15 }, BowlingRules.cumulative(rolls))
        assertEquals(150, BowlingRules.total(rolls))
        assertTrue(BowlingRules.isComplete(rolls))
    }

    @Test fun nineAndMissTenTimesIs90() {
        val rolls = List(10) { listOf(9, 0) }.flatten()
        assertEquals(90, BowlingRules.total(rolls))
        assertEquals((1..10).map { it * 9 }, BowlingRules.cumulative(rolls))
    }

    @Test fun aMixedGameMatchesTheHandScoredSheet() {
        val rolls = listOf(10, 7, 3, 9, 0, 10, 0, 8, 8, 2, 0, 6, 10, 10, 10, 8, 1)
        assertEquals(listOf(20, 39, 48, 66, 74, 84, 90, 120, 148, 167), BowlingRules.cumulative(rolls))
        assertEquals(167, BowlingRules.total(rolls))
        assertTrue(BowlingRules.isValid(rolls))
        assertTrue(BowlingRules.isComplete(rolls))
    }

    @Test fun bonusesAreHeldBackUntilTheBallsExist() {
        assertEquals(List(10) { null }, cum(10))
        assertEquals(10, BowlingRules.total(listOf(10)))
        assertEquals(15, BowlingRules.total(listOf(10, 5)))
        assertEquals(20, cum(10, 5, 5)[0])
        assertNull(cum(10, 5, 5)[1])
        assertEquals(listOf(20, 40), cum(10, 5, 5, 10, 0).take(2).map { it })
        assertNull(cum(5, 5)[0])
        assertEquals(15, cum(5, 5, 5)[0])
        assertEquals(8, BowlingRules.total(listOf(3, 5)))
    }

    @Test fun theTenthFrameGivesAThirdBallOnlyAfterAStrikeOrSpare() {
        val zeros = List(18) { 0 }
        assertEquals(15, BowlingRules.total(zeros + listOf(9, 1, 5)))
        assertTrue(BowlingRules.isComplete(zeros + listOf(9, 1, 5)))
        assertFalse(BowlingRules.isComplete(zeros + listOf(9, 1)))
        assertFalse(BowlingRules.isComplete(zeros + listOf(10)))
        assertFalse(BowlingRules.isComplete(zeros + listOf(10, 10)))
        assertTrue(BowlingRules.isComplete(zeros + listOf(10, 10, 10)))
        assertEquals(30, BowlingRules.total(zeros + listOf(10, 10, 10)))
        assertTrue(BowlingRules.isComplete(zeros + listOf(4, 5)))
        assertEquals(9, BowlingRules.total(zeros + listOf(4, 5)))
        assertNull(cum(*(zeros + listOf(10)).toIntArray())[9])
    }

    @Test fun nextBallPositions() {
        assertEquals(0 to 0, BowlingRules.next(emptyList()))
        assertEquals(0 to 1, BowlingRules.next(listOf(3)))
        assertEquals(1 to 0, BowlingRules.next(listOf(3, 4)))
        assertEquals(1 to 0, BowlingRules.next(listOf(10)))
        val zeros = List(18) { 0 }
        assertEquals(9 to 0, BowlingRules.next(zeros))
        assertEquals(9 to 1, BowlingRules.next(zeros + listOf(10)))
        assertEquals(9 to 1, BowlingRules.next(zeros + listOf(4)))
        assertEquals(9 to 2, BowlingRules.next(zeros + listOf(5, 5)))
        assertEquals(9 to 2, BowlingRules.next(zeros + listOf(10, 3)))
        assertNull(BowlingRules.next(zeros + listOf(10, 3, 4)))
        assertNull(BowlingRules.next(zeros + listOf(4, 3)))
    }

    @Test fun pinsFacingTheNextBall() {
        assertEquals(10, BowlingRules.standingCountFor(emptyList()))
        assertEquals(7, BowlingRules.standingCountFor(listOf(3)))
        assertEquals(10, BowlingRules.standingCountFor(listOf(10)))
        val zeros = List(18) { 0 }
        assertEquals(10, BowlingRules.standingCountFor(zeros + listOf(10)))
        assertEquals(3, BowlingRules.standingCountFor(zeros + listOf(10, 7)))
        assertEquals(10, BowlingRules.standingCountFor(zeros + listOf(10, 10)))
        assertEquals(10, BowlingRules.standingCountFor(zeros + listOf(4, 6)))
        assertEquals(6, BowlingRules.standingCountFor(zeros + listOf(4)))
    }

    @Test fun framesSplitAndMarks() {
        val rolls = listOf(10, 7, 3, 9, 0)
        assertEquals(listOf(listOf(10), listOf(7, 3), listOf(9, 0)), BowlingRules.frames(rolls))
        assertTrue(BowlingRules.isStrike(rolls, 0))
        assertFalse(BowlingRules.isStrike(rolls, 1))
        assertTrue(BowlingRules.isSpare(rolls, 1))
        assertFalse(BowlingRules.isSpare(rolls, 2))
    }

    @Test fun invalidSequencesAreRefused() {
        assertFalse(BowlingRules.isValid(listOf(11)))
        assertFalse(BowlingRules.isValid(listOf(-1)))
        assertFalse(BowlingRules.isValid(listOf(5, 6)))
        val zeros = List(18) { 0 }
        assertFalse(BowlingRules.isValid(zeros + listOf(10, 5, 6)))
        assertFalse(BowlingRules.isValid(zeros + listOf(5, 3, 4)))
        assertFalse(BowlingRules.isValid(zeros + listOf(1, 2, 3, 4)))
        assertFalse(BowlingRules.isValid(List(30) { 0 }))
        assertTrue(BowlingRules.isValid(zeros + listOf(10, 10, 10)))
        assertTrue(BowlingRules.isValid(zeros + listOf(5, 5, 10)))
        assertTrue(BowlingRules.isValid(zeros + listOf(10, 3, 7)))
    }

    @Test fun anyValidCompleteGameScoresWithinRange() {
        val rnd = java.util.Random(4)
        repeat(300) {
            val rolls = ArrayList<Int>()
            while (BowlingRules.next(rolls) != null) {
                val left = BowlingRules.standingCountFor(rolls)
                rolls += rnd.nextInt(left + 1)
                assertTrue(BowlingRules.isValid(rolls))
            }
            assertTrue(BowlingRules.isComplete(rolls))
            val t = BowlingRules.total(rolls)
            assertTrue(t in 0..300)
            assertEquals(t, BowlingRules.cumulative(rolls).last())
            assertTrue(BowlingRules.cumulative(rolls).all { it != null })
        }
    }
}
