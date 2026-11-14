package com.gamesuite.games.piedetective

/** The three difficulty bands (docs/design/kids-games/pie-detective.md, section 2.6). */
enum class PieBand(val id: String) {
    LITTLE("little"), MEDIUM("medium"), BIG("big");

    companion object {
        fun fromId(id: String?): PieBand = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** The six visible accessories. The declaration order is the bit order of every suspect's trait vector. */
enum class PieTrait(val id: String) {
    HAT("hat"), GLASSES("glasses"), SCARF("scarf"), BAG("bag"), BOOTS("boots"), BOW("bow");

    companion object {
        fun fromId(id: String?): PieTrait? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One animal of the cast. [vector] is six characters of '0'/'1' in [PieTrait] order ("100011" = hat, boots, bow tie).
 * [furColour] is the light-theme fur colour as ARGB.
 */
data class PieSuspect(
    val id: String,
    val speciesId: String,
    val furColour: Long,
    val vector: String
) {
    fun has(trait: PieTrait): Boolean = vector.getOrNull(trait.ordinal) == '1'
}

/** The turn state machine of section 2.4. */
enum class PiePhase { PASS, CHOOSE, PEEK, FLIP, SNEAK, CATCH, CASE_WON, CASE_LOST, BOOK_DONE }

/** One seat of the table as the engine needs it (people and Sidekicks alike). */
data class PieSeat(val playerId: String, val name: String, val isBot: Boolean)

/** One animal on the table as the screen sees it. */
data class PieSuspectView(val id: String, val flipped: Boolean)

/** One line of the notebook: the trait that was asked and the answer that was shown to everyone. */
data class PieClue(val trait: PieTrait, val yes: Boolean)

/** Something that just happened, for animation and sound. [serial] only ever grows. */
data class PieEvent(val serial: Int, val code: String)

object PieEventCode {
    const val PEEKED = "peeked"
    const val SHOWN = "shown"
    const val FLIPPED = "flipped"
    const val SNEAK_DASH = "sneak_dash"
    const val SNEAK_PAUSE = "sneak_pause"
    const val CATCH_RIGHT = "catch_right"
    const val CATCH_WRONG = "catch_wrong"
    const val CASE_WON = "case_won"
    const val CASE_LOST = "case_lost"
}

/** Book-level tally. */
data class PieSession(
    val casesPlayed: Int,
    val casesSolved: Int,
    val casesInBook: Int,
    val stars: Int,
    val luckyPeeksUsed: Int,
    val bookFinished: Boolean
)

/**
 * Everything the screen reads. The thief is NEVER here while a case runs: [revealedThiefId] is non-null only in
 * CASE_WON and CASE_LOST. [pendingAnswer] is non-null only in the PEEK phase (the lens shows it).
 */
data class PieState(
    val phase: PiePhase,
    val band: PieBand,
    val caseIndex: Int,
    val casesInBook: Int,
    val lineup: List<PieSuspectView>,
    val availableTraits: List<PieTrait>,
    val clues: List<PieClue>,
    val currentSeat: Int,
    val thiefHops: Int,
    val lastSneakWasDash: Boolean?,
    val pendingTrait: PieTrait?,
    val pendingAnswer: Boolean?,
    val luckyAvailable: Boolean,
    val uncleared: Int,
    val revealedThiefId: String?,
    val lastEvent: PieEvent?
)

enum class PieResult {
    OK, NOT_ACTIVE, NOT_YOUR_TURN, WRONG_PHASE, TRAIT_ALREADY_ASKED, TRAIT_NOT_OFFERED,
    NO_SUCH_SUSPECT, STILL_MATCHES, ALREADY_CLEARED, NO_LUCKY_PEEK_LEFT
}

/** A suggested next step: glow [trait], or pulse Catch! when [catchNow] is true. */
data class PieHint(val trait: PieTrait?, val catchNow: Boolean)
