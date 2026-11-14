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

    /** Bounds a restored counter must stay inside (a corrupt save is rejected, never clamped). */
    const val MAX_TRIES = 100_000
    const val MAX_EVENT_SERIAL = 50_000_000

    /** Pals on the trail: one per seat, but never fewer than 2 (a solo player moves two) nor more than 4. */
    fun palsFor(seats: Int): Int = seats.coerceIn(MIN_PALS, MAX_PALS)

    fun band(b: TrailBand): TrailBandParams = when (b) {
        TrailBand.LITTLE -> TrailBandParams(colours = 4, length = 16, builtAtStart = 4, columns = 5, secondsPerPlay = 18)
        TrailBand.MEDIUM -> TrailBandParams(colours = 5, length = 20, builtAtStart = 4, columns = 5, secondsPerPlay = 14)
        TrailBand.BIG -> TrailBandParams(colours = 6, length = 24, builtAtStart = 3, columns = 5, secondsPerPlay = 14)
    }

    /** The colours a band uses, in order. */
    fun coloursOf(b: TrailBand): List<TrailColour> = TrailColour.entries.take(band(b).colours)

    /** The deck table of section 2.5; [pals] is clamped to 2..4. This is data: tune the numbers, not the engine. */
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

    /**
     * Where a pal standing on [from] lands when a hop card of [colour] is played: the nearest LIT spot of that colour
     * ahead of it, or the Burrow (which counts as every colour) when the trail is lit through and no lit spot of the
     * colour is nearer. -1 when there is none. [trail] is the colours of the lit spots, index 0 first; [length] is the
     * Burrow's index. A pal that is already home never moves.
     */
    fun hopTarget(trail: List<TrailColour>, length: Int, from: Int, colour: TrailColour): Int {
        if (from < 0 || from >= length) return -1
        var i = from + 1
        while (i < trail.size) {
            if (trail[i] == colour) return i
            i++
        }
        return if (trail.size >= length) length else -1
    }

    /** Where a lantern hop (exactly one spot forward, onto a lit spot or the open Burrow) lands, or -1. */
    fun stepTarget(trail: List<TrailColour>, length: Int, from: Int): Int {
        if (from < 0 || from >= length) return -1
        val next = from + 1
        return if (next < trail.size || trail.size >= length) next else -1
    }
}

/** The "Extra card for" handicap row (section 2.9). */
object TrailHandicap {
    const val MAX_SPEC_LENGTH = 40
    private val SEAT_FORM = Regex("p([1-4])")

    /**
     * Hand size per seat. "none" (or anything unknown, too long, or naming a seat that is not at the table): everybody
     * [TrailRules.HAND_SIZE]. "p1".."p4": that seat holds [TrailRules.HANDICAP_HAND_SIZE]. A comma list with exactly
     * one size per seat ("3,4,3") is what a profile screen may pass; each is clamped to 3..4.
     */
    fun parse(spec: String?, seats: Int): List<Int> {
        val n = seats.coerceIn(TrailRules.MIN_SEATS, TrailRules.MAX_SEATS)
        val plain = List(n) { TrailRules.HAND_SIZE }
        val s = spec?.trim() ?: return plain
        if (s.isEmpty() || s.length > MAX_SPEC_LENGTH) return plain
        val seat = SEAT_FORM.matchEntire(s)?.groupValues?.get(1)?.toInt()
        if (seat != null) {
            return if (seat <= n) plain.mapIndexed { i, h -> if (i == seat - 1) TrailRules.HANDICAP_HAND_SIZE else h } else plain
        }
        if (s.contains(',')) {
            val parts = s.split(',')
            if (parts.size != n) return plain
            return parts.map { (it.trim().toIntOrNull() ?: return plain).coerceIn(TrailRules.HAND_SIZE, TrailRules.HANDICAP_HAND_SIZE) }
        }
        return plain
    }

    /** The Hub option key for a table of [seats]: `handicap` (2 or fewer), `handicap3`, `handicap4`. */
    fun keyFor(seats: Int): String = if (seats <= 2) "handicap" else "handicap$seats"

    /**
     * The handicap spec of a launch: the key for the table size, accepted only as "none" or "p1".."pN" with N the
     * number of seats (anything else, including a stale choice for a seat that is not there, is "none").
     */
    fun fromExtras(extras: Map<String, String>, seats: Int): String {
        val v = extras[keyFor(seats)] ?: return "none"
        val seat = SEAT_FORM.matchEntire(v)?.groupValues?.get(1)?.toInt() ?: return "none"
        return if (seat in 1..seats.coerceIn(TrailRules.MIN_SEATS, TrailRules.MAX_SEATS)) v else "none"
    }
}
