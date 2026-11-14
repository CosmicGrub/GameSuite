package com.gamesuite.games.trailtogether

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrailPlayerSave(val playerId: String, val isBot: Boolean)

/**
 * The resume snapshot of one running try (section 4.2 of the design doc). Card codes: "S:berry" hop, "T:sun" stone,
 * "L" lantern, "F" fog. Saved only at a turn boundary (the PICK_CARD state), so there is no half-made play in it.
 */
@Serializable
data class TrailSave(
    val version: Int = TrailRules.SAVE_VERSION,
    val players: List<TrailPlayerSave>,
    val band: String,
    val handicap: String,
    val difficulty: String,
    val seed: Long,
    /** Colour ids of the lit spots, index 0 first. Its size is the frontier. */
    val trail: List<String>,
    /** Position of each pal (0..length). */
    val pals: List<Int>,
    /** Card codes per seat, in hand order. */
    val hands: List<List<String>>,
    /** Remaining draw pile, next card FIRST. */
    val pile: List<String>,
    val discard: List<String>,
    val fog: Int,
    val currentSeat: Int,
    val plays: Int,
    val tries: Int,
    val wins: Int,
    val eventSerial: Int = 0
)

/**
 * Encodes, decodes and VALIDATES saves. A save that does not fit the roster or the rules is rejected, never repaired.
 */
object TrailSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** A real save is under 4 KB; anything much larger is not ours. */
    const val MAX_TEXT = 16_384

    fun encode(save: TrailSave): String = json.encodeToString(TrailSave.serializer(), save)

    fun decode(text: String, seats: List<TrailSeat>): TrailSave? = try {
        if (text.length > MAX_TEXT) null
        else {
            val s = json.decodeFromString(TrailSave.serializer(), text)
            if (isValid(s, seats)) s else null
        }
    } catch (_: Exception) {
        null
    }

    private fun counts(codes: Iterable<String>): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (c in codes) m[c] = (m[c] ?: 0) + 1
        return m
    }

    /** The whole deck of a band and pal count as code counts (stones, hops, lanterns, fog). */
    fun deckCounts(band: TrailBand, pals: Int): Map<String, Int> {
        val spec = TrailRules.deck(band, pals)
        val m = HashMap<String, Int>()
        for (c in TrailRules.coloursOf(band)) {
            m[TrailCard(TrailCardKind.STONE, c).code] = spec.stonesPerColour
            m[TrailCard(TrailCardKind.STEP, c).code] = spec.hopsPerColour
        }
        m[TrailCard(TrailCardKind.LANTERN).code] = spec.lanterns
        m[TrailCard(TrailCardKind.FOG).code] = spec.fogCards
        return m.filterValues { it > 0 }
    }

    /**
     * A save is valid only when ALL of these hold: the version is ours; there are 1..4 seats and the players equal
     * [seats] in order (id and isBot); band and difficulty ids are known; the handicap text is short; every card code is
     * canonical; each hand holds exactly its seat's hand size and no fog; hands + pile + discard are EXACTLY the deck
     * of the band and pal count (every played, swapped and revealed card sits in the discard); the trail holds
     * builtAtStart..length stones of the band's colours with no two equal neighbours among the opening spots and no
     * more lit-up extras than stone or lantern cards in the discard; the pal count is right and each pal stands on
     * a lit spot or on the Burrow once the trail is lit through (and not all are home); the fog marker equals the fog
     * cards in the discard and is below the track length; plays equals the non-fog cards in the discard; the seat on
     * turn exists; the counters are non-negative, bounded and wins <= tries.
     */
    fun isValid(s: TrailSave, seats: List<TrailSeat>): Boolean {
        if (s.version != TrailRules.SAVE_VERSION) return false
        val n = seats.size
        if (n !in TrailRules.MIN_SEATS..TrailRules.MAX_SEATS) return false
        if (s.players.size != n || s.hands.size != n) return false
        for (i in 0 until n) {
            if (s.players[i].playerId != seats[i].playerId || s.players[i].isBot != seats[i].isBot) return false
        }
        val band = TrailBand.parse(s.band) ?: return false
        if (CpuDifficulty.entries.none { it.name == s.difficulty }) return false
        if (s.handicap.length > TrailHandicap.MAX_SPEC_LENGTH) return false

        val params = TrailRules.band(band)
        val bandColours = TrailRules.coloursOf(band)
        val palCount = TrailRules.palsFor(n)
        val sizes = TrailHandicap.parse(s.handicap, n)

        // cards
        val handCards = ArrayList<TrailCard>()
        for (i in 0 until n) {
            val hand = s.hands[i]
            if (hand.size != sizes[i]) return false
            for (code in hand) {
                val card = TrailCard.fromCode(code) ?: return false
                if (card.kind == TrailCardKind.FOG) return false
                handCards += card
            }
        }
        val pileCards = s.pile.map { TrailCard.fromCode(it) ?: return false }
        val discardCards = s.discard.map { TrailCard.fromCode(it) ?: return false }
        val all = ArrayList<String>(handCards.size + pileCards.size + discardCards.size)
        handCards.forEach { all += it.code }
        pileCards.forEach { all += it.code }
        discardCards.forEach { all += it.code }
        if (counts(all) != deckCounts(band, palCount)) return false

        // trail
        if (s.trail.size !in params.builtAtStart..params.length) return false
        val trail = s.trail.map { id -> TrailColour.fromId(id)?.takeIf { it in bandColours } ?: return false }
        for (i in 1 until params.builtAtStart) if (trail[i] == trail[i - 1]) return false
        val extras = trail.size - params.builtAtStart
        val lightable = discardCards.count { it.kind == TrailCardKind.STONE || it.kind == TrailCardKind.LANTERN }
        if (extras > lightable) return false

        // pals
        if (s.pals.size != palCount) return false
        val open = trail.size >= params.length
        for (p in s.pals) {
            if (p < 0 || p > params.length) return false
            if (p == params.length) { if (!open) return false } else if (p >= trail.size) return false
        }
        if (s.pals.all { it == params.length }) return false

        // fog and counters
        val fogDiscarded = discardCards.count { it.kind == TrailCardKind.FOG }
        val spec = TrailRules.deck(band, palCount)
        if (s.fog != fogDiscarded || s.fog < 0 || s.fog >= spec.fogCards) return false
        if (s.plays != discardCards.size - fogDiscarded) return false
        if (s.currentSeat !in 0 until n) return false
        if (s.tries !in 0..TrailRules.MAX_TRIES || s.wins !in 0..s.tries) return false
        if (s.eventSerial !in 0..TrailRules.MAX_EVENT_SERIAL) return false
        return true
    }
}
