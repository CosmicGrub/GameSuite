package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.piedetective.PieBand
import com.gamesuite.games.piedetective.PieCast
import com.gamesuite.games.piedetective.PieClue
import com.gamesuite.games.piedetective.PieDetectiveGame
import com.gamesuite.games.piedetective.PieEventCode
import com.gamesuite.games.piedetective.PieHint
import com.gamesuite.games.piedetective.PiePhase
import com.gamesuite.games.piedetective.PieResult
import com.gamesuite.games.piedetective.PieRules
import com.gamesuite.games.piedetective.PieState
import com.gamesuite.games.piedetective.PieTokens
import com.gamesuite.games.piedetective.PieTrait
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pie Detective on one screen (design doc docs/design/kids-games/pie-detective.md). Cooperative: the team shares one
 * cast of animals, takes turns choosing a clue, peeks through the magic lens, tells the others, and clears animals that
 * do not match until the thief can be caught before it reaches the back gate.
 *
 * Drawing: the lane (with the thief token that glides between the four places) is ONE Canvas; the animals are small
 * static canvases that only redraw when the state changes. Targets: every card, trait button and action is at least
 * 48dp (trait buttons 72dp, actions 56dp). TalkBack: each animal reads its name and accessories, the lens is a toggle
 * button that announces the clue sentence, the status line is the single polite live region, the notebook is described
 * as a list. Reduced Motion: no pulse, the thief jumps instead of gliding, the lens works by tap. Pause: the Sidekick
 * and the Sneak step wait while a dialog is open or the window lost focus. No reading is needed (icons and shapes carry
 * every answer: Yes is a green circle with a tick, No an orange rounded square with a cross). Same screen only.
 */
@Composable
fun PieDetectiveScreen(
    sessionManager: GameSessionManager,
    game: PieDetectiveGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scheme = if (dark) PieTokens.dark else PieTokens.light
    var rotate by remember { mutableStateOf(true) }
    var showTutorial by remember { mutableStateOf(false) }
    var uiHold by remember { mutableStateOf(false) }
    val ownPause = remember { booleanArrayOf(false) }
    val touchExploration = remember { (androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.band = PieBand.fromId(extras["band"])
        game.hints = extras["hints"] != "false"
        game.autoFlip = extras["autoflip"] == "true"
        game.handicap = extras["handicap"] ?: "none"
        rotate = extras["rotate"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // Pause while a dialog is up or the window lost focus: the Sidekick and the Sneak step wait, a half-made peek is hidden.
    LaunchedEffect(uiHold) {
        if (uiHold) {
            game.pause()
            ownPause[0] = true
        } else if (ownPause[0]) {
            ownPause[0] = false
            game.resume()
        }
    }

    val helpTitle = stringResource(R.string.pie_detective_help_title)
    val helpText = stringResource(R.string.pie_detective_help_text)
    val s = state(game) ?: return
    val players = context?.players ?: return
    val seatCount = players.size
    val humans = players.count { !it.isBot }
    fun seatName(seat: Int): String {
        val p = players.getOrNull(seat) ?: return ""
        return if (p.isBot) androidContext.getString(R.string.pie_detective_sidekick_name) else androidContext.seatLabel(p.displayName)
    }
    val seatIsBot = players.getOrNull(s.currentSeat)?.isBot == true
    val session = game.engine.session

    // The big answer shown to everybody for two seconds after "Show everyone".
    var bigClue by remember { mutableStateOf<PieClue?>(null) }
    var lastClueCount by remember { mutableStateOf(s.clues.size) }
    var flipBounce by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<PieHint?>(null) }
    var lucky by remember { mutableStateOf(false) }

    LaunchedEffect(s.clues.size, s.caseIndex) {
        if (s.clues.size > lastClueCount) {
            bigClue = s.clues.last()
            delay(if (reduced) 150L else PieRules.SHOW_MS)
            bigClue = null
        } else if (s.clues.isEmpty()) bigClue = null
        lastClueCount = s.clues.size
    }
    LaunchedEffect(flipBounce) { if (flipBounce) { delay(1500L); flipBounce = false } }
    LaunchedEffect(s.phase, s.currentSeat, s.clues.size, s.caseIndex) {
        lucky = false
        hint = if (s.phase == PiePhase.CHOOSE && !seatIsBot && game.hints) game.hint() else null
    }

    // Sidekick autoplay and the Sneak step: each state change restarts this wait; a dialog freezes it.
    LaunchedEffect(s.phase, s.currentSeat, s.lastEvent?.serial, s.thiefHops, uiHold) {
        if (uiHold || game.matchOver.value) return@LaunchedEffect
        if (s.phase == PiePhase.SNEAK) {
            delay(if (reduced) 150L else 900L)
            game.sneakDone()
        } else if (seatIsBot && (s.phase == PiePhase.CHOOSE || s.phase == PiePhase.PEEK || s.phase == PiePhase.FLIP)) {
            delay(if (reduced) PieRules.SIDEKICK_PAUSE_REDUCED_MS else PieRules.SIDEKICK_PAUSE_MS)
            game.sidekickStep()
        }
    }

    LaunchedEffect(s.lastEvent?.serial) {
        when (s.lastEvent?.code) {
            PieEventCode.PEEKED -> haptics(HapticSignal.LIGHT_TICK)
            PieEventCode.SHOWN -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            PieEventCode.FLIPPED -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            PieEventCode.SNEAK_DASH -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.ESCALATING) }
            PieEventCode.CATCH_WRONG -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            PieEventCode.CASE_WON -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION) }
            PieEventCode.CASE_LOST -> haptics(HapticSignal.LIGHT_TICK)
            else -> {}
        }
    }

    val status = statusText(androidContext, s, ::seatName, seatIsBot, flipBounce, hint, bigClue != null, reduced || touchExploration, session.casesSolved)
    val inProgress = s.phase != PiePhase.BOOK_DONE
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = inProgress,
        onLeave = game::leaveSession,
        onAbort = { game.leaveSession() },
        leaveKeepsProgress = session.casesPlayed == 0,
        buttonFill = Color(scheme.background), buttonContent = Color(scheme.text)
    ) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, showTutorial) { uiHold = !windowFocused || showTutorial }
        Column(
            Modifier.fillMaxSize().background(Color(scheme.background)).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                val helpLabel = stringResource(R.string.pie_detective_action_help)
                TextButton(
                    onClick = { showTutorial = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = helpLabel }
                ) { Text("?", color = Color(scheme.accent), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
                ClStatusLine(status, Color(scheme.text), Modifier.weight(1f), emphasized = true)
            }
            if (s.phase != PiePhase.BOOK_DONE) {
                Text(
                    stringResource(R.string.pie_detective_case_title, s.caseIndex + 1, s.casesInBook),
                    Modifier.fillMaxWidth(), color = Color(scheme.muted), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge
                )
                LaneCanvas(s, scheme, reduced, androidContext)
            }
            when (s.phase) {
                PiePhase.PASS -> PassScreen(seatName(s.currentSeat), s.currentSeat, scheme) { game.passAcknowledged(s.currentSeat) }
                PiePhase.BOOK_DONE -> ScoreCard(game, scheme, session.casesSolved, session.casesInBook, session.stars, session.luckyPeeksUsed, session.lastCaseHops)
                else -> {
                    Lineup(
                        s, scheme, humanTurn = !seatIsBot, onFlip = { id ->
                            val r = game.flip(s.currentSeat, id)
                            if (r == PieResult.STILL_MATCHES) { flipBounce = true; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.LIGHT_TICK) }
                        },
                        onCatch = { id -> game.catchSuspect(s.currentSeat, id) }, ctx = androidContext
                    )
                    Notebook(s, scheme, androidContext)
                    val farSeat = rotate && humans >= 2 && !seatIsBot && s.currentSeat >= (seatCount + 1) / 2
                    Box(Modifier.fillMaxWidth().graphicsLayer { rotationZ = if (farSeat) 180f else 0f }) {
                        when {
                            bigClue != null -> BigAnswer(bigClue!!, scheme, androidContext)
                            s.phase == PiePhase.CHOOSE && !seatIsBot -> ChoosePanel(s, scheme, hint, lucky, { lucky = it }, androidContext,
                                onTrait = { t -> game.chooseTrait(s.currentSeat, t, lucky) },
                                onCatch = { game.startCatch(s.currentSeat) },
                                onHint = { hint = game.hint() })
                            s.phase == PiePhase.PEEK && !seatIsBot -> PeekPanel(s, scheme, reduced, touchExploration, androidContext,
                                onHeld = { game.peekHeld(s.currentSeat) },
                                onShow = { game.showEveryone(s.currentSeat) },
                                onBack = { game.cancelToChoose(s.currentSeat) })
                            s.phase == PiePhase.CATCH && !seatIsBot -> OutlinedButton(
                                onClick = { game.cancelToChoose(s.currentSeat) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)
                            ) { Text(stringResource(R.string.pie_detective_action_cancel)) }
                            s.phase == PiePhase.CASE_WON || s.phase == PiePhase.CASE_LOST -> CaseEnd(s, scheme, androidContext) { game.nextCase() }
                            else -> Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
        if (showTutorial) TutorialDialog(scheme) { showTutorial = false }
    }
}

@Composable
private fun state(game: PieDetectiveGame): PieState? {
    val s by game.state
    return s
}

// ---- names and text ----------------------------------------------------------------------------

private fun traitRes(t: PieTrait): Int = when (t) {
    PieTrait.HAT -> R.string.pie_detective_trait_hat
    PieTrait.GLASSES -> R.string.pie_detective_trait_glasses
    PieTrait.SCARF -> R.string.pie_detective_trait_scarf
    PieTrait.BAG -> R.string.pie_detective_trait_bag
    PieTrait.BOOTS -> R.string.pie_detective_trait_boots
    PieTrait.BOW -> R.string.pie_detective_trait_bow
}

private fun clueRes(c: PieClue): Int = when (c.trait) {
    PieTrait.HAT -> if (c.yes) R.string.pie_detective_clue_hat_yes else R.string.pie_detective_clue_hat_no
    PieTrait.GLASSES -> if (c.yes) R.string.pie_detective_clue_glasses_yes else R.string.pie_detective_clue_glasses_no
    PieTrait.SCARF -> if (c.yes) R.string.pie_detective_clue_scarf_yes else R.string.pie_detective_clue_scarf_no
    PieTrait.BAG -> if (c.yes) R.string.pie_detective_clue_bag_yes else R.string.pie_detective_clue_bag_no
    PieTrait.BOOTS -> if (c.yes) R.string.pie_detective_clue_boots_yes else R.string.pie_detective_clue_boots_no
    PieTrait.BOW -> if (c.yes) R.string.pie_detective_clue_bow_yes else R.string.pie_detective_clue_bow_no
}

private fun suspectRes(id: String): Int = when (id) {
    "mouse" -> R.string.pie_detective_suspect_mouse
    "frog" -> R.string.pie_detective_suspect_frog
    "otter" -> R.string.pie_detective_suspect_otter
    "pig" -> R.string.pie_detective_suspect_pig
    "sheep" -> R.string.pie_detective_suspect_sheep
    "cat" -> R.string.pie_detective_suspect_cat
    "badger" -> R.string.pie_detective_suspect_badger
    "rabbit" -> R.string.pie_detective_suspect_rabbit
    "penguin" -> R.string.pie_detective_suspect_penguin
    "goat" -> R.string.pie_detective_suspect_goat
    "duck" -> R.string.pie_detective_suspect_duck
    else -> R.string.pie_detective_suspect_squirrel
}

private fun placeRes(i: Int): Int = when (i) {
    0 -> R.string.pie_detective_place_stall
    1 -> R.string.pie_detective_place_fountain
    2 -> R.string.pie_detective_place_bridge
    else -> R.string.pie_detective_place_gate
}

private val tutorialRes = listOf(
    R.string.pie_detective_tutorial_1, R.string.pie_detective_tutorial_2, R.string.pie_detective_tutorial_3, R.string.pie_detective_tutorial_4,
    R.string.pie_detective_tutorial_5, R.string.pie_detective_tutorial_6, R.string.pie_detective_tutorial_7, R.string.pie_detective_tutorial_8
)

private fun suspectDescription(ctx: Context, id: String, visible: List<PieTrait>, flipped: Boolean): String {
    val name = ctx.getString(suspectRes(id))
    if (flipped) return ctx.getString(R.string.pie_detective_suspect_cleared, name)
    val s = PieCast.byId(id) ?: return name
    val worn = visible.filter { s.has(it) }.map { ctx.getString(traitRes(it)).lowercase() }
    return if (worn.isEmpty()) ctx.getString(R.string.pie_detective_suspect_desc_none, name)
    else ctx.getString(R.string.pie_detective_suspect_desc, name, worn.joinToString(", "))
}

private fun statusText(
    ctx: Context, s: PieState, seatName: (Int) -> String, seatIsBot: Boolean, bounce: Boolean, hint: PieHint?,
    showing: Boolean, tapLens: Boolean, solved: Int
): String {
    val name = seatName(s.currentSeat)
    return when (s.phase) {
        PiePhase.PASS -> ctx.getString(R.string.pie_detective_status_pass_to, name)
        PiePhase.CHOOSE -> when {
            seatIsBot -> ctx.getString(R.string.pie_detective_status_helper_turn, name)
            s.uncleared == 1 -> ctx.getString(R.string.pie_detective_status_one_left)
            hint?.trait != null -> ctx.getString(R.string.pie_detective_status_sidekick_says)
            else -> ctx.getString(R.string.pie_detective_status_choose, name)
        }
        PiePhase.PEEK -> when {
            seatIsBot -> ctx.getString(R.string.pie_detective_status_helper_turn, name)
            s.peeked -> ctx.getString(R.string.pie_detective_status_tell)
            tapLens -> ctx.getString(R.string.pie_detective_status_tap_lens)
            else -> ctx.getString(R.string.pie_detective_status_hold_lens)
        }
        PiePhase.FLIP -> when {
            bounce -> ctx.getString(R.string.pie_detective_status_flip_wrong)
            seatIsBot -> ctx.getString(R.string.pie_detective_status_helper_turn, name)
            else -> ctx.getString(R.string.pie_detective_status_flip)
        }
        PiePhase.SNEAK -> when {
            s.wrongCatch -> ctx.getString(R.string.pie_detective_status_catch_wrong)
            s.lastSneakWasDash == true -> ctx.getString(R.string.pie_detective_status_sneak_dash)
            else -> ctx.getString(R.string.pie_detective_status_sneak_pause)
        }
        PiePhase.CATCH -> ctx.getString(R.string.pie_detective_status_catch_pick)
        PiePhase.CASE_WON -> ctx.getString(R.string.pie_detective_status_case_won)
        PiePhase.CASE_LOST -> ctx.getString(R.string.pie_detective_status_case_lost)
        PiePhase.BOOK_DONE ->
            if (solved >= PieRules.bookWinThreshold(s.casesInBook)) ctx.getString(R.string.pie_detective_status_book_won)
            else ctx.getString(R.string.pie_detective_status_book_lost)
    }
}

// ---- lane (one Canvas, the only animated drawing) -----------------------------------------------

@Composable
private fun LaneCanvas(s: PieState, scheme: PieTokens.Scheme, reduced: Boolean, ctx: Context) {
    val hops by animateFloatAsState(s.thiefHops.toFloat(), tween(if (reduced) 0 else 550), label = "thiefHops")
    val desc = ctx.getString(R.string.pie_detective_lane_desc, ctx.getString(placeRes(s.thiefHops.coerceIn(0, 3))), (PieRules.LANE_HOPS - s.thiefHops).coerceAtLeast(0))
    val ink = Color(scheme.text)
    val soft = Color(scheme.card)
    Canvas(
        Modifier.fillMaxWidth().height(84.dp).padding(vertical = 4.dp).semantics { contentDescription = desc }
    ) {
        val n = 4
        val cell = size.width / n
        val place = minOf(cell * 0.7f, size.height * 0.8f)
        val y = size.height / 2f
        for (i in 0 until n - 1) {
            drawLine(ink.copy(alpha = 0.35f), Offset(cell * (i + 0.5f) + place / 2f, y), Offset(cell * (i + 1.5f) - place / 2f, y), 4f)
        }
        for (i in 0 until n) drawPiePlace(i, Offset(cell * (i + 0.5f), y), place, ink, soft)
        val tx = cell * (hops + 0.5f)
        drawPieThief(Offset(tx, y - place * 0.1f), place * 0.28f, pie = s.thiefHops >= PieRules.LANE_HOPS)
    }
}

// ---- lineup ------------------------------------------------------------------------------------

@Composable
private fun Lineup(s: PieState, scheme: PieTokens.Scheme, humanTurn: Boolean, onFlip: (String) -> Unit, onCatch: (String) -> Unit, ctx: Context) {
    val cols = if (s.lineup.size == 10) 5 else 4
    val flipping = s.phase == PiePhase.FLIP && humanTurn
    val catching = s.phase == PiePhase.CATCH && humanTurn
    val flipLabel = stringResource(R.string.pie_detective_suspect_tap_flip)
    val catchLabel = stringResource(R.string.pie_detective_suspect_tap_catch)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in s.lineup.chunked(cols)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in row) {
                    val active = (flipping && !v.flipped) || (catching && !v.flipped)
                    val desc = suspectDescription(ctx, v.id, s.offeredTraits, v.flipped)
                    SuspectCard(
                        id = v.id, flipped = v.flipped, visible = s.offeredTraits, scheme = scheme, desc = desc,
                        clickLabel = if (catching) catchLabel else flipLabel,
                        onClick = if (active) ({ if (catching) onCatch(v.id) else onFlip(v.id) }) else null
                    )
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RowScope.SuspectCard(
    id: String, flipped: Boolean, visible: List<PieTrait>, scheme: PieTokens.Scheme, desc: String,
    clickLabel: String, onClick: (() -> Unit)?
) {
    val shape = RoundedCornerShape(12.dp)
    val suspect = PieCast.byId(id)
    val name = stringResource(suspectRes(id))
    Column(
        Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 72.dp)
            .clip(shape)
            .background(Color(scheme.card), shape)
            .border(BorderStrokeOf(onClick != null, scheme), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(0.85f)) {
            if (flipped || suspect == null) {
                drawPieCardBack(Offset.Zero, Size(size.width, size.height), Color(scheme.accent), Color(scheme.onAccent))
            } else {
                drawPieAnimal(suspect, visible, Offset.Zero, size.height)
            }
        }
        if (!flipped) {
            Text(name, color = Color(scheme.text), textAlign = TextAlign.Center, maxLines = 3, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun BorderStrokeOf(active: Boolean, scheme: PieTokens.Scheme) =
    BorderStroke(if (active) 2.dp else 1.dp, Color(if (active) scheme.accent else scheme.muted).copy(alpha = if (active) 1f else 0.35f))

// ---- notebook and answers ----------------------------------------------------------------------

@Composable
private fun Notebook(s: PieState, scheme: PieTokens.Scheme, ctx: Context) {
    val desc = ctx.getString(R.string.pie_detective_notebook_desc, s.clues.size)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp).semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
    ) {
        if (s.clues.isEmpty()) {
            Text(stringResource(R.string.pie_detective_notebook_empty), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
        }
        for (c in s.clues) {
            val label = ctx.getString(if (c.yes) R.string.pie_detective_notebook_yes else R.string.pie_detective_notebook_no, ctx.getString(traitRes(c.trait)))
            Row(
                Modifier.background(Color(scheme.card), RoundedCornerShape(10.dp)).padding(6.dp).semantics(mergeDescendants = true) { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Canvas(Modifier.size(32.dp)) { drawPieTraitIcon(c.trait, Offset.Zero, size.minDimension, Color(scheme.text), Color(scheme.card)) }
                StampCanvas(c.yes, scheme, 32)
            }
        }
    }
}

@Composable
private fun StampCanvas(yes: Boolean, scheme: PieTokens.Scheme, sizeDp: Int) {
    val stamp = PieTokens.stampFor(yes)
    Canvas(Modifier.size(sizeDp.dp)) {
        drawPieStamp(yes, Offset.Zero, size.minDimension, Color(if (yes) scheme.yes else scheme.no), Color(if (stamp.icon == PieTokens.StampIcon.TICK) scheme.onYes else scheme.onNo))
    }
}

@Composable
private fun BigAnswer(c: PieClue, scheme: PieTokens.Scheme, ctx: Context) {
    val text = ctx.getString(clueRes(c))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color(scheme.card), RoundedCornerShape(16.dp)).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = text; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        StampCanvas(c.yes, scheme, 80)
        Text(text, color = Color(scheme.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
    }
}

// ---- choosing, peeking -------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoosePanel(
    s: PieState, scheme: PieTokens.Scheme, hint: PieHint?, lucky: Boolean, onLucky: (Boolean) -> Unit, ctx: Context,
    onTrait: (PieTrait) -> Unit, onCatch: () -> Unit, onHint: () -> Unit
) {
    val luckyOn = ctx.getString(R.string.pie_detective_lucky_on)
    val luckyOff = ctx.getString(R.string.pie_detective_lucky_off)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (t in s.offeredTraits) {
                val asked = s.clues.firstOrNull { it.trait == t }
                val name = ctx.getString(traitRes(t))
                val glow = hint?.trait == t
                val desc = when {
                    asked != null -> ctx.getString(R.string.pie_detective_trait_asked, name, ctx.getString(if (asked.yes) R.string.pie_detective_answer_yes else R.string.pie_detective_answer_no))
                    glow -> ctx.getString(R.string.pie_detective_trait_hint_glow, name)
                    else -> ctx.getString(R.string.pie_detective_trait_free, name)
                }
                TraitButton(t, name, asked, glow, scheme, desc) { if (asked == null) onTrait(t) }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val catchGlow = hint?.catchNow == true || s.uncleared == 1
            Button(
                onClick = onCatch, modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent)),
                border = if (catchGlow) BorderStroke(4.dp, Color(scheme.text)) else null
            ) { Text(stringResource(R.string.pie_detective_action_catch), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = onHint, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.pie_detective_action_hint), color = Color(scheme.text))
            }
            if (s.luckyAvailable) {
                OutlinedButton(
                    onClick = { onLucky(!lucky) },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { stateDescription = if (lucky) luckyOn else luckyOff },
                    border = BorderStroke(if (lucky) 4.dp else 1.dp, Color(scheme.accent))
                ) { Text((if (lucky) "★ " else "☆ ") + stringResource(R.string.pie_detective_action_lucky), color = Color(scheme.text)) }
            }
        }
    }
}

@Composable
private fun TraitButton(t: PieTrait, name: String, asked: PieClue?, glow: Boolean, scheme: PieTokens.Scheme, desc: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .size(width = 76.dp, height = 84.dp)
            .clip(shape)
            .background(Color(scheme.card).copy(alpha = if (asked != null) 0.5f else 1f), shape)
            .border(BorderStroke(if (glow) 4.dp else 2.dp, Color(if (glow) scheme.accent else scheme.muted).copy(alpha = if (glow || asked == null) 1f else 0.4f)), shape)
            .clickable(enabled = asked == null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawPieTraitIcon(t, Offset.Zero, size.minDimension, Color(scheme.text), Color(scheme.card)) }
        }
        if (asked != null) StampCanvas(asked.yes, scheme, 18)
        else Text(name, color = Color(scheme.text), maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PeekPanel(
    s: PieState, scheme: PieTokens.Scheme, reduced: Boolean, touchExploration: Boolean, ctx: Context,
    onHeld: () -> Unit, onShow: () -> Unit, onBack: () -> Unit
) {
    val trait = s.pendingTrait ?: return
    val answer = s.pendingAnswer ?: return
    val traitName = ctx.getString(traitRes(trait))
    val clue = PieClue(trait, answer)
    var open by remember(trait) { mutableStateOf(false) }
    val held = remember(trait) { booleanArrayOf(false) }
    val scope = rememberCoroutineScope()
    val toggleOnly = reduced || touchExploration
    val pulse by rememberInfiniteTransition(label = "lens").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "lensPulse"
    )
    val ringAlpha = if (reduced || open) 1f else pulse
    val lensDesc = if (open) ctx.getString(clueRes(clue)) else ctx.getString(R.string.pie_detective_lens_desc, traitName)
    val stateText = ctx.getString(if (open) R.string.pie_detective_lens_open else R.string.pie_detective_lens_closed)
    val peekLabel = stringResource(R.string.pie_detective_action_peek)
    val showHint = stringResource(R.string.pie_detective_status_show_hint)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(144.dp)
                .clip(CircleShape)
                .background(Color(scheme.card), CircleShape)
                .border(BorderStroke(6.dp, Color(scheme.accent).copy(alpha = ringAlpha)), CircleShape)
                .then(
                    if (toggleOnly) Modifier.clickable(role = Role.Button) { open = !open; if (open) onHeld() }
                    else Modifier.pointerInput(trait) {
                        detectTapGestures(
                            onPress = {
                                held[0] = false
                                val job = scope.launch {
                                    delay(PieRules.PEEK_HOLD_MS)
                                    held[0] = true
                                    open = true
                                    onHeld()
                                }
                                tryAwaitRelease()
                                job.cancel()
                                if (held[0]) open = false
                            },
                            onTap = {
                                if (held[0]) held[0] = false else { open = !open; if (open) onHeld() }
                            }
                        )
                    }
                )
                .semantics {
                    role = Role.Button
                    contentDescription = lensDesc
                    stateDescription = stateText
                    if (open) liveRegion = LiveRegionMode.Polite
                    onClick(label = peekLabel) { open = !open; if (open) onHeld(); true }
                },
            contentAlignment = Alignment.Center
        ) {
            if (open) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StampCanvas(answer, scheme, 64)
                    Text(
                        stringResource(if (answer) R.string.pie_detective_answer_yes else R.string.pie_detective_answer_no),
                        color = Color(scheme.text), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(56.dp)) { drawPieTraitIcon(trait, Offset.Zero, size.minDimension, Color(scheme.text), Color(scheme.card)) }
                    Text(stringResource(R.string.pie_detective_lens_hold), color = Color(scheme.muted), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.pie_detective_action_cancel), color = Color(scheme.text))
            }
            Button(
                onClick = onShow, enabled = s.peeked,
                modifier = Modifier.heightIn(min = 56.dp).semantics { if (s.peeked) stateDescription = showHint },
                colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
            ) { Text(stringResource(R.string.pie_detective_action_show), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

// ---- pass, case end, score card, tutorial ------------------------------------------------------

@Composable
private fun PassScreen(name: String, seat: Int, scheme: PieTokens.Scheme, onReady: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp).background(Color(scheme.card), RoundedCornerShape(20.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(72.dp).background(Color(scheme.accent), CircleShape), contentAlignment = Alignment.Center) {
            Text("${seat + 1}", color = Color(scheme.onAccent), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
        }
        Text(stringResource(R.string.pie_detective_status_pass_to, name), color = Color(scheme.text), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Button(
            onClick = onReady, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
        ) { Text(stringResource(R.string.pie_detective_action_ready), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun CaseEnd(s: PieState, scheme: PieTokens.Scheme, ctx: Context, onNext: () -> Unit) {
    val won = s.phase == PiePhase.CASE_WON
    val thiefId = s.revealedThiefId
    val thief = thiefId?.let { PieCast.byId(it) }
    val title = stringResource(if (won) R.string.pie_detective_status_case_won else R.string.pie_detective_status_case_lost)
    val sub = stringResource(if (won) R.string.pie_detective_status_case_won_sub else R.string.pie_detective_status_case_lost_sub)
    val reveal = thiefId?.let { ctx.getString(R.string.pie_detective_reveal_desc, ctx.getString(suspectRes(it))) } ?: ""
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color(scheme.card), RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color(scheme.text), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(sub, color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (thief != null) {
            Canvas(Modifier.size(width = 100.dp, height = 120.dp).semantics { contentDescription = reveal }) {
                drawPieAnimal(thief, PieRules.traitsOf(s.band), Offset.Zero, size.height)
                drawPie(Offset(size.width * 0.78f, size.height * 0.88f), size.width * 0.16f)
            }
            Text(reveal, color = Color(scheme.muted), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (won) StarRow(s.caseStars, scheme, ctx)
        Button(
            onClick = onNext, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
        ) { Text(stringResource(R.string.pie_detective_action_next_case), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun StarRow(stars: Int, scheme: PieTokens.Scheme, ctx: Context) {
    val desc = ctx.getString(R.string.pie_detective_stars_desc, stars)
    Canvas(Modifier.size(width = 132.dp, height = 40.dp).semantics { contentDescription = desc }) {
        for (i in 0 until 3) {
            val c = Offset(size.width * (i + 0.5f) / 3f, size.height / 2f)
            drawPieStar(c, size.height * 0.45f, if (i < stars) Color(0xFFF2B100) else Color(scheme.muted).copy(alpha = 0.25f), Color(scheme.text).copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ScoreCard(game: PieDetectiveGame, scheme: PieTokens.Scheme, solved: Int, cases: Int, stars: Int, lucky: Int, hops: Int) {
    val won = solved >= PieRules.bookWinThreshold(cases)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).background(Color(scheme.card), RoundedCornerShape(20.dp)).padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.pie_detective_score_title), color = Color(scheme.muted), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (won) R.string.pie_detective_status_book_won else R.string.pie_detective_status_book_lost),
            color = Color(scheme.text), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center
        )
        Text(
            stringResource(if (won) R.string.pie_detective_status_book_won_sub else R.string.pie_detective_status_book_lost_sub),
            color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
        )
        Text(stringResource(R.string.pie_detective_score_solved, solved, cases), color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.pie_detective_score_stars, stars), color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.pie_detective_score_lucky, lucky), color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.pie_detective_score_hops, hops), color = Color(scheme.text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.pie_detective_teaches), color = Color(scheme.muted), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { game.playAgain() }, modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(scheme.accent), contentColor = Color(scheme.onAccent))
            ) { Text(stringResource(R.string.pie_detective_action_play_again)) }
            OutlinedButton(onClick = { game.leaveSession() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.pie_detective_action_finish), color = Color(scheme.text))
            }
        }
    }
}

@Composable
private fun TutorialDialog(scheme: PieTokens.Scheme, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(scheme.card),
        title = { Text(stringResource(R.string.pie_detective_tutorial_title), color = Color(scheme.text)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tutorialRes.forEachIndexed { i, res ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(28.dp).background(Color(scheme.accent), CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color(scheme.onAccent), fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(res), color = Color(scheme.text), modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.pie_detective_action_close), color = Color(scheme.accent))
            }
        }
    )
}
