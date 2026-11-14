package com.gamesuite.games.slideclimb

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

/** The Hub spec, resources, coach, tips and identity colours of Slide and Climb. */
class SlideClimbHubSpecsTest {
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

    private fun parseFile(path: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(path))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val spec = SlideClimbHubSpecs.slideClimb
    private val ownFiles = listOf("slide_climb_strings.xml", "slide_climb_coach_strings.xml", "slide_climb_hub_strings.xml")

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value }.sorted().toList()
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

    // ---- the spec ----

    @Test fun theSpecIsAKidsGameFromAgeFour() {
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(4, spec.minAge)
        assertEquals("chutes-race", spec.gameId)
        assertEquals("Slide and Climb", spec.title)
        assertTrue(spec.resumable)
    }

    @Test fun theTitleDoesNotUseTheClassicTrademarkedName() {
        val everything = texts(spec).joinToString(" ").lowercase()
        assertFalse("chutes and ladders" in everything)
        assertFalse("snakes and ladders" in everything)
        assertFalse(spec.title.lowercase().contains("chutes"))
    }

    @Test fun itIsLocalOnly() {
        assertTrue(spec.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
        assertFalse(spec.isNetworked)
        assertTrue(spec.modes.all { it.route == "chutes-race" })
        assertEquals(setOf("chutes-race"), spec.routes())
    }

    @Test fun twoToFourSeatsInBothModes() {
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(4, m.maxSeats) }
        val cpu = spec.mode("cpu")!!
        val pass = spec.mode("pass")!!
        assertEquals(HubModeKind.VS_CPU, cpu.kind)
        assertEquals(HubModeKind.PASS_AND_PLAY, pass.kind)
        val cpus = cpu.options.filterIsInstance<HubOption.Stepper>().first { it.key == "cpus" }
        assertEquals(1, cpus.min); assertEquals(3, cpus.max)
    }

    @Test fun theSetupExtrasHaveTheAgreedKeysAndDefaults() {
        for (m in spec.modes) {
            val exact = m.options.filterIsInstance<HubOption.Toggle>().first { it.key == "exactFinish" }
            assertFalse(exact.default)
            // one spare-roll choice per table size, each offering only the seats that exist at that size
            for ((key, seats) in listOf("handicap" to 2, "handicap3" to 3, "handicap4" to 4)) {
                val handicap = m.options.filterIsInstance<HubOption.Choice>().first { it.key == key }
                assertEquals("none", handicap.default)
                assertEquals(listOf("none", "all") + (1..seats).map { "$it" }, handicap.choices.map { it.id })
            }
        }
    }

    private fun visibleHandicapKeys(modeId: String, c: HubChoices) =
        spec.mode(modeId)!!.options.filter { it.key.startsWith("handicap") && it.visibleWhen!!.invoke(c) }.map { it.key }

    @Test fun onlyTheSpareRollChoiceForTheRealTableSizeIsOffered() {
        // vs Computers: you + 1..3 computers
        assertEquals(listOf("handicap"), visibleHandicapKeys("cpu", HubChoices().with("cpus", 1)))
        assertEquals(listOf("handicap3"), visibleHandicapKeys("cpu", HubChoices().with("cpus", 2)))
        assertEquals(listOf("handicap4"), visibleHandicapKeys("cpu", HubChoices().with("cpus", 3)))
        // Pass & Play: people + computers, never more than 4 seats
        assertEquals(listOf("handicap"), visibleHandicapKeys("pass", HubChoices().with("humans", 2).with("cpus", 0)))
        assertEquals(listOf("handicap3"), visibleHandicapKeys("pass", HubChoices().with("humans", 2).with("cpus", 1)))
        assertEquals(listOf("handicap4"), visibleHandicapKeys("pass", HubChoices().with("humans", 4).with("cpus", 2)))
        assertEquals(listOf("handicap4"), visibleHandicapKeys("pass", HubChoices().with("humans", 3).with("cpus", 1)))
    }

    @Test fun aStaleSpareRollChoiceForAMissingSeatNeverReachesTheGame() {
        val cpu = spec.mode("cpu")!!
        // "Player 4 only" was picked at a 4-seat table, then the table shrank to 2 seats
        val choices = cpu.defaults().with("handicap4", "4").with("cpus", 1)
        val extras = (buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local).launch.options.extras
        assertEquals(null, extras["handicap4"])
        assertEquals(emptySet<Int>(), slideClimbHandicapFromExtras(extras, 2))
    }

    @Test fun theHandicapIsReadForTheRealSeatCount() {
        assertEquals(setOf(1), slideClimbHandicapFromExtras(mapOf("handicap" to "2"), 2))
        assertEquals(setOf(2), slideClimbHandicapFromExtras(mapOf("handicap3" to "3", "handicap" to "none"), 3))
        assertEquals(setOf(0, 1, 2, 3), slideClimbHandicapFromExtras(mapOf("handicap4" to "all"), 4))
        // a seat that is not at the table is dropped, not remembered
        assertEquals(emptySet<Int>(), slideClimbHandicapFromExtras(mapOf("handicap" to "4"), 2))
        assertEquals(emptySet<Int>(), slideClimbHandicapFromExtras(emptyMap(), 3))
    }

    @Test fun theComputerLevelOnlyShowsOnceASpareRollIsInPlay() {
        for (m in spec.modes) {
            val level = m.options.first { it.key == "difficulty" }
            assertFalse(level.visibleWhen!!.invoke(HubChoices()))
            assertFalse(level.visibleWhen!!.invoke(HubChoices().with("handicap", "none")))
            assertTrue(level.visibleWhen!!.invoke(HubChoices().with("handicap", "all")))
            assertTrue(level.visibleWhen!!.invoke(HubChoices().with("handicap", "2")))
            // a stale choice for another table size does not count
            val three = HubChoices().with("cpus", 2).with("humans", 1)
            assertFalse(level.visibleWhen!!.invoke(three.with("handicap", "all")))
            assertTrue(level.visibleWhen!!.invoke(three.with("handicap3", "all")))
        }
    }

    @Test fun aVsComputerLaunchCarriesTheExtras() {
        val cpu = spec.mode("cpu")!!
        val choices = cpu.defaults().with("exactFinish", true).with("handicap3", "1").with("cpus", 2)
        val launch = (buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local)
        assertEquals("chutes-race", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(3, launch.launch.players.size)
        assertEquals("true", launch.launch.options.extras["exactFinish"])
        assertEquals("1", launch.launch.options.extras["handicap3"])
        assertEquals(setOf(0), slideClimbHandicapFromExtras(launch.launch.options.extras, 3))
    }

    @Test fun aPassAndPlayLaunchSeatsPeopleThenComputers() {
        val pass = spec.mode("pass")!!
        val choices = pass.defaults().with("humans", 3).with("cpus", 1)
        val launch = (buildHubLaunch(spec, pass, choices, random = Random(1)) as HubLaunch.Local).launch
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, launch.mode)
        assertEquals(listOf(false, false, false, true), launch.players.map { it.isBot })
        assertEquals("false", launch.options.extras["exactFinish"])
        assertEquals("none", launch.options.extras["handicap4"])
        assertEquals(emptySet<Int>(), slideClimbHandicapFromExtras(launch.options.extras, 4))
    }

    @Test fun neverMoreThanFourSeats() {
        val pass = spec.mode("pass")!!
        val choices = pass.defaults().with("humans", 4).with("cpus", 2)
        val launch = (buildHubLaunch(spec, pass, choices, random = Random(1)) as HubLaunch.Local).launch
        assertEquals(4, launch.players.size)
    }

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

    @Test fun noTwoHubTextsShareAKey() {
        val all = texts(spec)
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourOwnFilesHaveTheSameNamesAndPlaceholdersInBothLocales() {
        for (f in ownFiles) {
            val e = parseFile("res/values/$f")
            val s = parseFile("res/values-es/$f")
            assertEquals("names of $f", e.keys.toList(), s.keys.toList())
            for ((k, v) in e) {
                assertTrue("blank EN $k", v.isNotBlank())
                assertTrue("blank ES $k", s[k]!!.isNotBlank())
                assertEquals("placeholders of $k", placeholders(v), placeholders(s[k]!!))
            }
        }
    }

    @Test fun ourStringNamesCarryOurPrefixAndAreDefinedOnce() {
        val allowed = listOf("slide_climb_", "coach_chutes_race_", "tip_chutes_race_", "hub_")
        for (f in ownFiles) {
            for (k in parseFile("res/values/$f").keys) {
                assertTrue("$k has no allowed prefix", allowed.any { k.startsWith(it) })
            }
        }
        // a name defined in one of our files must not be defined in any other file of the same locale
        val others = moduleFile("res/values").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
        val otherNames = others.flatMap { parseFile("res/values/${it.name}").keys }.toSet()
        for (f in ownFiles) {
            val dup = parseFile("res/values/$f").keys.filter { it in otherNames }
            assertEquals("duplicates of $f", emptyList<String>(), dup)
        }
    }

    @Test fun apostrophesAreEscaped() {
        for (f in ownFiles) for (locale in listOf("values", "values-es")) {
            for ((k, v) in parseFile("res/$locale/$f")) {
                assertFalse("unescaped apostrophe in $locale/$k", Regex("(?<!\\\\)'").containsMatchIn(v))
            }
        }
    }

    private fun screenSource() =
        moduleFile("java/com/gamesuite/ui/SlideClimbScreen.kt").readText() + "\n" + moduleFile("java/com/gamesuite/ui/SlideClimbArt.kt").readText()

    @Test fun everyStringTheScreenUsesExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 25)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = en.keys.filter { it.startsWith("slide_climb_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    // ---- coach and tips ----

    @Test fun theCoachCardHasFiveStepsAndAFact() {
        for (n in 1..5) {
            assertTrue("no EN step $n", "coach_chutes_race_s$n" in en)
            assertTrue("no ES step $n", "coach_chutes_race_s$n" in es)
        }
        assertTrue("coach_chutes_race_fact" in en && "coach_chutes_race_fact" in es)
    }

    @Test fun threeShortTipsExistInBothLocales() {
        for (n in 1..3) {
            val k = "tip_chutes_race_$n"
            assertTrue(k in en && k in es)
            assertTrue(shown(en[k]!!).length <= 140)
        }
    }

    // ---- identity ----

    @Test fun theAccentIsReadableOnEveryThemeSurface() {
        for (bg in GameThemes.lightSurfaces) {
            assertTrue(Wcag.contrast(SlideClimbHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        }
        for (bg in GameThemes.darkSurfaces) {
            assertTrue(Wcag.contrast(SlideClimbHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        }
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, SlideClimbHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, SlideClimbHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun theAccentIsNotOneAnotherGameAlreadyUses() {
        for ((id, accent) in GameThemes.accents) {
            if (id == "chutes-race") continue
            assertTrue("same light accent as $id", accent.light != SlideClimbHubSpecs.ACCENT_LIGHT)
            assertTrue("same dark accent as $id", accent.dark != SlideClimbHubSpecs.ACCENT_DARK)
        }
    }

    @Test fun theGlyphIsASingleEmoji() {
        assertTrue(SlideClimbHubSpecs.GLYPH.codePointCount(0, SlideClimbHubSpecs.GLYPH.length) == 1)
    }
}
