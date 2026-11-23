package com.gamesuite.games.towerdefence

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure board geometry for the Tower Defence screen: pointer position to build anchor, tower hit
 * test, the 14 x 8 TalkBack / one-switch grid over the 24 x 14 tile field, and the one-switch scan
 * state machine. No Compose, JVM tested.
 */
object TdBoardMath {
    const val GRID_COLS = 14
    const val GRID_ROWS = 8

    /** Anchor cell (top-left of the 2 x 2 footprint) for a pointer at ([xPx], [yPx]) on a board of [wPx] x [hPx]. */
    fun anchorFor(xPx: Float, yPx: Float, wPx: Float, hPx: Float, snap: Boolean = true): Pair<Int, Int> {
        val tx = xPx / wPx * TdGrid.TILES_X
        val ty = yPx / hPx * TdGrid.TILES_Y
        val ax: Int
        val ay: Int
        if (snap) {
            ax = floor(tx).toInt() * TdGrid.CELLS_PER_TILE
            ay = floor(ty).toInt() * TdGrid.CELLS_PER_TILE
        } else {
            ax = (tx * TdGrid.CELLS_PER_TILE - 1f).roundToInt()
            ay = (ty * TdGrid.CELLS_PER_TILE - 1f).roundToInt()
        }
        return ax.coerceIn(0, TdGrid.COLS - 2) to ay.coerceIn(0, TdGrid.ROWS - 2)
    }

    /**
     * The anchor for a pointer at ([rootX], [rootY]) in ROOT coordinates while a card is dragged from the
     * tray: the board's top-left in the same space is ([boardLeft], [boardTop]) and the ghost rides [liftPx]
     * above the finger. Null when the finger is outside the board, so releasing there cancels.
     */
    fun anchorForDrag(
        rootX: Float, rootY: Float, boardLeft: Float, boardTop: Float, wPx: Float, hPx: Float, liftPx: Float, snap: Boolean = true
    ): Pair<Int, Int>? {
        val x = rootX - boardLeft
        val y = rootY - boardTop
        if (x < 0f || x > wPx || y < 0f || y > hPx) return null
        // The ghost is lifted above the finger, but the finger itself must be on the board.
        return anchorFor(x, (y - liftPx).coerceAtLeast(0f), wPx, hPx, snap)
    }

    /**
     * One keyboard / D-pad step of the placement cursor: a whole tile (2 cells) when [snap], one cell
     * otherwise, kept on the board. Anchors are top-left cells of the 2 x 2 footprint.
     */
    fun moveAnchor(ax: Int, ay: Int, dx: Int, dy: Int, snap: Boolean = true): Pair<Int, Int> {
        val step = if (snap) TdGrid.CELLS_PER_TILE else 1
        val nx = (ax + dx * step).coerceIn(0, TdGrid.COLS - 2)
        val ny = (ay + dy * step).coerceIn(0, TdGrid.ROWS - 2)
        return if (snap) (nx / 2 * 2) to (ny / 2 * 2) else nx to ny
    }

    /** The tower under the pointer (footprint plus a [haloTiles] halo), nearest centre first; null if none. */
    fun towerAt(
        towers: List<TowerDefenceGame.TowerDefenceTower>, xPx: Float, yPx: Float, wPx: Float, hPx: Float, haloTiles: Float
    ): Int? {
        val tx = xPx / wPx * TdGrid.TILES_X
        val ty = yPx / hPx * TdGrid.TILES_Y
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (t in towers) {
            val c = TdGrid.towerCentre(t.cx, t.cy)
            val dx = abs(c.x - tx)
            val dy = abs(c.y - ty)
            if (dx <= 0.5f + haloTiles && dy <= 0.5f + haloTiles) {
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = t.id }
            }
        }
        return best
    }

    /** The anchor that centres a tower in a11y cell ([col], [row]). */
    fun anchorOfA11yCell(col: Int, row: Int): Pair<Int, Int> {
        val tx = (col + 0.5f) * TdGrid.TILES_X / GRID_COLS
        val ty = (row + 0.5f) * TdGrid.TILES_Y / GRID_ROWS
        return anchorFor(tx, ty, TdGrid.TILES_X, TdGrid.TILES_Y, snap = true)
    }

    /** The a11y cell that contains the tower anchored at ([ax], [ay]). */
    fun a11yCellOf(ax: Int, ay: Int): Pair<Int, Int> {
        val c = TdGrid.towerCentre(ax, ay)
        val col = (c.x / TdGrid.TILES_X * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)
        val row = (c.y / TdGrid.TILES_Y * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)
        return col to row
    }

    fun columnLetter(col: Int): Char = 'A' + col.coerceIn(0, GRID_COLS - 1)
}

/**
 * The one-switch placement path: a band sweeps the columns, one press locks it; the band sweeps the
 * rows, one press locks it; the tower roles cycle, one press builds. Immutable; the screen advances
 * it on a timer ([step]) and on each press ([lock]).
 */
data class TdScan(val phase: Phase = Phase.COLUMN, val index: Int = 0, val col: Int = 0, val row: Int = 0) {
    enum class Phase { COLUMN, ROW, ROLE }

    sealed interface Outcome {
        data class Continue(val scan: TdScan) : Outcome
        data class Build(val col: Int, val row: Int, val role: TdRole) : Outcome
    }

    private val size: Int
        get() = when (phase) { Phase.COLUMN -> TdBoardMath.GRID_COLS; Phase.ROW -> TdBoardMath.GRID_ROWS; Phase.ROLE -> TdRole.entries.size }

    /** One timer tick: the band moves to the next column, row or role (wrapping). */
    fun step(): TdScan = copy(index = (index + 1) % size)

    /** One switch press. */
    fun lock(): Outcome = when (phase) {
        Phase.COLUMN -> Outcome.Continue(TdScan(Phase.ROW, 0, col = index))
        Phase.ROW -> Outcome.Continue(TdScan(Phase.ROLE, 0, col = col, row = index))
        Phase.ROLE -> Outcome.Build(col, row, TdRole.entries[index])
    }
}
