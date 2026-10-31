package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.gamesuite.games.hareandhounds.HareAndHoundsGame
import com.gamesuite.games.hareandhounds.HoundsBoard
import com.gamesuite.games.hareandhounds.HoundsMove
import com.gamesuite.games.hareandhounds.HoundsRole
import com.gamesuite.games.hareandhounds.HoundsSides
import com.gamesuite.games.hareandhounds.HoundsState
import com.gamesuite.games.hareandhounds.HoundsWhy
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private const val HOUNDS_MIN_CELL_DP = 48f
private const val HOUNDS_MAX_CELL_DP = 84f

private data class HoundsPalette(
    val background: Color,
    val text: Color,
    val plateA: Color,
    val plateB: Color,
    val line: Color,
    val frame: Color,
    val accent: Color,
    val warn: Color,
    val gold: Color
)

@Composable
private fun houndsPalette(isDark: Boolean, colorblind: Boolean): HoundsPalette {
    val accent = if (colorblind) Color(0xFF1F77D0) else Color(0xFFF2C230)
    return if (!isDark) {
        HoundsPalette(
            background = Color(0xFFF6F1E4), text = Color(0xFF2E2A20),
            plateA = Color(0xFF3F7A64), plateB = Color(0xFF2B5A4A), line = Color(0xFFF1E7C9), frame = Color(0xFF4A3520),
            accent = accent, warn = Color(0xFFD33F2F), gold = Color(0xFFF2C230)
        )
    } else {
        HoundsPalette(
            background = Color(0xFF16181A), text = Color(0xFFEDE6D4),
            plateA = Color(0xFF2E5D4B), plateB = Color(0xFF1E4034), line = Color(0xFFE4D8B4), frame = Color(0xFF2B1D10),
            accent = if (colorblind) Color(0xFF5BA8F0) else Color(0xFFF5CE4E), warn = Color(0xFFFF6B5A), gold = Color(0xFFF5CE4E)
        )
    }
}

/** A hound: a warm, floppy-eared dog head. A distinct silhouette from the hare, so the sides never rely on colour alone. */
private fun DrawScope.drawHound(c: Offset, r: Float, alpha: Float = 1f) {
    if (r < 0.5f) return // zero-radius gradients throw; nothing to draw yet
    drawOval(Color.Black.copy(alpha = 0.3f * alpha), Offset(c.x - r * 0.95f, c.y + r * 0.55f), Size(r * 1.9f, r * 0.7f))
    // Floppy ears hang down the sides.
    for (side in intArrayOf(-1, 1)) {
        val ear = Path().apply {
            moveTo(c.x + side * r * 0.45f, c.y - r * 0.75f)
            lineTo(c.x + side * r * 1.1f, c.y - r * 0.2f)
            lineTo(c.x + side * r * 0.85f, c.y + r * 0.55f)
            lineTo(c.x + side * r * 0.5f, c.y + r * 0.05f)
            close()
        }
        drawPath(ear, Color(0xFF3B2412).copy(alpha = alpha))
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFC98A52).copy(alpha = alpha), Color(0xFF8A5530).copy(alpha = alpha), Color(0xFF5A3419).copy(alpha = alpha)),
            Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f
        ),
        r * 0.85f, c
    )
    drawCircle(Color(0xFF3B2412).copy(alpha = 0.6f * alpha), r * 0.85f, c, style = Stroke(r * 0.07f))
    // Muzzle, nose and eyes.
    drawOval(Color(0xFFEAC9A1).copy(alpha = alpha), Offset(c.x - r * 0.4f, c.y + r * 0.0f), Size(r * 0.8f, r * 0.55f))
    drawCircle(Color(0xFF1A1109).copy(alpha = alpha), r * 0.14f, Offset(c.x, c.y + r * 0.12f))
    drawCircle(Color(0xFF1A1109).copy(alpha = alpha), r * 0.09f, Offset(c.x - r * 0.3f, c.y - r * 0.2f))
    drawCircle(Color(0xFF1A1109).copy(alpha = alpha), r * 0.09f, Offset(c.x + r * 0.3f, c.y - r * 0.2f))
}

/** The hare: a pale round body with two tall upright ears. */
private fun DrawScope.drawHare(c: Offset, r: Float, alpha: Float = 1f) {
    if (r < 0.5f) return // zero-radius gradients throw; nothing to draw yet
    drawOval(Color.Black.copy(alpha = 0.3f * alpha), Offset(c.x - r * 0.95f, c.y + r * 0.55f), Size(r * 1.9f, r * 0.7f))
    for (side in intArrayOf(-1, 1)) {
        drawOval(Color(0xFFE9E1D3).copy(alpha = alpha), Offset(c.x + side * r * 0.38f - r * 0.2f, c.y - r * 1.45f), Size(r * 0.4f, r * 1.2f))
        drawOval(Color(0xFFE8A6B0).copy(alpha = alpha), Offset(c.x + side * r * 0.38f - r * 0.1f, c.y - r * 1.3f), Size(r * 0.2f, r * 0.85f))
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFFFFF).copy(alpha = alpha), Color(0xFFE3DAC9).copy(alpha = alpha), Color(0xFFB5A98F).copy(alpha = alpha)),
            Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f
        ),
        r * 0.82f, c
    )
    drawCircle(Color(0xFF6E6046).copy(alpha = 0.5f * alpha), r * 0.82f, c, style = Stroke(r * 0.07f))
    drawCircle(Color(0xFF2A2218).copy(alpha = alpha), r * 0.09f, Offset(c.x - r * 0.28f, c.y - r * 0.12f))
    drawCircle(Color(0xFF2A2218).copy(alpha = alpha), r * 0.09f, Offset(c.x + r * 0.28f, c.y - r * 0.12f))
    drawCircle(Color(0xFFE08A98).copy(alpha = alpha), r * 0.11f, Offset(c.x, c.y + r * 0.12f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HareAndHoundsScreen(
    sessionManager: GameSessionManager,
    game: HareAndHoundsGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val state by game.state
    val hintMove by game.hintMove
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colorblind = LocalColorblindMode.current
    val reduced = LocalReducedMotion.current
    val palette = houndsPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f, colorblind)
    val gameSpeed = LocalGameSpeed.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.sidePreference = sessionManager.launchOptions.extras["side"] ?: HoundsSides.HOUNDS
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

    var botThinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, state?.currentPlayerIndex, state?.boardOver, state?.ply) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            botThinking = true
            try {
                val thinkMs = gameSpeed.scale(750L)
                if (thinkMs > 60L) delay(thinkMs)
                game.playBotTurnAsync()
            } finally {
                botThinking = false
            }
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.hounds_help_title)
    val helpText = stringResource(R.string.hounds_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = palette.background, buttonContent = palette.text
            ) {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.morris_waiting_host), color = palette.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val isMyTurn = if (isNetworked) s.currentPlayerIndex == localSeat && !opponentLeft else !s.players[s.currentPlayerIndex].isBot
    val isPassAndPlay = !isNetworked && s.players.none { it.isBot }
    val opponentSeat = if (localSeat == 0) 1 else 0
    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value

    var selected by remember { mutableIntStateOf(-1) }
    var whyMessage by remember { mutableStateOf<HoundsWhy?>(null) }
    LaunchedEffect(s.ply, s.currentPlayerIndex, s.boardOver) {
        selected = -1
        whyMessage = null
    }

    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
    val humanSeat = if (isNetworked) localSeat else s.players.indexOfFirst { !it.isBot }
    val localWon: Boolean? = when {
        !s.boardOver || winnerSeat < 0 -> null
        isPassAndPlay -> true
        else -> winnerSeat == humanSeat
    }

    LaunchedEffect(s.ply) {
        if (s.lastMove == null || s.ply == 0) return@LaunchedEffect
        playSfx(SfxKind.SOLID_THUNK)
        haptics(if (s.lastMover == s.hareSeat) HapticSignal.LIGHT_TICK else HapticSignal.NORMAL_ACTION)
    }
    LaunchedEffect(s.boardOver) {
        if (!s.boardOver) return@LaunchedEffect
        delay(gameSpeed.scale(450L))
        if (localWon == false) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
        } else {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
        }
    }

    val houndsWord = stringResource(R.string.hounds_role_hounds)
    val hareWord = stringResource(R.string.hounds_role_hare)
    fun roleLabel(seat: Int) = if (s.roleOfSeat(seat) == HoundsRole.HOUNDS) houndsWord else hareWord

    val lastText: String = run {
        val m = s.lastMove
        val mover = s.players.getOrNull(s.lastMover)?.displayName ?: ""
        when {
            s.boardOver && winnerSeat >= 0 -> youAware(s.players[winnerSeat].displayName, R.string.morris_ann_win, R.string.yg_win)
            m == null -> stringResource(R.string.hounds_ann_start)
            s.lastMover == s.houndsSeat -> stringResource(R.string.hounds_ann_hound, mover, HoundsBoard.COLS[m.to] + 1, HoundsBoard.ROWS[m.to] + 1)
            else -> stringResource(R.string.hounds_ann_hare, mover, HoundsBoard.COLS[m.to] + 1, HoundsBoard.ROWS[m.to] + 1)
        }
    }
    val phaseText = when {
        s.boardOver -> ""
        s.currentRole == HoundsRole.HOUNDS -> stringResource(R.string.hounds_phase_hounds)
        else -> stringResource(R.string.hounds_phase_hare)
    }
    val whyStrings = mapOf(
        HoundsWhy.GAME_OVER to stringResource(R.string.morris_why_over),
        HoundsWhy.NOT_YOUR_TURN to stringResource(R.string.morris_why_not_turn),
        HoundsWhy.CPU_THINKING to stringResource(R.string.morris_why_cpu),
        HoundsWhy.NOT_YOUR_PIECE to stringResource(R.string.hounds_why_not_yours),
        HoundsWhy.BLOCKED_PIECE to stringResource(R.string.hounds_why_blocked),
        HoundsWhy.TOO_FAR to stringResource(R.string.hounds_why_far),
        HoundsWhy.NO_BACKWARDS to stringResource(R.string.hounds_why_backwards),
        HoundsWhy.OCCUPIED to stringResource(R.string.hounds_why_occupied),
        HoundsWhy.PICK_PIECE_FIRST to stringResource(R.string.hounds_why_pick_first)
    )

    fun onTap(i: Int) {
        whyMessage = null
        val botTurn = !isNetworked && s.players[s.currentPlayerIndex].isBot
        val why = s.whyIllegal(i, selected, isMyTurn, botTurn)
        if (why != null) {
            whyMessage = why
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
            return
        }
        sounds.playTap()
        if (s.cells[i] != 0) {
            haptics(HapticSignal.LIGHT_TICK)
            selected = if (selected == i) -1 else i
        } else if (selected >= 0) {
            s.move(selected, i)?.let { game.play(it) }
            selected = -1
        }
    }

    GameChrome(
        helpTitle = helpTitle,
        helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = palette.background,
        buttonContent = palette.text,
        leaveTitle = stringResource(R.string.morris_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.morris_leave_body_session else R.string.morris_leave_body)
    ) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight * 1.15f
                val infoTop: @Composable ColumnScope.() -> Unit = {
                FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    for (seat in intArrayOf(s.houndsSeat, s.hareSeat)) {
                        val turn = s.currentPlayerIndex == seat && !s.boardOver
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (turn) palette.gold.copy(alpha = 0.25f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(28.dp)) {
                                val r = size.minDimension * 0.4f
                                if (s.roleOfSeat(seat) == HoundsRole.HOUNDS) drawHound(center.copy(y = center.y), r) else drawHare(center.copy(y = center.y + r * 0.3f), r * 0.75f)
                            }
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(s.players[seat].displayName, color = palette.text, fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal)
                                Text(roleLabel(seat), color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val sessionWins by game.sessionWins
                Text(
                    stringResource(R.string.morris_session, s.players.joinToString(" · ") { "${it.displayName}: ${sessionWins[it.playerId] ?: 0}" }),
                    color = palette.text.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall
                )

                CoachCard(
                    prefKey = "hare_and_hounds",
                    body = stringResource(R.string.hounds_coach_body),
                    trivia = stringResource(R.string.hounds_coach_trivia),
                    textColor = palette.text
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    if (isNetworked) {
                        val oppName = s.players.getOrNull(opponentSeat)?.displayName ?: ""
                        Text(
                            stringResource(R.string.hounds_vs_you, oppName, roleLabel(localSeat)),
                            color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium
                        )
                        if (opponentLeft) {
                            Text(stringResource(R.string.morris_opponent_left), color = palette.text, style = MaterialTheme.typography.bodyMedium)
                        } else if (!s.boardOver) {
                            Text(
                                if (isMyTurn) stringResource(R.string.morris_your_turn) else stringResource(R.string.morris_waiting_for, oppName),
                                color = palette.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (isPassAndPlay && !s.boardOver) {
                        Text(
                            stringResource(R.string.hounds_turn_pass, s.players[s.currentPlayerIndex].displayName, roleLabel(s.currentPlayerIndex)),
                            color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(lastText, color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    if (!s.boardOver) {
                        Text(
                            stringResource(R.string.hounds_moves_left, s.houndsRemaining),
                            color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
                        )
                        if (phaseText.isNotEmpty() && (isMyTurn || isPassAndPlay)) {
                            Text(phaseText, color = palette.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    whyMessage?.let {
                        Text(whyStrings.getValue(it), color = palette.warn, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (botThinking && !s.boardOver) ThinkingLine(stringResource(R.string.morris_thinking), palette.text)
                }

                }
                val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
                BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
                    val fit = remember(maxWidth, maxHeight) {
                        fitBoard(
                            availableWidthPx = maxWidth.value, availableHeightPx = maxHeight.value,
                            columns = 5, rows = 3, minCellPx = HOUNDS_MIN_CELL_DP, maxCellPx = HOUNDS_MAX_CELL_DP
                        )
                    }
                    val scrolls = !fit.meetsMinimum
                    val cell = if (scrolls) HOUNDS_MIN_CELL_DP.dp else fit.cellPx.dp
                    Box(modifier = if (scrolls) Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()) else Modifier) {
                        HoundsBoardView(
                            s = s, cell = cell, palette = palette, reduced = reduced, speedScale = { gameSpeed.scale(it) },
                            selected = selected, hint = hintMove, interactive = isMyTurn || isPassAndPlay,
                            houndLabel = stringResource(R.string.hounds_cell_hound), hareLabel = stringResource(R.string.hounds_cell_hare),
                            emptyLabel = stringResource(R.string.morris_cell_empty),
                            colRow = { i -> stringResource(R.string.hounds_col_row, HoundsBoard.COLS[i] + 1, HoundsBoard.ROWS[i] + 1) },
                            selectedLabel = stringResource(R.string.morris_cell_selected),
                            canMoveLabel = stringResource(R.string.morris_cell_can_move),
                            destLabel = stringResource(R.string.morris_cell_destination),
                            hintLabel = stringResource(R.string.morris_cell_hint),
                            onTap = ::onTap
                        )
                    }
                }

                }
                val controls: @Composable ColumnScope.() -> Unit = {
                if (!isNetworked) {
                    Spacer(Modifier.height(8.dp))
                    BoardControlsRow(
                        canUndo = game.canUndo,
                        canHint = isMyTurn && !s.boardOver,
                        hintUnavailableReason = stringResource(if (s.boardOver) R.string.morris_reason_over else R.string.morris_reason_cpu),
                        onUndo = { game.undo() },
                        onHint = { scope.launch { game.requestHint() } },
                        contentColor = palette.text
                    )
                }

                if (s.boardOver) {
                    Spacer(Modifier.height(12.dp))
                    val wName = s.players.getOrNull(winnerSeat)?.displayName ?: ""
                    val headline = youAware(wName, R.string.morris_wins, R.string.yg_win)
                    val detail = when (s.endReason) {
                        "escaped" -> stringResource(R.string.hounds_end_escaped)
                        "trapped" -> stringResource(R.string.hounds_end_trapped)
                        "stuck" -> stringResource(R.string.hounds_end_stuck)
                        else -> stringResource(R.string.hounds_end_timeout)
                    }
                    val youLine = when {
                        isPassAndPlay -> null
                        localWon == true -> stringResource(R.string.morris_you_won)
                        else -> stringResource(R.string.morris_you_lost)
                    }
                    val sw by game.sessionWins
                    val session = if (sw.values.sum() > 1) {
                        stringResource(R.string.morris_session, s.players.joinToString(" · ") { "${it.displayName}: ${sw[it.playerId] ?: 0}" })
                    } else null
                    BoardFinishedPanel(
                        headline = headline, youLine = youLine, detail = detail, session = session,
                        canPlayAgain = !opponentLeft, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                    )
                }
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        boardBlock(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) { infoTop(); Spacer(Modifier.height(8.dp)); controls() }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        infoTop()
                        Spacer(Modifier.height(8.dp))
                        boardBlock(Modifier.weight(1f, fill = true).fillMaxWidth())
                        controls()
                    }
                }
            }
            if (s.boardOver && localWon == true) CelebrationBurst(active = true)
            if (s.boardOver && localWon == false) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
        }
    }
}

@Composable
private fun HoundsBoardView(
    s: HoundsState,
    cell: Dp,
    palette: HoundsPalette,
    reduced: Boolean,
    speedScale: (Long) -> Long,
    selected: Int,
    hint: HoundsMove?,
    interactive: Boolean,
    houndLabel: String,
    hareLabel: String,
    emptyLabel: String,
    colRow: @Composable (Int) -> String,
    selectedLabel: String,
    canMoveLabel: String,
    destLabel: String,
    hintLabel: String,
    onTap: (Int) -> Unit
) {
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    fun centerOf(i: Int) = Offset((HoundsBoard.COLS[i] + 0.5f) * cellPx, (HoundsBoard.ROWS[i] + 0.5f) * cellPx)

    val t = remember { Animatable(1f) }
    var seenPly by remember { mutableIntStateOf(s.ply) }
    LaunchedEffect(s.ply) {
        if (s.ply > seenPly && s.lastMove != null && !reduced) {
            seenPly = s.ply
            t.snapTo(0f)
            t.animateTo(1f, tween(speedScale(420L).toInt().coerceAtLeast(1), easing = LinearEasing))
        } else {
            seenPly = s.ply
            t.snapTo(1f)
        }
    }
    val pulse = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "houndsPulse")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "houndsPulseValue").value
    }
    val lm = s.lastMove
    val animating = t.value < 1f && lm != null
    val moveT = if (animating) PieceEase.transform(t.value) else 1f

    val movable = if (interactive) s.movablePoints() else emptySet()
    val dests = if (interactive && selected >= 0) s.destinations(selected) else emptySet()

    Box(
        modifier = Modifier
            .width(cell * 5)
            .height(cell * 3)
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, palette.frame, RoundedCornerShape(16.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Painted slate plate with a soft sheen; cream enamel lines like a hand-lacquered game board.
            drawRect(Brush.linearGradient(listOf(palette.plateA, palette.plateB), Offset.Zero, Offset(size.width, size.height)))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), 0f, size.height * 0.5f))
            val w = cellPx * 0.07f
            for (a in 0 until HoundsBoard.POINTS) for (b in HoundsBoard.ADJACENT[a]) if (b > a) {
                drawLine(Color.Black.copy(alpha = 0.3f), centerOf(a) + Offset(0f, w * 0.5f), centerOf(b) + Offset(0f, w * 0.5f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(palette.line, centerOf(a), centerOf(b), strokeWidth = w, cap = StrokeCap.Round)
            }
            for (i in 0 until HoundsBoard.POINTS) {
                val c = centerOf(i)
                drawCircle(palette.line, cellPx * 0.13f, c)
                drawCircle(Color.Black.copy(alpha = 0.35f), cellPx * 0.08f, c)
            }
        }
        for (i in 0 until HoundsBoard.POINTS) {
            val kind = s.cells[i]
            val hideStone = animating && lm != null && lm.to == i
            val isSelected = selected == i
            val canMove = i in movable
            val isDest = i in dests
            val isHint = hint != null && interactive && (i == hint.from || i == hint.to)
            val desc = buildList {
                add(when (kind) { 1 -> houndLabel; 2 -> hareLabel; else -> emptyLabel })
                add(colRow(i))
                if (isSelected) add(selectedLabel)
                if (canMove) add(canMoveLabel)
                if (isDest) add(destLabel)
                if (isHint) add(hintLabel)
            }.joinToString(", ")
            Box(
                modifier = Modifier
                    .offset(cell * HoundsBoard.COLS[i], cell * HoundsBoard.ROWS[i])
                    .size(cell)
                    .clickable(role = Role.Button, onClick = { onTap(i) })
                    .semantics { contentDescription = desc }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val r = size.minDimension * 0.36f
                    val breathe = if (canMove) 1f + 0.05f * pulse else 1f
                    if (!hideStone) {
                        if (kind == 1) drawHound(c, r * breathe)
                        if (kind == 2) drawHare(Offset(c.x, c.y + r * 0.2f), r * 0.85f * breathe)
                    }
                    if (isSelected) drawCircle(palette.accent, r * 1.2f, c, style = Stroke(width = r * 0.18f))
                    if (isDest) {
                        drawCircle(palette.accent.copy(alpha = 0.9f), r * (0.28f + 0.06f * pulse), c)
                        drawCircle(palette.accent.copy(alpha = 0.45f), r * (0.6f + 0.1f * pulse), c, style = Stroke(width = r * 0.1f))
                    }
                    if (isHint) {
                        drawCircle(palette.gold, r * 1.3f, c, style = Stroke(width = r * 0.12f))
                        drawCircle(palette.line, r * 1.08f, c, style = Stroke(width = r * 0.06f))
                    }
                }
            }
        }
        if (animating && lm != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = cellPx * 0.36f
                val from = centerOf(lm.from)
                val to = centerOf(lm.to)
                val pos = Offset(from.x + (to.x - from.x) * moveT, from.y + (to.y - from.y) * moveT)
                val hop = sin(moveT.coerceIn(0f, 1f) * PI.toFloat()) * r * 0.45f
                val p = Offset(pos.x, pos.y - hop)
                if (s.lastMover == s.houndsSeat) drawHound(p, r) else drawHare(Offset(p.x, p.y + r * 0.2f), r * 0.85f)
            }
        }
    }
}
