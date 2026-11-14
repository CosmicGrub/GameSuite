package com.gamesuite.games.morris

import com.gamesuite.transport.MultiplayerTransport

/**
 * Scaffolding shared by the Morris and Hare and Hounds networking tests (not a test class): a
 * transport that records every send, exposes the listeners the game registered, and a relay that
 * tells the receiver the REAL sender, never the id the sender claimed.
 */
class MorrisFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: MorrisFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
    fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class MorrisFakeHub {
    val endpoints = linkedMapOf<String, MorrisFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): MorrisFakeTransport {
        val t = MorrisFakeTransport()
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
