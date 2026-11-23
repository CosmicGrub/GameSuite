package com.gamesuite.games.slotcars

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

/** Host-authoritative Nearby / Online Slot Cars: guests send intents, identity comes from the transport, syncs are versioned. */
class SlotCarsNetworkedTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun enc(m: SlotCarsNetMessage) = json.encodeToString(SlotCarsNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = json.decodeFromString(SlotCarsNetMessage.serializer(), String(b, Charsets.UTF_8))

    private class Node(val game: SlotCarsGame, val transport: ScFakeTransport)
    private class Table(val hub: ScHub, val nodes: List<Node>) {
        val host get() = nodes[0]
        val guest get() = nodes[1]
    }

    private fun table(seats: Int = 2, mode: PlayMode = PlayMode.LOCAL_AD_HOC, assist: String = "off", live: Boolean = true): Table {
        val hub = ScHub()
        val ids = listOf("h", "g", "g2", "g3").take(seats)
        val roster = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val eps = ids.map { hub.endpoint(it) }
        val nodes = ids.indices.map { i ->
            val g = SlotCarsGame(Random(7 + i), ScClock())
            g.assist = assist
            Node(g, eps[i])
        }
        nodes[0].game.init(GameContext(mode, roster, 0, eps[0]))
        nodes[0].game.startMatch()
        for (i in 1 until seats) nodes[i].game.init(GameContext(mode, roster, i, eps[i]))
        val t = Table(hub, nodes)
        if (live) t.host.game.stepTicks(SlotCarsRules.COUNTDOWN_TICKS + 10)
        return t
    }

    private fun lastSync(t: ScFakeTransport): SlotNetState = t.sent.map { dec(it.payload) }.filterIsInstance<SlotCarsNetMessage.StateSync>().last().state

    @Test fun guestsReceiveTheRaceAndNobodyEverLiesAboutWhoSentWhat() {
        val t = table(seats = 4)
        for (i in 1..3) {
            val gs = t.nodes[i].game.state.value
            assertNotNull("seat $i has a state", gs)
            assertEquals(t.host.game.state.value!!.phase, gs!!.phase)
            assertEquals(4, gs.cars.size)
        }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestThrottleIsAppliedByTheHostAndStreamedBack() {
        val t = table()
        assertEquals(SlotCarsReject.NONE, t.guest.game.setThrottle(1, true))
        t.host.game.stepTicks(90)
        val hs = t.host.game.state.value!!
        assertTrue(hs.cars[1].s > 80.0)
        assertEquals(0.0, hs.cars[0].s, 1e-12)
        assertTrue(t.guest.game.state.value!!.cars[1].s > 50.0)            // the guest sees its car move
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCanOnlyDriveItsOwnCar() {
        val t = table(seats = 3)
        assertEquals(SlotCarsReject.NOT_YOUR_CAR, t.guest.game.setThrottle(0, true))
        assertEquals(SlotCarsReject.NOT_YOUR_CAR, t.host.game.setThrottle(1, true))
        // the wire carries no seat: a request from guest 1 can only ever drive car 1
        t.nodes[1].transport.send("g", "h", enc(SlotCarsNetMessage.Throttle(true)))
        t.host.game.stepTicks(60)
        val c = t.host.game.state.value!!.cars
        assertTrue(c[1].s > 0.0); assertEquals(0.0, c[0].s, 1e-12); assertEquals(0.0, c[2].s, 1e-12)
    }

    @Test fun intentsFromTheHostSeatOrUnknownSendersAreIgnored() {
        val t = table()
        val msg = enc(SlotCarsNetMessage.Throttle(true))
        t.host.transport.inject("h", msg); t.host.transport.inject("nobody", msg); t.host.transport.inject("zz", msg)
        t.host.game.stepTicks(60)
        assertEquals(0.0, t.host.game.state.value!!.cars[0].s, 1e-12)
        assertEquals(0.0, t.host.game.state.value!!.cars[1].s, 1e-12)
        // junk and wrong types
        t.host.transport.inject("g", "garbage".toByteArray()); t.host.transport.inject("g", ByteArray(0))
        t.host.transport.inject("g", "{\"type\":\"nope\"}".toByteArray())
        t.host.game.stepTicks(60)
        assertEquals(0.0, t.host.game.state.value!!.cars[1].s, 1e-12)
    }

    @Test fun onlyTheHostMaySendState() {
        val t = table()
        val gs = t.guest.game.state.value!!
        val forged = SlotCarsNetMessage.StateSync(9999, lastSync(t.host.transport).copy(sessionWins = listOf(5, 0)))
        t.guest.transport.inject("g", enc(forged)); t.guest.transport.inject("nobody", enc(forged))
        assertEquals(listOf(0, 0), t.guest.game.sessionWins.value)
        assertEquals(gs.phase, t.guest.game.state.value!!.phase)
        t.guest.transport.inject("h", enc(forged))
        assertEquals(listOf(5, 0), t.guest.game.sessionWins.value)
        t.host.transport.inject("g", enc(forged))                           // a host never takes state from a guest
        assertEquals(listOf(0, 0), t.host.game.sessionWins.value)
    }

    @Test fun staleAndMalformedSnapshotsAreIgnored() {
        val t = table()
        val base = lastSync(t.host.transport)
        val ver = 5000
        t.guest.transport.inject("h", enc(SlotCarsNetMessage.StateSync(ver, base.copy(sessionWins = listOf(1, 0)))))
        assertEquals(listOf(1, 0), t.guest.game.sessionWins.value)
        t.guest.transport.inject("h", enc(SlotCarsNetMessage.StateSync(ver, base.copy(sessionWins = listOf(2, 2)))))
        t.guest.transport.inject("h", enc(SlotCarsNetMessage.StateSync(ver - 1, base.copy(sessionWins = listOf(3, 3)))))
        assertEquals(listOf(1, 0), t.guest.game.sessionWins.value)
        val c0 = base.cars[0]
        val bad = listOf(
            base.copy(cars = base.cars.dropLast(1)),
            base.copy(cars = listOf(c0.copy(s = 1e9), base.cars[1])),
            base.copy(cars = listOf(c0.copy(v = 9999.0), base.cars[1])),
            base.copy(cars = listOf(c0.copy(off = 999), base.cars[1])),
            base.copy(cars = listOf(c0.copy(s = -5.0), base.cars[1])),
            base.copy(cars = listOf(c0.copy(lapsDone = 99), base.cars[1])),
            base.copy(phase = "BOGUS"), base.copy(track = "mars"), base.copy(laps = 4), base.copy(assist = "turbo"),
            base.copy(players = base.players.map { it.copy(playerId = "x") }),
            base.copy(ready = listOf(true)), base.copy(stepRemaining = 999), base.copy(sessionWins = listOf(1)),
            base.copy(gone = listOf(9)), base.copy(lastDeslotCar = 7), base.copy(lapSerial = -1)
        )
        for ((i, b) in bad.withIndex()) {
            t.guest.transport.inject("h", enc(SlotCarsNetMessage.StateSync(ver + 1 + i, b)))
            assertEquals("bad #$i", listOf(1, 0), t.guest.game.sessionWins.value)
        }
        t.guest.transport.inject("h", enc(SlotCarsNetMessage.StateSync(ver + 100, base.copy(sessionWins = listOf(2, 0)))))
        assertEquals(listOf(2, 0), t.guest.game.sessionWins.value)
    }

    @Test fun aGuestCarriesTheSimulationForwardBetweenSnapshots() {
        val t = table()
        t.guest.game.setThrottle(1, true)
        t.host.game.stepTicks(30)
        t.hub.endpoints.getValue("h").router = null                           // cut the host's stream
        t.guest.game.stepTicks(40); t.host.game.stepTicks(40)
        val h = t.host.game.state.value!!.cars[1]; val g = t.guest.game.state.value!!.cars[1]
        assertEquals(h.s, g.s, 2.0)
    }

    @Test fun aGuestNeverDecidesTheEndOfTheRace() {
        val t = table()
        val sim = t.guest.game.simForTest()
        sim.s[0] = sim.totalDistance - 1.0; sim.lapsDone[0] = 2; sim.v[0] = 150.0; sim.throttle[0] = true
        t.guest.game.stepTicks(5)
        assertFalse(t.guest.game.state.value!!.raceOver)
        assertEquals(0, t.guest.game.sessionWins.value.sum())
    }

    @Test fun theHostStreamsTheFinishAndAGuestRematchStartsAFreshRace() {
        val t = table()
        val hs = t.host.game.simForTest()
        hs.s[1] = hs.totalDistance - 1.0; hs.lapsDone[1] = 2; hs.v[1] = 150.0; hs.throttle[1] = true
        t.host.game.stepTicks(2)
        hs.finishTime[0] = 5000.0
        t.host.game.stepTicks(SlotCarsRules.FINISH_GRACE_TICKS + 5)
        assertTrue(t.host.game.state.value!!.raceOver)
        assertEquals(1, t.host.game.state.value!!.winner)
        val gs = t.guest.game.state.value!!
        assertTrue(gs.raceOver); assertEquals(1, gs.winner)
        assertEquals(listOf(0, 1), t.guest.game.sessionWins.value)
        t.guest.game.playAgain()
        assertEquals(SlotPhase.COUNTDOWN, t.host.game.state.value!!.phase)
        assertEquals(SlotPhase.COUNTDOWN, t.guest.game.state.value!!.phase)
        assertEquals(listOf(0, 1), t.guest.game.sessionWins.value)           // the tally survives the rematch
    }

    @Test fun aLeavingGuestEndsAOneVersusOneAndTheHostRefusesTheirPad() {
        val t = table()
        t.host.transport.fireLeft("g")
        assertTrue(t.host.game.opponentLeft.value)
        assertEquals(SlotCarsReject.OPPONENT_LEFT, t.host.game.setThrottle(0, true))
        t.host.transport.fireJoined("g")
        assertFalse(t.host.game.opponentLeft.value)
        assertEquals(SlotCarsReject.NONE, t.host.game.setThrottle(0, true))
    }

    @Test fun aGuestWithNoStateYetShowsNothingAndHostsDoNotSnapshot() {
        val hub = ScHub()
        val roster = listOf(PlayerInfo("h", "A"), PlayerInfo("g", "B"))
        val g = SlotCarsGame(Random(1), ScClock())
        g.init(GameContext(PlayMode.ONLINE, roster, 1, hub.endpoint("g")))
        assertNull(g.state.value)
        g.startMatch()
        assertNull(g.state.value)
        assertNull(g.snapshot())
        val t = table()
        assertNull(t.host.game.snapshot())
    }

    @Test fun syncsAreVersionedAndFrequentWhileRacing() {
        val t = table()
        t.guest.game.setThrottle(1, true)
        val before = t.host.transport.sent.size
        t.host.game.stepTicks(120)
        val syncs = t.host.transport.sent.drop(before).map { dec(it.payload) }.filterIsInstance<SlotCarsNetMessage.StateSync>()
        assertTrue("syncs while racing: ${syncs.size}", syncs.size in 10..60)
        assertEquals(syncs.map { it.version }, syncs.map { it.version }.sorted())
        assertEquals(syncs.size, syncs.map { it.version }.toSet().size)
    }

    @Test fun stepByStepWaitsForGuestsReadyIntent() {
        val t = table(assist = "step")
        t.guest.game.setThrottle(1, true)
        t.host.game.setThrottle(0, true)
        t.host.game.setReady(0, true)
        t.host.game.stepTicks(60)
        assertEquals(0.0, t.host.game.state.value!!.cars[0].s, 1e-12)        // waiting for the guest
        assertEquals(SlotCarsReject.NONE, t.guest.game.setReady(1, true))
        t.host.game.stepTicks(SlotCarsRules.STEP_TICKS)
        val c = t.host.game.state.value!!.cars
        assertTrue(c[0].s > 20.0 && c[1].s > 20.0)
        // a forged Ready from the host's own id does nothing, and Ready is refused outside step mode
        val t2 = table(assist = "off")
        t2.host.transport.inject("g", enc(SlotCarsNetMessage.Ready(true)))
        assertFalse(t2.host.game.state.value!!.cars[1].ready)
    }
}
