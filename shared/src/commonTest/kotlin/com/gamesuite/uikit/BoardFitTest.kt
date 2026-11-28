package com.gamesuite.uikit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one property that matters, checked across every real device shape this app targets (see
 * docs/DEVICE_SPECIFIC_PLAN.md) times every board size a game in this suite actually uses:
 * [fitBoard] must NEVER return a footprint larger than the space it was given. That single
 * invariant is what the Chess/Checkers/Mancala/Connect Four/Tic-Tac-Toe/Dots and Boxes clipping
 * bugs all violated by coercing a cell size up to a touch-target floor without checking whether
 * the container could actually hold it.
 */
class BoardFitTest {

    // Real device shapes (portrait and landscape) from docs/DEVICE_SPECIFIC_PLAN.md, plus the
    // narrowest phone this app supports and two extremes to stress the math at either end.
    private val shapes = listOf(
        "fold cover" to (344f to 620f),
        "fold cover, tall" to (344f to 882f),
        "fold inner, portrait" to (690f to 829f),
        "fold inner, landscape" to (829f to 690f),
        "tab s9, portrait" to (800f to 1280f),
        "tab s9, landscape" to (1280f to 800f),
        "narrow phone" to (312f to 600f),
        "square-ish" to (500f to 500f),
        "tiny (multi-window popup)" to (200f to 200f),
        "very wide (desktop/DeX)" to (2000f to 700f),
    )

    // (columns, rows) actually used by a real game in the suite.
    private val boardSizes = listOf(
        "tic-tac-toe" to (3 to 3),
        "connect four" to (7 to 6),
        "chess/checkers" to (8 to 8),
        "sudoku" to (9 to 9),
        "kakuro/nonogram (hard)" to (12 to 12),
        "nonogram (hard, non-square)" to (15 to 15),
        "minesweeper (hard)" to (16 to 30),
    )

    @Test
    fun `never exceeds the available space, across every real shape x board size x frame x floor combination`() {
        var casesChecked = 0
        for ((shapeName, shape) in shapes) {
            val (w, h) = shape
            for ((boardName, size) in boardSizes) {
                val (cols, rows) = size
                for (frame in listOf(0f, 8f, 16f)) {
                    for (minCell in listOf(0f, 32f, 48f)) {
                        val fit = fitBoard(w, h, cols, rows, framePx = frame, minCellPx = minCell)
                        val usableW = (w - 2 * frame).coerceAtLeast(0f)
                        val usableH = (h - 2 * frame).coerceAtLeast(0f)
                        val label = "$shapeName (${w}x$h) / $boardName (${cols}x$rows) / frame=$frame / min=$minCell"
                        assertTrue(fit.boardWidthPx <= usableW + 0.01f, "$label: board width ${fit.boardWidthPx} exceeded usable width $usableW")
                        assertTrue(fit.boardHeightPx <= usableH + 0.01f, "$label: board height ${fit.boardHeightPx} exceeded usable height $usableH")
                        assertTrue(fit.cellPx >= 0f, "$label: negative cell size ${fit.cellPx}")
                        casesChecked++
                    }
                }
            }
        }
        assertTrue(casesChecked >= 10 * 7 * 3 * 3, "expected a real matrix, not an accidentally-empty loop")
    }

    @Test
    fun `the historical bug -- a floor coerced past the container -- cannot reproduce`() {
        // The exact shape of the confirmed Chess bug: an 8x8 board, a 48dp touch-target floor,
        // in a 312dp-wide pane (Fold cover screen minus its frame). The naive
        // `(min(w,h)/8).coerceAtLeast(48.dp)` this replaces would return 48dp/cell = 384dp total,
        // blowing past the 312dp container. fitBoard must stay inside it instead.
        val fit = fitBoard(availableWidthPx = 312f, availableHeightPx = 600f, columns = 8, rows = 8, minCellPx = 48f)
        assertTrue(fit.boardWidthPx <= 312f, "must not overflow the 312px pane")
        assertEquals(39f, fit.cellPx) // 312 / 8, exactly -- narrower than the requested 48 floor
        assertFalse(fit.meetsMinimum, "39px is genuinely under the 48px floor -- meetsMinimum must say so, not lie")
    }

    @Test
    fun `honours the floor when the container is actually big enough for it`() {
        val fit = fitBoard(availableWidthPx = 1000f, availableHeightPx = 1000f, columns = 8, minCellPx = 48f)
        assertTrue(fit.meetsMinimum)
        assertEquals(125f, fit.cellPx) // 1000 / 8
    }

    @Test
    fun `maxCellPx is safe to coerce down to, unlike minCellPx`() {
        val fit = fitBoard(availableWidthPx = 2000f, availableHeightPx = 2000f, columns = 4, maxCellPx = 100f)
        assertEquals(100f, fit.cellPx)
        assertEquals(400f, fit.boardWidthPx)
        assertTrue(fit.boardWidthPx <= 2000f)
    }

    @Test
    fun `frame is subtracted symmetrically from both sides before fitting`() {
        val fit = fitBoard(availableWidthPx = 116f, availableHeightPx = 116f, columns = 10, framePx = 8f)
        // usable = 116 - 2*8 = 100 -> 100/10 = 10 per cell
        assertEquals(10f, fit.cellPx)
        assertEquals(100f, fit.boardWidthPx)
    }

    @Test
    fun `a container smaller than the frame clamps to zero instead of going negative`() {
        val fit = fitBoard(availableWidthPx = 10f, availableHeightPx = 10f, columns = 4, framePx = 20f, minCellPx = 24f)
        assertEquals(0f, fit.cellPx)
        assertEquals(0f, fit.boardWidthPx)
        assertFalse(fit.meetsMinimum, "a 0px cell can never meet a positive floor")
    }

    @Test
    fun `non-square boards fit columns and rows independently`() {
        // A 7-wide, 6-tall Connect Four board in a wide-but-short landscape pane: width is not
        // the constraint, height is.
        val fit = fitBoard(availableWidthPx = 2000f, availableHeightPx = 300f, columns = 7, rows = 6)
        assertEquals(50f, fit.cellPx) // bound by height: 300 / 6 = 50, not width: 2000 / 7 ~= 285.7
        assertEquals(350f, fit.boardWidthPx)
        assertEquals(300f, fit.boardHeightPx)
    }

    @Test
    fun `rejects non-positive columns or rows`() {
        assertFailsWith<IllegalArgumentException> { fitBoard(100f, 100f, columns = 0) }
        assertFailsWith<IllegalArgumentException> { fitBoard(100f, 100f, columns = 3, rows = -1) }
    }

    @Test
    fun `rejects an inverted min-max range`() {
        assertFailsWith<IllegalArgumentException> { fitBoard(100f, 100f, columns = 3, minCellPx = 50f, maxCellPx = 10f) }
    }
}

/** P6: non-square cells and the hit-slop helpers. */
class BoardFitNonSquareTest {
    @Test
    fun `default aspect is exactly the old square behaviour`() {
        val a = fitBoard(300f, 500f, columns = 7, rows = 6, minCellPx = 20f, maxCellPx = 80f)
        val b = fitBoard(300f, 500f, columns = 7, rows = 6, minCellPx = 20f, maxCellPx = 80f, cellAspect = 1f)
        assertEquals(a, b)
        assertEquals(a.cellPx, a.cellWidthPx)
    }

    @Test
    fun `wide cells never overflow either axis`() {
        for (aspect in listOf(0.5f, 0.866f, 1f, 1.5f, 2f)) {
            for ((w, h) in listOf(344f to 620f, 829f to 690f, 200f to 200f, 2000f to 700f)) {
                val f = fitBoard(w, h, columns = 9, rows = 5, cellAspect = aspect)
                assertTrue(f.boardWidthPx <= w + 0.001f, "width aspect=$aspect ${w}x$h")
                assertTrue(f.boardHeightPx <= h + 0.001f, "height aspect=$aspect ${w}x$h")
                assertEquals(f.cellPx * aspect, f.cellWidthPx, 0.0001f)
            }
        }
    }

    @Test
    fun `width limited board uses the aspect`() {
        // 4 columns twice as wide as tall in 400x1000: width bound 400/(4*2) = 50, height bound 200.
        val f = fitBoard(400f, 1000f, columns = 4, rows = 5, cellAspect = 2f)
        assertEquals(50f, f.cellPx, 0.001f)
        assertEquals(100f, f.cellWidthPx, 0.001f)
        assertEquals(400f, f.boardWidthPx, 0.001f)
        assertEquals(250f, f.boardHeightPx, 0.001f)
    }

    @Test
    fun `max and min constrain the shorter side`() {
        val wide = fitBoard(2000f, 2000f, columns = 4, rows = 4, maxCellPx = 40f, cellAspect = 2f)
        assertEquals(40f, wide.cellPx, 0.001f)       // height is the short side
        assertEquals(80f, wide.cellWidthPx, 0.001f)
        val tall = fitBoard(2000f, 2000f, columns = 4, rows = 4, maxCellPx = 40f, cellAspect = 0.5f)
        assertEquals(80f, tall.cellPx, 0.001f)       // width (40) is the short side
        assertEquals(40f, tall.cellWidthPx, 0.001f)
        assertFalse(fitBoard(100f, 100f, columns = 4, rows = 4, minCellPx = 20f, cellAspect = 2f).meetsMinimum)
        assertTrue(fitBoard(400f, 400f, columns = 4, rows = 4, minCellPx = 20f, cellAspect = 2f).meetsMinimum)
    }

    @Test
    fun `rejects a non-positive aspect`() {
        assertFailsWith<IllegalArgumentException> { fitBoard(100f, 100f, columns = 3, cellAspect = 0f) }
        assertFailsWith<IllegalArgumentException> { fitBoard(100f, 100f, columns = 3, cellAspect = -1f) }
    }

    @Test
    fun `hit slop grows small cells to the minimum target and leaves big ones alone`() {
        val small = fitBoard(280f, 280f, columns = 8)       // 35 px cells
        val slop = small.hitSlopPx(48f)
        assertEquals(6.5f, slop.horizontalPx, 0.001f)
        assertEquals(6.5f, slop.verticalPx, 0.001f)
        assertFalse(slop.isNone)
        val big = fitBoard(800f, 800f, columns = 8)         // 100 px cells
        assertTrue(big.hitSlopPx(48f).isNone)
    }

    @Test
    fun `hit slop is per axis for non-square cells`() {
        val f = fitBoard(400f, 400f, columns = 10, rows = 10, cellAspect = 0.5f)
        val s = f.hitSlopPx(48f)
        assertEquals((48f - f.cellWidthPx) / 2f, s.horizontalPx, 0.001f)
        assertEquals((48f - f.cellPx) / 2f, s.verticalPx, 0.001f)
    }

    @Test
    fun `hit test accepts a near miss inside the slop and rejects beyond it`() {
        val xs = floatArrayOf(20f, 60f, 100f)
        val ys = floatArrayOf(20f, 20f, 20f)
        val slop = HitSlop(10f, 10f)
        // A 20 px peg (half 10): a touch 16 px from the centre misses without slop and lands with it.
        assertEquals(-1, hitTestWithSlop(76f, 20f, floatArrayOf(60f), floatArrayOf(20f), 10f, 10f, HitSlop(0f, 0f)))
        assertEquals(0, hitTestWithSlop(76f, 20f, floatArrayOf(60f), floatArrayOf(20f), 10f, 10f, slop))
        // Beyond half + slop is still a miss.
        assertEquals(-1, hitTestWithSlop(81f, 20f, floatArrayOf(60f), floatArrayOf(20f), 10f, 10f, slop))
        // A touch on a centre picks that target even when neighbours' grown rects overlap it.
        assertEquals(1, hitTestWithSlop(60f, 20f, xs, ys, 10f, 10f, HitSlop(25f, 25f)))
        // Between two pegs the nearer one wins.
        assertEquals(1, hitTestWithSlop(41f, 20f, xs, ys, 10f, 10f, HitSlop(25f, 25f)))
        assertEquals(0, hitTestWithSlop(39f, 20f, xs, ys, 10f, 10f, HitSlop(25f, 25f)))
    }

    @Test
    fun `hit test rejects mismatched arrays`() {
        assertFailsWith<IllegalArgumentException> {
            hitTestWithSlop(0f, 0f, floatArrayOf(1f), floatArrayOf(), 1f, 1f, HitSlop(0f, 0f))
        }
    }
}
