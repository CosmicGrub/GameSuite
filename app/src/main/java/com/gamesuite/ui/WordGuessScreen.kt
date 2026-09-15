package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.wordguess.LetterFeedback
import com.gamesuite.games.wordguess.WordGuessGame
import com.gamesuite.games.wordguess.WordGuessRecord
import com.gamesuite.games.wordguess.WordGuessStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.victoryGlow
import kotlinx.coroutines.delay

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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { WordGuessStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = wordGuessPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.loadWordData(androidContext)
        game.difficulty = settings.defaultCpuDifficulty
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        game.startMatch(dailySeed)
    }

    val s = state ?: return

    val allRecords by statsStore.records.collectAsState(initial = emptyMap())
    val record = allRecords[game.difficulty.name]
    // Keyed on the actual secret (unique per round, stable across guesses of the SAME round) --
    // the same fix Edge Match's own reportedResult needed after a real bug was found there.
    var reportedResult by remember(game.difficulty, s.secret) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var currentInput by remember(game.difficulty, s.secret) { mutableStateOf("") }
    var showInvalidMessage by remember(game.difficulty, s.secret) { mutableStateOf(false) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.secret, s.guesses.isEmpty()) { mutableStateOf(0L) }
    LaunchedEffect(game.timerStartElapsedRealtime.value, s.isOver) {
        val start = game.timerStartElapsedRealtime.value
        if (start == null) {
            liveElapsedMillis = 0L
            return@LaunchedEffect
        }
        while (!s.isOver) {
            liveElapsedMillis = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }

    LaunchedEffect(s.solved) {
        if (s.solved && reportedResult == null) {
            val finalTime = game.finishedElapsedMillis.value ?: liveElapsedMillis
            val result = statsStore.recordSolve(game.difficulty, s.guesses.size, finalTime)
            reportedResult = result.isNewBestGuesses to result.isNewBestTimeMillis
            haptics(HapticSignal.CELEBRATION)
        }
    }

    var reportedLoss by remember(game.difficulty, s.secret) { mutableStateOf(false) }
    LaunchedEffect(s.outOfGuesses) {
        if (s.outOfGuesses && !reportedLoss) {
            reportedLoss = true
            haptics(HapticSignal.FAILURE)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            // AGSL deepening: same shared "big win" glow as MastermindScreen, timed to
            // the same s.solved moment the CELEBRATION haptic above fires from, gated
            // on reducedMotion to match UnoScreen's ConfettiOverlay precedent.
            .victoryGlow(trigger = s.solved && !settings.reducedMotion, tint = palette.accent)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabsWordGuess(
            current = game.difficulty,
            palette = palette,
            onSelect = { tier ->
                if (tier != game.difficulty) {
                    game.difficulty = tier
                    game.startMatch()
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        WordGuessStatusRow(
            guessesUsed = s.guesses.size,
            maxGuesses = s.maxGuesses,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until s.maxGuesses) {
                when {
                    row < s.guesses.size -> WordGuessRow(letters = s.guesses[row].word, feedback = s.guesses[row].feedback, palette = palette)
                    row == s.guesses.size && !s.isOver -> WordGuessRow(letters = currentInput.padEnd(WordGuessGameWordLength, ' '), feedback = null, palette = palette)
                    else -> WordGuessRow(letters = " ".repeat(WordGuessGameWordLength), feedback = null, palette = palette)
                }
            }
        }

        if (showInvalidMessage) {
            Spacer(Modifier.height(8.dp))
            Text("Not a valid word", color = palette.absent, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        if (!s.isOver) {
            WordGuessKeyboard(
                bestFeedbackByLetter = bestFeedbackByLetter,
                canSubmit = currentInput.length == WordGuessGameWordLength,
                palette = palette,
                onLetter = { letter ->
                    if (currentInput.length < WordGuessGameWordLength) {
                        currentInput += letter
                        showInvalidMessage = false
                        haptics(HapticSignal.LIGHT_TICK)
                    }
                },
                onBackspace = {
                    if (currentInput.isNotEmpty()) {
                        currentInput = currentInput.dropLast(1)
                        showInvalidMessage = false
                    }
                },
                onEnter = {
                    if (currentInput.length == WordGuessGameWordLength) {
                        val before = s.guesses.size
                        game.submitGuess(currentInput)
                        if (game.state.value?.guesses?.size == before) {
                            showInvalidMessage = true
                            haptics(HapticSignal.FAILURE)
                        } else {
                            currentInput = ""
                            sounds.playTap()
                            haptics(HapticSignal.NORMAL_ACTION)
                        }
                    }
                }
            )
        } else {
            WordGuessFinishedPanel(
                solved = s.solved,
                secret = s.secret,
                guessCount = s.guesses.size,
                record = record,
                isNewBestGuesses = reportedResult?.first ?: false,
                isNewBestTime = reportedResult?.second ?: false,
                onNewWord = { game.startMatch() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
    }
}

/** [WordGuessGame.WORD_LENGTH] under a shorter local name, since it's referenced repeatedly for layout sizing throughout this file. */
private const val WordGuessGameWordLength = WordGuessGame.WORD_LENGTH

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
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9),
        emptyTile = Color(0xFFFFFBF5),
        tileBorder = Color(0xFFD9C6AA),
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
        tileBorder = Color(0xFF574A3A),
        correct = Color(0xFF6FBE6F),
        present = Color(0xFFDBBE55),
        absent = Color(0xFF9B8D7E),
        keyDefault = Color(0xFF453A2E)
    )
}

@Composable
private fun DifficultyTabsWordGuess(current: CpuDifficulty, palette: WordGuessPalette, onSelect: (CpuDifficulty) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tier in CpuDifficulty.entries) {
            val selected = tier == current
            val label = when (tier) {
                CpuDifficulty.EASY -> "Easy"
                CpuDifficulty.MEDIUM -> "Medium"
                CpuDifficulty.HARD -> "Hard"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.accent else palette.chipBackground)
                    .clickable { onSelect(tier) }
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
}

@Composable
private fun WordGuessStatusRow(guessesUsed: Int, maxGuesses: Int, elapsedMillis: Long, palette: WordGuessPalette) {
    val minutes = (elapsedMillis / 1000) / 60
    val seconds = (elapsedMillis / 1000) % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Guesses: $guessesUsed / $maxGuesses", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Text("%d:%02d".format(minutes, seconds), color = palette.textPrimary, fontWeight = FontWeight.Bold)
    }
}

/** One row of [WordGuessGameWordLength] letter tiles — a submitted guess (colored by [feedback]) or the in-progress/empty row (null feedback, neutral tile). [letters] is padded with spaces up to the fixed width so every row lines up regardless of how many letters have been typed so far. */
@Composable
private fun WordGuessRow(letters: String, feedback: List<LetterFeedback>?, palette: WordGuessPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until WordGuessGameWordLength) {
            val letter = letters.getOrNull(i)?.takeIf { it != ' ' }
            val bg = when (feedback?.getOrNull(i)) {
                LetterFeedback.CORRECT -> palette.correct
                LetterFeedback.PRESENT -> palette.present
                LetterFeedback.ABSENT -> palette.absent
                null -> palette.emptyTile
            }
            val fg = if (feedback != null) palette.textOnAccent else palette.textPrimary
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                if (letter != null) {
                    Text(letter.uppercase(), color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

private val KEYBOARD_ROWS = listOf(
    "QWERTYUIOP",
    "ASDFGHJKL",
    "ZXCVBNM"
)

/** Gap between adjacent keys in every row -- pulled out to a named value since both the layout
 *  below and its own width math (see [WordGuessKeyboard]'s KDoc) need the exact same number. */
private val KEYBOARD_KEY_GAP = 4.dp

/** Enter/Backspace are drawn wider than a letter key -- same real on-screen-QWERTY convention
 *  every mobile keyboard (incl. the genre this screen is explicitly modeled on) uses, and the
 *  exact multiplier that keeps the bottom row's own total width (7 letters + these 2 action
 *  keys) from ever exceeding row 1's (10 letters), see [WordGuessKeyboard]'s KDoc. */
private const val ACTION_KEY_WIDTH_MULTIPLIER = 1.5f

/**
 * This app's established "never let a tappable element shrink below a usable size" floor (see
 * e.g. ChessScreen/CheckersScreen/MancalaScreen's own `MIN_TOUCH_TARGET`) -- applied here to key
 * HEIGHT (which has the room to honor it safely on every real screen) rather than width. Width
 * can NOT honor the same floor: 10 keys at 48dp each plus 9 gaps needs ~516dp, wider than almost
 * any real phone in portrait, so a hard 48dp floor on width would just reintroduce the overflow
 * this file's own keyboard-fit fix removes. Every real on-screen QWERTY keyboard (this app's own
 * included) makes the same trade -- keys narrower than the "ideal" touch target, height held to
 * it -- because a horizontal near-miss just lands on the adjacent letter (cheap, recoverable),
 * while a cramped row height is what actually costs mis-taps.
 */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Sizes every key from the ACTUAL available width (via [BoxWithConstraints]/`maxWidth`) instead
 * of a fixed dp value, so the row never overflows off-screen regardless of how narrow the real
 * device is (e.g. a folded cover screen at ~344dp available) -- the bug this replaces: fixed
 * 32dp keys + 4dp gaps needed 10*32 + 9*4 = 356dp for row 1 alone, which didn't fit a 344dp-wide
 * screen, pushing the P key (and, in row 3, the trailing Backspace key) off-screen with no way
 * to reach them.
 *
 * All three rows share the SAME per-key width -- sized off the WIDEST row (row 1's 10 letters)
 * -- so this reads as one keyboard, not three differently-scaled ones, matching real
 * physical/software QWERTY convention. Row 3 (7 letters + Enter + Backspace) is kept from ever
 * exceeding row 1's own width by drawing Enter/Backspace at [ACTION_KEY_WIDTH_MULTIPLIER] (1.5x)
 * a letter key's width -- the same "wider action keys" real mobile keyboards use -- which makes
 * row 3's own total width 7 + 1.5 + 1.5 = 10 key-widths, worth of gaps: never more than row 1's.
 *
 * Worked example at a 344dp-available width (this app's own narrowest real target, the Fold 5
 * cover screen -- see this file's own KDoc): keyWidth = (344dp - 9*4dp) / 10 = (344-36)/10 =
 * 30.8dp. Row 1's total = 10*30.8 + 9*4 = 308 + 36 = 344dp -- fits exactly. Row 3's total =
 * 7*30.8 + 2*(1.5*30.8) + 8*4 = 215.6 + 92.4 + 32 = 340dp -- fits with room to spare (one fewer
 * gap than row 1). Row 2 (9 letters) fits with even more room. No key is ever cut off.
 */
@Composable
private fun WordGuessKeyboard(
    bestFeedbackByLetter: Map<Char, LetterFeedback>,
    canSubmit: Boolean,
    palette: WordGuessPalette,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widestRowKeyCount = KEYBOARD_ROWS.maxOf { it.length } // 10, from row 1 (QWERTYUIOP)
        val totalGapWidth = KEYBOARD_KEY_GAP * (widestRowKeyCount - 1)
        // Capped AT MOST MIN_TOUCH_TARGET so keys don't balloon absurdly large on a wide tablet
        // -- see MIN_TOUCH_TARGET's own KDoc for why a LOWER floor isn't safe to apply here.
        val keyWidth = ((maxWidth - totalGapWidth) / widestRowKeyCount).coerceAtMost(MIN_TOUCH_TARGET)
        val actionKeyWidth = keyWidth * ACTION_KEY_WIDTH_MULTIPLIER

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((rowIndex, row) in KEYBOARD_ROWS.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(KEYBOARD_KEY_GAP)) {
                    if (rowIndex == 2) {
                        KeyboardActionKey(label = "Enter", enabled = canSubmit, width = actionKeyWidth, palette = palette, onClick = onEnter)
                    }
                    for (letter in row) {
                        val feedback = bestFeedbackByLetter[letter.lowercaseChar()]
                        val bg = when (feedback) {
                            LetterFeedback.CORRECT -> palette.correct
                            LetterFeedback.PRESENT -> palette.present
                            LetterFeedback.ABSENT -> palette.absent
                            null -> palette.keyDefault
                        }
                        val fg = if (feedback != null) palette.textOnAccent else palette.textPrimary
                        Box(
                            modifier = Modifier
                                .width(keyWidth)
                                .height(MIN_TOUCH_TARGET)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .clickable { onLetter(letter.lowercaseChar()) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(letter.toString(), color = fg, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (rowIndex == 2) {
                        KeyboardActionKey(label = "⌫", enabled = true, width = actionKeyWidth, palette = palette, onClick = onBackspace)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardActionKey(label: String, enabled: Boolean, width: Dp, palette: WordGuessPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(width)
            .height(MIN_TOUCH_TARGET)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) palette.accent else palette.chipBackground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) palette.textOnAccent else palette.textPrimary.copy(alpha = 0.4f),
            fontWeight = FontWeight.Bold
        )
    }
}

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
    palette: WordGuessPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (solved) {
                Text("You got it!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Guesses: $guessCount", style = MaterialTheme.typography.bodyMedium)
                if (isNewBestGuesses) {
                    Spacer(Modifier.height(4.dp))
                    Text("New best guess count!", color = palette.accent, fontWeight = FontWeight.Bold)
                } else if (record?.bestGuesses != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Best guesses: ${record.bestGuesses}", style = MaterialTheme.typography.bodyMedium)
                }
                if (isNewBestTime) {
                    Spacer(Modifier.height(4.dp))
                    Text("New best time!", color = palette.accent, fontWeight = FontWeight.Bold)
                } else if (record?.bestTimeMillis != null) {
                    Spacer(Modifier.height(4.dp))
                    val m = (record.bestTimeMillis / 1000) / 60
                    val sec = (record.bestTimeMillis / 1000) % 60
                    Text("Best time: %d:%02d".format(m, sec), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text("Out of guesses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("The word was: ${secret.uppercase()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewWord) { Text("New Word") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
