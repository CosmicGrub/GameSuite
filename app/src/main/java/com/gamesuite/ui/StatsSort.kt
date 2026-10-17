package com.gamesuite.ui

import com.gamesuite.stats.GameStats

enum class StatsSort { RECENT, MOST_PLAYED, A_TO_Z }

/** Comparator for the Stats screen sort chips. Ties always fall back to name, then id, so order is stable. */
fun statsComparator(sort: StatsSort): Comparator<GameStats> {
    val byName = compareBy<GameStats, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        .thenBy { it.gameId }
    return when (sort) {
        StatsSort.RECENT -> compareByDescending<GameStats> { it.lastPlayedEpochMillis }.then(byName)
        StatsSort.MOST_PLAYED -> compareByDescending<GameStats> { it.matchesPlayed }.then(byName)
        StatsSort.A_TO_Z -> byName
    }
}

/** Overall totals across every game, for the Stats summary header. */
data class StatsSummary(val games: Int, val matches: Int, val wins: Int, val losses: Int, val draws: Int)

fun summarizeStats(stats: Collection<GameStats>): StatsSummary = StatsSummary(
    games = stats.size,
    matches = stats.sumOf { it.matchesPlayed },
    wins = stats.sumOf { it.wins },
    losses = stats.sumOf { it.losses },
    draws = stats.sumOf { it.draws }
)
