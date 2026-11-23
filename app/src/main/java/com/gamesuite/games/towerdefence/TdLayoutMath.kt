package com.gamesuite.games.towerdefence

import kotlin.math.max
import kotlin.math.min

/** The three screen arrangements of Tower Defence (docs/design/canvas TDTabletFold). */
enum class TdLayoutKind {
    /** Narrow and tall (a phone held upright, a Fold cover screen): scrolling map zoomed in, castle pinned, minimap. */
    PHONE_PORTRAIT,

    /** Wide and short (a tablet or an unfolded Fold in landscape): the board beside a control column. */
    SPLIT_LANDSCAPE,

    /** Everything else (a square-ish inner Fold screen, a small landscape phone window): the board above its controls. */
    STACKED
}

/**
 * Pure layout math for the Tower Defence screen, JVM tested. Sizes are in dp (or any one unit).
 * The board is always 24 x 14 tiles ([TdGrid.TILES_X] by [TdGrid.TILES_Y]).
 */
object TdLayoutMath {
    const val ASPECT = TdGrid.TILES_X / TdGrid.TILES_Y
    /** Zoom of the scrolling portrait map: the viewport shows 1 / this share of the field's width. */
    const val PORTRAIT_ZOOM = 1.7f
    /** The side column of the landscape split. */
    const val SIDE_COLUMN_MIN = 300f
    const val SIDE_COLUMN_MAX = 380f

    fun kindFor(width: Float, height: Float): TdLayoutKind = when {
        width <= 0f || height <= 0f -> TdLayoutKind.STACKED
        width < 600f && height >= width * 1.2f -> TdLayoutKind.PHONE_PORTRAIT
        width >= 720f && width >= height * 1.25f -> TdLayoutKind.SPLIT_LANDSCAPE
        else -> TdLayoutKind.STACKED
    }

    fun zoomFor(kind: TdLayoutKind): Float = if (kind == TdLayoutKind.PHONE_PORTRAIT) PORTRAIT_ZOOM else 1f

    /** Width of the control column in the landscape split: about a third of the window, clamped. */
    fun sideColumnWidth(width: Float): Float = (width * 0.32f).coerceIn(SIDE_COLUMN_MIN, SIDE_COLUMN_MAX)

    /** The largest 24:14 board that fits [availW] x [availH]; at most [maxW] wide. */
    fun fitBoard(availW: Float, availH: Float, maxW: Float = 900f): Pair<Float, Float> {
        val w = max(0f, min(min(availW, availH * ASPECT), maxW))
        return w to w / ASPECT
    }

    /** Width of the full (zoomed) map content given the visible [viewportW]. */
    fun contentWidth(viewportW: Float, zoom: Float): Float = viewportW * zoom

    /** Largest scroll offset of the zoomed map (0 when nothing scrolls). */
    fun maxScroll(viewportW: Float, zoom: Float): Float = max(0f, contentWidth(viewportW, zoom) - viewportW)

    /** The scroll that shows the castle (the right edge); the screen starts there so the player sees what they defend. */
    fun castleScroll(viewportW: Float, zoom: Float): Float = maxScroll(viewportW, zoom)

    /** The visible share of the map, 0..1 (the minimap's viewport rectangle width). */
    fun visibleFraction(zoom: Float): Float = (1f / zoom.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** The minimap viewport rectangle's left edge, as a 0..1 fraction of the map. */
    fun viewportLeftFraction(scroll: Float, viewportW: Float, zoom: Float): Float {
        val content = contentWidth(viewportW, zoom)
        return if (content <= 0f) 0f else (scroll / content).coerceIn(0f, 1f - visibleFraction(zoom))
    }

    /** The scroll that centres the viewport on a tap at [mapFraction] (0..1 across the map) of the minimap, clamped. */
    fun scrollForMinimapTap(mapFraction: Float, viewportW: Float, zoom: Float): Float {
        val content = contentWidth(viewportW, zoom)
        return (mapFraction.coerceIn(0f, 1f) * content - viewportW / 2f).coerceIn(0f, maxScroll(viewportW, zoom))
    }

    /** True while the castle (the right edge) is inside the viewport, within [slack]; false means the pinned castle chip shows. */
    fun castleVisible(scroll: Float, viewportW: Float, zoom: Float, slack: Float = 8f): Boolean =
        scroll >= maxScroll(viewportW, zoom) - slack
}
