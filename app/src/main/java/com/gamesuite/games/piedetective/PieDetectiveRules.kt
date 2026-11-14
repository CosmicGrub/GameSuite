package com.gamesuite.games.piedetective

/** The numbers of one band (section 2.5 JSON and 2.6 table). */
data class PieBandParams(
    val lineup: Int,
    val traits: Int,
    val dashCards: Int,
    val cases: Int,
    val hintsDefault: Boolean,
    val autoFlipDefault: Boolean,
    val secondsPerTurn: Int
)

/** Constants and the data tables. The numbers must equal the "pie-spec" JSON in the design doc (tests P01-P06). */
object PieRules {
    const val SAVE_VERSION = 1
    const val MIN_SEATS = 2
    const val MAX_SEATS = 4
    const val LANE_HOPS = 3
    const val WRONG_CATCH_HOPS = 2
    const val SNEAK_DECK = 12
    const val LUCKY_PEEKS_PER_CASE = 1
    const val PEEK_HOLD_MS = 400L
    const val SHOW_MS = 2000L

    /** The lane places in order; the thief starts on the first and loses the case on the last. */
    val lanePlaces: List<String> = listOf("stall", "fountain", "bridge", "gate")

    fun band(b: PieBand): PieBandParams = when (b) {
        PieBand.LITTLE -> PieBandParams(lineup = 8, traits = 4, dashCards = 4, cases = 2, hintsDefault = true, autoFlipDefault = true, secondsPerTurn = 55)
        PieBand.MEDIUM -> PieBandParams(lineup = 10, traits = 5, dashCards = 5, cases = 3, hintsDefault = true, autoFlipDefault = false, secondsPerTurn = 45)
        PieBand.BIG -> PieBandParams(lineup = 12, traits = 6, dashCards = 6, cases = 3, hintsDefault = false, autoFlipDefault = false, secondsPerTurn = 50)
    }

    /** The traits a band offers, in order. */
    fun traitsOf(b: PieBand): List<PieTrait> = PieTrait.entries.take(band(b).traits)

    /** Cases that must be solved in a finished book for a team WIN: half, rounded up (O3). */
    fun bookWinThreshold(cases: Int): Int = (cases + 1) / 2
}

/** The "Lucky peek for" handicap row (section 2.9). */
object PieHandicap {
    /**
     * Lucky peeks per seat. "none" (or anything unknown): nobody. "p1".."p4": that seat gets
     * [PieRules.LUCKY_PEEKS_PER_CASE]. A comma list with one count per seat ("0,1,0") is what a profile screen may
     * pass; each is clamped to 0..2.
     *
     * TODO(scaffold): implement exactly as documented; this safe version always returns zeros.
     */
    fun parse(spec: String?, seats: Int): List<Int> {
        val n = seats.coerceIn(PieRules.MIN_SEATS, PieRules.MAX_SEATS)
        return List(n) { 0 }
    }
}
