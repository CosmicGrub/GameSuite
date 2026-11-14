package com.gamesuite.games.flickrink

import com.gamesuite.settings.CpuDifficulty

/**
 * Reads the level list. The format is one JSON object per line (see [FlickRinkLevelData]); the full schema is in the design
 * doc. The scaffold does not parse yet: [all] returns an empty list, which every caller must treat as "no levels, show nothing".
 *
 * TODO(phase 1): parse with kotlinx.serialization.json (ignoreUnknownKeys), validate (three fish, everything inside the
 * 600 x 960 rink, ids rink01..rink14 in order) and cache. A bad line must be skipped, never thrown.
 */
object FlickRinkLevels {
    fun all(): List<RinkLevel> = emptyList()

    fun byIndex(oneBased: Int): RinkLevel? = all().getOrNull(oneBased - 1)

    /** The 1-based rink numbers a match plays, in order: the band's rinks from its first one, [FlickLength.rinks] of them. */
    fun matchRinks(band: FlickBand, length: FlickLength): List<Int> =
        (0 until length.rinks).map { band.firstRink + it }
}

/**
 * The Flick Rink rules and physics, with no Compose and no Android. A fixed-step (120 Hz) deterministic simulation on an
 * injected seed: the same level, seed and list of [FlickInput]s always produce the same trajectory and state hash.
 *
 * Contract (all of it is TODO in the stub):
 *  - [flick] runs the WHOLE slide to rest in one call (at most [FlickRinkRules.MAX_STEPS] steps), applies the rules
 *    (pick-up, banking, splash, gate pads, relay hand-off, scoring), moves on to the next turn, and returns the outcome.
 *    The screen plays the recorded frames back at display rate; a Reduced-Motion player may skip the playback.
 *  - Inputs outside [FlickRinkRules.isInRange] are rejected (the outcome is [FlickOutcome.NONE], nothing changes).
 *  - [previewLine] is the aim helper: a short polyline of where the flick would go (no pal bumps), at most [maxLength] long.
 *  - [stateHash] covers positions (rounded to 1/16 unit), fish, gate and slider poses, scores, flick counts and the RNG state,
 *    and ignores the recorded frames.
 *  - No method allocates inside the step loop.
 */
interface FlickRinkEngine {
    /** Sets up a match. [seats] is 1 seat (solo) to 4. Returns false (and changes nothing) when the config does not fit the seats. */
    fun configure(config: FlickConfig, seats: List<FlickSeat>, levels: List<RinkLevel> = FlickRinkLevels.all()): Boolean

    /** Starts the first rink. */
    fun start()

    fun state(): FlickRinkState

    /** True when the engine would accept this flick right now (phase is AIMING and the input is in range). */
    fun canFlick(input: FlickInput): Boolean

    /** Plays one flick for the active seat. [record] false skips the frame buffer (bots, skipping the slide). */
    fun flick(input: FlickInput, record: Boolean = true): FlickOutcome

    /** The aim helper polyline for the active seat: x0, y0, x1, y1, ... Empty when the seat has no helper. */
    fun previewLine(input: FlickInput, maxLength: Int): FloatArray

    /** After RINK_DONE: moves to the next rink, or finishes the match. Returns false when there is no next rink. */
    fun nextRink(): Boolean

    /** Co-op, after a failed rink: the same rink again with the retry bonus. False when the rink was not failed. */
    fun retryRink(): Boolean

    fun summary(): FlickMatchSummary?

    fun stateHash(): Long

    /** The resumable form, or null when there is nothing to save. */
    fun toSave(): FlickSave?

    /** Replays [save] from its seed. False (engine untouched) when it is invalid for these seats or this physics version. */
    fun restore(save: FlickSave): Boolean
}

/** The scaffold engine: every call returns a safe value and changes nothing. Replace in phase 1. */
class StubFlickRinkEngine : FlickRinkEngine {
    override fun configure(config: FlickConfig, seats: List<FlickSeat>, levels: List<RinkLevel>): Boolean = false
    override fun start() {}
    override fun state(): FlickRinkState = FlickRinkState.EMPTY
    override fun canFlick(input: FlickInput): Boolean = false
    override fun flick(input: FlickInput, record: Boolean): FlickOutcome = FlickOutcome.NONE
    override fun previewLine(input: FlickInput, maxLength: Int): FloatArray = FloatArray(0)
    override fun nextRink(): Boolean = false
    override fun retryRink(): Boolean = false
    override fun summary(): FlickMatchSummary? = null
    override fun stateHash(): Long = 0L
    override fun toSave(): FlickSave? = null
    override fun restore(save: FlickSave): Boolean = false
}

/**
 * The computer pal. Picks the flick for the active seat. Noise is injected from the seed so a replay plays the same match.
 *
 * Behaviour per level (docs: SCRIPT > Bot behaviour): EASY aims at the target with 9 degrees of angle noise and 14 points of
 * power noise and never plans bank shots; MEDIUM tries the direct line and 24 bounce candidates with 4 degrees and 7 points of
 * noise; HARD searches 160 candidates with 1.5 degrees and 3 points of noise. Budget: at most 150 000 sim steps per decision.
 */
interface FlickRinkBot {
    fun choose(engine: FlickRinkEngine, difficulty: CpuDifficulty, seed: Long): FlickInput
}

/** The scaffold bot: a safe straight-up flick at half power. */
class StubFlickRinkBot : FlickRinkBot {
    override fun choose(engine: FlickRinkEngine, difficulty: CpuDifficulty, seed: Long): FlickInput =
        FlickInput(angleDeci = 2700, power = 50)
}
