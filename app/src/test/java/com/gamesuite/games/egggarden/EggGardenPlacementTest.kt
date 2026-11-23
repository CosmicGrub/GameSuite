package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Placement). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenPlacementTest {
    @Ignore("TODO EG-P1: nest cell stays occupied by the nest")
    @Test fun theNestIsWildAndTheCentreIsNeverCovered() {}

    @Ignore("TODO EG-P2: OUT_OF_BOUNDS and CELL_TAKEN verdicts")
    @Test fun aPlacementMustBeInsideTheGridOnEmptyCells() {}

    @Ignore("TODO EG-P3: NOT_CONNECTED verdict")
    @Test fun aPlacementMustTouchAnOccupiedSquare() {}

    @Ignore("TODO EG-P4: NO_MATCH verdict")
    @Test fun aPlacementMustMatchATerrainOrTouchTheNest() {}

    @Ignore("TODO EG-P5: OK_FREE_SPOT only in that case")
    @Test fun theFreeSpotRuleAppliesOnlyWhenNoMatchingPlacementExists() {}

    @Ignore("TODO EG-P6: no penalty, same number of turns")
    @Test fun anUnplaceableTileIsDiscardedAndTheTurnIsSpent() {}

    @Ignore("TODO EG-P7: no duplicate and no missing legal placement")
    @Test fun everyOrientationIsListedExactlyOnce() {}

    @Ignore("TODO EG-P8: a tile cannot match itself")
    @Test fun adjacencyInsideASameTerrainTileDoesNotCountAsAMatch() {}

    @Ignore("TODO EG-P9: state hash identical before and after")
    @Test fun aRejectedPlacementChangesNothing() {}
}
