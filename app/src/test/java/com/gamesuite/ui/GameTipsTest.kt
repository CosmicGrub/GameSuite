package com.gamesuite.ui

import com.gamesuite.core.GameHubSpecs
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** First-run tips: every game has 2 to 3 lines, and both locales carry exactly those lines. */
class GameTipsTest {

    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private fun strings(relative: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.tagName == "string") out[node.getAttribute("name")] = node.textContent
        }
        return out
    }

    /** An Android string value as the user sees it: \' is an apostrophe. */
    private fun shown(raw: String) = raw.replace("\\'", "'")

    private fun stringsDir(relativeDir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(relativeDir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }
            .forEach { out.putAll(strings("$relativeDir/${it.name}")) }
        return out
    }

    private val en by lazy { stringsDir("res/values") }
    private val es by lazy { stringsDir("res/values-es") }

    @Test
    fun everySpecHasTips() {
        val missing = GameHubSpecs.all.map { it.gameId }.filter { GameTips.tipsFor(it).isEmpty() }
        assertTrue("Games with no tips in GameTips.tips: $missing", missing.isEmpty())
    }

    @Test
    fun everyGameHasTwoOrThreeShortLines() {
        for ((id, lines) in GameTips.tips) {
            assertTrue("$id has ${lines.size} tip lines", lines.size in GameTips.MIN_LINES..GameTips.MAX_LINES)
            for (line in lines) {
                assertTrue("$id has a blank tip", line.isNotBlank())
                assertTrue("$id tip is too long to be a tip: $line", line.length <= 140)
            }
        }
    }

    @Test
    fun noTipsForUnknownGames() {
        val ids = GameHubSpecs.all.map { it.gameId }.toSet()
        assertTrue("tips for games that do not exist: ${GameTips.tips.keys - ids}", GameTips.tips.keys.all { it in ids })
        assertTrue(GameTips.tipsFor("no-such-game").isEmpty())
    }

    @Test
    fun resourceNamesAreDeterministic() {
        assertEquals("tip_chess_1", GameTips.resourceName("chess", 0))
        assertEquals("tip_tic_tac_toe_2", GameTips.resourceName("tic-tac-toe", 1))
        assertEquals("tip_dots_and_boxes_3", GameTips.resourceName("dots-and-boxes", 2))
    }

    @Test
    fun englishResourcesMatchTheMapExactly() {
        for ((id, lines) in GameTips.tips) {
            lines.forEachIndexed { i, line ->
                val name = GameTips.resourceName(id, i)
                assertEquals("values/ $name", line, shown(en[name] ?: error("missing $name in values/")))
            }
        }
    }

    @Test
    fun spanishHasEveryLineAndItIsTranslated() {
        for ((id, lines) in GameTips.tips) {
            lines.forEachIndexed { i, line ->
                val name = GameTips.resourceName(id, i)
                val text = shown(es[name] ?: error("missing $name in values-es/"))
                assertTrue("$name is blank in Spanish", text.isNotBlank())
                assertNotEquals("$name was not translated", line, text)
            }
        }
    }

    @Test
    fun noStrayTipResourcesAreLeftBehind() {
        val expected = GameTips.tips.flatMap { (id, lines) -> lines.indices.map { GameTips.resourceName(id, it) } }.toSet()
        val tipNames = en.keys.filter { it.startsWith("tip_") && it.substringAfterLast('_').toIntOrNull() != null }
        assertEquals("orphaned tip strings in values/", emptySet<String>(), tipNames.toSet() - expected)
        val esNames = es.keys.filter { it.startsWith("tip_") && it.substringAfterLast('_').toIntOrNull() != null }
        assertEquals("orphaned tip strings in values-es/", emptySet<String>(), esNames.toSet() - expected)
    }

    @Test
    fun uiStringsExistInBothLocalesWithMatchingPlaceholders() {
        val names = listOf(
            "tip_card_title", "tip_dismiss", "tip_dismiss_desc", "tip_dismissed_notice",
            "tip_show_again", "tip_show_again_desc", "tip_shown_notice", "polish_discarded_notice"
        )
        val placeholder = Regex("%\\d+\\$[sd]")
        for (name in names) {
            val a = en[name] ?: error("missing $name in values/")
            val b = es[name] ?: error("missing $name in values-es/")
            assertEquals("$name placeholders", placeholder.findAll(a).map { it.value }.toList(), placeholder.findAll(b).map { it.value }.toList())
        }
    }
}
