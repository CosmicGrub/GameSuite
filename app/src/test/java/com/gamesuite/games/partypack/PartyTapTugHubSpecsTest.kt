package com.gamesuite.games.partypack

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

/** Hub specs, launches, resources and identity colours of Tap Race and Tug of War (HubLocalizationTest's rules, applied to our own specs). */
class PartyTapTugHubSpecsTest {
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
    private val specs = PartyTapTugHubSpecs.all
    private val mine = listOf("trc_", "tow_", "coach_tap_race_", "coach_tug_of_war_", "tip_tap_race_", "tip_tug_of_war_")
    private fun isMine(k: String) = mine.any { k.startsWith(it) }

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

    @Test fun theSameScreenModeUsesTheTitleEveryPartyGameShares() {
        for (spec in specs) {
            val same = spec.modes.first { it.kind == HubModeKind.PASS_AND_PLAY }
            assertEquals("Same screen", same.title)
        }
        assertEquals("Same screen", shown(en.strings["hub_same_screen"]!!))
        assertTrue(es.strings["hub_same_screen"]!!.isNotBlank())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.strings.keys.filter { isMine(it) }
        assertTrue(ours.size > 70)
        for (k in ours) {
            assertTrue("no ES for $k", k in es.strings)
            assertEquals("placeholders of $k", placeholders(en.strings[k]!!), placeholders(es.strings[k]!!))
            assertTrue("blank $k", en.strings[k]!!.isNotBlank() && es.strings[k]!!.isNotBlank())
        }
        val oursEs = es.strings.keys.filter { isMine(it) }
        assertEquals(emptySet<String>(), oursEs.toSet() - en.strings.keys)
    }

    private val screenFiles = listOf("TapRaceScreen.kt", "TugOfWarScreen.kt", "PartyTapTugArt.kt")
    private fun screenSource() = screenFiles.joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }

    @Test fun everyStringTheScreensReferenceExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en.strings)
            assertTrue("R.string.$name missing in values-es/", name in es.strings)
        }
        val screenStrings = en.strings.keys.filter { it.startsWith("trc_") || it.startsWith("tow_") }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreensHaveNoHardCodedEnglish() {
        for (f in screenFiles) {
            val src = moduleFile("java/com/gamesuite/ui/$f").readText()
            val literals = Regex("(?:Text\\(\\s*|text\\s*=\\s*|contentDescription\\s*=\\s*|description\\s*=\\s*)\"([^\"]*)\"").findAll(src)
            for (m in literals) {
                val visible = m.groupValues[1].replace(Regex("\\$\\{[^}]*\\}"), "").replace(Regex("\\$[A-Za-z]+"), "")
                assertFalse("hard-coded text in $f: '${m.groupValues[1]}'", Regex("[A-Za-z]{3,}").containsMatchIn(visible))
            }
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        val files = listOf("party_tap_tug_hub_strings.xml", "tap_race_strings.xml", "tug_of_war_strings.xml", "party_tap_tug_coach_strings.xml", "party_tap_tug_tips_strings.xml")
        for (d in listOf("values", "values-es")) for (n in files) {
            val xml = moduleFile("res/$d/$n").readText()
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $d/$n: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in PartyTapTugHubSpecs.tips) {
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
        for (id in listOf("tap_race", "tug_of_war")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in en.strings }
            assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
            assertFalse("coach_${id}_s${steps.size + 1}" in es.strings)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
                assertTrue("coach $k", en.strings[k]?.isNotBlank() == true && es.strings[k]?.isNotBlank() == true)
                assertTrue("$k not translated", en.strings[k] != es.strings[k])
            }
        }
    }

    @Test fun theCoachSaysTheGamesAreSameScreenOnly() {
        for (id in listOf("tap_race", "tug_of_war")) {
            val last = (1..6).last { "coach_${id}_s$it" in en.strings }
            assertTrue(en.strings["coach_${id}_s$last"]!!.contains("Same screen only"))
            assertTrue(es.strings["coach_${id}_s$last"]!!.contains("Solo en la misma pantalla"))
        }
    }

    // ---- specs and launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothAreSameScreenOnlyFourSeatArcadeGames() {
        assertEquals(setOf("tap-race", "tug-of-war"), specs.map { it.gameId }.toSet())
        for (s in specs) {
            assertTrue(s.resumable); assertFalse(s.isNetworked); assertFalse(s.skipsHub)
            assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), s.modes.map { it.kind }.toSet())
            assertTrue(s.modes.all { it.route == s.gameId && it.minSeats == 2 && it.maxSeats == 4 })
        }
    }

    @Test fun tapRaceLaunchesAgainstComputersWithItsOptions() {
        val l = local(PartyTapTugHubSpecs.tapRace, "cpu", "difficulty" to "HARD", "variant" to "brake", "rounds" to "5", "relaxed" to "true", "hold" to "true", "rotate" to "false", "cpus" to "3")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
        assertEquals(4, l.launch.players.size)
        assertEquals(listOf(false, true, true, true), l.launch.players.map { it.isBot })
        val x = l.launch.options.extras
        assertEquals("brake", x["variant"]); assertEquals("5", x["rounds"]); assertEquals("true", x["relaxed"]); assertEquals("true", x["hold"]); assertEquals("false", x["rotate"])
        val d = local(PartyTapTugHubSpecs.tapRace, "cpu")
        assertEquals(2, d.launch.players.size)
        assertEquals("sprint", d.launch.options.extras["variant"]); assertEquals("3", d.launch.options.extras["rounds"])
        assertEquals("false", d.launch.options.extras["relaxed"]); assertEquals("false", d.launch.options.extras["hold"]); assertEquals("true", d.launch.options.extras["rotate"])
    }

    @Test fun sameScreenSeatsPeopleFirstAndComputersFillTheRest() {
        val p = local(PartyTapTugHubSpecs.tapRace, "same", "humans" to "3", "cpus" to "1")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode)
        assertEquals(listOf(false, false, false, true), p.launch.players.map { it.isBot })
        val full = local(PartyTapTugHubSpecs.tugOfWar, "same", "humans" to "4", "cpus" to "2")
        assertEquals(4, full.launch.players.size) // never more than four zones
        assertTrue(full.launch.players.none { it.isBot })
        val two = local(PartyTapTugHubSpecs.tugOfWar, "same")
        assertEquals(2, two.launch.players.size)
    }

    @Test fun tugOfWarCarriesItsLayoutAndAStaleChoiceFallsBack() {
        val l = local(PartyTapTugHubSpecs.tugOfWar, "same", "humans" to "4", "layout" to "lone", "rounds" to "1")
        assertEquals("lone", l.launch.options.extras["layout"]); assertEquals("1", l.launch.options.extras["rounds"])
        assertEquals("teams", local(PartyTapTugHubSpecs.tugOfWar, "cpu").launch.options.extras["layout"])
        assertEquals("teams", local(PartyTapTugHubSpecs.tugOfWar, "cpu", "layout" to "pyramid").launch.options.extras["layout"])
        assertEquals("sprint", local(PartyTapTugHubSpecs.tapRace, "cpu", "variant" to "warp").launch.options.extras["variant"])
        assertEquals("3", local(PartyTapTugHubSpecs.tapRace, "cpu", "rounds" to "47").launch.options.extras["rounds"])
    }

    @Test fun everyOptionKeyTheScreensReadIsOnOffer() {
        val tap = PartyTapTugHubSpecs.tapRace.modes.flatMap { it.options }.map { it.key }.toSet()
        val tug = PartyTapTugHubSpecs.tugOfWar.modes.flatMap { it.options }.map { it.key }.toSet()
        assertTrue(tap.containsAll(listOf("variant", "rounds", "relaxed", "hold", "rotate")))
        assertTrue(tug.containsAll(listOf("layout", "rounds", "relaxed", "hold", "rotate")))
        val tapSrc = moduleFile("java/com/gamesuite/ui/TapRaceScreen.kt").readText()
        val tugSrc = moduleFile("java/com/gamesuite/ui/TugOfWarScreen.kt").readText()
        for (k in listOf("variant", "rounds", "relaxed", "hold", "rotate")) assertTrue(k, tapSrc.contains("extras[\"$k\"]"))
        for (k in listOf("layout", "rounds", "relaxed", "hold", "rotate")) assertTrue(k, tugSrc.contains("extras[\"$k\"]"))
    }

    // ---- identity colours ----

    @Test fun proposedAccentsAreUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in PartyTapTugHubSpecs.accents.keys }
        for ((id, pair) in PartyTapTugHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        val all = PartyTapTugHubSpecs.accents.values.toList()
        assertEquals(all.size, all.map { it.first }.toSet().size); assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun theFourSeatColoursAreSeparatedByLuminanceAsWellAsHueAndEachSeatAlsoHasAGlyphAndAPattern() {
        val seat = listOf(0xFFD55E00L, 0xFF0072B2L, 0xFFE8D22AL, 0xFF009E73L) // Okabe-Ito, the palette of Chinese Checkers
        for (i in seat.indices) for (j in i + 1 until seat.size) {
            val dl = kotlin.math.abs(Wcag.luminance(seat[i]) - Wcag.luminance(seat[j]))
            assertTrue("seat $i and $j differ by colour alone only slightly ($dl): they must differ by glyph and pattern too", dl > 0.03 || Wcag.contrast(seat[i], seat[j]) >= 1.3)
        }
        val art = moduleFile("java/com/gamesuite/ui/PartyTapTugArt.kt").readText()
        assertTrue(art.contains("fun tapTugGlyph(index: Int): Int = index % 4"))
        assertTrue(art.contains("fun DrawScope.drawTapTugPattern"))
    }
}
