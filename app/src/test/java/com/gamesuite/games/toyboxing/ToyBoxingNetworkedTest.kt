package com.gamesuite.games.toyboxing

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Toy Boxing: the host simulates, guests send intents, identity comes from the transport. */
class ToyBoxingNetworkedTest {
    private val roster = listOf(PlayerInfo("h", "Hana"), PlayerInfo("g", "Gus"))

    private class Node(val game: ToyBoxingGame, val transport: TbFakeTransport)
    private class Duo(val hub: TbHub, val clock: TbClock, val host: Node, val guest: Node) {
        fun adv(ms: Long) { var left = ms; while (left > 0) { val d = minOf(25L, left); clock.advance(d); host.game.tick(); guest.game.tick(); left -= d } }
    }

    private fun enc(m: ToyBoxingNetMessage) = Json.encodeToString(ToyBoxingNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = Json.decodeFromString(ToyBoxingNetMessage.serializer(), String(b, Charsets.UTF_8))

    private fun duo(mode: PlayMode = PlayMode.LOCAL_AD_HOC, live: Boolean = true, assist: Boolean = false): Duo {
        val hub = TbHub(); val clock = TbClock()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val host = ToyBoxingGame(Random(3), clock); host.assist = assist
        host.init(GameContext(mode, roster, 0, ht)); host.startMatch()
        val guest = ToyBoxingGame(Random(9), clock)
        guest.init(GameContext(mode, roster, 1, gt)) // asks the host for state; the host answers over the hub
        val d = Duo(hub, clock, Node(host, ht), Node(guest, gt))
        if (live && !assist) d.adv(3_000)
        return d
    }

    private fun syncs(t: TbFakeTransport) = t.sent.map { dec(it.payload) }.filterIsInstance<ToyBoxingNetMessage.StateSync>()

    @Test fun theGuestReceivesTheRingAndTracksTheHost() {
        val d = duo()
        val hs = d.host.game.state.value!!; val gs = d.guest.game.state.value!!
        assertEquals("FIGHT", gs.phase)
        assertEquals(hs.fighters, gs.fighters); assertEquals(hs.round, gs.round)
        assertEquals(roster.map { it.playerId }, gs.players.map { it.playerId })
        assertTrue(d.hub.senderLies.isEmpty())
    }

    @Test fun aGuestPunchIsSimulatedByTheHostAndStreamedBack() {
        val d = duo()
        assertEquals(BoxingReject.NONE, d.guest.game.play(1, BoxingAction.HOOK))
        assertEquals("HOOK", d.host.game.state.value!!.fighters[1].action)
        d.adv(500)
        assertEquals(82, d.host.game.state.value!!.fighters[0].hp)
        assertEquals(82, d.guest.game.state.value!!.fighters[0].hp) // the guest saw the host's result
        assertEquals(BoxingEvent.HOOK_HIT, d.guest.game.state.value!!.event)
        assertEquals(d.host.game.state.value!!.eventSerial, d.guest.game.state.value!!.eventSerial)
    }

    @Test fun theGuestCannotActForTheHostSeatOrBeforeTheBell() {
        val early = duo(live = false)
        assertEquals(BoxingReject.NOT_STARTED, early.guest.game.play(1, BoxingAction.JAB))
        val d = duo()
        assertEquals(BoxingReject.NOT_YOUR_SEAT, d.guest.game.play(0, BoxingAction.JAB))
        assertEquals(BoxingReject.NOT_YOUR_SEAT, d.host.game.play(1, BoxingAction.JAB)) // the host cannot punch for the guest either
        assertEquals("IDLE", d.host.game.state.value!!.fighters[0].action)
    }

    @Test fun intentsOnlyCountFromTheSeatTheTransportEstablished() {
        val d = duo()
        // Someone claiming to be the host (or a stranger) sends the guest's intent to the host: ignored.
        for (sender in listOf("h", "x", "")) d.host.transport.inject(sender, enc(ToyBoxingNetMessage.Intent("JAB")))
        d.adv(200)
        assertEquals(100, d.host.game.state.value!!.fighters[0].hp)
        assertEquals("IDLE", d.host.game.state.value!!.fighters[1].action)
        // A forged action name from the real guest is ignored without crashing.
        d.host.transport.inject("g", enc(ToyBoxingNetMessage.Intent("NUKE")))
        d.host.transport.inject("g", "{garbage".toByteArray())
        assertEquals("IDLE", d.host.game.state.value!!.fighters[1].action)
        // The seat is not in the payload at all: the message type has no such field.
        assertFalse(Json.encodeToString(ToyBoxingNetMessage.serializer(), ToyBoxingNetMessage.Intent("JAB")).contains("seat", ignoreCase = true))
    }

    @Test fun forgedStaleOrMalformedSyncsAreNotAdopted() {
        val d = duo()
        val good = syncs(d.host.transport).last()
        val serial = d.guest.game.state.value!!.eventSerial
        // A forged StateSync claiming the bout is over, from a stranger and from the guest itself.
        val forged = enc(ToyBoxingNetMessage.StateSync(9_999, good.state.copy(over = true, event = BoxingEvent.KO, eventSerial = serial + 50, winnerPlayerId = "h")))
        d.guest.transport.inject("x", forged)
        d.guest.transport.inject("g", forged)
        d.host.transport.inject("g", forged)
        assertFalse(d.guest.game.state.value!!.over); assertFalse(d.host.game.state.value!!.over)
        // Same version again: stale.
        d.guest.transport.inject("h", enc(good.copy(state = good.state.copy(eventSerial = serial + 7))))
        assertEquals(serial, d.guest.game.state.value!!.eventSerial)
        // Out-of-range or inconsistent states are refused even from the host.
        val badHp = good.state.copy(fighters = listOf(good.state.fighters[0].copy(hp = 999), good.state.fighters[1]))
        val badSeat = good.state.copy(downSeat = 5)
        val badPlayers = good.state.copy(players = good.state.players.map { it.copy(playerId = "mallory") })
        val badWinner = good.state.copy(winnerPlayerId = "nobody")
        val badAction = good.state.copy(fighters = listOf(good.state.fighters[0].copy(action = "FLY"), good.state.fighters[1]))
        for ((i, st) in listOf(badHp, badSeat, badPlayers, badWinner, badAction).withIndex()) {
            d.guest.transport.inject("h", enc(ToyBoxingNetMessage.StateSync(good.version + 10 + i, st)))
            assertEquals("bad state $i", 100, d.guest.game.state.value!!.fighters[0].hp)
            assertEquals("bad state $i", good.state.players.map { it.playerId }, d.guest.game.state.value!!.players.map { it.playerId })
        }
        d.guest.transport.inject("h", "{nope".toByteArray())
        assertFalse(d.guest.game.state.value!!.over)
    }

    @Test fun anIdleRingDoesNotFloodTheRelay() {
        val d = duo()
        val before = d.host.transport.sent.size
        d.adv(3_000) // three quiet seconds: only the throttled stamina/clock updates
        val sent = d.host.transport.sent.size - before
        assertTrue("sent $sent", sent <= 40)
    }

    @Test fun reconnectAndRejoinResendTheCurrentRing() {
        val d = duo()
        d.guest.game.play(1, BoxingAction.JAB); d.adv(300)
        val v = syncs(d.host.transport).size
        d.host.transport.fireLeft("g")
        assertTrue(d.host.game.opponentLeft.value)
        assertEquals(BoxingReject.NOT_YOUR_SEAT, d.host.game.play(0, BoxingAction.JAB))
        d.host.transport.fireJoined("g")
        assertFalse(d.host.game.opponentLeft.value)
        assertTrue(syncs(d.host.transport).size > v)
        d.guest.transport.fireReconnected()
        assertEquals(d.host.game.state.value!!.fighters[1].hp, d.guest.game.state.value!!.fighters[1].hp)
    }

    @Test fun aGuestInTheTurnBasedAssistDefendsOverTheNetwork() {
        val d = duo(assist = true)
        assertEquals("FIGHT", d.guest.game.state.value!!.phase)
        assertEquals(BoxingReject.NONE, d.host.game.play(0, BoxingAction.HOOK))
        assertEquals("DEFEND", d.guest.game.state.value!!.assistStage)
        assertEquals(BoxingReject.NONE, d.guest.game.play(1, BoxingAction.DODGE))
        assertEquals(BoxingEvent.DODGED, d.host.game.state.value!!.event)
        assertEquals(BoxingEvent.DODGED, d.guest.game.state.value!!.event)
        // Out of turn: the host tells the guest why.
        assertEquals(BoxingReject.NONE, d.guest.game.play(1, BoxingAction.BLOCK)) // sent; refused by the host (not a defence stage)
        assertNotNull(d.guest.game.lastReject.value)
    }

    @Test fun playAgainOnlyAfterTheBoutAndOnlyFromThePlayersTheHostKnows() {
        val d = duo()
        d.guest.game.playAgain() // not over yet: nothing
        assertFalse(d.host.game.state.value!!.over)
        d.guest.transport.inject("g", enc(ToyBoxingNetMessage.RequestPlayAgain)) // loops back to nowhere
        d.host.transport.inject("x", enc(ToyBoxingNetMessage.RequestPlayAgain))
        assertEquals("FIGHT", d.host.game.state.value!!.phase)
    }
}
