package com.gamesuite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubChoicesStore
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubStart
import com.gamesuite.core.MAX_PLAYER_NAME_LENGTH
import com.gamesuite.core.PlayerNameStore
import com.gamesuite.core.SavedGame
import com.gamesuite.core.launchHubMode
import com.gamesuite.core.savedAgoText
import com.gamesuite.core.summarizeHubChoices

private const val HUB_MAX_NAME_SLOTS = 10
private val HubMinTouch = 48.dp

/**
 * The Game Hub (docs/design/game-hub.md): header, Resume cards for this game's saves, a Play button
 * that repeats the last mode, the mode tiles and the selected mode's options panel with an explicit
 * Start button. Built entirely from [spec]; the caller only supplies navigation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameHubScreen(
    spec: GameHubSpec,
    sessionManager: GameSessionManager,
    onStartLocal: (route: String) -> Unit,
    onOpenLobby: (kind: HubModeKind, gameId: String, route: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { HubChoicesStore(context) }
    val nameStore = remember(context) { PlayerNameStore(context) }

    var selectedId by remember(spec.gameId) { mutableStateOf(store.lastModeId(spec)) }
    // The mode the Play button repeats: the last mode the player launched, fixed for this visit.
    val playModeId = remember(spec.gameId) { store.lastModeId(spec) }
    val choicesByMode = remember(spec.gameId) {
        mutableStateMapOf<String, HubChoices>().apply {
            spec.modes.forEach { put(it.id, store.load(spec.gameId, it)) }
        }
    }
    val names = remember(spec.gameId) {
        mutableStateListOf<String>().apply {
            repeat(HUB_MAX_NAME_SLOTS) { add(nameStore.name(it, "")) }
        }
    }

    val routes = remember(spec) { spec.routes() }
    var saveTick by remember { mutableStateOf(0) }
    // Re-read whenever this entry resumes: returning from a game must not show a stale Resume card.
    val resumeTick = rememberResumeTick()
    val saves: List<SavedGame> = remember(saveTick, resumeTick, spec.gameId) {
        sessionManager.savedGames?.all().orEmpty().filter { it.navRoute in routes }
    }
    val now = remember(saveTick, resumeTick) { System.currentTimeMillis() }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<SavedGame?>(null) }

    val panelRequester = remember { BringIntoViewRequester() }
    var scrollToPanelTick by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToPanelTick) {
        if (scrollToPanelTick > 0) panelRequester.bringIntoView()
    }

    BackHandler(onBack = onBack)

    val mode: HubMode = spec.mode(selectedId) ?: spec.modes.first()
    val playMode: HubMode = spec.mode(playModeId) ?: spec.modes.first()
    fun choicesOf(m: HubMode): HubChoices = choicesByMode[m.id] ?: m.defaults()

    fun launch(m: HubMode) {
        val c = m.resolve(choicesOf(m))
        val humanNames: List<String> =
            if (m.kind == HubModeKind.PASS_AND_PLAY) {
                val count = c.int(HubKeys.HUMANS, 2).coerceIn(1, HUB_MAX_NAME_SLOTS)
                names.take(count)
            } else {
                emptyList()
            }
        store.save(spec.gameId, m.id, c)
        store.setLastMode(spec.gameId, m.id)
        if (humanNames.isNotEmpty()) nameStore.setNames(humanNames)
        when (val result = launchHubMode(sessionManager, spec, m, c, humanNames)) {
            is HubLaunch.Local -> onStartLocal(result.route)
            is HubLaunch.Lobby -> onOpenLobby(result.kind, result.gameId, result.route)
        }
    }

    fun onPlay() {
        when (playMode.start) {
            HubStart.READY_SCREEN -> {
                selectedId = playMode.id
                scrollToPanelTick += 1
            }
            HubStart.IMMEDIATE, HubStart.LOBBY -> launch(playMode)
        }
    }

    fun resume(saved: SavedGame) {
        if (sessionManager.resumeSavedGame(saved)) {
            onStartLocal(saved.navRoute)
        } else {
            sessionManager.savedGames?.delete(saved.route)
            saveTick += 1
            message = "That saved game could not be resumed, so it was removed."
        }
    }

    val header: @Composable () -> Unit = { HubHeader(spec, onBack) }

    val messageBlock: @Composable () -> Unit = {
        val text = message
        if (text != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    val resumeBlock: @Composable () -> Unit = {
        if (saves.isNotEmpty()) {
            HubSectionTitle("Resume")
            Spacer(Modifier.height(8.dp))
            saves.forEach { saved ->
                HubResumeCard(
                    saved = saved,
                    now = now,
                    onResume = { resume(saved) },
                    onDiscard = { confirmDiscard = saved }
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val playBlock: @Composable () -> Unit = {
        HubPlayButton(
            title = playMode.title,
            summary = summarizeHubChoices(playMode, choicesOf(playMode)),
            onClick = ::onPlay
        )
        Spacer(Modifier.height(20.dp))
    }

    val tilesBlock: @Composable () -> Unit = {
        HubSectionTitle("Modes")
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.selectableGroup().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            spec.modes.forEach { m ->
                HubModeTile(
                    mode = m,
                    selected = m.id == mode.id,
                    onClick = { selectedId = m.id }
                )
            }
        }
    }

    val panelBlock: @Composable () -> Unit = {
        Column(modifier = Modifier.bringIntoViewRequester(panelRequester)) {
            HubOptionsPanel(
                mode = mode,
                choices = choicesOf(mode),
                names = names,
                onChoices = { choicesByMode[mode.id] = it },
                onStart = { launch(mode) }
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 720.dp
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            header()
            Spacer(Modifier.height(16.dp))
            messageBlock()
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        resumeBlock()
                        playBlock()
                        tilesBlock()
                    }
                    Column(modifier = Modifier.weight(1.2f)) {
                        panelBlock()
                    }
                }
            } else {
                resumeBlock()
                playBlock()
                tilesBlock()
                Spacer(Modifier.height(16.dp))
                panelBlock()
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val pending = confirmDiscard
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this saved game?") },
            text = { Text("The game in progress will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = HubMinTouch).pointerHoverIcon(PointerIcon.Hand),
                    onClick = {
                        sessionManager.savedGames?.delete(pending.route)
                        confirmDiscard = null
                        saveTick += 1
                    }
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = HubMinTouch).pointerHoverIcon(PointerIcon.Hand),
                    onClick = { confirmDiscard = null }
                ) { Text("Keep it") }
            }
        )
    }
}

@Composable
private fun HubHeader(spec: GameHubSpec, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .heightIn(min = HubMinTouch)
                .semantics { contentDescription = "Back to the games menu" }
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Text("← Back", modifier = Modifier.clearAndSetSemantics { })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            spec.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(4.dp))
        Text(spec.tagline, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HubSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubResumeCard(saved: SavedGame, now: Long, onResume: () -> Unit, onDiscard: () -> Unit) {
    val title = saved.summary.ifBlank { saved.label }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("Saved ${savedAgoText(saved.savedAtMillis, now)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .heightIn(min = HubMinTouch)
                        .semantics { contentDescription = "Resume saved game: $title" }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Resume", modifier = Modifier.clearAndSetSemantics { }) }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .heightIn(min = HubMinTouch)
                        .semantics { contentDescription = "Discard saved game: $title" }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) { Text("Discard", modifier = Modifier.clearAndSetSemantics { }) }
            }
        }
    }
}

@Composable
private fun HubPlayButton(title: String, summary: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text("Play: $title", style = MaterialTheme.typography.titleLarge)
            if (summary != title) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HubModeTile(mode: HubMode, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decorative selection mark: the border weight and this tick carry "selected" without colour.
            Text(
                if (selected) "✓" else "○",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics { }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(mode.title, style = MaterialTheme.typography.titleMedium)
                Text(mode.blurb, style = MaterialTheme.typography.bodySmall)
            }
            if (mode.kind == HubModeKind.DAILY) {
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = scheme.tertiaryContainer,
                    contentColor = scheme.onTertiaryContainer
                ) {
                    Text(
                        "Daily",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HubOptionsPanel(
    mode: HubMode,
    choices: HubChoices,
    names: MutableList<String>,
    onChoices: (HubChoices) -> Unit,
    onStart: () -> Unit
) {
    val isLobby = mode.start == HubStart.LOBBY
    val isPass = mode.kind == HubModeKind.PASS_AND_PLAY
    val startLabel = when {
        isLobby -> "Open lobby"
        mode.kind == HubModeKind.DAILY -> "Play today's puzzle"
        else -> "Start game"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp).focusGroup()) {
            Text(
                mode.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(mode.blurb, style = MaterialTheme.typography.bodyMedium)

            if (isLobby) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "You will pick or join a room in the lobby, and the host starts once everyone is in.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                mode.options.forEach { option ->
                    val visible = option.visibleWhen?.invoke(choices) ?: true
                    if (visible) {
                        Spacer(Modifier.height(16.dp))
                        when (option) {
                            is HubOption.Choice -> HubChoiceRow(option, choices) { key, c ->
                                onChoices(hubMarkCustom(mode, c, key))
                            }
                            is HubOption.Toggle -> HubToggleRow(option, choices) { key, c ->
                                onChoices(hubMarkCustom(mode, c, key))
                            }
                            is HubOption.Stepper -> HubStepperRow(option, choices) { key, c ->
                                onChoices(hubMarkCustom(mode, c, key))
                            }
                        }
                    }
                }
                if (isPass) {
                    val count = choices.int(HubKeys.HUMANS, 2).coerceIn(1, HUB_MAX_NAME_SLOTS)
                    Spacer(Modifier.height(16.dp))
                    HubNameFields(count, names)
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text(startLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * After a player tweaks an option a preset had set, flip that preset's Choice to "custom" (when the
 * game offers one) so the chips do not claim "Party" for rules that no longer match it.
 */
private fun hubMarkCustom(mode: HubMode, c: HubChoices, changedKey: String): HubChoices {
    for (o in mode.options) {
        if (o is HubOption.Choice && o.key != changedKey) {
            val picked = o.choices.firstOrNull { it.id == c.string(o.key, o.default) }
            if (picked != null && changedKey in picked.applies && o.choices.any { it.id == "custom" }) {
                return c.with(o.key, "custom")
            }
        }
    }
    return c
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubChoiceRow(option: HubOption.Choice, choices: HubChoices, onChange: (String, HubChoices) -> Unit) {
    val current = choices.string(option.key, option.default)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(option.label, style = MaterialTheme.typography.titleSmall)
        if (option.help.isNotBlank()) {
            Text(option.help, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            option.choices.forEach { choice ->
                val selected = choice.id == current
                FilterChip(
                    selected = selected,
                    onClick = {
                        var c = choices.with(option.key, choice.id)
                        choice.applies.forEach { (k, v) -> c = c.with(k, v) }
                        // A preset's own key never re-marks itself custom.
                        onChange(option.key, c)
                    },
                    label = { Text(choice.label) },
                    leadingIcon = if (selected) {
                        { Text("✓", modifier = Modifier.clearAndSetSemantics { }) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .heightIn(min = HubMinTouch)
                        .semantics { role = Role.RadioButton }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
        val help = option.choices.firstOrNull { it.id == current }?.help.orEmpty()
        if (help.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HubToggleRow(option: HubOption.Toggle, choices: HubChoices, onChange: (String, HubChoices) -> Unit) {
    val checked = choices.bool(option.key, option.default)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HubMinTouch)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onChange(option.key, choices.with(option.key, it)) }
            )
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            if (option.help.isNotBlank()) {
                Text(option.help, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun HubStepperRow(option: HubOption.Stepper, choices: HubChoices, onChange: (String, HubChoices) -> Unit) {
    val value = choices.int(option.key, option.default).coerceIn(option.min, option.max)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            if (option.help.isNotBlank()) {
                Text(option.help, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange(option.key, choices.with(option.key, value - 1)) },
                enabled = value > option.min,
                modifier = Modifier
                    .size(HubMinTouch)
                    .semantics { contentDescription = "Decrease ${option.label}" }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentPadding = PaddingValues(0.dp)
            ) { Text("−", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 40.dp)
                    .semantics {
                        contentDescription = option.label
                        stateDescription = "$value of ${option.min} to ${option.max}"
                    }
            )
            OutlinedButton(
                onClick = { onChange(option.key, choices.with(option.key, value + 1)) },
                enabled = value < option.max,
                modifier = Modifier
                    .size(HubMinTouch)
                    .semantics { contentDescription = "Increase ${option.label}" }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentPadding = PaddingValues(0.dp)
            ) { Text("+", modifier = Modifier.clearAndSetSemantics { }) }
        }
    }
}

@Composable
private fun HubNameFields(count: Int, names: MutableList<String>) {
    Column(modifier = Modifier.fillMaxWidth().focusGroup()) {
        Text("Players", style = MaterialTheme.typography.titleSmall)
        Text(
            "Type a name for each seat, or leave it blank. The device is handed between turns, so pass it along when a turn ends.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        repeat(count) { i ->
            OutlinedTextField(
                value = names[i],
                onValueChange = { names[i] = it.take(MAX_PLAYER_NAME_LENGTH) },
                label = { Text("Player ${i + 1} name") },
                placeholder = { Text(if (count == 1) "You" else "Player ${i + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (i == count - 1) ImeAction.Done else ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = HubMinTouch)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
