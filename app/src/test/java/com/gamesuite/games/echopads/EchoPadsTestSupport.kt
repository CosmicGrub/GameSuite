package com.gamesuite.games.echopads

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport

/** A do-nothing transport (Echo Pads is same-device only). */
class EchoNoTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** A clock a test advances by hand. */
class EchoClock(var now: Long = 10_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}

/** A roster of [humans] people, as the Hub builds it (a lone human is "You"). */
fun echoContext(humans: Int): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", if (humans == 1) "You" else "Player ${it + 1}") }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = EchoNoTransport()
    )
}

/** Drives an engine with a hand-run clock, in small steps like frames. */
class EchoDriver(val e: EchoPadsEngine, var now: Long = 1_000L) {
    fun step(ms: Long = 50L) {
        now += ms
        e.tick(now)
    }

    fun until(limitMs: Long = 2_000_000L, cond: () -> Boolean) {
        var spent = 0L
        while (!cond()) {
            step()
            spent += 50L
            check(spent < limitMs) { "condition never reached; phase=${e.phase}" }
        }
    }

    /** Gets the current turn to the point where the player can copy (taps Ready first when the hand-off asks). */
    fun toWaiting() {
        if (e.awaitingReady) e.ready(now)
        until { e.phase == EchoPhase.WAITING || e.phase == EchoPhase.OVER }
    }

    /** Lets the feedback pause after a decided turn run out. */
    fun settle() = until { e.phase != EchoPhase.CHECKING }

    /** Taps the whole pattern right; returns the last tap's result. */
    fun copyAll(): TapResult {
        var r = TapResult.IGNORED
        for (pad in e.sequence.toList()) r = e.tap(pad, now)
        return r
    }

    /** Taps one wrong pad (the one after the right one), so the turn is decided. */
    fun miss(): TapResult = e.tap((e.sequence[e.progress] + 1) % EchoPads.COUNT, now)

    /** A whole turn played right: wait, copy, let the pause run out. */
    fun goodTurn() {
        toWaiting()
        check(copyAll() == TapResult.SEQUENCE_DONE)
        settle()
    }

    /** A whole turn missed on the first pad (no free try). */
    fun badTurn() {
        toWaiting()
        check(miss() == TapResult.MISS)
        settle()
    }
}
