package com.gamesuite.games.billiards

import com.gamesuite.core.GameCoach
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
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.games.minigolf.GolfCourse
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The Hub specs of Billiards and Mini Golf, their launches, and every EN + ES string the two games ship. */
class BilliardsGolfHubSpecsTest {
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
    private fun shown(raw: String) = raw.replace("\\'", "'")

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

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        val missing = ArrayList<String>()
        for (spec in BilliardsGolfHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            if (key !in en) missing += "EN $key = $text"
            else assertEquals(text, shown(en[key]!!))
            if (key !in es) missing += "ES $key"
            else assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        assertEquals(emptyList<String>(), missing)
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = BilliardsGolfHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun everyGameStringExistsInBothLocalesWithMatchingPlaceholders() {
        val mine = en.keys.filter { it.startsWith("bl_") || it.startsWith("mg_") || it.startsWith("tip_billiards") || it.startsWith("tip_mini_golf") || it.startsWith("coach_billiards") || it.startsWith("coach_mini_golf") }
        assertTrue(mine.size > 120)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        val strays = es.keys.filter { it.startsWith("bl_") || it.startsWith("mg_") } - en.keys
        assertEquals(emptyList<String>(), strays)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocales() {
        val src = listOf("BilliardsScreen.kt", "MiniGolfScreen.kt", "BilliardsGolfUiHelpers.kt").joinToString("\n") {
            moduleFile("java/com/gamesuite/ui/$it").readText()
        }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 80)
        val missing = used.filter { it !in en || it !in es }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun everySpanishStringIsActuallyTranslated() {
        val same = en.keys.filter { (it.startsWith("bl_") || it.startsWith("mg_") || it.startsWith("coach_billiards") || it.startsWith("coach_mini_golf") || it.startsWith("tip_billiards") || it.startsWith("tip_mini_golf")) }
            .filter { !it.startsWith("mg_hole_name_") && !it.startsWith("mg_score_") && !it.startsWith("mg_card_") }
            .filter { en[it] == es[it] && en[it]!!.replace(Regex("%(\\d+\\$)?[sdf]"), "").count { c -> c.isLetter() } > 6 }
        assertEquals(emptyList<String>(), same)
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        listOf("values", "values-es").flatMap { d ->
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("billiards") || f.name.startsWith("mini_golf") }!!.map { it.readText() }
        }.forEach { xml ->
            Regex(">([^<]*)<").findAll(xml).forEach { m -> assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1])) }
        }
    }

    @Test fun tipsAreThreeShortLinesAndMatchTheStringResources() {
        for ((id, lines) in BilliardsGolfHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                assertTrue(line.length <= 140)
                val name = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(line, shown(en[name] ?: error("missing $name")))
                assertTrue("$name translated", es[name]?.isNotBlank() == true && shown(es[name]!!) != line)
            }
        }
    }

    @Test fun coachStringsExistForBothGamesInBothLocales() {
        for (id in listOf("billiards", "mini-golf")) {
            val n = (1..6).count { GameCoach.stepName(id, it) in en }
            assertTrue("$id has 3 to 6 coach steps ($n)", n in 3..6)
            for (i in 1..n) assertTrue(GameCoach.stepName(id, i) in es && es[GameCoach.stepName(id, i)] != en[GameCoach.stepName(id, i)])
            assertTrue(GameCoach.factName(id) in en && GameCoach.factName(id) in es)
            assertTrue((1..6).filter { GameCoach.stepName(id, it) in en }.size == n && (n + 1..6).none { GameCoach.stepName(id, it) in en })
        }
    }

    // ---- launches ---------------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        val m = spec.mode(mode)!!
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, m, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun billiardsLaunchesTwoSeatsAndTheVariantReachesTheGame() {
        val cpu = local(BilliardsGolfHubSpecs.billiards, "cpu", "variant" to "9ball", "difficulty" to "HARD", "first" to "opponent")
        assertEquals(2, cpu.launch.players.size); assertEquals(1, cpu.launch.players.count { it.isBot })
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, cpu.launch.mode); assertEquals("billiards", cpu.route)
        assertEquals("9ball", cpu.launch.options.extras["variant"])
        assertEquals(CpuDifficulty.HARD, cpu.launch.options.cpuDifficulty); assertEquals(1, cpu.launch.options.firstMoverIndex)
        val pass = local(BilliardsGolfHubSpecs.billiards, "pass")
        assertEquals(2, pass.launch.players.size); assertEquals(0, pass.launch.players.count { it.isBot })
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode)
        assertEquals("8ball", pass.launch.options.extras["variant"])
        assertEquals("9ball", BilliardsVariant.fromOption("9ball")); assertEquals("8ball", BilliardsVariant.fromOption("junk"))
    }

    @Test fun miniGolfSeatCountsStayWithinOneToFourAndOptionsReachTheGame() {
        val solo = local(BilliardsGolfHubSpecs.miniGolf, "solo", "holes" to "3", "limit" to "8")
        assertEquals(1, solo.launch.players.size); assertEquals("3", solo.launch.options.extras["holes"]); assertEquals("8", solo.launch.options.extras["limit"])
        for (cpus in 1..3) assertEquals(cpus + 1, local(BilliardsGolfHubSpecs.miniGolf, "cpu", "cpus" to cpus.toString()).launch.players.size)
        val big = local(BilliardsGolfHubSpecs.miniGolf, "pass", "humans" to "4", "cpus" to "2")
        assertEquals(4, big.launch.players.size)
        val d = local(BilliardsGolfHubSpecs.miniGolf, "cpu")
        assertEquals("9", d.launch.options.extras["holes"]); assertEquals("6", d.launch.options.extras["limit"]); assertEquals("mini-golf", d.route)
    }

    @Test fun bothSpecsAreResumableArcadeGamesWithTheRightSeatRanges() {
        assertTrue(BilliardsGolfHubSpecs.all.all { it.family == HubFamily.ARCADE_PUZZLE && it.resumable })
        assertEquals(2, BilliardsGolfHubSpecs.billiards.modes.maxOf { it.maxSeats })
        assertEquals(1, BilliardsGolfHubSpecs.miniGolf.modes.minOf { it.minSeats })
        assertEquals(4, BilliardsGolfHubSpecs.miniGolf.modes.maxOf { it.maxSeats })
        assertTrue(BilliardsGolfHubSpecs.miniGolf.modes.any { it.kind == HubModeKind.SOLO })
        assertEquals(setOf("billiards"), BilliardsGolfHubSpecs.billiards.routes())
        assertEquals(setOf("mini-golf"), BilliardsGolfHubSpecs.miniGolf.routes())
    }

    @Test fun everyHoleNameAndScoreWordIsLocalised() {
        for (i in 1..GolfCourse.holes.size) assertTrue("mg_hole_name_$i" in en && "mg_hole_name_$i" in es)
    }

    @Test fun coachStringsAreCompiledIntoTheResourceClassUnderTheNamesGameCoachUses() {
        val fields = Class.forName("com.gamesuite.R\$string").fields.map { it.name }.toSet()
        for ((id, steps) in listOf("billiards" to 5, "mini-golf" to 4)) {
            for (i in 1..steps) assertTrue(GameCoach.stepName(id, i), GameCoach.stepName(id, i) in fields)
            assertTrue(GameCoach.factName(id) in fields)
        }
    }

    @Test fun theProposedAccentsAreUniqueAndLegibleInBothThemes() {
        val proposed = mapOf(
            "billiards" to (0xFF0B6A5AL to 0xFF4FD6B4L),
            "mini-golf" to (0xFF587514L to 0xFFB8D95BL)
        )
        val taken = com.gamesuite.ui.GameThemes.accents.filterKeys { it != "billiards" && it != "mini-golf" }.values
        for ((id, p) in proposed) {
            assertTrue("$id light unique", taken.none { it.light == p.first })
            assertTrue("$id dark unique", taken.none { it.dark == p.second })
            for (surface in com.gamesuite.ui.GameThemes.lightSurfaces) assertTrue(com.gamesuite.ui.Wcag.contrast(p.first, surface) >= 3.0)
            for (surface in com.gamesuite.ui.GameThemes.darkSurfaces) assertTrue(com.gamesuite.ui.Wcag.contrast(p.second, surface) >= 3.0)
            for (c in listOf(p.first, p.second)) {
                val label = com.gamesuite.ui.GameThemes.onAccent(c)
                assertTrue(com.gamesuite.ui.Wcag.contrast(label, c) >= 4.5)
            }
        }
        assertTrue(proposed.values.map { it.first }.toSet().size == 2)
    }

    @Test fun everyModeBuildsALaunchWithItsDefaultsWithinItsSeatRange() {
        for (spec in BilliardsGolfHubSpecs.all) {
            assertEquals(spec.modes.size, spec.modes.map { it.id }.toSet().size)
            for (m in spec.modes) {
                val l = buildHubLaunch(spec, m, m.defaults(), random = Random(1)) as HubLaunch.Local
                val n = l.launch.players.size
                assertTrue("${spec.gameId}/${m.id}: $n seats within ${m.minSeats}..${m.maxSeats}", n in m.minSeats..m.maxSeats)
                assertEquals(n, l.launch.players.map { it.playerId }.toSet().size)
                assertTrue(l.launch.options.firstMoverIndex in 0 until n)
            }
        }
    }
}
