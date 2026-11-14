package com.gamesuite.games.drawguess

import kotlin.math.abs
import kotlin.math.roundToInt

/** Hard limits that keep a drawing (and so a save file and the per-frame cost) bounded. */
object DrawLimits {
    /** Points are stored as integers 0..COORD_MAX across the canvas (a 4:3 box), so a drawing is resolution independent. */
    const val COORD_MAX = 1000

    /** The canvas is 4:3, so y runs 0..750 (y is also a fraction of the canvas WIDTH, which keeps brushes round at any size). */
    const val COORD_MAX_Y = 750
    const val MAX_STROKES = 300
    const val MAX_POINTS_PER_STROKE = 400
    const val MAX_TOTAL_POINTS = 6000

    /**
     * Eraser strokes have their own budget (same size), so fixing a mistake never uses up the room for ink. They are still
     * capped, to keep a drawing and its save file bounded.
     */
    const val MAX_ERASER_STROKES = 300
    const val MAX_ERASER_POINTS = 6000

    /** A new point closer than this (sum of the two axis steps, in coordinate units) to the last one is dropped. */
    const val MIN_STEP = 3
    const val PALETTE_SIZE = 6
    const val BRUSH_COUNT = 3

    fun quantise(v: Float): Int = (v.coerceIn(0f, 1f) * COORD_MAX).roundToInt()
    fun quantiseY(v: Float): Int = quantise(v).coerceAtMost(COORD_MAX_Y)
    fun dequantise(q: Int): Float = q / COORD_MAX.toFloat()

    /** Pointer coordinate [px] over a canvas [width] pixels wide, as a 0..1 fraction of the width (use it for x AND y; y tops out at 0.75). */
    fun normalise(px: Float, width: Float): Float = if (width <= 0f) 0f else (px / width).coerceIn(0f, 1f)
}

/** Six colours from the Okabe-Ito colour-blind-safe set (yellow left out: it vanishes on white) and three brush widths (fractions of the canvas width). */
object DrawPalette {
    val colors: LongArray = longArrayOf(0xFF1F1F1FL, 0xFF0072B2L, 0xFFD55E00L, 0xFF009E73L, 0xFFCC79A7L, 0xFFE69F00L)
    val brushFractions: FloatArray = floatArrayOf(0.010f, 0.022f, 0.048f)
    const val PAPER: Long = 0xFFFFFFFFL
}

/** One finished stroke: palette index, brush index, whether it is the eraser (paints the paper colour), and x,y,x,y... in 0..COORD_MAX. */
class DrawStroke(val color: Int, val size: Int, val eraser: Boolean, val xy: IntArray) {
    val pointCount: Int get() = xy.size / 2
}

/**
 * The drawing, independent of Compose. A stroke is started with [begin], grown with [extend] (which thins points and
 * enforces the caps, reusing one growable buffer so a drag allocates nothing per point) and committed with [end].
 * Committed strokes are immutable; [undo] drops the last one, [clear] all of them.
 */
class DrawingBoard {
    private val committed = ArrayList<DrawStroke>()
    private var buf = IntArray(64)
    private var n = 0

    val strokes: List<DrawStroke> get() = committed
    /** Points of every committed stroke, ink and eraser. */
    var totalPoints: Int = 0
        private set
    private var inkStrokes = 0
    private var inkPoints = 0
    private var eraserStrokes = 0
    private var eraserPoints = 0

    /** True once a cap cut the stroke being drawn (or refused to start one) since the last begin, undo or clear: the screen says "page is full". */
    var capHit: Boolean = false
        private set
    var drawing: Boolean = false
        private set
    var activeColor: Int = 0
        private set
    var activeSize: Int = 1
        private set
    var activeEraser: Boolean = false
        private set

    val activePointCount: Int get() = n
    fun activeX(i: Int): Int = buf[2 * i]
    fun activeY(i: Int): Int = buf[2 * i + 1]

    /** True when no further INK stroke can start: the page is full (undo or clear to go on). Eraser strokes have their own budget, see [isFullFor]. */
    val isFull: Boolean get() = isFullFor(false)

    /** True when no further stroke of that kind ([eraser] or ink) can start. */
    fun isFullFor(eraser: Boolean): Boolean =
        if (eraser) eraserStrokes >= DrawLimits.MAX_ERASER_STROKES || eraserPoints >= DrawLimits.MAX_ERASER_POINTS
        else inkStrokes >= DrawLimits.MAX_STROKES || inkPoints >= DrawLimits.MAX_TOTAL_POINTS

    fun begin(color: Int, size: Int, eraser: Boolean, nx: Float, ny: Float): Boolean {
        if (drawing) end()
        if (isFullFor(eraser)) { capHit = true; return false }
        if (color !in 0 until DrawLimits.PALETTE_SIZE || size !in 0 until DrawLimits.BRUSH_COUNT) return false
        capHit = false
        activeColor = color; activeSize = size; activeEraser = eraser
        n = 0
        put(DrawLimits.quantise(nx), DrawLimits.quantiseY(ny))
        drawing = true
        return true
    }

    /** Adds a point; false when it was dropped (too close to the last one, or a cap was reached). */
    fun extend(nx: Float, ny: Float): Boolean {
        if (!drawing) return false
        val budgetLeft = if (activeEraser) inkFree(eraserPoints, DrawLimits.MAX_ERASER_POINTS) else inkFree(inkPoints, DrawLimits.MAX_TOTAL_POINTS)
        if (n >= DrawLimits.MAX_POINTS_PER_STROKE || n >= budgetLeft) { capHit = true; return false }
        val qx = DrawLimits.quantise(nx)
        val qy = DrawLimits.quantiseY(ny)
        if (abs(qx - buf[2 * (n - 1)]) + abs(qy - buf[2 * (n - 1) + 1]) < DrawLimits.MIN_STEP) return false
        put(qx, qy)
        return true
    }

    private fun inkFree(used: Int, max: Int): Int = (max - used).coerceAtLeast(0)

    private fun put(x: Int, y: Int) {
        if (2 * n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[2 * n] = x
        buf[2 * n + 1] = y
        n++
    }

    /** Commits the stroke being drawn. */
    fun end(): Boolean {
        if (!drawing) return false
        drawing = false
        if (n == 0) return false
        committed += DrawStroke(activeColor, activeSize, activeEraser, buf.copyOf(2 * n))
        account(committed.last(), +1)
        n = 0
        return true
    }

    private fun account(s: DrawStroke, sign: Int) {
        totalPoints += sign * s.pointCount
        if (s.eraser) { eraserStrokes += sign; eraserPoints += sign * s.pointCount }
        else { inkStrokes += sign; inkPoints += sign * s.pointCount }
    }

    /** Takes back the last finished stroke. Ignored while a finger is down. */
    fun undo(): Boolean {
        if (drawing || committed.isEmpty()) return false
        account(committed.removeAt(committed.lastIndex), -1)
        capHit = false
        return true
    }

    fun clear() {
        committed.clear()
        totalPoints = 0
        inkStrokes = 0; inkPoints = 0; eraserStrokes = 0; eraserPoints = 0
        capHit = false
        drawing = false
        n = 0
    }

    /** Replaces the drawing with [list] if every stroke and the totals are within the limits; otherwise changes nothing. */
    fun load(list: List<DrawStroke>): Boolean {
        if (!validate(list)) return false
        clear()
        committed.addAll(list)
        for (s in list) account(s, +1)
        return true
    }

    companion object {
        fun validStroke(s: DrawStroke): Boolean =
            s.color in 0 until DrawLimits.PALETTE_SIZE && s.size in 0 until DrawLimits.BRUSH_COUNT &&
                s.xy.size >= 2 && s.xy.size % 2 == 0 && s.pointCount <= DrawLimits.MAX_POINTS_PER_STROKE &&
                s.xy.indices.all { it2 -> s.xy[it2] in 0..(if (it2 % 2 == 0) DrawLimits.COORD_MAX else DrawLimits.COORD_MAX_Y) }

        fun validate(list: List<DrawStroke>): Boolean {
            if (!list.all { validStroke(it) }) return false
            val ink = list.filter { !it.eraser }
            val erase = list.filter { it.eraser }
            return ink.size <= DrawLimits.MAX_STROKES && ink.sumOf { it.pointCount } <= DrawLimits.MAX_TOTAL_POINTS &&
                erase.size <= DrawLimits.MAX_ERASER_STROKES && erase.sumOf { it.pointCount } <= DrawLimits.MAX_ERASER_POINTS
        }
    }
}
