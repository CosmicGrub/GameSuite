package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Tic-Tac-Toe host-authoritative networking tests (a copy of the idea in the
 * UNO tests' fake transport, kept separate on purpose). Not a test class itself.
 *
 * [TttFakeTransport] records every send verbatim and keeps the listeners the game registered so a
 * test can inject inbound traffic exactly like a real transport. [TttHub] wires two of them the
 * way a relay does: the receiver is told the REAL sender (the endpoint the bytes came from), never
 * the id the sender claimed.
 */
class TttFakeTransport : MultiplayerTransport {
    /** Plain class (not data): a ByteArray member would give a data class identity equality. */
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: TicTacToeNetMessage
            get() = Json.decodeFromString(TicTacToeNetMessage.serializer(), payload.decodeToString())
        val stateSync: TicTacToeNetMessage.StateSync get() = message as TicTacToeNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    /** Set by [TttHub]; when non-null every send is also routed through it. */
    var router: ((source: TttFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    /** Simulates an inbound message from [fromPlayerId] (the transport-level real sender). */
    fun inject(fromPlayerId: String, message: TicTacToeNetMessage) =
        inject(fromPlayerId, TttTestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

/** In-memory relay: a send from an endpoint is delivered synchronously, tagged with the ENDPOINT's id. */
class TttHub {
    val endpoints = linkedMapOf<String, TttFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): TttFakeTransport {
        val t = TttFakeTransport()
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

class TttNode(val game: TicTacToeGame, val transport: TttFakeTransport)

/** A started host (seat 0, "host") and guest (seat 1, "guest") wired through one [TttHub]. */
class TttPair(val hub: TttHub, val host: TttNode, val guest: TttNode)

object TttTestKit {
    const val HOST = "host"
    const val GUEST = "guest"

    fun encode(message: TicTacToeNetMessage): ByteArray =
        Json.encodeToString(TicTacToeNetMessage.serializer(), message).encodeToByteArray()

    fun cell(index: Int) = TicTacToeNetMessage.Intent(TicTacToeIntentPayload.CellClicked(index))
    val playAgainIntent = TicTacToeNetMessage.Intent(TicTacToeIntentPayload.PlayAgain)

    val roster = listOf(
        PlayerInfo(playerId = HOST, displayName = "Hosty"),
        PlayerInfo(playerId = GUEST, displayName = "Guesty")
    )

    /** One engine in a networked mode. [start] calls startMatch() like the screen does. */
    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: TttFakeTransport = TttFakeTransport(),
        start: Boolean = true
    ): TttNode {
        val game = TicTacToeGame()
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = localIndex, transport = transport))
        if (start) game.startMatch()
        return TttNode(game, transport)
    }

    /** Host first (its listener must exist before the guest's init sends RequestState), then guest. */
    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): TttPair {
        val hub = TttHub()
        val host = node(0, mode, hub.endpoint(HOST))
        val guest = node(1, mode, hub.endpoint(GUEST))
        return TttPair(hub, host, guest)
    }

    /** Asserts both engines show the same authoritative state. */
    fun assertSame(a: TicTacToeGame, b: TicTacToeGame) {
        org.junit.Assert.assertEquals(a.board.value.toList(), b.board.value.toList())
        org.junit.Assert.assertEquals(a.currentPlayer.value, b.currentPlayer.value)
        org.junit.Assert.assertEquals(a.roundOver.value, b.roundOver.value)
        org.junit.Assert.assertEquals(a.winningLine.value, b.winningLine.value)
        org.junit.Assert.assertEquals(a.scoreP1.value, b.scoreP1.value)
        org.junit.Assert.assertEquals(a.scoreP2.value, b.scoreP2.value)
        org.junit.Assert.assertEquals(a.draws.value, b.draws.value)
        org.junit.Assert.assertEquals(a.roundNumber.value, b.roundNumber.value)
    }

    fun state(
        board: List<Int> = List(9) { 0 },
        currentPlayer: Int = 1,
        roundOver: Boolean = false,
        winningLine: List<Int>? = null,
        scoreP1: Int = 0,
        scoreP2: Int = 0,
        draws: Int = 0,
        roundNumber: Int = 1
    ) = TicTacToeNetState(
        board = board, currentPlayer = currentPlayer, roundOver = roundOver, winningLine = winningLine,
        scoreP1 = scoreP1, scoreP2 = scoreP2, draws = draws, roundNumber = roundNumber,
        matchOver = false, selectedSymbol = 1
    )
}
