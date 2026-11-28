package com.gamesuite.games.territorywars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerritoryWarsMapTest {
    private val map = TerritoryWarsMaps.STANDARD

    private fun connected(m: WarMap, ids: Set<String>): Boolean {
        if (ids.isEmpty()) return true
        val seen = HashSet<String>(); val stack = ArrayDeque<String>()
        stack.addLast(ids.first()); seen += ids.first()
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            for (n in m.territory(c)!!.neighbours) if (n in ids && seen.add(n)) stack.addLast(n)
        }
        return seen == ids
    }

    @Test fun standardMapHasThirtySixTerritoriesInSixRegionsOfSix() {
        assertEquals(36, map.size)
        assertEquals(6, map.regions.size)
        assertTrue(map.regions.all { it.territoryIds.size == 6 })
    }

    @Test fun everyBorderIsSymmetricAndNoTerritoryBordersItself() {
        for (t in map.territories) {
            assertTrue(t.id !in t.neighbours)
            for (n in t.neighbours) assertTrue("${t.id} -> $n", t.id in map.territory(n)!!.neighbours)
        }
    }

    @Test fun mapProblemsIsEmptyForAllThreeMaps() {
        for (m in TerritoryWarsMaps.ALL) assertEquals(m.id, emptyList<String>(), m.problems())
    }

    @Test fun everyRegionIsInternallyConnected() {
        for (r in map.regions) assertTrue(r.id, connected(map, r.territoryIds.toSet()))
    }

    @Test fun wholeMapIsConnectedIncludingSeaLinks() {
        assertTrue(connected(map, map.territories.map { it.id }.toSet()))
        for (l in map.seaLinks) assertTrue(l.b in map.territory(l.a)!!.neighbours)
        assertEquals(3, map.seaLinks.size)
    }

    @Test fun blitzHalvesHaveEighteenTerritoriesAndStayConnected() {
        for (m in listOf(TerritoryWarsMaps.BLITZ_WEST, TerritoryWarsMaps.BLITZ_EAST)) {
            assertEquals(18, m.size)
            assertTrue(m.id, connected(m, m.territories.map { it.id }.toSet()))
        }
    }

    @Test fun territoryAndRegionIdsAreUniqueAndKebabCase() {
        val ids = map.territories.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("[a-z]+(-[a-z0-9]+)*").matches(it) })
        assertEquals(map.regions.size, map.regions.map { it.id }.toSet().size)
    }

    @Test fun polygonsAreSimpleAndCentroidsLieInsideBounds() {
        for (t in map.territories) {
            assertTrue(t.polygon.size >= 3)
            val xs = t.polygon.map { it.x }; val ys = t.polygon.map { it.y }
            assertTrue(t.centroid.x in xs.min()..xs.max() && t.centroid.y in ys.min()..ys.max())
            assertTrue(xs.all { it in 0f..map.width } && ys.all { it in 0f..map.height })
        }
    }

    @Test fun regionBonusesMatchTheDocTable() {
        val bonus = mapOf("cinderwake" to 2, "glasswater" to 3, "mistwood" to 3, "stormcrown" to 3, "dunescar" to 4, "heartmark" to 5)
        for (r in map.regions) assertEquals(r.id, bonus.getValue(r.id), r.bonus)
    }

    @Test fun noTerritoryNameIsOnTheTrademarkBlocklist() {
        val banned = listOf("risk", "alaska", "kamchatka", "irkutsk", "yakutsk", "ukraine", "siam", "madagascar", "afghanistan", "brazil", "venezuela", "greenland")
        for (t in map.territories) for (b in banned) assertTrue("${t.name} ~ $b", !t.name.lowercase().contains(b))
    }

    @Test fun mapLookupByIdFindsEveryMap() {
        for (m in TerritoryWarsMaps.ALL) assertEquals(m, TerritoryWarsMaps.byId(m.id))
        assertEquals(null, TerritoryWarsMaps.byId("nowhere"))
    }
}
