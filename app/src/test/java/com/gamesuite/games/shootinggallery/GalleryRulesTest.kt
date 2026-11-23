package com.gamesuite.games.shootinggallery

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GalleryRulesTest {
    private val cfg = RangeConfig(seed = 7L, round = 0, relaxed = false)

    /** A shot at the exact centre of a target at the moment [t]. */
    private fun RangeRun.centre(tg: GalleryTarget, t: Int) = shoot(t, tg.xAt(t).toInt(), tg.yAt(t).toInt())

    private fun RangeRun.firstOf(kind: String) = targets.first { it.kind == kind }

    @Test fun rangesAreSeededSortedAndTheSameForEveryone() {
        val a = GalleryRange.targets(cfg)
        assertEquals(a, GalleryRange.targets(cfg))
        assertNotEquals(a, GalleryRange.targets(cfg.copy(seed = 8L)))
        assertNotEquals(a, GalleryRange.targets(cfg.copy(round = 1)))
        assertTrue(a.size in 12..60)
        assertEquals(a.sortedBy { it.spawnMs }, a)
        assertEquals(a.indices.toList(), a.map { it.id })
        assertTrue(a.all { it.kind in TargetKind.ALL })
        assertTrue(a.all { it.spawnMs < cfg.durationMs })
        assertTrue(a.take(3).none { it.kind == TargetKind.DECOY })
        // over a spread of seeds every kind shows up
        val kinds = (1L..10L).flatMap { GalleryRange.targets(cfg.copy(seed = it)) }.map { it.kind }.toSet()
        assertEquals(TargetKind.ALL, kinds)
    }

    @Test fun relaxedRangesAreSlowerBiggerAndLonger() {
        val n = GalleryRange.targets(cfg)
        val r = GalleryRange.targets(cfg.copy(relaxed = true))
        assertTrue(r.sumOf { it.speed } / r.size < 0.75 * n.sumOf { it.speed } / n.size)
        assertTrue(r.sumOf { it.rx } / r.size > 1.25 * n.sumOf { it.rx } / n.size)
        assertTrue(cfg.copy(relaxed = true).durationMs > cfg.durationMs)
        assertTrue(cfg.copy(relaxed = true).reloadMs < cfg.reloadMs)
    }

    @Test fun laterRoundsAreFaster() {
        val r0 = GalleryRange.targets(cfg.copy(round = 0)).filter { it.kind == TargetKind.DUCK }
        val r4 = GalleryRange.targets(cfg.copy(round = 4)).filter { it.kind == TargetKind.DUCK }
        assertTrue(r4.sumOf { it.speed } / r4.size > r0.sumOf { it.speed } / r0.size)
    }

    @Test fun targetsMoveAcrossTheRangeAndLeave() {
        val tg = GalleryRange.targets(cfg).first()
        assertTrue(tg.xAt(tg.spawnMs) * tg.dir < 0 || tg.xAt(tg.spawnMs) > 1000 - 1)
        val mid = (tg.spawnMs + tg.exitMs) / 2
        assertTrue(tg.xAt(mid) in 300.0..700.0)
        assertTrue(tg.aliveAt(mid) && !tg.aliveAt(tg.exitMs + 5) && !tg.aliveAt(tg.spawnMs - 1))
    }

    @Test fun aCentreHitScoresTheTargetsPointsAndAMissResetsTheCombo() {
        val run = RangeRun(cfg)
        val duck = run.firstOf(TargetKind.DUCK)
        val t = (duck.spawnMs + duck.exitMs) / 2
        val o = run.centre(duck, t)
        assertEquals(ShotKind.HIT, o.kind)
        assertEquals(duck.points, o.points)
        assertEquals(duck.points, run.score)
        assertEquals(1, run.streak)
        assertEquals(5, run.ammo)
        val miss = run.shoot(t + 10, 0, 0)
        assertEquals(ShotKind.MISS, miss.kind)
        assertEquals(0, run.streak)
    }

    @Test fun aTargetCanOnlyBeShotOnce() {
        val run = RangeRun(cfg)
        val duck = run.firstOf(TargetKind.DUCK)
        val t = (duck.spawnMs + duck.exitMs) / 2
        assertEquals(ShotKind.HIT, run.centre(duck, t).kind)
        assertEquals(ShotKind.MISS, run.centre(duck, t + 5).kind)
    }

    @Test fun comboMultiplierGrowsEveryThreeHitsToFourTimes() {
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 4), (0..12).map { GalleryRange.multiplierFor(it) })
        // a synthetic run of hits on many ducks: stream the targets one at a time
        val run = RangeRun(cfg)
        var expected = 0
        var streak = 0
        for (tg in run.targets.filter { it.kind == TargetKind.DUCK }.take(8)) {
            val t = (tg.spawnMs + tg.exitMs) / 2
            if (run.ammoAt(t) == 0) continue
            val o = run.centre(tg, t)
            if (o.kind == ShotKind.HIT) { expected += tg.points * GalleryRange.multiplierFor(streak); streak++ }
            else if (o.kind == ShotKind.MISS) streak = 0
        }
        assertEquals(expected, run.score)
    }

    @Test fun ringsScoreByZone() {
        val run = RangeRun(cfg)
        val ring = run.firstOf(TargetKind.RING)
        val t = (ring.spawnMs + ring.exitMs) / 2
        val cx = ring.xAt(t); val cy = ring.yAt(t)
        val o = run.shoot(t, (cx + 0.7 * ring.rx).toInt(), cy.toInt())
        assertEquals(10, o.points); assertEquals(1, o.zone)
        val run2 = RangeRun(cfg)
        val o2 = run2.shoot(t, (cx + 0.4 * ring.rx).toInt(), cy.toInt())
        assertEquals(25, o2.points); assertEquals(2, o2.zone)
        val run3 = RangeRun(cfg)
        val o3 = run3.shoot(t, cx.toInt(), cy.toInt())
        assertEquals(50, o3.points); assertEquals(3, o3.zone)
    }

    @Test fun aDecoyCostsPointsBreaksTheComboButNeverGoesBelowZero() {
        val run = RangeRun(cfg)
        val decoy = run.firstOf(TargetKind.DECOY)
        val t = (decoy.spawnMs + decoy.exitMs) / 2
        val o = run.centre(decoy, t)
        assertEquals(-GalleryWorld.DECOY_PENALTY, o.points)
        assertEquals(0, run.score)
        assertEquals(1, run.decoys)
        assertEquals(0, run.streak)
    }

    @Test fun theMagazineEmptiesReloadsAutomaticallyAndDryShotsAreFree() {
        val run = RangeRun(cfg)
        repeat(6) { assertEquals(ShotKind.MISS, run.shoot(100 + it * 10, 0, 0).kind) }
        assertEquals(0, run.ammo)
        assertTrue(run.reloading(200))
        assertEquals(ShotKind.DRY, run.shoot(300, 0, 0).kind)
        assertEquals(6, run.fired)
        assertEquals(6, run.ammoAt(150 + cfg.reloadMs))
        assertEquals(ShotKind.MISS, run.shoot(150 + cfg.reloadMs, 0, 0).kind)
        assertEquals(7, run.fired)
    }

    @Test fun aManualReloadTakesTheSameTimeAndIsIgnoredWhenFull() {
        val run = RangeRun(cfg)
        assertEquals(ShotKind.IGNORED, run.reload(10).kind)
        run.shoot(20, 0, 0); run.shoot(30, 0, 0)
        assertEquals(ShotKind.RELOAD, run.reload(40).kind)
        assertEquals(ShotKind.DRY, run.shoot(100, 0, 0).kind)
        assertEquals(6, run.ammoAt(40 + cfg.reloadMs))
        assertEquals(ShotKind.IGNORED, run.reload(60).kind)
    }

    @Test fun aStarGivesBonusPointsAndAFreeMagazine() {
        val run = RangeRun(cfg)
        val star = run.firstOf(TargetKind.STAR)
        val t = (star.spawnMs + star.exitMs) / 2
        repeat(3) { run.shoot(maxOf(0, t - 300 + it), 0, 0) }
        assertEquals(3, run.ammo)
        val o = run.centre(star, t)
        assertEquals(GalleryWorld.STAR_POINTS, o.points)
        assertEquals(6, run.ammo)
        assertEquals(1, run.stars)
        // even in the middle of a reload
        val run2 = RangeRun(cfg)
        repeat(6) { run2.shoot(maxOf(0, t - 400 + it), 0, 0) }
        assertTrue(run2.reloading(t))
        // the reload (1.5 s) is still running, so the star can only be shot after it
        assertEquals(ShotKind.DRY, run2.centre(star, t).kind)
    }

    @Test fun shotsOutsideTheTurnOrOutOfBoundsAreRejectedByTheLogCheck() {
        assertTrue(GalleryRange.validLog(cfg, emptyList()))
        assertFalse(GalleryRange.validLog(cfg, listOf(GalleryShot(500, 10, 10), GalleryShot(400, 10, 10))))
        assertFalse(GalleryRange.validLog(cfg, listOf(GalleryShot(cfg.durationMs + 1, 10, 10))))
        assertFalse(GalleryRange.validLog(cfg, listOf(GalleryShot(10, 1001, 10))))
        assertFalse(GalleryRange.validLog(cfg, listOf(GalleryShot(10, 10, -1))))
        assertFalse(GalleryRange.validLog(cfg, List(GalleryWorld.MAX_SHOTS + 1) { GalleryShot(10, 10, 10) }))
        assertNull(GalleryRange.play(cfg, listOf(GalleryShot(-5, 10, 10))))
    }

    @Test fun theLiveRunAndTheReplayAgreeOnAnyLog() {
        val rnd = Random(3)
        repeat(30) {
            val c = cfg.copy(seed = rnd.nextLong(), round = rnd.nextInt(5), relaxed = rnd.nextBoolean())
            var t = 0
            val log = ArrayList<GalleryShot>()
            val run = RangeRun(c)
            while (t < c.durationMs && log.size < 100) {
                t += rnd.nextInt(30, 600)
                if (t > c.durationMs) break
                val shot = if (rnd.nextInt(8) == 0) GalleryShot(t, GalleryWorld.RELOAD_X, 0) else GalleryShot(t, rnd.nextInt(0, 1001), rnd.nextInt(0, 601))
                log += shot
                run.act(shot.t, shot.x, shot.y)
            }
            assertEquals(run.result(), GalleryRange.play(c, log))
        }
    }

    private fun meanScore(level: CpuDifficulty, relaxed: Boolean = false, seeds: Int = 24): Triple<Double, Double, Double> {
        var score = 0.0; var hits = 0.0; var fired = 0.0
        for (s in 1..seeds) {
            val c = RangeConfig(s.toLong(), s % 3, relaxed)
            val r = GalleryRange.play(c, GalleryBot.log(c, level, Random(s)))!!
            score += r.score; hits += r.hits; fired += r.fired
        }
        return Triple(score / seeds, hits / seeds, hits / fired.coerceAtLeast(1.0))
    }

    @Test fun computerLevelsAreOrderedByScoreAndAccuracyButHardIsNotPerfect() {
        val easy = meanScore(CpuDifficulty.EASY)
        val med = meanScore(CpuDifficulty.MEDIUM)
        val hard = meanScore(CpuDifficulty.HARD)
        assertTrue("score ${easy.first} ${med.first} ${hard.first}", easy.first < med.first * 0.85 && med.first < hard.first * 0.9)
        assertTrue("accuracy ${easy.third} ${med.third} ${hard.third}", easy.third < med.third && med.third < hard.third)
        assertTrue("hard accuracy ${hard.third}", hard.third < 0.97)
        assertTrue("easy still hits things ${easy.second}", easy.second >= 2.0)
    }

    @Test fun botLogsAreDeterministicLegalAndRespectTheMagazine() {
        for (level in CpuDifficulty.values()) {
            val a = GalleryBot.log(cfg, level, Random(4))
            assertEquals(a, GalleryBot.log(cfg, level, Random(4)))
            assertTrue(GalleryRange.validLog(cfg, a))
            // no dry shots in a log the bot produced
            val run = RangeRun(cfg)
            a.forEach { assertTrue(run.act(it.t, it.x, it.y).kind != ShotKind.DRY) }
        }
    }

    @Test fun relaxedPlayHelpsTheSameShooter() {
        val normal = meanScore(CpuDifficulty.EASY, relaxed = false)
        val relaxed = meanScore(CpuDifficulty.EASY, relaxed = true)
        assertTrue("${normal.third} ${relaxed.third}", relaxed.third > normal.third)
    }
}
