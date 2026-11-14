package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Rules). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkRulesTest {
    @Ignore("TODO FR-R1: 58 units normal, 87 relaxed")
    @Test fun fishIsPickedUpWhenTheCentreIsInsidePickupRadius() {}

    @Ignore("TODO FR-R2: moving through the ring does not bank; stopping inside does")
    @Test fun bankingNeedsAStopInsideTheHomeRingWhileCarrying() {}

    @Ignore("TODO FR-R3: cap is 12, 8 and 6 flicks each for 2, 3 and 4 players")
    @Test fun aRinkEndsAtThreeFishOrWhenTheFlickCapIsUsed() {}

    @Ignore("TODO FR-R4: 2.0 little, 1.6 medium, 1.3 big")
    @Test fun coopBudgetIsParTimesTheBandMultiplierRoundedUp() {}

    @Ignore("TODO FR-R5: third failed try gives +6, a fourth stays at +6")
    @Test fun coopRetryAddsTwoFlicksCappedAtSix() {}

    @Ignore("TODO FR-R6: fish moves to the pal that was hit; only in co-op")
    @Test fun relayBumpHandsTheFishToTheBumpedPal() {}

    @Ignore("TODO FR-R7: 3 stars at ceil(par*1.25), 2 stars at ceil(par*1.75)")
    @Test fun soloStarsFollowTheParThresholds() {}

    @Ignore("TODO FR-R8: 5, 3, 1, 0 by centre distance; ties by bullseyes then closest disc then shared")
    @Test fun bullseyeRingPointsAndTieBreaks() {}

    @Ignore("TODO FR-R9: most fish, then fewest flicks, then shared win")
    @Test fun turnsWinnerAndTieAreSharedFairly() {}

    @Ignore("TODO FR-R10: preview line length is 0 for seats that did not get the helper")
    @Test fun aimAssistOnlyAffectsTheChosenSeats() {}
}
