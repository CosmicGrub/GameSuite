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
 * "Start Game" is tapped. Because ANY room member (a guest, or a spectator) can relay a
 * message shaped like one, a guest only acts on a [GameStart] that [isFromHost].
 */
@Serializable
sealed class OnlineLobbyMessage {
    /** Host -> all connected guests. [gameRoute] is the NavHost route to launch (e.g. "uno"). */
    @Serializable
    data class GameStart(val gameRoute: String, val players: List<PlayerInfo>) : OnlineLobbyMessage()
}

/**
 * Whether a guest should act on this [OnlineLobbyMessage.GameStart]: the relay stamped it as
 * coming from [hostPlayerId] (the room's creator, see [OnlineTransport.hostPlayerId]), and the
 * roster agrees that sender is seat 0. Anything else was written by another room member and
 * would let them dictate who "the host" of the guest's match is.
 */
fun OnlineLobbyMessage.GameStart.isFromHost(fromPlayerId: String, hostPlayerId: String?): Boolean =
    hostPlayerId != null && fromPlayerId == hostPlayerId && players.firstOrNull()?.playerId == fromPlayerId

/**
 * Where a guest sits for this (already host-verified) GameStart: null means ignore it, -1 means watch only,
 * otherwise the guest's seat. A spectator never takes a seat, even one the roster claims to give it; a
 * player must be validly seated ([guestSeatIndex]).
 */
fun OnlineLobbyMessage.GameStart.localSeatIndex(clientPlayerId: String, asSpectator: Boolean): Int? {
    if (asSpectator) return -1
    return guestSeatIndex(players, clientPlayerId).takeIf { it > 0 }
}
