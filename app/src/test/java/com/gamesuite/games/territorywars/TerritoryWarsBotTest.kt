package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TerritoryWarsBotTest {
    private fun bot(e: TerritoryWarsEngine) = StandardTerritoryWarsBot(e)

    @Test fun everyBotActionIsLegalForTheSeatOnTurn() {
        for (level in listOf(CpuLevel.EASY, CpuLevel.MEDIUM, CpuLevel.HARD)) for (seed in 1L..5L) {
            val e = TWT.seeded(seed)
            val n = 2 + (seed % 4).toInt()
            val b = bot(e)
            var s = e.newGame(TWT.players(n, n), TWT.config(n = n, seed = seed))
            var steps = 0
            while (s.phase != TurnPhase.GAME_OVER && steps++ < 30000) {
                val a = b.chooseAction(s, level, ThinkBudgets.forLevel(level), Random(steps))!!
                val r = e.apply(s, a)
                assertTrue("$level $a in ${s.phase}: $r", r is ActionResult.Accepted)
                s = (r as ActionResult.Accepted).state
            }
            assertEquals(TurnPhase.GAME_OVER, s.phase)
        }
    }

    @Test fun botNeverStallsATableWhenItHasNothingToDo() {
        val e = TWT.seeded(4)
        val s = TWT.started(2, 4, e = e).copy(armiesToPlace = 0, phase = TurnPhase.ATTACK)
        val a = bot(e).chooseAction(s, CpuLevel.MEDIUM, ThinkBudgets.forLevel(CpuLevel.MEDIUM), Random(1))
        assertNotNull(a)
        assertNull(bot(e).chooseAction(s.copy(phase = TurnPhase.GAME_OVER), CpuLevel.MEDIUM, ThinkBudgets.forLevel(CpuLevel.MEDIUM), Random(1)))
    }

    @Test fun botDecisionIsIdenticalForTheSameStateAndRandom() {
        val e = TWT.seeded(9)
        val s = TWT.started(3, 9, e = e).copy(armiesToPlace = 0)
        for (level in listOf(CpuLevel.EASY, CpuLevel.MEDIUM, CpuLevel.HARD)) {
            assertEquals(bot(e).chooseAction(s, level, ThinkBudgets.forLevel(level), Random(5)), bot(e).chooseAction(s, level, ThinkBudgets.forLevel(level), Random(5)))
        }
    }

    @Test fun mediumPlacesOnItsMostThreatenedBorderAndEasyPlacesAtRandom() {
        val e = TWT.seeded(7)
        val s = TWT.started(3, 7, e = e)
        val a = bot(e).chooseAction(s, CpuLevel.MEDIUM, ThinkBudgets.forLevel(CpuLevel.MEDIUM), Random(1))
        assertTrue(a is TerritoryWarsAction.PlaceArmies || a is TerritoryWarsAction.TurnInCards)
        assertEquals(TerritoryWarsAction.AutoPlaceRemaining, bot(e).chooseAction(s, CpuLevel.EASY, ThinkBudgets.forLevel(CpuLevel.EASY), Random(1)))
    }

    @Test fun hardWeighsAnAlmostCompleteRegion() {
        val e = TWT.seeded(3)
        val map = TerritoryWarsMaps.STANDARD
        val region = map.regions.first { it.id == "heartmark" }
        val ids = region.territoryIds.map { map.indexOf(it) }
        val s0 = TWT.started(2, 3, e = e)
        val owners = MutableList(36) { 1 }
        for (i in ids.dropLast(1)) owners[i] = 0
        val armies = MutableList(36) { 1 }
        for (i in ids.dropLast(1)) armies[i] = 6
        val s = s0.copy(owners = owners, armies = armies, armiesToPlace = 0, phase = TurnPhase.ATTACK, currentSeat = 0)
        val a = bot(e).chooseAction(s, CpuLevel.HARD, ThinkBudgets.forLevel(CpuLevel.HARD), Random(1))
        assertTrue(a is TerritoryWarsAction.AttackUntilDone)
    }

    @Test fun botsRespectTheComputeBudget() {
        val e = TWT.seeded(2)
        val b = bot(e)
        var s = e.newGame(TWT.players(4, 4), TWT.config(n = 4, seed = 2, auto = true))
        val times = ArrayList<Long>()
        var n = 0
        while (s.phase != TurnPhase.GAME_OVER && n++ < 4000) {
            val t0 = System.nanoTime()
            val a = b.chooseAction(s, CpuLevel.HARD, ThinkBudgets.forLevel(CpuLevel.HARD), Random(n))!!
            times += System.nanoTime() - t0
            s = TWT.act(e, s, a)
        }
        times.sort()
        assertTrue("p95 ${times[times.size * 95 / 100] / 1e6} ms", times[times.size * 95 / 100] < 250_000_000L)
    }
}
