package com.gamesuite.games.wordgames.crossword

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume (snapshot/restore), Hint and cell selection for Crossword. Puzzle generation is random,
 * so every test reads the generated entries back from the game instead of assuming a layout.
 */
class CrosswordResumeHintTest {

    private val solo = listOf(PlayerInfo("you", "You"))
    private val soloPlusCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        gridSize: Int = 15
    ): CrosswordGame {
        val game = CrosswordGame(gridSize)
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(players: List<PlayerInfo> = solo, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(players, mode).also { it.startMatch() }

    private fun CrosswordGame.entries() = state.value!!.entries

    /** The state with the transient selection cleared: a restore never carries the selected entry. */
    private fun CrosswordGame.persistentState() = state.value!!.copy(selectedEntryId = null)

    private fun CrosswordGame.hiddenCount(): Int =
        state.value!!.grid.sumOf { row -> row.count { it.letter != null && !it.revealed } }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no puzzle yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = started(mode = PlayMode.ONLINE)
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is the same puzzle and plays on identically`() {
        val a = started()
        val first = a.entries().first()
        assertTrue(a.submitAnswer(first.id, first.word))
        a.selectEntry(a.entries().last().id)
        a.revealNextLetter()
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.persistentState(), b.state.value)
        assertEquals(a.hintedCells.value, b.hintedCells.value)
        assertEquals(a.hintsRemaining.value, b.hintsRemaining.value)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)

        // The hidden answers came back too: the same entry solves the same way on both.
        val next = a.entries().first { it.id !in a.state.value!!.solvedEntryIds }
        assertEquals(a.submitAnswer(next.id, next.word), b.submitAnswer(next.id, next.word))
        assertEquals(a.persistentState(), b.persistentState())
        assertFalse("a wrong word is still wrong after a resume", b.submitAnswer(next.id, "ZZZZZZZZZZ"))
    }

    @Test
    fun `a resumed game keeps the solved tally, the hint budget and the difficulty`() {
        val a = newGame()
        a.difficulty = CpuDifficulty.HARD
        a.startMatch()
        // Solve a whole puzzle, then start another so the tally is mid-session.
        a.entries().forEach { assertTrue(a.submitAnswer(it.id, it.word)) }
        assertEquals(1, a.puzzlesSolved.value)
        a.playAgain()
        a.selectEntry(a.entries().first().id)
        a.revealNextLetter()
        val hintsLeft = a.hintsRemaining.value
        assertEquals("HARD allows 2 hints, one spent", 1, hintsLeft)

        val b = newGame()
        b.difficulty = CpuDifficulty.EASY
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(hintsLeft, b.hintsRemaining.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(1, b.hintedCells.value.size)
    }

    @Test
    fun `a finished puzzle can be resumed and still shows as finished`() {
        val a = started()
        a.entries().forEach { a.submitAnswer(it.id, it.word) }
        assertTrue(a.state.value!!.matchOver)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.matchOver)
        assertEquals(1, b.puzzlesSolved.value)
        assertNull("nothing to hint on a finished puzzle", b.hint())
    }

    @Test
    fun `the selection and the flash signals are not carried across a resume`() {
        val a = started()
        a.selectEntry(a.entries().first().id)
        a.revealNextLetter()
        assertNotNull(a.selectedCell.value)
        assertTrue(a.justHintedCells.value.isNotEmpty())

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertNull(b.state.value!!.selectedEntryId)
        assertNull(b.selectedCell.value)
        assertTrue(b.justHintedCells.value.isEmpty())
        assertTrue(b.justSolvedCells.value.isEmpty())
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val game = newGame()
        assertFalse(game.restore("not json at all"))
        assertFalse(game.restore("{}"))
        assertFalse(game.restore(""))
        assertNull("a rejected restore must not generate a puzzle", game.state.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val cpuGame = started(soloPlusCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        val snapshot = cpuGame.snapshot()!!

        val soloGame = newGame(solo)
        assertFalse("a roster with a CPU must not load into a solo game", soloGame.restore(snapshot))
        assertNull(soloGame.state.value)
    }

    @Test
    fun `restore rejects a snapshot with a different grid size`() {
        val big = started()
        val snapshot = big.snapshot()!!

        val small = newGame(gridSize = 11)
        assertFalse(small.restore(snapshot))
        assertNull(small.state.value)
    }

    @Test
    fun `restore rejects a snapshot whose entries do not match its grid`() {
        val a = started()
        val snapshot = a.snapshot()!!
        val word = a.entries().first().word
        // Corrupt one entry's word: its letters no longer match the grid cells it points at.
        val corrupted = snapshot.replace("\"word\":\"$word\"", "\"word\":\"${"Q".repeat(word.length)}\"")
        assertNotEquals("the corruption must actually change the text", snapshot, corrupted)

        val b = newGame()
        assertFalse(b.restore(corrupted))
        assertNull(b.state.value)
    }

    @Test
    fun `a failed restore does not disturb a game that is already running`() {
        val game = started()
        val before = game.state.value
        assertFalse(game.restore("garbage"))
        assertEquals(before, game.state.value)
    }

    // --- Cell selection ---------------------------------------------------------------------

    @Test
    fun `tapping a cell selects an unsolved word through it, and tapping again switches to the crossing word`() {
        val game = started()
        val entries = game.entries()
        assertTrue("a generated puzzle has crossing words", entries.size >= 2)
        // A square shared by exactly one across and one down word.
        val crossing = entries.flatMap { it.cells }.distinct().first { pos ->
            val here = entries.filter { pos in it.cells }
            here.size == 2 && here[0].direction != here[1].direction
        }

        game.selectCell(crossing)
        val firstId = game.state.value!!.selectedEntryId
        assertNotNull(firstId)
        assertEquals(crossing, game.selectedCell.value)
        val firstEntry = entries.first { it.id == firstId }
        assertEquals("across is tried first", Direction.ACROSS, firstEntry.direction)

        game.selectCell(crossing)
        val secondId = game.state.value!!.selectedEntryId
        assertNotEquals("the second tap switches word", firstId, secondId)
        assertTrue(crossing in entries.first { it.id == secondId }.cells)

        game.selectCell(crossing)
        assertEquals("and a third tap comes back", firstId, game.state.value!!.selectedEntryId)
    }

    @Test
    fun `tapping an empty cell or a solved word changes nothing`() {
        val game = started()
        val grid = game.state.value!!.grid
        val blank = (grid.indices).flatMap { r -> grid[r].indices.map { c -> CrosswordPos(r, c) } }
            .first { grid[it.row][it.col].letter == null }
        game.selectCell(blank)
        assertNull(game.state.value!!.selectedEntryId)
        assertNull(game.selectedCell.value)

        // A cell that belongs only to solved words cannot be selected.
        val entry = game.entries().first()
        game.submitAnswer(entry.id, entry.word)
        val onlySolved = entry.cells.firstOrNull { pos ->
            game.entries().filter { pos in it.cells }.all { it.id in game.state.value!!.solvedEntryIds }
        }
        if (onlySolved != null) {
            game.selectCell(onlySolved)
            assertNull(game.state.value!!.selectedEntryId)
        }
    }

    @Test
    fun `selecting a clue parks the cursor on its first open square`() {
        val game = started()
        val entry = game.entries().first()
        game.selectEntry(entry.id)
        assertEquals(entry.cells.first(), game.selectedCell.value)
        assertEquals(entry.id, game.state.value!!.selectedEntryId)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint is null before the first puzzle`() {
        val game = newGame()
        assertNull(game.hint())
        assertFalse(game.canHint)
    }

    @Test
    fun `hint reveals the selected square, spends one hint and marks the square as given`() {
        val game = started()
        val entry = game.entries().first { it.word.length >= 3 }
        game.selectEntry(entry.id)
        val target = entry.cells[1]
        game.selectCell(target)
        // selectCell may have switched to a crossing word; re-select the intended word's cursor.
        if (game.state.value!!.selectedEntryId != entry.id) game.selectCell(target)
        assertEquals(entry.id, game.state.value!!.selectedEntryId)
        val budget = game.hintsRemaining.value
        val hiddenBefore = game.hiddenCount()

        val revealed = game.hint()

        assertEquals(target, revealed)
        assertTrue(game.state.value!!.grid[target.row][target.col].revealed)
        assertEquals(budget - 1, game.hintsRemaining.value)
        assertEquals(hiddenBefore - 1, game.hiddenCount())
        assertTrue(target in game.hintedCells.value)
        assertEquals(setOf(target), game.justHintedCells.value)
        assertEquals("the answer is not marked solved by a hint alone", false, entry.id in game.state.value!!.solvedEntryIds)
    }

    @Test
    fun `hint with nothing selected opens the lowest numbered unsolved word`() {
        val game = started()
        val expected = game.entries().sortedBy { it.number }.first()
        val revealed = game.hint()
        assertNotNull(revealed)
        assertEquals(expected.id, game.state.value!!.selectedEntryId)
        assertEquals(expected.cells.first(), revealed)
    }

    @Test
    fun `hint moves to the next hidden square when the selected one is already shown`() {
        val game = started()
        val entry = game.entries().first()
        game.selectEntry(entry.id)
        val first = game.hint()
        assertEquals(entry.cells.first(), first)
        // The cursor now sits on the revealed square; the next hint takes the next hidden one.
        val second = game.hint()
        assertNotNull(second)
        assertNotEquals(first, second)
        assertTrue(second in entry.cells)
    }

    @Test
    fun `hint is null once the budget is spent, and canHint says so`() {
        val game = started()
        game.selectEntry(game.entries().maxByOrNull { it.word.length }!!.id)
        var guard = 0
        while (game.canHint && guard++ < 20) game.hint()
        assertEquals(0, game.hintsRemaining.value)
        assertFalse(game.canHint)
        val hidden = game.hiddenCount()
        assertNull(game.hint())
        assertEquals("a refused hint reveals nothing", hidden, game.hiddenCount())
    }

    @Test
    fun `hint is null on a finished puzzle and after the session ends`() {
        val done = started()
        done.entries().forEach { done.submitAnswer(it.id, it.word) }
        assertNull(done.hint())
        assertFalse(done.canHint)

        val left = started()
        left.leaveSession()
        assertNull(left.hint())
        left.selectCell(left.entries().first().cells.first())
        assertNull("input is ignored after the session ends", left.state.value!!.selectedEntryId)
        val e = left.entries().first()
        assertFalse(left.submitAnswer(e.id, e.word))
    }

    @Test
    fun `hinted squares survive a resume and are cleared by a new puzzle`() {
        val a = started()
        a.selectEntry(a.entries().first().id)
        val pos = a.hint()!!

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(setOf(pos), b.hintedCells.value)

        b.playAgain()
        assertTrue("a new puzzle starts with no given letters", b.hintedCells.value.isEmpty())
        assertNull(b.selectedCell.value)
    }

    @Test
    fun `the dialog hint and the menu hint share one budget`() {
        val game = started()
        val entry = game.entries().first()
        game.selectEntry(entry.id)
        val budget = game.hintsRemaining.value
        game.revealNextLetter()
        game.hint()
        assertEquals(budget - 2, game.hintsRemaining.value)
        assertEquals(2, game.hintedCells.value.size)
    }
}
