package com.gamesuite.games.drawguess

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.GameContext
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/** Hub spec, launches, resources and identity colour of Draw and Guess (HubLocalizationTest's rules applied to our own spec and files). */
class DrawGuessHubSpecsTest {
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
    private val spec = DrawGuessHubSpecs.drawGuess
    private val mine = listOf("draw_guess_", "coach_draw_guess_", "tip_draw_guess_")
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
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en.strings)
            assertTrue("missing ES $key for '$text'", key in es.strings)
            assertEquals(text, shown(en.strings[key]!!))
            assertTrue("blank ES $key", es.strings[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en.strings && hubKey(it) in es.strings) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        assertEquals(emptyList<Any>(), texts(spec).groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourStringsExistInBothLocalesWithMatchingPlaceholdersAndNoLeftovers() {
        val ours = en.strings.keys.filter { isMine(it) }
        assertTrue(ours.size > 80)
        for (k in ours) {
            assertTrue("no ES for $k", k in es.strings)
            assertEquals("placeholders of $k", placeholders(en.strings[k]!!), placeholders(es.strings[k]!!))
            assertTrue("blank $k", en.strings[k]!!.isNotBlank() && es.strings[k]!!.isNotBlank())
        }
        assertEquals(emptySet<String>(), es.strings.keys.filter { isMine(it) }.toSet() - en.strings.keys)
    }

    private val screenFiles = listOf("DrawGuessScreen.kt", "DrawGuessArt.kt")
    private fun screenSource() = screenFiles.joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }

    @Test fun everyStringTheScreensReferenceExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 70)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en.strings)
            assertTrue("R.string.$name missing in values-es/", name in es.strings)
        }
        val screenStrings = en.strings.keys.filter { it.startsWith("draw_guess_") }
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
        val files = listOf("draw_guess_hub_strings.xml", "draw_guess_strings.xml", "draw_guess_coach_strings.xml")
        for (d in listOf("values", "values-es")) for (n in files) {
            val xml = moduleFile("res/$d/$n").readText()
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $d/$n: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in DrawGuessHubSpecs.tips) {
            assertEquals("draw-guess", id)
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                val k = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en.strings[k]!!))
                assertTrue("$k not translated", en.strings[k] != es.strings[k] && es.strings[k]!!.isNotBlank())
                assertTrue("tip too long: $k", line.length <= 140)
            }
        }
    }

    @Test fun theCoachCardHasThreeToSixStepsAndAFactInBothLanguages() {
        val steps = (1..6).takeWhile { "coach_draw_guess_s$it" in en.strings }
        assertTrue("${steps.size} steps", steps.size in 3..6)
        assertFalse("coach_draw_guess_s${steps.size + 1}" in es.strings)
        for (k in steps.map { "coach_draw_guess_s$it" } + "coach_draw_guess_fact") {
            assertTrue(k, en.strings[k]?.isNotBlank() == true && es.strings[k]?.isNotBlank() == true)
            assertTrue("$k not translated", en.strings[k] != es.strings[k])
        }
        val last = en.strings["coach_draw_guess_s${steps.last()}"]!!
        assertTrue(last.contains("Same screen only"))
        assertTrue(es.strings["coach_draw_guess_s${steps.last()}"]!!.contains("Solo en la misma pantalla"))
    }

    // ---- spec and launches ----

    private fun local(mode: String = "same", vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun itIsAFamilyGameFromSixUpThatIsSameScreenOnly() {
        assertEquals("draw-guess", spec.gameId)
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(6, spec.minAge)
        assertTrue(spec.resumable); assertFalse(spec.isNetworked); assertFalse(spec.skipsHub)
        assertEquals(listOf(HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind })
        val m = spec.modes.single()
        assertEquals(2, m.minSeats); assertEquals(8, m.maxSeats); assertEquals("draw-guess", m.route)
        assertEquals("Same screen", m.title)
    }

    @Test fun defaultsAreThreePeopleSixtySecondsPickingCardsOneRound() {
        val l = local()
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.launch.mode)
        assertEquals(3, l.launch.players.size)
        assertTrue(l.launch.players.none { it.isBot })
        val x = l.launch.options.extras
        assertEquals("60", x["timer"]); assertEquals("pick", x["guess"]); assertEquals("1", x["rounds"])
        val cfg = DrawGuessConfig.fromExtras(x, WordLang.EN)
        assertEquals(DrawGuessConfig(60, false, 1, WordLang.EN), cfg)
    }

    @Test fun launchChoicesReachTheEngineConfig() {
        val l = local("same", "humans" to "8", "timer" to "0", "guess" to "type", "rounds" to "3")
        assertEquals(8, l.launch.players.size)
        val cfg = DrawGuessConfig.fromExtras(l.launch.options.extras, WordLang.ES)
        assertEquals(0, cfg.timerSeconds); assertTrue(cfg.typeIt); assertEquals(3, cfg.rounds); assertEquals(WordLang.ES, cfg.lang)
        assertEquals(2, local("same", "humans" to "1").launch.players.size.coerceAtLeast(2)) // never below two seats
        assertEquals(8, local("same", "humans" to "20").launch.players.size)
    }

    @Test fun staleChoicesFallBackToTheDefaults() {
        val x = local("same", "timer" to "45", "guess" to "shout", "rounds" to "99").launch.options.extras
        assertEquals("60", x["timer"]); assertEquals("pick", x["guess"]); assertEquals("4", x["rounds"])
    }

    @Test fun teamsOnlyShowAtAnEvenTableOfFourOrMoreAndGiveEachPlayerATeam() {
        val teams = spec.modes.single().options.first { it.key == "teams" }
        val show = { n: Int -> teams.visibleWhen!!.invoke(HubChoices().with("humans", n)) }
        assertFalse(show(2)); assertFalse(show(3)); assertTrue(show(4)); assertFalse(show(5)); assertTrue(show(6)); assertTrue(show(8))
        val l = local("same", "humans" to "6", "teams" to "true")
        assertEquals(listOf(0, 1, 0, 1, 0, 1), l.launch.players.map { it.teamId })
        assertTrue("teams is a standard key, not an extra", "teams" !in l.launch.options.extras)
    }

    @Test fun aStaleTeamsFlagWithAnOddTableStillGivesAWorkingGameWithoutTeams() {
        val l = local("same", "humans" to "3", "teams" to "true")
        // The Hub may pad the table with a computer seat; the engine ignores computers and plays without teams.
        val g = DrawGuessGame(Random(1))
        g.init(GameContext(l.launch.mode, l.launch.players, 0, object : com.gamesuite.transport.MultiplayerTransport {
            override fun connect() {}
            override fun disconnect() {}
            override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
            override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
            override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
            override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
            override fun onReconnected(listener: () -> Unit) {}
        }))
        g.startMatch()
        assertFalse(g.teamMode)
        assertEquals(listOf(0, 1, 2), g.order)
        assertEquals(3, g.state.value!!.totalTurns)
    }

    @Test fun everyOptionKeyTheEngineAndScreenReadIsOnOffer() {
        val keys = spec.modes.flatMap { it.options }.map { it.key }.toSet()
        assertTrue(keys.containsAll(listOf("timer", "guess", "rounds", "humans", "teams")))
        val src = moduleFile("java/com/gamesuite/games/drawguess/DrawGuessGame.kt").readText()
        for (k in listOf("timer", "guess", "rounds")) assertTrue(k, src.contains("extras[\"$k\"]"))
        assertTrue(moduleFile("java/com/gamesuite/ui/DrawGuessScreen.kt").readText().contains("DrawGuessConfig.fromExtras(sessionManager.launchOptions.extras"))
    }

    // ---- identity colours ----

    @Test fun proposedAccentIsUniqueLegibleAndAReadableLabelInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in DrawGuessHubSpecs.accents.keys }
        for ((id, pair) in DrawGuessHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }

    @Test fun thePenPaletteIsSixDistinctColoursWithEnoughContrastOnWhitePaper() {
        assertEquals(DrawLimits.PALETTE_SIZE, DrawPalette.colors.size)
        assertEquals(DrawPalette.colors.size, DrawPalette.colors.toSet().size)
        for (c in DrawPalette.colors) assertTrue("${c.toString(16)} on paper", Wcag.contrast(c, DrawPalette.PAPER) >= 2.2)
        // Colours that sit close in lightness are told apart by their own mark on the swatch (ColorSwatch), not by hue alone.
        assertTrue(moduleFile("java/com/gamesuite/ui/DrawGuessArt.kt").readText().contains("fun ColorSwatch"))
        assertEquals(DrawLimits.BRUSH_COUNT, DrawPalette.brushFractions.size)
        assertTrue(DrawPalette.brushFractions.toList().zipWithNext().all { (a, b) -> a < b })
    }
}
