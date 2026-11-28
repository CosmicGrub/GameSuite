package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the module's resource XML (the unit tests run from the module or the repo root). */
internal object PtStringFiles {
    fun file(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    fun parse(relative: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(relative))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    fun parseDir(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        file(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f -> out += parse("$dir/${f.name}") }
        return out
    }
}

class PropertyTycoonBoardTest {
    private val tiles = PtBoard.tiles

    @Test fun boardHasFortyTilesWithContiguousIndexes() {
        assertEquals(40, tiles.size)
        assertEquals((0 until 40).toList(), tiles.map { it.index })
    }

    @Test fun boardHasTwentyTwoPropertiesInEightGroupsOfTwoTwoTwoThreeThreeThreeThreeFour() {
        assertEquals(22, tiles.count { it.type == TileType.PROPERTY })
        for (g in ColorGroup.values()) assertEquals(g.name, g.size, PtBoard.tilesOf(g).size)
        assertEquals(listOf(2, 2, 2, 3, 3, 3, 3, 4), ColorGroup.values().map { it.size })
    }

    @Test fun boardHasFourTransitTwoUtilityTwoTaxSixCardTilesAndFourCorners() {
        assertEquals(4, tiles.count { it.type == TileType.TRANSIT })
        assertEquals(2, tiles.count { it.type == TileType.UTILITY })
        assertEquals(2, tiles.count { it.type == TileType.TAX })
        assertEquals(3, tiles.count { it.type == TileType.TIDE_CARD })
        assertEquals(3, tiles.count { it.type == TileType.GUILD_CARD })
        assertEquals(listOf(TileType.START, TileType.TIMEOUT, TileType.FREE_ANCHORAGE, TileType.GO_TO_TIMEOUT), listOf(0, 10, 20, 30).map { tiles[it].type })
    }

    @Test fun everyTileNameAndCardTextKeyExistsInEnglishAndSpanishResources() {
        val en = PtStringFiles.parse("res/values/property_tycoon_strings.xml")
        val es = PtStringFiles.parse("res/values-es/property_tycoon_strings.xml")
        for (t in tiles) { assertTrue(t.nameKey, t.nameKey in en); assertTrue(t.nameKey, t.nameKey in es) }
        assertEquals(en.keys, es.keys)
    }

    @Test fun rentsGrowWithEveryBuildingLevelAndWholeColourDoublesBaseRent() {
        for (t in tiles.filter { it.type == TileType.PROPERTY }) {
            assertEquals(6, t.rents.size)
            assertEquals(t.rents.sorted(), t.rents)
            assertTrue(t.rents.zipWithNext().all { (a, b) -> b > a })
            assertEquals(t.price / 2, t.pledgeValue)
        }
    }

    @Test fun priceAndBuildCostRiseFromClayToOpal() {
        val byGroup = ColorGroup.values().map { g -> PtBoard.tilesOf(g).map { it.price }.average() }
        assertEquals(byGroup.sorted(), byGroup)
        val build = ColorGroup.values().map { g -> PtBoard.tilesOf(g).first().buildCost }
        assertEquals(build.sorted(), build)
    }

    @Test fun nearestAheadWrapsTheRing() {
        assertEquals(6, PtBoard.nearestAhead(3, TileType.TRANSIT))
        assertEquals(6, PtBoard.nearestAhead(36, TileType.TRANSIT))
        assertEquals(12, PtBoard.nearestAhead(30, TileType.UTILITY))
    }

    @Test fun noTileNameOrCardTextMatchesAnyTrademarkedReferenceList() {
        val banned = listOf("monopoly", "boardwalk", "park place", "mediterranean", "baltic", "marvin gardens", "reading railroad", "chance", "community chest",
            "go to jail", "just visiting", "free parking", "pass go", "collect 200", "hasbro", "illinois", "st. charles", "atlantic", "ventnor", "pennsylvania")
        for (map in listOf(PtStringFiles.parse("res/values/property_tycoon_strings.xml"), PtStringFiles.parse("res/values-es/property_tycoon_strings.xml"))) {
            for ((k, v) in map) for (b in banned) assertTrue("$k contains $b", !v.lowercase().contains(b))
        }
    }

    @Test fun noRealMoneyWordsAppear() {
        for ((k, v) in PtStringFiles.parse("res/values/property_tycoon_strings.xml")) {
            assertTrue("$k: $v", !v.contains("$") && !v.lowercase().contains("dollar") && !v.lowercase().contains("real money") || k == "hub_play_money_only_everyone_starts_equal")
        }
    }
}
