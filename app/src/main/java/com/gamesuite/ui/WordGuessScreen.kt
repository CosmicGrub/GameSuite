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

@Composable
private fun WordGuessKeyboard(
    bestFeedbackByLetter: Map<Char, LetterFeedback>,
    canSubmit: Boolean,
    palette: WordGuessPalette,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((rowIndex, row) in KEYBOARD_ROWS.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rowIndex == 2) {
                    KeyboardActionKey(label = "Enter", enabled = canSubmit, palette = palette, onClick = onEnter)
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
                            .width(32.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .clickable { onLetter(letter.lowercaseChar()) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(letter.toString(), color = fg, fontWeight = FontWeight.Bold)
                    }
                }
                if (rowIndex == 2) {
                    KeyboardActionKey(label = "⌫", enabled = true, palette = palette, onClick = onBackspace)
                }
            }
        }
    }
}

@Composable
private fun KeyboardActionKey(label: String, enabled: Boolean, palette: WordGuessPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) palette.accent else palette.chipBackground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
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
