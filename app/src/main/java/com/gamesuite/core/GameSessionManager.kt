package com.gamesuite.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport
import com.gamesuite.transport.NearbyConnectionsTransport
import com.gamesuite.transport.OnlineTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shell-side state holder that tracks the active game + its GameContext, so
 * Compose navigation can show the right screen and any GameModule can be
 * launched/ended without the shell knowing game-specific rules.
 *
 * Register with Hilt/manual DI as a single shared instance (e.g. scoped to
 * the NavHost's parent, or a plain singleton while the app is small).
 */
class GameSessionManager : ViewModel() {

    private val _activeContext = MutableStateFlow<GameContext?>(null)
    val activeContext: StateFlow<GameContext?> = _activeContext.asStateFlow()

    private val _lastResult = MutableStateFlow<GameResult?>(null)
    val lastResult: StateFlow<GameResult?> = _lastResult.asStateFlow()

    /** Emitted once per finished match, alongside [lastResult] — a stats/history layer
     *  observes this instead of re-deriving "which player is the local one" itself. See
     *  [MatchOutcome]'s KDoc for why this lives on the shell rather than in each game. */
    private val _lastMatchOutcome = MutableStateFlow<MatchOutcome?>(null)
    val lastMatchOutcome: StateFlow<MatchOutcome?> = _lastMatchOutcome.asStateFlow()

    /**
     * The GameModule instance backing the currently displayed game screen,
     * if any. The shell only ever tracks GameContext (players/transport/
     * mode) elsewhere — this is what lets [pauseActiveGame]/[resumeActiveGame]
     * actually have something to call.
     */
    private var activeModule: GameModule? = null

    /** The nav route [activeModule] was launched on; the key its saved game lives under. */
    private var activeRoute: String? = null

    /**
     * Where resumable games are written down. Attached by MainActivity (the only place with a
     * Context); null in unit tests and before attach, which simply turns saving off.
     */
    var savedGames: SavedGameStore? = null

    /**
     * Set by the shell when the player picks "Resume" for a route, consumed exactly once by the
     * launched game screen through [tryResume]. A [ViewModel] field, so it survives the
     * navigation between the menu and the game.
     */
    var pendingResume: SavedGame? = null

    /**
     * Held here (a ViewModel, so it survives navigation) between "user picked Host/Join"
     * and either [launchGame] (which captures it inside GameContext) or the user backing
     * out of the lobby without starting. The Nearby lobby screens are the only readers/
     * writers — nothing else in the shell should touch this.
     */
    var pendingNearbyTransport: NearbyConnectionsTransport? = null

    /** Same role as [pendingNearbyTransport], for the Online lobby screens (roadmap item 12). */
    var pendingOnlineTransport: OnlineTransport? = null

    /**
     * Called by the shell (MainActivity) when a game screen's GameModule becomes active; pass null
     * when leaving it. [route] is the nav route it was launched on and keys its saved game.
     */
    fun setActiveModule(module: GameModule?, route: String? = null) {
        activeModule = module
        activeRoute = if (module != null) route else null
    }

    /**
     * Forward from the host Activity's onPause so a running game can stop per-frame work, timers,
     * or bot-turn delays while backgrounded. Also writes the game down: backgrounding is the last
     * moment the app is guaranteed to run before Android may kill it, and a resumable game must
     * survive that.
     */
    fun pauseActiveGame() {
        activeModule?.pause()
        saveActiveProgress()
    }

    /**
     * The key a game's save lives under: the nav route PLUS the launch configuration (mode and
     * roster). Several menu entries share one route (CPU vs pass-and-play, different CPU rosters),
     * and a save from one must never be offered, or deleted, for another.
     */
    fun saveKey(route: String, ctx: GameContext): String =
        route + "@" + ctx.activeMode.name + "@" +
            ctx.players.joinToString(",") { it.playerId + if (it.isBot) "!" else "" }

    /** The save for [route] under the launch configuration the menu just installed, if any. */
    fun savedGameForLaunch(route: String): SavedGame? {
        val ctx = _activeContext.value ?: return null
        return savedGames?.load(saveKey(route, ctx))
    }

    private fun currentSnapshot(): String? {
        val resumable = activeModule as? ResumableGame ?: return null
        return try {
            resumable.snapshot()
        } catch (_: Exception) {
            null
        }
    }

    /** True when the active game can be resumed right now: resumable, a store to keep it in, and a snapshot to write. */
    fun canSaveActiveProgress(): Boolean =
        activeRoute != null && savedGames != null && _activeContext.value != null && currentSnapshot() != null

    /**
     * Writes the active game's snapshot to the store. Returns true ONLY when it really reached
     * disk (no snapshot, no store or a failed write all return false), so callers never claim a
     * save that did not happen. A null snapshot ("nothing worth saving") deliberately does NOT
     * delete an existing save: a pause that lands before the screen has had its [tryResume] must
     * not wipe the save the player is about to resume.
     */
    fun saveActiveProgress(): Boolean {
        val store = savedGames ?: return false
        val route = activeRoute ?: return false
        val ctx = _activeContext.value ?: return false
        val module = activeModule ?: return false
        val snapshot = currentSnapshot() ?: return false
        return store.save(
            SavedGame(
                route = saveKey(route, ctx),
                label = module.displayName,
                savedAtMillis = System.currentTimeMillis(),
                savedAtEpochDay = java.time.LocalDate.now().toEpochDay(),
                snapshot = snapshot,
                navRoute = route,
                launch = SavedLaunch(
                    mode = ctx.activeMode.name,
                    seats = ctx.players.map { SavedSeat(it.playerId, it.displayName, it.isBot, it.teamId) },
                    localPlayerIndex = ctx.localPlayerIndex,
                    cpuDifficulty = launchOptions.cpuDifficulty?.name,
                    firstMoverIndex = launchOptions.firstMoverIndex,
                    extras = launchOptions.extras
                ),
                summary = ctx.players.joinToString(" vs ") { it.displayName }
            )
        )
    }

    /**
     * Resumes [saved] by re-creating the launch it was saved under, then arming [pendingResume] so
     * the game screen's [tryResume] restores the snapshot. The caller navigates to
     * [SavedGame.navRoute] afterwards. Returns false for a save with no recorded launch (written
     * before the Hub): the caller should offer a fresh game instead.
     */
    fun resumeSavedGame(saved: SavedGame): Boolean {
        val launch = saved.launch ?: return false
        val mode = runCatching { PlayMode.valueOf(launch.mode) }.getOrNull() ?: return false
        if (launch.seats.isEmpty()) return false
        launchGame(
            mode = mode,
            players = launch.seats.map { PlayerInfo(it.id, it.name, it.isBot, it.teamId) },
            localPlayerIndex = launch.localPlayerIndex.coerceIn(0, launch.seats.lastIndex),
            options = LaunchOptions(
                cpuDifficulty = launch.cpuDifficulty?.let { n -> com.gamesuite.settings.CpuDifficulty.values().firstOrNull { it.name == n } },
                firstMoverIndex = launch.firstMoverIndex,
                extras = launch.extras
            )
        )
        pendingResume = saved
        return true
    }

    /**
     * Called by a game screen at start-up in place of an unconditional `startMatch()`:
     * `if (!sessionManager.tryResume(game)) game.startMatch()`. Uses [pendingResume] (set when the
     * player chose Resume) exactly once, after the engine's `init`. When the engine rejects the
     * snapshot the stale save is deleted so it cannot be offered again, and the caller falls back
     * to a fresh match.
     */
    fun tryResume(module: GameModule): Boolean {
        val saved = pendingResume ?: return false
        pendingResume = null
        val resumable = module as? ResumableGame ?: return false
        val restored = try {
            resumable.restore(saved.snapshot)
        } catch (_: Exception) {
            false
        }
        if (!restored) savedGames?.delete(saved.route)
        return restored
    }

    /** Forward from the host Activity's onResume. */
    fun resumeActiveGame() {
        activeModule?.resume()
    }

    /**
     * Called from the game-library UI when a player picks a game + mode.
     * The caller (a NavHost composable) is responsible for actually
     * navigating to that game's screen once this context is set.
     */
    fun launchGame(
        mode: PlayMode,
        players: List<PlayerInfo>,
        localPlayerIndex: Int,
        options: LaunchOptions = LaunchOptions()
    ) {
        val transport = createTransport(mode)
        transport.connect()
        launchOptions = options
        _activeContext.value = GameContext(
            activeMode = mode,
            players = players,
            localPlayerIndex = localPlayerIndex,
            transport = transport
        )
    }

    /**
     * For a transport that's already connected before the game starts — Nearby
     * Connections in particular, where the lobby UI has already run advertising/
     * discovery/connection to completion and knows the final [players] roster before
     * this is ever called. [transport] is not reconnected here (already live); use the
     * zero-transport overload above for every mode where the transport can just be
     * created fresh at launch time.
     */
    fun launchGame(mode: PlayMode, players: List<PlayerInfo>, localPlayerIndex: Int, transport: MultiplayerTransport) {
        launchOptions = LaunchOptions()
        _activeContext.value = GameContext(
            activeMode = mode,
            players = players,
            localPlayerIndex = localPlayerIndex,
            transport = transport
        )
    }

    /**
     * Per-launch choices from the Match Setup dialog (CPU difficulty override, who moves first).
     * Reset to the defaults by every [launchGame], so one game's setup never leaks into the next.
     * A game screen reads it once when it starts.
     */
    var launchOptions: LaunchOptions = LaunchOptions()
        private set

    /** The CPU difficulty for this launch: the Match Setup choice if there is one, else [default] (the app setting). */
    fun cpuDifficultyFor(default: com.gamesuite.settings.CpuDifficulty): com.gamesuite.settings.CpuDifficulty =
        launchOptions.cpuDifficulty ?: default

    /** Called by the active game's screen when it's finished, or on a "quit" action. */
    fun endActiveGame(result: GameResult) {
        val ctx = _activeContext.value
        val module = activeModule
        if (ctx != null && module != null && !result.wasAborted) {
            _lastMatchOutcome.value = MatchOutcome(
                gameId = module.gameId,
                gameDisplayName = module.displayName,
                result = result,
                localOutcome = localOutcomeFor(ctx, result)
            )
        }
        // A finished match has nothing left to resume. An ABORT keeps whatever save exists: the
        // game chrome writes the save just before it aborts, so "Leave" parks the game for later.
        if (!result.wasAborted) {
            val route = activeRoute
            if (route != null && ctx != null) savedGames?.delete(saveKey(route, ctx))
        }
        ctx?.transport?.disconnect()
        _lastResult.value = result
        _activeContext.value = null
    }

    /**
     * Consumes [lastMatchOutcome] once the stats layer has recorded it — call this right
     * after (see MainActivity's root LaunchedEffect). Without this, the outcome sits in the
     * StateFlow forever, which causes two real bugs: (1) an Activity recreation with no new
     * match (dark mode switching at sunset, a font-size change, ...) replays the same
     * LaunchedEffect(lastMatchOutcome) on the fresh composition and records the same match a
     * second time, since a brand-new composition has no memory of the LaunchedEffect that
     * already ran in the destroyed one; (2) MutableStateFlow only emits on a value CHANGE, so
     * two matches in a row that happen to produce an equal MatchOutcome (same game, same
     * scores) collapse into a single emission and the second one is silently never recorded.
     * Clearing back to null after every consumption fixes both: a recreation replays with a
     * null key (no-op), and the next real match always transitions null -> outcome, which is
     * always a change.
     */
    fun acknowledgeMatchOutcome() {
        _lastMatchOutcome.value = null
    }

    /** Resolves [result] to this device's own win/loss/draw — null for a spectator
     *  (localPlayerIndex == -1) or a result with nothing to score. */
    private fun localOutcomeFor(ctx: GameContext, result: GameResult): LocalOutcome? {
        val localPlayer = ctx.players.getOrNull(ctx.localPlayerIndex) ?: return null
        if (result.scores.isEmpty()) return null
        val myScore = result.scores.firstOrNull { it.playerId == localPlayer.playerId } ?: return null
        val anyoneWon = result.scores.any { it.isWinner }
        return when {
            myScore.isWinner -> LocalOutcome.WIN
            !anyoneWon -> LocalOutcome.DRAW
            else -> LocalOutcome.LOSS
        }
    }

    private fun createTransport(mode: PlayMode): MultiplayerTransport {
        return when (mode) {
            PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
            PlayMode.SINGLE_PLAYER_VS_BOT -> LocalPassAndPlayTransport()

            // PlayMode.ONLINE never reaches this branch in practice: like LOCAL_AD_HOC
            // (Nearby), matchmaking/room-join must finish *before* a GameContext can
            // exist, so the Online lobby screens always call the transport-overload
            // launchGame() below directly with an already-connected OnlineTransport,
            // the same way NearbyHostLobbyScreen/NearbyJoinLobbyScreen do. This branch
            // is unreachable dead code for ONLINE, not a real fallback — kept explicit
            // (rather than folded into the `else`) so it reads as a deliberate non-path,
            // not an oversight.
            PlayMode.ONLINE -> LocalPassAndPlayTransport()

            // DualScreen transport gets added here as it's built — every game already
            // works with it automatically once added, no game code changes.
            else -> LocalPassAndPlayTransport()
        }
    }
}
