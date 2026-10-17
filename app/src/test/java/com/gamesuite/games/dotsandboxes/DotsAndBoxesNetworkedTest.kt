package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-authoritative Dots and Boxes over a fake transport: host = seat 0 (p0), guest = seat 1 (p1). */
class DotsAndBoxesNetworkedTest {

    private fun hostWithGuestTurn(): DotsNode {
        // Host opens (seat 0), draws h(0,0): the turn passes to the guest (seat 1).
        val host = DotsTestKit.node(0, start = true)
        host.game.drawHorizontalEdge(0, 0)
        assertEquals(1, host.game.state.value!!.currentPlayerIndex)
        return host
    }

    @Test
    fun supportsBothNetworkedModesAndKeepsLocalOnes() {
        val modes = DotsAndBoxesGame().supportedModes
        assertTrue(PlayMode.LOCAL_AD_HOC in modes)
        assertTrue(PlayMode.ONLINE in modes)
        assertTrue(PlayMode.SINGLE_PLAYER_VS_BOT in modes)
        assertTrue(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY in modes)
    }

    @Test
    fun hostDealsAndBroadcastsTheOpeningBoardToTheGuestOnly() {
        val host = DotsTestKit.node(0, start = true)
        assertEquals(1, host.transport.sent.size)
        val sent = host.transport.sent.single()
        assertEquals("p0", sent.from)
        assertEquals("p1", sent.to)
        assertEquals(1, sent.stateSync.version)
        assertEquals(0, host.game.state.value!!.currentPlayerIndex) // the host opens the first board
    }

    @Test
    fun validGuestEdgeIsAppliedAndBroadcastWithANewerVersion() {
        val host = hostWithGuestTurn()
        val mark = host.transport.mark()
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(horizontal = true, row = 1, col = 0))
        val s = host.game.state.value!!
        assertTrue(s.horizontalEdges[1 * 5 + 0])
        assertEquals(0, s.currentPlayerIndex) // no box: the turn passes back to the host
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out.single().to)
        assertEquals(3, out.single().stateSync.version) // deal = 1, host edge = 2, guest edge = 3
    }

    @Test
    fun guestBoxCompletingEdgeKeepsTheTurnAndScores() {
        val host = hostWithGuestTurn()
        // Box (0,0): top h(0,0) done. Guest bottom, host left, then the guest closes the right side.
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 1, 0))
        host.game.drawVerticalEdge(0, 0)
        val mark = host.transport.mark()
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(false, 0, 1))
        val s = host.game.state.value!!
        assertEquals(1, s.boxOwner[0])
        assertEquals(listOf(0, 1), s.scores)
        assertEquals(1, s.currentPlayerIndex) // go-again: the guest moves again
        assertEquals(1, host.transport.sentSince(mark).size)
        // ...and the repeated turn is accepted from the same seat.
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 5, 4))
        assertTrue(host.game.state.value!!.horizontalEdges[5 * 5 + 4])
    }

    @Test
    fun alreadyDrawnEdgeIsIgnoredWithoutABroadcast() {
        val host = hostWithGuestTurn()
        val before = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 0, 0)) // the host's own line
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun outOfTurnGuestRequestIsIgnored() {
        val host = DotsTestKit.node(0, start = true) // the host's turn
        val before = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 2, 2))
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun spoofedUnknownOrHostSenderAndOutOfRangeEdgesAreIgnored() {
        val host = hostWithGuestTurn() // the guest's turn
        val before = host.game.state.value
        val mark = host.transport.mark()
        host.transport.inject("p9", DotsAndBoxesNetMessage.DrawEdge(true, 2, 2)) // not in the roster
        host.transport.inject("p0", DotsAndBoxesNetMessage.DrawEdge(true, 2, 2)) // the host cannot act as a guest
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 9, 9)) // out of range
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(false, -1, 0))
        host.transport.inject("p1", "not json".toByteArray())
        assertSame(before, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun hostIgnoresStateSyncAndGuestIgnoresRequests() {
        val (host, guest, _) = DotsTestKit.pair()
        val hostState = host.game.state.value
        host.transport.inject("p1", DotsAndBoxesNetMessage.StateSync(99, "{}"))
        assertSame(hostState, host.game.state.value)
        val mark = guest.transport.mark()
        guest.transport.inject("p0", DotsAndBoxesNetMessage.RequestState)
        guest.transport.inject("p0", DotsAndBoxesNetMessage.DrawEdge(true, 0, 0))
        assertTrue(guest.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun guestTapOnItsTurnOnlyAsksTheHost() {
        val host = DotsTestKit.node(0, start = true)
        val hub = DotsHub() // unwired guest: nothing delivers its request, so its engine must stay put
        val guest = DotsTestKit.node(1, transport = hub.endpoint("p1"))
        // Give the guest the host's board.
        guest.transport.inject("p0", host.transport.sent.single().message)
        val before = guest.game.state.value
        assertNotNull(before)
        assertEquals(0, before!!.currentPlayerIndex)
        // Host's turn: the guest's tap is dropped locally.
        val m0 = guest.transport.mark()
        guest.game.drawHorizontalEdge(3, 3)
        assertTrue(guest.transport.sentSince(m0).isEmpty())
        // Pass the turn to the guest, then tap.
        host.game.drawHorizontalEdge(0, 0)
        guest.transport.inject("p0", host.transport.sent.last().message)
        val turnState = guest.game.state.value
        assertEquals(1, turnState!!.currentPlayerIndex)
        val m1 = guest.transport.mark()
        guest.game.drawHorizontalEdge(3, 3)
        assertSame(turnState, guest.game.state.value) // never mutated by the tap
        val req = guest.transport.sentSince(m1).single()
        assertEquals("p1", req.from)
        assertEquals("p0", req.to)
        assertEquals(DotsAndBoxesNetMessage.DrawEdge(true, 3, 3), req.message)
    }

    @Test
    fun guestAppliesNewerStatesAndIgnoresOlderOrForgedOnes() {
        val host = DotsTestKit.node(0, start = true)
        host.game.drawHorizontalEdge(0, 0) // version 2
        val v1 = host.transport.sent[0].message
        val v2 = host.transport.sent[1].message
        val guest = DotsTestKit.node(1)
        guest.transport.inject("p0", v2)
        val applied = guest.game.state.value
        assertEquals(1, applied!!.currentPlayerIndex)
        assertTrue(applied.horizontalEdges[0])
        guest.transport.inject("p0", v1) // older: ignored
        assertSame(applied, guest.game.state.value)
        guest.transport.inject("p0", v2) // same version: ignored
        assertSame(applied, guest.game.state.value)
        // Newer but from the guest's own seat: a forgery.
        guest.transport.inject("p1", DotsAndBoxesNetMessage.StateSync(50, (v1 as DotsAndBoxesNetMessage.StateSync).snapshot))
        assertSame(applied, guest.game.state.value)
        // Newer from the host but undecodable: ignored and the version is not consumed.
        guest.transport.inject("p0", DotsAndBoxesNetMessage.StateSync(60, "garbage"))
        assertSame(applied, guest.game.state.value)
        host.transport.inject("p1", DotsAndBoxesNetMessage.DrawEdge(true, 1, 0)) // version 3
        guest.transport.inject("p0", host.transport.sent.last().message)
        assertTrue(guest.game.state.value!!.horizontalEdges[5])
    }

    @Test
    fun guestAsksForStateAtStartAndHostAnswersOnlyThatSeat() {
        val hub = DotsHub()
        val host = DotsTestKit.node(0, transport = hub.endpoint("p0"), start = true) // dealt before the guest listens
        val guest = DotsTestKit.node(1, transport = hub.endpoint("p1"))
        val ask = guest.transport.sent.single()
        assertEquals("p1", ask.from)
        assertEquals("p0", ask.to)
        assertEquals(DotsAndBoxesNetMessage.RequestState, ask.message)
        assertNotNull(guest.game.state.value) // the hub delivered the host's reply
        assertEquals(DotsTestKit.essence(host.game.state.value), DotsTestKit.essence(guest.game.state.value))
        // A stranger's request gets no answer.
        val mark = host.transport.mark()
        host.transport.inject("p9", DotsAndBoxesNetMessage.RequestState)
        assertTrue(host.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun guestStartMatchNeverDealsItsOwnBoard() {
        val guest = DotsTestKit.node(1, start = true)
        assertNull(guest.game.state.value)
    }

    @Test
    fun reconnectRebroadcastsFromHostAndReRequestsFromGuest() {
        val (host, guest, _) = DotsTestKit.pair()
        val hm = host.transport.mark()
        host.transport.fireReconnected()
        val re = host.transport.sentSince(hm).single()
        assertEquals("p1", re.to)
        assertEquals(1, re.stateSync.version)
        val gm = guest.transport.mark()
        guest.transport.fireReconnected()
        assertEquals(DotsAndBoxesNetMessage.RequestState, guest.transport.sentSince(gm).single().message)
    }

    @Test
    fun opponentLeavingFreezesInputWithoutRecordingAResult() {
        val (host, guest, _) = DotsTestKit.pair()
        val results = mutableListOf<GameResult>()
        host.game.setOnMatchEnd { results += it }
        guest.game.setOnMatchEnd { results += it }
        host.transport.fireLeft("p9") // not the other seat: ignored
        assertFalse(host.game.opponentLeft.value)

        host.transport.fireLeft("p1")
        assertTrue(host.game.opponentLeft.value)
        assertEquals("Opponent disconnected", host.game.state.value!!.lastAction)
        val frozen = host.game.state.value
        host.game.drawHorizontalEdge(0, 0)
        assertSame(frozen, host.game.state.value)

        guest.transport.fireLeft("p0")
        assertTrue(guest.game.opponentLeft.value)
        assertEquals("Opponent disconnected", guest.game.state.value!!.lastAction)
        assertTrue(results.isEmpty()) // nothing recorded: the player chooses how to leave
    }

    @Test
    fun scriptedGameEndsIdenticallyOnBothEnginesAndTheGuestHearsEveryClaim() {
        val (host, guest, hub) = DotsTestKit.pair()
        DotsTestKit.playOut(host, guest)
        val hs = host.game.state.value!!
        val gs = guest.game.state.value!!
        assertTrue(hs.boardOver)
        assertEquals(25, hs.scores.sum())
        assertEquals(DotsTestKit.essence(hs), DotsTestKit.essence(gs))
        assertEquals(host.game.sessionWins.value, guest.game.sessionWins.value)
        assertEquals(host.game.sessionDraws.value, guest.game.sessionDraws.value)
        assertNotNull(gs.lastBoxCompleted)
        assertEquals(hs.lastBoxCompleted, gs.lastBoxCompleted)
        assertTrue(hub.senderLies.isEmpty())
    }

    @Test
    fun playAgainFromEitherSeatRedealsWithTheOpenerAlternatingOnlyWhenTheBoardIsOver() {
        val (host, guest, _) = DotsTestKit.pair()
        // Not over yet: a Play Again from either side changes nothing.
        val mid = host.game.state.value
        guest.game.playAgain()
        host.game.playAgain()
        assertSame(mid, host.game.state.value)

        DotsTestKit.playOut(host, guest)
        val versionBefore = host.transport.sent.last().stateSync.version

        // The guest asks: the host re-deals (the first board was opened by seat 0, so seat 1 opens now).
        guest.game.playAgain()
        val second = host.game.state.value!!
        assertFalse(second.boardOver)
        assertTrue(second.horizontalEdges.none { it } && second.verticalEdges.none { it })
        assertEquals(1, second.currentPlayerIndex)
        assertEquals(DotsTestKit.essence(second), DotsTestKit.essence(guest.game.state.value))
        assertTrue(host.transport.sent.last().stateSync.version > versionBefore)
        val finished = host.game.sessionWins.value.values.sum() + host.game.sessionDraws.value
        assertEquals(1, finished) // the session tally survived the re-deal

        // A duplicate request while the new board is in play is ignored.
        val mark = host.transport.mark()
        host.transport.inject("p1", DotsAndBoxesNetMessage.PlayAgain)
        assertSame(second, host.game.state.value)
        assertTrue(host.transport.sentSince(mark).isEmpty())

        // The host can ask too, once that board is over; seat 0 opens the third.
        DotsTestKit.playOut(host, guest)
        host.game.playAgain()
        assertEquals(0, host.game.state.value!!.currentPlayerIndex)
        assertEquals(DotsTestKit.essence(host.game.state.value), DotsTestKit.essence(guest.game.state.value))
    }

    @Test
    fun networkedModesAreNotResumableAndHaveNoUndoOrHint() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            val (host, guest, _) = DotsTestKit.pair(mode)
            host.game.drawHorizontalEdge(0, 0)
            assertNull(host.game.snapshot())
            assertNull(guest.game.snapshot())
            assertFalse(host.game.canUndo)
            assertNull(host.game.hint())
            assertNull(host.game.hintEdge.value)
            val before = host.game.state.value
            host.game.undo()
            assertSame(before, host.game.state.value)
            assertFalse(host.game.restore("{}"))
            assertEquals(1, guest.game.state.value!!.currentPlayerIndex)
        }
    }

    @Test
    fun onlineModeBehavesLikeAdHoc() {
        val (host, guest, _) = DotsTestKit.pair(PlayMode.ONLINE)
        guest.game.drawHorizontalEdge(0, 0) // host's turn: dropped
        assertTrue(host.game.state.value!!.horizontalEdges.none { it })
        host.game.drawHorizontalEdge(0, 0)
        guest.game.drawHorizontalEdge(1, 0) // guest's turn: asked, applied by the host, synced back
        assertTrue(host.game.state.value!!.horizontalEdges[5])
        assertTrue(guest.game.state.value!!.horizontalEdges[5])
        assertEquals(0, guest.game.state.value!!.currentPlayerIndex)
    }
}
