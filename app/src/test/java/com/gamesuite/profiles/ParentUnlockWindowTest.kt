package com.gamesuite.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentUnlockWindowTest {
    private val min = 60_000L

    @Test fun closedUntilUnlocked() {
        val w = ParentUnlockWindow()
        assertFalse(w.isOpen(0))
        w.touch(10) // a touch never opens it
        assertFalse(w.isOpen(20))
    }

    @Test fun staysOpenWhileTouchedAndClosesAfterTwoIdleMinutes() {
        val w = ParentUnlockWindow()
        w.unlock(0)
        assertTrue(w.isOpen(119_999))
        w.touch(100_000)
        assertTrue(w.isOpen(100_000 + 2 * min - 1))
        assertFalse(w.isOpen(100_000 + 2 * min))
    }

    @Test fun onceIdleClosedATouchCannotReopenIt() {
        val w = ParentUnlockWindow()
        w.unlock(0)
        assertFalse(w.isOpen(3 * min))
        w.touch(3 * min + 1)
        assertFalse(w.isOpen(3 * min + 2))
    }

    @Test fun closeShutsItAtOnceAndUnlockReopens() {
        val w = ParentUnlockWindow()
        w.unlock(0)
        w.close()
        assertFalse(w.isOpen(1))
        w.unlock(5)
        assertTrue(w.isOpen(6))
    }
}
