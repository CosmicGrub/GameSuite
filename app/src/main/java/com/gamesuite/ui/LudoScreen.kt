package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.ludo.LudoBoard
import com.gamesuite.games.ludo.LudoEvent
import com.gamesuite.games.ludo.LudoGame
import com.gamesuite.games.ludo.LudoReject
import com.gamesuite.games.ludo.LudoState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val LUDO_MIN_CELL_DP = 22f
private const val LUDO_MAX_CELL_DP = 44f

private class LudoTheme(val bg: Color, val text: Color, val card: Color, val boardA: Color, val boardB: Color, val line: Color)

@Composable
private fun ludoTheme(dark: Boolean) = if (!dark) LudoTheme(
    bg = Color(0xFFFBF1E6), text = Color(0xFF3A2E22), card = Color(0xFFF0E1CC),
    boardA = Color(0xFFFFFBF2), boardB = Color(0xFFEFE2C8), line = Color(0xFF8B7355)
) else LudoTheme(
    bg = Color(0xFF1C1712), text = Color(0xFFF3E9DB), card = Color(0xFF2A221A),
    boardA = Color(0xFF3A3126), boardB = Color(0xFF2B241B), line = Color(0xFF8B7355)
)

/** A token (or several) in flight along a run of grid cells, then any captured tokens flying home. */
private class LudoFlight(
    val seat: Int, val token: Int, val route: List<Pair<Int, Int>>,
    val returning: List<Triple<Int, Int, Pair<Int, Int>>>, // seat, token, from-cell
    val hasCapture: Boolean
)

/**
 * Ludo on a glossy enamel board: the die tumbles and settles, tokens hop square by square, captured
 * tokens fly back to their yard. Roll with the big die button; the tokens you can move wear numbered
 * rings and have matching 48dp buttons under the board (or tap the token). Every illegal tap says
 * why. Colour-blind safe (colour plus a glyph on every token), Reduced-Motion aware, TalkBack-friendly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LudoScreen(
    sessionManager: GameSessionManager,
    game: LudoGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = musicEnabled)
    val state by game.state
    val hintToken by game.hintToken
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = ludoTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.needSix = extras["needSix"] != "false"
        game.exactFinish = extras["exactFinish"] != "false"
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

    // ---- animation state -------------------------------------------------------------------
    var shownTokens by remember { mutableStateOf<List<Int>?>(null) }
    var flight by remember { mutableStateOf<LudoFlight?>(null) }
    val flightProgress = remember { Animatable(0f) }
    val returnProgress = remember { Animatable(0f) }
    val landPulse = remember { Animatable(1f) }
    var lastMoveSerial by remember { mutableIntStateOf(-1) }

    var dieShown by remember { mutableIntStateOf(1) }
    var rolling by remember { mutableStateOf(false) }
    val dieSpin = remember { Animatable(0f) }
    var lastRollSerial by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<LudoReject?>(null) }

    LaunchedEffect(state?.rollSerial) {
        val cur = state ?: return@LaunchedEffect
        val first = lastRollSerial < 0
        val isNewRoll = !first && cur.rollSerial == lastRollSerial + 1
        lastRollSerial = cur.rollSerial
        if (!isNewRoll || cur.dice == 0 || reduced) {
            dieShown = cur.dice.coerceAtLeast(1)
            return@LaunchedEffect
        }
        rolling = true
        try {
            playSfx(SfxKind.WHOOSH)
            val rnd = Random(cur.rollSerial)
            val spin = scope.launch { dieSpin.snapTo(0f); dieSpin.animateTo(360f * 2, tween(gameSpeed.scale(560L).toInt().coerceAtLeast(120), easing = LinearEasing)) }
            repeat(7) {
                dieShown = rnd.nextInt(1, 7)
                playSfx(SfxKind.LIGHT_TICK)
                delay(gameSpeed.scale(70L))
            }
            spin.join()
        } finally {
            dieShown = cur.dice
            dieSpin.snapTo(0f)
            rolling = false
        }
        playSfx(SfxKind.SOLID_THUNK)
        haptics(if (cur.dice == 6) HapticSignal.STRONG_ACTION else HapticSignal.NORMAL_ACTION)
    }

    LaunchedEffect(state?.moveSerial, state) {
        val cur = state ?: return@LaunchedEffect
        val prev = shownTokens
        val seat = cur.eventSeat
        val tok = cur.eventToken
        val isNextMove = prev != null && cur.moveSerial == lastMoveSerial + 1 && seat != null && tok != null &&
            cur.event != LudoEvent.START && cur.event != LudoEvent.ROLL
        lastMoveSerial = cur.moveSerial
        if (!isNextMove || prev == null || seat == null || tok == null) {
            if (flight == null) shownTokens = cur.tokens
            return@LaunchedEffect
        }
        val color = cur.players[seat].color
        val from = cur.eventFrom
        val to = cur.eventTo
        val route = buildList {
            add(LudoBoard.cellOf(color, tok, from))
            if (from < 0) add(LudoBoard.cellOf(color, tok, 0))
            else for (p in (from + 1)..to) add(LudoBoard.cellOf(color, tok, p))
        }
        val returning = cur.tokens.indices.filter { i -> i != seat * 4 + tok && prev.getOrNull(i) != null && prev[i] != -1 && cur.tokens[i] == -1 }
            .map { i ->
                val sSeat = i / 4
                Triple(sSeat, i % 4, LudoBoard.cellOf(cur.players[sSeat].color, i % 4, prev[i]))
            }
        val localMover = !isNetworked || seat == localSeat
        try {
            if (reduced) {
                shownTokens = cur.tokens
            } else {
                shownTokens = prev
                flight = LudoFlight(seat, tok, route, returning, returning.isNotEmpty())
                returnProgress.snapTo(0f)
                flightProgress.snapTo(0f)
                val segs = (route.size - 1).coerceAtLeast(1)
                flightProgress.animateTo(segs.toFloat(), tween(gameSpeed.scale(120L * segs).toInt().coerceAtLeast(80), easing = LinearEasing))
                playSfx(SfxKind.SOLID_THUNK)
                haptics(if (localMover) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
                if (returning.isNotEmpty()) {
                    playSfx(SfxKind.INVALID_BUZZ)
                    haptics(HapticSignal.STRONG_ACTION)
                    returnProgress.animateTo(1f, tween(gameSpeed.scale(420L).toInt().coerceAtLeast(100), easing = LinearEasing))
                }
                landPulse.snapTo(1.25f)
                flight = null
                shownTokens = cur.tokens
                landPulse.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMediumLow))
            }
        } finally {
            flight = null
            shownTokens = cur.tokens
        }
        if (cur.event == LudoEvent.HOME) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
    }

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(700L).coerceAtLeast(450L))
            while (flight != null || rolling) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.ludo_help_title)
    val helpText = stringResource(R.string.ludo_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = theme.bg, buttonContent = theme.text
            ) {
                Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = theme.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val colorNames = androidx.compose.ui.res.stringArrayResource(R.array.ludo_color_names)
    val shapeNames = androidx.compose.ui.res.stringArrayResource(R.array.cc_shape_names)
    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    fun colorOf(seat: Int) = s.players[seat].color
    fun seatDescription(seat: Int) = "${colorNames[colorOf(seat) % 4]} ${shapeNames[colorOf(seat) % 6]}"

    val isMyTurn = game.isMyTurn(s)
    val settled = !rolling && flight == null
    val legal = remember(s) { game.legalTokens() }
    val finishedBoards = game.sessionWins.value.values.sum()
    val homeCounts = remember(s.tokens) { s.players.indices.map { seat -> (0 until 4).count { s.tokens[seat * 4 + it] == LudoBoard.FINISHED } } }

    LaunchedEffect(s.boardOver, s.moveSerial) {
        if (!s.boardOver) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.players[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) {
                burst.spawn(Offset(0.5f, 0.32f), 70, listOf(LudoSeatColors[s.players[winnerSeat].color % 4], Color.White, Color(0xFFFFD866)),
                    speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
            }
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words -----------------------------------------------------------------------------
    val current = seatName(s.currentPlayerIndex)
    val statusText = run {
        val who = seatName(s.eventSeat ?: s.currentPlayerIndex)
        val tokenNo = (s.eventToken ?: 0) + 1
        val steps = (s.eventTo - maxOf(s.eventFrom, 0)).coerceAtLeast(0)
        val last = when (s.event) {
            LudoEvent.ROLL -> stringResource(R.string.ludo_ann_rolled, who, s.dice)
            LudoEvent.NO_MOVE -> stringResource(R.string.ludo_ann_no_move, who, s.dice)
            LudoEvent.THREE_SIXES -> stringResource(R.string.ludo_ann_three_sixes, who)
            LudoEvent.ENTER -> stringResource(R.string.ludo_ann_enter, who, tokenNo)
            LudoEvent.MOVE -> stringResource(R.string.ludo_ann_move, who, tokenNo, steps)
            LudoEvent.CAPTURE -> stringResource(R.string.ludo_ann_capture, who, tokenNo)
            LudoEvent.HOME -> stringResource(R.string.ludo_ann_home, who, tokenNo)
            LudoEvent.WIN -> youAware(who, R.string.ludo_ann_win, R.string.yg_ludo_ann_win)
            else -> stringResource(R.string.ludo_ann_start, seatName(s.currentPlayerIndex))
        }
        val turn = when {
            s.boardOver -> ""
            hostLeft -> stringResource(R.string.ccl_host_left)
            s.awaitingMove && isMyTurn -> stringResource(R.string.ludo_turn_choose)
            isMyTurn && !isNetworked && s.players.none { it.isBot } -> stringResource(R.string.ludo_turn_named_roll, current)
            isMyTurn -> stringResource(R.string.ludo_turn_roll)
            botThinking -> stringResource(R.string.ludo_thinking, current)
            else -> stringResource(R.string.ludo_turn_named, current)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            LudoReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.cc_why_wait else R.string.cc_why_cpu)
            LudoReject.ROLL_FIRST -> stringResource(R.string.ludo_why_roll_first)
            LudoReject.ALREADY_ROLLED -> stringResource(R.string.ludo_why_already)
            LudoReject.NEED_SIX -> stringResource(R.string.ludo_why_need_six)
            LudoReject.EXACT_ROLL -> stringResource(R.string.ludo_why_exact, s.dice)
            LudoReject.FINISHED -> stringResource(R.string.ludo_why_finished)
            LudoReject.NOT_YOUR_TOKEN -> stringResource(R.string.ludo_why_not_yours)
            LudoReject.BOARD_OVER -> stringResource(R.string.cc_why_over)
            LudoReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun tapToken(token: Int) {
        val reason = game.explainToken(token)
        if (reason == LudoReject.NONE) {
            if (settled) { whyKey = null; game.moveToken(token) }
        } else {
            whyKey = reason
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }
    fun tapRoll() {
        val reason = game.explainRoll()
        if (reason == LudoReject.NONE) { if (settled) { whyKey = null; game.roll() } }
        else { whyKey = reason; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }

    val boardDescription = stringResource(
        R.string.ludo_board_desc,
        s.players.indices.joinToString("; ") { "${seatName(it)} ${seatDescription(it)} ${homeCounts[it]}/4" }
    )

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("ludo", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("ludo", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        s.players.indices.forEach { i ->
                            val c = LudoSeatColors[colorOf(i) % 4]
                            val isTurn = i == s.currentPlayerIndex && !s.boardOver
                            val chipName = seatName(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
                            val chipDesc = stringResource(R.string.ludo_chip_desc, chipName, seatDescription(i), homeCounts[i]) +
                                if (s.players[i].isBot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
                            Row(
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) c.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) c else theme.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = chipDesc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClSeatBadge(c, colorOf(i) % 6, 20)
                                Spacer(Modifier.width(5.dp))
                                Text(chipName, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(5.dp))
                                Text("${homeCounts[i]}/4", color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod, contentAlignment = Alignment.Center) {
                    val fit = remember(maxWidth, maxHeight) {
                        fitBoard(maxWidth.value, maxHeight.value, columns = 15, rows = 15, minCellPx = LUDO_MIN_CELL_DP, maxCellPx = LUDO_MAX_CELL_DP)
                    }
                    val scrolls = !fit.meetsMinimum
                    val cellDp = if (scrolls) LUDO_MIN_CELL_DP else fit.cellPx
                    val hs = rememberScrollState()
                    val vs = rememberScrollState()
                    Box(if (scrolls) Modifier.horizontalScroll(hs).verticalScroll(vs) else Modifier, contentAlignment = Alignment.Center) {
                        LudoBoardCanvas(
                            s = s, shown = shownTokens ?: s.tokens, flight = flight,
                            flightProgress = flightProgress.value, returnProgress = returnProgress.value, landPulse = landPulse.value,
                            legal = if (isMyTurn && s.awaitingMove) legal else emptyList(), hint = hintToken,
                            theme = theme, cellDp = cellDp, reduced = reduced, colorblind = colorblind,
                            description = boardDescription, onTapToken = ::tapToken
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val footer: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DieView(
                        value = dieShown, rotation = dieSpin.value, rim = LudoSeatColors[colorOf(s.currentPlayerIndex) % 4],
                        description = if (s.dice == 0) stringResource(R.string.ludo_die_none) else stringResource(R.string.ludo_die_desc, s.dice)
                    )
                    Button(
                        onClick = ::tapRoll,
                        enabled = isMyTurn && !s.awaitingMove && !s.boardOver && settled,
                        modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 120.dp)
                    ) { Text(stringResource(R.string.ludo_roll), fontWeight = FontWeight.Bold) }
                }
                if (isMyTurn && s.awaitingMove && !s.boardOver) {
                    val suggestedWord = stringResource(R.string.ludo_suggested)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until 4).forEach { t ->
                            val pos = s.tokens[s.currentPlayerIndex * 4 + t]
                            val canMove = t in legal
                            val label = stringResource(
                                when {
                                    pos == LudoBoard.FINISHED -> R.string.ludo_token_btn_home
                                    pos < 0 -> R.string.ludo_token_btn_yard
                                    else -> R.string.ludo_token_btn_track
                                }, t + 1, pos
                            )
                            OutlinedButton(
                                onClick = { tapToken(t) },
                                enabled = canMove && settled,
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp)
                                    .semantics { contentDescription = label + if (hintToken == t) " $suggestedWord" else "" }
                            ) {
                                ClSeatBadge(LudoSeatColors[colorOf(s.currentPlayerIndex) % 4], colorOf(s.currentPlayerIndex) % 6, 20)
                                Spacer(Modifier.width(6.dp))
                                Text("${t + 1}", fontWeight = if (hintToken == t) FontWeight.ExtraBold else FontWeight.Bold)
                            }
                        }
                    }
                }
                if (!isNetworked) {
                    Spacer(Modifier.height(6.dp))
                    ClUndoHintRow(
                        canUndo = game.canUndo, canHint = isMyTurn && s.awaitingMove && !s.boardOver,
                        onUndo = { game.undo() }, onHint = { scope.launch { game.requestHint() } }, contentColor = theme.text
                    )
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(10.dp))
                    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
                    val winnerSeat = s.players.indexOf(winner)
                    ClFinishedPanel(
                        title = winner?.let { youAware(it.displayName, R.string.ludo_wins, R.string.yg_win) } ?: stringResource(R.string.cc_draw),
                        subtitle = if (isNetworked) stringResource(if (winnerSeat == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                        lines = s.players.indices.map { stringResource(R.string.ludo_final_line, seatName(it), homeCounts[it]) } +
                            (if (finishedBoards > 1) listOf(
                                stringResource(R.string.ccl_session, s.players.joinToString(" · ") { "${it.displayName}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                            ) else emptyList()),
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        header(); footer()
                    }
                    board(Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Die
// ------------------------------------------------------------------------------------------

@Composable
private fun DieView(value: Int, rotation: Float, rim: Color, description: String) {
    Canvas(
        Modifier
            .size(64.dp)
            .rotate(rotation)
            .semantics { contentDescription = description }
    ) {
        val r = size.minDimension
        drawRoundRect(Color(0x33000000), Offset(r * 0.04f, r * 0.07f), Size(r * 0.92f, r * 0.92f), CornerRadius(r * 0.2f))
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE6DCC6)), Offset.Zero, Offset(r, r)),
            Offset.Zero, Size(r * 0.94f, r * 0.94f), CornerRadius(r * 0.2f)
        )
        drawRoundRect(rim, Offset.Zero, Size(r * 0.94f, r * 0.94f), CornerRadius(r * 0.2f), style = Stroke(r * 0.055f))
        val pips = when (value.coerceIn(1, 6)) {
            1 -> listOf(4)
            2 -> listOf(0, 8)
            3 -> listOf(0, 4, 8)
            4 -> listOf(0, 2, 6, 8)
            5 -> listOf(0, 2, 4, 6, 8)
            else -> listOf(0, 2, 3, 5, 6, 8)
        }
        for (p in pips) {
            val cx = r * 0.94f * (0.24f + 0.26f * (p % 3))
            val cy = r * 0.94f * (0.24f + 0.26f * (p / 3))
            drawCircle(Color(0xFF2A2118), r * 0.075f, Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = 0.35f), r * 0.025f, Offset(cx - r * 0.02f, cy - r * 0.025f))
        }
    }
}

// ------------------------------------------------------------------------------------------
// Board
// ------------------------------------------------------------------------------------------

private fun cellCenter(rc: Pair<Int, Int>, u: Float) = Offset((rc.second + 0.5f) * u, (rc.first + 0.5f) * u)

@Composable
private fun LudoBoardCanvas(
    s: LudoState, shown: List<Int>, flight: LudoFlight?, flightProgress: Float, returnProgress: Float, landPulse: Float,
    legal: List<Int>, hint: Int?, theme: LudoTheme, cellDp: Float, reduced: Boolean, colorblind: Boolean,
    description: String, onTapToken: (Int) -> Unit
) {
    val pulse = if (reduced) 0.6f else rememberInfiniteTransition(label = "ludo-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "ludo-pulse-a"
    ).value
    val measurer = rememberTextMeasurer()
    val unit = remember { FloatArray(1) { 1f } }
    val glyph = if (colorblind) 0.95f else 0.62f

    // Where every visible token sits right now (grid cell), with stack offsets applied.
    fun tokenCell(seat: Int, token: Int, progress: Int) = LudoBoard.cellOf(s.players[seat].color, token, progress)

    Canvas(
        Modifier
            .size((cellDp * 15).dp)
            .semantics { contentDescription = description }
            .pointerInput(legal, s.currentPlayerIndex, shown) {
                detectTapGestures { offset ->
                    val u = unit[0]
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    // any token of the seat on turn can be tapped so an illegal one explains itself
                    for (t in 0 until 4) {
                        val rc = tokenCell(s.currentPlayerIndex, t, shown[s.currentPlayerIndex * 4 + t])
                        val o = cellCenter(rc, u)
                        val d = (o.x - offset.x) * (o.x - offset.x) + (o.y - offset.y) * (o.y - offset.y)
                        if (d < bestD) { bestD = d; best = t }
                    }
                    if (best >= 0 && bestD <= (0.85f * u) * (0.85f * u)) onTapToken(best)
                }
            }
    ) {
        val u = size.width / 15f
        unit[0] = u

        // --- enamel board ---
        drawRoundRect(Color(0x33000000), Offset(u * 0.1f, u * 0.18f), size, CornerRadius(u * 0.7f))
        drawRoundRect(Brush.linearGradient(listOf(theme.boardA, theme.boardB), Offset.Zero, Offset(size.width, size.height)), Offset.Zero, size, CornerRadius(u * 0.7f))
        drawRoundRect(theme.line, Offset.Zero, size, CornerRadius(u * 0.7f), style = Stroke(u * 0.12f))

        val used = s.players.map { it.color }.toSet()
        // yards
        for (color in 0..3) {
            val r0 = if (color == 0 || color == 1) 0 else 9
            val c0 = if (color == 0 || color == 3) 0 else 9
            val base = LudoSeatColors[color]
            val alpha = if (color in used) 1f else 0.35f
            drawRoundRect(
                Brush.linearGradient(listOf(clShade(base, 0.12f), clShade(base, -0.18f)), Offset(c0 * u, r0 * u), Offset((c0 + 6) * u, (r0 + 6) * u)),
                Offset(c0 * u + u * 0.15f, r0 * u + u * 0.15f), Size(u * 5.7f, u * 5.7f), CornerRadius(u * 0.6f), alpha = alpha
            )
            drawRoundRect(Color(0xFFFFFBF0), Offset(c0 * u + u * 0.9f, r0 * u + u * 0.9f), Size(u * 4.2f, u * 4.2f), CornerRadius(u * 0.5f), alpha = alpha)
            for (t in 0 until 4) {
                val o = cellCenter(LudoBoard.yardCell(color, t), u)
                drawCircle(clShade(base, -0.3f).copy(alpha = 0.35f * alpha), u * 0.62f, o)
                drawCircle(base.copy(alpha = 0.25f * alpha), u * 0.5f, o)
                drawSeatGlyph(color % 6, o, u * 0.16f)
            }
        }
        // ring squares
        for (i in 0 until LudoBoard.TRACK) {
            val rc = LudoBoard.RING[i]
            val tl = Offset(rc.second * u + u * 0.04f, rc.first * u + u * 0.04f)
            val startOf = (0..3).firstOrNull { LudoBoard.startSquare(it) == i }
            val fill = if (startOf != null) LudoSeatColors[startOf] else Color(0xFFFFFDF6)
            drawRoundRect(fill, tl, Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.14f), alpha = if (startOf != null && startOf !in used) 0.4f else 1f)
            drawRoundRect(theme.line.copy(alpha = 0.7f), tl, Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.14f), style = Stroke(u * 0.035f))
            if (LudoBoard.isSafe(i) && startOf == null) {
                drawSeatGlyph(4, Offset((rc.second + 0.5f) * u, (rc.first + 0.5f) * u), u * 0.2f)
            }
        }
        // home columns
        for (color in 0..3) {
            val base = LudoSeatColors[color]
            for (p in 51..55) {
                val rc = LudoBoard.homeColumnCell(color, p)
                val tl = Offset(rc.second * u + u * 0.04f, rc.first * u + u * 0.04f)
                drawRoundRect(base.copy(alpha = if (color in used) 0.75f else 0.28f), tl, Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.14f))
                drawRoundRect(theme.line.copy(alpha = 0.6f), tl, Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.14f), style = Stroke(u * 0.035f))
            }
        }
        // centre triangles
        val cx = 7.5f * u; val cy = 7.5f * u
        val corners = listOf(Offset(6 * u, 6 * u) to Offset(6 * u, 9 * u), Offset(6 * u, 6 * u) to Offset(9 * u, 6 * u), Offset(9 * u, 6 * u) to Offset(9 * u, 9 * u), Offset(6 * u, 9 * u) to Offset(9 * u, 9 * u))
        for (color in 0..3) {
            val (a, b) = corners[color]
            val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(cx, cy); close() }
            drawPath(path, LudoSeatColors[color].copy(alpha = if (color in used) 1f else 0.3f))
            drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(u * 0.04f))
        }
        drawCircle(Color.White.copy(alpha = 0.9f), u * 0.28f, Offset(cx, cy))

        // --- tokens ---
        val flyingIdx = flight?.let { it.seat * 4 + it.token }
        val returningIdx = flight?.returning?.map { it.first * 4 + it.second }?.toSet() ?: emptySet()
        // group by displayed cell to fan out stacks
        val groups = HashMap<Pair<Int, Int>, MutableList<Int>>()
        for (i in shown.indices) {
            if (i == flyingIdx || i in returningIdx) continue
            val rc = tokenCell(i / 4, i % 4, shown[i])
            groups.getOrPut(rc) { mutableListOf() }.add(i)
        }
        fun drawToken(idx: Int, o: Offset, rad: Float, lift: Float = 0f, scale: Float = 1f) {
            val seat = idx / 4
            val color = s.players[seat].color
            drawMarble(o, rad, LudoSeatColors[color % 4], color % 6, glyph, lift = lift, scale = scale)
        }
        val badge = TextStyle(fontSize = (u * 0.5f / density * 1f).sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B1B1B))
        val ordered = groups.entries.sortedBy { it.key.first }
        for ((rc, members) in ordered) {
            val base = cellCenter(rc, u)
            val n = members.size
            members.forEachIndexed { k, idx ->
                val off = if (n == 1) Offset.Zero else {
                    val a = 2.0 * PI * k / n - PI / 2
                    Offset((cos(a) * u * 0.2f).toFloat(), (sin(a) * u * 0.2f).toFloat())
                }
                val rad = if (n == 1) u * 0.36f else u * 0.28f
                val o = base + off
                val seat = idx / 4
                val token = idx % 4
                val isLegal = seat == s.currentPlayerIndex && token in legal
                val isFlightTarget = flight == null && s.eventSeat == seat && s.eventToken == token
                if (isLegal) {
                    drawCircle(Color.White.copy(alpha = 0.3f + 0.5f * pulse), rad * 1.38f, o, style = Stroke(u * 0.07f))
                    drawCircle(Color(0xFFFFE066).copy(alpha = 0.35f + 0.4f * pulse), rad * 1.22f, o, style = Stroke(u * 0.05f))
                }
                if (hint == token && seat == s.currentPlayerIndex) {
                    drawCircle(Color(0xFFFFD21F), rad * 1.5f, o, style = Stroke(u * 0.1f))
                }
                drawToken(idx, o, rad, scale = if (isFlightTarget) landPulse else 1f)
                if (isLegal) {
                    val layout = measurer.measure("${token + 1}", badge)
                    val bx = o.x + rad * 0.55f; val by = o.y - rad * 1.45f
                    drawCircle(Color.White, u * 0.2f, Offset(bx, by))
                    drawCircle(Color(0xFF222222), u * 0.2f, Offset(bx, by), style = Stroke(u * 0.04f))
                    drawText(layout, topLeft = Offset(bx - layout.size.width / 2f, by - layout.size.height / 2f))
                }
            }
        }
        // the hopping token
        if (flight != null) {
            val route = flight.route
            val segs = (route.size - 1).coerceAtLeast(1)
            val prog = flightProgress.coerceIn(0f, segs.toFloat())
            val idx = prog.toInt().coerceAtMost(segs - 1)
            val local = prog - idx
            val a = cellCenter(route[idx], u)
            val b = cellCenter(route[(idx + 1).coerceAtMost(route.size - 1)], u)
            val te = local * local * (3f - 2f * local)
            val pos = Offset(a.x + (b.x - a.x) * te, a.y + (b.y - a.y) * te)
            drawToken(flight.seat * 4 + flight.token, pos, u * 0.36f, lift = sin(PI * te).toFloat() * u * 0.3f, scale = 1f + 0.06f * sin(PI * te).toFloat())
            // captured tokens stay where they were until the mover lands, then fly home
            flight.returning.forEach { (rs, rt, fromCell) ->
                val toCell = LudoBoard.yardCell(s.players[rs].color, rt)
                val t = returnProgress.coerceIn(0f, 1f)
                val reached = flightProgress >= segs - 0.001f
                val from = cellCenter(fromCell, u)
                val to = cellCenter(toCell, u)
                val p = if (reached) t else 0f
                val q = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
                drawToken(rs * 4 + rt, q, u * 0.36f, lift = sin(PI * p).toFloat() * u * 0.5f, scale = 1f - 0.25f * p)
            }
        }
    }
}
