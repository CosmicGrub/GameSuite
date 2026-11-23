package com.gamesuite.games.partypack

/**
 * Where the seats of a same-screen party game sit around the device. Shared by Paddle Duel and
 * Rock-Paper-Showdown (pure Kotlin: no Compose, so the layout rules are unit tested).
 *
 * Seat 0 is always the player at the bottom (the one holding the device the usual way). Two people
 * face each other (bottom / top); three take the bottom and the two sides; four take every edge.
 */
enum class PpEdge {
    BOTTOM, TOP, LEFT, RIGHT;

    val isHorizontal: Boolean get() = this == BOTTOM || this == TOP
}

object PpSeatLayout {
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4

    fun edges(seats: Int): List<PpEdge> = when (seats.coerceIn(MIN_SEATS, MAX_SEATS)) {
        2 -> listOf(PpEdge.BOTTOM, PpEdge.TOP)
        3 -> listOf(PpEdge.BOTTOM, PpEdge.LEFT, PpEdge.RIGHT)
        else -> listOf(PpEdge.BOTTOM, PpEdge.TOP, PpEdge.LEFT, PpEdge.RIGHT)
    }

    /** Clockwise degrees a seat's text turns so it reads upright for a person sitting at the SCREEN edge [edge]. */
    fun facingDegrees(edge: PpEdge): Float = when (edge) {
        PpEdge.BOTTOM -> 0f
        PpEdge.TOP -> 180f
        PpEdge.LEFT -> 90f
        PpEdge.RIGHT -> 270f
    }

    /** Quarter turns (clockwise) for [facingDegrees]; 0..3. */
    fun quarterTurns(edge: PpEdge): Int = (facingDegrees(edge) / 90f).toInt()
}
