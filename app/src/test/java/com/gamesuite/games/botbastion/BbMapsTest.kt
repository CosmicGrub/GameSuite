package com.gamesuite.games.botbastion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T11: the map data rules of MAPS.md, for every map in the file. */
class MapDataValidationTest {
    @Test fun everyMapInTheFileIsPlayable() {
        val maps = allMaps()
        assertTrue(maps.size >= 3)
        for (m in maps) assertEquals(m.id, emptyList<String>(), BbMaps.problems(m))
        assertEquals(maps.size, shippedMaps().size)
    }

    @Test fun theThreeLaunchMapsAreThereWithTheirRules() {
        val ids = shippedMaps().map { it.id }
        assertTrue(ids.containsAll(listOf("hearth", "twin", "pebble")))
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(0, shipped("hearth").unlockStars)
        assertEquals(0, shipped("twin").unlockStars)
        assertEquals(2, shipped("pebble").unlockStars)
    }

    @Test fun unlockStarsNeverDecreaseInFileOrder() {
        val stars = shippedMaps().map { it.unlockStars }
        assertEquals(stars.sorted(), stars)
    }

    @Test fun theValidatorCatchesEachRule() {
        val good = shipped("hearth")
        fun with(nodes: List<Pt> = good.nodes, lanes: List<Lane> = good.lanes, voids: List<Void> = good.voids) =
            MapDef(good.id, good.sector, good.pace, good.unlockStars, good.core, lanes, nodes, voids)
        assertTrue(BbMaps.problems(with(nodes = good.nodes.take(11))).any { "node count" in it })
        assertTrue(BbMaps.problems(with(nodes = good.nodes.drop(1) + Pt(good.lanes[0].pointAt(10.0).x, good.lanes[0].pointAt(10.0).y + 0.1))).any { "lane" in it })
        assertTrue(BbMaps.problems(with(nodes = good.nodes.drop(1) + Pt(good.core.x + 0.3, good.core.y - 0.9))).any { "Core" in it })
        assertTrue(BbMaps.problems(with(nodes = good.nodes + good.nodes[0])).any { "too close" in it })
        assertTrue(BbMaps.problems(with(voids = listOf(Void(good.nodes[0].x, good.nodes[0].y, 1.0, 1.0)))).any { "void" in it })
        val shortLane = Lane(listOf(Pt(5.0, 5.0), Pt(6.0, 5.0)))
        assertTrue(BbMaps.problems(with(lanes = listOf(shortLane))).any { "Core" in it || "inside" in it })
        assertTrue(BbMaps.problems(with(nodes = good.nodes.drop(1) + Pt(17.0, 4.0))).any { "outside" in it })
    }

    @Test fun brokenJsonYieldsNoMapsAndABadMapIsSkipped() {
        assertEquals(0, BbMaps.parse("not json").size)
        assertEquals(0, BbMaps.parse("{\"maps\":[{\"id\":\"x\"}]}").size)
    }

    @Test fun everyMapHasAtLeastOneLanePerSectorRuleAndNodeCountInRange() {
        for (m in shippedMaps()) {
            assertTrue(m.id, m.nodes.size in 12..22)
            assertTrue(m.id, m.lanes.isNotEmpty())
            assertTrue(m.id, m.sector in 1..4)
        }
    }
}
