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

    /**
     * The GameModule instance backing the currently displayed game screen,
     * if any. The shell only ever tracks GameContext (players/transport/
     * mode) elsewhere — this is what lets [pauseActiveGame]/[resumeActiveGame]
     * actually have something to call.
     */
    private var activeModule: GameModule? = null

    /**
     * Held here (a ViewModel, so it survives navigation) between "user picked Host/Join"
     * and either [launchGame] (which captures it inside GameContext) or the user backing
     * out of the lobby without starting. The Nearby lobby screens are the only readers/
     * writers — nothing else in the shell should touch this.
     */
    var pendingNearbyTransport: NearbyConnectionsTransport? = null

    /** Same role as [pendingNearbyTransport], for the Online lobby screens (roadmap item 12). */
    var pendingOnlineTransport: OnlineTransport? = null

    /** Called by the shell (MainActivity) when a game screen's GameModule becomes active; pass null when leaving it. */
    fun setActiveModule(module: GameModule?) {
        activeModule = module
    }

    /** Forward from the host Activity's onPause so a running game can stop per-frame work, timers, or bot-turn delays while backgrounded. */
    fun pauseActiveGame() {
        activeModule?.pause()
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
    fun launchGame(mode: PlayMode, players: List<PlayerInfo>, localPlayerIndex: Int) {
        val transport = createTransport(mode)
        transport.connect()
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
        _activeContext.value = GameContext(
            activeMode = mode,
            players = players,
            localPlayerIndex = localPlayerIndex,
            transport = transport
        )
    }

    /** Called by the active game's screen when it's finished, or on a "quit" action. */
    fun endActiveGame(result: GameResult) {
        _activeContext.value?.transport?.disconnect()
        _lastResult.value = result
        _activeContext.value = null
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
