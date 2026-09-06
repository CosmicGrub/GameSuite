package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.Serializable

/**
 * Lobby-phase wire message, sent through [OnlineTransport]'s raw relay channel —
 * the exact same role [NearbyLobbyMessage.GameStart] plays for Nearby, adapted to
 * this transport's simpler identity model. Unlike Nearby's endpoint ids (which are
 * per-observer and can't double as a stable cross-device playerId — see
 * [NearbyLobbyMessage]'s KDoc), the relay server already deals in the game-level
 * `playerId` a client chose for itself, so no id-translation layer is needed here:
 * a [GameStart] is just one more relayed message, broadcast host-to-all the moment
 * "Start Game" is tapped.
 */
@Serializable
sealed class OnlineLobbyMessage {
    /** Host -> all connected guests. [gameRoute] is the NavHost route to launch (e.g. "uno"). */
    @Serializable
    data class GameStart(val gameRoute: String, val players: List<PlayerInfo>) : OnlineLobbyMessage()
}
