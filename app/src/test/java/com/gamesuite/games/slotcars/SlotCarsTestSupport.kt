package com.gamesuite.games.slotcars

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport
import kotlin.random.Random

/** A recording transport whose listeners a test can fire (Slot Cars and Fishing tests). */
class ScFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: ScFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

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

    fun inject(fromPlayerId: String, payload: ByteArray) { messageListeners.toList().forEach { it(fromPlayerId, payload) } }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }
}

/**
 * Wires endpoints like a relay: the receiver is told the REAL sender (the endpoint the bytes came
 * from), never the sender's own claim; a lie in `fromPlayerId` is recorded in [senderLies].
 */
class ScHub {
    val endpoints = linkedMapOf<String, ScFakeTransport>()
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): ScFakeTransport {
        val t = ScFakeTransport()
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

class ScClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

object ScKit {
    fun roster(humans: Int, total: Int = humans) = (0 until total).map { i ->
        if (i < humans) PlayerInfo("p${i + 1}", "P${i + 1}") else PlayerInfo("bot${i + 1}", "CPU${i + 1}", isBot = true)
    }

    fun newGame(
        players: List<PlayerInfo> = roster(2),
        seed: Int = 1,
        clock: ScClock = ScClock(),
        laps: Int = 3,
        track: String = "meadow",
        assist: String = "off",
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        results: MutableList<GameResult>? = null,
        start: Boolean = true
    ): SlotCarsGame {
        val mode = if (players.any { it.isBot }) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        val g = SlotCarsGame(Random(seed), clock)
        g.difficulty = difficulty; g.laps = laps; g.trackId = track; g.assist = assist
        g.init(GameContext(mode, players, 0, ScFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        if (start) g.startMatch()
        return g
    }

    /** Runs the countdown so the race is live. */
    fun live(g: SlotCarsGame) { g.stepTicks(SlotCarsRules.COUNTDOWN_TICKS + 2) }
}
