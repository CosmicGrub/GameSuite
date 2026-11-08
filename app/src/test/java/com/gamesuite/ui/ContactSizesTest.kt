package com.gamesuite.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSizesTest {
    @Test
    fun sizeIsLookedUpByPositionNotByPointerId() {
        val c = ContactSizes()
        c.set(floatArrayOf(100f, 700f), floatArrayOf(900f, 905f), floatArrayOf(2f, 480f))
        assertEquals(2f, c.sizeAt(100f, 900f), 0f)
        assertEquals(480f, c.sizeAt(703f, 905f), 0f)
    }

    @Test
    fun noContactNearbyMeansUnknownSize() {
        val c = ContactSizes()
        c.set(floatArrayOf(100f), floatArrayOf(100f), floatArrayOf(480f))
        assertEquals(0f, c.sizeAt(300f, 300f), 0f)
        assertEquals(0f, ContactSizes().sizeAt(1f, 1f), 0f)
    }

    @Test
    fun theNearestOfTwoCloseContactsWins() {
        val c = ContactSizes()
        c.set(floatArrayOf(100f, 104f), floatArrayOf(100f, 100f), floatArrayOf(5f, 400f))
        assertEquals(400f, c.sizeAt(105f, 100f), 0f)
        assertEquals(5f, c.sizeAt(99f, 100f), 0f)
    }
}
