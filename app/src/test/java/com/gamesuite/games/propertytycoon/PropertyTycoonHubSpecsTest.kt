package com.gamesuite.games.propertytycoon

import com.gamesuite.core.GameAudience
import com.gamesuite.core.HubChoices
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

class PropertyTycoonHubSpecsTest {
    private val spec = PropertyTycoonHubSpecs.propertyTycoon

    @Test fun specIsAdultsAudienceWithMinAgeEightSoKidProfilesNeverSeeIt() {
        assertEquals(GameAudience.ADULTS, spec.audience)
        assertEquals(8, spec.minAge)
        assertEquals("property-tycoon", spec.gameId)
    }

    @Test fun kidMaySeeIsFalseForEveryAgeBand() {
        for (age in 3..17) assertFalse("age $age", kidMaySee(spec, age))
    }

    @Test fun specIsLocalOnlyAndHasTwoToSixSeats() {
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
        assertFalse(spec.isNetworked)
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(6, m.maxSeats) }
    }

    @Test fun onlyOptionsTheEngineHonoursAreOffered() {
        // auctions and trades ship with v1 (PtRelease); offering a toggle that does nothing would be dishonest
        for (m in spec.modes) {
            val keys = m.options.map { it.key }
            assertFalse(PropertyTycoonHubSpecs.KEY_AUCTIONS in keys)
            assertFalse(PropertyTycoonHubSpecs.KEY_TRADES in keys)
            for (c in m.options.filterIsInstance<HubOption.Choice>()) assertTrue(c.key, c.choices.any { it.id == c.default })
        }
    }

    @Test fun handicapOptionsOnlyShowWhenAPlayerIsChosen() {
        val boost = spec.modes.first().options.first { it.key == PropertyTycoonHubSpecs.KEY_HANDICAP_BOOST }
        assertFalse(boost.visibleWhen!!.invoke(HubChoices()))
        assertTrue(boost.visibleWhen!!.invoke(HubChoices().with(PropertyTycoonHubSpecs.KEY_HANDICAP, "2")))
    }

    @Test fun parsersFallBackToSafeDefaults() {
        assertEquals(GameLength.QUICK, PropertyTycoonHubSpecs.parseLength("quick"))
        assertEquals(GameLength.STANDARD, PropertyTycoonHubSpecs.parseLength("???"))
        assertEquals(null, PropertyTycoonHubSpecs.parseStartPearls("auto"))
        assertEquals(1000, PropertyTycoonHubSpecs.parseStartPearls("1000"))
        assertEquals(1, PropertyTycoonHubSpecs.parseHandicapSeat("2", 3))
        assertEquals(-1, PropertyTycoonHubSpecs.parseHandicapSeat("6", 3))
        assertEquals(25, PropertyTycoonHubSpecs.parseHandicapPercent("x"))
    }

    @Test fun launchHelperAppliesTheExtrasToTheGame() {
        val g = PropertyTycoonGame()
        PropertyTycoonLaunch.apply(g, mapOf("length" to "quick", "startPearls" to "1000", "handicap" to "2", "handicapBoost" to "50", "jackpot" to "true"), 3)
        assertEquals(GameLength.QUICK, g.length)
        assertEquals(1000, g.startCashOverride)
        assertEquals(1, g.handicapSeat)
        assertEquals(50, g.handicapPercent)
        assertTrue(g.houseRules.jackpot)
    }

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        val en = PtStringFiles.parseDir("res/values")
        val es = PtStringFiles.parse("res/values-es/property_tycoon_strings.xml")
        val texts = LinkedHashSet<String>()
        texts += spec.title; texts += spec.tagline
        for (m in spec.modes) for (o in m.options) {
            if (o.key in setOf("cpus", "humans", "difficulty", "firstMover")) continue
            texts += o.label; texts += o.help
            if (o is HubOption.Choice) o.choices.forEach { texts += it.label; texts += it.help }
        }
        for (t in texts.filter { it.isNotBlank() }) {
            val key = hubKey(t)
            assertTrue("missing EN $key for '$t'", key in en)
            if (key.startsWith("hub_") && PtStringFiles.parse("res/values/property_tycoon_strings.xml").containsKey(key)) assertTrue("missing ES $key", key in es)
        }
    }

    @Test fun accentColoursMeetContrastAndAreUnique() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(PropertyTycoonHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(PropertyTycoonHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, PropertyTycoonHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, PropertyTycoonHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
        for ((id, a) in GameThemes.accents) assertTrue(id, a.light != PropertyTycoonHubSpecs.ACCENT_LIGHT && a.dark != PropertyTycoonHubSpecs.ACCENT_DARK)
    }

    @Test fun spanishAndEnglishKeySetsMatchAndPlaceholdersAgree() {
        val en = PtStringFiles.parse("res/values/property_tycoon_strings.xml")
        val es = PtStringFiles.parse("res/values-es/property_tycoon_strings.xml")
        assertEquals(en.keys, es.keys)
        val re = Regex("%(\\d+\\$)?[0-9]*[sdf]")
        for ((k, v) in en) assertEquals(k, re.findAll(v).map { it.value }.sorted().toList(), re.findAll(es.getValue(k)).map { it.value }.sorted().toList())
        for (i in 1..12) assertTrue(en.getValue("pt_tut_%02d".format(i)).length < 120)
    }
}
