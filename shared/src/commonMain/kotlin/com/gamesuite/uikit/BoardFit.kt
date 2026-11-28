package com.gamesuite.uikit

/**
 * Solves the one problem every grid/board game screen in the suite re-solves by hand: how big
 * should a single cell be, given the space actually available. Pure math — no Compose, Android,
 * or Dp types — so it is fully covered by a plain JUnit/kotlin.test shape matrix instead of a
 * device or an androidTest. Values are unit-agnostic; pass px, dp, or any consistent unit and
 * the result comes back in that same unit.
 *
 * THE BUG THIS REPLACES: several screens compute a cell size as roughly
 * `(min(availableWidth, availableHeight) / cells).coerceAtLeast(MIN_TOUCH_TARGET)` — a
 * reasonable-looking touch-target floor that silently makes the board LARGER than its
 * container whenever the container is smaller than `cells * MIN_TOUCH_TARGET` (confirmed on
 * Chess, Checkers, Mancala and Connect Four on a Fold cover screen). A "floor" that can push
 * the result past the ceiling isn't a floor, it's a bug — [fitBoard] never returns a cell size
 * whose total footprint exceeds the available space, full stop. [minCellPx] is informational
 * only: when the natural fit falls under it, [BoardFit.meetsMinimum] is false and the caller
 * decides what to do (scroll, zoom, or accept the smaller cell) — this function never invents
 * room that isn't there.
 */
data class BoardFit(
    /** Never negative; never makes [boardWidthPx] exceed the available width or [boardHeightPx]
     *  exceed the available height (both net of [BoardFit] having already subtracted the frame). */
    val cellPx: Float,
    val boardWidthPx: Float,
    val boardHeightPx: Float,
    /** True iff [cellPx] met the requested `minCellPx` — informational; never forces a resize. */
    val meetsMinimum: Boolean,
    /** Cell width in px. Equals [cellPx] for square cells; `cellPx * cellAspect` otherwise.
     *  [cellPx] is always the cell HEIGHT, so existing square-cell callers are unaffected. */
    val cellWidthPx: Float = cellPx,
) {
    /**
     * Extra px to grow a touch target by on EACH side so that its effective size reaches
     * [minTargetPx] (a 48 dp target converted to px) in both axes. 0 when the cell is already
     * big enough. Visual size is never changed; use it to widen a hit test only.
     */
    fun hitSlopPx(minTargetPx: Float): HitSlop = HitSlop(
        horizontalPx = ((minTargetPx - cellWidthPx) / 2f).coerceAtLeast(0f),
        verticalPx = ((minTargetPx - cellPx) / 2f).coerceAtLeast(0f),
    )
}

/** Per-side hit-target growth in px (see [BoardFit.hitSlopPx]). */
data class HitSlop(val horizontalPx: Float, val verticalPx: Float) {
    val isNone: Boolean get() = horizontalPx <= 0f && verticalPx <= 0f
}

/**
 * Hit test for sparse touch targets (Mastermind code pegs, Backgammon points, narrow columns)
 * whose drawn size is under the minimum touch size. Each target is a rect centred on
 * ([centerXs][i], [centerYs][i]) with the drawn half size, grown by [slop]. Returns the index of
 * the target whose centre is nearest the touch among those that contain it, or -1 for none, so
 * two neighbouring grown targets that overlap never swallow each other's centre taps.
 */
fun hitTestWithSlop(
    touchX: Float,
    touchY: Float,
    centerXs: FloatArray,
    centerYs: FloatArray,
    halfWidthPx: Float,
    halfHeightPx: Float,
    slop: HitSlop,
): Int {
    require(centerXs.size == centerYs.size) { "centre arrays must be the same length" }
    var best = -1
    var bestDist = Float.MAX_VALUE
    for (i in centerXs.indices) {
        val dx = kotlin.math.abs(touchX - centerXs[i])
        val dy = kotlin.math.abs(touchY - centerYs[i])
        if (dx <= halfWidthPx + slop.horizontalPx && dy <= halfHeightPx + slop.verticalPx) {
            val d = dx * dx + dy * dy
            if (d < bestDist) { bestDist = d; best = i }
        }
    }
    return best
}

/**
 * @param availableWidthPx width of the space the board may occupy.
 * @param availableHeightPx height of the space the board may occupy.
 * @param columns number of cells across; must be positive.
 * @param rows number of cells down; defaults to [columns] (a square grid).
 * @param framePx symmetric inset (border/padding) subtracted from each side before fitting —
 *   pass 0 if the caller's box is already the content area.
 * @param minCellPx the cell size below which [BoardFit.meetsMinimum] reports false. Purely
 *   informational: never coerced up into a size that would overflow the container.
 * @param cellAspect cell width divided by cell height (1 = square, the default; a hex or wide
 *   tile uses another ratio). [BoardFit.cellPx] stays the cell HEIGHT and `minCellPx` /
 *   `maxCellPx` constrain the SHORTER cell side, so a 1.0 aspect behaves exactly as before.
 * @param maxCellPx an upper bound on the cell size (e.g. so a small board doesn't sprawl across
 *   a tablet) — this one IS safe to coerce down to, since shrinking never overflows anything.
 */
fun fitBoard(
    availableWidthPx: Float,
    availableHeightPx: Float,
    columns: Int,
    rows: Int = columns,
    framePx: Float = 0f,
    minCellPx: Float = 0f,
    maxCellPx: Float = Float.MAX_VALUE,
    cellAspect: Float = 1f,
): BoardFit {
    require(columns > 0) { "columns must be positive, got $columns" }
    require(rows > 0) { "rows must be positive, got $rows" }
    require(framePx >= 0f) { "framePx must not be negative, got $framePx" }
    require(minCellPx >= 0f) { "minCellPx must not be negative, got $minCellPx" }
    require(cellAspect > 0f && cellAspect.isFinite()) { "cellAspect must be positive, got $cellAspect" }
    require(minCellPx <= maxCellPx) { "minCellPx ($minCellPx) must be <= maxCellPx ($maxCellPx)" }

    val usableWidth = (availableWidthPx - 2f * framePx).coerceAtLeast(0f)
    val usableHeight = (availableHeightPx - 2f * framePx).coerceAtLeast(0f)
    // The whole fix in one line: clamp DOWN to maxCellPx (always safe), never clamp UP past
    // what the container can hold.
    // cell is the height; the width is cell * cellAspect. The shorter side is what min/max bound.
    val shortScale = minOf(1f, cellAspect)
    val cell = minOf(
        usableWidth / (columns * cellAspect),
        usableHeight / rows,
        maxCellPx / shortScale,
    ).coerceAtLeast(0f)

    return BoardFit(
        cellPx = cell,
        boardWidthPx = cell * cellAspect * columns,
        boardHeightPx = cell * rows,
        meetsMinimum = cell * shortScale >= minCellPx,
        cellWidthPx = cell * cellAspect,
    )
}
