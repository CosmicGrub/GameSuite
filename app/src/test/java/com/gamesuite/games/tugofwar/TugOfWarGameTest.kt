package com.gamesuite.games.tugofwar

import com.gamesuite.core.GameResult
import com.gamesuite.games.partypack.TapTugClock
import com.gamesuite.games.partypack.TapTugFakeTransport
import com.gamesuite.games.partypack.tapTugContext
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class TugOfWarGameTest {
    private val GO_AT = 1_000L + TugRules.COUNTDOWN_MS

    private class Rig(val game: TugOfWarGame, val clock: TapTugClock) {
        val s get() = game.state.value!!
        fun advance(ms: Long) {
            var left = ms
            while (left > 0) { val d = minOf(20L, left); clock.advance(d); game.tick(); left -= d }
        }
        fun advanceTo(t: Long) = advance(t - clock.now)
        fun taps(seat: Int, n: Int, gap: Long = 50L) { repeat(n) { game.tap(seat); advance(gap) } }
        /** Hold, release (a heave), and a short rest. */
        fun heaves(seat: Int, n: Int, hold: Long = 700L) {
            repeat(n) { game.press(seat, 1); advance(hold); game.release(seat, 1); advance(60) }
        }
    }

    private fun rig(
        layout: TugLayout = TugLayout.TEAMS, humans: Int = 2, bots: Int = 0, rounds: Int = 3, relaxed: Boolean = false,
        hold: Boolean = false, seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): Rig {
        val clock = TapTugClock()
        val g = TugOfWarGame(Random(seed), clock)
        g.layout = layout; g.rounds = rounds; g.relaxed = relaxed; g.holdToPlay = hold; g.difficulty = level
        g.init(tapTugContext(humans, bots))
        g.startMatch()
        return Rig(g, clock)
    }

    private fun Rig.live() { advanceTo(GO_AT + 40) }

    // ---- sides ----

    @Test fun layoutsSplitThePlayersAsPromised() {
        assertEquals(listOf(1, 1), TugSides.sizes(TugLayout.TEAMS, 2))
        assertEquals(listOf(1, 2), TugSides.sizes(TugLayout.TEAMS, 3))
        assertEquals(listOf(2, 2), TugSides.sizes(TugLayout.TEAMS, 4))
        assertEquals(listOf(1, 2), TugSides.sizes(TugLayout.LONE, 3))
        assertEquals(listOf(1, 3), TugSides.sizes(TugLayout.LONE, 4))
        assertEquals(listOf(1, 1, 1), TugSides.sizes(TugLayout.FFA, 3))
        assertEquals(listOf(1, 1, 1, 1), TugSides.sizes(TugLayout.FFA, 4))
        assertEquals(listOf(1, 1), TugSides.sizes(TugLayout.FFA, 2)) // two players are always one against one
        assertEquals("2v2", TugSides.label(TugLayout.TEAMS, 4)); assertEquals("1v3", TugSides.label(TugLayout.LONE, 4))
        assertEquals("1v1", TugSides.label(TugLayout.FFA, 2)); assertEquals("ffa4", TugSides.label(TugLayout.FFA, 4))
    }

    @Test fun everySideIsContiguousAndEveryDirectionIsAUnitVector() {
        for (layout in TugLayout.entries) for (n in 2..4) {
            val of = TugSides.sideOf(layout, n)
            assertEquals(n, of.size)
            assertTrue((1 until n).all { of[it] >= of[it - 1] })
            val dirs = TugSides.directions(layout, n)
            assertEquals(of.max() + 1, dirs.size)
            dirs.forEach { assertEquals(1.0, hypot(it.first, it.second), 1e-9) }
            assertEquals(dirs.size, dirs.toSet().size)
        }
    }

    @Test fun unevenTeamsPullInProportionSoTheTotalsMatch() {
        val b = TugSides.boosts(TugLayout.LONE, 4)
        assertTrue(b[0] > 1.0 && b[1] < 1.0)
        assertEquals(1.0 * b[0], 3 * b[1], 1e-9) // one hard puller equals three softer ones
        assertEquals(listOf(1.0, 1.0), TugSides.boosts(TugLayout.TEAMS, 4))
        assertEquals(listOf(1.0, 1.0, 1.0), TugSides.boosts(TugLayout.FFA, 3))
    }

    // ---- pulling ----

    @Test fun aTapPullsTheMarkerTowardTheTappersSide() {
        val r = rig()
        r.live()
        r.game.tap(0)
        assertTrue(r.s.markerX > 0f)
        assertEquals(0f, r.s.markerY, 1e-6f)
        r.advance(300)
        r.taps(1, 3)
        assertTrue(r.s.markerX < 0.025f * 1f)
        assertEquals(TugPull.TAP, r.s.seats[1].pullKind)
    }

    @Test fun equalPullsFromBothSidesCancelOut() {
        val r = rig()
        r.live()
        repeat(6) { r.game.tap(0); r.game.tap(1); r.advance(60) }
        assertEquals(0f, r.s.markerX, 1e-4f)
    }

    @Test fun pullsBeforeGoAreIgnored() {
        val r = rig()
        r.advance(1000)
        assertEquals(TugReject.NOT_PULLING, r.game.tap(0))
        assertEquals(0f, r.s.markerX, 1e-6f)
    }

    @Test fun aPullThatComesTooSoonAfterTheLastIsRefused() {
        val r = rig()
        r.live()
        assertEquals(TugReject.NONE, r.game.tap(0))
        r.advance(20)
        assertEquals(TugReject.TOO_FAST, r.game.tap(0))
    }

    @Test fun mashingRunsOutOfStaminaAndPullsAtAFifthOfTheStrength() {
        val r = rig()
        r.live()
        r.taps(0, 120, gap = 50) // six seconds at twenty taps a second
        val s = r.s.seats[0]
        assertEquals(0, s.stamina)
        assertTrue(s.tired)
        assertEquals(TugRules.floorOf(false).toFloat(), s.pullPower, 0.02f)
        // full strength would have been 120 * 0.025 = 3.0; the marker (clamped at the goal) shows it fell far short
        assertTrue("round ended or marker ${r.s.markerX}", r.s.phase != "PULL" || r.s.markerX < 1.0f)
    }

    @Test fun mashingBurnsStaminaAtTheTapCost() {
        val r = rig()
        r.live()
        r.taps(0, 5, gap = 60)
        assertEquals(100.0 - 5 * TugRules.TAP_COST, r.s.seats[0].stamina.toDouble(), 3.0)
    }

    @Test fun restingBringsStaminaBack() {
        val r = rig()
        r.live()
        r.taps(0, 10, gap = 50)
        val low = r.s.seats[0].stamina
        r.advance(3000)
        assertTrue(r.s.seats[0].stamina >= low + 20)
    }

    @Test fun holdingBracesAndRestsFasterThanLettingGo() {
        val rested = rig(); rested.live(); rested.taps(0, 12, 50)
        val braced = rig(); braced.live(); braced.taps(0, 12, 50)
        rested.advance(2000)
        braced.game.press(0, 1); braced.advance(2000)
        assertTrue(braced.s.seats[0].braced)
        assertTrue(braced.s.seats[0].stamina > rested.s.seats[0].stamina + 20)
    }

    @Test fun aHeavePullsFarHarderThanATap() {
        val r = rig()
        r.live()
        r.game.press(0, 1); r.advance(700)
        assertEquals(1f, r.s.seats[0].charge, 0.01f)
        val before = r.s.markerX
        r.game.release(0, 1)
        assertEquals(TugPull.HEAVE, r.s.seats[0].pullKind)
        val heave = r.s.markerX - before
        assertEquals(TugRules.UNIT * (1 + TugRules.HEAVE_BONUS) * TugRules.pullPower(100.0, false), heave.toDouble(), 0.004)
        assertTrue(heave > 0.08f)
        assertTrue(heave > 3.5 * TugRules.UNIT)
    }

    @Test fun aShortPressIsJustATapNotAHeave() {
        val r = rig()
        r.live()
        r.game.press(0, 1); r.advance(100); r.game.release(0, 1)
        assertEquals(TugPull.TAP, r.s.seats[0].pullKind)
    }

    @Test fun holdingAndReleasingBeatsMashingOverTime() {
        val r = rig(rounds = 1)
        r.live()
        // seat 0 mashes at 20 a second, seat 1 holds 700 ms and releases, again and again
        var t = 0
        while (t < 10_000 && r.s.phase == "PULL") {
            r.game.tap(0)
            if (t % 800 == 0) r.game.press(1, 1)
            if (t % 800 == 750) r.game.release(1, 1)
            r.advance(50); t += 50
        }
        assertTrue("marker ${r.s.markerX} winner ${r.s.winnerSide}", r.s.markerX < 0f || r.s.winnerSide == 1)
    }

    @Test fun theTalkBackHeaveNeedsTheSameRestAsAHeldOne() {
        val r = rig()
        r.live()
        assertEquals(TugReject.NONE, r.game.heave(0))
        assertEquals(TugPull.HEAVE, r.s.seats[0].pullKind)
        assertEquals(TugReject.TOO_FAST, r.game.heave(0))
        r.advance(TugRules.chargeFullMs(false) + 20)
        assertEquals(TugReject.NONE, r.game.heave(0))
        assertEquals(TugReject.BAD_SEAT, r.game.heave(9))
    }

    @Test fun holdToPlayPullsForAHeldFinger() {
        val r = rig(hold = true)
        r.live()
        r.game.press(0, 1)
        r.advance(2000)
        assertTrue("marker ${r.s.markerX}", r.s.markerX > 0.08f)
        assertEquals(TugPull.AUTO, r.s.seats[0].pullKind)
        val x = r.s.markerX
        r.game.release(0, 1)
        r.advance(1000)
        assertEquals(x, r.s.markerX, 1e-6f) // a release is not a heave when holding is the way to play
    }

    @Test fun relaxedTiresYouLessAndHeavesChargeSooner() {
        assertTrue(TugRules.costOf(true) < TugRules.costOf(false))
        assertTrue(TugRules.floorOf(true) > TugRules.floorOf(false))
        assertTrue(TugRules.chargeFullMs(true) < TugRules.chargeFullMs(false))
        assertTrue(TugRules.roundLimitMs(true) > TugRules.roundLimitMs(false))
        assertEquals(0.0, TugRules.charge(100, false), 1e-9)
        assertEquals(1.0, TugRules.charge(450, true), 1e-9)
        assertTrue(TugRules.charge(450, false) < 1.0)
    }

    @Test fun aFingerLiftingNeverCancelsAnotherFingersHold() {
        val r = rig()
        r.live()
        r.game.press(0, 1); r.advance(100); r.game.press(0, 2); r.game.release(0, 2)
        assertTrue(r.s.seats[0].held)
        r.advance(700)
        assertTrue(r.s.seats[0].braced)
    }

    // ---- rounds ----

    @Test fun pullingTheMarkerOverTheLineWinsTheRound() {
        val r = rig(rounds = 3)
        r.live()
        var n = 0
        while (r.s.phase == "PULL" && n++ < 200) r.heaves(0, 1)
        assertEquals("ROUND_END", r.s.phase)
        assertEquals(0, r.s.roundWinnerSide)
        assertEquals(listOf(1, 0), r.s.sideWins)
        assertEquals(TugEvent.ROUND_WIN, r.s.event)
        assertTrue(r.s.advance[0] >= TugRules.GOAL)
    }

    @Test fun roundsRunToABestOfThreeForATeam() {
        val r = rig(TugLayout.TEAMS, humans = 4, rounds = 3)
        for (round in 1..2) {
            while (r.s.phase != "PULL") r.advance(20)
            var n = 0
            while (r.s.phase == "PULL" && n++ < 200) r.heaves(0, 1)
        }
        assertTrue(r.s.over)
        assertEquals(0, r.s.winnerSide)
        assertEquals(listOf(0, 1), r.s.winnerSeats)
        assertEquals(1, r.s.sessionWins["p1"]); assertEquals(1, r.s.sessionWins["p2"]); assertEquals(0, r.s.sessionWins["p3"])
        assertEquals(TugEvent.MATCH_WIN, r.s.event)
    }

    @Test fun anIdleRoundIsADrawAndAMatchOfDrawsIsADraw() {
        val r = rig(rounds = 1)
        var guard = 0
        while (!r.s.over && guard++ < 20_000) r.advance(1000)
        assertTrue(r.s.over)
        assertEquals(-1, r.s.winnerSide)
        assertEquals(TugEvent.MATCH_DRAW, r.s.event)
    }

    @Test fun theSideAheadWinsWhenTimeRunsOut() {
        val r = rig(rounds = 1)
        r.live()
        r.taps(1, 4)
        r.advance(TugRules.ROUND_LIMIT_MS)
        assertTrue(r.s.over)
        assertEquals(1, r.s.winnerSide)
    }

    @Test fun relaxedRoundsLastLonger() {
        val r = rig(relaxed = true, rounds = 1)
        r.live()
        r.taps(0, 2)
        r.advance(TugRules.ROUND_LIMIT_MS)
        assertFalse(r.s.over)
        r.advance(TugRules.RELAXED_ROUND_LIMIT_MS - TugRules.ROUND_LIMIT_MS + 200)
        assertTrue(r.s.over)
    }

    @Test fun inAFreeForAllTheMarkerGoesTowardTheCornerOfTheSeatThatPulls() {
        val r = rig(TugLayout.FFA, humans = 4)
        r.live()
        r.game.tap(2)
        val d = TugSides.directions(TugLayout.FFA, 4)[2]
        assertTrue(r.s.markerX * d.first + r.s.markerY * d.second > 0)
        assertEquals(d.first.toFloat() * 0.025f, r.s.markerX, 1e-4f)
        assertEquals(4, r.s.sides)
    }

    @Test fun inAFreeForAllTheFirstToTheirCornerWins() {
        val r = rig(TugLayout.FFA, humans = 3, rounds = 1)
        r.live()
        var n = 0
        while (r.s.phase == "PULL" && n++ < 300) r.heaves(1, 1)
        assertTrue(r.s.over)
        assertEquals(1, r.s.winnerSide)
        assertEquals(listOf(1), r.s.winnerSeats)
    }

    @Test fun theMarkerNeverLeavesTheArena() {
        val r = rig(TugLayout.TEAMS, humans = 2, rounds = 1)
        r.live()
        repeat(2000) { r.game.heave(0); r.advance(25) }
        assertTrue(hypot(r.s.markerX, r.s.markerY) <= TugRules.MAX_RADIUS + 1e-3)
    }

    // ---- computer pullers ----

    @Test fun aComputerPullerWinsAnIdleRound() {
        val r = rig(humans = 1, bots = 1, rounds = 1)
        var guard = 0
        while (!r.s.over && guard++ < 6000) r.advance(20)
        assertTrue(r.s.over)
        assertEquals(1, r.s.winnerSide)
    }

    @Test fun aComputerNeverPressesFasterThanAPersonCan() {
        for (level in CpuDifficulty.entries) {
            val r = rig(humans = 1, bots = 1, rounds = 1, level = level)
            r.advanceTo(GO_AT)
            var presses = 0
            var last = r.s.seats[1].pullSerial
            repeat(250) { r.advance(20); val n = r.s.seats[1].pullSerial; if (n != last) { presses += n - last; last = n } }
            assertTrue("$level pulls ${presses} in 5 s", presses <= 5 * 10)
        }
    }

    private fun botDuel(strong: CpuDifficulty, weak: CpuDifficulty, seeds: Int): Int {
        var strongWins = 0
        for (seed in 1..seeds) {
            val clock = TapTugClock()
            val g = TugOfWarGame(Random(seed), clock)
            g.rounds = 1
            g.init(tapTugContext(0, 2))
            g.seatLevelOverride[0] = strong; g.seatLevelOverride[1] = weak
            g.startMatch()
            var guard = 0
            while (!g.state.value!!.over && guard++ < 40_000) { clock.advance(20); g.tick() }
            if (g.state.value!!.winnerSide == 0) strongWins++
        }
        return strongWins
    }

    @Test fun computerLevelsAreClearlyOrderedButNotHopeless() {
        val seeds = 60
        val hardEasy = botDuel(CpuDifficulty.HARD, CpuDifficulty.EASY, seeds)
        val hardMedium = botDuel(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, seeds)
        val mediumEasy = botDuel(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, seeds)
        println("TugOfWar duels: hard>easy $hardEasy, hard>medium $hardMedium, medium>easy $mediumEasy of $seeds")
        assertTrue("hard should beat easy clearly: $hardEasy", hardEasy > seeds * 0.7)
        assertTrue("hard should beat medium: $hardMedium", hardMedium > seeds * 0.55)
        assertTrue("medium should beat easy: $mediumEasy", mediumEasy > seeds * 0.55)
        assertTrue("hard over easy ($hardEasy) should beat hard over medium ($hardMedium)", hardEasy >= hardMedium)
        assertTrue("easy is not hopeless: $hardEasy of $seeds", hardEasy < seeds)
    }

    // ---- sessions, results, resume ----

    @Test fun leavingReportsTheSideThatLeadsTheSession() {
        val r = rig(TugLayout.TEAMS, humans = 4, rounds = 1)
        var result: GameResult? = null
        r.game.setOnMatchEnd { result = it }
        r.live()
        var n = 0
        while (r.s.phase == "PULL" && n++ < 200) r.heaves(2, 1)
        assertTrue(r.s.over)
        r.game.leaveSession()
        assertEquals(listOf(false, false, true, true), result!!.scores.map { it.isWinner })
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
        r.live()
        var n = 0
        while (r.s.phase == "PULL" && n++ < 200) r.heaves(0, 1)
        r.game.playAgain()
        assertEquals("COUNTDOWN", r.s.phase)
        assertEquals(1, r.s.sessionWins["p1"])
        assertEquals(listOf(0, 0), r.s.sideWins)
        assertEquals(0f, r.s.markerX, 1e-6f)
    }

    private fun midMatch(layout: TugLayout = TugLayout.TEAMS): Rig {
        val r = rig(layout, humans = 1, bots = 3, rounds = 3, relaxed = true, level = CpuDifficulty.HARD)
        var guard = 0
        while (r.s.round == 1 && guard++ < 20_000) r.advance(20)
        return r
    }

    @Test fun snapshotAndRestoreRoundTripBetweenRoundsAndRestartTheRound() {
        for (layout in TugLayout.entries) {
            val r = midMatch(layout)
            val snap = r.game.snapshot()
            assertNotNull(snap)
            val g2 = TugOfWarGame(Random(5), TapTugClock())
            g2.init(tapTugContext(1, 3))
            assertTrue(g2.restore(snap!!))
            val s2 = g2.state.value!!
            assertEquals(r.s.round, s2.round)
            assertEquals(r.s.sideWins, s2.sideWins)
            assertEquals("COUNTDOWN", s2.phase)
            assertEquals(r.s.layout, s2.layout)
            assertTrue(s2.relaxed)
            assertEquals(0f, s2.markerX, 1e-6f)
            assertTrue(s2.seats.all { it.stamina == 100 })
        }
    }

    @Test fun restoreRefusesAnythingThatDoesNotFit() {
        val r = midMatch()
        val good = r.game.snapshot()!!
        fun fresh(humans: Int = 1, bots: Int = 3) = TugOfWarGame(Random(1), TapTugClock()).also { it.init(tapTugContext(humans, bots)) }
        assertFalse(fresh().restore("nope"))
        assertFalse(fresh().restore("{}"))
        assertFalse(fresh(2, 2).restore(good))
        assertFalse(fresh(1, 1).restore(good))
        val tampered = listOf(
            good.replace("\"layout\":\"teams\"", "\"layout\":\"pyramid\""),
            good.replace("\"rounds\":3", "\"rounds\":4"),
            good.replace("\"played\":1", "\"played\":8"),
            good.replace("\"wins\":[", "\"wins\":[7,"),
            good.replace("\"difficulty\":\"HARD\"", "\"difficulty\":\"X\"")
        )
        for (t in tampered) {
            val g = fresh()
            assertFalse("accepted: $t", g.restore(t))
            assertNull(g.state.value)
        }
    }

    @Test fun noSnapshotBeforeStartOrAfterTheMatchIsOver() {
        val g = TugOfWarGame(Random(1), TapTugClock())
        g.init(tapTugContext(2, 0))
        assertNull(g.snapshot())
        val r = rig(rounds = 1)
        r.live()
        var n = 0
        while (r.s.phase == "PULL" && n++ < 200) r.heaves(0, 1)
        assertTrue(r.s.over)
        assertNull(r.game.snapshot())
    }

    @Test fun theGameIsSameScreenOnlyAndNeverSendsAMessage() {
        val transport = TapTugFakeTransport()
        val clock = TapTugClock()
        val g = TugOfWarGame(Random(1), clock)
        g.init(tapTugContext(2, 0, transport))
        g.startMatch()
        repeat(300) { clock.advance(20); g.tick(); g.tap(0); g.heave(1) }
        assertEquals(0, transport.sends)
        assertEquals(
            listOf(com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, com.gamesuite.core.PlayMode.SINGLE_PLAYER_VS_BOT),
            g.supportedModes
        )
    }

    // ---- fuzz ----

    @Test fun randomPlayAlwaysTerminatesAndKeepsItsInvariants() {
        for (seed in 1..60) {
            val random = Random(seed)
            val layout = TugLayout.entries[random.nextInt(3)]
            val humans = 1 + random.nextInt(3)
            val bots = if (humans == 1) 1 + random.nextInt(3) else random.nextInt(5 - humans)
            val seats = humans + bots
            val r = rig(layout, humans = humans, bots = bots, rounds = listOf(1, 3, 5)[random.nextInt(3)],
                relaxed = random.nextBoolean(), hold = random.nextBoolean(), seed = seed,
                level = CpuDifficulty.entries[random.nextInt(3)])
            var steps = 0
            while (!r.s.over && steps++ < 80_000) {
                r.clock.advance(1L + random.nextInt(40)); r.game.tick()
                if (random.nextInt(3) == 0) {
                    val seat = random.nextInt(seats)
                    when (random.nextInt(5)) {
                        0 -> r.game.tap(seat)
                        1 -> r.game.press(seat, random.nextInt(3).toLong())
                        2 -> r.game.release(seat, random.nextInt(3).toLong())
                        3 -> r.game.heave(seat)
                        else -> r.game.press(seat, 7)
                    }
                }
                val s = r.s
                assertEquals(seats, s.seats.size)
                assertTrue(s.seats.all { it.stamina in 0..100 })
                assertTrue(hypot(s.markerX, s.markerY) <= TugRules.MAX_RADIUS + 1e-3)
                assertTrue(s.round <= s.rounds + 4)
                if (steps % 500 == 0) r.game.snapshot()?.let { snap ->
                    val g2 = TugOfWarGame(Random(seed), TapTugClock())
                    g2.init(tapTugContext(humans, bots))
                    assertTrue("snapshot did not restore: $snap", g2.restore(snap))
                    assertEquals(r.s.sideWins, g2.state.value!!.sideWins)
                }
            }
            assertTrue("seed $seed never ended (layout $layout)", r.s.over)
            assertTrue(abs(r.s.markerX) <= TugRules.MAX_RADIUS + 1e-3)
        }
    }
}
