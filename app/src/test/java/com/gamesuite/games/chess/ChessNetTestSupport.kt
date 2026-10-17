package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Chess host-authoritative networking tests (same idea as the UNO tests'
 * UnoNetTestSupport, copied rather than shared). Not a test class itself.
 *
 * [ChessFakeTransport] records every send verbatim and exposes the listeners ChessGame registered
 * so a test can inject inbound traffic like a real transport. [ChessHub] wires endpoints together
 * the way a relay does: the receiver is told the REAL sender (the endpoint the bytes came from),
 * never the fromPlayerId the sender claimed.
 */
class ChessFakeTransport : MultiplayerTransport {
    /** Plain class (not data): a ByteArray member would give a data class identity equals. */
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: ChessNetMessage get() = Json.decodeFromString<ChessNetMessage>(String(payload, Charsets.UTF_8))
        val stateSync: ChessNetMessage.StateSync get() = message as ChessNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    /** Set by [ChessHub]; when non-null every send is also routed through it. */
    var router: ((source: ChessFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    /** Simulates an inbound message from [fromPlayerId] (the transport-level real sender). */
    fun inject(fromPlayerId: String, message: ChessNetMessage) =
        inject(fromPlayerId, ChessNetKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class ChessHub {
    val endpoints = linkedMapOf<String, ChessFakeTransport>()
    val senderLies = mutableListOf<String>()
    val dropTo = mutableSetOf<String>()

    fun endpoint(playerId: String): ChessFakeTransport {
        val t = ChessFakeTransport()
        endpoints[playerId] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "endpoint $owner claimed to be $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { target ->
                val targetId = endpoints.entries.first { it.value === target }.key
                if (targetId !in dropTo) target.inject(owner, payload)
            }
        }
        return t
    }
}

class ChessNode(val game: ChessGame, val transport: ChessFakeTransport, val players: List<PlayerInfo>) {
    val results = mutableListOf<GameResult>()
}

object ChessNetKit {
    const val HOST_ID = "host"
    const val GUEST_ID = "guest-1"

    val players = listOf(
        PlayerInfo(playerId = HOST_ID, displayName = "Ann"),
        PlayerInfo(playerId = GUEST_ID, displayName = "Bob")
    )

    /** Encodes exactly as ChessGame does on the wire (polymorphic [ChessNetMessage], default Json). */
    fun encode(message: ChessNetMessage): ByteArray =
        Json.encodeToString<ChessNetMessage>(message).toByteArray(Charsets.UTF_8)

    /** a1 = 0, h8 = 63. */
    fun sq(file: Char, rank: Int): Int = (rank - 1) * 8 + (file - 'a')

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: ChessFakeTransport = ChessFakeTransport(),
        start: Boolean = false
    ): ChessNode {
        val game = ChessGame()
        val node = ChessNode(game, transport, players)
        game.setOnMatchEnd { node.results += it }
        game.init(GameContext(activeMode = mode, players = players, localPlayerIndex = localIndex, transport = transport))
        if (start) game.startMatch()
        return node
    }

    class Pair2(val hub: ChessHub, val host: ChessNode, val guest: ChessNode)

    /**
     * A host and a guest wired through a [ChessHub], started the way the lobby/screen does it:
     * host init + startMatch first, then the guest init (which asks RequestState), then the
     * guest's own startMatch (a no-op for a guest).
     */
    fun connected(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Pair2 {
        val hub = ChessHub()
        val host = node(0, mode, hub.endpoint(HOST_ID), start = true)
        val guest = node(1, mode, hub.endpoint(GUEST_ID))
        guest.game.startMatch()
        return Pair2(hub, host, guest)
    }

    /** The wire form of [game]'s visible state, as a host would send it. */
    fun netStateOf(game: ChessGame): ChessNetState {
        val s = game.state.value!!
        return ChessNetState(
            board = s.board, sideToMove = s.sideToMove,
            castleWK = s.castleWK, castleWQ = s.castleWQ, castleBK = s.castleBK, castleBQ = s.castleBQ,
            epSquare = s.epSquare, lastFrom = s.lastFrom, lastTo = s.lastTo,
            lastAction = s.lastAction, roundOver = s.roundOver, result = s.result,
            inCheck = s.inCheck, winnerPlayerId = s.winnerPlayerId,
            scoreP1 = game.scoreP1.value, scoreP2 = game.scoreP2.value, draws = game.draws.value,
            matchOver = false
        )
    }
}
