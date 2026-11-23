package com.gamesuite.games.echopads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EchoPadsEngineTest {
    private fun engine(
        mode: EchoMode, players: Int, seed: Int = 7, speed: EchoSpeed = EchoSpeed.NORMAL,
        forgiving: Boolean = false, delayScale: Float = 1f
    ) = EchoPadsEngine(EchoConfig(mode, players, speed, delayScale, forgiving), Random(seed))

    private fun started(
        mode: EchoMode, players: Int, seed: Int = 7, speed: EchoSpeed = EchoSpeed.NORMAL,
        forgiving: Boolean = false, delayScale: Float = 1f
    ): EchoDriver {
        val e = engine(mode, players, seed, speed, forgiving, delayScale)
        val d = EchoDriver(e)
        e.startMatch(d.now)
        return d
    }

    // ---- config ----

    @Test fun configRejectsASeatCountThatDoesNotFitTheMode() {
        for ((mode, bad) in listOf(EchoMode.SOLO to 2, EchoMode.PASS_AND_PLAY to 1, EchoMode.PASS_AND_PLAY to 7, EchoMode.DUEL to 3, EchoMode.DUEL to 1)) {
            val failed = try { EchoConfig(mode, bad); false } catch (_: IllegalArgumentException) { true }
            assertTrue("$mode with $bad", failed)
        }
        EchoConfig(EchoMode.PASS_AND_PLAY, 6)
    }

    // ---- determinism and growth ----

    @Test fun theSequenceIsExactlyWhatTheSeededRandomDraws() {
        val d = started(EchoMode.SOLO, 1, seed = 42)
        val reference = Random(42)
        val expected = List(7) { reference.nextInt(EchoPads.COUNT) }
        repeat(6) { d.goodTurn() }
        assertEquals(7, d.e.round)
        assertEquals(expected, d.e.sequence)
    }

    @Test fun twoRunsWithTheSameSeedAreIdenticalAndDifferentSeedsDiffer() {
        fun play(seed: Int): List<Int> {
            val d = started(EchoMode.SOLO, 1, seed = seed)
            repeat(9) { d.goodTurn() }
            return d.e.sequence.toList()
        }
        assertEquals(play(5), play(5))
        assertNotEquals(play(5), play(6))
    }

    @Test fun theSequenceGrowsByOneEachRoundAndKeepsItsStart() {
        val d = started(EchoMode.SOLO, 1)
        var previous = d.e.sequence.toList()
        assertEquals(1, previous.size)
        repeat(8) {
            d.goodTurn()
            val now = d.e.sequence.toList()
            assertEquals(previous.size + 1, now.size)
            assertEquals(previous, now.take(previous.size))
            assertEquals(now.size, d.e.round)
            previous = now
        }
    }

    @Test fun everyPadInTheSequenceIsOneOfTheFour() {
        val d = started(EchoMode.SOLO, 1, seed = 3)
        repeat(20) { d.goodTurn() }
        assertTrue(d.e.sequence.all { it in 0 until EchoPads.COUNT })
        assertEquals(setOf(0, 1, 2, 3), d.e.sequence.toSet())
    }

    // ---- the state machine ----

    @Test fun aTurnRunsReadyShowingWaitingChecking() {
        val d = started(EchoMode.SOLO, 1)
        val seen = mutableListOf(d.e.phase)
        fun note() { if (seen.last() != d.e.phase) seen += d.e.phase }
        while (d.e.phase != EchoPhase.WAITING) { d.step(); note() }
        d.e.tap(d.e.sequence[0], d.now); note()
        assertEquals(listOf(EchoPhase.READY, EchoPhase.SHOWING, EchoPhase.WAITING, EchoPhase.CHECKING), seen)
    }

    @Test fun tapsAreIgnoredWhileReadyAndWhileTheSequenceIsShowing() {
        val d = started(EchoMode.SOLO, 1)
        assertEquals(EchoPhase.READY, d.e.phase)
        assertEquals(TapResult.IGNORED, d.e.tap(d.e.sequence[0], d.now))
        d.until { d.e.phase == EchoPhase.SHOWING }
        // Every tap, even the right pad, even the wrong one, during the whole showing.
        while (d.e.phase == EchoPhase.SHOWING) {
            assertEquals(TapResult.IGNORED, d.e.tap(d.e.sequence[0], d.now))
            assertEquals(TapResult.IGNORED, d.e.tap((d.e.sequence[0] + 1) % 4, d.now))
            d.step(25)
        }
        assertEquals(0, d.e.progress)
        assertEquals(EchoPhase.WAITING, d.e.phase)
    }

    @Test fun aLongerSequenceIsAlsoDeafToTapsWhileItPlays() {
        val d = started(EchoMode.SOLO, 1)
        repeat(4) { d.goodTurn() }
        d.until { d.e.phase == EchoPhase.SHOWING }
        var ignored = 0
        while (d.e.phase == EchoPhase.SHOWING) {
            assertEquals(TapResult.IGNORED, d.e.tap(d.e.sequence[0], d.now)); ignored++
            d.step(40)
        }
        assertTrue(ignored > 10)
        assertEquals(0, d.e.progress)
        assertEquals(null, d.e.outcome)
    }

    @Test fun tapsAreIgnoredWhileTheAnswerIsBeingChecked() {
        val d = started(EchoMode.SOLO, 1)
        d.toWaiting()
        assertEquals(TapResult.SEQUENCE_DONE, d.copyAll())
        assertEquals(EchoPhase.CHECKING, d.e.phase)
        assertEquals(TapResult.IGNORED, d.e.tap(0, d.now))
        assertEquals(TapResult.IGNORED, d.e.tap(1, d.now))
        d.settle()
        assertEquals(2, d.e.round)
    }

    @Test fun anOutOfRangePadIsIgnoredEvenWhileCopying() {
        val d = started(EchoMode.SOLO, 1)
        d.toWaiting()
        assertEquals(TapResult.IGNORED, d.e.tap(-1, d.now))
        assertEquals(TapResult.IGNORED, d.e.tap(4, d.now))
        assertEquals(EchoPhase.WAITING, d.e.phase)
        assertEquals(0, d.e.progress)
    }

    @Test fun theEventsTellTheScreenWhenToPlayEachPadTone() {
        val d = started(EchoMode.SOLO, 1)
        repeat(3) { d.goodTurn() }
        d.toWaiting()
        val shown = d.e.eventsSince(0).filter { it.kind == EchoEventKind.PAD_SHOWN }
        // Round 1 shows 1 pad, round 2 shows 2, round 3 shows 3 and round 4 is now fully shown: 1 + 2 + 3 + 4.
        assertEquals(10, shown.size)
        assertEquals(d.e.sequence, shown.takeLast(4).map { it.pad })
        assertEquals(1, d.e.eventsSince(0).count { it.kind == EchoEventKind.YOUR_TURN && it.serial > shown.last().serial })
    }

    @Test fun litPadFollowsThePatternOnTheClock() {
        val e = engine(EchoMode.PASS_AND_PLAY, 2)
        e.startMatch(0)
        e.ready(1_000)
        val t = e.timing
        // Nothing lit during the head pause, the pad is lit for litMs, dark for the gap.
        assertEquals(-1, e.litPad(1_000 + EchoRules.SHOW_HEAD_MS - 10))
        assertEquals(e.sequence[0], e.litPad(1_000 + EchoRules.SHOW_HEAD_MS + 10))
        assertEquals(-1, e.litPad(1_000 + EchoRules.SHOW_HEAD_MS + t.litMs + 10))
        assertEquals(0, e.litStep(1_000 + EchoRules.SHOW_HEAD_MS + 10))
        // Not showing: nothing is lit.
        assertEquals(-1, engine(EchoMode.SOLO, 1).litPad(0))
    }

    @Test fun timeSpentAwayDoesNotSkipThePattern() {
        val e = engine(EchoMode.PASS_AND_PLAY, 2)
        e.startMatch(0)
        e.ready(1_000)
        e.tick(1_500)
        assertEquals(EchoPhase.SHOWING, e.phase)
        e.shiftTime(10_000)
        e.tick(11_500)
        assertEquals("pattern should still be playing after the app was away", EchoPhase.SHOWING, e.phase)
        e.tick(1_000 + 10_000 + EchoRules.SHOW_HEAD_MS + e.timing.stepMs + 1)
        assertEquals(EchoPhase.WAITING, e.phase)
    }

    @Test fun aShiftAlsoPushesBackTheLeadInAndFeedbackPauses() {
        val d = started(EchoMode.SOLO, 1)
        d.e.shiftTime(5_000)
        d.e.tick(d.now + 1_000)
        assertEquals(EchoPhase.READY, d.e.phase)
        d.until { d.e.phase == EchoPhase.WAITING }
        d.copyAll()
        d.e.shiftTime(5_000)
        d.e.tick(d.now + 2_000)
        assertEquals(EchoPhase.CHECKING, d.e.phase)
    }

    // ---- solo ----

    @Test fun aCorrectCopyScoresAndGrowsTheNextRound() {
        val d = started(EchoMode.SOLO, 1)
        d.toWaiting()
        assertEquals(TapResult.SEQUENCE_DONE, d.copyAll())
        assertEquals(EchoOutcome.OK, d.e.outcome)
        d.settle()
        assertEquals(1, d.e.completedBy(0))
        assertEquals(2, d.e.round)
        assertEquals(EchoPhase.READY, d.e.phase)
    }

    @Test fun progressCountsUpPadByPad() {
        val d = started(EchoMode.SOLO, 1)
        repeat(3) { d.goodTurn() }
        d.toWaiting()
        for (i in d.e.sequence.indices.take(3)) {
            assertEquals(i, d.e.progress)
            assertEquals(TapResult.PROGRESS, d.e.tap(d.e.sequence[i], d.now))
            assertEquals(EchoPhase.WAITING, d.e.phase)
        }
        assertEquals(TapResult.SEQUENCE_DONE, d.e.tap(d.e.sequence[3], d.now))
    }

    @Test fun aWrongPadEndsASoloGameWithTheRoundsCopied() {
        val d = started(EchoMode.SOLO, 1)
        repeat(4) { d.goodTurn() }
        d.toWaiting()
        assertEquals(TapResult.MISS, d.miss())
        assertEquals(EchoOutcome.MISS, d.e.outcome)
        assertEquals(EchoPhase.CHECKING, d.e.phase)
        d.settle()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(4, d.e.completedBy(0))
        assertTrue(d.e.winners.isEmpty())
        assertEquals(EchoEventKind.MATCH_OVER, d.e.eventsSince(0).last().kind)
    }

    @Test fun aWrongPadLaterInThePatternAlsoMisses() {
        val d = started(EchoMode.SOLO, 1)
        repeat(2) { d.goodTurn() }
        d.toWaiting()
        assertEquals(TapResult.PROGRESS, d.e.tap(d.e.sequence[0], d.now))
        assertEquals(TapResult.MISS, d.miss())
        d.settle()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(2, d.e.completedBy(0))
    }

    @Test fun aMissedFirstRoundScoresZero() {
        val d = started(EchoMode.SOLO, 1)
        d.badTurn()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(0, d.e.completedBy(0))
    }

    @Test fun noTapsAfterTheGameIsOver() {
        val d = started(EchoMode.SOLO, 1)
        d.badTurn()
        assertEquals(TapResult.IGNORED, d.e.tap(0, d.now))
        assertFalse(d.e.ready(d.now))
    }

    @Test fun aSoloGameEndsAtTheLongestPatternAsAWin() {
        val d = started(EchoMode.SOLO, 1, speed = EchoSpeed.FAST, delayScale = 0.25f)
        repeat(EchoRules.MAX_LENGTH) { d.goodTurn() }
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(EchoRules.MAX_LENGTH, d.e.sequence.size)
        assertEquals(EchoRules.MAX_LENGTH, d.e.completedBy(0))
        assertEquals(listOf(0), d.e.winners)
    }

    // ---- forgiving ----

    @Test fun forgivingGivesOneFreeTryThatShowsTheSamePatternAgain() {
        val d = started(EchoMode.SOLO, 1, forgiving = true)
        repeat(3) { d.goodTurn() }
        d.toWaiting()
        val before = d.e.sequence.toList()
        d.e.tap(before[0], d.now)
        assertEquals(TapResult.RETRY, d.miss())
        assertEquals(EchoOutcome.RETRY, d.e.outcome)
        assertTrue(d.e.retryUsed)
        d.settle()
        assertEquals(EchoPhase.SHOWING, d.e.phase)
        assertEquals(before, d.e.sequence.toList())
        assertEquals(0, d.e.progress)
        assertEquals(3, d.e.completedBy(0))
        // The free try is spent: the next wrong pad is a real miss.
        d.toWaiting()
        assertEquals(TapResult.MISS, d.miss())
        d.settle()
        assertEquals(EchoPhase.OVER, d.e.phase)
    }

    @Test fun afterAFreeTryACorrectCopyStillScores() {
        val d = started(EchoMode.SOLO, 1, forgiving = true)
        d.toWaiting()
        assertEquals(TapResult.RETRY, d.miss())
        d.settle()
        d.toWaiting()
        assertEquals(TapResult.SEQUENCE_DONE, d.copyAll())
        d.settle()
        assertEquals(1, d.e.completedBy(0))
        assertEquals(2, d.e.round)
    }

    @Test fun theFreeTryIsRenewedEveryTurn() {
        val d = started(EchoMode.SOLO, 1, forgiving = true)
        repeat(3) {
            d.toWaiting()
            assertEquals(TapResult.RETRY, d.miss())
            d.settle()
            d.toWaiting()
            assertEquals(TapResult.SEQUENCE_DONE, d.copyAll())
            d.settle()
        }
        assertEquals(3, d.e.completedBy(0))
        assertFalse(d.e.retryUsed)
    }

    @Test fun withoutForgivingTheFirstWrongPadIsAMiss() {
        val d = started(EchoMode.SOLO, 1, forgiving = false)
        d.toWaiting()
        assertEquals(TapResult.MISS, d.miss())
    }

    // ---- pass and play ----

    @Test fun passAndPlayWaitsForReadyAtEveryHandOff() {
        val d = started(EchoMode.PASS_AND_PLAY, 3)
        assertTrue(d.e.awaitingReady)
        d.step(5_000)
        assertEquals("must not start by itself", EchoPhase.READY, d.e.phase)
        assertEquals(0, d.e.currentPlayer)
        d.goodTurn()
        assertEquals(1, d.e.currentPlayer)
        assertTrue(d.e.awaitingReady)
        assertEquals(EchoPhase.READY, d.e.phase)
    }

    @Test fun readyDoesNothingOutsideTheHandOff() {
        val d = started(EchoMode.PASS_AND_PLAY, 2)
        assertTrue(d.e.ready(d.now))
        assertFalse(d.e.ready(d.now))
        assertEquals(EchoPhase.SHOWING, d.e.phase)
    }

    @Test fun everyPlayerCopiesTheSamePatternInTurnThenItGrows() {
        val d = started(EchoMode.PASS_AND_PLAY, 3)
        val first = d.e.sequence.toList()
        val turns = mutableListOf<Int>()
        repeat(3) { turns += d.e.currentPlayer; assertEquals(first, d.e.sequence.toList()); d.goodTurn() }
        assertEquals(listOf(0, 1, 2), turns)
        assertEquals(2, d.e.round)
        assertEquals(0, d.e.currentPlayer)
    }

    @Test fun missersAreOutInTurnOrderAndTheLastOneStandingWins() {
        val d = started(EchoMode.PASS_AND_PLAY, 4)
        // Round 1: everybody copies it.
        repeat(4) { d.goodTurn() }
        // Round 2: player 2 and then player 0 ... order is 0,1,2,3: 1 misses, the rest copy.
        d.goodTurn(); d.badTurn(); d.goodTurn(); d.goodTurn()
        assertEquals(listOf(1), d.e.eliminationOrder)
        assertTrue(d.e.isOut(1))
        assertEquals(listOf(0, 2, 3), d.e.alive)
        // Round 3: only the players still in take part: 0, 2, 3. 3 misses, then 0 misses in round 4.
        assertEquals(0, d.e.currentPlayer)
        d.goodTurn(); d.goodTurn(); d.badTurn()
        assertEquals(listOf(1, 3), d.e.eliminationOrder)
        assertEquals(listOf(0, 2), d.e.alive)
        assertEquals(EchoPhase.READY, d.e.phase)
        // Round 4: 0 misses, 2 copies: 2 wins.
        d.badTurn(); d.goodTurn()
        assertEquals(listOf(1, 3, 0), d.e.eliminationOrder)
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(listOf(2), d.e.winners)
        assertFalse(d.e.isTie)
    }

    @Test fun anEliminatedPlayerNeverGetsAnotherTurn() {
        val d = started(EchoMode.PASS_AND_PLAY, 3)
        d.badTurn(); d.goodTurn(); d.goodTurn()
        assertTrue(d.e.isOut(0))
        val turns = mutableListOf<Int>()
        repeat(4) {
            if (d.e.phase == EchoPhase.OVER) return@repeat
            turns += d.e.currentPlayer
            d.goodTurn()
        }
        assertTrue(turns.isNotEmpty())
        assertFalse(0 in turns)
    }

    @Test fun playersWhoMissInTheSameRoundAreAllOutTogetherButTheOneWhoCopiedWins() {
        val d = started(EchoMode.PASS_AND_PLAY, 3)
        d.badTurn(); d.badTurn(); d.goodTurn()
        assertEquals(listOf(0, 1), d.e.eliminationOrder)
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(listOf(2), d.e.winners)
    }

    @Test fun ifEveryoneLeftMissesTogetherTheyTie() {
        val d = started(EchoMode.PASS_AND_PLAY, 2)
        d.badTurn(); d.badTurn()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertTrue(d.e.isTie)
        assertEquals(listOf(0, 1), d.e.winners)
    }

    @Test fun passAndPlayHonoursTheFreeTryForEveryPlayer() {
        val d = started(EchoMode.PASS_AND_PLAY, 2, forgiving = true)
        d.toWaiting()
        assertEquals(TapResult.RETRY, d.miss())
        d.settle()
        d.toWaiting()
        d.copyAll(); d.settle()
        assertFalse(d.e.isOut(0))
        assertEquals(1, d.e.currentPlayer)
    }

    @Test fun passAndPlayWithMaxPlayersRunsToAWinner() {
        val d = started(EchoMode.PASS_AND_PLAY, 6)
        // Each round the first player still in misses and everybody else copies it.
        repeat(5) {
            val inRound = d.e.alive
            d.badTurn()
            inRound.drop(1).forEach { _ -> d.goodTurn() }
        }
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(listOf(5), d.e.winners)
        assertEquals(listOf(0, 1, 2, 3, 4), d.e.eliminationOrder)
    }

    // ---- duel ----

    @Test fun duelTurnsAlternateAndTheFirstPlayerSwapsEachRound() {
        val d = started(EchoMode.DUEL, 2)
        val order = mutableListOf<Int>()
        repeat(6) { order += d.e.currentPlayer; d.goodTurn() }
        assertEquals(listOf(0, 1, 1, 0, 0, 1), order)
    }

    @Test fun duelStartsWithoutAHandOffButWithALeadIn() {
        val d = started(EchoMode.DUEL, 2)
        assertFalse(d.e.awaitingReady)
        assertEquals(EchoPhase.READY, d.e.phase)
        d.until { d.e.phase == EchoPhase.SHOWING }
    }

    @Test fun inADuelBothCopyingGrowsTheSharedPattern() {
        val d = started(EchoMode.DUEL, 2)
        d.goodTurn(); d.goodTurn()
        assertEquals(2, d.e.round)
        assertEquals(EchoPhase.READY, d.e.phase)
        assertEquals(1, d.e.completedBy(0)); assertEquals(1, d.e.completedBy(1))
    }

    @Test fun inADuelTheOneWhoSlipsUpLoses() {
        val d = started(EchoMode.DUEL, 2)
        d.goodTurn()   // player 0 copies it
        d.badTurn()    // player 1 slips up
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(listOf(0), d.e.winners)
        assertFalse(d.e.isTie)
        assertTrue(d.e.isOut(1))
    }

    @Test fun inADuelTheFirstPlayerSlippingUpDoesNotEndItBeforeTheOtherHasHadTheirGo() {
        val d = started(EchoMode.DUEL, 2)
        d.badTurn()
        assertEquals(EchoPhase.READY, d.e.phase)
        assertEquals(1, d.e.currentPlayer)
        d.goodTurn()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertEquals(listOf(1), d.e.winners)
    }

    @Test fun inADuelBothSlippingUpIsATie() {
        val d = started(EchoMode.DUEL, 2)
        d.badTurn(); d.badTurn()
        assertEquals(EchoPhase.OVER, d.e.phase)
        assertTrue(d.e.isTie)
        assertEquals(listOf(0, 1), d.e.winners)
    }

    @Test fun duelForgivingStillLetsAPlayerRecover() {
        val d = started(EchoMode.DUEL, 2, forgiving = true)
        d.toWaiting()
        assertEquals(TapResult.RETRY, d.miss())
        d.settle(); d.toWaiting()
        d.copyAll(); d.settle()
        d.goodTurn()
        assertEquals(2, d.e.round)
        assertEquals(EchoPhase.READY, d.e.phase)
    }

    // ---- save and resume ----

    @Test fun progressRoundTripsAndResumesTheTurnInProgress() {
        val d = started(EchoMode.PASS_AND_PLAY, 3, seed = 11)
        repeat(3) { d.goodTurn() }       // round 1
        d.goodTurn()                     // round 2, player 0
        d.badTurn()                      // round 2, player 1 is out
        d.toWaiting()                    // player 2 is in the middle of their turn
        val p = d.e.progressSnapshot()
        val e2 = engine(EchoMode.PASS_AND_PLAY, 3, seed = 99)
        assertTrue(e2.resume(5_000, p))
        assertEquals(EchoPhase.READY, e2.phase)
        assertTrue(e2.awaitingReady)
        assertEquals(2, e2.currentPlayer)
        assertEquals(d.e.sequence.toList(), e2.sequence.toList())
        assertEquals(listOf(1), e2.eliminationOrder)
        assertTrue(e2.isOut(1))
        assertEquals(p, e2.progressSnapshot())
        // And it plays on to the end.
        val d2 = EchoDriver(e2, 5_000)
        d2.goodTurn()
        assertEquals(0, e2.currentPlayer)
    }

    @Test fun soloProgressResumesAtTheStartOfTheRound() {
        val d = started(EchoMode.SOLO, 1)
        repeat(5) { d.goodTurn() }
        val e2 = engine(EchoMode.SOLO, 1)
        assertTrue(e2.resume(0, d.e.progressSnapshot()))
        assertEquals(6, e2.round)
        assertEquals(5, e2.completedBy(0))
    }

    @Test fun corruptProgressIsRejectedAndLeavesTheEngineUntouched() {
        val d = started(EchoMode.PASS_AND_PLAY, 3, seed = 11)
        repeat(3) { d.goodTurn() }
        val good = d.e.progressSnapshot()
        val cfg = d.e.config
        val bad = listOf(
            "pad out of range" to good.copy(sequence = good.sequence + 9),
            "empty sequence" to good.copy(sequence = emptyList()),
            "too long" to good.copy(sequence = List(EchoRules.MAX_LENGTH + 1) { 0 }),
            "order position past the end" to good.copy(orderPos = good.order.size),
            "negative position" to good.copy(orderPos = -1),
            "duplicate player in order" to good.copy(order = listOf(0, 0, 1)),
            "unknown player in order" to good.copy(order = listOf(0, 1, 5)),
            "results of the wrong size" to good.copy(results = listOf(-1)),
            "result out of range" to good.copy(results = good.results.map { 3 }),
            "completed too high" to good.copy(completed = good.completed.map { 99 }),
            "elimination without being out" to good.copy(elimination = listOf(1)),
            "out without elimination" to good.copy(out = listOf(false, true, false)),
            "results contradict the position" to good.copy(results = listOf(1, 1, 1))
        )
        for ((why, p) in bad) {
            val e = EchoPadsEngine(cfg, Random(1))
            assertFalse("should reject: $why", e.resume(0, p))
            assertTrue("engine touched by: $why", e.sequence.isEmpty())
        }
        // A solo save cannot be loaded into a duel or a party, nor the other way round.
        assertFalse(EchoPadsEngine(EchoConfig(EchoMode.DUEL, 2), Random(1)).resume(0, good.copy(order = listOf(0, 1), results = listOf(-1, -1))))
        assertFalse(EchoPadsEngine(EchoConfig(EchoMode.SOLO, 1), Random(1)).resume(0, good))
    }

    @Test fun duelProgressMustBeInTheRightTurnOrderForTheRound() {
        val d = started(EchoMode.DUEL, 2)
        d.goodTurn(); d.goodTurn()           // round 2 begins: player 1 goes first
        val good = d.e.progressSnapshot()
        assertEquals(listOf(1, 0), good.order)
        assertTrue(EchoPadsEngine(d.e.config, Random(1)).resume(0, good))
        assertFalse(EchoPadsEngine(d.e.config, Random(1)).resume(0, good.copy(order = listOf(0, 1))))
    }

    // ---- speed ----

    @Test fun theEnginesTimingFollowsTheSpeedAndTheGameSpeed() {
        val slow = engine(EchoMode.SOLO, 1, speed = EchoSpeed.SLOW).also { it.startMatch(0) }.timing
        val normal = engine(EchoMode.SOLO, 1, speed = EchoSpeed.NORMAL).also { it.startMatch(0) }.timing
        val fast = engine(EchoMode.SOLO, 1, speed = EchoSpeed.FAST).also { it.startMatch(0) }.timing
        assertTrue(slow.stepMs > normal.stepMs && normal.stepMs > fast.stepMs)
        val slowGame = engine(EchoMode.SOLO, 1, delayScale = 2.4f).also { it.startMatch(0) }.timing
        assertTrue(slowGame.stepMs > normal.stepMs)
    }

    @Test fun aSlowerSpeedKeepsThePatternOnScreenLonger() {
        fun showingMs(speed: EchoSpeed): Long {
            val e = engine(EchoMode.PASS_AND_PLAY, 2, speed = speed)
            e.startMatch(0)
            e.ready(0)
            var t = 0L
            while (e.phase == EchoPhase.SHOWING) { t += 10; e.tick(t) }
            return t
        }
        assertTrue(showingMs(EchoSpeed.SLOW) > showingMs(EchoSpeed.NORMAL))
        assertTrue(showingMs(EchoSpeed.NORMAL) > showingMs(EchoSpeed.FAST))
    }
}
