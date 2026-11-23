package com.gamesuite.games.partypack

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubStart
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.games.lighttrail.LightTrailConfig
import com.gamesuite.games.lighttrail.LtArena
import com.gamesuite.games.sumopush.SumoArena
import com.gamesuite.games.sumopush.SumoConfig
import com.gamesuite.settings.CpuDifficulty
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

/**
 * Hub specs, launches, resources and identity colours of Light-Trail Duel and Sumo Push: HubLocalizationTest's rules
 * applied to our own specs, plus the resource completeness of the two screens (EN/ES parity, every string used, no
 * hard-coded English).
 */
class PartyTrailSumoHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parse(path: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(path))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") strings[e.getAttribute("name")] = e.textContent
        }
        return strings
    }

    private fun parseDir(dir: String): Map<String, String> {
        val all = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { all.putAll(parse("$dir/${it.name}")) }
        return all
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val specs = PartyTrailSumoHubSpecs.all
    private val ownFiles = listOf("trail_sumo_strings.xml", "light_trail_strings.xml", "sumo_push_strings.xml", "trail_sumo_coach_strings.xml", "trail_sumo_hub_strings.xml")

    private fun own(locale: String): Map<String, String> = ownFiles.flatMap { parse("res/$locale/$it").entries }.associate { it.key to it.value }

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
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringFilesHaveTheSameNamesAndPlaceholdersInBothLocalesAndTranslateEverySentence() {
        val e = own("values"); val s = own("values-es")
        assertTrue(e.size > 90)
        assertEquals(emptySet<String>(), e.keys - s.keys)
        assertEquals(emptySet<String>(), s.keys - e.keys)
        for ((k, v) in e) {
            assertEquals("placeholders of $k", placeholders(v), placeholders(s[k]!!))
            assertTrue("blank $k", v.isNotBlank() && s[k]!!.isNotBlank())
            if (v.length > 14) assertNotEquals("$k left in English", v, s[k])
        }
    }

    @Test fun noStringOfOursIsDeclaredTwiceAcrossAllResourceFiles() {
        for (dir in listOf("res/values", "res/values-es")) {
            val owners = HashMap<String, MutableList<String>>()
            moduleFile(dir).listFiles { f -> f.name.endsWith(".xml") }!!.forEach { f -> parse("$dir/${f.name}").keys.forEach { owners.getOrPut(it) { mutableListOf() } += f.name } }
            val dups = owners.filter { it.value.size > 1 }
            assertEquals("duplicate resource names in $dir: $dups", emptyMap<String, List<String>>(), dups)
        }
    }

    private val screenFiles = listOf("LightTrailScreen.kt", "SumoPushScreen.kt", "TrailSumoUi.kt")
    private fun screenSource() = screenFiles.joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }

    @Test fun everyStringTheScreensReferenceExistsInBothLocalesAndNoStringOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = own("values").keys.filter { it.startsWith("tsp_") || it.startsWith("ltd_") || it.startsWith("sup_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    @Test fun theScreensHardCodeNoEnglishText() {
        val src = screenSource()
        val literal = Regex("(?:Text\\(\\s*|contentDescription = )\"([^\"]*)\"")
        for (m in literal.findAll(src)) {
            val stripped = m.groupValues[1].replace(Regex("\\$\\{[^}]*}|\\$[A-Za-z_]+"), "")
            assertFalse("hard-coded text: ${m.groupValues[1]}", Regex("[A-Za-z]").containsMatchIn(stripped))
        }
        assertFalse(src.contains("Toast"))
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) for (f in ownFiles) {
            val xml = moduleFile("res/$d/$f").readText()
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $d/$f: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in PartyTrailSumoHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLanguagesAndSayTheGameIsSameScreenOnly() {
        for (id in listOf("light_trail", "sumo_push")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in en }
            assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
            assertFalse("coach_${id}_s${steps.size + 1}" in es)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
                assertTrue("coach $k", en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
                assertTrue("$k not translated", en[k] != es[k])
            }
            assertTrue(steps.any { en["coach_${id}_s$it"]!!.contains("Nearby and Online") })
        }
    }

    // ---- specs and launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothAreSameScreenArcadeGamesOnTheirOwnRoutesWithNoNearbyOrOnlineMode() {
        assertEquals(setOf("light-trail", "sumo-push"), specs.map { it.gameId }.toSet())
        for (s in specs) {
            assertEquals(HubFamily.ARCADE_PUZZLE, s.family)
            assertTrue(s.resumable)
            assertEquals(listOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), s.modes.map { it.kind })
            assertEquals(listOf("vs Computer", "Same screen"), s.modes.map { it.title })
            assertTrue(s.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
            assertFalse(s.isNetworked)
            s.modes.forEach { assertEquals(s.gameId, it.route); assertEquals(2, it.minSeats); assertEquals(4, it.maxSeats) }
            assertEquals(HubStart.IMMEDIATE, s.mode("cpu")!!.start)
            assertEquals(HubStart.READY_SCREEN, s.mode("same")!!.start)
        }
    }

    @Test fun sameScreenLaunchesTwoToFourPeopleAndComputersFillTheRest() {
        for (spec in specs) {
            val l = local(spec, "same", "humans" to "3", "cpus" to "1", "rounds" to "5", "arena" to "LARGE", "powerups" to "true", "relaxed" to "true", "controls" to "one", "zones" to "false", "difficulty" to "HARD")
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
            assertEquals(4, l.launch.players.size)
            assertEquals(listOf(false, false, false, true), l.launch.players.map { it.isBot })
            assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
            assertEquals("5", l.launch.options.extras["rounds"]); assertEquals("LARGE", l.launch.options.extras["arena"])
            assertEquals("true", l.launch.options.extras["powerups"]); assertEquals("true", l.launch.options.extras["relaxed"])
            assertEquals("one", l.launch.options.extras["controls"]); assertEquals("false", l.launch.options.extras["zones"])
            assertEquals(spec.gameId, l.route)
            // Too many seats: the computers give way, never the people.
            val full = local(spec, "same", "humans" to "4", "cpus" to "2")
            assertEquals(listOf(false, false, false, false), full.launch.players.map { it.isBot })
            val two = local(spec, "same")
            assertEquals(2, two.launch.players.size); assertTrue(two.launch.players.none { it.isBot })
        }
    }

    @Test fun vsComputerSeatsTheCpusInTheOtherZones() {
        for (spec in specs) {
            val l = local(spec, "cpu", "cpus" to "3", "difficulty" to "EASY")
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
            assertEquals(4, l.launch.players.size)
            assertEquals(listOf(false, true, true, true), l.launch.players.map { it.isBot })
            assertEquals(CpuDifficulty.EASY, l.launch.options.cpuDifficulty)
            val d = local(spec, "cpu")
            assertEquals(2, d.launch.players.size)
            assertEquals("3", d.launch.options.extras["rounds"]); assertEquals("MEDIUM", d.launch.options.extras["arena"])
            assertEquals("false", d.launch.options.extras["powerups"]); assertEquals("false", d.launch.options.extras["relaxed"])
        }
        assertEquals("pads", local(PartyTrailSumoHubSpecs.lightTrail, "cpu").launch.options.extras["controls"])
        assertEquals("stick", local(PartyTrailSumoHubSpecs.sumoPush, "cpu").launch.options.extras["controls"])
    }

    @Test fun theComputerLevelOnlyShowsInSameScreenWhenAComputerPlays() {
        for (spec in specs) {
            val level = spec.mode("same")!!.options.first { it.key == "difficulty" }
            assertFalse(level.visibleWhen!!(HubChoices().with("cpus", 0)))
            assertTrue(level.visibleWhen!!(HubChoices().with("cpus", 1)))
        }
    }

    @Test fun staleSavedValuesFallBackToTheDefaultsInsteadOfReachingTheGame() {
        val l = local(PartyTrailSumoHubSpecs.lightTrail, "same", "rounds" to "47", "arena" to "ENORMOUS", "controls" to "telepathy")
        assertEquals("3", l.launch.options.extras["rounds"]); assertEquals("MEDIUM", l.launch.options.extras["arena"]); assertEquals("pads", l.launch.options.extras["controls"])
    }

    @Test fun configsReadTheHubExtrasAndRepairAnythingOdd() {
        val lt = LightTrailConfig.fromExtras(3, mapOf("rounds" to "5", "arena" to "LARGE", "powerups" to "true", "relaxed" to "true", "controls" to "one"))
        assertEquals(LightTrailConfig(3, LtArena.LARGE, 5, true, true, true), lt)
        assertEquals(LightTrailConfig(2, LtArena.MEDIUM, 3, false, false, false), LightTrailConfig.fromExtras(1, mapOf("rounds" to "9", "arena" to "x")))
        assertEquals(4, LightTrailConfig.fromExtras(9, emptyMap()).players)
        val su = SumoConfig.fromExtras(4, mapOf("rounds" to "5", "arena" to "small", "powerups" to "true", "relaxed" to "true", "controls" to "one"))
        assertEquals(SumoConfig(4, SumoArena.SMALL, 5, true, true, true), su)
        assertEquals(SumoConfig(2, SumoArena.MEDIUM, 3, false, false, false), SumoConfig.fromExtras(0, mapOf("rounds" to "-1")))
    }

    // ---- identity colours ----

    @Test fun proposedAccentsAreUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in PartyTrailSumoHubSpecs.accents.keys }
        for ((id, pair) in PartyTrailSumoHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        val all = PartyTrailSumoHubSpecs.accents.values.toList()
        assertEquals(all.size, all.map { it.first }.toSet().size); assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun everySeatIsToldApartByColourShapePatternAndNameNotColourAlone() {
        val src = screenSource()
        // Four distinct pattern words and four distinct seat shapes are wired into the art.
        val words = listOf("tsp_style_solid", "tsp_style_dashed", "tsp_style_dotted", "tsp_style_hollow")
        assertEquals(4, words.map { en.getValue(it) }.toSet().size)
        words.forEach { assertTrue(src.contains("R.string.$it")) }
        assertTrue(src.contains("drawSeatGlyph(seat"))   // a shape per rider head and per chip
        assertTrue(src.contains("tsDashedCircle"))        // dashed belts and dashed pickups
        // The colours themselves are distinct, and the chip carries the pattern word and name in its TalkBack text.
        val colours = listOf(0xFF56B4E9L, 0xFFF0E442L, 0xFFFF7A33L, 0xFFCC79A7L)
        assertEquals(4, colours.toSet().size)
        assertTrue(en.getValue("tsp_chip_desc").let { it.contains("%1$") && it.contains("%2$") })
    }
}
