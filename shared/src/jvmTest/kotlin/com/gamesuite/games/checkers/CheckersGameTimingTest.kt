package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HARD-tier search timing (justifies CheckersGame's own HARD_SEARCH_DEPTH companion KDoc) --
 * split out from CheckersGameTest.kt (which lives in commonTest, portable to any KMP target)
 * because this one test genuinely needs real JVM APIs -- System.nanoTime() and
 * String.format() -- with no equivalent in Kotlin's common stdlib. Lives in jvmTest for the
 * same reason LanMultiplayerTransportTest.kt does: both real targets (Android, Desktop) are
 * actual JVMs, so a jvmTest source set reaches both without needing per-target duplication.
 */
class CheckersGameTimingTest {

    private fun newGame(p0Bot: Boolean = false, p1Bot: Boolean = false): CheckersGame {
        val game = CheckersGame()
        val players = listOf(
            PlayerInfo(playerId = "p0", displayName = "P0", isBot = p0Bot),
            PlayerInfo(playerId = "p1", displayName = "P1", isBot = p1Bot)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = players,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    @Test
    fun `HARD bot turn timing stays within a sane per-move budget on this JVM host`() {
        val game = newGame(p0Bot = true, p1Bot = true)
        game.difficulty = CpuDifficulty.HARD
        game.startMatch()
        val timingsMs = mutableListOf<Long>()
        var turns = 0
        while (turns < 60) {
            if (game.state.value!!.gameOver) break
            val start = System.nanoTime()
            game.playBotTurn()
            timingsMs += (System.nanoTime() - start) / 1_000_000
            turns++
        }
        val avg = timingsMs.average()
        val worst = timingsMs.max()
        println("HARD bot turn timing over ${timingsMs.size} turns on this JVM host: avg=${"%.2f".format(avg)}ms, worst=${worst}ms")
        // Generous regression guard (not a tight perf target) against accidentally raising the
        // search depth far enough to make HARD noticeably laggy for a UI bot-turn delay budget.
        assertTrue(avg < 3000, "HARD bot average turn time too high: ${avg}ms")
        assertTrue(worst < 8000, "HARD bot worst-case turn time too high: ${worst}ms")
    }
}
