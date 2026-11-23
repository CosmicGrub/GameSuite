package com.gamesuite.games.partypack

/*
 * Where each player's controls live on the shared screen (Light-Trail Duel and Sumo Push). Pure geometry in
 * pixels, unit tested: the screens turn it into pads, and the pointer router uses the very same rectangles.
 *
 * A "zone" is a strip along one edge of the board that belongs to one seat. Its contents are laid out in the
 * seat's own LOCAL frame (as if that player sat at the bottom edge and read upright), then rotated onto the
 * screen: BOTTOM 0 degrees, TOP 180, LEFT 90 clockwise, RIGHT 270 clockwise, so every label faces its player.
 * With "face each player" off every human shares the bottom edge, upright, side by side.
 */

data class TsRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    fun intersects(o: TsRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun inside(o: TsRect, eps: Float = 0.01f): Boolean =
        left >= o.left - eps && right <= o.right + eps && top >= o.top - eps && bottom <= o.bottom + eps
}

enum class TsSide(val degrees: Float) { BOTTOM(0f), TOP(180f), LEFT(90f), RIGHT(270f) }

/** Which controls a zone holds. */
enum class TsPadSet {
    /** Light-Trail Duel: a Left pad and a Right pad. */
    TRAIL_PADS,

    /** Light-Trail Duel, one-switch: a single button. */
    TRAIL_ONE,

    /** Sumo Push: a thumb-stick and a dash button. */
    SUMO_STICK,

    /** Sumo Push: a direction pad and a dash button. */
    SUMO_DPAD,

    /** Sumo Push, one-switch: only a dash button (the sumo steers itself). */
    SUMO_ONE
}

data class TsPadSpec(val id: String, val kind: TsKind, val local: TsRect)

class TsZone(
    val seat: Int,
    val side: TsSide,
    val human: Boolean,
    /** The strip on the screen. */
    val screen: TsRect,
    /** The strip size in its own local frame (width runs along the strip). */
    val localWidth: Float,
    val localHeight: Float,
    val pads: List<TsPadSpec>
) {
    /** Maps a rectangle of the local frame to the screen (an axis-aligned box, since every rotation is a quarter turn). */
    fun toScreen(local: TsRect): TsRect {
        val cx = screen.centerX
        val cy = screen.centerY
        fun p(lx: Float, ly: Float): Pair<Float, Float> {
            val u = lx - localWidth / 2f
            val v = ly - localHeight / 2f
            return when (side) {
                TsSide.BOTTOM -> (cx + u) to (cy + v)
                TsSide.TOP -> (cx - u) to (cy - v)
                TsSide.LEFT -> (cx - v) to (cy + u)
                TsSide.RIGHT -> (cx + v) to (cy - u)
            }
        }
        val a = p(local.left, local.top)
        val b = p(local.right, local.bottom)
        return TsRect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    /** A screen-axis vector turned into this zone's local axes (for drawing a stick knob inside the rotated pad). */
    fun toLocalVector(sx: Float, sy: Float): Pair<Float, Float> = when (side) {
        TsSide.BOTTOM -> sx to sy
        TsSide.TOP -> -sx to -sy
        TsSide.LEFT -> sy to -sx
        TsSide.RIGHT -> -sy to sx
    }

    fun regions(): List<TsRegion> = pads.map { p ->
        val r = toScreen(p.local)
        TsRegion(p.id, seat, p.kind, r.left, r.top, r.right, r.bottom)
    }
}

class TsLayout(val board: TsRect, val zones: List<TsZone>) {
    fun regions(): List<TsRegion> = zones.flatMap { it.regions() }
}

object TsZoneLayout {
    /** Sides in seat order: the first two players face each other, the next two take the short edges. */
    val seatSides: List<TsSide> = listOf(TsSide.BOTTOM, TsSide.TOP, TsSide.LEFT, TsSide.RIGHT)

    fun sideOf(seat: Int): TsSide = seatSides[seat.coerceIn(0, 3)]

    /** The controls of one zone in its local frame. [m] is the margin between controls in pixels. */
    fun pads(set: TsPadSet, seat: Int, w: Float, h: Float, m: Float): List<TsPadSpec> {
        val fullSide = (h - 2 * m).coerceAtLeast(1f)
        // A stick plus its gap and dash button is 2.5 sides wide: shrink to fit a narrow zone.
        val side = if (set == TsPadSet.SUMO_STICK || set == TsPadSet.SUMO_DPAD) minOf(fullSide, ((w - 2 * m) / 2.5f).coerceAtLeast(1f)) else fullSide
        return when (set) {
            TsPadSet.TRAIL_PADS -> {
                val pw = minOf((w - 3 * m) / 2f, side * 3.2f).coerceAtLeast(1f)
                val x0 = w / 2f - m / 2f - pw
                val x1 = w / 2f + m / 2f
                listOf(
                    TsPadSpec("$seat:L", TsKind.BUTTON, TsRect(x0, m, x0 + pw, m + side)),
                    TsPadSpec("$seat:R", TsKind.BUTTON, TsRect(x1, m, x1 + pw, m + side))
                )
            }
            TsPadSet.TRAIL_ONE -> {
                val bw = minOf(w - 2 * m, side * 4f).coerceAtLeast(1f)
                listOf(TsPadSpec("$seat:S", TsKind.BUTTON, TsRect((w - bw) / 2f, m, (w + bw) / 2f, m + side)))
            }
            TsPadSet.SUMO_STICK, TsPadSet.SUMO_DPAD -> {
                val stickSide = side
                val dash = side
                val gap = side * 0.5f
                val total = stickSide + gap + dash
                val x0 = ((w - total) / 2f).coerceAtLeast(m)
                val kind = if (set == TsPadSet.SUMO_STICK) TsKind.STICK else TsKind.DPAD
                listOf(
                    TsPadSpec("$seat:move", kind, TsRect(x0, m, x0 + stickSide, m + stickSide)),
                    TsPadSpec(
                        "$seat:dash", TsKind.BUTTON,
                        TsRect(x0 + stickSide + gap, m + (stickSide - dash) / 2f, x0 + stickSide + gap + dash, m + (stickSide + dash) / 2f)
                    )
                )
            }
            TsPadSet.SUMO_ONE -> {
                val bw = minOf(w - 2 * m, side * 3f).coerceAtLeast(1f)
                listOf(TsPadSpec("$seat:dash", TsKind.BUTTON, TsRect((w - bw) / 2f, m, (w + bw) / 2f, m + side)))
            }
        }
    }

    /**
     * Lays out the screen of [width] x [height] pixels. [header] pixels at the top are kept for the status row.
     * [humans] says per seat whether a person plays it (a computer seat gets a slim label strip of [cpuThick]
     * pixels and no controls). [face] turns on "face each player"; off, every human shares the bottom edge.
     */
    fun compute(
        width: Float, height: Float, header: Float, humans: List<Boolean>, face: Boolean,
        set: TsPadSet, thick: Float, cpuThick: Float, margin: Float
    ): TsLayout {
        val n = humans.size.coerceIn(2, 4)
        val top0 = header.coerceIn(0f, height)
        val zones = ArrayList<TsZone>()
        var bottomT = 0f; var topT = 0f; var leftT = 0f; var rightT = 0f
        if (face) {
            for (seat in 0 until n) {
                val t = if (humans.getOrElse(seat) { false }) thick else cpuThick
                when (sideOf(seat)) {
                    TsSide.BOTTOM -> bottomT = t
                    TsSide.TOP -> topT = t
                    TsSide.LEFT -> leftT = t
                    TsSide.RIGHT -> rightT = t
                }
            }
            val innerTop = top0 + topT
            val innerBottom = (height - bottomT).coerceAtLeast(innerTop)
            for (seat in 0 until n) {
                val side = sideOf(seat)
                val human = humans.getOrElse(seat) { false }
                val rect = when (side) {
                    TsSide.BOTTOM -> TsRect(0f, height - bottomT, width, height)
                    TsSide.TOP -> TsRect(0f, top0, width, top0 + topT)
                    TsSide.LEFT -> TsRect(0f, innerTop, leftT, innerBottom)
                    TsSide.RIGHT -> TsRect(width - rightT, innerTop, width, innerBottom)
                }
                val lw = if (side == TsSide.LEFT || side == TsSide.RIGHT) rect.height else rect.width
                val lh = if (side == TsSide.LEFT || side == TsSide.RIGHT) rect.width else rect.height
                zones += TsZone(seat, side, human, rect, lw, lh, if (human) pads(set, seat, lw, lh, margin) else emptyList())
            }
        } else {
            val humanSeats = (0 until n).filter { humans.getOrElse(it) { false } }
            // Everybody shares the bottom edge, two to a row when there are more than two, so controls stay big.
            val perRow = if (humanSeats.size <= 2) humanSeats.size.coerceAtLeast(1) else 2
            val rows = if (humanSeats.isEmpty()) 0 else (humanSeats.size + perRow - 1) / perRow
            bottomT = thick * rows
            humanSeats.forEachIndexed { i, seat ->
                val row = i / perRow
                val inRow = minOf(perRow, humanSeats.size - row * perRow)
                val slot = width / inRow
                val col = i % perRow
                val rect = TsRect(col * slot, height - bottomT + row * thick, (col + 1) * slot, height - bottomT + (row + 1) * thick)
                zones += TsZone(seat, TsSide.BOTTOM, true, rect, rect.width, rect.height, pads(set, seat, rect.width, rect.height, margin))
            }
        }
        val inner = TsRect(leftT, top0 + topT, width - rightT, (height - bottomT).coerceAtLeast(top0 + topT))
        val side = minOf(inner.width, inner.height).coerceAtLeast(0f)
        val board = TsRect(inner.centerX - side / 2f, inner.centerY - side / 2f, inner.centerX + side / 2f, inner.centerY + side / 2f)
        return TsLayout(board, zones)
    }
}
