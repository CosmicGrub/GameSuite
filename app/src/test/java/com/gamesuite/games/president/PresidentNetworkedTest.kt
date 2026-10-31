package com.gamesuite.games.president

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative play over a relay: identity checks, redaction, spectators, take-over, convergence. */
class PresidentNetworkedTest {
    private class Table(val hub: PsHub, val games: List<PresidentGame>, val spectator: PresidentGame?) {
        val host get() = games[0]
    }

    private fun table(seats: Int, rules: PresidentRuleSet = PresidentRuleSet(), withSpectator: Boolean = false, seed: Int = 1): Table {
        val players = psPlayers(seats)
        val hub = PsHub()
        val games = players.mapIndexed { i, p ->
            PresidentGame(Random(seed + i)).also { g ->
                g.rules = rules
                g.difficulty = CpuDifficulty.MEDIUM
                g.init(GameContext(PlayMode.ONLINE, players, i, hub.endpoint(p.playerId)))
            }
        }
        val spec = if (withSpectator) PresidentGame(Random(99)).also { g ->
            g.init(GameContext(PlayMode.ONLINE, players, -1, hub.endpoint("watcher")))
        } else null
        games[0].startMatch()
        games.drop(1).forEach { it.startMatch() } // guests ignore startMatch
        return Table(hub, games, spec)
    }

    private fun json(m: PresidentNetMessage) = Json.encodeToString(PresidentNetMessage.serializer(), m).toByteArray()

    @Test fun everyGuestHoldsOnlyItsOwnHand() {
        val t = table(4)
        val host = t.host.state.value!!
        for (i in 1..3) {
            val view = t.games[i].state.value!!
            assertEquals(host.seats.map { it.hand.size }, view.seats.map { it.hand.size })
            assertEquals(host.seats[i].hand, view.seats[i].hand)
            for (j in view.seats.indices) if (j != i) assertTrue(view.seats[j].hand.all { it == PS_HIDDEN_CARD })
        }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun noCardFaceOfAnotherSeatEverAppearsInAnyWireMessage() {
        val t = table(3)
        val rnd = Random(4)
        var steps = 0
        while (!t.host.state.value!!.roundOver && steps++ < 400) playOneHumanMove(t, rnd)
        val players = psPlayers(3)
        for ((id, ep) in t.hub.endpoints) {
            if (id == "p1") continue
            val seat = players.indexOfFirst { it.playerId == id }
            for (m in t.hub.endpoints["p1"]!!.sent.filter { it.to == id }) {
                val msg = Json.decodeFromString(PresidentNetMessage.serializer(), String(m.payload)) as PresidentNetMessage.StateSync
                for (j in msg.state.seats.indices) if (j != seat) assertTrue("leak to $id", msg.state.seats[j].hand.all { it == PS_HIDDEN_CARD })
            }
            assertNotNull(ep)
        }
    }

    private fun playOneHumanMove(t: Table, rnd: Random) {
        val s = t.host.state.value!!
        val actor = when (s.phase) {
            PsPhase.PLAY -> s.currentSeat
            PsPhase.EXCHANGE -> s.pendingExchanges.first().from
            else -> return
        }
        val g = t.games[actor]
        val view = g.state.value!!
        if (s.phase == PsPhase.EXCHANGE) {
            val task = s.pendingExchanges.first { it.from == actor }
            assertEquals(PresidentReject.NONE, g.tryGive(view.seats[actor].hand.shuffled(rnd).take(task.count)))
        } else {
            val options = PresidentRules.candidatePlays(view.seats[actor].hand, view.top?.cards, view.revolution, view.rules.ties, view.mustInclude)
            if (options.isEmpty()) assertEquals(PresidentReject.NONE, g.tryPass())
            else assertEquals(PresidentReject.NONE, g.tryPlay(options[rnd.nextInt(options.size)]))
        }
    }

    @Test fun aWholeSessionOverTheWireEndsInTheSameStateEverywhere() {
        val t = table(4, PresidentRuleSet(rounds = 3), withSpectator = true)
        t.spectator!!.let { /* the watcher announced itself in init */ }
        val rnd = Random(8)
        var steps = 0
        while (t.host.state.value!!.phase != PsPhase.SESSION_OVER && steps++ < 20000) {
            val s = t.host.state.value!!
            if (s.roundOver) t.games[1 + steps % 3].continueSession() else playOneHumanMove(t, rnd)
            val h = t.host.state.value!!
            for (i in 1..3) {
                val v = t.games[i].state.value!!
                assertEquals(h.eventSerial, v.eventSerial)
                assertEquals(h.currentSeat, v.currentSeat)
                assertEquals(h.trick, v.trick)
                assertEquals(h.seats.map { it.score }, v.seats.map { it.score })
                assertEquals(h.seats[i].hand, v.seats[i].hand)
            }
            val w = t.spectator!!.state.value!!
            assertEquals(h.eventSerial, w.eventSerial)
            assertTrue(w.seats.all { seat -> seat.hand.all { it == PS_HIDDEN_CARD } })
        }
        assertEquals(PsPhase.SESSION_OVER, t.host.state.value!!.phase)
        // (a watcher stamps a placeholder sender; the relay overwrites it, so only seated players must be honest)
        assertTrue(t.hub.senderLies.none { !it.startsWith("endpoint watcher") })
        for (g in t.games) assertEquals(PsPhase.SESSION_OVER, g.state.value!!.phase)
    }

    @Test fun aGuestCannotMoveForAnotherSeatOrOutOfTurn() {
        val t = table(3)
        val s = t.host.state.value!!
        val cur = s.currentSeat
        val other = (1..2).first { it != cur }
        val victimCards = s.seats[cur].hand.take(1)
        val before = t.host.state.value
        // a guest that is not on turn sends a play for itself
        if (other > 0) {
            val g = t.games[other]
            val v = g.state.value!!
            assertEquals(PresidentReject.NOT_YOUR_TURN, g.tryPlay(listOf(v.seats[other].hand.first())))
        }
        // forged intent from a non-turn seat straight to the host
        val forged = json(PresidentNetMessage.Intent(PresidentIntent.Play(victimCards)))
        t.hub.endpoints["p${other + 1}"]!!.send("p${cur + 1}", "p1", forged) // claims to be the seat on turn
        assertEquals(before, t.host.state.value)
        assertTrue("the relay saw the lie", t.hub.senderLies.isNotEmpty())
    }

    @Test fun aGuestCannotPlayCardsItDoesNotHold() {
        val t = table(3, seed = 3)
        var s = t.host.state.value!!
        // walk to a guest's turn
        val rnd = Random(2)
        var guard = 0
        while (s.currentSeat == 0 && guard++ < 10) { playOneHumanMove(t, rnd); s = t.host.state.value!! }
        val actor = s.currentSeat
        if (actor == 0) return
        val foreign = s.seats[0].hand.first { it !in s.seats[actor].hand }
        val before = t.host.state.value
        val forged = json(PresidentNetMessage.Intent(PresidentIntent.Play(listOf(foreign))))
        t.hub.endpoints["p${actor + 1}"]!!.send("p${actor + 1}", "p1", forged)
        assertEquals(before, t.host.state.value)
    }

    @Test fun aForgedStateSyncFromAGuestIsIgnoredAndOlderVersionsAreDropped() {
        val t = table(3)
        val g1 = t.games[1]
        val keep = g1.state.value
        val fake = t.host.state.value!!.copy(currentSeat = 2)
        val payload = json(PresidentNetMessage.StateSync(9999, presidentRedactFor(fake, "p2")))
        t.hub.endpoints["p3"]!!.send("p3", "p2", payload) // a fellow guest, not the host
        assertEquals(keep, g1.state.value)
        // an old version from the real host is dropped too
        val old = json(PresidentNetMessage.StateSync(0, presidentRedactFor(fake, "p2")))
        t.hub.endpoints["p1"]!!.send("p1", "p2", old)
        assertEquals(keep, g1.state.value)
    }

    @Test fun aStateThatFailsValidationIsNotApplied() {
        val t = table(3)
        val g1 = t.games[1]
        val keep = g1.state.value
        val bad = presidentRedactFor(t.host.state.value!!, "p2").let { it.copy(playedCards = listOf(5, 5)) }
        t.hub.endpoints["p1"]!!.send("p1", "p2", json(PresidentNetMessage.StateSync(500, bad)))
        assertEquals(keep, g1.state.value)
        val badSeats = presidentRedactFor(t.host.state.value!!, "p2").let { it.copy(seats = it.seats.drop(1)) }
        t.hub.endpoints["p1"]!!.send("p1", "p2", json(PresidentNetMessage.StateSync(501, badSeats)))
        assertEquals(keep, g1.state.value)
    }

    @Test fun malformedPayloadsAreIgnored() {
        val t = table(3)
        val keep = t.host.state.value
        t.hub.endpoints["p2"]!!.send("p2", "p1", "not json".toByteArray())
        t.hub.endpoints["p2"]!!.send("p2", "p1", ByteArray(0))
        assertEquals(keep, t.host.state.value)
    }

    @Test fun aWatcherGetsTheTableButCannotAct() {
        val t = table(3, withSpectator = true)
        val w = t.spectator!!
        assertTrue(w.isSpectator)
        val s = w.state.value!!
        assertTrue(s.seats.all { seat -> seat.hand.all { it == PS_HIDDEN_CARD } })
        assertEquals(PresidentReject.SPECTATOR, w.tryPass())
        assertEquals(PresidentReject.SPECTATOR, w.tryPlay(listOf(1)))
        // an intent sent in a watcher's name is ignored by the host
        val before = t.host.state.value
        t.hub.endpoints["watcher"]!!.send("watcher", "p1", json(PresidentNetMessage.Intent(PresidentIntent.Pass)))
        t.hub.endpoints["watcher"]!!.send("watcher", "p1", json(PresidentNetMessage.Intent(PresidentIntent.Continue)))
        assertEquals(before, t.host.state.value)
    }

    @Test fun aSeatThatLeavesIsTakenOverByAComputerAndGivenBackWhenItReturns() {
        val t = table(3, PresidentRuleSet(exchange = false))
        val host = t.host
        // make a guest seat the one on turn so the take-over has to move
        var s = host.state.value!!
        val rnd = Random(1)
        var guard = 0
        while (s.currentSeat == 0 && guard++ < 10) { playOneHumanMove(t, rnd); s = host.state.value!! }
        val leaver = s.currentSeat
        if (leaver == 0) return
        t.hub.endpoints["p1"]!!.fireLeft("p${leaver + 1}")
        s = host.state.value!!
        assertTrue(s.seats[leaver].isBot)
        assertTrue(host.botToMove())
        host.playBotStep()
        assertNotEquals(leaver, host.state.value!!.currentSeat)
        t.hub.endpoints["p1"]!!.fireJoined("p${leaver + 1}")
        assertFalse(host.state.value!!.seats[leaver].isBot)
    }

    @Test fun aGuestSeesTheHostLeaveAndRecovers() {
        val t = table(3)
        val g = t.games[1]
        t.hub.endpoints["p2"]!!.fireLeft("p1")
        assertTrue(g.hostLeft.value)
        assertFalse(g.isMyTurn(g.state.value!!))
        t.hub.endpoints["p2"]!!.fireJoined("p1")
        assertFalse(g.hostLeft.value)
    }

    @Test fun reconnectResyncsGuestsAndHost() {
        val t = table(3)
        val g = t.games[2]
        val before = g.state.value
        t.hub.endpoints["p3"]!!.fireReconnected()
        assertEquals(before!!.eventSerial, g.state.value!!.eventSerial)
        val sentBefore = t.hub.endpoints["p1"]!!.sent.size
        t.hub.endpoints["p1"]!!.fireReconnected()
        assertTrue(t.hub.endpoints["p1"]!!.sent.size > sentBefore)
    }

    @Test fun onlineAndNearbyHaveNoUndoOrHint() {
        val t = table(3)
        assertFalse(t.host.canUndo)
        t.host.undo()
        assertNull(t.host.hintNow())
        assertTrue(t.host.networked)
    }

    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)

    @Test fun theExchangeGiftsStayPrivateToTheTwoSeatsInvolved() {
        val t = table(4, PresidentRuleSet(rounds = 5))
        val rnd = Random(12)
        var steps = 0
        while (!t.host.state.value!!.roundOver && steps++ < 2000) playOneHumanMove(t, rnd)
        t.games[1].continueSession()
        val s = t.host.state.value!!
        assertEquals(PsPhase.EXCHANGE, s.phase)
        val order = s.seats.indices.sortedBy { s.seats[it].roundPoints } // unused; roles are on the seats
        assertEquals(4, order.size)
        // a seat uninvolved in a swap sees hidden cards for it
        for (i in 1..3) {
            val view = t.games[i].state.value!!
            for (e in view.exchanges) {
                if (e.from != i && e.to != i) assertTrue(e.cards.all { it == PS_HIDDEN_CARD })
                else assertTrue(e.cards.none { it == PS_HIDDEN_CARD })
            }
        }
    }

    @Test fun redactionOfAnEveryStateKeepsCountsAndOnlyTheViewersCards() {
        val s = PresidentFlow.newSession(psPlayers(5).map { PsSeat(it.playerId, it.displayName, false) }, PresidentRuleSet(), Random(3), CpuDifficulty.MEDIUM)
        for (viewer in listOf("p1", "p3", null)) {
            val r = presidentRedactFor(s, viewer)
            for (i in s.seats.indices) {
                assertEquals(s.seats[i].hand.size, r.seats[i].hand.size)
                if (s.seats[i].playerId == viewer) assertEquals(s.seats[i].hand, r.seats[i].hand)
                else assertTrue(r.seats[i].hand.all { it == PS_HIDDEN_CARD })
            }
            assertEquals(s.playedCards, r.playedCards)
        }
        assertTrue(PresidentValidator.isValid(presidentRedactFor(s, "p2"), psPlayers(5), allowHidden = true))
        assertFalse(PresidentValidator.isValid(presidentRedactFor(s, "p2"), psPlayers(5), allowHidden = false))
    }
}
