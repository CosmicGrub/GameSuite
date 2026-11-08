package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RpsEngineTest {
    private val R = RpsMove.ROCK; private val P = RpsMove.PAPER; private val S = RpsMove.SCISSORS
    private val L = RpsMove.LIZARD; private val K = RpsMove.SPOCK

    private fun humans(n: Int) = List(n) { RpsSeat(false) }
    private fun cfg(n: Int = 2, variant: RpsVariant = RpsVariant.CLASSIC, format: RpsFormat = RpsFormat.BEST_OF_3, timer: Int = 0, relaxed: Boolean = false, seats: List<RpsSeat> = humans(n), seed: Long = 5) =
        RpsConfig(seats, variant, format, timer, relaxed, seed)

    /** Everyone locks the given moves, then the clock runs through the countdown to the reveal. */
    private fun round(e: RpsEngine, vararg moves: RpsMove) {
        moves.forEachIndexed { i, m -> assertEquals(RpsReject.NONE, e.lock(i, m)) }
        var guard = 0
        while (e.view().phase == RpsPhase.COUNTDOWN || e.view().phase == RpsPhase.PICK) { e.advance(100); check(guard++ < 400) }
    }

    // ---- resolution tables -----------------------------------------------------------------

    @Test fun classicTableAllNinePairs() {
        val v = RpsVariant.CLASSIC
        val expected = mapOf((R to S) to true, (S to P) to true, (P to R) to true, (S to R) to false, (P to S) to false, (R to P) to false, (R to R) to false, (P to P) to false, (S to S) to false)
        for ((pair, wins) in expected) assertEquals("$pair", wins, RpsRules.beats(v, pair.first, pair.second))
    }

    @Test fun lizardSpockTableAllTwentyFivePairs() {
        val v = RpsVariant.LIZARD_SPOCK
        val wins = setOf(R to S, R to L, P to R, P to K, S to P, S to L, L to P, L to K, K to S, K to R)
        for (a in v.moves) for (b in v.moves) assertEquals("$a vs $b", (a to b) in wins, RpsRules.beats(v, a, b))
        // every move beats exactly two and loses to exactly two
        for (m in v.moves) { assertEquals(2, v.moves.count { RpsRules.beats(v, m, it) }); assertEquals(2, RpsRules.beatenBy(v, m).size) }
    }

    @Test fun lizardAndSpockAreNotClassicMoves() {
        assertFalse(RpsRules.beats(RpsVariant.CLASSIC, R, L)); assertFalse(RpsRules.beats(RpsVariant.CLASSIC, K, S))
    }

    @Test fun twoPlayerRoundsResolveWinnerLoserOrTie() {
        for (a in RpsVariant.LIZARD_SPOCK.moves) for (b in RpsVariant.LIZARD_SPOCK.moves) {
            val o = RpsRules.resolve(RpsVariant.LIZARD_SPOCK, listOf(a, b))
            when {
                a == b -> assertTrue(o.tie)
                RpsRules.beats(RpsVariant.LIZARD_SPOCK, a, b) -> assertEquals(listOf(0), o.winners)
                else -> assertEquals(listOf(1), o.winners)
            }
        }
    }

    @Test fun threeAndFourPlayerRoundsUseTheDominantMove() {
        val c = RpsVariant.CLASSIC
        assertEquals(listOf(0, 1), RpsRules.resolve(c, listOf(R, R, S)).winners) // two share the winning move
        assertEquals(listOf(2), RpsRules.resolve(c, listOf(R, R, P)).winners)
        assertTrue(RpsRules.resolve(c, listOf(R, P, S)).tie) // all three present: nothing dominates
        assertTrue(RpsRules.resolve(c, listOf(R, R, R, R)).tie)
        assertEquals(listOf(3), RpsRules.resolve(c, listOf(S, S, S, R)).winners)
        // lizard-spock, four players: rock beats lizard and scissors
        val ls = RpsVariant.LIZARD_SPOCK
        assertEquals(listOf(0), RpsRules.resolve(ls, listOf(R, L, S, S)).winners.take(1))
        assertTrue(RpsRules.resolve(ls, listOf(R, P, S, L)).tie) // four distinct moves, none beats all three others
    }

    @Test fun anOutOfPlaySeatIsIgnored() {
        val o = RpsRules.resolve(RpsVariant.CLASSIC, listOf(R, null, S))
        assertEquals(listOf(0), o.winners); assertEquals(listOf(2), o.losers)
        assertEquals(0, RpsRules.resultFor(o, 1))
    }

    // ---- secrecy ---------------------------------------------------------------------------

    @Test fun picksAreHiddenUntilTheRevealAndShowAsLockedOnly() {
        val e = RpsEngine(cfg())
        e.lock(0, R)
        var v = e.view()
        assertEquals(listOf(true, false), v.locked); assertTrue(v.picks.all { it == null }); assertNull(v.outcome)
        e.lock(1, S)
        v = e.view()
        assertEquals(RpsPhase.COUNTDOWN, v.phase); assertEquals(3, v.countdown)
        assertTrue("still hidden during the countdown", v.picks.all { it == null } && v.outcome == null)
        e.advance(800); assertTrue(e.view().picks.all { it == null })
        e.advance(1700)
        v = e.view()
        assertEquals(RpsPhase.RESULT, v.phase); assertEquals(listOf(R, S), v.picks); assertEquals(listOf(0), v.outcome!!.winners)
    }

    @Test fun theViewAndTheSavedSnapshotNeverMentionAPendingPick() {
        val game = RpsGame(PpClock(), { 3L })
        game.init(ppContext(2, 0)); game.startMatch()
        game.lock(0, RpsMove.SPOCK)
        val text = game.view.value.toString() + game.snapshot()
        for (m in RpsMove.entries) assertFalse("leaked ${m.name}", text.contains(m.name, ignoreCase = true))
    }

    @Test fun lockingIsRefusedOutsideThePickPhaseAndForBadSeatsOrMoves() {
        val e = RpsEngine(cfg(seats = listOf(RpsSeat(false), RpsSeat(true))))
        assertEquals(RpsReject.BOT_SEAT, e.lock(1, R)); assertEquals(RpsReject.NOT_A_SEAT, e.lock(5, R)); assertEquals(RpsReject.BAD_MOVE, e.lock(0, L))
        assertEquals(RpsReject.NOT_LOCKED, e.unlock(0))
        e.lock(0, R); assertEquals(RpsReject.NONE, e.unlock(0)); e.lock(0, R)
        e.advance(4000) // computer locks, countdown runs, reveal
        assertEquals(RpsReject.WRONG_PHASE, e.lock(0, P)); assertEquals(RpsReject.WRONG_PHASE, e.unlock(0))
    }

    // ---- match rules -----------------------------------------------------------------------

    @Test fun bestOfThreeEndsAtTwoWinsAndTiesReplayWithoutCounting() {
        val e = RpsEngine(cfg())
        round(e, R, R)
        assertTrue(e.view().outcome!!.tie); assertEquals(listOf(0, 0), e.view().wins); assertEquals(1, e.view().tieStreak)
        assertTrue(e.next())
        round(e, R, S); assertEquals(listOf(1, 0), e.view().wins); assertEquals(RpsPhase.RESULT, e.view().phase)
        e.next(); round(e, S, R); assertEquals(listOf(1, 1), e.view().wins)
        e.next(); round(e, P, R)
        assertEquals(RpsPhase.OVER, e.view().phase); assertEquals(listOf(0), e.view().winners); assertEquals(listOf(2, 1), e.view().wins)
    }

    @Test fun bestOfFiveAndSevenNeedThreeAndFour() {
        assertEquals(3, RpsFormat.BEST_OF_5.winsNeeded); assertEquals(4, RpsFormat.BEST_OF_7.winsNeeded)
        val e = RpsEngine(cfg(format = RpsFormat.BEST_OF_5))
        repeat(2) { round(e, R, S); e.next() }
        assertEquals(RpsPhase.PICK, e.view().phase)
        round(e, R, S); assertEquals(RpsPhase.OVER, e.view().phase)
    }

    @Test fun fourPlayerMatchPlaysOnWhenTwoReachTheTargetTogether() {
        val e = RpsEngine(cfg(n = 4, format = RpsFormat.BEST_OF_3))
        round(e, R, R, S, S); e.next()                         // seats 0 and 1 win
        round(e, R, R, S, S)                                   // both reach 2: tied at the top, play on
        assertEquals(listOf(2, 2, 0, 0), e.view().wins); assertEquals(RpsPhase.RESULT, e.view().phase)
        e.next(); round(e, R, S, S, S)
        assertEquals(RpsPhase.OVER, e.view().phase); assertEquals(listOf(0), e.view().winners)
    }

    @Test fun eliminationDropsLosersAndTheLastPlayerStandingWins() {
        val e = RpsEngine(cfg(n = 4, format = RpsFormat.ELIMINATION))
        round(e, R, R, S, P)       // rock vs scissors vs paper: all three present, tie
        assertTrue(e.view().outcome!!.tie); e.next()
        round(e, R, R, S, S)       // rock beats scissors: seats 2 and 3 are out
        assertEquals(listOf(true, true, false, false), e.view().active); e.next()
        assertEquals(RpsReject.OUT_OF_PLAY, e.lock(2, R))
        round(e, P, R)             // eliminated seats are not waited for
        assertEquals(RpsPhase.OVER, e.view().phase); assertEquals(listOf(0), e.view().winners)
    }

    @Test fun nextDoesNothingExceptAfterAResult() {
        val e = RpsEngine(cfg())
        assertFalse(e.next())
    }

    // ---- time, timer, relaxed -------------------------------------------------------------

    @Test fun theCountdownIsThreeBeatsAndRelaxedIsSlower() {
        fun beats(relaxed: Boolean): Int {
            val e = RpsEngine(cfg(relaxed = relaxed)); e.lock(0, R); e.lock(1, S)
            var ms = 0
            while (e.view().phase == RpsPhase.COUNTDOWN) { e.advance(50); ms += 50 }
            return ms
        }
        assertTrue(beats(false) in 2300..2600); assertTrue(beats(true) > beats(false) * 1.4)
    }

    @Test fun theResultAdvancesByItselfUnlessRelaxed() {
        val e = RpsEngine(cfg()); round(e, R, R)
        e.advance(e.autoAdvanceMs + 100)
        assertEquals(RpsPhase.PICK, e.view().phase); assertEquals(2, e.view().round)
        val r = RpsEngine(cfg(relaxed = true)); round(r, R, R)
        assertEquals(0, r.autoAdvanceMs); r.advance(60_000)
        assertEquals(RpsPhase.RESULT, r.view().phase)
    }

    private fun run(e: RpsEngine, ms: Int) { var left = ms; while (left > 0) { val s = minOf(left, 1000); e.advance(s); left -= s } }

    @Test fun theTimerPicksForSlowPlayersAndRelaxedDoublesIt() {
        val e = RpsEngine(cfg(timer = 10_000)); e.lock(0, R)
        run(e, 9_900); assertEquals(listOf(true, false), e.view().locked)
        assertEquals(100, e.view().timerLeftMs)
        run(e, 200); assertTrue(e.view().locked.all { it })
        assertTrue(e.drainEvents().any { it.kind == RpsEventKind.AUTO_PICK && it.seat == 1 })
        val r = RpsEngine(cfg(timer = 10_000, relaxed = true)); r.lock(0, R)
        run(r, 15_000); assertEquals(listOf(true, false), r.view().locked)
        run(r, 5_100); assertTrue(r.view().locked.all { it })
    }

    @Test fun computersThinkForANaturalTimeBeforeLocking() {
        val e = RpsEngine(cfg(seats = listOf(RpsSeat(false), RpsSeat(true, CpuDifficulty.HARD))))
        e.advance(400); assertEquals(listOf(false, false), e.view().locked)
        e.advance(2000); assertTrue(e.view().locked[1])
    }

    // ---- the computer cannot see the human's pick ------------------------------------------

    private class SpyBrain : RpsBrain {
        val observed = mutableListOf<RpsRoundRecord>()
        val chooseCalls = mutableListOf<Pair<List<Int>, Int>>()
        override fun observe(record: RpsRoundRecord) { observed += record }
        override fun choose(ctx: RpsBrainContext): RpsMove { chooseCalls += ctx.opponents to observed.size; return RpsMove.PAPER }
    }

    @Test fun theBrainOnlyEverReceivesRevealedRounds() {
        val spy = SpyBrain()
        val e = RpsEngine(cfg(seats = listOf(RpsSeat(false), RpsSeat(true, CpuDifficulty.HARD)), format = RpsFormat.BEST_OF_7)) { _, _ -> spy }
        e.lock(0, RpsMove.ROCK) // the human locks FIRST
        e.advance(3000)
        assertEquals(1, spy.chooseCalls.size)
        assertEquals(0, spy.chooseCalls[0].second) // it chose having seen zero rounds
        assertTrue("no record before the reveal", spy.observed.isEmpty() || e.view().phase != RpsPhase.PICK)
        e.advance(3000)
        assertEquals(1, spy.observed.size)
        assertEquals(listOf(RpsMove.ROCK, RpsMove.PAPER), spy.observed[0].picks)
    }

    @Test fun theComputersChoiceDoesNotDependOnTheHumansCurrentPick() {
        fun cpuPick(human: RpsMove): RpsMove? {
            val e = RpsEngine(cfg(seats = listOf(RpsSeat(false), RpsSeat(true, CpuDifficulty.HARD)), seed = 77))
            e.lock(0, human)
            e.advance(5500)
            return e.view().picks[1]
        }
        val picks = RpsVariant.CLASSIC.moves.map { cpuPick(it) }
        assertEquals("the CPU played the same whatever the human locked", 1, picks.toSet().size)
    }

    @Test fun theCpuLocksBeforeTheHumanAndStillCannotSeeThem() {
        val e = RpsEngine(cfg(seats = listOf(RpsSeat(false), RpsSeat(true, CpuDifficulty.MEDIUM))))
        e.advance(3000)
        assertTrue(e.view().locked[1] && !e.view().locked[0] && e.view().picks.all { it == null })
    }

    // ---- fuzz & snapshots -----------------------------------------------------------------

    @Test fun fuzzRandomMatchesTerminateAndRestoreRoundTrips() {
        val rnd = Random(11)
        repeat(300) { run ->
            val n = 2 + rnd.nextInt(3)
            val variant = RpsVariant.entries[rnd.nextInt(2)]; val format = RpsFormat.entries[rnd.nextInt(4)]
            val seats = List(n) { if (rnd.nextBoolean()) RpsSeat(false) else RpsSeat(true, CpuDifficulty.entries[rnd.nextInt(3)]) }
            val e = RpsEngine(cfg(seats = seats, variant = variant, format = format, timer = if (rnd.nextBoolean()) 3000 else 0, seed = run.toLong()))
            var guard = 0
            while (!e.isOver && guard++ < 20_000) {
                val v = e.view()
                if (v.phase == RpsPhase.PICK) {
                    val s = rnd.nextInt(n)
                    if (rnd.nextInt(4) == 0) e.lock(s, variant.moves[rnd.nextInt(variant.moves.size)])
                    if (rnd.nextInt(9) == 0) e.unlock(s)
                } else if (v.phase == RpsPhase.RESULT && rnd.nextBoolean()) e.next()
                e.advance(rnd.nextInt(1, 400))
                e.drainEvents()
            }
            assertTrue("run $run ended", e.isOver)
            val w = e.view().winners
            assertTrue(w.isNotEmpty() && w.all { it in 0 until n })
            if (format.elimination) assertEquals(1, e.view().active.count { it })
        }
    }

    @Test fun gameSnapshotRestoresAcrossRoundsAndRestartsAMidRound() {
        val clock = PpClock()
        val a = RpsGame(clock, { 9L }); val ctx = ppContext(3, 0)
        a.formatId = "bo5"; a.variantId = "lizard"
        a.init(ctx); a.startMatch()
        val e = a.engine!!
        round(e, R, R, S); e.next()                    // 0 and 1 each have a win
        a.lock(0, L)                                   // a half-played round
        val snap = a.snapshot()!!
        val b = RpsGame(clock, { 10L }); b.init(ctx)
        assertTrue(b.restore(snap))
        val v = b.view.value!!
        assertEquals(listOf(1, 1, 0), v.wins); assertEquals(RpsPhase.PICK, v.phase); assertTrue(v.locked.none { it })
        assertEquals(RpsVariant.LIZARD_SPOCK, b.engine!!.config.variant); assertEquals(RpsFormat.BEST_OF_5, b.engine!!.config.format)
    }

    @Test fun badSnapshotsAreRefusedAndLeaveTheGameUntouched() {
        val a = RpsGame(PpClock(), { 1L }); a.init(ppContext(2, 0)); a.startMatch()
        val good = a.snapshot()!!
        for (bad in listOf("", "{}", "not json", good.replace("\"seats\":2", "\"seats\":3"), good.replace("\"wins\":[0,0]", "\"wins\":[9,0]"), good.replace("\"format\":\"bo3\"", "\"format\":\"zzz\""), good.replace("\"round\":1", "\"round\":0"))) {
            val g = RpsGame(PpClock(), { 1L }); g.init(ppContext(2, 0))
            assertFalse("accepted: $bad", g.restore(bad))
            assertNull(g.engine)
        }
        assertNotEquals("", good)
    }
}
