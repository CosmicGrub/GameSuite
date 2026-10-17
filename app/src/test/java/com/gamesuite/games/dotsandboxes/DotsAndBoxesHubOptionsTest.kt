package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Game Hub options for Dots and Boxes: first mover, and two-human pass and play. */
class DotsAndBoxesHubOptionsTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Bo"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun game(
        players: List<PlayerInfo>,
        mode: PlayMode,
        firstOpener: Int
    ): DotsAndBoxesGame {
        val g = DotsAndBoxesGame()
        g.firstOpenerIndex = firstOpener
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test
    fun defaultFirstOpenerIsSeatZero() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun firstOpenerNamesTheSeatForTheFirstBoard() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun alternationContinuesAfterTheChosenOpener() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 1)
        g.playAgain()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun cpuCanOpenTheFirstBoardAndRosterOrderIsUnchanged() {
        val g = game(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, 1)
        val s = g.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertTrue(s.players[1].isBot)
        assertEquals("you", s.players[0].playerId)
        g.playBotTurn()
        assertNotNull(g.state.value)
        assertTrue(g.state.value!!.horizontalEdges.any { it } || g.state.value!!.verticalEdges.any { it })
    }

    @Test
    fun outOfRangeFirstOpenerIsClamped() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 7)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun resumedGameIgnoresFirstOpener() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        g.drawHorizontalEdge(0, 0) // seat 1 to move now
        val snap = g.snapshot()!!

        val resumed = DotsAndBoxesGame()
        resumed.firstOpenerIndex = 0
        resumed.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
        assertTrue(resumed.restore(snap))
        assertEquals(1, resumed.state.value!!.currentPlayerIndex)
    }

    @Test
    fun passAndPlayNamesTheMoverAndGoesAgainOnABox() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        g.drawHorizontalEdge(0, 0) // Ann
        g.drawHorizontalEdge(1, 0) // Bo
        g.drawVerticalEdge(0, 0) // Ann
        g.drawVerticalEdge(0, 1) // Bo completes box (0,0)
        val s = g.state.value!!
        assertEquals(0, s.boxOwner[0]?.let { 1 - it }) // owner is seat 1
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(listOf(0, 1), s.scores)
        assertTrue(s.lastAction.startsWith("Bo claimed"))
        assertTrue(s.lastAction.endsWith("going again"))
    }

    @Test
    fun passAndPlayNeverPlaysBotMovesAndUndoTakesBackOneHumanEdge() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        g.playBotTurn() // no bot: must do nothing
        assertFalse(g.state.value!!.horizontalEdges.any { it })
        g.drawHorizontalEdge(0, 0)
        g.drawHorizontalEdge(1, 0)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.undo()
        val s = g.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertTrue(s.horizontalEdges[0])
        assertFalse(s.horizontalEdges[5])
    }

    @Test
    fun winnerIsReportedByName() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, 0)
        for (r in 0..5) for (c in 0 until 5) g.drawHorizontalEdge(r, c)
        for (r in 0 until 5) for (c in 0..5) g.drawVerticalEdge(r, c)
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertNotNull(s.winnerPlayerId)
        val winnerName = s.players.first { it.playerId == s.winnerPlayerId }.displayName
        assertTrue(s.lastAction.startsWith(winnerName))
    }
}
