package com.gamesuite.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordgames.tiles.*
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalCard3DMode
import com.gamesuite.settings.LocalEnhancedAnimations
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.specularSweep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Board fidelity note: placement supports BOTH a drag gesture (pick up a
 * rack tile, drag it over the board, release on a cell) and the original
 * tap-to-select-then-tap-to-place flow as a fallback. The drag path tracks
 * root-coordinate bounds for every board cell (`cellBounds`) and hit-tests
 * the drop point against them — this is meaningfully more involved than
 * UNO's single-hand drag (which only needed a vertical threshold), so it's
 * flagged in the roadmap as unverified on a physical device until confirmed
 * hands-on; the tap fallback guarantees the game stays playable either way.
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
    // 3D Perspective Mode -- gates the tile gloss/emboss sheen and the wood board's own
    // slow idle-sheen below, same combine rule as every other screen this pass touched.
    val card3D = LocalCard3DMode.current && !reducedMotion
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Ambient music (premium 2026 vision pass) -- gated on BOTH the dedicated ambient-music
    // setting AND the existing master sound toggle, same AND pattern every other feature
    // that respects CardSounds.soundEnabled already uses (see sounds.playPlace() above).
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.WORD_TILES, enabled = musicEnabled)

    // Standard/Maximum motion tier -- see TileMotionPrefsStore.kt's KDoc for exactly what
    // Maximum unlocks (the velocity-based fling settle below, and a camera micro-punch on
    // Bingo). Everything else new in this file applies regardless of this tier.
    val motionPrefsStore = remember { TileMotionPrefsStore(androidContext) }
    val motionTier by motionPrefsStore.motionTier.collectAsState(initial = TileMotionTier.STANDARD)
    val maximumTier = motionTier == TileMotionTier.MAXIMUM

    // Word-completion flourish (sparkle/pulse/banner escalation) -- see WordPlayFlourishOverlay.
    // Bumped every time a new flourish fires so re-triggering the SAME tier back-to-back
    // (e.g. two ordinary plays in a row) still restarts the animation instead of no-op'ing
    // on an unchanged data-class key.
    var wordFlourish by remember { mutableStateOf<WordPlayResult?>(null) }
    var wordFlourishNonce by remember { mutableStateOf(0) }
    // Maximum tier only: a small camera micro-punch on the whole board when a Bingo lands.
    val cameraPunch = remember { Animatable(1f) }

    // game.setOnWordPlayed below registers this callback exactly ONCE per match
    // (LaunchedEffect(context) only re-runs when the session's context itself changes) --
    // rememberUpdatedState keeps its own haptics dispatch reading the LATEST `haptics`
    // (and thus the current Haptics setting) on every call, not whatever it was the one
    // time this registration ran. Same fix, same reasoning, as SolitaireScreen.kt's own
    // `currentEnhanced`.
    val currentHaptics by rememberUpdatedState(haptics)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.setOnWordPlayed { result ->
            currentHaptics(if (result.tier == WordPlayTier.BINGO) HapticSignal.CELEBRATION else HapticSignal.SUCCESS)
            // Genuinely silent before this pass -- the sparkle/pulse/banner flourish below
            // (WordPlayFlourishOverlay) had no accompanying sound at any tier, ordinary word
            // through Bingo.
            playSfx(SfxKind.SUCCESS_CHIME)
            wordFlourish = result
            wordFlourishNonce++
        }
        game.startMatch()
    }

    // Maximum-tier camera micro-punch, fired only on a Bingo (see the setOnWordPlayed
    // callback above bumping wordFlourishNonce for every play, and result.tier gating
    // which of those actually deserves the punch).
    LaunchedEffect(wordFlourishNonce, maximumTier) {
        val flourish = wordFlourish ?: return@LaunchedEffect
        if (!maximumTier || flourish.tier != WordPlayTier.BINGO) return@LaunchedEffect
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
    val activeContext = context ?: return

    if (s.matchOver) {
        val winner = s.players.maxByOrNull { it.score }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("${winner?.displayName} wins with ${winner?.score} points!", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onMatchEnded) { Text("Back to menu") }
        }
        return
    }

    var selectedTileId by remember { mutableStateOf<Int?>(null) }
    // (row, col, blank tile's instanceId) — captured together so the letter dialog knows exactly which tile it's naming.
    var blankPickerFor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    // Drag state: which rack tile is being dragged, and its current root-space center position.
    var draggedTile by remember { mutableStateOf<RackTile?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val cellBounds = remember { mutableStateMapOf<Pair<Int, Int>, Rect>() }
    val tileCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }

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
    val isMyTurn = s.currentPlayerIndex == humanIndex
    val tileSizePx = with(density) { 44.dp.toPx() }

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
            haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
            foldState = LocalFoldState.current,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            primary = {
            Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                s.players.forEachIndexed { i, p ->
                    Text(
                        "${p.displayName}: ${p.score}",
                        fontWeight = if (i == s.currentPlayerIndex) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    )
                }
            }
            Text(s.lastAction, style = MaterialTheme.typography.bodySmall)
            if (s.lastPlayedWords.isNotEmpty()) {
                // Word transparency affordance: submitMove() already dictionary-checks every
                // word before it's ever committed, so a real "challenge that overturns an
                // already-committed play" isn't meaningful here — nothing invalid ever lands
                // on the board. What players (especially against the bot) actually lack is a
                // way to tell whether an obscure-looking play was a real word, so tapping a
                // just-played word simply surfaces the confirmation that the check already
                // ran and passed. Low-stakes, informational — not a scoring/challenge mechanic.
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    s.lastPlayedWords.forEach { word ->
                        Text(
                            word,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "$word, last played word. Double tap to confirm it is a valid dictionary word."
                                }
                                .clickable {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("\"$word\" is a valid dictionary word.")
                                    }
                                }
                        )
                    }
                }
            }
            Text("Bag: ${s.bagCount}", style = MaterialTheme.typography.labelSmall)
            if (s.players.any { it.isBot }) {
                Text(
                    "CPU difficulty: ${settings.defaultCpuDifficulty.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(4.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                // Never let cells shrink below a usable touch-target size — if the
                // available width can't fit BOARD_SIZE cells at minCellSize, widen the
                // grid past the viewport and let it scroll horizontally instead.
                //
                // Fold/rotation fix: the board is a fixed 15x15 SQUARE grid, so it must be
                // sized from the SMALLER of the available width AND height, never width
                // alone (see docs/DEVICE_SPECIFIC_PLAN.md §3's sizing principle). Sizing
                // purely from maxWidth let a wide-but-short window (the Fold 5 cover screen
                // rotated to landscape, ~344dp tall; or this TABLET-mode split's wide
                // primary pane) stretch every cell sideways to fill the width, which also
                // stretched the grid's own natural HEIGHT (same value, since cells are
                // square) well past the real vertical budget — silently hiding most of the
                // board behind an unsignposted internal scroll instead of showing the whole
                // board at a glance. Using min(width, height) keeps the board fully visible
                // whenever there's room in EITHER axis, and only falls back to the
                // pre-existing floor+scroll behavior when there truly isn't enough of
                // either.
                val minCellSize = 24.dp
                val cellSize = maxOf(minCellSize, minOf(maxWidth, maxHeight) / BOARD_SIZE)
                val boardSize = cellSize * BOARD_SIZE
                // Board material identity: a cached wood-board baseline framing the grid --
                // Word Tiles had none before this pass (flat solid Material colors only).
                // Deliberately a STATIC Brush, not an animated shader -- the brief is
                // explicit that a continuously-moving board texture is the wrong call under
                // 225 small precision drag targets, so only individual TILES (far fewer,
                // and each one a natural, physically-plausible gloss surface) get the
                // shared specularSweep below. Premium-square colors (DL/TL/DW/TW) inside the
                // grid are left exactly as they were -- those are functional, not decorative.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(WOOD_BOARD_BRUSH)
                        .padding(6.dp)
                ) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(BOARD_SIZE),
                            // .size(), not .width() alone — an explicit height keeps the grid
                            // from stretching to fill unused ambient height when the window
                            // has room to spare (it would otherwise leave a large blank
                            // wood-board panel below the actual 15 rows), while still
                            // coercing down to whatever real height IS available in the tight
                            // fallback case above, where LazyVerticalGrid's own built-in
                            // vertical scroll takes over gracefully instead of clipping.
                            modifier = Modifier.size(boardSize)
                        ) {
                        items(BOARD_SIZE * BOARD_SIZE) { index ->
                            val row = index / BOARD_SIZE
                            val col = index % BOARD_SIZE
                            val cell = s.board[row][col]
                            val pending = s.pending.firstOrNull { it.row == row && it.col == col }
                            val squareType = TileBoardLayout.typeAt(row, col)
                            // Hoisted into a derivedStateOf so this cell only recomposes when
                            // ITS drop-target status actually flips, not on every pointer-move
                            // frame of a drag (dragPosition changes far more often than the
                            // cell under it does).
                            //
                            // Bug fix: this used to report "drop target" purely from bounds
                            // containment, with no occupancy check — so hovering an already-
                            // occupied cell showed the confident green "legal" highlight, and
                            // then onDragEnd's placement check (which DOES verify occupancy)
                            // silently no-op'd, making the dragged tile vanish from under the
                            // player's finger. Tri-state result: null = not hovering this cell,
                            // true = hovering an empty/legal cell, false = hovering an occupied
                            // one — mirrors onDragEnd's own occupied check exactly (committed
                            // board tile OR another pending letter already staged there).
                            //
                            // NOTE: `remember(row, col)` only recreates this derivedStateOf when
                            // row/col change (never, for a fixed grid item), so the calculation
                            // lambda's closure is fixed at first composition — it must only read
                            // Snapshot-State-backed values (game.state.value, draggedTile,
                            // cellBounds, dragPosition), never plain local vals like `cell`/
                            // `pending` above, or it would silently freeze on stale data.
                            val dropHoverState by remember(row, col) {
                                derivedStateOf {
                                    if (draggedTile == null) return@derivedStateOf null
                                    if (cellBounds[row to col]?.contains(dragPosition) != true) return@derivedStateOf null
                                    val latest = game.state.value
                                    val occupied = latest != null &&
                                        (latest.board[row][col].tile != null ||
                                            latest.pending.any { it.row == row && it.col == col })
                                    !occupied
                                }
                            }
                            val isDropTarget = dropHoverState == true
                            val isOccupiedDropTarget = dropHoverState == false

                            val targetCellBackground = when {
                                isDropTarget -> Color(0xFFAED581)
                                isOccupiedDropTarget -> Color(0xFFFFAB91)
                                else -> squareColor(squareType, cell.tile != null, pending != null)
                            }
                            val animatedCellBackground by animateColorAsState(
                                targetCellBackground,
                                label = "tileCellBackground"
                            )
                            val targetCellBorderColor = when {
                                isDropTarget -> Color(0xFF558B2F)
                                isOccupiedDropTarget -> Color(0xFFD84315)
                                else -> Color.Gray
                            }
                            val animatedCellBorderColor by animateColorAsState(
                                targetCellBorderColor,
                                label = "tileCellBorder"
                            )

                            // Computed up front (not just at render time) so the same letter/value
                            // pair backing the visible Text can also back this cell's screen-reader
                            // announcement below.
                            val displayLetter = pending?.chosenLetter ?: cell.effectiveLetter
                            val displayTile = pending?.tile ?: cell.tile

                            // Placement settle (item 3 of the placement lifecycle): a scale+fade-in
                            // pop keyed on the shown tile's instance id, so it fires exactly once
                            // whenever a NEW tile instance starts occupying this cell — regardless
                            // of whether it got here via drag-drop (item 2's floating tile lands on
                            // top of this at roughly the same moment) or the tap-to-place fallback
                            // (which has no floating tile at all, so this is its only placement cue).
                            // Committing to board (post-submit) keeps the same instance id, so this
                            // does not re-fire a second time when a pending tile becomes permanent.
                            val placementTileId = displayTile?.instanceId
                            val placementProgress = remember(placementTileId) {
                                Animatable(if (placementTileId != null && enhanced) 0f else 1f)
                            }
                            LaunchedEffect(placementTileId) {
                                if (placementTileId != null && enhanced) {
                                    placementProgress.snapTo(0f)
                                    placementProgress.animateTo(1f, animationSpec = tween(140))
                                }
                            }

                            val cellDescription = if (displayLetter != null && displayTile != null) {
                                val pointsText = "${TileBag.valueOf(displayTile)} point${if (TileBag.valueOf(displayTile) == 1) "" else "s"}"
                                if (pending != null) "$displayLetter, $pointsText, not yet submitted"
                                else "$displayLetter, $pointsText"
                            } else null

                            Box(
                                modifier = Modifier
                                    .padding(0.3.dp)
                                    .aspectRatio(1f)
                                    .onGloballyPositioned { coords -> cellBounds[row to col] = coords.boundsInRoot() }
                                    .background(animatedCellBackground)
                                    .border(
                                        if (isDropTarget || isOccupiedDropTarget) 1.5.dp else 0.3.dp,
                                        animatedCellBorderColor
                                    )
                                    .let { m -> if (cellDescription != null) m.semantics { contentDescription = cellDescription } else m }
                                    // Tile material identity (item: gloss/emboss): the shared specular
                                    // sweep applied directly to an occupied cell's tile face -- "one of
                                    // the most literal fits for the shared shader in the whole suite" per
                                    // this bundle's own notes. No-op on an empty cell (displayTile null)
                                    // or with 3D Perspective Mode off.
                                    .specularSweep(enabled = card3D && displayTile != null, tint = TILE_GLOSS_TINT, periodMs = TILE_GLOSS_PERIOD_MS)
                                    .clickable(enabled = isMyTurn) {
                                        when {
                                            pending != null -> game.unstageTile(row, col)
                                            cell.tile == null -> {
                                                val tileId = selectedTileId
                                                if (tileId != null) {
                                                    val tile = s.players[humanIndex].rack.firstOrNull { it.instanceId == tileId }
                                                    if (tile != null) {
                                                        placeTile(row, col, tile)
                                                        selectedTileId = null
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (displayLetter != null) {
                                    Text(
                                        displayLetter.toString(),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.graphicsLayer {
                                            val p = placementProgress.value
                                            scaleX = 0.6f + 0.4f * p
                                            scaleY = 0.6f + 0.4f * p
                                            alpha = p
                                        }
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
            }
            },
            secondary = {
            Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (swapMode) "Swap mode — tap tiles to exchange, then Swap"
                else "Your rack — tap to select, or drag a tile onto the board",
                style = MaterialTheme.typography.titleSmall
            )
            LazyRow(modifier = Modifier.padding(top = 4.dp)) {
                val stagedIds = s.pending.map { it.tile.instanceId }.toSet()
                items(s.players.getOrNull(humanIndex)?.rack ?: emptyList(), key = { it.instanceId }) { tile ->
                    if (tile.instanceId in stagedIds) return@items
                    val isBeingDragged = draggedTile?.instanceId == tile.instanceId
                    val tileDescription = if (tile.isBlank) "Blank tile, 0 points"
                        else "Letter ${tile.letter}, ${TileBag.valueOf(tile)} point${if (TileBag.valueOf(tile) == 1) "" else "s"}"

                    // Pickup lift (item 1 of the placement lifecycle): mirrors FannedHand's
                    // own lift-on-drag treatment (an animateFloatAsState whose spec is only
                    // ever a real spring/tween when the motion setting allows it, snap()
                    // otherwise) but expressed as scale+shadow+ghost-alpha instead of a
                    // vertical offset, since this tile doesn't lift in place — it becomes the
                    // separate floating overlay below that actually follows the finger. When
                    // `enhanced` is false this collapses to exactly today's behavior: an
                    // instant, fully transparent rack slot with no scale or shadow at all.
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

                    Box(
                        modifier = Modifier
                            // Automatic reflow animation whenever a tile is placed (removed from
                            // the rack) or drawn (added to it) — gated the same way as everything
                            // else here: null specs when `enhanced` is off collapse this to the
                            // instant, un-animated reflow the rack had before.
                            .animateItem(
                                fadeInSpec = if (enhanced) tween(150) else null,
                                placementSpec = if (enhanced) spring(stiffness = Spring.StiffnessMediumLow) else null,
                                fadeOutSpec = if (enhanced) tween(150) else null
                            )
                            .padding(3.dp)
                            .size(44.dp)
                            .onGloballyPositioned { coords -> tileCoords[tile.instanceId] = coords }
                            .graphicsLayer {
                                scaleX = liftScale
                                scaleY = liftScale
                                alpha = rackTileAlpha
                            }
                            .shadow(liftElevation.dp, RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    swapMode && tile.instanceId in swapSelectedIds -> Color(0xFF80CBC4)
                                    tile.instanceId == selectedTileId -> Color(0xFFFFF176)
                                    else -> Color(0xFFE0C097)
                                }
                            )
                            // Tile material identity: the same gloss/emboss sheen as the board's
                            // placed tiles (item above) -- "a physical ivory/plastic tile" per this
                            // bundle's own notes, applied to every rack tile too.
                            .specularSweep(enabled = card3D, tint = TILE_GLOSS_TINT, periodMs = TILE_GLOSS_PERIOD_MS)
                            .semantics { contentDescription = tileDescription }
                            .clickable(enabled = isMyTurn && !isBeingDragged) {
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
                                    if (!wasSelected) haptics(HapticSignal.LIGHT_TICK)
                                }
                            }
                            .pointerInputDrag(
                                enabled = isMyTurn && !swapMode,
                                onDragStart = { localOffset ->
                                    val coords = tileCoords[tile.instanceId] ?: return@pointerInputDrag
                                    draggedTile = tile
                                    dragPosition = coords.localToRoot(localOffset)
                                    selectedTileId = null
                                    velocityTracker.resetTracking()
                                    haptics(HapticSignal.LIGHT_TICK)
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
                                    if (target != null && latest != null) {
                                        val (row, col) = target
                                        val occupied = latest.board[row][col].tile != null ||
                                            latest.pending.any { it.row == row && it.col == col }
                                        if (!occupied) {
                                            placed = true
                                            // Drop settle (item 2): commit the placement immediately —
                                            // same convention as UnoScreen's fly-to-discard overlay, real
                                            // state lands first — then, when enhanced, let the floating
                                            // tile fly on from its release point to the cell's known
                                            // center (cellBounds, already tracked via onGloballyPositioned
                                            // above) instead of vanishing in place this same frame.
                                            val releasePos = dragPosition
                                            placeTile(row, col, tile)
                                            if (enhanced) {
                                                val targetCenter = cellBounds[row to col]?.center ?: releasePos
                                                val progressVelocity = if (maximumTier) {
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
                                    // haptic (and sounds.playPlace()) already fires from inside
                                    // placeTile() above; this path was genuinely silent before this
                                    // pass -- only the haptic fired, no sound at all.
                                    if (!placed) {
                                        haptics(HapticSignal.FAILURE)
                                        playSfx(SfxKind.INVALID_BUZZ)
                                    }
                                    velocityTracker.resetTracking()
                                    draggedTile = null
                                },
                                onDragCancel = {
                                    velocityTracker.resetTracking()
                                    draggedTile = null
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Unconditional now (was `if (!isBeingDragged)`): rackTileAlpha already
                        // drives full invisibility while dragged with enhanced off (alpha -> 0,
                        // identical end state to the old hard-hidden text), and while dragged with
                        // enhanced on this is exactly the dimmed "ghost" half of the lift cue.
                        Text(if (tile.isBlank) "?" else tile.letter.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Idle Submit-button pulse: a slow, low-amplitude scale breathing while a
                // VALID staged word has sat unsubmitted for a few seconds (submitPulseActive,
                // see its own LaunchedEffect above) -- a functional nudge, not decoration, so
                // it's driven by an actual infinite transition rather than a one-shot.
                val submitPulseTransition = rememberInfiniteTransition(label = "submitPulse")
                val submitPulseScale by submitPulseTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (submitPulseActive && enhanced) 1.06f else 1f,
                    animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
                    label = "submitPulseScale"
                )
                Button(
                    enabled = isMyTurn && !swapMode,
                    modifier = Modifier.graphicsLayer { scaleX = submitPulseScale; scaleY = submitPulseScale },
                    onClick = {
                        val error = game.submitMove()
                        if (error != null) {
                            coroutineScope.launch { snackbarHostState.showSnackbar(error) }
                        }
                    }
                ) { Text("Submit") }
                OutlinedButton(enabled = isMyTurn && !swapMode, onClick = { game.clearStaged() }) { Text("Clear") }
                OutlinedButton(enabled = isMyTurn && !swapMode, onClick = { game.pass() }) { Text("Pass") }
                if (swapMode) {
                    Button(
                        enabled = isMyTurn && swapSelectedIds.isNotEmpty() && swapSelectedIds.size <= s.bagCount,
                        onClick = {
                            game.swapTiles(swapSelectedIds)
                            swapSelectedIds = emptySet()
                            swapMode = false
                        }
                    ) { Text("Swap (${swapSelectedIds.size})") }
                    OutlinedButton(enabled = isMyTurn, onClick = {
                        swapMode = false
                        swapSelectedIds = emptySet()
                    }) { Text("Cancel") }
                } else {
                    OutlinedButton(
                        enabled = isMyTurn && s.bagCount > 0,
                        onClick = { swapMode = true; selectedTileId = null }
                    ) { Text("Swap") }
                }
                // Motion-intensity tier -- see TileMotionPrefsStore.kt's KDoc for exactly
                // what Maximum unlocks over Standard.
                OutlinedButton(onClick = { coroutineScope.launch { motionPrefsStore.setMotionTier(if (maximumTier) TileMotionTier.STANDARD else TileMotionTier.MAXIMUM) } }) {
                    Text(if (maximumTier) "Motion: Maximum" else "Motion: Standard")
                }
            }
            }
            }
        )

        // Floating drag overlay — drawn last so it renders above the board/rack, unaffected
        // by list clipping. Two phases share this one Box: `floating` while actively
        // dragging (tracks the finger via dragPosition, unchanged from before), and
        // `settlingNow` for the brief post-release flight (item 2) — mutually exclusive in
        // time, since `settling` is only ever set the same frame `draggedTile` is nulled.
        val floating = draggedTile
        val settlingNow = settling
        if (floating != null || settlingNow != null) {
            val shownTile = floating ?: settlingNow!!.tile
            val pos = if (floating != null) {
                dragPosition
            } else {
                val flight = settlingNow!!
                val t = settleProgress.value
                Offset(flight.from.x + (flight.to.x - flight.from.x) * t, flight.from.y + (flight.to.y - flight.from.y) * t)
            }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (pos.x - tileSizePx / 2).roundToInt(),
                            (pos.y - tileSizePx / 2).roundToInt()
                        )
                    }
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFF176)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (shownTile.isBlank) "?" else shownTile.letter.toString(), fontWeight = FontWeight.Bold)
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        // Word-completion flourish: an escalating sparkle/pulse/banner by WordPlayResult.tier
        // (see game.setOnWordPlayed above) -- a 2-point word and a 50-point Bingo looked
        // identical on submit before this pass. Not tier-gated (applies on Standard too);
        // only the camera micro-punch above is Maximum-exclusive.
        if (enhanced) {
            WordPlayFlourishOverlay(flourish = wordFlourish, nonce = wordFlourishNonce, modifier = Modifier.align(Alignment.Center))
        }
    }

    val blankPos = blankPickerFor
    if (blankPos != null) {
        val (row, col, tileId) = blankPos
        BlankLetterDialog(
            onLetterChosen = { letter ->
                val blankTile = s.players[humanIndex].rack.firstOrNull { it.instanceId == tileId }
                if (blankTile != null) {
                    game.stageTile(row, col, blankTile, letter)
                    // Parity with the non-blank placeTile() path below -- a blank tile's
                    // placement was previously silent on both counts (no sound, no haptic).
                    sounds.playPlace()
                    haptics(HapticSignal.NORMAL_ACTION)
                }
                blankPickerFor = null
            },
            onDismiss = { blankPickerFor = null }
        )
    }
}

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

@Composable
private fun BlankLetterDialog(onLetterChosen: (Char) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.background(Color.White).padding(16.dp)) {
            Text("Choose a letter for the blank tile")
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.height(200.dp)) {
                items(('A'..'Z').toList()) { letter ->
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .size(32.dp)
                            .clickable { onLetterChosen(letter) },
                        contentAlignment = Alignment.Center
                    ) { Text(letter.toString()) }
                }
            }
        }
    }
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

private fun squareColor(type: SquareType, occupied: Boolean, pending: Boolean): Color {
    if (pending) return Color(0xFFFFF176)
    if (occupied) return Color(0xFFFAFAFA)
    return when (type) {
        SquareType.CENTER -> Color(0xFFEF9A9A)
        SquareType.TRIPLE_WORD -> Color(0xFFE57373)
        SquareType.DOUBLE_WORD -> Color(0xFFF8BBD0)
        SquareType.TRIPLE_LETTER -> Color(0xFF64B5F6)
        SquareType.DOUBLE_LETTER -> Color(0xFFB3E5FC)
        SquareType.NORMAL -> Color(0xFFEEEEEE)
    }
}

/**
 * Word-completion flourish: a 2-point word and a 50-point Bingo used to look identical on
 * submit -- this escalates by [WordPlayResult.tier]: a modest sparkle for an ORDINARY play,
 * a stronger pulse (bigger burst + a pulsing point total) for a MULTIPLIER play, and a full
 * banner + larger burst specifically for a BINGO. Self-retiring like SolitaireScreen's own
 * CardBackFanFlourish -- once its own progress animation finishes, it draws nothing.
 * Re-keyed on [nonce] (not just [flourish] itself) so two back-to-back plays of the SAME
 * tier still restart the animation instead of no-op'ing on an unchanged data-class value.
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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

// ---- board material identity ----

/** A cached (allocated once, file scope) wood-board baseline framing the grid -- deliberately a lighter, warmer "honey oak" than Solitaire's felt or Checkers/Chess/Mancala's own wood, so it doesn't read as a copy-paste of any of them. */
private val WOOD_BOARD_BRUSH = Brush.linearGradient(colors = listOf(Color(0xFFE0C39A), Color(0xFFC79A5B), Color(0xFF8B5E34)))

// ---- tile material identity ----

/** Warm ivory/plastic gloss tint for the shared specular sweep on both rack and board tiles. */
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
