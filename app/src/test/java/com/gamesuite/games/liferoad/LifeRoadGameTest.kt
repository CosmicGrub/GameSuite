package com.gamesuite.games.liferoad

import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadGameTest {
    private fun game(players: List<PlayerInfo> = LRT.roster(3), seed: Long = 4L, configure: LifeRoadGame.() -> Unit = {}): LifeRoadGame {
        val g = LifeRoadGame(seedSource = { seed })
        g.configure()
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test fun initRequiresTwoToSixPlayers() {
        for (n in listOf(1, 7)) {
            val g = LifeRoadGame()
            val failed = try {
                g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, List(n) { PlayerInfo("p$it", "P$it") }, 0, LocalPassAndPlayTransport()))
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue("$n seats", failed)
        }
    }

    @Test fun startMatchCreatesAStartForkState() {
        val s = game().state.value!!
        assertEquals(Phase.START_FORK, s.phase)
        assertTrue(s.players.all { it.nodeId == "S0" })
    }

    @Test fun abortMatchReportsNoScores() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
        assertTrue(g.matchOver.value)
    }

    @Test fun endMatchReportsScoresAndWinners() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(3, result!!.scores.size)
        assertFalse(result!!.wasAborted)
    }

    @Test fun botStepAlwaysChangesTheState() {
        val g = game(LRT.roster(3, bots = 3), configure = { config = config.copy(size = RoadSize.QUICK) })
        var n = 0
        while (g.state.value?.over != true && n++ < 3000) {
            val before = g.state.value
            assertTrue(g.botToMove(before!!))
            g.playBotStep()
            assertTrue(g.state.value !== before)
        }
        assertTrue(g.state.value!!.over)
        assertTrue(g.sessionWins.value.values.sum() >= 1)
    }

    @Test fun playAgainKeepsSessionWins() {
        val g = game(LRT.roster(2, bots = 2), configure = { config = config.copy(size = RoadSize.QUICK) })
        while (g.state.value?.over != true) g.playBotStep()
        val wins = g.sessionWins.value
        assertTrue(wins.values.sum() >= 1)
        g.playAgain()
        assertEquals(wins, g.sessionWins.value)
        assertFalse(g.state.value!!.over)
        assertEquals(1, g.state.value!!.openerSeat)
    }

    @Test fun playAgainIsBlockedWhenTheKidsTimeIsUp() {
        val g = game(LRT.roster(2, bots = 2), configure = { config = config.copy(size = RoadSize.QUICK) })
        while (g.state.value?.over != true) g.playBotStep()
        val over = g.state.value
        LaunchPolicy.timeUp = true
        try {
            g.playAgain()
            assertTrue(g.state.value === over)
        } finally {
            LaunchPolicy.resetToFailClosed()
        }
    }

    @Test fun humanActionsAreRefusedOnACpuTurn() {
        val g = game(LRT.roster(2, bots = 1))
        // start fork: seat 0 (person) chooses, then seat 1 (CPU) is on turn
        assertTrue(g.perform(LifeRoadAction.ChooseBranch("LRN.0")))
        assertFalse(g.perform(LifeRoadAction.ChooseBranch("LRN.0")))
    }

    @Test fun snapshotAndRestoreRoundTripThroughTheWrapper() {
        val g = game()
        g.perform(LifeRoadAction.ChooseBranch("WRK.0"))
        val text = g.snapshot()
        assertNotNull(text)
        val h = game(seed = 99L)
        assertTrue(h.restore(text!!))
        assertEquals(g.state.value, h.state.value)
        assertFalse(h.restore("garbage"))
        assertNull(LifeRoadGame().snapshot())
    }

    @Test fun moduleMetadataMatchesTheDoc() {
        val g = LifeRoadGame()
        assertEquals("life-road", g.gameId)
        assertEquals(2, g.minPlayers); assertEquals(6, g.maxPlayers)
        assertEquals(GameCategory.BOARD, g.category)
        assertEquals(setOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes.toSet())
        g.difficulty = CpuDifficulty.HARD
        assertEquals(CpuDifficulty.HARD, g.config.difficulty)
    }
}
