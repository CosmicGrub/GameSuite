package com.gamesuite.games.billiards

import com.gamesuite.transport.MultiplayerTransport

/**
 * A relay-like fake for the toy-sport games' networking tests: the receiver is told the REAL sender (the
 * endpoint the bytes came from), never the `fromPlayerId` the sender wrote, and every claim that differed
 * is recorded in [TsHub.senderLies].
 */
class TsFakeTransport : MultiplayerTransport {
    val sent = mutableListOf<Triple<String, String?, ByteArray>>()
    private val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    private val joinedListeners = mutableListOf<(String) -> Unit>()
    private val leftListeners = mutableListOf<(String) -> Unit>()
    private val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: TsFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Triple(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, payload: ByteArray) = messageListeners.toList().forEach { it(fromPlayerId, payload) }
    fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
    fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
}

class TsHub {
    val endpoints = linkedMapOf<String, TsFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): TsFakeTransport {
        val t = TsFakeTransport()
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
