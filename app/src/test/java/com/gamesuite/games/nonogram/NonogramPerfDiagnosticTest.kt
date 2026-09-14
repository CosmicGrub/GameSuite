package com.gamesuite.games.nonogram

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Test

class NonogramPerfDiagnosticTest {
    private fun newGame(difficulty: CpuDifficulty): NonogramGame {
        val game = NonogramGame(nowMillis = { 0L })
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

    @Test
    fun `diagnostic HARD tier, 40 seeds, printed one at a time`() {
        val timings = mutableListOf<Long>()
        for (seed in 1L..40L) {
            val game = newGame(CpuDifficulty.HARD)
            val start = System.nanoTime()
            game.startMatch(dailySeed = seed)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            timings.add(elapsedMs)
            println("HARD seed=$seed took ${elapsedMs}ms")
        }
        println("SUMMARY min=${timings.min()} max=${timings.max()} avg=${timings.average()} total=${timings.sum()}")
    }
}
