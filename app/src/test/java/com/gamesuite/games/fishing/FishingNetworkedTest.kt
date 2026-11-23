package com.gamesuite.games.fishing

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.slotcars.ScClock
import com.gamesuite.games.slotcars.ScFakeTransport
import com.gamesuite.games.slotcars.ScHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Fishing: guests send intents, identity comes from the transport, the fish stays hidden until hooked. */
class FishingNetworkedTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun enc(m: FishingNetMessage) = json.encodeToString(FishingNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = json.decodeFromString(FishingNetMessage.serializer(), String(b, Charsets.UTF_8))

    private class Node(val game: FishingGame, val transport: ScFakeTransport)
    private class Table(val hub: ScHub, val nodes: List<Node>) {
        val host get() = nodes[0]
        val guest get() = nodes[1]
    }

    private fun table(seats: Int = 2, mode: PlayMode = PlayMode.LOCAL_AD_HOC, relaxed: Boolean = false, rounds: Int = 3): Table {
        val hub = ScHub()
        val ids = listOf("h", "g", "g2", "g3").take(seats)
        val roster = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val eps = ids.map { hub.endpoint(it) }
        val nodes = ids.indices.map { i ->
            val g = FishingGame(Random(7 + i), ScClock())
            g.relaxed = relaxed; g.rounds = rounds; g.lake = "lake"
            Node(g, eps[i])
        }
        nodes[0].game.init(GameContext(mode, roster, 0, eps[0]))
        nodes[0].game.startMatch()
        for (i in 1 until seats) nodes[i].game.init(GameContext(mode, roster, i, eps[i]))
        return Table(hub, nodes)
    }

    private fun lastSync(t: ScFakeTransport): FishNetState = t.sent.map { dec(it.payload) }.filterIsInstance<FishingNetMessage.StateSync>().last().state

    /** The host's own turn, played to the end of its result. */
    private fun hostTurn(t: Table) {
        FishKit.playTurn(t.host.game, 0)
        t.host.game.stepTicks(FishingRules.RESULT_TICKS + 2)
    }

    @Test fun guestsReceiveTheContestAndNobodyEverLiesAboutWhoSentWhat() {
        val t = table(seats = 4)
        for (i in 1..3) {
            val gs = t.nodes[i].game.state.value
            assertNotNull("seat $i has a state", gs)
            assertEquals(FishPhase.AIM, gs!!.phase); assertEquals(4, gs.players.size)
        }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestPlaysItsOwnTurnThroughTheHost() {
        val t = table()
        hostTurn(t)
        assertEquals(1, t.host.game.state.value!!.seat)
        assertEquals(1, t.guest.game.state.value!!.seat)
        assertEquals(FishingReject.NONE, t.guest.game.setAim(1, 4))
        assertEquals(4, t.host.game.state.value!!.aimCol)
        assertEquals(FishingReject.NONE, t.guest.game.setHold(1, true))
        t.host.game.stepTicks(20)
        assertEquals(FishPhase.CHARGING, t.host.game.state.value!!.phase)
        assertEquals(FishPhase.CHARGING, t.guest.game.state.value!!.phase)
        t.guest.game.setHold(1, false)
        t.host.game.stepTicks(2)
        assertEquals(1, t.host.game.state.value!!.castSerial - 1)
        assertEquals(FishPhase.CASTING, t.host.game.state.value!!.phase)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun nobodyCanActOutOfTurn() {
        val t = table(seats = 3)
        assertEquals(FishingReject.NOT_YOUR_TURN, t.guest.game.setHold(1, true))            // seat 0 is on turn
        assertEquals(FishingReject.NOT_YOUR_TURN, t.guest.game.setHold(0, true))            // and a guest never drives seat 0
        // a forged request from a guest who is not on turn does nothing
        t.nodes[1].transport.send("g", "h", enc(FishingNetMessage.Hold(true)))
        t.nodes[2].transport.send("g2", "h", enc(FishingNetMessage.Aim(0)))
        t.host.game.stepTicks(30)
        assertEquals(FishPhase.AIM, t.host.game.state.value!!.phase)
        assertEquals(2, t.host.game.state.value!!.aimCol)
    }

    @Test fun intentsFromTheHostSeatOrUnknownSendersAreIgnored() {
        val t = table()
        hostTurn(t)                                                                           // now seat 1's turn
        val hold = enc(FishingNetMessage.Hold(true))
        t.host.transport.inject("h", hold); t.host.transport.inject("nobody", hold); t.host.transport.inject("zz", hold)
        t.host.game.stepTicks(30)
        assertEquals(FishPhase.AIM, t.host.game.state.value!!.phase)
        t.host.transport.inject("g", "garbage".toByteArray()); t.host.transport.inject("g", ByteArray(0))
        t.host.transport.inject("g", "{\"type\":\"nope\"}".toByteArray())
        t.host.transport.inject("g", enc(FishingNetMessage.Aim(99))); t.host.transport.inject("g", enc(FishingNetMessage.Aim(-1)))
        t.host.game.stepTicks(5)
        assertEquals(FishPhase.AIM, t.host.game.state.value!!.phase)
        assertEquals(2, t.host.game.state.value!!.aimCol)
    }

    @Test fun onlyTheHostMaySendState() {
        val t = table()
        val forged = FishingNetMessage.StateSync(9999, lastSync(t.host.transport).copy(sessionWins = listOf(5, 0)))
        t.guest.transport.inject("g", enc(forged)); t.guest.transport.inject("nobody", enc(forged))
        assertEquals(listOf(0, 0), t.guest.game.sessionWins.value)
        t.guest.transport.inject("h", enc(forged))
        assertEquals(listOf(5, 0), t.guest.game.sessionWins.value)
        t.host.transport.inject("g", enc(forged))
        assertEquals(listOf(0, 0), t.host.game.sessionWins.value)
    }

    @Test fun staleAndMalformedSnapshotsAreIgnored() {
        val t = table()
        val base = lastSync(t.host.transport)
        val ver = 5000
        t.guest.transport.inject("h", enc(FishingNetMessage.StateSync(ver, base.copy(sessionWins = listOf(1, 0)))))
        assertEquals(listOf(1, 0), t.guest.game.sessionWins.value)
        t.guest.transport.inject("h", enc(FishingNetMessage.StateSync(ver, base.copy(sessionWins = listOf(2, 2)))))
        t.guest.transport.inject("h", enc(FishingNetMessage.StateSync(ver - 1, base.copy(sessionWins = listOf(3, 3)))))
        assertEquals(listOf(1, 0), t.guest.game.sessionWins.value)
        val bad = listOf(
            base.copy(phase = "BOGUS"), base.copy(outcome = "BOGUS"), base.copy(lake = "mars"), base.copy(rounds = 4),
            base.copy(players = base.players.map { it.copy(playerId = "x") }), base.copy(round = 99), base.copy(turnIndex = 5),
            base.copy(aimCol = 9), base.copy(band = 4), base.copy(power = 2.0), base.copy(tension = 5.0), base.copy(distance = -3.0),
            base.copy(casts = listOf(1)), base.copy(grams = listOf(-1, 0)), base.copy(grams = listOf(99999999, 0)),
            base.copy(catches = listOf(listOf(FishNetCatch("dragon", 100)), emptyList())),
            base.copy(catches = listOf(listOf(FishNetCatch("carp", 1)), emptyList())),
            base.copy(hooked = FishNetCatch("carp", 10)), base.copy(hooked = FishNetCatch("dragon", 10)),
            base.copy(rushes = List(50) { FishNetRush(1, 10, 50) }), base.copy(rushes = listOf(FishNetRush(1, 10, 500))),
            base.copy(castSerial = -1), base.copy(sessionWins = listOf(1)), base.copy(gone = listOf(9)), base.copy(phaseTicks = -1)
        )
        for ((i, b) in bad.withIndex()) {
            t.guest.transport.inject("h", enc(FishingNetMessage.StateSync(ver + 1 + i, b)))
            assertEquals("bad #$i", listOf(1, 0), t.guest.game.sessionWins.value)
        }
        t.guest.transport.inject("h", enc(FishingNetMessage.StateSync(ver + 100, base.copy(sessionWins = listOf(2, 0)))))
        assertEquals(listOf(2, 0), t.guest.game.sessionWins.value)
    }

    @Test fun theFishStaysSecretOnTheWireUntilItIsHooked() {
        val t = table()
        t.host.game.setHold(0, true); t.host.game.stepTicks(40); t.host.game.setHold(0, false)
        var guard = 0
        while (t.host.game.state.value!!.phase != FishPhase.BITE && guard++ < 3000) {
            t.host.game.stepTicks(1)
            assertNull(t.guest.game.state.value!!.fishId)
        }
        // not one snapshot sent so far carries the fish
        val so = t.host.transport.sent.map { dec(it.payload) }.filterIsInstance<FishingNetMessage.StateSync>()
        assertTrue(so.isNotEmpty() && so.all { it.state.hooked == null && it.state.rushes.isEmpty() })
        t.host.game.setHold(0, true); t.host.game.stepTicks(2)
        val after = lastSync(t.host.transport)
        assertNotNull(after.hooked); assertTrue(after.rushes.isNotEmpty())
        assertNotNull(t.guest.game.state.value!!.fishId)
    }

    @Test fun aGuestCarriesTheReelInForwardBetweenSnapshots() {
        val t = table()
        t.host.game.setHold(0, true); t.host.game.stepTicks(40); t.host.game.setHold(0, false)
        var guard = 0
        while (t.host.game.state.value!!.phase != FishPhase.BITE && guard++ < 3000) t.host.game.stepTicks(1)
        t.host.game.setHold(0, true); t.host.game.stepTicks(3)
        assertEquals(FishPhase.REELING, t.guest.game.state.value!!.phase)
        t.hub.endpoints.getValue("h").router = null                                           // cut the host's stream
        t.host.game.stepTicks(30); t.guest.game.stepTicks(30)
        val h = t.host.game.state.value!!; val g = t.guest.game.state.value!!
        assertEquals(h.tension, g.tension, 0.12)
        assertEquals(h.distance, g.distance, 1.5)
    }

    @Test fun aGuestNeverDecidesACatchByItself() {
        val t = table()
        t.host.game.setHold(0, true); t.host.game.stepTicks(40); t.host.game.setHold(0, false)
        var guard = 0
        while (t.host.game.state.value!!.phase != FishPhase.BITE && guard++ < 3000) t.host.game.stepTicks(1)
        t.host.game.setHold(0, true); t.host.game.stepTicks(3)
        val e = t.guest.game.engineForTest()
        e.distance = 0.0; e.tension = 1.04
        t.guest.game.stepTicks(20)
        assertEquals(FishPhase.REELING, t.guest.game.state.value!!.phase)                     // only the host's snapshot can end it
        assertEquals(listOf(0, 0), t.guest.game.state.value!!.grams)
    }

    @Test fun theHostStreamsTheEndAndAGuestRematchStartsAFreshContest() {
        val t = table(rounds = 3)
        var guard = 0
        while (!t.host.game.state.value!!.over && guard++ < 400000) t.host.game.stepTicks(30)       // nobody fishes: every turn times out
        assertTrue(t.host.game.state.value!!.over)
        assertTrue(t.guest.game.state.value!!.over)
        assertEquals(listOf(1, 1), t.guest.game.sessionWins.value)                                // a scoreless tie is shared
        t.guest.game.playAgain()
        assertEquals(FishPhase.AIM, t.host.game.state.value!!.phase); assertEquals(0, t.host.game.state.value!!.round)
        assertFalse(t.guest.game.state.value!!.over)
        assertEquals(listOf(1, 1), t.guest.game.sessionWins.value)
    }

    @Test fun aLeavingAnglerEndsTheContestForTheRestAndTheHostRefusesTheirButton() {
        val t = table()
        t.host.transport.fireLeft("g")
        assertTrue(t.host.game.opponentLeft.value)
        assertEquals(FishingReject.OPPONENT_LEFT, t.host.game.setHold(0, true))
        t.host.transport.fireJoined("g")
        assertFalse(t.host.game.opponentLeft.value)
        assertEquals(FishingReject.NONE, t.host.game.setHold(0, true))
    }

    @Test fun aGuestWithNoStateYetShowsNothingAndHostsDoNotSnapshot() {
        val hub = ScHub()
        val roster = listOf(PlayerInfo("h", "A"), PlayerInfo("g", "B"))
        val g = FishingGame(Random(1), ScClock())
        g.init(GameContext(PlayMode.ONLINE, roster, 1, hub.endpoint("g")))
        assertNull(g.state.value)
        g.startMatch()
        assertNull(g.state.value); assertNull(g.snapshot())
        assertNull(table().host.game.snapshot())
    }

    @Test fun syncsAreVersionedAndFastWhileAFishIsOnTheLine() {
        val t = table()
        t.host.game.setHold(0, true); t.host.game.stepTicks(40); t.host.game.setHold(0, false)
        var guard = 0
        while (t.host.game.state.value!!.phase != FishPhase.BITE && guard++ < 3000) t.host.game.stepTicks(1)
        t.host.game.setHold(0, true); t.host.game.stepTicks(2)
        val before = t.host.transport.sent.size
        t.host.game.stepTicks(60)
        val syncs = t.host.transport.sent.drop(before).map { dec(it.payload) }.filterIsInstance<FishingNetMessage.StateSync>()
        assertTrue("syncs while reeling: ${syncs.size}", syncs.size in 15..40)
        assertEquals(syncs.map { it.version }, syncs.map { it.version }.sorted())
        assertEquals(syncs.size, syncs.map { it.version }.toSet().size)
    }
}
