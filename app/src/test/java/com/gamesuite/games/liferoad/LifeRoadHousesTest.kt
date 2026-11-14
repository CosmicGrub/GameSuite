package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadHousesTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadHousesTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun houseMarketOffersThreeAvailableHomes() {
        // Offers exclude owned homes
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun playerCanOwnTwoHomesAtMost() {
        // A third purchase is rejected with HOUSE_LIMIT
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun purchaseRemovesTheHomeFromStock() {
        // Nobody else can be offered it
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun marketRollTableMatchesTheSpec() {
        // Spin 1..10 maps to the multipliers in section 2.8
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun marketDaySellsOneHomeWithARoll() {
        // Seller receives price x multiplier
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun retirementSellsEveryHomeWithItsOwnRoll() {
        // One spin per home
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun grandLakeShiftsTheMarketSpinByTwoCappedAtTen() {
        // Shift applies to every home at retirement
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun homeSaleNeverPaysLessThanSixtyPercent() {
        // Floor of the table is x0.7 standard, x1.0 junior
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun homeComfortCountsOncePerOwnedHome() {
        // Comfort is 15 per home kept to the end, sold or not
        // TODO
    }
}
