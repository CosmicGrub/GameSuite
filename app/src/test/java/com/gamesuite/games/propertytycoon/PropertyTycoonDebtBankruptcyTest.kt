package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonDebtBankruptcyTest {
    @Ignore("debt entry: not built yet")
    @Test
    fun cashShortfallOpensDebtInterruptWithCreditorOrBank() {
        // TODO: debt entry
    }

    @Ignore("liquidation: not built yet")
    @Test
    fun sellingBuildingsPaysHalfCostAndKeepsEvenRule() {
        // TODO: liquidation
    }

    @Ignore("liquidation: not built yet")
    @Test
    fun pledgingPaysHalfPriceAndBlocksRent() {
        // TODO: liquidation
    }

    @Ignore("auto settle: not built yet")
    @Test
    fun debtAutoSettlesTheMomentCashCoversIt() {
        // TODO: auto settle
    }

    @Ignore("bankrupt gate: not built yet")
    @Test
    fun playerWhoCannotCoverDebtAfterFullLiquidationMayOnlyDeclareBankruptcy() {
        // TODO: bankrupt gate
    }

    @Ignore("player creditor: not built yet")
    @Test
    fun bankruptcyToPlayerTransfersTilesAndCashAndChargesPledgeInterest() {
        // TODO: player creditor
    }

    @Ignore("bank creditor: not built yet")
    @Test
    fun bankruptcyToBankReturnsTilesAndBuildingsToSupply() {
        // TODO: bank creditor
    }

    @Ignore("winner rule: not built yet")
    @Test
    fun lastPlayerStandingWinsAndGameEnds() {
        // TODO: winner rule
    }

    @Ignore("turn order: not built yet")
    @Test
    fun bankruptPlayerIsSkippedAndTheirFreeCardsReturnToDecks() {
        // TODO: turn order
    }
}
