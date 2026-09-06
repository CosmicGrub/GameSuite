package com.gamesuite.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

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

    // ---- Lobby layer ----

    /** Opens the socket and asks the server to create a fresh room. [onHosted] fires with the room code once confirmed. */
    fun hostRoom(playerId: String, displayName: String) {
        role = OnlineRole.HOST
        localPlayerId = playerId
        openSocket {
            sendFrame(WireMessage(type = "host", playerId = playerId, displayName = displayName))
        }
    }

    /** Opens the socket and asks to join an existing room. [onJoined] fires with the pre-existing roster once confirmed. */
    fun joinRoom(code: String, playerId: String, displayName: String) {
        role = OnlineRole.GUEST
        localPlayerId = playerId
        openSocket {
            sendFrame(WireMessage(type = "join", roomCode = code, playerId = playerId, displayName = displayName))
        }
    }

    private fun openSocket(onOpenSendInitialFrame: () -> Unit) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpenSendInitialFrame()

            override fun onMessage(webSocket: WebSocket, text: String) = handleIncoming(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionError.value = t.message ?: "Connection failed"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) _connectionError.value = "Disconnected: $reason"
            }
        })
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
            "message" -> {
                val fromPlayerId = msg.fromPlayerId ?: return
                val payloadBase64 = msg.payloadBase64 ?: return
                val bytes = runCatching { Base64.getDecoder().decode(payloadBase64) }.getOrNull() ?: return
                rawMessageListener?.invoke(fromPlayerId, bytes)
                messageListener?.invoke(fromPlayerId, bytes)
            }
            "error" -> _connectionError.value = msg.message ?: "Server error"
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
    val message: String? = null
)

@Serializable
private data class WirePlayer(val playerId: String, val displayName: String)
