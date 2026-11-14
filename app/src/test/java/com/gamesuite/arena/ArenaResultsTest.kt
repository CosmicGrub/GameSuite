package com.gamesuite.arena

import com.gamesuite.settings.GameSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaResultsTest {
    private fun rec(o: ArenaOutcome, ms: Long = 1000) = ArenaGameRecord(o, ms)

    @Test
    fun stalledGamesAreTalliedApartFromWinsAndDraws() {
        val b = ArenaScoreboard().after(ArenaOutcome.Winner(0)).after(ArenaOutcome.Stalled).after(ArenaOutcome.Draw)
        assertEquals(ArenaScoreboard(aWins = 1, draws = 1, stalled = 1), b)
        assertEquals(3, b.played)
    }

    @Test
    fun swappedExchangesTheLevels() {
        val cfg = ArenaConfig("chess", ArenaLevel.HARD, ArenaLevel.EASY, 5)
        val sw = cfg.swapped()
        assertEquals(ArenaLevel.EASY, sw.levelA)
        assertEquals(ArenaLevel.HARD, sw.levelB)
        assertEquals(cfg, sw.swapped())
        assertEquals(5, sw.games)
    }

    @Test
    fun levelStatsGiveWinRatesPerLevel() {
        val cfg = ArenaConfig("chess", ArenaLevel.HARD, ArenaLevel.EASY, 4)
        val s = ArenaSeriesSummary(
            cfg,
            listOf(
                rec(ArenaOutcome.Winner(0), 2000), rec(ArenaOutcome.Winner(0), 4000),
                rec(ArenaOutcome.Winner(1), 6000), rec(ArenaOutcome.Draw, 4000)
            )
        )
        val stats = s.levelStats().associateBy { it.level }
        assertEquals(2, stats.getValue(ArenaLevel.HARD).wins)
        assertEquals(0.5, stats.getValue(ArenaLevel.HARD).winRate, 1e-9)
        assertEquals(1, stats.getValue(ArenaLevel.EASY).wins)
        assertEquals(0.25, stats.getValue(ArenaLevel.EASY).winRate, 1e-9)
        assertEquals(1, stats.getValue(ArenaLevel.EASY).draws)
        assertEquals(4000L, s.averageGameMs)
        assertEquals(6000L, s.longestGameMs)
    }

    @Test
    fun stalledGamesDoNotCountInRatesOrAverages() {
        val cfg = ArenaConfig("hex", ArenaLevel.MEDIUM, ArenaLevel.EASY, 3)
        val s = ArenaSeriesSummary(cfg, listOf(rec(ArenaOutcome.Winner(0), 3000), rec(ArenaOutcome.Stalled, 99_000), rec(ArenaOutcome.Winner(1), 5000)))
        assertEquals(1, s.stalled)
        assertEquals(4000L, s.averageGameMs)
        assertEquals(2, s.levelStats().first().games)
    }

    @Test
    fun sameLevelOnBothSidesAppearsOnce() {
        val cfg = ArenaConfig("hex", ArenaLevel.HARD, ArenaLevel.HARD, 2)
        val s = ArenaSeriesSummary(cfg, listOf(rec(ArenaOutcome.Winner(0)), rec(ArenaOutcome.Winner(1))))
        val stats = s.levelStats()
        assertEquals(1, stats.size)
        assertEquals(0.5, stats[0].winRate, 1e-9)
    }

    @Test
    fun cooperativeSeriesReportABuiltRate() {
        val cfg = ArenaConfig("bridge-builders", games = 4)
        val s = ArenaSeriesSummary(
            cfg, listOf(rec(ArenaOutcome.Team(true)), rec(ArenaOutcome.Team(false)), rec(ArenaOutcome.Team(true)), rec(ArenaOutcome.Team(true))), cooperative = true
        )
        assertEquals(0.75, s.teamBuiltRate, 1e-9)
        assertTrue(s.levelStats().isEmpty())
    }

    @Test
    fun emptySeriesHasZeroEverything() {
        val s = ArenaSeriesSummary(ArenaConfig("chess"), emptyList())
        assertEquals(0L, s.averageGameMs)
        assertEquals(0.0, s.levelStats().first().winRate, 1e-9)
        assertEquals(0.0, s.teamBuiltRate, 1e-9)
    }

    @Test
    fun watchdogFiresOnlyWhenTheFingerprintStaysTheSame() {
        val w = ArenaWatchdog(1000)
        assertFalse(w.tick(7, 100))
        repeat(8) { assertFalse(w.tick(7, 100)) } // 800 ms still
        assertFalse(w.tick(8, 100)) // progress resets
        repeat(9) { assertFalse(w.tick(8, 100)) }
        assertTrue(w.tick(8, 100))
        assertTrue(w.stillMs >= 1000)
        w.reset()
        assertFalse(w.tick(8, 100))
        assertEquals(0L, w.stillMs)
    }

    @Test
    fun roundRobinPlaysEveryPairingOnce() {
        val t = ArenaTournament("chess", 3)
        assertEquals(
            listOf(
                ArenaFixture(ArenaLevel.EASY, ArenaLevel.MEDIUM),
                ArenaFixture(ArenaLevel.EASY, ArenaLevel.HARD),
                ArenaFixture(ArenaLevel.MEDIUM, ArenaLevel.HARD)
            ),
            t.fixtures
        )
        assertEquals(ArenaConfig("chess", ArenaLevel.EASY, ArenaLevel.HARD, 3), t.configFor(1))
        assertFalse(t.isComplete(2))
        assertTrue(t.isComplete(3))
        assertEquals(5, ArenaTournament.gamesPerFixtureFor(0))
        assertEquals(10, ArenaTournament.gamesPerFixtureFor(10))
    }

    @Test
    fun standingsAndHeadToHeadFromTheFixtures() {
        val results = listOf(
            ArenaFixtureResult(ArenaFixture(ArenaLevel.EASY, ArenaLevel.MEDIUM), listOf(rec(ArenaOutcome.Winner(1)), rec(ArenaOutcome.Winner(1)), rec(ArenaOutcome.Winner(0)))),
            ArenaFixtureResult(ArenaFixture(ArenaLevel.EASY, ArenaLevel.HARD), listOf(rec(ArenaOutcome.Winner(1)), rec(ArenaOutcome.Draw), rec(ArenaOutcome.Stalled))),
            ArenaFixtureResult(ArenaFixture(ArenaLevel.MEDIUM, ArenaLevel.HARD), listOf(rec(ArenaOutcome.Winner(1)), rec(ArenaOutcome.Winner(1)), rec(ArenaOutcome.Winner(0))))
        )
        val st = ArenaTournament.standings(results)
        assertEquals(listOf(ArenaLevel.HARD, ArenaLevel.MEDIUM, ArenaLevel.EASY), st.map { it.level })
        val hard = st.first()
        assertEquals(3, hard.wins) // 1 vs Easy + 2 vs Medium
        assertEquals(1, hard.draws)
        assertEquals(7, hard.points)
        // Stalled games are in nobody's totals.
        assertEquals(16, st.sumOf { it.games }) // 3 + 2 + 3 completed games, two levels each
        assertEquals(Triple(2, 0, 1), ArenaTournament.headToHead(results, ArenaLevel.MEDIUM, ArenaLevel.EASY))
        assertEquals(Triple(1, 0, 2), ArenaTournament.headToHead(results, ArenaLevel.EASY, ArenaLevel.MEDIUM))
        assertEquals(null, ArenaTournament.headToHead(results.take(1), ArenaLevel.HARD, ArenaLevel.EASY))
    }

    @Test
    fun resultsLogRoundTripsCapsAndSurvivesGarbage() {
        val summary = ArenaSeriesSummary(ArenaConfig("hex", ArenaLevel.HARD, ArenaLevel.EASY, 2), listOf(rec(ArenaOutcome.Winner(0), 5000), rec(ArenaOutcome.Stalled)))
        val r = ArenaResultsLog.recordOf(summary, tournament = true, nowMs = 42L)
        assertEquals(1, r.aWins)
        assertEquals(1, r.stalled)
        assertEquals(5000L, r.averageGameMs)
        var log = emptyList<ArenaSeriesRecord>()
        repeat(ArenaResultsLog.MAX + 5) { log = ArenaResultsLog.add(log, r.copy(finishedAtMs = it.toLong())) }
        assertEquals(ArenaResultsLog.MAX, log.size)
        assertEquals((ArenaResultsLog.MAX + 4).toLong(), log.first().finishedAtMs) // newest first
        assertEquals(log, ArenaResultsLog.decode(ArenaResultsLog.encode(log)))
        assertTrue(ArenaResultsLog.decode("not json {").isEmpty())
        assertTrue(ArenaResultsLog.decode(null).isEmpty())
        assertTrue(ArenaResultsLog.decode("").isEmpty())
    }

    @Test
    fun maxSpeedKeepsAtLeastOneFramePerStep() {
        assertTrue(GameSpeed.ARENA_MAX.scale(1) >= 16L)
        assertTrue(GameSpeed.ARENA_MAX.scale(5) >= 16L)
        assertEquals(0L, GameSpeed.ARENA_MAX.scale(0))
        // Other speeds are untouched.
        assertEquals(1L, GameSpeed.ARENA_X4.scale(1))
        assertEquals(1000L, GameSpeed.NORMAL.scale(1000))
    }
}
