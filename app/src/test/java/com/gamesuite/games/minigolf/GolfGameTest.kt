package com.gamesuite.games.minigolf

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GolfGameTest {
    private val solo = listOf(PlayerInfo("you", "You"))
    private val two = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        players: List<PlayerInfo> = solo, holes: Int = 9, limit: Int = 6, seed: Int = 1,
        mode: PlayMode = if (players.size == 1) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): GolfGame {
        var now = 500L
        val g = GolfGame(Random(seed)) { now += 5; now }
        g.holeCount = holes; g.limit = limit; g.difficulty = level
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    /** The exact line that holes out from where the current seat's ball lies. */
    private fun holeOut(g: GolfGame): GolfShot {
        val s = g.state.value!!
        return GolfBot.chooseShot(GolfCourse.holes[s.hole], s.ballX[s.current], s.ballY[s.current], CpuDifficulty.HARD, Random(1), exact = true)
    }

    private fun finishHole(g: GolfGame) {
        var guard = 0
        while (g.state.value!!.phase == GolfPhase.AIM && guard++ < 30) {
            val s = g.state.value!!
            val ok = if (s.seats[s.current].isBot) { g.playBotStep(); true } else g.putt(holeOut(g))
            assertTrue(ok)
        }
        assertEquals(GolfPhase.HOLE_DONE, g.state.value!!.phase)
    }

    @Test fun aNewRoundStartsOnTheTeeOfTheFirstHole() {
        val g = newGame(players = two)
        val s = g.state.value!!
        assertTrue(s.isValid(two)); assertEquals(0, s.hole); assertEquals(GolfPhase.AIM, s.phase); assertEquals(0, s.current)
        assertEquals(GolfCourse.holes[0].tee.x, s.ballX[1], 0.0)
        assertEquals(27, s.parTotal)
    }

    @Test fun anAcceptedPuttCountsAStrokeAndPublishesTheStrokeForReplay() {
        val g = newGame()
        assertTrue(g.putt(GolfShot(0.0, 0.4)))
        val s = g.state.value!!
        assertEquals(1, s.strokes[0][0]); assertEquals(1, s.strokeNo)
        val rec = s.last!!
        assertEquals(GolfCourse.holes[0].tee.x, rec.startX, 0.0)
        val replay = GolfPhysics.simulate(GolfCourse.holes[0], rec.startX, rec.startY, rec.shot)
        assertEquals(s.ballX[0], replay.x, 0.0)
        assertTrue(s.isValid())
    }

    @Test fun rejectedPuttsChangeNothingAndAreExplained() {
        val g = newGame()
        val before = g.state.value
        assertEquals(GolfReject.BAD_POWER, g.explainPutt(GolfShot(0.0, 0.0)))
        assertEquals(GolfReject.BAD_POWER, g.explainPutt(GolfShot(0.0, 1.2)))
        assertEquals(GolfReject.BAD_SHOT, g.explainPutt(GolfShot(Double.NaN, 0.5)))
        assertFalse(g.putt(GolfShot(0.0, 0.0)))
        assertTrue(before === g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun aHoleInOneEndsTheHoleAndSoloMovesStraightToHoleDone() {
        val g = newGame()
        assertTrue(g.putt(holeOut(g)))
        val s = g.state.value!!
        assertTrue(s.done[0][0]); assertEquals(1, s.strokes[0][0]); assertEquals(GolfEnd.CUP, s.last!!.end)
        assertEquals(GolfPhase.HOLE_DONE, s.phase)
        assertEquals(GolfScoreName.HOLE_IN_ONE, GolfRules.scoreName(s.strokes[0][0], GolfCourse.holes[0].par))
        assertFalse(g.putt(GolfShot(0.0, 0.5))) // nothing to hit until the next hole
        g.nextHole()
        val n = g.state.value!!
        assertEquals(1, n.hole); assertEquals(GolfPhase.AIM, n.phase)
        assertEquals(GolfCourse.holes[1].tee.x, n.ballX[0], 0.0)
        assertEquals(1, n.strokes[0][0]) // the first hole's score stays
    }

    @Test fun waterCostsAPenaltyStrokeAndPutsTheBallBackWhereItWasHit() {
        val g = newGame()
        g.nextHoleForTest(3)
        val hole = GolfCourse.holes[3]
        assertTrue(g.putt(GolfShot(Math.atan2(17.0 - hole.tee.y, 48.0 - hole.tee.x), 0.7)))
        val s = g.state.value!!
        assertEquals(GolfEnd.WATER, s.last!!.end); assertEquals(1, s.last!!.penalty)
        assertEquals(2, s.strokes[0][3]); assertFalse(s.done[0][3])
        assertEquals(hole.tee.x, s.ballX[0], 0.0); assertEquals(hole.tee.y, s.ballY[0], 0.0)
    }

    private fun GolfGame.nextHoleForTest(index: Int) {
        // play the earlier holes out so the engine itself walks to hole [index]
        repeat(index) { finishHole(this); nextHole() }
        assertEquals(index, state.value!!.hole)
    }

    @Test fun reachingTheStrokeLimitEndsTheHoleAtThatScore() {
        val g = newGame(limit = 3)
        repeat(3) { assertTrue(g.putt(GolfShot(Math.PI / 2, 0.1))) }
        val s = g.state.value!!
        assertTrue(s.done[0][0]); assertEquals(3, s.strokes[0][0]); assertTrue(s.last!!.hitLimit)
        assertEquals(GolfPhase.HOLE_DONE, s.phase)
    }

    @Test fun playersTakeTurnsInRotationAndAFinishedPlayerIsSkipped() {
        val g = newGame(players = two)
        assertEquals(0, g.state.value!!.current)
        assertTrue(g.putt(GolfShot(Math.PI / 2, 0.1)))
        assertEquals(1, g.state.value!!.current)
        assertTrue(g.putt(holeOut(g)))           // player 2 holes out
        assertTrue(g.state.value!!.done[1][0])
        assertEquals(0, g.state.value!!.current)
        assertTrue(g.putt(GolfShot(Math.PI / 2, 0.1)))
        assertEquals(0, g.state.value!!.current) // player 1 putts again: player 2 is done
        assertTrue(g.putt(holeOut(g)))
        assertEquals(GolfPhase.HOLE_DONE, g.state.value!!.phase)
        g.nextHole()
        assertEquals(1, g.state.value!!.current) // the lowest score on the hole has the honor
    }

    @Test fun aFullThreeHoleRoundEndsWithTotalsAndAWinner() {
        val g = newGame(players = two, holes = 3)
        repeat(3) { finishHole(g); g.nextHole() }
        val s = g.state.value!!
        assertEquals(GolfPhase.OVER, s.phase)
        assertEquals(s.total(0), s.strokes[0].sum())
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertEquals(2, result!!.scores.size)
        // both holed in the same strokes: a tie is a draw
        assertEquals(s.total(0) == s.total(1), result!!.scores.none { it.isWinner })
    }

    @Test fun aSoloRoundUnderBogeyGolfCountsAsAWin() {
        val g = newGame(holes = 3)
        repeat(3) { finishHole(g); g.nextHole() }
        assertEquals(GolfPhase.OVER, g.state.value!!.phase)
        assertEquals(1, g.state.value!!.seats[0].wins)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        assertTrue(result!!.scores[0].isWinner)
    }

    @Test fun playAgainStartsAFreshRoundAndKeepsTheTally() {
        val g = newGame(holes = 3)
        repeat(3) { finishHole(g); g.nextHole() }
        g.playAgain()
        val s = g.state.value!!
        assertEquals(0, s.hole); assertEquals(0, s.strokes[0][0]); assertEquals(1, s.seats[0].wins)
    }

    @Test fun snapshotRestoreRoundTripsAndDamagedSavesAreRefused() {
        val g = newGame(players = two, seed = 3)
        g.putt(GolfShot(0.2, 0.5)); g.putt(GolfShot(-0.3, 0.6))
        val snap = g.snapshot()!!
        val g2 = newGame(players = two, seed = 9)
        assertTrue(g2.restore(snap))
        assertEquals(g.state.value!!.copy(serial = 0), g2.state.value!!.copy(serial = 0))
        assertEquals(snap, g2.snapshot())
        assertFalse(g2.restore("{}"))
        assertFalse(g2.restore("nonsense"))
        assertFalse(g2.restore(snap.replace("\"current\":0", "\"current\":5").replace("\"current\":1", "\"current\":5")))
        assertFalse(g2.restore(snap.replace("\"limit\":6", "\"limit\":99")))
        assertFalse("another roster", newGame(players = vsCpu).restore(snap))
        assertNull(newGame(mode = PlayMode.ONLINE).snapshot())
    }

    @Test fun undoTakesTheCourseBackToBeforeTheLastHumanPutt() {
        val g = newGame(players = vsCpu, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        val before = g.state.value!!
        g.putt(GolfShot(0.1, 0.5))
        assertTrue(g.canUndo)
        g.undo()
        val after = g.state.value!!
        assertEquals(before.strokes, after.strokes); assertEquals(before.ballX, after.ballX); assertEquals(before.current, after.current)
        assertFalse(g.canUndo)
    }

    @Test fun randomPlayNeverThrowsAndEveryStateStaysValid() {
        val rnd = Random(31)
        repeat(20) { n ->
            val players = if (n % 3 == 0) solo else if (n % 3 == 1) two else listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B"), PlayerInfo("c", "C", true))
            val g = newGame(players = players, holes = if (n % 2 == 0) 3 else 9, limit = 3 + n % 5, seed = n)
            var guard = 0
            while (g.state.value!!.phase != GolfPhase.OVER && guard++ < 600) {
                val s = g.state.value!!
                if (s.phase == GolfPhase.HOLE_DONE) { g.nextHole(); assertTrue(g.state.value!!.isValid(players)); continue }
                if (s.seats[s.current].isBot) g.playBotStep()
                else assertTrue(g.putt(GolfShot(rnd.nextDouble(-Math.PI, Math.PI), rnd.nextDouble(0.03, 1.0))))
                val ns = g.state.value!!
                assertTrue("valid at step $guard", ns.isValid(players))
                if (ns.phase != GolfPhase.OVER && players.none { it.isBot } || players.size == 1) {
                    val twin = newGame(players = players, seed = 100)
                    assertTrue(twin.restore(g.snapshot()!!))
                    assertEquals(ns.strokes, twin.state.value!!.strokes)
                }
            }
            assertEquals("round $n finished", GolfPhase.OVER, g.state.value!!.phase)
        }
    }

    @Test fun theHintIsALegalPuttThatEventuallyHolesOut() {
        val g = newGame(seed = 5)
        val hint = runBlocking { g.requestHint() }
        assertNotNull(hint)
        assertEquals(GolfReject.NONE, g.explainPutt(hint!!))
        assertEquals(hint, g.hintShot.value)
        assertTrue(g.putt(hint))
        assertEquals(GolfEnd.CUP, g.state.value!!.last!!.end)
    }

    @Test fun aComputerPuttIsPlayedOffTheMainThreadAndHardPlayersFinishRounds() {
        val g = newGame(players = listOf(PlayerInfo("cpu", "CPU", true), PlayerInfo("cpu2", "CPU 2", true)), holes = 3, level = CpuDifficulty.HARD, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        assertTrue(g.botToMove())
        runBlocking { g.playBotTurnAsync() }
        assertEquals(1, g.state.value!!.strokeNo)
        var guard = 0
        while (g.state.value!!.phase != GolfPhase.OVER && guard++ < 200) {
            if (g.state.value!!.phase == GolfPhase.HOLE_DONE) g.nextHole() else g.playBotStep()
        }
        assertEquals(GolfPhase.OVER, g.state.value!!.phase)
    }

    @Test fun anAbortedSessionScoresNothing() {
        val g = newGame()
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted); assertTrue(result!!.scores.isEmpty())
    }
}
