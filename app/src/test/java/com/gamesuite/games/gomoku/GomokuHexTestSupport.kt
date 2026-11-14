package com.gamesuite.games.gomoku

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/**
 * Scaffolding shared by the Gomoku and Hex networking tests (not a test class). [FakeTransport] records every
 * send verbatim and exposes the listeners the game registered so a test can inject inbound traffic the way a
 * real transport would; [FakeHub] wires endpoints like a relay, telling the receiver the REAL sender.
 */
class FakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: FakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, payload: ByteArray) = messageListeners.toList().forEach { it(fromPlayerId, payload) }
    fun inject(fromPlayerId: String, json: String) = inject(fromPlayerId, json.toByteArray(Charsets.UTF_8))
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }
    fun mark(): Int = sent.size
    fun sentSince(mark: Int): List<Sent> = sent.drop(mark)
}

class FakeHub {
    val endpoints = linkedMapOf<String, FakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): FakeTransport {
        val t = FakeTransport()
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

object TestSeats {
    const val HOST = "h"
    const val GUEST = "g"
    val humans = listOf(PlayerInfo(HOST, "Hana"), PlayerInfo(GUEST, "Gus"))
    val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    fun context(
        mode: PlayMode, players: List<PlayerInfo> = humans, localIndex: Int = 0,
        transport: MultiplayerTransport = com.gamesuite.transport.LocalPassAndPlayTransport()
    ) = GameContext(mode, players, localIndex, transport)
}
