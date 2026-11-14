package com.gamesuite.games.actitout

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
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

/** Hub spec, launch, resources, coach, tips and accessibility facts of Act It Out. */
class ActItOutHubSpecsTest {
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
    private val spec = ActItOutHubSpecs.actItOut

    private fun isMine(k: String) = k.startsWith("act_it_out_") || k.startsWith("coach_act_it_out_") || k.startsWith("tip_act_it_out_")

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
        val all = texts(spec)
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.keys.filter { isMine(it) }
        assertTrue("only ${ours.size} strings", ours.size > 250)
        for (k in ours) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
        }
        assertEquals(emptySet<String>(), es.keys.filter { isMine(it) }.toSet() - en.keys)
    }

    @Test fun everyStringTheScreenReferencesExistsInBothLocalesAndNoScreenStringIsDead() {
        val src = moduleFile("java/com/gamesuite/ui/ActItOutScreen.kt").readText()
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 40)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val screenStrings = en.keys.filter { it.startsWith("act_it_out_") && !it.startsWith("act_it_out_card_") }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreenHasNoHardCodedEnglish() {
        for (f in listOf("ActItOutScreen.kt", "ActItOutArt.kt")) {
            val lines = moduleFile("java/com/gamesuite/ui/$f").readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            for (line in lines) {
                for (m in Regex("(Text\\(\\s*|contentDescription\\s*=\\s*|label\\s*=\\s*)\"([^\"]*)\"").findAll(line)) {
                    assertFalse("hard-coded text in $f: ${m.value}", Regex("[A-Za-z]{2,}").containsMatchIn(m.groupValues[2]))
                }
            }
        }
    }

    @Test fun theScreenUsesThemeTypographyAndNoFixedSpTextSizes() {
        val src = moduleFile("java/com/gamesuite/ui/ActItOutScreen.kt").readText()
        assertFalse("hard-coded sp would ignore the text-size setting", Regex("\\d\\s*\\.sp\\b").containsMatchIn(src))
        assertFalse(src.contains("fontSize ="))
        assertTrue(src.contains("MaterialTheme.typography"))
    }

    @Test fun theScreenKeepsItsAccessibilityPromises() {
        val src = moduleFile("java/com/gamesuite/ui/ActItOutScreen.kt").readText()
        assertTrue("live status line", src.contains("ClStatusLine"))
        assertTrue("reduced motion", src.contains("LocalReducedMotion"))
        assertTrue("56dp controls", src.contains("56.dp"))
        assertTrue("landscape layout", src.contains("wide"))
        assertTrue("tap fallback for the hold pad", src.contains("detectTapGestures(onTap"))
        assertTrue("screen reader activation of the pad", src.contains("onClick(label"))
        // The timer's seconds are visual only, so a screen reader never reads them every second.
        assertTrue(src.contains("clearAndSetSemantics"))
        // Only the public ProceduralSfx API is used.
        assertTrue(src.contains("rememberProceduralSfx"))
        assertFalse(src.contains("SfxPlayer"))
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) {
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("act_it_out_") }!!.map { it.readText() }.forEach { xml ->
                Regex(">([^<]*)<").findAll(xml).forEach { m ->
                    assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        assertEquals(setOf("act-it-out"), ActItOutHubSpecs.tips.keys)
        for ((id, lines) in ActItOutHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun coachCardHasThreeToSixStepsAndAFactInBothLanguages() {
        val steps = (1..6).takeWhile { "coach_act_it_out_s$it" in en }
        assertTrue("${steps.size} steps", steps.size in 3..6)
        assertFalse("coach_act_it_out_s${steps.size + 1}" in es)
        for (k in steps.map { "coach_act_it_out_s$it" } + "coach_act_it_out_fact") {
            assertTrue("coach $k", en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
            assertNotEquals("$k not translated", en[k], es[k])
        }
    }

    // ---- spec and launches ----

    private fun local(vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.modes.first(), choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun theSpecIsAFamilyPartyGameForAgesFourAndUpOnOneDevice() {
        assertEquals("act-it-out", spec.gameId)
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(4, spec.minAge)
        assertTrue(spec.resumable)
        assertEquals(HubFamily.ARCADE_PUZZLE, spec.family)
        assertFalse(spec.isNetworked)
        assertFalse("act-it-out must stay out of the networked games", spec.gameId in GameHubSpecs.networkedGames)
        val mode = spec.modes.single()
        assertEquals(HubModeKind.PASS_AND_PLAY, mode.kind)
        assertEquals(HubStart.READY_SCREEN, mode.start)
        assertEquals("act-it-out", mode.route)
        assertEquals(3, mode.minSeats)
        assertEquals(12, mode.maxSeats)
        assertEquals(setOf("act-it-out"), ActItOutHubSpecs.all.map { it.gameId }.toSet())
    }

    @Test fun threeToTwelvePeopleLaunchAsHumansWithNoComputers() {
        for (n in 3..12) {
            val l = local("humans" to n.toString())
            assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
            assertEquals(n, l.launch.players.size)
            assertTrue(l.launch.players.none { it.isBot })
            assertEquals("act-it-out", l.route)
        }
        // Out-of-range saved values are clamped into the table size.
        assertEquals(12, local("humans" to "40").launch.players.size)
        assertEquals(3, local("humans" to "1").launch.players.size)
    }

    @Test fun hubOptionsReachTheScreenAsExtrasWithSafeDefaults() {
        val d = local().launch.options.extras
        assertEquals("teams", d["format"]); assertEquals("60", d["time"]); assertEquals("3", d["rounds"])
        assertEquals("everyone", d["band"]); assertEquals("false", d["handicap"])
        assertEquals("true", d["buzzer"]); assertEquals("true", d["hold"])
        val x = local("format" to "ffa", "time" to "90", "rounds" to "5", "band" to "little", "handicap" to "true", "buzzer" to "false", "hold" to "false").launch.options.extras
        assertEquals("ffa", x["format"]); assertEquals("90", x["time"]); assertEquals("5", x["rounds"])
        assertEquals("little", x["band"]); assertEquals("true", x["handicap"])
        assertEquals("false", x["buzzer"]); assertEquals("false", x["hold"])
        // A stale choice falls back to the default instead of reaching the game, and the screen's parser agrees.
        val stale = local("format" to "chaos", "time" to "45", "rounds" to "99", "band" to "none").launch.options.extras
        assertEquals("teams", stale["format"]); assertEquals("60", stale["time"]); assertEquals("5", stale["rounds"]); assertEquals("everyone", stale["band"])
        assertEquals(ActItOutDefaults(rounds = 5), ActItOutDefaults.fromExtras(stale))
    }

    @Test fun everyChoiceIdTheHubOffersIsOneTheEngineUnderstands() {
        val opts = spec.modes.first().options.associateBy { it.key }
        val formats = (opts["format"] as HubOption.Choice).choices.map { it.id }
        assertEquals(ActItOutFormat.values().map { it.id }.toSet(), formats.toSet())
        val bands = (opts["band"] as HubOption.Choice).choices.map { it.id }
        assertEquals(ActItOutBand.values().map { it.id }.toSet(), bands.toSet())
        val times = (opts["time"] as HubOption.Choice).choices.map { it.id.toInt() }
        assertEquals(ActItOutRules.TIME_CHOICES, times)
        val rounds = opts["rounds"] as HubOption.Stepper
        assertEquals(ActItOutRules.MIN_ROUNDS, rounds.min); assertEquals(ActItOutRules.MAX_ROUNDS, rounds.max)
    }

    @Test fun theModeSummarisesWithoutSurprises() {
        val m = spec.modes.first()
        assertTrue(summarizeHubChoices(m, m.defaults()).startsWith(m.title))
    }

    @Test fun theBuzzerAndHoldSwitchesAreOnByDefaultAndTheBonusIsOff() {
        val o = spec.modes.first().options.associateBy { it.key }
        assertEquals(true, (o["buzzer"] as HubOption.Toggle).default)
        assertEquals(true, (o["hold"] as HubOption.Toggle).default)
        assertEquals(false, (o["handicap"] as HubOption.Toggle).default)
    }

    // ---- identity colour ----

    @Test fun proposedAccentIsUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in ActItOutHubSpecs.accents.keys }
        for ((id, pair) in ActItOutHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }

    @Test fun theScreensOwnColoursReadWellOnTheirBackgrounds() {
        // Mirrors the palette in ActItOutScreen.kt (text on bg and card, accent label text) for both themes.
        val light = listOf(0xFF2B1E26L to 0xFFFBF5EEL, 0xFF2B1E26L to 0xFFF1E4ECL, 0xFFFFFFFFL to 0xFF8E2B6DL, 0xFF6E5C66L to 0xFFF1E4ECL, 0xFFB3261EL to 0xFFFBF5EEL)
        val dark = listOf(0xFFF3E9F0L to 0xFF17131AL, 0xFFF3E9F0L to 0xFF2A2230L, 0xFF101014L to 0xFFF08CCBL, 0xFFB7A6B1L to 0xFF2A2230L, 0xFFFF7A70L to 0xFF17131AL)
        for ((fg, bg) in light + dark) assertTrue("${fg.toString(16)} on ${bg.toString(16)}", Wcag.contrast(fg, bg) >= 4.5)
    }
}
