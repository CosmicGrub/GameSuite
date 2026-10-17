package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/**
 * Scaffolding for the Checkers host-authoritative networking tests. Not a test class itself.
 *
 * [CheckersFakeTransport] records every `send` verbatim and keeps the listeners CheckersGame
 * registered so a test can inject inbound traffic the way a real transport would. [CheckersHub]
 * wires endpoints together like a relay does: the receiver is always told the REAL sender (the
 * endpoint the bytes came from), never the `fromPlayerId` the sender claimed.
 */
class CheckersFakeTransport : MultiplayerTransport {
    /** Plain class on purpose: a ByteArray member would give a data class identity-based equals. */
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: CheckersNetMessage get() = decodeCheckersNetMessage(payload)!!
        val stateSync: CheckersNetMessage.StateSync get() = message as CheckersNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: CheckersFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, message: CheckersNetMessage) =
        inject(fromPlayerId, encodeCheckersNetMessage(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
    fun stateSyncs(): List<CheckersNetMessage.StateSync> =
        sent.mapNotNull { it.message as? CheckersNetMessage.StateSync }
}

class CheckersHub {
    val endpoints = linkedMapOf<String, CheckersFakeTransport>()
    val senderLies = mutableListOf<String>()
    val dropTo = mutableSetOf<String>()

    fun endpoint(playerId: String): CheckersFakeTransport {
        val t = CheckersFakeTransport()
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

class CheckersNode(val game: CheckersGame, val transport: CheckersFakeTransport, val seat: Int) {
    var result: GameResult? = null
}

object CheckersTestKit {
    const val HOST = "p0"
    const val GUEST = "p1"

    val players = listOf(
        PlayerInfo(playerId = HOST, displayName = "Host"),
        PlayerInfo(playerId = GUEST, displayName = "Guest")
    )

    fun node(seat: Int, transport: CheckersFakeTransport, mode: PlayMode = PlayMode.LOCAL_AD_HOC): CheckersNode {
        val game = CheckersGame()
        val node = CheckersNode(game, transport, seat)
        game.setOnMatchEnd { node.result = it }
        game.init(GameContext(activeMode = mode, players = players, localPlayerIndex = seat, transport = transport))
        return node
    }

    /** A host that has dealt, optionally with [board] replacing the opening position (turn = [currentSeat]). */
    fun host(hub: CheckersHub, board: List<CheckersPiece?>? = null, currentSeat: Int = 1, mode: PlayMode = PlayMode.LOCAL_AD_HOC): CheckersNode {
        val host = node(0, hub.endpoint(HOST), mode)
        host.game.startMatch()
        if (board != null) host.game.state.value = CheckersState(board = board, currentPlayerIndex = currentSeat)
        return host
    }

    /** A guest that starts after the host: its RequestState is answered synchronously by the hub. */
    fun guest(hub: CheckersHub, mode: PlayMode = PlayMode.LOCAL_AD_HOC): CheckersNode =
        node(1, hub.endpoint(GUEST), mode)

    /** Builds a 64-cell board from (row, col, owner, kind) entries. */
    fun board(vararg pieces: Piece): List<CheckersPiece?> {
        val cells = MutableList<CheckersPiece?>(64) { null }
        pieces.forEach { cells[it.row * 8 + it.col] = CheckersPiece(it.owner, it.kind) }
        return cells
    }

    class Piece(val row: Int, val col: Int, val owner: Int, val kind: PieceKind = PieceKind.MAN)

    fun dark(row: Int, col: Int, kind: PieceKind = PieceKind.MAN) = Piece(row, col, 0, kind)
    fun light(row: Int, col: Int, kind: PieceKind = PieceKind.MAN) = Piece(row, col, 1, kind)

    /** Guest to move: Dark man (1,0) can jump (2,1) then (4,3), landing on (5,4); spare men elsewhere. */
    fun doubleJumpBoard() = board(dark(1, 0), dark(0, 5), light(2, 1), light(4, 3), light(7, 6))

    /** The guest's only legal turn on [doubleJumpBoard] as a request path. */
    val doubleJumpPath = listOf(3 to 2, 5 to 4)

    fun square(row: Int, col: Int) = row * 8 + col

    /**
     * Plays the first legal turn for [node]'s seat, through the public API only: a host plays hop by
     * hop with playMove, a guest builds the whole chain with previewChain and submits it.
     */
    fun playFirstLegalTurn(node: CheckersNode): Boolean {
        val game = node.game
        val s = game.state.value ?: return false
        if (s.gameOver || s.currentPlayerIndex != node.seat) return false
        for (row in 0..7) for (col in 0..7) {
            if (!game.hasLegalMoveFrom(row, col)) continue
            if (node.seat == 0) {
                do {
                    val cur = game.state.value!!
                    val from = if (cur.inForcedContinuation) cur.forcedRow to cur.forcedCol else row to col
                    val dest = game.legalDestinationsFrom(from.first, from.second).first()
                    game.playMove(0, from.first, from.second, dest.first, dest.second)
                } while (game.state.value!!.currentPlayerIndex == 0 && !game.state.value!!.gameOver)
                return true
            }
            val chain = mutableListOf<Pair<Int, Int>>()
            while (true) {
                val preview = game.previewChain(row, col, chain) ?: return false
                if (preview.complete) break
                chain += preview.destinations.first()
            }
            return game.submitMove(row, col, chain)
        }
        return false
    }
}
