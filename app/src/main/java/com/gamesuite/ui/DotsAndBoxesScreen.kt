package com.gamesuite.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.gamesuite.games.dotsandboxes.DotsAndBoxesGame
import com.gamesuite.games.dotsandboxes.DotsAndBoxesState
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
import kotlin.math.floor

/** Below this cell size (dp) the board stops shrinking and scrolls instead. */
private const val MIN_CELL_DP = 28f

/** Upper bound on a cell (dp) so the 5x5 board does not sprawl on a tablet. */
private const val MAX_CELL_DP = 84f

/** The square (dp) every dot sits in, and so the thickness of every edge's tap strip. Dots, edge
 *  strips and boxes are all laid out on the same pitch (cell + slot), which is what keeps the
 *  lattice aligned. */
private const val EDGE_SLOT_DP = 18f

/** The same square on a roomy window, where a fatter tap strip costs nothing. */
private const val EDGE_SLOT_LARGE_DP = 26f

/** The board's smaller side must reach this for [EDGE_SLOT_LARGE_DP] to be used. */
private val LARGE_BOARD_BREAKPOINT = 440.dp

/** How long a freshly claimed box takes to fade its owner's colour in. */
private const val CLAIM_FILL_MS = 220

/** Opacity of a claimed box's owner tint at full reveal. */
private const val OWNER_FILL_ALPHA = 0.35f

/** How long the small per-claim shake nudge takes to settle -- short, since a chain can claim
 *  several boxes in one turn and a lingering shake would lag behind the moves that caused it. */
private const val BOX_SHAKE_DECAY_MS = 120

/** How long the whole-board win shake takes to settle: it fires once per board and should read
 *  as a full stop, not a tick. */
private const val WIN_SHAKE_DECAY_MS = 320

/** Dark ink for marks drawn on a light player colour. */
private val MARK_INK_DARK = Color(0xFF1A1208)

/**
 * Renders DotsAndBoxesGame's state reactively. Two-player: against the CPU the 800ms-delayed
 * `LaunchedEffect` + `game.playBotTurn()` idiom (same as DominoesScreen/ConnectFourScreen) drives
 * the bot, and in SINGLE_DEVICE_PASS_AND_PLAY neither player is a bot so that effect never fires.
 * One screen serves both modes. No daily-seed route (a solo-puzzle concept in this app).
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play) plus its BackHandler.
 * Leaving mid-board goes through `GameModule.abortMatch` (confirmation first, never recorded to
 * stats) when no board has been finished yet. If earlier boards in this session were already won,
 * leaving goes through `leaveSession()` instead so those results still count, and the confirm
 * dialog says so. A finished board always leaves through `leaveSession()`. The status row
 * reserves [GameChromeEndInset] at its end so the corner button never covers a score chip.
 *
 * BOARD GEOMETRY: dots, edge tap strips and boxes are laid out on ONE pitch. Every dot sits in an
 * `EDGE_SLOT_DP` square, every edge strip is `cell x slot`, every box is `cell x cell`, so the dot
 * rows and the vertical-edge/box rows line up exactly. (The previous layout mixed a 7dp dot slot
 * with a 14dp edge strip, so vertical lines and boxes drifted off the dots by 7dp per column.)
 * A drawn line runs dot-centre to dot-centre; an undrawn one is a short guide between the dots.
 *
 * SIZING: [fitBoard] against the measured space of the board's own slot (never an outer scope),
 * after subtracting the `slot x (n + 1)` the dots and strips take. Cells are clamped to
 * 28-84dp. If the slot cannot give a cell 28dp the board keeps 28dp cells and scrolls instead of
 * overflowing. Portrait stacks status / board / result; a wide landscape window puts the board on
 * the left and the status plus result panel in a column on the right, so the result panel never
 * shrinks the board. A tap strip is `cell x slot` (about 36 x 18dp on a Fold cover screen): below
 * the 48dp guideline, deliberately, because five boxes plus six dots must fit in 312dp.
 *
 * ACCESSIBILITY: every edge is a screen-reader node ("Horizontal line, row 2, column 3,
 * undrawn", 1-indexed, row/column numbered along the dot grid) and is a `Role.Button` while it
 * can be played; every box says who claimed it or how many of its four sides are drawn. Score
 * chips are one labelled node each and the status line is a polite live region. Undrawn guides
 * are at least 3:1 against the background. With `LocalColorblindMode` on, claimed boxes and the
 * chip dots carry a shape (ring for the first player, diamond for the second) on top of the tint.
 * The line(s) drawn by the latest move get an amber halo so the CPU's reply is easy to spot.
 *
 * MOTION: a claimed box fades its owner's tint in over 220ms, claims get a small particle burst
 * and shake nudge, and winning the board gets a larger burst and shake. All of it is off under
 * `LocalReducedMotion`. The particle frame loop runs only while particles are alive. Bursts are
 * located by diffing the board against the previously drawn one, so a CPU chain that claims
 * several boxes inside one recomposition (the engine plays the whole chain synchronously) still
 * bursts at every box it claimed rather than only the last. Cues are outcome-aware: a box claim
 * is a STRONG_ACTION haptic plus a thunk; the celebration (haptic, chime, shake, burst) is for a
 * HUMAN win only; a CPU win gets a failure buzz and a tie a plain haptic.
 *
 * VISUAL IDENTITY: shares its warm background/text tokens with MinesweeperScreen/SudokuScreen/
 * LightsOutScreen, extended with two per-player accents (terracotta and teal) because ownership
 * of each box is the point of the board. Never reads `MaterialTheme.colorScheme` for gameplay
 * colors beyond the light/dark probe, same standing rule as every other game's board.
 */
@Composable
fun DotsAndBoxesScreen(
    sessionManager: GameSessionManager,
    game: DotsAndBoxesGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOTS_AND_BOXES, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = dotsAndBoxesPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws

    // JUICE: two CameraShake instances (a per-claim nudge and the whole-board win shake are
    // different weights of the same gesture, and a shake's magnitude is fixed per
    // Modifier.cameraShake call site) plus one particle burst. The burst is built directly (not
    // via rememberParticleBurst) so its frame loop can stop when no particle is alive.
    val boxShake = rememberCameraShake()
    val winShake = rememberCameraShake()
    val particleBurst = remember { ParticleBurst() }
    val density = LocalDensity.current
    val boxShakeMagnitudePx = with(density) { 3.dp.toPx() }
    val winShakeMagnitudePx = with(density) { 12.dp.toPx() }

    // Drives the particles only while any are alive, then stops asking for frames. The derived
    // flag flips only when the list goes empty <-> non-empty, so this does not recompose per frame.
    val hasParticles by remember { derivedStateOf { particleBurst.particles.value.isNotEmpty() } }
    LaunchedEffect(hasParticles) {
        if (!hasParticles) return@LaunchedEffect
        var lastNanos = 0L
        while (particleBurst.particles.value.isNotEmpty()) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) particleBurst.tick((nanos - lastNanos) / 1_000_000_000f)
                lastNanos = nanos
            }
        }
    }

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

    // Same idiom as DominoesScreen's own bot-turn trigger -- a short delay so a bot's move doesn't
    // feel instantaneous, then the engine's own playBotTurn() plays however many chained extra
    // turns it needs. A plain tap sound only when the turn claimed nothing: a claim gets its own
    // (louder) cue from the claim effect below, and the CPU used to move in silence.
    LaunchedEffect(state?.currentPlayerIndex, state?.boardOver) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            val boxesBefore = s.scores.sum()
            game.playBotTurn()
            val after = game.state.value
            if (after != null && after.scores.sum() == boxesBefore) sounds.playTap()
        }
    }

    val s = state ?: return

    val current = s.players[s.currentPlayerIndex]
    val isMyTurn = !current.isBot
    val vsBot = s.players.any { it.isBot }
    val winnerIndex = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
    // Pass-and-play has two humans, so any win there is a human win; against the CPU only the
    // human's is. A tie has no winner and never celebrates (the engine's 25 boxes make one
    // impossible today, but the panel still renders it as a tie).
    val humanWon = s.boardOver && winnerIndex >= 0 && !s.players[winnerIndex].isBot
    val statusText = when {
        s.boardOver -> resultTitle(s)
        current.isBot -> "${current.displayName} is thinking…"
        vsBot -> "Your turn — tap a line"
        else -> "${current.displayName}'s turn — tap a line"
    }

    // CLAIM CUE: a box claim is this game's defining event (it grants another turn), so it gets
    // its own haptic and sound, human or CPU. Skipped on the board's final claim -- the finish
    // cue below owns that moment. The seq the screen first saw is remembered so a recreated
    // composition (rotation, fold) does not replay the cue for a claim that already happened.
    val seenClaimSeq = remember { longArrayOf(s.lastBoxCompleted?.seq ?: 0L) }
    LaunchedEffect(s.lastBoxCompleted?.seq) {
        val seq = s.lastBoxCompleted?.seq
        if (seq == null) {
            // A fresh board: its first claim restarts at seq 1, so forget the old board's seq.
            seenClaimSeq[0] = 0L
            return@LaunchedEffect
        }
        if (seq == seenClaimSeq[0]) return@LaunchedEffect
        seenClaimSeq[0] = seq
        if (s.boardOver) return@LaunchedEffect
        haptics(HapticSignal.STRONG_ACTION)
        playSfx(SfxKind.SOLID_THUNK)
    }

    // FINISH CUE, once per finished board. Celebration (haptic + chime) only for a HUMAN win; a
    // CPU win is a failure buzz; a tie a plain haptic. The old version celebrated whenever any
    // winner existed, including when the CPU won.
    val finishHandled = remember { booleanArrayOf(s.boardOver) }
    LaunchedEffect(s.boardOver) {
        if (!s.boardOver) {
            finishHandled[0] = false
            return@LaunchedEffect
        }
        if (finishHandled[0]) return@LaunchedEffect
        finishHandled[0] = true
        when {
            humanWon -> {
                haptics(HapticSignal.CELEBRATION)
                playSfx(SfxKind.SUCCESS_CHIME)
            }
            winnerIndex >= 0 -> {
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            }
            else -> haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // A fresh board carries no claim event yet (startMatch resets lastBoxCompleted to null), so
    // that is the moment to drop any motes still alive from the previous board's win burst.
    LaunchedEffect(s.lastBoxCompleted == null) {
        if (s.lastBoxCompleted == null) particleBurst.clear()
    }

    val finishedBoards = sessionWins.values.sum() + sessionDraws

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-board
    // discards ONLY the unfinished board: if earlier boards in this session were already won,
    // leaving goes through leaveSession() so those results still count; only a session with
    // nothing finished is a pure abort (never a win or loss). A finished board always leaves
    // through leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Dots and Boxes",
        helpText = "Take turns drawing one line between two neighboring dots. Draw the fourth " +
            "side of a box and you claim it, then you must draw again. One line can finish " +
            "two boxes at once.\n\n" +
            "When every box on the 5 by 5 board is claimed, whoever claimed more wins.\n\n" +
            "Play Again keeps the session tally, and the player who moves first alternates " +
            "each board.",
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
                    statusText = statusText,
                    isMyTurn = isMyTurn,
                    palette = palette,
                    colorblind = colorblind
                )
            }
            val boardSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                BoardSlot(
                    s = s,
                    interactive = isMyTurn && !s.boardOver,
                    humanWon = humanWon,
                    winnerIndex = winnerIndex,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    boxShake = boxShake,
                    winShake = winShake,
                    boxShakeMagnitudePx = boxShakeMagnitudePx,
                    winShakeMagnitudePx = winShakeMagnitudePx,
                    particleBurst = particleBurst,
                    onEdge = { horizontal, row, col ->
                        // Re-read the live state: the enabled flag an edge was composed with can
                        // be a frame stale, and drawEdge itself does not know whose turn it is.
                        val live = game.state.value
                        if (live != null && !live.boardOver && !live.players[live.currentPlayerIndex].isBot) {
                            val boxesBefore = live.scores.sum()
                            if (horizontal) game.drawHorizontalEdge(row, col) else game.drawVerticalEdge(row, col)
                            val after = game.state.value
                            // A claim is announced by the claim effect (STRONG_ACTION + thunk), so
                            // the plain tap cue is only for a line that claimed nothing.
                            if (after != null && after !== live && after.scores.sum() == boxesBefore) {
                                sounds.playTap()
                                haptics(HapticSignal.NORMAL_ACTION)
                            }
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
// Warm, Chogan-inspired palette -- shares its base tokens with
// MinesweeperPalette/SudokuPalette/LightsOutPalette (see this file's class
// KDoc for why this doesn't read MaterialTheme.colorScheme), extended with
// two distinct per-player accent colors this game needs for box ownership.
// ---------------------------------------------------------------------------
private data class DotsAndBoxesPalette(
    val background: Color,
    val dot: Color,
    /** Undrawn guide line: at least 3:1 against [background] (it was 1.4:1 / 1.6:1). */
    val edgeHint: Color,
    val edgeDrawn: Color,
    /** Halo behind the line(s) the latest move drew. */
    val latest: Color,
    val player0: Color,
    val player1: Color,
    val textPrimary: Color
)

@Composable
private fun dotsAndBoxesPalette(isDark: Boolean): DotsAndBoxesPalette = if (!isDark) {
    DotsAndBoxesPalette(
        background = Color(0xFFFBF1E6),
        dot = Color(0xFF3A2E22),
        edgeHint = Color(0xFF9A8264),
        edgeDrawn = Color(0xFF3A2E22),
        latest = Color(0xFFF2B84B).copy(alpha = 0.65f),
        player0 = Color(0xFFE08D4B), // warm terracotta -- the shared "new games" accent
        player1 = Color(0xFF3E7A8C), // a cool contrasting teal-blue, deliberately outside the warm family for at-a-glance ownership contrast
        textPrimary = Color(0xFF3A2E22)
    )
} else {
    DotsAndBoxesPalette(
        background = Color(0xFF1C1712),
        dot = Color(0xFFF3E9DB),
        edgeHint = Color(0xFF7D6C58),
        edgeDrawn = Color(0xFFF3E9DB),
        latest = Color(0xFFF2B84B).copy(alpha = 0.5f),
        player0 = Color(0xFFE8985B),
        player1 = Color(0xFF6FB4C9),
        textPrimary = Color(0xFFF3E9DB)
    )
}

// ---------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------

/** "You win!" / "CPU wins!" / "It's a tie!". The engine's displayName for the local player is
 *  "You", and "You wins!" is not grammatical, so the screen words the result itself. */
private fun resultTitle(s: DotsAndBoxesState): String {
    val winner = s.players.firstOrNull { it.playerId == s.winnerPlayerId } ?: return "It's a tie!"
    return if (winner.displayName.equals("You", ignoreCase = true)) "You win!" else "${winner.displayName} wins!"
}

/** Screen-reader description of one edge. Horizontal lines are numbered along the dot grid
 *  (row 1 is the top row of dots), vertical lines by box row (row 1 is the top row of boxes). */
private fun edgeDescription(horizontal: Boolean, row: Int, col: Int, drawn: Boolean, latest: Boolean): String {
    val kind = if (horizontal) "Horizontal line" else "Vertical line"
    val state = when {
        !drawn -> "undrawn"
        latest -> "drawn, just now"
        else -> "drawn"
    }
    return "$kind, row ${row + 1}, column ${col + 1}, $state"
}

/** How many of box (row, col)'s four sides are drawn; mirrors the engine's own edge indexing. */
private fun sidesDrawn(s: DotsAndBoxesState, row: Int, col: Int): Int {
    var count = 0
    if (s.horizontalEdges[row * s.boxCols + col]) count++
    if (s.horizontalEdges[(row + 1) * s.boxCols + col]) count++
    if (s.verticalEdges[row * (s.boxCols + 1) + col]) count++
    if (s.verticalEdges[row * (s.boxCols + 1) + col + 1]) count++
    return count
}

/** Screen-reader description of one box: its owner, or how close it is to being claimed. */
private fun boxDescription(s: DotsAndBoxesState, row: Int, col: Int): String {
    val head = "Box, row ${row + 1}, column ${col + 1}"
    val owner = s.boxOwner[row * s.boxCols + col]
    return if (owner != null) {
        "$head, claimed by ${s.players[owner].displayName}"
    } else {
        "$head, empty, ${sidesDrawn(s, row, col)} of 4 sides drawn"
    }
}

// ---------------------------------------------------------------------------
// Change tracking: what did the last move draw and claim?
// ---------------------------------------------------------------------------

/** What changed between two successive boards: the edge indices newly drawn and the box indices
 *  newly claimed. Derived from the boards themselves (engine state shape untouched), so a CPU
 *  chain that lands several moves in one recomposition still reports every one of them. */
private data class DotsBoardChanges(
    val horizontal: Set<Int>,
    val vertical: Set<Int>,
    val boxes: List<Int>
)

private val DotsNoChanges = DotsBoardChanges(emptySet(), emptySet(), emptyList())

/** Holds the previously rendered board so [diffDotsBoards] has something to diff against. */
private class DotsBoardTracker {
    var previous: DotsAndBoxesState? = null
}

/**
 * The edges newly drawn and boxes newly claimed in [current] relative to [previous]. Empty when
 * there is no previous board (first render, a restored game), the shapes differ, or an edge went
 * from drawn to undrawn (a new board, which is not a move and must not highlight or burst).
 */
private fun diffDotsBoards(previous: DotsAndBoxesState?, current: DotsAndBoxesState): DotsBoardChanges {
    if (previous == null ||
        previous.horizontalEdges.size != current.horizontalEdges.size ||
        previous.verticalEdges.size != current.verticalEdges.size ||
        previous.boxOwner.size != current.boxOwner.size
    ) return DotsNoChanges
    val newH = current.horizontalEdges.indices.filter { current.horizontalEdges[it] && !previous.horizontalEdges[it] }
    val newV = current.verticalEdges.indices.filter { current.verticalEdges[it] && !previous.verticalEdges[it] }
    val reset = current.horizontalEdges.indices.any { previous.horizontalEdges[it] && !current.horizontalEdges[it] } ||
        current.verticalEdges.indices.any { previous.verticalEdges[it] && !current.verticalEdges[it] }
    if (reset) return DotsNoChanges
    val newBoxes = current.boxOwner.indices.filter { current.boxOwner[it] != null && previous.boxOwner[it] == null }
    return DotsBoardChanges(newH.toSet(), newV.toSet(), newBoxes)
}

// ---------------------------------------------------------------------------
// Status row
// ---------------------------------------------------------------------------

@Composable
private fun StatusRow(
    s: DotsAndBoxesState,
    statusText: String,
    isMyTurn: Boolean,
    palette: DotsAndBoxesPalette,
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
            PlayerScoreChip(
                playerIndex = 0,
                name = s.players[0].displayName,
                score = s.scores[0],
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                isMyTurn = isMyTurn,
                color = palette.player0,
                palette = palette,
                colorblind = colorblind,
                modifier = Modifier.weight(1f, fill = false)
            )
            PlayerScoreChip(
                playerIndex = 1,
                name = s.players[1].displayName,
                score = s.scores[1],
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                isMyTurn = isMyTurn,
                color = palette.player1,
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
                color = palette.textPrimary.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PlayerScoreChip(
    playerIndex: Int,
    name: String,
    score: Int,
    isTurn: Boolean,
    isMyTurn: Boolean,
    color: Color,
    palette: DotsAndBoxesPalette,
    colorblind: Boolean,
    modifier: Modifier = Modifier
) {
    val description = buildString {
        append(name)
        append(if (score == 1) ", 1 box" else ", $score boxes")
        if (isTurn) append(if (isMyTurn) ", to move" else ", thinking")
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) color.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // One labelled node per chip: name, boxes, whose turn. The dot and the digit below
            // are visual only.
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(color)) {
            if (colorblind) {
                val ink = markInk(color)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawOwnerMark(playerIndex, ink, size.minDimension * 0.6f, center)
                }
            }
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
        Text(score.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The board's own slot. Sized from THIS slot's measured space (not an outer scope): [fitBoard]
 * never returns a footprint larger than the space it is given, so the old
 * `coerceAtLeast(22.dp)` floor, which also ignored the dots' own 84dp of overhead, is gone.
 */
@Composable
private fun BoardSlot(
    s: DotsAndBoxesState,
    interactive: Boolean,
    humanWon: Boolean,
    winnerIndex: Int,
    palette: DotsAndBoxesPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    boxShake: CameraShake,
    winShake: CameraShake,
    boxShakeMagnitudePx: Float,
    winShakeMagnitudePx: Float,
    particleBurst: ParticleBurst,
    onEdge: (horizontal: Boolean, row: Int, col: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    // What the latest move drew and claimed, diffed against the previously composed board. The
    // remember keys are the board's own lists, so this runs once per real move, not per frame.
    val tracker = remember { DotsBoardTracker() }
    val changes = remember(s.horizontalEdges, s.verticalEdges, s.boxOwner) {
        val diff = diffDotsBoards(tracker.previous, s)
        tracker.previous = s
        diff
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val cols = s.boxCols
        val rows = s.boxRows
        // The dot square, floored to a whole pixel so n slots never round up past the slot.
        val slotBase = if (minOf(maxWidth, maxHeight) >= LARGE_BOARD_BREAKPOINT) EDGE_SLOT_LARGE_DP else EDGE_SLOT_DP
        val slotDp: Dp = with(density) { floor(slotBase * density.density).coerceAtLeast(1f).toDp() }
        val fit = fitBoard(
            availableWidthPx = maxWidth.value - (cols + 1) * slotDp.value,
            availableHeightPx = maxHeight.value - (rows + 1) * slotDp.value,
            columns = cols,
            rows = rows,
            minCellPx = MIN_CELL_DP,
            maxCellPx = MAX_CELL_DP
        )
        // The slot cannot give a cell MIN_CELL_DP: keep the minimum and let the board scroll
        // rather than shrink further.
        val scrolls = !fit.meetsMinimum
        // Each cell is laid out in whole pixels, so the dp size is floored to a pixel and trimmed
        // by one more: five rounded-up cells would otherwise overshoot the slot by a pixel or two.
        val cellDp: Dp = if (scrolls) {
            MIN_CELL_DP.dp
        } else {
            with(density) { (floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f).toDp() }
        }
        val cellPx = with(density) { cellDp.toPx() }
        val slotPx = with(density) { slotDp.toPx() }

        // One small burst on every box this move claimed, in the claimer's colour. Positions come
        // from the lattice geometry (a box's centre is one slot plus its own cell-and-slot pitch
        // in from the board's corner), the same maths the layout below uses.
        LaunchedEffect(changes) {
            if (changes.boxes.isEmpty() || reducedMotion) return@LaunchedEffect
            for (boxIndex in changes.boxes) {
                val owner = s.boxOwner[boxIndex] ?: continue
                val boxRow = boxIndex / cols
                val boxCol = boxIndex % cols
                particleBurst.spawn(
                    origin = Offset(
                        slotPx + boxCol * (cellPx + slotPx) + cellPx / 2f,
                        slotPx + boxRow * (cellPx + slotPx) + cellPx / 2f
                    ),
                    count = 10,
                    colors = listOf(if (owner == 1) palette.player1 else palette.player0),
                    // ParticleBurst integrates pos += vel * dt, so these are PIXELS per second:
                    // scale by the cell (a claim should travel about one cell).
                    speedRange = (1.2f * cellPx)..(2.4f * cellPx),
                    lifeRangeSeconds = 0.35f..0.55f,
                    gravity = 3.5f * cellPx
                )
            }
            boxShake.trigger(durationMs = BOX_SHAKE_DECAY_MS, easing = FastOutSlowInEasing)
        }

        // The board's biggest moment, for a HUMAN win only.
        LaunchedEffect(s.boardOver) {
            if (!s.boardOver || !humanWon || reducedMotion) return@LaunchedEffect
            val boardWidthPx = cols * cellPx + (cols + 1) * slotPx
            val boardHeightPx = rows * cellPx + (rows + 1) * slotPx
            particleBurst.spawn(
                origin = Offset(boardWidthPx / 2f, boardHeightPx / 2f),
                count = 28,
                colors = listOf(if (winnerIndex == 1) palette.player1 else palette.player0, palette.dot),
                speedRange = (2f * cellPx)..(4.5f * cellPx),
                lifeRangeSeconds = 0.6f..0.95f,
                gravity = 5f * cellPx
            )
            winShake.trigger(durationMs = WIN_SHAKE_DECAY_MS, easing = FastOutSlowInEasing)
        }

        Box(
            modifier = if (scrolls) Modifier.horizontalScroll(hScroll).verticalScroll(vScroll) else Modifier
        ) {
            // The lattice AND the particle overlay live inside this shaking Box, so a shake moves
            // the whole board and its particles as one rigid unit. Two chained cameraShake calls
            // (a claim nudge and the win shake) since each has its own fixed magnitude.
            Box(
                modifier = Modifier
                    .cameraShake(boxShake, magnitudePx = boxShakeMagnitudePx)
                    .cameraShake(winShake, magnitudePx = winShakeMagnitudePx)
            ) {
                Column {
                    for (boxRow in 0..rows) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (boxCol in 0..cols) {
                                DotView(slot = slotDp, palette = palette)
                                if (boxCol < cols) {
                                    val edgeIndex = boxRow * cols + boxCol
                                    EdgeView(
                                        horizontal = true,
                                        row = boxRow,
                                        col = boxCol,
                                        drawn = s.horizontalEdges[edgeIndex],
                                        latest = edgeIndex in changes.horizontal,
                                        cell = cellDp,
                                        slot = slotDp,
                                        palette = palette,
                                        enabled = interactive,
                                        onTap = { onEdge(true, boxRow, boxCol) }
                                    )
                                }
                            }
                        }
                        if (boxRow < rows) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (boxCol in 0..cols) {
                                    val edgeIndex = boxRow * (cols + 1) + boxCol
                                    EdgeView(
                                        horizontal = false,
                                        row = boxRow,
                                        col = boxCol,
                                        drawn = s.verticalEdges[edgeIndex],
                                        latest = edgeIndex in changes.vertical,
                                        cell = cellDp,
                                        slot = slotDp,
                                        palette = palette,
                                        enabled = interactive,
                                        onTap = { onEdge(false, boxRow, boxCol) }
                                    )
                                    if (boxCol < cols) {
                                        BoxCellView(
                                            owner = s.boxOwner[boxRow * cols + boxCol],
                                            description = boxDescription(s, boxRow, boxCol),
                                            cell = cellDp,
                                            inset = slotDp * 0.24f,
                                            palette = palette,
                                            colorblind = colorblind,
                                            reducedMotion = reducedMotion
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Particle motes, drawn last so they sit on top of the board: a plain
                // matchParentSize() Canvas, since the lattice is made of composables.
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (particle in particleBurst.particles.value) {
                        drawCircle(
                            color = particle.color.copy(alpha = particle.lifeFraction),
                            radius = cellPx * 0.08f * particle.lifeFraction.coerceAtLeast(0.4f),
                            center = particle.pos
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DotView(slot: Dp, palette: DotsAndBoxesPalette) {
    // Decorative: a drawn circle in the middle of its slot, no semantics of its own.
    Box(
        modifier = Modifier
            .size(slot)
            .drawBehind { drawCircle(color = palette.dot, radius = size.minDimension * 0.25f) }
    )
}

/**
 * One edge: a `cell x slot` strip whose drawn line is much thinner than the strip itself (the
 * strip is the tap target, the line is the picture). While it can be played it is a button; once
 * drawn, or while it is the CPU's turn, it is just a described node. The latest move's lines get
 * a halo so the CPU's reply is easy to find.
 */
@Composable
private fun EdgeView(
    horizontal: Boolean,
    row: Int,
    col: Int,
    drawn: Boolean,
    latest: Boolean,
    cell: Dp,
    slot: Dp,
    palette: DotsAndBoxesPalette,
    enabled: Boolean,
    onTap: () -> Unit
) {
    // Same phrasing convention as ReversiScreen.CellView / CheckersScreen.squareDescription:
    // what it is, row, column (1-indexed), then its state.
    val description = remember(horizontal, row, col, drawn, latest) {
        edgeDescription(horizontal, row, col, drawn, latest)
    }
    val strip = if (horizontal) Modifier.size(width = cell, height = slot) else Modifier.size(width = slot, height = cell)
    val lineColor = if (drawn) palette.edgeDrawn else palette.edgeHint
    val haloColor = palette.latest
    Box(
        modifier = strip
            .drawBehind { drawEdgeLine(horizontal, drawn, latest, lineColor, haloColor) }
            .then(
                if (enabled && !drawn) {
                    Modifier.clickable(onClickLabel = "Draw line", role = Role.Button, onClick = onTap)
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description }
    )
}

/** Draws one edge inside its own `cell x slot` strip (the strip's short side is the slot). A drawn
 *  line is thick and runs dot-centre to dot-centre, overlapping half a slot into each neighbouring
 *  dot; an undrawn guide is thin and stops at the strip so it never paints over a dot. */
private fun DrawScope.drawEdgeLine(horizontal: Boolean, drawn: Boolean, latest: Boolean, lineColor: Color, haloColor: Color) {
    val slotPx = if (horizontal) size.height else size.width
    if (latest) drawEdgeBar(horizontal, thickness = slotPx * 0.8f, reach = slotPx / 2f, color = haloColor)
    drawEdgeBar(
        horizontal,
        thickness = slotPx * (if (drawn) 0.33f else 0.16f),
        reach = if (drawn) slotPx / 2f else 0f,
        color = lineColor
    )
}

/** A rounded bar centred across the strip, [reach] px longer than the strip at each end. */
private fun DrawScope.drawEdgeBar(horizontal: Boolean, thickness: Float, reach: Float, color: Color) {
    val radius = CornerRadius(thickness / 2f, thickness / 2f)
    if (horizontal) {
        drawRoundRect(
            color = color,
            topLeft = Offset(-reach, (size.height - thickness) / 2f),
            size = Size(size.width + 2f * reach, thickness),
            cornerRadius = radius
        )
    } else {
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - thickness) / 2f, -reach),
            size = Size(thickness, size.height + 2f * reach),
            cornerRadius = radius
        )
    }
}

/**
 * One box. The owner's tint fades in over [CLAIM_FILL_MS] when it is claimed (snaps under reduced
 * motion, and snaps back when a new board clears it). The tint is inset by [inset] so it never
 * paints over the half-thickness of the lines around it. With colorblind mode on, a shape (ring
 * for player 0, diamond for player 1) says who owns it without relying on the tint.
 */
@Composable
private fun BoxCellView(
    owner: Int?,
    description: String,
    cell: Dp,
    inset: Dp,
    palette: DotsAndBoxesPalette,
    colorblind: Boolean,
    reducedMotion: Boolean
) {
    val fill by animateFloatAsState(
        targetValue = if (owner != null) 1f else 0f,
        animationSpec = if (owner != null && !reducedMotion) tween<Float>(CLAIM_FILL_MS) else snap<Float>(),
        label = "dots-box-fill"
    )
    val ownerColor = if (owner == 1) palette.player1 else palette.player0
    val markColor = palette.textPrimary
    Box(
        modifier = Modifier
            .size(cell)
            .drawBehind {
                if (owner != null && fill > 0f) {
                    val insetPx = inset.toPx()
                    drawRoundRect(
                        color = ownerColor.copy(alpha = OWNER_FILL_ALPHA * fill),
                        topLeft = Offset(insetPx, insetPx),
                        size = Size(size.width - 2f * insetPx, size.height - 2f * insetPx),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    if (colorblind) {
                        drawOwnerMark(owner, markColor.copy(alpha = 0.8f * fill), size.minDimension * 0.4f, center)
                    }
                }
            }
            .semantics { contentDescription = description }
    )
}

/** Colorblind-mode identity mark: a ring for player 0, a filled diamond for player 1. */
private fun DrawScope.drawOwnerMark(owner: Int, color: Color, markSize: Float, center: Offset) {
    if (owner == 0) {
        drawCircle(color = color, radius = markSize * 0.5f, center = center, style = Stroke(width = markSize * 0.18f))
    } else {
        val r = markSize * 0.55f
        val diamond = Path().apply {
            moveTo(center.x, center.y - r)
            lineTo(center.x + r, center.y)
            lineTo(center.x, center.y + r)
            lineTo(center.x - r, center.y)
            close()
        }
        drawPath(path = diamond, color = color)
    }
}

/** Whichever of dark ink or white contrasts better with [color] (a solid player-colour dot). */
private fun markInk(color: Color): Color = if (color.luminance() > 0.3f) MARK_INK_DARK else Color.White

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@Composable
private fun ResultPanel(
    s: DotsAndBoxesState,
    sessionWins: Map<String, Int>,
    sessionDraws: Int,
    palette: DotsAndBoxesPalette,
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
            Spacer(Modifier.height(4.dp))
            Text(
                "${s.players[0].displayName} ${s.scores[0]} — ${s.scores[1]} ${s.players[1].displayName}",
                style = MaterialTheme.typography.bodyMedium,
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Play Again", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Back to Menu", textAlign = TextAlign.Center) }
            }
        }
    }
}
