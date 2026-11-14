package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Physics). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkPhysicsTest {
    @Ignore("TODO FR-P1: a lone pal on open ice only ever loses speed")
    @Test fun speedNeverGrowsWithoutAWallPostOrPalBump() {}

    @Ignore("TODO FR-P2: all 14 levels x 72 angles x 6 powers settle within 900 steps")
    @Test fun everyFlickSettlesWithinMaxSteps() {}

    @Ignore("TODO FR-P3: no resting pal overlaps static geometry at max power")
    @Test fun palNeverEndsInsideAWallOrPost() {}

    @Ignore("TODO FR-P4: speed 900 units per second cannot cross a 14 unit wall")
    @Test fun maxSpeedNeverTunnelsThroughThinWalls() {}

    @Ignore("TODO FR-P5: speedForPower(0)=140, speedForPower(100)=900, strictly rising")
    @Test fun powerCurveIsMonotoneAndHitsBothEndpoints() {}

    @Ignore("TODO FR-P6: normal speed after a wall hit is 0.80 of the incoming normal speed")
    @Test fun wallBounceUsesTheWallRestitution() {}

    @Ignore("TODO FR-P7: two equal pals: momentum kept, relative speed scaled by 0.92")
    @Test fun palPalCollisionConservesMomentumWithRestitution() {}

    @Ignore("TODO FR-P8: same flick travels at most half as far in a drift")
    @Test fun snowDriftStopsAPalSoonerThanOpenIce() {}

    @Ignore("TODO FR-P9: splash event, pal at its turn-start spot, carrying unchanged")
    @Test fun puddleSendsThePalBackToTurnStartAndKeepsTheFish() {}

    @Ignore("TODO FR-P10: staying on the pad does not flip the gate again")
    @Test fun gatePadTogglesItsGateOncePerEntry() {}

    @Ignore("TODO FR-P11: mid-round slides never move a slider")
    @Test fun slidersSwapPoseOnlyAtTheEndOfARound() {}
}
