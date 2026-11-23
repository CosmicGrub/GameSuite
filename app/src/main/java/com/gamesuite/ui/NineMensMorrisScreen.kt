package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.gamesuite.games.morris.MorrisBoard
import com.gamesuite.games.morris.MorrisMen
import com.gamesuite.games.morris.MorrisMove
import com.gamesuite.games.morris.MorrisPhase
import com.gamesuite.games.morris.MorrisState
import com.gamesuite.games.morris.MorrisWhy
import com.gamesuite.games.morris.NineMensMorrisGame
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MORRIS_MIN_CELL_DP = 48f
private const val MORRIS_MAX_CELL_DP = 64f

/** Ease with a slight overshoot, shared by the Morris and Hare and Hounds boards. */
internal val PieceEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.18f)

// ---------------------------------------------------------------------------------------------
// Shared building blocks (also used by HareAndHoundsScreen)
// ---------------------------------------------------------------------------------------------

/** A confetti burst over the board for a win; a still sprinkle when reduced motion is on. */
@Composable
internal fun CelebrationBurst(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val reduced = LocalReducedMotion.current
    val speed = LocalGameSpeed.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) t.snapTo(1f) else t.animateTo(1f, tween(speed.scale(2200L).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    val colors = remember {
        listOf(Color(0xFFF2C230), Color(0xFFE5533D), Color(0xFF3FA7D6), Color(0xFF59C173), Color(0xFFB66DFF), Color(0xFFFFFFFF))
    }
    Canvas(modifier.fillMaxSize()) {
        val n = 36
        val origin = Offset(size.width / 2f, size.height * 0.45f)
        val p = t.value
        for (i in 0 until n) {
            val angle = i * 2.399963f
            val v = 0.35f + 0.65f * (((i * 37) % 17) / 16f)
            val dist = size.minDimension * 0.62f * v * (if (reduced) 1f else 1f - (1f - p) * (1f - p))
            val gravity = if (reduced) 0f else size.minDimension * 0.35f * p * p
            val pos = Offset(origin.x + cos(angle) * dist, origin.y + sin(angle) * dist * 0.8f + gravity)
            val alpha = if (reduced) 0.7f else (1f - p * p).coerceIn(0f, 1f)
            val s = size.minDimension * 0.016f * (0.8f + (i % 3) * 0.3f)
            rotate(degrees = (i * 47 + p * 360f), pivot = pos) {
                drawRect(colors[i % colors.size].copy(alpha = alpha), topLeft = Offset(pos.x - s, pos.y - s * 0.5f), size = Size(s * 2f, s))
            }
        }
    }
}

/** Three dots that pulse while the computer thinks (still when reduced motion is on). */
@Composable
internal fun ThinkingLine(text: String, color: Color) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) 1f else {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "thinkingAlpha").value
    }
    Text(text, color = color.copy(alpha = pulse), style = MaterialTheme.typography.bodySmall)
}

/** Undo and Hint under the board. */
@Composable
internal fun BoardControlsRow(
    canUndo: Boolean,
    canHint: Boolean,
    hintUnavailableReason: String,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    contentColor: Color
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
    val hintOn = stringResource(R.string.morris_hint)
    val hintOff = stringResource(R.string.morris_hint_unavailable, hintUnavailableReason)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onUndo, enabled = canUndo, colors = colors, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.morris_undo))
        }
        OutlinedButton(
            onClick = onHint,
            enabled = canHint,
            colors = colors,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (canHint) hintOn else hintOff }
        ) { Text(stringResource(R.string.morris_hint)) }
    }
}

/** The end-of-board card. [headline] is the result, [detail] the reason, [session] the running tally. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardFinishedPanel(
    headline: String,
    youLine: String?,
    detail: String,
    session: String?,
    canPlayAgain: Boolean,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit
) {
    Card(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (youLine != null) {
                Spacer(Modifier.height(2.dp))
                Text(youLine, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (session != null) {
                Spacer(Modifier.height(4.dp))
                Text(session, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPlayAgain, enabled = canPlayAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.morris_play_again))
                }
                OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.morris_back_to_menu))
                }
            }
        }
    }
}

/**
 * A dismissible first-run card: how to play in two sentences plus a bit of history. It remembers
 * that it was dismissed in a tiny preferences file; if storage is unavailable it simply shows
 * each time.
 */
@Composable
internal fun CoachCard(prefKey: String, body: String, trivia: String, textColor: Color) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(!coachSeen(context, prefKey)) }
    if (!visible) return
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.morris_coach_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(trivia, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    visible = false
                    markCoachSeen(context, prefKey)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.morris_coach_got_it)) }
        }
    }
}

private fun coachSeen(context: Context, key: String): Boolean = try {
    context.getSharedPreferences("gs_coach_cards", Context.MODE_PRIVATE).getBoolean(key, false)
} catch (_: Exception) {
    false
}

private fun markCoachSeen(context: Context, key: String) {
    try {
        context.getSharedPreferences("gs_coach_cards", Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
    } catch (_: Exception) {
    }
}

// ---------------------------------------------------------------------------------------------
// Palette and stone art
// ---------------------------------------------------------------------------------------------

private data class MorrisPalette(
    val background: Color,
    val text: Color,
    val woodLight: Color,
    val woodDark: Color,
    val groove: Color,
    val grooveLight: Color,
    val frame: Color,
    val accent: Color,
    val warn: Color,
    val gold: Color
)

@Composable
private fun morrisPalette(isDark: Boolean, colorblind: Boolean): MorrisPalette {
    val accent = if (colorblind) Color(0xFF1F77D0) else Color(0xFF1E9E6A)
    return if (!isDark) {
        MorrisPalette(
            background = Color(0xFFFBF1E6), text = Color(0xFF3A2E22),
            woodLight = Color(0xFFD9AE6E), woodDark = Color(0xFFB98547),
            groove = Color(0xFF4A2F14), grooveLight = Color(0x40FFFFFF), frame = Color(0xFF5A3A1B),
            accent = accent, warn = Color(0xFFD33F2F), gold = Color(0xFFF2C230)
        )
    } else {
        MorrisPalette(
            background = Color(0xFF1C1712), text = Color(0xFFF3E9DB),
            woodLight = Color(0xFFB88545), woodDark = Color(0xFF8A5D2A),
            groove = Color(0xFF2B1A0A), grooveLight = Color(0x30FFFFFF), frame = Color(0xFF3A2410),
            accent = if (colorblind) Color(0xFF5BA8F0) else Color(0xFF3DD598), warn = Color(0xFFFF6B5A), gold = Color(0xFFF5CE4E)
        )
    }
}

/** An original carved-stone look: a soft shadow, a lit radial body and an engraved shape cue (dot or ring). */
internal fun DrawScope.drawStone(center: Offset, radius: Float, kind: Int, alpha: Float = 1f) {
    // A pop-in animation starts at scale 0, and a radial gradient with radius 0 throws: nothing to draw yet.
    if (radius < 0.5f) return
    drawOval(
        Color.Black.copy(alpha = 0.32f * alpha),
        topLeft = Offset(center.x - radius * 0.95f, center.y + radius * 0.55f),
        size = Size(radius * 1.9f, radius * 0.7f)
    )
    val colors = if (kind == 0) {
        listOf(Color(0xFF6A6A74), Color(0xFF25252C), Color(0xFF0C0C10))
    } else {
        listOf(Color(0xFFFFFCF2), Color(0xFFE8DCC0), Color(0xFFB4A484))
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = colors.map { it.copy(alpha = alpha) },
            center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f),
            radius = radius * 1.5f
        ),
        radius = radius, center = center
    )
    drawCircle(
        (if (kind == 0) Color(0xFF000000) else Color(0xFF6E6046)).copy(alpha = 0.55f * alpha),
        radius = radius, center = center, style = Stroke(width = radius * 0.08f)
    )
    // Shape cue so the two sides never rely on tone alone: a dot on dark stones, a ring on light ones.
    if (kind == 0) {
        drawCircle(Color(0xFFB9B9C6).copy(alpha = 0.7f * alpha), radius = radius * 0.2f, center = center)
    } else {
        drawCircle(Color(0xFF7A6B50).copy(alpha = 0.7f * alpha), radius = radius * 0.5f, center = center, style = Stroke(width = radius * 0.11f))
    }
    drawOval(
        Color.White.copy(alpha = 0.32f * alpha),
        topLeft = Offset(center.x - radius * 0.62f, center.y - radius * 0.75f),
        size = Size(radius * 0.75f, radius * 0.36f)
    )
}

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NineMensMorrisScreen(
    sessionManager: GameSessionManager,
    game: NineMensMorrisGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CHECKERS, enabled = musicEnabled)
    val state by game.state
    val hintMove by game.hintMove
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colorblind = LocalColorblindMode.current
    val reduced = LocalReducedMotion.current
    val palette = morrisPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f, colorblind)
    val gameSpeed = LocalGameSpeed.current

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.men = MorrisMen.fromOptionId(sessionManager.launchOptions.extras["men"])
        game.flying = sessionManager.launchOptions.extras["flying"] != "off"
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
    val opponentLeft by game.opponentLeft

    var botThinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, state?.currentPlayerIndex, state?.boardOver, state?.ply) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            botThinking = true
            try {
                // Long enough for the previous move's animation to land and for the CPU to feel like it is thinking.
                val thinkMs = gameSpeed.scale(750L)
                if (thinkMs > 60L) delay(thinkMs)
                game.playBotTurnAsync()
            } finally {
                botThinking = false
            }
        }
    }

    val s = state
    val helpTitle = stringResource(R.string.morris_help_title)
    val helpText = stringResource(R.string.morris_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = palette.background, buttonContent = palette.text
            ) {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.morris_waiting_host), color = palette.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val isMyTurn = if (isNetworked) s.currentPlayerIndex == localSeat && !opponentLeft else !s.players[s.currentPlayerIndex].isBot
    val isPassAndPlay = !isNetworked && s.players.none { it.isBot }
    val opponentSeat = if (localSeat == 0) 1 else 0
    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value

    // Half-made turn: the man picked up, and a move waiting on its removal pick.
    var selected by remember { mutableIntStateOf(-1) }
    var pending by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var whyMessage by remember { mutableStateOf<MorrisWhy?>(null) }
    LaunchedEffect(s.ply, s.currentPlayerIndex, s.boardOver) {
        selected = -1
        pending = null
        whyMessage = null
    }

    // Who the result favours on this device (null in pass and play, where there is no "you").
    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
    val humanSeat = if (isNetworked) localSeat else s.players.indexOfFirst { !it.isBot }
    val localWon: Boolean? = when {
        !s.boardOver || winnerSeat < 0 -> null
        isPassAndPlay -> true
        else -> winnerSeat == humanSeat
    }

    // Sound and haptic cues for every move (both sides), and the end of the board.
    LaunchedEffect(s.ply) {
        val m = s.lastMove ?: return@LaunchedEffect
        if (s.ply == 0) return@LaunchedEffect
        when {
            m.remove >= 0 -> {
                playSfx(SfxKind.WHOOSH); haptics(HapticSignal.STRONG_ACTION)
            }
            else -> {
                playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION)
            }
        }
    }
    LaunchedEffect(s.boardOver) {
        if (!s.boardOver) return@LaunchedEffect
        delay(gameSpeed.scale(450L))
        when (localWon) {
            false -> {
                playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
            }
            else -> {
                if (winnerSeat >= 0) {
                    playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                } else haptics(HapticSignal.NORMAL_ACTION)
            }
        }
    }

    val ringNames = listOf(
        stringResource(R.string.morris_ring_outer), stringResource(R.string.morris_ring_middle), stringResource(R.string.morris_ring_inner)
    )
    val slotNames = listOf(
        stringResource(R.string.morris_pos_top_left), stringResource(R.string.morris_pos_top), stringResource(R.string.morris_pos_top_right),
        stringResource(R.string.morris_pos_right), stringResource(R.string.morris_pos_bottom_right), stringResource(R.string.morris_pos_bottom),
        stringResource(R.string.morris_pos_bottom_left), stringResource(R.string.morris_pos_left)
    )
    fun pointName(i: Int) = "${ringNames[MorrisBoard.ringOf(i)]}, ${slotNames[MorrisBoard.slotOf(i)]}"
    val darkName = stringResource(R.string.morris_side_dark)
    val lightName = stringResource(R.string.morris_side_light)
    fun sideName(seat: Int) = if (seat == 0) darkName else lightName

    // Localised "what just happened" line, also announced to TalkBack (live region on the status).
    val lastText: String = run {
        val m = s.lastMove
        val mover = s.players.getOrNull(s.lastMover)?.displayName ?: ""
        when {
            s.boardOver && winnerSeat >= 0 -> youAware(s.players[winnerSeat].displayName, R.string.morris_ann_win, R.string.yg_win)
            s.boardOver -> stringResource(R.string.morris_ann_draw)
            m == null -> stringResource(R.string.morris_ann_start)
            m.from < 0 && m.remove < 0 -> stringResource(R.string.morris_ann_place, mover, pointName(m.to))
            m.from < 0 -> stringResource(R.string.morris_ann_place_mill, mover, pointName(m.to), pointName(m.remove))
            m.remove < 0 -> stringResource(R.string.morris_ann_move, mover, pointName(m.from), pointName(m.to))
            else -> stringResource(R.string.morris_ann_move_mill, mover, pointName(m.from), pointName(m.to), pointName(m.remove))
        }
    }
    val phaseText: String = when {
        s.boardOver -> ""
        pending != null -> stringResource(R.string.morris_phase_remove)
        s.phase == MorrisPhase.PLACING -> stringResource(R.string.morris_phase_place, s.hand[s.currentPlayerIndex])
        s.phase == MorrisPhase.FLYING -> stringResource(R.string.morris_phase_fly)
        else -> stringResource(R.string.morris_phase_move)
    }

    val whyStrings = mapOf(
        MorrisWhy.GAME_OVER to stringResource(R.string.morris_why_over),
        MorrisWhy.NOT_YOUR_TURN to stringResource(R.string.morris_why_not_turn),
        MorrisWhy.CPU_THINKING to stringResource(R.string.morris_why_cpu),
        MorrisWhy.POINT_TAKEN to stringResource(R.string.morris_why_taken),
        MorrisWhy.ENEMY_MAN to stringResource(R.string.morris_why_enemy),
        MorrisWhy.BLOCKED_MAN to stringResource(R.string.morris_why_blocked),
        MorrisWhy.TOO_FAR to stringResource(R.string.morris_why_far),
        MorrisWhy.PICK_OWN_MAN to stringResource(R.string.morris_why_pick_own),
        MorrisWhy.REMOVE_OWN_MAN to stringResource(R.string.morris_why_remove_own),
        MorrisWhy.PROTECTED_MAN to stringResource(R.string.morris_why_protected),
        MorrisWhy.NOTHING_THERE to stringResource(R.string.morris_why_nothing)
    )

    fun reject(why: MorrisWhy) {
        whyMessage = why
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
    }

    fun commitStep(from: Int, to: Int) {
        val removals = s.removals(from, to)
        if (removals.isEmpty()) {
            s.move(from, to)?.let { game.play(it) }
            selected = -1
        } else {
            pending = from to to
            whyMessage = null
            haptics(HapticSignal.ESCALATING)
        }
    }

    fun onTap(i: Int) {
        whyMessage = null
        val p = pending
        if (p != null) {
            if (i == p.second) { // tapping the destination again takes the move back
                pending = null
                return
            }
            val why = s.whyNotRemovable(i, p.first, p.second)
            if (why != null) reject(why) else s.move(p.first, p.second, i)?.let {
                pending = null
                selected = -1
                game.play(it)
            }
            return
        }
        val botTurn = !isNetworked && s.players[s.currentPlayerIndex].isBot
        val why = s.whyIllegal(i, selected, isMyTurn, botTurn)
        if (why != null) {
            reject(why)
            return
        }
        sounds.playTap()
        if (s.phase == MorrisPhase.PLACING) {
            commitStep(-1, i)
        } else if (s.points[i] == s.currentPlayerIndex) {
            haptics(HapticSignal.LIGHT_TICK)
            selected = if (selected == i) -1 else i
        } else if (selected >= 0) {
            commitStep(selected, i)
        }
    }

    GameChrome(
        helpTitle = helpTitle,
        helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = palette.background,
        buttonContent = palette.text,
        leaveTitle = stringResource(R.string.morris_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.morris_leave_body_session else R.string.morris_leave_body)
    ) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight * 1.15f
                val infoTop: @Composable ColumnScope.() -> Unit = {
                FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    for (seat in 0..1) {
                        val turn = s.currentPlayerIndex == seat && !s.boardOver
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (turn) palette.gold.copy(alpha = 0.25f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(22.dp)) { drawStone(center, size.minDimension * 0.42f, seat) }
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(s.players[seat].displayName, color = palette.text, fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal)
                                Text(
                                    stringResource(R.string.morris_counts, s.hand[seat], s.onBoard[seat]),
                                    color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                val sessionWins by game.sessionWins
                val sessionDraws by game.sessionDraws
                val sessionLine = stringResource(R.string.morris_session, s.players.joinToString(" · ") { "${it.displayName}: ${sessionWins[it.playerId] ?: 0}" }) +
                    if (sessionDraws > 0) stringResource(R.string.morris_session_draws, sessionDraws) else ""
                Text(sessionLine, color = palette.text.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)

                CoachCard(
                    prefKey = "nine_mens_morris",
                    body = stringResource(R.string.morris_coach_body),
                    trivia = stringResource(R.string.morris_coach_trivia),
                    textColor = palette.text
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    if (isPassAndPlay && !s.boardOver) {
                        Text(
                            stringResource(R.string.morris_turn_pass, s.players[s.currentPlayerIndex].displayName, sideName(s.currentPlayerIndex)),
                            color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (isNetworked) {
                        val oppName = s.players.getOrNull(opponentSeat)?.displayName ?: ""
                        Text(
                            stringResource(R.string.morris_vs_you, oppName, sideName(localSeat)),
                            color = palette.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium
                        )
                        if (opponentLeft) {
                            Text(stringResource(R.string.morris_opponent_left), color = palette.text, style = MaterialTheme.typography.bodyMedium)
                        } else if (!s.boardOver) {
                            Text(
                                if (isMyTurn) stringResource(R.string.morris_your_turn) else stringResource(R.string.morris_waiting_for, oppName),
                                color = palette.text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(lastText, color = palette.text.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    if (phaseText.isNotEmpty() && (isMyTurn || isPassAndPlay)) {
                        Text(phaseText, color = palette.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                    whyMessage?.let {
                        Text(whyStrings.getValue(it), color = palette.warn, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (botThinking && !s.boardOver) ThinkingLine(stringResource(R.string.morris_thinking), palette.text)
                }

                }
                val boardBlock: @Composable (Modifier) -> Unit = { boardModifier ->
                BoxWithConstraints(modifier = boardModifier, contentAlignment = Alignment.Center) {
                    val fit = remember(maxWidth, maxHeight) {
                        fitBoard(
                            availableWidthPx = maxWidth.value, availableHeightPx = maxHeight.value,
                            columns = 7, rows = 7, minCellPx = MORRIS_MIN_CELL_DP, maxCellPx = MORRIS_MAX_CELL_DP
                        )
                    }
                    val scrolls = !fit.meetsMinimum
                    val cell = if (scrolls) MORRIS_MIN_CELL_DP.dp else fit.cellPx.dp
                    Box(modifier = if (scrolls) Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()) else Modifier) {
                        MorrisBoardView(
                            s = s, cell = cell, palette = palette, reduced = reduced, speedScale = { gameSpeed.scale(it) },
                            selected = selected, pending = pending, hint = hintMove, interactive = isMyTurn || isPassAndPlay,
                            describe = { i, flags -> describePoint(i, s, ::pointName, flags, sideName(s.points[i] ?: 0)) },
                            onTap = ::onTap
                        )
                    }
                }

                }
                val controls: @Composable ColumnScope.() -> Unit = {
                if (!isNetworked) {
                    Spacer(Modifier.height(8.dp))
                    BoardControlsRow(
                        canUndo = game.canUndo,
                        canHint = isMyTurn && !s.boardOver,
                        hintUnavailableReason = stringResource(if (s.boardOver) R.string.morris_reason_over else R.string.morris_reason_cpu),
                        onUndo = { game.undo() },
                        onHint = { scope.launch { game.requestHint() } },
                        contentColor = palette.text
                    )
                }

                if (s.boardOver) {
                    Spacer(Modifier.height(12.dp))
                    val wName = s.players.getOrNull(winnerSeat)?.displayName
                    val headline = if (wName != null) youAware(wName, R.string.morris_wins, R.string.yg_win) else stringResource(R.string.morris_tie)
                    val loserName = s.players.getOrNull(if (winnerSeat >= 0) 1 - winnerSeat else 0)?.displayName ?: ""
                    val detail = when (s.endReason) {
                        "reduced" -> youAware(loserName, R.string.morris_end_reduced, R.string.yg_morris_end_reduced)
                        "blocked" -> stringResource(R.string.morris_end_blocked, loserName)
                        else -> stringResource(R.string.morris_end_drawn)
                    }
                    val youLine = when {
                        isPassAndPlay -> null
                        winnerSeat < 0 -> stringResource(R.string.morris_a_draw)
                        localWon == true -> stringResource(R.string.morris_you_won)
                        else -> stringResource(R.string.morris_you_lost)
                    }
                    val sw by game.sessionWins
                    val sd by game.sessionDraws
                    val session = if (sw.values.sum() + sd > 1) {
                        stringResource(R.string.morris_session, s.players.joinToString(" · ") { "${it.displayName}: ${sw[it.playerId] ?: 0}" }) +
                            if (sd > 0) stringResource(R.string.morris_session_draws, sd) else ""
                    } else null
                    BoardFinishedPanel(
                        headline = headline, youLine = youLine, detail = detail, session = session,
                        canPlayAgain = !opponentLeft, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() }
                    )
                }
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        boardBlock(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) { infoTop(); Spacer(Modifier.height(8.dp)); controls() }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        infoTop()
                        Spacer(Modifier.height(8.dp))
                        boardBlock(Modifier.weight(1f, fill = true).fillMaxWidth())
                        controls()
                    }
                }
            }
            // Win: confetti. Loss: the board room dims slightly (a quiet "not this time").
            if (s.boardOver && localWon == true) CelebrationBurst(active = true)
            if (s.boardOver && localWon == false) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
        }
    }
}

/** Flags describing one point for the screen reader. */
private data class PointFlags(
    val selected: Boolean, val canMove: Boolean, val destination: Boolean, val removable: Boolean, val hint: Boolean
)

@Composable
private fun describePoint(i: Int, s: MorrisState, name: (Int) -> String, f: PointFlags, ownerName: String): String {
    val owner = s.points[i]
    val base = when (owner) {
        null -> stringResource(R.string.morris_cell_empty)
        else -> stringResource(R.string.morris_cell_man, ownerName)
    }
    val extras = buildList {
        if (f.selected) add(stringResource(R.string.morris_cell_selected))
        if (f.canMove) add(stringResource(R.string.morris_cell_can_move))
        if (f.destination) add(stringResource(R.string.morris_cell_destination))
        if (f.removable) add(stringResource(R.string.morris_cell_removable))
        if (f.hint) add(stringResource(R.string.morris_cell_hint))
    }
    return (listOf(base, name(i)) + extras).joinToString(", ")
}

@Composable
private fun MorrisBoardView(
    s: MorrisState,
    cell: Dp,
    palette: MorrisPalette,
    reduced: Boolean,
    speedScale: (Long) -> Long,
    selected: Int,
    pending: Pair<Int, Int>?,
    hint: MorrisMove?,
    interactive: Boolean,
    describe: @Composable (Int, PointFlags) -> String,
    onTap: (Int) -> Unit
) {
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) { cell.toPx() }
    fun centerOf(i: Int): Offset {
        val (x, y) = MorrisBoard.COORDS[i]
        return Offset((x + 0.5f) * cellPx, (y + 0.5f) * cellPx)
    }

    // One animation clock per move: the stone travels (or drops), then a captured man shrinks away.
    val t = remember { Animatable(1f) }
    var seenPly by remember { mutableIntStateOf(s.ply) }
    LaunchedEffect(s.ply) {
        if (s.ply > seenPly && s.lastMove != null && !reduced) {
            seenPly = s.ply
            t.snapTo(0f)
            t.animateTo(1f, tween(speedScale(520L).toInt().coerceAtLeast(1), easing = LinearEasing))
        } else {
            seenPly = s.ply
            t.snapTo(1f)
        }
    }
    // A State, read only inside the Canvas draw lambdas below so the idle pulse does not recompose the board.
    val pulse: State<Float> = if (reduced) remember { mutableStateOf(0f) } else {
        val transition = rememberInfiniteTransition(label = "morrisPulse")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "morrisPulseValue")
    }

    val lm = s.lastMove
    val animating = t.value < 1f && lm != null
    val moveT = if (animating) PieceEase.transform((t.value / 0.6f).coerceIn(0f, 1f)) else 1f
    val capT = if (animating) ((t.value - 0.5f) / 0.5f).coerceIn(0f, 1f) else 1f

    // Mills closed by the last move glow while the move animates.
    val millPoints: Set<Int> = remember(s.ply, s.lastMove) {
        if (lm == null || lm.remove < 0) emptySet() else {
            MorrisBoard.MILLS_OF[lm.to].filter { m -> m.all { s.points[it] == s.lastMover } }.flatMap { it.toList() }.toSet()
        }
    }

    val movable = if (interactive && pending == null) s.movablePoints() else emptySet()
    val dests: Set<Int> = when {
        !interactive || s.boardOver -> emptySet()
        pending != null -> emptySet()
        s.phase == MorrisPhase.PLACING -> s.destinations(-1)
        selected >= 0 -> s.destinations(selected)
        else -> emptySet()
    }
    val removable: Set<Int> = pending?.let { s.removals(it.first, it.second).toSet() } ?: emptySet()

    Box(
        modifier = Modifier
            .size(cell * 7)
            .clip(RoundedCornerShape(14.dp))
            .border(3.dp, palette.frame, RoundedCornerShape(14.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Wood plank with soft grain.
            drawRect(Brush.linearGradient(listOf(palette.woodLight, palette.woodDark, palette.woodLight), Offset.Zero, Offset(size.width, size.height)))
            for (g in 0 until 16) {
                val y = size.height * (g + 0.5f) / 16f
                val wob = sin(g * 1.7f) * cellPx * 0.06f
                drawLine(Color.Black.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y + wob), strokeWidth = cellPx * 0.03f)
            }
            val w = cellPx * 0.075f
            fun line(a: Int, b: Int) {
                val pa = centerOf(a)
                val pb = centerOf(b)
                drawLine(palette.grooveLight, pa + Offset(0f, w * 0.6f), pb + Offset(0f, w * 0.6f), strokeWidth = w * 0.8f, cap = StrokeCap.Round)
                drawLine(palette.groove, pa, pb, strokeWidth = w, cap = StrokeCap.Round)
            }
            for (r in 0..2) for (k in 0..7) line(r * 8 + k, r * 8 + (k + 1) % 8)
            for (r in 0..1) for (k in intArrayOf(1, 3, 5, 7)) line(r * 8 + k, (r + 1) * 8 + k)
            for (i in 0 until MorrisBoard.POINTS) {
                val c = centerOf(i)
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), palette.groove), c, cellPx * 0.14f), cellPx * 0.12f, c)
            }
        }
        for (i in 0 until MorrisBoard.POINTS) {
            val (gx, gy) = MorrisBoard.COORDS[i]
            val owner = s.points[i]
            val hideStone = animating && lm != null && lm.to == i
            val ghost: Int? = if (pending != null && pending.second == i) s.currentPlayerIndex else null
            val flags = PointFlags(
                selected = selected == i && pending == null,
                canMove = i in movable && s.phase != MorrisPhase.PLACING,
                destination = i in dests && owner == null,
                removable = i in removable,
                hint = hint != null && (i == hint.to || i == hint.from) && interactive
            )
            val desc = describe(i, flags)
            val isPendingFrom = pending != null && pending.first == i
            Box(
                modifier = Modifier
                    .offset(cell * gx, cell * gy)
                    .size(cell)
                    .clickable(role = Role.Button, onClick = { onTap(i) })
                    .semantics { contentDescription = desc }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val r = size.minDimension * 0.36f
                    if (owner != null && !hideStone && !isPendingFrom) {
                        val breathe = if (flags.canMove && owner == s.currentPlayerIndex) 1f + 0.05f * pulse.value else 1f
                        drawStone(c, r * breathe, owner)
                    }
                    if (ghost != null) drawStone(c, r, ghost, alpha = 0.6f)
                    if (flags.selected) {
                        drawCircle(palette.gold, r * 1.25f, c, style = Stroke(width = r * 0.18f))
                    }
                    if (flags.destination) {
                        drawCircle(palette.accent.copy(alpha = 0.85f), r * (0.3f + 0.06f * pulse.value), c)
                        drawCircle(palette.accent.copy(alpha = 0.45f), r * (0.62f + 0.1f * pulse.value), c, style = Stroke(width = r * 0.1f))
                    }
                    if (flags.removable) {
                        drawCircle(palette.warn, r * 1.2f, c, style = Stroke(width = r * 0.2f))
                        val d = r * 0.55f
                        drawLine(palette.warn, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
                        drawLine(palette.warn, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
                    }
                    if (flags.hint) {
                        drawCircle(palette.gold, r * 1.35f, c, style = Stroke(width = r * 0.12f))
                        drawCircle(palette.text, r * 1.1f, c, style = Stroke(width = r * 0.06f))
                    }
                    if (i in millPoints) {
                        val a = if (animating) sin(t.value * PI.toFloat()) else 0f
                        drawCircle(palette.gold.copy(alpha = 0.9f * a), r * (1.2f + 0.3f * a), c, style = Stroke(width = r * 0.25f))
                    }
                }
            }
        }
        // Flying overlay: the moving stone and the captured man leaving.
        if (animating && lm != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = cellPx * 0.36f
                val to = centerOf(lm.to)
                val mover = s.lastMover.coerceIn(0, 1)
                if (lm.from >= 0) {
                    val from = centerOf(lm.from)
                    val pos = Offset(from.x + (to.x - from.x) * moveT, from.y + (to.y - from.y) * moveT)
                    val lift = sin(moveT.coerceIn(0f, 1f) * PI.toFloat()) * r * 0.35f
                    drawStone(Offset(pos.x, pos.y - lift), r * (1f + 0.12f * sin(moveT.coerceIn(0f, 1f) * PI.toFloat())), mover)
                } else {
                    val scale = moveT.coerceAtLeast(0f)
                    drawStone(Offset(to.x, to.y), r * scale, mover)
                    drawCircle(palette.gold.copy(alpha = (1f - moveT.coerceIn(0f, 1f)) * 0.6f), r * (0.8f + moveT.coerceIn(0f, 1f) * 1.2f), to, style = Stroke(width = r * 0.12f))
                }
                if (lm.remove >= 0) {
                    val at = centerOf(lm.remove)
                    drawStone(Offset(at.x, at.y - r * capT * 0.6f), r * (1f - 0.55f * capT), 1 - mover, alpha = 1f - capT)
                }
            }
        }
    }
}

