package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.tictactoe.TttTestKit.GUEST
import com.gamesuite.games.tictactoe.TttTestKit.HOST
import com.gamesuite.games.tictactoe.TttTestKit.cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's side of the Tic-Tac-Toe protocol: it validates every request and owns the engine. */
class TicTacToeNetworkedHostTest {

    private fun host(mode: PlayMode = PlayMode.LOCAL_AD_HOC) = TttTestKit.node(0, mode)

    private fun TttNode.board() = game.board.value.toList()

    /** Leaves a won round: X (host) on 0,1,2 and O on 3,4; returns with round over. */
    private fun TttNode.hostWinsTopRow() {
        game.cellClicked(0)
        transport.inject(GUEST, cell(3))
        game.cellClicked(1)
        transport.inject(GUEST, cell(4))
        game.cellClicked(2)
    }

    @Test
    fun startMatchBroadcastsTheOpeningStateAsVersionOne() {
        val h = host()
        assertEquals(1, h.transport.sent.size)
        val sent = h.transport.sent[0]
        assertEquals(HOST, sent.from)
        assertNull("a broadcast", sent.to)
        assertEquals(1, sent.stateSync.version)
        assertEquals(List(9) { 0 }, sent.stateSync.state.board)
        assertEquals(1, sent.stateSync.state.currentPlayer)
    }

    @Test
    fun aGuestMoveOnItsTurnIsAppliedAndBroadcast() {
        val h = host()
        h.game.cellClicked(4) // X
        val mark = h.transport.mark()

        h.transport.inject(GUEST, cell(0))

        assertEquals(2, h.board()[0])
        assertEquals(1, h.game.currentPlayer.value)
        val out = h.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(2, out[0].stateSync.state.board[0])
    }

    @Test
    fun aGuestMoveOutOfTurnIsIgnoredSilently() {
        val h = host() // X to move
        val mark = h.transport.mark()

        h.transport.inject(GUEST, cell(0))

        assertEquals(List(9) { 0 }, h.board())
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun theHostCannotPlayTheGuestsTurnWithItsOwnTap() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.game.cellClicked(0) // guest's turn

        assertEquals(0, h.board()[0])
        assertEquals(2, h.game.currentPlayer.value)
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun spoofedAndUnknownSendersAreIgnored() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.inject("mallory", cell(0))          // not in the roster
        h.transport.inject(HOST, cell(0))               // the host's own seat never arrives over the wire
        h.transport.inject("", cell(1))

        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), h.board())
        assertEquals(2, h.game.currentPlayer.value)
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun occupiedAndOutOfRangeCellsAreIgnored() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.inject(GUEST, cell(4))
        h.transport.inject(GUEST, cell(9))
        h.transport.inject(GUEST, cell(-1))

        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), h.board())
        assertEquals(2, h.game.currentPlayer.value)
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun malformedPayloadsAreIgnored() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.inject(GUEST, "not json".encodeToByteArray())
        h.transport.inject(GUEST, ByteArray(0))
        h.transport.inject(GUEST, """{"type":"nope"}""".encodeToByteArray())

        assertEquals(2, h.game.currentPlayer.value)
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun aStateSyncSentToTheHostIsIgnored() {
        val h = host()
        val before = h.board()

        h.transport.inject(GUEST, TicTacToeNetMessage.StateSync(99, TttTestKit.state(board = List(9) { 1 })))

        assertEquals(before, h.board())
    }

    @Test
    fun movesAfterTheRoundEndsAreIgnored() {
        val h = host()
        h.hostWinsTopRow()
        assertTrue(h.game.roundOver.value)
        assertEquals(1, h.game.scoreP1.value)
        val mark = h.transport.mark()

        h.transport.inject(GUEST, cell(5))
        h.transport.inject(GUEST, cell(6))
        h.game.cellClicked(7)

        assertEquals(listOf(1, 1, 1, 2, 2, 0, 0, 0, 0), h.board())
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun theWinningMoveBroadcastsTheFinishedRound() {
        val h = host()
        h.hostWinsTopRow()
        val last = h.transport.sent.last().stateSync.state
        assertTrue(last.roundOver)
        assertEquals(listOf(0, 1, 2), last.winningLine)
        assertEquals(1, last.scoreP1)
    }

    @Test
    fun playAgainMidRoundIsIgnored() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.inject(GUEST, TttTestKit.playAgainIntent)
        h.game.playAgain()

        assertEquals(1, h.game.roundNumber.value)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), h.board())
        assertEquals(0, h.transport.sentSince(mark).size)
    }

    @Test
    fun aGuestPlayAgainRequestRedealsAndAlternatesTheFirstMover() {
        val h = host()
        h.hostWinsTopRow()
        val mark = h.transport.mark()

        h.transport.inject(GUEST, TttTestKit.playAgainIntent)

        assertEquals(2, h.game.roundNumber.value)
        assertEquals(List(9) { 0 }, h.board())
        assertFalse(h.game.roundOver.value)
        assertEquals("the guest opens round 2", 2, h.game.currentPlayer.value)
        assertEquals(1, h.game.scoreP1.value)
        val out = h.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(2, out[0].stateSync.state.roundNumber)

        // The duplicate (both players tapped) arrives mid-round and is dropped.
        h.transport.inject(GUEST, TttTestKit.playAgainIntent)
        assertEquals(2, h.game.roundNumber.value)
        assertEquals(1, h.transport.sentSince(mark).size)
    }

    @Test
    fun theHostsOwnPlayAgainWorksAndAlsoBroadcasts() {
        val h = host()
        h.hostWinsTopRow()
        val mark = h.transport.mark()

        h.game.playAgain()

        assertEquals(2, h.game.roundNumber.value)
        assertEquals(1, h.transport.sentSince(mark).size)
    }

    @Test
    fun versionsOnlyIncrease() {
        val h = host()
        h.game.cellClicked(4)
        h.transport.inject(GUEST, cell(0))
        h.transport.inject(GUEST, TttTestKit.playAgainIntent) // ignored
        h.game.cellClicked(1)
        val versions = h.transport.sent.map { it.stateSync.version }
        assertEquals(versions.sorted(), versions)
        assertEquals(versions.toSet().size, versions.size)
    }

    @Test
    fun requestStateIsAnsweredDirectlyToTheAskerOnly() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.inject(GUEST, TicTacToeNetMessage.RequestState)

        val out = h.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(GUEST, out[0].to)
        assertEquals(1, out[0].stateSync.state.board[4])

        h.transport.inject("mallory", TicTacToeNetMessage.RequestState)
        h.transport.inject(HOST, TicTacToeNetMessage.RequestState)
        assertEquals(1, h.transport.sentSince(mark).size)
    }

    @Test
    fun aReconnectMakesTheHostRebroadcast() {
        val h = host()
        h.game.cellClicked(4)
        val mark = h.transport.mark()

        h.transport.fireReconnected()

        val out = h.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertNull(out[0].to)
        assertEquals(1, out[0].stateSync.state.board[4])
    }

    @Test
    fun theGuestLeavingFreezesTheBoardAndLetsTheHostLeaveWithoutAScore() {
        val h = host()
        h.game.cellClicked(4)
        val results = mutableListOf<GameResult>()
        h.game.setOnMatchEnd { results += it }

        h.transport.fireLeft(GUEST)
        assertTrue(h.game.opponentLeft.value)
        val mark = h.transport.mark()
        h.transport.inject(GUEST, cell(0))
        h.game.cellClicked(1)

        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), h.board())
        assertEquals(0, h.transport.sentSince(mark).size)

        h.game.leaveSession()
        assertEquals(1, results.size)
        assertTrue("leaving after the opponent left is an abort, never a loss", results[0].wasAborted)
        assertTrue(results[0].scores.isEmpty())
    }

    @Test
    fun aDepartureOfTheHostItselfIsNotMistakenForTheOpponentLeaving() {
        val h = host()
        h.transport.fireLeft(HOST)
        h.transport.fireLeft("stranger")
        assertFalse(h.game.opponentLeft.value)
    }

    @Test
    fun aRejoinClearsTheFlagAndResyncsTheGuest() {
        val h = host()
        h.transport.fireLeft(GUEST)
        val mark = h.transport.mark()

        h.transport.fireJoined(GUEST)

        assertFalse(h.game.opponentLeft.value)
        assertEquals(1, h.transport.sentSince(mark).size)
    }

    @Test
    fun networkedHostHasNoUndoHintOrSnapshotAndPlaysClassicRules() {
        val h = host(PlayMode.ONLINE)
        h.game.misere = true
        h.game.wild = true
        h.game.cellClicked(4)

        assertFalse(h.game.canUndo)
        assertNull(h.game.hint())
        assertNull(h.game.snapshot())
        assertEquals("wild is ignored on the wire: seat 0 places X", 1, h.board()[4])

        // Misere would hand the point to the OTHER seat; classic gives it to the completer.
        h.transport.inject(GUEST, cell(3))
        h.game.cellClicked(0)
        h.transport.inject(GUEST, cell(5))
        h.game.cellClicked(8)
        assertTrue(h.game.roundOver.value)
        assertEquals(1, h.game.scoreP1.value)
        assertEquals(0, h.game.scoreP2.value)
    }

    @Test
    fun theFirstMoverChoiceDoesNotApplyOverTheNetwork() {
        val game = TicTacToeGame()
        game.firstOpenerIndex = 1
        game.init(
            com.gamesuite.core.GameContext(
                PlayMode.LOCAL_AD_HOC, TttTestKit.roster, 0, TttFakeTransport()
            )
        )
        game.startMatch()
        assertEquals(1, game.currentPlayer.value)
    }

    @Test
    fun theHostCanBeUsedAfterTheMatchEndsWithoutAnySends() {
        val h = host()
        h.game.abortMatch()
        val mark = h.transport.mark()
        h.transport.inject(GUEST, cell(0))
        h.game.cellClicked(0)
        h.game.playAgain()
        assertEquals(0, h.transport.sentSince(mark).size)
    }
}
