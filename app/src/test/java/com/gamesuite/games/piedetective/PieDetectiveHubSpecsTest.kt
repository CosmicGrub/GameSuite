package com.gamesuite.games.piedetective

import com.gamesuite.core.GameAudience
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hub spec, accessibility tokens, resources and identity colour (tests P38-P44 plus localisation rules on our own spec). */
class PieDetectiveHubSpecsTest {
    private val spec = PieDetectiveHubSpecs.spec
    private val en by lazy { PieTestFiles.dirStrings("res/values") }
    private val es by lazy { PieTestFiles.dirStrings("res/values-es") }
    private fun mine(k: String) = k.startsWith("pie_detective_") || k.startsWith("tip_pie_detective_") || k.startsWith("coach_pie_detective_")
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

    // ---- accessibility tokens (P38) ----

    @Test
    fun answerStampsUseShapeAndColourTokens() {
        val yes = PieTokens.stampFor(true)
        val no = PieTokens.stampFor(false)
        assertEquals(PieTokens.StampShape.CIRCLE, yes.shape); assertEquals(PieTokens.StampIcon.TICK, yes.icon)
        assertEquals(PieTokens.StampShape.ROUNDED_SQUARE, no.shape); assertEquals(PieTokens.StampIcon.CROSS, no.icon)
        assertNotEquals(yes.shape, no.shape); assertNotEquals(yes.icon, no.icon); assertNotEquals(yes.light, no.light); assertNotEquals(yes.dark, no.dark)
        for ((scheme, name) in listOf(PieTokens.light to "light", PieTokens.dark to "dark")) {
            assertTrue("$name yes mark", Wcag.contrast(scheme.onYes, scheme.yes) >= 4.5)
            assertTrue("$name no mark", Wcag.contrast(scheme.onNo, scheme.no) >= 4.5)
            assertTrue("$name text", Wcag.contrast(scheme.text, scheme.background) >= 7.0)
            assertTrue("$name text on card", Wcag.contrast(scheme.text, scheme.card) >= 4.5)
            assertTrue("$name yes on card", Wcag.contrast(scheme.yes, scheme.card) >= 3.0)
            assertTrue("$name no on card", Wcag.contrast(scheme.no, scheme.card) >= 3.0)
            assertTrue("$name muted", Wcag.contrast(scheme.muted, scheme.background) >= 4.5)
            assertTrue("$name accent button", Wcag.contrast(scheme.onAccent, scheme.accent) >= 4.5)
        }
    }

    // ---- spec (P39-P42) ----

    @Test
    fun specIsKidsAudienceWithMinAgeFive() {
        assertEquals("pie-detective", spec.gameId)
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(5, spec.minAge)
        assertTrue(spec.resumable)
    }

    @Test
    fun specIsSameScreenOnlyAndNotNetworked() {
        assertFalse(spec.isNetworked)
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
        assertTrue(spec.modes.all { it.route == "pie-detective" })
        assertEquals("Same screen", spec.mode("same")!!.title)
        val game = PieDetectiveGame()
        assertFalse(game.supportedModes.contains(PlayMode.ONLINE))
        assertFalse(game.supportedModes.contains(PlayMode.LOCAL_AD_HOC))
    }

    @Test
    fun modeSeatBoundsAreTwoToFour() {
        assertTrue(spec.modes.all { it.minSeats == 2 && it.maxSeats == 4 })
        val local = { mode: String, c: List<Pair<String, String>> ->
            var choices = HubChoices(); c.forEach { choices = choices.with(it.first, it.second) }
            (buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local).launch
        }
        assertEquals(2, local("cpu", emptyList()).players.size) // a lone child plays with one Sidekick
        assertEquals(listOf(false, true, true, true), local("cpu", listOf("cpus" to "3")).players.map { it.isBot })
        assertEquals(4, local("same", listOf("humans" to "4", "cpus" to "2")).players.size)
        assertTrue(local("same", listOf("humans" to "2", "cpus" to "2")).players.size <= 4)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, local("cpu", emptyList()).mode)
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, local("same", emptyList()).mode)
    }

    @Test
    fun optionKeysAreBandHintsAutoFlipHandicapRotateAndSidekickLevel() {
        val cpu = spec.mode("cpu")!!.options.map { it.key }
        assertEquals(setOf("band", "cpus", "difficulty", "hints", "autoflip"), cpu.toSet())
        val same = spec.mode("same")!!.options.map { it.key }
        assertEquals(setOf("band", "humans", "cpus", "difficulty", "hints", "autoflip", "handicap", "rotate"), same.toSet())
        var choices = HubChoices()
        listOf("band" to "big", "hints" to "false", "autoflip" to "true", "handicap" to "p2", "rotate" to "false", "difficulty" to "HARD").forEach { choices = choices.with(it.first, it.second) }
        val l = (buildHubLaunch(spec, spec.mode("same")!!, choices, random = Random(1)) as HubLaunch.Local).launch
        assertEquals("big", l.options.extras["band"]); assertEquals("false", l.options.extras["hints"]); assertEquals("true", l.options.extras["autoflip"])
        assertEquals("p2", l.options.extras["handicap"]); assertEquals("false", l.options.extras["rotate"])
        assertEquals(CpuDifficulty.HARD, l.options.cpuDifficulty)
        for (c in (spec.mode("same")!!.options.first { it.key == "band" } as HubOption.Choice).choices) assertEquals(c.id, PieBand.fromId(c.id).id)
        for (c in (spec.mode("same")!!.options.first { it.key == "handicap" } as HubOption.Choice).choices) {
            assertEquals(c.id != "none", PieHandicap.parse(c.id, 4).any { it > 0 })
        }
        // the defaults of the Hub equal the engine's own defaults
        val d = (buildHubLaunch(spec, spec.mode("cpu")!!, HubChoices(), random = Random(1)) as HubLaunch.Local).launch
        assertEquals("medium", d.options.extras["band"]); assertEquals("true", d.options.extras["hints"]); assertEquals("false", d.options.extras["autoflip"])
    }

    // ---- strings (P43, P44) ----

    @Test
    fun englishAndSpanishStringFilesDefineTheSameKeys() {
        val ours = en.keys.filter { mine(it) }
        assertTrue("${ours.size} strings", ours.size > 120)
        assertEquals(ours.toSet(), es.keys.filter { mine(it) }.toSet())
        for (k in ours) {
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
            assertTrue("blank $k", en[k]!!.isNotBlank() && es[k]!!.isNotBlank())
        }
        // the tutorial and the coach card are complete
        for (i in 1..8) assertTrue("pie_detective_tutorial_$i" in en)
        for (i in 1..5) assertTrue("coach_pie_detective_s$i" in en)
        assertTrue("coach_pie_detective_fact" in en)
        // one name, one clue pair and one place per game element
        for (c in PieCast.all) assertTrue("pie_detective_suspect_${c.id}" in en)
        for (t in PieTrait.entries) for (a in listOf("yes", "no")) assertTrue("pie_detective_clue_${t.id}_$a" in en)
        for (t in PieTrait.entries) assertTrue("pie_detective_trait_${t.id}" in en)
        for (p in PieRules.lanePlaces) assertTrue("pie_detective_place_$p" in en)
        // each tutorial line fits the design's 120 character limit
        for (i in 1..8) assertTrue(en["pie_detective_tutorial_$i"]!!.length <= 120)
    }

    @Test
    fun noStringValueContainsTodoMarker() {
        for (map in listOf(en, es)) for ((k, v) in map) if (mine(k) || k.startsWith("hub_")) {
            val low = v.lowercase()
            assertFalse("$k: '$v'", low.contains("todo") || low.contains("fixme") || low.contains("lorem") || low.contains("xxx"))
        }
    }

    @Test
    fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (text in texts()) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
        assertEquals(emptyList<Any>(), texts().groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test
    fun ourHubFileDefinesOnlyKeysItsTextsNeedAndNothingElseDefinesThem() {
        val keys = texts().map { hubKey(it) }.toSet()
        val own = PieTestFiles.strings(PieTestFiles.read("res/values/pie_detective_hub_strings.xml")).keys
        val ownEs = PieTestFiles.strings(PieTestFiles.read("res/values-es/pie_detective_hub_strings.xml")).keys
        assertEquals(own, ownEs)
        assertTrue(keys.containsAll(own))
        for (dir in listOf("res/values", "res/values-es")) {
            PieTestFiles.file(dir).listFiles { f -> f.name.endsWith(".xml") && f.name != "pie_detective_hub_strings.xml" }!!.forEach { f ->
                val other = PieTestFiles.strings(f.readText()).keys
                assertEquals("duplicate in $dir/${f.name}", emptySet<String>(), other intersect own)
            }
        }
    }

    @Test
    fun ourStringsAreTranslatedAndApostrophesAreEscaped() {
        val keepSame = setOf("pie_detective_title", "pie_detective_help_title", "pie_detective_answer_no", "pie_detective_notebook_no")
        for (k in en.keys.filter { mine(it) }) {
            assertTrue("$k not translated", en[k] != es[k] || k in keepSame || k.startsWith("pie_detective_suspect_"))
        }
        for (d in listOf("values", "values-es")) for (n in listOf("pie_detective_hub_strings.xml", "pie_detective_strings.xml", "pie_detective_coach_strings.xml")) {
            val xml = PieTestFiles.read("res/$d/$n")
            Regex(">([^<]*)<").findAll(xml).forEach { m ->
                assertFalse("unescaped apostrophe in $d/$n: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test
    fun tipsAreThreeLinesAndTheEnglishLiteralEqualsTheStringResource() {
        for ((id, lines) in PieDetectiveHubSpecs.tips) {
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
    fun coachCardHasFiveStepsAndAFactInBothLanguages() {
        for (k in (1..5).map { "coach_pie_detective_s$it" } + "coach_pie_detective_fact") {
            assertTrue(en[k]?.isNotBlank() == true && es[k]?.isNotBlank() == true)
            assertNotEquals("$k not translated", en[k], es[k])
        }
        assertFalse("coach_pie_detective_s6" in en)
    }

    // ---- the screen ----

    private val screenFile = "java/com/gamesuite/ui/PieDetectiveScreen.kt"

    @Test
    fun everyStringTheScreenReferencesExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = PieTestFiles.read(screenFile) + PieTestFiles.read("java/com/gamesuite/ui/PieDetectiveArt.kt")
        // names built at runtime from an id (suspect_<id>, trait_<id>, place_<n>, clue_<trait>_yes ...) are listed in the screen's maps
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 40)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val screenStrings = PieTestFiles.strings(PieTestFiles.read("res/values/pie_detective_strings.xml")).keys
        val dead = screenStrings.filter { it !in used && it !in PieDetectiveHubSpecsTestSupport.metadataKeys }
        assertEquals("strings nobody references: $dead", emptyList<String>(), dead)
    }

    @Test
    fun theScreenHasNoHardCodedEnglish() {
        val src = PieTestFiles.read(screenFile)
        val literals = Regex("(?:Text\\(\\s*|text\\s*=\\s*|contentDescription\\s*=\\s*|description\\s*=\\s*|label\\s*=\\s*)\"([^\"]*)\"").findAll(src)
        for (m in literals) {
            val visible = m.groupValues[1].replace(Regex("\\$\\{[^}]*\\}"), "").replace(Regex("\\$[A-Za-z]+"), "")
            assertFalse("hard-coded text: '${m.groupValues[1]}'", Regex("[A-Za-z]{3,}").containsMatchIn(visible))
        }
    }

    @Test
    fun theScreenReadsEveryOptionKeyTheHubOffersAndCallsPlayAgainOnlyThroughTheGame() {
        val src = PieTestFiles.read(screenFile)
        val keys = spec.modes.flatMap { it.options }.map { it.key }.toSet()
        for (k in listOf("band", "hints", "autoflip", "handicap", "rotate")) {
            assertTrue(k, k in keys)
            assertTrue(k, src.contains("extras[\"$k\"]"))
        }
        // the in-place restart lives in the game (LaunchPolicy.requestNewRound), the screen must not bypass it
        assertFalse(src.contains("startBook"))
        assertTrue(PieTestFiles.read("java/com/gamesuite/games/piedetective/PieDetectiveGame.kt").contains("LaunchPolicy.requestNewRound()"))
    }

    // ---- identity colour ----

    @Test
    fun proposedAccentIsUniqueLegibleAndReadableLabelledInBothThemes() {
        val existing = GameThemes.accents.filterKeys { it !in PieDetectiveHubSpecs.accents.keys }
        for ((id, pair) in PieDetectiveHubSpecs.accents) {
            val (light, dark) = pair
            assertTrue("$id light accent already used", existing.values.none { it.light == light })
            assertTrue("$id dark accent already used", existing.values.none { it.dark == dark })
            for (s in GameThemes.lightSurfaces) assertTrue("$id light on ${s.toString(16)}", Wcag.contrast(light, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (s in GameThemes.darkSurfaces) assertTrue("$id dark on ${s.toString(16)}", Wcag.contrast(dark, s) >= GameThemes.MIN_ACCENT_CONTRAST)
            for (a in listOf(light, dark)) assertTrue("$id label on ${a.toString(16)}", Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
        }
    }
}

/** Keys that are read by other layers (the Hub card, the coach card, the parent dashboard) and not by the screen file. */
object PieDetectiveHubSpecsTestSupport {
    val metadataKeys = setOf("pie_detective_title", "pie_detective_tagline", "pie_detective_teaches", "pie_detective_seat_sidekick_tag")
}
