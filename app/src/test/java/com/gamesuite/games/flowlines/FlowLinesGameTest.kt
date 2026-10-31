package com.gamesuite.games.flowlines

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session behaviour of Flow Lines: strokes, solve, timer, undo, hint, resume, daily. Plain JUnit, injected clock. */
class FlowLinesGameTest {

    private class TestClock(var now: Long = 0L)

    private val solo = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: TestClock = TestClock(),
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        tier: FlowTier = FlowTier.S6
    ): FlowLinesGame {
        val game = FlowLinesGame(nowMillis = { clock.now })
        game.init(GameContext(activeMode = mode, players = players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.tier = tier
        return game
    }

    private fun started(clock: TestClock = TestClock(), tier: FlowTier = FlowTier.S6, seed: Long = 11L): FlowLinesGame =
        newGame(clock, tier = tier).also { it.startMatch(seed) }

    private fun puzzleOf(game: FlowLinesGame): FlowPuzzle = game.state.value!!.puzzle

    /** Draws colour [color]'s generated solution path with one stroke. */
    private fun drawColor(game: FlowLinesGame, color: Int) {
        val path = puzzleOf(game).solution[color]
        assertTrue(game.beginStroke(path[0]))
        for (i in 1 until path.size) game.dragTo(path[i])
        game.endStroke()
    }

    private fun solveAll(game: FlowLinesGame) {
        for (color in 0 until puzzleOf(game).colorCount) drawColor(game, color)
    }

    // --- Lifecycle --------------------------------------------------------------------------

    @Test
    fun `module metadata matches the registration`() {
        val game = newGame()
        assertEquals("flow-lines", game.gameId)
        assertEquals("Flow Lines", game.displayName)
        assertEquals(1, game.minPlayers)
        assertEquals(1, game.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_PLAYER_VS_BOT), game.supportedModes)
    }

    @Test
    fun `a new puzzle starts empty with no moves and nothing to undo`() {
        val game = started()
        val s = game.state.value!!
        assertEquals(6, s.size)
        assertEquals(0, s.moves)
        assertFalse(s.won)
        assertTrue(s.paths.all { it.isEmpty() })
        assertFalse(game.canUndo)
        assertEquals(0, game.hintsUsed.value)
        assertNull(game.activeElapsedMillis())
    }

    @Test
    fun `a stroke along a solution path is one move and connects the pair`() {
        val game = started()
        drawColor(game, 0)
        val s = game.state.value!!
        assertEquals(1, s.moves)
        assertEquals(puzzleOf(game).solution[0], s.paths[0])
        assertEquals(1, s.connected)
        assertTrue(game.canUndo)
    }

    @Test
    fun `touching an empty cell starts nothing and touching a dot without drawing is not a move`() {
        val game = started()
        val p = puzzleOf(game)
        val empty = (0 until p.cellCount).first { p.endpointColor(it) < 0 }
        assertFalse(game.beginStroke(empty))
        assertTrue(game.beginStroke(p.pairs[0].a))
        game.endStroke()
        assertEquals(0, game.state.value!!.moves)
        assertTrue(game.state.value!!.paths.all { it.isEmpty() })
        assertFalse(game.canUndo)
    }

    @Test
    fun `a long drag leaves a valid contiguous path`() {
        val game = started()
        val p = puzzleOf(game)
        assertTrue(game.beginStroke(p.pairs[0].a))
        game.dragTo(p.solution[0].last())
        game.endStroke()
        val paths = game.state.value!!.paths
        // A swipe that is refused at once (a foreign dot in the way) leaves nothing drawn.
        assertTrue(paths[0].isEmpty() || paths[0].first() == p.pairs[0].a)
        assertTrue(FlowRules.validatePaths(p, paths))
    }

    @Test
    fun `drawing every solution path solves the puzzle and freezes the board`() {
        val game = started()
        solveAll(game)
        val s = game.state.value!!
        assertTrue(s.won)
        assertEquals(s.puzzle.colorCount, s.moves)
        assertEquals(1, game.puzzlesSolved.value)
        assertNotNull(game.finishedElapsedMillis.value)
        assertFalse("a solved board takes no more strokes", game.beginStroke(s.puzzle.pairs[0].a))
        assertFalse(game.canUndo)
        assertNull("a solved board has nothing to hint", game.hint())
    }

    @Test
    fun `the clock starts on the first stroke and freezes at the solve`() {
        val clock = TestClock(1000)
        val game = started(clock)
        assertNull(game.activeElapsedMillis())
        drawColor(game, 0)
        clock.now = 6000
        for (color in 1 until puzzleOf(game).colorCount) drawColor(game, color)
        assertEquals(5000L, game.finishedElapsedMillis.value)
        clock.now = 99_000
        assertEquals(5000L, game.activeElapsedMillis())
    }

    @Test
    fun `time spent paused is not counted`() {
        val clock = TestClock(1000)
        val game = started(clock)
        drawColor(game, 0)
        clock.now = 2000
        game.pause()
        clock.now = 4000
        game.resume()
        clock.now = 5000
        assertEquals(2000L, game.activeElapsedMillis())
        for (color in 1 until puzzleOf(game).colorCount) drawColor(game, color)
        assertEquals(2000L, game.finishedElapsedMillis.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back strokes one at a time and restores the move count`() {
        val game = started()
        drawColor(game, 0)
        drawColor(game, 1)
        assertEquals(2, game.state.value!!.moves)
        game.undo()
        var s = game.state.value!!
        assertEquals(1, s.moves)
        assertTrue(s.paths[1].isEmpty())
        assertEquals(puzzleOf(game).solution[0], s.paths[0])
        game.undo()
        s = game.state.value!!
        assertEquals(0, s.moves)
        assertTrue(s.paths.all { it.isEmpty() })
        assertFalse(game.canUndo)
    }

    @Test
    fun `undo does not rewind the clock or the hint count`() {
        val clock = TestClock(0)
        val game = started(clock)
        game.hint()
        clock.now = 3000
        drawColor(game, 1)
        game.undo()
        assertEquals(3000L, game.activeElapsedMillis())
        assertEquals(1, game.hintsUsed.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint reveals a correct segment ring it and marks the puzzle assisted`() {
        val game = started()
        val cell = game.hint()
        assertNotNull(cell)
        val s = game.state.value!!
        assertEquals(puzzleOf(game).solution[0].take(1 + FlowRules.HINT_STEP), s.paths[0])
        assertEquals(cell, game.hintCell.value)
        assertEquals(1, game.hintsUsed.value)
        assertEquals("a hint is not a move", 0, s.moves)
        assertTrue(FlowRules.validatePaths(s.puzzle, s.paths))
        game.undo()
        assertTrue(game.state.value!!.paths.all { it.isEmpty() })
        assertEquals("taking the hint back does not un-assist the puzzle", 1, game.hintsUsed.value)
    }

    @Test
    fun `hints alone can solve the puzzle and it stays assisted`() {
        val game = started(tier = FlowTier.S7, seed = 5L)
        var guard = 0
        while (game.state.value?.won != true && guard++ < 300) {
            if (game.hint() == null) break
        }
        assertTrue(game.state.value!!.won)
        assertTrue(game.hintsUsed.value > 1)
        assertEquals(1, game.puzzlesSolved.value)
    }

    // --- Accessibility route ----------------------------------------------------------------

    @Test
    fun `tapping a dot and then neighbouring cells draws the path one move per cell`() {
        val game = started()
        val sol = puzzleOf(game).solution[0]
        game.tapCell(sol[0])
        assertEquals(0, game.selectedColor.value)
        assertEquals(0, game.state.value!!.moves)
        for (i in 1 until sol.size) game.tapCell(sol[i])
        val s = game.state.value!!
        assertEquals(sol, s.paths[0])
        assertEquals(sol.size - 1, s.moves)
        assertNull("selection ends once the pair is joined", game.selectedColor.value)
    }

    // --- Daily ------------------------------------------------------------------------------

    @Test
    fun `the daily puzzle is the same for the same day and tier and differs otherwise`() {
        val a = newGame().also { it.startMatch(19000L) }
        val b = newGame().also { it.startMatch(19000L) }
        val c = newGame().also { it.startMatch(19001L) }
        val d = newGame(tier = FlowTier.S8).also { it.startMatch(19000L) }
        assertEquals(puzzleOf(a), puzzleOf(b))
        assertNotEquals(puzzleOf(a), puzzleOf(c))
        assertNotEquals(puzzleOf(a).size, puzzleOf(d).size)
        assertEquals(19000L, a.dailySeedInUse)
        assertTrue(a.isDailyBoard.value)
        a.startMatch(null)
        assertNull(a.dailySeedInUse)
        assertFalse(a.isDailyBoard.value)
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before a puzzle, after the session ends, or in online play`() {
        val game = newGame()
        assertNull(game.snapshot())
        game.startMatch(3L)
        assertNotNull(game.snapshot())
        game.leaveSession()
        assertNull(game.snapshot())
        val online = newGame(mode = PlayMode.ONLINE)
        online.startMatch(3L)
        assertNull(online.snapshot())
    }

    @Test
    fun `a restored game matches the saved one and the clock continues from the active time`() {
        val clock = TestClock(1000)
        val a = started(clock, tier = FlowTier.S7, seed = 77L)
        a.startMatch(21000L)
        drawColor(a, 0)
        a.hint()
        clock.now = 4000
        val saved = a.snapshot()!!

        val clock2 = TestClock(50_000)
        val b = newGame(clock2, tier = FlowTier.S5)
        assertTrue(b.restore(saved))
        assertEquals(a.state.value, b.state.value)
        assertEquals(FlowTier.S7, b.tier)
        assertEquals(21000L, b.dailySeedInUse)
        assertTrue(b.isDailyBoard.value)
        assertEquals(a.hintsUsed.value, b.hintsUsed.value)
        clock2.now = 52_000
        assertEquals(5000L, b.activeElapsedMillis())
        assertFalse("undo history is not carried over", b.canUndo)
    }

    @Test
    fun `a restored game plays on and can be finished`() {
        val a = started()
        drawColor(a, 0)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        for (color in 1 until puzzleOf(b).colorCount) drawColor(b, color)
        assertTrue(b.state.value!!.won)
        assertEquals(puzzleOf(b).colorCount, b.state.value!!.moves)
    }

    @Test
    fun `a solved game restores as solved with its frozen time`() {
        val clock = TestClock(0)
        val a = started(clock)
        drawColor(a, 0)
        clock.now = 7000
        for (color in 1 until puzzleOf(a).colorCount) drawColor(a, color)
        val b = newGame(TestClock(123_456))
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.won)
        assertEquals(7000L, b.finishedElapsedMillis.value)
        assertEquals(7000L, b.activeElapsedMillis())
        assertEquals(1, b.puzzlesSolved.value)
    }

    @Test
    fun `garbage and tampered snapshots are rejected and leave the game untouched`() {
        val a = started()
        drawColor(a, 0)
        val good = a.snapshot()!!

        val b = started(seed = 99L)
        val before = b.state.value
        for (bad in listOf("", "not json", "{}", "[]", good.dropLast(5), good.replace("\"won\":false", "\"won\":true"),
            good.replace("\"tier\":\"S6\"", "\"tier\":\"S9\""), good.replace("\"tier\":\"S6\"", "\"tier\":\"S42\""))) {
            assertFalse("should reject: ${bad.take(40)}", b.restore(bad))
            assertEquals(before, b.state.value)
        }
        val stranger = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X")))
        stranger.startMatch(1L)
        assertFalse("a different roster is rejected", stranger.restore(good))
        assertTrue("the untouched snapshot still restores", b.restore(good))
    }

    @Test
    fun `a snapshot with a path through a foreign dot is rejected`() {
        val a = started()
        val p = puzzleOf(a)
        // Build a tampered copy by hand: colour 0's path swallows colour 1's dot.
        val bad = a.snapshot()!!.replace("\"paths\":[[]", "\"paths\":[[${p.pairs[0].a},${p.pairs[1].a}]")
        val b = newGame()
        assertFalse(b.restore(bad))
    }

    // --- Session end ------------------------------------------------------------------------

    @Test
    fun `leaving scores the puzzles solved once and ends input`() {
        val game = started()
        var results = 0
        var last: GameResult? = null
        game.setOnMatchEnd { results++; last = it }
        solveAll(game)
        game.leaveSession()
        game.leaveSession()
        assertEquals(1, results)
        assertEquals(1, last!!.scores.single().score)
        assertTrue(last!!.scores.single().isWinner)
        assertTrue(game.matchOver.value)
        assertFalse(game.beginStroke(puzzleOf(game).pairs[0].a))
    }

    @Test
    fun `aborting an unsolved puzzle is not recorded as a win`() {
        val game = started()
        var last: GameResult? = null
        game.setOnMatchEnd { last = it }
        game.abortMatch()
        assertTrue(last!!.wasAborted)
        assertTrue(last!!.scores.isEmpty())
    }

    @Test
    fun `new puzzle resets the board, hints and undo but keeps the tally`() {
        val game = started()
        solveAll(game)
        game.playAgain()
        val s = game.state.value!!
        assertFalse(s.won)
        assertEquals(0, s.moves)
        assertEquals(0, game.hintsUsed.value)
        assertFalse(game.canUndo)
        assertEquals(1, game.puzzlesSolved.value)
    }
}
