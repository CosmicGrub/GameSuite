package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.actitout.ActItOutBand
import com.gamesuite.games.actitout.ActItOutCard
import com.gamesuite.games.actitout.ActItOutCategory
import com.gamesuite.games.actitout.ActItOutDefaults
import com.gamesuite.games.actitout.ActItOutEngine
import com.gamesuite.games.actitout.ActItOutFormat
import com.gamesuite.games.actitout.ActItOutGame
import com.gamesuite.games.actitout.ActItOutPhase
import com.gamesuite.games.actitout.ActItOutRules
import com.gamesuite.games.actitout.ActItOutSetup
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.SecureWindowEffect
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class ActItOutColors(
    val bg: Color, val text: Color, val card: Color, val accent: Color, val onAccent: Color,
    val good: Color, val bad: Color, val muted: Color
)

private fun actItOutColors(dark: Boolean) = if (!dark) ActItOutColors(
    bg = Color(0xFFFBF5EE), text = Color(0xFF2B1E26), card = Color(0xFFF1E4EC), accent = Color(0xFF8E2B6D),
    onAccent = Color.White, good = Color(0xFF1B7F4F), bad = Color(0xFFB3261E), muted = Color(0xFF6E5C66)
) else ActItOutColors(
    bg = Color(0xFF17131A), text = Color(0xFFF3E9F0), card = Color(0xFF2A2230), accent = Color(0xFFF08CCB),
    onAccent = Color(0xFF101014), good = Color(0xFF4CD08C), bad = Color(0xFFFF7A70), muted = Color(0xFFB7A6B1)
)

private val Control = 56.dp
private const val HOLD_MS = 700f

private fun categoryRes(c: ActItOutCategory): Int = when (c) {
    ActItOutCategory.ANIMALS -> R.string.act_it_out_cat_animals
    ActItOutCategory.ACTIONS -> R.string.act_it_out_cat_actions
    ActItOutCategory.EVERYDAY -> R.string.act_it_out_cat_everyday
    ActItOutCategory.SCENES -> R.string.act_it_out_cat_scenes
    ActItOutCategory.SPORTS -> R.string.act_it_out_cat_sports
    ActItOutCategory.FEELINGS -> R.string.act_it_out_cat_feelings
    ActItOutCategory.HARD -> R.string.act_it_out_cat_hard
}

private fun bandRes(b: ActItOutBand): Int = when (b) {
    ActItOutBand.LITTLE -> R.string.act_it_out_band_little
    ActItOutBand.EVERYONE -> R.string.act_it_out_band_everyone
    ActItOutBand.GROWN_UPS -> R.string.act_it_out_band_grownups
}

/** The card's words in the current language: the `act_it_out_card_<id>` string, else the English original. */
private fun Context.cardText(card: ActItOutCard): String {
    val id = resources.getIdentifier(card.stringName, "string", packageName)
    if (id == 0) return card.english
    return try { getString(id) } catch (e: android.content.res.Resources.NotFoundException) { card.english }
}

/**
 * Act It Out on one shared device: setup, then pass-the-device turns with a curtain, a hold-to-start pad, the card,
 * big Correct and Skip buttons and a timer ring. All rules are in the Compose-free engine.
 *
 * Accessibility: every control is at least 56dp; the timer's seconds are NOT a live region (they would be read every
 * second): one polite status line speaks only milestones, scores and skips; Reduced Motion drops the card pop; all
 * text uses MaterialTheme typography so the app's text-size setting applies; wide screens put the card beside the
 * buttons. The hold pad also answers a plain TalkBack activation, and a setup switch turns hold into a tap.
 */
@Composable
fun ActItOutScreen(
    sessionManager: GameSessionManager,
    game: ActItOutGame,
    @Suppress("UNUSED_PARAMETER") settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = actItOutColors(dark)
    val reduced = LocalReducedMotion.current
    val version by game.version

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.defaults = ActItOutDefaults.fromExtras(sessionManager.launchOptions.extras)
        game.defaultPlayerName = { seat -> androidContext.getString(R.string.act_it_out_default_player_name, seat + 1) }
        game.init(ctx)
        // A finished match must not stay resumable: its save lives under the nav route (= game id) and this launch's roster.
        game.setOnDiscardSave { sessionManager.savedGames?.delete(sessionManager.saveKey(game.gameId, ctx)) }
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // Leaving the foreground puts a running turn back behind the curtain, so the card is never on show to whoever
    // the phone is handed to (idempotent: the shell's own onPause forward does the same).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, game) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) game.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @Suppress("UNUSED_VARIABLE") val subscribe = version
    val setup = game.setup
    val engine = game.engine
    val phase = engine?.phase
    val teamWord = stringResource(R.string.act_it_out_team_word)

    var status by remember { mutableStateOf("") }
    var remaining by remember { mutableLongStateOf(0L) }

    // The clock: one engine tick per frame while a turn runs; the status line speaks milestones only.
    LaunchedEffect(engine, phase) {
        val e = engine ?: return@LaunchedEffect
        if (e.phase != ActItOutPhase.PLAYING) return@LaunchedEffect
        var last = e.remainingMs()
        remaining = last
        var lastSecs = ActItOutRules.secondsLeft(last)
        status = ""
        while (true) {
            withFrameMillis { }
            game.tick()
            val now = e.remainingMs()
            remaining = now
            val secs = ActItOutRules.secondsLeft(now)
            if (e.config.buzzer && secs != lastSecs && secs in 1..ActItOutRules.BUZZER_SECONDS) {
                playSfx(SfxKind.LIGHT_TICK)
                haptics(HapticSignal.LIGHT_TICK)
            }
            ActItOutRules.milestoneCrossed(e.config.roundSeconds, last, now)?.let {
                status = androidContext.getString(R.string.act_it_out_status_seconds_left, it)
            }
            last = now
            lastSecs = secs
            if (e.phase != ActItOutPhase.PLAYING) break
        }
    }

    LaunchedEffect(phase) {
        if (phase == ActItOutPhase.TIMES_UP) {
            status = androidContext.getString(R.string.act_it_out_status_times_up)
            if (engine?.config?.buzzer == true) {
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
            }
        }
        if (phase == ActItOutPhase.END) haptics(HapticSignal.CELEBRATION)
        if (phase == ActItOutPhase.CURTAIN) status = ""
    }

    val helpTitle = stringResource(R.string.act_it_out_help_title)
    val helpText = stringResource(R.string.act_it_out_help_text)
    val (_, openCoach) = rememberClCoach("act-it-out", ready = true)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = engine != null && phase != ActItOutPhase.END,
        onLeave = game::leaveSession,
        onAbort = { if (game.finishedResult != null) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = game.finishedResult == null,
        buttonFill = colors.bg, buttonContent = colors.text
    ) {
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = GameChromeEndInset),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClCoachButton("act-it-out", openCoach)
                Text(
                    text = when {
                        setup != null -> stringResource(R.string.act_it_out_setup_title)
                        engine != null && phase != ActItOutPhase.END ->
                            stringResource(R.string.act_it_out_round_banner, engine.round + 1, engine.config.rounds)
                        else -> ""
                    },
                    color = colors.text, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() }, textAlign = TextAlign.Center
                )
            }
            if (setup != null) {
                AiSetup(game, setup, colors)
            } else if (engine != null) {
                val sideName = engine.config.sideName(engine.side, teamWord)
                when (engine.phase) {
                    ActItOutPhase.CURTAIN -> AiCurtain(engine, sideName, colors) {
                        haptics(HapticSignal.NORMAL_ACTION)
                        game.startTurn()
                    }
                    ActItOutPhase.PLAYING -> AiPlaying(
                        engine = engine, sideName = sideName, remaining = remaining, status = status, colors = colors,
                        reduced = reduced,
                        onCorrect = {
                            val p = game.markCorrect()
                            if (p > 0) {
                                haptics(HapticSignal.SUCCESS)
                                playSfx(SfxKind.SUCCESS_CHIME)
                                status = androidContext.getString(
                                    R.string.act_it_out_status_correct, p, sideName, engine.scores[engine.side]
                                )
                            }
                        },
                        onSkip = {
                            if (game.skip()) {
                                haptics(HapticSignal.LIGHT_TICK)
                                playSfx(SfxKind.WHOOSH)
                                status = androidContext.getString(R.string.act_it_out_status_skip, engine.skipsLeft)
                            }
                        }
                    )
                    ActItOutPhase.TIMES_UP -> AiTimesUp(
                        engine, status, colors,
                        onGotIt = { game.resolveLastCard(true) },
                        onMissed = { game.resolveLastCard(false) }
                    )
                    ActItOutPhase.TURN_SUMMARY -> AiSummary(engine, sideName, colors) { game.continueAfterSummary() }
                    ActItOutPhase.SCOREBOARD -> AiScoreboard(engine, teamWord, colors) { game.continueFromScoreboard() }
                    ActItOutPhase.END -> AiEnd(engine, teamWord, colors, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() })
                }
            }
        }
    }
}

// ---- small building blocks ----

@Composable
private fun AiButton(text: String, colors: ActItOutColors, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = Control),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
    ) { Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}

@Composable
private fun AiOutlineButton(text: String, colors: ActItOutColors, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = Control),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
    ) { Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}

/** A selectable pill (a radio choice) at least 56dp tall. */
@Composable
private fun AiChoice(selected: Boolean, label: String, colors: ActItOutColors, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = Control).widthIn(min = Control)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) colors.accent else colors.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) colors.onAccent else colors.text,
            style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AiSwitchRow(label: String, checked: Boolean, colors: ActItOutColors, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Control)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // The row is the control; the switch itself only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AiHeading(text: String, colors: ActItOutColors) {
    Text(
        text, color = colors.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp).semantics { heading() }
    )
}

/** A name field that keeps what is typed (spaces included) while the setup stores the cleaned name. */
@Composable
private fun AiNameField(initial: String, hint: String, modifier: Modifier = Modifier, onName: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(ActItOutRules.MAX_NAME_LENGTH); onName(text) },
        label = { Text(hint) },
        singleLine = true,
        modifier = modifier.heightIn(min = Control)
    )
}

// ---- setup ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSetup(game: ActItOutGame, setup: ActItOutSetup, colors: ActItOutColors) {
    val ctx = LocalContext.current
    fun changed() = game.setupChanged()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        AiHeading(stringResource(R.string.act_it_out_format_title), colors)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiChoice(setup.format == ActItOutFormat.TEAMS, stringResource(R.string.act_it_out_format_teams), colors) { setup.setFormat(ActItOutFormat.TEAMS); changed() }
            AiChoice(setup.format == ActItOutFormat.FREE_FOR_ALL, stringResource(R.string.act_it_out_format_ffa), colors) { setup.setFormat(ActItOutFormat.FREE_FOR_ALL); changed() }
        }

        AiHeading(stringResource(R.string.act_it_out_time_title), colors)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in ActItOutRules.TIME_CHOICES) {
                AiChoice(setup.roundSeconds == t, stringResource(R.string.act_it_out_time_option, t), colors) { setup.roundSeconds = t; changed() }
            }
        }

        AiHeading(stringResource(R.string.act_it_out_rounds_title), colors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { setup.rounds = (setup.rounds - 1).coerceAtLeast(ActItOutRules.MIN_ROUNDS); changed() },
                enabled = setup.rounds > ActItOutRules.MIN_ROUNDS,
                modifier = Modifier.size(Control).semantics { contentDescription = ctx.getString(R.string.act_it_out_rounds_less) },
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text(
                stringResource(R.string.act_it_out_rounds_value, setup.rounds), color = colors.text,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 12.dp)
            )
            OutlinedButton(
                onClick = { setup.rounds = (setup.rounds + 1).coerceAtMost(ActItOutRules.MAX_ROUNDS); changed() },
                enabled = setup.rounds < ActItOutRules.MAX_ROUNDS,
                modifier = Modifier.size(Control).semantics { contentDescription = ctx.getString(R.string.act_it_out_rounds_more) },
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }

        Spacer(Modifier.height(8.dp))
        AiSwitchRow(stringResource(R.string.act_it_out_hold_toggle), setup.holdToReveal, colors) { setup.holdToReveal = it; changed() }
        AiSwitchRow(stringResource(R.string.act_it_out_buzzer_toggle), setup.buzzer, colors) { setup.buzzer = it; changed() }

        if (setup.format == ActItOutFormat.TEAMS) {
            for (team in 0..1) {
                val label = stringResource(R.string.act_it_out_team_label, com.gamesuite.games.actitout.ActItOutTeams.letter(team))
                AiHeading(label, colors)
                key("team-name-$team") {
                    AiNameField(setup.teamNames[team], stringResource(R.string.act_it_out_team_name_hint), Modifier.fillMaxWidth()) {
                        setup.renameTeam(team, it)
                    }
                }
                AiBandPicker(setup, team, label, colors) { changed() }
                Text(
                    stringResource(R.string.act_it_out_team_size, setup.teamSizes()[team]),
                    color = colors.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                for (p in 0 until setup.playerCount) {
                    if (setup.teamOf[p] != team) continue
                    val otherLabel = stringResource(R.string.act_it_out_team_label, com.gamesuite.games.actitout.ActItOutTeams.letter(1 - team))
                    // Keyed by seat: a row (and its typed text) follows its player when teams change or reshuffle.
                    key(p) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AiNameField(setup.names[p], stringResource(R.string.act_it_out_player_name_hint), Modifier.weight(1f)) {
                                setup.rename(p, it)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { if (setup.togglePlayerTeam(p)) changed() },
                                modifier = Modifier.heightIn(min = Control).semantics {
                                    contentDescription = ctx.getString(R.string.act_it_out_move_to_team, setup.names[p], otherLabel)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text)
                            ) { Text(stringResource(R.string.act_it_out_move_short, otherLabel), style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AiOutlineButton(stringResource(R.string.act_it_out_shuffle_teams), colors, Modifier.fillMaxWidth()) {
                setup.reshuffleTeams(); changed()
            }
        } else {
            AiHeading(stringResource(R.string.act_it_out_players_title), colors)
            for (p in 0 until setup.playerCount) {
                key(p) {
                    AiNameField(setup.names[p], stringResource(R.string.act_it_out_player_name_hint), Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        setup.rename(p, it)
                    }
                    AiBandPicker(setup, p, setup.names[p], colors) { changed() }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        AiButton(stringResource(R.string.act_it_out_start), colors, Modifier.fillMaxWidth(), enabled = setup.canStart()) {
            game.beginMatch()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiBandPicker(setup: ActItOutSetup, side: Int, who: String, colors: ActItOutColors, changed: () -> Unit) {
    Text(
        stringResource(R.string.act_it_out_band_title, who), color = colors.muted,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in ActItOutBand.values()) {
            AiChoice(setup.band(side) == b, stringResource(bandRes(b)), colors) { setup.setBand(side, b); changed() }
        }
    }
    if (setup.handicapOffered) {
        AiSwitchRow(stringResource(R.string.act_it_out_bonus_toggle, who), setup.bonus(side), colors) {
            setup.setBonus(side, it); changed()
        }
    }
}

// ---- match stages ----

@Composable
private fun AiCurtain(engine: ActItOutEngine, sideName: String, colors: ActItOutColors, onStart: () -> Unit) {
    val actor = engine.actorName()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.act_it_out_curtain_title, actor), color = colors.text,
            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.act_it_out_curtain_side, sideName), color = colors.accent, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.act_it_out_curtain_hint, actor), color = colors.muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (engine.resumedTurn) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.act_it_out_curtain_resumed, ActItOutRules.secondsLeft(engine.startingMs())),
                color = colors.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        AiHoldPad(
            label = stringResource(if (engine.config.holdToReveal) R.string.act_it_out_curtain_hold else R.string.act_it_out_curtain_tap),
            holdingLabel = stringResource(R.string.act_it_out_curtain_holding),
            hold = engine.config.holdToReveal, colors = colors, onDone = onStart
        )
    }
}

/**
 * The big start pad. Hold mode fills a bar over [HOLD_MS] while a finger stays down; lifting early starts over.
 * A plain TalkBack activation (the semantics click) starts at once, so nobody is locked out by the hold.
 */
@Composable
private fun AiHoldPad(label: String, holdingLabel: String, hold: Boolean, colors: ActItOutColors, onDone: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth().widthIn(max = 520.dp).heightIn(min = 180.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.card)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { onDone(); true }
            }
            .pointerInput(hold) {
                if (!hold) {
                    detectTapGestures(onTap = { onDone() })
                } else {
                    detectTapGestures(onPress = {
                        holding = true
                        coroutineScope {
                            val job = launch {
                                val t0 = System.nanoTime()
                                while (true) {
                                    delay(16)
                                    val p = (System.nanoTime() - t0) / 1_000_000f / HOLD_MS
                                    progress = p.coerceAtMost(1f)
                                    if (p >= 1f) {
                                        onDone()
                                        break
                                    }
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                        }
                        holding = false
                        progress = 0f
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(colors.accent, Offset.Zero, Size(size.width * progress, size.height))
        }
        Text(
            if (holding) holdingLabel else label,
            color = if (progress > 0.5f) colors.onAccent else colors.text,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp).clearAndSetSemantics { }
        )
    }
}

@Composable
private fun AiCardFace(card: ActItOutCard, colors: ActItOutColors, glyphSize: Dp, reduced: Boolean, modifier: Modifier = Modifier) {
    // While a card is on screen the window is secure (no Recents thumbnail or screenshot spoils it); cleared when the card leaves.
    SecureWindowEffect(true)
    val ctx = LocalContext.current
    // Keyed on the locale too: the activity survives a language change, so the cached text must not.
    val localeTag = LocalConfiguration.current.locales.toLanguageTags()
    val text = remember(card.id, localeTag) { ctx.cardText(card) }
    // A small pop when a new card arrives; with Reduced Motion the card just appears.
    val pop = remember(card.id) { Animatable(if (reduced) 1f else 0.92f) }
    LaunchedEffect(card.id) { if (!reduced) pop.animateTo(1f, tween(160)) }
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp)).background(colors.card)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(categoryRes(card.category)), color = colors.muted, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        ActItOutGlyphView(
            card.glyph, card.category, colors.text, colors.card,
            Modifier.size(glyphSize * pop.value)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text, color = colors.text, style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AiTimerRing(remainingMs: Long, totalMs: Long, colors: ActItOutColors, size: Dp) {
    val secs = ActItOutRules.secondsLeft(remainingMs)
    val hot = secs <= ActItOutRules.BUZZER_SECONDS
    val color = if (hot) colors.bad else colors.accent
    // The digits follow the app's text-size setting (theme typography); the RING grows to fit them, so a large
    // system font neither overflows the ring nor ignores the setting.
    val digitDp = with(LocalDensity.current) { MaterialTheme.typography.displayMedium.fontSize.toDp() }
    val ringSize = maxOf(size, digitDp * 2.8f)
    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.minDimension * 0.1f
            val inset = w / 2f
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(colors.card, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            val frac = if (totalMs > 0) (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        // Visual only: reading the seconds out every second would drown the status line.
        Text(
            secs.toString(), color = colors.text, style = MaterialTheme.typography.displayMedium,
            maxLines = 1, softWrap = false,
            fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
private fun AiPlaying(
    engine: ActItOutEngine, sideName: String, remaining: Long, status: String, colors: ActItOutColors, reduced: Boolean,
    onCorrect: () -> Unit, onSkip: () -> Unit
) {
    val card = engine.card ?: return
    val skipLabel = if (engine.skipsLeft > 0) stringResource(R.string.act_it_out_skip_left, engine.skipsLeft) else stringResource(R.string.act_it_out_skip_none)
    val total = engine.config.roundSeconds * 1000L
    // `remaining` changes every frame while a turn runs; reading it here is what redraws the ring.
    val live = remaining.let { engine.remainingMs() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val wide = maxWidth > maxHeight * 1.15f
        val glyph = if (wide) minOf(maxHeight * 0.45f, 200.dp) else minOf(maxWidth * 0.5f, maxHeight * 0.28f, 200.dp)
        val buttons: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AiButton(stringResource(R.string.act_it_out_correct), colors, Modifier.fillMaxWidth().heightIn(min = 88.dp), onClick = onCorrect)
                AiOutlineButton(skipLabel, colors, Modifier.fillMaxWidth(), enabled = engine.skipsLeft > 0, onClick = onSkip)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    AiCardFace(card, colors, glyph, reduced, Modifier.fillMaxWidth())
                    ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 8.dp), emphasized = true)
                }
                Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.act_it_out_acting, engine.actorName(), sideName), color = colors.muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    AiTimerRing(live, total, colors, 150.dp)
                    buttons(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.act_it_out_acting, engine.actorName(), sideName), color = colors.muted,
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                    )
                    AiTimerRing(live, total, colors, 96.dp)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AiCardFace(card, colors, glyph, reduced, Modifier.fillMaxWidth())
                }
                ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(vertical = 6.dp), emphasized = true)
                buttons(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AiTimesUp(engine: ActItOutEngine, status: String, colors: ActItOutColors, onGotIt: () -> Unit, onMissed: () -> Unit) {
    val card = engine.card
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.act_it_out_times_up_title), color = colors.bad, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        ClStatusLine(status, colors.text, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (card != null) AiCardFace(card, colors, 120.dp, true, Modifier.fillMaxWidth().widthIn(max = 520.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.act_it_out_times_up_ask), color = colors.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        AiButton(stringResource(R.string.act_it_out_got_it), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onGotIt)
        Spacer(Modifier.height(8.dp))
        AiOutlineButton(stringResource(R.string.act_it_out_missed), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onMissed)
    }
}

@Composable
private fun AiSummary(engine: ActItOutEngine, sideName: String, colors: ActItOutColors, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.act_it_out_summary_title, sideName, engine.turnPoints), color = colors.text,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.act_it_out_summary_detail, engine.turnCorrect, engine.turnSkipped), color = colors.muted,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        AiButton(stringResource(R.string.act_it_out_continue), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onContinue)
    }
}

@Composable
private fun AiStandings(engine: ActItOutEngine, teamWord: String, colors: ActItOutColors) {
    val order = engine.config.sides.indices.sortedByDescending { engine.scores[it] }
    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).semantics(mergeDescendants = true) { }) {
        for (i in order) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(colors.card).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(engine.config.sideName(i, teamWord), color = colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(engine.scores[i].toString(), color = colors.accent, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AiScoreboard(engine: ActItOutEngine, teamWord: String, colors: ActItOutColors, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.act_it_out_scoreboard_title, engine.round, engine.config.rounds), color = colors.text,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(12.dp))
        AiStandings(engine, teamWord, colors)
        Spacer(Modifier.height(20.dp))
        AiButton(stringResource(R.string.act_it_out_start_round, engine.round + 1), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onContinue)
    }
}

@Composable
private fun AiEnd(engine: ActItOutEngine, teamWord: String, colors: ActItOutColors, onPlayAgain: () -> Unit, onLeave: () -> Unit) {
    val leaders = engine.leaders()
    val best = engine.scores.maxOrNull() ?: 0
    val title = if (leaders.size == 1 && best > 0) {
        stringResource(R.string.act_it_out_end_winner, engine.config.sideName(leaders[0], teamWord))
    } else {
        stringResource(R.string.act_it_out_end_tie)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title, color = colors.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp)
        )
        AiStandings(engine, teamWord, colors)
        Spacer(Modifier.height(20.dp))
        AiButton(stringResource(R.string.act_it_out_play_again), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onPlayAgain)
        Spacer(Modifier.height(8.dp))
        AiOutlineButton(stringResource(R.string.act_it_out_back_to_menu), colors, Modifier.fillMaxWidth().widthIn(max = 520.dp), onClick = onLeave)
    }
}
