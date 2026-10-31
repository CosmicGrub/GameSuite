package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.curling.CurlingFlag
import com.gamesuite.games.curling.CurlingGame
import com.gamesuite.games.curling.CurlingPhase
import com.gamesuite.games.curling.CurlingPhysics
import com.gamesuite.games.curling.CurlingReject
import com.gamesuite.games.curling.CurlingRules
import com.gamesuite.games.curling.CurlingSheet
import com.gamesuite.games.curling.CurlingSim
import com.gamesuite.games.curling.CurlingSimEvent
import com.gamesuite.games.curling.CurlingSimResult
import com.gamesuite.games.curling.CurlingState
import com.gamesuite.games.curling.CurlingStone
import com.gamesuite.games.curling.CurlingThrow
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
import kotlin.math.abs
import kotlin.math.roundToInt

private val CurlingTeamColors = listOf(Color(0xFFD55E00), Color(0xFFE5B800))
private const val VIEW_W = 475f + 2 * 18f

private class CurlingTheme(val bg: Color, val text: Color, val card: Color, val iceA: Color, val iceB: Color)

@Composable
private fun curlingTheme(dark: Boolean) = if (!dark) CurlingTheme(Color(0xFFEAF3F8), Color(0xFF1E2F3A), Color(0xFFD3E4EE), Color(0xFFF4FAFD), Color(0xFFD4E8F2))
else CurlingTheme(Color(0xFF0F171C), Color(0xFFE4EEF3), Color(0xFF1C2A33), Color(0xFF9FB9C6), Color(0xFF6F8D9D))

/** A delivery being slid by hand right now: the simulation advances with the frames and the player's sweeping. */
private class LiveRun(val sim: CurlingSim, val throwing: CurlingThrow, val team: Int, val id: Int) {
    val log = ArrayList<Int>()
    var sweeping = false
    var openedAt = -1
    var eventIdx = 0
    fun setSweeping(on: Boolean, auto: Boolean) {
        if (auto) return
        if (on == sweeping) return
        if (on) {
            if (log.size / 2 >= CurlingPhysics.MAX_SWEEPS) return
            openedAt = sim.steps
            sweeping = true
        } else {
            if (sim.steps > openedAt) { log += openedAt; log += sim.steps }
            sweeping = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CurlingScreen(
    sessionManager: GameSessionManager,
    game: CurlingGame,
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
    val hintThrow by game.hintThrow
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = curlingTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
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
        game.totalEnds = extras["ends"]?.toIntOrNull()?.takeIf { it in 1..12 } ?: 4
        game.stonesPerTeam = extras["stones"]?.toIntOrNull()?.takeIf { it in 1..8 } ?: 4
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

    // ---- the call (what the player has set up for the next stone) -------------------------
    var callX by remember { mutableFloatStateOf(0f) }
    var callY by remember { mutableFloatStateOf(CurlingSheet.TEE_Y.toFloat()) }
    var curl by remember { mutableIntStateOf(1) }
    var autoSweep by remember { mutableStateOf(false) }
    var aim by remember { mutableIntStateOf(0) }
    var weight by remember { mutableIntStateOf(380) }
    var whyKey by remember { mutableStateOf<CurlingReject?>(null) }

    // ---- motion state --------------------------------------------------------------------
    var live by remember { mutableStateOf<LiveRun?>(null) }
    var liveTick by remember { mutableIntStateOf(0) }
    var liveSkipSerial by remember { mutableIntStateOf(-1) }
    var liveHeld by remember { mutableStateOf<List<CurlingStone>?>(null) }
    var playback by remember { mutableStateOf<CurlingSimResult?>(null) }
    var playFrame by remember { mutableFloatStateOf(0f) }
    var lastSerial by remember { mutableIntStateOf(-1) }
    var camBottom by remember { mutableFloatStateOf(Float.NaN) }
    val scorePulse = remember { Animatable(0f) }

    LaunchedEffect(state?.throwSerial) {
        val cur = state ?: return@LaunchedEffect
        val first = lastSerial < 0
        val isNew = !first && cur.throwSerial == lastSerial + 1 && cur.last != null
        lastSerial = cur.throwSerial
        if (isNew && cur.throwSerial == liveSkipSerial) {
            liveHeld = null
        } else if (isNew) {
            val sim = withContext(Dispatchers.Default) { game.replayOf(cur) } ?: return@LaunchedEffect
            val mine = !isNetworked || cur.last?.seat == localSeat
            try {
                if (reduced || sim.frames.size < 2) {
                    playSfx(SfxKind.SOLID_THUNK, 0f)
                } else {
                    playSfx(SfxKind.WHOOSH, 0f)
                    haptics(HapticSignal.LIGHT_TICK)
                    playback = sim
                    playFrame = 0f
                    var lastNanos = 0L
                    var eventIdx = 0
                    val lastIdx = (sim.frames.size - 1).toFloat()
                    while (playFrame < lastIdx) {
                        withFrameNanos { nanos ->
                            if (lastNanos != 0L) playFrame = (playFrame + (nanos - lastNanos) / 1_000_000_000f * 60f).coerceAtMost(lastIdx)
                            lastNanos = nanos
                        }
                        val stepNow = playFrame * CurlingPhysics.FRAME_STEPS
                        while (eventIdx < sim.events.size && sim.events[eventIdx].step <= stepNow) {
                            val ev = sim.events[eventIdx++]
                            if (ev.kind == CurlingSimEvent.HIT) {
                                playSfx(SfxKind.SOLID_THUNK, ((ev.x / CurlingSheet.HALF_WIDTH.toFloat())).coerceIn(-0.8f, 0.8f))
                                if (mine) haptics(HapticSignal.NORMAL_ACTION)
                            }
                        }
                    }
                    delay(gameSpeed.scale(200L))
                }
            } finally {
                playback = null
            }
        }
        if (cur.flags and CurlingFlag.TAKEOUT != 0) haptics(HapticSignal.STRONG_ACTION)
        if (isNew && cur.flags and (CurlingFlag.HOGGED or CurlingFlag.OUT) != 0) {
            playSfx(SfxKind.INVALID_BUZZ, 0f)
            haptics(HapticSignal.FAILURE)
        }
        if (cur.phase != CurlingPhase.THROWING && isNew) {
            scorePulse.snapTo(0f)
            if (!reduced) scorePulse.animateTo(1f, tween(900))
            if (cur.endScorer >= 0) playSfx(SfxKind.SUCCESS_CHIME, 0f)
        }
    }

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (!game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(900L).coerceAtLeast(500L))
            while (playback != null || live != null) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.curling_help_title)
    val helpText = stringResource(R.string.curling_help_text)
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

    val busy = live != null || playback != null || liveHeld != null
    val isMyTurn = game.isMyTurn(s)
    val team = s.teamToThrow
    val stoneId = team * 8 + s.thrown[team]
    val sweepList = if (autoSweep) CurlingSheet.fullSweep else emptyList()

    fun solveTo(tx: Float, ty: Float, forCurl: Int = curl, sweeps: List<Int> = sweepList) {
        callX = tx; callY = ty
        CurlingPhysics.solveDraw(tx.toDouble(), ty.toDouble(), forCurl, sweeps)?.let { aim = it.aim; weight = it.weight }
    }

    // a fresh call for each new stone
    LaunchedEffect(s.throwSerial, s.phase, s.end) {
        if (s.phase == CurlingPhase.THROWING) {
            callX = 0f; callY = CurlingSheet.TEE_Y.toFloat()
            solveTo(0f, CurlingSheet.TEE_Y.toFloat())
        }
    }
    LaunchedEffect(hintThrow) {
        hintThrow?.let {
            aim = it.aim; weight = it.weight; curl = it.curl; autoSweep = it.sweeps.isNotEmpty()
            val stop = CurlingPhysics.loneStop(it)
            callX = stop[0].toFloat(); callY = stop[1].toFloat()
        }
    }

    val draft = CurlingThrow(aim, weight, curl, sweepList)

    fun launchLive() {
        if (busy) return
        val reason = game.explainThrow(draft)
        if (reason != CurlingReject.NONE) {
            whyKey = reason; playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE); return
        }
        whyKey = null
        val t = draft
        val run = LiveRun(CurlingSim(s.stones, team, stoneId, t), t, team, stoneId)
        live = run
        liveSkipSerial = s.throwSerial + 1
        haptics(HapticSignal.NORMAL_ACTION)
        playSfx(SfxKind.WHOOSH, 0f)
        scope.launch {
            var lastNanos = 0L
            var acc = 0f
            val k = run.sim.n - 1
            val rate = 240f
            try {
                while (run.sim.steps < CurlingPhysics.MAX_STEPS && run.sim.isMoving()) {
                    withFrameNanos { nanos ->
                        if (lastNanos != 0L) acc += ((nanos - lastNanos) / 1_000_000_000f * rate).coerceAtMost(24f)
                        lastNanos = nanos
                    }
                    while (acc >= 1f && run.sim.steps < CurlingPhysics.MAX_STEPS && run.sim.isMoving()) {
                        acc -= 1f
                        val sweepNow = if (autoSweep) CurlingSheet.sweptAt(t.sweeps, run.sim.steps) else run.sweeping
                        run.sim.step(sweepNow)
                    }
                    liveTick++
                    val ev = run.sim.eventList
                    while (run.eventIdx < ev.size) {
                        val e = ev[run.eventIdx++]
                        if (e.kind == CurlingSimEvent.HIT) {
                            playSfx(SfxKind.SOLID_THUNK, (e.x / CurlingSheet.HALF_WIDTH.toFloat()).coerceIn(-0.8f, 0.8f))
                            haptics(HapticSignal.NORMAL_ACTION)
                        }
                    }
                }
                if (run.sweeping) run.setSweeping(false, false)
                val sweeps = if (autoSweep) t.sweeps else run.log.toList()
                val result = run.sim.finish()
                liveHeld = result.stones
                live = null
                delay(gameSpeed.scale(150L))
                // a guest waits for the host's state; a host or solo player sees it at once
                if (!game.throwStone(CurlingThrow(t.aim, t.weight, t.curl, sweeps))) liveHeld = null
            } finally {
                live = null
                if (state?.throwSerial?.let { it >= liveSkipSerial } == true) liveHeld = null
            }
        }
    }

    LaunchedEffect(s.throwSerial) { if (s.throwSerial >= liveSkipSerial) liveHeld = null }

    // celebration at the end of the match
    LaunchedEffect(s.phase, s.throwSerial) {
        if (s.phase != CurlingPhase.MATCH_OVER) return@LaunchedEffect
        val humanWon = s.winnerTeam >= 0 && s.players.indices.any { s.players[it].team == s.winnerTeam && !s.players[it].isBot && (!isNetworked || it == localSeat) }
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME, 0f)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.3f), 70, listOf(CurlingTeamColors[s.winnerTeam], Color.White, Color(0xFFFFD866)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ, 0f)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words ---------------------------------------------------------------------------
    fun seatName(i: Int) = androidContext.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    val redWord = stringResource(R.string.curling_red)
    val yellowWord = stringResource(R.string.curling_yellow)
    fun teamWord(t: Int) = if (t == 0) redWord else yellowWord
    val thrower = seatName(s.seatToThrow)
    val statusText = run {
        val parts = ArrayList<String>()
        val rec = s.last
        if (rec == null) {
            parts += stringResource(R.string.curling_ann_start, s.end, s.totalEnds)
        } else if (s.phase == CurlingPhase.THROWING || s.phase == CurlingPhase.END_SCORED || s.phase == CurlingPhase.MATCH_OVER) {
            val who = seatName(rec.seat)
            when {
                s.flags and CurlingFlag.HOGGED != 0 -> parts += sayYou(who, R.string.curling_ann_hogged, R.string.curling_ann_hogged_you)
                s.flags and CurlingFlag.OUT != 0 -> parts += sayYou(who, R.string.curling_ann_out, R.string.curling_ann_out_you)
                s.flags and CurlingFlag.TAKEOUT != 0 -> parts += sayYou(who, R.string.curling_ann_takeout, R.string.curling_ann_takeout_you)
                s.flags and CurlingFlag.HIT != 0 -> parts += sayYou(who, R.string.curling_ann_hit, R.string.curling_ann_hit_you)
                else -> parts += sayYou(who, R.string.curling_ann_slid, R.string.curling_ann_slid_you)
            }
        }
        if (s.phase == CurlingPhase.END_SCORED || s.phase == CurlingPhase.MATCH_OVER) {
            parts += if (s.endScorer >= 0) stringResource(R.string.curling_end_scored, teamWord(s.endScorer), s.endPoints) else stringResource(R.string.curling_end_blank)
        } else {
            parts += when {
                hostLeft -> stringResource(R.string.ccl_host_left)
                isMyTurn && !isNetworked && s.players.count { !it.isBot } > 1 -> stringResource(R.string.curling_turn_named, thrower)
                isMyTurn -> stringResource(R.string.curling_turn_you)
                botThinking -> stringResource(R.string.curling_thinking, thrower)
                else -> stringResource(R.string.curling_turn_named, thrower)
            }
        }
        parts.filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText = whyKey?.let {
        when (it) {
            CurlingReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.cc_why_wait else R.string.cc_why_cpu)
            CurlingReject.BAD_THROW -> stringResource(R.string.curling_why_bad)
            CurlingReject.NOT_THROWING -> stringResource(R.string.curling_why_not_throwing)
            CurlingReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    val finishedMatches = game.sessionWins.value.values.sum()
    val inHouseCount = s.stones.count { CurlingSheet.inHouse(it.x, it.y) }
    val closest = s.stones.minByOrNull { CurlingSheet.distanceToButton(it.x, it.y) }
    val sheetDescription = stringResource(
        R.string.curling_sheet_desc, s.end, s.totalEnds, s.scores[0], s.scores[1], s.stones.size, inHouseCount,
        closest?.let { stringResource(R.string.curling_closest, teamWord(it.team), CurlingSheet.distanceToButton(it.x, it.y).roundToInt()) } ?: stringResource(R.string.curling_house_empty)
    )

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != CurlingPhase.MATCH_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedMatches > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedMatches == 0,
        onUndo = if (activeCtx?.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT) ({ game.undo() }) else null,
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.curling_leave_title),
        leaveBody = stringResource(if (finishedMatches > 0) R.string.ccl_leave_body_session else R.string.curling_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("curling", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("curling", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (tm in 0..1) {
                            val isTurn = tm == team && s.phase == CurlingPhase.THROWING
                            val names = s.players.filter { it.team == tm }.joinToString(" & ") { androidContext.seatLabel(it.displayName) } +
                                if (isNetworked && s.players.getOrNull(localSeat)?.team == tm) " " + stringResource(R.string.ccl_you_suffix) else ""
                            val left = s.stonesPerTeam - s.thrown[tm]
                            val desc = stringResource(R.string.curling_chip_desc, teamWord(tm), names, s.scores[tm], left) + if (s.hammer == tm) " " + stringResource(R.string.curling_hammer) else ""
                            Row(
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) CurlingTeamColors[tm].copy(alpha = 0.25f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) CurlingTeamColors[tm] else theme.text.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = desc },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(Modifier.size(22.dp)) { drawCurlingStone(Offset(size.width / 2, size.height / 2), size.minDimension * 0.46f, tm, colorblind) }
                                Spacer(Modifier.width(5.dp))
                                Text(names, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(5.dp))
                                Text("${s.scores[tm]}", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Text(" · $left", color = theme.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                                if (s.hammer == tm) Text(" ⚑", color = theme.text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { contentDescription = "" })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.curling_end_line, s.end, s.totalEnds), color = theme.text.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val sheet: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.clipToBounds(), contentAlignment = Alignment.Center) {
                    // keep the sheet's own proportions; it fills the height it is given
                    val w = minOf(maxWidth, maxHeight * 0.66f).coerceAtLeast(120.dp)
                    val canAim = isMyTurn && !busy && s.phase == CurlingPhase.THROWING
                    IceCanvas(
                        s = s, playback = playback, playFrame = playFrame, live = live, liveTick = liveTick, liveHeld = liveHeld,
                        camBottom = camBottom, onCam = { camBottom = it }, canAim = canAim,
                        call = Offset(callX, callY), draft = draft, hint = hintThrow, team = team, stoneId = stoneId,
                        scorePulse = scorePulse.value, theme = theme, reduced = reduced, colorblind = colorblind,
                        description = sheetDescription, modifier = Modifier.size(w, maxHeight),
                        onCall = { x, y -> solveTo(x, y) }
                    )
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }
            }

            val footer: @Composable () -> Unit = {
                @Suppress("UNUSED_VARIABLE") val tickRead = liveTick // re-read when the sweep button changes
                val selectedWord = stringResource(R.string.curling_selected)
                Spacer(Modifier.height(6.dp))
                if (s.phase == CurlingPhase.THROWING && isMyTurn) {
                    val outLabel = stringResource(R.string.curling_out_turn)
                    val inLabel = stringResource(R.string.curling_in_turn)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((value, label) in listOf(-1 to outLabel, 1 to inLabel)) {
                            val selected = curl == value
                            val onClick = { if (!busy) { curl = value; solveTo(callX, callY, value) } }
                            val modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 120.dp).semantics { stateDescription = if (selected) "✓" else ""; role = Role.RadioButton }
                            if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
                            else OutlinedButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)) { Text(label) }
                        }
                    }
                    CurlingSlider(stringResource(R.string.curling_line), aim / 10f, -150f..150f, "${(aim / 10f).roundToInt()}", theme.text) { aim = (it * 10).roundToInt() }
                    CurlingSlider(stringResource(R.string.curling_weight), weight.toFloat(), 0f..1000f, "${weight / 10}%", theme.text) { weight = it.roundToInt() }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(checked = autoSweep, onCheckedChange = { if (!busy) { autoSweep = it; solveTo(callX, callY, curl, if (it) CurlingSheet.fullSweep else emptyList()) } })
                        Text(stringResource(R.string.curling_auto_sweep), color = theme.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (s.phase == CurlingPhase.MATCH_OVER) {
                    Spacer(Modifier.height(10.dp))
                    val winners = s.players.filter { it.team == s.winnerTeam }
                    val title = when {
                        s.winnerTeam < 0 -> stringResource(R.string.cc_draw)
                        winners.size == 1 -> youAware(winners[0].displayName, R.string.curling_wins_one, R.string.yg_win)
                        else -> stringResource(R.string.curling_wins_many, winners.joinToString(" & ") { androidContext.seatLabel(it.displayName) })
                    }
                    val localTeam = s.players.getOrNull(localSeat)?.team
                    ClFinishedPanel(
                        title = title,
                        subtitle = if (isNetworked && s.winnerTeam >= 0) stringResource(if (localTeam == s.winnerTeam) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                        lines = buildList {
                            add(stringResource(R.string.curling_final_score, redWord, s.scores[0], yellowWord, s.scores[1]))
                            for (e in 0 until s.endScores.size / 2) add(stringResource(R.string.curling_end_result, e + 1, s.endScores[e * 2], s.endScores[e * 2 + 1]))
                            if (finishedMatches > 1) add(stringResource(R.string.ccl_session, s.players.joinToString(" · ") { "${it.displayName}: ${game.sessionWins.value[it.playerId] ?: 0}" }))
                        },
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                }
            }

            // Throw, Sweep, Next end, Undo and Hint stay on screen under the scrolling controls (Sweep above all: it is pressed while the stone slides).
            val actions: @Composable () -> Unit = {
                @Suppress("UNUSED_VARIABLE") val tickRead = liveTick
                if (s.phase == CurlingPhase.THROWING && isMyTurn) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = ::launchLive, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth(0.7f)) {
                        Text(stringResource(R.string.curling_throw), fontWeight = FontWeight.Bold)
                    }
                }
                if (live != null && !autoSweep) {
                    val sweepingNow = live?.sweeping == true
                    val sweepOn = stringResource(R.string.curling_sweeping)
                    val sweepOff = stringResource(R.string.curling_sweep)
                    FilledTonalButton(
                        onClick = { live?.setSweeping(!(live?.sweeping ?: false), false); haptics(HapticSignal.LIGHT_TICK); liveTick++ },
                        modifier = Modifier.heightIn(min = 64.dp).fillMaxWidth(0.8f).semantics { stateDescription = if (sweepingNow) sweepOn else "" }
                    ) { Text(if (sweepingNow) sweepOn else sweepOff, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                }
                if (s.phase == CurlingPhase.END_SCORED) {
                    Button(onClick = { game.nextEnd() }, enabled = game.canAdvance() && !busy, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth(0.7f)) {
                        Text(stringResource(R.string.curling_next_end), fontWeight = FontWeight.Bold)
                    }
                }
                if (!isNetworked && s.phase == CurlingPhase.THROWING) {
                    Spacer(Modifier.height(6.dp))
                    ClUndoHintRow(
                        canUndo = game.canUndo && !busy, canHint = isMyTurn && !busy,
                        onUndo = { game.undo() }, onHint = { scope.launch { game.requestHint() } }, contentColor = theme.text
                    )
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.55f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header(); footer()
                        }
                        actions()
                    }
                    sheet(Modifier.weight(0.45f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    sheet(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { footer() }
                    actions()
                }
            }
        }
    }
}

@Composable
private fun CurlingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, textColor: Color, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = shown }
        )
        Text(shown, color = textColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
    }
}

// ------------------------------------------------------------------------------------------
// The ice
// ------------------------------------------------------------------------------------------

@Composable
private fun IceCanvas(
    s: CurlingState, playback: CurlingSimResult?, playFrame: Float, live: LiveRun?, liveTick: Int, liveHeld: List<CurlingStone>?,
    camBottom: Float, onCam: (Float) -> Unit, canAim: Boolean, call: Offset, draft: CurlingThrow, hint: CurlingThrow?,
    team: Int, stoneId: Int, scorePulse: Float, theme: CurlingTheme, reduced: Boolean, colorblind: Boolean,
    description: String, modifier: Modifier, onCall: (Float, Float) -> Unit
) {
    val pulse = if (reduced || !canAim) 0.5f else rememberInfiniteTransition(label = "curl-pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "curl-pulse-a"
    ).value
    val geom = remember { FloatArray(3) } // scale, camBottom, height
    val onCallState by rememberUpdatedState(onCall)
    // the line the stone would take, up to the first stone it touches
    val preview = remember(draft, s.stones, team, stoneId, canAim) {
        if (canAim) CurlingPhysics.previewPath(s.stones, team, stoneId, draft) else null
    }
    val speckle = remember {
        val r = java.util.Random(7)
        List(260) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val scoring = remember(s.stones, s.phase) {
        if (s.phase == CurlingPhase.THROWING) emptySet() else {
            val (scorer, pts) = CurlingRules.scoreEnd(s.stones)
            if (scorer < 0) emptySet() else s.stones.filter { it.team == scorer && CurlingSheet.inHouse(it.x, it.y) }
                .sortedBy { CurlingSheet.distanceToButton(it.x, it.y) }.take(pts).map { it.id }.toSet()
        }
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(canAim) {
                if (!canAim) return@pointerInput
                detectTapGestures { o ->
                    val sc = geom[0]
                    if (sc <= 0f) return@detectTapGestures
                    val x = ((o.x / sc) - VIEW_W / 2f).coerceIn(-150f, 150f)
                    val y = (geom[1] + (geom[2] - o.y / sc)).coerceIn(CurlingSheet.HOG_FAR.toFloat() + 60f, CurlingSheet.BACK_Y.toFloat() - 10f)
                    onCallState(x, y)
                }
            }
            .pointerInput(canAim) {
                if (!canAim) return@pointerInput
                detectDragGestures { change, _ ->
                    val o = change.position
                    val sc = geom[0]
                    if (sc <= 0f) return@detectDragGestures
                    val x = ((o.x / sc) - VIEW_W / 2f).coerceIn(-150f, 150f)
                    val y = (geom[1] + (geom[2] - o.y / sc)).coerceIn(CurlingSheet.HOG_FAR.toFloat() + 60f, CurlingSheet.BACK_Y.toFloat() - 10f)
                    onCallState(x, y)
                }
            }
    ) {
        val sc = size.width / VIEW_W
        val visibleH = size.height / sc
        val houseCam = run {
            val top = minOf(CurlingSheet.BACK_Y.toFloat() + 45f, CurlingSheet.TEE_Y.toFloat() + visibleH * 0.5f)
            top - visibleH
        }
        // where the camera is: on the house, or following the stone that is sliding
        var follow = Float.NaN
        if (live != null) { val k = live.sim.n - 1; follow = live.sim.y[k].toFloat() }
        else if (playback != null) {
            val fl = playFrame.toInt().coerceIn(0, playback.frames.size - 1)
            follow = playback.frames[fl][(playback.ids.size - 1) * 3 + 1]
        }
        val targetCam = if (follow.isNaN()) houseCam else (follow - visibleH * 0.3f).coerceIn(-70f, houseCam)
        val cam = if (reduced || camBottom.isNaN()) targetCam else camBottom + (targetCam - camBottom) * 0.25f
        if (abs(cam - camBottom) > 0.01f || camBottom.isNaN()) onCam(cam)
        geom[0] = sc; geom[1] = cam; geom[2] = visibleH

        fun px(x: Double) = ((x + VIEW_W / 2f) * sc).toFloat()
        fun py(y: Double) = ((cam + visibleH - y) * sc).toFloat()
        fun pt(x: Double, y: Double) = Offset(px(x), py(y))

        // boards and ice
        drawRect(Color(0xFF4A5A66))
        val iceLeft = px(-CurlingSheet.HALF_WIDTH)
        val iceRight = px(CurlingSheet.HALF_WIDTH)
        drawRect(Brush.verticalGradient(listOf(theme.iceA, theme.iceB), 0f, size.height), Offset(iceLeft, 0f), Size(iceRight - iceLeft, size.height))
        for ((a, b, c) in speckle) {
            drawCircle(Color.White.copy(alpha = 0.35f), sc * (0.7f + c * 0.9f), Offset(iceLeft + a * (iceRight - iceLeft), b * size.height))
        }
        drawRect(Color(0x55000000), Offset(iceLeft - sc * 3, 0f), Size(sc * 3, size.height))
        drawRect(Color(0x55000000), Offset(iceRight, 0f), Size(sc * 3, size.height))
        // lines
        val line = Color(0xFF3D6A8C)
        drawLine(line.copy(alpha = 0.5f), pt(0.0, -200.0), pt(0.0, 1800.0), strokeWidth = sc * 2)
        drawLine(Color(0xFFB03A3A), pt(-CurlingSheet.HALF_WIDTH, CurlingSheet.HOG_FAR), pt(CurlingSheet.HALF_WIDTH, CurlingSheet.HOG_FAR), strokeWidth = sc * 7)
        drawLine(line.copy(alpha = 0.7f), pt(-CurlingSheet.HALF_WIDTH, CurlingSheet.TEE_Y), pt(CurlingSheet.HALF_WIDTH, CurlingSheet.TEE_Y), strokeWidth = sc * 2)
        drawLine(line.copy(alpha = 0.7f), pt(-CurlingSheet.HALF_WIDTH, CurlingSheet.BACK_Y), pt(CurlingSheet.HALF_WIDTH, CurlingSheet.BACK_Y), strokeWidth = sc * 2)
        // house
        val tee = pt(0.0, CurlingSheet.TEE_Y)
        drawCircle(Color(0xFF2F6DB5), sc * CurlingSheet.HOUSE_R.toFloat(), tee)
        drawCircle(Color(0xFFF7FBFF), sc * CurlingSheet.RING_8.toFloat(), tee)
        drawCircle(Color(0xFFC0392B), sc * CurlingSheet.RING_4.toFloat(), tee)
        drawCircle(Color(0xFFF7FBFF), sc * CurlingSheet.BUTTON_R.toFloat(), tee)
        for (rr in floatArrayOf(CurlingSheet.HOUSE_R.toFloat(), CurlingSheet.RING_8.toFloat(), CurlingSheet.RING_4.toFloat())) drawCircle(Color(0x66203040), sc * rr, tee, style = Stroke(sc * 1.5f))

        // the stones: sliding live, or replayed, or at rest
        val shown: List<CurlingStone> = liveHeld ?: s.stones
        val mover = when {
            live != null -> {
                val k = live.sim.n - 1
                for (i in 0 until live.sim.n - 1) if (live.sim.alive[i]) drawCurlingStone(pt(live.sim.x[i], live.sim.y[i]), sc * CurlingSheet.STONE_R.toFloat(), live.sim.teams[i], colorblind)
                if (live.sim.alive[k]) drawCurlingStone(pt(live.sim.x[k], live.sim.y[k]), sc * CurlingSheet.STONE_R.toFloat(), live.team, colorblind)
                if (live.sweeping) drawSweepBrush(pt(live.sim.x[k], live.sim.y[k] + 38.0), sc)
                true
            }
            playback != null -> {
                val fl = playFrame.toInt().coerceIn(0, playback.frames.size - 1)
                val nx = (fl + 1).coerceAtMost(playback.frames.size - 1)
                val t = playFrame - fl
                val a = playback.frames[fl]
                val b = playback.frames[nx]
                for (i in playback.ids.indices) {
                    val aliveB = b[i * 3 + 2] > 0.5f
                    if (!aliveB && a[i * 3 + 2] < 0.5f) continue
                    val x = a[i * 3] + (b[i * 3] - a[i * 3]) * t
                    val y = a[i * 3 + 1] + (b[i * 3 + 1] - a[i * 3 + 1]) * t
                    val id = playback.ids[i]
                    drawCurlingStone(pt(x.toDouble(), y.toDouble()), sc * CurlingSheet.STONE_R.toFloat() * (if (aliveB) 1f else (1f - t)), id / 8, colorblind)
                }
                true
            }
            else -> false
        }
        if (!mover) {
            for (st in shown) {
                val c = pt(st.x, st.y)
                val r = sc * CurlingSheet.STONE_R.toFloat()
                if (st.id in scoring) drawCircle(Color(0xFFFFD21F).copy(alpha = 0.35f + 0.5f * (if (reduced) 1f else scorePulse)), r * 1.45f, c, style = Stroke(sc * 3.5f))
                drawCurlingStone(c, r, st.team, colorblind)
            }
        }

        if (canAim && !mover) {
            // the called spot and the line the stone will take
            preview?.let { (path, hit) ->
                if (path.size > 1) {
                    for (i in 1 until path.size) {
                        if (i % 2 == 0) continue
                        drawLine(Color(0xFF1B5C8A).copy(alpha = 0.85f), pt(path[i - 1][0], path[i - 1][1]), pt(path[i][0], path[i][1]), strokeWidth = sc * 4, cap = StrokeCap.Round)
                    }
                    val end = path.last()
                    drawCircle(CurlingTeamColors[team].copy(alpha = 0.45f), sc * CurlingSheet.STONE_R.toFloat(), pt(end[0], end[1]))
                    drawCircle(CurlingTeamColors[team], sc * CurlingSheet.STONE_R.toFloat(), pt(end[0], end[1]), style = Stroke(sc * 3, pathEffect = if (hit) null else PathEffect.dashPathEffect(floatArrayOf(sc * 8, sc * 6))))
                    if (hit) drawCircle(Color(0xFFB03A3A), sc * 6, pt(end[0], end[1]))
                }
            }
            val broom = pt(call.x.toDouble(), call.y.toDouble())
            drawCircle(Color.White.copy(alpha = 0.3f + 0.5f * pulse), sc * 26, broom, style = Stroke(sc * 3))
            drawLine(Color(0xFF203040), Offset(broom.x - sc * 10, broom.y), Offset(broom.x + sc * 10, broom.y), strokeWidth = sc * 3)
            drawLine(Color(0xFF203040), Offset(broom.x, broom.y - sc * 10), Offset(broom.x, broom.y + sc * 10), strokeWidth = sc * 3)
            if (hint != null) {
                val stop = CurlingPhysics.loneStop(hint)
                drawCircle(Color(0xFFFFD21F), sc * 30, pt(stop[0], stop[1]), style = Stroke(sc * 4))
            }
        }
    }
}

private fun DrawScope.drawSweepBrush(c: Offset, sc: Float) {
    for (k in -2..2) drawLine(Color(0xFFC9A66B), Offset(c.x + k * sc * 6, c.y), Offset(c.x + k * sc * 6 + sc * 3 * (k % 2), c.y + sc * 22), strokeWidth = sc * 3.2f, cap = StrokeCap.Round)
    drawLine(Color(0xFF5A3A1E), Offset(c.x - sc * 18, c.y), Offset(c.x + sc * 18, c.y), strokeWidth = sc * 5, cap = StrokeCap.Round)
}

/** Granite with a coloured handle. The team shows as colour AND as a glyph on the handle (circle or diamond). */
internal fun DrawScope.drawCurlingStone(c: Offset, r: Float, team: Int, colorblind: Boolean) {
    if (r < 0.5f) return
    val handle = CurlingTeamColors[team % 2]
    drawCircle(Color(0x55000000).copy(alpha = 0.3f), r, Offset(c.x + r * 0.12f, c.y + r * 0.2f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB4B9BE), Color(0xFF878C92), Color(0xFF55595E)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f), r, c)
    drawCircle(Color(0xFF3F4347), r, c, style = Stroke(r * 0.1f))
    drawCircle(Color(0xFF2A2D30).copy(alpha = 0.6f), r * 0.72f, c, style = Stroke(r * 0.06f))
    drawCircle(handle, r * 0.5f, c)
    drawCircle(Color(0xFF2A2D30), r * 0.5f, c, style = Stroke(r * 0.07f))
    drawSeatGlyph(if (team % 2 == 0) 0 else 3, c, r * (if (colorblind) 0.3f else 0.22f))
    drawCircle(Color.White.copy(alpha = 0.45f), r * 0.13f, Offset(c.x - r * 0.45f, c.y - r * 0.5f))
}
