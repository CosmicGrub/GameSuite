package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.Serializable

/**
 * Lobby-phase wire protocol, sent over [NearbyConnectionsTransport]'s raw (endpoint-id
 * addressed) channel — before any game-level `playerId` mapping exists yet.
 *
 * Nearby endpoint IDs are per-observer: the id the host sees for a given guest's
 * connection is generally NOT the same string the guest sees for its own connection to
 * the host. That means an endpoint id can't be reused as a cross-device-stable
 * `PlayerInfo.playerId` — the two sides would each be labeling the same player
 * differently. Instead, each guest mints its own random, stable [JoinRequest.clientPlayerId]
 * client-side and tells the host what it is; the host echoes it straight back into the
 * roster it sends in [GameStart], so both sides end up agreeing on the same id for the
 * same player without either one needing to know the other's local endpoint-id view.
 */
@Serializable
sealed class NearbyLobbyMessage {
    /** Guest -> host, sent immediately after the connection is confirmed. */
    @Serializable
    data class JoinRequest(val clientPlayerId: String, val displayName: String) : NearbyLobbyMessage()

    /**
     * Host -> all connected guests, sent once when "Start Game" is tapped. [gameRoute]
     * is the NavHost route to launch (e.g. "uno") — lets one lobby flow serve every
     * LOCAL_AD_HOC-capable game rather than needing a bespoke lobby per game.
     */
    @Serializable
    data class GameStart(val gameRoute: String, val players: List<PlayerInfo>) : NearbyLobbyMessage()
}
