package com.gamesuite.games.speed

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.games.matching.MatchingRules
import com.gamesuite.settings.CpuDifficulty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** Hub specs, launches, and the EN + ES text of Speed and Matching (HubLocalizationTest's rules, applied to our own specs). */
class SpeedMatchingHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private class Res(val strings: Map<String, String>, val arrays: Map<String, List<String>>)

    private fun parseDir(dir: String): Res {
        val strings = linkedMapOf<String, String>()
        val arrays = linkedMapOf<String, List<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = e.textContent
                    "string-array" -> {
                        val items = e.getElementsByTagName("item")
                        arrays[e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
                    }
                }
            }
        }
        return Res(strings, arrays)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val specs = SpeedMatchingHubSpecs.all

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

    // ---- localization ----

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in specs) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en.strings)
            assertTrue("missing ES $key for '$text'", key in es.strings)
            assertEquals(text, shown(en.strings[key]!!))
            assertTrue("blank ES $key", es.strings[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en.strings && hubKey(it) in es.strings) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholders() {
        val mine = en.strings.keys.filter { it.startsWith("speed_") || it.startsWith("match_") || it.startsWith("tip_speed") || it.startsWith("tip_matching") }
        assertTrue(mine.size > 90)
        for (k in mine) {
            assertTrue("no ES for $k", k in es.strings)
            assertEquals("placeholders of $k", placeholders(en.strings[k]!!), placeholders(es.strings[k]!!))
        }
        val mineEs = es.strings.keys.filter { it.startsWith("speed_") || it.startsWith("match_") }
        assertEquals(emptySet<String>(), mineEs.toSet() - en.strings.keys)
        for ((k, v) in en.arrays.filterKeys { it.startsWith("speed_") || it.startsWith("match_") }) {
            assertEquals("array $k size", v.size, es.arrays[k]?.size)
        }
    }

    @Test fun suitAndRankArraysHaveTheRightLengths() {
        assertEquals(13, en.arrays.getValue("speed_rank_names").size)
        assertEquals(4, en.arrays.getValue("speed_suit_names").size)
        assertEquals(13, en.arrays.getValue("match_rank_names").size)
        assertEquals(5, en.arrays.getValue("match_species_names").size)
        assertEquals(4, en.arrays.getValue("match_coat_names").size)
        assertEquals(10, en.arrays.getValue("match_shape_names").size)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocales() {
        val src = listOf("SpeedScreen.kt", "MatchingScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        val arrays = Regex("R\\.array\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en.strings)
            assertTrue("R.string.$name missing in values-es/", name in es.strings)
        }
        for (name in arrays) {
            assertTrue("R.array.$name missing in values/", name in en.arrays)
            assertTrue("R.array.$name missing in values-es/", name in es.arrays)
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        listOf("values", "values-es").flatMap { d ->
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("speed_matching") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAndCoachTextExistInBothLocalesAndSpanishIsTranslated() {
        for (id in listOf("speed", "matching")) {
            for (n in 1..3) {
                val k = "tip_${id}_$n"
                assertTrue(k in en.strings && k in es.strings)
                assertTrue("tip too long: $k", shown(en.strings[k]!!).length <= 140)
                assertTrue("$k not translated", en.strings[k] != es.strings[k])
            }
            val names = (1..4).map { "coach_${id}_s$it" } + "coach_${id}_fact"
            for (k in names) {
                assertTrue("coach $k", en.strings[k]?.isNotBlank() == true && es.strings[k]?.isNotBlank() == true)
                assertTrue("$k not translated", en.strings[k] != es.strings[k])
            }
        }
    }

    // ---- specs and launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothAreCardGamesWithTheRightSeatRanges() {
        assertTrue(specs.all { it.family == HubFamily.CARD && it.resumable })
        assertEquals(setOf("speed", "matching"), specs.map { it.gameId }.toSet())
        assertEquals(2, SpeedMatchingHubSpecs.speed.modes.maxOf { it.maxSeats })
        assertEquals(1, SpeedMatchingHubSpecs.matching.modes.minOf { it.minSeats })
        assertEquals(4, SpeedMatchingHubSpecs.matching.modes.maxOf { it.maxSeats })
        assertTrue(specs.all { s -> s.modes.all { it.route == s.gameId } })
    }

    @Test fun speedLaunchesAgainstTheComputerAndWithTheAssistOption() {
        val l = local(SpeedMatchingHubSpecs.speed, "cpu", "difficulty" to "HARD", "assist" to "true")
        assertEquals(2, l.launch.players.size)
        assertTrue(l.launch.players[1].isBot)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
        assertEquals("true", l.launch.options.extras["assist"])
        assertEquals("false", local(SpeedMatchingHubSpecs.speed, "cpu").launch.options.extras["assist"])
        val p = local(SpeedMatchingHubSpecs.speed, "pass")
        assertEquals(2, p.launch.players.size)
        assertTrue(p.launch.players.none { it.isBot })
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode)
    }

    @Test fun matchingLaunchesSoloAndWithUpToThreeComputers() {
        val solo = local(SpeedMatchingHubSpecs.matching, "solo", "cards" to "40", "theme" to "cards")
        assertEquals(1, solo.launch.players.size)
        assertEquals("40", solo.launch.options.extras["cards"])
        assertEquals(40, MatchingRules.countFromOptionId(solo.launch.options.extras["cards"]))
        assertEquals("cards", solo.launch.options.extras["theme"])
        for (cpus in 1..3) {
            val l = local(SpeedMatchingHubSpecs.matching, "cpu", "cpus" to cpus.toString())
            assertEquals(cpus + 1, l.launch.players.size)
            assertEquals("20", l.launch.options.extras["cards"])
        }
        val pass = local(SpeedMatchingHubSpecs.matching, "pass", "humans" to "4", "cpus" to "2")
        assertEquals(4, pass.launch.players.size) // trimmed to the four seats
        assertTrue(pass.launch.players.none { it.isBot })
    }

    @Test fun everyCardCountChoiceIsAValidBoard() {
        val cards = SpeedMatchingHubSpecs.matching.mode("solo")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "cards" }
        assertEquals(MatchingRules.CARD_COUNTS.map { it.toString() }, cards.choices.map { it.id })
        assertEquals("20", cards.default)
        val kinds = SpeedMatchingHubSpecs.matching.modes.map { it.kind }
        assertTrue(HubModeKind.SOLO in kinds && HubModeKind.VS_CPU in kinds && HubModeKind.PASS_AND_PLAY in kinds)
    }
}
