package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberPannedProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.carrom.CarromBoard
import com.gamesuite.games.carrom.CarromCoin
import com.gamesuite.games.carrom.CarromFlag
import com.gamesuite.games.carrom.CarromGame
import com.gamesuite.games.carrom.CarromPhysics
import com.gamesuite.games.carrom.CarromReject
import com.gamesuite.games.carrom.CarromRules
import com.gamesuite.games.carrom.CarromShot
import com.gamesuite.games.carrom.CarromSimEvent
import com.gamesuite.games.carrom.CarromSimResult
import com.gamesuite.games.carrom.CarromState
import com.gamesuite.games.cards.CardSounds
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val BOARD_PAD = 40f
private const val BOARD_TOTAL = 740f + 2 * BOARD_PAD
private const val MAX_PULL = 230.0
private const val MIN_PULL = 34.0

private class CarromTheme(val bg: Color, val text: Color, val card: Color)

@Composable
private fun carromTheme(dark: Boolean) = if (!dark) CarromTheme(Color(0xFFF7EEDF), Color(0xFF33261A), Color(0xFFEBDCC2))
else CarromTheme(Color(0xFF1B1611), Color(0xFFF1E6D6), Color(0xFF2A2219))

private val TeamStrikerColors = listOf(Color(0xFF0072B2), Color(0xFFE69F00))

private fun carromGrid(x: Double, y: Double, side: Int): String {
    val (dx, dy) = CarromBoard.rotate((4 - side) % 4, x - CarromBoard.CENTER, y - CarromBoard.CENTER)
    val col = (((dx + CarromBoard.CENTER) / (CarromBoard.SIZE / 5)).toInt()).coerceIn(0, 4)
    val rowFromTop = (((dy + CarromBoard.CENTER) / (CarromBoard.SIZE / 5)).toInt()).coerceIn(0, 4)
    return "${'A' + col}${5 - rowFromTop}"
}

/** The first thing the striker would touch along its line: the end point, and the coin it would hit (id) if any. */
private fun guideEnd(coins: List<CarromCoin>, sx: Double, sy: Double, dx: Double, dy: Double): Triple<Double, Double, Int> {
    var bestT = Double.MAX_VALUE
    var hit = -1
    val rr = CarromBoard.COIN_R + CarromBoard.STRIKER_R
    for (c in coins) {
        val ox = c.x - sx
        val oy = c.y - sy
        val proj = ox * dx + oy * dy
        if (proj <= 0) continue
        val perp2 = ox * ox + oy * oy - proj * proj
        if (perp2 >= rr * rr) continue
        val t = proj - sqrt(rr * rr - perp2)
        if (t in 0.0..bestT) { bestT = t; hit = c.id }
    }
    val lo = CarromBoard.STRIKER_R
    val hi = CarromBoard.SIZE - CarromBoard.STRIKER_R
    var wall = Double.MAX_VALUE
    if (dx > 1e-9) wall = minOf(wall, (hi - sx) / dx) else if (dx < -1e-9) wall = minOf(wall, (lo - sx) / dx)
    if (dy > 1e-9) wall = minOf(wall, (hi - sy) / dy) else if (dy < -1e-9) wall = minOf(wall, (lo - sy) / dy)
    if (wall < bestT) { bestT = wall; hit = -1 }
    if (bestT == Double.MAX_VALUE) bestT = 0.0
    return Triple(sx + dx * bestT, sy + dy * bestT, hit)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarromScreen(
    sessionManager: GameSessionManager,
    game: CarromGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberPannedProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = musicEnabled)
    val state by game.state
    val hintShot by game.hintShot
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = carromTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
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
        game.targetPoints = extras["target"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: 0
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

    // ---- aiming state (local to this device) ---------------------------------------------
    var stand by remember { mutableIntStateOf(500) }
    var aim by remember { mutableIntStateOf(0) }
    var power by remember { mutableIntStateOf(550) }
    var pullEnd by remember { mutableStateOf<Offset?>(null) }
    var whyKey by remember { mutableStateOf<CarromReject?>(null) }

    // ---- playback state ------------------------------------------------------------------
    var playback by remember { mutableStateOf<CarromSimResult?>(null) }
    var playFrame by remember { mutableFloatStateOf(0f) }
    var lastSerial by remember { mutableIntStateOf(-1) }
    val popPulse = remember { Animatable(1f) }
    var popIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val flash = remember { List(4) { Animatable(0f) } }

    LaunchedEffect(state?.shotSerial) {
        val cur = state ?: return@LaunchedEffect
        val first = lastSerial < 0
        val isNew = !first && cur.shotSerial == lastSerial + 1 && cur.last != null
        lastSerial = cur.shotSerial
        if (!isNew) return@LaunchedEffect
        val sim = withContext(Dispatchers.Default) { game.replayOf(cur) } ?: return@LaunchedEffect
        val mine = cur.eventSeat == localSeat || !isNetworked
        val potted = sim.pocketed
        try {
            if (reduced || sim.frames.size < 2) {
                playSfx(SfxKind.SOLID_THUNK, 0f)
            } else {
                playSfx(SfxKind.WHOOSH, 0f)
                haptics(HapticSignal.LIGHT_TICK)
                playback = sim
                playFrame = 0f
                var eventIdx = 0
                var lastNanos = 0L
                var lastHitFrame = -10f
                val last = (sim.frames.size - 1).toFloat()
                while (playFrame < last) {
                    androidx.compose.runtime.withFrameNanos { nanos ->
                        if (lastNanos != 0L) playFrame = (playFrame + (nanos - lastNanos) / 1_000_000_000f * 60f).coerceAtMost(last)
                        lastNanos = nanos
                    }
                    val stepNow = playFrame * CarromPhysics.FRAME_STEPS
                    while (eventIdx < sim.events.size && sim.events[eventIdx].step <= stepNow) {
                        val ev = sim.events[eventIdx++]
                        val pan = (((ev.x / CarromBoard.SIZE.toFloat()) - 0.5f) * 1.2f).coerceIn(-0.8f, 0.8f)
                        when (ev.kind) {
                            CarromSimEvent.POCKET -> {
                                playSfx(SfxKind.SOLID_THUNK, pan)
                                if (mine) haptics(HapticSignal.STRONG_ACTION)
                                val k = ev.other.coerceIn(0, 3)
                                scope.launch { flash[k].snapTo(1f); flash[k].animateTo(0f, tween(450)) }
                            }
                            else -> if (playFrame - lastHitFrame > 2f && ev.speed > 120f) {
                                lastHitFrame = playFrame
                                playSfx(SfxKind.LIGHT_TICK, pan)
                            }
                        }
                    }
                }
                delay(gameSpeed.scale(150L))
            }
        } finally {
            playback = null
        }
        // coins that came back onto the board pop in
        val back = cur.coins.map { it.id }.toSet() - sim.coins.map { it.id }.toSet()
        if (back.isNotEmpty() && !reduced) {
            popIds = back
            popPulse.snapTo(0.2f)
            popPulse.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
            popIds = emptySet()
        }
        val sideEffects = cur.flags
        if (sideEffects and CarromFlag.FOUL != 0) {
            playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE)
        } else if (sideEffects and CarromFlag.OWN != 0) {
            playSfx(SfxKind.SUCCESS_CHIME, 0f)
            if (mine) haptics(HapticSignal.SUCCESS)
        }
    }

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(800L).coerceAtLeast(450L))
            while (playback != null) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.carrom_help_title)
    val helpText = stringResource(R.string.carrom_help_text)
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

    val settled = playback == null
    val isMyTurn = game.isMyTurn(s)
    val shooter = s.players[s.currentPlayerIndex]
    val viewSide = s.players[game.viewSeat().coerceIn(0, s.players.size - 1)].side

    // a fresh aim for each new turn of this device
    LaunchedEffect(s.shotSerial, s.currentPlayerIndex, s.boardOver) {
        val free = CarromRules.freeStands(s, 25)
        stand = free.minByOrNull { abs(it - 500) } ?: 500
        aim = 0
        power = 550
        pullEnd = null
    }
    LaunchedEffect(hintShot) {
        hintShot?.let { stand = it.t; aim = it.aim; power = it.power }
    }

    fun seatName(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    val finishedBoards = game.sessionWins.value.values.sum()
    val whites = s.onBoard(0)
    val blacks = s.onBoard(1)
    val queenText = when {
        s.queenOwner >= 0 -> stringResource(R.string.carrom_queen_owned, if (s.queenOwner == 0) stringResource(R.string.carrom_white) else stringResource(R.string.carrom_black))
        s.queenPending >= 0 -> stringResource(R.string.carrom_queen_pending)
        else -> stringResource(R.string.carrom_queen_on_board)
    }

    fun flick() {
        if (!settled) return
        val draftShot = CarromShot(stand, aim, power)
        val reason = game.explainShot(draftShot)
        if (reason != CarromReject.NONE) {
            whyKey = reason; playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE); return
        }
        whyKey = null
        haptics(HapticSignal.NORMAL_ACTION)
        game.shoot(draftShot)
    }

    // celebration
    LaunchedEffect(s.boardOver, s.shotSerial) {
        if (!s.boardOver) return@LaunchedEffect
        val humanWon = s.winnerTeam >= 0 && s.players.indices.any { s.players[it].team == s.winnerTeam && !s.players[it].isBot && (!isNetworked || it == localSeat) }
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME, 0f)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 70, listOf(TeamStrikerColors[s.winnerTeam], Color.White, Color(0xFFFFD866)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ, 0f)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words ---------------------------------------------------------------------------
    val current = seatName(s.currentPlayerIndex)
    val statusText = run {
        val who = seatName(s.eventSeat ?: s.currentPlayerIndex)
        val n = s.pottedThisShot.size
        val parts = ArrayList<String>()
        if (s.last == null) {
            parts += sayYou(current, R.string.carrom_ann_start, R.string.carrom_ann_start_you)
        } else {
            val own = s.flags and CarromFlag.OWN != 0
            if (s.flags and CarromFlag.FOUL != 0) parts += sayYou(who, R.string.carrom_ann_foul, R.string.carrom_ann_foul_you)
            else if (s.pottedThisShot.isEmpty() && s.flags and CarromFlag.QUEEN_BACK == 0) parts += sayYou(who, R.string.carrom_ann_miss, R.string.carrom_ann_miss_you)
            if (own && s.flags and CarromFlag.FOUL == 0) parts += if (LocalContext.current.isYouName(who)) pluralStringResource(R.plurals.carrom_ann_own_you, n.coerceAtLeast(1), n) else pluralStringResource(R.plurals.carrom_ann_own, n.coerceAtLeast(1), who, n)
            if (s.flags and CarromFlag.OPPONENT != 0) parts += stringResource(R.string.carrom_ann_opponent)
            if (s.flags and CarromFlag.COVERED != 0) parts += stringResource(R.string.carrom_ann_covered)
            else if (s.queenPending >= 0 && s.flags and CarromFlag.QUEEN != 0) parts += stringResource(R.string.carrom_ann_queen)
            if (s.flags and CarromFlag.QUEEN_BACK != 0) parts += stringResource(R.string.carrom_ann_queen_back)
            if (s.flags and CarromFlag.LAST_COIN_BACK != 0) parts += stringResource(R.string.carrom_ann_last_back)
            if (s.flags and CarromFlag.LIMIT != 0) parts += stringResource(R.string.carrom_ann_limit)
        }
        val turn = when {
            s.boardOver -> ""
            hostLeft -> stringResource(R.string.ccl_host_left)
            s.queenPending == shooter.team && isMyTurn -> stringResource(R.string.carrom_turn_cover)
            isMyTurn && !isNetworked && s.players.count { !it.isBot } > 1 -> stringResource(R.string.carrom_turn_named, current)
            isMyTurn -> stringResource(R.string.carrom_turn_you)
            botThinking -> stringResource(R.string.carrom_thinking, current)
            else -> stringResource(R.string.carrom_turn_named, current)
        }
        (parts + turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let {
        when (it) {
            CarromReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.cc_why_wait else R.string.cc_why_cpu)
            CarromReject.BLOCKED -> stringResource(R.string.carrom_why_blocked)
            CarromReject.BAD_SHOT -> stringResource(R.string.carrom_why_bad)
            CarromReject.BOARD_OVER -> stringResource(R.string.cc_why_over)
            CarromReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    val whiteWord = stringResource(R.string.carrom_white)
    val blackWord = stringResource(R.string.carrom_black)
    val queenWord = stringResource(R.string.carrom_queen)
    val boardDescription = stringResource(
        R.string.carrom_board_desc,
        whites, blacks, queenText,
        s.coins.sortedBy { it.id }.joinToString(", ") {
            val nm = when (CarromBoard.colourOf(it.id)) { 0 -> whiteWord; 1 -> blackWord; else -> queenWord }
            "$nm ${carromGrid(it.x, it.y, viewSide)}"
        }
    )

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (activeCtx?.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT) ({ game.undo() }) else null,
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("carrom", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("carrom", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        s.players.indices.forEach { i ->
                            val p = s.players[i]
                            val isTurn = i == s.currentPlayerIndex && !s.boardOver
                            val chipName = seatName(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
                            val left = s.onBoard(p.team)
                            val colourWord = if (p.team == 0) whiteWord else blackWord
                            val chipDesc = stringResource(R.string.carrom_chip_desc, chipName, colourWord, left) +
                                if (p.isBot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
                            Row(
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) TeamStrikerColors[p.team].copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) TeamStrikerColors[p.team] else theme.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = chipDesc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(Modifier.size(20.dp)) { drawCarromCoin(Offset(size.width / 2, size.height / 2), size.minDimension * 0.46f, p.team, colorblind) }
                                Spacer(Modifier.width(5.dp))
                                Text(chipName, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(5.dp))
                                Text("$left", color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                if (s.targetPoints > 0) {
                    Text(
                        stringResource(R.string.carrom_points_line, s.teamPoints[0], s.teamPoints[1], s.targetPoints),
                        color = theme.text.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(queenText, color = theme.text.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val board: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.clipToBounds(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    CarromBoardCanvas(
                        s = s, playback = playback, playFrame = playFrame, viewSide = viewSide,
                        canAim = isMyTurn && settled && !s.boardOver, stand = stand, aim = aim, power = power,
                        pullEnd = pullEnd, hint = hintShot, popIds = popIds, popScale = popPulse.value, flash = flash.map { it.value },
                        theme = theme, reduced = reduced, colorblind = colorblind, description = boardDescription,
                        modifier = Modifier.size(side),
                        onStand = { stand = it },
                        onAim = { a, p, end -> aim = a; power = p; pullEnd = end },
                        onRelease = { shoot ->
                            pullEnd = null
                            if (shoot) flick()
                        }
                    )
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val footer: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                if (isMyTurn && settled && !s.boardOver) {
                    val posLabel = stringResource(R.string.carrom_position)
                    val angLabel = stringResource(R.string.carrom_angle)
                    val powLabel = stringResource(R.string.carrom_power)
                    LabeledSlider(posLabel, stand.toFloat(), 0f..1000f, "${stand / 10}%", theme.text) { stand = it.roundToInt() }
                    LabeledSlider(angLabel, aim / 100f, -80f..80f, stringResource(R.string.carrom_degrees, (aim / 100f).roundToInt()), theme.text) { aim = (it * 100).roundToInt() }
                    LabeledSlider(powLabel, power.toFloat(), 1f..1000f, "${power / 10}%", theme.text) { power = it.roundToInt().coerceIn(1, 1000) }
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(10.dp))
                    val winners = s.players.filter { it.team == s.winnerTeam }
                    val title = when {
                        s.winnerTeam < 0 -> stringResource(R.string.cc_draw)
                        winners.size == 1 -> youAware(winners[0].displayName, R.string.carrom_wins_one, R.string.yg_win)
                        else -> stringResource(R.string.carrom_wins_many, winners.joinToString(" & ") { androidContext.seatLabel(it.displayName) })
                    }
                    val localTeam = s.players.getOrNull(localSeat)?.team
                    ClFinishedPanel(
                        title = title,
                        subtitle = if (isNetworked && s.winnerTeam >= 0) stringResource(if (localTeam == s.winnerTeam) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                        lines = buildList {
                            if (s.winnerTeam >= 0) add(stringResource(R.string.carrom_final_points, s.winPoints))
                            add(stringResource(R.string.carrom_final_line, whiteWord, 9 - whites))
                            add(stringResource(R.string.carrom_final_line, blackWord, 9 - blacks))
                            if (s.targetPoints > 0) add(stringResource(R.string.carrom_points_line, s.teamPoints[0], s.teamPoints[1], s.targetPoints))
                            if (finishedBoards > 1) add(stringResource(R.string.ccl_session, s.players.joinToString(" · ") { "${it.displayName}: ${game.sessionWins.value[it.playerId] ?: 0}" }))
                        },
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() },
                        playAgainLabel = stringResource(if (game.matchDecided() || s.targetPoints == 0) R.string.ccl_play_again else R.string.carrom_next_board)
                    )
                }
            }

            // Flick, Undo and Hint stay on screen under the scrolling controls: in a short window or at a large font they were pushed below the fold.
            val actions: @Composable () -> Unit = {
                if (isMyTurn && settled && !s.boardOver) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = ::flick, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth(0.7f)) {
                        Text(stringResource(R.string.carrom_flick), fontWeight = FontWeight.Bold)
                    }
                }
                if (!isNetworked) {
                    Spacer(Modifier.height(4.dp))
                    ClUndoHintRow(
                        canUndo = game.canUndo, canHint = isMyTurn && settled && !s.boardOver,
                        onUndo = { game.undo() }, onHint = { scope.launch { game.requestHint() } }, contentColor = theme.text
                    )
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.45f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header(); footer()
                        }
                        actions()
                    }
                    board(Modifier.weight(0.55f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    board(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                    actions()
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, textColor: Color, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = shown }
        )
        Text(shown, color = textColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
    }
}

// ------------------------------------------------------------------------------------------
// Board
// ------------------------------------------------------------------------------------------

@Composable
private fun CarromBoardCanvas(
    s: CarromState, playback: CarromSimResult?, playFrame: Float, viewSide: Int, canAim: Boolean,
    stand: Int, aim: Int, power: Int, pullEnd: Offset?, hint: CarromShot?, popIds: Set<Int>, popScale: Float, flash: List<Float>,
    theme: CarromTheme, reduced: Boolean, colorblind: Boolean, description: String, modifier: Modifier,
    onStand: (Int) -> Unit, onAim: (Int, Int, Offset?) -> Unit, onRelease: (Boolean) -> Unit
) {
    val pulse = if (reduced || !canAim) 0.5f else rememberInfiniteTransition(label = "carrom-pulse").animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "carrom-pulse-a"
    ).value
    val shooter = s.players[s.currentPlayerIndex]
    val unit = remember { FloatArray(1) { 1f } }
    val sideNow = shooter.side
    val standState by androidx.compose.runtime.rememberUpdatedState(stand)
    val onStandState by androidx.compose.runtime.rememberUpdatedState(onStand)
    val onAimState by androidx.compose.runtime.rememberUpdatedState(onAim)
    val onReleaseState by androidx.compose.runtime.rememberUpdatedState(onRelease)

    fun toBoard(o: Offset): Pair<Double, Double> {
        val u = unit[0]
        val dx = o.x / u - BOARD_PAD - CarromBoard.CENTER
        val dy = o.y / u - BOARD_PAD - CarromBoard.CENTER
        val (bx, by) = CarromBoard.rotate(viewSide, dx, dy)
        return (bx + CarromBoard.CENTER) to (by + CarromBoard.CENTER)
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(canAim, s.shotSerial, s.currentPlayerIndex) {
                if (!canAim) return@pointerInput
                var mode = 0 // 0 none, 1 slide, 2 aim
                var armed = false
                detectDragGestures(
                    onDragStart = { o ->
                        val (bx, by) = toBoard(o)
                        val (sx, sy) = CarromBoard.strikerStart(sideNow, standState)
                        val (fx, fy) = CarromBoard.shotDirection(sideNow, 0)
                        val along = (bx - sx) * fx + (by - sy) * fy
                        mode = when {
                            hypot(bx - sx, by - sy) <= CarromBoard.STRIKER_R * 2.6 -> 2
                            abs(along) < 46 -> 1
                            else -> 0
                        }
                        armed = false
                        if (mode == 1) slideTo(bx, by, sideNow, onStandState)
                    },
                    onDrag = { change, _ ->
                        val (bx, by) = toBoard(change.position)
                        if (mode == 1) slideTo(bx, by, sideNow, onStandState)
                        else if (mode == 2) {
                            val (sx, sy) = CarromBoard.strikerStart(sideNow, standState)
                            val px = sx - bx
                            val py = sy - by
                            val len = hypot(px, py)
                            if (len > 1.0) {
                                val (fx, fy) = CarromBoard.shotDirection(sideNow, 0)
                                val (rx, ry) = CarromBoard.rotate(sideNow, 1.0, 0.0)
                                val ang = Math.toDegrees(atan2((px * rx + py * ry) / len, (px * fx + py * fy) / len)).coerceIn(-80.0, 80.0)
                                val pw = (((len - MIN_PULL) / (MAX_PULL - MIN_PULL)).coerceIn(0.0, 1.0) * 999 + 1).toInt()
                                armed = len >= MIN_PULL
                                onAimState((ang * 100).roundToInt(), pw, Offset((bx).toFloat(), (by).toFloat()))
                            }
                        }
                    },
                    onDragEnd = {
                        val aimed = mode == 2 && armed
                        mode = 0
                        onReleaseState(aimed)
                    },
                    onDragCancel = { mode = 0; onReleaseState(false) }
                )
            }
    ) {
        val u = size.width / BOARD_TOTAL
        unit[0] = u
        fun sp(bx: Double, by: Double): Offset {
            val (dx, dy) = CarromBoard.rotate((4 - viewSide) % 4, bx - CarromBoard.CENTER, by - CarromBoard.CENTER)
            return Offset(((dx + CarromBoard.CENTER) * u + BOARD_PAD * u).toFloat(), ((dy + CarromBoard.CENTER) * u + BOARD_PAD * u).toFloat())
        }

        // frame and playing surface
        drawRoundRect(Color(0x44000000), Offset(u * 4, u * 8), size, CornerRadius(u * 34))
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF8A5A33), Color(0xFF4E301A)), Offset.Zero, Offset(size.width, size.height)), Offset.Zero, size, CornerRadius(u * 34))
        drawRoundRect(Color(0x55FFFFFF), Offset(u * 3, u * 3), Size(size.width - u * 6, size.height - u * 6), CornerRadius(u * 32), style = Stroke(u * 2))
        val surfaceTl = Offset(BOARD_PAD * u, BOARD_PAD * u)
        val surfaceSize = Size(740f * u, 740f * u)
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFFF1D9A6), Color(0xFFDDB878)), surfaceTl, Offset(surfaceTl.x + surfaceSize.width, surfaceTl.y + surfaceSize.height)), surfaceTl, surfaceSize, CornerRadius(u * 8))
        drawRoundRect(Color(0xFF3E2815), surfaceTl, surfaceSize, CornerRadius(u * 8), style = Stroke(u * 5))

        val lineColor = Color(0xFF5A3A1E)
        // corner arrows
        for (c in 0 until 4) {
            val cxs = if (c % 2 == 0) 0.0 else CarromBoard.SIZE
            val cys = if (c < 2) 0.0 else CarromBoard.SIZE
            val ix = if (c % 2 == 0) 1.0 else -1.0
            val iy = if (c < 2) 1.0 else -1.0
            drawLine(lineColor.copy(alpha = 0.7f), sp(cxs + ix * 55, cys + iy * 55), sp(cxs + ix * 150, cys + iy * 150), strokeWidth = u * 4, cap = StrokeCap.Round)
            drawCircle(lineColor.copy(alpha = 0.7f), u * 11, sp(cxs + ix * 165, cys + iy * 165), style = Stroke(u * 3))
        }
        // baselines with their end circles
        for (side in 0 until 4) {
            val a = CarromBoard.strikerStart(side, 0)
            val b = CarromBoard.strikerStart(side, 1000)
            val (fx, fy) = CarromBoard.shotDirection(side, 0)
            val off = 21.0
            for (sgn in doubleArrayOf(-1.0, 1.0)) {
                drawLine(lineColor, sp(a.first + fx * off * sgn, a.second + fy * off * sgn), sp(b.first + fx * off * sgn, b.second + fy * off * sgn), strokeWidth = u * 2.6f)
            }
            for (e in listOf(a, b)) {
                drawCircle(Color(0xFFB33A3A), u * 21, sp(e.first, e.second))
                drawCircle(lineColor, u * 21, sp(e.first, e.second), style = Stroke(u * 2.6f))
            }
        }
        // centre rings
        val mid = sp(CarromBoard.CENTER, CarromBoard.CENTER)
        drawCircle(lineColor, u * 84, mid, style = Stroke(u * 3))
        drawCircle(lineColor, u * 44, mid, style = Stroke(u * 3))
        drawCircle(Color(0xFFB33A3A).copy(alpha = 0.85f), u * 24, mid)

        // pockets
        for (p in 0 until 4) {
            val c = sp(CarromBoard.pocketX[p], CarromBoard.pocketY[p])
            drawCircle(Color(0xFF1A0F07), u * 33, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF000000), Color(0xFF2A1A0E)), c, u * 30), u * 30, c)
            drawCircle(Color(0xFF8A6A3A), u * 33, c, style = Stroke(u * 3.4f))
            val f = flash.getOrElse(p) { 0f }
            if (f > 0f) drawCircle(Color(0xFFFFE08A).copy(alpha = 0.7f * f), u * (33 + 22 * (1 - f)), c, style = Stroke(u * 5 * f))
        }

        // coins
        val coinR = (CarromBoard.COIN_R * u).toFloat()
        val strikerR = (CarromBoard.STRIKER_R * u).toFloat()
        if (playback != null) {
            val fl = playFrame.toInt().coerceIn(0, playback.frames.size - 1)
            val nx = (fl + 1).coerceAtMost(playback.frames.size - 1)
            val t = playFrame - fl
            val a = playback.frames[fl]
            val b = playback.frames[nx]
            for (i in playback.discIds.indices) {
                val id = playback.discIds[i]
                val alive = b[i * 3 + 2] > 0.5f
                val wasAlive = a[i * 3 + 2] > 0.5f
                if (!alive && !wasAlive) continue
                val x = a[i * 3] + (b[i * 3] - a[i * 3]) * t
                val y = a[i * 3 + 1] + (b[i * 3 + 1] - a[i * 3 + 1]) * t
                val o = sp(x.toDouble(), y.toDouble())
                val shrink = if (alive) 1f else (1f - t).coerceIn(0f, 1f)
                if (id == CarromBoard.STRIKER_ID) drawStriker(o, strikerR * shrink, shooter.team, 1f)
                else drawCarromCoin(o, coinR * shrink, CarromBoard.colourOf(id), colorblind)
            }
        } else {
            for (c in s.coins) {
                val pop = if (c.id in popIds) popScale else 1f
                drawCarromCoin(sp(c.x, c.y), coinR * pop, CarromBoard.colourOf(c.id), colorblind)
            }
            if (!s.boardOver) {
                val (sx, sy) = CarromBoard.strikerStart(sideNow, stand)
                val so = sp(sx, sy)
                if (canAim) {
                    // aim guide
                    val (dx, dy) = CarromBoard.shotDirection(sideNow, aim)
                    val (ex, ey, hitId) = guideEnd(s.coins, sx, sy, dx, dy)
                    val eo = sp(ex, ey)
                    drawLine(Color.White.copy(alpha = 0.9f), so, eo, strokeWidth = u * 3.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 14, u * 10)), cap = StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = 0.55f), strikerR, eo, style = Stroke(u * 3))
                    if (hitId >= 0) {
                        val hc = s.coins.first { it.id == hitId }
                        val cx = hc.x - ex
                        val cy = hc.y - ey
                        val cl = hypot(cx, cy).coerceAtLeast(1e-6)
                        drawLine(Color(0xFFFFE08A), sp(hc.x, hc.y), sp(hc.x + cx / cl * 70, hc.y + cy / cl * 70), strokeWidth = u * 3.4f, cap = StrokeCap.Round)
                    }
                    // power ring
                    drawArc(Color(0xFFFFD21F), -90f, 360f * power / 1000f, false, Offset(so.x - strikerR * 1.5f, so.y - strikerR * 1.5f), Size(strikerR * 3f, strikerR * 3f), style = Stroke(u * 5, cap = StrokeCap.Round))
                    if (pullEnd != null) {
                        val pe = sp(pullEnd.x.toDouble(), pullEnd.y.toDouble())
                        drawLine(Color(0xFFFFD21F).copy(alpha = 0.8f), so, pe, strokeWidth = u * 4, cap = StrokeCap.Round)
                        drawCircle(Color(0xFFFFD21F).copy(alpha = 0.6f), u * 16, pe)
                    } else {
                        drawCircle(Color.White.copy(alpha = 0.25f + 0.4f * pulse), strikerR * (1.35f + 0.2f * pulse), so, style = Stroke(u * 3))
                    }
                }
                if (canAim && hint != null) {
                    val (hx, hy) = CarromBoard.strikerStart(sideNow, hint.t)
                    drawCircle(Color(0xFFFFD21F), strikerR * 1.25f, sp(hx, hy), style = Stroke(u * 4))
                }
                drawStriker(so, strikerR, shooter.team, if (canAim) 1f else 0.6f)
            }
        }
    }
}

private fun slideTo(bx: Double, by: Double, side: Int, onStand: (Int) -> Unit) {
    val a = CarromBoard.strikerStart(side, 0)
    val b = CarromBoard.strikerStart(side, 1000)
    val dx = b.first - a.first
    val dy = b.second - a.second
    val t = (((bx - a.first) * dx + (by - a.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    onStand((t * 1000).roundToInt())
}

// ------------------------------------------------------------------------------------------
// Pieces
// ------------------------------------------------------------------------------------------

/** kind 0 ivory, 1 ebony, 2 the queen. Each has its own etching so colour is never the only cue. */
internal fun DrawScope.drawCarromCoin(c: Offset, r: Float, kind: Int, colorblind: Boolean, alpha: Float = 1f) {
    if (r < 0.5f) return
    val base = when (kind) { 0 -> Color(0xFFF5ECD6); 1 -> Color(0xFF2B2623); else -> Color(0xFFC8283E) }
    val rim = when (kind) { 0 -> Color(0xFF8A7A5C); 1 -> Color(0xFF8C8279); else -> Color(0xFF6E1020) }
    drawCircle(Color(0x55000000).copy(alpha = 0.33f * alpha), r, Offset(c.x + r * 0.1f, c.y + r * 0.16f))
    drawCircle(
        Brush.radialGradient(listOf(clShade(base, if (kind == 1) 0.22f else 0.4f), base, clShade(base, -0.3f)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f),
        r, c, alpha = alpha
    )
    drawCircle(rim.copy(alpha = alpha), r, c, style = Stroke(r * 0.1f))
    drawCircle(rim.copy(alpha = 0.7f * alpha), r * 0.62f, c, style = Stroke(r * (if (colorblind) 0.12f else 0.07f)))
    when (kind) {
        1 -> drawCircle(rim.copy(alpha = alpha), r * 0.2f, c)
        2 -> drawSeatGlyph(4, c, r * 0.4f)
    }
    drawCircle(Color.White.copy(alpha = 0.5f * alpha), r * 0.16f, Offset(c.x - r * 0.4f, c.y - r * 0.45f))
}

private fun DrawScope.drawStriker(c: Offset, r: Float, team: Int, alpha: Float) {
    if (r < 0.5f) return
    val team0 = TeamStrikerColors[team % 2]
    drawCircle(Color(0x55000000).copy(alpha = 0.3f * alpha), r, Offset(c.x + r * 0.1f, c.y + r * 0.18f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFF2F7FA), Color(0xFFC9D6DE), Color(0xFF8FA3B0)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f),
        r, c, alpha = alpha
    )
    drawCircle(team0.copy(alpha = alpha), r, c, style = Stroke(r * 0.16f))
    drawSeatGlyph(if (team % 2 == 0) 0 else 3, c, r * 0.36f)
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), r * 0.14f, Offset(c.x - r * 0.4f, c.y - r * 0.45f))
}
