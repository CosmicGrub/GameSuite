package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Levels). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkLevelsTest {
    @Ignore("TODO FR-L1: JSONL parses, ids rink01..rink14, three fish each")
    @Test fun allFourteenLevelsParseAndValidate() {}

    @Ignore("TODO FR-L2: grid flood fill from home in every pose")
    @Test fun everyFishAndPadIsReachableInEveryGateAndSliderState() {}

    @Ignore("TODO FR-L3: par is non-decreasing across the band boundaries")
    @Test fun difficultyRisesWithBandAndPar() {}

    @Ignore("TODO FR-L4: no fish within radius + 20 of a puddle or post")
    @Test fun noFishOrStartOverlapsAHazard() {}

    @Ignore("TODO FR-L5: pal diameter 68 plus a 12 unit margin everywhere on the route")
    @Test fun corridorsAreAtLeastEightyUnitsWide() {}
}
