package com.gamesuite.games.spider

import com.gamesuite.core.GameCoach
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.core.hubKey
import com.gamesuite.games.hanafuda.HanafudaOptions
import com.gamesuite.games.hanafuda.YakuSet
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/**
 * Proves the Hub specs of Spider Solitaire and Hanafuda are complete and localized: every spec text has
 * a string named hubKey(text) in English and Spanish (the English repeats the text), the tips and coach
 * strings exist in both, every string the two screens reference exists in both, and the two locales agree
 * on placeholders. The shared HubLocalizationTest enforces the first part once the specs are in
 * GameHubSpecs; this test enforces it before.
 */
class SpiderHanafudaHubSpecsTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val arrays: Map<String, List<String>>)

    private fun parseFile(relative: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val arrays = linkedMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "plurals" -> plurals[e.getAttribute("name")] = (0 until e.getElementsByTagName("item").length).associate {
                    val item = e.getElementsByTagName("item").item(it) as Element
                    item.getAttribute("quantity") to item.textContent
                }
                "string-array" -> arrays[e.getAttribute("name")] = (0 until e.getElementsByTagName("item").length).map { e.getElementsByTagName("item").item(it).textContent }
            }
        }
        return Res(strings, plurals, arrays)
    }

    private fun parseDir(dir: String): Res {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val arrays = linkedMapOf<String, List<String>>()
        moduleFile(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
            val r = parseFile("$dir/${f.name}")
            strings.putAll(r.strings); plurals.putAll(r.plurals); arrays.putAll(r.arrays)
        }
        return Res(strings, plurals, arrays)
    }

    private val en by lazy { parseDir("res/values") }
    private val es by lazy { parseDir("res/values-es") }
    private val ownEn by lazy { parseFile("res/values/spider_hanafuda_strings.xml") }
    private val ownEs by lazy { parseFile("res/values-es/spider_hanafuda_strings.xml") }

    private fun specTexts(): Set<String> {
        val out = linkedSetOf<String>()
        fun add(s: String) { if (s.isNotBlank()) out += s }
        for (spec in SpiderHanafudaHubSpecs.all) {
            add(spec.title); add(spec.tagline)
            for (mode in spec.modes) {
                add(mode.title); add(mode.blurb)
                for (o in mode.options) {
                    add(o.label); add(o.help)
                    if (o is HubOption.Choice) o.choices.forEach { add(it.label); add(it.help) }
                }
            }
        }
        return out
    }

    private val PLACEHOLDER = placeholderRegex()
    private fun shown(raw: String) = raw.replace("\\'", "'")
    private fun placeholderRegex() = Regex("%(\\d+\\$)?[sdf]")
    private fun placeholders(s: String) = placeholderRegex().findAll(s).map { it.value }.sorted().toList()

    // ---- the specs themselves ---------------------------------------------------------------------

    @Test fun spiderSpecOffersPlayAndATodaysDealWithSuitsAndWinnableOptions() {
        val spec = SpiderHanafudaHubSpecs.spiderSolitaire
        assertEquals("spider-solitaire", spec.gameId)
        assertTrue(spec.resumable)
        assertTrue(spec.hasDaily)
        assertEquals(listOf("solo", "daily"), spec.modes.map { it.id })
        val solo = spec.mode("solo")!!
        assertEquals(setOf("suits", "winnable"), solo.options.map { it.key }.toSet())
        assertEquals(setOf("1", "2", "4"), (solo.options.first { it.key == "suits" } as HubOption.Choice).choices.map { it.id }.toSet())
        assertEquals("spider-solitaire-daily", spec.mode("daily")!!.route)
    }

    @Test fun hanafudaSpecOffersCpuAndPassAndPlayWithRoundsAndScoringOptions() {
        val spec = SpiderHanafudaHubSpecs.hanafuda
        assertEquals("hanafuda", spec.gameId)
        assertEquals(listOf("cpu", "pass"), spec.modes.map { it.id })
        for (mode in spec.modes) {
            assertEquals(2, mode.minSeats)
            assertEquals(2, mode.maxSeats)
            assertEquals(setOf("rounds", "yakuSet"), mode.options.map { it.key }.filter { it == "rounds" || it == "yakuSet" }.toSet())
        }
        val rounds = spec.modes[0].options.first { it.key == "rounds" } as HubOption.Choice
        assertEquals(listOf("3", "6", "12"), rounds.choices.map { it.id })
        assertEquals("3", rounds.default)
        val cpu = spec.modes[0]
        assertTrue(cpu.options.any { it.key == HubKeys.DIFFICULTY })
    }

    @Test fun everyOptionDefaultIsOneOfItsChoicesAndAllIdsAreUnique() {
        for (spec in SpiderHanafudaHubSpecs.all) for (mode in spec.modes) for (o in mode.options) {
            if (o is HubOption.Choice) {
                assertTrue("${spec.gameId}/${o.key} default", o.choices.any { it.id == o.default })
                assertEquals(o.choices.size, o.choices.map { it.id }.toSet().size)
            }
        }
    }

    @Test fun hubLaunchesCarryTheGameSpecificChoicesToTheScreens() {
        val spec = SpiderHanafudaHubSpecs.hanafuda
        val mode = spec.mode("cpu")!!
        val choices = HubChoices().with("rounds", "6").with("yakuSet", "full")
        val launch = buildHubLaunch(spec, mode, choices, random = Random(1)) as HubLaunch.Local
        assertEquals("hanafuda", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(2, launch.launch.players.size)
        assertEquals(6, HanafudaOptions.roundsFrom(launch.launch.options.extras))
        assertEquals(YakuSet.FULL, HanafudaOptions.yakuSetFrom(launch.launch.options.extras))

        val pass = buildHubLaunch(spec, spec.mode("pass")!!, HubChoices(), listOf("Ann", "Ben")) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, pass.launch.mode)
        assertEquals(3, HanafudaOptions.roundsFrom(pass.launch.options.extras))

        val spider = SpiderHanafudaHubSpecs.spiderSolitaire
        val s = buildHubLaunch(spider, spider.mode("solo")!!, HubChoices().with("suits", "4").with("winnable", true)) as HubLaunch.Local
        assertEquals(4, SpiderOptions.suitsFrom(s.launch.options.extras))
        assertTrue(SpiderOptions.winnableFrom(s.launch.options.extras))
        val d = buildHubLaunch(spider, spider.mode("daily")!!, HubChoices().with("suits", "2")) as HubLaunch.Local
        assertEquals("spider-solitaire-daily", d.route)
        assertEquals(2, SpiderOptions.suitsFrom(d.launch.options.extras))
        assertEquals(HubModeKind.DAILY, spider.mode("daily")!!.kind)
    }

    @Test fun badOptionValuesFallBackToTheDefaults() {
        assertEquals(1, SpiderOptions.suitsFrom(mapOf("suits" to "3")))
        assertEquals(1, SpiderOptions.suitsFrom(emptyMap()))
        assertEquals(1, SpiderOptions.suitsFrom(mapOf("suits" to "x")))
        assertEquals(3, HanafudaOptions.roundsFrom(mapOf("rounds" to "5")))
        assertEquals(3, HanafudaOptions.roundsFrom(emptyMap()))
        assertEquals(YakuSet.BASIC, HanafudaOptions.yakuSetFrom(mapOf("yakuSet" to "zzz")))
    }

    // ---- hub texts ----------------------------------------------------------------------------------

    @Test fun everySpecTextHasAnEnglishAndSpanishStringNamedByHubKey() {
        // The shared Hub strings ("Play", "vs Computer", the difficulty labels ...) live in other files; ours must resolve across the directory.
        val missingEn = specTexts().filter { hubKey(it) !in en.strings }
        val missingEs = specTexts().filter { hubKey(it) !in es.strings }
        assertTrue("missing in values/: $missingEn", missingEn.isEmpty())
        assertTrue("missing in values-es/: $missingEs", missingEs.isEmpty())
    }

    @Test fun theEnglishEntriesRepeatTheSpecText() {
        val wrong = specTexts().filter { t -> en.strings[hubKey(t)]?.let { shown(it) != t } == true }
        assertTrue("English differs from the spec text: $wrong", wrong.isEmpty())
    }

    @Test fun theSpanishHubTextsAreTranslatedAndKeepTheirPlaceholders() {
        // Titles that are proper names may match; every sentence must differ.
        val untranslated = specTexts().filter { it.length > 14 && hubKey(it) in ownEs.strings && ownEs.strings[hubKey(it)] == ownEn.strings[hubKey(it)] }
        assertTrue("left in English: $untranslated", untranslated.isEmpty())
        for (t in specTexts()) assertEquals(placeholders(t), placeholders(es.strings[hubKey(t)] ?: ""))
    }

    @Test fun noTwoSpecTextsShareAKey() {
        val clashes = specTexts().groupBy { hubKey(it) }.filterValues { it.size > 1 }
        assertTrue("collisions: $clashes", clashes.isEmpty())
    }

    // ---- ui strings -----------------------------------------------------------------------------------

    @Test fun bothLocalesDefineTheSameUiNames() {
        assertEquals(ownEn.strings.keys, ownEs.strings.keys)
        assertEquals(ownEn.plurals.keys, ownEs.plurals.keys)
        assertEquals(ownEn.arrays.keys, ownEs.arrays.keys)
        for ((k, v) in ownEn.arrays) assertEquals("array $k size", v.size, ownEs.arrays[k]!!.size)
        for ((k, v) in ownEn.plurals) assertEquals("plural $k quantities", v.keys, ownEs.plurals[k]!!.keys)
    }

    @Test fun placeholdersMatchBetweenEnglishAndSpanish() {
        val bad = ownEn.strings.filter { (k, v) -> placeholders(v) != placeholders(ownEs.strings[k] ?: "") }.keys
        assertTrue("placeholder mismatch in strings: $bad", bad.isEmpty())
        val badPlurals = ownEn.plurals.filter { (k, qs) -> qs.any { (q, v) -> placeholders(v).toSet() != placeholders(ownEs.plurals[k]!![q] ?: "").toSet() } }.keys
        assertTrue("placeholder mismatch in plurals: $badPlurals", badPlurals.isEmpty())
    }

    @Test fun theSpanishUiIsTranslated() {
        val same = ownEn.strings.filter { (k, v) ->
            (k.startsWith("spd_") || k.startsWith("hf_")) &&
                v.replace(PLACEHOLDER, "").filter { it.isLetter() }.length > 9 && ownEs.strings[k] == v
        }.keys
        assertTrue("sentences left in English: $same", same.isEmpty())
    }

    @Test fun noUnescapedApostropheInAnyOfOurFiles() {
        val bad = Regex("(?<!\\\\)'")
        for (f in listOf(
            "res/values/spider_hanafuda_strings.xml", "res/values-es/spider_hanafuda_strings.xml",
            "res/values/spider_hanafuda_tips_strings.xml", "res/values-es/spider_hanafuda_tips_strings.xml",
            "res/values/spider_hanafuda_coach_strings.xml", "res/values-es/spider_hanafuda_coach_strings.xml"
        )) assertTrue("$f has an unescaped apostrophe", !bad.containsMatchIn(moduleFile(f).readText()))
    }

    @Test fun everyResourceTheTwoScreensReferenceExistsInBothLocales() {
        val files = listOf("ui/SpiderScreen.kt", "ui/HanafudaScreen.kt")
        val problems = mutableListOf<String>()
        for (f in files) {
            val src = moduleFile("java/com/gamesuite/$f").readText()
            for (m in Regex("R\\.string\\.((?:spd|hf)_[a-z0-9_]+)").findAll(src)) {
                val n = m.groupValues[1]
                if (n !in en.strings) problems += "$f: string $n missing in values"
                if (n !in es.strings) problems += "$f: string $n missing in values-es"
            }
            for (m in Regex("R\\.plurals\\.((?:spd|hf)_[a-z0-9_]+)").findAll(src)) {
                val n = m.groupValues[1]
                if (n !in en.plurals) problems += "$f: plural $n missing in values"
                if (n !in es.plurals) problems += "$f: plural $n missing in values-es"
            }
            for (m in Regex("R\\.array\\.((?:spd|hf)_[a-z0-9_]+)").findAll(src)) {
                val n = m.groupValues[1]
                if (n !in en.arrays) problems += "$f: array $n missing in values"
                if (n !in es.arrays) problems += "$f: array $n missing in values-es"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun theArraysHaveOneEntryPerEnumValue() {
        assertEquals(13, ownEn.arrays["spd_rank_names"]!!.size)
        assertEquals(4, ownEn.arrays["spd_suit_names"]!!.size)
        assertEquals(12, ownEn.arrays["hf_month_names"]!!.size)
        assertEquals(com.gamesuite.games.hanafuda.HanaSpecial.entries.size, ownEn.arrays["hf_special_names"]!!.size)
        assertEquals(com.gamesuite.games.hanafuda.HanaRibbon.entries.size, ownEn.arrays["hf_ribbon_names"]!!.size)
        assertEquals(com.gamesuite.games.hanafuda.HanaKind.entries.size, ownEn.arrays["hf_kind_names"]!!.size)
        assertEquals(com.gamesuite.games.hanafuda.Yaku.entries.size, ownEn.arrays["hf_yaku_names"]!!.size)
    }

    // ---- tips and coach -----------------------------------------------------------------------------------

    private val tipNames = listOf("spider_solitaire", "hanafuda").flatMap { id -> (1..3).map { "tip_${id}_$it" } }
    private val tipsEn by lazy { parseFile("res/values/spider_hanafuda_tips_strings.xml").strings }
    private val tipsEs by lazy { parseFile("res/values-es/spider_hanafuda_tips_strings.xml").strings }

    @Test fun threeTipsPerGameInBothLocalesShortAndTranslated() {
        assertEquals(tipNames.toSet(), tipsEn.keys)
        assertEquals(tipNames.toSet(), tipsEs.keys)
        for (n in tipNames) {
            val a = shown(tipsEn[n]!!)
            val b = shown(tipsEs[n]!!)
            assertTrue("$n en too long (${a.length})", a.length in 1..140)
            assertTrue("$n es too long (${b.length})", b.length in 1..140)
            assertNotEquals("$n not translated", a, b)
        }
    }

    private val coachIds = listOf("spider-solitaire" to 5, "hanafuda" to 6)
    private val coachEn by lazy { parseFile("res/values/spider_hanafuda_coach_strings.xml").strings }
    private val coachEs by lazy { parseFile("res/values-es/spider_hanafuda_coach_strings.xml").strings }

    @Test fun coachCardsHaveThreeToSixStepsAndAFactInBothLocalesFollowingGameCoachNames() {
        val expected = coachIds.flatMap { (id, n) -> (1..n).map { GameCoach.stepName(id, it) } + GameCoach.factName(id) }.toSet()
        assertEquals(expected, coachEn.keys)
        assertEquals(expected, coachEs.keys)
        for ((id, n) in coachIds) {
            assertTrue(n in 3..6)
            for (name in (1..n).map { GameCoach.stepName(id, it) } + GameCoach.factName(id)) {
                assertTrue(coachEn[name]!!.isNotBlank())
                assertNotEquals("$name not translated", coachEn[name], coachEs[name])
            }
        }
    }
}
