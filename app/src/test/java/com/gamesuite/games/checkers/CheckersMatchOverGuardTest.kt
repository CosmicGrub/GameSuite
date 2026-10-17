package com.gamesuite.games.checkers

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's corner menu can end the session (abort, or leaveSession when earlier rounds were
 * already won) while a tap or the bot's delayed turn is still in flight. These pin the engine
 * side of that: once [CheckersGame.matchOver] is true, neither entry point may move a piece on
 * the abandoned board, and an abort reports exactly one aborted, score-less result.
 *
 * Rules coverage (mandatory capture, multi-jump, promotion, both loss conditions) lives in
 * shared/src/commonTest/.../CheckersGameTest.kt; this file only covers the match-over guards.
 */
class CheckersMatchOverGuardTest {

    /** Index 0 = human "You" (side 0, Dark). Index 1 = side 1, which MOVES FIRST in this engine. */
    private fun newGame(sideOneIsBot: Boolean): CheckersGame {
        val game = CheckersGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "you", displayName = "You"),
                    PlayerInfo(playerId = "cpu", displayName = "CPU", isBot = sideOneIsBot)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private data class Hop(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

    /** The first legal hop for whichever side is to move, read only through the public queries. */
    private fun firstLegalHop(game: CheckersGame): Hop {
        for (row in 0..7) for (col in 0..7) {
            val dest = game.legalDestinationsFrom(row, col).firstOrNull() ?: continue
            return Hop(row, col, dest.first, dest.second)
        }
        error("no legal hop on the opening board")
    }

    @Test
    fun `a legal move is accepted while the match is live (control for the guard test below)`() {
        val game = newGame(sideOneIsBot = false)
        game.startMatch()
        val before = game.state.value!!
        val hop = firstLegalHop(game)
        game.playMove(before.currentPlayerIndex, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)
        assertNotSame(before, game.state.value)
        assertNotNull(game.state.value!!.lastMove)
    }

    @Test
    fun `playMove is rejected once the session has ended`() {
        val game = newGame(sideOneIsBot = false)
        game.startMatch()
        val before = game.state.value!!
        val hop = firstLegalHop(game)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        game.playMove(before.currentPlayerIndex, hop.fromRow, hop.fromCol, hop.toRow, hop.toCol)

        assertSame("a move after matchOver must not touch the board", before, game.state.value)
    }

    @Test
    fun `playBotTurn moves the bot while the match is live (control for the guard test below)`() {
        val game = newGame(sideOneIsBot = true)
        game.startMatch()
        val before = game.state.value!!
        game.playBotTurn()
        assertNotSame(before, game.state.value)
    }

    @Test
    fun `playBotTurn is a no-op once the player has aborted`() {
        val game = newGame(sideOneIsBot = true)
        game.startMatch()
        val before = game.state.value!!

        game.abortMatch()
        assertTrue(game.matchOver.value)
        game.playBotTurn()

        assertSame("the bot must not move on an abandoned board", before, game.state.value)
    }

    @Test
    fun `abortMatch reports one aborted score-less result and a later leaveSession adds nothing`() {
        val game = newGame(sideOneIsBot = true)
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()

        game.abortMatch()
        game.leaveSession()

        assertEquals(1, results.size)
        assertTrue(results.single().wasAborted)
        assertTrue(results.single().scores.isEmpty())
    }

    @Test
    fun `leaveSession reports the running score so rounds already won still count`() {
        val game = newGame(sideOneIsBot = true)
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        game.scoreP1.value = 2
        game.scoreP2.value = 1

        game.leaveSession()

        val result = results.single()
        assertFalse(result.wasAborted)
        val you = result.scores.first { it.playerId == "you" }
        val cpu = result.scores.first { it.playerId == "cpu" }
        assertEquals(2, you.score)
        assertTrue(you.isWinner)
        assertEquals(1, cpu.score)
        assertFalse(cpu.isWinner)
    }
}
