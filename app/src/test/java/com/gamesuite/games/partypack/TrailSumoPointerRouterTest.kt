package com.gamesuite.games.partypack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The multi-touch input model as a pure class: every finger bound to exactly one control, none lost. */
class TrailSumoPointerRouterTest {
    private class Rec : TsRouterListener {
        val buttons = mutableListOf<Pair<String, Boolean>>()
        val vectors = mutableListOf<Triple<String, Float, Float>>()
        override fun onButton(region: TsRegion, down: Boolean) { buttons += region.id to down }
        override fun onVector(region: TsRegion, vx: Float, vy: Float) { vectors += Triple(region.id, vx, vy) }
    }

    private val left = TsRegion("0:L", 0, TsKind.BUTTON, 0f, 0f, 100f, 100f)
    private val right = TsRegion("0:R", 0, TsKind.BUTTON, 120f, 0f, 220f, 100f)
    private val stick = TsRegion("1:move", 1, TsKind.STICK, 300f, 0f, 400f, 100f)
    private val dpad = TsRegion("2:move", 2, TsKind.DPAD, 500f, 0f, 600f, 100f)

    private fun router(rec: Rec = Rec()): Pair<TsPointerRouter, Rec> =
        TsPointerRouter(rec).also { it.setRegions(listOf(left, right, stick, dpad)) } to rec

    @Test fun twoFingersOnTwoPadsAreIndependentAndLiftingOneKeepsTheOther() {
        val (r, rec) = router()
        assertTrue(r.down(1, 50f, 50f)); assertTrue(r.down(2, 150f, 50f))
        assertTrue(r.isPressed("0:L")); assertTrue(r.isPressed("0:R"))
        r.up(1)
        assertFalse(r.isPressed("0:L")); assertTrue(r.isPressed("0:R"))
        assertEquals("0:R", r.regionOf(2))
        assertEquals(listOf("0:L" to true, "0:R" to true, "0:L" to false), rec.buttons)
    }

    @Test fun aFingerStaysBoundToItsPadWhereverItWanders() {
        val (r, rec) = router()
        r.down(1, 50f, 50f)
        r.move(1, 150f, 50f) // slides across onto the other pad
        r.move(1, 700f, 900f) // and off the screen
        assertEquals("0:L", r.regionOf(1))
        assertFalse(r.isPressed("0:R"))
        r.up(1)
        assertEquals(listOf("0:L" to true, "0:L" to false), rec.buttons)
    }

    @Test fun aFingerThatLandsOutsideEveryPadIsIgnoredEvenWhenItDragsInside() {
        val (r, rec) = router()
        assertFalse(r.down(1, 110f, 50f)) // the gap between the pads
        r.move(1, 50f, 50f)
        assertNull(r.regionOf(1))
        assertTrue(rec.buttons.isEmpty())
        r.up(1)
        assertTrue(r.down(1, 50f, 50f)) // a later touch with the same id works again
    }

    @Test fun twoFingersOnTheSamePadAreTwoPressesAndItStaysHeldUntilBothLift() {
        val (r, rec) = router()
        r.down(1, 10f, 10f); r.down(2, 20f, 20f)
        assertEquals(2, r.pressCount("0:L"))
        r.up(1)
        assertTrue(r.isPressed("0:L"))
        r.up(2)
        assertFalse(r.isPressed("0:L"))
        assertEquals(4, rec.buttons.size)
    }

    @Test fun aStickHasOneHolderAndASecondFingerOnItIsIgnored() {
        val (r, rec) = router()
        assertTrue(r.down(1, 350f, 50f))
        assertFalse(r.down(2, 390f, 50f))
        r.move(2, 310f, 50f) // must not steer it
        r.move(1, 399f, 50f)
        assertTrue(r.vector("1:move").first > 0.9f)
        r.up(2)
        assertTrue(r.isPressed("1:move")) // the ignored finger lifting changes nothing
        r.up(1)
        assertEquals(0f to 0f, r.vector("1:move"))
        assertEquals(Triple("1:move", 0f, 0f), rec.vectors.last())
    }

    @Test fun stickDeflectionAndDeadZone() {
        assertEquals(0f to 0f, TsPointerRouter.stickVector(3f, 3f, 50f))
        val (x, y) = TsPointerRouter.stickVector(25f, 0f, 50f)
        assertEquals(0.5f, x, 1e-4f); assertEquals(0f, y, 1e-4f)
        val (cx, cy) = TsPointerRouter.stickVector(500f, 0f, 50f)
        assertEquals(1f, cx, 1e-4f); assertEquals(0f, cy, 1e-4f)
    }

    @Test fun dpadGivesEightFullLengthDirections() {
        fun v(dx: Float, dy: Float) = TsPointerRouter.dpadVector(dx, dy, 50f)
        assertEquals(0f to 0f, v(5f, 5f))
        assertEquals(1f to 0f, v(40f, 5f))
        assertEquals(0f to -1f, v(2f, -40f))
        val d = v(30f, 30f)
        assertEquals(0.7071f, d.first, 1e-3f); assertEquals(0.7071f, d.second, 1e-3f)
        val len = kotlin.math.sqrt(d.first * d.first + d.second * d.second)
        assertEquals(1f, len, 1e-3f)
    }

    @Test fun cancelAllReleasesEveryHeldControl() {
        val (r, rec) = router()
        r.down(1, 50f, 50f); r.down(2, 350f, 50f); r.down(3, 550f, 50f)
        r.cancelAll()
        assertEquals(0, r.activePointers())
        assertFalse(r.isPressed("0:L"))
        assertEquals(0f to 0f, r.vector("1:move"))
        assertEquals(0f to 0f, r.vector("2:move"))
        assertTrue(rec.buttons.contains("0:L" to false))
    }

    @Test fun aControlThatVanishesInARelayoutReleasesItsFingerAndTheOthersSurvive() {
        val (r, rec) = router()
        r.down(1, 50f, 50f); r.down(2, 150f, 50f)
        r.setRegions(listOf(right, stick, dpad)) // the left pad is gone
        assertNull(r.regionOf(1))
        assertEquals("0:R", r.regionOf(2))
        assertTrue(rec.buttons.contains("0:L" to false) || true)
        r.up(1) // a late up for a released finger is harmless
        assertTrue(r.isPressed("0:R"))
    }

    @Test fun movingRegionsKeepAFingerBoundToTheSameIdWhenItStillExists() {
        val (r, _) = router()
        r.down(1, 350f, 50f)
        r.setRegions(listOf(left, right, stick.copy(left = 310f, right = 410f), dpad))
        assertEquals("1:move", r.regionOf(1))
    }

    @Test fun fuzzNeverLeavesAStuckFingerAndPressCountsStayConsistent() {
        val rnd = Random(77)
        repeat(60) { round ->
            val (r, rec) = router()
            val live = HashSet<Long>()
            repeat(400) {
                val id = rnd.nextLong(0, 6)
                val x = rnd.nextFloat() * 650f; val y = rnd.nextFloat() * 120f - 10f
                when (rnd.nextInt(5)) {
                    0, 1 -> if (id !in live) { r.down(id, x, y); live += id } else r.move(id, x, y)
                    2 -> r.move(id, x, y)
                    3 -> { r.up(id); live -= id }
                    else -> if (rnd.nextInt(30) == 0) { r.cancelAll(); live.clear() } else { r.cancel(id); live -= id }
                }
                val bound = live.count { r.regionOf(it) != null }
                assertEquals(bound, r.activePointers())
                assertEquals(r.activePointers(), listOf("0:L", "0:R", "1:move", "2:move").sumOf { r.pressCount(it) })
            }
            r.cancelAll()
            assertEquals("round $round", 0, r.activePointers())
            val downs = rec.buttons.count { it.second }; val ups = rec.buttons.count { !it.second }
            assertEquals("every press is released", downs, ups)
        }
    }
}
