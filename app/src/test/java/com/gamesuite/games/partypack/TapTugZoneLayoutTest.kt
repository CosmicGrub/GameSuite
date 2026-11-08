package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTugZoneLayoutTest {
    private fun overlap(a: TapTugZone, b: TapTugZone) =
        a.left < b.right - 1e-6 && b.left < a.right - 1e-6 && a.top < b.bottom - 1e-6 && b.top < a.bottom - 1e-6

    @Test fun twoPlayersAreBottomAndTopAndTheFarOneIsTurnedAround() {
        val z = TapTugZoneLayout.forSeats(2, rotateTop = true, band = 0.12f)
        assertEquals(2, z.size)
        assertTrue(z[0].top > z[1].top)
        assertFalse(z[0].rotated); assertTrue(z[1].rotated)
        assertEquals(0, z[0].seat); assertEquals(1, z[1].seat)
        assertEquals(1f, z[0].width, 1e-6f); assertEquals(1f, z[1].width, 1e-6f)
    }

    @Test fun threeAndFourPlayersFillTheRows() {
        val three = TapTugZoneLayout.forSeats(3, true, 0.12f)
        assertEquals(1, three.count { !it.rotated }); assertEquals(2, three.count { it.rotated })
        assertEquals(0.5f, three[1].width, 1e-6f)
        val four = TapTugZoneLayout.forSeats(4, true, 0.12f)
        assertEquals(2, four.count { !it.rotated }); assertEquals(2, four.count { it.rotated })
        assertEquals(listOf(0, 1, 2, 3), four.map { it.seat })
    }

    @Test fun turningRotationOffLeavesEveryZoneUpright() {
        for (n in 2..4) assertTrue(TapTugZoneLayout.forSeats(n, rotateTop = false, band = 0.12f).none { it.rotated })
    }

    @Test fun zonesNeverOverlapEachOtherOrTheBandAndStayOnTheBoard() {
        for (n in 2..4) for (band in listOf(0f, 0.12f, 0.3f)) {
            val zones = TapTugZoneLayout.forSeats(n, true, band)
            val strip = TapTugZoneLayout.bandRect(band)
            for (a in zones) {
                assertTrue(a.left >= 0f && a.top >= 0f && a.right <= 1f && a.bottom <= 1f)
                if (band > 0f) assertFalse(overlap(a, strip))
                for (b in zones) if (a !== b) assertFalse("$a $b", overlap(a, b))
            }
        }
    }

    @Test fun teamRowsPutTheTeamsOnOppositeEdges() {
        val z = TapTugZoneLayout.rows(bottom = 1, top = 3, rotateTop = true, band = 0.2f)
        assertEquals(4, z.size)
        assertEquals(1f / 3f, z[1].width, 1e-6f)
        assertTrue(z.drop(1).all { it.rotated && it.bottom <= 0.4f + 1e-6f })
        assertEquals(1f, z[0].width, 1e-6f)
    }

    @Test fun everyZoneIsALargeTargetOnASmallPhoneInPortrait() {
        // 360 x 640 dp board; the smallest zone is a quarter-width pad of a 1 v 3 team
        for (z in TapTugZoneLayout.rows(1, 3, true, 0.2f) + TapTugZoneLayout.forSeats(4, true, 0.12f)) {
            assertTrue("${z.width * 360} wide", z.width * 360f >= 100f)
            assertTrue("${z.height * 640} tall", z.height * 640f >= 100f)
        }
    }

    @Test fun zonesMapToPixelRects() {
        val r = TapTugZoneLayout.forSeats(2, true, 0.1f)[0].toRect(400f, 800f)
        assertEquals(0f, r.left, 1e-3f); assertEquals(400f, r.right, 1e-3f); assertEquals(440f, r.top, 1e-3f); assertEquals(800f, r.bottom, 1e-3f)
    }

    @Test fun defaultBottomRowHalvesTheTable() {
        assertEquals(listOf(1, 1, 2), (2..4).map { TapTugZoneLayout.defaultBottomCount(it) })
    }
}
