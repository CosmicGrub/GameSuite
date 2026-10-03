package com.gamesuite.games.breakout

import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
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

/**
 * Drives [BreakoutGame] entirely through its public surface (state/movePaddle/launchBall/tick),
 * same idiom as AirHockeyGameTest.kt: [BreakoutGame.state] is a public `MutableState`, so tests
 * set up exact ball/paddle/brick scenarios directly through it rather than reaching into
 * BreakoutGame's actually-private collision helpers.
 */
class BreakoutGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): BreakoutGame {
        val game = BreakoutGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "You")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        game.startMatch()
        return game
    }

    @Test
    fun ballBouncesOffTheLeftWall() {
        val game = newGame()
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.02f, 0.5f),
            ballVel = Offset(-0.5f, 0.3f),
            ballLaunched = true
        )
        game.tick(0.05f)
        assertTrue("expected vel.x to flip sign off the left wall", game.state.value.ballVel.x > 0f)
        assertNotNull(game.state.value.lastWallBounce)
    }

    @Test
    fun ballBouncesOffTheTopWall() {
        val game = newGame()
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.5f, 0.02f),
            ballVel = Offset(0.1f, -0.5f),
            ballLaunched = true
        )
        game.tick(0.05f)
        assertTrue("expected vel.y to flip sign off the top wall", game.state.value.ballVel.y > 0f)
    }

    @Test
    fun hittingABrickDestroysItAndAwardsTheRightScore() {
        val game = newGame()
        val s0 = game.state.value
        val brickIndex = 0 // top-left brick, row 0 -- worth the most
        val rect = brickRectForTest(brickIndex, s0.rows, s0.cols)
        val expectedValue = (s0.rows - 0) * 10

        game.state.value = s0.copy(
            ballPos = Offset(rect.centerX, rect.bottom + BreakoutGame.BALL_RADIUS_Y - 0.001f),
            ballVel = Offset(0f, -0.4f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val s1 = game.state.value
        assertFalse("the hit brick should be gone", s1.bricks[brickIndex])
        assertEquals(expectedValue, s1.score)
        assertTrue("a vertical approach should flip vel.y", s1.ballVel.y > 0f)
        assertNotNull(s1.lastBrickBroken)
        assertEquals(brickIndex / s0.cols, s1.lastBrickBroken!!.row)
    }

    @Test
    fun clearingEveryBrickAdvancesTheLevelAndRegeneratesTheGrid() {
        val game = newGame()
        val s0 = game.state.value
        // Leave exactly one brick standing, positioned where the ball is about to arrive.
        val lastIndex = s0.bricks.size - 1
        val bricks = s0.bricks.toMutableList().apply { indices.forEach { this[it] = it == lastIndex } }
        val rect = brickRectForTest(lastIndex, s0.rows, s0.cols)
        game.state.value = s0.copy(
            bricks = bricks,
            ballPos = Offset(rect.centerX, rect.bottom + BreakoutGame.BALL_RADIUS_Y - 0.001f),
            ballVel = Offset(0f, -0.4f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val s1 = game.state.value
        assertEquals(2, s1.level)
        assertTrue("a fresh level's grid should be fully restocked", s1.bricks.all { it })
        assertFalse("ball should be re-racked, not still in flight", s1.ballLaunched)
        assertNotNull(s1.lastLevelCleared)
        assertEquals(2, s1.lastLevelCleared!!.level)
    }

    @Test
    fun paddleHitSteersTheBallTowardWhereItWasCaught() {
        val game = newGame()
        val s0 = game.state.value
        // Catch it on the RIGHT half of the paddle -- expect the ball to steer rightward (positive vel.x).
        val hitX = s0.paddleX + s0.paddleHalfWidth * 0.6f
        game.state.value = s0.copy(
            ballPos = Offset(hitX, BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT - BreakoutGame.BALL_RADIUS_Y + 0.001f),
            ballVel = Offset(0f, 0.5f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val vel = game.state.value.ballVel
        assertTrue("expected the paddle bounce to send the ball back upward", vel.y < 0f)
        assertTrue("catching it right-of-center should steer the ball rightward, got $vel", vel.x > 0f)
        assertNotNull(game.state.value.lastPaddleBounce)
    }

    @Test
    fun losingTheBallDecrementsLivesAndReRacksIt() {
        val game = newGame()
        val s0 = game.state.value
        game.state.value = s0.copy(ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.tick(0.05f)

        val s1 = game.state.value
        assertEquals(BreakoutGame.STARTING_LIVES - 1, s1.lives)
        assertFalse("run should not be over with lives remaining", s1.gameOver)
        assertFalse("ball should be re-racked pre-launch", s1.ballLaunched)
        assertEquals(Offset(s1.paddleX, BreakoutGame.RESTING_BALL_Y), s1.ballPos)
        assertNotNull(s1.lastLifeLost)
        assertFalse(s1.lastLifeLost!!.gameOver)
    }

    @Test
    fun losingTheLastLifeEndsTheRunButNotTheSession() {
        val game = newGame()
        var s = game.state.value.copy(lives = 1)
        game.state.value = s
        s = s.copy(ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.state.value = s
        game.tick(0.05f)

        val after = game.state.value
        assertEquals(0, after.lives)
        assertTrue("the run should be over", after.gameOver)
        assertTrue(after.lastLifeLost!!.gameOver)
        assertFalse("the SESSION should not be over just because one run ended", game.matchOver.value)
    }

    @Test
    fun aNearHorizontalBounceGetsNudgedBackTowardVertical() {
        val game = newGame()
        val speed = 0.6f
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.5f, 0.5f),
            ballVel = Offset(speed, 0.001f), // effectively horizontal
            ballLaunched = true
        )
        game.tick(0.001f)

        val vel = game.state.value.ballVel
        assertTrue(
            "expected the degenerate-rally guard to restore real vertical speed, got $vel",
            kotlin.math.abs(vel.y) >= speed * BreakoutGame.MIN_VERTICAL_SPEED_FRACTION - 0.01f
        )
    }

    @Test
    fun movePaddleClampsToBoundsAndCarriesTheRestingBall() {
        val game = newGame()
        game.movePaddle(-5f) // way out of range
        val s = game.state.value
        assertEquals(s.paddleHalfWidth, s.paddleX, 0.0001f)
        assertEquals(Offset(s.paddleX, BreakoutGame.RESTING_BALL_Y), s.ballPos)
    }

    @Test
    fun launchBallIsIdempotentAndAlwaysAimsUpward() {
        val game = newGame()
        game.launchBall()
        val firstVel = game.state.value.ballVel
        assertTrue("expected a real launch to have nonzero speed", firstVel.getDistance() > 0f)
        assertTrue("a launch should always send the ball upward", firstVel.y < 0f)

        game.launchBall() // second call while already launched -- must not re-roll
        assertEquals(firstVel, game.state.value.ballVel)
    }

    @Test
    fun playAgainOnlyWorksAfterGameOverAndResetsTheRun() {
        val game = newGame()
        game.playAgain() // not over yet -- must no-op
        assertEquals(BreakoutGame.STARTING_LIVES, game.state.value.lives)

        game.state.value = game.state.value.copy(lives = 0, gameOver = true, score = 40)
        game.playAgain()

        val s = game.state.value
        assertEquals(BreakoutGame.STARTING_LIVES, s.lives)
        assertEquals(0, s.score)
        assertFalse(s.gameOver)
    }

    @Test
    fun leaveSessionReportsTheBestScoreThisSessionAndEndsTheSession() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        // First run scores 30, ends.
        game.state.value = game.state.value.copy(lives = 1, score = 30)
        game.state.value = game.state.value.copy(ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.tick(0.05f)
        assertTrue(game.state.value.gameOver)

        game.playAgain()
        // Second run only reaches score 10 before the session is abandoned.
        game.state.value = game.state.value.copy(score = 10)
        game.leaveSession()

        assertNotNull(reported)
        val score = reported!!.scores.single()
        assertEquals("the session's best run (30), not the last run's (10), should be reported", 30, score.score)
        assertTrue(score.isWinner)
        assertTrue(game.matchOver.value)
    }

    @Test
    fun leaveSessionOnAZeroScoreSessionReportsNotAWinner() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }
        game.leaveSession()
        assertEquals(0, reported!!.scores.single().score)
        assertFalse(reported!!.scores.single().isWinner)
    }

    @Test
    fun startMatchPauseAndResumeNeverThrowOrInvokeTheMatchEndListener() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        game.startMatch()
        game.pause()
        game.resume()

        assertNull("none of startMatch/pause/resume should end the session", reported)
    }

    // ---- pause: freezes a ball in flight, idempotent, resumed only by the player ----

    private fun launchedGame(): BreakoutGame {
        val game = newGame()
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.5f, 0.6f),
            ballVel = Offset(0.1f, 0.4f),
            ballLaunched = true
        )
        return game
    }

    @Test
    fun pauseFreezesTheBallAndIsIdempotent() {
        val game = launchedGame()
        game.pause()
        game.pause() // a second onPause with no resume in between must change nothing
        assertTrue(game.paused.value)

        val before = game.state.value
        game.tick(0.05f)
        assertEquals("a paused ball must not move", before, game.state.value)
    }

    @Test
    fun pauseBeforeLaunchIsANoOp() {
        val game = newGame() // ball resting on the paddle
        game.pause()
        assertFalse("nothing is simulated while the ball rests, so there is nothing to pause", game.paused.value)
    }

    @Test
    fun pauseAfterTheRunIsOverIsANoOp() {
        val game = launchedGame()
        game.state.value = game.state.value.copy(gameOver = true)
        game.pause()
        assertFalse(game.paused.value)
    }

    @Test
    fun hostResumeLeavesThePausedRunPausedAndOnlyResumePlayUnfreezesIt() {
        val game = launchedGame()
        game.pause()

        game.resume() // the Activity coming back to the foreground
        assertTrue("coming back must not drop the player into a live ball", game.paused.value)
        val frozen = game.state.value
        game.tick(0.05f)
        assertEquals(frozen, game.state.value)

        game.resumePlay()
        game.resumePlay() // idempotent
        assertFalse(game.paused.value)
        game.tick(0.05f)
        assertTrue("the ball should move again once the player resumes", game.state.value.ballPos != frozen.ballPos)
    }

    @Test
    fun aPausedRunIgnoresPaddleInput() {
        val game = launchedGame()
        val x = game.state.value.paddleX
        game.pause()
        game.movePaddle(0.9f)
        assertEquals(x, game.state.value.paddleX, 0.0001f)
        game.resumePlay()
        game.movePaddle(0.9f)
        assertEquals(0.9f, game.state.value.paddleX, 0.0001f)
    }

    @Test
    fun startingANewRunClearsPause() {
        val game = launchedGame()
        game.pause()
        assertTrue(game.paused.value)
        game.startMatch()
        assertFalse(game.paused.value)
    }

    @Test
    fun pauseNeverEndsTheSession() {
        val game = launchedGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }
        game.pause()
        game.resume()
        game.resumePlay()
        assertNull(reported)
        assertFalse(game.matchOver.value)
    }

    // ---- finished-run bookkeeping for the screen's leave-confirm ----

    @Test
    fun runsFinishedCountsOnlyRunsThatActuallyEnded() {
        val game = newGame()
        assertEquals(0, game.runsFinished.value)

        // Losing a life with lives to spare does not finish the run.
        game.state.value = game.state.value.copy(ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.tick(0.05f)
        assertEquals(0, game.runsFinished.value)

        // Losing the last life does.
        game.state.value = game.state.value.copy(lives = 1, ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.tick(0.05f)
        assertTrue(game.state.value.gameOver)
        assertEquals(1, game.runsFinished.value)

        // Further ticks on a finished run must not count it twice.
        game.tick(0.05f)
        assertEquals(1, game.runsFinished.value)
    }

    @Test
    fun leaveSessionDoesNotBankARunStillInProgress() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        // A big score, but the run never ended.
        game.state.value = game.state.value.copy(score = 500)
        game.leaveSession()

        val score = reported!!.scores.single()
        assertEquals("an unfinished run's partial score must not be reported", 0, score.score)
        assertFalse(score.isWinner)
    }

    @Test
    fun leavingMidRunAfterAFinishedRunStillReportsTheFinishedOne() {
        val game = newGame()
        var reported: GameResult? = null
        game.setOnMatchEnd { reported = it }

        game.state.value = game.state.value.copy(lives = 1, score = 70, ballPos = Offset(0.5f, 0.995f), ballVel = Offset(0f, 0.5f), ballLaunched = true)
        game.tick(0.05f)
        assertEquals(1, game.runsFinished.value)

        game.playAgain()
        game.state.value = game.state.value.copy(score = 900) // second run, still going
        game.leaveSession()

        assertEquals(70, reported!!.scores.single().score)
    }

    // ---- ball geometry: a circle on screen, resting exactly on the paddle ----

    @Test
    fun theBallIsACircleOnScreenAndRestsExactlyOnThePaddle() {
        // The field is BOARD_ASPECT times taller than wide, so the y radius in normalized units is
        // the x radius divided by that ratio (equal physical size on both axes).
        assertEquals(BreakoutGame.BALL_RADIUS, BreakoutGame.BALL_RADIUS_Y * BreakoutGame.BOARD_ASPECT, 0.00001f)
        // Resting ball's bottom edge touches the paddle's top edge: no floating gap, no overlap.
        assertEquals(
            BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT,
            BreakoutGame.RESTING_BALL_Y + BreakoutGame.BALL_RADIUS_Y,
            0.00001f
        )
    }

    @Test
    fun brickCollisionUsesTheScreenSpaceBallNotAStretchedEllipse() {
        val game = newGame()
        val s0 = game.state.value
        // Bottom row, middle brick: nothing below it, so the only thing in range is that brick.
        val index = (s0.rows - 1) * s0.cols + 3
        val rect = brickRectForTest(index, s0.rows, s0.cols)

        fun bricksAfterBallAt(offsetBelowBottom: Float): List<Boolean> {
            val g = newGame()
            g.state.value = g.state.value.copy(
                ballPos = Offset(rect.centerX, rect.bottom + offsetBelowBottom),
                ballVel = Offset(0f, -0.001f), // barely moving: the ball effectively stays put
                ballLaunched = true
            )
            g.tick(0.001f)
            return g.state.value.bricks
        }

        // Well inside one ball radius (measured on screen): the brick breaks.
        assertFalse(bricksAfterBallAt(BreakoutGame.BALL_RADIUS_Y * 0.9f)[index])
        // Beyond one on-screen radius but still inside the old stretched-ellipse radius: untouched.
        assertTrue(
            "the ball must not break a brick it is visibly not touching",
            bricksAfterBallAt(BreakoutGame.BALL_RADIUS_Y * 1.15f)[index]
        )
    }

    // ---- bounce positions and long frames, in numbers ----

    @Test
    fun theTopWallClampsTheBallToItsOnScreenRadius() {
        val game = newGame()
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.5f, 0.005f),
            ballVel = Offset(0f, -0.3f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val s = game.state.value
        // The ball's top edge rests on the wall, so its centre is one y-radius down (not one x-radius).
        assertEquals(BreakoutGame.BALL_RADIUS_Y, s.ballPos.y, 0.000001f)
        assertEquals("a fully elastic wall only flips vel.y", 0.3f, s.ballVel.y, 0.000001f)
    }

    @Test
    fun theSideWallClampsTheBallToItsOnScreenRadius() {
        val game = newGame()
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.005f, 0.7f), // below the brick rows, above the paddle
            ballVel = Offset(-0.3f, 0.3f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val s = game.state.value
        assertEquals(BreakoutGame.BALL_RADIUS, s.ballPos.x, 0.000001f)
        assertEquals("a fully elastic wall only flips vel.x", 0.3f, s.ballVel.x, 0.000001f)
    }

    @Test
    fun aCenterPaddleHitReturnsTheBallStraightUpFromTheTopOfThePaddle() {
        val game = newGame()
        val s0 = game.state.value
        val paddleTop = BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT
        game.state.value = s0.copy(
            ballPos = Offset(s0.paddleX, paddleTop - BreakoutGame.BALL_RADIUS_Y + 0.002f),
            ballVel = Offset(0f, 0.5f),
            ballLaunched = true
        )
        game.tick(0.001f)

        val s = game.state.value
        assertEquals("the ball's bottom edge sits on the paddle's top edge", paddleTop - BreakoutGame.BALL_RADIUS_Y, s.ballPos.y, 0.000001f)
        assertEquals("a dead-centre catch has no sideways kick", 0f, s.ballVel.x, 0.000001f)
        assertEquals("the bounce keeps the ball's speed", -0.5f, s.ballVel.y, 0.00001f)
    }

    @Test
    fun aSlowFrameCannotCarryTheFastestBallPastThePaddle() {
        val game = newGame()
        val s0 = game.state.value
        val topSpeed = 0.68f * BreakoutGame.MAX_SPEED_MULTIPLIER // Hard tier at the speed cap
        val paddleTop = BreakoutGame.PADDLE_Y - BreakoutGame.PADDLE_HALF_HEIGHT
        // Just above the paddle's collision window. One unsplit 0.05s step would carry the ball
        // 0.054 of the field height, from here to below the paddle (window depth 0.045): a skip.
        game.state.value = s0.copy(
            ballPos = Offset(s0.paddleX, paddleTop - BreakoutGame.BALL_RADIUS_Y - 0.001f),
            ballVel = Offset(0f, topSpeed),
            ballLaunched = true
        )
        game.tick(0.05f)

        val s = game.state.value
        assertNotNull("the paddle should have caught the ball", s.lastPaddleBounce)
        assertTrue("the ball should be heading back up, got ${s.ballVel}", s.ballVel.y < 0f)
        assertEquals("no life should be lost", BreakoutGame.STARTING_LIVES, s.lives)
    }

    // ---- personal-best rule ----

    @Test
    fun aRunThatScoredNothingNeverSetsARecord() {
        assertFalse("first ever run ending on 0 is not a 'New best!'", BreakoutGame.isNewBestScore(existing = null, score = 0))
        assertFalse(BreakoutGame.isNewBestScore(existing = 0, score = 0))
    }

    @Test
    fun aPositiveScoreSetsARecordOnlyIfItBeatsTheExistingBest() {
        assertTrue(BreakoutGame.isNewBestScore(existing = null, score = 10))
        assertTrue(BreakoutGame.isNewBestScore(existing = 50, score = 60))
        assertFalse(BreakoutGame.isNewBestScore(existing = 50, score = 50))
        assertFalse(BreakoutGame.isNewBestScore(existing = 50, score = 40))
    }

    // ---- geometry helper mirroring BreakoutGame's own private brickRect, for test setup only ----
    private data class TestRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
    }

    private fun brickRectForTest(index: Int, rows: Int, cols: Int): TestRect {
        val gap = 0.006f
        val topY = 0.08f
        val areaHeight = 0.42f
        val col = index % cols
        val row = index / cols
        val brickWidth = (1f - gap * (cols + 1)) / cols
        val brickHeight = (areaHeight - gap * (rows + 1)) / rows
        val left = gap + col * (brickWidth + gap)
        val top = topY + gap + row * (brickHeight + gap)
        return TestRect(left, top, left + brickWidth, top + brickHeight)
    }
}
