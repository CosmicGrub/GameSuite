package com.gamesuite.games.partypack

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

/** Hub specs, launches, resources and accessibility facts of Reaction Duel and Whack-a-Mole Race (the rules of HubLocalizationTest, applied to our own specs). */
class PartyReactionWhackHubSpecsTest {
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
    private val specs = PartyReactionWhackHubSpecs.all
    private val mine = listOf("rd_", "wm_", "coach_reaction_duel_", "coach_whack_a_mole_", "tip_reaction_duel_", "tip_whack_a_mole_")
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

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()
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

    @Test fun noTwoDifferentHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.keys.filter { isMine(it) }
        assertTrue("only ${ours.size} strings", ours.size > 90)
        for (k in ours) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
        }
        assertEquals(emptySet<String>(), es.keys.filter { isMine(it) }.toSet() - en.keys)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = listOf("ReactionDuelScreen.kt", "WhackAMoleScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val screenStrings = en.keys.filter { it.startsWith("rd_") || it.startsWith("wm_") }
        assertEquals(emptyList<String>(), screenStrings.filter { it !in used })
    }

    @Test fun theScreensHaveNoHardCodedEnglish() {
        for (f in listOf("ReactionDuelScreen.kt", "WhackAMoleScreen.kt", "ReactionDuelArt.kt", "WhackAMoleArt.kt", "ReactionWhackStage.kt")) {
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
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("reaction_") || f.name.startsWith("whack_") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        assertEquals(setOf("reaction-duel", "whack-a-mole"), PartyReactionWhackHubSpecs.tips.keys)
        for ((id, lines) in PartyReactionWhackHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[k]!!))
                assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLanguages() {
        for (id in listOf("reaction_duel", "whack_a_mole")) {
            val steps = (1..6).takeWhile { "coach_${id}_s$it" in en }
            assertTrue("$id has ${steps.size} steps", steps.size in 3..6)
            assertFalse("coach_${id}_s${steps.size + 1}" in es)
            for (k in steps.map { "coach_${id}_s$it" } + "coach_${id}_fact") {
                assertTrue("coach $k", en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
                assertNotEquals("$k not translated", en[k], es[k])
            }
        }
    }

    @Test fun theCoachSaysThisVersionIsSameScreenOnly() {
        for (id in listOf("coach_reaction_duel_s5", "coach_whack_a_mole_s5")) {
            assertTrue(en[id]!!.contains("Nearby") && en[id]!!.contains("Online"))
            assertTrue(es[id]!!.contains("Cercano") && es[id]!!.contains("En línea"))
        }
    }

    // ---- specs and launches ----

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun bothAreSameScreenFourSeatArcadeGamesWithNoNetworkModes() {
        assertEquals(setOf("reaction-duel", "whack-a-mole"), specs.map { it.gameId }.toSet())
        assertTrue(specs.all { it.family == HubFamily.ARCADE_PUZZLE && it.resumable })
        for (s in specs) {
            assertEquals(setOf(HubModeKind.PASS_AND_PLAY, HubModeKind.VS_CPU), s.modes.map { it.kind }.toSet())
            assertTrue(s.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
            assertTrue(s.modes.all { it.route == s.gameId && it.minSeats == 2 && it.maxSeats == 4 })
            assertEquals("Same screen", s.modes.first { it.kind == HubModeKind.PASS_AND_PLAY }.title)
            assertFalse(s.isNetworked)
            assertFalse("${s.gameId} must stay out of the networked games", s.gameId in GameHubSpecs.networkedGames)
        }
    }

    @Test fun sameScreenLaunchesEveryoneAtOnceAndPadsWithComputers() {
        val four = local(PartyReactionWhackHubSpecs.reactionDuel, "screen", "humans" to "4")
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, four.launch.mode)
        assertEquals(4, four.launch.players.size); assertTrue(four.launch.players.none { it.isBot })
        val mixed = local(PartyReactionWhackHubSpecs.whackAMole, "screen", "humans" to "2", "cpus" to "2", "difficulty" to "HARD")
        assertEquals(listOf(false, false, true, true), mixed.launch.players.map { it.isBot })
        assertEquals(CpuDifficulty.HARD, mixed.launch.options.cpuDifficulty)
        // One person alone gets a computer in the other zone.
        val solo = local(PartyReactionWhackHubSpecs.reactionDuel, "screen", "humans" to "1")
        assertEquals(2, solo.launch.players.size); assertTrue(solo.launch.players[1].isBot)
        // Too many seats are trimmed (computers first), never humans.
        val over = local(PartyReactionWhackHubSpecs.reactionDuel, "screen", "humans" to "4", "cpus" to "3")
        assertEquals(4, over.launch.players.size); assertTrue(over.launch.players.none { it.isBot })
    }

    @Test fun vsComputerLaunchesOneToThreeComputersAgainstYou() {
        for (cpus in 1..3) {
            val l = local(PartyReactionWhackHubSpecs.whackAMole, "cpu", "cpus" to cpus.toString(), "difficulty" to "EASY")
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
            assertEquals(cpus + 1, l.launch.players.size)
            assertEquals(listOf(false) + List(cpus) { true }, l.launch.players.map { it.isBot })
            assertEquals(CpuDifficulty.EASY, l.launch.options.cpuDifficulty)
        }
        assertEquals(2, local(PartyReactionWhackHubSpecs.reactionDuel, "cpu").launch.players.size)
    }

    @Test fun reactionDuelOptionsReachTheScreenAsExtrasWithSafeDefaults() {
        val d = local(PartyReactionWhackHubSpecs.reactionDuel, "screen")
        assertEquals("classic", d.launch.options.extras["variant"]); assertEquals("3", d.launch.options.extras["best"])
        assertEquals("false", d.launch.options.extras["relaxed"]); assertEquals("false", d.launch.options.extras["oneSwitch"]); assertEquals("true", d.launch.options.extras["orient"])
        val l = local(PartyReactionWhackHubSpecs.reactionDuel, "cpu", "variant" to "echo", "best" to "7", "relaxed" to "true", "oneSwitch" to "true", "orient" to "false")
        assertEquals("echo", l.launch.options.extras["variant"]); assertEquals("7", l.launch.options.extras["best"])
        assertEquals("true", l.launch.options.extras["relaxed"]); assertEquals("true", l.launch.options.extras["oneSwitch"]); assertEquals("false", l.launch.options.extras["orient"])
        // A stale saved value falls back to the default instead of reaching the game.
        assertEquals("classic", local(PartyReactionWhackHubSpecs.reactionDuel, "cpu", "variant" to "chaos").launch.options.extras["variant"])
        assertEquals("3", local(PartyReactionWhackHubSpecs.reactionDuel, "cpu", "best" to "99").launch.options.extras["best"])
    }

    @Test fun whackOptionsReachTheScreenAsExtrasWithSafeDefaults() {
        val d = local(PartyReactionWhackHubSpecs.whackAMole, "screen")
        assertEquals("90", d.launch.options.extras["time"]); assertEquals("3", d.launch.options.extras["grid"])
        val l = local(PartyReactionWhackHubSpecs.whackAMole, "cpu", "time" to "120", "grid" to "4", "relaxed" to "true")
        assertEquals("120", l.launch.options.extras["time"]); assertEquals("4", l.launch.options.extras["grid"]); assertEquals("true", l.launch.options.extras["relaxed"])
        assertEquals("90", local(PartyReactionWhackHubSpecs.whackAMole, "cpu", "time" to "45").launch.options.extras["time"])
    }

    @Test fun theComputerLevelIsOnlyOfferedWhenThereIsAComputerInTheGame() {
        val mode = PartyReactionWhackHubSpecs.reactionDuel.mode("screen")!!
        val level = mode.options.first { it.key == "difficulty" }
        assertFalse(level.visibleWhen!!(HubChoices().with("humans", 3).with("cpus", 0)))
        assertTrue(level.visibleWhen!!(HubChoices().with("humans", 2).with("cpus", 1)))
        assertTrue(level.visibleWhen!!(HubChoices().with("humans", 1).with("cpus", 0)))
    }

    @Test fun everyModeSummarisesWithoutSurprises() {
        for (s in specs) for (m in s.modes) {
            val text = summarizeHubChoices(m, m.defaults())
            assertTrue(text.startsWith(m.title))
        }
    }

    @Test fun relaxedAndOneSwitchAreOffByDefaultAndOrientationIsOn() {
        for (s in specs) for (m in s.modes) {
            val o = m.options.associateBy { it.key }
            assertEquals(false, (o["relaxed"] as HubOption.Toggle).default)
            assertEquals(false, (o["oneSwitch"] as HubOption.Toggle).default)
            assertEquals(true, (o["orient"] as HubOption.Toggle).default)
        }
    }

    // ---- identity colours ----

    @Test fun proposedAccentsAreUniqueLegibleAndReadableLabelsInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in PartyReactionWhackHubSpecs.accents.keys }
        for ((id, pair) in PartyReactionWhackHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        val all = PartyReactionWhackHubSpecs.accents.values.toList()
        assertEquals(all.size, all.map { it.first }.toSet().size); assertEquals(all.size, all.map { it.second }.toSet().size)
    }

    @Test fun seatColoursAreColourBlindSafeAndEveryZoneAlsoCarriesANameAndAShape() {
        // Okabe-Ito vermillion and blue: well separated in luminance as well as hue.
        assertTrue(kotlin.math.abs(Wcag.luminance(0xFFD55E00) - Wcag.luminance(0xFF0072B2)) > 0.05)
        val stage = moduleFile("java/com/gamesuite/ui/ReactionWhackStage.kt").readText()
        assertTrue(stage.contains("RwSeatColors"))
        for (f in listOf("ReactionDuelScreen.kt", "WhackAMoleScreen.kt")) {
            val src = moduleFile("java/com/gamesuite/ui/$f").readText()
            assertTrue("$f seat badge", src.contains("ClSeatBadge"))   // a shape per seat
            assertTrue("$f zone semantics", src.contains("contentDescription") && src.contains("onClick("))
            assertTrue("$f live region", src.contains("ClStatusLine"))
            assertTrue("$f reduced motion", src.contains("LocalReducedMotion"))
        }
    }
}
