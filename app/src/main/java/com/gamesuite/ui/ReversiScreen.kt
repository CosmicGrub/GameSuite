package com.gamesuite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.reversi.ReversiGame
import com.gamesuite.games.reversi.ReversiState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay

/**
 * Renders ReversiGame's state reactively — same overall shape as ConnectFourScreen (the other
 * simple two-player board game in this batch): a session-init LaunchedEffect, an 800ms-delayed
 * bot-turn LaunchedEffect, a plain-Column layout, a FinishedPanel with Play Again/Back to Menu.
 *
 * BOARD SIZING: [com.gamesuite.uikit.fitBoard] against this BoxWithConstraints's own measured
 * space, clamped to a [40, 56]dp cell-size hint. Chess/Checkers/Mancala/Connect Four all had a
 * confirmed bug from this exact screen's own prior formula — `minOf(maxWidth, maxHeight) /
 * 8).coerceIn(40.dp, 56.dp)` — where the floor could push the board past its container on the
 * Fold cover screen (312dp pane / 8 = 39dp, coerced up to 40dp = 320dp board, an 8dp overflow).
 * fitBoard never returns a footprint larger than the space it was given — see its own KDoc.
 *
 * LEGAL-MOVE HIGHLIGHTING: every cell in [ReversiState.legalMoves] gets a translucent tint plus a
 * small center dot while it's the LOCAL human's turn — real Othello UIs conventionally show this,
 * and it's the one piece of UI feedback this genre genuinely needs (unlike Connect Four/Checkers,
 * where "can I play here" is usually obvious at a glance). Colorblind-safe as-is: the dot carries
 * the "legal" signal by shape, not hue alone, and the two disc colors are near-black/near-white
 * (a luminance distinction every colorblindness type preserves), so no LocalColorblindMode branch
 * was needed here the way ColorFlood/Mastermind's multi-hue boards need one.
 *
 * THE PASS MOMENT: [ReversiState.justPassed] renders a dedicated, clearly-visible banner (not just
 * folded into the generic status line) — Othello's forced-pass rule is a common new-player
 * confusion point, so it's surfaced as its own moment rather than a silent turn hand-back.
 *
 * CHROME: the shared [GameChrome] corner menu (Back to Menu / How to Play) plus its BackHandler —
 * this game previously had neither, the #1 gap shared by every screen in the app relative to the
 * UNO/Air Hockey quality bar. Leaving mid-board goes through GameModule.abortMatch (a
 * confirmation dialog first, never recorded to stats) rather than the board-over panel's own
 * "Back to Menu", which scores the finished session — see that method's KDoc for why they're two
 * different actions. Every board cell also carries a screen-reader description (row/column/owner,
 * matching CheckersScreen's own phrasing) and a captured disc cross-fades to its new color instead
 * of snapping — see [CellView]'s KDoc.
 *
 * VISUAL IDENTITY: chrome (background/text/buttons) shares this batch's own warm tokens, but the
 * BOARD ITSELF — green felt, not a checkerboard — is the one deliberate exception, same reasoning
 * ConnectFourScreen's own KDoc gives for keeping its blue frame/red-yellow discs: a green Othello
 * board is a real, recognizable signature of this exact game. Discs are colored by PLAYER INDEX
 * (0 = dark, 1 = light) rather than any engine-level "black"/"white" concept — see ReversiGame's
 * own KDoc for why the engine itself stays color-agnostic.
 */
@Composable
fun ReversiScreen(
    sessionManager: GameSessionManager,
    game: ReversiGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = reversiPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

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

    // Same idiom as ConnectFourScreen's own bot-turn trigger -- a short delay so a bot's move
    // doesn't feel instantaneous. A single playBotTurn() call always either ends the board, passes
    // the turn to the human, or (if the bot itself is immediately forced to pass again) hands the
    // turn straight back to the bot -- this effect re-fires on that state change too since it's
    // keyed on currentPlayerIndex/justPassed, so a bot-passes-back-to-bot chain still resolves
    // without the human needing to do anything.
    LaunchedEffect(state?.currentPlayerIndex, state?.boardOver, state?.justPassed) {
        val s = state ?: return@LaunchedEffect
        if (s.boardOver) return@LaunchedEffect
        if (s.players.getOrNull(s.currentPlayerIndex)?.isBot == true) {
            delay(800)
            game.playBotTurn()
        }
    }

    val s = state ?: return

    LaunchedEffect(s.boardOver) {
        if (s.boardOver) haptics(if (s.winnerPlayerId != null) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
    }
    LaunchedEffect(s.justPassed) {
        if (s.justPassed) haptics(HapticSignal.NORMAL_ACTION)
    }

    val isMyTurn = !s.players[s.currentPlayerIndex].isBot
    val matchInProgress = !s.boardOver
    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value

    // Back / abort-confirm / How to Play live in the shared GameChrome now -- this screen's
    // corner menu was the pilot it was extracted from. Leaving mid-board discards ONLY the
    // unfinished board: if earlier boards in this session were already won or drawn, leaving
    // goes through leaveSession() so those results still count; only a session with nothing
    // finished is a pure abort (never a win or loss). A finished board always leaves through
    // leaveSession(), which scores the session.
    GameChrome(
        helpTitle = "How to Play Reversi",
        helpText = "Place a disc so it traps one or more of your opponent's discs between your " +
            "new disc and another disc of your own color, in any straight line. Every " +
            "trapped disc flips to your color.\n\n" +
            "If you have no legal move, your turn is skipped. The board ends once " +
            "neither player can move — whoever has more discs wins.",
        matchInProgress = matchInProgress,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusRow(s, game, palette)

            Spacer(Modifier.height(10.dp))

            if (s.justPassed) {
                PassBanner(text = s.lastAction, palette = palette)
                Spacer(Modifier.height(10.dp))
            }

            BoxWithConstraints(modifier = Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
                // fitBoard never returns a footprint larger than this BoxWithConstraints's own
                // measured space -- see its KDoc for the exact Chess/Checkers/Mancala bug class
                // this replaces (a touch-target floor that could exceed its container).
                val fit = remember(maxWidth, maxHeight) {
                    fitBoard(
                        availableWidthPx = maxWidth.value,
                        availableHeightPx = maxHeight.value,
                        columns = 8,
                        rows = 8,
                        minCellPx = 40f,
                        maxCellPx = 56f
                    )
                }
                val squareSize = fit.cellPx.dp

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.boardFelt)
                        .border(3.dp, palette.boardFrame, RoundedCornerShape(6.dp))
                ) {
                    for (row in 0 until 8) {
                        Row {
                            for (col in 0 until 8) {
                                val index = row * 8 + col
                                val isLegal = isMyTurn && !s.boardOver && index in s.legalMoves
                                CellView(
                                    owner = s.cells[index],
                                    row = row,
                                    col = col,
                                    isLegal = isLegal,
                                    size = squareSize,
                                    palette = palette,
                                    onTap = {
                                        game.placeDisc(index)
                                        sounds.playTap()
                                        haptics(HapticSignal.NORMAL_ACTION)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (s.boardOver) {
                Spacer(Modifier.height(16.dp))
                FinishedPanel(s = s, game = game, palette = palette)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- see this file's own KDoc for why the board itself (green felt,
// dark/light discs) is the one deliberate exception.
// ---------------------------------------------------------------------------
private data class ReversiPalette(
    val background: Color,
    val textPrimary: Color,
    val boardFelt: Color,
    val boardFrame: Color,
    val gridLine: Color,
    val legalTint: Color,
    val legalDot: Color,
    val player0Disc: Color,
    val player1Disc: Color,
    val discBorder: Color,
    val passBannerBg: Color
)

@Composable
private fun reversiPalette(isDark: Boolean): ReversiPalette = if (!isDark) {
    ReversiPalette(
        background = Color(0xFFFBF1E6),
        textPrimary = Color(0xFF3A2E22),
        boardFelt = Color(0xFF1F7A4D),
        boardFrame = Color(0xFF3E2A18),
        gridLine = Color(0xFF17603C),
        legalTint = Color(0xFFF2C230).copy(alpha = 0.35f),
        legalDot = Color(0xFFF2C230),
        player0Disc = Color(0xFF232323), // dark disc -- player index 0
        player1Disc = Color(0xFFF4EEE2), // light disc -- player index 1
        discBorder = Color(0xFF00000066),
        passBannerBg = Color(0xFFE8B33B)
    )
} else {
    ReversiPalette(
        background = Color(0xFF1C1712),
        textPrimary = Color(0xFFF3E9DB),
        boardFelt = Color(0xFF17552F),
        boardFrame = Color(0xFF2A1D11),
        gridLine = Color(0xFF104426),
        legalTint = Color(0xFFF5CE4E).copy(alpha = 0.30f),
        legalDot = Color(0xFFF5CE4E),
        player0Disc = Color(0xFF121212),
        player1Disc = Color(0xFFEDE6D8),
        discBorder = Color(0x66000000),
        passBannerBg = Color(0xFF8A5F19)
    )
}

@Composable
private fun StatusRow(s: ReversiState, game: ReversiGame, palette: ReversiPalette) {
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            // End padding reserves room for the corner menu button, which is aligned to the
            // whole screen's top-end and would otherwise sit on top of (and clip) the CPU chip
            // this row places at ITS end -- found on-device, not visible from source alone.
            modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerChip(
                name = s.players[0].displayName,
                liveScore = s.scores.getOrElse(0) { 0 },
                isTurn = s.currentPlayerIndex == 0 && !s.boardOver,
                discColor = palette.player0Disc,
                palette = palette
            )
            PlayerChip(
                name = s.players[1].displayName,
                liveScore = s.scores.getOrElse(1) { 0 },
                isTurn = s.currentPlayerIndex == 1 && !s.boardOver,
                discColor = palette.player1Disc,
                palette = palette
            )
        }
        Spacer(Modifier.height(4.dp))
        val sessionText = buildString {
            append("Session: ")
            append(s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" })
            if (sessionDraws > 0) append(" · Draws $sessionDraws")
        }
        Text(sessionText, color = palette.textPrimary.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(s.lastAction, color = palette.textPrimary.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerChip(name: String, liveScore: Int, isTurn: Boolean, discColor: Color, palette: ReversiPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isTurn) discColor.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(discColor)
                .border(1.dp, palette.discBorder, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(name, color = palette.textPrimary, fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(6.dp))
        Text(
            liveScore.toString(),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PassBanner(text: String, palette: ReversiPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.passBannerBg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFF241A08), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CellView(
    owner: Int?,
    row: Int,
    col: Int,
    isLegal: Boolean,
    size: androidx.compose.ui.unit.Dp,
    palette: ReversiPalette,
    onTap: () -> Unit
) {
    // Mirrors CheckersScreen's own squareDescription convention exactly (same owner/row/column
    // phrasing, 1-indexed for a human reader) rather than inventing a new one.
    val description = remember(owner, isLegal, row, col) {
        val base = when {
            owner == 0 -> "Dark disc"
            owner == 1 -> "Light disc"
            isLegal -> "Legal move"
            else -> "Empty"
        }
        "$base, row ${row + 1}, column ${col + 1}"
    }

    Box(
        modifier = Modifier
            .size(size)
            .border(0.5.dp, palette.gridLine)
            .background(if (isLegal) palette.legalTint else Color.Transparent)
            .clickable(enabled = isLegal, onClickLabel = "Place disc", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        when {
            owner != null -> {
                // Cross-fades whenever an existing disc's owner changes -- a real capture-flip.
                // This Box's composable identity stays stable across that transition (the branch
                // taken here is "owner != null" both before and after a flip), which is exactly
                // what lets animateColorAsState interpolate between the two colors instead of
                // snapping. The one moment this doesn't animate -- a disc appearing on a
                // previously-empty cell -- is a brand-new placement, not a flip, so a snap there
                // reads correctly rather than as a missed animation.
                val discColor by animateColorAsState(
                    targetValue = if (owner == 0) palette.player0Disc else palette.player1Disc,
                    animationSpec = tween(350),
                    label = "reversi-disc-color"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(discColor)
                        .border(1.dp, palette.discBorder, CircleShape)
                )
            }
            isLegal -> {
                Box(
                    modifier = Modifier
                        .size(size * 0.22f)
                        .clip(CircleShape)
                        .background(palette.legalDot)
                )
            }
        }
    }
}

@Composable
private fun FinishedPanel(s: ReversiState, game: ReversiGame, palette: ReversiPalette) {
    val sessionWins by game.sessionWins
    val sessionDraws by game.sessionDraws
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val title = if (s.winnerPlayerId != null) {
                "${s.players.first { it.playerId == s.winnerPlayerId }.displayName} wins!"
            } else {
                "It's a tie!"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Final: ${s.players.joinToString(" — ") { "${it.displayName} ${s.scores[s.players.indexOf(it)]}" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary.copy(alpha = 0.85f)
            )
            val totalRounds = sessionWins.values.sum() + sessionDraws
            if (totalRounds > 1) {
                Spacer(Modifier.height(4.dp))
                val winsText = s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" }
                Text(
                    if (sessionDraws > 0) "Session: $winsText · Draws $sessionDraws" else "Session: $winsText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { game.playAgain() }) { Text("Play Again") }
                OutlinedButton(onClick = { game.leaveSession() }) { Text("Back to Menu") }
            }
        }
    }
}
