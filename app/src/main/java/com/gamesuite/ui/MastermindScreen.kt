package com.gamesuite.ui

import android.os.SystemClock
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
import com.gamesuite.games.mastermind.MastermindGame
import com.gamesuite.games.mastermind.MastermindGuess
import com.gamesuite.games.mastermind.MastermindRecord
import com.gamesuite.games.mastermind.MastermindStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.SettingsViewModel
import kotlinx.coroutines.delay

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
 * panel once the round is over (solved OR out of guesses).
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
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val statsStore = remember { MastermindStatsStore(androidContext) }
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val palette = mastermindPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
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
    // same fix Edge Match's own reportedResult needed after a real bug was found there (keying
    // only on difficulty/size stayed constant across "New Puzzle" clicks in the same tier and so
    // only ever recorded the FIRST solve). Built in here from the start rather than discovered
    // after the fact.
    var reportedResult by remember(game.difficulty, s.secret) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var currentGuess by remember(game.difficulty, s.secret) { mutableStateOf(List<Int?>(s.positions) { null }) }
    var reportedLoss by remember(game.difficulty, s.secret) { mutableStateOf(false) }

    // Live "Time: M:SS" display -- same idiom as every other solo puzzle's own live-timer LaunchedEffect.
    var liveElapsedMillis by remember(s.positions, s.guesses.isEmpty()) { mutableStateOf(0L) }
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

    LaunchedEffect(s.outOfGuesses) {
        if (s.outOfGuesses && !reportedLoss) {
            reportedLoss = true
            haptics(HapticSignal.FAILURE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DifficultyTabsMastermind(
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

        MastermindStatusRow(
            guessesUsed = s.guesses.size,
            maxGuesses = s.maxGuesses,
            elapsedMillis = game.finishedElapsedMillis.value ?: liveElapsedMillis,
            palette = palette
        )

        Spacer(Modifier.height(14.dp))

        if (!s.isOver) {
            Text("Your guess", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until s.positions) {
                    PegSlot(
                        color = currentGuess[i]?.let { palette.pegColors[it] },
                        palette = palette,
                        onTap = {
                            if (currentGuess[i] != null) {
                                currentGuess = currentGuess.toMutableList().apply { this[i] = null }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Pick a color", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (c in 0 until s.colorCount) {
                    ColorSwatch(
                        color = palette.pegColors[c],
                        palette = palette,
                        onTap = {
                            val firstEmpty = currentGuess.indexOfFirst { it == null }
                            if (firstEmpty != -1) {
                                currentGuess = currentGuess.toMutableList().apply { this[firstEmpty] = c }
                                sounds.playTap()
                                haptics(HapticSignal.LIGHT_TICK)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    game.submitGuess(currentGuess.map { it!! })
                    currentGuess = List(s.positions) { null }
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                enabled = currentGuess.all { it != null }
            ) { Text("Submit Guess") }

            Spacer(Modifier.height(16.dp))
        }

        if (s.guesses.isNotEmpty()) {
            Text("Guesses", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (guess in s.guesses.asReversed()) {
                    GuessHistoryRow(guess, palette)
                }
            }
        }

        if (s.isOver) {
            Spacer(Modifier.height(16.dp))
            MastermindFinishedPanel(
                solved = s.solved,
                secret = s.secret,
                guessCount = s.guesses.size,
                record = record,
                isNewBestGuesses = reportedResult?.first ?: false,
                isNewBestTime = reportedResult?.second ?: false,
                onNewSecret = { game.startMatch() },
                onBackToMenu = { game.leaveSession() },
                palette = palette
            )
        }
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

@Composable
private fun mastermindPalette(isDark: Boolean): MastermindPalette = if (!isDark) {
    MastermindPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
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
} else {
    MastermindPalette(
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
}

@Composable
private fun DifficultyTabsMastermind(current: CpuDifficulty, palette: MastermindPalette, onSelect: (CpuDifficulty) -> Unit) {
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
private fun MastermindStatusRow(guessesUsed: Int, maxGuesses: Int, elapsedMillis: Long, palette: MastermindPalette) {
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

/** One peg slot in the in-progress guess row — [color] null means still empty. Tapping a FILLED slot clears it back to empty; tapping an already-empty slot does nothing (use the color palette below to fill it instead). */
@Composable
private fun PegSlot(color: Color?, palette: MastermindPalette, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color ?: palette.emptyPeg)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {}
}

/** One tappable color swatch in the "pick a color" row — fills whichever peg slot is currently the first empty one. */
@Composable
private fun ColorSwatch(color: Color, palette: MastermindPalette, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onTap)
    )
}

/** One past guess: its own peg colors, plus up to [MastermindGuess.blackPegs] black dots followed by up to [MastermindGuess.whitePegs] white dots (the rest left as empty placeholders) -- the standard genre feedback-peg layout. */
@Composable
private fun GuessHistoryRow(guess: MastermindGuess, palette: MastermindPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (color in guess.colors) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(palette.pegColors[color])
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val total = guess.colors.size
            for (i in 0 until total) {
                val color = when {
                    i < guess.blackPegs -> palette.blackPeg
                    i < guess.blackPegs + guess.whitePegs -> palette.whitePeg
                    else -> palette.emptyPeg
                }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

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
    palette: MastermindPalette
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (solved) {
                Text("You cracked it!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                Text("The secret was:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (color in secret) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(palette.pegColors[color])
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNewSecret) { Text("New Secret") }
                OutlinedButton(onClick = onBackToMenu) { Text("Back to Menu") }
            }
        }
    }
}
