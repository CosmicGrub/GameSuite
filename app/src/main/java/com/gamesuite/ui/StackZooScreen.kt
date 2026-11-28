package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.stackzoo.StackZooGame
import com.gamesuite.games.stackzoo.ZooBodyBuffer
import com.gamesuite.games.stackzoo.ZooEvent
import com.gamesuite.games.stackzoo.ZooGoal
import com.gamesuite.games.stackzoo.ZooHelper
import com.gamesuite.games.stackzoo.ZooLength
import com.gamesuite.games.stackzoo.ZooMode
import com.gamesuite.games.stackzoo.ZooOutcome
import com.gamesuite.games.stackzoo.ZooPhase
import com.gamesuite.games.stackzoo.ZooPhysics
import com.gamesuite.games.stackzoo.ZooShapes
import com.gamesuite.games.stackzoo.ZooState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Stack Zoo on one screen (docs/design/kids-games/stack-zoo.md). The whole table (log, tower, pond, flag, the animal being
 * placed) is ONE Canvas redrawn every frame from the engine's pose buffer, so nothing is allocated per frame and the
 * composition only changes at discrete events (a pick, a release, a turn result). Physics runs in whole fixed steps in the
 * game wrapper; the screen only feeds it frame time.
 *
 * Input: tap an animal on the shelf to pick it (tap it again to put it back), drag on the table to move it, Turn left /
 * Turn right to rotate it, let go to place it. One-switch placing (and TalkBack, which cannot drag) uses one big button: the
 * animal slides by itself, press to stop it, it turns through its flat angles, press to stop, press to let go. Targets are
 * at least 56dp. Reduced Motion: the camera counter-turns so the drawn wobble is half as big, no balloons or pulses.
 * Same screen only.
 */
@Composable
fun StackZooScreen(
    sessionManager: GameSessionManager,
    game: StackZooGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var showTutorial by remember { mutableStateOf(false) }
    var uiHold by remember { mutableStateOf(false) }
    val ownPause = remember { booleanArrayOf(false) }
    val touchExploration = remember { (androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        // The Hub's four modes share one route: a lone player is Solo Balance, a goal choice means Co-op Tower, otherwise Take Turns.
        game.mode = if (ctx.players.size == 1) ZooMode.SOLO_BALANCE else if (extras["goal"] != null) ZooMode.COOP_TOWER else ZooMode.TAKE_TURNS
        game.goal = ZooGoal.fromId(extras["goal"] ?: "medium")
        game.length = ZooLength.fromId(extras["length"] ?: "standard")
        game.assist = extras["assist"] ?: "none"
        game.relaxed = extras["relaxed"] == "true"
        game.oneSwitch = extras["oneSwitch"] == "true"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    LaunchedEffect(uiHold) {
        if (uiHold) {
            game.pause()
            ownPause[0] = true
        } else if (ownPause[0]) {
            ownPause[0] = false
            game.resume()
        }
    }

    val helpTitle = stringResource(R.string.stack_zoo_help_title)
    val helpText = stringResource(R.string.stack_zoo_help_text)
    val s by game.state
    val players = context?.players ?: return
    val buffer = remember { ZooBodyBuffer() }
    val tick = remember { mutableLongStateOf(0L) }
    val ghostX = remember { mutableDoubleStateOf(0.0) }
    val ghostAngle = remember { mutableDoubleStateOf(0.0) }
    // one-switch stage: 0 sliding in x, 1 turning, 2 ready to let go
    val stage = remember { mutableIntStateOf(0) }
    val seatIsBot = players.getOrNull(s.activeSeat)?.isBot == true
    val scheme = if (dark) ZooDark else ZooLight
    val switchMode = game.oneSwitch || touchExploration

    // frame loop: feed real frame time to the fixed-step physics and invalidate the table canvas
    LaunchedEffect(game) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && !uiHold) {
                    val dtMs = ((now - last) / 1_000_000.0).coerceAtMost(100.0)
                    game.frame(dtMs)
                }
                last = now
                tick.longValue = now
            }
        }
    }

    // the piece being placed starts in the middle with its flattest pose
    LaunchedEffect(s.chosen) {
        if (s.chosen >= 0 && !seatIsBot) {
            ghostX.doubleValue = 0.0
            ghostAngle.doubleValue = 0.0
            stage.intValue = 0
        }
    }

    // one-switch: the animal slides by itself (ping-pong 0.5 u/s) or turns through its flat angles until the press
    LaunchedEffect(s.phase, s.chosen, switchMode, stage.intValue, uiHold) {
        if (!switchMode || seatIsBot || s.phase != ZooPhase.POSITIONING || s.chosen < 0) return@LaunchedEffect
        var dir = if (ghostX.doubleValue >= 3.5) -1.0 else 1.0
        var idx = 0
        val flats = ZooShapes.animals[s.chosenAnimal.coerceIn(0, 19)].flatAngles.let { if (it.isEmpty()) doubleArrayOf(0.0) else it }
        var acc = 0L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L || uiHold) 0.0 else (now - last) / 1e9
                last = now
                when (stage.intValue) {
                    0 -> {
                        var x = ghostX.doubleValue + dir * 0.5 * dt
                        if (x > 3.5) { x = 3.5; dir = -1.0 } else if (x < -3.5) { x = -3.5; dir = 1.0 }
                        ghostX.doubleValue = x
                    }
                    1 -> {
                        acc += (dt * 1000.0).toLong()
                        if (acc >= 1200L) { acc = 0L; idx = (idx + 1) % flats.size; ghostAngle.doubleValue = flats[idx] }
                    }
                    else -> {}
                }
            }
        }
    }

    // helper turns: think, plan on a worker thread from a copy of the engine, drag visibly, then let go
    LaunchedEffect(s.phase, s.activeSeat, s.turnNumber, uiHold) {
        if (uiHold || s.phase != ZooPhase.CHOOSING || !seatIsBot) return@LaunchedEffect
        delay(if (reduced) ZooHelper.MIN_THINK_MS / 2 else ZooHelper.MIN_THINK_MS)
        val save = game.helperSave() ?: return@LaunchedEffect
        val seats = game.helperSeats()
        val diff = game.difficulty
        val move = withContext(Dispatchers.Default) { ZooHelper.plan(save, seats, diff) } ?: return@LaunchedEffect
        if (!game.choose(move.pieceId)) return@LaunchedEffect
        val tx = move.xQ / ZooPhysics.QUANT
        val ta = move.angleQ / ZooPhysics.QUANT
        ghostX.doubleValue = 0.0
        ghostAngle.doubleValue = 0.0
        val total = if (reduced) 200L else ZooHelper.DRAG_MS
        var t = 0L
        var last = 0L
        while (t < total) {
            withFrameNanos { now ->
                if (last != 0L && !uiHold) t += ((now - last) / 1_000_000L)
                last = now
                val f = (t.toDouble() / total).coerceIn(0.0, 1.0)
                val eased = f * f * (3.0 - 2.0 * f)
                ghostX.doubleValue = tx * eased
                ghostAngle.doubleValue = ta * eased
            }
        }
        game.release(move.xQ, move.angleQ)
    }

    LaunchedEffect(s.eventSerial) {
        when (s.event) {
            ZooEvent.CHOSE -> haptics(HapticSignal.LIGHT_TICK)
            ZooEvent.PLACED -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            ZooEvent.STEADY -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
            ZooEvent.FELL -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            ZooEvent.HOLDING -> haptics(HapticSignal.ESCALATING)
            ZooEvent.WON, ZooEvent.FINISHED -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
            else -> {}
        }
    }

    fun seatName(i: Int): String = players.getOrNull(i)?.displayName?.let { androidContext.seatLabel(it) } ?: ""
    val status = statusText(androidContext, s, ::seatName, seatIsBot)
    val announce = announceText(androidContext, s, ::seatName, status)
    val inProgress = s.phase != ZooPhase.OVER

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = inProgress,
        onLeave = game::leaveSession,
        onAbort = { if (s.turnNumber > 0 || s.standing > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = true,
        buttonFill = Color(scheme.background), buttonContent = Color(scheme.text)
    ) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, showTutorial) { uiHold = !windowFocused || showTutorial }
        Column(Modifier.fillMaxSize().background(Color(scheme.background)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                val helpLabel = stringResource(R.string.stack_zoo_cd_help)
                TextButton(
                    onClick = { showTutorial = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = helpLabel }
                ) { Text("?", color = Color(scheme.accent), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
                Text(
                    status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announce },
                    color = Color(scheme.text), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                )
            }
            CountersRow(s, scheme, players.map { it.displayName }, androidContext)
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                TableCanvas(
                    s = s, game = game, buffer = buffer, tick = tick, scheme = scheme, reduced = reduced,
                    ghostX = ghostX, ghostAngle = ghostAngle, humanPlacing = s.phase == ZooPhase.POSITIONING && !seatIsBot && !switchMode,
                    onDragX = { ghostX.doubleValue = it },
                    onRelease = { game.release((ghostX.doubleValue * ZooPhysics.QUANT).toInt(), (ghostAngle.doubleValue * ZooPhysics.QUANT).toInt()) },
                    desc = worldDescription(androidContext, s, buffer.baseAngle)
                )
                if (s.paused && !uiHold) PausedCard(scheme) { game.resume() }
            }
            when (s.phase) {
                ZooPhase.READY -> ReadyPanel(scheme) { game.begin() }
                ZooPhase.OVER -> OverPanel(s, scheme, players.map { it.displayName }, androidContext, onAgain = { game.playAgain() }, onDone = { game.leaveSession() })
                else -> {
                    Shelf(s, scheme, seatIsBot, androidContext, onPick = { piece -> if (s.chosen == piece) game.unchoose() else game.choose(piece) })
                    if (s.phase == ZooPhase.POSITIONING && !seatIsBot) {
                        Controls(
                            scheme = scheme, switchMode = switchMode, stage = stage.intValue,
                            onLeft = { ghostAngle.doubleValue = ghostAngle.doubleValue - 0.2618 },
                            onRight = { ghostAngle.doubleValue = ghostAngle.doubleValue + 0.2618 },
                            onSwitch = {
                                if (stage.intValue < 2) stage.intValue = stage.intValue + 1
                                else game.release((ghostX.doubleValue * ZooPhysics.QUANT).toInt(), (ghostAngle.doubleValue * ZooPhysics.QUANT).toInt())
                            },
                            onBack = { game.unchoose() }
                        )
                    }
                }
            }
        }
        if (showTutorial) TutorialDialog(scheme) { showTutorial = false }
    }
}

// ---- colours ---------------------------------------------------------------------------------

private class ZooScheme(
    val background: Long, val card: Long, val text: Long, val muted: Long, val accent: Long, val onAccent: Long,
    val sky: Long, val pond: Long, val wood: Long, val woodDark: Long, val ghostRing: Long
)

private val ZooLight = ZooScheme(
    background = 0xFFFFF6E5, card = 0xFFFFEBC8, text = 0xFF3B2A1E, muted = 0xFF6B5444, accent = 0xFF2F6B3A, onAccent = 0xFFFFFFFF,
    sky = 0xFFDDF0F7, pond = 0xFF6FB6D8, wood = 0xFFB98B54, woodDark = 0xFF6E4A22, ghostRing = 0xFF2F6B3A
)
private val ZooDark = ZooScheme(
    background = 0xFF1F1812, card = 0xFF33281E, text = 0xFFFBEFD9, muted = 0xFFD2BFA8, accent = 0xFF8ADC9A, onAccent = 0xFF10281C,
    sky = 0xFF26323D, pond = 0xFF2F6F8F, wood = 0xFFA37845, woodDark = 0xFF5A3C1B, ghostRing = 0xFF8ADC9A
)

// ---- text ------------------------------------------------------------------------------------

private fun animalName(ctx: Context, animalIndex: Int): String = ctx.getString(animalNameRes(animalIndex))

private fun animalNameRes(i: Int): Int = when (i) {
    0 -> R.string.stack_zoo_animal_turtle
    1 -> R.string.stack_zoo_animal_hippo
    2 -> R.string.stack_zoo_animal_elephant
    3 -> R.string.stack_zoo_animal_giraffe
    4 -> R.string.stack_zoo_animal_crocodile
    5 -> R.string.stack_zoo_animal_snail
    6 -> R.string.stack_zoo_animal_penguin
    7 -> R.string.stack_zoo_animal_rabbit
    8 -> R.string.stack_zoo_animal_fox
    9 -> R.string.stack_zoo_animal_bear
    10 -> R.string.stack_zoo_animal_duck
    11 -> R.string.stack_zoo_animal_frog
    12 -> R.string.stack_zoo_animal_owl
    13 -> R.string.stack_zoo_animal_monkey
    14 -> R.string.stack_zoo_animal_dino
    15 -> R.string.stack_zoo_animal_whale
    16 -> R.string.stack_zoo_animal_camel
    17 -> R.string.stack_zoo_animal_cat
    18 -> R.string.stack_zoo_animal_snake
    else -> R.string.stack_zoo_animal_pig
}

private fun statusText(ctx: Context, s: ZooState, seatName: (Int) -> String, seatIsBot: Boolean): String = when (s.phase) {
    ZooPhase.READY -> ctx.getString(R.string.stack_zoo_ready)
    ZooPhase.CHOOSING -> if (seatIsBot) ctx.getString(R.string.stack_zoo_helper_thinking, seatName(s.activeSeat))
    else if (s.seats.size > 1) ctx.getString(R.string.stack_zoo_turn_of, seatName(s.activeSeat)) else ctx.getString(R.string.stack_zoo_pick_animal)
    ZooPhase.POSITIONING -> if (seatIsBot) ctx.getString(R.string.stack_zoo_helper_thinking, seatName(s.activeSeat)) else ctx.getString(R.string.stack_zoo_drag_it)
    ZooPhase.SETTLING -> ctx.getString(R.string.stack_zoo_hold_still)
    ZooPhase.TURN_RESULT -> when {
        s.mode == ZooMode.COOP_TOWER && s.towerHeight >= s.goalHeight && s.holding -> ctx.getString(R.string.stack_zoo_goal_hold)
        s.fellLastTurn == 1 -> if (s.mode == ZooMode.TAKE_TURNS) ctx.getString(R.string.stack_zoo_take_back, seatName(s.activeSeat)) else ctx.getString(R.string.stack_zoo_oops_one)
        s.fellLastTurn > 1 -> if (s.mode == ZooMode.TAKE_TURNS) ctx.getString(R.string.stack_zoo_take_back, seatName(s.activeSeat)) else ctx.getString(R.string.stack_zoo_oops_many, s.fellLastTurn)
        else -> ctx.getString(R.string.stack_zoo_nice)
    }
    ZooPhase.OVER -> when (s.outcome) {
        ZooOutcome.COOP_WON -> ctx.getString(R.string.stack_zoo_goal_reached)
        ZooOutcome.COOP_LOST -> ctx.getString(R.string.stack_zoo_try_again_together)
        ZooOutcome.SOLO_DONE -> ctx.getString(R.string.stack_zoo_stacked_n, s.standing)
        ZooOutcome.WINNER -> ctx.getString(R.string.stack_zoo_winner, seatName(Integer.numberOfTrailingZeros(s.winnerMask.coerceAtLeast(1))))
        ZooOutcome.TIE -> ctx.getString(R.string.stack_zoo_tie)
        ZooOutcome.NONE -> ""
    }
}

/** What TalkBack hears after a turn: who placed what, and whether it stood. */
private fun announceText(ctx: Context, s: ZooState, seatName: (Int) -> String, fallback: String): String {
    if (s.phase != ZooPhase.TURN_RESULT) return fallback
    val fell = when {
        s.fellLastTurn == 0 -> ctx.getString(R.string.stack_zoo_ann_settled)
        s.fellLastTurn == 1 -> ctx.getString(R.string.stack_zoo_ann_fell_one)
        else -> ctx.getString(R.string.stack_zoo_ann_fell_many, s.fellLastTurn)
    }
    return fallback + ". " + fell
}

private fun worldDescription(ctx: Context, s: ZooState, baseAngle: Double): String {
    val deg = Math.round(Math.toDegrees(baseAngle)).toInt()
    val log = ctx.getString(R.string.stack_zoo_cd_log, deg)
    val tower = ctx.getString(R.string.stack_zoo_cd_tower, s.standing)
    val flag = if (s.mode == ZooMode.COOP_TOWER) ". " + ctx.getString(R.string.stack_zoo_cd_flag, Math.round(s.goalHeight).toInt()) else ""
    return ctx.getString(R.string.stack_zoo_cd_world, log + flag, tower)
}

// ---- the table -------------------------------------------------------------------------------

@Composable
private fun TableCanvas(
    s: ZooState, game: StackZooGame, buffer: ZooBodyBuffer, tick: androidx.compose.runtime.State<Long>, scheme: ZooScheme, reduced: Boolean,
    ghostX: androidx.compose.runtime.MutableDoubleState, ghostAngle: androidx.compose.runtime.MutableDoubleState, humanPlacing: Boolean, onDragX: (Double) -> Unit, onRelease: () -> Unit, desc: String
) {
    val canvasW = remember { floatArrayOf(1f) }
    val canvasScale = remember { floatArrayOf(1f) }
    Canvas(
        Modifier.fillMaxSize()
            .semantics { contentDescription = desc }
            .pointerInput(humanPlacing, s.chosen) {
                if (!humanPlacing) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> onDragX(((o.x - canvasW[0] / 2f) / canvasScale[0]).toDouble().coerceIn(-4.0, 4.0)) },
                    onDrag = { change, _ -> onDragX(((change.position.x - canvasW[0] / 2f) / canvasScale[0]).toDouble().coerceIn(-4.0, 4.0)); change.consume() },
                    onDragEnd = { onRelease() },
                    onDragCancel = {}
                )
            }
    ) {
        tick.value // read in the draw phase only, so the canvas redraws every frame without recomposing
        game.engine.fillBodies(buffer)
        var top = 0.0
        for (i in 0 until buffer.count) if (!buffer.fallen[i]) top = maxOf(top, buffer.y[i] + 2.0)
        val viewH = maxOf(10.0, top + 4.0, s.towerHeight + 5.0, s.goalHeight + 3.0).toFloat() + 3.2f
        val viewW = 12f
        val scale = minOf(size.width / viewW, size.height / viewH)
        canvasW[0] = size.width
        canvasScale[0] = scale
        val cx = size.width / 2f
        val groundY = size.height - 3.2f * scale
        fun sx(x: Double) = cx + x.toFloat() * scale
        fun sy(y: Double) = groundY - y.toFloat() * scale
        drawRect(Color(scheme.sky), Offset.Zero, Size(size.width, size.height))
        // pond
        drawRect(Color(scheme.pond), Offset(0f, groundY + 2.2f * scale), Size(size.width, size.height - groundY - 2.2f * scale))
        // the camera counter-turns under Reduced Motion so the wobble looks half as big
        val camera = if (reduced) -(buffer.baseAngle * 0.5) else 0.0
        rotate(degrees = (-Math.toDegrees(camera)).toFloat(), pivot = Offset(sx(0.0), sy(-0.2))) {
            // goal flag (Co-op)
            if (s.mode == ZooMode.COOP_TOWER && s.goalHeight > 0.0) {
                val fy = sy(s.goalHeight)
                drawLine(Color(scheme.text).copy(alpha = 0.5f), Offset(sx(-4.2), fy), Offset(sx(4.2), fy), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
                drawLine(Color(scheme.text), Offset(sx(3.6), fy), Offset(sx(3.6), fy - 1.4f * scale), 4f)
                val flag = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sx(3.6), fy - 1.4f * scale); lineTo(sx(4.5), fy - 1.05f * scale); lineTo(sx(3.6), fy - 0.7f * scale); close()
                }
                drawPath(flag, Color(if (s.holding) 0xFF1B7F4F else 0xFFD64545))
                drawPath(flag, Color(scheme.text), style = Stroke(3f))
            }
            val alarm = Math.abs(buffer.baseAngle) > 0.21
            drawZooLog(Offset(sx(0.0), sy(-0.2)), buffer.baseAngle, scale, Color(scheme.wood), Color(scheme.woodDark), alarm)
            for (i in 0 until buffer.count) {
                val fallen = buffer.fallen[i]
                val a = if (fallen) 0.45f else 1f
                drawZooAnimal(buffer.animalIndex[i], Offset(sx(buffer.x[i]), sy(buffer.y[i])), buffer.angle[i], scale, a)
            }
            // the animal being placed, with a dotted drop line and a ring (shape, not colour only) at the assisted spot
            if (s.phase == ZooPhase.POSITIONING && s.chosenAnimal >= 0) {
                val gy = maxOf(top, 3.0) + 1.5
                val gxv = ghostX.doubleValue
                val gav = ghostAngle.doubleValue
                val placed = game.assisted((gxv * ZooPhysics.QUANT).toInt(), (gav * ZooPhysics.QUANT).toInt())
                val ax = placed.xQ / ZooPhysics.QUANT
                drawLine(Color(scheme.muted).copy(alpha = 0.6f), Offset(sx(ax), sy(gy)), Offset(sx(ax), sy(0.0)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                if (s.activeAssisted) drawCircle(Color(scheme.ghostRing), 0.35f * scale, Offset(sx(ax), sy(0.0)), style = Stroke(5f))
                drawZooAnimal(s.chosenAnimal, Offset(sx(gxv), sy(gy)), gav, scale, 0.9f)
            }
        }
    }
}

@Composable
private fun PausedCard(scheme: ZooScheme, onResume: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(Modifier.background(Color(scheme.card), RoundedCornerShape(16.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.stack_zoo_paused), color = Color(scheme.text), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onResume, modifier = Modifier.heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))) {
                Text(stringResource(R.string.stack_zoo_resume))
            }
        }
    }
}

// ---- counters, shelf, controls, panels -------------------------------------------------------

@Composable
private fun CountersRow(s: ZooState, scheme: ZooScheme, names: List<String>, ctx: Context) {
    val modeLabel = stringResource(
        when (s.mode) { ZooMode.COOP_TOWER -> R.string.stack_zoo_mode_coop; ZooMode.TAKE_TURNS -> R.string.stack_zoo_mode_turns; ZooMode.SOLO_BALANCE -> R.string.stack_zoo_mode_solo }
    )
    val line = when (s.mode) {
        ZooMode.COOP_TOWER -> ctx.getString(R.string.stack_zoo_animals_left, s.bagLeft) + "   " +
            ctx.getString(R.string.stack_zoo_tower_progress, Math.floor(s.towerHeight).toInt(), Math.round(s.goalHeight).toInt())
        ZooMode.SOLO_BALANCE -> ctx.getString(R.string.stack_zoo_stacked_n, s.standing) + "   " +
            (if (s.livesLeft >= 0) ctx.getString(R.string.stack_zoo_tries_left, s.livesLeft) else ctx.getString(R.string.stack_zoo_animals_left, s.bagLeft))
        ZooMode.TAKE_TURNS -> s.seats.mapIndexed { i, seat -> ctx.seatLabel(names.getOrElse(i) { seat.name }) + " " + seat.handCount }.joinToString("   ")
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(modeLabel, color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
        Text(line, color = Color(scheme.text), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

@Composable
private fun Shelf(s: ZooState, scheme: ZooScheme, seatIsBot: Boolean, ctx: Context, onPick: (Int) -> Unit) {
    val shelfDesc = stringResource(R.string.stack_zoo_cd_shelf)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = shelfDesc },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        val offer = if (s.phase == ZooPhase.CHOOSING || s.phase == ZooPhase.POSITIONING) s.offer else emptyList()
        for ((i, piece) in offer.withIndex()) {
            val animal = s.offerAnimals[i]
            val picked = s.chosen == piece
            val name = animalName(ctx, animal)
            val desc = if (picked) ctx.getString(R.string.stack_zoo_cd_picked, name) else ctx.getString(R.string.stack_zoo_cd_animal_pick, name)
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .size(width = 92.dp, height = 80.dp)
                    .clip(shape)
                    .background(Color(scheme.card), shape)
                    .border(BorderStroke(if (picked) 4.dp else 1.dp, Color(if (picked) scheme.accent else scheme.muted).copy(alpha = if (picked) 1f else 0.4f)), shape)
                    .then(if (!seatIsBot && (s.phase == ZooPhase.CHOOSING || picked)) Modifier.clickable(role = Role.Button) { onPick(piece) } else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = desc },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize().padding(8.dp)) { drawZooAnimalFitted(animal, Offset.Zero, size.width, size.height, if (picked) 0.4f else 1f) }
            }
        }
    }
}

@Composable
private fun Controls(scheme: ZooScheme, switchMode: Boolean, stage: Int, onLeft: () -> Unit, onRight: () -> Unit, onSwitch: () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (switchMode) {
            Button(
                onClick = onSwitch, modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
            ) {
                Text(
                    stringResource(when (stage) { 0 -> R.string.stack_zoo_switch_stop_x; 1 -> R.string.stack_zoo_switch_stop_turn; else -> R.string.stack_zoo_switch_drop }),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                )
            }
        } else {
            OutlinedButton(onClick = onLeft, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.stack_zoo_rotate_left), color = Color(scheme.text)) }
            OutlinedButton(onClick = onRight, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.stack_zoo_rotate_right), color = Color(scheme.text)) }
            Text(stringResource(R.string.stack_zoo_let_go), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.stack_zoo_put_back), color = Color(scheme.text)) }
    }
}

@Composable
private fun ReadyPanel(scheme: ZooScheme, onBegin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onBegin, modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 220.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
        ) { Text(stringResource(R.string.stack_zoo_ready), style = MaterialTheme.typography.headlineSmall) }
        Text(stringResource(R.string.stack_zoo_tap_to_start), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OverPanel(s: ZooState, scheme: ZooScheme, names: List<String>, ctx: Context, onAgain: () -> Unit, onDone: () -> Unit) {
    val title = when (s.outcome) {
        ZooOutcome.COOP_WON -> stringResource(R.string.stack_zoo_we_did_it)
        ZooOutcome.COOP_LOST -> stringResource(R.string.stack_zoo_try_again_together)
        ZooOutcome.WINNER -> stringResource(R.string.stack_zoo_winner, ctx.seatLabel(names.getOrElse(Integer.numberOfTrailingZeros(s.winnerMask.coerceAtLeast(1))) { "" }))
        ZooOutcome.TIE -> stringResource(R.string.stack_zoo_tie)
        else -> stringResource(R.string.stack_zoo_stacked_n, s.standing)
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color(scheme.card), RoundedCornerShape(20.dp)).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color(scheme.text), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (s.outcome == ZooOutcome.COOP_WON) Text(stringResource(R.string.stack_zoo_stars_n, s.stars), color = Color(scheme.text), style = MaterialTheme.typography.titleMedium)
        for ((i, seat) in s.seats.withIndex()) {
            Text(
                stringResource(R.string.stack_zoo_score_row, ctx.seatLabel(names.getOrElse(i) { seat.name }), seat.stacked, seat.handCount),
                color = Color(scheme.text), style = MaterialTheme.typography.bodyMedium
            )
        }
        val steady = s.seats.indices.maxByOrNull { s.seats[it].clean }
        if (steady != null && s.seats[steady].clean > 0) {
            Text(stringResource(R.string.stack_zoo_badge_steady) + ": " + ctx.seatLabel(names.getOrElse(steady) { "" }), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
        }
        if (s.mode == ZooMode.COOP_TOWER) Text(stringResource(R.string.stack_zoo_badge_tall), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
        if (s.mode == ZooMode.SOLO_BALANCE) {
            Text(stringResource(R.string.stack_zoo_best, s.standing), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
            if (s.standing >= 8) Text(stringResource(R.string.stack_zoo_new_best), color = Color(scheme.accent), fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.stack_zoo_teaches), color = Color(scheme.muted), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAgain, modifier = Modifier.heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))) {
                Text(stringResource(R.string.stack_zoo_play_again))
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.stack_zoo_done), color = Color(scheme.text)) }
        }
    }
}

private val tutorialRes = listOf(
    R.string.stack_zoo_tut_1, R.string.stack_zoo_tut_2, R.string.stack_zoo_tut_3, R.string.stack_zoo_tut_4, R.string.stack_zoo_tut_5, R.string.stack_zoo_tut_6
)

@Composable
private fun TutorialDialog(scheme: ZooScheme, onClose: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val last = step == tutorialRes.size - 1
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(scheme.card),
        title = { Text(stringResource(R.string.stack_zoo_help_title), color = Color(scheme.text)) },
        text = { Text(stringResource(tutorialRes[step]), color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = { if (last) onClose() else step++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (last) R.string.stack_zoo_tut_start else R.string.stack_zoo_tut_next), color = Color(scheme.accent))
            }
        },
        dismissButton = {
            if (!last) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.stack_zoo_tut_skip), color = Color(scheme.muted)) }
        }
    )
}
