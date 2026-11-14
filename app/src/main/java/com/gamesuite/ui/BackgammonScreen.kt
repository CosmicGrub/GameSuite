package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.backgammon.BackgammonGame
import com.gamesuite.games.backgammon.BackgammonMove
import com.gamesuite.games.backgammon.BackgammonPhase
import com.gamesuite.games.backgammon.BackgammonResultKind
import com.gamesuite.games.backgammon.BackgammonRules
import com.gamesuite.games.backgammon.BackgammonState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Backgammon for two seats (vs the CPU, or pass and play on one device). There is no hidden
 * information, so pass and play needs no curtain: the current player's name comes from
 * `context.players`.
 *
 * FLOW: a big action button rolls the dice (the opening roll first, unless the Hub forced a first
 * mover), the player taps a highlighted checker and then a highlighted destination, once per die,
 * and finally taps Done. The button doubles as the "no legal moves, pass" button. Undo takes back a
 * single checker move of the current turn; a roll cannot be undone. Hint marks the suggested move
 * with a dashed ring and a star.
 *
 * LAYOUT: the board picks upright or sideways to fit its space ([BackgammonBoardView]); in a wide,
 * short window (landscape) the controls move to a side panel. Every point, the bar and both trays
 * are accessible nodes with a spoken description including the checker count and whether the point
 * is a legal destination.
 *
 * MOTION: the CPU's pauses scale with [LocalGameSpeed]; a checker flight and a hit pulse play
 * unless [LocalReducedMotion] is on, in which case moves only leave a static ring behind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgammonScreen(
    sessionManager: GameSessionManager,
    game: BackgammonGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val state by game.state
    val hint by game.hintMove
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colorblind = LocalColorblindMode.current
    val reducedMotion = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val palette = backgammonPalette(MaterialTheme.colorScheme.background.luminance() < 0.5f, colorblind)
    val scope = rememberCoroutineScope()
    var selectedFrom by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        // Per-launch Hub choices, read once before init/tryResume. The Hub's default first mover
        // (seat 0) means "no preference": the opening roll decides. A resumed game ignores it.
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex.takeIf { it > 0 }
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // The CPU plays its whole turn (roll, each checker, end of turn) from one effect keyed on whose
    // turn it is, so the coroutine is not cancelled by its own moves. The search itself runs off
    // the main thread inside the engine, which drops the answer if the position changed meanwhile.
    val botKey = state?.let { Triple(it.currentPlayerIndex, it.phase == BackgammonPhase.OVER, it.phase == BackgammonPhase.OPENING) }
    LaunchedEffect(botKey) {
        val s = game.state.value ?: return@LaunchedEffect
        if (s.phase == BackgammonPhase.OVER || s.phase == BackgammonPhase.OPENING) return@LaunchedEffect
        if (!s.players[s.currentPlayerIndex].isBot) return@LaunchedEffect
        delay(gameSpeed.scale(500L))
        game.playBotTurnAsync(pace = { delay(gameSpeed.scale(PACE_MS)) })
    }

    val s = state ?: return

    // A move (not a restore or an undo) gets a haptic tick and a sound; a hit is stronger.
    LaunchedEffect(s.moveSeq) {
        val move = s.lastMove
        if (s.moveSeq > 0 && move != null) {
            haptics(if (move.hit) HapticSignal.STRONG_ACTION else HapticSignal.LIGHT_TICK)
            sounds.playTap()
        }
    }
    LaunchedEffect(s.dice) {
        if (s.dice.isNotEmpty()) haptics(HapticSignal.NORMAL_ACTION)
    }
    LaunchedEffect(s.phase == BackgammonPhase.OVER) {
        if (s.phase == BackgammonPhase.OVER) {
            val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
            haptics(if (winner != null && !winner.isBot) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
        }
    }
    LaunchedEffect(s.moveSeq, s.currentPlayerIndex, s.phase) { selectedFrom = null }

    val current = s.players[s.currentPlayerIndex]
    val vsBot = s.players.any { it.isBot }
    val humanTurn = (s.phase == BackgammonPhase.MOVING || s.phase == BackgammonPhase.TO_ROLL) && !current.isBot
    val canInteract = s.phase == BackgammonPhase.MOVING && !current.isBot
    val legal = s.legalMoves
    // When only one checker can move at all (a forced entry from the bar, one movable stack) its
    // destinations are shown straight away, saving a tap.
    val autoFrom = legal.map { it.from }.distinct().singleOrNull()
    val activeFrom = selectedFrom?.takeIf { sf -> legal.any { it.from == sf } } ?: autoFrom
    val finishedGames = game.sessionWins.value.values.sum()
    val flightMs = (gameSpeed.scale(PACE_MS) * 6 / 10).toInt().coerceIn(60, 260)
    val barWord = stringResource(R.string.backgammon_bar)
    val offWord = stringResource(R.string.backgammon_off)

    fun tapPoint(index: Int) {
        if (!canInteract) return
        val from = activeFrom
        if (from != null && legal.any { it.from == from && it.to == index }) {
            game.moveChecker(from, index)
            selectedFrom = null
        } else if (legal.any { it.from == index }) {
            selectedFrom = if (selectedFrom == index) null else index
        } else {
            selectedFrom = null
        }
    }

    fun tapBar() {
        if (!canInteract) return
        if (legal.any { it.from == BackgammonMove.BAR }) {
            selectedFrom = if (selectedFrom == BackgammonMove.BAR) null else BackgammonMove.BAR
        }
    }

    fun tapTray(side: Int) {
        if (!canInteract || side != s.currentPlayerIndex) return
        val from = activeFrom ?: return
        if (legal.any { it.from == from && it.to == BackgammonMove.OFF }) {
            game.moveChecker(from, BackgammonMove.OFF)
            selectedFrom = null
        }
    }

    val status = backgammonStatusText(s, vsBot, activeFrom != null)
    val hintText = hint?.let {
        stringResource(
            R.string.backgammon_hint_text,
            hintPointLabel(it.from, s.currentPlayerIndex, barWord, offWord),
            hintPointLabel(it.to, s.currentPlayerIndex, barWord, offWord)
        )
    }

    GameChrome(
        helpTitle = stringResource(R.string.backgammon_help_title),
        helpText = stringResource(R.string.backgammon_help_body),
        matchInProgress = s.phase != BackgammonPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        onUndo = { selectedFrom = null; game.undo() },
        canUndo = game.canUndo && humanTurn,
        onHint = { scope.launch { game.hintAsync() } },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = stringResource(R.string.backgammon_leave_title),
        leaveBody = stringResource(if (finishedGames > 0) R.string.backgammon_leave_body_session else R.string.backgammon_leave_body)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(palette.background)) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            val board: @Composable (Modifier) -> Unit = { mod ->
                BackgammonBoardView(
                    state = s,
                    selectedFrom = activeFrom,
                    hint = hint,
                    canInteract = canInteract,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    flightMs = flightMs,
                    onPointTap = ::tapPoint,
                    onBarTap = ::tapBar,
                    onTrayTap = ::tapTray,
                    modifier = mod
                )
            }
            if (landscape) {
                Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    board(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .width(272.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(top = GameChromeEndInset),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BgPlayerStrip(s, game, palette, colorblind, stacked = true)
                        BgStatus(status, hintText, palette)
                        BgDiceRow(s, palette, colorblind)
                        BgControls(s, game, humanTurn, canInteract, palette, scope, onUndo = { selectedFrom = null })
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BgPlayerStrip(s, game, palette, colorblind, stacked = false)
                    Spacer(Modifier.height(4.dp))
                    BgStatus(status, hintText, palette)
                    Spacer(Modifier.height(4.dp))
                    board(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    BgDiceRow(s, palette, colorblind)
                    Spacer(Modifier.height(6.dp))
                    BgControls(s, game, humanTurn, canInteract, palette, scope, onUndo = { selectedFrom = null })
                }
            }

            if (s.phase == BackgammonPhase.OVER) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BgResultPanel(s, game, palette, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

private const val PACE_MS = 450L

// ---------------------------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------------------------

private fun backgammonPalette(isDark: Boolean, colorblind: Boolean): BackgammonPalette {
    val base = if (!isDark) {
        BackgammonPalette(
            background = Color(0xFFFBF1E6), textPrimary = Color(0xFF3A2E22),
            frame = Color(0xFF3E2A18), wood = Color(0xFFD9B27C), barColor = Color(0xFF8B5E34), trayColor = Color(0xFFB88B55),
            pointLight = Color(0xFFF1DDB6), pointDark = Color(0xFF7A4A2A),
            white = Color(0xFFF6F0E2), whiteEdge = Color(0xFF5B4A38), whiteMark = Color(0xFF8A7A66),
            black = Color(0xFF2A2522), blackEdge = Color(0xFF0C0A09), blackMark = Color(0xFFE8DCC6),
            accent = Color(0xFF0B6FC2), selected = Color(0xFFE08A00), hint = Color(0xFFC2185B)
        )
    } else {
        BackgammonPalette(
            background = Color(0xFF1C1712), textPrimary = Color(0xFFF3E9DB),
            frame = Color(0xFF2A1D11), wood = Color(0xFF5A4630), barColor = Color(0xFF3B2A1A), trayColor = Color(0xFF47341F),
            pointLight = Color(0xFFB89868), pointDark = Color(0xFF3A2514),
            white = Color(0xFFEDE6D8), whiteEdge = Color(0xFF4A3D2E), whiteMark = Color(0xFF8A7A66),
            black = Color(0xFF151210), blackEdge = Color(0xFFB8AC98), blackMark = Color(0xFFE8DCC6),
            accent = Color(0xFF4FC3F7), selected = Color(0xFFFFB300), hint = Color(0xFFFF80AB)
        )
    }
    if (!colorblind) return base
    // Colorblind-safe: maximum light/dark contrast between the sides, an Okabe-Ito blue / orange /
    // reddish-purple trio for the three highlight roles (shapes carry the same signals regardless).
    return base.copy(
        white = Color.White, whiteEdge = Color.Black, whiteMark = Color.Black,
        black = Color.Black, blackEdge = Color.White, blackMark = Color.White,
        accent = if (isDark) Color(0xFF56B4E9) else Color(0xFF0072B2),
        selected = Color(0xFFE69F00),
        hint = Color(0xFFCC79A7)
    )
}

// ---------------------------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------------------------

private fun hintPointLabel(index: Int, seat: Int, barWord: String, offWord: String): String = when (index) {
    BackgammonMove.BAR -> barWord
    BackgammonMove.OFF -> offWord
    else -> BackgammonRules.pointNumber(seat, index).toString()
}

@Composable
private fun sideName(side: Int): String =
    stringResource(if (side == 0) R.string.backgammon_side_white else R.string.backgammon_side_black)

@Composable
private fun backgammonStatusText(s: BackgammonState, vsBot: Boolean, hasActive: Boolean): String {
    val current = s.players[s.currentPlayerIndex]
    return when (s.phase) {
        BackgammonPhase.OPENING ->
            if (s.openingDice.size == 2) {
                stringResource(R.string.backgammon_status_opening_tie, s.openingDice[0])
            } else {
                stringResource(R.string.backgammon_status_opening)
            }
        BackgammonPhase.TO_ROLL -> when {
            current.isBot -> stringResource(R.string.backgammon_thinking, current.displayName)
            vsBot -> stringResource(R.string.backgammon_status_roll_you)
            else -> stringResource(R.string.backgammon_status_roll, current.displayName, sideName(s.currentPlayerIndex))
        }
        BackgammonPhase.MOVING -> when {
            current.isBot -> stringResource(R.string.backgammon_thinking, current.displayName)
            s.turnComplete && s.remaining.isNotEmpty() -> stringResource(R.string.backgammon_status_blocked)
            s.turnComplete -> stringResource(R.string.backgammon_status_turn_done)
            s.board.bar(s.currentPlayerIndex) > 0 -> stringResource(R.string.backgammon_status_enter)
            hasActive -> stringResource(R.string.backgammon_status_destination)
            s.moveSeq == 0 && s.openingDice.size == 2 ->
                stringResource(
                    R.string.backgammon_status_opening_won,
                    current.displayName,
                    s.dice.getOrElse(0) { 0 },
                    s.dice.getOrElse(1) { 0 }
                )
            else -> stringResource(R.string.backgammon_status_select)
        }
        BackgammonPhase.OVER -> stringResource(R.string.backgammon_status_over)
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun BgStatus(status: String, hintText: String?, palette: BackgammonPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            status,
            color = palette.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (hintText != null) {
            Text(
                hintText,
                color = palette.textPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The two players: a checker badge (ring / square, matching the board), the name and the session points. */
@Composable
private fun BgPlayerStrip(s: BackgammonState, game: BackgammonGame, palette: BackgammonPalette, colorblind: Boolean, stacked: Boolean) {
    val points = game.sessionPoints.value
    val chips: @Composable (Int) -> Unit = { seat ->
        val player = s.players[seat]
        val isTurn = s.phase != BackgammonPhase.OVER && s.phase != BackgammonPhase.OPENING && s.currentPlayerIndex == seat
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isTurn) palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BgBadge(seat, palette, colorblind)
            Spacer(Modifier.width(6.dp))
            Text(
                player.displayName,
                color = palette.textPrimary,
                fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text(
                (points[player.playerId] ?: 0).toString(),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chips(0)
            chips(1)
        }
    } else {
        // End padding keeps the corner menu button clear of the second chip.
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            chips(0)
            chips(1)
        }
    }
}

@Composable
private fun BgBadge(side: Int, palette: BackgammonPalette, colorblind: Boolean) {
    val label = sideName(side)
    Canvas(modifier = Modifier.size(18.dp).clearAndSetSemantics { contentDescription = label }) {
        drawBgChecker(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f - 1f, side, palette, colorblind)
    }
}

/** The dice of the current roll: used ones are dimmed AND struck through. */
@Composable
private fun BgDiceRow(s: BackgammonState, palette: BackgammonPalette, colorblind: Boolean) {
    val dieSize = 44.dp
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = dieSize),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            s.phase == BackgammonPhase.OPENING && s.openingDice.size == 2 -> {
                BgDie(s.openingDice[0], used = false, side = 0, palette = palette, size = dieSize)
                BgDie(s.openingDice[1], used = false, side = 1, palette = palette, size = dieSize)
            }
            s.phase == BackgammonPhase.MOVING || s.phase == BackgammonPhase.OVER -> {
                val shown = if (s.dice.size == 2 && s.dice[0] == s.dice[1]) List(4) { s.dice[0] } else s.dice
                val pool = s.remaining.toMutableList()
                shown.forEach { value ->
                    val unused = pool.remove(value)
                    BgDie(value, used = !unused, side = s.currentPlayerIndex, palette = palette, size = if (shown.size > 2) 38.dp else dieSize)
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun BgDie(value: Int, used: Boolean, side: Int, palette: BackgammonPalette, size: Dp) {
    val description = stringResource(if (used) R.string.backgammon_die_used else R.string.backgammon_die, value)
    val face = if (side == 0) palette.white else palette.black
    val edge = if (side == 0) palette.whiteEdge else palette.blackEdge
    val pip = if (side == 0) palette.whiteEdge else palette.blackMark
    Canvas(
        modifier = Modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        val alpha = if (used) 0.3f else 1f
        val d = density
        val corner = CornerRadius(size.toPx() * 0.18f)
        drawRoundRect(face.copy(alpha = alpha), size = this.size, cornerRadius = corner)
        drawRoundRect(edge.copy(alpha = alpha), size = this.size, cornerRadius = corner, style = Stroke(width = 1.6f * d))
        val w = this.size.width
        val spots = when (value) {
            1 -> listOf(1 to 1)
            2 -> listOf(0 to 0, 2 to 2)
            3 -> listOf(0 to 0, 1 to 1, 2 to 2)
            4 -> listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2)
            5 -> listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2, 1 to 1)
            else -> listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1, 0 to 2, 2 to 2)
        }
        for ((cx, cy) in spots) {
            drawCircle(
                pip.copy(alpha = alpha),
                radius = w * 0.085f,
                center = Offset(w * (0.24f + 0.26f * cx), w * (0.24f + 0.26f * cy))
            )
        }
        if (used) {
            drawLine(edge, Offset(w * 0.12f, w * 0.88f), Offset(w * 0.88f, w * 0.12f), strokeWidth = 2.5f * d)
        }
    }
}

/** Moves left, the main action (roll / done / pass) and Undo + Hint. */
@Composable
private fun BgControls(
    s: BackgammonState,
    game: BackgammonGame,
    humanTurn: Boolean,
    canInteract: Boolean,
    palette: BackgammonPalette,
    scope: kotlinx.coroutines.CoroutineScope,
    onUndo: () -> Unit
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.phase != BackgammonPhase.OVER) {
            val label: String
            val enabled: Boolean
            val onClick: () -> Unit
            when (s.phase) {
                BackgammonPhase.OPENING -> {
                    label = stringResource(R.string.backgammon_roll_opening)
                    enabled = true
                    onClick = { game.rollDice() }
                }
                BackgammonPhase.TO_ROLL -> {
                    label = stringResource(R.string.backgammon_roll_dice)
                    enabled = humanTurn
                    onClick = { game.rollDice() }
                }
                else -> {
                    label = when {
                        !s.turnComplete -> stringResource(R.string.backgammon_make_move)
                        s.remaining.isNotEmpty() -> stringResource(R.string.backgammon_no_moves_pass)
                        else -> stringResource(R.string.backgammon_done)
                    }
                    enabled = humanTurn && s.turnComplete
                    onClick = { game.endTurn() }
                }
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onUndo(); game.undo() },
                    enabled = game.canUndo && humanTurn,
                    colors = colors,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.backgammon_undo)) }
                val hintDescription = stringResource(
                    if (canInteract && s.legalMoves.isNotEmpty()) R.string.backgammon_hint else R.string.backgammon_hint_unavailable
                )
                OutlinedButton(
                    onClick = { scope.launch { game.hintAsync() } },
                    enabled = canInteract && s.legalMoves.isNotEmpty(),
                    colors = colors,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = hintDescription }
                ) { Text(stringResource(R.string.backgammon_hint)) }
                // The "moves left" indicator: how many dice are still to be played this turn.
                if (s.phase == BackgammonPhase.MOVING) {
                    Text(
                        if (s.turnComplete && s.remaining.isNotEmpty()) {
                            stringResource(R.string.backgammon_no_moves_left)
                        } else {
                            stringResource(R.string.backgammon_moves_left, s.remaining.size)
                        },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BgResultPanel(s: BackgammonState, game: BackgammonGame, palette: BackgammonPalette, modifier: Modifier = Modifier) {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId }
    val points = game.sessionPoints.value
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (winner != null) stringResource(R.string.backgammon_result_wins, winner.displayName) else stringResource(R.string.backgammon_status_over),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (s.resultKind) {
                    BackgammonResultKind.BACKGAMMON -> stringResource(R.string.backgammon_result_backgammon, s.pointsScored)
                    BackgammonResultKind.GAMMON -> stringResource(R.string.backgammon_result_gammon, s.pointsScored)
                    else -> stringResource(R.string.backgammon_result_normal, s.pointsScored)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.backgammon_result_session,
                    s.players.joinToString(" · ") { "${it.displayName} ${points[it.playerId] ?: 0}" }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { game.playAgain() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backgammon_play_again))
                }
                ChangeSetupButton(onLeave = { game.leaveSession() })
                OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backgammon_back_to_menu))
                }
            }
        }
    }
}
