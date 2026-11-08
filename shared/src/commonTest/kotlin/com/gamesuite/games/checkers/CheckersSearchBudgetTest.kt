package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckersSearchBudgetTest {
    private fun cpuToMove(): CheckersGame {
        val game = CheckersGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo("p0", "P0", isBot = false),
                    PlayerInfo("p1", "P1", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.HARD
        game.startMatch()
        return game
    }

    @Test
    fun `an expired deadline still yields a plan after the depth-1 pass`() {
        val game = cpuToMove()
        val job = game.beginBotTurn()!!
        var calls = 0
        val plan = job.compute(timeBudgetMs = 10, nowMs = { if (calls++ == 0) 0L else 1_000_000L })
        assertNotNull(plan)
        assertTrue(calls < 100, "clock polled $calls times")
        assertTrue(game.applyBotPlan(plan!!))
    }

    @Test
    fun `cancellation still returns a plan promptly`() {
        val game = cpuToMove()
        assertNotNull(game.beginBotTurn()!!.compute(isCancelled = { true }))
    }

    @Test
    fun `a generous budget matches the unconstrained result`() {
        val game = cpuToMove()
        val job = game.beginBotTurn()!!
        var t = 0L
        val a = job.compute(timeBudgetMs = 10_000_000, nowMs = { t += 1; t })
        val b = job.compute()
        assertEquals(a?.summary, b?.summary)
    }
}
