package com.gamesuite.games.findit

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** One card on the shared table: its centre, radius (same unit as the table) and the angle its "up" is turned to. */
data class FindItCardPlace(val cx: Float, val cy: Float, val radius: Float, val rotationDeg: Float) {
    /** A point of the table as a point of this card's unit disc (rotation undone), 0,0 at the card centre. */
    fun toUnit(x: Float, y: Float): Pair<Float, Float> {
        val a = -rotationDeg * PI.toFloat() / 180f
        val dx = x - cx
        val dy = y - cy
        return ((dx * cos(a) - dy * sin(a)) / radius) to ((dx * sin(a) + dy * cos(a)) / radius)
    }

    /** A point of the unit disc as a point of the table. */
    fun fromUnit(ux: Float, uy: Float): Pair<Float, Float> {
        val a = rotationDeg * PI.toFloat() / 180f
        return (cx + radius * (ux * cos(a) - uy * sin(a))) to (cy + radius * (ux * sin(a) + uy * cos(a)))
    }

    fun contains(x: Float, y: Float): Boolean = hypot(x - cx, y - cy) <= radius
}

/** The middle card and every seat's card for one table size. [portrait] says how the seats were arranged. */
data class FindItTable(val centre: FindItCardPlace, val seats: List<FindItCardPlace>, val portrait: Boolean)

/**
 * Geometry of the same-screen table (pure, unit agnostic: pass dp or px). Portrait: seats at the bottom (upright) and
 * top (turned 180 degrees), the middle card between. Landscape and tablet: seats on the left (turned 90) and right
 * (turned 270). A lone seat (Solo Dash) is never turned.
 */
object FindItTableLayout {
    fun compute(seatCount: Int, w: Float, h: Float): FindItTable {
        val n = seatCount.coerceIn(1, 4)
        val portrait = h >= w
        if (n == 1) {
            return if (portrait) {
                val d = min(w * 0.92f, h * 0.46f)
                val pad = h * 0.02f
                FindItTable(
                    centre = FindItCardPlace(w / 2, pad + d * 0.5f, d * 0.5f, 0f),
                    seats = listOf(FindItCardPlace(w / 2, h - pad - d * 0.5f, d * 0.5f, 0f)),
                    portrait = true
                )
            } else {
                val d = min(h * 0.92f, w * 0.46f)
                FindItTable(
                    centre = FindItCardPlace(w * 0.27f, h / 2, d * 0.5f, 0f),
                    seats = listOf(FindItCardPlace(w * 0.73f, h / 2, d * 0.5f, 0f)),
                    portrait = false
                )
            }
        }
        return if (portrait) {
            val cols = if (n >= 3) 2 else 1
            val d = min(w / cols * 0.96f, h / 3f * 0.96f)
            val r = d / 2
            val pad = h * 0.015f
            val yBottom = h - pad - r
            val yTop = pad + r
            val left = w * 0.25f
            val right = w * 0.75f
            val mid = w * 0.5f
            val seats = when (n) {
                2 -> listOf(FindItCardPlace(mid, yBottom, r, 0f), FindItCardPlace(mid, yTop, r, 180f))
                3 -> listOf(FindItCardPlace(left, yBottom, r, 0f), FindItCardPlace(right, yBottom, r, 0f), FindItCardPlace(mid, yTop, r, 180f))
                else -> listOf(
                    FindItCardPlace(left, yBottom, r, 0f), FindItCardPlace(right, yBottom, r, 0f),
                    FindItCardPlace(right, yTop, r, 180f), FindItCardPlace(left, yTop, r, 180f)
                )
            }
            FindItTable(FindItCardPlace(mid, h / 2, r * 0.95f, 0f), seats, true)
        } else {
            val rowsPerSide = if (n >= 3) 2 else 1
            val d = min(h / rowsPerSide * 0.96f, w / 3f * 0.96f)
            val r = d / 2
            val pad = w * 0.015f
            val xLeft = pad + r
            val xRight = w - pad - r
            val top = h * 0.25f
            val bottom = h * 0.75f
            val mid = h * 0.5f
            val seats = when (n) {
                2 -> listOf(FindItCardPlace(xLeft, mid, r, 90f), FindItCardPlace(xRight, mid, r, 270f))
                3 -> listOf(FindItCardPlace(xLeft, top, r, 90f), FindItCardPlace(xLeft, bottom, r, 90f), FindItCardPlace(xRight, mid, r, 270f))
                else -> listOf(
                    FindItCardPlace(xLeft, top, r, 90f), FindItCardPlace(xLeft, bottom, r, 90f),
                    FindItCardPlace(xRight, bottom, r, 270f), FindItCardPlace(xRight, top, r, 270f)
                )
            }
            FindItTable(FindItCardPlace(w / 2, h / 2, r * 0.95f, 0f), seats, false)
        }
    }

    /**
     * The largest card size, never above [desired], whose smallest cell stays at least 56 dp across on a table of
     * [wDp] x [hDp] with [seatCount] seats; TINY when even that does not fit (the layout then still uses the room it has).
     */
    fun fittingSize(desired: FindItCardSize, seatCount: Int, wDp: Float, hDp: Float): FindItCardSize {
        var size = desired
        while (true) {
            val d = compute(seatCount, wDp, hDp).seats.minOf { it.radius * 2 }
            if (d >= ProjectiveDeck.minCardDiameterDp(size) || size == FindItCardSize.TINY) return size
            size = size.smaller()
        }
    }

    /** The seat whose card holds table point ([x], [y]), or -1. */
    fun seatAt(table: FindItTable, x: Float, y: Float): Int = table.seats.indexOfFirst { it.contains(x, y) }

    /**
     * The picture a tap at table point ([x], [y]) selects on seat [seat]'s card: the slot index into [layout] or -1
     * (a tap in a gap or on another card is never a wrong tap).
     */
    fun slotAt(table: FindItTable, seat: Int, size: FindItCardSize, layout: List<FindItCardSlot>, x: Float, y: Float): Int {
        val place = table.seats.getOrNull(seat) ?: return -1
        if (!place.contains(x, y)) return -1
        val (ux, uy) = place.toUnit(x, y)
        return ProjectiveDeck.hitSlot(size, layout, ux, uy)
    }

    /** Hunt scene hit test: the index of the item a tap at scene point ([x], [y]) selects (nearest within 1.15 radii), or -1. */
    fun sceneItemAt(scene: FindItScene, x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in scene.items.indices) {
            val it = scene.items[i]
            val d = hypot(it.x - x, it.y - y)
            val limit = 1.15f * FindItScene.ITEM_RADIUS * it.scale
            if (d <= limit && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Angle in degrees (0 = right, clockwise) of a slot around its card, for scan ordering. */
    internal fun angleOf(slot: FindItCardSlot): Float = (atan2(slot.cy, slot.cx) * 180f / PI.toFloat() + 360f) % 360f
}
