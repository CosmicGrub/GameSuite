package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadContentTest {
    @Test fun thereAreThirtyThreeJobsInFiveTiers() {
        assertEquals(33, LifeRoadContent.jobs.size)
        assertEquals(listOf(6, 8, 7, 6, 6), (1..5).map { t -> LifeRoadContent.jobs.count { it.tier == t } })
        assertTrue(LifeRoadContent.jobs.all { it.needsDegree == (it.tier >= 4) })
    }

    @Test fun salariesStayInTheirTierBands() {
        val bands = mapOf(1 to 20..26, 2 to 30..40, 3 to 45..58, 4 to 70..88, 5 to 90..115)
        for (j in LifeRoadContent.jobs) assertTrue(j.id, j.salary in bands.getValue(j.tier))
    }

    @Test fun homePricesAscendAndIdsAreUnique() {
        val prices = LifeRoadContent.houses.map { it.price }
        assertEquals(12, prices.size)
        assertEquals(prices.sorted(), prices)
        assertEquals(80, prices.first()); assertEquals(520, prices.last())
        assertEquals(12, LifeRoadContent.houses.map { it.id }.toSet().size)
    }

    @Test fun everyCoverCategoryHasSetbackCards() {
        val by = LifeRoadContent.events.groupBy { it.cover }
        assertEquals(5, by[CoverKind.HOME]!!.size)
        assertEquals(4, by[CoverKind.CARE]!!.size)
        assertEquals(3, by[CoverKind.INCOME]!!.size)
    }

    private fun swing(c: EventCardDef) = c.effects.filter { it.kind == EffectKind.COINS || it.kind == EffectKind.SPIN_COINS }
        .sumOf { if (it.kind == EffectKind.SPIN_COINS) it.amount * 10 else it.amount }

    @Test fun noCardSwingsMoreThanFiftyCoins() {
        for (c in LifeRoadContent.events) assertTrue(c.id, Math.abs(swing(c)) <= 50)
    }

    @Test fun deckExpectedCoinValueIsSlightlyPositive() {
        val mean = LifeRoadContent.events.sumOf { c ->
            c.effects.filter { it.kind == EffectKind.COINS || it.kind == EffectKind.SPIN_COINS }
                .sumOf { if (it.kind == EffectKind.SPIN_COINS) it.amount * 5.5 else it.amount.toDouble() }
        } / 64.0
        assertTrue("mean $mean", mean in 0.0..8.0)
    }
}
