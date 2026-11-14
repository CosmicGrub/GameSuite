package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Bot behaviour per level (doc 2.6).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsBotTest {
    @Ignore("scaffold: fuzz all levels: chooseAction in legalActions")
    @Test fun everyBotActionIsLegalForTheSeatOnTurn() {}

    @Ignore("scaffold: cap")
    @Test fun easyNeverAttacksMoreThanThreeTimesPerTurn() {}

    @Ignore("scaffold: 25 percent rule within sampling bounds")
    @Test fun easyFortifiesAtMostOneTurnInFour() {}

    @Ignore("scaffold: threshold rule")
    @Test fun mediumSkipsAttacksBelowSixtyPercentConquestChance() {}

    @Ignore("scaffold: completion bonus rule")
    @Test fun mediumFinishesARegionWhenOneTerritoryIsMissingAndOddsAreSeventyFive() {}

    @Ignore("scaffold: region scoring formula")
    @Test fun hardPicksTheTargetRegionWithTheBestBonusPerEnemyArmy() {}

    @Ignore("scaffold: 60 staging / 40 defence")
    @Test fun hardSplitsReinforcementsSixtyForty() {}

    @Ignore("scaffold: exposure check")
    @Test fun hardKeepsAReserveGarrisonNextToStrongEnemies() {}

    @Ignore("scaffold: elimination rule")
    @Test fun hardGoesForAnEliminationWhenTheVictimIsWeakAndHoldsCards() {}

    @Ignore("scaffold: fake clock, budgets 20/80/250 ms")
    @Test fun botsRespectTheComputeBudgetOnASlowClock() {}

    @Ignore("scaffold: determinism")
    @Test fun botDecisionIsIdenticalForTheSameStateAndRandom() {}

    @Ignore("scaffold: ensureActive check")
    @Test fun botStopsWhenCoroutineIsCancelled() {}

    @Ignore("scaffold: null -> phase advances")
    @Test fun botNeverStallsATableWhenItHasNothingToDo() {}
}
