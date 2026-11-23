package com.gamesuite.games.tankbattle

import com.gamesuite.core.GameHubSpec
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** Hub specs, launches, tips, coach cards, accents and every string the two screens use, in EN and ES. */
class TankGalleryHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseDir(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
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
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()
    private val ids = listOf("tank-battle", "shooting-gallery")
    private fun under(id: String) = id.replace('-', '_')

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
        for (spec in TankGalleryHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
            assertEquals("placeholders of $key", placeholders(en[key]!!), placeholders(es[key]!!))
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = TankGalleryHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun everyOwnStringExistsInBothLocalesWithMatchingPlaceholders() {
        val prefixes = listOf("tkb_", "sgl_", "tip_tank_battle", "tip_shooting_gallery", "coach_tank_battle", "coach_shooting_gallery")
        val mine = en.keys.filter { k -> prefixes.any { k.startsWith(it) } }
        assertTrue(mine.size > 120)
        for (k in mine) {
            assertTrue("no ES for $k", k in es)
            assertTrue("blank ES $k", es[k]!!.isNotBlank())
            assertEquals("placeholders of $k", placeholders(en[k]!!), placeholders(es[k]!!))
        }
        val esOnly = es.keys.filter { k -> prefixes.any { k.startsWith(it) } }.toSet() - en.keys
        assertEquals(emptySet<String>(), esOnly)
    }

    @Test fun everyStringTheScreensReferenceExistsInBothLocales() {
        val src = listOf("TankBattleScreen.kt", "ShootingGalleryScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 100)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
    }

    @Test fun noHardCodedUserFacingTextInTheScreens() {
        val src = listOf("TankBattleScreen.kt", "ShootingGalleryScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/ui/$it").readText() }
        val literal = Regex("Text\\(\"([A-Za-z][A-Za-z ]{3,})\"")
        assertEquals(emptyList<String>(), literal.findAll(src).map { it.groupValues[1] }.toList())
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (d in listOf("values", "values-es")) {
            moduleFile("res/$d").listFiles { f -> f.name.startsWith("tank_gallery") }!!.forEach { f ->
                Regex(">([^<]*)<").findAll(f.readText()).forEach { m ->
                    assertFalse("unescaped apostrophe in ${f.name}: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
                }
            }
        }
    }

    @Test fun tipsAreThreeShortLinesEqualToTheirStringResources() {
        for ((id, lines) in TankGalleryHubSpecs.tips) {
            assertEquals(3, lines.size)
            lines.forEachIndexed { i, line ->
                assertTrue(line.length <= 140)
                val key = "tip_${under(id)}_${i + 1}"
                assertEquals(line, shown(en[key]!!))
                assertTrue(es[key]!!.isNotBlank())
            }
        }
    }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLocales() {
        for (id in ids.map(::under)) {
            val steps = (1..6).filter { "coach_${id}_s$it" in en }
            assertTrue("$id steps $steps", steps.size in 3..6 && steps == (1..steps.size).toList())
            for (n in steps) assertTrue("coach_${id}_s$n ES", "coach_${id}_s$n" in es)
            assertTrue("coach_${id}_fact" in en && "coach_${id}_fact" in es)
        }
        // the coach strings live in a *coach_strings.xml file, where GameCoachTest looks for them
        assertTrue(moduleFile("res/values/tank_gallery_coach_strings.xml").readText().contains("coach_tank_battle_s1"))
        assertTrue(moduleFile("res/values-es/tank_gallery_coach_strings.xml").readText().contains("coach_shooting_gallery_fact"))
    }

    // ---- accents ------------------------------------------------------------------------------

    @Test fun accentsAreUniqueLegibleInBothThemesAndLabelsReadable() {
        val others = GameThemes.accents.filterKeys { it !in ids }
        for ((id, pair) in TankGalleryHubSpecs.accents) {
            assertFalse("$id light accent duplicates another game", others.values.any { it.light == pair.first })
            assertFalse("$id dark accent duplicates another game", others.values.any { it.dark == pair.second })
            GameThemes.lightSurfaces.forEach { assertTrue("$id light ${Wcag.contrast(pair.first, it)}", Wcag.contrast(pair.first, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            GameThemes.darkSurfaces.forEach { assertTrue("$id dark ${Wcag.contrast(pair.second, it)}", Wcag.contrast(pair.second, it) >= GameThemes.MIN_ACCENT_CONTRAST) }
            for (a in listOf(pair.first, pair.second)) {
                assertTrue(Wcag.contrast(GameThemes.onAccent(a), a) >= GameThemes.MIN_TEXT_CONTRAST)
            }
        }
        assertEquals(ids.toSet(), TankGalleryHubSpecs.accents.keys)
    }

    // ---- launches ------------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun modesAndSeatCountsMatchTheEngines() {
        val tank = TankGalleryHubSpecs.tank
        assertEquals(listOf(HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), tank.modes.map { it.kind })
        val gallery = TankGalleryHubSpecs.gallery
        assertEquals(listOf(HubModeKind.SOLO, HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), gallery.modes.map { it.kind })
        assertTrue(tank.resumable && gallery.resumable)
        // a networked table (added from these specs) needs at least two seats and at most four
        for (spec in TankGalleryHubSpecs.all) {
            val shared = spec.modes.filter { it.kind != HubModeKind.SOLO }
            assertTrue(shared.all { it.minSeats == 2 && it.maxSeats == 4 })
        }
        assertEquals(1, gallery.mode("practice")!!.maxSeats)
    }

    @Test fun seatsComputersAndOptionsReachTheGames() {
        for (cpus in 1..3) {
            val l = local(TankGalleryHubSpecs.tank, "cpu", "cpus" to cpus.toString())
            assertEquals(cpus + 1, l.launch.players.size)
            assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
            assertEquals("tank-battle", l.route)
        }
        val pass = local(TankGalleryHubSpecs.tank, "pass", "humans" to "3", "cpus" to "1")
        assertEquals(4, pass.launch.players.size)
        val t = local(TankGalleryHubSpecs.tank, "cpu", "wind" to "false", "specials" to "false", "difficulty" to "HARD", "first" to "opponent")
        assertEquals("false", t.launch.options.extras["wind"])
        assertEquals("false", t.launch.options.extras["specials"])
        assertEquals(CpuDifficulty.HARD, t.launch.options.cpuDifficulty)
        assertEquals(1, t.launch.options.firstMoverIndex)
        assertEquals("true", local(TankGalleryHubSpecs.tank, "cpu").launch.options.extras["wind"])

        val solo = local(TankGalleryHubSpecs.gallery, "practice", "pace" to "relaxed", "rounds" to "5")
        assertEquals(1, solo.launch.players.size)
        assertEquals("relaxed", solo.launch.options.extras["pace"])
        assertEquals("5", solo.launch.options.extras["rounds"])
        assertEquals("3", local(TankGalleryHubSpecs.gallery, "practice").launch.options.extras["rounds"])
        assertEquals("normal", local(TankGalleryHubSpecs.gallery, "practice").launch.options.extras["pace"])
    }

    @Test fun theRaceFormatIsOnlyOfferedForExactlyTwoSeats() {
        val g = TankGalleryHubSpecs.gallery
        val cpuFormat = g.mode("cpu")!!.options.first { it.key == "format" }
        assertTrue(cpuFormat.visibleWhen!!(HubChoices().with("cpus", 1)))
        assertFalse(cpuFormat.visibleWhen!!(HubChoices().with("cpus", 2)))
        val passFormat = g.mode("pass")!!.options.first { it.key == "format" }
        assertTrue(passFormat.visibleWhen!!(HubChoices().with("humans", 2).with("cpus", 0)))
        assertTrue(passFormat.visibleWhen!!(HubChoices().with("humans", 1 + 1).with("cpus", 0)))
        assertFalse(passFormat.visibleWhen!!(HubChoices().with("humans", 3).with("cpus", 0)))
        assertFalse(passFormat.visibleWhen!!(HubChoices().with("humans", 2).with("cpus", 1)))
        val race = local(g, "cpu", "format" to "race")
        assertEquals("race", race.launch.options.extras["format"])
        // a stale race choice with three seats does not reach the game
        val stale = local(g, "cpu", "format" to "race", "cpus" to "2")
        assertFalse(stale.launch.options.extras.containsKey("format"))
    }
}
