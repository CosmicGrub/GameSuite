package com.gamesuite.games.territorywars

import com.gamesuite.core.GameAudience
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.hubKey
import com.gamesuite.core.kidMaySee
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class TerritoryWarsHubSpecsTest {
    private val spec = TerritoryWarsHubSpecs.territoryWars

    private fun file(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parse(relative: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(relative))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val en by lazy { parse("res/values/territory_wars_strings.xml") }
    private val es by lazy { parse("res/values-es/territory_wars_strings.xml") }

    @Test fun specIsAdultsWithMinAgeTen() {
        assertEquals(GameAudience.ADULTS, spec.audience)
        assertEquals(10, spec.minAge)
        for (age in 3..17) assertFalse(kidMaySee(spec, age))
    }

    @Test fun specIsNotNetworkedInTheMvp() {
        assertFalse(spec.isNetworked)
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
        assertEquals(setOf(TerritoryWarsHubSpecs.GAME_ID), spec.routes())
    }

    @Test fun seatRangeIsTwoToSix() {
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(6, m.maxSeats) }
    }

    @Test fun optionDefaultsAreValidAndParseToTheEngineOptions() {
        for (m in spec.modes) for (c in m.options.filterIsInstance<HubOption.Choice>()) assertTrue(c.key, c.choices.any { it.id == c.default })
        val o = TerritoryWarsOptions.fromExtras(mapOf("length" to "blitz", "fortify" to "adjacent", "autoPlace" to "true", "handicap" to "cpu6"))
        assertEquals(WarLength.BLITZ, o.length); assertEquals(FortifyReach.ADJACENT, o.fortifyReach); assertTrue(o.autoPlace); assertEquals(HandicapPreset.COMPUTERS, o.handicap)
        assertEquals(TerritoryWarsOptions(), TerritoryWarsOptions.fromExtras(mapOf("length" to "???")))
    }

    @Test fun everyHubTextHasAResourceInBothLocales() {
        val texts = LinkedHashSet<String>()
        texts += spec.title; texts += spec.tagline
        for (m in spec.modes) for (o in m.options) {
            if (o.key in setOf("cpus", "humans", "difficulty", "firstMover")) continue
            texts += o.label; texts += o.help
            if (o is HubOption.Choice) o.choices.forEach { texts += it.label; texts += it.help }
        }
        for (t in texts.filter { it.isNotBlank() }) {
            val key = hubKey(t)
            if (key in en) assertTrue("missing ES $key", key in es)
        }
    }

    @Test fun englishAndSpanishFilesHaveTheSameKeysAndPlaceholders() {
        assertEquals(en.keys, es.keys)
        val re = Regex("%(\\d+\\$)?[0-9]*[sdf]")
        for ((k, v) in en) assertEquals(k, re.findAll(v).map { it.value }.sorted().toList(), re.findAll(es.getValue(k)).map { it.value }.sorted().toList())
        val coachEn = parse("res/values/territory_wars_coach_strings.xml"); val coachEs = parse("res/values-es/territory_wars_coach_strings.xml")
        assertEquals(coachEn.keys, coachEs.keys)
    }

    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {
        for (map in listOf(en, es)) for ((k, v) in map) if (k.startsWith("tw_tut_")) assertTrue("$k ${v.length}", v.length < 120)
    }

    @Test fun everyRejectReasonKeyExistsAsAString() {
        for (r in RejectReason.values()) r.whyKey?.let { assertTrue(it, it in en && it in es) }
    }

    @Test fun everyTerritoryAndRegionHasAName() {
        for (t in TerritoryWarsMaps.STANDARD.territories) assertTrue(t.id, "tw_terr_" + t.id.replace('-', '_') in en)
        for (r in TerritoryWarsMaps.STANDARD.regions) assertTrue(r.id, "tw_region_" + r.id in en)
    }

    @Test fun accentColoursMeetContrastMinimums() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(TerritoryWarsHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(TerritoryWarsHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, TerritoryWarsHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, TerritoryWarsHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun seatColoursAndPatternsAreDistinctForSixSeats() {
        assertEquals(6, (0 until 6).map { TerritoryWarsPalette.colorOf(it) }.toSet().size)
        assertEquals(6, (0 until 6).map { TerritoryWarsPalette.patternOf(it) }.toSet().size)
    }
}
