package com.gamesuite.games.blackjack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Blackjack: the host owns the shoe, the deal and the dealer. */
class BlackjackNetworkedTest {
    private class Node(val game: BlackjackGame, val transport: BwFakeTransport)
    private class Table(val hub: BwHub, val nodes: List<Node>) {
        val host get() = nodes[0]
    }

    private fun encode(m: BjNetMessage) = Json.encodeToString(BjNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, config: BjConfig = BjConfig(decks = 1, insurance = false), seed: Int = 1): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = BwHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = BlackjackGame(if (i == 0) Random(seed) else Random(9000 + i)) // a guest's own Random must never matter
            g.config = config
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            nodes += Node(g, t)
        }
        nodes[0].game.startMatch()
        return Table(hub, nodes)
    }

    private fun payloads(t: Table) = t.host.transport.sent.map { Json.decodeFromString(BjNetMessage.serializer(), String(it.payload, Charsets.UTF_8)) }
    private fun syncs(t: Table) = payloads(t).filterIsInstance<BjNetMessage.StateSync>()

    /** The host's state with its hole card blanked the way a guest sees it. */
    private fun asGuestSees(s: BjState) =
        if (!s.holeHidden) s else s.copy(dealer = s.dealer.mapIndexed { i, c -> if (i == 1) c.copy(card = BJ_HIDDEN_CARD) else c })

    private fun assertInSync(t: Table) {
        val expected = asGuestSees(t.host.game.state.value!!)
        t.nodes.drop(1).forEach { assertEquals(expected, it.game.state.value) }
    }

    /** Plays the table to ROUND_OVER: each seat acts from its OWN device; the host runs the bots and the dealer. */
    private fun playRound(t: Table, action: (BlackjackGame) -> BjAction = { BjAction.STAND }) {
        var guard = 0
        while (t.host.game.state.value!!.phase != BjPhase.ROUND_OVER && guard++ < 300) {
            val s = t.host.game.state.value!!
            when {
                t.host.game.botToMove(s) -> t.host.game.playBotStep()
                t.host.game.dealerToMove(s) -> t.host.game.dealerStep()
                else -> {
                    val actor = t.nodes[s.activeSeat].game
                    when (s.phase) {
                        BjPhase.BETTING -> actor.bet(BjRules.MIN_BET * 5)
                        BjPhase.INSURANCE -> actor.insurance(false)
                        BjPhase.PLAYING -> { val a = action(actor); if (!actor.act(a)) actor.act(BjAction.STAND) }
                        else -> {}
                    }
                }
            }
            assertInSync(t)
        }
        assertEquals(BjPhase.ROUND_OVER, t.host.game.state.value!!.phase)
    }

    @Test fun guestsReceiveTheOpeningTableFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(BjPhase.BETTING, it.game.state.value!!.phase) }
        assertInSync(t)
        assertTrue(t.hub.lies.isEmpty())
    }

    @Test fun aFullRoundPlaysOverTheWireAndEveryoneEndsInTheSameState() {
        val t = table(3)
        playRound(t)
        val host = t.host.game.state.value!!
        t.nodes.forEach { assertEquals(host, it.game.state.value) }       // hole card revealed: identical
        assertEquals(3, host.seats.count { it.hands.isNotEmpty() })
        assertTrue(t.hub.lies.isEmpty())
        // a guest asks for the next round and the host deals a fresh betting phase to everyone
        assertTrue(t.nodes[2].game.nextRound())
        assertEquals(BjPhase.BETTING, t.host.game.state.value!!.phase)
        assertEquals(2, t.nodes[1].game.state.value!!.round)
        assertInSync(t)
    }

    @Test fun theHoleCardAndTheShoeNeverLeaveTheHostWhileTheHoleCardIsFaceDown() {
        val t = table(3)
        var rounds = 0
        while (rounds < 6) {
            // stop in the middle of the round, hole card still down
            var guard = 0
            while (guard++ < 100 && t.host.game.state.value!!.phase != BjPhase.PLAYING && t.host.game.state.value!!.phase != BjPhase.ROUND_OVER) {
                val s = t.host.game.state.value!!
                when {
                    t.host.game.botToMove(s) -> t.host.game.playBotStep()
                    t.host.game.dealerToMove(s) -> t.host.game.dealerStep()
                    s.phase == BjPhase.BETTING -> t.nodes[s.activeSeat].game.bet(50)
                    s.phase == BjPhase.INSURANCE -> t.nodes[s.activeSeat].game.insurance(false)
                }
            }
            if (t.host.game.state.value!!.phase == BjPhase.PLAYING) {
                val s = t.host.game.state.value!!
                assertTrue(s.holeHidden)
                val hole = s.dealer[1].card
                // what every guest holds
                t.nodes.drop(1).forEach {
                    val gs = it.game.state.value!!
                    assertTrue(gs.holeHidden)
                    assertEquals(-1, gs.dealer[1].card.id)
                    assertEquals(s.dealer[0], gs.dealer[0])
                }
                // and every byte the host has sent so far: a hidden state carries -1 for the hole card
                syncs(t).filter { it.state.holeHidden && it.state.dealer.size >= 2 }.forEach { sync ->
                    assertEquals(listOf(-1), sync.state.dealer.drop(1).map { it.id })
                }
                assertNotEquals(-1, hole.id)
            }
            playRound(t)
            t.nodes[0].game.nextRound()
            rounds++
        }
        // the wire format has no field that could carry the shoe order
        val raw = t.host.transport.sent.joinToString("") { String(it.payload, Charsets.UTF_8) }
        assertFalse(raw.contains("\"shoe\""))
        assertFalse(raw.contains("discard"))
    }

    @Test fun onlyTheSeatOnTurnCanActAndGuestsOnlyAsk() {
        val t = table(3)
        val g1 = t.nodes[1].game
        val g2 = t.nodes[2].game
        assertFalse(g1.bet(50))                    // seat 0 is betting; g1 is refused locally
        assertEquals(BjReject.NOT_YOUR_TURN, g1.explainBet(50))
        // a forged request from the wrong seat is ignored by the host
        val before = t.host.game.state.value
        t.nodes[2].transport.send("g2", "h", encode(BjNetMessage.BetRequest(50)))
        t.nodes[1].transport.send("g1", "h", encode(BjNetMessage.BetRequest(50)))
        assertTrue(before === t.host.game.state.value)
        t.nodes[1].transport.send("h", "h", encode(BjNetMessage.BetRequest(50)))   // claims to be the host
        assertTrue(before === t.host.game.state.value)
        assertTrue(t.hub.lies.isNotEmpty())                                          // the relay stamped the real sender
        t.host.game.bet(50)
        assertFalse(g2.bet(70))                    // seat 2 waits for its turn
        assertTrue(g1.bet(50))                     // g1's own turn: accepted through the host
        assertEquals(50, t.host.game.state.value!!.seats[1].bet)
        assertInSync(t)
    }

    @Test fun malformedAndOutOfRangeRequestsAreRefused() {
        val t = table(2)
        t.host.game.bet(50)
        val s = t.host.game.state.value
        val g1 = t.nodes[1].transport
        g1.send("g1", "h", encode(BjNetMessage.BetRequest(-10)))
        g1.send("g1", "h", encode(BjNetMessage.BetRequest(15)))
        g1.send("g1", "h", encode(BjNetMessage.BetRequest(1_000_000)))
        g1.send("g1", "h", encode(BjNetMessage.BetRequest(Int.MAX_VALUE)))
        g1.send("g1", "h", "garbage".toByteArray())
        g1.send("g1", "h", encode(BjNetMessage.ActionRequest("HIT")))            // betting phase
        g1.send("g1", "h", encode(BjNetMessage.ActionRequest("NOPE")))
        g1.send("g1", "h", encode(BjNetMessage.InsuranceRequest(true)))
        g1.send("g1", "h", encode(BjNetMessage.RebuyRequest))                   // has chips
        g1.send("g1", "h", encode(BjNetMessage.NextRoundRequest))                // round not over
        assertTrue(s === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndMalformedSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value
        val good = t.host.game.toNet(t.host.game.state.value!!)
        g.transport.inject("g1", encode(BjNetMessage.StateSync(500, good)))                               // not from the host
        g.transport.inject("h", "###".toByteArray())
        g.transport.inject("h", encode(BjNetMessage.StateSync(500, good.copy(phase = "bogus"))))
        g.transport.inject("h", encode(BjNetMessage.StateSync(501, good.copy(activeSeat = 9))))
        g.transport.inject("h", encode(BjNetMessage.StateSync(502, good.copy(seats = good.seats.take(1)))))
        g.transport.inject("h", encode(BjNetMessage.StateSync(503, good.copy(dealer = listOf(BjNetCard(-1, 0))))))   // hidden card out of place
        g.transport.inject("h", encode(BjNetMessage.StateSync(504, good.copy(seats = good.seats.map { it.copy(stack = -1) }))))
        g.transport.inject("h", encode(BjNetMessage.StateSync(505, good.copy(config = BjConfig(decks = 3)))))
        assertTrue(real === g.game.state.value)
        t.host.game.bet(50)
        val fresh = g.game.state.value
        g.transport.inject("h", encode(BjNetMessage.StateSync(0, good)))                                  // stale version
        assertTrue(fresh === g.game.state.value)
    }

    @Test fun guestsCannotRunTheDealerOrTheBots() {
        val t = table(2)
        t.host.game.stackShoe(cds("10S", "9H", "7C", "8D", "10D", "10C"))
        t.host.game.bet(50)
        t.nodes[1].game.bet(50)
        // play to the dealer phase
        t.host.game.act(BjAction.STAND)
        t.nodes[1].game.act(BjAction.STAND)
        val g = t.nodes[1].game
        assertEquals(BjPhase.DEALER, t.host.game.state.value!!.phase)
        assertFalse(g.dealerToMove(g.state.value!!))
        assertFalse(g.dealerStep())
        assertFalse(g.botToMove())
        assertTrue(t.host.game.dealerToMove(t.host.game.state.value!!))
    }

    @Test fun aDepartedSeatIsPlayedByTheComputerAndReturnedOnRejoin() {
        val t = table(3)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.seats[1].isBot)
        assertTrue(t.nodes[2].game.state.value!!.seats[1].isBot)
        t.host.game.bet(50)
        assertTrue(t.host.game.botToMove())          // seat 1 now bets for itself
        t.host.game.playBotStep()
        assertEquals(2, t.host.game.state.value!!.activeSeat)
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.seats[1].isBot)
        assertInSync(t)
    }

    @Test fun guestsSeeTheHostLeavingAndRecover() {
        val t = table(2)
        val g = t.nodes[1]
        g.transport.fireLeft("h")
        assertTrue(g.game.hostLeft.value)
        assertFalse(g.game.isMyTurn())
        g.transport.fireJoined("h")
        assertFalse(g.game.hostLeft.value)
    }

    @Test fun aReconnectingGuestAsksForTheTableAndGetsIt() {
        val t = table(2)
        t.host.game.bet(50)
        t.nodes[1].transport.inject("h", "x".toByteArray())
        t.nodes[1].transport.send("g1", "h", encode(BjNetMessage.RequestState))
        assertEquals(asGuestSees(t.host.game.state.value!!), t.nodes[1].game.state.value)
    }

    @Test fun randomNetworkedPlayStaysInSyncAndCardsAreConserved() {
        for (seed in 0 until 12) {
            val t = table(2 + seed % 3, BjConfig(decks = 2, insurance = seed % 2 == 0, surrender = seed % 3 == 0), seed)
            val rnd = Random(seed)
            repeat(6) {
                playRound(t) { g ->
                    val legal = g.legalActions().toList()
                    legal[rnd.nextInt(legal.size)]
                }
                t.nodes[rnd.nextInt(t.nodes.size)].game.nextRound()
                t.nodes.forEach { n -> n.game.state.value!!.seats.forEach { assertTrue(it.stack >= 0) } }
            }
            assertTrue(t.hub.lies.isEmpty())
        }
    }
}
