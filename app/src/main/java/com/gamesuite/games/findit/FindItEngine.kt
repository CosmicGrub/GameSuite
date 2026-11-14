package com.gamesuite.games.findit

/**
 * The rules of Find It Frenzy with no Compose, no Android and no clock: time only moves through [advance], so tests
 * and replays are exact. The game wrapper ([FindItGame]) owns locking, Compose state and the shell lifecycle.
 *
 * Lifecycle: [setup] -> [start] (READY) -> [go] (PLAYING) -> taps and [advance] -> OVER.
 */
interface FindItEngine {
    /** Seats are people first, then helpers. Resets everything; [FindItConfig.seed] (never 0 from the wrapper) fixes every shuffle. */
    fun setup(config: FindItConfig, seats: List<FindItSeat>)

    /** Moves to READY with the cards dealt. */
    fun start()

    /** The "Ready?" tap or the 3-2-1 finished: moves READY to PLAYING and starts the clock. */
    fun go()

    /** Moves engine time forward by [dtMs] (bot taps, hints, lockouts and clocks run from here). No-op while paused. */
    fun advance(dtMs: Long)

    /**
     * Seat [seat] taps picture [symbol]. In Match Race and Dash the picture must be on that seat's own card; in Group
     * Hunt it is a scene item. [atMs] is engine time of the tap (ties resolve by timestamp, then rotated seat order).
     */
    fun tap(seat: Int, symbol: Int, atMs: Long): FindItTapResult

    fun pause()
    fun resume()

    /** Starts another game with the same seats; the next seed is derived from the previous one (SplitMix64), so it stays reproducible. */
    fun playAgain()

    fun state(): FindItState

    /** The replay log so far (seed, config ids, taps). */
    fun replay(): FindItReplay

    /** Null when there is nothing worth saving (not started, over). */
    fun toSave(): FindItSave?

    /** True when the engine is fully back in the saved state; false (and unchanged) when the save is unusable. */
    fun restore(save: FindItSave): Boolean
}

/** A do-nothing engine that returns safe values, so the package compiles and the wrapper can be tested before M0. */
class StubFindItEngine : FindItEngine {
    private var config = FindItConfig()
    private var seats: List<FindItSeat> = emptyList()

    override fun setup(config: FindItConfig, seats: List<FindItSeat>) {
        this.config = config
        this.seats = seats
    }

    override fun start() {}
    override fun go() {}
    override fun advance(dtMs: Long) {}
    override fun tap(seat: Int, symbol: Int, atMs: Long): FindItTapResult = FindItTapResult.NOT_PLAYING
    override fun pause() {}
    override fun resume() {}
    override fun playAgain() {}
    override fun state(): FindItState = FindItState.EMPTY.copy(mode = config.mode)
    override fun replay(): FindItReplay = FindItReplay(seed = config.seed, seats = seats.size)
    override fun toSave(): FindItSave? = null
    override fun restore(save: FindItSave): Boolean = false
}
