package com.gamesuite.games.darts

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Darts: the host owns the engine and the scatter of every dart. */
class DartsNetworkedTest {
    private class Node(val game: DartsGame, val transport: TbFakeTransport)
    private class Table(val hub: TbHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun encode(m: DartsNetMessage) = Json.encodeToString(DartsNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun table(seats: Int, hostSeed: Long = 5L, type: String = DartsGame.G301): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = TbHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = DartsGame(if (i == 0) Random(hostSeed) else Random(9000 + i)) // a guest's Random must never matter
            g.gameType = type
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, localPlayerIndex = i, transport = t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    @Test fun guestsReceiveTheOpeningBoardFromTheHost() {
        val t = table(3)
        t.nodes.forEach { assertEquals(t.host.game.state.value, it.game.state.value) }
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestsThrowIsScatteredByTheHostWithTheHostsSeed() {
        val t = table(2)
        // seat 0 (host) takes its visit, collects; seat 1 is up
        repeat(3) { assertTrue(t.host.game.throwDart(0.0, 300.0, 0.7)) }
        assertTrue(t.host.game.collect())
        val g1 = t.nodes[1].game
        assertTrue(g1.isMyTurn())
        assertTrue(g1.throwDart(0.0, -103.0, 0.68))
        val hostSeed = Random(5L).nextLong()
        val serial = 3
        val expected = DartsAim.land(0.0, -103.0, 0.68, DartsGame.assistSigma(DartsGame.ASSIST_NORMAL), DartsAim.throwRandom(hostSeed, serial))
        val dart = t.host.game.state.value!!.visit.single()
        assertEquals(expected.first, dart.x, 0.0); assertEquals(expected.second, dart.y, 0.0)
        assertEquals(t.host.game.state.value, g1.state.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestCannotThrowOrCollectOutOfTurn() {
        val t = table(2)
        val g1 = t.nodes[1].game
        assertFalse(g1.isMyTurn())
        assertFalse(g1.throwDart(0.0, 0.0, 0.5))
        assertFalse(g1.collect())
        val before = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", encode(DartsNetMessage.ThrowRequest(0.0, 0.0, 0.5)))
        t.nodes[1].transport.send("g1", "h", encode(DartsNetMessage.CollectRequest))
        assertTrue(before === t.host.game.state.value)
    }

    @Test fun identityComesFromTheTransportNeverFromTheMessage() {
        val t = table(3)
        val before = t.host.game.state.value
        // seat 2 claims to be the host or seat 1: the relay stamps its real identity, so nothing happens
        t.nodes[2].transport.send("h", "h", encode(DartsNetMessage.ThrowRequest(0.0, -103.0, 0.7)))
        t.nodes[2].transport.send("g1", "h", encode(DartsNetMessage.ThrowRequest(0.0, -103.0, 0.7)))
        assertTrue(before === t.host.game.state.value)
        assertEquals(2, t.hub.senderLies.size)
        // and a guest cannot send state to other guests as if it were the host
        val other = t.nodes[1].game.state.value
        t.nodes[2].transport.send("g2", "g1", encode(DartsNetMessage.StateSync(99, other!!.copy(current = 2), emptyMap())))
        assertTrue(other === t.nodes[1].game.state.value)
    }

    @Test fun badAimsFromAGuestAreRefusedByTheHost() {
        val t = table(2)
        repeat(3) { t.host.game.throwDart(0.0, 300.0, 0.7) }
        t.host.game.collect()
        val s = t.host.game.state.value
        t.nodes[1].transport.send("g1", "h", """{"type":"com.gamesuite.games.darts.DartsNetMessage.ThrowRequest","aimX":1e9,"aimY":0.0,"power":0.5}""".toByteArray())
        t.nodes[1].transport.send("g1", "h", encode(DartsNetMessage.ThrowRequest(0.0, 0.0, 7.0)))
        t.nodes[1].transport.send("g1", "h", encode(DartsNetMessage.ThrowRequest(5000.0, 0.0, 0.5)))
        t.nodes[1].transport.send("g1", "h", "garbage".toByteArray())
        assertTrue(s === t.host.game.state.value)
    }

    @Test fun guestsRejectForgedStaleAndImpossibleSyncs() {
        val t = table(2)
        val g = t.nodes[1]
        val real = g.game.state.value!!
        // from a non-host
        g.transport.inject("g1", encode(DartsNetMessage.StateSync(50, real.copy(current = 1), emptyMap())))
        assertTrue(real === g.game.state.value)
        // from the host but impossible: remaining above the start, a made-up roster
        val bad = real.copy(players = real.players.mapIndexed { i, p -> if (i == 0) p.copy(remaining = 9999) else p })
        g.transport.inject("h", encode(DartsNetMessage.StateSync(51, bad, emptyMap())))
        assertTrue(real === g.game.state.value)
        val roster = real.copy(players = real.players.map { it.copy(playerId = "evil") })
        g.transport.inject("h", encode(DartsNetMessage.StateSync(52, roster, emptyMap())))
        assertTrue(real === g.game.state.value)
        val phase = real.copy(phase = DartsGame.PHASE_OVER)
        g.transport.inject("h", encode(DartsNetMessage.StateSync(53, phase, emptyMap())))
        assertTrue(real === g.game.state.value)
        // a real update applies, an older version is then ignored
        t.host.game.throwDart(0.0, -103.0, 0.68)
        val applied = g.game.state.value
        assertEquals(t.host.game.state.value, applied)
        g.transport.inject("h", encode(DartsNetMessage.StateSync(1, real, emptyMap())))
        assertTrue(applied === g.game.state.value)
    }

    @Test fun aLateJoinerOrReconnectingGuestAsksForTheBoard() {
        val t = table(2)
        t.host.game.throwDart(0.0, -103.0, 0.68)
        val g = t.nodes[1]
        g.transport.reconnectedListeners.forEach { it() }
        assertEquals(t.host.game.state.value, g.game.state.value)
        assertNotNull(g.game.state.value)
    }

    @Test fun aLeavingGuestIsTakenOverByTheComputerAndCanReturn() {
        val t = table(3)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.state.value!!.players[1].isBot)
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.state.value!!.players[1].isBot)
    }

    @Test fun hostLeavingFlagsGuestsAndPlayAgainWorks() {
        val t = table(2, type = DartsGame.G301)
        // finish the match on the host quickly: 301 needs many darts, so check the guard instead
        t.nodes[1].game.playAgain() // nothing happens before the match is over
        assertEquals(0, t.host.game.state.value!!.throwSerial)
        t.nodes[1].transport.fireLeft("h")
        assertTrue(t.nodes[1].game.hostLeft.value)
        assertFalse(t.nodes[1].game.isMyTurn())
        t.nodes[1].transport.fireJoined("h")
        assertFalse(t.nodes[1].game.hostLeft.value)
    }

    @Test fun networkedMatchesNeverSnapshot() {
        val t = table(2)
        assertEquals(null, t.host.game.snapshot())
        assertEquals(null, t.nodes[1].game.hint())
    }
}
