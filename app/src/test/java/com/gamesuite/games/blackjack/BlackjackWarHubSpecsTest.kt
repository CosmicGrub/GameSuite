package com.gamesuite.games.blackjack

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
import com.gamesuite.games.war.WarConfig
import com.gamesuite.games.war.WarVariant
import com.gamesuite.settings.CpuDifficulty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The Hub specs of Blackjack and War, their launches, and every EN + ES string both games ship. */
class BlackjackWarHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseFile(f: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private fun parseDir(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { out.putAll(parseFile(it)) }
        return out
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private fun mine(locale: String) = parseFile(moduleFile("res/$locale/blackjack_war_strings.xml")) +
        parseFile(moduleFile("res/$locale/blackjack_war_coach_strings.xml"))
    private val myEn by lazy { mine("values") }
    private val myEs by lazy { mine("values-es") }

    private fun shown(raw: String) = raw.replace("\\'", "'")
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sdf]").findAll(s).map { it.value }.sorted().toList()

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

    // ---- strings ---------------------------------------------------------------------------------

    @Test fun everyHubTextHasAnEnglishAndASpanishStringUnderItsHubKey() {
        for (spec in BlackjackWarHubSpecs.all) for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in en)
            assertTrue("missing ES $key for '$text'", key in es)
            assertEquals(text, shown(en.getValue(key)).replace("&amp;", "&"))
            assertTrue("blank ES $key", es.getValue(key).isNotBlank())
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in en && hubKey(it) in es) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = BlackjackWarHubSpecs.all.flatMap { texts(it) }.toSet()
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun myStringFilesHaveTheSameKeysInBothLocalesWithMatchingPlaceholders() {
        assertEquals(myEn.keys, myEs.keys)
        assertTrue(myEn.size > 150)
        for (k in myEn.keys) {
            assertEquals("placeholders of $k", placeholders(myEn.getValue(k)), placeholders(myEs.getValue(k)))
            assertTrue("blank $k", myEn.getValue(k).isNotBlank() && myEs.getValue(k).isNotBlank())
        }
    }

    @Test fun noneOfMyResourceNamesCollidesWithAnotherFileOfTheSameLocale() {
        for (locale in listOf("values", "values-es")) {
            val others = moduleFile("res/$locale").listFiles { f -> f.name.endsWith(".xml") && !f.name.startsWith("blackjack_war") }!!
            val seen = HashSet<String>()
            others.forEach { seen += parseFile(it).keys }
            val clashes = (if (locale == "values") myEn else myEs).keys.filter { it in seen }
            assertEquals("duplicate resource names in $locale", emptyList<String>(), clashes)
        }
    }

    @Test fun everyStringTheScreensUseExistsInBothLocales() {
        val src = listOf("ui/BlackjackScreen.kt", "ui/WarScreen.kt").joinToString("\n") { moduleFile("java/com/gamesuite/$it").readText() } +
            moduleFile("java/com/gamesuite/games/blackjack/CasinoCards.kt").readText()
        val used = Regex("R\\.string\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 100)
        for (name in used) {
            assertTrue("R.string.$name missing in values/", name in en)
            assertTrue("R.string.$name missing in values-es/", name in es)
        }
        val arrays = Regex("R\\.array\\.([a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        for (a in arrays) for (locale in listOf("values", "values-es")) {
            assertTrue("array $a in $locale", moduleFile("res/$locale/blackjack_war_strings.xml").readText().contains("name=\"$a\""))
        }
    }

    @Test fun cardNameArraysHaveThirteenRanksAndFourSuitsInEnumOrder() {
        for (locale in listOf("values", "values-es")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile("res/$locale/blackjack_war_strings.xml"))
            val arrays = doc.getElementsByTagName("string-array")
            val sizes = (0 until arrays.length).associate { i ->
                val e = arrays.item(i) as Element
                e.getAttribute("name") to e.getElementsByTagName("item").length
            }
            assertEquals(13, sizes["bjw_rank_names"])
            assertEquals(4, sizes["bjw_suit_names"])
        }
        assertEquals(13, com.gamesuite.games.cards.Rank.entries.size)
        assertEquals(listOf("CLUBS", "DIAMONDS", "HEARTS", "SPADES"), com.gamesuite.games.cards.Suit.entries.map { it.name })
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (locale in listOf("values", "values-es")) for (name in listOf("blackjack_war_strings.xml", "blackjack_war_coach_strings.xml")) {
            Regex(">([^<]*)<").findAll(moduleFile("res/$locale/$name").readText()).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    @Test fun spanishIsTranslatedNotCopied() {
        val sentences = myEn.filter { (k, v) -> !k.startsWith("hub_") && v.length > 18 && !k.endsWith("_card_desc") }
        val untranslated = sentences.filter { (k, v) -> myEs[k] == v }.keys
        assertEquals(emptySet<String>(), untranslated)
    }

    // ---- first-run tips and coach cards (the snippets handed to the integrator) -----------------------

    private val tipLines = mapOf(
        "blackjack" to listOf(
            "Tap chips to build a bet, then press Place bet. Play chips only; no real money.",
            "Hit to draw, Stand to stop, Double to double your bet for one card, Split a pair into two hands.",
            "Go over 21 and you bust. Tap Hint any time for the basic-strategy move."
        ),
        "war" to listOf(
            "Tap Flip. The higher card takes both. Aces are high.",
            "A tie is a war: each tied player puts cards face down, then flips a deciding card.",
            "In Choose 3, pick one of your top three cards in secret. Spend your aces wisely."
        )
    )

    @Test fun tipStringsMatchTheGameTipsLinesExactly() {
        for ((id, lines) in tipLines) {
            assertTrue(lines.size in 2..3)
            lines.forEachIndexed { i, line ->
                val name = "tip_${id}_${i + 1}"
                assertTrue(line.length <= 140)
                assertEquals(line, shown(myEn.getValue(name)))
                assertNotEquals("$name not translated", line, shown(myEs.getValue(name)))
            }
        }
        assertEquals(6, myEn.keys.count { it.startsWith("tip_") })
    }

    @Test fun coachStringsCoverThreeToSixStepsAndATriviaLineInBothLocales() {
        for ((id, count) in mapOf("blackjack" to 5, "war" to 4)) {
            assertTrue(count in 3..6)
            val names = (1..count).map { "coach_${id}_s$it" } + "coach_${id}_fact"
            for (n in names) {
                assertTrue("en $n", myEn[n]?.isNotBlank() == true)
                assertTrue("es $n", myEs[n]?.isNotBlank() == true)
                assertNotEquals("es differs from en for $n", myEn[n], myEs[n])
            }
            assertFalse(myEn.containsKey("coach_${id}_s${count + 1}"))
        }
    }

    // ---- launches --------------------------------------------------------------------------------

    private fun local(spec: GameHubSpec, mode: String, vararg c: Pair<String, String>): HubLaunch.Local {
        var choices = HubChoices()
        c.forEach { choices = choices.with(it.first, it.second) }
        return buildHubLaunch(spec, spec.mode(mode)!!, choices, random = Random(1)) as HubLaunch.Local
    }

    @Test fun blackjackSoloIsOnePersonAgainstTheDealer() {
        val l = local(BlackjackWarHubSpecs.blackjack, "solo")
        assertEquals(1, l.launch.players.size)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.launch.mode)
        assertEquals("blackjack", l.route)
        assertEquals(BjConfig(), BjConfig.fromExtras(l.launch.options.extras))   // the defaults are the standard table
    }

    @Test fun blackjackComputerAndPassAndPlayTablesStayWithinOneToFourSeats() {
        for (cpus in 1..3) assertEquals(cpus + 1, local(BlackjackWarHubSpecs.blackjack, "cpu", "cpus" to "$cpus").launch.players.size)
        val big = local(BlackjackWarHubSpecs.blackjack, "pass", "humans" to "4", "cpus" to "2")
        assertEquals(4, big.launch.players.size)
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, big.launch.mode)
        assertEquals(2, local(BlackjackWarHubSpecs.blackjack, "pass").launch.players.size)
    }

    @Test fun everyBlackjackOptionReachesTheEngineConfig() {
        val l = local(
            BlackjackWarHubSpecs.blackjack, "cpu", "decks" to "2", "chips" to "5000", "payout" to "6:5",
            "hitSoft17" to "true", "insurance" to "false", "surrender" to "false"
        )
        val c = BjConfig.fromExtras(l.launch.options.extras)
        assertEquals(BjConfig(decks = 2, startingChips = 5000, payout = "6:5", hitSoft17 = true, insurance = false, surrender = false), c)
        // every offered choice maps to a legal engine value
        val opts = BlackjackWarHubSpecs.blackjack.modes.flatMap { it.options }.filterIsInstance<HubOption.Choice>()
        opts.filter { it.key == "decks" }.flatMap { it.choices }.forEach { assertTrue(it.id.toInt() in BjRules.DECK_COUNTS) }
        opts.filter { it.key == "chips" }.flatMap { it.choices }.forEach { assertTrue(it.id.toInt() in BjRules.START_STACKS) }
        opts.filter { it.key == "payout" }.flatMap { it.choices }.forEach { assertEquals(it.id, BjPayout.fromId(it.id).id) }
    }

    @Test fun computerLevelAndFirstMoverAreCarriedForBlackjack() {
        val l = local(BlackjackWarHubSpecs.blackjack, "cpu", "difficulty" to "HARD")
        assertEquals(CpuDifficulty.HARD, l.launch.options.cpuDifficulty)
    }

    @Test fun warTablesAndOptions() {
        for (cpus in 1..3) assertEquals(cpus + 1, local(BlackjackWarHubSpecs.war, "cpu", "cpus" to "$cpus").launch.players.size)
        val p = local(BlackjackWarHubSpecs.war, "pass", "humans" to "4", "cpus" to "2")
        assertEquals(4, p.launch.players.size)
        assertEquals("war", p.route)
        val classic = local(BlackjackWarHubSpecs.war, "cpu")
        assertEquals(WarConfig(), WarConfig.fromExtras(classic.launch.options.extras))
        val c3 = local(BlackjackWarHubSpecs.war, "cpu", "variant" to "choose3", "warDown" to "1", "maxRounds" to "600", "difficulty" to "EASY")
        assertEquals(WarConfig(WarVariant.CHOOSE3, 1, 600), WarConfig.fromExtras(c3.launch.options.extras))
        assertEquals(CpuDifficulty.EASY, c3.launch.options.cpuDifficulty)
        // every offered round limit is one the engine accepts
        BlackjackWarHubSpecs.war.modes.flatMap { it.options }.filterIsInstance<HubOption.Choice>().filter { it.key == "maxRounds" }
            .flatMap { it.choices }.forEach { assertTrue(WarConfig(maxRounds = it.id.toInt()).isValid) }
    }

    @Test fun theComputerLevelOnlyShowsWhereItMatters() {
        val mode = BlackjackWarHubSpecs.war.mode("cpu")!!
        val level = mode.options.first { it.key == "difficulty" }
        assertFalse(level.visibleWhen!!.invoke(HubChoices()))
        assertTrue(level.visibleWhen!!.invoke(HubChoices().with("variant", "choose3")))
        // a stale level from Choose 3 does not reach a Classic game
        val classic = local(BlackjackWarHubSpecs.war, "cpu", "variant" to "classic", "difficulty" to "HARD")
        assertFalse(classic.launch.options.extras.containsKey("difficulty"))
    }

    @Test fun specShapes() {
        assertTrue(BlackjackWarHubSpecs.all.all { it.family == HubFamily.CARD && it.resumable })
        assertEquals(4, BlackjackWarHubSpecs.blackjack.modes.maxOf { it.maxSeats })
        assertEquals(4, BlackjackWarHubSpecs.war.modes.maxOf { it.maxSeats })
        assertEquals(2, BlackjackWarHubSpecs.war.modes.minOf { it.minSeats })
        assertEquals(setOf(HubModeKind.SOLO, HubModeKind.VS_CPU, HubModeKind.PASS_AND_PLAY), BlackjackWarHubSpecs.blackjack.modes.map { it.kind }.toSet())
        assertEquals(setOf("blackjack"), BlackjackWarHubSpecs.blackjack.routes())
        assertEquals(setOf("war"), BlackjackWarHubSpecs.war.routes())
        // every default launch has a legal seat count
        for (spec in BlackjackWarHubSpecs.all) for (m in spec.modes) {
            val l = buildHubLaunch(spec, m, m.defaults(), random = Random(1)) as HubLaunch.Local
            assertTrue(l.launch.players.size in m.minSeats..m.maxSeats)
        }
    }

    @Test fun networkLobbiesNeverStartWithOneSeat() {
        val built = com.gamesuite.core.GameHubSpecs.buildAll(setOf("blackjack", "matching", "speed"))
        for (id in listOf("blackjack", "matching", "speed")) {
            val spec = built.firstOrNull { it.gameId == id } ?: continue
            for (m in spec.modes.filter { it.kind == HubModeKind.NEARBY || it.kind == HubModeKind.ONLINE }) {
                assertTrue("$id/${m.id} minSeats ${m.minSeats}", m.minSeats >= 2)
            }
        }
    }
}
