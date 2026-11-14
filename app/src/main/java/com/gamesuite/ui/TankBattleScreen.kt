package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.gamesuite.games.tankbattle.TankEvent
import com.gamesuite.games.tankbattle.TankGame
import com.gamesuite.games.tankbattle.TankImpact
import com.gamesuite.games.tankbattle.TankReject
import com.gamesuite.games.tankbattle.TankSim
import com.gamesuite.games.tankbattle.TankState
import com.gamesuite.games.tankbattle.TankWeapons
import com.gamesuite.games.tankbattle.TankWorld
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

private class TankUi(val bg: Color, val text: Color, val card: Color, val accent: Color)

@Composable
private fun tankUi(dark: Boolean) = if (!dark) TankUi(
    bg = Color(0xFFF1EEE3), text = Color(0xFF26301A), card = Color(0xFFE2DFCB), accent = Color(0xFF5F6B1F)
) else TankUi(
    bg = Color(0xFF151A12), text = Color(0xFFECEFD8), card = Color(0xFF232A1B), accent = Color(0xFFC8D86A)
)

private class TkFlight(val points: List<Offset>, val weapon: String, val bouncePoints: List<Pair<Int, Offset>>)
private class TkBoom(val x: Double, val y: Double, val radius: Float, val serial: Int)
private class TkPopup(val seat: Int, val damage: Int)

/**
 * Tank Battle: a toy-tank artillery duel on destructible hills. Set the angle and power with the sliders,
 * the plus and minus buttons, or by dragging back from your tank like a slingshot, pick a shell, then press
 * Fire. A dotted preview shows the first moments of the shot (wind included). Every result is spoken in
 * the status line, refused taps say why, and the Fire button is pinned on screen in every layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TankBattleScreen(
    sessionManager: GameSessionManager,
    game: TankGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.TOWER_DEFENCE, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val hintPlan by game.hintPlan
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val ui = tankUi(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val pal = tankPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.windy = extras["wind"] != "false"
        game.specials = extras["specials"] != "false"
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

    // ---- what the canvas shows, and the animation of the latest shot ------------------------------
    var shown by remember { mutableStateOf<TankState?>(null) }
    var flight by remember { mutableStateOf<TkFlight?>(null) }
    val flightP = remember { Animatable(0f) }
    var boom by remember { mutableStateOf<TkBoom?>(null) }
    val boomP = remember { Animatable(1f) }
    var popups by remember { mutableStateOf<List<TkPopup>>(emptyList()) }
    val popupP = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    var callout by remember { mutableStateOf<String?>(null) }
    var botThinking by remember { mutableStateOf(false) }
    var whyKey by remember { mutableStateOf<TankReject?>(null) }
    var dragFrom by remember { mutableStateOf<Offset?>(null) }
    var dragTo by remember { mutableStateOf<Offset?>(null) }

    var angle by remember { mutableIntStateOf(90) }
    var power by remember { mutableIntStateOf(55) }
    var weapon by remember { mutableStateOf(TankWeapons.SHELL) }

    // A State read only inside the field's draw lambda: reading `.value` here would recompose the whole screen every frame.
    val secondsState: State<Float> = if (reduced) remember { mutableStateOf(0f) } else rememberInfiniteTransition(label = "tank-clock").animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing), RepeatMode.Restart), label = "tank-seconds"
    )
    val artCache = remember { TankArtCache() }

    val callDirect = stringResource(R.string.tkb_big_direct)
    val callKill = stringResource(R.string.tkb_big_destroyed)
    val callWin = stringResource(R.string.tkb_big_victory)
    val callDraw = stringResource(R.string.tkb_big_draw)

    LaunchedEffect(state?.shotSerial, state?.seed) {
        val cur = state ?: return@LaunchedEffect
        val prev = shown
        val shot = cur.lastShot
        val isNew = prev != null && prev.seed == cur.seed && cur.shotSerial == prev.shotSerial + 1 && shot != null
        if (!isNew || shot == null) {
            flight = null; boom = null; popups = emptyList()
            shown = cur
            return@LaunchedEffect
        }
        try {
            val pts = shot.path.chunked(2).map { Offset(it[0] / 4f, it[1] / 4f) }
            playSfx(SfxKind.WHOOSH)
            if (!reduced && pts.size > 2) {
                val bounces = shot.impacts.filter { it.kind == TankImpact.BOUNCE }.map { imp ->
                    val o = Offset(imp.x10 / 10f, imp.y10 / 10f)
                    pts.indices.minByOrNull { (pts[it] - o).getDistance() }!! to o
                }
                flight = TkFlight(pts, shot.weapon, bounces)
                flightP.snapTo(0f)
                val ms = (pts.size * 22L).coerceIn(500L, 3200L)
                flightP.animateTo(1f, tween(gameSpeed.scale(ms).toInt().coerceAtLeast(150), easing = LinearEasing))
                flight = null
            }
            shown = cur
            val last = shot.impacts.lastOrNull()
            val hurt = shot.damage.indices.filter { shot.damage[it] > 0 }
            if (last != null && last.kind != TankImpact.LOST) {
                boom = TkBoom(last.x10 / 10.0, last.y10 / 10.0, last.radius.toFloat(), cur.shotSerial)
                playSfx(SfxKind.SOLID_THUNK)
                val big = last.radius >= 24
                if (!reduced) {
                    burst.spawn(
                        Offset((last.x10 / 10f / TankWorld.W).coerceIn(0f, 1f), (1f - last.y10 / 10f / TankWorld.H).coerceIn(0f, 1f)),
                        if (big) 46 else 26, listOf(Color(0xFFFFD166), Color(0xFFEF6C00), Color(0xFF8D6E63), Color.White),
                        speedRange = 0.15f..0.6f, lifeRangeSeconds = 0.5f..1.1f, gravity = 0.9f
                    )
                }
                val ownHurt = hurt.any { h -> !cur.tanks[h].isBot && (!isNetworked || h == localSeat) }
                haptics(if (ownHurt) HapticSignal.FAILURE else if (hurt.isNotEmpty()) HapticSignal.STRONG_ACTION else HapticSignal.LIGHT_TICK)
            }
            popups = hurt.map { TkPopup(it, shot.damage[it]) }
            when {
                cur.event == TankEvent.WIN -> { callout = callWin; playSfx(SfxKind.SUCCESS_CHIME) }
                cur.event == TankEvent.DRAW -> callout = callDraw
                shot.killed.isNotEmpty() -> { callout = callKill; haptics(HapticSignal.ESCALATING); playSfx(SfxKind.SUCCESS_CHIME) }
                last?.kind == TankImpact.TANK -> callout = callDirect
            }
            if (!reduced) {
                launch { boomP.snapTo(0f); boomP.animateTo(1f, tween(gameSpeed.scale(650L).toInt().coerceAtLeast(120), easing = LinearEasing)) }
                launch { popupP.snapTo(0f); popupP.animateTo(1f, tween(gameSpeed.scale(1300L).toInt().coerceAtLeast(200), easing = LinearEasing)) }
                if ((last?.radius ?: 0) >= 24) launch { shake.snapTo(1f); shake.animateTo(0f, tween(450, easing = LinearEasing)) }
            } else {
                popupP.snapTo(0.3f); boomP.snapTo(1f)
            }
            if (callout != null) { delay(gameSpeed.scale(1100L)); callout = null }
            if (reduced) { delay(900L); popups = emptyList() }
        } finally {
            flight = null
            shown = state
            callout = null
        }
    }

    // the controls start from the tank on turn's last settings
    LaunchedEffect(state?.seed, state?.turn, state?.current) {
        val s = state ?: return@LaunchedEffect
        val t = s.tanks[s.current]
        angle = t.angle; power = t.power
        weapon = if (t.canFire(t.weapon)) t.weapon else TankWeapons.SHELL
    }
    LaunchedEffect(hintPlan) {
        val p = hintPlan ?: return@LaunchedEffect
        angle = p.angle; power = p.power; weapon = p.weapon
    }

    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.phase == TankGame.PHASE_OVER || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(if (s.shotSerial == 0) 900L else 1100L).coerceAtLeast(450L))
            while (flight != null || shown?.shotSerial != s.shotSerial) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.tkb_help_title)
    val helpText = stringResource(R.string.tkb_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = ui.bg, buttonContent = ui.text
            ) {
                Box(Modifier.fillMaxSize().background(ui.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.tkb_waiting_host), color = ui.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }
    val view = shown ?: s
    val animating = flight != null || shown?.shotSerial != s.shotSerial
    fun nameOf(i: Int) = androidContext.seatLabel(s.tanks.getOrNull(i)?.displayName ?: "")
    val isMyTurn = game.isMyTurn(s)
    val canAct = isMyTurn && !animating && s.phase == TankGame.PHASE_AIM
    val finishedBoards = game.sessionWins.value.values.sum()
    val windSign = if (s.wind > 0) 1 else if (s.wind < 0) -1 else 0

    fun weaponName(w: String) = androidContext.getString(
        when (w) { TankWeapons.BIG -> R.string.tkb_w_big; TankWeapons.BOUNCER -> R.string.tkb_w_bouncer; else -> R.string.tkb_w_shell }
    )

    // ---- the words ------------------------------------------------------------------------------
    val shotText: String = run {
        val shot = view.lastShot ?: return@run ""
        val parts = ArrayList<String>()
        parts += androidContext.sayFor(nameOf(shot.seat), R.string.tkb_ann_fired, R.string.tkb_ann_fired_you, weaponName(shot.weapon).lowercase())
        val hurt = shot.damage.indices.filter { shot.damage[it] > 0 }
        if (hurt.isEmpty()) parts += androidContext.getString(R.string.tkb_ann_missed)
        hurt.forEach { h ->
            parts += androidContext.getString(R.string.tkb_ann_lost, nameOf(h), shot.damage[h])
            if (h in shot.killed) parts += androidContext.sayFor(nameOf(h), R.string.tkb_ann_destroyed, R.string.tkb_ann_destroyed_you)
        }
        parts.joinToString(" ")
    }
    val statusText = run {
        val who = nameOf(view.current)
        val turn = when {
            view.phase == TankGame.PHASE_OVER -> {
                val w = view.tanks.indexOfFirst { it.playerId == view.winnerPlayerId }
                if (w >= 0) androidContext.sayFor(nameOf(w), R.string.tkb_ann_win, R.string.tkb_ann_win_you) else androidContext.getString(R.string.tkb_ann_draw)
            }
            hostLeft -> androidContext.getString(R.string.tkb_host_left)
            animating -> ""
            botThinking -> androidContext.getString(R.string.tkb_thinking, who)
            isMyTurn -> androidContext.sayFor(who, R.string.tkb_turn_aim, R.string.tkb_turn_aim_you, windWords(androidContext, s.wind))
            else -> androidContext.sayFor(who, R.string.tkb_turn_named, R.string.tkb_turn_named_you)
        }
        listOf(shotText, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            TankReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.tkb_why_wait else R.string.tkb_why_cpu)
            TankReject.NO_AMMO -> stringResource(R.string.tkb_why_ammo)
            TankReject.OVER -> stringResource(R.string.tkb_why_over)
            TankReject.BAD_AIM -> stringResource(R.string.tkb_why_aim)
            TankReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun refuse(r: TankReject) {
        whyKey = r
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    fun doFire() {
        val r = game.explainFire(weapon)
        if (r != TankReject.NONE) { refuse(r); return }
        if (animating) return
        whyKey = null
        game.fire(angle, power, weapon)
    }

    // win celebration
    LaunchedEffect(s.phase, s.winnerPlayerId) {
        if (s.phase != TankGame.PHASE_OVER) return@LaunchedEffect
        val winnerSeat = s.tanks.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.tanks[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        while (shown?.shotSerial != s.shotSerial) delay(60)
        delay(gameSpeed.scale(600L))
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 90, listOf(LudoSeatColors[winnerSeat % 4], Color.White, Color(0xFFFFD866)),
                speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }

    val fieldDesc = stringResource(
        R.string.tkb_field_desc, windWords(androidContext, s.wind),
        view.tanks.indices.joinToString(". ") { i ->
            androidContext.getString(R.string.tkb_tank_desc, nameOf(i), view.tanks[i].hp, i + 1)
        }
    )

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != TankGame.PHASE_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo && !animating,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = ui.bg, buttonContent = ui.text,
        leaveTitle = stringResource(R.string.tkb_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.tkb_leave_body_session else R.string.tkb_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("tank-battle", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(ui.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val boxH = maxHeight

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("tank-battle", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        view.tanks.indices.forEach { i ->
                            val t = view.tanks[i]
                            val c = LudoSeatColors[i % 4]
                            val isTurn = i == view.current && view.phase != TankGame.PHASE_OVER
                            val chipName = nameOf(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.tkb_you_suffix) else ""
                            val desc = stringResource(R.string.tkb_chip_desc, chipName, t.hp) +
                                (if (t.isBot) " (" + stringResource(R.string.tkb_cpu_tag) + ")" else "") +
                                (if (t.hp <= 0) " " + stringResource(R.string.tkb_destroyed_tag) else "")
                            Row(
                                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) c.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) c else ui.text.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = desc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClSeatBadge(if (t.hp > 0) c else Color(0xFF6B6661), i, 20)
                                Spacer(Modifier.width(5.dp))
                                Text(chipName, color = ui.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text(if (t.hp > 0) "${t.hp}" else "✕", color = ui.text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                WindGauge(s.wind, windSign, ui.text, ui.card)
                Spacer(Modifier.height(2.dp))
                ClStatusLine(statusText, ui.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, ui.text, ui.card)
            }

            val field: @Composable (Modifier) -> Unit = { mod ->
                Box(mod, contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ratio = TankWorld.W.toFloat() / TankWorld.H
                        val fieldMod = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
                        val curAngle by rememberUpdatedState(angle)
                        val curPower by rememberUpdatedState(power)
                        val canActNow by rememberUpdatedState(canAct)
                        val viewNow by rememberUpdatedState(view)
                        val sNow by rememberUpdatedState(s)
                        Box(fieldMod.clip(RoundedCornerShape(12.dp))) {
                            Canvas(
                                Modifier.fillMaxSize()
                                    .semantics { contentDescription = fieldDesc }
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            if (!canActNow) game.explainFire().let { r -> if (r != TankReject.NONE) refuse(r) }
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                if (!canActNow) { game.explainFire().let { r -> if (r != TankReject.NONE) refuse(r) }; dragFrom = null }
                                                else {
                                                    val vv = TankView(size.width.toFloat(), size.height.toFloat())
                                                    val t = sNow.tanks[sNow.current]
                                                    dragFrom = vv.pt(t.x.toDouble(), sNow.terrain[t.x] + 8.0)
                                                    dragTo = start
                                                }
                                            },
                                            onDragEnd = { dragFrom = null; dragTo = null },
                                            onDragCancel = { dragFrom = null; dragTo = null },
                                            onDrag = { change, _ ->
                                                val from = dragFrom
                                                if (from != null && canActNow) {
                                                    dragTo = change.position
                                                    val d = change.position - from
                                                    val len = d.getDistance()
                                                    if (len > 10f * density) {
                                                        val deg = Math.toDegrees(atan2(d.y.toDouble(), -d.x.toDouble()))
                                                        angle = if (deg >= 0) deg.toInt().coerceIn(0, 180) else if (-d.x >= 0) 0 else 180
                                                        val maxLen = 0.42f * minOf(size.width, size.height)
                                                        power = (5 + 95 * (len / maxLen).coerceIn(0f, 1f)).toInt().coerceIn(5, 100)
                                                    }
                                                    change.consume()
                                                }
                                            }
                                        )
                                    }
                            ) {
                                val v = TankView(size.width, size.height)
                                val seconds = secondsState.value
                                val shakeX = if (shake.value > 0f) (kotlin.math.sin(shake.value * 40f) * 4f * shake.value) else 0f
                                translate(shakeX, 0f) {
                                    drawTankSky(pal, s.wind.toFloat(), seconds, reduced, artCache)
                                    drawTankTerrain(v, view.terrain, pal, artCache)
                                    view.tanks.forEachIndexed { i, t ->
                                        val winner = view.phase == TankGame.PHASE_OVER && t.playerId == view.winnerPlayerId
                                        val hop = if (winner && !reduced) kotlin.math.abs(kotlin.math.sin(seconds * 4f)) else 0f
                                        drawTank(v, t, i, view.terrain, LudoSeatColors[i % 4], pal.ink, t.angle.toFloat(), t.hp, hop, seconds, reduced)
                                    }
                                    view.tanks.forEachIndexed { i, t ->
                                        drawTankHp(v, t, view.terrain, t.hp, LudoSeatColors[i % 4], pal.ink, i == view.current && view.phase == TankGame.PHASE_AIM && !animating, seconds, reduced)
                                    }
                                    // aim preview for the local player
                                    if (canAct) {
                                        val me = view.tanks[view.current]
                                        val pts = TankSim.preview(view.terrain.toIntArray(), me.x, angle, power, view.wind)
                                            .map { v.pt(it.first, it.second) }
                                        drawAimPreview(v, pts, LudoSeatColors[view.current % 4])
                                    }
                                    val f = flight
                                    if (f != null) {
                                        val idx = (f.points.size - 1) * flightP.value
                                        val i0 = idx.toInt().coerceIn(0, f.points.size - 1)
                                        val i1 = (i0 + 1).coerceAtMost(f.points.size - 1)
                                        val frac = idx - i0
                                        val a = f.points[i0]; val b = f.points[i1]
                                        val cur = Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
                                        val back = f.points[(i0 - 2).coerceAtLeast(0)]
                                        f.bouncePoints.forEach { (bi, o) ->
                                            if (i0 > bi) drawBouncePuff(v, v.pt(o.x.toDouble(), o.y.toDouble()), ((i0 - bi) / 14f).coerceIn(0f, 1f))
                                        }
                                        drawShell(v, v.pt(cur.x.toDouble(), cur.y.toDouble()), v.pt(back.x.toDouble(), back.y.toDouble()), f.weapon == TankWeapons.BIG)
                                    }
                                    val bm = boom
                                    if (bm != null) drawExplosion(v, v.pt(bm.x, bm.y), bm.radius.coerceAtLeast(10f), boomP.value)
                                    // the drag-back sling
                                    val df = dragFrom; val dt = dragTo
                                    if (df != null && dt != null) {
                                        drawLine(Color.White.copy(alpha = 0.85f), df, dt, 3f)
                                        drawCircle(Color.White.copy(alpha = 0.6f), 10f, dt, style = Stroke(3f))
                                    }
                                    drawBurst(burst)
                                }
                            }
                            // floating damage numbers (real text, so the overlay is readable by large fonts)
                            if (popupP.value < 1f) {
                                popups.forEach { pu ->
                                    val t = view.tanks[pu.seat]
                                    BoxWithConstraints(Modifier.fillMaxSize()) {
                                        val x = maxWidth * (t.x / TankWorld.W.toFloat())
                                        val y = maxHeight * (1f - (view.terrain[t.x] + 24f) / TankWorld.H) - 30.dp * popupP.value
                                        Text(
                                            "-${pu.damage}", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                                            modifier = Modifier.padding(start = (x - 20.dp).coerceAtLeast(0.dp), top = y.coerceAtLeast(0.dp))
                                                .background(Color(0xAA7F1D1D), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp)
                                        )
                                    }
                                }
                            }
                            val co = callout
                            if (co != null) {
                                Text(
                                    co, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 18.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            val controls: @Composable () -> Unit = {
                val me = s.tanks[s.current]
                StepSlider(
                    label = stringResource(R.string.tkb_angle), unit = stringResource(R.string.tkb_degrees, angle),
                    value = angle, range = 0..TankWorld.ANGLE_MAX, enabled = canAct, ui = ui,
                    minusDesc = stringResource(R.string.tkb_angle_minus), plusDesc = stringResource(R.string.tkb_angle_plus),
                    stateText = stringResource(R.string.tkb_angle_state, angle)
                ) { angle = it }
                StepSlider(
                    label = stringResource(R.string.tkb_power), unit = stringResource(R.string.tkb_percent, power),
                    value = power, range = TankWorld.POWER_MIN..TankWorld.POWER_MAX, enabled = canAct, ui = ui,
                    minusDesc = stringResource(R.string.tkb_power_minus), plusDesc = stringResource(R.string.tkb_power_plus),
                    stateText = stringResource(R.string.tkb_power_state, power)
                ) { power = it }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TankWeapons.ALL.forEach { w ->
                        val ammo = me.ammoOf(w)
                        val usable = me.canFire(w)
                        val picked = weapon == w
                        val ammoText = if (ammo < 0) "∞" else "$ammo"
                        val desc = stringResource(R.string.tkb_weapon_desc, weaponName(w), if (ammo < 0) stringResource(R.string.tkb_unlimited) else ammo.toString())
                        OutlinedButton(
                            onClick = { weapon = w }, enabled = canAct && usable,
                            border = BorderStroke(if (picked) 3.dp else 1.dp, if (picked) ui.accent else ui.text.copy(alpha = 0.35f)),
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc; selected = picked },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                        ) {
                            Canvas(Modifier.size(20.dp)) { drawShellIcon(w, if (usable) ui.text else ui.text.copy(alpha = 0.4f)) }
                            Spacer(Modifier.width(6.dp))
                            Text("${weaponName(w)} $ammoText", color = ui.text, fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Text(
                    stringResource(R.string.tkb_drag_hint), color = ui.text.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
                )
            }

            val fireBar: @Composable () -> Unit = {
                Button(
                    onClick = { doFire() }, enabled = canAct,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.tkb_fire), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }

            val finished: @Composable () -> Unit = {
                val winnerSeat = s.tanks.indexOfFirst { it.playerId == s.winnerPlayerId }
                val secs = (game.durationMs.value / 1000L).toInt()
                ClFinishedPanel(
                    title = if (winnerSeat >= 0) sayYou(nameOf(winnerSeat), R.string.tkb_wins, R.string.tkb_wins_you) else stringResource(R.string.tkb_ann_draw),
                    subtitle = if (isNetworked && winnerSeat >= 0) stringResource(if (winnerSeat == localSeat) R.string.tkb_you_won else R.string.tkb_you_lost) else null,
                    lines = s.tanks.indices.map { i ->
                        val t = s.tanks[i]
                        stringResource(R.string.tkb_final_line, nameOf(i), t.damageDealt, t.hits, t.shots)
                    } + listOf(stringResource(R.string.tkb_match_time, "%d:%02d".format(secs / 60, secs % 60))) +
                        (if (finishedBoards > 1) listOf(
                            stringResource(R.string.tkb_session, s.tanks.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                        ) else emptyList()),
                    canPlayAgain = !hostLeft,
                    onPlayAgain = { game.playAgain() },
                    onLeave = { game.leaveSession() },
                    playAgainLabel = stringResource(R.string.tkb_play_again),
                    backLabel = stringResource(R.string.tkb_back_to_menu)
                )
            }

            val over = s.phase == TankGame.PHASE_OVER
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    field(Modifier.weight(0.58f).fillMaxSize())
                    Column(Modifier.weight(0.42f).fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header()
                            if (over) finished() else controls()
                        }
                        if (!over) { Spacer(Modifier.height(6.dp)); fireBar() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { header() }
                    field(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = if (over) boxH * 0.62f else 250.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (over) finished() else controls()
                    }
                    if (!over) { Spacer(Modifier.height(6.dp)); fireBar() }
                }
            }
        }
    }
}

private fun windWords(c: android.content.Context, wind: Int): String = when {
    wind == 0 -> c.getString(R.string.tkb_wind_calm)
    wind > 0 -> c.getString(R.string.tkb_wind_right, wind)
    else -> c.getString(R.string.tkb_wind_left, -wind)
}

/** The wind as an arrow plus a number (never colour alone), with a plain-words description for TalkBack. */
@Composable
private fun WindGauge(wind: Int, sign: Int, text: Color, card: Color) {
    val ctx = LocalContext.current
    val words = windWords(ctx, wind)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(card).padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 32.dp).semantics(mergeDescendants = true) { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.tkb_wind_label), color = text, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.size(width = 70.dp, height = 22.dp)) {
            val cy = size.height / 2
            drawLine(text.copy(alpha = 0.35f), Offset(0f, cy), Offset(size.width, cy), 2f)
            if (sign != 0) {
                val len = size.width * (0.25f + 0.75f * kotlin.math.abs(wind) / TankWorld.WIND_MAX.toFloat())
                val x0 = size.width / 2 - sign * len / 2
                val x1 = size.width / 2 + sign * len / 2
                drawLine(text, Offset(x0, cy), Offset(x1, cy), 5f)
                drawLine(text, Offset(x1, cy), Offset(x1 - sign * 9f, cy - 8f), 5f)
                drawLine(text, Offset(x1, cy), Offset(x1 - sign * 9f, cy + 8f), 5f)
            } else drawCircle(text, 5f, Offset(size.width / 2, cy))
        }
        Spacer(Modifier.width(8.dp))
        Text("${kotlin.math.abs(wind)}", color = text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    }
}

/** A labelled slider with plus and minus buttons (48dp) for exact values and TalkBack. */
@Composable
private fun StepSlider(
    label: String, unit: String, value: Int, range: IntRange, enabled: Boolean, ui: TankUi,
    minusDesc: String, plusDesc: String, stateText: String, onChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ui.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(78.dp))
            OutlinedButton(
                onClick = { onChange((value - 1).coerceAtLeast(range.first)) }, enabled = enabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = minusDesc },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Slider(
                value = value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
                valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = stateText }
            )
            OutlinedButton(
                onClick = { onChange((value + 1).coerceAtMost(range.last)) }, enabled = enabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = plusDesc },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Text(unit, color = ui.text, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 52.dp).padding(start = 6.dp), textAlign = TextAlign.End)
        }
    }
}
