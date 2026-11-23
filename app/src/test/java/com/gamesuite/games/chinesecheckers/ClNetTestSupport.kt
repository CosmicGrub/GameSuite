package com.gamesuite.games.chinesecheckers

import com.gamesuite.transport.MultiplayerTransport

/**
 * Scaffolding for the host-authoritative networking tests of Chinese Checkers and Ludo (not a test
 * class). [ClFakeTransport] records every send and exposes the listeners a game registered;
 * [ClHub] wires endpoints together like a relay: the receiver is told the REAL sender (the endpoint
 * the bytes came from), never the fromPlayerId the sender claimed.
 */
class ClFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: ClFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, payload: ByteArray) {
        messageListeners.toList().forEach { it(fromPlayerId, payload) }
    }

    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun mark(): Int = sent.size
}

class ClHub {
    val endpoints = linkedMapOf<String, ClFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): ClFakeTransport {
        val t = ClFakeTransport()
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
