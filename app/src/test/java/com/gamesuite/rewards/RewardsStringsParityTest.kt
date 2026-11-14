package com.gamesuite.rewards

import com.gamesuite.core.Skill
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Reads the real values/ and values-es/ files: every rewards_* and prof_* key exists in both with the same placeholders. */
class RewardsStringsParityTest {
    private fun file(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val root = File("app/src/main/$relative")
        if (root.exists()) return root
        error("Cannot find $relative")
    }

    /** name -> list of item texts (a plain string has one item; plurals and arrays have several). */
    private fun parse(relative: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(relative))
        val out = linkedMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> out[name] = listOf(e.textContent)
                "plurals", "string-array" -> {
                    val items = e.getElementsByTagName("item")
                    out[name] = (0 until items.length).map { items.item(it).textContent }
                }
            }
        }
        return out
    }

    private val placeholder = Regex("%\\d+\\$[sd]")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    private fun checkParity(fileName: String) {
        val en = parse("res/values/$fileName")
        val es = parse("res/values-es/$fileName")
        assertEquals("$fileName: key sets differ", en.keys, es.keys)
        en.forEach { (k, enItems) ->
            val esItems = es.getValue(k)
            assertTrue("$k: Spanish is empty", esItems.isNotEmpty() && esItems.all { it.isNotBlank() })
            if (enItems.size == 1 && esItems.size == 1) {
                assertEquals("$k: placeholders differ", placeholders(enItems[0]), placeholders(esItems[0]))
            }
        }
    }

    @Test fun `rewards strings have the same keys and placeholders in English and Spanish`() = checkParity("rewards_strings.xml")

    @Test fun `profile strings have the same keys and placeholders in English and Spanish`() = checkParity("profiles_strings.xml")

    @Test fun `profile strings are translated not copied`() {
        val en = parse("res/values/profiles_strings.xml")
        val es = parse("res/values-es/profiles_strings.xml")
        val allowedSame = setOf("prof_gate_pin_label", "prof_gate_method_pin", "prof_dash_minutes")
        val copied = en.keys.filter { it !in allowedSame && en[it] == es[it] }
        assertTrue("untranslated: $copied", copied.isEmpty())
    }

    @Test fun `every sticker has a name in both languages`() {
        val en = parse("res/values/rewards_strings.xml")
        val es = parse("res/values-es/rewards_strings.xml")
        StickerCatalog.all.forEach {
            assertTrue("rw_st_${it.id} missing (en)", en.containsKey("rw_st_${it.id}"))
            assertTrue("rw_st_${it.id} missing (es)", es.containsKey("rw_st_${it.id}"))
        }
        StickerSet.values().forEach { assertTrue(en.containsKey("rw_set_${it.id}") && es.containsKey("rw_set_${it.id}")) }
    }

    @Test fun `every reason and skill has strings in both languages`() {
        val en = parse("res/values/rewards_strings.xml")
        val es = parse("res/values-es/rewards_strings.xml")
        RewardReason.values().forEach { assertTrue(it.id, en.containsKey("rw_reason_${it.id}") && es.containsKey("rw_reason_${it.id}")) }
        Skill.values().forEach {
            listOf("rw_skill_${it.id}", "rw_skill_${it.id}_line").forEach { k ->
                assertTrue(k, en.containsKey(k) && es.containsKey(k))
            }
        }
    }

    @Test fun `spanish plurals have the categories the language needs`() {
        val es = parse("res/values-es/rewards_strings.xml")
        listOf("rw_goal_games", "rw_goal_streak").forEach { assertTrue(it, es.getValue(it).size >= 2) }
    }

    @Test fun `digit word arrays have ten words in both languages`() {
        assertEquals(10, parse("res/values/profiles_strings.xml").getValue("prof_gate_digit_words").size)
        assertEquals(10, parse("res/values-es/profiles_strings.xml").getValue("prof_gate_digit_words").size)
    }
}
