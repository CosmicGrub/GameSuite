package com.gamesuite.core

import com.gamesuite.transport.LOBBY_FULL_MESSAGE
import com.gamesuite.transport.LobbyJson
import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.OnlineLateJoinResponder
import com.gamesuite.transport.OnlineLobbyMessage
import com.gamesuite.transport.OnlinePlayer
import com.gamesuite.transport.SeatView
import com.gamesuite.transport.sanitized
import com.gamesuite.transport.isCpuPlayerId
import com.gamesuite.transport.isFromHost
import com.gamesuite.transport.lobbyGameProblem
import com.gamesuite.transport.lobbyRejectionText
import com.gamesuite.transport.localSeatIndex
import com.gamesuite.transport.seatOnlineGuests

/**
 * The raw (lobby-layer) relay channel the Online lobbies talk through: addressed or broadcast payloads out,
 * and a single listener for everything that arrives. [com.gamesuite.transport.OnlineTransport] is the real
 * one; tests use a fake. Kept to these two members so the match-start logic below has no Android or socket types.
 */
interface OnlineRawChannel {
    /** [toPlayerId] null = broadcast to the whole room. */
    fun sendRaw(toPlayerId: String?, payload: ByteArray)

    fun onRawMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit)
}

/** What a guest does with a host-verified GameStart; see [OnlineMatchStarter.guestDecision]. */
sealed class GuestStartDecision {
    /** The room plays a game this guest must not start into (wrong game, or one this build cannot play online). */
    data class Refused(val reason: String) : GuestStartDecision()

    /** The roster did not (validly) seat this client: ignore the message rather than crash. */
    object Ignore : GuestStartDecision()

    /** Launch the match: [localPlayerIndex] is the guest's seat, or -1 to watch only. */
    data class Launch(val message: OnlineLobbyMessage.GameStart, val localPlayerIndex: Int) : GuestStartDecision()
}

/**
 * Everything the two Online lobby screens used to do inline, in one tested place: the HOST turning away
 * overflow guests and starting the match, and the GUEST deciding what a received GameStart means. The screens
 * now only render state and apply the results (navigation, `launchGame`).
 */
object OnlineMatchStarter {

    /** The announced roster: the host (seat 0) first, then the seated guests in join order, then any computer seats. */
    fun hostRoster(
        hostPlayerId: String,
        hostName: String,
        seated: List<OnlinePlayer>,
        cpuSeats: List<PlayerInfo> = emptyList(),
    ): List<PlayerInfo> =
        listOf(PlayerInfo(playerId = hostPlayerId, displayName = hostName)) +
            seated.map { PlayerInfo(playerId = it.playerId, displayName = it.displayName) } +
            cpuSeats

    /**
     * Tells each overflow guest (more guests than the game has seats) that the room is full, once per id:
     * [turnedAway] remembers who was already told. Returns the ids told by this call.
     */
    fun rejectOverflow(channel: OnlineRawChannel, overflow: List<OnlinePlayer>, turnedAway: MutableSet<String>): List<String> {
        val told = ArrayList<String>()
        overflow.filter { turnedAway.add(it.playerId) }.forEach { extra ->
            val rejected: OnlineLobbyMessage = OnlineLobbyMessage.Rejected(LOBBY_FULL_MESSAGE)
            channel.sendRaw(extra.playerId, encode(rejected))
            told += extra.playerId
        }
        return told
    }

    /**
     * The host's Start: re-seats [connected] guests against [limits] (anyone beyond the game's seats was already
     * turned away and is not announced), refuses when Start is not allowed, otherwise installs the late-join
     * responder BEFORE broadcasting [OnlineLobbyMessage.GameStart] (so no Hello can fall in the gap), broadcasts it,
     * and calls [launch] with the announced roster (host = seat 0). Returns whether it started.
     */
    fun startAsHost(
        channel: OnlineRawChannel,
        hostPlayerId: String,
        hostName: String,
        connected: List<OnlinePlayer>,
        limits: LobbySeatLimits,
        gameRoute: String,
        gameDisplayName: String,
        /** Computer seats the host filled ([cpuSeatsFor]); they follow the guests, and every one must be a bot with a cpu- id. */
        cpuSeats: List<PlayerInfo> = emptyList(),
        launch: (players: List<PlayerInfo>) -> Unit,
    ): Boolean {
        val seated = seatOnlineGuests(connected, limits).seated
        if (!limits.canStart(seated.size, cpuSeats.size)) return false
        if (!cpuSeats.all { it.isBot && isCpuPlayerId(it.playerId) }) return false
        val players = hostRoster(hostPlayerId, hostName, seated, cpuSeats)

        val gameStart = OnlineLobbyMessage.GameStart(gameRoute, players, gameDisplayName)
        // The screen is left right after Start, but the transport (and this listener on it) lives as long as the
        // game: a spectator or a guest that arrives, or reconnects, after Start asks with a Hello and is answered
        // with the GameStart again (or a "started" refusal for a seat).
        val lateJoin = OnlineLateJoinResponder(gameStart) { to, bytes -> channel.sendRaw(to, bytes) }
        channel.onRawMessageReceived { from, bytes -> lateJoin.onRawMessage(from, bytes) }
        val startMessage: OnlineLobbyMessage = gameStart
        channel.sendRaw(null, encode(startMessage))

        launch(players)
        return true
    }

    /**
     * The guest's verdict on a host-verified GameStart: a room playing a game we did not open (or cannot play
     * online) is [GuestStartDecision.Refused]; a roster that does not validly seat this client (and is not a
     * spectator, who is legitimately absent from it) is [GuestStartDecision.Ignore]; otherwise [GuestStartDecision.Launch].
     */
    fun guestDecision(
        message: OnlineLobbyMessage.GameStart,
        expectedGameId: String?,
        clientPlayerId: String,
        asSpectator: Boolean,
    ): GuestStartDecision {
        val problem = lobbyGameProblem(message.gameRoute, message.gameDisplayName, expectedGameId)
        if (problem != null) return GuestStartDecision.Refused(problem)
        val index = message.localSeatIndex(clientPlayerId, asSpectator) ?: return GuestStartDecision.Ignore
        return GuestStartDecision.Launch(message, index)
    }

    /**
     * The guest lobby's raw-message listener. Any room member can relay a GameStart- or Rejected-shaped payload;
     * only the host's (per [hostPlayerId], read at message time because it is learned from the relay's 'joined')
     * is believed. [onGameStart] receives the verified GameStart, [onRejected] the cleaned refusal text.
     */
    fun guestListener(
        hostPlayerId: () -> String?,
        onGameStart: (OnlineLobbyMessage.GameStart) -> Unit,
        onRejected: (reasonText: String) -> Unit,
        /** The host's description of the lobby table (already sanitized); optional, for the guest's seat grid. */
        onRoster: (List<SeatView>) -> Unit = {},
    ): (fromPlayerId: String, payload: ByteArray) -> Unit = { fromPlayerId, payload ->
        val message = runCatching {
            LobbyJson.decodeFromString(OnlineLobbyMessage.serializer(), String(payload, Charsets.UTF_8))
        }.getOrNull()
        val host = hostPlayerId()
        if (message is OnlineLobbyMessage.GameStart && message.isFromHost(fromPlayerId, host)) onGameStart(message)
        if (message is OnlineLobbyMessage.Rejected && message.isFromHost(fromPlayerId, host)) {
            onRejected(lobbyRejectionText(message.reason))
        }
        if (message is OnlineLobbyMessage.RosterUpdate && message.isFromHost(fromPlayerId, host)) onRoster(message.sanitized())
    }

    private fun encode(message: OnlineLobbyMessage): ByteArray =
        kotlinx.serialization.json.Json.encodeToString(OnlineLobbyMessage.serializer(), message).toByteArray(Charsets.UTF_8)
}
