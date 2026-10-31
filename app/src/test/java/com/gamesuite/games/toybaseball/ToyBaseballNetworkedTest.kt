package com.gamesuite.games.toybaseball

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.toyboxing.TbClock
import com.gamesuite.games.toyboxing.TbFakeTransport
import com.gamesuite.games.toyboxing.TbHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Toy Baseball: the host owns the dice and the pending pitch; identity comes from the transport. */
class ToyBaseballNetworkedTest {
    private val roster = listOf(PlayerInfo("h", "Hana"), PlayerInfo("g", "Gus"))

    private class Node(val game: ToyBaseballGame, val transport: TbFakeTransport)
    private class Duo(val hub: TbHub, val host: Node, val guest: Node)

    private fun enc(m: ToyBaseballNetMessage) = Json.encodeToString(ToyBaseballNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = Json.decodeFromString(ToyBaseballNetMessage.serializer(), String(b, Charsets.UTF_8))

    /** [away] is the seat that bats first: 0 makes the host bat (and the guest pitch) in the top of the 1st. */
    private fun duo(away: Int = 0, mode: PlayMode = PlayMode.LOCAL_AD_HOC): Duo {
        val hub = TbHub(); val clock = TbClock()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val host = ToyBaseballGame(Random(3), clock); host.awaySeat = away
        host.init(GameContext(mode, roster, 0, ht)); host.startMatch()
        val guest = ToyBaseballGame(Random(9), clock)
        guest.init(GameContext(mode, roster, 1, gt))
        return Duo(hub, Node(host, ht), Node(guest, gt))
    }

    private fun syncs(t: TbFakeTransport) = t.sent.map { dec(it.payload) }.filterIsInstance<ToyBaseballNetMessage.StateSync>()
    private val swing = BaseballSwing(false, 1, 1, PitchType.FASTBALL, false)

    @Test fun theGuestReceivesTheGameAndEveryPitchResolvesOnTheHost() {
        val d = duo(away = 0) // host bats, guest pitches
        assertEquals(1, d.guest.game.state.value!!.pitcherSeat)
        assertEquals(BaseballReject.NONE, d.guest.game.pitch(1, PitchType.FASTBALL, 2, 2))
        assertEquals("SWING", d.host.game.state.value!!.phase)
        assertEquals(BaseballReject.NONE, d.host.game.swing(0, BaseballSwing.TAKE))
        assertEquals(BaseballOutcome.CALLED_STRIKE, d.host.game.state.value!!.last!!.outcome)
        assertEquals(BaseballOutcome.CALLED_STRIKE, d.guest.game.state.value!!.last!!.outcome)
        assertEquals(d.host.game.state.value!!.eventSerial, d.guest.game.state.value!!.eventSerial)
        assertTrue(d.hub.senderLies.isEmpty())
    }

    @Test fun theBatterNeverReceivesThePitchersPendingChoiceButThePitcherDoes() {
        // Host pitches, guest bats: the sync sent to the guest while a pitch is pending must not carry it.
        val a = duo(away = 1)
        assertEquals(0, a.host.game.state.value!!.pitcherSeat)
        a.host.game.pitch(0, PitchType.CURVEBALL, 3, 2)
        assertNotNull(a.host.game.state.value!!.pending) // the host's own device holds it
        val toGuest = syncs(a.host.transport).last()
        assertNull(toGuest.state.pending)
        assertNull(a.guest.game.state.value!!.pending)
        assertTrue(a.host.transport.sent.none { String(it.payload, Charsets.UTF_8).contains("CURVEBALL") })
        // Guest pitches, host bats: the host's local state hides it, the guest sees its own.
        val b = duo(away = 0)
        b.guest.game.pitch(1, PitchType.SLIDER, 1, 3)
        assertNull(b.host.game.state.value!!.pending)
        assertEquals(PendingPitch("SLIDER", 1, 3), b.guest.game.state.value!!.pending)
        // Even a forged request for state cannot extract it: the host answers each seat only with its own view.
        b.host.transport.inject("g", enc(ToyBaseballNetMessage.RequestState))
        assertTrue(syncs(b.host.transport).all { it.state.pending == null || b.host.game.state.value!!.pitcherSeat == 1 })
    }

    @Test fun onlyTheRightSeatMayThrowOrSwing() {
        val d = duo(away = 0)
        assertEquals(BaseballReject.NOT_YOUR_SEAT, d.guest.game.pitch(0, PitchType.FASTBALL, 2, 2)) // not this device's seat
        assertEquals(BaseballReject.NOT_YOUR_SEAT, d.host.game.pitch(1, PitchType.FASTBALL, 2, 2))
        assertEquals(BaseballReject.NOT_BATTER, d.guest.game.swing(1, BaseballSwing.TAKE).let { d.guest.game.lastReject.value!!.first })
        // The guest is the pitcher; it cannot swing, and its swing intent is refused by the host too.
        d.host.transport.inject("g", enc(ToyBaseballNetMessage.SwingIntent(true, 0, 0, "FASTBALL", false)))
        assertEquals("PITCH", d.host.game.state.value!!.phase)
        assertTrue(syncs(d.host.transport).isNotEmpty())
        val rejected = d.host.transport.sent.map { dec(it.payload) }.filterIsInstance<ToyBaseballNetMessage.Rejected>()
        assertTrue(rejected.any { it.reason == BaseballReject.NOT_BATTER.name })
    }

    @Test fun intentsOnlyCountFromTheSeatTheTransportEstablished() {
        val d = duo(away = 0)
        for (sender in listOf("h", "x", "")) d.host.transport.inject(sender, enc(ToyBaseballNetMessage.PitchIntent("FASTBALL", 2, 2)))
        assertEquals("PITCH", d.host.game.state.value!!.phase)
        d.host.transport.inject("g", enc(ToyBaseballNetMessage.PitchIntent("NUKE", 2, 2)))
        d.host.transport.inject("g", enc(ToyBaseballNetMessage.PitchIntent("FASTBALL", 9, 2)))
        d.host.transport.inject("g", "{bad".toByteArray())
        assertEquals("PITCH", d.host.game.state.value!!.phase)
        d.host.transport.inject("g", enc(ToyBaseballNetMessage.PitchIntent("FASTBALL", 2, 2)))
        assertEquals("SWING", d.host.game.state.value!!.phase)
        // A swing "from" the host or a stranger is not a swing from the batter's transport identity... the host's own seat swings locally only.
        d.host.transport.inject("x", enc(ToyBaseballNetMessage.SwingIntent(true, 0, 0, "FASTBALL", false)))
        assertEquals("SWING", d.host.game.state.value!!.phase)
        assertFalse(Json.encodeToString(ToyBaseballNetMessage.serializer(), ToyBaseballNetMessage.PitchIntent("FASTBALL", 2, 2)).contains("seat", ignoreCase = true))
    }

    @Test fun forgedStaleOrMalformedSyncsAreNotAdopted() {
        val d = duo(away = 1)
        d.host.game.pitch(0, PitchType.FASTBALL, 2, 2)
        d.guest.game.swing(1, BaseballSwing.TAKE)
        val good = syncs(d.host.transport).last()
        val serial = d.guest.game.state.value!!.eventSerial
        val forged = enc(ToyBaseballNetMessage.StateSync(9_999, good.state.copy(over = true, winnerPlayerId = "h", score = listOf(9, 0), lineScore = listOf(listOf(9), listOf(0)), eventSerial = serial + 9)))
        d.guest.transport.inject("x", forged); d.guest.transport.inject("g", forged); d.host.transport.inject("g", forged)
        assertFalse(d.guest.game.state.value!!.over); assertFalse(d.host.game.state.value!!.over)
        d.guest.transport.inject("h", enc(good.copy(state = good.state.copy(eventSerial = serial + 7)))) // same version: stale
        assertEquals(serial, d.guest.game.state.value!!.eventSerial)
        val bads = listOf(
            good.state.copy(outs = 3), good.state.copy(balls = 9), good.state.copy(batterSeat = 0, pitcherSeat = 0),
            good.state.copy(score = listOf(5, 5)),                                    // does not match the line score
            good.state.copy(players = good.state.players.map { it.copy(playerId = "mallory") }),
            good.state.copy(winnerPlayerId = "nobody"), good.state.copy(innings = 40),
            good.state.copy(phase = "BOGUS"), good.state.copy(bases = listOf(true)),
            good.state.copy(pending = PendingPitch("NUKE", 2, 2), phase = "SWING"),
            good.state.copy(pending = PendingPitch("FASTBALL", 2, 2), phase = "SWING", pitcherSeat = 0, batterSeat = 1)  // a batter must never hold the pending pitch
        )
        for ((i, st) in bads.withIndex()) {
            d.guest.transport.inject("h", enc(ToyBaseballNetMessage.StateSync(good.version + 10 + i, st)))
            assertEquals("bad $i", good.state.outs, d.guest.game.state.value!!.outs)
            assertNull("bad $i", d.guest.game.state.value!!.pending)
            assertEquals("bad $i", good.state.players.map { it.playerId }, d.guest.game.state.value!!.players.map { it.playerId })
        }
        d.guest.transport.inject("h", "{nope".toByteArray())
        assertFalse(d.guest.game.state.value!!.over)
    }

    @Test fun reconnectAndRejoinResendTheGame() {
        val d = duo(away = 0)
        d.guest.game.pitch(1, PitchType.FASTBALL, 2, 2)
        val n = syncs(d.host.transport).size
        d.host.transport.fireLeft("g")
        assertTrue(d.host.game.opponentLeft.value)
        assertEquals(BaseballReject.NOT_YOUR_SEAT, d.host.game.swing(0, BaseballSwing.TAKE))
        d.host.transport.fireJoined("g")
        assertFalse(d.host.game.opponentLeft.value)
        assertTrue(syncs(d.host.transport).size > n)
        d.guest.transport.fireReconnected()
        assertEquals(d.host.game.state.value!!.eventSerial, d.guest.game.state.value!!.eventSerial)
    }

    @Test fun aWholeNetworkedGameFinishesWithSessionCountsOnBothSides() {
        val d = duo(away = 0)
        var guard = 0
        while (!d.host.game.state.value!!.over && guard++ < 400) {
            val s = d.host.game.state.value!!
            val pitcher = if (s.pitcherSeat == 0) d.host else d.guest
            val batter = if (s.batterSeat == 0) d.host else d.guest
            pitcher.game.pitch(s.pitcherSeat, PitchType.FASTBALL, 2, 2)
            batter.game.swing(s.batterSeat, BaseballSwing.TAKE)
        }
        assertTrue(d.host.game.state.value!!.over)
        assertTrue(d.guest.game.state.value!!.over)
        assertEquals(d.host.game.state.value!!.sessionDraws, d.guest.game.state.value!!.sessionDraws)
        d.guest.game.playAgain()
        assertFalse(d.host.game.state.value!!.over)
        assertEquals(0, d.host.game.state.value!!.pitcherSeat) // the sides swapped: seat 1 bats first now
    }
}
