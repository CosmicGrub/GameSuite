package com.gamesuite.games.hanafuda

import com.gamesuite.transport.MultiplayerTransport

/**
 * Scaffolding for the Hanafuda networking tests (not a test class). [HfFakeTransport] records every
 * send; [HfHub] wires endpoints together like a relay: the receiver is told the REAL sender (the
 * endpoint the bytes came from), never the fromPlayerId the sender claimed.
 */
class HfFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()

    var router: ((source: HfFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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
}

class HfHub {
    val endpoints = linkedMapOf<String, HfFakeTransport>()
    val senderLies = mutableListOf<String>()

    /** When true, messages are queued instead of delivered, so a test can reorder or replay them. */
    var held = false
    val queue = mutableListOf<Triple<HfFakeTransport, String, ByteArray>>()

    fun endpoint(playerId: String): HfFakeTransport {
        val t = HfFakeTransport()
        endpoints[playerId] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "endpoint $owner claimed to be $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { target ->
                if (held) queue += Triple(target, owner, payload) else target.inject(owner, payload)
            }
        }
        return t
    }

    fun flush() {
        val items = queue.toList()
        queue.clear()
        items.forEach { (target, owner, payload) -> target.inject(owner, payload) }
    }
}
