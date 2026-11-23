package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameCoach
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.blackjack.CasinoCard
import com.gamesuite.games.blackjack.CasinoCardBack
import com.gamesuite.games.blackjack.DealIn
import com.gamesuite.games.blackjack.cardHeightFor
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.war.WarConfig
import com.gamesuite.games.war.WarGame
import com.gamesuite.games.war.WarPhase
import com.gamesuite.games.war.WarReject
import com.gamesuite.games.war.WarRound
import com.gamesuite.games.war.WarSeat
import com.gamesuite.games.war.WarState
import com.gamesuite.games.war.WarVariant
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay

private val WAR_TOP_LIGHT = Color(0xFF7A2E3B)
private val WAR_BOTTOM_LIGHT = Color(0xFF4A1824)
private val WAR_TOP_DARK = Color(0xFF4F1D27)
private val WAR_BOTTOM_DARK = Color(0xFF2A0E15)
private val WAR_TEXT = Color(0xFFFBF1E4)
private val WAR_GOLD = Color(0xFFF2CD6B)

/**
 * War on a wine-red felt table: piles of card backs, cards that slide out and turn over, a "War!" call on
 * every tie, and (in the Choose 3 variant) three candidate cards you pick from in secret, with a
 * "pass the device" cover between people on one device. Reduced Motion turns the reveal into a cut;
 * every pile, card and result is labelled for TalkBack and announced politely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WarScreen(
    sessionManager: GameSessionManager,
    game: WarGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val cardSounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.DOMINOES, enabled = musicEnabled)
    val state by game.state
    val hostLeft by game.hostLeft
    val hintIndex by game.hintIndex
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()
    val nameOf = rememberCardNamer()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.config = WarConfig.fromExtras(sessionManager.launchOptions.extras)
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0

    // ---- the reveal animation: shows the last round's flips one by one, then updates the pile counts ----
    var animRound by remember { mutableStateOf<WarRound?>(null) }
    var animSerial by remember { mutableIntStateOf(-1) }
    var stepShown by remember { mutableIntStateOf(0) }
    var faceUp by remember { mutableStateOf(true) }
    var winnerGlow by remember { mutableStateOf(false) }
    var warFlash by remember { mutableStateOf(false) }
    var animating by remember { mutableStateOf(false) }
    var shownCounts by remember { mutableStateOf<List<Int>?>(null) }
    val pileCenters = remember { mutableStateMapOf<Int, Offset>() }
    val glow = remember { Animatable(1f) }
    var selected by remember { mutableIntStateOf(-1) }
    var auto by remember { mutableStateOf(false) }
    var whyKey by remember { mutableStateOf<WarReject?>(null) }
    var readyFor by remember { mutableStateOf(Pair(-1, -1)) } // (round, seat) whose device cover was dismissed
    var showHint by remember { mutableStateOf(false) }

    LaunchedEffect(state?.serial) {
        val s = state ?: return@LaunchedEffect
        selected = -1
        showHint = false
        val first = animSerial < 0
        val isNew = !first && s.serial > animSerial
        animSerial = s.serial
        val round = s.lastRound
        if (!isNew || round == null || s.lastRound === animRound) {
            if (first || round == null) {
                animRound = round
                stepShown = (round?.steps?.size ?: 1) - 1
                faceUp = true
                winnerGlow = round != null
            }
            shownCounts = s.seats.map { it.count }
            return@LaunchedEffect
        }
        // A pick that only waited for others (no new round) leaves the table as it was.
        if (round === animRound) { shownCounts = s.seats.map { it.count }; return@LaunchedEffect }
        animRound = round
        if (reduced) {
            stepShown = round.steps.size - 1; faceUp = true; winnerGlow = true
            shownCounts = s.seats.map { it.count }
            return@LaunchedEffect
        }
        animating = true
        try {
            winnerGlow = false
            for (i in round.steps.indices) {
                stepShown = i
                faceUp = false
                if (i > 0) {
                    warFlash = true
                    playSfx(SfxKind.WHOOSH); haptics(HapticSignal.ESCALATING)
                    delay(gameSpeed.scale(750L))
                    warFlash = false
                }
                cardSounds.playDraw()
                delay(gameSpeed.scale(450L))
                faceUp = true
                cardSounds.playPlace()
                haptics(HapticSignal.LIGHT_TICK)
                delay(gameSpeed.scale(if (i == round.steps.lastIndex) 650L else 900L))
            }
            winnerGlow = true
            glow.snapTo(1.3f)
            glow.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
            shownCounts = s.seats.map { it.count }
            val mine = if (isNetworked) localSeat else s.seats.indexOfFirst { !it.isBot }
            if (round.winnerSeat >= 0 && round.winnerSeat == mine) { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
            else if (round.winnerSeat >= 0 && mine >= 0 && (isNetworked || s.seats.count { !it.isBot } == 1)) {
                playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.LIGHT_TICK)
            }
            if (round.wars > 0 && round.winnerSeat == mine) haptics(HapticSignal.STRONG_ACTION)
        } finally {
            animating = false
            faceUp = true
            shownCounts = s.seats.map { it.count }
        }
    }

    // computer picks, on a gentle clock
    LaunchedEffect(state?.serial, hostLeft, animating) {
        val s = state ?: return@LaunchedEffect
        if (animating || !game.botToMove(s)) return@LaunchedEffect
        delay(gameSpeed.scale(650L).coerceAtLeast(420L))
        game.playBotTurnAsync()
    }
    // optional auto-flip for the long classic game
    LaunchedEffect(auto, state?.serial, animating, hostLeft) {
        val s = state ?: return@LaunchedEffect
        if (!auto || animating || !game.canFlip()) return@LaunchedEffect
        delay(gameSpeed.scale(550L).coerceAtLeast(300L))
        game.flip()
    }
    LaunchedEffect(whyKey) { if (whyKey != null) { delay(3500); whyKey = null } }

    // game over: celebration for a human winner
    LaunchedEffect(state?.phase, state?.winnerSeat) {
        val s = state ?: return@LaunchedEffect
        if (s.phase != WarPhase.OVER) return@LaunchedEffect
        while (animating) delay(60)
        auto = false
        val humanWon = s.winnerSeat >= 0 && !s.seats[s.winnerSeat].isBot && (!isNetworked || s.winnerSeat == localSeat)
        if (humanWon) {
            playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(
                Offset(0.5f, 0.4f), 80, listOf(WAR_GOLD, Color.White, Color(0xFFE96A5A), Color(0xFF6BC4E8)),
                speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f
            )
        } else if (s.winnerSeat >= 0 && isNetworked) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
        } else haptics(HapticSignal.LIGHT_TICK)
    }

    val s = state
    val helpTitle = stringResource(R.string.wr_help_title)
    val helpText = stringResource(R.string.wr_help_text)
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = WAR_BOTTOM_LIGHT, buttonContent = WAR_TEXT
            ) {
                Box(Modifier.fillMaxSize().background(WAR_BOTTOM_LIGHT), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bjw_waiting_host), color = WAR_TEXT, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val ctx = LocalContext.current
    val choose3 = s.config.variant == WarVariant.CHOOSE3
    val multiHuman = !isNetworked && s.seats.count { !it.isBot } > 1
    val picker = game.pickerSeat()
    val myChoices = game.myChoices()
    val counts = shownCounts ?: s.seats.map { it.count }
    fun seatName(i: Int) = s.seats.getOrNull(i)?.displayName ?: ""

    val whyText = whyKey?.let { k ->
        ctx.getString(
            when (k) {
                WarReject.OVER -> R.string.wr_why_over
                WarReject.WRONG_PHASE -> R.string.wr_why_phase
                WarReject.NOT_YOUR_TURN -> if (isNetworked) R.string.wr_why_wait else R.string.wr_why_phase
                WarReject.BAD_CHOICE, WarReject.ALREADY_PICKED -> R.string.wr_why_choice
                WarReject.NONE -> R.string.wr_why_phase
            }
        )
    }

    val statusText = run {
        val r = s.lastRound
        val last = when {
            s.phase == WarPhase.OVER && s.draw -> ctx.getString(R.string.wr_over_draw)
            s.phase == WarPhase.OVER && s.winnerSeat >= 0 ->
                ctx.sayFor(seatName(s.winnerSeat), R.string.wr_over_win, R.string.wr_over_win_you) +
                    if (s.round >= s.config.maxRounds) " " + ctx.getString(R.string.wr_over_limit) else ""
            r == null -> ctx.getString(if (choose3) R.string.wr_ann_start_pick else R.string.wr_ann_start)
            r.winnerSeat < 0 -> ctx.getString(R.string.wr_ann_split)
            else -> {
                val who = seatName(r.winnerSeat)
                val card = r.steps.last().plays.firstOrNull { it.seat == r.winnerSeat }?.up?.let(nameOf) ?: ""
                val warText = if (r.wars > 0) ctx.getString(R.string.wr_ann_war_prefix, r.steps[0].plays.size) + " " else ""
                warText + ctx.sayFor(who, R.string.wr_ann_round_win, R.string.wr_ann_round_win_you, card, r.potSize)
            }
        }
        val turn = when {
            s.phase == WarPhase.OVER -> ""
            hostLeft -> ctx.getString(R.string.bjw_host_left)
            animating -> ""
            !choose3 -> ctx.getString(R.string.wr_turn_flip)
            picker != null && multiHuman -> ctx.getString(R.string.wr_turn_pick_named, seatName(picker))
            picker != null -> ctx.getString(R.string.wr_turn_pick_you)
            else -> ctx.getString(R.string.wr_turn_waiting)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    val canCoach = GameCoach.forGame("war") != null
    val (_, openCoach) = rememberClCoach("war", ready = canCoach && s.round == 0)
    val finishedGames = s.seats.sumOf { it.wins }
    val leaveTitle = stringResource(R.string.bjw_leave_title)
    val leaveBody = stringResource(if (isNetworked) R.string.bjw_leave_body_net else R.string.wr_leave_body)

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = s.phase != WarPhase.OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finishedGames > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = !isNetworked && finishedGames == 0,
        onHint = if (picker != null) ({ game.hint(); showHint = true }) else null,
        buttonFill = WAR_BOTTOM_LIGHT, buttonContent = WAR_TEXT,
        leaveTitle = leaveTitle, leaveBody = leaveBody
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(if (dark) listOf(WAR_TOP_DARK, WAR_BOTTOM_DARK) else listOf(WAR_TOP_LIGHT, WAR_BOTTOM_LIGHT))
            )
        ) {
            val seatCount = s.seats.size
            val cardW = minOf(((if (maxWidth > maxHeight * 1.15f) maxWidth - 320.dp else maxWidth) - 24.dp) / (seatCount * 1.3f + 0.6f), maxHeight * 0.17f, if (seatCount <= 2) 104.dp else 76.dp).coerceAtLeast(40.dp)
            // On a wide, short screen the controls move to a side panel; stacked under the table they fall off the bottom.
            val landscape = maxWidth > maxHeight * 1.15f
            val sidePanelW = 320.dp
            val controls: @Composable () -> Unit = {
                val btn = Modifier.heightIn(min = 52.dp).widthIn(min = 96.dp)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        s.phase == WarPhase.OVER -> {
                            val winner = s.seats.getOrNull(s.winnerSeat)
                            ClFinishedPanel(
                                title = if (s.draw || winner == null) stringResource(R.string.wr_over_draw_title)
                                else youAware(winner.displayName, R.string.wr_over_win, R.string.wr_over_win_you),
                                subtitle = if (isNetworked && winner != null) stringResource(if (s.winnerSeat == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost) else null,
                                lines = s.seats.map { stringResource(R.string.wr_final_line, LocalContext.current.seatLabel(it.displayName), it.count) },
                                canPlayAgain = !hostLeft && !animating,
                                onPlayAgain = { game.playAgain() },
                                onLeave = { game.leaveSession() }
                            )
                        }
                        !choose3 -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        val r = game.explainFlip()
                                        if (r == WarReject.NONE) { whyKey = null; game.flip() } else {
                                            whyKey = r; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                                        }
                                    },
                                    enabled = !animating && !auto, modifier = btn.widthIn(min = 160.dp)
                                ) { Text(stringResource(R.string.wr_flip), fontWeight = FontWeight.Bold) }
                                OutlinedButton(
                                    onClick = { auto = !auto },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAR_TEXT),
                                    border = BorderStroke(if (auto) 3.dp else 1.dp, if (auto) WAR_GOLD else WAR_TEXT.copy(alpha = 0.5f)),
                                    modifier = btn.semantics {
                                        role = Role.Switch
                                        stateDescription = ctx.getString(if (auto) R.string.wr_auto_on else R.string.wr_auto_off)
                                    }
                                ) { Text(stringResource(R.string.wr_auto)) }
                            }
                        }
                        picker != null && multiHuman && readyFor != Pair(s.round, picker) -> {
                            // Hot-seat privacy: nobody sees the candidates until the right person has the device.
                            Text(
                                stringResource(R.string.wr_pass_to, seatName(picker)), color = WAR_TEXT, textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { readyFor = Pair(s.round, picker) }, modifier = btn.widthIn(min = 180.dp)) {
                                Text(stringResource(R.string.wr_pass_ready, seatName(picker)))
                            }
                        }
                        picker != null -> {
                            Text(stringResource(R.string.wr_pick_title), color = WAR_TEXT, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                myChoices.forEachIndexed { idx, card ->
                                    val isSel = selected == idx
                                    val desc = stringResource(R.string.wr_choice_desc, idx + 1, nameOf(card)) +
                                        if (hintIndex == idx && showHint) " " + stringResource(R.string.wr_suggested) else ""
                                    Box(
                                        Modifier
                                            .offset(y = if (isSel) (-10).dp else 0.dp)
                                            .border(if (hintIndex == idx && showHint) 3.dp else 0.dp, WAR_GOLD, RoundedCornerShape(8.dp))
                                            .clickable(role = Role.Button) {
                                                if (isSel) { game.pick(idx) } else { selected = idx; cardSounds.playTap(); haptics(HapticSignal.LIGHT_TICK) }
                                            }
                                            .semantics { this.selected = isSel; contentDescription = desc }
                                            .padding(2.dp)
                                    ) {
                                        CasinoCard(card, faceUp = true, width = (cardW * 1.1f).coerceAtLeast(52.dp), description = desc, colorblind = colorblind)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        if (selected in myChoices.indices) { game.pick(selected) } else {
                                            whyKey = WarReject.BAD_CHOICE; playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
                                        }
                                    },
                                    enabled = !animating, modifier = btn.widthIn(min = 160.dp)
                                ) { Text(stringResource(R.string.wr_play_card), fontWeight = FontWeight.Bold) }
                                OutlinedButton(
                                    onClick = { game.hint(); showHint = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAR_TEXT), modifier = btn
                                ) { Text(stringResource(R.string.bj_hint)) }
                            }
                        }
                        else -> {
                            val waiting = s.seats.indices.filter { s.seats[it].count > 0 && !s.seats[it].picked }
                            Text(
                                stringResource(R.string.wr_waiting_picks, waiting.joinToString(", ") { seatName(it) }),
                                color = WAR_TEXT.copy(alpha = 0.9f), textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    if (canCoach) ClCoachButton("war", openCoach)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wr_round_line, s.round, s.config.maxRounds), color = WAR_TEXT, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }
                        )
                        if (finishedGames > 0) {
                            Text(
                                stringResource(R.string.wr_session, s.seats.joinToString(" · ") { "${it.displayName} ${it.wins}" }),
                                color = WAR_TEXT.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                // ---- piles ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    s.seats.forEachIndexed { i, seat ->
                        PilePanel(
                            seat = seat, count = counts.getOrElse(i) { seat.count }, cardW = cardW * 0.8f,
                            isWinner = winnerGlow && s.lastRound?.winnerSeat == i && !animating,
                            isLocal = isNetworked && i == localSeat, glow = glow.value, dark = dark,
                            onCenter = { pileCenters[i] = it }, modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Text(
                    statusText, color = WAR_TEXT, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
                )
                val hintLine = if (showHint && hintIndex != null && picker != null) stringResource(R.string.wr_hint_line, hintIndex!! + 1) else null
                val line = whyText ?: hintLine
                if (line != null) {
                    Text(
                        line, color = Color(0xFF1B1B1B), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(WAR_GOLD, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { liveRegion = if (whyText != null) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                    )
                }

                // ---- the table ----
                Box(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val round = animRound
                    if (round != null) {
                        val step = round.steps.getOrNull(stepShown) ?: round.steps.last()
                        Row(
                            Modifier.verticalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            step.plays.forEach { p ->
                                key(stepShown, p.seat) {
                                    val winner = winnerGlow && !animating && round.winnerSeat == p.seat && stepShown == round.steps.lastIndex
                                    PlayColumn(
                                        rawName = seatName(p.seat), down = p.down, card = p.up, faceUp = faceUp, cardW = cardW,
                                        from = pileCenters[p.seat], highlight = winner, glow = if (winner) glow.value else 1f,
                                        colorblind = colorblind, nameOf = nameOf, animate = animating
                                    )
                                }
                            }
                        }
                        if (warFlash) {
                            Text(
                                stringResource(R.string.wr_war), color = WAR_GOLD, fontWeight = FontWeight.Black,
                                style = MaterialTheme.typography.displaySmall,
                                modifier = Modifier.background(Color(0xCC1A0A0E), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Assertive }
                            )
                        }
                    } else {
                        Text(
                            stringResource(if (choose3) R.string.wr_empty_pick else R.string.wr_empty_flip),
                            color = WAR_TEXT.copy(alpha = 0.8f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                }

                // ---- controls ----
                if (!landscape) controls()
            }
            if (landscape) {
                Box(Modifier.width(sidePanelW).fillMaxHeight().padding(end = 8.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.Center) { controls() }
            }
            }
        }
    }
}

@Composable
private fun PilePanel(
    seat: WarSeat, count: Int, cardW: Dp, isWinner: Boolean, isLocal: Boolean, glow: Float, dark: Boolean,
    onCenter: (Offset) -> Unit, modifier: Modifier = Modifier
) {
    val youTag = if (isLocal) " " + stringResource(R.string.bjw_you_suffix) else ""
    val cpuWord = stringResource(R.string.bjw_cpu_tag)
    val cpuTag = if (seat.isBot && !seat.displayName.contains(cpuWord, ignoreCase = true)) " ($cpuWord)" else ""
    val desc = stringResource(R.string.wr_pile_desc, LocalContext.current.seatLabel(seat.displayName) + youTag + cpuTag, count) +
        if (seat.picked) " " + stringResource(R.string.wr_chosen) else ""
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isWinner) Color(0x33F2CD6B) else Color(0x22000000))
            .border(if (isWinner) 2.dp else 1.dp, if (isWinner) WAR_GOLD else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            LocalContext.current.seatLabel(seat.displayName) + youTag, color = WAR_TEXT, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge
        )
        Box(
            Modifier
                .size(cardW + 10.dp, cardHeightFor(cardW) + 8.dp)
                .scale(if (isWinner) glow else 1f)
                .onGloballyPositioned { onCenter(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
        ) {
            if (count <= 0) {
                Box(
                    Modifier.size(cardW, cardHeightFor(cardW)).border(2.dp, WAR_TEXT.copy(alpha = 0.35f), RoundedCornerShape(cardW * 0.1f))
                )
            } else {
                val layers = (1 + count / 8).coerceAtMost(5)
                for (i in layers - 1 downTo 0) {
                    CasinoCardBack(cardW, Modifier.offset(x = (i * 2).dp, y = (i * 2).dp))
                }
            }
        }
        Text(
            stringResource(R.string.wr_count, count), color = WAR_GOLD, fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelLarge
        )
        if (seat.picked) Text(stringResource(R.string.wr_chosen), color = WAR_TEXT.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
    }
}

/** One seat's cards in one flip: the face-down war cards fanned behind the deciding card. */
@Composable
private fun PlayColumn(
    rawName: String, down: Int, card: Card, faceUp: Boolean, cardW: Dp, from: Offset?, highlight: Boolean, glow: Float,
    colorblind: Boolean, nameOf: (Card) -> String, animate: Boolean
) {
    val backs = down.coerceAtMost(3)
    val name = LocalContext.current.seatLabel(rawName)
    val gap = cardW * 0.2f
    val width = cardW + gap * backs
    val hidden = stringResource(R.string.bjw_card_back_desc)
    val desc = stringResource(R.string.wr_play_desc, name, if (faceUp) nameOf(card) else hidden) +
        if (down > 0) " " + stringResource(R.string.wr_play_down, down) else ""
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(name, color = WAR_TEXT, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        DealIn(from = from, delayMs = 0L, animate = animate) {
            Box(
                Modifier
                    .size(width + 8.dp, cardHeightFor(cardW) + 8.dp)
                    .scale(if (highlight) glow else 1f)
                    .then(if (highlight) Modifier.border(3.dp, WAR_GOLD, RoundedCornerShape(cardW * 0.12f)).padding(2.dp) else Modifier.padding(2.dp))
            ) {
                for (i in 0 until backs) CasinoCardBack(cardW, Modifier.offset(x = gap * i))
                CasinoCard(card, faceUp = faceUp, width = cardW, description = if (faceUp) nameOf(card) else hidden, colorblind = colorblind,
                    modifier = Modifier.offset(x = gap * backs))
            }
        }
        if (down > 0) {
            Text(stringResource(R.string.wr_play_down, down), color = WAR_TEXT.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
