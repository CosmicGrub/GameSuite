package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
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
import com.gamesuite.games.colorflood.ColorFloodGame
import com.gamesuite.games.colorflood.ColorFloodRecord
import com.gamesuite.games.colorflood.ColorFloodState
import com.gamesuite.games.colorflood.ColorFloodStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Below this cell size (dp) the board stops shrinking and scrolls/pans instead of squeezing on. It is
 * deliberately lower than the ~28dp used for tappable grids: Color Flood's cells are display only
 * (every tap lands on the swatches), so a floor here only has to keep the mosaic legible. A 16x16
 * Hard board on a 312dp Fold cover content area is 18dp per cell and fits whole; panning a puzzle
 * whose whole point is reading the entire board at a glance would be the worse trade.
 */
private const val MIN_CELL_DP = 16f

/** Upper bound so a small board does not sprawl across a tablet (16 x 44 = a 704dp board). */
private const val MAX_CELL_DP = 44f

/** Colorblind shape marks are drawn on a cell only once it is at least this big (dp). */
private const val MARK_MIN_CELL_DP = 12f

/** The cross-fade a pick plays over the cells it repaints. Off entirely under reduced motion. */
private const val FLOOD_FADE_MS = 220

/** How long the flooded board is left alone before the result panel covers its lower part. */
private const val RESULT_HOLD_MS = 600L

/** Near-black ink: the casing of the territory outline and the colorblind marks on light cells. */
private val INK_DARK = Color(0xFF1A1208)

/** Names for the 6 palette slots, in [ColorFloodPalette.colors] order (screen-reader text only). */
private val FLOOD_COLOR_NAMES = listOf("Red", "Yellow", "Green", "Blue", "Purple", "Pink")

private const val HELP_TEXT =
    "You start in the top-left corner, along with any neighbors that already share its color. Tap a " +
        "color to repaint your whole flooded region that color; every cell touching it that already " +
        "has that color joins you, directly or through a chain of same-colored cells.\n\n" +
        "Flood the entire board with one color to win. There is no move limit, so aim for as few moves " +
        "as you can; a color that touches nothing new still costs a move, and your current color does " +
        "nothing.\n\n" +
        "The outline and the corner ring show your region, and the number under each color is how many " +
        "cells it would add right now. Easy, Medium and Hard use 9x9, 12x12 and 16x16 boards with 4, 5 " +
        "and 6 colors, and your clock starts on your first move."

private const val DAILY_HELP_TEXT =
    "\n\nThe Daily board is the same for every player on the same difficulty today; switching " +
        "difficulty or starting a new board leaves it."

/**
 * Renders ColorFloodGame's state reactively: tier chips, a live moves / flooded / clock status
 * block, the board, the six color swatches, and a flooded-board result panel. The board itself is
 * pure DISPLAY: every tap happens on the swatches below it, since a cell's color is what you are
 * choosing FROM, not a thing you act ON directly.
 *
 * BOARD: a single [Canvas] (not 256 boxes) sized with [fitBoard] against the measured space of the
 * board's own slot, clamped to [MIN_CELL_DP]..[MAX_CELL_DP]; when the slot cannot give a cell
 * [MIN_CELL_DP] the board keeps that size and scrolls/pans instead (see [MIN_CELL_DP] for why that
 * floor is lower than a tappable grid's). The old `coerceAtLeast(14.dp)` floor and the outer-scope
 * sizing are gone. Cell edges snap to whole pixels so no hairline seams show between cells.
 *
 * TERRITORY: the engine always knew [ColorFloodState.territory] but the screen never drew it, so
 * the player could not see which same-colored cells were theirs. The flooded region now gets a
 * two-tone outline (near-black casing, white core, so it reads over every hue) and a ring on the
 * origin corner. Each swatch shows `+N`, the cells it would add right now
 * ([ColorFloodGame.absorbCount]); `+0` means that pick only costs a move.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, New board, Back to Menu) plus its
 * BackHandler. Leaving mid-board discards ONLY the unfinished board: with no board flooded yet it
 * is a pure `abortMatch()` (never recorded), but once the session has flooded boards it goes
 * through `leaveSession()`, the same call the result panel's "Back to Menu" makes, so those boards
 * still count (the confirm dialog says so). The old inline refresh button is now the menu's "New
 * board" entry. The status block reserves [GameChromeEndInset] so the corner button never covers
 * the clock. On the Daily route a "Daily board" tag shows until a new board replaces it.
 *
 * LAYOUT: portrait stacks status / tiers / board / swatches and the result panel overlays the
 * bottom of that stack after a short hold, so the flooded board is never re-measured at the moment
 * of the win. A wide landscape window puts the board on the left and status, tiers, swatches and
 * the result panel (between tiers and swatches) in a scrolling column on the right.
 *
 * ACCESSIBILITY: each swatch is one button described by its color name plus what it would add (or
 * "current color"), at least 48dp wide and tall; the board is one node that announces its size,
 * flooded count and flood color; the "Flooded" counter is a polite live region; tier chips are
 * radio buttons. With [LocalColorblindMode] every cell and swatch also carries a shape mark
 * (circle, ring, diamond, plus, X, triangle; the same shapes Edge Match uses for the same six
 * colors), because hue alone separates ocean blue from plum for some players. The selection ring
 * on the current swatch and the territory outline carry state by shape, not color.
 *
 * MOTION: a pick cross-fades the repainted cells over [FLOOD_FADE_MS]. The one celebration is the
 * flood itself (a human's win, since there is no opponent): the success chime and celebration
 * haptic always fire; the 14dp [com.gamesuite.ui.effects.CameraShake] and 32-particle
 * [ParticleBurst] only play when [LocalReducedMotion] is off, and the burst's frame clock runs only
 * while particles are alive (the shared `rememberParticleBurst` keeps a loop going forever).
 * Pick haptics scale with how much of the board the pick absorbed.
 *
 * VISUAL IDENTITY: chrome (background/text/accent) shares its warm tokens with MinesweeperScreen/
 * SudokuScreen/LightsOutScreen/DotsAndBoxesScreen (see [colorFloodPalette]). The CELL colors
 * themselves are a deliberate exception: this puzzle's entire mechanic depends on genuinely
 * distinguishable hues, so [ColorFloodPalette.colors] spans real variety (not shades of one warm
 * tone), and the board stays a solid edge-to-edge mosaic with no gaps or rounding.
 */
@Composable
fun ColorFloodScreen(
    sessionManager: GameSessionManager,
    game: ColorFloodGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's board for every player — see ColorFloodGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    // A separate call from `sounds` above deliberately -- CardSounds is this screen's own
    // sample-based tap/place sound, not a general SFX vocabulary; ProceduralSfx is the ALREADY
    // shared system TowerDefenceScreen/CheckersScreen (etc.) already use for a win chime, so
    // reusing it here is following the existing convention, not adding a second parallel one.
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.COLOR_FLOOD, enabled = musicEnabled)
    val statsStore = remember { ColorFloodStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = colorFloodPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current

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
    var reportedResult by remember(s.cellColors, game.difficulty) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // The result panel waits for the celebration to land before it covers the lower part of the
    // stack. It is a state of its own (not just `s.isOver`) so the hold survives recompositions.
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(s.isOver) {
        if (s.isOver) {
            if (!reducedMotion) delay(RESULT_HOLD_MS)
            showResult = true
        } else {
            showResult = false
        }
    }

    LaunchedEffect(s.won) {
        if (s.won && reportedResult == null) {
            // A flood is always the human's win (this puzzle has no opponent), so the chime and
            // haptic are unconditional. The shake and particles live with the board and are the
            // only part that reduced motion switches off. Celebrate first, then write the stats.
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.difficulty, s.moves, finalTime)
            reportedResult = result.isNewBestMoves to result.isNewBestTimeMillis
        }
    }

    val totalCells = s.size * s.size
    val flooded = s.territory.size
    // What each swatch would add, computed once per board state (a BFS per color) rather than per frame.
    val absorbs = remember(s.cellColors, s.isOver) { List(s.colorCount) { game.absorbCount(it) } }
    // "Finished units" for the abort policy: boards already flooded this session. If any exist,
    // leaving mid-board must still score them -- see onAbort below.
    val finishedBoards = game.puzzlesSolved.value

    val onPick: (Int) -> Unit = { colorIndex ->
        // Re-read the live state: the swatch a tap landed on can be a frame stale, and a tap on a
        // flooded board (or after the session ended) must not buzz, click or count.
        val live = game.state.value
        if (live != null && !live.isOver && !game.matchOver.value && colorIndex != live.currentColor) {
            val absorbed = game.absorbCount(colorIndex)
            val remaining = live.size * live.size - live.territory.size
            game.pick(colorIndex)
            sounds.playTap()
            // The winning pick gets the celebration haptic from the win effect instead.
            if (absorbed < remaining) haptics(pickHaptic(absorbed, live.size * live.size))
        }
    }
    val startNewBoard: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            game.playAgain()
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value && tier != game.difficulty) {
            dailyBoardActive = false
            game.difficulty = tier
            game.startMatch()
        }
    }

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-board discards
    // ONLY the unfinished board: if boards were already flooded this session, leaving goes through
    // leaveSession() (the same call the result panel's "Back to Menu" makes) so they still count;
    // with nothing flooded it is a pure abort (never a win or loss). A flooded board leaves through
    // leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Color Flood",
        helpText = if (dailySeed != null) HELP_TEXT + DAILY_HELP_TEXT else HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this board?",
        leaveBody = if (finishedBoards > 0) {
            "This board is still unfinished and won't count, but the boards you've already flooded stay on your record."
        } else {
            "This board is still unfinished. Leaving now won't count it as a win or a loss."
        },
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text(if (dailyBoardActive) "New board (leaves the daily)" else "New board") },
                onClick = {
                    dismiss()
                    startNewBoard()
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
                ColorFloodStatus(
                    moves = s.moves,
                    flooded = flooded,
                    total = totalCells,
                    daily = dailyBoardActive,
                    game = game,
                    palette = palette,
                    modifier = statusModifier
                )
            }
            val tiersRow: @Composable () -> Unit = {
                ColorFloodTiers(current = game.difficulty, palette = palette, onSelect = onSelectTier)
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                ColorFloodBoardSlot(
                    s = s,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    modifier = slotModifier
                )
            }
            val swatchesRow: @Composable () -> Unit = {
                ColorSwatches(
                    colorCount = s.colorCount,
                    currentColor = s.currentColor,
                    absorbs = absorbs,
                    palette = palette,
                    colorblind = colorblind,
                    enabled = !s.isOver,
                    onPick = onPick
                )
            }
            val resultPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                ColorFloodResultPanel(
                    moves = s.moves,
                    timeMillis = game.finishedElapsedMillis.value,
                    record = record,
                    isNewBestMoves = reportedResult?.first ?: false,
                    isNewBestTime = reportedResult?.second ?: false,
                    daily = dailyBoardActive,
                    onNewBoard = startNewBoard,
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
                            .width(300.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        statusBlock(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        tiersRow()
                        // Above the (by then disabled) swatches, not below them: on a short landscape
                        // window this column scrolls, and the panel must not land off-screen.
                        if (s.isOver && showResult) resultPanel(Modifier.fillMaxWidth())
                        swatchesRow()
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
                    swatchesRow()
                }
                if (s.isOver && showResult) {
                    // An overlay on the stack rather than a sibling in it: a sibling re-measured the
                    // board smaller at the win frame. The flooded board is one color, so covering
                    // its lower part loses nothing; the panel scrolls inside its own cap.
                    resultPanel(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .heightIn(max = areaMaxHeight * 0.7f)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/** Pick haptics scale with how much of the board a pick absorbed: a nibble, a bite, a big flood. */
private fun pickHaptic(absorbed: Int, totalCells: Int): HapticSignal = when {
    absorbed * 100 >= totalCells * 12 -> HapticSignal.STRONG_ACTION
    absorbed * 100 >= totalCells * 4 -> HapticSignal.NORMAL_ACTION
    else -> HapticSignal.LIGHT_TICK
}

private fun formatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun movesText(moves: Int): String = if (moves == 1) "1 move" else "$moves moves"

// ---------------------------------------------------------------------------
// Win-moment JUICE tuning -- see this file's own class KDoc MOTION paragraph. Sized deliberately
// bigger than a mid-game nudge (c.f. TowerDefenceScreen's 8dp life-lost shake, CheckersScreen's
// 4dp capture jitter): flooding the whole board is the ONLY celebratory moment this puzzle has.
// ---------------------------------------------------------------------------
private const val WIN_SHAKE_MAGNITUDE_DP = 14f
private const val WIN_SHAKE_DECAY_MS = 320
private const val WIN_BURST_PARTICLE_COUNT = 32
private const val WIN_BURST_PARTICLE_RADIUS_DP = 5f
private const val WIN_BURST_MIN_SPEED_DP = 220f
private const val WIN_BURST_MAX_SPEED_DP = 460f
private const val WIN_BURST_GRAVITY_DP = 520f

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why [colors] itself is the one
// deliberate exception, spanning real hue variety rather than warm shades.
// ---------------------------------------------------------------------------
private data class ColorFloodPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val colors: List<Color> // index 0..5, matches ColorFloodGame's own color indices
)

private val LightPalette = ColorFloodPalette(
    background = Color(0xFFFBF1E6),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1 on the selected tier chip.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9),
    colors = listOf(
        Color(0xFFD9573F), // terracotta red
        Color(0xFFE0A83E), // golden yellow
        Color(0xFF5B9A5B), // leaf green
        Color(0xFF3E7A9E), // ocean blue
        Color(0xFF8A5FA0), // plum purple
        Color(0xFFC9628F)  // warm rose
    )
)

private val DarkPalette = ColorFloodPalette(
    background = Color(0xFF1C1712),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    colors = listOf(
        Color(0xFFE0705A),
        Color(0xFFE8BE6C),
        Color(0xFF7ECB98),
        Color(0xFF7FA6D9),
        Color(0xFFB399D9),
        Color(0xFFE099B8)
    )
)

/** Shared instances, so a recomposition never allocates a fresh palette (and its list) again. */
private fun colorFloodPalette(isDark: Boolean): ColorFloodPalette = if (isDark) DarkPalette else LightPalette

// ---------------------------------------------------------------------------
// Tier chips, status block, clock
// ---------------------------------------------------------------------------

@Composable
private fun ColorFloodTiers(current: CpuDifficulty, palette: ColorFloodPalette, onSelect: (CpuDifficulty) -> Unit) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text, so
    // the row is simply scrolled into view instead of a chip being clipped.
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            ColorFloodTab(label = label, selected = tier == current, palette = palette, onClick = { onSelect(tier) })
        }
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun ColorFloodTab(label: String, selected: Boolean, palette: ColorFloodPalette, onClick: () -> Unit) {
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
 * Moves, flooded cells and the clock on one line (plus a "Daily board" tag when today's seeded
 * board is the one on screen). At least 48dp tall so the layout below it clears the corner button.
 * Callers give it `padding(end = GameChromeEndInset)`.
 */
@Composable
private fun ColorFloodStatus(
    moves: Int,
    flooded: Int,
    total: Int,
    daily: Boolean,
    game: ColorFloodGame,
    palette: ColorFloodPalette,
    modifier: Modifier = Modifier
) {
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
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "Flooded: $flooded/$total",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A polite live region so a screen reader announces progress after each pick.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "$flooded of $total cells flooded"
                    }
            )
            ColorFloodClock(game = game, palette = palette)
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the screen and the board (the old version read the clock at the screen root). Reads
 * [ColorFloodGame.activeElapsedMillis], so it freezes while the engine is paused (app backgrounded)
 * and ends on exactly the recorded solve time.
 */
@Composable
private fun ColorFloodClock(game: ColorFloodGame, palette: ColorFloodPalette) {
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

/** Plain holder for the board as of the last applied composition, so a pick can cross-fade from it. */
private class FloodFadeMemory {
    var colors: List<Int>? = null
    var moves: Int = 0
}

/**
 * One pick's cross-fade: [from] is the board before the pick (null = nothing to fade from: a new
 * board, the first composition, or reduced motion), [progress] runs 0 to 1. A fresh instance per
 * board state means the very first frame is already at 0, so there is no one-frame flash of the
 * final colors before the fade starts.
 */
private class FloodFade(val from: List<Int>?) {
    val progress = Animatable(if (from == null) 1f else 0f)
}

/**
 * The board slot. Sizes from THIS slot's own measured space (not an outer scope) with [fitBoard],
 * which never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(14.dp)` floor (which could push a board past its container) is gone. When the slot
 * cannot give a cell [MIN_CELL_DP] the board keeps that size and scrolls/pans in both axes.
 *
 * Owns the win-moment shake and particle burst, because it is the only place that knows the board's
 * real pixel size when the burst spawns; the result panel overlays rather than resizes it, so that
 * size is never stale at the win frame.
 */
@Composable
private fun ColorFloodBoardSlot(
    s: ColorFloodState,
    palette: ColorFloodPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val n = s.size

    // Win-moment pixel constants, converted once (same idiom TowerDefenceScreen's own
    // `shakeMagnitudePx` uses) rather than re-converting inline at every use below.
    val shakePx = with(density) { WIN_SHAKE_MAGNITUDE_DP.dp.toPx() }
    val burstMinSpeedPx = with(density) { WIN_BURST_MIN_SPEED_DP.dp.toPx() }
    val burstMaxSpeedPx = with(density) { WIN_BURST_MAX_SPEED_DP.dp.toPx() }
    val burstGravityPx = with(density) { WIN_BURST_GRAVITY_DP.dp.toPx() }
    val shake = rememberCameraShake()
    val burst = remember { ParticleBurst() }
    // The board's own real pixel size (the canvas below IS the board), so the burst origin is the
    // board's center as it is at spawn time.
    var boardPx by remember { mutableStateOf(Size.Zero) }

    // The burst's frame clock runs only while particles are alive. The shared rememberParticleBurst
    // keeps a withFrameNanos loop spinning for the whole life of the screen even when idle.
    LaunchedEffect(burst) {
        snapshotFlow { burst.particles.value.isNotEmpty() }.collectLatest { alive ->
            if (!alive) return@collectLatest
            var lastNanos = 0L
            while (true) {
                withFrameNanos { nanos ->
                    if (lastNanos != 0L) burst.tick((nanos - lastNanos) / 1_000_000_000f)
                    lastNanos = nanos
                }
            }
        }
    }

    LaunchedEffect(s.won) {
        // Reduced motion: no shake and no particles. The chime and haptic live in the screen's own
        // win effect and are unaffected.
        if (!s.won || reducedMotion) return@LaunchedEffect

        // Launched rather than awaited so the shake's ~300ms decay never delays the burst below.
        launch { shake.trigger(durationMs = WIN_SHAKE_DECAY_MS, easing = FastOutSlowInEasing) }

        // The flood's own final color plus 1-2 hue-neighbors from this puzzle's own active
        // palette (wrapping mod colorCount, not the full 6-swatch list -- a board playing
        // with only 4 colors has no business bursting a 5th/6th color nobody ever saw) reads
        // as "this board's own colors celebrating," not a generic confetti overlay.
        val winIndex = s.currentColor
        val burstColors = (listOf(winIndex) + listOf(
            (winIndex + 1) % s.colorCount,
            (winIndex - 1 + s.colorCount) % s.colorCount
        ).distinct().filterNot { it == winIndex }).map { palette.colors[it] }

        if (boardPx.width > 0f && boardPx.height > 0f) {
            burst.spawn(
                origin = Offset(boardPx.width / 2f, boardPx.height / 2f),
                count = WIN_BURST_PARTICLE_COUNT,
                colors = burstColors,
                speedRange = burstMinSpeedPx..burstMaxSpeedPx,
                lifeRangeSeconds = 0.6f..1.1f,
                gravity = burstGravityPx
            )
        }
    }

    // Cross-fade: only a genuine single move on the SAME board fades (the repainted cells go from
    // their old color to the picked one). A new board or the first composition just appears. The
    // previous board is recorded in a SideEffect (once a composition is applied), never inside
    // `remember`, so a composition pass that is thrown away cannot swallow the next fade.
    val memory = remember { FloodFadeMemory() }
    val fade = remember(s.cellColors) {
        val before = memory.colors
        val oneMove = !reducedMotion && before != null &&
            before.size == s.cellColors.size && s.moves == memory.moves + 1
        FloodFade(if (oneMove) before else null)
    }
    SideEffect {
        memory.colors = s.cellColors
        memory.moves = s.moves
    }
    LaunchedEffect(fade) {
        if (fade.from != null) {
            fade.progress.animateTo(1f, animationSpec = tween(durationMillis = FLOOD_FADE_MS, easing = FastOutSlowInEasing))
        }
    }

    val boundary = remember(s.territory, n) { territoryBoundary(s.territory, n) }
    val floodName = FLOOD_COLOR_NAMES.getOrElse(s.currentColor) { "color ${s.currentColor + 1}" }.lowercase()
    val boardDescription = if (s.won) {
        "Board, $n by $n, fully flooded in ${movesText(s.moves)}"
    } else {
        "Board, $n by $n, ${s.territory.size} of ${n * n} cells flooded in $floodName"
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = remember(maxWidth, maxHeight, n) {
            fitBoard(
                availableWidthPx = maxWidth.value,
                availableHeightPx = maxHeight.value,
                columns = n,
                rows = n,
                minCellPx = MIN_CELL_DP,
                maxCellPx = MAX_CELL_DP
            )
        }
        val scrolls = !fit.meetsMinimum
        val boardDp: Dp = if (scrolls) MIN_CELL_DP.dp * n else fit.cellPx.dp * n
        Box(modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Canvas(
                modifier = Modifier
                    .size(boardDp)
                    // Shakes the grid AND the particles drawn into it together, as one physical board.
                    .then(if (reducedMotion) Modifier else Modifier.cameraShake(shake, magnitudePx = shakePx))
                    .onSizeChanged { boardPx = Size(it.width.toFloat(), it.height.toFloat()) }
                    .semantics { contentDescription = boardDescription }
            ) {
                drawFloodBoard(
                    n = n,
                    colors = s.cellColors,
                    from = fade.from,
                    // Read inside the draw lambda so a fade frame redraws without recomposing.
                    fadeT = fade.progress.value,
                    boundary = boundary,
                    showTerritory = !s.won,
                    palette = palette,
                    colorblind = colorblind
                )
                for (particle in burst.particles.value) {
                    drawCircle(
                        color = particle.color.copy(alpha = particle.lifeFraction),
                        radius = WIN_BURST_PARTICLE_RADIUS_DP.dp.toPx() * particle.lifeFraction.coerceAtLeast(0.3f),
                        center = particle.pos
                    )
                }
            }
        }
    }
}

/**
 * The edges of the flooded region as grid-line segments, four ints each: `x0, y0, x1, y1` in cell
 * units (so `gx` runs 0..n). Only edges between a territory cell and an in-board cell that is NOT in
 * the territory are included; the board's own outer edge needs no outline.
 */
private fun territoryBoundary(territory: Set<Int>, n: Int): IntArray {
    val segments = ArrayList<Int>()
    for (i in territory) {
        val row = i / n
        val col = i % n
        if (col < n - 1 && (i + 1) !in territory) {
            segments.add(col + 1); segments.add(row); segments.add(col + 1); segments.add(row + 1)
        }
        if (col > 0 && (i - 1) !in territory) {
            segments.add(col); segments.add(row); segments.add(col); segments.add(row + 1)
        }
        if (row < n - 1 && (i + n) !in territory) {
            segments.add(col); segments.add(row + 1); segments.add(col + 1); segments.add(row + 1)
        }
        if (row > 0 && (i - n) !in territory) {
            segments.add(col); segments.add(row); segments.add(col + 1); segments.add(row)
        }
    }
    return segments.toIntArray()
}

/**
 * Draws the whole mosaic: solid edge-to-edge cells (their edges snapped to whole pixels so no
 * hairline seam shows between them), a cross-fade from [from] to [colors] at [fadeT], a shape mark on
 * each cell under [colorblind], and, while [showTerritory], the two-tone territory outline from
 * [boundary] plus a ring on the origin cell.
 */
private fun DrawScope.drawFloodBoard(
    n: Int,
    colors: List<Int>,
    from: List<Int>?,
    fadeT: Float,
    boundary: IntArray,
    showTerritory: Boolean,
    palette: ColorFloodPalette,
    colorblind: Boolean
) {
    val cellW = size.width / n
    val cellH = size.height / n
    val xs = FloatArray(n + 1) { (it * cellW).roundToInt().toFloat() }
    val ys = FloatArray(n + 1) { (it * cellH).roundToInt().toFloat() }
    val minCell = minOf(cellW, cellH)
    val markUnit = minCell * 0.2f
    // Null (nothing allocated) unless colorblind mode is on and the cells are big enough to carry a
    // mark; otherwise one scratch set per draw, so the 256 marks of a 16x16 board allocate nothing
    // per cell.
    val markScratch = if (colorblind && minCell >= MARK_MIN_CELL_DP.dp.toPx()) MarkScratch(markUnit) else null

    for (row in 0 until n) {
        for (col in 0 until n) {
            val index = row * n + col
            val colorIndex = colors[index]
            val target = palette.colors[colorIndex]
            var fill = target
            if (from != null && fadeT < 1f) {
                val before = from[index]
                if (before != colorIndex) fill = lerp(palette.colors[before], target, fadeT)
            }
            drawRect(
                color = fill,
                topLeft = Offset(xs[col], ys[row]),
                size = Size(xs[col + 1] - xs[col], ys[row + 1] - ys[row])
            )
            if (markScratch != null) {
                val centre = Offset((xs[col] + xs[col + 1]) / 2f, (ys[row] + ys[row + 1]) / 2f)
                drawFloodMark(colorIndex, centre, unit = markUnit, ink = markInk(target), scratch = markScratch)
            }
        }
    }

    if (!showTerritory) return

    // Two passes (dark casing, then white core) so a core is never overdrawn by a neighbor's casing.
    val casingWidth = 3.5f.dp.toPx()
    val coreWidth = 1.5f.dp.toPx()
    val casing = INK_DARK.copy(alpha = 0.75f)
    for (pass in 0..1) {
        val color = if (pass == 0) casing else Color.White
        val width = if (pass == 0) casingWidth else coreWidth
        var k = 0
        while (k + 3 < boundary.size) {
            drawLine(
                color = color,
                start = Offset(xs[boundary[k]], ys[boundary[k + 1]]),
                end = Offset(xs[boundary[k + 2]], ys[boundary[k + 3]]),
                strokeWidth = width,
                cap = StrokeCap.Square
            )
            k += 4
        }
    }

    // The origin is always the top-left cell; the ring marks where the flood grows from.
    val originCentre = Offset((xs[0] + xs[1]) / 2f, (ys[0] + ys[1]) / 2f)
    val ringRadius = minCell * 0.38f
    drawCircle(color = casing, radius = ringRadius, center = originCentre, style = Stroke(width = casingWidth * 0.8f))
    drawCircle(color = Color.White, radius = ringRadius, center = originCentre, style = Stroke(width = coreWidth * 0.8f))
}

/** Near-black or white, whichever contrasts with [fill] (by luminance, so it works over any hue). */
private fun markInk(fill: Color): Color = if (fill.luminance() > 0.2f) INK_DARK else Color.White

/**
 * The reusable objects [drawFloodMark] needs, built once per draw pass for a given mark radius
 * [unit]: one [Path] (rewound before each polygon) and the ring's [Stroke]. Without it every
 * diamond/triangle/ring cell of a 16x16 board would allocate a fresh native Path or Stroke on every
 * animation frame.
 */
private class MarkScratch(unit: Float) {
    val path = Path()
    val ring = Stroke(width = unit * 0.55f)
}

/**
 * Colorblind-mode identity mark for color [colorIndex], centered at [at], [unit] = its radius. Six
 * distinct shapes for the six palette slots, the same six Edge Match uses for the same colors: dot,
 * ring, diamond, plus, X, triangle. Drawn in [ink] (near-black or white, whichever contrasts).
 * [scratch] must have been built with the same [unit].
 */
private fun DrawScope.drawFloodMark(colorIndex: Int, at: Offset, unit: Float, ink: Color, scratch: MarkScratch) {
    val stroke = unit * 0.55f
    when (colorIndex % 6) {
        0 -> drawCircle(color = ink, radius = unit, center = at)
        1 -> drawCircle(color = ink, radius = unit, center = at, style = scratch.ring)
        2 -> {
            val r = unit * 1.25f
            val diamond = scratch.path
            diamond.rewind()
            diamond.moveTo(at.x, at.y - r)
            diamond.lineTo(at.x + r, at.y)
            diamond.lineTo(at.x, at.y + r)
            diamond.lineTo(at.x - r, at.y)
            diamond.close()
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
        else -> {
            val r = unit * 1.3f
            val triangle = scratch.path
            triangle.rewind()
            triangle.moveTo(at.x, at.y - r)
            triangle.lineTo(at.x + r, at.y + r * 0.8f)
            triangle.lineTo(at.x - r, at.y + r * 0.8f)
            triangle.close()
            drawPath(triangle, color = ink)
        }
    }
}

// ---------------------------------------------------------------------------
// Swatches
// ---------------------------------------------------------------------------

/**
 * The color swatches: the only controls that act on the board. Each is one button at least 48dp
 * wide and 56dp tall (a 36dp disc plus its `+N` label), described by its color name and what it
 * would add. The row shares its width evenly, so six swatches still get 48dp each on a 288dp-wide
 * Fold cover content area, and is capped so a tablet's swatches do not spread across the screen.
 * The current color is ringed and not tappable (picking it is a no-op in the engine too); with
 * [colorblind] every disc carries its shape mark. Disabled (dimmed) once the board is flooded.
 */
@Composable
private fun ColorSwatches(
    colorCount: Int,
    currentColor: Int,
    absorbs: List<Int>,
    palette: ColorFloodPalette,
    colorblind: Boolean,
    enabled: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.widthIn(max = 72.dp * colorCount).fillMaxWidth()) {
        for (c in 0 until colorCount) {
            val isCurrent = c == currentColor
            val name = FLOOD_COLOR_NAMES.getOrElse(c) { "Color ${c + 1}" }
            val absorbed = absorbs.getOrElse(c) { 0 }
            val description = when {
                isCurrent -> "$name, current color"
                absorbed == 1 -> "$name, adds 1 cell"
                else -> "$name, adds $absorbed cells"
            }
            val fill = palette.colors[c]
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .alpha(if (enabled) 1f else 0.4f)
                    .clickable(
                        enabled = enabled && !isCurrent,
                        onClickLabel = "Flood with ${name.lowercase()}",
                        role = Role.Button,
                        onClick = { onPick(c) }
                    )
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .border(
                            width = if (isCurrent) 3.dp else 1.dp,
                            color = if (isCurrent) palette.textPrimary else palette.textPrimary.copy(alpha = 0.25f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (colorblind) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val markUnit = size.minDimension * 0.17f
                            drawFloodMark(
                                colorIndex = c,
                                at = Offset(size.width / 2f, size.height / 2f),
                                unit = markUnit,
                                ink = markInk(fill),
                                scratch = MarkScratch(markUnit)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                // Decorative: the button's own description already says all of this.
                Text(
                    if (isCurrent) "✓" else "+$absorbed",
                    color = palette.textPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@Composable
private fun ColorFloodResultPanel(
    moves: Int,
    timeMillis: Long?,
    record: ColorFloodRecord?,
    isNewBestMoves: Boolean,
    isNewBestTime: Boolean,
    daily: Boolean,
    onNewBoard: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: ColorFloodPalette,
    modifier: Modifier = Modifier
) {
    // Opaque (unlike a translucent tint) because on a phone this panel overlays the board's lower part.
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
                if (daily) "Daily board flooded!" else "Board flooded!",
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
