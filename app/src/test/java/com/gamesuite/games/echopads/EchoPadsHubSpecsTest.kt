package com.gamesuite.games.echopads

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.core.summarizeHubChoices
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

/** Hub spec, launches, resources and accessibility facts of Echo Pads (the rules of HubLocalizationTest, applied to our own spec). */
class EchoPadsHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseDir(dir: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") strings[e.getAttribute("name")] = e.textContent
            }
        }
        return strings
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val spec: GameHubSpec = EchoPadsHubSpecs.echoPads
    private val mine = listOf("echo_pads_", "tip_echo_pads_", "coach_echo_pads_")
    private fun isMine(k: String) = mine.any { k.startsWith(it) }

    private fun texts(s: GameHubSpec): Set<String> {
        val out = linkedSetOf<String>()
        fun add(t: String) { if (t.isNotBlank()) out += t }
        add(s.title); add(s.tagline)
        for (mode in s.modes) {
            add(mode.title); add(mode.blurb)
            for (o in mode.options) {
                add(o.label); add(o.help)
                if (o is HubOption.Choice) o.choices.forEach { add(it.label); add(it.help) }
            }
        }
        return out
    }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")

    // ---- localization ----

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoDifferentHubTextsShareAKey() {
        assertEquals(emptyList<Any>(), texts(spec).groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.keys.filter { isMine(it) }
        assertTrue("only ${ours.size} strings", ours.size > 40)
        for (k in ours) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
        }
        assertEquals(emptySet<String>(), es.keys.filter { isMine(it) }.toSet() - en.keys)
    }

    @Test fun everyStringTheScreenReferencesExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = moduleFile("java/com/gamesuite/ui/EchoPadsScreen.kt").readText()
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 30)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val screenStrings = en.keys.filter { it.startsWith("echo_pads_") }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreenAndArtHaveNoHardCodedEnglish() {
        for (f in listOf("EchoPadsScreen.kt", "EchoPadsArt.kt")) {
            val lines = moduleFile("java/com/gamesuite/ui/$f").readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            for (line in lines) {
                for (m in Regex("(Text\\(\\s*|contentDescription\\s*=\\s*|label\\s*=\\s*)\"([^\"]*)\"").findAll(line)) {
                    assertFalse("hard-coded text in $f: ${m.value}", Regex("[A-Za-z]{2,}").containsMatchIn(m.groupValues[2]))
                }
            }
        }
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        listOf("values", "values-es").flatMap { d ->
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("echo_pads_") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun theResourceNamesAreUniqueAcrossOurFilesAndAllPrefixed() {
        for (d in listOf("values", "values-es")) {
            val names = moduleFile("res/$d").listFiles { f -> f.name.startsWith("echo_pads_") }!!.flatMap { f ->
                Regex("<string name=\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toList()
            }
            assertEquals("duplicate names in $d", names.size, names.toSet().size)
            assertTrue(names.all { it.startsWith("echo_pads_") || it.startsWith("tip_echo_pads_") || it.startsWith("coach_echo_pads_") || it.startsWith("hub_") })
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        assertEquals(setOf("echo-pads"), EchoPadsHubSpecs.tips.keys)
        for ((id, lines) in EchoPadsHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun theCoachCardHasThreeToSixStepsAndAFactInBothLanguages() {
        val id = "echo_pads"
        val steps = (1..6).takeWhile { "coach_${id}_s$it" in en }
        assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
        assertFalse("coach_${id}_s${steps.size + 1}" in es)
        for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
            assertTrue("coach $k", en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
            assertNotEquals("$k not translated", en[k], es[k])
        }
    }

    // ---- spec and launches ----

    private fun local(mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun itIsAFamilyGameForKidsFromFourWithThreeModesAndNoNetwork() {
        assertEquals("echo-pads", spec.gameId)
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(4, spec.minAge)
        assertEquals(HubFamily.ARCADE_PUZZLE, spec.family)
        assertTrue(spec.resumable)
        assertEquals(listOf(HubModeKind.SOLO, HubModeKind.PASS_AND_PLAY, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind })
        assertEquals(listOf("solo", "party", "duel"), spec.modes.map { it.id })
        assertFalse(spec.isNetworked)
        assertFalse("must stay out of the networked games", spec.gameId in GameHubSpecs.networkedGames)
        assertEquals(setOf("echo-pads", "echo-pads-duel"), spec.routes())
    }

    @Test fun soloLaunchesOneLocalPlayerOnTheSharedRoute() {
        val l = local("solo")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals(1, l.launch.players.size)
        assertFalse(l.launch.players[0].isBot)
        assertEquals("echo-pads", l.route)
    }

    @Test fun partyLaunchesTwoToSixPeopleAndTrimsAnythingElse() {
        assertEquals(2, local("party").launch.players.size)
        for (n in 2..6) {
            val l = local("party", "humans" to n.toString())
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
            assertEquals(n, l.launch.players.size)
            assertTrue(l.launch.players.none { it.isBot })
            assertEquals("echo-pads", l.route)
        }
        assertEquals(6, local("party", "humans" to "9").launch.players.size)
        assertEquals(2, local("party", "humans" to "1").launch.players.size)
    }

    @Test fun theDuelIsTwoPeopleOnItsOwnRoute() {
        val l = local("duel")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
        assertEquals(2, l.launch.players.size)
        assertTrue(l.launch.players.none { it.isBot })
        assertEquals("echo-pads-duel", l.route)
        assertEquals(2, spec.mode("duel")!!.minSeats); assertEquals(2, spec.mode("duel")!!.maxSeats)
    }

    @Test fun optionsReachTheScreenAsExtrasWithSafeDefaults() {
        for (m in listOf("solo", "party")) {
            val x = local(m).launch.options.extras
            assertEquals("normal", x["speed"]); assertEquals("false", x["forgiving"]); assertEquals("true", x["sound"])
        }
        val d = local("duel").launch.options.extras
        assertEquals("true", d["orient"]); assertEquals("normal", d["speed"])
        val custom = local("duel", "speed" to "slow", "forgiving" to "true", "sound" to "false", "orient" to "false").launch.options.extras
        assertEquals("slow", custom["speed"]); assertEquals("true", custom["forgiving"])
        assertEquals("false", custom["sound"]); assertEquals("false", custom["orient"])
        // A stale saved value falls back to the default instead of reaching the game.
        assertEquals("normal", local("solo", "speed" to "warp").launch.options.extras["speed"])
    }

    @Test fun theSoloAndPartySetupOffersNoOrientationOption() {
        for (m in listOf("solo", "party")) assertTrue(spec.mode(m)!!.options.none { it.key == "orient" })
    }

    @Test fun easyModeAndSoundAreSetupExtrasAndEasyIsOffByDefault() {
        for (m in spec.modes) {
            val o = m.options.associateBy { it.key }
            assertEquals(false, (o["forgiving"] as HubOption.Toggle).default)
            assertEquals(true, (o["sound"] as HubOption.Toggle).default)
            assertEquals("normal", (o["speed"] as HubOption.Choice).default)
            assertEquals(listOf("slow", "normal", "fast"), (o["speed"] as HubOption.Choice).choices.map { it.id })
        }
    }

    @Test fun everyModeSummarisesWithoutSurprises() {
        for (m in spec.modes) assertTrue(summarizeHubChoices(m, m.defaults()).startsWith(m.title))
    }

    // ---- identity colour ----

    @Test fun proposedAccentIsUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in EchoPadsHubSpecs.accents.keys }
        for ((id, pair) in EchoPadsHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }

    // ---- accessibility facts of the screen ----

    @Test fun theScreenHonoursReducedMotionLabelsEveryPadAndAnnouncesStatus() {
        val src = moduleFile("java/com/gamesuite/ui/EchoPadsScreen.kt").readText()
        assertTrue(src.contains("LocalReducedMotion"))
        assertTrue("pad semantics", src.contains("contentDescription") && src.contains("onClick(") && src.contains("Role.Button"))
        assertTrue("live region", src.contains("ClStatusLine"))
        assertTrue("haptic per pad", src.contains("HapticSignal.LIGHT_TICK") && src.contains("rememberHaptics"))
        assertTrue("sound is optional", src.contains("soundOn"))
        // Taps register on touch-down, not release.
        assertTrue(src.contains("awaitFirstDown"))
        val art = moduleFile("java/com/gamesuite/ui/EchoPadsArt.kt").readText()
        for (shape in listOf("CIRCLE", "TRIANGLE", "SQUARE")) assertTrue("shape $shape drawn", art.contains("EchoPads.$shape"))
    }
}
