package com.gamesuite.arena

import com.gamesuite.core.ArenaHook
import com.gamesuite.core.GameModule
import com.gamesuite.games.bridgebuilders.BridgeBuildersGame
import com.gamesuite.games.checkers.CheckersGame
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.reversi.ReversiGame
import com.gamesuite.games.slideclimb.SlideClimbGame
import com.gamesuite.games.backgammon.BackgammonGame
import com.gamesuite.games.backgammon.BackgammonPhase
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.gomoku.GomokuGame
import com.gamesuite.games.hex.HexGame
import com.gamesuite.games.ludo.LudoGame
import com.gamesuite.games.mancala.MancalaGame
import com.gamesuite.games.morris.NineMensMorrisGame
import com.gamesuite.games.shogi.ShogiGame
import com.gamesuite.games.shogi.ShogiVariant
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.games.yahtzee.YahtzeeGame

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

    /**
     * A hash of everything that changes when the game makes progress (the board, whose turn, the dice...). The
     * arena watchdog ends a game whose fingerprint stays the same for too long.
     */
    fun fingerprint(module: GameModule): Int

    /**
     * Called every poll while a board is being played and the arena is running: for a step no computer takes on
     * its own (the Backgammon opening roll is a human tap). The default does nothing.
     */
    fun tick(module: GameModule) {}

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
    override fun fingerprint(module: GameModule): Int = (module as SlideClimbGame).state.value.hashCode()
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
    override fun fingerprint(module: GameModule): Int = (module as BridgeBuildersGame).state.value.hashCode()
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
    override fun fingerprint(module: GameModule): Int = (module as ChessGame).state.value.hashCode()
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
    override fun fingerprint(module: GameModule): Int = (module as CheckersGame).state.value.hashCode()
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
    override fun fingerprint(module: GameModule): Int = (module as ReversiGame).state.value.hashCode()
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
    override fun fingerprint(module: GameModule): Int = (module as ConnectFourGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as ConnectFourGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) {
        (module as ConnectFourGame).firstOpenerIndex = firstSeat
        module.playAgain()
    }
}


internal object TicTacToeArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("tic-tac-toe", "tic-tac-toe")
    override fun accepts(module: GameModule) = module is TicTacToeGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as TicTacToeGame).arena = hook }
    override fun fingerprint(module: GameModule): Int {
        val g = module as TicTacToeGame
        return java.util.Objects.hash(g.board.value.contentHashCode(), g.currentPlayer.value, g.roundOver.value, g.roundNumber.value)
    }
    override fun poll(module: GameModule): ArenaPoll {
        val g = module as TicTacToeGame
        if (!g.roundOver.value) return ArenaPoll.Running
        val line = g.winningLine.value ?: return ArenaPoll.Over(ArenaOutcome.Draw)
        // Standard rules: the mark on the winning line (1 = X = seat 0, 2 = O = seat 1) won.
        val mark = g.board.value.getOrNull(line.firstOrNull() ?: -1) ?: 0
        return ArenaPoll.Over(if (mark in 1..2) ArenaOutcome.Winner(mark - 1) else ArenaOutcome.Draw)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as TicTacToeGame).playAgainOpenedBy(firstSeat) }
}

internal object MancalaArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("mancala", "mancala")
    override fun accepts(module: GameModule) = module is MancalaGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as MancalaGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as MancalaGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as MancalaGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.roundOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as MancalaGame).playAgainOpenedBy(firstSeat) }
}

internal object GomokuArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("gomoku", "gomoku")
    override fun accepts(module: GameModule) = module is GomokuGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as GomokuGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as GomokuGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as GomokuGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as GomokuGame).playAgainOpenedBy(firstSeat) }
}

internal object DotsAndBoxesArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("dots-and-boxes", "dots-and-boxes")
    override fun accepts(module: GameModule) = module is DotsAndBoxesGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as DotsAndBoxesGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as DotsAndBoxesGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as DotsAndBoxesGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as DotsAndBoxesGame).playAgainOpenedBy(firstSeat) }
}

internal object HexArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("hex", "hex")
    override fun accepts(module: GameModule) = module is HexGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as HexGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as HexGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as HexGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as HexGame).playAgainOpenedBy(firstSeat) }
}

internal object MorrisArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("nine-mens-morris", "nine-mens-morris")
    override fun accepts(module: GameModule) = module is NineMensMorrisGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as NineMensMorrisGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as NineMensMorrisGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as NineMensMorrisGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as NineMensMorrisGame).playAgainOpenedBy(firstSeat) }
}

internal object DominoesArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("dominoes", "dominoes")
    override fun accepts(module: GameModule) = module is DominoGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as DominoGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as DominoGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as DominoGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.handOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as DominoGame).playAgainOpenedBy(firstSeat) }
}

internal class ShogiArena(private val variant: ShogiVariant) : ArenaGameAdapter {
    override val spec = ArenaGameSpec(variant.gameId, variant.gameId)
    override fun accepts(module: GameModule) = module is ShogiGame && module.variant == variant
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as ShogiGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as ShogiGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as ShogiGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.gameOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as ShogiGame).playAgainOpenedBy(firstSeat) }
}

/** Four seats like Bridge Builders: Computer A holds seats 1 and 3, Computer B seats 2 and 4 (0-based 0,2 and 1,3). */
internal object LudoArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("ludo", "ludo", seats = 4)
    override fun accepts(module: GameModule) = module is LudoGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as LudoGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as LudoGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as LudoGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.boardOver, s.winnerPlayerId)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as LudoGame).playAgainOpenedBy(firstSeat) }
}

internal object BackgammonArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("backgammon", "backgammon")
    override fun accepts(module: GameModule) = module is BackgammonGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as BackgammonGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as BackgammonGame).state.value.hashCode()
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as BackgammonGame).state.value ?: return ArenaPoll.Running
        return outcomeOf(s.phase == BackgammonPhase.OVER, s.winnerPlayerId)
    }
    // The opening roll is a human tap in the game screen; the arena makes it (a tie rolls again at the next poll).
    override fun tick(module: GameModule) {
        val g = module as BackgammonGame
        if (g.state.value?.phase == BackgammonPhase.OPENING) g.rollDice()
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as BackgammonGame).playAgainOpenedBy(firstSeat) }
}

/** A score race: both computers fill their own scorecard; the higher total wins, equal totals are a draw. */
internal object YahtzeeArena : ArenaGameAdapter {
    override val spec = ArenaGameSpec("yahtzee", "yahtzee")
    override fun accepts(module: GameModule) = module is YahtzeeGame
    override fun attach(module: GameModule, hook: ArenaHook, config: ArenaConfig) { (module as YahtzeeGame).arena = hook }
    override fun fingerprint(module: GameModule): Int = (module as YahtzeeGame).state.value?.version ?: 0
    override fun poll(module: GameModule): ArenaPoll {
        val s = (module as YahtzeeGame).state.value ?: return ArenaPoll.Running
        if (!s.gameOver) return ArenaPoll.Running
        val totals = s.players.map { it.card.total }
        val top = totals.max()
        val leaders = totals.indices.filter { totals[it] == top }
        return ArenaPoll.Over(if (leaders.size == 1) ArenaOutcome.Winner(leaders[0]) else ArenaOutcome.Draw)
    }
    override fun restart(module: GameModule, firstSeat: Int) { (module as YahtzeeGame).playAgainOpenedBy(firstSeat) }
}

/** The registry of games the arena can run, in the order the setup screen lists them. */
object ArenaGames {
    internal val adapters: List<ArenaGameAdapter> = listOf(
        SlideClimbArena, BridgeBuildersArena, ChessArena, CheckersArena, ReversiArena, ConnectFourArena,
        TicTacToeArena, MancalaArena, GomokuArena, DotsAndBoxesArena, HexArena, MorrisArena,
        ShogiArena(ShogiVariant.STANDARD), ShogiArena(ShogiVariant.MINI), DominoesArena, LudoArena, BackgammonArena, YahtzeeArena
    )

    val specs: List<ArenaGameSpec> get() = adapters.map { it.spec }
    val ids: Set<String> get() = adapters.map { it.spec.gameId }.toSet()
    internal fun adapterFor(gameId: String): ArenaGameAdapter? = adapters.firstOrNull { it.spec.gameId == gameId }
}
