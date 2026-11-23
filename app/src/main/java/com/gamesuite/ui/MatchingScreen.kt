package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.PlayMode
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.matching.MatchingArt
import com.gamesuite.games.matching.MatchingEvent
import com.gamesuite.games.matching.MatchingGame
import com.gamesuite.games.matching.MatchingReject
import com.gamesuite.games.matching.MatchingRules
import com.gamesuite.games.matching.MatchingState
import com.gamesuite.games.matching.MatchingStatsStore
import com.gamesuite.games.matching.MatchingStatus
import com.gamesuite.games.matching.MatchingTheme
import com.gamesuite.games.speed.CardArtColors
import com.gamesuite.games.speed.SpeedCardBack
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
import kotlinx.coroutines.launch

private class MatchColors(val bg: Color, val text: Color, val card: Color, val accent: Color, val gold: Color, val back: Color)

@Composable
private fun matchColors(dark: Boolean) = if (!dark) MatchColors(
    bg = Color(0xFFF1F4F0), text = Color(0xFF23302B), card = Color(0xFFDDE7E0), accent = Color(0xFF1F7A8C), gold = Color(0xFFE0A100), back = Color(0xFF1F7A8C)
) else MatchColors(
    bg = Color(0xFF13191A), text = Color(0xFFE9F0EC), card = Color(0xFF212B2A), accent = Color(0xFF5CC6D8), gold = Color(0xFFFFC83D), back = Color(0xFF1B6677)
)

private fun Context.matchAnn(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you, *extra) else getString(other, name, *extra)

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Matching on a teal-backed deck. Tap a face-down card to turn it; turn two to find a pair. A pair
 * stays up, scores for you and you go again; a miss turns back and passes the turn. The solo game counts
 * moves and time, has Undo and a Hint that only uses what you have already seen, and keeps your best.
 * Three picture sets (critters, bold symbols, playing cards); colour-blind safe, Reduced-Motion aware,
 * every card described for TalkBack, and the status line announces each flip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchingScreen(
    sessionManager: GameSessionManager,
    game: MatchingGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.PUZZLE_FOCUS, enabled = musicEnabled)
    val state by game.state
    val hint by game.hint
    val opponentLeft by game.opponentLeft
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val colors = matchColors(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val gameSpeed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val burst = rememberParticleBurst()
    val scope = rememberCoroutineScope()
    val statsStore = remember { MatchingStatsStore(androidContext.applicationContext) }
    val records by remember(statsStore) { statsStore.records }.collectAsState(initial = emptyMap())

    var theme by remember { mutableStateOf(MatchingTheme.CRITTERS) }
    var whyText by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var newBest by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstOpenerIndex = sessionManager.launchOptions.firstMoverIndex
        game.cardCount = MatchingRules.countFromOptionId(extras["cards"])
        theme = MatchingTheme.fromId(extras["theme"])
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

    val helpTitle = stringResource(R.string.match_help_title)
    val helpText = stringResource(R.string.match_help_text)
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

    val solo = s.players.size == 1
    val currentIsBot = s.players[s.currentPlayerIndex].isBot
    val isGuest = isNetworked && localSeat != 0

    // The look at a pair, then score it or turn it back. The host (or the offline device) decides.
    LaunchedEffect(s.resolving, s.flipSerial) {
        if (s.resolving && !isGuest) {
            delay(gameSpeed.scale(MatchingRules.RESOLVE_DELAY_MS))
            game.resolve()
        }
    }
    // The computer turns one card at a time with a short, natural pause.
    LaunchedEffect(s.flipSerial, s.resolveSerial, s.dealSerial, s.resolving, s.currentPlayerIndex, s.boardOver) {
        if (s.boardOver || s.resolving || isGuest) return@LaunchedEffect
        if (game.botToMove(s)) {
            delay(gameSpeed.scale(if (s.eventCards.isEmpty() || s.event == MatchingEvent.START) 800L else 650L))
            game.botFlipAsync()
        }
    }
    // The solo clock.
    LaunchedEffect(solo, s.boardOver, s.dealSerial) {
        while (solo && !s.boardOver) {
            elapsed = game.elapsedMs()
            delay(250)
        }
        if (solo) elapsed = game.elapsedMs()
    }

    // Sound, touch and celebration.
    LaunchedEffect(s.flipSerial) {
        if (s.flipSerial == 0 || s.event != MatchingEvent.FLIP) return@LaunchedEffect
        playSfx(SfxKind.LIGHT_TICK)
        haptics(HapticSignal.LIGHT_TICK)
    }
    LaunchedEffect(s.resolveSerial) {
        if (s.resolveSerial == 0) return@LaunchedEffect
        val mine = s.eventSeat != null && (if (isNetworked) s.eventSeat == localSeat else !s.players[s.eventSeat].isBot)
        when (s.event) {
            MatchingEvent.MATCH -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(if (mine) HapticSignal.SUCCESS else HapticSignal.LIGHT_TICK) }
            MatchingEvent.MISS -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }
    LaunchedEffect(s.boardOver, s.dealSerial) {
        if (!s.boardOver) { newBest = null; return@LaunchedEffect }
        val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
        val localWon = solo || (winnerSeat >= 0 && (if (isNetworked) winnerSeat == localSeat else !s.players[winnerSeat].isBot))
        if (localWon) {
            playSfx(SfxKind.SUCCESS_CHIME)
            haptics(HapticSignal.CELEBRATION)
            if (!reduced) burst.spawn(Offset(0.5f, 0.4f), 80, listOf(colors.gold, Color.White, colors.accent, Color(0xFFE05A5A)), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..1.9f, gravity = 0.9f)
        } else {
            playSfx(SfxKind.INVALID_BUZZ)
            haptics(HapticSignal.FAILURE)
        }
        if (solo && game.isCleanRun) {
            try {
                val r = statsStore.recordSolve(s.cardCount, s.moves[0], game.elapsedMs())
                newBest = r.isNewBestMoves to r.isNewBestTime
            } catch (_: Exception) { }
        }
    }
    LaunchedEffect(whyText) { if (whyText != null) { delay(2800); whyText = null } }

    // ---- words ----
    val speciesNames = stringArrayResource(R.array.match_species_names)
    val coatNames = stringArrayResource(R.array.match_coat_names)
    val shapeNames = stringArrayResource(R.array.match_shape_names)
    val rankNames = stringArrayResource(R.array.match_rank_names)
    fun faceName(face: Int): String = when (theme) {
        MatchingTheme.CRITTERS -> androidContext.getString(R.string.match_face_critter, coatNames[MatchingArt.coat(face)], speciesNames[MatchingArt.species(face)])
        MatchingTheme.SYMBOLS -> androidContext.getString(if (MatchingArt.isOutline(face)) R.string.match_face_outline else R.string.match_face_solid, shapeNames[MatchingArt.shape(face)])
        MatchingTheme.CARDS -> androidContext.getString(R.string.match_face_rank, rankNames[MatchingArt.cardRankIndex(face)], androidContext.getString(if (MatchingArt.cardIsRed(face)) R.string.match_red else R.string.match_black))
    }
    fun seatName(i: Int) = s.players.getOrNull(i)?.displayName ?: ""

    val lastIndex = s.eventCards.firstOrNull()
    val status: String = run {
        val who = seatName(s.eventSeat ?: s.currentPlayerIndex)
        val last = when (s.event) {
            MatchingEvent.FLIP -> if (lastIndex != null && s.faces.getOrElse(lastIndex) { -1 } >= 0)
                androidContext.matchAnn(who, R.string.match_ann_flip, R.string.match_ann_flip_you, lastIndex + 1, faceName(s.faces[lastIndex])) else ""
            MatchingEvent.MATCH -> androidContext.matchAnn(who, R.string.match_ann_match, R.string.match_ann_match_you, s.eventCards.firstOrNull()?.let { faceName(s.faces[it]) } ?: "")
            MatchingEvent.MISS -> androidContext.matchAnn(who, R.string.match_ann_miss, R.string.match_ann_miss_you)
            MatchingEvent.WIN -> if (solo) stringResource(R.string.match_ann_solo_done, s.moves[0])
                else androidContext.matchAnn(seatName(s.players.indexOfFirst { it.playerId == s.winnerPlayerId }), R.string.match_ann_win, R.string.match_ann_win_you, s.scores.max())
            MatchingEvent.TIE -> stringResource(R.string.match_ann_tie)
            else -> stringResource(R.string.match_ann_start)
        }
        val turn = when {
            s.boardOver || solo -> ""
            opponentLeft -> stringResource(R.string.match_opp_left)
            currentIsBot -> stringResource(R.string.match_thinking, seatName(s.currentPlayerIndex))
            !isNetworked && s.players.none { it.isBot } -> stringResource(R.string.match_turn_named, seatName(s.currentPlayerIndex))
            !isNetworked || s.currentPlayerIndex == localSeat -> stringResource(R.string.match_turn_you)
            else -> stringResource(R.string.match_turn_named, seatName(s.currentPlayerIndex))
        }
        listOf(last, turn).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun tap(index: Int) {
        val r = game.flipCard(index)
        when (r) {
            MatchingReject.NOT_YOUR_TURN -> {
                whyText = androidContext.getString(if (opponentLeft) R.string.match_opp_left else if (isNetworked) R.string.match_why_wait else R.string.match_why_cpu)
                playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
            }
            MatchingReject.NOT_FACE_DOWN -> { whyText = androidContext.getString(R.string.match_why_taken); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }

    val finishedBoards = game.sessionWins.value.values.sum() + game.sessionDraws.value
    val canPlayTurn = !s.boardOver && !s.resolving && !opponentLeft &&
        (if (isNetworked) s.currentPlayerIndex == localSeat else !currentIsBot)
    val record = records[s.cardCount.toString()]
    val nextThemeName = stringResource(themeLabel(MatchingTheme.entries[(theme.ordinal + 1) % MatchingTheme.entries.size]))

    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.boardOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedBoards > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedBoards == 0,
        onUndo = if (isNetworked || !solo) null else ({ game.undo() }),
        canUndo = game.canUndo,
        onHint = if (isNetworked) null else ({ game.requestHint() }),
        buttonFill = colors.bg, buttonContent = colors.text,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finishedBoards > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body),
        extraItems = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.match_menu_theme, nextThemeName)) },
                onClick = { theme = MatchingTheme.entries[(theme.ordinal + 1) % MatchingTheme.entries.size]; dismiss() }
            )
        }
    ) {
        val (_, openCoach) = rememberClCoach("matching", ready = true)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val actionButtons: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (solo) OutlinedButton(
                            onClick = { game.undo() }, enabled = game.canUndo,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_undo)) }
                        OutlinedButton(
                            onClick = {
                                val h = game.requestHint()
                                whyText = androidContext.getString(
                                    when {
                                        h == null -> R.string.match_hint_none
                                        h.known -> R.string.match_hint_known
                                        else -> R.string.match_hint_explore
                                    }
                                )
                            },
                            enabled = canPlayTurn,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text), modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.ccl_hint)) }
                    }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                    ClCoachButton("matching", openCoach)
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (solo) {
                            val pairsText = stringResource(R.string.match_pairs_found, s.scores[0], s.pairs)
                            Chip(pairsText, colors, true)
                            Chip(stringResource(R.string.match_moves, s.moves[0]), colors, false)
                            Chip(stringResource(R.string.match_time, formatTime(elapsed)), colors, false)
                        } else {
                            s.players.indices.forEach { i ->
                                val name = androidContext.seatLabel(seatName(i)) + if (isNetworked && i == localSeat) " " + stringResource(R.string.ccl_you_suffix) else ""
                                val isTurn = i == s.currentPlayerIndex && !s.boardOver
                                val desc = stringResource(R.string.match_chip_desc, name, s.scores[i]) + if (s.players[i].isBot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
                                Row(
                                    Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(14.dp))
                                        .background(if (isTurn) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
                                        .border(if (isTurn) 2.dp else 1.dp, if (isTurn) colors.accent else colors.text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = desc },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SeatBadge(i, 20.dp, colors)
                                    Text(" " + stringResource(R.string.match_chip, name, s.scores[i]), color = colors.text,
                                        fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (landscape && !s.boardOver && !isNetworked) actionButtons()
                }
                ClStatusLine(status, colors.text, Modifier.fillMaxWidth().padding(top = 2.dp), emphasized = true)
                ClWhyLine(whyText, colors.text, colors.card)
                Spacer(Modifier.height(4.dp))

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val portrait = maxHeight >= maxWidth
                    val (cols, rows) = MatchingRules.grid(s.cardCount, portrait)
                    val gap = 6.dp
                    val aspect = 1.18f
                    val byW = (maxWidth - gap * (cols - 1)) / cols
                    val byH = (maxHeight - gap * (rows - 1)) / rows / aspect
                    val raw = minOf(byW, byH)
                    val cw = raw.coerceIn(48.dp, 120.dp)
                    val scrolls = raw < 48.dp
                    val ch = cw * aspect
                    Column(
                        Modifier.let { if (scrolls) it.verticalScroll(rememberScrollState()) else it },
                        verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        for (r in 0 until rows) {
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                for (c in 0 until cols) {
                                    val i = r * cols + c
                                    if (i >= s.cardCount) { Spacer(Modifier.size(cw, ch)); continue }
                                    val face = s.faces[i]
                                    val st = s.status[i]
                                    val name = when {
                                        st == MatchingStatus.DOWN || face < 0 -> stringResource(R.string.match_card_down, i + 1, s.cardCount)
                                        st == MatchingStatus.MATCHED -> stringResource(R.string.match_card_matched, i + 1, faceName(face), seatName(s.owner[i]))
                                        else -> stringResource(R.string.match_card_up, i + 1, faceName(face))
                                    }
                                    MatchCard(
                                        index = i, face = face, status = st, owner = s.owner[i], multi = !solo, theme = theme, width = cw, height = ch,
                                        colors = colors, colorblind = colorblind, reduced = reduced, speed = gameSpeed.delayScale,
                                        dealSerial = s.dealSerial, pulse = s.event == MatchingEvent.MATCH && i in s.eventCards, pulseKey = s.resolveSerial,
                                        hinted = hint?.cards?.contains(i) == true, enabled = canPlayTurn, description = name,
                                        onTap = { tap(i) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!landscape && !s.boardOver && !isNetworked) {
                    Spacer(Modifier.height(4.dp))
                    actionButtons()
                }
            }

            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }

            if (s.boardOver) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).padding(16.dp), contentAlignment = Alignment.Center) {
                    val winnerSeat = s.players.indexOfFirst { it.playerId == s.winnerPlayerId }
                    val title = when {
                        solo -> stringResource(R.string.match_done_title)
                        s.tie -> stringResource(R.string.ccl_a_draw)
                        isNetworked -> stringResource(if (winnerSeat == localSeat) R.string.ccl_you_won else R.string.ccl_you_lost)
                        s.players.none { it.isBot } -> androidContext.matchAnn(seatName(winnerSeat), R.string.match_ann_win, R.string.match_ann_win_you, s.scores.max())
                        else -> stringResource(if (!s.players[winnerSeat].isBot) R.string.ccl_you_won else R.string.ccl_you_lost)
                    }
                    val lines = buildList {
                        if (solo) {
                            add(stringResource(R.string.match_done_stats, s.moves[0], formatTime(game.elapsedMs())))
                            val nb = newBest
                            when {
                                !game.isCleanRun -> add(stringResource(R.string.match_unclean))
                                nb != null && (nb.first || nb.second) -> add(stringResource(R.string.match_new_best))
                                record?.bestMoves != null -> add(stringResource(R.string.match_record, record.bestMoves!!, formatTime(record.bestTimeMillis ?: 0L)))
                            }
                        } else {
                            add(s.players.indices.joinToString(" · ") { "${seatName(it)} ${s.scores[it]}" })
                            add(stringResource(R.string.ccl_session, s.players.joinToString(" · ") { "${it.displayName} ${game.sessionWins.value[it.playerId] ?: 0}" }))
                        }
                    }
                    ClFinishedPanel(
                        title = title, subtitle = null, lines = lines,
                        canPlayAgain = !opponentLeft, onPlayAgain = { scope.launch { game.playAgain() } }, onLeave = { game.leaveSession() }
                    )
                }
            }
        }
    }
}

private fun themeLabel(t: MatchingTheme): Int = when (t) {
    MatchingTheme.CRITTERS -> R.string.match_theme_critters
    MatchingTheme.SYMBOLS -> R.string.match_theme_symbols
    MatchingTheme.CARDS -> R.string.match_theme_cards
}

@Composable
private fun Chip(text: String, colors: MatchColors, strong: Boolean) {
    Text(
        text, color = colors.text, style = MaterialTheme.typography.bodySmall, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(14.dp)).background(colors.card).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** A seat's mark: its number in a ring, so it never depends on colour. */
@Composable
private fun SeatBadge(seat: Int, size: Dp, colors: MatchColors) {
    val ring = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFF009E73), Color(0xFFCC79A7))[seat % 4]
    Box(Modifier.size(size).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        Text((seat + 1).toString(), color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MatchCard(
    index: Int, face: Int, status: Int, owner: Int, multi: Boolean, theme: MatchingTheme, width: Dp, height: Dp,
    colors: MatchColors, colorblind: Boolean, reduced: Boolean, speed: Float, dealSerial: Int, pulse: Boolean, pulseKey: Int,
    hinted: Boolean, enabled: Boolean, description: String, onTap: () -> Unit
) {
    val faceUp = (status != MatchingStatus.DOWN) && face >= 0
    val rotation by animateFloatAsState(
        targetValue = if (faceUp) 180f else 0f,
        animationSpec = if (reduced) tween(0) else tween((300 * speed).toInt().coerceAtLeast(60)),
        label = "flip"
    )
    val appear = remember(dealSerial) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(dealSerial) {
        if (appear.value < 1f) {
            delay(index * 14L)
            appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        if (pulse && !reduced) {
            bounce.snapTo(1.18f)
            bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
    }
    val shape = RoundedCornerShape(width * 0.12f)
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer {
                val a = appear.value
                scaleX = a * bounce.value; scaleY = a * bounce.value; alpha = a.coerceIn(0f, 1f)
                rotationY = rotation; cameraDistance = 14f * density
            }
            .clickable(enabled = enabled && status == MatchingStatus.DOWN) { onTap() }
            .semantics { contentDescription = description }
    ) {
        if (rotation <= 90f) {
            SpeedCardBack(width, tint = colors.back)
            Box(Modifier.size(width, height).let { m -> if (hinted) m.border(4.dp, colors.gold, shape) else m })
        } else {
            Box(
                Modifier.size(width, height).graphicsLayer { rotationY = 180f }
                    .clip(shape).background(CardArtColors.Stock)
                    .border(if (status == MatchingStatus.MATCHED) 3.dp else 1.dp, if (status == MatchingStatus.MATCHED) colors.accent else CardArtColors.StockEdge, shape)
                    .padding(width * 0.07f)
            ) {
                MatchingArt.Face(theme, face, colorblind, Modifier.graphicsLayer { alpha = if (status == MatchingStatus.MATCHED) 0.9f else 1f })
                if (status == MatchingStatus.MATCHED && multi && owner >= 0) {
                    Box(Modifier.align(Alignment.TopEnd)) { SeatBadge(owner, width * 0.3f, colors) }
                }
            }
        }
    }
}
