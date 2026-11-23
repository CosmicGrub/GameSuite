package com.gamesuite.games.reactionduel

import kotlin.math.min

/**
 * Where the four echo pads sit inside a zone's pad area (a 2 x 2 grid: circle and square on top, triangle and
 * diamond below), shared by the drawing and the touch hit-test so they can never disagree. Pure Kotlin.
 */
object DuelPadGrid {
    /** The gap between pads, as a fraction of the smaller side of the area. */
    private const val GAP = 0.05f

    /** The pad's rectangle as (left, top, right, bottom) inside an area of [w] x [h] whose corner is (0, 0). */
    fun rect(index: Int, w: Float, h: Float): FloatArray {
        val gap = min(w, h) * GAP
        val cw = (w - gap) / 2f
        val ch = (h - gap) / 2f
        val col = index % 2
        val row = index / 2
        val l = col * (cw + gap)
        val t = row * (ch + gap)
        return floatArrayOf(l, t, l + cw, t + ch)
    }

    /** The pad under ([x], [y]) in the area, or -1 for the gap or outside. */
    fun padAt(x: Float, y: Float, w: Float, h: Float): Int {
        for (i in 0 until DuelSymbol.ECHO_PADS) {
            val r = rect(i, w, h)
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i
        }
        return -1
    }
}
