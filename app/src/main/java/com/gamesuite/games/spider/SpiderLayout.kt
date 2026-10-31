package com.gamesuite.games.spider

import kotlin.math.max

/**
 * Pure geometry of the Spider table, in dp-like units (no Compose types, so it is unit tested on the
 * JVM). Every card is drawn at the position computed here, which is what lets the screen animate any
 * change (a move, a deal, a finished run) by simply gliding each card to its new slot.
 *
 * Ten lanes sit side by side; each lane is [SpiderMetrics.lane] wide and holds a card of
 * [SpiderMetrics.cardW]. Below the columns runs one strip: finished runs on the left, the stock rows on
 * the right.
 */
data class SpiderMetrics(
    val cardW: Float,
    val cardH: Float,
    val lane: Float,
    /** Visible step between face-down cards and between face-up cards at their natural size. */
    val downStep: Float,
    val upStep: Float,
    /** Height available for the columns (above the bottom strip). */
    val columnsH: Float,
    val boardW: Float,
    val boardH: Float,
    /** True when the lanes had to stay at the minimum width and the board is wider than the screen. */
    val scrollsHorizontally: Boolean
) {
    val stripY: Float get() = boardH - cardH - STRIP_PAD * 2
    val stripH: Float get() = cardH + STRIP_PAD * 2

    companion object {
        const val STRIP_PAD = 4f
    }
}

object SpiderLayout {
    const val CARD_ASPECT = 1.42f
    const val MIN_LANE = 36f
    const val MAX_LANE = 74f
    const val GAP = 3f

    private const val MIN_DOWN_RATIO = 0.07f
    private const val MIN_UP_RATIO = 0.17f
    private const val DOWN_RATIO = 0.2f
    private const val UP_RATIO = 0.42f

    /**
     * Fits the table into [availW] x [availH]. The lanes shrink to fit the width down to
     * [MIN_LANE]; below that the board keeps that lane width and scrolls sideways instead of
     * overflowing. The height is whatever the screen offers; a column too tall for it compresses its
     * overlap ([columnSteps]), and only if that is still not enough does the board grow taller than
     * [availH] (and the screen scrolls vertically).
     */
    fun metrics(availW: Float, availH: Float, tallestMinimumColumn: Float = 0f): SpiderMetrics {
        val rawLane = (availW / SPIDER_COLUMNS).coerceAtLeast(0f)
        val lane = rawLane.coerceIn(MIN_LANE, MAX_LANE)
        val scrolls = rawLane < MIN_LANE
        val cardW = lane - GAP
        val cardH = cardW * CARD_ASPECT
        val boardW = lane * SPIDER_COLUMNS
        val stripH = cardH + SpiderMetrics.STRIP_PAD * 2
        val columnsH = max(availH - stripH, cardH * 3f)
        val neededColumns = max(columnsH, tallestMinimumColumn)
        return SpiderMetrics(
            cardW = cardW, cardH = cardH, lane = lane,
            downStep = cardH * DOWN_RATIO, upStep = cardH * UP_RATIO,
            columnsH = neededColumns, boardW = boardW, boardH = neededColumns + stripH,
            scrollsHorizontally = scrolls
        )
    }

    fun columnX(col: Int, m: SpiderMetrics): Float = col * m.lane + (m.lane - m.cardW) / 2f

    /** Overlap steps for column [col] so the whole column fits [SpiderMetrics.columnsH]: (down, up). */
    fun columnSteps(s: SpiderState, col: Int, m: SpiderMetrics): Pair<Float, Float> {
        val down = s.faceDown[col]
        val up = s.columns[col].size - down
        val natural = naturalHeight(down, up, m.cardH, m.downStep, m.upStep)
        if (natural <= m.columnsH) return m.downStep to m.upStep
        val minH = naturalHeight(down, up, m.cardH, m.cardH * MIN_DOWN_RATIO, m.cardH * MIN_UP_RATIO)
        if (minH >= m.columnsH) return m.cardH * MIN_DOWN_RATIO to m.cardH * MIN_UP_RATIO
        // Interpolate between natural and minimum so the column fills exactly the space available.
        val t = (natural - m.columnsH) / (natural - minH)
        val d = m.downStep + (m.cardH * MIN_DOWN_RATIO - m.downStep) * t
        val u = m.upStep + (m.cardH * MIN_UP_RATIO - m.upStep) * t
        return d to u
    }

    private fun naturalHeight(down: Int, up: Int, cardH: Float, downStep: Float, upStep: Float): Float {
        if (down + up == 0) return cardH
        return cardH + down * downStep + max(up - 1, 0) * upStep
    }

    /** Top edge of card [index] in column [col]. */
    fun cardY(s: SpiderState, col: Int, index: Int, m: SpiderMetrics): Float {
        val (d, u) = columnSteps(s, col, m)
        val down = s.faceDown[col]
        return if (index < down) index * d else down * d + (index - down) * u
    }

    /** The tallest any column can get at the minimum overlap; the board is at least this tall. */
    fun tallestMinimum(s: SpiderState, cardH: Float): Float =
        (0 until SPIDER_COLUMNS).maxOf { c ->
            val down = s.faceDown[c]
            val up = s.columns[c].size - down
            naturalHeight(down, up, cardH, cardH * MIN_DOWN_RATIO, cardH * MIN_UP_RATIO)
        }

    /** Lane under horizontal position [x] (board coordinates), clamped to the table. */
    fun laneAt(x: Float, m: SpiderMetrics): Int = (x / m.lane).toInt().coerceIn(0, SPIDER_COLUMNS - 1)

    /** Where a finished run's cards rest: the left of the bottom strip, overlapping a little. */
    fun completedX(runIndex: Int, m: SpiderMetrics): Float = SpiderMetrics.STRIP_PAD + runIndex * m.cardW * 0.42f

    /** Stock row [rowFromNext] (0 = the next row to be dealt) rests at the right of the strip. */
    fun stockX(rowFromNext: Int, m: SpiderMetrics): Float = m.boardW - m.cardW - SpiderMetrics.STRIP_PAD - rowFromNext * m.cardW * 0.30f

    fun stripCardY(m: SpiderMetrics): Float = m.stripY + SpiderMetrics.STRIP_PAD

    /** Resting position of every card id in [s]: columns, finished runs, stock. Pure and complete. */
    fun placements(s: SpiderState, m: SpiderMetrics): Map<Int, Placement> {
        val out = HashMap<Int, Placement>(SPIDER_DECK_SIZE * 2)
        for (c in 0 until SPIDER_COLUMNS) {
            val x = columnX(c, m)
            s.columns[c].forEachIndexed { i, id ->
                out[id] = Placement(x, cardY(s, c, i, m), faceUp = i >= s.faceDown[c], column = c, index = i, z = i)
            }
        }
        s.completed.forEachIndexed { i, id ->
            val run = i / SPIDER_RUN_LENGTH
            out[id] = Placement(completedX(run, m), stripCardY(m), faceUp = true, column = -1, index = i % SPIDER_RUN_LENGTH, z = i)
        }
        s.stock.forEachIndexed { i, id ->
            val row = (s.stock.size - 1 - i) / SPIDER_COLUMNS
            out[id] = Placement(stockX(row, m), stripCardY(m), faceUp = false, column = -2, index = i, z = 200 - row)
        }
        return out
    }

    data class Placement(val x: Float, val y: Float, val faceUp: Boolean, val column: Int, val index: Int, val z: Int)
}
