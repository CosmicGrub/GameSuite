package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Turns). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenTurnsTest {
    @Ignore("TODO EG-H1: turns 1, 4, 7, 10 of 12")
    @Test fun peekTurnsAreEveryThirdTurnForTheChosenSeatsOnly() {}

    @Ignore("TODO EG-H2: deck order afterwards")
    @Test fun aPeekKeepsOneTileAndSendsTheOtherToTheBottom() {}

    @Ignore("TODO EG-H3: 2 to 4 players")
    @Test fun turnOrderFollowsTheFirstMoverAndEveryoneGetsEqualTurns() {}
}
