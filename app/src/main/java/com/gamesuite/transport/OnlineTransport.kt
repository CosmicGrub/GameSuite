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
    private var webSocket: WebSocket? = null

    private var role: OnlineRole = OnlineRole.NONE
    var localPlayerId: String = ""
        private set
    private var localDisplayName: String = ""
    private var localIsSpectator: Boolean = false

    // Set true right before a deliberate close ([disconnect]) so the reconnect logic below
    // never tries to resurrect a connection the player (or the shell) actually meant to end.
    private var intentionalDisconnect = false
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

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

    /** Resolved by [handleIncoming] when a 'reconnected'/'joined'/'error' arrives while an
     *  [attemptReconnect] is in flight — kept separate from the general listeners above so a
     *  reconnect attempt's outcome doesn't depend on whatever the game layer's own
     *  onMessageReceived happens to do with the same frame. */
    private var pendingReconnectResult: CompletableDeferred<Boolean>? = null

    /** One reconnect attempt: opens a fresh socket and re-joins the same room under the same
     *  playerId. Routes every incoming frame through the SAME [handleIncoming] the normal
     *  connection uses (so a 'reconnected' correctly updates state and fires
     *  [onReconnectedListener], and any game traffic that happens to arrive immediately after
     *  is not silently dropped) rather than a narrower one-off listener. Returns true on a
     *  confirmed 'reconnected' (or 'joined', for a spectator's simpler re-entry). */
    private suspend fun attemptReconnect(code: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingReconnectResult = deferred
        val request = Request.Builder().url(serverUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    json.encodeToString(
                        WireMessage.serializer(),
                        WireMessage(type = "join", roomCode = code, playerId = localPlayerId, displayName = localDisplayName, spectator = localIsSpectator)
                    )
                )
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleIncoming(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.complete(false)
            }
            override fun onClosed(webSocket: WebSocket, closeCode: Int, reason: String) {
                if (closeCode != 1000) deferred.complete(false)
            }
        })
        val succeeded = withTimeoutOrNull(3_000) { deferred.await() } ?: false
        pendingReconnectResult = null
        return if (succeeded) {
            webSocket = socket
            true
        } else {
            socket.close(1000, "reconnect attempt failed")
            false
        }
    }

    private fun handleIncoming(text: String) {
        val msg = runCatching { json.decodeFromString(WireMessage.serializer(), text) }.getOrNull() ?: return
        when (msg.type) {
            "hosted" -> msg.roomCode?.let {
                _roomCode.value = it
                onHostedListener?.invoke(it)
            }
            "joined" -> {
                val code = msg.roomCode ?: return
                val existing = msg.players?.map { OnlinePlayer(it.playerId, it.displayName) } ?: emptyList()
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
                val existing = msg.players?.map { OnlinePlayer(it.playerId, it.displayName) } ?: emptyList()
                _roomCode.value = code
                _connectedPlayers.value = existing
                onReconnectedListener?.invoke()
                pendingReconnectResult?.complete(true)
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
    val spectator: Boolean? = null
)

@Serializable
private data class WirePlayer(val playerId: String, val displayName: String)
