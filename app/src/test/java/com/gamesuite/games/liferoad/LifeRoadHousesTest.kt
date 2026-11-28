package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadHousesTest {
    private fun market(vararg spins: Int, cash: Int = 500): LifeRoadState {
        val s = LRT.start(2, LRT.config(seed = LRT.seedFor(*spins)))
        return LRT.edit(LRT.at(s, 0, "TB.1"), 0) { it.copy(cash = cash) }
    }

    @Test fun houseMarketOffersThreeAvailableHomes() {
        val r = LRT.act(market(1), LifeRoadAction.Spin)
        assertEquals(Phase.AWAIT_HOUSE, r.phase)
        assertEquals(3, r.pending.offerIds.size)
        assertTrue(r.pending.offerIds.none { it in r.decks.houseStock })
    }

    @Test fun purchaseRemovesTheHomeFromStock() {
        val r = LRT.act(market(1), LifeRoadAction.Spin)
        val id = r.pending.offerIds[0]
        val bought = LRT.act(r, LifeRoadAction.BuyHouse(id))
        assertEquals(listOf(id), bought.players[0].homes.map { it.houseId })
        assertTrue(id !in bought.decks.houseStock)
        assertEquals(12 - 1, bought.decks.houseStock.size)
        assertEquals(500 - LifeRoadIndex.house.getValue(id).price, bought.players[0].cash)
    }

    @Test fun playerCanOwnTwoHomesAtMost() {
        val s = LRT.edit(market(1), 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80), HomeHolding("canal_boat", 90))) }
        val r = LRT.act(s.copy(decks = s.decks.copy(houseStock = s.decks.houseStock - setOf("tiny_flat", "canal_boat"))), LifeRoadAction.Spin)
        assertEquals(1, r.currentSeat) // the square does nothing at the cap
        val offered = LRT.card(market(1), "x").copy(phase = Phase.AWAIT_HOUSE, pending = PendingState(offerIds = listOf("brick_row")))
        val full = LRT.edit(offered, 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80), HomeHolding("canal_boat", 90))) }
        assertEquals(LifeRoadReject.HOUSE_LIMIT, LRT.engine.explain(full, 0, LifeRoadAction.BuyHouse("brick_row")))
    }

    @Test fun marketRollTableMatchesTheSpec() {
        val mult = listOf(7, 8, 9, 10, 11, 12, 12, 13, 14, 16)
        for (spin in 1..10) assertEquals(100 * mult[spin - 1] / 10, LifeRoadRules.marketValue(100, spin, false))
    }

    @Test fun marketDaySellsOneHomeWithARoll() {
        val seed = LRT.seedFor(1, 8)
        val s = LRT.start(2, LRT.config(seed = seed))
        val owned = LRT.edit(LRT.at(s, 0, "TB.8"), 0) { it.copy(homes = listOf(HomeHolding("brick_row", 180))) }
        val at = LRT.act(owned, LifeRoadAction.Spin)
        assertEquals(Phase.AWAIT_MARKET, at.phase)
        val sold = LRT.act(at, LifeRoadAction.SellHome("brick_row"))
        assertTrue(sold.players[0].homes.isEmpty())
        assertEquals(owned.players[0].cash + LifeRoadRules.marketValue(180, 8, false), sold.players[0].cash)
    }

    @Test fun retirementSellsEveryHomeWithItsOwnRoll() {
        val seed = LRT.seedFor(5, 3, 6)
        val s = LRT.edit(LRT.at(LRT.start(2, LRT.config(seed = seed)), 0, "COZY.4"), 0) {
            it.copy(homes = listOf(HomeHolding("tiny_flat", 80), HomeHolding("brick_row", 180)))
        }
        var r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(Phase.SETTLING, r.phase)
        r = LRT.act(r, LifeRoadAction.Spin)
        assertEquals(LifeRoadRules.marketValue(80, 3, false), r.players[0].homes[0].soldFor)
        assertEquals(Phase.SETTLING, r.phase)
        r = LRT.act(r, LifeRoadAction.Spin)
        assertEquals(LifeRoadRules.marketValue(180, 6, false), r.players[0].homes[1].soldFor)
        assertEquals(1, r.currentSeat)
    }

    @Test fun grandLakeShiftsTheMarketSpinByTwoCappedAtTen() {
        val seed = LRT.seedFor(1, 9)
        val s = LRT.edit(LRT.at(LRT.start(2, LRT.config(seed = seed)), 0, "GRAND.4"), 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80))) }
        var r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(RetireLane.GRAND, r.players[0].lane)
        r = LRT.act(r, LifeRoadAction.Spin)
        assertEquals(LifeRoadRules.marketValue(80, 10, false), r.players[0].homes[0].soldFor)
    }

    @Test fun grandLakeNeedsAHomeAndChargesTheFee() {
        val s = LRT.start(2, LRT.config())
        val atFork = s.copy(phase = Phase.AWAIT_FORK, pending = PendingState(forkNodeId = "F4"))
        val f4 = LRT.at(atFork, 0, "F4")
        assertEquals(LifeRoadReject.NEED_HOUSE_FOR_GRAND, LRT.engine.explain(f4, 0, LifeRoadAction.ChooseBranch("GRAND.0")))
        val owner = LRT.edit(f4, 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80))) }
        val r = LRT.act(owner, LifeRoadAction.ChooseBranch("GRAND.0"))
        assertEquals(100 - LifeRoadRules.GRAND_FEE, r.players[0].cash)
        assertEquals(Phase.AWAIT_SPIN, r.phase)
    }

    @Test fun homeSaleNeverPaysLessThanSeventyPercent() {
        for (spin in 1..10) {
            assertTrue(LifeRoadRules.marketValue(100, spin, false) >= 70)
            assertTrue(LifeRoadRules.marketValue(100, spin, true) >= 100)
        }
    }

    @Test fun homeComfortCountsOncePerOwnedHome() {
        val s = LRT.edit(LRT.start(2), 0) { it.copy(homes = listOf(HomeHolding("tiny_flat", 80, soldFor = 90))) }
        val c = LifeRoadScoring.card(s, 0)
        assertEquals(15, c.lines.first { it.labelKey == "lr_sc_comfort" }.amount)
        assertEquals(90, c.lines.first { it.labelKey == "lr_sc_homes" }.amount)
    }
}
