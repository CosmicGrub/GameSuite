package com.gamesuite.games.sudoku

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for Sudoku. */
class SudokuResumeUndoHintTest {

    private var clock = 0L
    private val roster = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        players: List<PlayerInfo> = roster,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): SudokuGame {
        val game = SudokuGame(nowMillis = { clock })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(dailySeed: Long? = 42L): SudokuGame = newGame().also { it.startMatch(dailySeed) }

    private fun SudokuGame.st() = state.value!!

    private fun SudokuGame.emptyCells(): List<Int> = st().cells.indices.filter { st().cells[it].value == null }

    private fun wrongDigit(game: SudokuGame, index: Int): Int = game.st().solution[index] % 9 + 1

    private fun enter(game: SudokuGame, index: Int, digit: Int): Boolean {
        game.selectCell(index)
        return game.setValue(digit)
    }

    private fun enterCorrect(game: SudokuGame, index: Int) = enter(game, index, game.st().solution[index])

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
        val empties = a.emptyCells()
        enterCorrect(a, empties[0])
        enter(a, empties[1], wrongDigit(a, empties[1]))
        a.selectCell(empties[2])
        a.toggleNote(3)
        a.toggleNote(5)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.st().solution, b.st().solution)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        assertEquals(a.hintedCells.value, b.hintedCells.value)
        assertFalse(b.matchOver.value)
        assertFalse("a fresh restore has no undo history", b.canUndo)

        enterCorrect(a, empties[3])
        enterCorrect(b, empties[3])
        assertEquals("the same entry must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `tier, daily seed, hints and the solved tally survive a resume`() {
        val a = newGame()
        a.difficulty = CpuDifficulty.HARD
        a.startMatch(1234L)
        // Solve it with hints so puzzlesSolved and hintsUsed are both non-zero.
        a.hint()
        a.emptyCells().forEach { enterCorrect(a, it) }
        assertTrue(a.st().won)
        assertEquals(1, a.puzzlesSolved.value)
        a.playAgain()
        a.hint()

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals("the random puzzle after Play Again has no daily seed", null, b.dailySeedInUse)
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(1, b.hintsUsed.value)

        val c = newGame()
        c.startMatch(99L)
        val d = newGame()
        assertTrue(d.restore(c.snapshot()!!))
        assertEquals(99L, d.dailySeedInUse)
    }

    @Test
    fun `the clock continues from the active elapsed time and an unstarted clock stays unstarted`() {
        clock = 1_000L
        val a = started()
        assertNull(a.timerStartElapsedRealtime.value)
        val fresh = newGame()
        assertTrue(fresh.restore(a.snapshot()!!))
        assertNull("a clock that had not started stays unstarted", fresh.timerStartElapsedRealtime.value)
        assertNull(fresh.activeElapsedMillis())

        enterCorrect(a, a.emptyCells()[0]) // clock starts at 1000
        clock = 5_000L
        val snapshot = a.snapshot()!!
        assertEquals(4_000L, a.activeElapsedMillis())

        clock = 900_000L // the app was gone for a long time
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals("the time away is not counted", 4_000L, b.activeElapsedMillis())
        clock = 900_500L
        assertEquals(4_500L, b.activeElapsedMillis())
    }

    @Test
    fun `a paused clock is stored as active time, not wall time`() {
        clock = 0L
        val a = started()
        enterCorrect(a, a.emptyCells()[0])
        clock = 2_000L
        a.pause()
        clock = 50_000L // backgrounded
        val snapshot = a.snapshot()!!
        val b = newGame()
        clock = 60_000L
        assertTrue(b.restore(snapshot))
        assertEquals(2_000L, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong sized board and leaves the engine untouched`() {
        val a = started()
        enterCorrect(a, a.emptyCells()[0])
        val good = a.snapshot()!!

        val b = started(5L)
        val before = b.state.value
        val solvedBefore = b.puzzlesSolved.value

        assertFalse(b.restore("not json at all"))
        assertFalse(b.restore("{}"))
        assertFalse(b.restore(""))

        val other = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X"))).also { it.startMatch(5L) }
        assertFalse("a different roster is rejected", other.restore(good))

        val obj = Json.parseToJsonElement(good).jsonObject
        val shortCells = JsonArray(obj.getValue("cells").jsonArray.drop(1))
        val wrongSize = JsonObject(obj + ("cells" to shortCells)).toString()
        assertFalse("an 80-cell board is rejected", b.restore(wrongSize))

        val badSelected = JsonObject(obj + ("selectedIndex" to kotlinx.serialization.json.JsonPrimitive(81))).toString()
        assertFalse("an out-of-range selection is rejected", b.restore(badSelected))

        assertEquals("a failed restore leaves the engine untouched", before, b.state.value)
        assertEquals(solvedBefore, b.puzzlesSolved.value)
        assertFalse(b.matchOver.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo goes back one entry at a time`() {
        val game = started()
        assertFalse(game.canUndo)
        val empties = game.emptyCells()
        val start = game.st().cells

        enterCorrect(game, empties[0])
        game.toggleNote(1) // rejected: the cell now holds a value, leaves no step
        game.selectCell(empties[1])
        game.toggleNote(4)
        enterCorrect(game, empties[2])
        assertTrue(game.canUndo)

        game.undo()
        assertNull(game.st().cells[empties[2]].value)
        assertEquals("the earlier note is still there", setOf(4), game.st().cells[empties[1]].notes)
        assertEquals("undo re-selects the cell that was edited", empties[2], game.st().selectedIndex)

        game.undo()
        assertEquals("the note toggle is taken back", emptySet<Int>(), game.st().cells[empties[1]].notes)
        assertNotNull(game.st().cells[empties[0]].value)

        game.undo()
        assertEquals(start, game.st().cells)
        assertFalse(game.canUndo)
        game.undo() // nothing left, must not throw
        assertEquals(start, game.st().cells)
    }

    @Test
    fun `undoing an erase brings the digit back`() {
        val game = started()
        val i = game.emptyCells()[0]
        enterCorrect(game, i)
        assertTrue(game.clearValue())
        assertNull(game.st().cells[i].value)
        game.undo()
        assertEquals(game.st().solution[i], game.st().cells[i].value)
    }

    @Test
    fun `undo never lowers mistakes or hints and never rewinds the clock`() {
        clock = 0L
        val game = started()
        val empties = game.emptyCells()
        enter(game, empties[0], wrongDigit(game, empties[0]))
        assertEquals(1, game.st().mistakes)
        game.hint()
        assertEquals(1, game.hintsUsed.value)
        clock = 7_000L

        game.undo() // takes the hint back
        game.undo() // takes the wrong entry back
        assertNull(game.st().cells[empties[0]].value)
        assertEquals("the mistake stays counted", 1, game.st().mistakes)
        assertEquals("the hint stays counted", 1, game.hintsUsed.value)
        assertEquals("the clock was not rewound", 7_000L, game.activeElapsedMillis())
    }

    @Test
    fun `undo does not cross into a new puzzle or work after the session or the solve`() {
        val game = started()
        enterCorrect(game, game.emptyCells()[0])
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("a new puzzle starts a new history", game.canUndo)

        // Solving closes the history: the solve is already counted and recorded.
        game.emptyCells().forEach { enterCorrect(game, it) }
        assertTrue(game.st().won)
        assertFalse(game.canUndo)
        val solved = game.state.value
        game.undo()
        assertEquals(solved, game.state.value)
        assertEquals(1, game.puzzlesSolved.value)

        val other = started()
        enterCorrect(other, other.emptyCells()[0])
        other.leaveSession()
        assertFalse(other.canUndo)
        val frozen = other.state.value
        other.undo()
        assertEquals(frozen, other.state.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint fills the selected empty cell with its correct digit and marks it`() {
        val game = started()
        val target = game.emptyCells()[5]
        game.selectCell(target)

        assertEquals(target, game.hint())
        assertEquals(game.st().solution[target], game.st().cells[target].value)
        assertEquals(setOf(target), game.hintedCells.value)
        assertEquals(1, game.hintsUsed.value)
        assertEquals("a hint is never a mistake", 0, game.st().mistakes)
        assertTrue("a hint is one undo step", game.canUndo)
    }

    @Test
    fun `without a selection hint picks the empty cell with the fewest candidates`() {
        val game = started()
        val s = game.st()
        fun candidates(i: Int): Int {
            val row = i / 9
            val col = i % 9
            val used = (0 until 81).filter { p ->
                p != i && (p / 9 == row || p % 9 == col || (p / 9 / 3 == row / 3 && p % 9 / 3 == col / 3))
            }.mapNotNull { s.cells[it].value }.toSet()
            return 9 - used.size
        }
        val fewest = game.emptyCells().minOf { candidates(it) }

        val picked = game.hint()!!
        assertEquals("no selection means the easiest cell", fewest, candidates(picked))
        assertNull("it was empty before", s.cells[picked].value)
        assertEquals(s.solution[picked], game.st().cells[picked].value)
    }

    @Test
    fun `an assisted solve still counts as solved and keeps its hint count`() {
        val game = started()
        game.hint()
        game.emptyCells().forEach { enterCorrect(game, it) }
        assertTrue(game.st().won)
        assertEquals(1, game.puzzlesSolved.value)
        assertTrue("the screen reads this to skip the best-time record", game.hintsUsed.value > 0)

        // A clean solve of a fresh puzzle has no hints.
        game.playAgain()
        assertEquals(0, game.hintsUsed.value)
        assertTrue(game.hintedCells.value.isEmpty())
    }

    @Test
    fun `hint is null when the puzzle is solved or the session is over and is cleared by an overwrite`() {
        val game = started()
        val i = game.emptyCells()[0]
        game.selectCell(i)
        game.hint()
        assertTrue(i in game.hintedCells.value)
        // Overwriting the hinted cell removes its mark.
        game.selectCell(i)
        game.setValue(game.st().solution[i])
        assertFalse(i in game.hintedCells.value)

        game.emptyCells().forEach { enterCorrect(game, it) }
        assertTrue(game.st().won)
        assertNull("nothing left to suggest", game.hint())

        val other = started()
        other.leaveSession()
        assertNull(other.hint())
    }

    @Test
    fun `undoing a hint clears its mark and a hint corrects a wrong entry when nothing is empty`() {
        val game = started()
        val i = game.emptyCells()[0]
        game.selectCell(i)
        game.hint()
        game.undo()
        assertNull(game.st().cells[i].value)
        assertTrue(game.hintedCells.value.isEmpty())

        // Fill every empty cell, the first one wrongly: no empty cell is left, so the hint fixes it.
        val empties = game.emptyCells()
        enter(game, empties[0], wrongDigit(game, empties[0]))
        empties.drop(1).forEach { enterCorrect(game, it) }
        assertFalse(game.st().won)
        assertEquals(empties[0], game.hint())
        assertTrue(game.st().won)
    }

    @Test
    fun `a resumed game keeps hinted cells marked`() {
        val a = started()
        a.hint()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.hintedCells.value, b.hintedCells.value)
        assertEquals(1, b.hintsUsed.value)
    }
}
