package com.gamesuite.games.tictactoe

import com.gamesuite.core.PlayMode
import com.gamesuite.games.tictactoe.TttTestKit.GUEST
import com.gamesuite.games.tictactoe.TttTestKit.HOST
import com.gamesuite.ui.networkRoundResultText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host and a guest engine joined by the in-memory relay: real round trips, both directions. */
class TicTacToeNetworkedEndToEndTest {

    /** Taps [cell] on whichever device owns the seat to move, exactly as the screen would. */
    private fun TttPair.tap(cell: Int) {
        val mover = if (host.game.currentPlayer.value == 1) host else guest
        mover.game.cellClicked(cell)
    }

    @Test
    fun theGuestSyncsOnStartAndBothSeeAnEmptyBoard() {
        val p = TttTestKit.pair()
        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(List(9) { 0 }, p.guest.game.board.value.toList())
        assertEquals(1, p.guest.game.currentPlayer.value)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun aGuestThatStartsLateStillGetsTheCurrentBoardThroughRequestState() {
        val hub = TttHub()
        val host = TttTestKit.node(0, transport = hub.endpoint(HOST))
        host.game.cellClicked(4)
        val guest = TttTestKit.node(1, transport = hub.endpoint(GUEST)) // joins after the host already moved
        TttTestKit.assertSame(host.game, guest.game)
        assertEquals(1, guest.game.board.value[4])
        assertEquals(2, guest.game.currentPlayer.value)
    }

    @Test
    fun aScriptedHostWinEndsIdenticallyOnBothEngines() {
        val p = TttTestKit.pair(PlayMode.ONLINE)
        // X: 4, 2, 6 (anti-diagonal). O: 0, 1.
        for (c in listOf(4, 0, 2, 1, 6)) p.tap(c)

        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertTrue(p.guest.game.roundOver.value)
        assertEquals(listOf(2, 4, 6), p.guest.game.winningLine.value)
        assertEquals(1, p.guest.game.scoreP1.value)
        assertEquals(0, p.guest.game.scoreP2.value)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun aScriptedGuestWinEndsIdenticallyOnBothEngines() {
        val p = TttTestKit.pair()
        // X: 0, 1, 8. O: 3, 4, 5 (middle row, completed by the guest).
        for (c in listOf(0, 3, 1, 4, 8, 5)) p.tap(c)

        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(listOf(3, 4, 5), p.host.game.winningLine.value)
        assertEquals(1, p.host.game.scoreP2.value)
        assertEquals(
            "You win!",
            networkRoundResultText(p.guest.game.winningLine.value, p.guest.game.currentPlayer.value, 2, "Hosty", "Guesty")
        )
        assertEquals(
            "Guesty wins - you lose",
            networkRoundResultText(p.host.game.winningLine.value, p.host.game.currentPlayer.value, 1, "Hosty", "Guesty")
        )
    }

    @Test
    fun aScriptedDrawEndsIdenticallyOnBothEngines() {
        val p = TttTestKit.pair()
        // X O X / X O O / O X X
        for (c in listOf(0, 1, 2, 4, 3, 5, 7, 6, 8)) p.tap(c)

        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertTrue(p.guest.game.roundOver.value)
        assertEquals(1, p.guest.game.draws.value)
        assertEquals(null, p.guest.game.winningLine.value)
        assertEquals("It's a draw!", networkRoundResultText(null, 1, 2, "Hosty", "Guesty"))
    }

    @Test
    fun playAgainWorksFromEitherSeatAndAlternatesWhoOpens() {
        val p = TttTestKit.pair()
        for (c in listOf(4, 0, 2, 1, 6)) p.tap(c) // host wins round 1

        p.guest.game.playAgain() // the guest asks
        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(2, p.host.game.roundNumber.value)
        assertEquals("the guest opens round 2", 2, p.host.game.currentPlayer.value)
        assertEquals(1, p.guest.game.scoreP1.value)

        p.guest.game.playAgain() // a duplicate tap mid-round does nothing
        p.host.game.playAgain()
        assertEquals(2, p.host.game.roundNumber.value)

        // Round 2: the guest (O) opens and wins on the left column.
        for (c in listOf(0, 4, 3, 5, 6)) p.tap(c)
        // O: 0, 3, 6 / X: 4, 5.
        assertTrue(p.host.game.roundOver.value)
        assertEquals(1, p.host.game.scoreP2.value)
        TttTestKit.assertSame(p.host.game, p.guest.game)

        p.host.game.playAgain() // now the host asks; round 3 is the host's to open
        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(3, p.guest.game.roundNumber.value)
        assertEquals(1, p.guest.game.currentPlayer.value)
    }

    @Test
    fun aGuestTapOnTheHostsTurnChangesNothingAnywhere() {
        val p = TttTestKit.pair()
        p.guest.game.cellClicked(0) // host's turn
        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(List(9) { 0 }, p.host.game.board.value.toList())
    }

    @Test
    fun aGuestWhoMissedBroadcastsCatchesUpAfterAReconnect() {
        val p = TttTestKit.pair()
        // Simulate the guest being offline for two host moves by hiding the guest endpoint.
        val guestEndpoint = p.hub.endpoints.remove(GUEST)!!
        p.host.game.cellClicked(4)
        p.hub.endpoints[GUEST] = guestEndpoint
        assertEquals("the guest missed the move", 0, p.guest.game.board.value[4])

        guestEndpoint.fireReconnected() // guest asks again; host answers directly

        TttTestKit.assertSame(p.host.game, p.guest.game)
        assertEquals(1, p.guest.game.board.value[4])
    }

    @Test
    fun aHostDisconnectLeavesTheGuestAbleToLeaveWithoutALoss() {
        val p = TttTestKit.pair()
        p.tap(4)
        p.guest.transport.fireLeft(HOST)

        assertTrue(p.guest.game.opponentLeft.value)
        assertFalse(p.host.game.opponentLeft.value)
        var aborted: Boolean? = null
        p.guest.game.setOnMatchEnd { aborted = it.wasAborted }
        p.guest.game.leaveSession()
        assertEquals(true, aborted)
    }

    @Test
    fun resultWordingNamesTheWinnerAndYourOutcome() {
        val line = listOf(0, 1, 2)
        assertEquals("You win!", networkRoundResultText(line, 1, 1, "Hosty", "Guesty"))
        assertEquals("Guesty wins - you lose", networkRoundResultText(line, 2, 1, "Hosty", "Guesty"))
        assertEquals("Hosty wins - you lose", networkRoundResultText(line, 1, 2, "Hosty", "Guesty"))
        assertEquals("Hosty wins!", networkRoundResultText(line, 1, null, "Hosty", "Guesty"))
    }
}
