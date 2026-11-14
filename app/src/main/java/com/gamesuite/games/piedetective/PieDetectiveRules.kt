package com.gamesuite.games.piedetective

import kotlin.random.Random

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
    const val MAX_LUCKY_PER_CASE = 2
    const val PEEK_HOLD_MS = 400L
    const val SHOW_MS = 2000L
    const val SIDEKICK_PAUSE_MS = 1200L
    const val SIDEKICK_PAUSE_REDUCED_MS = 600L
    const val AUTO_FLIP_GAP_MS = 150L
    const val MAX_HANDICAP_LENGTH = 40

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

    /** Stars for catching the thief when it stood on [hops]: 3 while it is still near the stall, 2 once it passed the fountain. */
    fun starsForCatch(hops: Int): Int = if (hops <= 1) 3 else 2

    /** Estimated minutes of a whole book for [seats] seats (design doc 3.1: 5 s per extra seat per turn, plus one minute). */
    fun estimatedMinutes(b: PieBand, turnsPerCase: Double, seats: Int): Double {
        val p = band(b)
        val perTurn = p.secondsPerTurn + 5 * (seats - 2).coerceAtLeast(0)
        return turnsPerCase * perTurn * p.cases / 60.0 + 1.0
    }
}

/** One dealt case: the lineup in display order, the thief and the Sneak deck (true = dash). Pure function of the seeds. */
data class PieDeal(val lineup: List<String>, val thief: String, val deck: List<Boolean>)

/** The deterministic dealer (design doc 2.5 and 3.3). The Sneak deck does not depend on the cast, only on the seeds and the band. */
object PieDealer {
    fun caseSeed(bookSeed: Long, caseIndex: Int): Long = bookSeed * 31L + caseIndex

    fun deck(bookSeed: Long, caseIndex: Int, band: PieBand): List<Boolean> {
        val dash = PieRules.band(band).dashCards
        val cards = List(PieRules.SNEAK_DECK) { it < dash }
        return cards.shuffled(Random(caseSeed(bookSeed, caseIndex) xor 0x5EACL))
    }

    fun deal(bookSeed: Long, caseIndex: Int, band: PieBand): PieDeal {
        val p = PieRules.band(band)
        val rnd = Random(caseSeed(bookSeed, caseIndex))
        val picked = PieCast.all.shuffled(rnd).take(p.lineup)
        val display = picked.shuffled(rnd)
        val thief = display[rnd.nextInt(p.lineup)]
        return PieDeal(display.map { it.id }, thief.id, deck(bookSeed, caseIndex, band))
    }

    /** How many of the first [drawn] Sneak cards are dashes. */
    fun dashesIn(deck: List<Boolean>, drawn: Int): Int = deck.take(drawn.coerceIn(0, deck.size)).count { it }
}

/** The "Lucky peek for" handicap row (section 2.9). */
object PieHandicap {
    /**
     * Lucky peeks per seat. "none" (or anything unknown): nobody. "p1".."p4": that seat gets
     * [PieRules.LUCKY_PEEKS_PER_CASE]. A comma list with one count per seat ("0,1,0") is what a profile screen may
     * pass; each is clamped to 0..[PieRules.MAX_LUCKY_PER_CASE]; a list with a token that is not a whole number is
     * unknown (nobody). The result always has exactly the seat count (clamped to 2..4) of entries.
     */
    fun parse(spec: String?, seats: Int): List<Int> {
        val n = seats.coerceIn(PieRules.MIN_SEATS, PieRules.MAX_SEATS)
        val zeros = List(n) { 0 }
        val text = spec?.trim()?.lowercase() ?: return zeros
        if (text.isEmpty() || text.length > PieRules.MAX_HANDICAP_LENGTH || text == "none") return zeros
        if (text.length == 2 && text[0] == 'p' && text[1] in '1'..'4') {
            val seat = text[1] - '1'
            return List(n) { if (it == seat) PieRules.LUCKY_PEEKS_PER_CASE else 0 }
        }
        if (text.contains(',')) {
            val parts = text.split(',').map { it.trim() }
            val nums = parts.map { it.toIntOrNull() ?: return zeros }
            return List(n) { (nums.getOrNull(it) ?: 0).coerceIn(0, PieRules.MAX_LUCKY_PER_CASE) }
        }
        return zeros
    }
}
