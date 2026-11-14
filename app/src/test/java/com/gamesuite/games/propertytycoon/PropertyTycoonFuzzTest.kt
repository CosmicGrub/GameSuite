package com.gamesuite.games.propertytycoon

import org.junit.Ignore
import org.junit.Test

/**
 * STUBS for Property Tycoon (design doc, SCALE part, "Test plan"). Every test is @Ignore until its phase lands;
 * remove the annotation as each phase is built. Nothing here was compiled or run by its author.
 */
class PropertyTycoonFuzzTest {
    @Ignore("invariants: not built yet")
    @Test
    fun randomLegalCommandsNeverThrowAndAlwaysConserveTotalPearlsPlusTiles() {
        // TODO: invariants
    }

    @Ignore("round trip: not built yet")
    @Test
    fun anySnapshotTakenMidFuzzRestoresToAnEqualState() {
        // TODO: round trip
    }

    @Ignore("invariants: not built yet")
    @Test
    fun tileOwnershipBuildingSupplyAndDecksStayConsistentAfterEveryCommand() {
        // TODO: invariants
    }
}
