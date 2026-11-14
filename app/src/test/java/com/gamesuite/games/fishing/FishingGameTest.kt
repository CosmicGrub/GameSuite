package com.gamesuite.games.fishing

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.slotcars.ScClock
import com.gamesuite.games.slotcars.ScFakeTransport
import com.gamesuite.games.slotcars.ScHub
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

object FishKit {
    fun roster(humans: Int, total: Int = humans) = (0 until total).map { i ->
        if (i < humans) PlayerInfo("p${i + 1}", "P${i + 1}") else PlayerInfo("bot${i + 1}", "CPU${i + 1}", isBot = true)
    }

    fun newGame(
        players: List<PlayerInfo> = roster(2), seed: Int = 1, clock: ScClock = ScClock(), lake: String = "lake", rounds: Int = 3,
        relaxed: Boolean = false, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, results: MutableList<GameResult>? = null, start: Boolean = true
    ): FishingGame {
        val mode = if (players.any { it.isBot } || players.size == 1) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        val g = FishingGame(Random(seed), clock)
        g.difficulty = difficulty; g.lake = lake; g.rounds = rounds; g.relaxed = relaxed
        g.init(GameContext(mode, players, 0, ScFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        if (start) g.startMatch()
        return g
    }

    /** Plays the human turn at the top of the game with a sensible player: cast, hook, reel. */
    fun playTurn(g: FishingGame, seat: Int, band: Int = 1) {
        g.setAim(seat, 2)
        g.setHold(seat, true); g.stepTicks(1)
        g.stepTicks(((band + 0.5) / 4.0 * FishingRules.METER_HALF).toInt())
        g.setHold(seat, false); g.stepTicks(1)
        var guard = 0
        while (g.state.value!!.phase != FishPhase.BITE && guard++ < 3000) g.stepTicks(1)
        g.setHold(seat, true); g.stepTicks(1)
        guard = 0
        while (g.state.value!!.phase == FishPhase.REELING && guard++ < 9000) {
            val e = g.engineForTest()
            g.setHold(seat, e.rush == null && !e.warning && e.tension < 0.62)
            g.stepTicks(1)
        }
        g.setHold(seat, false)
    }
}

/** The Fishing module: lifecycle, intents, hints, resume, scoring. */
class FishingGameTest {
    private val K = FishKit

    @Test fun aTurnWithTheButtonCatchesAFishAndThePassMovesOn() {
        val g = K.newGame()
        val s0 = g.state.value!!
        assertEquals(FishPhase.AIM, s0.phase); assertEquals(0, s0.seat); assertEquals(3, s0.rounds)
        K.playTurn(g, 0)
        val s = g.state.value!!
        assertEquals(FishPhase.RESULT, s.phase)
        assertEquals(FishOutcome.CAUGHT, s.outcome)
        assertNotNull(s.fishId); assertTrue(s.fishGrams > 0)
        assertEquals(s.fishGrams, s.grams[0])
        g.stepTicks(FishingRules.RESULT_TICKS + 2)
        assertEquals(1, g.state.value!!.seat); assertEquals(FishPhase.AIM, g.state.value!!.phase)
    }

    @Test fun theFishIsASecretUntilItIsHooked() {
        val g = K.newGame()
        g.setHold(0, true); g.stepTicks(40); g.setHold(0, false); g.stepTicks(5)
        assertNull(g.state.value!!.fishId); assertEquals(0, g.state.value!!.fishGrams)
        var guard = 0
        while (g.state.value!!.phase != FishPhase.BITE && guard++ < 3000) { g.stepTicks(1); assertNull(g.state.value!!.fishId) }
        g.setHold(0, true); g.stepTicks(1)
        assertNotNull(g.state.value!!.fishId)
    }

    @Test fun onlyTheAnglerOnTurnMayActAndBotsFishForThemselves() {
        val g = K.newGame(K.roster(1, 3), difficulty = CpuDifficulty.HARD)
        assertEquals(FishingReject.NONE, g.setHold(0, true))
        g.setHold(0, false)
        assertEquals(FishingReject.NOT_YOUR_TURN, g.setHold(1, true))          // a computer seat cannot be driven
        assertEquals(FishingReject.BAD_INPUT, g.setHold(9, true))
        assertEquals(FishingReject.BAD_INPUT, g.setAim(0, 7))
        assertEquals(FishingReject.NONE, g.setAim(0, 4))
        K.playTurn(g, 0)
        g.stepTicks(FishingRules.RESULT_TICKS + 2)
        assertEquals(1, g.state.value!!.seat)
        // the computer on turn fishes by itself
        var guard = 0
        while (g.state.value!!.seat == 1 && guard++ < 20000) g.stepTicks(5)
        assertEquals(2, g.state.value!!.seat)
        assertEquals(1, g.state.value!!.casts[1])
    }

    @Test fun passAndPlayLetsEachHumanTakeTheirTurnOnlyWhenItIsTheirs() {
        val g = K.newGame(K.roster(3))
        assertEquals(FishingReject.NOT_YOUR_TURN, g.setHold(1, true))
        K.playTurn(g, 0); g.stepTicks(FishingRules.RESULT_TICKS + 2)
        assertEquals(FishingReject.NOT_YOUR_TURN, g.setHold(0, true))
        assertEquals(FishingReject.NONE, g.setHold(1, true))
    }

    @Test fun aWholeContestEndsRanksAndCountsTheWin() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(K.roster(1, 2), results = results, rounds = 3, difficulty = CpuDifficulty.EASY)
        var guard = 0
        while (!g.state.value!!.over && guard++ < 400000) {
            val s = g.state.value!!
            if (s.seat == 0 && s.phase == FishPhase.AIM) K.playTurn(g, 0, band = 3) else g.stepTicks(1)
        }
        val s = g.state.value!!
        assertTrue(s.over)
        assertEquals(setOf(0, 1), s.ranking.toSet())
        assertEquals(3, s.casts[0]); assertEquals(3, s.casts[1])
        assertEquals(1, g.sessionWins.value.sum())
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(2, results.single().scores.size)
        assertEquals(s.grams, results.single().scores.map { it.score })
    }

    @Test fun leavingScoresTheStrictSessionLeaderAsTheWinnerOnly() {
        val results = mutableListOf<GameResult>()
        val g = K.newGame(K.roster(2), results = results)
        g.sessionWins.value = listOf(1, 1)
        g.leaveSession()
        assertTrue(results.single().scores.none { it.isWinner })
        val r2 = mutableListOf<GameResult>()
        val g2 = K.newGame(K.roster(2), results = r2)
        g2.sessionWins.value = listOf(0, 2)
        g2.leaveSession()
        assertEquals(listOf(false, true), r2.single().scores.map { it.isWinner })
    }

    @Test fun playAgainStartsAFreshContestKeepingTheTally() {
        val g = K.newGame(K.roster(1, 2), rounds = 3)
        g.playAgain()
        assertEquals(0, g.state.value!!.round)                                  // only after a contest
        var guard = 0
        while (!g.state.value!!.over && guard++ < 400000) g.stepTicks(50)
        val wins = g.sessionWins.value.sum()
        assertTrue(wins >= 1)
        g.playAgain()
        assertEquals(FishPhase.AIM, g.state.value!!.phase); assertEquals(0, g.state.value!!.round)
        assertEquals(0, g.state.value!!.grams.sum()); assertEquals(wins, g.sessionWins.value.sum())
    }

    @Test fun theHintFollowsThePhase() {
        val g = K.newGame()
        assertEquals(FishHintKind.CAST_HERE, g.requestHint()!!.kind)
        g.setHold(0, true); g.stepTicks(5)
        assertEquals(FishHintKind.HOLD_THE_BUTTON, g.requestHint()!!.kind)
        g.setHold(0, false); g.stepTicks(5)
        assertEquals(FishHintKind.WAIT, g.requestHint()!!.kind)
        var guard = 0
        while (g.state.value!!.phase != FishPhase.BITE && guard++ < 3000) g.stepTicks(1)
        assertEquals(FishHintKind.HOOK_NOW, g.requestHint()!!.kind)
        g.setHold(0, true); g.stepTicks(1)
        assertTrue(g.requestHint()!!.kind in setOf(FishHintKind.REEL_IN, FishHintKind.EASE_OFF))
        assertEquals(FishingCpu.bestSpot("lake").first, g.requestHint()!!.column)
    }

    @Test fun relaxedFishingWidensWindowsInTheState() {
        val a = K.newGame(relaxed = false); val b = K.newGame(relaxed = true)
        assertFalse(a.state.value!!.relaxed); assertTrue(b.state.value!!.relaxed)
        assertTrue(b.state.value!!.sweetHi - b.state.value!!.sweetLo > a.state.value!!.sweetHi - a.state.value!!.sweetLo)
    }

    // ---- resume ----

    @Test fun aContestResumesAtTheStartOfTheTurnInProgress() {
        val g = K.newGame(K.roster(2), rounds = 5, lake = "reservoir")
        K.playTurn(g, 0); g.stepTicks(FishingRules.RESULT_TICKS + 2)
        K.playTurn(g, 1); g.stepTicks(FishingRules.RESULT_TICKS + 2)
        val before = g.state.value!!
        assertEquals(1, before.round)
        g.setHold(0, true); g.stepTicks(30)                                       // a cast in the air is not worth saving
        val snap = g.snapshot()!!
        val g2 = K.newGame(K.roster(2), start = false, seed = 55)
        assertTrue(g2.restore(snap))
        val after = g2.state.value!!
        assertEquals("reservoir", after.lake); assertEquals(5, after.rounds)
        assertEquals(1, after.round); assertEquals(FishPhase.AIM, after.phase); assertEquals(before.seat, after.seat)
        assertEquals(before.grams, after.grams); assertEquals(before.catches, after.catches); assertEquals(before.casts, after.casts)
    }

    @Test fun restoreRefusesStaleOrCorruptSnapshotsAndLeavesTheGameUntouched() {
        val g = K.newGame(K.roster(2))
        K.playTurn(g, 0); g.stepTicks(FishingRules.RESULT_TICKS + 2)
        val snap = g.snapshot()!!
        val other = K.newGame(K.roster(3), start = false)
        assertFalse(other.restore(snap)); assertNull(other.state.value)
        val g3 = K.newGame(K.roster(2), start = false)
        for (bad in listOf("", "{", "null", "[]", snap.replace("\"lake\"", "\"mars\""), snap.replace("\"rounds\":3", "\"rounds\":4"),
            snap.replace("\"p1\"", "\"zz\""), snap.replace(Regex("\"grams\":\\[[0-9]+"), "\"grams\":[999999"),
            snap.replace(Regex("\"species\":\"[a-z]+\""), "\"species\":\"dragon\""))) {
            assertFalse("accepted: $bad", g3.restore(bad)); assertNull(g3.state.value)
        }
        assertTrue(g3.restore(snap))
    }

    @Test fun thereIsNothingToSaveBeforeTheStartAfterTheEndOrOnline() {
        val g = K.newGame(K.roster(2), start = false)
        assertNull(g.snapshot())
        val g2 = K.newGame(K.roster(2))
        assertNotNull(g2.snapshot())
        g2.leaveSession()
        assertNull(g2.snapshot())
        val online = FishingGame(Random(1), ScClock())
        online.init(GameContext(PlayMode.ONLINE, K.roster(2), 0, ScFakeTransport()))
        online.startMatch()
        assertNull(online.snapshot())
    }

    // ---- fuzz and clock ----

    @Test fun theFrameClockDrivesTheSameFixedSteps() {
        val clock = ScClock()
        val g = K.newGame(clock = clock)
        g.advance(); clock.advance(200); g.advance()
        assertTrue("turnTicks ${g.engineForTest().turnTicks}", g.engineForTest().turnTicks in 11..12)                           // no input: only the aim timer runs
        assertTrue(g.state.value!!.aimTimeLeft < 1.0)
    }

    @Test fun randomPlayNeverThrowsAndEveryContestTerminates() {
        for (seed in 0 until 25) {
            val r = Random(seed)
            val humans = 1 + r.nextInt(3)
            val total = (humans + r.nextInt(3)).coerceAtMost(4)
            val g = K.newGame(K.roster(humans, total), seed = seed, lake = FishingLakes.lakeIds[seed % 3], rounds = 3, relaxed = seed % 2 == 0)
            var guard = 0
            while (!g.state.value!!.over && guard++ < 600000) {
                if (r.nextInt(15) == 0) g.setHold(g.state.value!!.seat, r.nextBoolean())
                if (r.nextInt(80) == 0) g.setAim(g.state.value!!.seat, r.nextInt(5))
                if (r.nextInt(300) == 0) g.requestHint()
                if (r.nextInt(400) == 0) { val snap = g.snapshot(); if (snap != null) K.newGame(K.roster(humans, total), start = false).restore(snap) }
                g.stepTicks(1 + r.nextInt(6))
            }
            assertTrue("seed $seed", g.state.value!!.over)
            val s = g.state.value!!
            for (i in s.grams.indices) assertEquals(s.grams[i], s.catches[i].sumOf { it.grams })
        }
    }

    @Test fun theSameInputsReplayToTheSameContest() {
        fun run(): Long {
            val g = K.newGame(K.roster(1, 2), seed = 6)
            val r = Random(9)
            repeat(3000) { if (it % 11 == 0) g.setHold(0, r.nextBoolean()); g.stepTicks(1) }
            return g.fingerprint()
        }
        assertEquals(run(), run())
        assertNotEquals(0L, run())
    }

    @Test fun configurationIsNormalisedAndASoloAnglerWorks() {
        val g = FishingGame(Random(1), ScClock())
        g.lake = "mars"; g.rounds = 9
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.roster(1), 0, ScFakeTransport()))
        g.startMatch()
        val s = g.state.value!!
        assertEquals("pond", s.lake); assertEquals(3, s.rounds); assertEquals(1, s.players.size)
        K.playTurn(g, 0); g.stepTicks(FishingRules.RESULT_TICKS + 2)
        assertEquals(0, g.state.value!!.seat); assertEquals(1, g.state.value!!.round)       // alone, the next turn is again yours
    }
}
