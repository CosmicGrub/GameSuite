package com.gamesuite.games.minesweeper

import com.gamesuite.core.GameContext
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

    @Test
    fun `pausing mid-game does not inflate the recorded solve time`() {
        // Found by adversarial review during the Lights Out pass (this class
        // shares the exact same pattern): pause()/resume() used to be
        // no-ops while the timer was a pure wall-clock delta, so
        // backgrounding the app mid-game (which really does call
        // pause()/resume() -- see GameSessionManager -- not merely a
        // theoretical concern) silently added the entire background
        // duration to the recorded solve time.
        var clock = 0L
        val game = MinesweeperGame(nowMillis = { clock })
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

        game.revealCell(0) // starts the timer (and places mines)
        clock = 10L
        game.pause() // e.g. the app was backgrounded here
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L

        // Reveal every remaining safe cell to win.
        val safeIndices = game.state.value!!.cells.indices.filter { !game.state.value!!.cells[it].isMine }
        for (i in safeIndices) game.revealCell(i)

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `revealCell and toggleFlag are rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        // Found by adversarial review (ColorFloodGame.pick()'s own pass):
        // revealCell()/toggleFlag()'s only liveness check was `s.isOver`
        // (per-board), never `matchOver` (session-level), so a call after
        // leaveSession() had already delivered the final GameResult could
        // still mutate state -- including a phantom win with no second
        // result ever reported.
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.revealCell(0)
        assertFalse(game.state.value!!.isOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.revealCell(1)
        assertEquals("a revealCell() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)

        game.toggleFlag(2)
        assertEquals("a toggleFlag() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        // Found by adversarial review: pause() overwrote its anchor on a
        // second call with no resume() in between (unlike resume(), which
        // was already correctly idempotent), silently dropping the
        // interval between the two pause() calls from totalPausedMillis
        // and counting it as active play instead.
        var clock = 0L
        val game = MinesweeperGame(nowMillis = { clock })
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

        clock = 100L
        game.revealCell(0) // starts the timer at t=100 (and places mines)

        clock = 110L
        game.pause() // pausedAt = 110

        clock = 200L
        game.pause() // must be a no-op -- pausedAt should STILL be 110, not overwritten to 200

        clock = 250L
        game.resume() // totalPausedMillis += 250 - 110 = 140 (not 250 - 200 = 50)

        clock = 300L
        val safeIndices = game.state.value!!.cells.indices.filter { !game.state.value!!.cells[it].isMine }
        for (i in safeIndices) game.revealCell(i)

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [110,200] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.endMatch(com.gamesuite.core.GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch() // a fresh board should always be fully playable again
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    // ---------------------------------------------------------------------------------------
    // Screen-facing state: the exploded cell, flags surviving a loss, the finished-board tally,
    // the per-board counter and the pause-aware live clock.
    // ---------------------------------------------------------------------------------------

    private fun testContext() = GameContext(
        activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
        localPlayerIndex = 0,
        transport = LocalPassAndPlayTransport()
    )

    /**
     * A daily seed whose first reveal at cell 0 leaves EASY's board still live (neither won nor
     * lost). A flood from one corner opening every safe cell is astronomically unlikely, but
     * searching for a seed makes that a certainty instead of a hope: the tests below can then
     * assert on a board that has hidden safe cells and hidden mines left.
     */
    private fun liveSeed(): Long = (1L..200L).first { seed ->
        val probe = newGame(CpuDifficulty.EASY)
        probe.startMatch(dailySeed = seed)
        probe.revealCell(0)
        !probe.state.value!!.isOver
    }

    private fun liveGame(): MinesweeperGame {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = liveSeed())
        game.revealCell(0)
        assertFalse("precondition: the first reveal must leave a live board", game.state.value!!.isOver)
        return game
    }

    @Test
    fun `losing a board records the exploded cell, keeps flags on the board, and counts as a finished board`() {
        val game = liveGame()
        val before = game.state.value!!
        assertNull("no exploded cell on a live board", before.explodedIndex)

        val mines = before.cells.indices.filter { before.cells[it].isMine }
        val wrongFlag = before.cells.indices.first { !before.cells[it].isMine && before.cells[it].state == CellState.HIDDEN }
        val flaggedMine = mines[0]
        val hitMine = mines[1]
        game.toggleFlag(flaggedMine)
        game.toggleFlag(wrongFlag)
        assertEquals(0, game.puzzlesFailed.value)

        game.revealCell(hitMine)

        val s = game.state.value!!
        assertTrue(s.exploded)
        assertFalse(s.won)
        assertEquals("the exploded cell is the mine that was revealed", hitMine, s.explodedIndex)
        assertEquals(CellState.REVEALED, s.cells[hitMine].state)
        assertEquals("a correctly flagged mine keeps its flag", CellState.FLAGGED, s.cells[flaggedMine].state)
        assertEquals("a wrong flag stays on the board so it can be marked", CellState.FLAGGED, s.cells[wrongFlag].state)
        assertEquals(2, s.flagCount)
        assertEquals("flagCount must keep agreeing with the flags on the board", s.flagCount, s.cells.count { it.state == CellState.FLAGGED })
        assertTrue(
            "every unflagged mine is revealed",
            mines.filter { it != flaggedMine }.all { s.cells[it].state == CellState.REVEALED }
        )
        assertEquals("a lost board is a finished board", 1, game.puzzlesFailed.value)
        assertEquals(0, game.puzzlesSolved.value)

        // The lost board takes no more input, and is counted exactly once.
        game.revealCell(mines[2])
        game.toggleFlag(flaggedMine)
        assertEquals(s, game.state.value)
        assertEquals(1, game.puzzlesFailed.value)
    }

    @Test
    fun `winning a board leaves explodedIndex null and does not count it as failed`() {
        val game = liveGame()
        val safeIndices = game.state.value!!.cells.indices.filter { !game.state.value!!.cells[it].isMine }
        for (i in safeIndices) game.revealCell(i)

        val s = game.state.value!!
        assertTrue(s.won)
        assertNull(s.explodedIndex)
        assertEquals(1, game.puzzlesSolved.value)
        assertEquals(0, game.puzzlesFailed.value)
    }

    @Test
    fun `boardNumber counts every fresh board, and a new session clears the failed tally`() {
        val game = liveGame()
        val first = game.boardNumber.value
        assertTrue("a board has been dealt", first > 0)

        val mine = game.state.value!!.cells.indices.first { game.state.value!!.cells[it].isMine }
        game.revealCell(mine)
        assertEquals(1, game.puzzlesFailed.value)

        game.playAgain()
        assertEquals(first + 1, game.boardNumber.value)
        game.startMatch(dailySeed = 5L)
        game.startMatch(dailySeed = 5L) // re-dealing the same daily seed is still a distinct board
        assertEquals(first + 3, game.boardNumber.value)
        assertEquals("dealing boards must not clear the session tally", 1, game.puzzlesFailed.value)

        game.init(testContext())
        assertEquals("a new session starts with nothing finished", 0, game.puzzlesFailed.value)
        assertEquals(0, game.puzzlesSolved.value)
    }

    @Test
    fun `activeElapsedMillis is null before the first reveal, freezes while paused, and ends equal to the finished time`() {
        var clock = 0L
        val game = MinesweeperGame(nowMillis = { clock })
        game.init(testContext())
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = liveSeed())
        assertNull("no stopwatch before the first reveal", game.activeElapsedMillis())

        clock = 1_000L
        game.revealCell(0) // starts the timer at t=1000
        assertFalse(game.state.value!!.isOver)
        assertEquals(0L, game.activeElapsedMillis())

        clock = 3_000L
        assertEquals(2_000L, game.activeElapsedMillis())

        game.pause() // pausedAt = 3000
        clock = 10_000L
        assertEquals("the live clock must not tick while paused", 2_000L, game.activeElapsedMillis())

        game.resume() // 7000ms spent paused
        clock = 11_000L
        assertEquals(3_000L, game.activeElapsedMillis()) // 11000 - 1000 - 7000

        val mine = game.state.value!!.cells.indices.first { game.state.value!!.cells[it].isMine }
        game.revealCell(mine) // losing the board freezes the clock too
        assertEquals(3_000L, game.finishedElapsedMillis.value)
        clock = 50_000L
        assertEquals(
            "once the board is decided, the live clock reads exactly the recorded time",
            game.finishedElapsedMillis.value,
            game.activeElapsedMillis()
        )
    }
}
