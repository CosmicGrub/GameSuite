package com.gamesuite.games.chess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChessSearchBudgetTest {
    private fun botToMove(): Pair<ChessGame, ChessBotRequest> {
        val game = ChessGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo("white", "White", isBot = false),
                    PlayerInfo("black", "Black", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.HARD
        game.startMatch()
        game.playMove(0, 12, 28) // e2-e4
        return game to game.botRequest()!!
    }

    @Test
    fun `an already expired deadline still returns a legal move after only the depth-1 pass`() {
        val (game, request) = botToMove()
        var calls = 0
        // First call (search start) = 0; every later call is far past any deadline.
        val clock = { if (calls++ == 0) 0L else 1_000_000L }
        val move = game.searchBotMove(request, timeBudgetMs = 10, nowMs = clock)
        assertNotNull(move)
        assertTrue((move.from to move.to) in game.allLegalMoves())
        assertTrue(calls < 100, "search must stop promptly, clock polled $calls times")
    }

    @Test
    fun `cancellation returns promptly with a legal move`() {
        val (game, request) = botToMove()
        val move = game.searchBotMove(request, isCancelled = { true })
        assertNotNull(move)
        assertTrue((move.from to move.to) in game.allLegalMoves())
    }

    @Test
    fun `a generous budget gives exactly the same move as no pressure`() {
        val (game, request) = botToMove()
        var t = 0L
        val ticking = { t += 1; t } // 1 ms per poll, far below the budget
        val a = game.searchBotMove(request, timeBudgetMs = 10_000_000, nowMs = ticking)
        val b = game.searchBotMove(request)
        assertEquals(a, b)
    }

    @Test
    fun `a mid-search deadline falls back to a completed shallower depth`() {
        val (game, request) = botToMove()
        var t = 0L
        val ticking = { t += 1; t }
        val move = game.searchBotMove(request, timeBudgetMs = 3_000, nowMs = ticking)
        assertNotNull(move)
        assertTrue((move.from to move.to) in game.allLegalMoves())
    }
}
