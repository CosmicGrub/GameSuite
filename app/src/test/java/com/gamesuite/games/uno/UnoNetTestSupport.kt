package com.gamesuite.games.uno

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals

/**
 * Shared scaffolding for the UNO host-authoritative networking tests. Not a test class itself.
 *
 * [UnoFakeTransport] records every `send(fromPlayerId, toPlayerId, payload)` verbatim and exposes
 * the listeners UnoGame registered so a test can inject inbound traffic exactly the way a real
 * transport would (`onMessageReceived` / `onReconnected` / `onPlayerLeft`). [UnoHub] wires several
 * of them together the way a real relay does: the receiver is always told the REAL sender (the
 * endpoint the bytes came from), never the `fromPlayerId` the sender claimed, matching
 * MultiplayerTransport's own contract.
 */
class UnoFakeTransport : MultiplayerTransport {
    /** Plain class on purpose (not a data class): a ByteArray member would give it identity-based equals. */
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val message: UnoNetMessage get() = Json.decodeFromString<UnoNetMessage>(String(payload, Charsets.UTF_8))
        val stateSync: UnoNetMessage.StateSync get() = message as UnoNetMessage.StateSync
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    /** Set by [UnoHub]; when non-null every send is also routed through it. */
    var router: ((source: UnoFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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
    fun inject(fromPlayerId: String, message: UnoNetMessage) =
        inject(fromPlayerId, UnoTestKit.encodeUnoMessage(message))

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }

    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

/**
 * In-memory relay. `endpoints[playerId]` is the transport belonging to that device. A send from an
 * endpoint is delivered synchronously to the addressee(s) tagged with the ENDPOINT's own id, and
 * any send whose claimed `fromPlayerId` differs from the endpoint's id is recorded in
 * [senderLies] so a test can assert UnoGame never lies about who it is.
 */
class UnoHub {
    val endpoints = linkedMapOf<String, UnoFakeTransport>()
    val senderLies = mutableListOf<String>()
    val dropTo = mutableSetOf<String>()

    fun endpoint(playerId: String): UnoFakeTransport {
        val t = UnoFakeTransport()
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

class UnoNode(val game: UnoGame, val transport: UnoFakeTransport, val players: List<PlayerInfo>)

object UnoTestKit {
    /** Encodes exactly as UnoGame does on the wire (polymorphic [UnoNetMessage], default Json, UTF-8). */
    fun encodeUnoMessage(message: UnoNetMessage): ByteArray =
        Json.encodeToString<UnoNetMessage>(message).toByteArray(Charsets.UTF_8)

    fun players(n: Int) = (0 until n).map { PlayerInfo(playerId = "p$it", displayName = "Player $it") }

    fun node(
        localIndex: Int,
        n: Int = 3,
        mode: PlayMode = PlayMode.LOCAL_AD_HOC,
        rules: UnoRules = UnoRules(),
        transport: UnoFakeTransport = UnoFakeTransport(),
        start: Boolean = false
    ): UnoNode {
        val game = UnoGame()
        game.rules = rules
        val ps = players(n)
        game.init(GameContext(activeMode = mode, players = ps, localPlayerIndex = localIndex, transport = transport))
        if (start) game.startMatch()
        return UnoNode(game, transport, ps)
    }

    fun c(color: UnoColor, rank: UnoRank, id: Int) = UnoCard(color, rank, id)

    /** A default hand for seat [i] that is unplayable against RED FIVE / RED (no red, no five, no wild). */
    fun deadHand(i: Int): List<UnoCard> = listOf(
        c(UnoColor.BLUE, UnoRank.ONE, 100 + i * 10),
        c(UnoColor.BLUE, UnoRank.TWO, 101 + i * 10),
        c(UnoColor.GREEN, UnoRank.THREE, 102 + i * 10)
    )

    /**
     * Hand-built, fully deterministic table state: [n] seats each holding [deadHand] unless
     * overridden in [hands], top of discard = RED FIVE (instanceId 900), colour RED.
     */
    fun table(
        n: Int = 3,
        current: Int = 1,
        hands: Map<Int, List<UnoCard>> = emptyMap(),
        top: UnoCard = c(UnoColor.RED, UnoRank.FIVE, 900),
        color: UnoColor = UnoColor.RED
    ): UnoState = UnoState(
        players = (0 until n).map { i ->
            UnoPlayerState(
                playerId = "p$i", displayName = "Player $i", isBot = false, teamId = -1,
                hand = hands[i] ?: deadHand(i)
            )
        },
        drawPileSize = 50,
        discardPile = listOf(top),
        currentColor = color,
        currentPlayerIndex = current,
        direction = 1,
        pendingDraw = 0,
        awaitingColorChoice = false,
        lastAction = "test table"
    )

    /**
     * Replaces UnoGame's private draw pile (top = first element). UnoGame deals from an unseeded
     * shuffle and exposes no seam, so reflection is the only way to make "what does drawCard()
     * hand out" certain instead of probabilistic. This is the single place that reflects; any
     * mismatch with the production field (`private var drawPile: MutableList<UnoCard>`) fails with
     * an AssertionError naming the required field, so a refactor is diagnosed in one line, not by
     * dozens of unrelated failures.
     */
    fun seedDrawPile(game: UnoGame, cards: List<UnoCard>) {
        val required = "UnoGame.drawPile must be a non-final field of type MutableList<UnoCard> (assignable from java.util.ArrayList)"
        try {
            val f = UnoGame::class.java.getDeclaredField("drawPile")
            f.isAccessible = true
            f.set(game, cards.toMutableList())
        } catch (e: NoSuchFieldException) {
            throw AssertionError("$required; the field was renamed or removed. Update UnoTestKit.seedDrawPile.", e)
        } catch (e: IllegalArgumentException) {
            throw AssertionError("$required; its type changed (${e.message}). Update UnoTestKit.seedDrawPile.", e)
        } catch (e: IllegalAccessException) {
            throw AssertionError("$required; it is no longer reflectively writable (${e.message}). Update UnoTestKit.seedDrawPile.", e)
        }
    }

    /** The exact hand a recipient would see for seat [i]: real for [viewer], sentinel placeholders elsewhere. */
    val placeholder = UnoCard(UnoColor.WILD, UnoRank.WILD, instanceId = -1)

    fun expectedRedaction(host: UnoState, viewerId: String): UnoState = host.copy(
        players = host.players.map { p ->
            if (p.playerId == viewerId) p else p.copy(hand = List(p.hand.size) { placeholder })
        }
    )

    /** Asserts [msg] is a StateSync whose state equals the host state redacted for [viewerId]. */
    fun assertRedactedFor(msg: UnoNetMessage.StateSync, hostState: UnoState, viewerId: String) {
        assertEquals(expectedRedaction(hostState, viewerId), msg.state)
    }
}
