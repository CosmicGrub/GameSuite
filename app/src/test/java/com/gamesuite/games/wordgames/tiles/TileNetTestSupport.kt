package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.json.Json

/**
 * Scaffolding for the Word Tiles host-authoritative networking tests. Not a test class itself.
 *
 * [TileFakeTransport] records every send verbatim and exposes the listeners the game registered so a
 * test can inject inbound traffic like a real transport would. [TileHub] wires endpoints together the
 * way a relay does: the receiver is told the REAL sender (the endpoint), never the claimed id.
 */
class TileFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
        val message: TileNetMessage get() = Json.decodeFromString(TileNetMessage.serializer(), text)
        val stateSync: TileNetMessage.StateSync get() = message as TileNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: TileFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

    override fun connect() {}
    override fun disconnect() {}

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Sent(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }

    override fun onMessageReceived(listener: (String, ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, message: TileNetMessage) = inject(fromPlayerId, TileTestKit.encode(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class TileHub {
    val endpoints = linkedMapOf<String, TileFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): TileFakeTransport {
        val t = TileFakeTransport()
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

class TileNode(val game: TileGame, val transport: TileFakeTransport) {
    val results = mutableListOf<GameResult>()
    val s: TileGameState get() = game.state.value!!
}

object TileTestKit {
    val roster = listOf(
        PlayerInfo(playerId = "p0", displayName = "Host"),
        PlayerInfo(playerId = "p1", displayName = "Guest")
    )

    fun encode(message: TileNetMessage): ByteArray =
        Json.encodeToString(TileNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun node(
        localIndex: Int,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        transport: TileFakeTransport = TileFakeTransport(),
        start: Boolean = false
    ): TileNode {
        val game = TileGame()
        val node = TileNode(game, transport)
        game.init(GameContext(activeMode = mode, players = roster, localPlayerIndex = localIndex, transport = transport))
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    /** A host + guest pair joined through a [TileHub]; the host deals first, the guest then asks for state. */
    class Pair2(val hub: TileHub, val host: TileNode, val guest: TileNode) {
        /**
         * Replaces both racks (and whose turn it is) in the host's authoritative state with known tiles,
         * then has the host re-send, so the guest's view follows. Ids >= 900 never collide with the bag's.
         */
        fun setRacks(hostRack: List<RackTile>, guestRack: List<RackTile>, current: Int = 0) {
            val s = host.s
            host.game.state.value = s.copy(
                players = listOf(s.players[0].copy(rack = hostRack), s.players[1].copy(rack = guestRack)),
                currentPlayerIndex = current
            )
            host.transport.fireReconnected()
        }
    }

    fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Pair2 {
        val hub = TileHub()
        val hostT = hub.endpoint("p0")
        val guestT = hub.endpoint("p1")
        val host = node(0, mode, hostT, start = true) // the broadcast lands on a guest that is not listening yet
        val guest = node(1, mode, guestT)             // init asks the host for the state
        return Pair2(hub, host, guest)
    }

    /** Letters as real (non-blank) rack tiles with ids [firstId], [firstId]+1, ... */
    fun tiles(letters: String, firstId: Int): List<RackTile> =
        letters.mapIndexed { i, ch -> RackTile(ch, false, firstId + i) }

    /** Runs [block] with a dictionary holding exactly [words] (lower case), then restores the real one. */
    fun <T> withWords(vararg words: String, block: () -> T): T {
        val field = WordDictionary::class.java.getDeclaredField("allWords").apply { isAccessible = true }
        val original = field.get(WordDictionary)
        try {
            field.set(WordDictionary, words.map { it.lowercase() }.toSet())
            return block()
        } finally {
            field.set(WordDictionary, original)
        }
    }
}
