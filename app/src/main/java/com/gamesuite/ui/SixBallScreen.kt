package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.sixball.SixBallBoard
import com.gamesuite.games.sixball.SixBallGame
import com.gamesuite.games.sixball.SixBallLevels
import com.gamesuite.games.sixball.SixBallProgress
import com.gamesuite.games.sixball.SixBallProgressStore
import com.gamesuite.games.sixball.SixBallRace
import com.gamesuite.games.sixball.SixBallRaceRules
import com.gamesuite.games.sixball.SixBallReject
import com.gamesuite.games.sixball.SixBallRules
import com.gamesuite.games.sixball.SixBallStatus
import com.gamesuite.games.sixball.SIXBALL_BALLS
import com.gamesuite.games.sixball.Tilt
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private class SbTheme(
    val bg: Color, val text: Color, val card: Color, val woodA: Color, val woodB: Color,
    val floorA: Color, val floorB: Color, val groove: Color, val post: Color
)

@Composable
private fun sbTheme(dark: Boolean) = if (!dark) SbTheme(
    bg = Color(0xFFF6EFE4), text = Color(0xFF3A2E22), card = Color(0xFFEBDDC6),
    woodA = Color(0xFFC49A66), woodB = Color(0xFF8E6538),
    floorA = Color(0xFFEFE3CC), floorB = Color(0xFFDCCBAA), groove = Color(0xFFB59B74), post = Color(0xFF5B4630)
) else SbTheme(
    bg = Color(0xFF17130E), text = Color(0xFFF3E9DB), card = Color(0xFF2A221A),
    woodA = Color(0xFF7A5634), woodB = Color(0xFF4A3220),
    floorA = Color(0xFF3A3024), floorB = Color(0xFF2A2218), groove = Color(0xFF55452F), post = Color(0xFFB8A27E)
)

/** The six ball colours (Okabe-Ito, colour-blind safe) are shared with Chinese Checkers; the shape is always drawn too. */
private val sbBallColors = ChineseSeatColors

/**
 * Six-Ball Puzzle on a wooden tray. Tilt with the four arrow buttons (always on screen) or by swiping the
 * tray: every ball rolls until a post, the rail or another ball stops it, with a little lean of the whole
 * tray. Each ball wears its own symbol AND colour, and so does its slot, so nobody has to tell hues apart.
 * Reduced Motion snaps instead of rolling; every state change is announced through a live region.
 * Solo play shows stars against par; a race (pass and play, Nearby, Online) shows the finishing order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SixBallScreen(
    sessionManager: GameSessionManager,
    game: SixBallGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    dailyEpochDay: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val theme = sbTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()
    val store = remember(androidContext) { SixBallProgressStore(androidContext.applicationContext) }
    val records by remember(store) { store.records }.collectAsState(initial = emptyMap())

    val board by game.board
    val race by game.race
    val hostLeft by game.hostLeft
    val hintMove by game.hintMove
    val hintDead by game.hintDeadEnd

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val isRace = (activeCtx?.players?.size ?: 1) > 1
    val localSeat = activeCtx?.localPlayerIndex ?: 0

    var loading by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    var whyKey by remember { mutableStateOf<SixBallReject?>(null) }
    var blockedFlash by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }
    var recordedSerial by remember { mutableIntStateOf(-1) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networked = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (!networked && sessionManager.tryResume(game)) { loading = false; return@LaunchedEffect }
        val rec = try { store.records.first() } catch (_: Exception) { emptyMap() }
        val id = when {
            dailyEpochDay != null -> SixBallLevels.dailyId(dailyEpochDay)
            extras["start"] == "first" -> SixBallLevels.campaignId(1)
            else -> SixBallLevels.campaignId(SixBallProgress.nextUnsolved(rec))
        }
        game.levelId = id
        // Level generation searches the tray, so it happens off the main thread before the board appears.
        withContext(Dispatchers.Default) { SixBallLevels.byId(id) }
        game.startMatch()
        loading = false
    }

    // ---- animation ------------------------------------------------------------------------
    var drawFrom by remember { mutableStateOf<List<Int>?>(null) }
    var drawTo by remember { mutableStateOf<List<Int>?>(null) }
    var leanDir by remember { mutableStateOf<Tilt?>(null) }
    val roll = remember { Animatable(1f) }
    val land = remember { Animatable(1f) }
    var lastSerial by remember { mutableIntStateOf(-1) }

    LaunchedEffect(board?.serial) {
        val b = board ?: return@LaunchedEffect
        val prevTo = drawTo
        val serialNow = b.serial
        if (serialNow == lastSerial) return@LaunchedEffect
        lastSerial = serialNow
        val animate = !reduced && prevTo != null && b.kind != SixBallBoard.KIND_LOAD && prevTo != b.positions
        if (!animate) {
            drawFrom = b.positions; drawTo = b.positions
            roll.snapTo(1f); land.snapTo(1f); leanDir = null
            return@LaunchedEffect
        }
        val from = prevTo!!
        val dist = (0 until SIXBALL_BALLS).maxOf {
            val a = from[it]; val c = b.positions[it]
            max(abs(a % b.level.width - c % b.level.width), abs(a / b.level.width - c / b.level.width))
        }
        drawFrom = from; drawTo = b.positions
        leanDir = if (b.kind == SixBallBoard.KIND_MOVE) b.moves.lastOrNull()?.let { Tilt.fromCode(it) } else null
        playSfx(SfxKind.WHOOSH)
        roll.snapTo(0f)
        val ms = gameSpeed.scale((150L + 55L * dist.coerceAtMost(7)))
        roll.animateTo(1f, tween(ms.toInt().coerceAtLeast(90), easing = CubicBezierEasing(0.35f, 0f, 0.65f, 1f)))
        leanDir = null
        playSfx(SfxKind.SOLID_THUNK)
        haptics(HapticSignal.NORMAL_ACTION)
        val newlySettled = (0 until SIXBALL_BALLS).count { b.positions[it] == b.level.target[it] && from[it] != b.level.target[it] }
        if (newlySettled > 0 && !b.solved) { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.ESCALATING) }
        land.snapTo(1.18f)
        land.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMediumLow))
    }

    // ---- recording and celebration ---------------------------------------------------------
    LaunchedEffect(board?.serial, board?.solved) {
        val b = board ?: return@LaunchedEffect
        if (!b.solved || b.kind == SixBallBoard.KIND_LOAD && b.moves.isEmpty()) return@LaunchedEffect
        if (recordedSerial == b.serial) return@LaunchedEffect
        recordedSerial = b.serial
        if (!isRace) {
            newBest = try { store.recordSolve(b.level.id, b.moveCount, game.starsNow()) } catch (_: Exception) { false }
        }
        // let the last roll finish before the fanfare
        delay(gameSpeed.scale(380L))
        playSfx(SfxKind.SUCCESS_CHIME)
        haptics(HapticSignal.CELEBRATION)
        if (!reduced) {
            burst.spawn(Offset(0.5f, 0.35f), 64, sbBallColors + Color.White, speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        }
    }
    LaunchedEffect(race?.raceOver) {
        val r = race ?: return@LaunchedEffect
        if (!r.raceOver) return@LaunchedEffect
        val mine = r.racers.getOrNull(if (isNetworked) localSeat else 0)
        if (isNetworked && mine != null) {
            if (mine.playerId in r.winnerIds) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
            else { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
        } else if (r.winnerIds.isNotEmpty()) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    val helpTitle = stringResource(R.string.sbp_help_title)
    val helpText = stringResource(R.string.sbp_help_text)
    val b = board
    if (b == null || loading) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = theme.bg, buttonContent = theme.text
        ) {
            Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (isNetworked && game.localSeat != 0) R.string.ccl_waiting_host else R.string.sbp_setting_up),
                    color = theme.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    val level = b.level
    val shapeNames = stringArrayResource(R.array.cc_shape_names)
    val colorNames = stringArrayResource(R.array.sbp_color_names)
    val dirNames = stringArrayResource(R.array.sbp_dir_names)
    val settledNow = SixBallRules.settled(level, b.positions.toIntArray())
    val onSlots = settledNow.count { it }
    val levelNumber = SixBallLevels.campaignNumber(level.id)
    val titleText = when {
        levelNumber != null -> stringResource(R.string.sbp_level, levelNumber)
        else -> stringResource(R.string.sbp_daily)
    }
    val stars = game.starsNow()
    val canPlay = game.canPlay()

    fun tryTilt(dir: Tilt) {
        val reason = game.explainTilt(dir)
        if (reason == SixBallReject.NONE) { whyKey = null; game.tilt(dir) }
        else {
            whyKey = reason; blockedFlash++
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
        }
    }

    val statusText = run {
        val racer = race
        when {
            race?.raceOver == true -> {
                val winner = racer?.racers?.firstOrNull { it.playerId in racer.winnerIds }
                if (winner == null) stringResource(R.string.sbp_race_nowinner)
                else youAware(winner.displayName, R.string.sbp_race_winner, R.string.sbp_race_winner_you, winner.moves)
            }
            b.solved && !isRace -> stringResource(R.string.sbp_ann_solved, b.moveCount, level.par, stars)
            b.solved -> stringResource(R.string.sbp_ann_solved_race, b.moveCount)
            b.kind == SixBallBoard.KIND_MOVE -> stringResource(R.string.sbp_ann_moved, dirNames[b.moves.last().coerceIn(0, 3)], onSlots)
            b.kind == SixBallBoard.KIND_UNDO -> stringResource(R.string.sbp_ann_undo, onSlots)
            b.kind == SixBallBoard.KIND_RESTART -> stringResource(R.string.sbp_ann_restart)
            else -> stringResource(R.string.sbp_ann_start, level.par)
        }
    }
    val hintText = when {
        hintDead -> stringResource(R.string.sbp_hint_dead)
        hintMove != null -> stringResource(R.string.sbp_hint_line, dirNames[hintMove!!.ordinal])
        else -> null
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            SixBallReject.NO_MOVE -> stringResource(R.string.sbp_why_blocked)
            SixBallReject.SOLVED -> stringResource(R.string.sbp_why_solved)
            SixBallReject.HANDOFF -> stringResource(R.string.sbp_why_handoff)
            SixBallReject.NOT_PLAYING -> stringResource(R.string.sbp_why_not_playing)
            SixBallReject.WAITING_HOST -> stringResource(R.string.ccl_waiting_host)
            SixBallReject.OVER -> stringResource(R.string.cc_why_over)
            SixBallReject.NONE -> null
        }
    }

    val ballDescriptions = (0 until SIXBALL_BALLS).map { i ->
        val p = b.positions[i]
        stringResource(
            R.string.sbp_ball_desc, colorNames[i], shapeNames[i], p / level.width + 1, p % level.width + 1,
            if (settledNow[i]) stringResource(R.string.sbp_on_slot) else ""
        )
    }
    val boardDescription = stringResource(R.string.sbp_board_desc, level.width, level.height, ballDescriptions.joinToString("; "))

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = if (isRace) race?.raceOver != true else true,
        onLeave = game::leaveSession,
        onAbort = { if (!isRace && game.solvedLevels.value.isNotEmpty()) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = isRace || game.solvedLevels.value.isEmpty(),
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = if (isRace) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(R.string.sbp_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("six-ball-puzzle", ready = !loading)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("six-ball-puzzle", openCoach)
                    Column(Modifier.weight(1f)) {
                        Text(titleText, color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.sbp_counts, b.moveCount, level.par, onSlots, SIXBALL_BALLS) +
                                (records[level.id]?.let { " · " + stringResource(R.string.sbp_best, it.bestMoves) } ?: ""),
                            color = theme.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!isRace && levelNumber != null) {
                        OutlinedButton(
                            onClick = { showPicker = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
                            modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.sbp_levels)) }
                    }
                }
                if (race != null) RacerChips(race!!, theme, isNetworked, localSeat)
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                if (hintText != null) ClStatusLine(hintText, theme.text, Modifier.fillMaxWidth())
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val boardContent: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod, contentAlignment = Alignment.Center) {
                    val fit = remember(maxWidth, maxHeight, level.width, level.height) {
                        fitBoard(maxWidth.value, maxHeight.value, columns = level.width, rows = level.height, framePx = 14f, minCellPx = 0f, maxCellPx = 72f)
                    }
                    val cellDp = fit.cellPx
                    val frameDp = 14f
                    val from = drawFrom ?: b.positions
                    val to = drawTo ?: b.positions
                    val t = roll.value
                    val lean = leanDir
                    Box(
                        Modifier.graphicsLayer {
                            val amount = if (lean != null) sin(PI * t).toFloat() * 5f else 0f
                            if (lean != null) {
                                rotationY = lean.dx * amount
                                rotationX = -lean.dy * amount
                                cameraDistance = 14f * density
                            }
                        }
                    ) {
                        TrayCanvas(
                            level = level, from = from, to = to, progress = t, landScale = land.value,
                            hint = hintMove, theme = theme, cellDp = cellDp, frameDp = frameDp,
                            reduced = reduced, colorblind = colorblind, flash = blockedFlash,
                            description = boardDescription,
                            onSwipe = { dir -> tryTilt(dir) }
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val over = race?.raceOver == true
            val handoff = isRace && !isNetworked && race?.awaitingHandoff == true && !over

            val pinned: @Composable () -> Unit = {
                when {
                    handoff -> {
                        val next = race!!.racers.firstOrNull { it.status == SixBallStatus.WAITING }
                        Button(
                            onClick = { game.beginNextRacer() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        ) { Text(stringResource(R.string.sbp_handoff_ready, next?.let { androidContext.seatLabel(it.displayName) } ?: ""), fontWeight = FontWeight.Bold) }
                    }
                    over -> ResultButtons(
                        primaryLabel = stringResource(R.string.ccl_play_again),
                        primaryEnabled = !hostLeft,
                        onPrimary = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                    b.solved && !isRace -> ResultButtons(
                        primaryLabel = stringResource(if (game.nextLevelId() != null) R.string.sbp_next_level else R.string.sbp_replay),
                        primaryEnabled = true,
                        onPrimary = { game.playAgain(); newBest = false },
                        onLeave = { game.leaveSession() },
                        secondaryLabel = stringResource(R.string.sbp_replay),
                        onSecondary = { game.restartLevel(); newBest = false }
                    )
                    else -> DPad(theme, hintMove, onTilt = ::tryTilt)
                }
            }

            val secondary: @Composable () -> Unit = {
                if (b.solved && !isRace) SolvedCard(theme, stars, b.moveCount, level.par, newBest, game.hintsUsed.value > 0)
                if (over) ResultsCard(race!!, theme, isNetworked, localSeat)
                else if (handoff) HandoffCard(race!!, theme)
                if (isNetworked && !over && b.solved) {
                    ClStatusLine(stringResource(R.string.sbp_waiting_race), theme.text, Modifier.fillMaxWidth())
                }
                if (hostLeft) ClStatusLine(stringResource(R.string.ccl_host_left), theme.text, Modifier.fillMaxWidth(), emphasized = true)
                Spacer(Modifier.height(6.dp))
                // once a solo tray is solved the pinned result buttons (Next / Replay / Back) take over from Undo / Restart / Hint
                if (!over && !handoff && !(b.solved && !isRace)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val oc = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
                        OutlinedButton(onClick = { game.undo() }, enabled = game.canUndo, colors = oc, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ccl_undo)) }
                        OutlinedButton(onClick = { game.restartLevel() }, enabled = b.moves.isNotEmpty() && (!isRace || canPlay), colors = oc, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sbp_restart)) }
                        if (!isRace) {
                            OutlinedButton(onClick = { scope.launch { game.requestHint() } }, enabled = !b.solved, colors = oc, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ccl_hint)) }
                        } else {
                            OutlinedButton(onClick = { game.giveUp() }, enabled = canPlay, colors = oc, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sbp_give_up)) }
                        }
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.44f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header(); secondary()
                        }
                        Spacer(Modifier.height(6.dp))
                        pinned()
                    }
                    boardContent(Modifier.weight(0.56f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    boardContent(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { secondary() }
                    Spacer(Modifier.height(6.dp))
                    pinned()
                }
            }
        }
    }

    if (showPicker) {
        LevelPicker(records, game, theme, onClose = { showPicker = false }, onPick = { n ->
            showPicker = false
            scope.launch {
                val id = SixBallLevels.campaignId(n)
                withContext(Dispatchers.Default) { SixBallLevels.byId(id) }
                newBest = false
                game.startLevel(id)
            }
        })
    }
}

// ------------------------------------------------------------------------------------------
// Pieces of chrome
// ------------------------------------------------------------------------------------------

@Composable
private fun DPad(theme: SbTheme, hint: Tilt?, onTilt: (Tilt) -> Unit) {
    val names = stringArrayResource(R.array.sbp_dir_names)
    val arrows = mapOf(Tilt.UP to "▲", Tilt.RIGHT to "▶", Tilt.DOWN to "▼", Tilt.LEFT to "◀")
    val order = listOf(Tilt.LEFT, Tilt.UP, Tilt.DOWN, Tilt.RIGHT)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { d ->
            val desc = stringResource(R.string.sbp_tilt_desc, names[d.ordinal]) + if (hint == d) " " + stringResource(R.string.sbp_suggested) else ""
            Button(
                onClick = { onTilt(d) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = desc },
                colors = if (hint == d) ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD21F), contentColor = Color(0xFF1B1B1B))
                else ButtonDefaults.buttonColors(containerColor = theme.woodB, contentColor = Color.White)
            ) { Text(arrows.getValue(d), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultButtons(
    primaryLabel: String, primaryEnabled: Boolean, onPrimary: () -> Unit, onLeave: () -> Unit,
    secondaryLabel: String? = null, onSecondary: () -> Unit = {}
) {
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.heightIn(min = 52.dp)) { Text(primaryLabel, fontWeight = FontWeight.Bold) }
        if (secondaryLabel != null) OutlinedButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 52.dp)) { Text(secondaryLabel) }
        OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.ccl_back_to_menu)) }
    }
}

@Composable
private fun StarsRow(count: Int, sizeDp: Int = 36) {
    val desc = stringResource(R.string.sbp_stars_desc, count)
    Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Canvas(Modifier.size(sizeDp.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2 * 0.95f
                val path = Path()
                for (k in 0 until 10) {
                    val a = -PI / 2 + k * PI / 5
                    val rr = if (k % 2 == 0) r else r * 0.45f
                    val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                if (i < count) {
                    drawPath(path, Brush.linearGradient(listOf(Color(0xFFFFE066), Color(0xFFE5A800))))
                    drawPath(path, Color(0xFF8A6200), style = Stroke(r * 0.08f))
                } else {
                    drawPath(path, Color(0x33000000))
                    drawPath(path, Color(0x66000000), style = Stroke(r * 0.08f))
                }
            }
        }
    }
}

@Composable
private fun SolvedCard(theme: SbTheme, stars: Int, moves: Int, par: Int, newBest: Boolean, assisted: Boolean) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StarsRow(stars)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.sbp_solved_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.sbp_solved_line, moves, par), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (newBest) Text(stringResource(R.string.sbp_new_best), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (assisted) Text(stringResource(R.string.sbp_assisted), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun racerStatusText(r: com.gamesuite.games.sixball.SixBallRacer): String = when (r.status) {
    SixBallStatus.SOLVED -> stringResource(R.string.sbp_status_solved, r.moves)
    SixBallStatus.GAVE_UP -> stringResource(R.string.sbp_status_gave_up)
    SixBallStatus.LEFT -> stringResource(R.string.sbp_status_left)
    SixBallStatus.WAITING -> stringResource(R.string.sbp_status_waiting)
    else -> stringResource(R.string.sbp_status_playing)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RacerChips(race: SixBallRace, theme: SbTheme, networked: Boolean, localSeat: Int) {
    val ctx = LocalContext.current
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        race.racers.forEachIndexed { i, r ->
            val current = if (networked) false else i == race.currentPlayerIndex && !race.raceOver && !race.awaitingHandoff
            val name = ctx.seatLabel(r.displayName) + if (networked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
            val status = racerStatusText(r)
            val accent = sbBallColors[i % 6]
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (current) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .border(if (current) 2.dp else 1.dp, if (current) accent else theme.text.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$name: $status" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClSeatBadge(accent, i % 6, 20)
                Spacer(Modifier.width(5.dp))
                Text("$name · $status", color = theme.text, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HandoffCard(race: SixBallRace, theme: SbTheme) {
    val next = race.racers.firstOrNull { it.status == SixBallStatus.WAITING }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.sbp_handoff_title, next?.let { LocalContext.current.seatLabel(it.displayName) } ?: ""),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
            )
            Text(stringResource(R.string.sbp_handoff_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResultsCard(race: SixBallRace, theme: SbTheme, networked: Boolean, localSeat: Int) {
    val androidCtx = LocalContext.current
    val ranked = SixBallRaceRules.ranking(race.racers)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sbp_race_over), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (networked) {
                val mine = race.racers.getOrNull(localSeat)
                if (mine != null) Text(stringResource(if (mine.playerId in race.winnerIds) R.string.ccl_you_won else R.string.ccl_you_lost), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            ranked.forEachIndexed { k, r ->
                Text(
                    stringResource(R.string.sbp_race_line, k + 1, androidCtx.seatLabel(r.displayName), racerStatusText(r)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (r.playerId in race.winnerIds) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun LevelPicker(
    records: Map<String, com.gamesuite.games.sixball.SixBallRecord>, game: SixBallGame, theme: SbTheme,
    onClose: () -> Unit, onPick: (Int) -> Unit
) {
    val current = SixBallLevels.campaignNumber(game.board.value?.level?.id ?: "")
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.sbp_picker_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.sbp_picker_stars, SixBallProgress.campaignStars(records), SixBallLevels.CAMPAIGN_COUNT * 3),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(60.dp), modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items((1..SixBallLevels.CAMPAIGN_COUNT).toList()) { n ->
                        val rec = records[SixBallLevels.campaignId(n)]
                        val par = SixBallLevels.recipe(n).par
                        val desc = stringResource(R.string.sbp_level_tile, n, par, rec?.stars ?: 0)
                        Column(
                            Modifier
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (n == current) theme.woodA.copy(alpha = 0.5f) else theme.card)
                                .border(1.dp, theme.text.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .clickable { onPick(n) }
                                .padding(6.dp)
                                .semantics(mergeDescendants = true) { contentDescription = desc },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("$n", fontWeight = FontWeight.Bold, color = theme.text)
                            Text("★".repeat(rec?.stars ?: 0).ifEmpty { "–" }, color = Color(0xFFB88400), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text(stringResource(R.string.sbp_close)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// The tray
// ------------------------------------------------------------------------------------------

@Composable
private fun TrayCanvas(
    level: com.gamesuite.games.sixball.SixBallLevel, from: List<Int>, to: List<Int>, progress: Float, landScale: Float,
    hint: Tilt?, theme: SbTheme, cellDp: Float, frameDp: Float, reduced: Boolean, colorblind: Boolean, flash: Int,
    description: String, onSwipe: (Tilt) -> Unit
) {
    val w = level.width
    val h = level.height
    val pulse = if (reduced) 0.6f else rememberInfiniteTransition(label = "sb-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sb-pulse-a"
    ).value
    val dens = LocalDensity.current
    val threshold = with(dens) { 22.dp.toPx() }
    val glyph = if (colorblind) 0.95f else 0.62f
    val swipe by rememberUpdatedState(onSwipe)
    Canvas(
        Modifier
            .size(width = (cellDp * w + frameDp * 2).dp, height = (cellDp * h + frameDp * 2).dp)
            .semantics { contentDescription = description }
            .pointerInput(level.id) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDragEnd = {
                        val ax = abs(total.x); val ay = abs(total.y)
                        if (max(ax, ay) >= threshold) {
                            swipe(if (ax > ay) (if (total.x > 0) Tilt.RIGHT else Tilt.LEFT) else (if (total.y > 0) Tilt.DOWN else Tilt.UP))
                        }
                    },
                    onDragCancel = { total = Offset.Zero }
                ) { change, drag -> change.consume(); total += drag }
            }
    ) {
        val f = frameDp * dens.density
        val u = cellDp * dens.density
        fun center(cell: Int) = Offset(f + (cell % w + 0.5f) * u, f + (cell / w + 0.5f) * u)

        // wooden tray with raised rails
        val trayCorner = CornerRadius(u * 0.4f)
        drawRoundRect(Color(0x44000000), Offset(f * 0.15f, f * 0.3f), size, trayCorner)
        drawRoundRect(Brush.linearGradient(listOf(theme.woodA, theme.woodB), Offset.Zero, Offset(size.width, size.height)), Offset.Zero, size, trayCorner)
        drawRoundRect(Color(0x55000000), Offset.Zero, size, trayCorner, style = Stroke(u * 0.05f))
        // wood grain
        for (g in 0 until 7) {
            val gy = size.height * (g + 0.5f) / 7f
            drawLine(Color(0x14000000), Offset(f * 0.4f, gy), Offset(size.width - f * 0.4f, gy + u * 0.06f), strokeWidth = u * 0.025f)
        }
        // recessed floor
        val fl = Offset(f * 0.85f, f * 0.85f)
        val fs = Size(size.width - f * 1.7f, size.height - f * 1.7f)
        drawRoundRect(Brush.verticalGradient(listOf(theme.floorB, theme.floorA)), fl, fs, CornerRadius(u * 0.25f))
        drawRoundRect(Color(0x55000000), fl, fs, CornerRadius(u * 0.25f), style = Stroke(u * 0.07f))
        // grooves between cells
        for (x in 1 until w) drawLine(theme.groove.copy(alpha = 0.35f), Offset(f + x * u, f), Offset(f + x * u, f + h * u), strokeWidth = u * 0.025f)
        for (y in 1 until h) drawLine(theme.groove.copy(alpha = 0.35f), Offset(f, f + y * u), Offset(f + w * u, f + y * u), strokeWidth = u * 0.025f)

        // slots: a socket carrying the symbol and colour of the ball that belongs there
        for (i in 0 until SIXBALL_BALLS) {
            val c = center(level.target[i])
            val col = sbBallColors[i]
            val occupied = to[i] == level.target[i] && progress >= 0.999f
            drawCircle(Color(0x66000000), u * 0.43f, Offset(c.x, c.y + u * 0.03f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2118), Color(0xFF4A3B2A)), c, u * 0.42f), u * 0.4f, c)
            drawCircle(col.copy(alpha = 0.9f), u * 0.4f, c, style = Stroke(u * 0.06f))
            if (!occupied) drawSeatGlyph(i % 6, c, u * 0.17f)
            else drawCircle(Color.White.copy(alpha = 0.25f + 0.45f * pulse), u * 0.47f, c, style = Stroke(u * 0.06f))
        }
        // posts
        for (cell in level.walls) {
            val c = center(cell)
            drawRoundRect(Color(0x55000000), Offset(c.x - u * 0.4f + u * 0.05f, c.y - u * 0.4f + u * 0.09f), Size(u * 0.8f, u * 0.8f), CornerRadius(u * 0.16f))
            drawRoundRect(
                Brush.linearGradient(listOf(clShade(theme.post, 0.28f), theme.post, clShade(theme.post, -0.3f)), Offset(c.x - u * 0.4f, c.y - u * 0.4f), Offset(c.x + u * 0.4f, c.y + u * 0.4f)),
                Offset(c.x - u * 0.4f, c.y - u * 0.4f), Size(u * 0.8f, u * 0.8f), CornerRadius(u * 0.16f)
            )
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(c.x - u * 0.34f, c.y - u * 0.34f), Size(u * 0.68f, u * 0.2f), CornerRadius(u * 0.1f))
            drawRoundRect(Color(0x66000000), Offset(c.x - u * 0.4f, c.y - u * 0.4f), Size(u * 0.8f, u * 0.8f), CornerRadius(u * 0.16f), style = Stroke(u * 0.03f))
        }
        // hint arrow on the rail
        if (hint != null) {
            val mid = Offset(size.width / 2, size.height / 2)
            val tip = Offset(mid.x + hint.dx * (size.width / 2 - f * 0.1f), mid.y + hint.dy * (size.height / 2 - f * 0.1f))
            val back = Offset(tip.x - hint.dx * f * 0.8f, tip.y - hint.dy * f * 0.8f)
            val side = Offset(-hint.dy * f * 0.5f, hint.dx * f * 0.5f)
            val arrow = Path().apply {
                moveTo(tip.x, tip.y); lineTo(back.x + side.x, back.y + side.y); lineTo(back.x - side.x, back.y - side.y); close()
            }
            drawPath(arrow, Color(0xFFFFD21F).copy(alpha = 0.55f + 0.4f * pulse))
            drawPath(arrow, Color(0xFF6B4E00), style = Stroke(u * 0.03f))
        }
        // balls, drawn in a stable order; a ball on its slot gets a ring
        val t = progress.coerceIn(0f, 1f)
        for (i in 0 until SIXBALL_BALLS) {
            val a = center(from[i]); val b = center(to[i])
            val pos = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            val moving = from[i] != to[i] && t < 1f
            val onSlot = to[i] == level.target[i] && t >= 1f
            val scale = (if (moving) 1f else landScale.coerceIn(0.9f, 1.25f)) * (if (onSlot) 1f else 1f)
            drawMarble(pos, u * 0.38f, sbBallColors[i], i % 6, glyph, lift = if (moving) u * 0.08f else 0f, scale = scale)
            if (onSlot) drawCircle(Color.White.copy(alpha = 0.55f), u * 0.4f * scale, pos, style = Stroke(u * 0.035f))
        }
    }
}
