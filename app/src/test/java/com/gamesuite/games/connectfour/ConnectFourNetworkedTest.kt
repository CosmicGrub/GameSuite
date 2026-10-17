package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-authoritative Connect Four over a (fake) MultiplayerTransport. Host = seat 0 "p0", guest = seat 1 "p1". */
class ConnectFourNetworkedTest {

    private fun idx(row: Int, col: Int) = row * 7 + col

    // --- modes -------------------------------------------------------------------------------

    @Test
    fun advertisesNetworkedModes() {
        val modes = ConnectFourGame().supportedModes
        assertTrue(PlayMode.LOCAL_AD_HOC in modes)
        assertTrue(PlayMode.ONLINE in modes)
        assertTrue(PlayMode.SINGLE_PLAYER_VS_BOT in modes)
    }

    @Test
    fun networkedGameIsNotResumable() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            val host = C4TestKit.node(0, mode, start = true)
            assertNull(host.game.snapshot())
            assertFalse(host.game.restore("{}"))
            assertNull(host.game.hint())
            assertFalse(host.game.canUndo)
        }
    }

    // --- host --------------------------------------------------------------------------------

    @Test
    fun hostDealsAndBroadcastsToGuestSeat() {
        val host = C4TestKit.node(0, start = true)
        val syncs = host.transport.sent
        assertEquals(1, syncs.size)
        assertEquals("p0", syncs[0].from)
        assertEquals("p1", syncs[0].to)
        assertEquals(1, syncs[0].stateSync.version)
        assertEquals(0, syncs[0].stateSync.state.currentPlayerIndex) // host opens board 1
    }

    @Test
    fun hostAcceptsValidGuestDropAndBroadcasts() {
        val host = C4TestKit.node(0, start = true)
        host.game.dropDisc(3) // host's own tap, seat 0 on turn
        val mark = host.transport.mark()
        host.transport.inject("p1", ConnectFourNetMessage.DropRequest(3))
        val s = host.game.state.value!!
        assertEquals(1, s.cells[idx(4, 3)])
        assertEquals(0, s.cells[idx(5, 3)])
        assertEquals(0, s.currentPlayerIndex)
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].to)
        assertEquals(s.cells, out[0].stateSync.state.cells)
        assertEquals(3, out[0].stateSync.version) // deal, host drop, guest drop
    }

    @Test
    fun hostRejectsOutOfTurnRequest() {
        val host = C4TestKit.node(0, start = true) // host on turn
        val mark = host.transport.mark()
        host.transport.inject("p1", ConnectFourNetMessage.DropRequest(2))
        assertTrue(host.game.state.value!!.cells.all { it == null })
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsFullColumn() {
        val host = C4TestKit.node(0, start = true)
        repeat(3) {
            host.game.dropDisc(0)
            host.transport.inject("p1", ConnectFourNetMessage.DropRequest(0))
        }
        // Column 0 now holds six alternating discs; hand the turn to the guest and ask again.
        host.game.dropDisc(1)
        val before = host.game.state.value!!
        val mark = host.transport.mark()
        host.transport.inject("p1", ConnectFourNetMessage.DropRequest(0))
        assertEquals(before, host.game.state.value)
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsOutOfRangeColumn() {
        val host = C4TestKit.node(0, start = true)
        host.game.dropDisc(3)
        val before = host.game.state.value!!
        host.transport.inject("p1", ConnectFourNetMessage.DropRequest(-1))
        host.transport.inject("p1", ConnectFourNetMessage.DropRequest(7))
        assertEquals(before, host.game.state.value)
    }

    @Test
    fun hostRejectsSpoofedAndUnknownSenders() {
        val host = C4TestKit.node(0, start = true)
        host.game.dropDisc(3) // now seat 1 is on turn
        val before = host.game.state.value!!
        val mark = host.transport.mark()
        host.transport.inject("p0", ConnectFourNetMessage.DropRequest(4))       // the host's own id
        host.transport.inject("intruder", ConnectFourNetMessage.DropRequest(4)) // not in the roster
        host.transport.inject("p1", "not json".toByteArray())                   // garbage
        assertEquals(before, host.game.state.value)
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsDropAfterBoardOver() {
        val pair = C4TestKit.pair()
        // Host wins on the bottom row.
        for (c in listOf(0, 0, 1, 1, 2, 2, 3)) pair.move(c)
        assertTrue(pair.host.game.state.value!!.boardOver)
        val before = pair.host.game.state.value!!
        pair.host.transport.inject("p1", ConnectFourNetMessage.DropRequest(5))
        assertEquals(before, pair.host.game.state.value)
    }

    @Test
    fun hostTapOnGuestsTurnIsIgnored() {
        val host = C4TestKit.node(0, start = true)
        host.game.dropDisc(3) // seat 1 on turn now
        val before = host.game.state.value!!
        host.game.dropDisc(4)
        assertEquals(before, host.game.state.value)
    }

    // --- guest -------------------------------------------------------------------------------

    @Test
    fun guestAsksHostForStateOnInit() {
        val guest = C4TestKit.node(1)
        assertEquals(1, guest.transport.sent.size)
        assertEquals("p1", guest.transport.sent[0].from)
        assertEquals("p0", guest.transport.sent[0].to)
        assertTrue(guest.transport.sent[0].message is ConnectFourNetMessage.RequestState)
        assertNull(guest.game.state.value) // nothing until the host answers
        guest.game.startMatch()            // a guest never deals
        assertNull(guest.game.state.value)
    }

    @Test
    fun guestAppliesNewerAndIgnoresOlderOrEqualVersions() {
        val guest = C4TestKit.node(1)
        val b2 = C4TestKit.net(cells = C4TestKit.cellsWith(idx(5, 3) to 0), current = 1, lastAction = "v2")
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(2, b2))
        assertEquals("v2", guest.game.state.value!!.lastAction)

        val stale = C4TestKit.net(cells = C4TestKit.cellsWith(idx(5, 0) to 0), current = 1, lastAction = "v1")
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, stale))
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(2, stale))
        assertEquals("v2", guest.game.state.value!!.lastAction)

        val b3 = C4TestKit.net(cells = C4TestKit.cellsWith(idx(5, 3) to 0, idx(4, 3) to 1), current = 0, lastAction = "v3")
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(3, b3))
        assertEquals("v3", guest.game.state.value!!.lastAction)
        assertEquals(0, guest.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun guestIgnoresStateFromNonHostAndMalformedState() {
        val guest = C4TestKit.node(1)
        guest.transport.inject("p1", ConnectFourNetMessage.StateSync(9, C4TestKit.net()))
        guest.transport.inject("intruder", ConnectFourNetMessage.StateSync(9, C4TestKit.net()))
        assertNull(guest.game.state.value)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(cells = List(5) { null })))
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(2, C4TestKit.net(current = 7)))
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(3, C4TestKit.net(cells = List(42) { 5 })))
        assertNull(guest.game.state.value)
        // A good one still lands afterwards (rejected ones did not burn their version).
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(lastAction = "ok")))
        assertEquals("ok", guest.game.state.value!!.lastAction)
    }

    @Test
    fun guestTapSendsRequestAndNeverMutatesItself() {
        val guest = C4TestKit.node(1)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(current = 1)))
        val before = guest.game.state.value!!
        val mark = guest.transport.mark()
        guest.game.dropDisc(2)
        assertEquals(before, guest.game.state.value)
        val out = guest.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].from)
        assertEquals("p0", out[0].to)
        assertEquals(ConnectFourNetMessage.DropRequest(2), out[0].message)
    }

    @Test
    fun guestTapOutOfTurnSendsNothing() {
        val guest = C4TestKit.node(1)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(current = 0)))
        val mark = guest.transport.mark()
        guest.game.dropDisc(2)
        assertEquals(0, guest.transport.sentSince(mark).size)
    }

    @Test
    fun guestHasNoUndoOrHint() {
        val guest = C4TestKit.node(1)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(current = 1)))
        assertNull(guest.game.hint())
        assertFalse(guest.game.canUndo)
        guest.game.undo()
        assertTrue(guest.game.state.value!!.cells.all { it == null })
    }

    // --- RequestState / reconnect -------------------------------------------------------------

    @Test
    fun hostAnswersRequestStateDirectly() {
        val host = C4TestKit.node(0, start = true)
        host.game.dropDisc(3)
        val mark = host.transport.mark()
        host.transport.inject("p1", ConnectFourNetMessage.RequestState)
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].to)
        assertEquals(host.game.state.value!!.cells, out[0].stateSync.state.cells)
        // Not for strangers or for the host's own id.
        host.transport.inject("intruder", ConnectFourNetMessage.RequestState)
        host.transport.inject("p0", ConnectFourNetMessage.RequestState)
        assertEquals(1, host.transport.sentSince(mark).size)
    }

    @Test
    fun lateGuestCatchesUpThroughRequestState() {
        val pair = C4TestKit.pair()
        val g = pair.guest.game.state.value
        assertNotNull(g)
        assertEquals(pair.host.game.state.value!!.cells, g!!.cells)
        assertEquals(0, g.currentPlayerIndex)
    }

    @Test
    fun reconnectGuestAsksAgainAndHostResends() {
        val host = C4TestKit.node(0, start = true)
        val hmark = host.transport.mark()
        host.transport.fireReconnected()
        val resent = host.transport.sentSince(hmark)
        assertEquals(1, resent.size)
        assertEquals("p1", resent[0].to)
        assertEquals(2, resent[0].stateSync.version)

        val guest = C4TestKit.node(1)
        val gmark = guest.transport.mark()
        guest.transport.fireReconnected()
        val asked = guest.transport.sentSince(gmark)
        assertEquals(1, asked.size)
        assertTrue(asked[0].message is ConnectFourNetMessage.RequestState)
    }

    // --- leaving ------------------------------------------------------------------------------

    @Test
    fun opponentLeavingIsShownAndBlocksPlay() {
        val host = C4TestKit.node(0, start = true)
        host.transport.fireLeft("someone-else")
        assertFalse(host.game.opponentLeft.value)
        host.transport.fireLeft("p0") // ourselves: not an opponent
        assertFalse(host.game.opponentLeft.value)

        host.transport.fireLeft("p1")
        assertTrue(host.game.opponentLeft.value)
        assertEquals("Opponent disconnected", host.game.state.value!!.lastAction)
        val before = host.game.state.value!!
        host.game.dropDisc(3)
        assertEquals(before, host.game.state.value)
    }

    @Test
    fun guestSeesHostLeaveAndMayLeaveWithoutARecordedLoss() {
        val pair = C4TestKit.pair()
        pair.guest.transport.fireLeft("p0")
        assertTrue(pair.guest.game.opponentLeft.value)
        assertEquals("Opponent disconnected", pair.guest.game.state.value!!.lastAction)
        pair.guest.game.leaveSession()
        assertEquals(1, pair.guest.results.size)
        assertTrue(pair.guest.results[0].wasAborted)
        assertTrue(pair.guest.results[0].scores.isEmpty())
    }

    @Test
    fun hostLeavesAfterGuestLeftWithoutARecordedLoss() {
        val host = C4TestKit.node(0, start = true)
        host.transport.fireLeft("p1")
        host.game.leaveSession()
        assertEquals(1, host.results.size)
        assertTrue(host.results[0].wasAborted)
    }

    @Test
    fun staleSyncAfterLeaveDoesNotEraseDisconnectedNotice() {
        val guest = C4TestKit.node(1)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, C4TestKit.net(current = 1)))
        guest.transport.fireLeft("p0")
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(2, C4TestKit.net(current = 1, lastAction = "late")))
        assertEquals("Opponent disconnected", guest.game.state.value!!.lastAction)
        val mark = guest.transport.mark()
        guest.game.dropDisc(1)
        assertEquals(0, guest.transport.sentSince(mark).size)
    }

    // --- Play Again ---------------------------------------------------------------------------

    private fun hostWinsBottomRow(pair: C4TestKit.Pair2) {
        for (c in listOf(0, 0, 1, 1, 2, 2, 3)) pair.move(c)
    }

    @Test
    fun guestPlayAgainRedealsWithAlternatingOpener() {
        val pair = C4TestKit.pair()
        hostWinsBottomRow(pair)
        assertTrue(pair.guest.game.state.value!!.boardOver)
        assertEquals(1, pair.guest.game.sessionWins.value["p0"])

        pair.guest.game.playAgain()
        for (node in listOf(pair.host, pair.guest)) {
            val s = node.game.state.value!!
            assertTrue(s.cells.all { it == null })
            assertFalse(s.boardOver)
            assertEquals(1, s.currentPlayerIndex) // seat 1 opens board 2
            assertEquals(1, node.game.sessionWins.value["p0"]) // tally kept
        }
        assertTrue(pair.hub.senderLies.isEmpty())
    }

    @Test
    fun hostPlayAgainRedealsAndGuestFollows() {
        val pair = C4TestKit.pair()
        hostWinsBottomRow(pair)
        pair.host.game.playAgain()
        assertTrue(pair.guest.game.state.value!!.cells.all { it == null })
        assertEquals(1, pair.guest.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun playAgainWhileBoardInProgressOrRepeatedIsIgnored() {
        val pair = C4TestKit.pair()
        pair.host.game.playAgain() // board not over: must not wipe it
        pair.move(3)
        val mid = pair.host.game.state.value!!
        pair.host.transport.inject("p1", ConnectFourNetMessage.PlayAgainRequest)
        pair.host.transport.inject("intruder", ConnectFourNetMessage.PlayAgainRequest)
        assertEquals(mid, pair.host.game.state.value)

        // Finish a board, re-deal once, then a second (stale) request must not re-deal again.
        val p2 = C4TestKit.pair()
        hostWinsBottomRow(p2)
        p2.guest.game.playAgain()
        p2.move(4) // seat 1 opens board 2
        val afterMove = p2.host.game.state.value!!
        p2.host.transport.inject("p1", ConnectFourNetMessage.PlayAgainRequest)
        assertEquals(afterMove, p2.host.game.state.value)
    }

    // --- whole game ---------------------------------------------------------------------------

    @Test
    fun scriptedGameEndsIdenticallyOnHostGuestAndLocalEngine() {
        val pair = C4TestKit.pair()
        val local = ConnectFourGame()
        local.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = C4TestKit.roster,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        local.startMatch()

        // Host (red) takes the bottom row 1-4 on the seventh drop; guest stacks on top each time.
        val script = listOf(3, 3, 2, 2, 4, 4, 1)
        for (col in script) {
            val before = pair.host.game.state.value!!
            if (before.boardOver) break
            pair.move(col)
            local.dropDisc(col)
            val h = pair.host.game.state.value!!
            val g = pair.guest.game.state.value!!
            assertEquals(h.cells, g.cells)
            assertEquals(h.currentPlayerIndex, g.currentPlayerIndex)
            assertEquals(local.state.value!!.cells, h.cells)
        }
        val h = pair.host.game.state.value!!
        val g = pair.guest.game.state.value!!
        val l = local.state.value!!
        assertEquals(h.boardOver, g.boardOver)
        assertEquals(h.winnerPlayerId, g.winnerPlayerId)
        assertEquals(h.winningLine, g.winningLine)
        assertEquals(l.winnerPlayerId, h.winnerPlayerId)
        assertEquals(l.winningLine, h.winningLine)
        assertEquals(pair.host.game.sessionWins.value, pair.guest.game.sessionWins.value)
        assertEquals(pair.host.game.sessionDraws.value, pair.guest.game.sessionDraws.value)
        assertTrue(pair.hub.senderLies.isEmpty())
    }

    @Test
    fun bothSidesRecordTheSameSessionOnLeave() {
        val pair = C4TestKit.pair()
        hostWinsBottomRow(pair)
        pair.host.game.leaveSession()
        pair.guest.game.leaveSession()
        val hostScores = pair.host.results.single().scores.associate { it.playerId to it.isWinner }
        val guestScores = pair.guest.results.single().scores.associate { it.playerId to it.isWinner }
        assertEquals(mapOf("p0" to true, "p1" to false), hostScores)
        assertEquals(hostScores, guestScores)
    }
}
