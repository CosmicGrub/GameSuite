package com.gamesuite.haptics

import com.gamesuite.haptics.HapticSignal.CELEBRATION
import com.gamesuite.haptics.HapticSignal.FAILURE
import com.gamesuite.haptics.HapticSignal.LIGHT_TICK
import com.gamesuite.haptics.HapticSignal.NORMAL_ACTION
import com.gamesuite.haptics.HapticSignal.STRONG_ACTION
import com.gamesuite.haptics.HapticSignal.SUCCESS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleHapticCuesTest {
    @Test
    fun `lights out toggle is a light tick and solve a celebration`() {
        assertEquals(listOf(LIGHT_TICK), LightsOutHapticCues.forToggle().signals)
        assertEquals(listOf(CELEBRATION), LightsOutHapticCues.forSolve().signals)
    }

    @Test
    fun `lights out solving tap hands over to the solve cue`() {
        assertEquals(listOf(LIGHT_TICK), LightsOutHapticCues.forTap(false).signals)
        assertTrue(LightsOutHapticCues.forTap(true).isEmpty)
    }

    @Test
    fun `sliding puzzle slide blocked and solve`() {
        assertEquals(listOf(NORMAL_ACTION), SlidingPuzzleHapticCues.forSlide().signals)
        assertEquals(listOf(LIGHT_TICK), SlidingPuzzleHapticCues.forBlocked().signals)
        assertEquals(listOf(CELEBRATION), SlidingPuzzleHapticCues.forSolve().signals)
        assertFalse(FAILURE in SlidingPuzzleHapticCues.forBlocked().signals)
    }

    @Test
    fun `sliding puzzle tap decision`() {
        assertEquals(listOf(LIGHT_TICK), SlidingPuzzleHapticCues.forTap(moved = false, solvedByThisTap = false).signals)
        assertEquals(listOf(NORMAL_ACTION), SlidingPuzzleHapticCues.forTap(moved = true, solvedByThisTap = false).signals)
        assertTrue(SlidingPuzzleHapticCues.forTap(moved = true, solvedByThisTap = true).isEmpty)
    }

    @Test
    fun `puzzle cues are bounded`() {
        for (s in listOf(LightsOutHapticCues.forToggle(), SlidingPuzzleHapticCues.forSlide(), SlidingPuzzleHapticCues.forBlocked())) {
            assertTrue(s.spanMs <= HapticSequence.MAX_SPAN_MS)
        }
    }
}

class StrategyHapticCuesTest {
    @Test
    fun `hex placement and end of board`() {
        assertEquals(listOf(NORMAL_ACTION), HexHapticCues.forPlace().signals)
        val win = HexHapticCues.forBoardEnd(true)
        assertEquals(listOf(SUCCESS, CELEBRATION), win.signals)
        assertTrue(win.cues[0].delayMs < win.cues[1].delayMs)
        assertTrue(
            "SUCCESS must finish before CELEBRATION starts",
            win.cues[0].delayMs + HapticPatterns.patternFor(SUCCESS).totalMs <= win.cues[1].delayMs
        )
        assertEquals(listOf(FAILURE), HexHapticCues.forBoardEnd(false).signals)
    }

    @Test
    fun `hex end cue starts after the placement tick finishes`() {
        assertTrue(HexHapticCues.forBoardEnd(false).cues[0].delayMs >= HapticPatterns.patternFor(NORMAL_ACTION).totalMs)
    }

    @Test
    fun `hare and hounds moves and ends`() {
        assertEquals(listOf(NORMAL_ACTION), HaHHapticCues.forMove().signals)
        assertEquals(listOf(SUCCESS, CELEBRATION), HaHHapticCues.forBoardEnd(localWon = true, houndsTrapped = true).signals)
        assertEquals(listOf(CELEBRATION), HaHHapticCues.forBoardEnd(localWon = true, houndsTrapped = false).signals)
        assertEquals(listOf(FAILURE), HaHHapticCues.forBoardEnd(localWon = false, houndsTrapped = true).signals)
        assertEquals(listOf(SUCCESS), HaHHapticCues.forBoardEnd(localWon = null, houndsTrapped = false).signals)
        val trapped = HaHHapticCues.forBoardEnd(true, true)
        assertTrue(
            trapped.cues[0].delayMs + HapticPatterns.patternFor(SUCCESS).totalMs <= trapped.cues[1].delayMs
        )
    }

    @Test
    fun `snake milestones every ten`() {
        assertFalse(SnakeHapticCues.isMilestone(3, 4))
        assertFalse(SnakeHapticCues.isMilestone(8, 9))
        assertTrue(SnakeHapticCues.isMilestone(9, 10))
        assertFalse(SnakeHapticCues.isMilestone(10, 11))
        assertTrue(SnakeHapticCues.isMilestone(19, 20))
        // Growth of more than one cell that jumps over a multiple still counts once.
        assertTrue(SnakeHapticCues.isMilestone(8, 11))
        // Never on shrink / same length (a new run).
        assertFalse(SnakeHapticCues.isMilestone(10, 3))
        assertFalse(SnakeHapticCues.isMilestone(10, 10))
    }

    @Test
    fun `snake food and death`() {
        assertEquals(listOf(NORMAL_ACTION), SnakeHapticCues.forFood(4, 5).signals)
        assertEquals(listOf(SUCCESS), SnakeHapticCues.forFood(9, 10).signals)
        val death = SnakeHapticCues.forDeath()
        assertEquals(listOf(FAILURE, STRONG_ACTION), death.signals)
        assertTrue(death.spanMs <= HapticSequence.MAX_SPAN_MS)
        assertTrue(death.cues[0].delayMs + HapticPatterns.patternFor(FAILURE).totalMs <= death.cues[1].delayMs)
        assertEquals(listOf(CELEBRATION), SnakeHapticCues.forRunEnd(true).signals)
        assertEquals(death.signals, SnakeHapticCues.forRunEnd(false).signals)
    }

    @Test
    fun `non-celebration strategy cues are bounded`() {
        val all = listOf(
            HexHapticCues.forPlace(), HexHapticCues.forBoardEnd(false),
            HaHHapticCues.forMove(), HaHHapticCues.forBoardEnd(false, true),
            SnakeHapticCues.forFood(9, 10), SnakeHapticCues.forDeath()
        )
        for (s in all) assertTrue("$s", s.spanMs <= HapticSequence.MAX_SPAN_MS)
    }
}
