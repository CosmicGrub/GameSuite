package com.gamesuite.ui

import com.gamesuite.stats.GameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStatsLogicTest {
    private fun g(id: String, name: String, played: Int, last: Long, w: Int = 0, l: Int = 0, d: Int = 0) =
        GameStats(gameId = id, displayName = name, matchesPlayed = played, wins = w, losses = l, draws = d,
            lastPlayedEpochMillis = last)

    private val sample = listOf(
        g("b", "banana", 5, 100),
        g("a", "Apple", 9, 50),
        g("c", "cherry", 5, 300)
    )

    @Test fun blankQueryMatchesEverything() {
        assertTrue(settingMatches("", "Haptics"))
        assertTrue(settingMatches("   ", "Haptics"))
    }

    @Test fun matchIsCaseInsensitiveAndTrimmed() {
        assertTrue(settingMatches(" HAPT ", "Haptics"))
        assertTrue(settingMatches("motion", "Reduced motion", "Minimize animations"))
        assertTrue(settingMatches("animations", "Reduced motion", "Minimize animations"))
    }

    @Test fun noMatch() {
        assertFalse(settingMatches("zzz", "Haptics", "Vibration"))
    }

    @Test fun recentSort() {
        assertEquals(listOf("c", "b", "a"), sample.sortedWith(statsComparator(StatsSort.RECENT)).map { it.gameId })
    }

    @Test fun mostPlayedSortTiesByName() {
        assertEquals(listOf("a", "b", "c"), sample.sortedWith(statsComparator(StatsSort.MOST_PLAYED)).map { it.gameId })
    }

    @Test fun alphabeticalIgnoresCase() {
        assertEquals(listOf("a", "b", "c"), sample.sortedWith(statsComparator(StatsSort.A_TO_Z)).map { it.gameId })
    }

    @Test fun summary() {
        val s = summarizeStats(listOf(g("a", "A", 4, 1, 2, 1, 1), g("b", "B", 3, 2, 1, 2, 0)))
        assertEquals(StatsSummary(games = 2, matches = 7, wins = 3, losses = 3, draws = 1), s)
    }
}
