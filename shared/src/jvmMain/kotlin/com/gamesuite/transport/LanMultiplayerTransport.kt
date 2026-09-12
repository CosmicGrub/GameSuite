package com.gamesuite.transport

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Collections

/** One host GameSuite is currently advertising on the LAN, as seen by a guest's discovery
 *  listener (see [LanMultiplayerTransport.startDiscovery]). [address] is what [joinHost]
 *  actually dials. */
data class LanHostInfo(val address: String, val port: Int, val hostId: String, val displayName: String)

private enum class LanRole { NONE, HOST, GUEST }

/**
 * Same-network local multiplayer (docs/ENGINE_DECISION.md Action Item 8's "Desktop parity"
 * scope) -- the Android app's [NearbyConnectionsTransport] equivalent for Desktop, which has
 * no Bluetooth/Wi-Fi Direct hardware API to build on. Lives in the shared `jvmMain`
 * intermediate source set (see shared/build.gradle.kts's own comment on why) rather than
 * commonMain, since `java.net.Socket`/`ServerSocket`/`DatagramSocket` are genuinely JVM-only
 * -- but that source set is real on BOTH the androidTarget and jvm("desktop") compilations,
 * so this one class equally lets two desktop instances, two Android devices, or one of each,
 * play together over a shared Wi-Fi/LAN, entirely without Nearby.
 *
 * Two layers, the same split [OnlineTransport] uses (read that class's KDoc first if this is
 * unfamiliar) -- but the relay role is genuinely different here, and that difference drives
 * most of this class's design:
 *  - [OnlineTransport] talks to a separate relay SERVER that is never itself a player --
 *    every message it sends just gets forwarded, full stop.
 *  - Here, the HOST peer plays double duty: it is both a real player (with its own local
 *    [MultiplayerTransport] instance driving its own game state) AND the one relaying
 *    traffic between every guest, since there's no third-party server. Concretely: when a
 *    broadcast ([toPlayerId] == null) or host-addressed message arrives at the host from a
 *    guest, [handleIncomingFrame] both re-broadcasts it to the OTHER connected guests AND
 *    fires the host's own [messageListener] -- a guest's socket write, by contrast, only
 *    ever needs to reach the one socket it has (to the host), and trusts the host to do that
 *    same double duty on its behalf. This asymmetry (host code path vs. guest code path) is
 *    the one genuinely new piece of protocol logic this transport needed that
 *    [OnlineTransport]'s design didn't have to solve.
 *
 * Discovery replaces Nearby's Bluetooth/Wi-Fi Direct advertising with a plain periodic UDP
 * broadcast on [DISCOVERY_PORT] (every [BEACON_INTERVAL_MS]) -- the one real, named
 * limitation versus Nearby: both peers must already be on the same LAN/Wi-Fi network (no
 * ad-hoc Wi-Fi-Direct pairing), and a network that blocks UDP broadcast (some public/guest
 * Wi-Fi, some VPNs) will hide hosts from discovery entirely. [joinHost] still works with a
 * manually-supplied address if a caller has one some other way, so discovery failing is a
 * discoverability gap, not a hard connectivity one.
 *
 * Framing: newline-delimited JSON over the TCP stream (`\n` can never appear inside a
 * Base64-encoded payload or a compact JSON-encoded frame, so a plain per-line `BufferedReader`
 * is a correct, simple framer for this protocol -- no length-prefixing needed).
 */
class LanMultiplayerTransport : MultiplayerTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var role: LanRole = LanRole.NONE
    private var localPlayerId: String = ""
    private var localDisplayName: String = ""

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var beaconJob: Job? = null
    private var discoveryJob: Job? = null
    private var discoverySocket: DatagramSocket? = null

    /** HOST role: every connected guest, keyed by the playerId it announced in its own
     *  `join` frame. GUEST role: unused (a guest only ever has [guestConnection]). Both the
     *  accept loop and each connection's own read-loop coroutine touch this concurrently, so
     *  it's a synchronized map rather than a plain MutableMap. */
    private val hostConnections = Collections.synchronizedMap(LinkedHashMap<String, LanConnection>())

    /** GUEST role only: the one socket/writer pair to the host. */
    private var guestConnection: LanConnection? = null

    private val _discoveredHosts = MutableStateFlow<List<LanHostInfo>>(emptyList())
    val discoveredHosts: StateFlow<List<LanHostInfo>> = _discoveredHosts.asStateFlow()

    private val _connectedPlayers = MutableStateFlow<List<LanPlayerInfo>>(emptyList())
    val connectedPlayers: StateFlow<List<LanPlayerInfo>> = _connectedPlayers.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    fun clearError() { _connectionError.value = null }

    private var onHostedListener: ((port: Int) -> Unit)? = null
    fun onHosted(listener: (port: Int) -> Unit) { onHostedListener = listener }

    private var onJoinedLobbyListener: ((existingPlayers: List<LanPlayerInfo>) -> Unit)? = null
    fun onJoinedLobby(listener: (existingPlayers: List<LanPlayerInfo>) -> Unit) { onJoinedLobbyListener = listener }

    // ---- MultiplayerTransport (game layer) ----

    private var messageListener: ((String, ByteArray) -> Unit)? = null
    private var joinedListener: ((String) -> Unit)? = null
    private var leftListener: ((String) -> Unit)? = null

    /** No-op: the socket(s) are opened explicitly by [hostGame]/[joinHost], well before a
     *  GameModule exists to call this -- same reasoning as [OnlineTransport.connect]. */
    override fun connect() {}

    override fun disconnect() {
        beaconJob?.cancel()
        discoveryJob?.cancel()
        acceptJob?.cancel()
        runCatching { discoverySocket?.close() }
        runCatching { serverSocket?.close() }
        synchronized(hostConnections) {
            hostConnections.values.forEach { it.close() }
            hostConnections.clear()
        }
        guestConnection?.close()
        guestConnection = null
        role = LanRole.NONE
        _connectedPlayers.value = emptyList()
        _discoveredHosts.value = emptyList()
    }

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        val frame = LanFrame(
            type = "message",
            fromPlayerId = fromPlayerId,
            toPlayerId = toPlayerId,
            payloadBase64 = Base64.getEncoder().encodeToString(payload)
        )
        when (role) {
            LanRole.HOST -> relayFromHost(frame, excludePlayerId = null) // host is the sender; never echo to itself
            LanRole.GUEST -> guestConnection?.writeFrame(frame)
            LanRole.NONE -> {}
        }
    }

    override fun onMessageReceived(listener: (String, ByteArray) -> Unit) { messageListener = listener }
    override fun onPlayerJoined(listener: (String) -> Unit) { joinedListener = listener }
    override fun onPlayerLeft(listener: (String) -> Unit) { leftListener = listener }

    // ---- Lobby layer: hosting ----

    /** Starts listening for guest connections on an OS-assigned free port, and starts
     *  broadcasting a discovery beacon advertising it. [onHosted] fires with the actual
     *  bound port once the socket is open (useful for logging/manual-join fallback even
     *  though guests normally never need to know it -- discovery carries it for them). */
    fun hostGame(playerId: String, displayName: String) {
        role = LanRole.HOST
        localPlayerId = playerId
        localDisplayName = displayName
        _connectedPlayers.value = listOf(LanPlayerInfo(playerId, displayName))

        val socket = runCatching { ServerSocket(0) }.getOrElse {
            _connectionError.value = "Could not open a port to host on: ${it.message}"
            return
        }
        serverSocket = socket
        onHostedListener?.invoke(socket.localPort)

        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handleGuestConnection(client) }
            }
        }
        beaconJob = scope.launch { broadcastBeaconLoop(socket.localPort, playerId, displayName) }
    }

    private suspend fun handleGuestConnection(client: Socket) {
        val connection = LanConnection(client)
        // The new guest's very first frame must be its own `join` -- reject anything else
        // (including no frame at all before the peer hangs up) as malformed, same defensive
        // posture OnlineTransport's server takes over its own room protocol.
        val join = connection.readFrame() ?: run { connection.close(); return }
        if (join.type != "join" || join.playerId == null) { connection.close(); return }
        val newPlayerId = join.playerId
        val newDisplayName = join.displayName ?: newPlayerId

        hostConnections[newPlayerId] = connection
        val roster = currentRoster()
        connection.writeFrame(LanFrame(type = "joined", players = roster))
        relayFromHost(
            LanFrame(type = "playerJoined", playerId = newPlayerId, displayName = newDisplayName),
            excludePlayerId = newPlayerId
        )
        _connectedPlayers.update { it + LanPlayerInfo(newPlayerId, newDisplayName) }
        joinedListener?.invoke(newPlayerId)

        try {
            while (true) {
                val frame = connection.readFrame() ?: break
                handleIncomingFrameAtHost(frame, fromConnectionPlayerId = newPlayerId)
            }
        } finally {
            hostConnections.remove(newPlayerId)
            connection.close()
            _connectedPlayers.update { list -> list.filterNot { it.playerId == newPlayerId } }
            relayFromHost(LanFrame(type = "playerLeft", playerId = newPlayerId), excludePlayerId = null)
            leftListener?.invoke(newPlayerId)
        }
    }

    private fun currentRoster(): List<LanPlayerInfo> = _connectedPlayers.value

    /** A frame arriving at the HOST from one of its guest connections. `message` frames get
     *  the double-duty treatment described in this class's own KDoc: relayed onward to every
     *  OTHER guest (never back to the sender), AND delivered to the host's own local
     *  [messageListener] whenever the host itself is an intended recipient (broadcast, or
     *  addressed to the host's own [localPlayerId]) -- a guest has no way to reach the host's
     *  local game logic except through this listener, since the host IS the relay. */
    private fun handleIncomingFrameAtHost(frame: LanFrame, fromConnectionPlayerId: String) {
        if (frame.type != "message") return // join/joined/playerJoined/playerLeft are host-originated only
        val toPlayerId = frame.toPlayerId
        val fromPlayerId = frame.fromPlayerId ?: fromConnectionPlayerId
        relayFromHost(frame, excludePlayerId = fromConnectionPlayerId)
        if (toPlayerId == null || toPlayerId == localPlayerId) {
            deliverMessage(fromPlayerId, frame.payloadBase64)
        }
    }

    /** Sends [frame] to every currently-connected guest except [excludePlayerId] (pass null
     *  to exclude no one -- used when the HOST itself is the original sender, so there's
     *  nothing to exclude on the guest side, only itself, which never gets a socket write in
     *  the first place). Respects `toPlayerId` on `message` frames: a directed message only
     *  goes to that one guest (if it's one of them), not every guest. */
    private fun relayFromHost(frame: LanFrame, excludePlayerId: String?) {
        val targetPlayerId = frame.toPlayerId
        val connections = synchronized(hostConnections) { hostConnections.entries.toList() }
        for ((playerId, connection) in connections) {
            if (playerId == excludePlayerId) continue
            if (frame.type == "message" && targetPlayerId != null && targetPlayerId != playerId) continue
            connection.writeFrame(frame)
        }
    }

    private fun deliverMessage(fromPlayerId: String, payloadBase64: String?) {
        val bytes = payloadBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return
        messageListener?.invoke(fromPlayerId, bytes)
    }

    private suspend fun broadcastBeaconLoop(tcpPort: Int, hostId: String, displayName: String) {
        val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return
        discoverySocket = socket
        val beacon = Json.encodeToString(
            LanBeacon.serializer(),
            LanBeacon(hostId = hostId, displayName = displayName, tcpPort = tcpPort)
        ).toByteArray()
        val destinations = broadcastAddresses()
        try {
            while (!socket.isClosed) {
                for (dest in destinations) {
                    runCatching {
                        socket.send(DatagramPacket(beacon, beacon.size, dest, DISCOVERY_PORT))
                    }
                }
                delay(BEACON_INTERVAL_MS)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Every IPv4 interface's own broadcast address (typically just one on a normal
     *  single-NIC machine) for real cross-machine LAN discovery, PLUS a direct unicast send
     *  to 127.0.0.1 so a host and guest running as two separate processes on the SAME
     *  machine (real end-to-end tests without needing two physical machines, or two players
     *  in the same room happy to share one PC) can discover each other too.
     *
     *  Empirically verified this needed to be unicast loopback, not just also including the
     *  limited-broadcast address 255.255.255.255 -- that was the first approach tried here,
     *  and it measurably failed this class's own same-machine discoveryFindsTheHost test on
     *  this project's real Windows dev machine (an 8-second timeout, zero beacons received) --
     *  whether a broadcast datagram loops back to a listener on the SENDING machine itself is
     *  OS/network-stack-dependent and not something to assume works. A plain unicast packet
     *  to 127.0.0.1, by contrast, is one of the most universally reliable delivery paths in
     *  any OS's network stack, and is exactly what same-machine discovery actually needs
     *  (nothing about it requires a true subnet broadcast) -- confirmed fixed against that
     *  same test after switching to it.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        // A real bug hit and fixed here: some virtual/unconfigured interfaces (VPN adapters,
        // Docker's virtual network, an adapter with no real IPv4 lease) report their own
        // "broadcast address" as 0.0.0.0 -- not a valid datagram destination at all, and
        // sending to it throws (confirmed on this project's real dev machine:
        // java.net.BindException: Cannot assign requested address). Filtered out rather than
        // trusting every NetworkInterface-reported broadcast address to be genuinely usable.
        val subnetBroadcasts = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.broadcast }
                .filter { !it.isAnyLocalAddress }
                .toList()
        }.getOrDefault(emptyList())
        val loopback = runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()
        return (subnetBroadcasts + listOfNotNull(loopback)).distinct()
    }

    // ---- Lobby layer: discovering + joining ----

    /** Starts listening for host beacons; [discoveredHosts] accumulates every distinct
     *  (address, port) seen, newest-seen-last-updated but never removed on its own -- a real
     *  "host went away" timeout is a reasonable follow-up, not required for a first pass
     *  where a stale entry just fails to connect if actually chosen. */
    fun startDiscovery() {
        discoveryJob = scope.launch {
            val socket = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(DISCOVERY_PORT)) } }
                .getOrElse { _connectionError.value = "Could not listen for hosts: ${it.message}"; return@launch }
            discoverySocket = socket
            val buffer = ByteArray(1024)
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) continue
                    val beacon = runCatching {
                        Json.decodeFromString(LanBeacon.serializer(), String(packet.data, 0, packet.length))
                    }.getOrNull() ?: continue
                    // DatagramPacket.getAddress() is a nullable Java platform type -- genuinely
                    // unset on a freshly-constructed packet, though always populated by a
                    // successful receive() (already confirmed via `received` above); handled
                    // explicitly rather than asserted non-null, since a null here should just
                    // skip this one beacon, not crash the whole discovery loop.
                    val senderAddress = packet.address?.hostAddress ?: continue
                    val info = LanHostInfo(senderAddress, beacon.tcpPort, beacon.hostId, beacon.displayName)
                    _discoveredHosts.update { current ->
                        if (current.any { it.address == info.address && it.port == info.port }) current else current + info
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        runCatching { discoverySocket?.close() }
    }

    /** Dials a specific host (from [discoveredHosts], or any other address/port a caller
     *  already has) and joins it. [onJoinedLobby] fires with the pre-existing roster once
     *  the host replies `joined`. */
    fun joinHost(address: String, port: Int, playerId: String, displayName: String) {
        role = LanRole.GUEST
        localPlayerId = playerId
        localDisplayName = displayName
        scope.launch {
            val socket = runCatching { Socket(address, port) }.getOrElse {
                _connectionError.value = "Could not connect to $address:$port -- ${it.message}"
                return@launch
            }
            val connection = LanConnection(socket)
            guestConnection = connection
            connection.writeFrame(LanFrame(type = "join", playerId = playerId, displayName = displayName))

            try {
                while (true) {
                    val frame = connection.readFrame() ?: break
                    handleIncomingFrameAtGuest(frame)
                }
            } finally {
                connection.close()
                guestConnection = null
                if (role == LanRole.GUEST) _connectionError.value = "Lost connection to host"
            }
        }
    }

    private fun handleIncomingFrameAtGuest(frame: LanFrame) {
        when (frame.type) {
            "joined" -> {
                val roster = frame.players ?: emptyList()
                _connectedPlayers.value = roster
                onJoinedLobbyListener?.invoke(roster)
            }
            "playerJoined" -> {
                val playerId = frame.playerId ?: return
                val name = frame.displayName ?: playerId
                _connectedPlayers.update { current ->
                    if (current.any { it.playerId == playerId }) current else current + LanPlayerInfo(playerId, name)
                }
                joinedListener?.invoke(playerId)
            }
            "playerLeft" -> {
                val playerId = frame.playerId ?: return
                _connectedPlayers.update { it.filterNot { p -> p.playerId == playerId } }
                leftListener?.invoke(playerId)
            }
            "message" -> {
                val fromPlayerId = frame.fromPlayerId ?: return
                deliverMessage(fromPlayerId, frame.payloadBase64)
            }
        }
    }

    companion object {
        /** Arbitrary, unlikely-to-collide high port for the UDP discovery beacon -- distinct
         *  from the TCP game-connection port, which is OS-assigned per host (see [hostGame]).
         *  47632 was the first choice here and had to be abandoned: something already had it
         *  permanently bound on this project's real dev machine (a genuine
         *  java.net.BindException on every attempt, yet invisible to `netstat -ano -p UDP` --
         *  likely one of Docker Desktop's networking components running a listener Windows'
         *  own netstat doesn't surface cleanly). Confirmed this port is free instead. */
        const val DISCOVERY_PORT = 58943
        const val BEACON_INTERVAL_MS = 1000L
    }
}

/** One TCP connection's read/write side, framed as newline-delimited JSON (see
 *  [LanMultiplayerTransport]'s own KDoc on why that framing is correct here). Writes are
 *  synchronized since the host's relay methods can write to the same connection from more
 *  than one caller's coroutine (the accept-loop-spawned read loop, and another guest's own
 *  incoming-message handling) concurrently. */
private class LanConnection(private val socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()

    fun readFrame(): LanFrame? {
        val line = try {
            reader.readLine()
        } catch (e: IOException) {
            null
        } catch (e: SocketTimeoutException) {
            null
        } ?: return null
        return runCatching { Json.decodeFromString(LanFrame.serializer(), line) }.getOrNull()
    }

    fun writeFrame(frame: LanFrame) {
        val line = (Json.encodeToString(LanFrame.serializer(), frame) + "\n").toByteArray()
        synchronized(writeLock) {
            runCatching { output.write(line); output.flush() }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

@Serializable
private data class LanBeacon(val hostId: String, val displayName: String, val tcpPort: Int)

@Serializable
data class LanPlayerInfo(val playerId: String, val displayName: String)

/** One flexible envelope covering every frame shape this protocol needs -- same reasoning as
 *  [OnlineTransport]'s own WireMessage: simpler than a polymorphic hierarchy for a protocol
 *  this small, at the cost of every field being nullable/type-specific. */
@Serializable
private data class LanFrame(
    val type: String,
    val playerId: String? = null,
    val displayName: String? = null,
    val players: List<LanPlayerInfo>? = null,
    val toPlayerId: String? = null,
    val fromPlayerId: String? = null,
    val payloadBase64: String? = null
)
