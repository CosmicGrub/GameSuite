package com.gamesuite.games.ludo

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubOption
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.games.chinesecheckers.CCBoard
import com.gamesuite.games.chinesecheckers.CCPieceCounts
import com.gamesuite.core.PlayMode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The Hub specs of the two games, their launches, and the EN + ES strings they need (HubLocalizationTest's rules). */
class ChineseLudoHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseDir(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") {
                    out[e.getAttribute("name")] = e.textContent
                }
            }
        }
        return out
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }

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

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in ChineseLudoHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, en[key]!!.replace("\\'", "'"))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        // the shared words used inside the options are already translated
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = ChineseLudoHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun everyGameStringExistsInBothLocalesWithMatchingPlaceholders() {
        val mine = en.keys.filter { it.startsWith("cc_") || it.startsWith("ccl_") || it.startsWith("ludo_") || it.startsWith("tip_chinese") || it.startsWith("tip_ludo") }
        assertTrue(mine.size > 60)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        assertEquals(emptySet<String>(), es.keys.filter { it.startsWith("cc_") || it.startsWith("ccl_") || it.startsWith("ludo_") }.toSet() - en.keys)
    }

    @Test fun everyStringTheScreensReferenceExists() {
        val src = listOf("ChineseCheckersScreen.kt", "LudoScreen.kt", "ChineseLudoUi.kt").joinToString("\n") {
            moduleFile("java/com/gamesuite/ui/$it").readText()
        }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 50)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
    }

    @Test fun aposrophesAreEscapedSoTheResourceCompilerAccepts() {
        val raw = listOf("values", "values-es").flatMap { d ->
            File(moduleFile("res/$d").path).listFiles { f -> f.name.startsWith("chinese_ludo") }!!.map { it.readText() }
        }
        raw.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                val t = m.groupValues[1]
                assertFalse("unescaped apostrophe in: $t", Regex("(?<!\\\\)'").containsMatchIn(t))
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

    @Test fun chineseCheckersLaunchesEveryTableSizeAgainstComputers() {
        for (cpus in 1..5) {
            val l = local(ChineseLudoHubSpecs.chineseCheckers, "cpu", "cpus" to cpus.toString())
            assertEquals(cpus + 1, l.launch.players.size)
            assertTrue(CCBoard.validSeatCount(l.launch.players.size))
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
            assertEquals("chinese-checkers", l.route)
        }
    }

    @Test fun chineseCheckersPassAndPlayFillsEmptySeatsWithComputersWithinSixSeats() {
        val l = local(ChineseLudoHubSpecs.chineseCheckers, "pass", "humans" to "3", "cpus" to "3")
        assertEquals(6, l.launch.players.size)
        assertEquals(3, l.launch.players.count { it.isBot })
        val big = local(ChineseLudoHubSpecs.chineseCheckers, "pass", "humans" to "6", "cpus" to "4")
        assertEquals(6, big.launch.players.size)
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, big.launch.mode)
    }

    @Test fun piecesOptionReachesTheGame() {
        val short = local(ChineseLudoHubSpecs.chineseCheckers, "cpu", "pieces" to "short")
        assertEquals(6, CCPieceCounts.fromOptionId(short.launch.options.extras["pieces"]))
        val std = local(ChineseLudoHubSpecs.chineseCheckers, "cpu")
        assertEquals(10, CCPieceCounts.fromOptionId(std.launch.options.extras["pieces"]))
        assertEquals(10, CCPieceCounts.fromOptionId(null))
    }

    @Test fun ludoSeatCountsStayWithinTwoToFourAndRuleTogglesReachTheGame() {
        for (cpus in 1..3) assertEquals(cpus + 1, local(ChineseLudoHubSpecs.ludo, "cpu", "cpus" to cpus.toString()).launch.players.size)
        val l = local(ChineseLudoHubSpecs.ludo, "pass", "humans" to "4", "cpus" to "2", "needSix" to "false", "exactFinish" to "false")
        assertEquals(4, l.launch.players.size)
        assertEquals("false", l.launch.options.extras["needSix"])
        assertEquals("false", l.launch.options.extras["exactFinish"])
        val d = local(ChineseLudoHubSpecs.ludo, "cpu")
        assertEquals("true", d.launch.options.extras["needSix"])
        assertEquals("true", d.launch.options.extras["exactFinish"])
        assertEquals("ludo", d.route)
    }

    @Test fun firstMoverAndDifficultyAreHonoured() {
        val l = local(ChineseLudoHubSpecs.ludo, "cpu", "first" to "opponent", "difficulty" to "HARD")
        assertEquals(1, l.launch.options.firstMoverIndex)
        assertEquals(com.gamesuite.settings.CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
    }

    @Test fun bothSpecsDeclareBoardFamilyAndSeatRanges() {
        assertEquals(6, ChineseLudoHubSpecs.chineseCheckers.modes.maxOf { it.maxSeats })
        assertEquals(4, ChineseLudoHubSpecs.ludo.modes.maxOf { it.maxSeats })
        assertTrue(ChineseLudoHubSpecs.all.all { it.family == com.gamesuite.core.HubFamily.BOARD && it.resumable })
    }
}
