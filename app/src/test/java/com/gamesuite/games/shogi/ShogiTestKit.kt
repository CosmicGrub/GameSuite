package com.gamesuite.games.shogi

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/** Test scaffolding (not a test class): position diagrams, fake transport, game factories. */
object ShogiTestKit {
    const val HOST = "h"
    const val GUEST = "g"
    val humans = listOf(PlayerInfo(HOST, "Hana"), PlayerInfo(GUEST, "Gus"))
    val vsBot = listOf(PlayerInfo(HOST, "Hana"), PlayerInfo("bot", "CPU", isBot = true))

    /**
     * Builds a position from a diagram: [rows] are space-separated tokens, "." empty, uppercase = Sente,
     * lowercase = Gote, optional leading "+" = promoted. Letters P L N S G B R K.
     * [hands] maps (side, type) to a count.
     */
    fun position(n: Int, rows: List<String>, side: Int = 0, hands: Map<Pair<Int, Int>, Int> = emptyMap()): ShogiPosition {
        val p = ShogiPosition(n)
        require(rows.size == n)
        for ((r, line) in rows.withIndex()) {
            val tokens = line.trim().split(Regex("\\s+"))
            require(tokens.size == n) { "row $r has ${tokens.size} tokens: $line" }
            for ((c, tok) in tokens.withIndex()) {
                if (tok == ".") continue
                val promoted = tok.startsWith("+")
                val ch = tok.removePrefix("+")[0]
                val owner = if (ch.isUpperCase()) 0 else 1
                var type = when (ch.uppercaseChar()) {
                    'P' -> ShogiPiece.PAWN
                    'L' -> ShogiPiece.LANCE
                    'N' -> ShogiPiece.KNIGHT
                    'S' -> ShogiPiece.SILVER
                    'G' -> ShogiPiece.GOLD
                    'B' -> ShogiPiece.BISHOP
                    'R' -> ShogiPiece.ROOK
                    'K' -> ShogiPiece.KING
                    else -> error("bad token $tok")
                }
                if (promoted) type = ShogiPiece.promote(type)
                p.board[r * n + c] = ShogiPiece.code(owner, type)
            }
        }
        for ((k, v) in hands) p.hands[k.first * 7 + k.second - 1] = v
        p.side = side
        p.recomputeKings()
        return p
    }

    /** A 9x9 board with just the given pieces: each entry is ((row, col), token). */
    fun sparse(n: Int, vararg at: Pair<Pair<Int, Int>, String>, side: Int = 0, hands: Map<Pair<Int, Int>, Int> = emptyMap()): ShogiPosition {
        val grid = Array(n) { Array(n) { "." } }
        for ((rc, tok) in at) grid[rc.first][rc.second] = tok
        return position(n, grid.map { it.joinToString(" ") }, side, hands)
    }

    fun movesOf(rules: ShogiRules, p: ShogiPosition): List<Int> = rules.legalMoves(p).toList()

    class FakeTransport : MultiplayerTransport {
        class Sent(val from: String, val to: String?, val payload: ByteArray) {
            val message: ShogiNetMessage
                get() = Json.decodeFromString(ShogiNetMessage.serializer(), String(payload, Charsets.UTF_8))
        }

        val sent = mutableListOf<Sent>()
        val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
        val joinedListeners = mutableListOf<(String) -> Unit>()
        val leftListeners = mutableListOf<(String) -> Unit>()
        val reconnectedListeners = mutableListOf<() -> Unit>()
        var router: ((FakeTransport, String, String?, ByteArray) -> Unit)? = null
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
        fun inject(from: String, m: ShogiNetMessage) = inject(from, encode(m))
        fun inject(from: String, payload: ByteArray) = messageListeners.toList().forEach { it(from, payload) }
        fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
        fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
        fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
        fun lastVersion(): Int = sent.mapNotNull { (it.message as? ShogiNetMessage.StateSync)?.version }.lastOrNull() ?: 0
    }

    fun encode(m: ShogiNetMessage): ByteArray =
        Json.encodeToString(ShogiNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    class Node(val game: ShogiGame, val transport: FakeTransport) {
        val results = mutableListOf<GameResult>()
    }

    class Table(val host: Node, val guest: Node, val senderLies: MutableList<String>)

    fun local(
        variant: ShogiVariant = ShogiVariant.STANDARD,
        players: List<PlayerInfo> = humans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        start: Boolean = true
    ): ShogiGame {
        val g = ShogiGame(variant)
        g.init(GameContext(mode, players, 0, FakeTransport()))
        if (start) g.startMatch()
        return g
    }

    fun table(variant: ShogiVariant = ShogiVariant.STANDARD, mode: PlayMode = PlayMode.LOCAL_AD_HOC): Table {
        val endpoints = linkedMapOf<String, FakeTransport>()
        val lies = mutableListOf<String>()
        fun endpoint(id: String): FakeTransport {
            val t = FakeTransport()
            endpoints[id] = t
            t.router = { source, from, to, payload ->
                val owner = endpoints.entries.first { it.value === source }.key
                if (from != owner) lies += "endpoint $owner claimed to be $from"
                val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
                targets.forEach { it.inject(owner, payload) }
            }
            return t
        }
        val hostT = endpoint(HOST)
        val guestT = endpoint(GUEST)
        fun node(idx: Int, t: FakeTransport, start: Boolean): Node {
            val g = ShogiGame(variant)
            val n = Node(g, t)
            g.init(GameContext(mode, humans, idx, t))
            g.setOnMatchEnd { n.results += it }
            if (start) g.startMatch()
            return n
        }
        val host = node(0, hostT, true)
        val guest = node(1, guestT, false)
        return Table(host, guest, lies)
    }
}
