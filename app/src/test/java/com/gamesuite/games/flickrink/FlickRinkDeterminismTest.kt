package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Determinism). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkDeterminismTest {
    @Ignore("TODO FR-D1: same level, seed and flick log produce identical frames on two engine instances")
    @Test fun samePhysicsInputsGiveByteIdenticalTrajectories() {}

    @Ignore("TODO FR-D2: driving the sim from a 60 Hz and a 120 Hz render loop ends in the same state hash")
    @Test fun simulatedStepsDoNotDependOnDisplayRefreshRate() {}

    @Ignore("TODO FR-D3: recorded flicks replayed from the seed give the same hash at every turn")
    @Test fun replayingTheFlickLogReproducesTheStateHash() {}

    @Ignore("TODO FR-D4: same seed means the same bot flicks; a different seed differs")
    @Test fun botNoiseIsSeededSoTheSameSeedPlaysTheSameMatch() {}

    @Ignore("TODO FR-D5: allocation counter stays flat across 10 000 sim steps")
    @Test fun stepLoopAllocatesNothingAfterWarmup() {}

    @Ignore("TODO FR-D6: recording frames on or off never changes the hash")
    @Test fun stateHashIgnoresTheRenderFrameBuffer() {}
}
