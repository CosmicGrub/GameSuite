package com.gamesuite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape matrix for [solitaireFitLayout], the pure sizing function behind SolitaireScreen. The
 * fixture shapes are the usable board slot (window minus status row, controls and padding), in dp.
 */
class SolitaireLayoutTest {

    private val freshDeal = List(7) { SolitaireColumnShape(faceDown = it, faceUp = 1) }
    private val eps = 0.01f

    /** (width, height) of a board slot on: Fold cover, small phone, regular phone, Fold inner, tablet, wide desktop. */
    private val shapes = listOf(
        296f to 560f,
        320f to 440f,
        387f to 700f,
        673f to 640f,
        1004f to 600f,
        1256f to 640f
    )

    @Test
    fun `seven columns always fit across the slot when the slot is wide enough`() {
        for ((w, h) in shapes) {
            val fit = solitaireFitLayout(w, h, freshDeal, cardScale = 1f)
            assertFalse("$w x $h should not pan sideways", fit.scrollsHorizontally)
            assertTrue("$w x $h: board ${fit.boardWidthDp} exceeds the slot", fit.boardWidthDp <= w + eps)
            assertEquals(fit.cardWidthDp * 1.44f, fit.cardHeightDp, eps)
        }
    }

    @Test
    fun `a window too narrow for the minimum card pans sideways instead of shrinking further`() {
        val fit = solitaireFitLayout(200f, 600f, freshDeal, cardScale = 1f)
        assertTrue(fit.scrollsHorizontally)
        assertEquals(SOLITAIRE_MIN_CARD_WIDTH_DP, fit.cardWidthDp, eps)
    }

    @Test
    fun `cards do not sprawl on a wide window and follow the card size setting`() {
        val normal = solitaireFitLayout(1256f, 800f, freshDeal, cardScale = 1f)
        assertTrue(normal.cardWidthDp <= SOLITAIRE_MAX_CARD_WIDTH_DP + eps)
        val small = solitaireFitLayout(1256f, 800f, freshDeal, cardScale = 0.65f)
        assertTrue(small.cardWidthDp <= SOLITAIRE_MAX_CARD_WIDTH_DP * 0.65f + eps)
        assertTrue(small.cardWidthDp < normal.cardWidthDp)
    }

    @Test
    fun `a wider slot never makes the cards smaller`() {
        var previous = 0f
        for (w in listOf(280f, 320f, 400f, 600f, 900f, 1300f)) {
            val width = solitaireFitLayout(w, 700f, freshDeal, cardScale = 1f).cardWidthDp
            assertTrue("width $w gave $width after $previous", width >= previous - eps)
            previous = width
        }
    }

    @Test
    fun `a fresh deal needs no vertical scroll on a phone and keeps the full steps`() {
        val fit = solitaireFitLayout(387f, 700f, freshDeal, cardScale = 1f)
        assertFalse(fit.needsVerticalScroll)
        assertEquals(fit.cardHeightDp * SOLITAIRE_DOWN_STEP_FRACTION, fit.downStepDp, eps)
        assertEquals(fit.cardHeightDp * SOLITAIRE_UP_STEP_FRACTION, fit.upStepDp, eps)
    }

    @Test
    fun `a tall column compresses its steps to the floor and then scrolls`() {
        val tall = freshDeal.toMutableList().also { it[6] = SolitaireColumnShape(faceDown = 6, faceUp = 19) }
        val fit = solitaireFitLayout(400f, 500f, tall, cardScale = 1f)
        assertEquals(fit.cardHeightDp * SOLITAIRE_UP_STEP_FRACTION * SOLITAIRE_MIN_STEP_FACTOR, fit.upStepDp, eps)
        assertTrue(fit.needsVerticalScroll)
    }

    @Test
    fun `a moderately tall column compresses its steps just enough to fit`() {
        val medium = freshDeal.toMutableList().also { it[3] = SolitaireColumnShape(faceDown = 3, faceUp = 9) }
        val fit = solitaireFitLayout(411f, 560f, medium, cardScale = 1f)
        if (!fit.needsVerticalScroll) {
            assertTrue(fit.cardHeightDp + SOLITAIRE_SECTION_GAP_DP + fit.tallestColumnDp <= 560f + 0.5f)
        }
        assertTrue(fit.upStepDp >= fit.cardHeightDp * SOLITAIRE_UP_STEP_FRACTION * SOLITAIRE_MIN_STEP_FACTOR - eps)
    }

    @Test
    fun `column height counts a lone card, an empty column and a mixed run`() {
        assertEquals(100f, solitaireColumnHeightDp(100f, 14f, 30f, 0, 0), eps)
        assertEquals(100f, solitaireColumnHeightDp(100f, 14f, 30f, 0, 1), eps)
        // 2 face-down then 3 face-up: the last card sits 2*14 + 2*30 below the top.
        assertEquals(100f + 28f + 60f, solitaireColumnHeightDp(100f, 14f, 30f, 2, 3), eps)
    }

    @Test
    fun `degenerate sizes never crash or go negative`() {
        for ((w, h) in listOf(0f to 0f, 10f to 10f, -5f to -5f, 5000f to 5000f)) {
            val fit = solitaireFitLayout(w, h, freshDeal, cardScale = 1f)
            assertTrue(fit.cardWidthDp >= SOLITAIRE_MIN_CARD_WIDTH_DP - eps)
            assertTrue(fit.downStepDp >= 0f && fit.upStepDp >= 0f)
        }
        // No columns at all.
        val empty = solitaireFitLayout(400f, 600f, emptyList(), cardScale = 1f)
        assertEquals(empty.cardHeightDp, empty.tallestColumnDp, eps)
    }
}
