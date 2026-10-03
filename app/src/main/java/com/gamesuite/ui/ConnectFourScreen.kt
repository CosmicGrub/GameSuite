package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.connectfour.ConnectFourGame
import com.gamesuite.games.connectfour.ConnectFourState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.CameraShake
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/** The board Row's own inset from its frame background. It is both the real `padding` of that Row
 *  and the `framePx` handed to [fitBoard], and it is the offset the win-burst particle math adds,
 *  so the three can never drift apart. */
private val BOARD_FRAME_PADDING = 4.dp

/** Below this cell size (dp) the board stops shrinking and scrolls instead. */
private const val MIN_CELL_DP = 28f

/** Upper bound on a cell (dp) so the board does not sprawl on a tablet: 7 x 80 = a 568dp-wide board. */
private const val MAX_CELL_DP = 80f

/** Dark ink for the win ring and for marks drawn on the light (yellow) disc. */
private val DISC_INK_DARK = Color(0xFF1A1208)

/**
 * Renders ConnectFourGame's state reactively. Same overall shape as DotsAndBoxesScreen: no
 * daily-seed route (a solo-puzzle concept in this app), and the same 800ms-delayed
 * `LaunchedEffect` + `game.playBotTurn()` idiom so a bot's move doesn't feel instantaneous, which
 * simply never fires in SINGLE_DEVICE_PASS_AND_PLAY mode since neither player is a bot there.
 *
 * A tap anywhere in a column (not just its lowest empty cell) drops into that column, matching a
 * real Connect Four cabinet, where you aim at a column, not a specific slot.
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play) plus its BackHandler.
 * Leaving mid-board goes through `GameModule.abortMatch` (confirmation first, never recorded to
 * stats) when nothing has been finished yet. If earlier boards in this session were already won
 * or drawn, leaving goes through `leaveSession()` instead so those results still count, and the
 * confirm dialog says so. A finished board always leaves through `leaveSession()`. The status
 * row reserves [GameChromeEndInset] at its end so the corner button never covers a player chip.
 *
 * LAYOUT: [fitBoard] against the measured space of the board's own slot, a 28-80dp cell clamp,
 * and a scroll fallback (never a bigger-than-container board) if the slot cannot give a cell 28dp.
 * Portrait stacks status / board / result; a wide landscape window (a tablet, a landscape phone)
 * puts the board on the left and status plus the result panel in a column on the right, so the
 * result panel never shrinks the board.
 *
 * MOTION: a dropped disc falls from above the board with an accelerating ease, 190 to 350ms
 * depending on how far it drops; a genuine human win adds a camera shake and a particle burst.
 * All of it is off under `LocalReducedMotion`. The win burst is a one-shot: its per-frame tick
 * runs only while particles are alive (the shared `rememberParticleBurst` loop never stops).
 * Celebration (haptic, chime, shake, burst, green win ring) is for a HUMAN win only; a CPU win or
 * a tie gets a plain haptic and a neutral white-and-ink ring on the winning four.
 *
 * ACCESSIBILITY: each column is one button described by its number, its free slots and every
 * disc in it (colour, row, winning-four membership). Player chips are one labelled node each,
 * the status line is a polite live region. With `LocalColorblindMode` on, red discs carry a
 * ring and yellow discs a diamond. The winning four is also marked by a double ring, a
 * luminance cue rather than hue alone.
 *
 * VISUAL IDENTITY: chrome (background/text/buttons) shares its warm tokens with the rest of this
 * batch (see [connectFourPalette]), but the BOARD ITSELF, frame plus discs, is the one
 * deliberate exception, same reasoning ColorFloodScreen's own KDoc gives for ITS cell colors:
 * Connect Four's blue frame with red/yellow discs is one of the most instantly recognizable
 * visual signatures in board games, and reinventing it in the warm palette would trade real
 * recognizability for consistency this particular game doesn't need.
 */
@Composable
fun ConnectFourScreen(
    sessionManager: GameSessionManager,
    game: ConnectFourGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.CONNECT_FOUR, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = connectFourPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws

    // JUICE: the shared com.gamesuite.ui.effects.CameraShake/ParticleBurst utilities, reserved for
    // this game's single biggest moment, a genuine human four-in-a-row win. The burst is built
    // directly (not via rememberParticleBurst) so its frame loop can stop when it has nothing to
    // animate; see the win effect below. density-derived pixel values are resolved once up here
    // (a @Composable CompositionLocal read) so the effect's suspend lambda can close over them.
    val cameraShake = rememberCameraShake()
    val particleBurst = remember { ParticleBurst() }
    val density = LocalDensity.current
    val shakeMagnitudePx = with(density) { 14.dp.toPx() }
    val framePaddingPx = with(density) { BOARD_FRAME_PADDING.toPx() }
    // The board's real laid-out size (frame included), reported by the board itself, so the win
    // burst's particle origins are computed from what was actually drawn, whichever of the two
    // layouts below is showing.
    var boardSizePx by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch()
    }

    // Same idiom as DotsAndBoxesScreen/DominoesScreen's own bot-turn trigger -- a short
    // delay so a bot's move doesn't feel instantaneous. No recursion needed here (unlike
    // Dots and Boxes' "go again" rule) since a single playBotTurn() call always either
    // ends the board or passes the turn to the human.
    LaunchedEffect(state?.currentPlayerIndex, state?.boardOver) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
            sounds.playTap()
        }
    }

    val s = state ?: return

    val current = s.players[s.currentPlayerIndex]
    val isMyTurn = !current.isBot
    val vsBot = s.players.any { it.isBot }
    val winnerIndex = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
    // Pass-and-play has two humans, so any win there is a human win; against the CPU only the
    // human's is. A tie has no winner and never celebrates.
    val humanWon = s.boardOver && winnerIndex >= 0 && !s.players[winnerIndex].isBot
    val statusText = when {
        s.boardOver -> resultTitle(s)
        current.isBot -> "${current.displayName} is thinking…"
        vsBot -> "Your turn — tap a column"
        else -> "${current.displayName}'s turn — tap a column"
    }

    // Which single cell the latest drop filled, for the fall animation. Derived from the board
    // itself (previous vs current cells) so the engine's state shape stays untouched.
    val dropTracker = remember { ConnectFourDropTracker() }
    val lastDropIndex = remember(s.cells) {
        val dropped = newlyFilledIndex(dropTracker.previous, s.cells)
        dropTracker.previous = s.cells
        dropped
    }

    // One effect per finished board (boardOver flips false -> true once per board; startMatch()/
    // playAgain() reset it before it can fire again). Only a HUMAN win gets the celebration: the
    // old version shook the board and played the success chime when the CPU won.
    LaunchedEffect(s.boardOver) {
        if (!s.boardOver) {
            // A new board began while the last win burst was still alive: this effect's cancelled
            // tick loop would otherwise leave those particles frozen on top of the fresh board.
            particleBurst.clear()
            return@LaunchedEffect
        }
        if (!humanWon) {
            haptics(HapticSignal.NORMAL_ACTION)
            return@LaunchedEffect
        }
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)

        val winningLine = s.winningLine
        val cellPx = (boardSizePx.width - 2f * framePaddingPx) / s.cols
        if (reducedMotion || winningLine == null || cellPx <= 0f) return@LaunchedEffect

        val discColor = if (winnerIndex == 1) palette.player1Disc else palette.player0Disc
        // A small burst from EVERY cell in the winning line, not just its midpoint.
        for (index in winningLine) {
            val row = index / s.cols
            val col = index % s.cols
            particleBurst.spawn(
                origin = Offset(
                    framePaddingPx + col * cellPx + cellPx / 2f,
                    framePaddingPx + row * cellPx + cellPx / 2f
                ),
                count = 10,
                colors = listOf(discColor),
                // ParticleBurst integrates pos += vel * dt, so these are PIXELS per second (the
                // origin above is in px). The library defaults (0.35..0.85) are normalized units
                // and would move a particle under 1px; scale by the cell instead, as in
                // ColorFloodScreen: 1.5-3.5 cells/s outward, 4 cells/s^2 of gravity.
                speedRange = (1.5f * cellPx)..(3.5f * cellPx),
                lifeRangeSeconds = 0.5f..0.8f,
                gravity = 4f * cellPx
            )
        }
        launch { cameraShake.trigger(durationMs = 400, easing = FastOutSlowInEasing) }
        // Drive the particles only while any are alive, then stop asking for frames.
        var lastNanos = 0L
        while (particleBurst.particles.value.isNotEmpty()) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) particleBurst.tick((nanos - lastNanos) / 1_000_000_000f)
                lastNanos = nanos
            }
        }
    }

    val finishedBoards = sessionWins.values.sum() + sessionDraws

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-board discards
    // ONLY the unfinished board: if earlier boards in this session were already won or drawn,
    // leaving goes through leaveSession() so those results still count; only a session with
    // nothing finished is a pure abort (never a win or loss). A finished board always leaves
    // through leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Connect Four",
        helpText = "Take turns dropping a disc into one of the seven columns. Tap anywhere in a " +
            "column: the disc falls to the lowest empty slot.\n\n" +
            "Connect four of your discs in a straight line, across, up and down, or diagonally, " +
            "to win the board. If all 42 slots fill up with no four in a row, it's a tie.\n\n" +
            "Play Again keeps the session tally, and the player who moves first alternates each board.",
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this board?",
        leaveBody = if (finishedBoards > 0) {
            "This board is still in progress and won't count, but the boards you've already finished stay on your record."
        } else {
            "This board is still in progress. Leaving now won't count it as a win or a loss."
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp)
        ) {
            // Board left + status/result right only when the window is genuinely wide; every
            // phone portrait, the Fold cover screen included, takes the stacked layout.
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f
            // Read here: inside the Column below, this scope's maxHeight is not implicitly reachable.
            val screenMaxHeight = maxHeight

            val statusRow: @Composable () -> Unit = {
                StatusRow(
                    s = s,
                    sessionWins = sessionWins,
                    statusText = statusText,
                    isMyTurn = isMyTurn,
                    palette = palette,
                    colorblind = colorblind
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                BoardSlot(
                    s = s,
                    isMyTurn = isMyTurn,
                    humanWon = humanWon,
                    lastDropIndex = lastDropIndex,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    cameraShake = cameraShake,
                    shakeMagnitudePx = shakeMagnitudePx,
                    particleBurst = particleBurst,
                    onBoardSized = { boardSizePx = it },
                    onDropInColumn = { col ->
                        // Re-read the live state: the enabled flag a column was composed with can
                        // be a frame stale, and dropDisc itself does not know whose turn it is.
                        val live = game.state.value
                        if (live != null && !live.boardOver && !live.players[live.currentPlayerIndex].isBot) {
                            game.dropDisc(col)
                            sounds.playTap()
                            haptics(HapticSignal.NORMAL_ACTION)
                        }
                    },
                    modifier = slotModifier
                )
            }
            val resultPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                ResultPanel(
                    s = s,
                    sessionWins = sessionWins,
                    sessionDraws = sessionDraws,
                    palette = palette,
                    onPlayAgain = { game.playAgain() },
                    onBackToMenu = { game.leaveSession() },
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        statusRow()
                        if (s.boardOver) resultPanel(Modifier.fillMaxWidth())
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    statusRow()
                    Spacer(Modifier.height(14.dp))
                    boardSlot(Modifier.weight(1f).fillMaxWidth())
                    if (s.boardOver) {
                        Spacer(Modifier.height(12.dp))
                        // Capped so even a large font scale cannot push the board out of the
                        // slot above; the panel scrolls inside its own cap instead.
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
}

// ---------------------------------------------------------------------------
// Drop tracking: which single cell did the latest move fill?
// ---------------------------------------------------------------------------

/** Holds the previously rendered board so [newlyFilledIndex] has something to diff against. */
private class ConnectFourDropTracker {
    var previous: List<Int?>? = null
}

/**
 * The one cell that is filled in [current] but was empty in [previous], or null when there is no
 * previous board (first render, a restored game), the sizes differ, nothing changed, or more than
 * one cell changed (a new board or a reset is not a drop and must not animate).
 */
private fun newlyFilledIndex(previous: List<Int?>?, current: List<Int?>): Int? {
    if (previous == null || previous.size != current.size) return null
    var found: Int? = null
    for (i in current.indices) {
        if (previous[i] == null && current[i] != null) {
            if (found != null) return null
            found = i
        }
    }
    return found
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why the board frame/discs are the
// one deliberate exception, staying the classic blue/red/yellow instead.
// ---------------------------------------------------------------------------
private data class ConnectFourPalette(
    val background: Color,
    val textPrimary: Color,
    val boardFrame: Color,
    val emptySlot: Color,
    val player0Disc: Color,
    val player1Disc: Color,
    /** Win ring when a human won. */
    val winningGlow: Color,
    /** Win ring when the CPU won: the winning four is still shown, just not celebrated. */
    val neutralRing: Color
)

@Composable
private fun connectFourPalette(isDark: Boolean): ConnectFourPalette = if (!isDark) {
    ConnectFourPalette(
        background = Color(0xFFFBF1E6),
        textPrimary = Color(0xFF3A2E22),
        boardFrame = Color(0xFF2E5FA3),
        emptySlot = Color(0xFFEFEAE1),
        player0Disc = Color(0xFFE23B3B), // classic red
        player1Disc = Color(0xFFF2C230), // classic yellow
        winningGlow = Color(0xFF3ED18C),
        neutralRing = Color(0xFFF5F5F5)
    )
} else {
    ConnectFourPalette(
        background = Color(0xFF1C1712),
        textPrimary = Color(0xFFF3E9DB),
        boardFrame = Color(0xFF3A6BB0),
        emptySlot = Color(0xFF241E17),
        player0Disc = Color(0xFFEB5757),
        player1Disc = Color(0xFFF5CE4E),
        winningGlow = Color(0xFF4CE0A0),
        neutralRing = Color(0xFFF5F5F5)
    )
}

// ---------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------

/** "You win!" / "CPU wins!" / "It's a tie!". The engine's own lastAction ("You connects four...")
 *  is not grammatical for the local player, so the screen words the result itself. */
private fun resultTitle(s: ConnectFourState): String {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId } ?: return "It's a tie!"
    return if (winner.displayName.equals("You", ignoreCase = true)) "You win!" else "${winner.displayName} wins!"
}

private fun slotsText(count: Int): String = if (count == 1) "1 free slot" else "$count free slots"

/** Screen-reader description of one column: its number, free slots, then each disc bottom-up with
 *  its colour and 1-indexed row (row 1 is the top row), and whether it is in the winning four. */
private fun columnDescription(s: ConnectFourState, col: Int): String {
    val discs = (s.rows - 1 downTo 0).mapNotNull { row ->
        val index = row * s.cols + col
        val owner = s.cells[index] ?: return@mapNotNull null
        val colour = if (owner == 0) "Red" else "Yellow"
        val inLine = if (s.winningLine?.contains(index) == true) ", in the winning four" else ""
        "$colour disc, row ${row + 1}$inLine"
    }
    val head = "Column ${col + 1}"
    val free = slotsText(s.rows - discs.size)
    return if (discs.isEmpty()) "$head, empty, $free" else "$head, $free. ${discs.joinToString("; ")}"
}

// ---------------------------------------------------------------------------
// Status row
// ---------------------------------------------------------------------------

@Composable
private fun StatusRow(
    s: ConnectFourState,
    sessionWins: Map<String, Int>,
    statusText: String,
    isMyTurn: Boolean,
    palette: ConnectFourPalette,
    colorblind: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            // End padding reserves room for the corner menu button, which is aligned to the whole
            // screen's top-end and would otherwise sit on top of (and clip) the chip this row
            // places at ITS end.
            modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerChip(
                playerIndex = 0,
                name = s.players[0].displayName,
                wins = sessionWins[s.players[0].playerId] ?: 0,
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                isMyTurn = isMyTurn,
                discColor = palette.player0Disc,
                palette = palette,
                colorblind = colorblind,
                modifier = Modifier.weight(1f, fill = false)
            )
            PlayerChip(
                playerIndex = 1,
                name = s.players[1].displayName,
                wins = sessionWins[s.players[1].playerId] ?: 0,
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                isMyTurn = isMyTurn,
                discColor = palette.player1Disc,
                palette = palette,
                colorblind = colorblind,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.height(6.dp))
        // The turn prompt / CPU-thinking line / result. A polite live region so a screen reader
        // announces the CPU's reply and the result without being asked.
        Text(
            statusText,
            color = palette.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (!s.boardOver) {
            Text(
                s.lastAction,
                color = palette.textPrimary.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PlayerChip(
    playerIndex: Int,
    name: String,
    wins: Int,
    isTurn: Boolean,
    isMyTurn: Boolean,
    discColor: Color,
    palette: ConnectFourPalette,
    colorblind: Boolean,
    modifier: Modifier = Modifier
) {
    val colour = if (playerIndex == 0) "red" else "yellow"
    val description = buildString {
        append(name)
        append(", $colour discs, ")
        append(if (wins == 1) "1 win" else "$wins wins")
        if (isTurn) append(if (isMyTurn) ", to move" else ", thinking")
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) discColor.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // One labelled node per chip: name, disc colour, wins, whose turn. The dot and the
            // digit below are visual only.
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(discColor)) {
            if (colorblind) DiscMark(owner = playerIndex, discColor = discColor, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            color = palette.textPrimary,
            fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Text(wins.toString(), color = palette.textPrimary.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

@Composable
private fun BoardSlot(
    s: ConnectFourState,
    isMyTurn: Boolean,
    humanWon: Boolean,
    lastDropIndex: Int?,
    palette: ConnectFourPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    cameraShake: CameraShake,
    shakeMagnitudePx: Float,
    particleBurst: ParticleBurst,
    onBoardSized: (IntSize) -> Unit,
    onDropInColumn: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    // Sized from THIS slot's own measured space (not an outer scope). fitBoard never returns a
    // footprint larger than the space it is given, so unlike the old `coerceAtLeast(24.dp)` floor
    // (which ignored the frame inset and squeezed the last column below 368dp windows) the board
    // cannot exceed its container.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value,
            availableHeightPx = maxHeight.value,
            columns = s.cols,
            rows = s.rows,
            framePx = BOARD_FRAME_PADDING.value,
            minCellPx = MIN_CELL_DP,
            maxCellPx = MAX_CELL_DP
        )
        // The slot cannot give a cell MIN_CELL_DP: keep the minimum and let the board scroll
        // rather than shrink further.
        val scrolls = !fit.meetsMinimum
        // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed
        // by one more: seven rounded-up cells plus the rounded frame would otherwise overshoot
        // the slot by a pixel or two and squeeze the last column.
        val cellSize: Dp = if (scrolls) {
            MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }

        Box(
            modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier
        ) {
            // The disc grid AND the win-burst overlay live inside this shaking Box, so the board
            // and the burst jolt together as one physical unit (a graphicsLayer translation never
            // propagates to a sibling composable).
            Box(modifier = Modifier.onSizeChanged(onBoardSized).cameraShake(cameraShake, magnitudePx = shakeMagnitudePx)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.boardFrame)
                        .padding(BOARD_FRAME_PADDING)
                ) {
                    for (col in 0 until s.cols) {
                        val columnFull = s.cells[col] != null // row 0 = the top row -- filled means no room left
                        val enabled = isMyTurn && !s.boardOver && !columnFull
                        val description = columnDescription(s, col)
                        Column(
                            modifier = Modifier
                                .clickable(
                                    enabled = enabled,
                                    onClickLabel = "Drop disc in column ${col + 1}",
                                    role = Role.Button
                                ) { onDropInColumn(col) }
                                .semantics { contentDescription = description }
                        ) {
                            for (row in 0 until s.rows) {
                                val index = row * s.cols + col
                                val ringColor = when {
                                    s.winningLine?.contains(index) != true -> null
                                    humanWon -> palette.winningGlow
                                    else -> palette.neutralRing
                                }
                                DiscSlotView(
                                    owner = s.cells[index],
                                    row = row,
                                    dropping = index == lastDropIndex,
                                    ringColor = ringColor,
                                    size = cellSize,
                                    palette = palette,
                                    colorblind = colorblind,
                                    reducedMotion = reducedMotion
                                )
                            }
                        }
                    }
                }

                // Win-burst motes, drawn last so they sit on top of the board/discs: a plain
                // matchParentSize() Canvas, since this board has no single Canvas of its own.
                val cellPx = with(density) { cellSize.toPx() }
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (particle in particleBurst.particles.value) {
                        drawCircle(
                            color = particle.color.copy(alpha = particle.lifeFraction),
                            radius = cellPx * 0.12f * particle.lifeFraction.coerceAtLeast(0.35f),
                            center = particle.pos
                        )
                    }
                }
            }
        }
    }
}

/**
 * One slot. The empty slot is always drawn; a disc, when there is one, is a second circle on top
 * of it. That layering is what lets a newly dropped disc fall from above the board (the board
 * Row's rounded clip hides it until it enters) and leave a visible hole behind. Under reduced
 * motion, or for any disc that was not just dropped, it simply sits in place.
 */
@Composable
private fun DiscSlotView(
    owner: Int?,
    row: Int,
    dropping: Boolean,
    ringColor: Color?,
    size: Dp,
    palette: ConnectFourPalette,
    colorblind: Boolean,
    reducedMotion: Boolean
) {
    // Scales with the cell (3dp at the old 40dp cell) so a big tablet disc is not jammed against
    // its neighbours.
    val inset = (size * 0.08f).coerceAtLeast(2.dp)
    Box(modifier = Modifier.size(size).padding(inset)) {
        Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(palette.emptySlot))
        if (owner != null) {
            val discColor = if (owner == 0) palette.player0Disc else palette.player1Disc
            val animate = dropping && !reducedMotion
            // 1f = the disc sits just above the board, 0f = landed. Created at 1f for a fresh
            // drop so its very first frame is already up top (no one-frame flash at the landing
            // spot); remembered per (owner, animate) so unrelated recompositions never restart it.
            val fall = remember(owner, animate) { Animatable(if (animate) 1f else 0f) }
            LaunchedEffect(owner, animate) {
                if (animate) {
                    // 190ms for a one-row drop up to a 350ms cap: an accelerating, gravity-like ease.
                    fall.animateTo(
                        0f,
                        animationSpec = tween(durationMillis = (140 + 50 * (row + 1)).coerceAtMost(350), easing = FastOutLinearInEasing)
                    )
                }
            }
            val density = LocalDensity.current
            // From just above the board's top edge (frame included) down to this slot.
            val fallDistancePx = with(density) { size.toPx() * (row + 1) + BOARD_FRAME_PADDING.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = -fall.value * fallDistancePx }
                    .clip(CircleShape)
                    .background(discColor)
                    .then(
                        if (ringColor != null) {
                            // Double ring: a coloured outer ring plus a dark inner one. The pair
                            // reads by brightness as well as hue (the old single green ring was
                            // 1.17:1 against a yellow disc).
                            Modifier
                                .border(3.dp, ringColor, CircleShape)
                                .padding(2.dp)
                                .border(2.dp, DISC_INK_DARK, CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (colorblind) DiscMark(owner = owner, discColor = discColor, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Colorblind-mode identity mark drawn on a disc: red (player 0) gets a ring, yellow (player 1) a
 * filled diamond, in whichever of white or dark ink contrasts better with the disc. Purely
 * decorative: the owning column / chip already carries the colour name for screen readers.
 */
@Composable
private fun DiscMark(owner: Int, discColor: Color, modifier: Modifier = Modifier) {
    val ink = if (discColor.luminance() > 0.3f) DISC_INK_DARK else Color.White
    Canvas(modifier = modifier) {
        val unit = size.minDimension
        val c = center
        if (owner == 0) {
            drawCircle(color = ink, radius = unit * 0.2f, center = c, style = Stroke(width = unit * 0.08f))
        } else {
            val r = unit * 0.24f
            val diamond = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r, c.y)
                lineTo(c.x, c.y + r)
                lineTo(c.x - r, c.y)
                close()
            }
            drawPath(path = diamond, color = ink)
        }
    }
}

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@Composable
private fun ResultPanel(
    s: ConnectFourState,
    sessionWins: Map<String, Int>,
    sessionDraws: Int,
    palette: ConnectFourPalette,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
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
            // The same words the status line already announces, so no live region here.
            Text(
                resultTitle(s),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            val totalBoards = sessionWins.values.sum() + sessionDraws
            if (totalBoards > 1) {
                Spacer(Modifier.height(4.dp))
                val winsText = s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" }
                Text(
                    if (sessionDraws > 0) "Session: $winsText · Draws $sessionDraws" else "Session: $winsText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlayAgain,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.boardFrame, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Play Again", textAlign = TextAlign.Center) }
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
