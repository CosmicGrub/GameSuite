package com.gamesuite.ui

import com.gamesuite.core.GameResult
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.MatchOutcome
import com.gamesuite.haptics.HapticSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultFeedbackTest {

    private fun plan(
        outcome: LocalOutcome?,
        aborted: Boolean = false,
        sound: Boolean = true,
        haptics: Boolean = true,
        reduced: Boolean = false
    ) = resultFeedbackPlan(outcome, aborted, sound, haptics, reduced)

    @Test
    fun `win loss and draw each get their own cue`() {
        val win = plan(LocalOutcome.WIN)
        val loss = plan(LocalOutcome.LOSS)
        val draw = plan(LocalOutcome.DRAW)
        assertEquals(HapticSignal.CELEBRATION, win.haptic)
        assertEquals(HapticSignal.FAILURE, loss.haptic)
        assertEquals(HapticSignal.NORMAL_ACTION, draw.haptic)
        assertEquals(3, win.notes.size)
        assertTrue("a win rises in pitch", win.notes.zipWithNext().all { (a, b) -> b.rate > a.rate && b.delayMs > a.delayMs })
        assertTrue("a loss is lower than the neutral pitch", loss.notes.single().rate < 1f)
        assertEquals(1f, draw.notes.single().rate, 0f)
    }

    @Test
    fun `settings switches silence only their own channel`() {
        val noSound = plan(LocalOutcome.WIN, sound = false)
        assertTrue(noSound.notes.isEmpty())
        assertEquals(HapticSignal.CELEBRATION, noSound.haptic)

        val noHaptics = plan(LocalOutcome.WIN, haptics = false)
        assertNull(noHaptics.haptic)
        assertFalse(noHaptics.notes.isEmpty())

        assertTrue(plan(LocalOutcome.WIN, sound = false, haptics = false).isSilent)
    }

    @Test
    fun `reduced motion collapses the cue to one note and a gentler buzz`() {
        val win = plan(LocalOutcome.WIN, reduced = true)
        assertEquals(1, win.notes.size)
        assertEquals(HapticSignal.SUCCESS, win.haptic)
        assertEquals(1, plan(LocalOutcome.LOSS, reduced = true).notes.size)
    }

    @Test
    fun `aborted or unattributed matches play nothing`() {
        assertTrue(plan(LocalOutcome.WIN, aborted = true).isSilent)
        assertTrue(plan(null).isSilent)
    }

    @Test
    fun `plan for a published outcome follows its result`() {
        val outcome = MatchOutcome("g", "G", GameResult(), LocalOutcome.LOSS)
        val p = resultFeedbackPlanFor(outcome, soundEnabled = true, hapticsEnabled = true, reducedMotion = false)
        assertEquals(HapticSignal.FAILURE, p.haptic)
        val aborted = MatchOutcome("g", "G", GameResult(wasAborted = true), LocalOutcome.WIN)
        assertTrue(resultFeedbackPlanFor(aborted, true, true, false).isSilent)
    }

    @Test
    fun `the gate lets each outcome instance through exactly once`() {
        val gate = ResultFeedbackGate()
        val first = MatchOutcome("g", "G", GameResult(), LocalOutcome.WIN)
        assertTrue(gate.firstTime(first))
        assertFalse("a replay of the same outcome", gate.firstTime(first))
        assertFalse(gate.firstTime(first))
        // An equal-looking second match is a different instance and must still play.
        val second = MatchOutcome("g", "G", GameResult(), LocalOutcome.WIN)
        assertEquals(first, second)
        assertTrue(gate.firstTime(second))
        assertFalse(gate.firstTime(second))
    }
}
