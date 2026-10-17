package com.gamesuite.games.minesweeper

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
 * Resume (snapshot/restore) and Hint for Minesweeper. There is deliberately no Undo: taking back a
 * reveal would show where the mines are. Uses an injected clock so timer behaviour is exact.
 */
class MinesweeperResumeHintTest {

    private var clock = 0L

    private val solo = listOf(PlayerInfo("p1", "Player 1"))

    private fun ctx(players: List<PlayerInfo> = solo, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT) =
        GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport())

    private fun newGame(
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): MinesweeperGame {
        val game = MinesweeperGame(nowMillis = { clock })
        game.init(ctx(players, mode))
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private fun started(seed: Long? = null): MinesweeperGame = newGame().also { it.startMatch(seed) }

    /** A daily seed whose first reveal at cell 0 leaves the EASY board still live. */
    private fun liveSeed(): Long = (1L..200L).first { seed ->
        val probe = newGame()
        probe.startMatch(seed)
        probe.revealCell(0)
        !probe.state.value!!.isOver
    }

    private fun liveGame(): MinesweeperGame {
        val game = started(liveSeed())
        game.revealCell(0)
        return game
    }

    private fun winBoard(game: MinesweeperGame) {
        val s = game.state.value!!
        for (i in s.cells.indices) if (!s.cells[i].isMine) game.revealCell(i)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first board, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no board dealt yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch() }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored board is identical and plays on identically`() {
        val a = liveGame()
        val open = a.state.value!!.cells.indexOfFirst { it.state == CellState.HIDDEN && !it.isMine }
        val flagTarget = a.state.value!!.cells.indexOfFirst { it.state == CellState.HIDDEN && it.isMine }
        a.toggleFlag(flagTarget)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.boardNumber.value, b.boardNumber.value)
        assertEquals(a.isDailyBoard, b.isDailyBoard)
        assertEquals(a.difficulty, b.difficulty)

        a.revealCell(open)
        b.revealCell(open)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `a board with no mines placed yet resumes onto the same daily deal`() {
        val a = started(seed = 42L)
        assertTrue(a.isDailyBoard)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue("the daily flag survives", b.isDailyBoard)
        assertNull("the clock had not started and stays unstarted", b.timerStartElapsedRealtime.value)
        assertNull(b.activeElapsedMillis())
        assertNull("no hint before the first reveal", b.hint())

        a.revealCell(40)
        b.revealCell(40)
        assertEquals("first reveal places the same mines", a.state.value, b.state.value)
    }

    @Test
    fun `the tally, hints and a finished board survive a resume`() {
        val a = started(seed = 5L)
        a.revealCell(0)
        winBoard(a)
        assertTrue(a.state.value!!.won)
        assertEquals(1, a.puzzlesSolved.value)

        // Lose the next board (a new deal keeps the tally).
        a.startMatch(liveSeed())
        a.revealCell(0)
        val s = a.state.value!!
        if (!s.isOver) {
            val mine = s.cells.indexOfFirst { it.isMine && it.state == CellState.HIDDEN }
            a.revealCell(mine)
        }
        assertTrue(a.state.value!!.exploded)
        assertEquals(1, a.puzzlesSolved.value)
        assertEquals(1, a.puzzlesFailed.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(1, b.puzzlesFailed.value)
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.finishedElapsedMillis.value, b.finishedElapsedMillis.value)
        assertNotNull(b.finishedElapsedMillis.value)
        assertEquals(a.state.value!!.explodedIndex, b.state.value!!.explodedIndex)
    }

    @Test
    fun `the clock continues from the active time and ignores the time the app was gone`() {
        clock = 1_000L
        val a = started(liveSeed())
        a.revealCell(0)
        clock = 6_000L
        assertEquals(5_000L, a.activeElapsedMillis())
        val snapshot = a.snapshot()!!

        clock = 900_000L // the app was gone for a long time
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(5_000L, b.activeElapsedMillis())
        clock += 2_000L
        assertEquals(7_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a snapshot taken while paused stores the paused-adjusted time`() {
        clock = 1_000L
        val a = started(liveSeed())
        a.revealCell(0)
        clock = 3_000L
        a.pause()
        clock = 9_000L
        val snapshot = a.snapshot()!!

        clock = 50_000L
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(2_000L, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong-sized board and leaves the engine untouched`() {
        val a = liveGame()
        val snapshot = a.snapshot()!!

        val target = started(seed = 9L)
        val before = target.state.value
        val boardBefore = target.boardNumber.value

        assertFalse(target.restore("not json at all"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(""))

        val resized = snapshot.replace("\"rows\":9", "\"rows\":10")
        assertNotEquals("the tamper must actually change the text", snapshot, resized)
        assertFalse(target.restore(resized))

        val badTier = snapshot.replace("\"difficulty\":\"EASY\"", "\"difficulty\":\"NOPE\"")
        assertNotEquals(snapshot, badTier)
        assertFalse(target.restore(badTier))

        val other = newGame(players = listOf(PlayerInfo("someone-else", "Someone")))
        other.startMatch()
        val otherBefore = other.state.value
        assertFalse("different player ids", other.restore(snapshot))

        assertEquals(before, target.state.value)
        assertEquals(boardBefore, target.boardNumber.value)
        assertEquals(otherBefore, other.state.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint is null before the first reveal, once the board is over and after the session ends`() {
        val game = started(liveSeed())
        assertNull("no mines exist before the first reveal", game.hint())
        assertEquals(0, game.hintsUsed.value)

        game.revealCell(0)
        winBoard(game)
        assertTrue(game.state.value!!.won)
        assertNull("nothing to suggest on a finished board", game.hint())

        val ended = liveGame()
        ended.leaveSession()
        assertNull(ended.hint())
    }

    @Test
    fun `a proven-safe hint reveals a real safe square and never loses the board`() {
        var safeHints = 0
        var guesses = 0
        for (seed in 1L..120L) {
            val game = started(seed)
            game.revealCell(40)
            var steps = 0
            while (steps++ < 300) {
                val s = game.state.value!!
                if (s.isOver) break
                val hintsBefore = game.hintsUsed.value
                val hint = game.hint() ?: break
                assertEquals("every hint costs one", hintsBefore + 1, game.hintsUsed.value)
                assertEquals(hint, game.hintCell.value)
                val after = game.state.value!!
                if (hint.isGuess) {
                    guesses++
                    assertEquals("a guess is only highlighted, never revealed", CellState.HIDDEN, after.cells[hint.index].state)
                    assertFalse(after.isOver)
                    break
                }
                safeHints++
                assertFalse("a proven-safe hint must not be a mine", after.cells[hint.index].isMine)
                assertEquals(CellState.REVEALED, after.cells[hint.index].state)
                assertFalse("a proven-safe hint must not lose the board", after.exploded)
            }
            val end = game.state.value!!
            if (end.won) {
                assertEquals("an assisted win still counts as solved", 1, game.puzzlesSolved.value)
                assertTrue("and is marked assisted", game.hintsUsed.value > 0)
            }
            assertEquals(0, game.puzzlesFailed.value)
        }
        assertTrue("some boards are deducible", safeHints > 0)
        assertTrue("some boards need a guess", guesses > 0)
    }

    @Test
    fun `a hint is counted, cleared by the next move or a new board, and the count survives a restore but the highlight does not`() {
        val game = liveGame()
        val hint = game.hint()
        assertNotNull("a live board always has something to point at", hint)
        assertEquals(1, game.hintsUsed.value)
        assertEquals(hint, game.hintCell.value)

        val again = newGame()
        assertTrue(again.restore(game.snapshot()!!))
        assertEquals("hint count is part of the snapshot", 1, again.hintsUsed.value)
        assertNull("a restore clears the highlight", again.hintCell.value)

        val s = game.state.value!!
        if (!s.isOver) {
            val hidden = s.cells.indices.first { s.cells[it].state == CellState.HIDDEN && it != hint!!.index }
            game.toggleFlag(hidden)
            assertNull("a flag is a move: it clears the hint", game.hintCell.value)
        }

        val boardBefore = game.boardNumber.value
        game.playAgain()
        assertNull("a new board starts without a hint", game.hintCell.value)
        assertEquals("hints reset per board", 0, game.hintsUsed.value)
        assertEquals(boardBefore + 1, game.boardNumber.value)
    }
}
