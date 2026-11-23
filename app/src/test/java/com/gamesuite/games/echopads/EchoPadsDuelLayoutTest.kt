package com.gamesuite.games.echopads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pad size on short landscape screens (audit F3): every duel pad hit area must stay at least 72dp. */
class EchoPadsDuelLayoutTest {
    /** The upright frame of one duel half in a window of [w] x [h] dp: the status row (52dp) is above, two halves share the rest. */
    private fun half(w: Float, h: Float): Pair<Float, Float> = w to (h - 52f) / 2f

    private fun padSide(w: Float, h: Float): Float {
        val (cw, ch) = half(w, h)
        return EchoPadGrid.duelPadSideDp(cw, ch)
    }

    @Test fun duelPadsStayAtLeast72dpOnAPhoneInLandscape() {
        assertTrue(padSide(640f, 360f) >= EchoPadGrid.MIN_TOUCH_DP)
        assertTrue(padSide(720f, 360f) >= EchoPadGrid.MIN_TOUCH_DP)
        assertTrue(padSide(800f, 360f) >= EchoPadGrid.MIN_TOUCH_DP)
    }

    @Test fun duelPadsStayAtLeast72dpOnTheFoldCoverScreenInLandscape() {
        assertTrue(padSide(880f, 340f) >= EchoPadGrid.MIN_TOUCH_DP)
        assertTrue(padSide(760f, 330f) >= EchoPadGrid.MIN_TOUCH_DP)
    }

    @Test fun duelPadsAreLargeInPortraitAndOnTablets() {
        assertTrue(padSide(360f, 780f) >= EchoPadGrid.MIN_TOUCH_DP)
        assertTrue(padSide(1280f, 800f) >= EchoPadGrid.MIN_TOUCH_DP)
    }

    @Test fun aWideShortHalfLaysTheFourPadsInOneRow() {
        assertEquals(4, EchoPadGrid.columnsFor(600f, 100f))
        assertEquals(2, EchoPadGrid.columnsFor(320f, 320f))
        assertEquals(2, EchoPadGrid.columnsFor(300f, 400f))
    }

    @Test fun theRowLayoutHasFourSeparateInOrderPadsThatHitTestToThemselves() {
        val w = 600f
        val h = 100f
        val rects = (0 until EchoPads.COUNT).map { EchoPadGrid.rect(it, w, h, 4) }
        for (i in 0 until 3) assertTrue("pad $i is left of pad ${i + 1}", rects[i][2] <= rects[i + 1][0])
        for (r in rects) {
            assertTrue(r[0] >= 0f && r[2] <= w + 0.01f && r[1] >= 0f && r[3] <= h + 0.01f)
        }
        for (i in 0 until EchoPads.COUNT) {
            val r = rects[i]
            assertEquals(i, EchoPadGrid.padAt((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, w, h, 4))
        }
    }

    @Test fun theBetterArrangementIsNeverSmallerThanTheGrid() {
        for (w in listOf(200f, 320f, 640f, 900f)) for (h in listOf(60f, 100f, 200f, 320f)) {
            assertTrue(EchoPadGrid.bestPadSide(w, h) >= EchoPadGrid.padSide(w, h) - 0.01f)
        }
    }

    @Test fun theTwoByTwoDefaultIsUnchanged() {
        val a = EchoPadGrid.rect(3, 200f, 100f)
        val b = EchoPadGrid.rect(3, 200f, 100f, 2)
        assertTrue(a.contentEquals(b))
    }
}
