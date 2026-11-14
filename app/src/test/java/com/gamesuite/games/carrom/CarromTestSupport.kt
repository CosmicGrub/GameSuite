package com.gamesuite.games.carrom

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport

fun carromPlayers(humans: Int, bots: Int): List<PlayerInfo> =
    List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}", false) } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", true) }

fun carromContext(players: List<PlayerInfo>, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT) =
    GameContext(mode, players, 0, LocalPassAndPlayTransport())

class CrFakeTransport : MultiplayerTransport {
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    var router: ((CrFakeTransport, String, String?, ByteArray) -> Unit)? = null
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) { router?.invoke(this, fromPlayerId, toPlayerId, payload) }
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    fun inject(from: String, payload: ByteArray) = messageListeners.toList().forEach { it(from, payload) }
}

/** Routes between endpoints like a relay: the receiver is told the endpoint it really came from. */
class CrHub {
    val endpoints = linkedMapOf<String, CrFakeTransport>()
    val senderLies = mutableListOf<String>()
    fun endpoint(id: String): CrFakeTransport {
        val t = CrFakeTransport()
        endpoints[id] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "$owner claimed $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { it.inject(owner, payload) }
        }
        return t
    }
}
