package com.gamesuite.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGateTest {
    @Test fun fingerSizedDownIsAcceptedPalmIsNot() {
        val g = PalmGate(100f)
        assertTrue(g.acceptDown(1, 40f))
        assertFalse(g.acceptDown(2, 300f))
        assertTrue(g.isRejected(2))
        assertFalse(g.isRejected(1))
    }

    @Test fun unknownSizeAndDisabledLimitAreAccepted() {
        assertTrue(PalmGate(100f).acceptDown(1, 0f))
        assertTrue(PalmGate(0f).acceptDown(1, 9999f))
    }

    @Test fun palmStaysRejectedUntilReleased() {
        val g = PalmGate(100f)
        g.acceptDown(7, 500f)
        assertTrue(g.isRejected(7))
        g.release(7)
        assertFalse(g.isRejected(7))
    }

    @Test fun reusedIdAsFingerClearsStalePalm() {
        val g = PalmGate(100f)
        g.acceptDown(7, 500f)
        assertTrue(g.acceptDown(7, 30f))
        assertFalse(g.isRejected(7))
    }

    @Test fun retainDropsVanishedPalms() {
        val g = PalmGate(100f)
        g.acceptDown(1, 500f); g.acceptDown(2, 500f)
        g.retain(listOf(2L))
        assertFalse(g.isRejected(1)); assertTrue(g.isRejected(2))
    }

    @Test fun twentyTwoMillimetresAt420Dpi() {
        assertEquals(363.78f, PalmGate.maxFingerContactPx(420f), 0.1f)
    }

    @Test fun contactSizesAreMatchedByPosition() {
        val c = ContactSizes()
        c.set(floatArrayOf(100f, 700f), floatArrayOf(900f, 905f), floatArrayOf(2f, 480f))
        assertEquals(480f, c.sizeAt(703f, 905f), 0f)
        assertEquals(0f, c.sizeAt(300f, 300f), 0f)
    }
}
