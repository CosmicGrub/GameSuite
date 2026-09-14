package com.gamesuite.games.slidingpuzzle

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SlidingPuzzleGame]'s `scramble()` (private) builds a start position with
 * a random walk of legal single-tile moves from the solved board, which its
 * own KDoc argues can never produce an unsolvable board since every step is
 * invertible — rather than trust that argument, this file checks the result
 * against the actual math: the standard 15-puzzle solvability invariant
 * (permutation parity of the non-blank tiles, plus blank-row parity on
 * even-width grids — see [isSolvable]'s KDoc for the exact rule), reimplemented
 * independently here rather than reusing any of SlidingPuzzleGame's own code.
 *
 * `scramble`/`generatePuzzle` are both private, so every puzzle here is
 * generated through the real public entry point (`difficulty` + `startMatch()`,
 * exactly as TicTacToeScreen-equivalent SlidingPuzzleScreen drives it), run
 * for 200 trials per difficulty tier so each of the three (grid size,
 * scramble depth) configurations gets real coverage rather than a single
 * lucky/unlucky draw.
 *
 * [newGame] hands every test a fake, monotonically-increasing clock via
 * SlidingPuzzleGame's injectable `nowMillis` constructor param, which is
 * exactly why this class can now exercise [SlidingPuzzleGame.tapTile] at
 * all: before that seam existed, `tapTile()` called the real
 * `SystemClock.elapsedRealtime()` directly, which throws "not mocked" under
 * plain JUnit (no Robolectric in this project) — a previously-documented
 * coverage gap this file used to work around by never calling `tapTile()`.
 */
class SlidingPuzzleGameTest {

    // A monotonically-increasing fake clock -- avoids the real
    // SystemClock.elapsedRealtime(), which throws "not mocked" under plain
    // JUnit (no Robolectric in this project) -- see MinesweeperGameTest's
    // identical `newGame` and SlidingPuzzleGame's own KDoc on why
    // `nowMillis` is injectable at all.
    private fun newGame(): SlidingPuzzleGame {
        var fakeClock = 0L
        val game = SlidingPuzzleGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /**
     * Standard 15-puzzle solvability check, independent of SlidingPuzzleGame's
     * own implementation. `tiles` is row-major, 0 = blank, size x size.
     *
     * Rule (see e.g. the classic 15-puzzle parity proof): count inversions
     * among the non-blank tiles in row-major reading order. On an odd-width
     * grid, a board is solvable iff that count is even. On an even-width
     * grid, it also depends on which row (counted 1-indexed from the
     * BOTTOM) the blank sits on: solvable iff inversions is odd when the
     * blank's row-from-bottom is even, or even when it's odd.
     */
    private fun isSolvable(tiles: List<Int>, size: Int): Boolean {
        val values = tiles.filter { it != 0 }
        var inversions = 0
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                if (values[i] > values[j]) inversions++
            }
        }
        return if (size % 2 == 1) {
            inversions % 2 == 0
        } else {
            val blankRowFromTop = tiles.indexOf(0) / size
            val blankRowFromBottom = size - blankRowFromTop
            if (blankRowFromBottom % 2 == 0) inversions % 2 == 1 else inversions % 2 == 0
        }
    }

    private fun assertScrambleAlwaysSolvable(difficulty: CpuDifficulty, trials: Int = 200) {
        val game = newGame()
        game.difficulty = difficulty
        repeat(trials) { trial ->
            game.startMatch()
            val state = game.state.value!!
            assertTrue(
                "Trial $trial: $difficulty scramble produced an unsolvable board: size=${state.size}, tiles=${state.tiles}",
                isSolvable(state.tiles, state.size)
            )
        }
    }

    @Test
    fun `EASY scrambles are always solvable`() {
        assertScrambleAlwaysSolvable(CpuDifficulty.EASY)
    }

    @Test
    fun `MEDIUM scrambles are always solvable`() {
        assertScrambleAlwaysSolvable(CpuDifficulty.MEDIUM)
    }

    @Test
    fun `HARD scrambles are always solvable`() {
        assertScrambleAlwaysSolvable(CpuDifficulty.HARD)
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

    @Test
    fun `tapping a tile not adjacent to the blank is a no-op`() {
        val game = newGame()
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()
        val before = game.state.value!!
        val blankIndex = before.tiles.indexOf(0)
        val neighbors = neighborsOf(blankIndex, before.size)
        val nonNeighbor = before.tiles.indices.first { it != blankIndex && it !in neighbors }

        game.tapTile(nonNeighbor)

        assertEquals("a non-adjacent tap must not change the board", before.tiles, game.state.value!!.tiles)
        assertEquals(0, game.state.value!!.moveCount)
        assertEquals("a no-op tap must not start the stopwatch", null, game.timerStartElapsedRealtime.value)
    }

    @Test
    fun `tapping a tile adjacent to the blank slides it, increments moveCount, and starts the stopwatch`() {
        val game = newGame()
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()
        val before = game.state.value!!
        val blankIndex = before.tiles.indexOf(0)
        val neighbor = neighborsOf(blankIndex, before.size).first()
        val movedValue = before.tiles[neighbor]

        assertEquals(null, game.timerStartElapsedRealtime.value)
        game.tapTile(neighbor)

        val after = game.state.value!!
        assertEquals(1, after.moveCount)
        assertEquals("the tapped tile's value should now sit where the blank was", movedValue, after.tiles[blankIndex])
        assertEquals("the blank should now sit where the tapped tile was", 0, after.tiles[neighbor])
        assertTrue("the first real slide must start the stopwatch", game.timerStartElapsedRealtime.value != null)
    }

    @Test
    fun `sliding the final tile into place solves the puzzle, increments puzzlesSolved, and freezes solvedElapsedMillis`() {
        val game = newGame()
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()
        val size = game.state.value!!.size
        val solved = (1 until size * size).toList() + 0
        val blank = solved.size - 1
        val neighbor = blank - 1 // same row as `blank` on any size >= 2 grid
        // One tile out of place, directly adjacent to the blank -- a single tapTile() away from solved.
        val almostSolved = solved.toMutableList()
        almostSolved[blank] = almostSolved[neighbor].also { almostSolved[neighbor] = almostSolved[blank] }
        game.state.value = game.state.value!!.copy(tiles = almostSolved, moveCount = 0, solved = false)

        game.tapTile(blank) // the only tile adjacent to the blank (now sitting at `neighbor`) -- slides it home

        val after = game.state.value!!
        assertTrue("the final slide should solve the puzzle", after.solved)
        assertEquals(solved, after.tiles)
        assertEquals(1, game.puzzlesSolved.value)
        assertTrue("solvedElapsedMillis should be set once solved", game.solvedElapsedMillis.value != null)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        // Found by adversarial review during the Lights Out pass (Minesweeper/Sudoku/
        // LightsOut all share this exact pattern): pause()/resume() used to be no-ops
        // while the timer was a pure wall-clock delta, so backgrounding the app
        // mid-puzzle (which really does call pause()/resume() -- see
        // GameSessionManager -- not merely a theoretical concern) silently added the
        // entire background duration to the recorded solve time.
        var clock = 0L
        val game = SlidingPuzzleGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch()

        // Force a specific 3x3 board exactly two slides from solved, rather than
        // trusting the random scramble to land on a known-length solution.
        check(game.state.value!!.size == 3) // EASY is a 3x3 grid -- guards the hand-picked indices below
        game.state.value = game.state.value!!.copy(tiles = listOf(1, 2, 3, 4, 5, 6, 0, 7, 8), moveCount = 0, solved = false)

        clock = 5L
        game.tapTile(7) // first real slide -- starts the stopwatch
        clock = 10L
        game.pause() // e.g. the app was backgrounded here
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L
        game.tapTile(8) // completes the solve

        val recordedMillis = game.solvedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame()
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch() // a fresh puzzle should always be fully playable again
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }
}
