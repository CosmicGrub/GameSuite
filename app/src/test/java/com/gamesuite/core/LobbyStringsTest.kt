package com.gamesuite.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The wave-5 lobby and session-health strings (new files, EN + ES) stay complete and consistent: same names in both
 * languages, the same format placeholders, every plural has its `other` form, nothing left empty, and every
 * `R.string.ls_*` / `sh_*` / `R.plurals.ls_*` the Kotlin sources reference exists.
 */
class LobbyStringsTest {

    private fun moduleFile(relative: String): File {
        listOf("src/main/$relative", "app/src/main/$relative").map { File(it) }.firstOrNull { it.exists() }?.let { return it }
        error("Cannot find $relative from ${File(".").absolutePath}")
    }

    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(relative: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val children = node.getElementsByTagName("item")
                    for (j in 0 until children.length) {
                        val item = children.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[node.getAttribute("name")] = items
                }
            }
        }
        return Res(strings, plurals)
    }

    private val files = listOf("lobby_seats_strings.xml", "session_health_strings.xml")

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+)\$[ds]""").findAll(text).map { it.groupValues[1] + it.value.last() }.sorted().toList()

    @Test
    fun `English and Spanish define the same names with the same placeholders`() {
        for (f in files) {
            val en = parse("res/values/$f")
            val es = parse("res/values-es/$f")
            assertEquals("$f string names", en.strings.keys, es.strings.keys)
            assertEquals("$f plural names", en.plurals.keys, es.plurals.keys)
            for ((name, text) in en.strings) {
                assertTrue("$f $name is empty in English", text.isNotBlank())
                assertTrue("$f $name is empty in Spanish", es.strings.getValue(name).isNotBlank())
                assertEquals("$f $name placeholders", placeholders(text), placeholders(es.strings.getValue(name)))
            }
            for ((name, items) in en.plurals) {
                assertTrue("$f plural $name needs other (en)", "other" in items)
                assertTrue("$f plural $name needs other (es)", "other" in es.plurals.getValue(name))
                // The count placeholder is mandatory in the general form; "one" may spell the number out.
                assertEquals("$f plural $name other placeholders", placeholders(items.getValue("other")), placeholders(es.plurals.getValue(name).getValue("other")))
            }
        }
    }

    @Test
    fun `Spanish is actually translated, not a copy of the English`() {
        val untranslatable = setOf("sh_ok") // "OK" vs "Aceptar" differ, but keep the guard general below
        for (f in files) {
            val en = parse("res/values/$f")
            val es = parse("res/values-es/$f")
            for ((name, text) in en.strings) {
                if (name in untranslatable) continue
                assertFalse("$f $name is identical in Spanish", text == es.strings.getValue(name))
            }
        }
    }

    @Test
    fun `every lobby and session string the code references exists`() {
        val declared = files.flatMap { f -> parse("res/values/$f").let { it.strings.keys + it.plurals.keys } }.toSet()
        val roots = listOf("java/com/gamesuite/ui", "java/com/gamesuite/uikit").map { moduleFile(it) }
        val refs = roots.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { Regex("""R\.(?:string|plurals)\.((?:ls|sh)_[a-z0-9_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        assertTrue("expected the lobby screens to use the new strings", refs.size > 10)
        assertEquals("referenced but not declared", emptySet<String>(), refs - declared)
        // And nothing declared goes unused, so a rename cannot leave dead strings behind.
        assertEquals("declared but never referenced", emptySet<String>(), declared - refs)
    }
}
