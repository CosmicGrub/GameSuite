package com.gamesuite.games.trailtogether

import com.gamesuite.settings.CpuDifficulty

/**
 * The rules of Trail Together (docs/design/kids-games/trail-together.md, section 2). Pure and deterministic for a
 * given seed; no Android, no Compose. Every call that acts takes the seat that is acting, so a later Nearby host can
 * validate who is allowed to do what without changing this contract.
 */
interface TrailEngine {
    var band: TrailBand
    var handicapSpec: String
    var difficulty: CpuDifficulty

    /** Set the table. Does not deal; call [start] for that. */
    fun setup(seats: List<TrailSeat>)

    /** Deals a fresh try with [seed] (trail, deck, hands, fog). */
    fun start(seed: Long)

    /** Null before [start]. */
    fun state(): TrailState?

    /** Play the hop, stone or lantern card at [handIndex] of [seat]. Never throws; illegal plays change nothing. */
    fun playCard(seat: Int, handIndex: Int, target: TrailTarget): TrailMoveResult

    /** Discard the card at [handIndex] of [seat] and draw (always legal while a try is running). */
    fun swap(seat: Int, handIndex: Int): TrailMoveResult

    /** Lets a helper pal seat play one turn. Returns false when it is not a helper's turn. */
    fun helperStep(): Boolean

    /** The suggested play for the seat whose turn it is, or null when none or not running. Never illegal. */
    fun hint(): TrailHint?

    fun pause()
    fun resume()

    /** Everything needed to continue, or null when there is nothing to save. */
    fun toSave(): TrailSave?

    /** True only when the engine is fully back in [save]; on false it must be left untouched. */
    fun restore(save: TrailSave): Boolean

    /** Tries won and finished in this session. */
    val sessionTries: Int
    val sessionWins: Int
}

/**
 * A stub that returns safe values and holds no state, so the game wrapper, hub spec and screen can compile against
 * the final contract. Replace with the real engine in phase 0.
 */
class StubTrailEngine : TrailEngine {
    override var band: TrailBand = TrailBand.MEDIUM
    override var handicapSpec: String = "none"
    override var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    override val sessionTries: Int get() = 0
    override val sessionWins: Int get() = 0

    override fun setup(seats: List<TrailSeat>) {}
    override fun start(seed: Long) {}
    override fun state(): TrailState? = null
    override fun playCard(seat: Int, handIndex: Int, target: TrailTarget): TrailMoveResult = TrailMoveResult.NOT_ACTIVE
    override fun swap(seat: Int, handIndex: Int): TrailMoveResult = TrailMoveResult.NOT_ACTIVE
    override fun helperStep(): Boolean = false
    override fun hint(): TrailHint? = null
    override fun pause() {}
    override fun resume() {}
    override fun toSave(): TrailSave? = null
    override fun restore(save: TrailSave): Boolean = false
}
