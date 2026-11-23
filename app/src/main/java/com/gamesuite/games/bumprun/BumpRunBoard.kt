package com.gamesuite.games.bumprun

/**
 * The three game lengths (the Hub's `length` choice): how many pawns each seat races home.
 * QUICK 2, CLASSIC 3, LONG 4.
 */
enum class BumpLength(val id: String, val pawns: Int) {
    QUICK("quick", 2),
    CLASSIC("classic", 3),
    LONG("long", 4);

    companion object {
        /** Unknown or null ids fall back to QUICK (a stale saved Hub choice must never crash). */
        fun fromId(id: String?): BumpLength = values().firstOrNull { it.id == id } ?: QUICK
    }
}

/**
 * The board: a ring of 36 squares (the edge of a 10 x 10 grid, numbered clockwise from the top-left corner), one
 * side per seat, each with a private 3-square lane to Home. Pure geometry, no Compose.
 *
 * A pawn's PROGRESS is its whole journey as one number: [NEST] (-1), then 0 (its exit square) up to 35 around the
 * ring, 36..38 the lane, [HOME] (39). The ring square of progress p for a seat on side s is
 * `(EXIT_SQUARES[s] + p) % LOOP` (only for p in 0..35). Lane squares belong to one seat and are never shared.
 * Full diagram and tables: docs/design/boardgames/bump-run.md.
 */
object BumpRunBoard {
    const val LOOP = 36
    const val NEST = -1
    const val LANE_START = 36
    const val LANE_SQUARES = 3
    const val HOME = 39
    const val MAX_SEATS = 4

    /** The ring square a pawn lands on when it leaves the Nest, per side 0..3. */
    val EXIT_SQUARES: List<Int> = listOf(5, 14, 23, 32)

    /** The last ring square before a side's lane (the square just before its exit square). */
    val LANE_ENTRY_SQUARES: List<Int> = listOf(4, 13, 22, 31)

    /** Spring pad squares: a pawn that lands on one hops [PAD_HOP] squares further. */
    val PADS: Set<Int> = setOf(1, 10, 19, 28)
    const val PAD_HOP = 3

    /** Chase looks at most this many ring squares ahead. */
    const val CHASE_RANGE = 12

    /** Which board side each seat sits on, by seat count: 2 face each other, 3 take sides 0..2, 4 take all. */
    fun sidesFor(seats: Int): List<Int> = when (seats) {
        2 -> listOf(0, 2)
        3 -> listOf(0, 1, 2)
        else -> listOf(0, 1, 2, 3)
    }

    /** The ring square of [progress] for a pawn on [side], or null when it is in the Nest, a lane or Home. */
    fun squareOf(side: Int, progress: Int): Int? =
        if (progress in 0 until LOOP) (EXIT_SQUARES[side] + progress) % LOOP else null

    /** The progress a pawn on [side] would have standing on ring [square] (0..35). */
    fun progressOn(side: Int, square: Int): Int = ((square - EXIT_SQUARES[side]) % LOOP + LOOP) % LOOP
}
