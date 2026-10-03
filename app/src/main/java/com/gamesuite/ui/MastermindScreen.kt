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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.gamesuite.games.mastermind.MastermindGame
import com.gamesuite.games.mastermind.MastermindGuess
import com.gamesuite.games.mastermind.MastermindRecord
import com.gamesuite.games.mastermind.MastermindStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.victoryGlow
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.fitBoard
import kotlinx.coroutines.delay
import kotlin.math.floor

/** The play column never gets wider than this, so a tablet or a 1280dp window is not a sea of empty cream. */
private val MASTERMIND_MAX_WIDTH = 520.dp

/** Guess-slot size bounds (dp). Below the minimum the slot row stops shrinking and scrolls sideways instead. */
private const val PEG_MIN_DP = 28f
private const val PEG_MAX_DP = 56f

/** Colour swatches are standalone controls, so they stay at the 48dp accessibility floor. */
private const val PEG_SWATCH_DP = 48f
private const val PEG_GAP_DP = 8f

/** History pegs and feedback dots are display only, so they can be smaller than a control. */
private const val PEG_HISTORY_DP = 28f
private const val PEG_FEEDBACK_DP = 14f

/** How long the newest history row takes to drop in. Off entirely under reduced motion. */
private const val GUESS_REVEAL_MS = 260

/** Near-black ink for the colour-blind marks drawn on light pegs. */
private val MASTERMIND_INK_DARK = Color(0xFF1A1208)

/** Names for the 8 peg colours, in [MastermindPalette.pegColors] order (the same eight Edge Match names). */
private val PEG_COLOR_NAMES = listOf("red", "yellow", "green", "blue", "purple", "pink", "teal", "grey")

private const val MASTERMIND_HELP_TEXT =
    "A secret code of 4 or 5 colored pegs is hidden (a color can repeat), and you have 10 " +
        "guesses to crack it.\n\n" +
        "Tap a color to drop it into the next empty slot, or tap a slot first to choose which one. " +
        "Tap a filled slot to clear it, then press Submit Guess.\n\n" +
        "Each guess gets feedback pegs, black first and then white: a black peg is a right color in " +
        "the right place, and a white peg is a right color in the wrong place. The pegs don't say " +
        "which of your pegs they score.\n\n" +
        "Easy is 4 pegs and 4 colors, Medium is 4 pegs and 6 colors, and Hard is 5 pegs and 8 colors. " +
        "Your clock starts on your first guess, and Colorblind-safe mode in Settings adds a shape " +
        "to every color."

/**
 * Renders MastermindGame's state reactively — same overall shape as ColorFloodScreen/
 * EdgeMatchScreen (difficulty selector, live status row, finished-round panel), plus a genuinely
 * new-to-this-app input shape: build a guess by tapping color swatches to fill empty peg slots
 * (tap a filled slot to clear it back to empty), then commit it with "Submit Guess" once every
 * slot is filled. Past guesses render below, newest first (matching the "recent history" idiom
 * `PartyToolkitScreen`'s own Dice/Coin Toss history rows already use), each showing its own peg
 * colors plus black/white feedback dots — see [MastermindGame.scoreGuess]'s own KDoc for what
 * those mean.
 *
 * The secret itself is never rendered while the round is still live — [MastermindState.secret]
 * exists in the engine's state the whole time (same "the engine holds the full truth" idiom
 * MinesweeperState's own hidden mine layout uses), this screen only reveals it in the finished
 * panel once the round is over (solved OR out of guesses). The finished panel takes the place of
 * the input (not the bottom of the history), so it is on screen the moment the round ends.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Back to Menu) plus its BackHandler.
 * A "finished unit" here is a code that was solved OR lost ([MastermindGame.puzzlesSolved] +
 * [MastermindGame.puzzlesFailed]). Leaving mid-code discards ONLY the unfinished code: with nothing
 * finished yet it is a pure `abortMatch()` (never recorded); once the session has finished codes
 * it goes through `leaveSession()`, the same call the finished panel's "Back to Menu" makes, so
 * those results still count (the confirm dialog says so). A finished code always leaves through
 * `leaveSession()`. The status block reserves [GameChromeEndInset] so the corner button never
 * covers the clock, and it is a fixed header so scrolled history never slides under the button.
 *
 * DAILY CODE: when [dailySeed] is non-null the first code is today's seeded one, tagged "Daily
 * code". Any later fresh code (a difficulty switch, "New Secret") is random again and the tag
 * goes away; switching difficulty away from a code in progress asks first.
 *
 * LAYOUT: a fixed header (status, difficulty chips) above one scrolling region, all in a column
 * capped at [MASTERMIND_MAX_WIDTH] and centered. The guess slots are sized from this column's
 * measured width with [fitBoard] (never wider than their container, [PEG_MIN_DP] to [PEG_MAX_DP]),
 * and the row scrolls sideways rather than shrink under [PEG_MIN_DP]. The colour swatches are
 * fixed 48dp controls that wrap into balanced rows instead of scrolling sideways, so no colour is
 * hidden off-screen at HARD's 8 colours on a Fold cover screen. History pegs sit next to their
 * feedback dots (a 2-row grid) instead of at opposite edges.
 *
 * INPUT: the next peg goes into the slot ringed in the guess row: the slot last tapped if it is
 * empty, otherwise the first empty one. Tapping a filled slot clears it and ringed-selects it.
 *
 * ACCESSIBILITY: every slot and swatch is a button described by its colour name (and slot
 * position); each history row is one description ("Guess 3: red, blue, green, red. Feedback:
 * 2 black, 1 white"); the guess counter is a polite live region that also announces the newest
 * feedback; the difficulty chips are radio buttons; non-grid controls are at least 48dp. Every peg
 * has a rim (the golden peg and the empty slot are nearly the cream background otherwise), and
 * with [LocalColorblindMode] each colour also carries a distinct shape mark (dot, ring, diamond,
 * plus, X, triangle, bar, square, the same eight Edge Match uses). Feedback dots are told apart
 * by luminance (black / white / empty), not hue, so they need no extra mark.
 *
 * MOTION/SOUND: the newest history row drops in over [GUESS_REVEAL_MS], off under
 * [LocalReducedMotion]; a solve (always the human's, the only player) plays the success chime
 * alongside the celebration haptic and glow.
 *
 * CLOCK: shown by its own child composable (so the 5Hz tick recomposes one Text, not the screen)
 * reading [MastermindGame.activeElapsedMillis], which freezes while the engine is paused.
 */
@Composable
fun MastermindScreen(
    sessionManager: GameSessionManager,
    game: MastermindGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's secret for every player — see MastermindGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { MastermindStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = mastermindPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current

    // True while the code on screen is the seeded daily one. Any later fresh code (a difficulty
    // switch, "New Secret") is random again, and the status block stops saying "Daily code".
    var dailyBoardActive by remember { mutableStateOf(dailySeed != null) }
    // A difficulty chip tapped while a code is in progress waits here for the player's confirmation.
    var pendingTier by remember { mutableStateOf<CpuDifficulty?>(null) }

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
    // Keyed on the engine's per-code counter (new for every fresh code, stable across the guesses
    // of the SAME code) -- same fix Edge Match's own reportedResult needed (keying only on
    // difficulty/size stayed constant across "New Puzzle" clicks in the same tier and so only ever
    // recorded the FIRST solve). Not the secret itself: a secret can repeat back to back (and
    // re-dealing the daily seed repeats it), which would skip recording the second solve.
    val round = game.roundNumber.value
    var reportedResult by remember(game.difficulty, round) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var currentGuess by remember(game.difficulty, round) { mutableStateOf(List<Int?>(s.positions) { null }) }
    var selectedSlot by remember(game.difficulty, round) { mutableStateOf<Int?>(null) }
    var reportedLoss by remember(game.difficulty, round) { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // The result panel replaces the input at the top of the scrolling region, so bring that top
    // into view when a round ends or a fresh one starts, wherever the player had scrolled to.
    LaunchedEffect(s.isOver, round) { scroll.scrollTo(0) }

    LaunchedEffect(s.solved, round) {
        if (!s.solved) return@LaunchedEffect
        // A solve is always the human's (the only player), so the celebration is unconditional.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.difficulty, s.guesses.size, finalTime)
            reportedResult = result.isNewBestGuesses to result.isNewBestTimeMillis
        }
    }

    LaunchedEffect(s.outOfGuesses, round) {
        if (s.outOfGuesses && !reportedLoss) {
            reportedLoss = true
            haptics(HapticSignal.FAILURE)
        }
    }

    val applyTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value) {
            dailyBoardActive = false
            game.difficulty = tier
            game.startMatch()
        }
    }
    // Only a code with guesses on it (or an untouched daily one) is worth a confirmation; a
    // finished code, or a blank random one, can be left behind freely.
    val roundInProgress = !s.isOver && (s.guesses.isNotEmpty() || dailyBoardActive)
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (tier != game.difficulty) {
            if (roundInProgress) pendingTier = tier else applyTier(tier)
        }
    }
    val startNewCode: () -> Unit = {
        if (!game.matchOver.value) {
            dailyBoardActive = false
            game.playAgain()
        }
    }
    val onSlotTap: (Int) -> Unit = { slot ->
        if (!s.isOver && !game.matchOver.value) {
            if (currentGuess[slot] != null) {
                currentGuess = currentGuess.toMutableList().apply { this[slot] = null }
            }
            selectedSlot = slot
        }
    }
    val onPickColor: (Int) -> Unit = { color ->
        val target = mastermindTargetSlot(currentGuess, selectedSlot)
        if (target != null && !s.isOver && !game.matchOver.value) {
            currentGuess = currentGuess.toMutableList().apply { this[target] = color }
            selectedSlot = null
            sounds.playTap()
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onSubmit: () -> Unit = {
        val guess = currentGuess.filterNotNull()
        if (guess.size == s.positions && !s.isOver && !game.matchOver.value) {
            game.submitGuess(guess)
            currentGuess = List(s.positions) { null }
            selectedSlot = null
            haptics(HapticSignal.NORMAL_ACTION)
        }
    }

    // "Finished units" for the abort policy: codes already solved OR lost this session. If any
    // exist, leaving mid-code must still score them -- see onAbort below.
    val finishedCodes = game.puzzlesSolved.value + game.puzzlesFailed.value

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-code discards
    // ONLY the unfinished code: if codes were already finished this session, leaving goes through
    // leaveSession() (the same call the finished panel's "Back to Menu" makes) so they still count;
    // with nothing finished it is a pure abort (never a win or loss). A finished code leaves
    // through leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Mastermind",
        helpText = MASTERMIND_HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedCodes > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this code?",
        leaveBody = if (finishedCodes > 0) {
            "This code is still unsolved and won't count, but the codes you've already finished stay on your record."
        } else {
            "This code is still unsolved. Leaving now won't count it as a win or a loss."
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                // AGSL deepening: the same shared "big win" glow every other flagship
                // screen now shows, timed to the exact solve moment the CELEBRATION
                // haptic just above already fires from. Gated on reducedMotion, same
                // as UnoScreen's own ConfettiOverlay ("enhanced" = LocalEnhancedAnimations
                // && !reducedMotion) -- the haptic above stays unconditional, only this
                // visual flourish is suppressed.
                .victoryGlow(trigger = s.solved && !reducedMotion, tint = palette.accent)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MASTERMIND_MAX_WIDTH)
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Fixed header. The status block is the top-right occupant of this layout, so it
                // keeps the corner button's width free at its end, and it is at least 48dp tall so
                // everything below it starts under the button's bottom edge.
                MastermindStatus(
                    guessesUsed = s.guesses.size,
                    maxGuesses = s.maxGuesses,
                    lastGuess = s.guesses.lastOrNull(),
                    daily = dailyBoardActive,
                    game = game,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = GameChromeEndInset)
                )
                DifficultyTabsMastermind(current = game.difficulty, palette = palette, onSelect = onSelectTier)
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!s.isOver) {
                        MastermindInput(
                            positions = s.positions,
                            colorCount = s.colorCount,
                            currentGuess = currentGuess,
                            selectedSlot = selectedSlot,
                            palette = palette,
                            colorblind = colorblind,
                            onSlotTap = onSlotTap,
                            onPickColor = onPickColor,
                            onSubmit = onSubmit
                        )
                    } else {
                        MastermindFinishedPanel(
                            solved = s.solved,
                            secret = s.secret,
                            guessCount = s.guesses.size,
                            record = record,
                            isNewBestGuesses = reportedResult?.first ?: false,
                            isNewBestTime = reportedResult?.second ?: false,
                            onNewSecret = startNewCode,
                            onBackToMenu = { game.leaveSession() },
                            palette = palette,
                            colorblind = colorblind,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (s.guesses.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Guesses", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            for ((index, guess) in s.guesses.withIndex().reversed()) {
                                key(index) {
                                    MastermindHistoryRow(
                                        number = index + 1,
                                        guess = guess,
                                        animateIn = !reducedMotion && index == s.guesses.lastIndex,
                                        palette = palette,
                                        colorblind = colorblind
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    pendingTier?.let { tier ->
        AlertDialog(
            onDismissRequest = { pendingTier = null },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text("Switch to ${mastermindTierLabel(tier)}?") },
            text = {
                Text(
                    if (dailyBoardActive) {
                        "This starts a new random code and leaves today's daily code. Your guesses on this code are discarded."
                    } else {
                        "This starts a new code. Your guesses on this code are discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTier = null
                        applyTier(tier)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Switch", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingTier = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.textPrimary)
                ) { Text("Keep Playing") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- pegColors are the one deliberate exception, spanning real hue
// variety for the same reason Color Flood/Edge Match's own cell/edge colors
// do -- this puzzle's whole mechanic depends on genuinely distinguishable
// pegs. Sized for HARD's own ceiling (8 colors), same as Edge Match's own
// widened Custom Game Builder palette.
// ---------------------------------------------------------------------------
private data class MastermindPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val slotBorder: Color,
    val blackPeg: Color,
    val whitePeg: Color,
    val emptyPeg: Color,
    val pegColors: List<Color>
)

private val MastermindLightPalette = MastermindPalette(
    background = Color(0xFFFBF1E6),
    accent = Color(0xFFE08D4B),
    textPrimary = Color(0xFF3A2E22),
    // Dark ink: cream text on this orange was only ~2.5:1.
    textOnAccent = Color(0xFF2B1F12),
    chipBackground = Color(0xFFE3CBA9),
    slotBorder = Color(0xFF3A2E22),
    blackPeg = Color(0xFF2A2118),
    whitePeg = Color(0xFFFFFBF5),
    emptyPeg = Color(0xFFD9C6AA),
    pegColors = listOf(
        Color(0xFFD9573F), // terracotta red
        Color(0xFFE0A83E), // golden yellow
        Color(0xFF5B9A5B), // leaf green
        Color(0xFF3E7A9E), // ocean blue
        Color(0xFF8A5FA0), // plum purple
        Color(0xFFC9628F), // warm rose
        Color(0xFF3E9E96), // teal
        Color(0xFF6E7A8A)  // slate
    )
)

private val MastermindDarkPalette = MastermindPalette(
    background = Color(0xFF1C1712),
    accent = Color(0xFFE8985B),
    textPrimary = Color(0xFFF3E9DB),
    textOnAccent = Color(0xFF1C1712),
    chipBackground = Color(0xFF453A2E),
    slotBorder = Color(0xFFF3E9DB),
    blackPeg = Color(0xFF0F0C09),
    whitePeg = Color(0xFFF3E9DB),
    emptyPeg = Color(0xFF574A3A),
    pegColors = listOf(
        Color(0xFFE0705A),
        Color(0xFFE8BE6C),
        Color(0xFF7ECB98),
        Color(0xFF7FA6D9),
        Color(0xFFB399D9),
        Color(0xFFE099B8),
        Color(0xFF5CC9C0),
        Color(0xFF9AA8BA)
    )
)

/** Shared instances, so a recomposition never allocates a fresh palette (and its colour list) again. */
private fun mastermindPalette(isDark: Boolean): MastermindPalette =
    if (isDark) MastermindDarkPalette else MastermindLightPalette

// ---------------------------------------------------------------------------
// Text and screen-reader helpers
// ---------------------------------------------------------------------------

private fun mastermindColorName(colorIndex: Int): String =
    PEG_COLOR_NAMES.getOrElse(colorIndex) { "color ${colorIndex + 1}" }

private fun mastermindTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

private fun mastermindFormatClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/** "Feedback: 2 black, 1 white" / "Feedback: none" -- the spoken form of a guess's feedback dots. */
private fun mastermindFeedbackText(blackPegs: Int, whitePegs: Int): String {
    val parts = mutableListOf<String>()
    if (blackPegs > 0) parts += "$blackPegs black"
    if (whitePegs > 0) parts += "$whitePegs white"
    return "Feedback: " + if (parts.isEmpty()) "none" else parts.joinToString(", ")
}

/**
 * The slot the next picked colour goes into: the slot the player last tapped if it is still empty,
 * otherwise the first empty slot; null when the guess is already full. The one rule the picker, the
 * ring on the guess row and the swatches' enabled state all share.
 */
private fun mastermindTargetSlot(guess: List<Int?>, selected: Int?): Int? {
    if (selected != null && selected in guess.indices && guess[selected] == null) return selected
    val firstEmpty = guess.indexOfFirst { it == null }
    return if (firstEmpty == -1) null else firstEmpty
}

// ---------------------------------------------------------------------------
// Difficulty chips, status block, clock
// ---------------------------------------------------------------------------

@Composable
private fun DifficultyTabsMastermind(current: CpuDifficulty, palette: MastermindPalette, onSelect: (CpuDifficulty) -> Unit) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text, so
    // a chip is never compressed to a sliver; the row is simply scrolled into view.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        for (tier in CpuDifficulty.entries) {
            MastermindTab(
                label = mastermindTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill chip with a 48dp-tall touch target around a 36dp-tall pill, announced as a radio button. */
@Composable
private fun MastermindTab(label: String, selected: Boolean, palette: MastermindPalette, onClick: () -> Unit) {
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
 * Guesses used and the clock on one line (plus a "Daily code" tag when today's seeded code is the
 * one on screen). At least 48dp tall so the layout below it clears the corner button. Callers give
 * it `padding(end = GameChromeEndInset)`. The guess counter is a polite live region that also
 * speaks the newest guess's feedback, so a screen-reader player hears the result of every submit.
 */
@Composable
private fun MastermindStatus(
    guessesUsed: Int,
    maxGuesses: Int,
    lastGuess: MastermindGuess?,
    daily: Boolean,
    game: MastermindGame,
    palette: MastermindPalette,
    modifier: Modifier = Modifier
) {
    val spoken = remember(guessesUsed, maxGuesses, lastGuess) {
        val base = "$guessesUsed of $maxGuesses guesses used"
        if (lastGuess == null) base else "$base. ${mastermindFeedbackText(lastGuess.blackPegs, lastGuess.whitePegs)}"
    }
    Column(modifier = modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        if (daily) {
            Text(
                "Daily code",
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
                "Guesses: $guessesUsed / $maxGuesses",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = spoken
                    }
            )
            MastermindClock(game = game, palette = palette)
        }
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen. Reads [MastermindGame.activeElapsedMillis], so it freezes while the engine is
 * paused (app backgrounded) and ends on exactly the recorded round time.
 */
@Composable
private fun MastermindClock(game: MastermindGame, palette: MastermindPalette) {
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
        mastermindFormatClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold
    )
}

// ---------------------------------------------------------------------------
// Input: guess slots, colour swatches, submit
// ---------------------------------------------------------------------------

/**
 * The guess being built: the slot row, the colour swatches and "Submit Guess". Sizes from its own
 * measured width, not an outer scope. The slot row uses [fitBoard] (one row of [positions] cells,
 * gaps taken out of the width first), so it can never be wider than the space it lives in; if even
 * [PEG_MIN_DP] slots would not fit, the row keeps that size and scrolls sideways. The swatches are
 * controls, so they stay 48dp and wrap into balanced rows (4 + 4 at HARD on a narrow window)
 * instead of scrolling.
 */
@Composable
private fun MastermindInput(
    positions: Int,
    colorCount: Int,
    currentGuess: List<Int?>,
    selectedSlot: Int?,
    palette: MastermindPalette,
    colorblind: Boolean,
    onSlotTap: (Int) -> Unit,
    onPickColor: (Int) -> Unit,
    onSubmit: () -> Unit
) {
    val density = LocalDensity.current
    val target = mastermindTargetSlot(currentGuess, selectedSlot)
    val full = currentGuess.all { it != null }
    val slotScroll = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Your guess", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val fit = fitBoard(
                availableWidthPx = availableWidth.value - PEG_GAP_DP * (positions - 1),
                availableHeightPx = PEG_MAX_DP,
                columns = positions,
                rows = 1,
                minCellPx = PEG_MIN_DP,
                maxCellPx = PEG_MAX_DP
            )
            val scrolls = !fit.meetsMinimum
            // Each slot is laid out in whole pixels, so the dp size is floored to a pixel and
            // trimmed by one more: rounded-up slots plus rounded gaps would otherwise overshoot
            // the available width by a pixel or two.
            val slotSize: Dp = if (scrolls) {
                PEG_MIN_DP.dp
            } else {
                ((floor(fit.cellPx * density.density) - 1f).coerceAtLeast(1f) / density.density).dp
            }
            Row(
                modifier = if (scrolls) Modifier.horizontalScroll(slotScroll) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(PEG_GAP_DP.dp)
            ) {
                for (i in 0 until positions) {
                    MastermindSlot(
                        index = i,
                        total = positions,
                        colorIndex = currentGuess[i],
                        isTarget = target == i,
                        size = slotSize,
                        palette = palette,
                        colorblind = colorblind,
                        onTap = { onSlotTap(i) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Pick a color", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val perRow = ((availableWidth.value + PEG_GAP_DP) / (PEG_SWATCH_DP + PEG_GAP_DP)).toInt().coerceAtLeast(1)
            val rowCount = (colorCount + perRow - 1) / perRow
            val balancedPerRow = (colorCount + rowCount - 1) / rowCount
            Column(
                verticalArrangement = Arrangement.spacedBy(PEG_GAP_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (rowColors in (0 until colorCount).toList().chunked(balancedPerRow)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PEG_GAP_DP.dp)) {
                        for (c in rowColors) {
                            MastermindSwatch(
                                colorIndex = c,
                                enabled = target != null,
                                palette = palette,
                                colorblind = colorblind,
                                onTap = { onPickColor(c) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSubmit,
                enabled = full,
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.textPrimary,
                    contentColor = palette.background,
                    disabledContainerColor = palette.chipBackground,
                    disabledContentColor = palette.textPrimary.copy(alpha = 0.6f)
                )
            ) { Text("Submit Guess", fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * One peg slot in the in-progress guess row. Tapping a FILLED slot clears it back to empty; tapping
 * any slot makes it the one the next picked colour fills. [isTarget] (the slot the next colour will
 * go into) gets a heavy ring in the text colour: a thickness cue, not a hue one.
 */
@Composable
private fun MastermindSlot(
    index: Int,
    total: Int,
    colorIndex: Int?,
    isTarget: Boolean,
    size: Dp,
    palette: MastermindPalette,
    colorblind: Boolean,
    onTap: () -> Unit
) {
    // State first, then where it is, with a 1-indexed position -- ReversiScreen.CellView's phrasing.
    val description = remember(index, total, colorIndex, isTarget) {
        val state = if (colorIndex == null) "empty" else mastermindColorName(colorIndex)
        val base = "Slot ${index + 1} of $total, $state"
        if (isTarget) "$base, next to fill" else base
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                onClickLabel = if (colorIndex == null) "Choose this slot" else "Clear this slot",
                role = Role.Button,
                onClick = onTap
            )
            .semantics { contentDescription = description }
            .then(if (isTarget) Modifier.border(3.dp, palette.textPrimary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        MastermindPeg(
            colorIndex = colorIndex,
            palette = palette,
            colorblind = colorblind,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** One tappable colour swatch in the "pick a color" rows: a 48dp button that drops its colour into the target slot. */
@Composable
private fun MastermindSwatch(
    colorIndex: Int,
    enabled: Boolean,
    palette: MastermindPalette,
    colorblind: Boolean,
    onTap: () -> Unit
) {
    val description = "${mastermindColorName(colorIndex)} peg"
    Box(
        modifier = Modifier
            .size(PEG_SWATCH_DP.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Add to guess", role = Role.Button, onClick = onTap)
            .semantics { contentDescription = description }
    ) {
        MastermindPeg(
            colorIndex = colorIndex,
            palette = palette,
            colorblind = colorblind,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------------------------------------------------------------------
// Pegs, history, feedback
// ---------------------------------------------------------------------------

/**
 * One peg: a filled disc ([colorIndex] null draws the empty-slot colour) with a rim, because the
 * golden peg and the empty slot are only ~1.5-1.9:1 against the cream background on their own. With
 * [colorblind] a colour also carries its shape mark (see [drawMastermindMark]). Purely visual, no
 * semantics of its own: the slot, swatch, row or panel that contains it describes it.
 */
@Composable
private fun MastermindPeg(
    colorIndex: Int?,
    palette: MastermindPalette,
    colorblind: Boolean,
    modifier: Modifier = Modifier
) {
    val fill = if (colorIndex == null) palette.emptyPeg else palette.pegColors[colorIndex]
    val rim = palette.slotBorder.copy(alpha = if (colorIndex == null) 0.5f else 0.7f)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, rim, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (colorblind && colorIndex != null) {
            val ink = if (fill.luminance() > 0.2f) MASTERMIND_INK_DARK else Color.White
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMastermindMark(colorIndex, center, unit = size.minDimension * 0.17f, ink = ink)
            }
        }
    }
}

/**
 * Colorblind-mode identity mark for peg colour [colorIndex], centred at [at], [unit] = its radius.
 * Eight distinct shapes for the eight palette slots: dot, ring, diamond, plus, X, triangle, bar,
 * square (the same eight, in the same order, Edge Match uses for its edge colours). Drawn in [ink]
 * (near-black or white, whichever contrasts with the peg).
 */
private fun DrawScope.drawMastermindMark(colorIndex: Int, at: Offset, unit: Float, ink: Color) {
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

/**
 * One past guess: its guess number, its own pegs, then its feedback dots right beside them (a
 * 2-row grid, black first then white then empty placeholders -- the standard genre layout) rather
 * than at the opposite edge of the row. The whole row is ONE screen-reader node: the pegs and dots
 * are cleared and a single description names the colours and the feedback.
 *
 * The newest row ([animateIn]) drops in from slightly above over [GUESS_REVEAL_MS], which reads as
 * the guess leaving the input and landing in the history; every older row (and every row under
 * reduced motion) just sits at its final place. Rows are keyed by guess number by the caller, so
 * only a genuinely new row runs this.
 */
@Composable
private fun MastermindHistoryRow(
    number: Int,
    guess: MastermindGuess,
    animateIn: Boolean,
    palette: MastermindPalette,
    colorblind: Boolean
) {
    val reveal = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) {
            reveal.animateTo(1f, animationSpec = tween(durationMillis = GUESS_REVEAL_MS, easing = FastOutSlowInEasing))
        }
    }
    val description = remember(number, guess) {
        val colors = guess.colors.joinToString(", ") { mastermindColorName(it) }
        "Guess $number: $colors. ${mastermindFeedbackText(guess.blackPegs, guess.whitePegs)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val lift = 12.dp.toPx()
                alpha = reveal.value
                translationY = -(1f - reveal.value) * lift
            }
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$number",
            color = palette.textPrimary.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (color in guess.colors) {
                MastermindPeg(
                    colorIndex = color,
                    palette = palette,
                    colorblind = colorblind,
                    modifier = Modifier.size(PEG_HISTORY_DP.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        MastermindFeedbackDots(guess = guess, palette = palette)
    }
}

/**
 * The feedback dots for one guess: up to [MastermindGuess.blackPegs] black dots followed by up to
 * [MastermindGuess.whitePegs] white dots (the rest left as empty placeholders), laid out in two rows.
 *
 * The dots always get a thin [MastermindPalette.slotBorder] ring -- without it, the white-peg fill
 * color sits almost indistinguishable from this screen's own light-theme background (both
 * near-white/cream), which defeats the entire point of that dot (telling a white peg apart from an
 * empty placeholder or the page behind it). The border keeps every dot legible in both themes
 * rather than relying on fill-color contrast alone.
 */
@Composable
private fun MastermindFeedbackDots(guess: MastermindGuess, palette: MastermindPalette) {
    val total = guess.colors.size
    val columns = (total + 1) / 2
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (rowStart in 0 until total step columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in rowStart until minOf(rowStart + columns, total)) {
                    val color = when {
                        i < guess.blackPegs -> palette.blackPeg
                        i < guess.blackPegs + guess.whitePegs -> palette.whitePeg
                        else -> palette.emptyPeg
                    }
                    Box(
                        modifier = Modifier
                            .size(PEG_FEEDBACK_DP.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, palette.slotBorder, CircleShape)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Finished-round panel
// ---------------------------------------------------------------------------

@Composable
private fun MastermindFinishedPanel(
    solved: Boolean,
    secret: List<Int>,
    guessCount: Int,
    record: MastermindRecord?,
    isNewBestGuesses: Boolean,
    isNewBestTime: Boolean,
    onNewSecret: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: MastermindPalette,
    colorblind: Boolean,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // A polite live region so a screen reader announces the outcome the moment it appears.
            val titleModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            if (solved) {
                Text(
                    "You cracked it!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = titleModifier
                )
                Spacer(Modifier.height(4.dp))
                Text("Guesses: $guessCount", style = MaterialTheme.typography.bodyMedium)
                if (isNewBestGuesses) {
                    Spacer(Modifier.height(4.dp))
                    Text("New best guess count!", fontWeight = FontWeight.Bold)
                } else if (record?.bestGuesses != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Best guesses: ${record.bestGuesses}", style = MaterialTheme.typography.bodyMedium)
                }
                if (isNewBestTime) {
                    Spacer(Modifier.height(4.dp))
                    Text("New best time!", fontWeight = FontWeight.Bold)
                } else if (record?.bestTimeMillis != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Best time: ${mastermindFormatClock(record.bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "Out of guesses",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = titleModifier
                )
                Spacer(Modifier.height(8.dp))
                Text("The secret was:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val secretDescription = "Secret code: " + secret.joinToString(", ") { mastermindColorName(it) }
                Row(
                    modifier = Modifier.clearAndSetSemantics { contentDescription = secretDescription },
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (color in secret) {
                        MastermindPeg(
                            colorIndex = color,
                            palette = palette,
                            colorblind = colorblind,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNewSecret,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.textPrimary, contentColor = palette.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Secret", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Back to Menu", textAlign = TextAlign.Center) }
            }
        }
    }
}
