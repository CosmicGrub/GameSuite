package com.gamesuite.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.core.GameSessionManager
import com.gamesuite.foldable.AdaptiveTwoPane
import com.gamesuite.foldable.LocalFoldState
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordgames.tiles.*
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.loadDictionary(androidContext)
        game.setOnMatchEnd { result -> sessionManager.endActiveGame(result) }
        game.startMatch()
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

    // Swap mode: multi-select rack tiles to exchange for new ones from the bag, distinct
    // from the single-select-to-place flow above.
    var swapMode by remember { mutableStateOf(false) }
    var swapSelectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val humanIndex = s.players.indexOfFirst { !it.isBot }.let { if (it >= 0) it else 0 }
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
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                val minCellSize = 24.dp
                val boardWidth = maxOf(minCellSize * BOARD_SIZE, maxWidth)
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(BOARD_SIZE),
                        modifier = Modifier.width(boardWidth)
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
                            val isDropTarget by remember(row, col) {
                                derivedStateOf { draggedTile != null && cellBounds[row to col]?.contains(dragPosition) == true }
                            }

                            Box(
                                modifier = Modifier
                                    .padding(0.3.dp)
                                    .aspectRatio(1f)
                                    .onGloballyPositioned { coords -> cellBounds[row to col] = coords.boundsInRoot() }
                                    .background(
                                        if (isDropTarget) Color(0xFFAED581)
                                        else squareColor(squareType, cell.tile != null, pending != null)
                                    )
                                    .border(if (isDropTarget) 1.5.dp else 0.3.dp, if (isDropTarget) Color(0xFF558B2F) else Color.Gray)
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
                                val displayLetter = pending?.chosenLetter ?: cell.effectiveLetter
                                if (displayLetter != null) {
                                    Text(displayLetter.toString(), fontWeight = FontWeight.Bold)
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

                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(44.dp)
                            .onGloballyPositioned { coords -> tileCoords[tile.instanceId] = coords }
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    isBeingDragged -> Color.Transparent
                                    swapMode && tile.instanceId in swapSelectedIds -> Color(0xFF80CBC4)
                                    tile.instanceId == selectedTileId -> Color(0xFFFFF176)
                                    else -> Color(0xFFE0C097)
                                }
                            )
                            .clickable(enabled = isMyTurn && !isBeingDragged) {
                                if (swapMode) {
                                    swapSelectedIds = if (tile.instanceId in swapSelectedIds) {
                                        swapSelectedIds - tile.instanceId
                                    } else {
                                        swapSelectedIds + tile.instanceId
                                    }
                                } else {
                                    selectedTileId = if (selectedTileId == tile.instanceId) null else tile.instanceId
                                }
                            }
                            .pointerInputDrag(
                                enabled = isMyTurn && !swapMode,
                                onDragStart = { localOffset ->
                                    val coords = tileCoords[tile.instanceId] ?: return@pointerInputDrag
                                    draggedTile = tile
                                    dragPosition = coords.localToRoot(localOffset)
                                    selectedTileId = null
                                },
                                onDrag = { dragAmount -> dragPosition += dragAmount },
                                onDragEnd = {
                                    val target = cellBounds.entries.firstOrNull { it.value.contains(dragPosition) }?.key
                                    val latest = game.state.value
                                    if (target != null && latest != null) {
                                        val (row, col) = target
                                        val occupied = latest.board[row][col].tile != null ||
                                            latest.pending.any { it.row == row && it.col == col }
                                        if (!occupied) placeTile(row, col, tile)
                                    }
                                    draggedTile = null
                                },
                                onDragCancel = { draggedTile = null }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isBeingDragged) {
                            Text(if (tile.isBlank) "?" else tile.letter.toString(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(enabled = isMyTurn && !swapMode, onClick = {
                    val error = game.submitMove()
                    if (error != null) {
                        coroutineScope.launch { snackbarHostState.showSnackbar(error) }
                    }
                }) { Text("Submit") }
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
            }
            }
            }
        )

        // Floating drag overlay — drawn last so it renders above the board/rack, unaffected by list clipping.
        val floating = draggedTile
        if (floating != null) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragPosition.x - tileSizePx / 2).roundToInt(),
                            (dragPosition.y - tileSizePx / 2).roundToInt()
                        )
                    }
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFF176)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (floating.isBlank) "?" else floating.letter.toString(), fontWeight = FontWeight.Bold)
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val blankPos = blankPickerFor
    if (blankPos != null) {
        val (row, col, tileId) = blankPos
        BlankLetterDialog(
            onLetterChosen = { letter ->
                val blankTile = s.players[humanIndex].rack.firstOrNull { it.instanceId == tileId }
                if (blankTile != null) game.stageTile(row, col, blankTile, letter)
                blankPickerFor = null
            },
            onDismiss = { blankPickerFor = null }
        )
    }
}

/** Small wrapper around detectDragGestures giving root-space-friendly callbacks. */
private fun Modifier.pointerInputDrag(
    enabled: Boolean,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectDragGestures(
        onDragStart = { offset -> onDragStart(offset) },
        onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount) },
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel
    )
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
