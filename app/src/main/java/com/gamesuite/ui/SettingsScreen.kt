package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.settings.AppSettings
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.NamedTheme
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.settings.ThemeMode

/**
 * App-wide settings only — per-game settings (UNO house rules, per-game
 * difficulty overrides, ...) belong on each game's own setup screen, not
 * here. See docs/SETTINGS_THEMING_ACCESSIBILITY.md §1's design note.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showResetConfirmation by remember { mutableStateOf(false) }

    // Wide-window fix (Fold/Tab compatibility audit): this is a form screen — a plain
    // fillMaxSize() column would stretch every label/switch row (and the full-width
    // slider/text field) edge to edge across a Tab S9 landscape or Fold-unfolded window,
    // leaving a huge gap between e.g. a switch's label and the switch itself. Same
    // widthIn(max = 840.dp)-before-fillMaxWidth() centering trick as AdaptiveTwoPane/
    // MainMenuScreen caps this screen to a sane reading width instead — no grid reflow
    // needed here, unlike MainMenuScreen's button list, since a form's rows are already
    // one-per-line by nature.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) { Text("← Back") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            SectionHeader("Theme")
            ThemeModeSelector(settings.themeMode, onSelect = viewModel::setThemeMode)
            Spacer(Modifier.height(12.dp))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingSwitchRow(
                    label = "Dynamic color (Material You)",
                    description = "Match your wallpaper's colors",
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
                Spacer(Modifier.height(12.dp))
            }

            NamedThemeSelector(
                selected = settings.namedTheme,
                enabled = !settings.dynamicColor,
                onSelect = viewModel::setNamedTheme
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Sound & feedback")
            // focusGroup() (§4c): the three toggles below are one logical cluster — Tab
            // moves through them together before jumping to the next section header.
            Column(modifier = Modifier.focusGroup()) {
                SettingSwitchRow(
                    label = "Sound effects",
                    description = null,
                    checked = settings.soundEnabled,
                    onCheckedChange = viewModel::setSoundEnabled
                )
                SettingSwitchRow(
                    label = "Haptics",
                    description = null,
                    checked = settings.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled
                )
                SettingSwitchRow(
                    label = "Ambient music",
                    description = "Calming background music while you play",
                    checked = settings.musicEnabled,
                    onCheckedChange = viewModel::setMusicEnabled
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Accessibility")
            // focusGroup() (§4c): same clustering as "Sound & feedback" above, including
            // the text-size slider since it's part of the same accessibility control set.
            Column(modifier = Modifier.focusGroup()) {
                SettingSwitchRow(
                    label = "Reduced motion",
                    description = "Minimize animations across all games",
                    checked = settings.reducedMotion,
                    onCheckedChange = viewModel::setReducedMotion
                )
                SettingSwitchRow(
                    label = "Colorblind-safe mode",
                    description = "Add shape/pattern cues alongside color (e.g. UNO card colors)",
                    checked = settings.colorblindMode,
                    onCheckedChange = viewModel::setColorblindMode
                )
                SettingSwitchRow(
                    label = "3D perspective mode",
                    description = "Cards and pieces get real depth and perspective flips, and boards/tables get a resting tilt",
                    checked = settings.card3DEnabled,
                    onCheckedChange = viewModel::setCard3DEnabled
                )
                SettingSwitchRow(
                    label = "Enhanced move animations",
                    description = "Pieces and cards move with weight instead of snapping instantly — lift-and-place, capture fades, fly-to-target tosses",
                    checked = settings.enhancedAnimationsEnabled,
                    onCheckedChange = viewModel::setEnhancedAnimationsEnabled
                )
                Spacer(Modifier.height(8.dp))
                Text("Text size: ${"%.0f".format(settings.textScale * 100)}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.textScale,
                    onValueChange = viewModel::setTextScale,
                    valueRange = 0.85f..1.5f,
                    steps = 12
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Card size")
            Text(
                "Applies to every card game. Cards are capped to a size range computed " +
                    "for this device — the smallest setting stays comfortably tappable, and " +
                    "the largest still fits the table, so the game stays fully playable at " +
                    "either end.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))
            CardSizePreview()
            Spacer(Modifier.height(4.dp))
            Slider(
                value = settings.cardSizePreference,
                onValueChange = viewModel::setCardSizePreference,
                valueRange = 0f..1f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Small", style = MaterialTheme.typography.labelSmall)
                Text("Large", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Gameplay")
            Text("Default CPU difficulty", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pre-selects each game's own difficulty picker — you can still override per match.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            DifficultySelector(settings.defaultCpuDifficulty, onSelect = viewModel::setDefaultCpuDifficulty)

            Spacer(Modifier.height(24.dp))
            SectionHeader("Online multiplayer")
            Text(
                "A ws:// or wss:// address for the relay server UNO's online mode connects " +
                    "through — see server/README.md. Leave blank and Online play stays disabled.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            var serverUrlDraft by remember(settings.onlineServerUrl) { mutableStateOf(settings.onlineServerUrl) }
            // focusGroup() (§4c): the address field and its Save button are one cluster.
            Column(modifier = Modifier.focusGroup()) {
                OutlinedTextField(
                    value = serverUrlDraft,
                    onValueChange = { serverUrlDraft = it },
                    label = { Text("Online server") },
                    placeholder = { Text("ws://192.168.1.23:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { viewModel.setOnlineServerUrl(serverUrlDraft) }
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.setOnlineServerUrl(serverUrlDraft) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text("Save server address")
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("About")
            Text(
                "Also available: a standalone hardware version of part of this " +
                    "project runs as a physical ESP32-based arcade cabinet — the " +
                    "same game logic and AI, built from scratch in C++ for a " +
                    "touchscreen microcontroller with no Android involved.",
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = { showResetConfirmation = true },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Reset all settings")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // resetAll() wipes every setting on this screen (theme, sound, accessibility,
    // card size, difficulty, server address) in one shot with no undo — confirm
    // before calling it rather than firing straight off the button tap.
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset all settings?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        viewModel.resetAll()
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Reads the exact same `LocalCardScale` every real card in the app reads
 * (provided once at MainActivity's root from this same
 * `settings.cardSizePreference`) and applies it itself — PlayingCardView
 * deliberately never scales on its own (see CardScale.kt's KDoc: components
 * like FannedHand compute overlap/fan-width FROM the size they're given, so
 * scaling twice — once there, once again silently inside PlayingCardView —
 * would desync the two). Every card-drawing call site is responsible for
 * applying the multiplier itself; this preview is no exception, it's just
 * reading the same live value the rest of the app does, so what's shown here
 * updates instantly as the slider moves and matches a real in-game card
 * exactly, not a separate approximation of one.
 */
@Composable
private fun CardSizePreview() {
    val scale = LocalCardScale.current
    Box(modifier = Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
        PlayingCardView(
            card = CardVisual(
                id = 0,
                label = "7",
                // UNO's own red (matches UnoScreen's private colorFor(RED) exactly) —
                // a real card color makes it obvious this preview is showing actual
                // game cards, not a generic placeholder swatch.
                backgroundColor = Color(0xFFD32F2F)
            ),
            width = 64.dp * scale,
            height = 92.dp * scale
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingSwitchRow(label: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        )
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    // focusGroup() (§4c): the theme-mode options are one selectable cluster.
    Column(modifier = Modifier.focusGroup()) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == mode, onClick = { onSelect(mode) })
                    .padding(vertical = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // onClick = null (not a duplicate of the Row's own .selectable() above):
                // the standard Material3 pattern for a RadioButton inside a selectable
                // row -- otherwise the RadioButton attaches its OWN independent
                // clickable/focusable semantics node on top of the Row's, and a
                // keyboard user has to Tab through both to get past a single visual
                // option. Confirmed as a real bug on-device (Tab order genuinely
                // double-stopped per row) before this fix, not a hypothetical.
                RadioButton(selected = selected == mode, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> "Follow system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                )
            }
        }
    }
}

@Composable
private fun NamedThemeSelector(selected: NamedTheme, enabled: Boolean, onSelect: (NamedTheme) -> Unit) {
    // Classic, High Contrast, and Midnight Arcade ship with real palettes —
    // see NamedTheme's KDoc. Felt Table remains future work.
    val available = listOf(NamedTheme.CLASSIC, NamedTheme.HIGH_CONTRAST, NamedTheme.MIDNIGHT_ARCADE)
    // focusGroup() (§4c): the palette options are one selectable cluster.
    Column(modifier = Modifier.focusGroup()) {
        available.forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == theme, enabled = enabled, onClick = { onSelect(theme) })
                    .padding(vertical = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // onClick = null -- see ThemeModeSelector's matching comment above for
                // why: the Row's own .selectable() is the real target, this is a pure
                // visual indicator now, not a second independent Tab stop.
                RadioButton(selected = selected == theme, enabled = enabled, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (theme) {
                        NamedTheme.CLASSIC -> "Classic"
                        NamedTheme.HIGH_CONTRAST -> "High Contrast"
                        NamedTheme.MIDNIGHT_ARCADE -> "Midnight Arcade"
                        NamedTheme.FELT_TABLE -> "Felt Table"
                    }
                )
            }
        }
        if (!enabled) {
            Text(
                "Disable dynamic color to pick a palette",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun DifficultySelector(selected: CpuDifficulty, onSelect: (CpuDifficulty) -> Unit) {
    // focusGroup() (§4c): the difficulty chips are one selectable cluster.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
        CpuDifficulty.entries.forEach { difficulty ->
            FilterChip(
                selected = selected == difficulty,
                onClick = { onSelect(difficulty) },
                label = { Text(difficulty.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
        }
    }
}
