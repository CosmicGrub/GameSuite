package com.gamesuite.games.drawguess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawGuessBoardTest {
    private fun stroke(board: DrawingBoard, vararg pts: Float, color: Int = 0, size: Int = 1, eraser: Boolean = false): Boolean {
        if (!board.begin(color, size, eraser, pts[0], pts[1])) return false
        var i = 2
        while (i + 1 < pts.size) { board.extend(pts[i], pts[i + 1]); i += 2 }
        return board.end()
    }

    @Test fun quantiseNormalisesAndClamps() {
        assertEquals(0, DrawLimits.quantise(-3f))
        assertEquals(1000, DrawLimits.quantise(7f))
        assertEquals(500, DrawLimits.quantise(0.5f))
        assertEquals(750, DrawLimits.quantiseY(1f))
        assertEquals(0.25f, DrawLimits.dequantise(250), 1e-6f)
        assertEquals(0.5f, DrawLimits.normalise(200f, 400f), 1e-6f)
        assertEquals(1f, DrawLimits.normalise(900f, 400f), 1e-6f)
        assertEquals(0f, DrawLimits.normalise(5f, 0f), 1e-6f)
    }

    @Test fun aStrokeIsStoredAsNormalisedIntegerPoints() {
        val b = DrawingBoard()
        assertTrue(stroke(b, 0.1f, 0.1f, 0.2f, 0.2f, 0.5f, 0.3f, color = 2, size = 0))
        val s = b.strokes.single()
        assertEquals(listOf(100, 100, 200, 200, 500, 300), s.xy.toList())
        assertEquals(2, s.color); assertEquals(0, s.size); assertFalse(s.eraser)
        assertEquals(3, b.totalPoints)
    }

    @Test fun pointsTooCloseToTheLastAreDropped() {
        val b = DrawingBoard()
        b.begin(0, 1, false, 0.1f, 0.1f)
        assertFalse(b.extend(0.1005f, 0.1005f))   // 1 + 1 = 2 units, under the 3-unit step
        assertTrue(b.extend(0.104f, 0.1f))        // 4 units
        b.end()
        assertEquals(2, b.strokes.single().pointCount)
    }

    @Test fun aTapMakesAOnePointDot() {
        val b = DrawingBoard()
        b.begin(1, 2, false, 0.5f, 0.4f)
        assertTrue(b.end())
        assertEquals(1, b.strokes.single().pointCount)
    }

    @Test fun eraserStrokesAreRecordedAsSuch() {
        val b = DrawingBoard()
        assertTrue(stroke(b, 0.1f, 0.1f, 0.4f, 0.4f, eraser = true))
        assertTrue(b.strokes.single().eraser)
    }

    @Test fun outOfRangeToolsAreRejected() {
        val b = DrawingBoard()
        assertFalse(b.begin(6, 0, false, 0f, 0f))
        assertFalse(b.begin(0, 3, false, 0f, 0f))
        assertFalse(b.begin(-1, 0, false, 0f, 0f))
        assertFalse(b.drawing)
    }

    @Test fun aStrokeStopsGrowingAtThePerStrokeCap() {
        val b = DrawingBoard()
        b.begin(0, 1, false, 0f, 0f)
        var accepted = 1
        for (i in 1..2000) if (b.extend((i % 2) * 0.9f, ((i / 2) % 2) * 0.7f + (i % 7) * 0.01f)) accepted++
        b.end()
        assertEquals(DrawLimits.MAX_POINTS_PER_STROKE, accepted)
        assertEquals(DrawLimits.MAX_POINTS_PER_STROKE, b.strokes.single().pointCount)
    }

    @Test fun theStrokeCountCapMakesTheBoardFull() {
        val b = DrawingBoard()
        repeat(DrawLimits.MAX_STROKES) { assertTrue(stroke(b, 0.1f, 0.1f, 0.3f, 0.3f)) }
        assertTrue(b.isFull)
        assertFalse(b.begin(0, 1, false, 0f, 0f))
        assertEquals(DrawLimits.MAX_STROKES, b.strokes.size)
        assertTrue(b.undo())
        assertFalse(b.isFull)
        assertTrue(b.begin(0, 1, false, 0f, 0f))
    }

    @Test fun theTotalPointCapIsNeverExceeded() {
        val b = DrawingBoard()
        var strokes = 0
        while (!b.isFull && strokes < DrawLimits.MAX_STROKES) {
            b.begin(0, 1, false, 0f, 0f)
            for (i in 1 until DrawLimits.MAX_POINTS_PER_STROKE) b.extend(if (i % 2 == 0) 0.05f else 0.95f, 0.01f * (i % 70))
            b.end()
            strokes++
        }
        assertTrue(b.totalPoints <= DrawLimits.MAX_TOTAL_POINTS)
        assertEquals(b.strokes.sumOf { it.pointCount }, b.totalPoints)
        assertTrue(b.isFull)
    }

    @Test fun undoDropsTheLastStrokeAndClearEmptiesTheBoard() {
        val b = DrawingBoard()
        stroke(b, 0.1f, 0.1f, 0.5f, 0.5f)
        stroke(b, 0.2f, 0.2f, 0.6f, 0.6f)
        assertTrue(b.undo())
        assertEquals(1, b.strokes.size)
        assertEquals(2, b.totalPoints)
        b.clear()
        assertTrue(b.strokes.isEmpty()); assertEquals(0, b.totalPoints)
        assertFalse(b.undo())
    }

    @Test fun undoIsIgnoredWhileAFingerIsDown() {
        val b = DrawingBoard()
        stroke(b, 0.1f, 0.1f, 0.5f, 0.5f)
        b.begin(0, 1, false, 0.3f, 0.3f)
        assertFalse(b.undo())
        assertEquals(1, b.strokes.size)
    }

    @Test fun beginningWhileDrawingCommitsThePreviousStroke() {
        val b = DrawingBoard()
        b.begin(0, 1, false, 0.1f, 0.1f); b.extend(0.5f, 0.5f)
        b.begin(1, 1, false, 0.2f, 0.2f)
        assertEquals(1, b.strokes.size)
    }

    @Test fun theLiveStrokeIsReadableWhileDrawing() {
        val b = DrawingBoard()
        b.begin(3, 2, true, 0.1f, 0.2f); b.extend(0.4f, 0.3f)
        assertTrue(b.drawing)
        assertEquals(2, b.activePointCount)
        assertEquals(400, b.activeX(1)); assertEquals(300, b.activeY(1))
        assertEquals(3, b.activeColor); assertEquals(2, b.activeSize); assertTrue(b.activeEraser)
    }

    @Test fun theBufferGrowsWithoutLosingPoints() {
        val b = DrawingBoard()
        b.begin(0, 1, false, 0f, 0f)
        for (i in 1 until 300) b.extend(i / 300f, (i % 3) * 0.05f)
        b.end()
        val s = b.strokes.single()
        assertEquals(300, s.pointCount)
        assertTrue(s.xy.indices.all { s.xy[it] in 0..1000 })
        assertEquals(0, s.xy[0])
    }

    // ---- the eraser has its own budget; caps are reported ----

    @Test fun eraserStrokesDoNotUseUpTheInkBudget() {
        val b = DrawingBoard()
        repeat(DrawLimits.MAX_ERASER_STROKES) { assertTrue(stroke(b, 0.1f, 0.1f, 0.3f, 0.3f, eraser = true)) }
        assertFalse("erasing never fills the page for ink", b.isFull)
        assertTrue(b.isFullFor(true))
        assertFalse("the eraser budget is still capped", b.begin(0, 1, true, 0.5f, 0.5f))
        assertTrue(stroke(b, 0.1f, 0.1f, 0.3f, 0.3f))
        assertEquals(DrawLimits.MAX_ERASER_STROKES * 2 + 2, b.totalPoints)
    }

    @Test fun aFullInkPageStillAllowsTheEraserAndTheCapIsReported() {
        val b = DrawingBoard()
        repeat(DrawLimits.MAX_STROKES) { assertTrue(stroke(b, 0.1f, 0.1f, 0.3f, 0.3f)) }
        assertTrue(b.isFull)
        assertFalse(b.begin(0, 1, false, 0.5f, 0.5f))
        assertTrue("a refused stroke raises capHit", b.capHit)
        assertTrue(stroke(b, 0.1f, 0.1f, 0.3f, 0.3f, eraser = true))
        assertTrue(b.strokes.last().eraser)
        assertTrue(b.undo()) // undoes the eraser stroke; the page is still full of ink
        assertTrue(b.isFull)
        assertFalse(b.capHit)
        b.clear()
        assertFalse(b.isFull)
    }

    @Test fun hittingThePerStrokeCapMidStrokeRaisesCapHit() {
        val b = DrawingBoard()
        assertTrue(b.begin(0, 1, false, 0f, 0f))
        assertFalse(b.capHit)
        for (i in 0 until DrawLimits.MAX_POINTS_PER_STROKE + 20) b.extend(if (i % 2 == 0) 0.05f else 0.95f, 0.01f * (i % 70))
        assertTrue(b.capHit)
        assertEquals(DrawLimits.MAX_POINTS_PER_STROKE, b.activePointCount)
        b.end()
        assertTrue(b.begin(0, 1, false, 0f, 0f)) // a new stroke starts and the flag drops
        assertFalse(b.capHit)
    }

    @Test fun eraserAndInkBudgetsAreValidatedSeparately() {
        val ink = List(DrawLimits.MAX_STROKES) { DrawStroke(0, 0, false, intArrayOf(1, 1)) }
        val erase = List(DrawLimits.MAX_ERASER_STROKES) { DrawStroke(0, 0, true, intArrayOf(1, 1)) }
        assertTrue(DrawingBoard.validate(ink + erase))
        assertFalse(DrawingBoard.validate(erase + DrawStroke(0, 0, true, intArrayOf(1, 1))))
        val b = DrawingBoard()
        assertTrue(b.load(ink + erase))
        assertTrue(b.isFull)
        assertTrue(b.isFullFor(true))
        assertEquals((ink.size + erase.size) * 1, b.totalPoints)
    }

    // ---- validation / load ----

    @Test fun loadAcceptsValidStrokesAndReplacesTheDrawing() {
        val b = DrawingBoard()
        stroke(b, 0.9f, 0.5f, 0.8f, 0.6f)
        val ok = listOf(DrawStroke(1, 0, false, intArrayOf(10, 10, 500, 700)), DrawStroke(5, 2, true, intArrayOf(0, 0)))
        assertTrue(b.load(ok))
        assertEquals(2, b.strokes.size)
        assertEquals(3, b.totalPoints)
    }

    @Test fun loadRejectsAnythingOutsideTheLimitsAndChangesNothing() {
        val b = DrawingBoard()
        stroke(b, 0.1f, 0.1f, 0.5f, 0.5f)
        val bad = listOf(
            listOf(DrawStroke(6, 0, false, intArrayOf(1, 1))),
            listOf(DrawStroke(0, 3, false, intArrayOf(1, 1))),
            listOf(DrawStroke(0, 0, false, intArrayOf(1001, 1))),
            listOf(DrawStroke(0, 0, false, intArrayOf(1, 751))),
            listOf(DrawStroke(0, 0, false, intArrayOf(-1, 1))),
            listOf(DrawStroke(0, 0, false, intArrayOf(1, 1, 2))),
            listOf(DrawStroke(0, 0, false, intArrayOf())),
            listOf(DrawStroke(0, 0, false, IntArray(2 * (DrawLimits.MAX_POINTS_PER_STROKE + 1)))),
            List(DrawLimits.MAX_STROKES + 1) { DrawStroke(0, 0, false, intArrayOf(1, 1)) },
            List(16) { DrawStroke(0, 0, false, IntArray(2 * DrawLimits.MAX_POINTS_PER_STROKE)) }
        )
        for (list in bad) {
            assertFalse(DrawingBoard.validate(list))
            assertFalse(b.load(list))
        }
        assertEquals(1, b.strokes.size)
    }
}
