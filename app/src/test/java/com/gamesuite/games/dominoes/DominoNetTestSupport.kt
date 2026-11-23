package com.gamesuite.games.dominoes

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Dominoes host-authoritative networking tests. Not a test class itself.
 *
 * [DomFakeTransport] records every send verbatim and exposes the listeners the game registered so a
 * test can inject inbound traffic like a real transport would. [DomHub] wires endpoints together the
 * way a relay does: the receiver is told the REAL sender (the endpoint), never the claimed id.
 */
class DomFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: DominoNetMessage
            get() = Json.decodeFromString(DominoNetMessage.serializer(), String(payload, Charsets.UTF_8))
        val stateSync: DominoNetMessage.StateSync get() = message as DominoNetMessage.StateSync
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: DomFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, message: DominoNetMessage) =
        inject(fromPlayerId, DomTestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class DomHub {
    val endpoints = linkedMapOf<String, DomFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): DomFakeTransport {
        val t = DomFakeTransport()
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

class DomNode(val game: DominoGame, val transport: DomFakeTransport) {
    val results = mutableListOf<GameResult>()
    val state: DominoState get() = game.state.value!!
}

object DomTestKit {
    val roster = listOf(
        PlayerInfo(playerId = "p0", displayName = "Host"),
        PlayerInfo(playerId = "p1", displayName = "Guest")
    )

    fun encode(message: DominoNetMessage): ByteArray =
        Json.encodeToString(DominoNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: DomFakeTransport = DomFakeTransport(),
        start: Boolean = false,
        set: DominoSet = DominoSet.DOUBLE_SIX
    ): DomNode {
        val game = DominoGame()
        val node = DomNode(game, transport)
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = localIndex, transport = transport))
        if (localIndex == 0) game.tileSet = set // a guest learns the set from the host's sync
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    /** The canonical double-six tile with id [id] (ids run 0-0, 0-1, ... 6-6). */
    fun d(id: Int, set: DominoSet = DominoSet.DOUBLE_SIX): Domino = set.fullSet()[id]

    /** The canonical id of the double-six tile a-b (a <= b). */
    fun idOf(a: Int, b: Int, set: DominoSet = DominoSet.DOUBLE_SIX): Int =
        set.fullSet().first { it.a == minOf(a, b) && it.b == maxOf(a, b) }.instanceId

    fun t(a: Int, b: Int, set: DominoSet = DominoSet.DOUBLE_SIX): Domino = d(idOf(a, b, set), set)

    fun netTile(d: Domino) = DominoNetTile(d.a, d.b, d.instanceId)

    /**
     * A well-formed sync payload for the GUEST (seat 1): [myHandIds] its own tiles, [oppCount] the
     * host's hand size, [chain] (tile id, flipped) in left-to-right order. The boneyard count is
     * whatever is left of the 28 tiles.
     */
    fun net(
        myHandIds: List<Int> = listOf(1, 2, 3),
        oppCount: Int = 3,
        chain: List<Pair<Int, Boolean>> = emptyList(),
        current: Int = 1,
        handOver: Boolean = false,
        winner: String? = null,
        scores: Map<String, Int> = mapOf("p0" to 0, "p1" to 0),
        handPips: List<Int>? = null,
        lastAction: String = "test",
        handsFinished: Int = 0,
        maxPip: Int = 6
    ): DominoNetState {
        val set = DominoSet.fromMaxPip(maxPip)!!
        val boneyard = set.tileCount - chain.size - myHandIds.size - oppCount
        return DominoNetState(
            maxPip = maxPip,
            chain = chain.map { (id, flipped) -> DominoNetPlaced(netTile(d(id, set)), flipped) },
            myHand = myHandIds.map { netTile(d(it, set)) },
            handSizes = listOf(oppCount, myHandIds.size),
            boneyardSize = boneyard,
            currentPlayerIndex = current,
            consecutivePasses = 0,
            lastAction = lastAction,
            handOver = handOver,
            winnerPlayerId = winner,
            sessionScores = scores,
            handsFinished = handsFinished,
            handPips = handPips
        )
    }

    /** A host + guest pair joined through a [DomHub]; the host deals first, the guest then asks for state. */
    class Pair2(val hub: DomHub, val host: DomNode, val guest: DomNode) {
        fun engineFor(seat: Int): DominoGame = if (seat == 0) host.game else guest.game

        /**
         * ONE action for whichever seat is on turn, on that seat's OWN engine (the guest through its
         * mirrored view, so its tap becomes a request): play the first legal tile, else draw, else pass.
         */
        fun step() {
            val seat = host.state.currentPlayerIndex
            val g = engineFor(seat)
            val view = g.state.value!!
            val hand = view.players[seat].hand
            val tile = hand.firstOrNull { g.canPlace(it, true) || g.canPlace(it, false) }
            when {
                tile != null -> g.playDomino(seat, tile, g.canPlace(tile, true))
                view.boneyardSize > 0 -> g.drawFromBoneyard(seat)
                else -> g.pass(seat)
            }
        }

        fun stepUntil(maxSteps: Int = 600, done: () -> Boolean) {
            var n = 0
            while (!done() && n++ < maxSteps) step()
        }
    }

    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC, set: DominoSet = DominoSet.DOUBLE_SIX): Pair2 {
        val hub = DomHub()
        val hostT = hub.endpoint("p0")
        val guestT = hub.endpoint("p1")
        val host = node(0, mode, hostT, start = true, set = set) // broadcast lands on a guest that is not listening yet
        val guest = node(1, mode, guestT)                        // init asks the host for the state
        return Pair2(hub, host, guest)
    }
}
