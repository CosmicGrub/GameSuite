package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PaddleDuelEngineTest {
    private fun humans(n: Int) = List(n) { PdSeat(bot = false) }
    private fun cfg(n: Int = 2, format: PdFormat = PdFormat.DEFAULT, powerups: Boolean = false, relaxed: Boolean = false, seed: Long = 1L, seats: List<PdSeat> = humans(n)) =
        PdConfig(seats, format, powerups, relaxed, seed)

    /** Skips the serve and puts the ball where a test wants it. */
    private fun play(e: PaddleDuelEngine, x: Double, y: Double, vx: Double, vy: Double) {
        while (e.phase == PdPhase.SERVE) e.step()
        e.drainEvents()
        val b = e.balls.first()
        b.x = x; b.y = y; b.vx = vx; b.vy = vy; b.spin = 0.0
    }

    private fun steps(e: PaddleDuelEngine, n: Int) { repeat(n) { e.step() } }

    @Test fun theBallIsServedAfterTheCountdownAndTheCountdownTicksThreeTwoOne() {
        val e = PaddleDuelEngine(cfg())
        assertEquals(PdPhase.SERVE, e.phase)
        val ticks = mutableListOf<Int>()
        while (e.phase == PdPhase.SERVE) { e.step(); e.drainEvents().filter { it.kind == PdEventKind.SERVE_TICK }.forEach { ticks += it.a } }
        assertEquals(listOf(3, 2, 1), ticks)
        assertEquals(PdPhase.PLAY, e.phase)
        assertTrue(e.balls.first().speed > 0.0)
    }

    @Test fun aBallComingDownIsReturnedByTheBottomPaddleAndSpeedsUpPerRally() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.5, 1.2, 0.0, 0.8)
        e.paddlePos[0] = 0.5
        steps(e, 120)
        assertEquals(1, e.rally)
        assertEquals(0, e.lastHitter)
        val b = e.balls.first()
        assertTrue("returned upwards", b.vy < 0)
        val first = b.speed
        e.paddlePos[1] = 0.5
        steps(e, 400)
        assertTrue("rally ${e.rally}", e.rally >= 2)
        assertTrue(e.balls.first().speed > first)
    }

    @Test fun rallySpeedIsCapped() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.5, 1.2, 0.0, 0.8)
        repeat(60) {
            val bx = e.balls.firstOrNull()?.x ?: 0.5
            e.paddlePos[0] = bx; e.paddlePos[1] = bx
            steps(e, 60)
        }
        for (b in e.balls) assertTrue(b.speed <= PdRules.BASE_SPEED * PdRules.MAX_SPEED_FACTOR + 1e-9)
    }

    @Test fun hittingTheEndOfThePaddleAnglesTheBallThatWay() {
        val right = PaddleDuelEngine(cfg())
        play(right, 0.58, 1.2, 0.0, 0.8); right.paddlePos[0] = 0.5
        steps(right, 45)
        assertTrue("hit on the +x half goes +x: ${right.balls.first().vx}", right.balls.first().vx > 0.1)
        val left = PaddleDuelEngine(cfg())
        play(left, 0.42, 1.2, 0.0, 0.8); left.paddlePos[0] = 0.5
        steps(left, 45)
        assertTrue(left.balls.first().vx < -0.1)
    }

    @Test fun aMovingPaddleGivesTheBallSpinAndAStationaryOneDoesNot() {
        val still = PaddleDuelEngine(cfg())
        play(still, 0.5, 1.2, 0.0, 0.8); still.paddlePos[0] = 0.5
        steps(still, 120)
        assertEquals(0.0, still.balls.first().spin, 1e-9)
        val moving = PaddleDuelEngine(cfg())
        play(moving, 0.7, 1.2, 0.0, 0.8); moving.paddlePos[0] = 0.2
        moving.setTarget(0, 0.9)
        steps(moving, 42)
        assertEquals(1, moving.rally)
        assertTrue("spin ${moving.balls.first().spin}", abs(moving.balls.first().spin) > 0.02)
    }

    @Test fun aMissedBallScoresForTheOpponentAndTheNextServeGoesToTheConceder() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.1, 1.2, 0.0, 0.8); e.paddlePos[0] = 0.9
        steps(e, 100)
        assertEquals(listOf(0, 1), e.score.toList())
        assertEquals(PdPhase.SERVE, e.phase)
        val goal = e.drainEvents().first { it.kind == PdEventKind.GOAL }
        assertEquals(0, goal.seat); assertEquals(1, goal.a)
        while (e.phase == PdPhase.SERVE) e.step()
        assertTrue("served toward the bottom seat: vy=${e.balls.first().vy}", e.balls.first().vy > 0)
    }

    @Test fun sideWallsReflectInATwoPlayerGame() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.05, 0.75, -0.5, 0.0)
        steps(e, 30)
        assertTrue(e.balls.first().vx > 0)
        assertTrue(e.balls.first().x >= PdRules.BALL_R)
    }

    @Test fun firstToTheTargetWinsAPointsMatch() {
        val e = PaddleDuelEngine(cfg(format = PdFormat(true, 5)))
        repeat(5) {
            play(e, 0.1, 1.2, 0.0, 0.8); e.paddlePos[0] = 0.9
            steps(e, 200)
        }
        assertTrue(e.over); assertEquals(1, e.winner); assertEquals(5, e.score[1])
        assertTrue(e.drainEvents().any { it.kind == PdEventKind.WIN })
    }

    @Test fun livesFormatEliminatesAtZeroTurnsTheEdgeIntoAWallAndTheLastSeatStandingWins() {
        val e = PaddleDuelEngine(cfg(n = 4, format = PdFormat(false, 3)))
        // fires a ball straight at seat [seat]'s edge, with its paddle parked away from the ball
        fun concede(seat: Int) {
            e.paddlePos[seat] = 0.85
            when (e.edges[seat]) {
                PpEdge.BOTTOM -> play(e, 0.3, 0.7, 0.0, 0.8)
                PpEdge.TOP -> play(e, 0.3, 0.3, 0.0, -0.8)
                PpEdge.LEFT -> play(e, 0.3, 0.3, -0.8, 0.0)
                PpEdge.RIGHT -> play(e, 0.7, 0.3, 0.8, 0.0)
            }
            steps(e, 100) // the goal falls at about 45 steps; the next serve is still counting down
        }
        repeat(3) { concede(1) } // TOP
        assertEquals(0, e.lives[1]); assertFalse(e.alive[1])
        assertEquals(3, e.aliveCount)
        // the old top edge is a wall now
        play(e, 0.5, 0.2, 0.0, -0.8)
        steps(e, 120)
        assertTrue("bounced off the new wall", e.balls.first().vy > 0 && e.aliveCount == 3)
        repeat(3) { concede(2) }
        repeat(3) { concede(3) }
        assertTrue(e.over); assertEquals(0, e.winner)
    }

    @Test fun cornersAreSolidWithFourPlayers() {
        val e = PaddleDuelEngine(cfg(n = 4, format = PdFormat(false, 5)))
        play(e, 0.07, 0.2, 0.0, -0.8)
        steps(e, 60)
        assertTrue("reflected, no goal: ${e.balls.first().vy}", e.balls.first().vy > 0 && e.lives.all { it == 5 })
    }

    @Test fun relaxedAssistIsSlowerAndWider() {
        val normal = PaddleDuelEngine(cfg()); val relaxed = PaddleDuelEngine(cfg(relaxed = true))
        while (normal.phase == PdPhase.SERVE) normal.step()
        while (relaxed.phase == PdPhase.SERVE) relaxed.step()
        assertTrue(relaxed.balls.first().speed < normal.balls.first().speed * 0.7)
        assertTrue(relaxed.paddleLength(0) > normal.paddleLength(0))
    }

    @Test fun staleRalliesAreReservedWithoutAPoint() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.5, 0.75, 0.0, 0.0)
        steps(e, (PdRules.STALE_S / PdRules.DT).toInt() + 20)
        assertEquals(listOf(0, 0), e.score.toList())
        assertEquals(PdPhase.SERVE, e.phase)
    }

    @Test fun identicalSeedsAndInputsReplayExactly() {
        fun run(): List<Double> {
            val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(true, CpuDifficulty.HARD), PdSeat(true, CpuDifficulty.EASY)), powerups = true, seed = 99))
            steps(e, 20_000)
            return listOf(e.balls.sumOf { it.x + it.y * 3 }, e.paddlePos[0], e.paddlePos[1], e.score[0].toDouble(), e.score[1].toDouble(), e.simTime)
        }
        assertEquals(run(), run())
    }

    @Test fun humanTargetMovesThePaddleAtALimitedSpeedAndButtonsMoveItToo() {
        val e = PaddleDuelEngine(cfg())
        e.paddlePos[0] = 0.2
        e.setTarget(0, 0.95)
        steps(e, 60)
        assertTrue("limited: ${e.paddlePos[0]}", e.paddlePos[0] in 0.5..0.9)
        e.setTarget(0, null)
        val before = e.paddlePos[0]
        e.setHold(0, -1)
        steps(e, 60)
        assertTrue(e.paddlePos[0] < before - 0.5)
        e.setHold(0, 0)
        val rest = e.paddlePos[0]
        e.nudge(0, 1)
        steps(e, 120)
        assertTrue(e.paddlePos[0] > rest)
    }

    @Test fun botsIgnoreHumanInput() {
        val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(false), PdSeat(true, CpuDifficulty.EASY))))
        e.setTarget(1, 0.9); e.setHold(1, 1); e.nudge(1, 1)
        steps(e, 5)
        assertTrue(abs(e.paddlePos[1] - 0.5) < 0.05)
    }

    @Test fun oneSwitchSeatCyclesTheAimAndFollowsTheBall() {
        val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(false, oneSwitch = true), PdSeat(true, CpuDifficulty.HARD))))
        assertEquals(0, e.aimOf(0)); e.pressSwitch(0); assertEquals(1, e.aimOf(0)); e.pressSwitch(0); assertEquals(-1, e.aimOf(0)); e.pressSwitch(0); assertEquals(0, e.aimOf(0))
        play(e, 0.2, 0.9, 0.3, 0.8)
        e.paddlePos[0] = 0.8
        steps(e, 200)
        assertTrue("the auto paddle returned the ball", e.rally >= 1)
    }

    @Test fun powerUpsSpawnAndEveryKindGetsTakenAcrossSeeds() {
        val taken = mutableSetOf<Int>()
        var spawned = 0
        for (seed in 1L..40L) {
            val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(true, CpuDifficulty.HARD), PdSeat(true, CpuDifficulty.HARD)), powerups = true, seed = seed, format = PdFormat(true, 11)))
            repeat(60_000) {
                e.step()
                if (it % 12 == 0) e.drainEvents().forEach { ev ->
                    if (ev.kind == PdEventKind.PICKUP_SPAWN) spawned++
                    if (ev.kind == PdEventKind.PICKUP_TAKEN) taken += ev.a
                }
            }
        }
        assertTrue(spawned > 20)
        assertEquals(PdPowerKind.entries.map { it.ordinal }.toSet(), taken)
    }

    @Test fun powerUpEffectsApply() {
        val seen = mutableSetOf<PdPowerKind>()
        for (seed in 1L..80L) {
            val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(true, CpuDifficulty.HARD), PdSeat(true, CpuDifficulty.HARD)), powerups = true, seed = seed, format = PdFormat(true, 11)))
            var i = 0
            while (i++ < 60_000 && seen.size < 3 && !e.over) {
                val before = e.balls.size
                e.step()
                val ev = e.drainEvents().firstOrNull { it.kind == PdEventKind.PICKUP_TAKEN } ?: continue
                val kind = PdPowerKind.entries[ev.a]
                when (kind) {
                    PdPowerKind.WIDE -> assertEquals(PdRules.PAD_LEN * PdRules.WIDE_FACTOR, e.paddleLength(ev.seat), 1e-9)
                    PdPowerKind.MULTI -> assertEquals(before + 1, e.balls.size)
                    PdPowerKind.SLOW -> {}
                }
                seen += kind
            }
            if (seen.size == 3) break
        }
        assertEquals(3, seen.size)
    }

    @Test fun aStalledFrameCannotTeleportTheBall() {
        val e = PaddleDuelEngine(cfg())
        play(e, 0.5, 0.75, 0.3, 0.8)
        e.advance(5.0)
        assertTrue("clamped to a quarter second: ${e.simTime}", e.simTime < 0.3 + 1.2 + 0.3)
    }

    // ---- CPU levels ------------------------------------------------------------------------

    /** Level [a] against level [b], alternating sides; a's win fraction over [games] first-to-5 matches. */
    private fun duel(a: CpuDifficulty, b: CpuDifficulty, games: Int): Double {
        var aWins = 0
        for (g in 0 until games) {
            val aFirst = g % 2 == 0
            val seats = if (aFirst) listOf(PdSeat(true, a), PdSeat(true, b)) else listOf(PdSeat(true, b), PdSeat(true, a))
            val e = PaddleDuelEngine(cfg(seats = seats, format = PdFormat(true, 5), seed = 1000L + g))
            var guard = 0
            while (!e.over && guard++ < 600_000) e.step()
            assertTrue("match finishes", e.over)
            if ((e.winner == 0) == aFirst) aWins++
        }
        return aWins.toDouble() / games
    }

    @Test fun cpuLevelsAreClearlyOrderedAndHardIsNotPerfect() {
        val hardVsEasy = duel(CpuDifficulty.HARD, CpuDifficulty.EASY, 30)
        val hardVsMed = duel(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 30)
        val medVsEasy = duel(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 30)
        println("PD levels: hardVsEasy=$hardVsEasy hardVsMed=$hardVsMed medVsEasy=$medVsEasy")
        assertTrue("hard beats easy: $hardVsEasy", hardVsEasy >= 0.9)
        assertTrue("hard clearly beats medium but not every time: $hardVsMed", hardVsMed in 0.55..0.97)
        assertTrue("medium beats easy: $medVsEasy", medVsEasy >= 0.9)
        assertTrue("a level beats itself half the time: ${duel(CpuDifficulty.MEDIUM, CpuDifficulty.MEDIUM, 20)}", duel(CpuDifficulty.MEDIUM, CpuDifficulty.MEDIUM, 20) in 0.2..0.8)
    }

    @Test fun easyIsABeatableOpponentThatStillScores() {
        var easyPoints = 0
        var matches = 0
        for (g in 0 until 10) {
            val e = PaddleDuelEngine(cfg(seats = listOf(PdSeat(true, CpuDifficulty.EASY), PdSeat(true, CpuDifficulty.EASY)), format = PdFormat(true, 5), seed = 40L + g))
            var guard = 0
            while (!e.over && guard++ < 600_000) e.step()
            assertTrue(e.over); matches++
            easyPoints += e.score.sum()
        }
        assertTrue("Easy bots score against each other: $easyPoints in $matches matches", easyPoints >= matches * 5)
    }

    // ---- fuzz -------------------------------------------------------------------------------

    @Test fun fuzzRandomMatchesStayInBoundsAndTerminate() {
        val rnd = Random(7)
        repeat(60) { run ->
            val n = 2 + rnd.nextInt(3)
            val format = PdFormat.ALL[rnd.nextInt(PdFormat.ALL.size)]
            val seats = List(n) { if (rnd.nextInt(3) == 0) PdSeat(false) else PdSeat(true, CpuDifficulty.entries[rnd.nextInt(3)]) }
            val e = PaddleDuelEngine(cfg(seats = seats, format = format, powerups = rnd.nextBoolean(), relaxed = rnd.nextBoolean(), seed = run.toLong()))
            var guard = 0
            while (!e.over && guard++ < 1_500_000) {
                if (guard % 7 == 0) {
                    val s = rnd.nextInt(n)
                    when (rnd.nextInt(4)) { 0 -> e.setTarget(s, rnd.nextDouble() * 2 - 0.5); 1 -> e.setHold(s, rnd.nextInt(3) - 1); 2 -> e.nudge(s, rnd.nextInt(3) - 1); else -> e.pressSwitch(s) }
                }
                e.step()
                for (b in e.balls) {
                    assertFalse("NaN", b.x.isNaN() || b.y.isNaN() || b.vx.isNaN() || b.vy.isNaN())
                    assertTrue("ball out of bounds ${b.x},${b.y}", b.x in -0.1..e.width + 0.1 && b.y in -0.1..e.height + 0.1)
                    assertTrue("speed ${b.speed}", b.speed <= PdRules.BASE_SPEED * PdRules.MAX_SPEED_FACTOR * 1.6)
                }
                for (s in 0 until n) assertTrue(e.paddlePos[s] in 0.0..e.extent(s))
                if (guard % 5000 == 0) e.drainEvents()
            }
            assertTrue("run $run n=$n $format did not end", e.over)
            assertTrue(e.winner in 0 until n)
        }
    }
}
