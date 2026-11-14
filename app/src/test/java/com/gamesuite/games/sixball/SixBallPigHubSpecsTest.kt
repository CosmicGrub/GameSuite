package com.gamesuite.games.sixball

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.random.Random

/** Reads the real res/values{,-es} files from disk (no Android needed). */
abstract class SbpResourceTest {
    protected fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    protected class Parsed(val strings: Map<String, String>, val arrays: Map<String, List<String>>)

    protected fun parseFile(f: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val strings = linkedMapOf<String, String>()
        val arrays = linkedMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "string-array" -> {
                    val items = e.getElementsByTagName("item")
                    arrays[e.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
                }
            }
        }
        return Parsed(strings, arrays)
    }

    protected fun parseDirMerged(dir: String): Parsed {
        val strings = linkedMapOf<String, String>()
        val arrays = linkedMapOf<String, List<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach {
            val p = parseFile(it)
            strings.putAll(p.strings); arrays.putAll(p.arrays)
        }
        return Parsed(strings, arrays)
    }

    protected val enAll: Map<String, String> by lazy { parseDirMerged("res/values").strings }
    protected val esAll: Map<String, String> by lazy { parseDirMerged("res/values-es").strings }
    protected val enArrays: Map<String, List<String>> by lazy { parseDirMerged("res/values").arrays }
    protected val esArrays: Map<String, List<String>> by lazy { parseDirMerged("res/values-es").arrays }

    protected fun placeholders(s: String): List<String> = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()
    protected fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n")
}

class SixBallPigHubSpecsTest : SbpResourceTest() {
    private val specs = SixBallPigHubSpecs.all

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

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in specs) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in enAll)
            assertTrue("missing ES $key for '$text'", key in esAll)
            assertEquals(text, shown(enAll[key]!!))
            assertTrue("blank ES $key", esAll[key]!!.isNotBlank())
            assertEquals("placeholders $key", placeholders(enAll[key]!!), placeholders(esAll[key]!!))
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in enAll && hubKey(it) in esAll) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = specs.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun theBlurbNamesTheRealLevelCount() {
        assertTrue(SixBallPigHubSpecs.sixBall.mode("free")!!.blurb.startsWith("${SixBallLevels.CAMPAIGN_COUNT} trays"))
    }

    @Test fun sixBallSpecIsShapedLikeAPuzzleWithADailyAndARace() {
        val s = SixBallPigHubSpecs.sixBall
        assertEquals("six-ball-puzzle", s.gameId); assertEquals(HubFamily.ARCADE_PUZZLE, s.family)
        assertEquals(setOf("six-ball-puzzle", "six-ball-puzzle-daily"), s.routes())
        assertTrue(s.hasDaily); assertTrue(s.resumable)
        assertEquals(HubModeKind.SOLO, s.mode("free")!!.kind)
        assertEquals(HubModeKind.DAILY, s.mode("daily")!!.kind)
        val pass = s.mode("pass")!!
        assertEquals(HubModeKind.PASS_AND_PLAY, pass.kind)
        assertEquals(2, pass.minSeats); assertEquals(4, pass.maxSeats)
        // a Nearby / Online lobby is sized from the shared modes: never below two, never above four
        val shared = s.modes.filter { it.kind != HubModeKind.SOLO && it.kind != HubModeKind.DAILY }
        assertTrue(shared.all { it.minSeats >= 2 })
        assertEquals(4, s.modes.maxOf { it.maxSeats })
        for (m in s.modes) for (o in m.options) if (o is HubOption.Choice) assertTrue(o.choices.any { it.id == o.default })
    }

    @Test fun pigSpecIsShapedLikeADiceGame() {
        val s = SixBallPigHubSpecs.pig
        assertEquals("pig-dice", s.gameId); assertEquals(HubFamily.BOARD, s.family)
        assertEquals(setOf("pig-dice"), s.routes())
        val cpu = s.mode("cpu")!!; val pass = s.mode("pass")!!
        assertEquals(2, cpu.minSeats); assertEquals(6, cpu.maxSeats)
        assertEquals(2, pass.minSeats); assertEquals(6, pass.maxSeats)
        val variant = cpu.options.filterIsInstance<HubOption.Choice>().first { it.key == SixBallPigHubSpecs.KEY_VARIANT }
        assertEquals(listOf("classic", "bigpig", "hog", "twentyone"), variant.choices.map { it.id })
        assertTrue(variant.choices.all { com.gamesuite.games.pigdice.PigVariant.fromId(it.id).id == it.id })
        val goal = cpu.options.filterIsInstance<HubOption.Choice>().first { it.key == SixBallPigHubSpecs.KEY_TARGET }
        assertTrue(goal.choices.all { it.id.toInt() in 20..200 })
    }

    @Test fun launchesMatchTheChoices() {
        val pig = SixBallPigHubSpecs.pig
        val cpu = buildHubLaunch(pig, pig.mode("cpu")!!, HubChoices().with(HubKeys.CPUS, 3).with("variant", "hog").with("target", "50").with(HubKeys.DIFFICULTY, "HARD"), random = Random(1)) as HubLaunch.Local
        assertEquals("pig-dice", cpu.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, cpu.launch.mode)
        assertEquals(4, cpu.launch.players.size); assertEquals(3, cpu.launch.players.count { it.isBot })
        assertEquals(CpuDifficulty.HARD, cpu.launch.options.cpuDifficulty)
        assertEquals("hog", cpu.launch.options.extras["variant"]); assertEquals("50", cpu.launch.options.extras["target"])

        val pass = buildHubLaunch(pig, pig.mode("pass")!!, HubChoices().with(HubKeys.HUMANS, 3).with(HubKeys.CPUS, 0), random = Random(1)) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode); assertEquals(3, pass.launch.players.size)
        assertEquals("classic", pass.launch.options.extras["variant"])
        assertFalse("difficulty is hidden with no computers", HubKeys.DIFFICULTY in pass.launch.options.extras)

        val six = SixBallPigHubSpecs.sixBall
        val solo = buildHubLaunch(six, six.mode("free")!!, HubChoices(), random = Random(1)) as HubLaunch.Local
        assertEquals("six-ball-puzzle", solo.route); assertEquals(1, solo.launch.players.size)
        assertEquals("next", solo.launch.options.extras["start"])
        val daily = buildHubLaunch(six, six.mode("daily")!!, HubChoices(), random = Random(1)) as HubLaunch.Local
        assertEquals("six-ball-puzzle-daily", daily.route); assertEquals(1, daily.launch.players.size)
        val race = buildHubLaunch(six, six.mode("pass")!!, HubChoices().with(HubKeys.HUMANS, 4).with("start", "first"), random = Random(1)) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, race.launch.mode); assertEquals(4, race.launch.players.size)
        assertEquals("first", race.launch.options.extras["start"])
    }

    @Test fun tipsAreThreeLinesEachAndMatchTheirStringResources() {
        for ((id, lines) in SixBallPigHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, text ->
                val name = "tip_${id.replace('-', '_')}_${i + 1}"
                assertEquals(text, shown(enAll[name] ?: error("missing $name")))
                assertTrue("ES $name", !esAll[name].isNullOrBlank())
            }
        }
        assertEquals(setOf("six-ball-puzzle", "pig-dice"), SixBallPigHubSpecs.tips.keys)
    }

    @Test fun coachCardsHaveStepsAndAFactInBothLanguages() {
        for ((id, steps) in SixBallPigHubSpecs.coachSteps) {
            assertTrue(steps in 3..6)
            val base = "coach_${id.replace('-', '_')}"
            for (n in 1..steps) {
                val name = "${base}_s$n"
                assertTrue(name, !enAll[name].isNullOrBlank()); assertTrue(name, !esAll[name].isNullOrBlank())
                assertEquals(placeholders(enAll[name]!!), placeholders(esAll[name]!!))
            }
            assertTrue(!enAll["${base}_fact"].isNullOrBlank()); assertTrue(!esAll["${base}_fact"].isNullOrBlank())
            assertTrue("no step ${steps + 1}", "${base}_s${steps + 1}" !in enAll)
        }
    }

    @Test fun everyStringTheScreensUseExistsInBothLanguagesWithMatchingPlaceholders() {
        val roots = listOf("java/com/gamesuite/ui/SixBallScreen.kt", "java/com/gamesuite/ui/PigScreen.kt", "java/com/gamesuite/ui/PigDiceArt.kt")
        val names = linkedSetOf<String>()
        for (r in roots) {
            val text = moduleFile(r).readText()
            Regex("R\\.string\\.([a-z0-9_]+)").findAll(text).forEach { names += it.groupValues[1] }
        }
        assertTrue(names.size > 80)
        for (n in names) {
            assertTrue("EN $n", n in enAll); assertTrue("ES $n", n in esAll)
            assertEquals("placeholders $n", placeholders(enAll[n]!!), placeholders(esAll[n]!!))
            assertTrue("ES $n blank", esAll[n]!!.isNotBlank())
        }
        for (a in listOf("sbp_dir_names", "sbp_color_names", "cc_shape_names")) {
            assertTrue(a, a in enArrays && a in esArrays)
            assertEquals(a, enArrays[a]!!.size, esArrays[a]!!.size)
        }
        assertEquals(4, enArrays["sbp_dir_names"]!!.size); assertEquals(6, enArrays["sbp_color_names"]!!.size)
    }

    @Test fun theSecondPersonVariantsOfEveryPigAnnouncementKeepTheirNumbers() {
        // the "You" strings take the same numbers as the named ones, minus the leading name
        for (event in listOf("roll", "bust", "bust_zero", "snake", "doubles", "exact", "hog", "hog_bust", "hold", "win", "start")) {
            val other = enAll["pig_ann_$event"]!!; val you = enAll["yg_pig_ann_$event"]!!
            val o = placeholders(other).filter { !it.startsWith("%1\$") }.map { it.replace(Regex("%(\\d+)\\$")) { m -> "%${m.groupValues[1].toInt() - 1}\$" } }.sorted()
            assertEquals(event, o, placeholders(you).sorted())
            assertFalse(you.contains("%1\$s") && you.startsWith("%1\$s"))
        }
    }

    @Test fun theProposedAccentsAreDistinctAndReadableOnTheReferenceSurfaces() {
        val mine = SixBallPigHubSpecs.accents
        val others = GameThemes.accents.filterKeys { it !in mine.keys }
        for ((id, a) in mine) {
            for ((otherId, o) in others) {
                assertNotEquals("$id light duplicates $otherId", o.light, a.light)
                assertNotEquals("$id dark duplicates $otherId", o.dark, a.dark)
            }
            GameThemes.lightSurfaces.forEach { assertTrue("$id light on surface", Wcag.contrast(a.light, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            GameThemes.darkSurfaces.forEach { assertTrue("$id dark on surface", Wcag.contrast(a.dark, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.light), a.light) >= GameThemes.MIN_TEXT_CONTRAST)
            assertTrue(Wcag.contrast(GameThemes.onAccent(a.dark), a.dark) >= GameThemes.MIN_TEXT_CONTRAST)
        }
        assertNotEquals(mine.getValue("six-ball-puzzle").light, mine.getValue("pig-dice").light)
        assertEquals(setOf("six-ball-puzzle", "pig-dice"), SixBallPigHubSpecs.glyphs.keys)
    }
}
