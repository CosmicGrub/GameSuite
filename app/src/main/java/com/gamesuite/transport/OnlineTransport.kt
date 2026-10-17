package com.gamesuite.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A room member as reported by the relay server — mirrors [ConnectedPeer] from the Nearby transport. */
data class OnlinePlayer(val playerId: String, val displayName: String)

enum class OnlineRole { NONE, HOST, GUEST }

/**
 * Real internet multiplayer (roadmap item 12) over a room-code relay WebSocket
 * server — see `server/index.js` and `server/README.md` for the backend this
 * talks to and the exact wire protocol. Every game stays host-authoritative on
 * this transport exactly as it already is on [NearbyConnectionsTransport] —
 * this class only carries bytes between players, same as that one; it never
 * inspects game state.
 *
 * Two layers, same split as [NearbyConnectionsTransport] (read that class's
 * KDoc first if this is unfamiliar) — but simpler here, because there's no
 * endpoint-id/playerId translation to do:
 *  1. **Lobby layer** (not part of [MultiplayerTransport]): [hostRoom]/[joinRoom],
 *     [roomCode]/[connectedPlayers] for the lobby UI, [onRawMessageReceived] for
 *     the host's [OnlineLobbyMessage.GameStart] broadcast.
 *  2. **Game layer** ([MultiplayerTransport] itself): every relayed "message"
 *     frame already carries the real, stable `playerId` a client chose for
 *     itself when it hosted/joined (the server relays the sender's playerId,
 *     it doesn't invent connection-layer ids the way Nearby's endpoint ids
 *     work) — so unlike Nearby, no [NearbyConnectionsTransport.setPlayerIdMapping]
 *     equivalent is needed. [send]/[onMessageReceived] work directly.
 *
 * That `fromPlayerId` is only as good as the relay's seat ownership: playerIds are public
 * (every guest's roster lists the host's), so the relay hands the socket that takes a seat a
 * secret `reconnectToken` ('hosted'/'joined'), and this class keeps it and presents it when it
 * re-takes its seat after a dropped connection ([attemptReconnect]). It is never exposed or
 * relayed. See server/index.js's header comment.
 *
 * [serverUrl] is a `ws://` or `wss://` WebSocket URL — see Settings' "Online
 * server" field (defaults to nothing configured; same-Wi-Fi testing against a
 * locally-run `server/` works with `ws://<lan-ip>:8080`, real internet-wide
 * play needs that server deployed somewhere reachable — server/README.md
 * covers both).
 */
class OnlineTransport(private val serverUrl: String) : MultiplayerTransport {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // keeps NAT/carrier connections from silently timing out
        .build()
    @Volatile private var webSocket: WebSocket? = null

    private var role: OnlineRole = OnlineRole.NONE
    var localPlayerId: String = ""
        private set
    private var localDisplayName: String = ""
    private var localIsSpectator: Boolean = false

    /** Secret the relay issued for OUR seat (null for a spectator, or against an older relay that
     *  issues none); presented on a reconnect 'join' so only this client can re-take the seat. */
    @Volatile private var reconnectToken: String? = null

    /** The playerId of the room's host (`players[0]` of the match): our own when hosting, and what
     *  the relay reports in 'joined' when joining. A guest lobby only believes a
     *  [OnlineLobbyMessage.GameStart] that arrived from this id. Null until known. */
    @Volatile var hostPlayerId: String? = null
        private set

    // Set true right before a deliberate close ([disconnect]) so the reconnect logic below
    // never tries to resurrect a connection the player (or the shell) actually meant to end.
    @Volatile private var intentionalDisconnect = false
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var reconnectJob: Job? = null
    // Guards the read-check-then-write below: [webSocket], [reconnectJob] and
    // [pendingReconnectResult] are all touched from OkHttp's callback threads as well as the
    // reconnect coroutine, and two near-simultaneous onFailure/onClosed callbacks (a TCP RST
    // and a ping-timeout closing within milliseconds of each other, say) must not both pass the
    // "already retrying" guard and launch competing reconnect attempts that race to overwrite
    // [webSocket].
    private val reconnectLock = Any()

    private var onReconnectedListener: (() -> Unit)? = null

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    /** True while an unexpected disconnect is being retried — the lobby/game UI can surface
     *  a "Reconnecting..." indicator off this instead of treating every drop as [connectionError]. */
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _connectedPlayers = MutableStateFlow<List<OnlinePlayer>>(emptyList())
    val connectedPlayers: StateFlow<List<OnlinePlayer>> = _connectedPlayers.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    /** Set on a failed connect/host/join/relay error — the lobby UI surfaces this; cleared by [clearError]. */
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    fun clearError() { _connectionError.value = null }

    private var onHostedListener: ((roomCode: String) -> Unit)? = null
    fun onHosted(listener: (roomCode: String) -> Unit) { onHostedListener = listener }

    private var onJoinedListener: ((roomCode: String, existingPlayers: List<OnlinePlayer>) -> Unit)? = null
    fun onJoined(listener: (roomCode: String, existingPlayers: List<OnlinePlayer>) -> Unit) { onJoinedListener = listener }

    /** Raw relayed payloads, addressed by playerId — for the lobby UI's [OnlineLobbyMessage.GameStart]. */
    private var rawMessageListener: ((fromPlayerId: String, payload: ByteArray) -> Unit)? = null
    fun onRawMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {
        rawMessageListener = listener
    }

    fun sendRaw(toPlayerId: String?, payload: ByteArray) = sendMessageFrame(toPlayerId, payload)

    // ---- MultiplayerTransport (game layer) ----

    private var messageListener: ((String, ByteArray) -> Unit)? = null
    private var joinedListener: ((String) -> Unit)? = null
    private var leftListener: ((String) -> Unit)? = null

    /** No-op: the socket is opened explicitly by [hostRoom]/[joinRoom], well before a GameModule exists to call this. */
    override fun connect() {}

    override fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        sendFrame(WireMessage(type = "leave"))
        webSocket?.close(1000, "leaving")
        webSocket = null
        reconnectToken = null
        hostPlayerId = null
        role = OnlineRole.NONE
        _roomCode.value = null
        _connectedPlayers.value = emptyList()
    }

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) = sendMessageFrame(toPlayerId, payload)

    override fun onMessageReceived(listener: (String, ByteArray) -> Unit) { messageListener = listener }
    override fun onPlayerJoined(listener: (String) -> Unit) { joinedListener = listener }
    override fun onPlayerLeft(listener: (String) -> Unit) { leftListener = listener }
    override fun onReconnected(listener: () -> Unit) { onReconnectedListener = listener }

    // ---- Lobby layer ----

    /** Opens the socket and asks the server to create a fresh room. [onHosted] fires with the room code once confirmed. */
    fun hostRoom(playerId: String, displayName: String) {
        role = OnlineRole.HOST
        localPlayerId = playerId
        localDisplayName = displayName
        localIsSpectator = false
        reconnectToken = null
        hostPlayerId = playerId
        intentionalDisconnect = false
        openSocket {
            sendFrame(WireMessage(type = "host", playerId = playerId, displayName = displayName))
        }
    }

    /** Opens the socket and asks to join an existing room. [onJoined] fires with the pre-existing
     *  roster once confirmed. [spectator]=true gets a read-only seat that renders the live game
     *  (via the same per-recipient hand redaction any non-owned seat already gets) without
     *  occupying one of the game's real player slots — see server/index.js's header comment. */
    fun joinRoom(code: String, playerId: String, displayName: String, spectator: Boolean = false) {
        role = OnlineRole.GUEST
        localPlayerId = playerId
        localDisplayName = displayName
        localIsSpectator = spectator
        reconnectToken = null
        hostPlayerId = null
        intentionalDisconnect = false
        openSocket {
            sendFrame(WireMessage(type = "join", roomCode = code, playerId = playerId, displayName = displayName, spectator = spectator))
        }
    }

    private fun openSocket(onOpenSendInitialFrame: () -> Unit) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpenSendInitialFrame()

            override fun onMessage(webSocket: WebSocket, text: String) = handleIncoming(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleUnexpectedDisconnect(t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) handleUnexpectedDisconnect("Disconnected: $reason")
            }
        })
    }

    /**
     * A drop that wasn't [disconnect]'s own doing. If we were actively in a room, try to win
     * our seat back before giving up and surfacing [connectionError] — OkHttp does not
     * auto-reconnect a closed WebSocket on its own, and the relay holds a dropped seat open for
     * exactly this purpose (server/index.js's reconnect grace period). Spectators reconnect the
     * same way (a plain re-`join` with spectator=true is idempotent — see the relay's own
     * duplicate-playerId check for spectators, which only rejects a genuinely still-connected
     * spectator, not a freshly-dropped one).
     */
    private fun handleUnexpectedDisconnect(reason: String) {
        if (intentionalDisconnect) return
        val code = _roomCode.value
        if (role == OnlineRole.NONE || code == null) {
            _connectionError.value = reason
            return
        }
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true) return // already retrying
            _isReconnecting.value = true
            reconnectJob = reconnectScope.launch {
                val delaysMs = longArrayOf(1_000, 2_000, 4_000, 8_000, 8_000) // ~23s total, under the relay's 30s grace window
                for (attemptDelay in delaysMs) {
                    delay(attemptDelay)
                    if (attemptReconnect(code)) {
                        _isReconnecting.value = false
                        return@launch
                    }
                }
                _isReconnecting.value = false
                _connectionError.value = "Lost connection and couldn't reconnect: $reason"
            }
        }
    }

    /** Resolved by [handleIncoming] when a 'reconnected'/'joined'/'error' arrives while an
     *  [attemptReconnect] is in flight — kept separate from the general listeners above so a
     *  reconnect attempt's outcome doesn't depend on whatever the game layer's own
     *  onMessageReceived happens to do with the same frame. */
    @Volatile private var pendingReconnectResult: CompletableDeferred<Boolean>? = null

    /** One reconnect attempt: opens a fresh socket and re-joins the same room under the same
     *  playerId (presenting the seat's [reconnectToken]). Routes every incoming frame through the
     *  SAME [handleIncoming] the normal connection uses (so a 'reconnected' correctly updates
     *  state and fires [onReconnectedListener], and any game traffic that happens to arrive
     *  immediately after is not silently dropped) rather than a narrower one-off listener.
     *  Returns true on a confirmed 'reconnected' (or 'joined', for a spectator's simpler
     *  re-entry; for a real seat 'joined' means the relay does not know our seat, see
     *  [handleIncoming]).
     *
     *  The new socket becomes [webSocket] only when the relay ACKNOWLEDGES the resume, and
     *  [handleIncoming] does that just before it fires [onReconnectedListener]. Publishing it
     *  earlier let a game send made while the attempt was still connecting reach the relay ahead
     *  of the 'join' ("Not in a room", which fails the attempt); publishing it later left the
     *  send a game makes FROM onReconnected (UnoGame's state resync) going to the dead pre-drop
     *  socket and being dropped. */
    private suspend fun attemptReconnect(code: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingReconnectResult = deferred
        val request = Request.Builder().url(serverUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    json.encodeToString(
                        WireMessage.serializer(),
                        WireMessage(
                            type = "join", roomCode = code, playerId = localPlayerId, displayName = localDisplayName,
                            spectator = localIsSpectator, reconnectToken = reconnectToken
                        )
                    )
                )
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Only the attempt in flight, or the socket it won, may speak. A straggler from an
                // earlier timed-out attempt must not fire onReconnected or complete a later attempt.
                if (pendingReconnectResult !== deferred && this@OnlineTransport.webSocket !== webSocket) return
                handleIncoming(text, viaSocket = webSocket)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resumeSocketEnded(deferred, webSocket, t.message ?: "Connection failed")
            }
            override fun onClosed(webSocket: WebSocket, closeCode: Int, reason: String) {
                if (closeCode != 1000) resumeSocketEnded(deferred, webSocket, "Disconnected: $reason")
            }
        })
        var won = false
        try {
            won = withTimeoutOrNull(3_000) { deferred.await() } ?: false
        } finally { // also runs when disconnect() cancels this attempt mid-wait
            pendingReconnectResult = null
            // Failed, timed out, cancelled, or disconnect() raced the win: never leave or resurrect a connection.
            if (!won || intentionalDisconnect) socket.cancel()
        }
        return won && !intentionalDisconnect
    }

    /** A reconnect attempt's socket failed or closed abnormally. While the attempt is still
     *  pending that just fails it. After it WON, this socket is the live connection, and its drop
     *  is a fresh disconnect to recover from exactly like the first socket's ([openSocket]'s
     *  listener does the same); without this a resumed connection that dropped again was never
     *  noticed and the seat was silently lost. */
    private fun resumeSocketEnded(deferred: CompletableDeferred<Boolean>, socket: WebSocket, reason: String) {
        if (deferred.complete(false)) return
        if (webSocket !== socket) return
        // The retry loop that won this socket is finished, or about to be: onReconnected fires BEFORE that
        // loop's coroutine gets to return, so a drop in that instant would otherwise meet its own
        // "already retrying" guard and be swallowed, leaving a dead connection nobody is recovering.
        synchronized(reconnectLock) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        handleUnexpectedDisconnect(reason)
    }

    /** Makes [socket] the connection game sends go out on, unless [disconnect] has been called. */
    private fun adoptResumedSocket(socket: WebSocket): Boolean {
        if (intentionalDisconnect) {
            socket.cancel()
            return false
        }
        webSocket = socket
        return true
    }

    /** [viaSocket] is the socket the frame arrived on when it came from a reconnect attempt. */
    private fun handleIncoming(text: String, viaSocket: WebSocket? = null) {
        val msg = runCatching { json.decodeFromString(WireMessage.serializer(), text) }.getOrNull() ?: return
        when (msg.type) {
            "hosted" -> msg.roomCode?.let {
                msg.reconnectToken?.let { token -> reconnectToken = token }
                _roomCode.value = it
                onHostedListener?.invoke(it)
            }
            "joined" -> {
                val code = msg.roomCode ?: return
                val resuming = pendingReconnectResult
                if (resuming != null && !localIsSpectator) {
                    // A real seat is resumed with 'reconnected'. A fresh 'joined' means the relay has no
                    // such seat in this room: the code now names a DIFFERENT room (the relay restarted,
                    // or reaped ours and reused the code). It is not our room: keep our state, take no
                    // token or host id from it, and fail the attempt.
                    resuming.complete(false)
                    return
                }
                val existing = msg.players?.map { OnlinePlayer(it.playerId, it.displayName) } ?: emptyList()
                if (resuming != null && viaSocket != null && !adoptResumedSocket(viaSocket)) return
                msg.reconnectToken?.let { token -> reconnectToken = token }
                // An older relay doesn't say who the host is, but lists the room's creator first.
                if (role == OnlineRole.GUEST) hostPlayerId = msg.hostPlayerId ?: existing.firstOrNull()?.playerId
                _roomCode.value = code
                _connectedPlayers.value = existing
                onJoinedListener?.invoke(code, existing)
                // A spectator's re-join after a drop replies "joined" rather than "reconnected"
                // (see server/index.js) since it has no prior roster to diff against — still a
                // successful reconnect attempt if one is in flight.
                pendingReconnectResult?.complete(true)
            }
            "reconnected" -> {
                // Same shape as "joined" but for a real seat resuming after a drop — the relay
                // held it open rather than treating the disconnect as a departure. Re-request
                // full game state exactly like a fresh join does, since messages may have been
                // missed while disconnected (see MultiplayerTransport.onReconnected's KDoc).
                val code = msg.roomCode ?: return
                val resuming = pendingReconnectResult ?: return // only ever a reply to an attempt in flight
                val existing = msg.players?.map { OnlinePlayer(it.playerId, it.displayName) } ?: emptyList()
                // Publish the resumed socket BEFORE telling the game: its handler answers by sending.
                if (viaSocket != null && !adoptResumedSocket(viaSocket)) return
                _connectionError.value = null // any refusal from an earlier attempt is history now
                _roomCode.value = code
                _connectedPlayers.value = existing
                onReconnectedListener?.invoke()
                resuming.complete(true)
            }
            "playerJoined" -> {
                val playerId = msg.playerId ?: return
                val name = msg.displayName ?: playerId
                _connectedPlayers.update { current ->
                    if (current.any { it.playerId == playerId }) current else current + OnlinePlayer(playerId, name)
                }
                joinedListener?.invoke(playerId)
            }
            "playerLeft" -> {
                val playerId = msg.playerId ?: return
                _connectedPlayers.update { it.filterNot { p -> p.playerId == playerId } }
                leftListener?.invoke(playerId)
            }
            "playerDisconnected", "playerReconnected" -> {
                // A room member's connection dropped but the relay is holding their seat open
                // (or it just came back) — deliberately NOT onPlayerLeft/onPlayerJoined, since a
                // disconnect may well resolve itself before the grace period expires. No
                // "temporarily away" UI hook exists yet; a reasonable follow-up, not required
                // for the reconnect mechanism itself to work correctly.
            }
            "message" -> {
                val fromPlayerId = msg.fromPlayerId ?: return
                val payloadBase64 = msg.payloadBase64 ?: return
                val bytes = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrNull() ?: return
                rawMessageListener?.invoke(fromPlayerId, bytes)
                messageListener?.invoke(fromPlayerId, bytes)
            }
            "error" -> {
                _connectionError.value = msg.message ?: "Server error"
                pendingReconnectResult?.complete(false)
            }
        }
    }

    private fun sendMessageFrame(toPlayerId: String?, payload: ByteArray) {
        sendFrame(WireMessage(type = "message", toPlayerId = toPlayerId, payloadBase64 = Base64.getEncoder().encodeToString(payload)))
    }

    private fun sendFrame(msg: WireMessage) {
        webSocket?.send(json.encodeToString(WireMessage.serializer(), msg))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}

/**
 * One flexible envelope covering every message shape in server/index.js's protocol —
 * simpler than modeling each type as its own polymorphic subclass for a protocol this
 * small, at the cost of every field being nullable/type-specific. Field-by-field mapping
 * to the server's JSON is documented at the top of server/index.js; keep the two in sync.
 */
@Serializable
private data class WireMessage(
    val type: String,
    val playerId: String? = null,
    val displayName: String? = null,
    val roomCode: String? = null,
    val players: List<WirePlayer>? = null,
    val toPlayerId: String? = null,
    val fromPlayerId: String? = null,
    val payloadBase64: String? = null,
    val message: String? = null,
    val spectator: Boolean? = null,
    val reconnectToken: String? = null,
    val hostPlayerId: String? = null
)

@Serializable
private data class WirePlayer(val playerId: String, val displayName: String)
