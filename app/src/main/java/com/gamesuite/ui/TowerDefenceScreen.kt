package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.towerdefence.TowerDefenceGame
import com.gamesuite.games.towerdefence.TowerDefenceStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import kotlin.math.roundToInt

/**
 * Renders TowerDefenceGame's state reactively — see [TowerDefenceGame]'s own class KDoc for the
 * full design reasoning (fixed paths, 1 upgradeable tower type, a real pause). Real-time loop,
 * same shape as [BreakoutScreen]/[AirHockeyScreen]'s own (a `withFrameNanos` loop stepping
 * `game.tick(dt)` every frame) — `docs/TOWER_DEFENCE_ADR.md`'s own reason for staying on this
 * infrastructure rather than a new game-loop dependency. The loop keeps running even while
 * [TowerDefenceGame.TowerDefenceState.paused] is true; `tick()` itself is the no-op, not this
 * loop, so resuming is instant with no separate "restart the loop" step.
 *
 * INTERACTION: a single tap on a tower zone places a tower there if it's empty and affordable, or
 * upgrades the tower already there if it's occupied — no separate "select, then act" step, kept
 * intentionally simple for this game's one tower type. Level and difficulty are both read
 * directly from `game.state.value` (not shadowed by separate remembered UI state) since
 * [TowerDefenceGame.TowerDefenceState] already carries both — switching either calls
 * `game.startMatch()` fresh, same "change the var, replay" idiom [BreakoutScreen]'s own
 * difficulty tabs use.
 *
 * VISUAL IDENTITY: chrome shares this batch's warm tokens (see [towerDefencePalette]); the path,
 * tower zones, enemies, and projectiles are the one deliberate exception, same "authentic
 * exception" reasoning as every other real-time/arcade game's own play-element colors in this
 * batch (Breakout's ball/paddle/bricks, Color Flood's cells, Edge Match's edges).
 *
 * JUICE: the first real consumer of the shared [com.gamesuite.ui.effects.CameraShake]/
 * [com.gamesuite.ui.effects.ParticleBurst] utilities — see those files' own KDoc for why they
 * exist (generalizing five independent hand-rolled camera-shake implementations and three
 * independent particle systems already shipped elsewhere in this app into two shared
 * primitives). A life lost jolts the whole board briefly (paired with the existing FAILURE
 * haptic/buzz); a kill spawns a small burst of motes at the enemy's own death position (paired
 * with the gold it just earned). This screen had none of this before — a natural, low-risk
 * first wiring since it's new polish on a game with zero prior juice, not a migration of an
 * already-shipped, already-tuned effect.
 */
@Composable
fun TowerDefenceScreen(
    sessionManager: GameSessionManager,
    game: TowerDefenceGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current
    rememberAmbientMusic(profile = MusicProfiles.TOWER_DEFENCE, enabled = musicEnabled)
    val statsStore = remember { TowerDefenceStatsStore(androidContext) }
    val state by game.state
    val matchOver by game.matchOver
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = towerDefencePalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val textMeasurer = rememberTextMeasurer()
    val cameraShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    val shakeMagnitudePx = with(LocalDensity.current) { 8.dp.toPx() }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    if (matchOver) return

    val bestWaves by statsStore.bestWaves.collectAsState(initial = emptyMap())
    val bestWave = bestWaves["${state.level.id}:${state.difficulty.name}"]
    var recordedForRunSeq by remember { mutableStateOf(-1) }
    var reportedNewBest by remember { mutableStateOf(false) }

    // The physics/simulation frame loop -- same shape as BreakoutScreen's own. tick() itself
    // no-ops while paused (see the class KDoc), so this loop simply keeps running unconditionally.
    LaunchedEffect(matchOver) {
        if (matchOver) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (!game.matchOver.value) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val dt = (nanos - lastFrameNanos) / 1_000_000_000f
                    game.tick(dt)
                }
                lastFrameNanos = nanos
            }
        }
    }

    // Lives lost -- compares against the previous composition's value rather than a seq-numbered
    // event object (this engine has none), same effect for a UI this simple.
    var previousLives by remember(state.runSeq) { mutableStateOf(state.lives) }
    LaunchedEffect(state.lives) {
        if (state.lives < previousLives) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
            cameraShake.trigger()
        }
        previousLives = state.lives
    }

    // A kill spawns a small burst of motes at the enemy's own death position -- see
    // TowerDefenceGame.TowerDefenceEnemyDeathEvent's own KDoc for why this is keyed on `seq`
    // rather than a plain non-null check (the event persists across ticks where nothing new
    // died, same idiom BreakoutGame's own lastBrickBroken already uses). Tuned well below
    // AirHockeyGame's own goal-burst scale (0.35-0.85 speed, 1.4 gravity) since a single small
    // enemy dying is a much smaller beat than a scored goal.
    LaunchedEffect(state.lastEnemyDeath?.seq) {
        val death = state.lastEnemyDeath ?: return@LaunchedEffect
        particleBurst.spawn(
            origin = death.position, count = 8,
            colors = listOf(palette.enemyColor, palette.projectileColor),
            speedRange = 0.04f..0.09f, lifeRangeSeconds = 0.3f..0.45f, gravity = 0.12f
        )
    }

    var previousWave by remember(state.runSeq) { mutableStateOf(state.waveNumber) }
    LaunchedEffect(state.waveNumber) {
        if (state.waveNumber > previousWave) {
            haptics(HapticSignal.LIGHT_TICK)
            playSfx(SfxKind.SUCCESS_CHIME)
        }
        previousWave = state.waveNumber
    }

    LaunchedEffect(state.runResult, state.runSeq) {
        if (state.runResult != null && recordedForRunSeq != state.runSeq) {
            recordedForRunSeq = state.runSeq
            val isNewBest = statsStore.recordWave(state.level.id, state.difficulty, state.waveNumber)
            reportedNewBest = isNewBest
            if (state.runResult == TowerDefenceGame.TowerDefenceRunResult.WON) {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            } else {
                haptics(HapticSignal.FAILURE)
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
        LevelTabs(current = state.level, palette = palette, onSelect = { picked ->
            if (picked.id != game.level.id) {
                game.level = picked
                reportedNewBest = false
                game.startMatch()
            }
        })

        Spacer(Modifier.height(6.dp))

        DifficultyTabsTowerDefence(current = state.difficulty, palette = palette, onSelect = { tier ->
            if (tier != game.difficulty) {
                game.difficulty = tier
                reportedNewBest = false
                game.startMatch()
            }
        })

        Spacer(Modifier.height(10.dp))

        TowerDefenceStatusRow(
            lives = state.lives, gold = state.gold, wave = state.waveNumber, totalWaves = state.totalWaves,
            paused = state.paused, onTogglePause = { game.togglePause() }, palette = palette
        )

        Spacer(Modifier.height(6.dp))

        if (!state.runOver && state.waveInProgress.not() && state.interWaveCooldown > 0f) {
            Text(
                "Next wave in ${state.interWaveCooldown.roundToInt().coerceAtLeast(1)}s — place or upgrade towers now",
                color = palette.textPrimary.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
        }

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val boardSizeDp = minOf(maxWidth, maxHeight)
            Canvas(
                modifier = Modifier
                    .width(boardSizeDp)
                    .height(boardSizeDp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.boardBackground)
                    .cameraShake(cameraShake, magnitudePx = shakeMagnitudePx)
                    .pointerInput(state.level.id) {
                        detectTapOnZone(state.level.towerZones) { zoneIndex ->
                            val s = game.state.value
                            if (s.towers.any { it.zoneIndex == zoneIndex }) {
                                val towerId = s.towers.first { it.zoneIndex == zoneIndex }.id
                                game.upgradeTower(towerId)
                            } else {
                                game.placeTower(zoneIndex)
                            }
                            haptics(HapticSignal.LIGHT_TICK)
                        }
                    }
            ) {
                val w = this.size.width
                val h = this.size.height
                fun toPx(p: Offset) = Offset(p.x * w, p.y * h)

                // Path.
                val path = state.level.path
                for (i in 0 until path.size - 1) {
                    drawLine(
                        color = palette.pathColor,
                        start = toPx(path[i]), end = toPx(path[i + 1]),
                        strokeWidth = w * 0.03f
                    )
                }

                // Tower zones (empty outline, or the placed tower + its upgrade level).
                val zoneRadiusPx = w * 0.045f
                for ((zoneIndex, zone) in state.level.towerZones.withIndex()) {
                    val center = toPx(zone)
                    val tower = state.towers.firstOrNull { it.zoneIndex == zoneIndex }
                    if (tower == null) {
                        drawCircle(color = palette.zoneEmptyColor, radius = zoneRadiusPx, center = center, style = Stroke(width = w * 0.008f))
                    } else {
                        drawCircle(color = palette.towerColor, radius = zoneRadiusPx, center = center)
                        drawCircle(color = palette.towerRangeColor, radius = TowerDefenceGame.towerRange(tower.upgradeLevel) * w, center = center, style = Stroke(width = w * 0.003f))
                        val label = textMeasurer.measure(
                            tower.upgradeLevel.toString(),
                            style = TextStyle(color = palette.textOnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        )
                        drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
                    }
                }

                // Enemies, with a small HP bar above each.
                val enemyRadiusPx = w * 0.022f
                for (enemy in state.enemies) {
                    val pos = TowerDefenceGame.positionAlongPath(path, enemy.distanceTraveled) ?: continue
                    val center = toPx(pos)
                    drawCircle(color = palette.enemyColor, radius = enemyRadiusPx, center = center)
                    val hpFraction = (enemy.hp / enemy.maxHp).coerceIn(0f, 1f)
                    val barWidth = enemyRadiusPx * 2.4f
                    val barTop = Offset(center.x - barWidth / 2f, center.y - enemyRadiusPx * 1.9f)
                    drawRect(color = palette.hpBarBackground, topLeft = barTop, size = Size(barWidth, w * 0.006f))
                    drawRect(color = palette.hpBarFill, topLeft = barTop, size = Size(barWidth * hpFraction, w * 0.006f))
                }

                // Projectiles.
                for (projectile in state.projectiles) {
                    drawCircle(color = palette.projectileColor, radius = w * 0.009f, center = toPx(projectile.position))
                }

                // Kill-burst motes -- see the class KDoc's JUICE section and the particleBurst
                // spawn LaunchedEffect above. Drawn last so a burst never sits under a
                // still-approaching enemy or tower.
                for (particle in particleBurst.particles.value) {
                    drawCircle(
                        color = particle.color.copy(alpha = particle.lifeFraction),
                        radius = w * 0.008f * particle.lifeFraction.coerceAtLeast(0.35f),
                        center = toPx(particle.pos)
                    )
                }
            }
        }

        if (state.runOver) {
            Spacer(Modifier.height(16.dp))
            TowerDefenceFinishedPanel(
                won = state.runResult == TowerDefenceGame.TowerDefenceRunResult.WON,
                waveReached = state.waveNumber,
                totalWaves = state.totalWaves,
                bestWave = bestWave,
                isNewBest = reportedNewBest,
                onPlayAgain = { game.playAgain() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

/** One combined `awaitPointerEventScope` tap-detection loop against a set of normalized-space
 *  zone centers — same "detect a fresh touch-down manually" idiom [BreakoutScreen] uses, but for
 *  a tap-to-act board rather than continuous drag: the nearest zone within a generous touch
 *  radius is picked on pointer-down, matching a real finger's imprecision against small on-screen
 *  circles better than an exact hit-test would. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapOnZone(
    zones: List<Offset>,
    onZoneTapped: (Int) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val nx = down.position.x / size.width
        val ny = down.position.y / size.height
        val nearestIndex = zones.indices.minByOrNull { i ->
            val dx = zones[i].x - nx
            val dy = zones[i].y - ny
            dx * dx + dy * dy
        }
        if (nearestIndex != null) {
            val nearest = zones[nearestIndex]
            val dx = nearest.x - nx
            val dy = nearest.y - ny
            if (dx * dx + dy * dy <= TOUCH_RADIUS_SQUARED) onZoneTapped(nearestIndex)
        }
        waitForUpOrCancellation()
    }
}

/** Generous normalized-space touch radius (0f..1f units) for zone taps — small on-screen circles
 *  are much harder to hit exactly with a real finger than with a mouse cursor. */
private const val TOUCH_RADIUS = 0.08f
private const val TOUCH_RADIUS_SQUARED = TOUCH_RADIUS * TOUCH_RADIUS

@Composable
private fun TowerDefenceStatusRow(
    lives: Int, gold: Int, wave: Int, totalWaves: Int, paused: Boolean,
    onTogglePause: () -> Unit, palette: TowerDefencePalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("❤ $lives", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text("💰 $gold", color = palette.textPrimary)
        Text("Wave $wave/$totalWaves", color = palette.textPrimary)
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (paused) palette.accent else palette.chipBackground)
                .clickable(onClick = onTogglePause)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(if (paused) "Resume" else "Pause", color = if (paused) palette.textOnAccent else palette.textPrimary)
        }
    }
}

@Composable
private fun TowerDefenceFinishedPanel(
    won: Boolean, waveReached: Int, totalWaves: Int, bestWave: Int?, isNewBest: Boolean,
    onPlayAgain: () -> Unit, onBackToMenu: () -> Unit, palette: TowerDefencePalette
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (won) "Victory!" else "Overrun",
            style = MaterialTheme.typography.headlineSmall, color = palette.textPrimary, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text("Reached wave $waveReached of $totalWaves", color = palette.textPrimary)
        if (isNewBest) {
            Text("New best!", color = palette.accent, fontWeight = FontWeight.Bold)
        } else if (bestWave != null) {
            Text("Best: wave $bestWave", color = palette.textPrimary.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayAgain) { Text("Play Again") }
            OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
        }
    }
}

@Composable
private fun LevelTabs(current: TowerDefenceGame.TowerDefenceLevel, palette: TowerDefencePalette, onSelect: (TowerDefenceGame.TowerDefenceLevel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in TowerDefenceGame.LEVELS) {
            val selected = level.id == current.id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.chipBackground)
                    .clickable { onSelect(level) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    level.displayName,
                    color = if (selected) palette.textOnAccent else palette.textPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DifficultyTabsTowerDefence(current: CpuDifficulty, palette: TowerDefencePalette, onSelect: (CpuDifficulty) -> Unit) {
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

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this batch -- see this
// file's own KDoc for why path/zone/enemy/projectile colors are the one deliberate exception.
// ---------------------------------------------------------------------------
private data class TowerDefencePalette(
    val background: Color,
    val boardBackground: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val pathColor: Color,
    val zoneEmptyColor: Color,
    val towerColor: Color,
    val towerRangeColor: Color,
    val enemyColor: Color,
    val projectileColor: Color,
    val hpBarBackground: Color,
    val hpBarFill: Color
)

@Composable
private fun towerDefencePalette(isDark: Boolean): TowerDefencePalette = if (!isDark) {
    TowerDefencePalette(
        background = Color(0xFFFBF1E6),
        boardBackground = Color(0xFF2B3B2E),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        pathColor = Color(0xFF8A6F4E),
        zoneEmptyColor = Color(0xFFCBE0C8),
        towerColor = Color(0xFF3E7A9E),
        towerRangeColor = Color(0x553E7A9E),
        enemyColor = Color(0xFFD9573F),
        projectileColor = Color(0xFFF5E0A3),
        hpBarBackground = Color(0x552B1E14),
        hpBarFill = Color(0xFF5B9A5B)
    )
} else {
    TowerDefencePalette(
        background = Color(0xFF1C1712),
        boardBackground = Color(0xFF17231A),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        pathColor = Color(0xFF9C8462),
        zoneEmptyColor = Color(0xFF3E5A3E),
        towerColor = Color(0xFF4E8FBA),
        towerRangeColor = Color(0x554E8FBA),
        enemyColor = Color(0xFFE06B52),
        projectileColor = Color(0xFFF7EAC0),
        hpBarBackground = Color(0x55000000),
        hpBarFill = Color(0xFF6FB36F)
    )
}
