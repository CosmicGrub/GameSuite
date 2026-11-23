package com.gamesuite.games.trailtogether

/** The three difficulty bands (docs/design/kids-games/trail-together.md, section 2.5). */
enum class TrailBand(val id: String) {
    LITTLE("little"), MEDIUM("medium"), BIG("big");

    companion object {
        fun fromId(id: String?): TrailBand = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** A stone colour and the shape that always travels with it (colour is never the only cue). */
enum class TrailColour(val id: String, val shape: String) {
    BERRY("berry", "circle"),
    SUN("sun", "triangle"),
    LEAF("leaf", "square"),
    SKY("sky", "diamond"),
    PLUM("plum", "star"),
    PEACH("peach", "hexagon");

    companion object {
        fun fromId(id: String?): TrailColour? = entries.firstOrNull { it.id == id }
    }
}

/** HOP moves a pal, STONE lights the frontier spot, LANTERN does either, FOG is never held (it lives in the pile). */
enum class TrailCardKind(val code: String) {
    STEP("S"), STONE("T"), LANTERN("L"), FOG("F")
}

/** One card. [colour] is null for lanterns and fog. The short [code] is what snapshots store ("S:berry", "T:sun", "L", "F"). */
data class TrailCard(val kind: TrailCardKind, val colour: TrailColour? = null) {
    val code: String get() = if (colour == null) kind.code else kind.code + ":" + colour.id

    companion object {
        /** Null when [code] is not a card this version knows. */
        fun fromCode(code: String): TrailCard? {
            val kind = TrailCardKind.entries.firstOrNull { it.code == code.substringBefore(':') } ?: return null
            val colourPart = if (code.contains(':')) code.substringAfter(':') else null
            return when (kind) {
                TrailCardKind.STEP, TrailCardKind.STONE -> {
                    val colour = TrailColour.fromId(colourPart) ?: return null
                    TrailCard(kind, colour)
                }
                TrailCardKind.LANTERN, TrailCardKind.FOG -> if (colourPart == null) TrailCard(kind) else null
            }
        }
    }
}

/** The turn state machine of section 2.4. */
enum class TrailPhase { DEAL, PICK_CARD, PICK_TARGET, RESOLVE, DRAW, WON, LOST }

/** What a lantern does when played. */
enum class LanternMode { STONE, HOP }

/** One seat of the table as the engine needs it (people and helper pals alike). */
data class TrailSeat(val playerId: String, val name: String, val isBot: Boolean)

/** One trail spot as the screen sees it. [colour] is null while the spot is not lit. */
data class TrailSpot(val index: Int, val colour: TrailColour?, val lit: Boolean)

/** A pal on the trail. [ownerSeat] only decides its badge; any seat may move any pal. */
data class TrailPal(val ownerSeat: Int, val position: Int)

/** A thing that just happened, for animation and sound. [serial] only ever grows. [code] is one of [TrailEventCode]. */
data class TrailEvent(val serial: Int, val code: String)

object TrailEventCode {
    const val HOP = "hop"
    const val STONE = "stone"
    const val SWAP = "swap"
    const val FOG_STEP = "fog_step"
    const val BURROW_OPEN = "burrow_open"
    const val WIN = "win"
    const val LOSE = "lose"
}

/** Everything the screen reads. Hands are open (face up) in same-screen play. */
data class TrailState(
    val phase: TrailPhase,
    val band: TrailBand,
    val spots: List<TrailSpot>,
    val pals: List<TrailPal>,
    val hands: List<List<TrailCard>>,
    val currentSeat: Int,
    val fog: Int,
    val fogLength: Int,
    val cardsLeft: Int,
    val burrowOpen: Boolean,
    val plays: Int,
    val lastEvent: TrailEvent?
)

/**
 * What a play points at. [palIndex] is -1 when no pal is needed (stone card, swap). [colour] and [lanternMode] are
 * only read for a lantern played in STONE mode.
 */
data class TrailTarget(
    val palIndex: Int = -1,
    val colour: TrailColour? = null,
    val lanternMode: LanternMode? = null
)

enum class TrailMoveResult { OK, NOT_ACTIVE, NOT_YOUR_TURN, NO_SUCH_CARD, NO_LEGAL_TARGET, INVALID_TARGET }

/** A suggested play: which hand card and where. */
data class TrailHint(val handIndex: Int, val target: TrailTarget)
