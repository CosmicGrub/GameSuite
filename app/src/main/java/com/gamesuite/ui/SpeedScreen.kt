package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.speed.CardArtColors
import com.gamesuite.games.speed.SpeedCardBack
import com.gamesuite.games.speed.SpeedCardFace
import com.gamesuite.games.speed.SpeedEvent
import com.gamesuite.games.speed.SpeedGame
import com.gamesuite.games.speed.SpeedPhase
import com.gamesuite.games.speed.SpeedPlay
import com.gamesuite.games.speed.SpeedReject
import com.gamesuite.games.speed.SpeedState
import com.gamesuite.games.speed.cardHeightFor
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private class SpeedColors(val bg: Color, val text: Color, val felt: Color, val feltEdge: Color, val card: Color, val accent: Color, val gold: Color)

@Composable
private fun speedColors(dark: Boolean) = if (!dark) SpeedColors(
    bg = Color(0xFFF4EFE6), text = Color(0xFF2A2620), felt = Color(0xFF2F6F5B), feltEdge = Color(0xFF1F4D3F),
    card = Color(0xFFE6DCCB), accent = Color(0xFF1B7F5F), gold = Color(0xFFE0A100)
) else SpeedColors(
    bg = Color(0xFF15191A), text = Color(0xFFEFE9DD), felt = Color(0xFF1F4A3D), feltEdge = Color(0xFF123027),
    card = Color(0xFF232B2A), accent = Color(0xFF45C79A), gold = Color(0xFFFFC83D)
)

/** Seat naming helper: "You" is spoken in the second person. */
private fun Context.speedAnn(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

/**
 * Speed on a green felt table. The opponent's hand and piles sit at the top (rotated, and tappable by
 * the second person, in Pass and Play), the two centre piles in the middle, and your own hand at the
 * bottom. Tap a card to play it when only one pile fits; when both fit the card lifts and you tap the
 * pile. Both players are stuck? Press Flip (the computer presses too, or the cards flip by themselves
 * after a moment). Every refusal says why. Turn-based assist removes the clock for players who cannot
 * play at speed. Colour-blind safe (suits are shapes), Reduced-Motion aware, TalkBack-friendly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedScreen(
    sessionManager: GameSessionManager,
    game: SpeedGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.UNO, enabled = musicEnabled)
    val state by game.state
    val hintPlay by game.hintPlay
    val lastReject by game.lastReject
    val opponentLeft by game.opponentLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colors = speedColors(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.assist = sessionManager.launchOptions.extras["assist"] == "true"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        val networkedStart = ctx.activeMode == PlayMode.LOCAL_AD_HOC || ctx.activeMode == PlayMode.ONLINE
        if (networkedStart || !sessionManager.tryResume(game)) game.startMatch()
    }

    // The clock: the countdown, the computer's reactions and the automatic flip all run from here.
    LaunchedEffect(game) {
        while (true) {
            game.tick()
            delay(40)
        }
    }

    val activeCtx = context
    val isNetworked = activeCtx?.activeMode == PlayMode.LOCAL_AD_HOC || activeCtx?.activeMode == PlayMode.ONLINE
    val localSeat = activeCtx?.localPlayerIndex ?: 0

    val helpTitle = stringResource(R.string.speed_help_title)
    val helpText = stringResource(R.string.speed_help_text)
    val s = state
    if (s == null) {
        if (isNetworked) {
            GameChrome(
                helpTitle = helpTitle, helpText = helpText, matchInProgress = true,
                onLeave = game::abortMatch, onAbort = { game.abortMatch() }, leaveKeepsProgress = false,
                buttonFill = colors.bg, buttonContent = colors.text
            ) {
                Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.ccl_waiting_host), color = colors.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }

    val bottomSeat = if (isNetworked) localSeat.coerceIn(0, 1) else 0
    val topSeat = 1 - bottomSeat
    val hotSeat = !isNetworked && s.players.none { it.isBot }
    val rankNames = stringArrayResource(R.array.speed_rank_names)
    val suitNames = stringArrayResource(R.array.speed_suit_names)
    val leftWord = stringResource(R.string.speed_pile_left)
    val rightWord = stringResource(R.string.speed_pile_right)
    fun pileWord(p: Int) = if (p == 0) leftWord else rightWord
    fun cardName(c: Card) = stringResource0(androidContext, R.string.speed_card_name, rankNames[c.rank.value - 2], suitNames[c.suit.ordinal])
    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""

    var selBottom by remember { mutableIntStateOf(-1) }
    var selTop by remember { mutableIntStateOf(-1) }
    var whyKey by remember { mutableStateOf<Pair<Int, SpeedReject>?>(null) } // (serial, reason)
    var whyPick by remember { mutableStateOf(false) }
    var goFlash by remember { mutableStateOf(false) }
    var countdownDigit by remember { mutableIntStateOf(3) }

    // Countdown digits and the "Go!" flash.
    LaunchedEffect(s.phase, s.dealSerial) {
        if (s.phase == SpeedPhase.COUNTDOWN) {
            goFlash = false
            for (d in 3 downTo 1) {
                countdownDigit = d
                playSfx(SfxKind.LIGHT_TICK)
                delay(1000L)
            }
        } else if (s.phase == SpeedPhase.PLAYING && s.dealSerial > 0 && !s.assist) {
            goFlash = true
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.STRONG_ACTION)
            delay(700)
            goFlash = false
        }
    }

    // Animations keyed by the engine's serials.
    val slap = remember { listOf(Animatable(1f), Animatable(1f)) }
    val flipAnim = remember { Animatable(1f) }
    LaunchedEffect(s.playSerial) {
        val pile = s.eventPile
        if (s.event != SpeedEvent.PLAY || pile == null || s.playSerial == 0) return@LaunchedEffect
        val mine = !isNetworked && !s.players[s.eventSeat ?: 0].isBot || (isNetworked && s.eventSeat == localSeat)
        playSfx(SfxKind.SOLID_THUNK)
        haptics(if (mine) HapticSignal.NORMAL_ACTION else HapticSignal.LIGHT_TICK)
        if (!reduced) {
            slap[pile].snapTo(1.4f)
            slap[pile].animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
        selBottom = -1; selTop = -1
        whyKey = null
    }
    LaunchedEffect(s.flipSerial) {
        if (s.flipSerial == 0) return@LaunchedEffect
        playSfx(SfxKind.WHOOSH)
        haptics(HapticSignal.LIGHT_TICK)
        selBottom = -1; selTop = -1
        if (!reduced) {
            flipAnim.snapTo(0f)
            flipAnim.animateTo(1f, tween(gameSpeed.scale(420L).toInt().coerceAtLeast(120), easing = LinearEasing))
        }
    }
    LaunchedEffect(lastReject?.serial) {
        val r = lastReject ?: return@LaunchedEffect
        whyKey = r.serial to r.reason
        playSfx(SfxKind.INVALID_BUZZ)
        haptics(HapticSignal.FAILURE)
        delay(3200)
        if (whyKey?.first == r.serial) whyKey = null
    }
    LaunchedEffect(whyPick) { if (whyPick) { delay(2500); whyPick = false } }

    LaunchedEffect(s.boardOver, s.dealSerial) {
        if (!s.boardOver) return@LaunchedEffect
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val localWon = winnerSeat >= 0 && (hotSeat || winnerSeat == bottomSeat)
        if (localWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 80, listOf(colors.gold, Color.White, colors.accent, Color(0xFFE05A5A)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
    }

    // ---- words ----
    val status: String = run {
        val who = seatName(s.eventSeat ?: 0)
        val last = when (s.event) {
            SpeedEvent.PLAY -> {
                val c = s.eventCardId?.let { id -> com.gamesuite.games.speed.SpeedCards.byId(id) }
                if (c != null) androidContext.speedAnn(who, R.string.speed_ann_play, R.string.speed_ann_play_you, cardName(c), pileWord(s.eventPile ?: 0)) else ""
            }
            SpeedEvent.FLIP -> stringResource(R.string.speed_ann_flip)
            SpeedEvent.RESHUFFLE -> stringResource(R.string.speed_ann_reshuffle)
            SpeedEvent.PASS -> androidContext.speedAnn(who, R.string.speed_ann_pass, R.string.speed_ann_pass_you)
            SpeedEvent.WIN -> androidContext.speedAnn(who, R.string.speed_ann_win, R.string.speed_ann_win_you)
            SpeedEvent.DRAW -> stringResource(R.string.speed_ann_draw)
            else -> stringResource(R.string.speed_ann_start)
        }
        val turn = when {
            s.boardOver -> ""
            s.phase == SpeedPhase.COUNTDOWN -> stringResource(R.string.speed_status_countdown)
            opponentLeft -> stringResource(R.string.speed_opp_left)
            s.assist && hotSeat -> stringResource(R.string.speed_assist_turn_named, seatName(s.assistTurn))
            s.assist && s.assistTurn == bottomSeat -> stringResource(R.string.speed_assist_turn_you)
            s.assist -> stringResource(R.string.speed_assist_wait, seatName(s.assistTurn))
            else -> stringResource(R.string.speed_race)
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }
    val whyText: String? = when {
        whyPick -> stringResource(R.string.speed_why_pick)
        else -> whyKey?.second?.let { r ->
            when (r) {
                SpeedReject.NOT_STARTED -> stringResource(R.string.speed_why_not_started)
                SpeedReject.ILLEGAL, SpeedReject.NO_SUCH_CARD -> stringResource(R.string.speed_why_illegal)
                SpeedReject.LOST_RACE -> stringResource(R.string.speed_why_lost_race)
                SpeedReject.NOT_YOUR_TURN -> stringResource(R.string.speed_why_not_turn)
                SpeedReject.CAN_STILL_PLAY -> stringResource(R.string.speed_why_still_play)
                SpeedReject.NOT_YOUR_SEAT -> stringResource(if (opponentLeft) R.string.speed_opp_left else R.string.speed_why_not_turn)
                SpeedReject.NOT_PLAYING -> stringResource(R.string.speed_why_over)
                SpeedReject.NONE -> null
            }
        }
    }

    // ---- actions ----
    fun tapPile(pile: Int, seat: Int) {
        val sel = if (seat == bottomSeat) selBottom else selTop
        val legal = SpeedGame.legalPlaysIn(s, seat)
        val card = when {
            sel >= 0 -> sel
            else -> legal.filter { it.pile == pile }.map { it.cardId }.singleOrNull()
        }
        if (card == null) {
            if (legal.none { it.pile == pile }) game.play(seat, s.hands[seat].firstOrNull { it != null }?.id ?: -1, pile) else whyPick = true
            return
        }
        game.play(seat, card, pile)
    }

    fun tapCard(c: Card, seat: Int) {
        val legal = SpeedGame.legalPlaysIn(s, seat).filter { it.cardId == c.id }
        when {
            legal.size == 1 -> game.play(seat, c.id, legal[0].pile)
            legal.size >= 2 -> if (seat == bottomSeat) selBottom = if (selBottom == c.id) -1 else c.id else selTop = if (selTop == c.id) -1 else c.id
            else -> game.play(seat, c.id, 0) // refused with the reason (not started, not your turn, wrong rank)
        }
    }

    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onHint = if (isNetworked) null else ({ game.hint(bottomSeat) }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val (_, openCoach) = rememberClCoach("speed", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val byWidth = maxWidth / 6.9f
            val byHeight = (maxHeight - (if (maxWidth > maxHeight * 1.15f) 140.dp else 200.dp)) / (1.4f * 2.9f)
            val landscape = maxWidth > maxHeight * 1.15f
            val cw: Dp = minOf(byWidth, byHeight).coerceIn(if (landscape) 34.dp else 48.dp, 86.dp)
            val topScale = if (hotSeat) 1f else 0.62f
            val pileW = cw * 1.12f

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = this@BoxWithConstraints.maxHeight).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("speed", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(topSeat, bottomSeat).forEach { i ->
                            val name = androidContext.seatLabel(seatName(i)) + if (isNetworked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
                            val left = s.cardsLeft(i)
                            val desc = stringResource(R.string.speed_chip_desc, name, left) + if (s.players[i].isBot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
                            Text(
                                stringResource(R.string.speed_chip, name, left), color = colors.text, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                                    .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = desc }
                            )
                        }
                        if (s.assist) Text(stringResource(R.string.speed_assist_badge), color = colors.text, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, colors.accent, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                    if (landscape && !isNetworked && !s.boardOver) {
                        OutlinedButton(
                            onClick = { game.hint(bottomSeat) },
                            enabled = s.phase == SpeedPhase.PLAYING,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_hint)) }
                    }
                }
                ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 2.dp), emphasized = true)
                ClWhyLine(whyText, colors.text, colors.card)
              }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(14.dp))
                // ---- opponent (top) ----
                SpeedHandRow(
                    s = s, seat = topSeat, cw = cw * topScale, faceUp = hotSeat, rotated = true, interactive = hotSeat,
                    selectedId = selTop, hint = null, colorblind = colorblind, reduced = reduced, colors = colors,
                    rankNames = rankNames, suitNames = suitNames, leftWord = leftWord, rightWord = rightWord,
                    onTap = { c -> tapCard(c, topSeat) }
                )
                if (hotSeat) SpeedFlipButton(s, topSeat, colors, rotated = true) { game.requestFlip(topSeat) }
              }

                // ---- the table ----
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.felt)
                        .border(3.dp, colors.feltEdge, RoundedCornerShape(20.dp)).padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                ) {
                    SpareStack(s.reserveCounts[0], cw * 0.62f, stringResource(R.string.speed_spare_desc, seatName(0), s.reserveCounts[0]))
                    for (p in 0..1) {
                        val selected = (selBottom >= 0 && SpeedGame.legalPlaysIn(s, bottomSeat).any { it.cardId == selBottom && it.pile == p }) ||
                            (selTop >= 0 && SpeedGame.legalPlaysIn(s, topSeat).any { it.cardId == selTop && it.pile == p })
                        SpeedPile(
                            top = s.pileTops[p], size = s.pileSizes[p], width = pileW, slap = slap[p].value, flipProgress = flipAnim.value,
                            glow = selected, hintGlow = hintPlay?.pile == p, colorblind = colorblind, colors = colors,
                            description = stringResource(R.string.speed_pile_desc, pileWord(p),
                                s.pileTops[p]?.let { cardName(it) } ?: "", s.pileSizes[p]),
                            onTap = { tapPile(p, if (hotSeat && selTop >= 0 && selBottom < 0) topSeat else bottomSeat) }
                        )
                    }
                    SpareStack(s.reserveCounts[1], cw * 0.62f, stringResource(R.string.speed_spare_desc, seatName(1), s.reserveCounts[1]))
                }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // ---- me (bottom) ----
                SpeedFlipButton(s, bottomSeat, colors, rotated = false) { game.requestFlip(bottomSeat) }
                SpeedHandRow(
                    s = s, seat = bottomSeat, cw = cw, faceUp = true, rotated = false, interactive = true,
                    selectedId = selBottom, hint = hintPlay, colorblind = colorblind, reduced = reduced, colors = colors,
                    rankNames = rankNames, suitNames = suitNames, leftWord = leftWord, rightWord = rightWord,
                    onTap = { c -> tapCard(c, bottomSeat) }
                )
                if (!landscape && !isNetworked && !s.boardOver) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { game.hint(bottomSeat) },
                        enabled = s.phase == SpeedPhase.PLAYING,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.ccl_hint)) }
                }
                if (s.boardOver) {
                    Spacer(Modifier.height(8.dp))
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val title = when {
                        winnerSeat < 0 -> stringResource(R.string.ccl_a_draw)
                        hotSeat -> androidContext.speedAnn(seatName(winnerSeat), R.string.speed_ann_win, R.string.speed_ann_win_you)
                        winnerSeat == bottomSeat -> stringResource(R.string.ccl_you_won)
                        else -> stringResource(R.string.ccl_you_lost)
                    }
                    val session = s.players.joinToString(" · ") { "${it.displayName} ${game.sessionWins.value[it.playerId] ?: 0}" }
                    ClFinishedPanel(
                        title = title, subtitle = null,
                        lines = listOf(stringResource(R.string.ccl_session, session)),
                        canPlayAgain = !opponentLeft,
                        onPlayAgain = { game.playAgain() },
                        onLeave = { game.leaveSession() }
                    )
                }
              }
            }
            }

            // Countdown / Go overlay
            if (s.phase == SpeedPhase.COUNTDOWN || goFlash) {
                val label = if (goFlash) stringResource(R.string.speed_go) else countdownDigit.toString()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 88.sp,
                        modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0x99000000)).padding(horizontal = 36.dp, vertical = 8.dp)
                            .semantics { contentDescription = label }
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
        }
    }
}

private fun stringResource0(ctx: Context, id: Int, vararg args: Any): String = ctx.getString(id, *args)

@Composable
private fun SpeedFlipButton(s: SpeedState, seat: Int, colors: SpeedColors, rotated: Boolean, onClick: () -> Unit) {
    val stuck = SpeedGame.isStuck(s, seat) && s.phase == SpeedPhase.PLAYING && !s.boardOver
    val myTurn = !s.assist || s.assistTurn == seat
    val waiting = s.flipReady.getOrElse(seat) { false }
    val label = when {
        waiting -> stringResource(R.string.speed_btn_waiting)
        s.assist -> stringResource(R.string.speed_btn_pass)
        else -> stringResource(R.string.speed_btn_flip)
    }
    if (!stuck || !myTurn) {
        Spacer(Modifier.height(2.dp))
        return
    }
    Button(
        onClick = onClick, enabled = !waiting,
        colors = ButtonDefaults.buttonColors(containerColor = colors.gold, contentColor = Color(0xFF1B1B20)),
        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 160.dp).padding(vertical = 4.dp).let { if (rotated) it.rotate(180f) else it }
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

/** Five hand slots and the draw pile for one seat. */
@Composable
private fun SpeedHandRow(
    s: SpeedState, seat: Int, cw: Dp, faceUp: Boolean, rotated: Boolean, interactive: Boolean, selectedId: Int, hint: SpeedPlay?,
    colorblind: Boolean, reduced: Boolean, colors: SpeedColors, rankNames: Array<String>, suitNames: Array<String>,
    leftWord: String, rightWord: String, onTap: (Card) -> Unit
) {
    val ctx = LocalContext.current
    val legal = SpeedGame.legalPlaysIn(s, seat)
    val ch = cardHeightFor(cw)
    Row(
        Modifier.let { if (rotated) it.rotate(180f) else it }.heightIn(min = ch + 14.dp),
        horizontalArrangement = Arrangement.spacedBy(cw * 0.07f), verticalAlignment = Alignment.Bottom
    ) {
        for (slot in 0 until 5) {
            val card = s.hands[seat][slot]
            Box(Modifier.size(cw, ch + 14.dp), contentAlignment = Alignment.BottomCenter) {
                if (card == null) {
                    Box(Modifier.size(cw, ch).clip(RoundedCornerShape(cw * 0.1f)).border(1.dp, colors.text.copy(alpha = 0.18f), RoundedCornerShape(cw * 0.1f))
                        .semantics { contentDescription = ctx.getString(R.string.speed_empty_slot) })
                } else {
                    val plays = legal.filter { it.cardId == card.id }
                    val name = if (card.id >= 0) ctx.getString(R.string.speed_card_name, rankNames[card.rank.value - 2], suitNames[card.suit.ordinal]) else ""
                    val desc = when {
                        !faceUp || card.id < 0 -> ctx.getString(R.string.speed_opp_card)
                        plays.size >= 2 -> ctx.getString(R.string.speed_card_desc_both, name)
                        plays.size == 1 -> ctx.getString(R.string.speed_card_desc_pile, name, if (plays[0].pile == 0) leftWord else rightWord)
                        else -> ctx.getString(R.string.speed_card_desc_none, name)
                    } + (if (selectedId == card.id) " " + ctx.getString(R.string.speed_selected) else "") +
                        (if (hint?.cardId == card.id) " " + ctx.getString(R.string.speed_suggested) else "")
                    val lift = remember(card.id) { Animatable(if (reduced) 0f else 1f) }
                    LaunchedEffect(card.id) { if (lift.value != 0f) lift.animateTo(0f, tween(260)) }
                    val playable = faceUp && card.id >= 0 && plays.isNotEmpty()
                    val raise = if (selectedId == card.id) 0.2f else 0f
                    Box(
                        Modifier
                            .graphicsLayer {
                                translationY = (lift.value * cw.toPx() * 0.7f) - raise * cw.toPx()
                                alpha = 1f - lift.value * 0.5f
                            }
                            .let { m ->
                                when {
                                    hint?.cardId == card.id -> m.border(3.dp, colors.gold, RoundedCornerShape(cw * 0.1f))
                                    playable -> m.border(2.dp, colors.accent, RoundedCornerShape(cw * 0.1f))
                                    else -> m
                                }
                            }
                            .let { m -> if (interactive) m.clickable(onClickLabel = null) { onTap(card) } else m }
                            .semantics { contentDescription = desc }
                    ) {
                        if (faceUp && card.id >= 0) SpeedCardFace(card, cw, colorblind = colorblind, dim = !playable && s.phase == SpeedPhase.PLAYING)
                        else SpeedCardBack(cw)
                    }
                }
            }
        }
        DrawStack(s.drawCounts[seat], cw, stringResource(R.string.speed_draw_desc, s.players[seat].displayName, s.drawCounts[seat]))
    }
}

@Composable
private fun DrawStack(count: Int, cw: Dp, description: String) {
    val ch = cardHeightFor(cw)
    Box(Modifier.size(cw, ch + 14.dp).semantics { contentDescription = description }, contentAlignment = Alignment.BottomCenter) {
        if (count > 0) {
            Box(Modifier.padding(bottom = 3.dp)) { SpeedCardBack(cw, elevation = 1.dp) }
            Box(Modifier.padding(bottom = 1.dp, start = 2.dp)) { SpeedCardBack(cw, elevation = 1.dp) }
            SpeedCardBack(cw)
            Text(
                count.toString(), color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SpareStack(count: Int, cw: Dp, description: String) {
    Box(Modifier.size(cw, cardHeightFor(cw) + 4.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (count > 0) {
            SpeedCardBack(cw, tint = Color(0xFF5B3E8C), elevation = 2.dp)
            Text(
                count.toString(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/** A centre pile: the top card with a little tilt (so a heap looks heaped), the slap bounce and the flip. */
@Composable
private fun SpeedPile(
    top: Card?, size: Int, width: Dp, slap: Float, flipProgress: Float, glow: Boolean, hintGlow: Boolean,
    colorblind: Boolean, colors: SpeedColors, description: String, onTap: () -> Unit
) {
    val tilt = if (top == null) 0f else ((top.id * 37) % 11 - 5) * 0.9f
    val h = cardHeightFor(width)
    val rotY = (1f - flipProgress) * 180f
    Box(
        Modifier.size(width + 12.dp, h + 12.dp).clickable(onClick = onTap).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (size > 1) Box(Modifier.graphicsLayer { rotationZ = -tilt * 1.4f }.padding(1.dp)) { SpeedCardBack(width, elevation = 1.dp, tint = Color(0xFF3E4A98)) }
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = slap; scaleY = slap; rotationZ = tilt; rotationY = rotY; cameraDistance = 12f * density
                }
                .let { m ->
                    when {
                        hintGlow -> m.border(4.dp, colors.gold, RoundedCornerShape(width * 0.1f))
                        glow -> m.border(4.dp, colors.accent, RoundedCornerShape(width * 0.1f))
                        else -> m
                    }
                }
        ) {
            if (top == null) Box(Modifier.size(width, h))
            else if (rotY > 90f) SpeedCardBack(width, tint = Color(0xFF3E4A98))
            else SpeedCardFace(top, width, colorblind = colorblind)
        }
    }
}
