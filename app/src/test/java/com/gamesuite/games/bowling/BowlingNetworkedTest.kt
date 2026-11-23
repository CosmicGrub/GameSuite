package com.gamesuite.games.bowling

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.darts.TbFakeTransport
import com.gamesuite.games.darts.TbHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Bowling: the host owns the engine, the lane physics and the scatter. */
class BowlingNetworkedTest {
    private class Node(val game: BowlingGame, val transport: TbFakeTransport)
    private class Table(val hub: TbHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun encode(m: BowlingNetMessage) = Json.encodeToString(BowlingNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private val shot = BowlShot(0.0, 0.0, 7.5, 0.0)

    private fun table(seats: Int): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = TbHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = BowlingGame(if (i == 0) Random(5) else Random(9000 + i))
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    /** Bowls the host's whole frame so that seat 1 is up. */
    private fun hostFrame(t: Table) {
        t.host.game.roll(shot)
        while (t.host.game.state.value!!.current == 0) t.host.game.roll(shot)
    }

    @Test fun guestsReceiveTheOpeningLaneFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestsRollIsScatteredAndSimulatedByTheHost() {
        val t = table(2)
        hostFrame(t)
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        val serial = t.host.game.state.value!!.rollSerial
        assertTrue(g1.roll(BowlShot(0.1, -0.004, 7.2, 0.5)))
        val s = t.host.game.state.value!!
        assertEquals(serial + 1, s.rollSerial)
        assertEquals(1, s.roller)
        // the host's seeded stream decides the scatter, not the guest's own Random
        val expected = BowlingScatter.NORMAL.apply(BowlShot(0.1, -0.004, 7.2, 0.5), bowlingRollRandom(Random(5).nextLong(), serial))
        assertEquals(expected, s.lastShot)
        assertEquals(s, g1.state.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotRollOutOfTurn() {
        val t = table(2)
        val g1 = t.nodes[1].game
        assertFalse(g1.isMyTurn())
        assertFalse(g1.roll(shot))
        val before = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(BowlingNetMessage.RollRequest(shot)))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun identityComesFromTheTransportNeverFromTheMessage() {
        val t = table(3)
        val before = t.host.game.state.value
        t.nodes[2].transport.send("h", "h", encode(BowlingNetMessage.RollRequest(shot)))
        t.nodes[2].transport.send("g1", "h", encode(BowlingNetMessage.RollRequest(shot)))
        assertTrue(before === t.host.game.state.value)
        assertEquals(2, t.hub.senderLies.size)
    }

    @Test fun badShotsAndGarbageFromAGuestAreRefused() {
        val t = table(2)
        hostFrame(t)
        val s = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(BowlingNetMessage.RollRequest(BowlShot(0.0, 0.0, 400.0, 0.0))))
        t.nodes[1].transport.send("g1", "h", encode(BowlingNetMessage.RollRequest(BowlShot(0.9, 0.0, 7.0, 0.0))))
        t.nodes[1].transport.send("g1", "h", encode(BowlingNetMessage.RollRequest(BowlShot(0.0, 0.0, 7.0, 55.0))))
        t.nodes[1].transport.send("g1", "h", "garbage".toByteArray())
        assertTrue(s === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndImpossibleSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value!!
        g.transport.inject("g1", encode(BowlingNetMessage.StateSync(50, real.copy(current = 1), emptyMap())))
        assertTrue(real === g.game.state.value)
        val badRolls = real.copy(players = real.players.mapIndexed { i, p -> if (i == 0) p.copy(rolls = listOf(14)) else p })
        g.transport.inject("h", encode(BowlingNetMessage.StateSync(51, badRolls, emptyMap())))
        assertTrue(real === g.game.state.value)
        val wrongPins = real.copy(standing = 0b11)
        g.transport.inject("h", encode(BowlingNetMessage.StateSync(52, wrongPins, emptyMap())))
        assertTrue(real === g.game.state.value)
        val roster = real.copy(players = real.players.map { it.copy(playerId = "evil") })
        g.transport.inject("h", encode(BowlingNetMessage.StateSync(53, roster, emptyMap())))
        assertTrue(real === g.game.state.value)
        t.host.game.roll(shot)
        val applied = g.game.state.value
        assertEquals(t.host.game.state.value, applied)
        g.transport.inject("h", encode(BowlingNetMessage.StateSync(1, real, emptyMap())))
        assertTrue(applied === g.game.state.value)
    }

    @Test fun reconnectingGuestAsksForTheLane() {
        val t = table(2)
        t.host.game.roll(shot)
        t.nodes[1].transport.reconnectedListeners.forEach { it() }
        assertEquals(t.host.game.state.value, t.nodes[1].game.state.value)
    }

    @Test fun aLeavingGuestIsTakenOverByTheComputerAndCanReturn() {
        val t = table(3)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.players[1].isBot)
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
    }

    @Test fun hostLeavingFlagsGuests() {
        val t = table(2)
        t.nodes[1].transport.fireLeft("h")
        assertTrue(t.nodes[1].game.hostLeft.value)
        assertFalse(t.nodes[1].game.isMyTurn())
        t.nodes[1].transport.fireJoined("h")
        assertFalse(t.nodes[1].game.hostLeft.value)
    }
}
