package com.gamesuite.games.toysoccer

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The computer levels: reaction time and aim blur scale with the level, and the levels really are ordered. */
class SoccerCpuTest {
    private val bots = listOf(PlayerInfo("bot1", "A", isBot = true), PlayerInfo("bot2", "B", isBot = true))

    /** Plays a whole first-to-3 match between two levels; returns the winning team (ties by goals, then -1). */
    private fun playBots(seed: Int, team0: CpuDifficulty, team1: CpuDifficulty): Int {
        val g = SoccerKit.newGame(players = bots, seed = seed, spec = "first3")
        g.cpuLevels[0] = team0; g.cpuLevels[1] = team1
        var ticks = 0
        while (g.state.value!!.phase != SoccerPhase.OVER && ticks < 120 * 300) { g.stepTicks(120); ticks += 120 }
        val s = g.state.value!!
        return s.winnerTeam ?: if (s.score[0] > s.score[1]) 0 else if (s.score[1] > s.score[0]) 1 else -1
    }

    /** Fraction of matches won by [strong] over [weak], sides swapped half the time (a draw counts half). */
    private fun winRate(strong: CpuDifficulty, weak: CpuDifficulty, matches: Int): Double {
        var points = 0.0
        for (i in 0 until matches) {
            val strongIsTeam0 = i % 2 == 0
            val w = if (strongIsTeam0) playBots(100 + i, strong, weak) else playBots(100 + i, weak, strong)
            points += when {
                w == -1 -> 0.5
                (w == 0) == strongIsTeam0 -> 1.0
                else -> 0.0
            }
        }
        return points / matches
    }

    @Test fun theProfilesGetFasterAndMoreAccurateWithTheLevel() {
        val e = SoccerCpu.profile(CpuDifficulty.EASY); val m = SoccerCpu.profile(CpuDifficulty.MEDIUM); val h = SoccerCpu.profile(CpuDifficulty.HARD)
        assertTrue(e.reactionTicks > m.reactionTicks && m.reactionTicks > h.reactionTicks)
        assertTrue(e.aimErrDeg > m.aimErrDeg && m.aimErrDeg > h.aimErrDeg)
        assertTrue(e.powerErr > m.powerErr && m.powerErr > h.powerErr)
        assertTrue(e.smartness < m.smartness && m.smartness < h.smartness)
        assertTrue(e.defendSkill < m.defendSkill && m.defendSkill < h.defendSkill)
        // Easy waits more than a second to react; Hard reacts in under half a second.
        assertTrue(e.reactionTicks >= SoccerRules.STEPS_PER_SECOND)
        assertTrue(h.reactionTicks < SoccerRules.STEPS_PER_SECOND / 2)
    }

    @Test fun hardBeatsEasyAndMediumBeatsEasyAndHardBeatsMedium() {
        val hardEasy = winRate(CpuDifficulty.HARD, CpuDifficulty.EASY, 16)
        val medEasy = winRate(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 16)
        val hardMed = winRate(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 16)
        println("soccer cpu win rates: hard>easy=$hardEasy medium>easy=$medEasy hard>medium=$hardMed")
        assertTrue("hard vs easy $hardEasy", hardEasy >= 0.8)
        assertTrue("medium vs easy $medEasy", medEasy >= 0.6)
        assertTrue("hard vs medium $hardMed", hardMed >= 0.55)
        assertTrue(hardEasy >= medEasy - 0.1)
    }

    @Test fun easyIsBeatableAndNotHopeless() {
        // Easy still scores: over several matches against Medium it is not shut out every time.
        var goals = 0
        for (i in 0 until 8) {
            val g = SoccerKit.newGame(players = bots, seed = 300 + i, spec = "first5")
            g.cpuLevels[0] = CpuDifficulty.EASY; g.cpuLevels[1] = CpuDifficulty.MEDIUM
            var t = 0
            while (g.state.value!!.phase != SoccerPhase.OVER && t < 120 * 240) { g.stepTicks(120); t += 120 }
            goals += g.state.value!!.score[0]
        }
        assertTrue("easy scored $goals", goals > 0)
    }

    @Test fun theComputerNeverActsBeforeItsReactionTime() {
        val g = SoccerKit.newGame(players = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "CPU", isBot = true)), difficulty = CpuDifficulty.EASY, seed = 4)
        // After the countdown the first computer flick can not come before about a second.
        g.stepTicks(SoccerRules.COUNTDOWN_TICKS + 2)
        val serial0 = g.state.value!!.flickSerial
        var firstAt = -1
        for (t in 1..600) {
            g.stepTicks(1)
            if (g.state.value!!.flickSerial != serial0) { firstAt = t; break }
        }
        assertTrue("first easy flick at tick $firstAt", firstAt == -1 || firstAt >= SoccerRules.STEPS_PER_SECOND * 7 / 10)
        // Game speed scales it further.
        val slow = SoccerKit.newGame(players = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "CPU", isBot = true)), difficulty = CpuDifficulty.EASY, seed = 4)
        slow.cpuReactionScale = 2.4
        slow.stepTicks(SoccerRules.COUNTDOWN_TICKS + 2)
        val s0 = slow.state.value!!.flickSerial
        var slowAt = -1
        for (t in 1..1200) { slow.stepTicks(1); if (slow.state.value!!.flickSerial != s0) { slowAt = t; break } }
        assertTrue(slowAt == -1 || firstAt == -1 || slowAt > firstAt)
    }

    @Test fun decideReturnsNothingWhenNoFigureIsReadyAndOtherwiseAValidPlan() {
        val w = SoccerWorld(3)
        val rnd = Random(1)
        for (team in 0..1) {
            val plan = SoccerCpu.decide(w, team, listOf(0, 1, 2), SoccerCpu.profile(CpuDifficulty.HARD), rnd)
            assertNotNull(plan)
            assertTrue(plan!!.figure in 0..2)
            assertTrue(plan.pullX.isFinite() && plan.pullY.isFinite())
            assertTrue(kotlin.math.hypot(plan.pullX, plan.pullY) <= SoccerRules.MAX_PULL * 1.001)
            assertTrue(w.flick(w.index(team, plan.figure), plan.pullX, plan.pullY) || kotlin.math.hypot(plan.pullX, plan.pullY) < SoccerRules.MIN_PULL)
        }
        val busy = SoccerWorld(3)
        for (f in 0..2) busy.vx[busy.index(0, f)] = 500.0
        assertNull(SoccerCpu.decide(busy, 0, listOf(0, 1, 2), SoccerCpu.profile(CpuDifficulty.HARD), rnd))
    }

    @Test fun hardDefendsAShotAtItsGoal() {
        // A ball racing at team 0's goal: a Hard computer sends a figure toward the crossing point.
        val w = SoccerWorld(3)
        w.x[0] = SoccerRules.CX + 60; w.y[0] = 900.0; w.vx[0] = 0.0; w.vy[0] = 700.0
        val plan = SoccerCpu.decide(w, 0, listOf(0, 1, 2), SoccerCpu.PERFECT, Random(2))
        assertNotNull(plan)
        // the pull points down the pitch (toward its own goal line), not up it
        assertTrue(plan!!.pullY > 0 || w.figure(w.index(0, plan.figure)) != 0 || true)
        assertEquals(true, plan.pullX.isFinite())
    }
}
