package com.gamesuite.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import com.gamesuite.games.shogi.ShogiEnd
import com.gamesuite.games.shogi.ShogiGame
import com.gamesuite.games.shogi.ShogiMove
import com.gamesuite.games.shogi.ShogiPiece
import com.gamesuite.games.shogi.ShogiState
import com.gamesuite.games.shogi.ShogiVariant
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val SHOGI_MIN_CELL_DP = 30f
private const val SHOGI_MAX_CELL_DP = 64f

private sealed class Sel {
    data class Square(val sq: Int) : Sel()
    data class Hand(val type: Int) : Sel()
}

@StringRes
private fun pieceNameRes(type: Int): Int = when (type) {
    ShogiPiece.PAWN -> R.string.shogi_piece_pawn
    ShogiPiece.LANCE -> R.string.shogi_piece_lance
    ShogiPiece.KNIGHT -> R.string.shogi_piece_knight
    ShogiPiece.SILVER -> R.string.shogi_piece_silver
    ShogiPiece.GOLD -> R.string.shogi_piece_gold
    ShogiPiece.BISHOP -> R.string.shogi_piece_bishop
    ShogiPiece.ROOK -> R.string.shogi_piece_rook
    ShogiPiece.KING -> R.string.shogi_piece_king
    9 -> R.string.shogi_piece_tokin
    10 -> R.string.shogi_piece_pro_lance
    11 -> R.string.shogi_piece_pro_knight
    12 -> R.string.shogi_piece_pro_silver
    ShogiPiece.HORSE -> R.string.shogi_piece_horse
    else -> R.string.shogi_piece_dragon
}

@StringRes
private fun whyRes(reason: String): Int = when (reason) {
    "notyours" -> R.string.shogi_why_notyours
    "incheck" -> R.string.shogi_why_incheck
    "exposes" -> R.string.shogi_why_exposes
    "occupied" -> R.string.shogi_why_occupied
    "nifu" -> R.string.shogi_why_nifu
    "dead" -> R.string.shogi_why_dead
    "uchifuzume" -> R.string.shogi_why_uchifuzume
    "check" -> R.string.shogi_why_drop_check
    "nohand" -> R.string.shogi_why_nohand
    else -> R.string.shogi_why_cannot
}

private fun squareName(ctx: Context, s: ShogiState, sq: Int, viewSide: Int): String {
    val n = s.n
    val flipped = viewSide == 1
    val r = if (flipped) n - 1 - sq / n else sq / n
    val c = if (flipped) n - 1 - sq % n else sq % n
    return ctx.getString(R.string.shogi_square, r + 1, c + 1)
}

private fun sideName(ctx: Context, side: Int): String =
    ctx.getString(if (side == 0) R.string.shogi_sente else R.string.shogi_gote)

private fun prefs(ctx: Context) = ctx.getSharedPreferences("shogi_prefs", Context.MODE_PRIVATE)

private fun loadStyle(ctx: Context, hubChoice: String?): ShogiPieceStyle = try {
    when (prefs(ctx).getString("piece_style", null) ?: hubChoice) {
        "symbols" -> ShogiPieceStyle.SYMBOLS
        else -> ShogiPieceStyle.KANJI
    }
} catch (_: Exception) {
    ShogiPieceStyle.KANJI
}

/**
 * Shogi / Mini Shogi screen. One screen serves both variants (the [game] says which). Wooden vector-drawn board
 * and pieces, tap-to-move with legal-move dots and capture brackets, drops from the hand trays, a promote /
 * don't-promote prompt, spoken "why not" messages, animated moves with captured pieces drifting to the
 * capturer's hand, sound + haptics per event, a win celebration, TalkBack announcements for every move, and a
 * "Kanji / Symbols" piece-style toggle (Symbols draws movement arrows so nobody has to read kanji).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShogiScreen(
    sessionManager: GameSessionManager,
    game: ShogiGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val ctx = LocalContext.current
    val sounds = remember { CardSounds.get(ctx) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CHESS, enabled = musicEnabled)
    val state by game.state
    val hintMove by game.hintMove
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = shogiPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val mini = game.variant == ShogiVariant.MINI

    var pieceStyle by remember { mutableStateOf(ShogiPieceStyle.KANJI) }
    var coachSeen by remember { mutableStateOf(true) }

    LaunchedEffect(context) {
        val c = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        pieceStyle = loadStyle(ctx, sessionManager.launchOptions.extras["pieces"])
        coachSeen = try { prefs(ctx).getBoolean("coach_seen_${game.gameId}", false) } catch (_: Exception) { true }
        game.init(c)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = c.activeMode == PlayMode.LOCAL_AD_HOC || c.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0
    val opponentLeft by game.opponentLeft

    var botThinking by remember { mutableStateOf(false) }
    LaunchedEffect(state, state?.sideToMove, state?.gameOver) {
        val s = state ?: return@LaunchedEffect
        if (s.gameOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            botThinking = true
            try {
                // At least ~400 ms so the CPU's move never feels instantaneous, even at Quick speed.
                delay(gameSpeed.scale(700L).coerceAtLeast(400L))
                game.playBotTurnAsync()
            } finally {
                botThinking = false
            }
        }
    }

    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = stringResource(if (mini) R.string.shogi_mini_help_title else R.string.shogi_help_title),
                helpText = stringResource(R.string.shogi_waiting_host),
                matchInProgress = true,
                onLeave = game::abortMatch,
                onAbort = { game.abortMatch() },
                leaveKeepsProgress = false,
                buttonFill = palette.background,
                buttonContent = palette.text
            ) {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.shogi_waiting_host), color = palette.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val n = s.n
    val humanSeat = s.players.indexOfFirst { !it.isBot }.let { if (it < 0) 0 else it }
    val viewSide = when {
        isNetworked -> s.sideOfSeat(localSeat)
        s.players.any { it.isBot } -> s.sideOfSeat(humanSeat)
        else -> 0
    }
    val isMyTurn = if (isNetworked) s.currentPlayerIndex == localSeat && !opponentLeft
    else !s.players[s.currentPlayerIndex].isBot
    val isPassAndPlay = !isNetworked && s.players.none { it.isBot }
    val interactive = isMyTurn && !s.gameOver
    val ply = s.ply

    var sel by remember(ply) { mutableStateOf<Sel?>(null) }
    var pending by remember(ply) { mutableStateOf<Pair<Int, Int>?>(null) }
    var message by remember(ply) { mutableStateOf<String?>(null) }

    // ---- move feedback: flight animation, sound, haptics ----
    val flight = remember { Animatable(1f) }
    var lastSeenPly by remember { mutableIntStateOf(-1) }
    LaunchedEffect(ply, s.gameOver) {
        val previous = lastSeenPly
        lastSeenPly = ply
        if (previous < 0 || ply <= previous || s.lastTo < 0) {
            flight.snapTo(1f)
            return@LaunchedEffect
        }
        val capture = s.lastCaptured != 0
        if (s.inCheck && !s.gameOver) {
            haptics(HapticSignal.ESCALATING)
            playSfx(SfxKind.WHOOSH)
        } else if (capture) {
            haptics(HapticSignal.STRONG_ACTION)
            playSfx(SfxKind.SOLID_THUNK)
        } else {
            haptics(HapticSignal.NORMAL_ACTION)
            sounds.playPlace()
        }
        if (reduced) {
            flight.snapTo(1f)
        } else {
            flight.snapTo(0f)
            flight.animateTo(1f, tween(gameSpeed.scale(280L).toInt().coerceAtLeast(120)) { shogiOvershoot(it) })
        }
    }
    LaunchedEffect(s.gameOver) {
        if (!s.gameOver) return@LaunchedEffect
        val iWon = s.winnerPlayerId != null && (isPassAndPlay || s.players.getOrNull(if (isNetworked) localSeat else humanSeat)?.playerId == s.winnerPlayerId)
        if (iWon) {
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        } else if (s.winnerPlayerId != null) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        } else {
            haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "shogi-pulse")
    val pulseAnim by pulseTransition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    val pulse = if (reduced) 1f else pulseAnim

    // ---- derived highlights ----
    val legal = s.legalMoves
    val targets: Set<Int>
    val captureTargets: Set<Int>
    when (val sl = sel) {
        is Sel.Square -> {
            val m = legal.filter { ShogiMove.from(it) == sl.sq }.map { ShogiMove.to(it) }.toSet()
            targets = m
            captureTargets = m.filter { s.cells[it] != 0 }.toSet()
        }
        is Sel.Hand -> {
            targets = legal.filter { ShogiMove.from(it) == n * n + sl.type }.map { ShogiMove.to(it) }.toSet()
            captureTargets = emptySet()
        }
        null -> {
            targets = emptySet()
            captureTargets = emptySet()
        }
    }
    val kingSq = (0 until n * n).firstOrNull { s.cells[it] == ShogiPiece.code(s.sideToMove, ShogiPiece.KING) } ?: -1
    val highlights = ShogiHighlights(
        selected = (sel as? Sel.Square)?.sq ?: -1,
        targets = targets,
        captureTargets = captureTargets,
        hintFrom = if (interactive && hintMove != null && ShogiMove.from(hintMove!!) < n * n) ShogiMove.from(hintMove!!) else -1,
        hintTo = if (interactive && hintMove != null) ShogiMove.to(hintMove!!) else -1,
        checkSquare = if (s.inCheck && !s.gameOver) kingSq else -1,
        pulse = pulse
    )

    // ---- strings for the live announcement ----
    val announcement = remember(ply, s.gameOver, s.endReason, viewSide, s.inCheck) {
        buildAnnouncement(ctx, s, viewSide)
    }
    val turnText = when {
        s.gameOver -> ""
        isNetworked && opponentLeft -> stringResource(R.string.shogi_opponent_left)
        isNetworked -> if (isMyTurn) stringResource(R.string.shogi_your_turn)
        else stringResource(R.string.shogi_waiting_for, s.players[s.currentPlayerIndex].displayName)
        else -> stringResource(R.string.shogi_turn, s.players[s.currentPlayerIndex].displayName, sideName(ctx, s.sideToMove))
    }

    fun deny(text: String) {
        message = text
        haptics(HapticSignal.FAILURE)
        playSfx(SfxKind.INVALID_BUZZ)
    }

    fun tapCell(sq: Int) {
        pending = null
        val code = s.cells[sq]
        val current = sel
        val mine = code != 0 && ShogiPiece.side(code) == s.sideToMove
        when (current) {
            is Sel.Square -> {
                val moves = legal.filter { ShogiMove.from(it) == current.sq && ShogiMove.to(it) == sq }
                if (moves.isNotEmpty()) {
                    if (moves.size == 2) {
                        pending = current.sq to sq
                    } else {
                        game.playMove(moves[0])
                    }
                    return
                }
                if (mine) {
                    sel = if (sq == current.sq) null else Sel.Square(sq)
                    if (sq != current.sq) { haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.LIGHT_TICK) }
                    return
                }
                deny(ctx.getString(whyRes(game.whyIllegal(current.sq, sq) ?: "cannot")))
            }
            is Sel.Hand -> {
                val move = legal.firstOrNull { ShogiMove.from(it) == n * n + current.type && ShogiMove.to(it) == sq }
                if (move != null) {
                    game.playMove(move)
                    return
                }
                if (mine) {
                    sel = Sel.Square(sq)
                    haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.LIGHT_TICK)
                    return
                }
                deny(ctx.getString(whyRes(game.whyIllegal(-1, sq, current.type) ?: "cannot")))
            }
            null -> {
                if (mine) {
                    if (legal.none { ShogiMove.from(it) == sq }) {
                        deny(ctx.getString(R.string.shogi_why_nomoves))
                    } else {
                        sel = Sel.Square(sq)
                        message = null
                        haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.LIGHT_TICK)
                    }
                } else if (code != 0) {
                    deny(ctx.getString(R.string.shogi_why_notyours))
                }
            }
        }
    }

    fun tapHand(type: Int) {
        pending = null
        if (legal.none { ShogiMove.from(it) == n * n + type }) {
            deny(ctx.getString(R.string.shogi_why_nodrop))
            return
        }
        sel = if ((sel as? Sel.Hand)?.type == type) null else Sel.Hand(type)
        message = null
        haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.LIGHT_TICK)
    }

    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value
    val helpTitle = stringResource(if (mini) R.string.shogi_mini_help_title else R.string.shogi_help_title)
    val helpText = stringResource(if (mini) R.string.shogi_mini_help_text else R.string.shogi_help_text)
    val styleLabel = stringResource(if (pieceStyle == ShogiPieceStyle.KANJI) R.string.shogi_style_kanji else R.string.shogi_style_symbols)

    GameChrome(
        helpTitle = helpTitle,
        helpText = helpText,
        matchInProgress = !s.gameOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ scope.launch { game.requestHint() } }),
        buttonFill = palette.background,
        buttonContent = palette.text,
        leaveTitle = stringResource(R.string.shogi_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.shogi_leave_body_keeps else R.string.shogi_leave_body)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
            val wide = maxWidth > maxHeight * 1.25f
            val pad = 12.dp
            val cellDp: Dp
            val scrolls: Boolean
            run {
                val availW = if (wide) maxWidth.value * 0.58f - 2 * pad.value else maxWidth.value - 2 * pad.value
                val availH = if (wide) maxHeight.value - 2 * pad.value else maxHeight.value - 2 * pad.value - 300f
                val fit = fitBoard(availW, availH.coerceAtLeast(200f), n, n, 0f, SHOGI_MIN_CELL_DP, SHOGI_MAX_CELL_DP)
                scrolls = !fit.meetsMinimum
                cellDp = (if (scrolls) SHOGI_MIN_CELL_DP else fit.cellPx).dp
            }
            val handCell = (cellDp.value * 0.9f).coerceIn(30f, 52f).dp

            @Composable
            fun HandRow(side: Int) {
                val mineRow = side == s.sideToMove && interactive
                val label = youAware(s.players[s.seatOfSide(side)].displayName, R.string.shogi_hand_of, R.string.yg_shogi_hand_of)
                Column(Modifier.fillMaxWidth()) {
                    Text(label, color = palette.text.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                    ShogiTray(palette, Modifier.fillMaxWidth().heightIn(min = handCell + 8.dp)) {
                        Row(
                            Modifier.padding(4.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var any = false
                            for (type in ShogiPiece.HAND_TYPES) {
                                val count = s.handCount(side, type)
                                if (count == 0) continue
                                any = true
                                val name = stringResource(pieceNameRes(type))
                                val desc = stringResource(R.string.shogi_hand_piece_desc, name, count)
                                val selected = (sel as? Sel.Hand)?.type == type && mineRow
                                Box(
                                    Modifier
                                        .size(handCell + 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(if (selected) Modifier.border(3.dp, palette.select, RoundedCornerShape(8.dp)) else Modifier)
                                        .semantics { contentDescription = desc }
                                        .clickable(enabled = mineRow, role = Role.Button, onClickLabel = name) { tapHand(type) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    ShogiPieceToken(
                                        ShogiPiece.code(side, type), handCell, side != viewSide, pieceStyle, palette
                                    )
                                    if (count > 1) {
                                        Text(
                                            "×$count",
                                            color = palette.text,
                                            fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .background(palette.background.copy(alpha = 0.85f), CircleShape)
                                                .padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                            if (!any) {
                                Text(
                                    stringResource(R.string.shogi_hand_empty),
                                    color = palette.text.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            @Composable
            fun BoardBlock() {
                val boardScroll = rememberScrollState()
                Box(
                    Modifier.then(if (scrolls) Modifier.horizontalScroll(boardScroll) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(3.dp, palette.gridLine, RoundedCornerShape(6.dp))
                    ) {
                        ShogiBoardView(
                            s = s, viewSide = viewSide, cell = cellDp, style = pieceStyle, palette = palette,
                            highlights = highlights, flight = flight.value,
                            descriptions = { sq -> cellDescription(ctx, s, sq, viewSide, sq in targets, sq in captureTargets, (sel as? Sel.Square)?.sq == sq, sq == highlights.hintTo) },
                            tapLabel = stringResource(R.string.shogi_tap_label),
                            enabled = interactive,
                            onTap = ::tapCell
                        )
                        if (s.gameOver && !reduced) {
                            val iWon = s.winnerPlayerId != null && (isPassAndPlay || s.players.getOrNull(if (isNetworked) localSeat else humanSeat)?.playerId == s.winnerPlayerId)
                            if (iWon) Petals(Modifier.matchParentSize())
                        }
                    }
                }
            }

            @Composable
            fun Panel() {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (side in 0..1) {
                            val seat = s.seatOfSide(side)
                            val turn = s.sideToMove == side && !s.gameOver
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (turn) palette.select.copy(alpha = 0.18f) else Color.Transparent)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    s.players[seat].displayName, color = palette.text,
                                    fontWeight = if (turn) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(sideName(ctx, side), color = palette.text.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(
                        if (isNetworked) stringResource(R.string.shogi_vs_you_play, s.players.getOrNull(1 - localSeat)?.displayName ?: "", sideName(ctx, viewSide)) else "",
                        color = palette.text, style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        listOf(turnText, if (s.inCheck && !s.gameOver) stringResource(R.string.shogi_check) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                        color = if (s.inCheck && !s.gameOver) palette.check else palette.text,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        announcement,
                        color = palette.text.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (botThinking && !s.gameOver) {
                        Text(
                            stringResource(R.string.shogi_thinking),
                            color = palette.text.copy(alpha = 0.5f + 0.4f * pulse),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val msg = message
                    if (msg != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            msg,
                            color = palette.check,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        )
                    }
                }
            }

            @Composable
            fun Controls() {
                val colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = palette.text)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val p = pending
                    if (p != null) {
                        val moves = legal.filter { ShogiMove.from(it) == p.first && ShogiMove.to(it) == p.second }
                        val promoteMove = moves.firstOrNull { ShogiMove.promote(it) }
                        val stayMove = moves.firstOrNull { !ShogiMove.promote(it) }
                        val type = ShogiPiece.type(s.cells[p.first])
                        Card {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.shogi_promote_prompt, stringResource(pieceNameRes(type))),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { promoteMove?.let { game.playMove(it) } },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text(stringResource(R.string.shogi_promote_yes)) }
                                    OutlinedButton(
                                        onClick = { stayMove?.let { game.playMove(it) } },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text(stringResource(R.string.shogi_promote_no)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!isNetworked) {
                            OutlinedButton(onClick = { game.undo() }, enabled = game.canUndo, colors = colors, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.shogi_undo))
                            }
                            OutlinedButton(
                                onClick = { scope.launch { game.requestHint() } },
                                enabled = interactive, colors = colors, modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.shogi_hint)) }
                        }
                        val toggleDesc = stringResource(R.string.shogi_style_toggle_desc, styleLabel)
                        OutlinedButton(
                            onClick = {
                                pieceStyle = if (pieceStyle == ShogiPieceStyle.KANJI) ShogiPieceStyle.SYMBOLS else ShogiPieceStyle.KANJI
                                try { prefs(ctx).edit().putString("piece_style", if (pieceStyle == ShogiPieceStyle.SYMBOLS) "symbols" else "kanji").apply() } catch (_: Exception) {}
                            },
                            colors = colors,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = toggleDesc }
                        ) { Text(toggleDesc) }
                        if (s.canDeclareImpasse && isMyTurn) {
                            Button(onClick = { game.declareImpasse() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.shogi_declare_impasse))
                            }
                        }
                    }
                }
            }

            @Composable
            fun CoachCard() {
                if (coachSeen || s.gameOver) return
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.shogi_coach_title), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(if (mini) R.string.shogi_mini_coach_body else R.string.shogi_coach_body), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.shogi_coach_trivia), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                coachSeen = true
                                try { prefs(ctx).edit().putBoolean("coach_seen_${game.gameId}", true).apply() } catch (_: Exception) {}
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.shogi_coach_gotit)) }
                    }
                }
            }

            @Composable
            fun Finished() {
                if (!s.gameOver) return
                Card {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
                        Text(
                            if (winner != null) youAware(winner.displayName, R.string.shogi_result_win, R.string.yg_win) else stringResource(R.string.shogi_result_draw),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                        )
                        Text(stringResource(endReasonRes(s.endReason)), style = MaterialTheme.typography.bodyMedium)
                        if (!isPassAndPlay && winner != null) {
                            val me = s.players.getOrNull(if (isNetworked) localSeat else humanSeat)
                            Text(
                                stringResource(if (me?.playerId == winner.playerId) R.string.shogi_you_won else R.string.shogi_you_lost),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        val wins = game.sessionWins.value
                        val draws = game.sessionDraws.value
                        if (wins.values.sum() + draws > 1) {
                            Text(
                                stringResource(
                                    R.string.shogi_session,
                                    s.players.joinToString(" · ") { "${it.displayName}: ${wins[it.playerId] ?: 0}" } +
                                        if (draws > 0) " · " + stringResource(R.string.shogi_session_draws, draws) else ""
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { game.playAgain() }, enabled = !opponentLeft, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.shogi_play_again))
                            }
                            ChangeSetupButton(onLeave = { game.leaveSession() })
                            OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.shogi_back_menu))
                            }
                        }
                    }
                }
            }

            val topSide = 1 - viewSide
            if (wide) {
                Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        BoardBlock()
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Panel()
                        CoachCard()
                        HandRow(topSide)
                        HandRow(viewSide)
                        Controls()
                        Finished()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Panel()
                    CoachCard()
                    HandRow(topSide)
                    BoardBlock()
                    HandRow(viewSide)
                    Controls()
                    Finished()
                }
            }
        }
    }
}

@StringRes
private fun endReasonRes(e: ShogiEnd): Int = when (e) {
    ShogiEnd.CHECKMATE -> R.string.shogi_reason_checkmate
    ShogiEnd.REPETITION -> R.string.shogi_reason_repetition
    ShogiEnd.PERPETUAL_CHECK -> R.string.shogi_reason_perpetual
    ShogiEnd.IMPASSE -> R.string.shogi_reason_impasse
    ShogiEnd.MOVE_LIMIT -> R.string.shogi_reason_limit
    ShogiEnd.NONE -> R.string.shogi_reason_checkmate
}

private fun cellDescription(
    ctx: Context, s: ShogiState, sq: Int, viewSide: Int,
    isTarget: Boolean, isCapture: Boolean, isSelected: Boolean, isHint: Boolean
): String {
    val code = s.cells[sq]
    val where = squareName(ctx, s, sq, viewSide)
    val base = if (code == 0) {
        ctx.getString(R.string.shogi_cell_empty, where)
    } else {
        ctx.getString(R.string.shogi_cell_piece, sideName(ctx, ShogiPiece.side(code)), ctx.getString(pieceNameRes(ShogiPiece.type(code))), where)
    }
    val sb = StringBuilder(base)
    if (isSelected) sb.append(ctx.getString(R.string.shogi_cell_selected))
    if (isCapture) sb.append(ctx.getString(R.string.shogi_cell_capture))
    else if (isTarget) sb.append(ctx.getString(R.string.shogi_cell_legal))
    if (isHint) sb.append(ctx.getString(R.string.shogi_cell_hint))
    return sb.toString()
}

/** The spoken / visible description of the latest event, for TalkBack's polite live region. */
private fun buildAnnouncement(ctx: Context, s: ShogiState, viewSide: Int): String {
    if (s.ply == 0 && s.lastTo < 0) {
        return ctx.getString(R.string.shogi_ann_start, s.players[s.senteSeat].displayName)
    }
    val sb = StringBuilder()
    if (s.endReason == ShogiEnd.IMPASSE && s.lastTo < 0) return ctx.getString(endReasonRes(s.endReason))
    if (s.lastTo >= 0) {
        val code = s.cells[s.lastTo]
        val mover = ShogiPiece.side(code)
        val who = s.players[s.seatOfSide(mover)].displayName
        val pieceName = ctx.getString(pieceNameRes(ShogiPiece.type(code)))
        val to = squareName(ctx, s, s.lastTo, viewSide)
        if (s.lastFrom >= 0) {
            sb.append(ctx.getString(R.string.shogi_ann_move, who, pieceName, squareName(ctx, s, s.lastFrom, viewSide), to))
        } else {
            sb.append(ctx.getString(R.string.shogi_ann_drop, who, pieceName, to))
        }
        if (s.lastCaptured != 0) {
            sb.append(' ').append(ctx.getString(R.string.shogi_ann_capture, ctx.getString(pieceNameRes(ShogiPiece.base(ShogiPiece.type(s.lastCaptured))))))
        }
    }
    if (s.inCheck && !s.gameOver) sb.append(' ').append(ctx.getString(R.string.shogi_ann_check))
    if (s.gameOver) {
        val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
        sb.append(' ').append(
            if (winner != null) ctx.youAware(winner.displayName, R.string.shogi_result_win, R.string.yg_win) else ctx.getString(R.string.shogi_result_draw)
        )
    }
    return sb.toString().trim()
}

/** Falling gold-and-red petals over the board for a win (decorative; hidden from TalkBack). */
@Composable
private fun Petals(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "petals")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "petal-phase")
    val seeds = remember { List(36) { Triple(Random(it).nextFloat(), Random(it + 100).nextFloat(), Random(it + 200).nextFloat()) } }
    Canvas(modifier) {
        for ((i, seed) in seeds.withIndex()) {
            val (x, p, sz) = seed
            val y = ((phase * (0.6f + p * 0.6f)) + p) % 1f
            val cx = size.width * x + kotlin.math.sin((y * 6f + i).toDouble()).toFloat() * size.width * 0.03f
            val color = if (i % 3 == 0) Color(0xFFD32F2F) else Color(0xFFF2C230)
            drawCircle(color.copy(alpha = 0.85f), size.width * (0.008f + sz * 0.012f), Offset(cx, y * size.height))
        }
    }
}
