package com.gamesuite.ui

import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-game identity: every game has an accent and a glyph, and every accent is legible in both themes. */
class GameThemeTest {

    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private val specIds: List<String> get() = GameHubSpecs.all.map { it.gameId }

    // ---- the WCAG helper itself ------------------------------------------------------------------

    @Test
    fun wcagKnownValues() {
        assertEquals(21.0, Wcag.contrast(0xFF000000, 0xFFFFFFFF), 0.001)
        assertEquals(1.0, Wcag.contrast(0xFF123456, 0xFF123456), 0.0001)
        // #767676 on white is the classic 4.54:1 boundary of AA.
        assertEquals(4.54, Wcag.contrast(0xFF767676, 0xFFFFFFFF), 0.01)
        // Order does not matter.
        assertEquals(Wcag.contrast(0xFF2368C7, 0xFFFFFFFF), Wcag.contrast(0xFFFFFFFF, 0xFF2368C7), 1e-9)
    }

    // ---- coverage ---------------------------------------------------------------------------------

    @Test
    fun everySpecHasAnAccent() {
        val missing = specIds.filter { it !in GameThemes.accents }
        assertTrue("Games with no accent in GameThemes.accents: $missing", missing.isEmpty())
    }

    @Test
    fun everySpecHasAGlyph() {
        // gameGlyph() lives in core/GameCatalog.kt; its fallback is a die, so the check reads the
        // source for an explicit `"id" ->` branch instead of comparing against the fallback.
        val source = moduleFile("java/com/gamesuite/core/GameCatalog.kt").readText()
        val missing = specIds.filter { id -> !Regex("\"" + Regex.escape(id) + "\"\\s*->").containsMatchIn(source) }
        assertTrue("Games with no entry in gameGlyph(): $missing", missing.isEmpty())
    }

    @Test
    fun accentsAreDistinctPerGame() {
        val lights = GameThemes.accents.values.map { it.light }
        assertEquals("two games share the same light accent", lights.size, lights.toSet().size)
        val darks = GameThemes.accents.values.map { it.dark }
        assertEquals("two games share the same dark accent", darks.size, darks.toSet().size)
    }

    @Test
    fun everyFamilyHasAFallback() {
        for (family in HubFamily.values()) {
            assertTrue("no fallback for $family", family in GameThemes.familyFallback)
        }
        // An id nobody has heard of gets its family's colour.
        assertEquals(GameThemes.familyFallback.getValue(HubFamily.CARD), GameThemes.accentFor("brand-new-game", HubFamily.CARD))
        // A known id ignores the family argument.
        assertEquals(GameThemes.accents.getValue("chess"), GameThemes.accentFor("chess", HubFamily.TOOLKIT))
    }

    // ---- contrast ----------------------------------------------------------------------------------

    private fun allPairs(): List<Triple<String, GameAccent, HubFamily?>> =
        GameThemes.accents.map { (id, pair) -> Triple(id, pair, null as HubFamily?) } +
            GameThemes.familyFallback.map { (family, pair) -> Triple("family:$family", pair, family as HubFamily?) }

    @Test
    fun lightAccentsHaveThreeToOneOnEveryLightCardSurface() {
        for ((id, pair, _) in allPairs()) {
            for (surface in GameThemes.lightSurfaces) {
                val ratio = Wcag.contrast(pair.light, surface)
                assertTrue("$id light accent is $ratio:1 on ${surface.toString(16)}", ratio >= GameThemes.MIN_ACCENT_CONTRAST)
            }
        }
    }

    @Test
    fun darkAccentsHaveThreeToOneOnEveryDarkCardSurface() {
        for ((id, pair, _) in allPairs()) {
            for (surface in GameThemes.darkSurfaces) {
                val ratio = Wcag.contrast(pair.dark, surface)
                assertTrue("$id dark accent is $ratio:1 on ${surface.toString(16)}", ratio >= GameThemes.MIN_ACCENT_CONTRAST)
            }
        }
    }

    @Test
    fun playButtonLabelIsReadableOnEveryAccentInBothThemes() {
        for ((id, pair, _) in allPairs()) {
            for (accent in listOf(pair.light, pair.dark)) {
                val label = GameThemes.onAccent(accent)
                val ratio = Wcag.contrast(label, accent)
                assertTrue("$id label on ${accent.toString(16)} is $ratio:1", ratio >= GameThemes.MIN_TEXT_CONTRAST)
            }
        }
    }

    // ---- the runtime nudge ---------------------------------------------------------------------------

    @Test
    fun ensureContrastLeavesAGoodColourAlone() {
        val c = GameThemes.accents.getValue("chess").light
        assertEquals(c, GameThemes.ensureContrast(c, GameThemes.lightSurfaces))
    }

    @Test
    fun ensureContrastRescuesAnAccentOnAnUnexpectedSurface() {
        // The light-theme chess violet on a dark surface is far under 3:1; the nudge lightens it.
        val violet = GameThemes.accents.getValue("chess").light
        val darkSurface = listOf(0xFF000000L)
        assertFalse(Wcag.contrast(violet, darkSurface[0]) >= 3.0)
        val fixed = GameThemes.ensureContrast(violet, darkSurface)
        assertTrue(Wcag.contrast(fixed, darkSurface[0]) >= 3.0)
        // And a pale accent on a white surface is darkened.
        val white = listOf(0xFFFFFFFFL)
        val pale = GameThemes.accents.getValue("lights-out").dark
        val fixedPale = GameThemes.ensureContrast(pale, white)
        assertTrue(Wcag.contrast(fixedPale, white[0]) >= 3.0)
    }

    @Test
    fun ensureContrastWithNoBackgroundsIsANoOp() {
        assertEquals(0xFF123456, GameThemes.ensureContrast(0xFF123456, emptyList()))
    }
}
