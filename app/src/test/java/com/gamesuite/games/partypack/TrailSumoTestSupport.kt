package com.gamesuite.games.partypack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/** A transport that goes nowhere: the same-screen party games never use one. */
class TsNullTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** A hand-wound clock in milliseconds. */
class TsClock { var now = 0L; fun advance(ms: Long) { now += ms }; val read: () -> Long = { now } }

/** Seats 0..humans-1 are people, the rest computers. */
fun tsContext(humans: Int, bots: Int): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", if (humans == 1) "You" else "Player ${it + 1}") } +
        List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT, players, 0, TsNullTransport())
}
