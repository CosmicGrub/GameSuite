package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HARD-difficulty misere is TicTacToeGame's trickiest bot path (see the
 * class KDoc on [TicTacToeGame.misere]): completing a line normally WINS,
 * but under misere it LOSES, so a bot that naively reused the standard
 * win-seeking heuristic would race to complete its own line and hand the
 * round away. HARD delegates to full minimax (see [TicTacToeGame]'s KDoc on
 * `minimax`), which factors depth into the score so an immediate self-loss
 * (scored at the shallowest possible depth) is always dominated by any move
 * that isn't an immediate self-completion, as long as one legal alternative
 * exists — a deferred loss is always scored better than an immediate one,
 * and a draw or win beats both. This file drives that invariant directly:
 * several hand-built near-endgame boards, each with exactly one "trap" cell
 * that would complete a line for the bot and several safe alternatives, and
 * checks the bot never takes the trap while a safe move is available.
 *
 * `board`/`currentPlayer`/`difficulty`/`misere` are all public var/mutable
 * state on [TicTacToeGame], and `playBotTurn()` is the public entry point
 * the UI uses to drive the bot — so every fixture here is set up and
 * exercised entirely through that real public surface, no private minimax
 * internals reached into directly.
 */
class TicTacToeGameTest {

    /** Minimal 2-player context with the given player marked as the bot — mirrors how TicTacToeScreen wires a vs-bot match. */
    private fun newGame(botIsPlayer1: Boolean): TicTacToeGame {
        val game = TicTacToeGame()
        val players = listOf(
            PlayerInfo(playerId = "p1", displayName = "Player 1", isBot = botIsPlayer1),
            PlayerInfo(playerId = "p2", displayName = "Player 2", isBot = !botIsPlayer1)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = players,
                localPlayerIndex = if (botIsPlayer1) 1 else 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.HARD
        game.misere = true
        return game
    }

    /**
     * Runs one fixture: places [board] verbatim, sets the bot on the move,
     * fires [TicTacToeGame.playBotTurn], and asserts the [trapIndex] cell
     * (which would complete a line for the bot) is still empty afterward —
     * i.e. the bot picked one of the other, safe legal moves instead.
     */
    private fun assertBotAvoidsTrap(board: IntArray, botMark: Int, trapIndex: Int) {
        val game = newGame(botIsPlayer1 = botMark == 1)
        game.board.value = board.copyOf()
        game.currentPlayer.value = botMark

        game.playBotTurn()

        assertEquals(
            "HARD misere bot completed its own line at cell $trapIndex instead of using a safe alternative",
            0,
            game.board.value[trapIndex]
        )
    }

    // 0 = empty, 1 = P1, 2 = P2 (see TicTacToeGame.board's KDoc).

    @Test
    fun `misere HARD bot avoids completing a top row for player 2`() {
        // Row 0 (0,1,2) is one stone from completing for P2; cell 2 is the trap.
        // Cells 5,6,7,8 are all safe: placing P2 there completes no line.
        val board = intArrayOf(
            2, 2, 0,
            1, 1, 0,
            0, 0, 0
        )
        assertBotAvoidsTrap(board, botMark = 2, trapIndex = 2)
    }

    @Test
    fun `misere HARD bot avoids completing a main diagonal for player 2`() {
        // Diagonal (0,4,8) is one stone from completing for P2; cell 8 is the trap.
        val board = intArrayOf(
            2, 1, 1,
            0, 2, 0,
            0, 0, 0
        )
        assertBotAvoidsTrap(board, botMark = 2, trapIndex = 8)
    }

    @Test
    fun `misere HARD bot avoids completing a left column for player 2`() {
        // Column (0,3,6) is one stone from completing for P2; cell 0 is the trap.
        val board = intArrayOf(
            0, 1, 1,
            2, 0, 0,
            2, 0, 0
        )
        assertBotAvoidsTrap(board, botMark = 2, trapIndex = 0)
    }

    @Test
    fun `misere HARD bot avoids completing a line for player 1 too`() {
        // Same shape as the first fixture with marks swapped, and P1 as the
        // bot this time — the misere avoidance has to hold for either seat,
        // not just whichever player happens to move second.
        val board = intArrayOf(
            1, 1, 0,
            2, 2, 0,
            0, 0, 0
        )
        assertBotAvoidsTrap(board, botMark = 1, trapIndex = 2)
    }
}
