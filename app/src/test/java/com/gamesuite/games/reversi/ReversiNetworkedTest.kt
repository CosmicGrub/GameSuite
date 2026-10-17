package com.gamesuite.games.reversi

import com.gamesuite.core.PlayMode
import com.gamesuite.games.reversi.ReversiTestKit.GUEST
import com.gamesuite.games.reversi.ReversiTestKit.HOST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reversi over LOCAL_AD_HOC / ONLINE: the host (seat 0, dark) owns the engine, the guest (seat 1, light)
 * only forwards taps and renders the host's versioned StateSync.
 */
class ReversiNetworkedTest {

    private val modes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)

    private fun ReversiNode.state() = game.state.value!!

    /** Host plays its first legal cell so it is the guest's turn. */
    private fun hostOpens(p: ReversiPair) {
        p.host.game.placeDisc(p.host.state().legalMoves.sorted().first())
        assertEquals(1, p.host.state().currentPlayerIndex)
    }

    // ---- Modes and start ----

    @Test
    fun `reversi advertises both networked modes`() {
        assertTrue(PlayMode.LOCAL_AD_HOC in ReversiGame().supportedModes)
        assertTrue(PlayMode.ONLINE in ReversiGame().supportedModes)
    }

    @Test
    fun `a guest asks for the state on start and renders the host's board without dealing`() {
        for (mode in modes) {
            val p = ReversiTestKit.pair(mode)
            val first = p.guest.transport.sent.first()
            assertEquals(GUEST, first.from)
            assertEquals(HOST, first.to)
            assertTrue(first.message is ReversiNetMessage.RequestState)
            assertEquals(p.host.state(), p.guest.state())
            assertEquals(0, p.guest.state().currentPlayerIndex) // host (dark) opens
            assertTrue(p.guest.state().legalMoves.isNotEmpty())
            assertTrue(p.hub.senderLies.isEmpty())
        }
    }

    @Test
    fun `a guest that starts before any host state exists stays empty`() {
        val g = ReversiTestKit.node(1, start = true)
        assertNull(g.game.state.value)
    }

    @Test
    fun `RequestState from the guest seat gets the current state, from anyone else nothing`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val version = p.host.transport.lastSentVersion()

        val mark = p.host.transport.mark()
        p.host.transport.inject("stranger", ReversiNetMessage.RequestState)
        p.host.transport.inject(HOST, ReversiNetMessage.RequestState)
        assertTrue(p.host.transport.sentSince(mark).isEmpty())

        p.host.transport.inject(GUEST, ReversiNetMessage.RequestState)
        val replies = p.host.transport.sentSince(mark)
        assertEquals(1, replies.size)
        assertEquals(GUEST, replies[0].to)
        assertEquals(version, replies[0].stateSync.version)
        assertEquals(p.host.state().cells, replies[0].stateSync.state.cells)
    }

    // ---- Guest moves ----

    @Test
    fun `a valid guest move is applied by the host and broadcast with a newer version`() {
        for (mode in modes) {
            val p = ReversiTestKit.pair(mode)
            hostOpens(p)
            val move = p.host.state().legalMoves.sorted().first()
            val before = p.host.transport.lastSentVersion()
            val mark = p.host.transport.mark()

            p.guest.game.placeDisc(move)

            assertEquals(1, p.host.state().cells[move])
            assertTrue(p.host.state().lastAction.startsWith("Gus places"))
            val syncs = p.host.transport.sentSince(mark)
            assertEquals(1, syncs.size)
            assertEquals(HOST, syncs[0].from)
            assertEquals(GUEST, syncs[0].to)
            assertEquals(before + 1, syncs[0].stateSync.version)
            assertEquals(p.host.state(), p.guest.state())
            assertEquals(p.host.game.sessionWins.value, p.guest.game.sessionWins.value)
        }
    }

    @Test
    fun `a guest tap sends one request and never changes the guest's own engine`() {
        val g = ReversiTestKit.node(1)
        val cells = ReversiTestKit.openingCells()
        g.transport.inject(HOST, ReversiNetMessage.StateSync(1, ReversiTestKit.netState(current = 1, cells = cells)))
        val before = g.state()
        val move = before.legalMoves.sorted().first()
        val mark = g.transport.mark()

        g.game.placeDisc(move)

        assertEquals(before, g.state())
        val out = g.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(GUEST, out[0].from)
        assertEquals(HOST, out[0].to)
        assertEquals(ReversiNetMessage.PlaceDisc(move), out[0].message)
    }

    @Test
    fun `a guest tap off its own turn or on an illegal cell sends nothing`() {
        val g = ReversiTestKit.node(1)
        g.transport.inject(HOST, ReversiNetMessage.StateSync(1, ReversiTestKit.netState(current = 0)))
        val mark = g.transport.mark()
        g.game.placeDisc(g.state().legalMoves.sorted().first()) // seat 0's cell on seat 0's turn
        assertTrue(g.transport.sentSince(mark).isEmpty())

        g.transport.inject(HOST, ReversiNetMessage.StateSync(2, ReversiTestKit.netState(current = 1)))
        g.game.placeDisc(0) // not a legal cell
        g.game.placeDisc(27) // occupied
        assertTrue(g.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the host cannot tap on the guest's turn`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val before = p.host.state()
        p.host.game.placeDisc(before.legalMoves.sorted().first())
        assertEquals(before, p.host.state())
    }

    @Test
    fun `an illegal, occupied or out of range cell from the guest is ignored`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val before = p.host.state()
        val mark = p.host.transport.mark()
        for (cell in listOf(0, 27, 63, -1, 64, 1000, Int.MIN_VALUE)) {
            p.host.transport.inject(GUEST, ReversiNetMessage.PlaceDisc(cell))
        }
        assertEquals(before, p.host.state())
        assertTrue(p.host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `a guest request on the host's turn is ignored`() {
        val p = ReversiTestKit.pair()
        val before = p.host.state()
        val mark = p.host.transport.mark()
        // Cell legal for the host right now; the guest has no business playing it.
        p.host.transport.inject(GUEST, ReversiNetMessage.PlaceDisc(before.legalMoves.sorted().first()))
        assertEquals(before, p.host.state())
        assertTrue(p.host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `a move request that claims to come from the host seat or a stranger is ignored`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val before = p.host.state()
        val move = before.legalMoves.sorted().first()
        val mark = p.host.transport.mark()

        p.host.transport.inject(HOST, ReversiNetMessage.PlaceDisc(move))
        p.host.transport.inject("stranger", ReversiNetMessage.PlaceDisc(move))
        p.host.transport.inject(GUEST, "not json".toByteArray())

        assertEquals(before, p.host.state())
        assertTrue(p.host.transport.sentSince(mark).isEmpty())

        p.host.transport.inject(GUEST, ReversiNetMessage.PlaceDisc(move))
        assertEquals(1, p.host.state().cells[move])
    }

    @Test
    fun `the host never adopts a StateSync from anyone`() {
        val p = ReversiTestKit.pair()
        val before = p.host.state()
        val forged = ReversiNetMessage.StateSync(99, ReversiTestKit.netState(current = 1, lastAction = "forged"))
        p.host.transport.inject(GUEST, forged)
        p.host.transport.inject(HOST, forged)
        assertEquals(before, p.host.state())
    }

    // ---- Guest applying state ----

    @Test
    fun `a guest applies only newer versions from the host seat and rejects malformed boards`() {
        val g = ReversiTestKit.node(1, PlayMode.ONLINE)
        fun sync(v: Int, label: String) = ReversiNetMessage.StateSync(v, ReversiTestKit.netState(lastAction = label))

        g.transport.inject(HOST, sync(5, "five"))
        assertEquals("five", g.state().lastAction)

        g.transport.inject(HOST, sync(3, "three")) // older
        g.transport.inject(HOST, sync(5, "five again")) // duplicate
        g.transport.inject(HOST, sync(0, "zero"))
        assertEquals("five", g.state().lastAction)

        g.transport.inject(GUEST, sync(50, "forged by guest seat"))
        g.transport.inject("stranger", sync(50, "forged by stranger"))
        assertEquals("five", g.state().lastAction)

        val bad = ReversiTestKit.netState(cells = List(63) { null }, lastAction = "short board")
        g.transport.inject(HOST, ReversiNetMessage.StateSync(60, bad))
        val badOwner = ReversiTestKit.netState(cells = List<Int?>(64) { 7 }, lastAction = "bad owner")
        g.transport.inject(HOST, ReversiNetMessage.StateSync(61, badOwner))
        assertEquals("five", g.state().lastAction)

        g.transport.inject(HOST, sync(6, "six")) // a rejected board did not burn its version
        assertEquals("six", g.state().lastAction)
    }

    @Test
    fun `a guest shows legal moves only for the seat to move, derived from the board`() {
        val g = ReversiTestKit.node(1)
        g.transport.inject(HOST, ReversiNetMessage.StateSync(1, ReversiTestKit.netState(current = 0)))
        assertEquals(setOf(19, 26, 37, 44), g.state().legalMoves)
        assertEquals(0, g.state().currentPlayerIndex) // so the UI hides the guest's dots
        g.transport.inject(HOST, ReversiNetMessage.StateSync(2, ReversiTestKit.netState(current = 1)))
        assertEquals(setOf(20, 29, 34, 43), g.state().legalMoves)
        assertEquals(listOf(2, 2), g.state().scores)
    }

    // ---- Passes and board end ----

    @Test
    fun `a pass and the end of the board are broadcast and identical on both engines`() {
        val p = ReversiTestKit.pair()
        ReversiTestKit.loadPosition(p, ReversiTestKit.passPositionSnapshot())
        assertEquals(p.host.state(), p.guest.state())

        p.host.game.placeDisc(2) // seat 1 is left without a move
        val passed = p.host.state()
        assertTrue(passed.justPassed)
        assertEquals(0, passed.currentPlayerIndex)
        assertTrue(passed.lastAction.contains("passes"))
        assertEquals(passed, p.guest.state())

        p.host.game.placeDisc(58)
        val over = p.host.state()
        assertTrue(over.boardOver)
        assertEquals(HOST, over.winnerPlayerId)
        assertEquals(over, p.guest.state())
        assertEquals(mapOf(HOST to 1, GUEST to 0), p.guest.game.sessionWins.value)
    }

    // ---- Reconnect, leave ----

    @Test
    fun `reconnect makes a guest re-ask and the host re-send its current state`() {
        val p = ReversiTestKit.pair(PlayMode.ONLINE)
        hostOpens(p)
        val version = p.host.transport.lastSentVersion()

        val guestMark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        val asked = p.guest.transport.sentSince(guestMark)
        assertEquals(1, asked.size)
        assertTrue(asked[0].message is ReversiNetMessage.RequestState)
        assertEquals(HOST, asked[0].to)

        val hostMark = p.host.transport.mark()
        p.host.transport.fireReconnected()
        val resent = p.host.transport.sentSince(hostMark)
        assertEquals(1, resent.size)
        assertEquals(GUEST, resent[0].to)
        assertEquals(version, resent[0].stateSync.version)
        assertEquals(p.host.state(), p.guest.state())
    }

    @Test
    fun `a guest that missed a broadcast catches up after reconnecting`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val staleGuest = p.guest.state()
        // The guest goes deaf (a dropped connection), so the host's next broadcast is lost.
        val listeners = p.guest.transport.messageListeners.toList()
        p.guest.transport.messageListeners.clear()
        val hostBefore = p.host.state()
        assertEquals(staleGuest, p.guest.state())
        // The guest moves while deaf: the host still gets the request, its reply is what is lost.
        p.guest.game.placeDisc(hostBefore.legalMoves.sorted().first())
        assertTrue(p.host.state() != hostBefore)
        assertEquals(staleGuest, p.guest.state())

        p.guest.transport.messageListeners.addAll(listeners)
        p.guest.transport.fireReconnected()
        assertEquals(p.host.state(), p.guest.state())
    }

    @Test
    fun `the opponent leaving shows a notice, blocks further moves and records no result`() {
        for (mode in modes) {
            val p = ReversiTestKit.pair(mode)
            hostOpens(p)
            val winsBefore = p.host.game.sessionWins.value

            p.host.transport.fireLeft(GUEST)

            assertTrue(p.host.game.opponentLeft.value)
            assertEquals("Opponent disconnected", p.host.state().lastAction)
            assertTrue(p.host.results.isEmpty())
            assertFalse(p.host.game.matchOver.value)
            assertEquals(winsBefore, p.host.game.sessionWins.value)

            val frozen = p.host.state()
            p.host.transport.inject(GUEST, ReversiNetMessage.PlaceDisc(frozen.legalMoves.sorted().first()))
            assertEquals(frozen, p.host.state())
        }
    }

    @Test
    fun `a departure by someone who is not the opponent is ignored`() {
        val p = ReversiTestKit.pair()
        p.host.transport.fireLeft("stranger")
        p.host.transport.fireLeft(HOST)
        assertFalse(p.host.game.opponentLeft.value)
    }

    @Test
    fun `a guest sees the host leave`() {
        val p = ReversiTestKit.pair()
        p.guest.transport.fireLeft(HOST)
        assertTrue(p.guest.game.opponentLeft.value)
        assertEquals("Opponent disconnected", p.guest.state().lastAction)
        assertTrue(p.guest.results.isEmpty())
        val before = p.guest.state()
        val mark = p.guest.transport.mark()
        p.guest.game.placeDisc(0)
        assertEquals(before, p.guest.state())
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
    }

    // ---- Play Again ----

    private fun finishedPair(): ReversiPair {
        val p = ReversiTestKit.pair()
        ReversiTestKit.loadPosition(p, ReversiTestKit.passPositionSnapshot(nextStarting = 1))
        p.host.game.placeDisc(2)
        p.host.game.placeDisc(58)
        assertTrue(p.host.state().boardOver)
        assertTrue(p.guest.state().boardOver)
        return p
    }

    @Test
    fun `Play Again from the guest re-deals with the next opener and keeps the session tally`() {
        val p = finishedPair()
        val mark = p.guest.transport.mark()

        p.guest.game.playAgain()

        val asked = p.guest.transport.sentSince(mark)
        assertEquals(ReversiNetMessage.RequestPlayAgain, asked.single().message)
        val s = p.host.state()
        assertFalse(s.boardOver)
        assertEquals(1, s.currentPlayerIndex) // the opener alternated to the guest seat
        assertEquals(1, s.cells[28])
        assertEquals(1, s.cells[35])
        assertEquals(mapOf(HOST to 1, GUEST to 0), p.host.game.sessionWins.value)
        assertEquals(s, p.guest.state())

        // A duplicate request mid-board changes nothing.
        val version = p.host.transport.lastSentVersion()
        p.host.transport.inject(GUEST, ReversiNetMessage.RequestPlayAgain)
        assertEquals(s, p.host.state())
        assertEquals(version, p.host.transport.lastSentVersion())
    }

    @Test
    fun `Play Again from the host re-deals and broadcasts`() {
        val p = finishedPair()
        p.host.game.playAgain()
        assertFalse(p.host.state().boardOver)
        assertEquals(1, p.host.state().currentPlayerIndex)
        assertEquals(p.host.state(), p.guest.state())
    }

    @Test
    fun `Play Again is ignored mid-board, from a spoofed sender and after the opponent left`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        val mid = p.host.state()
        val gMark = p.guest.transport.mark()
        p.guest.game.playAgain() // board not over: nothing is even sent
        p.host.game.playAgain()
        p.host.transport.inject(GUEST, ReversiNetMessage.RequestPlayAgain)
        assertTrue(p.guest.transport.sentSince(gMark).isEmpty())
        assertEquals(mid, p.host.state())

        val done = finishedPair()
        val over = done.host.state()
        done.host.transport.inject("stranger", ReversiNetMessage.RequestPlayAgain)
        done.host.transport.inject(HOST, ReversiNetMessage.RequestPlayAgain)
        assertEquals(over, done.host.state())

        done.host.transport.fireLeft(GUEST)
        val afterLeave = done.host.state()
        done.host.transport.inject(GUEST, ReversiNetMessage.RequestPlayAgain)
        done.host.game.playAgain()
        assertEquals(afterLeave, done.host.state())
        assertTrue(done.host.state().boardOver)
    }

    // ---- Whole game ----

    @Test
    fun `a scripted game stays identical on both engines and ends with the same winner`() {
        for (mode in modes) {
            val p = ReversiTestKit.pair(mode)
            var guard = 0
            while (!p.host.state().boardOver && guard++ < 200) {
                val s = p.host.state()
                val mover = if (s.currentPlayerIndex == 0) p.host else p.guest
                mover.game.placeDisc(s.legalMoves.sorted().first())
                assertEquals("after ply $guard", p.host.state(), p.guest.state())
                assertEquals(p.host.game.sessionWins.value, p.guest.game.sessionWins.value)
                assertEquals(p.host.game.sessionDraws.value, p.guest.game.sessionDraws.value)
            }
            assertTrue("the board must finish", p.host.state().boardOver)
            assertTrue(p.guest.state().boardOver)
            assertEquals(p.host.state().winnerPlayerId, p.guest.state().winnerPlayerId)
            assertTrue(p.hub.senderLies.isEmpty())
        }
    }

    // ---- Disabled features ----

    @Test
    fun `networked play is not resumable and has no undo or hint`() {
        val p = ReversiTestKit.pair()
        hostOpens(p)
        p.guest.game.placeDisc(p.host.state().legalMoves.sorted().first())

        assertNull(p.host.game.snapshot())
        assertNull(p.guest.game.snapshot())
        assertFalse(p.host.game.canUndo)
        assertFalse(p.guest.game.canUndo)
        val before = p.host.state()
        p.host.game.undo()
        assertEquals(before, p.host.state())
        assertNull(p.host.game.hint())
        assertNull(p.guest.game.hint())
        assertNull(p.host.game.hintMove.value)
    }

    @Test
    fun `local modes are unchanged by the networking code`() {
        val game = ReversiGame()
        game.init(
            com.gamesuite.core.GameContext(
                PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                ReversiTestKit.players,
                localPlayerIndex = 0,
                transport = ReversiFakeTransport()
            )
        )
        game.startMatch()
        assertNotNull(game.snapshot())
        game.placeDisc(game.state.value!!.legalMoves.sorted().first())
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        assertNotNull(game.hint())
    }
}
