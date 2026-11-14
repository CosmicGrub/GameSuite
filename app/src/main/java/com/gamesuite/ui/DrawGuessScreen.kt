package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.drawguess.DrawGuessConfig
import com.gamesuite.games.drawguess.DrawGuessGame
import com.gamesuite.games.drawguess.DrawGuessWordBank
import com.gamesuite.games.drawguess.DrawLimits
import com.gamesuite.games.drawguess.DrawPhase
import com.gamesuite.games.drawguess.DrawState
import com.gamesuite.games.drawguess.DrawWord
import com.gamesuite.games.drawguess.WordLang
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.SecureWindowEffect
import kotlinx.coroutines.delay
import java.util.Locale

private val CurtainColor = Color(0xFF120C24)

/**
 * Draw and Guess (same screen, 2 to 8 people). The drawer sees the word only behind a curtain ("Pass the device to X",
 * tap to start, then press and hold to see the word) and draws with a finger on a 4:3 paper: six colour-blind-safe
 * colours (each with its own mark), three brushes, eraser, undo, clear. Everyone else buzzes in with their own name;
 * a buzzer gets one go at six picture cards (or typing). The clock stops while someone guesses.
 *
 * ACCESSIBILITY: every control is at least 56dp; words are always picture AND text; turn changes, the word being
 * revealed to the drawer, correct and wrong guesses and the end of time are announced through a polite live region;
 * "hold to see the word" is also a plain activate-to-toggle for TalkBack. Drawing itself needs a finger, so the
 * game suits a sighted drawer with blind or low-vision guessers describing nothing aloud: the help says so.
 * Layout: portrait scrolls (paper on top); a wide window puts the paper left and the controls in a side column.
 * MOTION: none beyond the paper itself, so Reduced Motion needs no special case.
 * PERFORMANCE: the paper is cached Paths plus one growing Path (see DrawingCanvas); the clock redraws only its own text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawGuessScreen(
    sessionManager: GameSessionManager,
    game: DrawGuessGame,
    @Suppress("UNUSED_PARAMETER") settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    // Keyed on the live locale (the manifest handles locale changes without recreating), so a runtime switch is followed.
    val localeTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language ?: Locale.getDefault().language
    val lang = remember(localeTag) { WordLang.fromLanguageTag(localeTag) }
    val s = game.state.value

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        // A context that re-emits (a new instance) while a match is live must not start another one over it.
        if (game.hasLiveMatch) return@LaunchedEffect
        game.firstMover = sessionManager.launchOptions.firstMoverIndex
        game.config = DrawGuessConfig.fromExtras(sessionManager.launchOptions.extras, lang)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
        // The words follow the device language now, whatever language a saved game was started in.
        game.config = game.config.copy(lang = lang)
    }

    var announcement by remember { mutableStateOf("") }
    // Held as a state object (not read here): only TurnHeader reads it, so only the clock text recomposes four times a second.
    val clockTick = remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(DrawTool()) }
    var canvasFull by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    var announcedLowTime by remember { mutableStateOf(false) }

    val helpTitle = stringResource(R.string.draw_guess_help_title)
    val helpBody = stringResource(R.string.draw_guess_help_body)
    val leaveTitle = stringResource(R.string.draw_guess_leave_title)

    // The clock: a few ticks a second while drawing. It ends the turn when the time is up.
    val drawing = s?.phase == DrawPhase.DRAW
    LaunchedEffect(drawing, s?.turn) {
        while (drawing) {
            delay(250)
            if (game.tick()) {
                haptics(HapticSignal.FAILURE)
                playSfx(SfxKind.INVALID_BUZZ)
            }
            clockTick.intValue = clockTick.intValue + 1
            val left = game.remainingMs()
            if (left != null && left in 1..10_000 && !announcedLowTime) {
                announcedLowTime = true
                announcement = androidContext.getString(R.string.draw_guess_announce_ten_seconds)
            }
        }
    }
    LaunchedEffect(s?.turn) { announcedLowTime = false; canvasFull = false }

    // Spoken announcements for each change of turn and outcome.
    val phase = s?.phase
    // (turn, locked-out count) last seen by the effect below. A wrong guess is the locked-out count GROWING within a turn;
    // cancelling a guess or restoring a saved turn changes the phase but not the count, and must stay silent.
    val seenLock = remember { intArrayOf(-1, 0) }
    LaunchedEffect(phase, s?.turn, s?.lockedOut?.size) {
        val st = game.state.value ?: return@LaunchedEffect
        val res = st.result
        val wrongNow = st.phase == DrawPhase.DRAW && st.turn == seenLock[0] && st.lockedOut.size > seenLock[1]
        seenLock[0] = st.turn
        seenLock[1] = st.lockedOut.size
        announcement = when {
            st.phase == DrawPhase.CHOOSE ->
                androidContext.getString(R.string.draw_guess_pass_title, game.playerName(st.drawerSeat)) + ". " +
                    androidContext.getString(R.string.draw_guess_pass_hint, game.playerName(st.drawerSeat))
            st.phase == DrawPhase.REVEAL && res != null && res.solved ->
                androidContext.getString(
                    R.string.draw_guess_announce_correct, game.playerName(res.guesserSeat),
                    DrawGuessWordBank.byKey(res.wordKey)?.text(lang) ?: "", res.guesserPoints
                )
            st.phase == DrawPhase.REVEAL && res != null ->
                androidContext.getString(R.string.draw_guess_announce_nobody, DrawGuessWordBank.byKey(res.wordKey)?.text(lang) ?: "")
            wrongNow ->
                androidContext.getString(R.string.draw_guess_announce_wrong, game.playerName(st.lockedOut.last()))
            else -> announcement
        }
        if (st.phase == DrawPhase.REVEAL && res != null) {
            if (res.solved) { haptics(HapticSignal.SUCCESS); playSfx(SfxKind.SUCCESS_CHIME) }
        } else if (wrongNow) {
            haptics(HapticSignal.FAILURE); playSfx(SfxKind.INVALID_BUZZ)
        } else if (st.phase == DrawPhase.OVER) {
            haptics(HapticSignal.CELEBRATION)
        }
    }

    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpBody, matchInProgress = false,
            onLeave = { game.abortMatch() }, onAbort = { game.abortMatch() }, leaveTitle = leaveTitle
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (game.unplayable.value) R.string.draw_guess_need_two else R.string.draw_guess_loading), fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        return
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpBody,
        matchInProgress = s.phase != DrawPhase.OVER,
        onLeave = { game.leaveSession() },
        onAbort = { game.abortMatch() },
        leaveTitle = leaveTitle,
        leaveBody = stringResource(R.string.draw_guess_leave_body)
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (s.phase) {
                DrawPhase.CHOOSE -> ChooseStep(game, s, lang, haptics)
                DrawPhase.READY -> ReadyStep(game, s, lang)
                DrawPhase.OVER -> OverStep(game, s, lang)
                else -> PlayStep(
                    game = game, s = s, lang = lang, tool = tool, onTool = { tool = it },
                    canvasFull = canvasFull, onFull = { canvasFull = true }, onRoom = { canvasFull = false }, clockTick = clockTick,
                    onSkip = { confirmSkip = true }, haptics = haptics
                )
            }
            // One polite live region carries every spoken update; it draws nothing.
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        }
    }

    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text(stringResource(R.string.draw_guess_skip_title)) },
            text = { Text(stringResource(R.string.draw_guess_skip_body)) },
            confirmButton = { TextButton(onClick = { confirmSkip = false; game.giveUp() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.draw_guess_skip_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmSkip = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.draw_guess_skip_keep)) } }
        )
    }
}

// ---- small shared pieces --------------------------------------------------------------------------

/** The opaque hand-over cover: nothing of the next turn shows until the right person taps. */
@Composable
private fun Curtain(title: String, hint: String, button: String, onShow: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(CurtainColor)
            .clickable(enabled = true, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(hint, color = Color(0xFFD9D4EA), textAlign = TextAlign.Center)
            Button(onClick = onShow, modifier = Modifier.heightIn(min = 64.dp)) { Text(button, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun PlayerLabel(game: DrawGuessGame, seat: Int): String =
    if (game.teamMode) stringResource(R.string.draw_guess_player_team, game.playerName(seat), game.teamOf(seat) + 1) else game.playerName(seat)

/** Press and hold to see the word (let go to hide it); activating it with a screen reader toggles it instead. */
@Composable
private fun HoldToReveal(word: DrawWord, lang: WordLang, glyphSize: Dp, modifier: Modifier = Modifier) {
    var holding by remember { mutableStateOf(false) }
    var toggled by remember { mutableStateOf(false) }
    val showing = holding || toggled
    // The word is on screen only now: keep it out of the Recents thumbnail and screenshots for exactly this long.
    SecureWindowEffect(showing)
    // The screen-reader toggle must not leave the word shown (and so speakable) for the whole turn.
    LaunchedEffect(toggled) { if (toggled) { delay(5_000); toggled = false } }
    val holdLabel = stringResource(R.string.draw_guess_hold_to_see)
    val showDesc = stringResource(R.string.draw_guess_toggle_show)
    val hideDesc = stringResource(R.string.draw_guess_toggle_hide)
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (showing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
            .border(3.dp, MaterialTheme.colorScheme.outline, shape)
            .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) }
            .semantics {
                role = Role.Button
                contentDescription = if (toggled) hideDesc else showDesc
                onClick(label = if (toggled) hideDesc else showDesc) { toggled = !toggled; true }
            },
        contentAlignment = Alignment.Center
    ) {
        if (showing) {
            Row(
                // No live region: the word is read only if the drawer moves focus here, never announced to the table.
                // This node exists only while the word is revealed (hold or toggle); otherwise the neutral hold label is all there is.
                Modifier.padding(12.dp).clearAndSetSemantics { contentDescription = word.text(lang) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlyphTile(word.key, glyphSize)
                Text(word.text(lang), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            }
        } else {
            Text(holdLabel, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---- CHOOSE: curtain, then three pictures ---------------------------------------------------------

@Composable
private fun ChooseStep(game: DrawGuessGame, s: DrawState, lang: WordLang, haptics: (HapticSignal) -> Unit) {
    var shown by rememberSaveable(s.turn) { mutableStateOf(false) }
    val name = game.playerName(s.drawerSeat)
    // The three offered words are visible once the curtain is gone.
    SecureWindowEffect(shown)
    if (!shown) {
        Curtain(
            title = stringResource(R.string.draw_guess_pass_title, name),
            hint = stringResource(R.string.draw_guess_pass_hint, name),
            button = stringResource(R.string.draw_guess_pass_button, name),
            onShow = { shown = true; haptics(HapticSignal.LIGHT_TICK) }
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val glyph = ((maxWidth - 24.dp - 16.dp - 16.dp) / 3).coerceIn(56.dp, 150.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.draw_guess_choose_title, name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.draw_guess_choose_hint), textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.offered.forEachIndexed { i, key ->
                    val word = DrawGuessWordBank.byKey(key) ?: return@forEachIndexed
                    WordCard(
                        word = word, lang = lang, glyphSize = glyph, showDifficulty = true,
                        description = stringResource(R.string.draw_guess_choice_desc, word.text(lang), word.difficulty),
                        modifier = Modifier.weight(1f),
                        onClick = { haptics(HapticSignal.NORMAL_ACTION); game.chooseWord(i) }
                    )
                }
            }
        }
    }
}

// ---- READY: hold to see the word, then start ------------------------------------------------------

@Composable
private fun ReadyStep(game: DrawGuessGame, s: DrawState, lang: WordLang) {
    val word = DrawGuessWordBank.byKey(s.wordKey ?: "") ?: return
    Box(Modifier.fillMaxSize().background(CurtainColor), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()).widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.draw_guess_ready_title, game.playerName(s.drawerSeat)), color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.draw_guess_ready_hint), color = Color(0xFFD9D4EA), textAlign = TextAlign.Center)
            HoldToReveal(word, lang, glyphSize = 96.dp)
            Button(onClick = { game.beginDrawing() }, modifier = Modifier.heightIn(min = 64.dp).fillMaxWidth()) {
                Text(stringResource(R.string.draw_guess_ready_start), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---- DRAW / GUESS / REVEAL ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayStep(
    game: DrawGuessGame,
    s: DrawState,
    lang: WordLang,
    tool: DrawTool,
    onTool: (DrawTool) -> Unit,
    canvasFull: Boolean,
    onFull: () -> Unit,
    onRoom: () -> Unit,
    clockTick: MutableIntState,
    onSkip: () -> Unit,
    haptics: (HapticSignal) -> Unit
) {
    val word = DrawGuessWordBank.byKey(s.wordKey ?: "") ?: return
    val interactive = s.phase == DrawPhase.DRAW && game.clockRunning.value
    // The stroke list is a plain ArrayList nobody observes: reading the version here is what refreshes the stroke count,
    // and Undo / Clear enabling, after a stroke is finished, undone or cleared.
    @Suppress("UNUSED_VARIABLE") val boardVersion = game.boardVersion.value
    val strokeCount = game.board.strokes.size
    val hasStrokes = strokeCount > 0
    // "Page is full" stays up only while a cap is really in the way: Undo or Clear (a version bump) takes it down again.
    val pageFull = canvasFull && game.board.capHit
    val canvasDesc = stringResource(if (interactive) R.string.draw_guess_canvas_desc else R.string.draw_guess_canvas_desc_view, strokeCount)

    val header: @Composable () -> Unit = { TurnHeader(game, s, clockTick) }
    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        Box(canvasModifier) {
            DrawingCanvas(game = game, tool = tool, interactive = interactive, description = canvasDesc, onFull = onFull, onRoom = onRoom)
            if (s.phase == DrawPhase.DRAW && !game.clockRunning.value) {
                Box(
                    Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Color(0xCC120C24))
                        .clickable(role = Role.Button) { game.resume() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.draw_guess_resume_clock), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.draw_guess_resume_hint), color = Color(0xFFD9D4EA), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    val side: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s.phase) {
                DrawPhase.DRAW -> DrawControls(game, s, word, lang, tool, onTool, pageFull, hasStrokes, onSkip, haptics)
                DrawPhase.GUESS -> GuessPanel(game, s, lang, haptics)
                else -> RevealPanel(game, s, lang)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        if (wide) {
            // Landscape: the paper stays 4:3 and square-ish to the height that is left (the header takes about 84dp),
            // never wider than its column; the controls sit beside it, and the column scrolls if even that is too tall.
            val paperWidth = maxOf((maxHeight - 84.dp) * (4f / 3f), 160.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    header()
                    canvas(Modifier.widthIn(max = paperWidth))
                }
                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { side() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                header()
                canvas(Modifier)
                side()
            }
        }
    }
}

@Composable
private fun TurnHeader(game: DrawGuessGame, s: DrawState, clockTick: MutableIntState) {
    // The only reader of the clock tick: this header recomposes on each tick, the rest of the play panel does not.
    @Suppress("UNUSED_VARIABLE") val t = clockTick.intValue
    val left = game.remainingMs()
    val limit = game.config.limitMs
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.draw_guess_drawing_by, PlayerLabel(game, s.drawerSeat)), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.draw_guess_turn_progress, s.turn + 1, s.totalTurns), style = MaterialTheme.typography.bodySmall)
            }
            if (s.phase == DrawPhase.DRAW || s.phase == DrawPhase.GUESS) {
                val text = if (left == null) stringResource(R.string.draw_guess_time_none)
                else stringResource(R.string.draw_guess_time_fmt, (left / 60_000).toInt(), ((left / 1000) % 60).toInt())
                val desc = if (left == null) text else stringResource(R.string.draw_guess_time_desc, (left / 1000).toInt())
                Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.semantics { contentDescription = desc })
            }
        }
        if (left != null && limit > 0 && (s.phase == DrawPhase.DRAW || s.phase == DrawPhase.GUESS)) {
            val frac = (left.toFloat() / limit).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawControls(
    game: DrawGuessGame,
    s: DrawState,
    word: DrawWord,
    lang: WordLang,
    tool: DrawTool,
    onTool: (DrawTool) -> Unit,
    canvasFull: Boolean,
    hasStrokes: Boolean,
    onSkip: () -> Unit,
    haptics: (HapticSignal) -> Unit
) {
    val selectedWord = stringResource(R.string.draw_guess_tool_selected)
    val colorNames = listOf(
        R.string.draw_guess_color_0, R.string.draw_guess_color_1, R.string.draw_guess_color_2,
        R.string.draw_guess_color_3, R.string.draw_guess_color_4, R.string.draw_guess_color_5
    ).map { stringResource(it) }
    val brushNames = listOf(R.string.draw_guess_brush_small, R.string.draw_guess_brush_medium, R.string.draw_guess_brush_large).map { stringResource(it) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until DrawLimits.PALETTE_SIZE) {
            ColorSwatch(i, selected = !tool.eraser && tool.color == i, name = colorNames[i], selectedWord = selectedWord) { onTool(tool.copy(color = i, eraser = false)) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until DrawLimits.BRUSH_COUNT) {
            BrushButton(i, selected = tool.size == i, name = brushNames[i], selectedWord = selectedWord) { onTool(tool.copy(size = i)) }
        }
        ToolTextButton(stringResource(R.string.draw_guess_tool_eraser), selected = tool.eraser, toggle = true) { onTool(tool.copy(eraser = !tool.eraser)) }
        ToolTextButton(stringResource(R.string.draw_guess_tool_undo), enabled = hasStrokes) { if (game.undoStroke()) haptics(HapticSignal.LIGHT_TICK) }
        ToolTextButton(stringResource(R.string.draw_guess_tool_clear), enabled = hasStrokes) { game.clearBoard(); haptics(HapticSignal.NORMAL_ACTION) }
    }
    if (canvasFull) {
        Text(stringResource(R.string.draw_guess_canvas_full), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    HoldToReveal(word, lang, glyphSize = 40.dp)

    Text(stringResource(R.string.draw_guess_buzz_hint), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val eligible = game.eligibleGuessers(s.drawerSeat)
        eligible.forEach { seat ->
            val out = seat in s.lockedOut
            val label = PlayerLabel(game, seat)
            val desc = if (out) stringResource(R.string.draw_guess_buzz_out, label) else stringResource(R.string.draw_guess_buzz_desc, label)
            Box(
                Modifier.heightIn(min = 56.dp).widthIn(min = 96.dp)
                    .alpha(if (out) 0.45f else 1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                    .clickable(enabled = !out, role = Role.Button) { haptics(HapticSignal.STRONG_ACTION); game.buzz(seat) }
                    .semantics { contentDescription = desc }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontWeight = FontWeight.Bold, textDecoration = if (out) TextDecoration.LineThrough else TextDecoration.None, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    ToolTextButton(stringResource(R.string.draw_guess_skip), modifier = Modifier.fillMaxWidth(), onClick = onSkip)
}

@Composable
private fun GuessPanel(game: DrawGuessGame, s: DrawState, lang: WordLang, haptics: (HapticSignal) -> Unit) {
    val name = game.playerName(s.guesserSeat)
    Text(stringResource(R.string.draw_guess_guess_title, name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    if (game.config.typeIt) {
        var text by remember(s.guesserSeat, s.turn) { mutableStateOf("") }
        Text(stringResource(R.string.draw_guess_guess_type_hint))
        val submit = { if (text.trim().length >= 2) game.guessTyped(text) }
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(30) }, singleLine = true,
            label = { Text(stringResource(R.string.draw_guess_guess_type_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        Button(onClick = { submit() }, enabled = text.trim().length >= 2, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.draw_guess_guess_submit), fontWeight = FontWeight.Bold)
        }
    } else {
        Text(stringResource(R.string.draw_guess_guess_pick_hint))
        s.options.chunked(3).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKeys.forEach { key ->
                    val word = DrawGuessWordBank.byKey(key) ?: return@forEach
                    val tried = key in s.eliminated
                    WordCard(
                        word = word, lang = lang, glyphSize = 56.dp, crossedOut = tried,
                        description = if (tried) stringResource(R.string.draw_guess_card_tried, word.text(lang)) else word.text(lang),
                        modifier = Modifier.weight(1f),
                        onClick = { haptics(HapticSignal.NORMAL_ACTION); game.guessCard(key) }
                    )
                }
            }
        }
    }
    ToolTextButton(stringResource(R.string.draw_guess_guess_cancel), modifier = Modifier.fillMaxWidth()) { game.cancelGuess() }
}

@Composable
private fun RevealPanel(game: DrawGuessGame, s: DrawState, lang: WordLang) {
    val res = s.result ?: return
    val word = DrawGuessWordBank.byKey(res.wordKey) ?: return
    val headline = when {
        res.solved -> stringResource(R.string.draw_guess_reveal_correct, game.playerName(res.guesserSeat))
        res.timedOut -> stringResource(R.string.draw_guess_reveal_timeup)
        else -> stringResource(R.string.draw_guess_reveal_nobody)
    }
    Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.semantics { heading() })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        GlyphTile(word.key, 88.dp)
        Text(stringResource(R.string.draw_guess_reveal_word, word.text(lang)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    if (res.solved) {
        Text(stringResource(R.string.draw_guess_reveal_guesser, game.playerName(res.guesserSeat), res.guesserPoints))
        Text(stringResource(R.string.draw_guess_reveal_drawer, game.playerName(s.drawerSeat), res.drawerPoints))
    }
    Scoreboard(game, s)
    val last = s.turn + 1 >= s.totalTurns
    val next = if (last) stringResource(R.string.draw_guess_reveal_finish)
    else stringResource(R.string.draw_guess_reveal_next, game.playerName(game.order[(s.turn + 1) % game.order.size]))
    Button(onClick = { game.nextTurn() }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(next, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Running totals: per player, and per team in team play. */
@Composable
private fun Scoreboard(game: DrawGuessGame, s: DrawState) {
    Text(stringResource(R.string.draw_guess_scores_title), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    if (game.teamMode) {
        game.teamScores().forEachIndexed { team, pts ->
            Text(stringResource(R.string.draw_guess_score_line, stringResource(R.string.draw_guess_team_name, team + 1), pts), fontWeight = FontWeight.SemiBold)
        }
    }
    game.order.forEach { seat ->
        Text(stringResource(R.string.draw_guess_score_line, PlayerLabel(game, seat), s.scores[seat]))
    }
}

// ---- OVER ------------------------------------------------------------------------------------------

@Composable
private fun OverStep(game: DrawGuessGame, s: DrawState, @Suppress("UNUSED_PARAMETER") lang: WordLang) {
    val winners = game.winners()
    val headline = when {
        winners.isEmpty() || (!game.teamMode && winners.size > 1) -> stringResource(R.string.draw_guess_over_tie)
        game.teamMode -> stringResource(R.string.draw_guess_over_winner_team, stringResource(R.string.draw_guess_team_name, game.teamOf(winners.first()) + 1))
        else -> stringResource(R.string.draw_guess_over_winner, game.playerName(winners.first()))
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.draw_guess_over_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (game.teamMode) {
                game.teamScores().forEachIndexed { team, pts ->
                    Text(stringResource(R.string.draw_guess_score_line, stringResource(R.string.draw_guess_team_name, team + 1), pts), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            game.standings().forEachIndexed { rank, seat ->
                Text(
                    stringResource(R.string.draw_guess_rank_line, rank + 1, PlayerLabel(game, seat), s.scores[seat]),
                    fontWeight = if (seat in winners) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Button(onClick = { game.playAgain() }, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 64.dp)) {
            Text(stringResource(R.string.draw_guess_over_again), fontWeight = FontWeight.Bold)
        }
        Button(onClick = { game.leaveSession() }, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 64.dp)) {
            Text(stringResource(R.string.draw_guess_over_menu), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
}
