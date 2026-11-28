package com.gamesuite.games.propertytycoon

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

class PropertyTycoonGameTest {
    private fun game(players: List<PlayerInfo> = PTT.roster(3), configure: PropertyTycoonGame.() -> Unit = { seed = 4L }): PropertyTycoonGame {
        val g = PropertyTycoonGame()
        g.configure()
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test fun gameModuleDeclaresIdPropertyTycoonBoardCategoryTwoToSixPlayers() {
        val g = PropertyTycoonGame()
        assertEquals("property-tycoon", g.gameId)
        assertEquals(GameCategory.BOARD, g.category)
        assertEquals(2, g.minPlayers); assertEquals(6, g.maxPlayers)
        assertEquals(setOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes.toSet())
    }

    @Test fun initRejectsRostersOutsideTwoToSixSeats() {
        for (n in listOf(1, 7)) {
            val failed = try {
                PropertyTycoonGame().init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, List(n) { PlayerInfo("p$it", "P$it") }, 0, LocalPassAndPlayTransport()))
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue("$n", failed)
        }
    }

    @Test fun startMatchDealsStartingPearlsAndPicksTheFirstPlayerFromTheSeed() {
        val a = game().state.value!!
        val b = game().state.value!!
        assertEquals(a, b)
        assertTrue(a.players.all { it.cash == 1600 })
        assertEquals(a.currentSeat, PtRng(4L).let { r -> r.shuffled(PtCards.tide.map { it.id }); r.shuffled(PtCards.guild.map { it.id }); r.nextInt(3) })
        val quick = game { seed = 4L; length = GameLength.QUICK }.state.value!!
        assertTrue(quick.players.all { it.cash == 1200 })
        val forced = game { seed = 4L; firstSeat = 2 }.state.value!!
        assertEquals(2, forced.currentSeat)
    }

    @Test fun abortMatchEndsWithWasAbortedAndNoScores() {
        val g = game()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test fun endMatchReportsRankedScoresWithASingleWinner() {
        val g = game(PTT.roster(2, bots = 2)) { seed = 9L; length = GameLength.QUICK }
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        var n = 0
        while (g.state.value?.over != true && n++ < 20000) g.playBotStep()
        assertTrue(g.state.value!!.over)
        g.leaveSession()
        assertEquals(2, result!!.scores.size)
        assertEquals(1, result!!.scores.count { it.isWinner })
    }

    @Test fun botStepAlwaysChangesTheState() {
        val g = game(PTT.roster(3, bots = 3))
        var n = 0
        while (g.state.value?.over != true && n++ < 20000) {
            val before = g.state.value
            g.playBotStep()
            assertTrue(g.state.value !== before)
        }
        assertTrue(g.state.value!!.over)
    }

    @Test fun humanCommandsAreRefusedForTheWrongSeat() {
        val g = game()
        val s = g.state.value!!
        assertFalse(g.command((s.currentSeat + 1) % 3, PtCommand.Roll))
        assertTrue(g.command(s.currentSeat, PtCommand.Roll))
    }

    @Test fun playAgainKeepsSessionWinsAndIsBlockedWhenTheKidsTimeIsUp() {
        val g = game(PTT.roster(2, bots = 2)) { seed = 9L; length = GameLength.QUICK }
        while (g.state.value?.over != true) g.playBotStep()
        val wins = g.sessionWins.value
        assertEquals(1, wins.values.sum())
        LaunchPolicy.timeUp = true
        try {
            val over = g.state.value
            g.playAgain()
            assertTrue(g.state.value === over)
        } finally {
            LaunchPolicy.resetToFailClosed()
        }
        g.playAgain()
        assertFalse(g.state.value!!.over)
        assertEquals(wins, g.sessionWins.value)
    }

    @Test fun resumeAfterRestoreKeepsTheSameCurrentPlayerAndPhase() {
        val g = game()
        val s = g.state.value!!
        g.command(s.currentSeat, PtCommand.Roll)
        val text = g.snapshot()
        assertNotNull(text)
        val h = game { seed = 99L }
        assertTrue(h.restore(text!!))
        assertEquals(g.state.value, h.state.value)
        assertFalse(h.restore("nope"))
        assertNull(PropertyTycoonGame().snapshot())
        g.difficulty = CpuDifficulty.HARD
    }
}
