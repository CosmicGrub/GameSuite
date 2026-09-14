package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
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

class ConnectFourGameTest {

    /** Two human players — used for hand-driving both "sides" directly, same pattern as DotsAndBoxesGameTest. */
    private fun newTwoHumanGame(): ConnectFourGame {
        val game = ConnectFourGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Player 1"),
                    PlayerInfo(playerId = "p2", displayName = "Player 2")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /** One human (index 0), one bot at [difficulty] (index 1) — used for bot-behavior tests. */
    private fun newVsBotGame(difficulty: CpuDifficulty): ConnectFourGame {
        val game = ConnectFourGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "you", displayName = "You"),
                    PlayerInfo(playerId = "cpu", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    private fun index(row: Int, col: Int, cols: Int = 7) = row * cols + col

    @Test
    fun `a fresh board has every cell empty, the correct dimensions, and player 0 to move`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s = game.state.value!!
        assertEquals(6, s.rows)
        assertEquals(7, s.cols)
        assertEquals(42, s.cells.size)
        assertTrue(s.cells.all { it == null })
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.boardOver)
        assertNull(s.winnerPlayerId)
        assertNull(s.winningLine)
    }

    @Test
    fun `successive drops into the same column stack from the bottom up`() {
        val game = newTwoHumanGame()
        game.startMatch()
        game.dropDisc(3)
        game.dropDisc(3)
        val s = game.state.value!!
        assertEquals("the first disc into an empty column must land on the very bottom row", 0, s.cells[index(5, 3)])
        assertEquals("the second disc must stack directly on top of the first, not fall past it", 1, s.cells[index(4, 3)])
        assertNull(s.cells[index(3, 3)])
    }

    @Test
    fun `dropping a disc that does not complete a win passes the turn to the other player`() {
        val game = newTwoHumanGame()
        game.startMatch()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        game.dropDisc(0)
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        game.dropDisc(1)
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `dropping into a full column is a no-op`() {
        val game = newTwoHumanGame()
        game.startMatch()
        // Alternating players never make 4-in-a-row here (P1/P2/P1/P2/P1/P2 down one column).
        repeat(6) { game.dropDisc(0) }
        val before = game.state.value!!
        assertFalse(before.boardOver)
        val filled = (0 until before.rows).count { r -> before.cells[index(r, 0)] != null }
        assertEquals(6, filled)

        game.dropDisc(0)
        assertEquals("dropping into an already-full column must be a total no-op", before, game.state.value)
    }

    @Test
    fun `dropping into an out-of-range column is ignored, not a crash`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val before = game.state.value
        game.dropDisc(-1)
        assertEquals(before, game.state.value)
        game.dropDisc(7)
        assertEquals(before, game.state.value)
    }

    @Test
    fun `connecting four horizontally wins the board for the mover and updates the session tally`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s0 = game.state.value!!
        val cells = s0.cells.toMutableList()
        cells[index(5, 0)] = 0
        cells[index(5, 1)] = 0
        cells[index(5, 2)] = 0
        game.state.value = s0.copy(cells = cells, currentPlayerIndex = 0)

        game.dropDisc(3)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(s0.players[0].playerId, s.winnerPlayerId)
        assertEquals(setOf(index(5, 0), index(5, 1), index(5, 2), index(5, 3)), s.winningLine!!.toSet())
        assertEquals(1, game.sessionWins.value[s0.players[0].playerId])
        assertEquals(0, game.sessionDraws.value)
    }

    @Test
    fun `connecting four vertically wins the board for the mover`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s0 = game.state.value!!
        val cells = s0.cells.toMutableList()
        cells[index(5, 0)] = 0
        cells[index(4, 0)] = 0
        cells[index(3, 0)] = 0
        game.state.value = s0.copy(cells = cells, currentPlayerIndex = 0)

        game.dropDisc(0) // lands at row 2, completing rows 5-2 of column 0
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(s0.players[0].playerId, s.winnerPlayerId)
        assertEquals(setOf(index(5, 0), index(4, 0), index(3, 0), index(2, 0)), s.winningLine!!.toSet())
    }

    @Test
    fun `connecting four diagonally (down-right) wins the board for the mover`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s0 = game.state.value!!
        val cells = s0.cells.toMutableList()
        cells[index(2, 0)] = 0
        cells[index(3, 1)] = 0
        cells[index(4, 2)] = 0
        game.state.value = s0.copy(cells = cells, currentPlayerIndex = 0)

        game.dropDisc(3) // column 3 is empty, so this lands at row 5 -- completes (2,0)-(3,1)-(4,2)-(5,3)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(s0.players[0].playerId, s.winnerPlayerId)
        assertEquals(setOf(index(2, 0), index(3, 1), index(4, 2), index(5, 3)), s.winningLine!!.toSet())
    }

    @Test
    fun `connecting four diagonally (down-left) wins the board for the mover`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val s0 = game.state.value!!
        val cells = s0.cells.toMutableList()
        cells[index(2, 3)] = 0
        cells[index(3, 2)] = 0
        cells[index(4, 1)] = 0
        game.state.value = s0.copy(cells = cells, currentPlayerIndex = 0)

        game.dropDisc(0) // column 0 is empty, so this lands at row 5 -- completes (2,3)-(3,2)-(4,1)-(5,0)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertEquals(s0.players[0].playerId, s.winnerPlayerId)
        assertEquals(setOf(index(2, 3), index(3, 2), index(4, 1), index(5, 0)), s.winningLine!!.toSet())
    }

    @Test
    fun `filling the board with no four-in-a-row anywhere is a draw`() {
        // A full 42-cell fill, independently verified offline (a brute-force
        // scan checking every possible 4-in-a-row in all 4 directions) to
        // contain none for either player. The very last cell (row 5, col 6)
        // is left empty here so the test's own dropDisc() call is what
        // actually exercises the engine's draw detection, not just this
        // fixture.
        val fullBoardExceptLastCell: List<Int?> = listOf(
            0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 1,
            1, 1, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 0,
            1, null
        )
        val game = newTwoHumanGame()
        game.startMatch()
        val s0 = game.state.value!!
        game.state.value = s0.copy(cells = fullBoardExceptLastCell, currentPlayerIndex = 0)

        game.dropDisc(6)
        val s = game.state.value!!
        assertTrue(s.boardOver)
        assertNull("a draw must not report a winner", s.winnerPlayerId)
        assertNull("a draw must not report a winning line", s.winningLine)
        assertTrue(s.cells.none { it == null })
        assertEquals(1, game.sessionDraws.value)
    }

    @Test
    fun `the starting player alternates across successive boards`() {
        val game = newTwoHumanGame()
        game.startMatch()
        val first = game.state.value!!.currentPlayerIndex
        game.playAgain()
        val second = game.state.value!!.currentPlayerIndex
        game.playAgain()
        val third = game.state.value!!.currentPlayerIndex
        assertEquals(first, third)
        assertFalse(first == second)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newTwoHumanGame()
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch()
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `dropDisc is rejected once the session has ended via leaveSession, even if the board itself was not yet over`() {
        val game = newTwoHumanGame()
        game.startMatch()
        game.dropDisc(0)
        assertFalse(game.state.value!!.boardOver)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.dropDisc(1)
        assertEquals("a dropDisc() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `playBotTurn is rejected once the session has ended via leaveSession, even if the board itself was not yet over`() {
        val game = newVsBotGame(CpuDifficulty.EASY)
        game.startMatch()
        val s0 = game.state.value!!
        game.state.value = s0.copy(currentPlayerIndex = 1) // force it to be the bot's turn, without finishing the board

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.playBotTurn()
        assertEquals("a playBotTurn() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `every difficulty bot always plays some legal move without crashing, across many full games`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(5) {
                val game = newVsBotGame(difficulty)
                game.startMatch()
                playFullGameAlternatingHumanDropsAndBotTurns(game)
                assertTrue(game.state.value!!.boardOver)
            }
        }
    }

    @Test
    fun `the bot takes an immediate winning move when one is available, at every difficulty`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newVsBotGame(difficulty)
            game.startMatch()
            val s0 = game.state.value!!
            // Bot (player index 1) has 3 in a row on the bottom row (cols 0-2); column 3 is the only winning move.
            val cells = s0.cells.toMutableList()
            cells[index(5, 0)] = 1
            cells[index(5, 1)] = 1
            cells[index(5, 2)] = 1
            game.state.value = s0.copy(cells = cells, currentPlayerIndex = 1)

            game.playBotTurn()
            val s = game.state.value!!
            assertTrue("difficulty=$difficulty: the bot must take an immediate winning move", s.boardOver)
            assertEquals(s0.players[1].playerId, s.winnerPlayerId)
        }
    }

    @Test
    fun `the bot blocks an immediate opponent winning move when it has no winning move of its own, at every difficulty`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newVsBotGame(difficulty)
            game.startMatch()
            val s0 = game.state.value!!
            // Human (player index 0) has 3 in a row on the bottom row (cols 0-2);
            // column 3 would let them win next turn. It's the bot's (index 1) turn
            // now, with no winning move of its own available anywhere.
            val cells = s0.cells.toMutableList()
            cells[index(5, 0)] = 0
            cells[index(5, 1)] = 0
            cells[index(5, 2)] = 0
            game.state.value = s0.copy(cells = cells, currentPlayerIndex = 1)

            game.playBotTurn()
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty: the bot must block the opponent's immediate winning column", 1, s.cells[index(5, 3)])
            assertFalse("blocking must not itself complete a win", s.boardOver)
        }
    }

    @Test
    fun `HARD bot moves complete quickly even from an empty board`() {
        // A coarse performance sanity check (mirroring SudokuGameTest's own
        // timing tests), not a strict benchmark -- exists to catch a real
        // regression (e.g. a pruning bug that degrades back toward the full
        // unpruned tree), not to assert a specific number.
        val game = newVsBotGame(CpuDifficulty.HARD)
        game.startMatch()
        game.state.value = game.state.value!!.copy(currentPlayerIndex = 1) // force it to be the bot's turn on the empty board

        val start = System.nanoTime()
        game.playBotTurn()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("a single HARD-difficulty bot move took ${elapsedMs}ms -- unexpectedly slow for a depth-6 alpha-beta search on a 6x7 board", elapsedMs < 5000)
        assertNotNull("the bot must have actually placed a disc", game.state.value!!.cells.firstOrNull { it == 1 })
    }

    /** Drives a full vs-bot game to completion: on each turn, either let the bot move, or -- for the human -- drop into the first column with room, purely to exercise the engine end-to-end without crashing or looping forever. Mirrors DotsAndBoxesGameTest's own playFullGameAlternatingHumanTapsAndBotTurns. */
    private fun playFullGameAlternatingHumanDropsAndBotTurns(game: ConnectFourGame) {
        var guard = 0
        while (game.state.value?.boardOver == false) {
            guard++
            assertTrue("game did not terminate within a sane number of moves", guard < 100)
            val s = game.state.value!!
            if (s.players[s.currentPlayerIndex].isBot) {
                game.playBotTurn()
            } else {
                val col = firstLegalColumn(s) ?: break
                game.dropDisc(col)
            }
        }
    }

    private fun firstLegalColumn(s: ConnectFourState): Int? =
        (0 until s.cols).firstOrNull { c -> (0 until s.rows).any { r -> s.cells[index(r, c, s.cols)] == null } }
}
