package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.edgematch.EdgeMatchGame
import com.gamesuite.games.edgematch.EdgeMatchGeometry
import com.gamesuite.games.edgematch.EdgeMatchLayout
import com.gamesuite.games.edgematch.EdgeMatchRecord
import com.gamesuite.games.edgematch.EdgeMatchState
import com.gamesuite.games.edgematch.EdgeMatchStatsStore
import com.gamesuite.games.edgematch.EdgeMatchTile
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Below this cell size (dp) a board stops shrinking and scrolls/pans instead. For a hex board the
 *  "cell" is the flat-to-flat tile width, so the minimum radius is this divided by sqrt(3). */
private const val MIN_CELL_DP = 28f
private val MIN_HEX_RADIUS_DP = MIN_CELL_DP / 1.7320508f

/** Upper bounds so a small custom board does not sprawl across a tablet (4 x 96 = a 384dp board). */
private const val MAX_CELL_DP = 96f
private const val MAX_HEX_RADIUS_DP = 48f

/** Frame padding around each board (dp). Real `padding` of the frame AND the `framePx` handed to
 *  [fitBoard], so the two can never drift apart (the old code ignored it and squeezed the last column). */
private const val SQUARE_FRAME_DP = 3f
private const val HEX_FRAME_DP = 6f

/** One-quarter-turn (or one-sixth-turn) rotation tween. Off entirely under reduced motion. */
private const val TILE_SPIN_MS = 120

/** Near-black ink: the underlay of a match bead and the colorblind marks on light wedges. */
private val INK_DARK = Color(0xFF1A1208)

private const val HELP_TEXT =
    "Tap a tile to turn it one step: a quarter turn clockwise on a square tile, a sixth of a turn " +
        "on a hex tile. Tiles never move, only their edges rotate.\n\n" +
        "The puzzle is solved when every pair of touching edges shows the same color; edges on the " +
        "outside of the board don't count. A bright bead on a side means it already matches its " +
        "neighbor.\n\n" +
        "Easy, Medium and Hard use 4x4, 6x6 and 8x8 boards with 4, 5 and 6 colors, and Custom lets " +
        "you choose square or hex tiles, 2 to 10 across, with 2 to 8 colors. Your clock starts on " +
        "your first tap."

/**
 * Renders EdgeMatchGame's state reactively: tier chips plus a Custom Game Builder, a live
 * moves / seams / clock status block, the board, and a solved-puzzle panel. Each tile is drawn as
 * 4 (square) or 6 (hex) triangular wedges, one per edge, meeting at its center, so a player sees at
 * a glance whether a tile's edge matches its neighbor's without reading numbers.
 *
 * The ONLY player action is tapping a tile to rotate it one step in place (clockwise on a square
 * tile; a hex tile's engine step moves its colors counter-clockwise on screen, see [rememberTileSpin]);
 * see EdgeMatchGame's class KDoc (CORE MECHANIC) and docs/EDGE_MATCH_DESIGN.md for why there is no
 * placement or swapping. Every currently matching interior edge is highlighted live, recomputed once
 * per state via [EdgeMatchGame.matchingDirections] (not once per tile per frame).
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Restart this puzzle, New puzzle, Back to
 * Menu) plus its BackHandler. Leaving mid-puzzle discards ONLY the unfinished puzzle: with nothing
 * solved yet it is a pure `abortMatch()` (never recorded), but once the session has solved puzzles
 * it goes through `leaveSession()`, the same call the solved panel's "Back to Menu" makes, so those
 * results still count (the confirm dialog says so). The status block reserves [GameChromeEndInset]
 * so the corner button never covers the clock.
 *
 * CUSTOM GAME BUILDER (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md): a dismissible dialog over the live
 * board, not a panel that replaced it. The engine clock is paused while it is open and the on-screen
 * clock freezes with it ([EdgeMatchGame.activeElapsedMillis]); Cancel changes nothing.
 *
 * LAYOUT: [fitBoard] against the measured space of the board's own slot, the frame padding handed to
 * it as `framePx`, a [MIN_CELL_DP] to [MAX_CELL_DP] clamp, and a scroll/pan fallback (never a board
 * bigger than its container) when the slot cannot give a cell [MIN_CELL_DP]. A hex board is sized the
 * same way from its bounding box ([EdgeMatchLayout.hexWidthFactor]/[EdgeMatchLayout.hexHeightFactor]).
 * Portrait stacks status / tiers / board / result; a wide landscape window puts the board on the left
 * and status, tiers and the result panel in a column on the right, so the result panel never shrinks
 * the board.
 *
 * HEX HIT-TESTING: one pointer handler over the whole hex board resolves a tap with
 * [EdgeMatchLayout.hexIndexAt] (pixel to axial rounding, unit-tested). The old per-hex clickable
 * 2R x 2R Boxes overlapped on the 1.5R x sqrt(3)R pitch and rotated the wrong tile for the bottom
 * sixth of every interior hex. The per-tile nodes are semantics-only; square tiles keep a plain
 * clickable (their cells never overlap).
 *
 * ACCESSIBILITY: every tile is one button described by its edge colors, how many of its sides
 * match and its 1-indexed row and column; the seams counter is a polite live region; tier chips are
 * radio buttons; every non-grid control is at least 48dp. With [LocalColorblindMode] each edge color
 * also carries a distinct shape mark (dot, ring, diamond, plus, X, triangle, bar, square). The match
 * cue is a two-tone bead (near-black underlay, green top) along the matched side, which reads by
 * luminance, not hue, over every edge color.
 *
 * MOTION: a tapped tile spins from its old orientation to its new one in 120ms; off under
 * [LocalReducedMotion]. A solve plays the success chime and celebration haptic (a human solve is the
 * only kind there is here). Taps on a solved board do nothing, not even a click.
 *
 * VISUAL IDENTITY: chrome shares its warm tokens with the rest of this batch (see
 * [edgeMatchPalette]); the EDGE PATTERN colors are the one deliberate exception, spanning real hue
 * variety for the same reason Color Flood's own cell colors do: this puzzle's whole mechanic depends
 * on genuinely distinguishable edges.
 */
@Composable
fun EdgeMatchScreen(
    sessionManager: GameSessionManager,
    game: EdgeMatchGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's puzzle for every player — see EdgeMatchGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { EdgeMatchStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = edgeMatchPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current

    // True while the board on screen is the seeded daily puzzle. Any later fresh board (a tier
    // switch, a Custom start, "New puzzle") is random again, and the screen says so.
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
    val record = allRecords[game.statsKey()]
    // Keyed on the actual tile list (unique per puzzle generation, changes on every tap) rather
    // than on difficulty/size: an earlier version keyed only on (s.tiles.size, game.difficulty),
    // which stays constant across successive same-tier puzzles and so only ever recorded the FIRST
    // solve per tier per screen visit.
    var reportedResult by remember(game.statsKey(), s.tiles) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var draftSize by remember { mutableStateOf(game.customConfig?.size ?: 6) }
    var draftColors by remember { mutableStateOf(game.customConfig?.colorCount ?: 4) }
    var draftHex by remember { mutableStateOf(game.customConfig?.geometry == EdgeMatchGeometry.HEX) }

    // Computed ONCE per state (the old code recomputed matchingDirections per tile per recomposition,
    // and recomposed everything at 5Hz because the clock was read here). The clock now lives in its
    // own child composable, so these only change when a tile is actually turned.
    val matching = remember(s) { List(s.tiles.size) { game.matchingDirections(it) } }
    val neighborCounts = remember(s.size, s.geometry) {
        List(s.size * s.size) { EdgeMatchLayout.interiorNeighborCount(it, s.size, s.geometry) }
    }
    val seamTotal = remember(neighborCounts) { neighborCounts.sum() / 2 }
    val seamsMatched = matching.sumOf { it.size } / 2

    // The Custom dialog freezes the clock while it is open. Both calls are idempotent engine-side.
    LaunchedEffect(showCustomDialog) {
        if (showCustomDialog) game.pause() else game.resume()
    }

    LaunchedEffect(s.solved) {
        if (!s.solved) return@LaunchedEffect
        // A solve is always the human's, so the celebration is unconditional here.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedResult == null) {
            val finalTime = game.solvedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.statsKey(), s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
        }
    }

    val onTapTile: (Int) -> Unit = { index ->
        // Re-read the live state: the flag a tile was composed with can be a frame stale, and a
        // tap on a solved board (or after the session ended) must not buzz, click or count.
        val live = game.state.value
        if (live != null && !live.solved && !game.matchOver.value) {
            game.tapTile(index)
            sounds.playTap()
            haptics(HapticSignal.NORMAL_ACTION)
        }
    }
    val startNewPuzzle: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            game.playAgain()
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value && (tier != game.difficulty || game.customConfig != null)) {
            dailyBoardActive = false
            game.selectDifficultyTier(tier)
            game.startMatch()
        }
    }
    val onSelectCustom: () -> Unit = {
        // Pre-fill from whatever custom config is already active (reconfiguring); otherwise the
        // last-drafted values are left alone.
        game.customConfig?.let {
            draftSize = it.size
            draftColors = it.colorCount
            draftHex = it.geometry == EdgeMatchGeometry.HEX
        }
        showCustomDialog = true
    }

    // "Finished units" for the abort policy: puzzles already solved this session. If any exist,
    // leaving mid-puzzle must still score them -- see onAbort below.
    val finishedPuzzles = game.puzzlesSolved.value

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-puzzle discards
    // ONLY the unfinished puzzle: if puzzles were already solved this session, leaving goes through
    // leaveSession() (the same call the solved panel's "Back to Menu" makes) so they still count;
    // with nothing solved it is a pure abort (never a win or loss). A solved puzzle leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Edge Match",
        helpText = HELP_TEXT,
        matchInProgress = !s.solved,
        onLeave = game::leaveSession,
        onAbort = { if (finishedPuzzles > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this puzzle?",
        leaveBody = if (finishedPuzzles > 0) {
            "This puzzle is still unsolved and won't count, but the puzzles you've already solved stay on your record."
        } else {
            "This puzzle is still unsolved. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text("Restart this puzzle") },
                enabled = s.moves > 0,
                onClick = {
                    dismiss()
                    if (!game.matchOver.value) game.resetToInitial()
                }
            )
            DropdownMenuItem(
                text = { Text(if (dailyBoardActive) "New puzzle (leaves the daily)" else "New puzzle") },
                onClick = {
                    dismiss()
                    startNewPuzzle()
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
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f
            // Read here: inside the Column below, this scope's maxHeight is not implicitly reachable.
            val screenMaxHeight = maxHeight

            val statusBlock: @Composable (Modifier) -> Unit = { statusModifier ->
                EdgeMatchStatus(
                    moves = s.moves,
                    seamsMatched = seamsMatched,
                    seamTotal = seamTotal,
                    daily = dailyBoardActive,
                    game = game,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                DifficultyTabsEdgeMatch(
                    current = game.difficulty,
                    isCustomActive = game.customConfig != null,
                    palette = palette,
                    onSelectTier = onSelectTier,
                    onSelectCustom = onSelectCustom
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                EdgeMatchBoardSlot(
                    s = s,
                    matching = matching,
                    neighborCounts = neighborCounts,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    onTapTile = onTapTile,
                    modifier = slotModifier
                )
            }
            val resultPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                EdgeMatchFinishedPanel(
                    moves = s.moves,
                    timeMillis = game.solvedElapsedMillis.value,
                    record = record,
                    isNewBestMoves = reportedResult?.first ?: false,
                    isNewBestTime = reportedResult?.second ?: false,
                    onNewPuzzle = startNewPuzzle,
                    onRestart = { if (!game.matchOver.value) game.resetToInitial() },
                    onBackToMenu = { game.leaveSession() },
                    palette = palette,
                    modifier = panelModifier
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
                            .width(320.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        if (s.solved) resultPanel(Modifier.fillMaxWidth())
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
                    Spacer(Modifier.height(12.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                    if (s.solved) {
                        Spacer(Modifier.height(12.dp))
                        // Capped so even a large font scale cannot push the board out of the slot
                        // above; the panel scrolls inside its own cap instead.
                        resultPanel(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = screenMaxHeight * 0.45f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    if (showCustomDialog) {
        EdgeMatchCustomDialog(
            size = draftSize,
            colorCount = draftColors,
            hex = draftHex,
            onSizeChange = { draftSize = it },
            onColorCountChange = { draftColors = it },
            onHexChange = { draftHex = it },
            onStart = {
                showCustomDialog = false
                dailyBoardActive = false
                game.startCustomMatch(
                    draftSize,
                    draftColors,
                    if (draftHex) EdgeMatchGeometry.HEX else EdgeMatchGeometry.SQUARE
                )
            },
            onDismiss = { showCustomDialog = false },
            palette = palette
        )
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why [edgePatterns] is the one
// deliberate exception, spanning real hue variety rather than warm shades.
// ---------------------------------------------------------------------------
private data class EdgeMatchPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val boardFrame: Color,
    /** Top layer of the match bead drawn along any side EdgeMatchGame.matchingDirections() reports as currently matching its neighbor. */
    val matchGlow: Color,
    /** index 0..7 -- sized for EdgeMatchGame.MAX_COLORS (8), the Custom Game Builder's own ceiling (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md), 2 wider than the fixed tiers ever need (HARD tops out at 6). */
    val edgePatterns: List<Color>
)

private val LightPalette = EdgeMatchPalette(
    background = Color(0xFFFBF1E6),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9),
    boardFrame = Color(0xFF3A2E22),
    matchGlow = Color(0xFF3ED18C),
    edgePatterns = listOf(
        Color(0xFFD9573F), // terracotta red
        Color(0xFFE0A83E), // golden yellow
        Color(0xFF5B9A5B), // leaf green
        Color(0xFF3E7A9E), // ocean blue
        Color(0xFF8A5FA0), // plum purple
        Color(0xFFC9628F), // warm rose
        Color(0xFF3E9E96), // teal (Custom Game Builder's 7th color)
        Color(0xFF6E7A8A)  // slate (Custom Game Builder's 8th color)
    )
)

private val DarkPalette = EdgeMatchPalette(
    background = Color(0xFF1C1712),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    boardFrame = Color(0xFFF3E9DB),
    matchGlow = Color(0xFF4CE0A0),
    edgePatterns = listOf(
        Color(0xFFE0705A),
        Color(0xFFE8BE6C),
        Color(0xFF7ECB98),
        Color(0xFF7FA6D9),
        Color(0xFFB399D9),
        Color(0xFFE099B8),
        Color(0xFF5CC9C0), // teal (Custom Game Builder's 7th color)
        Color(0xFF9AA8BA)  // slate (Custom Game Builder's 8th color)
    )
)

/** Shared instances, so a recomposition never allocates a fresh palette (and 8 lists) again. */
private fun edgeMatchPalette(isDark: Boolean): EdgeMatchPalette = if (isDark) DarkPalette else LightPalette

// ---------------------------------------------------------------------------
// Text and screen-reader helpers
// ---------------------------------------------------------------------------

/** Names for the 8 palette slots, in [EdgeMatchPalette.edgePatterns] order. */
private val EDGE_COLOR_NAMES = listOf("red", "yellow", "green", "blue", "purple", "pink", "teal", "grey")

/** Side names in [EdgeMatchGame.TOP]/RIGHT/BOTTOM/LEFT order. */
private val SQUARE_SIDE_NAMES = listOf("top", "right", "bottom", "left")

/** Side names in [EdgeMatchGame.E]/NE/NW/W/SW/SE order. */
private val HEX_SIDE_NAMES = listOf("east", "northeast", "northwest", "west", "southwest", "southeast")

private fun formatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Screen-reader description of one tile, phrased like ReversiScreen.CellView / CheckersScreen's
 * squareDescription (state first, then 1-indexed row and column): its edge colors by side, how many
 * of its touching sides currently match, then where it is.
 */
private fun tileDescription(
    tile: EdgeMatchTile,
    geometry: EdgeMatchGeometry,
    matchedSides: Int,
    neighborSides: Int,
    row: Int,
    col: Int
): String {
    val sideNames = if (geometry == EdgeMatchGeometry.HEX) HEX_SIDE_NAMES else SQUARE_SIDE_NAMES
    val edges = sideNames.indices.joinToString(", ") { direction ->
        val colorIndex = tile.currentEdge(direction)
        "${sideNames[direction]} ${EDGE_COLOR_NAMES.getOrElse(colorIndex) { "color ${colorIndex + 1}" }}"
    }
    val matches = if (neighborSides > 0 && matchedSides == neighborSides) {
        "all $neighborSides sides match"
    } else {
        "$matchedSides of $neighborSides sides match"
    }
    return "Tile, $edges, $matches, row ${row + 1}, column ${col + 1}"
}

// ---------------------------------------------------------------------------
// Tier chips, status block, clock
// ---------------------------------------------------------------------------

/**
 * The EASY/MEDIUM/HARD tier chips plus a 4th "Custom" chip for the Custom Game
 * Builder (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md). [isCustomActive] (rather
 * than trying to represent "Custom" as a [CpuDifficulty] value, which it isn't)
 * is what decides whether a tier chip or the Custom chip is the highlighted one.
 * Tapping an already-selected tier is still forwarded to [onSelectTier] so that
 * tapping e.g. "Medium" while a custom game is active reliably switches back to
 * it; the caller ignores a tap that would not change anything.
 */
@Composable
private fun DifficultyTabsEdgeMatch(
    current: CpuDifficulty,
    isCustomActive: Boolean,
    palette: EdgeMatchPalette,
    onSelectTier: (CpuDifficulty) -> Unit,
    onSelectCustom: () -> Unit
) {
    // horizontalScroll is the fallback for a window too narrow for four chips plus large text, so
    // the 4th chip never gets compressed to a sliver; the row is simply scrolled into view.
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
            EdgeMatchTab(
                label = label,
                selected = tier == current && !isCustomActive,
                palette = palette,
                onClick = { onSelectTier(tier) }
            )
        }
        EdgeMatchTab(label = "Custom", selected = isCustomActive, palette = palette, onClick = onSelectCustom)
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun EdgeMatchTab(label: String, selected: Boolean, palette: EdgeMatchPalette, onClick: () -> Unit) {
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
 * Moves, matched seams and the clock on one line (plus a "Daily puzzle" tag when today's seeded
 * board is the one on screen). At least 48dp tall so the layout below it clears the corner button.
 * Callers give it `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun EdgeMatchStatus(
    moves: Int,
    seamsMatched: Int,
    seamTotal: Int,
    daily: Boolean,
    game: EdgeMatchGame,
    palette: EdgeMatchPalette,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Text(
                "Daily puzzle",
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
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "Seams: $seamsMatched/$seamTotal",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A polite live region so a screen reader announces progress after each turn.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "$seamsMatched of $seamTotal seams matched"
                    }
            )
            EdgeMatchClock(game = game, palette = palette)
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not the
 * whole screen and every tile (the old version read the clock at the screen root). Reads
 * [EdgeMatchGame.activeElapsedMillis], so it freezes while the engine is paused (Custom dialog open,
 * app backgrounded) and ends on exactly the recorded solve time.
 */
@Composable
private fun EdgeMatchClock(game: EdgeMatchGame, palette: EdgeMatchPalette) {
    val startedAt = game.timerStartElapsedRealtime.value
    val solvedMillis = game.solvedElapsedMillis.value
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt, solvedMillis) {
        if (solvedMillis != null) {
            elapsed = solvedMillis
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
// Custom Game Builder dialog
// ---------------------------------------------------------------------------

/**
 * The Custom Game Builder's config dialog (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md): a shape
 * toggle (Phase 2), two integer sliders (board size, color count), Start and Cancel. Deliberately
 * does NOT regenerate a board on every drag tick: only [onStart] (a single deliberate tap, same as
 * picking a fixed tier) starts a puzzle, so Cancel (or tapping outside) leaves the live board alone.
 */
@Composable
private fun EdgeMatchCustomDialog(
    size: Int,
    colorCount: Int,
    hex: Boolean,
    onSizeChange: (Int) -> Unit,
    onColorCountChange: (Int) -> Unit,
    onHexChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    palette: EdgeMatchPalette
) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = palette.accent,
        activeTrackColor = palette.accent,
        inactiveTrackColor = palette.chipBackground
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.background,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary,
        title = { Text("Custom game") },
        text = {
            // Scrolls so the dialog still works on a short window or at a large font scale.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Shape")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EdgeMatchTab(label = "Square", selected = !hex, palette = palette, onClick = { onHexChange(false) })
                    EdgeMatchTab(label = "Hex", selected = hex, palette = palette, onClick = { onHexChange(true) })
                }
                Spacer(Modifier.height(12.dp))

                Text("Board size: $size x $size")
                Slider(
                    value = size.toFloat(),
                    onValueChange = { onSizeChange(it.roundToInt()) },
                    valueRange = EdgeMatchGame.MIN_SIZE.toFloat()..EdgeMatchGame.MAX_SIZE.toFloat(),
                    steps = EdgeMatchGame.MAX_SIZE - EdgeMatchGame.MIN_SIZE - 1,
                    colors = sliderColors,
                    modifier = Modifier.semantics {
                        contentDescription = "Board size"
                        stateDescription = "$size by $size"
                    }
                )

                Spacer(Modifier.height(8.dp))

                Text("Colors: $colorCount")
                Slider(
                    value = colorCount.toFloat(),
                    onValueChange = { onColorCountChange(it.roundToInt()) },
                    valueRange = EdgeMatchGame.MIN_COLORS.toFloat()..EdgeMatchGame.MAX_COLORS.toFloat(),
                    steps = EdgeMatchGame.MAX_COLORS - EdgeMatchGame.MIN_COLORS - 1,
                    colors = sliderColors,
                    modifier = Modifier.semantics {
                        contentDescription = "Color count"
                        stateDescription = "$colorCount colors"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onStart,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
            ) { Text("Start", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
            ) { Text("Cancel") }
        }
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * One board slot for either geometry. Sizes from THIS slot's own measured space (not an outer
 * scope) with [fitBoard], which never returns a footprint larger than the space it is given, so the
 * old `coerceAtLeast(20.dp)` / `coerceAtLeast(10.dp)` floors (which let a board exceed its
 * container, and ignored the frame padding) are gone. When the slot cannot give a cell
 * [MIN_CELL_DP] the board keeps that size and scrolls/pans in both axes instead of shrinking
 * further.
 */
@Composable
private fun EdgeMatchBoardSlot(
    s: EdgeMatchState,
    matching: List<Set<Int>>,
    neighborCounts: List<Int>,
    palette: EdgeMatchPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onTapTile: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val n = s.size
        if (s.geometry == EdgeMatchGeometry.HEX) {
            // The hex board is not a cols x rows grid, so fitBoard is used per unit of radius:
            // each axis' free space is divided by that axis' bounding factor, and one "cell" is one
            // radius. That keeps its never-exceeds-the-container guarantee and its min/max clamps.
            val fit = fitBoard(
                availableWidthPx = (maxWidth.value - 2f * HEX_FRAME_DP) / EdgeMatchLayout.hexWidthFactor(n),
                availableHeightPx = (maxHeight.value - 2f * HEX_FRAME_DP) / EdgeMatchLayout.hexHeightFactor(n),
                columns = 1,
                rows = 1,
                minCellPx = MIN_HEX_RADIUS_DP,
                maxCellPx = MAX_HEX_RADIUS_DP
            )
            val scrolls = !fit.meetsMinimum
            val radius: Dp = if (scrolls) MIN_HEX_RADIUS_DP.dp else fit.cellPx.dp
            Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
                HexBoard(
                    s = s,
                    matching = matching,
                    neighborCounts = neighborCounts,
                    radius = radius,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    onTapTile = onTapTile
                )
            }
        } else {
            val fit = fitBoard(
                availableWidthPx = maxWidth.value,
                availableHeightPx = maxHeight.value,
                columns = n,
                rows = n,
                framePx = SQUARE_FRAME_DP,
                minCellPx = MIN_CELL_DP,
                maxCellPx = MAX_CELL_DP
            )
            val scrolls = !fit.meetsMinimum
            // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed
            // by one more: n rounded-up cells plus the rounded frame would otherwise overshoot the
            // slot by a pixel or two and squeeze the last column.
            val cellSize: Dp = if (scrolls) {
                MIN_CELL_DP.dp
            } else {
                with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
            }
            Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.boardFrame)
                        .padding(SQUARE_FRAME_DP.dp)
                ) {
                    for (row in 0 until n) {
                        Row {
                            for (col in 0 until n) {
                                val index = row * n + col
                                EdgeMatchTileView(
                                    tile = s.tiles[index],
                                    matching = matching[index],
                                    neighborSides = neighborCounts[index],
                                    row = row,
                                    col = col,
                                    tileSize = cellSize,
                                    solved = s.solved,
                                    palette = palette,
                                    colorblind = colorblind,
                                    reducedMotion = reducedMotion,
                                    onTap = { onTapTile(index) }
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
 * Lays out a HEX geometry board (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md, Phase 2): a rhombus grid
 * of axial `(q, r)` pointy-top hex tiles at the absolute offsets [EdgeMatchLayout.hexCenterX] /
 * [EdgeMatchLayout.hexCenterY] give. A staggered hex grid doesn't fit `Row`/`Column` nesting the
 * way an unstaggered square grid does. [radius] (center-to-vertex) is chosen by the caller.
 *
 * One pointer handler on the whole board maps a tap to a tile via [EdgeMatchLayout.hexIndexAt]; the
 * tile nodes themselves carry only semantics (see [HexEdgeMatchTileView]).
 */
@Composable
private fun HexBoard(
    s: EdgeMatchState,
    matching: List<Set<Int>>,
    neighborCounts: List<Int>,
    radius: Dp,
    palette: EdgeMatchPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onTapTile: (Int) -> Unit
) {
    val density = LocalDensity.current
    val n = s.size
    val radiusPx = with(density) { radius.toPx() }
    val latestTap by rememberUpdatedState(onTapTile)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.boardFrame)
            .padding(HEX_FRAME_DP.dp)
    ) {
        Box(
            modifier = Modifier
                .width(radius * EdgeMatchLayout.hexWidthFactor(n))
                .height(radius * EdgeMatchLayout.hexHeightFactor(n))
                .pointerInput(n, radiusPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            val index = EdgeMatchLayout.hexIndexAt(offset.x, offset.y, radiusPx, n)
                            if (index != null) latestTap(index)
                        }
                    )
                }
        ) {
            for (r in 0 until n) {
                for (q in 0 until n) {
                    val index = r * n + q
                    HexEdgeMatchTileView(
                        tile = s.tiles[index],
                        matching = matching[index],
                        neighborSides = neighborCounts[index],
                        row = r,
                        col = q,
                        radius = radius,
                        solved = s.solved,
                        palette = palette,
                        colorblind = colorblind,
                        reducedMotion = reducedMotion,
                        onTap = { onTapTile(index) },
                        // Top-left of the tile's 2R x 2R box: its centre minus R on each axis.
                        modifier = Modifier.offset(
                            x = radius * (EdgeMatchLayout.hexCenterX(q, r, 1f) - 1f),
                            y = radius * (EdgeMatchLayout.hexCenterY(r, 1f) - 1f)
                        )
                    )
                }
            }
        }
    }
}

/** Plain holder for the previous value of one tile, so [rememberTileSpin] can tell a one-step turn from a reset. */
private class EdgeMatchTileMemory { var last: EdgeMatchTile? = null }

/**
 * The spin of one tile after a tap. Because the engine flips a tile's rotation instantly, the tile
 * is DRAWN in its new orientation and this value (degrees, drawn as `rotationZ`) starts one step away
 * from 0 and eases to 0, so the tile appears to turn from its old orientation to the new one. A fresh
 * [Animatable] per tile change means the very first frame is already at the start angle (no
 * one-frame flash of the final orientation). Only a genuine one-step turn of the same tile animates:
 * a new puzzle or a first composition just sits at 0. [animate] false (reduced motion) always sits at 0.
 *
 * [colorsTurnClockwise] is the way the engine's step moves a tile's colors ON SCREEN, which decides the
 * sign of the start angle so that the first frame equals the old orientation. A square tile's
 * directions (top, right, bottom, left) run clockwise, so its colors turn clockwise and it starts at
 * minus one step. A hex tile's directions (east, northeast, northwest, ...) run counter-clockwise on
 * a pointy-top board whose rows grow downward, so its colors turn counter-clockwise and it starts at
 * plus one step. The previous tile is recorded in a [SideEffect] (once a composition is applied),
 * never inside `remember`, so a composition pass that is thrown away cannot swallow the next spin.
 */
@Composable
private fun rememberTileSpin(tile: EdgeMatchTile, animate: Boolean, colorsTurnClockwise: Boolean): Animatable<Float, AnimationVector1D> {
    val memory = remember { EdgeMatchTileMemory() }
    val spin = remember(tile) {
        val before = memory.last
        val sides = tile.canonicalEdges.size
        val turnedOneStep = animate && before != null &&
            before.canonicalEdges == tile.canonicalEdges &&
            tile.rotation == (before.rotation + 1) % sides
        val oneStep = 360f / sides
        Animatable(if (!turnedOneStep) 0f else if (colorsTurnClockwise) -oneStep else oneStep)
    }
    SideEffect { memory.last = tile }
    LaunchedEffect(spin) {
        if (spin.value != 0f) {
            spin.animateTo(0f, animationSpec = tween(durationMillis = TILE_SPIN_MS, easing = FastOutSlowInEasing))
        }
    }
    return spin
}

/**
 * One SQUARE tile: 4 wedges (one per [EdgeMatchGame.TOP]/[EdgeMatchGame.RIGHT]/[EdgeMatchGame.BOTTOM]/
 * [EdgeMatchGame.LEFT]) meeting at its center. A side whose direction is in [matching] (see
 * [EdgeMatchGame.matchingDirections]) gets a match bead; with [colorblind] each wedge also carries a
 * shape mark for its color. One button for the whole tile, described by [tileDescription]; disabled
 * once the board is [solved]. Square cells never overlap, so a plain clickable is exact here.
 */
@Composable
private fun EdgeMatchTileView(
    tile: EdgeMatchTile,
    matching: Set<Int>,
    neighborSides: Int,
    row: Int,
    col: Int,
    tileSize: Dp,
    solved: Boolean,
    palette: EdgeMatchPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onTap: () -> Unit
) {
    val description = remember(tile, matching, neighborSides, row, col) {
        tileDescription(tile, EdgeMatchGeometry.SQUARE, matching.size, neighborSides, row, col)
    }
    val spin = rememberTileSpin(tile, animate = !reducedMotion, colorsTurnClockwise = true)
    Box(
        modifier = Modifier
            .size(tileSize)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = !solved, onClickLabel = "Rotate tile clockwise", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value }) {
            val w = size.width
            val h = size.height
            // Wedge d runs from corner d to corner d + 1: TOP (0,0)-(w,0), RIGHT (w,0)-(w,h),
            // BOTTOM (w,h)-(0,h), LEFT (0,h)-(0,0).
            val corners = arrayOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
            val starts = Array(4) { corners[it] }
            val ends = Array(4) { corners[(it + 1) % 4] }
            drawWedges(tile, matching, Offset(w / 2f, h / 2f), starts, ends, palette, colorblind)
        }
    }
}

/**
 * One HEX tile, drawn as 6 triangular wedges (one per [EdgeMatchGame.E]/[EdgeMatchGame.NE]/
 * [EdgeMatchGame.NW]/[EdgeMatchGame.W]/[EdgeMatchGame.SW]/[EdgeMatchGame.SE]) meeting at its
 * center, in a pointy-top hexagon (a vertex at top/bottom, flat sides facing E/NE/NW/W/SW/SE).
 * Vertex `i` sits at angle `60i - 90` degrees; direction `d`'s own wedge spans from vertex
 * `edgeIdx(d)` to vertex `edgeIdx(d) + 1`, where `edgeIdx(d) = (1 - d) mod 6` -- worked out from
 * that same vertex-angle formula so a direction's wedge always faces the SAME real-world side a
 * neighbor laid out via [HexBoard]'s axial->pixel formula actually touches (see
 * docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md's Rendering section). Same "wedge = one edge's color,
 * meeting at center" idiom [EdgeMatchTileView] uses, just 6 wedges instead of 4.
 *
 * This node does NOT take pointer input: the board resolves taps itself (see [HexBoard]). It carries
 * only semantics, on an inner node the size of the hexagon's flat-to-flat width, so neighbouring
 * tiles' accessibility bounds barely overlap.
 */
@Composable
private fun HexEdgeMatchTileView(
    tile: EdgeMatchTile,
    matching: Set<Int>,
    neighborSides: Int,
    row: Int,
    col: Int,
    radius: Dp,
    solved: Boolean,
    palette: EdgeMatchPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = remember(tile, matching, neighborSides, row, col) {
        tileDescription(tile, EdgeMatchGeometry.HEX, matching.size, neighborSides, row, col)
    }
    val spin = rememberTileSpin(tile, animate = !reducedMotion, colorsTurnClockwise = false)
    Box(modifier = modifier.size(radius * 2)) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value }) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // A small inset from the tile's own bounding box so adjacent hexes show a visible
            // gap/border, the same visual role square's own 2.dp Box padding plays.
            val r = size.minDimension / 2f * 0.94f

            fun vertex(i: Int): Offset {
                val angle = Math.toRadians(60.0 * i - 90.0)
                return Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
            }

            val starts = Array(6) { d -> vertex(((1 - d) % 6 + 6) % 6) }
            val ends = Array(6) { d -> vertex((((1 - d) % 6 + 6) % 6 + 1) % 6) }
            drawWedges(tile, matching, Offset(cx, cy), starts, ends, palette, colorblind)
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(radius * EdgeMatchLayout.SQRT3)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    if (solved) {
                        disabled()
                    } else {
                        onClick(label = "Rotate tile") {
                            onTap()
                            true
                        }
                    }
                }
        )
    }
}

// ---------------------------------------------------------------------------
// Tile drawing (shared by both geometries)
// ---------------------------------------------------------------------------

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Draws one tile's wedges. Wedge `d` is the triangle ([starts]`[d]`, [ends]`[d]`, [centre]) filled
 * with the color currently facing direction `d`. Then, per wedge: with [colorblind], a shape mark
 * for that color (see [drawEdgeMark]); and, if `d` is in [matching], a match bead along the side.
 */
private fun DrawScope.drawWedges(
    tile: EdgeMatchTile,
    matching: Set<Int>,
    centre: Offset,
    starts: Array<Offset>,
    ends: Array<Offset>,
    palette: EdgeMatchPalette,
    colorblind: Boolean
) {
    val sides = starts.size
    for (d in 0 until sides) {
        val path = Path().apply {
            moveTo(starts[d].x, starts[d].y)
            lineTo(ends[d].x, ends[d].y)
            lineTo(centre.x, centre.y)
            close()
        }
        drawPath(path, color = palette.edgePatterns[tile.currentEdge(d)])
    }
    for (d in 0 until sides) {
        val mid = Offset((starts[d].x + ends[d].x) / 2f, (starts[d].y + ends[d].y) / 2f)
        val toCentre = Offset(centre.x - mid.x, centre.y - mid.y)
        val apothem = toCentre.getDistance()
        if (apothem <= 0f) continue
        if (colorblind) {
            val colorIndex = tile.currentEdge(d)
            val wedgeColor = palette.edgePatterns[colorIndex]
            val ink = if (wedgeColor.luminance() > 0.2f) INK_DARK else Color.White
            val at = Offset(mix(mid.x, centre.x, 0.5f), mix(mid.y, centre.y, 0.5f))
            drawEdgeMark(colorIndex, at, unit = apothem * 0.15f, ink = ink)
        }
        if (d in matching) {
            val thickness = (apothem * 0.10f).coerceAtLeast(1.5f.dp.toPx())
            // Pull the bead in from the tile's own edge by its own width so it is never half-clipped.
            val inset = thickness * 1.3f
            val shift = Offset(toCentre.x / apothem * inset, toCentre.y / apothem * inset)
            val a = Offset(mix(starts[d].x, ends[d].x, 0.18f) + shift.x, mix(starts[d].y, ends[d].y, 0.18f) + shift.y)
            val b = Offset(mix(starts[d].x, ends[d].x, 0.82f) + shift.x, mix(starts[d].y, ends[d].y, 0.82f) + shift.y)
            // Two tones: the near-black underlay separates the bead from ANY wedge color by
            // luminance, so the cue does not depend on how green reads against that hue.
            drawLine(color = INK_DARK, start = a, end = b, strokeWidth = thickness * 1.9f, cap = StrokeCap.Round)
            drawLine(color = palette.matchGlow, start = a, end = b, strokeWidth = thickness, cap = StrokeCap.Round)
        }
    }
}

/**
 * Colorblind-mode identity mark for edge color [colorIndex], centred at [at], [unit] = its radius.
 * Eight distinct shapes for the eight palette slots: dot, ring, diamond, plus, X, triangle, bar,
 * square. Drawn in [ink] (near-black or white, whichever contrasts with the wedge).
 */
private fun DrawScope.drawEdgeMark(colorIndex: Int, at: Offset, unit: Float, ink: Color) {
    val stroke = unit * 0.55f
    when (colorIndex % 8) {
        0 -> drawCircle(color = ink, radius = unit, center = at)
        1 -> drawCircle(color = ink, radius = unit, center = at, style = Stroke(width = stroke))
        2 -> {
            val r = unit * 1.25f
            val diamond = Path().apply {
                moveTo(at.x, at.y - r)
                lineTo(at.x + r, at.y)
                lineTo(at.x, at.y + r)
                lineTo(at.x - r, at.y)
                close()
            }
            drawPath(diamond, color = ink)
        }
        3 -> {
            drawLine(color = ink, start = Offset(at.x - unit, at.y), end = Offset(at.x + unit, at.y), strokeWidth = stroke)
            drawLine(color = ink, start = Offset(at.x, at.y - unit), end = Offset(at.x, at.y + unit), strokeWidth = stroke)
        }
        4 -> {
            drawLine(color = ink, start = Offset(at.x - unit, at.y - unit), end = Offset(at.x + unit, at.y + unit), strokeWidth = stroke)
            drawLine(color = ink, start = Offset(at.x - unit, at.y + unit), end = Offset(at.x + unit, at.y - unit), strokeWidth = stroke)
        }
        5 -> {
            val r = unit * 1.3f
            val triangle = Path().apply {
                moveTo(at.x, at.y - r)
                lineTo(at.x + r, at.y + r * 0.8f)
                lineTo(at.x - r, at.y + r * 0.8f)
                close()
            }
            drawPath(triangle, color = ink)
        }
        6 -> drawRect(
            color = ink,
            topLeft = Offset(at.x - unit * 1.4f, at.y - unit * 0.4f),
            size = Size(unit * 2.8f, unit * 0.8f)
        )
        else -> drawRect(
            color = ink,
            topLeft = Offset(at.x - unit * 0.9f, at.y - unit * 0.9f),
            size = Size(unit * 1.8f, unit * 1.8f)
        )
    }
}

// ---------------------------------------------------------------------------
// Solved panel
// ---------------------------------------------------------------------------

@Composable
private fun EdgeMatchFinishedPanel(
    moves: Int,
    timeMillis: Long?,
    record: EdgeMatchRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    onNewPuzzle: () -> Unit,
    onRestart: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: EdgeMatchPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = palette.textPrimary.copy(alpha = 0.08f),
            contentColor = palette.textPrimary
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Every edge matches!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
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
                    onClick = onNewPuzzle,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.boardFrame, contentColor = palette.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Puzzle", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Restart", textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Back to Menu", textAlign = TextAlign.Center) }
        }
    }
}
