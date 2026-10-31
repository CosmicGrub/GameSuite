package com.gamesuite.games.minigolf

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.billiards.TsFakeTransport
import com.gamesuite.games.billiards.TsHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Mini Golf: the host runs the physics, guests only ask. */
class GolfNetworkedTest {
    private class Table(val hub: TsHub, val host: GolfGame, val guests: List<GolfGame>, val hostT: TsFakeTransport, val guestT: List<TsFakeTransport>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private fun encode(m: GolfNetMessage) = json.encodeToString(GolfNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(guestCount: Int = 1, holes: Int = 3): Table {
        val players = List(guestCount + 1) { if (it == 0) PlayerInfo("h", "Host") else PlayerInfo("g$it", "Guest $it") }
        val hub = TsHub()
        val ht = hub.endpoint("h")
        val gts = (1..guestCount).map { hub.endpoint("g$it") }
        val host = GolfGame(Random(3)) { 1L }
        host.holeCount = holes
        host.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 0, ht))
        val guests = (1..guestCount).map { i ->
            GolfGame(Random(900 + i)) { 1L }.also { it.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, gts[i - 1])) }
        }
        host.startMatch()
        return Table(hub, host, guests, ht, gts)
    }

    @Test fun guestsReceiveTheOpeningStateFromTheHost() {
        val t = table(2)
        t.guests.forEach { assertEquals(t.host.state.value, it.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun theHostPuttsAndEveryGuestSeesTheSameBallAndTheSameStroke() {
        val t = table(2)
        assertTrue(t.host.putt(GolfShot(0.1, 0.5)))
        t.guests.forEach { assertEquals(t.host.state.value, it.state.value) }
        val rec = t.guests[0].state.value!!.last!!
        val replay = GolfPhysics.simulate(GolfCourse.holes[0], rec.startX, rec.startY, rec.shot)
        assertEquals(t.guests[0].state.value!!.ballX[0], replay.x, 0.0)
    }

    @Test fun aGuestPuttGoesThroughTheHostOnItsOwnTurnOnly() {
        val t = table()
        val g = t.guests[0]
        assertFalse(g.putt(GolfShot(0.0, 0.5))) // the host is first
        t.host.putt(GolfShot(Math.PI / 2, 0.1))
        assertTrue(g.isMyTurn())
        assertTrue(g.putt(GolfShot(0.0, 0.5)))
        assertEquals(t.host.state.value, g.state.value)
        assertEquals(1, t.host.state.value!!.strokes[1][0])
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun forgedAndInvalidRequestsAreIgnoredByTheHost() {
        val t = table(2)
        val before = t.host.state.value
        // guest 1 puts on the host's turn; guest 2 claims to be guest 1
        t.guestT[0].send("g1", "h", encode(GolfNetMessage.StrokeRequest(GolfShot(0.0, 0.5))))
        t.guestT[1].send("g1", "h", encode(GolfNetMessage.StrokeRequest(GolfShot(0.0, 0.5))))
        assertTrue(before === t.host.state.value)
        t.host.putt(GolfShot(Math.PI / 2, 0.1)) // now it is g1's turn
        val mid = t.host.state.value
        t.guestT[1].send("g1", "h", encode(GolfNetMessage.StrokeRequest(GolfShot(0.0, 0.5)))) // g2 forging g1
        assertTrue(mid === t.host.state.value)
        t.guestT[0].send("g1", "h", encode(GolfNetMessage.StrokeRequest(GolfShot(Double.NaN, 0.5))))
        t.guestT[0].send("g1", "h", encode(GolfNetMessage.StrokeRequest(GolfShot(0.0, 9.0))))
        t.guestT[0].send("g1", "h", "{}".toByteArray())
        assertTrue(mid === t.host.state.value)
        assertTrue(t.hub.senderLies.isNotEmpty())
    }

    @Test fun badOrStaleStatesFromTheHostAreRefusedAndOthersAreIgnored() {
        val t = table()
        val g = t.guests[0]
        val good = g.state.value!!
        t.guestT[0].inject("x", encode(GolfNetMessage.StateSync(99, good.copy(current = 0))))
        t.guestT[0].inject("h", encode(GolfNetMessage.StateSync(50, good.copy(hole = 7))))
        t.guestT[0].inject("h", encode(GolfNetMessage.StateSync(51, good.copy(ballX = listOf(-50.0, 3.0)))))
        t.guestT[0].inject("h", encode(GolfNetMessage.StateSync(52, good.copy(limit = 100))))
        t.guestT[0].inject("h", encode(GolfNetMessage.StateSync(53, good.copy(seats = good.seats.reversed()))))
        assertEquals(good, g.state.value)
        t.host.putt(GolfShot(0.0, 0.3))
        val after = g.state.value!!
        t.guestT[0].inject("h", encode(GolfNetMessage.StateSync(1, good)))
        assertEquals(after, g.state.value)
    }

    @Test fun anyGuestCanMoveTheTableOnToTheNextHole() {
        val t = table()
        var guard = 0
        while (t.host.state.value!!.phase == GolfPhase.AIM && guard++ < 50) {
            val s = t.host.state.value!!
            val shot = GolfBot.chooseShot(GolfCourse.holes[s.hole], s.ballX[s.current], s.ballY[s.current], CpuDifficulty_HARD, Random(1), exact = true)
            val actor = if (s.current == 0) t.host else t.guests[0]
            assertTrue(actor.putt(shot))
        }
        assertEquals(GolfPhase.HOLE_DONE, t.host.state.value!!.phase)
        t.guests[0].nextHole()
        assertEquals(1, t.host.state.value!!.hole)
        assertEquals(t.host.state.value, t.guests[0].state.value)
    }

    @Test fun reconnectsAndLeavesAreHandled() {
        val t = table()
        t.host.putt(GolfShot(0.0, 0.3))
        t.guestT[0].fireReconnected()
        assertEquals(t.host.state.value, t.guests[0].state.value)
        t.guestT[0].fireLeft("h")
        assertTrue(t.guests[0].hostLeft.value); assertFalse(t.guests[0].isMyTurn())
        t.guestT[0].fireJoined("h")
        assertFalse(t.guests[0].hostLeft.value)
        t.hostT.fireLeft("g1")
        assertTrue(t.host.state.value!!.seats[1].isBot)
        t.hostT.fireJoined("g1")
        assertFalse(t.host.state.value!!.seats[1].isBot)
    }

    @Test fun nothingIsSavedOrUndoneOnline() {
        val t = table()
        t.host.putt(GolfShot(0.0, 0.3))
        assertFalse(t.host.canUndo)
        assertEquals(null, t.guests[0].snapshot())
    }

    private val CpuDifficulty_HARD = com.gamesuite.settings.CpuDifficulty.HARD
}
