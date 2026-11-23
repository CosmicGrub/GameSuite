package com.gamesuite.games.billiards

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

/** Host-authoritative Nearby / Online Billiards: the host simulates and every device agrees. */
class BilliardsNetworkedTest {
    private class Table(val hub: TsHub, val host: BilliardsGame, val guest: BilliardsGame, val hostT: TsFakeTransport, val guestT: TsFakeTransport)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private fun encode(m: BilliardsNetMessage) = json.encodeToString(BilliardsNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(guestBreaks: Boolean = false, variant: String = BilliardsVariant.EIGHT): Table {
        val players = listOf(PlayerInfo("h", "Host"), PlayerInfo("g", "Guest"))
        val hub = TsHub()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val host = BilliardsGame(Random(11)) { 1L }
        val guest = BilliardsGame(Random(12345)) { 1L } // a guest's own random must never be used
        host.variant = variant
        if (guestBreaks) host.firstBreaker = 1
        host.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 0, ht))
        guest.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 1, gt))
        host.startMatch()
        return Table(hub, host, guest, ht, gt)
    }

    private fun breakAt(g: BilliardsGame, power: Double = 1.0): Shot {
        val s = g.state.value!!
        return Shot(Math.atan2(25.0 - s.y[0], 75.0 - s.x[0]), power, 0.0, 0.0, s.x[0], s.y[0])
    }

    @Test fun theGuestReceivesTheOpeningTableFromTheHost() {
        val t = table()
        assertEquals(t.host.state.value, t.guest.state.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun theHostSimulatesAndTheGuestGetsTheSameTableAndTheSameStroke() {
        val t = table()
        assertTrue(t.host.shoot(breakAt(t.host)))
        assertEquals(t.host.state.value, t.guest.state.value)
        assertEquals(1, t.guest.state.value!!.shotNo)
        // the guest can replay the recorded stroke itself and land on the host's positions
        val rec = t.guest.state.value!!.lastShot!!
        val replay = BilliardsPhysics.simulate(rec.startX.toDoubleArray(), rec.startY.toDoubleArray(), rec.startOn.toBooleanArray(), rec.shot)
        val s = t.guest.state.value!!
        for (i in 0 until 16) if (s.onTable[i] && replay.onTable[i]) assertEquals(s.x[i], replay.x[i], 0.0)
    }

    @Test fun aGuestStrokeGoesThroughTheHostAndIsNeverSimulatedLocally() {
        val t = table(guestBreaks = true)
        assertEquals(1, t.host.state.value!!.shooter)
        assertTrue(t.guest.isMyTurn()); assertFalse(t.host.isMyTurn())
        val before = t.guest.state.value
        assertTrue(t.guest.shoot(breakAt(t.guest)))
        assertEquals(t.host.state.value, t.guest.state.value)
        assertNotEquals(before, t.guest.state.value)
        assertEquals(1, t.host.state.value!!.shotNo)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotShootOnTheHostsTurn() {
        val t = table()
        val before = t.host.state.value
        assertFalse(t.guest.shoot(breakAt(t.guest)))                 // refused locally
        t.guestT.send("g", "h", encode(BilliardsNetMessage.ShotRequest(breakAt(t.host)))) // forced onto the wire
        assertTrue(before === t.host.state.value)
    }

    @Test fun anInvalidOrForgedRequestIsIgnoredByTheHost() {
        val t = table(guestBreaks = true)
        val before = t.host.state.value
        val ok = breakAt(t.guest)
        t.guestT.send("g", "h", encode(BilliardsNetMessage.ShotRequest(ok.copy(power = 7.0))))
        t.guestT.send("g", "h", encode(BilliardsNetMessage.ShotRequest(ok.copy(angle = Double.NaN))))
        t.guestT.send("g", "h", encode(BilliardsNetMessage.ShotRequest(ok.copy(cueX = 40.0)))) // not ball in hand: cannot move the cue ball
        t.guestT.inject("h", ByteArray(5) { 1 })
        t.guestT.send("g", "h", "garbage".toByteArray())
        assertTrue(before === t.host.state.value)
        // claiming to be the host changes nothing: identity is the endpoint
        t.guestT.send("h", "h", encode(BilliardsNetMessage.ShotRequest(ok.copy(power = 7.0))))
        assertTrue(before === t.host.state.value)
        assertTrue(t.hub.senderLies.isNotEmpty())
    }

    @Test fun aStateSyncFromAnyoneButTheHostIsIgnoredAndBadOrStaleStatesAreRefused() {
        val t = table()
        val good = t.guest.state.value!!
        // a rogue endpoint claims a state
        t.guestT.inject("x", encode(BilliardsNetMessage.StateSync(99, good.copy(shooter = 1))))
        assertEquals(good, t.guest.state.value)
        // the host's own id but an impossible table (cue ball missing, a ball outside the cloth)
        t.guestT.inject("h", encode(BilliardsNetMessage.StateSync(50, good.copy(onTable = good.onTable.toMutableList().also { it[0] = false }))))
        t.guestT.inject("h", encode(BilliardsNetMessage.StateSync(51, good.copy(x = good.x.toMutableList().also { it[3] = 500.0 }))))
        t.guestT.inject("h", encode(BilliardsNetMessage.StateSync(52, good.copy(variant = "snooker"))))
        t.guestT.inject("h", encode(BilliardsNetMessage.StateSync(53, good.copy(seats = good.seats.reversed()))))
        assertEquals(good, t.guest.state.value)
        // a real later state is taken, then an older version is not
        assertTrue(t.host.shoot(breakAt(t.host)))
        val after = t.guest.state.value!!
        assertEquals(1, after.shotNo)
        t.guestT.inject("h", encode(BilliardsNetMessage.StateSync(1, good)))
        assertEquals(after, t.guest.state.value)
    }

    @Test fun aGuestWhoReconnectsIsSentTheTableAgain() {
        val t = table()
        t.host.shoot(breakAt(t.host))
        t.guestT.fireReconnected()
        assertEquals(t.host.state.value, t.guest.state.value)
    }

    @Test fun theGuestKnowsWhenTheHostLeavesAndWhenItReturns() {
        val t = table()
        t.guestT.fireLeft("h")
        assertTrue(t.guest.hostLeft.value)
        assertFalse(t.guest.isMyTurn())
        t.guestT.fireJoined("h")
        assertFalse(t.guest.hostLeft.value)
    }

    @Test fun aLeavingGuestIsReplacedByTheComputerOnTheHost() {
        val t = table()
        t.hostT.fireLeft("g")
        assertTrue(t.host.state.value!!.seats[1].isBot)
        t.hostT.fireJoined("g")
        assertFalse(t.host.state.value!!.seats[1].isBot)
    }

    @Test fun theGuestHasNoUndoAndNoSnapshot() {
        val t = table()
        t.host.shoot(breakAt(t.host))
        assertFalse(t.host.canUndo); assertFalse(t.guest.canUndo)
        assertEquals(null, t.guest.snapshot())
    }
}
