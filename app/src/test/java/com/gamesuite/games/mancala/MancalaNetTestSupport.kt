package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import org.junit.Assert.assertEquals

/**
 * Scaffolding for the Mancala host-authoritative networking tests (not a test class).
 *
 * [MancalaFakeTransport] records every send verbatim and keeps the listeners MancalaGame registered
 * so a test can inject inbound traffic the way a real transport would. [MancalaHub] wires two of
 * them like a relay: the receiver is always told the REAL sender (the endpoint the bytes came from),
 * never the `fromPlayerId` the sender claimed.
 */
class MancalaFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: MancalaNetMessage get() = MancalaNetCodec.decode(payload)!!
        val stateSync: MancalaNetMessage.StateSync get() = message as MancalaNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: MancalaFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, message: MancalaNetMessage) = inject(fromPlayerId, MancalaNetCodec.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class MancalaHub {
    val endpoints = linkedMapOf<String, MancalaFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): MancalaFakeTransport {
        val t = MancalaFakeTransport()
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

class MancalaNode(val game: MancalaGame, val transport: MancalaFakeTransport)

class MancalaPair(val host: MancalaNode, val guest: MancalaNode, val hub: MancalaHub)

object MancalaTestKit {
    const val HOST_ID = "host"
    const val GUEST_ID = "guest"

    val roster = listOf(
        PlayerInfo(playerId = HOST_ID, displayName = "Hosty"),
        PlayerInfo(playerId = GUEST_ID, displayName = "Guesty")
    )

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: MancalaFakeTransport = MancalaFakeTransport()
    ): MancalaNode {
        val game = MancalaGame()
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = localIndex, transport = transport))
        return MancalaNode(game, transport)
    }

    /** Host and guest wired through a relay, host dealt, guest already holding the opening board. */
    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC, deal: Boolean = true): MancalaPair {
        val hub = MancalaHub()
        val host = node(0, mode, hub.endpoint(HOST_ID))
        val guest = node(1, mode, hub.endpoint(GUEST_ID))
        if (deal) {
            host.game.startMatch()
            guest.game.startMatch() // a guest never deals; it waits for the host's StateSync
        }
        return MancalaPair(host, guest, hub)
    }

    fun encode(message: MancalaNetMessage): ByteArray = MancalaNetCodec.encode(message)

    /** A plausible board (48 stones) for hand-fed StateSyncs. */
    fun netState(
        pits: List<Int> = List(14) { if (it == 6 || it == 13) 0 else 4 },
        current: Int = 0,
        lastAction: String = "Game started",
        roundOver: Boolean = false,
        winner: String? = null,
        path: List<Int> = emptyList(),
        capture: MancalaNetCapture? = null
    ) = MancalaNetState(
        pits = pits, currentPlayerIndex = current, lastAction = lastAction, roundOver = roundOver,
        winnerPlayerId = winner, lastSowPath = path, lastCapture = capture
    )

    /** The comparable part of a state (the screen-only `restored` flag is excluded). */
    fun board(game: MancalaGame): MancalaState = game.state.value!!.copy(restored = false)

    fun assertSynced(pair: MancalaPair) {
        assertEquals(board(pair.host.game), board(pair.guest.game))
        assertEquals(pair.host.game.scoreP1.value, pair.guest.game.scoreP1.value)
        assertEquals(pair.host.game.scoreP2.value, pair.guest.game.scoreP2.value)
        assertEquals(pair.host.game.draws.value, pair.guest.game.draws.value)
    }
}
