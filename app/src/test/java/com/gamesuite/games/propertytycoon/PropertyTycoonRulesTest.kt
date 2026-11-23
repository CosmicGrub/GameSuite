package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonRulesTest {
    @Ignore("same seed gives same rolls: not built yet")
    @Test
    fun diceAreTwoSixSidedDrawnFromTheSeededStreamInOrder() {
        // TODO: same seed gives same rolls
    }

    @Ignore("salary rules: not built yet")
    @Test
    fun passingLanternQuayPaysTheStipendOnceAndLandingPaysTwiceOnlyWithTheHouseRule() {
        // TODO: salary rules
    }

    @Ignore("buy decision phase: not built yet")
    @Test
    fun landingOnUnownedPropertyOffersBuyOrAuction() {
        // TODO: buy decision phase
    }

    @Ignore("set bonus: not built yet")
    @Test
    fun rentIsBaseTimesTwoForWholeColourWithoutBuildings() {
        // TODO: set bonus
    }

    @Ignore("mode scaling: not built yet")
    @Test
    fun rentFollowsBuildingLevelAndRentPercentScaling() {
        // TODO: mode scaling
    }

    @Ignore("20/45/90/180: not built yet")
    @Test
    fun transitRentFollowsFerriesOwned() {
        // TODO: 20/45/90/180
    }

    @Ignore("5x and 12x: not built yet")
    @Test
    fun utilityRentIsFiveOrTwelveTimesTheDice() {
        // TODO: 5x and 12x
    }

    @Ignore("tax + jackpot: not built yet")
    @Test
    fun taxTilesTakeTheirFlatAmountAndFeedTheJackpotWhenEnabled() {
        // TODO: tax + jackpot
    }

    @Ignore("mortgage rule: not built yet")
    @Test
    fun pledgedTileCollectsNoRent() {
        // TODO: mortgage rule
    }

    @Ignore("speeding rule: not built yet")
    @Test
    fun threeDoublesInARowSendsThePlayerToTheSandbar() {
        // TODO: speeding rule
    }

    @Ignore("doubles in timeout: not built yet")
    @Test
    fun doublesGiveAnExtraRollButNotWhenLeavingTheSandbar() {
        // TODO: doubles in timeout
    }

    @Ignore("timeout choices: not built yet")
    @Test
    fun sandbarOffersRollFinePayAndFreePassCardAndForcesFineOnThirdTurn() {
        // TODO: timeout choices
    }

    @Ignore("timeout entries: not built yet")
    @Test
    fun goToTimeoutTileAndCardsSendToSandbarWithoutStipend() {
        // TODO: timeout entries
    }

    @Ignore("even build: not built yet")
    @Test
    fun evenBuildRuleRejectsUnevenBuildingAndSelling() {
        // TODO: even build
    }

    @Ignore("build preconditions: not built yet")
    @Test
    fun buildingNeedsWholeColourAndNoPledgedTileInTheColour() {
        // TODO: build preconditions
    }

    @Ignore("resort rule: not built yet")
    @Test
    fun resortNeedsFourCabinsOnEveryTileOfTheColour() {
        // TODO: resort rule
    }

    @Ignore("supply: not built yet")
    @Test
    fun buildingSupplyIsLimitedToThirtySixCabinsAndFourteenResorts() {
        // TODO: supply
    }

    @Ignore("rising tide: not built yet")
    @Test
    fun rentPercentEscalatorStartsAfterTheModeRoundAndGrowsEachRound() {
        // TODO: rising tide
    }

    @Ignore("round cap: not built yet")
    @Test
    fun quickModeEndsAfterTwentyRoundsAndRichestNetWorthWins() {
        // TODO: round cap
    }

    @Ignore("definition: not built yet")
    @Test
    fun netWorthCountsCashTilesAtPriceBuildingsAtCostAndPledgedAtPledgeValue() {
        // TODO: definition
    }

    @Ignore("handicap: not built yet")
    @Test
    fun handicapAddsStartingPearlsToTheChosenSeatOnly() {
        // TODO: handicap
    }
}
