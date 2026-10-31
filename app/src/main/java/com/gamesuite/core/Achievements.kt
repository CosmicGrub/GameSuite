package com.gamesuite.core

/**
 * A small immutable summary of everything the stats store knows, which is all an achievement
 * predicate may look at. Built by `stats/MatchRecording.kt` from the per-game [com.gamesuite.stats.GameStats]
 * and the global progress record. Every field is a plain number so fixtures are one-liners in tests.
 */
data class AchievementSnapshot(
    val totalMatches: Int = 0,
    val totalWins: Int = 0,
    /** Distinct games with at least one match recorded. */
    val gamesPlayed: Int = 0,
    /** Best run of consecutive wins across all games, in the order the matches were played. */
    val bestOverallWinStreak: Int = 0,
    val winsOnHard: Int = 0,
    val onlineWins: Int = 0,
    val dailiesSolved: Int = 0,
    /** Distinct game families (board, card, word, arcade/puzzle) with a match recorded. */
    val familiesPlayed: Int = 0,
    /** How many families exist to be played; the goal of "Play every family". */
    val familiesTotal: Int = 0,
    val bestDayStreak: Int = 0
)

/**
 * One achievement. [current] and [goal] give a cheap progress readout (`current / goal`); it is
 * unlocked once [current] reaches [goal] (and the goal is real). The id is stable and persisted;
 * the UI maps it to localized text (StatsScreen), so titles never live in this pure file.
 */
class AchievementDef(
    val id: String,
    val current: (AchievementSnapshot) -> Int,
    val goal: (AchievementSnapshot) -> Int
) {
    fun isMet(s: AchievementSnapshot): Boolean {
        val g = goal(s)
        return g > 0 && current(s) >= g
    }

    /** Progress clamped to the goal, as (done, goal). */
    fun progress(s: AchievementSnapshot): Pair<Int, Int> {
        val g = goal(s)
        return minOf(current(s), g).coerceAtLeast(0) to g
    }
}

object Achievements {
    const val FIRST_WIN = "first_win"
    const val WINS_10 = "wins_10"
    const val WINS_100 = "wins_100"
    const val STREAK_5 = "streak_5"
    const val HARD_WIN = "hard_win"
    const val DAILY = "daily"
    const val ALL_FAMILIES = "all_families"
    const val ONLINE_WIN = "online_win"
    const val DAYS_7 = "days_7"
    const val GAMES_10 = "games_10"

    private fun fixed(id: String, goal: Int, current: (AchievementSnapshot) -> Int) =
        AchievementDef(id, current) { goal }

    /** Display order. "Beat the CPU in under N moves" is intentionally absent: the stats store keeps no move counts. */
    val all: List<AchievementDef> = listOf(
        fixed(FIRST_WIN, 1) { it.totalWins },
        fixed(WINS_10, 10) { it.totalWins },
        fixed(WINS_100, 100) { it.totalWins },
        fixed(STREAK_5, 5) { it.bestOverallWinStreak },
        fixed(HARD_WIN, 1) { it.winsOnHard },
        fixed(DAILY, 1) { it.dailiesSolved },
        AchievementDef(ALL_FAMILIES, { it.familiesPlayed }, { it.familiesTotal }),
        fixed(ONLINE_WIN, 1) { it.onlineWins },
        fixed(DAYS_7, 7) { it.bestDayStreak },
        fixed(GAMES_10, 10) { it.gamesPlayed }
    )

    val byId: Map<String, AchievementDef> = all.associateBy { it.id }

    /** Ids met by [snapshot] that are not in [alreadyUnlocked], in display order. */
    fun newlyMet(snapshot: AchievementSnapshot, alreadyUnlocked: Set<String>): List<String> =
        all.filter { it.id !in alreadyUnlocked && it.isMet(snapshot) }.map { it.id }
}
