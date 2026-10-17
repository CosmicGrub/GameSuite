package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub's per-launch options for Connect Four: who opens the first board, and pass & play with two humans. */
class ConnectFourHubOptionsTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Bo"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun game(players: List<PlayerInfo>, mode: PlayMode, firstOpener: Int?): ConnectFourGame {
        val g = ConnectFourGame()
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.firstOpenerIndex = firstOpener
        return g
    }

    @Test
    fun `default opener is seat 0 and alternates`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, null)
        g.startMatch()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `first board opens with the chosen seat then alternates`() {
        val g = game(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, 1)
        g.startMatch()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        assertNull("the option is consumed", g.firstOpenerIndex)
        g.playAgain()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `an out of range opener is clamped`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 7)
        g.startMatch()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a resumed game ignores the opener option`() {
        val a = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, null)
        a.startMatch()
        a.dropDisc(3)
        val snap = a.snapshot()!!

        val b = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1)
        assertTrue(b.restore(snap))
        assertNull(b.firstOpenerIndex)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `pass and play lets both humans play and names the winner`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, null)
        g.startMatch()
        // Ann stacks column 0, Bo column 1; Ann wins with four vertically.
        listOf(0, 1, 0, 1, 0, 1, 0).forEach { g.dropDisc(it) }
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertTrue(s.lastAction.startsWith("Ann"))
        assertFalse(s.players.any { it.isBot })
    }

    @Test
    fun `pass and play undo takes back only the last move and hint works`() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, null)
        g.startMatch()
        g.dropDisc(3)
        g.dropDisc(4)
        assertNotNull(g.hint())
        g.undo()
        val s = g.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(1, s.cells.count { it != null })
        assertNotNull(g.hint())
    }
}
