package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The zone geometry: every player's controls face them, nothing overlaps, every control is a real touch target. */
class TrailSumoZoneLayoutTest {
    private fun layout(w: Float, h: Float, humans: List<Boolean>, face: Boolean = true, set: TsPadSet = TsPadSet.TRAIL_PADS, thick: Float = 120f) =
        TsZoneLayout.compute(w, h, 56f, humans, face, set, thick, 48f, 6f)

    @Test fun twoPlayersFaceEachOtherTopAndBottom() {
        val l = layout(1000f, 800f, listOf(true, true))
        assertEquals(listOf(TsSide.BOTTOM, TsSide.TOP), l.zones.map { it.side })
        val bottom = l.zones[0].screen; val top = l.zones[1].screen
        assertTrue(bottom.top > l.board.bottom - 0.01f && top.bottom < l.board.top + 0.01f)
        assertEquals(l.board.width, l.board.height, 0.01f)
    }

    @Test fun threeAndFourPlayersUseTheShortEdgesToo() {
        assertEquals(listOf(TsSide.BOTTOM, TsSide.TOP, TsSide.LEFT), layout(1000f, 800f, listOf(true, true, true)).zones.map { it.side })
        assertEquals(listOf(TsSide.BOTTOM, TsSide.TOP, TsSide.LEFT, TsSide.RIGHT), layout(1000f, 800f, listOf(true, true, true, true)).zones.map { it.side })
    }

    @Test fun leftAndRightPadsSitOnTheSideThatIsTheirPlayersLeftAndRight() {
        val l = layout(1000f, 800f, listOf(true, true, true, true))
        fun pad(seat: Int, name: String) = l.zones[seat].let { z -> z.toScreen(z.pads.first { it.id == "$seat:$name" }.local) }
        // Bottom player faces up: left pad is on the screen's left.
        assertTrue(pad(0, "L").centerX < pad(0, "R").centerX)
        // Top player faces down: their left is the screen's right.
        assertTrue(pad(1, "L").centerX > pad(1, "R").centerX)
        // Left-edge player faces right: their left is up the screen.
        assertTrue(pad(2, "L").centerY < pad(2, "R").centerY)
        // Right-edge player faces left: their left is down the screen.
        assertTrue(pad(3, "L").centerY > pad(3, "R").centerY)
    }

    @Test fun padsLieInsideTheirZoneAndNeverOverlapAnyOtherControlOrTheBoard() {
        val rnd = Random(5)
        repeat(300) {
            val w = 360f + rnd.nextInt(1400); val h = 560f + rnd.nextInt(1000)
            val n = 2 + rnd.nextInt(3)
            val humans = List(n) { rnd.nextInt(4) != 0 }
            val set = TsPadSet.entries[rnd.nextInt(TsPadSet.entries.size)]
            val face = rnd.nextBoolean()
            val l = layout(w, h, humans, face, set, thick = 100f + rnd.nextInt(60))
            val regions = l.regions()
            val screen = TsRect(0f, 0f, w, h)
            regions.forEach { r ->
                val rect = TsRect(r.left, r.top, r.right, r.bottom)
                assertTrue("inside screen $rect", rect.inside(screen))
                assertFalse("overlaps the board", rect.intersects(l.board) && rect.width > 1f && rect.height > 1f && l.board.width > 1f)
            }
            for (i in regions.indices) for (j in i + 1 until regions.size) {
                val a = regions[i]; val b = regions[j]
                assertFalse("${a.id} overlaps ${b.id}", TsRect(a.left, a.top, a.right, a.bottom).intersects(TsRect(b.left, b.top, b.right, b.bottom)))
            }
            for (z in l.zones) for (p in z.pads) assertTrue(z.toScreen(p.local).inside(z.screen))
        }
    }

    @Test fun everyControlIsAtLeast48UnitsInBothDirectionsWhenTheZoneIsThickEnough() {
        // thick 64 means margin 6 leaves a 52-unit pad: the smallest the screens ever ask for.
        for (set in TsPadSet.entries) {
            val l = TsZoneLayout.compute(800f, 1200f, 56f, listOf(true, true, true, true), true, set, 64f, 40f, 6f)
            for (r in l.regions()) assertTrue("${r.id} ${r.width}x${r.height}", r.width >= 48f && r.height >= 48f)
        }
    }

    @Test fun withFaceOffEveryHumanSharesTheBottomEdgeUpright() {
        val l = layout(900f, 700f, listOf(true, false, true), face = false)
        assertEquals(2, l.zones.size)
        assertTrue(l.zones.all { it.side == TsSide.BOTTOM && it.human })
        assertEquals(listOf(0, 2), l.zones.map { it.seat })
        assertTrue(l.zones[0].screen.right <= l.zones[1].screen.left + 0.01f)
    }

    @Test fun aComputerSeatGetsASlimStripWithNoControls() {
        val l = layout(1000f, 800f, listOf(true, false))
        val cpu = l.zones[1]
        assertFalse(cpu.human)
        assertTrue(cpu.pads.isEmpty())
        assertEquals(48f, cpu.screen.height, 0.01f)
    }

    @Test fun toLocalVectorTurnsAScreenVectorIntoTheZonesOwnAxes() {
        val l = layout(1000f, 800f, listOf(true, true, true, true), set = TsPadSet.SUMO_STICK)
        // Pushing the stick toward the middle of the board is "up" (negative local y) for every player.
        val toward = mapOf(TsSide.BOTTOM to (0f to -1f), TsSide.TOP to (0f to 1f), TsSide.LEFT to (1f to 0f), TsSide.RIGHT to (-1f to 0f))
        for (z in l.zones) {
            val (sx, sy) = toward.getValue(z.side)
            val (lx, ly) = z.toLocalVector(sx, sy)
            assertEquals(0f, lx, 1e-5f); assertEquals(-1f, ly, 1e-5f)
        }
    }
}
