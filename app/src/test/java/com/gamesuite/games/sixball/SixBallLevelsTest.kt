package com.gamesuite.games.sixball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SixBallLevelsTest {
    private val all: List<SixBallLevel> by lazy { (1..SixBallLevels.CAMPAIGN_COUNT).map { SixBallLevels.campaign(it) } }

    @Test fun thereAreAtLeastFortyLevelsWithRisingPar() {
        assertTrue(SixBallLevels.CAMPAIGN_COUNT >= 40)
        assertEquals(3, all.first().par)
        assertTrue(all.last().par >= 18)
        assertEquals(all.map { it.par }, all.map { it.par }.sorted())
        for (n in 1..SixBallLevels.CAMPAIGN_COUNT) assertEquals(SixBallLevels.recipe(n).par, all[n - 1].par)
    }

    @Test fun everyLevelIsWellFormedAndNamedByItsNumber() {
        all.forEachIndexed { i, l ->
            assertTrue(l.id, l.isWellFormed())
            assertEquals(SixBallLevels.campaignId(i + 1), l.id)
            assertEquals(i + 1, SixBallLevels.campaignNumber(l.id))
            assertNotEquals("level ${l.id} is already solved", l.start, l.target)
            assertTrue("at least four balls have to travel", (0 until 6).count { l.start[it] != l.target[it] } >= 4)
        }
    }

    @Test fun everyLevelIsSolvableAndItsParIsTheTrueShortestSolution() {
        all.forEach { l ->
            val path = SixBallRules.solve(l)
            assertTrue("${l.id} unsolved", path != null)
            assertEquals("${l.id} par", l.par, path!!.size)
            var p = l.start.toIntArray()
            for (m in path) p = SixBallRules.tilt(l, p, m)
            assertTrue(SixBallRules.isSolved(l, p))
        }
    }

    @Test fun anIndependentSearchConfirmsNoShorterSolutionExists() {
        // a second implementation of the tilt and of the search (see SixBallRulesTest) on every level
        all.forEach { l ->
            val d = sbNaiveDistance(l.width, l.height, l.walls.toSet(), l.start, l.target)
            assertEquals("${l.id}", l.par, d)
        }
    }

    @Test fun generationIsAPureFunctionOfTheRecipe() {
        for (n in listOf(1, 7, 20, 33, 48)) {
            val r = SixBallLevels.recipe(n)
            val again = SixBallLevels.generate(SixBallLevels.campaignId(n), 0x5B1L * 1_000_003L + n, r.width, r.height, r.walls, r.par)
            assertEquals(all[n - 1], again)
        }
        assertSame(SixBallLevels.campaign(5), SixBallLevels.campaign(5))
        assertSame(SixBallLevels.byId("c5"), SixBallLevels.campaign(5))
    }

    @Test fun dailyTraysAreTheSameForEveryoneOnADayAndSolvable() {
        val base = 20_000L
        val week = (0 until 14).map { SixBallLevels.daily(base + it) }
        week.forEachIndexed { i, l ->
            assertTrue(l.isWellFormed())
            assertEquals(SixBallLevels.dailyId(base + i), l.id)
            assertEquals(SixBallLevels.dailyDay(l.id), base + i)
            assertEquals(l.par, SixBallRules.solve(l)!!.size)
        }
        assertEquals(week[0], SixBallLevels.generate(week[0].id, (base) * 7919L + 0xD41L, week[0].width, week[0].height, week[0].walls.size, week[0].par))
        assertTrue("the week should not repeat one tray", week.map { it.start to it.target }.toSet().size > 10)
        // the difficulty climbs through the week (par depends on the weekday)
        assertEquals(setOf(6, 8, 10, 12, 14, 16, 18), week.map { it.par }.toSet())
    }

    @Test fun idsThatNameNoLevelAreRefused() {
        assertNull(SixBallLevels.byId(""))
        assertNull(SixBallLevels.byId("c0"))
        assertNull(SixBallLevels.byId("c${SixBallLevels.CAMPAIGN_COUNT + 1}"))
        assertNull(SixBallLevels.byId("cx"))
        assertNull(SixBallLevels.byId("zzz"))
        assertNull(SixBallLevels.campaignNumber("d12"))
        assertNull(SixBallLevels.dailyDay("c3"))
    }
}
