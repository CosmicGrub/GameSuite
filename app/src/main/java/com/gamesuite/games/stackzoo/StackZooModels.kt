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
    val oneSwitch: Boolean = false,
    val seats: Int = 1,
    val releases: List<ZooReleaseEvent> = emptyList()
)

/** What happened last, so the screen can play one sound or haptic per event ([ZooState.eventSerial] keys it). */
enum class ZooEvent { NONE, BEGAN, CHOSE, PLACED, STEADY, FELL, HOLDING, WON, LOST, FINISHED }

/** How a game ended. */
enum class ZooOutcome { NONE, COOP_WON, COOP_LOST, SOLO_DONE, WINNER, TIE }

data class ZooSeatState(
    val name: String,
    val isBot: Boolean,
    val handCount: Int,
    /** Animals this seat placed that are still standing. */
    val stacked: Int,
    /** Placements this seat made. */
    val placements: Int = 0,
    /** Placements without any animal falling. */
    val clean: Int = 0
)

/**
 * Immutable, coarse view the screen reads in composition. It changes only at discrete events (a pick, a release, a turn
 * result, the end), never every frame: the per-frame poses live in [ZooBodyBuffer].
 */
data class ZooState(
    val phase: ZooPhase = ZooPhase.READY,
    val mode: ZooMode = ZooMode.COOP_TOWER,
    val activeSeat: Int = 0,
    val seats: List<ZooSeatState> = emptyList(),
    /** Piece ids the active seat may pick from (the offer of 3, or the hand in Take Turns), and their animals. */
    val offer: List<Int> = emptyList(),
    val offerAnimals: List<Int> = emptyList(),
    val chosen: Int = -1,
    val chosenAnimal: Int = -1,
    val bagLeft: Int = 0,
    val bagTotal: Int = 0,
    val goalHeight: Double = 0.0,
    val towerHeight: Double = 0.0,
    val livesLeft: Int = 0,
    val lives: Int = 0,
    val fellLastTurn: Int = 0,
    val standing: Int = 0,
    val stars: Int = 0,
    val holding: Boolean = false,
    val outcome: ZooOutcome = ZooOutcome.NONE,
    /** Bit i set: seat i won (Take Turns, or Co-op / Solo where everyone is a winner). */
    val winnerMask: Int = 0,
    val handSize: Int = 0,
    val turnNumber: Int = 0,
    val activeAssisted: Boolean = false,
    val paused: Boolean = false,
    val event: ZooEvent = ZooEvent.NONE,
    val eventArg: Int = 0,
    /** Only ever grows; keys animations and sounds. */
    val eventSerial: Int = 0
) {
    companion object {
        val EMPTY = ZooState()
    }
}

/**
 * Reusable poses of every animal in the world, filled by [StackZooEngine.fillBodies]. The screen reads it in the draw
 * phase only, so nothing is allocated per frame.
 */
class ZooBodyBuffer {
    var count = 0
    var baseAngle = 0.0
    var baseOmega = 0.0
    val pieceId = IntArray(ZooPhysics.MAX_BODIES)
    val animalIndex = IntArray(ZooPhysics.MAX_BODIES)
    val owner = IntArray(ZooPhysics.MAX_BODIES)
    val x = DoubleArray(ZooPhysics.MAX_BODIES)
    val y = DoubleArray(ZooPhysics.MAX_BODIES)
    val angle = DoubleArray(ZooPhysics.MAX_BODIES)
    val fallen = BooleanArray(ZooPhysics.MAX_BODIES)
    val asleep = BooleanArray(ZooPhysics.MAX_BODIES)
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
 * Resume snapshot (JSON via [StackZooSaveCodec]). A drag in progress is saved as the turn start (the animal goes back
 * to the shelf). The contact warm-start cache is not saved, so a restored run matches an uninterrupted one only within
 * 1e-6; replay equality applies to uninterrupted sessions.
 *
 * Validation lives in [StackZooSaveCodec.validate] and [ZooEngine.restore]: versions, known enum ids, every double
 * finite and bounded, animal indexes in 0..19, unique piece ids that each live in exactly one of bag, hand or world,
 * counters inside their bounds, the replay log in order, and finally the state hash recomputed from the restored world
 * must equal [stateHash]. Anything else is rejected without throwing and the shell starts a fresh game.
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
    val baseRest: Int = 0,
    val bodies: List<ZooBodySave> = emptyList(),
    /** Piece id to animal index for every piece in the game (hands, bag, bodies). */
    val pieceAnimals: List<Int> = emptyList(),
    val hands: List<List<Int>> = emptyList(),
    val bag: List<Int> = emptyList(),
    val bagTotal: Int = 0,
    val activeSeat: Int = 0,
    val turnNumber: Int = 0,
    val lives: Int = 0,
    val holdSteps: Int = 0,
    val phase: String = "CHOOSING",
    val settleSteps: Int = 0,
    val resultSteps: Int = 0,
    val fellThisTurn: Int = 0,
    val fellLastTurn: Int = 0,
    val pendingFallen: List<Int> = emptyList(),
    val placements: List<Int> = emptyList(),
    val clean: List<Int> = emptyList(),
    val tallestSeat: Int = -1,
    val tallestHeight: Double = 0.0,
    val pendingWinner: Int = -1,
    val releases: List<ZooReleaseEvent> = emptyList(),
    val stateHash: Long = 0L,
    val playerIds: List<String> = emptyList()
)
