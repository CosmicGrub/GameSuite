package com.gamesuite.games.flickrink

import com.gamesuite.settings.CpuDifficulty

// Data model of Flick Rink (id `flick-rink`). Pure Kotlin: no Compose, no Android. Rules and numbers are in
// docs/design/kids-games/flick-rink.md; the numbers live in [FlickRinkRules]. Units are "rink units":
// the rink is 600 wide and 960 tall, x grows right, y grows DOWN (screen space). Angles are in tenths of a degree,
// clockwise from +x (so straight up is 2700). Nothing in this file has been compiled or run.

/** How a match is played. [id] is the value of the hub extra `style`. */
enum class FlickStyle(val id: String) {
    TURNS("turns"), COOP("coop"), SOLO("solo");

    companion object {
        fun fromId(id: String?): FlickStyle = entries.firstOrNull { it.id == id } ?: TURNS
    }
}

/** What the rinks ask for. [id] is the hub extra `game`. */
enum class FlickGameKind(val id: String) {
    FISH_RUN("fishrun"), BULLSEYE("bullseye");

    companion object {
        fun fromId(id: String?): FlickGameKind = entries.firstOrNull { it.id == id } ?: FISH_RUN
    }
}

/**
 * How tricky the rinks are. [coopBudgetMultiplier] turns a level's par into the team's flick budget (rounded up).
 * [firstRink] is the 1-based index of the first rink of the band (the band's rinks run on from there).
 */
enum class FlickBand(val id: String, val coopBudgetMultiplier: Double, val firstRink: Int) {
    LITTLE("little", 2.0, 1), MEDIUM("medium", 1.6, 5), BIG("big", 1.3, 10);

    companion object {
        fun fromId(id: String?): FlickBand = entries.firstOrNull { it.id == id } ?: LITTLE
    }
}

/** Number of rinks (or bullseye boards) in a match. [id] is the hub extra `length`. */
enum class FlickLength(val id: String, val rinks: Int) {
    QUICK("quick", 2), STANDARD("standard", 3), LONG("long", 5);

    companion object {
        fun fromId(id: String?): FlickLength = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

/** The aim helper: how long the guide line is, in rink units. 0 draws nothing. */
enum class AimAssist(val id: String, val lengthUnits: Int) {
    OFF("off", 0), SHORT("short", 160), MEDIUM("medium", 320), LONG("long", 560);

    companion object {
        fun fromId(id: String?): AimAssist = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The state machine. SETUP and START are screen states; the engine starts in [AIMING] once started. */
enum class FlickPhase {
    SETUP, START, AIMING, SLIDING, RESOLVING, RINK_DONE, MATCH_DONE
}

/** One flick: [angleDeci] 0..3599 and [power] 0..100. This is the whole input of a turn (and what a replay stores). */
data class FlickInput(val angleDeci: Int, val power: Int)

/** Whole-match settings, set from the hub's launch options before [FlickRinkEngine.configure]. */
data class FlickConfig(
    val style: FlickStyle = FlickStyle.TURNS,
    val kind: FlickGameKind = FlickGameKind.FISH_RUN,
    val band: FlickBand = FlickBand.LITTLE,
    val length: FlickLength = FlickLength.STANDARD,
    val relaxed: Boolean = false,
    val dragAnywhere: Boolean = true,
    val switchAiming: Boolean = false,
    /** Which seats get the helper: "none", "all" or "p1".."p4". */
    val assistFor: String = "none",
    val assist: AimAssist = AimAssist.MEDIUM,
    val difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
    /** Solo practice only: the 1-based rink to play. */
    val soloRink: Int = 1,
    /** Team flick budget feel in co-op: "plenty" (x1.25), "normal" (x1), "tight" (x0.8) on top of the band multiplier. */
    val budget: String = "normal",
    val seed: Long = 0L
)

/** One player (or computer pal). [colorIndex] picks the colour AND the shape badge, so colour is never the only cue. */
data class FlickSeat(
    val playerId: String,
    val name: String,
    val isBot: Boolean,
    val colorIndex: Int,
    val assist: AimAssist = AimAssist.OFF
)

// ---- level format -------------------------------------------------------------------------------

data class RinkSegment(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
data class RinkDisc(val x: Int, val y: Int, val r: Int)
data class RinkRect(val x: Int, val y: Int, val w: Int, val h: Int)
data class RinkPoint(val x: Int, val y: Int)

/** A gate: a wall segment that is closed until a pal passes over its [pad]; the pad flips it each time (open <-> closed). */
data class RinkGate(val id: String, val seg: RinkSegment, val pad: RinkDisc)

/** A sliding wall: pose A or pose B, swapped at the end of every round. */
data class RinkSlider(val id: String, val a: RinkSegment, val b: RinkSegment)

/**
 * One rink. [par] is the designer's flick estimate for all three fish (solo star thresholds and the co-op budget use it).
 * [posts] are round bumpers (restitution 0.90), [slow] are snow drifts (three times the damping), [puddles] splash a pal
 * back to where its turn started.
 */
data class RinkLevel(
    val id: String,
    val name: String,
    val band: FlickBand,
    val par: Int,
    val seed: Int,
    val fish: List<RinkPoint>,
    val walls: List<RinkSegment>,
    val posts: List<RinkDisc>,
    val gates: List<RinkGate>,
    val sliders: List<RinkSlider>,
    val slow: List<RinkRect>,
    val puddles: List<RinkDisc>
)

// ---- live state ---------------------------------------------------------------------------------

data class FlickPal(val seat: Int, val x: Double, val y: Double, val carrying: Boolean)

/** A bullseye disc. [thrown] false means it is still in the player's hand. */
data class FlickDisc(val seat: Int, val x: Double, val y: Double, val thrown: Boolean, val points: Int)

/** What the screen needs to draw and to talk about the match. A snapshot of the engine, safe to hold on to. */
data class FlickRinkState(
    val phase: FlickPhase,
    val style: FlickStyle,
    val kind: FlickGameKind,
    val activeSeat: Int,
    val rinkIndex: Int,
    val rinkCount: Int,
    val levelId: String,
    val pals: List<FlickPal>,
    val discs: List<FlickDisc>,
    val fish: RinkPoint?,
    /** The seat carrying the fish, or -1. */
    val fishCarrier: Int,
    /** Fish brought home on this rink (all seats together), and how many the rink needs. */
    val fishBanked: Int,
    val fishGoal: Int,
    val flicksUsed: Int,
    /** -1 when unlimited (solo, relaxed). */
    val flicksLeft: Int,
    val gateOpen: List<Boolean>,
    val sliderPose: List<Int>,
    /** Per seat: fish banked this match (fish run) or points (bullseye). */
    val seatScores: List<Int>,
    val coopRetries: Int,
    val matchStars: Int
) {
    companion object {
        /** The safe "nothing yet" state the stub engine returns. */
        val EMPTY = FlickRinkState(
            phase = FlickPhase.SETUP, style = FlickStyle.TURNS, kind = FlickGameKind.FISH_RUN, activeSeat = 0,
            rinkIndex = 0, rinkCount = 0, levelId = "", pals = emptyList(), discs = emptyList(), fish = null,
            fishCarrier = -1, fishBanked = 0, fishGoal = 3, flicksUsed = 0, flicksLeft = -1,
            gateOpen = emptyList(), sliderPose = emptyList(), seatScores = emptyList(), coopRetries = 0, matchStars = 0
        )
    }
}

/** Things that happened during one flick, in order. The screen turns them into sound, haptics and words. */
sealed interface FlickEvent {
    val step: Int

    data class WallBump(override val step: Int, val seat: Int, val speed: Float) : FlickEvent
    data class PalBump(override val step: Int, val seatA: Int, val seatB: Int, val speed: Float) : FlickEvent
    data class FishCollected(override val step: Int, val seat: Int) : FlickEvent
    data class FishBanked(override val step: Int, val seat: Int) : FlickEvent
    data class RelayPass(override val step: Int, val fromSeat: Int, val toSeat: Int) : FlickEvent
    data class Splash(override val step: Int, val seat: Int) : FlickEvent
    data class GateFlipped(override val step: Int, val gateId: String, val open: Boolean) : FlickEvent
    data class DiscScored(override val step: Int, val seat: Int, val points: Int) : FlickEvent
}

/**
 * The result of one flick: the trajectory for playback and the events. [frames] holds one FloatArray per recorded frame
 * (every 2nd sim step, 60 frames a second): x0, y0, x1, y1, ... for every moving body, in body order. Empty when the
 * engine was asked not to record (bots, reduced-motion skip).
 */
class FlickOutcome(
    val steps: Int,
    val settled: Boolean,
    val events: List<FlickEvent>,
    val frames: List<FloatArray>,
    val stateHash: Long
) {
    companion object {
        val NONE = FlickOutcome(steps = 0, settled = true, events = emptyList(), frames = emptyList(), stateHash = 0L)
    }
}

/** How a finished match ended, for the score card. [stars] is 0 for turn-taking matches. */
data class FlickMatchSummary(
    val winners: List<Int>,
    val seatScores: List<Int>,
    val flicksUsed: Int,
    val stars: Int,
    val cleared: Boolean
)
