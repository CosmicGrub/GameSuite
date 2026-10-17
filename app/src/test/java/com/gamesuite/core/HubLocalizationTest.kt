package com.gamesuite.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Keeps the Game Hub's localization honest without Android. The pure model stays English; the UI
 * (HubText) finds each English catalog string's translation by the resource NAME [hubKey] gives
 * it. This test reads the real values/ and values-es/ strings.xml from disk and proves that every
 * English string reachable from GameHubSpecs has an entry under that name in both locales, that no
 * two different texts share a name, that the Spanish placeholders match the English ones, and that
 * every `R.string.hub_*` / `home_*` / `games_*` the owning screens reference exists in both files.
 */
class HubLocalizationTest {

    // ---- reading the files -------------------------------------------------------------------

    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative (looked in ${here.absolutePath} and ${fromRoot.absolutePath})")
    }

    private class Res(val strings: Map<String, String>, val plurals: Set<String>)

    private fun parse(relative: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val strings = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        val plurals = linkedSetOf<String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> plurals += node.getAttribute("name")
            }
        }
        return Res(strings, plurals)
    }

    private val en: Res by lazy { parse("res/values/strings.xml") }
    private val es: Res by lazy { parse("res/values-es/strings.xml") }

    private val ownedPrefixes = listOf("hub_", "home_", "games_")
    private fun owned(name: String) = ownedPrefixes.any { name.startsWith(it) }

    /** Every English string the Hub can put on screen from the spec table (networked games included). */
    private fun catalogTexts(): Set<String> {
        val out = linkedSetOf<String>()
        fun add(s: String) {
            if (s.isNotBlank()) out += s
        }
        for (spec in GameHubSpecs.buildAll(GameHubSpecs.networkedGames)) {
            add(spec.title)
            add(spec.tagline)
            for (mode in spec.modes) {
                add(mode.title)
                add(mode.blurb)
                for (option in mode.options) {
                    add(option.label)
                    add(option.help)
                    if (option is HubOption.Choice) {
                        for (choice in option.choices) {
                            add(choice.label)
                            add(choice.help)
                        }
                    }
                }
            }
        }
        HUB_SUMMARY_WORDS.forEach(::add)
        return out
    }

    private fun placeholders(s: String): List<String> =
        Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()

    // ---- hubKey ------------------------------------------------------------------------------

    @Test fun hubKeyOfShortTextIsAPlainSlug() {
        assertEquals("hub_chess", hubKey("Chess"))
        assertEquals("hub_tic_tac_toe", hubKey("Tic-Tac-Toe"))
        assertEquals("hub_pass_play", hubKey("Pass & Play"))
        assertEquals("hub_allow_4_on_2", hubKey("Allow +4 on +2"))
        assertEquals("hub_7_0", hubKey("7-0"))
        assertEquals("hub_today_s_puzzle", hubKey("Today's puzzle"))
        assertEquals("hub_200", hubKey("200"))
    }

    @Test fun hubKeyIgnoresCaseAndRepeatedSeparators() {
        assertEquals(hubKey("Free play"), hubKey("  FREE --- play!! "))
    }

    @Test fun hubKeyOfLongTextIsTruncatedWithAStableHash() {
        val text = "The classic. Full rules with castling, en passant and promotion."
        val key = hubKey(text)
        assertEquals(key, hubKey(text))
        val body = key.removePrefix("hub_")
        val slug = body.substringBeforeLast('_')
        val hash = body.substringAfterLast('_')
        assertTrue(slug.length <= 48)
        assertFalse(slug.endsWith("_"))
        assertEquals(6, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(slug.startsWith("the_classic_full_rules_with_castling"))
    }

    @Test fun longTextsWithTheSamePrefixGetDifferentKeys() {
        val a = "x".repeat(60) + " one"
        val b = "x".repeat(60) + " two"
        assertNotEquals(hubKey(a), hubKey(b))
    }

    @Test fun textWithoutLettersOrDigitsStillGetsAUniqueKey() {
        val plus = hubKey("+")
        val minus = hubKey("-")
        assertTrue(plus.startsWith("hub_x_"))
        assertNotEquals(plus, minus)
    }

    @Test fun hubKeysAreValidResourceNames() {
        for (text in catalogTexts()) {
            val key = hubKey(text)
            assertTrue("bad resource name for '$text': $key", Regex("[a-z][a-z0-9_]*").matches(key))
        }
    }

    // ---- catalog coverage --------------------------------------------------------------------

    @Test fun everyCatalogStringHasAnEnglishEntry() {
        val missing = catalogTexts().filter { hubKey(it) !in en.strings }
        assertTrue("missing from values/strings.xml: $missing", missing.isEmpty())
    }

    @Test fun everyCatalogStringHasASpanishEntry() {
        val missing = catalogTexts().filter { hubKey(it) !in es.strings }
        assertTrue("missing from values-es/strings.xml: $missing", missing.isEmpty())
    }

    @Test fun englishEntriesRepeatTheCatalogText() {
        // The English file is the source of truth for fallback text: it must say what the model says.
        val wrong = catalogTexts().filter { text ->
            val entry = en.strings[hubKey(text)] ?: return@filter false
            entry.replace("\\'", "'") != text
        }
        assertTrue("English entry differs from the catalog text for: $wrong", wrong.isEmpty())
    }

    @Test fun spanishEntriesAreNotBlank() {
        val blank = catalogTexts().filter { es.strings[hubKey(it)]?.isBlank() == true }
        assertTrue("blank Spanish entries: $blank", blank.isEmpty())
    }

    @Test fun differentTextsNeverShareAKey() {
        val byKey = catalogTexts().groupBy { hubKey(it) }
        val clashes = byKey.filterValues { it.size > 1 }
        assertTrue("key collisions: $clashes", clashes.isEmpty())
    }

    @Test fun catalogKeysNeverTakeTheScreenLiteralNamespace() {
        // Hand-named screen strings are hub_ui_*; a generated key must not shadow one.
        val clashes = catalogTexts().filter { hubKey(it).startsWith("hub_ui_") }
        assertTrue("catalog text mapped into hub_ui_: $clashes", clashes.isEmpty())
    }

    @Test fun summaryWordsAreCatalogText() {
        HUB_SUMMARY_WORDS.forEach { word ->
            assertTrue("no entry for summary word '$word'", hubKey(word) in en.strings && hubKey(word) in es.strings)
        }
    }

    // ---- the two files agree -----------------------------------------------------------------

    @Test fun everyOwnedEnglishEntryHasASpanishOne() {
        val missing = en.strings.keys.filter { owned(it) && it !in es.strings }
        assertTrue("owned entries missing from values-es: $missing", missing.isEmpty())
        val missingPlurals = en.plurals.filter { owned(it) && it !in es.plurals }
        assertTrue("owned plurals missing from values-es: $missingPlurals", missingPlurals.isEmpty())
    }

    @Test fun spanishPlaceholdersMatchEnglish() {
        val bad = es.strings.filter { (name, value) ->
            owned(name) && en.strings[name]?.let { placeholders(it) != placeholders(value) } == true
        }.keys
        assertTrue("placeholder mismatch in values-es: $bad", bad.isEmpty())
    }

    @Test fun spanishDiffersFromEnglishForTheTranslatableUiStrings() {
        // Proper names and numbers may legitimately match; a sentence that does not is untranslated.
        val untranslated = en.strings.filter { (name, value) ->
            name.startsWith("hub_ui_") && es.strings[name] == value && value.length > 12
        }.keys
        assertTrue("hub_ui_ strings left in English: $untranslated", untranslated.isEmpty())
    }

    @Test fun noOwnedEntryUsesAnUnescapedApostrophe() {
        // aapt2 rejects a bare ' in a string resource: it must be written \' (or be inside quotes).
        val bad = (en.strings + es.strings.mapKeys { "es:" + it.key }).filter { (name, value) ->
            owned(name.removePrefix("es:")) && Regex("(?<!\\\\)'").containsMatchIn(value)
        }.keys
        assertTrue("unescaped apostrophe in: $bad", bad.isEmpty())
    }

    // ---- the screens reference only strings that exist ---------------------------------------

    @Test fun everyResourceTheOwnedScreensUseExists() {
        val files = listOf(
            "ui/GameHubScreen.kt", "ui/GamesScreen.kt", "ui/GameCards.kt", "ui/MainMenuScreen.kt", "ui/HubText.kt"
        )
        val stringRef = Regex("R\\.string\\.((?:hub|home|games)_[a-z0-9_]+)")
        val pluralRef = Regex("R\\.plurals\\.((?:hub|home|games)_[a-z0-9_]+)")
        val problems = mutableListOf<String>()
        for (relative in files) {
            val source = moduleFile("java/com/gamesuite/$relative").readText()
            for (m in stringRef.findAll(source)) {
                val name = m.groupValues[1]
                if (name !in en.strings) problems += "$relative: R.string.$name missing from values"
                if (name !in es.strings) problems += "$relative: R.string.$name missing from values-es"
            }
            for (m in pluralRef.findAll(source)) {
                val name = m.groupValues[1]
                if (name !in en.plurals) problems += "$relative: R.plurals.$name missing from values"
                if (name !in es.plurals) problems += "$relative: R.plurals.$name missing from values-es"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
