package com.gamesuite.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.LayoutCoordinates
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
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import kotlinx.coroutines.delay

/**
 * Renders DotsAndBoxesGame's state reactively. The first TWO-PLAYER game in
 * this new-games batch (Minesweeper/Sudoku/Lights Out were all solo) — no
 * daily-seed route exists here (that's a solo-puzzle concept in this app),
 * and unlike those screens, this one has to represent whose turn it is and
 * trigger the bot's turn itself, same idiom DominoesScreen already uses
 * (an 800ms `LaunchedEffect(state?.currentPlayerIndex, ...)` delay before
 * `game.playBotTurn()`, so a bot's move doesn't feel instantaneous/jarring).
 * In SINGLE_DEVICE_PASS_AND_PLAY mode neither player is a bot, so that
 * effect simply never fires — the same screen serves both modes with no
 * branching required.
 *
 * VISUAL IDENTITY: shares its warm background/textPrimary tokens with
 * MinesweeperScreen/SudokuScreen/LightsOutScreen (see
 * [dotsAndBoxesPalette]) for one consistent "new games" identity, extended
 * with two distinct per-player accent colors (needed here for the first
 * time in this batch, since ownership — which player claimed which box —
 * is the entire point of the board). Never reads `MaterialTheme.colorScheme`
 * for gameplay colors, same standing rule as every other game's board.
 *
 * JUICE: a consumer of the shared [com.gamesuite.ui.effects.CameraShake]/
 * [com.gamesuite.ui.effects.ParticleBurst] utilities (see those files' own KDoc for why they
 * exist) — this game's defining moment is completing a box (it grants an extra turn, the entire
 * reason a single tap can chain into a long run for one player; see [DotsAndBoxesGame.drawEdge]'s
 * own KDoc on `nextIndex`), so every HUMAN-driven box claim gets a small, modest per-box burst in
 * the claimer's own [DotsAndBoxesPalette.player0]/[DotsAndBoxesPalette.player1] color plus a light
 * shake nudge — deliberately restrained since a chain can fire this many times in one turn and
 * must read as a satisfying tick, not an overwhelming wallop. The board's true biggest moment,
 * winning the whole board, gets a proportionally bigger shake + burst + [SfxKind.SUCCESS_CHIME],
 * layered on top of the existing [HapticSignal.CELEBRATION]/[HapticSignal.NORMAL_ACTION] pairing
 * below rather than replacing it.
 *
 * KNOWN LIMITATION (accepted, not fixed — same category [TowerDefenceEnemyDeathEvent]/
 * `BreakoutGame`'s own one-shot events already carry): [DotsAndBoxesGame.playBotTurn] chains its
 * own recursive box-completing moves synchronously with no suspension point between them, so
 * Compose can coalesce several real [DotsAndBoxesBoxCompletedEvent]s into one recomposition and
 * this effect only ever observes the LAST one in a bot's own multi-box turn — ownership/score
 * stay fully correct throughout, but an earlier box in that same chain silently loses its own
 * burst/shake. Fixing this for real would mean turning `lastBoxCompleted` into a drained queue of
 * pending events rather than one overwritable field, a real engine change not worth it for a
 * purely cosmetic gap on bot turns specifically (a human's own taps are naturally frame-separated
 * and never hit this).
 */
@Composable
fun DotsAndBoxesScreen(
    sessionManager: GameSessionManager,
    game: DotsAndBoxesGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOTS_AND_BOXES, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = dotsAndBoxesPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val playSfx = rememberProceduralSfx()

    // JUICE -- see this file's own class KDoc. Two separate CameraShake instances (rather than
    // one shared magnitude) since a per-box nudge and the whole-board win shake are genuinely
    // different weights of the SAME gesture, and CameraShake's magnitude is fixed per
    // Modifier.cameraShake call site, not per trigger() -- chaining both modifiers on the same
    // board container lets either (or, rarely, both at once) contribute its own offset.
    val boxShake = rememberCameraShake()
    val winShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    val density = LocalDensity.current
    val boxShakeMagnitudePx = with(density) { 3.dp.toPx() }
    val winShakeMagnitudePx = with(density) { 12.dp.toPx() }
    // Particle speed/gravity are expressed in raw PIXELS here (this screen's own coordinate
    // space, via BoxWithConstraints/onGloballyPositioned below) rather than ParticleBurst's own
    // normalized-0..1-board defaults (tuned for AirHockeyGame's normalized board), so both are
    // converted from Dp via the same [density] -- a per-box tick should travel roughly one box
    // cell's width, the win burst noticeably further.
    val boxBurstSpeedRange = with(density) { 40.dp.toPx()..90.dp.toPx() }
    val boxBurstGravity = with(density) { 160.dp.toPx() }
    val winBurstSpeedRange = with(density) { 70.dp.toPx()..160.dp.toPx() }
    val winBurstGravity = with(density) { 200.dp.toPx() }

    // Per-box-cell centers, captured once via onGloballyPositioned (see the board Composable
    // below) relative to the board's own stable, UNshaken BoxWithConstraints frame -- so a burst
    // spawned mid-chain still lands exactly on the claimed box even while a PRIOR box's shake is
    // still decaying. Plain remember (not snapshot state): only ever read from inside a
    // LaunchedEffect reacting to a real event, never from composition itself, so writes here
    // don't need to trigger recomposition.
    val boxCenters = remember { mutableMapOf<Int, Offset>() }
    var boardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

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

    // Same idiom as DominoesScreen's own bot-turn trigger -- a short delay
    // so a bot's move doesn't feel instantaneous, then let the engine's own
    // playBotTurn() handle however many chained extra turns it needs to.
    LaunchedEffect(state?.currentPlayerIndex, state?.boardOver) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
        }
    }

    val s = state ?: return

    LaunchedEffect(s.boardOver) {
        if (s.boardOver) haptics(if (s.winnerPlayerId != null) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
        // JUICE: an actual win (never a tie) is this game's single biggest moment -- layered on
        // top of the CELEBRATION haptic above rather than replacing it, matching CheckersScreen's
        // own established win/loss SfxKind+HapticSignal pairing. A tie keeps its existing
        // NORMAL_ACTION haptic and no extra fanfare -- there's no single claimer to celebrate.
        if (s.boardOver && s.winnerPlayerId != null) {
            playSfx(SfxKind.SUCCESS_CHIME)
            val winnerIndex = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
            val winnerColor = if (winnerIndex == 1) palette.player1 else palette.player0
            val origin = boardCoordinates?.let { Offset(it.size.width / 2f, it.size.height / 2f) } ?: Offset.Zero
            particleBurst.spawn(
                origin = origin, count = 28,
                colors = listOf(winnerColor, palette.dot),
                speedRange = winBurstSpeedRange, lifeRangeSeconds = 0.6f..0.95f, gravity = winBurstGravity
            )
            winShake.trigger(durationMs = WIN_SHAKE_DECAY_MS, easing = FastOutSlowInEasing)
        }
    }

    // JUICE: this game's actual defining moment -- see the class KDoc's JUICE section. Keyed on
    // the event's own [seq] (not a plain non-null check) so this fires exactly once per NEW
    // completion, never re-fires on an unrelated recomposition, and correctly does nothing while
    // still null on a freshly dealt board -- same idiom TowerDefenceScreen's own
    // `state.lastEnemyDeath?.seq` key already established.
    LaunchedEffect(s.lastBoxCompleted?.seq) {
        val event = s.lastBoxCompleted ?: return@LaunchedEffect
        val claimerColor = if (event.playerIndex == 1) palette.player1 else palette.player0
        for (boxIndex in event.boxIndices) {
            val origin = boxCenters[boxIndex] ?: continue
            particleBurst.spawn(
                origin = origin, count = 10,
                colors = listOf(claimerColor),
                speedRange = boxBurstSpeedRange, lifeRangeSeconds = 0.35f..0.55f, gravity = boxBurstGravity
            )
        }
        boxShake.trigger(durationMs = BOX_SHAKE_DECAY_MS, easing = FastOutSlowInEasing)
    }

    val isMyTurn = !s.players[s.currentPlayerIndex].isBot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusRow(s, palette)

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f, fill = false)
                // The board's own STABLE reference frame for boxCenters below -- deliberately NOT
                // shaken itself, so a capture landing mid-shake still reads the box's true resting
                // position rather than a fleeting jittered one (see boxCenters' own KDoc above).
                .onGloballyPositioned { boardCoordinates = it }
        ) {
            val cellSize = remember(maxWidth, maxHeight, s.boxRows, s.boxCols) {
                minOf(maxWidth / (s.boxCols + 0.6f), maxHeight / (s.boxRows + 0.6f), 46.dp).coerceAtLeast(22.dp)
            }
            val dotSize = 7.dp
            val edgeThickness = 14.dp // generous tap target around the thinner drawn line itself

            // Inner Box carries the actual shake -- both the grid AND the particle overlay below
            // live inside it, so a shake moves the whole board as one rigid unit, the particles
            // included. Two chained .cameraShake() calls (see boxShake/winShake's own remember
            // site above) rather than one shared magnitude, since a per-box nudge and the
            // whole-board win shake are genuinely different weights of the same gesture.
            Box(
                modifier = Modifier
                    .cameraShake(boxShake, magnitudePx = boxShakeMagnitudePx)
                    .cameraShake(winShake, magnitudePx = winShakeMagnitudePx)
            ) {
            Column {
                for (boxRow in 0..s.boxRows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (boxCol in 0..s.boxCols) {
                            DotView(dotSize, palette)
                            if (boxCol < s.boxCols) {
                                val drawn = s.horizontalEdges[boxRow * s.boxCols + boxCol]
                                EdgeView(
                                    drawn = drawn,
                                    horizontal = true,
                                    length = cellSize,
                                    thickness = edgeThickness,
                                    palette = palette,
                                    enabled = isMyTurn && !s.boardOver,
                                    onTap = {
                                        game.drawHorizontalEdge(boxRow, boxCol)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                )
                            }
                        }
                    }
                    if (boxRow < s.boxRows) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (boxCol in 0..s.boxCols) {
                                val drawnV = s.verticalEdges[boxRow * (s.boxCols + 1) + boxCol]
                                EdgeView(
                                    drawn = drawnV,
                                    horizontal = false,
                                    length = cellSize,
                                    thickness = edgeThickness,
                                    palette = palette,
                                    enabled = isMyTurn && !s.boardOver,
                                    onTap = {
                                        game.drawVerticalEdge(boxRow, boxCol)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                )
                                if (boxCol < s.boxCols) {
                                    val boxIndex = boxRow * s.boxCols + boxCol
                                    // Captures this box's own on-screen center, relative to the
                                    // stable boardCoordinates frame above, purely so the
                                    // lastBoxCompleted LaunchedEffect knows where to spawn a
                                    // burst -- see boxCenters' own KDoc at this composable's top.
                                    Box(
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            boardCoordinates?.let { parent ->
                                                boxCenters[boxIndex] = parent.localPositionOf(
                                                    coords, Offset(coords.size.width / 2f, coords.size.height / 2f)
                                                )
                                            }
                                        }
                                    ) {
                                        BoxCellView(owner = s.boxOwner[boxIndex], size = cellSize, palette = palette)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Overlay purely for drawing the particle burst -- standard Compose pattern (see
            // ParticleBurst's own KDoc): sits inside the SAME shaking inner Box as the grid above,
            // after it, so a burst never sits under a still-drawing edge and shakes together with
            // the board it's celebrating.
            Canvas(modifier = Modifier.matchParentSize()) {
                for (particle in particleBurst.particles.value) {
                    drawCircle(
                        color = particle.color.copy(alpha = particle.lifeFraction),
                        radius = with(density) { 3.dp.toPx() } * particle.lifeFraction.coerceAtLeast(0.4f),
                        center = particle.pos
                    )
                }
            }
            }
        }

        if (s.boardOver) {
            Spacer(Modifier.height(16.dp))
            FinishedPanel(s = s, game = game, palette = palette)
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette/SudokuPalette/LightsOutPalette (see this file's class
// KDoc for why this doesn't read MaterialTheme.colorScheme), extended with
// two distinct per-player accent colors this game needs for box ownership.
// ---------------------------------------------------------------------------
private data class DotsAndBoxesPalette(
    val background: Color,
    val dot: Color,
    val edgeHint: Color,
    val edgeDrawn: Color,
    val player0: Color,
    val player1: Color,
    val textPrimary: Color
)

@Composable
private fun dotsAndBoxesPalette(isDark: Boolean): DotsAndBoxesPalette = if (!isDark) {
    DotsAndBoxesPalette(
        background = Color(0xFFFBF1E6),
        dot = Color(0xFF3A2E22),
        edgeHint = Color(0xFFE3CBA9),
        edgeDrawn = Color(0xFF3A2E22),
        player0 = Color(0xFFE08D4B), // warm terracotta -- the shared "new games" accent
        player1 = Color(0xFF3E7A8C), // a cool contrasting teal-blue, deliberately outside the warm family for at-a-glance ownership contrast
        textPrimary = Color(0xFF3A2E22)
    )
} else {
    DotsAndBoxesPalette(
        background = Color(0xFF1C1712),
        dot = Color(0xFFF3E9DB),
        edgeHint = Color(0xFF453A2E),
        edgeDrawn = Color(0xFFF3E9DB),
        player0 = Color(0xFFE8985B),
        player1 = Color(0xFF6FB4C9),
        textPrimary = Color(0xFFF3E9DB)
    )
}

@Composable
private fun StatusRow(s: DotsAndBoxesState, palette: DotsAndBoxesPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerScoreChip(
                name = s.players[0].displayName,
                score = s.scores[0],
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                color = palette.player0,
                palette = palette
            )
            PlayerScoreChip(
                name = s.players[1].displayName,
                score = s.scores[1],
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                color = palette.player1,
                palette = palette
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(s.lastAction, color = palette.textPrimary.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerScoreChip(name: String, score: Int, isTurn: Boolean, color: Color, palette: DotsAndBoxesPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) color.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, color = palette.textPrimary, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(6.dp))
        Text(score.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DotView(size: Dp, palette: DotsAndBoxesPalette) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(palette.dot))
}

@Composable
private fun EdgeView(
    drawn: Boolean,
    horizontal: Boolean,
    length: Dp,
    thickness: Dp,
    palette: DotsAndBoxesPalette,
    enabled: Boolean,
    onTap: () -> Unit
) {
    // A wider invisible tap target (`thickness`) around a visually thinner
    // drawn/undrawn line -- the line itself is too thin to comfortably tap
    // directly on a phone.
    val lineThickness = 5.dp
    val targetModifier = if (horizontal) Modifier.width(length).height(thickness) else Modifier.width(thickness).height(length)
    val lineModifier = if (horizontal) Modifier.width(length).height(lineThickness) else Modifier.width(lineThickness).height(length)
    Box(
        modifier = targetModifier
            .then(if (enabled && !drawn) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = lineModifier
                .clip(RoundedCornerShape(2.dp))
                .background(if (drawn) palette.edgeDrawn else palette.edgeHint)
        )
    }
}

@Composable
private fun BoxCellView(owner: Int?, size: Dp, palette: DotsAndBoxesPalette) {
    val bg = when (owner) {
        0 -> palette.player0.copy(alpha = 0.35f)
        1 -> palette.player1.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
    )
}

@Composable
private fun FinishedPanel(s: DotsAndBoxesState, game: DotsAndBoxesGame, palette: DotsAndBoxesPalette) {
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = if (s.winnerPlayerId != null) {
                "${s.players.first { it.playerId == s.winnerPlayerId }.displayName} wins!"
            } else {
                "It's a tie!"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${s.players[0].displayName} ${s.scores[0]} — ${s.scores[1]} ${s.players[1].displayName}",
                style = MaterialTheme.typography.bodyMedium
            )
            val totalRounds = sessionWins.values.sum() + sessionDraws
            if (totalRounds > 1) {
                Spacer(Modifier.height(4.dp))
                val winsText = s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" }
                Text(
                    if (sessionDraws > 0) "Session: $winsText · Draws $sessionDraws" else "Session: $winsText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { game.playAgain() }) { Text("Play Again") }
                OutlinedButton(onClick = { game.leaveSession() }) { Text("Back to Menu") }
            }
        }
    }
}

/** How long the small per-box shake nudge takes to settle back to zero -- see boxShake's own
 *  remember site above. Short and snappy since a long chain can trigger this many times in one
 *  turn; a lingering shake would visibly stack/lag behind the taps that caused it. */
private const val BOX_SHAKE_DECAY_MS = 120

/** How long the whole-board win shake takes to settle back to zero -- longer than
 *  [BOX_SHAKE_DECAY_MS] since it fires exactly once per board and should read as a real
 *  full-stop moment, not a quick tick. */
private const val WIN_SHAKE_DECAY_MS = 320
