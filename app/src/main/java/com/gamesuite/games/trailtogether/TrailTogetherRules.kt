package com.gamesuite.games.trailtogether

/** The numbers of one band (section 2.5). Spot count is [length] + 1; index 0 is Start, index [length] is the Burrow. */
data class TrailBandParams(
    val colours: Int,
    val length: Int,
    val builtAtStart: Int,
    val columns: Int,
    val secondsPerPlay: Int
) {
    val spotCount: Int get() = length + 1
    val stonesToLight: Int get() = length - builtAtStart
}

/** The deck of one band and pal count. Fog cards are shuffled in after the deal; their count is the fog track length. */
data class TrailDeckSpec(
    val stonesPerColour: Int,
    val hopsPerColour: Int,
    val lanterns: Int,
    val fogCards: Int
) {
    /** Non-fog cards (what hands are dealt from). */
    fun nonFogCards(colours: Int): Int = colours * (stonesPerColour + hopsPerColour) + lanterns

    fun totalCards(colours: Int): Int = nonFogCards(colours) + fogCards
}

/** Constants and the data tables. The numbers must equal the "trail-spec" JSON in the design doc (tests T01-T03). */
object TrailRules {
    const val SAVE_VERSION = 1
    const val HAND_SIZE = 3
    const val HANDICAP_HAND_SIZE = 4
    const val MIN_SEATS = 1
    const val MAX_SEATS = 4
    const val MIN_PALS = 2
    const val MAX_PALS = 4
    const val STONES_PER_COLOUR = 4

    /** Pals on the trail: one per seat, but never fewer than 2 (a solo player moves two) nor more than 4. */
    fun palsFor(seats: Int): Int = seats.coerceIn(MIN_PALS, MAX_PALS)

    fun band(b: TrailBand): TrailBandParams = when (b) {
        TrailBand.LITTLE -> TrailBandParams(colours = 4, length = 16, builtAtStart = 4, columns = 5, secondsPerPlay = 18)
        TrailBand.MEDIUM -> TrailBandParams(colours = 5, length = 20, builtAtStart = 4, columns = 5, secondsPerPlay = 14)
        TrailBand.BIG -> TrailBandParams(colours = 6, length = 24, builtAtStart = 3, columns = 5, secondsPerPlay = 14)
    }

    /** The colours a band uses, in order. */
    fun coloursOf(b: TrailBand): List<TrailColour> = TrailColour.entries.take(band(b).colours)

    /** The deck table of section 2.5; [pals] is clamped to 2..4. */
    fun deck(b: TrailBand, pals: Int): TrailDeckSpec {
        val p = pals.coerceIn(MIN_PALS, MAX_PALS)
        return when (b) {
            TrailBand.LITTLE -> when (p) {
                2 -> TrailDeckSpec(STONES_PER_COLOUR, 5, 2, 10)
                3 -> TrailDeckSpec(STONES_PER_COLOUR, 7, 3, 12)
                else -> TrailDeckSpec(STONES_PER_COLOUR, 10, 4, 12)
            }
            TrailBand.MEDIUM -> when (p) {
                2 -> TrailDeckSpec(STONES_PER_COLOUR, 4, 2, 10)
                3 -> TrailDeckSpec(STONES_PER_COLOUR, 6, 3, 10)
                else -> TrailDeckSpec(STONES_PER_COLOUR, 8, 4, 10)
            }
            TrailBand.BIG -> when (p) {
                2 -> TrailDeckSpec(STONES_PER_COLOUR, 4, 2, 10)
                3 -> TrailDeckSpec(STONES_PER_COLOUR, 6, 3, 10)
                else -> TrailDeckSpec(STONES_PER_COLOUR, 8, 4, 10)
            }
        }
    }
}

/** The "Extra card for" handicap row (section 2.9). */
object TrailHandicap {
    /**
     * Hand size per seat. "none" (or anything unknown): everybody [TrailRules.HAND_SIZE]. "p1".."p4": that seat holds
     * [TrailRules.HANDICAP_HAND_SIZE]. A comma list with one size per seat ("3,4,3") is what a profile screen may pass;
     * each is clamped to 3..4.
     *
     * TODO(scaffold): implement exactly as documented; this safe version always returns the plain hand size.
     */
    fun parse(spec: String?, seats: Int): List<Int> {
        val n = seats.coerceIn(TrailRules.MIN_SEATS, TrailRules.MAX_SEATS)
        return List(n) { TrailRules.HAND_SIZE }
    }
}
