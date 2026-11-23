package com.gamesuite.games.gemcascade

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.effectiveAudience
import com.gamesuite.core.effectiveMinAge
import com.gamesuite.core.hubKey
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

/** The Hub spec, strings (EN and ES), coach, tips, identity colours and the no-hard-coded-English rule of Gem Cascade. */
class GemCascadeHubSpecsTest {
    private val spec = GemCascadeHubSpecs.gemCascade
    private val ownFiles = listOf("gem_cascade_strings.xml", "gem_cascade_coach_strings.xml", "gem_cascade_hub_strings.xml")

    private fun parseFile(path: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gemModuleFile(path))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private fun parseDir(dir: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        gemModuleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { f ->
            strings.putAll(parseFile("$dir/${f.name}"))
        }
        return strings
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }

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

    @Test fun theSpecIsAFamilyGameFromAgeSixAndLocalOnly() {
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(6, spec.minAge)
        assertEquals(GameAudience.FAMILY, effectiveAudience(spec))
        assertEquals(6, effectiveMinAge(spec))
        assertEquals("gem-cascade", spec.gameId)
        assertTrue(spec.resumable)
        assertFalse(spec.isNetworked)
        assertTrue(spec.modes.none { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE })
        assertEquals(setOf("gem-cascade", "gem-cascade-daily"), spec.routes())
        assertTrue(spec.hasDaily)
    }

    @Test fun theTitleAvoidsTrademarkedNames() {
        val everything = texts(spec).joinToString(" ").lowercase()
        for (banned in listOf("bejeweled", "candy", "crush", "jewel quest", "blitz", "saga")) assertFalse(banned, banned in everything)
    }

    @Test fun everyGameTypeIsOfferedAndMatchesAMode() {
        val type = spec.mode("play")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "type" }
        assertEquals(GemMode.values().map { it.id }.toSet(), type.choices.map { it.id }.toSet())
        assertEquals("classic", type.default)
    }

    @Test fun levelAndSizeAreShownForTheRightTypes() {
        val play = spec.mode("play")!!
        val level = play.options.first { it.key == "level" }
        val size = play.options.first { it.key == "size" }
        assertTrue(level.visibleWhen!!.invoke(HubChoices().with("type", "quest")))
        assertFalse(level.visibleWhen!!.invoke(HubChoices().with("type", "zen")))
        assertFalse(size.visibleWhen!!.invoke(HubChoices().with("type", "quest")))
        assertTrue(size.visibleWhen!!.invoke(HubChoices().with("type", "zen")))
        assertEquals(30, (level as HubOption.Stepper).max)
        assertEquals((6..10).map { "$it" }, (size as HubOption.Choice).choices.map { it.id })
    }

    @Test fun aLaunchCarriesTheExtrasTheGameReads() {
        val play = spec.mode("play")!!
        val choices = play.defaults().with("type", "lightning").with("size", "9").with("switchMode", true)
        val launch = (buildHubLaunch(spec, play, choices, random = Random(1)) as HubLaunch.Local)
        assertEquals("gem-cascade", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(1, launch.launch.players.size)
        assertEquals("lightning", launch.launch.options.extras["type"])
        assertEquals("9", launch.launch.options.extras["size"])
        assertEquals("true", launch.launch.options.extras["switchMode"])
        val game = GemCascadeGame { 5L }
        game.configure(launch.launch.options.extras, bundledLevels())
        assertEquals(GemMode.LIGHTNING, game.mode)
        assertEquals(9, game.size)
        val daily = spec.mode("daily")!!
        val d = buildHubLaunch(spec, daily, daily.defaults(), random = Random(1)) as HubLaunch.Local
        assertEquals("gem-cascade-daily", d.route)
    }

    @Test fun theGameModuleIsASinglePlayerPuzzle() {
        val g = GemCascadeGame { 1L }
        assertEquals("gem-cascade", g.gameId)
        assertEquals(1, g.minPlayers); assertEquals(1, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes)
    }

    @Test fun theGameBlocksADoubleActionUntilSettled() {
        val g = GemCascadeGame { 3L }
        g.mode = GemMode.ZEN
        g.holdInputUntilSettled = true
        g.startMatch()
        val first = g.hint() as GemAction.SwapAction
        assertTrue(g.swap(first.a, first.b)!!.accepted)
        assertEquals(null, g.swap(first.a, first.b))
        g.settled()
        assertNotEquals(null, g.swap(Pos(0, 0), Pos(0, 1)))
        g.pause()
        assertEquals(null, g.swap(first.a, first.b))
    }

    // ---- strings ----

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
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
                if (v.length >= 14) assertNotEquals("ES of $k is still English", v, s[k])
            }
        }
    }

    @Test fun ourNamesCarryOurPrefixAndAreDefinedOnce() {
        val allowed = listOf("gem_cascade_", "coach_gem_cascade_", "tip_gem_cascade_", "hub_")
        for (f in ownFiles) for (k in parseFile("res/values/$f").keys) assertTrue("$k has no allowed prefix", allowed.any { k.startsWith(it) })
        val others = gemModuleFile("res/values").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
        val otherNames = others.flatMap { parseFile("res/values/${it.name}").keys }.toSet()
        for (f in ownFiles) assertEquals("duplicates of $f", emptyList<String>(), parseFile("res/values/$f").keys.filter { it in otherNames })
        val othersEs = gemModuleFile("res/values-es").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
        val otherNamesEs = othersEs.flatMap { parseFile("res/values-es/${it.name}").keys }.toSet()
        for (f in ownFiles) assertEquals("ES duplicates of $f", emptyList<String>(), parseFile("res/values-es/$f").keys.filter { it in otherNamesEs })
    }

    @Test fun theCoachFileHoldsOnlyTheCoachKeys() {
        val keys = parseFile("res/values/gem_cascade_coach_strings.xml").keys
        assertEquals((1..5).map { "coach_gem_cascade_s$it" } + "coach_gem_cascade_fact", keys.toList())
    }

    @Test fun threeShortTipsExistInBothLocales() {
        for (n in 1..3) {
            val k = "tip_gem_cascade_$n"
            assertTrue(k in en && k in es)
            assertTrue(shown(en[k]!!).length <= 140)
        }
    }

    @Test fun apostrophesAreEscaped() {
        for (f in ownFiles) for (locale in listOf("values", "values-es")) {
            for ((k, v) in parseFile("res/$locale/$f")) assertFalse("unescaped apostrophe in $locale/$k", Regex("(?<!\\\\)'").containsMatchIn(v))
        }
    }

    private fun uiSource(name: String) = gemModuleFile("java/com/gamesuite/ui/$name").readText()

    @Test fun everyStringTheScreenUsesExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = uiSource("GemCascadeScreen.kt") + uiSource("GemCascadeArt.kt")
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = en.keys.filter { it.startsWith("gem_cascade_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    @Test fun noEnglishIsHardCodedInTheScreenOrTheGame() {
        val sources = listOf(
            uiSource("GemCascadeScreen.kt"), uiSource("GemCascadeArt.kt"),
            gemModuleFile("java/com/gamesuite/games/gemcascade/GemCascadeGame.kt").readText(),
            gemModuleFile("java/com/gamesuite/games/gemcascade/GemInput.kt").readText()
        )
        val patterns = listOf(
            Regex("""Text\(\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""contentDescription\s*=\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""\blabel\s*=\s*\{\s*Text\(\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""announce\s*=\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""whyText\s*=\s*"[^"$]*[A-Za-z]{3,}""")
        )
        for (src in sources) for (p in patterns) {
            val hit = p.find(src)
            assertTrue("hard-coded text: ${hit?.value}", hit == null)
        }
    }

    // ---- identity ----

    @Test fun theAccentIsReadableOnEveryThemeSurface() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(GemCascadeHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(GemCascadeHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, GemCascadeHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, GemCascadeHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun theGemKindsDifferInShapeAndColour() {
        assertEquals(7, GemKinds.shapes.toSet().size)
        assertEquals(7, GemKinds.argb.toSet().size)
        assertEquals(7, GemKinds.COUNT)
    }
}
