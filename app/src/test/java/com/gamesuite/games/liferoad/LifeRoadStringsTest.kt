package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the module's resource XML (the unit tests run from the module or the repo root). */
internal object LifeRoadStringFiles {
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

class LifeRoadStringsTest {
    private val en by lazy { LifeRoadStringFiles.parse("res/values/life_road_strings.xml") }
    private val es by lazy { LifeRoadStringFiles.parse("res/values-es/life_road_strings.xml") }

    @Test fun everyEnglishKeyHasASpanishValue() {
        assertEquals(en.keys, es.keys)
        assertTrue(es.values.none { it.isBlank() })
    }

    @Test fun formatPlaceholdersMatchBetweenLocales() {
        val re = Regex("%(\\d+\\$)?[0-9]*[sdf]")
        for ((k, v) in en) assertEquals(k, re.findAll(v).map { it.value }.sorted().toList(), re.findAll(es.getValue(k)).map { it.value }.sorted().toList())
    }

    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {
        for (map in listOf(en, es)) for (i in 1..16) assertTrue(map.getValue("lr_tut_%02d".format(i)).length < 120)
    }

    @Test fun everyContentIdHasANameString() {
        for (j in LifeRoadContent.jobs) assertTrue(j.nameKey, j.nameKey in en)
        for (h in LifeRoadContent.houses) assertTrue(h.nameKey, h.nameKey in en)
        for (m in LifeRoadContent.milestones) assertTrue(m.nameKey, m.nameKey in en)
        for (c in LifeRoadContent.events) assertTrue(c.textKey, c.textKey in en)
        for (c in LifeRoadContent.familyCards) assertTrue(c.textKey, c.textKey in en)
        for (k in NodeKind.values()) { assertTrue(k.nameKey, k.nameKey in en); assertTrue(k.helpKey, k.helpKey in en) }
        for (r in LifeRoadReject.values()) r.stringKey?.let { assertTrue(it, it in en) }
        for (c in CoverKind.values()) assertTrue(c.nameKey, c.nameKey in en)
    }

    @Test fun noForbiddenTrademarkWordsAppear() {
        val banned = listOf("the game of life", "hasbro", "mattel", "monopoly", "candy land", "sorry!", "payday", "pay day",
            "millionaire estates", "countryside acres", "life tiles", "milton bradley", "parker brothers")
        for (map in listOf(en, es)) for ((k, v) in map) for (b in banned) assertFalse("$k contains $b", v.lowercase().contains(b))
    }

    @Test fun noRealMoneyWordsAppear() {
        for (map in listOf(en, es)) for ((k, v) in map) {
            assertFalse("$k: $v", v.contains("$") || v.contains("€") || v.lowercase().let { "dollar" in it || "euro " in it || "dólar" in it })
        }
    }

    @Test fun noPercentSignsInFormatStrings() {
        for (map in listOf(en, es)) for ((k, v) in map) assertFalse("$k: $v", Regex("%(?![\\d$]*[sdf%])").containsMatchIn(v) && v.contains("%"))
    }
}
