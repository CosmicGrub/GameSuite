package com.gamesuite.core

import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.Serializable

/**
 * Contract every game in the suite implements to plug into the shell.
 * The shell (menu, lobby, multiplayer transport) never knows game-specific
 * rules — it only calls these lifecycle methods and reads this metadata.
 */
interface GameModule {
    /** Stable unique id, e.g. "tic-tac-toe". Used for save data, analytics, matchmaking. */
    val gameId: String

    /** Display name shown in the game library. */
    val displayName: String

    val category: GameCategory
    val minPlayers: Int
    val maxPlayers: Int

    /** Which play modes this game currently supports. */
    val supportedModes: List<PlayMode>

    /** Called once when the shell navigates to this game. Do setup here. */
    fun init(context: GameContext)

    /** Called when all players are ready and the match should begin. */
    fun startMatch()

    fun pause()
    fun resume()

    /** Called by the game itself when the match concludes, or by the shell to force-quit. */
    fun endMatch(result: GameResult)
}

enum class GameCategory { BOARD, CARD, ARCADE, PUZZLE, WORD, OTHER }

enum class PlayMode {
    SINGLE_DEVICE_PASS_AND_PLAY,
    DUAL_SCREEN,
    LOCAL_AD_HOC,
    ONLINE,
    SINGLE_PLAYER_VS_BOT
}

/**
 * Everything a game needs from the shell, handed to it at init time. A game
 * reads player info and sends/receives moves through the transport — it
 * never talks to networking, dual-screen, or navigation code directly.
 */
data class GameContext(
    val activeMode: PlayMode,
    val players: List<PlayerInfo>,
    /** Local player's index into players. -1 for a spectator. */
    val localPlayerIndex: Int,
    val transport: MultiplayerTransport
)

@Serializable
data class PlayerInfo(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean = false,
    /** -1 = no team */
    val teamId: Int = -1
)

data class GameResult(
    val wasAborted: Boolean = false,
    val scores: List<PlayerScore> = emptyList()
)

data class PlayerScore(
    val playerId: String,
    val score: Int,
    val isWinner: Boolean
)

/** How the LOCAL device's own player fared in a finished match — null when that can't be
 *  attributed to a single outcome (a spectator, localPlayerIndex == -1, or an unscored
 *  result). Computed once by GameSessionManager (which already has both GameContext and
 *  GameResult at the moment a match ends) so a stats layer never needs game-specific rules
 *  or its own copy of "who am I in this match" logic. */
enum class LocalOutcome { WIN, LOSS, DRAW }

/** A finished match's result, already resolved to this device's own outcome — the shell's
 *  hand-off from [GameSessionManager] to a stats/history layer. Kept in `core` (not `stats`)
 *  so the stats package depends on core, never the other way around. */
data class MatchOutcome(
    val gameId: String,
    val gameDisplayName: String,
    val result: GameResult,
    val localOutcome: LocalOutcome?
)
