package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Connect Four host-authoritative networking tests. Not a test class itself.
 *
 * [C4FakeTransport] records every send verbatim and exposes the listeners the game registered so a
 * test can inject inbound traffic like a real transport would. [C4Hub] wires endpoints together the
 * way a relay does: the receiver is told the REAL sender (the endpoint), never the claimed id.
 */
class C4FakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: ConnectFourNetMessage
            get() = Json.decodeFromString(ConnectFourNetMessage.serializer(), String(payload, Charsets.UTF_8))
        val stateSync: ConnectFourNetMessage.StateSync get() = message as ConnectFourNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: C4FakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

    override fun connect() {}
    override fun disconnect() {}

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Sent(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }

    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, message: ConnectFourNetMessage) =
        inject(fromPlayerId, C4TestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class C4Hub {
    val endpoints = linkedMapOf<String, C4FakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): C4FakeTransport {
        val t = C4FakeTransport()
        endpoints[playerId] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "endpoint $owner claimed to be $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { it.inject(owner, payload) }
        }
        return t
    }
}

class C4Node(val game: ConnectFourGame, val transport: C4FakeTransport) {
    val results = mutableListOf<GameResult>()
    val cells: List<Int?> get() = game.state.value!!.cells
}

object C4TestKit {
    val roster = listOf(
        PlayerInfo(playerId = "p0", displayName = "Host"),
        PlayerInfo(playerId = "p1", displayName = "Guest")
    )

    fun encode(message: ConnectFourNetMessage): ByteArray =
        Json.encodeToString(ConnectFourNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: C4FakeTransport = C4FakeTransport(),
        start: Boolean = false
    ): C4Node {
        val game = ConnectFourGame()
        val node = C4Node(game, transport)
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = localIndex, transport = transport))
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    /** A well-formed sync payload for a guest: [cells] row-major 6x7, [current] on turn. */
    fun net(
        cells: List<Int?> = List(42) { null },
        current: Int = 1,
        boardOver: Boolean = false,
        winnerPlayerId: String? = null,
        winningLine: List<Int>? = null,
        wins: Map<String, Int> = mapOf("p0" to 0, "p1" to 0),
        draws: Int = 0,
        lastAction: String = "test"
    ) = ConnectFourNetState(cells, current, lastAction, boardOver, winnerPlayerId, winningLine, wins, draws)

    fun cellsWith(vararg placed: Pair<Int, Int>): List<Int?> {
        val c = MutableList<Int?>(42) { null }
        placed.forEach { (index, owner) -> c[index] = owner }
        return c
    }

    /** A host + guest pair joined through a [C4Hub]; the host deals first, the guest then asks for state. */
    class Pair2(val hub: C4Hub, val host: C4Node, val guest: C4Node) {
        /** Plays [col] for whichever seat is on turn, on that seat's own engine. */
        fun move(col: Int) {
            val s = host.game.state.value!!
            if (s.currentPlayerIndex == 0) host.game.dropDisc(col) else guest.game.dropDisc(col)
        }
    }

    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Pair2 {
        val hub = C4Hub()
        val hostT = hub.endpoint("p0")
        val guestT = hub.endpoint("p1")
        val host = node(0, mode, hostT, start = true) // broadcast lands on a guest that is not listening yet
        val guest = node(1, mode, guestT)             // init asks the host for the state
        return Pair2(hub, host, guest)
    }
}
