package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Accessibility). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkAccessibilityTest {
    @Ignore("TODO FR-A1: three taps produce one legal flick")
    @Test fun oneSwitchSweepLocksAimThenPowerThenFlicks() {}

    @Ignore("TODO FR-A2: turn buttons step 5 degrees, power buttons step 10 percent")
    @Test fun buttonAimReachesEveryAngleAndPower() {}

    @Ignore("TODO FR-A3: skipping the slide gives the identical state hash")
    @Test fun reducedMotionSkipsPlaybackWithTheSameOutcome() {}
}
