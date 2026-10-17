package com.gamesuite.games.kakuro

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

/** Resume (snapshot/restore), Undo and Hint for Kakuro, on a seeded EASY puzzle. */
class KakuroResumeUndoHintTest {

    private var now = 0L
    private val roster = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        players: List<PlayerInfo> = roster,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): KakuroGame {
        val game = KakuroGame(nowMillis = { now })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private fun started(seed: Long? = 7L): KakuroGame = newGame().also { it.startMatch(seed) }

    private fun whiteCells(g: KakuroGame): List<Int> =
        g.state.value!!.cells.indices.filter { g.state.value!!.cells[it].type == KakuroCellType.WHITE }

    private fun solutionOf(g: KakuroGame, index: Int): Int = g.state.value!!.solution[index]!!

    /** A digit that is wrong for [index]. */
    private fun wrongDigit(g: KakuroGame, index: Int): Int = if (solutionOf(g, index) == 1) 2 else 1

    private fun enter(g: KakuroGame, index: Int, digit: Int) {
        g.selectCell(index)
        g.setValue(digit)
    }

    private fun enterCorrect(g: KakuroGame, index: Int) = enter(g, index, solutionOf(g, index))

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no puzzle dealt yet", game.snapshot())

        game.startMatch(7L)
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch(7L) }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        val cells = whiteCells(a)
        enterCorrect(a, cells[0])
        enter(a, cells[1], wrongDigit(a, cells[1]))
        a.selectCell(cells[2])
        a.toggleNote(3)
        a.toggleNote(5)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        assertEquals(a.difficulty, b.difficulty)
        assertFalse(b.matchOver.value)
        assertFalse("undo history is not carried over", b.canUndo)

        enterCorrect(a, cells[3])
        enterCorrect(b, cells[3])
        assertEquals("the same entry must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `the hidden solution, tier, daily flag and seed survive a resume`() {
        val a = started(seed = 42L)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.state.value!!.solution, b.state.value!!.solution)
        assertEquals(CpuDifficulty.EASY, b.difficulty)
        assertTrue(b.dailyBoard.value)
        assertEquals(42L, b.boardSeed)

        val random = started(seed = null)
        val c = newGame()
        assertTrue(c.restore(random.snapshot()!!))
        assertFalse(c.dailyBoard.value)
        assertNull(c.boardSeed)
    }

    @Test
    fun `solved count, mistakes and hints survive a resume`() {
        val a = started()
        for (i in whiteCells(a)) enterCorrect(a, i)
        assertEquals(1, a.puzzlesSolved.value)
        a.playAgain()
        val first = whiteCells(a).first()
        enter(a, first, wrongDigit(a, first))
        a.selectCell(whiteCells(a).last())
        a.hint()

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(1, b.state.value!!.mistakes)
        assertEquals(1, b.hintsUsed.value)
    }

    @Test
    fun `the clock continues from the active time and does not count the time the app was gone`() {
        val a = started()
        now = 1_000L
        enterCorrect(a, whiteCells(a).first())
        now = 5_000L
        assertEquals(4_000L, a.activeElapsedMillis())
        a.pause()
        now = 9_000L // paused: does not count
        val snapshot = a.snapshot()!!
        assertTrue(snapshot.isNotEmpty())

        now = 1_000_000L // the app was gone for a long time
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(4_000L, b.activeElapsedMillis())
        now += 500L
        assertEquals(4_500L, b.activeElapsedMillis())
        // A resume callback right after a restore must not shift the clock.
        b.resume()
        assertEquals(4_500L, b.activeElapsedMillis())
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
    fun `a solved puzzle keeps its frozen time through a resume`() {
        val a = started()
        now = 100L
        val cells = whiteCells(a)
        for (i in cells.dropLast(1)) enterCorrect(a, i)
        now = 900L
        enterCorrect(a, cells.last())
        val frozen = a.finishedElapsedMillis.value
        assertNotNull(frozen)
        val b = newGame()
        now = 50_000L
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.won)
        assertEquals(frozen, b.finishedElapsedMillis.value)
        assertEquals(frozen, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a damaged board and leaves the engine untouched`() {
        val a = started()
        enterCorrect(a, whiteCells(a).first())
        val good = a.snapshot()!!

        val target = started(seed = 99L)
        val before = target.state.value
        val solvedBefore = target.puzzlesSolved.value

        assertFalse(target.restore("not json at all"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(""))

        // Wrong roster: a different player id.
        val other = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X")))
        assertFalse(other.restore(good))

        // Wrong-sized board: shrink the declared columns.
        assertFalse(target.restore(good.replaceFirst("\"cols\":6", "\"cols\":5")))
        // Impossible digit in a cell.
        assertFalse(target.restore(good.replaceFirst("\"value\":" + solutionOf(a, whiteCells(a).first()), "\"value\":10")))
        // A clue that no longer matches the hidden solution.
        val clueDamaged = Regex("\"across\":(\\d+)").find(good)!!.let { m ->
            val bad = (m.groupValues[1].toInt() + 1).toString()
            good.replaceRange(m.range, "\"across\":$bad")
        }
        assertFalse(target.restore(clueDamaged))

        assertEquals(before, target.state.value)
        assertEquals(solvedBefore, target.puzzlesSolved.value)
        assertTrue("a valid snapshot still restores afterwards", target.restore(good))
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one entry or note at a time`() {
        val g = started()
        val cells = whiteCells(g)
        assertFalse(g.canUndo)
        enterCorrect(g, cells[0])
        g.selectCell(cells[1])
        g.toggleNote(4)
        enterCorrect(g, cells[2])
        assertTrue(g.canUndo)

        g.undo()
        assertNull(g.state.value!!.cells[cells[2]].value)
        assertEquals(setOf(4), g.state.value!!.cells[cells[1]].notes)

        g.undo()
        assertEquals(emptySet<Int>(), g.state.value!!.cells[cells[1]].notes)
        assertEquals(solutionOf(g, cells[0]), g.state.value!!.cells[cells[0]].value)

        g.undo()
        assertNull(g.state.value!!.cells[cells[0]].value)
        assertFalse("nothing left to undo", g.canUndo)
        g.undo() // harmless
        assertNull(g.state.value!!.cells[cells[0]].value)
    }

    @Test
    fun `undo of an erase brings the digit back`() {
        val g = started()
        val cell = whiteCells(g).first()
        enterCorrect(g, cell)
        g.clearValue()
        assertNull(g.state.value!!.cells[cell].value)
        g.undo()
        assertEquals(solutionOf(g, cell), g.state.value!!.cells[cell].value)
    }

    @Test
    fun `a rejected input leaves no undo step`() {
        val g = started()
        g.setValue(5) // nothing selected
        g.clearValue()
        g.toggleNote(2)
        assertFalse(g.canUndo)
    }

    @Test
    fun `undo cannot lower mistakes or hints and does not rewind the clock`() {
        val g = started()
        val cells = whiteCells(g)
        now = 0L
        enter(g, cells[0], wrongDigit(g, cells[0]))
        assertEquals(1, g.state.value!!.mistakes)
        now = 7_000L
        g.undo()
        assertNull(g.state.value!!.cells[cells[0]].value)
        assertEquals("a mistake stays counted", 1, g.state.value!!.mistakes)
        assertEquals("the clock was not rewound", 7_000L, g.activeElapsedMillis())

        g.selectCell(cells[1])
        g.hint()
        assertEquals(1, g.hintsUsed.value)
        g.undo()
        assertNull("the hinted digit is taken back", g.state.value!!.cells[cells[1]].value)
        assertEquals("but the hint stays counted", 1, g.hintsUsed.value)
    }

    @Test
    fun `undo does not reach into a previous puzzle and does nothing after the session ends`() {
        val g = started()
        enterCorrect(g, whiteCells(g).first())
        assertTrue(g.canUndo)
        g.playAgain()
        assertFalse("a new puzzle starts with no history", g.canUndo)

        enterCorrect(g, whiteCells(g).first())
        val afterEntry = g.state.value
        g.leaveSession()
        assertFalse(g.canUndo)
        g.undo()
        assertEquals(afterEntry, g.state.value)
    }

    @Test
    fun `a solved puzzle cannot be undone`() {
        val g = started()
        for (i in whiteCells(g)) enterCorrect(g, i)
        assertTrue(g.state.value!!.won)
        assertFalse(g.canUndo)
        g.undo()
        assertTrue(g.state.value!!.won)
    }

    @Test
    fun `restore clears the undo history`() {
        val a = started()
        enterCorrect(a, whiteCells(a).first())
        val snapshot = a.snapshot()!!
        assertTrue(a.restore(snapshot))
        assertFalse(a.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint fills the selected empty cell with its correct digit`() {
        val g = started()
        val cell = whiteCells(g)[3]
        g.selectCell(cell)
        assertEquals(cell, g.hint())
        assertEquals(solutionOf(g, cell), g.state.value!!.cells[cell].value)
        assertEquals(cell, g.hintCell.value)
        assertEquals(1, g.hintsUsed.value)
        assertEquals("a hint is never a mistake", 0, g.state.value!!.mistakes)
        assertNotNull("the clock starts", g.timerStartElapsedRealtime.value)
    }

    @Test
    fun `hint fixes a selected wrong digit`() {
        val g = started()
        val cell = whiteCells(g)[2]
        enter(g, cell, wrongDigit(g, cell))
        assertEquals(cell, g.hint())
        assertEquals(solutionOf(g, cell), g.state.value!!.cells[cell].value)
    }

    @Test
    fun `without a useful selection hint picks a cell of the run with the fewest unknowns`() {
        val g = started()
        val cells = whiteCells(g)
        val missing = cells[cells.size / 2]
        for (i in cells) if (i != missing) enterCorrect(g, i)
        // Select an already-correct cell: the hint must go to the one missing cell instead.
        g.selectCell(cells.first { it != missing })
        assertEquals(missing, g.hint())
        assertTrue(g.state.value!!.won)
        assertEquals("an assisted solve still counts as solved", 1, g.puzzlesSolved.value)
        assertEquals("and is flagged as assisted", 1, g.hintsUsed.value)
        assertNotNull(g.finishedElapsedMillis.value)
    }

    @Test
    fun `with nothing selected hint still finds the cell that is left`() {
        val g = started()
        val cells = whiteCells(g)
        val missing = cells.last()
        for (i in cells) if (i != missing) enterCorrect(g, i)
        // Selection is on the last cell entered (already correct), so the hint must look at the runs.
        assertEquals(missing, g.hint())
        assertTrue(g.state.value!!.won)
    }

    @Test
    fun `hint is null when it makes no sense and is cleared by the next entry and by undo`() {
        val g = newGame()
        assertNull("no puzzle yet", g.hint())
        g.startMatch(7L)
        val cells = whiteCells(g)

        g.selectCell(cells[0])
        assertEquals(cells[0], g.hint())
        assertEquals(cells[0], g.hintCell.value)
        enterCorrect(g, cells[1])
        assertNull("the next entry clears the hint cue", g.hintCell.value)

        g.selectCell(cells[2])
        g.hint()
        assertNotNull(g.hintCell.value)
        g.undo()
        assertNull("undo clears the hint cue", g.hintCell.value)

        g.hint()
        g.playAgain()
        assertNull("a new puzzle clears the hint cue", g.hintCell.value)
        assertEquals("and resets the per-puzzle hint count", 0, g.hintsUsed.value)

        g.leaveSession()
        assertNull("no hints after the session ends", g.hint())
    }

    @Test
    fun `hint is null on a solved board`() {
        val g = started()
        for (i in whiteCells(g)) enterCorrect(g, i)
        assertNull(g.hint())
        assertEquals(0, g.hintsUsed.value)
    }
}
