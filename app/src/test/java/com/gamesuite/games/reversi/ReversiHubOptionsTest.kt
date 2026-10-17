package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub's "who goes first" option and two-human Pass & Play behaviour for Reversi. */
class ReversiHubOptionsTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true))

    private fun game(
        players: List<PlayerInfo>,
        mode: PlayMode,
        firstOpener: Int? = null
    ): ReversiGame {
        val g = ReversiGame()
        if (firstOpener != null) g.firstOpenerIndex = firstOpener
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return g
    }

    @Test
    fun defaultFirstBoardIsOpenedBySeatZero() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.startMatch()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun firstOpenerOneMakesSeatOneMoveFirstThenAlternates() {
        val g = game(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, firstOpener = 1)
        g.startMatch()
        val first = g.state.value!!
        assertEquals(1, first.currentPlayerIndex)
        assertTrue(first.players[1].isBot)
        // The opener owns 28 and 35 (its legal opening moves exist).
        assertEquals(1, first.cells[28])
        assertEquals(1, first.cells[35])
        assertTrue(first.legalMoves.isNotEmpty())

        g.playAgain()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        g.playAgain()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun outOfRangeFirstOpenerFallsBackToSeatZero() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, firstOpener = 5)
        g.startMatch()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        val n = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, firstOpener = -1)
        n.startMatch()
        assertEquals(0, n.state.value!!.currentPlayerIndex)
    }

    @Test
    fun resumedGameIgnoresFirstOpener() {
        val a = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        a.startMatch()
        val saved = a.snapshot()
        assertNotNull(saved)

        val b = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, firstOpener = 1)
        assertTrue(b.restore(saved!!))
        assertEquals(0, b.state.value!!.currentPlayerIndex)
        // Next board follows the restored alternation (seat 1), not the launch option.
        b.playAgain()
        assertEquals(1, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun passAndPlayEitherHumanMovesUndoTakesBackOneMoveAndHintWorks() {
        val g = game(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.startMatch()
        val start = g.state.value!!
        g.placeDisc(start.legalMoves.sorted().first())
        val afterOne = g.state.value!!
        assertEquals(1, afterOne.currentPlayerIndex)
        assertFalse(afterOne.players[1].isBot)

        // Seat 1 is a human: a hint is offered and it is a legal move.
        val hint = g.hint()
        assertNotNull(hint)
        assertTrue(hint!! in afterOne.legalMoves)

        g.placeDisc(afterOne.legalMoves.sorted().first())
        assertTrue(g.canUndo)
        g.undo()
        // One undo takes back only the second human move.
        assertEquals(afterOne.cells, g.state.value!!.cells)
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        g.undo()
        assertEquals(start.cells, g.state.value!!.cells)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test
    fun cpuFirstGameHasNoHintUntilTheCpuMoves() {
        val g = game(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, firstOpener = 1)
        g.startMatch()
        assertEquals(null, g.hint())
        g.playBotTurn()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertNotNull(g.hint())
    }
}
