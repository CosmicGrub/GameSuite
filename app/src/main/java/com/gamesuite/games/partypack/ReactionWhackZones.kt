package com.gamesuite.games.partypack

import kotlin.math.max
import kotlin.math.min

/**
 * Same-screen zones for Reaction Duel and Whack-a-Mole Race: where each player's zone sits on the shared screen
 * and which way it faces. Pure Kotlin (no Compose), so the geometry and the multi-touch bookkeeping are unit tested.
 *
 * Seats and sides. Seat 0 always sits at the bottom edge (the person holding or facing the device), seat 1 across
 * from them at the top. With the big-screen edge layout seat 2 takes the left edge and seat 3 the right edge, each
 * facing inwards; on a compact screen (a phone) three or four seats share a 2x2 grid whose top row is turned to face
 * the far edge. With `orient` off every zone faces the bottom.
 */
data class ReactionWhackZone(
    val seat: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Clockwise degrees the zone's CONTENT is turned so it reads upright to its player: 0, 90, 180 or 270. */
    val rotation: Int
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Size of the content frame (width and height swap for a quarter turn). */
    val contentWidth: Float get() = if (rotation % 180 == 0) width else height
    val contentHeight: Float get() = if (rotation % 180 == 0) height else width
}

/** The zones plus, for an edge layout, the free rectangle (left, top, right, bottom) left in the middle. */
class ReactionWhackLayout(val zones: List<ReactionWhackZone>, val hub: FloatArray?)

object ReactionWhackZones {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 4

    /** Fraction of the area's height an edge strip (top or bottom zone) takes, and of its width a side zone takes. */
    private const val EDGE_STRIP = 0.30f
    private const val EDGE_SIDE = 0.26f

    /**
     * Lays [players] zones out over a [width] x [height] area. [edge] selects the one-zone-per-edge layout (only
     * meaningful for three or four players; the screen turns it on when the area is big enough for four upright
     * grids), [orient] makes each zone face its own player.
     */
    fun layout(players: Int, width: Float, height: Float, orient: Boolean, edge: Boolean): ReactionWhackLayout {
        val n = players.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        fun rot(r: Int) = if (orient) r else 0
        if (n == 2) {
            val half = height / 2f
            return ReactionWhackLayout(
                listOf(
                    ReactionWhackZone(0, 0f, half, width, height, 0),
                    ReactionWhackZone(1, 0f, 0f, width, half, rot(180))
                ),
                null
            )
        }
        if (edge) {
            val strip = height * EDGE_STRIP
            val side = width * EDGE_SIDE
            val midTop = strip
            val midBottom = height - strip
            val zones = mutableListOf(
                ReactionWhackZone(0, 0f, midBottom, width, height, 0),
                ReactionWhackZone(1, 0f, 0f, width, midTop, rot(180)),
                ReactionWhackZone(2, 0f, midTop, side, midBottom, rot(90))
            )
            if (n == 4) zones += ReactionWhackZone(3, width - side, midTop, width, midBottom, rot(270))
            val hubRight = if (n == 4) width - side else width
            return ReactionWhackLayout(zones, floatArrayOf(side, midTop, hubRight, midBottom))
        }
        val halfW = width / 2f
        val halfH = height / 2f
        val zones = mutableListOf(
            ReactionWhackZone(0, 0f, halfH, halfW, height, 0),
            ReactionWhackZone(1, halfW, halfH, width, height, 0)
        )
        if (n == 3) {
            zones += ReactionWhackZone(2, 0f, 0f, width, halfH, rot(180))
        } else {
            zones += ReactionWhackZone(2, halfW, 0f, width, halfH, rot(180))
            zones += ReactionWhackZone(3, 0f, 0f, halfW, halfH, rot(180))
        }
        return ReactionWhackLayout(zones, null)
    }

    /** The seat whose zone holds the point, or -1. */
    fun zoneAt(zones: List<ReactionWhackZone>, x: Float, y: Float): Int =
        zones.firstOrNull { it.contains(x, y) }?.seat ?: -1

    /**
     * A point in screen coordinates as seen in the zone's own (upright) content frame, which is what the zone's
     * buttons are drawn in. The inverse of the clockwise quarter turn the screen applies to the content.
     */
    fun toContent(zone: ReactionWhackZone, x: Float, y: Float): Pair<Float, Float> {
        val lx = x - zone.left
        val ly = y - zone.top
        val w = zone.width
        val h = zone.height
        return when (zone.rotation) {
            90 -> ly to (w - lx)
            180 -> (w - lx) to (h - ly)
            270 -> (h - ly) to lx
            else -> lx to ly
        }
    }

    /** The largest grid size (at most [requested], at least 3) whose square cells are all at least [minCellPx] in a content frame below a header of [reservedPx]. */
    fun maxGridThatFits(contentWidth: Float, contentHeight: Float, requested: Int, minCellPx: Float, reservedPx: Float): Int {
        var g = requested
        while (g > 3) {
            if (cellSize(contentWidth, contentHeight, g, reservedPx) >= minCellPx) break
            g--
        }
        return g
    }

    /** Largest square cell a [grid] x [grid] board can use in the content frame below a header of [reservedPx]. */
    fun cellSize(contentWidth: Float, contentHeight: Float, grid: Int, reservedPx: Float): Float =
        max(0f, min(contentWidth / grid, (contentHeight - reservedPx) / grid))
}

/**
 * Per-pointer bookkeeping for several fingers on one screen. Every finger is bound to the zone it went DOWN in and
 * stays that zone's finger until it lifts or is cancelled, however far it drifts: a thumb sliding over the middle
 * line never presses the other player's button, and one finger lifting never disturbs another. Pointer ids are the
 * platform's (Compose's `PointerId.value`).
 */
class ReactionWhackPointerMap(private val zoneAt: (Float, Float) -> Int) {
    private val owner = HashMap<Long, Int>()

    val activePointers: Int get() = owner.size

    /** A finger touched down: the zone it is now bound to, or -1 (outside every zone; it stays unbound). A repeated down for a known id rebinds it. */
    fun down(id: Long, x: Float, y: Float): Int {
        val z = zoneAt(x, y)
        if (z < 0) owner.remove(id) else owner[id] = z
        return z
    }

    /** The zone a finger is bound to, or -1. */
    fun zoneOf(id: Long): Int = owner[id] ?: -1

    /** True while the finger is still inside the zone it is bound to (a finger that left is "off the button"). */
    fun stillInside(id: Long, x: Float, y: Float): Boolean {
        val z = owner[id] ?: return false
        return zoneAt(x, y) == z
    }

    /** A finger lifted: the zone it was bound to (or -1); it is unbound. Other fingers are untouched. */
    fun up(id: Long): Int = owner.remove(id) ?: -1

    fun cancel(id: Long): Int = up(id)

    /**
     * Drops every bound finger that is no longer in [present] (the pointer ids the latest event still reports) and
     * returns the zones that lost one. A cancelled gesture or a lost event never leaves a finger bound forever.
     */
    fun reconcile(present: Collection<Long>): List<Int> {
        val gone = owner.keys.filter { it !in present }
        return gone.mapNotNull { owner.remove(it) }
    }

    fun pointersIn(zone: Int): Int = owner.values.count { it == zone }

    fun clear() = owner.clear()
}
