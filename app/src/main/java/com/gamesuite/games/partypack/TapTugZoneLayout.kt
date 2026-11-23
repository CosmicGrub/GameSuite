package com.gamesuite.games.partypack

/** One player's zone in unit coordinates (0..1 of the board); [rotated] zones are drawn upside down to face the player across the table. */
data class TapTugZone(val seat: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val rotated: Boolean) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun toRect(widthPx: Float, heightPx: Float) = TapTugRect(left * widthPx, top * heightPx, right * widthPx, bottom * heightPx)
}

/**
 * Where the zones go. Everyone gets a rectangle of the screen: the seats of the bottom row (seat 0 first) face
 * the near edge, the seats of the top row face the far edge and are rotated half a turn so players sitting opposite
 * each other see their own zone upright. A horizontal [band] of the board is left in the middle for the shared
 * status (the rope, the traffic light). Two players are bottom and top, three are one below and two above, four
 * are two and two. With `rotateTop` off every zone is upright (one person holding the device).
 */
object TapTugZoneLayout {
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4

    /** Seats in the bottom row for [seats] seats with no teams: 1, 1, 2 for 2, 3, 4. */
    fun defaultBottomCount(seats: Int): Int = (seats.coerceIn(MIN_SEATS, MAX_SEATS)) / 2

    fun rows(bottom: Int, top: Int, rotateTop: Boolean, band: Float): List<TapTugZone> {
        require(bottom >= 1 && top >= 1) { "both rows need a seat" }
        val b = band.coerceIn(0f, 0.5f)
        val topEnd = 0.5f - b / 2f
        val bottomStart = 0.5f + b / 2f
        val out = ArrayList<TapTugZone>(bottom + top)
        for (i in 0 until bottom) {
            out += TapTugZone(i, i.toFloat() / bottom, bottomStart, (i + 1f) / bottom, 1f, rotated = false)
        }
        for (i in 0 until top) {
            out += TapTugZone(bottom + i, i.toFloat() / top, 0f, (i + 1f) / top, topEnd, rotated = rotateTop)
        }
        return out
    }

    /** The shared strip between the two rows. */
    fun bandRect(band: Float): TapTugZone {
        val b = band.coerceIn(0f, 0.5f)
        return TapTugZone(-1, 0f, 0.5f - b / 2f, 1f, 0.5f + b / 2f, rotated = false)
    }

    /** Free-for-all (or no teams) for [seats] players. */
    fun forSeats(seats: Int, rotateTop: Boolean, band: Float): List<TapTugZone> {
        val n = seats.coerceIn(MIN_SEATS, MAX_SEATS)
        val bottom = defaultBottomCount(n)
        return rows(bottom, n - bottom, rotateTop, band)
    }
}
