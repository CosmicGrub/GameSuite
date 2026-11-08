package com.gamesuite.games.slotcars

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
import com.gamesuite.games.fishing.FishingLakes
import com.gamesuite.games.fishing.FishingRules
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
class SlotCarsFishingHubSpecsTest {
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
    private val ids = listOf("slot-cars", "fishing")

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
    private fun unescape(s: String) = s.replace("\\'", "'")

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in SlotCarsFishingHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, unescape(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = SlotCarsFishingHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun spanishIsTranslatedNotCopied() {
        for (spec in SlotCarsFishingHubSpecs.all) for (text in texts(spec)) {
            if (text.any { it.isDigit() } && text.length < 14) continue
            if (text in setOf("Slot Cars", "Fishing")) continue
            assertNotEquals("ES copy of '$text'", text, unescape(es[hubKey(text)]!!))
        }
    }

    private val mine get() = en.keys.filter {
        it.startsWith("sct_") || it.startsWith("fsh_") || it.startsWith("tip_slot_cars") || it.startsWith("tip_fishing") ||
            it.startsWith("coach_slot_cars") || it.startsWith("coach_fishing")
    }

    @Test fun everyGameStringExistsInBothLocalesWithMatchingPlaceholders() {
        assertTrue(mine.size > 150)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertTrue("blank EN $k", en[k]!!.isNotBlank())
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        val esMine = es.keys.filter { it.startsWith("sct_") || it.startsWith("fsh_") || it.startsWith("tip_slot_cars") || it.startsWith("tip_fishing") || it.startsWith("coach_slot_cars") || it.startsWith("coach_fishing") }
        assertEquals(emptySet<String>(), esMine.toSet() - en.keys)
    }

    @Test fun everyStringTheScreensReferenceExists() {
        val src = listOf("SlotCarsScreen.kt", "SlotCarsArt.kt", "FishingScreen.kt", "FishingArt.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 90)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
    }

    @Test fun everySpeciesTrackLakeAndPlaceHasAName() {
        for (sp in FishingLakes.species) for (m in listOf(en, es)) assertTrue("fish ${sp.id}", "fsh_fish_${sp.id}" in m)
        assertEquals(FishingLakes.species.size, FishingLakes.species.map { en["fsh_fish_${it.id}"] }.toSet().size)
        assertEquals(FishingLakes.species.size, FishingLakes.species.map { es["fsh_fish_${it.id}"] }.toSet().size)
        for (id in SlotTrack.ids) for (m in listOf(en, es)) assertTrue("track $id", "sct_track_$id" in m)
        for (id in FishingLakes.lakeIds) for (m in listOf(en, es)) assertTrue("lake $id", "fsh_lake_$id" in m)
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        val names = listOf("slotcars_strings", "fishing_strings", "slotcars_fishing_hub_strings", "slotcars_fishing_coach_strings")
        for (d in listOf("values", "values-es")) for (n in names) {
            val xml = File(moduleFile("res/$d").path, "$n.xml").readText()
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $n: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                assertFalse("raw newline inside a string in $n", m.groupValues[1].trim().isNotEmpty() && m.groupValues[1].contains("\n"))
            }
        }
    }

    @Test fun noResourceNameIsDefinedTwice() {
        val mineFiles = setOf("slotcars_strings.xml", "fishing_strings.xml", "slotcars_fishing_hub_strings.xml", "slotcars_fishing_coach_strings.xml")
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
                    if (prev != null && (f.name in mineFiles || prev in mineFiles)) throw AssertionError("$n defined in both $prev and ${f.name}")
                }
            }
        }
    }

    // ---- tips and coach ----

    @Test fun tipsAreThreeLinesAndTheEnglishEqualsTheResource() {
        for ((id, lines) in SlotCarsFishingHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val name = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, unescape(en[name]!!))
                assertTrue(es[name]!!.isNotBlank() && es[name] != en[name])
            }
        }
        assertEquals(setOf("slot-cars", "fishing"), SlotCarsFishingHubSpecs.tips.keys)
    }

    @Test fun coachTextExistsForBothGamesInBothLocalesWithThreeToSixSteps() {
        for (id in ids) {
            val base = "coach_${id.replace('-', '_')}"
            val steps = (1..9).takeWhile { "${base}_s$it" in en }
            assertTrue("$id steps ${steps.size}", steps.size in 3..6)
            for (n in steps.map { "${base}_s$it" } + "${base}_fact") {
                assertTrue(en[n]!!.isNotBlank()); assertTrue(es[n]!!.isNotBlank()); assertNotEquals(en[n], es[n])
            }
            assertFalse("${base}_s${steps.size + 1}" in en)
        }
        // the coach strings live only in files the coach test scans
        val coachFiles = listOf("values", "values-es").map { File(moduleFile("res/$it").path, "slotcars_fishing_coach_strings.xml") }
        assertTrue(coachFiles.all { it.exists() && it.readText().contains("coach_fishing_fact") })
    }

    // ---- launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun slotCarsSeatsAndOptionsReachTheGame() {
        val s = SlotCarsFishingHubSpecs.slotCars
        val one = local(s, "cpu", "laps" to "10", "track" to "hill", "assist" to "step", "difficulty" to "HARD")
        assertEquals(2, one.launch.players.size)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, one.launch.mode)
        assertEquals("10", one.launch.options.extras["laps"]); assertEquals("hill", one.launch.options.extras["track"]); assertEquals("step", one.launch.options.extras["assist"])
        assertEquals(CpuDifficulty.HARD, one.launch.options.cpuDifficulty)
        assertEquals("slot-cars", one.route)
        val three = local(s, "cpu", "cpus" to "3")
        assertEquals(4, three.launch.players.size); assertEquals(3, three.launch.players.count { it.isBot })
        assertEquals("3", three.launch.options.extras["laps"]); assertEquals("meadow", three.launch.options.extras["track"]); assertEquals("off", three.launch.options.extras["assist"])
        for (humans in 2..4) {
            val p = local(s, "pass", "humans" to humans.toString())
            assertEquals(humans, p.launch.players.size); assertEquals(0, p.launch.players.count { it.isBot })
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode)
        }
    }

    @Test fun fishingSeatsAndOptionsReachTheGame() {
        val s = SlotCarsFishingHubSpecs.fishing
        val solo = local(s, "solo", "lake" to "reservoir", "rounds" to "8", "assist" to "true")
        assertEquals(1, solo.launch.players.size)
        assertEquals("reservoir", solo.launch.options.extras["lake"]); assertEquals("8", solo.launch.options.extras["rounds"]); assertEquals("true", solo.launch.options.extras["assist"])
        val cpu = local(s, "cpu", "cpus" to "2", "difficulty" to "EASY")
        assertEquals(3, cpu.launch.players.size); assertEquals(2, cpu.launch.players.count { it.isBot })
        assertEquals(CpuDifficulty.EASY, cpu.launch.options.cpuDifficulty)
        assertEquals("false", cpu.launch.options.extras["assist"]); assertEquals("pond", cpu.launch.options.extras["lake"])
        val pass = local(s, "pass", "humans" to "4")
        assertEquals(4, pass.launch.players.size); assertEquals("fishing", pass.route)
    }

    @Test fun optionValuesAreTheOnesTheGamesParse() {
        fun choice(spec: GameHubSpec, key: String) = spec.modes.first().options.filterIsInstance<HubOption.Choice>().first { it.key == key }
        val sc = SlotCarsFishingHubSpecs.slotCars
        assertEquals(SlotCarsRules.LAP_CHOICES.map { it.toString() }, choice(sc, "laps").choices.map { it.id })
        assertEquals(SlotTrack.ids, choice(sc, "track").choices.map { it.id })
        assertEquals(listOf("off", "slow", "step"), choice(sc, "assist").choices.map { it.id })
        val fi = SlotCarsFishingHubSpecs.fishing
        assertEquals(FishingLakes.lakeIds, choice(fi, "lake").choices.map { it.id })
        assertEquals(FishingRules.ROUND_CHOICES.map { it.toString() }, choice(fi, "rounds").choices.map { it.id })
    }

    @Test fun nearbyAndOnlineWouldSeatTwoToFour() {
        val s = SlotCarsFishingHubSpecs.slotCars
        assertEquals(4, s.modes.maxOf { it.maxSeats }); assertEquals(2, s.modes.filter { it.kind != HubModeKind.SOLO }.minOf { it.minSeats })
        val f = SlotCarsFishingHubSpecs.fishing
        assertEquals(4, f.modes.maxOf { it.maxSeats }); assertEquals(2, f.modes.filter { it.kind != HubModeKind.SOLO }.minOf { it.minSeats })
        assertTrue(SlotCarsFishingHubSpecs.all.all { it.family == HubFamily.ARCADE_PUZZLE && it.resumable })
        assertEquals(ids, SlotCarsFishingHubSpecs.all.map { it.gameId })
        // the engines' own seat limits agree with the hub
        assertEquals(2, SlotCarsGame().minPlayers); assertEquals(4, SlotCarsGame().maxPlayers)
        assertEquals(1, com.gamesuite.games.fishing.FishingGame().minPlayers); assertEquals(4, com.gamesuite.games.fishing.FishingGame().maxPlayers)
    }

    // ---- the accents the integrator should paste ----

    private val slotAccent = GameAccent(0xFFB5381E, 0xFFFF8F70)
    private val fishAccent = GameAccent(0xFF0B7A8F, 0xFF5CCBE6)

    @Test fun proposedAccentsAreUniqueAndReadable() {
        val others = GameThemes.accents.filterKeys { it != "slot-cars" && it != "fishing" }.values
        for (a in listOf(slotAccent, fishAccent)) {
            assertFalse(others.any { it.light == a.light }); assertFalse(others.any { it.dark == a.dark })
            for (surface in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(a.light, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (surface in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(a.dark, surface) >= GameThemes.MIN_ACCENT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.light), a.light) >= GameThemes.MIN_TEXT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.dark), a.dark) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        assertNotEquals(slotAccent, fishAccent)
    }
}
