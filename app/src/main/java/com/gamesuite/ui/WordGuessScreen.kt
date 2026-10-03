package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordguess.LetterFeedback
import com.gamesuite.games.wordguess.WordGuessEntry
import com.gamesuite.games.wordguess.WordGuessGame
import com.gamesuite.games.wordguess.WordGuessRecord
import com.gamesuite.games.wordguess.WordGuessState
import com.gamesuite.games.wordguess.WordGuessStatsStore
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

private const val HELP_TEXT =
    "Guess the hidden five-letter word in a limited number of tries: Easy gives 8 guesses, " +
        "Medium 6 and Hard 5. Type a real English word on the keyboard and press Enter. Each " +
        "letter then shows whether it is in the right spot, in the word but in a different " +
        "spot, or not in the word at all, and a repeated letter is only marked as many times as " +
        "it really appears.\n\n" +
        "Keys remember the best result for their letter, and with Colorblind-safe mode on every " +
        "tile and key also carries a check, ring or cross. Fewer guesses and a faster time set " +
        "new bests, and the today's-word entry gives everyone the same word that day."

/**
 * Renders WordGuessGame's state reactively — same overall shape as MastermindScreen (difficulty
 * selector, live status row, finished-round panel, feedback-driven guessing). Input is a custom
 * on-screen QWERTY keyboard rather than the system IME, matching HangmanScreen's own established
 * "no native keyboard for letter input" precedent — typing builds up [WordGuessGame.WORD_LENGTH]
 * letters locally before "Enter" actually submits a guess to the engine, so an invalid word never
 * even reaches [WordGuessGame.submitGuess] without the player seeing what they typed.
 *
 * Each keyboard key's own background reflects the BEST feedback seen for that letter across
 * every past guess so far (CORRECT beats PRESENT beats ABSENT beats never-tried) — the standard
 * genre convention, and genuinely useful information (which letters are already ruled out),
 * not just decoration.
 *
 * CHROME: the shared [GameChrome] corner menu (How to Play, Back to Menu) plus its BackHandler.
 * The unit of this session is a WORD, so leaving mid-word discards only the unfinished word: with
 * no word solved yet it is a pure `abortMatch()` (never recorded), but once the session has solved
 * words it goes through `leaveSession()`, the same call the finished panel's "Back to Menu" makes,
 * so those results still count (the confirm dialog says so). Words lost to running out of guesses
 * are not tracked by the engine, so they do not make a session count on their own. Switching
 * difficulty mid-word asks first, since it discards the guesses so far; on the today's-word route
 * a switch made mid-word replays today's word at the new guess allowance, while a switch made
 * after the word is decided (and "New Word") deals a random word and drops the "Today's word"
 * tag. The status row reserves [GameChromeEndInset] on the tab row so the corner button never
 * covers it.
 *
 * LAYOUT: the board is sized by [fitBoard] against the measured space of its own slot (tile plus
 * its gap treated as one cell), clamped to [MIN_TILE_DP]..[MAX_TILE_DP]; a slot that cannot give a
 * [MIN_TILE_DP] tile pans instead of shrinking the tiles further. Portrait stacks header / board /
 * keyboard (or result panel) in a column capped at [CONTENT_MAX_WIDTH]: the header and the bottom
 * zone take their natural height and the board is a weighted, measured slot for what is left (no
 * height estimate), so it can neither clip nor waste space; the result panel is capped at 45% of
 * the window height and scrolls inside that cap. A wide landscape window puts the board on the
 * left and tabs, status and keyboard in a
 * [SIDE_PANE_WIDTH] column on the right. The keyboard's key slots tile the full width with no dead
 * gaps between them (the visible key is inset inside its touch slot), and Enter / Backspace are
 * 1.5 slots wide so the bottom row is never wider than the top one.
 *
 * ACCESSIBILITY: every tile of a submitted row and of the row being typed is described like
 * ReversiScreen.CellView (letter and state, then 1-indexed row and column; unused rows are hidden
 * since "empty" tells a screen-reader user nothing). Every key is a button whose description
 * carries its best-known state. With [LocalColorblindMode] each revealed tile and each key that
 * has a result also draws a corner mark (check = correct, ring = in the word elsewhere, cross =
 * not in the word) so colour is never the only signal. The result of each guess, and a rejected
 * word, are announced through a polite live region. Tabs are radio buttons; every non-grid
 * control is at least 48dp tall. Tile ink is chosen per fill by contrast ratio, and empty tiles
 * carry a >= 3:1 border with a heavier cursor cell.
 *
 * MOTION (off under [LocalReducedMotion]): a submitted row flips tile by tile (45ms stagger, 120ms
 * per tile, 300ms end to end) and a rejected word shakes its row for 270ms. The rejection message
 * has a reserved slot, so showing it never shifts the keyboard.
 *
 * FEEDBACK: a human solve plays the success chime and the celebration haptic BEFORE the stats
 * write (it used to wait on DataStore), a rejected word buzzes, and running out of guesses buzzes
 * with the failure haptic. The clock reads [WordGuessGame.activeElapsedMillis], so it freezes
 * while paused and ends on exactly the recorded time.
 */
@Composable
fun WordGuessScreen(
    sessionManager: GameSessionManager,
    game: WordGuessGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** Non-null pins today's word for every player — see WordGuessGame.startMatch(dailySeed)'s KDoc. */
    dailySeed: Long? = null
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { WordGuessStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = wordGuessPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val density = LocalDensity.current

    // True while the word on screen is today's seeded word. Any later fresh word (New Word, or a
    // tier switch once the word is decided) is random again, and the status row says so.
    var dailyWordActive by remember { mutableStateOf(dailySeed != null) }
    // Bumped by every round this screen starts, so per-round UI state resets even if the engine
    // happened to deal the same secret twice in a row.
    var roundId by remember { mutableStateOf(0) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.loadWordData(androidContext)
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        dailyWordActive = dailySeed != null
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name]

    // Per-round UI state, keyed on the round: the actual secret (unique per round, stable across
    // guesses of the SAME round) plus the round counter and tier.
    val roundKey = "$roundId/${game.difficulty.name}/${s.secret}"
    var reportedResult by remember(roundKey) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var currentInput by remember(roundKey) { mutableStateOf("") }
    var showInvalidMessage by remember(roundKey) { mutableStateOf(false) }
    var announcement by remember(roundKey) { mutableStateOf("") }
    var invalidTick by remember(roundKey) { mutableStateOf(0) }
    var revealRow by remember(roundKey) { mutableStateOf(-1) }
    var pendingTier by remember { mutableStateOf<CpuDifficulty?>(null) }

    // A rejected word shakes the row being typed: a short damped side-to-side swing.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(invalidTick, reducedMotion) {
        if (invalidTick == 0 || reducedMotion) {
            shake.snapTo(0f)
            return@LaunchedEffect
        }
        val amplitudePx = with(density) { SHAKE_AMPLITUDE_DP.dp.toPx() }
        for (step in SHAKE_STEPS) {
            shake.animateTo(step * amplitudePx, animationSpec = tween(durationMillis = SHAKE_STEP_MS, easing = LinearEasing))
        }
    }

    LaunchedEffect(s.solved) {
        if (!s.solved) return@LaunchedEffect
        // A solve is always the human's (this is a solo game). Feedback first: the chime and the
        // haptic must not wait on the DataStore write below.
        haptics(HapticSignal.CELEBRATION)
        playSfx(SfxKind.SUCCESS_CHIME)
        if (reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: game.activeElapsedMillis() ?: 0L
            val result = statsStore.recordSolve(game.difficulty, s.guesses.size, finalTime)
            reportedResult = result.isNewBestGuesses to result.isNewBestTimeMillis
        }
    }

    LaunchedEffect(s.outOfGuesses) {
        if (s.outOfGuesses) {
            haptics(HapticSignal.FAILURE)
            playSfx(SfxKind.INVALID_BUZZ)
        }
    }

    // The per-letter "best feedback seen so far" driving each keyboard key's color.
    val bestFeedbackByLetter = remember(s.guesses) {
        val best = mutableMapOf<Char, LetterFeedback>()
        for (entry in s.guesses) {
            for (i in entry.word.indices) {
                val letter = entry.word[i]
                val fb = entry.feedback[i]
                val existing = best[letter]
                if (existing == null || fb.rank() > existing.rank()) best[letter] = fb
            }
        }
        best
    }

    // "Finished units" for the abort policy: words already solved this session. If any exist,
    // leaving mid-word must still score them -- see onAbort below.
    val finishedWords = game.puzzlesSolved.value

    val startRound: (Boolean) -> Unit = { daily ->
        if (!game.matchOver.value) {
            dailyWordActive = daily && dailySeed != null
            roundId += 1
            game.startMatch(if (dailyWordActive) dailySeed else null)
        }
    }
    val switchTier: (CpuDifficulty) -> Unit = { tier ->
        if (!game.matchOver.value && tier != game.difficulty) {
            val live = game.state.value
            // Mid-word on the daily route the word stays today's; once decided, a tier tap means
            // "give me a new word at this difficulty".
            val keepDaily = dailyWordActive && live != null && !live.isOver
            game.difficulty = tier
            startRound(keepDaily)
        }
    }
    val onSelectTier: (CpuDifficulty) -> Unit = { tier ->
        if (tier != game.difficulty) {
            val live = game.state.value
            if (live != null && !live.isOver && live.guesses.isNotEmpty()) {
                pendingTier = tier
            } else {
                switchTier(tier)
            }
        }
    }

    val onLetter: (Char) -> Unit = { letter ->
        val live = game.state.value
        if (currentInput.length < WordGuessGameWordLength && live != null && !live.isOver && !game.matchOver.value) {
            currentInput += letter
            showInvalidMessage = false
            // Cleared so a second rejected word re-announces: a live region only speaks on change.
            announcement = ""
            haptics(HapticSignal.LIGHT_TICK)
        }
    }
    val onBackspace: () -> Unit = {
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
            showInvalidMessage = false
            announcement = ""
        }
    }
    val onEnter: () -> Unit = {
        val live = game.state.value
        if (currentInput.length == WordGuessGameWordLength && live != null && !live.isOver && !game.matchOver.value) {
            val before = live.guesses.size
            game.submitGuess(currentInput)
            val updated = game.state.value
            if (updated == null || updated.guesses.size == before) {
                showInvalidMessage = true
                invalidTick += 1
                announcement = "Not a valid word"
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            } else {
                revealRow = before
                currentInput = ""
                showInvalidMessage = false
                announcement = updated.guesses.lastOrNull()
                    ?.let { wordGuessGuessSummary(it, updated.guesses.size, updated.maxGuesses) }
                    ?: ""
                sounds.playTap()
                haptics(HapticSignal.NORMAL_ACTION)
            }
        }
    }

    // Back / abort-confirm / How to Play live in the shared GameChrome. Leaving mid-word discards
    // ONLY the unfinished word: if words were already solved this session, leaving goes through
    // leaveSession() (the same call the finished panel's "Back to Menu" makes) so they still
    // count; with nothing solved it is a pure abort (never a win or loss). A decided word leaves
    // through leaveSession() too, which scores the session.
    GameChrome(
        helpTitle = "How to Play Word Guess",
        helpText = HELP_TEXT,
        matchInProgress = !s.isOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedWords > 0) game.leaveSession() else game.abortMatch() },
        buttonFill = palette.background,
        buttonContent = palette.textPrimary,
        leaveTitle = "Leave this word?",
        leaveBody = if (finishedWords > 0) {
            "This word is still unsolved and won't count, but the words you've already solved stay on your record."
        } else {
            "This word is still unsolved. Leaving now won't count it as a win or a loss."
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                // AGSL deepening: same shared "big win" glow as MastermindScreen, timed to
                // the same s.solved moment the CELEBRATION haptic above fires from, gated
                // on reduced motion to match UnoScreen's ConfettiOverlay precedent.
                .victoryGlow(trigger = s.solved && !reducedMotion, tint = palette.accent)
                .padding(12.dp)
        ) {
            val availableHeight = maxHeight
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 1.15f

            val header: @Composable (Modifier) -> Unit = { headerModifier ->
                WordGuessHeader(
                    current = game.difficulty,
                    dailyActive = dailyWordActive,
                    guessesUsed = s.guesses.size,
                    maxGuesses = s.maxGuesses,
                    game = game,
                    palette = palette,
                    onSelectTier = onSelectTier,
                    modifier = headerModifier
                )
            }
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                WordGuessBoard(
                    s = s,
                    currentInput = currentInput,
                    revealRow = revealRow,
                    shake = shake,
                    palette = palette,
                    colorblind = colorblind,
                    reducedMotion = reducedMotion,
                    modifier = boardModifier
                )
            }
            val inputZone: @Composable (Modifier) -> Unit = { zoneModifier ->
                WordGuessInputZone(
                    s = s,
                    bestFeedbackByLetter = bestFeedbackByLetter,
                    canSubmit = currentInput.length == WordGuessGameWordLength,
                    showInvalidMessage = showInvalidMessage,
                    announcement = announcement,
                    record = record,
                    isNewBestGuesses = reportedResult?.first ?: false,
                    isNewBestTime = reportedResult?.second ?: false,
                    palette = palette,
                    colorblind = colorblind,
                    onLetter = onLetter,
                    onBackspace = onBackspace,
                    onEnter = onEnter,
                    onNewWord = { startRound(false) },
                    onBackToMenu = { game.leaveSession() },
                    modifier = zoneModifier
                )
            }

            if (sideBySide) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = SIDE_BY_SIDE_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        board(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            modifier = Modifier
                                .width(SIDE_PANE_WIDTH)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            header(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            inputZone(Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                // The board slot is MEASURED, not estimated: the header and the bottom zone are laid
                // out at their natural height first and the slot takes whatever is left (weight with
                // fill = false, so a board that reaches its max tile size stays that size and the
                // whole stack centres). WordGuessBoard sizes itself from this slot with fitBoard and
                // pans if the slot cannot give a MIN_TILE_DP tile.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        header(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        board(Modifier.weight(1f, fill = false).fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        // Capped so a large font scale cannot push the board out of the slot above;
                        // the result panel scrolls inside its own cap instead. The keyboard is a
                        // fixed height and is never capped.
                        inputZone(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (s.isOver) {
                                        Modifier
                                            .heightIn(max = availableHeight * 0.45f)
                                            .verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
    }

    val tierToConfirm = pendingTier
    if (tierToConfirm != null) {
        AlertDialog(
            onDismissRequest = { pendingTier = null },
            containerColor = palette.background,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = { Text("Switch to ${wordGuessTierLabel(tierToConfirm)}?") },
            text = {
                Text(
                    if (dailyWordActive) {
                        "You'll start today's word again with ${wordGuessGuessAllowance(tierToConfirm)} guesses. Your guesses so far are cleared."
                    } else {
                        "This deals a new word with ${wordGuessGuessAllowance(tierToConfirm)} guesses. Your current word and guesses are discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTier = null
                        switchTier(tierToConfirm)
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

/** [WordGuessGame.WORD_LENGTH] under a shorter local name, since it's referenced repeatedly for layout sizing throughout this file. */
private const val WordGuessGameWordLength = WordGuessGame.WORD_LENGTH

/** Below this tile size (dp) the board stops shrinking and pans instead. */
private const val MIN_TILE_DP = 28f

/** Upper bound so a small board does not sprawl across a tablet. */
private const val MAX_TILE_DP = 64f

/** Gap between adjacent tiles, both ways (dp). Folded into [fitBoard]'s cell size, see [WordGuessBoard]. */
private const val TILE_GAP_DP = 6f

/** A rejected word's shake: swing amplitude (dp), the swing positions as multiples of it, and ms per swing (6 x 45 = 270ms). */
private const val SHAKE_AMPLITUDE_DP = 8f
private const val SHAKE_STEP_MS = 45
private val SHAKE_STEPS = floatArrayOf(-1f, 1f, -0.7f, 0.7f, -0.35f, 0f)

/** A submitted row's flip: ms between successive tiles and ms per tile (4 x 45 + 120 = 300ms for the last tile). */
private const val REVEAL_STAGGER_MS = 45
private const val REVEAL_FLIP_MS = 120

/** Portrait content never stretches wider than this, so a tablet in portrait is not a sea of cream. */
private val CONTENT_MAX_WIDTH = 520.dp

/** Wide-window layout: the right-hand column's width, and the cap on the whole board-plus-column row. */
private val SIDE_PANE_WIDTH = 360.dp
private val SIDE_BY_SIDE_MAX_WIDTH = 880.dp

private val KEY_HEIGHT = 48.dp
private val KEY_ROW_GAP = 6.dp

/** Three key rows plus the two gaps between them: 156dp. */
private val KEYBOARD_HEIGHT = KEY_HEIGHT * 3 + KEY_ROW_GAP * 2

/** The reserved line above the keyboard for "Not a valid word", so showing it never shifts the keys. */
private val MESSAGE_SLOT_HEIGHT = 24.dp

/** The whole bottom zone while playing (message line + gap + keyboard); the result panel is at least this tall, so ending a word never resizes the board. */
private val BOTTOM_ZONE_HEIGHT = MESSAGE_SLOT_HEIGHT + 6.dp + KEYBOARD_HEIGHT

/** A key's visible face is inset inside its touch slot by this much, which makes the gap between faces. */
private val KEY_INSET_H = 2.dp
private val KEY_INSET_V = 3.dp

/** A key slot never grows past this on a wide window. */
private val MAX_KEY_SLOT = 52.dp

/** Near-black and cream inks for text and marks on a coloured fill; [wordGuessInkOn] picks by contrast. */
private val WORD_GUESS_INK_DARK = Color(0xFF1A1208)
private val WORD_GUESS_INK_LIGHT = Color(0xFFFFFBF5)

/** CORRECT > PRESENT > ABSENT ranking, used to pick which feedback "wins" for a keyboard key that's appeared in more than one past guess with different results. */
private fun LetterFeedback.rank(): Int = when (this) {
    LetterFeedback.CORRECT -> 2
    LetterFeedback.PRESENT -> 1
    LetterFeedback.ABSENT -> 0
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch -- correct/present/absent are the one deliberate exception, using
// the genre's own well-known green/gold/gray convention rather than warm
// terracotta tones, the same "authentic exception" reasoning Color Flood's
// cell colors / Mastermind's peg colors already use for their own mechanics.
// ---------------------------------------------------------------------------
private data class WordGuessPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color,
    val emptyTile: Color,
    val tileBorder: Color,
    val correct: Color,
    val present: Color,
    val absent: Color,
    val keyDefault: Color
)

@Composable
private fun wordGuessPalette(isDark: Boolean): WordGuessPalette = if (!isDark) {
    WordGuessPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        // Dark ink: cream text on this orange was only ~2.5:1.
        textOnAccent = Color(0xFF2B1F12),
        chipBackground = Color(0xFFE3CBA9),
        emptyTile = Color(0xFFFFFBF5),
        // ~3.2:1 against the background (the old D9C6AA was ~1.5:1, so empty tiles vanished).
        tileBorder = Color(0xFF9C8466),
        correct = Color(0xFF5B9A5B),
        present = Color(0xFFC9A227),
        absent = Color(0xFF8A7B6C),
        keyDefault = Color(0xFFE3CBA9)
    )
} else {
    WordGuessPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E),
        emptyTile = Color(0xFF2A2118),
        // ~3.5:1 against the background (the old 574A3A was ~2:1).
        tileBorder = Color(0xFF7A6C5C),
        correct = Color(0xFF6FBE6F),
        present = Color(0xFFDBBE55),
        absent = Color(0xFF9B8D7E),
        keyDefault = Color(0xFF453A2E)
    )
}

private fun WordGuessPalette.colorFor(feedback: LetterFeedback): Color = when (feedback) {
    LetterFeedback.CORRECT -> correct
    LetterFeedback.PRESENT -> present
    LetterFeedback.ABSENT -> absent
}

private fun wordGuessContrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Whichever of near-black / cream reads better on [background] (the gold and amber fills failed with cream). */
private fun wordGuessInkOn(background: Color): Color =
    if (wordGuessContrast(background, WORD_GUESS_INK_LIGHT) >= wordGuessContrast(background, WORD_GUESS_INK_DARK)) {
        WORD_GUESS_INK_LIGHT
    } else {
        WORD_GUESS_INK_DARK
    }

// ---------------------------------------------------------------------------
// Text and screen-reader helpers
// ---------------------------------------------------------------------------

private fun wordGuessTierLabel(tier: CpuDifficulty): String = when (tier) {
    CpuDifficulty.EASY -> "Easy"
    CpuDifficulty.MEDIUM -> "Medium"
    CpuDifficulty.HARD -> "Hard"
}

/** Mirrors the engine's own difficulty table (EASY 8, MEDIUM 6, HARD 5) for the switch-confirm copy. */
private fun wordGuessGuessAllowance(tier: CpuDifficulty): Int = when (tier) {
    CpuDifficulty.EASY -> 8
    CpuDifficulty.MEDIUM -> 6
    CpuDifficulty.HARD -> 5
}

private fun formatWordGuessClock(millis: Long): String {
    val minutes = (millis / 1000) / 60
    val seconds = (millis / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}

/** What one letter's feedback means, in words, for screen readers. */
private fun wordGuessFeedbackPhrase(feedback: LetterFeedback): String = when (feedback) {
    LetterFeedback.CORRECT -> "correct"
    LetterFeedback.PRESENT -> "in word, wrong spot"
    LetterFeedback.ABSENT -> "not in word"
}

/** The whole row's result as one sentence, announced through a polite live region after each valid guess. */
private fun wordGuessGuessSummary(entry: WordGuessEntry, number: Int, maxGuesses: Int): String =
    "Guess $number of $maxGuesses: " + entry.word.indices.joinToString(", ") { i ->
        "${entry.word[i].uppercaseChar()} ${wordGuessFeedbackPhrase(entry.feedback[i])}"
    }

// ---------------------------------------------------------------------------
// Header: tier tabs, status row, clock
// ---------------------------------------------------------------------------

/**
 * The tab row, then the status row. The tab row is the top-right occupant of the layout, so it
 * keeps [GameChromeEndInset] free at its end for the corner menu button.
 */
@Composable
private fun WordGuessHeader(
    current: CpuDifficulty,
    dailyActive: Boolean,
    guessesUsed: Int,
    maxGuesses: Int,
    game: WordGuessGame,
    palette: WordGuessPalette,
    onSelectTier: (CpuDifficulty) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        DifficultyTabsWordGuess(
            current = current,
            palette = palette,
            onSelect = onSelectTier,
            modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)
        )
        Spacer(Modifier.height(4.dp))
        WordGuessStatusRow(
            dailyActive = dailyActive,
            guessesUsed = guessesUsed,
            maxGuesses = maxGuesses,
            game = game,
            palette = palette
        )
    }
}

@Composable
private fun DifficultyTabsWordGuess(
    current: CpuDifficulty,
    palette: WordGuessPalette,
    onSelect: (CpuDifficulty) -> Unit,
    modifier: Modifier = Modifier
) {
    // horizontalScroll is the fallback for a window too narrow for three chips plus large text.
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tier in CpuDifficulty.entries) {
            WordGuessTab(
                label = wordGuessTierLabel(tier),
                selected = tier == current,
                palette = palette,
                onClick = { onSelect(tier) }
            )
        }
    }
}

/** A pill chip with a 48dp-tall touch target around the pill, announced as a radio button. */
@Composable
private fun WordGuessTab(label: String, selected: Boolean, palette: WordGuessPalette, onClick: () -> Unit) {
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

@Composable
private fun WordGuessStatusRow(
    dailyActive: Boolean,
    guessesUsed: Int,
    maxGuesses: Int,
    game: WordGuessGame,
    palette: WordGuessPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (dailyActive) "Today's word · Guesses: $guessesUsed / $maxGuesses" else "Guesses: $guessesUsed / $maxGuesses",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp)
                .semantics {
                    contentDescription = (if (dailyActive) "Today's word. " else "") + "$guessesUsed of $maxGuesses guesses used"
                }
        )
        WordGuessClock(game = game, palette = palette)
    }
}

/**
 * The live "M:SS" clock. Owns its own tick state so the 5Hz update recomposes only this Text, not
 * the whole screen and every tile. Reads [WordGuessGame.activeElapsedMillis], so it freezes while
 * the engine is paused (app backgrounded) and ends on exactly the recorded round time.
 */
@Composable
private fun WordGuessClock(game: WordGuessGame, palette: WordGuessPalette) {
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
        formatWordGuessClock(elapsed),
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold
    )
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

/**
 * The guess grid: [WordGuessState.maxGuesses] rows of [WordGuessGameWordLength] tiles. Sizes from
 * THIS slot's own measured space with [fitBoard], which never returns a footprint larger than the
 * space it is given, so the old fixed 44dp tiles (and any floor that could exceed the container)
 * are gone. Each tile plus its trailing gap is one fitBoard cell, and one gap is added back to the
 * available space, because n tiles with n - 1 gaps need `n * (tile + gap) - gap`. The tile size is
 * floored to a whole dp so rounding can never push the last column past the slot. A slot that
 * cannot give a [MIN_TILE_DP] tile keeps that size and pans in both axes instead.
 */
@Composable
private fun WordGuessBoard(
    s: WordGuessState,
    currentInput: String,
    revealRow: Int,
    shake: Animatable<Float, AnimationVector1D>,
    palette: WordGuessPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val fit = fitBoard(
            availableWidthPx = maxWidth.value + TILE_GAP_DP,
            availableHeightPx = maxHeight.value + TILE_GAP_DP,
            columns = WordGuessGameWordLength,
            rows = s.maxGuesses,
            minCellPx = MIN_TILE_DP + TILE_GAP_DP,
            maxCellPx = MAX_TILE_DP + TILE_GAP_DP
        )
        val scrolls = !fit.meetsMinimum
        val tileSize: Dp = if (scrolls) MIN_TILE_DP.dp else floor(fit.cellPx - TILE_GAP_DP).coerceAtLeast(1f).dp
        Box(modifier = if (scrolls) Modifier.verticalScroll(vScroll).horizontalScroll(hScroll) else Modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)) {
                for (row in 0 until s.maxGuesses) {
                    val submitted = s.guesses.getOrNull(row)
                    val active = submitted == null && row == s.guesses.size && !s.isOver
                    WordGuessRow(
                        rowIndex = row,
                        letters = submitted?.word ?: if (active) currentInput else "",
                        feedback = submitted?.feedback,
                        isActive = active,
                        cursorColumn = if (active && currentInput.length < WordGuessGameWordLength) currentInput.length else -1,
                        tileSize = tileSize,
                        animateReveal = !reducedMotion && row == revealRow,
                        shake = if (active) shake else null,
                        palette = palette,
                        colorblind = colorblind
                    )
                }
            }
        }
    }
}

/**
 * One row of [WordGuessGameWordLength] letter tiles — a submitted guess (colored by [feedback]), the
 * row being typed ([isActive], neutral tiles, [cursorColumn] marking the next cell to fill), or an
 * unused row. [letters] may be shorter than the row (letters typed so far). [shake] is non-null only
 * for the active row, and is read in the draw phase so a rejected word's swing never recomposes.
 */
@Composable
private fun WordGuessRow(
    rowIndex: Int,
    letters: String,
    feedback: List<LetterFeedback>?,
    isActive: Boolean,
    cursorColumn: Int,
    tileSize: Dp,
    animateReveal: Boolean,
    shake: Animatable<Float, AnimationVector1D>?,
    palette: WordGuessPalette,
    colorblind: Boolean
) {
    Row(
        modifier = if (shake != null) Modifier.graphicsLayer { translationX = shake.value } else Modifier,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_DP.dp)
    ) {
        for (col in 0 until WordGuessGameWordLength) {
            WordGuessTile(
                letter = letters.getOrNull(col)?.takeIf { it != ' ' },
                feedback = feedback?.getOrNull(col),
                row = rowIndex,
                col = col,
                isActiveRow = isActive,
                isCursor = col == cursorColumn,
                tileSize = tileSize,
                revealDelayMs = col * REVEAL_STAGGER_MS,
                animateReveal = animateReveal,
                palette = palette,
                colorblind = colorblind
            )
        }
    }
}

/**
 * One tile. Visual states: unused (>= 3:1 border), cursor (the next cell to type into, heavier
 * border), typed (letter, dark border), revealed (filled by [feedback], ink chosen by contrast).
 * With [colorblind], a revealed tile also draws a corner mark (see [drawFeedbackMark]). With
 * [animateReveal] the tile flips in after [revealDelayMs]: it turns edge-on looking like a typed
 * tile, then turns back showing its result; a fresh [Animatable] per (revealed, animate) change
 * means the first frame is already at the start of the flip, so the result never flashes early.
 *
 * Screen reader: letter and state, then 1-indexed row and column (same phrasing as
 * ReversiScreen.CellView); tiles in unused rows are hidden from it.
 */
@Composable
private fun WordGuessTile(
    letter: Char?,
    feedback: LetterFeedback?,
    row: Int,
    col: Int,
    isActiveRow: Boolean,
    isCursor: Boolean,
    tileSize: Dp,
    revealDelayMs: Int,
    animateReveal: Boolean,
    palette: WordGuessPalette,
    colorblind: Boolean
) {
    val flip = remember(feedback != null, animateReveal) {
        Animatable(if (feedback != null && animateReveal) 0f else 1f)
    }
    LaunchedEffect(flip) {
        if (flip.value < 1f) {
            delay(revealDelayMs.toLong())
            flip.animateTo(1f, animationSpec = tween(durationMillis = REVEAL_FLIP_MS, easing = LinearEasing))
        }
    }
    val progress = flip.value
    val showFeedback = feedback != null && progress >= 0.5f
    // 0 -> 90 degrees (edge-on) in the first half, then -90 -> 0 in the second.
    val angle = if (progress < 0.5f) 180f * progress else 180f * progress - 180f

    val fill = if (showFeedback && feedback != null) palette.colorFor(feedback) else palette.emptyTile
    val ink = if (showFeedback) wordGuessInkOn(fill) else palette.textPrimary
    val borderColor = when {
        showFeedback -> fill
        letter != null -> palette.textPrimary
        isCursor -> palette.textPrimary
        else -> palette.tileBorder
    }
    val borderWidth = if (isCursor && letter == null) 3.dp else 2.dp

    val description = remember(letter, feedback, row, col, isActiveRow) {
        val where = "row ${row + 1}, column ${col + 1}"
        when {
            letter != null && feedback != null -> "${letter.uppercaseChar()}, ${wordGuessFeedbackPhrase(feedback)}, $where"
            letter != null -> "${letter.uppercaseChar()}, typed, $where"
            isActiveRow -> "Empty, $where"
            else -> null
        }
    }

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(tileSize)
            .graphicsLayer {
                rotationX = angle
                cameraDistance = 12f * density
            }
            .clip(shape)
            .background(fill)
            .border(borderWidth, borderColor, shape)
            .then(
                if (description != null) {
                    Modifier.clearAndSetSemantics { contentDescription = description }
                } else {
                    Modifier.clearAndSetSemantics {}
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            Text(
                letter.uppercaseChar().toString(),
                color = ink,
                fontWeight = FontWeight.Bold,
                fontSize = (tileSize.value * 0.5f).sp
            )
        }
        if (colorblind && showFeedback && feedback != null) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size((tileSize * 0.26f).coerceAtLeast(8.dp))
            ) {
                drawFeedbackMark(feedback, ink)
            }
        }
    }
}

/**
 * Colorblind-mode mark for one letter's result, drawn to fill the Canvas it is called in: a check
 * (correct spot), a ring (in the word, elsewhere) or a cross (not in the word). Shape, not hue, so
 * it survives every colour-vision type; [ink] is whatever already contrasts with the fill.
 */
private fun DrawScope.drawFeedbackMark(feedback: LetterFeedback, ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.16f).coerceAtLeast(1.5f)
    when (feedback) {
        LetterFeedback.CORRECT -> {
            val check = Path().apply {
                moveTo(w * 0.06f, h * 0.55f)
                lineTo(w * 0.38f, h * 0.88f)
                lineTo(w * 0.94f, h * 0.14f)
            }
            drawPath(check, color = ink, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        LetterFeedback.PRESENT -> drawCircle(
            color = ink,
            radius = (w * 0.5f - stroke / 2f).coerceAtLeast(1f),
            center = Offset(w / 2f, h / 2f),
            style = Stroke(width = stroke)
        )
        LetterFeedback.ABSENT -> {
            drawLine(color = ink, start = Offset(w * 0.1f, h * 0.1f), end = Offset(w * 0.9f, h * 0.9f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = ink, start = Offset(w * 0.9f, h * 0.1f), end = Offset(w * 0.1f, h * 0.9f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom zone: message line + keyboard, or the finished panel
// ---------------------------------------------------------------------------

/**
 * While the word is undecided: the reserved message line (a polite live region carrying the last
 * guess's result or "Not a valid word") over the keyboard. Once decided: the result panel, at least
 * as tall as that whole zone, so the board above never changes size when a word ends.
 */
@Composable
private fun WordGuessInputZone(
    s: WordGuessState,
    bestFeedbackByLetter: Map<Char, LetterFeedback>,
    canSubmit: Boolean,
    showInvalidMessage: Boolean,
    announcement: String,
    record: WordGuessRecord?,
    isNewBestGuesses: Boolean,
    isNewBestTime: Boolean,
    palette: WordGuessPalette,
    colorblind: Boolean,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onNewWord: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!s.isOver) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MESSAGE_SLOT_HEIGHT)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (announcement.isNotEmpty()) contentDescription = announcement
                    },
                contentAlignment = Alignment.Center
            ) {
                if (showInvalidMessage) {
                    Text(
                        "Not a valid word",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            WordGuessKeyboard(
                bestFeedbackByLetter = bestFeedbackByLetter,
                canSubmit = canSubmit,
                palette = palette,
                colorblind = colorblind,
                onLetter = onLetter,
                onBackspace = onBackspace,
                onEnter = onEnter
            )
        }
    } else {
        WordGuessFinishedPanel(
            solved = s.solved,
            secret = s.secret,
            guessCount = s.guesses.size,
            record = record,
            isNewBestGuesses = isNewBestGuesses,
            isNewBestTime = isNewBestTime,
            onNewWord = onNewWord,
            onBackToMenu = onBackToMenu,
            palette = palette,
            modifier = modifier
        )
    }
}

private val KEYBOARD_ROWS = listOf(
    "QWERTYUIOP",
    "ASDFGHJKL",
    "ZXCVBNM"
)

/** Enter/Backspace are drawn wider than a letter key -- same real on-screen-QWERTY convention
 *  every mobile keyboard (incl. the genre this screen is explicitly modeled on) uses, and the
 *  exact multiplier that keeps the bottom row's own total width (7 letters + these 2 action
 *  keys = 10 slots) from ever exceeding row 1's (10 letters = 10 slots). */
private const val ACTION_KEY_WIDTH_MULTIPLIER = 1.5f

/**
 * Sizes every key from the ACTUAL available width (via [BoxWithConstraints]/`maxWidth`), so the
 * rows never overflow off-screen on any device (the Fold 5 cover screen is the narrowest real
 * target). The widest row (10 letters) is divided into equal key SLOTS with no gap between them;
 * each key's visible face is inset inside its slot ([KEY_INSET_H]), so the gaps between faces are
 * still touchable and a near-miss lands on a key instead of dead space. Height is the 48dp touch
 * floor. At 288dp of width a slot is 28.8dp wide (26dp of face); width cannot honour 48dp (10 keys
 * would need 480dp+), the same trade every real on-screen keyboard makes. All three rows share one
 * slot width so this reads as one keyboard, and slots stop growing at [MAX_KEY_SLOT].
 */
@Composable
private fun WordGuessKeyboard(
    bestFeedbackByLetter: Map<Char, LetterFeedback>,
    canSubmit: Boolean,
    palette: WordGuessPalette,
    colorblind: Boolean,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widestRowKeyCount = KEYBOARD_ROWS.maxOf { it.length } // 10, from row 1 (QWERTYUIOP)
        val slot = (maxWidth / widestRowKeyCount).coerceAtMost(MAX_KEY_SLOT)
        val actionSlot = slot * ACTION_KEY_WIDTH_MULTIPLIER

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KEY_ROW_GAP)
        ) {
            for ((rowIndex, row) in KEYBOARD_ROWS.withIndex()) {
                Row {
                    if (rowIndex == 2) {
                        WordGuessActionKey(
                            description = "Enter",
                            clickLabel = "Submit guess",
                            enabled = canSubmit,
                            width = actionSlot,
                            palette = palette,
                            onClick = onEnter
                        ) { keyInk ->
                            Text(
                                "Enter",
                                color = keyInk,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clearAndSetSemantics {}
                            )
                        }
                    }
                    for (letter in row) {
                        WordGuessLetterKey(
                            letter = letter,
                            feedback = bestFeedbackByLetter[letter.lowercaseChar()],
                            slot = slot,
                            palette = palette,
                            colorblind = colorblind,
                            onLetter = onLetter
                        )
                    }
                    if (rowIndex == 2) {
                        WordGuessActionKey(
                            description = "Delete",
                            clickLabel = "Delete last letter",
                            enabled = true,
                            width = actionSlot,
                            palette = palette,
                            onClick = onBackspace
                        ) { keyInk ->
                            Canvas(modifier = Modifier.size(width = 24.dp, height = 20.dp)) {
                                drawBackspaceIcon(keyInk)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One letter key: a full-slot, 48dp-tall button whose description carries the letter's best-known result. */
@Composable
private fun WordGuessLetterKey(
    letter: Char,
    feedback: LetterFeedback?,
    slot: Dp,
    palette: WordGuessPalette,
    colorblind: Boolean,
    onLetter: (Char) -> Unit
) {
    val fill = if (feedback != null) palette.colorFor(feedback) else palette.keyDefault
    val ink = if (feedback != null) wordGuessInkOn(fill) else palette.textPrimary
    val description = remember(letter, feedback) {
        if (feedback == null) "Letter $letter" else "Letter $letter, ${wordGuessFeedbackPhrase(feedback)}"
    }
    Box(
        modifier = Modifier
            .width(slot)
            .height(KEY_HEIGHT)
            .clickable(onClickLabel = "Type letter", role = Role.Button) { onLetter(letter.lowercaseChar()) }
            .semantics { contentDescription = description }
            .padding(horizontal = KEY_INSET_H, vertical = KEY_INSET_V)
            .clip(RoundedCornerShape(6.dp))
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter.toString(),
            color = ink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {}
        )
        if (colorblind && feedback != null) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(7.dp)
            ) {
                drawFeedbackMark(feedback, ink)
            }
        }
    }
}

/** Enter / Backspace: [width] wide, 48dp tall; [content] receives the ink color to draw with. */
@Composable
private fun WordGuessActionKey(
    description: String,
    clickLabel: String,
    enabled: Boolean,
    width: Dp,
    palette: WordGuessPalette,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Color) -> Unit
) {
    val fill = if (enabled) palette.accent else palette.chipBackground
    val ink = if (enabled) palette.textOnAccent else palette.textPrimary.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .width(width)
            .height(KEY_HEIGHT)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = KEY_INSET_H, vertical = KEY_INSET_V)
            .clip(RoundedCornerShape(6.dp))
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        content(ink)
    }
}

/** A left-pointing "erase" tag with a cross, drawn to fill its Canvas (replaces a font-dependent glyph). */
private fun DrawScope.drawBackspaceIcon(ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = (w * 0.09f).coerceAtLeast(1.5f)
    val outline = Path().apply {
        moveTo(w * 0.34f, h * 0.10f)
        lineTo(w * 0.94f, h * 0.10f)
        lineTo(w * 0.94f, h * 0.90f)
        lineTo(w * 0.34f, h * 0.90f)
        lineTo(w * 0.04f, h * 0.50f)
        close()
    }
    drawPath(outline, color = ink, style = Stroke(width = stroke, join = StrokeJoin.Round))
    drawLine(color = ink, start = Offset(w * 0.52f, h * 0.34f), end = Offset(w * 0.76f, h * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color = ink, start = Offset(w * 0.76f, h * 0.34f), end = Offset(w * 0.52f, h * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------------------
// Result panel
// ---------------------------------------------------------------------------

@Composable
private fun WordGuessFinishedPanel(
    solved: Boolean,
    secret: String,
    guessCount: Int,
    record: WordGuessRecord?,
    isNewBestGuesses: Boolean,
    isNewBestTime: Boolean,
    onNewWord: () -> Unit,
    onBackToMenu: () -> Unit,
    palette: WordGuessPalette,
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
                .heightIn(min = BOTTOM_ZONE_HEIGHT)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (solved) {
                Text(
                    "You got it!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
                    Text("Best time: ${formatWordGuessClock(record.bestTimeMillis)}", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "Out of guesses",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Spacer(Modifier.height(8.dp))
                Text("The word was: ${secret.uppercase()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onNewWord,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.textPrimary, contentColor = palette.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("New Word", textAlign = TextAlign.Center) }
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
