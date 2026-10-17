package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Reversi host-authoritative networking tests (not a test class).
 *
 * [ReversiFakeTransport] records every send verbatim and exposes the listeners ReversiGame registered
 * so a test can inject inbound traffic the way a real transport would. [ReversiHub] wires endpoints
 * together like a relay: the receiver is told the REAL sender (the endpoint the bytes came from),
 * never the fromPlayerId the sender claimed.
 */
class ReversiFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: ReversiNetMessage
            get() = Json.decodeFromString(ReversiNetMessage.serializer(), String(payload, Charsets.UTF_8))
        val stateSync: ReversiNetMessage.StateSync get() = message as ReversiNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: ReversiFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, message: ReversiNetMessage) =
        inject(fromPlayerId, ReversiTestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)

    /** The version of the newest StateSync this transport has sent (0 if none). */
    fun lastSentVersion(): Int =
        sent.mapNotNull { (it.message as? ReversiNetMessage.StateSync)?.version }.lastOrNull() ?: 0
}

class ReversiHub {
    val endpoints = linkedMapOf<String, ReversiFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): ReversiFakeTransport {
        val t = ReversiFakeTransport()
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

class ReversiNode(val game: ReversiGame, val transport: ReversiFakeTransport) {
    val results = mutableListOf<GameResult>()
}

class ReversiPair(val hub: ReversiHub, val host: ReversiNode, val guest: ReversiNode)

object ReversiTestKit {
    const val HOST = "h"
    const val GUEST = "g"

    val players = listOf(PlayerInfo(HOST, "Hana"), PlayerInfo(GUEST, "Gus"))

    fun encode(message: ReversiNetMessage): ByteArray =
        Json.encodeToString(ReversiNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: ReversiFakeTransport = ReversiFakeTransport(),
        start: Boolean = false
    ): ReversiNode {
        val game = ReversiGame()
        val node = ReversiNode(game, transport)
        game.init(GameContext(activeMode = mode, players = players, localPlayerIndex = localIndex, transport = transport))
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    /** A host that has dealt, then a guest that joined (its RequestState is answered over the hub). */
    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): ReversiPair {
        val hub = ReversiHub()
        val hostT = hub.endpoint(HOST)
        val guestT = hub.endpoint(GUEST)
        val host = node(0, mode, hostT, start = true)
        val guest = node(1, mode, guestT)
        return ReversiPair(hub, host, guest)
    }

    fun openingCells(): List<Int?> = MutableList<Int?>(64) { null }.also {
        it[27] = 1; it[36] = 1; it[28] = 0; it[35] = 0
    }

    fun netState(current: Int = 0, lastAction: String = "x", cells: List<Int?> = openingCells()) = ReversiNetState(
        cells = cells,
        players = players.map { ReversiNetPlayer(it.playerId, it.displayName) },
        currentPlayerIndex = current,
        lastAction = lastAction,
        justPassed = false,
        boardOver = false,
        winnerPlayerId = null,
        sessionWins = mapOf(HOST to 0, GUEST to 0),
        sessionDraws = 0
    )

    /**
     * A hand-built position, in the engine's own snapshot format, where seat 0 to move plays cell 2
     * (flipping cell 1) and seat 1 is then left with no legal move (a real pass back to seat 0);
     * seat 0 then plays cell 58 (flipping 57) and seat 1 has no discs: the board is over, seat 0 wins 6-0.
     * [nextStarting] is the opener the NEXT board will get.
     */
    fun passPositionSnapshot(nextStarting: Int = 1): String {
        val cells = (0 until 64).joinToString(",") {
            when (it) {
                0, 56 -> "0"
                1, 57 -> "1"
                else -> "null"
            }
        }
        return "{\"cells\":[" + cells + "]," +
            "\"players\":[{\"playerId\":\"h\",\"displayName\":\"Hana\",\"isBot\":false}," +
            "{\"playerId\":\"g\",\"displayName\":\"Gus\",\"isBot\":false}]," +
            "\"currentPlayerIndex\":0,\"lastAction\":\"test\",\"justPassed\":false,\"boardOver\":false," +
            "\"winnerPlayerId\":null,\"sessionWins\":{\"h\":0,\"g\":0},\"sessionDraws\":0," +
            "\"nextStartingPlayerIndex\":$nextStarting,\"difficulty\":\"MEDIUM\"}"
    }

    /**
     * Puts the host engine on [snapshot] and gets the position to the guest. restore() itself does not
     * broadcast (resume is a local-mode feature), so a leave + rejoin of the guest seat is used as the
     * state-changing event that bumps the version and re-broadcasts.
     */
    fun loadPosition(pair: ReversiPair, snapshot: String) {
        check(pair.host.game.restore(snapshot)) { "test snapshot was rejected" }
        pair.host.transport.fireLeft(GUEST)
        pair.host.transport.fireJoined(GUEST)
    }
}
