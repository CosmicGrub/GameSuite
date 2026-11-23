package com.gamesuite.games.sweettrail

/**
 * The six card and square colours of Sweet Trail. [letter] is the character used in
 * [SweetTrailBoard.COLOUR_SEQUENCE]; [key] names the resources `sweet_trail_colour_<key>` and
 * `sweet_trail_pattern_<key>`. Colour is never the only cue: every colour also has a pattern.
 */
enum class TrailColour(val letter: Char, val key: String) {
    CHERRY('R', "cherry"),
    TANGERINE('O', "tangerine"),
    LEMON('Y', "lemon"),
    LIME('G', "lime"),
    BLUEBERRY('B', "blueberry"),
    GRAPE('P', "grape");

    companion object {
        fun ofLetter(letter: Char): TrailColour? = values().firstOrNull { it.letter == letter }
    }
}

/** The six landmarks. A landmark card jumps a piece forward to [square]; [key] names `sweet_trail_landmark_<key>`. */
enum class TrailLandmark(val key: String, val square: Int) {
    COOKIE_HILL("cookie_hill", 11),
    LEMONADE_LAKE("lemonade_lake", 17),
    CUPCAKE_TOWER("cupcake_tower", 29),
    HONEYCOMB_CAVE("honeycomb_cave", 35),
    MARSHMALLOW_MEADOW("marshmallow_meadow", 46),
    CAKE_GATE("cake_gate", 57)
}

/**
 * The three game lengths (the Hub's `length` choice). All three use the same 60-square trail:
 *  - SHORT: every piece starts on square 28 (the Hub's "Short trail"), everything else as STANDARD;
 *  - STANDARD: start at 0, three shortcuts, double cards, one puddle (square 33);
 *  - LONG: no shortcuts, no double cards (ten single cards per colour), a second puddle on square 50.
 */
enum class TrailLength(
    val id: String,
    val startSquare: Int,
    val shortcuts: Boolean,
    val doubles: Boolean,
    val puddles: Set<Int>
) {
    SHORT("short", 28, true, true, setOf(33)),
    STANDARD("standard", 0, true, true, setOf(33)),
    LONG("long", 0, false, false, setOf(33, 50));

    companion object {
        /** Unknown or null ids fall back to STANDARD (a stale saved Hub choice must never crash). */
        fun fromId(id: String?): TrailLength = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

/** One square as the screen draws it. Square 0 is the start area and [SweetTrailBoard.FINISH] the Party Table (both have no colour). */
data class TrailSquare(
    val index: Int,
    val colour: TrailColour?,
    val landmark: TrailLandmark?,
    val shortcutTo: Int?,
    val puddle: Boolean
)

/**
 * The trail: 60 coloured squares (1..60), the start area (0) and the Party Table (61). Pure data, no Compose.
 * The full table (square, colour, landmark, shortcut, puddle) is in docs/design/boardgames/sweet-trail.md.
 */
object SweetTrailBoard {
    const val PATH_SQUARES = 60
    const val START = 0
    const val FINISH = 61

    /** Square 1..60 left to right: ten blocks of the six colours, each block a different permutation (10 of each colour, no run longer than 2). */
    const val COLOUR_SEQUENCE: String =
        "RBYPGO" + "GROBPY" + "YOPGRB" + "BGRYOP" + "PYBOGR" + "OPGRYB" + "RYBGPO" + "GBOPYR" + "YPROBG" + "ORGYBP"

    /** Shortcut start square to end square (always forward; ends are never starts, so shortcuts never chain). */
    val SHORTCUTS: Map<Int, Int> = mapOf(8 to 20, 24 to 37, 42 to 53)

    /** Resource key suffixes (`sweet_trail_<name>`) of the shortcut starts. */
    val SHORTCUT_NAMES: Map<Int, String> = mapOf(8 to "jelly_bridge", 24 to "pretzel_ladder", 42 to "cloud_zipline")

    /** Colour of [square], or null for the start area and the Party Table. */
    fun colourAt(square: Int): TrailColour? =
        if (square in 1..PATH_SQUARES) TrailColour.ofLetter(COLOUR_SEQUENCE[square - 1]) else null

    fun landmarkAt(square: Int): TrailLandmark? = TrailLandmark.values().firstOrNull { it.square == square }

    /**
     * The [count]-th square after [from] that has [colour] (count 1 = the next one). When fewer than [count]
     * such squares remain, the answer is [FINISH]: running out of a colour carries a piece to the Party Table.
     */
    fun nextSquareOfColour(from: Int, colour: TrailColour, count: Int = 1): Int {
        var seen = 0
        var square = from + 1
        while (square <= PATH_SQUARES) {
            if (colourAt(square) == colour) {
                seen += 1
                if (seen >= count) return square
            }
            square += 1
        }
        return FINISH
    }

    fun square(index: Int, length: TrailLength, sticky: Boolean = true): TrailSquare = TrailSquare(
        index = index,
        colour = colourAt(index),
        landmark = landmarkAt(index),
        shortcutTo = if (length.shortcuts) SHORTCUTS[index] else null,
        puddle = sticky && index in length.puddles
    )
}
