package com.gamesuite.games.sweettrail

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HUB_SUMMARY_WORDS
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.KID_BLOCKED_GAME_IDS
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.core.kidMaySee
import com.gamesuite.core.withoutNetworkModes
import com.gamesuite.profiles.AgeBand
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

/** The Hub spec, resources, coach, tips, identity colours and the source scans of Sweet Trail. */
class SweetTrailHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun repoFile(relative: String): File {
        for (base in listOf(".", "..", "../..")) {
            val f = File(base, relative)
            if (f.exists()) return f
        }
        error("no $relative")
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
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f -> strings += parseFile("$dir/${f.name}") }
        return strings
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val spec = SweetTrailHubSpecs.sweetTrail
    private val ownFiles = listOf("sweet_trail_strings.xml", "sweet_trail_coach_strings.xml")

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

    @Test fun theSpecIsAKidsGameFromAgeThree() {
        assertEquals(GameAudience.KIDS, spec.audience)
        assertEquals(3, spec.minAge)
        assertEquals("sweet-trail", spec.gameId)
        assertEquals("Sweet Trail", spec.title)
        assertTrue(spec.resumable)
    }

    @Test fun theSpecIsNotNetworked() {
        assertTrue(spec.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
        assertFalse(spec.isNetworked)
        assertEquals(setOf("sweet-trail"), spec.routes())
        for (m in spec.modes) { assertEquals(2, m.minSeats); assertEquals(4, m.maxSeats) }
        assertEquals(setOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), spec.modes.map { it.kind }.toSet())
    }

    @Test fun optionKeysAndDefaultsMatchTheDoc() {
        for (m in spec.modes) {
            val length = m.options.filterIsInstance<HubOption.Choice>().first { it.key == "length" }
            assertEquals("standard", length.default)
            assertEquals(listOf("short", "standard", "long"), length.choices.map { it.id })
            val sticky = m.options.filterIsInstance<HubOption.Toggle>().first { it.key == "sticky" }
            assertTrue(sticky.default)
            // one wish choice per table size, each offering only the seats that exist at that size
            for ((key, seats) in listOf("wish" to 2, "wish3" to 3, "wish4" to 4)) {
                val wish = m.options.filterIsInstance<HubOption.Choice>().first { it.key == key }
                assertEquals("none", wish.default)
                assertEquals(listOf("none", "all") + (1..seats).map { "$it" }, wish.choices.map { it.id })
            }
        }
        val cpus = spec.mode("cpu")!!.options.filterIsInstance<HubOption.Stepper>().first { it.key == "cpus" }
        assertEquals(1, cpus.min); assertEquals(3, cpus.max)
    }

    private fun visibleWishKeys(modeId: String, c: HubChoices) =
        spec.mode(modeId)!!.options.filter { it.key.startsWith("wish") && it.visibleWhen!!.invoke(c) }.map { it.key }

    @Test fun onlyTheWishChoiceForTheRealTableSizeIsOffered() {
        assertEquals(listOf("wish"), visibleWishKeys("cpu", HubChoices().with("cpus", 1)))
        assertEquals(listOf("wish3"), visibleWishKeys("cpu", HubChoices().with("cpus", 2)))
        assertEquals(listOf("wish4"), visibleWishKeys("cpu", HubChoices().with("cpus", 3)))
        assertEquals(listOf("wish"), visibleWishKeys("pass", HubChoices().with("humans", 2).with("cpus", 0)))
        assertEquals(listOf("wish3"), visibleWishKeys("pass", HubChoices().with("humans", 2).with("cpus", 1)))
        assertEquals(listOf("wish4"), visibleWishKeys("pass", HubChoices().with("humans", 4).with("cpus", 2)))
    }

    @Test fun aStaleWishChoiceForAMissingSeatNeverReachesTheGame() {
        val cpu = spec.mode("cpu")!!
        val choices = cpu.defaults().with("wish4", "4").with("cpus", 1)
        val extras = (buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local).launch.options.extras
        assertEquals(null, extras["wish4"])
        assertEquals(emptySet<Int>(), sweetTrailWishFromExtras(extras, 2))
    }

    @Test fun difficultyIsHiddenUntilAWishIsChosen() {
        for (m in spec.modes) {
            val level = m.options.first { it.key == "difficulty" }
            assertFalse(level.visibleWhen!!.invoke(HubChoices()))
            assertFalse(level.visibleWhen!!.invoke(HubChoices().with("wish", "none")))
            assertTrue(level.visibleWhen!!.invoke(HubChoices().with("wish", "all")))
            assertTrue(level.visibleWhen!!.invoke(HubChoices().with("wish", "2")))
            // a stale choice for another table size does not count
            val three = HubChoices().with("cpus", 2).with("humans", 1)
            assertFalse(level.visibleWhen!!.invoke(three.with("wish", "all")))
            assertTrue(level.visibleWhen!!.invoke(three.with("wish3", "all")))
        }
    }

    @Test fun aVsComputerLaunchCarriesTheExtras() {
        val cpu = spec.mode("cpu")!!
        val choices = cpu.defaults().with("length", "long").with("sticky", false).with("wish3", "1").with("cpus", 2)
        val launch = (buildHubLaunch(spec, cpu, choices, random = Random(1)) as HubLaunch.Local)
        assertEquals("sweet-trail", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(3, launch.launch.players.size)
        val extras = launch.launch.options.extras
        assertEquals("long", extras["length"]); assertEquals("false", extras["sticky"]); assertEquals("1", extras["wish3"])
        assertEquals(setOf(0), sweetTrailWishFromExtras(extras, 3))
    }

    @Test fun aPassAndPlayLaunchSeatsPeopleThenComputers() {
        val pass = spec.mode("pass")!!
        val launch = (buildHubLaunch(spec, pass, pass.defaults().with("humans", 3).with("cpus", 1), random = Random(1)) as HubLaunch.Local).launch
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, launch.mode)
        assertEquals(listOf(false, false, false, true), launch.players.map { it.isBot })
        assertEquals("standard", launch.options.extras["length"])
        assertEquals("true", launch.options.extras["sticky"])
        val tooMany = (buildHubLaunch(spec, pass, pass.defaults().with("humans", 4).with("cpus", 2), random = Random(1)) as HubLaunch.Local).launch
        assertEquals(4, tooMany.players.size)
    }

    @Test fun kidProfileSeesTheGameAndNoNetworkMode() {
        assertFalse("sweet-trail" in KID_BLOCKED_GAME_IDS)
        for (band in AgeBand.values()) {
            if (band.maxAge >= 3) assertTrue("$band", kidMaySee(spec, band.maxAge))
        }
        assertTrue(kidMaySee(spec, AgeBand.AGE_4_6.maxAge))
        val stripped = spec.withoutNetworkModes()
        assertEquals(spec.modes.size, stripped.modes.size)
        assertFalse(stripped.isNetworked)
    }

    // ---- localisation ----

    @Test fun everyHubTextHasAnEnglishAndASpanishString() {
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
        assertEquals(emptyList<Any>(), texts(spec).groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun spanishPlaceholdersMatchEnglish() {
        for (f in ownFiles) {
            val e = parseFile("res/values/$f")
            val s = parseFile("res/values-es/$f")
            for ((k, v) in e) {
                assertTrue("blank EN $k", v.isNotBlank())
                assertTrue("blank ES $k", s[k]?.isNotBlank() == true)
                assertEquals("placeholders of $k", placeholders(v), placeholders(s[k]!!))
            }
        }
    }

    @Test fun everySweetTrailKeyExistsInBothLocales() {
        for (f in ownFiles) {
            assertEquals("names of $f", parseFile("res/values/$f").keys.toList(), parseFile("res/values-es/$f").keys.toList())
        }
        // the doc's exact key list is the contract
        val doc = repoFile("docs/design/boardgames/sweet-trail.md").readText()
        val documented = Regex("\\| `((?:sweet_trail|coach_sweet_trail)_[a-z0-9_]+)` \\|").findAll(doc).map { it.groupValues[1] }.toSet()
        assertTrue(documented.size > 80)
        for (k in documented) assertTrue("$k missing in values/", k in en && k in es)
    }

    @Test fun ourStringNamesCarryOurPrefixAndAreDefinedOnce() {
        val allowed = listOf("sweet_trail_", "coach_sweet_trail_", "draft_tip_sweet_trail_", "hub_")
        for (f in ownFiles) for (k in parseFile("res/values/$f").keys) assertTrue("$k has no allowed prefix", allowed.any { k.startsWith(it) })
        val coachOnly = parseFile("res/values/sweet_trail_coach_strings.xml").keys
        assertTrue("the coach file holds only coach_ keys", coachOnly.all { it.startsWith("coach_sweet_trail_") })
        val others = moduleFile("res/values").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
        val otherNames = others.flatMap { parseFile("res/values/${it.name}").keys }.toSet()
        for (f in ownFiles) assertEquals("duplicates of $f", emptyList<String>(), parseFile("res/values/$f").keys.filter { it in otherNames })
    }

    @Test fun apostrophesAreEscaped() {
        for (f in ownFiles) for (locale in listOf("values", "values-es")) {
            for ((k, v) in parseFile("res/$locale/$f")) assertFalse("unescaped apostrophe in $locale/$k", Regex("(?<!\\\\)'").containsMatchIn(v))
        }
    }

    private fun screenSources() = listOf("SweetTrailScreen.kt", "SweetTrailArt.kt").map { moduleFile("java/com/gamesuite/ui/$it").readText() }

    @Test fun everyStringTheScreenUsesExistsInBothLocalesAndNoneOfOursIsDead() {
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(screenSources().joinToString("\n")).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 40)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = parseFile("res/values/sweet_trail_strings.xml").keys.filter { it.startsWith("sweet_trail_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    @Test fun theScreenHasNoHardCodedEnglish() {
        for (f in listOf("SweetTrailScreen.kt", "SweetTrailArt.kt")) {
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
        val src = screenSources().joinToString("\n")
        assertFalse("hard-coded sp would ignore the text-size setting", Regex("\\d\\s*\\.sp\\b").containsMatchIn(src))
        assertFalse(src.contains("fontSize ="))
        assertTrue(src.contains("MaterialTheme.typography"))
    }

    @Test fun theScreenKeepsItsAccessibilityPromises() {
        val src = screenSources().joinToString("\n")
        assertTrue("live status line", src.contains("ClStatusLine"))
        assertTrue("assertive why line", src.contains("ClWhyLine"))
        assertTrue("56 dp or larger targets", Regex("heightIn\\(min = 56\\.dp\\)").containsMatchIn(src))
        assertTrue("reduced motion", src.contains("LocalReducedMotion"))
        assertTrue("game speed", src.contains("LocalGameSpeed"))
        assertTrue("pause on focus loss", src.contains("isWindowFocused"))
        assertTrue("fitBoard", src.contains("fitBoard"))
        assertTrue("touch exploration squares", src.contains("rememberClTouchExploration"))
    }

    // ---- coach and tips ----

    @Test fun tutorialLinesAreUnderOneHundredTwentyCharacters() {
        for (map in listOf(en, es)) {
            for (n in 1..5) {
                val k = "coach_sweet_trail_s$n"
                assertTrue("$k missing", k in map)
                assertTrue("$k is ${shown(map[k]!!).length} characters", shown(map[k]!!).length < 120)
            }
            assertTrue("coach_sweet_trail_fact" in map && "coach_sweet_trail_s6" !in map)
        }
        assertNotEquals(en["coach_sweet_trail_s1"], es["coach_sweet_trail_s1"])
    }

    @Test fun tipsMatchTheStringResources() {
        assertEquals(setOf("sweet-trail"), SweetTrailHubSpecs.tips.keys)
        val lines = SweetTrailHubSpecs.tips.getValue("sweet-trail")
        assertEquals(3, lines.size)
        lines.forEachIndexed { i, line ->
            val k = "draft_tip_sweet_trail_${i + 1}"
            assertEquals(line, shown(en[k]!!))
            assertTrue("$k not translated", en[k] != es[k] && es[k]!!.isNotBlank())
            assertTrue("tip too long: $k", line.length <= 140)
        }
        // not yet `tip_*`: GameTipsTest rejects tip strings that no GameTips entry owns (see integration-notes.md)
        assertTrue(en.keys.none { it.startsWith("tip_sweet_trail_") })
    }

    // ---- identity and legal ----

    @Test fun accentColoursPassContrast() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(SweetTrailHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(SweetTrailHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, SweetTrailHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, SweetTrailHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
        for ((id, accent) in GameThemes.accents) {
            if (id == "sweet-trail") continue
            assertTrue("same light accent as $id", accent.light != SweetTrailHubSpecs.ACCENT_LIGHT)
            assertTrue("same dark accent as $id", accent.dark != SweetTrailHubSpecs.ACCENT_DARK)
        }
    }

    @Test fun theGlyphIsASingleEmoji() {
        assertEquals(1, SweetTrailHubSpecs.GLYPH.codePointCount(0, SweetTrailHubSpecs.GLYPH.length))
    }

    /**
     * The release-gate scan. The terms below are MY list of well-known commercial candy-path and path-race titles,
     * companies, places and characters (the owner's own list of protected names was not available to this team);
     * extend it with the owner's list before store release. It scans the strings, the sources and the design doc.
     */
    @Test fun noProtectedNamesInStringsSourcesOrDocs() {
        val protectedTerms = listOf(
            "candy land", "candyland", "hasbro", "milton bradley", "gumdrop mountain", "lollipop woods", "peppermint forest",
            "gloppy", "molasses swamp", "queen frostine", "king kandy", "lord licorice", "princess lolly", "mr. mint",
            "plumpy", "candy castle", "ice cream sea", "chutes and ladders", "snakes and ladders", "parker brothers"
        )
        val files = ArrayList<File>()
        for (locale in listOf("values", "values-es")) files += moduleFile("res/$locale").listFiles { f -> f.name.startsWith("sweet_trail") }!!
        files += moduleFile("java/com/gamesuite/games/sweettrail").listFiles { f -> f.extension in setOf("kt", "md") }!!
        files += listOf("SweetTrailScreen.kt", "SweetTrailArt.kt").map { moduleFile("java/com/gamesuite/ui/$it") }
        files += repoFile("docs/design/boardgames/sweet-trail.md")
        assertTrue(files.size >= 12)
        for (f in files) {
            val text = f.readText().lowercase()
            for (term in protectedTerms) assertFalse("'$term' found in ${f.name}", text.contains(term))
        }
    }
}
