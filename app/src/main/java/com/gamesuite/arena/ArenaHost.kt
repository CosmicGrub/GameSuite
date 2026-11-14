package com.gamesuite.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gamesuite.core.ArenaHook
import com.gamesuite.core.GameResult
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.LaunchOptions
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class ArenaPhase { STARTING, PLAYING, BETWEEN, DONE }

/**
 * One running Computer Arena series. Everything lives in memory (Compose state); nothing is written to the
 * player's stats, achievements, streaks or saved games (see [GameSessionManager.arenaActive]).
 */
class ArenaSession(val config: ArenaConfig, internal val adapter: ArenaGameAdapter, speed: ArenaSpeed = ArenaSpeed.X1) {
    internal val hook = ArenaHook((0 until 4).associateWith { config.levelOfSeat(it).difficulty })

    var board by mutableStateOf(ArenaScoreboard())
        internal set
    var phase by mutableStateOf(ArenaPhase.STARTING)
        internal set
    var paused by mutableStateOf(false)
        internal set
    var speed by mutableStateOf(speed)
    var lastOutcome by mutableStateOf<ArenaOutcome?>(null)
        internal set

    /** The seat that moved (or will move) first in the game on the table, 0 = Computer A. */
    var firstSeat by mutableStateOf(0)
        internal set

    val cooperative: Boolean get() = adapter.spec.cooperative
    val gameNumber: Int get() = ArenaSeries.currentGameNumber(config, board.played)
    val done: Boolean get() = phase == ArenaPhase.DONE
}

/** Starts, drives and stops the one arena series (a process-wide singleton, like the session manager it drives). */
object ArenaHost {
    var session by mutableStateOf<ArenaSession?>(null)
        private set

    /** The shell roster of an arena game: all computers, Computer A on the even seats and B on the odd ones. */
    fun roster(spec: ArenaGameSpec): List<PlayerInfo> = List(spec.seats) { seat ->
        val side = if (ArenaSeries.sideOf(seat) == 0) "A" else "B"
        val n = seat / 2 + 1
        PlayerInfo("arena-$seat", "Computer $side" + if (spec.seats > 2) "$n" else "", isBot = true)
    }

    /**
     * Opens [config]'s game with every seat a computer and begins the series. [navigate] opens the game's route.
     * Returns false for a game the arena cannot run. Any series already running is stopped first.
     */
    fun start(
        sessionManager: GameSessionManager,
        config: ArenaConfig,
        speed: ArenaSpeed = ArenaSpeed.X1,
        navigate: (route: String) -> Unit
    ): Boolean {
        val adapter = ArenaGames.adapterFor(config.gameId) ?: return false
        if (session != null) {
            stop(sessionManager)
            sessionManager.endActiveGame(GameResult(wasAborted = true))
        }
        val s = ArenaSession(config, adapter, speed)
        sessionManager.arenaActive = true
        session = s
        sessionManager.launchGame(
            mode = PlayMode.SINGLE_PLAYER_VS_BOT,
            players = roster(adapter.spec),
            localPlayerIndex = 0,
            options = LaunchOptions(
                cpuDifficulty = config.levelA.difficulty,
                firstMoverIndex = ArenaSeries.firstSeat(0),
                extras = adapter.spec.extras
            )
        )
        navigate(adapter.spec.route)
        return true
    }

    /** Ends the series (the screen is left alone; the caller navigates away). Safe to call twice. */
    fun stop(sessionManager: GameSessionManager) {
        val s = session
        if (s != null) {
            s.hook.clearHeld()
            sessionManager.currentModule?.let { m -> if (s.adapter.accepts(m)) s.adapter.setPaused(m, s.hook, false) }
            s.hook.setPaused(false)
        }
        session = null
        sessionManager.arenaActive = false
    }

    fun togglePause(sessionManager: GameSessionManager) {
        val s = session ?: return
        if (s.done) return
        val next = !s.paused
        s.paused = next
        sessionManager.currentModule?.let { m -> if (s.adapter.accepts(m)) s.adapter.setPaused(m, s.hook, next) }
            ?: s.hook.setPaused(next)
    }

    /**
     * The series loop, run while the overlay is composed: attaches the hook to the game module once its screen
     * has registered it, watches for the end of each board, tallies it, shows the result for a moment (scaled by
     * the arena speed, held while paused) and deals the next board with the opener swapped.
     */
    suspend fun drive(s: ArenaSession, sessionManager: GameSessionManager) {
        var attached: Any? = null
        while (currentCoroutineContext().isActive) {
            delay(100)
            if (s.done || session !== s) return
            val module = sessionManager.currentModule ?: continue
            if (!s.adapter.accepts(module)) continue
            if (module !== attached) {
                s.adapter.attach(module, s.hook, s.config)
                attached = module
                if (s.phase == ArenaPhase.STARTING) s.phase = ArenaPhase.PLAYING
            }
            if (s.paused) continue
            val poll = s.adapter.poll(module)
            if (poll !is ArenaPoll.Over || s.phase != ArenaPhase.PLAYING) continue

            s.lastOutcome = poll.outcome
            s.board = s.board.after(poll.outcome)
            if (ArenaSeries.isComplete(s.config, s.board.played)) {
                s.phase = ArenaPhase.DONE
                return
            }
            s.firstSeat = ArenaSeries.firstSeat(s.board.played)
            s.phase = ArenaPhase.BETWEEN
            var waited = 0L
            while (waited < s.speed.betweenGamesMs || s.paused) {
                delay(100)
                if (session !== s) return
                if (!s.paused) waited += 100
            }
            if (sessionManager.currentModule !== module) continue
            s.adapter.restart(module, s.firstSeat)
            s.phase = ArenaPhase.PLAYING
        }
    }
}
