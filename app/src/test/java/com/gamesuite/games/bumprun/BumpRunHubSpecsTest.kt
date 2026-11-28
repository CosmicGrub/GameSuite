package com.gamesuite.games.bumprun

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.core.kidMaySee
import com.gamesuite.core.withoutNetworkModes
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

/** The Hub spec, resources, coach, tips and identity colours of Bump and Run. */
class BumpRunHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
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

    private fun parseDir(dir: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { strings += parseFile("$dir/${it.name}") }
        return strings
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val spec = BumpRunHubSpecs.bumpRun
    private val ownFiles = listOf("bump_run_strings.xml", "bump_run_ui_strings.xml")

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

    @Test fun theSpecIsAFamilyGameFromAgeSix() {
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(6, spec.minAge)
        assertEquals("bump-run", spec.gameId)
        assertEquals("Bump and Run", spec.title)
        assertTrue(spec.resumable)
    }

    @Test fun theSpecIsNotNetworkedAndUsesOneRoute() {
        assertTrue(spec.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
        assertFalse(spec.isNetworked)
        assertEquals(setOf("bump-run"), spec.routes())
        assertEquals(spec, spec.withoutNetworkModes())
    }

    @Test fun kidProfilesOfSixAndOlderSeeTheGameAndNoNetworkMode() {
        assertTrue(kidMaySee(spec, 6))
        assertTrue(kidMaySee(spec, 12))
        assertFalse(kidMaySee(spec, 5))
    }

    @Test fun twoToFourSeatsInBothModes() {
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(4, m.maxSeats) }
        val cpus = spec.mode("cpu")!!.options.filterIsInstance<HubOption.Stepper>().first { it.key == "cpus" }
        assertEquals(1, cpus.min); assertEquals(3, cpus.max)
    }

    @Test fun optionKeysAndDefaultsMatchTheDoc() {
        for (m in spec.modes) {
            val length = m.options.filterIsInstance<HubOption.Choice>().first { it.key == "length" }
            assertEquals("quick", length.default)
            assertEquals(listOf("quick", "classic", "long"), length.choices.map { it.id })
            assertEquals(listOf(2, 3, 4), length.choices.map { BumpLength.fromId(it.id).pawns })
            assertTrue(m.options.filterIsInstance<HubOption.Toggle>().first { it.key == "pads" }.default)
            assertTrue(m.options.filterIsInstance<HubOption.Toggle>().first { it.key == "autoPlay" }.default)
            val helper = m.options.filterIsInstance<HubOption.Choice>().first { it.key == "helper" }
            assertEquals("none", helper.default)
            assertEquals(listOf("none", "all", "1", "2", "3", "4"), helper.choices.map { it.id })
        }
    }

    @Test fun teamToggleIsHiddenBelowFourSeats() {
        val cpuTeams = spec.mode("cpu")!!.options.first { it.key == HubKeys.TEAMS }
        assertFalse(cpuTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.CPUS, 1)))
        assertFalse(cpuTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.CPUS, 2)))
        assertTrue(cpuTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.CPUS, 3)))
        val passTeams = spec.mode("pass")!!.options.first { it.key == HubKeys.TEAMS }
        assertFalse(passTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.HUMANS, 2).with(HubKeys.CPUS, 1)))
        assertTrue(passTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.HUMANS, 3).with(HubKeys.CPUS, 1)))
        assertTrue(passTeams.visibleWhen!!.invoke(HubChoices().with(HubKeys.HUMANS, 4).with(HubKeys.CPUS, 0)))
    }

    @Test fun aVsComputerLaunchCarriesTheExtras() {
        val cpu = spec.mode("cpu")!!
        val choices = cpu.defaults().with("length", "long").with("helper", "1").with("cpus", 2)
        val launch = buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local
        assertEquals("bump-run", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(3, launch.launch.players.size)
        assertEquals("long", launch.launch.options.extras["length"])
        assertEquals(setOf(0), parseBumpRunHelper(launch.launch.options.extras["helper"], 3))
    }

    @Test fun aTeamLaunchGivesPartnersTheSameTeamId() {
        val cpu = spec.mode("cpu")!!
        val choices = cpu.defaults().with(HubKeys.CPUS, 3).with(HubKeys.TEAMS, true)
        val players = (buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local).launch.players
        assertEquals(4, players.size)
        assertTrue(players.all { it.teamId >= 0 })
        assertEquals(players[0].teamId, players[2].teamId)
        assertEquals(players[1].teamId, players[3].teamId)
        assertTrue(players[0].teamId != players[1].teamId)
    }

    @Test fun neverMoreThanFourSeatsAndPeopleComeFirst() {
        val pass = spec.mode("pass")!!
        val launch = (buildHubLaunch(spec, pass, pass.defaults().with("humans", 4).with("cpus", 2), random = Random(1)) as HubLaunch.Local).launch
        assertEquals(4, launch.players.size)
        val mixed = (buildHubLaunch(spec, pass, pass.defaults().with("humans", 2).with("cpus", 2), random = Random(1)) as HubLaunch.Local).launch
        assertEquals(listOf(false, false, true, true), mixed.players.map { it.isBot })
    }

    @Test fun helperChoicesAreReadForTheRealSeatCount() {
        assertEquals(emptySet<Int>(), parseBumpRunHelper(null, 3))
        assertEquals(setOf(0, 1), parseBumpRunHelper("all", 2))
        assertEquals(emptySet<Int>(), parseBumpRunHelper("4", 2))
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
        assertEquals(emptyList<Any>(), texts(spec).groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun ourFilesHaveTheSameNamesAndPlaceholdersInBothLocales() {
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
        val allowed = listOf("bump_run_", "coach_bump_run_", "tip_bump_run_", "hub_")
        for (f in ownFiles) for (k in parseFile("res/values/$f").keys) assertTrue("$k has no allowed prefix", allowed.any { k.startsWith(it) })
        val others = moduleFile("res/values").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
        val otherNames = others.flatMap { parseFile("res/values/${it.name}").keys }.toSet()
        for (f in ownFiles) assertEquals("duplicates of $f", emptyList<String>(), parseFile("res/values/$f").keys.filter { it in otherNames })
    }

    @Test fun apostrophesAreEscaped() {
        for (f in ownFiles) for (locale in listOf("values", "values-es")) {
            for ((k, v) in parseFile("res/$locale/$f")) assertFalse("unescaped apostrophe in $locale/$k", Regex("(?<!\\\\)'").containsMatchIn(v))
        }
    }

    private fun screenSource() =
        moduleFile("java/com/gamesuite/ui/BumpRunScreen.kt").readText() + "\n" + moduleFile("java/com/gamesuite/ui/BumpRunArt.kt").readText()

    @Test fun everyStringTheScreenUsesExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSource()).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 40)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = en.keys.filter { it.startsWith("bump_run_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    // ---- coach, tips, wording ----

    @Test fun theCoachCardHasFiveShortStepsAndAFact() {
        for (n in 1..5) {
            val k = "coach_bump_run_s$n"
            assertTrue("no EN/ES $k", k in en && k in es)
            assertTrue(shown(en[k]!!).length < 120)
        }
        assertTrue("coach_bump_run_fact" in en && "coach_bump_run_fact" in es)
    }

    @Test fun threeShortTipsExistInBothLocales() {
        for (n in 1..3) {
            val k = "tip_bump_run_$n"
            assertTrue(k in en && k in es)
            assertTrue(shown(en[k]!!).length <= 140)
        }
    }

    @Test fun noProtectedNamesInStringsSourcesOrDocs() {
        val banned = listOf("sorry", "parcheesi", "trouble", "aggravation", "frustration", "ludo", "pachisi", "mensch", "slide square", "apology")
        val texts = ownFiles.flatMap { f -> listOf("values", "values-es").map { moduleFile("res/$it/$f").readText() } } +
            screenSource() + moduleFile("java/com/gamesuite/games/bumprun/BumpRunEngine.kt").readText()
        for (t in texts) for (b in banned) {
            // the single allowed mention is the coach-card history line about Pachisi, an ancient public-domain game
            val cleaned = t.replace(Regex("(?i)Pachisi[^<]*"), "").replace(Regex("(?i)Pachisi"), "")
            assertFalse("found '$b'", cleaned.lowercase().contains(b))
        }
    }

    // ---- identity ----

    @Test fun theAccentIsReadableOnEveryThemeSurface() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(BumpRunHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(BumpRunHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, BumpRunHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, BumpRunHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun theAccentIsNotOneAnotherGameAlreadyUses() {
        for ((id, accent) in GameThemes.accents) {
            if (id == "bump-run") continue
            assertTrue("same light accent as $id", accent.light != BumpRunHubSpecs.ACCENT_LIGHT)
            assertTrue("same dark accent as $id", accent.dark != BumpRunHubSpecs.ACCENT_DARK)
        }
    }

    @Test fun theGlyphIsASingleEmoji() {
        assertTrue(BumpRunHubSpecs.GLYPH.codePointCount(0, BumpRunHubSpecs.GLYPH.length) == 1)
    }
}
