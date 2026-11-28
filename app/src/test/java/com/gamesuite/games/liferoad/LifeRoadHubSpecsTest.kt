package com.gamesuite.games.liferoad

import com.gamesuite.core.GameAudience
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.hubKey
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadHubSpecsTest {
    private val spec = LifeRoadHubSpecs.lifeRoad

    @Test fun audienceIsFamilyAndMinAgeIsEight() {
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(8, spec.minAge)
        assertEquals("life-road", spec.gameId)
        assertTrue(spec.resumable)
    }

    @Test fun noNetworkModesInTheFirstRelease() {
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
        assertFalse(spec.isNetworked)
        assertEquals(setOf("life-road"), spec.routes())
    }

    @Test fun seatLimitsAreTwoToSix() {
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(6, m.maxSeats) }
    }

    @Test fun optionKeysAndDefaultsAreValid() {
        for (m in spec.modes) {
            for (c in m.options.filterIsInstance<HubOption.Choice>()) assertTrue("${c.key} default", c.choices.any { it.id == c.default })
            assertEquals(m.options.size, m.options.map { it.key }.toSet().size)
        }
        val keys = spec.modes.first().options.map { it.key }
        for (k in listOf("rules", "length", "handicap", "handicapFor", "catchUp")) assertTrue(k, k in keys)
    }

    @Test fun juniorChoiceAppliesTheQuickLength() {
        val rules = spec.modes.first().options.filterIsInstance<HubOption.Choice>().first { it.key == "rules" }
        assertEquals("quick", rules.choices.first { it.id == "junior" }.applies["length"])
    }

    @Test fun theBoostSeatChoiceOnlyShowsWithABoost() {
        val opt = spec.modes.first().options.first { it.key == "handicapFor" }
        assertFalse(opt.visibleWhen!!.invoke(HubChoices()))
        assertTrue(opt.visibleWhen!!.invoke(HubChoices().with("handicap", "small")))
    }

    @Test fun everyHubTextHasEnglishAndSpanishStrings() {
        val en = LifeRoadStringFiles.parse("res/values/life_road_strings.xml")
        val es = LifeRoadStringFiles.parse("res/values-es/life_road_strings.xml")
        val texts = LinkedHashSet<String>()
        texts += spec.title; texts += spec.tagline
        for (m in spec.modes) for (o in m.options) {
            if (o.key == "cpus" || o.key == "humans" || o.key == "difficulty" || o.key == "firstMover") continue
            texts += o.label; texts += o.help
            if (o is HubOption.Choice) o.choices.forEach { texts += it.label; texts += it.help }
        }
        val shared = LifeRoadStringFiles.parseDir("res/values")
        for (t in texts.filter { it.isNotBlank() }) {
            val key = hubKey(t)
            assertTrue("missing EN $key for '$t'", key in en || key in shared)
            if (key in en) assertTrue("missing ES $key", key in es)
        }
    }

    @Test fun accentColoursMeetContrast() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(LifeRoadHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(LifeRoadHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, LifeRoadHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, LifeRoadHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun accentIsNotOneAnotherGameUses() {
        for ((id, a) in GameThemes.accents) {
            assertTrue(id, a.light != LifeRoadHubSpecs.ACCENT_LIGHT && a.dark != LifeRoadHubSpecs.ACCENT_DARK)
        }
    }

    @Test fun theGlyphIsASingleSymbol() {
        assertEquals(1, LifeRoadHubSpecs.GLYPH.codePoints().filter { it != 0xFE0F }.count().toInt())
    }
}
