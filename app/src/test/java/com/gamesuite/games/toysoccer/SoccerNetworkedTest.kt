package com.gamesuite.games.toysoccer

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

/** Host-authoritative Nearby / Online Toy Soccer: guests send intents, identity comes from the transport, syncs are versioned. */
class SoccerNetworkedTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun enc(m: SoccerNetMessage) = json.encodeToString(SoccerNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = json.decodeFromString(SoccerNetMessage.serializer(), String(b, Charsets.UTF_8))

    private class Node(val game: ToySoccerGame, val transport: ToyFakeTransport)
    private class Table(val hub: ToyHub, val clock: ToyClock, val nodes: List<Node>) {
        val host get() = nodes[0]
        val guest get() = nodes[1]
    }

    private fun table(seats: Int = 2, mode: PlayMode = PlayMode.LOCAL_AD_HOC, live: Boolean = true, assist: Boolean = false): Table {
        val hub = ToyHub(); val clock = ToyClock()
        val ids = listOf("h", "g", "g2", "g3").take(seats)
        val roster = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val eps = ids.map { hub.endpoint(it) }
        val nodes = ids.indices.map { i ->
            val g = ToySoccerGame(Random(7 + i), clock)
            g.assist = assist
            Node(g, eps[i])
        }
        nodes[0].game.init(GameContext(mode, roster, 0, eps[0]))
        nodes[0].game.startMatch()
        for (i in 1 until seats) nodes[i].game.init(GameContext(mode, roster, i, eps[i]))
        val t = Table(hub, clock, nodes)
        if (live && !assist) t.host.game.stepTicks(SoccerRules.COUNTDOWN_TICKS + 20)
        return t
    }

    @Test fun guestsReceiveTheTableAndNobodyEverLiesAboutWhoSentWhat() {
        val t = table(seats = 4)
        for (i in 1..3) {
            val gs = t.nodes[i].game.state.value
            assertNotNull("seat $i has a state", gs)
            assertEquals(t.host.game.state.value!!.phase, gs!!.phase)
            assertEquals(4, gs.figuresPerTeam)
            assertEquals(t.host.game.state.value!!.bodies.map { it.x }, gs.bodies.map { it.x })
        }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestFlickIsAppliedByTheHostAndStreamedBack() {
        val t = table()
        assertEquals(SoccerReject.NONE, t.guest.game.flick(1, 1, 0.0, 120.0))
        val hs = t.host.game.state.value!!
        assertEquals(1, hs.flickSerial)
        assertEquals(1, hs.eventTeam)
        val hb = hs.bodies.first { it.team == 1 && it.figure == 1 }
        assertTrue(hb.vy > 100.0)                               // team 1 flicked toward its attacking end (down the pitch)
        assertEquals(1, t.guest.game.state.value!!.flickSerial)
        assertEquals(hb.vy, t.guest.game.state.value!!.bodies.first { it.team == 1 && it.figure == 1 }.vy, 1e-9)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCanOnlyUseItsOwnSeat() {
        val t = table()
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, t.guest.game.flick(0, 1, 0.0, -120.0))   // seat 0 is the host's
        assertEquals(0, t.host.game.state.value!!.flickSerial)
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, t.host.game.flick(1, 1, 0.0, 120.0))
    }

    @Test fun theHostRefusesAGuestRequestForAFigureThatIsNotTheirs() {
        val t = table(seats = 4)
        val before = t.host.game.state.value!!.flickSerial
        // seat 1 asks to flick figure 2, which belongs to seat 3
        t.nodes[1].transport.send("g", "h", enc(SoccerNetMessage.FlickRequest(2, 0.0, 120.0)))
        assertEquals(before, t.host.game.state.value!!.flickSerial)
        assertEquals(SoccerReject.NONE, t.nodes[3].game.flick(3, 2, 0.0, 120.0))
        assertEquals(before + 1, t.host.game.state.value!!.flickSerial)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun intentsFromTheHostSeatOrUnknownSendersAreIgnored() {
        val t = table()
        val msg = enc(SoccerNetMessage.FlickRequest(1, 0.0, -150.0))
        t.host.transport.inject("h", msg)          // as if the host had sent itself a request
        t.host.transport.inject("nobody", msg)
        t.host.transport.inject("zz", msg)
        assertEquals(0, t.host.game.state.value!!.flickSerial)
        // junk and wrong types
        t.host.transport.inject("g", "garbage".toByteArray())
        t.host.transport.inject("g", ByteArray(0))
        t.host.transport.inject("g", "{\"type\":\"nope\"}".toByteArray())
        assertEquals(0, t.host.game.state.value!!.flickSerial)
        // out of range or non-finite pulls
        t.host.transport.inject("g", enc(SoccerNetMessage.FlickRequest(99, 0.0, 100.0)))
        t.host.transport.inject("g", enc(SoccerNetMessage.FlickRequest(-3, 0.0, 100.0)))
        t.host.transport.inject("g", "{\"type\":\"x\",\"figure\":1,\"pullX\":1e999,\"pullY\":0}".toByteArray())
        assertEquals(0, t.host.game.state.value!!.flickSerial)
    }

    @Test fun onlyTheHostMaySendState() {
        val t = table()
        val gs = t.guest.game.state.value!!
        val forged = SoccerNetMessage.StateSync(9999, netStateOf(t.host.transport).copy(score = listOf(7, 0)))
        // from the other guest seat's id or any non-host id
        t.guest.transport.inject("g", enc(forged))
        t.guest.transport.inject("nobody", enc(forged))
        assertEquals(gs.score, t.guest.game.state.value!!.score)
        // and from the host it is accepted
        t.guest.transport.inject("h", enc(forged))
        assertEquals(listOf(7, 0), t.guest.game.state.value!!.score)
        // a host never takes state from a guest
        t.host.transport.inject("g", enc(forged))
        assertEquals(listOf(0, 0), t.host.game.state.value!!.score)
    }

    private fun netStateOf(hostT: ToyFakeTransport): SoccerNetState =
        // The last StateSync the host sent, as a template for crafted ones.
        hostT.sent.map { dec(it.payload) }.filterIsInstance<SoccerNetMessage.StateSync>().last().state

    @Test fun staleAndMalformedSnapshotsAreIgnored() {
        val t = table()
        val base = netStateOf(t.host.transport)
        val ver = 5000
        t.guest.transport.inject("h", enc(SoccerNetMessage.StateSync(ver, base.copy(score = listOf(1, 0)))))
        assertEquals(listOf(1, 0), t.guest.game.state.value!!.score)
        // same or older version: ignored
        t.guest.transport.inject("h", enc(SoccerNetMessage.StateSync(ver, base.copy(score = listOf(2, 2)))))
        t.guest.transport.inject("h", enc(SoccerNetMessage.StateSync(ver - 1, base.copy(score = listOf(3, 3)))))
        assertEquals(listOf(1, 0), t.guest.game.state.value!!.score)
        // malformed ones: all refused (and they do not burn the version number)
        val bad = listOf(
            base.copy(bodies = base.bodies.dropLast(1)),
            base.copy(bodies = base.bodies.toMutableList().also { it[0] = 1e9 }),
            base.copy(bodies = base.bodies.toMutableList().also { it[2] = 9e5 }),
            base.copy(cooldowns = base.cooldowns.map { 999 }),
            base.copy(phase = "BOGUS"),
            base.copy(score = listOf(-1, 0)),
            base.copy(players = base.players.map { it.copy(playerId = "x") }),
            base.copy(figuresPerTeam = 4),
            base.copy(turnTeam = 5),
            base.copy(hitStrength = 4.0),
            base.copy(sessionWins = listOf(1)),
            base.copy(gone = listOf(9))
        )
        for ((i, b) in bad.withIndex()) {
            t.guest.transport.inject("h", enc(SoccerNetMessage.StateSync(ver + 1 + i, b)))
            assertEquals("bad #$i", listOf(1, 0), t.guest.game.state.value!!.score)
        }
        t.guest.transport.inject("h", enc(SoccerNetMessage.StateSync(ver + 100, base.copy(score = listOf(2, 0)))))
        assertEquals(listOf(2, 0), t.guest.game.state.value!!.score)
    }

    @Test fun aGuestCarriesTheSimulationForwardBetweenSnapshots() {
        val t = table()
        t.guest.game.flick(1, 1, 30.0, 140.0)
        // Cut the host's stream, so both carry the same state forward on their own.
        t.hub.endpoints.getValue("h").router = null
        t.guest.game.stepTicks(24)
        t.host.game.stepTicks(24)
        val hb = t.host.game.state.value!!.bodies
        val gb = t.guest.game.state.value!!.bodies
        for (i in hb.indices) { assertEquals(hb[i].x, gb[i].x, 1e-6); assertEquals(hb[i].y, gb[i].y, 1e-6) }
    }

    @Test fun aGuestNeverDecidesAGoalByItself() {
        val t = table()
        val w = t.guest.game.worldForTest()
        w.x[0] = SoccerRules.CX + 100; w.y[0] = 100.0; w.vy[0] = -1500.0
        t.guest.game.stepTicks(120)
        assertEquals(listOf(0, 0), t.guest.game.state.value!!.score)
        assertEquals(SoccerPhase.PLAYING, t.guest.game.state.value!!.phase)
    }

    @Test fun theHostStreamsAGoalAndTheMatchEndToGuests() {
        val t = table()
        repeat(3) {
            SoccerKit.score(t.host.game, 0)
            t.host.game.stepTicks(SoccerRules.GOAL_TICKS + 700)
        }
        assertEquals(SoccerPhase.OVER, t.host.game.state.value!!.phase)
        val gs = t.guest.game.state.value!!
        assertEquals(SoccerPhase.OVER, gs.phase)
        assertEquals(listOf(3, 0), gs.score)
        assertEquals(0, gs.winnerTeam)
        assertEquals(listOf(1, 0), t.guest.game.sessionWins.value)
        // the guest asks for a rematch, the host starts it
        t.guest.game.playAgain()
        assertEquals(SoccerPhase.COUNTDOWN, t.host.game.state.value!!.phase)
        assertEquals(SoccerPhase.COUNTDOWN, t.guest.game.state.value!!.phase)
        assertEquals(listOf(0, 0), t.guest.game.state.value!!.score)
    }

    @Test fun aLeavingGuestEndsAOneVersusOneAndTheHostRefusesFlicks() {
        val t = table()
        t.host.transport.fireLeft("g")
        assertTrue(t.host.game.opponentLeft.value)
        assertEquals(SoccerReject.OPPONENT_LEFT, t.host.game.flick(0, 1, 0.0, -100.0))
        t.host.transport.fireJoined("g")
        assertFalse(t.host.game.opponentLeft.value)
        assertEquals(SoccerReject.NONE, t.host.game.flick(0, 1, 0.0, -100.0))
    }

    @Test fun aLeavingTeamMateHandsItsFiguresToTheOtherOne() {
        val t = table(seats = 4)
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, t.host.game.flick(0, 2, 0.0, -100.0))
        t.host.transport.fireLeft("g2")
        assertFalse(t.host.game.opponentLeft.value)           // the match goes on
        assertEquals(SoccerReject.NONE, t.host.game.flick(0, 2, 0.0, -100.0))
        // when the other team is gone the match cannot go on
        t.host.transport.fireLeft("g")
        t.host.transport.fireLeft("g3")
        assertTrue(t.host.game.opponentLeft.value)
    }

    @Test fun aGuestWithNoStateYetShowsNothingAndHostsDoNotSnapshot() {
        val hub = ToyHub()
        val roster = listOf(PlayerInfo("h", "A"), PlayerInfo("g", "B"))
        val g = ToySoccerGame(Random(1), ToyClock())
        g.init(GameContext(PlayMode.ONLINE, roster, 1, hub.endpoint("g")))
        assertNull(g.state.value)
        g.startMatch()                                        // a guest never starts a match itself
        assertNull(g.state.value)
        assertNull(g.snapshot())
    }

    @Test fun syncsAreVersionedAndFrequentWhileThingsMove() {
        val t = table()
        val sentBefore = t.host.transport.sent.size
        t.guest.game.flick(1, 1, 0.0, 200.0)
        t.host.game.stepTicks(120)
        val syncs = t.host.transport.sent.drop(sentBefore).map { dec(it.payload) }.filterIsInstance<SoccerNetMessage.StateSync>()
        assertTrue("syncs while moving: ${syncs.size}", syncs.size in 3..40)
        assertEquals(syncs.map { it.version }, syncs.map { it.version }.sorted())
        assertEquals(syncs.size, syncs.map { it.version }.toSet().size)
    }
}
