package com.gamesuite.games.partytoolkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyToolkitTimerTest {

    @Test
    fun `initial is full, stopped, and has not finished`() {
        val s = PartyToolkitTimer.initial(60)
        assertEquals(60_000L, s.remainingMs)
        assertFalse(s.running)
        assertEquals(0, s.finishSeq)
        assertEquals(1f, PartyToolkitTimer.progress(s), 0.0001f)
    }

    @Test
    fun `tick counts down from the wall clock, not from call count`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(10), nowMs = 1_000L)
        // Many ticks at the same instant must not drain anything.
        repeat(50) { s = PartyToolkitTimer.tick(s, 1_000L) }
        assertEquals(10_000L, s.remainingMs)
        s = PartyToolkitTimer.tick(s, 4_000L)
        assertEquals(7_000L, s.remainingMs)
        assertTrue(s.running)
    }

    @Test
    fun `reaching zero stops the timer and bumps finishSeq exactly once`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(2), nowMs = 0L)
        s = PartyToolkitTimer.tick(s, 2_000L)
        assertEquals(0L, s.remainingMs)
        assertFalse(s.running)
        assertEquals(1, s.finishSeq)
        // Further ticks after finishing are no-ops.
        s = PartyToolkitTimer.tick(s, 9_000L)
        assertEquals(1, s.finishSeq)
    }

    @Test
    fun `a tick long after the deadline still finishes once (backgrounded app)`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(5), nowMs = 0L)
        s = PartyToolkitTimer.tick(s, 600_000L)
        assertEquals(1, s.finishSeq)
        assertEquals(0L, s.remainingMs)
    }

    @Test
    fun `pause freezes remaining time and resume continues from it`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(10), nowMs = 0L)
        s = PartyToolkitTimer.pause(s, 3_000L)
        assertFalse(s.running)
        assertEquals(7_000L, s.remainingMs)
        // Time passing while paused costs nothing.
        s = PartyToolkitTimer.tick(s, 50_000L)
        assertEquals(7_000L, s.remainingMs)
        s = PartyToolkitTimer.start(s, nowMs = 60_000L)
        s = PartyToolkitTimer.tick(s, 62_000L)
        assertEquals(5_000L, s.remainingMs)
    }

    @Test
    fun `pause at or past the deadline finishes instead of pausing`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(3), nowMs = 0L)
        s = PartyToolkitTimer.pause(s, 3_500L)
        assertEquals(0L, s.remainingMs)
        assertEquals(1, s.finishSeq)
    }

    @Test
    fun `start is idempotent while running and a no-op with nothing remaining`() {
        val running = PartyToolkitTimer.start(PartyToolkitTimer.initial(10), nowMs = 0L)
        assertEquals(running, PartyToolkitTimer.start(running, nowMs = 5_000L))
        val done = PartyToolkitTimer.tick(PartyToolkitTimer.start(PartyToolkitTimer.initial(1), 0L), 5_000L)
        assertEquals(done, PartyToolkitTimer.start(done, nowMs = 6_000L))
    }

    @Test
    fun `reset and setTotal stop the timer and refill it`() {
        var s = PartyToolkitTimer.start(PartyToolkitTimer.initial(10), nowMs = 0L)
        s = PartyToolkitTimer.tick(s, 4_000L)
        val reset = PartyToolkitTimer.reset(s)
        assertFalse(reset.running)
        assertEquals(10_000L, reset.remainingMs)
        val changed = PartyToolkitTimer.setTotal(s, 180)
        assertFalse(changed.running)
        assertEquals(180, changed.totalSeconds)
        assertEquals(180_000L, changed.remainingMs)
    }

    @Test
    fun `remainingSeconds rounds up so zero only shows when finished`() {
        val s = PartyToolkitTimerState(totalSeconds = 10, remainingMs = 100L)
        assertEquals(1, PartyToolkitTimer.remainingSeconds(s))
        assertEquals(0, PartyToolkitTimer.remainingSeconds(s.copy(remainingMs = 0L)))
        assertEquals(2, PartyToolkitTimer.remainingSeconds(s.copy(remainingMs = 1_001L)))
    }

    @Test
    fun `non-positive durations are clamped to one second`() {
        assertEquals(1, PartyToolkitTimer.initial(0).totalSeconds)
        assertEquals(1, PartyToolkitTimer.setTotal(PartyToolkitTimer.initial(), -5).totalSeconds)
    }
}
