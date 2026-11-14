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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.gamesuite.games.chinesecheckers.CCBoard
import com.gamesuite.games.chinesecheckers.CCEnd
import com.gamesuite.games.chinesecheckers.CCPieceCounts
import com.gamesuite.games.chinesecheckers.CCReject
import com.gamesuite.games.chinesecheckers.CCRules
import com.gamesuite.games.chinesecheckers.CCState
import com.gamesuite.games.chinesecheckers.ChineseCheckersGame
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
import kotlin.math.sin

private const val CC_MIN_PITCH_DP = 20f
private const val CC_MAX_PITCH_DP = 48f
// The wooden star (tip overshoot plus its rim) extends ~0.7 pitch beyond the outermost holes, so the canvas
// carries that margin: otherwise the left/right tips are cut flat at the edge of the viewport.
private const val CC_MARGIN = 0.7f
private const val CC_COLS = 13 + 1.4f
private const val CC_ROWS = 15 + 1.4f

/** A piece in flight: [path] holes, the seat that owns it, and how many hops it makes (0 = a step). */
private class CCFlight(val path: List<Int>, val seat: Int, val hops: Int)

private class CCTheme(
    val bg: Color, val text: Color, val card: Color,
    val woodA: Color, val woodB: Color, val woodEdge: Color, val holeDark: Color, val holeLight: Color
)

@Composable
private fun ccTheme(dark: Boolean) = if (!dark) CCTheme(
    bg = Color(0xFFFBF1E6), text = Color(0xFF3A2E22), card = Color(0xFFF0E1CC),
    woodA = Color(0xFFD2A56B), woodB = Color(0xFFB07C45), woodEdge = Color(0xFF5A3A1C),
    holeDark = Color(0xFF45301B), holeLight = Color(0xFF6A4B2B)
) else CCTheme(
    bg = Color(0xFF1C1712), text = Color(0xFFF3E9DB), card = Color(0xFF2A221A),
    woodA = Color(0xFF9A7240), woodB = Color(0xFF7A5428), woodEdge = Color(0xFF2A1A0C),
    holeDark = Color(0xFF2E1F10), holeLight = Color(0xFF523A21)
)

/**
 * Chinese Checkers on a glossy-marble star board. Tap one of your marbles, see where it can go
 * (every empty hole it can step or hop to is ringed), tap a hole and the marble slides or hops
 * along its route. A forgiving nearest-hole tap makes the dense board playable; a TalkBack user
 * gets an explicit list of pieces and destinations instead. Honours Reduced Motion, game speed,
 * colour-blind mode (bigger seat glyphs) and the shared sound / haptic settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChineseCheckersScreen(
    sessionManager: GameSessionManager,
    game: ChineseCheckersGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOMINOES, enabled = musicEnabled)
    val state by game.state
    val hintMove by game.hintMove
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = ccTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val touchExploration = rememberClTouchExploration()
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.piecesPerSeat = CCPieceCounts.fromOptionId(sessionManager.launchOptions.extras["pieces"])
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
    var shownCells by remember { mutableStateOf<List<Int?>?>(null) }
    var flight by remember { mutableStateOf<CCFlight?>(null) }
    val flightProgress = remember { Animatable(0f) }
    val landPulse = remember { Animatable(1f) }
    var lastTurn by remember { mutableIntStateOf(-1) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var whyKey by remember { mutableStateOf<CCReject?>(null) }
    var whyOwner by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        val cur = state ?: return@LaunchedEffect
        val prev = shownCells
        val from = cur.lastFrom
        val to = cur.lastTo
        val seat = cur.lastMover
        val isNextMove = prev != null && cur.turnCount == lastTurn + 1 && from != null && to != null && seat != null
        lastTurn = cur.turnCount
        selected = null
        if (!isNextMove || prev == null || from == null || to == null || seat == null) {
            shownCells = cur.cells
            flight = null
            return@LaunchedEffect
        }
        val owners = IntArray(CCBoard.CELLS) { cur.cells[it] ?: -1 }
        owners[to] = -1
        val route = CCRules.path(owners, from, to)
        val localMover = !isNetworked || seat == localSeat
        try {
            if (reduced) {
                shownCells = cur.cells
            } else {
                shownCells = prev
                flight = CCFlight(route, seat, cur.lastHops)
                flightProgress.snapTo(0f)
                val segs = (route.size - 1).coerceAtLeast(1)
                playSfx(if (cur.lastHops > 0) SfxKind.WHOOSH else SfxKind.LIGHT_TICK)
                flightProgress.animateTo(segs.toFloat(), tween(gameSpeed.scale(170L * segs).toInt().coerceAtLeast(60), easing = LinearEasing))
            }
            shownCells = cur.cells
            flight = null
            playSfx(SfxKind.SOLID_THUNK)
            haptics(if (localMover) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
            if (!reduced) {
                landPulse.snapTo(1.22f)
                landPulse.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMediumLow))
            }
        } finally {
            shownCells = cur.cells
            flight = null
        }
    }

    // Computer turns: think for a natural beat, wait for the last move to land, then play.
    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(650L).coerceAtLeast(400L))
            while (flight != null) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val shortHelpTitle = stringResource(R.string.cc_help_title)
    val helpText = stringResource(R.string.cc_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = shortHelpTitle, helpText = helpText, matchInProgress = true,
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

    val colorNames = stringArrayResource(R.array.cc_color_names)
    val shapeNames = stringArrayResource(R.array.cc_shape_names)
    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    val homeIndexOf = remember(s.players.size) { CCBoard.layout(s.players.size) }
    fun seatColorIndex(i: Int) = homeIndexOf[i] // the home corner doubles as the seat's colour slot
    fun seatDescription(i: Int) = "${colorNames[seatColorIndex(i) % 6]} ${shapeNames[seatColorIndex(i) % 6]}"

    val isMyTurn = game.isMyTurn(s)
    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value
    val goalCounts = remember(s.cells, s.players) {
        s.players.indices.map { seat ->
            val goal = CCRules.goalCells(s.players[seat].homeCorner, s.pieces)
            goal.count { s.cells[it] == seat }
        }
    }

    // ---- board over: celebration -----------------------------------------------------------
    LaunchedEffect(s.boardOver, s.turnCount) {
        if (!s.boardOver) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.players[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        when {
            humanWon -> {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.CELEBRATION)
                if (!reduced) {
                    val tint = ChineseSeatColors[seatColorIndex(winnerSeat) % 6]
                    burst.spawn(Offset(0.5f, 0.32f), 70, listOf(tint, Color.White, Color(0xFFFFD866)),
                        speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
                }
            }
            winnerSeat >= 0 -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
            else -> haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // ---- words -----------------------------------------------------------------------------
    val currentName = seatName(s.currentPlayerIndex)
    val statusText = run {
        val moved = s.lastMover?.let { m ->
            when {
                s.lastHops > 0 -> androidx.compose.ui.res.pluralStringResource(R.plurals.cc_ann_hopped, s.lastHops, seatName(m), s.lastHops)
                else -> stringResource(R.string.cc_ann_stepped, seatName(m))
            }
        }.orEmpty()
        val skipped = s.skippedSeat?.let { youAware(seatName(it), R.string.cc_ann_skipped, R.string.yg_cc_ann_skipped) }.orEmpty()
        val turn = when {
            s.boardOver -> ""
            hostLeft -> stringResource(R.string.ccl_host_left)
            isMyTurn && !isNetworked && s.players.none { it.isBot } -> stringResource(R.string.cc_turn_named, currentName)
            isMyTurn -> stringResource(R.string.cc_turn_you)
            botThinking -> stringResource(R.string.cc_thinking, currentName)
            else -> stringResource(R.string.cc_turn_named, currentName)
        }
        listOf(moved, skipped, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            CCReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.cc_why_wait else R.string.cc_why_cpu)
            CCReject.OPPONENT_PIECE -> stringResource(R.string.cc_why_opponent, whyOwner)
            CCReject.BOXED_IN -> stringResource(R.string.cc_why_boxed)
            CCReject.UNREACHABLE -> stringResource(R.string.cc_why_unreachable)
            CCReject.PICK_A_PIECE -> stringResource(R.string.cc_why_pick)
            CCReject.BOARD_OVER -> stringResource(R.string.cc_why_over)
            CCReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey, whyOwner) {
        if (whyKey != null) { delay(3500); whyKey = null }
    }

    val destinations: Set<Int> = remember(selected, s) { selected?.let { game.destinationsFrom(it) } ?: emptySet() }
    val movable: Set<Int> = remember(s, isMyTurn) { if (isMyTurn) game.movablePieces() else emptySet() }

    fun onTapCell(cell: Int) {
        val reason = game.explainTap(selected, cell)
        when (reason) {
            CCReject.NONE -> {
                val owner = s.cells[cell]
                if (owner != null) {
                    selected = if (selected == cell) null else cell
                    playSfx(SfxKind.LIGHT_TICK)
                    haptics(HapticSignal.LIGHT_TICK)
                } else {
                    val from = selected
                    if (from != null && game.requestMove(from, cell)) selected = null
                }
                whyKey = null
            }
            else -> {
                whyOwner = s.cells.getOrNull(cell)?.let { seatName(it) }.orEmpty()
                whyKey = reason
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
            }
        }
    }

    val boardDescription = stringResource(
        R.string.cc_board_desc,
        s.players.indices.joinToString("; ") { "${seatName(it)} ${seatDescription(it)} ${goalCounts[it]}/${s.pieces}" }
    )

    GameChrome(
        helpTitle = shortHelpTitle,
        helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg,
        buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("chinese-checkers", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("chinese-checkers", openCoach)
                    FlowRow(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        s.players.indices.forEach { i ->
                            SeatChip(
                                name = seatName(i) + if (!isNetworked || i != localSeat) "" else " " + stringResource(R.string.ccl_you_suffix),
                                description = seatDescription(i),
                                color = ChineseSeatColors[seatColorIndex(i) % 6],
                                shape = seatColorIndex(i) % 6,
                                inGoal = goalCounts[i], pieces = s.pieces,
                                isTurn = i == s.currentPlayerIndex && !s.boardOver,
                                isBot = s.players[i].isBot,
                                theme = theme
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod, contentAlignment = Alignment.Center) {
                    val fitPitch = minOf(maxWidth.value / CC_COLS, maxHeight.value / CC_ROWS, CC_MAX_PITCH_DP)
                    val scrolls = fitPitch < CC_MIN_PITCH_DP
                    val pitchDp = if (scrolls) CC_MIN_PITCH_DP else fitPitch
                    val hs = rememberScrollState()
                    val vs = rememberScrollState()
                    // When panning is needed, start centred so neither star tip is the cut one.
                    androidx.compose.runtime.LaunchedEffect(scrolls, hs.maxValue, vs.maxValue) {
                        if (scrolls) { hs.scrollTo(hs.maxValue / 2); vs.scrollTo(vs.maxValue / 2) }
                    }
                    Box(
                        if (scrolls) Modifier.horizontalScroll(hs).verticalScroll(vs) else Modifier,
                        contentAlignment = Alignment.Center
                    ) {
                        CCBoardCanvas(
                            s = s, shown = shownCells ?: s.cells, flight = flight, flightProgress = flightProgress.value,
                            landPulse = landPulse.value, selected = selected, destinations = destinations,
                            movable = if (selected == null) movable else emptySet(), hint = hintMove,
                            theme = theme, pitchDp = pitchDp, reduced = reduced, colorblind = colorblind,
                            seatColorIndex = ::seatColorIndex, description = boardDescription,
                            onTap = ::onTapCell
                        )
                    }
                    // Confetti over the board.
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val footer: @Composable () -> Unit = {
                if (!isNetworked) {
                    Spacer(Modifier.height(6.dp))
                    ClUndoHintRow(
                        canUndo = game.canUndo,
                        canHint = isMyTurn && !s.boardOver,
                        onUndo = { game.undo() },
                        onHint = { scope.launch { game.requestHint() } },
                        contentColor = theme.text
                    )
                }
                if (touchExploration && isMyTurn && !s.boardOver) {
                    Spacer(Modifier.height(6.dp))
                    AccessibleMovePanel(
                        s = s, game = game, selected = selected, onSelect = { selected = it },
                        distanceTo = { c -> CCBoard.distance(c, CCBoard.tip(CCBoard.opposite(s.players[s.currentPlayerIndex].homeCorner))) },
                        theme = theme
                    )
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(10.dp))
                    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
                    val youWon = winner != null && s.players.indexOf(winner) == localSeat && isNetworked
                    ClFinishedPanel(
                        title = winner?.let { youAware(it.displayName, R.string.cc_wins, R.string.yg_win) } ?: stringResource(R.string.cc_draw),
                        subtitle = when {
                            s.endReason == CCEnd.LIMIT -> stringResource(R.string.cc_end_limit)
                            s.endReason == CCEnd.STUCK -> stringResource(R.string.cc_end_stuck)
                            isNetworked && winner == null -> stringResource(R.string.ccl_a_draw)
                            isNetworked && youWon -> stringResource(R.string.ccl_you_won)
                            isNetworked -> stringResource(R.string.ccl_you_lost)
                            else -> null
                        },
                        lines = s.players.indices.map { stringResource(R.string.cc_final_line, seatName(it), goalCounts[it], s.pieces) } +
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
                Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxSize().verticalScroll(rememberScrollState())) {
                        header(); footer()
                    }
                    board(Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.padding(horizontal = 8.dp)) { header() }
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                }
            }
        }
    }
}

@Composable
private fun SeatChip(
    name: String, description: String, color: Color, shape: Int, inGoal: Int, pieces: Int,
    isTurn: Boolean, isBot: Boolean, theme: CCTheme
) {
    val botWord = stringResource(R.string.ccl_cpu_tag)
    val desc = stringResource(R.string.cc_chip_desc, name, description, inGoal, pieces) + if (isBot) " ($botWord)" else ""
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isTurn) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(if (isTurn) 2.dp else 1.dp, if (isTurn) color else theme.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClSeatBadge(color, shape, 20)
        Spacer(Modifier.width(5.dp))
        Text(name, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(5.dp))
        Text("$inGoal/$pieces", color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
    }
}

private fun pitchPos(cell: Int, pitch: Float) =
    Offset((CCBoard.x(cell) + 6.5f + CC_MARGIN) * pitch, (CCBoard.y(cell) + 7.5f + CC_MARGIN) * pitch)

@Composable
private fun CCBoardCanvas(
    s: CCState, shown: List<Int?>, flight: CCFlight?, flightProgress: Float, landPulse: Float,
    selected: Int?, destinations: Set<Int>, movable: Set<Int>, hint: com.gamesuite.games.chinesecheckers.CCMove?,
    theme: CCTheme, pitchDp: Float, reduced: Boolean, colorblind: Boolean,
    seatColorIndex: (Int) -> Int, description: String, onTap: (Int) -> Unit
) {
    // A State read only inside the draw lambda (below), so the pulse redraws the board without recomposing it.
    val pulseState: androidx.compose.runtime.State<Float> = if (reduced) remember { androidx.compose.runtime.mutableStateOf(0.6f) } else rememberInfiniteTransition(label = "cc-pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "cc-pulse-a"
    )
    val tapPitch = remember { FloatArray(1) { 1f } }
    val glyph = if (colorblind) 0.95f else 0.62f

    Canvas(
        Modifier
            .size((pitchDp * CC_COLS).dp, (pitchDp * CC_ROWS).dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val p = tapPitch[0]
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (c in 0 until CCBoard.CELLS) {
                        val o = pitchPos(c, p)
                        val d = (o.x - offset.x) * (o.x - offset.x) + (o.y - offset.y) * (o.y - offset.y)
                        if (d < bestD) { bestD = d; best = c }
                    }
                    if (best >= 0 && bestD <= (0.66f * p) * (0.66f * p)) onTap(best)
                }
            }
            .drawWithCache {
                val p = size.width / CC_COLS
                val center = Offset(size.width / 2f, (7.5f + CC_MARGIN) * p)
                fun tri(corners: List<Int>): Path {
                    val path = Path()
                    corners.forEachIndexed { i, k ->
                        val t = pitchPos(CCBoard.tip(k), p)
                        val v = Offset(center.x + (t.x - center.x) * 1.09f, center.y + (t.y - center.y) * 1.09f)
                        if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
                    }
                    path.close()
                    return path
                }
                val t1 = tri(listOf(0, 2, 4))
                val t2 = tri(listOf(1, 3, 5))
                val round = Stroke(p * 1.0f, join = StrokeJoin.Round, cap = StrokeCap.Round)
                val edge = Stroke(p * 1.22f, join = StrokeJoin.Round, cap = StrokeCap.Round)
                val wood = Brush.linearGradient(listOf(theme.woodA, theme.woodB), Offset(0f, 0f), Offset(size.width, size.height))
                val glow = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), center, p * 7f)
                val holeBrushes = Array(CCBoard.CELLS) { c ->
                    val o = pitchPos(c, p)
                    Brush.radialGradient(listOf(theme.holeLight, theme.holeDark), Offset(o.x, o.y - p * 0.1f), p * 0.4f)
                }
                val tint = HashMap<Int, Pair<Color, Boolean>>() // corner -> (colour, isGoal)
                s.players.indices.forEach { i ->
                    tint[s.players[i].homeCorner] = ChineseSeatColors[seatColorIndex(i) % 6] to false
                }
                onDrawBehind {
                    drawPath(t1, theme.woodEdge, style = edge); drawPath(t2, theme.woodEdge, style = edge)
                    drawPath(t1, wood); drawPath(t2, wood)
                    drawPath(t1, wood, style = round); drawPath(t2, wood, style = round)
                    drawCircle(glow, p * 7f, center)
                    // --- holes, with the seats' home and goal corners tinted ---
                    for (c in 0 until CCBoard.CELLS) {
                        val o = pitchPos(c, p)
                        drawCircle(Color.White.copy(alpha = 0.16f), p * 0.385f, Offset(o.x, o.y + p * 0.05f))
                        drawCircle(holeBrushes[c], p * 0.36f, o)
                        val corner = CCBoard.cornerOf(c)
                        val t = tint[corner]
                        if (t != null && CCBoard.depthIn(c, corner) < CCBoard.rowsFor(s.pieces)) {
                            drawCircle(t.first.copy(alpha = 0.55f), p * 0.33f, o, style = Stroke(p * 0.07f))
                        }
                    }
                }
            }
    ) {
        val p = size.width / CC_COLS
        tapPitch[0] = p
        val pulse = pulseState.value

        // goal-corner glyph in the tip hole, so a goal is told apart by shape as well as colour
        s.players.indices.forEach { i ->
            val goalTip = CCBoard.tip(CCBoard.opposite(s.players[i].homeCorner))
            if (shown[goalTip] == null) {
                val sc = seatColorIndex(i) % 6
                val o = pitchPos(goalTip, p)
                drawSeatGlyph(sc, o, p * 0.14f)
            }
        }

        // --- last move trail ---
        val lf = s.lastFrom; val lt = s.lastTo
        if (flight == null && lf != null && lt != null) {
            val a = pitchPos(lf, p); val b = pitchPos(lt, p)
            val mc = ChineseSeatColors[seatColorIndex(s.lastMover ?: 0) % 6]
            drawLine(mc.copy(alpha = 0.45f), a, b, strokeWidth = p * 0.1f, cap = StrokeCap.Round)
            drawCircle(mc.copy(alpha = 0.55f), p * 0.4f, a, style = Stroke(p * 0.07f))
        }

        // --- destinations ---
        destinations.forEach { c ->
            val o = pitchPos(c, p)
            drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.22f + 0.25f * pulse), p * 0.4f, o)
            drawCircle(Color(0xFFFFE066), p * 0.12f, o)
            drawCircle(Color(0xFFFFE066).copy(alpha = 0.5f + 0.5f * pulse), p * 0.43f, o, style = Stroke(p * 0.08f))
        }

        // --- marbles ---
        val inFlightFrom = flight?.path?.firstOrNull()
        for (c in 0 until CCBoard.CELLS) {
            val owner = shown[c] ?: continue
            if (c == inFlightFrom) continue
            val o = pitchPos(c, p)
            val sc = seatColorIndex(owner) % 6
            val lifted = c == selected
            val scale = if (flight == null && c == lt) landPulse else 1f
            if (movable.contains(c)) {
                drawCircle(Color.White.copy(alpha = 0.25f + 0.35f * pulse), p * 0.47f, o, style = Stroke(p * 0.06f))
            }
            if (lifted) drawCircle(Color(0xFFFFE066), p * 0.5f, o, style = Stroke(p * 0.1f))
            drawMarble(o, p * 0.4f, ChineseSeatColors[sc], sc, glyph, lift = if (lifted) p * 0.14f else 0f, scale = scale)
        }
        // the flying marble
        if (flight != null) {
            val segs = (flight.path.size - 1).coerceAtLeast(1)
            val prog = flightProgress.coerceIn(0f, segs.toFloat())
            val idx = prog.toInt().coerceAtMost(segs - 1)
            val local = prog - idx
            val te = local * local * (3f - 2f * local)
            val a = pitchPos(flight.path[idx], p)
            val b = pitchPos(flight.path[(idx + 1).coerceAtMost(flight.path.size - 1)], p)
            val pos = Offset(a.x + (b.x - a.x) * te, a.y + (b.y - a.y) * te)
            val arc = if (flight.hops > 0) sin(PI * te).toFloat() * p * 0.55f else sin(PI * te).toFloat() * p * 0.12f
            val sc = seatColorIndex(flight.seat) % 6
            drawMarble(pos, p * 0.4f, ChineseSeatColors[sc], sc, glyph, lift = arc, scale = 1f + 0.08f * sin(PI * te).toFloat())
        }

        // --- hint ---
        if (hint != null && flight == null) {
            val a = pitchPos(hint.from, p); val b = pitchPos(hint.to, p)
            drawLine(Color(0xFFFFD21F), a, b, strokeWidth = p * 0.12f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFFFD21F), p * 0.5f, a, style = Stroke(p * 0.09f))
            drawCircle(Color(0xFFFFD21F), p * 0.5f, b, style = Stroke(p * 0.09f))
            drawCircle(Color(0xFFFFD21F), p * 0.13f, b)
        }
    }
}

/** What TalkBack users get instead of a dense hole grid: pick a piece, then pick where it goes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessibleMovePanel(
    s: CCState, game: ChineseCheckersGame, selected: Int?, onSelect: (Int?) -> Unit,
    distanceTo: (Int) -> Int, theme: CCTheme
) {
    val movable = remember(s) { game.movablePieces().sortedBy { distanceTo(it) } }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cc_a11y_panel_title), color = theme.text, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            movable.forEach { c ->
                OutlinedButton(
                    onClick = { onSelect(if (selected == c) null else c) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.cc_a11y_piece, distanceTo(c), game.destinationsFrom(c).size))
                }
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.cc_a11y_dest_title), color = theme.text, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                game.destinationsFrom(selected).sortedBy { distanceTo(it) }.forEach { d ->
                    val gain = distanceTo(selected) - distanceTo(d)
                    OutlinedButton(
                        onClick = { game.requestMove(selected, d); onSelect(null) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.cc_a11y_dest, distanceTo(d), gain))
                    }
                }
            }
        }
    }
}
