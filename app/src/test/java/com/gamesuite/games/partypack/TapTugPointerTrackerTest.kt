package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-touch input model as a pure class: zone assignment, finger lifts, cancel, palms and the anti-cheese limits. */
class TapTugPointerTrackerTest {
    // two zones side by side: 0 = left half, 1 = right half (100 x 100 board)
    private val left = TapTugRect(0f, 0f, 50f, 100f)
    private val right = TapTugRect(50f, 0f, 100f, 100f)

    private fun tracker(maxContact: Float = Float.MAX_VALUE, pointerGap: Long = 90, zoneGap: Long = 40) =
        TapTugPointerTracker(listOf(left, right), maxContact, pointerGap, zoneGap, leaveSlopPx = 5f)

    @Test fun aFingerIsBoundToTheZoneItLandedIn() {
        val t = tracker()
        assertEquals(TapTugDown(TapTugContact.COUNTED, 0), t.down(1, 10f, 10f, 0f, 0))
        assertEquals(TapTugDown(TapTugContact.COUNTED, 1), t.down(2, 80f, 50f, 0f, 0))
        assertEquals(0, t.zoneOf(1)); assertEquals(1, t.zoneOf(2))
        assertEquals(1, t.fingersIn(0)); assertEquals(1, t.fingersIn(1)); assertEquals(2, t.activeCount)
    }

    @Test fun aTouchOutsideEveryLiveZoneIsIgnored() {
        val t = tracker()
        assertEquals(TapTugContact.IGNORED, t.down(1, 150f, 10f, 0f, 0).contact)
        assertEquals(0, t.activeCount)
        t.zones = listOf(left, TapTugRect.NONE) // a computer's zone takes no touches
        assertEquals(TapTugContact.IGNORED, t.down(2, 80f, 50f, 0f, 0).contact)
    }

    @Test fun oneFingerLiftingNeverDisturbsAnother() {
        val t = tracker()
        t.down(1, 10f, 10f, 0f, 0); t.down(2, 20f, 20f, 0f, 0); t.down(3, 80f, 80f, 0f, 0)
        assertEquals(TapTugRelease(2, 0), t.up(2))
        assertEquals(0, t.zoneOf(1)); assertEquals(1, t.zoneOf(3)); assertNull(t.zoneOf(2))
        assertEquals(1, t.fingersIn(0))
        assertEquals(TapTugRelease(1, 0), t.up(1))
        assertEquals(1, t.zoneOf(3))
    }

    @Test fun liftingAnUntrackedFingerDoesNothing() {
        val t = tracker()
        assertNull(t.up(9))
        assertNull(t.cancel(9))
    }

    @Test fun aFingerSlidingOutOfItsZoneIsReleasedAndNeverRebound() {
        val t = tracker()
        t.down(1, 40f, 50f, 0f, 0)
        assertNull(t.move(1, 52f, 50f)) // inside the 5 px slop
        assertEquals(TapTugRelease(1, 0), t.move(1, 70f, 50f))
        assertNull(t.zoneOf(1))
        assertNull(t.move(1, 20f, 50f)) // sliding back in does not re-bind it
        assertNull(t.up(1))
    }

    @Test fun cancelReleasesLikeALift() {
        val t = tracker()
        t.down(1, 10f, 10f, 0f, 0)
        assertEquals(TapTugRelease(1, 0), t.cancel(1))
        assertEquals(0, t.activeCount)
    }

    @Test fun cancelAllReleasesEveryFinger() {
        val t = tracker()
        t.down(1, 10f, 10f, 0f, 0); t.down(2, 90f, 10f, 0f, 0)
        assertEquals(setOf(TapTugRelease(1, 0), TapTugRelease(2, 1)), t.cancelAll().toSet())
        assertEquals(0, t.activeCount)
        assertTrue(t.cancelAll().isEmpty())
    }

    @Test fun reconcileDropsOnlyThePointersThatAreGone() {
        val t = tracker()
        t.down(1, 10f, 10f, 0f, 0); t.down(2, 90f, 10f, 0f, 0)
        assertEquals(listOf(TapTugRelease(2, 1)), t.reconcile(setOf(1)))
        assertEquals(0, t.zoneOf(1))
    }

    @Test fun aSecondDownForAPointerThatIsAlreadyBoundIsIgnored() {
        val t = tracker()
        t.down(1, 10f, 10f, 0f, 0)
        assertEquals(TapTugContact.IGNORED, t.down(1, 60f, 10f, 0f, 500).contact)
        assertEquals(0, t.zoneOf(1))
    }

    @Test fun aPalmSizedContactIsIgnoredForGood() {
        val t = tracker(maxContact = 100f)
        assertEquals(TapTugContact.PALM, t.down(1, 10f, 10f, 180f, 0).contact)
        assertEquals(0, t.activeCount)
        assertNull(t.move(1, 10f, 12f)); assertNull(t.up(1))
        assertEquals(TapTugContact.COUNTED, t.down(2, 10f, 10f, 60f, 0).contact) // a real finger beside it still counts
    }

    @Test fun oneFingerCannotTapFasterThanTheHumanLimit() {
        val t = tracker(pointerGap = 90)
        assertEquals(TapTugContact.COUNTED, t.down(1, 10f, 10f, 0f, 1000).contact)
        t.up(1)
        assertEquals(TapTugContact.TOO_FAST, t.down(1, 10f, 10f, 0f, 1050).contact) // 50 ms later: 20 taps a second
        t.up(1)
        assertEquals(TapTugContact.COUNTED, t.down(1, 10f, 10f, 0f, 1100).contact) // 100 ms after the last COUNTED tap
    }

    @Test fun aTooFastContactStillBindsSoItsLiftIsTracked() {
        val t = tracker(pointerGap = 90)
        t.down(1, 10f, 10f, 0f, 1000); t.up(1)
        val d = t.down(1, 10f, 10f, 0f, 1020)
        assertEquals(TapTugContact.TOO_FAST, d.contact); assertEquals(0, d.zone)
        assertNotNull(t.up(1))
    }

    @Test fun aWholeZoneIsLimitedToo() {
        val t = tracker(zoneGap = 40)
        assertEquals(TapTugContact.COUNTED, t.down(1, 10f, 10f, 0f, 1000).contact)
        assertEquals(TapTugContact.TOO_FAST, t.down(2, 20f, 10f, 0f, 1020).contact) // a second finger, 20 ms later
        assertEquals(TapTugContact.COUNTED, t.down(3, 30f, 10f, 0f, 1045).contact)
        // the other zone is unaffected
        assertEquals(TapTugContact.COUNTED, t.down(4, 80f, 10f, 0f, 1046).contact)
    }

    @Test fun manyFingersAtOnceNeverExceedTheZoneRate() {
        val t = tracker(pointerGap = 90, zoneGap = 40)
        var counted = 0
        var now = 0L
        // twelve fingers tapping as fast as they can, one touch every 10 ms for a second
        for (i in 0 until 100) {
            val id = (i % 12).toLong()
            t.up(id)
            if (t.down(id, 10f, 10f, 0f, now).contact == TapTugContact.COUNTED) counted++
            now += 10
        }
        assertTrue("counted $counted", counted <= 25)
    }

    @Test fun maxFingerContactScalesWithDensity() {
        assertEquals(22f / 25.4f * 420f, TapTugPointerTracker.maxFingerContactPx(420f), 0.01f)
        assertFalse(TapTugPointerTracker.maxFingerContactPx(160f) > TapTugPointerTracker.maxFingerContactPx(420f))
    }
}
