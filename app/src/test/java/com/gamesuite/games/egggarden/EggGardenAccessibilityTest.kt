package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Accessibility). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenAccessibilityTest {
    @Ignore("TODO EG-A1: tap a highlighted cell then Place it")
    @Test fun aTileCanBePlacedByTappingACellInsteadOfDragging() {}

    @Ignore("TODO EG-A2: scan order is row-major, wraps around")
    @Test fun oneSwitchCyclesTheLegalSpotsThenConfirms() {}

    @Ignore("TODO EG-A3: five unique glyphs and five unique colours")
    @Test fun everyTerrainHasADistinctShapeAndColour() {}
}
