package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Bot). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkBotTest {
    @Ignore("TODO FR-B1: same state and seed give the same input")
    @Test fun botIsDeterministicForTheSameSeed() {}

    @Ignore("TODO FR-B2: measured angle error std dev within 15 percent of 9 degrees")
    @Test fun easyBotAimErrorMatchesTheNoiseTable() {}

    @Ignore("TODO FR-B3: success rate at least 90 percent")
    @Test fun hardBotClearsEveryLevelWithinTwiceParInFiftySeeds() {}

    @Ignore("TODO FR-B4: at most 150 000 sim steps for Hard")
    @Test fun botDecisionStaysInsideItsStepBudget() {}

    @Ignore("TODO FR-B5: angle 0..3599 and power 0..100 always")
    @Test fun botNeverReturnsOutOfRangeInput() {}
}
