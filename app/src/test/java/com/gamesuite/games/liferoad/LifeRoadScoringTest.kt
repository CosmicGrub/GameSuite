package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadScoringTest {
    private fun table(n: Int = 3) = LRT.start(n, LRT.config())
    private fun line(c: ScoreCard, key: String) = c.lines.first { it.labelKey == key }.amount

    @Test fun scoreAddsEveryComponent() {
        val s = LRT.edit(table(), 0) {
            it.copy(cash = 200, homes = listOf(HomeHolding("tiny_flat", 80, soldFor = 100)), retireOrder = 1, retired = true, lane = RetireLane.COZY,
                familyPegs = 2, cardMemories = 3)
        }
        val c = LifeRoadScoring.card(s, 0)
        assertEquals(200 + 100 + 80 + 3 * 20 + 2 * 30 + 15, c.total)
        assertEquals(3 * 20 + 2 * 30, c.lifePoints)
    }

    @Test fun loansAndFreshStartsSubtract() {
        val c = LifeRoadScoring.card(LRT.edit(table(), 0) { it.copy(loans = 2, freshStarts = 1) }, 0)
        assertEquals(100 - 120 - 40, c.total)
    }

    @Test fun retireBonusFollowsArrivalOrder() {
        for ((i, b) in listOf(80, 50, 30, 20, 10, 10).withIndex()) {
            val c = LifeRoadScoring.card(LRT.edit(table(), 0) { it.copy(retireOrder = i + 1) }, 0)
            assertEquals(b, line(c, "lr_sc_retire"))
        }
    }

    @Test fun lateRetirementGetsNoBonus() {
        val c = LifeRoadScoring.card(LRT.edit(table(), 0) { it.copy(retired = true, lane = RetireLane.LATE) }, 0)
        assertEquals(0, line(c, "lr_sc_retire"))
    }

    @Test fun tieBreakOrderIsLifePointsThenFewerDebtsThenEarlierRetire() {
        // equal totals (120): more life points wins
        val a = LRT.edit(LRT.edit(table(2), 0) { it.copy(cash = 100, cardMemories = 1) }, 1) { it.copy(cash = 120) }
        assertEquals(listOf(1, 2), LifeRoadScoring.cards(a).map { it.place })
        // equal totals and life points: fewer debts wins
        val d = LRT.edit(LRT.edit(table(2), 0) { it.copy(cash = 160, loans = 1) }, 1) { it.copy(cash = 100) }
        assertEquals(LifeRoadScoring.cards(d)[0].total, LifeRoadScoring.cards(d)[1].total)
        assertEquals(listOf(2, 1), LifeRoadScoring.cards(d).map { it.place })
        // equal everything: a retiree beats a late retiree
        val r = LRT.edit(LRT.edit(table(2), 0) { it.copy(retired = true, lane = RetireLane.LATE) }, 1) { it.copy(retired = true, lane = RetireLane.COZY, retireOrder = 7) }
        assertEquals(listOf(2, 1), LifeRoadScoring.cards(r).map { it.place })
    }

    @Test fun exactTieBecomesASharedWin() {
        val s = table(3)
        val cards = LifeRoadScoring.cards(s)
        assertEquals(listOf(1, 1, 1), cards.map { it.place })
        val over = s.copy(over = true, phase = Phase.GAME_OVER)
        assertEquals(3, LifeRoadScoring.cards(over).count { it.place == 1 })
    }

    @Test fun competitionRankingSkipsAfterATie() {
        val s = LRT.edit(table(3), 2) { it.copy(cash = 10) }
        assertEquals(listOf(1, 1, 3), LifeRoadScoring.cards(s).map { it.place })
    }

    @Test fun scoreCardLinesSumToTheTotal() {
        val (end, _) = LRT.playRandom(LRT.start(4, LRT.config(seed = 12)), kotlin.random.Random(12))
        for (c in LifeRoadScoring.cards(end)) assertEquals(c.lines.sumOf { it.amount }, c.total)
        assertTrue(end.winnerSeats.isNotEmpty())
    }

    @Test fun gameResultListsEverySeatOnce() {
        val g = LifeRoadGame(seedSource = { 4L })
        g.init(com.gamesuite.core.GameContext(com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, LRT.roster(3), 0, com.gamesuite.transport.LocalPassAndPlayTransport()))
        g.startMatch()
        assertEquals(3, g.scoreCards().map { it.seat }.toSet().size)
    }
}
