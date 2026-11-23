package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Zone geometry and the per-pointer multi-touch model, as pure classes (no Compose, no device). */
class ReactionWhackZonesTest {
    private val w = 1000f
    private val h = 1600f

    private fun layout(n: Int, orient: Boolean = true, edge: Boolean = false) = ReactionWhackZones.layout(n, w, h, orient, edge)

    @Test fun everySeatCountGetsExactlyThatManyZonesWithDistinctSeats() {
        for (edge in listOf(false, true)) for (n in 2..4) {
            val l = layout(n, edge = edge)
            assertEquals(n, l.zones.size)
            assertEquals((0 until n).toList(), l.zones.map { it.seat }.sorted())
        }
    }

    @Test fun outOfRangePlayerCountsAreClampedNotCrashed() {
        assertEquals(2, ReactionWhackZones.layout(1, w, h, true, false).zones.size)
        assertEquals(4, ReactionWhackZones.layout(9, w, h, true, false).zones.size)
    }

    @Test fun tiledLayoutsCoverTheWholeAreaWithoutOverlap() {
        for (n in 2..4) {
            val l = layout(n)
            val area = l.zones.sumOf { (it.width * it.height).toDouble() }
            assertEquals(w * h.toDouble(), area, 1.0)
            for (a in l.zones) for (b in l.zones) if (a.seat < b.seat) {
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertTrue("zones ${a.seat} and ${b.seat} overlap", overlapW <= 0.001f || overlapH <= 0.001f)
            }
        }
    }

    @Test fun edgeLayoutZonesNeverOverlapAndLeaveARoomForTheScoreboard() {
        for (n in 3..4) {
            val l = layout(n, edge = true)
            for (a in l.zones) for (b in l.zones) if (a.seat < b.seat) {
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertTrue(overlapW <= 0.001f || overlapH <= 0.001f)
            }
            val hub = l.hub!!
            assertTrue(hub[2] > hub[0] && hub[3] > hub[1])
            for (z in l.zones) assertTrue(z.left >= 0f && z.top >= 0f && z.right <= w + 0.01f && z.bottom <= h + 0.01f)
        }
    }

    @Test fun twoPlayersFaceEachOtherAcrossTheDevice() {
        val l = layout(2)
        val bottom = l.zones.first { it.seat == 0 }
        val top = l.zones.first { it.seat == 1 }
        assertEquals(0, bottom.rotation); assertEquals(180, top.rotation)
        assertTrue(top.bottom <= bottom.top + 0.01f)
    }

    @Test fun edgeSeatsFaceInwardsAndTurningOrientationOffKeepsEveryoneUpright() {
        val l = layout(4, edge = true)
        assertEquals(listOf(0, 180, 90, 270), (0..3).map { s -> l.zones.first { it.seat == s }.rotation })
        for (n in 2..4) for (edge in listOf(false, true)) {
            assertTrue(layout(n, orient = false, edge = edge).zones.all { it.rotation == 0 })
        }
    }

    @Test fun gridLayoutTurnsTheTopRowToFaceTheFarEdge() {
        val l = layout(4)
        assertTrue(l.zones.filter { it.top < h / 2 }.all { it.rotation == 180 })
        assertTrue(l.zones.filter { it.top >= h / 2 }.all { it.rotation == 0 })
    }

    @Test fun zoneAtFindsTheZoneUnderEachCentreAndNothingOutside() {
        for (n in 2..4) for (edge in listOf(false, true)) {
            val l = layout(n, edge = edge)
            for (z in l.zones) assertEquals(z.seat, ReactionWhackZones.zoneAt(l.zones, (z.left + z.right) / 2, (z.top + z.bottom) / 2))
            assertEquals(-1, ReactionWhackZones.zoneAt(l.zones, -5f, 10f))
            assertEquals(-1, ReactionWhackZones.zoneAt(l.zones, w + 5f, 10f))
        }
        // The hub of an edge layout belongs to nobody.
        val e = layout(4, edge = true)
        val hub = e.hub!!
        assertEquals(-1, ReactionWhackZones.zoneAt(e.zones, (hub[0] + hub[2]) / 2, (hub[1] + hub[3]) / 2))
    }

    private fun zone(rotation: Int, width: Float = 300f, height: Float = 100f) =
        ReactionWhackZone(0, 50f, 20f, 50f + width, 20f + height, rotation)

    @Test fun contentFrameSizeSwapsForAQuarterTurn() {
        assertEquals(300f, zone(0).contentWidth, 0f); assertEquals(100f, zone(0).contentHeight, 0f)
        assertEquals(100f, zone(90).contentWidth, 0f); assertEquals(300f, zone(90).contentHeight, 0f)
        assertEquals(300f, zone(180).contentWidth, 0f)
        assertEquals(100f, zone(270).contentWidth, 0f)
    }

    @Test fun toContentMapsTheCornersOfAnyRotationOntoTheContentFrame() {
        // Content frame corners (cw x ch). Rotating the content clockwise sends its top-left to the screen's top-right for 90.
        val z0 = zone(0); val z90 = zone(90); val z180 = zone(180); val z270 = zone(270)
        assertEquals(0f to 0f, ReactionWhackZones.toContent(z0, 50f, 20f))
        assertEquals(300f to 100f, ReactionWhackZones.toContent(z180, 50f, 20f))
        assertEquals(0f to 300f, ReactionWhackZones.toContent(z90, 50f, 20f))     // screen top-left = content bottom-left
        assertEquals(100f to 0f, ReactionWhackZones.toContent(z270, 50f, 20f))     // screen top-left = content top-right
        // Screen bottom-right.
        assertEquals(300f to 100f, ReactionWhackZones.toContent(z0, 350f, 120f))
        assertEquals(0f to 0f, ReactionWhackZones.toContent(z180, 350f, 120f))
        assertEquals(100f to 0f, ReactionWhackZones.toContent(z90, 350f, 120f))
        assertEquals(0f to 300f, ReactionWhackZones.toContent(z270, 350f, 120f))
    }

    @Test fun toContentStaysInsideTheContentFrameForEveryPointOfEveryRotation() {
        for (rot in listOf(0, 90, 180, 270)) {
            val z = zone(rot)
            var x = z.left
            while (x < z.right) {
                var y = z.top
                while (y < z.bottom) {
                    val (cx, cy) = ReactionWhackZones.toContent(z, x, y)
                    assertTrue("rot $rot ($x,$y) -> ($cx,$cy)", cx >= -0.01f && cx <= z.contentWidth + 0.01f && cy >= -0.01f && cy <= z.contentHeight + 0.01f)
                    y += 7f
                }
                x += 11f
            }
        }
    }

    @Test fun movingRightOnTheScreenIsMovingTowardsThePlayersLeftWhenTheContentIsTurnedAround() {
        val z = zone(180)
        val a = ReactionWhackZones.toContent(z, 100f, 60f)
        val b = ReactionWhackZones.toContent(z, 200f, 60f)
        assertTrue(b.first < a.first)
        assertEquals(a.second, b.second, 0.001f)
    }

    @Test fun theLargestGridThatKeepsEveryCellBigEnoughIsChosen() {
        // 600 x 700 content frame, 100 reserved, 150px minimum cell: 4x4 gives min(150, 150) = 150, fits.
        assertEquals(4, ReactionWhackZones.maxGridThatFits(600f, 700f, 4, 150f, 100f))
        // 560 wide gives cells of 140: 4x4 does not fit, 3x3 does.
        assertEquals(3, ReactionWhackZones.maxGridThatFits(560f, 700f, 4, 150f, 100f))
        // Never below 3 even when nothing fits.
        assertEquals(3, ReactionWhackZones.maxGridThatFits(100f, 100f, 4, 150f, 10f))
        assertEquals(3, ReactionWhackZones.maxGridThatFits(1000f, 1000f, 3, 150f, 0f))
        assertEquals(0f, ReactionWhackZones.cellSize(100f, 50f, 3, 80f), 0f)
        assertTrue(abs(ReactionWhackZones.cellSize(300f, 400f, 3, 100f) - 100f) < 0.001f)
    }

    // ---- the pointer map ----

    private fun map(n: Int = 4, edge: Boolean = false): Pair<ReactionWhackPointerMap, ReactionWhackLayout> {
        val l = layout(n, edge = edge)
        return ReactionWhackPointerMap { x, y -> ReactionWhackZones.zoneAt(l.zones, x, y) } to l
    }

    private fun centre(l: ReactionWhackLayout, seat: Int): Pair<Float, Float> {
        val z = l.zones.first { it.seat == seat }
        return (z.left + z.right) / 2 to (z.top + z.bottom) / 2
    }

    @Test fun eachFingerBindsToTheZoneItWentDownIn() {
        val (m, l) = map()
        for (s in 0..3) {
            val (x, y) = centre(l, s)
            assertEquals(s, m.down(100L + s, x, y))
        }
        assertEquals(4, m.activePointers)
        for (s in 0..3) assertEquals(s, m.zoneOf(100L + s))
        for (s in 0..3) assertEquals(1, m.pointersIn(s))
    }

    @Test fun aFingerThatDriftsIntoAnotherZoneStaysItsOriginalZonesFinger() {
        val (m, l) = map()
        val (x0, y0) = centre(l, 0)
        val (x1, y1) = centre(l, 1)
        m.down(7L, x0, y0)
        assertTrue(m.stillInside(7L, x0, y0))
        assertTrue(!m.stillInside(7L, x1, y1))
        assertEquals(0, m.zoneOf(7L))
        assertEquals(0, m.up(7L))
    }

    @Test fun oneFingerLiftingNeverDisturbsTheOthers() {
        val (m, l) = map()
        val ids = (0..3).associateWith { 10L + it }
        for (s in 0..3) { val (x, y) = centre(l, s); m.down(ids[s]!!, x, y) }
        assertEquals(2, m.up(ids[2]!!))
        assertEquals(3, m.activePointers)
        for (s in listOf(0, 1, 3)) assertEquals(s, m.zoneOf(ids[s]!!))
        assertEquals(-1, m.zoneOf(ids[2]!!))
        // Lifting a finger that is already gone is harmless.
        assertEquals(-1, m.up(ids[2]!!))
        assertEquals(3, m.activePointers)
    }

    @Test fun twoFingersInOneZoneAreTrackedSeparately() {
        val (m, l) = map()
        val (x, y) = centre(l, 1)
        m.down(1L, x, y); m.down(2L, x + 3, y + 3)
        assertEquals(2, m.pointersIn(1))
        m.up(1L)
        assertEquals(1, m.pointersIn(1))
        assertEquals(1, m.zoneOf(2L))
    }

    @Test fun aFingerThatLandsOutsideEveryZoneIsNeverBound() {
        val (m, l) = map(4, edge = true)
        val hub = l.hub!!
        assertEquals(-1, m.down(5L, (hub[0] + hub[2]) / 2, (hub[1] + hub[3]) / 2))
        assertEquals(0, m.activePointers)
        assertEquals(-1, m.zoneOf(5L))
    }

    @Test fun aRepeatedDownForAKnownIdRebindsInsteadOfLeaking() {
        val (m, l) = map()
        val (x0, y0) = centre(l, 0)
        val (x3, y3) = centre(l, 3)
        m.down(9L, x0, y0)
        m.down(9L, x3, y3)
        assertEquals(1, m.activePointers)
        assertEquals(3, m.zoneOf(9L))
    }

    @Test fun cancelForgetsOnlyThatFinger() {
        val (m, l) = map()
        val (xa, ya) = centre(l, 0)
        val (xb, yb) = centre(l, 2)
        m.down(1L, xa, ya); m.down(2L, xb, yb)
        assertEquals(0, m.cancel(1L))
        assertEquals(2, m.zoneOf(2L))
    }

    @Test fun reconcileDropsFingersTheSystemNoLongerReportsAndReturnsTheirZones() {
        val (m, l) = map()
        for (s in 0..3) { val (x, y) = centre(l, s); m.down(20L + s, x, y) }
        val lost = m.reconcile(listOf(20L, 22L))
        assertEquals(listOf(1, 3), lost.sorted())
        assertEquals(2, m.activePointers)
        assertEquals(emptyList<Int>(), m.reconcile(listOf(20L, 22L, 99L)))
        m.clear()
        assertEquals(0, m.activePointers)
    }

    @Test fun tenSimultaneousFingersOverFourZonesAreAllKept() {
        val (m, l) = map()
        for (i in 0 until 10) { val (x, y) = centre(l, i % 4); m.down(i.toLong(), x + i, y + i) }
        assertEquals(10, m.activePointers)
        assertEquals(3, m.pointersIn(0)); assertEquals(3, m.pointersIn(1)); assertEquals(2, m.pointersIn(2)); assertEquals(2, m.pointersIn(3))
        for (i in 0 until 10 step 2) m.up(i.toLong())
        assertEquals(5, m.activePointers)
        for (i in 1 until 10 step 2) assertEquals(i % 4, m.zoneOf(i.toLong()))
    }
}
