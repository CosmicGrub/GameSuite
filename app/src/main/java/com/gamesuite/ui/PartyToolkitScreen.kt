package com.gamesuite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.partytoolkit.LifePlayer
import com.gamesuite.games.partytoolkit.PartyToolkitGame
import com.gamesuite.games.partytoolkit.PartyToolkitLogic
import com.gamesuite.games.partytoolkit.PartyToolkitStore
import com.gamesuite.games.partytoolkit.ScorePlayer
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Renders Party Toolkit — a single screen with its OWN internal tab
 * navigation across 8 small, mostly independent tools (Dice, Coin Toss,
 * Random Letter, Scoreboard, Life Points, Hourglass, First Player, Teams),
 * rather than 8 separate menu entries or 8 separate [com.gamesuite.core.GameModule]s
 * — see PartyToolkitGame's own class KDoc for the full architecture reasoning
 * this screen is just the view layer for.
 *
 * Only Scoreboard and Life Points carry state that outlives a single tab
 * visit (a running tally you'd want to survive backing out mid-game-night) —
 * both persist through [PartyToolkitStore]. The other 6 tools are
 * deliberately stateless-between-visits: switching tabs and back resets
 * Dice/Coin Toss/Random Letter's own "last result," and First Player/Teams'
 * own name lists — each tool keeps its own small `remember`ed state rather
 * than a shared player roster across tools, a deliberate "honest MVP" scope
 * cut (see the class KDoc for [FirstPlayerTool]/[TeamsTool]).
 *
 * VISUAL IDENTITY: shares this batch's warm Chogan-inspired chrome tokens
 * (see [partyToolkitPalette]) — no cell/edge colors of its own to need an
 * exception for, unlike the puzzle games in this batch.
 */
@Composable
fun PartyToolkitScreen(
    sessionManager: GameSessionManager,
    game: PartyToolkitGame,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val musicEnabled = LocalMusicEnabled.current
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val store = remember { PartyToolkitStore(androidContext) }
    val palette = partyToolkitPalette(isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.init(ctx)
        game.setOnMatchEnd {
            sessionManager.endActiveGame(it)
            onMatchEnded()
        }
        game.startMatch()
    }

    var selectedTool by remember { mutableStateOf(PartyTool.DICE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { game.leaveToolkit() }) { Text("← Back to Menu", color = palette.textPrimary) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Party Toolkit",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary
        )
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tool in PartyTool.entries) {
                val selected = tool == selectedTool
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) palette.accent else palette.chipBackground)
                        .clickable { selectedTool = tool }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        tool.label,
                        color = if (selected) palette.textOnAccent else palette.textPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (selectedTool) {
                PartyTool.DICE -> DiceTool(palette, haptics)
                PartyTool.COIN_TOSS -> CoinTossTool(palette, haptics)
                PartyTool.RANDOM_LETTER -> RandomLetterTool(palette, haptics)
                PartyTool.SCOREBOARD -> ScoreboardTool(store, palette, haptics)
                PartyTool.LIFE_POINTS -> LifePointsTool(store, palette, haptics)
                PartyTool.HOURGLASS -> HourglassTool(palette, haptics)
                PartyTool.FIRST_PLAYER -> FirstPlayerTool(palette, haptics)
                PartyTool.TEAMS -> TeamsTool(palette, haptics)
            }
        }
    }
}

private enum class PartyTool(val label: String) {
    DICE("🎲 Dice"),
    COIN_TOSS("🪙 Coin Toss"),
    RANDOM_LETTER("🔤 Letter"),
    SCOREBOARD("📊 Scoreboard"),
    LIFE_POINTS("❤️ Life Points"),
    HOURGLASS("⏳ Hourglass"),
    FIRST_PLAYER("🎯 First Player"),
    TEAMS("👥 Teams")
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch — no bespoke cell/edge colors needed here, unlike the puzzle games.
// ---------------------------------------------------------------------------
private data class PartyToolkitPalette(
    val background: Color,
    val accent: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color
)

@Composable
private fun partyToolkitPalette(isDark: Boolean): PartyToolkitPalette = if (!isDark) {
    PartyToolkitPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        textPrimary = Color(0xFF3A2E22),
        textOnAccent = Color(0xFFFFFBF5),
        chipBackground = Color(0xFFE3CBA9)
    )
} else {
    PartyToolkitPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        textPrimary = Color(0xFFF3E9DB),
        textOnAccent = Color(0xFF1C1712),
        chipBackground = Color(0xFF453A2E)
    )
}

// ---------------------------------------------------------------------------
// Dice
// ---------------------------------------------------------------------------

/** The die-type chips offered — the standard polyhedral-dice set real board/tabletop games actually use, not an arbitrary or exhaustive list. */
private val DICE_TYPES = listOf(4, 6, 8, 10, 12, 20)

@Composable
private fun DiceTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var diceCount by remember { mutableStateOf(2) }
    // d6 stays the default -- same "covers the vast majority of real board-game
    // dice needs" reasoning docs/PARTY_TOOLKIT_DESIGN.md gave for being d6-only
    // originally; this just ADDS the other standard types as an option rather
    // than replacing the sensible default.
    var dieSides by remember { mutableStateOf(6) }
    var lastRoll by remember { mutableStateOf<List<Int>>(emptyList()) }

    ToolCard(palette) {
        Text("Die type", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (sides in DICE_TYPES) {
                DieTypeChip(
                    sides = sides,
                    selected = sides == dieSides,
                    palette = palette,
                    onClick = {
                        if (sides != dieSides) {
                            dieSides = sides
                            // Switching die type invalidates whatever's on screen -- a
                            // stale d6 roll sitting under a newly-picked d20 chip would
                            // misleadingly look like it belongs to it.
                            lastRoll = emptyList()
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Roll how many dice?", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Stepper(value = diceCount, range = 1..6, onChange = { diceCount = it }, palette = palette)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            lastRoll = PartyToolkitLogic.rollDice(diceCount, dieSides)
            haptics(HapticSignal.NORMAL_ACTION)
        }) { Text("Roll") }
        if (lastRoll.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in lastRoll) DieFace(value, palette)
            }
            Spacer(Modifier.height(8.dp))
            Text("Total: ${lastRoll.sum()}", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DieTypeChip(sides: Int, selected: Boolean, palette: PartyToolkitPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.accent else palette.chipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "d$sides",
            color = if (selected) palette.textOnAccent else palette.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun DieFace(value: Int, palette: PartyToolkitPalette) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.accent),
        contentAlignment = Alignment.Center
    ) {
        Text(value.toString(), color = palette.textOnAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------------------
// Coin Toss
// ---------------------------------------------------------------------------
@Composable
private fun CoinTossTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var lastResult by remember { mutableStateOf<Boolean?>(null) }
    var headsCount by remember { mutableStateOf(0) }
    var tailsCount by remember { mutableStateOf(0) }

    ToolCard(palette) {
        Text("Flip a coin", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val heads = PartyToolkitLogic.flipCoin()
            lastResult = heads
            if (heads) headsCount++ else tailsCount++
            haptics(HapticSignal.NORMAL_ACTION)
        }) { Text("Flip") }
        lastResult?.let { heads ->
            Spacer(Modifier.height(16.dp))
            Text(
                if (heads) "Heads" else "Tails",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = palette.accent
            )
            Spacer(Modifier.height(8.dp))
            Text("Heads: $headsCount · Tails: $tailsCount", color = palette.textPrimary.copy(alpha = 0.75f))
        }
    }
}

// ---------------------------------------------------------------------------
// Random Letter
// ---------------------------------------------------------------------------
@Composable
private fun RandomLetterTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var lastLetter by remember { mutableStateOf<Char?>(null) }

    ToolCard(palette) {
        Text("Pick a random letter", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            lastLetter = PartyToolkitLogic.randomLetter()
            haptics(HapticSignal.NORMAL_ACTION)
        }) { Text("Pick") }
        lastLetter?.let { letter ->
            Spacer(Modifier.height(16.dp))
            Text(letter.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = palette.accent)
        }
    }
}

// ---------------------------------------------------------------------------
// Scoreboard (persisted)
// ---------------------------------------------------------------------------
@Composable
private fun ScoreboardTool(store: PartyToolkitStore, palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    val scope = rememberCoroutineScope()
    val players by store.scoreboard.collectAsState(initial = emptyList())
    var newName by remember { mutableStateOf("") }

    ToolCard(palette) {
        Text("Scoreboard", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        for (player in players) {
            PlayerCounterRow(
                name = player.name,
                value = player.score,
                onDecrement = {
                    scope.launch { store.saveScoreboard(players.map { if (it.name == player.name) it.copy(score = it.score - 1) else it }) }
                },
                onIncrement = {
                    scope.launch { store.saveScoreboard(players.map { if (it.name == player.name) it.copy(score = it.score + 1) else it }) }
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onRemove = { scope.launch { store.saveScoreboard(players.filterNot { it.name == player.name }) } },
                palette = palette
            )
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            onAdd = {
                // Capture the name into a plain `val` BEFORE launching -- `newName` is a
                // mutable var read INSIDE the coroutine body, and rememberCoroutineScope()
                // .launch{} does not run synchronously, so clearing `newName = ""` right
                // after starting the coroutine (below) would otherwise race it: the
                // coroutine could resume only after the field was already blanked,
                // silently saving a player with an empty name instead of the one typed.
                val trimmedName = newName
                if (trimmedName.isNotBlank() && players.none { it.name == trimmedName }) {
                    scope.launch { store.saveScoreboard(players + ScorePlayer(name = trimmedName, score = 0)) }
                    newName = ""
                }
            },
            palette = palette
        )
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { scope.launch { store.saveScoreboard(players.map { it.copy(score = 0) }) } }) {
                Text("Reset Scores")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Life Points (persisted)
// ---------------------------------------------------------------------------
@Composable
private fun LifePointsTool(store: PartyToolkitStore, palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    val scope = rememberCoroutineScope()
    val players by store.lifePlayers.collectAsState(initial = emptyList())
    val startingValue by store.lifeStartingValue.collectAsState(initial = PartyToolkitStore.DEFAULT_LIFE_STARTING_VALUE)
    var newName by remember { mutableStateOf("") }

    ToolCard(palette) {
        Text("Life Points", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Starting total: ", color = palette.textPrimary)
            Stepper(
                value = startingValue,
                range = 1..99,
                step = 5,
                onChange = { scope.launch { store.saveLifeStartingValue(it) } },
                palette = palette
            )
        }
        Spacer(Modifier.height(12.dp))
        for (player in players) {
            PlayerCounterRow(
                name = player.name,
                value = player.life,
                onDecrement = {
                    scope.launch { store.saveLifePlayers(players.map { if (it.name == player.name) it.copy(life = it.life - 1) else it }) }
                },
                onIncrement = {
                    scope.launch { store.saveLifePlayers(players.map { if (it.name == player.name) it.copy(life = it.life + 1) else it }) }
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onRemove = { scope.launch { store.saveLifePlayers(players.filterNot { it.name == player.name }) } },
                palette = palette
            )
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            onAdd = {
                // See ScoreboardTool's identical onAdd for why the name is captured into a
                // plain `val` before launching, rather than read from `newName` inside the
                // coroutine body.
                val trimmedName = newName
                if (trimmedName.isNotBlank() && players.none { it.name == trimmedName }) {
                    scope.launch { store.saveLifePlayers(players + LifePlayer(name = trimmedName, life = startingValue)) }
                    newName = ""
                }
            },
            palette = palette
        )
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                scope.launch { store.saveLifePlayers(players.map { it.copy(life = startingValue) }) }
            }) { Text("Reset to $startingValue") }
        }
    }
}

// ---------------------------------------------------------------------------
// Hourglass (countdown timer)
// ---------------------------------------------------------------------------
/**
 * A real countdown timer per [docs/PARTY_TOOLKIT_DESIGN.md]'s Hourglass row: duration presets
 * plus a custom-minutes entry, start/pause/reset, and — since the design calls this "not a
 * purely decorative animation" — an hourglass-shaped sand visual ([HourglassVisual]) whose fill
 * level is driven directly by `remainingSeconds`/`totalSeconds`, not a canned loop. The alert at
 * zero is both a haptic ([HapticSignal.CELEBRATION], matching every other tool's feedback) and a
 * real sound ([SfxKind.SUCCESS_CHIME] via [rememberProceduralSfx], the same one-shot-SFX idiom
 * every other game screen already uses) — the design doc's "sound+vibration alert," not haptic
 * alone.
 */
@Composable
private fun HourglassTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var totalSeconds by remember { mutableStateOf(5 * 60) }
    var remainingSeconds by remember { mutableStateOf(totalSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var customMinutesText by remember { mutableStateOf("") }
    val playSfx = rememberProceduralSfx()

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isRunning && remainingSeconds > 0) {
            delay(1000)
            remainingSeconds -= 1
        }
        if (remainingSeconds <= 0) {
            isRunning = false
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        }
    }

    ToolCard(palette) {
        Text("Hourglass", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        HourglassVisual(
            progress = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds else 0f,
            isRunning = isRunning,
            palette = palette
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = if (remainingSeconds == 0) palette.accent else palette.textPrimary
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in listOf(1, 3, 5, 10)) {
                OutlinedButton(onClick = {
                    isRunning = false
                    totalSeconds = minutes * 60
                    remainingSeconds = totalSeconds
                }) { Text("${minutes}m") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customMinutesText,
                onValueChange = { customMinutesText = it.filter(Char::isDigit).take(3) },
                label = { Text("Custom (min)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp)
            )
            val customMinutes = customMinutesText.toIntOrNull()
            Button(
                enabled = customMinutes != null && customMinutes in 1..180,
                onClick = {
                    val minutes = customMinutes ?: return@Button
                    isRunning = false
                    totalSeconds = minutes * 60
                    remainingSeconds = totalSeconds
                    customMinutesText = ""
                }
            ) { Text("Set") }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = isRunning || remainingSeconds > 0,
                onClick = { isRunning = !isRunning }
            ) {
                Text(if (isRunning) "Pause" else "Start")
            }
            OutlinedButton(onClick = {
                isRunning = false
                remainingSeconds = totalSeconds
            }) { Text("Reset") }
        }
    }
}

/**
 * Hourglass-shaped sand visual: a bowtie glass outline (two triangles meeting at a neck) with
 * sand drawn in both bulbs, sized by [progress] (1f = full time remaining, 0f = done) — the top
 * bulb's sand shrinks toward the neck as it drains, the bottom bulb's grows from the neck, each
 * using the same linear taper as the glass outline itself so the sand edge lines up with the
 * glass walls. [animateFloatAsState] eases between each one-second tick instead of jumping, so
 * the drain reads as continuous motion rather than a once-a-second snap. A short trickle line at
 * the neck only draws while [isRunning], so a paused hourglass visibly stops draining.
 */
@Composable
private fun HourglassVisual(progress: Float, isRunning: Boolean, palette: PartyToolkitPalette) {
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "hourglassSand")
    Canvas(modifier = Modifier.size(width = 120.dp, height = 150.dp)) {
        val w = size.width
        val h = size.height
        val neckY = h / 2f

        val outline = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w / 2f, neckY)
            lineTo(w, h)
            lineTo(0f, h)
            lineTo(w / 2f, neckY)
            close()
        }
        drawPath(outline, color = palette.textPrimary.copy(alpha = 0.4f), style = Stroke(width = 3f))

        // Top bulb: a smaller similar triangle from the current sand surface down to the neck.
        val topSurfaceY = (1f - animatedProgress) * neckY
        val topHalfWidth = (w / 2f) * (1f - topSurfaceY / neckY)
        val topSand = Path().apply {
            moveTo(w / 2f - topHalfWidth, topSurfaceY)
            lineTo(w / 2f + topHalfWidth, topSurfaceY)
            lineTo(w / 2f, neckY)
            close()
        }
        drawPath(topSand, color = palette.accent)

        // Bottom bulb: the trapezoid from the current sand surface down to the full base. The
        // surface starts AT the base (zero fill — degenerates to zero area, empty) and rises
        // toward the neck as animatedProgress falls, reaching the neck itself (zero half-width,
        // degenerating the other way into the FULL triangle via the fixed base corners below)
        // once the countdown completes. Deliberately driven by animatedProgress directly, not a
        // separately-computed "1f - animatedProgress" — that inverted version is exactly the bug
        // an earlier pass here had: it makes the surface approach the BASE as time runs out
        // instead of the neck, so the bottom bulb visually drains back to empty right as the
        // timer completes instead of ending full. Confirmed by pixel-sampling a real device
        // screenshot mid-countdown and at 0:00 before this fix, and again after.
        val bottomSurfaceY = neckY + animatedProgress * neckY
        val bottomHalfWidth = (w / 2f) * ((bottomSurfaceY - neckY) / neckY)
        val bottomSand = Path().apply {
            moveTo(w / 2f - bottomHalfWidth, bottomSurfaceY)
            lineTo(w / 2f + bottomHalfWidth, bottomSurfaceY)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(bottomSand, color = palette.accent)

        if (isRunning && animatedProgress > 0f) {
            drawLine(
                color = palette.accent,
                start = Offset(w / 2f, neckY - 6f),
                end = Offset(w / 2f, neckY + 6f),
                strokeWidth = 3f
            )
        }
    }
}

// ---------------------------------------------------------------------------
// First Player
// ---------------------------------------------------------------------------
/**
 * Its own independent player-name list, NOT shared with Scoreboard/Life
 * Points/Teams — a deliberate "honest MVP" scope cut. A shared roster across
 * every tool would be a genuine convenience, but adds a real cross-tool
 * state-sharing layer this first version doesn't need to earn its keep;
 * each tool re-entering its own names on first use is a small, honest cost.
 */
@Composable
private fun FirstPlayerTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var names by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }

    ToolCard(palette) {
        Text("Who goes first?", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        for (name in names) {
            NameRow(name = name, onRemove = { names = names.filterNot { it == name }; picked = null }, palette = palette)
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            onAdd = {
                if (newName.isNotBlank() && newName !in names) {
                    names = names + newName
                    newName = ""
                }
            },
            palette = palette
        )
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = names.isNotEmpty(),
            onClick = {
                picked = PartyToolkitLogic.pickFirstPlayer(names)
                haptics(HapticSignal.CELEBRATION)
            }
        ) { Text("Pick First Player") }
        picked?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = palette.accent)
        }
    }
}

// ---------------------------------------------------------------------------
// Teams
// ---------------------------------------------------------------------------
/** Its own independent player-name list — see [FirstPlayerTool]'s own KDoc for why this isn't shared with the other tools. */
@Composable
private fun TeamsTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit) {
    var names by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var teamCount by remember { mutableStateOf(2) }
    var teams by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    ToolCard(palette) {
        Text("Split into teams", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        for (name in names) {
            NameRow(name = name, onRemove = { names = names.filterNot { it == name }; teams = emptyList() }, palette = palette)
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            onAdd = {
                if (newName.isNotBlank() && newName !in names) {
                    names = names + newName
                    newName = ""
                }
            },
            palette = palette
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Teams: ", color = palette.textPrimary)
            Stepper(value = teamCount, range = 2..4, onChange = { teamCount = it }, palette = palette)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = names.size >= 2,
            onClick = {
                teams = PartyToolkitLogic.splitIntoTeams(names, teamCount)
                haptics(HapticSignal.CELEBRATION)
            }
        ) { Text("Split into Teams") }
        if (teams.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            for ((index, team) in teams.withIndex()) {
                Text(
                    "Team ${index + 1}: ${team.joinToString(", ")}",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared small building blocks
// ---------------------------------------------------------------------------
@Composable
private fun ToolCard(palette: PartyToolkitPalette, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit, palette: PartyToolkitPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIconButton("−", enabled = value - step >= range.first, onClick = { onChange((value - step).coerceIn(range)) }, palette = palette)
        Text(value.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        RoundIconButton("+", enabled = value + step <= range.last, onClick = { onChange((value + step).coerceIn(range)) }, palette = palette)
    }
}

@Composable
private fun RoundIconButton(symbol: String, enabled: Boolean, onClick: () -> Unit, palette: PartyToolkitPalette) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) palette.accent else palette.chipBackground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (enabled) palette.textOnAccent else palette.textPrimary.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayerCounterRow(name: String, value: Int, onDecrement: () -> Unit, onIncrement: () -> Unit, onRemove: () -> Unit, palette: PartyToolkitPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = palette.textPrimary, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton("−", enabled = true, onClick = onDecrement, palette = palette)
            Text(value.toString(), color = palette.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 28.dp))
            RoundIconButton("+", enabled = true, onClick = onIncrement, palette = palette)
            TextButton(onClick = onRemove) { Text("✕", color = palette.textPrimary.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun NameRow(name: String, onRemove: () -> Unit, palette: PartyToolkitPalette) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = palette.textPrimary)
        TextButton(onClick = onRemove) { Text("✕", color = palette.textPrimary.copy(alpha = 0.6f)) }
    }
}

@Composable
private fun AddPlayerRow(name: String, onNameChange: (String) -> Unit, onAdd: () -> Unit, palette: PartyToolkitPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Add player") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = onAdd, enabled = name.isNotBlank()) { Text("Add") }
    }
}
