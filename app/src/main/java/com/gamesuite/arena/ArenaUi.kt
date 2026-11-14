package com.gamesuite.arena

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.core.GameSessionManager

/** Seat colours of the scoreboard strip: Computer A orange, Computer B blue (the colours Slide and Climb gives its first two seats; also named in words, never colour alone). */
val ArenaColorA = Color(0xFFD55E00)
val ArenaColorB = Color(0xFF0072B2)

internal fun arenaGameName(context: Context, gameId: String): String = context.getString(
    when (gameId) {
        "chutes-race" -> R.string.arena_game_chutes_race
        "bridge-builders" -> R.string.arena_game_bridge_builders
        "chess" -> R.string.arena_game_chess
        "checkers" -> R.string.arena_game_checkers
        "reversi" -> R.string.arena_game_reversi
        "connect-four" -> R.string.arena_game_connect_four
        else -> R.string.arena_title
    }
)

internal fun arenaLevelName(context: Context, level: ArenaLevel): String = context.getString(
    when (level) {
        ArenaLevel.EASY -> R.string.arena_level_easy
        ArenaLevel.MEDIUM -> R.string.arena_level_medium
        ArenaLevel.HARD -> R.string.arena_level_hard
    }
)

private fun computerName(context: Context, side: Int): String =
    context.getString(if (side == 0) R.string.arena_computer_a else R.string.arena_computer_b)

/**
 * The persistent strip above the game while an arena runs: seat colours + scores, "Game N of M", the levels, and
 * the controls (pause / resume, speed, stop). Sits in the layout (not over the game), so it never hides the board.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArenaStrip(session: ArenaSession, sessionManager: GameSessionManager, onStop: () -> Unit, onAgain: () -> Unit) {
    val context = LocalContext.current
    val cfg = session.config
    val b = session.board
    val lvA = arenaLevelName(context, cfg.levelA)
    val lvB = arenaLevelName(context, cfg.levelB)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(arenaGameName(context, cfg.gameId), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (session.cooperative) {
                Text(stringResource(R.string.arena_strip_robots, lvA, lvB), fontSize = 14.sp)
                Text(stringResource(R.string.arena_strip_built, b.teamBuilt), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(stringResource(R.string.arena_strip_failed, b.teamFailed), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                SeatScore(ArenaColorA, stringResource(R.string.arena_strip_a, lvA, b.aWins))
                SeatScore(ArenaColorB, stringResource(R.string.arena_strip_b, lvB, b.bWins))
                Text(stringResource(R.string.arena_strip_draws, b.draws), fontSize = 14.sp)
            }
            Text(
                if (cfg.endless) stringResource(R.string.arena_strip_game_endless, session.gameNumber)
                else stringResource(R.string.arena_strip_game, session.gameNumber, cfg.games),
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
            if (session.paused) Text(stringResource(R.string.arena_paused), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (session.done) {
                StripButton(stringResource(R.string.arena_again), onAgain)
                StripButton(stringResource(R.string.arena_close), onStop)
            } else {
                StripButton(stringResource(if (session.paused) R.string.arena_resume else R.string.arena_pause)) {
                    ArenaHost.togglePause(sessionManager)
                }
                StripButton(stringResource(R.string.arena_speed, session.speed.label)) { session.speed = session.speed.next() }
                StripButton(stringResource(R.string.arena_stop), onStop)
            }
        }
        val status = resultLine(context, session)
        if (status != null) {
            Text(
                status, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

private fun resultLine(context: Context, s: ArenaSession): String? {
    val out = s.lastOutcome ?: return null
    if (s.phase == ArenaPhase.PLAYING || s.phase == ArenaPhase.STARTING) return null
    val n = s.board.played
    val result = when (out) {
        is ArenaOutcome.Winner -> context.getString(
            if (ArenaSeries.sideOf(out.seat) == 0) R.string.arena_result_a else R.string.arena_result_b, n
        )
        ArenaOutcome.Draw -> context.getString(R.string.arena_result_draw, n)
        is ArenaOutcome.Team -> context.getString(if (out.succeeded) R.string.arena_result_built else R.string.arena_result_failed, n)
    }
    if (s.done) {
        val b = s.board
        val summary = if (s.cooperative) context.getString(R.string.arena_done_coop, b.teamBuilt, b.teamFailed)
        else context.getString(R.string.arena_done_summary, b.aWins, b.bWins, b.draws)
        return result + " " + context.getString(R.string.arena_done) + ": " + summary
    }
    return if (s.cooperative) result
    else result + " " + context.getString(R.string.arena_next_first, computerName(context, ArenaSeries.sideOf(s.firstSeat)))
}

@Composable
private fun SeatScore(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(14.dp).background(color, CircleShape))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun StripButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 14.sp) }
}

/** The "Computer Arena" card on the Games screen (adults only: the Games tab is not reachable from a kids profile). */
@Composable
fun ArenaEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.arena_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.arena_card_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Setup: pick a game, the two computer levels and the number of games, then Start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArenaSetupScreen(onStart: (ArenaConfig) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var gameId by rememberSaveable { mutableStateOf(ArenaGames.specs.first().gameId) }
    var a by rememberSaveable { mutableStateOf(ArenaLevel.HARD.name) }
    var b by rememberSaveable { mutableStateOf(ArenaLevel.EASY.name) }
    var games by rememberSaveable { mutableStateOf(5) }
    val spec = ArenaGames.specs.first { it.gameId == gameId }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.arena_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.arena_subtitle), style = MaterialTheme.typography.bodyLarge)

            Text(stringResource(R.string.arena_pick_game), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArenaGames.specs.forEach { g ->
                    FilterChip(
                        selected = gameId == g.gameId, onClick = { gameId = g.gameId },
                        label = { Text(arenaGameName(context, g.gameId)) }, modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            if (spec.cooperative) Text(stringResource(R.string.arena_note_coop), style = MaterialTheme.typography.bodyMedium)
            if (spec.gameId == "chutes-race") Text(stringResource(R.string.arena_note_slide), style = MaterialTheme.typography.bodyMedium)

            LevelPicker(stringResource(R.string.arena_pick_a), ArenaColorA, a) { a = it }
            LevelPicker(stringResource(R.string.arena_pick_b), ArenaColorB, b) { b = it }

            Text(stringResource(R.string.arena_pick_games), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 5, 10, 0).forEach { n ->
                    FilterChip(
                        selected = games == n, onClick = { games = n },
                        label = { Text(if (n == 0) stringResource(R.string.arena_games_endless) else n.toString()) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onStart(ArenaConfig(gameId, ArenaLevel.valueOf(a), ArenaLevel.valueOf(b), games)) },
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text(stringResource(R.string.arena_start)) }
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.arena_back)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelPicker(title: String, color: Color, selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArenaLevel.entries.forEach { level ->
                FilterChip(
                    selected = selected == level.name, onClick = { onSelect(level.name) },
                    label = { Text(arenaLevelName(context, level)) }, modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

/** Runs the series loop for [session] while composed. */
@Composable
fun ArenaDriver(session: ArenaSession, sessionManager: GameSessionManager) {
    LaunchedEffect(session) { ArenaHost.drive(session, sessionManager) }
}
