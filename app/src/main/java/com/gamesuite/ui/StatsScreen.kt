package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import com.gamesuite.stats.GameStats
import com.gamesuite.stats.StatsViewModel

/**
 * "My Stats" — the fix for the audited finding that every game already computes a correct
 * result at match end but nothing kept it past the current session (see
 * GameSessionManager.lastMatchOutcome / StatsRepository). Deliberately one shared, generic
 * layout across every game (matches played + win/loss/draw) rather than 11 bespoke
 * scoreboards — see GameStats.kt's KDoc for why.
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val stats by viewModel.allStats.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }

    // Fold/Tab compatibility audit finding: on a Fold unfolded (MEDIUM width) or a
    // Tab S9 (EXPANDED width), the plain one-per-row stat list below stretches its
    // name/record SpaceBetween Row edge to edge, leaving a huge empty gap between the
    // two — same "wasted horizontal margin" problem as MainMenuScreen's game list, just
    // via stretching instead of a single skinny column. COMPACT (phone, Fold cover
    // screen) keeps today's plain divided list unchanged; MEDIUM/EXPANDED reflow the
    // same per-game stats into a real 2-3 column card grid instead.
    val widthSizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val statColumns = when (widthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> 3
        WindowWidthSizeClass.MEDIUM -> 2
        else -> 1
    }

    // Wide-window fix, same widthIn(max = 840.dp)-before-fillMaxWidth() centering trick
    // as MainMenuScreen/AdaptiveTwoPane — caps the whole screen to a sane reading width
    // on a Tab S9 landscape or Fold-unfolded window instead of stretching every row.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            Spacer(Modifier.height(8.dp))
            Text("My Stats", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))

            if (stats.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Play a match to start tracking your stats — every game you finish here builds " +
                        "your all-time record.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val totalMatches = stats.values.sumOf { it.matchesPlayed }
                val totalWins = stats.values.sumOf { it.wins }
                Text(
                    "$totalMatches matches played across ${stats.size} game${if (stats.size == 1) "" else "s"}, $totalWins wins overall.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))

                val sortedStats = stats.values.sortedByDescending { it.lastPlayedEpochMillis }
                if (statColumns == 1) {
                    // COMPACT — unchanged from before this pass.
                    sortedStats.forEach { s ->
                        StatRow(s)
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    val rows = sortedStats.chunked(statColumns)
                    rows.forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { s -> StatCard(s, modifier = Modifier.weight(1f)) }
                            // A short final row (e.g. 5 games ÷ 2 columns leaves 1) gets weighted
                            // spacers in the empty slots so its real card(s) don't stretch wider
                            // than every other row's — same trick as MainMenuScreen's GameSection.
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
                OutlinedButton(onClick = { confirmingReset = true }) {
                    Text("Reset stats")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset all stats?") },
            text = { Text("This permanently clears every game's win/loss record. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    confirmingReset = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatRow(stats: GameStats) {
    val record = "${stats.wins}W – ${stats.losses}L" + if (stats.draws > 0) " – ${stats.draws}D" else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = "${stats.displayName}: ${stats.matchesPlayed} matches played, $record"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(stats.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${stats.matchesPlayed} match${if (stats.matchesPlayed == 1) "" else "es"} played",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(record, style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider()
}

/**
 * Same per-game record as [StatRow], boxed as a grid cell for the MEDIUM/EXPANDED
 * (Fold-unfolded/Tab S9) column layout above instead of a full-width divided row —
 * name/match-count stacked over the W-L-D record rather than side by side, since a
 * grid cell is narrower than a full-width row.
 */
@Composable
private fun StatCard(stats: GameStats, modifier: Modifier = Modifier) {
    val record = "${stats.wins}W – ${stats.losses}L" + if (stats.draws > 0) " – ${stats.draws}D" else ""
    ElevatedCard(
        modifier = modifier.semantics {
            contentDescription = "${stats.displayName}: ${stats.matchesPlayed} matches played, $record"
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stats.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${stats.matchesPlayed} match${if (stats.matchesPlayed == 1) "" else "es"} played",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(record, style = MaterialTheme.typography.titleMedium)
        }
    }
}
