package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadJuniorTest {
    private fun junior(vararg spins: Int) = LRT.start(2, LRT.config(junior = true, seed = if (spins.isEmpty()) 1L else LRT.seedFor(*spins)))

    @Test fun juniorStartsWithOneHundredFiftyCoins() {
        assertTrue(junior().players.all { it.cash == 150 })
    }

    @Test fun juniorTuitionIsFree() {
        val s = LRT.at(junior(1), 0, "LRN.6")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(150, r.players[0].cash)
        assertTrue(r.players[0].degree)
    }

    @Test fun juniorCoverAndBankSquaresPayTenCoins() {
        val s = LRT.at(junior(1), 0, "TA.4") // TA.5 is the cover shop
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(160, r.players[0].cash)
        assertEquals(1, r.currentSeat)
    }

    @Test fun juniorMarketRollNeverLosesValue() {
        for (spin in 1..10) assertTrue(LifeRoadRules.marketValue(100, spin, true) >= 100)
    }

    @Test fun juniorEveryoneStartsWithOneLuckyCoin() {
        assertTrue(junior().players.all { it.luckyCoins == 1 })
    }

    @Test fun juniorDefaultsToTheQuickRoad() {
        val choice = LifeRoadHubSpecs.lifeRoad.modes.first().options.filterIsInstance<com.gamesuite.core.HubOption.Choice>().first { it.key == "rules" }
        assertEquals("quick", choice.choices.first { it.id == "junior" }.applies["length"])
    }

    @Test fun juniorBotsPlayEasyAndFinish() {
        val (end, _) = LRT.playBots(LRT.start(3, LRT.config(junior = true, size = RoadSize.QUICK, seed = 9), bots = 3), com.gamesuite.settings.CpuDifficulty.HARD)
        assertTrue(end.over)
        assertTrue(end.players.all { it.loans == 0 && it.freshStarts == 0 && it.covers.isEmpty() })
    }
}
