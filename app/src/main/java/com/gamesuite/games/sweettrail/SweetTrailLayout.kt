package com.gamesuite.games.sweettrail

/**
 * Where every square sits on the screen, as pure numbers (no Compose), so the winding layout is unit-testable.
 * The board is [COLUMNS] cells wide and [ROWS] tall: row 0 is the Party Table banner, rows 1..6 hold the 60
 * squares in six snaking rows of ten (square 1 bottom-left, row 2 runs right to left, ...), and row 7 is the start
 * strip. All coordinates are in cell units (x across, y down).
 */
object SweetTrailLayout {
    const val COLUMNS = 10
    const val ROWS = 8
    const val TRAIL_ROWS = 6

    /** Column (0..9) of square 1..60. */
    fun column(square: Int): Int {
        val i = square - 1
        val inRow = i % COLUMNS
        return if ((i / COLUMNS) % 2 == 0) inRow else COLUMNS - 1 - inRow
    }

    /** Screen row (1..6) of square 1..60; square 1 is on the lowest trail row. */
    fun row(square: Int): Int = TRAIL_ROWS - (square - 1) / COLUMNS

    fun centerX(square: Int): Float = when {
        square <= SweetTrailBoard.START -> 0.9f
        square >= SweetTrailBoard.FINISH -> 2.0f
        else -> column(square) + 0.5f
    }

    fun centerY(square: Int): Float = when {
        square <= SweetTrailBoard.START -> ROWS - 0.5f
        square >= SweetTrailBoard.FINISH -> 0.5f
        else -> row(square) + 0.5f
    }

    /**
     * Where seat [seat]'s piece rests on [square] when [crowd] pieces share it (cell units). Pieces on the start
     * strip and the Party Table line up side by side; elsewhere a shared square is split into quarters.
     */
    fun piecePosition(seat: Int, square: Int, crowd: Int): Pair<Float, Float> {
        if (square <= SweetTrailBoard.START) return Pair(0.9f + seat * 0.8f, ROWS - 0.5f)
        if (square >= SweetTrailBoard.FINISH) return Pair(0.85f + seat * 0.7f, 0.5f)
        val x = centerX(square)
        val y = centerY(square)
        if (crowd <= 1) return Pair(x, y)
        return Pair(x + ((seat % 2) - 0.5f) * 0.4f, y + ((seat / 2) - 0.5f) * 0.4f)
    }

    /** The bend of the drawn (and travelled) arc of the shortcut starting on [startSquare], in cells. */
    fun shortcutBend(startSquare: Int): Float = when (SweetTrailBoard.SHORTCUT_NAMES[startSquare]) {
        "jelly_bridge" -> 1.0f
        "pretzel_ladder" -> -0.35f
        else -> -0.9f
    }
}
