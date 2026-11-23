package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonBoardTest {
    @Ignore("indexes 0..39 once each, ring order: not built yet")
    @Test
    fun boardHasFortyTilesWithContiguousIndexes() {
        // TODO: indexes 0..39 once each, ring order
    }

    @Ignore("group sizes match the table: not built yet")
    @Test
    fun boardHasTwentyTwoPropertiesInEightGroupsOfTwoTwoTwoThreeThreeThreeThreeFour() {
        // TODO: group sizes match the table
    }

    @Ignore("tile type counts: not built yet")
    @Test
    fun boardHasFourTransitTwoUtilityTwoTaxSixCardTilesAndFourCorners() {
        // TODO: tile type counts
    }

    @Ignore("no missing keys in either locale: not built yet")
    @Test
    fun everyTileNameAndCardTextKeyExistsInEnglishAndSpanishResources() {
        // TODO: no missing keys in either locale
    }

    @Ignore("rent ladder is strictly increasing: not built yet")
    @Test
    fun rentsGrowWithEveryBuildingLevelAndWholeColourDoublesBaseRent() {
        // TODO: rent ladder is strictly increasing
    }

    @Ignore("group ordering sanity: not built yet")
    @Test
    fun priceAndBuildCostRiseFromClayToOpal() {
        // TODO: group ordering sanity
    }

    @Ignore("legal guard: compares with a deny-list file: not built yet")
    @Test
    fun noTileNameOrCardTextMatchesAnyTrademarkedReferenceList() {
        // TODO: legal guard: compares with a deny-list file
    }

    @Ignore("landing-frequency data used by bots: not built yet")
    @Test
    fun heatTableSumsToOneAndIsSymmetricAroundTimeoutCorners() {
        // TODO: landing-frequency data used by bots
    }
}
