package com.gamesuite.games.slotcars

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The computer drivers: three clearly ordered levels that are not unbeatable, and deterministic for a seed. */
class SlotCarsCpuTest {
    private class Result(val winner: Int, val times: DoubleArray, val deslots: IntArray)

    private fun race(track: String, levels: List<CpuDifficulty>, seed: Int, laps: Int = 3): Result {
        val sim = SlotCarsSim(SlotTrack.byId(track), laps, levels.size)
        val driver = SlotCpu.Driver({ levels[it] }, Random(seed), levels.size)
        var guard = 0
        while (sim.phase != SlotPhase.OVER && guard++ < SlotCarsRules.MAX_RACE_TICKS + 10) {
            for (i in levels.indices) driver.drive(sim, i)
            sim.step()
        }
        assertEquals(SlotPhase.OVER, sim.phase)
        // a car that never finished counts as slower than every finisher
        return Result(sim.order().first(), DoubleArray(levels.size) { if (sim.finishTime[it] >= 0) sim.finishTime[it] / 60.0 else 9999.0 }, sim.deslots.copyOf())
    }

    private val seeds = 0 until 30

    @Test fun hardBeatsMediumBeatsEasyOnEveryTrack() {
        val lv = listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)
        for (track in SlotTrack.ids) {
            val wins = IntArray(3); val avg = DoubleArray(3)
            for (seed in seeds) {
                val r = race(track, lv, seed)
                wins[r.winner]++
                for (i in 0..2) avg[i] += r.times[i] / seeds.count()
            }
            assertTrue("$track avg times ${avg.toList()}", avg[2] < avg[1] && avg[1] < avg[0])
            assertTrue("$track Hard wins ${wins.toList()}", wins[2] >= 16 && wins[2] > wins[1])
            assertTrue("$track Easy does not win outright ${wins.toList()}", wins[0] <= wins[2])
        }
    }

    @Test fun mediumUsuallyBeatsEasyHeadToHeadButNotAlways() {
        var medium = 0; var easy = 0
        for (track in SlotTrack.ids) for (seed in 0 until 30) {
            val r = race(track, listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM), seed)
            if (r.winner == 1) medium++ else easy++
        }
        assertTrue("medium $medium easy $easy", medium >= 60)
        assertTrue("Easy is beatable but not helpless: it won $easy of 90", easy >= 1)
    }

    @Test fun hardIsNotPerfectAndMakesHumanMistakes() {
        var deslots = 0
        for (seed in 0 until 20) deslots += race("twist", listOf(CpuDifficulty.HARD, CpuDifficulty.HARD), seed, laps = 5).deslots.sum()
        assertTrue("Hard left the slot $deslots times in 20 races", deslots >= 1)
    }

    @Test fun theSameSeedGivesTheSameRace() {
        val lv = listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)
        val a = race("hill", lv, 5); val b = race("hill", lv, 5)
        assertEquals(a.times.toList(), b.times.toList())
        val c = race("hill", lv, 6)
        assertTrue(a.times.toList() != c.times.toList())
    }

    @Test fun thePerfectDriverNeverLeavesTheSlot() {
        for (track in SlotTrack.ids) {
            val sim = SlotCarsSim(SlotTrack.byId(track), 3, 1)
            var g = 0
            while (sim.phase != SlotPhase.OVER && g++ < 60000) {
                if (sim.phase == SlotPhase.RACING) sim.throttle[0] = SlotCpu.wantsThrottle(sim.track, sim.s[0], sim.v[0], SlotCpu.PERFECT, 0.0)
                sim.step()
            }
            assertEquals(track, 0, sim.deslots[0])
            assertTrue(sim.finishTime[0] > 0)
        }
    }

    @Test fun profilesGetBolderWithTheLevel() {
        val e = SlotCpu.profile(CpuDifficulty.EASY); val m = SlotCpu.profile(CpuDifficulty.MEDIUM); val h = SlotCpu.profile(CpuDifficulty.HARD)
        assertTrue(e.margin < m.margin && m.margin < h.margin)
        assertTrue(e.decisionTicks > m.decisionTicks && m.decisionTicks > h.decisionTicks)
    }
}
