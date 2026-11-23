package com.gamesuite.games.findit

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable

/** The three ways to play (docs/design/kids-games/find-it.md, section SCRIPT). */
enum class FindItMode { MATCH_RACE, GROUP_HUNT, SOLO_DASH;
    companion object {
        fun fromId(id: String): FindItMode = when (id.lowercase()) {
            "hunt", "group_hunt" -> GROUP_HUNT
            "dash", "solo_dash" -> SOLO_DASH
            else -> MATCH_RACE
        }
    }
}

/** Phases of the engine. SETUP lives in the Hub, so the engine starts at READY. Pause is a flag, not a phase. */
enum class FindItPhase { READY, PLAYING, ROUND_WON, SCENE_CLEAR, SCENE_TIMEOUT, OVER }

/**
 * Card size = projective plane order. TINY 4 pictures per card (13 cards, 13 pictures), LITTLE 6 (31, 31),
 * STANDARD 8 (57, 57). Order must be prime for the construction in [ProjectiveDeck].
 */
enum class FindItCardSize(val order: Int, val symbolsPerCard: Int, val deckSize: Int) {
    TINY(3, 4, 13), LITTLE(5, 6, 31), STANDARD(7, 8, 57);

    companion object {
        fun fromId(id: String): FindItCardSize = when (id.lowercase()) {
            "tiny" -> TINY
            "standard" -> STANDARD
            else -> LITTLE
        }
    }
}

/** Game length: Match Race and Dash rounds, Group Hunt scenes. */
enum class FindItLength(val raceRounds: Int, val huntScenes: Int) {
    QUICK(15, 2), STANDARD(24, 3), LONG(36, 4);

    companion object {
        fun fromId(id: String): FindItLength = when (id.lowercase()) {
            "quick" -> QUICK
            "long" -> LONG
            else -> STANDARD
        }
    }
}

/** What a seat's hint handicap currently shows: nothing, three candidate pictures, or a pulse on the answer. */
enum class FindItHint { NONE, NARROW, PULSE }

/** Result of a tap, for sound, haptics and the screen reader. */
enum class FindItTapResult {
    NOT_PLAYING, INVALID, LOCKED, WRONG, WON_CARD, FOUND, MISS, SCENE_CLEARED
}

/** Colour tokens of the picture set. Colour is never the only cue: every picture also has its own silhouette. */
enum class FindItColour { CORAL, SUN, LEAF, SKY, GRAPE, BERRY, TEAL, CLAY }

/** One drawn picture. [index] is the picture number used by decks and scenes; [nameKey] is its string resource key. */
data class FindItSymbol(
    val index: Int,
    val id: String,
    val nameKey: String,
    val colour: FindItColour,
    /** How the vector icon is composed from primitives (documentation for the artist; the screen draws it from [id]). */
    val recipe: String
)

/** One seat at the table. A bot seat is a helper. */
data class FindItSeat(val playerId: String, val name: String, val isBot: Boolean)

/**
 * Everything the Hub chose. [handicap] is "none" | "all" | "p1".."p4" (Match Race and Dash), [huntHint] is
 * "off" | "slow" | "fast" (Group Hunt). [seed] 0 means "pick one at start" (the game wrapper draws it once).
 */
data class FindItConfig(
    val mode: FindItMode = FindItMode.MATCH_RACE,
    val cardSize: FindItCardSize = FindItCardSize.LITTLE,
    val length: FindItLength = FindItLength.STANDARD,
    val relaxed: Boolean = false,
    val handicap: String = "none",
    val huntHint: String = "slow",
    val oneSwitch: Boolean = false,
    val difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
    val seed: Long = 0L
)

/** Balanced numbers in one place (see the SCRIPT section for the reasoning). All times in milliseconds. */
object FindItRules {
    const val LOCKOUT_MS = 1_500L
    const val STALL_MS = 20_000L
    const val STALL_MS_LITTLE = 30_000L
    const val HINT_NARROW_MS = 5_000L
    const val HINT_PULSE_MS = 9_000L
    const val RELAXED_HINT_NARROW_MS = 3_000L
    const val RELAXED_HINT_PULSE_MS = 6_000L
    const val ROUND_WON_MS = 900L
    const val SCENE_CLEAR_MS = 1_500L
    const val DASH_MS = 60_000L
    const val DASH_LITTLE_MS = 90_000L
    const val DASH_PENALTY_MS = 2_000L
    const val DASH_RELAXED_CARDS = 12
    const val HUNT_SCENE_MS = 90_000L
    const val HUNT_SCENE_LITTLE_MS = 120_000L
    const val HUNT_MISS_PENALTY_MS = 2_000L
    const val HUNT_HINT_SLOW_MS = 20_000L
    const val HUNT_HINT_FAST_MS = 10_000L
    const val MIN_CELL_DP = 56
    const val HUNT_TARGETS_TINY = 4
    const val HUNT_TARGETS_LITTLE = 5
    const val HUNT_TARGETS_STANDARD = 6
    const val HUNT_ITEMS_TINY = 12
    const val HUNT_ITEMS_LITTLE = 16
    const val HUNT_ITEMS_STANDARD = 22
}

/** One placed picture of a card, in the unit disc (centre 0,0 radius 1). Rotation in degrees. */
data class FindItCardSlot(val symbol: Int, val cx: Float, val cy: Float, val scale: Float, val rotationDeg: Float)

/** One picture scattered in a Group Hunt scene, on the virtual canvas x 0..1, y 0..1.333 (3:4). */
data class FindItSceneItem(val symbol: Int, val x: Float, val y: Float, val scale: Float, val rotationDeg: Float)

/** A hunt scene: [targets] each appear exactly once in [items]; other pictures are decoys (at most twice each). */
data class FindItScene(val items: List<FindItSceneItem>, val targets: List<Int>)

/** What the screen needs about one seat. */
data class FindItSeatState(
    val name: String,
    val isBot: Boolean,
    val score: Int,
    val misses: Int,
    val lockedUntilMs: Long,
    val hint: FindItHint,
    /** Picture numbers on the seat's own card (empty in Group Hunt). */
    val card: List<Int>
)

/** Immutable view the screen reads every frame. [EMPTY] is what a stub engine returns. */
data class FindItState(
    val phase: FindItPhase = FindItPhase.READY,
    val mode: FindItMode = FindItMode.MATCH_RACE,
    val seats: List<FindItSeatState> = emptyList(),
    /** The middle card's picture numbers (Match Race and Dash). */
    val centre: List<Int> = emptyList(),
    val round: Int = 0,
    val rounds: Int = 0,
    val timeLeftMs: Long = 0L,
    val clueActive: Boolean = false,
    val scene: FindItScene? = null,
    val found: Set<Int> = emptySet(),
    val sceneIndex: Int = 0,
    val scenes: Int = 0,
    val paused: Boolean = false,
    val lastWinnerSeat: Int = -1,
    /** Only ever grows; keys animations and sounds. */
    val eventSerial: Int = 0
) {
    companion object {
        val EMPTY = FindItState()
    }
}

/** One accepted or rejected tap in the replay log. [atMs] is engine time since GO, not wall time. */
@Serializable
data class FindItTapEvent(val atMs: Long, val seat: Int, val symbol: Int)

/** Everything needed to replay a game exactly: the seed, the config as ids, and every tap. */
@Serializable
data class FindItReplay(
    val version: Int = 1,
    val seed: Long = 0L,
    val mode: String = "race",
    val cardSize: String = "little",
    val length: String = "standard",
    val relaxed: Boolean = false,
    val handicap: String = "none",
    val huntHint: String = "slow",
    val seats: Int = 1,
    val taps: List<FindItTapEvent> = emptyList()
)

/**
 * Resume snapshot (JSON via [FindItSaveCodec]). Taken between rounds or mid-round; a mid-round restore replays the
 * round fresh with the same cards (the round clock restarts).
 *
 * VALIDATION TODO(find-it) in [FindItSaveCodec.validate]: version == 1; every enum id known; seed any; deckOrder is a
 * permutation of 0 until deckSize; pileIndex in 0..deckSize; ownCards.size == playerIds.size and each entry
 * in 0 until deckSize; centreCard in 0 until deckSize; scores and misses non-negative, at most 10_000, same size as
 * playerIds; round and sceneIndex in range for the length; elapsedMs in 0..3_600_000; playerIds match the live seats
 * (the shell hands the same ids back); reject anything else without throwing.
 */
@Serializable
data class FindItSave(
    val version: Int = 1,
    val mode: String = "race",
    val cardSize: String = "little",
    val length: String = "standard",
    val relaxed: Boolean = false,
    val handicap: String = "none",
    val huntHint: String = "slow",
    val oneSwitch: Boolean = false,
    val difficulty: String = "MEDIUM",
    val seed: Long = 0L,
    val rngCalls: Long = 0L,
    val deckOrder: List<Int> = emptyList(),
    val pileIndex: Int = 0,
    val round: Int = 0,
    val sceneIndex: Int = 0,
    val scores: List<Int> = emptyList(),
    val misses: List<Int> = emptyList(),
    val ownCards: List<Int> = emptyList(),
    val centreCard: Int = 0,
    val elapsedMs: Long = 0L,
    val playerIds: List<String> = emptyList()
)
