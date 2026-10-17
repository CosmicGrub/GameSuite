package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.ui.effects.victoryGlow
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlin.math.roundToInt

/**
 * Renders TowerDefenceGame's state reactively -- see [TowerDefenceGame]'s own class KDoc for the
 * full design reasoning (fixed paths, 1 upgradeable tower type, a real pause). Real-time loop,
 * same shape as [BreakoutScreen]/[AirHockeyScreen]'s own (a `withFrameNanos` loop stepping
 * `game.tick(dt)` every frame) -- `docs/TOWER_DEFENCE_ADR.md`'s own reason for staying on this
 * infrastructure rather than a new game-loop dependency. The loop runs only while the run is live,
 * not paused and the lifecycle is RESUMED (so it stops when the app is backgrounded), and is
 * cancelled when the screen leaves composition; `tick()` itself is also a no-op while paused.
 *
 * INTERACTION: select, then commit. Tapping a tower spot only SELECTS it (every spot is a 48dp
 * button overlaid on the board, described to screen readers with its state and price). A tray
 * under the board then shows what the commit button will do and what it costs -- "Build * 50" on
 * an empty spot, "Upgrade * 40/64/102/164" on a tower, "Max level" at level 5 -- and says how much
 * gold is missing when it is disabled. Nothing is spent until that button is pressed, so a stray
 * touch can no longer spend gold. The engine API is unchanged (`placeTower` / `upgradeTower`).
 * Building while paused is allowed (the engine intends it).
 *
 * SETUP: level and difficulty are read from `game.state.value`. Picking one restarts the run, so
 * it applies at once only before anything is at stake (wave 1 not started, no tower built, or
 * the run already over); otherwise a confirm dialog asks first. Both pickers have 48dp targets.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play / Back to Menu) plus its BackHandler.
 * Leaving mid-run asks first. The unit of "finished" here is a RUN (won or lost), never a wave:
 * if an earlier run this session already ended ([TowerDefenceGame.runsFinished] > 0), leaving
 * goes through leaveSession() so those runs still count (the dialog says so); otherwise it is a
 * pure abort. A finished run leaves through leaveSession() with no prompt. The setup row reserves
 * [GameChromeEndInset] so the corner button never sits on it.
 *
 * PAUSE: the HUD pause button is a plain toggle and leaves the board usable. Anything that takes
 * window focus away (the corner menu's popup, the leave/help/restart dialogs, the notification
 * shade) pauses the engine (idempotent) and shows a "Paused / Tap to resume" scrim over the board
 * that resumes via [TowerDefenceGame.togglePause]. The host Activity's own pause()/resume() are
 * handled by the engine's `pausedByLifecycle` ownership, unchanged.
 *
 * SIZING: [fitBoard] against the measured space of the board's own slot (the levels are authored
 * on a square, so the board is square), capped at [MAX_BOARD_DP]. It never exceeds its slot. It
 * is a real-time field so it cannot scroll or pan: on a short window (landscape phone) it just
 * gets smaller.
 *
 * ACCESSIBILITY / COLOR: lives, gold and wave are drawn icons with one spoken description each
 * (lives and wave are polite live regions); enemies are diamonds, towers circles, so they differ
 * by shape and not hue alone, and with [LocalColorblindMode] the hp bar fill switches from green
 * to cream so it never competes with the red enemy by hue. Path, spots, towers and enemies use
 * literals at least 3:1 against the always-dark board.
 *
 * MOTION: the life-lost shake and the kill particles are off under reduced motion
 * ([LocalReducedMotion]), as are the victory glow and the result fade.
 *
 * VISUAL IDENTITY: chrome shares this batch's warm tokens (see [towerDefencePalette]); the path,
 * tower spots, enemies, and projectiles are the one deliberate exception, same "authentic
 * exception" reasoning as every other real-time/arcade game's own play-element colors in this
 * batch (Breakout's ball/paddle/bricks, Color Flood's cells, Edge Match's edges).
 *
 * JUICE: the first real consumer of the shared [com.gamesuite.ui.effects.CameraShake]/
 * [com.gamesuite.ui.effects.ParticleBurst] utilities. A life lost jolts the whole board briefly
 * (paired with the FAILURE haptic/buzz); a kill spawns a small burst of motes at the enemy's own
 * death position.
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
    val finishedRuns by game.runsFinished
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = towerDefencePalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycleOwner = LocalLifecycleOwner.current
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
    // Keyed on runSeq (bumped by every startMatch), so a finished run's "new best" can never
    // leak onto the next run's result panel.
    var reportedNewBest by remember(state.runSeq) { mutableStateOf(false) }

    // The tower spot the player has selected (not yet built on / upgraded); cleared per run.
    var selectedZone by remember(state.runSeq) { mutableStateOf<Int?>(null) }
    // True when THIS screen paused the engine (window focus lost, corner menu open) -- those
    // pauses show the "Tap to resume" scrim; the HUD's own pause button does not.
    var autoPaused by remember { mutableStateOf(false) }
    // A level/difficulty change waiting for the player's confirmation (the run would be lost).
    var pendingSetup by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Idempotent: the engine ignores a pause when already paused or the run is over.
    fun autoPause() {
        val s = game.state.value
        if (game.matchOver.value || s.runOver) return
        if (!s.paused) autoPaused = true
        game.pause()
    }

    LaunchedEffect(windowFocused) {
        if (!windowFocused) autoPause()
    }
    LaunchedEffect(state.paused) {
        if (!state.paused) autoPaused = false
    }

    // The simulation frame loop. Runs only while the run is live and not paused, and only while
    // the lifecycle is RESUMED (so it stops when the app is backgrounded); it is cancelled when
    // the screen leaves composition. `last` restarts from zero every time the loop (re)starts, so
    // a pause can never feed one huge dt into the simulation.
    LaunchedEffect(lifecycleOwner, state.paused, state.runOver) {
        if (state.paused || state.runOver) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (!game.matchOver.value && !game.state.value.paused && !game.state.value.runOver) {
                withFrameNanos { nanos ->
                    if (last != 0L) game.tick((nanos - last) / 1_000_000_000f)
                    last = nanos
                }
            }
        }
    }

    // Lives lost -- compares against the previous composition's value rather than a seq-numbered
    // event object (this engine has none), same effect for a UI this simple.
    var previousLives by remember(state.runSeq) { mutableStateOf(state.lives) }
    LaunchedEffect(state.lives) {
        val lost = state.lives < previousLives
        previousLives = state.lives
        if (lost) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
            if (!reducedMotion) cameraShake.trigger()
        }
    }

    // A kill spawns a small burst of motes at the enemy's own death position -- keyed on `seq`
    // (see TowerDefenceGame.TowerDefenceEnemyDeathEvent) rather than a plain non-null check.
    // Skipped under reduced motion.
    LaunchedEffect(state.lastEnemyDeath?.seq) {
        val death = state.lastEnemyDeath ?: return@LaunchedEffect
        if (reducedMotion) return@LaunchedEffect
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

    // Solo game: the only player is the human, so a WON run is always the human's win and the
    // celebration needs no further gating.
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

    // AGSL deepening: the shared "big win" glow, timed to a real run actually being WON (a loss
    // gets the FAILURE haptic above and no glow). Off under reduced motion.
    val victoryTrigger = state.runResult == TowerDefenceGame.TowerDefenceRunResult.WON

    // Picking a level/difficulty restarts the run: free before anything is at stake, otherwise
    // the player is asked first.
    val setupIsFree = state.runOver ||
        (state.waveNumber == 1 && state.towers.isEmpty() && !state.waveInProgress)
    fun requestSetup(apply: () -> Unit) {
        if (game.matchOver.value) return
        if (setupIsFree) apply() else pendingSetup = apply
    }

    val selectedTower = selectedZone?.let { z -> state.towers.firstOrNull { it.zoneIndex == z } }

    // A run still in progress is the unfinished unit: if an earlier run this session already
    // ended, leaving goes through leaveSession() (the same call the result panel's "Back to Menu"
    // makes) so those runs still count; with none finished it is a pure abort.
    GameChrome(
        helpTitle = "How to Play Tower Defence",
        helpText = HELP_TEXT,
        matchInProgress = !state.runOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRuns > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedRuns == 0,
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this run?",
        leaveBody = if (finishedRuns > 0) {
            "This run is still in progress and won't count, but the runs you've already finished stay on your record."
        } else {
            "This run is still in progress. Leaving now won't count it as a win or a loss."
        },
        // Composed only while the corner menu is open, so opening it freezes the simulation. The
        // dialogs the menu leads to are covered by the window-focus effect above.
        extraItems = { _ -> LaunchedEffect(Unit) { autoPause() } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .victoryGlow(trigger = victoryTrigger && !reducedMotion, tint = palette.accent)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top-right occupant of this layout: the level row keeps the corner button's width
            // free at its end.
            Column(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
                TowerDefenceChipRow(
                    labels = TowerDefenceGame.LEVELS.map { it.displayName },
                    selectedIndex = TowerDefenceGame.LEVELS.indexOfFirst { it.id == state.level.id },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
                    onSelect = { i ->
                        val picked = TowerDefenceGame.LEVELS[i]
                        if (picked.id != state.level.id) {
                            requestSetup {
                                game.level = picked
                                game.startMatch()
                            }
                        }
                    }
                )
                TowerDefenceChipRow(
                    labels = CpuDifficulty.entries.map { towerDefenceDifficultyLabel(it) },
                    selectedIndex = CpuDifficulty.entries.indexOf(state.difficulty),
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { i ->
                        val tier = CpuDifficulty.entries[i]
                        if (tier != state.difficulty) {
                            requestSetup {
                                game.difficulty = tier
                                game.startMatch()
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(4.dp))

            TowerDefenceStatusRow(
                lives = state.lives, gold = state.gold, wave = state.waveNumber, totalWaves = state.totalWaves,
                paused = state.paused, runOver = state.runOver,
                onTogglePause = { game.togglePause() }, palette = palette,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()
            )

            // Fixed-height slot so the board does not jump when the line appears or disappears.
            Box(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().heightIn(min = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                val info = when {
                    state.runOver -> null
                    state.paused -> "Paused · you can still build and upgrade"
                    !state.waveInProgress && state.interWaveCooldown > 0f ->
                        "Next wave in ${state.interWaveCooldown.roundToInt().coerceAtLeast(1)}s · build or upgrade now"
                    else -> null
                }
                if (info != null) {
                    Text(
                        info,
                        color = palette.textPrimary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
                // fitBoard never returns a footprint larger than this BoxWithConstraints's own
                // measured space -- see its KDoc. The levels are authored on a square.
                val fit = remember(maxWidth, maxHeight) {
                    fitBoard(
                        availableWidthPx = maxWidth.value,
                        availableHeightPx = maxHeight.value,
                        columns = BOARD_UNITS,
                        maxCellPx = MAX_BOARD_DP / BOARD_UNITS
                    )
                }
                val boardDp = fit.boardWidthPx.dp

                val summary = "Wave ${state.waveNumber} of ${state.totalWaves}, ${state.lives} lives, ${state.gold} gold" +
                    if (state.runOver) ", run over" else if (state.paused) ", paused" else ""

                Box(
                    modifier = Modifier
                        .size(boardDp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.boardBackground)
                        .cameraShake(cameraShake, magnitudePx = shakeMagnitudePx)
                        .semantics {
                            contentDescription = "Tower Defence battlefield"
                            stateDescription = summary
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = this.size.width
                        val h = this.size.height
                        fun toPx(p: Offset) = Offset(p.x * w, p.y * h)
                        val dp1 = 1.dp.toPx()

                        // Path: one continuous round-capped, round-joined stroke (separate butt
                        // segments left notches at every bend) over a darker edge stroke.
                        val path = state.level.path
                        val pathShape = Path().apply {
                            val first = toPx(path.first())
                            moveTo(first.x, first.y)
                            for (i in 1 until path.size) {
                                val p = toPx(path[i])
                                lineTo(p.x, p.y)
                            }
                        }
                        drawPath(
                            path = pathShape, color = palette.pathEdge,
                            style = Stroke(width = w * 0.03f + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawPath(
                            path = pathShape, color = palette.pathColor,
                            style = Stroke(width = w * 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // Tower spots (empty outline with a "+", or the placed tower + its level).
                        val zoneRadiusPx = w * 0.045f
                        val canAffordTower = state.gold >= TowerDefenceGame.BASE_TOWER_COST
                        for ((zoneIndex, zone) in state.level.towerZones.withIndex()) {
                            val center = toPx(zone)
                            val tower = state.towers.firstOrNull { it.zoneIndex == zoneIndex }
                            val isSelected = selectedZone == zoneIndex
                            if (tower == null) {
                                val ring = if (canAffordTower) palette.zoneEmptyColor else palette.zoneEmptyColor.copy(alpha = 0.55f)
                                if (isSelected) {
                                    drawCircle(color = palette.accent.copy(alpha = 0.3f), radius = zoneRadiusPx, center = center)
                                    drawCircle(
                                        color = palette.towerRangeColor,
                                        radius = TowerDefenceGame.towerRange(1) * w, center = center,
                                        style = Stroke(width = 1.5.dp.toPx())
                                    )
                                }
                                drawCircle(
                                    color = if (isSelected) palette.accent else ring,
                                    radius = zoneRadiusPx, center = center,
                                    style = Stroke(width = if (isSelected) 2.dp.toPx() else w * 0.008f)
                                )
                                val arm = zoneRadiusPx * 0.45f
                                drawLine(ring, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.5.dp.toPx())
                                drawLine(ring, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.5.dp.toPx())
                            } else {
                                drawCircle(
                                    color = palette.towerRangeColor,
                                    radius = TowerDefenceGame.towerRange(tower.upgradeLevel) * w, center = center,
                                    style = Stroke(width = if (isSelected) 2.dp.toPx() else dp1)
                                )
                                drawCircle(color = palette.towerRing, radius = zoneRadiusPx + 2.dp.toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
                                drawCircle(color = palette.towerColor, radius = zoneRadiusPx, center = center)
                                if (isSelected) {
                                    drawCircle(color = palette.accent, radius = zoneRadiusPx + 4.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                                }
                                // The text measurer caches layouts, and only levels 1..5 ever occur.
                                val label = textMeasurer.measure(
                                    tower.upgradeLevel.toString(),
                                    style = TextStyle(color = palette.towerNumeral, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
                            }
                        }

                        // Enemies (diamonds, so they differ from the round towers by shape, not
                        // hue alone), each with a bordered HP bar above.
                        val enemyRadiusPx = w * 0.024f
                        for (enemy in state.enemies) {
                            val pos = TowerDefenceGame.positionAlongPath(path, enemy.distanceTraveled) ?: continue
                            val center = toPx(pos)
                            val half = enemyRadiusPx * 0.8f
                            rotate(degrees = 45f, pivot = center) {
                                drawRect(
                                    color = palette.enemyColor,
                                    topLeft = Offset(center.x - half, center.y - half),
                                    size = Size(half * 2f, half * 2f)
                                )
                                drawRect(
                                    color = palette.enemyOutline,
                                    topLeft = Offset(center.x - half, center.y - half),
                                    size = Size(half * 2f, half * 2f),
                                    style = Stroke(width = dp1)
                                )
                            }
                            val hpFraction = (enemy.hp / enemy.maxHp).coerceIn(0f, 1f)
                            val barWidth = enemyRadiusPx * 3f
                            val barHeight = maxOf(3.dp.toPx(), w * 0.012f)
                            val barTop = Offset(center.x - barWidth / 2f, center.y - enemyRadiusPx * 2.1f)
                            drawRect(color = palette.hpBarBackground, topLeft = barTop, size = Size(barWidth, barHeight))
                            drawRect(
                                color = if (colorblind) palette.hpBarFillColorblind else palette.hpBarFill,
                                topLeft = barTop, size = Size(barWidth * hpFraction, barHeight)
                            )
                            drawRect(
                                color = palette.hpBarBorder, topLeft = barTop, size = Size(barWidth, barHeight),
                                style = Stroke(width = dp1)
                            )
                        }

                        // Projectiles.
                        for (projectile in state.projectiles) {
                            drawCircle(color = palette.projectileColor, radius = w * 0.009f, center = toPx(projectile.position))
                        }

                        // Kill-burst motes -- drawn last so a burst never sits under a
                        // still-approaching enemy or tower.
                        for (particle in particleBurst.particles.value) {
                            drawCircle(
                                color = particle.color.copy(alpha = particle.lifeFraction),
                                radius = w * 0.008f * particle.lifeFraction.coerceAtLeast(0.35f),
                                center = toPx(particle.pos)
                            )
                        }
                    }

                    // One 48dp button per tower spot, laid over the canvas. Spots are at least
                    // ~59dp apart on every level at the smallest board, so the targets never
                    // overlap, and each carries its own spoken state and price.
                    // Not offered once the run is over: the result panel covers them, and taps and
                    // screen-reader focus must not fall through to them.
                    val zones = if (state.runOver) emptyList() else state.level.towerZones
                    for ((index, zone) in zones.withIndex()) {
                        val level = state.towers.firstOrNull { it.zoneIndex == index }?.upgradeLevel
                        val isSelected = selectedZone == index
                        val description = remember(index, zones.size, level, isSelected) {
                            towerDefenceZoneDescription(index, zones.size, level, isSelected)
                        }
                        Box(
                            modifier = Modifier
                                .offset(x = boardDp * zone.x - ZONE_TARGET / 2, y = boardDp * zone.y - ZONE_TARGET / 2)
                                .size(ZONE_TARGET)
                                .clip(CircleShape)
                                .clickable(onClickLabel = "Select tower spot", role = Role.Button) {
                                    selectedZone = index
                                    haptics(HapticSignal.LIGHT_TICK)
                                }
                                .semantics { contentDescription = description }
                        )
                    }

                    if (state.paused && autoPaused && !state.runOver) {
                        TowerDefencePausedScrim(
                            onResume = { if (game.state.value.paused) game.togglePause() },
                            modifier = Modifier.matchParentSize()
                        )
                    }

                    if (state.runOver) {
                        TowerDefenceResultOverlay(
                            won = state.runResult == TowerDefenceGame.TowerDefenceRunResult.WON,
                            waveReached = state.waveNumber,
                            totalWaves = state.totalWaves,
                            bestWave = bestWave,
                            isNewBest = reportedNewBest,
                            reducedMotion = reducedMotion,
                            onPlayAgain = { game.playAgain() },
                            onBackToMenu = { game.leaveSession() },
                            palette = palette,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TowerDefenceTray(
                spot = selectedZone,
                total = state.level.towerZones.size,
                towerLevel = selectedTower?.upgradeLevel,
                gold = state.gold,
                runActive = !state.runOver,
                palette = palette,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                onCommit = {
                    val s = game.state.value
                    val z = selectedZone
                    if (z != null && !game.matchOver.value && !s.runOver) {
                        val tower = s.towers.firstOrNull { it.zoneIndex == z }
                        if (tower == null) game.placeTower(z) else game.upgradeTower(tower.id)
                        if (game.state.value.gold < s.gold) {
                            haptics(HapticSignal.SUCCESS)
                            playSfx(SfxKind.SOLID_THUNK)
                        } else {
                            haptics(HapticSignal.FAILURE)
                            playSfx(SfxKind.INVALID_BUZZ)
                        }
                    }
                }
            )
        }
    }

    pendingSetup?.let { apply ->
        AlertDialog(
            onDismissRequest = { pendingSetup = null },
            title = { Text("Restart this run?") },
            text = { Text("Changing the level or difficulty restarts the run from wave 1. Your towers and progress in this run will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSetup = null
                    if (!game.matchOver.value) apply()
                }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { pendingSetup = null }) { Text("Keep Playing") } }
        )
    }
}

/** The levels are authored on a square, so the board is square: this many units a side. */
private const val BOARD_UNITS = 20

/** Widest the board gets, so it does not sprawl on a tablet. */
private const val MAX_BOARD_DP = 560f

/** The HUD rows stop growing here and stay centered over the board on a wide window. */
private val CONTENT_MAX_WIDTH = 560.dp

/** Touch target of one tower spot. */
private val ZONE_TARGET = 48.dp

private const val RESULT_FADE_MS = 250

// Text and overlays that sit on the board are drawn over an always-dark surface, so they use fixed
// light colours rather than theme tokens (a theme "textPrimary" is dark brown in the light theme).
private val OnBoardText = Color(0xFFF5F0E6)
private val OnBoardScrim = Color(0xCC120E1C)

private const val HELP_TEXT =
    "Enemies march along the fixed path from the entrance to the exit. Each one that reaches the " +
        "exit costs a life; lose all 20 and the run is over. Survive all 10 waves to win.\n\n" +
        "Tap a tower spot to select it, then press the button under the board. Building costs 50 " +
        "gold. Upgrading costs 40 gold for level 2, then 64, 102 and 164, up to level 5; each " +
        "upgrade adds damage and range and shortens the reload. Every kill pays 10 gold, and " +
        "nothing is spent until you press the button.\n\n" +
        "Towers shoot the enemy in range that is closest to the exit. You get 5 seconds between " +
        "waves, and you can pause to build or upgrade while the action is frozen.\n\n" +
        "Changing the level or difficulty restarts the run (after asking once it has started). " +
        "Difficulty changes enemy health, speed and count and your starting gold (150, 100 or 70), " +
        "not your lives."

private fun towerDefenceDifficultyLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

/** Spoken description of one tower spot: position, what is on it, what the next action costs. */
private fun towerDefenceZoneDescription(index: Int, total: Int, level: Int?, selected: Boolean): String {
    val where = "Tower spot ${index + 1} of $total"
    val base = when {
        level == null -> "$where, empty, build costs ${TowerDefenceGame.BASE_TOWER_COST} gold"
        level >= TowerDefenceGame.MAX_UPGRADE_LEVEL -> "$where, tower level $level, fully upgraded"
        else -> "$where, tower level $level, upgrade costs ${TowerDefenceGame.upgradeCost(level)} gold"
    }
    return if (selected) "$base, selected" else base
}

/** A row of equal-width radio chips with 48dp-tall touch targets (pills inside them). */
@Composable
private fun TowerDefenceChipRow(
    labels: List<String>,
    selectedIndex: Int,
    palette: TowerDefencePalette,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) palette.accent else palette.chipBackground)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) palette.textOnAccent else palette.textPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun TowerDefenceStatusRow(
    lives: Int, gold: Int, wave: Int, totalWaves: Int, paused: Boolean, runOver: Boolean,
    onTogglePause: () -> Unit, palette: TowerDefencePalette, modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lives and wave are polite live regions, so a leak or a new wave is announced.
        Row(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Lives: $lives"
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(18.dp)) { towerDefenceDrawHeart(palette.heart) }
            Text("$lives", color = palette.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = "Gold: $gold" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                drawCircle(color = palette.coin)
                drawCircle(color = palette.coinEdge, style = Stroke(width = 1.5.dp.toPx()))
            }
            Text("$gold", color = palette.textPrimary, maxLines = 1)
        }
        Text(
            "Wave $wave/$totalWaves",
            color = palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.semantics {
                contentDescription = "Wave $wave of $totalWaves"
                liveRegion = LiveRegionMode.Polite
            }
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (paused) palette.accent else palette.chipBackground)
                .semantics { contentDescription = if (paused) "Resume game" else "Pause game" }
                .clickable(enabled = !runOver, onClickLabel = if (paused) "Resume" else "Pause", role = Role.Button, onClick = onTogglePause),
            contentAlignment = Alignment.Center
        ) {
            val glyph = if (paused) palette.textOnAccent else palette.textPrimary
            Canvas(modifier = Modifier.size(18.dp)) {
                if (paused) {
                    val tri = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.05f)
                        lineTo(size.width * 0.95f, size.height * 0.5f)
                        lineTo(size.width * 0.2f, size.height * 0.95f)
                        close()
                    }
                    drawPath(tri, color = glyph, style = Fill)
                } else {
                    val barW = size.width * 0.3f
                    drawRect(glyph, topLeft = Offset(size.width * 0.1f, 0f), size = Size(barW, size.height))
                    drawRect(glyph, topLeft = Offset(size.width * 0.6f, 0f), size = Size(barW, size.height))
                }
            }
        }
    }
}

private fun DrawScope.towerDefenceDrawHeart(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.95f)
        cubicTo(w * -0.35f, h * 0.60f, w * 0.05f, h * -0.10f, w * 0.5f, h * 0.25f)
        cubicTo(w * 0.95f, h * -0.10f, w * 1.35f, h * 0.60f, w * 0.5f, h * 0.95f)
        close()
    }
    drawPath(path = path, color = color)
}

/**
 * What the commit button will do for the selected spot, and what it costs. Disabled with the
 * reason in words (not just greyed) when gold is short or the tower is maxed.
 */
@Composable
private fun TowerDefenceTray(
    spot: Int?,
    total: Int,
    towerLevel: Int?,
    gold: Int,
    runActive: Boolean,
    palette: TowerDefencePalette,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxLevel = TowerDefenceGame.MAX_UPGRADE_LEVEL
    val cost: Int? = when {
        spot == null -> null
        towerLevel == null -> TowerDefenceGame.BASE_TOWER_COST
        towerLevel < maxLevel -> TowerDefenceGame.upgradeCost(towerLevel)
        else -> null
    }
    val title = when {
        spot == null -> "Select a tower spot"
        towerLevel == null -> "Spot ${spot + 1} of $total: empty"
        towerLevel >= maxLevel -> "Spot ${spot + 1} of $total: level $towerLevel (max)"
        else -> "Spot ${spot + 1} of $total: level $towerLevel"
    }
    val shortfall = if (cost != null) cost - gold else 0
    val detail = when {
        spot == null -> "Build costs ${TowerDefenceGame.BASE_TOWER_COST} gold. Tap a built tower to upgrade it."
        cost == null -> "Fully upgraded."
        shortfall > 0 -> "Need $shortfall more gold"
        towerLevel == null -> "Fires at enemies in range."
        else -> "Damage ${TowerDefenceGame.towerDamage(towerLevel).roundToInt()} to ${TowerDefenceGame.towerDamage(towerLevel + 1).roundToInt()}"
    }
    val actionLabel = when {
        spot == null -> "Build"
        towerLevel == null -> "Build · $cost"
        towerLevel >= maxLevel -> "Max level"
        else -> "Upgrade · $cost"
    }
    val enabled = runActive && cost != null && shortfall <= 0

    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.chipBackground.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = palette.textPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onCommit,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.textOnAccent,
                disabledContainerColor = palette.chipBackground,
                disabledContentColor = palette.textPrimary.copy(alpha = 0.7f)
            )
        ) { Text(actionLabel, maxLines = 1) }
    }
}

/** The whole scrim is one big "Resume" button, so the resume target is as large as the board. */
@Composable
private fun TowerDefencePausedScrim(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(OnBoardScrim)
            .clickable(onClickLabel = "Resume game", role = Role.Button, onClick = onResume)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Paused",
                color = OnBoardText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("Tap to resume", color = OnBoardText.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The finished-run panel, laid over the board (it used to stack below and shrink the board
 * mid-finale). Fades in unless [reducedMotion]; scrolls inside the board if a large font scale
 * makes it taller than the board; the buttons are stacked so it never needs more than the
 * board's width.
 */
@Composable
private fun TowerDefenceResultOverlay(
    won: Boolean, waveReached: Int, totalWaves: Int, bestWave: Int?, isNewBest: Boolean,
    reducedMotion: Boolean,
    onPlayAgain: () -> Unit, onBackToMenu: () -> Unit, palette: TowerDefencePalette,
    modifier: Modifier = Modifier
) {
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (fade.value < 1f) fade.animateTo(1f, animationSpec = tween(durationMillis = RESULT_FADE_MS))
    }
    Box(
        modifier = modifier
            .graphicsLayer { alpha = fade.value }
            .background(OnBoardScrim)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            // widthIn first, then fillMaxWidth: the reverse order forces the panel to the full
            // board width because widthIn cannot narrow constraints fillMaxWidth already pinned.
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (won) "Victory!" else "Overrun",
                    style = MaterialTheme.typography.headlineSmall, color = palette.textPrimary, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("Reached wave $waveReached of $totalWaves", color = palette.textPrimary)
                if (isNewBest) {
                    Text("New best!", color = palette.accentText, fontWeight = FontWeight.Bold)
                } else if (bestWave != null) {
                    Text("Best: wave $bestWave", color = palette.textPrimary.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPlayAgain,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent)
            ) { Text("Play Again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.4f))
            ) { Text("Back to Menu") }
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this batch -- see this
// file's own KDoc for why path/zone/enemy/projectile colors are the one deliberate exception.
// The board is dark in both themes, so the play-element literals are shared in spirit and each
// is at least 3:1 against its board.
// ---------------------------------------------------------------------------
private data class TowerDefencePalette(
    val background: Color,
    val boardBackground: Color,
    val accent: Color,
    /** The accent as TEXT on [background] (the plain accent is only ~2.3:1 on the light cream). */
    val accentText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val heart: Color,
    val coin: Color,
    val coinEdge: Color,
    val pathColor: Color,
    val pathEdge: Color,
    val zoneEmptyColor: Color,
    val towerColor: Color,
    val towerRing: Color,
    val towerNumeral: Color,
    val towerRangeColor: Color,
    val enemyColor: Color,
    val enemyOutline: Color,
    val projectileColor: Color,
    val hpBarBackground: Color,
    val hpBarFill: Color,
    /** Used instead of [hpBarFill] in colorblind mode, so the bar never reads against the red enemy by hue. */
    val hpBarFillColorblind: Color,
    val hpBarBorder: Color
)

@Composable
private fun towerDefencePalette(isDark: Boolean): TowerDefencePalette = if (!isDark) {
    TowerDefencePalette(
        background = Color(0xFFFBF1E6),
        boardBackground = Color(0xFF2B3B2E),
        accent = Color(0xFFE08D4B),
        accentText = Color(0xFF9A4E12),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFF2A1E12),
        chipBackground = Color(0xFFE3CBA9),
        heart = Color(0xFFC8452E),
        coin = Color(0xFFE0A83E),
        coinEdge = Color(0xFF8A6A1E),
        pathColor = Color(0xFFB89B6E),
        pathEdge = Color(0xFF1B261E),
        zoneEmptyColor = Color(0xFFB9D8B5),
        towerColor = Color(0xFF5FA8D3),
        towerRing = Color(0xFFDCEFFA),
        towerNumeral = Color(0xFF10202B),
        towerRangeColor = Color(0x995FA8D3),
        enemyColor = Color(0xFFEF6A4F),
        enemyOutline = Color(0xFF3A1208),
        projectileColor = Color(0xFFF5E0A3),
        hpBarBackground = Color(0xCC0B120C),
        hpBarFill = Color(0xFF6FD08C),
        hpBarFillColorblind = Color(0xFFF5F0E6),
        hpBarBorder = Color(0xCCF5F0E6)
    )
} else {
    TowerDefencePalette(
        background = Color(0xFF1C1712),
        boardBackground = Color(0xFF17231A),
        accent = Color(0xFFE8985B),
        accentText = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        heart = Color(0xFFE8644C),
        coin = Color(0xFFE8B95A),
        coinEdge = Color(0xFF8A6A1E),
        pathColor = Color(0xFFB89B6E),
        pathEdge = Color(0xFF0C130E),
        zoneEmptyColor = Color(0xFFB9D8B5),
        towerColor = Color(0xFF5FA8D3),
        towerRing = Color(0xFFDCEFFA),
        towerNumeral = Color(0xFF10202B),
        towerRangeColor = Color(0x995FA8D3),
        enemyColor = Color(0xFFEF6A4F),
        enemyOutline = Color(0xFF3A1208),
        projectileColor = Color(0xFFF7EAC0),
        hpBarBackground = Color(0xCC050806),
        hpBarFill = Color(0xFF6FD08C),
        hpBarFillColorblind = Color(0xFFF5F0E6),
        hpBarBorder = Color(0xCCF5F0E6)
    )
}
