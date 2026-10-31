package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.gamesuite.games.hex.HexBoardSizes
import com.gamesuite.games.hex.HexGame
import com.gamesuite.games.hex.HexPlaceResult
import com.gamesuite.games.hex.HexState
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
import kotlin.math.sqrt

private const val HEX_MIN_HALF_WIDTH_DP = 10f
private const val HEX_MAX_HALF_WIDTH_DP = 28f

/** Below this hexagon width a tap only SELECTS a cell and a second tap (or the Place stone button) plays it. */
private const val HEX_DIRECT_TAP_DP = 32f
private val SQRT3 = sqrt(3f)

/**
 * Hex on a slate-and-parchment rhombus: bevelled hexagon tiles, coloured goal edges (red top and bottom,
 * blue left and right), glossy stones that drop in with a small overshoot, a pulsing ring on the last move,
 * a glowing winning chain with a confetti burst, and the pie-rule Swap button. Stones carry a bar mark that
 * points along their goal (vertical for Red, horizontal for Blue) so the two sides differ by shape as well as
 * colour, and colour-blind mode swaps to an orange and blue pair. Taps are resolved against the nearest
 * hexagon centre; a cell is 20 to 56dp wide, so on a phone a tap selects and a second tap, or the 48dp
 * Place stone button, plays. Every hexagon is a TalkBack node with a description and an action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HexScreen(
    sessionManager: GameSessionManager,
    game: HexGame,
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
    val colorblind = LocalColorblindMode.current
    val palette = hexPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f, colorblind = colorblind)
    val gameSpeed = LocalGameSpeed.current
    val reducedMotion = LocalReducedMotion.current
    val touchExploration = rememberTouchExploration()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.boardSize = HexBoardSizes.fromOptionId(sessionManager.launchOptions.extras["board"])
        game.pieRule = sessionManager.launchOptions.extras["pie"] == "true"
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
                helpTitle = stringResource(R.string.hex_help_title),
                helpText = stringResource(R.string.hex_waiting_help),
                matchInProgress = true,
                onLeave = game::abortMatch,
                onAbort = { game.abortMatch() },
                leaveKeepsProgress = false,
                buttonFill = palette.background,
                buttonContent = palette.text
            ) {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.hex_waiting_host), color = palette.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    var previousStones by remember { mutableIntStateOf(-1) }
    var previousSwapped by remember { mutableStateOf(false) }
    LaunchedEffect(s.stones, s.swapped) {
        if ((previousStones >= 0 && s.stones > previousStones) || (s.swapped && !previousSwapped)) {
            playSfx(SfxKind.SOLID_THUNK)
            haptics(HapticSignal.NORMAL_ACTION)
        }
        previousStones = s.stones
        previousSwapped = s.swapped
    }
    val humanWon = s.winnerPlayerId != null && s.players.any { it.playerId == s.winnerPlayerId && !it.isBot } &&
        (!isNetworked || s.players.getOrNull(localSeat)?.playerId == s.winnerPlayerId)
    LaunchedEffect(s.boardOver, s.winnerPlayerId) {
        if (!s.boardOver) return@LaunchedEffect
        if (humanWon) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
        else { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }

    val sideNames = listOf(stringResource(R.string.hex_red), stringResource(R.string.hex_blue))
    val goals = listOf(stringResource(R.string.hex_goal_red), stringResource(R.string.hex_goal_blue))
    val lastMover = s.lastMove?.let { s.cells[it] }?.let { s.players.getOrNull(it) }
    val eventLine = when {
        s.boardOver && s.winnerPlayerId != null ->
            youAware(s.players.first { it.playerId == s.winnerPlayerId }.displayName, R.string.hex_ann_win, R.string.yg_hex_ann_win)
        s.swapped && s.stones == 1 && s.lastMove != null && lastMover != null ->
            stringResource(R.string.hex_ann_swap, lastMover.displayName)
        lastMover != null && s.lastMove != null -> stringResource(
            R.string.hex_ann_move, lastMover.displayName, s.lastMove / s.size + 1, s.lastMove % s.size + 1
        )
        else -> ""
    }

    var message by remember { mutableStateOf<String?>(null) }
    val errOccupied = stringResource(R.string.hex_err_occupied)
    val errNotTurn = stringResource(R.string.hex_err_not_turn)
    val errOver = stringResource(R.string.hex_err_over)
    val errLeft = stringResource(R.string.hex_err_left)
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
    val finishedBoards = game.sessionWins.value.values.sum()
    val canSwap = game.canSwap

    val pieLine = stringResource(if (s.pieRule) R.string.hex_pie_on else R.string.hex_pie_off)

    fun tap(index: Int, confirmNeeded: Boolean) {
        val why = game.rejectionFor(index)
        if (why != null) {
            message = when (why) {
                HexPlaceResult.OCCUPIED -> errOccupied
                HexPlaceResult.BOARD_OVER -> errOver
                HexPlaceResult.OPPONENT_LEFT -> errLeft
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

    GameChrome(
        helpTitle = stringResource(R.string.hex_help_title),
        helpText = stringResource(R.string.hex_help_body, pieLine),
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = palette.background,
        buttonContent = palette.text,
        leaveTitle = stringResource(R.string.hex_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.hex_leave_body_kept else R.string.hex_leave_body)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val n = s.size
            // Portrait turns the board so its long diagonal runs down the screen (see HexGeometry); landscape keeps the rhombus.
            val rotated = !landscape
            // The board is fitted to whatever room its own region is given, so large fonts (taller panel) shrink it instead of overlapping.
            var fittedHexWidthDp by remember { mutableStateOf(HEX_MAX_HALF_WIDTH_DP * 2f) }
            val confirmNeeded = fittedHexWidthDp < HEX_DIRECT_TAP_DP && !touchExploration

            val board: @Composable () -> Unit = {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val availW = maxWidth.value - 8f
                    val availH = maxHeight.value
                    val unit = remember(n, rotated) { HexGeometry(n, 1f, rotated) }
                    val sFit = minOf(availW / unit.width, availH / unit.height)
                    val rawHalf = sFit * SQRT3 / 2f
                    val halfW = rawHalf.coerceIn(HEX_MIN_HALF_WIDTH_DP, HEX_MAX_HALF_WIDTH_DP)
                    val hexWidthDp = halfW * 2f
                    val scrolls = rawHalf < HEX_MIN_HALF_WIDTH_DP
                    SideEffect { fittedHexWidthDp = hexWidthDp }
                    val hs = rememberScrollState()
                    val vs = rememberScrollState()
                    Box(
                        if (scrolls) Modifier.horizontalScroll(hs).verticalScroll(vs) else Modifier,
                        contentAlignment = Alignment.Center
                    ) {
                        HexBoard(
                            s = s, hexWidthDp = hexWidthDp, rotated = rotated, palette = palette, reduced = reducedMotion,
                            speed = gameSpeed.scale(300L).toInt(), hint = hintMove, pending = pending,
                            interactive = !s.boardOver, boardDescription = stringResource(R.string.hex_board_desc, s.size, pieLine),
                            onTap = { tap(it, confirmNeeded) }
                        )
                        if (s.boardOver && s.winnerPlayerId != null) {
                            CelebrationBurst(
                                trigger = s.stones, reduced = reducedMotion, durationMs = gameSpeed.scale(1700L).toInt(),
                                colors = listOf(palette.red, palette.blue, Color(0xFFE8B33B), Color(0xFF3BB8A0)),
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                }
            }
            val header: @Composable () -> Unit = { HexStatusHeader(s, game, palette, sideNames, goals) }
            val panel: @Composable ColumnScope.() -> Unit = {
                HexStatus(s, palette, sideNames, isNetworked, localSeat, opponentSeat, isMyTurn, isPassAndPlay, botThinking, opponentLeft)
                var coachOpen by rememberCoachSeen("hex")
                if (coachOpen) {
                    CoachCard(
                        title = stringResource(R.string.hex_coach_title), body = stringResource(R.string.hex_coach_body),
                        dismiss = stringResource(R.string.hex_coach_dismiss), text = palette.text, container = palette.card,
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
                if (canSwap) {
                    Text(
                        stringResource(R.string.hex_swap_hint), color = palette.text.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isNetworked) {
                        val canHint = isMyTurn && !s.boardOver
                        val reason = stringResource(if (s.boardOver) R.string.hex_reason_over else R.string.hex_reason_cpu)
                        val unavailable = stringResource(R.string.hex_hint_unavailable, reason)
                        val hintLabel = stringResource(R.string.hex_hint)
                        OutlinedButton(onClick = { game.undo() }, enabled = game.canUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.hex_undo), color = palette.text)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { game.requestHint() } }, enabled = canHint,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (canHint) hintLabel else unavailable }
                        ) { Text(hintLabel, color = palette.text) }
                    }
                    if (canSwap) {
                        Button(onClick = { game.swap() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.hex_swap)) }
                    }
                    if (confirmNeeded) {
                        Button(
                            onClick = { pending?.let { p -> tap(p, true) } },
                            enabled = pending != null && !s.boardOver && isMyTurn,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.hex_place)) }
                    }
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(12.dp))
                    HexFinished(s, game, palette, if (isNetworked) localSeat else null, !opponentLeft)
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1.5f).fillMaxHeight()) { board() }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        header()
                        Spacer(Modifier.height(8.dp))
                        panel()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    header()
                    // The board takes the middle and is centred in whatever the controls leave over.
                    Box(Modifier.weight(1f, fill = true).fillMaxWidth().padding(bottom = 6.dp)) { board() }
                    Column(
                        Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { panel() }
                }
            }
        }
    }
}

private data class HexPalette(
    val background: Color, val text: Color, val card: Color, val tile: Color, val tileLight: Color, val tileEdge: Color,
    val red: Color, val blue: Color, val warn: Color, val accent: Color
)

private fun hexPalette(isDark: Boolean, colorblind: Boolean): HexPalette {
    val red = if (colorblind) Color(0xFFE69F00) else Color(0xFFD9392F)
    val blue = if (colorblind) Color(0xFF0072B2) else Color(0xFF2F6FD9)
    return if (!isDark) HexPalette(
        Color(0xFFFBF1E6), Color(0xFF3A2E22), Color(0xFFF1E3CF), Color(0xFFD9CFB8), Color(0xFFEFE7D3), Color(0xFF7F7355),
        red, blue, Color(0xFF9A2B1E), Color(0xFFE8B33B)
    ) else HexPalette(
        Color(0xFF1C1712), Color(0xFFF3E9DB), Color(0xFF2B231B), Color(0xFF3B4150), Color(0xFF4B5263), Color(0xFF14171E),
        red, blue, Color(0xFFFFB3A6), Color(0xFFE8B33B)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HexStatusHeader(s: HexState, game: HexGame, palette: HexPalette, sideNames: List<String>, goals: List<String>) {
    val wins by game.sessionWins
    val res = LocalContext.current.resources
    val entries = s.players.joinToString(" · ") { res.getString(R.string.hex_session_entry, it.displayName, wins[it.playerId] ?: 0) }
    Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), horizontalAlignment = Alignment.CenterHorizontally) {
        // A FlowRow so large fonts / narrow side panels drop the second player onto its own line instead of crushing the text.
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 0..1) {
                val turn = s.currentPlayerIndex == i && !s.boardOver
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (turn) (if (i == 0) palette.red else palette.blue).copy(alpha = 0.18f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(20.dp)) { drawSphere(if (i == 0) palette.red else palette.blue, i == 0, size.minDimension * 0.45f, center) }
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(s.players[i].displayName, color = palette.text, fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            stringResource(R.string.hex_side_line, sideNames[i], goals[i]), color = palette.text.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.hex_session, entries), color = palette.text.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HexStatus(
    s: HexState, palette: HexPalette, sideNames: List<String>, isNetworked: Boolean, localSeat: Int, opponentSeat: Int,
    isMyTurn: Boolean, isPassAndPlay: Boolean, botThinking: Boolean, opponentLeft: Boolean
) {
    val style = MaterialTheme.typography.bodyMedium
    if (isPassAndPlay && !s.boardOver) {
        Text(
            stringResource(R.string.hex_turn_of, s.players[s.currentPlayerIndex].displayName, sideNames[s.currentPlayerIndex]),
            color = palette.text, fontWeight = FontWeight.Bold, style = style
        )
    }
    if (isNetworked) {
        val opp = s.players.getOrNull(opponentSeat)?.displayName.orEmpty()
        Text(stringResource(R.string.hex_vs_line, opp, sideNames[localSeat]), color = palette.text, fontWeight = FontWeight.Bold, style = style)
        when {
            opponentLeft -> Text(stringResource(R.string.hex_opp_left), color = palette.warn, style = style)
            !s.boardOver -> Text(
                if (isMyTurn) stringResource(R.string.hex_your_turn) else stringResource(R.string.hex_waiting_for, opp),
                color = palette.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
            )
        }
    } else if (!isPassAndPlay && !s.boardOver) {
        Text(
            if (botThinking) stringResource(R.string.hex_thinking) else stringResource(R.string.hex_your_turn),
            color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Board geometry for pointy-top hexagons with circumradius [s]; row r is shifted half a hexagon right per row.
 * With [rotated] the whole lattice is turned 60 degrees clockwise (a symmetry of the hex grid, so the hexagons
 * stay pointy-top): the board's long diagonal becomes vertical, which fills a portrait phone with much larger cells.
 */
private class HexGeometry(val n: Int, val s: Float, val rotated: Boolean = false) {
    val w = SQRT3 * s
    private val rawWidth = w * (1.5f * n - 0.5f)
    private val rawHeight = 1.5f * s * (n - 1) + 2f * s
    private val cosR = 0.5f
    private val sinR = SQRT3 / 2f
    private fun rawX(r: Int, c: Int) = w * (c + r / 2f) + w / 2f
    private fun rawY(r: Int) = 1.5f * s * r + s
    private fun rotX(r: Int, c: Int) = rawWidth / 2f + (rawX(r, c) - rawWidth / 2f) * cosR - (rawY(r) - rawHeight / 2f) * sinR
    private fun rotY(r: Int, c: Int) = rawHeight / 2f + (rawX(r, c) - rawWidth / 2f) * sinR + (rawY(r) - rawHeight / 2f) * cosR
    private var minX = 0f
    private var minY = 0f
    var width = rawWidth
        private set
    var height = rawHeight
        private set

    init {
        if (rotated) {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (r in 0 until n) for (c in 0 until n) for (k in 0 until 6) {
                val a = Math.toRadians(-90.0 + 60.0 * k)
                val vx = rotX(r, c) + s * cos(a).toFloat()
                val vy = rotY(r, c) + s * sin(a).toFloat()
                x0 = minOf(x0, vx); x1 = maxOf(x1, vx); y0 = minOf(y0, vy); y1 = maxOf(y1, vy)
            }
            minX = x0; minY = y0; width = x1 - x0; height = y1 - y0
        }
    }

    fun cx(r: Int, c: Int) = if (rotated) rotX(r, c) - minX else rawX(r, c)
    fun cy(r: Int, c: Int) = if (rotated) rotY(r, c) - minY else rawY(r)
    /** Vertex [k] of a hexagon centred at ([cx], [cy]); a clockwise turn moves vertex k to k + 1. */
    fun vertex(cx: Float, cy: Float, k: Int): Offset {
        val a = Math.toRadians((-90.0 + 60.0 * (if (rotated) k + 1 else k)))
        return Offset(cx + s * cos(a).toFloat(), cy + s * sin(a).toFloat())
    }
    fun hexPath(): Path = Path().apply {
        val v0 = vertex(0f, 0f, 0)
        moveTo(v0.x, v0.y)
        for (k in 1..5) vertex(0f, 0f, k).let { lineTo(it.x, it.y) }
        close()
    }
}

@Composable
private fun HexBoard(
    s: HexState, hexWidthDp: Float, rotated: Boolean, palette: HexPalette, reduced: Boolean, speed: Int, hint: Int?, pending: Int?,
    interactive: Boolean, boardDescription: String, onTap: (Int) -> Unit
) {
    val n = s.size
    val density = LocalDensity.current
    val sPx = with(density) { (hexWidthDp / SQRT3).dp.toPx() }
    val geo = remember(n, sPx, rotated) { HexGeometry(n, sPx, rotated) }
    val boardW = with(density) { geo.width.toDp() }
    val boardH = with(density) { geo.height.toDp() }
    val unit = remember(geo) { geo.hexPath() }
    val winSet = remember(s.winPath) { s.winPath.toSet() }

    val pulse = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "hex-pulse")
        val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        v
    }
    val drop = remember { Animatable(1f) }
    LaunchedEffect(s.lastMove, s.stones, s.swapped) {
        if (s.lastMove != null && !reduced) {
            drop.snapTo(1.45f)
            drop.animateTo(1f, tween(speed.coerceAtLeast(60), easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)))
        } else drop.snapTo(1f)
    }

    val describe = describeCells(s, hint, pending, winSet)

    Box(
        Modifier.size(boardW, boardH).semantics { contentDescription = boardDescription }
    ) {
        // Static layer: tiles and goal edges; it reads no changing state, so it is drawn once per size.
        Canvas(Modifier.fillMaxSize()) {
            for (r in 0 until n) for (c in 0 until n) {
                val cx = geo.cx(r, c)
                val cy = geo.cy(r, c)
                translate(cx, cy) {
                    drawPath(unit, Brush.verticalGradient(listOf(palette.tileLight, palette.tile), startY = -sPx, endY = sPx))
                    drawPath(unit, palette.tileEdge, style = Stroke(maxOf(1f, sPx * 0.06f)))
                }
            }
            val band = sPx * 0.2f
            fun edge(cx: Float, cy: Float, a: Int, b: Int, color: Color) {
                drawLine(color, geo.vertex(cx, cy, a), geo.vertex(cx, cy, b), band)
            }
            for (i in 0 until n) {
                // Red: top-left and top-right edges of row 0, bottom-left and bottom-right of the last row.
                edge(geo.cx(0, i), geo.cy(0, i), 5, 0, palette.red); edge(geo.cx(0, i), geo.cy(0, i), 0, 1, palette.red)
                edge(geo.cx(n - 1, i), geo.cy(n - 1, i), 2, 3, palette.red); edge(geo.cx(n - 1, i), geo.cy(n - 1, i), 3, 4, palette.red)
                // Blue: left and bottom-left of column 0, right and top-right of the last column.
                edge(geo.cx(i, 0), geo.cy(i, 0), 4, 5, palette.blue); edge(geo.cx(i, 0), geo.cy(i, 0), 3, 4, palette.blue)
                edge(geo.cx(i, n - 1), geo.cy(i, n - 1), 1, 2, palette.blue); edge(geo.cx(i, n - 1), geo.cy(i, n - 1), 0, 1, palette.blue)
            }
        }
        // Dynamic layer: stones, markers, hint and selection; takes the taps.
        Canvas(
            Modifier.fillMaxSize().pointerInput(n, sPx, interactive) {
                detectTapGestures { pos ->
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (r in 0 until n) for (c in 0 until n) {
                        val dx = pos.x - geo.cx(r, c)
                        val dy = pos.y - geo.cy(r, c)
                        val d = dx * dx + dy * dy
                        if (d < bestD) { bestD = d; best = r * n + c }
                    }
                    if (best >= 0 && bestD <= sPx * sPx && interactive) onTap(best)
                }
            }
        ) {
            for (r in 0 until n) for (c in 0 until n) {
                val i = r * n + c
                val cx = geo.cx(r, c)
                val cy = geo.cy(r, c)
                val owner = s.cells[i]
                if (owner != null) {
                    val isLast = i == s.lastMove
                    val inWin = i in winSet
                    val k = (if (isLast) drop.value else 1f) * (if (inWin && !reduced) 1f + 0.08f * pulse else 1f)
                    drawSphere(if (owner == 0) palette.red else palette.blue, owner == 0, sPx * 0.62f * k, Offset(cx, cy))
                    if (inWin) drawCircle(palette.accent, sPx * 0.74f, Offset(cx, cy), style = Stroke(sPx * 0.12f))
                    else if (isLast) drawCircle(
                        Color.White.copy(alpha = if (reduced) 1f else pulse), sPx * 0.2f, Offset(cx, cy), style = Stroke(sPx * 0.07f)
                    )
                } else {
                    if (i == pending) drawCircle(
                        (if (s.currentPlayerIndex == 0) palette.red else palette.blue).copy(alpha = 0.35f + 0.25f * pulse), sPx * 0.55f, Offset(cx, cy)
                    )
                    if (i == hint) {
                        drawCircle(palette.accent, sPx * 0.62f, Offset(cx, cy), style = Stroke(sPx * 0.1f))
                        drawCircle(palette.accent, sPx * 0.16f, Offset(cx, cy))
                    }
                }
            }
        }
        // TalkBack nodes: one per hexagon, no pointer handling (the canvas above takes the touches).
        for (r in 0 until n) for (c in 0 until n) {
            val i = r * n + c
            val d = describe(i)
            Box(
                Modifier
                    .offset(with(density) { (geo.cx(r, c) - geo.w / 2).toDp() }, with(density) { (geo.cy(r, c) - geo.s * 0.75f).toDp() })
                    .size(with(density) { geo.w.toDp() }, with(density) { (geo.s * 1.5f).toDp() })
                    .semantics { contentDescription = d; if (interactive) onClick(label = d) { onTap(i); true } }
            )
        }
    }
}

@Composable
private fun describeCells(s: HexState, hint: Int?, pending: Int?, winSet: Set<Int>): (Int) -> String {
    val n = s.size
    val last = stringResource(R.string.hex_cell_last)
    val win = stringResource(R.string.hex_cell_win)
    val hintText = stringResource(R.string.hex_cell_hint)
    val selected = stringResource(R.string.hex_cell_selected)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    return remember(s, hint, pending, winSet, last, win) {
        { i: Int ->
            val row = i / n + 1
            val col = i % n + 1
            val base = when (s.cells[i]) {
                0 -> resources.getString(R.string.hex_cell_red, row, col)
                1 -> resources.getString(R.string.hex_cell_blue, row, col)
                else -> resources.getString(R.string.hex_cell_empty, row, col)
            }
            val extras = buildList {
                if (i == s.lastMove) add(last)
                if (i in winSet) add(win)
                if (i == hint) add(hintText)
                if (i == pending) add(selected)
            }
            if (extras.isEmpty()) base else base + ", " + extras.joinToString(", ")
        }
    }
}

/** A glossy sphere with a bar mark: vertical for Red (top to bottom), horizontal for Blue (left to right). */
private fun DrawScope.drawSphere(color: Color, vertical: Boolean, r: Float, c: Offset) {
    drawCircle(Color.Black.copy(alpha = 0.3f), r, c + Offset(r * 0.1f, r * 0.15f))
    val dark = Color(
        (color.red * 0.55f), (color.green * 0.55f), (color.blue * 0.55f), 1f
    )
    val light = Color(
        (color.red + (1f - color.red) * 0.55f), (color.green + (1f - color.green) * 0.55f), (color.blue + (1f - color.blue) * 0.55f), 1f
    )
    drawCircle(Brush.radialGradient(listOf(light, color, dark), center = c + Offset(-r * 0.3f, -r * 0.35f), radius = r * 1.6f), r, c)
    drawCircle(dark, r, c, style = Stroke(maxOf(1f, r * 0.07f)))
    val bar = Color.White.copy(alpha = 0.85f)
    val half = r * 0.42f
    if (vertical) drawLine(bar, c + Offset(0f, -half), c + Offset(0f, half), maxOf(1.5f, r * 0.16f))
    else drawLine(bar, c + Offset(-half, 0f), c + Offset(half, 0f), maxOf(1.5f, r * 0.16f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HexFinished(s: HexState, game: HexGame, palette: HexPalette, localSeat: Int?, canPlayAgain: Boolean) {
    val wins by game.sessionWins
    Card {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
            Text(
                winner?.let { youAware(it.displayName, R.string.hex_win_title, R.string.yg_win) }.orEmpty(),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
            )
            if (localSeat != null) {
                val youWon = winner != null && s.players.getOrNull(localSeat)?.playerId == winner.playerId
                Text(stringResource(if (youWon) R.string.hex_you_won else R.string.hex_you_lost), style = MaterialTheme.typography.titleMedium)
            }
            if (wins.values.sum() > 1) {
                val res = LocalContext.current.resources
    val entries = s.players.joinToString(" · ") { res.getString(R.string.hex_session_entry, it.displayName, wins[it.playerId] ?: 0) }
                Text(stringResource(R.string.hex_session, entries), style = MaterialTheme.typography.bodySmall, color = palette.text.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { game.playAgain() }, enabled = canPlayAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.hex_play_again))
                }
                ChangeSetupButton(onLeave = { game.leaveSession() })
                OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.hex_back_to_menu))
                }
            }
        }
    }
}
