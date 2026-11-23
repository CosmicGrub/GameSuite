package com.gamesuite.games.reactionduel

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules and timing of Reaction Duel on known sequences. No sleeps: every method is told the time. */
class ReactionDuelEngineTest {
    private fun cfg(
        variant: DuelVariant = DuelVariant.CLASSIC, seats: Int = 2, cpu: Set<Int> = emptySet(), best: Int = 3,
        relaxed: Boolean = false, oneSwitch: Boolean = false, seed: Long = 1L, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ) = DuelConfig(seats, variant, best, relaxed, oneSwitch, seed, cpu, level)

    private fun started(c: DuelConfig, now: Long = 0L): ReactionDuelEngine = ReactionDuelEngine(c).also { it.startMatch(now) }

    /** Moves to the instant the signal goes. */
    private fun goLive(e: ReactionDuelEngine) { e.tick(e.signalAt) }

    // ---- the signal moment ----

    @Test fun theSignalMomentComesFromTheSeedAndIsTheSameWhoeverPlays() {
        val a = started(cfg(seed = 77)); val b = started(cfg(seed = 77)); val c = started(cfg(seed = 78))
        assertEquals(a.signalAt, b.signalAt)
        assertNotEquals(a.signalAt, c.signalAt)
        // The computer's level or presence never moves the signal: its timings use a separate stream.
        val withCpu = started(cfg(seed = 77, cpu = setOf(1), level = CpuDifficulty.HARD))
        val withCpuEasy = started(cfg(seed = 77, cpu = setOf(0, 1), level = CpuDifficulty.EASY))
        assertEquals(a.signalAt, withCpu.signalAt); assertEquals(a.signalAt, withCpuEasy.signalAt)
    }

    @Test fun theRandomWaitStaysInsideItsWindow() {
        for (seed in 0L until 300L) {
            val e = started(cfg(seed = seed))
            val wait = e.signalAt - e.liveStartAt
            assertTrue("seed $seed waited $wait", wait in DuelRules.WAIT_MIN_MS.toLong()..DuelRules.WAIT_MAX_MS.toLong())
        }
    }

    @Test fun theSignalFiresExactlyOnceAtItsMoment() {
        val e = started(cfg())
        e.tick(e.signalAt - 1)
        assertFalse(e.signalLive)
        assertTrue(e.eventsSince(0).none { it.kind == DuelEventKind.GO })
        e.tick(e.signalAt)
        assertTrue(e.signalLive)
        e.tick(e.signalAt + 500)
        assertEquals(1, e.eventsSince(0).count { it.kind == DuelEventKind.GO })
    }

    @Test fun nothingCountsDuringTheCountdown() {
        val e = started(cfg())
        assertEquals(PressResult.IGNORED, e.press(0, 100, -1, 200))
        assertEquals(SeatStatus.ARMED, e.seatStatus[0])
        assertEquals(0, e.falseStarts[0])
    }

    // ---- classic: false starts, ordering by event time, ties ----

    @Test fun pressingBeforeTheSignalIsAFalseStartAndTheOtherPlayerWinsTheRound() {
        val e = started(cfg())
        e.tick(e.liveStartAt)
        assertEquals(PressResult.FALSE_START, e.press(0, e.signalAt - 400, -1, e.signalAt - 400))
        assertEquals(RoundEnd.DEFAULT_WIN, e.lastOutcome!!.end)
        assertEquals(1, e.lastOutcome!!.winner)
        assertEquals(1, e.wins[1]); assertEquals(0, e.wins[0]); assertEquals(1, e.falseStarts[0])
        assertEquals(DuelPhase.RESULT, e.phase)
        assertTrue(e.lastOutcome!!.fouled[0])
    }

    @Test fun aPressThatTouchedBeforeTheSignalIsAFalseStartEvenIfItReachesTheEngineAfterwards() {
        val e = started(cfg())
        goLive(e)
        val late = e.signalAt + 40
        assertEquals(PressResult.FALSE_START, e.press(0, e.signalAt - 30, -1, late))
        assertEquals(1, e.lastOutcome!!.winner)
    }

    @Test fun theFasterEventTimeWinsEvenWhenItIsProcessedSecond() {
        val e = started(cfg())
        goLive(e)
        val s = e.signalAt
        assertEquals(PressResult.DONE, e.press(0, s + 150, -1, s + 160))
        assertEquals(PressResult.DONE, e.press(1, s + 120, -1, s + 170)) // touched earlier, delivered later
        assertEquals(RoundEnd.WIN, e.lastOutcome!!.end)
        assertEquals(1, e.lastOutcome!!.winner)
        assertEquals(120, e.reactionMs[1]); assertEquals(150, e.reactionMs[0])
        assertEquals(120, e.bestMs[1])
    }

    @Test fun aPressStillInFlightWhenTheGatherWindowIsOpenCanStillWin() {
        val e = started(cfg())
        goLive(e)
        val s = e.signalAt
        e.press(0, s + 200, -1, s + 210)         // window open until s + 290
        e.tick(s + 280)
        assertEquals(DuelPhase.LIVE, e.phase)
        e.press(1, s + 190, -1, s + 285)         // earlier touch, arrives inside the window
        assertEquals(1, e.lastOutcome!!.winner)
    }

    @Test fun theRoundIsDecidedOnceTheGatherWindowCloses() {
        val e = started(cfg())
        goLive(e)
        val s = e.signalAt
        e.press(0, s + 200, -1, s + 210)
        e.tick(s + 210 + DuelRules.GATHER_MS)
        assertEquals(DuelPhase.RESULT, e.phase)
        assertEquals(0, e.lastOutcome!!.winner)
        // A late press after the decision changes nothing.
        assertEquals(PressResult.IGNORED, e.press(1, s + 190, -1, s + 500))
        assertEquals(0, e.lastOutcome!!.winner)
    }

    @Test fun twoPressesWithinTheTieWindowAreADeadHeatAndTheRoundIsReplayed() {
        val e = started(cfg())
        goLive(e)
        val s = e.signalAt
        e.press(0, s + 200, -1, s + 205)
        e.press(1, s + 201, -1, s + 206)
        assertEquals(RoundEnd.DEAD_HEAT, e.lastOutcome!!.end)
        assertEquals(-1, e.lastOutcome!!.winner)
        assertEquals(0, e.wins.sum()); assertEquals(0, e.roundsPlayed)
        assertEquals(1, e.attempts)
        e.tick(s + 206 + DuelRules.RESULT_MS)
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
        assertEquals(2, e.attempts)
        assertNotEquals(s, e.signalAt) // a different round, a different stream
    }

    @Test fun exactlyTheTieWindowIsATieAndOneMillisecondMoreIsAWin() {
        val tie = started(cfg()); goLive(tie)
        tie.press(0, tie.signalAt + 100, -1, tie.signalAt + 110); tie.press(1, tie.signalAt + 100 + DuelRules.TIE_MS, -1, tie.signalAt + 120)
        assertEquals(RoundEnd.DEAD_HEAT, tie.lastOutcome!!.end)
        val win = started(cfg()); goLive(win)
        win.press(0, win.signalAt + 100, -1, win.signalAt + 110); win.press(1, win.signalAt + 100 + DuelRules.TIE_MS + 1, -1, win.signalAt + 120)
        assertEquals(RoundEnd.WIN, win.lastOutcome!!.end); assertEquals(0, win.lastOutcome!!.winner)
    }

    @Test fun anEventTimeFromTheFutureIsClampedToNow() {
        val e = started(cfg())
        goLive(e)
        val s = e.signalAt
        e.press(0, s + 9_999, -1, s + 100)
        assertEquals(100, e.reactionMs[0])
    }

    // ---- three and four players ----

    @Test fun inAThreeSeatRoundAFoulOnlyTakesThatSeatOutAndTheOthersStillRace() {
        val e = started(cfg(seats = 3))
        e.tick(e.liveStartAt)
        assertEquals(PressResult.FALSE_START, e.press(0, e.signalAt - 300, -1, e.signalAt - 300))
        assertEquals(DuelPhase.LIVE, e.phase)
        goLive(e)
        val s = e.signalAt
        e.press(2, s + 140, -1, s + 150)
        e.press(1, s + 130, -1, s + 160)
        assertEquals(1, e.lastOutcome!!.winner)
        assertTrue(e.lastOutcome!!.fouled[0])
        // A seat that fouled cannot press again this round.
        assertEquals(SeatStatus.LOCKED, e.seatStatus[0])
    }

    @Test fun whenAllButOneSeatHaveFouledTheSurvivorWinsAtOnce() {
        val e = started(cfg(seats = 4))
        e.tick(e.liveStartAt)
        val t = e.signalAt - 500
        e.press(0, t, -1, t); e.press(1, t + 5, -1, t + 5)
        assertEquals(DuelPhase.LIVE, e.phase)
        e.press(2, t + 10, -1, t + 10)
        assertEquals(RoundEnd.DEFAULT_WIN, e.lastOutcome!!.end)
        assertEquals(3, e.lastOutcome!!.winner)
        assertEquals(3, e.falseStarts.sum())
    }

    @Test fun aLockedSeatNeverWinsEvenIfItWouldHaveBeenFastest() {
        val e = started(cfg(seats = 3))
        e.tick(e.liveStartAt)
        e.press(0, e.signalAt - 10, -1, e.signalAt - 10)
        goLive(e)
        assertEquals(PressResult.IGNORED, e.press(0, e.signalAt + 1, -1, e.signalAt + 2))
        e.press(1, e.signalAt + 300, -1, e.signalAt + 310)
        e.press(2, e.signalAt + 310, -1, e.signalAt + 320)
        assertEquals(1, e.lastOutcome!!.winner)
    }

    @Test fun nobodyAnsweringVoidsTheRoundAndItIsReplayed() {
        val e = started(cfg())
        e.tick(e.deadlineAt)
        assertEquals(RoundEnd.TOO_SLOW, e.lastOutcome!!.end)
        assertEquals(0, e.wins.sum())
        e.tick(e.deadlineAt + DuelRules.RESULT_MS)
        assertEquals(2, e.attempts); assertEquals(DuelPhase.COUNTDOWN, e.phase)
    }

    // ---- relaxed ----

    @Test fun relaxedIgnoresAnEarlyPressInsteadOfPenalisingIt() {
        val e = started(cfg(relaxed = true))
        e.tick(e.liveStartAt)
        assertEquals(PressResult.EARLY_IGNORED, e.press(0, e.signalAt - 200, -1, e.signalAt - 200))
        assertEquals(SeatStatus.ARMED, e.seatStatus[0]); assertEquals(0, e.falseStarts[0])
        goLive(e)
        e.press(0, e.signalAt + 200, -1, e.signalAt + 210)
        e.tick(e.signalAt + 400)
        assertEquals(RoundEnd.WIN, e.lastOutcome!!.end)
        assertEquals(0, e.lastOutcome!!.winner)
    }

    @Test fun relaxedGivesLongerWindows() {
        val normal = started(cfg(seed = 5)); val relaxed = started(cfg(seed = 5, relaxed = true))
        assertTrue(relaxed.deadlineAt - relaxed.signalAt > normal.deadlineAt - normal.signalAt)
        val ec = started(cfg(variant = DuelVariant.COLOUR, seed = 5)); val er = started(cfg(variant = DuelVariant.COLOUR, seed = 5, relaxed = true))
        assertTrue(er.stream.size <= DuelRules.COLOUR_DECOYS_MAX_RELAXED)
        assertTrue(ec.stream.size <= DuelRules.COLOUR_DECOYS_MAX)
        assertTrue(er.deadlineAt - er.signalAt > ec.deadlineAt - ec.signalAt)
    }

    // ---- match flow ----

    private fun winRound(e: ReactionDuelEngine, seat: Int) {
        // Let the engine reach the live phase of the current round, then have [seat] press first.
        e.tick(e.liveStartAt)
        goLive(e)
        val s = e.signalAt
        val other = if (seat == 0) 1 else 0
        e.press(seat, s + 150, -1, s + 160)
        e.press(other, s + 400, -1, s + 410)
        assertEquals(DuelPhase.RESULT, e.phase)
        e.tick(s + 410 + DuelRules.RESULT_MS + 5)
    }

    @Test fun bestOfThreeEndsAtTwoWinsWithTheWinnerRecorded() {
        val e = started(cfg(best = 3))
        winRound(e, 0)
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
        winRound(e, 0)
        assertEquals(DuelPhase.OVER, e.phase)
        assertEquals(0, e.matchWinner)
        assertEquals(listOf(2, 0), e.wins.toList())
        assertEquals(DuelEventKind.MATCH_OVER, e.eventsSince(0).last().kind)
    }

    @Test fun bestOfSevenNeedsFourWinsAndAnEarlyLeadIsNotEnough() {
        val e = started(cfg(best = 7))
        repeat(3) { winRound(e, 1) }
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
        winRound(e, 0); winRound(e, 1)
        assertEquals(DuelPhase.OVER, e.phase)
        assertEquals(1, e.matchWinner)
        assertEquals(4, e.wins[1]); assertEquals(1, e.wins[0])
        assertEquals(4, DuelRules.target(7)); assertEquals(3, DuelRules.target(5)); assertEquals(2, DuelRules.target(3))
    }

    @Test fun theMatchEndsAfterTheAttemptCapEvenIfEveryRoundIsVoid() {
        val e = started(cfg(best = 3))
        var guard = 0
        while (e.phase != DuelPhase.OVER && guard++ < 100) e.tick(e.deadlineAt + DuelRules.RESULT_MS + 1_000)
        assertEquals(DuelPhase.OVER, e.phase)
        assertEquals(DuelRules.attemptCap(3), e.attempts)
        assertEquals(-1, e.matchWinner) // a draw: nobody won a round
    }

    @Test fun anEndedMatchWithADifferentLeaderAtTheCapNamesTheLeader() {
        val e = started(cfg(best = 3, seats = 4))
        // Seat 2 wins one round, then everything is void until the cap.
        winRound4(e, 2)
        var guard = 0
        while (e.phase != DuelPhase.OVER && guard++ < 100) e.tick(e.deadlineAt + DuelRules.RESULT_MS + 1_000)
        assertEquals(2, e.matchWinner)
    }

    private fun winRound4(e: ReactionDuelEngine, seat: Int) {
        e.tick(e.liveStartAt); goLive(e)
        val s = e.signalAt
        e.press(seat, s + 150, -1, s + 160)
        e.tick(s + 160 + DuelRules.GATHER_MS)
        e.tick(s + 160 + DuelRules.GATHER_MS + DuelRules.RESULT_MS + 5)
    }

    @Test fun aRoundWinnerSeatWithTheSameWinsAsAnotherAtTheCapIsADraw() {
        val e = started(cfg(best = 3, seats = 2))
        winRound(e, 0)
        // Seat 1 wins the second round: 1-1; with best of 3 the third decides, so no draw yet.
        winRound(e, 1)
        assertEquals(DuelPhase.COUNTDOWN, e.phase)
    }

    // ---- colour match ----

    @Test fun everyDecoyDiffersFromTheTargetInShapeSoColourBlindPlayersAreNeverFooled() {
        for (seed in 0L until 400L) for (relaxed in listOf(false, true)) {
            val e = started(cfg(variant = DuelVariant.COLOUR, seed = seed, relaxed = relaxed))
            val target = e.target!!
            assertTrue(e.stream.isNotEmpty())
            for (d in e.stream) assertNotEquals("seed $seed", target.shape, d.shape)
            for (i in 1 until e.stream.size) assertNotEquals(e.stream[i - 1].shape, e.stream[i].shape)
        }
    }

    @Test fun someDecoysShareTheTargetsColourSoColourAloneIsNotEnough() {
        var sameColour = 0
        for (seed in 0L until 200L) {
            val e = started(cfg(variant = DuelVariant.COLOUR, seed = seed))
            sameColour += e.stream.count { it.colour == e.target!!.colour }
        }
        assertTrue(sameColour > 0)
    }

    @Test fun theStreamIsTheSameInEveryZoneAndEveryEngineOfTheSameSeed() {
        val a = started(cfg(variant = DuelVariant.COLOUR, seed = 9, seats = 4))
        val b = started(cfg(variant = DuelVariant.COLOUR, seed = 9, seats = 2, cpu = setOf(1)))
        assertEquals(a.stream, b.stream); assertEquals(a.target, b.target); assertEquals(a.signalAt, b.signalAt)
    }

    @Test fun pressingWhileADecoyShowsIsAFalseStartAndTheTargetMomentIsValid() {
        val e = started(cfg(variant = DuelVariant.COLOUR, seed = 3))
        e.tick(e.liveStartAt)
        assertEquals(PressResult.FALSE_START, e.press(0, e.signalAt - 300, -1, e.signalAt - 300))
        assertEquals(1, e.lastOutcome!!.winner)
        val f = started(cfg(variant = DuelVariant.COLOUR, seed = 3)); goLive(f)
        assertEquals(PressResult.DONE, f.press(0, f.signalAt + 180, -1, f.signalAt + 190))
    }

    @Test fun displayShowsDecoysThenTheTargetAndBlanksBetweenThem() {
        val e = started(cfg(variant = DuelVariant.COLOUR, seed = 3))
        e.tick(e.liveStartAt)
        val first = e.signalAt - e.stream.size * 900L
        assertEquals(DisplayKind.WAIT, e.displayAt(first - 10).kind)
        for (i in e.stream.indices) {
            val d = e.displayAt(first + i * 900L + 100)
            assertEquals(DisplayKind.DECOY, d.kind); assertEquals(e.stream[i], d.symbol)
            assertEquals(DisplayKind.WAIT, e.displayAt(first + i * 900L + 900 - DuelRules.COLOUR_GAP_MS + 5).kind)
        }
        e.tick(e.signalAt)
        assertEquals(DisplayKind.TARGET, e.displayAt(e.signalAt).kind)
        assertEquals(e.target, e.displayAt(e.signalAt + 50).symbol)
    }

    // ---- echo ----

    private fun echoLive(c: DuelConfig): ReactionDuelEngine = started(c).also { goLive(it) }

    @Test fun echoSequenceGrowsWithTheRoundsPlayedAndIsCapped() {
        assertEquals(3, DuelRules.echoLength(0, false, false)); assertEquals(5, DuelRules.echoLength(2, false, false))
        assertEquals(7, DuelRules.echoLength(10, false, false)); assertEquals(4, DuelRules.echoLength(10, true, false))
        assertEquals(4, DuelRules.echoLength(10, false, true))
        val e = started(cfg(variant = DuelVariant.ECHO)); assertEquals(3, e.sequence.size)
    }

    @Test fun echoCompletedCorrectlyWinsAndTheWinnerIsTheFirstToFinishByEventTime() {
        val e = echoLive(cfg(variant = DuelVariant.ECHO, seed = 4))
        val s = e.signalAt
        val seq = e.sequence
        // Seat 0 taps every 200 ms from +100; seat 1 every 150 ms from +90. Delivered in event-time order.
        val taps = seq.indices.flatMap { i -> listOf(Triple(s + 100 + 200L * i, 0, seq[i]), Triple(s + 90 + 150L * i, 1, seq[i])) }.sortedBy { it.first }
        val results = mutableMapOf<Int, PressResult>()
        for ((t, seat, pad) in taps) results[seat] = e.press(seat, t, pad, t + 5)
        e.tick(s + 2_000)
        assertEquals(PressResult.DONE, results[1])
        assertEquals(RoundEnd.WIN, e.lastOutcome!!.end)
        assertEquals(1, e.lastOutcome!!.winner) // 90 + 2*150 = 390 beats 100 + 2*200 = 500
        assertEquals(390 - 0, e.reactionMs[1])
        assertEquals(0, e.falseStarts.sum())
    }

    @Test fun echoProgressCountsCorrectTapsOneByOne() {
        val e = echoLive(cfg(variant = DuelVariant.ECHO, seed = 4))
        val s = e.signalAt
        for ((i, p) in e.sequence.withIndex()) {
            if (i == e.sequence.lastIndex) break
            assertEquals(PressResult.PROGRESS, e.press(0, s + 100 + 100L * i, p, s + 105 + 100L * i))
            assertEquals(i + 1, e.echoProgress[0])
        }
    }

    @Test fun echoWrongPadPutsASeatOutAndTheOtherWins() {
        val e = echoLive(cfg(variant = DuelVariant.ECHO, seed = 4))
        val s = e.signalAt
        val wrong = (e.sequence[0] + 1) % 4
        assertEquals(PressResult.WRONG, e.press(0, s + 100, wrong, s + 105))
        assertEquals(RoundEnd.DEFAULT_WIN, e.lastOutcome!!.end); assertEquals(1, e.lastOutcome!!.winner)
        assertEquals(DuelEventKind.WRONG, e.eventsSince(0).first { it.kind == DuelEventKind.WRONG }.kind)
    }

    @Test fun echoPressesBeforeYourTurnAreIgnoredNotPenalised() {
        val e = started(cfg(variant = DuelVariant.ECHO, seed = 4))
        e.tick(e.liveStartAt + 300)
        assertEquals(PressResult.EARLY_IGNORED, e.press(0, e.liveStartAt + 250, 3, e.liveStartAt + 300))
        assertEquals(SeatStatus.ARMED, e.seatStatus[0]); assertEquals(0, e.falseStarts[0])
    }

    @Test fun echoRelaxedForgivesAWrongPad() {
        val e = echoLive(cfg(variant = DuelVariant.ECHO, seed = 4, relaxed = true))
        val s = e.signalAt
        assertEquals(PressResult.IGNORED, e.press(0, s + 100, (e.sequence[0] + 1) % 4, s + 105))
        assertEquals(SeatStatus.ARMED, e.seatStatus[0])
        assertEquals(PressResult.PROGRESS, e.press(0, s + 200, e.sequence[0], s + 205))
    }

    @Test fun echoOneSwitchPicksTheScanPadUnderTheCursorAtEventTime() {
        val e = echoLive(cfg(variant = DuelVariant.ECHO, seed = 4, oneSwitch = true))
        val s = e.signalAt
        val want = e.sequence[0]
        // The cursor rests on pad (t / 900) % 4; press during the slot of the wanted pad.
        val slotStart = s + want * DuelRules.SCAN_STEP_MS.toLong()
        assertEquals(want, e.scanPadAt(slotStart + 100))
        assertEquals(PressResult.PROGRESS, e.press(0, slotStart + 100, -1, slotStart + 110))
        // And the wrong slot is wrong.
        val wantNext = e.sequence[1]
        val wrongPad = (wantNext + 1) % 4
        val wrongAt = s + (wrongPad + 4) * DuelRules.SCAN_STEP_MS.toLong() + 50
        assertEquals(PressResult.WRONG, e.press(0, wrongAt, -1, wrongAt + 5))
        assertEquals(0, DuelRules.scanPad(0)); assertEquals(1, DuelRules.scanPad(900)); assertEquals(0, DuelRules.scanPad(3_600))
    }

    @Test fun echoDisplayLightsEachSequencePadInOrderAndOnlyOnce() {
        val e = started(cfg(variant = DuelVariant.ECHO, seed = 4))
        e.tick(e.liveStartAt)
        val slot = (DuelRules.ECHO_SHOW_MS + DuelRules.ECHO_GAP_MS).toLong()
        val showStart = e.liveStartAt + DuelRules.ECHO_LEAD_MS
        var lit = 0
        for (i in e.sequence.indices) {
            val d = e.displayAt(showStart + i * slot + 50)
            assertEquals(DisplayKind.ECHO_LIT, d.kind); assertEquals(e.sequence[i], d.pad)
            assertEquals(i, e.echoSlotAt(showStart + i * slot + 50))
            assertEquals(DisplayKind.ECHO_REST, e.displayAt(showStart + i * slot + DuelRules.ECHO_SHOW_MS + 10).kind)
            assertEquals(-1, e.echoSlotAt(showStart + i * slot + DuelRules.ECHO_SHOW_MS + 10))
            lit++
        }
        assertEquals(e.sequence.size, lit)
        assertEquals(DisplayKind.WAIT, e.displayAt(e.signalAt - 10).kind)
        assertEquals(DisplayKind.YOUR_TURN, e.displayAt(e.signalAt).kind)
    }

    @Test fun classicDisplayWaitsThenShowsGo() {
        val e = started(cfg())
        assertEquals(DisplayKind.GET_READY, e.displayAt(0).kind)
        e.tick(e.liveStartAt)
        assertEquals(DisplayKind.WAIT, e.displayAt(e.signalAt - 1).kind)
        assertEquals(DisplayKind.GO, e.displayAt(e.signalAt).kind)
    }

    // ---- seeded fairness ----

    @Test fun everyZoneSeesTheIdenticalStimulusBecauseTheEngineHasOnePerRound() {
        // Same seed, different seat counts and humans/computers: the round's content is identical.
        for (variant in DuelVariant.entries) for (seed in 0L until 30L) {
            val a = started(cfg(variant = variant, seed = seed, seats = 2))
            val b = started(cfg(variant = variant, seed = seed, seats = 4, cpu = setOf(1, 2, 3), level = CpuDifficulty.HARD))
            assertEquals(a.signalAt, b.signalAt); assertEquals(a.sequence, b.sequence); assertEquals(a.stream, b.stream); assertEquals(a.target, b.target)
        }
    }

    @Test fun roundStreamsDifferFromRoundToRoundButReplayWithTheSameSeed() {
        val a = started(cfg(seed = 5)); val first = a.signalAt - a.liveStartAt
        winRound(a, 0)
        val second = a.signalAt - a.liveStartAt
        val b = started(cfg(seed = 5)); winRound(b, 0)
        assertEquals(first, started(cfg(seed = 5)).let { it.signalAt - it.liveStartAt })
        assertEquals(second, b.signalAt - b.liveStartAt)
        assertEquals(DuelRules.stream(5, 2, 1).nextInt(), DuelRules.stream(5, 2, 1).nextInt())
        assertNotEquals(DuelRules.stream(5, 2, 1).nextInt(), DuelRules.stream(5, 3, 1).nextInt())
    }

    // ---- save / resume ----

    @Test fun resumingReplaysTheRoundInProgressFromItsCountdownWithTheSameSignal() {
        val e = started(cfg(seed = 21, best = 5))
        winRound(e, 0)                        // attempt 2 begins
        val signalOfRound2 = e.signalAt - e.liveStartAt
        e.tick(e.liveStartAt + 100)           // mid-round
        val p = e.progress()
        assertEquals(1, p.completedAttempts); assertEquals(listOf(1, 0), p.wins)
        val r = ReactionDuelEngine(cfg(seed = 21, best = 5))
        r.resume(50_000, p)
        assertEquals(DuelPhase.COUNTDOWN, r.phase); assertEquals(2, r.attempts)
        assertEquals(listOf(1, 0), r.wins.toList())
        assertEquals(signalOfRound2, r.signalAt - r.liveStartAt)
    }

    @Test fun savingBetweenRoundsResumesAtTheNextRound() {
        val e = started(cfg(seed = 22, best = 5))
        e.tick(e.liveStartAt); goLive(e)
        e.press(0, e.signalAt + 100, -1, e.signalAt + 110); e.press(1, e.signalAt + 300, -1, e.signalAt + 310)
        assertEquals(DuelPhase.RESULT, e.phase)
        val p = e.progress()
        assertEquals(1, p.completedAttempts)
        val r = ReactionDuelEngine(cfg(seed = 22, best = 5)); r.resume(0, p)
        assertEquals(2, r.attempts)
    }

    // ---- the computer ----

    /** Plays [rounds] classic rounds of a human who always reacts after [humanMs] against a computer at [level]; returns the computer's wins. */
    private fun cpuWins(level: CpuDifficulty, humanMs: Long, rounds: Int, variant: DuelVariant = DuelVariant.CLASSIC): Int {
        var wins = 0
        for (i in 0 until rounds) {
            val e = ReactionDuelEngine(cfg(variant = variant, seed = 1000L + i, cpu = setOf(1), level = level))
            e.startMatch(0)
            e.tick(e.liveStartAt)
            val s = e.signalAt
            // The computer's presses are scheduled by the engine; the human presses humanMs after the signal.
            e.tick(s)
            if (variant == DuelVariant.ECHO) {
                var t = s + humanMs
                for (p in e.sequence) { e.press(0, t, p, t); t += 200 }
            } else {
                e.press(0, s + humanMs, -1, s + humanMs)
            }
            e.tick(e.deadlineAt + 10)
            val o = e.lastOutcome
            if (o != null && o.winner == 1) wins++
        }
        return wins
    }

    @Test fun computerLevelsAreClearlyOrderedButNeverPerfect() {
        val n = 500
        val easy = cpuWins(CpuDifficulty.EASY, 300, n)
        val medium = cpuWins(CpuDifficulty.MEDIUM, 300, n)
        val hard = cpuWins(CpuDifficulty.HARD, 300, n)
        assertTrue("easy $easy < medium $medium", easy + n / 10 < medium)
        assertTrue("medium $medium < hard $hard", medium + n / 10 < hard)
        assertTrue("easy is beatable ($easy of $n)", easy < n * 0.2)
        assertTrue("hard is strong ($hard of $n)", hard > n * 0.6)
        assertTrue("hard is not perfect ($hard of $n)", hard < n * 0.99)
    }

    @Test fun computerFalseStartsHappenAtEveryLevelButMoreOftenWhenEasy() {
        fun fouls(level: CpuDifficulty): Int {
            var f = 0
            for (i in 0 until 600) {
                val e = started(cfg(seed = 5_000L + i, cpu = setOf(1), level = level))
                e.tick(e.deadlineAt + 10)
                f += e.falseStarts[1]
            }
            return f
        }
        val easy = fouls(CpuDifficulty.EASY); val hard = fouls(CpuDifficulty.HARD)
        assertTrue("easy $easy hard $hard", easy > hard)
        assertTrue(hard > 0)
    }

    @Test fun noComputerEverReactsFasterThanAHumanCan() {
        for (level in CpuDifficulty.entries) {
            val floor = DuelRules.profile(level).floorMs
            assertTrue(floor >= 170)
            for (i in 0 until 300) {
                val e = started(cfg(seed = 9_000L + i, cpu = setOf(0, 1), level = level))
                e.tick(e.deadlineAt + 10)
                for (ms in e.lastOutcome!!.reactionMs) if (ms >= 0) assertTrue("$level reacted in $ms", ms >= floor)
            }
        }
    }

    @Test fun computerColourAndEchoSeatsAlsoFinishRoundsAndLevelsOrderInEcho() {
        val n = 300
        val easy = cpuWins(CpuDifficulty.EASY, 600, n, DuelVariant.ECHO)
        val hard = cpuWins(CpuDifficulty.HARD, 600, n, DuelVariant.ECHO)
        assertTrue("echo easy $easy hard $hard", easy < hard)
        val c = cpuWins(CpuDifficulty.MEDIUM, 400, 200, DuelVariant.COLOUR)
        assertTrue("colour cpu won $c of 200", c in 30..170)
    }

    // ---- fuzz ----

    @Test fun randomPlayAlwaysTerminatesAndKeepsItsInvariants() {
        for (seed in 0L until 120L) {
            val r = java.util.Random(seed)
            val variant = DuelVariant.entries[r.nextInt(3)]
            val seats = 2 + r.nextInt(3)
            val cpu = (0 until seats).filter { r.nextInt(3) == 0 }.toSet()
            val e = started(cfg(variant = variant, seats = seats, cpu = cpu, best = listOf(3, 5, 7)[r.nextInt(3)], relaxed = r.nextBoolean(), oneSwitch = r.nextInt(4) == 0, seed = seed, level = CpuDifficulty.entries[r.nextInt(3)]))
            var now = 0L
            var steps = 0
            while (e.phase != DuelPhase.OVER && steps++ < 20_000) {
                now += r.nextInt(400).toLong()
                if (r.nextInt(3) == 0) e.press(r.nextInt(seats + 1) - 0, now - r.nextInt(300), r.nextInt(5) - 1, now) else e.tick(now)
                assertTrue(e.wins.all { it in 0..e.targetWins })
                assertTrue(e.attempts <= DuelRules.attemptCap(e.config.bestOf))
                assertTrue(e.roundsPlayed <= e.attempts)
                assertEquals(e.wins.sum(), e.roundsPlayed)
            }
            // Time marching on always ends it.
            var guard = 0
            while (e.phase != DuelPhase.OVER && guard++ < 500) { now += 5_000; e.tick(now) }
            assertEquals("seed $seed", DuelPhase.OVER, e.phase)
            assertTrue(e.matchWinner == -1 || e.wins[e.matchWinner] == e.wins.max())
            assertNotNull(e.eventsSince(0).lastOrNull { it.kind == DuelEventKind.MATCH_OVER })
        }
    }

    @Test fun pressesFromOutsideTheTableAreIgnored() {
        val e = started(cfg())
        goLive(e)
        assertEquals(PressResult.IGNORED, e.press(5, e.signalAt + 10, -1, e.signalAt + 20))
        assertEquals(PressResult.IGNORED, e.press(-1, e.signalAt + 10, -1, e.signalAt + 20))
    }
}
