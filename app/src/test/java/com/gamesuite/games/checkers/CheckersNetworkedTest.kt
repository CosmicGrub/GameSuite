package com.gamesuite.games.checkers

import com.gamesuite.core.PlayMode
import com.gamesuite.games.checkers.CheckersTestKit.GUEST
import com.gamesuite.games.checkers.CheckersTestKit.HOST
import com.gamesuite.games.checkers.CheckersTestKit.doubleJumpBoard
import com.gamesuite.games.checkers.CheckersTestKit.doubleJumpPath
import com.gamesuite.games.checkers.CheckersTestKit.square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-authoritative Checkers over a fake transport: host = seat 0 (Light, moves first), guest =
 * seat 1 (Dark). The guest's engine never changes from a tap; it submits one whole turn and
 * applies the host's newer StateSyncs.
 */
class CheckersNetworkedTest {

    private fun request(baseVersion: Int, fromRow: Int, fromCol: Int, path: List<Pair<Int, Int>>) =
        CheckersNetMessage.MoveRequest(baseVersion, square(fromRow, fromCol), path.map { square(it.first, it.second) })

    private fun doubleJumpRequest(baseVersion: Int = 1) = request(baseVersion, 1, 0, doubleJumpPath)

    // ---- start / RequestState -------------------------------------------------------------

    @Test
    fun `guest start asks the host and ends up with the host's opening position`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)

        // The guest's first message is RequestState, addressed to the host and signed as the guest.
        val first = guest.transport.sent.first()
        assertEquals(GUEST, first.from)
        assertEquals(HOST, first.to)
        assertTrue(first.message is CheckersNetMessage.RequestState)

        val h = host.game.state.value!!
        val g = guest.game.state.value!!
        assertEquals(h, g)
        // Light (side 1) is held by the host and moves first.
        assertEquals(0, g.currentPlayerIndex)
        assertEquals(0, host.game.openingSeatIndex)
        assertEquals(0, guest.game.openingSeatIndex)
        assertEquals(1, guest.game.sideOfSeat(0))
        assertEquals(0, guest.game.sideOfSeat(1))
        assertTrue(hub.senderLies.isEmpty())
    }

    @Test
    fun `host answers RequestState only to the guest seat`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val before = host.transport.mark()
        host.transport.inject("intruder", CheckersNetMessage.RequestState())
        host.transport.inject(HOST, CheckersNetMessage.RequestState())
        assertTrue(host.transport.sentSince(before).isEmpty())
        host.transport.inject(GUEST, CheckersNetMessage.RequestState())
        val replies = host.transport.sentSince(before)
        assertEquals(1, replies.size)
        assertEquals(GUEST, replies[0].to)
        assertEquals(HOST, replies[0].from)
    }

    @Test
    fun `a guest started before the host deals has no board until the host's StateSync arrives`() {
        val hub = CheckersHub()
        val hostT = hub.endpoint(HOST)
        val guest = CheckersTestKit.guest(hub)
        guest.game.startMatch() // the guest never deals
        assertNull(guest.game.state.value)

        val host = CheckersTestKit.node(0, hostT)
        host.game.startMatch() // broadcasts to the guest
        assertEquals(host.game.state.value, guest.game.state.value)
    }

    // ---- guest moves ----------------------------------------------------------------------

    @Test
    fun `a valid guest single move is accepted by the host and broadcast`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        assertTrue(CheckersTestKit.playFirstLegalTurn(host)) // v2
        assertEquals(1, guest.game.state.value!!.currentPlayerIndex)

        val before = host.transport.mark()
        assertTrue(CheckersTestKit.playFirstLegalTurn(guest))

        val h = host.game.state.value!!
        assertEquals(0, h.currentPlayerIndex)
        assertNotNull(h.lastMove)
        val sync = host.transport.sentSince(before).single().stateSync
        assertEquals(3, sync.version)
        assertEquals(h.lastMove!!.toWire(), sync.lastMove)
        assertEquals(h, guest.game.state.value)
        // Dark's piece really moved on the host's board.
        val m = h.lastMove!!
        assertEquals(CheckersPiece(0, PieceKind.MAN), h.pieceAt(m.toRow, m.toCol))
        assertNull(h.pieceAt(m.fromRow, m.fromCol))
    }

    @Test
    fun `a guest multi-jump is accepted as one move and broadcast once`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        val guest = CheckersTestKit.guest(hub)
        assertEquals(1, guest.game.state.value!!.currentPlayerIndex)

        // The UI builds the chain locally without touching the engine.
        val first = guest.game.previewChain(1, 0, emptyList())!!
        assertEquals(setOf(3 to 2), first.destinations)
        val mid = guest.game.previewChain(1, 0, listOf(3 to 2))!!
        assertFalse(mid.complete)
        assertEquals(setOf(5 to 4), mid.destinations)
        assertEquals(CheckersPiece(0, PieceKind.MAN), guest.game.state.value!!.pieceAt(1, 0)) // unchanged

        val before = host.transport.mark()
        assertTrue(guest.game.submitMove(1, 0, doubleJumpPath))
        val sentByHost = host.transport.sentSince(before)
        assertEquals(1, sentByHost.size)

        val h = host.game.state.value!!
        assertNull(h.pieceAt(2, 1))
        assertNull(h.pieceAt(4, 3))
        assertNull(h.pieceAt(1, 0))
        assertEquals(CheckersPiece(0, PieceKind.MAN), h.pieceAt(5, 4))
        assertEquals(0, h.currentPlayerIndex)
        assertFalse(h.inForcedContinuation)
        assertEquals(2, h.lastTurnHops.size)
        assertEquals(CheckersMove(1, 0, 5, 4, isCapture = true), h.lastMove)
        assertEquals(h, guest.game.state.value)
    }

    @Test
    fun `host rejects a non-capturing move while a capture is mandatory`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        val guest = CheckersTestKit.guest(hub)
        val boardBefore = host.game.state.value
        val mark = host.transport.mark()

        // The spare Dark man at (0,5) may step to (1,4) in general, but a jump is available.
        host.transport.inject(GUEST, request(1, 0, 5, listOf(1 to 4)))
        assertEquals(boardBefore, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())

        // The guest's own UI refuses to send it as well.
        assertFalse(guest.game.submitMove(0, 5, listOf(1 to 4)))
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `host rejects an incomplete or over-long multi-jump and a different piece mid-chain`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        CheckersTestKit.guest(hub)
        val boardBefore = host.game.state.value
        val mark = host.transport.mark()

        host.transport.inject(GUEST, request(1, 1, 0, listOf(3 to 2))) // stops with a jump still owed
        host.transport.inject(GUEST, request(1, 1, 0, doubleJumpPath + (6 to 3))) // extra hop after the turn passed
        host.transport.inject(GUEST, request(1, 1, 0, listOf(3 to 2, 1 to 4))) // not a legal second hop
        host.transport.inject(GUEST, CheckersNetMessage.MoveRequest(1, square(1, 0), emptyList()))
        host.transport.inject(GUEST, CheckersNetMessage.MoveRequest(1, 200, listOf(3)))
        host.transport.inject(GUEST, CheckersNetMessage.MoveRequest(1, square(1, 0), List(40) { 0 }))
        assertEquals(boardBefore, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `host rejects a move when it is not the guest's turn`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub) // host (Light) is to move
        val boardBefore = host.game.state.value
        val mark = host.transport.mark()

        host.transport.inject(GUEST, request(1, 2, 1, listOf(3 to 0))) // a legal-looking Dark step
        assertEquals(boardBefore, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
        assertFalse(guest.game.submitMove(2, 1, listOf(3 to 0)))
    }

    @Test
    fun `host rejects a move for the wrong colour or from an empty square`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        CheckersTestKit.guest(hub)
        val boardBefore = host.game.state.value
        // The guest tries to move a Light piece, then an empty square.
        host.transport.inject(GUEST, request(1, 2, 1, listOf(1 to 0)))
        host.transport.inject(GUEST, request(1, 3, 4, listOf(4 to 5)))
        assertEquals(boardBefore, host.game.state.value)
    }

    @Test
    fun `host ignores a move from the wrong sender`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        CheckersTestKit.guest(hub)
        val boardBefore = host.game.state.value
        val mark = host.transport.mark()

        host.transport.inject(HOST, doubleJumpRequest()) // the host's own id: not a guest request
        host.transport.inject("intruder", doubleJumpRequest())
        assertEquals(boardBefore, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())

        host.transport.inject(GUEST, doubleJumpRequest()) // the real guest
        assertEquals(0, host.game.state.value!!.currentPlayerIndex)
        assertEquals(CheckersPiece(0, PieceKind.MAN), host.game.state.value!!.pieceAt(5, 4))
    }

    @Test
    fun `host ignores a stale request and a replayed one`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, doubleJumpBoard())
        CheckersTestKit.guest(hub)
        val boardBefore = host.game.state.value
        val mark = host.transport.mark()

        host.transport.inject(GUEST, doubleJumpRequest(baseVersion = 0)) // computed against an older board
        host.transport.inject(GUEST, doubleJumpRequest(baseVersion = 7)) // from the future
        assertEquals(boardBefore, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())

        host.transport.inject(GUEST, doubleJumpRequest())
        val afterFirst = host.game.state.value
        val sentAfterFirst = host.transport.mark()
        host.transport.inject(GUEST, doubleJumpRequest()) // replay of the accepted request
        assertEquals(afterFirst, host.game.state.value)
        assertEquals(sentAfterFirst, host.transport.mark())
    }

    @Test
    fun `host ignores requests once the round is over`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, CheckersTestKit.board(CheckersTestKit.dark(1, 0), CheckersTestKit.light(2, 1)))
        CheckersTestKit.guest(hub)
        host.transport.inject(GUEST, request(1, 1, 0, listOf(3 to 2)))
        assertTrue(host.game.state.value!!.gameOver)
        val over = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject(GUEST, request(2, 3, 2, listOf(4 to 3)))
        assertEquals(over, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    // ---- guest applies ------------------------------------------------------------------------

    @Test
    fun `guest applies only newer versions and ignores older or equal ones`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        assertTrue(CheckersTestKit.playFirstLegalTurn(host)) // v2
        assertTrue(CheckersTestKit.playFirstLegalTurn(guest)) // v3
        assertTrue(CheckersTestKit.playFirstLegalTurn(host)) // v4
        val latest = guest.game.state.value
        val syncs = host.transport.stateSyncs()
        assertEquals(listOf(1, 2, 3, 4), syncs.map { it.version }.distinct())

        // Older and equal versions are dropped: the board does not rewind.
        syncs.take(3).forEach { guest.transport.inject(HOST, it) }
        syncs.last().let { guest.transport.inject(HOST, it) }
        assertEquals(latest, guest.game.state.value)

        // A newer one is applied (here: the same snapshot with a bumped version and no move detail).
        val newer = syncs.last().copy(version = 9, lastMove = null, hops = emptyList())
        guest.transport.inject(HOST, newer)
        assertEquals(latest!!.board, guest.game.state.value!!.board)
        assertNull(guest.game.state.value!!.lastMove)
        // ... and nothing older than 9 gets in afterwards.
        guest.transport.inject(HOST, syncs.last().copy(version = 8))
        assertNull(guest.game.state.value!!.lastMove)
    }

    @Test
    fun `guest ignores a StateSync that did not come from the host or is malformed`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        val before = guest.game.state.value
        val real = host.transport.stateSyncs().last()

        guest.transport.inject(GUEST, real.copy(version = 50, snapshot = real.snapshot.replace("\"currentPlayerIndex\":0", "\"currentPlayerIndex\":1")))
        guest.transport.inject("intruder", real.copy(version = 51))
        guest.transport.inject(HOST, real.copy(version = 52, snapshot = "not json"))
        guest.transport.inject(HOST, real.copy(version = 53, snapshot = "{}"))
        guest.transport.inject(HOST, byteArrayOf(1, 2, 3))
        guest.transport.inject(HOST, "{\"type\":\"nope\"}".encodeToByteArray())
        assertEquals(before, guest.game.state.value)

        // The failed attempts must not have burned version numbers: version 2 is still accepted.
        guest.transport.inject(HOST, real.copy(version = 2, lastMove = CheckersWireMove(5, 0, 4, 1, false), hops = emptyList()))
        assertEquals(CheckersMove(5, 0, 4, 1, false), guest.game.state.value!!.lastMove)
    }

    @Test
    fun `a guest never mutates its engine from playMove`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        assertTrue(CheckersTestKit.playFirstLegalTurn(host))
        val before = guest.game.state.value
        guest.game.playMove(1, 2, 1, 3, 0)
        guest.game.playMove(0, 5, 0, 4, 1)
        assertEquals(before, guest.game.state.value)
        // The host's engine refuses playMove for the guest's seat too.
        val hostBefore = host.game.state.value
        host.game.playMove(1, 2, 1, 3, 0)
        assertEquals(hostBefore, host.game.state.value)
    }

    // ---- reconnect --------------------------------------------------------------------------------

    @Test
    fun `a reconnecting guest asks the host again and catches up`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        hub.dropTo += GUEST // the guest's link is down
        assertTrue(CheckersTestKit.playFirstLegalTurn(host))
        assertFalse(host.game.state.value == guest.game.state.value)

        hub.dropTo -= GUEST
        val mark = guest.transport.mark()
        guest.transport.fireReconnected()
        val asked = guest.transport.sentSince(mark).single()
        assertTrue(asked.message is CheckersNetMessage.RequestState)
        assertEquals(HOST, asked.to)
        assertEquals(host.game.state.value, guest.game.state.value)
    }

    @Test
    fun `a reconnecting host re-broadcasts its position`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        hub.dropTo += GUEST
        assertTrue(CheckersTestKit.playFirstLegalTurn(host))
        hub.dropTo -= GUEST
        host.transport.fireReconnected()
        assertEquals(host.game.state.value, guest.game.state.value)
    }

    // ---- leaving ------------------------------------------------------------------------------------

    @Test
    fun `the host sees the guest leave and leaving records no result`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        CheckersTestKit.guest(hub)
        host.transport.fireLeft("someone-else")
        assertFalse(host.game.opponentLeft.value)
        host.transport.fireLeft(GUEST)
        assertTrue(host.game.opponentLeft.value)

        host.game.leaveSession()
        val result = host.result!!
        assertTrue(result.wasAborted)
        assertTrue(result.scores.isEmpty())
        assertTrue(host.game.matchOver.value)
    }

    @Test
    fun `the guest sees the host leave`() {
        val hub = CheckersHub()
        CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        guest.transport.fireLeft(HOST)
        assertTrue(guest.game.opponentLeft.value)
        assertFalse(guest.game.submitMove(2, 1, listOf(3 to 0)))
        guest.game.leaveSession()
        assertTrue(guest.result!!.wasAborted)
    }

    @Test
    fun `an opponent who left after a finished round does not erase that round`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, CheckersTestKit.board(CheckersTestKit.dark(1, 0), CheckersTestKit.light(2, 1)))
        val guest = CheckersTestKit.guest(hub)
        assertTrue(guest.game.submitMove(1, 0, listOf(3 to 2)))
        assertTrue(host.game.state.value!!.gameOver)
        host.transport.fireLeft(GUEST)
        host.game.leaveSession()
        // A finished round is scored: this is a real result, not an abort.
        assertFalse(host.result!!.wasAborted)
        assertEquals(1, host.result!!.scores.first { it.playerId == GUEST }.score)
    }

    // ---- play again --------------------------------------------------------------------------------

    @Test
    fun `guest asks for Play Again and the host re-deals and broadcasts keeping the score`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, CheckersTestKit.board(CheckersTestKit.dark(1, 0), CheckersTestKit.light(2, 1)))
        val guest = CheckersTestKit.guest(hub)
        assertTrue(guest.game.submitMove(1, 0, listOf(3 to 2)))
        assertTrue(host.game.state.value!!.gameOver)
        assertEquals(GUEST, host.game.state.value!!.winnerPlayerId)
        assertEquals(1, host.game.scoreP2.value)
        assertEquals(host.game.state.value, guest.game.state.value)
        assertEquals(1, guest.game.scoreP2.value)

        val mark = host.transport.mark()
        guest.game.playAgain()
        assertTrue(guest.transport.sent.last().message is CheckersNetMessage.PlayAgainRequest)

        val fresh = host.game.state.value!!
        assertFalse(fresh.gameOver)
        assertNull(fresh.lastMove)
        assertEquals(0, fresh.currentPlayerIndex)
        assertEquals(12, fresh.board.count { it?.owner == 0 })
        assertEquals(12, fresh.board.count { it?.owner == 1 })
        assertEquals(1, host.game.scoreP2.value) // the session score is kept
        assertEquals(1, host.transport.sentSince(mark).size)
        assertEquals(fresh, guest.game.state.value)
        assertEquals(1, guest.game.scoreP2.value)
    }

    @Test
    fun `a host Play Again re-deals on the guest too`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(
            hub,
            CheckersTestKit.board(CheckersTestKit.light(3, 2), CheckersTestKit.dark(2, 1)),
            currentSeat = 0
        )
        val guest = CheckersTestKit.guest(hub)
        assertTrue(CheckersTestKit.playFirstLegalTurn(host))
        assertTrue(guest.game.state.value!!.gameOver)
        assertEquals(1, guest.game.scoreP1.value)
        host.game.playAgain()
        val fresh = host.game.state.value!!
        assertFalse(fresh.gameOver)
        assertEquals(fresh, guest.game.state.value)
        assertEquals(1, guest.game.scoreP1.value)
    }

    @Test
    fun `Play Again is ignored while the round is live or from the wrong sender`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        val before = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject(GUEST, CheckersNetMessage.PlayAgainRequest()) // round is live
        host.transport.inject("intruder", CheckersNetMessage.PlayAgainRequest())
        host.game.playAgain() // host cannot re-deal a live round either
        guest.game.playAgain() // nor can the guest ask for one
        assertEquals(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
        assertEquals(1, guest.transport.sent.count { it.message is CheckersNetMessage.RequestState })
        assertEquals(0, guest.transport.sent.count { it.message is CheckersNetMessage.PlayAgainRequest })
    }

    // ---- scripted game ---------------------------------------------------------------------------

    @Test
    fun `a scripted game stays identical on both engines every turn`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub, mode = PlayMode.ONLINE)
        val guest = CheckersTestKit.guest(hub, mode = PlayMode.ONLINE)
        var plies = 0
        var lastVersion = 0
        while (plies < 200) {
            val s = host.game.state.value!!
            if (s.gameOver) break
            val mover = if (s.currentPlayerIndex == 0) host else guest
            assertTrue("ply $plies: the mover must have a legal turn", CheckersTestKit.playFirstLegalTurn(mover))
            plies++
            assertEquals("ply $plies", host.game.state.value, guest.game.state.value)
            assertEquals(host.game.scoreP1.value, guest.game.scoreP1.value)
            assertEquals(host.game.scoreP2.value, guest.game.scoreP2.value)
            val v = host.transport.stateSyncs().last().version
            assertTrue(v > lastVersion)
            lastVersion = v
        }
        assertTrue("the scripted game must have run", plies > 4)
        assertTrue(hub.senderLies.isEmpty())
        if (host.game.state.value!!.gameOver) {
            assertEquals(host.game.state.value!!.winnerPlayerId, guest.game.state.value!!.winnerPlayerId)
        }
    }

    @Test
    fun `host mid-multi-jump states reach the guest hop by hop and the guest cannot move then`() {
        val hub = CheckersHub()
        // Light (host, seat 0) to move: two jumps available in a row for the man on (6,1).
        val host = CheckersTestKit.host(
            hub,
            CheckersTestKit.board(CheckersTestKit.light(6, 1), CheckersTestKit.dark(5, 2), CheckersTestKit.dark(3, 4), CheckersTestKit.dark(0, 1)),
            currentSeat = 0
        )
        val guest = CheckersTestKit.guest(hub)
        host.game.playMove(0, 6, 1, 4, 3)
        assertTrue(host.game.state.value!!.inForcedContinuation)
        assertEquals(host.game.state.value, guest.game.state.value)
        assertEquals(0, guest.game.state.value!!.currentPlayerIndex)
        assertFalse(guest.game.submitMove(0, 1, listOf(1 to 0)))
        host.game.playMove(0, 4, 3, 2, 5)
        assertEquals(host.game.state.value, guest.game.state.value)
        assertEquals(1, guest.game.state.value!!.currentPlayerIndex)
    }

    // ---- gating --------------------------------------------------------------------------------------

    @Test
    fun `networked play is not resumable and has no undo or hint`() {
        val hub = CheckersHub()
        val host = CheckersTestKit.host(hub)
        val guest = CheckersTestKit.guest(hub)
        assertTrue(CheckersTestKit.playFirstLegalTurn(host))
        for (node in listOf(host, guest)) {
            assertNull(node.game.snapshot())
            assertFalse(node.game.canUndo)
            assertNull(node.game.hint())
            val boardBefore = node.game.state.value
            node.game.undo()
            assertEquals(boardBefore, node.game.state.value)
        }
        val saved = "{}"
        assertFalse(host.game.restore(saved))
        assertTrue(host.game.isNetworked)
        assertTrue(guest.game.isNetworkGuest)
        assertFalse(host.game.isNetworkGuest)
    }

    @Test
    fun `both networked modes are advertised and local modes are untouched`() {
        val modes = CheckersGame().supportedModes
        assertTrue(PlayMode.LOCAL_AD_HOC in modes)
        assertTrue(PlayMode.ONLINE in modes)
        assertTrue(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY in modes)
        assertTrue(PlayMode.SINGLE_PLAYER_VS_BOT in modes)
    }

    @Test
    fun `local pass and play still saves, undoes and keeps its opening seat`() {
        val game = CheckersGame()
        val t = CheckersFakeTransport()
        game.openingSeatIndex = 0
        game.init(
            com.gamesuite.core.GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = CheckersTestKit.players,
                localPlayerIndex = 0,
                transport = t
            )
        )
        game.startMatch()
        assertEquals(0, game.openingSeatIndex) // init does not override it outside networked modes
        assertFalse(game.isNetworked)
        assertNotNull(game.snapshot())
        val from = (0..63).first { game.hasLegalMoveFrom(it / 8, it % 8) }
        val to = game.legalDestinationsFrom(from / 8, from % 8).first()
        game.playMove(0, from / 8, from % 8, to.first, to.second)
        assertTrue(game.canUndo)
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun `wire messages round trip and foreign payloads decode to null`() {
        val msgs = listOf(
            CheckersNetMessage.StateSync(3, "snap", CheckersWireMove(1, 0, 3, 2, true, 2, 1), listOf(CheckersWireMove(1, 0, 3, 2, true, 2, 1))),
            CheckersNetMessage.MoveRequest(4, 8, listOf(26, 44)),
            CheckersNetMessage.RequestState(),
            CheckersNetMessage.PlayAgainRequest()
        )
        for (m in msgs) {
            val back = decodeCheckersNetMessage(encodeCheckersNetMessage(m))
            assertNotNull(back)
            assertEquals(m::class, back!!::class)
        }
        assertEquals(msgs[0], decodeCheckersNetMessage(encodeCheckersNetMessage(msgs[0])))
        assertEquals(msgs[1], decodeCheckersNetMessage(encodeCheckersNetMessage(msgs[1])))
        assertNull(decodeCheckersNetMessage(byteArrayOf(0, 1, 2)))
        assertNull(CheckersWireMove(9, 0, 0, 0, false).toMoveOrNull())
        assertEquals(CheckersMove(1, 0, 3, 2, true, 2, 1), CheckersWireMove(1, 0, 3, 2, true, 2, 1).toMoveOrNull())
    }
}
