package com.gamesuite.games.toysoccer

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

object SoccerKit {
    fun two(bot: Boolean) = listOf(PlayerInfo("p1", "Ann"), PlayerInfo(if (bot) "bot1" else "p2", "Ben", isBot = bot))
    fun four(humans: Int) = (0 until 4).map { i ->
        if (i < humans) PlayerInfo("p${i + 1}", "P${i + 1}") else PlayerInfo("bot${i + 1}", "CPU${i + 1}", isBot = true)
    }

    fun newGame(
        players: List<PlayerInfo> = two(false),
        seed: Int = 1,
        clock: ToyClock = ToyClock(),
        spec: String = "first3",
        assist: Boolean = false,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        mode: PlayMode = if (players.any { it.isBot }) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        results: MutableList<GameResult>? = null,
        start: Boolean = true
    ): ToySoccerGame {
        val g = ToySoccerGame(Random(seed), clock)
        g.difficulty = difficulty; g.assist = assist; g.matchSpec = spec
        g.init(GameContext(mode, players, 0, ToyFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        if (start) g.startMatch()
        return g
    }

    /** Runs the game's own countdown so the match is live. */
    fun live(g: ToySoccerGame) { if (!g.assist) g.stepTicks(SoccerRules.COUNTDOWN_TICKS + 120) }

    /** Puts the ball in front of a goal heading in, for team [team] to score. */
    fun shootInto(g: ToySoccerGame, team: Int) {
        val w = g.worldForTest()
        w.x[0] = SoccerRules.CX + 100; w.vx[0] = 0.0
        if (team == 0) { w.y[0] = 260.0; w.vy[0] = -1700.0 } else { w.y[0] = SoccerRules.L - 260; w.vy[0] = 1700.0 }
    }

    fun score(g: ToySoccerGame, team: Int) { shootInto(g, team); g.stepTicks(120) }
}

class ToySoccerGameTest {
    private val K = SoccerKit

    @Test fun aNewMatchStartsWithACountdownThenPlays() {
        val g = K.newGame()
        val s0 = g.state.value!!
        assertEquals(SoccerPhase.COUNTDOWN, s0.phase)
        assertEquals(listOf(0, 0), s0.score)
        assertEquals(3, s0.target)
        assertEquals(SoccerReject.NOT_STARTED, g.flick(0, 1, 0.0, -100.0))
        g.stepTicks(SoccerRules.COUNTDOWN_TICKS + 5)
        assertEquals(SoccerPhase.PLAYING, g.state.value!!.phase)
        assertEquals(SoccerReject.NONE, g.flick(0, 1, 0.0, -100.0))
    }

    @Test fun firstToThreeEndsTheMatchAndCountsTheSessionWin() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(results = results)
        K.live(g)
        K.score(g, 0)
        assertEquals(SoccerPhase.GOAL, g.state.value!!.phase)
        assertEquals(listOf(1, 0), g.state.value!!.score)
        g.stepTicks(SoccerRules.GOAL_TICKS + 400)
        assertEquals(SoccerPhase.PLAYING, g.state.value!!.phase)
        K.score(g, 1)
        assertEquals(listOf(1, 1), g.state.value!!.score)
        g.stepTicks(SoccerRules.GOAL_TICKS + 400)
        K.score(g, 0); g.stepTicks(SoccerRules.GOAL_TICKS + 400)
        K.score(g, 0)
        assertEquals(listOf(3, 1), g.state.value!!.score)
        g.stepTicks(SoccerRules.GOAL_TICKS + 5)
        val s = g.state.value!!
        assertEquals(SoccerPhase.OVER, s.phase)
        assertEquals(0, s.winnerTeam)
        assertEquals(listOf(1, 0), g.sessionWins.value)
        assertEquals(SoccerReject.NOT_PLAYING, g.flick(0, 1, 0.0, -100.0))
        g.leaveSession()
        assertEquals(1, results.size)
        assertTrue(results[0].scores.single { it.playerId == "p1" }.isWinner)
        assertFalse(results[0].scores.single { it.playerId == "p2" }.isWinner)
    }

    @Test fun anOwnGoalScoresForTheOpponent() {
        val g = K.newGame()
        K.live(g)
        K.score(g, 1)
        assertEquals(listOf(0, 1), g.state.value!!.score)
        assertEquals(1, g.state.value!!.eventTeam)
    }

    @Test fun aGoalResetsTheTableAndGivesAnotherKickoff() {
        val g = K.newGame()
        K.live(g)
        g.flick(0, 1, 40.0, -100.0)
        K.score(g, 0)
        g.stepTicks(SoccerRules.GOAL_TICKS + 5)
        assertEquals(SoccerPhase.COUNTDOWN, g.state.value!!.phase)
        g.stepTicks(SoccerRules.COUNTDOWN_TICKS + 10)
        val w = g.worldForTest()
        for (i in 0 until w.count) { assertEquals(w.homeX(i), w.x[i], 1e-6); assertEquals(w.homeY(i), w.y[i], 1e-6) }
        assertEquals(SoccerPhase.PLAYING, g.state.value!!.phase)
    }

    @Test fun aTimedMatchEndsWhenTheClockRunsOutAndTheLeaderWins() {
        val g = K.newGame(spec = "time2")
        K.live(g)
        K.score(g, 1)
        g.stepTicks(SoccerRules.GOAL_TICKS + 400)
        assertNotNull(g.state.value!!.ticksLeft.takeIf { it > 0 })
        g.stepTicks(2 * 60 * SoccerRules.STEPS_PER_SECOND + 10)
        val s = g.state.value!!
        assertEquals(SoccerPhase.OVER, s.phase)
        assertEquals(1, s.winnerTeam)
    }

    @Test fun aLevelTimedMatchGoesToSuddenDeath() {
        val g = K.newGame(spec = "time2")
        K.live(g)
        g.stepTicks(2 * 60 * SoccerRules.STEPS_PER_SECOND + 10)
        var s = g.state.value!!
        assertTrue(s.goldenGoal)
        assertEquals(SoccerPhase.PLAYING, s.phase)
        assertEquals(SoccerEvent.OVERTIME, s.event)
        K.score(g, 0)
        g.stepTicks(SoccerRules.GOAL_TICKS + 5)
        s = g.state.value!!
        assertEquals(SoccerPhase.OVER, s.phase)
        assertEquals(0, s.winnerTeam)
    }

    @Test fun flickIsRefusedForTheWrongFigureAndWhileMoving() {
        val g = K.newGame()
        K.live(g)
        assertEquals(SoccerReject.BAD_INPUT, g.flick(0, 9, 0.0, -100.0))
        assertEquals(SoccerReject.BAD_INPUT, g.flick(0, 1, Double.NaN, -100.0))
        assertEquals(SoccerReject.NONE, g.flick(0, 1, 0.0, -100.0))
        assertEquals(SoccerReject.STILL_MOVING, g.flick(0, 1, 0.0, -100.0))
        assertEquals(SoccerReject.TOO_WEAK, g.flick(0, 2, 1.0, 1.0))
        assertEquals(SoccerReject.NONE, g.flick(1, 2, 0.0, 100.0)) // pass and play: both seats are people
        assertEquals(2, g.state.value!!.flickSerial)
    }

    @Test fun inTwoVersusTwoEachPlayerOnlyFlicksTheirOwnFigures() {
        val g = K.newGame(players = K.four(4))
        K.live(g)
        assertEquals(4, g.state.value!!.figuresPerTeam)
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, g.flick(0, 2, 0.0, -100.0))  // figure 2 belongs to seat 2
        assertEquals(SoccerReject.NONE, g.flick(2, 2, 0.0, -100.0))
        assertEquals(SoccerReject.NONE, g.flick(0, 1, 0.0, -100.0))
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, g.flick(3, 0, 0.0, 100.0))
    }

    @Test fun aHumanCannotFlickForAComputerSeat() {
        val g = K.newGame(players = K.two(true))
        K.live(g)
        assertEquals(SoccerReject.NOT_YOUR_FIGURE, g.flick(1, 1, 0.0, 100.0))
        assertEquals(SoccerReject.NONE, g.flick(0, 1, 0.0, -100.0))
    }

    @Test fun turnBasedAssistTakesTurnsAndWaitsForTheTableToSettle() {
        val g = K.newGame(assist = true, spec = "first3")
        assertEquals(SoccerPhase.PLAYING, g.state.value!!.phase)   // no countdown in assist
        assertEquals(SoccerReject.NOT_YOUR_TURN, g.flick(1, 1, 0.0, 100.0))
        assertEquals(SoccerReject.NONE, g.flick(0, 1, 0.0, -140.0))
        assertEquals(SoccerReject.NOT_YOUR_TURN, g.flick(0, 2, 0.0, -140.0)) // one flick per turn
        assertEquals(0, g.state.value!!.turnTeam)
        g.stepTicks(SoccerRules.TURN_TIMEOUT_TICKS + 10)
        assertEquals(1, g.state.value!!.turnTeam)
        assertFalse(g.state.value!!.turnFlicked)
        assertEquals(SoccerReject.NONE, g.flick(1, 1, 0.0, 140.0))
    }

    @Test fun inAssistTheConcedingTeamTakesTheNextTurn() {
        val g = K.newGame(assist = true)
        K.score(g, 0)
        g.stepTicks(SoccerRules.GOAL_TICKS + SoccerRules.RESET_TICKS + 200)
        val s = g.state.value!!
        assertEquals(SoccerPhase.PLAYING, s.phase)
        assertEquals(1, s.turnTeam)
    }

    @Test fun anAssistTimedMatchCountsFlicksInsteadOfSeconds() {
        val g = K.newGame(assist = true, spec = "time2")
        assertEquals(30, g.state.value!!.flicksLeft)
        assertEquals(-1, g.state.value!!.ticksLeft)
    }

    @Test fun matchSpecParsing() {
        assertEquals(SoccerMatch(5, 0), SoccerMatch.parse("first5"))
        assertEquals(SoccerMatch(0, 240), SoccerMatch.parse("time4"))
        assertEquals(SoccerMatch(3, 0), SoccerMatch.parse("zzz"))
        assertEquals(SoccerMatch(3, 0), SoccerMatch.parse(null))
        assertEquals(SoccerMatch(3, 0), SoccerMatch.parse("first0"))
        assertEquals(SoccerMatch(3, 0), SoccerMatch.parse("first99999"))
    }

    @Test fun theClockDrivesTheSimulationInFixedSteps() {
        val clock = ToyClock()
        val g = K.newGame(clock = clock)
        assertFalse(g.advance())            // first call only reads the clock
        clock.advance(105)
        assertTrue(g.advance())
        val t = g.tick
        assertEquals(12L, t)                 // ~100 ms = 12 steps of 8.33 ms
        clock.advance(10_000)                // a long stall is capped at a quarter of a second
        g.advance()
        assertEquals(t + 30, g.tick)
    }

    @Test fun pausingFreezesTheTable() {
        val clock = ToyClock()
        val g = K.newGame(clock = clock)
        g.advance(); clock.advance(50); g.advance()
        val t = g.tick
        g.pause(); clock.advance(500); g.advance()
        assertEquals(t, g.tick)
        g.resume(); g.advance(); clock.advance(50); g.advance()
        assertTrue(g.tick > t)
    }

    // ---- resume ----------------------------------------------------------------------------

    @Test fun snapshotRoundTripsAndRestoresTheMatch() {
        val g = K.newGame(players = K.two(true), difficulty = CpuDifficulty.HARD, spec = "first5")
        K.live(g)
        g.flick(0, 1, 60.0, -120.0); g.stepTicks(50)
        K.score(g, 0); g.stepTicks(SoccerRules.GOAL_TICKS + 500)
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = K.newGame(players = K.two(true), start = false, spec = "first5")
        assertTrue(h.restore(snap!!))
        val s = h.state.value!!
        assertEquals(listOf(1, 0), s.score)
        assertEquals(5, s.target)
        assertEquals(CpuDifficulty.HARD, h.difficulty)
        assertEquals(SoccerPhase.COUNTDOWN, s.phase)
        for (i in s.bodies.indices) {
            assertEquals(g.state.value!!.bodies[i].x, s.bodies[i].x, 1e-9)
            assertEquals(g.state.value!!.bodies[i].y, s.bodies[i].y, 1e-9)
            assertEquals(0.0, s.bodies[i].vx, 1e-9)
        }
        h.stepTicks(SoccerRules.COUNTDOWN_TICKS + 20)
        assertEquals(SoccerPhase.PLAYING, h.state.value!!.phase)
        assertEquals(h.snapshot()!!.length > 0, true)
    }

    @Test fun noSnapshotBeforeStartAfterTheMatchOrOnline() {
        val g = K.newGame(start = false)
        assertNull(g.snapshot())
        val n = K.newGame(mode = PlayMode.LOCAL_AD_HOC)
        assertNull(n.snapshot())
    }

    @Test fun badSnapshotsAreRefusedAndLeaveTheGameUntouched() {
        val g = K.newGame(players = K.two(true))
        K.live(g)
        val good = g.snapshot()!!
        val before = g.state.value
        val bad = listOf(
            "", "{}", "not json", good.replace("\"p1\"", "\"zz\""), good.replace("\"bodies\":[", "\"bodies\":[1e9,"),
            good.replace("\"target\":3", "\"target\":-4"), good.replace("\"score\":[0,0]", "\"score\":[9,0]"),
            good.replace("\"turnTeam\":0", "\"turnTeam\":7"), good.replace("\"firstTeam\":0", "\"firstTeam\":5"),
            good.replace("\"sessionWins\":[0,0]", "\"sessionWins\":[-1,0]"), good.substring(0, good.length / 2)
        )
        for (b in bad) assertFalse("accepted: ${b.take(60)}", g.restore(b))
        assertEquals(before, g.state.value)
        assertTrue(g.restore(good))
    }

    // ---- fuzz ------------------------------------------------------------------------------

    @Test fun randomInputNeverBreaksAMatchAndAlwaysTerminates() {
        for (seed in 1..10) for (assist in listOf(false, true)) for (players in listOf(K.two(false), K.four(2))) {
            val r = Random(seed)
            val g = K.newGame(players = players, seed = seed, assist = assist, spec = if (seed % 2 == 0) "first3" else "time2")
            var guard = 0
            while (g.state.value!!.phase != SoccerPhase.OVER && guard < 400_000) {
                g.stepTicks(1 + r.nextInt(30)); guard += 30
                val seat = r.nextInt(players.size)
                val fig = r.nextInt(-1, 6)
                val rej = g.flick(seat, fig, r.nextDouble(-400.0, 400.0), r.nextDouble(-400.0, 400.0))
                assertTrue(rej in SoccerReject.entries)
                if (r.nextInt(40) == 0) {
                    val snap = g.snapshot()
                    if (snap != null) { val h = K.newGame(players = players, start = false); assertTrue(h.restore(snap)) }
                }
                if (r.nextInt(60) == 0) K.shootInto(g, r.nextInt(2)) // keep matches moving
                val s = g.state.value!!
                assertTrue(s.score.all { it in 0..99 })
                assertTrue(s.bodies.all { it.x.isFinite() && it.y.isFinite() })
            }
            assertEquals("match $seed assist=$assist ended", SoccerPhase.OVER, g.state.value!!.phase)
            val s = g.state.value!!
            assertNotNull(s.winnerTeam)
            assertTrue(s.score[s.winnerTeam!!] >= s.score[1 - s.winnerTeam!!])
        }
    }

    @Test fun theSameSeedReplaysTheSameMatchExactly() {
        fun run(): Pair<Long, List<Int>> {
            val g = K.newGame(players = K.two(true), seed = 9, difficulty = CpuDifficulty.MEDIUM)
            repeat(40) { g.stepTicks(120) }
            return g.fingerprint() to g.state.value!!.score
        }
        assertEquals(run(), run())
    }

    @Test fun playAgainStartsANewMatchInTheSameSession() {
        val g = K.newGame(spec = "first3")
        K.live(g)
        repeat(3) { K.score(g, 0); g.stepTicks(SoccerRules.GOAL_TICKS + 500) }
        assertEquals(SoccerPhase.OVER, g.state.value!!.phase)
        g.playAgain()
        assertEquals(SoccerPhase.COUNTDOWN, g.state.value!!.phase)
        assertEquals(listOf(0, 0), g.state.value!!.score)
        assertEquals(listOf(1, 0), g.sessionWins.value)
    }

    @Test fun hintOffersAFlickForTheLocalTeam() {
        val g = K.newGame(players = K.two(true))
        assertNull(g.requestHint())              // not live yet
        K.live(g)
        val h = g.requestHint()
        assertNotNull(h)
        assertEquals(0, h!!.team)
        assertEquals(SoccerReject.NONE, g.flick(0, h.plan.figure, h.plan.pullX, h.plan.pullY))
        assertNull(g.hint.value)                 // cleared by the flick
    }
}
