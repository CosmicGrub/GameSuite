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

/** Hub specs, launches, resources (EN + ES parity, no hard-coded English) and identity colours of Paddle Duel and Rock-Paper-Showdown. */
class PartyPaddleRpsHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private class Res(val strings: Map<String, String>, val fileOf: Map<String, MutableList<String>>)

    private fun parseDir(dir: String): Res {
        val strings = linkedMapOf<String, String>()
        val fileOf = linkedMapOf<String, MutableList<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") { strings[e.getAttribute("name")] = e.textContent; fileOf.getOrPut(e.getAttribute("name")) { mutableListOf() } += f.name }
            }
        }
        return Res(strings, fileOf)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val specs = PartyPaddleRpsHubSpecs.all
    private val mine = listOf("pdl_", "rps_", "coach_paddle_duel_", "coach_rps_showdown_", "tip_paddle_duel_", "tip_rps_showdown_")
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

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKeyWithTheSameText() {
        for (spec in specs) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en.strings)
            assertTrue("missing ES $key for '$text'", key in es.strings)
            assertEquals(text, shown(en.strings[key]!!))
            assertTrue("blank ES $key", es.strings[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en.strings && hubKey(it) in es.strings) }
    }

    @Test fun noTwoDifferentHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun noResourceNameIsDefinedTwiceInTheLocaleFolders() {
        for ((label, res) in listOf("values" to en, "values-es" to es)) {
            val dupes = res.fileOf.filterValues { it.size > 1 }.filterKeys { isMine(it) || it in specs.flatMap { s -> texts(s) }.map { t -> hubKey(t) } }
            assertEquals("duplicate resource names in $label", emptyMap<String, Any>(), dupes)
        }
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.strings.keys.filter { isMine(it) }
        assertTrue(ours.size > 90)
        for (k in ours) {
            assertTrue("no ES for $k", k in es.strings)
            assertEquals("placeholders of $k", placeholders(en.strings[k]!!), placeholders(es.strings[k]!!))
        }
        assertEquals(emptySet<String>(), es.strings.keys.filter { isMine(it) }.toSet() - en.strings.keys)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = listOf("PaddleDuelScreen.kt", "RpsShowdownScreen.kt", "PaddleRpsPartyUi.kt", "RpsShowdownArt.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 80)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en.strings)
            assertTrue("R.string.$name missing in values-es/", name in es.strings)
        }
        val screenStrings = en.strings.keys.filter { it.startsWith("pdl_") || it.startsWith("rps_") }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreensHardCodeNoEnglishText() {
        for (f in listOf("PaddleDuelScreen.kt", "RpsShowdownScreen.kt", "PaddleRpsPartyUi.kt")) {
            val src = moduleFile("java/com/gamesuite/ui/$f").readText()
            // a Text("...") or contentDescription = "..." whose literal has a run of two or more letters
            val bad = Regex("(Text\\(|contentDescription = )\"([^\"$]*[A-Za-z]{2,}[^\"]*)\"").findAll(src).map { it.groupValues[2] }.toList()
            assertEquals("hard-coded text in $f", emptyList<String>(), bad)
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        listOf("values", "values-es").flatMap { d ->
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("party_paddle_rps") || f.name.startsWith("paddle_duel") || f.name.startsWith("rps_showdown") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in PartyPaddleRpsHubSpecs.tips) {
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
        for (id in listOf("paddle_duel", "rps_showdown")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in en.strings }
            assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
            assertFalse("coach_${id}_s${steps.size + 1}" in es.strings)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
                assertTrue("coach $k", en.strings[k]?.isNotBlank() == true && es.strings[k]?.isNotBlank() == true)
                assertTrue("$k not translated", en.strings[k] != es.strings[k])
            }
        }
    }

    @Test fun theCoachAndTheHelpSayThereIsNoNearbyOrOnlineYet() {
        for (k in listOf("coach_paddle_duel_s5", "coach_rps_showdown_s5", "pdl_help_text", "rps_help_text")) {
            assertTrue(k, en.strings[k]!!.contains("Nearby") && en.strings[k]!!.contains("Online"))
            assertTrue(k, es.strings[k]!!.contains("Cercano") && es.strings[k]!!.contains("En línea"))
        }
    }

    // ---- specs and launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothAreSameScreenGamesWithNoNetworkedModes() {
        assertEquals(setOf("paddle-duel", "rps-showdown"), specs.map { it.gameId }.toSet())
        for (s in specs) {
            assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), s.modes.map { it.kind }.toSet())
            assertFalse(s.isNetworked)
            assertEquals("Same screen", s.mode("same")!!.title)
            assertTrue(s.modes.all { it.route == s.gameId && it.minSeats == 2 && it.maxSeats == 4 })
            assertTrue(s.resumable)
        }
    }

    @Test fun vsComputerFillsEmptyZonesWithComputersAndCarriesTheLevel() {
        for (spec in specs) {
            val one = local(spec, "cpu", "difficulty" to "HARD")
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, one.launch.mode)
            assertEquals(CpuDifficulty.HARD, one.launch.options.cpuDifficulty)
            assertEquals(2, one.launch.players.size); assertTrue(one.launch.players[1].isBot)
            val three = local(spec, "cpu", "cpus" to "3")
            assertEquals(4, three.launch.players.size); assertEquals(3, three.launch.players.count { it.isBot })
            val tooMany = local(spec, "cpu", "cpus" to "9")
            assertEquals(4, tooMany.launch.players.size)
        }
    }

    @Test fun sameScreenSeatsHumansThenComputersUpToFour() {
        for (spec in specs) {
            val two = local(spec, "same")
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, two.launch.mode); assertEquals(2, two.launch.players.size); assertTrue(two.launch.players.none { it.isBot })
            val mixed = local(spec, "same", "humans" to "3", "cpus" to "2")
            assertEquals(4, mixed.launch.players.size); assertEquals(3, mixed.launch.players.count { !it.isBot })
            val four = local(spec, "same", "humans" to "4")
            assertEquals(4, four.launch.players.size)
        }
    }

    @Test fun optionsReachTheGameAsExtrasWithSafeDefaultsAndStaleValuesFallBack() {
        val pd = local(PartyPaddleRpsHubSpecs.paddleDuel, "cpu", "format" to "l5", "powerups" to "true", "relaxed" to "true", "controls" to "switch", "facing" to "false")
        assertEquals("l5", pd.launch.options.extras["format"]); assertEquals("true", pd.launch.options.extras["powerups"]); assertEquals("true", pd.launch.options.extras["relaxed"])
        assertEquals("switch", pd.launch.options.extras["controls"]); assertEquals("false", pd.launch.options.extras["facing"])
        val d = local(PartyPaddleRpsHubSpecs.paddleDuel, "cpu")
        assertEquals("p7", d.launch.options.extras["format"]); assertEquals("false", d.launch.options.extras["powerups"]); assertEquals("drag", d.launch.options.extras["controls"]); assertEquals("true", d.launch.options.extras["facing"])
        assertEquals("p7", local(PartyPaddleRpsHubSpecs.paddleDuel, "cpu", "format" to "p99").launch.options.extras["format"])
        val rps = local(PartyPaddleRpsHubSpecs.rpsShowdown, "same", "variant" to "lizard", "format" to "elim", "timer" to "20", "relaxed" to "true")
        assertEquals("lizard", rps.launch.options.extras["variant"]); assertEquals("elim", rps.launch.options.extras["format"]); assertEquals("20", rps.launch.options.extras["timer"])
        val rd = local(PartyPaddleRpsHubSpecs.rpsShowdown, "cpu")
        assertEquals("classic", rd.launch.options.extras["variant"]); assertEquals("bo3", rd.launch.options.extras["format"]); assertEquals("off", rd.launch.options.extras["timer"])
        assertEquals("bo3", local(PartyPaddleRpsHubSpecs.rpsShowdown, "cpu", "format" to "bo99").launch.options.extras["format"])
    }

    @Test fun everyOptionChoiceTheSpecOffersIsOneTheGameParses() {
        val pd = PartyPaddleRpsHubSpecs.paddleDuel.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "format" }
        assertEquals(PdFormat.ALL.map { it.id }.toSet(), pd.choices.map { it.id }.toSet())
        val rf = PartyPaddleRpsHubSpecs.rpsShowdown.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "format" }
        assertEquals(RpsFormat.entries.map { it.id }.toSet(), rf.choices.map { it.id }.toSet())
        val rv = PartyPaddleRpsHubSpecs.rpsShowdown.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "variant" }
        assertEquals(RpsVariant.entries.map { it.id }.toSet(), rv.choices.map { it.id }.toSet())
        val timer = PartyPaddleRpsHubSpecs.rpsShowdown.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "timer" }
        assertTrue(timer.choices.all { it.id == "off" || it.id.toInt() in 1..120 })
    }

    // ---- identity colours ----

    @Test fun proposedAccentsAreUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in PartyPaddleRpsHubSpecs.accents.keys }
        for ((id, pair) in PartyPaddleRpsHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        val all = PartyPaddleRpsHubSpecs.accents.values.toList()
        assertEquals(all.size, all.map { it.first }.toSet().size); assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun theSeatColoursAreToldApartByShapeAndNameNotColourAlone() {
        val art = moduleFile("java/com/gamesuite/ui/PaddleRpsPartyUi.kt").readText()
        assertTrue(art.contains("ppSeatShape"))
        val pd = moduleFile("java/com/gamesuite/ui/PaddleDuelScreen.kt").readText()
        assertTrue(pd.contains("drawSeatGlyph") && pd.contains("ClSeatBadge"))
        val rps = moduleFile("java/com/gamesuite/ui/RpsShowdownScreen.kt").readText()
        assertTrue(rps.contains("ClSeatBadge") && rps.contains("rpsMoveName")) // a move is always named in text next to its drawing
    }
}
