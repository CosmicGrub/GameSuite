package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub's first-mover choice: [MancalaGame.openingSeatIndex]. */
class MancalaOpeningSeatTest {

    private fun newGame(botIndex: Int? = null): MancalaGame {
        val game = MancalaGame()
        game.init(
            GameContext(
                activeMode = if (botIndex == null) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p0", displayName = "P0", isBot = botIndex == 0),
                    PlayerInfo(playerId = "p1", displayName = "P1", isBot = botIndex == 1)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.MEDIUM
        return game
    }

    @Test
    fun `default opening seat is zero`() {
        val g = newGame()
        g.startMatch()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `seat one opens the first board in pass and play`() {
        val g = newGame()
        g.openingSeatIndex = 1
        g.startMatch()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        // Seat 0 may not move; seat 1 may.
        g.sow(0, 0)
        assertEquals(48, g.state.value!!.pits.sum())
        assertEquals("Game started", g.state.value!!.lastAction)
        g.sow(1, 7)
        assertTrue(g.state.value!!.lastSowPath.isNotEmpty())
    }

    @Test
    fun `a CPU in seat one can open the game`() {
        val g = newGame(botIndex = 1)
        g.openingSeatIndex = 1
        g.startMatch()
        g.playBotTurn()
        assertTrue(g.state.value!!.lastSowPath.first() in 7..12)
    }

    @Test
    fun `out of range opening seat falls back to zero`() {
        val g = newGame()
        g.openingSeatIndex = 5
        g.startMatch()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `play again deals with seat zero`() {
        val g = newGame()
        g.openingSeatIndex = 1
        g.startMatch()
        g.playAgain()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `opening seat survives a snapshot and restore`() {
        val a = newGame()
        a.openingSeatIndex = 1
        a.startMatch()
        val snap = a.snapshot()
        assertNotNull(snap)
        val b = newGame()
        assertTrue(b.restore(snap!!))
        assertEquals(1, b.openingSeatIndex)
        assertEquals(1, b.state.value!!.currentPlayerIndex)
        assertTrue(b.state.value!!.restored)
    }

    @Test
    fun `an old save without the field still restores`() {
        val old = """{"pits":[4,4,4,4,4,4,0,4,4,4,4,4,4,0],"currentPlayerIndex":0,"lastAction":"x","roundOver":false,"winnerPlayerId":null,""" +
            """"lastSowPath":[],"lastCapture":null,"players":[{"playerId":"p0","isBot":false},""" +
            """{"playerId":"p1","isBot":false}],"scoreP1":0,"scoreP2":0,"draws":0,"difficulty":"MEDIUM"}"""
        val g = newGame()
        assertTrue(g.restore(old))
        assertEquals(0, g.openingSeatIndex)
        assertFalse(g.matchOver.value)
    }

    @Test
    fun `pass and play undo takes back exactly one human move`() {
        val g = newGame()
        g.startMatch()
        g.sow(0, 0)
        g.sow(1, 7)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        assertTrue(g.state.value!!.restored)
    }
}
