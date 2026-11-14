package com.gamesuite.games.trailtogether

/** The three difficulty bands (docs/design/kids-games/trail-together.md, section 2.5). */
enum class TrailBand(val id: String) {
    LITTLE("little"), MEDIUM("medium"), BIG("big");

    companion object {
        fun fromId(id: String?): TrailBand = entries.firstOrNull { it.id == id } ?: MEDIUM

        /** Null when [id] is not a band this version knows (a save is rejected, never repaired). */
        fun parse(id: String?): TrailBand? = entries.firstOrNull { it.id == id }
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
        /** Null when [code] is not a card this version knows. The code must be exactly canonical ("S:berry", never "S:berry:"). */
        fun fromCode(code: String): TrailCard? {
            val kind = TrailCardKind.entries.firstOrNull { it.code == code.substringBefore(':') } ?: return null
            val colourPart = if (code.contains(':')) code.substringAfter(':') else null
            val card = when (kind) {
                TrailCardKind.STEP, TrailCardKind.STONE -> {
                    val colour = TrailColour.fromId(colourPart) ?: return null
                    TrailCard(kind, colour)
                }
                TrailCardKind.LANTERN, TrailCardKind.FOG -> if (colourPart == null) TrailCard(kind) else return null
            }
            return if (card.code == code) card else null
        }
    }
}

/** The turn state machine of section 2.4. The engine only rests in PICK_CARD, WON and LOST; the others are transient. */
enum class TrailPhase { DEAL, PICK_CARD, PICK_TARGET, RESOLVE, DRAW, WON, LOST }

/** What a lantern does when played. */
enum class LanternMode { STONE, HOP }

/** One seat of the table as the engine needs it (people and helper pals alike). */
data class TrailSeat(val playerId: String, val name: String, val isBot: Boolean)

/** One trail spot as the screen sees it. [colour] is null while the spot is not lit (and for the Burrow). */
data class TrailSpot(val index: Int, val colour: TrailColour?, val lit: Boolean)

/** A pal on the trail. [ownerSeat] only decides its badge; any seat may move any pal. */
data class TrailPal(val ownerSeat: Int, val position: Int)

/** A thing that just happened, for sound and haptics. [serial] only ever grows. [code] is one of [TrailEventCode]. */
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

/**
 * Everything one turn did, so the screen can animate it beat by beat and word it only after it has finished.
 * [serial] equals the engine's event serial after the action and only ever grows. [kind] is [TrailEventCode.HOP],
 * [TrailEventCode.STONE] or [TrailEventCode.SWAP]; [viaLantern] says the card was a lantern. For a hop: [palIndex],
 * [from], [to]. For a stone: [spotIndex] and [colour]. [fogSteps] is how many Fog cards the refill revealed.
 */
data class TrailAction(
    val serial: Int,
    val seat: Int,
    val kind: String,
    val card: TrailCard,
    val viaLantern: Boolean = false,
    val palIndex: Int = -1,
    val from: Int = -1,
    val to: Int = -1,
    val spotIndex: Int = -1,
    val colour: TrailColour? = null,
    val fogSteps: Int = 0,
    val burrowOpened: Boolean = false,
    val won: Boolean = false,
    val lost: Boolean = false
)

/**
 * Everything the screen reads. Hands are open (face up) in same-screen play. [trail] is the colours of the lit
 * spots (index 0 first; its size is the [frontier]); [spots] has [length] + 1 entries, the last being the Burrow.
 */
data class TrailState(
    val phase: TrailPhase,
    val band: TrailBand,
    val length: Int,
    val spots: List<TrailSpot>,
    val trail: List<TrailColour>,
    val pals: List<TrailPal>,
    val hands: List<List<TrailCard>>,
    val handSizes: List<Int>,
    val currentSeat: Int,
    val fog: Int,
    val fogLength: Int,
    val cardsLeft: Int,
    val burrowOpen: Boolean,
    val plays: Int,
    val tries: Int,
    val wins: Int,
    val seed: Long,
    val lastEvent: TrailEvent?,
    val action: TrailAction?
) {
    val frontier: Int get() = trail.size
    val over: Boolean get() = phase == TrailPhase.WON || phase == TrailPhase.LOST
    val won: Boolean get() = phase == TrailPhase.WON
    val palsHome: Int get() = pals.count { it.position >= length }

    /** Where a pal at [from] lands for a hop card of [colour], or -1 when it cannot. */
    fun hopDestination(from: Int, colour: TrailColour): Int = TrailRules.hopTarget(trail, length, from, colour)

    /** Where a pal at [from] lands for a lantern hop (exactly one spot), or -1. */
    fun stepDestination(from: Int): Int = TrailRules.stepTarget(trail, length, from)
}

/**
 * What a play points at. [palIndex] is -1 when no pal is needed (stone card, swap). [colour] and [lanternMode] are
 * only read for a lantern: [LanternMode.STONE] needs a [colour], [LanternMode.HOP] needs a [palIndex].
 */
data class TrailTarget(
    val palIndex: Int = -1,
    val colour: TrailColour? = null,
    val lanternMode: LanternMode? = null
)

/** [BUSY]: the screen has not yet reported the previous action settled (a second tap in the same input batch). */
enum class TrailMoveResult { OK, NOT_ACTIVE, NOT_YOUR_TURN, NO_SUCH_CARD, NO_LEGAL_TARGET, INVALID_TARGET, BUSY }

/** A suggested play: which hand card and where. [isSwap] means "nothing helps, swap that card" ([target] is then empty). */
data class TrailHint(val handIndex: Int, val target: TrailTarget, val isSwap: Boolean = false)
