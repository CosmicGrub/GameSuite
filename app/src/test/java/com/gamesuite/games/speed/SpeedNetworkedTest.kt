package com.gamesuite.games.speed

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Speed: the host orders plays by arrival and hides its own hand. */
class SpeedNetworkedTest {
    private val K = SpeedTestKit
    private val roster = listOf(PlayerInfo("h", "Hana"), PlayerInfo("g", "Gus"))

    private class Node(val game: SpeedGame, val transport: SmFakeTransport)
    private class Pair2(val hub: SmHub, val clock: FakeClock, val host: Node, val guest: Node)

    private fun enc(m: SpeedNetMessage) = Json.encodeToString(SpeedNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = Json.decodeFromString(SpeedNetMessage.serializer(), String(b, Charsets.UTF_8))

    private fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC, seed: Int = 3, live: Boolean = true): Pair2 {
        val hub = SmHub(); val clock = FakeClock()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val host = SpeedGame(Random(seed), clock)
        host.init(GameContext(mode, roster, 0, ht))
        host.startMatch()
        val guest = SpeedGame(Random(999), clock)
        guest.init(GameContext(mode, roster, 1, gt)) // asks the host for state; the host answers over the hub
        if (live) { clock.advance(SpeedRules.COUNTDOWN_MS); host.tick() }
        return Pair2(hub, clock, Node(host, ht), Node(guest, gt))
    }

    @Test fun theGuestReceivesTheTableButNeverTheHostsCards() {
        val p = pair(live = false)
        val gs = p.guest.game.state.value!!
        assertEquals(p.host.game.state.value!!.pileTops, gs.pileTops)
        assertEquals(p.host.game.state.value!!.drawCounts, gs.drawCounts)
        assertTrue(gs.hands[0].all { it === SpeedCards.HIDDEN })       // the host's hand: face-down placeholders only
        assertTrue(gs.hands[1].all { it != null && it.id >= 0 })        // its own hand: real cards
        assertEquals(p.host.game.state.value!!.hands[1], gs.hands[1])
        // The bytes on the wire carry no id of the host's hand.
        val sync = p.hub.endpoints.getValue("g").let { p.host.transport.sent.map { dec(it.payload) }.filterIsInstance<SpeedNetMessage.StateSync>().last() }
        assertTrue(sync.state.hands[0].all { it == SpeedNetState.HIDDEN || it == SpeedNetState.EMPTY })
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test fun aGuestPlayIsAppliedByTheHostAndSyncedBack() {
        val p = pair()
        var done = false
        for (seed in 1..80) {
            val q = pair(seed = seed)
            val play = SpeedGame.legalPlaysIn(q.guest.game.state.value!!, 1).firstOrNull() ?: continue
            assertEquals(SpeedReject.NONE, q.guest.game.play(1, play.cardId, play.pile))
            val hs = q.host.game.state.value!!
            assertEquals(play.cardId, hs.pileTops[play.pile]!!.id)
            assertEquals(1, hs.eventSeat)
            assertEquals(hs.playSerial, q.guest.game.state.value!!.playSerial)
            assertEquals(hs.pileTops, q.guest.game.state.value!!.pileTops)
            assertEquals((0 until 52).toList(), q.host.game.allCardIds())
            done = true; break
        }
        assertTrue(done)
        assertNotNull(p)
    }

    @Test fun simultaneousPlaysAreOrderedByArrivalAndTheLaterOneIsToldItLost() {
        val hubClockSeeds = 1..200
        var tested = false
        for (seed in hubClockSeeds) {
            val clock = FakeClock(); val hub = SmHub()
            val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
            val host = SpeedGame(Random(seed), clock); host.init(GameContext(PlayMode.LOCAL_AD_HOC, roster, 0, ht))
            val guest = SpeedGame(Random(1), clock); guest.init(GameContext(PlayMode.LOCAL_AD_HOC, roster, 1, gt))
            // craft a board through the host's own engine: use a dealt board and look for a shared target pile
            host.startMatch(); clock.advance(SpeedRules.COUNTDOWN_MS); host.tick()
            val s = host.state.value!!
            val hp = SpeedGame.legalPlaysIn(s, 0); val gp = SpeedGame.legalPlaysIn(s, 1)
            val pair = hp.flatMap { a -> gp.map { b -> a to b } }.firstOrNull { (a, b) ->
                a.pile == b.pile && a.cardId != b.cardId && !SpeedRules.canPlay(SpeedCards.all[b.cardId], SpeedCards.all[a.cardId])
            } ?: continue
            assertEquals(SpeedReject.NONE, host.play(0, pair.first.cardId, pair.first.pile)) // arrives first
            clock.advance(60)
            guest.play(1, pair.second.cardId, pair.second.pile)                               // arrives 60 ms later: it fit the old top
            assertEquals(SpeedReject.LOST_RACE, guest.lastReject.value?.reason)
            assertTrue(guest.state.value!!.hands[1].any { it?.id == pair.second.cardId })    // still in hand
            assertEquals(pair.first.cardId, host.state.value!!.pileTops[pair.first.pile]!!.id)
            assertEquals((0 until 52).toList(), host.allCardIds())
            tested = true; break
        }
        assertTrue("no seed produced a shared target", tested)
    }

    @Test fun aGuestCannotPlayBeforeTheGoOrForTheOtherSeat() {
        val p = pair(live = false)
        val card = p.guest.game.state.value!!.hands[1].first()!!
        assertEquals(SpeedReject.NOT_STARTED, p.guest.game.play(1, card.id, 0))
        assertEquals(SpeedReject.NOT_YOUR_SEAT, p.guest.game.play(0, card.id, 0))
        assertEquals(SpeedReject.NOT_YOUR_SEAT, p.host.game.play(1, card.id, 0)) // the host cannot play the guest's seat either
    }

    @Test fun requestsOnlyCountFromTheSeatTheTransportEstablished() {
        val p = pair()
        val hostCard = p.host.game.state.value!!.hands[0].first { it != null }!!
        val before = p.host.game.state.value!!.playSerial
        // Someone claiming to be the host (or a stranger) sends a play request to the host: ignored.
        for (sender in listOf("h", "x", "")) p.host.transport.inject(sender, enc(SpeedNetMessage.PlayRequest(hostCard.id, 0)))
        p.host.transport.inject("x", enc(SpeedNetMessage.FlipRequest))
        assertEquals(before, p.host.game.state.value!!.playSerial)
        // A forged StateSync (from the guest, to the host, or from a stranger to the guest) is not adopted.
        val forged = p.host.game.state.value!!.let { enc(SpeedNetMessage.StateSync(9_999, SpeedNetState(
            players = it.players.map { pl -> SpeedNetPlayer(pl.playerId, pl.displayName) }, hands = List(2) { List(5) { -1 } },
            drawCounts = listOf(0, 0), reserveCounts = listOf(0, 0), pileTops = listOf(0, 0), pileSizes = listOf(1, 1),
            phase = "OVER", assist = false, assistTurn = 0, flipReady = listOf(false, false), event = "win", eventSeat = 0,
            eventPile = null, eventCardId = null, playSerial = 99, flipSerial = 0, dealSerial = 1, boardOver = true,
            winnerPlayerId = "h", sessionWins = emptyMap(), sessionDraws = 0))) }
        p.guest.transport.inject("x", forged)
        p.guest.transport.inject("g", forged)
        p.host.transport.inject("g", forged)
        assertFalse(p.guest.game.state.value!!.boardOver)
        assertFalse(p.host.game.state.value!!.boardOver)
    }

    @Test fun aStaleOrMalformedStateIsIgnored() {
        val p = pair()
        val good = p.host.transport.sent.map { dec(it.payload) }.filterIsInstance<SpeedNetMessage.StateSync>().last()
        val serial = p.guest.game.state.value!!.playSerial
        // same version again (stale)
        p.guest.transport.inject("h", enc(good.copy(state = good.state.copy(playSerial = serial + 50))))
        assertEquals(serial, p.guest.game.state.value!!.playSerial)
        // a state that leaks a real card into the other seat's hand is refused even from the host
        val leaky = good.copy(version = good.version + 10, state = good.state.copy(hands = listOf(listOf(3, 4, 5, 6, 7), good.state.hands[1])))
        p.guest.transport.inject("h", enc(leaky))
        assertTrue(p.guest.game.state.value!!.hands[0].all { it === SpeedCards.HIDDEN })
        // garbage
        p.guest.transport.inject("h", "{nope".toByteArray())
        p.guest.transport.inject("h", enc(good.copy(version = good.version + 11, state = good.state.copy(phase = "BOGUS"))))
        assertEquals(serial, p.guest.game.state.value!!.playSerial)
    }

    @Test fun aFlipRequestFromTheGuestFlipsWhenBothAreStuck() {
        var found = false
        for (seed in 1..600) {
            val p = pair(mode = PlayMode.ONLINE, seed = seed)
            val s = p.host.game.state.value!!
            if (!SpeedGame.isStuck(s, 0) || !SpeedGame.isStuck(s, 1)) continue
            assertEquals(SpeedReject.NONE, p.guest.game.requestFlip(1))          // sent to the host
            assertEquals(listOf(false, true), p.host.game.state.value!!.flipReady)
            val f = p.host.game.state.value!!.flipSerial
            assertEquals(SpeedReject.NONE, p.host.game.requestFlip(0))
            assertEquals(f + 1, p.host.game.state.value!!.flipSerial)
            assertEquals(f + 1, p.guest.game.state.value!!.flipSerial)
            assertEquals((0 until 52).toList(), p.host.game.allCardIds())
            found = true; break
        }
        assertTrue("no seed dealt a stuck opening", found)
    }

    @Test fun theGuestCanAskForAFreshBoardOnlyAfterOneEnds() {
        val p = pair()
        val v = p.host.game.state.value!!.dealSerial
        p.guest.game.playAgain() // board not over: ignored
        assertEquals(v, p.host.game.state.value!!.dealSerial)
        p.guest.transport.inject("g", enc(SpeedNetMessage.RequestPlayAgain)) // harmless noise
        assertEquals(v, p.host.game.state.value!!.dealSerial)
    }

    @Test fun leavingAndRejoiningResendsTheTable() {
        val p = pair()
        p.host.transport.fireLeft("g")
        assertTrue(p.host.game.opponentLeft.value)
        assertEquals(SpeedReject.NOT_YOUR_SEAT, p.host.game.play(0, p.host.game.state.value!!.hands[0].first { it != null }!!.id, 0))
        val n = p.host.transport.sent.size
        p.host.transport.fireJoined("g")
        assertFalse(p.host.game.opponentLeft.value)
        assertTrue(p.host.transport.sent.size > n)
        p.guest.transport.fireReconnected()
        assertNotNull(p.guest.game.state.value)
        assertNull(p.host.game.hint(0)) // no hints in a networked game
    }

    @Test fun onlyOfflineBoardsAreSaved() {
        val p = pair()
        assertNull(p.host.game.snapshot())
        assertNull(p.guest.game.snapshot())
    }
}
