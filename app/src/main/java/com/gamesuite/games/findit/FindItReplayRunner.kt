package com.gamesuite.games.findit

import com.gamesuite.settings.CpuDifficulty

/** Feeds a [FindItReplay] to a fresh engine: the same seed, config and people's taps reproduce a game exactly. */
object FindItReplayRunner {
    /** The config a replay was recorded with. Unknown ids fall back to defaults, never throw. */
    fun configOf(r: FindItReplay): FindItConfig = FindItConfig(
        mode = FindItMode.fromId(r.mode), cardSize = FindItCardSize.fromId(r.cardSize), length = FindItLength.fromId(r.length),
        relaxed = r.relaxed, handicap = r.handicap, huntHint = r.huntHint, oneSwitch = r.oneSwitch,
        difficulty = runCatching { CpuDifficulty.valueOf(r.difficulty) }.getOrDefault(CpuDifficulty.MEDIUM),
        seed = r.seed, timeScale = r.timeScale
    )

    fun seatsOf(r: FindItReplay): List<FindItSeat> = List(r.seats.coerceIn(1, 4)) { i ->
        val bot = r.botSeats.getOrNull(i) == true
        FindItSeat(if (bot) "bot${i + 1}" else "p${i + 1}", if (bot) "CPU ${i + 1}" else "Player ${i + 1}", bot)
    }

    /**
     * Plays [replay] on [engine] up to engine time [untilMs] (or until the game is over) and returns the final state.
     * The engine is set up, started and sent GO first.
     */
    fun play(replay: FindItReplay, untilMs: Long, engine: FindItEngine = DefaultFindItEngine()): FindItState {
        engine.setup(configOf(replay), seatsOf(replay))
        engine.start()
        engine.go()
        var now = 0L
        for (t in replay.taps) {
            if (engine.state().phase == FindItPhase.OVER) break
            if (t.atMs > now) { engine.advance(t.atMs - now); now = t.atMs }
            engine.tap(t.seat, t.symbol, if (t.stampMs >= 0) t.stampMs else t.atMs)
        }
        if (now < untilMs && engine.state().phase != FindItPhase.OVER) engine.advance(untilMs - now)
        return engine.state()
    }
}
