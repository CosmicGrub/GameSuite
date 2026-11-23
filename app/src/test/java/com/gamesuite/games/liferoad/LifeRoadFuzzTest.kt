package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadFuzzTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadFuzzTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun randomLegalPlayNeverThrows() {
        // 10k games with random legal actions
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun invariantsHoldAfterEveryStep() {
        // Cash at least 0, loans at most 3, homes at most 2, family at most 4, memories at most 6
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun snapshotRestoreInTheMiddleOfFuzzGames() {
        // Restore at random steps and keep going
        // TODO
    }
}
