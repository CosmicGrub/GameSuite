package com.gamesuite.games.morris

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.settings.CpuDifficulty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The two Hub specs exactly as they are pasted into GameHubSpecs (see the hand-off), checked against
 * this feature's own strings files: every catalog text has an English and a Spanish `hub_*` entry,
 * the launch carries the options the games read, and every string the screens use exists in both locales.
 */
class MorrisHoundsHubSpecTest {

    private val morrisOptions = listOf(
        HubOption.Choice(
            key = "men", label = "Men each", default = "9",
            choices = listOf(
                HubChoice("9", "Nine men", "The classic game."),
                HubChoice("6", "Six men", "A shorter game.")
            )
        ),
        HubOption.Choice(
            key = "flying", label = "Flying", default = "on",
            choices = listOf(
                HubChoice("on", "Flying allowed", "A player down to three men can jump anywhere."),
                HubChoice("off", "No flying", "Men always slide one step along a line.")
            )
        )
    )

    private fun side(label: String) = HubOption.Choice(
        key = "side", label = label, default = "hounds",
        choices = listOf(
            HubChoice("hounds", "The hounds", "Three hunters that never step back."),
            HubChoice("hare", "The hare", "One runner that can go anywhere."),
            HubChoice("random", "Random")
        )
    )

    // Mirrors GameHubSpecs.twoSeat for the Morris entry.
    private val morris = GameHubSpec(
        gameId = "nine-mens-morris", title = "Nine Men's Morris",
        tagline = "Line up three men to form a mill and capture.", family = HubFamily.BOARD,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                route = "nine-mens-morris", start = HubStart.IMMEDIATE, defaultCpus = 1,
                options = morrisOptions + listOf(HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                route = "nine-mens-morris", start = HubStart.READY_SCREEN,
                options = morrisOptions + listOf(HubOptions.firstMover("Player 2", "Player 1"))
            )
        )
    )

    private val hounds = GameHubSpec(
        gameId = "hare-and-hounds", title = "Hare and Hounds",
        tagline = "Three hounds hunt one hare across the board.", family = HubFamily.BOARD,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computer", blurb = "Play against the computer.",
                route = "hare-and-hounds", start = HubStart.IMMEDIATE, defaultCpus = 1,
                options = listOf(side("You play"), HubOptions.difficulty())
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play", blurb = "Two people, one device.",
                route = "hare-and-hounds", start = HubStart.READY_SCREEN,
                options = listOf(side("Player 1 plays"))
            )
        )
    )

    private fun texts(spec: GameHubSpec): Set<String> {
        val out = linkedSetOf(spec.title, spec.tagline)
        for (m in spec.modes) {
            out += m.title; out += m.blurb
            for (o in m.options) {
                out += o.label; out += o.help
                if (o is HubOption.Choice) o.choices.forEach { out += it.label; out += it.help }
            }
        }
        return out.filter { it.isNotBlank() }.toSet()
    }

    private fun resDir(locale: String): File {
        val here = File("src/main/res/$locale")
        return if (here.exists()) here else File("app/src/main/res/$locale")
    }

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? Element ?: continue
            if (n.tagName == "string") out[n.getAttribute("name")] = n.textContent
        }
        return out
    }

    private val en by lazy { strings(File(resDir("values"), "morris_hounds_strings.xml")) }
    private val es by lazy { strings(File(resDir("values-es"), "morris_hounds_strings.xml")) }

    // Texts the app already localises for other games (shared option labels).
    private val shared = setOf(
        "vs Computer", "Play against the computer.", "Pass & Play", "Two people, one device.", "Random",
        "Computer level", "Settings default", "Easy", "Medium", "Hard", "Uses Settings > Default CPU difficulty.",
        "Who moves first", "You", "The computer", "Player 2", "Player 1"
    )

    @Test
    fun `every new catalog text has matching English and Spanish entries under its hub key`() {
        val own = (texts(morris) + texts(hounds)).filter { it !in shared }
        assertTrue(own.isNotEmpty())
        for (t in own) {
            val key = hubKey(t)
            assertTrue("missing en $key for '$t'", key in en)
            assertTrue("missing es $key for '$t'", key in es)
            assertEquals(t, en.getValue(key).replace("\\'", "'"))
            assertTrue("blank es $key", es.getValue(key).isNotBlank())
        }
    }

    @Test
    fun `both locales define exactly the same names, with matching placeholders and escaped apostrophes`() {
        assertEquals(en.keys, es.keys)
        val ph = Regex("%(\\d+\\$)?[sdf]")
        for ((k, v) in en) {
            assertEquals("placeholders of $k", ph.findAll(v).map { it.value }.sorted().toList(), ph.findAll(es.getValue(k)).map { it.value }.sorted().toList())
            assertTrue("bare apostrophe in en $k", !Regex("(?<!\\\\)'").containsMatchIn(v))
            assertTrue("bare apostrophe in es $k", !Regex("(?<!\\\\)'").containsMatchIn(es.getValue(k)))
            assertTrue("blank es $k", es.getValue(k).isNotBlank())
        }
    }

    @Test
    fun `hub keys of the new texts do not collide with each other`() {
        val own = (texts(morris) + texts(hounds)).filter { it !in shared }
        val byKey = own.groupBy { hubKey(it) }
        assertTrue(byKey.filterValues { it.size > 1 }.toString(), byKey.values.all { it.size == 1 })
    }

    @Test
    fun `the screens only use strings that exist in both locales`() {
        val root = if (File("src/main/java").exists()) File("src/main/java") else File("app/src/main/java")
        val ref = Regex("R\\.string\\.((?:morris|hounds)_[a-z0-9_]+)")
        for (f in listOf("NineMensMorrisScreen.kt", "HareAndHoundsScreen.kt")) {
            val src = File(root, "com/gamesuite/ui/$f").readText()
            for (m in ref.findAll(src)) {
                assertTrue("$f uses missing en ${m.groupValues[1]}", m.groupValues[1] in en)
                assertTrue("$f uses missing es ${m.groupValues[1]}", m.groupValues[1] in es)
            }
        }
    }

    @Test
    fun `a Morris launch carries the men and flying options and the cpu level`() {
        val mode = morris.mode("cpu")!!
        val choices = HubChoices(mapOf("men" to "6", "flying" to "off", "difficulty" to "HARD"))
        val launch = (buildHubLaunch(morris, mode, choices) as HubLaunch.Local)
        assertEquals("nine-mens-morris", launch.route)
        assertEquals("6", launch.launch.options.extras["men"])
        assertEquals("off", launch.launch.options.extras["flying"])
        assertEquals(CpuDifficulty.HARD, launch.launch.options.cpuDifficulty)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(MorrisMen.SIX, MorrisMen.fromOptionId(launch.launch.options.extras["men"]))
        // Defaults: nine men, flying on.
        val d = (buildHubLaunch(morris, mode, HubChoices()) as HubLaunch.Local).launch.options.extras
        assertEquals("9", d["men"])
        assertEquals("on", d["flying"])
    }

    @Test
    fun `a Hare and Hounds launch carries the chosen side in both modes`() {
        for (id in listOf("cpu", "pass")) {
            val mode = hounds.mode(id)!!
            val hare = (buildHubLaunch(hounds, mode, HubChoices(mapOf("side" to "hare"))) as HubLaunch.Local)
            assertEquals("hare", hare.launch.options.extras["side"])
            assertEquals("hounds", (buildHubLaunch(hounds, mode, HubChoices()) as HubLaunch.Local).launch.options.extras["side"])
        }
        val cpu = buildHubLaunch(hounds, hounds.mode("cpu")!!, HubChoices()) as HubLaunch.Local
        assertEquals(2, cpu.launch.players.size)
        assertTrue(cpu.launch.players[1].isBot)
        val pass = buildHubLaunch(hounds, hounds.mode("pass")!!, HubChoices()) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode)
    }
}
