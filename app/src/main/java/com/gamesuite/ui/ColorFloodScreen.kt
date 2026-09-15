package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.colorflood.ColorFloodGame
import com.gamesuite.games.colorflood.ColorFloodRecord
import com.gamesuite.games.colorflood.ColorFloodStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders ColorFloodGame's state reactively — same overall shape as
 * LightsOutScreen (difficulty selector, live status row, finished-board
 * panel), but the board itself is pure DISPLAY here: every tap happens on
 * the color swatches below it, not the grid, since a cell's color is what
 * you're choosing FROM, not a thing you act ON directly.
 *
 * VISUAL IDENTITY: chrome (background/text/accent) shares its warm tokens
 * with MinesweeperScreen/SudokuScreen/LightsOutScreen/DotsAndBoxesScreen
 * (see [colorFloodPalette]). The CELL colors themselves are a deliberate
 * exception — this puzzle's entire mechanic depends on genuinely
 * distinguishable hues, so [colorFloodPalette.colors] spans real variety
 * (not shades of one warm tone) the same way Minesweeper's own
 * per-adjacent-count number colors already do, rather than forcing content
 * that needs contrast into a monochrome identity it can't play well in.
 *
 * JUICE: this puzzle has exactly ONE moment worth celebrating — flooding the
 * whole board — so it gets the full [com.gamesuite.ui.effects.CameraShake]/
 * [com.gamesuite.ui.effects.ParticleBurst] treatment on that single `s.won`
 * transition (a real jolt, not the smaller mid-game nudge other games use
 * for a lesser moment), matching the reserved-for-the-biggest-beat spirit
 * [HapticSignal.CELEBRATION] (already wired here) already follows. The
 * board itself is a plain `Column` of `Row`s of solid-color `Box`es, not a
 * single `Canvas`, so the burst needs its own overlay `Canvas` sized to the
 * grid via `onGloballyPositioned` — the same "no existing Canvas to draw
 * into" situation [ParticleBurst]'s own KDoc calls out, solved the same way.
 */
@Composable
fun ColorFloodScreen(
    sessionManager: GameSessionManager,
    game: ColorFloodGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see ColorFloodGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    // A separate call from `sounds` above deliberately -- CardSounds is this screen's own
    // sample-based tap/place sound, not a general SFX vocabulary; ProceduralSfx is the ALREADY
    // shared system TowerDefenceScreen/CheckersScreen (etc.) already use for a win chime, so
    // reusing it here is following the existing convention, not adding a second parallel one.
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.COLOR_FLOOD, enabled = musicEnabled)
    val statsStore = remember { ColorFloodStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = colorFloodPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val cameraShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    // Win-moment pixel constants, converted once here (same "with(LocalDensity.current) {...}
    // once" idiom TowerDefenceScreen's own `shakeMagnitudePx` uses) rather than re-converting
    // inline at every use below.
    val density = LocalDensity.current
    val winShakeMagnitudePx = with(density) { WIN_SHAKE_MAGNITUDE_DP.dp.toPx() }
    val winBurstMinSpeedPx = with(density) { WIN_BURST_MIN_SPEED_DP.dp.toPx() }
    val winBurstMaxSpeedPx = with(density) { WIN_BURST_MAX_SPEED_DP.dp.toPx() }
    val winBurstGravityPx = with(density) { WIN_BURST_GRAVITY_DP.dp.toPx() }
    // The board's own real pixel size, captured off the grid Column below via
    // onGloballyPositioned -- this board is a plain Column/Row of solid-color Boxes, not a
    // single Canvas with its own normalized coordinate convention (c.f. TowerDefenceScreen's
    // `toPx()`), so there's no existing "board space" to reuse; real pixels captured once the
    // layout settles are the simplest correct source for "the board's center" a win-burst needs.
    var boardSizePx by remember { mutableStateOf(Offset.Zero) }

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
    // Keyed on the actual board (unique per round, stable across moves of the SAME round) --
    // `.size` alone doesn't change between two rounds of the same difficulty, which let round
    // 1's result silently keep showing on every later round -- the same fix WordGuess's own
    // reportedResult already applies (see that screen's own comment), mirrored here.
    var reportedResult by remember(s.cellColors, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's
    // own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.size, s.moves == 0) { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.isOver) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.isOver) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }

    LaunchedEffect(s.won) {
        if (s.won && reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: liveElapsedMillis
            val result = statsStore.recordSolve(game.difficulty, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)

            // Launched rather than awaited (see CheckersScreen's own capture-shake call site for
            // the same idiom) so the shake's own ~300ms decay never delays anything else this
            // effect does -- there's nothing sequenced after it here, but a future edit adding
            // one shouldn't silently start waiting on a shake it doesn't need to.
            launch { cameraShake.trigger(durationMs = WIN_SHAKE_DECAY_MS, easing = FastOutSlowInEasing) }

            // The flood's own final color plus 1-2 hue-neighbors from this puzzle's own active
            // palette (wrapping mod colorCount, not the full 6-swatch list -- a board playing
            // with only 4 colors has no business bursting a 5th/6th color nobody ever saw) reads
            // as "this board's own colors celebrating," not a generic confetti overlay.
            val winIndex = s.currentColor
            val burstColors = (listOf(winIndex) + listOf(
                (winIndex + 1) % s.colorCount,
                (winIndex - 1 + s.colorCount) % s.colorCount
            ).distinct().filterNot { it == winIndex }).map { palette.colors[it] }

            if (boardSizePx != Offset.Zero) {
                particleBurst.spawn(
                    origin = Offset(boardSizePx.x / 2f, boardSizePx.y / 2f),
                    count = WIN_BURST_PARTICLE_COUNT,
                    colors = burstColors,
                    speedRange = winBurstMinSpeedPx..winBurstMaxSpeedPx,
                    lifeRangeSeconds = 0.6f..1.1f,
                    gravity = winBurstGravityPx
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabs(
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

        StatusRow(
            moves = s.moves,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            onNewBoard = { game.startMatch() },
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f, fill = false)
                // Shakes the grid AND the particle overlay below together, as one physical
                // board -- see this file's own class KDoc JUICE paragraph for why this is the
                // one moment in this puzzle that earns a real jolt rather than a small nudge.
                .cameraShake(cameraShake, magnitudePx = winShakeMagnitudePx)
                .onGloballyPositioned { boardSizePx = Offset(it.size.width.toFloat(), it.size.height.toFloat()) }
        ) {
            val cellSize = remember(maxWidth, maxHeight, s.size) {
                minOf(maxWidth / s.size, maxHeight / s.size, 34.dp).coerceAtLeast(14.dp)
            }
            Column {
                for (row in 0 until s.size) {
                    Row {
                        for (col in 0 until s.size) {
                            val index = row * s.size + col
                            ColorFloodCellView(colorIndex = s.cellColors[index], size = cellSize, palette = palette)
                        }
                    }
                }
            }

            // Overlay-only Canvas purely for the win burst's motes -- see [ParticleBurst]'s own
            // KDoc and this file's class KDoc JUICE paragraph for why this board (a plain
            // Column/Row of Boxes, not a single Canvas) needs one. matchParentSize() ties its
            // pixel space directly to the grid Column above it, so `boardSizePx` (captured off
            // that same BoxWithConstraints) lines up with what gets drawn here with no separate
            // conversion.
            Canvas(modifier = Modifier.matchParentSize()) {
                for (particle in particleBurst.particles.value) {
                    drawCircle(
                        color = particle.color.copy(alpha = particle.lifeFraction),
                        radius = WIN_BURST_PARTICLE_RADIUS_DP.dp.toPx() * particle.lifeFraction.coerceAtLeast(0.3f),
                        center = particle.pos
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        ColorSwatches(
            colorCount = s.colorCount,
            currentColor = s.currentColor,
            palette = palette,
            enabled = !s.isOver,
            onPick = { colorIndex ->
                game.pick(colorIndex)
                sounds.playTap()
                haptics(HapticSignal.NORMAL_ACTION)
            }
        )

        if (s.isOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(
                moves = s.moves,
                record = record,
                isNewBestMoves = reportedResult?.first ?: false,
                isNewBestTime = reportedResult?.second ?: false,
                onNewBoard = { game.startMatch() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Win-moment JUICE tuning -- see this file's own class KDoc JUICE paragraph. Sized deliberately
// bigger than a mid-game nudge (c.f. TowerDefenceScreen's 8dp life-lost shake, CheckersScreen's
// 4dp capture jitter): flooding the whole board is the ONLY celebratory moment this puzzle has.
// ---------------------------------------------------------------------------
private const val WIN_SHAKE_MAGNITUDE_DP = 14f
private const val WIN_SHAKE_DECAY_MS = 320
private const val WIN_BURST_PARTICLE_COUNT = 32
private const val WIN_BURST_PARTICLE_RADIUS_DP = 5f
private const val WIN_BURST_MIN_SPEED_DP = 220f
private const val WIN_BURST_MAX_SPEED_DP = 460f
private const val WIN_BURST_GRAVITY_DP = 520f

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why [colors] itself is the one
// deliberate exception, spanning real hue variety rather than warm shades.
// ---------------------------------------------------------------------------
private data class ColorFloodPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val colors: List<Color> // index 0..5, matches ColorFloodGame's own color indices
)

@Composable
private fun colorFloodPalette(isDark: Boolean): ColorFloodPalette = if (!isDark) {
    ColorFloodPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        colors = listOf(
            Color(0xFFD9573F), // terracotta red
            Color(0xFFE0A83E), // golden yellow
            Color(0xFF5B9A5B), // leaf green
            Color(0xFF3E7A9E), // ocean blue
            Color(0xFF8A5FA0), // plum purple
            Color(0xFFC9628F)  // warm rose
        )
    )
} else {
    ColorFloodPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        colors = listOf(
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
private fun DifficultyTabs(current: CpuDifficulty, palette: ColorFloodPalette, onSelect: (CpuDifficulty) -> Unit) {
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
private fun StatusRow(
    moves: Int,
    elapsedMillis: Long,
    onNewBoard: () -> Unit,
    palette: ColorFloodPalette
) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Moves: $moves", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            "%d:%02d".format(minutes, seconds),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold
        )
        OutlinedButton(onClick = onNewBoard) { Text("↻") }
    }
}

@Composable
private fun ColorFloodCellView(colorIndex: Int, size: Dp, palette: ColorFloodPalette) {
    // Deliberately no padding/rounding/gap here -- a solid, edge-to-edge
    // mosaic is this puzzle's own visual identity, unlike every other
    // board in this batch which separates cells with a small gap.
    Box(modifier = Modifier.size(size).background(palette.colors[colorIndex]))
}

@Composable
private fun ColorSwatches(
    colorCount: Int,
    currentColor: Int,
    palette: ColorFloodPalette,
    enabled: Boolean,
    onPick: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in 0 until colorCount) {
            val isCurrent = c == currentColor
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(palette.colors[c])
                    .then(if (isCurrent) Modifier.border(3.dp, palette.textPrimary, CircleShape) else Modifier)
                    .then(if (enabled && !isCurrent) Modifier.clickable { onPick(c) } else Modifier)
            )
        }
    }
}

@Composable
private fun FinishedPanel(
    moves: Int,
    record: ColorFloodRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: ColorFloodPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Board flooded!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                Button(onClick = onNewBoard) { Text("New Board") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
