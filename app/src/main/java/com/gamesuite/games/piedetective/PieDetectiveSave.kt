package com.gamesuite.games.piedetective

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PiePlayerSave(val playerId: String, val isBot: Boolean)

/**
 * The resume snapshot of one running case book (section 4.2 of the design doc). Saved only at a turn boundary (the
 * CHOOSE state), so a half-made peek, flip or catch is never in it. The thief id is stored explicitly so a later change
 * of the cast cannot corrupt a save. [asked] entries look like "hat:1" (the trait id, then 1 for yes and 0 for no).
 * [luckyUsed] counts the Lucky peeks each seat has spent in the current case; [luckyTotal] is the whole book's tally
 * (the score card prints it).
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
    /** One count per seat: the Lucky peeks that seat has used in this case. */
    val luckyUsed: List<Int>,
    val turns: Int,
    val eventSerial: Int = 0,
    val luckyTotal: Int = 0
)

/**
 * Encodes, decodes and VALIDATES saves. A save that does not fit the roster or the rules is rejected, never repaired.
 */
object PieSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Upper bound on the text a restore will even try to parse (a real save is under 2 KB). */
    const val MAX_TEXT = 16_384

    fun encode(save: PieSave): String = json.encodeToString(PieSave.serializer(), save)

    fun decode(text: String, seats: List<PieSeat>): PieSave? = try {
        if (text.length > MAX_TEXT) null else {
            val s = json.decodeFromString(PieSave.serializer(), text)
            if (isValid(s, seats)) s else null
        }
    } catch (_: Exception) {
        null
    }

    /** Parses "hat:1" into the trait and its answer, or null when it does not look right. */
    fun parseClue(entry: String): PieClue? {
        val parts = entry.split(':')
        if (parts.size != 2) return null
        val trait = PieTrait.fromId(parts[0]) ?: return null
        val yes = when (parts[1]) { "1" -> true; "0" -> false; else -> return null }
        return PieClue(trait, yes)
    }

    fun clueEntry(c: PieClue): String = c.trait.id + ":" + (if (c.yes) "1" else "0")

    /**
     * A save is valid only when ALL of these hold:
     *  - version equals [PieRules.SAVE_VERSION];
     *  - 2..4 seats, [PieSave.players] equals [seats] in order (id and isBot), luckyUsed.size equals seats.size;
     *  - band and difficulty ids are known; handicap string is at most 40 characters;
     *  - caseIndex in 0 until the band's cases; casesSolved in 0..caseIndex; stars in 2 to 3 per solved case;
     *  - lineup has exactly the band's lineup size, ids unique and all in [PieCast];
     *  - thief is in the lineup and is NOT in flipped; flipped is a subset of the lineup without duplicates;
     *  - every asked entry parses (known trait offered by the band, each trait at most once, answer 0 or 1) and its
     *    answer equals the thief's value for that trait;
     *  - every flipped animal is excluded by at least one asked clue, except animals cleared by a wrong catch: the
     *    number of those must not exceed what the Sneak deck and the thief's hops explain (hops = dashes drawn + 2
     *    per wrong catch, and at most one wrong catch fits in a running case);
     *  - every animal excluded by an asked clue is flipped (a saved turn boundary has all its flips done);
     *  - hops in 0 until [PieRules.LANE_HOPS]; sneakIndex in 0 until [PieRules.SNEAK_DECK] and equal to the clues asked
     *    minus the Lucky peeks used; currentSeat in range; luckyUsed within the handicap's allowance;
     *  - turns, eventSerial and luckyTotal are non-negative, and luckyTotal covers this case's Lucky peeks.
     */
    fun isValid(s: PieSave, seats: List<PieSeat>): Boolean {
        if (s.version != PieRules.SAVE_VERSION) return false
        val n = seats.size
        if (n < PieRules.MIN_SEATS || n > PieRules.MAX_SEATS) return false
        if (s.players.size != n) return false
        for (i in 0 until n) {
            if (s.players[i].playerId != seats[i].playerId || s.players[i].isBot != seats[i].isBot) return false
        }
        if (seats.map { it.playerId }.toSet().size != n) return false
        if (s.luckyUsed.size != n) return false
        val band = PieBand.entries.firstOrNull { it.id == s.band } ?: return false
        if (CpuDifficulty.entries.none { it.name == s.difficulty }) return false
        if (s.handicap.length > PieRules.MAX_HANDICAP_LENGTH) return false
        val p = PieRules.band(band)
        if (s.caseIndex < 0 || s.caseIndex >= p.cases) return false
        if (s.casesSolved < 0 || s.casesSolved > s.caseIndex) return false
        if (s.stars < 2 * s.casesSolved || s.stars > 3 * s.casesSolved) return false

        if (s.lineup.size != p.lineup) return false
        if (s.lineup.toSet().size != s.lineup.size) return false
        val suspects = s.lineup.map { PieCast.byId(it) ?: return false }
        val thief = suspects.firstOrNull { it.id == s.thief } ?: return false
        if (s.flipped.toSet().size != s.flipped.size) return false
        if (s.flipped.any { it !in s.lineup }) return false
        if (s.thief in s.flipped) return false

        val offered = PieRules.traitsOf(band)
        val clues = ArrayList<PieClue>()
        for (entry in s.asked) {
            val c = parseClue(entry) ?: return false
            if (c.trait !in offered) return false
            if (clues.any { it.trait == c.trait }) return false
            if (c.yes != thief.has(c.trait)) return false
            clues += c
        }
        if (clues.size > offered.size) return false

        // Flips that no clue explains come from wrong catches only.
        val excluded = suspects.filter { x -> clues.any { c -> x.has(c.trait) != c.yes } }.map { it.id }.toSet()
        if (!excluded.all { it in s.flipped }) return false
        val extraFlips = s.flipped.count { it !in excluded }

        if (s.hops < 0 || s.hops >= PieRules.LANE_HOPS) return false
        if (s.sneakIndex < 0 || s.sneakIndex >= PieRules.SNEAK_DECK) return false
        if (s.currentSeat < 0 || s.currentSeat >= n) return false
        val allowance = PieHandicap.parse(s.handicap, n)
        var luckyThisCase = 0
        for (i in 0 until n) {
            val used = s.luckyUsed[i]
            if (used < 0 || used > allowance[i]) return false
            luckyThisCase += used
        }
        if (s.sneakIndex != clues.size - luckyThisCase) return false
        if (s.turns < 0 || s.eventSerial < 0 || s.luckyTotal < 0) return false
        if (s.luckyTotal < luckyThisCase) return false
        if (s.turns < clues.size) return false

        val deck = PieDealer.deck(s.bookSeed, s.caseIndex, band)
        val dashes = PieDealer.dashesIn(deck, s.sneakIndex)
        val rest = s.hops - dashes
        if (rest < 0 || rest % PieRules.WRONG_CATCH_HOPS != 0) return false
        val wrongCatches = rest / PieRules.WRONG_CATCH_HOPS
        // A wrong catch may clear an animal a later clue would have excluded anyway, so unexplained flips can only be fewer.
        if (wrongCatches > 1 || extraFlips > wrongCatches) return false
        return true
    }
}
