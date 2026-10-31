package com.gamesuite.games.toytennis

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.toysoccer.ToyClock
import com.gamesuite.games.toysoccer.ToyFakeTransport
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

object TennisKit {
    val humans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Ben"))
    val vsBot = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("bot1", "CPU", isBot = true))
    val bots = listOf(PlayerInfo("bot1", "A", isBot = true), PlayerInfo("bot2", "B", isBot = true))

    fun newGame(
        players: List<PlayerInfo> = vsBot, seed: Int = 1, clock: ToyClock = ToyClock(), format: String = "points7",
        assist: Boolean = false, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, first: Int = 0,
        mode: PlayMode = if (players.any { it.isBot }) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        results: MutableList<GameResult>? = null, start: Boolean = true
    ): ToyTennisGame {
        val g = ToyTennisGame(Random(seed), clock)
        g.difficulty = difficulty; g.assist = assist; g.formatId = format; g.firstServer = first
        g.init(GameContext(mode, players, 0, ToyFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        if (start) g.startMatch()
        return g
    }

    fun court(g: ToyTennisGame): TennisState = g.state.value!!.court

    fun runUntil(g: ToyTennisGame, max: Int = 60_000, cond: (TennisState) -> Boolean) {
        var n = 0
        while (!cond(court(g)) && n < max) { g.stepTicks(1); n++ }
        assertTrue("condition reached", cond(court(g)))
    }
}

class ToyTennisGameTest {
    private val K = TennisKit

    @Test fun aNewMatchStartsWithAServePause() {
        val g = K.newGame()
        val c = K.court(g)
        assertEquals(TPhase.PREP, c.phase)
        assertEquals(0, c.server)
        assertEquals(TennisFormat.POINTS7, g.state.value!!.format)
        assertEquals(TennisReject.NONE, g.swing(0, 0.0, 0.5))      // accepted as a command; the sim says whether it hits
        assertEquals(TennisReject.NOT_YOUR_SEAT, g.swing(1, 0.0, 0.5))   // the computer's seat
        assertEquals(TennisReject.BAD_INPUT, g.swing(0, Double.NaN, 0.5))
        assertEquals(TennisReject.NOT_YOUR_SEAT, g.swing(7, 0.0, 0.5))
    }

    @Test fun aHumanServeIsJudgedAgainstTheTossPeak() {
        val g = K.newGame(players = K.humans)
        K.runUntil(g) { it.phase == TPhase.TOSS }
        val c0 = K.court(g)
        // swing exactly at the peak tick
        while (K.court(g).tick < c0.cTick) g.stepTicks(1)
        g.swing(0, 0.0, 0.7); g.stepTicks(1)
        val c = K.court(g)
        assertEquals(TPhase.RALLY, c.phase)
        assertEquals(4, c.lastQuality)
        assertEquals(1, c.hitSerial)
    }

    @Test fun theComputerServesAndReturnsOnItsOwn() {
        val g = K.newGame(players = K.vsBot, first = 1)
        K.runUntil(g, 3000) { it.hitSerial >= 1 }
        assertEquals(1, K.court(g).lastSwingSide)
        // a human who returns every ball keeps a rally going with the computer
        var returned = 0
        var guard = 0
        while (returned < 3 && guard++ < 80_000) {
            val c = K.court(g)
            if (c.phase == TPhase.POINT || c.phase == TPhase.OVER) break
            if (c.phase == TPhase.RALLY && c.lastHitter == 1 && c.cTick >= 0 && c.tick == c.cTick) {
                g.swing(0, 0.0, 0.5); returned++
            }
            g.stepTicks(1)
        }
        assertTrue("returned $returned", returned >= 1)
    }

    @Test fun aMatchEndsAndTheSessionCountsTheWin() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(players = K.bots, results = results)
        K.runUntil(g) { it.phase == TPhase.OVER }
        val w = K.court(g).score.winner
        assertTrue(w in 0..1)
        assertEquals(1, g.sessionWins.value[w]); assertEquals(0, g.sessionWins.value[1 - w])
        g.leaveSession()
        assertEquals(1, results.size)
        assertEquals(1, results[0].scores.count { it.isWinner })
        assertTrue(results[0].scores[w].isWinner)
        assertEquals(TennisReject.NOT_PLAYING, g.swing(0, 0.0, 0.5))
    }

    @Test fun playAgainStartsAFreshMatchInTheSameSession() {
        val g = K.newGame(players = K.bots)
        K.runUntil(g) { it.phase == TPhase.OVER }
        g.playAgain()
        assertEquals(TPhase.PREP, K.court(g).phase)
        assertEquals(0, K.court(g).score.totalPoints)
        assertEquals(1, g.sessionWins.value.sum())
    }

    @Test fun everyFormatIsPlayableToTheEnd() {
        for (f in listOf("points7", "points11", "set4", "set6", "best3")) {
            val g = K.newGame(players = K.bots, format = f, seed = 5)
            K.runUntil(g, 800_000) { it.phase == TPhase.OVER }
            assertTrue(f, K.court(g).score.over)
            assertEquals(TennisFormat.parse(f), K.court(g).score.format)
        }
    }

    // ---- the computer levels ---------------------------------------------------------------

    private fun playBots(seed: Int, a: CpuDifficulty, b: CpuDifficulty): Int {
        val g = K.newGame(players = K.bots, seed = seed, format = "points7", first = seed % 2)
        g.cpuLevels[0] = a; g.cpuLevels[1] = b
        K.runUntil(g, 400_000) { it.phase == TPhase.OVER }
        return K.court(g).score.winner
    }

    private fun winRate(strong: CpuDifficulty, weak: CpuDifficulty, matches: Int): Double {
        var points = 0.0
        for (i in 0 until matches) {
            val strongIsSide0 = i % 2 == 0
            val w = if (strongIsSide0) playBots(200 + i, strong, weak) else playBots(200 + i, weak, strong)
            if ((w == 0) == strongIsSide0) points += 1.0
        }
        return points / matches
    }

    @Test fun profilesGetFasterAndMoreAccurateWithTheLevel() {
        val e = TennisCpu.profile(CpuDifficulty.EASY); val m = TennisCpu.profile(CpuDifficulty.MEDIUM); val h = TennisCpu.profile(CpuDifficulty.HARD)
        assertTrue(e.reactTicks > m.reactTicks && m.reactTicks > h.reactTicks)
        assertTrue(e.speed < m.speed && m.speed < h.speed)
        assertTrue(e.timingSigma > m.timingSigma && m.timingSigma > h.timingSigma)
        assertTrue(e.aimSigma > m.aimSigma && m.aimSigma > h.aimSigma)
        assertTrue(e.smart < m.smart && m.smart < h.smart)
        assertTrue(e.powerHigh < m.powerHigh && m.powerHigh < h.powerHigh)
    }

    @Test fun hardBeatsMediumBeatsEasyButNeverAlways() {
        val hardEasy = winRate(CpuDifficulty.HARD, CpuDifficulty.EASY, 40)
        val medEasy = winRate(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 40)
        val hardMed = winRate(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 40)
        println("tennis cpu win rates: hard>easy=$hardEasy medium>easy=$medEasy hard>medium=$hardMed")
        // Clearly ordered...
        assertTrue("hard vs easy $hardEasy", hardEasy >= 0.8)
        assertTrue("medium vs easy $medEasy", medEasy >= 0.6)
        assertTrue("hard vs medium $hardMed", hardMed >= 0.6)
        // ...but a level is never a lock: the weaker computer still takes matches (the levels were once 100 percent apart).
        assertTrue("medium vs easy $medEasy", medEasy <= 0.95)
        assertTrue("hard vs medium $hardMed", hardMed <= 0.92)
    }

    @Test fun theComputerReactionScalesWithGameSpeed() {
        val a = K.newGame(players = K.vsBot, difficulty = CpuDifficulty.EASY)
        assertEquals(31, a.configForTest().react[1])
        val g = ToyTennisGame(Random(1), ToyClock())
        g.cpuReactionScale = 2.0
        g.difficulty = CpuDifficulty.EASY
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.vsBot, 0, ToyFakeTransport()))
        g.startMatch()
        assertEquals(62, g.configForTest().react[1])
        assertEquals(6.8, g.configForTest().speed[1], 1e-9)
        assertEquals(8.0, g.configForTest().speed[0], 1e-9)
    }

    // ---- clock, pause, resume --------------------------------------------------------------

    @Test fun theClockDrivesFixedStepsAndPauseFreezes() {
        val clock = ToyClock()
        val g = K.newGame(clock = clock)
        assertFalse(g.advance())
        clock.advance(105)
        assertTrue(g.advance())
        assertEquals(12, K.court(g).tick)
        g.pause(); clock.advance(400); g.advance()
        assertEquals(12, K.court(g).tick)
        g.resume(); g.advance(); clock.advance(105); g.advance()
        assertTrue(K.court(g).tick > 12)
        val t = K.court(g).tick
        clock.advance(60_000); g.advance()
        assertEquals(t + 30, K.court(g).tick)           // a long stall is capped at a quarter of a second
    }

    @Test fun snapshotRoundTripRestartsTheCurrentPointWithTheScoreIntact() {
        val g = K.newGame(players = K.vsBot, format = "set4", difficulty = CpuDifficulty.HARD, first = 1)
        K.runUntil(g, 20000) { it.score.totalPoints >= 2 }
        val snap = g.snapshot()
        assertNotNull(snap)
        val h = K.newGame(players = K.vsBot, start = false)
        assertTrue(h.restore(snap!!))
        val c = K.court(h)
        assertEquals(K.court(g).score, c.score)
        assertEquals(TPhase.PREP, c.phase)
        assertEquals(c.score.server, c.server)
        assertEquals(CpuDifficulty.HARD, h.difficulty)
        assertEquals(TennisFormat.SET4, c.score.format)
        h.stepTicks(600)
        assertTrue(K.court(h).phase != TPhase.OVER) // the restored point plays on (it may even be over already)
    }

    @Test fun badSnapshotsAreRefusedAndLeaveTheGameUntouched() {
        val g = K.newGame()
        g.stepTicks(200)
        val good = g.snapshot()!!
        val before = g.state.value
        val bad = listOf(
            "", "{}", "nope", good.replace("\"p1\"", "\"q\""), good.replace("\"pointsA\":0", "\"pointsA\":-5"),
            good.replace("\"gameServer\":0", "\"gameServer\":4"), good.replace("\"formatId\":\"points7\"", "\"formatId\":\"set6\""),
            good.replace("\"firstServer\":0", "\"firstServer\":9"), good.substring(0, good.length / 2),
            good.replace("\"pointsTarget\":7", "\"pointsTarget\":70")
        )
        for (b in bad) assertFalse("accepted: ${b.take(80)}", g.restore(b))
        assertEquals(before, g.state.value)
        assertTrue(g.restore(good))
    }

    @Test fun noSnapshotBeforeStartAfterTheMatchOrOnline() {
        assertNull(K.newGame(start = false).snapshot())
        val over = K.newGame(players = K.bots)
        K.runUntil(over) { it.phase == TPhase.OVER }
        assertNull(over.snapshot())
        assertNull(K.newGame(players = K.humans, mode = PlayMode.LOCAL_AD_HOC).snapshot())
    }

    @Test fun aHintPointsAwayFromTheOpponent() {
        val g = K.newGame(players = K.vsBot)
        val h = g.requestHint()
        assertNotNull(h)
        assertTrue(h!!.dirX in -1.0..1.0 && h.power in 0.0..1.0)
        assertTrue(h.targetY < 0)                       // the far half, as seen from side 0
        assertNull(g.requestHint(seat = 5))
    }

    @Test fun randomInputNeverBreaksAMatchAndAlwaysTerminates() {
        for (seed in 1..6) for (assist in listOf(false, true)) for (players in listOf(K.humans, K.vsBot)) {
            val r = Random(seed)
            val g = K.newGame(players = players, seed = seed, assist = assist, format = if (seed % 2 == 0) "points7" else "set4")
            var guard = 0
            while (K.court(g).phase != TPhase.OVER && guard < 3_000_000) {
                g.stepTicks(1 + r.nextInt(12)); guard += 12
                val rej = g.swing(r.nextInt(-1, 3), r.nextDouble(-2.0, 2.0), r.nextDouble(-1.0, 2.0))
                assertTrue(rej in TennisReject.entries)
                val c = K.court(g)
                // aim a share of swings at the contact moment so points are actually played
                if (c.cTick >= 0 && c.tick in (c.cTick - 2)..(c.cTick + 2)) {
                    val side = if (c.phase == TPhase.TOSS) c.server else 1 - c.lastHitter
                    g.swing(side, r.nextDouble(-0.9, 0.9), r.nextDouble(0.2, 1.0))
                }
                if (r.nextInt(80) == 0) { val s = g.snapshot(); if (s != null) assertTrue(K.newGame(players = players, start = false).restore(s)) }
                assertTrue(c.score.totalPoints < 5000)
            }
            assertEquals("seed $seed assist=$assist ended", TPhase.OVER, K.court(g).phase)
        }
    }

    @Test fun theSameSeedReplaysTheSameMatchExactly() {
        fun run(): TennisState {
            val g = K.newGame(players = K.bots, seed = 12)
            g.stepTicks(20_000)
            return K.court(g).copy()
        }
        assertEquals(run(), run())
    }
}
