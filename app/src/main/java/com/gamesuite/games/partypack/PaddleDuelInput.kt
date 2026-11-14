package com.gamesuite.games.partypack

/**
 * The multi-touch input model of Paddle Duel as a pure class (no Compose), so "all fingers at once"
 * is unit tested instead of hoped for.
 *
 * RULES
 *  - A pointer is bound to the seat whose zone it first touched, and stays bound until it lifts or is
 *    cancelled, even if the finger slides into a neighbour's zone.
 *  - A seat is steered by at most one pointer. A second finger landing in a zone that is already steered
 *    is not bound; if the steering finger lifts, that spare finger takes over on its next move (so lifting
 *    one finger never leaves a seat dead while another finger of the same player is still down).
 *  - Lifting or cancelling a pointer never affects any other pointer.
 *  - A seat that is out of the game (or has no zone) accepts no pointer.
 */
class PdPointerRouter(private val zoneAt: (x: Double, y: Double) -> Int) {
    private val seatOf = HashMap<Long, Int>()
    private val steering = HashMap<Int, Long>()

    /** Bound pointer ids by seat (for tests and the debug overlay). */
    fun boundSeat(pointerId: Long): Int? = seatOf[pointerId]

    fun pointerFor(seat: Int): Long? = steering[seat]

    val activePointers: Int get() = seatOf.size

    /** A finger went down at (x, y). Returns the seat it now steers, or null when it steers nothing. */
    fun down(pointerId: Long, x: Double, y: Double): Int? {
        seatOf[pointerId]?.let { return it }
        return tryBind(pointerId, x, y)
    }

    /** A finger moved. Returns the seat it steers (binding it first if a seat became free), or null. */
    fun move(pointerId: Long, x: Double, y: Double): Int? {
        seatOf[pointerId]?.let { return it }
        return tryBind(pointerId, x, y)
    }

    /** A finger lifted or the system cancelled it. Returns the seat it was steering, now free. */
    fun up(pointerId: Long): Int? {
        val seat = seatOf.remove(pointerId) ?: return null
        if (steering[seat] == pointerId) steering.remove(seat)
        return seat
    }

    /** Drops every pointer (the whole gesture was cancelled, the window lost focus). Returns the seats that were steered. */
    fun cancelAll(): List<Int> {
        val seats = steering.keys.toList()
        seatOf.clear()
        steering.clear()
        return seats
    }

    private fun tryBind(pointerId: Long, x: Double, y: Double): Int? {
        val seat = zoneAt(x, y)
        if (seat < 0 || steering.containsKey(seat)) return null
        seatOf[pointerId] = seat
        steering[seat] = pointerId
        return seat
    }
}

/**
 * Screen geometry of the arena: where the logical rectangle sits in a [viewW] x [viewH] pixel box, and
 * which seat a pixel belongs to. With [rotated] (two players on a wide screen) the arena is turned so the
 * ball flies left and right: logical x runs down the screen and logical y runs right to left, which puts
 * the bottom seat on the LEFT edge and the top seat on the RIGHT edge.
 */
class PdViewTransform(
    val seats: Int,
    val rotated: Boolean,
    val viewW: Double,
    val viewH: Double,
    private val margin: Double = 0.0,
    private val alive: (Int) -> Boolean = { true }
) {
    private val arenaW = PdRules.ARENA_WIDTH
    private val arenaH = PdRules.arenaHeight(seats)
    private val drawW = if (rotated) arenaH else arenaW
    private val drawH = if (rotated) arenaW else arenaH
    val scale: Double = maxOf(0.0, minOf((viewW - 2 * margin) / drawW, (viewH - 2 * margin) / drawH))
    val originX: Double = (viewW - drawW * scale) / 2.0
    val originY: Double = (viewH - drawH * scale) / 2.0
    private val edges = PpSeatLayout.edges(seats)

    fun toScreenX(lx: Double, ly: Double): Double = originX + (if (rotated) (arenaH - ly) else lx) * scale
    fun toScreenY(lx: Double, ly: Double): Double = originY + (if (rotated) lx else ly) * scale

    fun toLogicalX(px: Double, py: Double): Double = if (scale <= 0.0) 0.0 else if (rotated) (py - originY) / scale else (px - originX) / scale
    fun toLogicalY(px: Double, py: Double): Double = if (scale <= 0.0) 0.0 else if (rotated) arenaH - (px - originX) / scale else (py - originY) / scale

    /** The edge of the SCREEN where logical edge [e] is drawn. */
    fun screenEdge(e: PpEdge): PpEdge = if (!rotated) e else when (e) {
        PpEdge.BOTTOM -> PpEdge.LEFT
        PpEdge.TOP -> PpEdge.RIGHT
        PpEdge.LEFT -> PpEdge.TOP
        PpEdge.RIGHT -> PpEdge.BOTTOM
    }

    /** The seat a pixel steers: the live seat whose edge is nearest, or -1 when no seat is left. Pixels beyond the arena count as its nearest side. */
    fun zoneAt(px: Double, py: Double): Int {
        val lx = toLogicalX(px, py)
        val ly = toLogicalY(px, py)
        var best = -1
        var bestD = Double.MAX_VALUE
        for (s in edges.indices) {
            if (!alive(s)) continue
            val d = when (edges[s]) {
                PpEdge.BOTTOM -> arenaH - ly
                PpEdge.TOP -> ly
                PpEdge.LEFT -> lx
                PpEdge.RIGHT -> arenaW - lx
            }
            if (d < bestD) { bestD = d; best = s }
        }
        return best
    }

    /** The screen direction (dx, dy; y down) in which the paddle's "along" coordinate increases for [seat]. */
    fun alongPlus(seat: Int): Pair<Int, Int> = if (rotated || !edges[seat].isHorizontal) 0 to 1 else 1 to 0

    /** The paddle centre (along the seat's own edge) a finger at (px, py) asks for. */
    fun alongFor(seat: Int, px: Double, py: Double): Double =
        if (edges[seat].isHorizontal) toLogicalX(px, py) else toLogicalY(px, py)
}
