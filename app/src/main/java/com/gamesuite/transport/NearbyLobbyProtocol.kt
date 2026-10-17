package com.gamesuite.transport

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubModeKind
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
 * differently. So the HOST assigns every guest a game-level `playerId` (see [NearbyRoster])
 * and puts them all in the roster it sends in [GameStart]; a guest whose proposed id had to be
 * replaced is told its real one in [GameStart.yourPlayerId] (every other guest finds itself
 * under the id it proposed).
 *
 * A guest's [JoinRequest.clientPlayerId] is only a *proposal*. The host keeps it when it is
 * well-formed and unclaimed, so an honest guest ends up with the id it minted, and replaces it
 * otherwise. It is never trusted as an identity: `fromPlayerId` on every game message is
 * derived by the transport from the endpoint the bytes arrived on (see [EndpointIdentityMap]),
 * never from anything inside the payload.
 */
@Serializable
sealed class NearbyLobbyMessage {
    /**
     * Guest -> host, sent immediately after the connection is confirmed. [clientPlayerId] is a
     * proposal the host may replace (see the class KDoc), not an identity.
     */
    @Serializable
    data class JoinRequest(val clientPlayerId: String, val displayName: String) : NearbyLobbyMessage()

    /**
     * Host -> all connected guests, sent once when "Start Game" is tapped. [gameRoute]
     * is the NavHost route to launch (e.g. "uno") — lets one lobby flow serve every
     * LOCAL_AD_HOC-capable game rather than needing a bespoke lobby per game.
     *
     * The host sends each guest its own copy. [yourPlayerId] is set, to the seat id the host
     * assigned, only when the host REPLACED the id that guest proposed; when it is null the guest
     * finds itself under the id it sent (which is all an older host ever does). Leaving it out for
     * honest guests keeps the message identical to what older guests, whose JSON decoder rejects
     * unknown fields, can read.
     */
    @Serializable
    data class GameStart(
        val gameRoute: String,
        val players: List<PlayerInfo>,
        val yourPlayerId: String? = null,
        /** The game's display name ("Chess"), for the guest's "Joining Chess" text; cosmetic only. */
        val gameDisplayName: String? = null,
    ) : NearbyLobbyMessage()

    /**
     * Host -> one guest: the host will not seat this guest (room full, or the game already started).
     * [reason] is text for the guest to show. The guest leaves the lobby; it carries no identity.
     */
    @Serializable
    data class Rejected(val reason: String) : NearbyLobbyMessage()
}

/** The text of a [NearbyLobbyMessage.Rejected] / [OnlineLobbyMessage.Rejected] made safe to show: no control chars, capped. */
fun lobbyRejectionText(raw: String): String =
    raw.filterNot { it.isISOControl() }.trim().take(120).ifBlank { "The host did not accept you." }

/** The Hub spec whose Nearby/Online mode plays [gameRoute], or null when this build cannot play it over a network. */
private fun networkedSpecForRoute(gameRoute: String): GameHubSpec? =
    GameHubSpecs.all.firstOrNull { spec ->
        spec.modes.any { (it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE) && it.route == gameRoute }
    }

/** The NavHost route of [gameId]'s lobby mode (what a host announces as `gameRoute`), or [gameId] itself. */
fun lobbyRouteFor(gameId: String): String =
    GameHubSpecs.byId(gameId)?.modes?.firstOrNull { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE }?.route
        ?: GameHubSpecs.byId(gameId)?.modes?.firstOrNull()?.route
        ?: gameId

/**
 * Why a guest must NOT start into a host's announced game, or null when it may. [expectedGameId] is the game the
 * guest opened the lobby for (its Hub); a room playing something else is refused with a message naming both, and a
 * route this build has no networked mode for (the host runs a newer or different build) is refused as unplayable.
 * [announcedName] is host-written text, used only (cleaned) when the route is unknown.
 */
fun lobbyGameProblem(gameRoute: String, announcedName: String?, expectedGameId: String?): String? {
    val spec = networkedSpecForRoute(gameRoute)
    if (spec == null) {
        val cleaned = (announcedName ?: "").filterNot { it.isISOControl() }.trim().take(40)
        val name = if (cleaned.isEmpty()) "a game" else cleaned
        return "This room is playing $name, which this version of GameSuite can't play over a network."
    }
    if (expectedGameId != null && spec.gameId != expectedGameId) {
        val expected = GameHubSpecs.byId(expectedGameId)?.title ?: expectedGameId
        return "This room is playing ${spec.title}, not $expected."
    }
    return null
}

/**
 * Whether a guest should act on this [NearbyLobbyMessage.GameStart]: only when it arrived on
 * the endpoint the guest itself connected to, i.e. the host. Any other connected endpoint could
 * otherwise start the guest into a match whose roster (and so whose "host", seat 0) it wrote.
 */
fun NearbyLobbyMessage.GameStart.isAcceptedFrom(fromEndpointId: String, hostEndpointId: String?): Boolean =
    hostEndpointId != null && fromEndpointId == hostEndpointId && players.isNotEmpty()

/** What a Nearby guest does with an accepted GameStart: where it sits, and the mapping to install. */
class NearbyGuestStart(val localPlayerIndex: Int, val hostEndpointToPlayerId: Map<String, String>)

/**
 * The whole guest-side decision for a GameStart, in one tested place the join screen just applies: null
 * (ignore the message) unless it came from the host endpoint AND seats this guest validly ([guestSeatIndex]);
 * otherwise the guest's seat and the one endpoint -> seat mapping to install (the endpoint the guest connected
 * to IS seat 0, whatever id the roster gives it). The guest is found under [yourPlayerId] when the host had
 * to replace its id, else under the id it proposed ([clientPlayerId]).
 */
fun NearbyLobbyMessage.GameStart.resolveForGuest(fromEndpointId: String, hostEndpointId: String?, clientPlayerId: String): NearbyGuestStart? {
    if (!isAcceptedFrom(fromEndpointId, hostEndpointId)) return null
    val index = guestSeatIndex(players, yourPlayerId ?: clientPlayerId)
    if (index < 0) return null
    return NearbyGuestStart(index, mapOf(fromEndpointId to players[0].playerId))
}
