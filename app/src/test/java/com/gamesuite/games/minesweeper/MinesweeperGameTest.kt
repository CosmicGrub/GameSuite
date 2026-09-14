package com.gamesuite.games.minesweeper

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MinesweeperGame]'s mine placement/flood-fill are both private, exactly
 * like SlidingPuzzleGame's `scramble()`/`generatePuzzle()` — every board
 * here is generated through the real public entry point
 * (`difficulty` + `startMatch()` + the first `revealCell()` call, which is
 * what actually places the mines — see the class KDoc's FIRST-CLICK SAFETY
 * section). Unlike a black-box player, this test freely reads
 * `state.value!!.cells` for the FULL board (mine identity/adjacent-count are
 * already set on every cell the instant mines are placed, regardless of
 * that cell's own visible HIDDEN/REVEALED/FLAGGED state) to verify
 * invariants a real player could never directly observe.
 */
class MinesweeperGameTest {

    // A monotonically-increasing fake clock -- avoids the real
    // SystemClock.elapsedRealtime(), which throws "not mocked" under plain
    // JUnit (no Robolectric in this project) -- see MinesweeperGame's own
    // KDoc on why `nowMillis` is injectable at all.
    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.EASY): MinesweeperGame {
        var fakeClock = 0L
        val game = MinesweeperGame(nowMillis = { fakeClock++ })
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

    private fun neighborsOf(index: Int, rows: Int, cols: Int): List<Int> {
        val row = index / cols
        val col = index % cols
        val result = mutableListOf<Int>()
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val r = row + dr
            val c = col + dc
            if (r in 0 until rows && c in 0 until cols) result += r * cols + c
        }
        return result
    }

    @Test
    fun `the first reveal is never a mine, across many trials and difficulties`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(100) { trial ->
                val game = newGame(difficulty)
                game.startMatch()
                val firstIndex = (game.state.value!!.rows * game.state.value!!.cols) / 2 // an arbitrary but fixed cell each trial
                game.revealCell(firstIndex)
                assertFalse(
                    "Trial $trial ($difficulty): the first-ever reveal was a mine",
                    game.state.value!!.cells[firstIndex].isMine
                )
            }
        }
    }

    @Test
    fun `adjacent-mine counts match an independent recount of the actual board`() {
        repeat(50) {
            val game = newGame(CpuDifficulty.MEDIUM)
            game.startMatch()
            game.revealCell(0) // triggers mine placement
            val s = game.state.value!!
            for (i in s.cells.indices) {
                val cell = s.cells[i]
                if (cell.isMine) continue
                val actual = neighborsOf(i, s.rows, s.cols).count { s.cells[it].isMine }
                assertEquals("cell $i's own adjacentMines disagrees with an independent recount", actual, cell.adjacentMines)
            }
        }
    }

    @Test
    fun `exactly mineCount cells are mines`() {
        val game = newGame(CpuDifficulty.HARD)
        game.startMatch()
        game.revealCell(0)
        val s = game.state.value!!
        assertEquals(s.mineCount, s.cells.count { it.isMine })
    }

    @Test
    fun `revealing a mine (after the safe first click) loses the board and reveals every mine`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.revealCell(0)
        val afterFirstClick = game.state.value!!
        val someMineIndex = afterFirstClick.cells.indices.first { afterFirstClick.cells[it].isMine }

        game.revealCell(someMineIndex)
        val s = game.state.value!!
        assertTrue("revealing a mine should set exploded=true", s.exploded)
        assertFalse("a lost board should not also report won=true", s.won)
        assertTrue("every mine should be revealed once the board is lost", s.cells.filter { it.isMine }.all { it.state == CellState.REVEALED })
    }

    @Test
    fun `revealing every non-mine cell wins the board`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.revealCell(0)
        val safeIndices = game.state.value!!.cells.indices.filter { !game.state.value!!.cells[it].isMine }

        for (i in safeIndices) game.revealCell(i) // flood-fill likely already opened many of these; re-revealing an already-open cell is a safe no-op

        val s = game.state.value!!
        assertTrue("revealing every safe cell should win the board", s.won)
        assertFalse(s.exploded)
        assertEquals("a won board's every non-mine cell must actually be revealed", safeIndices.size, s.cells.count { it.state == CellState.REVEALED })
    }

    @Test
    fun `winning increments puzzlesSolved, losing does not`() {
        val winGame = newGame(CpuDifficulty.EASY)
        winGame.startMatch()
        winGame.revealCell(0)
        val safeIndices = winGame.state.value!!.cells.indices.filter { !winGame.state.value!!.cells[it].isMine }
        for (i in safeIndices) winGame.revealCell(i)
        assertEquals(1, winGame.puzzlesSolved.value)

        val loseGame = newGame(CpuDifficulty.EASY)
        loseGame.startMatch()
        loseGame.revealCell(0)
        val mineIndex = loseGame.state.value!!.cells.indices.first { loseGame.state.value!!.cells[it].isMine }
        loseGame.revealCell(mineIndex)
        assertEquals(0, loseGame.puzzlesSolved.value)
    }

    @Test
    fun `flagging toggles a hidden cell, and a revealed cell cannot be flagged`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()

        game.toggleFlag(5)
        assertEquals(CellState.FLAGGED, game.state.value!!.cells[5].state)
        assertEquals(1, game.state.value!!.flagCount)

        game.toggleFlag(5)
        assertEquals(CellState.HIDDEN, game.state.value!!.cells[5].state)
        assertEquals(0, game.state.value!!.flagCount)

        // A flagged cell must be unflagged (not directly revealed) before it can be opened.
        game.toggleFlag(5)
        game.revealCell(5)
        assertEquals(CellState.FLAGGED, game.state.value!!.cells[5].state)
    }

    @Test
    fun `a daily seed makes the board reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        gameA.revealCell(0)

        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        gameB.revealCell(0)

        val minesA = gameA.state.value!!.cells.map { it.isMine }
        val minesB = gameB.state.value!!.cells.map { it.isMine }
        assertEquals("the same daily seed should place mines identically", minesA, minesB)
    }

    @Test
    fun `flood-fill on a zero-adjacent cell opens more than just that one cell`() {
        // A small, low-density board makes a large contiguous zero-region likely
        // on essentially every trial, without hand-building a specific layout.
        repeat(20) {
            val game = newGame(CpuDifficulty.EASY)
            game.startMatch()
            val center = (game.state.value!!.rows * game.state.value!!.cols) / 2
            game.revealCell(center)
            val s = game.state.value!!
            if (!s.won && !s.exploded && s.cells[center].adjacentMines == 0) {
                assertTrue(
                    "revealing a zero-adjacent cell should flood-open more than just itself",
                    s.cells.count { it.state == CellState.REVEALED } > 1
                )
            }
        }
    }
}
