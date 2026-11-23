package com.gamesuite.games.speed

import com.gamesuite.transport.MultiplayerTransport

/** A recording transport whose listeners a test can fire (shared by the Speed and Matching tests). */
class SmFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: SmFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

/**
 * Wires endpoints like a relay: the receiver is told the REAL sender (the endpoint the bytes came
 * from), never the fromPlayerId the sender claimed. A claim that differs is recorded in [senderLies].
 */
class SmHub {
    val endpoints = linkedMapOf<String, SmFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): SmFakeTransport {
        val t = SmFakeTransport()
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

/** A clock a test advances by hand. */
class FakeClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}
