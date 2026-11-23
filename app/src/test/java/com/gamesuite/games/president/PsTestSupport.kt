package com.gamesuite.games.president

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.transport.MultiplayerTransport

/** A transport that records every send and lets a test inject incoming messages. */
class PsFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: PsFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(from: String, payload: ByteArray) = messageListeners.toList().forEach { it(from, payload) }
    fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
    fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
}

/** Wires endpoints like a relay: the receiver is told the REAL sender, never the id the sender claimed. */
class PsHub {
    val endpoints = linkedMapOf<String, PsFakeTransport>()
    val senderLies = mutableListOf<String>()
    var delivered = 0

    fun endpoint(playerId: String): PsFakeTransport {
        val t = PsFakeTransport()
        endpoints[playerId] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "endpoint $owner claimed to be $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { delivered++; it.inject(owner, payload) }
        }
        return t
    }
}

fun psPlayers(humans: Int, bots: Int = 0): List<PlayerInfo> =
    List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}") } +
        List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }

fun psOfflineContext(players: List<PlayerInfo>, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
    GameContext(mode, players, 0, PsFakeTransport())

/** "3C", "10H", "KS", "AD", "2S": a card id from a short label. */
fun cid(label: String): Int {
    val suit = when (label.last()) { 'C' -> Suit.CLUBS; 'D' -> Suit.DIAMONDS; 'H' -> Suit.HEARTS; 'S' -> Suit.SPADES; else -> error(label) }
    val r = label.dropLast(1)
    val rank = Rank.values().first { it.label == r }
    return PsDeck.cards.first { it.rank == rank && it.suit == suit }.id
}

fun cids(vararg labels: String) = labels.map(::cid)
