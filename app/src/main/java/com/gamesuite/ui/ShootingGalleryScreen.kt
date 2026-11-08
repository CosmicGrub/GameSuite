package com.gamesuite.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.shootinggallery.GalleryEvent
import com.gamesuite.games.shootinggallery.GalleryGame
import com.gamesuite.games.shootinggallery.GalleryReject
import com.gamesuite.games.shootinggallery.GalleryShot
import com.gamesuite.games.shootinggallery.GalleryState
import com.gamesuite.games.shootinggallery.GalleryWorld
import com.gamesuite.games.shootinggallery.RangeConfig
import com.gamesuite.games.shootinggallery.RangeRun
import com.gamesuite.games.shootinggallery.ShotKind
import com.gamesuite.games.shootinggallery.TargetKind
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class GalleryUi(val bg: Color, val text: Color, val card: Color, val accent: Color)

@Composable
private fun galleryUi(dark: Boolean) = if (!dark) GalleryUi(
    bg = Color(0xFFF6EDEF), text = Color(0xFF2E1A20), card = Color(0xFFEBD9DD), accent = Color(0xFFB23A5C)
) else GalleryUi(
    bg = Color(0xFF1B1318), text = Color(0xFFF3E6EA), card = Color(0xFF2C1F26), accent = Color(0xFFFF92A8)
)

private enum class GPhase { IDLE, COUNTDOWN, RUNNING, PAUSED, REPLAY }

private class SglHitFx(val atMs: Int, val points: Int, val mult: Int, val x: Int, val y: Int)
private class SglMark(val atMs: Int, val x: Int, val y: Int, val hit: Boolean)

/** One range on screen: its run, its effects and (for a computer or a replay) the log it plays back. */
private class SglSide(val seat: Int, val run: RangeRun, val human: Boolean, val feed: List<GalleryShot>?) {
    val hits = HashMap<Int, SglHitFx>()
    val marks = ArrayList<SglMark>()
    val log = ArrayList<GalleryShot>()
    var feedIdx = 0
    var message by mutableStateOf("")
    var tick by mutableIntStateOf(0)
}

private val shootingStyle = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color.White)

/**
 * Shooting Gallery: tap the targets as they ride past. Six shots fill the magazine, then it reloads
 * (or tap Reload early); hits in a row build a combo up to times four; a decoy costs points. Everyone
 * shoots the same seeded range, so the contest is skill. The score of a turn is computed by the host
 * from the tap log, never from what a screen claims. Relaxed pace slows and enlarges the targets;
 * TalkBack users and Relaxed pace get a big "Shoot nearest" button. Start, Reload and Pause stay pinned
 * at the foot of the screen in every layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShootingGalleryScreen(
    sessionManager: GameSessionManager,
    game: GalleryGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.AIR_HOCKEY, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ui = galleryUi(dark)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val touchExploration = rememberClTouchExploration()
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.format = if (extras["format"] == GalleryGame.FORMAT_RACE) GalleryGame.FORMAT_RACE else GalleryGame.FORMAT_TURNS
        game.relaxed = extras["pace"] == "relaxed"
        game.rounds = extras["rounds"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
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

    var phase by remember { mutableStateOf(GPhase.IDLE) }
    val clock = remember { mutableIntStateOf(0) }
    var sides by remember { mutableStateOf<List<SglSide>>(emptyList()) }
    var replayConfig by remember { mutableStateOf<RangeConfig?>(null) }
    var replayName by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var suggestId by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<GalleryReject?>(null) }
    var botThinking by remember { mutableStateOf(false) }
    var ownPending by remember { mutableIntStateOf(0) }
    var seenSerial by remember { mutableIntStateOf(-1) }
    var seenSeed by remember { mutableStateOf(0L) }
    var callout by remember { mutableStateOf<String?>(null) }

    val seconds = if (reduced) 0f else rememberInfiniteTransition(label = "gallery-clock").animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing), RepeatMode.Restart), label = "gallery-seconds"
    ).value

    fun nameOfRaw(s: GalleryState, i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")

    // ---- one shot: the same run drives the live play, a computer's play and a replay ------------
    fun applyShot(side: SglSide, t: Int, x: Int, y: Int, local: Boolean, quiet: Boolean = false) {
        val o = side.run.act(t, x, y)
        side.tick++
        if (o.kind == ShotKind.IGNORED) return
        if (local && (o.kind == ShotKind.HIT || o.kind == ShotKind.MISS || o.kind == ShotKind.RELOAD)) side.log += GalleryShot(t, x, y)
        when (o.kind) {
            ShotKind.HIT -> {
                side.hits[o.targetId] = SglHitFx(t, o.points, o.multiplier, x, y)
                side.marks += SglMark(t, x, y, true)
                val target = side.run.targets.firstOrNull { it.id == o.targetId }
                val decoy = target?.kind == TargetKind.DECOY
                val star = target?.kind == TargetKind.STAR
                val msg = when {
                    decoy -> androidContext.getString(R.string.sgl_say_decoy, -o.points)
                    star -> androidContext.getString(R.string.sgl_say_star, o.points)
                    o.zone == 3 -> androidContext.getString(R.string.sgl_say_bull, o.points)
                    o.multiplier > 1 -> androidContext.getString(R.string.sgl_say_hit_combo, o.points, o.multiplier)
                    else -> androidContext.getString(R.string.sgl_say_hit, o.points)
                }
                side.message = msg
                if (!quiet) {
                    playSfx(if (decoy) SfxKind.INVALID_BUZZ else if (star || o.zone == 3) SfxKind.SUCCESS_CHIME else SfxKind.SOLID_THUNK)
                    if (local) haptics(if (decoy) HapticSignal.FAILURE else if (star) HapticSignal.SUCCESS else if (o.multiplier > 1) HapticSignal.STRONG_ACTION else HapticSignal.NORMAL_ACTION)
                }
                if (!reduced && !quiet) {
                    val cols = if (decoy) listOf(Color(0xFF9E9E9E), Color(0xFF424242)) else listOf(Color(0xFFFFD166), Color(0xFFFF8A65), Color.White)
                    burst.spawn(Offset(x / GalleryWorld.W.toFloat(), y / GalleryWorld.H.toFloat()), if (star) 30 else 14, cols,
                        speedRange = 0.15f..0.5f, lifeRangeSeconds = 0.4f..0.9f, gravity = 1.1f)
                }
            }
            ShotKind.MISS -> {
                side.marks += SglMark(t, x, y, false)
                side.message = androidContext.getString(R.string.sgl_say_miss)
                if (!quiet) { playSfx(SfxKind.LIGHT_TICK); if (local) haptics(HapticSignal.LIGHT_TICK) }
            }
            ShotKind.DRY -> {
                side.message = androidContext.getString(R.string.sgl_say_dry)
                if (!quiet) { playSfx(SfxKind.INVALID_BUZZ); if (local) haptics(HapticSignal.LIGHT_TICK) }
            }
            ShotKind.RELOAD -> {
                side.message = androidContext.getString(R.string.sgl_say_reload)
                if (!quiet) playSfx(SfxKind.WHOOSH)
            }
            ShotKind.IGNORED -> Unit
        }
    }

    fun finishRun() {
        val s = state ?: return
        val running = sides
        if (phase == GPhase.REPLAY) { phase = GPhase.IDLE; sides = emptyList(); replayConfig = null; return }
        phase = GPhase.IDLE
        if (s.format == GalleryGame.FORMAT_RACE) {
            ownPending = 0
            running.forEach { side ->
                val log = if (side.human) side.log else side.feed ?: emptyList()
                if (game.submitTurn(side.seat, log)) ownPending++
            }
        } else {
            val side = running.firstOrNull() ?: return
            ownPending = 0
            if (game.submitTurn(side.seat, side.log)) ownPending = 1
        }
    }

    fun beginReplay(s: GalleryState) {
        val lt = s.lastTurn ?: return
        val cfg = RangeConfig(s.seed, lt.round, s.relaxed)
        val side = SglSide(lt.seat, RangeRun(cfg), human = false, feed = lt.shots)
        sides = listOf(side)
        replayConfig = cfg
        replayName = nameOfRaw(s, lt.seat)
        clock.intValue = 0
        phase = GPhase.REPLAY
    }

    fun startPlay(s: GalleryState) {
        val cfg = s.config
        sides = if (s.format == GalleryGame.FORMAT_RACE) {
            s.players.indices.map { i ->
                val bot = s.players[i].isBot
                SglSide(i, RangeRun(cfg), human = !bot, feed = if (bot) game.botLogFor(s, i) else null)
            }
        } else {
            listOf(SglSide(s.current, RangeRun(cfg), human = true, feed = null))
        }
        replayConfig = null
        clock.intValue = 0
        suggestId = -1
        scope.launch {
            phase = GPhase.COUNTDOWN
            for (n in 3 downTo 1) {
                countdown = n
                playSfx(SfxKind.LIGHT_TICK)
                delay(gameSpeed.scale(650L).coerceAtLeast(300L))
            }
            countdown = 0
            playSfx(SfxKind.SUCCESS_CHIME)
            if (phase == GPhase.COUNTDOWN) phase = GPhase.RUNNING
        }
    }

    // the range clock: real time while shooting, three times as fast for a replay
    LaunchedEffect(phase) {
        if (phase != GPhase.RUNNING && phase != GPhase.REPLAY) return@LaunchedEffect
        val cfg = replayConfig ?: state?.config ?: return@LaunchedEffect
        val speed = if (phase == GPhase.REPLAY) 3 else 1
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000L).toInt().coerceIn(0, 100)
            last = now
            clock.intValue = (clock.intValue + dt * speed).coerceAtMost(cfg.durationMs)
            val t = clock.intValue
            sides.forEach { side ->
                val feed = side.feed
                if (feed != null) {
                    while (side.feedIdx < feed.size && feed[side.feedIdx].t <= t) {
                        val sh = feed[side.feedIdx++]
                        applyShot(side, sh.t, sh.x, sh.y, local = false)
                    }
                }
            }
            if (t >= cfg.durationMs) break
        }
        finishRun()
    }

    // pause when the app goes to the background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE && phase == GPhase.RUNNING) phase = GPhase.PAUSED }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // new turns arriving from the engine: replay someone else's, stay quiet about our own
    LaunchedEffect(state?.turnSerial, state?.seed) {
        val cur = state ?: return@LaunchedEffect
        if (seenSerial < 0 || cur.seed != seenSeed || cur.turnSerial < seenSerial) {
            seenSerial = cur.turnSerial; seenSeed = cur.seed; ownPending = 0
            phase = GPhase.IDLE; sides = emptyList()
            return@LaunchedEffect
        }
        val inc = cur.turnSerial - seenSerial
        if (inc <= 0) return@LaunchedEffect
        seenSerial = cur.turnSerial
        val own = ownPending > 0
        ownPending = (ownPending - inc).coerceAtLeast(0)
        if (own || cur.format == GalleryGame.FORMAT_RACE || cur.lastTurn == null) return@LaunchedEffect
        if (phase == GPhase.IDLE) beginReplay(cur)
    }

    LaunchedEffect(state?.event, state?.turnSerial) {
        val cur = state ?: return@LaunchedEffect
        if (cur.event == GalleryEvent.ROUND_END) { callout = androidContext.getString(R.string.sgl_big_round, cur.round + 1) }
        if (callout != null) { delay(gameSpeed.scale(1200L)); callout = null }
    }

    LaunchedEffect(state, hostLeft, phase) {
        val s = state ?: return@LaunchedEffect
        if (phase != GPhase.IDLE || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(900L).coerceAtLeast(450L))
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.sgl_help_title)
    val helpText = stringResource(R.string.sgl_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = ui.bg, buttonContent = ui.text
            ) {
                Box(Modifier.fillMaxSize().background(ui.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.sgl_waiting_host), color = ui.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val race = s.format == GalleryGame.FORMAT_RACE
    val over = s.phase == GalleryGame.PHASE_OVER
    fun nameOf(i: Int) = nameOfRaw(s, i)
    val finishedBoards = game.sessionWins.value.values.sum()
    val canStart = phase == GPhase.IDLE && !over && !hostLeft &&
        (if (race) game.raceSeatsDue(s).isNotEmpty() else game.isMyTurn(s))
    val assistButton = !race && (touchExploration || s.relaxed)

    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }
    val whyText = whyKey?.let {
        when (it) {
            GalleryReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.sgl_why_wait else R.string.sgl_why_cpu)
            GalleryReject.OVER -> stringResource(R.string.sgl_why_over)
            else -> stringResource(R.string.sgl_why_busy)
        }
    }

    // celebration
    LaunchedEffect(s.phase, s.winnerPlayerId) {
        if (s.phase != GalleryGame.PHASE_OVER) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.players[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        while (phase == GPhase.REPLAY) delay(100)
        delay(gameSpeed.scale(500L))
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 90, listOf(LudoSeatColors[winnerSeat % 4], Color.White, Color(0xFFFFD866)),
                speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else if (winnerSeat >= 0) { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }

    val lastTurnText = s.lastTurn?.let { lt ->
        val acc = if (lt.result.fired > 0) lt.result.hits * 100 / lt.result.fired else 0
        androidContext.getString(R.string.sgl_last_turn, nameOf(lt.seat), lt.result.score, lt.result.hits, lt.result.fired, acc, lt.result.bestStreak)
    } ?: ""
    val statusText = run {
        val activeMsg = if (phase == GPhase.RUNNING || phase == GPhase.REPLAY) sides.lastOrNull { it.message.isNotEmpty() }?.message ?: "" else ""
        val turn = when {
            over -> {
                val w = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                if (w >= 0) androidContext.sayFor(nameOf(w), R.string.sgl_ann_win, R.string.sgl_ann_win_you) else androidContext.getString(R.string.sgl_ann_draw)
            }
            hostLeft -> androidContext.getString(R.string.sgl_host_left)
            phase == GPhase.COUNTDOWN -> androidContext.getString(R.string.sgl_get_ready)
            phase == GPhase.REPLAY -> androidContext.getString(R.string.sgl_replaying, replayName)
            phase == GPhase.PAUSED -> androidContext.getString(R.string.sgl_paused)
            phase == GPhase.RUNNING -> ""
            race -> androidContext.getString(R.string.sgl_race_ready)
            botThinking -> androidContext.getString(R.string.sgl_thinking, nameOf(s.current))
            game.isMyTurn(s) -> androidContext.sayFor(nameOf(s.current), R.string.sgl_turn_ready, R.string.sgl_turn_ready_you, s.round + 1, s.rounds)
            else -> androidContext.sayFor(nameOf(s.current), R.string.sgl_turn_named, R.string.sgl_turn_named_you)
        }
        listOf(activeMsg, if (phase == GPhase.RUNNING) "" else lastTurnText, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onHint = if (isNetworked || race) null else ({
            if (phase == GPhase.RUNNING) {
                val side = sides.firstOrNull()
                val tg = side?.let { GalleryGame.suggest(it.run, clock.intValue) }
                if (side != null && tg != null) {
                    suggestId = tg.id
                    side.message = androidContext.getString(R.string.sgl_hint_try, androidContext.getString(kindName(tg.kind)))
                    scope.launch { delay(1600L); suggestId = -1 }
                }
            } else whyKey = GalleryReject.BAD_LOG
        }),
        buttonFill = ui.bg, buttonContent = ui.text,
        leaveTitle = stringResource(R.string.sgl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.sgl_leave_body_session else R.string.sgl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("shooting-gallery", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(ui.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val playing = phase == GPhase.RUNNING || phase == GPhase.PAUSED || phase == GPhase.COUNTDOWN
            val rangeCfg = replayConfig ?: s.config

            fun tapAt(side: SglSide, x: Int, y: Int) {
                if (phase != GPhase.RUNNING || !side.human) return
                applyShot(side, clock.intValue, x, y, local = true)
            }
            fun reloadSide(side: SglSide) {
                if (phase == GPhase.RUNNING && side.human) applyShot(side, clock.intValue, GalleryWorld.RELOAD_X, 0, local = true)
            }
            fun shootNearest(side: SglSide) {
                if (phase != GPhase.RUNNING) return
                val t = clock.intValue
                val tg = side.run.targets.filter { it.spawnMs <= t && it.aliveAt(t) && !side.run.isGone(it.id) && it.xAt(t) in 0.0..GalleryWorld.W.toDouble() }
                    .minByOrNull { (if (it.kind == TargetKind.DECOY) 10_000 else 0) + (it.exitMs - t) }
                if (tg != null && tg.kind != TargetKind.DECOY) applyShot(side, t, tg.xAt(t).toInt().coerceIn(0, GalleryWorld.W), tg.yAt(t).toInt().coerceIn(0, GalleryWorld.H), local = true)
                else applyShot(side, t, 0, 0, local = true)
            }

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("shooting-gallery", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.players.indices.forEach { i ->
                            val p = s.players[i]
                            val c = LudoSeatColors[i % 4]
                            val isTurn = !over && (if (race) !p.done else i == s.current)
                            val chipName = nameOf(i) + if (isNetworked && i == localSeat) " " + stringResource(R.string.sgl_you_suffix) else ""
                            val desc = stringResource(R.string.sgl_chip_desc, chipName, p.total) + if (p.isBot) " (" + stringResource(R.string.sgl_cpu_tag) + ")" else ""
                            Row(
                                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) c.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) c else ui.text.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = desc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClSeatBadge(c, i, 20)
                                Spacer(Modifier.width(5.dp))
                                Text(chipName, color = ui.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text("${p.total}", color = ui.text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.sgl_round_of, minOf(s.round + 1, s.rounds), s.rounds) + if (s.relaxed) " · " + stringResource(R.string.sgl_relaxed_tag) else "",
                    color = ui.text.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge
                )
                ClStatusLine(statusText, ui.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, ui.text, ui.card)
            }

            fun rangeDesc(side: SglSide?) = androidContext.getString(R.string.sgl_range_desc, minOf(s.round + 1, s.rounds), s.rounds) +
                (side?.let { " " + androidContext.getString(R.string.sgl_range_score, it.run.score) } ?: "")

            val rangeFor: @Composable (SglSide?, Modifier) -> Unit = { side, mod ->
                FitRatio(GalleryWorld.W.toFloat() / GalleryWorld.H, mod) {
                    RangeSurface(
                        side = side, clock = clock, phase = phase, suggestId = suggestId, seconds = seconds, colorblind = colorblind,
                        reduced = reduced, dark = dark, desc = rangeDesc(side), burst = burst, onTap = { x, y -> if (side != null) tapAt(side, x, y) }
                    )
                    if (phase == GPhase.COUNTDOWN && countdown > 0) {
                        Text("$countdown", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp,
                            modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(24.dp)).padding(horizontal = 28.dp))
                    }
                    if (phase == GPhase.PAUSED) {
                        Text(stringResource(R.string.sgl_paused), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp,
                            modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 8.dp))
                    }
                    val co = callout
                    if (co != null) {
                        Text(co, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
            }

            val hud: @Composable (SglSide?) -> Unit = { side ->
                if (side != null) RangeHud(side, clock, rangeCfg, ui, showReload = race && side.human && phase == GPhase.RUNNING, onReload = { reloadSide(side) })
            }

            val finished: @Composable () -> Unit = {
                val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                val secs = (game.durationMs.value / 1000L).toInt()
                ClFinishedPanel(
                    title = if (winnerSeat >= 0) sayYou(nameOf(winnerSeat), R.string.sgl_wins, R.string.sgl_wins_you) else stringResource(R.string.sgl_ann_draw),
                    subtitle = if (isNetworked && winnerSeat >= 0) stringResource(if (winnerSeat == localSeat) R.string.sgl_you_won else R.string.sgl_you_lost) else null,
                    lines = s.players.indices.map { i ->
                        val p = s.players[i]
                        val acc = if (p.fired > 0) p.hits * 100 / p.fired else 0
                        stringResource(R.string.sgl_final_line, nameOf(i), p.total, p.hits, acc, p.bestStreak) +
                            if (s.rounds > 1) " (" + p.roundScores.joinToString(" · ") + ")" else ""
                    } + listOf(stringResource(R.string.sgl_match_time, "%d:%02d".format(secs / 60, secs % 60))) +
                        (if (finishedBoards > 1) listOf(
                            stringResource(R.string.sgl_session, s.players.joinToString(" · ") { "${androidContext.seatLabel(it.displayName)}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                        ) else emptyList()),
                    canPlayAgain = !hostLeft,
                    onPlayAgain = { game.playAgain() },
                    onLeave = { game.leaveSession() },
                    playAgainLabel = stringResource(R.string.sgl_play_again),
                    backLabel = stringResource(R.string.sgl_back_to_menu)
                )
            }

            val actions: @Composable () -> Unit = {
                val mainSide = sides.firstOrNull()
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        over -> Unit
                        canStart -> Button(
                            onClick = { startPlay(s) }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp).weight(1f, fill = false)
                        ) { Text(stringResource(if (race) R.string.sgl_start_race else R.string.sgl_start), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                        phase == GPhase.REPLAY -> OutlinedButton(
                            onClick = { finishRun() }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 160.dp)
                        ) { Text(stringResource(R.string.sgl_skip_replay), color = ui.text) }
                        phase == GPhase.PAUSED -> Button(
                            onClick = { phase = GPhase.RUNNING }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp)
                        ) { Text(stringResource(R.string.sgl_resume), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                        phase == GPhase.RUNNING -> {
                            if (!race && mainSide != null) {
                                Button(onClick = { reloadSide(mainSide) }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 120.dp)) {
                                    Text(stringResource(R.string.sgl_reload), fontWeight = FontWeight.Bold)
                                }
                                if (assistButton) Button(onClick = { shootNearest(mainSide) }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 150.dp)) {
                                    Text(stringResource(R.string.sgl_shoot_nearest), fontWeight = FontWeight.Bold)
                                }
                            }
                            OutlinedButton(onClick = { phase = GPhase.PAUSED }, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 100.dp)) {
                                Text(stringResource(R.string.sgl_pause), color = ui.text)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            val mainSide = sides.firstOrNull()
            val raceSides: List<SglSide?> = if (sides.isEmpty()) listOf(null, null) else sides
            val scoreBoardVisible = !over
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // a split-screen race wants the width for its two ranges
                    val rangeW = if (race && !over) 0.76f else 0.62f
                    Column(Modifier.weight(rangeW).fillMaxSize()) {
                        if (race) {
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                raceSides.forEach { sd ->
                                    Column(Modifier.weight(1f).fillMaxHeight()) { rangeFor(sd, Modifier.weight(1f).fillMaxWidth()); hud(sd) }
                                }
                            }
                        } else {
                            rangeFor(mainSide, Modifier.weight(1f).fillMaxWidth())
                            if (scoreBoardVisible) hud(mainSide)
                        }
                    }
                    Column(Modifier.weight(1f - rangeW).fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header()
                            if (over) finished()
                        }
                        if (!over) { Spacer(Modifier.height(6.dp)); actions() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { header() }
                    if (over) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { finished() }
                    } else if (race) {
                        raceSides.forEach { sd ->
                            Column(Modifier.weight(1f).fillMaxWidth()) { rangeFor(sd, Modifier.weight(1f).fillMaxWidth()); hud(sd) }
                        }
                    } else {
                        // A wide range on a tall screen: keep the range and its HUD together and centred, not stranded at the ends.
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                            rangeFor(mainSide, Modifier.fillMaxWidth().aspectRatio(GalleryWorld.W.toFloat() / GalleryWorld.H))
                            hud(mainSide)
                        }
                    }
                    if (!over) { Spacer(Modifier.height(6.dp)); actions() }
                }
            }
        }
    }
}

private fun kindName(kind: String): Int = when (kind) {
    TargetKind.RING -> R.string.sgl_kind_ring
    TargetKind.STAR -> R.string.sgl_kind_star
    TargetKind.DECOY -> R.string.sgl_kind_decoy
    else -> R.string.sgl_kind_duck
}

/** Sizes [content] to the largest [ratio] (width / height) rectangle that fits. */
@Composable
private fun FitRatio(ratio: Float, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val m = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
        Box(m.clip(RoundedCornerShape(12.dp)), content = content)
    }
}

@Composable
private fun RangeSurface(
    side: SglSide?, clock: State<Int>, phase: GPhase, suggestId: Int, seconds: Float, colorblind: Boolean, reduced: Boolean, dark: Boolean,
    desc: String, burst: ParticleBurst, onTap: (Int, Int) -> Unit
) {
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier.fillMaxSize()
            .semantics { contentDescription = desc }
            .pointerInput(side) {
                detectTapGestures(onPress = { p ->
                    val x = (p.x / size.width * GalleryWorld.W).toInt().coerceIn(0, GalleryWorld.W)
                    val y = (p.y / size.height * GalleryWorld.H).toInt().coerceIn(0, GalleryWorld.H)
                    onTap(x, y)
                })
            }
    ) {
        val sc = size.width / GalleryWorld.W
        drawGalleryBooth(sc, seconds, reduced, dark)
        val sd = side
        if (sd != null && (phase == GPhase.RUNNING || phase == GPhase.REPLAY || phase == GPhase.COUNTDOWN)) {
            val t = clock.value
            sd.tick // re-draw on every shot
            if (phase != GPhase.COUNTDOWN) {
                for (tg in sd.run.targets) {
                    if (tg.spawnMs > t) break
                    val fx = sd.hits[tg.id]
                    val age = if (fx != null) t - fx.atMs else -1
                    val standing = tg.aliveAt(t) && !sd.run.isGone(tg.id)
                    if (!standing && !(age in 0..450)) continue
                    drawGalleryTarget(sc, tg, t, if (age in 0..450) age else -1, colorblind, seconds, tg.id == suggestId, reduced)
                }
                sd.marks.forEach { m -> drawGalleryMark(sc, m.x, m.y, t - m.atMs, m.hit) }
                sd.hits.values.forEach { fx ->
                    val age = t - fx.atMs
                    if (age in 0..900) {
                        val label = if (fx.points >= 0) "+${fx.points}" else "${fx.points}"
                        val text = if (fx.mult > 1) "$label ×${fx.mult}" else label
                        val layout = measurer.measure(text, shootingStyle)
                        val px = (fx.x * sc - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                        val py = fx.y * sc - 30f * sc - age / 900f * 40f * sc
                        drawText(layout, topLeft = Offset(px + 1.5f, py + 1.5f), color = Color(0xCC000000))
                        drawText(layout, topLeft = Offset(px, py), color = if (fx.points < 0) Color(0xFFFF8A80) else Color.White)
                    }
                }
            }
        }
        drawBurst(burst)
    }
}

/** Time left, the magazine and the score and combo of one range. Reads the clock only when drawing. */
@Composable
private fun RangeHud(side: SglSide, clock: State<Int>, cfg: RangeConfig, ui: GalleryUi, showReload: Boolean, onReload: () -> Unit) {
    side.tick // recompose on each shot
    val run = side.run
    val ammoNow = run.ammoAt(clock.value)
    val mult = com.gamesuite.games.shootinggallery.GalleryRange.multiplierFor(run.streak)
    val desc = stringResource(R.string.sgl_hud_desc, run.score, ammoNow, GalleryWorld.MAG, mult)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("${run.score}", color = ui.text, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.widthIn(min = 56.dp))
        Text(
            "×$mult", color = if (mult > 1) ui.accent else ui.text.copy(alpha = 0.6f), fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.titleMedium
        )
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val t = clock.value
                val reloadLeft = run.reloadDoneAt
                val frac = if (reloadLeft >= 0 && t < reloadLeft) 1f - (reloadLeft - t) / cfg.reloadMs.toFloat() else -1f
                drawAmmoRow(GalleryWorld.MAG, ammoNow, frac, ui.text)
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                val left = ((cfg.durationMs - clock.value).coerceAtLeast(0)).toFloat() / cfg.durationMs
                drawRect(ui.text.copy(alpha = 0.15f), size = size)
                drawRect(if (left < 0.2f) Color(0xFFD7263D) else ui.accent, size = androidx.compose.ui.geometry.Size(size.width * left, size.height))
                // tick marks every quarter so the bar reads without colour
                for (q in 1..3) drawLine(ui.text.copy(alpha = 0.5f), Offset(size.width * q / 4f, 0f), Offset(size.width * q / 4f, size.height), 2f)
            }
        }
        if (showReload) OutlinedButton(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sgl_reload), color = ui.text) }
    }
}
