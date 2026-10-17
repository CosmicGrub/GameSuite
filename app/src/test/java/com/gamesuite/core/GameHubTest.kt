package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.GameSpeed
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameHubSpecsTest {
    private val specs = GameHubSpecs.buildAll(emptySet())

    @Test
    fun everyGameHasAUniqueIdAndModes() {
        assertEquals(specs.size, specs.map { it.gameId }.toSet().size)
        assertTrue(specs.size >= 29)
        specs.forEach { s ->
            assertTrue("${s.gameId} has modes", s.modes.isNotEmpty())
            assertEquals("${s.gameId} mode ids unique", s.modes.size, s.modes.map { it.id }.toSet().size)
            assertNotNull("${s.gameId} default mode exists", s.mode(s.defaultModeId))
        }
    }

    @Test
    fun everyModeBuildsALaunchWithItsDefaults() {
        specs.forEach { s ->
            s.modes.forEach { m ->
                val launch = buildHubLaunch(s, m, m.defaults(), random = Random(1))
                if (launch is HubLaunch.Local) {
                    val n = launch.launch.players.size
                    assertTrue("${s.gameId}/${m.id}: $n seats within ${m.minSeats}..${m.maxSeats}", n in m.minSeats..m.maxSeats)
                    assertTrue(launch.launch.localPlayerIndex in 0 until n)
                    assertTrue("${s.gameId}/${m.id} first mover in range", launch.launch.options.firstMoverIndex in 0 until n)
                    assertEquals("${s.gameId}/${m.id} player ids unique", n, launch.launch.players.map { it.playerId }.toSet().size)
                }
            }
        }
    }

    @Test
    fun networkModesAppearOnlyForListedGames() {
        assertFalse(GameHubSpecs.buildAll(emptySet()).first { it.gameId == "uno" }.isNetworked)
        val withNet = GameHubSpecs.buildAll(setOf("uno", "chess"))
        assertTrue(withNet.first { it.gameId == "uno" }.isNetworked)
        assertTrue(withNet.first { it.gameId == "chess" }.isNetworked)
        assertFalse(withNet.first { it.gameId == "checkers" }.isNetworked)
        val nearby = withNet.first { it.gameId == "chess" }.mode("nearby")!!
        assertEquals(HubStart.LOBBY, nearby.start)
        assertEquals("chess", nearby.route)
    }

    @Test
    fun puzzlesOfferFreePlayAndADailyOnItsOwnRoute() {
        val sudoku = specs.first { it.gameId == "sudoku" }
        assertTrue(sudoku.hasDaily)
        assertEquals("sudoku", sudoku.mode("free")!!.route)
        assertEquals("sudoku-daily", sudoku.mode("daily")!!.route)
        assertEquals(HubModeKind.DAILY, sudoku.mode("daily")!!.kind)
    }

    @Test
    fun onlyNonResumableOptionlessSingleModeGamesSkipTheHub() {
        assertTrue(specs.first { it.gameId == "party-toolkit" }.skipsHub)
        assertTrue(specs.first { it.gameId == "breakout" }.skipsHub)
        assertFalse("solitaire resumes, so it needs the Hub's Resume card", specs.first { it.gameId == "solitaire" }.skipsHub)
        assertFalse(specs.first { it.gameId == "chess" }.skipsHub)
    }

    @Test
    fun ticTacToeVariantsChooseTheirOwnRoute() {
        val ttt = specs.first { it.gameId == "tic-tac-toe" }
        val cpu = ttt.mode("cpu")!!
        fun route(variant: String): String =
            (buildHubLaunch(ttt, cpu, cpu.defaults().with("variant", variant)) as HubLaunch.Local).route
        assertEquals("tic-tac-toe", route("classic"))
        assertEquals("tic-tac-toe-misere", route("misere"))
        assertEquals("tic-tac-toe-wild", route("wild"))
        assertEquals("a bogus variant falls back to classic", "tic-tac-toe", route("zzz"))
    }

    @Test
    fun unoPresetsOnlyTouchRealKeysAndStayInRange() {
        val rules = GameHubSpecs.unoRuleOptions()
        val keys = rules.map { it.key }.toSet()
        val preset = rules.first { it.key == "preset" } as HubOption.Choice
        preset.choices.forEach { c ->
            assertTrue("${c.id} applies only known keys", keys.containsAll(c.applies.keys))
        }
        val hand = rules.first { it.key == "handSize" } as HubOption.Stepper
        preset.choices.forEach { c -> c.applies["handSize"]?.let { assertTrue(it.toInt() in hand.min..hand.max) } }
        val target = rules.first { it.key == "targetScore" } as HubOption.Choice
        preset.choices.forEach { c -> c.applies["targetScore"]?.let { v -> assertTrue(target.choices.any { it.id == v }) } }
    }
}

class HubLaunchTest {
    private val specs = GameHubSpecs.buildAll(emptySet())
    private val chess = specs.first { it.gameId == "chess" }
    private val uno = specs.first { it.gameId == "uno" }

    private fun local(spec: GameHubSpec, modeId: String, c: HubChoices? = null, names: List<String> = emptyList(), random: Random = Random(3)): MatchLaunch {
        val mode = spec.mode(modeId)!!
        return (buildHubLaunch(spec, mode, c ?: mode.defaults(), names, random) as HubLaunch.Local).launch
    }

    @Test
    fun vsComputerIsYouAndOneCpuWithNoOverrideByDefault() {
        val l = local(chess, "cpu")
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.mode)
        assertEquals(listOf("You", "CPU"), l.players.map { it.displayName })
        assertEquals(listOf(false, true), l.players.map { it.isBot })
        assertNull(l.options.cpuDifficulty)
        assertEquals(0, l.options.firstMoverIndex)
    }

    @Test
    fun chosenDifficultyAndFirstMoverFlowIntoTheOptions() {
        val mode = chess.mode("cpu")!!
        val c = mode.defaults().with(HubKeys.DIFFICULTY, "HARD").with(HubKeys.FIRST, HubKeys.FIRST_OPPONENT)
        val l = local(chess, "cpu", c)
        assertEquals(CpuDifficulty.HARD, l.options.cpuDifficulty)
        assertEquals(1, l.options.firstMoverIndex)
    }

    @Test
    fun passAndPlayHasTwoNamedHumansAndNoDifficulty() {
        val l = local(chess, "pass", names = listOf("  Ann   Lee ", ""))
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.mode)
        assertEquals(listOf("Ann Lee", "Player 2"), l.players.map { it.displayName })
        assertTrue(l.players.none { it.isBot })
        assertNull(l.options.cpuDifficulty)
    }

    @Test
    fun randomFirstMoverIsDeterministicForASeedAndStaysInTheSeatRange() {
        val mode = chess.mode("cpu")!!
        val c = mode.defaults().with(HubKeys.FIRST, HubKeys.FIRST_RANDOM)
        val a = local(chess, "cpu", c, random = Random(11)).options.firstMoverIndex
        val b = local(chess, "cpu", c, random = Random(11)).options.firstMoverIndex
        assertEquals(a, b)
        val seen = (0 until 300).map { local(chess, "cpu", c, random = Random(it)).options.firstMoverIndex }.toSet()
        assertEquals("both seats come up and none other", setOf(0, 1), seen)
    }

    @Test
    fun aGameWithoutAFirstMoverOptionAlwaysStartsAtSeatZero() {
        val mode = uno.mode("cpu")!!
        val c = mode.defaults().with(HubKeys.FIRST, HubKeys.FIRST_RANDOM)
        assertEquals(0, local(uno, "cpu", c, random = Random(5)).options.firstMoverIndex)
    }

    @Test
    fun unoCpuTableScalesFromTwoToTenSeatsAndClamps() {
        val mode = uno.mode("cpu")!!
        assertEquals(3, local(uno, "cpu").players.size)
        assertEquals(10, local(uno, "cpu", mode.defaults().with(HubKeys.CPUS, 9)).players.size)
        assertEquals("a stepper beyond its range is clamped", 10, local(uno, "cpu", mode.defaults().with(HubKeys.CPUS, 40)).players.size)
        assertEquals(2, local(uno, "cpu", mode.defaults().with(HubKeys.CPUS, 0)).players.size)
    }

    @Test
    fun unoPassAndPlayMixesHumansAndComputersAndNeverDropsAHuman() {
        val mode = uno.mode("pass")!!
        val l = local(uno, "pass", mode.defaults().with(HubKeys.HUMANS, 3).with(HubKeys.CPUS, 2), names = listOf("A", "B", "C"))
        assertEquals(5, l.players.size)
        assertEquals(listOf("A", "B", "C", "CPU 1", "CPU 2"), l.players.map { it.displayName })
        assertEquals(listOf(false, false, false, true, true), l.players.map { it.isBot })
        val crowded = local(uno, "pass", mode.defaults().with(HubKeys.HUMANS, 6).with(HubKeys.CPUS, 8))
        assertEquals(10, crowded.players.size)
        assertEquals(6, crowded.players.count { !it.isBot })
    }

    @Test
    fun passAndPlayNeverHasFewerThanTwoPeople() {
        val mode = uno.mode("pass")!!
        val l = local(uno, "pass", mode.defaults().with(HubKeys.HUMANS, 1).with(HubKeys.CPUS, 2))
        assertEquals("the humans stepper clamps up to its minimum of 2", 2, l.players.count { !it.isBot })
        assertEquals(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, l.mode)
    }

    @Test
    fun aLoneHumanAtATeamsTablePlaysInVsBotMode() {
        val mode = uno.mode("teams")!!
        val l = local(uno, "teams", mode.defaults().with(HubKeys.HUMANS, 1).with(HubKeys.CPUS, 3))
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, l.mode)
        assertEquals("You", l.players.first().displayName)
        assertEquals(4, l.players.size)
    }

    @Test
    fun teamsPairOppositeSeatsAndPadAnOddTableWithACpu() {
        val mode = uno.mode("teams")!!
        val even = local(uno, "teams", mode.defaults().with(HubKeys.HUMANS, 2).with(HubKeys.CPUS, 2))
        assertEquals(4, even.players.size)
        assertEquals(listOf(0, 1, 0, 1), even.players.map { it.teamId })
        val odd = local(uno, "teams", mode.defaults().with(HubKeys.HUMANS, 1).with(HubKeys.CPUS, 2))
        assertEquals("an odd table gains a CPU so the teams are even", 4, odd.players.size)
        assertEquals(listOf(0, 1, 0, 1), odd.players.map { it.teamId })
    }

    @Test
    fun gameSpecificChoicesArriveAsExtrasAndStandardKeysDoNot() {
        val mode = uno.mode("cpu")!!
        val c = mode.defaults().with("stackDraw", true).with("handSize", 9)
        val extras = local(uno, "cpu", c).options.extras
        assertEquals("true", extras["stackDraw"])
        assertEquals("9", extras["handSize"])
        assertFalse(extras.containsKey(HubKeys.CPUS))
        assertFalse(extras.containsKey(HubKeys.DIFFICULTY))
    }

    @Test
    fun networkedModesOpenTheLobbyInsteadOfLaunching() {
        val net = GameHubSpecs.buildAll(setOf("uno")).first { it.gameId == "uno" }
        val r = buildHubLaunch(net, net.mode("nearby")!!, HubChoices())
        assertTrue(r is HubLaunch.Lobby)
        assertEquals(HubModeKind.NEARBY, (r as HubLaunch.Lobby).kind)
        assertEquals("uno", r.gameId)
    }

    @Test
    fun resolveRepairsStaleValues() {
        val mode = uno.mode("cpu")!!
        val stale = HubChoices(mapOf(HubKeys.CPUS to "99", HubKeys.DIFFICULTY to "IMPOSSIBLE", "handSize" to "x", "stackDraw" to "true"))
        val fixed = mode.resolve(stale)
        assertEquals(9, fixed.int(HubKeys.CPUS, -1))
        assertEquals(HubKeys.DIFFICULTY_DEFAULT, fixed.string(HubKeys.DIFFICULTY, "?"))
        assertEquals(7, fixed.int("handSize", -1))
        assertTrue(fixed.bool("stackDraw", false))
    }
}

class HubChoicesStoreTest {
    private class MemoryKv : KeyValues {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String) { data[key] = value }
    }

    private val spec = GameHubSpecs.buildAll(emptySet()).first { it.gameId == "chess" }

    @Test
    fun roundTripsAModesChoices() {
        val store = HubChoicesStore(MemoryKv())
        val mode = spec.mode("cpu")!!
        val chosen = mode.defaults().with(HubKeys.DIFFICULTY, "HARD").with(HubKeys.FIRST, HubKeys.FIRST_RANDOM)
        store.save("chess", "cpu", chosen)
        assertEquals(chosen, store.load("chess", mode))
    }

    @Test
    fun anUnseenModeLoadsItsDefaults() {
        val mode = spec.mode("pass")!!
        assertEquals(mode.defaults(), HubChoicesStore(MemoryKv()).load("chess", mode))
    }

    @Test
    fun garbageStorageFallsBackToDefaultsWithoutThrowing() {
        val kv = MemoryKv()
        kv.put("choices.chess.cpu", "{not json")
        val mode = spec.mode("cpu")!!
        assertEquals(mode.defaults(), HubChoicesStore(kv).load("chess", mode))
    }

    @Test
    fun theLastModeIsRememberedAndAStaleOneFallsBack() {
        val kv = MemoryKv()
        val store = HubChoicesStore(kv)
        assertEquals(spec.defaultModeId, store.lastModeId(spec))
        store.setLastMode("chess", "pass")
        assertEquals("pass", store.lastModeId(spec))
        store.setLastMode("chess", "teleport")
        assertEquals(spec.defaultModeId, store.lastModeId(spec))
    }
}

class GameSpeedTest {
    @Test
    fun normalIsTheIdentity() = assertEquals(700L, GameSpeed.NORMAL.scale(700L))

    @Test
    fun slowerSpeedsStretchAndQuickShortens() {
        assertEquals(2400L, GameSpeed.SLOW.scale(1000L))
        assertEquals(1500L, GameSpeed.RELAXED.scale(1000L))
        assertEquals(550L, GameSpeed.QUICK.scale(1000L))
    }

    @Test
    fun aPositiveDelayNeverCollapsesToZeroAndZeroStaysZero() {
        assertEquals(1L, GameSpeed.QUICK.scale(1L))
        assertEquals(0L, GameSpeed.QUICK.scale(0L))
        assertEquals(0L, GameSpeed.SLOW.scale(-5L))
    }

    @Test
    fun nextCyclesThroughEverySpeedAndWraps() {
        var s = GameSpeed.SLOW
        val seen = mutableListOf(s)
        repeat(GameSpeed.values().size) { s = s.next(); seen += s }
        assertEquals(GameSpeed.SLOW, seen.last())
        assertEquals(GameSpeed.values().toSet(), seen.toSet())
    }

    @Test
    fun anUnknownStoredNameFallsBackToNormal() {
        assertEquals(GameSpeed.NORMAL, GameSpeed.fromName(null))
        assertEquals(GameSpeed.NORMAL, GameSpeed.fromName("WARP"))
        assertEquals(GameSpeed.RELAXED, GameSpeed.fromName("RELAXED"))
    }
}
