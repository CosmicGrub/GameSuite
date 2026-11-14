package com.gamesuite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.flowlines.FlowLinesGame
import com.gamesuite.games.flowlines.FlowLinesRecord
import com.gamesuite.games.flowlines.FlowLinesState
import com.gamesuite.games.flowlines.FlowLinesStatsStore
import com.gamesuite.games.flowlines.FlowTier
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.floor

/** Below this cell size (dp) the board stops shrinking and scrolls/pans instead. */
private const val FLOW_MIN_CELL_DP = 28f

/** Upper bound so a 5x5 does not sprawl across a tablet. */
private const val FLOW_MAX_CELL_DP = 72f

/** Padding between the board's rim and its cells (dp). */
private const val FLOW_BOARD_PADDING_DP = 6f

/** Padding plus the 2dp rim: the `framePx` handed to [fitBoard], so layout and fit can never drift apart. */
private const val FLOW_BOARD_INSET_DP = FLOW_BOARD_PADDING_DP + 2f

private const val FLOW_RESULT_HOLD_MS = 300L
private const val FLOW_RESULT_PANEL_MAX_WIDTH_DP = 360
private const val FLOW_LETTERS = "ABCDEFGH"

private val FlowColorNameRes = listOf(
    R.string.flowlines_color_red,
    R.string.flowlines_color_blue,
    R.string.flowlines_color_green,
    R.string.flowlines_color_yellow,
    R.string.flowlines_color_orange,
    R.string.flowlines_color_purple,
    R.string.flowlines_color_cyan,
    R.string.flowlines_color_pink
)

/** Vivid colours for the default palette (names match [FlowColorNameRes]). */
private val FlowStandardColors = listOf(
    Color(0xFFFF4D4D), Color(0xFF3D8BFF), Color(0xFF2FCB6B), Color(0xFFFFD23F),
    Color(0xFFFF8A2B), Color(0xFFB565F0), Color(0xFF22D3EE), Color(0xFFFF6FB5)
)

/** Colourblind-friendly set (Okabe-Ito based, lightened for a dark board). Shapes and letters carry identity either way. */
private val FlowColorblindColors = listOf(
    Color(0xFFE69F00), Color(0xFF56B4E9), Color(0xFF00C48C), Color(0xFFF0E442),
    Color(0xFF6C8CFF), Color(0xFFFF6B3D), Color(0xFFCC79A7), Color(0xFFFFFFFF)
)

private val FlowInk = Color(0xFF0B0E14)

private fun flowTierFor(difficulty: CpuDifficulty): FlowTier = when (difficulty) {
    CpuDifficulty.EASY -> FlowTier.S5
    CpuDifficulty.MEDIUM -> FlowTier.S7
    CpuDifficulty.HARD -> FlowTier.S9
}

private data class FlowPalette(
    val background: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val accent: Color,
    val chipBackground: Color,
    val boardWell: Color,
    val gridLine: Color,
    val boardRim: Color
)

private val FlowLightPalette = FlowPalette(
    background = Color(0xFFF1F4FA),
    textPrimary = Color(0xFF1B2230),
    textOnAccent = Color(0xFF0B0E14),
    accent = Color(0xFF5FA8FF),
    chipBackground = Color(0xFFD5DEEE),
    boardWell = Color(0xFF12161F),
    gridLine = Color(0xFF3A4458),
    boardRim = Color(0xFF12161F)
)

private val FlowDarkPalette = FlowPalette(
    background = Color(0xFF12161F),
    textPrimary = Color(0xFFE8EDF7),
    textOnAccent = Color(0xFF0B0E14),
    accent = Color(0xFF6DB3FF),
    chipBackground = Color(0xFF2A3447),
    boardWell = Color(0xFF080A10),
    gridLine = Color(0xFF47536B),
    boardRim = Color(0xFF6B7A99)
)

private fun flowFormatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Flow Lines: join every pair of same-coloured dots with a path; paths never cross and every cell
 * must be covered. Used for the free-play route and (with [daily]) the daily route.
 *
 * INPUT: one pointer drags across the grid; the engine follows it cell by cell, so a fast swipe
 * cannot skip a square. Touching an empty cell does nothing, which is also what lets a board that
 * had to fall back to scrolling (cells would be under 28dp) pan by dragging on empty squares.
 *
 * NEVER COLOUR ALONE: every dot carries its letter (A, B, C...) and every path cell a small
 * per-colour shape; [LocalColorblindMode] also swaps in a colourblind-safe set of hues.
 *
 * ACCESSIBILITY: each cell is a button described by row, column and what is on it ("Row 2, column 3,
 * red, letter A path"); activating one selects that colour and then extends it one cell at a time
 * (see [FlowLinesGame.tapCell]) so the puzzle is playable without dragging. The status line is a
 * polite live region.
 */
@Composable
fun FlowLinesScreen(
    sessionManager: GameSessionManager,
    game: FlowLinesGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    daily: Boolean = false
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val statsStore = remember { FlowLinesStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) FlowDarkPalette else FlowLightPalette
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val colors = if (colorblind) FlowColorblindColors else FlowStandardColors

    val dailySeed: Long? = remember(daily) { if (daily) java.time.LocalDate.now().toEpochDay() else null }
    var dailyBoardActive by remember { mutableStateOf(daily) }
    var resumedAsSolved by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.tier = flowTierFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyBoardActive = dailySeed != null
        if (sessionManager.tryResume(game)) {
            val savedSeed = game.dailySeedInUse
            if (dailySeed != null && savedSeed != null && savedSeed != dailySeed) {
                // The save holds an earlier day's daily puzzle: today's is a different one.
                resumedAsSolved = false
                game.startMatch(dailySeed)
            } else {
                dailyBoardActive = game.isDailyBoard.value
                resumedAsSolved = game.state.value?.won == true
            }
        } else {
            game.startMatch(dailySeed)
        }
    }

    val s = state ?: return

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.tier.name]
    var reportedResult by remember(s.puzzle, game.tier) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    val connected = s.connected
    var lastConnected by remember { mutableStateOf(-1) }
    LaunchedEffect(connected) {
        // -1 = first look at this board (a resume must not buzz for pairs that were already joined).
        if (lastConnected >= 0 && connected > lastConnected) haptics(HapticSignal.SUCCESS)
        lastConnected = connected
    }

    LaunchedEffect(s.won) {
        if (!s.won || resumedAsSolved) return@LaunchedEffect
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        // An assisted solve (any hint taken) counts as solved but sets no record.
        if (reportedResult == null && game.hintsUsed.value == 0) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.tier, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
        }
    }

    var resultHoldDone by remember { mutableStateOf(false) }
    LaunchedEffect(s.isOver, reducedMotion) {
        if (!s.isOver) {
            resultHoldDone = false
            return@LaunchedEffect
        }
        if (!reducedMotion) delay(FLOW_RESULT_HOLD_MS)
        resultHoldDone = true
    }
    val showResult = s.isOver && resultHoldDone

    val onBegin: (Int) -> Boolean = { cell ->
        val started = game.beginStroke(cell)
        if (started) {
            sounds.playTap()
            haptics(HapticSignal.LIGHT_TICK)
        }
        started
    }
    val onDrag: (Int) -> Unit = { cell -> game.dragTo(cell) }
    val onEnd: () -> Unit = {
        val wasMoves = game.state.value?.moves
        game.endStroke()
        if (game.state.value?.moves != wasMoves && game.state.value?.won != true) haptics(HapticSignal.NORMAL_ACTION)
    }
    val onTapCell: (Int) -> Unit = { cell ->
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            game.tapCell(cell)
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val startNewPuzzle: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            resumedAsSolved = false
            game.playAgain()
        }
    }
    val onSelectTier: (FlowTier) -> Unit = { tier ->
        if (!game.matchOver.value && tier != game.tier) {
            dailyBoardActive = false
            resumedAsSolved = false
            game.tier = tier
            game.startMatch()
        }
    }

    val finishedPuzzles = game.puzzlesSolved.value

    GameChrome(
        helpTitle = stringResource(R.string.flowlines_help_title),
        helpText = stringResource(R.string.flowlines_help_text),
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedPuzzles > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedPuzzles == 0,
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = { game.hint() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = stringResource(R.string.flowlines_leave_title),
        leaveBody = if (finishedPuzzles > 0) {
            stringResource(R.string.flowlines_leave_body_keeps)
        } else {
            stringResource(R.string.flowlines_leave_body)
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp)
        ) {
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                FlowStatus(
                    moves = s.moves,
                    connected = connected,
                    flows = s.puzzle.colorCount,
                    filledPercent = (s.filled * 100) / s.puzzle.cellCount,
                    daily = dailyBoardActive,
                    game = game,
                    onNewPuzzle = startNewPuzzle,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                FlowTierChips(current = game.tier, palette = palette, onSelect = onSelectTier)
            }
            val controlsRow: @Composable () -> Unit = {
                FlowControlsRow(
                    canUndo = game.canUndo,
                    canHint = !s.isOver && !game.matchOver.value,
                    onUndo = { game.undo() },
                    onHint = { game.hint() },
                    palette = palette
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                FlowBoardSlot(
                    s = s,
                    hintCell = game.hintCell.value,
                    activeColor = game.activeColor.value,
                    palette = palette,
                    colors = colors,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    showResult = showResult,
                    onBegin = onBegin,
                    onDrag = onDrag,
                    onEnd = onEnd,
                    onTapCell = onTapCell,
                    modifier = slotModifier,
                    resultPanel = { panelModifier ->
                        FlowResultPanel(
                            moves = s.moves,
                            hintsUsed = game.hintsUsed.value,
                            timeMillis = game.finishedElapsedMillis.value,
                            record = record,
                            isNewBestMoves = reportedResult?.first ?: false,
                            isNewBestTime = reportedResult?.second ?: false,
                            onNewPuzzle = startNewPuzzle,
                            onBackToMenu = { game.leaveSession() },
                            palette = palette,
                            modifier = panelModifier
                        )
                    }
                )
            }

            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    boardSlot(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        controlsRow()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                    Spacer(Modifier.height(4.dp))
                    tiersRow()
                    controlsRow()
                    Spacer(Modifier.height(8.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tier chips, status row, clock, controls
// ---------------------------------------------------------------------------

@Composable
private fun FlowTierChips(current: FlowTier, palette: FlowPalette, onSelect: (FlowTier) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tier in FlowTier.entries) {
            val selected = tier == current
            val contentColor = if (selected) palette.textOnAccent else palette.textPrimary
            val desc = stringResource(R.string.flowlines_tier_desc, tier.size)
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(tier) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) palette.accent else palette.chipBackground)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.flowlines_tier_label, tier.size),
                        color = contentColor,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowStatus(
    moves: Int,
    connected: Int,
    flows: Int,
    filledPercent: Int,
    daily: Boolean,
    game: FlowLinesGame,
    onNewPuzzle: () -> Unit,
    palette: FlowPalette,
    modifier: Modifier = Modifier
) {
    val statusDesc = stringResource(R.string.flowlines_status_desc, moves, connected, flows, filledPercent)
    val newPuzzleDesc = stringResource(R.string.flowlines_new_puzzle_desc)
    val newPuzzleAction = stringResource(R.string.flowlines_new_puzzle_action)
    Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Text(
                stringResource(R.string.flowlines_daily_tag),
                color = palette.textPrimary.copy(alpha = 0.75f),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = statusDesc
                    }
            ) {
                Text(
                    stringResource(R.string.flowlines_moves, moves),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.flowlines_flows, connected, flows) + "  " +
                        stringResource(R.string.flowlines_filled, filledPercent),
                    color = palette.textPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            FlowClock(game = game, palette = palette)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, palette.textPrimary.copy(alpha = 0.35f), CircleShape)
                    .semantics { contentDescription = newPuzzleDesc }
                    .clickable(onClickLabel = newPuzzleAction, role = Role.Button, onClick = onNewPuzzle),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↻",
                    color = palette.textPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

@Composable
private fun FlowControlsRow(
    canUndo: Boolean,
    canHint: Boolean,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    palette: FlowPalette,
    modifier: Modifier = Modifier
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary)
    val undoState = stringResource(if (canUndo) R.string.flowlines_undo_ready else R.string.flowlines_undo_none)
    val hintState = stringResource(if (canHint) R.string.flowlines_hint_ready else R.string.flowlines_hint_none)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onUndo,
            enabled = canUndo,
            colors = colors,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { stateDescription = undoState }
        ) { Text(stringResource(R.string.flowlines_undo)) }
        OutlinedButton(
            onClick = onHint,
            enabled = canHint,
            colors = colors,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { stateDescription = hintState }
        ) { Text(stringResource(R.string.flowlines_hint)) }
    }
}

/** The live "M:SS" clock; owns its tick state so only this Text recomposes at 5Hz. */
@Composable
private fun FlowClock(game: FlowLinesGame, palette: FlowPalette) {
    val startedAt = game.timerStartElapsedRealtime.value
    val finishedMillis = game.finishedElapsedMillis.value
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt, finishedMillis) {
        if (finishedMillis != null) {
            elapsed = finishedMillis
        } else if (startedAt == null) {
            elapsed = 0L
        } else {
            while (true) {
                elapsed = game.activeElapsedMillis() ?: 0L
                delay(200)
            }
        }
    }
    Text(
        flowFormatClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

@Composable
private fun FlowBoardSlot(
    s: FlowLinesState,
    hintCell: Int?,
    activeColor: Int?,
    palette: FlowPalette,
    colors: List<Color>,
    colorblind: Boolean,
    reducedMotion: Boolean,
    showResult: Boolean,
    onBegin: (Int) -> Boolean,
    onDrag: (Int) -> Unit,
    onEnd: () -> Unit,
    onTapCell: (Int) -> Unit,
    resultPanel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val glow by animateFloatAsState(
        targetValue = if (s.isOver) 1f else 0f,
        animationSpec = if (reducedMotion) snap<Float>() else tween<Float>(400),
        label = "flow-lines-win-glow"
    )
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val slotMaxHeight = maxHeight
        val n = s.size
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = n,
            rows = n,
            framePx = FLOW_BOARD_INSET_DP,
            minCellPx = FLOW_MIN_CELL_DP,
            maxCellPx = FLOW_MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        val cellSize: Dp = if (scrolls) {
            FLOW_MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }
        val gridSize = cellSize * n
        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.boardWell)
                    .border(2.dp, lerp(palette.boardRim, palette.accent, glow), RoundedCornerShape(14.dp))
                    .padding(FLOW_BOARD_PADDING_DP.dp)
            ) {
                FlowGrid(
                    s = s,
                    hintCell = hintCell,
                    activeColor = activeColor,
                    palette = palette,
                    colors = colors,
                    colorblind = colorblind,
                    cellSize = cellSize,
                    gridSize = gridSize,
                    onBegin = onBegin,
                    onDrag = onDrag,
                    onEnd = onEnd,
                    onTapCell = onTapCell
                )
            }
        }

        if (showResult) {
            resultPanel(
                Modifier
                    .widthIn(max = FLOW_RESULT_PANEL_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .heightIn(max = slotMaxHeight)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/** A path/dot label: "red, letter A" normally, "letter A" in colourblind mode (the hue is not to be relied on). */
private fun flowColorLabel(ctx: android.content.Context, color: Int, colorblind: Boolean): String {
    val letter = FLOW_LETTERS[color % FLOW_LETTERS.length].toString()
    return if (colorblind) {
        ctx.getString(R.string.flowlines_letter_only, letter)
    } else {
        ctx.getString(R.string.flowlines_color_with_letter, ctx.getString(FlowColorNameRes[color % FlowColorNameRes.size]), letter)
    }
}

/**
 * The drawing surface: one Canvas for tints, grid, paths, shapes and dots, with an invisible grid
 * of per-cell accessibility nodes (and the dot letters) laid over it. One pointer gesture on the
 * whole grid is one stroke.
 */
@Composable
private fun FlowGrid(
    s: FlowLinesState,
    hintCell: Int?,
    activeColor: Int?,
    palette: FlowPalette,
    colors: List<Color>,
    colorblind: Boolean,
    cellSize: Dp,
    gridSize: Dp,
    onBegin: (Int) -> Boolean,
    onDrag: (Int) -> Unit,
    onEnd: () -> Unit,
    onTapCell: (Int) -> Unit
) {
    val n = s.size
    val density = LocalDensity.current
    val androidContext = LocalContext.current
    val cellPx = with(density) { cellSize.toPx() }
    val letterSp = with(density) { (cellSize * 0.46f).toSp() }
    val currentBegin by rememberUpdatedState(onBegin)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onEnd)
    val boardDesc = stringResource(R.string.flowlines_board_desc, n)
    val actionLabel = stringResource(R.string.flowlines_cell_action)
    val suggested = stringResource(R.string.flowlines_cell_suggested)
    val enabled = !s.isOver

    Box(
        modifier = Modifier
            .size(gridSize)
            .semantics { contentDescription = boardDesc }
            .pointerInput(n, cellPx, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val last = n - 1
                    fun cellAt(p: Offset): Int {
                        val c = (p.x / cellPx).toInt().coerceIn(0, last)
                        val r = (p.y / cellPx).toInt().coerceIn(0, last)
                        return r * n + c
                    }
                    // A touch on an empty square is not ours: leaving it unconsumed lets a scrolled board pan.
                    if (!currentBegin(cellAt(down.position))) return@awaitEachGesture
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            currentDrag(cellAt(change.position))
                            change.consume()
                        }
                    } finally {
                        currentEnd()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cell = size.width / n
            fun center(c: Int) = Offset((c % n + 0.5f) * cell, (c / n + 0.5f) * cell)
            val lineWidth = 1.dp.toPx()

            // Tint every covered cell so coverage (the second half of the goal) is visible at a glance.
            for (color in s.paths.indices) {
                val tint = colors[color % colors.size].copy(alpha = 0.22f)
                for (c in s.paths[color]) {
                    drawRect(tint, Offset((c % n) * cell, (c / n) * cell), Size(cell, cell))
                }
            }
            for (i in 0..n) {
                drawLine(palette.gridLine, Offset(i * cell, 0f), Offset(i * cell, size.height), lineWidth)
                drawLine(palette.gridLine, Offset(0f, i * cell), Offset(size.width, i * cell), lineWidth)
            }
            // Paths: a rounded polyline through the cell centres.
            for (color in s.paths.indices) {
                val p = s.paths[color]
                if (p.size < 2) continue
                val line = Path()
                line.moveTo(center(p[0]).x, center(p[0]).y)
                for (i in 1 until p.size) line.lineTo(center(p[i]).x, center(p[i]).y)
                drawPath(
                    line,
                    colors[color % colors.size],
                    style = Stroke(width = cell * 0.34f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            // A small per-colour shape on every path cell that is not a dot: identity without hue.
            for (color in s.paths.indices) {
                for (c in s.paths[color]) {
                    if (s.puzzle.endpointColor(c) >= 0) continue
                    drawFlowShape(color, center(c), cell * 0.1f, FlowInk.copy(alpha = 0.8f))
                }
            }
            // Dots (their letters are composed over the canvas).
            for ((color, pair) in s.puzzle.pairs.withIndex()) {
                for (e in intArrayOf(pair.a, pair.b)) {
                    drawCircle(colors[color % colors.size], cell * 0.37f, center(e))
                    drawCircle(FlowInk, cell * 0.37f, center(e), style = Stroke(width = cell * 0.04f))
                }
            }
            // The tip being dragged is enlarged so the finger does not hide it.
            if (activeColor != null && activeColor in s.paths.indices && s.paths[activeColor].isNotEmpty()) {
                drawCircle(colors[activeColor % colors.size].copy(alpha = 0.4f), cell * 0.52f, center(s.paths[activeColor].last()))
            }
            if (hintCell != null) {
                // Dark band under a light band so the ring reads on any colour.
                drawCircle(Color.Black, cell * 0.3f, center(hintCell), style = Stroke(width = 6.dp.toPx()))
                drawCircle(Color.White, cell * 0.3f, center(hintCell), style = Stroke(width = 3.dp.toPx()))
            }
        }

        Column {
            for (row in 0 until n) {
                Row {
                    for (col in 0 until n) {
                        val index = row * n + col
                        val dotColor = s.puzzle.endpointColor(index)
                        val owner = if (dotColor >= 0) dotColor else com.gamesuite.games.flowlines.FlowRules.ownerOf(s.paths, index)
                        val hinted = index == hintCell
                        val description = remember(owner, dotColor, hinted, row, col, colorblind) {
                            val base = when {
                                dotColor >= 0 -> androidContext.getString(
                                    R.string.flowlines_cell_dot, row + 1, col + 1, flowColorLabel(androidContext, dotColor, colorblind)
                                )
                                owner >= 0 -> androidContext.getString(
                                    R.string.flowlines_cell_path, row + 1, col + 1, flowColorLabel(androidContext, owner, colorblind)
                                )
                                else -> androidContext.getString(R.string.flowlines_cell_empty, row + 1, col + 1)
                            }
                            if (hinted) base + suggested else base
                        }
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .semantics {
                                    contentDescription = description
                                    role = Role.Button
                                    onClick(label = actionLabel) {
                                        onTapCell(index)
                                        true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (dotColor >= 0) {
                                Text(
                                    FLOW_LETTERS[dotColor % FLOW_LETTERS.length].toString(),
                                    color = FlowInk,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = letterSp,
                                    maxLines = 1,
                                    modifier = Modifier.clearAndSetSemantics {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One of eight distinct marks (circle, square, triangle, diamond, plus, inverted triangle, cross, ring) per colour. */
private fun DrawScope.drawFlowShape(shape: Int, center: Offset, r: Float, color: Color) {
    when (shape % 8) {
        0 -> drawCircle(color, r, center)
        1 -> drawRect(color, Offset(center.x - r, center.y - r), Size(2f * r, 2f * r))
        2 -> {
            val p = Path()
            p.moveTo(center.x, center.y - r * 1.2f)
            p.lineTo(center.x + r * 1.2f, center.y + r)
            p.lineTo(center.x - r * 1.2f, center.y + r)
            p.close()
            drawPath(p, color)
        }
        3 -> {
            val p = Path()
            p.moveTo(center.x, center.y - r * 1.3f)
            p.lineTo(center.x + r * 1.3f, center.y)
            p.lineTo(center.x, center.y + r * 1.3f)
            p.lineTo(center.x - r * 1.3f, center.y)
            p.close()
            drawPath(p, color)
        }
        4 -> {
            drawLine(color, Offset(center.x - r * 1.2f, center.y), Offset(center.x + r * 1.2f, center.y), r * 0.7f)
            drawLine(color, Offset(center.x, center.y - r * 1.2f), Offset(center.x, center.y + r * 1.2f), r * 0.7f)
        }
        5 -> {
            val p = Path()
            p.moveTo(center.x, center.y + r * 1.2f)
            p.lineTo(center.x + r * 1.2f, center.y - r)
            p.lineTo(center.x - r * 1.2f, center.y - r)
            p.close()
            drawPath(p, color)
        }
        6 -> {
            drawLine(color, Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r), r * 0.7f)
            drawLine(color, Offset(center.x - r, center.y + r), Offset(center.x + r, center.y - r), r * 0.7f)
        }
        else -> drawCircle(color, r * 0.85f, center, style = Stroke(width = r * 0.5f))
    }
}

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

@Composable
private fun FlowResultPanel(
    moves: Int,
    hintsUsed: Int,
    timeMillis: Long?,
    record: FlowLinesRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewPuzzle: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: FlowPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.background, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.flowlines_solved_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.flowlines_moves, moves), style = MaterialTheme.typography.bodyMedium)
            if (hintsUsed > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (hintsUsed == 1) {
                        stringResource(R.string.flowlines_result_hint_one)
                    } else {
                        stringResource(R.string.flowlines_result_hint_many, hintsUsed)
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(stringResource(R.string.flowlines_result_assisted), style = MaterialTheme.typography.labelMedium)
            }
            if (timeMillis != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.flowlines_result_time, flowFormatClock(timeMillis)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (isNewBestMoves) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.flowlines_new_best_moves), fontWeight = FontWeight.Bold)
            } else if (record?.bestMoves != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.flowlines_best_moves, record.bestMoves), style = MaterialTheme.typography.bodyMedium)
            }
            if (isNewBestTime) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.flowlines_new_best_time), fontWeight = FontWeight.Bold)
            } else if (record?.bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.flowlines_best_time, flowFormatClock(record.bestTimeMillis)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewPuzzle,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.flowlines_new_puzzle_button), textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.flowlines_back_to_menu), textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(8.dp))
            ChangeSetupButton(
                onLeave = onBackToMenu,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary)
            )
        }
    }
}
