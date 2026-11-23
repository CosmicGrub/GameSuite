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
    data class GameStart(
        val gameRoute: String,
        val players: List<PlayerInfo>,
        /** The game's display name ("Chess"), for the guest's "Joining Chess" text; cosmetic only. */
        val gameDisplayName: String? = null,
    ) : OnlineLobbyMessage()

    /**
     * Host -> one guest (addressed): the room has more guests than the game has seats and this one did not get
     * a seat. [reason] is text to show; the guest leaves. A guest only believes it from the host ([isFromHost]).
     */
    @Serializable
    data class Rejected(val reason: String) : OnlineLobbyMessage()

    /**
     * Guest or spectator -> host (addressed), sent once the relay confirms a join (and again after a
     * reconnect). The relay announces a joining SEAT to the host but never a spectator, and the host
     * broadcasts [GameStart] only once, from its Start button, so a member who arrives after Start would
     * wait forever. A host whose game is running answers a Hello (see [OnlineLateJoinResponder]); a host
     * still in its lobby ignores it, since the Start broadcast is still to come. [spectator] is what the
     * sender asked the relay for; it only decides between a [GameStart] and a "game already started"
     * refusal, so claiming it falsely gains nothing (a watcher never receives a hand).
     */
    @Serializable
    data class Hello(val spectator: Boolean = false) : OnlineLobbyMessage()

    /**
     * Guest -> host (addressed): this guest is ready to start (or no longer). Only believed from a SEATED guest's
     * relay-stamped id, never from anything inside the payload; the host decides what readiness means (see
     * [com.gamesuite.core.OnlineHostLobbyState]). A host that predates this simply ignores it.
     */
    @Serializable
    data class Ready(val ready: Boolean) : OnlineLobbyMessage()

    /**
     * Host -> everyone (broadcast), whenever a seat changes: what the lobby table looks like, so every guest sees the
     * same seat grid with avatars, computers and ready marks. A guest only believes it from the host
     * ([isFromHost]) and shows [sanitized] text. Purely informational: it never decides who is seated (the
     * [GameStart] roster does).
     */
    @Serializable
    data class RosterUpdate(val seats: List<SeatView>) : OnlineLobbyMessage()
}

/** What a lobby seat holds. */
@Serializable
enum class SeatKind { HOST, HUMAN, CPU, EMPTY }

/** One seat of the lobby table as the host reports it in [OnlineLobbyMessage.RosterUpdate]. */
@Serializable
data class SeatView(
    val kind: SeatKind,
    val playerId: String? = null,
    val name: String = "",
    val ready: Boolean = false,
)

/** [seats] made safe to show: a bounded count, control characters dropped, names capped, ids bounded. */
fun OnlineLobbyMessage.RosterUpdate.sanitized(): List<SeatView> =
    seats.take(MAX_LOBBY_SEATS).map {
        it.copy(
            playerId = it.playerId?.filterNot { c -> c.isISOControl() }?.take(64),
            name = it.name.filterNot { c -> c.isISOControl() }.trim().take(40),
        )
    }

/** The most seats any lobby table can show (the relay seats 8 per room). */
const val MAX_LOBBY_SEATS = 16

/** Whether a guest should act on this [OnlineLobbyMessage.RosterUpdate]: only the room's host describes the table. */
fun OnlineLobbyMessage.RosterUpdate.isFromHost(fromPlayerId: String, hostPlayerId: String?): Boolean =
    hostPlayerId != null && fromPlayerId == hostPlayerId

/** The raw relay payload of an [OnlineLobbyMessage.Hello]. */
fun onlineHelloPayload(spectator: Boolean): ByteArray {
    val hello: OnlineLobbyMessage = OnlineLobbyMessage.Hello(spectator)
    return LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), hello).toByteArray(Charsets.UTF_8)
}

/**
 * Host-side answer to late arrivals, alive for as long as the host's game is. Feed every raw relay
 * message to [onRawMessage]. A [OnlineLobbyMessage.Hello] from a roster seat (a reconnecting guest that
 * missed Start) or from a spectator gets the [gameStart] re-sent, addressed to that sender alone; a
 * Hello from anyone else who wanted a seat gets [LOBBY_STARTED_MESSAGE]. Game traffic and everything
 * else is ignored, and a single sender is answered a bounded number of times.
 */
class OnlineLateJoinResponder(
    private val gameStart: OnlineLobbyMessage.GameStart,
    private val sendRaw: (toPlayerId: String, payload: ByteArray) -> Unit,
) {
    private val answered = HashMap<String, Int>()

    @Synchronized
    fun onRawMessage(fromPlayerId: String, payload: ByteArray) {
        if (payload.size > MAX_HELLO_BYTES) return // game traffic, not a lobby Hello
        val message = runCatching {
            LobbyJson.decodeFromString(OnlineLobbyMessage.serializer(), String(payload, Charsets.UTF_8))
        }.getOrNull()
        if (message !is OnlineLobbyMessage.Hello) return
        if (fromPlayerId == gameStart.players.firstOrNull()?.playerId) return // the host never answers itself
        val count = answered[fromPlayerId] ?: 0
        if (count >= MAX_ANSWERS_PER_SENDER) return
        answered[fromPlayerId] = count + 1
        val seated = gameStart.players.any { it.playerId == fromPlayerId }
        val reply: OnlineLobbyMessage =
            if (seated || message.spectator) gameStart else OnlineLobbyMessage.Rejected(LOBBY_STARTED_MESSAGE)
        sendRaw(fromPlayerId, LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), reply).toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val MAX_HELLO_BYTES = 512
        const val MAX_ANSWERS_PER_SENDER = 8
    }
}

/** Whether a guest should act on this [OnlineLobbyMessage.Rejected]: only the room's host can turn a guest away. */
fun OnlineLobbyMessage.Rejected.isFromHost(fromPlayerId: String, hostPlayerId: String?): Boolean =
    hostPlayerId != null && fromPlayerId == hostPlayerId

/** Who gets a seat in an Online room and who is turned away; see [seatOnlineGuests]. */
class OnlineSeating(val seated: List<OnlinePlayer>, val overflow: List<OnlinePlayer>)

/**
 * The relay seats up to 8 per room, regardless of the game, so the HOST enforces the game's seat count: the first
 * `limits.maxGuests` guests in join order are seated, anyone beyond that is overflow (sent a
 * [OnlineLobbyMessage.Rejected] and left out of the announced roster).
 */
fun seatOnlineGuests(connected: List<OnlinePlayer>, limits: LobbySeatLimits): OnlineSeating {
    // An id in the computer-seat namespace ("cpu-N") is never seated: it would collide with a computer seat's id and
    // make every guest's roster fail the duplicate-id check. It is turned away like overflow.
    val (impostors, candidates) = connected.partition { isCpuPlayerId(it.playerId) }
    return OnlineSeating(candidates.take(limits.maxGuests), candidates.drop(limits.maxGuests) + impostors)
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
