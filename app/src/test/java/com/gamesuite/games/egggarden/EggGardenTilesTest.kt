package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Tiles). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenTilesTest {
    @Ignore("TODO EG-T1: ids t01..t60, no duplicates")
    @Test fun tileSetHasSixtyUniqueTiles() {}

    @Ignore("TODO EG-T2: five terrains, equal squares and eggs")
    @Test fun everyTerrainHasTwentyFourSquaresAndSeventeenEggs() {}

    @Ignore("TODO EG-T3: a tile never shows 2 and 2")
    @Test fun noTileHasMoreThanThreeEggs() {}

    @Ignore("TODO EG-T4: 12 x players + 4 tiles for competitive, 28 for co-op")
    @Test fun deckIsTheTopOfASeededShuffle() {}

    @Ignore("TODO EG-T5: property over 200 seeds")
    @Test fun sameSeedGivesTheSameDeckAndADifferentSeedDiffers() {}

    @Ignore("TODO EG-T6: property over players 2..4, both lengths, every handicap")
    @Test fun deckNeverRunsOutBeforeTheLastTurn() {}
}
