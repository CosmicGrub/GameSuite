package com.gamesuite.games.tankbattle

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.darts.TbHub
import com.gamesuite.games.darts.TbFakeTransport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Tank Battle: the host simulates every shot and broadcasts it. */
class TankNetworkedTest {
    private class Node(val game: TankGame, val transport: TbFakeTransport)
    private class Table(val hub: TbHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun encode(m: TankNetMessage) = Json.encodeToString(TankNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, hostSeed: Long = 5L): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = TbHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = TankGame(if (i == 0) Random(hostSeed) else Random(9000 + i)) // a guest's Random must never matter
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    /** Fires from whoever is on turn until [seat] is. */
    private fun Table.advanceTo(seat: Int) {
        var guard = 0
        while (host.game.state.value!!.current != seat && guard++ < 8) {
            val cur = host.game.state.value!!.current
            assertTrue(nodes[cur].game.fire(60, 55, TankWeapons.SHELL))
        }
        assertEquals(seat, host.game.state.value!!.current)
    }

    @Test fun guestsReceiveTheOpeningBattlefieldFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestsShotIsSimulatedByTheHostAndBroadcastWithItsPath() {
        val t = table(2)
        t.advanceTo(1)
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        val before = t.host.game.state.value!!
        assertTrue(g1.fire(120, 66, TankWeapons.BIG))
        val s = t.host.game.state.value!!
        assertEquals(before.shotSerial + 1, s.shotSerial)
        assertEquals(1, s.lastShot!!.seat)
        assertEquals(TankWeapons.BIG, s.lastShot!!.weapon)
        assertEquals(1, s.tanks[1].bigLeft)
        // the host's own simulation gives the very same result
        val expect = TankSim.simulate(before.terrain.toIntArray(), before.tanks.map { TankBody(it.x, it.alive) },
            IntArray(2) { before.tanks[it].hp }, 1, TankWeapons.BIG, 120, 66, before.wind)
        assertEquals(expect.impacts, s.lastShot!!.impacts)
        assertEquals(s, g1.state.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotFireOutOfTurnOrAtTheHostsExpense() {
        val t = table(2)
        val g1 = t.nodes[1].game
        assertFalse(g1.isMyTurn() && t.host.game.state.value!!.current == 0)
        t.advanceTo(0)
        assertFalse(g1.isMyTurn())
        assertFalse(g1.fire(60, 60, TankWeapons.SHELL))
        val before = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(TankNetMessage.FireRequest(60, 60, TankWeapons.SHELL)))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun identityComesFromTheTransportNeverFromTheMessage() {
        val t = table(3)
        t.advanceTo(0)
        val before = t.host.game.state.value
        t.nodes[2].transport.send("h", "h", encode(TankNetMessage.FireRequest(60, 60, TankWeapons.SHELL)))
        t.nodes[2].transport.send("g1", "h", encode(TankNetMessage.FireRequest(60, 60, TankWeapons.SHELL)))
        assertTrue(before === t.host.game.state.value)
        assertEquals(2, t.hub.senderLies.size)
        val other = t.nodes[1].game.state.value!!
        t.nodes[2].transport.send("g2", "g1", encode(TankNetMessage.StateSync(99, other.copy(current = 2), emptyMap())))
        assertTrue(other === t.nodes[1].game.state.value)
    }

    @Test fun badShotsFromAGuestAreRefusedByTheHost() {
        val t = table(2)
        t.advanceTo(1)
        val s = t.host.game.state.value
        listOf(
            TankNetMessage.FireRequest(999, 50, TankWeapons.SHELL), TankNetMessage.FireRequest(90, 0, TankWeapons.SHELL),
            TankNetMessage.FireRequest(90, 50, "laser"), TankNetMessage.FireRequest(-5, 50, TankWeapons.SHELL)
        ).forEach { t.nodes[1].transport.send("g1", "h", encode(it)) }
        t.nodes[1].transport.send("g1", "h", "garbage".toByteArray())
        assertTrue(s === t.host.game.state.value)
        // a spent weapon cannot be fired again
        assertTrue(t.nodes[1].game.fire(100, 50, TankWeapons.BIG)); t.advanceTo(1)
        assertTrue(t.nodes[1].game.fire(100, 50, TankWeapons.BIG)); t.advanceTo(1)
        val s2 = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(TankNetMessage.FireRequest(100, 50, TankWeapons.BIG)))
        assertTrue(s2 === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndImpossibleSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value!!
        g.transport.inject("g1", encode(TankNetMessage.StateSync(50, real.copy(wind = 1), emptyMap())))
        assertTrue(real === g.game.state.value)
        val badHp = real.copy(tanks = real.tanks.mapIndexed { i, tk -> if (i == 0) tk.copy(hp = 9999) else tk })
        g.transport.inject("h", encode(TankNetMessage.StateSync(51, badHp, emptyMap())))
        assertTrue(real === g.game.state.value)
        g.transport.inject("h", encode(TankNetMessage.StateSync(52, real.copy(tanks = real.tanks.map { it.copy(playerId = "evil") }), emptyMap())))
        assertTrue(real === g.game.state.value)
        g.transport.inject("h", encode(TankNetMessage.StateSync(53, real.copy(terrain = real.terrain.drop(5)), emptyMap())))
        assertTrue(real === g.game.state.value)
        g.transport.inject("h", encode(TankNetMessage.StateSync(54, real.copy(phase = TankGame.PHASE_OVER), emptyMap())))
        assertTrue(real === g.game.state.value)
        t.advanceTo(1)
        val applied = g.game.state.value
        assertEquals(t.host.game.state.value, applied)
        g.transport.inject("h", encode(TankNetMessage.StateSync(1, real, emptyMap())))
        assertTrue(applied === g.game.state.value)
    }

    @Test fun aLateJoinerOrReconnectingGuestAsksForTheBattlefield() {
        val t = table(2)
        t.advanceTo(1)
        t.nodes[1].transport.reconnectedListeners.forEach { it() }
        assertEquals(t.host.game.state.value, t.nodes[1].game.state.value)
        assertNotNull(t.nodes[1].game.state.value)
    }

    @Test fun aLeavingGuestIsTakenOverByTheComputerAndCanReturn() {
        val t = table(3)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.tanks[1].isBot)
        assertTrue(t.host.game.botToMove() == (t.host.game.state.value!!.current == 1))
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.tanks[1].isBot)
    }

    @Test fun hostLeavingFlagsGuestsAndMatchesNeverSnapshot() {
        val t = table(2)
        assertEquals(null, t.host.game.snapshot())
        assertEquals(null, t.nodes[1].game.snapshot())
        assertFalse(t.nodes[1].game.canUndo)
        t.nodes[1].transport.fireLeft("h")
        assertTrue(t.nodes[1].game.hostLeft.value)
        assertFalse(t.nodes[1].game.isMyTurn())
        t.nodes[1].transport.fireJoined("h")
        assertFalse(t.nodes[1].game.hostLeft.value)
    }

    @Test fun playAgainIsRefusedBeforeTheMatchIsOver() {
        val t = table(2)
        val s = t.host.game.state.value
        t.nodes[1].game.playAgain()
        t.nodes[1].transport.send("g1", "h", encode(TankNetMessage.RequestPlayAgain))
        assertTrue(s === t.host.game.state.value)
    }
}
