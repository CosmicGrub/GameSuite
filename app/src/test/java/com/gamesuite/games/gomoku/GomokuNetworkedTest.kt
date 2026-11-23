package com.gamesuite.games.gomoku

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.gomoku.TestSeats.GUEST
import com.gamesuite.games.gomoku.TestSeats.HOST
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gomoku over LOCAL_AD_HOC / ONLINE: the host (seat 0) owns the engine, the guest only forwards taps. */
class GomokuNetworkedTest {
    private val n = GOMOKU_SIZE
    private fun at(r: Int, c: Int) = r * n + c
    private val modes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)

    private class Node(val game: GomokuGame, val transport: FakeTransport) {
        val results = mutableListOf<GameResult>()
        fun s() = game.state.value!!
    }

    private class Pair2(val hub: FakeHub, val host: Node, val guest: Node)

    private fun encode(m: GomokuNetMessage) = Json.encodeToString(GomokuNetMessage.serializer(), m)
    private fun decode(s: FakeTransport.Sent) = Json.decodeFromString(GomokuNetMessage.serializer(), s.text)
    private fun syncs(t: FakeTransport, from: Int = 0) = t.sentSince(from).map { decode(it) }.filterIsInstance<GomokuNetMessage.StateSync>()

    private fun node(localIndex: Int, mode: PlayMode, t: FakeTransport, start: Boolean): Node {
        val game = GomokuGame()
        val node = Node(game, t)
        game.init(TestSeats.context(mode, TestSeats.humans, localIndex, t))
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    private fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Pair2 {
        val hub = FakeHub()
        val ht = hub.endpoint(HOST)
        val gt = hub.endpoint(GUEST)
        val host = node(0, mode, ht, true)
        val guest = node(1, mode, gt, false)
        return Pair2(hub, host, guest)
    }

    @Test
    fun `both networked modes are advertised`() {
        assertTrue(PlayMode.LOCAL_AD_HOC in GomokuGame().supportedModes)
        assertTrue(PlayMode.ONLINE in GomokuGame().supportedModes)
    }

    @Test
    fun `a guest asks for the state, renders the host board and never deals its own`() {
        for (mode in modes) {
            val p = pair(mode)
            val first = p.guest.transport.sent.first()
            assertEquals(GUEST, first.from)
            assertEquals(HOST, first.to)
            assertTrue(decode(first) is GomokuNetMessage.RequestState)
            assertEquals(p.host.s(), p.guest.s())
            assertTrue(p.hub.senderLies.isEmpty())
        }
    }

    @Test
    fun `a guest that starts before any host state exists stays empty`() {
        val g = node(1, PlayMode.LOCAL_AD_HOC, FakeTransport(), start = true)
        assertNull(g.game.state.value)
    }

    @Test
    fun `host move reaches the guest and a valid guest move is applied by the host with a newer version`() {
        for (mode in modes) {
            val p = pair(mode)
            p.host.game.placeStone(at(7, 7))
            assertEquals(p.host.s(), p.guest.s())
            val v1 = syncs(p.host.transport).last().version
            assertEquals(GomokuPlaceResult.SENT_TO_HOST, p.guest.game.placeStone(at(7, 8)))
            assertEquals(1, p.host.s().cells[at(7, 8)])
            assertEquals(p.host.s(), p.guest.s())
            assertTrue(syncs(p.host.transport).last().version > v1)
        }
    }

    @Test
    fun `a guest tap out of turn or on an occupied point is refused locally and by the host`() {
        val p = pair()
        assertEquals(GomokuPlaceResult.NOT_YOUR_TURN, p.guest.game.placeStone(at(1, 1)))
        p.host.game.placeStone(at(7, 7))
        assertEquals(GomokuPlaceResult.OCCUPIED, p.guest.game.placeStone(at(7, 7)))
        // A forged request straight at the host is validated too.
        val before = p.host.s()
        p.host.transport.inject(GUEST, encode(GomokuNetMessage.PlaceStone(at(7, 7))))
        p.host.transport.inject(GUEST, encode(GomokuNetMessage.PlaceStone(-5)))
        p.host.transport.inject(GUEST, encode(GomokuNetMessage.PlaceStone(999)))
        assertEquals(before, p.host.s())
        // It is the guest's turn, so a host tap is refused.
        assertEquals(GomokuPlaceResult.NOT_YOUR_TURN, p.host.game.placeStone(at(2, 2)))
    }

    @Test
    fun `identity checks ignore forged senders`() {
        val p = pair()
        val before = p.host.s()
        // A stranger, or the host's own id, cannot move for the guest.
        p.host.transport.inject("stranger", encode(GomokuNetMessage.PlaceStone(at(3, 3))))
        p.host.transport.inject(HOST, encode(GomokuNetMessage.PlaceStone(at(3, 3))))
        assertEquals(before, p.host.s())
        // A stranger cannot make the host send state or re-deal.
        val mark = p.host.transport.mark()
        p.host.transport.inject("stranger", encode(GomokuNetMessage.RequestState))
        p.host.transport.inject("stranger", encode(GomokuNetMessage.RequestPlayAgain))
        assertTrue(p.host.transport.sentSince(mark).isEmpty())
        // The guest never adopts a StateSync from anyone but the host, and the host never adopts one at all.
        p.host.game.placeStone(at(7, 7))
        val guestBefore = p.guest.s()
        val forged = (syncs(p.host.transport).last().state).copy(cells = List(225) { 1 }, boardOver = false)
        p.guest.transport.inject("stranger", encode(GomokuNetMessage.StateSync(99, forged)))
        p.guest.transport.inject(GUEST, encode(GomokuNetMessage.StateSync(99, forged)))
        assertEquals(guestBefore, p.guest.s())
        p.host.transport.inject(GUEST, encode(GomokuNetMessage.StateSync(99, forged)))
        assertEquals(1, p.host.s().stones)
    }

    @Test
    fun `stale, replayed, malformed and inconsistent syncs are dropped`() {
        val p = pair()
        p.host.game.placeStone(at(7, 7))
        val sync = syncs(p.host.transport).last()
        val good = p.guest.s()
        p.guest.transport.inject(HOST, encode(sync)) // replay of the same version
        assertEquals(good, p.guest.s())
        p.guest.transport.inject(HOST, encode(GomokuNetMessage.StateSync(sync.version - 1, sync.state.copy(cells = List(225) { null }))))
        assertEquals(good, p.guest.s())
        val bad = listOf(
            sync.state.copy(cells = sync.state.cells.drop(1)),
            sync.state.copy(cells = sync.state.cells.mapIndexed { i, v -> if (i == 0) 7 else v }),
            sync.state.copy(currentPlayerIndex = 4),
            sync.state.copy(winnerPlayerId = "ghost"),
            sync.state.copy(boardOver = true),
            sync.state.copy(winLine = listOf(1, 2, 3, 4, 5)),
            sync.state.copy(lastMove = 0),
            sync.state.copy(players = sync.state.players.reversed())
        )
        var version = sync.version + 10
        for (b in bad) {
            p.guest.transport.inject(HOST, encode(GomokuNetMessage.StateSync(version++, b)))
            assertEquals(good, p.guest.s())
        }
        p.guest.transport.inject(HOST, "garbage")
        p.guest.transport.inject(HOST, "{\"type\":\"nope\"}")
        assertEquals(good, p.guest.s())
    }

    @Test
    fun `a networked win syncs the winner and line, and play again is host dealt`() {
        val p = pair()
        val mine = listOf(at(7, 7), at(7, 8), at(7, 9), at(7, 10), at(7, 11))
        val theirs = listOf(at(0, 0), at(0, 2), at(0, 4), at(0, 6))
        for (i in mine.indices) {
            p.host.game.placeStone(mine[i])
            if (i < theirs.size) p.guest.game.placeStone(theirs[i])
        }
        assertTrue(p.guest.s().boardOver)
        assertEquals(HOST, p.guest.s().winnerPlayerId)
        assertEquals(5, p.guest.s().winLine.size)
        assertEquals(1, p.guest.game.sessionWins.value[HOST])
        // Early play-again requests do nothing; after the end the guest asks and the host re-deals.
        val fresh = pair()
        fresh.guest.game.playAgain()
        assertEquals(0, fresh.host.s().stones)
        p.guest.game.playAgain()
        assertFalse(p.host.s().boardOver)
        assertEquals(0, p.host.s().stones)
        assertEquals(p.host.s(), p.guest.s())
        assertEquals(1, p.host.s().currentPlayerIndex) // the opener alternates
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `networked play has no undo, hint or resume`() {
        val p = pair()
        p.host.game.placeStone(at(7, 7))
        assertFalse(p.host.game.canUndo)
        p.host.game.undo()
        assertEquals(1, p.host.s().stones)
        assertNull(p.host.game.hint())
        assertNull(p.host.game.snapshot())
    }

    @Test
    fun `a left opponent blocks moves and a rejoin or reconnect restores them`() {
        val p = pair()
        p.host.transport.fireLeft(GUEST)
        assertTrue(p.host.game.opponentLeft.value)
        assertEquals(GomokuPlaceResult.OPPONENT_LEFT, p.host.game.placeStone(at(7, 7)))
        p.host.transport.fireJoined(GUEST)
        assertFalse(p.host.game.opponentLeft.value)
        assertEquals(GomokuPlaceResult.PLACED, p.host.game.placeStone(at(7, 7)))
        // A reconnect makes the host resend, and a guest ask again.
        val mark = p.host.transport.mark()
        p.host.transport.fireReconnected()
        assertEquals(1, syncs(p.host.transport, mark).size)
        val gmark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        assertTrue(decode(p.guest.transport.sentSince(gmark).single()) is GomokuNetMessage.RequestState)
        assertNotNull(p.guest.s())
    }

    @Test
    fun `the exact-five rule travels to the guest`() {
        val hub = FakeHub()
        val ht = hub.endpoint(HOST)
        val gt = hub.endpoint(GUEST)
        val host = GomokuGame().also { it.exactFive = true; it.init(TestSeats.context(PlayMode.ONLINE, TestSeats.humans, 0, ht)); it.startMatch() }
        val guest = GomokuGame().also { it.init(TestSeats.context(PlayMode.ONLINE, TestSeats.humans, 1, gt)) }
        assertTrue(host.state.value!!.exactFive)
        assertTrue(guest.state.value!!.exactFive)
    }
}
