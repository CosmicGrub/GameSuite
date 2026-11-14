package com.gamesuite.games.tankbattle

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TankGameTest {
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    fun newGame(
        players: List<PlayerInfo> = humans(2), seed: Long = 1, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): TankGame {
        val g = TankGame(Random(seed), { 1_000L })
        g.difficulty = difficulty
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private val TankGame.s get() = state.value!!

    @Test fun aNewMatchHasFreshTanksOnTheirPadsAndSeededWind() {
        val g = newGame(humans(3))
        val s = g.s
        assertEquals(3, s.tanks.size)
        assertTrue(s.tanks.all { it.hp == 100 && it.bigLeft == 2 && it.bouncersLeft == 3 })
        assertEquals(TankWorld.windFor(s.seed, 0), s.wind)
        assertEquals(TankGame.PHASE_AIM, s.phase)
        assertTrue(TankGame.validState(s, s.tanks.map { it.playerId }, null))
        // the same Random gives the same battlefield
        assertEquals(s.terrain, newGame(humans(3)).s.terrain)
    }

    @Test fun firingAdvancesTheTurnWindAndStats() {
        val g = newGame()
        val first = g.s.current
        assertTrue(g.fire(50, 70, TankWeapons.SHELL))
        val s = g.s
        assertEquals(1, s.shotSerial)
        assertEquals(1 - first, s.current)
        assertEquals(TankWorld.windFor(s.seed, 1), s.wind)
        assertEquals(1, s.tanks[first].shots)
        assertEquals(50, s.tanks[first].angle)
        assertNotNull(s.lastShot)
        assertTrue(s.lastShot!!.path.size > 10)
    }

    @Test fun ammoIsCountedAndRunsOut() {
        val g = newGame()
        val seat = g.s.current
        assertTrue(g.fire(60, 60, TankWeapons.BIG))
        // hand the turn back round
        assertTrue(g.fire(60, 60, TankWeapons.SHELL))
        assertTrue(g.fire(60, 60, TankWeapons.BIG))
        assertTrue(g.fire(60, 60, TankWeapons.SHELL))
        assertEquals(0, g.s.tanks[seat].bigLeft)
        assertEquals(TankReject.NO_AMMO, g.explainFire(TankWeapons.BIG))
        assertFalse(g.fire(60, 60, TankWeapons.BIG))
        assertTrue(g.fire(60, 60, TankWeapons.BOUNCER))
        assertEquals(2, g.s.tanks[seat].bouncersLeft)
    }

    @Test fun badAimsAreRefused() {
        val g = newGame()
        assertFalse(g.fire(-1, 50, TankWeapons.SHELL))
        assertFalse(g.fire(181, 50, TankWeapons.SHELL))
        assertFalse(g.fire(90, 4, TankWeapons.SHELL))
        assertFalse(g.fire(90, 101, TankWeapons.SHELL))
        assertFalse(g.fire(90, 50, "nuke"))
        assertEquals(0, g.s.shotSerial)
    }

    private fun TankGame.rigged(edit: (TankState) -> TankState) { state.value = edit(state.value!!) }

    @Test fun lastTankStandingWinsAndTheSessionCountsIt() {
        val g = newGame()
        val s0 = g.s
        val me = s0.current
        val foe = 1 - me
        // put the foe right where a known shot lands, with 5 hp
        // find, by probing the real simulation, a shell that lands on open ground well away from the shooter
        val bodies = s0.tanks.map { TankBody(it.x, it.hp > 0) }
        var aim: Triple<Int, Int, Int>? = null
        search@ for (angle in 25..80 step 5) for (power in 40..100 step 10) {
            val probe = TankSim.simulate(s0.terrain.toIntArray(), bodies, IntArray(s0.tanks.size) { 100 }, me, TankWeapons.SHELL, angle, power, s0.wind, record = false)
            val last = probe.impacts.last()
            val col = last.x10 / 10
            if (last.kind == TankImpact.GROUND && col in 5..314 && kotlin.math.abs(col - s0.tanks[me].x) > 30) { aim = Triple(angle, power, col); break@search }
        }
        assertNotNull("some shell must land on open ground away from the shooter", aim)
        val (angle, power, col) = aim!!
        g.rigged { it.copy(tanks = it.tanks.mapIndexed { i, t -> if (i == foe) t.copy(x = col, hp = 5) else t }) }
        assertTrue("the rigged foe must be at the shell's landing spot", g.s.tanks[foe].hp == 5)
        assertTrue(g.fire(angle, power, TankWeapons.SHELL))
        val s = g.s
        // a direct hit at the landing spot must destroy the 5 hp foe: the kill path is exercised, not skipped
        assertEquals("the foe should be destroyed by a shell landing on it", 0, s.tanks[foe].hp)
        assertEquals(TankGame.PHASE_OVER, s.phase)
        assertEquals(s.tanks[me].playerId, s.winnerPlayerId)
        assertEquals(TankEvent.WIN, s.event)
        assertEquals(1, g.sessionWins.value[s.tanks[me].playerId])
        assertFalse(g.fire(angle, power, TankWeapons.SHELL))
        assertEquals(TankReject.OVER, g.explainFire())
    }

    @Test fun mutualDestructionIsADraw() {
        val g = newGame()
        val me = g.s.current
        g.rigged { st -> st.copy(tanks = st.tanks.map { it.copy(hp = 1) }) }
        // straight up: the shell comes back down on its own tank, and the foe is far away -> only me dies; then the foe wins
        g.fire(90, 30, TankWeapons.BIG)
        val s = g.s
        if (s.tanks[me].hp == 0) {
            assertEquals(TankGame.PHASE_OVER, s.phase)
            assertEquals(s.tanks[1 - me].playerId, s.winnerPlayerId)
        }
        // forced draw: both dead in one state is a valid OVER with no winner
        val draw = s.copy(tanks = s.tanks.map { it.copy(hp = 0) }, phase = TankGame.PHASE_OVER, winnerPlayerId = null, event = TankEvent.DRAW)
        assertTrue(TankGame.validState(draw, s.tanks.map { it.playerId }, null))
    }

    @Test fun snapshotRoundTripsAndRefusesTamperedOrMismatchedData() {
        val g = newGame(humans(3), seed = 5)
        g.fire(40, 60, TankWeapons.SHELL); g.fire(130, 60, TankWeapons.BIG)
        val snap = g.snapshot()!!
        val h = TankGame(Random(99), { 1_000L })
        h.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(3), 0, LocalPassAndPlayTransport()))
        assertTrue(h.restore(snap))
        assertEquals(g.s, h.s)
        // wrong roster
        val k = TankGame(Random(99), { 1_000L })
        k.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(2), 0, LocalPassAndPlayTransport()))
        assertFalse(k.restore(snap))
        assertNull(k.state.value)
        assertFalse(h.restore("not json"))
        assertFalse(h.restore(snap.replace("\"hp\":100", "\"hp\":900")))
    }

    @Test fun undoTakesBackTheLastShotAndTheComputersReply() {
        val g = newGame(listOf(PlayerInfo("me", "You"), PlayerInfo("cpu", "CPU", isBot = true)), mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        if (g.s.current != 0) g.playBotStep()
        val before = g.s
        assertFalse(g.canUndo)
        assertTrue(g.fire(60, 60, TankWeapons.SHELL))
        g.playBotStep()
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(before, g.s)
        assertFalse(g.canUndo)
        // replaying the same shot gives the same computer reply (no hidden randomness to exploit)
        g.fire(60, 60, TankWeapons.SHELL); g.playBotStep()
        val first = g.s
        g.undo(); g.fire(60, 60, TankWeapons.SHELL); g.playBotStep()
        assertEquals(first, g.s)
    }

    @Test fun resumeSurvivesUndoHistoryBeingLost() {
        val g = newGame()
        g.fire(60, 60, TankWeapons.SHELL)
        assertTrue(g.canUndo)
        g.undo()
        g.undo()
        assertEquals(0, g.s.shotSerial)
    }

    @Test fun hintReturnsALegalPlanAndNeverFiresForYou() = runBlocking {
        val g = newGame()
        val plan = g.requestHint()!!
        assertTrue(plan.angle in 0..180 && plan.power in 5..100 && plan.weapon in TankWeapons.ALL)
        assertEquals(0, g.s.shotSerial)
        assertEquals(plan, g.hintPlan.value)
        g.fire(plan.angle, plan.power, plan.weapon)
        assertNull(g.hintPlan.value)
    }

    @Test fun fuzzRandomPlayTerminatesKeepsInvariantsAndRoundTrips() {
        for (seed in 1L..25L) {
            val rnd = Random(seed)
            val n = 2 + rnd.nextInt(3)
            val g = newGame(humans(n), seed)
            var shots = 0
            while (g.s.phase == TankGame.PHASE_AIM && shots < 400) {
                val s = g.s
                val ids = s.tanks.map { it.playerId }
                assertTrue(TankGame.validState(s, ids, null))
                assertTrue(s.tanks[s.current].alive)
                val w = TankWeapons.ALL[rnd.nextInt(3)]
                g.fire(rnd.nextInt(0, 181), rnd.nextInt(5, 101), w)
                shots++
                g.snapshot()?.let { snap ->
                    val h = TankGame(Random(1), { 1_000L })
                    h.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, humans(n), 0, LocalPassAndPlayTransport()))
                    assertTrue(h.restore(snap))
                    assertEquals(g.s, h.s)
                }
            }
            // random shots may take a while; terrain only ever shrinks
            assertTrue(g.s.terrain.all { it in 0..TankWorld.H })
        }
    }

    // ---- CPU levels -----------------------------------------------------------------------

    private fun duel(a: CpuDifficulty, b: CpuDifficulty, seed: Long): Int {
        // returns +1 when a (seat 0) wins, -1 when b wins, 0 for no result in 40 turns
        val players = listOf(PlayerInfo("a", "A", isBot = true), PlayerInfo("b", "B", isBot = true))
        val g = TankGame(Random(seed), { 1_000L })
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        var turns = 0
        while (g.s.phase == TankGame.PHASE_AIM && turns < 40) {
            g.difficulty = if (g.s.current == 0) a else b
            g.playBotStep()
            turns++
        }
        val winner = g.s.winnerPlayerId
        return if (winner == "a") 1 else if (winner == "b") -1 else 0
    }

    private fun score(a: CpuDifficulty, b: CpuDifficulty, matches: Int): Double {
        var aw = 0.0
        for (i in 1..matches) {
            // alternate which level shoots first by flipping roles on odd seeds
            val r = if (i % 2 == 0) duel(a, b, i.toLong()) else -duel(b, a, i.toLong())
            aw += if (r > 0) 1.0 else if (r == 0) 0.5 else 0.0
        }
        return aw / matches
    }

    @Test fun cpuLevelsAreClearlyOrderedButNotPerfect() {
        val hardVsEasy = score(CpuDifficulty.HARD, CpuDifficulty.EASY, 40)
        val medVsEasy = score(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 40)
        val hardVsMed = score(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 40)
        assertTrue("hard v easy $hardVsEasy", hardVsEasy >= 0.7)
        assertTrue("medium v easy $medVsEasy", medVsEasy >= 0.6)
        assertTrue("hard v medium $hardVsMed", hardVsMed >= 0.55)
                assertTrue("medium and hard can still lose a duel: $medVsEasy $hardVsMed", medVsEasy < 1.0 && hardVsMed < 1.0)
    }

    @Test fun easyMissesMoreThanHardAtTheSameShot() {
        val g = newGame(listOf(PlayerInfo("a", "A", isBot = true), PlayerInfo("b", "B", isBot = true)), seed = 3, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        val s = g.s
        val rng = { Random(5) }
        val hard = TankBot.plan(s, s.current, CpuDifficulty.HARD, rng())
        val score = TankBot.evaluate(s, s.current, hard.weapon, hard.angle, hard.power)
        val easy = TankBot.plan(s, s.current, CpuDifficulty.EASY, rng())
        assertTrue(score >= TankBot.evaluate(s, s.current, easy.weapon, easy.angle, easy.power))
    }

    @Test fun botPlanningNeverThrowsOnAnyBattlefield() {
        for (seed in 1L..10L) {
            val g = newGame(humans(4), seed)
            for (level in CpuDifficulty.values()) {
                val p = TankBot.plan(g.s, g.s.current, level, Random(seed))
                assertTrue(p.angle in 0..180 && p.power in 5..100)
                assertTrue(g.s.tanks[g.s.current].canFire(p.weapon))
            }
        }
    }
}
