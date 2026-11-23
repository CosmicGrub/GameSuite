package com.gamesuite.games.botbastion

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.effectiveAudience
import com.gamesuite.core.effectiveMinAge
import com.gamesuite.core.hubKey
import com.gamesuite.ui.GameThemes
import com.gamesuite.ui.Wcag
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The Hub spec, strings (EN and ES), coach, tips, identity colours and the no-hard-coded-English rule of Bot Bastion. */
class BotBastionHubSpecsTest {
    private val spec = BotBastionHubSpecs.botBastion
    private val ownFiles = listOf("bot_bastion_strings.xml", "bot_bastion_coach_strings.xml", "bot_bastion_hub_strings.xml")

    private fun parseFile(path: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bbModuleFile(path))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private fun parseDir(dir: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        bbModuleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { strings.putAll(parseFile("$dir/${it.name}")) }
        return strings
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value }.sorted().toList()
    private fun shown(raw: String) = raw.replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")

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

    @Test fun theSpecIsALocalFamilyGame() {
        assertEquals("bot-bastion", spec.gameId)
        assertEquals(GameAudience.FAMILY, spec.audience)
        assertEquals(10, spec.minAge)
        assertEquals(GameAudience.FAMILY, effectiveAudience(spec))
        assertEquals(10, effectiveMinAge(spec))
        assertFalse(spec.isNetworked)
        assertEquals(setOf("bot-bastion"), spec.routes())
    }

    @Test fun everyTierIsOfferedAndMatchesAnEngineTier() {
        val tier = spec.mode("play")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == "tier" }
        assertEquals(Tier.values().map { it.id }.toSet(), tier.choices.map { it.id }.toSet())
        assertEquals("captain", tier.default)
        val players = spec.mode("play")!!.options.filterIsInstance<HubOption.Stepper>().first { it.key == "players" }
        assertEquals(1, players.min); assertEquals(BbRules.MAX_PLAYERS, players.max)
    }

    @Test fun aLaunchCarriesTheExtrasTheGameReads() {
        val play = spec.mode("play")!!
        val choices = play.defaults().with("tier", "hero").with("players", 3)
        val launch = buildHubLaunch(spec, play, choices, random = Random(1)) as HubLaunch.Local
        assertEquals("bot-bastion", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals("hero", launch.launch.options.extras["tier"])
        assertEquals("3", launch.launch.options.extras["players"])
        val g = BotBastionGame()
        g.configure(launch.launch.options.extras, shippedMaps())
        assertEquals(Tier.HERO, g.tier); assertEquals(3, g.playerCount)
    }

    @Test fun theNameAvoidsTheSourceGameAndTrademarks() {
        val everything = (texts(spec) + en.filterKeys { it.startsWith("bot_bastion_") }.values + bbModuleFile("assets/bot_bastion/maps.json").readText())
            .joinToString(" ").lowercase()
        for (banned in listOf("zacisa", "crayon", "last stand", "defense of", "nintendo", "wii")) assertFalse(banned, banned in everything)
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
        val allowed = listOf("bot_bastion_", "coach_bot_bastion_", "tip_bot_bastion_", "hub_")
        for (f in ownFiles) for (k in parseFile("res/values/$f").keys) assertTrue("$k has no allowed prefix", allowed.any { k.startsWith(it) })
        for (loc in listOf("values", "values-es")) {
            val others = bbModuleFile("res/$loc").listFiles { x -> x.isFile && x.name.endsWith(".xml") && x.name !in ownFiles }!!
            val otherNames = others.flatMap { parseFile("res/$loc/${it.name}").keys }.toSet()
            for (f in ownFiles) assertEquals("$loc duplicates of $f", emptyList<String>(), parseFile("res/$loc/$f").keys.filter { it in otherNames })
        }
    }

    @Test fun theCoachFileHoldsOnlyTheCoachKeys() {
        val keys = parseFile("res/values/bot_bastion_coach_strings.xml").keys.toList()
        assertEquals((1..5).map { "coach_bot_bastion_s$it" } + "coach_bot_bastion_fact", keys)
    }

    @Test fun threeShortTipsExistInBothLocales() {
        for (n in 1..3) {
            val k = "tip_bot_bastion_$n"
            assertTrue(k in en && k in es)
            assertTrue(shown(en[k]!!).length <= 140)
        }
    }

    @Test fun apostrophesAreEscapedOrAbsent() {
        for (f in ownFiles) for (locale in listOf("values", "values-es")) {
            for ((k, v) in parseFile("res/$locale/$f")) assertFalse("unescaped apostrophe in $locale/$k", Regex("(?<!\\\\)'").containsMatchIn(v))
        }
    }

    private fun uiSource(name: String) = bbModuleFile("java/com/gamesuite/ui/$name").readText()

    @Test fun everyStringTheScreenUsesExistsInBothLocalesAndNoneOfOursIsDead() {
        val src = uiSource("BotBastionScreen.kt") + uiSource("BotBastionArt.kt")
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 60)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val ours = en.keys.filter { it.startsWith("bot_bastion_") }
        assertEquals(emptyList<String>(), ours.filter { it !in used })
    }

    @Test fun noEnglishIsHardCodedInTheScreenTheArtOrTheGame() {
        val sources = listOf(
            uiSource("BotBastionScreen.kt"), uiSource("BotBastionArt.kt"),
            bbModuleFile("java/com/gamesuite/games/botbastion/BotBastionGame.kt").readText()
        )
        val patterns = listOf(
            Regex("""Text\(\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""contentDescription\s*=\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""label\s*=\s*\{\s*Text\(\s*"[^"$]*[A-Za-z]{3,}"""),
            Regex("""announce\s*=\s*"[^"$]*[A-Za-z]{3,}""")
        )
        for (src in sources) for (p in patterns) {
            val hit = p.find(src)
            assertTrue("hard-coded text: ${hit?.value}", hit == null)
        }
    }

    @Test fun theScreenKeepsTheAccessibilityAndPauseCommitments() {
        val src = uiSource("BotBastionScreen.kt")
        assertTrue("a frame clock drives the simulation", "rememberGameFrameClock" in src)
        assertTrue("the arena draws in one Canvas", "Canvas(" in src)
        assertTrue("touch targets are at least 48 dp", "MIN_TARGET = 48.dp" in src)
        assertTrue("live region for announcements", "liveRegion" in src)
        assertTrue("reduced motion is read", "LocalReducedMotion" in src)
        assertTrue("pause is a plain flag toggle", "game.togglePause()" in src)
    }

    // ---- identity ----

    @Test fun theAccentIsReadableOnEveryThemeSurface() {
        for (bg in GameThemes.lightSurfaces) assertTrue(Wcag.contrast(BotBastionHubSpecs.ACCENT_LIGHT, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        for (bg in GameThemes.darkSurfaces) assertTrue(Wcag.contrast(BotBastionHubSpecs.ACCENT_DARK, bg) >= GameThemes.MIN_ACCENT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_LIGHT, BotBastionHubSpecs.ACCENT_LIGHT) >= GameThemes.MIN_TEXT_CONTRAST)
        assertTrue(Wcag.contrast(GameThemes.ON_ACCENT_DARK, BotBastionHubSpecs.ACCENT_DARK) >= GameThemes.MIN_TEXT_CONTRAST)
    }

    @Test fun everyBotAndEnemyHasADistinctColour() {
        assertEquals(BotType.values().size, BotType.values().map { com.gamesuite.ui.BbPalette.bot(it) }.toSet().size)
        assertEquals(EnemyType.values().size, EnemyType.values().map { com.gamesuite.ui.BbPalette.enemy(it) }.toSet().size)
    }
}
