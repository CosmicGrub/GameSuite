package com.gamesuite.games.snake

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The run lifecycle around the engine: countdown, pause, game over, play again, reporting. */
class SnakeGameTest {

    private fun newGame(extras: Map<String, String> = emptyMap()): SnakeGame {
        val game = SnakeGame { Random(5) }
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.configure(extras)
        game.startMatch()
        return game
    }

    private fun SnakeGame.finishCountdown() {
        repeat(SnakeGame.COUNTDOWN_FROM) { tickCountdown() }
    }

    private fun SnakeGame.stepUntilOver(limit: Int = 500) {
        var n = 0
        while (phase.value == SnakePhase.PLAYING && n++ < limit) step()
    }

    @Test
    fun metadataIsSinglePlayerArcade() {
        val g = SnakeGame()
        assertEquals("snake", g.gameId)
        assertEquals("Snake", g.displayName)
        assertEquals(1, g.minPlayers)
        assertEquals(1, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes)
    }

    @Test
    fun startingARunBeginsTheThreeTwoOneCountdown() {
        val g = newGame()
        assertTrue(g.started.value)
        assertEquals(SnakePhase.COUNTDOWN, g.phase.value)
        assertEquals(3, g.countdown.value)
        assertEquals(3, g.state.value.snake.size)
    }

    @Test
    fun theCountdownLeadsToPlayAfterThreeBeats() {
        val g = newGame()
        g.tickCountdown(); assertEquals(2, g.countdown.value)
        g.tickCountdown(); assertEquals(1, g.countdown.value)
        assertEquals(SnakePhase.COUNTDOWN, g.phase.value)
        g.tickCountdown()
        assertEquals(SnakePhase.PLAYING, g.phase.value)
        assertEquals(0, g.countdown.value)
    }

    @Test
    fun theSnakeDoesNotMoveBeforeTheCountdownEnds() {
        val g = newGame()
        val before = g.state.value.snake
        g.step()
        assertEquals(before, g.state.value.snake)
    }

    @Test
    fun stepMovesTheSnakeOnceThePhaseIsPlaying() {
        val g = newGame()
        g.finishCountdown()
        val head = g.state.value.snake.first()
        g.step()
        assertEquals(head + 1, g.state.value.snake.first())
    }

    @Test
    fun aTurnQueuedDuringTheCountdownIsUsedOnTheFirstStep() {
        val g = newGame()
        assertTrue(g.steer(SnakeDirection.UP))
        g.finishCountdown()
        val head = g.state.value.snake.first()
        g.step()
        assertEquals(head - g.state.value.cols, g.state.value.snake.first())
    }

    @Test
    fun pauseFreezesTheClockAndTheInput() {
        val g = newGame()
        g.finishCountdown()
        g.pause()
        assertTrue(g.paused.value)
        val before = g.state.value.snake
        g.step()
        assertEquals(before, g.state.value.snake)
        assertFalse(g.steer(SnakeDirection.UP))
    }

    @Test
    fun pauseDuringTheCountdownHoldsTheNumber() {
        val g = newGame()
        g.pause()
        g.tickCountdown()
        assertEquals(3, g.countdown.value)
        g.resumePlay()
        g.tickCountdown()
        assertEquals(2, g.countdown.value)
    }

    @Test
    fun pauseAndResumeAreIdempotentAndResumeLeavesThePlayerInCharge() {
        val g = newGame()
        g.finishCountdown()
        g.pause(); g.pause()
        assertTrue(g.paused.value)
        g.resume() // the Activity coming back must not unfreeze the run
        assertTrue(g.paused.value)
        g.resumePlay(); g.resumePlay()
        assertFalse(g.paused.value)
    }

    @Test
    fun pauseDoesNothingBeforeAStartOrAfterTheRunEnds() {
        val idle = SnakeGame()
        idle.pause()
        assertFalse(idle.paused.value)

        val g = newGame()
        g.finishCountdown()
        g.stepUntilOver()
        assertEquals(SnakePhase.OVER, g.phase.value)
        g.pause()
        assertFalse(g.paused.value)
    }

    @Test
    fun hittingASolidWallEndsTheRunAndCountsIt() {
        val g = newGame(mapOf("walls" to "solid"))
        g.finishCountdown()
        g.stepUntilOver()
        assertEquals(SnakePhase.OVER, g.phase.value)
        assertEquals(1, g.runsFinished.value)
        assertEquals(SnakeStatus.DEAD, g.state.value.status)
        assertNotNull(g.state.value.deathCause)
    }

    @Test
    fun wrapAroundKeepsAStraightSnakeAliveLongerThanTheBoardIsWide() {
        val g = newGame(mapOf("walls" to "wrap", "grid" to "small"))
        g.finishCountdown()
        // A straight run across a 12-wide board and back round to the start: no wall, no food in
        // the way is guaranteed, but a wall death is impossible with wrapping.
        repeat(30) { g.step() }
        assertTrue(g.state.value.deathCause != SnakeDeathCause.WALL)
        assertTrue(g.state.value.wrap)
    }

    @Test
    fun configureAppliesTheHubChoices() {
        val g = newGame(mapOf("speed" to "fast", "grid" to "big", "walls" to "wrap"))
        assertEquals(20, g.state.value.cols)
        assertTrue(g.state.value.wrap)
        assertEquals(SnakeSpeed.QUICK, g.config.speed)
        assertEquals(SnakeEngine.tickMillis(SnakeSpeed.QUICK, 1), g.tickMillis())
    }

    @Test
    fun playAgainStartsAFreshRunOnlyAfterAFinishedOne() {
        val g = newGame()
        val firstSeq = g.state.value.runSeq
        g.playAgain() // mid-countdown: ignored
        assertEquals(firstSeq, g.state.value.runSeq)

        g.finishCountdown()
        g.stepUntilOver()
        g.playAgain()
        assertEquals(SnakePhase.COUNTDOWN, g.phase.value)
        assertEquals(firstSeq + 1, g.state.value.runSeq)
        assertEquals(0, g.state.value.score)
        assertEquals(SnakeStatus.RUNNING, g.state.value.status)
    }

    @Test
    fun leavingReportsTheBestFinishedScoreToTheStats() {
        val g = newGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.finishCountdown()
        g.stepUntilOver()
        val score = g.state.value.score
        g.leaveSession()
        assertTrue(g.matchOver.value)
        val r = result!!
        assertFalse(r.wasAborted)
        assertEquals("p1", r.scores.single().playerId)
        assertEquals(score, r.scores.single().score)
    }

    @Test
    fun abortingMidRunIsNeverScored() {
        val g = newGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.finishCountdown()
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test
    fun newBestNeedsAPositiveScoreThatBeatsTheOldBest() {
        assertFalse(SnakeGame.isNewBestScore(null, 0))
        assertTrue(SnakeGame.isNewBestScore(null, 1))
        assertTrue(SnakeGame.isNewBestScore(10, 11))
        assertFalse(SnakeGame.isNewBestScore(10, 10))
        assertFalse(SnakeGame.isNewBestScore(10, 3))
    }
}
