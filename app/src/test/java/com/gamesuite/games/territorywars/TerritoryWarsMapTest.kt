package com.gamesuite.games.territorywars

import org.junit.Ignore
import org.junit.Test

/**
 * SCAFFOLD test stubs: Map content: the invented world is sound (doc 2.5).
 * Every test is @Ignore until the engine exists, so the gate stays green. Each name is a test-plan item
 * (docs/design/boardgames/territory-wars.md section 3.10); un-ignore as the phase that owns it lands.
 */
class TerritoryWarsMapTest {
    @Ignore("scaffold: 36 territories, 6 regions of exactly 6")
    @Test fun standardMapHasThirtySixTerritoriesInSixRegionsOfSix() {}

    @Ignore("scaffold: adjacency symmetric, no self border, no duplicate neighbour")
    @Test fun everyBorderIsSymmetricAndNoTerritoryBordersItself() {}

    @Ignore("scaffold: WarMap.problems() is empty for STANDARD, BLITZ_WEST, BLITZ_EAST")
    @Test fun mapProblemsIsEmptyForAllThreeMaps() {}

    @Ignore("scaffold: each region is one connected component through own borders")
    @Test fun everyRegionIsInternallyConnected() {}

    @Ignore("scaffold: the 36 map is one connected graph")
    @Test fun wholeMapIsConnectedIncludingSeaLinks() {}

    @Ignore("scaffold: west and east halves: 18 each, connected, sea links to the other half dropped")
    @Test fun blitzHalvesHaveEighteenTerritoriesAndStayConnected() {}

    @Ignore("scaffold: ids unique, [a-z-]+ only (saves and network use them)")
    @Test fun territoryAndRegionIdsAreUniqueAndKebabCase() {}

    @Ignore("scaffold: each polygon has 4+ points, non-zero area, centroid inside its bounding box")
    @Test fun polygonsAreSimpleAndCentroidsLieInsideBounds() {}

    @Ignore("scaffold: names, regions and card names avoid the blocklist described in doc 1.7")
    @Test fun noTerritoryNameIsOnTheTrademarkBlocklist() {}

    @Ignore("scaffold: bonuses 2,3,5,4,3,3 as in doc 2.4")
    @Test fun regionBonusesMatchTheDocTable() {}
}
