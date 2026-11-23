package com.gamesuite.games.partypack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/** A do-nothing transport (the same-screen games never use it) shared by the Reaction Duel and Whack-a-Mole tests. */
class RdWhNoTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** A clock a test advances by hand. */
class RdWhClock(var now: Long = 10_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

/** A roster of [humans] people followed by [bots] computers, as the Hub builds it. */
fun rdWhContext(humans: Int, bots: Int): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", if (humans == 1) "You" else "Player ${it + 1}") } +
        List(bots) { PlayerInfo("bot${it + 1}", if (bots == 1) "CPU" else "CPU ${it + 1}", isBot = true) }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = RdWhNoTransport()
    )
}
