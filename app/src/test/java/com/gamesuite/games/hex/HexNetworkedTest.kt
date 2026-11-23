package com.gamesuite.games.hex

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.gomoku.FakeHub
import com.gamesuite.games.gomoku.FakeTransport
import com.gamesuite.games.gomoku.TestSeats
import com.gamesuite.games.gomoku.TestSeats.GUEST
import com.gamesuite.games.gomoku.TestSeats.HOST
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hex over LOCAL_AD_HOC / ONLINE: the host (seat 0) owns the engine, the guest only forwards taps and swaps. */
class HexNetworkedTest {
    private val n = 9
    private fun at(r: Int, c: Int) = r * n + c
    private val modes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)

    private class Node(val game: HexGame, val transport: FakeTransport) {
        val results = mutableListOf<GameResult>()
        fun s() = game.state.value!!
    }

    private class Duo(val hub: FakeHub, val host: Node, val guest: Node)

    private fun encode(m: HexNetMessage) = Json.encodeToString(HexNetMessage.serializer(), m)
    private fun decode(s: FakeTransport.Sent) = Json.decodeFromString(HexNetMessage.serializer(), s.text)
    private fun syncs(t: FakeTransport, from: Int = 0) = t.sentSince(from).map { decode(it) }.filterIsInstance<HexNetMessage.StateSync>()

    private fun node(localIndex: Int, mode: PlayMode, t: FakeTransport, start: Boolean, pie: Boolean): Node {
        val game = HexGame()
        game.boardSize = n
        game.pieRule = pie
        val node = Node(game, t)
        game.init(TestSeats.context(mode, TestSeats.humans, localIndex, t))
        game.setOnMatchEnd { node.results += it }
        if (start) game.startMatch()
        return node
    }

    private fun duo(mode: PlayMode = PlayMode.LOCAL_AD_HOC, pie: Boolean = false): Duo {
        val hub = FakeHub()
        val ht = hub.endpoint(HOST)
        val gt = hub.endpoint(GUEST)
        val host = node(0, mode, ht, true, pie)
        val guest = node(1, mode, gt, false, false)
        return Duo(hub, host, guest)
    }

    @Test
    fun `a guest asks for state, renders the host board and carries the host settings`() {
        for (mode in modes) {
            val p = duo(mode, pie = true)
            val first = p.guest.transport.sent.first()
            assertEquals(GUEST, first.from)
            assertEquals(HOST, first.to)
            assertTrue(decode(first) is HexNetMessage.RequestState)
            assertEquals(p.host.s(), p.guest.s())
            assertTrue(p.guest.s().pieRule)
            assertEquals(n, p.guest.s().size)
            assertTrue(p.hub.senderLies.isEmpty())
        }
    }

    @Test
    fun `a guest that starts before any host state exists stays empty`() {
        val g = node(1, PlayMode.ONLINE, FakeTransport(), start = true, pie = false)
        assertNull(g.game.state.value)
    }

    @Test
    fun `moves flow both ways and every state carries a newer version`() {
        for (mode in modes) {
            val p = duo(mode)
            p.host.game.placeStone(at(4, 4))
            val v1 = syncs(p.host.transport).last().version
            assertEquals(HexPlaceResult.SENT_TO_HOST, p.guest.game.placeStone(at(4, 5)))
            assertEquals(1, p.host.s().cells[at(4, 5)])
            assertEquals(p.host.s(), p.guest.s())
            assertTrue(syncs(p.host.transport).last().version > v1)
        }
    }

    @Test
    fun `out of turn, occupied and forged taps are refused`() {
        val p = duo()
        assertEquals(HexPlaceResult.NOT_YOUR_TURN, p.guest.game.placeStone(at(1, 1)))
        p.host.game.placeStone(at(4, 4))
        assertEquals(HexPlaceResult.OCCUPIED, p.guest.game.placeStone(at(4, 4)))
        val before = p.host.s()
        p.host.transport.inject(GUEST, encode(HexNetMessage.PlaceStone(at(4, 4))))
        p.host.transport.inject(GUEST, encode(HexNetMessage.PlaceStone(-3)))
        p.host.transport.inject(GUEST, encode(HexNetMessage.PlaceStone(500)))
        p.host.transport.inject("stranger", encode(HexNetMessage.PlaceStone(at(1, 1))))
        p.host.transport.inject(HOST, encode(HexNetMessage.PlaceStone(at(1, 1))))
        assertEquals(before, p.host.s())
        assertEquals(HexPlaceResult.NOT_YOUR_TURN, p.host.game.placeStone(at(2, 2)))
    }

    @Test
    fun `forged state syncs and requests from other senders are ignored`() {
        val p = duo()
        p.host.game.placeStone(at(4, 4))
        val guestBefore = p.guest.s()
        val real = syncs(p.host.transport).last().state
        val forged = real.copy(cells = List(81) { 1 })
        p.guest.transport.inject("stranger", encode(HexNetMessage.StateSync(99, forged)))
        p.guest.transport.inject(GUEST, encode(HexNetMessage.StateSync(99, forged)))
        assertEquals(guestBefore, p.guest.s())
        p.host.transport.inject(GUEST, encode(HexNetMessage.StateSync(99, forged)))
        assertEquals(1, p.host.s().stones)
        val mark = p.host.transport.mark()
        p.host.transport.inject("stranger", encode(HexNetMessage.RequestState))
        p.host.transport.inject("stranger", encode(HexNetMessage.RequestPlayAgain))
        p.host.transport.inject("stranger", encode(HexNetMessage.RequestSwap))
        assertTrue(p.host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `stale, replayed, malformed and inconsistent syncs are dropped`() {
        val p = duo(pie = true)
        p.host.game.placeStone(at(4, 4))
        val sync = syncs(p.host.transport).last()
        val good = p.guest.s()
        p.guest.transport.inject(HOST, encode(sync))
        assertEquals(good, p.guest.s())
        val bad = listOf(
            sync.state.copy(cells = sync.state.cells.drop(1)),
            sync.state.copy(size = 10),
            sync.state.copy(cells = sync.state.cells.mapIndexed { i, v -> if (i == 0) 5 else v }),
            sync.state.copy(currentPlayerIndex = 3),
            sync.state.copy(winnerPlayerId = "ghost"),
            sync.state.copy(boardOver = true),
            sync.state.copy(pieOpen = false, swapped = true, pieRule = false),
            sync.state.copy(currentPlayerIndex = 0), // the pie offer belongs to the other seat
            sync.state.copy(players = sync.state.players.reversed()),
            sync.state.copy(lastMove = 0)
        )
        var version = sync.version + 10
        for (b in bad) {
            p.guest.transport.inject(HOST, encode(HexNetMessage.StateSync(version++, b)))
            assertEquals(good, p.guest.s())
        }
        p.guest.transport.inject(HOST, "garbage")
        assertEquals(good, p.guest.s())
    }

    @Test
    fun `the guest can swap through the host under the pie rule and only then`() {
        val p = duo(pie = true)
        assertFalse(p.guest.game.canSwap) // nothing to swap yet
        p.host.game.placeStone(at(2, 5))
        assertTrue(p.guest.game.canSwap)
        assertFalse(p.host.game.canSwap)
        assertTrue(p.guest.game.swap())
        assertEquals(p.host.s(), p.guest.s())
        assertTrue(p.host.s().swapped)
        assertEquals(1, p.host.s().cells[at(5, 2)])
        assertEquals(0, p.host.s().currentPlayerIndex)
        assertFalse(p.guest.game.canSwap)
        // A late or repeated request does nothing.
        val before = p.host.s()
        p.host.transport.inject(GUEST, encode(HexNetMessage.RequestSwap))
        assertEquals(before, p.host.s())
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `a swap request without the pie rule is ignored`() {
        val p = duo(pie = false)
        p.host.game.placeStone(at(2, 5))
        val before = p.host.s()
        p.host.transport.inject(GUEST, encode(HexNetMessage.RequestSwap))
        assertEquals(before, p.host.s())
    }

    @Test
    fun `a networked win syncs the winner and chain and the host deals play again`() {
        val p = duo()
        for (r in 0 until n) {
            p.host.game.placeStone(at(r, 3))
            if (r < n - 1) p.guest.game.placeStone(at(r, 7))
        }
        assertTrue(p.guest.s().boardOver)
        assertEquals(HOST, p.guest.s().winnerPlayerId)
        assertEquals(n, p.guest.s().winPath.size)
        assertEquals(1, p.guest.game.sessionWins.value[HOST])
        p.guest.game.playAgain()
        assertFalse(p.host.s().boardOver)
        assertEquals(0, p.host.s().stones)
        assertEquals(1, p.host.s().currentPlayerIndex)
        assertEquals(p.host.s(), p.guest.s())
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `networked play has no undo, hint or resume`() {
        val p = duo()
        p.host.game.placeStone(at(4, 4))
        assertFalse(p.host.game.canUndo)
        p.host.game.undo()
        assertEquals(1, p.host.s().stones)
        assertNull(p.host.game.hint())
        assertNull(p.host.game.snapshot())
    }

    @Test
    fun `a left opponent blocks moves and a rejoin or reconnect restores them`() {
        val p = duo()
        p.host.transport.fireLeft(GUEST)
        assertEquals(HexPlaceResult.OPPONENT_LEFT, p.host.game.placeStone(at(4, 4)))
        p.host.transport.fireJoined(GUEST)
        assertEquals(HexPlaceResult.PLACED, p.host.game.placeStone(at(4, 4)))
        val mark = p.host.transport.mark()
        p.host.transport.fireReconnected()
        assertEquals(1, syncs(p.host.transport, mark).size)
        val gmark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        assertTrue(decode(p.guest.transport.sentSince(gmark).single()) is HexNetMessage.RequestState)
    }
}
