package com.gamesuite.stats

import com.gamesuite.core.AchievementSnapshot
import com.gamesuite.core.Achievements
import com.gamesuite.core.DayStreak
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubFamily
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.MatchDetails
import com.gamesuite.core.PlayMode
import com.gamesuite.core.atRiskOn
import com.gamesuite.core.currentOn
import com.gamesuite.core.nextWinStreak
import com.gamesuite.core.recordPlay
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable

/**
 * Global (not per-game) progress: the day streak, the cross-game win streak and the unlocked
 * achievements. Stored as one JSON blob next to the per-game stats; every field has a default so a
 * blob from an older or newer build decodes tolerantly.
 */
@Serializable
data class ProgressState(
    val dayStreak: DayStreak = DayStreak(),
    /** Consecutive wins across ALL games, in play order. */
    val overallWinStreak: Int = 0,
    val bestOverallWinStreak: Int = 0,
    /** Achievement id -> local epoch day it was unlocked on. */
    val unlocked: Map<String, Long> = emptyMap(),
    /** Unlocked ids the Home tab has not announced yet ("Newly unlocked"). */
    val pendingAnnounce: List<String> = emptyList()
)

/** What one recorded match produced, for the store to persist and for tests to assert on. */
data class RecordedMatch(
    val stats: Map<String, GameStats>,
    val progress: ProgressState,
    val newlyUnlocked: List<String>
)

/**
 * Pure core of [StatsRepository.recordMatch]: folds one finished match into the per-game stats and
 * the global progress, then evaluates which achievements it unlocked. [today] is the local epoch day
 * (see `epochDayOf`); [familyOf] maps a game id to its family (null = not counted, e.g. the toolkit)
 * and [familyCount] is how many families exist.
 */
fun recordMatchInto(
    stats: Map<String, GameStats>,
    progress: ProgressState,
    gameId: String,
    displayName: String,
    outcome: LocalOutcome?,
    details: MatchDetails?,
    atEpochMillis: Long,
    today: Long,
    familyOf: (String) -> String?,
    familyCount: Int
): RecordedMatch {
    val existing = stats[gameId] ?: GameStats(gameId = gameId, displayName = displayName)
    val won = outcome == LocalOutcome.WIN
    val newStreak = nextWinStreak(existing.winStreak, outcome)
    val updated = existing.copy(
        displayName = displayName,
        matchesPlayed = existing.matchesPlayed + 1,
        wins = existing.wins + if (won) 1 else 0,
        losses = existing.losses + if (outcome == LocalOutcome.LOSS) 1 else 0,
        draws = existing.draws + if (outcome == LocalOutcome.DRAW) 1 else 0,
        lastPlayedEpochMillis = atEpochMillis,
        // Playing it again is the obvious "I want this back" signal; see GameStats.dismissedFromContinue's KDoc.
        dismissedFromContinue = false,
        winStreak = newStreak,
        bestWinStreak = maxOf(existing.bestWinStreak, newStreak),
        winsOnHard = existing.winsOnHard +
            if (won && details != null && details.vsCpu && details.cpuDifficulty == CpuDifficulty.HARD) 1 else 0,
        onlineWins = existing.onlineWins + if (won && details?.mode == PlayMode.ONLINE) 1 else 0,
        dailiesSolved = existing.dailiesSolved + if (won && details?.daily == true) 1 else 0
    )
    val newStats = stats + (gameId to updated)

    val overall = nextWinStreak(progress.overallWinStreak, outcome)
    val withStreaks = progress.copy(
        dayStreak = progress.dayStreak.recordPlay(today),
        overallWinStreak = overall,
        bestOverallWinStreak = maxOf(progress.bestOverallWinStreak, overall)
    )

    val snapshot = buildAchievementSnapshot(newStats.values, withStreaks, familyOf, familyCount)
    val fresh = Achievements.newlyMet(snapshot, withStreaks.unlocked.keys)
    val finalProgress = if (fresh.isEmpty()) withStreaks else withStreaks.copy(
        unlocked = withStreaks.unlocked + fresh.associateWith { today },
        pendingAnnounce = (withStreaks.pendingAnnounce + fresh).distinct()
    )
    return RecordedMatch(newStats, finalProgress, fresh)
}

/** Builds the immutable snapshot achievement predicates read. */
fun buildAchievementSnapshot(
    stats: Collection<GameStats>,
    progress: ProgressState,
    familyOf: (String) -> String?,
    familyCount: Int
): AchievementSnapshot {
    val played = stats.filter { it.matchesPlayed > 0 }
    return AchievementSnapshot(
        totalMatches = played.sumOf { it.matchesPlayed },
        totalWins = played.sumOf { it.wins },
        gamesPlayed = played.size,
        bestOverallWinStreak = maxOf(progress.bestOverallWinStreak, played.maxOfOrNull { it.bestWinStreak } ?: 0),
        winsOnHard = played.sumOf { it.winsOnHard },
        onlineWins = played.sumOf { it.onlineWins },
        dailiesSolved = played.sumOf { it.dailiesSolved },
        familiesPlayed = played.mapNotNull { familyOf(it.gameId) }.toSet().size,
        familiesTotal = familyCount,
        bestDayStreak = progress.dayStreak.best
    )
}

/** The streak numbers shown on the Stats screen and the Home chip. */
data class StreakSummary(
    val dayStreak: Int,
    val dayStreakAtRisk: Boolean,
    val bestDayStreak: Int,
    val overallWinStreak: Int,
    val bestOverallWinStreak: Int
)

/** Resolves [progress] for display on local day [today] (an expired day streak shows as 0). */
fun streakSummary(progress: ProgressState, today: Long): StreakSummary = StreakSummary(
    dayStreak = progress.dayStreak.currentOn(today),
    dayStreakAtRisk = progress.dayStreak.atRiskOn(today),
    bestDayStreak = progress.dayStreak.best,
    overallWinStreak = progress.overallWinStreak,
    bestOverallWinStreak = progress.bestOverallWinStreak
)

/** Game families that count for "Play every family" (the toolkit has no matches). */
val ACHIEVEMENT_FAMILY_COUNT: Int = HubFamily.values().count { it != HubFamily.TOOLKIT }

/** The achievement family of [gameId], or null for an unknown game or the toolkit. */
fun achievementFamilyOf(gameId: String): String? =
    GameHubSpecs.byId(gameId)?.family?.takeIf { it != HubFamily.TOOLKIT }?.name

/** The snapshot for the UI: same inputs as recording, with the app's real family table. */
fun achievementSnapshotFor(stats: Collection<GameStats>, progress: ProgressState): AchievementSnapshot =
    buildAchievementSnapshot(stats, progress, ::achievementFamilyOf, ACHIEVEMENT_FAMILY_COUNT)
