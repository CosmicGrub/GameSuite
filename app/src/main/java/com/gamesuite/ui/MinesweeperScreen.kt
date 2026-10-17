package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.minesweeper.CellState
import com.gamesuite.games.minesweeper.MinesweeperCell
import com.gamesuite.games.minesweeper.MinesweeperGame
import com.gamesuite.games.minesweeper.MinesweeperState
import com.gamesuite.games.minesweeper.MinesweeperStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Below this cell size (dp) the board stops shrinking and pans instead. Cells are tappable, so this
 * is the tap-target floor for a grid: 28dp is about as small as a fingertip can hit reliably. The
 * old `coerceAtLeast(20.dp)` floor could make a board wider than its container and left Medium and
 * Hard at 17dp cells; neither is possible any more.
 */
private const val MINES_MIN_CELL_DP = 28f

/** Upper bound so a small board does not sprawl across a tablet (Hard is 30 x 48 = a 1440dp board). */
private const val MINES_MAX_CELL_DP = 48f

/** How long the finished board is left alone before the result panel replaces the mode toggle. */
private const val MINES_RESULT_HOLD_MS = 600L

/** A planted flag pops in over this long, from [MINES_FLAG_POP_FROM] to full size. Off under reduced motion. */
private const val MINES_FLAG_POP_MS = 140
private const val MINES_FLAG_POP_FROM = 0.4f

/** The board shake a lost board plays. Off under reduced motion. */
private const val MINES_LOSS_SHAKE_MS = 320
private const val MINES_LOSS_SHAKE_DP = 6f

/** A reveal that opens at least this many squares at once gets the stronger haptic. */
private const val MINES_BIG_CASCADE_CELLS = 12

private const val MINES_HELP_TEXT =
    "Tap a square to reveal it, and reveal every square that is not a mine to win; reveal a mine " +
        "and the board is lost. A number tells you how many of the 8 squares around it hold a " +
        "mine, and a blank square opens its neighbors for you.\n\n" +
        "Your first reveal is always safe, because mines are placed afterwards, away from that " +
        "square and its neighbors. To mark a suspected mine, choose Flag in the Reveal / Flag switch or press " +
        "and hold a square; a flagged square can't be revealed until its flag is removed.\n\n" +
        "Easy is 9x9 with 10 mines, Medium is 16x16 with 40 and Hard is 16x30 with 99. The clock " +
        "starts on your first reveal, and a board too big for the screen pans when you drag it."

private const val MINES_DAILY_HELP_TEXT =
    "\n\nThe Daily board is seeded by today's date, so on the same difficulty players who " +
        "reveal the same square first face the same mines. Switching difficulty or starting a new " +
        "board leaves it."

/**
 * Renders MinesweeperGame's state reactively: tier chips, a live mines-left / clock status block, the
 * board, a Reveal / Flag mode toggle and a finished-board result panel.
 *
 * BOARD: one [MinesweeperCellView] per square, sized with [fitBoard] against the measured space of
 * the board's OWN slot (not an outer scope), clamped to [MINES_MIN_CELL_DP]..[MINES_MAX_CELL_DP]. When
 * the slot cannot give a cell [MINES_MIN_CELL_DP] the board keeps that size and pans in both axes
 * (Hard, 16x30, always does on a phone; Medium does below roughly 450dp of width); otherwise the
 * whole board fits, centred. The old `coerceAtLeast(20.dp)` floor and the outer-scope sizing are gone.
 * The tap target is the WHOLE cell, gap included (the old click target sat inside 1.5dp of padding),
 * and the live clock lives in its own child so its 5Hz tick no longer recomposes the 480 cells.
 *
 * SHAPES, NOT JUST COLOUR: the emoji glyphs are gone. A flag is a pole with a pennant, a mine is a
 * spiked ball, the detonated mine sits on a starburst, and a wrong flag (marked once a board is lost)
 * is a flag struck through with an X. A hidden square is RAISED (a light top-left and dark
 * bottom-right edge) and a revealed one is flat, so hidden vs revealed no longer rests on a 1.2:1 fill
 * difference alone. The adjacent-mine DIGIT is the cue for a count and its colour is only a hint; the
 * light-theme 2, 3 and 6 were darkened so every digit is at least 4.5:1 on the revealed fill in both
 * themes, and digits are sized in px (not scaled sp) so large font settings can't clip them.
 *
 * LOSS: [MinesweeperState.explodedIndex] marks the mine that ended the board, flagged mines keep their
 * flags, wrong flags are struck through, and the board shakes ([MINES_LOSS_SHAKE_MS], off under
 * [LocalReducedMotion]). The result panel waits [MINES_RESULT_HOLD_MS] so the revealed board is seen
 * first. On a WIN the remaining mines show as flags and the counter reads 0.
 *
 * INPUT: tap acts per the mode toggle (Reveal, or Flag); press-and-hold flags in either mode. The
 * toggle is two 48dp radio buttons under the board. A tap on a revealed square does nothing (this
 * engine has no chording, a documented scope cut); a reveal tap on a flagged square buzzes instead of
 * silently doing nothing. Reveals escalate their haptic with how many squares they open.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, New board, Back to Menu) plus its
 * BackHandler. A "finished unit" here is a board that was won OR lost ([MinesweeperGame.puzzlesSolved]
 * + [MinesweeperGame.puzzlesFailed]). Leaving mid-board discards ONLY the unfinished board: with
 * nothing finished it is a pure `abortMatch()` (never recorded); once the session has finished boards
 * it goes through `leaveSession()`, the same call the result panel's "Back to Menu" makes, so those
 * boards still count (the confirm dialog says so). The old inline refresh button is now the menu's
 * "New board". The status block reserves [GameChromeEndInset] so the corner button never covers the
 * clock.
 *
 * DAILY BOARD: when [dailySeed] is non-null the first board is today's seeded one, tagged "Daily
 * board". Any later fresh board (a tier switch, "New board") is random again and the tag goes away;
 * switching tier or starting a new board away from a board in progress (a reveal or flag made, or an
 * untouched daily) asks first.
 *
 * LAYOUT: portrait stacks status / tiers / board / mode toggle (or result panel). A wide landscape
 * window puts the board on the left and status, tiers and toggle / panel in a scrolling column on the
 * right.
 *
 * ACCESSIBILITY: every hidden or flagged square is one button described by its state plus row and
 * column, 1-indexed ("Hidden, row 3, column 4"), with a click label that follows the mode and a
 * long-click label for the flag; a revealed square is plain text ("2 adjacent mines, row 3, column
 * 4"); the glyph inside a square is decoration. The mines-left counter is a polite live region, the
 * tier chips and mode toggle are radio buttons, and every non-grid control is at least 48dp. A lost
 * board still reads its mines, wrong flags and detonated square.
 *
 * SOUND: a win plays the success chime and celebration haptic; a loss gets the failure haptic and a
 * buzz, never the celebration. The clock reads [MinesweeperGame.activeElapsedMillis], which freezes
 * while the engine is paused.
 *
 * VISUAL IDENTITY: the warm cream-and-terracotta palette is unchanged apart from the digit and
 * on-accent contrast fixes and the bevel edge tokens; see [MinesweeperPalette].
 */
@Composable
fun MinesweeperScreen(
    sessionManager: GameSessionManager,
    game: MinesweeperGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see MinesweeperGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MINESWEEPER, enabled = musicEnabled)
    val statsStore = remember { MinesweeperStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = minesweeperPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current

    var flagMode by remember { mutableStateOf(false) }
    // True while the board on screen is the seeded daily board. Any later fresh board (a tier
    // switch, "New board") is random again, and the screen says so.
    var dailyBoardActive by remember { mutableStateOf(dailySeed != null) }
    // A tier chip or "New board" tapped while a board is in progress waits here for confirmation.
    var pendingTier by remember { mutableStateOf<CpuDifficulty?>(null) }
    var pendingNewBoard by remember { mutableStateOf(false) }

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

    val allBestTimes by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestTimeMillis = allBestTimes[game.difficulty.name]

    // Per-board bookkeeping keyed on the engine's own board counter (new for every fresh board,
    // stable across the moves of the SAME board): the board whose end-of-board effects have already
    // fired, and the board a new best time was set on.
    val board = game.boardNumber.value
    var endHandledBoard by remember { mutableStateOf(-1) }
    var newBestBoard by remember { mutableStateOf(-1) }

    // The result panel waits for the board to be seen before it replaces the mode toggle. It is a
    // state of its own (not just `s.isOver`) so the hold survives recompositions.
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(s.isOver) {
        if (s.isOver) {
            if (!reducedMotion) delay(MINES_RESULT_HOLD_MS)
            showResult = true
        } else {
            showResult = false
        }
    }

    LaunchedEffect(s.isOver, board) {
        if (!s.isOver || endHandledBoard == board) return@LaunchedEffect
        endHandledBoard = board
        if (s.won) {
            // The only player won: celebrate first, then write the stats.
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            if (statsStore.recordWin(game.difficulty, finalTime)) newBestBoard = board
        } else {
            // A lost board never gets the celebration: a failure haptic and a buzz only.
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        }
    }

    // Both handlers re-read the live state: the cell a tap landed on can be a frame stale, and a tap
    // on a finished board (or after the session ended) must not buzz, click or count.
    val onCellTap: (Int) -> Unit = { index ->
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value && index in live.cells.indices) {
            val cell = live.cells[index]
            when {
                flagMode -> {
                    // A tap on a revealed square in Flag mode does nothing, and says nothing.
                    if (cell.state != CellState.REVEALED) {
                        game.toggleFlag(index)
                        haptics(HapticSignal.LIGHT_TICK)
                    } else {
                        Unit
                    }
                }
                cell.state == CellState.HIDDEN -> {
                    val revealedBefore = live.cells.count { it.state == CellState.REVEALED }
                    game.revealCell(index)
                    val after = game.state.value
                    // The end of the board gets its own sound and haptic from the end effect above.
                    if (after != null && !after.isOver) {
                        sounds.playTap()
                        val opened = after.cells.count { it.state == CellState.REVEALED } - revealedBefore
                        haptics(if (opened >= MINES_BIG_CASCADE_CELLS) HapticSignal.STRONG_ACTION else HapticSignal.NORMAL_ACTION)
                    }
                }
                // A flagged square can't be revealed until its flag is removed: say so.
                cell.state == CellState.FLAGGED -> haptics(HapticSignal.FAILURE)
                else -> Unit
            }
        }
    }
    val onCellLongPress: (Int) -> Unit = { index ->
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value && index in live.cells.indices &&
            live.cells[index].state != CellState.REVEALED
        ) {
            game.toggleFlag(index)
            haptics(HapticSignal.LIGHT_TICK)
        }
    }

    val applyTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value) {
            dailyBoardActive = false
            flagMode = false
            game.difficulty = tier
            game.startMatch()
        }
    }
    val startNewBoard: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            flagMode = false
            game.playAgain()
        }
    }
    // Only a board with a reveal or a flag on it (or an untouched daily one, which a switch would
    // lose for good) is worth a confirmation; a finished board, or a blank random one, can be left
    // behind freely.
    val boardInProgress = !s.isOver &&
        (game.timerStartElapsedRealtime.value != null || s.flagCount > 0 || dailyBoardActive)
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (tier != game.difficulty) {
            if (boardInProgress) pendingTier = tier else applyTier(tier)
        }
    }

    val minesLeft = if (s.won) 0 else s.mineCount - s.flagCount
    // "Finished units" for the abort policy: boards already won OR lost this session. If any exist,
    // leaving mid-board must still score them -- see onAbort below.
    val finishedBoards = game.puzzlesSolved.value + game.puzzlesFailed.value

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-board discards
    // ONLY the unfinished board: if boards were already finished this session, leaving goes through
    // leaveSession() (the same call the result panel's "Back to Menu" makes) so they still count;
    // with nothing finished it is a pure abort (never a win or loss). A finished board leaves
    // through leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Minesweeper",
        helpText = if (dailySeed != null) MINES_HELP_TEXT + MINES_DAILY_HELP_TEXT else MINES_HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this board?",
        leaveBody = if (finishedBoards > 0) {
            "This board is still unfinished and won't count, but the boards you've already finished stay on your record."
        } else {
            "This board is still unfinished. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text(if (dailyBoardActive) "New board (leaves the daily)" else "New board") },
                onClick = {
                    dismiss()
                    if (boardInProgress) pendingNewBoard = true else startNewBoard()
                }
            )
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
            val sideBySide = maxWidth >= 640.dp && maxWidth > maxHeight * 1.2f
            // Read here: inside the Column below, this scope's maxHeight is not implicitly reachable.
            val areaMaxHeight = maxHeight

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                MinesweeperStatus(
                    minesLeft = minesLeft,
                    daily = dailyBoardActive,
                    game = game,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                MinesweeperTiers(current = game.difficulty, palette = palette, onSelect = onSelectTier)
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                MinesweeperBoardSlot(
                    s = s,
                    flagMode = flagMode,
                    palette = palette,
                    reducedMotion = reducedMotion,
                    onCellTap = onCellTap,
                    onCellLongPress = onCellLongPress,
                    modifier = slotModifier
                )
            }
            val bottomPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                if (s.isOver && showResult) {
                    MinesweeperResultPanel(
                        won = s.won,
                        timeMillis = game.finishedElapsedMillis.value,
                        clearedSafe = s.cells.count { it.state == CellState.REVEALED && !it.isMine },
                        totalSafe = s.rows * s.cols - s.mineCount,
                        bestTimeMillis = bestTimeMillis,
                        isNewBest = newBestBoard == board,
                        daily = dailyBoardActive,
                        onNewBoard = startNewBoard,
                        onBackToMenu = { game.leaveSession() },
                        palette = palette,
                        modifier = panelModifier
                    )
                } else {
                    // Dimmed (and inert) while a finished board is being looked at before the panel lands.
                    MinesweeperModeToggle(
                        flagMode = flagMode,
                        enabled = !s.isOver,
                        palette = palette,
                        onSelect = { flagMode = it },
                        modifier = panelModifier
                    )
                }
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The status block is the top-right occupant of this layout.
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        bottomPanel(Modifier.fillMaxWidth())
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
                    Spacer(Modifier.height(10.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    // Capped and scrollable so a result panel on a very short window never runs off
                    // the bottom of the screen.
                    bottomPanel(
                        Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .heightIn(max = areaMaxHeight * 0.55f)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    pendingTier?.let { tier ->
        MinesweeperDiscardDialog(
            title = "Switch to ${minesweeperTierLabel(tier)}?",
            body = if (dailyBoardActive) {
                "This starts a new random board and leaves today's daily board. Your progress on this board is discarded."
            } else {
                "This starts a new board. Your progress on this board is discarded."
            },
            confirmLabel = "Switch",
            palette = palette,
            onConfirm = {
                pendingTier = null
                applyTier(tier)
            },
            onDismiss = { pendingTier = null }
        )
    }
    if (pendingNewBoard) {
        MinesweeperDiscardDialog(
            title = "Start a new board?",
            body = if (dailyBoardActive) {
                "This leaves today's daily board for a new random one. Your progress on this board is discarded."
            } else {
                "Your progress on this board is discarded."
            },
            confirmLabel = "New Board",
            palette = palette,
            onConfirm = {
                pendingNewBoard = false
                startNewBoard()
            },
            onDismiss = { pendingNewBoard = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan/Tessel-inspired palette. This screen's gameplay colors deliberately don't come
// from MaterialTheme.colorScheme (see the project's standing rule on gameplay-meaningful colors).
// ---------------------------------------------------------------------------
private data class MinesweeperPalette(
    val background: Color,
    val hiddenCell: Color,
    /** The raised hidden cell's light top-left edge. */
    val hiddenHighlight: Color,
    /** The raised hidden cell's bottom-right edge; at least 3:1 on the revealed fill in both themes. */
    val hiddenShadow: Color,
    val revealedCell: Color,
    val accent: Color,
    val chipBackground: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val danger: Color,
    /** The flag pennant; at least 4:1 on the hidden fill. */
    val flag: Color,
    /** The starburst behind the detonated mine. */
    val burst: Color,
    /** The mine glyph on the detonated cell's red fill. */
    val mineInk: Color,
    /** Index 0 unused (a 0 renders blank), 1..8. Each is at least 4.5:1 on [revealedCell]. */
    val numberColors: List<Color>
)

private val MinesweeperLightPalette = MinesweeperPalette(
    background = Color(0xFFFBF1E6),
    hiddenCell = Color(0xFFF3E4D2),
    hiddenHighlight = Color(0xFFFFFBF5),
    hiddenShadow = Color(0xFF9C7F57),
    revealedCell = Color(0xFFFFFBF5),
    accent = Color(0xFFE08D4B),
    chipBackground = Color(0xFFE3CBA9),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1 on the selected chip.
    textOnAccent = Color(0xFF2B1F12),
    danger = Color(0xFFD9573F),
    flag = Color(0xFFC0432C),
    burst = Color(0xFFFFE3A3),
    mineInk = Color(0xFF15110D),
    numberColors = listOf(
        Color.Unspecified,
        Color(0xFF3B6FD4), Color(0xFF2F7A49), Color(0xFFC0432C), Color(0xFF6B4E9E),
        Color(0xFF9A3B3B), Color(0xFF1F7676), Color(0xFF3A2E22), Color(0xFF7A7167)
    )
)

private val MinesweeperDarkPalette = MinesweeperPalette(
    background = Color(0xFF1C1712),
    hiddenCell = Color(0xFF2B241D),
    hiddenHighlight = Color(0xFF6E5F4E),
    hiddenShadow = Color(0xFF0F0C09),
    revealedCell = Color(0xFF15110D),
    accent = Color(0xFFE8985B),
    chipBackground = Color(0xFF453A2E),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    danger = Color(0xFFE0705A),
    flag = Color(0xFFE0705A),
    burst = Color(0xFFFFE3A3),
    mineInk = Color(0xFF15110D),
    numberColors = listOf(
        Color.Unspecified,
        Color(0xFF7FA6F0), Color(0xFF7ECB98), Color(0xFFE68A73), Color(0xFFB199D9),
        Color(0xFFCE8A8A), Color(0xFF7FC6C6), Color(0xFFF3E9DB), Color(0xFFA89E92)
    )
)

/** Shared instances, so a recomposition never allocates a fresh palette (and its list) again. */
private fun minesweeperPalette(isDark: Boolean): MinesweeperPalette =
    if (isDark) MinesweeperDarkPalette else MinesweeperLightPalette

// ---------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------

private fun minesweeperTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

private fun minesFormatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

// ---------------------------------------------------------------------------
// Tier chips, status block, clock
// ---------------------------------------------------------------------------

@Composable
private fun MinesweeperTiers(current: CpuDifficulty, palette: MinesweeperPalette, onSelect: (CpuDifficulty) -> Unit) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text, so a
    // chip is never compressed to a sliver; the row is simply scrolled into view.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        for (tier in CpuDifficulty.entries) {
            MinesweeperTab(
                label = minesweeperTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun MinesweeperTab(label: String, selected: Boolean, palette: MinesweeperPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) palette.accent else palette.chipBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) palette.textOnAccent else palette.textPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * Mines left and the clock on one line (plus a "Daily board" tag when today's seeded board is the one
 * on screen). At least 48dp tall so the layout below it clears the corner button. Callers give it
 * `padding(end = GameChromeEndInset)`. The mines-left counter is a polite live region, so a
 * screen-reader player hears it change with every flag.
 */
@Composable
private fun MinesweeperStatus(
    minesLeft: Int,
    daily: Boolean,
    game: MinesweeperGame,
    palette: MinesweeperPalette,
    modifier: Modifier = Modifier
) {
    val spoken = if (minesLeft == 1) "1 mine left" else "$minesLeft mines left"
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                }
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    drawMineGlyph(ink = palette.textPrimary, shine = palette.background)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "$minesLeft",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            MinesweeperClock(game = game, palette = palette)
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the screen and the board (the old version read the clock at the screen root). Reads
 * [MinesweeperGame.activeElapsedMillis], so it freezes while the engine is paused (app backgrounded)
 * and ends on exactly the recorded time.
 */
@Composable
private fun MinesweeperClock(game: MinesweeperGame, palette: MinesweeperPalette) {
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
        minesFormatClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The board slot. Sizes from THIS slot's own measured space (not an outer scope) with [fitBoard],
 * which never returns a footprint larger than the space it is given. When the slot cannot give a
 * cell [MINES_MIN_CELL_DP] the board keeps that size and pans in both axes; otherwise the cell size
 * is floored to a whole pixel so rounding can never push the board past its container.
 *
 * Owns the loss shake, because it owns the board's layer.
 */
@Composable
private fun MinesweeperBoardSlot(
    s: MinesweeperState,
    flagMode: Boolean,
    palette: MinesweeperPalette,
    reducedMotion: Boolean,
    onCellTap: (Int) -> Unit,
    onCellLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val shake = rememberCameraShake()
    val shakePx = with(density) { MINES_LOSS_SHAKE_DP.dp.toPx() }

    // A different board size starts from the top-left, not wherever the last one was panned to.
    LaunchedEffect(s.rows, s.cols) {
        hScroll.scrollTo(0)
        vScroll.scrollTo(0)
    }
    LaunchedEffect(s.exploded) {
        if (s.exploded && !reducedMotion) {
            shake.trigger(durationMs = MINES_LOSS_SHAKE_MS, easing = FastOutSlowInEasing)
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = remember(maxWidth, maxHeight, s.rows, s.cols) {
            fitBoard(
                availableWidthPx = maxWidth.value,
                availableHeightPx = maxHeight.value,
                columns = s.cols,
                rows = s.rows,
                minCellPx = MINES_MIN_CELL_DP,
                maxCellPx = MINES_MAX_CELL_DP
            )
        }
        val scrolls = !fit.meetsMinimum
        val cellDp: Dp = if (scrolls) MINES_MIN_CELL_DP.dp else with(density) { floor(fit.cellPx.dp.toPx()).toDp() }
        // Digits are sized in px (dp converted to sp with the font scale divided back out), so a large
        // system font size cannot make a digit taller than its cell and clip.
        val digitSp: TextUnit = with(density) { (cellDp * 0.52f).toSp() }
        val digitLineSp: TextUnit = with(density) { (cellDp * 0.62f).toSp() }

        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Column(
                // Shakes the whole grid as one physical board.
                modifier = if (reducedMotion) Modifier else Modifier.cameraShake(shake, magnitudePx = shakePx)
            ) {
                for (row in 0 until s.rows) {
                    Row {
                        for (col in 0 until s.cols) {
                            val index = row * s.cols + col
                            MinesweeperCellView(
                                cell = s.cells[index],
                                index = index,
                                row = row,
                                col = col,
                                cellSize = cellDp,
                                digitSp = digitSp,
                                digitLineSp = digitLineSp,
                                palette = palette,
                                boardOver = s.isOver,
                                won = s.won,
                                explodedIndex = s.explodedIndex,
                                flagMode = flagMode,
                                reducedMotion = reducedMotion,
                                onTap = onCellTap,
                                onLongPress = onCellLongPress
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What one square shows, derived from its state plus how the board ended. */
private enum class MinesweeperCellLook { HIDDEN, FLAG, WRONG_FLAG, MINE, DETONATED, NUMBER, EMPTY }

private fun minesweeperCellLook(
    cell: MinesweeperCell,
    index: Int,
    boardOver: Boolean,
    won: Boolean,
    explodedIndex: Int?
): MinesweeperCellLook = when (cell.state) {
    CellState.REVEALED -> when {
        cell.isMine -> if (index == explodedIndex) MinesweeperCellLook.DETONATED else MinesweeperCellLook.MINE
        cell.adjacentMines > 0 -> MinesweeperCellLook.NUMBER
        else -> MinesweeperCellLook.EMPTY
    }
    // Only a lost board can hold a wrong flag; a won board has every safe square revealed.
    CellState.FLAGGED ->
        if (boardOver && !won && !cell.isMine) MinesweeperCellLook.WRONG_FLAG else MinesweeperCellLook.FLAG
    // A won board shows its remaining mines as flags, like the classic game.
    CellState.HIDDEN ->
        if (boardOver && won && cell.isMine) MinesweeperCellLook.FLAG else MinesweeperCellLook.HIDDEN
}

/**
 * Mirrors CheckersScreen's / ReversiScreen's own convention (state, then row and column, 1-indexed
 * for a human reader) rather than inventing a new one.
 */
private fun minesweeperCellDescription(look: MinesweeperCellLook, cell: MinesweeperCell, row: Int, col: Int): String {
    val base = when (look) {
        MinesweeperCellLook.HIDDEN -> "Hidden"
        MinesweeperCellLook.FLAG -> if (cell.state == CellState.FLAGGED) "Flagged" else "Mine"
        MinesweeperCellLook.WRONG_FLAG -> "Wrong flag, no mine here"
        MinesweeperCellLook.MINE -> "Mine"
        MinesweeperCellLook.DETONATED -> "Mine, detonated"
        MinesweeperCellLook.NUMBER ->
            if (cell.adjacentMines == 1) "1 adjacent mine" else "${cell.adjacentMines} adjacent mines"
        MinesweeperCellLook.EMPTY -> "Empty"
    }
    return "$base, row ${row + 1}, column ${col + 1}"
}

/**
 * One square. A hidden or flagged square is a button (its whole [cellSize], gap included, is the
 * touch target) whose click label follows the mode and whose long-click label is the flag; a revealed
 * square, or any square once the board is over, is plain text with no action. The glyphs are
 * decoration (drawn shapes, or a digit with its semantics cleared) because the description already
 * says what is there. Skips recomposition when its [cell] and the (stable, shared) arguments are
 * unchanged, so a tap re-renders only the squares it changed.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MinesweeperCellView(
    cell: MinesweeperCell,
    index: Int,
    row: Int,
    col: Int,
    cellSize: Dp,
    digitSp: TextUnit,
    digitLineSp: TextUnit,
    palette: MinesweeperPalette,
    boardOver: Boolean,
    won: Boolean,
    explodedIndex: Int?,
    flagMode: Boolean,
    reducedMotion: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val look = minesweeperCellLook(cell, index, boardOver, won, explodedIndex)
    val description = remember(look, cell.state, cell.adjacentMines, row, col) {
        minesweeperCellDescription(look, cell, row, col)
    }
    val interactive = !boardOver && cell.state != CellState.REVEALED
    val flagged = cell.state == CellState.FLAGGED
    val raised = look == MinesweeperCellLook.HIDDEN ||
        look == MinesweeperCellLook.FLAG ||
        look == MinesweeperCellLook.WRONG_FLAG
    val fill = when (look) {
        MinesweeperCellLook.DETONATED -> palette.danger
        MinesweeperCellLook.NUMBER, MinesweeperCellLook.EMPTY, MinesweeperCellLook.MINE -> palette.revealedCell
        else -> palette.hiddenCell
    }
    val small = cellSize < 32.dp
    val gap = if (small) 1.dp else 1.5.dp
    val shape = RoundedCornerShape(if (small) 3.dp else 4.dp)

    val clickModifier = if (interactive) {
        Modifier.combinedClickable(
            onClickLabel = when {
                flagMode -> if (flagged) "Remove flag" else "Place flag"
                flagged -> "Reveal, remove the flag first"
                else -> "Reveal"
            },
            role = Role.Button,
            onLongClickLabel = if (flagged) "Remove flag" else "Place flag",
            onLongClick = { onLongPress(index) },
            onClick = { onTap(index) }
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            // Before the padding, so the 1-1.5dp gap between squares is part of the touch target.
            .then(clickModifier)
            .semantics { contentDescription = description }
            .padding(gap)
            .clip(shape)
            .background(fill)
            .then(
                if (raised) Modifier.drawBehind { drawBevel(palette.hiddenHighlight, palette.hiddenShadow) }
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when (look) {
            MinesweeperCellLook.NUMBER -> Text(
                cell.adjacentMines.toString(),
                color = palette.numberColors[cell.adjacentMines.coerceIn(1, 8)],
                fontSize = digitSp,
                lineHeight = digitLineSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {}
            )
            MinesweeperCellLook.FLAG -> MinesweeperFlagGlyph(
                palette = palette,
                wrong = false,
                animate = !reducedMotion && !boardOver
            )
            MinesweeperCellLook.WRONG_FLAG -> MinesweeperFlagGlyph(palette = palette, wrong = true, animate = false)
            MinesweeperCellLook.MINE -> Canvas(modifier = Modifier.fillMaxSize()) {
                drawMineGlyph(ink = palette.textPrimary, shine = palette.revealedCell)
            }
            MinesweeperCellLook.DETONATED -> Canvas(modifier = Modifier.fillMaxSize()) {
                drawBurstGlyph(color = palette.burst)
                drawMineGlyph(ink = palette.mineInk, shine = palette.burst)
            }
            MinesweeperCellLook.HIDDEN, MinesweeperCellLook.EMPTY -> Unit
        }
    }
}

/**
 * A flag, or (when [wrong]) a flag struck through with an X. [animate] pops it in over
 * [MINES_FLAG_POP_MS] when it is first planted; the caller turns that off under reduced motion and
 * once the board is over.
 */
@Composable
private fun MinesweeperFlagGlyph(palette: MinesweeperPalette, wrong: Boolean, animate: Boolean) {
    val pop = remember { Animatable(if (animate) MINES_FLAG_POP_FROM else 1f) }
    LaunchedEffect(Unit) {
        if (animate) {
            pop.animateTo(1f, animationSpec = tween(durationMillis = MINES_FLAG_POP_MS, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Read in the draw lambda so a pop frame redraws without recomposing.
        scale(scale = pop.value) {
            drawFlagGlyph(pole = palette.textPrimary, pennant = palette.flag)
            if (wrong) drawCrossGlyph(ink = palette.textPrimary, casing = palette.hiddenCell)
        }
    }
}

// ---------------------------------------------------------------------------
// Drawn glyphs: shapes, so no state rests on a hue or an emoji font
// ---------------------------------------------------------------------------

/** The raised look of a hidden square: a light top-left edge and a darker bottom-right edge. */
private fun DrawScope.drawBevel(highlight: Color, shadow: Color) {
    val w = size.width
    val h = size.height
    val edge = maxOf(1.5f.dp.toPx(), minOf(w, h) * 0.07f)
    drawRect(color = highlight, topLeft = Offset.Zero, size = Size(w, edge))
    drawRect(color = highlight, topLeft = Offset.Zero, size = Size(edge, h))
    drawRect(color = shadow, topLeft = Offset(0f, h - edge), size = Size(w, edge))
    drawRect(color = shadow, topLeft = Offset(w - edge, 0f), size = Size(edge, h))
}

/** A flag: a pole on a small base with a triangular pennant. */
private fun DrawScope.drawFlagGlyph(pole: Color, pennant: Color) {
    val w = size.width
    val h = size.height
    val stroke = minOf(w, h) * 0.08f
    val poleX = w * 0.40f
    drawLine(
        color = pole,
        start = Offset(poleX, h * 0.18f),
        end = Offset(poleX, h * 0.80f),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = pole,
        start = Offset(w * 0.26f, h * 0.80f),
        end = Offset(w * 0.64f, h * 0.80f),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    val flagPath = Path()
    flagPath.moveTo(poleX, h * 0.18f)
    flagPath.lineTo(w * 0.78f, h * 0.34f)
    flagPath.lineTo(poleX, h * 0.50f)
    flagPath.close()
    drawPath(flagPath, color = pennant)
}

/** A mine: a ball with eight spikes and a small highlight. */
private fun DrawScope.drawMineGlyph(ink: Color, shine: Color) {
    val c = center
    val extent = minOf(size.width, size.height)
    val body = extent * 0.24f
    val spike = extent * 0.40f
    val stroke = extent * 0.07f
    for (i in 0 until 4) {
        val angle = (i * PI / 4.0).toFloat()
        val dx = cos(angle) * spike
        val dy = sin(angle) * spike
        drawLine(
            color = ink,
            start = Offset(c.x - dx, c.y - dy),
            end = Offset(c.x + dx, c.y + dy),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
    drawCircle(color = ink, radius = body, center = c)
    drawCircle(color = shine, radius = body * 0.28f, center = Offset(c.x - body * 0.35f, c.y - body * 0.35f))
}

/** The detonated mine's starburst: an eight-pointed star behind the mine. */
private fun DrawScope.drawBurstGlyph(color: Color) {
    val c = center
    val extent = minOf(size.width, size.height)
    val outer = extent * 0.48f
    val inner = extent * 0.30f
    val points = 16
    val star = Path()
    for (i in 0 until points) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = (i * 2.0 * PI / points - PI / 2.0).toFloat()
        val x = c.x + cos(angle) * radius
        val y = c.y + sin(angle) * radius
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    drawPath(star, color = color)
}

/** An X across the square, with a wider [casing] stroke under it so it reads over a red pennant. */
private fun DrawScope.drawCrossGlyph(ink: Color, casing: Color) {
    val w = size.width
    val h = size.height
    val extent = minOf(w, h)
    val inset = extent * 0.16f
    val stroke = extent * 0.09f
    for (pass in 0..1) {
        val color = if (pass == 0) casing else ink
        val width = if (pass == 0) stroke * 1.9f else stroke
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(w - inset, h - inset),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w - inset, inset),
            end = Offset(inset, h - inset),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

/** The Reveal mode's mark: an outlined square with a dot, the thing you tap. */
private fun DrawScope.drawRevealGlyph(ink: Color) {
    val w = size.width
    val h = size.height
    val extent = minOf(w, h)
    val stroke = extent * 0.12f
    drawRoundRect(
        color = ink,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius(extent * 0.18f),
        style = Stroke(width = stroke)
    )
    drawCircle(color = ink, radius = extent * 0.14f, center = center)
}

// ---------------------------------------------------------------------------
// Mode toggle
// ---------------------------------------------------------------------------

/**
 * The Reveal / Flag mode toggle: two radio buttons, each at least 48dp tall, sharing the row's width.
 * The selected one has the accent fill, a heavier outline and a bold label, so the mode is carried by
 * shape and weight as well as colour. Dimmed and inert while [enabled] is false (a finished board).
 */
@Composable
private fun MinesweeperModeToggle(
    flagMode: Boolean,
    enabled: Boolean,
    palette: MinesweeperPalette,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MinesweeperModeButton(
            label = "Reveal",
            isFlag = false,
            selected = !flagMode,
            enabled = enabled,
            palette = palette,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f)
        )
        MinesweeperModeButton(
            label = "Flag",
            isFlag = true,
            selected = flagMode,
            enabled = enabled,
            palette = palette,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MinesweeperModeButton(
    label: String,
    isFlag: Boolean,
    selected: Boolean,
    enabled: Boolean,
    palette: MinesweeperPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = if (selected) palette.textOnAccent else palette.textPrimary
    val pill = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pill)
                .background(if (selected) palette.accent else palette.chipBackground)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) palette.textPrimary else palette.textPrimary.copy(alpha = 0.35f),
                    shape = pill
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decoration: the label already says which mode this is.
            Canvas(modifier = Modifier.size(20.dp).clearAndSetSemantics {}) {
                if (isFlag) {
                    drawFlagGlyph(pole = ink, pennant = if (selected) ink else palette.flag)
                } else {
                    drawRevealGlyph(ink = ink)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = ink,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Result panel and confirm dialog
// ---------------------------------------------------------------------------

@Composable
private fun MinesweeperResultPanel(
    won: Boolean,
    timeMillis: Long?,
    clearedSafe: Int,
    totalSafe: Int,
    bestTimeMillis: Long?,
    isNewBest: Boolean,
    daily: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: MinesweeperPalette,
    modifier: Modifier = Modifier
) {
    // Opaque (unlike a translucent tint) so the text never competes with the board behind it.
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = palette.chipBackground,
            contentColor = palette.textPrimary
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                when {
                    won && daily -> "Daily board cleared!"
                    won -> "Board cleared!"
                    else -> "Boom! You hit a mine"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (timeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Time: ${minesFormatClock(timeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (!won) {
                Spacer(Modifier.height(2.dp))
                Text("Cleared $clearedSafe of $totalSafe safe squares", style = MaterialTheme.typography.bodyMedium)
            }
            if (won && isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text("New best time!", fontWeight = FontWeight.Bold)
            } else if (bestTimeMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text("Best: ${minesFormatClock(bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewBoard,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.textPrimary, contentColor = palette.background),
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

/** The "this discards your board" confirm shared by a tier switch and "New board". */
@Composable
private fun MinesweeperDiscardDialog(
    title: String,
    body: String,
    confirmLabel: String,
    palette: MinesweeperPalette,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.background,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
            ) { Text("Keep Playing") }
        }
    )
}
