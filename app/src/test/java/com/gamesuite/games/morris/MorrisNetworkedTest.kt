package com.gamesuite.games.morris

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorrisNetworkedTest {
    private val host = "h"
    private val guest = "g"
    private val roster = listOf(PlayerInfo(host, "Hana"), PlayerInfo(guest, "Gus"))

    private class Node(val game: NineMensMorrisGame, val t: MorrisFakeTransport) {
        fun s() = game.state.value!!
    }

    private class Pair2(val hub: MorrisFakeHub, val host: Node, val guest: Node)

    private fun enc(m: MorrisNetMessage) = Json.encodeToString(MorrisNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(s: MorrisFakeTransport.Sent) = Json.decodeFromString(MorrisNetMessage.serializer(), s.text)

    private fun node(index: Int, t: MorrisFakeTransport, mode: PlayMode, start: Boolean): Node {
        val g = NineMensMorrisGame()
        g.init(GameContext(mode, roster, index, t))
        if (start) g.startMatch()
        return Node(g, t)
    }

    private fun pair(mode: PlayMode = PlayMode.LOCAL_AD_HOC): Pair2 {
        val hub = MorrisFakeHub()
        val ht = hub.endpoint(host)
        val gt = hub.endpoint(guest)
        val h = node(0, ht, mode, true)
        val g = node(1, gt, mode, false)
        return Pair2(hub, h, g)
    }

    private fun Node.playFirst(to: Int) = game.play(s().legalMoves.first { it.to == to })

    @Test
    fun `a guest asks for the state and mirrors the host's board`() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            val p = pair(mode)
            assertTrue(dec(p.guest.t.sent.first()) is MorrisNetMessage.RequestState)
            assertEquals(p.host.s(), p.guest.s())
            assertTrue(p.hub.senderLies.isEmpty())
            assertTrue(mode in NineMensMorrisGame().supportedModes)
        }
    }

    @Test
    fun `a guest that starts alone stays empty`() {
        val g = node(1, MorrisFakeTransport(), PlayMode.ONLINE, start = true)
        assertNull(g.game.state.value)
    }

    @Test
    fun `host moves reach the guest with a newer version, guest moves go through the host`() {
        val p = pair()
        p.host.playFirst(0)
        assertEquals(p.host.s(), p.guest.s())
        assertEquals(1, p.guest.s().currentPlayerIndex)
        p.guest.playFirst(5)
        assertEquals(2, p.host.s().ply)
        assertEquals(p.host.s(), p.guest.s())
        // The guest never changed its own engine before the host's reply: it only sent a PlayMove.
        val sentMoves = p.guest.t.sent.map { dec(it) }.filterIsInstance<MorrisNetMessage.PlayMove>()
        assertEquals(listOf(MorrisNetMessage.PlayMove(-1, 5, -1)), sentMoves)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `a guest mill carries its removal choice`() {
        val p = pair()
        // host: 0, 1 ; guest: 16, 17 ... guest closes 16-17-18 and removes a host man.
        p.host.playFirst(0)
        p.guest.playFirst(16)
        p.host.playFirst(1)
        p.guest.playFirst(17)
        p.host.playFirst(5)
        p.guest.game.play(p.guest.s().legalMoves.first { it.to == 18 && it.remove == 5 })
        assertNull(p.host.s().points[5])
        assertEquals(p.host.s(), p.guest.s())
    }

    @Test
    fun `out of turn, illegal and forged requests change nothing on the host`() {
        val p = pair()
        val before = p.host.s()
        // Guest speaks while it is the host's turn.
        p.host.t.inject(guest, enc(MorrisNetMessage.PlayMove(-1, 3)))
        assertEquals(before, p.host.s())
        p.host.playFirst(0)
        val afterHost = p.host.s()
        // Wrong sender id.
        p.host.t.inject("stranger", enc(MorrisNetMessage.PlayMove(-1, 3)))
        p.host.t.inject(host, enc(MorrisNetMessage.PlayMove(-1, 3)))
        assertEquals(afterHost, p.host.s())
        // Illegal contents from the right sender: occupied point, off the board, bogus removal, slide while placing.
        for (bad in listOf(MorrisNetMessage.PlayMove(-1, 0), MorrisNetMessage.PlayMove(-1, 99), MorrisNetMessage.PlayMove(-1, 3, 0), MorrisNetMessage.PlayMove(0, 1))) {
            p.host.t.inject(guest, enc(bad))
            assertEquals(afterHost, p.host.s())
        }
        p.host.t.inject(guest, "not json".toByteArray())
        assertEquals(afterHost, p.host.s())
    }

    @Test
    fun `the guest only believes the host`() {
        val p = pair()
        p.host.playFirst(0)
        val good = p.guest.s()
        val forged = MorrisNetMessage.StateSync(
            99, MorrisNetState(
                points = List(24) { null }, players = listOf(MorrisNetPlayer(host, "Hana"), MorrisNetPlayer(guest, "Gus")),
                currentPlayerIndex = 1, hand = listOf(9, 9), quietPlies = 0, ply = 50, men = 9, flying = true, lastMove = null,
                lastMover = -1, lastAction = "x", boardOver = false, winnerPlayerId = null, endReason = null,
                sessionWins = emptyMap(), sessionDraws = 0
            )
        )
        p.guest.t.inject("stranger", enc(forged))
        p.guest.t.inject(guest, enc(forged))
        assertEquals(good, p.guest.s())
    }

    @Test
    fun `a guest drops stale versions and malformed states`() {
        val p = pair()
        p.host.playFirst(0)
        p.guest.playFirst(8)
        val current = p.guest.s()
        val staleSync = p.host.t.sent.map { dec(it) }.filterIsInstance<MorrisNetMessage.StateSync>().first()
        p.guest.t.inject(host, enc(staleSync))
        assertEquals(current, p.guest.s())

        fun bad(modify: (MorrisNetState) -> MorrisNetState) {
            val base = p.host.t.sent.map { dec(it) }.filterIsInstance<MorrisNetMessage.StateSync>().last()
            p.guest.t.inject(host, enc(MorrisNetMessage.StateSync(base.version + 100, modify(base.state))))
            assertEquals(current, p.guest.s())
        }
        bad { it.copy(men = 7) }
        bad { it.copy(points = it.points.drop(1)) }
        bad { it.copy(hand = listOf(0, 0)) }
        bad { it.copy(hand = listOf(30, 1)) }
        bad { it.copy(points = it.points.map { 3 }) }
        bad { it.copy(players = it.players.reversed()) }
        bad { it.copy(currentPlayerIndex = 4) }
        bad { it.copy(winnerPlayerId = "zz", boardOver = true) }
        bad { it.copy(lastMove = MorrisMove(0, 40)) }
        bad { it.copy(ply = -3) }
    }

    @Test
    fun `reconnect resends the state and the guest re-asks`() {
        val p = pair()
        p.host.playFirst(2)
        val mark = p.host.t.mark()
        p.host.t.fireReconnected()
        assertTrue(p.host.t.sentSince(mark).any { dec(it) is MorrisNetMessage.StateSync })
        val gMark = p.guest.t.mark()
        p.guest.t.fireReconnected()
        assertTrue(p.guest.t.sentSince(gMark).any { dec(it) is MorrisNetMessage.RequestState })
    }

    @Test
    fun `RequestState is answered only for the guest seat`() {
        val p = pair()
        val mark = p.host.t.mark()
        p.host.t.inject("stranger", enc(MorrisNetMessage.RequestState))
        assertTrue(p.host.t.sentSince(mark).isEmpty())
        p.host.t.inject(guest, enc(MorrisNetMessage.RequestState))
        assertEquals(1, p.host.t.sentSince(mark).size)
    }

    @Test
    fun `leaving and rejoining updates both seats and blocks moves while away`() {
        val p = pair()
        p.host.t.fireLeft(guest)
        assertTrue(p.host.game.opponentLeft.value)
        val ply = p.host.s().ply
        p.host.playFirst(3)
        assertEquals(ply, p.host.s().ply)
        p.host.t.fireJoined(guest)
        assertFalse(p.host.game.opponentLeft.value)
        p.host.playFirst(3)
        assertEquals(ply + 1, p.host.s().ply)
    }

    @Test
    fun `a rematch needs a finished board with both players present and swaps the opener`() {
        val p = pair()
        p.guest.game.playAgain() // not finished: nothing
        assertTrue(p.guest.t.sent.none { dec(it) is MorrisNetMessage.RequestPlayAgain })
        // Drive the host board to its end with random play from both seats.
        val rng = kotlin.random.Random(3)
        var guard = 0
        while (!p.host.s().boardOver && guard++ < 4000) {
            val actor = if (p.host.s().currentPlayerIndex == 0) p.host else p.guest
            val moves = actor.s().legalMoves
            actor.game.play(moves[rng.nextInt(moves.size)])
        }
        assertTrue(p.host.s().boardOver)
        assertEquals(p.host.s(), p.guest.s())
        p.guest.game.playAgain()
        assertEquals(0, p.host.s().ply)
        assertEquals(1, p.host.s().currentPlayerIndex)
        assertEquals(p.host.s(), p.guest.s())
    }

    @Test
    fun `no undo or hint over the network`() {
        val p = pair()
        p.host.playFirst(0)
        assertFalse(p.host.game.canUndo)
        p.host.game.undo()
        assertEquals(1, p.host.s().ply)
        assertNull(p.host.game.hint())
        assertNull(p.guest.game.hint())
    }
}
