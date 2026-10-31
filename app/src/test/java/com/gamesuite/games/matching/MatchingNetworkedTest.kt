package com.gamesuite.games.matching

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.speed.FakeClock
import com.gamesuite.games.speed.SmFakeTransport
import com.gamesuite.games.speed.SmHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Matching: the host holds every picture; guests learn a face only when it is turned up. */
class MatchingNetworkedTest {
    private class Node(val game: MatchingGame, val transport: SmFakeTransport)
    private class Table(val hub: SmHub, val nodes: List<Node>) { val host get() = nodes[0] }

    private fun enc(m: MatchingNetMessage) = Json.encodeToString(MatchingNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = Json.decodeFromString(MatchingNetMessage.serializer(), String(b, Charsets.UTF_8))

    private fun table(seats: Int, count: Int = 12, seed: Int = 4, mode: PlayMode = PlayMode.LOCAL_AD_HOC): Table {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = SmHub()
        val nodes = ArrayList<Node>()
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = MatchingGame(Random(if (i == 0) seed else 1000 + i), FakeClock())
            g.cardCount = count
            g.init(GameContext(mode, players, i, t))
            nodes += Node(g, t)
            if (i == 0) g.startMatch()
        }
        return Table(hub, nodes)
    }

    private fun pairs(t: Table) = MatchingTestKit.pairs(t.host.game)

    @Test fun guestsGetTheBoardButNoPictureOfAFaceDownCard() {
        val t = table(3)
        t.nodes.forEach { n ->
            val s = n.game.state.value!!
            assertEquals(12, s.cardCount)
            assertEquals(t.host.game.state.value!!.status, s.status)
        }
        for (i in 1..2) assertTrue(t.nodes[i].game.state.value!!.faces.all { it == -1 })
        // and not on the wire either
        val syncs = t.host.transport.sent.map { dec(it.payload) }.filterIsInstance<MatchingNetMessage.StateSync>()
        assertTrue(syncs.isNotEmpty())
        assertTrue(syncs.all { m -> m.state.faces.all { it == MatchingNetState.HIDDEN } })
        assertTrue(t.hub.senderLies.isEmpty())
    }

    @Test fun aGuestSeesAFaceOnlyWhileItIsUpAndHidesItAgainAfterAMiss() {
        val t = table(2)
        val ps = pairs(t)
        val a = ps[0].first; val b = ps[1].first
        t.host.game.flipCard(a)
        val g = t.nodes[1].game
        assertEquals(t.host.game.truthFaces()[a], g.state.value!!.faces[a])
        assertEquals(-1, g.state.value!!.faces[b])
        t.host.game.flipCard(b)
        assertEquals(t.host.game.truthFaces()[b], g.state.value!!.faces[b])
        assertTrue(g.state.value!!.resolving)
        assertFalse(g.resolve()) // a guest never resolves
        t.host.game.resolve()
        assertEquals(-1, g.state.value!!.faces[a])
        assertEquals(-1, g.state.value!!.faces[b])
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test fun theSeatOnTurnCanFlipOverTheNetworkAndOthersCannot() {
        val t = table(3)
        val ps = pairs(t)
        // seat 1 is not on turn
        assertEquals(MatchingReject.NOT_YOUR_TURN, t.nodes[1].game.flipCard(0))
        assertEquals(MatchingReject.NOT_YOUR_TURN, t.nodes[2].game.flipCard(0))
        // spoofed requests: from the wrong seat, an unknown sender, or "the host" from a guest endpoint
        t.host.transport.inject("g1", enc(MatchingNetMessage.FlipRequest(0)))
        t.host.transport.inject("x", enc(MatchingNetMessage.FlipRequest(0)))
        assertEquals(MatchingStatus.DOWN, t.host.game.state.value!!.status[0])
        // the host misses, the turn passes to seat 1 who may flip by request
        t.host.game.flipCard(ps[0].first); t.host.game.flipCard(ps[1].first); t.host.game.resolve()
        assertEquals(1, t.host.game.state.value!!.currentPlayerIndex)
        assertEquals(MatchingReject.NONE, t.nodes[1].game.flipCard(ps[2].first))
        assertEquals(MatchingStatus.UP, t.host.game.state.value!!.status[ps[2].first])
        assertEquals(MatchingStatus.UP, t.nodes[2].game.state.value!!.status[ps[2].first])
        // seat 2 asking out of turn is ignored even though the transport is genuine
        t.host.transport.inject("g2", enc(MatchingNetMessage.FlipRequest(ps[3].first)))
        assertEquals(MatchingStatus.DOWN, t.host.game.state.value!!.status[ps[3].first])
        // and the host cannot flip for the seat on turn
        assertEquals(MatchingReject.NOT_YOUR_TURN, t.host.game.flipCard(ps[3].first))
    }

    @Test fun aMatchOverTheNetworkKeepsTheTurnAndEveryoneSeesTheScore() {
        val t = table(2)
        val (a, b) = pairs(t).first()
        t.host.game.flipCard(a); t.host.game.flipCard(b); t.host.game.resolve()
        for (n in t.nodes) {
            val s = n.game.state.value!!
            assertEquals(listOf(1, 0), s.scores)
            assertEquals(0, s.currentPlayerIndex)
            assertEquals(MatchingStatus.MATCHED, s.status[a])
            assertEquals(t.host.game.truthFaces()[a], s.faces[a]) // a matched card stays visible to all
        }
    }

    @Test fun aFinishedBoardCanBeReplayedByAGuestRequestOnly() {
        val t = table(2)
        val g = t.nodes[1].game
        g.playAgain() // not over yet: nothing happens
        assertEquals(1, t.host.game.state.value!!.dealSerial)
        val ps = pairs(t)
        t.host.game.flipCard(ps[0].first); t.host.game.flipCard(ps[1].first); t.host.game.resolve() // the host misses: seat 1 is up
        for ((a, b) in ps) {
            assertEquals(MatchingReject.NONE, g.flipCard(a))
            assertEquals(MatchingReject.NONE, g.flipCard(b))
            t.host.game.resolve()
        }
        val over = t.host.game.state.value!!
        assertTrue(over.boardOver)
        assertEquals("g1", over.winnerPlayerId)
        assertTrue(g.state.value!!.boardOver)
        g.playAgain()
        assertFalse(t.host.game.state.value!!.boardOver)
        assertEquals(2, t.host.game.state.value!!.dealSerial)
        assertEquals(2, g.state.value!!.dealSerial)
        assertEquals(listOf(0, 0), g.state.value!!.scores)
    }

    @Test fun forgedAndStaleStatesAreIgnoredAndALeakingStateIsRefused() {
        val t = table(2)
        val g = t.nodes[1]
        val good = t.host.transport.sent.map { dec(it.payload) }.filterIsInstance<MatchingNetMessage.StateSync>().last()
        val before = g.game.state.value
        // from a stranger or from the guest itself
        g.transport.inject("x", enc(good.copy(version = good.version + 5, state = good.state.copy(currentPlayerIndex = 1))))
        g.transport.inject("g1", enc(good.copy(version = good.version + 5, state = good.state.copy(currentPlayerIndex = 1))))
        assertEquals(before, g.game.state.value)
        // stale
        g.transport.inject("h", enc(good.copy(state = good.state.copy(currentPlayerIndex = 1))))
        assertEquals(before, g.game.state.value)
        // a face-down card whose picture is revealed is not an honest host state: refused
        val leaky = good.state.copy(faces = good.state.faces.mapIndexed { i, _ -> i % 5 })
        g.transport.inject("h", enc(good.copy(version = good.version + 6, state = leaky)))
        assertTrue(g.game.state.value!!.faces.all { it == -1 })
        // malformed
        g.transport.inject("h", "{".toByteArray())
        g.transport.inject("h", enc(good.copy(version = good.version + 7, state = good.state.copy(cardCount = 13))))
        assertEquals(before, g.game.state.value)
    }

    @Test fun departuresAndReconnectsAreHandled() {
        val t = table(3)
        t.nodes[1].transport.fireLeft("g2")            // a guest only cares about the host: another guest leaving changes nothing for it
        assertFalse(t.nodes[1].game.opponentLeft.value)
        t.nodes[1].transport.fireLeft("h")
        assertTrue(t.nodes[1].game.opponentLeft.value)
        t.nodes[1].transport.fireJoined("h")
        assertFalse(t.nodes[1].game.opponentLeft.value)
        t.host.transport.fireLeft("g1")
        assertTrue(t.host.game.opponentLeft.value)
        assertEquals(MatchingReject.NOT_YOUR_TURN, t.host.game.flipCard(0))
        val n = t.host.transport.sent.size
        t.host.transport.fireJoined("g1")
        assertFalse(t.host.game.opponentLeft.value)
        assertTrue(t.host.transport.sent.size > n)
        t.nodes[2].transport.fireReconnected()
        assertNotNull(t.nodes[2].game.state.value)
    }

    @Test fun networkedBoardsAreNeverSavedOrHinted() {
        val t = table(2)
        assertNull(t.host.game.snapshot()); assertNull(t.nodes[1].game.snapshot())
        assertNull(t.host.game.requestHint())
        assertFalse(t.host.game.canUndo)
    }
}
