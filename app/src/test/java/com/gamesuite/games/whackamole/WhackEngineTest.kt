package com.gamesuite.games.whackamole

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scoring, the shared seeded schedule and the computer's levels of Whack-a-Mole Race. No sleeps: the engine is told the time. */
class WhackEngineTest {
    private fun cfg(
        seats: Int = 2, durationMs: Int = 60_000, grid: Int = 3, relaxed: Boolean = false, oneSwitch: Boolean = false,
        seed: Long = 1L, cpu: Set<Int> = emptySet(), level: CpuDifficulty = CpuDifficulty.MEDIUM
    ) = WhackConfig(seats, durationMs, grid, relaxed, oneSwitch, seed, cpu, level)

    /** An engine already in play (the countdown skipped), with play time 0 at engine time 0. */
    private fun playing(c: WhackConfig): WhackEngine = WhackEngine(c).also {
        it.startMatch(-WhackRules.COUNTDOWN_MS)
        it.tick(0)
        assertEquals(WhackPhase.PLAY, it.phase)
    }

    private fun hit(e: WhackEngine, seat: Int, sp: WhackSpawn, offset: Int = 20): WhackResult =
        e.whack(seat, sp.hole, sp.startMs + offset.toLong(), sp.startMs + offset.toLong())

    // ---- the schedule ----

    @Test fun theScheduleIsAPureFunctionOfTheSeedAndSettings() {
        val a = WhackRules.generate(5, 3, 60_000, 1.0); val b = WhackRules.generate(5, 3, 60_000, 1.0)
        assertEquals(a, b)
        assertTrue(a != WhackRules.generate(6, 3, 60_000, 1.0))
        assertTrue(a.size > 40)
    }

    @Test fun everyZoneAndEveryEngineOfTheSameSeedFollowsTheSameCritterList() {
        val two = WhackEngine(cfg(seats = 2, seed = 42))
        val four = WhackEngine(cfg(seats = 4, seed = 42, cpu = setOf(1, 2, 3), level = CpuDifficulty.HARD))
        assertEquals(two.spawns, four.spawns)
        // And within one engine there is only one list: nothing is per seat.
        assertTrue(WhackEngine(cfg(seed = 43)).spawns != two.spawns)
    }

    @Test fun spawnsAreWellFormedAndNoHoleHostsTwoCrittersAtOnce() {
        for (seed in 0L until 60L) for (grid in listOf(3, 4)) for (pace in listOf(1.0, WhackRules.RELAXED_PACE, WhackRules.SWITCH_PACE)) {
            val list = WhackRules.generate(seed, grid, 90_000, pace)
            var prevStart = -1
            for (sp in list) {
                assertTrue(sp.hole in 0 until grid * grid)
                assertTrue(sp.startMs >= prevStart); prevStart = sp.startMs
                assertTrue(sp.lifeMs >= 500)
                assertTrue(sp.startMs < 90_000)
            }
            for (h in 0 until grid * grid) {
                val onHole = list.filter { it.hole == h }
                for (i in 1 until onHole.size) assertTrue("seed $seed hole $h overlaps", onHole[i].startMs >= onHole[i - 1].endMs)
            }
            assertEquals(list.indices.toList(), list.map { it.id })
        }
    }

    @Test fun crittersComeFasterAndStayShorterAsTheRoundGoesOn() {
        var early = 0.0; var late = 0.0; var nEarly = 0; var nLate = 0
        for (seed in 0L until 40L) {
            val list = WhackRules.generate(seed, 3, 90_000, 1.0).filter { it.critter == Critter.MOLE }
            list.filter { it.startMs < 20_000 }.forEach { early += it.lifeMs; nEarly++ }
            list.filter { it.startMs > 70_000 }.forEach { late += it.lifeMs; nLate++ }
        }
        assertTrue(early / nEarly > late / nLate + 100)
        val list = WhackRules.generate(1, 3, 90_000, 1.0)
        assertTrue(list.count { it.startMs < 30_000 } < list.count { it.startMs >= 60_000 })
    }

    @Test fun theMixHasMolesBonusesAndDecoysInTheirIntendedShares() {
        val all = (0L until 40L).flatMap { WhackRules.generate(it, 3, 90_000, 1.0) }
        val total = all.size.toDouble()
        assertEquals(WhackRules.MOLE_BONUS_SHARE, all.count { it.critter == Critter.GOLD } / total, 0.03)
        assertEquals(WhackRules.DECOY_SHARE, all.count { it.critter == Critter.DECOY } / total, 0.04)
        assertTrue(all.count { it.critter == Critter.MOLE } / total > 0.6)
    }

    @Test fun relaxedAndOneSwitchSlowTheCrittersAndKeepThemUpLonger() {
        val normal = WhackRules.generate(3, 3, 90_000, 1.0); val relaxed = WhackRules.generate(3, 3, 90_000, WhackRules.RELAXED_PACE); val switch = WhackRules.generate(3, 3, 90_000, WhackRules.SWITCH_PACE)
        assertTrue(relaxed.size < normal.size && switch.size < relaxed.size)
        assertTrue(relaxed.map { it.lifeMs }.average() > normal.map { it.lifeMs }.average() * 1.3)
        assertTrue(switch.map { it.lifeMs }.average() > relaxed.map { it.lifeMs }.average())
        assertEquals(WhackRules.RELAXED_PACE, cfg(relaxed = true).pace, 0.0)
        assertEquals(WhackRules.SWITCH_PACE, cfg(oneSwitch = true, relaxed = true).pace, 0.0)
    }

    @Test fun aFourByFourGridHasMoreCrittersAtOnce() {
        assertTrue(WhackRules.generate(2, 4, 90_000, 1.0).size > WhackRules.generate(2, 3, 90_000, 1.0).size)
    }

    // ---- scoring ----

    private fun firstOf(e: WhackEngine, c: Critter) = e.spawns.first { it.critter == c }

    @Test fun aMoleScoresTenAGoldenBonusThirtyAndEachOnlyOnce() {
        val e = playing(cfg())
        val mole = firstOf(e, Critter.MOLE)
        val r = hit(e, 0, mole)
        assertEquals(WhackKind.HIT, r.kind); assertEquals(10, r.delta); assertEquals(10, e.score[0])
        assertEquals(1, e.hits[0]); assertEquals(1, e.combo[0])
        // The same critter is gone for this player...
        val again = e.whack(0, mole.hole, mole.startMs + 400L, mole.startMs + 400L)
        assertEquals(WhackKind.MISS, again.kind)
        // ...but the other zone's critter is still there: the zones are independent.
        assertEquals(WhackKind.HIT, hit(e, 1, mole).kind)
        assertEquals(10, e.score[1])
    }

    @Test fun theGoldenGopherIsAThirtyPointBonus() {
        val e = playing(cfg())
        val gold = firstOf(e, Critter.GOLD)
        val r = hit(e, 0, gold)
        assertEquals(WhackKind.BONUS, r.kind); assertEquals(30, r.delta); assertEquals(1, e.bonuses[0])
    }

    /** Whacks real critters in a row from the start of the schedule (decoys are left alone) and returns the deltas. */
    private fun chain(e: WhackEngine, seat: Int, count: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (sp in e.spawns.filter { it.critter != Critter.DECOY }) {
            if (out.size == count) break
            val r = hit(e, seat, sp)
            assertTrue(r.kind == WhackKind.HIT || r.kind == WhackKind.BONUS)
            out += r.delta / sp.critter.points
        }
        return out
    }

    @Test fun theStreakMultiplierIsTwoFromFiveAndThreeFromTen() {
        val e = playing(cfg())
        val mult = chain(e, 0, 12)
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3), mult)
        assertEquals(12, e.combo[0]); assertEquals(12, e.bestCombo[0])
        assertEquals(1, WhackRules.comboMultiplier(0)); assertEquals(2, WhackRules.comboMultiplier(5)); assertEquals(3, WhackRules.comboMultiplier(10)); assertEquals(3, WhackRules.comboMultiplier(99))
    }

    @Test fun aDecoyCostsTwentyAndBreaksTheStreakButNeverGoesBelowZero() {
        val e = playing(cfg())
        val decoy = firstOf(e, Critter.DECOY)
        val first = hit(e, 0, decoy)
        assertEquals(WhackKind.DECOY, first.kind); assertEquals(0, first.delta); assertEquals(0, e.score[0])
        assertEquals(1, e.decoyHits[0])
        // With points in hand it costs the full twenty and ends the streak.
        val e2 = playing(cfg())
        val real = e2.spawns.filter { it.critter != Critter.DECOY }
        val laterDecoy = e2.spawns.first { it.critter == Critter.DECOY && it.startMs > real[3].endMs }
        for (sp in real.filter { it.endMs < laterDecoy.startMs }.take(3)) hit(e2, 0, sp)
        val before = e2.score[0]
        assertTrue(before >= 30)
        val r = hit(e2, 0, laterDecoy)
        assertEquals(-20, r.delta); assertEquals(before - 20, e2.score[0]); assertEquals(0, e2.combo[0])
    }

    @Test fun tappingAnEmptyHoleOnlyBreaksTheStreak() {
        val e = playing(cfg())
        val real = e.spawns.filter { it.critter != Critter.DECOY }.take(3)
        for (sp in real) hit(e, 0, sp)
        assertEquals(3, e.combo[0])
        val score = e.score[0]
        // A moment and a hole with nothing in it (and no recent tap on it, so the debounce is not involved).
        val t = real[2].startMs + 600L
        val hitHoles = real.map { it.hole }.toSet()
        val emptyHole = (0 until 9).first { it !in hitHoles && e.holeView(0, it, t) == null }
        val empty = e.whack(0, emptyHole, t, t)
        assertEquals(WhackKind.MISS, empty.kind)
        assertEquals(0, e.combo[0]); assertEquals(score, e.score[0]); assertEquals(1, e.misses[0])
        assertEquals(3, e.bestCombo[0])
    }

    @Test fun relaxedRemovesEveryPenalty() {
        val e = playing(cfg(relaxed = true))
        val real = e.spawns.filter { it.critter != Critter.DECOY }
        val decoy = e.spawns.first { it.critter == Critter.DECOY }
        hit(e, 0, real[0])
        val combo = e.combo[0]
        val score = e.score[0]
        val r = hit(e, 0, decoy)
        assertEquals(WhackKind.DECOY, r.kind); assertEquals(0, r.delta)
        assertEquals(score, e.score[0]); assertEquals(combo, e.combo[0])
        val t = real[0].startMs + 600L
        val emptyHole = (0 until 9).first { it != real[0].hole && e.holeView(0, it, t) == null }
        assertEquals(WhackKind.MISS, e.whack(0, emptyHole, t, t).kind)
        assertEquals(combo, e.combo[0])
    }

    @Test fun twoTapsOnTheSameHoleCloserThanTheDebounceCountOnce() {
        val e = playing(cfg())
        val mole = firstOf(e, Critter.MOLE)
        val t = mole.startMs + 50L
        assertEquals(WhackKind.HIT, e.whack(0, mole.hole, t, t).kind)
        assertEquals(WhackKind.IGNORED, e.whack(0, mole.hole, t + 100, t + 100).kind)
        assertEquals(0, e.misses[0])
        // The same moment on a different hole is a separate tap.
        val other = (mole.hole + 1) % 9
        assertTrue(e.whack(0, other, t + 100, t + 100).kind != WhackKind.IGNORED)
    }

    @Test fun whacksBeforePlayAfterTheEndOrFromNowhereDoNothing() {
        val e = WhackEngine(cfg())
        e.startMatch(0)
        assertEquals(WhackKind.IGNORED, e.whack(0, 1, 100, 100).kind) // countdown
        e.tick(WhackRules.COUNTDOWN_MS)
        val mole = firstOf(e, Critter.MOLE)
        val abs = WhackRules.COUNTDOWN_MS + mole.startMs + 20
        assertEquals(WhackKind.IGNORED, e.whack(7, mole.hole, abs, abs).kind)
        assertEquals(WhackKind.IGNORED, e.whack(0, 99, abs, abs).kind)
        assertEquals(WhackKind.IGNORED, e.whack(0, mole.hole, WhackRules.COUNTDOWN_MS - 5, abs).kind)     // touched before play began
        e.tick(WhackRules.COUNTDOWN_MS + 60_000)
        assertEquals(WhackPhase.OVER, e.phase)
        assertEquals(WhackKind.IGNORED, e.whack(0, mole.hole, abs, WhackRules.COUNTDOWN_MS + 61_000).kind)
        assertEquals(0L, e.timeLeftMs(WhackRules.COUNTDOWN_MS + 61_000))
    }

    @Test fun aTouchTimestampFromTheFutureIsClampedToNow() {
        val e = playing(cfg())
        val mole = firstOf(e, Critter.MOLE)
        val now = mole.startMs + 30L
        assertEquals(WhackKind.HIT, e.whack(0, mole.hole, now + 5_000, now).kind)
    }

    @Test fun aCritterOnlyCountsWhileItIsUp() {
        val e = playing(cfg())
        val mole = firstOf(e, Critter.MOLE)
        e.tick(mole.endMs + 10L)
        assertEquals(WhackKind.MISS, e.whack(0, mole.hole, mole.endMs + 5L, mole.endMs + 10L).kind)
        val e2 = playing(cfg())
        assertEquals(WhackKind.HIT, e2.whack(0, mole.hole, mole.endMs - 1L, mole.endMs - 1L).kind)
    }

    @Test fun oneSwitchWhacksWhateverHoleTheCursorIsOnIgnoringWhereYouTouched() {
        val e = playing(cfg(oneSwitch = true, seed = 3))
        var done = false
        for (sp in e.spawns.filter { it.critter != Critter.DECOY }) {
            var t = sp.startMs
            while (t < sp.endMs && e.scanHoleAt(t.toLong()) != sp.hole) t += 50
            if (t >= sp.endMs) continue
            val r = e.whack(0, (sp.hole + 3) % 9, t.toLong(), t.toLong())
            assertTrue(r.kind == WhackKind.HIT || r.kind == WhackKind.BONUS)
            assertEquals(sp.hole, r.hole)
            done = true
            break
        }
        assertTrue(done)
        assertEquals(0, WhackRules.scanHole(0, 9)); assertEquals(1, WhackRules.scanHole(800, 9)); assertEquals(0, WhackRules.scanHole(9 * 800L, 9))
    }

    @Test fun holeViewShowsTheCritterPoppingUpThenTheBonkPoseThenNothing() {
        val e = playing(cfg())
        val mole = firstOf(e, Critter.MOLE)
        assertNull(e.holeView(0, mole.hole, mole.startMs - 1L))
        val rising = e.holeView(0, mole.hole, mole.startMs + 20L)!!
        assertTrue(rising.pop in 0.01f..0.99f); assertEquals(Critter.MOLE, rising.critter); assertEquals(-1, rising.hitAgeMs)
        assertEquals(1f, e.holeView(0, mole.hole, mole.startMs + mole.lifeMs / 2L)!!.pop, 0f)
        val falling = e.holeView(0, mole.hole, mole.endMs - 20L)!!
        assertTrue(falling.pop < 1f)
        assertNull(e.holeView(0, mole.hole, mole.endMs + 1L))
        hit(e, 0, mole, offset = 300)
        val bonk = e.holeView(0, mole.hole, mole.startMs + 350L)!!
        assertEquals(50, bonk.hitAgeMs)
        assertNull(e.holeView(0, mole.hole, mole.startMs + 300L + WhackEngine.HIT_SHOW_MS + 5L))
        // The other zone still sees the unwhacked critter.
        assertEquals(-1, e.holeView(1, mole.hole, mole.startMs + 350L)!!.hitAgeMs)
    }

    // ---- results ----

    @Test fun theWinnerIsTheStrictLeaderAndATieAtTheTopHasNoWinner() {
        val e = playing(cfg(seats = 3))
        assertEquals(-1, e.winner())
        hit(e, 2, firstOf(e, Critter.MOLE))
        assertEquals(2, e.winner())
        hit(e, 1, firstOf(e, Critter.MOLE))
        assertEquals(-1, e.winner())
    }

    @Test fun theRoundEndsWhenTheClockRunsOut() {
        val e = WhackEngine(cfg(durationMs = 60_000))
        e.startMatch(1_000)
        assertEquals(WhackPhase.COUNTDOWN, e.phase)
        e.tick(1_000 + WhackRules.COUNTDOWN_MS - 1)
        assertEquals(WhackPhase.COUNTDOWN, e.phase)
        e.tick(1_000 + WhackRules.COUNTDOWN_MS)
        assertEquals(WhackPhase.PLAY, e.phase)
        assertEquals(60_000L, e.timeLeftMs(1_000 + WhackRules.COUNTDOWN_MS))
        assertEquals(30_000L, e.timeLeftMs(1_000 + WhackRules.COUNTDOWN_MS + 30_000))
        e.tick(1_000 + WhackRules.COUNTDOWN_MS + 60_000)
        assertEquals(WhackPhase.OVER, e.phase)
    }

    @Test fun roundLengthsOfSixtyNinetyAndOneTwentyAreAccepted() {
        for (t in WhackRules.TIME_CHOICES) assertEquals(t * 1000, cfg(durationMs = t * 1000).durationMs)
        assertEquals(setOf(60, 90, 120), WhackRules.TIME_CHOICES.toSet())
    }

    // ---- the computer ----

    private fun scoreOf(level: CpuDifficulty, seed: Long, seats: Int = 2): Int {
        val e = WhackEngine(cfg(seats = seats, seed = seed, durationMs = 60_000, cpu = setOf(1), level = level))
        e.startMatch(0)
        e.tick(WhackRules.COUNTDOWN_MS)
        e.tick(WhackRules.COUNTDOWN_MS + 60_000)
        return e.score[1]
    }

    /** A perfect, instant player's score on the same schedule (ignores decoys, hits everything 20 ms after it appears). */
    private fun perfectScore(seed: Long): Int {
        val e = playing(cfg(seed = seed))
        for (sp in e.spawns.filter { it.critter != Critter.DECOY }) hit(e, 0, sp)
        return e.score[0]
    }

    @Test fun computerLevelsAreClearlyOrderedButNeverPerfect() {
        var easy = 0; var medium = 0; var hard = 0; var perfect = 0
        for (seed in 0L until 40L) {
            easy += scoreOf(CpuDifficulty.EASY, seed); medium += scoreOf(CpuDifficulty.MEDIUM, seed); hard += scoreOf(CpuDifficulty.HARD, seed)
            perfect += perfectScore(seed)
        }
        assertTrue("easy $easy medium $medium", easy * 1.25 < medium)
        assertTrue("medium $medium hard $hard", medium * 1.1 < hard)
        assertTrue("hard $hard is not perfect $perfect", hard < perfect * 0.9)
        assertTrue("hard $hard is a real opponent", hard > perfect * 0.25)
        assertTrue("easy $easy is beatable", easy < perfect * 0.35)
    }

    @Test fun everyBeatItPlansIsHumanPlausible() {
        for (level in CpuDifficulty.entries) {
            val p = WhackRules.profile(level)
            assertTrue(p.floorMs >= 230 && p.reactMs >= p.floorMs && p.gapMs >= 300)
            val e = WhackEngine(cfg(seed = 12, cpu = setOf(1), level = level, durationMs = 90_000))
            e.startMatch(0)
            val plan = e.plannedWhacks(1)
            assertTrue(plan.isNotEmpty())
            var last = -1_000_000
            for ((at, hole) in plan) {
                assertTrue("level $level strikes again after ${at - last} ms", at - last >= p.gapMs)
                last = at
                assertTrue(hole in 0 until 9 && at < 90_000)
                // It never strikes before a critter on that hole has been up for the reaction floor.
                val sp = e.spawns.firstOrNull { it.hole == hole && at >= it.startMs && at < it.endMs }
                if (sp != null) assertTrue(at - sp.startMs >= minOf(p.floorMs, (p.reactMs * 0.8).toInt()) - 1)
            }
        }
    }

    @Test fun computerSeatsHitDecoysMoreOftenWhenEasy() {
        fun decoys(level: CpuDifficulty): Int {
            var n = 0
            for (seed in 0L until 30L) {
                val e = WhackEngine(cfg(seed = seed, cpu = setOf(1), level = level)); e.startMatch(0); e.tick(WhackRules.COUNTDOWN_MS + 60_000)
                n += e.decoyHits[1]
            }
            return n
        }
        assertTrue(decoys(CpuDifficulty.EASY) > decoys(CpuDifficulty.HARD))
    }

    @Test fun aComputersPlanIsFixedAtStartAndReplaysExactly() {
        val a = WhackEngine(cfg(seed = 4, cpu = setOf(1))); a.startMatch(0)
        val b = WhackEngine(cfg(seed = 4, cpu = setOf(1))); b.startMatch(500)
        assertEquals(a.plannedWhacks(1), b.plannedWhacks(1))
        a.tick(WhackRules.COUNTDOWN_MS + 60_000); b.tick(500 + WhackRules.COUNTDOWN_MS + 60_000)
        assertEquals(a.score[1], b.score[1])
        assertEquals(emptyList<Pair<Int, Int>>(), a.plannedWhacks(0))
    }

    // ---- fuzz ----

    @Test fun randomPlayNeverBreaksAnInvariantAndAlwaysEnds() {
        for (seed in 0L until 150L) {
            val r = java.util.Random(seed)
            val seats = 2 + r.nextInt(3)
            val cpu = (0 until seats).filter { r.nextInt(3) == 0 }.toSet()
            val e = WhackEngine(cfg(seats = seats, durationMs = listOf(60_000, 90_000, 120_000)[r.nextInt(3)], grid = 3 + r.nextInt(2), relaxed = r.nextBoolean(), oneSwitch = r.nextInt(4) == 0, seed = seed, cpu = cpu, level = CpuDifficulty.entries[r.nextInt(3)]))
            e.startMatch(0)
            var now = 0L
            var steps = 0
            while (e.phase != WhackPhase.OVER && steps++ < 30_000) {
                now += r.nextInt(300).toLong()
                if (r.nextBoolean()) e.whack(r.nextInt(seats + 1), r.nextInt(e.config.holes + 2) - 1, now - r.nextInt(100), now) else e.tick(now)
                for (s in 0 until seats) {
                    assertTrue(e.score[s] >= 0)
                    assertTrue(e.combo[s] <= e.bestCombo[s])
                    assertTrue(e.hits[s] + e.bonuses[s] + e.decoyHits[s] <= e.spawns.size)
                }
            }
            var guard = 0
            while (e.phase != WhackPhase.OVER && guard++ < 50) { now += 20_000; e.tick(now) }
            assertEquals("seed $seed", WhackPhase.OVER, e.phase)
            val w = e.winner()
            assertTrue(w == -1 || e.score[w] == e.score.max())
        }
    }

    @Test fun configRejectsImpossibleSettings() {
        for (bad in listOf<() -> WhackConfig>({ cfg(seats = 1) }, { cfg(seats = 5) }, { cfg(grid = 5) }, { cfg(durationMs = 100) })) {
            try { bad(); assertFalse("accepted", true) } catch (_: IllegalArgumentException) { assertNotNull(1) }
        }
    }
}
