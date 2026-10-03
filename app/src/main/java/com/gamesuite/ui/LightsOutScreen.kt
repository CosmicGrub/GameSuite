package com.gamesuite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.lightsout.LightsOutGame
import com.gamesuite.games.lightsout.LightsOutRecord
import com.gamesuite.games.lightsout.LightsOutState
import com.gamesuite.games.lightsout.LightsOutStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.floor

/** Below this cell size (dp) the board stops shrinking and scrolls/pans instead. */
private const val MIN_CELL_DP = 28f

/** Upper bound so a 3x3 does not sprawl across a tablet (3 x 88 = a 264dp board). A 7x7 is height-limited well before this. */
private const val MAX_CELL_DP = 88f

/** Padding between the board's outer rim and its cells (dp). Real `padding` of the frame AND the `framePx` handed to [fitBoard], so the two can never drift apart. */
private const val BOARD_FRAME_DP = 6f

/** One light fading on or off. Snapped (no animation at all) under [LocalReducedMotion]. */
private const val CELL_FADE_MS = 120

/** How long the last light's fade-out is left on screen before the result panel covers the board. Skipped under [LocalReducedMotion]. */
private const val RESULT_HOLD_MS = 300L

/** The result panel never grows wider than this, so it does not stretch across a tablet. */
private const val RESULT_PANEL_MAX_WIDTH_DP = 360

private const val HELP_TEXT =
    "Tap a light to flip it and the lights directly above, below, left and right of it. " +
        "Diagonal neighbors are not affected, and the edges of the board do not wrap around.\n\n" +
        "Turn every light off to solve the board. Easy is a 3x3 grid, Medium 5x5 and Hard 7x7, " +
        "and every board can always be solved.\n\n" +
        "Your clock starts on your first tap, and your best move count and best time are saved for " +
        "each difficulty. Switching difficulty or tapping New board deals a fresh board; the daily " +
        "board is the same for every player on the same difficulty."

/**
 * Renders LightsOutGame's state reactively — the simplest screen in this new batch, matching the
 * simplest engine: no notes/flag-mode toggle at all, every tap is the same single action (press).
 * Same overall shape as EdgeMatchScreen otherwise (difficulty chips, live status row, a board slot,
 * a solved-board panel). Used for both the free-play route and the daily route ([dailySeed]).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Back to Menu) plus its BackHandler.
 * Leaving mid-board discards ONLY the unfinished board: with nothing solved yet it is a pure
 * `abortMatch()` (never recorded), but once the session has solved boards it goes through
 * `leaveSession()`, the same call the solved panel's "Back to Menu" makes, so those solves still
 * count (the confirm dialog says so). The status row reserves [GameChromeEndInset] so the corner
 * button never covers the "New board" button or the clock.
 *
 * DAILY BOARD: [dailySeed] pins the first board only. "New board" and a difficulty switch always
 * deal a random board again, and the "Daily board" tag disappears the moment that happens.
 *
 * LAYOUT: [fitBoard] against the measured space of the board's own slot, the rim padding handed to
 * it as `framePx`, a [MIN_CELL_DP] to [MAX_CELL_DP] clamp, and a scroll/pan fallback (never a board
 * bigger than its container) when the slot cannot give a cell [MIN_CELL_DP]. Portrait stacks status
 * / chips / board; a wide landscape window puts the board on the left and status and chips in a
 * column on the right. The solved panel is an overlay on the board's own slot, so the board never
 * resizes at the moment of the win.
 *
 * CLOCK: shown by its own child composable ([LightsOutClock]) reading [LightsOutGame.activeElapsedMillis],
 * so the 5Hz tick recomposes only that Text (not the grid) and the display freezes while the engine
 * is paused and ends on exactly the recorded solve time.
 *
 * ACCESSIBILITY: every light is one button described by its state plus 1-indexed row and column
 * (matching ReversiScreen.CellView), with its whole cell as the touch target (the lit tile is just
 * drawn inset inside it); the moves counter is a polite live region that also reads how many lights
 * are still on; difficulty chips are radio buttons; every non-grid control is at least 48dp. Lit
 * versus unlit is carried by luminance (a bright amber glow against a near-black, outlined tile),
 * never by hue alone, so no [com.gamesuite.settings.LocalColorblindMode] branch is needed here.
 *
 * MOTION: a light fades on or off over 120ms (the win is the last light fading out, then a short
 * hold before the result panel appears); both are off under [LocalReducedMotion]. Solving plays the
 * success chime and celebration haptic (a solve is always the human's).
 *
 * VISUAL IDENTITY: shares its warm background/accent tokens with MinesweeperScreen/SudokuScreen
 * (see [lightsOutPalette]) for one consistent "new games" identity across this batch. Never reads
 * `MaterialTheme.colorScheme` for gameplay colors, same standing rule as every other game's board
 * (the result panel and chips are painted from the palette too, so the cream page never shows the
 * default Material card/button colors).
 */
@Composable
fun LightsOutScreen(
    sessionManager: GameSessionManager,
    game: LightsOutGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see LightsOutGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.LIGHTS_OUT, enabled = musicEnabled)
    val statsStore = remember { LightsOutStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = lightsOutPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current

    // True while the board on screen is the seeded daily board. Any later fresh board (a tier
    // switch, "New board") is random again, and the screen says so.
    var dailyBoardActive by remember { mutableStateOf(dailySeed != null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyBoardActive = dailySeed != null
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name]
    // Keyed on the actual board (unique per round, stable across moves of the SAME round) --
    // `.size` alone doesn't change between two rounds of the same difficulty, which let round
    // 1's result silently keep showing on every later round -- the same fix WordGuess's own
    // reportedResult already applies (see that screen's own comment), mirrored here.
    var reportedResult by remember(s.cells, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    val litCount = remember(s.cells) { s.cells.count { it } }

    LaunchedEffect(s.won) {
        if (!s.won) return@LaunchedEffect
        // A solve is always the human's (this game has no opponent), so the celebration is unconditional.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.difficulty, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
        }
    }

    // The result panel covers the board, so it waits a beat for the last light's fade-out to be seen.
    var resultHoldDone by remember { mutableStateOf(false) }
    LaunchedEffect(s.isOver, reducedMotion) {
        if (!s.isOver) {
            resultHoldDone = false
            return@LaunchedEffect
        }
        if (!reducedMotion) delay(RESULT_HOLD_MS)
        resultHoldDone = true
    }
    val showResult = s.isOver && resultHoldDone

    val onTapCell: (Int) -> Unit = { index ->
        // Re-read the live state: the flag a cell was composed with can be a frame stale, and a
        // tap on a solved board (or after the session ended) must not buzz, click or count.
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value) {
            game.press(index)
            sounds.playTap()
            haptics(HapticSignal.NORMAL_ACTION)
        }
    }
    val startNewBoard: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            game.playAgain()
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        // Switching difficulty always deals a fresh board immediately (same as New board) -- no
        // reason to block this mid-puzzle or once one is already solved.
        if (!game.matchOver.value && tier != game.difficulty) {
            dailyBoardActive = false
            game.difficulty = tier
            game.startMatch()
        }
    }

    // "Finished units" for the abort policy: boards already solved this session. If any exist,
    // leaving mid-board must still score them -- see onAbort below.
    val finishedBoards = game.puzzlesSolved.value

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-board discards
    // ONLY the unfinished board: if boards were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved board leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Lights Out",
        helpText = HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this board?",
        leaveBody = if (finishedBoards > 0) {
            "This board is still unsolved and won't count, but the boards you've already solved stay on your record."
        } else {
            "This board is still unsolved. Leaving now won't count it as a win or a loss."
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp)
        ) {
            // Board left + controls right only when the window is genuinely wide; every phone
            // portrait, the Fold cover screen included, takes the stacked layout.
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                LightsOutStatus(
                    moves = s.moves,
                    litCount = litCount,
                    daily = dailyBoardActive,
                    game = game,
                    onNewBoard = startNewBoard,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                DifficultyTabs(current = game.difficulty, palette = palette, onSelect = onSelectTier)
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                LightsOutBoardSlot(
                    s = s,
                    palette = palette,
                    reducedMotion = reducedMotion,
                    showResult = showResult,
                    onTapCell = onTapCell,
                    modifier = slotModifier,
                    resultPanel = { panelModifier ->
                        LightsOutResultPanel(
                            moves = s.moves,
                            timeMillis = game.finishedElapsedMillis.value,
                            record = record,
                            isNewBestMoves = reportedResult?.first ?: false,
                            isNewBestTime = reportedResult?.second ?: false,
                            onNewBoard = startNewBoard,
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
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The status block is the top-right occupant of this layout, so it keeps the
                    // corner button's width free at its end.
                    statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                    Spacer(Modifier.height(4.dp))
                    tiersRow()
                    Spacer(Modifier.height(8.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette/SudokuPalette (see this file's class KDoc for why this
// doesn't read MaterialTheme.colorScheme), extended with an "on" glow color and
// the rim/edge tokens that give the board a visible extent in the dark theme
// (an unlit cell there is only ~1.06:1 against the page on its own).
// ---------------------------------------------------------------------------
private data class LightsOutPalette(
    val background: Color,
    val cellOff: Color,
    /** The 1.5dp outline that makes an unlit tile read as a raised tile (fades out as the light comes on). */
    val cellOffEdge: Color,
    val cellOn: Color,
    val cellOnGlow: Color,
    /** Fill behind the grid, inside the rim. */
    val boardWell: Color,
    /** The 2dp rim around the board. */
    val boardRim: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color
)

private val LightsOutLightPalette = LightsOutPalette(
    background = Color(0xFFFBF1E6),
    cellOff = Color(0xFF3A2E22),
    cellOffEdge = Color(0xFF5E4C39),
    cellOn = Color(0xFFF5C453),
    cellOnGlow = Color(0xFFFFE39A),
    boardWell = Color(0xFF241B12),
    boardRim = Color(0xFF241B12),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9)
)

private val LightsOutDarkPalette = LightsOutPalette(
    background = Color(0xFF1C1712),
    cellOff = Color(0xFF15110D),
    cellOffEdge = Color(0xFF8A7A66),
    cellOn = Color(0xFFF0B840),
    cellOnGlow = Color(0xFFFFD873),
    boardWell = Color(0xFF0E0B08),
    boardRim = Color(0xFF7A6853),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E)
)

/** Shared instances, so a recomposition never allocates a fresh palette again. */
private fun lightsOutPalette(isDark: Boolean): LightsOutPalette = if (isDark) LightsOutDarkPalette else LightsOutLightPalette

private fun formatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

// ---------------------------------------------------------------------------
// Difficulty chips, status row, clock
// ---------------------------------------------------------------------------

/**
 * The EASY/MEDIUM/HARD chips. Each is a two-line pill (name over grid size) so all three fit a
 * 288dp-wide content area (the Fold cover screen); `horizontalScroll` is the fallback for a window
 * too narrow for them at a large font scale, so a chip is never compressed to a sliver.
 */
@Composable
private fun DifficultyTabs(current: CpuDifficulty, palette: LightsOutPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            val grid = when (tier) {
                CpuDifficulty.EASY -> "3x3"
                CpuDifficulty.MEDIUM -> "5x5"
                CpuDifficulty.HARD -> "7x7"
            }
            DifficultyChip(
                label = label,
                grid = grid,
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill chip at least 48dp tall, announced as a radio button. */
@Composable
private fun DifficultyChip(label: String, grid: String, selected: Boolean, palette: LightsOutPalette, onClick: () -> Unit) {
    val contentColor = if (selected) palette.textOnAccent else palette.textPrimary
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) palette.accent else palette.chipBackground)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                grid,
                color = contentColor.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * Moves, the clock and the "New board" button on one line (plus a "Daily board" tag when today's
 * seeded board is the one on screen). At least 48dp tall so the layout below it clears the corner
 * button. Callers give it `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun LightsOutStatus(
    moves: Int,
    litCount: Int,
    daily: Boolean,
    game: LightsOutGame,
    onNewBoard: () -> Unit,
    palette: LightsOutPalette,
    modifier: Modifier = Modifier
) {
    val movesWords = if (moves == 1) "1 move" else "$moves moves"
    val lightsWords = if (litCount == 1) "1 light on" else "$litCount lights on"
    Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Text(
                "Daily board",
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
            Text(
                "Moves: $moves",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A polite live region so a screen reader announces progress after each press,
                // including how many lights are still on (the one number a player is chasing to 0).
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "$movesWords, $lightsWords"
                    }
            )
            LightsOutClock(game = game, palette = palette)
            // Matches the shape/size of the corner menu button right next to it.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, palette.textPrimary.copy(alpha = 0.35f), CircleShape)
                    .semantics { contentDescription = "New board" }
                    .clickable(onClickLabel = "Deal a new board", role = Role.Button, onClick = onNewBoard),
                contentAlignment = Alignment.Center
            ) {
                // The glyph is decoration; without this a screen reader reads "clockwise open circle arrow".
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

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen and every cell (the old version read the clock at the screen root). Reads
 * [LightsOutGame.activeElapsedMillis], so it freezes while the engine is paused (app backgrounded)
 * and ends on exactly the recorded solve time instead of jumping back at the solve.
 */
@Composable
private fun LightsOutClock(game: LightsOutGame, palette: LightsOutPalette) {
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
        formatClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The board's own slot. Sizes from THIS slot's measured space (not an outer scope) with
 * [fitBoard], which never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(28.dp)` floor (which let a board exceed its container) is gone. When the slot
 * cannot give a cell [MIN_CELL_DP] the board keeps that size and scrolls/pans in both axes instead
 * of shrinking further. The result panel is drawn over the board, inside this slot, so it never
 * competes with the board for layout space.
 */
@Composable
private fun LightsOutBoardSlot(
    s: LightsOutState,
    palette: LightsOutPalette,
    reducedMotion: Boolean,
    showResult: Boolean,
    onTapCell: (Int) -> Unit,
    resultPanel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Read here: inside the nested Box/Column lambdas below, this scope's maxHeight is not implicitly reachable.
        val slotMaxHeight = maxHeight
        val n = s.size
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = n,
            rows = n,
            framePx = BOARD_FRAME_DP,
            minCellPx = MIN_CELL_DP,
            maxCellPx = MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed by
        // one more: n rounded-up cells plus the rounded frame would otherwise overshoot the slot by
        // a pixel or two and squeeze the last column.
        val cellSize: Dp = if (scrolls) {
            MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }
        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.boardWell)
                    .border(2.dp, palette.boardRim, RoundedCornerShape(14.dp))
                    .padding(BOARD_FRAME_DP.dp)
            ) {
                Column {
                    for (row in 0 until n) {
                        Row {
                            for (col in 0 until n) {
                                val index = row * n + col
                                LightsOutCellView(
                                    lit = s.cells[index],
                                    enabled = !s.isOver,
                                    row = row,
                                    col = col,
                                    cellSize = cellSize,
                                    palette = palette,
                                    reducedMotion = reducedMotion,
                                    onTap = { onTapCell(index) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showResult) {
            resultPanel(
                Modifier
                    .widthIn(max = RESULT_PANEL_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    // Capped to the slot so even a large font scale or a short landscape window
                    // never pushes the buttons out of reach; the panel scrolls inside its cap.
                    .heightIn(max = slotMaxHeight)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/**
 * One light. The WHOLE [cellSize] square is the touch target and the accessibility node; the tile
 * itself is drawn inset by 3dp inside it (that gap is what separates neighbours), so the target is
 * never smaller than the cell pitch. Described like ReversiScreen.CellView: state first, then
 * 1-indexed row and column.
 *
 * Unlit: a near-black tile with a 1.5dp outline (so the board has a visible extent even where the
 * tile is only ~1.06:1 against the page in the dark theme). Lit: a radial glow from
 * [LightsOutPalette.cellOnGlow] at the centre to [LightsOutPalette.cellOn] at the edge. The two
 * cross-fade over [CELL_FADE_MS]; [reducedMotion] snaps instead. The default rectangular ripple is
 * replaced by a faint white wash while pressed, since a square ripple over a rounded, inset tile
 * (plus its gap) looks wrong.
 */
@Composable
private fun LightsOutCellView(
    lit: Boolean,
    enabled: Boolean,
    row: Int,
    col: Int,
    cellSize: Dp,
    palette: LightsOutPalette,
    reducedMotion: Boolean,
    onTap: () -> Unit
) {
    // Mirrors CheckersScreen's own squareDescription convention (same state/row/column phrasing,
    // 1-indexed for a human reader) rather than inventing a new one.
    val description = remember(lit, row, col) {
        "${if (lit) "Light on" else "Light off"}, row ${row + 1}, column ${col + 1}"
    }
    val glow by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = if (reducedMotion) snap<Float>() else tween<Float>(CELL_FADE_MS),
        label = "lights-out-glow"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(cellSize)
            .clickable(
                enabled = enabled,
                onClickLabel = "Flip this light and its neighbors",
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onTap
            )
            .semantics { contentDescription = description }
            .drawBehind {
                val inset = 3.dp.toPx()
                val tileTopLeft = Offset(inset, inset)
                val tileSize = Size(size.width - 2f * inset, size.height - 2f * inset)
                val corner = CornerRadius((tileSize.minDimension * 0.16f).coerceIn(3.dp.toPx(), 10.dp.toPx()))
                drawRoundRect(color = palette.cellOff, topLeft = tileTopLeft, size = tileSize, cornerRadius = corner)
                if (glow < 1f) {
                    val stroke = 1.5f.dp.toPx()
                    drawRoundRect(
                        color = palette.cellOffEdge,
                        topLeft = Offset(inset + stroke / 2f, inset + stroke / 2f),
                        size = Size(tileSize.width - stroke, tileSize.height - stroke),
                        cornerRadius = corner,
                        style = Stroke(width = stroke),
                        alpha = 1f - glow
                    )
                }
                if (glow > 0f) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(palette.cellOnGlow, palette.cellOn),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = tileSize.minDimension * 0.75f
                        ),
                        topLeft = tileTopLeft,
                        size = tileSize,
                        cornerRadius = corner,
                        alpha = glow
                    )
                }
                if (pressed) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = tileTopLeft,
                        size = tileSize,
                        cornerRadius = corner,
                        alpha = 0.18f
                    )
                }
            }
    )
}

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

@Composable
private fun LightsOutResultPanel(
    moves: Int,
    timeMillis: Long?,
    record: LightsOutRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: LightsOutPalette,
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
                "Lights out!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // A polite live region so a screen reader announces the win when the panel appears.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text("Moves: $moves", style = MaterialTheme.typography.bodyMedium)
            if (timeMillis != null) {
                Spacer(Modifier.height(2.dp))
                Text("Time: ${formatClock(timeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (isNewBestMoves) {
                Spacer(Modifier.height(4.dp))
                Text("New best move count!", fontWeight = FontWeight.Bold)
            } else if (record?.bestMoves != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best moves: ${record.bestMoves}", style = MaterialTheme.typography.bodyMedium)
            }
            if (isNewBestTime) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (record?.bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best time: ${formatClock(record.bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewBoard,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Board", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Back to Menu", textAlign = TextAlign.Center) }
            }
        }
    }
}
