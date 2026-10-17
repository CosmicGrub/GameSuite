package com.gamesuite.games.chess

import com.gamesuite.core.PlayMode
import com.gamesuite.games.chess.ChessNetKit.GUEST_ID
import com.gamesuite.games.chess.ChessNetKit.HOST_ID
import com.gamesuite.games.chess.ChessNetKit.sq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-authoritative Chess over a fake transport: the host owns the engine and applies every
 * move; the guest only asks. Ann (seat 0, id "host") is White, Bob (seat 1, id "guest-1") Black.
 */
class ChessNetworkedTest {

    private fun moveIntent(from: Int, to: Int, promotion: PieceType? = null) =
        ChessNetMessage.Intent(ChessIntentPayload.PlayMove(from, to, promotion))

    // --- Start and RequestState ------------------------------------------------------------

    @Test
    fun `the guest asks for state on init and the host answers it directly`() {
        val hub = ChessHub()
        val host = ChessNetKit.node(0, transport = hub.endpoint(HOST_ID), start = true)
        val guest = ChessNetKit.node(1, transport = hub.endpoint(GUEST_ID))

        val ask = guest.transport.sent.first()
        assertEquals(HOST_ID, ask.to)
        assertEquals(GUEST_ID, ask.from)
        assertEquals(ChessNetMessage.RequestState, ask.message)

        // The host answered only the asker, with the current version (not a fresh broadcast).
        val reply = host.transport.sent.last()
        assertEquals(GUEST_ID, reply.to)
        assertEquals(ChessNetKit.netStateOf(host.game), reply.stateSync.state)
        assertEquals(ChessNetKit.netStateOf(host.game), ChessNetKit.netStateOf(guest.game))
        assertEquals(PieceColor.WHITE, guest.game.state.value!!.sideToMove)
    }

    @Test
    fun `a guest startMatch never overwrites the synced board`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        val synced = p.guest.game.state.value
        assertEquals(PieceColor.BLACK, synced!!.sideToMove)
        p.guest.game.startMatch()
        assertSame(synced, p.guest.game.state.value)
    }

    @Test
    fun `a guest has no board until the host answers`() {
        val guest = ChessNetKit.node(1)
        guest.game.startMatch()
        assertNull(guest.game.state.value)
        assertFalse(guest.transport.sent.isEmpty())
    }

    @Test
    fun `a host ignores a RequestState from a stranger and from itself`() {
        val host = ChessNetKit.node(0, start = true)
        val mark = host.transport.mark()
        host.transport.inject("mallory", ChessNetMessage.RequestState)
        host.transport.inject(HOST_ID, ChessNetMessage.RequestState)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    // --- Host applies guest moves -----------------------------------------------------------

    @Test
    fun `the host applies a valid guest move and broadcasts the new authoritative state`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        val versionBefore = p.host.transport.sent.last().stateSync.version
        val mark = p.host.transport.mark()

        p.guest.game.playMove(1, sq('e', 7), sq('e', 5))

        val hostState = p.host.game.state.value!!
        assertEquals(Piece(PieceType.PAWN, PieceColor.BLACK), hostState.board[sq('e', 5)])
        assertNull(hostState.board[sq('e', 7)])
        assertEquals(PieceColor.WHITE, hostState.sideToMove)

        val broadcasts = p.host.transport.sentSince(mark).filter { it.message is ChessNetMessage.StateSync }
        assertEquals(1, broadcasts.size)
        assertNull("a broadcast, not addressed", broadcasts[0].to)
        assertTrue(broadcasts[0].stateSync.version > versionBefore)
        assertEquals(ChessNetKit.netStateOf(p.host.game), broadcasts[0].stateSync.state)
        assertEquals(hostState, p.guest.game.state.value)
    }

    @Test
    fun `a guest tap never changes the guest's own engine`() {
        val guest = ChessNetKit.node(1)
        val host = ChessNetKit.node(0, start = true)
        // Feed the guest the host's opening position directly, then let it tap on its own turn.
        host.game.playMove(0, sq('e', 2), sq('e', 4))
        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(1, ChessNetKit.netStateOf(host.game)))
        val before = guest.game.state.value
        val mark = guest.transport.mark()

        guest.game.playMove(1, sq('e', 7), sq('e', 5))

        assertSame(before, guest.game.state.value)
        val out = guest.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(HOST_ID, out[0].to)
        val intent = (out[0].message as ChessNetMessage.Intent).intent as ChessIntentPayload.PlayMove
        assertEquals(sq('e', 7), intent.from)
        assertEquals(sq('e', 5), intent.to)
    }

    @Test
    fun `a guest tap on the host's turn is not even sent`() {
        val p = ChessNetKit.connected()
        val mark = p.guest.transport.mark()
        p.guest.game.playMove(1, sq('e', 7), sq('e', 5))
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the host rejects an out-of-turn guest move`() {
        val host = ChessNetKit.node(0, start = true)
        val before = host.game.state.value
        val mark = host.transport.mark()
        // White is to move, so Black's request must be ignored.
        host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 5)))
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the host rejects an illegal guest move`() {
        val host = ChessNetKit.node(0, start = true)
        host.game.playMove(0, sq('e', 2), sq('e', 4))
        val before = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 4))) // a pawn cannot do that
        host.transport.inject(GUEST_ID, moveIntent(sq('a', 8), sq('a', 5))) // rook blocked by its own pawn
        host.transport.inject(GUEST_ID, moveIntent(-3, 99))                // off the board
        host.transport.inject(GUEST_ID, moveIntent(sq('e', 2), sq('e', 3))) // one of White's pieces
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the host rejects a spoofed or unknown sender`() {
        val host = ChessNetKit.node(0, start = true)
        val before = host.game.state.value
        val mark = host.transport.mark()
        // Somebody claiming to be the host seat, and somebody not at the table: both must be
        // ignored even though e2-e4 is a perfectly legal White move.
        host.transport.inject(HOST_ID, moveIntent(sq('e', 2), sq('e', 4)))
        host.transport.inject("mallory", moveIntent(sq('e', 2), sq('e', 4)))
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
        // And the genuine guest still cannot move White's pieces on White's turn.
        host.transport.inject(GUEST_ID, moveIntent(sq('e', 2), sq('e', 4)))
        assertSame(before, host.game.state.value)
    }

    @Test
    fun `the host refuses a promotion it cannot honour and a duplicate request`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        val mark = p.host.transport.mark()
        p.host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 5), PieceType.KNIGHT))
        assertTrue(p.host.transport.sentSince(mark).isEmpty())
        assertEquals(PieceColor.BLACK, p.host.game.state.value!!.sideToMove)

        // A queen (or no) promotion is fine; the same request again is then stale (White's turn).
        p.host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 5), PieceType.QUEEN))
        assertEquals(PieceColor.WHITE, p.host.game.state.value!!.sideToMove)
        val after = p.host.game.state.value
        p.host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 5)))
        assertSame(after, p.host.game.state.value)
    }

    @Test
    fun `the host's own tap can only move the host's seat`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        val before = p.host.game.state.value
        // Black's turn: neither the host's index 0 nor a screen passing the guest's index may move it.
        p.host.game.playMove(0, sq('e', 7), sq('e', 5))
        p.host.game.playMove(1, sq('e', 7), sq('e', 5))
        assertSame(before, p.host.game.state.value)
    }

    // --- Guest applies StateSync -------------------------------------------------------------

    @Test
    fun `a guest applies a newer state and ignores an older or equal one`() {
        val host = ChessNetKit.node(0, start = true)
        val guest = ChessNetKit.node(1)
        val start = ChessNetKit.netStateOf(host.game)
        host.game.playMove(0, sq('d', 2), sq('d', 4))
        val afterD4 = ChessNetKit.netStateOf(host.game)

        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(5, afterD4))
        assertEquals(PieceColor.BLACK, guest.game.state.value!!.sideToMove)

        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(3, start))
        assertEquals("older version ignored", PieceColor.BLACK, guest.game.state.value!!.sideToMove)
        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(5, start))
        assertEquals("equal version ignored", PieceColor.BLACK, guest.game.state.value!!.sideToMove)

        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(6, start))
        assertEquals("newer version applied", PieceColor.WHITE, guest.game.state.value!!.sideToMove)
    }

    @Test
    fun `a guest accepts state only from the host and only if it is well formed`() {
        val host = ChessNetKit.node(0, start = true)
        val guest = ChessNetKit.node(1)
        val good = ChessNetKit.netStateOf(host.game)

        guest.transport.inject("mallory", ChessNetMessage.StateSync(1, good))
        guest.transport.inject(GUEST_ID, ChessNetMessage.StateSync(1, good))
        assertNull(guest.game.state.value)

        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(1, good.copy(board = good.board.take(10))))
        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(1, good.copy(epSquare = 400)))
        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(1, good.copy(board = good.board.map { if (it?.type == PieceType.KING) null else it })))
        assertNull(guest.game.state.value)

        guest.transport.inject(HOST_ID, "not json".toByteArray())
        assertNull(guest.game.state.value)

        guest.transport.inject(HOST_ID, ChessNetMessage.StateSync(1, good))
        assertNotNull(guest.game.state.value)
    }

    @Test
    fun `a host never accepts a StateSync or a guest-bound message from the wire`() {
        val host = ChessNetKit.node(0, start = true)
        val before = host.game.state.value
        val forged = ChessNetKit.netStateOf(host.game).copy(sideToMove = PieceColor.BLACK)
        host.transport.inject(GUEST_ID, ChessNetMessage.StateSync(99, forged))
        assertSame(before, host.game.state.value)
    }

    // --- Reconnect ---------------------------------------------------------------------------

    @Test
    fun `a reconnecting guest asks again and a reconnecting host rebroadcasts`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))

        val guestMark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        val ask = p.guest.transport.sentSince(guestMark)
        assertEquals(1, ask.size)
        assertEquals(ChessNetMessage.RequestState, ask[0].message)
        assertEquals(HOST_ID, ask[0].to)

        // The guest missed the host's next two broadcasts while it was away.
        p.hub.dropTo += GUEST_ID
        p.guest.game.playMove(1, sq('e', 7), sq('e', 5))
        p.host.game.playMove(0, sq('g', 1), sq('f', 3))
        assertEquals("the guest is still on its old view", PieceColor.BLACK, p.guest.game.state.value!!.sideToMove)
        assertTrue(p.guest.game.state.value!!.board[sq('f', 3)] == null)

        p.hub.dropTo -= GUEST_ID
        val hostMark = p.host.transport.mark()
        val lastVersion = p.host.transport.sent.last { it.message is ChessNetMessage.StateSync }.stateSync.version
        p.host.transport.fireReconnected()
        val rebroadcast = p.host.transport.sentSince(hostMark).single()
        assertTrue(rebroadcast.stateSync.version > lastVersion)
        assertEquals(ChessNetKit.netStateOf(p.host.game), ChessNetKit.netStateOf(p.guest.game))
    }

    // --- Opponent leaves ---------------------------------------------------------------------

    @Test
    fun `the host marks an opponent that left, freezes the board and aborts without a result`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        assertFalse(p.host.game.opponentLeft.value)

        p.host.transport.fireLeft("someone-else")
        assertFalse("an unrelated id is not the opponent", p.host.game.opponentLeft.value)
        p.host.transport.fireLeft(GUEST_ID)
        assertTrue(p.host.game.opponentLeft.value)

        val before = p.host.game.state.value
        p.host.transport.inject(GUEST_ID, moveIntent(sq('e', 7), sq('e', 5)))
        assertSame(before, p.host.game.state.value)

        p.host.game.leaveSession()
        assertTrue(p.host.game.matchOver.value)
        val result = p.host.results.single()
        assertTrue(result.wasAborted)
        assertTrue(result.scores.isEmpty())
    }

    @Test
    fun `the guest marks a host that left and can leave without a recorded loss`() {
        val p = ChessNetKit.connected()
        p.guest.transport.fireLeft(HOST_ID)
        assertTrue(p.guest.game.opponentLeft.value)
        assertEquals("Ann", p.guest.game.opponentName)

        val mark = p.guest.transport.mark()
        p.guest.game.playMove(1, sq('e', 7), sq('e', 5))
        p.guest.game.playAgain()
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())

        p.guest.game.leaveSession()
        val result = p.guest.results.single()
        assertTrue(result.wasAborted)
        assertTrue(result.scores.isEmpty())
    }

    @Test
    fun `a finished session without a disconnect still reports its scores`() {
        val p = ChessNetKit.connected()
        playFoolsMate(p)
        p.guest.game.leaveSession()
        val result = p.guest.results.single()
        assertFalse(result.wasAborted)
        val bob = result.scores.first { it.playerId == GUEST_ID }
        assertEquals(1, bob.score)
        assertTrue(bob.isWinner)
    }

    // --- Play Again --------------------------------------------------------------------------

    @Test
    fun `play again mid-round is ignored from either seat`() {
        val p = ChessNetKit.connected()
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        val hostBefore = p.host.game.state.value
        val mark = p.guest.transport.mark()

        p.guest.game.playAgain()
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
        p.guest.transport.inject(HOST_ID, ChessNetMessage.RequestState) // harmless on a guest
        p.host.transport.inject(GUEST_ID, ChessNetMessage.Intent(ChessIntentPayload.PlayAgain))
        p.host.game.playAgain()
        assertSame(hostBefore, p.host.game.state.value)
    }

    @Test
    fun `either player can ask for play again after a result and the host re-deals for both`() {
        val p = ChessNetKit.connected()
        playFoolsMate(p)
        assertTrue(p.host.game.state.value!!.roundOver)
        assertEquals(1, p.host.game.scoreP2.value)

        p.guest.game.playAgain()
        assertFalse(p.host.game.state.value!!.roundOver)
        assertFalse(p.guest.game.state.value!!.roundOver)
        assertEquals(PieceColor.WHITE, p.guest.game.state.value!!.sideToMove)
        assertEquals("the running score carries over", 1, p.guest.game.scoreP2.value)
        assertEquals(ChessNetKit.netStateOf(p.host.game), ChessNetKit.netStateOf(p.guest.game))

        // Host is White again and can open; then a second finished game via the host's own tap.
        playFoolsMate(p)
        assertEquals(2, p.host.game.scoreP2.value)
        p.host.game.playAgain()
        assertFalse(p.guest.game.state.value!!.roundOver)
        assertEquals(2, p.guest.game.scoreP2.value)
    }

    // --- A scripted game ---------------------------------------------------------------------

    private fun playFoolsMate(p: ChessNetKit.Pair2) {
        p.host.game.playMove(0, sq('f', 2), sq('f', 3))
        p.guest.game.playMove(1, sq('e', 7), sq('e', 5))
        p.host.game.playMove(0, sq('g', 2), sq('g', 4))
        p.guest.game.playMove(1, sq('d', 8), sq('h', 4))
    }

    @Test
    fun `a scripted game between a host and a guest ends in identical positions`() {
        val p = ChessNetKit.connected(PlayMode.ONLINE)
        p.host.game.playMove(0, sq('e', 2), sq('e', 4))
        p.guest.game.playMove(1, sq('c', 7), sq('c', 5))
        p.host.game.playMove(0, sq('g', 1), sq('f', 3))
        p.guest.game.playMove(1, sq('d', 7), sq('d', 6))
        // A tap out of turn (it is White's move) changes nothing on either side.
        p.guest.game.playMove(1, sq('a', 7), sq('a', 4))
        assertEquals(p.host.game.state.value, p.guest.game.state.value)
        p.host.game.playMove(0, sq('d', 2), sq('d', 4))
        p.guest.game.playMove(1, sq('c', 5), sq('d', 4))
        p.host.game.playMove(0, sq('f', 3), sq('d', 4))

        assertEquals(p.host.game.state.value, p.guest.game.state.value)
        assertEquals(PieceColor.BLACK, p.guest.game.state.value!!.sideToMove)
        assertEquals(Piece(PieceType.KNIGHT, PieceColor.WHITE), p.guest.game.state.value!!.board[sq('d', 4)])
        assertEquals(p.host.game.scoreP1.value, p.guest.game.scoreP1.value)
        assertTrue("every sender told the truth", p.hub.senderLies.isEmpty())
        // Networked games are not resumable and have no undo/hint.
        assertNull(p.host.game.snapshot())
        assertNull(p.guest.game.snapshot())
        assertFalse(p.host.game.canUndo)
        assertNull(p.host.game.hint())
        assertNull(p.guest.game.hint())
    }

    @Test
    fun `a full fool's mate ends the same way on both devices and names Bob the winner`() {
        val p = ChessNetKit.connected()
        playFoolsMate(p)
        val h = p.host.game.state.value!!
        val g = p.guest.game.state.value!!
        assertEquals(h, g)
        assertEquals(ChessResult.BLACK_WINS, g.result)
        assertEquals(GUEST_ID, g.winnerPlayerId)
        assertEquals(1, p.guest.game.scoreP2.value)
        assertEquals(0, p.guest.game.scoreP1.value)
    }
}
