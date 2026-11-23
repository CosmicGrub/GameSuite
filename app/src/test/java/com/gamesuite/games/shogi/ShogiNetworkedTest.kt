package com.gamesuite.games.shogi

import com.gamesuite.games.shogi.ShogiTestKit.GUEST
import com.gamesuite.games.shogi.ShogiTestKit.HOST
import com.gamesuite.games.shogi.ShogiTestKit.sparse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShogiNetworkedTest {
    private fun sq(r: Int, c: Int) = r * 9 + c

    private fun move(g: ShogiGame, from: Int, to: Int, promote: Boolean = false) =
        g.state.value!!.legalMoves.first { ShogiMove.from(it) == from && ShogiMove.to(it) == to && ShogiMove.promote(it) == promote }

    @Test fun guestReceivesTheHostsBoardOnJoin() {
        val t = ShogiTestKit.table()
        val h = t.host.game.state.value!!
        val g = t.guest.game.state.value!!
        assertNotNull(g)
        assertEquals(h.cells, g.cells)
        assertEquals(h.legalMoves, g.legalMoves) // derived locally, never sent
        assertEquals(0, g.currentPlayerIndex)
        assertTrue(t.senderLies.isEmpty())
    }

    @Test fun hostMoveReachesTheGuestAndGuestReplyComesBack() {
        val t = ShogiTestKit.table()
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertEquals(t.host.game.state.value!!.cells, t.guest.game.state.value!!.cells)
        assertEquals(1, t.guest.game.state.value!!.currentPlayerIndex)
        t.guest.game.playMove(move(t.guest.game, sq(2, 4), sq(3, 4)))
        assertEquals(2, t.host.game.state.value!!.ply)
        assertEquals(t.host.game.state.value!!.cells, t.guest.game.state.value!!.cells)
        assertTrue(t.senderLies.isEmpty())
    }

    @Test fun guestCannotMoveOutOfTurnOrAnIllegalMove() {
        val t = ShogiTestKit.table()
        val sent = t.guest.transport.sent.size
        t.guest.game.playMove(t.guest.game.state.value!!.legalMoves.first()) // host's turn
        assertEquals(sent, t.guest.transport.sent.size)
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        // a forged PlayMove straight at the host, illegal (a rook jump)
        t.host.transport.inject(GUEST, ShogiNetMessage.PlayMove(ShogiMove.make(sq(2, 1), sq(5, 1))))
        assertEquals(1, t.host.game.state.value!!.ply)
    }

    @Test fun hostIgnoresMovesFromTheWrongSenderAndOutOfTurn() {
        val t = ShogiTestKit.table()
        val legalForGote = ShogiMove.make(sq(2, 4), sq(3, 4))
        t.host.transport.inject("mallory", ShogiNetMessage.PlayMove(legalForGote))
        t.host.transport.inject(GUEST, ShogiNetMessage.PlayMove(legalForGote)) // not Gote's turn yet
        assertEquals(0, t.host.game.state.value!!.ply)
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        // host (Sente) cannot be impersonated by a PlayMove either
        t.host.transport.inject(HOST, ShogiNetMessage.PlayMove(legalForGote))
        assertEquals(1, t.host.game.state.value!!.ply)
        t.host.transport.inject("mallory", ShogiNetMessage.PlayMove(legalForGote))
        assertEquals(1, t.host.game.state.value!!.ply)
        t.host.transport.inject(GUEST, ShogiNetMessage.PlayMove(legalForGote))
        assertEquals(2, t.host.game.state.value!!.ply)
    }

    @Test fun guestRejectsForgedStaleAndForeignStateSyncs() {
        val t = ShogiTestKit.table()
        val host = t.host.game
        host.playMove(move(host, sq(6, 4), sq(5, 4)))
        val guestState = t.guest.game.state.value!!
        val net = ShogiNetState(
            n = 9, cells = List(81) { 0 }, hands = List(14) { 0 }, sideToMove = 0, senteSeat = 0,
            players = listOf(ShogiNetPlayer(HOST, "Hana"), ShogiNetPlayer(GUEST, "Gus")),
            lastFrom = -1, lastTo = -1, lastCaptured = 0, ply = 0, gameOver = false, winnerPlayerId = null,
            endReason = "NONE", sessionWins = emptyMap(), sessionDraws = 0
        )
        // from a non-host sender
        t.guest.transport.inject(GUEST, ShogiNetMessage.StateSync(999, net))
        t.guest.transport.inject("mallory", ShogiNetMessage.StateSync(999, net))
        assertEquals(guestState, t.guest.game.state.value)
        // stale version from the real host
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(0, net))
        assertEquals(guestState, t.guest.game.state.value)
        // new version but no kings: not a plausible position
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(999, net))
        assertEquals(guestState, t.guest.game.state.value)
        // wrong board size for this variant
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(1000, net.copy(n = 5, cells = List(25) { 0 })))
        assertEquals(guestState, t.guest.game.state.value)
        // wrong roster
        t.guest.transport.inject(HOST, ShogiNetMessage.StateSync(1001, net.copy(players = listOf(ShogiNetPlayer("x", "X"), ShogiNetPlayer("y", "Y")))))
        assertEquals(guestState, t.guest.game.state.value)
        // the host never adopts a StateSync, even from the guest seat
        val hostState = host.state.value
        t.host.transport.inject(GUEST, ShogiNetMessage.StateSync(5000, net))
        assertEquals(hostState, host.state.value)
    }

    @Test fun requestStateReSendsOnlyToTheRealGuest() {
        val t = ShogiTestKit.table()
        val before = t.host.transport.sent.size
        t.host.transport.inject("mallory", ShogiNetMessage.RequestState)
        assertEquals(before, t.host.transport.sent.size)
        t.host.transport.inject(GUEST, ShogiNetMessage.RequestState)
        assertEquals(before + 1, t.host.transport.sent.size)
    }

    @Test fun versionsOnlyGoUp() {
        val t = ShogiTestKit.table()
        val v0 = t.host.transport.lastVersion()
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertTrue(t.host.transport.lastVersion() > v0)
    }

    @Test fun networkedGamesCannotUndoOrHint() {
        val t = ShogiTestKit.table()
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertFalse(t.host.game.canUndo)
        t.host.game.undo()
        assertEquals(1, t.host.game.state.value!!.ply)
        assertNull(t.guest.game.hint())
    }

    @Test fun opponentLeavingBlocksMovesUntilTheyReturn() {
        val t = ShogiTestKit.table()
        t.host.transport.fireLeft(GUEST)
        assertTrue(t.host.game.opponentLeft.value)
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertEquals(0, t.host.game.state.value!!.ply)
        t.host.transport.fireJoined(GUEST)
        assertFalse(t.host.game.opponentLeft.value)
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertEquals(1, t.host.game.state.value!!.ply)
    }

    @Test fun reconnectResendsTheState() {
        val t = ShogiTestKit.table()
        val before = t.host.transport.sent.size
        t.host.transport.fireReconnected()
        assertTrue(t.host.transport.sent.size > before)
        val gBefore = t.guest.transport.sent.size
        t.guest.transport.fireReconnected()
        assertEquals(gBefore + 1, t.guest.transport.sent.size)
    }

    @Test fun gameOverAndPlayAgainOverTheWire() {
        val t = ShogiTestKit.table()
        t.host.game.loadPosition(
            sparse(9, (0 to 4) to "k", (2 to 4) to "P", (8 to 8) to "K", hands = mapOf((0 to ShogiPiece.GOLD) to 1)), 0
        )
        val drop = t.host.game.state.value!!.legalMoves.first { ShogiMove.from(it) == 81 + ShogiPiece.GOLD && ShogiMove.to(it) == sq(1, 4) }
        t.host.game.playMove(drop)
        val g = t.guest.game.state.value!!
        assertTrue(g.gameOver)
        assertEquals(ShogiEnd.CHECKMATE, g.endReason)
        assertEquals(HOST, g.winnerPlayerId)
        assertEquals(1, t.guest.game.sessionWins.value[HOST])
        // a stranger cannot trigger a rematch; the guest can
        t.host.transport.inject("mallory", ShogiNetMessage.RequestPlayAgain)
        assertTrue(t.host.game.state.value!!.gameOver)
        t.guest.game.playAgain()
        assertFalse(t.host.game.state.value!!.gameOver)
        assertEquals(1, t.host.game.state.value!!.senteSeat)
        assertEquals(t.host.game.state.value!!.cells, t.guest.game.state.value!!.cells)
    }

    @Test fun guestSeatMovesFirstWhenItIsSente() {
        val t = ShogiTestKit.table()
        t.host.game.loadPosition(ShogiRules.of(ShogiVariant.STANDARD).startPosition(), sente = 1)
        assertEquals(1, t.guest.game.state.value!!.currentPlayerIndex)
        val m = move(t.guest.game, sq(6, 4), sq(5, 4))
        t.guest.game.playMove(m)
        assertEquals(1, t.host.game.state.value!!.ply)
        assertEquals(0, t.host.game.state.value!!.currentPlayerIndex)
    }

    @Test fun impasseDeclarationFromTheGuestIsHostValidated() {
        val t = ShogiTestKit.table()
        // not eligible: the host ignores it
        t.host.transport.inject(GUEST, ShogiNetMessage.DeclareImpasse)
        assertFalse(t.host.game.state.value!!.gameOver)
    }

    @Test fun miniVariantWorksOverTheWireAndRejectsStandardState() {
        val t = ShogiTestKit.table(ShogiVariant.MINI)
        assertEquals(25, t.guest.game.state.value!!.cells.size)
        val m = t.host.game.state.value!!.legalMoves.first()
        t.host.game.playMove(m)
        assertEquals(1, t.guest.game.state.value!!.ply)
        assertTrue(t.senderLies.isEmpty())
    }

    @Test fun onlineModeBehavesLikeNearby() {
        val t = ShogiTestKit.table(mode = com.gamesuite.core.PlayMode.ONLINE)
        t.host.game.playMove(move(t.host.game, sq(6, 4), sq(5, 4)))
        assertEquals(1, t.guest.game.state.value!!.ply)
    }
}
