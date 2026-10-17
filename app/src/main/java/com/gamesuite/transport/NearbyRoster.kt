package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo
import java.util.UUID

/**
 * The seat id of the host (`players[0]`) in a Nearby match. Reserved: [NearbyRoster] never
 * assigns it to a guest, and [EndpointIdentityMap] on the host side refuses a mapping that
 * contains it.
 */
const val NEARBY_HOST_PLAYER_ID = "host"

/**
 * One guest seat: the Nearby endpoint the HOST is connected to, and the seat id the host assigned
 * it. [idWasReplaced] is true when that is NOT the id the guest proposed, i.e. the guest has to be
 * told its real id (see [NearbyRoster.gameStartFor]).
 */
data class NearbySeat(
    val endpointId: String,
    val playerId: String,
    val displayName: String,
    val idWasReplaced: Boolean = false,
)

/**
 * The host lobby's roster, and the only place a Nearby guest's game-level `playerId` (the
 * `fromPlayerId` every message from that guest is stamped with) is decided.
 *
 * A guest tells the host an id in [NearbyLobbyMessage.JoinRequest.clientPlayerId], but that is
 * only a *proposal*: it comes out of a payload the guest controls, and taking it at face value
 * let a guest claim `"host"` (or another guest's id) and have its intents stamped as that seat.
 * So the host assigns ids, and stamps come from the endpoint mapping this roster produces:
 *  - a proposal is kept only if it is well-formed, is not the reserved host id (any casing),
 *    and no other seat already has it; otherwise the host mints a fresh one and tells the guest
 *    via [NearbyLobbyMessage.GameStart.yourPlayerId] (see [gameStartFor]);
 *  - an endpoint keeps the seat id it was first given, however many JoinRequests it sends;
 *  - once [close]d (Start Game tapped) the roster no longer changes.
 *
 * Immutable: every change returns a new roster, so the lobby screen can hold it in Compose state.
 */
class NearbyRoster private constructor(
    val guests: List<NearbySeat>,
    val isClosed: Boolean,
    private val mintId: () -> String,
) {
    constructor(mintId: () -> String = { "p-" + UUID.randomUUID() }) : this(emptyList(), false, mintId)

    /** The full player list for the match: the host first (seat 0), then guests in join order. */
    val players: List<PlayerInfo>
        get() = listOf(PlayerInfo(playerId = NEARBY_HOST_PLAYER_ID, displayName = "Host")) +
            guests.map { PlayerInfo(playerId = it.playerId, displayName = it.displayName) }

    /** endpointId -> seat id: what [NearbyConnectionsTransport.setPlayerIdMapping] is given. */
    val endpointToPlayerId: Map<String, String>
        get() = guests.associate { it.endpointId to it.playerId }

    /** Records a guest's JoinRequest arriving on [endpointId]. See the class KDoc for the id rules. */
    fun withJoinRequest(endpointId: String, request: NearbyLobbyMessage.JoinRequest): NearbyRoster {
        if (isClosed) return this
        val name = cleanDisplayName(request.displayName)
        val existing = guests.firstOrNull { it.endpointId == endpointId }
        if (existing != null) {
            // Same endpoint again (a re-sent JoinRequest): the seat id never changes, only the name.
            return copy(guests.map { if (it === existing) it.copy(displayName = name) else it })
        }
        val taken = guests.mapTo(HashSet()) { it.playerId }
        val kept = isAcceptable(request.clientPlayerId, taken)
        val playerId = if (kept) request.clientPlayerId else mintUnused(taken)
        return copy(guests + NearbySeat(endpointId, playerId, name, idWasReplaced = !kept))
    }

    /** A guest's display name is text the guest wrote, and it is sent to every guest in the GameStart (a
     *  Nearby BYTES payload tops out at 32 KB, so an oversized name could stop the match from starting for
     *  everyone). Control characters are dropped, the length is capped like the relay's, and a blank name
     *  becomes "Guest". It is cosmetic only: identity never depends on it. */
    private fun cleanDisplayName(raw: String): String =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_DISPLAY_NAME_LENGTH).ifBlank { "Guest" }

    /**
     * The GameStart to send [seat]'s endpoint. [NearbyLobbyMessage.GameStart.yourPlayerId] is set
     * only when the host replaced the guest's proposed id: an honest guest (whose id was kept)
     * gets exactly the message builds without that field send, which matters because an older
     * guest's strict JSON decoder rejects a message with a field it doesn't know, and would
     * otherwise never start.
     */
    fun gameStartFor(seat: NearbySeat, gameRoute: String): NearbyLobbyMessage.GameStart =
        NearbyLobbyMessage.GameStart(gameRoute, players, yourPlayerId = seat.playerId.takeIf { seat.idWasReplaced })

    /** Drops guests whose endpoint is no longer connected. */
    fun retainingEndpoints(connectedEndpointIds: Collection<String>): NearbyRoster {
        if (isClosed) return this
        val kept = guests.filter { it.endpointId in connectedEndpointIds }
        return if (kept.size == guests.size) this else copy(kept)
    }

    /** Freezes the roster: later JoinRequests (a late joiner after Start) change nothing. */
    fun close(): NearbyRoster = if (isClosed) this else NearbyRoster(guests, true, mintId)

    /**
     * Everything "Start Game" does that decides identity, in one place the lobby screen just applies:
     * freeze the roster, then the player list, the endpoint mapping to install on the transport, and
     * the GameStart to send each guest's endpoint. What is announced and what is installed cannot differ.
     */
    fun launch(gameRoute: String): NearbyLaunch {
        val frozen = close()
        return NearbyLaunch(
            roster = frozen,
            players = frozen.players,
            endpointToPlayerId = frozen.endpointToPlayerId,
            startMessages = frozen.guests.map { it.endpointId to frozen.gameStartFor(it, gameRoute) },
        )
    }

    private fun copy(newGuests: List<NearbySeat>) = NearbyRoster(newGuests, isClosed, mintId)

    private fun mintUnused(taken: Set<String>): String {
        repeat(MAX_MINT_ATTEMPTS) {
            val candidate = mintId()
            if (isAcceptable(candidate, taken)) return candidate
        }
        error("could not mint a unique seat id after $MAX_MINT_ATTEMPTS attempts")
    }

    private fun isAcceptable(candidate: String, taken: Set<String>): Boolean =
        SEAT_ID_PATTERN.matches(candidate) &&
            !candidate.equals(NEARBY_HOST_PLAYER_ID, ignoreCase = true) &&
            candidate !in taken

    private companion object {
        /** The app mints "p-" + a UUID (38 chars); anything outside this shape is re-minted, not trusted. */
        val SEAT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        const val MAX_MINT_ATTEMPTS = 32
        const val MAX_DISPLAY_NAME_LENGTH = 40
    }
}

/** The outcome of [NearbyRoster.launch]: [roster] is the frozen roster; [startMessages] pairs each guest's
 *  endpoint id with the GameStart to send it. */
class NearbyLaunch(
    val roster: NearbyRoster,
    val players: List<PlayerInfo>,
    val endpointToPlayerId: Map<String, String>,
    val startMessages: List<Pair<String, NearbyLobbyMessage.GameStart>>,
)

/**
 * The identity-sensitive half of [NearbyConnectionsTransport]'s game layer, with no Android types so it
 * can be tested: which seat an inbound payload is stamped as (from the ENDPOINT it arrived on, never from
 * anything inside it) and which endpoints a send goes to.
 *
 * [install] validates the mapping ([EndpointIdentityMap]) and, on the host, refuses one that would stamp a
 * guest endpoint as [NEARBY_HOST_PLAYER_ID]; the swap is a single volatile write, so a receive thread never
 * sees a half-installed mapping.
 */
class NearbyGameRouter {
    @Volatile private var identities: EndpointIdentityMap = EndpointIdentityMap.EMPTY

    /** @throws IllegalArgumentException for a mapping [EndpointIdentityMap] refuses. */
    fun install(endpointToPlayerId: Map<String, String>, isHost: Boolean) {
        identities = EndpointIdentityMap(
            endpointToPlayerId,
            reservedPlayerIds = if (isHost) setOf(NEARBY_HOST_PLAYER_ID) else emptySet(),
        )
    }

    fun reset() {
        identities = EndpointIdentityMap.EMPTY
    }

    /** The seat a payload from [endpointId] is stamped as, or null for an endpoint with no seat (dropped). */
    fun playerIdFor(endpointId: String): String? = identities.playerIdFor(endpointId)

    /** The endpoints a send to [toPlayerId] goes to: every seated endpoint for null (broadcast), the one
     *  endpoint for a known seat, none for an unknown seat (dropped, like a stale post-disconnect endpoint). */
    fun endpointsFor(toPlayerId: String?): List<String> {
        val current = identities
        if (toPlayerId == null) return current.endpointIds
        return listOfNotNull(current.endpointFor(toPlayerId))
    }
}

/**
 * A validated, immutable endpointId <-> playerId mapping: the thing a Nearby transport stamps
 * `fromPlayerId` from. One endpoint is exactly one seat and one seat is exactly one endpoint, so
 * a stamp can never be shared between two senders; otherwise (two endpoints, one id) the later
 * mapping silently wins and one device speaks with the other's identity.
 *
 * @throws IllegalArgumentException if two endpoints map to the same playerId, an id is blank, or
 *   an id is in [reservedPlayerIds] (the host side passes [NEARBY_HOST_PLAYER_ID]: the host is
 *   not an endpoint, so no guest endpoint may ever be stamped as it).
 */
class EndpointIdentityMap(
    endpointToPlayerId: Map<String, String> = emptyMap(),
    reservedPlayerIds: Set<String> = emptySet(),
) {
    private val byEndpoint: Map<String, String> = endpointToPlayerId.toMap()
    private val byPlayer: Map<String, String> = byEndpoint.entries.associate { (endpointId, playerId) -> playerId to endpointId }

    init {
        require(byEndpoint.values.none { it.isBlank() }) { "blank playerId in endpoint mapping" }
        require(byPlayer.size == byEndpoint.size) { "two endpoints map to the same playerId: $byEndpoint" }
        val reserved = byEndpoint.values.firstOrNull { it in reservedPlayerIds }
        require(reserved == null) { "playerId \"$reserved\" is reserved and cannot be mapped to a guest endpoint" }
    }

    /** The seat a message that arrived from [endpointId] is stamped as, or null for an unmapped endpoint (dropped). */
    fun playerIdFor(endpointId: String): String? = byEndpoint[endpointId]

    fun endpointFor(playerId: String): String? = byPlayer[playerId]

    val endpointIds: List<String> get() = byEndpoint.keys.toList()

    companion object {
        val EMPTY = EndpointIdentityMap()
    }
}
