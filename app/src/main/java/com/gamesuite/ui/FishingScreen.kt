package com.gamesuite.ui

import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberPannedProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.fishing.FishHintKind
import com.gamesuite.games.fishing.FishOutcome
import com.gamesuite.games.fishing.FishPhase
import com.gamesuite.games.fishing.FishSpecies
import com.gamesuite.games.fishing.FishingGame
import com.gamesuite.games.fishing.FishingLakes
import com.gamesuite.games.fishing.FishingReject
import com.gamesuite.games.fishing.FishingRules
import com.gamesuite.games.fishing.FishingState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private fun Context.fishName(id: String): String =
    getString(resources.getIdentifier("fsh_fish_$id", "string", packageName).takeIf { it != 0 } ?: R.string.fsh_fish_boot)

internal fun Context.fishWeight(grams: Int): String =
    if (grams < 1000) getString(R.string.fsh_weight_g, grams)
    else getString(R.string.fsh_weight_kg, String.format(Locale.getDefault(), "%.2f", grams / 1000.0))

private fun Context.tierName(sp: FishSpecies) = getString(when (sp.tier) { 0 -> R.string.fsh_tier_0; 1 -> R.string.fsh_tier_1; 2 -> R.string.fsh_tier_2; else -> R.string.fsh_tier_3 })

private fun Context.lakeName(id: String) = getString(when (id) { "lake" -> R.string.fsh_lake_lake; "reservoir" -> R.string.fsh_lake_reservoir; else -> R.string.fsh_lake_pond })

private fun Context.zoneName(lake: String, band: Int, col: Int): String {
    val depth = getString(when (FishingLakes.depthAt(lake, band, col)) { 0 -> R.string.fsh_depth_0; 1 -> R.string.fsh_depth_1; else -> R.string.fsh_depth_2 })
    val cover = getString(when (FishingLakes.coverAt(lake, band, col)) { 1 -> R.string.fsh_cover_1; 2 -> R.string.fsh_cover_2; 3 -> R.string.fsh_cover_3; else -> R.string.fsh_cover_0 })
    return getString(R.string.fsh_zone, depth, cover)
}

/** "<name> did X" or, when the seat is the local "You", the second-person string; both may take [extra]. */
private fun Context.who(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private val SeatColors = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFCC79A7), Color(0xFF009E73))

/**
 * Fishing. One big button does everything: hold and let go to cast (the meter swings, so the timing
 * picks how far out), press when the bobber dips to hook, then hold to reel and let go to ease the
 * line, keeping the tension bar out of the red. Aim by tapping a column of the lake or with the
 * arrows. With a screen reader on the button is a switch you tap on and off. The button is pinned
 * under the lake in every layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FishingScreen(
    sessionManager: GameSessionManager,
    game: FishingGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val ctx = LocalContext.current
    remember { CardSounds.get(ctx) }
    val haptics = rememberHaptics()
    val playSfx = rememberPannedProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val state by game.state
    val hint by game.hint
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val sessionWins by game.sessionWins
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pal = remember(dark) { fishingPalette(dark) }
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reduced
    val screenReader = rememberClTouchExploration()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val c = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.lake = extras["lake"] ?: "pond"
        game.rounds = extras["rounds"]?.toIntOrNull() ?: 3
        game.relaxed = extras["assist"] == "true"
        game.cpuReactionScale = gameSpeed.delayScale.toDouble()
        game.init(c)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = c.activeMode == PlayMode.LOCAL_AD_HOC || c.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }
    LaunchedEffect(gameSpeed) { game.cpuReactionScale = gameSpeed.delayScale.toDouble() }
    LaunchedEffect(game) { while (true) { androidx.compose.runtime.withFrameNanos { game.advance() } } }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val helpTitle = stringResource(R.string.fsh_help_title)
    val helpText = stringResource(R.string.fsh_help_text)
    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = pal.bg, buttonContent = pal.text
            ) {
                Box(Modifier.fillMaxSize().background(pal.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = pal.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    fun name(i: Int) = ctx.seatLabel(s.players.getOrNull(i)?.displayName ?: "")
    val onTurn = s.seat
    val iAct = !s.over && !s.players[onTurn].isBot && (!isNetworked || onTurn == localSeat) && !opponentLeft
    val toggleMode = screenReader
    val lakeWord = ctx.lakeName(s.lake)
    val fish = s.fishId?.let { FishingLakes.speciesById(it) }

    // ---- sounds, haptics, announcements keyed to serials ----
    var announce by remember { mutableStateOf("") }
    var whyText by remember { mutableStateOf<String?>(null) }
    var whySerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastReject?.second) {
        val r = lastReject?.first ?: return@LaunchedEffect
        whyText = ctx.getString(if (r == FishingReject.OPPONENT_LEFT) R.string.fsh_why_left else R.string.fsh_why_over)
        whySerial++; playSfx(SfxKind.INVALID_BUZZ, 0f); haptics(HapticSignal.FAILURE)
    }
    LaunchedEffect(whySerial) { if (whySerial != 0) { val m = whySerial; delay(3200); if (whySerial == m) whyText = null } }
    LaunchedEffect(hint?.serial) { if (hint != null) { delay(3200); if (game.hint.value?.serial == hint?.serial) game.hint.value = null } }
    val splash = remember { Animatable(1f) }
    val tug = remember { Animatable(0f) }
    LaunchedEffect(s.castSerial) {
        if (s.castSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.WHOOSH, 0f); haptics(HapticSignal.NORMAL_ACTION)
        announce = ctx.getString(R.string.fsh_s_cast)
    }
    LaunchedEffect(s.nibbleSerial) {
        if (s.nibbleSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.LIGHT_TICK, 0f); haptics(HapticSignal.LIGHT_TICK)
        if (iAct || !isNetworked) announce = ctx.getString(R.string.fsh_s_nibble)
        if (enhanced) { tug.snapTo(1f); tug.animateTo(0f, tween(gameSpeed.scale(300L).toInt().coerceAtLeast(120))) }
    }
    LaunchedEffect(s.biteSerial) {
        if (s.biteSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.SUCCESS_CHIME, 0f); haptics(HapticSignal.STRONG_ACTION)
        announce = ctx.getString(R.string.fsh_s_bite)
        if (enhanced) { splash.snapTo(0f); splash.animateTo(1f, tween(gameSpeed.scale(500L).toInt().coerceAtLeast(160), easing = LinearEasing)) }
    }
    LaunchedEffect(s.hookSerial) {
        if (s.hookSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.SOLID_THUNK, 0f); haptics(HapticSignal.STRONG_ACTION)
        announce = ctx.getString(R.string.fsh_s_reel)
    }
    LaunchedEffect(s.rushSerial) {
        if (s.rushSerial == 0) return@LaunchedEffect
        haptics(HapticSignal.ESCALATING); playSfx(SfxKind.WHOOSH, 0f)
        announce = ctx.getString(R.string.fsh_s_rush)
    }
    LaunchedEffect(s.warning) { if (s.warning && s.phase == FishPhase.REELING) { announce = ctx.getString(R.string.fsh_s_warn); haptics(HapticSignal.LIGHT_TICK) } }
    val resultScale = remember { Animatable(0f) }
    LaunchedEffect(s.resultSerial) {
        if (s.resultSerial == 0) return@LaunchedEffect
        val n = name(onTurn)
        val sp = fish
        val mine = !s.players[onTurn].isBot && (!isNetworked || onTurn == localSeat)
        announce = when (s.outcome) {
            FishOutcome.CAUGHT -> ctx.who(n, R.string.fsh_r_caught, R.string.fsh_r_caught_you, sp?.let { ctx.fishName(it.id) } ?: "", ctx.fishWeight(s.fishGrams))
            FishOutcome.SNAPPED -> ctx.who(n, R.string.fsh_r_snapped, R.string.fsh_r_snapped_you)
            FishOutcome.SLACK -> ctx.who(n, R.string.fsh_r_slack, R.string.fsh_r_slack_you)
            FishOutcome.TOO_EARLY -> ctx.who(n, R.string.fsh_r_early, R.string.fsh_r_early_you)
            FishOutcome.MISSED -> ctx.who(n, R.string.fsh_r_missed, R.string.fsh_r_missed_you)
            FishOutcome.TIMED_OUT -> ctx.who(n, R.string.fsh_r_timeout, R.string.fsh_r_timeout_you)
            else -> ctx.who(n, R.string.fsh_r_gone, R.string.fsh_r_gone_you)
        }
        val good = s.outcome == FishOutcome.CAUGHT
        playSfx(if (good) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
        haptics(if (good) (if ((sp?.tier ?: 0) >= 2) HapticSignal.CELEBRATION else HapticSignal.SUCCESS) else if (mine) HapticSignal.FAILURE else HapticSignal.LIGHT_TICK)
        if (good && !reduced && (sp?.tier ?: 0) >= 1) burst.spawn(Offset(0.5f, 0.4f), if (sp!!.tier >= 2) 80 else 36, listOf(pal.gold, Color.White, pal.accent), speedRange = 0.25f..0.8f, lifeRangeSeconds = 0.9f..1.7f, gravity = 0.9f)
        if (!reduced) { resultScale.snapTo(0f); resultScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) } else resultScale.snapTo(1f)
    }
    LaunchedEffect(s.phase == FishPhase.OVER) {
        if (!s.over) return@LaunchedEffect
        val top = s.ranking.first()
        announce = ctx.who(name(top), R.string.fsh_over_title, R.string.fsh_over_title_you)
        val mine = isNetworked && top == localSeat || (!isNetworked && !s.players[top].isBot)
        playSfx(if (mine) SfxKind.SUCCESS_CHIME else SfxKind.INVALID_BUZZ, 0f)
        haptics(if (mine) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
        if (!reduced && mine) burst.spawn(Offset(0.5f, 0.35f), 100, listOf(pal.gold, Color.White, pal.accent, SeatColors[top % 4]), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
    }

    val status: String = when {
        s.over -> announce
        opponentLeft -> stringResource(R.string.fsh_opp_left)
        s.phase == FishPhase.AIM -> ctx.who(name(onTurn), R.string.fsh_s_aim, R.string.fsh_s_aim_you)
        s.phase == FishPhase.CHARGING -> stringResource(R.string.fsh_s_charge)
        s.phase == FishPhase.CASTING -> stringResource(R.string.fsh_s_cast)
        s.phase == FishPhase.WAITING -> stringResource(R.string.fsh_s_wait)
        s.phase == FishPhase.BITE -> stringResource(R.string.fsh_s_bite)
        s.phase == FishPhase.REELING -> stringResource(if (s.rushing) R.string.fsh_s_rush else if (s.warning) R.string.fsh_s_warn else R.string.fsh_s_reel)
        else -> announce
    }
    val roundWord = stringResource(R.string.fsh_round_badge, s.round + 1, s.rounds)
    val sceneDesc = if (ctx.isYouName(name(onTurn))) stringResource(R.string.fsh_scene_desc_you, lakeWord, roundWord) else stringResource(R.string.fsh_scene_desc, lakeWord, name(onTurn), roundWord)

    // aim / button state
    val acting = iAct
    fun press(down: Boolean) { if (acting) game.setHold(onTurn, down) }
    fun toggle() { if (acting) game.setHold(onTurn, !s.hold) }
    val aimLabel = ctx.getString(R.string.fsh_aim_desc, s.aimCol + 1, ctx.zoneName(s.lake, 1, s.aimCol))

    val finished = sessionWins.sum()
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        onHint = if (isNetworked) null else ({ game.requestHint() }),
        buttonFill = pal.bg, buttonContent = pal.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finished > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body),
        extraItems = { _ ->
            androidx.compose.runtime.DisposableEffect(Unit) { game.pause(); onDispose { game.resume() } }
        }
    ) {
        val (_, openCoach) = rememberClCoach("fishing", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(pal.bg)) {
            val boxW = maxWidth
            val boxH = maxHeight
            val landscape = boxW > boxH * 1.15f

            @Composable
            fun Header(modifier: Modifier) {
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                        ClCoachButton("fishing", openCoach)
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in s.players.indices) {
                                val turn = i == onTurn && !s.over
                                val desc = stringResource(R.string.fsh_chip_desc, name(i), ctx.fishWeight(s.grams[i]), s.catches[i].size) +
                                    (if (turn) " " + stringResource(R.string.fsh_on_turn) else "")
                                Text(
                                    (if (turn) "▶ " else "") + "${name(i)}  ${ctx.fishWeight(s.grams[i])}",
                                    color = pal.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(14.dp)).background(pal.card)
                                        .border(if (turn) 3.dp else 1.dp, SeatColors[i % 4], RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = desc }
                                )
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOfNotNull(
                            stringResource(R.string.fsh_round_badge, s.round + 1, s.rounds), lakeWord,
                            if (s.relaxed) stringResource(R.string.fsh_relaxed_badge) else null
                        ).forEach {
                            Text(it, color = pal.text, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, pal.accent, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                        if (!isNetworked && !s.over) OutlinedButton(
                            onClick = { game.requestHint() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.text), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_hint)) }
                    }
                    ClStatusLine(status, pal.text, Modifier.fillMaxWidth().padding(top = 4.dp), emphasized = true)
                    hint?.let { h ->
                        ClWhyLine(
                            stringResource(
                                when (h.kind) {
                                    FishHintKind.CAST_HERE -> R.string.fsh_hint_cast; FishHintKind.WAIT -> R.string.fsh_hint_wait
                                    FishHintKind.HOOK_NOW -> R.string.fsh_hint_hook; FishHintKind.REEL_IN -> R.string.fsh_hint_reel
                                    FishHintKind.EASE_OFF -> R.string.fsh_hint_ease; FishHintKind.GET_READY -> R.string.fsh_hint_ready
                                    FishHintKind.HOLD_THE_BUTTON -> R.string.fsh_hint_hold
                                }
                            ), pal.text, pal.card
                        )
                    }
                    ClWhyLine(whyText, pal.text, pal.card)
                }
            }

            @Composable
            fun Scene(modifier: Modifier) {
                var cs by remember { mutableStateOf(IntSize.Zero) }
                val infinite = rememberInfiniteTransition(label = "water")
                val drift by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "drift")
                Box(modifier.onSizeChanged { cs = it }) {
                    Canvas(
                        Modifier.fillMaxSize().semantics { contentDescription = sceneDesc }.pointerInput(acting, s.phase == FishPhase.AIM, cs) {
                            detectTapGestures { p ->
                                if (acting && s.phase == FishPhase.AIM && cs.width > 0) {
                                    val col = (((p.x / cs.width) - 0.05f) / 0.9f * FishingRules.COLUMNS).toInt().coerceIn(0, FishingRules.COLUMNS - 1)
                                    game.setAim(onTurn, col)
                                }
                            }
                        }
                    ) {
                        val w = size.width; val h = size.height
                        val unit = min(w / 420f, h / 520f)
                        drawRect(pal.bank)
                        val lx = w * 0.05f; val lw = w * 0.9f; val ly = h * 0.04f; val lh = h * 0.70f
                        val cw = lw / FishingRules.COLUMNS; val ch = lh / FishingRules.BANDS
                        drawRoundRect(pal.deep, Offset(lx - 6f, ly - 6f), Size(lw + 12f, lh + 12f), CornerRadius(unit * 26f))
                        val t = if (reduced) 0f else drift
                        for (b in 0 until FishingRules.BANDS) for (c in 0 until FishingRules.COLUMNS) {
                            val depth = FishingLakes.depthAt(s.lake, b, c)
                            val col = when (depth) { 0 -> pal.shallow; 1 -> pal.mid; else -> pal.deep }
                            val o = Offset(lx + c * cw, ly + (FishingRules.BANDS - 1 - b) * ch)
                            drawRect(col, o, Size(cw + 1f, ch + 1f))
                            // depth shown by wave lines as well as colour: one, two or three
                            for (k in 0..depth) drawLine(Color.White.copy(alpha = 0.18f), Offset(o.x + cw * 0.15f, o.y + ch * (0.25f + 0.2f * k)), Offset(o.x + cw * 0.85f, o.y + ch * (0.25f + 0.2f * k)), strokeWidth = unit * 2f)
                            drawCover(FishingLakes.coverAt(s.lake, b, c), Offset(o.x + cw / 2, o.y + ch / 2), min(cw, ch) * 0.5f, sin((t + c * 0.2f) * 2f * PI.toFloat()))
                        }
                        // the column being aimed at
                        if (s.phase == FishPhase.AIM || s.phase == FishPhase.CHARGING) {
                            val ax = lx + s.aimCol * cw
                            drawRect(Color.White.copy(alpha = 0.16f), Offset(ax, ly), Size(cw, lh))
                            drawRect(Color.White, Offset(ax, ly), Size(cw, lh), style = Stroke(unit * 3f))
                            if (s.phase == FishPhase.CHARGING) {
                                val band = FishingRules.bandOfPower(meterNow(s))
                                val o = Offset(ax, ly + (FishingRules.BANDS - 1 - band) * ch)
                                drawRect(pal.gold.copy(alpha = 0.35f), o, Size(cw, ch))
                                drawRect(pal.gold, o, Size(cw, ch), style = Stroke(unit * 4f))
                            }
                        }
                        val dockC = Offset(w / 2, h - unit * 44f)
                        val tip = drawDock(dockC, unit, pal, SeatColors[onTurn % 4], if (s.hold) 1f else 0f)
                        val cell = Offset(lx + s.aimCol * cw + cw / 2, ly + (FishingRules.BANDS - 1 - s.band) * ch + ch / 2)
                        val fly = (s.phaseTicks.toFloat() / FishingRules.CAST_TICKS).coerceIn(0f, 1f)
                        when (s.phase) {
                            FishPhase.CASTING -> {
                                val p = Offset(tip.x + (cell.x - tip.x) * fly, tip.y + (cell.y - tip.y) * fly - sin(fly * PI.toFloat()) * unit * 90f)
                                drawLine(Color.White.copy(alpha = 0.8f), tip, p, strokeWidth = unit * 1.4f)
                                drawBobber(p, unit * 8f, 0f)
                            }
                            FishPhase.WAITING, FishPhase.BITE -> {
                                drawLine(Color.White.copy(alpha = 0.8f), tip, cell, strokeWidth = unit * 1.4f)
                                val dip = if (s.phase == FishPhase.BITE) 0.8f else if (s.nibbling) 0.35f else 0f
                                val bob = if (reduced) 0f else sin(drift * 2f * PI.toFloat() * 3f) * 0.08f
                                drawBobber(Offset(cell.x + tug.value * unit * 4f * sin(tug.value * 30f), cell.y), unit * 8f, dip + bob)
                                drawRipple(cell, unit * 22f, if (reduced) 0.5f else (drift * 3f) % 1f)
                                if (s.phase == FishPhase.BITE) {
                                    drawRipple(cell, unit * 40f, splash.value, pal.gold)
                                    drawCircle(pal.gold, unit * 14f, Offset(cell.x, cell.y - unit * 36f))
                                    drawLine(Color(0xFF222222), Offset(cell.x, cell.y - unit * 43f), Offset(cell.x, cell.y - unit * 35f), strokeWidth = unit * 4f)
                                    drawCircle(Color(0xFF222222), unit * 2.2f, Offset(cell.x, cell.y - unit * 29f))
                                }
                            }
                            FishPhase.REELING, FishPhase.RESULT -> {
                                val sp = fish
                                if (s.phase == FishPhase.REELING && sp != null) {
                                    val prog = (1f - (s.distance / s.startDistance).toFloat()).coerceIn(0f, 1f)
                                    val near = Offset(w / 2, ly + lh - ch * 0.15f)
                                    val jitter = if (s.rushing && !reduced) sin(s.phaseTicks * 1.7f) * unit * 7f else 0f
                                    val fp = Offset(cell.x + (near.x - cell.x) * prog + jitter, cell.y + (near.y - cell.y) * prog)
                                    val line = Path().apply { moveTo(tip.x, tip.y); quadraticBezierTo((tip.x + fp.x) / 2 + (if (s.tension < 0.1) unit * 20f else 0f), (tip.y + fp.y) / 2, fp.x, fp.y) }
                                    drawPath(line, Color.White.copy(alpha = 0.9f), style = Stroke(unit * (1.2f + 2.2f * s.tension.toFloat())))
                                    val len = unit * (40f + 70f * (sp.avgG / 20000.0).coerceIn(0.0, 1.0).toFloat() + (s.fishGrams / 60000f) * unit * 40f)
                                    drawFish(fp, len, sp, angleTo(fp, tip) + PI, if (reduced) 0f else wiggleOf(s.phaseTicks / 60f, if (s.rushing) 12f else 5f))
                                    if (s.rushing || s.warning) drawRipple(fp, unit * 36f, if (reduced) 0.5f else (s.phaseTicks % 20) / 20f, pal.warn)
                                } else drawLine(Color.White.copy(alpha = 0.5f), tip, cell, strokeWidth = unit * 1.2f)
                            }
                            else -> {}
                        }
                        drawBurst(burst)
                    }
                    // the result card
                    if (s.phase == FishPhase.RESULT) {
                        val sp = fish
                        Column(
                            Modifier.align(Alignment.Center).graphicsLayer { val sc = 0.7f + 0.3f * resultScale.value; scaleX = sc; scaleY = sc; alpha = resultScale.value.coerceIn(0f, 1f) }
                                .clip(RoundedCornerShape(20.dp)).background(pal.card.copy(alpha = 0.95f)).border(3.dp, if (s.outcome == FishOutcome.CAUGHT) pal.gold else pal.bad, RoundedCornerShape(20.dp)).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (sp != null && s.outcome == FishOutcome.CAUGHT) {
                                Canvas(Modifier.size(140.dp, 70.dp)) { drawFish(Offset(size.width / 2, size.height / 2), size.width * 0.85f, sp, 0.0, 0f) }
                                Text(ctx.fishName(sp.id), color = pal.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Text(ctx.fishWeight(s.fishGrams) + " · " + ctx.tierName(sp), color = pal.text, style = MaterialTheme.typography.titleMedium)
                            } else {
                                Text(announce, color = pal.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn2())
                            }
                        }
                    }
                }
            }

            @Composable
            fun Controls(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.over) {
                        val top = s.ranking.first()
                        val tie = s.ranking.drop(1).any { s.grams[it] == s.grams[top] && s.biggestOf(it) == s.biggestOf(top) && s.catches[it].size == s.catches[top].size }
                        ClFinishedPanel(
                            title = if (tie) stringResource(R.string.fsh_over_tie) else ctx.who(name(top), R.string.fsh_over_title, R.string.fsh_over_title_you),
                            subtitle = stringResource(R.string.fsh_over_sub, ctx.fishWeight(s.grams[top])),
                            lines = s.ranking.mapIndexed { place, i ->
                                val big = s.biggestOf(i)
                                ctx.getString(R.string.fsh_over_line, place + 1, name(i), ctx.fishWeight(s.grams[i]), s.catches[i].size,
                                    if (big > 0) ctx.fishWeight(big) else ctx.getString(R.string.fsh_over_none))
                            } + stringResource(R.string.fsh_over_session, sessionWins.joinToString(" – ")),
                            canPlayAgain = !opponentLeft, onPlayAgain = { game.playAgain() }, onLeave = game::leaveSession
                        )
                        return@Column
                    }
                    // aim arrows (only while aiming)
                    if (s.phase == FishPhase.AIM && acting) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { game.setAim(onTurn, s.aimCol - 1) }, enabled = s.aimCol > 0,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.text), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("◀ " + stringResource(R.string.fsh_aim_left)) }
                            OutlinedButton(onClick = { game.setAim(onTurn, s.aimCol + 1) }, enabled = s.aimCol < FishingRules.COLUMNS - 1,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.text), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.fsh_aim_right) + " ▶") }
                        }
                        Text(aimLabel, color = pal.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { contentDescription = aimLabel })
                    }
                    MeterBar(s, pal, Modifier.fillMaxWidth().height(52.dp))
                    val label = stringResource(
                        when {
                            !acting -> R.string.fsh_btn_other
                            s.phase == FishPhase.AIM -> if (toggleMode) R.string.fsh_btn_cast_tap else R.string.fsh_btn_cast
                            s.phase == FishPhase.CHARGING -> if (toggleMode) R.string.fsh_btn_charge_tap else R.string.fsh_btn_charge
                            s.phase == FishPhase.CASTING || s.phase == FishPhase.WAITING -> R.string.fsh_btn_wait
                            s.phase == FishPhase.BITE -> R.string.fsh_btn_bite
                            s.phase == FishPhase.REELING -> if (toggleMode) R.string.fsh_btn_reel_tap else R.string.fsh_btn_reel
                            else -> R.string.fsh_btn_wait
                        }, *(if (!acting) arrayOf<Any>(name(onTurn)) else emptyArray())
                    )
                    val hot = acting && (s.phase == FishPhase.BITE)
                    val stateWord = stringResource(if (s.hold && acting) R.string.fsh_state_on else R.string.fsh_state_off)
                    val down = s.hold && acting
                    val mod = Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (!acting) pal.card else if (hot) pal.gold else if (down) pal.accent else pal.card)
                        .border(if (hot) 5.dp else 3.dp, if (acting) pal.accent else pal.card, RoundedCornerShape(22.dp))
                        .semantics { contentDescription = label; role = Role.Button; stateDescription = stateWord }
                    val input = if (!acting) mod else if (toggleMode) mod.clickable { toggle() } else mod.pointerInput(onTurn, acting) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            press(true)
                            waitForUpOrCancellation()
                            press(false)
                        }
                    }
                    Box(input, contentAlignment = Alignment.Center) {
                        Text(label, color = if (hot) Color.Black else if (down) Color.White.takeIf { pal.accent.luminance() < 0.5f } ?: Color.Black else pal.text,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Scene(Modifier.weight(1f).fillMaxSize().padding(4.dp))
                    Column(Modifier.width(boxW * 0.42f).fillMaxSize().padding(8.dp)) {
                        Header(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                        Controls(Modifier.fillMaxWidth().heightIn(max = boxH * 0.7f).verticalScroll(rememberScrollState()).padding(top = 6.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Header(Modifier.fillMaxWidth().heightIn(max = boxH * 0.36f).verticalScroll(rememberScrollState()))
                    Scene(Modifier.weight(1f).fillMaxWidth().heightIn(min = 180.dp))
                    Controls(Modifier.fillMaxWidth().heightIn(max = boxH * 0.55f).verticalScroll(rememberScrollState()).padding(top = 6.dp))
                }
            }
        }
    }
}

private fun Modifier.widthIn2(): Modifier = this.width(240.dp)

private fun meterNow(s: FishingState): Double = FishingRules.meterAt(s.phaseTicks)

/** The power meter (while casting) or the tension bar and distance (while reeling). Zones carry words/hatching, never colour alone. */
@Composable
private fun MeterBar(s: FishingState, pal: FishingPalette, modifier: Modifier) {
    val ctx = LocalContext.current
    val reeling = s.phase == FishPhase.REELING
    val power = meterNow(s)
    val desc = if (reeling) {
        val zone = ctx.getString(if (s.tension > s.sweetHi) R.string.fsh_zone_tight else if (s.tension < s.sweetLo) R.string.fsh_zone_loose else R.string.fsh_zone_ok)
        ctx.getString(R.string.fsh_tension_desc, (s.tension * 100).toInt(), zone) + " " + ctx.getString(R.string.fsh_distance_desc, s.distance.toInt())
    } else ctx.getString(R.string.fsh_meter_power_desc, (power * 100).toInt())
    val looseW = stringResource(R.string.fsh_zone_loose); val okW = stringResource(R.string.fsh_zone_ok); val tightW = stringResource(R.string.fsh_zone_tight)
    Column(modifier.semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(percent = 50))) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(pal.card, Offset.Zero, size, r)
            if (reeling) {
                val lo = (s.sweetLo * size.width / 1.05).toFloat(); val hi = (s.sweetHi * size.width / 1.05).toFloat()
                drawRect(pal.good.copy(alpha = 0.55f), Offset(lo, 0f), Size(hi - lo, size.height))
                var x = hi
                while (x < size.width) { drawLine(pal.bad, Offset(x, size.height), Offset(x + size.height * 0.5f, 0f), strokeWidth = 3f); x += 14f }
                var y = 0f
                while (y < lo) { drawCircle(pal.warn, 2.5f, Offset(y + 6f, size.height / 2)); y += 14f }
                val px = (s.tension * size.width / 1.05).toFloat().coerceIn(0f, size.width)
                drawRoundRect(pal.text, Offset(px - 6f, 0f), Size(12f, size.height), CornerRadius(6f))
                drawRoundRect(pal.text, Offset.Zero, size, r, style = Stroke(2f))
            } else {
                val seg = size.width / FishingRules.BANDS
                for (b in 0 until FishingRules.BANDS) {
                    drawRect(if (b % 2 == 0) pal.shallow.copy(alpha = 0.5f) else pal.mid.copy(alpha = 0.5f), Offset(b * seg, 0f), Size(seg, size.height))
                    drawLine(pal.text, Offset(b * seg, 0f), Offset(b * seg, size.height), strokeWidth = 2f)
                    for (k in 0..b) drawCircle(pal.text, 3.5f, Offset(b * seg + seg * 0.2f + k * 11f, size.height * 0.22f))
                }
                if (s.phase == FishPhase.CHARGING) {
                    val px = (power * size.width).toFloat()
                    drawRoundRect(pal.gold, Offset(px - 7f, 0f), Size(14f, size.height), CornerRadius(7f))
                }
                drawRoundRect(pal.text, Offset.Zero, size, r, style = Stroke(2f))
            }
        }
        if (reeling) Row(Modifier.fillMaxWidth()) {
            Text(looseW, color = pal.text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(okW, color = pal.text, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(tightW, color = pal.text, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}
