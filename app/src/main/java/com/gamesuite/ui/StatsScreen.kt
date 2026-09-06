package com.gamesuite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(
        modifier = Modifier
            .fillMaxSize()
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

            stats.values.sortedByDescending { it.lastPlayedEpochMillis }.forEach { s ->
                StatRow(s)
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { confirmingReset = true }) {
                Text("Reset stats")
            }
        }
        Spacer(Modifier.height(24.dp))
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
