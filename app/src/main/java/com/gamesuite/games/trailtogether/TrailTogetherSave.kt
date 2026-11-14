package com.gamesuite.games.trailtogether

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

    fun encode(save: TrailSave): String = json.encodeToString(TrailSave.serializer(), save)

    fun decode(text: String, seats: List<TrailSeat>): TrailSave? = try {
        val s = json.decodeFromString(TrailSave.serializer(), text)
        if (isValid(s, seats)) s else null
    } catch (_: Exception) {
        null
    }

    /**
     * TODO(scaffold): implement. A save is valid only when ALL of these hold:
     *  - version equals [TrailRules.SAVE_VERSION];
     *  - 1..4 seats, [TrailSave.players] equals [seats] in order (id and isBot), hands.size equals seats.size;
     *  - band and difficulty ids are known; handicap string is at most 40 characters;
     *  - every card code parses with [TrailCard.fromCode]; hands hold exactly the hand size of their seat;
     *  - hand + pile + discard cards, plus lit-trail stones that came from stone cards, equal the deck table for the
     *    band and pal count (counts per kind and colour); no fog card in any hand;
     *  - trail has builtAtStart..length stones, every colour belongs to the band, no two adjacent initial spots equal;
     *  - pals.size equals [TrailRules.palsFor] of the seats, every position is 0..length, a pal beyond the lit trail
     *    is only allowed on the Burrow when the trail is lit through length-1;
     *  - 0 <= fog < the deck's fog count, and fog + fog cards left in the pile equals the deck's fog count;
     *  - currentSeat in range, plays/tries/wins/eventSerial non-negative, wins <= tries.
     * Until then this returns false, so a resume always falls back to a fresh start.
     */
    fun isValid(s: TrailSave, seats: List<TrailSeat>): Boolean = false
}
