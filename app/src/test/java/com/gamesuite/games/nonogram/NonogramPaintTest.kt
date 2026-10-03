package com.gamesuite.games.nonogram

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fill/Cross stroke API ([NonogramGame.beginStroke] / [NonogramGame.dragStrokeTo] /
 * [NonogramGame.endStroke] / [NonogramGame.toggleCell]), [NonogramGame.undo],
 * [NonogramGame.restartPuzzle] and [NonogramGame.activeElapsedMillis]. Every puzzle is EASY (5x5)
 * from a fixed daily seed; assertions that depend on which squares the solution fills are computed
 * from the state's own `solution` rather than hard-coded, so none of them is tied to one seed.
 */
class NonogramPaintTest {

    private fun newGame(nowMillis: () -> Long): NonogramGame {
        val game = NonogramGame(nowMillis = nowMillis)
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = 1L)
        return game
    }

    private fun startedGame(): NonogramGame {
        var fakeClock = 0L
        return newGame { fakeClock++ }
    }

    private fun cells(game: NonogramGame): List<NonogramCellState> = game.state.value!!.cells

    private fun cellAt(game: NonogramGame, row: Int, col: Int): NonogramCellState {
        val s = game.state.value!!
        return s.cells[row * s.size + col]
    }

    /** A row whose solution has at least one empty square, so filling that whole row can never solve the board. */
    private fun rowWithAnEmptySquare(game: NonogramGame): Int {
        val s = game.state.value!!
        return (0 until s.size).first { r -> (0 until s.size).any { c -> !s.solution[r * s.size + c] } }
    }

    /** Solves the board by toggling exactly the squares that differ from the solution. */
    private fun solve(game: NonogramGame) {
        val size = game.state.value!!.size
        for (i in 0 until size * size) {
            val s = game.state.value!!
            val filled = s.cells[i] == NonogramCellState.FILLED
            if (s.solution[i] != filled) game.toggleCell(i, NonogramTool.FILL)
        }
    }

    @Test
    fun `a FILL tap fills an empty square and a second FILL tap erases it`() {
        val game = startedGame()
        assertTrue(game.toggleCell(0, NonogramTool.FILL))
        assertEquals(NonogramCellState.FILLED, cells(game)[0])
        assertTrue(game.toggleCell(0, NonogramTool.FILL))
        assertEquals(NonogramCellState.UNDETERMINED, cells(game)[0])
    }

    @Test
    fun `a CROSS tap marks an X, a second CROSS tap clears it, and CROSS over a fill replaces it`() {
        val game = startedGame()
        assertTrue(game.toggleCell(0, NonogramTool.CROSS))
        assertEquals(NonogramCellState.MARKED_EMPTY, cells(game)[0])
        assertTrue(game.toggleCell(0, NonogramTool.CROSS))
        assertEquals(NonogramCellState.UNDETERMINED, cells(game)[0])

        game.toggleCell(1, NonogramTool.FILL)
        assertEquals(NonogramCellState.FILLED, cells(game)[1])
        assertTrue(game.toggleCell(1, NonogramTool.CROSS))
        assertEquals("a CROSS tap on a fill replaces it", NonogramCellState.MARKED_EMPTY, cells(game)[1])
    }

    @Test
    fun `a FILL tap on an X replaces it with a fill`() {
        val game = startedGame()
        game.toggleCell(0, NonogramTool.CROSS)
        assertTrue(game.toggleCell(0, NonogramTool.FILL))
        assertEquals(NonogramCellState.FILLED, cells(game)[0])
    }

    @Test
    fun `a horizontal drag paints every square it passes and then stays on the start row`() {
        val game = startedGame()
        val s = game.state.value!!
        val row = rowWithAnEmptySquare(game)
        val last = s.size - 1

        assertTrue(game.beginStroke(row * s.size, NonogramTool.FILL))
        // One call that jumps four squares: nothing in between may be skipped.
        assertTrue(game.dragStrokeTo(row, 3))
        for (c in 0..3) assertEquals("col $c", NonogramCellState.FILLED, cellAt(game, row, c))
        assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, row, last))

        // The stroke is now locked to the row: a finger far below it paints nothing off that row.
        val otherRow = if (row == 0) s.size - 1 else 0
        game.dragStrokeTo(otherRow, 3)
        for (c in 0 until s.size) assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, otherRow, c))

        assertTrue(game.dragStrokeTo(otherRow, last))
        assertEquals(NonogramCellState.FILLED, cellAt(game, row, last))
        game.endStroke()
    }

    @Test
    fun `a vertical drag locks to the start column`() {
        val game = startedGame()
        val s = game.state.value!!
        assertTrue(game.beginStroke(0 * s.size + 2, NonogramTool.FILL)) // row 0, col 2
        assertTrue(game.dragStrokeTo(3, 2))
        for (r in 0..3) assertEquals("row $r", NonogramCellState.FILLED, cellAt(game, r, 2))

        // Sliding sideways after the lock paints nothing outside column 2.
        game.dragStrokeTo(3, 0)
        for (r in 0 until s.size) {
            assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, r, 0))
        }
        game.endStroke()
    }

    @Test
    fun `a diagonal first move is a tie and goes to the row`() {
        val game = startedGame()
        val s = game.state.value!!
        game.beginStroke(2 * s.size + 2, NonogramTool.CROSS) // row 2, col 2
        assertTrue(game.dragStrokeTo(3, 3))
        assertEquals(NonogramCellState.MARKED_EMPTY, cellAt(game, 2, 3))
        assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, 3, 2))
        assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, 3, 3))
        game.endStroke()
    }

    @Test
    fun `a drag past the board edge clamps onto the edge square`() {
        val game = startedGame()
        val s = game.state.value!!
        val row = rowWithAnEmptySquare(game)
        game.beginStroke(row * s.size, NonogramTool.FILL)
        assertTrue(game.dragStrokeTo(row, 99))
        for (c in 0 until s.size) assertEquals("col $c", NonogramCellState.FILLED, cellAt(game, row, c))
        // And back out past the start edge: the stroke just walks back along the row.
        assertFalse(game.dragStrokeTo(row, -5))
        game.endStroke()
    }

    @Test
    fun `a paint stroke leaves existing marks alone and an erase stroke clears only matching marks`() {
        val game = startedGame()
        val s = game.state.value!!
        val row = rowWithAnEmptySquare(game)
        val base = row * s.size
        game.toggleCell(base + 1, NonogramTool.CROSS) // an X the player already placed
        game.toggleCell(base + 3, NonogramTool.FILL)  // a fill the player already placed

        // PAINT: starts on an empty square, so it paints.
        assertTrue(game.beginStroke(base + 0, NonogramTool.FILL))
        game.dragStrokeTo(row, s.size - 1)
        game.endStroke()
        assertEquals(NonogramCellState.FILLED, cellAt(game, row, 0))
        assertEquals("the X is not overwritten", NonogramCellState.MARKED_EMPTY, cellAt(game, row, 1))
        assertEquals(NonogramCellState.FILLED, cellAt(game, row, 2))
        assertEquals(NonogramCellState.FILLED, cellAt(game, row, 3))
        assertEquals(NonogramCellState.FILLED, cellAt(game, row, 4))

        // ERASE: starts on a fill, so it erases fills and leaves the X.
        assertTrue(game.beginStroke(base + 0, NonogramTool.FILL))
        game.dragStrokeTo(row, s.size - 1)
        game.endStroke()
        assertEquals("the X survives an erase of fills", NonogramCellState.MARKED_EMPTY, cellAt(game, row, 1))
        for (c in listOf(0, 2, 3, 4)) assertEquals("col $c", NonogramCellState.UNDETERMINED, cellAt(game, row, c))
    }

    @Test
    fun `a stroke is one undo step and undo restores the board it started from`() {
        val game = startedGame()
        val s = game.state.value!!
        val before = cells(game)
        assertEquals(0, game.undoDepth.value)

        game.beginStroke(0, NonogramTool.CROSS)
        game.dragStrokeTo(0, 3)
        game.endStroke()
        assertEquals("a whole drag is one undo step", 1, game.undoDepth.value)

        game.toggleCell(s.size, NonogramTool.CROSS)
        assertEquals(2, game.undoDepth.value)

        assertTrue(game.undo())
        assertEquals(1, game.undoDepth.value)
        assertEquals(NonogramCellState.UNDETERMINED, cellAt(game, 1, 0))
        assertEquals(NonogramCellState.MARKED_EMPTY, cellAt(game, 0, 3))

        assertTrue(game.undo())
        assertEquals(before, cells(game))
        assertEquals(0, game.undoDepth.value)
        assertFalse("nothing left to undo", game.undo())
    }

    @Test
    fun `tapCell is its own undo step`() {
        val game = startedGame()
        game.tapCell(0)
        assertEquals(1, game.undoDepth.value)
        assertTrue(game.undo())
        assertEquals(NonogramCellState.UNDETERMINED, cells(game)[0])
    }

    @Test
    fun `a stroke that changes nothing opens no undo step and does not start the clock`() {
        val game = startedGame()
        val size = game.state.value!!.size
        assertFalse(game.beginStroke(size * size, NonogramTool.FILL)) // out of range
        assertFalse(game.beginStroke(-1, NonogramTool.FILL))
        assertFalse("no stroke is open", game.dragStrokeTo(0, 1))
        game.endStroke()
        game.endStroke() // safe to repeat
        assertEquals(0, game.undoDepth.value)
        assertNull(game.activeElapsedMillis())
    }

    @Test
    fun `undo does not roll back the mistake count`() {
        val game = startedGame()
        val s = game.state.value!!
        val wrong = s.solution.indexOfFirst { !it }

        game.toggleCell(wrong, NonogramTool.FILL)
        assertEquals(1, game.state.value!!.mistakes)
        assertTrue(game.undo())
        assertEquals(NonogramCellState.UNDETERMINED, cells(game)[wrong])
        assertEquals("the running mistake total never decrements", 1, game.state.value!!.mistakes)

        game.toggleCell(wrong, NonogramTool.FILL)
        assertEquals("placing the same wrong fill again is a new mistake", 2, game.state.value!!.mistakes)
    }

    @Test
    fun `dragging over a square that is already a wrong fill does not count it again`() {
        val game = startedGame()
        val s = game.state.value!!
        val n = s.size
        // A row r with a wrong square at some column c >= 1 (so column 0 is a clean place to start).
        val (row, col) = (0 until n).flatMap { r -> (1 until n).map { c -> r to c } }
            .first { (r, c) -> !s.solution[r * n + c] }

        game.toggleCell(row * n + col, NonogramTool.FILL)
        assertEquals(1, game.state.value!!.mistakes)

        game.beginStroke(row * n, NonogramTool.FILL)
        game.dragStrokeTo(row, n - 1)
        game.endStroke()

        // Every empty square of this row ends up filled exactly once, the earlier one included.
        val emptySquaresInRow = (0 until n).count { c -> !s.solution[row * n + c] }
        assertEquals(emptySquaresInRow, game.state.value!!.mistakes)
        assertFalse("a row with a wrong fill in it cannot be a solved board", game.state.value!!.won)
    }

    @Test
    fun `strokes and undo are rejected once the board is solved`() {
        val game = startedGame()
        solve(game)
        val solved = game.state.value!!
        assertTrue(solved.won)
        assertEquals(1, game.puzzlesSolved.value)

        assertFalse(game.beginStroke(0, NonogramTool.CROSS))
        assertFalse(game.toggleCell(0, NonogramTool.FILL))
        assertFalse(game.dragStrokeTo(0, 1))
        assertFalse("undo of a solved board would un-solve it", game.undo())
        assertEquals(solved, game.state.value)
        assertEquals("a solve is counted exactly once", 1, game.puzzlesSolved.value)
    }

    @Test
    fun `strokes undo and restart are rejected once the session has ended`() {
        val game = startedGame()
        game.toggleCell(0, NonogramTool.FILL)
        game.leaveSession()
        assertTrue(game.matchOver.value)
        val atLeave = game.state.value

        assertFalse(game.beginStroke(1, NonogramTool.FILL))
        assertFalse(game.toggleCell(2, NonogramTool.FILL))
        assertFalse(game.undo())
        game.restartPuzzle()
        assertEquals("the board is untouched after the session ended", atLeave, game.state.value)
    }

    @Test
    fun `restartPuzzle clears the marks, mistakes, history and stopwatch of an unsolved board`() {
        var clock = 0L
        val game = newGame { clock }
        val s = game.state.value!!
        val wrong = s.solution.indexOfFirst { !it }

        clock = 500L
        game.toggleCell(wrong, NonogramTool.FILL) // a mistake, and the clock starts
        game.toggleCell(0, NonogramTool.CROSS)
        assertTrue(game.state.value!!.mistakes > 0)

        game.restartPuzzle()
        val after = game.state.value!!
        assertTrue(after.cells.all { it == NonogramCellState.UNDETERMINED })
        assertEquals(0, after.mistakes)
        assertFalse(after.won)
        assertEquals(s.solution, after.solution)
        assertEquals(0, game.undoDepth.value)
        assertNull(game.timerStartElapsedRealtime.value)
        assertNull(game.activeElapsedMillis())

        // And it is still a playable board.
        assertTrue(game.toggleCell(0, NonogramTool.FILL))
    }

    @Test
    fun `restartPuzzle does nothing to a solved board`() {
        val game = startedGame()
        solve(game)
        val solved = game.state.value
        game.restartPuzzle()
        assertEquals(solved, game.state.value)
        assertEquals(1, game.puzzlesSolved.value)
    }

    @Test
    fun `a new puzzle starts with an empty undo history`() {
        val game = startedGame()
        game.toggleCell(0, NonogramTool.FILL)
        assertEquals(1, game.undoDepth.value)
        game.playAgain()
        assertEquals(0, game.undoDepth.value)
        assertFalse(game.undo())
    }

    @Test
    fun `activeElapsedMillis is null before the first mark, freezes while paused, and ends equal to the finished time`() {
        var clock = 0L
        val game = newGame { clock }
        assertNull("no stopwatch before the first mark", game.activeElapsedMillis())

        clock = 1_000L
        game.toggleCell(0, NonogramTool.CROSS) // starts the timer at t=1000
        assertEquals(0L, game.activeElapsedMillis())

        clock = 3_000L
        assertEquals(2_000L, game.activeElapsedMillis())

        game.pause() // pausedAt = 3000
        clock = 10_000L
        assertEquals("the live clock must not tick while paused", 2_000L, game.activeElapsedMillis())

        game.resume() // 7000ms spent paused
        clock = 11_000L
        assertEquals(3_000L, game.activeElapsedMillis()) // 11000 - 1000 - 7000

        solve(game)
        assertTrue(game.state.value!!.won)
        assertEquals(3_000L, game.finishedElapsedMillis.value)
        assertEquals(
            "once solved, the live clock reads exactly the recorded solve time",
            game.finishedElapsedMillis.value,
            game.activeElapsedMillis()
        )
    }
}
