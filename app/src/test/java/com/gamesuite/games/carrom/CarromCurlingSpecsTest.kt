package com.gamesuite.games.carrom

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
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The Hub specs, tips, coach text and every string resource of Carrom and Curling, in both languages. */
class CarromCurlingSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private class Res(val strings: Map<String, String>, val plurals: Set<String>)

    private fun parseDir(dir: String): Res {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedSetOf<String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = e.textContent
                    "plurals" -> plurals += e.getAttribute("name")
                }
            }
        }
        return Res(strings, plurals)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")

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

    private val specs = CarromCurlingHubSpecs.all

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in specs) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en.strings)
            assertTrue("missing ES $key for '$text'", key in es.strings)
            assertEquals(text, shown(en.strings[key]!!))
            assertTrue("blank ES $key", es.strings[key]!!.isNotBlank())
            assertEquals("placeholders $key", placeholders(en.strings[key]!!), placeholders(es.strings[key]!!))
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en.strings && hubKey(it) in es.strings) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun theSpecsAreShapedLikeTheOtherArcadeGames() {
        for ((spec, id) in listOf(CarromCurlingHubSpecs.carrom to "carrom", CarromCurlingHubSpecs.curling to "curling")) {
            assertEquals(id, spec.gameId)
            assertEquals(HubFamily.ARCADE_PUZZLE, spec.family)
            assertEquals("cpu", spec.defaultModeId)
            assertEquals(setOf(id), spec.routes())
            assertTrue(spec.resumable)
            assertEquals(HubModeKind.VS_CPU, spec.mode("cpu")!!.kind)
            assertEquals(HubModeKind.PASS_AND_PLAY, spec.mode("pass")!!.kind)
            for (m in spec.modes) {
                assertEquals(2, m.minSeats); assertEquals(4, m.maxSeats)
                for (o in m.options) if (o is HubOption.Choice) assertTrue("${o.key} default", o.choices.any { it.id == o.default })
            }
        }
    }

    @Test fun launchesCarryTheRulesAndTheSeats() {
        val cpu = CarromCurlingHubSpecs.carrom.mode("cpu")!!
        val c = buildHubLaunch(CarromCurlingHubSpecs.carrom, cpu, HubChoices().with("target", "25").with("cpus", 3).with("difficulty", "HARD"), random = Random(1)) as HubLaunch.Local
        assertEquals("carrom", c.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, c.launch.mode)
        assertEquals(4, c.launch.players.size)
        assertEquals("25", c.launch.options.extras["target"])
        assertEquals(CpuDifficulty.HARD, c.launch.options.cpuDifficulty)
        val curl = CarromCurlingHubSpecs.curling.mode("pass")!!
        val p = buildHubLaunch(CarromCurlingHubSpecs.curling, curl, HubChoices().with("ends", "6").with("stones", "8").with("humans", 2).with("cpus", 0), random = Random(1)) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, p.launch.mode)
        assertEquals(2, p.launch.players.size)
        assertEquals("6", p.launch.options.extras["ends"])
        assertEquals("8", p.launch.options.extras["stones"])
        assertEquals(null, p.launch.options.cpuDifficulty)
        // a stale or hostile saved value falls back to a real choice
        val r = curl.resolve(HubChoices().with("ends", "99").with("stones", "x"))
        assertEquals("4", r.string("ends", "?")); assertEquals("4", r.string("stones", "?"))
        assertTrue(buildHubLaunch(CarromCurlingHubSpecs.carrom, CarromCurlingHubSpecs.carrom.mode("pass")!!, HubChoices().with("humans", 4), random = Random(1)).let { (it as HubLaunch.Local).launch.players.size == 4 })
    }

    // ---- tips and coach -------------------------------------------------------------------

    @Test fun tipsAreThreeShortLinesEqualToTheirStringResourcesInBothLocales() {
        for ((id, lines) in CarromCurlingHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                assertTrue(line.length <= 140)
                val name = "tip_${id}_${i + 1}"
                assertEquals(line, shown(en.strings[name] ?: error("missing $name")))
                assertTrue("ES $name", !es.strings[name].isNullOrBlank() && es.strings[name] != en.strings[name])
            }
        }
    }

    @Test fun coachEntriesHaveThreeToSixStepsAndAFactInBothLocales() {
        for (e in CarromCurlingHubSpecs.coach) {
            val n = e.steps.size
            assertTrue(n in 3..6)
            val names = (1..n).map { "coach_${e.gameId}_s$it" } + "coach_${e.gameId}_fact"
            for (name in names) {
                assertTrue("EN $name", !en.strings[name].isNullOrBlank())
                assertTrue("ES $name", !es.strings[name].isNullOrBlank())
                assertNotEquals(en.strings[name], es.strings[name])
            }
            assertFalse(en.strings.containsKey("coach_${e.gameId}_s${n + 1}"))
        }
    }

    // ---- resource completeness -------------------------------------------------------------

    @Test fun everyCarromAndCurlingResourceExistsInBothLanguagesWithMatchingPlaceholders() {
        val mine = en.strings.keys.filter { it.startsWith("carrom_") || it.startsWith("curling_") }
        assertTrue(mine.size > 60)
        for (name in mine) {
            assertTrue("ES missing $name", name in es.strings)
            assertEquals("placeholders $name", placeholders(en.strings[name]!!), placeholders(es.strings[name]!!))
            assertTrue("blank ES $name", es.strings[name]!!.isNotBlank())
        }
        assertTrue("carrom_ann_own" in en.plurals && "carrom_ann_own" in es.plurals)
        val apostrophe = Regex("(?<!\\\\)'")
        (en.strings + es.strings.mapKeys { "es:" + it.key }).filter { (k, _) -> k.removePrefix("es:").let { it.startsWith("carrom_") || it.startsWith("curling_") || it.startsWith("tip_c") || it.startsWith("coach_c") } }
            .forEach { (k, v) -> assertFalse("unescaped apostrophe in $k", apostrophe.containsMatchIn(v)) }
    }

    @Test fun everyStringTheScreensUseExists() {
        val used = Regex("R\\.(?:string|plurals)\\.((?:carrom|curling)_[a-z0-9_]+)")
        val missing = mutableListOf<String>()
        for (f in listOf("ui/CarromScreen.kt", "ui/CurlingScreen.kt")) {
            val src = moduleFile("java/com/gamesuite/$f").readText()
            for (m in used.findAll(src)) {
                val name = m.groupValues[1]
                if (name !in en.strings && name !in en.plurals) missing += "$f: $name missing from values"
                if (name !in es.strings && name !in es.plurals) missing += "$f: $name missing from values-es"
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun theGameIdsAreStable() {
        assertEquals("carrom", CarromGameIdProbe.carrom)
        assertEquals("curling", CarromGameIdProbe.curling)
    }

    // ---- the accents paste-ready for GameTheme ----------------------------------------------

    @Test fun theProposedAccentsAreReadableOnEveryCardSurfaceAndAreUnique() {
        val carrom = ProposedAccents.carrom
        val curling = ProposedAccents.curling
        for (a in listOf(carrom, curling)) {
            GameThemes.lightSurfaces.forEach { assertTrue("light ${a.first.toString(16)}", Wcag.contrast(a.first, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            GameThemes.darkSurfaces.forEach { assertTrue("dark ${a.second.toString(16)}", Wcag.contrast(a.second, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.second), a.second) >= GameThemes.MIN_TEXT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.first), a.first) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        assertNotEquals(carrom, curling)
        val taken = GameThemes.accents.filterKeys { it != "carrom" && it != "curling" }.values
        for (a in listOf(carrom, curling)) {
            assertFalse("light accent already used", taken.any { it.light == a.first })
            assertFalse("dark accent already used", taken.any { it.dark == a.second })
        }
    }
}

private object CarromGameIdProbe {
    val carrom = CarromGame().gameId
    val curling = com.gamesuite.games.curling.CurlingGame().gameId
}

/** The (light, dark) accent pairs proposed in the report; kept here so contrast is checked by a test. */
object ProposedAccents {
    val carrom: Pair<Long, Long> = 0xFF8C6A00 to 0xFFF2C14E
    val curling: Pair<Long, Long> = 0xFF3E7488 to 0xFF9ED0E0
}
