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
 * One running Computer Arena series (or tournament of series). Everything lives in memory (Compose state); nothing
 * is written to the player's stats, achievements, streaks or saved games (see [GameSessionManager.arenaActive]).
 * Only the finished-series summary goes to the arena's own results log ([ArenaHost.recorder]).
 */
class ArenaSession(
    /** What the player asked for (a tournament keeps its own current pairing in [config]). */
    val startConfig: ArenaConfig,
    internal val adapter: ArenaGameAdapter,
    speed: ArenaSpeed = ArenaSpeed.X1
) {
    /** The round-robin being played, or null for a single series. */
    val tournament: ArenaTournament? =
        if (startConfig.tournament && !adapter.spec.cooperative) ArenaTournament(startConfig.gameId, ArenaTournament.gamesPerFixtureFor(startConfig.games)) else null

    /** The series in progress (the current pairing of a tournament). */
    var config by mutableStateOf(tournament?.configFor(0) ?: startConfig)
        internal set

    internal val hook = ArenaHook((0 until 4).associateWith { config.levelOfSeat(it).difficulty })

    /** The games of the current series, in order. */
    var records by mutableStateOf<List<ArenaGameRecord>>(emptyList())
        internal set

    /** Finished pairings of a tournament. */
    var fixtureResults by mutableStateOf<List<ArenaFixtureResult>>(emptyList())
        internal set

    /** Games the watchdog ended, e.g. "Game 3: no change for 20 s". Real engine bugs show up here. */
    var stalls by mutableStateOf<List<String>>(emptyList())
        internal set

    val board: ArenaScoreboard get() = records.fold(ArenaScoreboard()) { b, r -> b.after(r.outcome) }

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

    /** 0-based pairing of a tournament in progress. */
    val fixtureIndex: Int get() = fixtureResults.size

    /** The numbers of the current (or just finished) series. */
    val summary: ArenaSeriesSummary get() = ArenaSeriesSummary(config, records, cooperative)
}

/** Starts, drives and stops the one arena series (a process-wide singleton, like the session manager it drives). */
object ArenaHost {
    var session by mutableStateOf<ArenaSession?>(null)
        private set

    /** The poll interval of the series loop; every duration and the watchdog count in these steps. */
    internal const val POLL_MS = 100L

    /** How long a board may show no change before the watchdog ends it as stalled (tests lower it). */
    @Volatile
    var stallLimitMs: Long = ArenaWatchdog.DEFAULT_LIMIT_MS

    /** Receives each finished series for the persistent log (set by the UI layer; null in tests). */
    @Volatile
    var recorder: ((ArenaSeriesRecord) -> Unit)? = null

    internal var nowMs: () -> Long = { System.currentTimeMillis() }

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
                cpuDifficulty = s.config.levelA.difficulty,
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

    private fun logSeries(summary: ArenaSeriesSummary, tournament: Boolean) {
        recorder?.invoke(ArenaResultsLog.recordOf(summary, tournament, nowMs()))
    }

    /**
     * Books a finished (or stalled) game into the series and moves the series on. Returns true when the whole
     * series or tournament is over (the phase is then DONE).
     */
    internal fun finishGame(s: ArenaSession, outcome: ArenaOutcome, playedMs: Long): Boolean {
        s.lastOutcome = outcome
        s.records = s.records + ArenaGameRecord(outcome, playedMs)
        if (!ArenaSeries.isComplete(s.config, s.board.played)) return false
        val series = s.summary
        val tournament = s.tournament
        if (tournament == null) {
            logSeries(series, tournament = false)
            s.phase = ArenaPhase.DONE
            return true
        }
        logSeries(series, tournament = true)
        val index = s.fixtureResults.size
        s.fixtureResults = s.fixtureResults + ArenaFixtureResult(tournament.fixtures[index], s.records)
        if (tournament.isComplete(s.fixtureResults.size)) {
            s.phase = ArenaPhase.DONE
            return true
        }
        return false
    }

    /** Moves a tournament on to its next pairing: new levels for the hook and the game, a fresh tally. */
    internal fun nextFixture(s: ArenaSession, module: com.gamesuite.core.GameModule) {
        val tournament = s.tournament ?: return
        s.config = tournament.configFor(s.fixtureResults.size)
        s.records = emptyList()
        s.hook.setLevels((0 until 4).associateWith { s.config.levelOfSeat(it).difficulty })
        s.adapter.attach(module, s.hook, s.config)
    }

    /**
     * The series loop, run while the overlay is composed: attaches the hook to the game module once its screen
     * has registered it, watches for the end of each board (or for a stall), tallies it, shows the result for a
     * moment (scaled by the arena speed, held while paused) and deals the next board with the opener swapped.
     */
    suspend fun drive(s: ArenaSession, sessionManager: GameSessionManager) {
        var attached: Any? = null
        var playedMs = 0L
        val watchdog = ArenaWatchdog(stallLimitMs)
        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            if (s.done || session !== s) return
            val module = sessionManager.currentModule ?: continue
            if (!s.adapter.accepts(module)) continue
            if (module !== attached) {
                s.adapter.attach(module, s.hook, s.config)
                attached = module
                playedMs = 0L
                watchdog.reset()
                if (s.phase == ArenaPhase.STARTING) s.phase = ArenaPhase.PLAYING
            }
            if (s.paused) continue
            if (s.phase != ArenaPhase.PLAYING) continue
            s.adapter.tick(module)
            playedMs += POLL_MS

            val poll = s.adapter.poll(module)
            var outcome = (poll as? ArenaPoll.Over)?.outcome
            if (outcome == null && watchdog.tick(s.adapter.fingerprint(module), POLL_MS)) {
                outcome = ArenaOutcome.Stalled
                s.stalls = s.stalls + "Game ${s.gameNumber}: no change for ${watchdog.stillMs / 1000} s (${s.config.gameId})"
            }
            if (outcome == null) continue
            watchdog.reset()

            val gameMs = playedMs
            playedMs = 0L
            if (finishGame(s, outcome, gameMs)) return
            val pairingDone = s.tournament != null && ArenaSeries.isComplete(s.config, s.board.played)
            s.firstSeat = ArenaSeries.firstSeat(if (pairingDone) 0 else s.board.played)
            s.phase = ArenaPhase.BETWEEN
            var waited = 0L
            while (waited < s.speed.betweenGamesMs || s.paused) {
                delay(POLL_MS)
                if (session !== s) return
                if (!s.paused) waited += POLL_MS
            }
            if (sessionManager.currentModule !== module) continue
            if (pairingDone) nextFixture(s, module)
            s.adapter.restart(module, s.firstSeat)
            s.phase = ArenaPhase.PLAYING
        }
    }
}
