package com.gamesuite.games.hanafuda

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

/** Host-authoritative Nearby / Online Hanafuda: the host owns the shuffle and the deck, guests only ask. */
class HanafudaNetworkedTest {
    private val roster = listOf(PlayerInfo("h", "Host"), PlayerInfo("g", "Guest"))

    private class Table(val hub: HfHub, val host: HanafudaGame, val guest: HanafudaGame, val hostT: HfFakeTransport, val guestT: HfFakeTransport)

    private fun encode(m: HanafudaNetMessage) = Json.encodeToString(HanafudaNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun decode(b: ByteArray) = Json.decodeFromString(HanafudaNetMessage.serializer(), String(b, Charsets.UTF_8))

    private fun table(seed: Int = 1, rounds: Int = 3, mode: PlayMode = PlayMode.LOCAL_AD_HOC, hold: Boolean = false, start: Boolean = true): Table {
        val hub = HfHub()
        hub.held = hold
        val ht = hub.endpoint("h")
        val gt = hub.endpoint("g")
        val host = HanafudaGame(Random(seed)).also { it.totalRounds = rounds }
        val guest = HanafudaGame(Random(seed + 1000))
        host.init(GameContext(mode, roster, 0, ht))
        guest.init(GameContext(mode, roster, 1, gt))
        if (start) host.startMatch()
        return Table(hub, host, guest, ht, gt)
    }

    /** One random legal action by whichever side is on turn. */
    private fun step(t: Table, rng: Random) {
        val mine = if (t.host.state.value!!.round.turn == 0) t.host else t.guest
        val s = mine.state.value!!
        val r = s.round
        when (r.phase) {
            KoiPhase.PLAY_HAND -> KoiRules.legalPlays(r).random(rng).let { assertTrue(mine.playHand(it.handCard, it.fieldCard)) }
            KoiPhase.DRAW_READY -> assertTrue(mine.drawFromDeck())
            KoiPhase.CHOOSE_DRAW_MATCH -> assertTrue(mine.chooseMatch(KoiRules.drawChoices(r).random(rng)))
            KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> assertTrue(mine.decide(rng.nextBoolean()))
            KoiPhase.ROUND_OVER -> assertTrue(if (s.finished) mine.playAgain() else mine.nextRound())
        }
    }

    private fun assertInSync(t: Table) {
        val h = t.host.state.value!!
        val g = t.guest.state.value!!
        assertEquals(h.roundNumber, g.roundNumber)
        assertEquals(h.totals, g.totals)
        assertEquals(h.roundScores, g.roundScores)
        assertEquals(h.round.turn, g.round.turn)
        assertEquals(h.round.phase, g.round.phase)
        assertEquals(h.round.field, g.round.field)
        assertEquals(h.round.captured, g.round.captured)
        assertEquals(h.round.hands[0].size, g.round.hands[0].size)
        assertEquals(h.round.hands[1].size, g.round.hands[1].size)
        assertEquals(h.round.deck.size, g.round.deck.size)
        assertEquals(h.round.drawn, g.round.drawn)
        assertEquals(h.finished, g.finished)
    }

    // ---- hidden information ---------------------------------------------------------------------

    @Test fun theGuestReceivesItsOwnHandButNeverTheHostsHandOrTheDeck() {
        val t = table()
        val g = t.guest.state.value!!
        assertTrue(g.round.hands[1].all { it >= 0 })
        assertEquals(8, g.round.hands[1].size)
        assertTrue("host hand leaked", g.round.hands[0].all { it < 0 })
        assertTrue("deck leaked", g.round.deck.all { it < 0 })
        assertEquals(24, g.round.deck.size)
        assertInSync(t)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun noMessageTheHostEverSendsCarriesAHiddenCard() {
        val t = table(seed = 5, rounds = 3)
        val rng = Random(5)
        var guard = 0
        while (!t.host.state.value!!.finished) {
            step(t, rng)
            assertTrue(++guard < 600)
        }
        val syncs = t.hostT.sent.map { decode(it.payload) }.filterIsInstance<HanafudaNetMessage.StateSync>()
        assertTrue(syncs.size > 20)
        for (m in syncs) {
            val r = m.state.round
            assertTrue("host hand in a sync", r.hands[0].all { it < 0 })
            assertTrue("deck in a sync", r.deck.all { it < 0 })
        }
        // Every sync goes to the guest only.
        assertTrue(t.hostT.sent.all { it.to == "g" })
    }

    @Test fun theHostsOwnViewHidesTheGuestsHandToo() {
        val t = table()
        val h = t.host.state.value!!
        assertTrue(h.round.hands[0].all { it >= 0 })
        assertTrue(h.round.hands[1].all { it < 0 })
        assertTrue(h.round.deck.all { it < 0 })
    }

    @Test fun networkedPlayCannotBeSnapshottedOrUndone() {
        val t = table()
        assertNull(t.guest.snapshot())
        assertNull(t.host.snapshot())
        assertFalse(t.host.canUndo)
        assertFalse(t.guest.canUndo)
    }

    // ---- a guest plays through the host ---------------------------------------------------------

    @Test fun theGuestWaitsForTheHostsFirstStateAndThenFollowsEveryChange() {
        val t = table(start = false)
        assertNull(t.guest.state.value)
        t.host.startMatch()
        assertInSync(t)
        val play = KoiRules.legalPlays(t.host.state.value!!.round).first()
        t.host.playHand(play.handCard, play.fieldCard)
        assertInSync(t)
        t.host.drawFromDeck()
        assertInSync(t)
    }

    @Test fun aLateGuestAsksForTheStateAndGetsIt() {
        val hub = HfHub()
        val ht = hub.endpoint("h")
        val host = HanafudaGame(Random(3))
        host.init(GameContext(PlayMode.ONLINE, roster, 0, ht))
        host.startMatch()
        val gt = hub.endpoint("g")
        val guest = HanafudaGame(Random(4))
        guest.init(GameContext(PlayMode.ONLINE, roster, 1, gt)) // sends RequestState
        assertNotNull(guest.state.value)
        assertEquals(host.state.value!!.round.field, guest.state.value!!.round.field)
    }

    @Test fun theGuestsMovesGoThroughTheHostAndTheGuestNeverMutatesItsOwnTable() {
        val t = table(seed = 12)
        val rng = Random(12)
        var guard = 0
        while (t.host.state.value!!.round.turn == 0) {
            step(t, rng)
            assertTrue(++guard < 50)
        }
        assertEquals(1, t.guest.state.value!!.round.turn)
        val before = t.host.state.value
        val play = KoiRules.legalPlays(t.guest.state.value!!.round).first()
        val sentBefore = t.guestT.sent.size
        assertTrue(t.guest.playHand(play.handCard, play.fieldCard))
        assertTrue(t.guestT.sent.size > sentBefore)
        assertTrue(decode(t.guestT.sent[sentBefore].payload) is HanafudaNetMessage.PlayHand)
        assertTrue(t.host.state.value != before)
        assertInSync(t)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotActOnTheHostsTurn() {
        val t = table()
        val guestCard = t.guest.state.value!!.round.hands[1].first()
        assertFalse(t.guest.playHand(guestCard))
        assertEquals(HanafudaEvent.Rejected(HanafudaReject.NOT_YOUR_TURN), t.guest.event.value)
        // Forced onto the wire anyway: the host ignores it.
        val before = t.host.state.value
        t.guestT.send("g", "h", encode(HanafudaNetMessage.PlayHand(guestCard)))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.DrawCard))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.Decide(true)))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.ChooseMatch(0)))
        assertEquals(before, t.host.state.value)
    }

    @Test fun anIllegalGuestRequestOnTheGuestsTurnIsIgnored() {
        val t = table(seed = 12)
        val rng = Random(12)
        while (t.host.state.value!!.round.turn == 0) step(t, rng)
        val before = t.host.state.value
        val hostCardIds = t.host.state.value!!.round.hands[0]
        // A card that is not in the guest's hand, out-of-range ids, and a wrong-phase request.
        val notHis = (0 until 48).first { it !in t.guest.state.value!!.round.hands[1] }
        t.guestT.send("g", "h", encode(HanafudaNetMessage.PlayHand(notHis)))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.PlayHand(-5)))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.PlayHand(999)))
        t.guestT.send("g", "h", encode(HanafudaNetMessage.DrawCard)) // wrong phase
        assertEquals(before, t.host.state.value)
        assertTrue(hostCardIds.isNotEmpty() || true)
    }

    // ---- identity -------------------------------------------------------------------------------

    @Test fun requestsAndStatesFromAnyoneButTheRightSeatAreIgnored() {
        val t = table()
        val before = t.host.state.value
        val gBefore = t.guest.state.value
        val intruder = t.hub.endpoint("mallory")
        val card = t.host.state.value!!.round.hands[0].first()
        // An intruder claims to be the guest...
        intruder.send("g", "h", encode(HanafudaNetMessage.PlayHand(card)))
        // ...someone forges a state at the guest...
        val forged = t.guest.state.value!!.copy(totals = listOf(99, 0))
        intruder.send("h", "g", encode(HanafudaNetMessage.StateSync(1000, forged)))
        // ...and the guest forges one at the host.
        t.guestT.send("g", "h", encode(HanafudaNetMessage.StateSync(1000, forged)))
        assertEquals(before, t.host.state.value)
        assertEquals(gBefore, t.guest.state.value)
    }

    @Test fun anIllegalStateFromTheRealHostIsRejectedByTheGuest() {
        val t = table()
        val good = t.guest.state.value!!
        fun push(version: Int, s: HanafudaState) = t.hostT.send("h", "g", encode(HanafudaNetMessage.StateSync(version, s)))
        push(500, good.copy(players = good.players.reversed()))
        push(501, good.copy(totalRounds = 7))
        push(502, good.copy(round = good.round.copy(turn = 5)))
        push(503, good.copy(round = good.round.copy(hands = listOf(emptyList()))))
        push(504, good.copy(round = good.round.copy(field = listOf(999))))
        assertEquals(good, t.guest.state.value)
        // A well-formed one is still accepted afterwards (the rejected versions did not poison the counter).
        push(505, good.copy(totals = listOf(3, 1)))
        assertEquals(listOf(3, 1), t.guest.state.value!!.totals)
    }

    @Test fun malformedPayloadsAreIgnored() {
        val t = table()
        val g = t.guest.state.value
        val h = t.host.state.value
        t.hostT.send("h", "g", "not json".toByteArray())
        t.guestT.send("g", "h", ByteArray(0))
        t.guestT.send("g", "h", "{\"type\":\"x\"}".toByteArray())
        assertEquals(g, t.guest.state.value)
        assertEquals(h, t.host.state.value)
    }

    // ---- ordering and recovery ------------------------------------------------------------------

    @Test fun anOlderStateArrivingLateNeverOverwritesANewerOne() {
        val t = table(hold = true)
        t.hub.flush() // the opening state
        val play = KoiRules.legalPlays(t.host.state.value!!.round).first()
        t.host.playHand(play.handCard, play.fieldCard)
        t.host.drawFromDeck()
        val queued = t.hub.queue.toList()
        assertTrue(queued.size >= 2)
        t.hub.queue.clear()
        // Deliver newest first, then the older ones.
        queued.reversed().forEach { (target, owner, payload) -> target.inject(owner, payload) }
        assertInSync(t)
    }

    @Test fun reconnectingAsksTheHostAgainAndEndsUpInSync() {
        val t = table()
        t.guestT.fireReconnected()
        assertInSync(t)
        t.hostT.fireReconnected() // host re-broadcasts
        assertInSync(t)
    }

    @Test fun whenTheOpponentLeavesMovesStopUntilTheyAreBack() {
        val t = table()
        t.hostT.fireLeft("g")
        assertTrue(t.host.opponentLeft.value)
        assertEquals(HanafudaEvent.OpponentLeft, t.host.event.value)
        val play = KoiRules.legalPlays(t.host.state.value!!.round).first()
        assertFalse(t.host.playHand(play.handCard, play.fieldCard))
        t.hostT.fireJoined("g")
        assertFalse(t.host.opponentLeft.value)
        assertEquals(HanafudaEvent.OpponentBack, t.host.event.value)
        assertTrue(t.host.playHand(play.handCard, play.fieldCard))
    }

    // ---- whole matches ----------------------------------------------------------------------------

    @Test fun aWholeNetworkedMatchIsPlayedByTwoRandomPlayersAndStaysInSync() {
        for (seed in 1..6) {
            val t = table(seed = seed, rounds = 3)
            val rng = Random(seed)
            var guard = 0
            while (!t.host.state.value!!.finished) {
                step(t, rng)
                assertInSync(t)
                assertTrue("seed $seed too long", ++guard < 600)
            }
            val h = t.host.state.value!!
            assertEquals(3, h.roundScores.size)
            assertEquals(h.roundScores.sumOf { it[0] }, h.totals[0])
            assertTrue(t.hub.senderLies.isEmpty())
            // Rematch: the guest asks, the host deals.
            assertTrue(t.guest.playAgain())
            assertEquals(1, t.host.state.value!!.roundNumber)
            assertInSync(t)
        }
    }

    @Test fun theGuestCanAskForTheNextRoundAndTheHostDealsIt() {
        val t = table(seed = 2, rounds = 6)
        val rng = Random(2)
        var guard = 0
        while (!t.host.state.value!!.round.over) {
            step(t, rng)
            assertTrue(++guard < 100)
        }
        assertTrue(t.guest.nextRound())
        assertEquals(2, t.host.state.value!!.roundNumber)
        assertEquals(2, t.guest.state.value!!.roundNumber)
        assertInSync(t)
    }

    @Test fun onlineModeBehavesLikeNearby() {
        val t = table(seed = 8, mode = PlayMode.ONLINE)
        val rng = Random(8)
        repeat(30) { if (!t.host.state.value!!.finished) step(t, rng) }
        assertInSync(t)
    }
}
