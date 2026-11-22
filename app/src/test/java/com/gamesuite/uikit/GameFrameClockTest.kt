package com.gamesuite.uikit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFrameClockTest {
    private fun source(vararg frames: Long): FrameSource {
        val it = frames.iterator()
        return FrameSource { if (it.hasNext()) it.next() else null }
    }

    @Test fun firstFrameIsZeroThenRealDeltas() {
        val s = FrameStepper()
        assertEquals(0f, s.step(5_000_000_000L), 0f)
        assertEquals(0.016f, s.step(5_016_000_000L), 1e-6f)
    }

    @Test fun hugeGapIsClamped() {
        val s = FrameStepper(maxDtSeconds = 0.1f)
        s.step(0L)
        assertEquals(0.1f, s.step(10_000_000_000L), 0f)
    }

    @Test fun backwardsTimeNeverGoesNegative() {
        val s = FrameStepper()
        s.step(1_000_000_000L)
        assertEquals(0f, s.step(900_000_000L), 0f)
    }

    @Test fun resetMakesTheNextFrameZeroAgain() {
        val s = FrameStepper()
        s.step(0L); s.step(16_000_000L)
        s.reset()
        assertEquals(0f, s.step(9_000_000_000L), 0f)
    }

    @Test fun loopDeliversOneCallbackPerFrameWithStepperDt() = runBlocking {
        val dts = ArrayList<Float>()
        runFrameLoop(source(0L, 16_000_000L, 33_000_000L), FrameStepper()) { dt, _ -> dts += dt }
        assertEquals(3, dts.size)
        assertEquals(0f, dts[0], 0f)
        assertEquals(0.016f, dts[1], 1e-6f)
        assertEquals(0.017f, dts[2], 1e-6f)
    }

    @Test fun loopStopsTheMomentRunningTurnsFalse() = runBlocking {
        var running = true
        var calls = 0
        runFrameLoop(source(0L, 1L, 2L, 3L, 4L), FrameStepper(), { running }) { _, _ ->
            calls++
            if (calls == 2) running = false
        }
        assertEquals(2, calls)
    }

    @Test fun notRunningFromTheStartNeverCallsBack() = runBlocking {
        var calls = 0
        runFrameLoop(source(0L, 1L), FrameStepper(), { false }) { _, _ -> calls++ }
        assertEquals(0, calls)
    }

    @Test fun restartedLoopDoesNotCarryAStaleBaseline() = runBlocking {
        val stepper = FrameStepper()
        runFrameLoop(source(0L, 16_000_000L), stepper) { _, _ -> }
        val dts = ArrayList<Float>()
        runFrameLoop(source(60_000_000_000L, 60_016_000_000L), stepper) { dt, _ -> dts += dt }
        assertTrue("first frame after restart is 0, not 60s", dts[0] == 0f)
    }
}
