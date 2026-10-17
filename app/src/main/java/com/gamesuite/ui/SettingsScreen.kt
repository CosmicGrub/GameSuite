package com.gamesuite.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.games.cards.CardVisual
import com.gamesuite.games.cards.LocalCardScale
import com.gamesuite.games.cards.PlayingCardView
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.GameSpeed
import com.gamesuite.settings.MusicSource
import com.gamesuite.settings.NamedTheme
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.settings.ThemeMode
import com.gamesuite.ui.effects.DialogBackdropBlur
import kotlin.math.roundToInt

/**
 * App-wide settings only; per-game settings (UNO house rules, per-game difficulty overrides, ...)
 * belong on each game's own setup screen, not here. See docs/SETTINGS_THEMING_ACCESSIBILITY.md §1.
 *
 * Grouped into titled sections (Play, Look & feel, Sound & music, Accessibility, Online, About) with a
 * search field on top that filters rows by label/description/section title ([settingMatches]).
 * Music has its own screen (route "music"); this screen only shows a row that opens it.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenMusic: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showResetConfirmation by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Labels (also the text the search field matches against).
    val secPlay = stringResource(R.string.settings_section_play)
    val secLook = stringResource(R.string.settings_section_look)
    val secSound = stringResource(R.string.settings_section_sound)
    val secAccess = stringResource(R.string.settings_section_access)
    val secOnline = stringResource(R.string.settings_section_online)
    val secAbout = stringResource(R.string.settings_section_about)

    val lblCpu = stringResource(R.string.settings_cpu_difficulty)
    val descCpu = stringResource(R.string.settings_cpu_difficulty_desc)
    val lblSpeed = stringResource(R.string.settings_game_speed)
    val descSpeed = stringResource(R.string.settings_game_speed_desc)

    val lblTheme = stringResource(R.string.settings_theme_mode)
    val lblThemeSystem = stringResource(R.string.settings_theme_system)
    val lblThemeLight = stringResource(R.string.settings_theme_light)
    val lblThemeDark = stringResource(R.string.settings_theme_dark)
    val lblDynamic = stringResource(R.string.settings_dynamic_color)
    val descDynamic = stringResource(R.string.settings_dynamic_color_desc)
    val lblPalette = stringResource(R.string.settings_palette)
    val lblPaletteClassic = stringResource(R.string.settings_palette_classic)
    val lblPaletteHc = stringResource(R.string.settings_palette_high_contrast)
    val lblPaletteMidnight = stringResource(R.string.settings_palette_midnight)
    val lblTextSize = stringResource(R.string.settings_text_size_label)
    val textSizeValue = stringResource(R.string.settings_text_size_value, (settings.textScale * 100).roundToInt())
    val lblCardSize = stringResource(R.string.settings_card_size)
    val descCardSize = stringResource(R.string.settings_card_size_desc)
    val lbl3d = stringResource(R.string.settings_3d)
    val desc3d = stringResource(R.string.settings_3d_desc)
    val lblAnim = stringResource(R.string.settings_animations)
    val descAnim = stringResource(R.string.settings_animations_desc)

    val lblSound = stringResource(R.string.settings_sound)
    val lblHaptics = stringResource(R.string.settings_haptics)
    val lblMusic = stringResource(R.string.settings_music)
    val descMusic = stringResource(R.string.settings_music_desc)

    val lblMotion = stringResource(R.string.settings_reduced_motion)
    val descMotion = stringResource(R.string.settings_reduced_motion_desc)
    val lblColorblind = stringResource(R.string.settings_colorblind)
    val descColorblind = stringResource(R.string.settings_colorblind_desc)

    val lblServer = stringResource(R.string.settings_online_server)
    val descServer = stringResource(R.string.settings_online_server_desc)

    val lblVersion = stringResource(R.string.settings_version)
    val blurb = stringResource(R.string.settings_about_blurb)
    val lblReset = stringResource(R.string.settings_reset_all)

    // Row visibility. A query that matches a section title shows that whole section.
    fun inSection(title: String) = settingMatches(query, title)
    fun row(title: String, vararg texts: String) = inSection(title) || settingMatches(query, *texts)

    val showCpu = row(secPlay, lblCpu, descCpu)
    val showSpeed = row(secPlay, lblSpeed, descSpeed)
    val showPlay = showCpu || showSpeed

    val showTheme = row(secLook, lblTheme, lblThemeSystem, lblThemeLight, lblThemeDark)
    val showDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && row(secLook, lblDynamic, descDynamic)
    val showPalette = row(secLook, lblPalette, lblPaletteClassic, lblPaletteHc, lblPaletteMidnight)
    val showTextSize = row(secLook, lblTextSize)
    val showCardSize = row(secLook, lblCardSize, descCardSize)
    val show3d = row(secLook, lbl3d, desc3d)
    val showAnim = row(secLook, lblAnim, descAnim)
    val showLook = showTheme || showDynamic || showPalette || showTextSize || showCardSize || show3d || showAnim

    val showSound = row(secSound, lblSound)
    val showHaptics = row(secSound, lblHaptics)
    val showMusic = row(secSound, lblMusic, descMusic)
    val showSoundSection = showSound || showHaptics || showMusic

    val showMotion = row(secAccess, lblMotion, descMotion)
    val showColorblind = row(secAccess, lblColorblind, descColorblind)
    val showAccess = showMotion || showColorblind

    val showServer = row(secOnline, lblServer, descServer)

    val showVersion = row(secAbout, lblVersion)
    val showBlurb = row(secAbout, blurb)
    val showResetRow = row(secAbout, lblReset)
    val showAbout = showVersion || showBlurb || showResetRow

    val anyVisible = showPlay || showLook || showSoundSection || showAccess || showServer || showAbout

    // Version name + code via PackageManager (BuildConfig may be disabled in this module).
    val context = LocalContext.current
    val versionText = remember(context) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            Pair(info.versionName ?: "?", code)
        } catch (e: Exception) {
            Pair("?", 0L)
        }
    }

    // Wide-window fix: cap the form to a sane reading width so label/switch rows are not stretched
    // edge to edge on a tablet or unfolded Fold.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.settings_back)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_search_label)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(
                            onClick = { query = "" },
                            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) { Text(stringResource(R.string.settings_search_clear)) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            if (!anyVisible) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.settings_no_match), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_no_match_hint), style = MaterialTheme.typography.bodyMedium)
            }

            // ---------------- Play ----------------
            if (showPlay) {
                SettingsSection(secPlay) {
                    if (showCpu) {
                        SettingTitle(lblCpu, descCpu)
                        DifficultySelector(settings.defaultCpuDifficulty, onSelect = viewModel::setDefaultCpuDifficulty)
                        Spacer(Modifier.height(16.dp))
                    }
                    if (showSpeed) {
                        SettingTitle(lblSpeed, descSpeed + " " + speedDescription(settings.gameSpeed))
                        GameSpeedSelector(settings.gameSpeed, onSelect = viewModel::setGameSpeed)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ---------------- Look & feel ----------------
            if (showLook) {
                SettingsSection(secLook) {
                    if (showTheme) {
                        SettingTitle(lblTheme, null)
                        ThemeModeSelector(settings.themeMode, onSelect = viewModel::setThemeMode)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (showDynamic) {
                        SettingSwitchRow(
                            label = lblDynamic,
                            description = descDynamic,
                            checked = settings.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor
                        )
                    }
                    if (showPalette) {
                        SettingTitle(lblPalette, null)
                        NamedThemeSelector(
                            selected = settings.namedTheme,
                            enabled = !settings.dynamicColor,
                            onSelect = viewModel::setNamedTheme
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (showTextSize) {
                        Text(textSizeValue, style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = settings.textScale,
                            onValueChange = viewModel::setTextScale,
                            valueRange = 0.85f..1.5f,
                            steps = 12,
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (showCardSize) {
                        Spacer(Modifier.height(8.dp))
                        SettingTitle(lblCardSize, descCardSize)
                        CardSizePreview()
                        Slider(
                            value = settings.cardSizePreference,
                            onValueChange = viewModel::setCardSizePreference,
                            valueRange = 0f..1f,
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.settings_card_size_small), style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(R.string.settings_card_size_large), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (show3d) {
                        SettingSwitchRow(
                            label = lbl3d,
                            description = desc3d,
                            checked = settings.card3DEnabled,
                            onCheckedChange = viewModel::setCard3DEnabled
                        )
                    }
                    if (showAnim) {
                        SettingSwitchRow(
                            label = lblAnim,
                            description = descAnim,
                            checked = settings.enhancedAnimationsEnabled,
                            onCheckedChange = viewModel::setEnhancedAnimationsEnabled
                        )
                    }
                }
            }

            // ---------------- Sound & music ----------------
            if (showSoundSection) {
                SettingsSection(secSound) {
                    if (showSound) {
                        SettingSwitchRow(
                            label = lblSound,
                            description = null,
                            checked = settings.soundEnabled,
                            onCheckedChange = viewModel::setSoundEnabled
                        )
                    }
                    if (showHaptics) {
                        SettingSwitchRow(
                            label = lblHaptics,
                            description = null,
                            checked = settings.hapticsEnabled,
                            onCheckedChange = viewModel::setHapticsEnabled
                        )
                    }
                    if (showMusic) {
                        val sourceLabel = when (settings.musicSource) {
                            MusicSource.OFF -> stringResource(R.string.settings_music_off)
                            MusicSource.AMBIENCE -> stringResource(R.string.settings_music_ambience)
                            MusicSource.LIBRARY -> stringResource(R.string.settings_music_library)
                            MusicSource.OTHER_APPS -> stringResource(R.string.settings_music_other_apps)
                            else -> ""
                        }
                        NavigationRow(
                            label = lblMusic,
                            description = descMusic,
                            value = sourceLabel,
                            onClick = onOpenMusic
                        )
                    }
                }
            }

            // ---------------- Accessibility ----------------
            if (showAccess) {
                SettingsSection(secAccess) {
                    if (showMotion) {
                        SettingSwitchRow(
                            label = lblMotion,
                            description = descMotion,
                            checked = settings.reducedMotion,
                            onCheckedChange = viewModel::setReducedMotion
                        )
                    }
                    if (showColorblind) {
                        SettingSwitchRow(
                            label = lblColorblind,
                            description = descColorblind,
                            checked = settings.colorblindMode,
                            onCheckedChange = viewModel::setColorblindMode
                        )
                    }
                }
            }

            // ---------------- Online ----------------
            if (showServer) {
                SettingsSection(secOnline) {
                    Text(descServer, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    var serverUrlDraft by remember(settings.onlineServerUrl) { mutableStateOf(settings.onlineServerUrl) }
                    OutlinedTextField(
                        value = serverUrlDraft,
                        onValueChange = { serverUrlDraft = it },
                        label = { Text(lblServer) },
                        placeholder = { Text("ws://192.168.1.23:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { viewModel.setOnlineServerUrl(serverUrlDraft) }
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.setOnlineServerUrl(serverUrlDraft) },
                        modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(stringResource(R.string.settings_online_save))
                    }
                }
            }

            // ---------------- About ----------------
            if (showAbout) {
                SettingsSection(secAbout) {
                    if (showVersion) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lblVersion, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_version_value, versionText.first, versionText.second),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (showBlurb) {
                        Spacer(Modifier.height(4.dp))
                        Text(blurb, style = MaterialTheme.typography.labelSmall)
                    }
                    if (showResetRow) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showResetConfirmation = true },
                            modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Text(lblReset)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // resetAll() restores every setting (theme, sound, music, accessibility, card size, difficulty,
    // server address) to its default in one shot with no undo, so confirm first.
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            // Real OS-compositor blur behind this dialog's window; see DialogBackdropBlur's doc.
            title = { DialogBackdropBlur(); Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        viewModel.resetAll()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

/**
 * Reads the exact same `LocalCardScale` every real card in the app reads (provided once at
 * MainActivity's root from `settings.cardSizePreference`) and applies it itself; PlayingCardView
 * deliberately never scales on its own (see CardScale.kt's KDoc), so this preview updates live as
 * the slider moves and matches a real in-game card exactly.
 */
@Composable
private fun CardSizePreview() {
    val scale = LocalCardScale.current
    Box(modifier = Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
        PlayingCardView(
            card = CardVisual(
                id = 0,
                label = "7",
                // UNO's own red, so the preview reads as an actual game card.
                backgroundColor = Color(0xFFD32F2F)
            ),
            width = 64.dp * scale,
            height = 92.dp * scale
        )
    }
}

/** A titled group of rows: a heading, then the rows as one focus cluster. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
    HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Column(modifier = Modifier.focusGroup(), content = content)
}

@Composable
private fun SettingTitle(label: String, description: String?) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    if (description != null) {
        Spacer(Modifier.height(2.dp))
        Text(description, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingSwitchRow(label: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // The whole row is the toggle target (48dp min, one Tab stop, one semantics node); the Switch
    // inside is a pure indicator.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun NavigationRow(label: String, description: String?, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("$value  ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.focusGroup()) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(vertical = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // onClick = null: the Row's own .selectable() is the real target (one Tab stop).
                RadioButton(selected = selected == mode, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                )
            }
        }
    }
}

@Composable
private fun NamedThemeSelector(selected: NamedTheme, enabled: Boolean, onSelect: (NamedTheme) -> Unit) {
    // Felt Table remains future work (no palette yet).
    val available = listOf(NamedTheme.CLASSIC, NamedTheme.HIGH_CONTRAST, NamedTheme.MIDNIGHT_ARCADE)
    Column(modifier = Modifier.focusGroup()) {
        available.forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected == theme, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(theme) })
                    .padding(vertical = 4.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == theme, enabled = enabled, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (theme) {
                        NamedTheme.CLASSIC -> stringResource(R.string.settings_palette_classic)
                        NamedTheme.HIGH_CONTRAST -> stringResource(R.string.settings_palette_high_contrast)
                        NamedTheme.MIDNIGHT_ARCADE -> stringResource(R.string.settings_palette_midnight)
                        NamedTheme.FELT_TABLE -> "Felt Table"
                    }
                )
            }
        }
        if (!enabled) {
            Text(
                stringResource(R.string.settings_palette_disabled),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun GameSpeedSelector(selected: GameSpeed, onSelect: (GameSpeed) -> Unit) {
    // A wrapping row: four chips do not fit a foldable's cover screen in one line.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.focusGroup()
    ) {
        GameSpeed.values().forEach { speed ->
            FilterChip(
                selected = selected == speed,
                onClick = { onSelect(speed) },
                label = { Text(speedLabel(speed)) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun DifficultySelector(selected: CpuDifficulty, onSelect: (CpuDifficulty) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
        CpuDifficulty.entries.forEach { difficulty ->
            FilterChip(
                selected = selected == difficulty,
                onClick = { onSelect(difficulty) },
                label = { Text(difficultyLabel(difficulty)) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            )
        }
    }
}

@Composable
private fun speedLabel(speed: GameSpeed): String = stringResource(
    when (speed) {
        GameSpeed.SLOW -> R.string.settings_speed_slow
        GameSpeed.RELAXED -> R.string.settings_speed_relaxed
        GameSpeed.NORMAL -> R.string.settings_speed_normal
        GameSpeed.QUICK -> R.string.settings_speed_quick
    }
)

@Composable
private fun speedDescription(speed: GameSpeed): String = stringResource(
    when (speed) {
        GameSpeed.SLOW -> R.string.settings_speed_slow_desc
        GameSpeed.RELAXED -> R.string.settings_speed_relaxed_desc
        GameSpeed.NORMAL -> R.string.settings_speed_normal_desc
        GameSpeed.QUICK -> R.string.settings_speed_quick_desc
    }
)

@Composable
private fun difficultyLabel(difficulty: CpuDifficulty): String = stringResource(
    when (difficulty) {
        CpuDifficulty.EASY -> R.string.settings_difficulty_easy
        CpuDifficulty.MEDIUM -> R.string.settings_difficulty_medium
        CpuDifficulty.HARD -> R.string.settings_difficulty_hard
    }
)
