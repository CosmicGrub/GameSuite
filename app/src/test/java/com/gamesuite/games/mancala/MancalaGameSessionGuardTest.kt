package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Session-end guards on [MancalaGame]'s input functions: once leaveSession()/abortMatch() has
 * ended the session, a stray pit tap or an in-flight bot turn must not mutate the board or tally
 * another win into scores that were already reported. Also pins how a finishing move scores
 * (win, tie) so the result panel has something true to render.
 */
class MancalaGameSessionGuardTest {

    /** Player 0 and player 1 are human unless [botIndex] names one of them as the CPU. */
    private fun newGame(botIndex: Int? = null): MancalaGame {
        val game = MancalaGame()
        game.init(
            GameContext(
                activeMode = if (botIndex == null) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p0", displayName = "P0", isBot = botIndex == 0),
                    PlayerInfo(playerId = "p1", displayName = "P1", isBot = botIndex == 1)
                ),
                localPlayerIndex = if (botIndex == 0) 1 else 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.MEDIUM
        return game
    }

    @Test
    fun `a legal sow before the session ends still moves the stones and passes the turn`() {
        val game = newGame()
        game.startMatch()

        game.sow(0, 0)

        val s = game.state.value!!
        assertEquals(0, s.pits[0])
        assertEquals(listOf(5, 5, 5, 5), s.pits.slice(1..4))
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test
    fun `sow is ignored once the session was left`() {
        val game = newGame()
        game.startMatch()
        game.leaveSession()
        val before = game.state.value

        game.sow(0, 0)

        assertEquals(before, game.state.value)
        assertEquals(0, game.scoreP1.value)
    }

    @Test
    fun `sow is ignored once the match was aborted and the abort reports no scores`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        game.abortMatch()
        val before = game.state.value

        game.sow(0, 0)

        assertEquals(before, game.state.value)
        assertTrue(game.matchOver.value)
        assertEquals(1, results.size)
        assertTrue(results.single().wasAborted)
        assertTrue(results.single().scores.isEmpty())
    }

    @Test
    fun `playBotTurn moves the bot normally but is a no-op once the session ended`() {
        val running = newGame(botIndex = 0)
        running.startMatch()
        val initial = running.state.value
        running.playBotTurn()
        assertNotEquals("the CPU should have sown before the session ended", initial, running.state.value)

        val ended = newGame(botIndex = 0)
        ended.startMatch()
        ended.leaveSession()
        val before = ended.state.value
        ended.playBotTurn()
        assertEquals(before, ended.state.value)
    }

    @Test
    fun `a finishing move scores the round once and later taps cannot score it again`() {
        val game = newGame()
        game.startMatch()
        // Player 0's only stone sits in pit 5 and drops into their own store, emptying their side.
        // Player 1's remaining 4 stones sweep into player 1's store: 11 vs 7.
        game.state.value = MancalaState(
            pits = listOf(0, 0, 0, 0, 0, 1, 10, 4, 0, 0, 0, 0, 0, 3),
            currentPlayerIndex = 0,
            lastAction = "Fixture"
        )

        game.sow(0, 5)

        val finished = game.state.value!!
        assertTrue(finished.roundOver)
        assertEquals("p0", finished.winnerPlayerId)
        assertEquals(11, finished.pits[6])
        assertEquals(7, finished.pits[13])
        assertEquals(1, game.scoreP1.value)

        game.sow(1, 7)
        game.sow(0, 5)

        assertEquals(finished, game.state.value)
        assertEquals(1, game.scoreP1.value)
        assertEquals(0, game.scoreP2.value)
        assertEquals(0, game.draws.value)
    }

    @Test
    fun `equal stores at the end is a tie with no winner and counts as a draw`() {
        val game = newGame()
        game.startMatch()
        // 5 + 1 = 6 in player 0's store; player 1's 6 remaining stones sweep to theirs: 6 vs 6.
        game.state.value = MancalaState(
            pits = listOf(0, 0, 0, 0, 0, 1, 5, 6, 0, 0, 0, 0, 0, 0),
            currentPlayerIndex = 0,
            lastAction = "Fixture"
        )

        game.sow(0, 5)

        val finished = game.state.value!!
        assertTrue(finished.roundOver)
        assertNull(finished.winnerPlayerId)
        assertEquals(6, finished.pits[6])
        assertEquals(6, finished.pits[13])
        assertEquals(1, game.draws.value)
        assertEquals(0, game.scoreP1.value)
        assertEquals(0, game.scoreP2.value)
    }
}
