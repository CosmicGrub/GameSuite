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
)

/**
 * @param availableWidthPx width of the space the board may occupy.
 * @param availableHeightPx height of the space the board may occupy.
 * @param columns number of cells across; must be positive.
 * @param rows number of cells down; defaults to [columns] (a square grid).
 * @param framePx symmetric inset (border/padding) subtracted from each side before fitting —
 *   pass 0 if the caller's box is already the content area.
 * @param minCellPx the cell size below which [BoardFit.meetsMinimum] reports false. Purely
 *   informational: never coerced up into a size that would overflow the container.
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
): BoardFit {
    require(columns > 0) { "columns must be positive, got $columns" }
    require(rows > 0) { "rows must be positive, got $rows" }
    require(framePx >= 0f) { "framePx must not be negative, got $framePx" }
    require(minCellPx >= 0f) { "minCellPx must not be negative, got $minCellPx" }
    require(minCellPx <= maxCellPx) { "minCellPx ($minCellPx) must be <= maxCellPx ($maxCellPx)" }

    val usableWidth = (availableWidthPx - 2f * framePx).coerceAtLeast(0f)
    val usableHeight = (availableHeightPx - 2f * framePx).coerceAtLeast(0f)
    // The whole fix in one line: clamp DOWN to maxCellPx (always safe), never clamp UP past
    // what the container can hold.
    val cell = minOf(usableWidth / columns, usableHeight / rows, maxCellPx).coerceAtLeast(0f)

    return BoardFit(
        cellPx = cell,
        boardWidthPx = cell * columns,
        boardHeightPx = cell * rows,
        meetsMinimum = cell >= minCellPx,
    )
}
