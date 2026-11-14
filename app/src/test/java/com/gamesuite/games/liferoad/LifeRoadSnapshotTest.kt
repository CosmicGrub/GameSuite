package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadSnapshotTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadSnapshotTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun snapshotRoundTripsMidGame() {
        // snapshot then restore equals the original state
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsAnUnknownVersion() {
        // Version field gate
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsAWrongPlayerCount() {
        // Roster must match the saved seats
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsNegativeCash() {
        // Range checks
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsAnUnknownNodeId() {
        // Node id must exist in the board for the saved size
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsDuplicateHomeOwnership() {
        // A home id may be owned once
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsAPendingThatDoesNotMatchThePhase() {
        // Phase and pending must agree
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreRejectsOverCapCounts() {
        // Loans, homes, family and memories above their caps
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun resumedGameMatchesTheUninterruptedGame() {
        // Save at a random step, restore, play on: identical result
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun snapshotStaysUnderTwelveKilobytes() {
        // Size budget
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun snapshotIsNullAfterGameOver() {
        // Finished games are not resumable
        // TODO
    }
}
