package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonCardTest {
    @Ignore("deck integrity: not built yet")
    @Test
    fun eachDeckHasSixteenUniqueCardsAndEveryCardHasTextInBothLocales() {
        // TODO: deck integrity
    }

    @Ignore("cycling: not built yet")
    @Test
    fun decksShuffleDeterministicallyFromTheSeedAndCycleWithoutLosingCards() {
        // TODO: cycling
    }

    @Ignore("held cards: not built yet")
    @Test
    fun getOutFreeCardLeavesTheDeckUntilUsedThenReturnsToTheBottom() {
        // TODO: held cards
    }

    @Ignore("MoveTo semantics: not built yet")
    @Test
    fun moveCardsCollectTheStipendOnlyWhenPassingTheQuayForward() {
        // TODO: MoveTo semantics
    }

    @Ignore("nearest effects: not built yet")
    @Test
    fun moveToNearestResolvesFerryDoubleFareAndUtilityTenTimesDice() {
        // TODO: nearest effects
    }

    @Ignore("repairs: not built yet")
    @Test
    fun repairCardsChargeByBuildingCountAndRaiseDebtWhenShort() {
        // TODO: repairs
    }

    @Ignore("each rival: not built yet")
    @Test
    fun payAndCollectEachRivalCardsSkipBankruptPlayers() {
        // TODO: each rival
    }
}
