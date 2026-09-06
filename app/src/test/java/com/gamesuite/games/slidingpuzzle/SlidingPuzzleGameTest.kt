package com.gamesuite.games.slidingpuzzle

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
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
 */
class SlidingPuzzleGameTest {

    private fun newGame(): SlidingPuzzleGame {
        val game = SlidingPuzzleGame()
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
}
