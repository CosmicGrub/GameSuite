package com.gamesuite.games.dominoes

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.GameSpeed
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.ui.dominoPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hub launch options for Dominoes: the first-opener override and the pause scaling helper. */
class DominoHubOptionsTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true))

    private fun newGame(players: List<PlayerInfo>, mode: PlayMode): DominoGame {
        val game = DominoGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    @Test
    fun overrideSeatOneOpensTheFirstHandEveryTime() {
        repeat(40) {
            val g = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
            g.firstOpenerIndex = 1
            g.startMatch()
            assertEquals(1, g.state.value!!.currentPlayerIndex)
        }
    }

    @Test
    fun overrideSeatOneWorksAgainstTheCpu() {
        repeat(20) {
            val g = newGame(humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.firstOpenerIndex = 1
            g.startMatch()
            assertEquals(1, g.state.value!!.currentPlayerIndex)
        }
    }

    @Test
    fun overrideIsConsumedByTheFirstHandOnly() {
        val g = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.firstOpenerIndex = 1
        g.startMatch()
        assertNull(g.firstOpenerIndex)
        // The next hand uses the standard rule: the opener holds the highest double / heaviest tile.
        repeat(20) {
            g.playAgain()
            val s = g.state.value!!
            val best = { hand: List<Domino> ->
                hand.filter { it.isDouble }.maxOfOrNull { it.a } ?: -1
            }
            val doubles = s.players.map { best(it.hand) }
            if (doubles.any { it >= 0 }) assertEquals(doubles.maxOrNull()!!, doubles[s.currentPlayerIndex])
        }
    }

    @Test
    fun outOfRangeOverrideFallsBackToTheStandardRule() {
        val g = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.firstOpenerIndex = 7
        g.startMatch()
        assertTrue(g.state.value!!.currentPlayerIndex in 0..1)
        assertNull(g.firstOpenerIndex)
    }

    @Test
    fun initClearsAStaleOverride() {
        val g = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.firstOpenerIndex = 1
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
        assertNull(g.firstOpenerIndex)
    }

    @Test
    fun passAndPlayUndoTakesBackOneHumanMove() {
        val g = newGame(twoHumans, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.firstOpenerIndex = 1
        g.startMatch()
        val before = g.state.value!!
        val tile = before.players[1].hand.first()
        g.playDomino(1, tile, attachToLeft = true)
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertTrue(g.canUndo)
        g.undo()
        val after = g.state.value!!
        assertEquals(1, after.currentPlayerIndex)
        assertEquals(before.players[1].hand, after.players[1].hand)
        assertTrue(after.chain.isEmpty())
    }

    @Test
    fun pauseScalesBotBeatsAndLeavesTinyOnesAlone() {
        assertEquals(800L, dominoPause(GameSpeed.NORMAL, 800L))
        assertEquals(1200L, dominoPause(GameSpeed.RELAXED, 800L))
        assertEquals(440L, dominoPause(GameSpeed.QUICK, 800L))
        assertEquals(50L, dominoPause(GameSpeed.SLOW, 50L))
        assertEquals(60L, dominoPause(GameSpeed.QUICK, 60L))
    }
}
