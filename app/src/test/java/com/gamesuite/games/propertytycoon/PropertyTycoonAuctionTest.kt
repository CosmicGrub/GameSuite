package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonAuctionTest {
    @Ignore("auction start (v1): not built yet")
    @Test
    fun declinedPropertyStartsAuctionWithEveryActivePlayerEligible() {
        // TODO: auction start (v1)
    }

    @Ignore("bid rules: not built yet")
    @Test
    fun bidsRiseByAtLeastTenAndPassingIsFinalForThatAuction() {
        // TODO: bid rules
    }

    @Ignore("settlement: not built yet")
    @Test
    fun highestBidderPaysAndReceivesTheTile() {
        // TODO: settlement
    }

    @Ignore("no bids: not built yet")
    @Test
    fun auctionWithNoBidsLeavesTheTileUnowned() {
        // TODO: no bids
    }

    @Ignore("affordability: not built yet")
    @Test
    fun bidderCannotBidMoreThanTheirCash() {
        // TODO: affordability
    }

    @Ignore("stack discipline: not built yet")
    @Test
    fun auctionInterruptReturnsToThePhaseItInterrupted() {
        // TODO: stack discipline
    }

    @Ignore("bank bankruptcy: not built yet")
    @Test
    fun bankruptcyToBankAuctionsEveryReturnedTileInTurn() {
        // TODO: bank bankruptcy
    }
}
