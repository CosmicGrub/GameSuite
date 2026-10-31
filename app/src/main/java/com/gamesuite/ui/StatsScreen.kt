package com.gamesuite.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import com.gamesuite.R
import com.gamesuite.core.Achievements
import com.gamesuite.core.SystemDayClock
import com.gamesuite.stats.GameStats
import com.gamesuite.stats.StatsViewModel
import com.gamesuite.stats.StreakSummary
import com.gamesuite.stats.achievementSnapshotFor
import com.gamesuite.stats.streakSummary
import androidx.compose.ui.draw.alpha
import com.gamesuite.ui.effects.DialogBackdropBlur

/**
 * "My Stats": one shared, generic layout across every game (matches played + win/loss/draw); see
 * GameStats.kt's KDoc for why. A summary header (games, matches, overall record), sort chips
 * ([StatsSort] / [statsComparator]), then one row (COMPACT) or card (MEDIUM/EXPANDED grid) per game.
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val stats by viewModel.allStats.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    // Resolved when the screen opens: an expired day streak shows as 0 without waiting for a match.
    val today = remember { SystemDayClock.today() }
    var confirmingReset by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(StatsSort.RECENT.name) }
    val sort = StatsSort.values().firstOrNull { it.name == sortName } ?: StatsSort.RECENT

    // COMPACT keeps a plain divided list; MEDIUM/EXPANDED reflow the same stats into a 2-3 column card grid.
    val widthSizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val statColumns = when (widthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> 3
        WindowWidthSizeClass.MEDIUM -> 2
        else -> 1
    }

    // Tablet width cap: same widthIn(max = 840.dp)-before-fillMaxWidth() centering trick as Settings.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.stats_back)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))

            if (stats.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                val summary = summarizeStats(stats.values)
                SummaryHeader(summary)
                Spacer(Modifier.height(16.dp))
                StreakHeader(streakSummary(progress, today))
                Spacer(Modifier.height(16.dp))

                SortChips(selected = sort, onSelect = { sortName = it.name })
                Spacer(Modifier.height(16.dp))

                val sortedStats = stats.values.sortedWith(statsComparator(sort))
                if (statColumns == 1) {
                    sortedStats.forEach { s ->
                        StatRow(s)
                    }
                } else {
                    val rows = sortedStats.chunked(statColumns)
                    rows.forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { s -> StatCard(s, modifier = Modifier.weight(1f)) }
                            // Weighted spacers keep a short final row's cards the same width as the others.
                            repeat(statColumns - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        if (rowIndex != rows.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                AchievementsSection(achievementRows(achievementSnapshotFor(stats.values, progress), progress.unlocked.keys))
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { confirmingReset = true },
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(stringResource(R.string.stats_reset))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            // Real OS-compositor blur behind this dialog's window; see DialogBackdropBlur's doc.
            title = { DialogBackdropBlur(); Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAll()
                        confirmingReset = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.stats_reset_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingReset = false },
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.stats_cancel)) }
            }
        )
    }
}

@Composable
private fun recordText(wins: Int, losses: Int, draws: Int): String =
    if (draws > 0) stringResource(R.string.stats_record_draws, wins, losses, draws)
    else stringResource(R.string.stats_record, wins, losses)

@Composable
private fun matchesText(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.stats_matches_played, count, count)

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SummaryHeader(summary: StatsSummary) {
    val record = recordText(summary.wins, summary.losses, summary.draws)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryTile(stringResource(R.string.stats_tile_games), summary.games.toString())
        SummaryTile(stringResource(R.string.stats_tile_matches), summary.matches.toString())
        SummaryTile(stringResource(R.string.stats_tile_record), record)
    }
}

@Composable
private fun SummaryTile(label: String, value: String) {
    ElevatedCard(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SortChips(selected: StatsSort, onSelect: (StatsSort) -> Unit) {
    val sortLabel = stringResource(R.string.stats_sort_label)
    Column {
        Text(sortLabel, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
            StatsSort.values().forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            when (option) {
                                StatsSort.RECENT -> stringResource(R.string.stats_sort_recent)
                                StatsSort.MOST_PLAYED -> stringResource(R.string.stats_sort_most_played)
                                StatsSort.A_TO_Z -> stringResource(R.string.stats_sort_az)
                            }
                        )
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
    }
}

@Composable
private fun StatRow(stats: GameStats) {
    val record = recordText(stats.wins, stats.losses, stats.draws)
    val played = matchesText(stats.matchesPlayed)
    val description = stringResource(R.string.stats_row_description, stats.displayName, played, record)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stats.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(played, style = MaterialTheme.typography.labelSmall)
            if (stats.winStreak >= 2) {
                Text(stringResource(R.string.streak_game_wins, stats.winStreak), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(record, style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider()
}

/** Same per-game record as [StatRow], boxed as a grid cell for the MEDIUM/EXPANDED column layout. */
@Composable
private fun StatCard(stats: GameStats, modifier: Modifier = Modifier) {
    val record = recordText(stats.wins, stats.losses, stats.draws)
    val played = matchesText(stats.matchesPlayed)
    val description = stringResource(R.string.stats_row_description, stats.displayName, played, record)
    ElevatedCard(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stats.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(played, style = MaterialTheme.typography.labelSmall)
            if (stats.winStreak >= 2) {
                Text(stringResource(R.string.streak_game_wins, stats.winStreak), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(record, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Day streak and cross-game win streak tiles. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun StreakHeader(streaks: StreakSummary) {
    Text(
        stringResource(R.string.streak_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryTile(
            stringResource(R.string.streak_tile_days),
            "${streaks.dayStreak} (${stringResource(R.string.streak_best, streaks.bestDayStreak)})"
        )
        SummaryTile(
            stringResource(R.string.streak_tile_wins),
            "${streaks.overallWinStreak} (${stringResource(R.string.streak_best, streaks.bestOverallWinStreak)})"
        )
    }
}

@Composable
internal fun achievementTitle(id: String): String = stringResource(
    when (id) {
        Achievements.FIRST_WIN -> R.string.achv_first_win_title
        Achievements.WINS_10 -> R.string.achv_wins_10_title
        Achievements.WINS_100 -> R.string.achv_wins_100_title
        Achievements.STREAK_5 -> R.string.achv_streak_5_title
        Achievements.HARD_WIN -> R.string.achv_hard_win_title
        Achievements.DAILY -> R.string.achv_daily_title
        Achievements.ALL_FAMILIES -> R.string.achv_all_families_title
        Achievements.ONLINE_WIN -> R.string.achv_online_win_title
        Achievements.DAYS_7 -> R.string.achv_days_7_title
        else -> R.string.achv_games_10_title
    }
)

@Composable
private fun achievementDescription(id: String): String = stringResource(
    when (id) {
        Achievements.FIRST_WIN -> R.string.achv_first_win_desc
        Achievements.WINS_10 -> R.string.achv_wins_10_desc
        Achievements.WINS_100 -> R.string.achv_wins_100_desc
        Achievements.STREAK_5 -> R.string.achv_streak_5_desc
        Achievements.HARD_WIN -> R.string.achv_hard_win_desc
        Achievements.DAILY -> R.string.achv_daily_desc
        Achievements.ALL_FAMILIES -> R.string.achv_all_families_desc
        Achievements.ONLINE_WIN -> R.string.achv_online_win_desc
        Achievements.DAYS_7 -> R.string.achv_days_7_desc
        else -> R.string.achv_games_10_desc
    }
)

/**
 * Every achievement: unlocked ones show a check mark and "Unlocked"; locked ones are dimmed AND carry a
 * lock glyph with the word "Locked" plus a done / goal count, so state never depends on colour alone.
 */
@Composable
private fun AchievementsSection(rows: List<AchievementRow>) {
    Text(
        stringResource(R.string.achv_section_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        stringResource(R.string.achv_summary, rows.count { it.unlocked }, rows.size),
        style = MaterialTheme.typography.labelMedium
    )
    Spacer(Modifier.height(8.dp))
    rows.forEach { row ->
        val title = achievementTitle(row.id)
        val description = achievementDescription(row.id)
        val spoken = if (row.unlocked) {
            stringResource(R.string.achv_row_description_unlocked, title, description)
        } else {
            stringResource(R.string.achv_row_description_locked, title, description, row.done, row.goal)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp).alpha(if (row.unlocked) 1f else 0.6f)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (row.unlocked) "✓ " + stringResource(R.string.achv_unlocked_label)
                    else "🔒 " + stringResource(R.string.achv_locked_label),
                    style = MaterialTheme.typography.labelMedium
                )
                if (!row.unlocked && row.goal > 1) {
                    Text(
                        stringResource(R.string.achv_progress, row.done, row.goal),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
