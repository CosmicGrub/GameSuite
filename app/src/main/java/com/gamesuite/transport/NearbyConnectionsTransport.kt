package com.gamesuite.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Same-room ad-hoc multiplayer over Wi-Fi Direct/Bluetooth — no internet required. */
private const val SERVICE_ID = "com.gamesuite.NEARBY_SERVICE"

/** A discovered advertiser, shown in the guest's "join" list. */
data class DiscoveredHost(val endpointId: String, val displayName: String)

/** A connected endpoint, shown in the host's lobby roster before Start Game is tapped. */
data class ConnectedPeer(val endpointId: String, val displayName: String)

enum class NearbyRole { NONE, HOST, GUEST }

/**
 * Ad-hoc local multiplayer transport for two nearby devices (Wi-Fi Direct/Bluetooth,
 * `Strategy.P2P_CLUSTER`, no internet) — see docs/DEVICE_SPECIFIC_PLAN.md §2.
 *
 * This class has two layers:
 *  1. **Lobby layer** (host/guest-specific, not part of [MultiplayerTransport]):
 *     [startHosting]/[startDiscovery], [connectedPeers]/[discoveredHosts] for the lobby
 *     UI to render, and [rawMessages] for lobby-phase control messages (e.g. the host's
 *     "game starting, here's the roster" broadcast) — these are endpoint-id addressed,
 *     since no game-level `playerId` exists yet at this point.
 *  2. **Game layer** ([MultiplayerTransport] itself): `playerId`-addressed, per the
 *     interface every [com.gamesuite.core.GameModule] already talks to. This only works
 *     once the lobby has finished assigning player identities — call [setPlayerIdMapping]
 *     with the finalized endpointId→playerId map right before
 *     [com.gamesuite.core.GameSessionManager.launchGame], and everything a `GameModule`
 *     sends/receives through the interface methods is translated through it from there.
 *
 * Auto-accepts every incoming connection request with no manual PIN confirmation step —
 * a reasonable simplification for a casual local-multiplayer game (not a security-
 * sensitive pairing flow), matching this pass's scope. [ConnectionInfo.authenticationDigits]
 * is available if a future pass wants to surface it for user confirmation instead.
 */
class NearbyConnectionsTransport(context: Context) : MultiplayerTransport {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    private var role: NearbyRole = NearbyRole.NONE
    private var localDisplayName: String = ""

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val connectedPeers: StateFlow<List<ConnectedPeer>> = _connectedPeers.asStateFlow()

    /** Fires (endpointId, displayName) the moment a connection is confirmed either direction. */
    private var onConnectedListener: ((endpointId: String, displayName: String) -> Unit)? = null
    fun onConnected(listener: (endpointId: String, displayName: String) -> Unit) {
        onConnectedListener = listener
    }

    /** Raw endpoint-addressed payloads — for the lobby UI's own control messages, before [setPlayerIdMapping] is set. */
    private var rawMessageListener: ((endpointId: String, payload: ByteArray) -> Unit)? = null
    fun onRawMessageReceived(listener: (endpointId: String, payload: ByteArray) -> Unit) {
        rawMessageListener = listener
    }

    fun sendRaw(endpointId: String, payload: ByteArray) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
    }

    fun sendRawToAll(payload: ByteArray) {
        val endpointIds = _connectedPeers.value.map { it.endpointId }
        if (endpointIds.isNotEmpty()) connectionsClient.sendPayload(endpointIds, Payload.fromBytes(payload))
    }

    // Set once the lobby finalizes the roster, right before launchGame(). Empty (no-op
    // translation) during the lobby phase itself.
    private var endpointToPlayerId: Map<String, String> = emptyMap()
    private var playerIdToEndpoint: Map<String, String> = emptyMap()

    fun setPlayerIdMapping(endpointToPlayerId: Map<String, String>) {
        this.endpointToPlayerId = endpointToPlayerId
        this.playerIdToEndpoint = endpointToPlayerId.entries.associate { (endpointId, playerId) -> playerId to endpointId }
    }

    // ---- MultiplayerTransport (game layer) ----

    private var messageListener: ((String, ByteArray) -> Unit)? = null
    private var joinedListener: ((String) -> Unit)? = null
    private var leftListener: ((String) -> Unit)? = null

    /** No-op: advertising/discovery/connection is driven explicitly by the lobby UI, well before a GameModule exists to call this. */
    override fun connect() {}

    override fun disconnect() {
        connectionsClient.stopAllEndpoints()
        stopAdvertising()
        stopDiscovery()
        role = NearbyRole.NONE
        endpointToPlayerId = emptyMap()
        playerIdToEndpoint = emptyMap()
        _connectedPeers.value = emptyList()
        _discoveredHosts.value = emptyList()
    }

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        val nearbyPayload = Payload.fromBytes(payload)
        if (toPlayerId == null) {
            val endpointIds = playerIdToEndpoint.values.toList()
            if (endpointIds.isNotEmpty()) connectionsClient.sendPayload(endpointIds, nearbyPayload)
        } else {
            // Stale/unknown endpoint (post-disconnect) — drop rather than crash, per
            // DEVICE_SPECIFIC_PLAN.md §2's STATUS_ENDPOINT_UNKNOWN guard.
            val endpointId = playerIdToEndpoint[toPlayerId] ?: return
            connectionsClient.sendPayload(endpointId, nearbyPayload)
        }
    }

    override fun onMessageReceived(listener: (String, ByteArray) -> Unit) {
        messageListener = listener
    }

    override fun onPlayerJoined(listener: (String) -> Unit) {
        joinedListener = listener
    }

    override fun onPlayerLeft(listener: (String) -> Unit) {
        leftListener = listener
    }

    // ---- Lobby layer (host) ----

    fun startHosting(displayName: String) {
        role = NearbyRole.HOST
        localDisplayName = displayName
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(displayName, SERVICE_ID, connectionLifecycleCallback, options)
    }

    fun stopAdvertising() {
        if (role == NearbyRole.HOST) connectionsClient.stopAdvertising()
    }

    // ---- Lobby layer (guest) ----

    fun startDiscovery(displayName: String) {
        role = NearbyRole.GUEST
        localDisplayName = displayName
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    fun stopDiscovery() {
        if (role == NearbyRole.GUEST) connectionsClient.stopDiscovery()
    }

    fun requestConnectionTo(endpointId: String) {
        connectionsClient.requestConnection(localDisplayName, endpointId, connectionLifecycleCallback)
    }

    // ---- Nearby callbacks ----

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) return
            // Discovered-endpoint bookkeeping only applies on the guest side, but calling
            // this on the host is harmless (the id just won't be present).
            val displayName = _discoveredHosts.value.firstOrNull { it.endpointId == endpointId }?.displayName
                ?: endpointId
            _connectedPeers.update { current ->
                if (current.any { it.endpointId == endpointId }) current
                else current + ConnectedPeer(endpointId, displayName)
            }
            onConnectedListener?.invoke(endpointId, displayName)
            endpointToPlayerId[endpointId]?.let { joinedListener?.invoke(it) }
        }

        override fun onDisconnected(endpointId: String) {
            _connectedPeers.update { it.filterNot { peer -> peer.endpointId == endpointId } }
            endpointToPlayerId[endpointId]?.let { leftListener?.invoke(it) }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            _discoveredHosts.update { current ->
                if (current.any { it.endpointId == endpointId }) current
                else current + DiscoveredHost(endpointId, info.endpointName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredHosts.update { it.filterNot { host -> host.endpointId == endpointId } }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            rawMessageListener?.invoke(endpointId, bytes)
            endpointToPlayerId[endpointId]?.let { playerId -> messageListener?.invoke(playerId, bytes) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Bytes payloads complete in a single SUCCESS update — nothing to track for
            // progress here (relevant for FILE/STREAM payloads, unused by this app).
        }
    }
}
