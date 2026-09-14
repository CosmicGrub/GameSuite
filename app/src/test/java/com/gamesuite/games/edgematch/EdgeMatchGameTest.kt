package com.gamesuite.games.edgematch

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
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
 * [EdgeMatchGame]'s generation (`generateSolvedGrid`, private) is never trusted at
 * face value here. Since a tile's POSITION never changes for the life of a puzzle
 * (see the class KDoc's CORE MECHANIC section), the real correctness question is
 * simpler than a general placement puzzle: does resetting every tile's own
 * `rotation` back to 0 — recovering the exact solved arrangement the generator
 * built — actually satisfy every adjacency? [allTilesAtRotationZeroAreSolved]
 * checks this independently, and [rotateEveryTileBackToItsOwnZero] additionally
 * proves it end-to-end through the real public `tapTile()` API on real generated
 * puzzles, rather than only checking the data.
 */
class EdgeMatchGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): EdgeMatchGame {
        var fakeClock = 0L
        val game = EdgeMatchGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    /** Independent re-derivation of the win check, never calling EdgeMatchGame's own (private) isBoardSolved. */
    private fun isSolvedIndependently(tiles: List<EdgeMatchTile>, size: Int): Boolean {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val i = r * size + c
                if (c < size - 1 && tiles[i].currentEdge(EdgeMatchGame.RIGHT) != tiles[i + 1].currentEdge(EdgeMatchGame.LEFT)) return false
                if (r < size - 1 && tiles[i].currentEdge(EdgeMatchGame.BOTTOM) != tiles[i + size].currentEdge(EdgeMatchGame.TOP)) return false
            }
        }
        return true
    }

    private fun allTilesAtRotationZeroAreSolved(tiles: List<EdgeMatchTile>, size: Int): Boolean =
        isSolvedIndependently(tiles.map { it.copy(rotation = 0) }, size)

    /** Drives a REAL generated puzzle to solved through the real public `tapTile()` API: for each tile, taps it exactly enough times to bring its rotation back to 0 (`(4 - rotation) % 4`), relying on nothing but the generator's own "rotation 0 is always a solution" guarantee. */
    private fun rotateEveryTileBackToItsOwnZero(game: EdgeMatchGame) {
        val s0 = game.state.value!!
        for (i in s0.tiles.indices) {
            val neededTaps = (4 - s0.tiles[i].rotation) % 4
            repeat(neededTaps) { game.tapTile(i) }
        }
    }

    @Test
    fun `difficulty controls grid size and color count -- EASY 4x4x4, MEDIUM 6x6x5, HARD 8x8x6`() {
        val expected = mapOf(
            CpuDifficulty.EASY to (4 to 4),
            CpuDifficulty.MEDIUM to (6 to 5),
            CpuDifficulty.HARD to (8 to 6)
        )
        for ((difficulty, sizeAndColors) in expected) {
            val (size, colorCount) = sizeAndColors
            val game = newGame(difficulty)
            game.startMatch()
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(colorCount, s.colorCount)
            assertEquals(size * size, s.tiles.size)
            for (tile in s.tiles) for (edge in tile.canonicalEdges) {
                assertTrue("difficulty=$difficulty: every edge color must be in 0 until $colorCount", edge in 0 until colorCount)
            }
        }
    }

    @Test
    fun `a freshly generated puzzle is never already solved`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(8) {
                val game = newGame(difficulty)
                game.startMatch()
                assertFalse("difficulty=$difficulty", game.state.value!!.solved)
            }
        }
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(
            "the same daily seed should produce identical tiles",
            gameA.state.value!!.tiles,
            gameB.state.value!!.tiles
        )
    }

    @Test
    fun `resetting every tile's own rotation to 0 always solves a freshly generated puzzle`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(8) { trial ->
                val game = newGame(difficulty)
                game.startMatch()
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty trial=$trial: the generator's own solved arrangement (every tile at rotation 0) does not actually satisfy every adjacency",
                    allTilesAtRotationZeroAreSolved(s.tiles, s.size)
                )
            }
        }
    }

    @Test
    fun `rotating every scrambled tile back to its own original orientation solves a real generated puzzle through the public API`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..5L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                rotateEveryTileBackToItsOwnZero(game)
                assertTrue("difficulty=$difficulty seed=$seed", game.state.value!!.solved)
                assertEquals(1, game.puzzlesSolved.value)
            }
        }
    }

    /**
     * A hand-built, hand-verified 2x2 puzzle where [tile00]/[tile01]/[tile10]/[tile11],
     * ALL at rotation 0 in that row-major order, form a genuinely solved arrangement:
     * (0,0).right==(0,1).left, (0,0).bottom==(1,0).top, (0,1).bottom==(1,1).top,
     * (1,0).right==(1,1).left. Every test below starts [tile00] at some OTHER rotation
     * (breaking the board) and drives it back through the real public API.
     */
    private val tile00 = EdgeMatchTile(canonicalEdges = listOf(0, 1, 2, 0)) // top=0(border) right=1 bottom=2 left=0(border)
    private val tile01 = EdgeMatchTile(canonicalEdges = listOf(0, 0, 3, 1)) // top=0(border) right=0(border) bottom=3 left=1
    private val tile10 = EdgeMatchTile(canonicalEdges = listOf(2, 4, 0, 0)) // top=2 right=4 bottom=0(border) left=0(border)
    private val tile11 = EdgeMatchTile(canonicalEdges = listOf(3, 0, 0, 4)) // top=3 right=0(border) bottom=0(border) left=4

    private fun newHandBuiltGame(tile00Rotation: Int): EdgeMatchGame {
        var fakeClock = 0L
        val game = EdgeMatchGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.startMatch() // establishes context/matchOver=false; state overwritten below
        game.state.value = EdgeMatchState(
            size = 2,
            colorCount = 5,
            tiles = listOf(tile00.copy(rotation = tile00Rotation), tile01, tile10, tile11)
        )
        return game
    }

    @Test
    fun `tapping a tile rotates it 90 degrees clockwise and always counts as a move, even before it solves anything`() {
        val game = newHandBuiltGame(tile00Rotation = 1) // 3 taps away from solved
        assertFalse(game.state.value!!.solved)

        game.tapTile(0)
        val s = game.state.value!!
        assertEquals(2, s.tiles[0].rotation)
        assertEquals(1, s.moves)
        assertFalse("one tap into a 3-tap fix should not solve the board yet", s.solved)
        assertTrue(game.timerStartElapsedRealtime.value != null)
    }

    @Test
    fun `rotating a mis-oriented tile through every step eventually solves the puzzle`() {
        val game = newHandBuiltGame(tile00Rotation = 1) // rotation sequence: 1 -> 2 -> 3 -> 0 (solved)
        game.tapTile(0)
        assertEquals(2, game.state.value!!.tiles[0].rotation)
        assertFalse(game.state.value!!.solved)

        game.tapTile(0)
        assertEquals(3, game.state.value!!.tiles[0].rotation)
        assertFalse(game.state.value!!.solved)

        game.tapTile(0) // back to rotation 0 -- the known-good orientation
        val s = game.state.value!!
        assertEquals(0, s.tiles[0].rotation)
        assertTrue("rotating tile00 back to its correct orientation should solve the board", s.solved)
        assertEquals(3, s.moves)
        assertEquals(1, game.puzzlesSolved.value)
        assertTrue(game.solvedElapsedMillis.value != null)

        // Once solved, further taps are rejected -- same guard every other engine's tapTile()/press() has.
        val stateAfterSolve = game.state.value
        game.tapTile(0)
        assertEquals("a tap after the board is already solved must be a total no-op", stateAfterSolve, game.state.value)
    }

    @Test
    fun `matchingDirections reports only sides that currently match a neighbor, and updates live`() {
        val game = newHandBuiltGame(tile00Rotation = 3) // mismatched on both its interior sides (right, bottom)
        assertEquals(
            "tile00 is a corner (no top/left neighbor); mismatched right+bottom means neither should report as matching",
            emptySet<Int>(),
            game.matchingDirections(0)
        )

        game.tapTile(0) // rotation 3 -> 0, solves the board (a single tap suffices from rotation 3)
        assertTrue(game.state.value!!.solved)
        assertEquals(
            "once solved, both of tile00's interior sides (right, bottom) should report matching",
            setOf(EdgeMatchGame.RIGHT, EdgeMatchGame.BOTTOM),
            game.matchingDirections(0)
        )
    }

    @Test
    fun `tapping an out-of-range index is ignored, not a crash`() {
        val game = newHandBuiltGame(tile00Rotation = 1)
        val before = game.state.value
        game.tapTile(-1)
        game.tapTile(999)
        assertEquals(before, game.state.value)
    }

    @Test
    fun `resetToInitial restores the original scramble and clears moves and the stopwatch`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 7L)
        val initial = game.state.value!!.tiles
        game.tapTile(0) // a real rotation -- moves the board away from `initial`
        assertTrue(game.state.value!!.moves > 0)

        game.resetToInitial()
        val s = game.state.value!!
        assertEquals(initial, s.tiles)
        assertEquals(0, s.moves)
        assertFalse(s.solved)
        assertNull(game.timerStartElapsedRealtime.value)
        assertNull(game.solvedElapsedMillis.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        var clock = 0L
        val game = EdgeMatchGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        clock = 0L
        game.startMatch()
        game.state.value = EdgeMatchState(size = 2, colorCount = 5, tiles = listOf(tile00.copy(rotation = 2), tile01, tile10, tile11))

        clock = 0L
        game.tapTile(0) // rotation 2 -> 3 -- starts the timer at t=0, does not yet solve
        assertFalse(game.state.value!!.solved)

        clock = 10L
        game.pause() // pausedAt = 10
        clock = 100_000L
        game.pause() // must be a no-op -- pausedAt should STILL be 10, not overwritten to 100_000
        clock = 100_050L
        game.resume() // totalPausedMillis += 100_050 - 10 = 100_040 (not 100_050 - 100_000 = 50)

        clock = 100_060L
        game.tapTile(0) // rotation 3 -> 0 -- solves the board

        val recordedMillis = game.solvedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [10,100_000] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `tapTile is rejected once the session has ended via leaveSession, even if the puzzle itself was not yet solved`() {
        val game = newHandBuiltGame(tile00Rotation = 1)
        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.tapTile(0)
        assertEquals("a tapTile() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch()
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }
}
