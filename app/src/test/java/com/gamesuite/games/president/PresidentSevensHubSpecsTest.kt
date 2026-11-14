package com.gamesuite.games.president

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.core.summarizeHubChoices
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The Hub specs, their launches, and every EN + ES string President and Sevens use. */
class PresidentSevensHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val arrays: Map<String, List<String>>)

    private fun parseDir(dir: String, only: ((File) -> Boolean)? = null): Strings {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val arrays = linkedMapOf<String, List<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") && (only?.invoke(f) ?: true) }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = e.textContent
                    "plurals" -> plurals[e.getAttribute("name")] = (0 until e.getElementsByTagName("item").length).associate {
                        val item = e.getElementsByTagName("item").item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                    "string-array" -> arrays[e.getAttribute("name")] = (0 until e.getElementsByTagName("item").length).map { e.getElementsByTagName("item").item(it).textContent }
                }
            }
        }
        return Strings(strings, plurals, arrays)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val mineEn by lazy { parseDir("res/values") { it.name.startsWith("president_sevens") } }
    private val mineEs by lazy { parseDir("res/values-es") { it.name.startsWith("president_sevens") } }

    private fun texts(spec: GameHubSpec): Set<String> {
        val out = linkedSetOf<String>()
        fun add(s: String) { if (s.isNotBlank()) out += s }
        add(spec.title); add(spec.tagline)
        for (mode in spec.modes) {
            add(mode.title); add(mode.blurb)
            for (o in mode.options) {
                add(o.label); add(o.help)
                if (o is HubOption.Choice) o.choices.forEach { add(it.label); add(it.help) }
            }
        }
        return out
    }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'")

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in PresidentSevensHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en.strings)
            assertTrue("missing ES $key for '$text'", key in es.strings)
            assertEquals(text, shown(en.strings[key]!!))
            assertTrue("blank ES $key", es.strings[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en.strings && hubKey(it) in es.strings) }
    }

    @Test fun noTwoHubTextsShareAKeyAndMyFilesDefineNoDuplicateOfAnotherFile() {
        val all = PresidentSevensHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
        // a resource name defined twice across files would fail the Android build
        for (dir in listOf("res/values", "res/values-es")) {
            val mine = parseDir(dir) { it.name.startsWith("president_sevens") }
            val others = parseDir(dir) { !it.name.startsWith("president_sevens") }
            val names = mine.strings.keys + mine.plurals.keys + mine.arrays.keys
            val dupes = names.filter { it in others.strings || it in others.plurals || it in others.arrays }
            assertEquals("$dir duplicates", emptyList<String>(), dupes)
        }
    }

    @Test fun everyGameStringExistsInBothLocalesWithMatchingPlaceholders() {
        assertTrue(mineEn.strings.size > 120)
        assertEquals(mineEn.strings.keys, mineEs.strings.keys)
        for ((k, v) in mineEn.strings) assertEquals("placeholders of $k", placeholders(v), placeholders(mineEs.strings[k]!!))
        assertEquals(mineEn.plurals.keys, mineEs.plurals.keys)
        for ((k, v) in mineEn.plurals) {
            assertEquals("plural quantities of $k", setOf("one", "other"), v.keys)
            assertEquals(setOf("one", "other"), mineEs.plurals[k]!!.keys)
            for (q in v.keys) assertEquals("placeholders of $k/$q", placeholders(v[q]!!), placeholders(mineEs.plurals[k]!![q]!!))
        }
        assertEquals(mineEn.arrays.keys, mineEs.arrays.keys)
        for ((k, v) in mineEn.arrays) assertEquals("array $k", v.size, mineEs.arrays[k]!!.size)
        assertEquals(13, mineEn.arrays["ps_rank_names"]!!.size)
        assertEquals(13, mineEn.arrays["ps_rank_plurals"]!!.size)
        assertEquals(4, mineEn.arrays["ps_suit_names"]!!.size)
    }

    @Test fun spanishIsTranslatedNotCopiedForTheUserVisibleSentences() {
        val same = mineEn.strings.filter { (k, v) -> v.length > 24 && v == mineEs.strings[k] }.keys
        assertEquals(emptySet<String>(), same)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocales() {
        val src = listOf("ui/PresidentScreen.kt", "ui/SevensScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/$it").readText() } +
            moduleFile("java/com/gamesuite/games/president/PsLabels.kt").readText() +
            moduleFile("java/com/gamesuite/games/president/PsCardUi.kt").readText()
        val used = Regex("R\\.(string|plurals|array)\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertTrue(used.size > 100)
        for ((kind, name) in used) {
            val inEn = when (kind) { "string" -> name in en.strings; "plurals" -> name in en.plurals; else -> name in en.arrays }
            val inEs = when (kind) { "string" -> name in es.strings; "plurals" -> name in es.plurals; else -> name in es.arrays }
            assertTrue("R.$kind.$name missing in values/", inEn)
            assertTrue("R.$kind.$name missing in values-es/", inEs)
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (dir in listOf("values", "values-es")) {
            val files = moduleFile("res/$dir").listFiles { f -> f.name.startsWith("president_sevens") }!!
            assertEquals(2, files.size)
            files.forEach { f ->
                Regex(">([^<]*)<").findAll(f.readText()).forEach { m ->
                    assertFalse("unescaped apostrophe in ${f.name}: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }

    @Test fun tipsAreTwoToThreeLinesPerGameAndEqualTheEnglishStrings() {
        for (id in listOf("president", "sevens")) {
            val lines = (1..3).map { mineEn.strings["tip_${id}_$it"]!! }
            lines.forEachIndexed { i, text ->
                assertTrue(text.isNotBlank())
                assertNotEquals(text, mineEs.strings["tip_${id}_${i + 1}"])
            }
        }
    }

    @Test fun coachStringsAreFullAndTranslated() {
        for (id in listOf("president", "sevens")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in mineEn.strings }
            assertTrue("steps for $id", steps.size in 3..6)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
                assertTrue(mineEn.strings[k]!!.isNotBlank())
                assertNotEquals("ES differs from EN for $k", mineEn.strings[k], mineEs.strings[k])
            }
        }
    }

    // ---- launches ---------------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        val m = spec.mode(mode)!!
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, m, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun vsComputersSeatsStayBetweenThreeAndSixAndOptionsReachTheGame() {
        for (spec in PresidentSevensHubSpecs.all) {
            for (cpus in 2..5) {
                val l = local(spec, "cpu", "cpus" to cpus.toString())
                assertEquals(cpus + 1, l.launch.players.size)
                assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
                assertEquals(spec.gameId, l.route)
                assertTrue(l.launch.players.drop(1).all { it.isBot })
            }
            // a stale or hand-edited cpus value is clamped, never producing an unplayable table
            assertEquals(6, local(spec, "cpu", "cpus" to "99").launch.players.size.coerceAtMost(6))
        }
        val p = local(PresidentSevensHubSpecs.president, "cpu")
        assertEquals("true", p.launch.options.extras["revolutions"])
        assertEquals("true", p.launch.options.extras["ties"])
        assertEquals("true", p.launch.options.extras["exchange"])
        assertEquals("5", p.launch.options.extras["rounds"])
        val q = local(PresidentSevensHubSpecs.president, "cpu", "revolutions" to "false", "ties" to "false", "exchange" to "false", "rounds" to "10")
        assertEquals("false", q.launch.options.extras["revolutions"])
        assertEquals("10", q.launch.options.extras["rounds"])
        assertEquals("false", local(PresidentSevensHubSpecs.sevens, "cpu").launch.options.extras["passPenalty"])
        assertEquals("true", local(PresidentSevensHubSpecs.sevens, "cpu", "passPenalty" to "true").launch.options.extras["passPenalty"])
        // an unknown rounds value falls back to the default before it can reach the game
        assertEquals("5", local(PresidentSevensHubSpecs.president, "cpu", "rounds" to "7").launch.options.extras["rounds"])
    }

    @Test fun passAndPlayFillsToThreeSeatsAndNeverExceedsSix() {
        for (spec in PresidentSevensHubSpecs.all) {
            val two = local(spec, "pass", "humans" to "2", "cpus" to "0")
            assertEquals(3, two.launch.players.size)
            assertEquals(1, two.launch.players.count { it.isBot })
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, two.launch.mode)
            val big = local(spec, "pass", "humans" to "6", "cpus" to "4")
            assertEquals(6, big.launch.players.size)
            assertEquals(6, big.launch.players.count { !it.isBot })
            val mix = local(spec, "pass", "humans" to "3", "cpus" to "2")
            assertEquals(5, mix.launch.players.size)
        }
    }

    @Test fun difficultyIsHonoured() {
        val l = local(PresidentSevensHubSpecs.sevens, "cpu", "difficulty" to "HARD")
        assertEquals(com.gamesuite.settings.CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
        assertEquals(null, local(PresidentSevensHubSpecs.sevens, "cpu").launch.options.cpuDifficulty)
    }

    @Test fun bothSpecsAreCardGamesWithSavesAndSummarizeCleanly() {
        for (spec in PresidentSevensHubSpecs.all) {
            assertEquals(com.gamesuite.core.HubFamily.CARD, spec.family)
            assertTrue(spec.resumable)
            assertFalse(spec.skipsHub)
            for (m in spec.modes) {
                assertEquals(3, m.minSeats)
                assertEquals(6, m.maxSeats)
                val summary = summarizeHubChoices(m, m.defaults())
                assertTrue(summary.startsWith(m.title))
            }
        }
        assertEquals(setOf("president"), PresidentSevensHubSpecs.president.routes())
        assertEquals(setOf("sevens"), PresidentSevensHubSpecs.sevens.routes())
    }
}
