package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Dots and Boxes host-authoritative networking tests (not a test class).
 * [DotsFakeTransport] records every send and exposes the listeners the game registered so a test
 * can inject inbound traffic the way a real transport would; [DotsHub] wires endpoints together
 * and always tells the receiver the REAL sender (the endpoint the bytes came from).
 */
class DotsFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: DotsAndBoxesNetMessage
            get() = Json.decodeFromString<DotsAndBoxesNetMessage>(String(payload, Charsets.UTF_8))
        val stateSync: DotsAndBoxesNetMessage.StateSync get() = message as DotsAndBoxesNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: DotsFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

    override fun connect() {}
    override fun disconnect() {}

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Sent(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }

    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, message: DotsAndBoxesNetMessage) = inject(fromPlayerId, DotsTestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class DotsHub {
    val endpoints = linkedMapOf<String, DotsFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): DotsFakeTransport {
        val t = DotsFakeTransport()
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

class DotsNode(val game: DotsAndBoxesGame, val transport: DotsFakeTransport)

object DotsTestKit {
    fun encode(message: DotsAndBoxesNetMessage): ByteArray =
        Json.encodeToString<DotsAndBoxesNetMessage>(message).toByteArray(Charsets.UTF_8)

    val roster = listOf(PlayerInfo(playerId = "p0", displayName = "Host"), PlayerInfo(playerId = "p1", displayName = "Guest"))

    fun node(
        seat: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: DotsFakeTransport = DotsFakeTransport(),
        start: Boolean = false
    ): DotsNode {
        val game = DotsAndBoxesGame()
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = seat, transport = transport))
        if (start) game.startMatch()
        return DotsNode(game, transport)
    }

    /** A host and a guest wired through a hub; the host deals after both are listening. */
    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Triple<DotsNode, DotsNode, DotsHub> {
        val hub = DotsHub()
        val host = node(0, mode, hub.endpoint("p0"))
        val guest = node(1, mode, hub.endpoint("p1"))
        host.game.startMatch()
        return Triple(host, guest, hub)
    }

    /** First undrawn edge in the engine's own scan order (horizontals, then verticals). */
    fun firstUndrawn(s: DotsAndBoxesState): Triple<Boolean, Int, Int>? {
        for (r in 0..s.boxRows) for (c in 0 until s.boxCols) if (!s.horizontalEdges[r * s.boxCols + c]) return Triple(true, r, c)
        for (r in 0 until s.boxRows) for (c in 0..s.boxCols) if (!s.verticalEdges[r * (s.boxCols + 1) + c]) return Triple(false, r, c)
        return null
    }

    /** Taps [edge] on [node] the way the screen does. */
    fun tap(node: DotsNode, edge: Triple<Boolean, Int, Int>) {
        if (edge.first) node.game.drawHorizontalEdge(edge.second, edge.third) else node.game.drawVerticalEdge(edge.second, edge.third)
    }

    /** Plays the board to the end by always tapping the first undrawn edge on whichever device owns the turn. */
    fun playOut(host: DotsNode, guest: DotsNode) {
        var guard = 0
        while (guard++ < 200) {
            val s = host.game.state.value ?: return
            if (s.boardOver) return
            val edge = firstUndrawn(s) ?: return
            tap(if (s.currentPlayerIndex == 0) host else guest, edge)
        }
        throw AssertionError("board did not finish")
    }

    /** The comparable part of a state: everything except the one-shot claim animation event. */
    fun essence(s: DotsAndBoxesState?): DotsAndBoxesState? = s?.copy(lastBoxCompleted = null)
}
