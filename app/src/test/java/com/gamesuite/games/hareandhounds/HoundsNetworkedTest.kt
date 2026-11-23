package com.gamesuite.games.hareandhounds

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.morris.MorrisFakeHub
import com.gamesuite.games.morris.MorrisFakeTransport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoundsNetworkedTest {
    private val host = "h"
    private val guest = "g"
    private val roster = listOf(PlayerInfo(host, "Hana"), PlayerInfo(guest, "Gus"))

    private class Node(val game: HareAndHoundsGame, val t: MorrisFakeTransport) {
        fun s() = game.state.value!!
    }

    private class Pair2(val hub: MorrisFakeHub, val host: Node, val guest: Node)

    private fun enc(m: HoundsNetMessage) = Json.encodeToString(HoundsNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(s: MorrisFakeTransport.Sent) = Json.decodeFromString(HoundsNetMessage.serializer(), s.text)

    private fun node(index: Int, t: MorrisFakeTransport, mode: PlayMode, start: Boolean, side: String = HoundsSides.HOUNDS): Node {
        val g = HareAndHoundsGame()
        g.sidePreference = side
        g.init(GameContext(mode, roster, index, t))
        if (start) g.startMatch()
        return Node(g, t)
    }

    private fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC, side: String = HoundsSides.HOUNDS): Pair2 {
        val hub = MorrisFakeHub()
        val ht = hub.endpoint(host)
        val gt = hub.endpoint(guest)
        val h = node(0, ht, mode, true, side)
        val g = node(1, gt, mode, false)
        return Pair2(hub, h, g)
    }

    private fun Node.play(from: Int, to: Int) = game.play(HoundsMove(from, to))

    @Test
    fun `the guest mirrors the host's board and learns the roles from it`() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            val p = pair(mode, HoundsSides.HARE)
            assertTrue(dec(p.guest.t.sent.first()) is HoundsNetMessage.RequestState)
            assertEquals(p.host.s(), p.guest.s())
            assertEquals(1, p.guest.s().houndsSeat) // the host chose the hare, so the guest hunts
            assertTrue(p.hub.senderLies.isEmpty())
        }
        val h = pair()
        assertEquals(0, h.guest.s().houndsSeat)
    }

    @Test
    fun `moves flow both ways through the host`() {
        val p = pair()
        p.host.play(1, 4) // host hounds move first
        assertEquals(p.host.s(), p.guest.s())
        p.guest.play(10, 8)
        assertEquals(2, p.host.s().ply)
        assertEquals(p.host.s(), p.guest.s())
        assertEquals(listOf(HoundsNetMessage.PlayMove(10, 8)), p.guest.t.sent.map { dec(it) }.filterIsInstance<HoundsNetMessage.PlayMove>())
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `forged, out of turn and illegal requests are ignored by the host`() {
        val p = pair()
        val start = p.host.s()
        p.host.t.inject(guest, enc(HoundsNetMessage.PlayMove(10, 8))) // hounds' turn
        assertEquals(start, p.host.s())
        p.host.play(1, 4)
        val after = p.host.s()
        p.host.t.inject("stranger", enc(HoundsNetMessage.PlayMove(10, 8)))
        p.host.t.inject(host, enc(HoundsNetMessage.PlayMove(10, 8)))
        for (bad in listOf(HoundsNetMessage.PlayMove(10, 3), HoundsNetMessage.PlayMove(0, 2), HoundsNetMessage.PlayMove(-4, 99), HoundsNetMessage.PlayMove(10, 10))) {
            p.host.t.inject(guest, enc(bad))
            assertEquals(after, p.host.s())
        }
        p.host.t.inject(guest, "nope".toByteArray())
        assertEquals(after, p.host.s())
    }

    @Test
    fun `the guest ignores forged, stale and malformed states`() {
        val p = pair()
        p.host.play(1, 4)
        p.guest.play(10, 8)
        val current = p.guest.s()
        val syncs = p.host.t.sent.map { dec(it) }.filterIsInstance<HoundsNetMessage.StateSync>()
        p.guest.t.inject(host, enc(syncs.first())) // stale
        p.guest.t.inject("stranger", enc(HoundsNetMessage.StateSync(500, syncs.last().state)))
        p.guest.t.inject(guest, enc(HoundsNetMessage.StateSync(500, syncs.last().state)))
        assertEquals(current, p.guest.s())

        fun bad(modify: (HoundsNetState) -> HoundsNetState) {
            p.guest.t.inject(host, enc(HoundsNetMessage.StateSync(900, modify(syncs.last().state))))
            assertEquals(current, p.guest.s())
        }
        bad { it.copy(cells = it.cells.drop(1)) }
        bad { it.copy(cells = List(11) { 1 }) }
        bad { it.copy(cells = List(11) { 0 }) }
        bad { it.copy(cells = it.cells.map { c -> if (c == 2) 7 else c }) }
        bad { it.copy(houndMoves = 99) }
        bad { it.copy(houndsSeat = 3) }
        bad { it.copy(currentPlayerIndex = -1) }
        bad { it.copy(players = it.players.reversed()) }
        bad { it.copy(winnerPlayerId = "zz", boardOver = true) }
        bad { it.copy(lastMove = HoundsMove(40, 2)) }
        // A "live" state whose position is already decided is refused too.
        bad { it.copy(cells = List(11) { i -> when (i) { 7, 8, 9 -> 1; 10 -> 2; else -> 0 } }, currentPlayerIndex = 1 - it.houndsSeat) }
    }

    @Test
    fun `reconnect and state requests behave`() {
        val p = pair()
        val mark = p.host.t.mark()
        p.host.t.inject("stranger", enc(HoundsNetMessage.RequestState))
        assertTrue(p.host.t.sentSince(mark).isEmpty())
        p.host.t.inject(guest, enc(HoundsNetMessage.RequestState))
        assertEquals(1, p.host.t.sentSince(mark).size)
        val m2 = p.host.t.mark()
        p.host.t.fireReconnected()
        assertTrue(p.host.t.sentSince(m2).any { dec(it) is HoundsNetMessage.StateSync })
        val g2 = p.guest.t.mark()
        p.guest.t.fireReconnected()
        assertTrue(p.guest.t.sentSince(g2).any { dec(it) is HoundsNetMessage.RequestState })
    }

    @Test
    fun `an absent opponent blocks moves until they return`() {
        val p = pair()
        p.host.t.fireLeft(guest)
        assertTrue(p.host.game.opponentLeft.value)
        p.host.play(1, 4)
        assertEquals(0, p.host.s().ply)
        p.host.t.fireJoined(guest)
        p.host.play(1, 4)
        assertEquals(1, p.host.s().ply)
    }

    @Test
    fun `a rematch swaps the roles and needs a finished board`() {
        val p = pair()
        p.guest.game.playAgain()
        assertTrue(p.guest.t.sent.none { dec(it) is HoundsNetMessage.RequestPlayAgain })
        var guard = 0
        while (!p.host.s().boardOver && guard++ < 100) {
            val actor = if (p.host.s().currentPlayerIndex == 0) p.host else p.guest
            actor.game.play(HoundsEngine.bestMove(p.host.game.toPosition(p.host.s()))!!)
        }
        assertTrue(p.host.s().boardOver)
        assertEquals(p.host.s(), p.guest.s())
        p.guest.game.playAgain()
        assertEquals(0, p.host.s().ply)
        assertEquals(1, p.host.s().houndsSeat)
        assertEquals(p.host.s(), p.guest.s())
    }

    @Test
    fun `no undo or hint over the network`() {
        val p = pair()
        p.host.play(1, 4)
        assertFalse(p.host.game.canUndo)
        p.host.game.undo()
        assertEquals(1, p.host.s().ply)
        assertNull(p.host.game.hint())
        assertNull(p.guest.game.hint())
    }
}
