package com.gamesuite.games.stackzoo

/**
 * The rules and the physics of Stack Zoo with no Compose, no Android and no clock. Time only moves through [advance]
 * in whole fixed steps ([ZooPhysics.DT]), so a seed plus a release log replays bit for bit. The game wrapper
 * ([StackZooGame]) turns frame time into step counts and owns locking and the shell lifecycle.
 *
 * Lifecycle: [setup] -> [start] (READY) -> [begin] -> [choose] -> [release] -> [advance] until settled -> next turn ... OVER.
 */
interface StackZooEngine {
    /** Seats are people first, then helpers. Resets everything; [ZooConfig.seed] (never 0 from the wrapper) fixes the bag and every bot choice. */
    fun setup(config: ZooConfig, seats: List<ZooSeat>)

    /** Moves to READY with the bag dealt. */
    fun start()

    /** The "Ready?" tap: the first turn starts (CHOOSING). */
    fun begin()

    /** The active seat picks a piece from [ZooState.offer]. Returns false when it is not on offer or not CHOOSING. */
    fun choose(pieceId: Int): Boolean

    /** The placement the assist would make for a drag at ([xQ], [angleQ]) for the active seat (for the halo preview). */
    fun assisted(xQ: Int, angleQ: Int): ZooPlacement

    /** Releases the chosen piece at a quantised placement; assist, lift-out and sweep-down happen inside. */
    fun release(xQ: Int, angleQ: Int): ZooReleaseResult

    /** Advances the physics by [steps] fixed steps (at most [ZooPhysics.MAX_STEPS_PER_FRAME] per frame from the wrapper). No-op while paused. */
    fun advance(steps: Int)

    fun pause()
    fun resume()

    /** Starts another game with the same seats; the next seed is derived from the previous one (SplitMix64), so it stays reproducible. */
    fun playAgain()

    fun state(): ZooState

    /** FNV-1a over the quantised pose of every body and the base, for desync detection and the golden test. */
    fun stateHash(): Long

    fun replay(): ZooReplay

    /** Null when there is nothing worth saving (not started, over, dragging). */
    fun toSave(): ZooSave?

    /** True when the engine is fully back in the saved state; false (and unchanged) when the save is unusable. */
    fun restore(save: ZooSave): Boolean
}

/** A do-nothing engine that returns safe values, so the package compiles and the wrapper can be tested before M0. */
class StubStackZooEngine : StackZooEngine {
    private var config = ZooConfig()
    private var seats: List<ZooSeat> = emptyList()

    override fun setup(config: ZooConfig, seats: List<ZooSeat>) {
        this.config = config
        this.seats = seats
    }

    override fun start() {}
    override fun begin() {}
    override fun choose(pieceId: Int): Boolean = false
    override fun assisted(xQ: Int, angleQ: Int): ZooPlacement = ZooPlacement(xQ, angleQ)
    override fun release(xQ: Int, angleQ: Int): ZooReleaseResult = ZooReleaseResult.NOT_POSITIONING
    override fun advance(steps: Int) {}
    override fun pause() {}
    override fun resume() {}
    override fun playAgain() {}
    override fun state(): ZooState = ZooState.EMPTY.copy(mode = config.mode)
    override fun stateHash(): Long = 0L
    override fun replay(): ZooReplay = ZooReplay(seed = config.seed, seats = seats.size)
    override fun toSave(): ZooSave? = null
    override fun restore(save: ZooSave): Boolean = false
}
