package com.gamesuite.games.liferoad

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD: LifeRoadRngTest is a stub. Every test is @Ignore'd with a reason until its phase lands (docs section 3.10, 3.8).
 * Remove the @Ignore of a test in the same change that makes it pass; never delete a name without updating the doc table.
 */
class LifeRoadRngTest {
    @Ignore("scaffold: not implemented yet")
    @Test fun sameSeedProducesSameSpinSequence() {
        // Two engines with one seed spin identical sequences
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun differentSeedsProduceDifferentSequences() {
        // Different seeds diverge within 20 spins
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun spinsAreAlwaysBetweenOneAndTen() {
        // 100k draws all fall in 1..10
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun spinDistributionIsRoughlyUniform() {
        // Each face is within 1 percentage point of 10% over 200k draws
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun streamsAreIndependent() {
        // Drawing deck or bot numbers never moves the spin cursor
        // TODO
    }

    @Ignore("scaffold: not implemented yet")
    @Test fun restoreFromCursorsContinuesTheSequence() {
        // A state saved at cursor N continues exactly like the uninterrupted run
        // TODO
    }
}
