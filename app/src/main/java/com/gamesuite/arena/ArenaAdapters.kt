package com.gamesuite.arena

import com.gamesuite.core.ArenaHook
import com.gamesuite.core.GameModule
import com.gamesuite.games.bridgebuilders.BridgeBuildersGame
import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.reversi.ReversiGame
import com.gamesuite.games.slideclimb.SlideClimbGame

/**
 * The per-game glue of the Computer Arena. A game's own screen keeps playing (all seats are computers, so the
 * screen's normal bot loop moves them); an adapter only knows how to (1) hand the module the [ArenaHook] that
 * gives each seat its own CPU level and lets the arena pause, (2) tell when the board is over and who won, and
 * (3) deal the next board with a chosen opener, keeping the module's own session tally.
 */
interface ArenaGameAdapter {
    val spec: ArenaGameSpec
    fun accepts(module: GameModule): Boolean
    fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig)
    fun poll(module: GameModule): ArenaPoll

    /** Deals the next board; [firstSeat] opens it. */
    fun restart(module: GameModule, firstSeat: Int)

    /** Pauses or resumes; the default is the hook's held-move pause, which every bot-driven game honours. */
    fun setPaused(module: GameModule, hook: ArenaHook, paused: Boolean) = hook.setPaused(paused)
}

/** The seat a player id of an arena roster belongs to ("arena-0", "arena-1", ...). */
internal fun arenaSeatOf(playerId: String?): Int? = playerId?.removePrefix("arena-")?.toIntOrNull()

private fun outcomeOf(over: Boolean, winnerPlayerId: String?): ArenaPoll {
    if (!over) return ArenaPoll.Running
    val seat = arenaSeatOf(winnerPlayerId)
    return ArenaPoll.Over(if (seat != null) ArenaOutcome.Winner(seat) else ArenaOutcome.Draw)
}

internal object SlideClimbArena : ArenaGameAdapter {
    // Every seat holds the spare roll (handicap=all): the spare-roll decision is the only thing a level changes here.
    override val spec = ArenaGameSpec("chutes-race", "chutes-race", seats = 2, extras = mapOf("handicap" to "all"))
    override fun accepts(module: GameModule) = module is SlideClimbGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as SlideClimbGame).arena = hook }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as SlideClimbGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.over, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as SlideClimbGame).playAgainOpenedBy(firstSeat) }
}

internal object BridgeBuildersArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("bridge-builders", "bridge-builders", seats = 4, cooperative = true, extras = mapOf("band" to "medium"))
    override fun accepts(module: GameModule) = module is BridgeBuildersGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) {
        val game = module as BridgeBuildersGame
        game.arena = hook
        // Lanes 0 and 2 are Computer A's helper robots, lanes 1 and 3 Computer B's.
        for (seat in 0 until spec.seats) game.seatLevelOverride[seat] = config.levelOfSeat(seat).difficulty
    }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as BridgeBuildersGame).state.value ?: return ArenaPoll.Running
        return if (s.over) ArenaPoll.Over(ArenaOutcome.Team(s.won)) else ArenaPoll.Running
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as BridgeBuildersGame).playAgain() }
    override fun setPaused(module: GameModule, hook: ArenaHook, paused: Boolean) {
        hook.setPaused(paused)
        // The bridge runs on a clock, so its own engine pause is the real pause.
        if (paused) module.pause() else module.resume()
    }
}

internal object ChessArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("chess", "chess")
    override fun accepts(module: GameModule) = module is ChessGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as ChessGame).arena = hook }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as ChessGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.roundOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) {
        (module as ChessGame).whiteSeatIndex = firstSeat
        module.playAgain()
    }
}

internal object CheckersArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("checkers", "checkers")
    override fun accepts(module: GameModule) = module is CheckersGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as CheckersGame).arena = hook }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as CheckersGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.gameOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) {
        (module as CheckersGame).openingSeatIndex = firstSeat
        module.playAgain()
    }
}

internal object ReversiArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("reversi", "reversi")
    override fun accepts(module: GameModule) = module is ReversiGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as ReversiGame).arena = hook }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as ReversiGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    // Reversi alternates the opener by itself (seat 0, then 1, ...), which is exactly the arena's swap.
    override fun restart(module: GameModule, firstSeat: Int) { (module as ReversiGame).playAgain() }
}

internal object ConnectFourArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("connect-four", "connect-four")
    override fun accepts(module: GameModule) = module is ConnectFourGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as ConnectFourGame).arena = hook }
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as ConnectFourGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) {
        (module as ConnectFourGame).firstOpenerIndex = firstSeat
        module.playAgain()
    }
}

/** The registry of games the arena can run, in the order the setup screen lists them. */
object ArenaGames {
    internal val adapters: List<ArenaGameAdapter> = listOf(
        SlideClimbArena, BridgeBuildersArena, ChessArena, CheckersArena, ReversiArena, ConnectFourArena
    )

    val specs: List<ArenaGameSpec> get() = adapters.map { it.spec }
    val ids: Set<String> get() = adapters.map { it.spec.gameId }.toSet()
    internal fun adapterFor(gameId: String): ArenaGameAdapter? = adapters.firstOrNull { it.spec.gameId == gameId }
}
