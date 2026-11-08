package com.gamesuite.games.shogi

import com.gamesuite.games.shogi.ShogiTestKit.GUEST
import com.gamesuite.games.shogi.ShogiTestKit.HOST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review finding 3 (and the sessionWins half of 8): a bad last-move or tally from a host or snapshot is refused whole. */
class ShogiLastMoveValidationTest {
    private fun sq(r: Int, c: Int) = r * 9 + c

    private fun playedTable(): ShogiTestKit.Table {
        val t = ShogiTestKit.table()
        val g = t.host.game
        val s = g.state.value!!
        g.playMove(s.legalMoves.first { ShogiMove.from(it) == sq(6, 4) && ShogiMove.to(it) == sq(5, 4) })
        return t
    }

    private fun netOf(t: ShogiTestKit.Table): ShogiNetState {
        val s = t.host.game.state.value!!
        return ShogiNetState(
            n = 9, cells = s.cells, hands = s.hands, sideToMove = s.sideToMove, senteSeat = s.senteSeat,
            players = listOf(ShogiNetPlayer(HOST, "Hana"), ShogiNetPlayer(GUEST, "Gus")),
            lastFrom = s.lastFrom, lastTo = s.lastTo, lastCaptured = s.lastCaptured, ply = s.ply + 1,
            gameOver = false, winnerPlayerId = null, endReason = "NONE",
            sessionWins = t.host.game.sessionWins.value, sessionDraws = 0
        )
    }

    private fun assertRejected(t: ShogiTestKit.Table, version: Int, bad: ShogiNetState) {
        val before = t.guest.game.state.value
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(version, bad))
        assertEquals(before, t.guest.game.state.value)
    }

    @Test fun guestAcceptsTheHostsGenuineLastMove() {
        val t = playedTable()
        val good = netOf(t).copy(ply = 1)
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(500, good))
        assertEquals(good.lastTo, t.guest.game.state.value!!.lastTo)
    }

    @Test fun guestRejectsAnOutOfRangeLastTo() {
        val t = playedTable()
        assertRejected(t, 500, netOf(t).copy(lastTo = 500))
    }

    @Test fun guestRejectsOutOfRangeLastFromAndNegativeBelowMinusOne() {
        val t = playedTable()
        assertRejected(t, 500, netOf(t).copy(lastFrom = 81))
        assertRejected(t, 501, netOf(t).copy(lastTo = -2))
    }

    @Test fun guestRejectsALastToPointingAtAnEmptySquare() {
        val t = playedTable()
        val n = netOf(t)
        val empty = n.cells.indexOfFirst { it == 0 }
        assertRejected(t, 500, n.copy(lastTo = empty))
    }

    @Test fun guestRejectsANonsenseCapturedCode() {
        val t = playedTable()
        assertRejected(t, 500, netOf(t).copy(lastCaptured = 999))
        assertRejected(t, 501, netOf(t).copy(lastCaptured = -5))
    }

    @Test fun guestRejectsNegativeOrForeignSessionTallies() {
        val t = playedTable()
        assertRejected(t, 500, netOf(t).copy(sessionWins = mapOf(HOST to -3)))
        assertRejected(t, 501, netOf(t).copy(sessionWins = mapOf("stranger" to 1)))
        assertRejected(t, 502, netOf(t).copy(sessionDraws = -1))
    }

    @Test fun snapshotWithAnOutOfRangeLastToIsRefusedNotCrashedOn() {
        val g = ShogiTestKit.local()
        val s = g.state.value!!
        g.playMove(s.legalMoves.first())
        val snap = g.snapshot()!!
        assertTrue(ShogiTestKit.local(start = false).restore(snap))
        val bad = snap.replace(Regex("\"lastTo\":-?\\d+"), "\"lastTo\":500")
        assertFalse(ShogiTestKit.local(start = false).restore(bad))
    }
}
