package com.gamesuite.games.nonogram

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume (snapshot/restore) and Hint for Nonogram, plus how the pre-existing stroke Undo coexists
 * with both. Every puzzle is EASY (5x5) dealt from daily seed 1; assertions that depend on which
 * squares the solution fills are computed from the state's own `solution`.
 */
class NonogramResumeHintTest {

    private class Clock(var t: Long = 0L)

    private val onePlayer = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: Clock = Clock(),
        players: List<PlayerInfo> = onePlayer,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): NonogramGame {
        val game = NonogramGame(nowMillis = { clock.t })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private fun started(clock: Clock = Clock(), mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): NonogramGame =
        newGame(clock, mode = mode).also { it.startMatch(dailySeed = 1L) }

    private fun st(game: NonogramGame): NonogramState = game.state.value!!

    private fun solveAll(game: NonogramGame) {
        val size = st(game).size
        for (i in 0 until size * size) {
            val s = st(game)
            if (s.solution[i] != (s.cells[i] == NonogramCellState.FILLED)) game.toggleCell(i, NonogramTool.FILL)
        }
    }

    private fun wrongCell(game: NonogramGame): Int = st(game).solution.indexOfFirst { !it }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no puzzle dealt yet", game.snapshot())

        game.startMatch(dailySeed = 1L)
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        assertNull("online state lives with the other players", started(mode = PlayMode.ONLINE).snapshot())
    }

    @Test
    fun `a restored game is identical and the same next move leads to the same position`() {
        val a = started()
        a.toggleCell(0, NonogramTool.FILL)
        a.toggleCell(wrongCell(a), NonogramTool.FILL)
        a.toggleCell(3, NonogramTool.CROSS)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(st(a), st(b))
        assertEquals(a.difficulty, b.difficulty)
        assertFalse(b.matchOver.value)

        a.toggleCell(7, NonogramTool.FILL)
        b.toggleCell(7, NonogramTool.FILL)
        assertEquals(st(a), st(b))
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun `the solution, tier, daily flag, mistakes and solved tally survive a resume`() {
        val a = started()
        assertTrue(a.dailyBoard.value)
        solveAll(a)
        assertEquals(1, a.puzzlesSolved.value)
        a.playAgain()
        assertFalse("a new puzzle leaves the daily", a.dailyBoard.value)
        a.toggleCell(wrongCell(a), NonogramTool.FILL)
        assertEquals(1, st(a).mistakes)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(st(a).solution, st(b).solution)
        assertEquals(st(a).rowClues, st(b).rowClues)
        assertEquals(st(a).colClues, st(b).colClues)
        assertEquals(1, st(b).mistakes)
        assertEquals(1, b.puzzlesSolved.value)
        assertFalse(b.dailyBoard.value)

        val daily = started()
        val c = newGame()
        assertTrue(c.restore(daily.snapshot()!!))
        assertTrue("the daily tag is re-derivable from the engine", c.dailyBoard.value)
    }

    @Test
    fun `a solved board resumes solved with its finished time`() {
        val clock = Clock()
        val a = started(clock)
        clock.t = 100
        a.toggleCell(0, NonogramTool.FILL)
        clock.t = 2_100
        solveAll(a)
        assertTrue(st(a).won)
        val finished = a.finishedElapsedMillis.value

        val b = newGame(Clock(t = 9_000_000))
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(st(b).won)
        assertEquals(finished, b.finishedElapsedMillis.value)
        assertEquals(finished, b.activeElapsedMillis())
        assertEquals(1, b.puzzlesSolved.value)
    }

    @Test
    fun `the clock continues from the active elapsed time and ignores time the app was gone`() {
        val c1 = Clock()
        val a = started(c1)
        c1.t = 1_000
        a.toggleCell(0, NonogramTool.FILL) // the clock starts here
        c1.t = 4_000
        a.pause() // backgrounded; this is when the shell saves
        c1.t = 9_000
        val snapshot = a.snapshot()!!

        val c2 = Clock(t = 500_000)
        val b = newGame(c2)
        assertTrue(b.restore(snapshot))
        assertEquals(3_000L, b.activeElapsedMillis())
        c2.t += 2_000
        assertEquals(5_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a timer that had not started stays unstarted`() {
        val a = started()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertNull(b.timerStartElapsedRealtime.value)
        assertNull(b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster, a wrong size and a bad hint list and leaves the engine untouched`() {
        val source = started()
        source.toggleCell(0, NonogramTool.FILL)
        val good = source.snapshot()!!

        val target = started()
        target.toggleCell(2, NonogramTool.CROSS)
        val before = st(target)

        assertFalse(target.restore("not json at all"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(""))
        assertFalse("size 6 is no tier's board", target.restore(good.replace("\"size\":5", "\"size\":6")))
        assertFalse("tier and board size must agree", target.restore(good.replace("\"difficulty\":\"EASY\"", "\"difficulty\":\"HARD\"")))
        assertFalse("a hinted square must be filled and in the solution", target.restore(good.replace("\"hinted\":[]", "\"hinted\":[0,0]")))
        assertFalse("a hinted square out of range", target.restore(good.replace("\"hinted\":[]", "\"hinted\":[99]")))
        assertFalse("negative mistakes", target.restore(good.replace(Regex("\"mistakes\":\\d+"), "\"mistakes\":-1")))

        val otherRoster = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X")))
        assertFalse(otherRoster.restore(good))
        assertNull(otherRoster.state.value)

        assertEquals("a failed restore must not change the engine", before, st(target))
    }

    // --- Undo coexists ----------------------------------------------------------------------

    @Test
    fun `after a resume the stroke history starts empty and undo works from the first new stroke`() {
        val a = started()
        a.toggleCell(0, NonogramTool.FILL)
        assertEquals(1, a.undoDepth.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals("stroke history is not saved", 0, b.undoDepth.value)
        assertFalse(b.undo())

        val wrong = wrongCell(b)
        b.toggleCell(wrong, NonogramTool.FILL)
        assertEquals(1, st(b).mistakes)
        assertTrue(b.undo())
        assertEquals(NonogramCellState.UNDETERMINED, st(b).cells[wrong])
        assertEquals("undo never lowers mistakes", 1, st(b).mistakes)
    }

    @Test
    fun `undo does not rewind the clock`() {
        val clock = Clock()
        val a = started(clock)
        clock.t = 1_000
        a.toggleCell(0, NonogramTool.FILL)
        clock.t = 6_000
        assertTrue(a.undo())
        assertEquals(5_000L, a.activeElapsedMillis())
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint reveals a correct square in the line with the fewest undecided squares`() {
        val game = started()
        val s0 = st(game)
        val n = s0.size
        val r = (0 until n).first { row -> (0 until n).any { c -> s0.solution[row * n + c] } }
        val rowSolution = (0 until n).map { c -> r * n + c }.filter { s0.solution[it] }
        val target = rowSolution.last()
        // Row r fully decided except one correct square: filled where the solution is, crossed elsewhere.
        for (i in (0 until n).map { c -> r * n + c }) {
            if (i == target) continue
            game.toggleCell(i, if (s0.solution[i]) NonogramTool.FILL else NonogramTool.CROSS)
        }
        assertTrue(game.canHint())

        val revealed = game.hint()
        assertEquals(target, revealed)
        val s = st(game)
        assertEquals(NonogramCellState.FILLED, s.cells[target])
        assertTrue(s.solution[target])
        assertEquals(1, s.hintsUsed)
        assertEquals(setOf(target), s.hinted)
        assertEquals(0, s.mistakes)
        assertEquals(target, game.hintCell.value)
    }

    @Test
    fun `a hint can also be taken on an untouched board and starts the clock`() {
        val game = started()
        val index = game.hint()
        assertNotNull(index)
        assertTrue(st(game).solution[index!!])
        assertNotNull(game.timerStartElapsedRealtime.value)
    }

    @Test
    fun `there is no hint before a puzzle, once solved, after the session ends, or with nothing left to reveal`() {
        val fresh = newGame()
        assertNull(fresh.hint())
        assertFalse(fresh.canHint())

        val solved = started()
        solveAll(solved)
        assertNull(solved.hint())
        assertFalse(solved.canHint())

        // Every correct square filled plus one wrong one: unsolved, yet nothing correct is left to reveal.
        val stuck = started()
        val s = st(stuck)
        stuck.toggleCell(wrongCell(stuck), NonogramTool.FILL) // first, so the board can never be won
        for (i in s.solution.indices) if (s.solution[i]) stuck.toggleCell(i, NonogramTool.FILL)
        assertFalse(st(stuck).won)
        assertNull(stuck.hint())
        assertFalse(stuck.canHint())
        assertEquals(0, st(stuck).hintsUsed)

        val over = started()
        over.leaveSession()
        assertNull(over.hint())
    }

    @Test
    fun `the next mark, a new puzzle and a restore clear the hint highlight`() {
        val game = started()
        game.hint()
        assertNotNull(game.hintCell.value)
        val other = st(game).cells.indices.first { it !in st(game).hinted }
        game.toggleCell(other, NonogramTool.CROSS)
        assertNull("the next mark clears it", game.hintCell.value)

        game.hint()
        assertNotNull(game.hintCell.value)
        game.playAgain()
        assertNull(game.hintCell.value)
        assertEquals("a new puzzle starts with no hints used", 0, st(game).hintsUsed)

        game.hint()
        val other2 = newGame()
        assertTrue(other2.restore(game.snapshot()!!))
        assertNull("a restore clears the highlight", other2.hintCell.value)
        assertEquals("but the hint count survives the resume", 1, st(other2).hintsUsed)
        assertEquals(st(game).hinted, st(other2).hinted)
    }

    @Test
    fun `undo clears the hint highlight but cannot take a hint back, and a hinted square is locked`() {
        val game = started()
        val wrong = wrongCell(game)
        game.toggleCell(wrong, NonogramTool.FILL) // a stroke to undo, and one mistake
        val index = game.hint()!!
        assertEquals(1, st(game).hintsUsed)

        assertTrue(game.undo())
        assertNull("undo clears the highlight", game.hintCell.value)
        assertEquals(NonogramCellState.UNDETERMINED, st(game).cells[wrong])
        assertEquals("undo never lowers hintsUsed", 1, st(game).hintsUsed)
        assertEquals("undo never lowers mistakes", 1, st(game).mistakes)
        assertEquals("the revealed square stays filled", NonogramCellState.FILLED, st(game).cells[index])
        assertEquals(setOf(index), st(game).hinted)

        assertFalse("a hinted square cannot be erased", game.toggleCell(index, NonogramTool.FILL))
        assertFalse("nor crossed out", game.toggleCell(index, NonogramTool.CROSS))
        game.tapCell(index)
        assertEquals(NonogramCellState.FILLED, st(game).cells[index])
    }

    @Test
    fun `restart keeps the hint count and unlocks the squares, a hint can finish the puzzle as an assisted solve`() {
        val game = started()
        game.toggleCell(wrongCell(game), NonogramTool.FILL)
        game.hint()
        game.restartPuzzle()
        assertEquals(1, st(game).hintsUsed)
        assertTrue(st(game).hinted.isEmpty())
        assertTrue(st(game).isAssisted)

        var guard = 0
        while (game.hint() != null && guard++ < 100) { /* keep revealing */ }
        val s = st(game)
        assertTrue("revealing every correct square solves it", s.won)
        assertTrue(s.isAssisted)
        assertTrue(s.hintsUsed > 1)
        assertEquals("an assisted solve still counts as solved", 1, game.puzzlesSolved.value)
        assertNotNull(game.finishedElapsedMillis.value)
        assertNull("nothing left to hint once solved", game.hint())
    }

    @Test
    fun `an unassisted solve is not marked assisted`() {
        val game = started()
        solveAll(game)
        assertTrue(st(game).won)
        assertFalse(st(game).isAssisted)
    }
}
