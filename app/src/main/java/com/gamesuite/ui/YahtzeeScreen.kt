package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.yahtzee.YahtzeeCategory
import com.gamesuite.games.yahtzee.YahtzeeGame
import com.gamesuite.games.yahtzee.YahtzeeScoring
import com.gamesuite.games.yahtzee.YahtzeeState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.ChangeSetupButton
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val YAHTZEE_ROUNDS = 13
private const val YAHTZEE_PREFS = "yahtzee_prefs"
private const val YAHTZEE_BEST_KEY = "best_score"

/**
 * Yahtzee: five large dice (tap to hold; a held die is lifted, double-outlined and labelled HELD, so
 * it never relies on colour), a Roll button that shows the rolls left, and the scorecard with the
 * potential score of every open box for the current dice (outlined and prefixed with +). Tapping a
 * box scores it. A suggested box (Hint) is starred; suggested dice get a dashed outline and KEEP.
 *
 * Players: solo chases a personal best (kept in the app's private preferences and reported as the
 * session result); 2 to 4 humans share the device with a "Name's turn" banner (dice are public, so
 * no curtain); CPU seats pause by [LocalGameSpeed] between visible steps (roll, holds, reroll, box).
 *
 * MOTION: thrown dice tumble and show random faces for a moment; off under [LocalReducedMotion].
 * ACCESSIBILITY: every die is a labelled checkbox, every scorable box one labelled button, the turn
 * banner is a polite live region. Layout: stacked on phones (dice above, scorecard scrolls below),
 * side by side in a wide landscape window; touch targets are at least 48dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YahtzeeScreen(
    sessionManager: GameSessionManager,
    game: YahtzeeGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val gameSpeed = LocalGameSpeed.current

    val s = game.state.value
    val hintHolds by game.hintHolds
    val hintCategory by game.hintCategory
    val sessionBest by game.sessionBest
    val sessionWins by game.sessionWins
    val gamesFinished by game.gamesFinished

    val prefs = remember(androidContext) { androidContext.getSharedPreferences(YAHTZEE_PREFS, Context.MODE_PRIVATE) }
    var bestEver by remember { mutableStateOf(runCatching { prefs.getInt(YAHTZEE_BEST_KEY, 0) }.getOrDefault(0)) }
    var brokeRecord by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // A CPU seat acts one visible step at a time (roll, choose holds, reroll, fill a box); every
    // step changes the state's version, which re-keys this effect for the next step.
    LaunchedEffect(s?.version, s?.gameOver) {
        val st = game.state.value ?: return@LaunchedEffect
        if (st.gameOver || !st.currentPlayer.isBot) return@LaunchedEffect
        val ms = gameSpeed.scale(if (st.rollsLeft == 3) 750L else 950L)
        if (ms > 60L) delay(ms)
        game.botStep()
    }

    // Once per finished game: personal best (solo) and the closing haptic.
    LaunchedEffect(s?.gameOver) {
        val st = game.state.value ?: return@LaunchedEffect
        if (!st.gameOver) {
            brokeRecord = false
            return@LaunchedEffect
        }
        if (st.players.size == 1) {
            val total = st.players[0].card.total
            if (total > bestEver) {
                brokeRecord = bestEver > 0
                bestEver = total
                runCatching { prefs.edit().putInt(YAHTZEE_BEST_KEY, total).apply() }
            }
            haptics(HapticSignal.CELEBRATION)
        } else {
            val top = st.players.maxOf { it.card.total }
            val leaders = st.players.filter { it.card.total == top }
            haptics(if (leaders.size == 1 && !leaders[0].isBot) HapticSignal.CELEBRATION else HapticSignal.NORMAL_ACTION)
        }
    }

    val helpTitle = stringResource(R.string.yahtzee_help_title)
    val helpBody = stringResource(R.string.yahtzee_help_body)
    val leaveTitle = stringResource(R.string.yahtzee_leave_title)

    if (s == null) {
        GameChrome(
            helpTitle = helpTitle,
            helpText = helpBody,
            matchInProgress = false,
            onLeave = { game.abortMatch() },
            onAbort = { game.abortMatch() },
            leaveTitle = leaveTitle
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.yahtzee_loading),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        return
    }

    val current = s.currentPlayer
    val humansAtTable = s.players.count { !it.isBot }
    val humanTurn = !s.gameOver && !current.isBot
    val legal = if (humanTurn) game.legalCategories() else emptyList()
    val canRoll = humanTurn && s.rollsLeft > 0 && !(s.hasRolled && s.held.all { it })
    val onRoll: () -> Unit = {
        if (game.roll()) haptics(HapticSignal.NORMAL_ACTION)
    }
    val onChoose: (YahtzeeCategory) -> Unit = { category ->
        if (game.chooseCategory(category)) {
            val scored = game.state.value?.lastScored
            haptics(
                if (scored != null && (scored.bonus > 0 || (category == YahtzeeCategory.YAHTZEE && scored.points > 0))) {
                    HapticSignal.CELEBRATION
                } else {
                    HapticSignal.STRONG_ACTION
                }
            )
        }
    }
    val finishedGames = gamesFinished

    GameChrome(
        helpTitle = helpTitle,
        helpText = helpBody,
        matchInProgress = !s.gameOver,
        onLeave = { game.leaveSession() },
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedGames == 0,
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = { game.hint() },
        leaveTitle = leaveTitle,
        leaveBody = stringResource(
            if (finishedGames > 0) R.string.yahtzee_leave_body_session else R.string.yahtzee_leave_body
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
        ) {
            val sideBySide = maxWidth >= 560.dp && maxWidth > maxHeight * 1.2f
            val screenHeight = maxHeight

            val topPart: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBanner(s = s, humansAtTable = humansAtTable)
                    if (s.gameOver) {
                        YahtzeeResultPanel(
                            s = s,
                            ranking = game.ranking(),
                            sessionBest = sessionBest[s.players[0].playerId] ?: 0,
                            bestEver = bestEver,
                            brokeRecord = brokeRecord,
                            sessionWins = sessionWins,
                            gamesFinished = gamesFinished,
                            onPlayAgain = { game.playAgain() },
                            onBackToMenu = { game.leaveSession() }
                        )
                    } else {
                        DiceArea(
                            s = s,
                            humanTurn = humanTurn,
                            canRoll = canRoll,
                            canUndo = game.canUndo,
                            hintHolds = hintHolds,
                            hintCategory = hintCategory,
                            reducedMotion = reducedMotion,
                            colorblind = colorblind,
                            onToggleHold = { i -> if (game.toggleHold(i)) haptics(HapticSignal.LIGHT_TICK) },
                            onRoll = onRoll,
                            onUndo = { game.undo() },
                            onHint = { game.hint() }
                        )
                    }
                }
            }
            val scorecard: @Composable (Modifier) -> Unit = { m ->
                Scorecard(
                    s = s,
                    legal = legal,
                    humanTurn = humanTurn,
                    hintCategory = hintCategory,
                    onChoose = onChoose,
                    modifier = m
                )
            }

            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) { topPart() }
                    scorecard(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = screenHeight * 0.58f)
                            .verticalScroll(rememberScrollState())
                    ) { topPart() }
                    Spacer(Modifier.height(10.dp))
                    scorecard(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

private fun yahtzeeCategoryRes(category: YahtzeeCategory): Int = when (category) {
    YahtzeeCategory.ACES -> R.string.yahtzee_cat_aces
    YahtzeeCategory.TWOS -> R.string.yahtzee_cat_twos
    YahtzeeCategory.THREES -> R.string.yahtzee_cat_threes
    YahtzeeCategory.FOURS -> R.string.yahtzee_cat_fours
    YahtzeeCategory.FIVES -> R.string.yahtzee_cat_fives
    YahtzeeCategory.SIXES -> R.string.yahtzee_cat_sixes
    YahtzeeCategory.THREE_OF_A_KIND -> R.string.yahtzee_cat_three_kind
    YahtzeeCategory.FOUR_OF_A_KIND -> R.string.yahtzee_cat_four_kind
    YahtzeeCategory.FULL_HOUSE -> R.string.yahtzee_cat_full_house
    YahtzeeCategory.SMALL_STRAIGHT -> R.string.yahtzee_cat_small_straight
    YahtzeeCategory.LARGE_STRAIGHT -> R.string.yahtzee_cat_large_straight
    YahtzeeCategory.YAHTZEE -> R.string.yahtzee_cat_yahtzee
    YahtzeeCategory.CHANCE -> R.string.yahtzee_cat_chance
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

@Composable
private fun StatusBanner(s: YahtzeeState, humansAtTable: Int) {
    val current = s.currentPlayer
    val turnText = when {
        s.gameOver -> stringResource(R.string.yahtzee_game_over)
        current.isBot -> stringResource(R.string.yahtzee_cpu_playing, current.displayName)
        humansAtTable == 1 -> stringResource(R.string.yahtzee_your_turn)
        else -> stringResource(R.string.yahtzee_turn_of, current.displayName)
    }
    val last = s.lastScored
    val lastText = if (last == null) {
        null
    } else {
        val who = s.players[last.playerIndex].displayName
        val box = stringResource(yahtzeeCategoryRes(last.category))
        if (last.bonus > 0) {
            stringResource(R.string.yahtzee_last_scored_bonus, who, last.points, box, last.bonus)
        } else {
            stringResource(R.string.yahtzee_last_scored, who, last.points, box)
        }
    }
    // The end padding keeps the corner menu button clear of the banner.
    Column(modifier = Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
        Text(
            stringResource(R.string.yahtzee_round_of, s.round.coerceAtMost(YAHTZEE_ROUNDS), YAHTZEE_ROUNDS),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The arrow is part of the cue so the active player is not marked by colour alone.
            Text(
                "▶",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.clearAndSetSemantics {}
            )
            Spacer(Modifier.width(8.dp))
            Text(
                turnText,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (lastText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                lastText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dice
// ---------------------------------------------------------------------------

@Composable
private fun DiceArea(
    s: YahtzeeState,
    humanTurn: Boolean,
    canRoll: Boolean,
    canUndo: Boolean,
    hintHolds: List<Boolean>?,
    hintCategory: YahtzeeCategory?,
    reducedMotion: Boolean,
    colorblind: Boolean,
    onToggleHold: (Int) -> Unit,
    onRoll: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 6.dp
            val dieSize = ((maxWidth - gap * 4) / 5).coerceIn(48.dp, 80.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top
            ) {
                for (i in 0 until 5) {
                    DieView(
                        index = i,
                        value = s.dice[i],
                        held = s.held[i],
                        suggested = hintHolds?.getOrNull(i) == true,
                        rolling = s.rolled[i],
                        rollSerial = s.rollSerial,
                        dieSize = dieSize,
                        enabled = humanTurn && s.rollsLeft in 1..2,
                        reducedMotion = reducedMotion,
                        colorblind = colorblind,
                        onToggle = { onToggleHold(i) }
                    )
                }
            }
        }

        val rollLabel = when {
            s.rollsLeft == 3 -> stringResource(R.string.yahtzee_roll_first)
            s.rollsLeft == 0 -> stringResource(R.string.yahtzee_roll_done)
            s.held.all { it } -> stringResource(R.string.yahtzee_roll_all_held)
            else -> stringResource(R.string.yahtzee_roll_again, s.rollsLeft)
        }
        Button(
            onClick = onRoll,
            enabled = canRoll,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(rollLabel, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.yahtzee_undo), textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = onHint,
                enabled = humanTurn && s.hasRolled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.yahtzee_hint), textAlign = TextAlign.Center) }
        }

        val instruction = when {
            hintHolds != null -> stringResource(R.string.yahtzee_hint_holds)
            hintCategory != null -> stringResource(R.string.yahtzee_hint_category, stringResource(yahtzeeCategoryRes(hintCategory)))
            humanTurn && s.rollsLeft in 1..2 -> stringResource(R.string.yahtzee_tap_to_hold)
            humanTurn && s.rollsLeft == 0 -> stringResource(R.string.yahtzee_choose_box)
            else -> null
        }
        if (instruction != null) {
            Text(
                instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
        }
    }
}

/** Pip positions (x, y as fractions of the face) for a die value 1..6. */
private fun yahtzeePips(value: Int): List<Pair<Float, Float>> {
    val lo = 0.2f
    val mid = 0.5f
    val hi = 0.8f
    return when (value) {
        1 -> listOf(mid to mid)
        2 -> listOf(lo to lo, hi to hi)
        3 -> listOf(lo to lo, mid to mid, hi to hi)
        4 -> listOf(lo to lo, hi to lo, lo to hi, hi to hi)
        5 -> listOf(lo to lo, hi to lo, mid to mid, lo to hi, hi to hi)
        6 -> listOf(lo to lo, hi to lo, lo to mid, hi to mid, lo to hi, hi to hi)
        else -> emptyList()
    }
}

/**
 * One die. A held die is lifted, double-outlined and labelled HELD; a suggested die has a dashed
 * outline and KEEP. A freshly thrown die tumbles and flickers through random faces (skipped under
 * reduced motion) before settling on its real value.
 */
@Composable
private fun DieView(
    index: Int,
    value: Int,
    held: Boolean,
    suggested: Boolean,
    rolling: Boolean,
    rollSerial: Int,
    dieSize: Dp,
    enabled: Boolean,
    reducedMotion: Boolean,
    colorblind: Boolean,
    onToggle: () -> Unit
) {
    var shown by remember { mutableStateOf(value) }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(rollSerial, value, rolling) {
        if (rolling && !reducedMotion && value in 1..6) {
            launch {
                rotation.snapTo(0f)
                rotation.animateTo(360f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
            }
            repeat(5) {
                shown = Random.nextInt(1, 7)
                delay(65)
            }
        }
        shown = value
    }

    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(dieSize * 0.18f)
    val face = if (held) colors.primaryContainer else colors.surface
    val ink = if (held) colors.onPrimaryContainer else colors.onSurface
    val dash = colors.tertiary
    val innerRing = colors.onPrimaryContainer
    val baseDescription = if (value in 1..6) {
        stringResource(R.string.yahtzee_cd_die, index + 1, value)
    } else {
        stringResource(R.string.yahtzee_cd_die_blank, index + 1)
    }
    val description = baseDescription + if (suggested && !held) stringResource(R.string.yahtzee_cd_die_suggested) else ""
    val stateText = stringResource(if (held) R.string.yahtzee_state_held else R.string.yahtzee_state_free)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(dieSize)
                .offset(y = if (held) (-6).dp else 0.dp)
                .graphicsLayer { rotationZ = rotation.value }
                .clip(shape)
                .background(face)
                .border(if (held) 4.dp else 1.5.dp, if (held) colors.primary else colors.outline, shape)
                .drawBehind {
                    if (held && colorblind) {
                        // A second inner ring so the held state also reads by shape, not hue.
                        val inset = 7.dp.toPx()
                        drawRoundRect(
                            color = innerRing,
                            topLeft = Offset(inset, inset),
                            size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                            cornerRadius = CornerRadius(dieSize.toPx() * 0.12f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    if (suggested && !held) {
                        drawRoundRect(
                            color = dash,
                            cornerRadius = CornerRadius(dieSize.toPx() * 0.18f),
                            style = Stroke(
                                width = 4.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                            )
                        )
                    }
                }
                .toggleable(
                    value = held,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() }
                )
                .semantics {
                    contentDescription = description
                    stateDescription = stateText
                },
            contentAlignment = Alignment.Center
        ) {
            if (value in 1..6) {
                val pips = yahtzeePips(shown.coerceIn(1, 6))
                Canvas(Modifier.fillMaxSize().padding(dieSize * 0.1f).clearAndSetSemantics {}) {
                    val r = this.size.minDimension * 0.095f
                    for ((x, y) in pips) {
                        drawCircle(color = ink, radius = r, center = Offset(x * this.size.width, y * this.size.height))
                    }
                }
            } else {
                Text("?", color = ink.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Spacer(Modifier.height(2.dp))
        // Always reserve the label line so the dice do not jump when a hold appears.
        Text(
            when {
                held -> stringResource(R.string.yahtzee_die_held)
                suggested -> stringResource(R.string.yahtzee_die_keep)
                else -> " "
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

// ---------------------------------------------------------------------------
// Scorecard
// ---------------------------------------------------------------------------

@Composable
private fun Scorecard(
    s: YahtzeeState,
    legal: List<YahtzeeCategory>,
    humanTurn: Boolean,
    hintCategory: YahtzeeCategory?,
    onChoose: (YahtzeeCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val n = s.players.size
    val colWidth = when (n) {
        1 -> 72.dp
        2 -> 64.dp
        3 -> 56.dp
        else -> 50.dp
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header: a marker line (arrow on the player whose turn it is) above each name.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                s.players.forEachIndexed { i, p ->
                    val isTurn = i == s.currentPlayerIndex && !s.gameOver
                    val description = stringResource(
                        if (isTurn) R.string.yahtzee_cd_header_turn else R.string.yahtzee_cd_header,
                        p.displayName,
                        p.card.total
                    )
                    Column(
                        modifier = Modifier.width(colWidth).clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (isTurn) "▶" else " ", style = MaterialTheme.typography.labelSmall)
                        Text(
                            p.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            SectionTitle(stringResource(R.string.yahtzee_section_upper))
            for (category in YahtzeeCategory.UPPER) {
                ScoreRow(s, category, legal, humanTurn, hintCategory, colWidth, onChoose)
            }
            SummaryRow(
                label = stringResource(R.string.yahtzee_row_upper_bonus),
                values = s.players.map { p ->
                    if (p.card.upperBonus > 0) "+${p.card.upperBonus}" else stringResource(R.string.yahtzee_progress, p.card.upperSum, 63)
                },
                numbers = s.players.map { it.card.upperBonus },
                names = s.players.map { it.displayName },
                colWidth = colWidth,
                bold = false
            )
            SectionTitle(stringResource(R.string.yahtzee_section_lower))
            for (category in YahtzeeCategory.LOWER) {
                ScoreRow(s, category, legal, humanTurn, hintCategory, colWidth, onChoose)
            }
            SummaryRow(
                label = stringResource(R.string.yahtzee_row_yahtzee_bonus),
                values = s.players.map { it.card.bonusPoints.toString() },
                numbers = s.players.map { it.card.bonusPoints },
                names = s.players.map { it.displayName },
                colWidth = colWidth,
                bold = false
            )
            SummaryRow(
                label = stringResource(R.string.yahtzee_row_total),
                values = s.players.map { it.card.total.toString() },
                numbers = s.players.map { it.card.total },
                names = s.players.map { it.displayName },
                colWidth = colWidth,
                bold = true
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)))
}

@Composable
private fun ScoreRow(
    s: YahtzeeState,
    category: YahtzeeCategory,
    legal: List<YahtzeeCategory>,
    humanTurn: Boolean,
    hintCategory: YahtzeeCategory?,
    colWidth: Dp,
    onChoose: (YahtzeeCategory) -> Unit
) {
    val label = stringResource(yahtzeeCategoryRes(category))
    val turn = s.currentPlayerIndex
    val currentOpen = s.players[turn].card.isOpen(category)
    val interactive = humanTurn && currentOpen && category in legal
    val blocked = humanTurn && s.hasRolled && currentOpen && category !in legal
    val hinted = hintCategory == category && interactive
    val potential = if (interactive) YahtzeeScoring.scoreFor(category, s.dice, s.players[turn].card) else 0

    val description = when {
        interactive && hinted -> stringResource(R.string.yahtzee_cd_row_choose_best, label, potential)
        interactive -> stringResource(R.string.yahtzee_cd_row_choose, label, potential)
        blocked -> stringResource(R.string.yahtzee_cd_row_blocked, label)
        else -> stringResource(
            R.string.yahtzee_cd_row,
            label,
            s.players.map { p ->
                val v = p.card[category]
                if (v != null) {
                    stringResource(R.string.yahtzee_cd_cell_scored, p.displayName, v)
                } else {
                    stringResource(R.string.yahtzee_cd_cell_open, p.displayName)
                }
            }.joinToString(", ")
        )
    }
    val clickLabel = stringResource(R.string.yahtzee_click_label, label)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (hinted) {
                        Modifier.background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (interactive) {
                        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { onChoose(category) }
                    } else {
                        Modifier
                    }
                )
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (hinted) "★ " else "") + label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (hinted) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)
            )
            s.players.forEachIndexed { i, p ->
                val scored = p.card[category]
                Box(
                    modifier = Modifier.width(colWidth).heightIn(min = 40.dp).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        scored != null -> Text(
                            scored.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        i == turn && interactive -> {
                            // The outline and the leading + mark a box that can be taken right now.
                            val shape = RoundedCornerShape(8.dp)
                            Box(
                                modifier = Modifier
                                    .clip(shape)
                                    .border(if (hinted) 3.dp else 2.dp, MaterialTheme.colorScheme.primary, shape)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("+$potential", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        i == turn && blocked -> Text("–", style = MaterialTheme.typography.bodyMedium)
                        else -> Text(
                            "·",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
        RowDivider()
    }
}

@Composable
private fun SummaryRow(
    label: String,
    values: List<String>,
    numbers: List<Int>,
    names: List<String>,
    colWidth: Dp,
    bold: Boolean
) {
    val description = stringResource(
        R.string.yahtzee_cd_total_row,
        label,
        names.indices.map { stringResource(R.string.yahtzee_cd_cell_scored, names[it], numbers[it]) }.joinToString(", ")
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)
            )
            values.forEach { v ->
                Text(
                    v,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(colWidth).clearAndSetSemantics {}
                )
            }
        }
        RowDivider()
    }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YahtzeeResultPanel(
    s: YahtzeeState,
    ranking: List<Int>,
    sessionBest: Int,
    bestEver: Int,
    brokeRecord: Boolean,
    sessionWins: Map<String, Int>,
    gamesFinished: Int,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit
) {
    val solo = s.players.size == 1
    val topTotal = s.players.maxOf { it.card.total }
    val leaders = s.players.filter { it.card.total == topTotal }
    val title = when {
        solo -> stringResource(R.string.yahtzee_result_solo, topTotal)
        leaders.size == 1 -> stringResource(R.string.yahtzee_result_winner, leaders[0].displayName, topTotal)
        else -> stringResource(R.string.yahtzee_result_tie, topTotal)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (solo) {
                if (brokeRecord) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "★ " + stringResource(R.string.yahtzee_result_new_record),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.yahtzee_result_best_ever, maxOf(bestEver, topTotal)),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (gamesFinished > 1) {
                    Text(
                        stringResource(R.string.yahtzee_result_best_session, sessionBest),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                ranking.forEachIndexed { place, seat ->
                    val p = s.players[seat]
                    Text(
                        stringResource(R.string.yahtzee_result_rank, place + 1, p.displayName, p.card.total),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (p.card.total == topTotal) FontWeight.Bold else FontWeight.Normal
                    )
                }
                if (gamesFinished > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.yahtzee_result_session_wins,
                            s.players.joinToString(" · ") { "${it.displayName} ${sessionWins[it.playerId] ?: 0}" }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayAgain,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.yahtzee_play_again), textAlign = TextAlign.Center) }
                ChangeSetupButton(onLeave = onBackToMenu)
                OutlinedButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.yahtzee_back_to_menu), textAlign = TextAlign.Center) }
            }
        }
    }
}
