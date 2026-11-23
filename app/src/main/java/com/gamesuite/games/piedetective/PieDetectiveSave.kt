package com.gamesuite.games.piedetective

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PiePlayerSave(val playerId: String, val isBot: Boolean)

/**
 * The resume snapshot of one running case book (section 4.2 of the design doc). Saved only at a turn boundary (the
 * CHOOSE state), so a half-made peek, flip or catch is never in it. The thief id is stored explicitly so a later change
 * of the cast cannot corrupt a save. [asked] entries look like "hat:1" (the trait id, then 1 for yes and 0 for no).
 */
@Serializable
data class PieSave(
    val version: Int = PieRules.SAVE_VERSION,
    val players: List<PiePlayerSave>,
    val band: String,
    val handicap: String,
    val difficulty: String,
    val hints: Boolean,
    val autoFlip: Boolean,
    val bookSeed: Long,
    val caseIndex: Int,
    val casesSolved: Int,
    val stars: Int,
    /** Suspect ids in display order. */
    val lineup: List<String>,
    val thief: String,
    val flipped: List<String>,
    val asked: List<String>,
    val hops: Int,
    val sneakIndex: Int,
    val currentSeat: Int,
    /** One flag per seat: true when that seat has already used its Lucky peek this case. */
    val luckyUsed: List<Boolean>,
    val turns: Int,
    val eventSerial: Int = 0
)

/**
 * Encodes, decodes and VALIDATES saves. A save that does not fit the roster or the rules is rejected, never repaired.
 */
object PieSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: PieSave): String = json.encodeToString(PieSave.serializer(), save)

    fun decode(text: String, seats: List<PieSeat>): PieSave? = try {
        val s = json.decodeFromString(PieSave.serializer(), text)
        if (isValid(s, seats)) s else null
    } catch (_: Exception) {
        null
    }

    /**
     * TODO(scaffold): implement. A save is valid only when ALL of these hold:
     *  - version equals [PieRules.SAVE_VERSION];
     *  - 2..4 seats, [PieSave.players] equals [seats] in order (id and isBot), luckyUsed.size equals seats.size;
     *  - band and difficulty ids are known; handicap string is at most 40 characters;
     *  - caseIndex in 0 until the band's cases; casesSolved in 0..caseIndex; stars in 0..3 * casesSolved;
     *  - lineup has exactly the band's lineup size, ids unique and all in [PieCast];
     *  - thief is in the lineup and is NOT in flipped; flipped is a subset of the lineup without duplicates;
     *  - every asked entry parses (known trait offered by the band, each trait at most once, answer 0 or 1) and its
     *    answer equals the thief's value for that trait;
     *  - every flipped animal is excluded by at least one asked clue, except animals cleared by a wrong catch
     *    (accepted only when hops >= [PieRules.WRONG_CATCH_HOPS] could explain it);
     *  - hops in 0 until [PieRules.LANE_HOPS]; sneakIndex in 0 until [PieRules.SNEAK_DECK]; currentSeat in range;
     *  - turns and eventSerial non-negative.
     * Until then this returns false, so a resume always falls back to a fresh start.
     */
    fun isValid(s: PieSave, seats: List<PieSeat>): Boolean = false
}
