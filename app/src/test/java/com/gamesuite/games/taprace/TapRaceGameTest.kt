package com.gamesuite.games.taprace

import com.gamesuite.core.GameResult
import com.gamesuite.games.partypack.TapTugClock
import com.gamesuite.games.partypack.tapTugContext
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rules and timing of Tap Race on a fake clock: every race here replays exactly and nothing sleeps. */
class TapRaceGameTest {
    /** The clock starts at 1000 and the countdown is 3000 ms, so GO is at clock = [GO_AT]. */
    private val GO_AT = 1_000L + TapRaceRules.COUNTDOWN_MS

    private class Rig(val game: TapRaceGame, val clock: TapTugClock) {
        val s get() = game.state.value!!
        fun advance(ms: Long) {
            var left = ms
            while (left > 0) { val d = minOf(20L, left); clock.advance(d); game.tick(); left -= d }
        }
        fun advanceTo(t: Long) = advance(t - clock.now)
        /** Taps seat [seat] [n] times, [gap] ms apart. */
        fun taps(seat: Int, n: Int, gap: Long = 50L) { repeat(n) { game.tap(seat); advance(gap) } }
    }

    private fun rig(
        variant: RaceVariant = RaceVariant.SPRINT, humans: Int = 2, bots: Int = 0, rounds: Int = 3, relaxed: Boolean = false,
        hold: Boolean = false, seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): Rig {
        val clock = TapTugClock()
        val g = TapRaceGame(Random(seed), clock)
        g.variant = variant; g.rounds = rounds; g.relaxed = relaxed; g.holdToPlay = hold; g.difficulty = level
        g.init(tapTugContext(humans, bots))
        g.startMatch()
        return Rig(g, clock)
    }

    // ---- sprint ----

    @Test fun theRaceStartsWithACountdownThenGo() {
        val r = rig()
        assertEquals("COUNTDOWN", r.s.phase)
        assertEquals(3000, r.s.countdownMs)
        r.advanceTo(GO_AT - 20)
        assertEquals("COUNTDOWN", r.s.phase)
        r.advanceTo(GO_AT + 20)
        assertEquals("RACE", r.s.phase)
        assertEquals(RaceEvent.GO, r.s.event)
    }

    @Test fun firstToTheTargetWinsTheRound() {
        val r = rig()
        r.advanceTo(GO_AT + TapRaceRules.FALSE_START_LOCK_MS + 20)
        r.taps(1, 5)
        r.taps(0, 40)
        assertEquals("ROUND_END", r.s.phase)
        assertEquals(0, r.s.roundWinnerSeat)
        assertEquals(1, r.s.seats[0].roundWins)
        assertEquals(RaceEvent.ROUND_WIN, r.s.event)
        assertEquals(5, r.s.seats[1].progress)
    }

    @Test fun aTapThatComesTooSoonAfterTheLastIsRefused() {
        val r = rig()
        r.advanceTo(GO_AT + 600)
        assertEquals(TapReject.NONE, r.game.tap(0))
        r.advance(20)
        assertEquals(TapReject.TOO_FAST, r.game.tap(0))
        assertEquals(1, r.s.seats[0].progress)
        r.advance(40)
        assertEquals(TapReject.NONE, r.game.tap(0))
    }

    @Test fun mashingFasterThanTheHumanLimitCannotBeatTheLimit() {
        val r = rig()
        r.advanceTo(GO_AT + 600)
        // a (cheating) 200 taps a second for one second: the engine counts at most about 22
        repeat(200) { r.game.tap(0); r.advance(5) }
        assertTrue("progress ${r.s.seats[0].progress}", r.s.seats[0].progress <= 23)
    }

    @Test fun aFalseStartFreezesThePadUntilHalfASecondAfterGo() {
        val r = rig()
        r.advance(1000)
        assertEquals(TapReject.FALSE_START, r.game.tap(0))
        assertEquals(RaceJudge.FALSE_START, r.s.seats[0].judge)
        r.advanceTo(GO_AT + 100)
        assertEquals(TapReject.LOCKED, r.game.tap(0))
        assertTrue(r.s.seats[0].lockedMs > 0)
        r.advanceTo(GO_AT + 600)
        assertEquals(TapReject.NONE, r.game.tap(0))
        assertEquals(TapReject.NONE, r.game.tap(1)) // the other racer was never frozen
    }

    @Test fun tapsBetweenRoundsAndAfterTheMatchAreNotRacing() {
        val r = rig(rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.taps(0, 40)
        assertTrue(r.s.over)
        assertEquals(TapReject.NOT_RACING, r.game.tap(0))
    }

    @Test fun aBadSeatIsRefused() {
        val r = rig(humans = 1, bots = 1)
        r.advanceTo(GO_AT + 600)
        assertEquals(TapReject.BAD_SEAT, r.game.tap(7))
        assertEquals(TapReject.BOT_SEAT, r.game.tap(1))
    }

    @Test fun relaxedLowersTheTarget() {
        assertEquals(40, rig().s.target)
        assertEquals(25, rig(relaxed = true).s.target)
        assertEquals(36, rig(RaceVariant.RHYTHM).s.target)
        assertEquals(24, rig(RaceVariant.RHYTHM, relaxed = true).s.target)
    }

    @Test fun holdToPlayTapsForAHeldFinger() {
        val r = rig(hold = true)
        r.advanceTo(GO_AT + 600)
        r.game.press(0, 5)
        r.advance(2000)
        val held = r.s.seats[0].progress
        assertTrue("progress $held", held in 5..8)
        assertTrue(r.s.seats[0].held)
        r.game.release(0, 5)
        assertFalse(r.s.seats[0].held)
        val after = r.s.seats[0].progress
        r.advance(1000)
        assertEquals(after, r.s.seats[0].progress)
    }

    @Test fun aFingerLiftingNeverCancelsAnotherFingersHold() {
        val r = rig(hold = true)
        r.advanceTo(GO_AT + 600)
        r.game.press(0, 1); r.advance(60); r.game.press(0, 2)
        r.game.release(0, 2)
        assertTrue(r.s.seats[0].held)
        r.advance(1000)
        assertTrue(r.s.seats[0].progress >= 3)
    }

    @Test fun roundsRunToABestOfThree() {
        val r = rig(rounds = 3)
        for (round in 1..2) {
            // wait for the countdown of this round, then win it with seat 0
            while (r.s.phase != "RACE") r.advance(20)
            r.advance(TapRaceRules.FALSE_START_LOCK_MS + 20)
            r.taps(0, 40)
        }
        assertTrue(r.s.over)
        assertEquals(listOf(0), r.s.winnerSeats)
        assertEquals(2, r.s.seats[0].roundWins)
        assertEquals(RaceEvent.MATCH_WIN, r.s.event)
        assertEquals(1, r.s.sessionWins["p1"])
    }

    @Test fun anIdleRoundEndsInADrawAtTheTimeLimitAndAMatchOfDrawsIsADraw() {
        val r = rig(rounds = 1)
        var guard = 0
        while (!r.s.over && guard++ < 20_000) r.advance(1000)
        assertTrue(r.s.over)
        assertTrue(r.s.winnerSeats.isEmpty())
        assertEquals(RaceEvent.MATCH_DRAW, r.s.event)
        assertEquals(1, r.s.sessionDraws)
    }

    @Test fun theLeaderWinsWhenTheTimeRunsOut() {
        val r = rig(rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.taps(1, 3)
        r.advance(TapRaceRules.ROUND_LIMIT_MS)
        assertTrue(r.s.over)
        assertEquals(listOf(1), r.s.winnerSeats)
    }

    // ---- rhythm ----

    /** Presses seat 0 at [offset] ms from beat [k] of the current race. */
    private fun Rig.beatTap(seat: Int, k: Int, offset: Long): TapReject {
        advanceTo(GO_AT + k * TapRaceRules.beatMs(s.relaxed) + offset)
        return game.tap(seat)
    }

    @Test fun rhythmJudgesPerfectGoodAndOffBeatTaps() {
        val r = rig(RaceVariant.RHYTHM)
        r.advanceTo(GO_AT + 20)
        r.beatTap(0, 1, 30)
        assertEquals(RaceJudge.PERFECT, r.s.seats[0].judge); assertEquals(3, r.s.seats[0].progress)
        r.beatTap(0, 2, -100)
        assertEquals(RaceJudge.GOOD, r.s.seats[0].judge); assertEquals(5, r.s.seats[0].progress)
        r.beatTap(0, 3, 250) // 250 ms late: outside the window
        assertEquals(RaceJudge.LATE, r.s.seats[0].judge); assertEquals(3, r.s.seats[0].progress)
        r.beatTap(0, 5, -250)
        assertEquals(RaceJudge.EARLY, r.s.seats[0].judge); assertEquals(1, r.s.seats[0].progress)
    }

    @Test fun rhythmProgressNeverGoesBelowZero() {
        val r = rig(RaceVariant.RHYTHM)
        r.advanceTo(GO_AT + 20)
        r.beatTap(0, 1, 300)
        assertEquals(0, r.s.seats[0].progress)
    }

    @Test fun aSecondTapOnTheSameBeatIsPunished() {
        val r = rig(RaceVariant.RHYTHM)
        r.advanceTo(GO_AT + 20)
        r.beatTap(0, 1, -30)
        assertEquals(3, r.s.seats[0].progress)
        r.beatTap(0, 1, 30)
        assertEquals(RaceJudge.DOUBLE, r.s.seats[0].judge)
        assertEquals(2, r.s.seats[0].progress)
    }

    @Test fun spammingARhythmRaceGoesNowhere() {
        val r = rig(RaceVariant.RHYTHM)
        r.advanceTo(GO_AT + 20)
        r.taps(0, 120, gap = 50) // six seconds of mashing
        assertTrue("progress ${r.s.seats[0].progress}", r.s.seats[0].progress < 10)
    }

    @Test fun tappingEveryBeatPerfectlyWinsARhythmRound() {
        val r = rig(RaceVariant.RHYTHM, rounds = 1)
        r.advanceTo(GO_AT + 20)
        var k = 1
        while (!r.s.over && k < 40) { r.beatTap(0, k, 0); k++ }
        assertTrue(r.s.over); assertEquals(listOf(0), r.s.winnerSeats)
        assertEquals(12, k - 1) // 12 perfect taps of 3 points reach 36
    }

    @Test fun relaxedWidensTheBeatWindowsAndSlowsTheBeat() {
        val strict = rig(RaceVariant.RHYTHM)
        strict.advanceTo(GO_AT + 20)
        strict.beatTap(0, 1, 100) // 100 ms off: only "good" when strict
        assertEquals(RaceJudge.GOOD, strict.s.seats[0].judge)
        val relaxed = rig(RaceVariant.RHYTHM, relaxed = true)
        relaxed.advanceTo(GO_AT + 20)
        relaxed.beatTap(0, 1, 100) // inside the relaxed perfect window
        assertEquals(RaceJudge.PERFECT, relaxed.s.seats[0].judge)
        assertTrue(TapRaceRules.beatMs(true) > TapRaceRules.beatMs(false))
    }

    @Test fun theBeatPhaseAdvancesSmoothlyWithinABeat() {
        val r = rig(RaceVariant.RHYTHM)
        r.advanceTo(GO_AT + 20)
        r.advanceTo(GO_AT + 333)
        assertTrue(r.s.beatPhase in 0.4f..0.6f)
        assertEquals(0, r.s.beatIndex)
        r.advanceTo(GO_AT + 700)
        assertEquals(1, r.s.beatIndex)
    }

    // ---- mash and brake ----

    private fun Rig.untilLight(light: String) { var guard = 0; while (s.light != light && guard++ < 5000) advance(20) }

    @Test fun theLightCyclesGreenAmberRedGreen() {
        val r = rig(RaceVariant.BRAKE, rounds = 1)
        r.advanceTo(GO_AT + 20)
        assertEquals("GREEN", r.s.light)
        r.untilLight("AMBER"); assertEquals(RaceEvent.AMBER, r.s.event)
        r.untilLight("RED"); assertEquals(RaceEvent.RED, r.s.event)
        r.untilLight("GREEN"); assertEquals(RaceEvent.GREEN, r.s.event)
    }

    @Test fun amberLastsLongerWhenRelaxed() {
        assertTrue(TapRaceRules.amberMs(true) > TapRaceRules.amberMs(false))
        val r = rig(RaceVariant.BRAKE, relaxed = true, rounds = 1)
        r.advanceTo(GO_AT + 20)
        r.untilLight("AMBER")
        assertTrue(r.s.lightLeftMs in 1400..1600)
    }

    @Test fun aTapOnRedCostsGroundAndFreezesThePad() {
        val r = rig(RaceVariant.BRAKE, rounds = 1)
        r.advanceTo(GO_AT + 20)
        r.untilLight("RED")
        // a tap in the grace moment right after the light changes is forgiven
        assertEquals(TapReject.NONE, r.game.tap(0))
        assertEquals(1, r.s.seats[0].progress)
        r.advance(400)
        val before = r.s.seats[0].progress
        r.game.tap(0)
        assertEquals(RaceJudge.RED, r.s.seats[0].judge)
        assertEquals((before - TapRaceRules.redPenalty(false)).coerceAtLeast(0), r.s.seats[0].progress)
        assertTrue(r.s.seats[0].lockedMs > 0)
        assertEquals(TapReject.LOCKED, r.game.tap(0))
    }

    @Test fun brakingOnRedCostsNothing() {
        val r = rig(RaceVariant.BRAKE, rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.taps(0, 10)
        r.untilLight("RED")
        val p = r.s.seats[0].progress
        r.advance(300) // hands off for the whole red light
        r.untilLight("GREEN")
        assertEquals(p, r.s.seats[0].progress)
        assertEquals(RaceJudge.NONE, r.s.seats[0].judge)
    }

    @Test fun aFingerLeftOnThePadAtRedCostsGroundOnceWhenHoldToPlay() {
        val r = rig(RaceVariant.BRAKE, hold = true, rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.game.press(0, 3)
        r.untilLight("RED")
        val before = r.s.seats[0].progress
        r.advance(1000)
        assertEquals(RaceJudge.RED, r.s.seats[0].judge)
        assertTrue(r.s.seats[0].progress < before + 3)
    }

    // ---- computer racers ----

    @Test fun aComputerRacerWinsAnIdleRound() {
        val r = rig(humans = 1, bots = 1, rounds = 1)
        var guard = 0
        while (!r.s.over && guard++ < 6000) r.advance(20)
        assertTrue(r.s.over)
        assertEquals(listOf(1), r.s.winnerSeats)
    }

    @Test fun aComputerNeverTapsFasterThanAPersonCan() {
        for (level in CpuDifficulty.entries) {
            val r = rig(humans = 1, bots = 1, rounds = 1, level = level, relaxed = false)
            r.advanceTo(GO_AT)
            r.advance(3000)
            assertTrue("$level ${r.s.seats[1].progress} in 3 s", r.s.seats[1].progress <= 3 * 9)
        }
    }

    private fun botDuel(variant: RaceVariant, strong: CpuDifficulty, weak: CpuDifficulty, seeds: Int): Int {
        var strongWins = 0
        for (seed in 1..seeds) {
            val clock = TapTugClock()
            val g = TapRaceGame(Random(seed), clock)
            g.variant = variant; g.rounds = 1
            g.init(tapTugContext(0, 2))
            g.seatLevelOverride[0] = strong; g.seatLevelOverride[1] = weak
            g.startMatch()
            var guard = 0
            while (!g.state.value!!.over && guard++ < 20_000) { clock.advance(20); g.tick() }
            if (g.state.value!!.winnerSeats == listOf(0)) strongWins++
        }
        return strongWins
    }

    @Test fun computerLevelsAreClearlyOrderedButNotHopeless() {
        val seeds = 40
        var hardOverEasy = 0; var hardOverMedium = 0; var mediumOverEasy = 0
        for (v in RaceVariant.entries) {
            hardOverEasy += botDuel(v, CpuDifficulty.HARD, CpuDifficulty.EASY, seeds)
            hardOverMedium += botDuel(v, CpuDifficulty.HARD, CpuDifficulty.MEDIUM, seeds)
            mediumOverEasy += botDuel(v, CpuDifficulty.MEDIUM, CpuDifficulty.EASY, seeds)
        }
        val total = seeds * RaceVariant.entries.size
        println("TapRace duels: hard>easy $hardOverEasy, hard>medium $hardOverMedium, medium>easy $mediumOverEasy of $total")
        assertTrue("hard should beat easy clearly: $hardOverEasy", hardOverEasy > total * 0.75)
        assertTrue("hard should beat medium: $hardOverMedium", hardOverMedium > total * 0.55)
        assertTrue("medium should beat easy: $mediumOverEasy", mediumOverEasy > total * 0.55)
        assertTrue("hard over easy ($hardOverEasy) must beat hard over medium ($hardOverMedium)", hardOverEasy > hardOverMedium)
        assertTrue("easy is not hopeless: hard beat easy $hardOverEasy of $total", hardOverEasy < total)
    }

    // ---- sessions, results, resume ----

    @Test fun leavingReportsTheSessionWinsAndOnlyAStrictLeaderWins() {
        val r = rig(rounds = 1)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.advanceTo(GO_AT + 600)
        r.taps(0, 40)
        r.game.leaveSession()
        val res = result!!
        assertFalse(res.wasAborted)
        assertEquals(listOf(true, false), res.scores.map { it.isWinner })
        assertEquals(listOf(1, 0), res.scores.map { it.score })
        assertTrue(r.game.matchOver.value)
    }

    @Test fun aSessionTieHasNoWinner() {
        val r = rig(rounds = 1)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.leaveSession()
        assertTrue(result!!.scores.none { it.isWinner })
    }

    @Test fun abortingEndsWithNoScores() {
        val r = rig()
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.game.abortMatch()
        assertTrue(result!!.wasAborted)
    }

    @Test fun playAgainStartsAFreshMatchAndKeepsTheSession() {
        val r = rig(rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.taps(0, 40)
        assertTrue(r.s.over)
        r.game.playAgain()
        assertFalse(r.s.over)
        assertEquals("COUNTDOWN", r.s.phase)
        assertEquals(1, r.s.round)
        assertEquals(1, r.s.sessionWins["p1"])
        assertEquals(0, r.s.seats[0].roundWins)
    }

    private fun midMatch(): Rig {
        val r = rig(RaceVariant.RHYTHM, rounds = 3, relaxed = true, level = CpuDifficulty.HARD, humans = 1, bots = 2)
        // one round for the human, via hold... simplest: let a bot-free duel? Here the human idles; bot 1 wins round 1.
        var guard = 0
        while (r.s.round == 1 && guard++ < 20_000) r.advance(20)
        return r
    }

    @Test fun snapshotAndRestoreRoundTripBetweenRoundsAndRestartTheRound() {
        val r = midMatch()
        val snap = r.game.snapshot()
        assertNotNull(snap)
        val clock2 = TapTugClock()
        val g2 = TapRaceGame(Random(9), clock2)
        g2.init(tapTugContext(1, 2))
        assertTrue(g2.restore(snap!!))
        val s2 = g2.state.value!!
        assertEquals(r.s.round, s2.round)
        assertEquals(r.s.seats.map { it.roundWins }, s2.seats.map { it.roundWins })
        assertEquals("COUNTDOWN", s2.phase)
        assertEquals(RaceVariant.RHYTHM.id, s2.variant)
        assertTrue(s2.relaxed)
        assertEquals(0, s2.seats.sumOf { it.progress })
        assertNotNull(g2.snapshot())
    }

    @Test fun aMidRoundSnapshotRestartsThatRound() {
        val r = rig(humans = 1, bots = 1, rounds = 3)
        r.advanceTo(GO_AT + 4000)
        assertTrue(r.s.seats[1].progress > 0)
        val snap = r.game.snapshot()!!
        val g2 = TapRaceGame(Random(2), TapTugClock())
        g2.init(tapTugContext(1, 1))
        assertTrue(g2.restore(snap))
        assertEquals(1, g2.state.value!!.round)
        assertEquals(0, g2.state.value!!.seats.sumOf { it.progress })
        assertEquals("COUNTDOWN", g2.state.value!!.phase)
    }

    @Test fun restoreRefusesAnythingThatDoesNotFit() {
        val r = midMatch()
        val good = r.game.snapshot()!!
        fun fresh(humans: Int = 1, bots: Int = 2) = TapRaceGame(Random(1), TapTugClock()).also { it.init(tapTugContext(humans, bots)) }
        assertFalse(fresh().restore("not json"))
        assertFalse(fresh().restore("{}"))
        assertFalse(fresh(2, 1).restore(good)) // a different roster
        assertFalse(fresh(1, 1).restore(good))
        val tampered = listOf(
            good.replace("\"played\":1", "\"played\":9"),
            good.replace("\"rounds\":3", "\"rounds\":4"),
            good.replace("\"variant\":\"rhythm\"", "\"variant\":\"warp\""),
            good.replace("\"difficulty\":\"HARD\"", "\"difficulty\":\"GODLIKE\""),
            good.replace("\"wins\":[", "\"wins\":[5,")
        )
        for (t in tampered) {
            val g = fresh()
            assertFalse("accepted: $t", g.restore(t))
            assertNull(g.state.value) // refused: left untouched
        }
    }

    @Test fun noSnapshotBeforeStartOrAfterTheMatchIsOver() {
        val g = TapRaceGame(Random(1), TapTugClock())
        g.init(tapTugContext(2, 0))
        assertNull(g.snapshot())
        val r = rig(rounds = 1)
        r.advanceTo(GO_AT + 600)
        r.taps(0, 40)
        assertTrue(r.s.over)
        assertNull(r.game.snapshot())
    }

    // ---- no network ----

    @Test fun theGameIsSameScreenOnlyAndNeverSendsAMessage() {
        val transport = com.gamesuite.games.partypack.TapTugFakeTransport()
        val clock = TapTugClock()
        val g = TapRaceGame(Random(1), clock)
        g.init(tapTugContext(2, 0, transport))
        g.startMatch()
        repeat(200) { clock.advance(20); g.tick(); g.tap(0) }
        assertEquals(0, transport.sends)
        assertEquals(
            listOf(com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, com.gamesuite.core.PlayMode.SINGLE_PLAYER_VS_BOT),
            g.supportedModes
        )
        assertEquals(2, g.minPlayers); assertEquals(4, g.maxPlayers)
    }

    // ---- fuzz ----

    @Test fun randomPlayAlwaysTerminatesAndKeepsItsInvariants() {
        for (seed in 1..60) {
            val random = Random(seed)
            val variant = RaceVariant.entries[random.nextInt(3)]
            val humans = 1 + random.nextInt(3)
            val bots = if (humans == 1) 1 + random.nextInt(3) else random.nextInt(5 - humans)
            val r = rig(variant, humans = humans, bots = bots, rounds = listOf(1, 3, 5)[random.nextInt(3)],
                relaxed = random.nextBoolean(), hold = random.nextBoolean(), seed = seed,
                level = CpuDifficulty.entries[random.nextInt(3)])
            val seats = humans + bots
            var steps = 0
            while (!r.s.over && steps++ < 60_000) {
                r.clock.advance(1L + random.nextInt(40)); r.game.tick()
                if (random.nextInt(3) == 0) {
                    val seat = random.nextInt(seats)
                    when (random.nextInt(4)) {
                        0 -> r.game.tap(seat)
                        1 -> r.game.press(seat, random.nextInt(3).toLong())
                        2 -> r.game.release(seat, random.nextInt(3).toLong())
                        else -> r.game.press(seat, 7)
                    }
                }
                val s = r.s
                assertEquals(seats, s.seats.size)
                assertTrue(s.seats.all { it.progress in 0..(s.target + TapRaceRules.PERFECT_POINTS) })
                assertTrue(s.round >= 1 && s.round <= s.rounds + TapRaceRules.ROUND_CHOICES.size + 1)
                if (steps % 400 == 0) r.game.snapshot()?.let { snap ->
                    val g2 = TapRaceGame(Random(seed), TapTugClock())
                    g2.init(tapTugContext(humans, bots))
                    assertTrue("snapshot did not restore: $snap", g2.restore(snap))
                    assertEquals(r.s.seats.map { it.roundWins }, g2.state.value!!.seats.map { it.roundWins })
                }
            }
            assertTrue("seed $seed never ended (variant $variant)", r.s.over)
        }
    }
}
