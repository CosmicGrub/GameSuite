package com.gamesuite.games.findit

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable

/** The three ways to play (docs/design/kids-games/find-it.md, section SCRIPT). */
enum class FindItMode(val id: String) {
    MATCH_RACE("race"), GROUP_HUNT("hunt"), SOLO_DASH("dash");

    companion object {
        fun fromId(id: String): FindItMode = when (id.lowercase()) {
            "hunt", "group_hunt" -> GROUP_HUNT
            "dash", "solo_dash" -> SOLO_DASH
            else -> MATCH_RACE
        }

        /**
         * The Hub gives every mode the same route, so the screen derives the mode from what the launch carries: only
         * Group Hunt has the `hint` option; Solo Dash is the one mode with a single seat and no `hint`.
         */
        fun fromLaunch(extras: Map<String, String>, seatCount: Int): FindItMode = when {
            "hint" in extras -> GROUP_HUNT
            seatCount <= 1 -> SOLO_DASH
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
enum class FindItCardSize(val id: String, val order: Int, val symbolsPerCard: Int, val deckSize: Int) {
    TINY("tiny", 3, 4, 13), LITTLE("little", 5, 6, 31), STANDARD("standard", 7, 8, 57);

    /** One step smaller (never below TINY): used when four same-screen seats cannot fit 56 dp cells. */
    fun smaller(): FindItCardSize = when (this) {
        STANDARD -> LITTLE
        else -> TINY
    }

    companion object {
        fun fromId(id: String): FindItCardSize = when (id.lowercase()) {
            "tiny" -> TINY
            "standard" -> STANDARD
            else -> LITTLE
        }
    }
}

/** Game length: Match Race and Dash rounds, Group Hunt scenes. */
enum class FindItLength(val id: String, val raceRounds: Int, val huntScenes: Int) {
    QUICK("quick", 15, 2), STANDARD("standard", 24, 3), LONG("long", 36, 4);

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

/** What the last change to the table was; the screen words, animates and announces it. */
enum class FindItEvent { NONE, DEAL, ROUND_WON, WRONG, FOUND, MISS, SCENE_CLEAR, TIMEOUT, OVER }

/** Badges on the score card. There is no loser badge. */
enum class FindItBadge { FASTEST, SHARP, COLLECTOR }

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
 * [timeScale] is the Game Speed multiplier for helper reaction times, hint timers and stall clues (never the human
 * lockout): 1.0 normal, above 1 slower.
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
    val seed: Long = 0L,
    val timeScale: Double = 1.0
) {
    /** True when [seat] (0-based) gets the hint glow handicap. */
    fun hasHintGlow(seat: Int): Boolean = when (handicap) {
        "all" -> true
        "p1" -> seat == 0
        "p2" -> seat == 1
        "p3" -> seat == 2
        "p4" -> seat == 3
        else -> false
    }

    /** No wrong-tap wait on Tiny cards, in Relaxed play and with one switch. */
    val lockoutEnabled: Boolean get() = !relaxed && !oneSwitch && cardSize != FindItCardSize.TINY
}

/** Balanced numbers in one place (see the SCRIPT section for the reasoning). All times in milliseconds. */
object FindItRules {
    const val LOCKOUT_MS = 1_500L
    const val STALL_MS = 20_000L
    const val STALL_MS_LITTLE = 30_000L
    const val STALL_MS_ONE_SWITCH = 10_000L
    const val CLUE_PULSE_MS = 2_000L
    const val CLUE_PERIOD_MS = 8_000L
    const val HINT_NARROW_MS = 5_000L
    const val HINT_PULSE_MS = 9_000L
    const val RELAXED_HINT_NARROW_MS = 3_000L
    const val RELAXED_HINT_PULSE_MS = 6_000L
    const val ROUND_WON_MS = 900L

    /** Solo Dash keeps its clock stopped during the celebration, so the celebration is short. */
    const val ROUND_WON_DASH_MS = 400L
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
    const val HUNT_SPOTLIGHT_AFTER_MS = 10_000L
    const val MIN_CELL_DP = 56
    const val HUNT_TARGETS_TINY = 4
    const val HUNT_TARGETS_LITTLE = 5
    const val HUNT_TARGETS_STANDARD = 6
    const val HUNT_ITEMS_TINY = 12
    const val HUNT_ITEMS_LITTLE = 16
    const val HUNT_ITEMS_STANDARD = 22

    /** Hard bounds for every counter a snapshot or a replay may carry. */
    const val MAX_COUNTER = 10_000
    const val MAX_ELAPSED_MS = 3_600_000L
    const val MAX_TAP_LOG = 10_000

    fun huntTargets(size: FindItCardSize): Int = when (size) {
        FindItCardSize.TINY -> HUNT_TARGETS_TINY
        FindItCardSize.LITTLE -> HUNT_TARGETS_LITTLE
        FindItCardSize.STANDARD -> HUNT_TARGETS_STANDARD
    }

    fun huntItems(size: FindItCardSize): Int = when (size) {
        FindItCardSize.TINY -> HUNT_ITEMS_TINY
        FindItCardSize.LITTLE -> HUNT_ITEMS_LITTLE
        FindItCardSize.STANDARD -> HUNT_ITEMS_STANDARD
    }

    fun dashMs(size: FindItCardSize): Long = if (size == FindItCardSize.STANDARD) DASH_MS else DASH_LITTLE_MS

    /** 90 s per scene, 120 s on Little and Big cards (more pictures to search). */
    fun huntSceneMs(size: FindItCardSize): Long = if (size == FindItCardSize.TINY) HUNT_SCENE_MS else HUNT_SCENE_LITTLE_MS

    fun stallMs(size: FindItCardSize, oneSwitch: Boolean): Long = when {
        oneSwitch -> STALL_MS_ONE_SWITCH
        size == FindItCardSize.LITTLE -> STALL_MS_LITTLE
        else -> STALL_MS
    }
}

/** One placed picture of a card, in the unit disc (centre 0,0 radius 1). Rotation in degrees. */
data class FindItCardSlot(val symbol: Int, val cx: Float, val cy: Float, val scale: Float, val rotationDeg: Float)

/** One picture scattered in a Group Hunt scene, on the virtual canvas x 0..1, y 0..1.333 (3:4). */
data class FindItSceneItem(val symbol: Int, val x: Float, val y: Float, val scale: Float, val rotationDeg: Float)

/** A hunt scene: [targets] each appear exactly once in [items]; other pictures are decoys (at most twice each). */
data class FindItScene(val items: List<FindItSceneItem>, val targets: List<Int>) {
    companion object {
        const val HEIGHT = 4f / 3f
        const val ITEM_RADIUS = 0.085f
    }
}

/** What the screen needs about one seat. Nothing here changes with the clock alone, so the screen is not recomposed every frame. */
data class FindItSeatState(
    val name: String,
    val isBot: Boolean,
    val score: Int,
    val misses: Int,
    /** True while the seat waits after a wrong tap. */
    val locked: Boolean,
    val hint: FindItHint,
    /** Picture numbers on the seat's own card (empty in Group Hunt). */
    val card: List<Int>,
    /** The seat's card number in the deck (keys its layout). -1 in Group Hunt. */
    val cardId: Int = -1,
    /** The three ringed pictures while [hint] is NARROW or PULSE (the answer is one of them); empty otherwise. */
    val candidates: List<Int> = emptyList(),
    /** The picture to pulse (hint PULSE, or a stall clue), -1 for none. */
    val pulse: Int = -1
)

/** The final standings, built once when the game reaches OVER. */
data class FindItResult(
    /** Seats that won (ties share). Group Hunt: every seat when anything was found. */
    val winnerSeats: List<Int>,
    /** Badges per seat; every seat has at least one. */
    val badges: List<List<FindItBadge>>,
    /** Group Hunt only: every scene was cleared ("We did it!"). */
    val cleared: Boolean = false,
    val tie: Boolean = false
)

/** Immutable view the screen reads. [EMPTY] is what an engine that has not started returns. */
data class FindItState(
    val phase: FindItPhase = FindItPhase.READY,
    val mode: FindItMode = FindItMode.MATCH_RACE,
    val seats: List<FindItSeatState> = emptyList(),
    /** The middle card's picture numbers (Match Race and Dash). */
    val centre: List<Int> = emptyList(),
    val centreId: Int = -1,
    /** 1-based number of the round being played (Match Race and Dash). */
    val round: Int = 0,
    /** Rounds in this game (Match Race, Relaxed Dash); 0 when the game is clock limited. */
    val rounds: Int = 0,
    /** Dash clock or Hunt sunset. Changes every frame: the wrapper publishes it separately. */
    val timeLeftMs: Long = 0L,
    /** The clock length in ms, 0 when there is no clock (Match Race, Relaxed, one-switch Hunt). */
    val clockMs: Long = 0L,
    val clueActive: Boolean = false,
    val scene: FindItScene? = null,
    val found: Set<Int> = emptySet(),
    /** Group Hunt: target picture to seat that found it. */
    val foundBy: Map<Int, Int> = emptyMap(),
    /** The target whose list icon glows (hunt hint stage 1), -1 for none. */
    val hintTarget: Int = -1,
    /** The picture the spotlight ring circles (hunt hint stage 2), -1 for none. */
    val spotlight: Int = -1,
    val sceneIndex: Int = 0,
    val scenes: Int = 0,
    /** Group Hunt: sunsets so far in this game ("tries"). */
    val tries: Int = 0,
    val paused: Boolean = false,
    val lastWinnerSeat: Int = -1,
    val event: FindItEvent = FindItEvent.NONE,
    val eventSeat: Int = -1,
    val eventSymbol: Int = -1,
    /** Only ever grows; keys animations and sounds. */
    val eventSerial: Int = 0,
    val result: FindItResult? = null,
    /** Engine time in ms since GO. Changes every frame: the wrapper publishes it separately. */
    val nowMs: Long = 0L
) {
    companion object {
        val EMPTY = FindItState()
    }
}

/**
 * One tap in the replay log. [atMs] is the engine time since GO when the tap was processed (not wall time); [stampMs]
 * is the tie-break time stamp the screen gave it (-1: the same as [atMs]).
 */
@Serializable
data class FindItTapEvent(val atMs: Long, val seat: Int, val symbol: Int, val stampMs: Long = -1L)

/**
 * Everything needed to replay a game exactly: the seed, the config as ids, and every person's tap. Helpers are not
 * logged: their taps come from the seed, so a replay regenerates them.
 */
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
    val oneSwitch: Boolean = false,
    val difficulty: String = "MEDIUM",
    val timeScale: Double = 1.0,
    val seats: Int = 1,
    /** Which seats are helpers (same size as [seats], or empty when none). */
    val botSeats: List<Boolean> = emptyList(),
    val taps: List<FindItTapEvent> = emptyList()
)

/**
 * Resume snapshot (JSON via [FindItSaveCodec]). Taken between rounds or mid-round; a mid-round restore replays the
 * round fresh with the same cards (the round clock restarts).
 *
 * Validation lives in [FindItSaveCodec.validate]: version 1; every enum id known; [deckOrder] a permutation of the
 * whole deck; [pileIndex] in 0..deckSize; [ownCards] one entry per seat, each in the deck; [centreCard] in the deck and
 * not held by any seat; [scores] and [misses] one per seat and in 0..10_000; [round] and [sceneIndex] in range for the
 * length; [elapsedMs] in 0..3_600_000; [playerIds] equal to the live seats'; anything else is rejected without throwing.
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
    /** How many times the pile was reshuffled (keys the next reshuffle stream). */
    val reshuffles: Int = 0,
    val deckOrder: List<Int> = emptyList(),
    val pileIndex: Int = 0,
    val round: Int = 0,
    val sceneIndex: Int = 0,
    val tries: Int = 0,
    val scores: List<Int> = emptyList(),
    val misses: List<Int> = emptyList(),
    val ownCards: List<Int> = emptyList(),
    val centreCard: Int = 0,
    /** Dash clock left (ms), or elapsed time in a clockless game: whatever the mode needs to continue. */
    val elapsedMs: Long = 0L,
    /** Hunt: target symbols found in the current scene. */
    val found: List<Int> = emptyList(),
    val playerIds: List<String> = emptyList()
)
