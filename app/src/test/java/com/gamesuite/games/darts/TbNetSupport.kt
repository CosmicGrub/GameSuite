package com.gamesuite.games.darts

import com.gamesuite.transport.MultiplayerTransport

/** A transport endpoint for tests: records what it sends and lets a test inject what it "receives". */
class TbFakeTransport : MultiplayerTransport {
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: TbFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null
    var sentCount = 0

    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sentCount++
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }

    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, payload: ByteArray) = messageListeners.toList().forEach { it(fromPlayerId, payload) }
    fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
    fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
}

/** Wires endpoints together like a real relay: the receiver is told the sender's REAL identity (the endpoint), not the claimed one. */
class TbHub {
    val endpoints = linkedMapOf<String, TbFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): TbFakeTransport {
        val t = TbFakeTransport()
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
