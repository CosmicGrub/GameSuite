package com.gamesuite.games.kenken

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for KenKen, written against the Connect Four reference. */
class KenKenResumeUndoHintTest {

    private class Clock(var now: Long = 0L)

    private val soloPlayer = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: Clock = Clock(),
        difficulty: CpuDifficulty = CpuDifficulty.EASY,
        players: List<PlayerInfo> = soloPlayer,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): KenKenGame {
        val game = KenKenGame(nowMillis = { clock.now })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = difficulty
        return game
    }

    private fun started(clock: Clock = Clock(), difficulty: CpuDifficulty = CpuDifficulty.EASY): KenKenGame =
        newGame(clock, difficulty).also { it.startMatch() }

    private fun KenKenGame.board(): KenKenState = state.value!!

    private fun KenKenGame.enter(index: Int, value: Int) {
        selectCell(index)
        setValue(value)
    }

    private fun KenKenGame.enterCorrect(index: Int) = enter(index, board().solution[index])

    private fun KenKenGame.wrongDigitFor(index: Int): Int {
        val s = board()
        return (s.solution[index] % s.size) + 1
    }

    private fun KenKenGame.solveAll() {
        for (i in board().cells.indices) enterCorrect(i)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no puzzle dealt yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch() }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        a.enterCorrect(0)
        a.selectCell(1)
        a.toggleNote(1)
        a.toggleNote(2)
        a.enter(2, a.wrongDigitFor(2))
        assertEquals(1, a.board().mistakes)
        a.selectCell(5)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.board(), b.board())
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        assertEquals(a.difficulty, b.difficulty)
        assertEquals(a.dailySeedInUse, b.dailySeedInUse)
        assertNull(b.hintCell.value)
        assertFalse(b.matchOver.value)

        a.enterCorrect(3)
        b.enterCorrect(3)
        assertEquals("the same move must lead to the same position", a.board(), b.board())
    }

    @Test
    fun `the tier and hidden solution come back with the snapshot, not from the receiving game`() {
        val a = started(difficulty = CpuDifficulty.MEDIUM)
        a.enterCorrect(0)
        val snapshot = a.snapshot()!!

        val b = newGame(difficulty = CpuDifficulty.EASY).also { it.startMatch() }
        assertTrue(b.restore(snapshot))
        assertEquals(CpuDifficulty.MEDIUM, b.difficulty)
        assertEquals(6, b.board().size)
        assertEquals(a.board().solution, b.board().solution)
        assertEquals(a.board().cages, b.board().cages)
    }

    @Test
    fun `the clock continues from the active elapsed time and ignores the time the app was gone`() {
        val clockA = Clock(1_000L)
        val a = started(clockA)
        a.enterCorrect(0) // the stopwatch starts at 1000
        clockA.now = 6_000L
        val snapshot = a.snapshot()!!

        val clockB = Clock(100_000L)
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals(5_000L, b.activeElapsedMillis())
        clockB.now = 102_000L
        assertEquals(7_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a paused clock is saved as the time up to the pause`() {
        val clockA = Clock(0L)
        val a = started(clockA)
        a.enterCorrect(0)
        clockA.now = 4_000L
        a.pause()
        clockA.now = 90_000L // the app sits in the background
        val snapshot = a.snapshot()!!

        val clockB = Clock(500_000L)
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals(4_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a clock that had not started stays unstarted`() {
        val a = started()
        val snapshot = a.snapshot()!!
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertNull(b.timerStartElapsedRealtime.value)
        assertNull(b.activeElapsedMillis())
    }

    @Test
    fun `the session tally, hints and the daily seed survive`() {
        val a = newGame()
        a.startMatch(dailySeed = 4242L)
        a.solveAll()
        assertEquals(1, a.puzzlesSolved.value)
        a.playAgain()
        assertNull("a random follow-up puzzle drops the daily tag", a.dailySeedInUse)
        a.startMatch(dailySeed = 4242L)
        a.selectCell(0)
        a.hint()
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(1, b.hintsUsed.value)
        assertEquals(4242L, b.dailySeedInUse)
        assertTrue(b.isAssisted)
    }

    @Test
    fun `a solved board restores as solved with its frozen time`() {
        val clock = Clock(0L)
        val a = started(clock)
        a.enterCorrect(0)
        clock.now = 3_000L
        for (i in 1 until a.board().cells.size) a.enterCorrect(i)
        assertTrue(a.board().won)
        val frozen = a.finishedElapsedMillis.value
        assertNotNull(frozen)
        val snapshot = a.snapshot()!!

        val b = newGame(Clock(777_000L))
        assertTrue(b.restore(snapshot))
        assertTrue(b.board().won)
        assertEquals(frozen, b.finishedElapsedMillis.value)
        assertEquals(frozen, b.activeElapsedMillis())
        assertEquals(1, b.puzzlesSolved.value)
    }

    @Test
    fun `restore rejects garbage, a wrong roster, a wrong board size and a wrong tier, leaving the engine untouched`() {
        val a = started()
        a.enterCorrect(0)
        val good = a.snapshot()!!

        val b = started()
        b.enterCorrect(1)
        val before = b.board()
        val tierBefore = b.difficulty

        fun assertRejected(text: String) {
            assertFalse(b.restore(text))
            assertSame("a rejected snapshot must not touch the board", before, b.board())
            assertEquals(tierBefore, b.difficulty)
            assertEquals(0, b.puzzlesSolved.value)
        }

        assertRejected("not json at all")
        assertRejected("{}")
        assertRejected("")

        val resized = good.replaceFirst("\"size\":4", "\"size\":6")
        assertNotEquals(good, resized)
        assertRejected(resized)

        val retiered = good.replaceFirst("\"difficulty\":\"EASY\"", "\"difficulty\":\"MEDIUM\"")
        assertNotEquals(good, retiered)
        assertRejected(retiered)

        val otherRoster = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "Else")))
        otherRoster.startMatch()
        val otherBefore = otherRoster.board()
        assertFalse(otherRoster.restore(good))
        assertSame(otherBefore, otherRoster.board())

        val twoPlayers = newGame(players = soloPlayer + PlayerInfo(playerId = "p2", displayName = "Player 2"))
        assertFalse(twoPlayers.restore(good))
        assertNull(twoPlayers.state.value)
    }

    @Test
    fun `restore rejects a snapshot whose clues do not match its solution`() {
        val a = started()
        val good = a.snapshot()!!
        // Corrupt the first cage's target to a value no solution can give.
        val corrupted = good.replaceFirst(Regex("\"target\":\\d+"), "\"target\":99")
        assertNotEquals(good, corrupted)
        assertFalse(newGame().restore(corrupted))
    }

    @Test
    fun `restore clears the undo history and any hint mark`() {
        val a = started()
        a.enterCorrect(0)
        val snapshot = a.snapshot()!!

        val b = started()
        b.enterCorrect(0)
        b.hint()
        assertTrue(b.canUndo)
        assertNotNull(b.hintCell.value)
        assertTrue(b.restore(snapshot))
        assertFalse(b.canUndo)
        assertNull(b.hintCell.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one entry or note at a time`() {
        val g = started()
        assertFalse(g.canUndo)

        g.enterCorrect(0)
        g.selectCell(1)
        g.toggleNote(2)
        g.enterCorrect(2)
        assertTrue(g.canUndo)

        g.undo()
        assertNull(g.board().cells[2].value)
        g.undo()
        assertTrue(g.board().cells[1].notes.isEmpty())
        assertNotNull(g.board().cells[0].value)
        g.undo()
        assertNull(g.board().cells[0].value)
        assertFalse(g.canUndo)

        val blank = g.board()
        g.undo()
        assertEquals("undo with nothing to take back changes nothing", blank, g.board())
    }

    @Test
    fun `a rejected input leaves no undo step`() {
        val g = started()
        g.selectCell(0)
        g.setValue(99) // out of range
        g.toggleNote(99)
        g.clearValue() // nothing to clear
        assertFalse(g.canUndo)
    }

    @Test
    fun `undoing an erase brings the number back`() {
        val g = started()
        g.enterCorrect(0)
        g.clearValue()
        assertNull(g.board().cells[0].value)
        g.undo()
        assertNotNull(g.board().cells[0].value)
    }

    @Test
    fun `undo cannot lower mistakes or hints and does not rewind the clock`() {
        val clock = Clock(1_000L)
        val g = started(clock)
        g.enter(0, g.wrongDigitFor(0))
        assertEquals(1, g.board().mistakes)
        clock.now = 5_000L
        g.selectCell(1)
        g.hint()
        assertEquals(1, g.hintsUsed.value)

        g.undo() // the hint
        assertNull(g.board().cells[1].value)
        assertEquals(1, g.hintsUsed.value)
        g.undo() // the wrong entry
        assertNull(g.board().cells[0].value)
        assertEquals("a mistake stays counted", 1, g.board().mistakes)
        assertEquals("a hint stays counted", 1, g.hintsUsed.value)
        assertEquals("the clock keeps running", 4_000L, g.activeElapsedMillis())
    }

    @Test
    fun `a new puzzle starts with no undo history`() {
        val g = started()
        g.enterCorrect(0)
        assertTrue(g.canUndo)
        g.playAgain()
        assertFalse(g.canUndo)
        val fresh = g.board()
        g.undo()
        assertEquals(fresh, g.board())
        g.enterCorrect(0)
        g.startMatch()
        assertFalse(g.canUndo)
    }

    @Test
    fun `undo does nothing once the puzzle is solved or the session has ended`() {
        val g = started()
        g.solveAll()
        assertTrue(g.board().won)
        assertFalse(g.canUndo)
        val solved = g.board()
        g.undo()
        assertEquals(solved, g.board())
        assertEquals(1, g.puzzlesSolved.value)

        val h = started()
        h.enterCorrect(0)
        h.leaveSession()
        assertFalse(h.canUndo)
        val ended = h.board()
        h.undo()
        assertEquals(ended, h.board())
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint fills the selected empty cell with its correct value`() {
        val g = started()
        g.selectCell(5)
        val filled = g.hint()
        assertEquals(5, filled)
        assertEquals(g.board().solution[5], g.board().cells[5].value)
        assertEquals(1, g.hintsUsed.value)
        assertEquals(5, g.hintCell.value)
        assertEquals(0, g.board().mistakes)
        assertTrue(g.isAssisted)
        assertNotNull("the clock starts with the first fill", g.timerStartElapsedRealtime.value)
    }

    @Test
    fun `without a usable selection hint picks an empty cell of the most constrained cage`() {
        val g = started()
        // Fill all but one cell of a multi-cell cage so that cage is the most constrained one.
        val cage = g.board().cages.first { it.cellIndices.size >= 2 }
        for (i in cage.cellIndices.dropLast(1)) g.enterCorrect(i)
        g.selectCell(cage.cellIndices.first()) // selected, but already filled

        val s = g.board()
        val minEmpty = s.cages.map { c -> c.cellIndices.count { s.cells[it].value == null } }.filter { it > 0 }.min()
        val filled = g.hint()!!
        val filledCage = s.cages.first { filled in it.cellIndices }
        assertNull(s.cells[filled].value)
        assertEquals(minEmpty, filledCage.cellIndices.count { s.cells[it].value == null })
        assertEquals(g.board().solution[filled], g.board().cells[filled].value)
    }

    @Test
    fun `hint is null before the first puzzle, once solved and after the session ends`() {
        assertNull(newGame().hint())

        val g = started()
        g.solveAll()
        assertTrue(g.board().won)
        assertNull(g.hint())
        assertEquals(0, g.hintsUsed.value)

        val h = started()
        h.leaveSession()
        assertNull(h.hint())
    }

    @Test
    fun `the next entry, undo and a new puzzle clear the hint mark`() {
        val g = started()
        g.selectCell(0)
        g.hint()
        assertNotNull(g.hintCell.value)
        g.enterCorrect(1)
        assertNull("the next entry clears the mark", g.hintCell.value)

        g.selectCell(2)
        g.hint()
        assertNotNull(g.hintCell.value)
        g.undo()
        assertNull("undo clears the mark", g.hintCell.value)

        g.selectCell(3)
        g.hint()
        g.playAgain()
        assertNull("a new puzzle clears the mark", g.hintCell.value)
        assertEquals("and the hint count", 0, g.hintsUsed.value)
    }

    @Test
    fun `undoing a hint empties the cell again but the hint stays counted`() {
        val g = started()
        g.selectCell(4)
        g.hint()
        assertNotNull(g.board().cells[4].value)
        g.undo()
        assertNull(g.board().cells[4].value)
        assertEquals(1, g.hintsUsed.value)
        assertTrue(g.isAssisted)
    }

    @Test
    fun `a puzzle finished with hints counts as solved but is assisted`() {
        val g = started()
        var guard = 0
        while (!g.board().won && guard++ < 100) g.hint()
        assertTrue(g.board().won)
        assertEquals(1, g.puzzlesSolved.value)
        assertTrue("the screen must not record a best time for it", g.isAssisted)
        assertEquals(g.board().cells.size, g.hintsUsed.value)
        assertNotNull(g.finishedElapsedMillis.value)
    }

    @Test
    fun `a puzzle finished without hints is not assisted`() {
        val g = started()
        g.solveAll()
        assertTrue(g.board().won)
        assertFalse(g.isAssisted)
        assertEquals(0, g.hintsUsed.value)
    }

    @Test
    fun `a full board with one wrong number gets that cell corrected by hint`() {
        val g = started()
        val size = g.board().cells.size
        for (i in 0 until size - 1) g.enterCorrect(i)
        g.enter(size - 1, g.wrongDigitFor(size - 1))
        assertFalse(g.board().won)

        g.selectCell(0) // a filled cell: no empty cell is left anywhere
        val fixed = g.hint()
        assertEquals(size - 1, fixed)
        assertTrue(g.board().won)
        assertTrue(g.isAssisted)
    }

    @Test
    fun `the hint count survives a resume and the next puzzle starts it again at zero`() {
        val a = started()
        a.selectCell(0)
        a.hint()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.hintsUsed.value)
        b.playAgain()
        assertEquals(0, b.hintsUsed.value)
        assertFalse(b.isAssisted)
    }
}
