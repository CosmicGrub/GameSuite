package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-pointer model as a pure class: every finger bound to exactly one seat, nothing lost when another lifts. */
class PaddleDuelInputTest {
    // two seats on a 1000 x 1500 portrait view: bottom half = seat 0, top half = seat 1
    private fun portrait(alive: (Int) -> Boolean = { true }) = PdViewTransform(2, rotated = false, viewW = 1000.0, viewH = 1500.0, alive = alive)
    private fun router(t: PdViewTransform) = PdPointerRouter { x, y -> t.zoneAt(x, y) }

    @Test fun twoFingersInTwoZonesSteerTwoSeatsAtOnce() {
        val t = portrait(); val r = router(t)
        assertEquals(0, r.down(1, 300.0, 1200.0))
        assertEquals(1, r.down(2, 700.0, 300.0))
        assertEquals(0, r.move(1, 320.0, 1190.0)); assertEquals(1, r.move(2, 650.0, 310.0))
        assertEquals(2, r.activePointers)
    }

    @Test fun aFingerStaysWithItsSeatEvenAfterSlidingIntoTheOtherZone() {
        val t = portrait(); val r = router(t)
        r.down(1, 300.0, 1200.0)
        assertEquals(0, r.move(1, 300.0, 100.0)) // far into seat 1's half
        assertEquals(0, r.boundSeat(1))
    }

    @Test fun liftingOneFingerNeverAffectsTheOther() {
        val t = portrait(); val r = router(t)
        r.down(1, 300.0, 1200.0); r.down(2, 700.0, 300.0)
        assertEquals(0, r.up(1))
        assertEquals(1, r.move(2, 710.0, 300.0))
        assertEquals(2L, r.pointerFor(1)); assertNull(r.pointerFor(0))
    }

    @Test fun aSecondFingerInASteeredZoneIsIgnoredUntilTheFirstLifts() {
        val t = portrait(); val r = router(t)
        r.down(1, 300.0, 1200.0)
        assertNull(r.down(2, 600.0, 1300.0))
        assertNull(r.move(2, 610.0, 1300.0)) // still steered by finger 1
        r.up(1)
        assertEquals(0, r.move(2, 620.0, 1300.0)) // the spare finger takes over on its next move
    }

    @Test fun cancelFreesOnlyThatPointerAndCancelAllFreesEverything() {
        val t = portrait(); val r = router(t)
        r.down(1, 300.0, 1200.0); r.down(2, 700.0, 300.0)
        assertEquals(1, r.up(2)) // a system cancel of one pointer is an up for that pointer
        assertEquals(1, r.activePointers)
        r.down(3, 600.0, 200.0)
        assertEquals(setOf(0, 1), r.cancelAll().toSet())
        assertEquals(0, r.activePointers)
        assertNull(r.up(99)) // unknown ids are harmless
    }

    @Test fun anEliminatedSeatAcceptsNoFingerAndItsZoneGoesToTheNearestLiveSeat() {
        val t = PdViewTransform(4, rotated = false, viewW = 1000.0, viewH = 1000.0, alive = { it != 1 })
        val r = router(t)
        // the top edge belongs to seat 1 (TOP); with it out, a touch near the top goes to a side seat
        val seat = r.down(1, 500.0, 20.0)
        assertTrue(seat != null && seat != 1)
    }

    @Test fun fourSeatZonesAreTheFourTrianglesOfTheArena() {
        val t = PdViewTransform(4, rotated = false, viewW = 1000.0, viewH = 1000.0)
        assertEquals(0, t.zoneAt(500.0, 900.0)); assertEquals(1, t.zoneAt(500.0, 100.0))
        assertEquals(2, t.zoneAt(100.0, 500.0)); assertEquals(3, t.zoneAt(900.0, 500.0))
    }

    @Test fun rotatedTwoPlayerViewPutsSeatZeroOnTheLeftAndMapsAxesBack() {
        val t = PdViewTransform(2, rotated = true, viewW = 1500.0, viewH = 1000.0)
        assertEquals(0, t.zoneAt(100.0, 500.0)); assertEquals(1, t.zoneAt(1400.0, 500.0))
        assertEquals(PpEdge.LEFT, t.screenEdge(PpEdge.BOTTOM)); assertEquals(PpEdge.RIGHT, t.screenEdge(PpEdge.TOP))
        // top of the screen is logical x = 0, bottom is logical x = 1
        assertEquals(0.0, t.alongFor(0, 100.0, 0.0), 1e-9)
        assertEquals(1.0, t.alongFor(0, 100.0, 1000.0), 1e-9)
        // round trip
        val lx = 0.3; val ly = 0.9
        val px = t.toScreenX(lx, ly); val py = t.toScreenY(lx, ly)
        assertEquals(lx, t.toLogicalX(px, py), 1e-9); assertEquals(ly, t.toLogicalY(px, py), 1e-9)
    }

    @Test fun alongDirectionsOnScreen() {
        assertEquals(1 to 0, portrait().alongPlus(0))
        assertEquals(0 to 1, PdViewTransform(4, false, 900.0, 900.0).alongPlus(2))
        assertEquals(0 to 1, PdViewTransform(2, true, 1500.0, 1000.0).alongPlus(1))
    }

    @Test fun portraitMappingRoundTripsAndScalesToFit() {
        val t = PdViewTransform(2, rotated = false, viewW = 900.0, viewH = 900.0, margin = 10.0)
        assertTrue(t.scale * 1.5 <= 900.0 - 20.0 + 1e-9)
        val px = t.toScreenX(0.25, 1.1); val py = t.toScreenY(0.25, 1.1)
        assertEquals(0.25, t.toLogicalX(px, py), 1e-9); assertEquals(1.1, t.toLogicalY(px, py), 1e-9)
    }

    @Test fun seatLayoutsFaceThePlayersAroundTheDevice() {
        assertEquals(listOf(PpEdge.BOTTOM, PpEdge.TOP), PpSeatLayout.edges(2))
        assertEquals(3, PpSeatLayout.edges(3).size); assertEquals(4, PpSeatLayout.edges(4).distinct().size)
        assertEquals(0f, PpSeatLayout.facingDegrees(PpEdge.BOTTOM)); assertEquals(180f, PpSeatLayout.facingDegrees(PpEdge.TOP))
        assertEquals(setOf(0, 1, 2, 3), PpEdge.entries.map { PpSeatLayout.quarterTurns(it) }.toSet())
    }
}
