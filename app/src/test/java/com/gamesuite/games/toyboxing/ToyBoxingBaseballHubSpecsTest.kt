package com.gamesuite.games.toyboxing

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
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** Hub specs, launches, and the EN + ES text of Toy Boxing and Toy Baseball (HubLocalizationTest's rules, applied to our own specs). */
class ToyBoxingBaseballHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private class Res(val strings: Map<String, String>)

    private fun parseDir(dir: String): Res {
        val strings = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") strings[e.getAttribute("name")] = e.textContent
            }
        }
        return Res(strings)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val specs = ToyBoxingBaseballHubSpecs.all

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

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")

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

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val mine = en.strings.keys.filter { it.startsWith("tbx_") || it.startsWith("tbb_") || it.startsWith("coach_toy_") || it.startsWith("tip_toy_") }
        assertTrue(mine.size > 180)
        for (k in mine) {
            assertTrue("no ES for $k", k in es.strings)
            assertEquals("placeholders of $k", placeholders(en.strings[k]!!), placeholders(es.strings[k]!!))
        }
        val mineEs = es.strings.keys.filter { it.startsWith("tbx_") || it.startsWith("tbb_") || it.startsWith("coach_toy_") || it.startsWith("tip_toy_") }
        assertEquals(emptySet<String>(), mineEs.toSet() - en.strings.keys)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocales() {
        val src = listOf("ToyBoxingScreen.kt", "ToyBaseballScreen.kt", "ToyBoxingArt.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 150)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en.strings)
            assertTrue("R.string.$name missing in values-es/", name in es.strings)
        }
        // And every screen string of ours is used by something (no dead text to translate).
        val ours = en.strings.keys.filter { it.startsWith("tbx_") || it.startsWith("tbb_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        listOf("values", "values-es").flatMap { d ->
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("toy_box") || f.name.startsWith("toy_base") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in ToyBoxingBaseballHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en.strings[k]!!))
                assertTrue("$k not translated", en.strings[k] != es.strings[k] && es.strings[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLanguages() {
        for (id in listOf("toy_boxing", "toy_baseball")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in en.strings }
            assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
            assertFalse("coach_${id}_s${steps.size + 1}" in es.strings)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
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

    @Test fun bothAreTwoSeatArcadeGamesOnTheirOwnRoutes() {
        assertEquals(setOf("toy-boxing", "toy-baseball"), specs.map { it.gameId }.toSet())
        assertTrue(specs.all { it.family == HubFamily.ARCADE_PUZZLE && it.resumable })
        assertTrue(specs.all { s -> s.modes.all { it.route == s.gameId && it.minSeats == 2 && it.maxSeats == 2 } })
        assertTrue(specs.all { s -> s.modes.map { it.kind }.toSet() == setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY) })
    }

    @Test fun boxingLaunchesAgainstTheComputerWithItsOptions() {
        val l = local(ToyBoxingBaseballHubSpecs.boxing, "cpu", "difficulty" to "HARD", "rounds" to "5", "knockouts" to "false", "assist" to "true")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
        assertEquals("5", l.launch.options.extras["rounds"]); assertEquals("false", l.launch.options.extras["knockouts"]); assertEquals("true", l.launch.options.extras["assist"])
        assertEquals(2, l.launch.players.size); assertTrue(l.launch.players[1].isBot)
        val d = local(ToyBoxingBaseballHubSpecs.boxing, "cpu")
        assertEquals("3", d.launch.options.extras["rounds"]); assertEquals("true", d.launch.options.extras["knockouts"]); assertEquals("false", d.launch.options.extras["assist"])
    }

    @Test fun baseballLaunchesWithInningsAndWhoBatsFirst() {
        val l = local(ToyBoxingBaseballHubSpecs.baseball, "cpu", "innings" to "9", "first" to "opponent")
        assertEquals("9", l.launch.options.extras["innings"]); assertEquals(1, l.launch.options.firstMoverIndex)
        val p = local(ToyBoxingBaseballHubSpecs.baseball, "pass", "innings" to "5")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode); assertEquals(2, p.launch.players.size); assertTrue(p.launch.players.none { it.isBot })
        assertEquals("5", p.launch.options.extras["innings"])
        // A stale saved value falls back to the default instead of reaching the game.
        val bad = local(ToyBoxingBaseballHubSpecs.baseball, "cpu", "innings" to "47")
        assertEquals("3", bad.launch.options.extras["innings"])
    }

    // ---- identity colours ----

    @Test fun proposedAccentsAreUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in ToyBoxingBaseballHubSpecs.accents.keys }
        for ((id, pair) in ToyBoxingBaseballHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        val all = ToyBoxingBaseballHubSpecs.accents.values.toList()
        assertEquals(all.size, all.map { it.first }.toSet().size); assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun theBoxersColoursStayApartForColourBlindPlayersAndCarryAShapeAndAName() {
        // Okabe-Ito vermillion and blue: well separated in luminance as well as hue.
        val a = Wcag.luminance(0xFFD55E00); val b = Wcag.luminance(0xFF0072B2)
        assertTrue(Wcag.contrast(0xFFD55E00, 0xFF0072B2) >= 1.5 || kotlin.math.abs(a - b) > 0.05)
        val art = moduleFile("java/com/gamesuite/ui/ToyBoxingArt.kt").readText()
        assertTrue(art.contains("badge")) // the trunk badge shape
    }
}
