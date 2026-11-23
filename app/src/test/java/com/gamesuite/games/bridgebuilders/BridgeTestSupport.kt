package com.gamesuite.games.bridgebuilders

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport
import kotlin.math.abs
import kotlin.random.Random

/** A transport that does nothing: Bridge Builders is same-screen only. */
class BridgeFakeTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** A clock a test advances by hand. */
class BridgeClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

fun bridgeSeats(humans: Int, bots: Int): List<BridgeSeat> =
    List(humans) { BridgeSeat("p${it + 1}", "Player ${it + 1}", false) } + List(bots) { BridgeSeat("bot${it + 1}", "CPU ${it + 1}", true) }

fun bridgeContext(humans: Int, bots: Int): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}") } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = BridgeFakeTransport()
    )
}

/** Steps an engine by hand and plays it. */
class BridgeRig(val e: BridgeEngine, val clock: BridgeClock) {
    val s: BridgeState get() = e.state()

    fun adv(ms: Long) {
        var left = ms
        while (left > 0) { val d = minOf(10L, left); clock.advance(d); e.tick(); left -= d }
    }

    /** Past the countdown, into building. */
    fun live() = adv(BridgeRules.COUNTDOWN_MS + 20)

    /** Waits until the cart of [seat] is within [tol] of the middle of the green spot and taps. Taps at once when the lane cannot move. */
    fun place(seat: Int, tol: Double = 0.01, maxMs: Long = 20_000): BridgeTapResult {
        var t = 0L
        while (t < maxMs) {
            val l = s.lanes[seat]
            if (l.status != BridgeLaneStatus.MOVING) return e.tap(seat)
            if (abs(l.marker - l.windowCentre) <= tol) return e.tap(seat)
            adv(10); t += 10
        }
        error("the cart never reached the middle")
    }

    /** Advances until [cond] holds on the cart of [seat], then taps. */
    fun tapWhen(seat: Int, maxMs: Long = 20_000, cond: (BridgeLaneState) -> Boolean): BridgeTapResult {
        var t = 0L
        while (t < maxMs) {
            if (cond(s.lanes[seat])) return e.tap(seat)
            adv(10); t += 10
        }
        error("the condition never held")
    }
}

fun bridgeRig(
    humans: Int = 2, bots: Int = 0, band: BridgeBand = BridgeBand.MEDIUM, relaxed: Boolean = false, handicap: String = "none",
    seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM
): BridgeRig {
    val clock = BridgeClock()
    val e = BridgeEngine(Random(seed), clock)
    e.band = band; e.relaxed = relaxed; e.handicapSpec = handicap; e.difficulty = level
    e.setup(bridgeSeats(humans, bots))
    e.start()
    return BridgeRig(e, clock)
}
