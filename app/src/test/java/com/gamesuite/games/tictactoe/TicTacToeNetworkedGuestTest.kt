package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.tictactoe.TttTestKit.GUEST
import com.gamesuite.games.tictactoe.TttTestKit.HOST
import com.gamesuite.games.tictactoe.TttTestKit.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guest's side of the Tic-Tac-Toe protocol: it only ever displays what the host sent. */
class TicTacToeNetworkedGuestTest {

    private fun guest(mode: PlayMode = PlayMode.ONLINE) = TttTestKit.node(1, mode)

    private fun TttNode.board() = game.board.value.toList()

    private fun TttNode.sync(version: Int, s: TicTacToeNetState) =
        transport.inject(HOST, TicTacToeNetMessage.StateSync(version, s))

    @Test
    fun initAsksTheHostForTheCurrentState() {
        val g = TttTestKit.node(1, start = false)
        assertEquals(1, g.transport.sent.size)
        val sent = g.transport.sent[0]
        assertEquals(GUEST, sent.from)
        assertEquals(HOST, sent.to)
        assertEquals(TicTacToeNetMessage.RequestState, sent.message)
    }

    @Test
    fun aTapIsForwardedToTheHostAndNeverAppliedLocally() {
        val g = guest()
        val mark = g.transport.mark()

        g.game.cellClicked(4)

        assertEquals(List(9) { 0 }, g.board())
        val out = g.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(HOST, out[0].to)
        assertEquals(GUEST, out[0].from)
        assertEquals(cellIntentOf(4), out[0].message)
    }

    private fun cellIntentOf(i: Int) = TttTestKit.cell(i)

    @Test
    fun aNewerStateIsApplied() {
        val g = guest()
        g.sync(5, state(board = listOf(1, 0, 0, 0, 2, 0, 0, 0, 0), currentPlayer = 1, roundNumber = 1))

        assertEquals(listOf(1, 0, 0, 0, 2, 0, 0, 0, 0), g.board())
        assertEquals(1, g.game.currentPlayer.value)
    }

    @Test
    fun anOlderOrRepeatedVersionIsIgnored() {
        val g = guest()
        g.sync(5, state(board = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 2))

        g.sync(4, state(board = List(9) { 0 }, currentPlayer = 1))
        g.sync(5, state(board = listOf(1, 1, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 1))

        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0, 0, 0), g.board())
        assertEquals(2, g.game.currentPlayer.value)

        g.sync(6, state(board = listOf(1, 2, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 1))
        assertEquals(listOf(1, 2, 0, 0, 0, 0, 0, 0, 0), g.board())
    }

    @Test
    fun aStateFromAnyoneButTheHostIsIgnored() {
        val g = guest()
        g.transport.inject("mallory", TicTacToeNetMessage.StateSync(9, state(board = List(9) { 1 })))
        g.transport.inject(GUEST, TicTacToeNetMessage.StateSync(9, state(board = List(9) { 1 })))

        assertEquals(List(9) { 0 }, g.board())
        // ...and the ignored message must not have burned the version either.
        g.sync(1, state(board = listOf(2, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(2, g.board()[0])
    }

    @Test
    fun implausibleStatesAreIgnored() {
        val g = guest()
        g.sync(1, state(board = List(8) { 0 }))
        g.sync(2, state(board = List(9) { 7 }))
        g.sync(3, state(currentPlayer = 0))
        g.sync(4, state(roundNumber = 0))
        g.sync(5, state(roundOver = false, winningLine = listOf(0, 1, 2)))
        g.transport.inject(HOST, "garbage".encodeToByteArray())

        assertEquals(List(9) { 0 }, g.board())
        g.sync(6, state(board = listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), currentPlayer = 2))
        assertEquals(1, g.board()[4])
    }

    @Test
    fun theGuestIgnoresIntentsAndRequestsAimedAtTheHost() {
        val g = guest()
        val mark = g.transport.mark()

        g.transport.inject(HOST, TttTestKit.cell(0))
        g.transport.inject(HOST, TttTestKit.playAgainIntent)
        g.transport.inject(HOST, TicTacToeNetMessage.RequestState)

        assertEquals(List(9) { 0 }, g.board())
        assertEquals(0, g.transport.sentSince(mark).size)
    }

    @Test
    fun aReconnectAsksTheHostAgain() {
        val g = guest()
        val mark = g.transport.mark()

        g.transport.fireReconnected()

        val out = g.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(HOST, out[0].to)
        assertEquals(TicTacToeNetMessage.RequestState, out[0].message)
    }

    @Test
    fun playAgainIsForwardedOnlyWhenTheRoundIsOver() {
        val g = guest()
        val mark = g.transport.mark()

        g.game.playAgain() // round still running
        assertEquals(0, g.transport.sentSince(mark).size)

        g.sync(1, state(board = listOf(1, 1, 1, 2, 2, 0, 0, 0, 0), roundOver = true, winningLine = listOf(0, 1, 2), scoreP1 = 1))
        g.game.playAgain()

        val out = g.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(TttTestKit.playAgainIntent, out[0].message)
        assertTrue("the guest's board resets only when the host says so", g.game.roundOver.value)
    }

    @Test
    fun theHostLeavingStopsInputAndLeavingIsAnAbortWithNoScore() {
        val g = guest()
        g.sync(1, state(board = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 2))
        val results = mutableListOf<GameResult>()
        g.game.setOnMatchEnd { results += it }

        g.transport.fireLeft(HOST)
        assertTrue(g.game.opponentLeft.value)
        val mark = g.transport.mark()
        g.game.cellClicked(4)
        g.game.playAgain()
        assertEquals(0, g.transport.sentSince(mark).size)

        g.game.leaveSession()
        assertEquals(1, results.size)
        assertTrue(results[0].wasAborted)
        assertTrue(results[0].scores.isEmpty())
    }

    @Test
    fun theGuestLeavingNormallyStillReportsTheScore() {
        val g = guest()
        g.sync(1, state(board = listOf(1, 1, 1, 2, 2, 0, 0, 0, 0), roundOver = true, winningLine = listOf(0, 1, 2), scoreP1 = 1))
        val results = mutableListOf<GameResult>()
        g.game.setOnMatchEnd { results += it }

        g.game.leaveSession()

        assertFalse(results[0].wasAborted)
        assertEquals(listOf(1, 0), results[0].scores.map { it.score })
        assertEquals(listOf(true, false), results[0].scores.map { it.isWinner })
    }

    @Test
    fun noUndoHintOrResumeInNetworkPlay() {
        val g = guest(PlayMode.LOCAL_AD_HOC)
        g.sync(1, state(board = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 2))

        assertFalse(g.game.canUndo)
        g.game.undo()
        assertNull(g.game.hint())
        assertNull(g.game.snapshot())
        assertFalse(g.game.restore("{}"))
    }

    @Test
    fun aGuestNeverMutatesItsEngineFromTaps() {
        val g = guest()
        g.sync(1, state(board = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0), currentPlayer = 2))
        val before = g.board()

        for (i in 0..8) g.game.cellClicked(i)

        assertEquals(before, g.board())
        assertEquals(2, g.game.currentPlayer.value)
        assertEquals(1, g.game.roundNumber.value)
    }
}
