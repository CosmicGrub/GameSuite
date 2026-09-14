package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.edgematch.EdgeMatchGame
import com.gamesuite.games.edgematch.EdgeMatchRecord
import com.gamesuite.games.edgematch.EdgeMatchStatsStore
import com.gamesuite.games.edgematch.EdgeMatchTile
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * Renders EdgeMatchGame's state reactively — same overall shape as
 * ColorFloodScreen/LightsOutScreen (difficulty selector, live status row,
 * finished-puzzle panel). Each tile is drawn as 4 colored triangular
 * wedges (one per edge) meeting at its center — the standard edge-matching
 * genre visual, letting a player see at a glance whether a tile's edge
 * actually matches its neighbor's without needing numbers/labels.
 *
 * The ONLY player action is tapping a tile to rotate it 90° clockwise in
 * place — see EdgeMatchGame's own class KDoc (CORE MECHANIC) for why there's
 * no placement/swapping, per docs/EDGE_MATCH_DESIGN.md. Every currently-
 * matching interior edge is highlighted LIVE, recomputed via
 * [EdgeMatchGame.matchingDirections] on every recomposition — the design
 * doc's own called-for "full information, no hidden state" feedback, same
 * spirit as Sudoku flagging a wrong entry immediately.
 *
 * VISUAL IDENTITY: chrome shares its warm tokens with the rest of this
 * batch (see [edgeMatchPalette]); the EDGE PATTERN colors are the one
 * deliberate exception, spanning real hue variety for the same reason
 * Color Flood's own cell colors do — this puzzle's whole mechanic depends
 * on genuinely distinguishable edges.
 */
@Composable
fun EdgeMatchScreen(
    sessionManager: GameSessionManager,
    game: EdgeMatchGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see EdgeMatchGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { EdgeMatchStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = edgeMatchPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name]
    var reportedResult by remember(s.tiles.size, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.size, s.moves == 0) { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.solved) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.solved) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }

    LaunchedEffect(s.solved) {
        if (s.solved && reportedResult == null) {
            val finalTime = game.solvedElapsedMillis.value ?: liveElapsedMillis
            val result = statsStore.recordSolve(game.difficulty, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
            haptics(HapticSignal.CELEBRATION)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabsEdgeMatch(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        EdgeMatchStatusRow(
            moves = s.moves,
            elapsedMillis = game.solvedElapsedMillis.value ?: liveElapsedMillis,
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val cellSize = remember(maxWidth, maxHeight, s.size) {
                minOf(maxWidth / s.size, maxHeight / s.size, 76.dp).coerceAtLeast(20.dp)
            }
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.boardFrame)
                    .padding(3.dp)
            ) {
                for (row in 0 until s.size) {
                    Row {
                        for (col in 0 until s.size) {
                            val index = row * s.size + col
                            EdgeMatchTileView(
                                tile = s.tiles[index],
                                matching = game.matchingDirections(index),
                                tileSize = cellSize,
                                palette = palette,
                                onTap = {
                                    game.tapTile(index)
                                    sounds.playTap()
                                    haptics(HapticSignal.NORMAL_ACTION)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (s.solved) {
            Spacer(Modifier.height(16.dp))
            EdgeMatchFinishedPanel(
                moves = s.moves,
                record = record,
                isNewBestMoves = reportedResult?.first ?: false,
                isNewBestTime = reportedResult?.second ?: false,
                onNewPuzzle = { game.startMatch() },
                onReset = { game.resetToInitial() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why [edgePatterns] is the one
// deliberate exception, spanning real hue variety rather than warm shades.
// ---------------------------------------------------------------------------
private data class EdgeMatchPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val boardFrame: Color,
    /** Live match-highlight stroke color, drawn on any wedge EdgeMatchGame.matchingDirections() reports as currently matching its neighbor. */
    val matchGlow: Color,
    /** index 0..5 -- sized for HARD's own color count (6, the largest of the three tiers, see EdgeMatchGame.difficultyConfig). */
    val edgePatterns: List<Color>
)

@Composable
private fun edgeMatchPalette(isDark: Boolean): EdgeMatchPalette = if (!isDark) {
    EdgeMatchPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        boardFrame = Color(0xFF3A2E22),
        matchGlow = Color(0xFF3ED18C),
        edgePatterns = listOf(
            Color(0xFFD9573F), // terracotta red
            Color(0xFFE0A83E), // golden yellow
            Color(0xFF5B9A5B), // leaf green
            Color(0xFF3E7A9E), // ocean blue
            Color(0xFF8A5FA0), // plum purple
            Color(0xFFC9628F)  // warm rose
        )
    )
} else {
    EdgeMatchPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        boardFrame = Color(0xFFF3E9DB),
        matchGlow = Color(0xFF4CE0A0),
        edgePatterns = listOf(
            Color(0xFFE0705A),
            Color(0xFFE8BE6C),
            Color(0xFF7ECB98),
            Color(0xFF7FA6D9),
            Color(0xFFB399D9),
            Color(0xFFE099B8)
        )
    )
}

@Composable
private fun DifficultyTabsEdgeMatch(current: CpuDifficulty, palette: EdgeMatchPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.chipBackground)
                    .clickable { onSelect(tier) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) palette.textOnAccent else palette.textPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun EdgeMatchStatusRow(moves: Int, elapsedMillis: Long, palette: EdgeMatchPalette) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Moves: $moves", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text("%d:%02d".format(minutes, seconds), color = palette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

/**
 * One tile, drawn as 4 triangular wedges (one per [EdgeMatchGame.TOP]/
 * [EdgeMatchGame.RIGHT]/[EdgeMatchGame.BOTTOM]/[EdgeMatchGame.LEFT]) meeting
 * at its center. Any wedge whose direction is in [matching] (see
 * [EdgeMatchGame.matchingDirections]) gets an extra bright stroke traced
 * along its own outer edge — the live "this side already matches its
 * neighbor" feedback docs/EDGE_MATCH_DESIGN.md calls for.
 */
@Composable
private fun EdgeMatchTileView(tile: EdgeMatchTile, matching: Set<Int>, tileSize: Dp, palette: EdgeMatchPalette, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(tileSize)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onTap)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = center.x
            val cy = center.y
            val strokeWidth = w * 0.06f

            val wedges = listOf(
                EdgeMatchGame.TOP to Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(cx, cy); close() },
                EdgeMatchGame.RIGHT to Path().apply { moveTo(w, 0f); lineTo(w, h); lineTo(cx, cy); close() },
                EdgeMatchGame.BOTTOM to Path().apply { moveTo(w, h); lineTo(0f, h); lineTo(cx, cy); close() },
                EdgeMatchGame.LEFT to Path().apply { moveTo(0f, h); lineTo(0f, 0f); lineTo(cx, cy); close() }
            )
            for ((direction, path) in wedges) {
                drawPath(path, color = palette.edgePatterns[tile.currentEdge(direction)])
            }
            for ((direction, path) in wedges) {
                if (direction in matching) {
                    drawPath(path, color = palette.matchGlow, style = Stroke(width = strokeWidth))
                }
            }
        }
    }
}

@Composable
private fun EdgeMatchFinishedPanel(
    moves: Int,
    record: EdgeMatchRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewPuzzle: () -> Unit,
    onReset: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: EdgeMatchPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Every edge matches!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Moves: $moves", style = MaterialTheme.typography.bodyMedium)
            if (isNewBestMoves) {
                Spacer(Modifier.height(4.dp))
                Text("New best move count!", color = palette.accent, fontWeight = FontWeight.Bold)
            } else if (record?.bestMoves != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best moves: ${record.bestMoves}", style = MaterialTheme.typography.bodyMedium)
            }
            if (isNewBestTime) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", color = palette.accent, fontWeight = FontWeight.Bold)
            } else if (record?.bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                val m = (record.bestTimeMillis / 1000) / 60
                val sec = (record.bestTimeMillis / 1000) % 60
                Text("Best time: %d:%02d".format(m, sec), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewPuzzle) { Text("New Puzzle") }
                OutlinedButton(onClick = onReset) { Text("Reset") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
        }
    }
}
