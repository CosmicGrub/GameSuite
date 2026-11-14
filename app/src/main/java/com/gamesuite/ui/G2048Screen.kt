package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.g2048.G2048Direction
import com.gamesuite.games.g2048.G2048Game
import com.gamesuite.games.g2048.G2048Rules
import com.gamesuite.games.g2048.G2048Size
import com.gamesuite.games.g2048.G2048State
import com.gamesuite.games.g2048.G2048StatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Rim around the tiles (dp). Both the real padding of the board and the `framePx` handed to [fitBoard]. */
private const val G2048_FRAME_DP = 6f

/** Below this cell pitch (dp) the board stops shrinking and scrolls instead (the swipe gesture is then off; the buttons still work). */
private const val G2048_MIN_CELL_DP = 28f

/** Upper bound on a cell so the board does not sprawl across a tablet. */
private const val G2048_MAX_CELL_DP = 104f

/** A drag must travel this far (dp) before it counts as a swipe. */
private const val G2048_SWIPE_THRESHOLD_DP = 28f

/** Slide and pop durations (ms), before the game-speed scale. Instant under reduced motion. */
private const val G2048_SLIDE_MS = 110
private const val G2048_POP_MS = 140

/** Portrait content never stretches wider than this. */
private val G2048_CONTENT_MAX_WIDTH = 560.dp

/** The side column of the wide layout. */
private val G2048_SIDE_PANE_WIDTH = 320.dp

private fun g2048DirectionOf(key: Key): G2048Direction? = when (key) {
    Key.DirectionLeft, Key.A -> G2048Direction.LEFT
    Key.DirectionRight, Key.D -> G2048Direction.RIGHT
    Key.DirectionUp, Key.W -> G2048Direction.UP
    Key.DirectionDown, Key.S -> G2048Direction.DOWN
    else -> null
}

private fun g2048Glyph(d: G2048Direction): String = when (d) {
    G2048Direction.LEFT -> "←"
    G2048Direction.RIGHT -> "→"
    G2048Direction.UP -> "↑"
    G2048Direction.DOWN -> "↓"
}

private fun g2048WordRes(d: G2048Direction): Int = when (d) {
    G2048Direction.LEFT -> R.string.g2048_dir_left
    G2048Direction.RIGHT -> R.string.g2048_dir_right
    G2048Direction.UP -> R.string.g2048_dir_up
    G2048Direction.DOWN -> R.string.g2048_dir_down
}

private fun g2048MoveRes(d: G2048Direction): Int = when (d) {
    G2048Direction.LEFT -> R.string.g2048_move_left
    G2048Direction.RIGHT -> R.string.g2048_move_right
    G2048Direction.UP -> R.string.g2048_move_up
    G2048Direction.DOWN -> R.string.g2048_move_down
}

private fun g2048PadRes(d: G2048Direction): Int = when (d) {
    G2048Direction.LEFT -> R.string.g2048_pad_left
    G2048Direction.RIGHT -> R.string.g2048_pad_right
    G2048Direction.UP -> R.string.g2048_pad_up
    G2048Direction.DOWN -> R.string.g2048_pad_down
}

/** Tile colour from the Material scheme, graded by the tile's power of two (2 is the first stop, 4096+ the last). */
private fun g2048TileColor(value: Int, scheme: ColorScheme): Color {
    val level = (31 - Integer.numberOfLeadingZeros(value.coerceAtLeast(2))).coerceIn(1, 12)
    val stops = listOf(
        scheme.secondaryContainer, scheme.primaryContainer, scheme.tertiaryContainer,
        scheme.tertiary, scheme.primary, scheme.error
    )
    val pos = (level - 1) / 11f * (stops.size - 1)
    val i = pos.toInt().coerceAtMost(stops.size - 2)
    return lerpColor(stops[i], stops[i + 1], pos - i)
}

/** Whichever of the scheme's onBackground / background contrasts better with [bg]. */
private fun g2048InkFor(bg: Color, scheme: ColorScheme): Color {
    val a = scheme.onBackground
    val b = scheme.background
    return if (abs(a.luminance() - bg.luminance()) >= abs(b.luminance() - bg.luminance())) a else b
}

/**
 * 2048. A square grid of numbered tiles: swipe (or press an arrow button or key) to slide them all,
 * equal tiles merge, reach the target tile to win, keep going if you like.
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play / Undo / Hint) with its
 * BackHandler. Leaving a game that has not reached a result is an abort (never recorded); once an
 * earlier game this session reached a result, leaving records the session through `leaveSession()`.
 *
 * LAYOUT: [fitBoard] on the board slot's measured space (28dp cell floor, 104dp ceiling). If the slot
 * cannot give a cell 28dp the board pans instead of shrinking and the swipe gesture is disabled (it
 * would fight the pan); the four direction buttons, the arrow keys and the screen-reader actions all
 * keep working. Portrait stacks status / board / controls; a wide window puts the board left and
 * everything else in a column on the right.
 *
 * INPUT: swipe (detectDragGestures, 28dp threshold, one move per drag), four labelled 48dp+ direction
 * buttons, arrow keys and WASD, and four custom accessibility actions on the board.
 *
 * MOTION: tiles slide to their new cells (110ms) and merged / new tiles pop; instant under
 * LocalReducedMotion. The durations follow LocalGameSpeed.
 *
 * ACCESSIBILITY: tile values are numbers (never colour alone); under LocalColorblindMode a tile at
 * or past the target also gets a heavy outline. The board is one node whose description reads every
 * row. The status line is a polite live region.
 *
 * ASSISTED GAMES: Undo and Hint mark the game assisted; the screen says so and never stores its score
 * as a best.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun G2048Screen(
    sessionManager: GameSessionManager,
    game: G2048Game,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val resources = androidContext.resources
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val statsStore = remember { G2048StatsStore(androidContext) }
    val state by game.state
    val sessionBest by game.sessionBest
    val hintDirection by game.hintDirection
    val gameNumber by game.gameNumber
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val gameSpeed = LocalGameSpeed.current
    val scheme = MaterialTheme.colorScheme
    val storedBest by statsStore.bestScores.collectAsState(initial = emptyMap())
    var confirmNew by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        // The Hub's "size" choice; a resumed game overrides it with the saved board's size.
        game.gridSize = G2048Size.fromId(sessionManager.launchOptions.extras["size"])
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val s = state ?: return
    val sizeId = s.size.id

    // The stored best as it stood when this game began, so "New best score!" means this game beat it.
    var priorBest by remember(gameNumber) { mutableStateOf<Int?>(null) }
    LaunchedEffect(gameNumber, sizeId) { priorBest = statsStore.bestScores.first()[sizeId] ?: 0 }

    // An unassisted score that beats the stored best is saved as it happens, so leaving never loses it.
    LaunchedEffect(s.score, s.assisted, sizeId) {
        if (!s.assisted && s.score > 0 && s.score > (storedBest[sizeId] ?: 0)) {
            statsStore.recordScore(sizeId, s.score)
        }
    }

    LaunchedEffect(gameNumber) { runCatching { focusRequester.requestFocus() } }

    val won = s.awaitingContinue
    LaunchedEffect(gameNumber, won) {
        if (won) {
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        }
    }
    val over = s.isOver
    LaunchedEffect(gameNumber, over) {
        if (over) haptics(HapticSignal.FAILURE)
    }

    val doMove: (G2048Direction) -> Unit = { direction ->
        val before = game.state.value
        if (game.move(direction)) {
            if ((game.state.value?.lastGain ?: 0) > 0) {
                haptics(HapticSignal.NORMAL_ACTION)
                playSfx(SfxKind.SOLID_THUNK)
            } else {
                haptics(HapticSignal.LIGHT_TICK)
            }
        } else if (before != null && !before.isFinished) {
            haptics(HapticSignal.FAILURE)
        }
    }
    val doUndo: () -> Unit = {
        if (game.canUndo) {
            game.undo()
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val doHint: () -> Unit = {
        if (game.hint() != null) haptics(HapticSignal.LIGHT_TICK)
    }
    val requestNewGame: () -> Unit = {
        if (s.moves > 0 && !s.isFinished) confirmNew = true else game.newGame()
    }

    val best = maxOf(storedBest[sizeId] ?: 0, sessionBest)
    val prior = priorBest
    val newBest = !s.assisted && prior != null && s.score > 0 && s.score > prior
    val finishedGames = game.finishedGames
    val finished = s.isFinished
    val canPlay = !finished && !game.matchOver.value

    val statusText = when {
        hintDirection != null -> stringResource(R.string.g2048_status_hint, stringResource(g2048WordRes(hintDirection!!)))
        s.awaitingContinue -> stringResource(R.string.g2048_status_won, s.target)
        s.isOver -> stringResource(R.string.g2048_status_over)
        else -> stringResource(R.string.g2048_status_ready)
    }
    val captionText = stringResource(if (s.assisted) R.string.g2048_assist_note else R.string.g2048_assist_caption)

    GameChrome(
        helpTitle = stringResource(R.string.g2048_help_title),
        helpText = stringResource(R.string.g2048_help_text, s.target),
        matchInProgress = !finished,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        onUndo = doUndo,
        canUndo = game.canUndo,
        onHint = if (canPlay) doHint else null,
        buttonFill = scheme.background,
        buttonContent = scheme.onBackground,
        leaveTitle = stringResource(R.string.g2048_leave_title),
        leaveBody = stringResource(
            if (finishedGames > 0) R.string.g2048_leave_body_session else R.string.g2048_leave_body
        ),
        modifier = Modifier
            .onKeyEvent { event ->
                val direction = g2048DirectionOf(event.key)
                if (event.type == KeyEventType.KeyDown && direction != null) {
                    doMove(direction)
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .padding(12.dp)
        ) {
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f
            val screenMaxHeight = maxHeight

            val header: @Composable () -> Unit = {
                G2048Header(s = s, best = best, status = statusText)
            }
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                G2048Board(
                    s = s,
                    hint = hintDirection,
                    reducedMotion = reducedMotion,
                    colorblind = colorblind,
                    slideMs = gameSpeed.scale(G2048_SLIDE_MS.toLong()).toInt(),
                    popMs = gameSpeed.scale(G2048_POP_MS.toLong()).toInt(),
                    onMove = doMove,
                    modifier = boardModifier
                )
            }
            val controls: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    G2048Pad(enabled = canPlay, hint = hintDirection, onMove = doMove)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = doUndo,
                            enabled = game.canUndo,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground)
                        ) { Text(stringResource(R.string.g2048_undo)) }
                        OutlinedButton(
                            onClick = doHint,
                            enabled = canPlay,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground)
                        ) { Text(stringResource(R.string.g2048_hint)) }
                        OutlinedButton(
                            onClick = requestNewGame,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground)
                        ) { Text(stringResource(R.string.g2048_new_game)) }
                    }
                    Text(
                        captionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            val resultPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                G2048ResultPanel(
                    s = s,
                    best = best,
                    newBest = newBest,
                    canUndo = game.canUndo,
                    onUndo = doUndo,
                    onKeepGoing = { game.keepGoing() },
                    onPlayAgain = { game.playAgain() },
                    onBackToMenu = { game.leaveSession() },
                    modifier = panelModifier
                )
            }

            if (sideBySide) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    board(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(G2048_SIDE_PANE_WIDTH)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        header()
                        if (finished) resultPanel(Modifier.fillMaxWidth()) else controls()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = G2048_CONTENT_MAX_WIDTH).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        header()
                    }
                    Spacer(Modifier.height(10.dp))
                    board(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (finished) {
                        // Capped so even a large font scale cannot push the board away; the panel scrolls in its cap.
                        resultPanel(
                            Modifier
                                .widthIn(max = G2048_CONTENT_MAX_WIDTH)
                                .fillMaxWidth()
                                .heightIn(max = screenMaxHeight * 0.45f)
                                .verticalScroll(rememberScrollState())
                        )
                    } else {
                        Column(modifier = Modifier.widthIn(max = G2048_CONTENT_MAX_WIDTH).fillMaxWidth()) {
                            controls()
                        }
                    }
                }
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.g2048_new_title)) },
            text = { Text(stringResource(R.string.g2048_new_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNew = false
                        game.newGame()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.g2048_new_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmNew = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.g2048_new_cancel)) }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Header: score / best chips, a one-line summary, the status line
// ---------------------------------------------------------------------------

@Composable
private fun G2048Header(s: G2048State, best: Int, status: String) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            // The corner menu button is aligned to the screen's top-end: keep clear of it.
            modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            G2048ScoreChip(
                label = stringResource(R.string.g2048_score),
                value = s.score,
                description = stringResource(R.string.g2048_score_desc, s.score),
                gain = s.lastGain,
                modifier = Modifier.weight(1f)
            )
            G2048ScoreChip(
                label = stringResource(R.string.g2048_best),
                value = best,
                description = stringResource(R.string.g2048_best_desc, best),
                gain = 0,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.g2048_moves_line, s.n, s.moves, s.target),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun G2048ScoreChip(label: String, value: Int, description: String, gain: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onBackground.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onBackground.copy(alpha = 0.7f), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (gain > 0) {
                Text(
                    stringResource(R.string.g2048_status_gain, gain),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onBackground.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Direction pad
// ---------------------------------------------------------------------------

@Composable
private fun G2048Pad(enabled: Boolean, hint: G2048Direction?, onMove: (G2048Direction) -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val suggested = stringResource(R.string.g2048_suggested_move)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(G2048Direction.LEFT, G2048Direction.UP, G2048Direction.DOWN, G2048Direction.RIGHT).forEach { direction ->
            val isHint = hint == direction
            val label = stringResource(g2048MoveRes(direction))
            val buttonModifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .semantics {
                    contentDescription = label
                    if (isHint) stateDescription = suggested
                }
            val content: @Composable () -> Unit = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // The glyph and the word are decoration: the button's own description carries the meaning.
                    modifier = Modifier.clearAndSetSemantics { }
                ) {
                    Text(g2048Glyph(direction), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        stringResource(g2048PadRes(direction)),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isHint) {
                // The suggested direction is a filled button (shape and weight, not just colour) and says so in text below the board.
                Button(
                    onClick = { onMove(direction) },
                    enabled = enabled,
                    modifier = buttonModifier,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) { content() }
            } else {
                OutlinedButton(
                    onClick = { onMove(direction) },
                    enabled = enabled,
                    modifier = buttonModifier,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) { content() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

@Composable
private fun G2048Board(
    s: G2048State,
    hint: G2048Direction?,
    reducedMotion: Boolean,
    colorblind: Boolean,
    slideMs: Int,
    popMs: Int,
    onMove: (G2048Direction) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    val scheme = MaterialTheme.colorScheme
    val n = s.n
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val currentOnMove by rememberUpdatedState(onMove)
    val swipeThresholdPx = with(density) { G2048_SWIPE_THRESHOLD_DP.dp.toPx() }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = n,
            rows = n,
            framePx = G2048_FRAME_DP,
            minCellPx = G2048_MIN_CELL_DP,
            maxCellPx = G2048_MAX_CELL_DP
        )
        val scrolls = !fit.meetsMinimum
        // Whole pixels, trimmed by one, so n rounded-up cells plus the frame never overshoot the slot.
        val pitch: Dp = if (scrolls) {
            G2048_MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }
        val frame = G2048_FRAME_DP.dp
        val gap = (pitch * 0.07f).coerceAtLeast(2.dp)
        val tile = pitch - gap
        val side = pitch * n + frame * 2

        // ---- what the last move did, for the slide ----
        val previous = s.previousCells
        val direction = s.lastDirection
        val canAnimate = !reducedMotion && previous != null && direction != null && previous.size == s.cells.size
        val motions = remember(s.moves, previous, direction) {
            if (canAnimate && previous != null && direction != null) {
                G2048Rules.move(previous, n, direction).motions
            } else {
                emptyList()
            }
        }
        val mergedTargets = remember(motions) { motions.filter { it.merged }.map { it.to }.toSet() }
        val slide = remember(s.moves, canAnimate, direction) { Animatable(if (canAnimate) 0f else 1f) }
        val pop = remember(s.moves, canAnimate, direction) { Animatable(if (canAnimate) 0f else 1f) }
        LaunchedEffect(s.moves, canAnimate, direction) {
            if (canAnimate) {
                slide.animateTo(1f, tween(durationMillis = slideMs.coerceAtLeast(1), easing = FastOutSlowInEasing))
                pop.animateTo(1f, tween(durationMillis = popMs.coerceAtLeast(1), easing = FastOutSlowInEasing))
            }
        }

        // ---- semantics: one node that reads the whole grid, plus the four moves as actions ----
        val empty = stringResource(R.string.g2048_cell_empty)
        val panNote = stringResource(R.string.g2048_board_pan_note)
        val description = remember(s.cells, scrolls, empty, panNote) {
            val rows = (0 until n).joinToString(" ") { r ->
                val row = (0 until n).joinToString(", ") { c ->
                    s.cells[r * n + c].let { if (it == 0) empty else it.toString() }
                }
                resources.getString(R.string.g2048_row_desc, r + 1, row)
            }
            val text = resources.getString(R.string.g2048_board_desc, n, rows)
            if (scrolls) "$text $panNote" else text
        }
        val actions = G2048Direction.values().map { d ->
            CustomAccessibilityAction(stringResource(g2048MoveRes(d))) {
                currentOnMove(d)
                true
            }
        }

        val swipeModifier = if (scrolls) {
            Modifier
        } else {
            Modifier.pointerInput(swipeThresholdPx) {
                var dx = 0f
                var dy = 0f
                var fired = false
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                        fired = false
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (!fired) {
                            dx += drag.x
                            dy += drag.y
                            if (abs(dx) >= swipeThresholdPx || abs(dy) >= swipeThresholdPx) {
                                fired = true
                                currentOnMove(
                                    if (abs(dx) > abs(dy)) {
                                        if (dx > 0f) G2048Direction.RIGHT else G2048Direction.LEFT
                                    } else {
                                        if (dy > 0f) G2048Direction.DOWN else G2048Direction.UP
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }

        Box(
            modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier
        ) {
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.onBackground.copy(alpha = 0.18f))
                    .then(swipeModifier)
                    .clearAndSetSemantics {
                        contentDescription = description
                        customActions = actions
                    }
            ) {
                // Empty cells.
                for (index in 0 until n * n) {
                    val x = frame + pitch * (index % n) + gap / 2
                    val y = frame + pitch * (index / n) + gap / 2
                    Box(
                        modifier = Modifier
                            .offset(x, y)
                            .size(tile)
                            .clip(RoundedCornerShape(tile * 0.14f))
                            .background(scheme.onBackground.copy(alpha = 0.10f))
                    )
                }

                val sliding = slide.value < 1f && motions.isNotEmpty()
                if (sliding) {
                    // Tiles in flight carry their old values to their new cells.
                    for (m in motions) {
                        val fx = frame + pitch * (m.from % n) + gap / 2
                        val fy = frame + pitch * (m.from / n) + gap / 2
                        val tx = frame + pitch * (m.to % n) + gap / 2
                        val ty = frame + pitch * (m.to / n) + gap / 2
                        G2048Tile(
                            value = m.value,
                            size = tile,
                            x = lerpDp(fx, tx, slide.value),
                            y = lerpDp(fy, ty, slide.value),
                            scale = 1f,
                            scheme = scheme,
                            target = s.target,
                            colorblind = colorblind
                        )
                    }
                } else {
                    for (index in 0 until n * n) {
                        val value = s.cells[index]
                        if (value == 0) continue
                        val scale = when {
                            !canAnimate -> 1f
                            index == s.lastSpawn -> pop.value
                            index in mergedTargets -> 1f + 0.14f * sin(PI.toFloat() * pop.value)
                            else -> 1f
                        }
                        G2048Tile(
                            value = value,
                            size = tile,
                            x = frame + pitch * (index % n) + gap / 2,
                            y = frame + pitch * (index / n) + gap / 2,
                            scale = scale,
                            scheme = scheme,
                            target = s.target,
                            colorblind = colorblind
                        )
                    }
                }

                // The suggested direction as an arrow badge on that edge (the status line says it in words too).
                if (hint != null) {
                    val alignment = when (hint) {
                        G2048Direction.LEFT -> Alignment.CenterStart
                        G2048Direction.RIGHT -> Alignment.CenterEnd
                        G2048Direction.UP -> Alignment.TopCenter
                        G2048Direction.DOWN -> Alignment.BottomCenter
                    }
                    Box(
                        modifier = Modifier
                            .align(alignment)
                            .padding(2.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .border(2.dp, scheme.onPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(g2048Glyph(hint), color = scheme.onPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun G2048Tile(
    value: Int,
    size: Dp,
    x: Dp,
    y: Dp,
    scale: Float,
    scheme: ColorScheme,
    target: Int,
    colorblind: Boolean
) {
    val density = LocalDensity.current
    val bg = g2048TileColor(value, scheme)
    val ink = g2048InkFor(bg, scheme)
    val digits = value.toString().length
    val factor = when {
        digits <= 1 -> 0.50f
        digits == 2 -> 0.44f
        digits == 3 -> 0.36f
        digits == 4 -> 0.29f
        else -> 0.23f
    }
    // Dp -> sp divides out the font scale: a tile's number is sized to its tile, not to the text-size setting.
    val textSize = with(density) { (size * factor).toSp() }
    Box(
        modifier = Modifier
            .offset(x, y)
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(size * 0.14f))
            .background(bg)
            // Not colour alone: with colour-blind mode on, a tile at or past the target gets a heavy outline.
            .then(if (colorblind && value >= target) Modifier.border(3.dp, ink, RoundedCornerShape(size * 0.14f)) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value.toString(),
            color = ink,
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun G2048ResultPanel(
    s: G2048State,
    best: Int,
    newBest: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onKeepGoing: () -> Unit,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val outlined = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onBackground)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = scheme.onBackground.copy(alpha = 0.08f),
            contentColor = scheme.onBackground
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (s.awaitingContinue) stringResource(R.string.g2048_won_title, s.target) else stringResource(R.string.g2048_over_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.g2048_result_line, s.score, s.highestTile),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (newBest) {
                Text(
                    stringResource(R.string.g2048_new_best),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            } else if (s.assisted) {
                Text(
                    stringResource(R.string.g2048_assist_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    stringResource(R.string.g2048_best_desc, best),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (s.awaitingContinue) {
                    Button(
                        onClick = onKeepGoing,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(stringResource(R.string.g2048_keep_going), textAlign = TextAlign.Center) }
                }
                if (canUndo) {
                    OutlinedButton(
                        onClick = onUndo,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = outlined,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(stringResource(R.string.g2048_undo_last), textAlign = TextAlign.Center) }
                }
                if (s.awaitingContinue) {
                    OutlinedButton(
                        onClick = onPlayAgain,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = outlined,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(stringResource(R.string.g2048_play_again), textAlign = TextAlign.Center) }
                } else {
                    Button(
                        onClick = onPlayAgain,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(stringResource(R.string.g2048_play_again), textAlign = TextAlign.Center) }
                }
                ChangeSetupButton(onLeave = onBackToMenu, modifier = Modifier, colors = outlined)
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = outlined,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.g2048_back_to_menu), textAlign = TextAlign.Center) }
            }
        }
    }
}
