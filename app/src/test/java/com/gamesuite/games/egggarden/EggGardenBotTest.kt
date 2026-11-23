package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Bot). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenBotTest {
    @Ignore("TODO EG-B1: same state and seed give the same placement")
    @Test fun botIsDeterministicForTheSameSeed() {}

    @Ignore("TODO EG-B2: property over random gardens")
    @Test fun botAlwaysFindsALegalPlacementWhenOneExists() {}

    @Ignore("TODO EG-B3: medium beats easy at least 60 percent over 500 seeds")
    @Test fun botLevelsAreOrderedByWinRate() {}

    @Ignore("TODO EG-B4: 7x7 co-op garden, Hard")
    @Test fun botDecisionTakesUnderTwentyMilliseconds() {}
}
