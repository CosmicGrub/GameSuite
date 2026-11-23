package com.gamesuite.games.darts

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.settings.CpuDifficulty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** Hub specs, launches, tips, coach cards and every string the two screens use, in EN and ES. */
class DartsBowlingHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseDir(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private fun shown(raw: String) = raw.replace("\\'", "'")
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()

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

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in DartsBowlingHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
            assertEquals("placeholders of $key", placeholders(en[key]!!), placeholders(es[key]!!))
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = DartsBowlingHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun everyOwnStringExistsInBothLocalesWithMatchingPlaceholders() {
        val mine = en.keys.filter { it.startsWith("dt_") || it.startsWith("bw_") || it.startsWith("tip_darts") || it.startsWith("tip_bowling") || it.startsWith("coach_darts") || it.startsWith("coach_bowling") }
        assertTrue(mine.size > 100)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertTrue("blank ES $k", es[k]!!.isNotBlank())
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        val esOnly = es.keys.filter { it.startsWith("dt_") || it.startsWith("bw_") || it.startsWith("coach_darts") || it.startsWith("coach_bowling") }.toSet() - en.keys
        assertEquals(emptySet<String>(), esOnly)
    }

    @Test fun everyStringTheScreensReferenceExists() {
        val src = listOf("DartsScreen.kt", "BowlingScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 80)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
    }

    @Test fun noHardCodedUserFacingTextInTheScreens() {
        // user-visible text goes through stringResource; the only literals allowed are glyphs and codes
        val src = listOf("DartsScreen.kt", "BowlingScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val literal = Regex("Text\\(\"([A-Za-z][A-Za-z ]{3,})\"")
        assertEquals(emptyList<String>(), literal.findAll(src).map { it.groupValues[1] }.toList())
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) {
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("darts") || f.name.startsWith("bowling") }!!.forEach { f ->
                Regex(">([^<]*)<").findAll(f.readText()).forEach { m ->
                    assertFalse("unescaped apostrophe in ${f.name}: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }

    @Test fun tipsAreThreeShortLinesEqualToTheirStringResources() {
        for ((id, lines) in DartsBowlingHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                assertTrue(line.length <= 140)
                val key = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[key]!!))
                assertTrue(es[key]!!.isNotBlank())
            }
        }
    }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLocales() {
        for (id in listOf("darts", "bowling")) {
            val steps = (1..6).filter { "coach_${id}_s$it" in en }
            assertTrue("$id steps $steps", steps.size in 3..6 && steps == (1..steps.size).toList())
            for (n in steps) assertTrue("coach_${id}_s$n ES", "coach_${id}_s$n" in es)
            assertTrue("coach_${id}_fact" in en && "coach_${id}_fact" in es)
        }
    }

    // ---- launches ----------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothSpecsOfferPracticeComputersAndPassAndPlay() {
        for (spec in DartsBowlingHubSpecs.all) {
            assertEquals(listOf(HubModeKind.SOLO, HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind })
            assertTrue(spec.resumable)
            assertEquals(4, spec.modes.maxOf { it.maxSeats })
        }
    }

    @Test fun practiceSeatsOnePlayerAndComputersFillTheTable() {
        val solo = local(DartsBowlingHubSpecs.darts, "practice")
        assertEquals(1, solo.launch.players.size)
        assertEquals("darts", solo.route)
        for (cpus in 1..3) {
            val l = local(DartsBowlingHubSpecs.bowling, "cpu", "cpus" to cpus.toString())
            assertEquals(cpus + 1, l.launch.players.size)
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        }
        val pass = local(DartsBowlingHubSpecs.darts, "pass", "humans" to "4", "cpus" to "2")
        assertEquals(4, pass.launch.players.size)
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode)
    }

    @Test fun optionsReachTheGames() {
        val d = local(DartsBowlingHubSpecs.darts, "cpu", "game" to "cricket", "assist" to "shaky", "difficulty" to "HARD", "first" to "opponent")
        assertEquals("cricket", d.launch.options.extras["game"])
        assertEquals("shaky", d.launch.options.extras["assist"])
        assertEquals(CpuDifficulty.HARD, d.launch.options.cpuDifficulty)
        assertEquals(1, d.launch.options.firstMoverIndex)
        val dflt = local(DartsBowlingHubSpecs.darts, "cpu")
        assertEquals("501", dflt.launch.options.extras["game"])
        assertEquals("true", dflt.launch.options.extras["doubleOut"])
        assertEquals("false", dflt.launch.options.extras["doubleIn"])
        // the X01 rules are hidden in Cricket, so a stale value must not reach the game
        assertFalse(d.launch.options.extras.containsKey("doubleOut"))
        val b = local(DartsBowlingHubSpecs.bowling, "cpu", "bumpers" to "true", "guide" to "false")
        assertEquals("true", b.launch.options.extras["bumpers"])
        assertEquals("false", b.launch.options.extras["guide"])
        assertEquals("bowling", b.route)
    }
}
