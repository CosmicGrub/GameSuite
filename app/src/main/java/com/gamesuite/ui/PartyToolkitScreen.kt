package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.gamesuite.games.partytoolkit.PartyToolkitTimer
import com.gamesuite.games.partytoolkit.PartyToolkitTimerState
import com.gamesuite.games.partytoolkit.ScorePlayer
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
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
 * both persist through [PartyToolkitStore]. The Hourglass countdown is hoisted to THIS screen
 * (a [PartyToolkitTimerState], saved across configuration changes), so switching tabs never
 * stops or forgets it; a mini timer chip shows on every other tab, the screen stays awake while it
 * runs, and finishing fires the haptic + sound cue from here regardless of the visible tab. The
 * remaining tools are deliberately stateless-between-visits: switching tabs and back resets
 * Dice/Coin Toss/Random Letter's own "last result," and First Player/Teams'
 * own name lists — each tool keeps its own small `remember`ed state rather
 * than a shared player roster across tools, a deliberate "honest MVP" scope
 * cut (see the class KDoc for [FirstPlayerTool]/[TeamsTool]).
 *
 * CHROME: wrapped in [GameChrome] (corner menu with how-to-use text for every tool, back
 * handling). Leaving while the hourglass runs asks first; [PartyToolkitGame.abortMatch] is the
 * abort path, since the toolkit has no scored session. The title row reserves
 * [GameChromeEndInset] so the corner button never overlaps it. Content is capped to 840dp wide on
 * tablets. Destructive actions on the persisted tools (remove a player, reset scores / life) ask
 * for confirmation. Shakes and particle bursts are off under reduced motion.
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
    val playSfx = rememberProceduralSfx()
    val reducedMotion = LocalReducedMotion.current
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

    // Saved by index (not the enum) so restoration never depends on enum serialization.
    var selectedToolIndex by rememberSaveable { mutableStateOf(0) }
    val selectedTool = PartyTool.entries[selectedToolIndex.coerceIn(0, PartyTool.entries.lastIndex)]

    // The hourglass lives here, not inside HourglassTool, so it survives tab switches.
    var timer by rememberSaveable(stateSaver = PARTY_TOOLKIT_TIMER_SAVER) {
        mutableStateOf(PartyToolkitTimer.initial())
    }

    // Wall-clock driven: each pass re-derives remaining time from the deadline, so it cannot drift.
    LaunchedEffect(timer.running) {
        while (timer.running) {
            delay(100)
            timer = PartyToolkitTimer.tick(timer, SystemClock.elapsedRealtime())
        }
    }

    // Finished cue: fires from here so it plays on any tab. The baseline keeps a restored
    // (already-finished) state from re-firing after a configuration change.
    val baselineFinishSeq = remember { timer.finishSeq }
    LaunchedEffect(timer.finishSeq) {
        if (timer.finishSeq != baselineFinishSeq) {
            haptics(HapticSignal.CELEBRATION)
            playSfx(SfxKind.SUCCESS_CHIME)
        }
    }

    // Keep the screen awake only while the countdown runs.
    val view = LocalView.current
    val timerRunning = timer.running
    DisposableEffect(timerRunning, view) {
        view.keepScreenOn = timerRunning
        onDispose { view.keepScreenOn = false }
    }

    GameChrome(
        helpTitle = "How to Use Party Toolkit",
        helpText = "Pick a tool from the tabs along the top.\n\n" +
            "Dice: choose a die type (d4 to d20) and how many to roll, then tap Roll. " +
            "Coin Toss: tap Flip; counts and recent flips are kept for this visit. " +
            "Letter: tap Pick for a random A to Z.\n\n" +
            "Scoreboard and Life Points: add players, then use minus and plus. Both are saved on this " +
            "device. Reset and remove ask first.\n\n" +
            "Hourglass: choose a preset or a custom number of minutes, then Start. It keeps running if you " +
            "switch tabs (a timer chip shows on the other tabs), keeps the screen on, and buzzes and chimes at zero.\n\n" +
            "First Player and Teams: add names, then tap the button to pick one name or split everyone " +
            "into 2 to 4 even teams.",
        matchInProgress = timer.running,
        onLeave = { game.leaveToolkit() },
        onAbort = { game.abortMatch() },
        leaveKeepsProgress = false,
        leaveTitle = "Leave Party Toolkit?",
        leaveBody = "The hourglass is still running and will stop if you leave.",
        buttonFill = palette.background,
        buttonContent = palette.textPrimary
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(palette.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PARTY_TOOLKIT_MAX_CONTENT_WIDTH)
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(end = GameChromeEndInset),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Party Toolkit",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (tool in PartyTool.entries) {
                        val selected = tool == selectedTool
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(if (selected) palette.accent else palette.chipBackground)
                                .semantics { contentDescription = tool.title }
                                .selectable(selected = selected, role = Role.Tab, onClick = { selectedToolIndex = tool.ordinal })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tool.label,
                                color = if (selected) palette.textOnAccent else palette.textPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clearAndSetSemantics {}
                            )
                        }
                    }
                }

                val timerTouched = timer.running || timer.remainingMs != timer.totalSeconds * 1000L
                if (selectedTool != PartyTool.HOURGLASS && timerTouched) {
                    Spacer(Modifier.height(8.dp))
                    PartyToolkitTimerChip(timer, palette, onClick = { selectedToolIndex = PartyTool.HOURGLASS.ordinal })
                }

                Spacer(Modifier.height(12.dp))

                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (selectedTool) {
                        PartyTool.DICE -> DiceTool(palette, haptics, reducedMotion)
                        PartyTool.COIN_TOSS -> CoinTossTool(palette, haptics, reducedMotion)
                        PartyTool.RANDOM_LETTER -> RandomLetterTool(palette, haptics)
                        PartyTool.SCOREBOARD -> ScoreboardTool(store, palette, haptics)
                        PartyTool.LIFE_POINTS -> LifePointsTool(store, palette, haptics)
                        PartyTool.HOURGLASS -> HourglassTool(timer, { timer = it }, palette, haptics, reducedMotion)
                        PartyTool.FIRST_PLAYER -> FirstPlayerTool(palette, haptics, reducedMotion)
                        PartyTool.TEAMS -> TeamsTool(palette, haptics, reducedMotion)
                    }
                }
            }
        }
    }
}

/** Widest the tool column grows on tablets / unfolded Fold; beyond this it centers. */
private val PARTY_TOOLKIT_MAX_CONTENT_WIDTH = 840.dp

private val PARTY_TOOLKIT_TIMER_SAVER = listSaver<PartyToolkitTimerState, Any>(
    save = { listOf(it.totalSeconds, it.remainingMs, it.running, it.deadlineMs, it.finishSeq) },
    restore = {
        PartyToolkitTimerState(
            totalSeconds = it[0] as Int,
            remainingMs = it[1] as Long,
            running = it[2] as Boolean,
            deadlineMs = it[3] as Long,
            finishSeq = it[4] as Int
        )
    }
)

private enum class PartyTool(val emoji: String, val title: String) {
    DICE("🎲", "Dice"),
    COIN_TOSS("🪙", "Coin Toss"),
    RANDOM_LETTER("🔤", "Letter"),
    SCOREBOARD("📊", "Scoreboard"),
    LIFE_POINTS("❤️", "Life Points"),
    HOURGLASS("⏳", "Hourglass"),
    FIRST_PLAYER("🎯", "First Player"),
    TEAMS("👥", "Teams");

    val label: String get() = "$emoji $title"
}

/** Mini timer shown on every tab but the Hourglass while the countdown is running, paused part-way, or finished. */
@Composable
private fun PartyToolkitTimerChip(timer: PartyToolkitTimerState, palette: PartyToolkitPalette, onClick: () -> Unit) {
    val secs = PartyToolkitTimer.remainingSeconds(timer)
    val finished = timer.remainingMs <= 0L
    val text = if (finished) "⏳ Time's up" else "⏳ %d:%02d".format(secs / 60, secs % 60)
    val spoken = if (finished) {
        "Hourglass finished"
    } else {
        "Hourglass, ${secs / 60} minutes ${secs % 60} seconds left" + if (timer.running) "" else ", paused"
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.accent)
            .semantics { contentDescription = spoken }
            .clickable(onClickLabel = "Open hourglass", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = palette.textOnAccent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

// ---------------------------------------------------------------------------
// Warm, Chogan-inspired CHROME palette, shared tokens with the rest of this
// batch — no bespoke cell/edge colors needed here, unlike the puzzle games.
// ---------------------------------------------------------------------------
private data class PartyToolkitPalette(
    val background: Color,
    val accent: Color,
    /** The accent as TEXT on [background] (the accent itself fails contrast as text on the light cream). */
    val accentText: Color,
    val textPrimary: Color,
    val textOnAccent: Color,
    val chipBackground: Color
)

@Composable
private fun partyToolkitPalette(isDark: Boolean): PartyToolkitPalette = if (!isDark) {
    PartyToolkitPalette(
        background = Color(0xFFFBF1E6),
        accent = Color(0xFFE08D4B),
        accentText = Color(0xFF9A4E12),
        textPrimary = Color(0xFF3A2E22),
        // Dark-on-orange (about 5:1); the former near-white was about 2.5:1.
        textOnAccent = Color(0xFF3A2E22),
        chipBackground = Color(0xFFE3CBA9)
    )
} else {
    PartyToolkitPalette(
        background = Color(0xFF1C1712),
        accent = Color(0xFFE8985B),
        accentText = Color(0xFFE8985B),
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

/**
 * Recent-history cap for Dice/Coin Toss — long enough to be useful mid-game-night ("wait, was
 * that three rolls ago a 6 or an 8?"), not an unbounded list that grows all session. In-memory
 * only, reset when the tool is left (switching tabs and back clears it) — the same "deliberately
 * stateless-between-visits" convention every non-persisted tool here already follows; see
 * docs/PARTY_TOOLKIT_DESIGN.md's own resolved persistence-question scope cut for why this doesn't
 * earn `PartyToolkitStore`'s real cross-app-restart persistence the way Scoreboard/Life Points do.
 */
private const val PARTY_TOOLKIT_HISTORY_LIMIT = 10

/** One past dice roll: which die type it actually used (independent of whatever's CURRENTLY selected — switching die type never retroactively relabels history) plus its real values. */
private data class DiceRollRecord(val sides: Int, val values: List<Int>) {
    val total: Int get() = values.sum()
}

/** A quick, small nudge for a die landing -- a "physical placement" acknowledgment, not a
 *  full-board wallop; short decay so it reads as a snap rather than a lingering wobble. See
 *  [CoinTossTool]'s own identical-shaped constants for the coin-landing sibling of this shake. */
private val DICE_LANDING_SHAKE_MAGNITUDE = 4.dp
private const val DICE_LANDING_SHAKE_DECAY_MS = 160

@Composable
private fun DiceTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit, reducedMotion: Boolean) {
    var diceCount by remember { mutableStateOf(2) }
    // d6 stays the default -- same "covers the vast majority of real board-game
    // dice needs" reasoning docs/PARTY_TOOLKIT_DESIGN.md gave for being d6-only
    // originally; this just ADDS the other standard types as an option rather
    // than replacing the sensible default.
    var dieSides by remember { mutableStateOf(6) }
    var lastRoll by remember { mutableStateOf<List<Int>>(emptyList()) }
    var rollHistory by remember { mutableStateOf<List<DiceRollRecord>>(emptyList()) }
    // A physical die landing gets the same SOLID_THUNK+shake treatment AirHockey/Checkers-style
    // "satisfying physical placement" moments already get elsewhere in this app (see
    // ProceduralSfx.kt's own SfxKind.SOLID_THUNK KDoc) -- layered alongside, never replacing, the
    // existing NORMAL_ACTION haptic below. Its own independent CameraShake/coroutine scope since
    // this tool is its own UI region, not shared with CoinTossTool's identical-shaped rig.
    val playSfx = rememberProceduralSfx()
    val cameraShake = rememberCameraShake()
    val scope = rememberCoroutineScope()
    val diceShakeMagnitudePx = with(LocalDensity.current) { DICE_LANDING_SHAKE_MAGNITUDE.toPx() }

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
                            // misleadingly look like it belongs to it. rollHistory is
                            // NOT cleared here -- it's a log of what actually happened,
                            // each entry keeping its own die type label, not a "current
                            // state for this die type" display.
                            lastRoll = emptyList()
                            haptics(HapticSignal.LIGHT_TICK)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Roll how many dice?", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Stepper(
            value = diceCount,
            range = 1..6,
            onChange = { diceCount = it },
            label = "dice count",
            palette = palette,
            haptics = haptics
        )
        Spacer(Modifier.height(16.dp))
        PartyButton(palette = palette, onClick = {
            val values = PartyToolkitLogic.rollDice(diceCount, dieSides)
            lastRoll = values
            rollHistory = (listOf(DiceRollRecord(dieSides, values)) + rollHistory).take(PARTY_TOOLKIT_HISTORY_LIMIT)
            haptics(HapticSignal.NORMAL_ACTION)
            playSfx(SfxKind.SOLID_THUNK)
            if (!reducedMotion) scope.launch { cameraShake.trigger(durationMs = DICE_LANDING_SHAKE_DECAY_MS) }
        }) { Text("Roll") }
        if (lastRoll.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.cameraShake(cameraShake, magnitudePx = diceShakeMagnitudePx)
            ) {
                for (value in lastRoll) DieFace(value, palette)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Total: ${lastRoll.sum()}",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Rolled ${lastRoll.joinToString(", ")}, total ${lastRoll.sum()}"
                    liveRegion = LiveRegionMode.Polite
                }
            )
        }
        if (rollHistory.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Recent rolls", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (record in rollHistory) {
                    Text(
                        "d${record.sides}: ${record.values.joinToString(", ")} = ${record.total}",
                        color = palette.textPrimary.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DieTypeChip(sides: Int, selected: Boolean, palette: PartyToolkitPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.accent else palette.chipBackground)
            .semantics { contentDescription = "Die with $sides sides" }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "d$sides",
            color = if (selected) palette.textOnAccent else palette.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** Decorative: the roll is announced by the Total line, so the faces are hidden from screen readers. */
@Composable
private fun DieFace(value: Int, palette: PartyToolkitPalette) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.accent)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Text(value.toString(), color = palette.textOnAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------------------
// Coin Toss
// ---------------------------------------------------------------------------
/** Coin's own sibling of [DICE_LANDING_SHAKE_MAGNITUDE]/[DICE_LANDING_SHAKE_DECAY_MS] -- same
 *  "quick physical landing" feel, kept as its own constants (not shared) since a coin and a die
 *  are independent UI regions that could legitimately want different tuning later. */
private val COIN_LANDING_SHAKE_MAGNITUDE = 4.dp
private const val COIN_LANDING_SHAKE_DECAY_MS = 160

@Composable
private fun CoinTossTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit, reducedMotion: Boolean) {
    var lastResult by remember { mutableStateOf<Boolean?>(null) }
    var headsCount by remember { mutableStateOf(0) }
    var tailsCount by remember { mutableStateOf(0) }
    var flipHistory by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    // See DiceTool's identical rig just above -- a coin landing is the same kind of "satisfying
    // physical placement" moment SOLID_THUNK was built for, with its own independent
    // CameraShake/coroutine scope since this is its own UI region.
    val playSfx = rememberProceduralSfx()
    val cameraShake = rememberCameraShake()
    val scope = rememberCoroutineScope()
    val coinShakeMagnitudePx = with(LocalDensity.current) { COIN_LANDING_SHAKE_MAGNITUDE.toPx() }

    ToolCard(palette) {
        Text("Flip a coin", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        PartyButton(palette = palette, onClick = {
            val heads = PartyToolkitLogic.flipCoin()
            lastResult = heads
            if (heads) headsCount++ else tailsCount++
            flipHistory = (listOf(heads) + flipHistory).take(PARTY_TOOLKIT_HISTORY_LIMIT)
            haptics(HapticSignal.NORMAL_ACTION)
            playSfx(SfxKind.SOLID_THUNK)
            if (!reducedMotion) scope.launch { cameraShake.trigger(durationMs = COIN_LANDING_SHAKE_DECAY_MS) }
        }) { Text("Flip") }
        lastResult?.let { heads ->
            Spacer(Modifier.height(16.dp))
            Text(
                if (heads) "Heads" else "Tails",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = palette.accentText,
                modifier = Modifier
                    .cameraShake(cameraShake, magnitudePx = coinShakeMagnitudePx)
                    .semantics {
                        contentDescription = "Coin landed on ${if (heads) "heads" else "tails"}"
                        liveRegion = LiveRegionMode.Polite
                    }
            )
            Spacer(Modifier.height(8.dp))
            Text("Heads: $headsCount · Tails: $tailsCount", color = palette.textPrimary.copy(alpha = 0.8f))
        }
        if (flipHistory.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Recent flips", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (heads in flipHistory) FlipHistoryBadge(heads, palette)
            }
        }
    }
}

/** One small badge per past flip in [CoinTossTool]'s recent-flips row, newest first. */
@Composable
private fun FlipHistoryBadge(heads: Boolean, palette: PartyToolkitPalette) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(palette.chipBackground)
            .semantics { contentDescription = if (heads) "Heads" else "Tails" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (heads) "H" else "T",
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clearAndSetSemantics {}
        )
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
        PartyButton(palette = palette, onClick = {
            lastLetter = PartyToolkitLogic.randomLetter()
            haptics(HapticSignal.NORMAL_ACTION)
        }) { Text("Pick") }
        lastLetter?.let { letter ->
            Spacer(Modifier.height(16.dp))
            Text(
                letter.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = palette.accentText,
                modifier = Modifier.semantics {
                    contentDescription = "Letter $letter"
                    liveRegion = LiveRegionMode.Polite
                }
            )
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
    var pending by remember { mutableStateOf<PartyToolkitPending?>(null) }

    ToolCard(palette) {
        Text("Scoreboard", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (players.isEmpty()) {
            Text("No players yet. Add a name below to start keeping score.", color = palette.textPrimary.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
        }
        for (player in players) {
            PlayerCounterRow(
                name = player.name,
                value = player.score,
                valueNoun = "points",
                onDecrement = {
                    haptics(HapticSignal.LIGHT_TICK)
                    scope.launch { store.saveScoreboard(players.map { if (it.name == player.name) it.copy(score = it.score - 1) else it }) }
                },
                onIncrement = {
                    scope.launch { store.saveScoreboard(players.map { if (it.name == player.name) it.copy(score = it.score + 1) else it }) }
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onRemove = {
                    pending = PartyToolkitPending(
                        title = "Remove ${player.name}?",
                        body = "${player.name} and their score will be deleted from the scoreboard.",
                        confirmLabel = "Remove"
                    ) { scope.launch { store.saveScoreboard(players.filterNot { it.name == player.name }) } }
                },
                palette = palette
            )
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            existing = players.map { it.name },
            onAdd = {
                // Capture the name into a plain `val` BEFORE launching -- `newName` is a
                // mutable var read INSIDE the coroutine body, and rememberCoroutineScope()
                // .launch{} does not run synchronously, so clearing `newName = ""` right
                // after starting the coroutine (below) would otherwise race it: the
                // coroutine could resume only after the field was already blanked,
                // silently saving a player with an empty name instead of the one typed.
                val trimmedName = newName.trim()
                if (trimmedName.isNotBlank() && players.none { it.name == trimmedName }) {
                    scope.launch { store.saveScoreboard(players + ScorePlayer(name = trimmedName, score = 0)) }
                    newName = ""
                    haptics(HapticSignal.LIGHT_TICK)
                }
            },
            palette = palette
        )
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PartyOutlinedButton(palette = palette, onClick = {
                pending = PartyToolkitPending(
                    title = "Reset all scores?",
                    body = "Every player's score goes back to 0. Players stay on the scoreboard.",
                    confirmLabel = "Reset"
                ) { scope.launch { store.saveScoreboard(players.map { it.copy(score = 0) }) } }
            }) { Text("Reset Scores") }
        }
        PartyToolkitConfirmHost(pending, palette, onClear = { pending = null })
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
    var pending by remember { mutableStateOf<PartyToolkitPending?>(null) }

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
                label = "starting total",
                palette = palette,
                haptics = haptics
            )
        }
        Spacer(Modifier.height(12.dp))
        if (players.isEmpty()) {
            Text("No players yet. Add a name below to start tracking life.", color = palette.textPrimary.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
        }
        for (player in players) {
            PlayerCounterRow(
                name = player.name,
                value = player.life,
                valueNoun = "life",
                onDecrement = {
                    haptics(HapticSignal.LIGHT_TICK)
                    scope.launch { store.saveLifePlayers(players.map { if (it.name == player.name) it.copy(life = it.life - 1) else it }) }
                },
                onIncrement = {
                    scope.launch { store.saveLifePlayers(players.map { if (it.name == player.name) it.copy(life = it.life + 1) else it }) }
                    haptics(HapticSignal.NORMAL_ACTION)
                },
                onRemove = {
                    pending = PartyToolkitPending(
                        title = "Remove ${player.name}?",
                        body = "${player.name} and their life total will be deleted.",
                        confirmLabel = "Remove"
                    ) { scope.launch { store.saveLifePlayers(players.filterNot { it.name == player.name }) } }
                },
                palette = palette
            )
        }
        Spacer(Modifier.height(8.dp))
        AddPlayerRow(
            name = newName,
            onNameChange = { newName = it },
            existing = players.map { it.name },
            onAdd = {
                // See ScoreboardTool's identical onAdd for why the name is captured into a
                // plain `val` before launching, rather than read from `newName` inside the
                // coroutine body.
                val trimmedName = newName.trim()
                if (trimmedName.isNotBlank() && players.none { it.name == trimmedName }) {
                    scope.launch { store.saveLifePlayers(players + LifePlayer(name = trimmedName, life = startingValue)) }
                    newName = ""
                    haptics(HapticSignal.LIGHT_TICK)
                }
            },
            palette = palette
        )
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PartyOutlinedButton(palette = palette, onClick = {
                pending = PartyToolkitPending(
                    title = "Reset all life totals?",
                    body = "Every player's life goes back to $startingValue.",
                    confirmLabel = "Reset"
                ) { scope.launch { store.saveLifePlayers(players.map { it.copy(life = startingValue) }) } }
            }) { Text("Reset to $startingValue") }
        }
        PartyToolkitConfirmHost(pending, palette, onClear = { pending = null })
    }
}

// ---------------------------------------------------------------------------
// Hourglass (countdown timer)
// ---------------------------------------------------------------------------
/** [HourglassVisual]'s own fixed canvas footprint -- pulled out as named constants (rather than
 *  the two bare dp literals that used to live only inside that Canvas's own modifier) so the new
 *  wrapping [BoxWithConstraints] added below for the completion particle burst is GUARANTEED to
 *  size identically to the visual it wraps, instead of two separately-typed magic numbers
 *  silently drifting apart later. */
private val HOURGLASS_VISUAL_WIDTH = 120.dp
private val HOURGLASS_VISUAL_HEIGHT = 150.dp

/** The Hourglass's own completion moment is this toolkit's single biggest beat among the tools
 *  touched in this pass (see this file's own class-level juice notes) -- the only one of the
 *  eight tools that stacks haptic + sound + camera shake + particle burst all at once -- so its
 *  shake is tuned a little stronger/longer than DiceTool/CoinTossTool's quick landing nudges. */
private val HOURGLASS_COMPLETE_SHAKE_MAGNITUDE = 7.dp
private const val HOURGLASS_COMPLETE_SHAKE_DECAY_MS = 260

/**
 * A real countdown timer per [docs/PARTY_TOOLKIT_DESIGN.md]'s Hourglass row: duration presets
 * plus a custom-minutes entry, start/pause/reset, and — since the design calls this "not a
 * purely decorative animation" — an hourglass-shaped sand visual ([HourglassVisual]) whose fill
 * level is driven directly by the timer's remaining/total time, not a canned loop.
 *
 * The countdown itself ([PartyToolkitTimerState]) is owned by [PartyToolkitScreen], not this
 * composable: that is what lets it keep running across tab switches. The haptic + sound alert at
 * zero is also fired from the screen so it plays on any tab; THIS tool only adds the visual
 * extras (camera shake + sand-colored particle burst) when it happens to be on screen as the
 * timer finishes, keyed on [PartyToolkitTimerState.finishSeq].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourglassTool(
    timer: PartyToolkitTimerState,
    onTimerChange: (PartyToolkitTimerState) -> Unit,
    palette: PartyToolkitPalette,
    haptics: (HapticSignal) -> Unit,
    reducedMotion: Boolean
) {
    var customMinutesText by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PartyToolkitPending?>(null) }
    val cameraShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hourglassShakeMagnitudePx = with(density) { HOURGLASS_COMPLETE_SHAKE_MAGNITUDE.toPx() }
    val hourglassCenterPx = with(density) {
        Offset(HOURGLASS_VISUAL_WIDTH.toPx() / 2f, HOURGLASS_VISUAL_HEIGHT.toPx() / 2f)
    }
    val burstScalePx = with(density) { HOURGLASS_VISUAL_WIDTH.toPx() }

    // Only reacts to a finish that happens while this tool is on screen (not one already counted
    // when the tab was opened).
    val baselineFinishSeq = remember { timer.finishSeq }
    LaunchedEffect(timer.finishSeq) {
        if (timer.finishSeq == baselineFinishSeq || reducedMotion) return@LaunchedEffect
        partyToolkitSpawnBurst(particleBurst, hourglassCenterPx, burstScalePx, listOf(palette.accent), count = 16)
        // Launched into a separate coroutine so the shake finishes its decay on its own lifecycle
        // rather than being tied to this effect's key.
        scope.launch { cameraShake.trigger(durationMs = HOURGLASS_COMPLETE_SHAKE_DECAY_MS) }
    }

    val remainingSeconds = PartyToolkitTimer.remainingSeconds(timer)
    val finished = timer.remainingMs <= 0L

    ToolCard(palette) {
        Text("Hourglass", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            modifier = Modifier
                .size(width = HOURGLASS_VISUAL_WIDTH, height = HOURGLASS_VISUAL_HEIGHT)
                .cameraShake(cameraShake, magnitudePx = hourglassShakeMagnitudePx)
                .clearAndSetSemantics {}
        ) {
            HourglassVisual(
                progress = PartyToolkitTimer.progress(timer),
                isRunning = timer.running,
                palette = palette
            )
            // Overlay canvas purely for the completion burst -- HourglassVisual's own Canvas stays
            // untouched, per the shared BoxWithConstraints+overlay-Canvas pattern (see
            // ParticleBurst.kt's own KDoc) for a container with no single pre-existing Canvas to
            // draw the particles into directly.
            Canvas(modifier = Modifier.matchParentSize()) {
                for (p in particleBurst.particles.value) {
                    drawCircle(color = p.color.copy(alpha = p.lifeFraction), radius = 4.dp.toPx(), center = p.pos)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = if (finished) palette.accentText else palette.textPrimary,
            modifier = Modifier.semantics {
                contentDescription = "$remainingSeconds seconds remaining, ${remainingSeconds / 60} minutes ${remainingSeconds % 60} seconds"
            }
        )
        if (finished) {
            Text(
                "Time's up!",
                color = palette.accentText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (minutes in listOf(1, 3, 5, 10)) {
                PartyOutlinedButton(
                    palette = palette,
                    modifier = Modifier.semantics { contentDescription = "Set timer to $minutes ${if (minutes == 1) "minute" else "minutes"}" },
                    onClick = { onTimerChange(PartyToolkitTimer.setTotal(timer, minutes * 60)) }
                ) { Text("${minutes}m") }
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
            PartyButton(
                palette = palette,
                enabled = customMinutes != null && customMinutes in 1..180,
                onClick = {
                    if (customMinutes != null) {
                        onTimerChange(PartyToolkitTimer.setTotal(timer, customMinutes * 60))
                        customMinutesText = ""
                    }
                }
            ) { Text("Set") }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PartyButton(
                palette = palette,
                enabled = timer.running || timer.remainingMs > 0L,
                onClick = {
                    val now = SystemClock.elapsedRealtime()
                    onTimerChange(
                        if (timer.running) PartyToolkitTimer.pause(timer, now) else PartyToolkitTimer.start(timer, now)
                    )
                    haptics(HapticSignal.LIGHT_TICK)
                }
            ) {
                Text(if (timer.running) "Pause" else "Start")
            }
            PartyOutlinedButton(palette = palette, onClick = {
                if (timer.running) {
                    pending = PartyToolkitPending(
                        title = "Reset the hourglass?",
                        body = "The countdown is running. Resetting stops it and starts over.",
                        confirmLabel = "Reset"
                    ) { onTimerChange(PartyToolkitTimer.reset(timer)) }
                } else {
                    onTimerChange(PartyToolkitTimer.reset(timer))
                }
            }) { Text("Reset") }
        }
        PartyToolkitConfirmHost(pending, palette, onClear = { pending = null })
    }
}

/**
 * Hourglass-shaped sand visual: a bowtie glass outline (two triangles meeting at a neck) with
 * sand drawn in both bulbs, sized by [progress] (1f = full time remaining, 0f = done) — the top
 * bulb's sand shrinks toward the neck as it drains, the bottom bulb's grows from the neck, each
 * using the same linear taper as the glass outline itself so the sand edge lines up with the
 * glass walls. [progress] arrives re-derived from the wall clock every 100ms, so the drain reads
 * as continuous motion without a separate easing animation. A short trickle line at
 * the neck only draws while [isRunning], so a paused hourglass visibly stops draining.
 */
@Composable
private fun HourglassVisual(progress: Float, isRunning: Boolean, palette: PartyToolkitPalette) {
    val animatedProgress = progress.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(width = HOURGLASS_VISUAL_WIDTH, height = HOURGLASS_VISUAL_HEIGHT)) {
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
        drawPath(outline, color = palette.textPrimary.copy(alpha = 0.7f), style = Stroke(width = 3f))

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
/** Fixed footprint for [FirstPlayerTool]'s single-line revealed-name reveal area -- tall enough
 *  to comfortably fit one headlineMedium line without needing a dynamically-measured size. Giving
 *  [BoxWithConstraints] an explicit height here (rather than leaving it to size purely from its
 *  content) matters because this tool lives inside PartyToolkitScreen's own `verticalScroll`
 *  column, which hands children an effectively unbounded max-height constraint — reading
 *  `maxHeight` there directly (to compute a burst-origin center) would otherwise read as a
 *  huge/unbounded value, not the actual on-screen size of the reveal. See
 *  [TEAMS_REVEAL_BURST_BOX_HEIGHT] for [TeamsTool]'s own taller sibling of this same constant. */
private val REVEAL_BURST_BOX_HEIGHT = 72.dp

/**
 * Its own independent player-name list, NOT shared with Scoreboard/Life
 * Points/Teams — a deliberate "honest MVP" scope cut. A shared roster across
 * every tool would be a genuine convenience, but adds a real cross-tool
 * state-sharing layer this first version doesn't need to earn its keep;
 * each tool re-entering its own names on first use is a small, honest cost.
 *
 * The reveal plays [SfxKind.SUCCESS_CHIME] and spawns a small celebratory particle
 * burst from the revealed name's own displayed position, alongside a [HapticSignal.SUCCESS]
 * haptic. [revealSeq] is bumped on every real "Pick First Player"
 * click (not just when the picked name changes) -- the same "seq-numbered one-shot event" idiom
 * TowerDefenceGame's own engine events use ([TowerDefenceGame.TowerDefenceEnemyDeathEvent]),
 * applied here as plain local Compose state since this tool is pure UI with no engine of its own
 * to carry a real event field; keying on the name itself would silently miss a re-fire on the
 * (rare but real) case of picking the same name twice in a row.
 */
@Composable
private fun FirstPlayerTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit, reducedMotion: Boolean) {
    var names by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var revealSeq by remember { mutableStateOf(0) }
    val playSfx = rememberProceduralSfx()
    val particleBurst = rememberParticleBurst()

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
            existing = names,
            onAdd = {
                val trimmed = newName.trim()
                if (trimmed.isNotBlank() && trimmed !in names) {
                    names = names + trimmed
                    newName = ""
                    haptics(HapticSignal.LIGHT_TICK)
                }
            },
            palette = palette
        )
        Spacer(Modifier.height(16.dp))
        PartyButton(
            palette = palette,
            enabled = names.isNotEmpty(),
            onClick = {
                picked = PartyToolkitLogic.pickFirstPlayer(names)
                revealSeq++
                haptics(HapticSignal.SUCCESS)
                playSfx(SfxKind.SUCCESS_CHIME)
            }
        ) { Text("Pick First Player") }
        if (names.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Add at least one name to pick from.", color = palette.textPrimary.copy(alpha = 0.8f))
        }
        picked?.let {
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(REVEAL_BURST_BOX_HEIGHT)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "First player: $it"
                        liveRegion = LiveRegionMode.Polite
                    },
                contentAlignment = Alignment.Center
            ) {
                val revealCenterPx = with(LocalDensity.current) { Offset(maxWidth.toPx() / 2f, maxHeight.toPx() / 2f) }
                val burstScalePx = with(LocalDensity.current) { maxHeight.toPx() * 1.5f }
                // Fires exactly once per NEW pick (see this function's own KDoc for why revealSeq,
                // not `it`/`picked`, is the key) -- deferred to here, rather than fired directly
                // from the Button's onClick above, because revealCenterPx (this reveal area's own
                // on-screen center) only exists once this Box has actually been composed with a
                // real, bounded size.
                LaunchedEffect(revealSeq) {
                    if (!reducedMotion) {
                        partyToolkitSpawnBurst(
                            particleBurst, revealCenterPx, burstScalePx,
                            listOf(palette.accent, palette.textPrimary), count = 14
                        )
                    }
                }
                Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = palette.accentText)
                // Overlay canvas purely for the reveal burst -- see HourglassTool's own identical
                // comment / ParticleBurst.kt's own KDoc for why this "wrap in BoxWithConstraints,
                // draw in a sibling matchParentSize Canvas" shape is the standard, low-risk pattern
                // for a container (here: a plain reveal Text) with no pre-existing Canvas of its own.
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (p in particleBurst.particles.value) {
                        drawCircle(color = p.color.copy(alpha = p.lifeFraction), radius = 4.dp.toPx(), center = p.pos)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Teams
// ---------------------------------------------------------------------------
/** [TeamsTool]'s own sibling of [REVEAL_BURST_BOX_HEIGHT] -- taller since up to 4 team lines
 *  (this tool's own `teamCount` range) can be revealed at once rather than FirstPlayerTool's
 *  always-exactly-one line. Comfortably fits the common case; an unusually long roster wrapping
 *  onto extra lines simply renders past this fixed box's own bottom edge (Compose's plain [Box]
 *  never clips a child by default) rather than being cut off, at the cost of the burst's own
 *  computed center then landing slightly above the true visual middle in that edge case -- a
 *  small, honest approximation for "content center" per this pass's own instructions, not a
 *  literal per-pixel measurement of the real (variable) content. */
private val TEAMS_REVEAL_BURST_BOX_HEIGHT = 150.dp

/**
 * Its own independent player-name list — see [FirstPlayerTool]'s own KDoc for why this isn't
 * shared with the other tools.
 *
 * The reveal plays [SfxKind.SUCCESS_CHIME] and spawns a small celebratory particle
 * burst from the revealed teams list's own content center (multiple teams reveal at once here,
 * so there's no single "team's own position" the way FirstPlayerTool has a single name's),
 * alongside a [HapticSignal.SUCCESS] haptic. [revealSeq] mirrors
 * [FirstPlayerTool]'s own identical field -- see that tool's KDoc for why a seq counter, not the
 * revealed value itself, is the right LaunchedEffect key.
 */
@Composable
private fun TeamsTool(palette: PartyToolkitPalette, haptics: (HapticSignal) -> Unit, reducedMotion: Boolean) {
    var names by remember { mutableStateOf(listOf<String>()) }
    var newName by remember { mutableStateOf("") }
    var teamCount by remember { mutableStateOf(2) }
    var teams by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var revealSeq by remember { mutableStateOf(0) }
    val playSfx = rememberProceduralSfx()
    val particleBurst = rememberParticleBurst()

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
            existing = names,
            onAdd = {
                val trimmed = newName.trim()
                if (trimmed.isNotBlank() && trimmed !in names) {
                    names = names + trimmed
                    newName = ""
                    haptics(HapticSignal.LIGHT_TICK)
                }
            },
            palette = palette
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Teams: ", color = palette.textPrimary)
            Stepper(value = teamCount, range = 2..4, onChange = { teamCount = it }, label = "team count", palette = palette, haptics = haptics)
        }
        Spacer(Modifier.height(16.dp))
        PartyButton(
            palette = palette,
            enabled = names.size >= 2,
            onClick = {
                teams = PartyToolkitLogic.splitIntoTeams(names, teamCount)
                revealSeq++
                haptics(HapticSignal.SUCCESS)
                playSfx(SfxKind.SUCCESS_CHIME)
            }
        ) { Text("Split into Teams") }
        if (names.size < 2) {
            Spacer(Modifier.height(8.dp))
            Text("Add at least two names to split into teams.", color = palette.textPrimary.copy(alpha = 0.8f))
        }
        if (teams.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TEAMS_REVEAL_BURST_BOX_HEIGHT)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            ) {
                val revealCenterPx = with(LocalDensity.current) { Offset(maxWidth.toPx() / 2f, maxHeight.toPx() / 2f) }
                val burstScalePx = with(LocalDensity.current) { maxHeight.toPx() }
                // See FirstPlayerTool's identical LaunchedEffect for why revealSeq (not `teams`
                // itself) is the key, and why this spawn is deferred to here rather than fired
                // directly from the Button's onClick above.
                LaunchedEffect(revealSeq) {
                    if (!reducedMotion) {
                        partyToolkitSpawnBurst(
                            particleBurst, revealCenterPx, burstScalePx,
                            listOf(palette.accent, palette.textPrimary), count = 14
                        )
                    }
                }
                Column {
                    for ((index, team) in teams.withIndex()) {
                        Text(
                            "Team ${index + 1}: ${team.joinToString(", ")}",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // Overlay canvas purely for the reveal burst -- see HourglassTool/FirstPlayerTool's
                // own identical comment for why this pattern applies here too.
                Canvas(modifier = Modifier.matchParentSize()) {
                    for (p in particleBurst.particles.value) {
                        drawCircle(color = p.color.copy(alpha = p.lifeFraction), radius = 4.dp.toPx(), center = p.pos)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared small building blocks
// ---------------------------------------------------------------------------

/**
 * Spawns a celebration burst. [ParticleBurst] integrates velocity and gravity in "units per
 * second" of whatever coordinate space the origin is in; the origins here are in PIXELS, so the
 * normalized default speeds moved particles about 1px. Scaling speed and gravity by [scalePx]
 * (a size of the reveal area) makes the burst actually travel across it. Kept here, not in the
 * shared [ParticleBurst], which other screens use with normalized coordinates.
 */
private fun partyToolkitSpawnBurst(
    burst: ParticleBurst,
    origin: Offset,
    scalePx: Float,
    colors: List<Color>,
    count: Int
) {
    burst.spawn(
        origin = origin,
        count = count,
        colors = colors,
        speedRange = (0.25f * scalePx)..(0.6f * scalePx),
        lifeRangeSeconds = 0.45f..0.75f,
        gravity = 0.9f * scalePx
    )
}

/** A destructive action waiting on the user's confirmation. */
private class PartyToolkitPending(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val action: () -> Unit
)

/** Shows the confirm dialog for [pending] (if any); [onClear] drops it after either choice. */
@Composable
private fun PartyToolkitConfirmHost(pending: PartyToolkitPending?, palette: PartyToolkitPalette, onClear: () -> Unit) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onClear,
        title = { Text(pending.title) },
        text = { Text(pending.body) },
        confirmButton = {
            TextButton(onClick = {
                onClear()
                pending.action()
            }) { Text(pending.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onClear) { Text("Cancel") } }
    )
}

@Composable
private fun PartyButton(
    palette: PartyToolkitPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.textOnAccent,
            disabledContainerColor = palette.chipBackground,
            disabledContentColor = palette.textPrimary.copy(alpha = 0.55f)
        ),
        content = content
    )
}

@Composable
private fun PartyOutlinedButton(
    palette: PartyToolkitPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = palette.textPrimary,
            disabledContentColor = palette.textPrimary.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.55f)),
        content = content
    )
}

@Composable
private fun ToolCard(palette: PartyToolkitPalette, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        colors = CardDefaults.cardColors(containerColor = palette.background, contentColor = palette.textPrimary),
        border = BorderStroke(1.dp, palette.chipBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** "Subtract one" / "Add five": the spoken action for a stepper button. */
private fun partyToolkitStepWords(step: Int): String = if (step == 1) "one" else step.toString()

@Composable
private fun Stepper(
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
    label: String,
    palette: PartyToolkitPalette,
    haptics: (HapticSignal) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RoundIconButton(
            symbol = "−",
            description = "Decrease $label",
            actionLabel = "Subtract ${partyToolkitStepWords(step)}",
            enabled = value - step >= range.first,
            onClick = {
                haptics(HapticSignal.LIGHT_TICK)
                onChange((value - step).coerceIn(range))
            },
            palette = palette
        )
        Text(
            value.toString(),
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(min = 32.dp)
                .semantics {
                    contentDescription = "$label: $value"
                    liveRegion = LiveRegionMode.Polite
                },
            maxLines = 1
        )
        RoundIconButton(
            symbol = "+",
            description = "Increase $label",
            actionLabel = "Add ${partyToolkitStepWords(step)}",
            enabled = value + step <= range.last,
            onClick = {
                haptics(HapticSignal.LIGHT_TICK)
                onChange((value + step).coerceIn(range))
            },
            palette = palette
        )
    }
}

/** A 48dp touch target drawn as a 36dp circle. Always focusable by screen readers; a disabled one reads as disabled. */
@Composable
private fun RoundIconButton(
    symbol: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    palette: PartyToolkitPalette
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClickLabel = actionLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (enabled) palette.accent else palette.chipBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(
                symbol,
                color = if (enabled) palette.textOnAccent else palette.textPrimary.copy(alpha = 0.55f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun PartyToolkitRemoveButton(name: String, onRemove: () -> Unit, palette: PartyToolkitPalette) {
    TextButton(
        onClick = onRemove,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = "Remove $name" },
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Text("✕", color = palette.textPrimary.copy(alpha = 0.75f), modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun PlayerCounterRow(
    name: String,
    value: Int,
    valueNoun: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onRemove: () -> Unit,
    palette: PartyToolkitPalette
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // On a narrow slot (Fold cover) the controls take most of the row, so the name gets its own line.
        val stacked = maxWidth < 340.dp
        val controls: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RoundIconButton("−", "Decrease $valueNoun for $name", "Subtract one", true, onDecrement, palette)
                Text(
                    value.toString(),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(min = 28.dp)
                        .semantics {
                            contentDescription = "$name, $value $valueNoun"
                            liveRegion = LiveRegionMode.Polite
                        }
                )
                RoundIconButton("+", "Increase $valueNoun for $name", "Add one", true, onIncrement, palette)
                PartyToolkitRemoveButton(name, onRemove, palette)
            }
        }
        if (stacked) {
            Column {
                Text(name, color = palette.textPrimary, modifier = Modifier.fillMaxWidth())
                controls()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, color = palette.textPrimary, modifier = Modifier.weight(1f))
                controls()
            }
        }
    }
}

@Composable
private fun NameRow(name: String, onRemove: () -> Unit, palette: PartyToolkitPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = palette.textPrimary, modifier = Modifier.weight(1f))
        PartyToolkitRemoveButton(name, onRemove, palette)
    }
}

@Composable
private fun AddPlayerRow(
    name: String,
    onNameChange: (String) -> Unit,
    existing: List<String>,
    onAdd: () -> Unit,
    palette: PartyToolkitPalette
) {
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed in existing
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Add player") },
            singleLine = true,
            isError = duplicate,
            supportingText = { if (duplicate) Text("Already added") },
            modifier = Modifier.weight(1f)
        )
        // The text field is 56dp tall; align the button with the field itself, not its helper text.
        PartyButton(
            palette = palette,
            onClick = onAdd,
            enabled = trimmed.isNotEmpty() && !duplicate,
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("Add") }
    }
}
