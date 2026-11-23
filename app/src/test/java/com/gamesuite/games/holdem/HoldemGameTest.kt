package com.gamesuite.games.holdem

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoldemGameTest {

    private fun vsBot(humans: Int = 1, bots: Int = 2, seed: Long = 5, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT, level: CpuDifficulty = CpuDifficulty.MEDIUM): HoldemGame {
        val g = HoldemGame(Random(seed))
        g.difficulty = level
        g.equityIterationsOverride = 20
        g.config = HoldemConfig(startingChips = 1000, blindSpeed = "off")
        g.firstButtonSeat = 0
        g.init(T.localContext(T.players(humans, bots), mode))
        g.startMatch()
        return g
    }

    /** Plays the computers (and the board) until a person has to act or the hand/match is over. */
    private fun advance(g: HoldemGame) {
        var guard = 0
        while (guard++ < 500) {
            val s = g.state.value!!
            when {
                s.matchOver || s.handOver -> return
                s.runout -> g.stepRunout()
                g.botToAct() -> g.playBotStep()
                else -> return
            }
        }
    }

    @Test fun aNewMatchDealsTheFirstHandWithBlindsPosted() {
        val g = vsBot()
        val s = g.state.value!!
        assertEquals(1, s.handNumber)
        assertEquals(3, s.seats.count { it.inHand && it.hole.size == 2 })
        assertEquals(30, s.seats.sumOf { it.bet })
        assertEquals(1000 * 3, s.chipsInPlay)
        assertFalse(s.handOver)
    }

    @Test fun undoTakesBackTheLastHumanActionExactly() {
        val g = vsBot()
        advance(g)
        val s = g.state.value!!
        assertEquals(0, s.toAct)
        val before = g.snapshot()
        assertFalse(g.canUndo)
        val o = g.options(0)!!
        assertEquals(HoldemReject.NONE, g.act(0, HoldemAction.raiseTo(o.minRaiseTo)))
        assertTrue(g.canUndo)
        assertTrue(g.state.value != s)
        g.undo()
        assertEquals(s, g.state.value)
        assertEquals(before, g.snapshot())
        assertFalse(g.canUndo)
    }

    @Test fun undoIsNotOfferedOnceTheHandEndsOrACardIsTurned() {
        val g = vsBot(humans = 1, bots = 1, seed = 9)
        advance(g)
        assertEquals(HoldemReject.NONE, g.act(0, HoldemAction.FOLD))
        assertTrue(g.state.value!!.handOver)
        assertFalse("folding ends the hand: nothing to take back", g.canUndo)
        // a call that closes the betting reveals the flop and clears the history
        val h = vsBot(humans = 1, bots = 1, seed = 11)
        advance(h)
        val s = h.state.value!!
        assertEquals(0, s.toAct)
        assertEquals(HoldemReject.NONE, h.act(0, HoldemAction.CALL))
        advance(h)
        val t = h.state.value!!
        if (t.street != HoldemStreet.PREFLOP || t.handOver) assertFalse(h.canUndo)
    }

    @Test fun undoIsNeverOfferedInPassAndPlayOrOnline() {
        val pass = vsBot(humans = 2, bots = 1, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        advance(pass)
        val o = pass.options(pass.state.value!!.toAct)!!
        pass.act(pass.state.value!!.toAct, HoldemAction.raiseTo(o.minRaiseTo))
        assertFalse(pass.canUndo)
        val t = HdTable(2)
        t.start()
        assertFalse(t.host.canUndo)
        assertFalse(t.games[1].canUndo)
    }

    @Test fun explainReportsWhyAnActionIsRefused() {
        val g = vsBot()
        advance(g)
        assertEquals(HoldemReject.NONE, g.explain(0, HoldemAction.CALL))
        assertEquals(HoldemReject.MUST_CALL, g.explain(0, HoldemAction.CHECK))
        assertEquals(HoldemReject.NOT_YOUR_TURN, g.explain(1, HoldemAction.CALL))
        assertEquals(HoldemReject.BELOW_MIN_RAISE, g.explain(0, HoldemAction.raiseTo(g.options(0)!!.minRaiseTo - 1)))
        assertEquals(HoldemReject.NOT_YOUR_TURN, g.act(1, HoldemAction.CALL))
    }

    @Test fun theHintReportsEquityAndPotOdds() {
        val g = vsBot()
        advance(g)
        val hint = runBlocking { g.requestHint() }
        assertNotNull(hint)
        hint!!
        assertTrue(hint.equity in 0.0..1.0)
        assertEquals(20, hint.toCall)
        assertEquals(20.0 / (hint.pot + 20), hint.potOdds, 1e-9)
        assertEquals(g.state.value!!.totalPot, hint.pot)
        assertEquals(hint, g.hint.value)
        // acting clears it
        g.act(0, HoldemAction.CALL)
        assertNull(g.hint.value)
    }

    @Test fun noHintWhileAComputerIsToAct() {
        val g = vsBot()
        // seat 0 is the button, so it acts first; fold it away and a computer has the move
        g.act(0, HoldemAction.FOLD)
        if (g.botToAct()) assertNull(runBlocking { g.requestHint() })
        assertFalse(g.canHint)
    }

    @Test fun aComputerDecisionIsOnlyAppliedToTheTableItWasMadeFor() {
        val g = vsBot()
        g.act(0, HoldemAction.CALL)
        assertTrue(g.botToAct())
        val afterCall = g.state.value!!
        val stale = runBlocking { g.computeBotDecision() }!!
        assertEquals(afterCall, stale.state)
        // an undo in between makes the decision stale
        g.undo()
        assertTrue(g.state.value != afterCall)
        assertFalse(g.applyBotDecision(stale))
        assertNull(runBlocking { g.computeBotDecision() }) // it is the person's turn again
    }

    @Test fun playBotTurnAsyncPlaysExactlyOneAction() {
        val g = vsBot()
        g.act(0, HoldemAction.CALL)
        val before = g.state.value!!.eventSerial
        runBlocking { g.playBotTurnAsync() }
        assertEquals(before + 1, g.state.value!!.eventSerial)
    }

    @Test fun humanVersusComputersRunsToAWinnerWithTheHumanAllIn() {
        val g = vsBot(humans = 1, bots = 2, seed = 21, level = CpuDifficulty.HARD)
        var guard = 0
        while (guard++ < 5000 && !g.state.value!!.matchOver) {
            advance(g)
            val s = g.state.value!!
            when {
                s.matchOver -> break
                s.handOver -> g.nextHand()
                else -> {
                    val o = g.options(s.toAct)!!
                    g.act(s.toAct, if (o.canAllIn) HoldemAction.ALL_IN else HoldemAction.CALL)
                }
            }
        }
        val end = g.state.value!!
        assertTrue(end.matchOver)
        assertNotNull(end.winnerPlayerId)
        assertEquals(3000, end.chipsInPlay)
    }

    @Test fun leavingSessionScoresChipsAndReportsOnce() {
        val g = vsBot()
        val results = ArrayList<GameResult>()
        g.setOnMatchEnd { results += it }
        g.leaveSession()
        g.leaveSession()
        g.abortMatch()
        assertEquals(1, results.size)
        assertEquals(3, results[0].scores.size)
        assertEquals(3000, results[0].scores.sumOf { it.score })
    }

    @Test fun abortingReportsAnAbortedMatchWithNoScores() {
        val g = vsBot()
        val results = ArrayList<GameResult>()
        g.setOnMatchEnd { results += it }
        g.abortMatch()
        assertTrue(results.single().wasAborted)
        assertTrue(results.single().scores.isEmpty())
    }

    @Test fun nothingToSaveOnceTheMatchIsOverOrOnline() {
        val g = vsBot(humans = 1, bots = 1, seed = 33)
        assertNotNull(g.snapshot())
        var guard = 0
        while (guard++ < 5000 && !g.state.value!!.matchOver) {
            advance(g)
            val s = g.state.value!!
            if (s.matchOver) break
            if (s.handOver) g.nextHand() else g.act(s.toAct, HoldemAction.ALL_IN)
        }
        assertNull(g.snapshot())
        val t = HdTable(3)
        t.start()
        assertNull(t.host.snapshot())
        assertFalse(t.games[1].restore("{}"))
    }

    @Test fun playAgainStartsAFreshTournament() {
        val g = vsBot(humans = 1, bots = 1, seed = 41)
        var guard = 0
        while (guard++ < 5000 && !g.state.value!!.matchOver) {
            advance(g)
            val s = g.state.value!!
            if (s.matchOver) break
            if (s.handOver) g.nextHand() else g.act(s.toAct, HoldemAction.ALL_IN)
        }
        assertTrue(g.state.value!!.matchOver)
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.matchOver)
        assertEquals(1, s.handNumber)
        assertTrue(s.seats.all { it.chips + it.committed == 1000 })
    }

    @Test fun restoreContinuesWithTheSameDeck() {
        val g = vsBot(humans = 1, bots = 1, seed = 61)
        advance(g)
        val snap = g.snapshot()!!
        val twin = vsBotUnstarted(61)
        assertTrue(twin.restore(snap))
        // both draw the same flop, because the deck order was saved
        assertEquals(HoldemReject.NONE, g.act(0, HoldemAction.CALL))
        assertEquals(HoldemReject.NONE, twin.act(0, HoldemAction.CALL))
        advance(g); advance(twin)
        val a = g.state.value!!
        val b = twin.state.value!!
        if (a.street == b.street) assertEquals(a.board, b.board)
        assertEquals(a.seats.map { it.hole }, b.seats.map { it.hole })
    }

    private fun vsBotUnstarted(seed: Long): HoldemGame {
        val g = HoldemGame(Random(seed + 1000))
        g.equityIterationsOverride = 20
        g.init(T.localContext(T.players(1, 1)))
        return g
    }
}
