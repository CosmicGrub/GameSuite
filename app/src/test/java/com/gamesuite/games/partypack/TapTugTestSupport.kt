package com.gamesuite.games.partypack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/** A transport that does nothing: the party games are same-screen only and never send a message. */
class TapTugFakeTransport : MultiplayerTransport {
    var sends = 0
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) { sends++ }
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** A clock a test advances by hand. */
class TapTugClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

/** A roster of [humans] people and [bots] computers (people first), and the context for it. */
fun tapTugContext(humans: Int, bots: Int, transport: MultiplayerTransport = TapTugFakeTransport()): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", if (humans == 1) "You" else "Player ${it + 1}") } +
        List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = transport
    )
}
