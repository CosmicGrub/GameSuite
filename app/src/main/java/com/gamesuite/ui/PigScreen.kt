package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.gamesuite.games.pigdice.PigEvent
import com.gamesuite.games.pigdice.PigGame
import com.gamesuite.games.pigdice.PigMove
import com.gamesuite.games.pigdice.PigReject
import com.gamesuite.games.pigdice.PigRules
import com.gamesuite.games.pigdice.PigState
import com.gamesuite.games.pigdice.PigVariant
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private class PigTheme(val bg: Color, val text: Color, val card: Color, val feltA: Color, val feltB: Color, val rim: Color)

@Composable
private fun pigTheme(dark: Boolean) = if (!dark) PigTheme(
    bg = Color(0xFFFBEFEA), text = Color(0xFF3B2A28), card = Color(0xFFF2DDD6),
    feltA = Color(0xFFE88F9B), feltB = Color(0xFFC85F75), rim = Color(0xFF7A3B47)
) else PigTheme(
    bg = Color(0xFF1D1315), text = Color(0xFFF6E7E5), card = Color(0xFF2C1D20),
    feltA = Color(0xFF8E4A57), feltB = Color(0xFF5E2D3B), rim = Color(0xFFB36A78)
)

/** Seat colours: Okabe-Ito, plus the seat number drawn in the chip, so identity never rests on hue alone. */
private val pigSeatColors = ChineseSeatColors

private fun Context.pigText(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

@Composable
private fun pigText(name: String, other: Int, you: Int, vararg extra: Any): String =
    LocalContext.current.pigText(name, other, you, *extra)

/**
 * Pig with real tumbling dice. Roll with the big Roll button, bank with Hold; both stay on screen in every
 * orientation and at large font sizes (only the secondary rows scroll). Dice are drawn as lit 3D cubes that
 * tumble and settle; Reduced Motion skips the tumble. Every roll, bust and bank is spoken through a live
 * region, and every illegal tap says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PigScreen(
    sessionManager: GameSessionManager,
    game: PigGame,
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
    val hint by game.hint
    val hostLeft by game.hostLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val theme = pigTheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.variant = PigVariant.fromId(extras["variant"])
        game.target = extras["target"]?.toIntOrNull()?.takeIf { it in 20..200 } ?: PigRules.DEFAULT_TARGET
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

    // ---- animation state ---------------------------------------------------------------------
    val tumble = remember { Animatable(1f) }   // 0 = just thrown, 1 = settled
    val shake = remember { Animatable(0f) }
    var shownDice by remember { mutableStateOf<List<Int>>(emptyList()) }
    var rolling by remember { mutableStateOf(false) }
    var lastRollSerial by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<PigReject?>(null) }
    var hogCount by remember { mutableIntStateOf(3) }
    var botThinking by remember { mutableStateOf(false) }

    LaunchedEffect(state?.rollSerial, state?.turnSerial) {
        val cur = state ?: return@LaunchedEffect
        val first = lastRollSerial < 0
        val isNewRoll = !first && cur.rollSerial == lastRollSerial + 1
        lastRollSerial = cur.rollSerial
        if (!isNewRoll || cur.dice.isEmpty() || reduced) {
            shownDice = cur.dice
            tumble.snapTo(1f)
            if (isNewRoll) afterRollFeedback(cur, playSfx, haptics)
            return@LaunchedEffect
        }
        rolling = true
        try {
            shownDice = cur.dice
            tumble.snapTo(0f)
            playSfx(SfxKind.WHOOSH)
            val dur = gameSpeed.scale(720L).toInt().coerceAtLeast(160)
            val ticker = scope.launch {
                repeat(3) { delay(gameSpeed.scale(170L)); playSfx(SfxKind.LIGHT_TICK) }
            }
            tumble.animateTo(1f, tween(dur, easing = LinearEasing))
            ticker.cancel()
        } finally {
            rolling = false
            tumble.snapTo(1f)
        }
        afterRollFeedback(cur, playSfx, haptics)
        if (cur.event == PigEvent.BUST || cur.event == PigEvent.HOG_BUST) {
            if (!reduced) {
                shake.snapTo(0f)
                shake.animateTo(1f, tween(gameSpeed.scale(380L).toInt().coerceAtLeast(120), easing = LinearEasing))
                shake.snapTo(0f)
            }
        }
    }

    // a hold or an automatic bank has no roll: just feedback
    LaunchedEffect(state?.turnSerial, state?.event) {
        val cur = state ?: return@LaunchedEffect
        if (cur.event == PigEvent.HOLD) { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION) }
    }

    LaunchedEffect(state, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (s.over || !game.botToMove(s)) return@LaunchedEffect
        botThinking = true
        try {
            delay(gameSpeed.scale(900L).coerceAtLeast(450L))
            while (rolling) delay(40)
            game.playBotTurnAsync()
        } finally {
            botThinking = false
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.pig_help_title)
    val helpText = stringResource(R.string.pig_help_text)
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
            onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
            buttonFill = theme.bg, buttonContent = theme.text
        ) {
            Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ccl_waiting_host), color = theme.text, style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    val isMyTurn = game.isMyTurn(s)
    val settled = !rolling
    val finishedGames = game.sessionWins.value.values.sum()
    val current = seatName(s.currentPlayerIndex)
    val somebodyHuman = s.players.any { !it.isBot }
    val pureHotseat = !isNetworked && s.players.none { it.isBot }

    LaunchedEffect(s.over, s.rollSerial) {
        if (!s.over) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val humanWon = winnerSeat >= 0 && !s.players[winnerSeat].isBot && (!isNetworked || winnerSeat == localSeat)
        delay(gameSpeed.scale(500L))
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 80, listOf(Color(0xFFFFD866), Color.White, pigSeatColors[winnerSeat % 6]), speedRange = 0.25f..0.95f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words ---------------------------------------------------------------------------
    val diceWords = s.dice.joinToString(" + ")
    val ann = run {
        val who = seatName(s.eventSeat)
        when (s.event) {
            PigEvent.ROLL -> pigText(who, R.string.pig_ann_roll, R.string.yg_pig_ann_roll, diceWords, s.eventPoints, s.turnTotal)
            PigEvent.BUST ->
                if (s.eventPoints > 0) pigText(who, R.string.pig_ann_bust, R.string.yg_pig_ann_bust, diceWords, s.eventPoints)
                else pigText(who, R.string.pig_ann_bust_zero, R.string.yg_pig_ann_bust_zero, diceWords)
            PigEvent.SNAKE_EYES -> pigText(who, R.string.pig_ann_snake, R.string.yg_pig_ann_snake, PigRules.SNAKE_EYES_POINTS)
            PigEvent.DOUBLES -> pigText(who, R.string.pig_ann_doubles, R.string.yg_pig_ann_doubles, diceWords, s.eventPoints)
            PigEvent.EXACT -> pigText(who, R.string.pig_ann_exact, R.string.yg_pig_ann_exact, s.eventPoints)
            PigEvent.HOG -> pigText(who, R.string.pig_ann_hog, R.string.yg_pig_ann_hog, diceWords, s.eventPoints)
            PigEvent.HOG_BUST -> pigText(who, R.string.pig_ann_hog_bust, R.string.yg_pig_ann_hog_bust, diceWords)
            PigEvent.HOLD -> pigText(who, R.string.pig_ann_hold, R.string.yg_pig_ann_hold, s.eventPoints)
            PigEvent.WIN -> pigText(who, R.string.pig_ann_win, R.string.yg_pig_ann_win, s.scores[s.eventSeat])
            else -> pigText(seatName(s.currentPlayerIndex), R.string.pig_ann_start, R.string.yg_pig_ann_start, s.target)
        }
    }
    val turn = when {
        s.over -> ""
        hostLeft -> stringResource(R.string.ccl_host_left)
        isMyTurn && s.variant.singleRoll -> stringResource(R.string.pig_turn_hog)
        isMyTurn && pureHotseat -> stringResource(R.string.pig_turn_named_roll, current)
        isMyTurn -> stringResource(R.string.pig_turn_roll)
        botThinking -> stringResource(R.string.pig_thinking, current)
        else -> stringResource(R.string.pig_turn_named, current)
    }
    val statusText = listOf(ann, turn).filter { it.isNotBlank() }.joinToString(" ")
    val hintText = hint?.let { h ->
        val pct = (h.winChance * 100).roundToInt()
        when (val m = h.move) {
            is PigMove.Roll -> if (s.variant.singleRoll) stringResource(R.string.pig_hint_dice, m.count, pct) else stringResource(R.string.pig_hint_roll, pct)
            PigMove.Hold -> stringResource(R.string.pig_hint_hold, pct)
        }
    }
    val whyText = whyKey?.let { k ->
        when (k) {
            PigReject.NOT_YOUR_TURN -> stringResource(if (isNetworked) R.string.cc_why_wait else R.string.cc_why_cpu)
            PigReject.NOTHING_TO_HOLD -> stringResource(R.string.pig_why_nothing_to_hold)
            PigReject.HOG_NO_HOLD -> stringResource(R.string.pig_why_hog_no_hold)
            PigReject.OVER -> stringResource(R.string.cc_why_over)
            PigReject.BAD_COUNT, PigReject.NONE -> null
        }
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    fun tapRoll() {
        val count = if (s.variant.singleRoll) hogCount else s.variant.diceCount
        val reason = game.explainRoll(count)
        if (reason == PigReject.NONE) { if (settled) { whyKey = null; game.roll(count) } }
        else { whyKey = reason; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }
    fun tapHold() {
        val reason = game.explainHold()
        if (reason == PigReject.NONE) { if (settled) { whyKey = null; game.hold() } }
        else { whyKey = reason; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
    }

    val tableDescription = if (s.dice.isEmpty()) stringResource(R.string.pig_dice_none) else stringResource(R.string.pig_dice_desc, s.dice.joinToString(", "))

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = theme.bg, buttonContent = theme.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedGames > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("pig-dice", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f

            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("pig-dice", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.players.indices.forEach { i ->
                            val c = pigSeatColors[i % 6]
                            val isTurn = i == s.currentPlayerIndex && !s.over
                            val nm = androidContext.seatLabel(seatName(i)) + if (isNetworked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
                            val desc = stringResource(R.string.pig_chip_desc, nm, s.scores[i], s.target) +
                                (if (isTurn && s.turnTotal > 0) " " + stringResource(R.string.pig_chip_pot, s.turnTotal) else "") +
                                if (s.players[i].isBot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
                            Column(
                                Modifier
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isTurn) c.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) c else theme.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = desc }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ClSeatBadge(c, i % 6, 20)
                                    Spacer(Modifier.width(5.dp))
                                    Text(nm, color = theme.text, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(6.dp))
                                    Text("${s.scores[i]}", color = theme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                }
                                ScoreBar(s.scores[i], s.target, c, theme)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClStatusLine(statusText, theme.text, Modifier.fillMaxWidth(), emphasized = true)
                if (hintText != null) ClStatusLine(hintText, theme.text, Modifier.fillMaxWidth())
                ClWhyLine(whyText, theme.text, theme.card)
            }

            val table: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    val count = shownDice.size.coerceAtLeast(s.variant.diceCount)
                    val perRow = if (count <= 2) count else if (count <= 6) 3 else 4
                    val rows = (count + perRow - 1) / perRow
                    // a lone die should fill the table, not float in it
                    val maxCell = when { count <= 1 -> 280.dp; count == 2 -> 210.dp; else -> 150.dp }
                    val cell: Dp = minOf(maxWidth / perRow, (maxHeight - 56.dp) / rows, maxCell).coerceAtLeast(52.dp)
                    val shakeX = if (shake.value > 0f) (sin(shake.value * 14f * PI.toFloat()) * (1f - shake.value) * 14f).dp else 0.dp
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset(x = shakeX)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Brush.radialGradient(listOf(theme.feltA, theme.feltB)))
                            .border(4.dp, theme.rim, RoundedCornerShape(24.dp))
                            .semantics { contentDescription = tableDescription },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (shownDice.isEmpty()) {
                                Text(stringResource(R.string.pig_dice_waiting), color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                            } else {
                                for (r in 0 until rows) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        for (k in 0 until perRow) {
                                            val idx = r * perRow + k
                                            if (idx >= shownDice.size) continue
                                            val t = tumble.value
                                            val hop = abs(sin(t * PI.toFloat() * 2.4f)) * (1f - t)
                                            val isBust = shownDice[idx] == 1 && (s.event == PigEvent.BUST || s.event == PigEvent.HOG_BUST) && t >= 1f
                                            Die3DView(
                                                value = shownDice[idx], spin = (1f - t) * (1f - t), axisSeed = s.rollSerial * 7 + idx,
                                                size = cell, body = if (idx % 2 == 0 || s.variant != PigVariant.BIG_PIG) Color(0xFFFBF6EA) else Color(0xFFE9F0F7),
                                                pip = Color(0xFF2A2118), description = stringResource(R.string.pig_die_desc, idx + 1, shownDice[idx]),
                                                hop = hop, bust = isBust
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            if (!s.over && !s.variant.singleRoll) {
                                Text(
                                    stringResource(R.string.pig_pot_line, s.turnTotal, s.scores[s.currentPlayerIndex] + s.turnTotal),
                                    color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                                )
                            }
                        }
                        Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                    }
                }
            }

            val pinned: @Composable () -> Unit = {
                if (s.over) {
                    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
                    val winnerSeat = s.players.indexOf(winner)
                    ClFinishedPanel(
                        title = winner?.let { youAware(it.displayName, R.string.pig_wins, R.string.yg_win) } ?: stringResource(R.string.cc_draw),
                        subtitle = if (isNetworked) stringResource(if (winnerSeat == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                        lines = s.players.indices.map { stringResource(R.string.pig_final_line, seatName(it), s.scores[it]) } +
                            (if (finishedGames > 1) listOf(
                                stringResource(R.string.ccl_session, s.players.joinToString(" · ") { "${it.displayName}: ${game.sessionWins.value[it.playerId] ?: 0}" })
                            ) else emptyList()),
                        canPlayAgain = !hostLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (s.variant.singleRoll) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { hogCount = (hogCount - 1).coerceAtLeast(1) },
                                    enabled = hogCount > 1,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = androidContext.getString(R.string.pig_fewer_dice) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                                Text(
                                    stringResource(R.string.pig_dice_count, hogCount), color = theme.text, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(88.dp), textAlign = TextAlign.Center
                                )
                                OutlinedButton(
                                    onClick = { hogCount = (hogCount + 1).coerceAtMost(PigRules.HOG_MAX_DICE) },
                                    enabled = hogCount < PigRules.HOG_MAX_DICE,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = androidContext.getString(R.string.pig_more_dice) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = ::tapRoll,
                                enabled = isMyTurn && settled,
                                modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                            ) { Text(stringResource(R.string.pig_roll), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                            if (!s.variant.singleRoll) {
                                Button(
                                    onClick = ::tapHold,
                                    enabled = isMyTurn && settled && s.turnTotal > 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = theme.rim, contentColor = Color.White),
                                    modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                                ) { Text(stringResource(R.string.pig_hold, s.turnTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
            }

            val secondary: @Composable () -> Unit = {
                if (!s.over && !isNetworked && isMyTurn) {
                    OutlinedButton(
                        onClick = { scope.launch { game.requestHint() } },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.ccl_hint)) }
                }
            }

            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.46f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            header(); secondary()
                        }
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { pinned() }
                    }
                    table(Modifier.weight(0.54f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    table(Modifier.weight(1f, fill = true).fillMaxWidth())
                    Column(Modifier.heightIn(max = 56.dp).verticalScroll(rememberScrollState())) { secondary() }
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { pinned() }
                }
            }
        }
    }
}

private fun afterRollFeedback(cur: PigState, playSfx: (SfxKind) -> Unit, haptics: (HapticSignal) -> Unit) {
    when (cur.event) {
        PigEvent.BUST, PigEvent.HOG_BUST -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
        PigEvent.SNAKE_EYES, PigEvent.DOUBLES, PigEvent.EXACT -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
        PigEvent.HOG -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.STRONG_ACTION) }
        PigEvent.WIN -> {}
        else -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
    }
}

@Composable
private fun ScoreBar(score: Int, target: Int, color: Color, theme: PigTheme) {
    Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
        drawRoundRect(theme.text.copy(alpha = 0.15f), Offset.Zero, size, CornerRadius(size.height / 2))
        val w = size.width * (score.toFloat() / target).coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, Offset.Zero, Size(w, size.height), CornerRadius(size.height / 2))
    }
}
