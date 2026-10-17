package com.gamesuite.games.slidingpuzzle

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
 * Resume (snapshot/restore), Undo and Hint for Sliding Puzzle, written against the same shape as
 * ConnectFourResumeUndoHintTest. A hand-driven clock stands in for SystemClock.
 *
 * Boards used below (3x3, 0 = blank, row-major):
 *  - TWO_AWAY [1,2,3,4,5,6,0,7,8]: slide tile 7 left, then tile 8 left.
 *  - ONE_AWAY [1,2,3,4,5,6,7,0,8]: slide tile 8 left.
 */
class SlidingPuzzleResumeUndoHintTest {

    private class Clock {
        var now = 0L
    }

    private val twoAway = listOf(1, 2, 3, 4, 5, 6, 0, 7, 8)
    private val oneAway = listOf(1, 2, 3, 4, 5, 6, 7, 0, 8)

    private fun newGame(
        clock: Clock = Clock(),
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        playerId: String = "p1"
    ): SlidingPuzzleGame {
        val game = SlidingPuzzleGame(nowMillis = { clock.now })
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(PlayerInfo(playerId = playerId, displayName = "Player")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun started(clock: Clock = Clock(), mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): SlidingPuzzleGame =
        newGame(clock, mode).also {
            it.difficulty = CpuDifficulty.EASY
            it.startMatch()
        }

    private fun setBoard(game: SlidingPuzzleGame, tiles: List<Int>) {
        game.state.value = game.state.value!!.copy(tiles = tiles, moveCount = 0, solved = false)
    }

    private fun neighborsOf(index: Int, size: Int): List<Int> {
        val row = index / size
        val col = index % size
        val result = mutableListOf<Int>()
        if (row > 0) result += index - size
        if (row < size - 1) result += index + size
        if (col > 0) result += index - 1
        if (col < size - 1) result += index + 1
        return result
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

        val online = started(mode = PlayMode.ONLINE)
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        setBoard(a, twoAway)
        a.tapTile(3) // tile 4 slides down
        a.tapTile(4) // tile 5 slides left
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertEquals("the difficulty comes back with the game", CpuDifficulty.EASY, b.difficulty)

        a.tapTile(7)
        b.tapTile(7)
        assertEquals("the same move must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `the stopwatch continues from its active elapsed time and ignores the time the app was gone`() {
        val clockA = Clock()
        val a = started(clockA)
        setBoard(a, twoAway)
        clockA.now = 100
        a.tapTile(7) // first slide starts the stopwatch at t=100
        clockA.now = 1100
        val snapshot = a.snapshot()!!

        val clockB = Clock()
        clockB.now = 50_000 // a long time later, in a fresh process
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals("1000ms of play were already on the clock", 49_000L, b.timerStartElapsedRealtime.value)

        clockB.now = 50_500
        b.tapTile(8) // solves
        assertTrue(b.state.value!!.solved)
        assertEquals(1_500L, b.solvedElapsedMillis.value)
    }

    @Test
    fun `a snapshot taken while paused does not count the pause`() {
        val clockA = Clock()
        val a = started(clockA)
        setBoard(a, twoAway)
        clockA.now = 100
        a.tapTile(7)
        clockA.now = 1100
        a.pause()
        clockA.now = 9_000 // backgrounded for a long while; the snapshot is written now
        val snapshot = a.snapshot()!!

        val clockB = Clock()
        clockB.now = 20_000
        val b = newGame(clockB)
        assertTrue(b.restore(snapshot))
        assertEquals(19_000L, b.timerStartElapsedRealtime.value)
    }

    @Test
    fun `a timer that had not started stays unstarted`() {
        val a = started()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertNull(b.timerStartElapsedRealtime.value)
        assertNull(b.solvedElapsedMillis.value)
    }

    @Test
    fun `the original scramble, daily seed, picture source, hints and session tally survive`() {
        val a = started()
        a.startMatch(42L)
        a.imageSourceTag = "BUILT_IN_KOI"
        val original = a.state.value!!.tiles
        val blank = original.indexOf(0)
        a.tapTile(neighborsOf(blank, 3).first())
        a.hint()
        assertEquals(1, a.hintsUsed.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(42L, b.activeDailySeed)
        assertEquals("BUILT_IN_KOI", b.imageSourceTag)
        assertEquals(1, b.hintsUsed.value)
        assertNull("a restored game shows no stale hint", b.hintTile.value)
        b.resetToInitial()
        assertEquals("Reset must still replay the original scramble", original, b.state.value!!.tiles)

        // Session tally: solve one puzzle, deal another, resume.
        val c = started()
        setBoard(c, oneAway)
        c.tapTile(8)
        assertEquals(1, c.puzzlesSolved.value)
        c.playAgain()
        val d = newGame()
        assertTrue(d.restore(c.snapshot()!!))
        assertEquals(1, d.puzzlesSolved.value)
        assertEquals(c.state.value, d.state.value)
    }

    @Test
    fun `a solved board resumes as solved with its frozen time`() {
        val clock = Clock()
        val a = started(clock)
        setBoard(a, oneAway)
        clock.now = 10
        a.tapTile(8)
        clock.now = 5_000 // time passing after the solve must not leak into the saved time
        val frozen = a.solvedElapsedMillis.value
        assertNotNull(frozen)

        val b = newGame(Clock().also { it.now = 777 })
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.solved)
        assertEquals(frozen, b.solvedElapsedMillis.value)
        assertEquals(1, b.puzzlesSolved.value)
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val empty = newGame()
        assertFalse(empty.restore("not json at all"))
        assertFalse(empty.restore("{}"))
        assertFalse(empty.restore(""))
        assertNull("a rejected restore must not deal a puzzle", empty.state.value)

        val playing = started()
        val before = playing.state.value
        assertFalse(playing.restore("{\"size\":3}"))
        assertEquals(before, playing.state.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val snapshot = started().snapshot()!!
        val other = newGame(playerId = "someone-else")
        assertFalse(other.restore(snapshot))
        assertNull(other.state.value)
    }

    @Test
    fun `restore rejects a wrong-sized or inconsistent board`() {
        val a = started()
        a.tapTile(neighborsOf(a.state.value!!.tiles.indexOf(0), 3).first())
        val good = a.snapshot()!!

        val wrongSize = good.replaceFirst("\"size\":3", "\"size\":4")
        assertTrue("the test edit must actually change the snapshot", wrongSize != good)
        val missingTile = good.replaceFirst(Regex("\"tiles\":\\[\\d+,"), "\"tiles\":[")
        assertTrue(missingTile != good)
        val duplicateTile = good.replaceFirst(Regex("\"tiles\":\\[\\d+,\\d+,"), "\"tiles\":[1,1,")
        val wrongSolvedFlag = good.replaceFirst("\"solved\":false", "\"solved\":true")
        assertTrue(wrongSolvedFlag != good)

        for (bad in listOf(wrongSize, missingTile, duplicateTile, wrongSolvedFlag)) {
            val b = newGame()
            assertFalse("rejected: $bad", b.restore(bad))
            assertNull(b.state.value)
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one slide at a time down to the start`() {
        val game = started()
        setBoard(game, twoAway)
        assertFalse(game.canUndo)

        game.tapTile(3) // tile 4 down
        game.tapTile(4) // tile 5 left
        assertTrue(game.canUndo)
        assertEquals(2, game.state.value!!.moveCount)

        game.undo()
        assertEquals(1, game.state.value!!.moveCount)
        assertEquals(listOf(1, 2, 3, 0, 5, 6, 4, 7, 8), game.state.value!!.tiles)

        game.undo()
        assertEquals(0, game.state.value!!.moveCount)
        assertEquals(twoAway, game.state.value!!.tiles)
        assertFalse(game.canUndo)

        game.undo() // nothing left: harmless
        assertEquals(twoAway, game.state.value!!.tiles)
    }

    @Test
    fun `a rejected tap leaves no undo step`() {
        val game = started()
        setBoard(game, twoAway)
        game.tapTile(0) // not next to the blank
        game.tapTile(-1)
        game.tapTile(99)
        assertFalse(game.canUndo)
        assertEquals(0, game.state.value!!.moveCount)
    }

    @Test
    fun `undo never rewinds the clock and never lowers the hints used`() {
        val clock = Clock()
        val game = started(clock)
        setBoard(game, twoAway)
        clock.now = 100
        game.tapTile(3)
        val timerStart = game.timerStartElapsedRealtime.value
        assertEquals(100L, timerStart)

        game.hint()
        game.tapTile(4)
        assertEquals(1, game.hintsUsed.value)

        clock.now = 900
        game.undo()
        game.undo() // back to the board from before the hint was taken
        assertEquals(timerStart, game.timerStartElapsedRealtime.value)
        assertEquals("a hint stays used even after undoing past it", 1, game.hintsUsed.value)
    }

    @Test
    fun `a new puzzle and a reset start a fresh undo history`() {
        val game = started()
        setBoard(game, twoAway)
        game.tapTile(3)
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse("undo must not reach back into the previous puzzle", game.canUndo)

        val other = started()
        other.tapTile(neighborsOf(other.state.value!!.tiles.indexOf(0), 3).first())
        assertTrue(other.canUndo)
        other.resetToInitial()
        assertFalse(other.canUndo)
    }

    @Test
    fun `a solved board cannot be undone`() {
        val game = started()
        setBoard(game, oneAway)
        game.tapTile(8)
        assertTrue(game.state.value!!.solved)
        assertFalse(game.canUndo)
        game.undo()
        assertTrue("the solve stands", game.state.value!!.solved)
        assertEquals(1, game.puzzlesSolved.value)
    }

    @Test
    fun `undo does nothing once the session has ended`() {
        val game = started()
        setBoard(game, twoAway)
        game.tapTile(3)
        game.leaveSession()
        assertFalse(game.canUndo)
        val before = game.state.value
        game.undo()
        assertEquals(before, game.state.value)
    }

    @Test
    fun `a resumed game starts with an empty undo history`() {
        val a = started()
        a.tapTile(neighborsOf(a.state.value!!.tiles.indexOf(0), 3).first())
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse("undo history is in memory only", b.canUndo)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint suggests the tile that finishes the puzzle`() {
        val game = started()
        setBoard(game, oneAway)
        assertEquals(8, game.hint())
        assertEquals(8, game.hintTile.value)
        assertEquals(1, game.hintsUsed.value)
    }

    @Test
    fun `a hint heads straight for the goal from two slides away`() {
        val game = started()
        setBoard(game, twoAway)
        assertEquals("tile 7 slides left first", 7, game.hint())
    }

    @Test
    fun `asking again while a hint is showing does not count a second hint`() {
        val game = started()
        setBoard(game, twoAway)
        val first = game.hint()
        assertEquals(first, game.hint())
        assertEquals(1, game.hintsUsed.value)
    }

    @Test
    fun `a hint is always a tile that can actually slide`() {
        for (difficulty in CpuDifficulty.values()) {
            val game = newGame()
            game.difficulty = difficulty
            repeat(10) {
                game.startMatch()
                val s = game.state.value!!
                val tile = game.hint()
                assertNotNull(tile)
                val slidable = neighborsOf(s.tiles.indexOf(0), s.size).map { s.tiles[it] }
                assertTrue("hint $tile is not next to the blank on ${s.tiles}", tile in slidable)
            }
        }
    }

    @Test
    fun `following the hints solves a board three slides from the goal`() {
        val game = started()
        setBoard(game, listOf(1, 2, 3, 4, 8, 5, 7, 0, 6))
        var steps = 0
        while (!game.state.value!!.solved && steps < 10) {
            val tile = game.hint()!!
            game.tapTile(game.state.value!!.tiles.indexOf(tile))
            steps++
        }
        assertTrue(game.state.value!!.solved)
        assertEquals(3, steps)
        assertEquals("every suggestion counted", 3, game.hintsUsed.value)
        assertEquals("a puzzle solved with hints is assisted", 1, game.puzzlesSolved.value)
    }

    @Test
    fun `no hint once the puzzle is solved or the session is over`() {
        val solved = started()
        setBoard(solved, oneAway)
        solved.tapTile(8)
        assertNull(solved.hint())
        assertEquals(0, solved.hintsUsed.value)

        val over = started()
        over.leaveSession()
        assertNull(over.hint())
        assertNull(over.hintTile.value)
    }

    @Test
    fun `the next slide clears the hint and so does undo`() {
        val game = started()
        setBoard(game, twoAway)
        game.hint()
        assertNotNull(game.hintTile.value)
        game.tapTile(3)
        assertNull(game.hintTile.value)

        game.hint()
        assertNotNull(game.hintTile.value)
        game.undo()
        assertNull(game.hintTile.value)
        assertEquals("hints stay counted", 2, game.hintsUsed.value)
    }

    @Test
    fun `a new puzzle resets the hint count`() {
        val game = started()
        setBoard(game, twoAway)
        game.hint()
        assertEquals(1, game.hintsUsed.value)
        game.playAgain()
        assertEquals(0, game.hintsUsed.value)
        assertNull(game.hintTile.value)
    }
}
