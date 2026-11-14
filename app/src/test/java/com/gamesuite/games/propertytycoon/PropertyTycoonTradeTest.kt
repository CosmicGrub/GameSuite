package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonTradeTest {
    @Ignore("atomic swap (v1): not built yet")
    @Test
    fun tradeProposalMovesTilesCashAndFreeCardsOnlyAfterAcceptance() {
        // TODO: atomic swap (v1)
    }

    @Ignore("building lock: not built yet")
    @Test
    fun tradeRejectsTilesInAColourThatHasAnyBuilding() {
        // TODO: building lock
    }

    @Ignore("pledge fee: not built yet")
    @Test
    fun tradeOfPledgedTileChargesTheNewOwnerTenPercentAtOnce() {
        // TODO: pledge fee
    }

    @Ignore("affordability: not built yet")
    @Test
    fun tradeRejectsMoreCashThanThePayerHolds() {
        // TODO: affordability
    }

    @Ignore("counter chain: not built yet")
    @Test
    fun counterOfferSwapsRolesLinksParentAndStopsAtDepthThree() {
        // TODO: counter chain
    }

    @Ignore("expiry: not built yet")
    @Test
    fun tradeExpiresWhenTheProposersTurnEnds() {
        // TODO: expiry
    }

    @Ignore("validity: not built yet")
    @Test
    fun tradeIsRejectedWhenEitherSideIsBankrupt() {
        // TODO: validity
    }

    @Ignore("bot policy: not built yet")
    @Test
    fun easyBotNeverProposesOrAcceptsATrade() {
        // TODO: bot policy
    }

    @Ignore("bot policy: not built yet")
    @Test
    fun mediumBotAcceptsClearlyFavourableTradesOnly() {
        // TODO: bot policy
    }

    @Ignore("bot policy: not built yet")
    @Test
    fun hardBotProposesSetCompletingSwapsAndRespectsCooldownAfterDecline() {
        // TODO: bot policy
    }

    @Ignore("bot safety: not built yet")
    @Test
    fun noBotHandsARivalAWholeColourWithoutLargeCompensation() {
        // TODO: bot safety
    }
}
