package com.gamesuite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import com.gamesuite.core.gameGlyph
import com.gamesuite.settings.LocalReducedMotion
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.gamesuite.R
import com.gamesuite.core.ClubViewModel
import com.gamesuite.core.GameCoach
import com.gamesuite.ui.club.CoachCard
import com.gamesuite.ui.club.CoachHelpButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    clubViewModel: ClubViewModel,
    onStartLocal: (route: String) -> Unit,
    onOpenLobby: (kind: HubModeKind, gameId: String, route: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // Reading the configuration re-resolves the catalog text when the locale changes.
    val configuration = LocalConfiguration.current
    val localize: (String) -> String = remember(context, configuration) { { text -> HubText.resolve(context, text) } }
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
    // A failure shows in the error colours; a plain confirmation ("tips hidden") does not.
    var messageIsError by remember { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf<SavedGame?>(null) }

    val reducedMotion = LocalReducedMotion.current
    val tipsStore = remember(context) { GameTipsStore(context) }
    val tipLines = remember(spec.gameId, configuration) {
        GameTips.tipsFor(spec.gameId).indices.map { GameTips.localized(context, spec.gameId, it) }
    }
    var tipsDismissed by remember(spec.gameId) { mutableStateOf(tipsStore.isDismissed(spec.gameId)) }
    val scrollState = rememberScrollState()
    var scrollToTopTick by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) {
            if (reducedMotion) scrollState.scrollTo(0) else scrollState.animateScrollTo(0)
        }
    }
    // A plain confirmation is announced once (live region) and then clears itself; an error stays.
    LaunchedEffect(message, messageIsError) {
        if (message != null && !messageIsError) {
            kotlinx.coroutines.delay(8000L)
            message = null
        }
    }
    val accent = gameAccent(spec.gameId, spec.family)
    val onAccent = onGameAccent(accent)

    val panelRequester = remember { BringIntoViewRequester() }
    var scrollToPanelTick by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToPanelTick) {
        if (scrollToPanelTick > 0) panelRequester.bringIntoView()
    }

    BackHandler(onBack = onBack)

    // Club coach ("How to play"): opened by the (?) button, and once by itself on a first visit.
    // It waits for the profile to load (null) so a game already seen never flashes the card, and
    // the check runs once per visit (rememberSaveable survives rotation). Games whose own screen has
    // a first-run card of its own are skipped (GameCoach.ownCoachInGame), so there is never a second card.
    val hasCoach = remember(spec.gameId) { GameCoach.forGame(spec.gameId) != null }
    val club by clubViewModel.profile.collectAsStateWithLifecycle()
    var coachOpen by rememberSaveable(spec.gameId) { mutableStateOf(false) }
    var coachAutoChecked by rememberSaveable(spec.gameId) { mutableStateOf(false) }
    LaunchedEffect(club, spec.gameId) {
        val profile = club ?: return@LaunchedEffect
        if (!coachAutoChecked) {
            coachAutoChecked = true
            if (GameCoach.autoOpensInHub(spec.gameId) && profile.shouldAutoCoach(spec.gameId)) coachOpen = true
        }
    }

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
            messageIsError = true
            message = context.getString(R.string.hub_ui_resume_failed)
        }
    }

    val header: @Composable () -> Unit = {
        HubHeader(spec, accent, onBack, onCoach = if (hasCoach) ({ coachOpen = true }) else null)
    }

    val messageBlock: @Composable () -> Unit = {
        val text = message
        if (text != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(12.dp),
                color = if (messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    val resumeBlock: @Composable () -> Unit = {
        if (saves.isNotEmpty()) {
            HubSectionTitle(stringResource(R.string.hub_ui_resume))
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
            title = localize(playMode.title),
            summary = summarizeHubChoices(playMode, choicesOf(playMode), localize),
            accent = accent,
            onAccent = onAccent,
            onClick = ::onPlay
        )
        Spacer(Modifier.height(20.dp))
    }

    val tilesBlock: @Composable () -> Unit = {
        HubSectionTitle(stringResource(R.string.hub_ui_modes))
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

    val tipsBlock: @Composable () -> Unit = {
        if (tipLines.isNotEmpty() && !tipsDismissed) {
            HubTipCard(
                gameTitle = localize(spec.title),
                lines = tipLines,
                onDismiss = {
                    tipsStore.setDismissed(spec.gameId, true)
                    tipsDismissed = true
                    messageIsError = false
                    message = context.getString(R.string.tip_dismissed_notice)
                }
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    val showTipsBlock: @Composable () -> Unit = {
        if (tipLines.isNotEmpty() && tipsDismissed) {
            val gameTitle = localize(spec.title)
            val showAgainDescription = stringResource(R.string.tip_show_again_desc, gameTitle)
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = {
                    tipsStore.setDismissed(spec.gameId, false)
                    tipsDismissed = false
                    messageIsError = false
                    message = context.getString(R.string.tip_shown_notice)
                    scrollToTopTick += 1
                },
                modifier = Modifier
                    .heightIn(min = HubMinTouch)
                    .semantics { contentDescription = showAgainDescription }
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text(stringResource(R.string.tip_show_again), modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }

    HubEnterEffect(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                header()
                Spacer(Modifier.height(16.dp))
                messageBlock()
                tipsBlock()
                if (wide) {
                    // Each column is a traversal group so a screen reader finishes the left column
                    // (saves, Play, modes) before the options panel, the order sighted users read in.
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    isTraversalGroup = true
                                    traversalIndex = 0f
                                }
                        ) {
                            resumeBlock()
                            playBlock()
                            tilesBlock()
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .semantics {
                                    isTraversalGroup = true
                                    traversalIndex = 1f
                                }
                        ) {
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
                showTipsBlock()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (coachOpen && hasCoach) {
        // Any dismissal counts as seen, so it opens by itself only the first time.
        CoachCard(gameId = spec.gameId, onDismiss = {
            coachOpen = false
            clubViewModel.markCoachSeen(spec.gameId)
        })
    }

    val pending = confirmDiscard
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(stringResource(R.string.hub_ui_discard_title)) },
            text = { Text(stringResource(R.string.hub_ui_discard_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = HubMinTouch).pointerHoverIcon(PointerIcon.Hand),
                    onClick = {
                        sessionManager.savedGames?.delete(pending.route)
                        confirmDiscard = null
                        saveTick += 1
                        messageIsError = false
                        message = context.getString(R.string.polish_discarded_notice)
                    }
                ) { Text(stringResource(R.string.hub_ui_discard)) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = HubMinTouch).pointerHoverIcon(PointerIcon.Hand),
                    onClick = { confirmDiscard = null }
                ) { Text(stringResource(R.string.hub_ui_keep)) }
            }
        )
    }
}

@Composable
private fun HubHeader(spec: GameHubSpec, accent: Color, onBack: () -> Unit, onCoach: (() -> Unit)? = null) {
    val backDescription = stringResource(R.string.hub_ui_back_desc)
    val scheme = MaterialTheme.colorScheme
    // The band is the game's accent at 14% over the page surface: text on it keeps the theme's own
    // (very high) onSurface contrast, and the solid left edge carries the identity.
    val band = accent.copy(alpha = 0.14f).compositeOver(scheme.surface)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = band,
        contentColor = scheme.onSurface
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier
                            .heightIn(min = HubMinTouch)
                            .semantics { contentDescription = backDescription }
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(stringResource(R.string.hub_ui_back), modifier = Modifier.clearAndSetSemantics { })
                    }
                    Spacer(Modifier.weight(1f))
                    if (onCoach != null) {
                        CoachHelpButton(
                            gameId = spec.gameId, onClick = onCoach,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Decorative: the title next to it names the game.
                    Text(
                        gameGlyph(spec.gameId),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics { }
                    )
                    Text(
                        hubText(spec.title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(hubText(spec.tagline), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The dismissible "How it works" card at the top of a Hub (see [GameTips]). */
@Composable
private fun HubTipCard(gameTitle: String, lines: List<String>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dismissDescription = stringResource(R.string.tip_dismiss_desc, gameTitle)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true },
        shape = RoundedCornerShape(16.dp),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.tip_card_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    // The bullet is decoration; each line reads as a sentence.
                    Text("•", modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics { })
                    Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSecondaryContainer),
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = HubMinTouch)
                    .semantics { contentDescription = dismissDescription }
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text(stringResource(R.string.tip_dismiss), modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/**
 * Fades the Hub in and slides it up a little (200 ms) when it first appears, as the shared
 * transition between the Games list and a Hub. The NavHost lives in MainActivity, so the Hub plays
 * the entrance itself. Nothing animates when [LocalReducedMotion] is on, nor when the Hub is
 * recomposed after a rotation or process restore (the "played" flag is saved).
 */
@Composable
fun HubEnterEffect(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    var played by rememberSaveable { mutableStateOf(false) }
    val animate = remember { !reducedMotion && !played }
    val slidePx = with(LocalDensity.current) { 16.dp.roundToPx() }
    LaunchedEffect(Unit) { played = true }
    if (!animate) {
        Box(modifier = modifier) { content() }
    } else {
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            modifier = modifier,
            enter = fadeIn(animationSpec = tween(durationMillis = HUB_ENTER_MS)) +
                slideInVertically(animationSpec = tween(durationMillis = HUB_ENTER_MS)) { slidePx }
        ) {
            content()
        }
    }
}

private const val HUB_ENTER_MS = 200

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
    val context = LocalContext.current
    val resumeDescription = stringResource(R.string.hub_ui_resume_desc, title)
    val discardDescription = stringResource(R.string.hub_ui_discard_desc, title)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.hub_ui_saved, HubText.savedAgo(context, saved.savedAtMillis, now)), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .heightIn(min = HubMinTouch)
                        .semantics { contentDescription = resumeDescription }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.hub_ui_resume), modifier = Modifier.clearAndSetSemantics { }) }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .heightIn(min = HubMinTouch)
                        .semantics { contentDescription = discardDescription }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) { Text(stringResource(R.string.hub_ui_discard), modifier = Modifier.clearAndSetSemantics { }) }
            }
        }
    }
}

@Composable
private fun HubPlayButton(title: String, summary: String, accent: Color, onAccent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pointerHoverIcon(PointerIcon.Hand),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.hub_ui_play_title, title), style = MaterialTheme.typography.titleLarge)
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
                Text(hubText(mode.title), style = MaterialTheme.typography.titleMedium)
                Text(hubText(mode.blurb), style = MaterialTheme.typography.bodySmall)
            }
            if (mode.kind == HubModeKind.DAILY) {
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = scheme.tertiaryContainer,
                    contentColor = scheme.onTertiaryContainer
                ) {
                    Text(
                        stringResource(R.string.badge_daily),
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
        isLobby -> stringResource(R.string.hub_ui_open_lobby)
        mode.kind == HubModeKind.DAILY -> stringResource(R.string.hub_ui_play_daily)
        else -> stringResource(R.string.hub_ui_start_game)
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
                hubText(mode.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(hubText(mode.blurb), style = MaterialTheme.typography.bodyMedium)

            if (isLobby) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.hub_ui_lobby_note),
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
        Text(hubText(option.label), style = MaterialTheme.typography.titleSmall)
        if (option.help.isNotBlank()) {
            Text(hubText(option.help), style = MaterialTheme.typography.bodySmall)
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
                    label = { Text(hubText(choice.label)) },
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
            Text(hubText(help), style = MaterialTheme.typography.bodySmall)
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
            Text(hubText(option.label), style = MaterialTheme.typography.bodyLarge)
            if (option.help.isNotBlank()) {
                Text(hubText(option.help), style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun HubStepperRow(option: HubOption.Stepper, choices: HubChoices, onChange: (String, HubChoices) -> Unit) {
    val value = choices.int(option.key, option.default).coerceIn(option.min, option.max)
    val label = hubText(option.label)
    val decreaseDescription = stringResource(R.string.hub_ui_decrease, label)
    val increaseDescription = stringResource(R.string.hub_ui_increase, label)
    val rangeDescription = stringResource(R.string.hub_ui_stepper_state, value, option.min, option.max)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(hubText(option.label), style = MaterialTheme.typography.bodyLarge)
            if (option.help.isNotBlank()) {
                Text(hubText(option.help), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange(option.key, choices.with(option.key, value - 1)) },
                enabled = value > option.min,
                modifier = Modifier
                    .size(HubMinTouch)
                    .semantics { contentDescription = decreaseDescription }
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
                        contentDescription = label
                        stateDescription = rangeDescription
                        // The new value is spoken after each tap on the minus / plus button.
                        liveRegion = LiveRegionMode.Polite
                    }
            )
            OutlinedButton(
                onClick = { onChange(option.key, choices.with(option.key, value + 1)) },
                enabled = value < option.max,
                modifier = Modifier
                    .size(HubMinTouch)
                    .semantics { contentDescription = increaseDescription }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentPadding = PaddingValues(0.dp)
            ) { Text("+", modifier = Modifier.clearAndSetSemantics { }) }
        }
    }
}

@Composable
private fun HubNameFields(count: Int, names: MutableList<String>) {
    Column(modifier = Modifier.fillMaxWidth().focusGroup()) {
        Text(stringResource(R.string.hub_ui_players), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.hub_ui_names_help),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        repeat(count) { i ->
            OutlinedTextField(
                value = names[i],
                onValueChange = { names[i] = it.take(MAX_PLAYER_NAME_LENGTH) },
                label = { Text(stringResource(R.string.hub_ui_player_name, i + 1)) },
                placeholder = { Text(if (count == 1) hubText("You") else stringResource(R.string.hub_ui_player_n, i + 1)) },
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
