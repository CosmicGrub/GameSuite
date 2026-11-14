package com.gamesuite.games.spider

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Spider engine as the screen drives it: dealing, taps, undo, resume, hints, the clock and the session. */
class SpiderGameTest {
    private val you = PlayerInfo("p1", "You")

    private fun newGame(seed: Int = 1, suits: Int = 1): SpiderGame {
        val g = SpiderGame(Random(seed))
        g.suits = suits
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(you), 0, LocalPassAndPlayTransport()))
        return g
    }

    private fun started(seed: Int = 1, suits: Int = 1): SpiderGame = newGame(seed, suits).also { it.startMatch() }

    /** A move chosen deterministically from the legal, useful ones. */
    private fun anyUsefulMove(s: SpiderState): SpiderMove? = SpiderMoveRanking.candidates(s).firstOrNull()?.move

    @Test fun startMatchDealsAConsistentTable() {
        val g = started(4, 2)
        val s = g.state.value!!
        assertTrue(SpiderRules.isConsistent(s))
        assertEquals(2, s.suits)
        assertEquals(1, g.dealSeq.value)
        assertEquals(SpiderEvent.NewDeal(daily = false, provenWinnable = false), g.event.value)
        assertFalse(g.canUndo)
    }

    @Test fun theSameInjectedRandomGivesTheSameDeal() {
        assertEquals(started(7).state.value, started(7).state.value)
        assertNotEquals(started(7).state.value, started(8).state.value)
    }

    @Test fun anInvalidSuitCountFallsBackToOne() {
        val g = SpiderGame()
        g.suits = 3
        assertEquals(1, g.suits)
        g.suits = 4
        assertEquals(4, g.suits)
    }

    @Test fun theDailyDealIsTheSameForEveryoneAndDiffersByDayAndSuits() {
        val a = newGame(1).also { it.startMatch(20_000L) }
        val b = newGame(99).also { it.startMatch(20_000L) } // a different injected Random must not matter
        assertEquals(a.state.value, b.state.value)
        assertTrue(a.isDaily.value)
        assertNotEquals(a.state.value, newGame(1).also { it.startMatch(20_001L) }.state.value)
        assertNotEquals(a.state.value!!.columns, newGame(1, 2).also { it.startMatch(20_000L) }.state.value!!.columns)
    }

    @Test fun theDailyDealIsSearchedForAWinnableOneAndIsStillSharedByEveryone() {
        val a = newGame(1).also { runBlocking { it.newDeal(5L) } }
        val b = newGame(2).also { runBlocking { it.newDeal(5L) } }
        assertEquals(a.state.value, b.state.value)
        assertTrue(a.provenWinnable.value)
        assertTrue(a.isDaily.value)
    }

    @Test fun aTapOnAFaceDownCardIsRejectedWithAReason() {
        val g = started()
        g.tapCard(0, 0)
        assertEquals(SpiderEvent.Rejected(SpiderReject.FACE_DOWN), g.event.value)
        assertNull(g.selected.value)
        assertEquals(1, g.eventSeq.value - 1) // NewDeal was the first event
    }

    @Test fun tappingAMovableRunSelectsItAndTappingItAgainDeselects() {
        val g = started()
        val top = g.state.value!!.columns[2].lastIndex
        g.tapCard(2, top)
        assertEquals(SpiderSelection(2, top), g.selected.value)
        assertTrue(g.event.value is SpiderEvent.Selected)
        g.tapCard(2, top)
        assertNull(g.selected.value)
        assertEquals(SpiderEvent.Deselected, g.event.value)
    }

    @Test fun tapSelectThenTapDestinationMovesTheRun() {
        val g = started(3)
        val s = g.state.value!!
        val move = SpiderRules.legalMoves(s).first { it.index == s.columns[it.from].lastIndex && s.columns[it.to].isNotEmpty() }
        g.tapCard(move.from, move.index)
        g.tapCard(move.to, s.columns[move.to].lastIndex)
        val after = g.state.value!!
        assertEquals(s.columns[move.to].size + 1, after.columns[move.to].size)
        assertEquals(1, after.moves)
        assertNull(g.selected.value)
        assertTrue(g.event.value is SpiderEvent.Moved)
        assertTrue(g.canUndo)
    }

    @Test fun tappingAnEmptyColumnDropsTheHeldRunThere() {
        val g = started(3)
        // Empty a column by hand-building: move every card of column 0 away is hard, so test the API on a crafted state.
        val s = g.state.value!!
        val crafted = s.copy(
            columns = s.columns.toMutableList().also { it[9] = emptyList() },
            faceDown = s.faceDown.toMutableList().also { it[9] = 0 },
            stock = s.stock,
            completed = emptyList()
        )
        // Conservation no longer holds for the crafted table, which is fine for a tap test.
        g.state.value = crafted
        val i = crafted.columns[0].lastIndex
        g.select(0, i)
        g.tapColumn(9)
        assertEquals(1, g.state.value!!.columns[9].size)
    }

    @Test fun anIllegalDropAnnouncesBadTargetAndKeepsTheTable() {
        val g = started(5)
        val s = g.state.value!!
        val from = 0
        val index = s.columns[from].lastIndex
        val bad = (1 until 10).first { !SpiderRules.canDrop(s, from, index, it) }
        g.select(from, index)
        val before = g.state.value
        g.tapCard(bad, s.columns[bad].lastIndex)
        assertEquals(before, g.state.value)
        // Either the run was re-picked from the tapped column (if it is a run) or the drop was refused.
        assertTrue(g.event.value is SpiderEvent.Rejected || g.event.value is SpiderEvent.Selected)
    }

    @Test fun dealRowAddsACardToEveryColumnAndIsUndoable() {
        val g = started(2)
        val before = g.state.value!!
        // Fill any empty column is impossible at the opening, so dealing is allowed immediately.
        assertTrue(g.dealRow())
        val after = g.state.value!!
        assertEquals(40, after.stock.size)
        assertEquals(before.columns.sumOf { it.size } + 10, after.columns.sumOf { it.size } + 13 * after.completedRuns)
        assertTrue(g.event.value is SpiderEvent.Dealt || g.event.value is SpiderEvent.NoMoves)
        g.undo()
        assertEquals(before.copy(), g.state.value)
        assertEquals(SpiderEvent.Undone, g.event.value)
    }

    @Test fun dealIsRefusedWithAnEmptyColumnAndWithNoStock() {
        val g = started(2)
        val s = g.state.value!!
        g.state.value = s.copy(columns = s.columns.toMutableList().also { it[4] = emptyList() }, faceDown = s.faceDown.toMutableList().also { it[4] = 0 })
        assertFalse(g.dealRow())
        assertEquals(SpiderEvent.Rejected(SpiderReject.DEAL_EMPTY_COLUMN), g.event.value)
        g.state.value = s.copy(stock = emptyList())
        assertFalse(g.dealRow())
        assertEquals(SpiderEvent.Rejected(SpiderReject.NO_STOCK), g.event.value)
    }

    @Test fun undoRestoresTheExactTableAndNeverGoesBackInTime() {
        val g = started(6, 1)
        val states = mutableListOf(g.state.value!!)
        var steps = 0
        while (steps < 25) {
            val m = anyUsefulMove(g.state.value!!)
            val ok = if (m != null) g.move(m.from, m.index, m.to) else g.dealRow()
            if (!ok) break
            states += g.state.value!!
            steps++
        }
        assertTrue(steps > 5)
        repeat(steps) { k ->
            g.tick()
            val ticked = g.state.value!!.elapsedSeconds
            g.undo()
            assertEquals(states[states.size - 2 - k].copy(elapsedSeconds = ticked), g.state.value)
        }
        assertFalse(g.canUndo)
        g.undo() // nothing left: a no-op
        assertEquals(states[0].copy(elapsedSeconds = g.state.value!!.elapsedSeconds), g.state.value)
    }

    @Test fun undoIsEffectivelyUnlimited() {
        val g = started(11, 1)
        val rng = Random(11)
        var count = 0
        // Random legal plies (moves and deals); every one pushes one undo step.
        while (count < 400) {
            val s = g.state.value!!
            if (s.won) break
            val legal = SpiderRules.legalMoves(s)
            val ok = if (legal.isEmpty() || (SpiderRules.canDeal(s) && rng.nextInt(10) == 0)) g.dealRow() else {
                val m = legal.random(rng)
                g.move(m.from, m.index, m.to)
            }
            if (!ok) break
            count++
        }
        assertTrue("only $count plies were possible", count > 100)
        var undone = 0
        while (g.canUndo) {
            g.undo()
            undone++
        }
        assertEquals(count, undone)
    }

    @Test fun theAsyncHintIsLegalAndMatchesWhatTheSolverFinds() {
        val g = started(5, 1)
        val h = runBlocking { g.requestHint() }
        assertNotNull(h)
        assertEquals(h, g.hintMove.value)
        when (h!!) {
            is SpiderHint.Move -> assertNotNull(SpiderRules.applyMove(g.state.value!!, (h as SpiderHint.Move).move))
            SpiderHint.Deal -> assertNotNull(SpiderRules.applyDeal(g.state.value!!))
        }
        assertTrue(g.event.value is SpiderEvent.Hinted)
    }

    @Test fun clockOnlyRunsWhileLiveAndNotPaused() {
        val g = started()
        g.tick(); g.tick()
        assertEquals(2, g.state.value!!.elapsedSeconds)
        g.pause()
        g.tick()
        assertEquals(2, g.state.value!!.elapsedSeconds)
        g.resume()
        g.tick()
        assertEquals(3, g.state.value!!.elapsedSeconds)
    }

    @Test fun snapshotRoundTripsEveryField() {
        val g = started(8, 2)
        repeat(6) {
            val m = anyUsefulMove(g.state.value!!)
            if (m != null) g.move(m.from, m.index, m.to)
        }
        g.tick()
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = newGame(99, 4)
        assertTrue(h.restore(snap!!))
        assertEquals(g.state.value, h.state.value)
        assertEquals(2, h.suits) // the save wins over the Hub choice
        assertEquals(SpiderEvent.Resumed, h.event.value)
        assertEquals(snap, h.snapshot())
    }

    @Test fun restoreRejectsGarbageWrongPlayerAndCorruptTables() {
        val g = started(8)
        val snap = g.snapshot()!!
        val h = newGame()
        assertFalse(h.restore("{"))
        assertFalse(h.restore(""))
        assertFalse(h.restore(snap.replace("\"p1\"", "\"someone-else\"")))
        // Duplicate a card in the stock.
        val dup = Regex("\"stock\":\\[(\\d+),(\\d+)").replace(snap) { "\"stock\":[${it.groupValues[1]},${it.groupValues[1]}" }
        assertFalse(h.restore(dup))
        assertNull(h.state.value)
        assertTrue(h.restore(snap))
    }

    @Test fun noSnapshotBeforeADealOrAfterLeaving() {
        val g = newGame()
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())
    }

    @Test fun hintHighlightsAMoveAndIsClearedByTheNextAction() {
        val g = started(3)
        val h = g.hint()
        assertNotNull(h)
        assertEquals(h, g.hintMove.value)
        assertTrue(g.event.value is SpiderEvent.Hinted)
        g.tapCard(0, 0)
        assertNull(g.hintMove.value)
    }

    @Test fun followingHintsNeverMakesAnIllegalMove() {
        for (seed in 1..12) {
            val g = started(seed, if (seed % 2 == 0) 1 else 2)
            var plies = 0
            while (plies < 1500) {
                val s = g.state.value!!
                if (s.won) break
                val h = SpiderHints.suggest(s) ?: break
                val ok = when (h) {
                    is SpiderHint.Move -> g.move(h.move.from, h.move.index, h.move.to)
                    SpiderHint.Deal -> g.dealRow()
                }
                assertTrue("hint $h was illegal at ply $plies", ok)
                assertTrue(SpiderRules.isConsistent(g.state.value!!))
                plies++
            }
        }
    }

    @Test fun winningCountsTheDealAndLocksTheTable() {
        val g = started(1)
        // One-suit table with seven runs already gone and the eighth one move from finished:
        // K..2 sit in column 0 and the ace is alone in column 1.
        val spadesRun = (12 downTo 1).toList()
        val ace = 0
        val done = (1..7).flatMap { b -> (12 downTo 0).map { b * 13 + it } }
        val won = SpiderState(
            suits = 1,
            columns = List(10) { i -> if (i == 0) spadesRun else if (i == 1) listOf(ace) else emptyList() },
            faceDown = List(10) { 0 },
            stock = emptyList(),
            completed = done,
            moves = 40,
            score = 300
        )
        assertTrue(SpiderRules.isConsistent(won))
        g.state.value = won
        assertTrue(g.move(1, 0, 0))
        assertTrue(g.state.value!!.won)
        assertEquals(SpiderEvent.Won, g.event.value)
        assertEquals(1, g.gamesWon.value)
        assertEquals(300 - 1 + SPIDER_RUN_BONUS, g.state.value!!.score)
        assertFalse(g.canUndo) // a won deal cannot be undone and won again
        assertFalse(g.move(0, 0, 1))
        assertNull(g.snapshot())
    }

    @Test fun stuckIsReportedWhenNoMoveOrRowRemains() {
        val g = started(1)
        // Ten single cards none of which fits another and no stock.
        val cols = List(10) { i -> listOf(i * 13 + 0) } // ten aces of ten different blocks (1-suit): ranks all 1, nothing stacks on an ace
        val s = SpiderState(1, cols.map { it }, List(10) { 0 }, emptyList(), moves = 1)
        // Not a full deck, so go through the engine's own "after move" bookkeeping through hasAnyMove.
        assertFalse(SpiderRules.hasAnyMove(s))
        g.state.value = s
        g.hint()
        assertEquals(SpiderEvent.NoMoves, g.event.value)
    }

    @Test fun leavingReportsDealsWonThisVisit() {
        val g = started()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(1, result!!.scores.size)
        assertFalse(result!!.scores[0].isWinner)
        g.leaveSession() // a second call does nothing
    }

    @Test fun abortingEndsTheMatchWithNoScores() {
        val g = started()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test fun changeListenerSeesBothTablesBeforeThePublish() {
        val g = newGame(3)
        val seen = mutableListOf<SpiderChange>()
        g.setOnChange { c ->
            seen += c
            // Called BEFORE the new state is published.
            assertTrue(c.after !== g.state.value || c.kind == SpiderChangeKind.NEW_DEAL && g.state.value == null)
        }
        g.startMatch()
        val s = g.state.value!!
        val m = SpiderRules.legalMoves(s).first()
        g.move(m.from, m.index, m.to)
        g.undo()
        assertEquals(listOf(SpiderChangeKind.NEW_DEAL, SpiderChangeKind.MOVE, SpiderChangeKind.UNDO), seen.map { it.kind })
        assertNull(seen[0].before)
        assertEquals(s, seen[1].before)
    }
}
