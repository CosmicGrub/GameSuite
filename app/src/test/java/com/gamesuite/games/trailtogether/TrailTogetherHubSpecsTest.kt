package com.gamesuite.games.trailtogether

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

/** Hub spec, launch, resources, coach, tips and accessibility facts of Trail Together (tests T36 to T41 and more). */
class TrailTogetherHubSpecsTest {
    private val spec = TrailTogetherHubSpecs.spec

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

    private fun isMine(k: String) = k.startsWith("trail_together_") || k.startsWith("coach_trail_together_") || k.startsWith("tip_trail_together_")
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")

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

    // ---- the spec ----

    @Test
    fun specIsKidsAudienceWithMinAgeFour() {
        assertEquals("trail-together", spec.gameId)
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(4, spec.minAge)
        assertEquals(HubFamily.BOARD, spec.family)
        assertTrue(spec.resumable)
        assertEquals(setOf("trail-together"), TrailTogetherHubSpecs.all.map { it.gameId }.toSet())
    }

    @Test
    fun specIsSameScreenOnlyAndNotNetworked() {
        assertFalse(spec.isNetworked)
        assertTrue(spec.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE || it.kind == HubModeKind.DAILY })
        assertFalse("trail-together must stay out of the networked games", spec.gameId in GameHubSpecs.networkedGames)
        assertEquals(setOf("trail-together"), spec.routes())
        assertEquals(listOf("solo", "cpu", "same"), spec.modes.map { it.id })
        assertEquals(HubStart.IMMEDIATE, spec.mode("solo")!!.start)
        assertEquals(HubStart.IMMEDIATE, spec.mode("cpu")!!.start)
        assertEquals(HubStart.READY_SCREEN, spec.mode("same")!!.start)
    }

    @Test
    fun modeSeatBoundsMatchEngineLimits() {
        for (m in spec.modes) {
            assertTrue(m.minSeats >= TrailRules.MIN_SEATS)
            assertTrue(m.maxSeats <= TrailRules.MAX_SEATS)
        }
        assertEquals(1 to 1, spec.mode("solo")!!.minSeats to spec.mode("solo")!!.maxSeats)
        assertEquals(2 to 4, spec.mode("cpu")!!.minSeats to spec.mode("cpu")!!.maxSeats)
        assertEquals(2 to 4, spec.mode("same")!!.minSeats to spec.mode("same")!!.maxSeats)
    }

    private fun local(modeId: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(modeId)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test
    fun optionKeysAreBandHandicapHintsRotateAndHelperLevel() {
        val same = spec.mode("same")!!.options.map { it.key }.toSet()
        assertTrue(same.containsAll(setOf("band", "hints", "rotate", "humans", "cpus", "difficulty", "handicap", "handicap3", "handicap4")))
        val cpu = spec.mode("cpu")!!.options.map { it.key }.toSet()
        assertTrue(cpu.containsAll(setOf("band", "hints", "cpus", "difficulty", "handicap", "handicap3", "handicap4")))
        assertFalse("one person holding the device has nobody opposite", "rotate" in cpu)
        assertEquals(setOf("band", "hints"), spec.mode("solo")!!.options.map { it.key }.toSet())
        val band = spec.mode("same")!!.options.first { it.key == "band" } as HubOption.Choice
        assertEquals(TrailBand.entries.map { it.id }.toSet(), band.choices.map { it.id }.toSet())
        assertEquals("medium", band.default)
        val hints = spec.mode("same")!!.options.first { it.key == "hints" } as HubOption.Choice
        assertEquals(setOf("auto", "on", "off"), hints.choices.map { it.id }.toSet())
        assertEquals("auto", hints.default)
    }

    @Test
    fun launchesCarryBandHintsRotateAndTheHandicapOfTheTableOnly() {
        // Same screen, 3 people: the "3 seats" handicap is on offer and arrives; the others never do
        val l = local("same", "humans" to "3", "cpus" to "0", "handicap3" to "p3", "handicap" to "p2", "handicap4" to "p4", "band" to "big", "hints" to "off", "rotate" to "false")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
        assertEquals(3, l.launch.players.size)
        val x = l.launch.options.extras
        assertEquals("big", x["band"]); assertEquals("off", x["hints"]); assertEquals("false", x["rotate"])
        assertEquals("p3", x["handicap3"])
        assertFalse("a handicap for a table size that is not being played must not reach the game", "handicap" in x || "handicap4" in x)
        assertEquals("p3", TrailHandicap.fromExtras(x, l.launch.players.size))
        // 2 people plus 1 helper is a 3-seat table; 1 person plus 1 helper is a 2-seat table
        assertEquals("p2", TrailHandicap.fromExtras(local("same", "humans" to "2", "cpus" to "1", "handicap3" to "p2").launch.options.extras, 3))
        val cpu = local("cpu", "cpus" to "1", "handicap" to "p2", "handicap3" to "p3")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, cpu.launch.mode)
        assertEquals(2, cpu.launch.players.size)
        assertEquals("p2", TrailHandicap.fromExtras(cpu.launch.options.extras, 2))
        assertFalse("handicap3" in cpu.launch.options.extras)
        // a stale choice for a seat that is not there falls back to nobody
        val stale = local("cpu", "cpus" to "1", "handicap" to "p4")
        assertEquals("none", TrailHandicap.fromExtras(stale.launch.options.extras, 2))
        // solo: one person, never a handicap, defaults
        val solo = local("solo")
        assertEquals(1, solo.launch.players.size)
        assertEquals("medium", solo.launch.options.extras["band"]); assertEquals("auto", solo.launch.options.extras["hints"])
        assertEquals("none", TrailHandicap.fromExtras(solo.launch.options.extras, 1))
        // helpers are the computer seats and always trail the people
        val mixed = local("same", "humans" to "2", "cpus" to "2")
        assertEquals(listOf(false, false, true, true), mixed.launch.players.map { it.isBot })
        assertEquals("trail-together", mixed.route)
    }

    @Test
    fun theModesSummariseWithoutSurprises() {
        for (m in spec.modes) assertTrue(summarizeHubChoices(m, m.defaults()).startsWith(m.title))
    }

    // ---- localization ----

    @Test
    fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test
    fun noTwoDifferentHubTextsShareAKey() {
        assertEquals(emptyList<Any>(), texts(spec).groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test
    fun englishAndSpanishStringFilesDefineTheSameKeys() {
        val ours = en.keys.filter { isMine(it) }
        assertTrue("only ${ours.size} strings", ours.size > 100)
        assertEquals(emptySet<String>(), ours.toSet() - es.keys)
        assertEquals(emptySet<String>(), es.keys.filter { isMine(it) }.toSet() - en.keys)
        for (k in ours) {
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
        }
        // the hub strings file of each locale only holds hub keys of this game's texts
        val hubFile = { dir: String -> moduleFile("res/$dir/trail_together_hub_strings.xml").readText() }
        for (dir in listOf("values", "values-es")) {
            val names = Regex("name=\"([^\"]+)\"").findAll(hubFile(dir)).map { it.groupValues[1] }.toList()
            val mine = texts(spec).map { hubKey(it) }.toSet()
            assertTrue("$dir hub file has keys that are not this game's", mine.containsAll(names))
        }
    }

    @Test
    fun spanishIsReallyTranslatedExceptNamesAndShortLabels() {
        val allowed = setOf("trail_together_pal_name_1", "trail_together_pal_name_2", "trail_together_pal_name_3", "trail_together_pal_name_4")
        for (k in en.keys.filter { isMine(it) && it !in allowed }) {
            if (en[k]!!.length > 12) assertNotEquals("$k not translated", en[k], es[k])
        }
    }

    @Test
    fun noStringValueContainsTodoMarker() {
        for ((dir, map) in listOf("values" to en, "values-es" to es)) {
            for ((k, v) in map) if (isMine(k)) {
                assertFalse("$dir/$k has a TODO marker", v.contains("TODO", ignoreCase = true))
                assertFalse("$dir/$k has a placeholder marker", v.contains("lorem", ignoreCase = true) || v.contains("xxx", ignoreCase = true))
            }
        }
        // the one reviewer note lives in an XML comment, which the parser above never sees
        assertTrue(moduleFile("res/values-es/trail_together_strings.xml").readText().contains("TODO translate"))
    }

    @Test
    fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) {
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("trail_together_") }!!.map { it.readText() }.forEach { xml ->
                Regex(">([^<]*)<").findAll(xml).forEach { m ->
                    assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }

    @Test
    fun theDocsTextsAreHereVerbatim() {
        assertEquals("Trail Together", en["trail_together_title"])
        assertEquals("Light the trail and get home before the fog!", en["trail_together_tagline"])
        assertEquals("Planning ahead, matching colours and shapes, and taking turns as a team.", en["trail_together_teaches"])
        assertEquals(8, (1..9).count { "trail_together_tutorial_$it" in en })
        for (i in 1..8) assertTrue("tutorial $i under 120 characters", en["trail_together_tutorial_$i"]!!.length < 120)
        assertEquals(4, (1..5).count { "trail_together_pal_name_$it" in en })
        assertEquals(listOf("Nutmeg", "Pip", "Clover", "Burr"), (1..4).map { en["trail_together_pal_name_$it"] })
    }

    @Test
    fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        assertEquals(setOf("trail-together"), TrailTogetherHubSpecs.tips.keys)
        for ((id, lines) in TrailTogetherHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test
    fun coachCardHasFiveStepsAndAFactInBothLanguagesInACoachOnlyFile() {
        val steps = (1..6).takeWhile { "coach_trail_together_s$it" in en }
        assertEquals(5, steps.size)
        assertFalse("coach_trail_together_s6" in es)
        for (k in steps.map { "coach_trail_together_s$it" } + "coach_trail_together_fact") {
            assertTrue("coach $k", en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
            assertNotEquals("$k not translated", en[k], es[k])
        }
        for (d in listOf("values", "values-es")) {
            val xml = moduleFile("res/$d/trail_together_coach_strings.xml").readText()
            val names = Regex("<string name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(6, names.size)
            assertTrue("the coach file holds only coach_ keys", names.all { it.startsWith("coach_trail_together_") })
        }
    }

    // ---- the screen ----

    private val screenFiles = listOf("TrailTogetherScreen.kt", "TrailTogetherArt.kt")
    private fun screenSource() = screenFiles.joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }

    @Test
    fun everyStringTheScreenReferencesExistsInBothLocalesAndNoScreenStringIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        // title and tagline are for the hub card and the parent dashboard; the rest must be on the screen
        val exempt = setOf("trail_together_title", "trail_together_tagline")
        val screenStrings = en.keys.filter { it.startsWith("trail_together_") && it !in exempt }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test
    fun theScreenHasNoHardCodedEnglish() {
        for (f in screenFiles) {
            val lines = moduleFile("java/com/gamesuite/ui/$f").readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            for (line in lines) {
                for (m in Regex("(Text\\(\\s*|contentDescription\\s*=\\s*|label\\s*=\\s*|stateDescription\\s*=\\s*)\"([^\"]*)\"").findAll(line)) {
                    assertFalse("hard-coded text in $f: ${m.value}", Regex("[A-Za-z]{2,}").containsMatchIn(m.groupValues[2]))
                }
            }
        }
    }

    @Test
    fun theScreenUsesThemeTypographyAndNoFixedSpTextSizes() {
        val src = screenSource()
        assertFalse("hard-coded sp would ignore the text-size setting", Regex("\\d\\s*\\.sp\\b").containsMatchIn(src))
        assertFalse(src.contains("fontSize ="))
        assertTrue(src.contains("MaterialTheme.typography"))
    }

    @Test
    fun theScreenKeepsItsAccessibilityAndSafetyPromises() {
        val src = screenSource()
        assertTrue("reduced motion", src.contains("LocalReducedMotion"))
        assertTrue("game speed", src.contains("LocalGameSpeed"))
        assertTrue("56dp controls", src.contains("56.dp"))
        assertTrue("landscape layout", src.contains("landscape"))
        assertTrue("fitBoard for the trail", src.contains("fitBoard("))
        assertTrue("live regions", src.contains("LiveRegionMode.Polite"))
        assertTrue("pause on dialogs and focus loss", src.contains("isWindowFocused") && src.contains("game.pause()") && src.contains("game.resume()"))
        assertTrue("a second tap is refused by the game until settled", src.contains("holdInputUntilSettled = true") && src.contains("game.settled()"))
        assertTrue("TalkBack gets descriptions", src.contains("contentDescription"))
        assertTrue("merged card semantics are not read twice", src.contains("clearAndSetSemantics"))
        assertTrue("only the public ProceduralSfx API", src.contains("rememberProceduralSfx") && !src.contains("SfxPlayer"))
        assertTrue("the result waits for the animation", src.contains("v.over && !animating"))
        // animated values are never read in composition: they live in draw / graphicsLayer lambdas
        assertFalse("no .value of an Animatable in a Text or Modifier chain", Regex("Text\\([^)]*(legT|fogShown|popT)\\.value").containsMatchIn(src))
    }

    // ---- identity colour ----

    @Test
    fun proposedAccentIsUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in TrailTogetherHubSpecs.accents.keys }
        for ((id, pair) in TrailTogetherHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun theScreensOwnColoursReadWellOnTheirBackgrounds() {
        // Mirrors TrailTheme in TrailTogetherArt.kt: ink and soft text on background and surface, label on the accent button.
        val light = listOf(0xFF3B2F22L to 0xFFFFF6E5L, 0xFF3B2F22L to 0xFFFFFDF7L, 0xFF6E5F4DL to 0xFFFFF6E5L, 0xFF6E5F4DL to 0xFFFFFDF7L, 0xFFFFFFFFL to 0xFF2E6B3FL, 0xFF2E6B3FL to 0xFFFFFDF7L)
        val dark = listOf(0xFFF6EEDDL to 0xFF2A241BL, 0xFFF6EEDDL to 0xFF3A3226L, 0xFFB9AE98L to 0xFF2A241BL, 0xFFB9AE98L to 0xFF3A3226L, 0xFF10301AL to 0xFF8FD6A0L, 0xFF8FD6A0L to 0xFF3A3226L)
        for ((fg, bg) in light + dark) assertTrue("${fg.toString(16)} on ${bg.toString(16)}", Wcag.contrast(fg, bg) >= 4.5)
    }
}
