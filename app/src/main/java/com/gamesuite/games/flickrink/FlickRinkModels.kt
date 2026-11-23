package com.gamesuite.games.flickrink

import com.gamesuite.settings.CpuDifficulty

// Data model of Flick Rink (id `flick-rink`). Pure Kotlin: no Compose, no Android. Rules and numbers are in
// docs/design/kids-games/flick-rink.md; the numbers live in [FlickRinkRules]. Units are "rink units":
// the rink is 600 wide and 960 tall, x grows right, y grows DOWN (screen space). Angles are in tenths of a degree,
// clockwise from +x (so straight up is 2700).

/** How a match is played. [id] is the value of the hub extra `style`. */
enum class FlickStyle(val id: String) {
    TURNS("turns"), COOP("coop"), SOLO("solo");

    companion object {
        fun fromId(id: String?): FlickStyle = entries.firstOrNull { it.id == id } ?: TURNS
        fun isKnown(id: String?): Boolean = entries.any { it.id == id }
    }
}

/** What the rinks ask for. [id] is the hub extra `game`. */
enum class FlickGameKind(val id: String) {
    FISH_RUN("fishrun"), BULLSEYE("bullseye");

    companion object {
        fun fromId(id: String?): FlickGameKind = entries.firstOrNull { it.id == id } ?: FISH_RUN
        fun isKnown(id: String?): Boolean = entries.any { it.id == id }
    }
}

/**
 * How tricky the rinks are. [coopBudgetMultiplier] turns a level's par into the team's flick budget (rounded up).
 * [firstRink] is the 1-based index of the first rink of the band (the band's rinks run on from there).
 */
enum class FlickBand(val id: String, val coopBudgetMultiplier: Double, val firstRink: Int, val bullScale: Double) {
    LITTLE("little", 2.0, 1, 1.3), MEDIUM("medium", 1.6, 5, 1.0), BIG("big", 1.3, 10, 0.8);

    companion object {
        fun fromId(id: String?): FlickBand = entries.firstOrNull { it.id == id } ?: LITTLE
        fun isKnown(id: String?): Boolean = entries.any { it.id == id }
    }
}

/** Number of rinks (or bullseye boards) in a match. [id] is the hub extra `length`. */
enum class FlickLength(val id: String, val rinks: Int) {
    QUICK("quick", 2), STANDARD("standard", 3), LONG("long", 5);

    companion object {
        fun fromId(id: String?): FlickLength = entries.firstOrNull { it.id == id } ?: STANDARD
        fun isKnown(id: String?): Boolean = entries.any { it.id == id }
    }
}

/** The aim helper: how long the guide line is, in rink units. 0 draws nothing. */
enum class AimAssist(val id: String, val lengthUnits: Int) {
    OFF("off", 0), SHORT("short", 160), MEDIUM("medium", 320), LONG("long", 560);

    companion object {
        fun fromId(id: String?): AimAssist = entries.firstOrNull { it.id == id } ?: OFF
        fun isKnown(id: String?): Boolean = entries.any { it.id == id }
    }
}

/** The state machine. SETUP and START are screen states; the engine starts in [AIMING] once started. */
enum class FlickPhase {
    SETUP, START, AIMING, SLIDING, RESOLVING, RINK_DONE, MATCH_DONE
}

/**
 * One flick: [angleDeci] 0..3599 and [power] 0..100. This is the whole input of a turn (and what a replay stores).
 * [launchX] is the Bullseye launch point (80..520 along the launch line); Fish run ignores it.
 */
data class FlickInput(val angleDeci: Int, val power: Int, val launchX: Int = FlickRinkRules.LAUNCH_DEFAULT_X)

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
    val seed: Long = 0L,
    /** The seat that takes the first turn of the first rink (the Hub's "who moves first"). Later rinks rotate from it. */
    val firstSeat: Int = 0
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
 * One rink or board. [par] is the flick estimate for all three fish measured by the solver test (solo star thresholds and the
 * co-op budget use it). [posts] are round bumpers (restitution 0.90), [slow] are snow drifts (three times the damping),
 * [puddles] splash a pal back to where its turn started. A bullseye board has a [target] and no fish.
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
    val puddles: List<RinkDisc>,
    val target: RinkPoint? = null
) {
    val isBoard: Boolean get() = target != null
}

// ---- live state ---------------------------------------------------------------------------------

/** A puck pal. [fish] is the index of the fish it carries, or -1. */
data class FlickPal(val seat: Int, val x: Double, val y: Double, val fish: Int) {
    val carrying: Boolean get() = fish >= 0
}

/** One fish of the rink. [state]: 0 free on the ice, 1 carried by [carrier], 2 banked. */
data class FlickFish(val x: Int, val y: Int, val state: Int, val carrier: Int)

/** A bullseye disc on the board (thrown ones only, in throw order). [points] is what it is worth where it lies now. */
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
    val fish: List<FlickFish>,
    /** Fish brought home on this rink (all seats together), and how many the rink needs. */
    val fishBanked: Int,
    val fishGoal: Int,
    /** Flicks (or discs thrown) on this rink, all seats together. */
    val flicksUsed: Int,
    /** Flicks the active seat (Turns) or the team (Co-op) has left on this rink; -1 when unlimited (Solo, Relaxed). */
    val flicksLeft: Int,
    val gateOpen: List<Boolean>,
    val sliderPose: List<Int>,
    /** Per seat: fish banked this match (fish run) or points (bullseye). */
    val seatScores: List<Int>,
    /** Per seat: flicks this match. */
    val seatFlicks: List<Int>,
    /** Bullseye: discs each seat still has to throw on this board. */
    val discsLeft: List<Int>,
    /** Failed tries of this rink so far (co-op). */
    val coopRetries: Int,
    /** Co-op: the team's flick budget on this rink, retry bonus included. 0 elsewhere. */
    val budget: Int,
    /** The rink's par (fish run) or 0. */
    val par: Int,
    /** RINK_DONE: the rink was cleared (three fish home, or a board finished). */
    val rinkCleared: Boolean,
    /** Stars of the rinks cleared so far in this match (co-op and solo), one per cleared rink. */
    val rinkStars: List<Int>,
    val matchStars: Int,
    /** Flicks taken in the whole match so far (it also seeds the bot noise). */
    val turn: Int
) {
    companion object {
        /** The safe "nothing yet" state the stub engine returns. */
        val EMPTY = FlickRinkState(
            phase = FlickPhase.SETUP, style = FlickStyle.TURNS, kind = FlickGameKind.FISH_RUN, activeSeat = 0,
            rinkIndex = 0, rinkCount = 0, levelId = "", pals = emptyList(), discs = emptyList(), fish = emptyList(),
            fishBanked = 0, fishGoal = 3, flicksUsed = 0, flicksLeft = -1,
            gateOpen = emptyList(), sliderPose = emptyList(), seatScores = emptyList(), seatFlicks = emptyList(),
            discsLeft = emptyList(), coopRetries = 0, budget = 0, par = 0, rinkCleared = false,
            rinkStars = emptyList(), matchStars = 0, turn = 0
        )
    }
}

/** Things that happened during one flick, in order. The screen turns them into sound, haptics and words. */
sealed interface FlickEvent {
    val step: Int

    data class WallBump(override val step: Int, val seat: Int, val speed: Float) : FlickEvent
    data class PalBump(override val step: Int, val seatA: Int, val seatB: Int, val speed: Float) : FlickEvent
    data class FishCollected(override val step: Int, val seat: Int, val fish: Int) : FlickEvent
    data class FishBanked(override val step: Int, val seat: Int, val fish: Int) : FlickEvent
    data class RelayPass(override val step: Int, val fromSeat: Int, val toSeat: Int, val fish: Int) : FlickEvent
    data class Splash(override val step: Int, val seat: Int) : FlickEvent
    data class GateFlipped(override val step: Int, val gateId: String, val open: Boolean) : FlickEvent
    data class DiscScored(override val step: Int, val seat: Int, val points: Int) : FlickEvent

    /** At the end of a round the sliding walls swapped pose (emitted after the last sim step of that flick). */
    data class WallsMoved(override val step: Int) : FlickEvent
}

/**
 * The result of one flick: the trajectory for playback and the events. [frames] holds one FloatArray per recorded frame
 * (frame 0 is the start, then every 2nd sim step, 60 frames a second, then the settled end): x0, y0, x1, y1, ... for every
 * body, in body order. Empty when the engine was asked not to record (bots, reduced-motion skip).
 */
class FlickOutcome(
    val steps: Int,
    val settled: Boolean,
    val events: List<FlickEvent>,
    val frames: List<FloatArray>,
    val stateHash: Long,
    /** Bodies per frame: pals in seat order (fish run) or discs in throw order up to and including this one (bullseye). */
    val bodyCount: Int = 0
) {
    companion object {
        val NONE = FlickOutcome(steps = 0, settled = true, events = emptyList(), frames = emptyList(), stateHash = 0L)
    }
}

/**
 * What a trial flick would do, from [FlickRinkEngine.probe]: the real simulation on a scratch copy, nothing committed.
 * The computer pal scores its candidates with this.
 */
class FlickProbe(
    val steps: Int,
    val endX: Double,
    val endY: Double,
    /** The flicking body picked up a fish (or got one by relay). */
    val picked: Boolean,
    /** Fish banked by this flick (all seats), and on the rink in total afterwards. */
    val banked: Int,
    val bankedTotal: Int,
    /** The flicking body carries a fish when everything has stopped. */
    val carrying: Boolean,
    val splashed: Boolean,
    /** Gates whose state flipped, and how many gates are open afterwards. */
    val gatesFlipped: Int,
    val gatesOpen: Int,
    val endsInDrift: Boolean,
    val bumps: Int,
    /** Bullseye: points of the thrown disc where it lies, and how many other discs moved more than 20 units. */
    val points: Int,
    val othersMoved: Int,
    /** Bullseye: this seat's board points minus the best rival's, counting every disc where it lies afterwards. */
    val margin: Int,
    /** Free fish left on the ice afterwards. */
    val fishFree: Int
)

/** How a finished match ended, for the score card. [stars] is 0 for turn-taking matches. */
data class FlickMatchSummary(
    val winners: List<Int>,
    val seatScores: List<Int>,
    /** Flicks used by each seat over the match. */
    val seatFlicks: List<Int>,
    val flicksUsed: Int,
    val stars: Int,
    val cleared: Boolean,
    /** Co-op and solo: stars of every rink cleared. */
    val rinkStars: List<Int> = emptyList()
)
