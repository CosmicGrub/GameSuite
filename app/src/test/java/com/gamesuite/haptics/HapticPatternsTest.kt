package com.gamesuite.haptics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPatternsTest {

    @Test
    fun `every signal has a non-empty well-formed pattern`() {
        for (signal in HapticSignal.values()) {
            val p = HapticPatterns.patternFor(signal)
            assertTrue("$signal empty", p.timings.isNotEmpty())
            assertEquals("$signal arrays differ", p.timings.size, p.amplitudes.size)
            assertTrue("$signal has no buzz", p.amplitudes.any { it > 0 })
            for (a in p.amplitudes) assertTrue("$signal amplitude $a", a in 0..255)
            for (t in p.timings) assertTrue("$signal negative timing", t >= 0)
        }
    }

    @Test
    fun `durations stay within budget`() {
        for (signal in HapticSignal.values()) {
            val total = HapticPatterns.patternFor(signal).totalMs
            if (signal == HapticSignal.CELEBRATION) {
                assertTrue("CELEBRATION $total", total < HapticPatterns.MAX_CELEBRATION_MS)
            } else {
                assertTrue("$signal $total", total < HapticPatterns.MAX_MS)
            }
        }
    }

    @Test
    fun `all signals are distinguishable`() {
        val all = HapticSignal.values()
        for (i in all.indices) for (j in i + 1 until all.size) {
            assertFalse(
                "${all[i]} and ${all[j]} share a waveform",
                HapticPatterns.patternFor(all[i]).sameAs(HapticPatterns.patternFor(all[j]))
            )
        }
    }

    @Test
    fun `ripple step is the lightest and landing is softer than a normal action`() {
        val ripple = HapticPatterns.patternFor(HapticSignal.RIPPLE_STEP)
        val tick = HapticPatterns.patternFor(HapticSignal.LIGHT_TICK)
        val landing = HapticPatterns.patternFor(HapticSignal.LANDING)
        val normal = HapticPatterns.patternFor(HapticSignal.NORMAL_ACTION)
        assertTrue(ripple.amplitudes.max() < tick.amplitudes.max())
        assertTrue(ripple.totalMs < tick.totalMs)
        assertTrue(landing.amplitudes.max() < normal.amplitudes.max())
        assertTrue(landing.totalMs > normal.totalMs)
    }

    @Test
    fun `the original seven signals keep their original waveforms`() {
        assertEquals(listOf(0L, 12L), HapticPatterns.patternFor(HapticSignal.LIGHT_TICK).timings.toList())
        assertEquals(listOf(0, 140), HapticPatterns.patternFor(HapticSignal.NORMAL_ACTION).amplitudes.toList())
        assertEquals(
            listOf(0L, 25L, 40L, 25L, 40L, 25L, 40L, 60L),
            HapticPatterns.patternFor(HapticSignal.CELEBRATION).timings.toList()
        )
    }

    @Test
    fun `sequence sorts cues and computes its span`() {
        val seq = HapticSequence.of(
            HapticCue(HapticSignal.STRONG_ACTION, 300), HapticCue(HapticSignal.NORMAL_ACTION, 0)
        )
        assertEquals(listOf(HapticSignal.NORMAL_ACTION, HapticSignal.STRONG_ACTION), seq.signals)
        assertEquals(300L + HapticPatterns.patternFor(HapticSignal.STRONG_ACTION).totalMs, seq.endMs)
        assertEquals(seq.endMs, seq.spanMs)
        assertEquals(0L, HapticSequence.EMPTY.endMs)
    }

    @Test
    fun `lead-in is excluded from the span`() {
        val seq = HapticSequence(listOf(HapticCue(HapticSignal.LANDING, 300)), leadInMs = 300)
        assertEquals(HapticPatterns.patternFor(HapticSignal.LANDING).totalMs, seq.spanMs)
    }

    @Test
    fun `then appends after the end with a gap`() {
        val a = HapticSequence.single(HapticSignal.NORMAL_ACTION)
        val b = HapticSequence.single(HapticSignal.SUCCESS)
        val joined = a.then(b, gapMs = 50)
        assertEquals(listOf(0L, 25L + 50L), joined.cues.map { it.delayMs })
        assertEquals(a, a.then(HapticSequence.EMPTY))
    }

    @Test
    fun `play waits the gaps between cues and fires in order`() {
        val seq = HapticSequence.of(
            HapticCue(HapticSignal.NORMAL_ACTION, 0),
            HapticCue(HapticSignal.RIPPLE_STEP, 60),
            HapticCue(HapticSignal.STRONG_ACTION, 100)
        )
        val waits = mutableListOf<Long>()
        val fired = mutableListOf<HapticSignal>()
        runBlocking { seq.play(wait = { waits += it }, fire = { fired += it }) }
        assertEquals(listOf(60L, 40L), waits)
        assertEquals(seq.signals, fired)
    }

    @Test
    fun `shouldPlay is off in the arena and at all-bot tables`() {
        assertTrue(HapticRules.shouldPlay(false, listOf(false, true)))
        assertTrue(HapticRules.shouldPlay(false, listOf(false, false)))
        assertFalse(HapticRules.shouldPlay(true, listOf(false, true)))
        assertFalse(HapticRules.shouldPlay(false, listOf(true, true)))
    }

    @Test
    fun `lighter mover is a bot or the remote seat`() {
        assertTrue(HapticRules.isLighterMover(1, listOf(false, true), null))
        assertFalse(HapticRules.isLighterMover(0, listOf(false, true), null))
        assertFalse(HapticRules.isLighterMover(1, listOf(false, false), null))
        assertTrue(HapticRules.isLighterMover(1, listOf(false, false), 0))
        assertFalse(HapticRules.isLighterMover(0, listOf(false, false), 0))
    }

    @Test
    fun `outcome is from the local persons point of view`() {
        assertEquals(HapticOutcome.DRAW, HapticRules.outcomeFor(null, listOf(false, true), null))
        assertEquals(HapticOutcome.DRAW, HapticRules.outcomeFor(-1, listOf(false, true), null))
        assertEquals(HapticOutcome.WIN, HapticRules.outcomeFor(0, listOf(false, true), null))
        assertEquals(HapticOutcome.LOSS, HapticRules.outcomeFor(1, listOf(false, true), null))
        assertEquals(HapticOutcome.WIN, HapticRules.outcomeFor(1, listOf(false, false), null))
        assertEquals(HapticOutcome.LOSS, HapticRules.outcomeFor(0, listOf(false, false), 1))
        assertEquals(HapticOutcome.WIN, HapticRules.outcomeFor(1, listOf(false, false), 1))
    }

    @Test
    fun `change tracker treats the first value as a baseline`() {
        val t = ChangeTracker<Int>()
        assertNull(t.update(5))
        assertEquals(5, t.update(6))
        assertEquals(6, t.update(6))
    }
}
