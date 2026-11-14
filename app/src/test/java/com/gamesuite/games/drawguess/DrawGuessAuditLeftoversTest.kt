package com.gamesuite.games.drawguess

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.MultiplayerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private class LoTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

/** The audit leftovers of Draw and Guess: F11 layer planning, F14 live-match guard, and the rest of F15. */
class DrawGuessAuditLeftoversTest {
    private fun ctx(humans: Int, bots: Int = 0, teams: List<Int>? = null): GameContext {
        val players = List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}", teamId = teams?.get(it) ?: -1) } +
            List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
        return GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, 0, LoTransport())
    }

    private fun game(humans: Int, cfg: DrawGuessConfig = DrawGuessConfig(), first: Int = 0, bots: Int = 0, teams: List<Int>? = null) =
        DrawGuessGame(Random(1)).also { it.config = cfg; it.firstMover = first; it.init(ctx(humans, bots, teams)) }

    private fun toGuess(g: DrawGuessGame): DrawState {
        assertTrue(g.chooseWord(0)); assertTrue(g.beginDrawing())
        val s = g.state.value!!
        assertTrue(g.buzz(g.eligibleGuessers(s.drawerSeat).first()))
        return g.state.value!!
    }

    // ---- F11: the cached stroke layer ----

    private class S(val id: Int)

    @Test fun anUnchangedStrokeListNeedsNoWork() {
        val a = S(1); val b = S(2)
        val p = StrokeLayerPlanner.plan(listOf(a, b), listOf(a, b), sizeChanged = false)
        assertFalse(p.reset); assertFalse(p.hasWork); assertEquals(2, p.from)
    }

    @Test fun aFinishedStrokeIsPaintedOnTopWithoutARebuild() {
        val a = S(1); val b = S(2); val c = S(3)
        val p = StrokeLayerPlanner.plan(listOf(a, b), listOf(a, b, c), sizeChanged = false)
        assertFalse(p.reset); assertTrue(p.hasWork); assertEquals(2, p.from); assertEquals(3, p.total)
    }

    @Test fun undoRebuildsTheLayer() {
        val a = S(1); val b = S(2)
        val p = StrokeLayerPlanner.plan(listOf(a, b), listOf(a), sizeChanged = false)
        assertTrue(p.reset); assertEquals(0, p.from)
    }

    @Test fun clearRebuildsAnEmptyLayer() {
        val p = StrokeLayerPlanner.plan(listOf(S(1)), emptyList<S>(), sizeChanged = false)
        assertTrue(p.reset); assertTrue(p.hasWork); assertEquals(0, p.total)
    }

    @Test fun undoThenANewStrokeIsNotMistakenForAnAppend() {
        val a = S(1); val b = S(2); val c = S(3)
        val p = StrokeLayerPlanner.plan(listOf(a, b), listOf(a, c), sizeChanged = false)
        assertTrue("same length, different last stroke", p.reset)
    }

    @Test fun aNewPaperSizeRebuildsEverything() {
        val a = S(1)
        val p = StrokeLayerPlanner.plan(listOf(a), listOf(a), sizeChanged = true)
        assertTrue(p.reset); assertEquals(0, p.from); assertTrue(p.hasWork)
    }

    @Test fun theBoardsStrokesDriveThePlannerThroughRealUndoAndClear() {
        val g = game(3)
        g.startMatch()
        g.chooseWord(0); g.beginDrawing()
        var rendered: List<DrawStroke> = emptyList()
        fun drawLine(x: Float) {
            assertTrue(g.beginStroke(0, 1, false, x, 0.2f)); g.extendStroke(x + 0.2f, 0.4f); g.endStroke()
        }
        drawLine(0.1f); drawLine(0.3f)
        var p = StrokeLayerPlanner.plan(rendered, g.board.strokes, false)
        assertFalse(p.reset); assertEquals(0, p.from); assertEquals(2, p.total)
        rendered = g.board.strokes.toList()
        drawLine(0.5f)
        p = StrokeLayerPlanner.plan(rendered, g.board.strokes, false)
        assertFalse(p.reset); assertEquals(2, p.from)
        rendered = g.board.strokes.toList()
        assertTrue(g.undoStroke())
        assertTrue(StrokeLayerPlanner.plan(rendered, g.board.strokes, false).reset)
        rendered = g.board.strokes.toList()
        g.clearBoard()
        assertTrue(StrokeLayerPlanner.plan(rendered, g.board.strokes, false).reset)
    }

    // ---- F14: a live match is never restarted ----

    @Test fun aLiveMatchIsReportedSoASecondSetUpCanSkipIt() {
        val g = game(3)
        assertFalse(g.hasLiveMatch)
        g.startMatch()
        assertTrue(g.hasLiveMatch)
        g.chooseWord(0)
        assertTrue(g.hasLiveMatch)
        g.endMatch(GameResult(emptyList()))
        assertFalse("a match that was left is not live", g.hasLiveMatch)
    }

    // ---- F15: who goes first ----

    @Test fun theHubsFirstMoverDrawsFirst() {
        val g = game(4, first = 2)
        assertEquals(listOf(2, 3, 0, 1), g.order)
        g.startMatch()
        assertEquals(2, g.state.value!!.drawerSeat)
    }

    @Test fun aFirstMoverOutsideTheTableOrOnSeatZeroChangesNothing() {
        assertEquals(listOf(0, 1, 2), game(3, first = 0).order)
        assertEquals(listOf(0, 1, 2), game(3, first = 7).order)
        assertEquals(listOf(0, 1, 2), game(3, first = -1).order)
    }

    @Test fun aBotSeatCannotBeTheFirstMover() {
        val g = game(2, bots = 1, first = 2)
        assertEquals(listOf(0, 1), g.order)
    }

    @Test fun teamPlayStillAlternatesWhoeverStarts() {
        val teamOf = listOf(0, 1, 0, 1)
        val g = game(4, teams = teamOf, first = 1)
        assertEquals(1, g.order[0])
        val teams = g.order.map { teamOf[it] }
        for (i in 1 until teams.size) assertTrue("teams alternate", teams[i] != teams[i - 1])
    }

    @Test fun everyoneStillDrawsOnceStartingFromTheFirstMover() {
        val g = game(3, first = 1); g.startMatch()
        val drawers = ArrayList<Int>()
        while (g.state.value!!.phase != DrawPhase.OVER) {
            drawers += g.state.value!!.drawerSeat
            g.chooseWord(0); g.beginDrawing(); g.giveUp(); g.nextTurn()
        }
        assertEquals(listOf(1, 2, 0), drawers)
    }

    // ---- F15: cannot start with fewer than two ----

    @Test fun fewerThanTwoHumanSeatsIsReportedAsUnplayable() {
        val g = game(1, bots = 2)
        assertTrue(g.unplayable.value)
        g.startMatch()
        assertNull(g.state.value)
        assertFalse(game(2).unplayable.value)
    }

    // ---- F15: typed and card guesses ----

    @Test fun aCardGuessIsRefusedWhenThePlayersTypeInstead() {
        val g = game(3, DrawGuessConfig(typeIt = true)); g.startMatch()
        val s = toGuess(g)
        assertFalse(g.guessCard(s.options.first()))
        assertEquals("nothing was spent", DrawPhase.GUESS, g.state.value!!.phase)
        assertTrue(g.state.value!!.lockedOut.isEmpty())
        assertTrue(g.guessTyped(DrawGuessWordBank.byKey(s.wordKey!!)!!.en))
    }

    @Test fun anEmptyOrPunctuationOnlyAnswerIsNotAGuessAndCostsNothing() {
        val g = game(3, DrawGuessConfig(typeIt = true)); g.startMatch()
        toGuess(g)
        for (t in listOf("", "   ", "!!!", "?", "...", "- -", "a")) {
            assertFalse(g.guessTyped(t))
            val s = g.state.value!!
            assertEquals("still guessing after: $t", DrawPhase.GUESS, s.phase)
            assertTrue(s.lockedOut.isEmpty())
        }
        // A real wrong answer still costs the go.
        assertFalse(g.guessTyped("zzzzzz"))
        assertEquals(1, g.state.value!!.lockedOut.size)
    }

    @Test fun cardGuessesStillWorkInPickMode() {
        val g = game(3); g.startMatch()
        val s = toGuess(g)
        assertTrue(g.guessCard(s.wordKey!!))
    }
}
