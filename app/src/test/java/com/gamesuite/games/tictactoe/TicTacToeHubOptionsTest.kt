package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.GameSpeed
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.ui.scaledPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hub launch options for Tic-Tac-Toe: the first mover seat, two-human turns, undo, pause scaling. */
class TicTacToeHubOptionsTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun game(
        players: List<PlayerInfo>,
        mode: PlayMode,
        firstOpener: Int,
        wild: Boolean = false
    ): TicTacToeGame {
        val g = TicTacToeGame()
        g.wild = wild
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.firstOpenerIndex = firstOpener
        g.startMatch()
        return g
    }

    @Test
    fun `seat zero opens by default`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        assertEquals(1, g.currentPlayer.value)
    }

    @Test
    fun `named seat opens the first round then alternation continues`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1)
        assertEquals(2, g.currentPlayer.value)
        listOf(0, 3, 1, 4, 2).forEach { g.cellClicked(it) } // seat 2 plays 0,1,2 -> top row of O
        assertTrue(g.roundOver.value)
        assertEquals(1, g.scoreP2.value)
        g.playAgain()
        assertEquals("round 2 opens with seat 1", 1, g.currentPlayer.value)
    }

    @Test
    fun `cpu first game lets the bot move and keeps the symbols tied to seats`() {
        val g = game(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, 1)
        assertEquals(2, g.currentPlayer.value)
        g.playBotTurn()
        assertEquals(1, g.currentPlayer.value)
        assertEquals(1, g.board.value.count { it == 2 })
        assertEquals(0, g.board.value.count { it == 1 })
    }

    @Test
    fun `second human can move in pass and play and the wild symbol is the movers`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1, wild = true)
        g.chooseSymbol(1)
        g.cellClicked(4)
        assertEquals(1, g.board.value[4])
        assertEquals(1, g.currentPlayer.value)
        assertEquals("the pick resets for the next mover", 1, g.selectedSymbol.value)
    }

    @Test
    fun `undo takes back exactly one move in pass and play`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1)
        g.cellClicked(0)
        g.cellClicked(4)
        g.undo()
        assertEquals(0, g.board.value[4])
        assertEquals(2, g.board.value[0])
        assertEquals(1, g.currentPlayer.value)
    }

    @Test
    fun `resumed games ignore the first mover`() {
        val a = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        a.cellClicked(4)
        val snap = a.snapshot()!!
        val b = TicTacToeGame()
        b.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        b.firstOpenerIndex = 1
        assertTrue(b.restore(snap))
        assertEquals(2, b.currentPlayer.value)
    }

    @Test
    fun `pauses scale with game speed but tiny beats are untouched`() {
        assertEquals(500L, scaledPause(GameSpeed.NORMAL, 500L))
        assertEquals(1200L, scaledPause(GameSpeed.SLOW, 500L))
        assertEquals(50L, scaledPause(GameSpeed.SLOW, 50L))
    }
}
