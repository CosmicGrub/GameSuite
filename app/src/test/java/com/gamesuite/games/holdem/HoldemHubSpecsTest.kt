package com.gamesuite.games.holdem

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
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Hub spec of Texas Hold'em, the launches it produces, and the EN + ES strings it needs (HubLocalizationTest's rules). */
class HoldemHubSpecsTest : HoldemResourceTest() {
    private val spec: GameHubSpec = HoldemHubSpecs.holdem

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
        for (text in texts(spec)) {
            val key = hubKey(text)
            assertTrue("missing EN $key for '$text'", key in enAll)
            assertTrue("missing ES $key for '$text'", key in esAll)
            assertEquals(text, enAll[key]!!.replace("\\'", "'"))
            assertTrue("blank ES $key", esAll[key]!!.isNotBlank())
            assertEquals("placeholders $key", placeholders(enAll[key]!!), placeholders(esAll[key]!!))
        }
        HUB_SUMMARY_WORDS.forEach { assertTrue(hubKey(it) in enAll && hubKey(it) in esAll) }
    }

    @Test fun noTwoHubTextsShareAKey() {
        val all = texts(spec)
        assertEquals(emptyList<Any>(), all.groupBy { hubKey(it) }.filterValues { it.size > 1 }.values.toList())
    }

    @Test fun theSpecIsShapedLikeTheOtherCardGames() {
        assertEquals("texas-holdem", spec.gameId)
        assertEquals(HubFamily.CARD, spec.family)
        assertEquals("cpu", spec.defaultModeId)
        assertEquals(setOf("texas-holdem"), spec.routes())
        assertTrue(spec.resumable)
        val cpu = spec.mode("cpu")!!
        val pass = spec.mode("pass")!!
        assertEquals(HubModeKind.VS_CPU, cpu.kind)
        assertEquals(HubModeKind.PASS_AND_PLAY, pass.kind)
        assertEquals(2, cpu.minSeats); assertEquals(6, cpu.maxSeats)
        assertEquals(2, pass.minSeats); assertEquals(6, pass.maxSeats)
        // the play-money notice is part of the chips option
        val chips = cpu.options.filterIsInstance<HubOption.Choice>().first { it.key == HoldemHubSpecs.KEY_CHIPS }
        assertTrue(chips.help.contains("play money", ignoreCase = true))
        assertEquals(listOf("500", "1000", "2000", "5000"), chips.choices.map { it.id })
        // every option's default is one of its own choices, and every choice id parses where it must
        for (m in spec.modes) for (o in m.options) if (o is HubOption.Choice) assertTrue("${o.key} default", o.choices.any { it.id == o.default })
        assertEquals(setOf("off", "slow", "normal", "fast"), spec.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == HoldemHubSpecs.KEY_BLINDS }.choices.map { it.id }.toSet())
        assertTrue(BlindSpeed.values().all { b -> spec.mode("cpu")!!.options.filterIsInstance<HubOption.Choice>().first { it.key == HoldemHubSpecs.KEY_BLINDS }.choices.any { it.id == b.id } })
    }

    @Test fun vsComputersLaunchesWithOneHumanAndTheChosenNumberOfComputers() {
        val cpu = spec.mode("cpu")!!
        val launch = buildHubLaunch(spec, cpu, HubChoices(), random = Random(1)) as HubLaunch.Local
        assertEquals("texas-holdem", launch.route)
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, launch.launch.mode)
        assertEquals(4, launch.launch.players.size)
        assertEquals(listOf(false, true, true, true), launch.launch.players.map { it.isBot })
        assertEquals("1000", launch.launch.options.extras[HoldemHubSpecs.KEY_CHIPS])
        assertEquals("normal", launch.launch.options.extras[HoldemHubSpecs.KEY_BLINDS])
        assertEquals("mixed", launch.launch.options.extras[HoldemHubSpecs.KEY_STYLE])
        val five = buildHubLaunch(spec, cpu, HubChoices().with("cpus", 5).with("difficulty", "HARD").with(HoldemHubSpecs.KEY_CHIPS, "5000"), random = Random(1)) as HubLaunch.Local
        assertEquals(6, five.launch.players.size)
        assertEquals(CpuDifficulty.HARD, five.launch.options.cpuDifficulty)
        assertEquals("5000", five.launch.options.extras[HoldemHubSpecs.KEY_CHIPS])
    }

    @Test fun passAndPlayHidesTheComputerOptionsUntilThereAreComputers() {
        val pass = spec.mode("pass")!!
        val none = buildHubLaunch(spec, pass, HubChoices().with("humans", 3).with("cpus", 0).with(HoldemHubSpecs.KEY_STYLE, "tight"), random = Random(1)) as HubLaunch.Local
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, none.launch.mode)
        assertEquals(3, none.launch.players.size)
        assertTrue(none.launch.players.none { it.isBot })
        assertFalse("a hidden option must not reach the game", HoldemHubSpecs.KEY_STYLE in none.launch.options.extras)
        assertTrue(none.launch.options.cpuDifficulty == null)
        val some = buildHubLaunch(spec, pass, HubChoices().with("humans", 2).with("cpus", 2).with(HoldemHubSpecs.KEY_STYLE, "tight"), random = Random(1)) as HubLaunch.Local
        assertEquals(4, some.launch.players.size)
        assertEquals("tight", some.launch.options.extras[HoldemHubSpecs.KEY_STYLE])
    }

    @Test fun theTableNeverExceedsSixSeats() {
        val pass = spec.mode("pass")!!
        val big = buildHubLaunch(spec, pass, HubChoices().with("humans", 6).with("cpus", 4), random = Random(1)) as HubLaunch.Local
        assertEquals(6, big.launch.players.size)
        assertEquals(6, big.launch.players.count { !it.isBot })
        val tiny = buildHubLaunch(spec, pass, HubChoices().with("humans", 2).with("cpus", 0), random = Random(1)) as HubLaunch.Local
        assertEquals(2, tiny.launch.players.size)
    }

    @Test fun staleOrUnknownSavedChoicesFallBackToDefaults() {
        val cpu = spec.mode("cpu")!!
        val r = cpu.resolve(HubChoices().with(HoldemHubSpecs.KEY_CHIPS, "123456").with(HoldemHubSpecs.KEY_BLINDS, "warp").with("cpus", 99))
        assertEquals("1000", r.string(HoldemHubSpecs.KEY_CHIPS, "?"))
        assertEquals("normal", r.string(HoldemHubSpecs.KEY_BLINDS, "?"))
        assertEquals(5, r.int("cpus", -1))
        assertNotNull(spec.title)
    }
}
