package com.gamesuite.games.colorflood

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

/** Resume (snapshot/restore), Undo and Hint for Color Flood. Plain JUnit, injected clock. */
class ColorFloodResumeUndoHintTest {

    private var clock = 0L

    private fun newGame(
        playerId: String = "p1",
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        difficulty: CpuDifficulty = CpuDifficulty.EASY
    ): ColorFloodGame {
        val game = ColorFloodGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(PlayerInfo(playerId = playerId, displayName = "Player")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    private fun started(seed: Long? = 7L, difficulty: CpuDifficulty = CpuDifficulty.EASY): ColorFloodGame =
        newGame(difficulty = difficulty).also { it.startMatch(seed) }

    /** Any colour that is a legal (move-costing) pick right now. */
    private fun anyPick(game: ColorFloodGame): Int {
        val s = game.state.value!!
        return (0 until s.colorCount).first { it != s.currentColor }
    }

    /** Picks the best-absorbing colour until the board is flooded, without using hint(). */
    private fun floodGreedily(game: ColorFloodGame) {
        while (game.state.value?.isOver == false) {
            val s = game.state.value!!
            val best = (0 until s.colorCount).maxByOrNull { game.absorbCount(it) }!!
            game.pick(best)
        }
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first board, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no board dealt yet", game.snapshot())

        game.startMatch(1L)
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch(1L) }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        repeat(3) { a.pick(a.state.value!!.let { s -> (0 until s.colorCount).first { c -> c != s.currentColor && a.absorbCount(c) > 0 } }) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.difficulty, b.difficulty)

        val next = anyPick(a)
        a.pick(next)
        b.pick(next)
        assertEquals("the same pick must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `tier, daily flag, solved tally and hints survive a resume`() {
        val a = started(seed = 5L, difficulty = CpuDifficulty.HARD)
        a.hint()
        a.pick(anyPick(a))

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(5L, b.dailySeedInUse.value)
        assertEquals(1, b.hintsUsed.value)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertNull("a hint on screen is transient", b.hintColor.value)

        // A solved board raises the tally, which also has to survive.
        val c = started()
        floodGreedily(c)
        assertEquals(1, c.puzzlesSolved.value)
        val d = newGame()
        assertTrue(d.restore(c.snapshot()!!))
        assertEquals(1, d.puzzlesSolved.value)
        assertTrue(d.state.value!!.won)
        assertEquals(c.finishedElapsedMillis.value, d.finishedElapsedMillis.value)
    }

    @Test
    fun `the clock continues from the active time and does not count the time the app was gone`() {
        clock = 1_000L
        val a = started()
        assertNull("an unstarted timer has no reading", a.activeElapsedMillis())
        val unstarted = newGame()
        assertTrue(unstarted.restore(a.snapshot()!!))
        assertNull("an unstarted timer stays unstarted", unstarted.timerStartElapsedRealtime.value)

        a.pick(anyPick(a)) // timer starts at 1000
        clock = 4_000L
        val snapshot = a.snapshot()!!

        clock = 900_000L // the app was gone for a long while
        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(3_000L, b.activeElapsedMillis())
        clock = 902_000L
        assertEquals(5_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a pause in progress is not counted in the snapshot`() {
        clock = 0L
        val a = started()
        a.pick(anyPick(a))
        clock = 2_000L
        a.pause()
        clock = 10_000L
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(2_000L, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong-sized board and leaves the engine untouched`() {
        val a = started(difficulty = CpuDifficulty.MEDIUM)
        a.pick(anyPick(a))
        val snapshot = a.snapshot()!!

        val b = newGame(difficulty = CpuDifficulty.MEDIUM)
        b.startMatch(3L)
        val before = b.state.value
        val beforeSolved = b.puzzlesSolved.value

        assertFalse(b.restore("not json"))
        assertFalse(b.restore("{}"))
        assertFalse(b.restore(""))
        assertEquals(before, b.state.value)

        val wrongRoster = newGame(playerId = "someone-else", difficulty = CpuDifficulty.MEDIUM)
        wrongRoster.startMatch(3L)
        val rosterBefore = wrongRoster.state.value
        assertFalse(wrongRoster.restore(snapshot))
        assertEquals(rosterBefore, wrongRoster.state.value)

        val wrongSize = snapshot.replace("\"size\":12", "\"size\":9")
        assertTrue("the tamper must actually have changed the text", wrongSize != snapshot)
        assertFalse(b.restore(wrongSize))

        // A "won" flag that contradicts the board is rejected too.
        val lyingWon = snapshot.replace("\"won\":false", "\"won\":true")
        assertTrue(lyingWon != snapshot)
        assertFalse(b.restore(lyingWon))

        assertEquals(before, b.state.value)
        assertEquals(beforeSolved, b.puzzlesSolved.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one pick at a time`() {
        val a = started()
        val start = a.state.value
        assertFalse(a.canUndo)

        a.pick(anyPick(a))
        val afterOne = a.state.value
        a.pick(anyPick(a))
        assertTrue(a.canUndo)

        a.undo()
        assertEquals(afterOne, a.state.value)
        a.undo()
        assertEquals(start, a.state.value)
        assertFalse(a.canUndo)
        a.undo() // nothing left: harmless
        assertEquals(start, a.state.value)
    }

    @Test
    fun `a rejected pick leaves no undo step`() {
        val a = started()
        a.pick(a.state.value!!.currentColor) // same colour: not a move
        a.pick(99) // out of range
        assertFalse(a.canUndo)
    }

    @Test
    fun `undo cannot lower the hint count or rewind the clock`() {
        clock = 0L
        val a = started()
        a.pick(anyPick(a))
        a.pick(anyPick(a))
        a.hint()
        assertEquals(1, a.hintsUsed.value)
        clock = 5_000L

        a.undo()
        assertEquals("a hint stays used after undo", 1, a.hintsUsed.value)
        assertEquals("the clock keeps running", 5_000L, a.activeElapsedMillis())
        a.undo()
        assertEquals(1, a.hintsUsed.value)
        assertEquals(5_000L, a.activeElapsedMillis())
    }

    @Test
    fun `undo does not cross into a new board or past the end of the session`() {
        val a = started()
        a.pick(anyPick(a))
        assertTrue(a.canUndo)
        a.playAgain()
        assertFalse("a new board starts with no history", a.canUndo)
        val fresh = a.state.value
        a.undo()
        assertEquals(fresh, a.state.value)

        a.pick(anyPick(a))
        a.leaveSession()
        assertFalse(a.canUndo)
        val ended = a.state.value
        a.undo()
        assertEquals(ended, a.state.value)
    }

    @Test
    fun `a flooded board cannot be undone, so its recorded win cannot be replayed`() {
        val a = started()
        floodGreedily(a)
        assertTrue(a.state.value!!.won)
        assertFalse(a.canUndo)
        val won = a.state.value
        a.undo()
        assertEquals(won, a.state.value)
        assertEquals(1, a.puzzlesSolved.value)
    }

    @Test
    fun `undo history is cleared by restore`() {
        val a = started()
        a.pick(anyPick(a))
        val b = newGame()
        b.startMatch(2L)
        b.pick(anyPick(b))
        assertTrue(b.canUndo)
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint suggests the colour that absorbs the most cells and that colour is legal`() {
        val a = started(seed = 11L)
        val s = a.state.value!!
        val best = (0 until s.colorCount).maxOf { a.absorbCount(it) }
        val suggestion = a.hint()
        assertNotNull(suggestion)
        assertTrue(suggestion != s.currentColor)
        assertEquals(best, a.absorbCount(suggestion!!))
        assertTrue(best > 0)
        assertEquals(suggestion, a.hintColor.value)
        assertEquals(1, a.hintsUsed.value)

        // Asking again while the same suggestion is showing is free.
        assertEquals(suggestion, a.hint())
        assertEquals(1, a.hintsUsed.value)
    }

    @Test
    fun `hint is cleared by the next pick and by undo, and a new board resets the count`() {
        val a = started()
        a.hint()
        assertNotNull(a.hintColor.value)
        a.pick(anyPick(a))
        assertNull(a.hintColor.value)

        a.hint()
        assertNotNull(a.hintColor.value)
        a.undo()
        assertNull(a.hintColor.value)
        assertEquals("count is per board and not lowered by undo", 2, a.hintsUsed.value)

        a.playAgain()
        assertEquals(0, a.hintsUsed.value)
        assertNull(a.hintColor.value)
    }

    @Test
    fun `hint is null when the board is flooded, the session ended or there is no board`() {
        assertNull(newGame().hint())

        val a = started()
        floodGreedily(a)
        assertNull(a.hint())
        assertEquals(0, a.hintsUsed.value)

        val b = started()
        b.leaveSession()
        assertNull(b.hint())
    }

    @Test
    fun `following hints floods the board and the solve is marked assisted`() {
        val a = started(seed = 21L)
        var guard = 0
        while (!a.state.value!!.isOver && guard++ < 1000) {
            val c = a.hint() ?: break
            a.pick(c)
        }
        assertTrue(a.state.value!!.won)
        assertEquals("still counts as a solved board for the session", 1, a.puzzlesSolved.value)
        assertTrue("an assisted solve is flagged so the screen skips the record", a.hintsUsed.value > 0)
    }
}
