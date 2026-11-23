package com.gamesuite.arena

import com.gamesuite.core.ArenaHook
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.GameSpeed
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ArenaModelsTest {
    @Test
    fun scoreboardTalliesWinsDrawsAndTeamResults() {
        var b = ArenaScoreboard()
        b = b.after(ArenaOutcome.Winner(0)) // A (seat 0)
        b = b.after(ArenaOutcome.Winner(1)) // B (seat 1)
        b = b.after(ArenaOutcome.Winner(2)) // A (second A seat)
        b = b.after(ArenaOutcome.Draw)
        assertEquals(ArenaScoreboard(aWins = 2, bWins = 1, draws = 1), b)
        assertEquals(4, b.played)
        val coop = ArenaScoreboard().after(ArenaOutcome.Team(true)).after(ArenaOutcome.Team(false)).after(ArenaOutcome.Team(true))
        assertEquals(2, coop.teamBuilt)
        assertEquals(1, coop.teamFailed)
        assertEquals(3, coop.played)
    }

    @Test
    fun seatSwapAlternatesTheOpener() {
        assertEquals(listOf(0, 1, 0, 1, 0), (0 until 5).map { ArenaSeries.firstSeat(it) })
        assertEquals(0, ArenaSeries.firstSeat(-3))
        assertEquals(0, ArenaSeries.sideOf(2))
        assertEquals(1, ArenaSeries.sideOf(3))
    }

    @Test
    fun seriesCompletion() {
        val five = ArenaConfig("chess", games = 5)
        assertFalse(ArenaSeries.isComplete(five, 4))
        assertTrue(ArenaSeries.isComplete(five, 5))
        assertEquals(5, ArenaSeries.currentGameNumber(five, 4))
        assertEquals(5, ArenaSeries.currentGameNumber(five, 5)) // held at the length once finished
        val endless = ArenaConfig("chess", games = 0)
        assertTrue(endless.endless)
        assertFalse(ArenaSeries.isComplete(endless, 10_000))
        assertEquals(11, ArenaSeries.currentGameNumber(endless, 10))
        assertTrue(ArenaSeries.isComplete(ArenaConfig("chess", games = 1), 1))
    }

    @Test
    fun levelsFollowTheSeatSide() {
        val cfg = ArenaConfig("bridge-builders", ArenaLevel.HARD, ArenaLevel.EASY, 3)
        assertEquals(listOf(ArenaLevel.HARD, ArenaLevel.EASY, ArenaLevel.HARD, ArenaLevel.EASY), (0..3).map { cfg.levelOfSeat(it) })
        assertEquals(CpuDifficulty.HARD, ArenaLevel.HARD.difficulty)
    }

    @Test
    fun speedScalingAndCycle() {
        assertEquals(1000L, ArenaSpeed.X1.scale(1000))
        assertEquals(550L, ArenaSpeed.X2.scale(1000))
        assertEquals(250L, ArenaSpeed.X4.scale(1000))
        assertTrue(ArenaSpeed.MAX.scale(1000) < 100L)
        assertTrue(ArenaSpeed.MAX.scale(5) >= 1L) // a positive pause never collapses to zero
        assertEquals(ArenaSpeed.X2, ArenaSpeed.X1.next())
        assertEquals(ArenaSpeed.X1, ArenaSpeed.MAX.next())
        // Faster speeds pause less between games.
        assertTrue(ArenaSpeed.entries.zipWithNext().all { (a, b) -> a.betweenGamesMs > b.betweenGamesMs })
        assertEquals(ArenaSpeed.X4, ArenaSpeed.parse("4x"))
        assertEquals(ArenaSpeed.MAX, ArenaSpeed.parse("max"))
        assertNull(ArenaSpeed.parse("warp"))
    }

    @Test
    fun arenaSpeedsAreNotOfferedInSettings() {
        assertFalse(GameSpeed.ARENA_X4 in GameSpeed.selectable)
        assertFalse(GameSpeed.ARENA_MAX in GameSpeed.selectable)
        assertEquals(GameSpeed.NORMAL, GameSpeed.fromName("ARENA_X4"))
        assertEquals(GameSpeed.SLOW, GameSpeed.QUICK.next())
    }

    @Test
    fun intentParsing() {
        val cfg = ArenaIntent.parse("chutes-race", "hard", "easy", 10)!!
        assertEquals(ArenaConfig("chutes-race", ArenaLevel.HARD, ArenaLevel.EASY, 10), cfg)
        // Tolerant of case, spaces and prefixes; defaults for the missing.
        val loose = ArenaIntent.parse(" Chess ", "M", null, null)!!
        assertEquals("chess", loose.gameId)
        assertEquals(ArenaLevel.MEDIUM, loose.levelA)
        assertEquals(ArenaLevel.EASY, loose.levelB)
        assertEquals(5, loose.games)
        assertEquals(0, ArenaIntent.parse("chess", "hard", "hard", 0)!!.games) // endless
        assertNull(ArenaIntent.parse("not-a-game", "hard", "easy", 3))
        assertNull(ArenaIntent.parse(null, "hard", "easy", 3))
        assertEquals(ArenaLevel.HARD, ArenaIntent.parse("chess", "banana", null, 1)!!.levelA)
    }

    @Test
    fun everyRegisteredGameIsParsableAndHasARoster() {
        assertTrue(ArenaGames.ids.containsAll(listOf("chutes-race", "bridge-builders", "chess", "checkers", "reversi", "connect-four")))
        ArenaGames.specs.forEach { spec ->
            assertTrue(ArenaIntent.parse(spec.gameId, null, null, 1) != null)
            val roster = ArenaHost.roster(spec)
            assertEquals(spec.seats, roster.size)
            assertTrue(roster.all { it.isBot })
            assertEquals(roster.size, roster.map { it.playerId }.toSet().size)
            assertEquals(roster.indices.toList(), roster.map { arenaSeatOf(it.playerId) })
        }
    }

    @Test
    fun hookHoldsAMoveWhilePausedAndRunsItAtResume() {
        val hook = ArenaHook(mapOf(0 to CpuDifficulty.HARD, 1 to CpuDifficulty.EASY))
        assertEquals(CpuDifficulty.HARD, hook.levelFor(0, CpuDifficulty.MEDIUM))
        assertEquals(CpuDifficulty.MEDIUM, hook.levelFor(5, CpuDifficulty.MEDIUM)) // unknown seat: fallback
        var ran = 0
        assertFalse(hook.deferIfPaused { ran++ })
        hook.setPaused(true)
        assertTrue(hook.deferIfPaused { ran++ })
        assertEquals(0, ran)
        hook.setPaused(false)
        assertEquals(1, ran)
        hook.setPaused(false)
        assertEquals(1, ran)
        hook.setPaused(true)
        hook.deferIfPaused { ran++ }
        hook.clearHeld()
        hook.setPaused(false)
        assertEquals(1, ran) // a cleared (stale) move never runs
    }
}
