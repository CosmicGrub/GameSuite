package com.gamesuite.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureWindowStateTest {
    private class Rig {
        val calls = ArrayList<Boolean>()
        val state = SecureWindowState { calls += it }
    }

    @Test fun startsClearAndNeverAppliesAnything() {
        val r = Rig()
        assertFalse(r.state.secure)
        assertTrue(r.calls.isEmpty())
    }

    @Test fun oneOwnerSetsThenClearsTheFlag() {
        val r = Rig(); val a = Any()
        r.state.set(a, true)
        assertTrue(r.state.secure)
        r.state.set(a, false)
        assertFalse(r.state.secure)
        assertEquals(listOf(true, false), r.calls)
    }

    @Test fun repeatingTheSameValueAppliesOnlyOnce() {
        val r = Rig(); val a = Any()
        r.state.set(a, true); r.state.set(a, true); r.state.set(a, true)
        r.state.set(a, false); r.state.set(a, false)
        assertEquals(listOf(true, false), r.calls)
        assertEquals(0, r.state.ownerCount)
    }

    @Test fun twoOwnersKeepTheFlagUntilTheLastLeaves() {
        val r = Rig(); val a = Any(); val b = Any()
        r.state.set(a, true)
        r.state.set(b, true)
        r.state.release(a)
        assertTrue("b still wants it", r.state.secure)
        r.state.release(b)
        assertFalse(r.state.secure)
        assertEquals(listOf(true, false), r.calls)
    }

    @Test fun releasingAnOwnerThatNeverAskedChangesNothing() {
        val r = Rig()
        r.state.release(Any())
        assertTrue(r.calls.isEmpty())
        r.state.set(Any(), true)
        r.state.release(Any())
        assertTrue(r.state.secure)
    }

    @Test fun clearDropsEveryClaimAndClearsTheFlagOnce() {
        val r = Rig()
        r.state.set(Any(), true); r.state.set(Any(), true)
        r.state.clear()
        assertFalse(r.state.secure)
        assertEquals(0, r.state.ownerCount)
        r.state.clear()
        assertEquals(listOf(true, false), r.calls)
    }

    @Test fun aDisposeAfterAnActiveFlipLeavesTheFlagOff() {
        // What the composable does: DisposableEffect(active) sets, onDispose releases; flipping active re-runs both.
        val r = Rig(); val owner = Any()
        r.state.set(owner, true)      // active = true
        r.state.set(owner, false)     // dispose of that effect
        r.state.set(owner, false)     // active = false
        r.state.set(owner, true)      // active = true again
        r.state.set(owner, false)     // leaving the screen
        assertFalse(r.state.secure)
        assertEquals(listOf(true, false, true, false), r.calls)
    }
}
