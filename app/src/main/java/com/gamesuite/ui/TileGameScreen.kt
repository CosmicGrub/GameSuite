package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveLayoutMode
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.foldable.rememberAdaptiveLayoutMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordgames.tiles.*
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A drag-placed tile in flight from its release point to the target cell's
 * center, purely for the presentational settle animation — the actual game
 * state (placeTile) has already committed by the time this exists. See the
 * `settling` state and its LaunchedEffect in [TileGameScreen].
 *
 * [releaseProgressVelocity] is the release's own fling speed, projected onto
 * the release-to-target direction and rescaled into the SAME units
 * [TileGameScreen]'s 0f..1f settle progress moves in (see its own
 * computation at the drag's onDragEnd) — Maximum tier only; always 0f on
 * Standard, which keeps today's exact fixed-tween settle. Fed straight into
 * `Animatable.animateTo`'s `initialVelocity`, so a hard flick genuinely
 * overshoots past the target before the spring settles, while a gentle drop
 * (velocity ~0) settles the same way a plain tween would.
 */
private data class SettlingTile(val tile: RackTile, val from: Offset, val to: Offset, val releaseProgressVelocity: Float = 0f)

/**
 * Word Tiles: a Scrabble-style 15x15 premium-square board, a seven-tile rack and a CPU (or
 * pass-and-play) opponent.
 *
 * PLACEMENT: a rack tile is dragged onto the board, or tapped and then a square tapped (the tap path
 * is also the screen-reader path); tapping a staged tile takes it back. The drag path hit-tests the
 * release point against each cell's root-coordinate bounds (`cellBounds`; `boundsInRoot` is clipped
 * to the board's viewport, so a cell panned out of view can never be hit). Drag-and-drop on this
 * board, and across a panned board in particular, is still not hardware-verified.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play / Motion tier / Back to Menu) and its
 * BackHandler. Leaving mid-game is an abort (`GameModule.abortMatch`, confirmation first, never
 * recorded to stats), after which the screen leaves at once because an abandoned game has no result
 * to show. A finished game leaves through [onMatchEnded] from its result panel, which is drawn
 * inside the same chrome. The score row (or, in book posture and the wide side-pane layout, the
 * rack title) reserves [GameChromeEndInset] so the corner button never covers it.
 *
 * BOARD SIZING: [fitBoard] against the measured space of the board's own slot, clamped to 28-44dp
 * cells. Below 28dp the board stops shrinking and pans (both axes) instead of overflowing. The rack
 * is 48dp touch targets: one row of seven when the pane is wide enough, otherwise two rows.
 *
 * LOOK: tiles are raised ivory pieces with a letter and a point value (blank tiles are italic and
 * worth nothing); premium squares keep their hues but also carry 2L / 3L / 2W / 3W / star text, so no
 * information is colour alone. Selected, staged and swap-picked tiles are marked by a heavy outline
 * (and a tick for swap), and an occupied drop target by a cross, again not by hue alone.
 * [LocalColorblindMode] only makes the premium labels bolder. Tile ink is explicit, so tiles read in
 * dark theme.
 *
 * ACCESSIBILITY: every board cell and rack tile has a description (letter, points, state, 1-indexed
 * row and column) and an action label; score chips are one node each; the turn prompt and last-play
 * line are polite live regions.
 *
 * FEEDBACK: the success haptic, chime and sparkle fire for the human's own plays only (a CPU word
 * gets a quiet thunk); the match-win celebration is for a sole human winner, a tie or a CPU win gets a
 * plain tick. A rejected Submit buzzes. Everything animated is off under `LocalReducedMotion`.
 */
@Composable
fun TileGameScreen(
    sessionManager: GameSessionManager,
    game: TileGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val density = LocalDensity.current
    // "Enhanced Move Animations" setting (motion quality — lift/settle/pop, distinct from
    // 3D perspective) always wins-loses to Reduced Motion, same combine rule as
    // CheckersScreen/ChessScreen: LocalEnhancedAnimations.current && !reducedMotion.
    val reducedMotion = LocalReducedMotion.current
    val enhanced = LocalEnhancedAnimations.current && !reducedMotion
    // 3D Perspective Mode -- gates the tile gloss sheen, same combine rule as every other screen.
    val card3D = LocalCard3DMode.current && !reducedMotion
    val colorblind = LocalColorblindMode.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val screenBackground = MaterialTheme.colorScheme.background
    val screenInk = MaterialTheme.colorScheme.onBackground

    // Ambient music (premium 2026 vision pass) -- gated on BOTH the dedicated ambient-music
    // setting AND the existing master sound toggle, same AND pattern every other feature
    // that respects CardSounds.soundEnabled already uses (see sounds.playPlace() below).
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.WORD_TILES, enabled = musicEnabled)

    // Standard/Maximum motion tier -- see TileMotionPrefsStore.kt's KDoc for exactly what
    // Maximum unlocks (the velocity-based fling settle below, and a camera micro-punch on
    // the human's Bingo). Switched from the corner menu's "Motion" entry.
    val motionPrefsStore = remember { TileMotionPrefsStore(androidContext) }
    val motionTier by motionPrefsStore.motionTier.collectAsState(initial = TileMotionTier.STANDARD)
    val maximumTier = motionTier == TileMotionTier.MAXIMUM

    // Word-completion flourish (sparkle/pulse/banner escalation) -- see WordPlayFlourishOverlay.
    // Bumped every time a new flourish fires so re-triggering the SAME tier back-to-back
    // (e.g. two ordinary plays in a row) still restarts the animation instead of no-op'ing
    // on an unchanged data-class key.
    var wordFlourish by remember { mutableStateOf<WordPlayResult?>(null) }
    var wordFlourishNonce by remember { mutableStateOf(0) }
    // Maximum tier only: a small camera micro-punch on the whole board when the human's Bingo lands.
    val cameraPunch = remember { Animatable(1f) }

    // game.setOnWordPlayed below registers its callback exactly ONCE per match
    // (LaunchedEffect(context) only re-runs when the session's context itself changes) --
    // rememberUpdatedState keeps it, and the drag callbacks further down (a pointerInput block
    // keyed only on `enabled`), reading the LATEST haptics / motion settings rather than whatever
    // they were the one time the registration ran. Same fix, same reasoning, as
    // SolitaireScreen.kt's own `currentEnhanced`.
    val currentHaptics by rememberUpdatedState(haptics)
    val currentEnhanced by rememberUpdatedState(enhanced)
    val currentMaximumTier by rememberUpdatedState(maximumTier)

    // Set the moment the player confirms "Leave" mid-game. An abort also flips the engine's
    // matchOver, which would otherwise draw the result panel (with made-up scores) for the frame
    // before navigation lands.
    var exiting by remember { mutableStateOf(false) }
    // The last non-null session context. endActiveGame nulls the live one when a match ends, but the
    // screen still renders (the result panel, or the board for the frame an abort is leaving).
    val lastContext = remember { arrayOfNulls<GameContext>(1) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        // A finished game stays on screen for its result panel (its button calls onMatchEnded). An
        // abort leaves from the chrome's onAbort below instead, since it has no result to show.
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.setOnWordPlayed { result ->
            if (result.byBot) {
                // The CPU's word: a quiet tile-landing cue, none of the human's success feedback.
                currentHaptics(HapticSignal.LIGHT_TICK)
                playSfx(SfxKind.SOLID_THUNK)
            } else {
                // A Bingo is a big play, not a match win: CELEBRATION is reserved for the winner.
                currentHaptics(if (result.tier == WordPlayTier.BINGO) HapticSignal.STRONG_ACTION else HapticSignal.SUCCESS)
                playSfx(SfxKind.SUCCESS_CHIME)
                wordFlourish = result
                wordFlourishNonce++
            }
        }
        game.startMatch()
    }

    // Maximum-tier camera micro-punch, fired only on the human's Bingo (the callback above only
    // bumps wordFlourishNonce for the human's plays, and result.tier gates which of those deserve
    // the punch).
    LaunchedEffect(wordFlourishNonce, maximumTier) {
        val flourish = wordFlourish ?: return@LaunchedEffect
        if (!maximumTier || reducedMotion || flourish.tier != WordPlayTier.BINGO) return@LaunchedEffect
        cameraPunch.snapTo(1f)
        cameraPunch.animateTo(1.04f, animationSpec = tween(90))
        cameraPunch.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    LaunchedEffect(state?.currentPlayerIndex, state?.matchOver) {
        val s = state ?: return@LaunchedEffect
        if (s.matchOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(900)
            game.playBotTurn()
        }
    }

    val s = state ?: return

    // Match-end feedback. Celebration is for a sole HUMAN winner only: a CPU win or a tie gets a
    // plain tick, and an abort (exiting) gets nothing.
    val topPlayers = s.leaders()
    val humanWon = s.matchOver && topPlayers.size == 1 && !topPlayers.first().isBot
    LaunchedEffect(s.matchOver) {
        if (!s.matchOver || exiting) return@LaunchedEffect
        if (humanWon) {
            currentHaptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        } else {
            currentHaptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // Checked BEFORE the context check below on purpose. setOnMatchEnd (above) calls
    // sessionManager.endActiveGame(result) synchronously, which nulls activeContext in the
    // same beat that the engine's own s.matchOver flips true. The result panel only ever reads
    // `s` and `onMatchEnded`, never `context` -- with the context check ordered first, that
    // null took the early return before this branch could ever run, and the screen went blank
    // right when the match ended instead of showing a result. It now sits inside the same
    // GameChrome, so system back and the corner menu still work on it.
    if (s.matchOver && !exiting) {
        GameChrome(
            helpTitle = TILE_HELP_TITLE,
            helpText = TILE_HELP_TEXT,
            matchInProgress = false,
            onLeave = onMatchEnded,
            onAbort = onMatchEnded,
            buttonFill = screenBackground,
            buttonContent = screenInk
        ) {
            TileResultPanel(
                players = s.players,
                topPlayers = topPlayers,
                ink = screenInk,
                background = screenBackground,
                onBackToMenu = onMatchEnded
            )
        }
        return
    }

    context?.let { lastContext[0] = it }
    val activeContext = lastContext[0] ?: return

    var selectedTileId by remember { mutableStateOf<Int?>(null) }
    // (row, col, blank tile's instanceId) — captured together so the letter dialog knows exactly which tile it's naming.
    var blankPickerFor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    // Drag state: which rack tile is being dragged, and its current root-space center position.
    var draggedTile by remember { mutableStateOf<RackTile?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val cellBounds = remember { mutableStateMapOf<Pair<Int, Int>, Rect>() }
    val tileCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    // Root-space origin of the Box the drag overlay is drawn in, so the overlay lands under the
    // finger even if an ancestor ever adds insets or padding.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // The one board cell currently under the dragged tile, or null. A single derived value (not one
    // per cell) so a drag frame scans the cell bounds once; each cell then derives its own boolean
    // from this, so only the cells whose answer flips recompose.
    val hoverCell = remember {
        derivedStateOf<Pair<Int, Int>?> {
            if (draggedTile == null) null
            else cellBounds.entries.firstOrNull { it.value.contains(dragPosition) }?.key
        }
    }
    // Reads only the engine's live state, so it is safe for a cell to capture the first instance
    // (see TileCellView's derivedStateOf): "is something already committed or staged there".
    val isOccupiedNow: (Int, Int) -> Boolean = remember(game) {
        { row: Int, col: Int ->
            val latest = game.state.value
            latest != null &&
                (latest.board[row][col].tile != null || latest.pending.any { it.row == row && it.col == col })
        }
    }

    // Drop-settle state: once a drag ends on a legal cell, the game state commits
    // immediately (same convention as UnoScreen's fly-to-discard overlay — the real
    // state update has already landed by the time this plays), and this drives a
    // short flight of the floating tile from its release point to the target cell's
    // known center (from cellBounds) instead of the floating tile just vanishing.
    // Only ever populated when `enhanced` is true; left null otherwise so the
    // floating-overlay code below never runs this branch when the setting is off.
    var settling by remember { mutableStateOf<SettlingTile?>(null) }
    val settleProgress = remember { Animatable(0f) }
    LaunchedEffect(settling) {
        val target = settling ?: return@LaunchedEffect
        settleProgress.snapTo(0f)
        if (maximumTier) {
            // The "honest real-physics" settle: a real spring, seeded with the release's
            // own fling speed (see onDragEnd below) instead of always starting at rest --
            // a flicked tile visibly overshoots the cell before settling; a gently-dropped
            // one (velocity ~0) settles almost the same as the plain tween did.
            settleProgress.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                initialVelocity = target.releaseProgressVelocity
            )
        } else {
            settleProgress.animateTo(1f, animationSpec = tween(140))
        }
        if (settling === target) settling = null
    }

    // Release-velocity tracking for the Maximum-tier fling settle above -- fed positions
    // through the drag via pointerInputDrag's onDrag below, read once at onDragEnd, then
    // reset for the next drag. A single shared tracker is safe here: only one tile can be
    // mid-drag at a time (draggedTile is a single nullable slot), so there's never a
    // second drag's samples to accidentally mix in.
    val velocityTracker = remember { VelocityTracker() }

    // Idle Submit-button pulse (framed as functional, not decorative): tracks how long a
    // VALID staged word (game.currentStagedWordIsValid(), not just "something is staged" --
    // an illegal or incomplete placement shouldn't nudge the player to submit it) has sat
    // unsubmitted. Recomputed whenever the pending placements or whose turn it is changes.
    var submitPulseActive by remember { mutableStateOf(false) }
    LaunchedEffect(s.pending, s.currentPlayerIndex) {
        submitPulseActive = false
        if (s.pending.isEmpty() || !game.currentStagedWordIsValid()) return@LaunchedEffect
        delay(SUBMIT_IDLE_PULSE_DELAY_MS)
        submitPulseActive = true
    }

    // Swap mode: multi-select rack tiles to exchange for new ones from the bag, distinct
    // from the single-select-to-place flow above.
    var swapMode by remember { mutableStateOf(false) }
    var swapSelectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val humanIndex = humanIndex(s, activeContext)
    val isMyTurn = s.currentPlayerIndex == humanIndex && !s.matchOver
    val tileSizePx = with(density) { TILE_RACK_TILE.toPx() }

    LaunchedEffect(s.currentPlayerIndex) {
        // A new turn invalidates any leftover selection state from the previous player.
        swapMode = false
        swapSelectedIds = emptySet()
        selectedTileId = null
    }

    fun placeTile(row: Int, col: Int, tile: RackTile) {
        if (tile.isBlank) {
            blankPickerFor = Triple(row, col, tile.instanceId)
        } else {
            game.stageTile(row, col, tile, tile.letter)
            sounds.playPlace()
            currentHaptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // A tap on a board cell: take back a staged tile, or put the selected rack tile on an empty
    // square. Re-reads the live state because the `actionable` flag a cell was composed with can be
    // a frame stale.
    val onCellTap: (Int, Int) -> Unit = { row, col ->
        val live = game.state.value
        if (live != null && !live.matchOver) {
            if (live.pending.any { it.row == row && it.col == col }) {
                game.unstageTile(row, col)
            } else if (live.board[row][col].tile == null) {
                val tileId = selectedTileId
                if (tileId != null) {
                    val tile = live.players.getOrNull(humanIndex)?.rack?.firstOrNull { it.instanceId == tileId }
                    if (tile != null) {
                        placeTile(row, col, tile)
                        selectedTileId = null
                    }
                }
            }
        }
    }

    val current = s.players[s.currentPlayerIndex]
    val vsBot = s.players.any { it.isBot }
    val statusText = when {
        s.matchOver -> "Game over"
        current.isBot -> "${current.displayName} is thinking…"
        swapMode -> "Pick the tiles to swap"
        vsBot -> "Your turn"
        else -> "${current.displayName}'s turn"
    }
    val infoText = buildString {
        append("Bag ${s.bagCount}")
        if (vsBot) {
            append(" · CPU ")
            append(settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() })
        }
    }
    val rackTitle = if (swapMode) "Swap mode — tap tiles to exchange, then Swap"
    else "Your rack — tap a tile, then a square, or drag it onto the board"

    val stagedIds = s.pending.map { it.tile.instanceId }.toSet()
    val visibleRack = (s.players.getOrNull(humanIndex)?.rack ?: emptyList()).filter { it.instanceId !in stagedIds }

    // Which pane sits in the top-right corner, under the chrome's corner button: the rack pane when
    // it is the right-hand pane (book posture, or the wide side-pane layout), otherwise the score row.
    val foldState = LocalFoldState.current
    val layoutMode = rememberAdaptiveLayoutMode(foldState)
    val rackOnRight = foldState.isBookPosture || layoutMode == AdaptiveLayoutMode.TABLET
    val scoreRowEndInset = if (rackOnRight) 0.dp else GameChromeEndInset
    val rackTitleEndInset = if (rackOnRight) GameChromeEndInset else 0.dp

    GameChrome(
        helpTitle = TILE_HELP_TITLE,
        helpText = TILE_HELP_TEXT,
        matchInProgress = !s.matchOver,
        onLeave = onMatchEnded,
        // One game, nothing finished yet: leaving is always a pure abort (never a win or a loss),
        // and an abandoned game has no result to show, so this leaves at once. `exiting` keeps the
        // engine's matchOver (flipped by the abort) from drawing a result panel for the frame
        // before navigation lands. If the game happened to finish in the same instant, abortMatch
        // is a no-op (the real result stands) and this still leaves.
        onAbort = {
            exiting = true
            game.abortMatch()
            onMatchEnded()
        },
        buttonFill = screenBackground,
        buttonContent = screenInk,
        extraItems = { dismiss ->
            // Motion-intensity tier -- see TileMotionPrefsStore.kt's KDoc for exactly what Maximum
            // unlocks over Standard. Lives in the menu now rather than as a play-row button.
            DropdownMenuItem(
                text = {
                    Text(
                        if (maximumTier) "Motion: Maximum (switch to Standard)"
                        else "Motion: Standard (switch to Maximum)"
                    )
                },
                onClick = {
                    dismiss()
                    coroutineScope.launch {
                        motionPrefsStore.setMotionTier(
                            if (maximumTier) TileMotionTier.STANDARD else TileMotionTier.MAXIMUM
                        )
                    }
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBackground)
                .onGloballyPositioned { coords -> overlayOrigin = coords.positionInRoot() }
                // Maximum-tier camera micro-punch on a Bingo (see the LaunchedEffect driving
                // cameraPunch above) -- a no-op scale of 1f the rest of the time/on Standard.
                .graphicsLayer {
                    scaleX = cameraPunch.value
                    scaleY = cameraPunch.value
                }
        ) {
            // Board on one pane, rack + actions on the other when unfolded in book
            // posture — same "table vs. hand" split as UNO. Drag-drop still works
            // across the hinge: cellBounds/tileCoords are root-window coordinates,
            // not pane-relative, so hit-testing is unaffected by which pane a tile
            // or cell physically renders in.
            AdaptiveTwoPane(
                foldState = foldState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                primary = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TileStatusHeader(
                            s = s,
                            statusText = statusText,
                            infoText = infoText,
                            scoreRowEndInset = scoreRowEndInset,
                            ink = screenInk,
                            onConfirmWord = { word ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("\"$word\" is a valid dictionary word.")
                                }
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        TileBoardSlot(
                            s = s,
                            cellBounds = cellBounds,
                            hoverCell = hoverCell,
                            isOccupiedNow = isOccupiedNow,
                            isMyTurn = isMyTurn,
                            tileSelected = selectedTileId != null,
                            enhanced = enhanced,
                            card3D = card3D,
                            colorblind = colorblind,
                            onCellTap = onCellTap,
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        )
                    }
                },
                secondary = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Keeps the rack grid below the corner button when this pane is the
                                // right-hand one (the 4th slot of a two-row rack would sit under it).
                                .heightIn(min = if (rackOnRight) 52.dp else 0.dp)
                                .padding(end = rackTitleEndInset),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(rackTitle, style = MaterialTheme.typography.titleSmall, color = screenInk)
                        }
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            // Seven 48dp touch targets in one row when the pane is wide enough,
                            // otherwise 4 + 3 over two rows -- never shrunk below 48dp, never a
                            // sideways scroll (a tile drag and a rack scroll would fight).
                            val perRow = if (maxWidth >= TILE_RACK_SLOT * TILE_RACK_SIZE) TILE_RACK_SIZE else 4
                            val rackRows = if (perRow >= TILE_RACK_SIZE) 1 else 2
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(perRow),
                                modifier = Modifier.fillMaxWidth().height(TILE_RACK_SLOT * rackRows),
                                userScrollEnabled = false
                            ) {
                                items(visibleRack, key = { it.instanceId }) { tile ->
                                    val isBeingDragged = draggedTile?.instanceId == tile.instanceId
                                    TileRackTileView(
                                        tile = tile,
                                        isBeingDragged = isBeingDragged,
                                        selected = tile.instanceId == selectedTileId,
                                        swapPicked = swapMode && tile.instanceId in swapSelectedIds,
                                        enabled = isMyTurn,
                                        swapMode = swapMode,
                                        enhanced = enhanced,
                                        card3D = card3D,
                                        onClick = {
                                            if (swapMode) {
                                                swapSelectedIds = if (tile.instanceId in swapSelectedIds) {
                                                    swapSelectedIds - tile.instanceId
                                                } else {
                                                    swapSelectedIds + tile.instanceId
                                                }
                                            } else {
                                                val wasSelected = selectedTileId == tile.instanceId
                                                selectedTileId = if (wasSelected) null else tile.instanceId
                                                // LIGHT_TICK on pickup -- the tap-to-select equivalent of picking the
                                                // tile up (the drag gesture's own onDragStart below fires the same
                                                // signal for the drag path). Only on an actual new selection, not a
                                                // deselect, mirroring SolitaireScreen's own "LIGHT_TICK on select".
                                                if (!wasSelected) currentHaptics(HapticSignal.LIGHT_TICK)
                                            }
                                        },
                                        // Automatic reflow animation whenever a tile is placed (removed from
                                        // the rack) or drawn (added to it) — gated the same way as everything
                                        // else here: null specs when `enhanced` is off collapse this to the
                                        // instant, un-animated reflow the rack had before.
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = if (enhanced) tween(150) else null,
                                            placementSpec = if (enhanced) spring(stiffness = Spring.StiffnessMediumLow) else null,
                                            fadeOutSpec = if (enhanced) tween(150) else null
                                        ),
                                        positionModifier = Modifier.onGloballyPositioned { coords ->
                                            tileCoords[tile.instanceId] = coords
                                        },
                                        dragModifier = Modifier.pointerInputDrag(
                                            enabled = isMyTurn && !swapMode,
                                            onDragStart = { localOffset ->
                                                val coords = tileCoords[tile.instanceId] ?: return@pointerInputDrag
                                                draggedTile = tile
                                                dragPosition = coords.localToRoot(localOffset)
                                                selectedTileId = null
                                                velocityTracker.resetTracking()
                                                currentHaptics(HapticSignal.LIGHT_TICK)
                                            },
                                            onDrag = { change, dragAmount ->
                                                dragPosition += dragAmount
                                                // Real-physics settle (Maximum tier, see onDragEnd below): sampled
                                                // in the tile's OWN local coordinate space (change.position), not
                                                // root-space dragPosition -- a pure translation offset between the
                                                // two doesn't change the VELOCITY (a derivative), so this is exactly
                                                // equivalent while avoiding a second running position to keep in sync.
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                            },
                                            onDragEnd = {
                                                val target = cellBounds.entries.firstOrNull { it.value.contains(dragPosition) }?.key
                                                val latest = game.state.value
                                                var placed = false
                                                if (target != null && latest != null && !latest.matchOver) {
                                                    val (row, col) = target
                                                    val occupied = latest.board[row][col].tile != null ||
                                                        latest.pending.any { it.row == row && it.col == col }
                                                    if (!occupied) {
                                                        placed = true
                                                        // Drop settle: commit the placement immediately —
                                                        // same convention as UnoScreen's fly-to-discard overlay, real
                                                        // state lands first — then, when enhanced, let the floating
                                                        // tile fly on from its release point to the cell's known
                                                        // center (cellBounds, already tracked via onGloballyPositioned)
                                                        // instead of vanishing in place this same frame.
                                                        val releasePos = dragPosition
                                                        placeTile(row, col, tile)
                                                        if (currentEnhanced) {
                                                            val targetCenter = cellBounds[row to col]?.center ?: releasePos
                                                            val progressVelocity = if (currentMaximumTier) {
                                                                releaseProgressVelocity(velocityTracker, releasePos, targetCenter)
                                                            } else {
                                                                0f
                                                            }
                                                            settling = SettlingTile(tile, releasePos, targetCenter, progressVelocity)
                                                        }
                                                    }
                                                }
                                                // FAILURE on an illegal drop -- no cell under the release point, or
                                                // one that's already occupied. A legal drop's own NORMAL_ACTION
                                                // haptic (and sounds.playPlace()) already fires from inside placeTile().
                                                if (!placed) {
                                                    currentHaptics(HapticSignal.FAILURE)
                                                    playSfx(SfxKind.INVALID_BUZZ)
                                                }
                                                velocityTracker.resetTracking()
                                                draggedTile = null
                                            },
                                            onDragCancel = {
                                                velocityTracker.resetTracking()
                                                draggedTile = null
                                            }
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        TileActionRow(
                            swapMode = swapMode,
                            isMyTurn = isMyTurn,
                            hasPending = s.pending.isNotEmpty(),
                            canSwapNow = s.bagCount > 0,
                            swapPickedCount = swapSelectedIds.size,
                            swapReady = swapSelectedIds.isNotEmpty() && swapSelectedIds.size <= s.bagCount,
                            pulseActive = submitPulseActive && enhanced,
                            onSubmit = {
                                val error = game.submitMove()
                                if (error != null) {
                                    // A rejected Submit was snackbar-only; now it also buzzes.
                                    currentHaptics(HapticSignal.FAILURE)
                                    playSfx(SfxKind.INVALID_BUZZ)
                                    coroutineScope.launch { snackbarHostState.showSnackbar(error) }
                                }
                            },
                            onClear = { game.clearStaged() },
                            onPass = {
                                currentHaptics(HapticSignal.NORMAL_ACTION)
                                playSfx(SfxKind.LIGHT_TICK)
                                game.pass()
                            },
                            onStartSwap = {
                                // Staged tiles never ride along into a swap: the engine's swapTiles
                                // ends the turn without clearing them, so they would leak into the
                                // next player's staging.
                                game.clearStaged()
                                swapMode = true
                                selectedTileId = null
                            },
                            onConfirmSwap = {
                                game.swapTiles(swapSelectedIds)
                                swapSelectedIds = emptySet()
                                swapMode = false
                                currentHaptics(HapticSignal.NORMAL_ACTION)
                                playSfx(SfxKind.WHOOSH)
                            },
                            onCancelSwap = {
                                swapMode = false
                                swapSelectedIds = emptySet()
                            }
                        )
                    }
                }
            )

            // Floating drag overlay — drawn last so it renders above the board/rack, unaffected
            // by list clipping. Two phases share this one Box: `floating` while actively
            // dragging (tracks the finger via dragPosition), and `settlingNow` for the brief
            // post-release flight — mutually exclusive in time, since `settling` is only ever
            // set the same frame `draggedTile` is nulled. The position is computed INSIDE the
            // offset lambda (layout phase), so a drag frame moves the tile without recomposing
            // the screen.
            val floating = draggedTile
            val settlingNow = settling
            if (floating != null || settlingNow != null) {
                val shownTile = floating ?: settlingNow!!.tile
                Box(
                    modifier = Modifier
                        .offset {
                            val pos = if (floating != null) {
                                dragPosition
                            } else {
                                val flight = settlingNow!!
                                val t = settleProgress.value
                                Offset(
                                    flight.from.x + (flight.to.x - flight.from.x) * t,
                                    flight.from.y + (flight.to.y - flight.from.y) * t
                                )
                            }
                            IntOffset(
                                (pos.x - overlayOrigin.x - tileSizePx / 2).roundToInt(),
                                (pos.y - overlayOrigin.y - tileSizePx / 2).roundToInt()
                            )
                        }
                        // Lifted: a touch larger, with a shadow, so it reads as held above the board.
                        .graphicsLayer {
                            scaleX = 1.1f
                            scaleY = 1.1f
                            shadowElevation = 8.dp.toPx()
                            shape = RoundedCornerShape(6.dp)
                            clip = false
                        }
                        .clearAndSetSemantics {}
                ) {
                    TileFace(
                        letter = if (shownTile.isBlank) null else shownTile.letter,
                        points = TileBag.valueOf(shownTile),
                        isBlank = shownTile.isBlank,
                        tileSize = TILE_RACK_TILE,
                        face = TILE_FACE_SELECTED,
                        emphasized = true
                    )
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

            // Word-completion flourish: an escalating sparkle/pulse/banner by WordPlayResult.tier
            // (see game.setOnWordPlayed above) -- a 2-point word and a 50-point Bingo looked
            // identical on submit before the premium pass. Human plays only; not tier-gated
            // (applies on Standard too); only the camera micro-punch above is Maximum-exclusive.
            if (enhanced) {
                WordPlayFlourishOverlay(flourish = wordFlourish, nonce = wordFlourishNonce, modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    val blankPos = blankPickerFor
    if (blankPos != null) {
        val (row, col, tileId) = blankPos
        TileBlankLetterDialog(
            onLetterChosen = { letter ->
                val blankTile = s.players.getOrNull(humanIndex)?.rack?.firstOrNull { it.instanceId == tileId }
                if (blankTile != null) {
                    game.stageTile(row, col, blankTile, letter)
                    // Parity with the non-blank placeTile() path above -- a blank tile's
                    // placement was previously silent on both counts (no sound, no haptic).
                    sounds.playPlace()
                    currentHaptics(HapticSignal.NORMAL_ACTION)
                }
                blankPickerFor = null
            },
            onDismiss = { blankPickerFor = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Header: scores, turn prompt, last play
// ---------------------------------------------------------------------------

/**
 * Score chips (the top row reserves [scoreRowEndInset] for the chrome's corner button), the turn
 * prompt, the last play, and one row with the tappable last-played words and the bag / CPU info.
 *
 * Word transparency affordance: submitMove() already dictionary-checks every word before it's ever
 * committed, so a real "challenge that overturns an already-committed play" isn't meaningful here
 * — nothing invalid ever lands on the board. What players (especially against the bot) lack is a
 * way to tell whether an obscure-looking play was a real word, so tapping a just-played word
 * surfaces the confirmation that the check already ran and passed. Informational only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileStatusHeader(
    s: TileGameState,
    statusText: String,
    infoText: String,
    scoreRowEndInset: Dp,
    ink: Color,
    onConfirmWord: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = scoreRowEndInset),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            s.players.forEachIndexed { i, p ->
                TileScoreChip(
                    name = p.displayName,
                    score = p.score,
                    isTurn = i == s.currentPlayerIndex && !s.matchOver,
                    ink = ink,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // The turn prompt / CPU-thinking line, and the last play: polite live regions so a screen
        // reader announces the CPU's reply without being asked.
        Text(
            statusText,
            color = ink,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        Text(
            s.lastAction,
            color = ink.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                s.lastPlayedWords.forEach { word ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            // Mouse/trackpad hover cursor (§4c) -- a real clickable, not decorative text.
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClickLabel = "Confirm $word is a valid word", role = Role.Button) {
                                onConfirmWord(word)
                            }
                            .semantics { contentDescription = "$word, last played word" }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            word,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clearAndSetSemantics {}
                        )
                    }
                }
            }
            Text(infoText, color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TileScoreChip(name: String, score: Int, isTurn: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val description = buildString {
        append(name)
        append(", ")
        append(if (score == 1) "1 point" else "$score points")
        if (isTurn) append(", to move")
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (isTurn) ink.copy(alpha = 0.12f) else Color.Transparent)
            // The turn is shown by a heavier outline and bold name as well as the tint.
            .then(if (isTurn) Modifier.border(1.5.dp, ink.copy(alpha = 0.55f), shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // One labelled node per chip: name, points, whose turn. The two texts are visual only.
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            name,
            color = ink,
            fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            score.toString(),
            color = ink,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The 15x15 board in its slot. Sized from THIS slot's own measured space (not an outer scope):
 * [fitBoard] never returns a footprint larger than the space it is given, so unlike the old
 * `maxOf(24.dp, ...)` floor, which also ignored the 6dp frame and was therefore ALWAYS 12dp too big
 * (a permanent scroll), the board cannot exceed its container. When the slot cannot give a cell
 * [TILE_MIN_CELL_DP], the cell stays that size and the board pans in both directions instead.
 *
 * Board material identity: a cached wood-board baseline framing the grid. Deliberately a STATIC
 * Brush, not an animated shader -- a continuously-moving board texture is the wrong call under 225
 * small precision drag targets, so only individual TILES get the shared specularSweep.
 */
@Composable
private fun TileBoardSlot(
    s: TileGameState,
    cellBounds: MutableMap<Pair<Int, Int>, Rect>,
    hoverCell: State<Pair<Int, Int>?>,
    isOccupiedNow: (Int, Int) -> Boolean,
    isMyTurn: Boolean,
    tileSelected: Boolean,
    enhanced: Boolean,
    card3D: Boolean,
    colorblind: Boolean,
    onCellTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = BOARD_SIZE,
            rows = BOARD_SIZE,
            framePx = TILE_BOARD_FRAME.value,
            minCellPx = TILE_MIN_CELL_DP,
            maxCellPx = TILE_MAX_CELL_DP
        )
        // The slot cannot give a cell TILE_MIN_CELL_DP: keep the minimum and let the board pan
        // rather than shrink further.
        val scrolls = !fit.meetsMinimum
        // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed by
        // one more: fifteen rounded-up cells plus the rounded frame would otherwise overshoot the
        // slot by a pixel or two and squeeze the last column.
        val cellSize: Dp = if (scrolls) {
            TILE_MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }

        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(WOOD_BOARD_BRUSH)
                    .padding(TILE_BOARD_FRAME)
            ) {
                Column(modifier = Modifier.background(TILE_GRID_LINE)) {
                    for (row in 0 until BOARD_SIZE) {
                        Row {
                            for (col in 0 until BOARD_SIZE) {
                                val cell = s.board[row][col]
                                val pending = s.pending.firstOrNull { it.row == row && it.col == col }
                                TileCellView(
                                    row = row,
                                    col = col,
                                    cell = cell,
                                    pending = pending,
                                    squareType = TILE_SQUARE_TYPES[row][col],
                                    cellSize = cellSize,
                                    hoverCell = hoverCell,
                                    isOccupiedNow = isOccupiedNow,
                                    actionable = isMyTurn && (pending != null || (cell.tile == null && tileSelected)),
                                    enhanced = enhanced,
                                    card3D = card3D,
                                    colorblind = colorblind,
                                    cellBounds = cellBounds,
                                    onTap = { onCellTap(row, col) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One board square. Empty: its premium colour plus a text label. Occupied: a raised tile on a plain
 * square. While a rack tile is dragged over it, it shows a legal (green) or occupied (orange, with a
 * cross) drop highlight; the highlight is derived per cell from the single shared [hoverCell], so it
 * only recomposes when its own answer flips.
 *
 * NOTE: `remember(row, col)` only recreates the derivedStateOf when row/col change (never, for a
 * fixed grid cell), so its calculation closes over the FIRST [hoverCell] and [isOccupiedNow]; both
 * are stable and read only snapshot-state-backed values.
 */
@Composable
private fun TileCellView(
    row: Int,
    col: Int,
    cell: BoardCell,
    pending: PendingPlacement?,
    squareType: SquareType,
    cellSize: Dp,
    hoverCell: State<Pair<Int, Int>?>,
    isOccupiedNow: (Int, Int) -> Boolean,
    actionable: Boolean,
    enhanced: Boolean,
    card3D: Boolean,
    colorblind: Boolean,
    cellBounds: MutableMap<Pair<Int, Int>, Rect>,
    onTap: () -> Unit
) {
    // Tri-state: null = not hovering this cell, true = hovering an empty/legal cell, false =
    // hovering an occupied one (a committed tile OR another staged letter) -- mirrors onDragEnd's
    // own occupied check exactly, so a "legal" highlight is never followed by a silent no-op drop.
    val hoverState by remember(row, col) {
        derivedStateOf<Boolean?> {
            if (hoverCell.value != (row to col)) null else !isOccupiedNow(row, col)
        }
    }
    val dropOk = hoverState == true
    val dropBlocked = hoverState == false

    val displayTile = pending?.tile ?: cell.tile
    val displayLetter = pending?.chosenLetter ?: cell.effectiveLetter
    val isStaged = pending != null
    val description = remember(row, col, squareType, displayLetter, displayTile, isStaged) {
        tileCellDescription(row, col, squareType, displayLetter, displayTile, isStaged)
    }
    val fill = when {
        dropOk -> TILE_HOVER_OK_FILL
        dropBlocked -> TILE_HOVER_BLOCKED_FILL
        displayTile != null -> TILE_SQUARE_NORMAL
        else -> tileSquareColor(squareType)
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            // Bounds BEFORE the hairline padding so neighbouring cells touch: no dead strip between
            // two drop targets.
            .onGloballyPositioned { coords -> cellBounds[row to col] = coords.boundsInRoot() }
            .padding(0.5.dp)
            .background(fill)
            .then(
                if (dropOk || dropBlocked) {
                    Modifier.border(2.dp, if (dropOk) TILE_HOVER_OK_BORDER else TILE_HOVER_BLOCKED_BORDER)
                } else {
                    Modifier
                }
            )
            .then(
                if (actionable) {
                    // Mouse/trackpad hover cursor (§4c) -- a real tap/drop target, purely additive.
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = if (isStaged) "Take tile back" else "Place selected tile here",
                            role = Role.Button,
                            onClick = onTap
                        )
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (displayTile != null && displayLetter != null) {
            TilePlacedFace(
                letter = displayLetter,
                points = TileBag.valueOf(displayTile),
                isBlank = displayTile.isBlank,
                staged = isStaged,
                tileId = displayTile.instanceId,
                tileSize = cellSize - 3.dp,
                enhanced = enhanced,
                // Only a freshly staged tile shimmers; the dozens of committed ones stay still (a
                // RuntimeShader loop per tile is real GPU cost on a full board).
                sheen = card3D && isStaged
            )
        } else {
            val label = tilePremiumLabel(squareType)
            if (label != null) {
                val density = LocalDensity.current
                val labelSp = with(density) { (cellSize.value * 0.34f).dp.toSp() }
                Text(
                    label,
                    color = TILE_LABEL_INK.copy(alpha = if (colorblind) 1f else 0.9f),
                    fontWeight = if (colorblind) FontWeight.Black else FontWeight.Bold,
                    fontSize = labelSp,
                    lineHeight = labelSp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
        if (dropBlocked) {
            val density = LocalDensity.current
            val crossSp = with(density) { (cellSize.value * 0.55f).dp.toSp() }
            Text(
                "✕",
                color = TILE_HOVER_BLOCKED_BORDER,
                fontWeight = FontWeight.Black,
                fontSize = crossSp,
                lineHeight = crossSp,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

/**
 * A tile sitting on the board (staged or committed). Placement settle: a scale+fade-in pop keyed on
 * the shown tile's instance id, so it fires exactly once whenever a NEW tile instance starts
 * occupying this cell -- whether it got here via drag-drop (the floating tile lands on top of this at
 * roughly the same moment) or the tap-to-place fallback (which has no floating tile at all, so this is
 * its only placement cue). Committing a staged tile keeps the same instance id, so it does not
 * re-fire when a staged tile becomes permanent. Lives in its own composable so the empty squares do
 * not each carry an Animatable and an effect.
 */
@Composable
private fun TilePlacedFace(
    letter: Char,
    points: Int,
    isBlank: Boolean,
    staged: Boolean,
    tileId: Int,
    tileSize: Dp,
    enhanced: Boolean,
    sheen: Boolean
) {
    val pop = remember(tileId) { Animatable(if (enhanced) 0f else 1f) }
    LaunchedEffect(tileId) {
        if (enhanced) {
            pop.snapTo(0f)
            pop.animateTo(1f, animationSpec = tween(140))
        }
    }
    TileFace(
        letter = letter,
        points = points,
        isBlank = isBlank,
        tileSize = tileSize,
        face = if (staged) TILE_FACE_STAGED else TILE_FACE,
        emphasized = staged,
        sheen = sheen,
        modifier = Modifier.graphicsLayer {
            val p = pop.value
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            alpha = p
        }
    )
}

/**
 * One physical tile: a rounded ivory face with a darker lower edge (so it reads as raised), the
 * letter, and its point value as a subscript (a blank shows an italic letter and no value; an
 * unassigned blank in the rack, [letter] null, shows "?"). Ink is explicit so the letter reads in dark
 * theme too (the old tiles used the theme's text colour on a light literal fill: 1.2:1 in dark).
 * [emphasized] draws the heavy outline used for selected / staged / swap-picked tiles, so those states
 * are never colour alone; [badge] is a small corner mark (the swap tick).
 *
 * Text sizes derive from [tileSize] through Density.toSp on a dp value, so they do not grow with the
 * system font scale and cannot overflow a fixed-size board cell.
 */
@Composable
private fun TileFace(
    letter: Char?,
    points: Int,
    isBlank: Boolean,
    tileSize: Dp,
    face: Color,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    sheen: Boolean = false
) {
    val density = LocalDensity.current
    val letterSp = with(density) { (tileSize.value * 0.52f).dp.toSp() }
    val pointSp = with(density) { (tileSize.value * 0.30f).coerceAtLeast(8f).dp.toSp() }
    val corner = (tileSize.value * 0.14f).dp
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(tileSize)
            .drawBehind {
                // The raised lower edge: the same rounded rect, pushed down 1.5dp, behind the face.
                drawRoundRect(
                    color = TILE_EDGE,
                    topLeft = Offset(0f, 1.5.dp.toPx()),
                    size = this.size,
                    cornerRadius = CornerRadius(corner.toPx())
                )
            }
            .clip(shape)
            .background(face)
            .border(if (emphasized) 2.dp else 1.dp, if (emphasized) TILE_INK else TILE_EDGE, shape)
            // Tile material identity (gloss sheen): the shared specular sweep -- "one of the most
            // literal fits for the shared shader in the whole suite". No-op unless [sheen] is on.
            .specularSweep(enabled = sheen, tint = TILE_GLOSS_TINT, periodMs = TILE_GLOSS_PERIOD_MS),
        contentAlignment = Alignment.Center
    ) {
        val letterIsChosen = isBlank && letter != null
        Text(
            (letter ?: '?').toString(),
            color = TILE_INK.copy(alpha = if (letterIsChosen) 0.65f else 1f),
            fontWeight = FontWeight.Bold,
            fontStyle = if (letterIsChosen) FontStyle.Italic else FontStyle.Normal,
            fontSize = letterSp,
            lineHeight = letterSp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {}
        )
        if (points > 0) {
            Text(
                points.toString(),
                color = TILE_INK.copy(alpha = 0.85f),
                fontSize = pointSp,
                lineHeight = pointSp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 1.dp)
                    .clearAndSetSemantics {}
            )
        }
        if (badge != null) {
            Text(
                badge,
                color = TILE_INK,
                fontWeight = FontWeight.Black,
                fontSize = pointSp,
                lineHeight = pointSp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp, top = 1.dp)
                    .clearAndSetSemantics {}
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rack and actions
// ---------------------------------------------------------------------------

/**
 * One rack tile in a 48dp-high slot (the touch target; the visible tile is [TILE_RACK_TILE]).
 * [modifier] carries the lazy-grid reflow animation, [positionModifier] the coordinate tracking and
 * [dragModifier] the drag gesture; they are applied in that order around the tap handler, the same
 * order the old inline tile used (drag innermost, so a drag past the touch slop cancels the tap).
 *
 * Pickup lift: an animateFloatAsState whose spec is only ever a real spring/tween when the motion
 * setting allows it (snap() otherwise), expressed as scale + shadow + ghost-alpha, since this tile
 * doesn't lift in place -- while dragged, the separate floating overlay follows the finger. With
 * `enhanced` off this collapses to an instant, fully transparent rack slot with no scale or shadow.
 */
@Composable
private fun TileRackTileView(
    tile: RackTile,
    isBeingDragged: Boolean,
    selected: Boolean,
    swapPicked: Boolean,
    enabled: Boolean,
    swapMode: Boolean,
    enhanced: Boolean,
    card3D: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    positionModifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier
) {
    val liftScale by animateFloatAsState(
        targetValue = if (isBeingDragged && enhanced) 1.15f else 1f,
        animationSpec = if (enhanced) spring(dampingRatio = Spring.DampingRatioMediumBouncy) else snap(),
        label = "rackTileLiftScale"
    )
    val liftElevation by animateFloatAsState(
        targetValue = if (isBeingDragged && enhanced) 8f else 0f,
        animationSpec = if (enhanced) tween(120) else snap(),
        label = "rackTileLiftElevation"
    )
    val rackTileAlpha by animateFloatAsState(
        targetValue = if (isBeingDragged) (if (enhanced) 0.35f else 0f) else 1f,
        animationSpec = if (enhanced) tween(120) else snap(),
        label = "rackTileLiftAlpha"
    )

    val points = TileBag.valueOf(tile)
    val description = remember(tile, selected, swapPicked) {
        val base = if (tile.isBlank) "Blank tile, 0 points"
        else "Letter ${tile.letter}, $points point${if (points == 1) "" else "s"}"
        base + when {
            swapPicked -> ", picked to swap"
            selected -> ", selected"
            else -> ""
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TILE_RACK_SLOT)
            .then(positionModifier)
            .then(
                if (enabled && !isBeingDragged) {
                    // Mouse/trackpad hover cursor (§4c) -- a real draggable/tappable rack tile.
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = if (swapMode) "Pick tile to swap" else "Select tile",
                            role = Role.Button,
                            onClick = onClick
                        )
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description }
            .then(dragModifier),
        contentAlignment = Alignment.Center
    ) {
        TileFace(
            letter = if (tile.isBlank) null else tile.letter,
            points = points,
            isBlank = tile.isBlank,
            tileSize = TILE_RACK_TILE,
            face = when {
                swapPicked -> TILE_FACE_SWAP
                selected -> TILE_FACE_SELECTED
                else -> TILE_FACE
            },
            emphasized = selected || swapPicked,
            badge = if (swapPicked) "✓" else null,
            sheen = card3D,
            modifier = Modifier.graphicsLayer {
                scaleX = liftScale
                scaleY = liftScale
                alpha = rackTileAlpha
                shadowElevation = liftElevation.dp.toPx()
                shape = RoundedCornerShape(6.dp)
                clip = false
            }
        )
    }
}

/**
 * Submit / Clear / Pass / Swap (or, in swap mode, Swap (n) / Cancel). Submit and Clear are only live
 * with something staged. A flow row, so a narrow pane or a large font wraps instead of clipping. The
 * "Pass" button is always present on the human's turn (the on-device rotation test taps it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileActionRow(
    swapMode: Boolean,
    isMyTurn: Boolean,
    hasPending: Boolean,
    canSwapNow: Boolean,
    swapPickedCount: Int,
    swapReady: Boolean,
    pulseActive: Boolean,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onPass: () -> Unit,
    onStartSwap: () -> Unit,
    onConfirmSwap: () -> Unit,
    onCancelSwap: () -> Unit
) {
    val pulseScale = rememberSubmitPulse(pulseActive)
    // Mouse/trackpad hover cursor (§4c) on every real game-action control button in this row --
    // purely additive, zero effect on touch input.
    val buttonModifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (swapMode) {
            Button(
                enabled = isMyTurn && swapReady,
                onClick = onConfirmSwap,
                modifier = buttonModifier,
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Swap ($swapPickedCount)", maxLines = 1, softWrap = false) }
            OutlinedButton(
                enabled = isMyTurn,
                onClick = onCancelSwap,
                modifier = buttonModifier,
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Cancel", maxLines = 1, softWrap = false) }
        } else {
            Button(
                enabled = isMyTurn && hasPending,
                onClick = onSubmit,
                modifier = buttonModifier.graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Submit", maxLines = 1, softWrap = false) }
            OutlinedButton(
                enabled = isMyTurn && hasPending,
                onClick = onClear,
                modifier = buttonModifier,
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Clear", maxLines = 1, softWrap = false) }
            OutlinedButton(
                enabled = isMyTurn,
                onClick = onPass,
                modifier = buttonModifier,
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Pass", maxLines = 1, softWrap = false) }
            OutlinedButton(
                enabled = isMyTurn && canSwapNow,
                onClick = onStartSwap,
                modifier = buttonModifier,
                contentPadding = TILE_ACTION_PADDING
            ) { Text("Swap", maxLines = 1, softWrap = false) }
        }
    }
}

/**
 * Idle Submit-button pulse: a slow, low-amplitude scale breathing, a functional nudge rather than
 * decoration, so it is a real infinite transition rather than a one-shot. The transition only exists
 * while [active]; the old version ran one forever (animating 1f -> 1f) even when idle.
 */
@Composable
private fun rememberSubmitPulse(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "submitPulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "submitPulseScale"
    )
    return scale
}

// ---------------------------------------------------------------------------
// Result panel and blank-letter dialog
// ---------------------------------------------------------------------------

/**
 * The finished-game panel. TileGame.finishGame() marks EVERY player at the max score as a winner
 * (isWinner = it.score == maxScore), so two or more players can tie; [topPlayers] carries that, so a
 * tie reads "It's a tie!" (the wording ConnectFour/DotsAndBoxes/Mancala/Reversi use) instead of
 * silently crowning the first of them. All scores are listed, final (after the end-game rack
 * adjustment), best first.
 */
@Composable
private fun TileResultPanel(
    players: List<TilePlayerState>,
    topPlayers: List<TilePlayerState>,
    ink: Color,
    background: Color,
    onBackToMenu: () -> Unit
) {
    val topIds = topPlayers.map { it.playerId }.toSet()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            tileResultTitle(topPlayers),
            style = MaterialTheme.typography.headlineSmall,
            color = ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(12.dp))
        players.sortedByDescending { it.score }.forEach { p ->
            val isTop = p.playerId in topIds
            Text(
                buildString {
                    append(p.displayName)
                    append(": ")
                    append(p.score)
                    if (isTop) append(if (topPlayers.size > 1) " (tied for first)" else " (winner)")
                },
                color = ink,
                fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        // Mouse/trackpad hover cursor (Tab S9 DeX / keyboard-cover use case,
        // docs/DEVICE_SPECIFIC_PLAN.md §4c) -- purely additive, no effect on touch.
        Button(
            onClick = onBackToMenu,
            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
        ) { Text("Back to menu") }
    }
}

/** "You win with 120 points!" / "CPU wins with 98 points!" / "It's a tie!". */
private fun tileResultTitle(topPlayers: List<TilePlayerState>): String = when {
    topPlayers.isEmpty() -> "Game over"
    topPlayers.size > 1 -> "It's a tie!"
    else -> {
        val winner = topPlayers.first()
        if (winner.displayName.equals("You", ignoreCase = true)) {
            "You win with ${winner.score} points!"
        } else {
            "${winner.displayName} wins with ${winner.score} points!"
        }
    }
}

/**
 * The blank tile's letter picker. A themed Surface (the old one was a hard-coded white panel under
 * theme-coloured text, 1.3:1 in dark theme) with 48dp letter targets, which scrolls rather than
 * clips on a short window.
 */
@Composable
private fun TileBlankLetterDialog(onLetterChosen: (Char) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Choose a letter for the blank tile", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(48.dp),
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 296.dp)
                ) {
                    items(('A'..'Z').toList()) { letter ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                // Mouse/trackpad hover cursor (§4c) -- each letter is a real "letter
                                // key" game-action target, purely additive over touch.
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClickLabel = "Use $letter", role = Role.Button) { onLetterChosen(letter) },
                            contentAlignment = Alignment.Center
                        ) { Text(letter.toString(), style = MaterialTheme.typography.titleMedium) }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)
                ) { Text("Cancel") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Drag helpers
// ---------------------------------------------------------------------------

/**
 * Small wrapper around detectDragGestures giving root-space-friendly callbacks. [onDrag]
 * is handed the raw [PointerInputChange] alongside the usual drag delta -- Word Tiles'
 * Maximum-tier velocity-based fling settle (see the rack-tile drag site) needs its
 * timestamp/position to feed a [VelocityTracker], which the plain delta alone can't give.
 */
private fun Modifier.pointerInputDrag(
    enabled: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectDragGestures(
        onDragStart = { offset -> onDragStart(offset) },
        onDrag = { change, dragAmount -> change.consume(); onDrag(change, dragAmount) },
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel
    )
}

/**
 * The release fling's velocity, projected onto the release-to-target direction and
 * rescaled into the same 0f..1f-per-second units [TileGameScreen]'s settle progress moves
 * in, for feeding straight into `Animatable.animateTo`'s `initialVelocity` (Maximum tier's
 * "honest real-physics" settle spring). Since settle progress `t` moves linearly from 0
 * (at [releasePos]) to 1 (at [targetCenter]), d(t)/d(time) at the moment of release is the
 * release velocity's component along that line, divided by the line's own length -- the
 * standard "velocity projected onto a parameterized path" derivation. Clamped to a sane
 * ceiling so an extreme flick overshoots noticeably without launching the tile off-screen.
 */
private fun releaseProgressVelocity(tracker: VelocityTracker, releasePos: Offset, targetCenter: Offset): Float {
    val velocity = tracker.calculateVelocity()
    val delta = targetCenter - releasePos
    val distanceSq = delta.x * delta.x + delta.y * delta.y
    if (distanceSq < 1f) return 0f
    val raw = (velocity.x * delta.x + velocity.y * delta.y) / distanceSq
    return raw.coerceIn(-MAX_SETTLE_PROGRESS_VELOCITY, MAX_SETTLE_PROGRESS_VELOCITY)
}

/**
 * "Which seat is this device/screen currently representing" — mode-dependent, the exact
 * same fix (and same underlying bug) as UnoScreen.kt's private `humanIndex()`:
 *  - LOCAL_AD_HOC: each device IS one specific, fixed player for the whole match —
 *    context.localPlayerIndex (assigned once by the lobby) is exactly that.
 *  - SINGLE_DEVICE_PASS_AND_PLAY: there's no single "local player" at all — the one
 *    shared device is handed around, so "my seat" is whoever's turn it currently is.
 *    This screen used to always return the first non-bot player's index regardless of
 *    mode, which is harmless for SINGLE_PLAYER_VS_BOT (only one human seat, so "first
 *    non-bot" and "current human's turn" coincide) but is a real bug for pass-and-play —
 *    players 2/3/4 could never act, since turn-gating requires
 *    s.currentPlayerIndex == humanIndex and humanIndex was frozen at whichever seat
 *    happened to be the first non-bot in the player list.
 *  - SINGLE_PLAYER_VS_BOT (default): the one human seat — first non-bot player.
 */
private fun humanIndex(s: TileGameState, context: com.gamesuite.core.GameContext): Int =
    when (context.activeMode) {
        com.gamesuite.core.PlayMode.LOCAL_AD_HOC -> context.localPlayerIndex
        com.gamesuite.core.PlayMode.SINGLE_DEVICE_PASS_AND_PLAY -> s.currentPlayerIndex
        else -> s.players.indexOfFirst { !it.isBot }.let { if (it >= 0) it else 0 }
    }

// ---------------------------------------------------------------------------
// Board text and colours
// ---------------------------------------------------------------------------

/** The square's premium colour. Hues are unchanged from the original board (functional, not decorative); the text label carries the same information without colour. */
private fun tileSquareColor(type: SquareType): Color = when (type) {
    SquareType.CENTER -> Color(0xFFEF9A9A)
    SquareType.TRIPLE_WORD -> Color(0xFFE57373)
    SquareType.DOUBLE_WORD -> Color(0xFFF8BBD0)
    SquareType.TRIPLE_LETTER -> Color(0xFF64B5F6)
    SquareType.DOUBLE_LETTER -> Color(0xFFB3E5FC)
    SquareType.NORMAL -> TILE_SQUARE_NORMAL
}

/** The 2-character label drawn on an empty premium square, null for a plain one. */
private fun tilePremiumLabel(type: SquareType): String? = when (type) {
    SquareType.DOUBLE_LETTER -> "2L"
    SquareType.TRIPLE_LETTER -> "3L"
    SquareType.DOUBLE_WORD -> "2W"
    SquareType.TRIPLE_WORD -> "3W"
    SquareType.CENTER -> "★"
    SquareType.NORMAL -> null
}

private fun tilePremiumSpoken(type: SquareType): String? = when (type) {
    SquareType.DOUBLE_LETTER -> "double letter square"
    SquareType.TRIPLE_LETTER -> "triple letter square"
    SquareType.DOUBLE_WORD -> "double word square"
    SquareType.TRIPLE_WORD -> "triple word square"
    SquareType.CENTER -> "center star, double word square"
    SquareType.NORMAL -> null
}

/**
 * Screen-reader description of one square, phrased like ReversiScreen.CellView / CheckersScreen's
 * squareDescription: what is there, then its 1-indexed row and column. A tile reads as its letter and
 * points; a blank as "Blank tile as X"; a staged one adds "not yet submitted".
 */
private fun tileCellDescription(
    row: Int,
    col: Int,
    type: SquareType,
    letter: Char?,
    tile: RackTile?,
    staged: Boolean
): String {
    val place = "row ${row + 1}, column ${col + 1}"
    if (tile != null && letter != null) {
        val points = TileBag.valueOf(tile)
        val pointsText = "$points point${if (points == 1) "" else "s"}"
        val what = if (tile.isBlank) "Blank tile as $letter" else "Letter $letter"
        val state = if (staged) ", not yet submitted" else ""
        return "$what, $pointsText$state, $place"
    }
    val square = tilePremiumSpoken(type)
    return if (square == null) "Empty, $place" else "Empty $square, $place"
}

/**
 * Word-completion flourish: a 2-point word and a 50-point Bingo used to look identical on
 * submit -- this escalates by [WordPlayResult.tier]: a modest sparkle for an ORDINARY play,
 * a stronger pulse (bigger burst + a pulsing point total) for a MULTIPLIER play, and a full
 * banner + larger burst specifically for a BINGO. Self-retiring like SolitaireScreen's own
 * CardBackFanFlourish -- once its own progress animation finishes, it draws nothing.
 * Re-keyed on [nonce] (not just [flourish] itself) so two back-to-back plays of the SAME
 * tier still restart the animation instead of no-op'ing on an unchanged data-class value.
 * Decorative: the last-play line already announces the same words and points, so the whole
 * overlay is hidden from screen readers.
 */
@Composable
private fun WordPlayFlourishOverlay(flourish: WordPlayResult?, nonce: Int, modifier: Modifier = Modifier) {
    if (flourish == null || nonce <= 0) return
    val durationMs = when (flourish.tier) {
        WordPlayTier.BINGO -> BINGO_FLOURISH_MS
        WordPlayTier.MULTIPLIER -> MULTIPLIER_FLOURISH_MS
        WordPlayTier.ORDINARY -> ORDINARY_FLOURISH_MS
    }
    val progress = remember(nonce) { Animatable(0f) }
    LaunchedEffect(nonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMs))
    }
    if (progress.value >= 1f) return

    val eased = 1f - (1f - progress.value) * (1f - progress.value)
    val fadeOutStart = 0.75f
    val alpha = if (progress.value > fadeOutStart) 1f - (progress.value - fadeOutStart) / (1f - fadeOutStart) else 1f
    val particleCount = when (flourish.tier) {
        WordPlayTier.BINGO -> BINGO_PARTICLE_COUNT
        WordPlayTier.MULTIPLIER -> MULTIPLIER_PARTICLE_COUNT
        WordPlayTier.ORDINARY -> ORDINARY_PARTICLE_COUNT
    }
    val radius = when (flourish.tier) {
        WordPlayTier.BINGO -> BINGO_PARTICLE_RADIUS_PX
        WordPlayTier.MULTIPLIER -> MULTIPLIER_PARTICLE_RADIUS_PX
        WordPlayTier.ORDINARY -> ORDINARY_PARTICLE_RADIUS_PX
    }
    val particleColor = when (flourish.tier) {
        WordPlayTier.BINGO -> Color(0xFFFFD54F)
        WordPlayTier.MULTIPLIER -> Color(0xFF4FC3F7)
        WordPlayTier.ORDINARY -> Color(0xFFA5D6A7)
    }

    Box(modifier = modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        // The particle burst, shared shape across all 3 tiers -- only count/radius/color/
        // duration escalate, so an ordinary play and a Bingo read as the same LANGUAGE at
        // different volumes, not two unrelated effects.
        for (i in 0 until particleCount) {
            val angle = (360f / particleCount) * i
            val radians = Math.toRadians(angle.toDouble())
            val dx = (kotlin.math.cos(radians) * radius * eased).toFloat()
            val dy = (kotlin.math.sin(radians) * radius * eased).toFloat()
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = dx
                        translationY = dy
                        this.alpha = alpha
                        val s = 1f - 0.5f * eased
                        scaleX = s
                        scaleY = s
                    }
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(particleColor)
            )
        }

        // MULTIPLIER/BINGO also get a scaling, escalating readout of the points just
        // earned -- a stronger pulse than the plain sparkle an ordinary play gets, and for
        // BINGO specifically, a full banner naming the play, not just its score.
        if (flourish.tier != WordPlayTier.ORDINARY) {
            val punch = 1f + 0.3f * (1f - eased).coerceAtLeast(0f) * (1f - progress.value)
            val label = if (flourish.tier == WordPlayTier.BINGO) {
                "BINGO! +${flourish.pointsGained}"
            } else {
                "+${flourish.pointsGained}"
            }
            Text(
                label,
                fontWeight = FontWeight.Bold,
                style = if (flourish.tier == WordPlayTier.BINGO) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                color = particleColor,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = punch
                        scaleY = punch
                        this.alpha = alpha
                    }
                    .background(Color.Black.copy(alpha = 0.55f * alpha), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

// ---- how to play ----

private const val TILE_HELP_TITLE = "How to Play Word Tiles"

private const val TILE_HELP_TEXT =
    "Build words on the 15 by 15 board from the seven tiles in your rack: tap a tile and then a " +
        "square, or drag it onto the board, then press Submit (tap a tile you placed to take it back). " +
        "The first word must cover the center star and every later word must connect to tiles already " +
        "played, and every word you form must be in the dictionary.\n\n" +
        "2L and 3L squares multiply one tile and 2W and 3W squares (and the star) multiply the whole " +
        "word, but only on the turn you cover them. Using all seven tiles at once earns 50 bonus " +
        "points, and a blank tile can stand for any letter but scores nothing.\n\n" +
        "Pass skips your turn, and Swap trades the tiles you pick for new ones while the bag has " +
        "enough left. The game ends when a player uses their last tile with the bag empty, or when " +
        "every player has passed twice in a row; the player who goes out gains the value of everyone " +
        "else's leftover tiles (and they lose it), and the highest score wins."

// ---- board geometry ----

/** Below this cell size (dp) the board stops shrinking and pans instead. */
private const val TILE_MIN_CELL_DP = 28f

/** Upper bound on a board cell (dp): 15 x 44 = a 660dp board, enough on a 1280dp tablet without sprawling. */
private const val TILE_MAX_CELL_DP = 44f

/** The wood rim around the grid. It is both the real padding and the `framePx` handed to [fitBoard]. */
private val TILE_BOARD_FRAME = 6.dp

/** Premium type per square, computed once (TileBoardLayout.typeAt scans lists and allocates pairs on every call). */
private val TILE_SQUARE_TYPES: Array<Array<SquareType>> =
    Array(BOARD_SIZE) { r -> Array(BOARD_SIZE) { c -> TileBoardLayout.typeAt(r, c) } }

// ---- rack geometry ----

/** The rack tile's touch target; the visible tile inside it is [TILE_RACK_TILE]. 48dp is the control floor. */
private val TILE_RACK_SLOT = 48.dp
private val TILE_RACK_TILE = 44.dp
private const val TILE_RACK_SIZE = 7

private val TILE_ACTION_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

// ---- board material identity ----

/** A cached (allocated once, file scope) wood-board baseline framing the grid -- deliberately a lighter, warmer "honey oak" than Solitaire's felt or Checkers/Chess/Mancala's own wood, so it doesn't read as a copy-paste of any of them. */
private val WOOD_BOARD_BRUSH = Brush.linearGradient(colors = listOf(Color(0xFFE0C39A), Color(0xFFC79A5B), Color(0xFF8B5E34)))

/** Shows through the hairline gaps between squares as the grid lines. */
private val TILE_GRID_LINE = Color(0xFF9A8A70)

private val TILE_SQUARE_NORMAL = Color(0xFFEEEEEE)

/** Dark ink for the 2L / 3L / 2W / 3W / star labels; the lightest premium fill (TW, 0xFFE57373) still gives about 4.4:1 against it. */
private val TILE_LABEL_INK = Color(0xFF3E2723)

// Drop highlight while dragging: legal (green) vs occupied (orange, plus a cross glyph).
private val TILE_HOVER_OK_FILL = Color(0xFFAED581)
private val TILE_HOVER_OK_BORDER = Color(0xFF558B2F)
private val TILE_HOVER_BLOCKED_FILL = Color(0xFFFFAB91)
private val TILE_HOVER_BLOCKED_BORDER = Color(0xFFD84315)

// ---- tile material identity ----

/** Explicit tile ink: the theme's text colour is light in dark theme, on a light literal tile. About 11:1 on [TILE_FACE]. */
private val TILE_INK = Color(0xFF2B1D0E)
private val TILE_EDGE = Color(0xFF8D6E3F)
private val TILE_FACE = Color(0xFFE8CF98)
private val TILE_FACE_STAGED = Color(0xFFFFF176)
private val TILE_FACE_SELECTED = Color(0xFFFFF176)
private val TILE_FACE_SWAP = Color(0xFF80CBC4)

/** Warm ivory/plastic gloss tint for the shared specular sweep on rack and staged tiles. */
private val TILE_GLOSS_TINT = Color.White.copy(alpha = 0.30f)
private const val TILE_GLOSS_PERIOD_MS = 4200

// ---- item: velocity-based fling settle (Maximum tier) ----

/** Ceiling on the settle spring's projected initial velocity (in settle-progress units per second) -- keeps an extreme flick's overshoot dramatic without launching the tile off-screen. */
private const val MAX_SETTLE_PROGRESS_VELOCITY = 30f

// ---- idle Submit-button pulse ----

/** How long a VALID staged word has to sit unsubmitted before the Submit button starts its idle pulse. */
private const val SUBMIT_IDLE_PULSE_DELAY_MS = 4000L

// ---- word-completion flourish ----

private const val ORDINARY_FLOURISH_MS = 450
private const val MULTIPLIER_FLOURISH_MS = 650
private const val BINGO_FLOURISH_MS = 1100

private const val ORDINARY_PARTICLE_COUNT = 6
private const val MULTIPLIER_PARTICLE_COUNT = 12
private const val BINGO_PARTICLE_COUNT = 20

private const val ORDINARY_PARTICLE_RADIUS_PX = 60f
private const val MULTIPLIER_PARTICLE_RADIUS_PX = 100f
private const val BINGO_PARTICLE_RADIUS_PX = 160f
