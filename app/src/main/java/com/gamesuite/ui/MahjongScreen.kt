package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.mahjong.MahjongGame
import com.gamesuite.games.mahjong.MahjongLayoutId
import com.gamesuite.games.mahjong.MahjongLayouts
import com.gamesuite.games.mahjong.MahjongState
import com.gamesuite.games.mahjong.MahjongStatsStore
import com.gamesuite.games.mahjong.MahjongSuit
import com.gamesuite.games.mahjong.MahjongTap
import com.gamesuite.games.mahjong.MahjongTiles
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

/** Below this tile width (dp) the board stops shrinking and pans instead. */
private const val MJ_MIN_TILE_DP = 28f

/** Upper bound so a small layout does not sprawl across a tablet. */
private const val MJ_MAX_TILE_DP = 56f

/** Tile height over tile width (a real mahjong tile is taller than wide). */
private const val MJ_TILE_ASPECT = 1.3f

/** The visible thickness of a tile and the shift per layer, as a fraction of the tile width. */
private const val MJ_DEPTH_FRACTION = 0.09f

/** Zoom steps of the Zoom button, as multiples of the fitted tile width. */
private val MJ_ZOOM_LEVELS = floatArrayOf(1f, 1.5f, 2f)
private val MJ_ZOOM_LABELS = arrayOf("1×", "1.5×", "2×")

private val MJ_CONTENT_MAX_WIDTH = 720.dp
private val MJ_SIDE_PANE_WIDTH = 270.dp
private const val MJ_MESSAGE_VISIBLE_MS = 2500L

/**
 * Mahjong Solitaire: remove pairs of matching free tiles from a layered layout.
 *
 * BOARD: every tile is one small composable placed on the half-tile grid, drawn back to front (layer,
 * then row, then column) with a drop shadow and a jade side under an ivory face so layers read as
 * depth. Tiles show the Unicode mahjong glyph (U+1F000..U+1F029) when the device font has it and a
 * drawn fallback (big rank plus suit letter) when it does not, so they never render as tofu; a
 * corner label ("3B") with the rank and suit letter is always drawn next to the glyph.
 *
 * NEVER COLOUR ALONE: a blocked tile is dimmed AND crossed with diagonal hatching; the selected tile
 * is lifted AND gets a solid heavy outline; the hinted pair gets a dashed outline (and pulses unless
 * reduced motion is on); suits are told apart by glyph and corner letter, with ink colour only as a
 * bonus. Each tile is one button described like "Bamboo 3, free" / "Bamboo 3, blocked".
 *
 * LAYOUT: the board is sized by the shared [fitBoard] against the measured slot (tile widths as
 * columns, 28dp floor); when even that floor does not fit, or the player zooms in, it pans in both
 * axes. Portrait stacks header, board and controls; a wide landscape window puts the controls in a
 * side pane.
 */
@Composable
fun MahjongScreen(
    sessionManager: GameSessionManager,
    game: MahjongGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val statsStore = remember { MahjongStatsStore(androidContext) }
    val state by game.state
    val palette = mahjongPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val tileText = rememberMahjongTileText()
    val glyphSupport = remember { mahjongGlyphSupport() }

    var zoomIndex by remember { mutableStateOf(0) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    var messageTick by remember { mutableStateOf(0) }
    var confirmNew by remember { mutableStateOf(false) }
    // null until this board's solve has been written to the stats store; then whether it was a new best.
    var newBest by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val wanted = MahjongLayoutId.fromKey(sessionManager.launchOptions.extras["layout"])
        game.layout = wanted
        if (!sessionManager.tryResume(game)) {
            game.startMatchWith(wanted, null)
        }
    }

    LaunchedEffect(messageTick) {
        if (messageTick > 0) {
            delay(MJ_MESSAGE_VISIBLE_MS)
            messageRes = null
        }
    }

    val s = state ?: return

    val geo = MahjongLayouts.geometry(s.layout)
    val free = remember(s.types) { game.freeMask(s) }
    val pairs = remember(s.types) { game.availablePairs() }
    val stuck = !s.won && s.remaining > 0 && pairs.isEmpty()
    val hintPair = game.hintPair.value
    var deadEnd by remember(s.types) { mutableStateOf(false) }
    val allBest by statsStore.bestTimesMillis.collectAsState(initial = emptyMap())
    val bestMillis = allBest[s.layout.key]
    val solvedBefore = game.gamesSolved.value
    val layoutName = mahjongLayoutName(s.layout)

    LaunchedEffect(s.won) {
        if (!s.won) return@LaunchedEffect
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (newBest == null) {
            if (!game.isUnassisted) {
                // An assisted or shuffled solve still counts as solved, but never sets a record.
                newBest = false
            } else {
                val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
                newBest = statsStore.recordWin(s.layout, finalTime)
            }
        }
    }

    LaunchedEffect(stuck) {
        if (stuck) haptics(HapticSignal.ESCALATING)
    }

    fun showMessage(res: Int) {
        messageRes = res
        messageTick += 1
    }

    val startNew: () -> Unit = {
        if (!game.matchOver.value) {
            newBest = null
            messageRes = null
            game.playAgain()
        }
    }
    val onNewGame: () -> Unit = {
        val live = game.state.value
        if (live != null && !live.won && live.moves > 0) confirmNew = true else startNew()
    }
    val onTap: (Int) -> Unit = { index ->
        when (game.tapTile(index)) {
            MahjongTap.SELECTED, MahjongTap.DESELECTED -> {
                messageRes = null
                haptics(HapticSignal.LIGHT_TICK)
            }
            MahjongTap.MATCHED -> {
                messageRes = null
                haptics(HapticSignal.NORMAL_ACTION)
                playSfx(SfxKind.SOLID_THUNK)
            }
            MahjongTap.MISMATCH -> {
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
                showMessage(R.string.mj_status_mismatch)
            }
            MahjongTap.BLOCKED -> {
                haptics(HapticSignal.FAILURE)
                showMessage(R.string.mj_status_blocked)
            }
            MahjongTap.IGNORED -> Unit
        }
    }
    val onUndo: () -> Unit = {
        if (game.canUndo) {
            game.undo()
            messageRes = null
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onHint: () -> Unit = {
        if (game.hint() != null) {
            haptics(HapticSignal.NORMAL_ACTION)
            showMessage(R.string.mj_status_hint)
        } else {
            haptics(HapticSignal.FAILURE)
            showMessage(R.string.mj_status_no_hint)
        }
    }
    val onShuffle: () -> Unit = {
        if (game.shuffle()) {
            messageRes = null
            haptics(HapticSignal.NORMAL_ACTION)
            playSfx(SfxKind.WHOOSH)
        } else {
            haptics(HapticSignal.FAILURE)
            deadEnd = true
        }
    }
    val onZoom: () -> Unit = { zoomIndex = (zoomIndex + 1) % MJ_ZOOM_LEVELS.size }

    GameChrome(
        helpTitle = stringResource(R.string.mj_help_title),
        helpText = stringResource(R.string.mj_help_body),
        matchInProgress = !s.won,
        onLeave = game::leaveSession,
        onAbort = { if (solvedBefore > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = solvedBefore == 0,
        onUndo = onUndo,
        canUndo = game.canUndo && !s.won,
        onHint = if (s.won) null else onHint,
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = stringResource(R.string.mj_leave_title),
        leaveBody = stringResource(if (solvedBefore > 0) R.string.mj_leave_body_scored else R.string.mj_leave_body)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            val availableHeight = maxHeight
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.15f
            val compactControls = maxWidth < 420.dp

            val header: @Composable (Modifier) -> Unit = { m ->
                MahjongHeader(
                    tilesLeft = s.remaining,
                    layoutName = layoutName,
                    assisted = !game.isUnassisted,
                    game = game,
                    palette = palette,
                    modifier = m
                )
            }
            val statusRes = messageRes ?: when {
                s.won -> null
                stuck -> R.string.mj_status_stuck
                hintPair != null -> R.string.mj_status_hint
                else -> R.string.mj_status_idle
            }
            val status: @Composable (Modifier) -> Unit = { m ->
                Text(
                    text = if (statusRes != null) stringResource(statusRes) else "",
                    color = palette.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = m
                        .heightIn(min = 40.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            val board: @Composable (Modifier) -> Unit = { m ->
                MahjongBoardSlot(
                    s = s,
                    free = free,
                    hint = hintPair,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    zoom = MJ_ZOOM_LEVELS[zoomIndex],
                    text = tileText,
                    glyphSupport = glyphSupport,
                    onTap = onTap,
                    modifier = m
                )
            }
            val bottom: @Composable (Modifier, Int) -> Unit = { m, columns ->
                when {
                    s.won -> MahjongResultPanel(
                        timeMillis = game.finishedElapsedMillis.value,
                        bestMillis = bestMillis,
                        isNewBest = newBest == true,
                        hintsUsed = game.hintsUsed.value,
                        shuffles = game.shuffleCount.value,
                        onNewGame = startNew,
                        onBackToMenu = { game.leaveSession() },
                        palette = palette,
                        modifier = m
                    )
                    stuck -> MahjongStuckPanel(
                        deadEnd = deadEnd,
                        canUndo = game.canUndo,
                        onShuffle = onShuffle,
                        onUndo = onUndo,
                        onNewGame = onNewGame,
                        palette = palette,
                        modifier = m
                    )
                    else -> MahjongControls(
                        columns = columns,
                        canUndo = game.canUndo,
                        zoomLabel = MJ_ZOOM_LABELS[zoomIndex],
                        onUndo = onUndo,
                        onHint = onHint,
                        onShuffle = onShuffle,
                        canShuffle = game.canShuffle,
                        onZoom = onZoom,
                        onNewGame = onNewGame,
                        palette = palette,
                        modifier = m
                    )
                }
            }

            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    board(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(MJ_SIDE_PANE_WIDTH)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        header(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        status(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        bottom(Modifier.fillMaxWidth(), 2)
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = MJ_CONTENT_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        header(Modifier.fillMaxWidth().padding(end = GameChromeEndInset))
                        status(Modifier.fillMaxWidth())
                        board(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        bottom(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (s.won || stuck) {
                                        Modifier
                                            .heightIn(max = availableHeight * 0.5f)
                                            .verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    }
                                ),
                            if (compactControls) 3 else 5
                        )
                    }
                }
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            containerColor = palette.panel,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text(stringResource(R.string.mj_new_title)) },
            text = { Text(stringResource(R.string.mj_new_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNew = false
                        startNew()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text(stringResource(R.string.mj_btn_new_game), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmNew = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text(stringResource(R.string.mj_keep_playing)) }
            }
        )
    }
}

private fun formatMahjongClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun mahjongLayoutName(id: MahjongLayoutId): String = stringResource(
    when (id) {
        MahjongLayoutId.TURTLE -> R.string.mj_layout_turtle
        MahjongLayoutId.PYRAMID -> R.string.mj_layout_pyramid
        MahjongLayoutId.CROSS -> R.string.mj_layout_cross
    }
)

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

private data class MahjongPalette(
    val background: Color,
    val panel: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val accent: Color,
    val tileFace: Color,
    val tileSide: Color,
    val tileBorder: Color,
    val hatch: Color,
    val scrim: Color,
    /** Solid, heavy outline of the selected tile (dark enough on the ivory face in both themes). */
    val selectedOutline: Color,
    /** Dashed outline of the hinted pair. */
    val hintOutline: Color,
    val dotsInk: Color,
    val bambooInk: Color,
    val charactersInk: Color,
    val honorInk: Color,
    val bonusInk: Color
)

private val MahjongLightPalette = MahjongPalette(
    background = Color(0xFFDCE8D8),
    panel = Color(0xFFF4F8F0),
    textPrimary = Color(0xFF1F2A1F),
    textOnAccent = Color(0xFF14201A),
    accent = Color(0xFF6FBF8E),
    tileFace = Color(0xFFFBF6E6),
    tileSide = Color(0xFF2F7D5B),
    tileBorder = Color(0xFF8A7E5E),
    hatch = Color(0xFF5A6B58),
    scrim = Color(0xFF2A3A2A),
    selectedOutline = Color(0xFFC2410C),
    hintOutline = Color(0xFF1D4ED8),
    dotsInk = Color(0xFF1C5FA8),
    bambooInk = Color(0xFF1E6E2E),
    charactersInk = Color(0xFFB3261E),
    honorInk = Color(0xFF3A2E22),
    bonusInk = Color(0xFF7A3E9D)
)

private val MahjongDarkPalette = MahjongPalette(
    background = Color(0xFF101C14),
    panel = Color(0xFF1A2A20),
    textPrimary = Color(0xFFE6EFE2),
    textOnAccent = Color(0xFF0E1A12),
    accent = Color(0xFF7FD1A0),
    tileFace = Color(0xFFEAE3CD),
    tileSide = Color(0xFF1F5A41),
    tileBorder = Color(0xFF6F654A),
    hatch = Color(0xFF3F4F3E),
    scrim = Color(0xFF0E1A12),
    selectedOutline = Color(0xFFC2410C),
    hintOutline = Color(0xFF1D4ED8),
    dotsInk = Color(0xFF1C5FA8),
    bambooInk = Color(0xFF1E6E2E),
    charactersInk = Color(0xFFB3261E),
    honorInk = Color(0xFF3A2E22),
    bonusInk = Color(0xFF7A3E9D)
)

private fun mahjongPalette(isDark: Boolean) = if (isDark) MahjongDarkPalette else MahjongLightPalette

private fun mahjongInk(type: Int, p: MahjongPalette): Color = when (MahjongTiles.suit(type)) {
    MahjongSuit.DOTS -> p.dotsInk
    MahjongSuit.BAMBOO -> p.bambooInk
    MahjongSuit.CHARACTERS -> p.charactersInk
    MahjongSuit.WIND, MahjongSuit.DRAGON -> p.honorInk
    MahjongSuit.FLOWER, MahjongSuit.SEASON -> p.bonusInk
}

// ---------------------------------------------------------------------------
// Tile text and glyph support
// ---------------------------------------------------------------------------

/** Spoken names, short corner labels and fallback faces of the 42 tile types, in the current locale. */
private class MahjongTileText(
    val names: List<String>,
    val shorts: List<String>,
    /** Big text of the drawn fallback face (the rank for number suits, else the short label). */
    val bigs: List<String>,
    /** Small text under the big one on the fallback face (the suit letter), or null. */
    val subs: List<String?>
)

@Composable
private fun rememberMahjongTileText(): MahjongTileText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { buildMahjongTileText(context) }
}

private fun buildMahjongTileText(context: Context): MahjongTileText {
    val suitNames = listOf(
        context.getString(R.string.mj_suit_dots),
        context.getString(R.string.mj_suit_bamboo),
        context.getString(R.string.mj_suit_characters)
    )
    val suitShorts = listOf(
        context.getString(R.string.mj_short_dots),
        context.getString(R.string.mj_short_bamboo),
        context.getString(R.string.mj_short_characters)
    )
    val windNames = listOf(R.string.mj_wind_east, R.string.mj_wind_south, R.string.mj_wind_west, R.string.mj_wind_north).map { context.getString(it) }
    val windShorts = listOf(R.string.mj_short_east, R.string.mj_short_south, R.string.mj_short_west, R.string.mj_short_north).map { context.getString(it) }
    val dragonNames = listOf(R.string.mj_dragon_red, R.string.mj_dragon_green, R.string.mj_dragon_white).map { context.getString(it) }
    val dragonShorts = listOf(R.string.mj_short_red, R.string.mj_short_green, R.string.mj_short_white).map { context.getString(it) }
    val flowerNames = listOf(R.string.mj_flower_plum, R.string.mj_flower_orchid, R.string.mj_flower_chrysanthemum, R.string.mj_flower_bamboo).map { context.getString(it) }
    val seasonNames = listOf(R.string.mj_season_spring, R.string.mj_season_summer, R.string.mj_season_autumn, R.string.mj_season_winter).map { context.getString(it) }
    val flowerShort = context.getString(R.string.mj_short_flower)
    val seasonShort = context.getString(R.string.mj_short_season)

    val names = ArrayList<String>(MahjongTiles.TYPE_COUNT)
    val shorts = ArrayList<String>(MahjongTiles.TYPE_COUNT)
    val bigs = ArrayList<String>(MahjongTiles.TYPE_COUNT)
    val subs = ArrayList<String?>(MahjongTiles.TYPE_COUNT)
    for (t in 0 until MahjongTiles.TYPE_COUNT) {
        val rank = MahjongTiles.rank(t)
        when (MahjongTiles.suit(t)) {
            MahjongSuit.DOTS, MahjongSuit.BAMBOO, MahjongSuit.CHARACTERS -> {
                val idx = t / 9
                names.add(context.getString(R.string.mj_tile_numbered, suitNames[idx], rank))
                shorts.add("$rank${suitShorts[idx]}")
                bigs.add(rank.toString())
                subs.add(suitShorts[idx])
            }
            MahjongSuit.WIND -> {
                names.add(windNames[rank - 1])
                shorts.add(windShorts[rank - 1])
                bigs.add(windShorts[rank - 1])
                subs.add(null)
            }
            MahjongSuit.DRAGON -> {
                names.add(dragonNames[rank - 1])
                shorts.add(dragonShorts[rank - 1])
                bigs.add(dragonShorts[rank - 1])
                subs.add(null)
            }
            MahjongSuit.FLOWER -> {
                names.add(flowerNames[rank - 1])
                shorts.add("$flowerShort$rank")
                bigs.add(flowerShort)
                subs.add(rank.toString())
            }
            MahjongSuit.SEASON -> {
                names.add(seasonNames[rank - 1])
                shorts.add("$seasonShort$rank")
                bigs.add(seasonShort)
                subs.add(rank.toString())
            }
        }
    }
    return MahjongTileText(names, shorts, bigs, subs)
}

/** Which tile glyphs the device fonts can draw; a missing one falls back to the drawn face. */
private fun mahjongGlyphSupport(): BooleanArray {
    val paint = android.graphics.Paint()
    return BooleanArray(MahjongTiles.TYPE_COUNT) { t ->
        try {
            paint.hasGlyph(MahjongTiles.glyph(t))
        } catch (_: Throwable) {
            false
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun MahjongHeader(
    tilesLeft: Int,
    layoutName: String,
    assisted: Boolean,
    game: MahjongGame,
    palette: MahjongPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                stringResource(R.string.mj_tiles_left, tilesLeft),
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (assisted) "$layoutName · ${stringResource(R.string.mj_assisted)}" else layoutName,
                color = palette.textPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MahjongClock(game = game, palette = palette)
    }
}

/** Reads [MahjongGame.activeElapsedMillis] on its own 5Hz tick so only this Text recomposes. */
@Composable
private fun MahjongClock(game: MahjongGame, palette: MahjongPalette) {
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
    val clockText = formatMahjongClock(elapsed)
    Text(
        clockText,
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = clockText }
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

@Composable
private fun MahjongBoardSlot(
    s: MahjongState,
    free: BooleanArray,
    hint: Pair<Int, Int>?,
    palette: MahjongPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    zoom: Float,
    text: MahjongTileText,
    glyphSupport: BooleanArray,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val geo = MahjongLayouts.geometry(s.layout)
    val positions = geo.positions
    val order = remember(s.layout) {
        positions.indices.sortedWith(compareBy<Int>({ positions[it].z }, { positions[it].y }, { positions[it].x }))
    }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widthTiles = (geo.maxX + 2) / 2
        val heightTiles = (geo.maxY + 2) / 2
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = widthTiles + 1,
            rows = ceil(heightTiles * MJ_TILE_ASPECT + 0.6f).toInt(),
            minCellPx = MJ_MIN_TILE_DP,
            maxCellPx = MJ_MAX_TILE_DP
        )
        val fitted = if (fit.meetsMinimum) {
            // Whole device pixels, trimmed by one so rounding can never overshoot the slot.
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp().value }
        } else {
            MJ_MIN_TILE_DP
        }
        val tileW = fitted * zoom
        val tileH = tileW * MJ_TILE_ASPECT
        val depth = tileW * MJ_DEPTH_FRACTION
        val layers = geo.maxZ
        val pad = depth * (layers + 2.2f)
        val boardW = widthTiles * tileW + pad
        val boardH = heightTiles * tileH + pad
        val pans = boardW > maxWidth.value + 0.5f || boardH > maxHeight.value + 0.5f

        val fontScale = { v: Float -> with(density) { v.dp.toSp() } }

        Box(modifier = if (pans) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier) {
            Box(modifier = Modifier.size(boardW.dp, boardH.dp)) {
                for (i in order) {
                    val type = s.types[i]
                    if (type < 0) continue
                    key(i) {
                        val p = positions[i]
                        MahjongTileView(
                            index = i,
                            type = type,
                            free = free[i],
                            isSelected = s.selected == i,
                            hinted = hint != null && (hint.first == i || hint.second == i),
                            tileW = tileW,
                            tileH = tileH,
                            depth = depth,
                            left = p.x * tileW / 2f + (layers - p.z) * depth,
                            top = p.y * tileH / 2f + (layers - p.z) * depth,
                            palette = palette,
                            colorblind = colorblind,
                            reducedMotion = reducedMotion,
                            name = text.names[type],
                            short = text.shorts[type],
                            big = text.bigs[type],
                            sub = text.subs[type],
                            glyph = if (glyphSupport[type]) MahjongTiles.glyph(type) else null,
                            fontScale = fontScale,
                            onTap = onTap
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMahjongHintAlpha(active: Boolean, reducedMotion: Boolean): Float {
    if (!active || reducedMotion) return 1f
    val transition = rememberInfiniteTransition(label = "mahjongHint")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "mahjongHintAlpha"
    )
    return alpha
}

@Composable
private fun MahjongTileView(
    index: Int,
    type: Int,
    free: Boolean,
    isSelected: Boolean,
    hinted: Boolean,
    tileW: Float,
    tileH: Float,
    depth: Float,
    left: Float,
    top: Float,
    palette: MahjongPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    name: String,
    short: String,
    big: String,
    sub: String?,
    glyph: String?,
    fontScale: (Float) -> androidx.compose.ui.unit.TextUnit,
    onTap: (Int) -> Unit
) {
    val hintAlpha = rememberMahjongHintAlpha(hinted, reducedMotion)
    val description = when {
        isSelected -> stringResource(R.string.mj_tile_selected, name)
        hinted -> stringResource(R.string.mj_tile_hinted, name)
        free -> stringResource(R.string.mj_tile_free, name)
        else -> stringResource(R.string.mj_tile_blocked, name)
    }
    val ink = mahjongInk(type, palette)
    // A selected tile is lifted off the board: a shape cue that does not rely on colour.
    val lift = if (isSelected) depth * 1.4f else 0f

    Box(
        modifier = Modifier
            .offset(x = left.dp, y = (top - lift).dp)
            .size(tileW.dp, tileH.dp)
            .drawBehind {
                val d = depth.dp.toPx()
                val corner = CornerRadius(tileW.dp.toPx() * 0.12f)
                drawRoundRect(Color.Black.copy(alpha = 0.28f), topLeft = Offset(d * 1.9f, d * 1.9f), size = size, cornerRadius = corner)
                drawRoundRect(palette.tileSide, topLeft = Offset(d, d), size = size, cornerRadius = corner)
                drawRoundRect(palette.tileFace, size = size, cornerRadius = corner)
                drawRoundRect(palette.tileBorder, size = size, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
                if (!free) {
                    drawRoundRect(palette.scrim.copy(alpha = 0.35f), size = size, cornerRadius = corner)
                    val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner)) }
                    clipPath(clip) {
                        val step = 7.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(
                                color = palette.hatch,
                                start = Offset(x, size.height),
                                end = Offset(x + size.height, 0f),
                                strokeWidth = 1.2.dp.toPx()
                            )
                            x += step
                        }
                    }
                }
                if (hinted) {
                    drawRoundRect(
                        palette.hintOutline.copy(alpha = hintAlpha),
                        size = size,
                        cornerRadius = corner,
                        style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)))
                    )
                }
                if (isSelected) {
                    drawRoundRect(palette.selectedOutline, size = size, cornerRadius = corner, style = Stroke(width = 3.5.dp.toPx()))
                }
            }
            .semantics { this.contentDescription = description; this.selected = isSelected }
            .clickable(onClickLabel = name, role = Role.Button) { onTap(index) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (free) Modifier else Modifier.alpha(0.55f))
                .clearAndSetSemantics {}
        ) {
            if (glyph != null) {
                Text(
                    glyph,
                    color = ink,
                    fontSize = fontScale(tileW * 0.72f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.Center)
                )
                Text(
                    short,
                    color = ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontScale(tileW * if (colorblind) 0.30f else 0.25f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = 1.dp)
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        big,
                        color = ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontScale(tileW * 0.5f),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (sub != null) {
                        Text(
                            sub,
                            color = ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontScale(tileW * 0.3f),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Controls and panels
// ---------------------------------------------------------------------------

@Composable
private fun MahjongActionButton(
    label: String,
    onClick: () -> Unit,
    palette: MahjongPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val padding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.textOnAccent),
            contentPadding = padding
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
            contentPadding = padding
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun MahjongControls(
    columns: Int,
    canUndo: Boolean,
    canShuffle: Boolean,
    zoomLabel: String,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onShuffle: () -> Unit,
    onZoom: () -> Unit,
    onNewGame: () -> Unit,
    palette: MahjongPalette,
    modifier: Modifier = Modifier
) {
    val undo = stringResource(R.string.mj_btn_undo)
    val hint = stringResource(R.string.mj_btn_hint)
    val shuffle = stringResource(R.string.mj_btn_shuffle)
    val zoom = stringResource(R.string.mj_btn_zoom, zoomLabel)
    val newGame = stringResource(R.string.mj_btn_new_game)
    val items = listOf<@Composable (Modifier) -> Unit>(
        { m -> MahjongActionButton(undo, onUndo, palette, m, enabled = canUndo) },
        { m -> MahjongActionButton(hint, onHint, palette, m) },
        { m -> MahjongActionButton(shuffle, onShuffle, palette, m, enabled = canShuffle, primary = canShuffle) },
        { m -> MahjongActionButton(zoom, onZoom, palette, m) },
        { m -> MahjongActionButton(newGame, onNewGame, palette, m) }
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rowItems in items.chunked(columns)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in rowItems) item(Modifier.weight(1f))
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MahjongStuckPanel(
    deadEnd: Boolean,
    canUndo: Boolean,
    onShuffle: () -> Unit,
    onUndo: () -> Unit,
    onNewGame: () -> Unit,
    palette: MahjongPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.panel, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.mj_stuck_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (deadEnd) R.string.mj_stuck_dead_end else R.string.mj_stuck_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MahjongActionButton(
                    stringResource(R.string.mj_btn_shuffle), onShuffle, palette,
                    Modifier.weight(1f), enabled = !deadEnd, primary = true
                )
                MahjongActionButton(stringResource(R.string.mj_btn_undo), onUndo, palette, Modifier.weight(1f), enabled = canUndo)
            }
            Spacer(Modifier.height(8.dp))
            MahjongActionButton(stringResource(R.string.mj_btn_new_game), onNewGame, palette, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MahjongResultPanel(
    timeMillis: Long?,
    bestMillis: Long?,
    isNewBest: Boolean,
    hintsUsed: Int,
    shuffles: Int,
    onNewGame: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: MahjongPalette,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = palette.panel, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.mj_solved_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(4.dp))
            if (timeMillis != null) {
                Text(
                    stringResource(R.string.mj_solved_time, formatMahjongClock(timeMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            if (hintsUsed > 0) {
                Text(stringResource(R.string.mj_solved_hints, hintsUsed), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            if (shuffles > 0) {
                Text(stringResource(R.string.mj_solved_shuffles, shuffles), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            if (hintsUsed > 0 || shuffles > 0) {
                Text(
                    stringResource(R.string.mj_solved_assisted),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            if (isNewBest) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.mj_solved_new_best), fontWeight = FontWeight.Bold)
            } else if (bestMillis != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.mj_solved_best, formatMahjongClock(bestMillis)), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MahjongActionButton(stringResource(R.string.mj_btn_new_game), onNewGame, palette, Modifier.weight(1f), primary = true)
                MahjongActionButton(stringResource(R.string.mj_btn_back_to_menu), onBackToMenu, palette, Modifier.weight(1f))
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
