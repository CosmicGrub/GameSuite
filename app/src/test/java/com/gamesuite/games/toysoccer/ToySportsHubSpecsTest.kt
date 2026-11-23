package com.gamesuite.games.toysoccer

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.games.toytennis.TennisFormat
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.ui.GameAccent
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** Hub specs, launches, EN + ES resources (HubLocalizationTest's rules), tips, coach text and the proposed accents. */
class ToySportsHubSpecsTest {
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
                if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
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
        for (spec in ToySportsHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, en[key]!!.replace("\\'", "'"))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = ToySportsHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun spanishIsTranslatedNotCopied() {
        for (spec in ToySportsHubSpecs.all) for (text in texts(spec)) {
            if (text.any { it.isDigit() } && text.length < 14) continue
            val key = hubKey(text)
            if (text in setOf("Toy Tennis", "OK")) continue
            assertNotEquals("ES copy of '$text'", text, es[key]!!.replace("\\'", "'"))
        }
    }

    private val mine get() = en.keys.filter { it.startsWith("tsc_") || it.startsWith("tte_") || it.startsWith("tip_toy_") || it.startsWith("coach_toy_") }

    @Test fun everyGameStringExistsInBothLocalesWithMatchingPlaceholders() {
        assertTrue(mine.size > 100)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertTrue("blank EN $k", en[k]!!.isNotBlank())
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        val esMine = es.keys.filter { it.startsWith("tsc_") || it.startsWith("tte_") || it.startsWith("tip_toy_") || it.startsWith("coach_toy_") }
        assertEquals(emptySet<String>(), esMine.toSet() - en.keys)
    }

    @Test fun everyStringTheScreensReferenceExists() {
        val src = listOf("ToySoccerScreen.kt", "ToyTennisScreen.kt", "ToySoccerTennisArt.kt").joinToString("\n") {
            moduleFile("java/com/gamesuite/ui/$it").readText()
        }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        val raw = listOf("values", "values-es").flatMap { d ->
            File(moduleFile("res/$d").path).listFiles { f -> f.name.startsWith("toysports") }!!.map { it.readText() }
        }
        assertEquals(4, raw.size)
        raw.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun noResourceNameIsDefinedTwice() {
        // A duplicate across resource files fails the build, so check the whole values/ and values-es/ folders.
        for (dir in listOf("res/values", "res/values-es")) {
            val seen = HashMap<String, String>()
            moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                val nodes = doc.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? Element ?: continue
                    if (e.tagName != "string" && e.tagName != "plurals" && e.tagName != "string-array") continue
                    val n = e.getAttribute("name")
                    val prev = seen.put(n, f.name)
                    if (prev != null && (f.name.startsWith("toysports") || prev.startsWith("toysports")))
                        throw AssertionError("$n defined in both $prev and ${f.name}")
                }
            }
        }
    }

    // ---- tips and coach --------------------------------------------------------------------

    @Test fun tipsAreThreeLinesAndTheEnglishEqualsTheResource() {
        for ((id, lines) in ToySportsHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val name = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, en[name]!!.replace("\\'", "'"))
                assertTrue(es[name]!!.isNotBlank() && es[name] != en[name])
            }
        }
        assertEquals(setOf("toy-soccer", "toy-tennis"), ToySportsHubSpecs.tips.keys)
    }

    @Test fun coachTextExistsForBothGamesInBothLocalesWithThreeToSixSteps() {
        for (id in listOf("toy-soccer", "toy-tennis")) {
            val base = "coach_${id.replace('-', '_')}"
            val steps = (1..9).takeWhile { "${base}_s$it" in en }
            assertTrue("$id steps ${steps.size}", steps.size in 3..6)
            for (n in steps.map { "${base}_s$it" } + "${base}_fact") {
                assertTrue(en[n]!!.isNotBlank()); assertTrue(es[n]!!.isNotBlank()); assertNotEquals(en[n], es[n])
            }
            assertFalse("${base}_s${steps.size + 1}" in en)
        }
        // the coach strings live only in files the coach test scans
        val coachFiles = listOf("values", "values-es").map { File(moduleFile("res/$it").path, "toysports_coach_strings.xml") }
        assertTrue(coachFiles.all { it.exists() && it.readText().contains("coach_toy_soccer_fact") })
    }

    // ---- launches --------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun soccerSeatsAndOptionsReachTheGame() {
        val s = ToySportsHubSpecs.toySoccer
        val one = local(s, "cpu", "match" to "time4", "assist" to "true", "difficulty" to "HARD")
        assertEquals(2, one.launch.players.size)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, one.launch.mode)
        assertEquals("time4", one.launch.options.extras["match"])
        assertEquals("true", one.launch.options.extras["assist"])
        assertEquals(CpuDifficulty.HARD, one.launch.options.cpuDifficulty)
        assertEquals("toy-soccer", one.route)
        val two = local(s, "cpu2")
        assertEquals(4, two.launch.players.size)
        assertEquals(3, two.launch.players.count { it.isBot })
        assertEquals("first3", two.launch.options.extras["match"])
        assertEquals("false", two.launch.options.extras["assist"])
        val pass = local(s, "pass")
        assertEquals(2, pass.launch.players.size); assertEquals(0, pass.launch.players.count { it.isBot })
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode)
        for (humans in 2..4) {
            val l = local(s, "pass2", "humans" to humans.toString())
            assertEquals(4, l.launch.players.size)
            assertEquals(4 - humans, l.launch.players.count { it.isBot })
        }
        // every roster alternates teams by seat, which is what the engine assumes
        assertEquals(4, SoccerRules.figuresFor(local(s, "pass2").launch.players.size))
    }

    @Test fun matchOptionValuesAreTheOnesTheGameParses() {
        val opt = ToySportsHubSpecs.toySoccer.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "match" }
        for (c in opt.choices) {
            val m = SoccerMatch.parse(c.id)
            assertTrue("${c.id} parses to a real setting", m.goals > 0 || m.seconds > 0)
            assertTrue((c.id.startsWith("first") && m.goals > 0) || (c.id.startsWith("time") && m.seconds > 0))
        }
    }

    @Test fun tennisFormatOptionValuesAreTheOnesTheGameParses() {
        val opt = ToySportsHubSpecs.toyTennis.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "format" }
        assertEquals(listOf("points7", "points11", "set4", "set6", "best3"), opt.choices.map { it.id })
        assertEquals(opt.choices.size, opt.choices.map { TennisFormat.parse(it.id) }.toSet().size)
        val l = local(ToySportsHubSpecs.toyTennis, "pass", "format" to "best3", "assist" to "true", "first" to "opponent")
        assertEquals("best3", l.launch.options.extras["format"])
        assertEquals("true", l.launch.options.extras["assist"])
        assertEquals(1, l.launch.options.firstMoverIndex)
        assertEquals("toy-tennis", l.route)
        assertEquals(2, l.launch.players.size)
    }

    @Test fun nearbyAndOnlineWouldSeatTwoToFour() {
        val s = ToySportsHubSpecs.toySoccer
        assertEquals(4, s.modes.maxOf { it.maxSeats }); assertEquals(2, s.modes.filter { it.kind != HubModeKind.SOLO }.minOf { it.minSeats })
        assertEquals(2, ToySportsHubSpecs.toyTennis.modes.maxOf { it.maxSeats })
        assertTrue(ToySportsHubSpecs.all.all { it.family == HubFamily.ARCADE_PUZZLE && it.resumable })
        assertEquals(listOf("toy-soccer", "toy-tennis"), ToySportsHubSpecs.all.map { it.gameId })
    }

    // ---- the accents the integrator should paste -------------------------------------------

    private val soccerAccent = GameAccent(0xFF537D38, 0xFF6F9956)
    private val tennisAccent = GameAccent(0xFF7D6F38, 0xFF998C56)

    @Test fun proposedAccentsAreUniqueAndReadable() {
        val others = GameThemes.accents.filterKeys { it != "toy-soccer" && it != "toy-tennis" }.values
        for (a in listOf(soccerAccent, tennisAccent)) {
            assertFalse(others.any { it.light == a.light }); assertFalse(others.any { it.dark == a.dark })
            for (surface in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(a.light, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (surface in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(a.dark, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.light), a.light) >= GameThemes.MIN_TEXT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.dark), a.dark) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        assertNotEquals(soccerAccent, tennisAccent)
    }
}
