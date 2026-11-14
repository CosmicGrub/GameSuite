package com.gamesuite.games.stackzoo

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable

enum class ZooMode { COOP_TOWER, TAKE_TURNS, SOLO_BALANCE;
    companion object {
        fun fromId(id: String): ZooMode = when (id.lowercase()) {
            "turns", "turns-cpu", "take_turns" -> TAKE_TURNS
            "solo", "solo_balance" -> SOLO_BALANCE
            else -> COOP_TOWER
        }
    }
}

/** READY -> CHOOSING (pick an animal) -> POSITIONING (drag) -> SETTLING (physics runs) -> TURN_RESULT -> CHOOSING ... OVER. */
enum class ZooPhase { READY, CHOOSING, POSITIONING, SETTLING, TURN_RESULT, OVER }

/** Snap assist strength for a seat (the handicap): none, small, large. */
enum class ZooAssist { NONE, SMALL, LARGE }

/** Co-op goal: flag height above the log in u and the size of the shared bag. */
enum class ZooGoal(val height: Double, val bag: Int) {
    LITTLE(4.0, 12), MEDIUM(6.0, 16), TALL(8.0, 20);

    companion object {
        fun fromId(id: String): ZooGoal = when (id.lowercase()) {
            "little" -> LITTLE
            "tall" -> TALL
            else -> MEDIUM
        }
    }
}

/** Take Turns hand size: quick 4, standard 5, long 7 animals per player. */
enum class ZooLength(val handSize: Int) {
    QUICK(4), STANDARD(5), LONG(7);

    companion object {
        fun fromId(id: String): ZooLength = when (id.lowercase()) {
            "quick" -> QUICK
            "long" -> LONG
            else -> STANDARD
        }
    }
}

enum class ZooTier { EASY, MEDIUM, HARD }

enum class ZooReleaseResult { NOT_POSITIONING, REJECTED, PLACED }

/** A point in an animal's own frame (x right, y up, units u). */
data class ZooVertex(val x: Double, val y: Double)

/** One convex part, vertices counter-clockwise, 3 to 8 of them. */
data class ZooPart(val vertices: List<ZooVertex>)

/**
 * One animal: 1 to 4 convex parts (their union is the possibly concave silhouette). [mass] is authored; the centre of
 * mass and the moment of inertia are derived from the parts and scaled to that mass at load.
 */
data class AnimalSpec(
    val index: Int,
    val id: String,
    val nameKey: String,
    val mass: Double,
    val friction: Double,
    val tier: ZooTier,
    val parts: List<ZooPart>
)

data class ZooSeat(val playerId: String, val name: String, val isBot: Boolean)

/**
 * Everything the Hub chose. [assist] is "none" | "all" | "p1".."p4". [seed] 0 means "draw one at start".
 */
data class ZooConfig(
    val mode: ZooMode = ZooMode.COOP_TOWER,
    val goal: ZooGoal = ZooGoal.MEDIUM,
    val length: ZooLength = ZooLength.STANDARD,
    val assist: String = "none",
    val relaxed: Boolean = false,
    val oneSwitch: Boolean = false,
    val difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
    val seed: Long = 0L
)

/** A quantised placement: x in 1/1024 u, angle in 1/1024 rad. Ints so a replay log is exact. */
@Serializable
data class ZooPlacement(val xQ: Int = 0, val angleQ: Int = 0)

/** One release in the replay log; [stepIndex] is the physics step it happened on. */
@Serializable
data class ZooReleaseEvent(val stepIndex: Long, val pieceId: Int, val xQ: Int, val angleQ: Int)

@Serializable
data class ZooReplay(
    val version: Int = 1,
    val physicsVersion: Int = 1,
    val seed: Long = 0L,
    val mode: String = "coop",
    val goal: String = "medium",
    val length: String = "standard",
    val assist: String = "none",
    val relaxed: Boolean = false,
    val seats: Int = 1,
    val releases: List<ZooReleaseEvent> = emptyList()
)

/** What the screen draws for one animal on the log. */
data class ZooBodyView(
    val pieceId: Int,
    val animalIndex: Int,
    val owner: Int,
    val x: Double,
    val y: Double,
    val angle: Double,
    val fallen: Boolean,
    val asleep: Boolean
)

data class ZooSeatState(val name: String, val isBot: Boolean, val handCount: Int, val stacked: Int)

/** Immutable view the screen reads every frame. [EMPTY] is what a stub engine returns. */
data class ZooState(
    val phase: ZooPhase = ZooPhase.READY,
    val mode: ZooMode = ZooMode.COOP_TOWER,
    val activeSeat: Int = 0,
    val seats: List<ZooSeatState> = emptyList(),
    /** Piece ids the active seat may pick from (the offer of 3, or the hand in Take Turns). */
    val offer: List<Int> = emptyList(),
    val bagLeft: Int = 0,
    val goalHeight: Double = 0.0,
    val towerHeight: Double = 0.0,
    val baseAngle: Double = 0.0,
    val livesLeft: Int = 0,
    val fellLastTurn: Int = 0,
    val stars: Int = 0,
    val bodies: List<ZooBodyView> = emptyList(),
    val paused: Boolean = false,
    /** Only ever grows; keys animations and sounds. */
    val eventSerial: Int = 0
) {
    companion object {
        val EMPTY = ZooState()
    }
}

/** One body in a snapshot. Doubles round-trip exactly through JSON. */
@Serializable
data class ZooBodySave(
    val pieceId: Int = 0,
    val animalIndex: Int = 0,
    val owner: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val angle: Double = 0.0,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val omega: Double = 0.0,
    val restSteps: Int = 0,
    val fallen: Boolean = false
)

/**
 * Resume snapshot (JSON via [StackZooSaveCodec]), taken at any moment except while a piece is being dragged
 * (state-exact; the contact warm-start cache is not saved, so a restored run matches an uninterrupted one only within
 * 1e-6, which the replay test accepts).
 *
 * VALIDATION TODO(stack-zoo) in [StackZooSaveCodec.validate]: version == 1 and physicsVersion == 1; every enum id
 * known; every double finite and |value| below 1000; baseAngle within +-BASE_MAX_ANGLE; animalIndex in 0 until 20;
 * pieceIds unique and below 64; bodies.size at most MAX_BODIES; each hand and the bag only hold known piece ids and
 * no id appears twice anywhere; owner in 0 until playerIds.size; activeSeat in range; lives 0..5; stepIndex >= 0;
 * playerIds match the live seats; reject anything else without throwing.
 */
@Serializable
data class ZooSave(
    val version: Int = 1,
    val physicsVersion: Int = 1,
    val mode: String = "coop",
    val goal: String = "medium",
    val length: String = "standard",
    val assist: String = "none",
    val relaxed: Boolean = false,
    val oneSwitch: Boolean = false,
    val difficulty: String = "MEDIUM",
    val seed: Long = 0L,
    val rngCalls: Long = 0L,
    val stepIndex: Long = 0L,
    val baseAngle: Double = 0.0,
    val baseOmega: Double = 0.0,
    val bodies: List<ZooBodySave> = emptyList(),
    /** Piece id to animal index for every piece in the game (hands, bag, bodies). */
    val pieceAnimals: List<Int> = emptyList(),
    val hands: List<List<Int>> = emptyList(),
    val bag: List<Int> = emptyList(),
    val activeSeat: Int = 0,
    val turnNumber: Int = 0,
    val lives: Int = 0,
    val holdSteps: Int = 0,
    val playerIds: List<String> = emptyList()
)
