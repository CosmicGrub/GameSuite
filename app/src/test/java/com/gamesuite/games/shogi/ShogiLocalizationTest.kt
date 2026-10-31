package com.gamesuite.games.shogi

import com.gamesuite.core.hubKey
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Both locales of shogi_strings.xml are complete, in step, and cover the Hub texts the specs will use. */
class ShogiLocalizationTest {
    private fun load(dir: String): Map<String, String> {
        val rel = "src/main/res/$dir/shogi_strings.xml"
        val f = File(rel).takeIf { it.exists() } ?: File("app/$rel")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val en by lazy { load("values") }
    private val es by lazy { load("values-es") }

    @Test fun bothLocalesDefineTheSameNames() {
        assertEquals(en.keys, es.keys)
        assertTrue(en.size > 100)
    }

    @Test fun placeholdersMatchBetweenLocales() {
        val ph = Regex("%(\\d+\\$)?[sdf]")
        for ((k, v) in en) {
            assertEquals("placeholders of $k", ph.findAll(v).map { it.value }.sorted().toList(), ph.findAll(es.getValue(k)).map { it.value }.sorted().toList())
        }
    }

    @Test fun apostrophesAreEscaped() {
        for ((file, map) in listOf("en" to en, "es" to es)) {
            // textContent drops the backslash; read raw to be sure no bare apostrophe remains
            val raw = File(if (File("src/main/res").exists()) "src/main/res" else "app/src/main/res")
                .resolve((if (file == "en") "values" else "values-es") + "/shogi_strings.xml").readText()
            assertTrue("$file has an unescaped apostrophe", !Regex("(?<!\\\\)'").containsMatchIn(raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")))
            assertTrue(map.isNotEmpty())
        }
    }

    private val hubTexts = listOf(
        "Shogi", "Mini Shogi",
        "Japanese chess: captured pieces join your side.",
        "Shogi on a small 5x5 board: quick, sharp games.",
        "Piece style",
        "Kanji is the traditional look. Symbols draws arrows showing how each piece moves, so no kanji reading is needed.",
        "Kanji", "Traditional characters.", "Symbols", "Arrows and letters."
    )

    @Test fun everyHubTextHasAKeyedStringInBothLocales() {
        for (t in hubTexts) {
            val key = hubKey(t)
            assertEquals("English copy of $t", t, en[key]?.replace("\\'", "'"))
            assertTrue("Spanish copy of $t", !es[key].isNullOrBlank())
        }
    }

    @Test fun tipsExistForBothGames() {
        for (id in listOf("shogi", "mini_shogi")) {
            for (i in 1..3) {
                assertTrue(en.containsKey("tip_${id}_$i"))
                assertTrue(es.containsKey("tip_${id}_$i"))
            }
        }
    }
}
