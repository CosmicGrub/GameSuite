package com.gamesuite.games.billiards

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BilliardsBotTest {
    private val bots = listOf(PlayerInfo("b1", "CPU 1", isBot = true), PlayerInfo("b2", "CPU 2", isBot = true))

    private fun botGame(seed: Int, level: CpuDifficulty, variant: String): BilliardsGame {
        val g = BilliardsGame(Random(seed)) { 5L }
        g.variant = variant; g.difficulty = level
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, bots, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    @Test fun everyLevelPlaysOnlyLegalStrokesForWholeRacks() {
        for (level in CpuDifficulty.entries) for (variant in listOf(BilliardsVariant.EIGHT, BilliardsVariant.NINE)) {
            for (seed in 1..3) {
                val g = botGame(seed, level, variant)
                var shots = 0
                while (g.state.value!!.phase == BilliardsPhase.AIM && shots < 120) {
                    val before = g.state.value!!.shotNo
                    g.playBotStep()
                    assertEquals("$level $variant seed $seed accepted shot ${shots + 1}", before + 1, g.state.value!!.shotNo)
                    assertTrue(g.state.value!!.isValid(bots))
                    shots++
                }
            }
        }
    }

    @Test fun hardComputersUsuallyFinishARack() {
        var finished = 0
        for (seed in 1..6) {
            val g = botGame(seed, CpuDifficulty.HARD, BilliardsVariant.NINE)
            var shots = 0
            while (g.state.value!!.phase == BilliardsPhase.AIM && shots < 200) { g.playBotStep(); shots++ }
            if (g.state.value!!.phase == BilliardsPhase.OVER) finished++
        }
        assertTrue("hard vs hard 9-ball racks finished: $finished/6", finished >= 3)
    }

    /** A scattered table: the cue ball and [n] object balls of an open 8-ball table, no two touching. */
    private fun scatter(rnd: Random, n: Int): BilliardsBot.View {
        val x = DoubleArray(16); val y = DoubleArray(16); val on = BooleanArray(16)
        x[0] = rnd.nextDouble(8.0, 92.0); y[0] = rnd.nextDouble(6.0, 44.0); on[0] = true
        val ids = ((1..7) + (9..15)).shuffled(rnd).take(n - 1) + 8
        for (id in ids) {
            var tries = 0
            while (tries++ < 200) {
                val px = rnd.nextDouble(8.0, 92.0); val py = rnd.nextDouble(6.0, 44.0)
                if ((0 until 16).all { !on[it] || Math.hypot(x[it] - px, y[it] - py) > 6.0 }) { x[id] = px; y[id] = py; on[id] = true; break }
            }
        }
        return BilliardsBot.View(BilliardsVariant.EIGHT, x, y, on, 0, listOf(0, 0), false, false, false, 0)
    }

    private fun successes(level: CpuDifficulty): Int {
        val rnd = Random(4242)
        var ok = 0
        repeat(70) { i ->
            val v = scatter(rnd, 7)
            val shot = BilliardsBot.chooseShot(v, level, Random(i + 1L))
            val sim = BilliardsPhysics.simulate(v.x, v.y, v.onTable, shot)
            val verdict = BilliardsRules.judge(RuleContext(v.variant, 0, v.groups, v.balls, false, 0), ShotOutcome.of(sim))
            if (!verdict.foul && verdict.pocketed.isNotEmpty()) ok++
        }
        return ok
    }

    @Test fun theLevelsAreOrderedByHowOftenTheyPotALegalBall() {
        val easy = successes(CpuDifficulty.EASY)
        val medium = successes(CpuDifficulty.MEDIUM)
        val hard = successes(CpuDifficulty.HARD)
        println("bot pot rates over 70 scattered tables: easy=$easy medium=$medium hard=$hard")
        assertTrue("medium ($medium) beats easy ($easy)", medium > easy)
        assertTrue("hard ($hard) is at least as good as medium ($medium)", hard >= medium - 2)
        assertTrue("hard ($hard) clearly beats easy ($easy)", hard > easy + 8)
        assertTrue("easy is still beatable but not hopeless ($easy)", easy in 3..45)
        assertTrue("hard is genuinely strong ($hard)", hard >= 35)
    }

    @Test fun theBreakIsAimedAtTheRackAndIsLegalMostOfTheTime() {
        var legal = 0
        for (seed in 1..20) {
            val g = botGame(seed, CpuDifficulty.HARD, BilliardsVariant.EIGHT)
            g.playBotStep()
            if (g.state.value!!.lastFouls.isEmpty()) legal++
        }
        assertTrue("hard breaks are legal $legal/20", legal >= 14)
    }

    @Test fun withBallInHandTheComputerPicksALegalSpotBehindTheHeadStringAfterABreakScratch() {
        val rnd = Random(3)
        val v0 = scatter(rnd, 6)
        val v = BilliardsBot.View(v0.variant, v0.x, v0.y, v0.onTable, 0, listOf(0, 0), true, true, false, 0)
        for (level in CpuDifficulty.entries) {
            val shot = BilliardsBot.chooseShot(v, level, Random(1))
            assertTrue(shot.isFinite)
            assertTrue("placed legally $level", BilliardsRules.canPlaceCue(shot.cueX, shot.cueY, v.x, v.y, v.onTable, true))
        }
    }
}
