package com.gamesuite.transport

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyAdmissionTest {
    @Test fun rejectsWhenLobbyClosed() {
        assertFalse(NearbyAdmission.shouldAcceptGuest(false, 0, 0, 4))
    }

    @Test fun acceptsUpToSeatCountIncludingPending() {
        assertTrue(NearbyAdmission.shouldAcceptGuest(true, 2, 1, 4))
        assertFalse(NearbyAdmission.shouldAcceptGuest(true, 3, 1, 4))
        assertFalse(NearbyAdmission.shouldAcceptGuest(true, 0, 4, 4))
        assertFalse(NearbyAdmission.shouldAcceptGuest(true, 0, 0, 0))
    }

    @Test fun codeIsShortUnambiguousAndSeedStable() {
        val c = NearbyAdmission.randomCode(Random(42))
        assertEquals(4, c.length)
        assertTrue(c.none { it in "01OI" })
        assertEquals(c, NearbyAdmission.randomCode(Random(42)))
    }

    @Test fun labelHasNoPersonalData() {
        assertEquals("GameSuite host AB23", NearbyAdmission.advertisedLabel("AB23"))
    }
}
