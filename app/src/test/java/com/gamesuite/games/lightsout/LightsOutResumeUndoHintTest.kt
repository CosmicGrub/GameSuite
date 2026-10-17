package com.gamesuite.games.lightsout

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

/** Resume (snapshot/restore), Undo and Hint for Lights Out. Plain JUnit, injected clock. */
class LightsOutResumeUndoHintTest {

    private class TestClock(var now: Long = 0L)

    private val solo = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: TestClock = TestClock(),
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): LightsOutGame {
        val game = LightsOutGame(nowMillis = { clock.now })
        game.init(GameContext(activeMode = mode, players = players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = difficulty
        return game
    }

    private fun started(clock: TestClock = TestClock(), difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, seed: Long? = 7L): LightsOutGame =
        newGame(clock, difficulty = difficulty).also { it.startMatch(seed) }

    /** Independent re-derivation of the cells a press toggles. */
    private fun toggled(index: Int, size: Int): Set<Int> {
        val r = index / size
        val c = index % size
        val out = mutableSetOf(index)
        if (r > 0) out += index - size
        if (r < size - 1) out += index + size
        if (c > 0) out += index - 1
        if (c < size - 1) out += index + 1
        return out
    }

    /** The board reached from all-off by pressing [presses]. */
    private fun boardAfter(size: Int, presses: List<Int>): List<Boolean> {
        val cells = MutableList(size * size) { false }
        for (p in presses) for (i in toggled(p, size)) cells[i] = !cells[i]
        return cells
    }

    private fun setBoard(game: LightsOutGame, size: Int, cells: List<Boolean>) {
        game.state.value = LightsOutState(size = size, cells = cells)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first board, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no board dealt yet", game.snapshot())

        game.startMatch(3L)
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE)
        online.startMatch(3L)
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started(difficulty = CpuDifficulty.HARD, seed = 99L)
        listOf(0, 10, 24, 48).forEach { a.press(it) }
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertTrue("the daily flag survives", b.isDailyBoard.value)

        a.press(12)
        b.press(12)
        assertEquals("the same press must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `a free play board is not flagged daily after a resume`() {
        val a = started(seed = null)
        a.press(0)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.isDailyBoard.value)
    }

    @Test
    fun `the clock continues from active time and does not count time away`() {
        val clockA = TestClock(1_000L)
        val a = started(clockA)
        assertNull("an unstarted stopwatch stays unstarted", newGame().also { it.restore(a.snapshot()!!) }.activeElapsedMillis())

        a.press(0) // stopwatch starts at 1000
        clockA.now = 6_000L
        val snapshot = a.snapshot()!!

        val clockB = TestClock(900_000L)
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals(5_000L, b.activeElapsedMillis())
        clockB.now = 902_000L
        assertEquals("the clock keeps going from where it was", 7_000L, b.activeElapsedMillis())
    }

    @Test
    fun `paused time is not part of the saved clock`() {
        val clock = TestClock(0L)
        val a = started(clock)
        clock.now = 100L
        a.press(0)
        clock.now = 1_100L
        a.pause()
        clock.now = 50_000L // app in the background
        val snapshot = a.snapshot()!!

        val clockB = TestClock(10L)
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals(1_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a solved board, its frozen time, the tally and hints survive a resume`() {
        val clock = TestClock(0L)
        val a = started(clock, CpuDifficulty.EASY)
        setBoard(a, 3, boardAfter(3, listOf(0, 4)))
        a.hint() // marks the board assisted
        clock.now = 10L
        a.press(0) // starts the stopwatch at 10
        clock.now = 2_510L
        a.press(4) // clears the board: solved after 2500 ms
        assertTrue(a.state.value!!.won)
        assertEquals(2_500L, a.finishedElapsedMillis.value)
        assertEquals(1, a.puzzlesSolved.value)
        val frozen = a.finishedElapsedMillis.value
        assertNotNull(frozen)

        val b = newGame(TestClock(777L))
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.won)
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(1, b.hintsUsed.value)
        assertEquals(frozen, b.finishedElapsedMillis.value)
        assertEquals(frozen, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, another roster and a wrong sized board, and leaves the engine untouched`() {
        val a = started(difficulty = CpuDifficulty.EASY)
        a.press(0)
        val good = a.snapshot()!!

        val b = started(difficulty = CpuDifficulty.MEDIUM, seed = 5L)
        b.press(3)
        val before = b.state.value
        val movesBefore = b.state.value!!.moves

        assertFalse(b.restore("not json at all"))
        assertFalse(b.restore("{}"))
        assertFalse(b.restore(good.replace("\"difficulty\":\"EASY\"", "\"difficulty\":\"HARD\"")))
        assertFalse(b.restore(good.replace("\"difficulty\":\"EASY\"", "\"difficulty\":\"NOPE\"")))
        assertFalse(b.restore(good.replace("\"moves\":1", "\"moves\":-4")))
        assertFalse(b.restore(good.replace("\"playerIds\":[\"p1\"]", "\"playerIds\":[\"p1\",\"p2\"]")))
        assertEquals(before, b.state.value)
        assertEquals(movesBefore, b.state.value!!.moves)
        assertEquals(CpuDifficulty.MEDIUM, b.difficulty)

        val otherRoster = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X")))
        otherRoster.startMatch(1L)
        val untouched = otherRoster.state.value
        assertFalse(otherRoster.restore(good))
        assertEquals(untouched, otherRoster.state.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one press at a time and restores the move count`() {
        val g = started(difficulty = CpuDifficulty.EASY)
        setBoard(g, 3, boardAfter(3, listOf(1, 3, 5, 7))) // a known board that neither press below clears
        assertFalse(g.canUndo)
        val s0 = g.state.value!!
        g.press(0)
        val s1 = g.state.value!!
        g.press(4)
        assertTrue(g.canUndo)

        g.undo()
        assertEquals(s1, g.state.value)
        g.undo()
        assertEquals(s0, g.state.value)
        assertFalse(g.canUndo)
        g.undo() // nothing left: a no-op
        assertEquals(s0, g.state.value)
    }

    @Test
    fun `a rejected press leaves no undo step`() {
        val g = started()
        g.press(-1)
        g.press(10_000)
        assertFalse(g.canUndo)
    }

    @Test
    fun `undo does not rewind the clock or lower hints used`() {
        val clock = TestClock(0L)
        val g = started(clock)
        clock.now = 100L
        g.press(0)
        clock.now = 600L
        g.hint()
        val hints = g.hintsUsed.value
        assertEquals(1, hints)
        g.press(1)
        clock.now = 4_100L
        g.undo()
        assertEquals("the clock keeps running through an undo", 4_000L, g.activeElapsedMillis())
        assertEquals(hints, g.hintsUsed.value)
        g.undo()
        assertEquals(hints, g.hintsUsed.value)
        assertEquals(4_000L, g.activeElapsedMillis())
    }

    @Test
    fun `undo cannot cross into a new board or work after a win or after the session ends`() {
        val g = started(difficulty = CpuDifficulty.EASY)
        g.press(0)
        assertTrue(g.canUndo)
        g.playAgain()
        assertFalse("a new board starts a fresh history", g.canUndo)

        setBoard(g, 3, boardAfter(3, listOf(4)))
        g.press(4)
        assertTrue(g.state.value!!.won)
        assertFalse("a solved board cannot be un-solved", g.canUndo)
        val solved = g.state.value
        g.undo()
        assertEquals(solved, g.state.value)
        assertEquals(1, g.puzzlesSolved.value)

        val h = started(difficulty = CpuDifficulty.EASY)
        h.press(1)
        h.leaveSession()
        assertFalse(h.canUndo)
        val after = h.state.value
        h.undo()
        assertEquals(after, h.state.value)
    }

    @Test
    fun `a restored game starts with an empty history`() {
        val a = started()
        a.press(2)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.canUndo)
        b.press(2)
        assertTrue(b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint suggests the press that solves a hand built 3x3 position`() {
        val g = started(difficulty = CpuDifficulty.EASY)
        setBoard(g, 3, boardAfter(3, listOf(4)))
        assertEquals(4, g.hint())
        assertEquals(4, g.hintCell.value)
        assertEquals(1, g.hintsUsed.value)
        assertEquals("a hint changes nothing on the board", 0, g.state.value!!.moves)
    }

    @Test
    fun `hint finds the minimum press solution on a 5x5 that has several`() {
        val g = started(difficulty = CpuDifficulty.MEDIUM)
        setBoard(g, 5, boardAfter(5, listOf(12)))
        assertEquals(listOf(12), g.solutionPresses())
        assertEquals(12, g.hint())
    }

    @Test
    fun `every dealt board has a solution that really clears it and the hint is part of it`() {
        for ((tier, size) in listOf(CpuDifficulty.EASY to 3, CpuDifficulty.MEDIUM to 5, CpuDifficulty.HARD to 7)) {
            for (seed in 1L..12L) {
                val g = started(difficulty = tier, seed = seed)
                val presses = g.solutionPresses()
                assertNotNull("tier=$tier seed=$seed", presses)
                val cells = g.state.value!!.cells
                val cleared = boardAfter(size, presses!!)
                assertEquals("tier=$tier seed=$seed: the solution must reproduce the board", cells, cleared)
                assertTrue(presses.size <= size * size)
                assertTrue(presses.contains(g.hint()))
            }
        }
    }

    @Test
    fun `following hints to the end solves the board and the solve is assisted but counted`() {
        val g = started(difficulty = CpuDifficulty.MEDIUM, seed = 21L)
        var guard = 0
        while (!g.state.value!!.won && guard++ < 60) {
            g.press(g.hint()!!)
        }
        assertTrue(g.state.value!!.won)
        assertEquals(1, g.puzzlesSolved.value)
        assertTrue("the board is marked assisted", g.hintsUsed.value > 0)
        assertNull("no hint on a solved board", g.hint())
    }

    @Test
    fun `asking again for the same hint does not count twice, and a hint is null when there is nothing to suggest`() {
        val fresh = newGame()
        assertNull("no board yet", fresh.hint())

        val g = started(difficulty = CpuDifficulty.EASY)
        setBoard(g, 3, boardAfter(3, listOf(0)))
        val first = g.hint()
        val second = g.hint()
        assertEquals(first, second)
        assertEquals(1, g.hintsUsed.value)

        g.leaveSession()
        assertNull("not after the session ends", g.hint())
    }

    @Test
    fun `the next press and undo clear the hint, and a new board resets the assist count`() {
        val g = started(difficulty = CpuDifficulty.EASY)
        setBoard(g, 3, boardAfter(3, listOf(0, 8)))
        g.press(1)
        g.hint()
        assertNotNull(g.hintCell.value)
        g.press(2)
        assertNull(g.hintCell.value)

        g.hint()
        assertNotNull(g.hintCell.value)
        g.undo()
        assertNull(g.hintCell.value)
        assertTrue(g.hintsUsed.value > 0)

        g.playAgain()
        assertEquals(0, g.hintsUsed.value)
        assertNull(g.hintCell.value)
    }

    @Test
    fun `hints used survive a resume`() {
        val a = started(difficulty = CpuDifficulty.EASY)
        a.press(0)
        a.hint()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.hintsUsed.value)
        assertNull("the highlight itself is transient", b.hintCell.value)
    }
}
