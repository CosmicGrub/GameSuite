package com.gamesuite.games.sweettrail

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The module around the engine: wish, turns, sessions, input rejection (docs/design/boardgames/sweet-trail.md, 2.2 and 3.3). */
class SweetTrailGameTest {
    private fun SweetTrailGame.s() = state.value!!
    private fun SweetTrailGame.put(next: SweetTrailState) { state.value = next }

    private val cherry = single(TrailColour.CHERRY)
    private val lemon = single(TrailColour.LEMON)

    // ---- setup ----

    @Test fun aNewBoardStartsEveryPieceOnTheStartSquare() {
        val g = stNewGame(stRoster(3))
        assertEquals(listOf(0, 0, 0), g.s().positions)
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(SweetTrailEvent.START, g.s().event)
        assertEquals("sweet-trail", g.gameId)
        assertEquals(2, g.minPlayers); assertEquals(4, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneSeatIsNotAGame() { stNewGame(stRoster(1)) }

    @Test(expected = IllegalArgumentException::class)
    fun fiveSeatsAreTooMany() { stNewGame(stRoster(5)) }

    @Test fun theHubOptionsReachTheBoard() {
        val g = stNewGame(stRoster(3)) { length = TrailLength.SHORT; sticky = false; wishSeats = setOf(1, 9) }
        assertEquals(listOf(28, 28, 28), g.s().positions)
        assertFalse(g.s().rules.sticky)
        assertEquals(listOf(0, 1, 0), g.s().wishes) // a seat that is not at the table is dropped
    }

    // ---- wish ----

    @Test fun wishLetsTheSeatSwapTheDrawnCardOnce() {
        val g = stNewGame { wishSeats = setOf(0) }
        // seat 0 draws cherry, then swaps it for lemon (the card under it)
        g.put(g.s().withTop(lemon).withTop(cherry))
        assertTrue(g.draw())
        assertTrue(g.s().pending)
        assertEquals(cherry, g.s().card)
        assertTrue(g.useWish())
        val after = g.s()
        assertFalse(after.pending)
        assertEquals(lemon, after.eventCard)
        assertTrue(after.eventWished)
        assertEquals(3, after.positions[0]) // lemon from the start is square 3
        assertEquals(0, after.wishes[0])
        assertEquals(1, after.currentPlayerIndex)
        assertTrue(stEngine.validate(after).isEmpty())
    }

    @Test fun wishCannotBeUsedTwice() {
        val g = stNewGame { wishSeats = setOf(0) }
        g.draw(); g.useWish()
        g.draw() // seat 1 (no wish)
        assertEquals(0, g.s().currentPlayerIndex)
        assertTrue(g.draw())
        assertFalse("no wish left, so the card is not held back", g.s().pending)
        assertEquals(SweetTrailReject.NOTHING_TO_DECIDE, g.explainDecision(wish = true))
        assertFalse(g.useWish())
    }

    @Test fun wishedCardGoesToTheDiscardPile() {
        val g = stNewGame { wishSeats = setOf(0) }
        g.put(g.s().withTop(lemon).withTop(cherry))
        g.draw(); g.useWish()
        assertTrue(cherry in g.s().discard)
        assertTrue(lemon in g.s().discard)
        assertEquals((0 until 66).toList(), (g.s().pile + g.s().discard + listOfNotNull(g.s().card)).sorted())
    }

    @Test fun aSeatWithoutAWishMovesAtOnce() {
        val g = stNewGame()
        g.put(g.s().withTop(cherry))
        assertTrue(g.draw())
        assertFalse(g.s().pending)
        assertEquals(1, g.s().positions[0])
        assertEquals(1, g.s().currentPlayerIndex)
    }

    @Test fun keepingTheCardMovesThePieceAndKeepsTheWish() {
        val g = stNewGame { wishSeats = setOf(0) }
        g.put(g.s().withTop(cherry))
        g.draw()
        assertTrue(g.keepCard())
        assertEquals(1, g.s().positions[0])
        assertEquals(1, g.s().wishes[0]) // a kept card does not spend the wish
        assertFalse(g.s().eventWished)
    }

    @Test fun afterAWishTheNewCardMustBeKept() {
        val g = stNewGame { wishSeats = setOf(0) }
        g.draw(); g.useWish()
        assertFalse(g.s().pending)
        assertNull(g.s().card)
        assertFalse(g.useWish())
        assertFalse(g.keepCard())
    }

    // ---- chance ----

    @Test fun chanceComesOnlyFromTheInjectedRandom() {
        val a = stNewGame(random = Random(5)).s().pile
        val b = stNewGame(random = Random(5)).s().pile
        val c = stNewGame(random = Random(6)).s().pile
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(stBoard(seed = 123).pile, stNewGame { fixedSeed = 123 }.s().pile)
        val counting = CountingRandom(1)
        val g = stNewGame(random = counting)
        val atStart = counting.calls
        assertTrue(atStart > 0)
        repeat(20) { if (!g.s().over) g.draw() }
        assertEquals("playing a board never asks the deck stream for more", atStart, counting.calls)
        g.playAgain()
        assertTrue("a new board asks for exactly one more seed", counting.calls > atStart)
    }

    // ---- seats and sessions ----

    @Test fun passAndPlayRotatesSeats() {
        val g = stNewGame(stRoster(3))
        val seen = ArrayList<Int>()
        repeat(6) { seen += g.s().currentPlayerIndex; if (!g.s().over) g.draw() }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), seen)
    }

    @Test fun firstOpenerIsHonoured() {
        val g = stNewGame(stRoster(3)) { firstOpenerIndex = 2 }
        assertEquals(2, g.s().currentPlayerIndex)
        g.playAgain()
        assertEquals(0, g.s().currentPlayerIndex)
    }

    private fun win(g: SweetTrailGame, seat: Int) {
        val s = g.s()
        val spots = s.positions.toMutableList()
        spots[seat] = 57
        g.put(s.copy(positions = spots, currentPlayerIndex = seat).withTop(cherry))
        assertTrue(g.draw())
        assertTrue(g.s().over)
    }

    @Test fun playAgainRotatesTheStarter() {
        val g = stNewGame(stRoster(3))
        val starters = ArrayList<Int>()
        repeat(4) { starters += g.s().currentPlayerIndex; win(g, 1); g.playAgain() }
        assertEquals(listOf(0, 1, 2, 0), starters)
        assertEquals(listOf(0, 0, 0), g.s().positions)
        assertFalse(g.s().over)
    }

    @Test fun leaveSessionRecordsTheBoardsWon() {
        val g = stNewGame(stRoster(3))
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        win(g, 1)
        g.playAgain()
        win(g, 1)
        g.playAgain()
        win(g, 2)
        g.leaveSession()
        val r = result!!
        assertEquals(mapOf("p1" to 0, "p2" to 2, "p3" to 1), r.scores.associate { it.playerId to it.score })
        assertEquals(listOf("p2"), r.scores.filter { it.isWinner }.map { it.playerId })
        assertTrue(g.matchOver.value)
        assertNull(g.snapshot())
        g.playAgain() // after the session ended nothing restarts
        assertTrue(g.matchOver.value)
    }

    @Test fun aTieAtTheEndOfASessionHasNoWinner() {
        val g = stNewGame(stRoster(2))
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        win(g, 0); g.playAgain(); win(g, 1)
        g.leaveSession()
        assertTrue(result!!.scores.none { it.isWinner })
    }

    @Test fun sessionWinsAccumulateAcrossBoards() {
        val g = stNewGame(stRoster(2))
        win(g, 0); g.playAgain(); win(g, 0); g.playAgain(); win(g, 1)
        assertEquals(mapOf("p1" to 2, "p2" to 1), g.sessionWins.value)
    }

    // ---- taps ----

    @Test fun tapsOutOfTurnAreRejectedWithAReason() {
        val g = stNewGame(listOf(stRoster(2, bots = setOf(0))[0], stRoster(2)[1])) // the computer opens
        assertEquals(SweetTrailReject.NOT_YOUR_TURN, g.explainDraw())
        assertFalse(g.draw())
        assertEquals(SweetTrailReject.NOT_YOUR_TURN, g.explainDecision(false))

        val h = stNewGame(stRoster(2)) { wishSeats = setOf(0) }
        assertTrue(h.draw())
        assertEquals(SweetTrailReject.DECIDE_FIRST, h.explainDraw())
        assertFalse(h.draw())
        assertTrue(h.keepCard())
        assertEquals(SweetTrailReject.NOTHING_TO_DECIDE, h.explainDecision(false))
        assertEquals(SweetTrailReject.NOTHING_TO_DECIDE, h.explainDecision(true))

        val done = stNewGame(stRoster(2))
        win(done, 0)
        assertEquals(SweetTrailReject.OVER, done.explainDraw())
        assertEquals(SweetTrailReject.OVER, done.explainDecision(true))
        assertFalse(done.draw())
    }

    @Test fun aWishIsRefusedForASeatThatHoldsNone() {
        val g = stNewGame(stRoster(2)) { wishSeats = setOf(1) }
        g.put(g.s().copy(pending = true, card = cherry, event = SweetTrailEvent.PENDING, eventSeat = 0, eventCard = cherry,
            pile = g.s().pile - cherry, drawSerial = 1, eventFrom = 0, eventTo = 0))
        assertEquals(SweetTrailReject.NO_WISH, g.explainDecision(wish = true))
        assertFalse(g.useWish())
    }

    @Test fun aSecondTapBeforeTheScreenHasSettledIsRefused() {
        val g = stNewGame(stRoster(3)) { holdInputUntilSettled = true }
        assertTrue(g.draw())
        assertEquals(SweetTrailReject.BUSY, g.explainDraw())
        assertFalse("the next seat must not be drawn for by the same input batch", g.draw())
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(1, g.s().moveSerial)
        g.settled()
        assertEquals(SweetTrailReject.NONE, g.explainDraw())
        assertTrue(g.draw())
        assertEquals(2, g.s().moveSerial)
    }

    @Test fun aSecondTapDuringAWishDecisionIsRefusedToo() {
        val g = stNewGame(stRoster(2)) { holdInputUntilSettled = true; wishSeats = setOf(0) }
        assertTrue(g.draw()); g.settled()
        assertTrue(g.useWish())
        assertFalse(g.keepCard())
        assertFalse(g.useWish())
        assertEquals(SweetTrailReject.BUSY, g.explainDecision(true))
    }

    @Test fun computerSeatNeverGetsAHumanTurn() {
        val g = stNewGame(listOf(stRoster(2)[0], stRoster(2, setOf(1))[1]))
        assertTrue(g.isMyTurn())
        g.draw()
        assertFalse(g.isMyTurn())
        assertTrue(g.botToMove())
        assertFalse(g.draw())
        assertFalse(g.keepCard())
        assertFalse(g.useWish())
        g.playBotStep()
        assertTrue(g.isMyTurn())
        assertFalse(g.botToMove())
    }

    @Test fun pausedComputersTakeNoStep() {
        val g = stNewGame(listOf(stRoster(2, setOf(0))[0], stRoster(2)[1]))
        g.pause()
        val before = g.s()
        g.playBotStep()
        assertTrue(before === g.s())
        g.resume()
        g.playBotStep()
        assertTrue(before !== g.s())
    }

    @Test fun aBotStepWithNothingToDoLeavesTheStateAlone() {
        val g = stNewGame()
        val before = g.s()
        g.playBotStep() // a person is on turn
        assertTrue(before === g.s())
        assertNotNull(g.state.value)
    }

    @Test fun anEverythingFinishedBoardAcceptsNoMoreComputerSteps() {
        val g = stNewGame(stRoster(2, setOf(0, 1)))
        g.playBotsToTheEnd()
        val done = g.s()
        g.playBotStep()
        assertTrue(done === g.s())
    }

    @Test fun wishExtrasAreReadForTheRealSeatCount() {
        assertEquals(setOf(1), sweetTrailWishFromExtras(mapOf("wish" to "2"), 2))
        assertEquals(setOf(2), sweetTrailWishFromExtras(mapOf("wish3" to "3", "wish" to "none"), 3))
        assertEquals(setOf(0, 1, 2, 3), sweetTrailWishFromExtras(mapOf("wish4" to "all"), 4))
        assertEquals(emptySet<Int>(), sweetTrailWishFromExtras(mapOf("wish" to "4"), 2))
        assertEquals(emptySet<Int>(), sweetTrailWishFromExtras(emptyMap(), 3))
        assertEquals(setOf(0, 2), parseSweetTrailWish("1, 3", 3))
        assertEquals(emptySet<Int>(), parseSweetTrailWish("x", 3))
        assertEquals(emptySet<Int>(), parseSweetTrailWish(null, 3))
    }
}
