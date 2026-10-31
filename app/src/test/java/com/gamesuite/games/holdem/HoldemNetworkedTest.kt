package com.gamesuite.games.holdem

import com.gamesuite.core.PlayMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-authoritative Nearby / Online Hold'em: the host owns the deck, guests only ever see their own cards. */
class HoldemNetworkedTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun decode(p: ByteArray): HoldemNetMessage =
        json.decodeFromString(HoldemNetMessage.serializer(), String(p, Charsets.UTF_8))

    private fun encode(m: HoldemNetMessage): ByteArray =
        json.encodeToString(HoldemNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun HdTable.state(i: Int) = games[i].state.value!!

    /** Plays the current hand to its end with calls and checks, whoever is on turn acting through its own device. */
    private fun HdTable.playHand(onStep: () -> Unit = {}) {
        var guard = 0
        while (guard++ < 400) {
            val s = host.state.value!!
            if (s.handOver) return
            if (s.runout) { host.stepRunout(); onStep(); continue }
            val o = HoldemRules.options(s, s.toAct)!!
            val r = games[s.toAct].act(s.toAct, if (o.canCheck) HoldemAction.CHECK else HoldemAction.CALL)
            assertEquals(HoldemReject.NONE, r)
            onStep()
        }
        error("hand did not finish")
    }

    @Test fun guestsReceiveTheOpeningTableFromTheHostWithOnlyTheirOwnCards() {
        val t = HdTable(3)
        t.start()
        val hostState = t.host.state.value!!
        for (i in 1..2) {
            val g = t.state(i)
            assertEquals(holdemRedactFor(hostState, t.ids[i]), g)
            assertTrue(g.seats[i].hole.none { it == HIDDEN_CARD })
            for (k in 0..2) if (k != i) assertTrue("guest $i can see seat $k", g.seats[k].hole.all { it == HIDDEN_CARD })
            assertEquals(2, g.seats[0].hole.size) // the count of hidden cards is public
        }
        // the host sees its own cards; its stored state (the authority) holds everyone's
        assertTrue(hostState.seats.all { s -> s.hole.none { it == HIDDEN_CARD } })
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun noPayloadEverSentToAGuestContainsAnotherSeatsHiddenCardsOrTheDeck() {
        val t = HdTable(4)
        t.start()
        repeat(3) { t.playHand(); if (!t.host.state.value!!.matchOver) t.host.nextHand() }
        for (guest in 1..3) {
            val toGuest = t.transports[0].sent.filter { it.to == t.ids[guest] }
            assertTrue(toGuest.isNotEmpty())
            for (sent in toGuest) {
                val text = String(sent.payload, Charsets.UTF_8)
                assertFalse("deck leaked", text.contains("\"deck\""))
                val msg = decode(sent.payload) as HoldemNetMessage.StateSync
                msg.state.seats.forEachIndexed { k, seat ->
                    if (k != guest && !seat.revealed) assertTrue("seat $k's cards reached guest $guest", seat.hole.all { it == HIDDEN_CARD })
                }
            }
        }
        // showdown hands are public once turned up: at least one revealed seat was ever sent
        val anyRevealed = t.transports[0].sent.any { s -> (decode(s.payload) as? HoldemNetMessage.StateSync)?.state?.seats?.any { it.revealed } == true }
        assertTrue(anyRevealed)
    }

    @Test fun aGuestsTapReachesTheHostAndEveryoneSeesTheResult() {
        val t = HdTable(3)
        t.start()
        val s = t.host.state.value!!
        val seat = s.toAct
        val r = t.games[seat].act(seat, HoldemAction.CALL)
        assertEquals(HoldemReject.NONE, r)
        val hostAfter = t.host.state.value!!
        assertEquals(s.eventSerial + 1, hostAfter.eventSerial)
        for (i in 1..2) assertEquals(holdemRedactFor(hostAfter, t.ids[i]), t.state(i))
    }

    @Test fun aGuestCannotActOutOfTurnAndTheHostIgnoresForgedActs() {
        val t = HdTable(3)
        t.start()
        val s = t.host.state.value!!
        val other = (1..2).first { it != s.toAct }
        // the guest's own pre-check refuses
        assertEquals(HoldemReject.NOT_YOUR_TURN, t.games[other].act(other, HoldemAction.CALL))
        // and so does the host when a modified client sends the message anyway
        val before = t.host.state.value
        t.transports[0].inject(t.ids[other], encode(HoldemNetMessage.Act(HoldemAction.CALL)))
        assertEquals(before, t.host.state.value)
        // an Act from someone who is not at the table is ignored too
        t.transports[0].inject("stranger", encode(HoldemNetMessage.Act(HoldemAction.FOLD)))
        assertEquals(before, t.host.state.value)
        // a guest naming another seat is impossible: the message has no seat, so acting for the seat on turn needs that seat's device
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun onlyTheHostsStateSyncIsBelieved() {
        val t = HdTable(3)
        t.start()
        val real = t.state(2)
        val forged = real.copy(seats = real.seats.mapIndexed { i, p -> if (i == 2) p.copy(chips = p.chips + 500) else p })
        // a different guest pretends to be the host's broadcaster
        t.transports[2].inject("g1", encode(HoldemNetMessage.StateSync(999, forged)))
        assertEquals(real, t.state(2))
        // even from the host, an impossible table (chips from nowhere) is dropped
        t.transports[2].inject("h", encode(HoldemNetMessage.StateSync(1000, forged)))
        assertEquals(real, t.state(2))
        // an old version never rewinds the table
        t.playHandPartially()
        val now = t.state(2)
        t.transports[2].inject("h", encode(HoldemNetMessage.StateSync(1, real)))
        assertEquals(now, t.state(2))
    }

    private fun HdTable.playHandPartially() {
        val s = host.state.value!!
        games[s.toAct].act(s.toAct, HoldemAction.CALL)
    }

    @Test fun aWatcherSeesTheTableWithEveryHandHidden() {
        val t = HdTable(3, extraSpectators = 1)
        t.start()
        val w = t.spectators[0]
        val ws = w.state.value!!
        assertTrue(ws.seats.all { it.hole.size == 2 && it.hole.all { c -> c == HIDDEN_CARD } })
        assertTrue(w.isSpectator)
        assertEquals(HoldemReject.NOT_YOUR_TURN, w.act(0, HoldemAction.CALL))
        t.playHand()
        val after = w.state.value!!
        assertTrue(after.handOver)
        // a showdown or an uncontested hand: only revealed seats show their cards
        after.seats.forEach { if (!it.revealed) assertTrue(it.hole.all { c -> c == HIDDEN_CARD }) }
        // a watcher's tap does nothing at the host
        val host = t.host.state.value
        t.spectatorTransports[0].inject("w0", encode(HoldemNetMessage.Act(HoldemAction.FOLD)))
        assertEquals(host, t.host.state.value)
    }

    @Test fun runOutCardsAreRevealedToEveryoneOnceAllAreAllIn() {
        val t = HdTable(3)
        t.start()
        var guard = 0
        while (!t.host.state.value!!.runout && guard++ < 20) {
            val s = t.host.state.value!!
            t.games[s.toAct].act(s.toAct, HoldemAction.ALL_IN)
        }
        val s = t.host.state.value!!
        assertTrue(s.runout)
        for (i in 1..2) {
            val g = t.state(i)
            g.seats.filter { it.live }.forEach { assertTrue(it.revealed); assertTrue(it.hole.none { c -> c == HIDDEN_CARD }) }
        }
    }

    @Test fun aGuestLeavingHandsItsSeatToTheComputerAndComingBackTakesItBack() {
        val t = HdTable(3)
        t.start()
        t.transports[0].fireLeft("g1")
        val s = t.host.state.value!!
        assertTrue(s.seats[1].isBot)
        assertEquals(HoldemEvent.LEFT, s.event)
        assertEquals(1, s.eventSeat)
        // the host now plays that seat
        if (s.toAct == 1) assertTrue(t.host.botToAct())
        // while it is the computer's, the absent person's late taps are ignored
        val before = t.host.state.value
        if (s.toAct == 1) {
            t.transports[0].inject("g1", encode(HoldemNetMessage.Act(HoldemAction.FOLD)))
            assertEquals(before, t.host.state.value)
        }
        t.transports[0].fireJoined("g1")
        assertFalse(t.host.state.value!!.seats[1].isBot)
        assertEquals(HoldemEvent.RETURNED, t.host.state.value!!.event)
        // the hand can still be finished
        var guard = 0
        while (!t.host.state.value!!.handOver && guard++ < 300) {
            val st = t.host.state.value!!
            when {
                st.runout -> t.host.stepRunout()
                else -> {
                    val o = HoldemRules.options(st, st.toAct)!!
                    t.games[st.toAct].act(st.toAct, if (o.canCheck) HoldemAction.CHECK else HoldemAction.CALL)
                }
            }
        }
        assertTrue(t.host.state.value!!.handOver)
    }

    @Test fun theHostLeavingIsNoticedByGuests() {
        val t = HdTable(3)
        t.start()
        assertFalse(t.games[1].hostLeft.value)
        t.transports[1].fireLeft("h")
        assertTrue(t.games[1].hostLeft.value)
        t.transports[1].fireJoined("h")
        assertFalse(t.games[1].hostLeft.value)
    }

    @Test fun aGuestAsksForTheNextHandAndTheHostDealsIt() {
        val t = HdTable(3)
        t.start()
        t.playHand()
        val over = t.host.state.value!!
        assertTrue(over.handOver)
        t.games[2].nextHand()
        val next = t.host.state.value!!
        assertEquals(over.handNumber + 1, next.handNumber)
        assertFalse(next.handOver)
        assertEquals(holdemRedactFor(next, "g2"), t.state(2))
        // asking again mid-hand changes nothing
        t.games[1].nextHand()
        assertEquals(next, t.host.state.value)
    }

    @Test fun reconnectingAGuestAsksForTheCurrentState() {
        val t = HdTable(3)
        t.start()
        val before = t.transports[1].sent.size
        t.transports[1].fireReconnected()
        assertTrue(t.transports[1].sent.size > before)
        val hostBefore = t.transports[0].sent.size
        t.transports[0].fireReconnected()
        assertTrue("the host re-broadcasts", t.transports[0].sent.size > hostBefore)
        assertEquals(holdemRedactFor(t.host.state.value!!, "g1"), t.state(1))
    }

    @Test fun onlineModeBehavesLikeNearby() {
        val t = HdTable(3, mode = PlayMode.ONLINE)
        t.start()
        assertNotNull(t.state(1))
        assertEquals(holdemRedactFor(t.host.state.value!!, "g2"), t.state(2))
        t.playHand()
        assertTrue(t.state(1).handOver)
    }

    @Test fun aWholeTournamentOverTheWireConservesChips() {
        val t = HdTable(3, seed = 99)
        t.start()
        var guard = 0
        while (!t.host.state.value!!.matchOver && guard++ < 4000) {
            val s = t.host.state.value!!
            when {
                s.handOver -> t.games[1 + (guard % 2)].nextHand()
                s.runout -> t.host.stepRunout()
                else -> {
                    val o = HoldemRules.options(s, s.toAct)!!
                    val a = when {
                        guard % 7 == 0 && o.canAllIn -> HoldemAction.ALL_IN
                        guard % 5 == 0 && o.canRaise -> HoldemAction.raiseTo(o.minRaiseTo)
                        o.canCheck -> HoldemAction.CHECK
                        else -> HoldemAction.CALL
                    }
                    assertEquals(HoldemReject.NONE, t.games[s.toAct].act(s.toAct, a))
                }
            }
            assertEquals(3000, t.host.state.value!!.chipsInPlay)
            for (i in 1..2) assertEquals(holdemRedactFor(t.host.state.value!!, t.ids[i]), t.state(i))
        }
        assertTrue(t.host.state.value!!.matchOver)
        assertNull(t.host.snapshot())
        // the winner is told to everyone
        assertEquals(t.host.state.value!!.winnerPlayerId, t.state(1).winnerPlayerId)
    }

    @Test fun redactionKeepsPublicInformationAndHidesPrivateCards() {
        val engine = HoldemEngine(kotlin.random.Random(1))
        val s = engine.startHand(T.match(3, engine = engine))
        val view = holdemRedactFor(s, "p2")
        assertEquals(s.seats[1].hole, view.seats[1].hole)
        assertTrue(view.seats[0].hole.all { it == HIDDEN_CARD } && view.seats[0].hole.size == 2)
        assertTrue(view.seats[2].hole.all { it == HIDDEN_CARD })
        assertEquals(s.copy(seats = s.seats.map { it.copy(hole = emptyList()) }), view.copy(seats = view.seats.map { it.copy(hole = emptyList()) }))
        val spectator = holdemRedactForSpectator(s)
        assertTrue(spectator.seats.all { p -> p.hole.all { it == HIDDEN_CARD } })
        // a revealed seat stays visible to everyone
        val shown = s.copy(seats = s.seats.mapIndexed { i, p -> if (i == 0) p.copy(revealed = true) else p })
        assertEquals(shown.seats[0].hole, holdemRedactFor(shown, "p3").seats[0].hole)
        // and a redacted state is still one a guest accepts
        assertTrue(HoldemRules.isConsistent(view, listOf("p1", "p2", "p3")))
        assertFalse(HoldemRules.isConsistent(view, listOf("p1", "p2", "p3"), engine.deck, engine.deckPos))
    }
}
