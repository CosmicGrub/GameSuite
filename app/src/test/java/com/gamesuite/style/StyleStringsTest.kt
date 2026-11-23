package com.gamesuite.style

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EN and ES style strings must define the same keys, and ES must differ from EN (no untranslated copy). */
class StyleStringsTest {
    private fun load(path: String): Map<String, String> {
        val text = File(path).readText()
        return Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en = load("src/main/res/values/style_strings.xml")
    private val es = load("src/main/res/values-es/style_strings.xml")

    @Test fun sameKeysInBothLanguages() {
        assertTrue(en.size >= 30)
        assertEquals(en.keys, es.keys)
    }

    @Test fun placeholdersMatch() {
        val ph = Regex("""%\d\$[sd]""")
        for ((k, v) in en) assertEquals("placeholders of $k", ph.findAll(v).map { it.value }.sorted().toList(), ph.findAll(es.getValue(k)).map { it.value }.sorted().toList())
    }

    @Test fun spanishIsTranslated() {
        val same = en.filter { (k, v) -> es[k] == v && v.any { it.isLetter() } }.keys
        // brand-like or numeric labels may legitimately match
        assertTrue("untranslated: $same", same.all { it == "style_art_25d" || it == "style_art_3d" || it == "style_art_card_desc" })
        assertFalse(es.values.any { it.isBlank() })
    }

    @Test fun everyReasonThatTheUiShowsHasACopy() {
        for (k in listOf("reduced_motion", "battery_saver", "thermal", "assets_missing", "low_ram", "kid_safe", "eco", "unsupported", "health", "quality")) assertTrue(en.containsKey("style_reason_$k"))
    }
}
