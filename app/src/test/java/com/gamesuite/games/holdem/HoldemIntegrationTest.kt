package com.gamesuite.games.holdem

import com.gamesuite.core.GameCoach
import com.gamesuite.ui.GameAccent
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the integrator pastes into shared files is checked here first: the tips, the coach strings, the
 * accent, plus the resource files this game owns (complete in both languages, escaped, never colliding with
 * another game's resource names, and covering every `R.string.holdem_*` the code uses).
 */
class HoldemIntegrationTest : HoldemResourceTest() {

    /** The exact GameTips.kt map entry (must equal tip_texas_holdem_1..3 in values/holdem_strings.xml). */
    private val tipLiterals = listOf(
        "You get two private cards. Five shared cards come face up; make your best five-card hand.",
        "On your turn: fold, check or call, or raise with the slider and its presets.",
        "Chips are for play only. The last player with chips wins, and the blinds can rise."
    )

    /** The accent to add to GameThemes.accents. */
    private val accent = GameAccent(0xFF8B1E3F, 0xFFF07C9E)

    // ---- tips ----------------------------------------------------------------------------------

    @Test fun tipLiteralsEqualTheEnglishStringsAndSpanishDiffers() {
        assertTrue(tipLiterals.size in 2..3)
        tipLiterals.forEachIndexed { i, line ->
            val name = "tip_texas_holdem_${i + 1}"
            assertEquals(line, shown(enAll[name] ?: error("missing $name")))
            assertTrue(line.length <= 140)
            val es = shown(esAll[name] ?: error("missing ES $name"))
            assertTrue(es.isNotBlank())
            assertNotEquals(line, es)
        }
    }

    // ---- coach ---------------------------------------------------------------------------------

    @Test fun coachStringsFollowTheGameCoachConventions() {
        val en = parseFile(moduleFile("res/values/holdem_coach_strings.xml")).strings
        val es = parseFile(moduleFile("res/values-es/holdem_coach_strings.xml")).strings
        assertEquals(en.keys, es.keys)
        val steps = (1..5).map { GameCoach.stepName("texas-holdem", it) }
        assertEquals(listOf("coach_texas_holdem_s1", "coach_texas_holdem_s2", "coach_texas_holdem_s3", "coach_texas_holdem_s4", "coach_texas_holdem_s5"), steps)
        val fact = GameCoach.factName("texas-holdem")
        assertEquals("coach_texas_holdem_fact", fact)
        assertEquals((steps + fact).toSet(), en.keys)
        for (n in steps + fact) {
            assertTrue(en[n]!!.isNotBlank())
            assertNotEquals("ES not translated: $n", en[n], es[n])
        }
        for (dir in listOf("values", "values-es")) {
            val raw = moduleFile("res/$dir/holdem_coach_strings.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertFalse("$dir coach has an unescaped apostrophe", Regex("(?<!\\\\)'").containsMatchIn(raw))
        }
        // it must teach the money rule, the hand ranking and the betting controls
        val all = steps.joinToString(" ") { en[it]!! }.lowercase()
        assertTrue("play money" in all || "for play only" in all)
        assertTrue("flop" in all && "river" in all && "blinds" in all && "side pot" in all)
    }

    // ---- accent --------------------------------------------------------------------------------

    @Test fun theAccentIsDistinctAndLegibleInBothThemes() {
        assertTrue(GameThemes.accents.values.count { it.light == accent.light } <= 1)
        assertTrue(GameThemes.accents.values.count { it.dark == accent.dark } <= 1)
        for (surface in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(accent.light, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (surface in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(accent.dark, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (a in listOf(accent.light, accent.dark)) {
            assertTrue(Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }

    // ---- the strings files this game owns ------------------------------------------------------

    private val ownFiles = listOf("holdem_strings.xml", "holdem_coach_strings.xml")

    @Test fun ownFilesAreCompleteAndConsistentInBothLanguages() {
        for (f in ownFiles) {
            val en = parseFile(moduleFile("res/values/$f"))
            val es = parseFile(moduleFile("res/values-es/$f"))
            assertEquals("$f string names", en.strings.keys, es.strings.keys)
            assertEquals("$f array names", en.arrays.keys, es.arrays.keys)
            for ((k, v) in en.strings) {
                assertTrue("$k blank", v.isNotBlank() && es.strings[k]!!.isNotBlank())
                assertEquals("placeholders of $k", placeholders(v), placeholders(es.strings[k]!!))
            }
            for ((k, v) in en.arrays) assertEquals("$k size", v.size, es.arrays[k]!!.size)
            for (dir in listOf("values", "values-es")) {
                val raw = moduleFile("res/$dir/$f").readText()
                assertFalse("$dir/$f has an unescaped apostrophe", Regex("(?<!\\\\)'").containsMatchIn(raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")))
            }
        }
    }

    @Test fun noResourceNameCollidesWithAnotherFile() {
        for (dir in listOf("values", "values-es")) {
            val mine = ownFiles.flatMap { f -> parseFile(moduleFile("res/$dir/$f")).let { it.strings.keys + it.arrays.keys } }.toSet()
            val others = moduleFile("res/$dir").listFiles { f -> f.isFile && f.name.endsWith(".xml") && f.name !in ownFiles }!!
            for (o in others) {
                val p = parseFile(o)
                val clash = mine.intersect(p.strings.keys + p.arrays.keys)
                assertTrue("${o.name} also defines $clash", clash.isEmpty())
            }
        }
    }

    @Test fun everyResourceTheCodeUsesExistsInBothLanguages() {
        val sources = listOf(
            "java/com/gamesuite/ui/HoldemScreen.kt",
            "java/com/gamesuite/games/holdem/HoldemCardArt.kt",
            "java/com/gamesuite/games/holdem/HoldemGame.kt"
        )
        val refs = sources.flatMap { src ->
            Regex("R\\.(string|array)\\.([a-z0-9_]+)").findAll(moduleFile(src).readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toSet()
        assertTrue(refs.size > 80)
        for ((kind, name) in refs) {
            if (kind == "string") {
                assertTrue("R.string.$name missing in values", name in enAll)
                assertTrue("R.string.$name missing in values-es", name in esAll)
            } else {
                assertTrue("R.array.$name missing in values", name in enArrays)
                assertTrue("R.array.$name missing in values-es", name in esArrays)
            }
        }
        // and nothing we define is dead weight
        val used = refs.map { it.second }.toSet()
        val mine = parseFile(moduleFile("res/values/holdem_strings.xml"))
        val unused = (mine.strings.keys + mine.arrays.keys).filter { !it.startsWith("hub_") && !it.startsWith("tip_") && it !in used }
        assertEquals("unused strings: $unused", emptyList<String>(), unused)
    }

    @Test fun rankSuitAndHandNamesAreCompleteAndTheMoneyRuleIsStated() {
        assertEquals(13, enArrays["holdem_rank_names"]!!.size)
        assertEquals(13, esArrays["holdem_rank_plurals"]!!.size)
        assertEquals(listOf("clubs", "diamonds", "hearts", "spades"), enArrays["holdem_suit_names"])
        assertEquals("Ace", enArrays["holdem_rank_names"]!!.last())
        assertEquals("Aces", enArrays["holdem_rank_plurals"]!!.last())
        assertTrue(shown(enAll["holdem_help_text"]!!).contains("no real money"))
        assertTrue(shown(esAll["holdem_help_text"]!!).contains("dinero real"))
    }

    @Test fun statusStringsAreNeutralSoTheyReadRightForYou() {
        // "%1$s raises" style sentences break for the seat called "You"; these use a colon form instead.
        val names = listOf("holdem_ev_fold", "holdem_ev_check", "holdem_ev_call", "holdem_ev_bet", "holdem_ev_raise", "holdem_ev_allin")
        for (n in names) {
            assertTrue("$n: ${enAll[n]}", enAll[n]!!.startsWith("%1\$s:"))
            assertTrue("$n: ${esAll[n]}", esAll[n]!!.startsWith("%1\$s:"))
        }
        // no verb directly after the name placeholder anywhere in the status/why strings
        val verbs = Regex("%1\\\$s (raises|calls|folds|checks|bets|wins|is|has|goes)")
        for (n in enAll.keys.filter { it.startsWith("holdem_") }) assertFalse("$n reads wrong for You", verbs.containsMatchIn(enAll[n]!!))
    }

    @Test fun theLaunchedGameAndSpecAgreeOnIds() {
        val g = HoldemGame()
        assertEquals("texas-holdem", g.gameId)
        assertEquals(HoldemHubSpecs.holdem.gameId, g.gameId)
        assertEquals(2, g.minPlayers)
        assertEquals(6, g.maxPlayers)
        assertTrue(File(".").exists())
    }
}
