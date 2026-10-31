package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.gomoku.GOMOKU_SIZE
import com.gamesuite.games.gomoku.GomokuGame
import com.gamesuite.games.gomoku.GomokuPlaceResult
import com.gamesuite.games.gomoku.GomokuState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val GOMOKU_MIN_CELL_DP = 22f
private const val GOMOKU_MAX_CELL_DP = 44f

/** Below this cell size a tap only SELECTS a point and a second tap (or the Place stone button) plays it. */
private const val GOMOKU_DIRECT_TAP_DP = 40f

/**
 * Gomoku on a lacquered wooden board: stones are glossy spheres that drop in with a small overshoot, the
 * last move wears a pulsing ring, a win lights the five stones and fires a confetti burst, and every move
 * has a sound, a haptic and a TalkBack announcement. All motion respects [LocalReducedMotion] and
 * [LocalGameSpeed]. Cells are 22 to 44dp, so on a phone a tap selects (a ghost stone appears) and a
 * second tap, or the 48dp Place stone button, plays; with TalkBack on a single activation plays.
 * A refused tap says why in a polite live region.
 */
@Composable
fun GomokuScreen(
    sessionManager: GameSessionManager,
    game: GomokuGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = musicEnabled)
    val state by game.state
    val hintMove by game.hintMove
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = gomokuPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val touchExploration = rememberTouchExploration()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.exactFive = sessionManager.launchOptions.extras["rules"] == "exact"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val opponentLeft by game.opponentLeft
    val scope = rememberCoroutineScope()

    // The CPU answers after a natural pause (never under 400ms); its search runs off the main thread.
    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            botThinking = true
            try {
                delay(maxOf(400L, gameSpeed.scale(750L)))
                game.playBotTurnAsync()
            } finally {
                botThinking = false
            }
        }
    }

    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = stringResource(R.string.gomoku_help_title),
                helpText = stringResource(R.string.gomoku_waiting_help),
                matchInProgress = true,
                onLeave = game::abortMatch,
                onAbort = { game.abortMatch() },
                leaveKeepsProgress = false,
                buttonFill = palette.background,
                buttonContent = palette.text
            ) {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gomoku_waiting_host), color = palette.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    // ---- feedback: sound + haptic per stone, per result; a spoken/visible line per event ----
    var previousStones by remember { mutableIntStateOf(-1) }
    LaunchedEffect(s.stones) {
        if (previousStones >= 0 && s.stones > previousStones) {
            playSfx(SfxKind.SOLID_THUNK)
            haptics(HapticSignal.NORMAL_ACTION)
        }
        previousStones = s.stones
    }
    val humanSeatWon = s.winnerPlayerId != null && s.players.any { it.playerId == s.winnerPlayerId && !it.isBot } &&
        (!isNetworked || s.players.getOrNull(localSeat)?.playerId == s.winnerPlayerId)
    LaunchedEffect(s.boardOver, s.winnerPlayerId) {
        if (!s.boardOver) return@LaunchedEffect
        when {
            s.winnerPlayerId == null -> haptics(HapticSignal.NORMAL_ACTION)
            humanSeatWon -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
            else -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
        }
    }

    val stoneNames = listOf(stringResource(R.string.gomoku_dark), stringResource(R.string.gomoku_light))
    val lastMover = s.lastMove?.let { s.cells[it] }?.let { s.players.getOrNull(it) }
    val eventLine = when {
        s.boardOver && s.winnerPlayerId != null -> youAware(s.players.first { it.playerId == s.winnerPlayerId }.displayName, R.string.gomoku_ann_win, R.string.yg_gomoku_ann_win)
        s.boardOver -> stringResource(R.string.gomoku_ann_draw)
        lastMover != null && s.lastMove != null -> stringResource(
            R.string.gomoku_ann_move, lastMover.displayName, s.lastMove / GOMOKU_SIZE + 1, s.lastMove % GOMOKU_SIZE + 1
        )
        else -> ""
    }

    var message by remember { mutableStateOf<String?>(null) }
    val errOccupied = stringResource(R.string.gomoku_err_occupied)
    val errNotTurn = stringResource(R.string.gomoku_err_not_turn)
    val errOver = stringResource(R.string.gomoku_err_over)
    val errLeft = stringResource(R.string.gomoku_err_left)
    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            message = null
        }
    }
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(s) { pending = null }

    val isMyTurn = if (isNetworked) s.currentPlayerIndex == localSeat && !opponentLeft
    else !s.players[s.currentPlayerIndex].isBot
    val isPassAndPlay = !isNetworked && s.players.none { it.isBot }
    val opponentSeat = if (localSeat == 0) 1 else 0
    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value

    fun tap(index: Int, confirmNeeded: Boolean) {
        val why = game.rejectionFor(index)
        if (why != null) {
            message = when (why) {
                GomokuPlaceResult.OCCUPIED -> errOccupied
                GomokuPlaceResult.BOARD_OVER -> errOver
                GomokuPlaceResult.OPPONENT_LEFT -> errLeft
                else -> errNotTurn
            }
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
            return
        }
        if (confirmNeeded && pending != index) {
            pending = index
            playSfx(SfxKind.LIGHT_TICK)
            haptics(HapticSignal.LIGHT_TICK)
            return
        }
        pending = null
        game.placeStone(index)
    }

    val rulesLine = stringResource(if (s.exactFive) R.string.gomoku_rules_exact else R.string.gomoku_rules_free)

    GameChrome(
        helpTitle = stringResource(R.string.gomoku_help_title),
        helpText = stringResource(R.string.gomoku_help_body, rulesLine),
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = palette.background,
        buttonContent = palette.text,
        leaveTitle = stringResource(R.string.gomoku_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.gomoku_leave_body_kept else R.string.gomoku_leave_body)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val reserve = if (landscape) 24f else 250f
            val availW = if (landscape) maxWidth.value * 0.58f - 24f else maxWidth.value - 24f
            val availH = maxHeight.value - reserve
            val fit = remember(availW, availH) {
                fitBoard(availW, availH, GOMOKU_SIZE, GOMOKU_SIZE, framePx = 8f, minCellPx = GOMOKU_MIN_CELL_DP, maxCellPx = GOMOKU_MAX_CELL_DP)
            }
            val cellDp = maxOf(fit.cellPx, GOMOKU_MIN_CELL_DP).dp
            val confirmNeeded = cellDp.value < GOMOKU_DIRECT_TAP_DP && !touchExploration

            val board: @Composable () -> Unit = {
                Box(contentAlignment = Alignment.Center) {
                    GomokuBoard(
                        s = s, cell = cellDp, palette = palette, colorblind = colorblind, reduced = reducedMotion,
                        speed = gameSpeed.scale(280L).toInt(), hint = hintMove, pending = pending,
                        interactive = !s.boardOver, onTap = { tap(it, confirmNeeded) }
                    )
                    if (s.boardOver && s.winnerPlayerId != null) {
                        CelebrationBurst(
                            trigger = s.stones, reduced = reducedMotion, durationMs = gameSpeed.scale(1700L).toInt(),
                            colors = listOf(palette.accent, Color(0xFFE8B33B), Color(0xFF3BB8A0), Color(0xFFE2563B)),
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
            val panel: @Composable ColumnScope.() -> Unit = {
                GomokuStatus(s, game, palette, stoneNames, isNetworked, localSeat, opponentSeat, isMyTurn, isPassAndPlay, botThinking, opponentLeft)
                var coachOpen by rememberCoachSeen("gomoku")
                if (coachOpen) {
                    CoachCard(
                        title = stringResource(R.string.gomoku_coach_title), body = stringResource(R.string.gomoku_coach_body),
                        dismiss = stringResource(R.string.gomoku_coach_dismiss), text = palette.text, container = palette.card,
                        onDismiss = { coachOpen = false }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    message ?: eventLine, color = if (message != null) palette.warn else palette.text.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (message != null) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                )
                Spacer(Modifier.height(8.dp))
                if (!isNetworked) {
                    val canHint = isMyTurn && !s.boardOver
                    val reason = stringResource(if (s.boardOver) R.string.gomoku_reason_over else R.string.gomoku_reason_cpu)
                    val unavailable = stringResource(R.string.gomoku_hint_unavailable, reason)
                    val hintLabel = stringResource(R.string.gomoku_hint)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { game.undo() }, enabled = game.canUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.gomoku_undo), color = palette.text)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { game.requestHint() } }, enabled = canHint,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .semantics { contentDescription = if (canHint) hintLabel else unavailable }
                        ) { Text(hintLabel, color = palette.text) }
                        if (confirmNeeded) PlaceButton(pending, !s.boardOver && isMyTurn) { pending?.let { p -> tap(p, true) } }
                    }
                } else if (confirmNeeded) {
                    PlaceButton(pending, !s.boardOver && isMyTurn) { pending?.let { p -> tap(p, true) } }
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(12.dp))
                    GomokuFinished(s, game, palette, if (isNetworked) localSeat else null, !opponentLeft)
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1.4f).fillMaxHeight(), contentAlignment = Alignment.Center) { board() }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        GomokuStatusHeader(s, game, palette)
                        Spacer(Modifier.height(8.dp))
                        panel()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GomokuStatusHeader(s, game, palette)
                    Spacer(Modifier.height(8.dp))
                    board()
                    Spacer(Modifier.height(8.dp))
                    panel()
                }
            }
        }
    }
}

@Composable
private fun PlaceButton(pending: Int?, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && pending != null, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.gomoku_place))
    }
}

// ---------------------------------------------------------------------------
// Palette: lacquered kaya wood with ivory and ebony stones.
// ---------------------------------------------------------------------------
private data class GomokuPalette(
    val background: Color, val text: Color, val card: Color,
    val woodTop: Color, val woodBottom: Color, val grain: Color, val frame: Color, val line: Color,
    val accent: Color, val warn: Color, val ghost: Color
)

private fun gomokuPalette(isDark: Boolean) = if (!isDark) GomokuPalette(
    background = Color(0xFFFBF1E6), text = Color(0xFF3A2E22), card = Color(0xFFF1E3CF),
    woodTop = Color(0xFFE9C58A), woodBottom = Color(0xFFD7A967), grain = Color(0x1A5A3A12), frame = Color(0xFF5A3B1C),
    line = Color(0xFF3B2A14), accent = Color(0xFFD1392B), warn = Color(0xFF9A2B1E), ghost = Color(0xFF3B2A14)
) else GomokuPalette(
    background = Color(0xFF1C1712), text = Color(0xFFF3E9DB), card = Color(0xFF2B231B),
    woodTop = Color(0xFFB98A4F), woodBottom = Color(0xFF9A6D38), grain = Color(0x22301C08), frame = Color(0xFF3A2612),
    line = Color(0xFF241707), accent = Color(0xFFFF6B57), warn = Color(0xFFFFB3A6), ghost = Color(0xFF241707)
)

@Composable
private fun GomokuStatusHeader(s: GomokuState, game: GomokuGame, palette: GomokuPalette) {
    val wins by game.sessionWins
    val draws by game.sessionDraws
    val res = LocalContext.current.resources
    val entries = s.players.joinToString(" · ") { res.getString(R.string.gomoku_session_entry, it.displayName, wins[it.playerId] ?: 0) }
    Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            for (i in 0..1) {
                val p = s.players[i]
                val turn = s.currentPlayerIndex == i && !s.boardOver
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (turn) palette.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(18.dp)) { drawStone(i == 0, palette.line, false) }
                    Spacer(Modifier.width(6.dp))
                    Text(p.displayName, color = palette.text, fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text(
            if (draws > 0) stringResource(R.string.gomoku_session_draws, entries, draws) else stringResource(R.string.gomoku_session, entries),
            color = palette.text.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun GomokuStatus(
    s: GomokuState, game: GomokuGame, palette: GomokuPalette, stoneNames: List<String>, isNetworked: Boolean,
    localSeat: Int, opponentSeat: Int, isMyTurn: Boolean, isPassAndPlay: Boolean, botThinking: Boolean, opponentLeft: Boolean
) {
    val style = MaterialTheme.typography.bodyMedium
    if (isPassAndPlay && !s.boardOver) {
        Text(
            stringResource(R.string.gomoku_turn_of_stone, s.players[s.currentPlayerIndex].displayName, stoneNames[s.currentPlayerIndex]),
            color = palette.text, fontWeight = FontWeight.Bold, style = style
        )
    }
    if (isNetworked) {
        val opp = s.players.getOrNull(opponentSeat)?.displayName.orEmpty()
        Text(stringResource(R.string.gomoku_vs_line, opp, stoneNames[localSeat]), color = palette.text, fontWeight = FontWeight.Bold, style = style)
        when {
            opponentLeft -> Text(stringResource(R.string.gomoku_opp_left), color = palette.warn, style = style)
            !s.boardOver -> Text(
                if (isMyTurn) stringResource(R.string.gomoku_your_turn) else stringResource(R.string.gomoku_waiting_for, opp),
                color = palette.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
            )
        }
    } else if (!isPassAndPlay && !s.boardOver) {
        Text(
            if (botThinking) stringResource(R.string.gomoku_thinking) else stringResource(R.string.gomoku_your_turn),
            color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
        )
    }
}

/** The whole board: wood, grain, grid, stones. Each point is a 22 to 44dp cell with its own TalkBack description. */
@Composable
private fun GomokuBoard(
    s: GomokuState, cell: Dp, palette: GomokuPalette, colorblind: Boolean, reduced: Boolean, speed: Int,
    hint: Int?, pending: Int?, interactive: Boolean, onTap: (Int) -> Unit
) {
    val n = GOMOKU_SIZE
    val shape = RoundedCornerShape(8.dp)
    val pulse = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "gomoku-pulse")
        val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        v
    }
    val winSet = remember(s.winLine) { s.winLine.toSet() }
    val boardDesc = stringResource(R.string.gomoku_board_desc, "")
    Box(
        Modifier.clip(shape).border(3.dp, palette.frame, shape)
            .background(Brush.verticalGradient(listOf(palette.woodTop, palette.woodBottom)))
            .drawBehind {
                // Wood grain: long, slightly wavy strokes across the board.
                val rnd = Random(7)
                repeat(26) {
                    val y = rnd.nextFloat() * size.height
                    val amp = 2f + rnd.nextFloat() * 5f
                    drawLine(palette.grain, Offset(0f, y), Offset(size.width, y + amp), strokeWidth = 1f + rnd.nextFloat() * 2f)
                }
            }
            .padding(4.dp)
            .semantics { contentDescription = boardDesc.trim() }
    ) {
        Column {
            for (row in 0 until n) {
                Row {
                    for (col in 0 until n) {
                        val index = row * n + col
                        GomokuCell(
                            row = row, col = col, owner = s.cells[index], size = cell, palette = palette,
                            colorblind = colorblind, reduced = reduced, speed = speed, pulse = pulse,
                            isLast = s.lastMove == index, isWin = index in winSet, isHint = index == hint,
                            isPending = index == pending, interactive = interactive, onTap = { onTap(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GomokuCell(
    row: Int, col: Int, owner: Int?, size: Dp, palette: GomokuPalette, colorblind: Boolean, reduced: Boolean,
    speed: Int, pulse: Float, isLast: Boolean, isWin: Boolean, isHint: Boolean, isPending: Boolean,
    interactive: Boolean, onTap: () -> Unit
) {
    val base = when (owner) {
        0 -> stringResource(R.string.gomoku_cell_dark, row + 1, col + 1)
        1 -> stringResource(R.string.gomoku_cell_light, row + 1, col + 1)
        else -> stringResource(R.string.gomoku_cell_empty, row + 1, col + 1)
    }
    val extras = buildList {
        if (isLast) add(stringResource(R.string.gomoku_cell_last))
        if (isWin) add(stringResource(R.string.gomoku_cell_win))
        if (isHint) add(stringResource(R.string.gomoku_cell_hint))
        if (isPending) add(stringResource(R.string.gomoku_cell_selected))
    }
    val description = if (extras.isEmpty()) base else base + ", " + extras.joinToString(", ")
    Box(
        Modifier.size(size)
            .drawBehind { drawGridCell(row, col, palette.line) }
            .clickable(enabled = interactive, role = Role.Button, onClickLabel = description, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (owner != null) {
            // A new stone drops in from a larger size with a slight overshoot (a quick settle, not a bounce).
            val scale = remember { Animatable(if (isLast && !reduced) 1.45f else 1f) }
            LaunchedEffect(Unit) {
                if (isLast && !reduced) scale.animateTo(1f, tween(speed.coerceAtLeast(60), easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)))
            }
            val winPulse = if (isWin && !reduced) 1f + 0.08f * pulse else 1f
            Canvas(
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value * winPulse; scaleY = scale.value * winPulse }
            ) {
                drawStone(owner == 0, palette.line, colorblind)
                if (isLast && !isWin) drawCircle(palette.accent.copy(alpha = if (reduced) 1f else pulse), this.size.minDimension * 0.17f, style = Stroke(this.size.minDimension * 0.07f))
                if (isWin) drawCircle(palette.accent, this.size.minDimension * 0.47f, style = Stroke(this.size.minDimension * 0.09f))
            }
        } else if (isPending || isHint) {
            // A ghost stone for the selected point, a dashed-look ring for a hint (shape, not only colour).
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension * 0.4f
                if (isPending) drawCircle(palette.ghost.copy(alpha = 0.4f * pulse + 0.2f), r)
                if (isHint) {
                    drawCircle(palette.accent, r * 1.05f, style = Stroke(this.size.minDimension * 0.08f))
                    drawCircle(palette.accent, r * 0.3f)
                }
            }
        }
    }
}

private fun DrawScope.drawGridCell(row: Int, col: Int, line: Color) {
    val n = GOMOKU_SIZE
    val cx = size.width / 2
    val cy = size.height / 2
    val w = maxOf(1f, size.minDimension * 0.045f)
    drawLine(line, Offset(if (col == 0) cx else 0f, cy), Offset(if (col == n - 1) cx else size.width, cy), w)
    drawLine(line, Offset(cx, if (row == 0) cy else 0f), Offset(cx, if (row == n - 1) cy else size.height), w)
    val star = (row == 7 && col == 7) || ((row == 3 || row == 11) && (col == 3 || col == 11))
    if (star) drawCircle(line, size.minDimension * 0.1f, Offset(cx, cy))
}

/** A glossy sphere: soft drop shadow, a lit gradient body, a thin rim; colour-blind mode adds an inner mark. */
private fun DrawScope.drawStone(dark: Boolean, rim: Color, colorblind: Boolean) {
    val r = size.minDimension * 0.43f
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(Color.Black.copy(alpha = 0.3f), r, c + Offset(r * 0.1f, r * 0.15f))
    val body = if (dark) listOf(Color(0xFF7A7A86), Color(0xFF26262C), Color(0xFF050507))
    else listOf(Color(0xFFFFFFFF), Color(0xFFEDE6D6), Color(0xFFB8AE98))
    drawCircle(Brush.radialGradient(body, center = c + Offset(-r * 0.3f, -r * 0.35f), radius = r * 1.6f), r, c)
    drawCircle(if (dark) Color.Black else Color(0xFF8B8068), r, c, style = Stroke(maxOf(1f, r * 0.06f)))
    if (colorblind) {
        if (dark) drawCircle(Color(0xFFEDE6D6), r * 0.22f, c)
        else drawCircle(rim.copy(alpha = 0.8f), r * 0.32f, c, style = Stroke(maxOf(1.5f, r * 0.1f)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GomokuFinished(s: GomokuState, game: GomokuGame, palette: GomokuPalette, localSeat: Int?, canPlayAgain: Boolean) {
    val wins by game.sessionWins
    val draws by game.sessionDraws
    Card {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
            Text(
                if (winner != null) youAware(winner.displayName, R.string.gomoku_win_title, R.string.yg_win) else stringResource(R.string.gomoku_draw_title),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
            )
            if (localSeat != null) {
                val youWon = winner != null && s.players.getOrNull(localSeat)?.playerId == winner.playerId
                Text(
                    stringResource(if (winner == null) R.string.gomoku_a_draw else if (youWon) R.string.gomoku_you_won else R.string.gomoku_you_lost),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (wins.values.sum() + draws > 1) {
                val res = LocalContext.current.resources
    val entries = s.players.joinToString(" · ") { res.getString(R.string.gomoku_session_entry, it.displayName, wins[it.playerId] ?: 0) }
                Text(
                    if (draws > 0) stringResource(R.string.gomoku_session_draws, entries, draws) else stringResource(R.string.gomoku_session, entries),
                    style = MaterialTheme.typography.bodySmall, color = palette.text.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { game.playAgain() }, enabled = canPlayAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gomoku_play_again))
                }
                ChangeSetupButton(onLeave = { game.leaveSession() })
                OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gomoku_back_to_menu))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared with HexScreen (same package): celebration, coach card, touch exploration.
// ---------------------------------------------------------------------------

/** True while a touch-exploration screen reader (TalkBack) is on: a single activation should act. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        try {
            (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
        } catch (_: Exception) {
            false
        }
    }
}

/** A one-shot confetti burst from the centre of its box; draws nothing under reduced motion. */
@Composable
internal fun CelebrationBurst(trigger: Any, reduced: Boolean, durationMs: Int, colors: List<Color>, modifier: Modifier = Modifier) {
    if (reduced) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(durationMs.coerceAtLeast(300), easing = LinearEasing)) }
    val particles = remember(trigger) {
        val rnd = Random(trigger.hashCode())
        List(44) { Triple(rnd.nextFloat() * 6.2832f, 0.25f + rnd.nextFloat() * 0.75f, rnd.nextInt(colors.size)) }
    }
    if (progress.value >= 1f) return
    Canvas(modifier) {
        val t = progress.value
        val reach = size.minDimension * 0.62f
        for ((angle, speed, ci) in particles) {
            val dist = reach * speed * (1f - (1f - t) * (1f - t))
            val x = size.width / 2 + cos(angle) * dist
            val y = size.height / 2 + sin(angle) * dist + reach * 0.35f * t * t
            drawCircle(colors[ci].copy(alpha = (1f - t).coerceIn(0f, 1f)), size.minDimension * 0.012f + speed * 4f, Offset(x, y))
        }
    }
}

/** First-run coach: shown until dismissed, then remembered on this device (best effort). */
@Composable
internal fun rememberCoachSeen(gameKey: String): MutableState<Boolean> {
    val ctx = LocalContext.current
    val open = remember(gameKey) {
        mutableStateOf(
            try {
                !ctx.getSharedPreferences("gomoku_hex_coach", Context.MODE_PRIVATE).getBoolean(gameKey, false)
            } catch (_: Exception) {
                true
            }
        )
    }
    val current = open.value
    LaunchedEffect(current) {
        if (!current) {
            try {
                ctx.getSharedPreferences("gomoku_hex_coach", Context.MODE_PRIVATE).edit().putBoolean(gameKey, true).apply()
            } catch (_: Exception) {
            }
        }
    }
    return open
}

@Composable
internal fun CoachCard(title: String, body: String, dismiss: String, text: Color, container: Color, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(container).padding(12.dp)
    ) {
        Text(title, color = text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(body, color = text.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text(dismiss) }
    }
}
