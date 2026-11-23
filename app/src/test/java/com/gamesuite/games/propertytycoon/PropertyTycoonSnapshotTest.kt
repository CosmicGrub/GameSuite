package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonSnapshotTest {
    @Ignore("resume: not built yet")
    @Test
    fun snapshotRoundTripsEveryPhaseIncludingInterrupts() {
        // TODO: resume
    }

    @Ignore("validation: not built yet")
    @Test
    fun restoreRejectsWrongRosterOrSeatCount() {
        // TODO: validation
    }

    @Ignore("validation: not built yet")
    @Test
    fun restoreRejectsImpossibleCashPositionsAndHoldings() {
        // TODO: validation
    }

    @Ignore("validation: not built yet")
    @Test
    fun restoreRejectsBuildingCountsThatBreakEvenOrSupplyRules() {
        // TODO: validation
    }

    @Ignore("validation: not built yet")
    @Test
    fun restoreRejectsDecksThatDropOrDuplicateCards() {
        // TODO: validation
    }

    @Ignore("validation: not built yet")
    @Test
    fun restoreRejectsDanglingAuctionTradeOrDebtState() {
        // TODO: validation
    }

    @Ignore("determinism: not built yet")
    @Test
    fun restoreContinuesTheSameDiceStreamAsAnUninterruptedGame() {
        // TODO: determinism
    }

    @Ignore("size budget: not built yet")
    @Test
    fun snapshotStaysUnderSixteenKilobytesForAWorstCaseSixPlayerGame() {
        // TODO: size budget
    }

    @Ignore("house pattern: not built yet")
    @Test
    fun snapshotIsNullAfterTheMatchEnds() {
        // TODO: house pattern
    }
}
