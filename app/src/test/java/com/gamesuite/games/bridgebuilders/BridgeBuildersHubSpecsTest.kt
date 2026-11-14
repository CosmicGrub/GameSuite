package com.gamesuite.games.bridgebuilders

import com.gamesuite.core.GameAudience
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

/** Hub spec, launches, resources and identity colours of Bridge Builders (HubLocalizationTest's rules, applied to our own spec). */
class BridgeBuildersHubSpecsTest {
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
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f -> all.putAll(parse("$dir/${f.name}")) }
        return all
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val spec = BridgeBuildersHubSpecs.spec
    private fun mine(k: String) = k.startsWith("bridge_builders_") || k.startsWith("tip_bridge_builders_") || k.startsWith("coach_bridge_builders_")
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")

    private fun texts(): Set<String> {
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

    // ---- localization ----

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (text in texts()) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        assertEquals(emptyList<Any>(), texts().groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourHubFileDefinesOnlyKeysItsTextsNeedAndNothingElseDefinesThem() {
        val keys = texts().map { hubKey(it) }.toSet()
        val own = parse("res/values/bridge_builders_hub_strings.xml").keys
        val ownEs = parse("res/values-es/bridge_builders_hub_strings.xml").keys
        assertEquals(own, ownEs)
        assertTrue(keys.containsAll(own))
        // every other file in values/ must not define one of our keys again (a duplicate resource breaks the build)
        moduleFile("res/values").listFiles { f -> f.name.endsWith(".xml") && f.name != "bridge_builders_hub_strings.xml" }!!.forEach { f ->
            val other = parse("res/values/${f.name}").keys
            assertEquals("duplicate in ${f.name}", emptySet<String>(), other intersect own)
        }
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.keys.filter { mine(it) }
        assertTrue(ours.size > 35)
        for (k in ours) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
            assertTrue("$k not translated", en[k] != es[k] || k.endsWith("robot_tag"))
        }
        assertEquals(emptySet<String>(), es.keys.filter { mine(it) }.toSet() - en.keys)
    }

    private val screenFile = "java/com/gamesuite/ui/BridgeBuildersScreen.kt"

    @Test fun everyStringTheScreenReferencesExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(moduleFile(screenFile).readText()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 20)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val screenStrings = parse("res/values/bridge_builders_strings.xml").keys
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreenHasNoHardCodedEnglish() {
        val src = moduleFile(screenFile).readText()
        val literals = Regex("(?:Text\\(\\s*|text\\s*=\\s*|contentDescription\\s*=\\s*|description\\s*=\\s*)\"([^\"]*)\"").findAll(src)
        for (m in literals) {
            val visible = m.groupValues[1].replace(Regex("\\$\\{[^}]*\\}"), "").replace(Regex("\\$[A-Za-z]+"), "")
            assertFalse("hard-coded text: '${m.groupValues[1]}'", Regex("[A-Za-z]{3,}").containsMatchIn(visible))
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) for (n in listOf("bridge_builders_hub_strings.xml", "bridge_builders_strings.xml", "bridge_builders_coach_strings.xml")) {
            val xml = moduleFile("res/$d/$n").readText()
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $d/$n: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in BridgeBuildersHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun coachCardHasThreeToSixStepsAndAFactInBothLanguagesAndSaysSameScreenOnly() {
        val steps = (1..6).takeWhile { "coach_bridge_builders_s$it" in en }
        assertTrue("${steps.size} steps", steps.size in 3..6)
        assertFalse("coach_bridge_builders_s${steps.size + 1}" in es)
        for (k in steps.map { "coach_bridge_builders_s$it" } + "coach_bridge_builders_fact") {
            assertTrue(en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
            assertTrue("$k not translated", en[k] != es[k])
        }
        assertTrue(en["coach_bridge_builders_s${steps.size}"]!!.contains("Same screen only"))
        assertTrue(es["coach_bridge_builders_s${steps.size}"]!!.contains("Solo en la misma pantalla"))
    }

    @Test fun theResultTextHasNoWinnersOrLosersWording() {
        for (k in listOf("bridge_builders_status_win", "bridge_builders_status_lose")) {
            val t = en[k]!!.lowercase()
            assertFalse(t.contains("lose") || t.contains("lost") || t.contains("winner") || t.contains("loser") || t.contains("defeat"))
        }
        assertEquals("We did it!", en["bridge_builders_status_win"])
        assertEquals("Try again together", en["bridge_builders_status_lose"])
    }

    // ---- spec and launches ----

    private fun local(mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun itIsAKidsGameForFiveAndUpOnTheSameScreenOnly() {
        assertEquals("bridge-builders", spec.gameId)
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(5, spec.minAge)
        assertTrue(spec.resumable); assertFalse(spec.isNetworked); assertFalse(spec.skipsHub)
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
        assertTrue(spec.modes.all { it.route == "bridge-builders" && it.minSeats == 2 && it.maxSeats == 4 })
        assertEquals("Same screen", spec.mode("same")!!.title)
    }

    @Test fun helperRobotsFillTheLanesAndCarryTheirLevel() {
        val l = local("cpu", "difficulty" to "HARD", "cpus" to "3", "band" to "big", "relaxed" to "true", "handicap4" to "p2", "rotate" to "false")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
        assertEquals(listOf(false, true, true, true), l.launch.players.map { it.isBot })
        val x = l.launch.options.extras
        assertEquals("big", x["band"]); assertEquals("true", x["relaxed"]); assertEquals("p2", x["handicap4"]); assertEquals("false", x["rotate"])
        assertEquals(setOf<String>(), setOf("handicap", "handicap3").filter { it in x }.toSet()) // the other table sizes' choices never reach the game
        val d = local("cpu")
        assertEquals(2, d.launch.players.size)
        assertEquals("medium", d.launch.options.extras["band"]); assertEquals("none", d.launch.options.extras["handicap"])
        assertEquals("false", d.launch.options.extras["relaxed"]); assertEquals("true", d.launch.options.extras["rotate"])
    }

    @Test fun sameScreenSeatsPeopleFirstAndNeverMoreThanFourLanes() {
        val p = local("same", "humans" to "3", "cpus" to "1")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode)
        assertEquals(listOf(false, false, false, true), p.launch.players.map { it.isBot })
        val full = local("same", "humans" to "4", "cpus" to "2")
        assertEquals(4, full.launch.players.size); assertTrue(full.launch.players.none { it.isBot })
        assertEquals(2, local("same").launch.players.size)
    }

    @Test fun staleChoicesFallBackToTheirDefaults() {
        assertEquals("medium", local("cpu", "band" to "huge").launch.options.extras["band"])
        assertEquals("none", local("cpu", "handicap" to "p9").launch.options.extras["handicap"])
        // every choice id the spec offers is understood by the engine
        for (c in (spec.modes[0].options.first { it.key == "band" } as HubOption.Choice).choices) assertEquals(c.id, BridgeBand.fromId(c.id).id)
        for (c in (spec.modes[0].options.first { it.key == "handicap" } as HubOption.Choice).choices) {
            val parsed = BridgeHandicap.parse(c.id, 4)
            assertEquals(c.id != "none", parsed.any { it > 0.0 })
        }
    }

    @Test fun everyOptionKeyTheScreenReadsIsOnOffer() {
        val keys = spec.modes.flatMap { it.options }.map { it.key }.toSet()
        val src = moduleFile(screenFile).readText()
        for (k in listOf("band", "relaxed", "handicap", "handicap3", "handicap4", "rotate")) {
            assertTrue(k, k in keys)
            assertTrue(k, src.contains("extras[\"$k\"]"))
        }
    }

    // ---- handicap choices follow the table size ----

    private fun visibleHandicapKeys(mode: String, vararg c: Pair<String, Int>): List<String> {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return spec.mode(mode)!!.options.filter { it.key.startsWith("handicap") && it.visibleWhen!!.invoke(choices) }.map { it.key }
    }

    @Test fun onlyTheHandicapChoiceForTheRealNumberOfLanesIsOffered() {
        assertEquals(listOf("handicap"), visibleHandicapKeys("cpu", "cpus" to 1))
        assertEquals(listOf("handicap3"), visibleHandicapKeys("cpu", "cpus" to 2))
        assertEquals(listOf("handicap4"), visibleHandicapKeys("cpu", "cpus" to 3))
        assertEquals(listOf("handicap"), visibleHandicapKeys("same", "humans" to 2, "cpus" to 0))
        assertEquals(listOf("handicap3"), visibleHandicapKeys("same", "humans" to 2, "cpus" to 1))
        assertEquals(listOf("handicap4"), visibleHandicapKeys("same", "humans" to 4, "cpus" to 2)) // never more than 4 lanes
    }

    @Test fun everyOfferedLaneExists() {
        for (m in spec.modes) for ((key, lanes) in listOf("handicap" to 2, "handicap3" to 3, "handicap4" to 4)) {
            val o = m.options.first { it.key == key } as HubOption.Choice
            assertEquals(listOf("none") + (1..lanes).map { "p$it" }, o.choices.map { it.id })
            for (c in o.choices) assertEquals(c.id != "none", BridgeHandicap.parse(c.id, lanes).any { it > 0.0 })
        }
    }

    @Test fun aStaleHandicapForALaneThatIsGoneNeverReachesTheGame() {
        // "Lane 4" was picked at a 4-lane table, then the table shrank to 2 lanes
        val x = local("cpu", "handicap4" to "p4", "cpus" to "1").launch.options.extras
        assertFalse("handicap4" in x)
        assertEquals("none", BridgeHandicap.fromExtras(x["handicap"], x["handicap3"], x["handicap4"], 2))
    }

    @Test fun theHandicapIsReadForTheRealLaneCount() {
        assertEquals("p2", BridgeHandicap.fromExtras("p2", null, null, 2))
        assertEquals("p3", BridgeHandicap.fromExtras("none", "p3", "none", 3))
        assertEquals("p4", BridgeHandicap.fromExtras("none", "none", "p4", 4))
        assertEquals("p1", BridgeHandicap.fromExtras("p1", null, null, 4)) // older launch: the plain extra is the fallback
        assertEquals("none", BridgeHandicap.fromExtras(null, null, null, 3))
        assertEquals("p2", BridgeHandicap.fromExtras("p2", null, null, 1)) // a lone builder plays 2 lanes (with a helper): the plain extra applies
    }

    // ---- identity colour ----

    @Test fun proposedAccentIsUniqueLegibleAndReadableLabelledInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in BridgeBuildersHubSpecs.accents.keys }
        for ((id, pair) in BridgeBuildersHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }
}
