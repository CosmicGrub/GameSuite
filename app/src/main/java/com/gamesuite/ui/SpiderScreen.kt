package com.gamesuite.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.spider.SPIDER_CLUBS
import com.gamesuite.games.spider.SPIDER_COLUMNS
import com.gamesuite.games.spider.SPIDER_DECK_SIZE
import com.gamesuite.games.spider.SPIDER_DIAMONDS
import com.gamesuite.games.spider.SPIDER_HEARTS
import com.gamesuite.games.spider.SPIDER_RUNS_TO_WIN
import com.gamesuite.games.spider.SPIDER_RUN_LENGTH
import com.gamesuite.games.spider.SpiderEvent
import com.gamesuite.games.spider.SpiderGame
import com.gamesuite.games.spider.SpiderHint
import com.gamesuite.games.spider.SpiderLayout
import com.gamesuite.games.spider.SpiderMetrics
import com.gamesuite.games.spider.SpiderOptions
import com.gamesuite.games.spider.SpiderReject
import com.gamesuite.games.spider.SpiderRules
import com.gamesuite.games.spider.SpiderSelection
import com.gamesuite.games.spider.SpiderState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ---- table palette (original: a deep felt, ivory cards, an indigo back) ----------------------------
private val SpiderFeltTop = Color(0xFF0E5A3B)
private val SpiderFeltBottom = Color(0xFF083A27)
private val SpiderCardFace = Color(0xFFFBF7EC)
private val SpiderCardEdge = Color(0xFFB9B09A)
private val SpiderBackTop = Color(0xFF3B3F8C)
private val SpiderBackBottom = Color(0xFF23265F)
private val SpiderSelect = Color(0xFFFFC83D)
private val SpiderHintRing = Color(0xFF3DDCFF)

private fun spiderSuitColor(suit: Int, colorblind: Boolean): Color = when (suit) {
    SPIDER_HEARTS, SPIDER_DIAMONDS -> if (colorblind) Color(0xFFB3261E) else Color(0xFFC62828)
    else -> if (colorblind) Color(0xFF14213D) else Color(0xFF1B1B1F)
}

private fun spiderSuitGlyph(suit: Int): String = when (suit) {
    SPIDER_HEARTS -> "♥"
    SPIDER_CLUBS -> "♣"
    SPIDER_DIAMONDS -> "♦"
    else -> "♠"
}

private val spiderRankShort = arrayOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

private fun spiderClock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Names a card for the screen reader: "Seven of hearts". */
private class SpiderCardNamer(private val ranks: Array<String>, private val suitNames: Array<String>, private val format: String, private val suits: Int) {
    fun name(id: Int): String =
        String.format(format, ranks[SpiderRules.rankOf(id) - 1], suitNames[SpiderRules.suitOf(id, suits)])
}

private class SpiderDrag(val col: Int, val index: Int, val offset: Offset)

/**
 * Spider Solitaire. Every one of the 104 cards is one composable keyed by its id and placed at the
 * slot [SpiderLayout.placements] computes, so a move, a dealt row or a finished run animates by simply
 * gliding each affected card to its new slot (and a card turning face up flips). Dragging a run drops
 * it on the lane under it; tapping works too (tap a run, then a column), and every card is a labelled
 * accessibility node with "move to column N" actions, so TalkBack never needs a drag.
 *
 * Narrow screens: the ten lanes shrink to a 36dp minimum and the table then scrolls sideways rather
 * than overflowing; columns overlap their cards more as they grow. Lanes below 48dp wide are the one
 * place a target is smaller than the 48dp guideline, a limit of ten columns on a phone; the full-height
 * lane is the target, and the TalkBack actions are the alternative.
 */
@Composable
fun SpiderScreen(
    sessionManager: GameSessionManager,
    game: SpiderGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit,
    /** True on the "Today's deal" route: the deal is the shared one for today's date. */
    daily: Boolean = false
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val sounds = remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.SOLITAIRE, enabled = musicEnabled)
    val reducedMotion = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val colorblind = LocalColorblindMode.current

    val state by game.state
    val selected by game.selected
    val hintMove by game.hintMove
    val event by game.event
    val eventSeq by game.eventSeq
    val dealSeq by game.dealSeq
    val stuck by game.stuck
    val gamesWon by game.gamesWon
    val isDaily by game.isDaily
    val proven by game.provenWinnable
    var dealing by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun newGame() {
        if (dealing) return // a deal (possibly a long winnable search) is already running
        scope.launch {
            dealing = true
            game.newDeal(if (daily) java.time.LocalDate.now().toEpochDay() else null)
            dealing = false
        }
    }
    var drag by remember { mutableStateOf<SpiderDrag?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) game.pause()
            if (e == Lifecycle.Event.ON_RESUME) game.resume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.suits = SpiderOptions.suitsFrom(extras)
        game.winnable = SpiderOptions.winnableFrom(extras)
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) {
            dealing = true
            game.newDeal(if (daily) java.time.LocalDate.now().toEpochDay() else null)
            sounds.playShuffle()
        }
        dealing = false
    }

    // The clock: one tick a second while the screen is up (the engine ignores ticks while paused or won).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            game.tick()
        }
    }

    // Sound and haptic cues, one per engine event.
    LaunchedEffect(eventSeq) {
        when (val e = game.event.value) {
            is SpiderEvent.Moved -> {
                playSfx(SfxKind.SOLID_THUNK)
                haptics(if (e.completedSuit != null) HapticSignal.SUCCESS else HapticSignal.NORMAL_ACTION)
                if (e.completedSuit != null) playSfx(SfxKind.SUCCESS_CHIME)
            }
            is SpiderEvent.Dealt -> {
                sounds.playDraw()
                haptics(HapticSignal.NORMAL_ACTION)
                if (e.completedRuns > 0) playSfx(SfxKind.SUCCESS_CHIME)
            }
            is SpiderEvent.Selected -> {
                playSfx(SfxKind.LIGHT_TICK)
                haptics(HapticSignal.LIGHT_TICK)
            }
            is SpiderEvent.Rejected -> {
                playSfx(SfxKind.INVALID_BUZZ)
                haptics(HapticSignal.FAILURE)
            }
            is SpiderEvent.Won -> {
                playSfx(SfxKind.SUCCESS_CHIME)
                haptics(HapticSignal.CELEBRATION)
            }
            is SpiderEvent.Undone, is SpiderEvent.Deselected -> playSfx(SfxKind.LIGHT_TICK)
            is SpiderEvent.Hinted -> haptics(HapticSignal.LIGHT_TICK)
            is SpiderEvent.NewDeal -> sounds.playShuffle()
            else -> Unit
        }
    }

    val s = state
    val res = androidContext.resources
    val ranks = stringArrayResource(R.array.spd_rank_names)
    val suitNames = stringArrayResource(R.array.spd_suit_names)
    val nameFormat = stringResource(R.string.spd_card_name)
    val namer = remember(ranks.toList(), suitNames.toList(), nameFormat, s?.suits) {
        SpiderCardNamer(ranks, suitNames, nameFormat, s?.suits ?: 1)
    }

    GameChrome(
        helpTitle = stringResource(R.string.spd_help_title),
        helpText = stringResource(R.string.spd_help_text),
        matchInProgress = s != null && !s.won,
        onLeave = game::leaveSession,
        onAbort = { if (gamesWon > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = gamesWon == 0,
        onUndo = { game.undo() },
        canUndo = game.canUndo,
        onHint = { scope.launch { game.requestHint() } },
        buttonFill = SpiderFeltBottom,
        buttonContent = Color.White,
        leaveTitle = stringResource(R.string.spd_leave_title),
        leaveBody = stringResource(R.string.spd_leave_body)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(SpiderFeltTop, SpiderFeltBottom)))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (dealing) R.string.spd_dealing else R.string.spd_loading),
                        color = Color.White, style = MaterialTheme.typography.titleMedium
                    )
                }
                return@Column
            }

            if (dealing) {
                Text(stringResource(R.string.spd_dealing), color = SpiderSelect, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            SpiderStatus(s, gamesWon, isDaily, proven, spiderEventText(event, eventSeq, namer, res, s), eventSeq)

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                SpiderTable(
                    game = game,
                    s = s,
                    selected = selected,
                    hint = hintMove,
                    drag = drag,
                    onDrag = { drag = it },
                    dealSeq = dealSeq,
                    namer = namer,
                    availW = maxWidth.value,
                    availH = maxHeight.value,
                    reducedMotion = reducedMotion,
                    speedScale = gameSpeed.delayScale,
                    colorblind = colorblind
                )
            }

            SpiderControls(
                canUndo = game.canUndo,
                dealsLeft = s.dealsLeft,
                onUndo = { game.undo() },
                onHint = { scope.launch { game.requestHint() } },
                onDeal = { game.dealRow() },
                onNew = ::newGame
            )
        }

        if (s != null && stuck && !s.won) {
            SpiderStuckBanner(onUndo = { game.undo() }, onNew = ::newGame, canUndo = game.canUndo)
        }
        if (s != null && s.won) {
            SpiderWinOverlay(s, reducedMotion, onAgain = ::newGame, onLeave = game::leaveSession)
        }
    }
}

// ---- status -----------------------------------------------------------------------------------------

@Composable
private fun SpiderStatus(s: SpiderState, gamesWon: Int, daily: Boolean, proven: Boolean, line: String, seq: Int) {
    Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.spd_score, s.score), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.spd_moves, s.moves), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.spd_time, spiderClock(s.elapsedSeconds)), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.spd_runs, s.completedRuns, SPIDER_RUNS_TO_WIN),
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall
            )
            if (daily) Badge(stringResource(R.string.spd_badge_daily))
            else if (proven) Badge(stringResource(R.string.spd_badge_winnable))
            if (gamesWon > 0) Text(stringResource(R.string.spd_games_won, gamesWon), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            line + if (seq % 2 == 0) "" else "​",
            color = Color(0xFFFFF3C4),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, color = Color(0xFF083A27), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFE08A)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** The localized sentence for an engine event. */
@Composable
private fun spiderEventText(e: SpiderEvent, seq: Int, namer: SpiderCardNamer, res: android.content.res.Resources, s: SpiderState): String {
    // [seq] is read so the text is recomputed for every event, equal or not.
    @Suppress("UNUSED_EXPRESSION") seq
    return when (e) {
        SpiderEvent.None -> stringResource(R.string.spd_ev_start)
        is SpiderEvent.NewDeal -> stringResource(
            when {
                e.daily -> R.string.spd_ev_new_daily
                e.provenWinnable -> R.string.spd_ev_new_winnable
                else -> R.string.spd_ev_new_deal
            }
        )
        is SpiderEvent.Moved -> {
            var text = res.getQuantityString(R.plurals.spd_ev_moved, e.count, e.count, namer.name(e.card), e.to + 1)
            if (e.flipped) text += " " + stringResource(R.string.spd_ev_flipped)
            if (e.completedSuit != null) text += " " + stringResource(R.string.spd_ev_run_done, stringArrayResource(R.array.spd_suit_names)[e.completedSuit])
            text
        }
        is SpiderEvent.Dealt ->
            if (e.completedRuns > 0) res.getQuantityString(R.plurals.spd_ev_dealt_runs, e.completedRuns, e.completedRuns)
            else stringResource(R.string.spd_ev_dealt)
        is SpiderEvent.Selected -> res.getQuantityString(R.plurals.spd_ev_selected, e.count, e.count, namer.name(e.card), e.col + 1)
        SpiderEvent.Deselected -> stringResource(R.string.spd_ev_deselected)
        is SpiderEvent.Rejected -> stringResource(
            when (e.reason) {
                SpiderReject.FACE_DOWN -> R.string.spd_ev_rej_face_down
                SpiderReject.NOT_A_RUN -> R.string.spd_ev_rej_not_run
                SpiderReject.BAD_TARGET -> R.string.spd_ev_rej_bad_target
                SpiderReject.DEAL_EMPTY_COLUMN -> R.string.spd_ev_rej_deal_empty
                SpiderReject.NO_STOCK -> R.string.spd_ev_rej_no_stock
            }
        )
        SpiderEvent.Undone -> stringResource(R.string.spd_ev_undone)
        is SpiderEvent.Hinted -> when (val h = e.hint) {
            is SpiderHint.Move -> {
                val card = s.columns[h.move.from].getOrNull(h.move.index)
                stringResource(R.string.spd_ev_hint_move, if (card != null) namer.name(card) else "", h.move.from + 1, h.move.to + 1)
            }
            SpiderHint.Deal -> stringResource(R.string.spd_ev_hint_deal)
        }
        SpiderEvent.NoMoves -> stringResource(R.string.spd_ev_no_moves)
        SpiderEvent.Won -> stringResource(R.string.spd_ev_won)
        SpiderEvent.Resumed -> stringResource(R.string.spd_ev_resumed)
    }
}

// ---- table ------------------------------------------------------------------------------------------

@Composable
private fun SpiderTable(
    game: SpiderGame,
    s: SpiderState,
    selected: SpiderSelection?,
    hint: SpiderHint?,
    drag: SpiderDrag?,
    onDrag: (SpiderDrag?) -> Unit,
    dealSeq: Int,
    namer: SpiderCardNamer,
    availW: Float,
    availH: Float,
    reducedMotion: Boolean,
    speedScale: Float,
    colorblind: Boolean
) {
    val base = remember(availW, availH) { SpiderLayout.metrics(availW, availH) }
    val m = remember(base, s.faceDown, s.columns.map { it.size }) {
        val tallest = SpiderLayout.tallestMinimum(s, base.cardH)
        SpiderLayout.metrics(availW, availH, tallest)
    }
    val density = LocalDensity.current.density
    val moveMs = if (reducedMotion) 0 else (260 * speedScale).roundToInt()
    val placements = remember(s, m) { SpiderLayout.placements(s, m) }

    // Deal-in: on a fresh deal the cards leave the stock one by one in dealing order.
    val dealOrder = remember(dealSeq) {
        val order = HashMap<Int, Int>()
        s.columns.forEachIndexed { c, col -> col.forEachIndexed { i, id -> order[id] = i * SPIDER_COLUMNS + c } }
        order
    }
    var landedCount by remember { mutableIntStateOf(if (dealSeq > 0 && !reducedMotion && s.moves == 0) 0 else Int.MAX_VALUE) }
    LaunchedEffect(dealSeq) {
        if (dealSeq == 0 || reducedMotion || s.moves != 0) {
            landedCount = Int.MAX_VALUE
            return@LaunchedEffect
        }
        landedCount = 0
        val per = max(8L, (16 * speedScale).toLong())
        while (landedCount < SPIDER_COLUMNS * 6) {
            delay(per)
            landedCount += 1
        }
        landedCount = Int.MAX_VALUE
    }

    val legalDrops: Set<Int> = if (drag != null) {
        (0 until SPIDER_COLUMNS).filter { SpiderRules.canDrop(s, drag.col, drag.index, it) }.toSet()
    } else emptySet()
    val dragCenterLane = drag?.let {
        val p = placements[s.columns[it.col][it.index]]
        if (p == null) null else SpiderLayout.laneAt(p.x + m.cardW / 2f + it.offset.x, m)
    }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val scrollMod = Modifier
        .then(if (m.scrollsHorizontally) Modifier.horizontalScroll(hScroll) else Modifier)
        .then(if (m.boardH > availH + 0.5f) Modifier.verticalScroll(vScroll) else Modifier)

    Box(modifier = scrollMod) {
        Box(modifier = Modifier.width(m.boardW.dp).height(m.boardH.dp)) {
            // Lane backdrops: the empty slots, also the drop targets for an empty column.
            for (c in 0 until SPIDER_COLUMNS) {
                val empty = s.columns[c].isEmpty()
                val glow = c in legalDrops
                Box(
                    modifier = Modifier
                        .offset(x = SpiderLayout.columnX(c, m).dp, y = 0.dp)
                        .size(m.cardW.dp, m.cardH.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = if (glow) 0.22f else 0.07f))
                        .border(
                            if (glow || (hint is SpiderHint.Move && hint.move.to == c && empty)) 2.dp else 1.dp,
                            if (hint is SpiderHint.Move && hint.move.to == c && empty) SpiderHintRing
                            else Color.White.copy(alpha = if (glow) 0.8f else 0.25f),
                            RoundedCornerShape(5.dp)
                        )
                        .clearAndSetSemantics { }
                )
                if (empty) {
                    Box(
                        modifier = Modifier
                            .offset(x = (c * m.lane).dp, y = 0.dp)
                            .size(m.lane.dp, max(m.cardH * 2f, 90f).dp)
                            .emptyColumnTarget(game, c, stringResource(R.string.spd_empty_column, c + 1))
                    )
                }
            }

            // Cards, keyed by id so each keeps its own animation state across moves, deals and runs.
            for (id in 0 until SPIDER_DECK_SIZE) {
                val p = placements[id] ?: continue
                androidx.compose.runtime.key(id) {
                    SpiderCard(
                        game = game,
                        s = s,
                        id = id,
                        place = p,
                        m = m,
                        selected = selected,
                        hint = hint,
                        drag = drag,
                        onDrag = onDrag,
                        landed = (dealOrder[id] ?: -1) < landedCount,
                        namer = namer,
                        moveMs = moveMs,
                        density = density,
                        reducedMotion = reducedMotion,
                        colorblind = colorblind
                    )
                }
            }

            // The stock: one tap target over the whole stock area.
            val stockLeft = SpiderLayout.stockX(max(s.dealsLeft - 1, 0), m)
            val stockWidth = (m.boardW - stockLeft).coerceAtLeast(m.cardW)
            val stockDesc = if (s.dealsLeft > 0) {
                LocalContext.current.resources.getQuantityString(R.plurals.spd_stock_desc, s.dealsLeft, s.dealsLeft)
            } else stringResource(R.string.spd_stock_empty)
            Box(
                modifier = Modifier
                    .offset(x = stockLeft.dp, y = m.stripY.dp)
                    .size(stockWidth.dp, m.stripH.dp)
                    .zIndex(900f)
                    .then(
                        if (hint is SpiderHint.Deal) Modifier.border(2.dp, SpiderHintRing, RoundedCornerShape(6.dp)) else Modifier
                    )
                    .clickable(enabled = s.dealsLeft > 0, onClickLabel = stringResource(R.string.spd_deal)) { game.dealRow() }
                    .semantics { contentDescription = stockDesc; role = Role.Button }
            )
            // Finished runs: a plain label for the screen reader.
            val doneDesc = stringResource(R.string.spd_runs, s.completedRuns, SPIDER_RUNS_TO_WIN)
            Box(
                modifier = Modifier
                    .offset(x = 0.dp, y = m.stripY.dp)
                    .size((m.cardW * (0.42f * 7 + 1f) + 8f).dp, m.stripH.dp)
                    .semantics { contentDescription = doneDesc }
            )
        }
    }
}

/** Makes an empty column's lane a drop target / tap target with a spoken name. */
private fun Modifier.emptyColumnTarget(game: SpiderGame, col: Int, description: String): Modifier =
    this
        .clickable { game.tapColumn(col) }
        .semantics { contentDescription = description; role = Role.Button }

// ---- one card ---------------------------------------------------------------------------------------

@Composable
private fun SpiderCard(
    game: SpiderGame,
    s: SpiderState,
    id: Int,
    place: SpiderLayout.Placement,
    m: SpiderMetrics,
    selected: SpiderSelection?,
    hint: SpiderHint?,
    drag: SpiderDrag?,
    onDrag: (SpiderDrag?) -> Unit,
    landed: Boolean,
    namer: SpiderCardNamer,
    moveMs: Int,
    density: Float,
    reducedMotion: Boolean,
    colorblind: Boolean
) {
    val inColumn = place.column >= 0
    val col = place.column
    val index = place.index
    val canPick = inColumn && SpiderRules.canPickUp(s, col, index)
    val isSelectedRun = inColumn && selected != null && selected.col == col && index >= selected.index
    val isDragged = inColumn && drag != null && drag.col == col && index >= drag.index
    val hintSource = inColumn && hint is SpiderHint.Move && hint.move.from == col && index >= hint.move.index
    val hintTarget = inColumn && hint is SpiderHint.Move && hint.move.to == col && index == s.columns[col].lastIndex

    // Until it has "landed" in the deal-in, a column card still sits face down on the stock.
    val stockHome = Offset(SpiderLayout.stockX(0, m), SpiderLayout.stripCardY(m))
    val resting = if (inColumn && !landed) stockHome else Offset(place.x, place.y)
    val lift = if (isSelectedRun) -4f else 0f
    val target = if (isDragged) Offset(place.x + drag!!.offset.x, place.y + drag.offset.y) else Offset(resting.x, resting.y + lift)
    val faceUp = place.faceUp && (landed || !inColumn)

    val pos = animateOffsetAsState(
        targetValue = target,
        animationSpec = if (isDragged || moveMs == 0) snap() else tween(moveMs, easing = FastOutSlowInEasing),
        label = "spiderCard$id"
    )
    val flip = animateFloatAsState(
        targetValue = if (faceUp) 1f else 0f,
        animationSpec = if (moveMs == 0) snap() else tween((moveMs * 0.8f).roundToInt(), easing = LinearEasing),
        label = "spiderFlip$id"
    )
    val moving = pos.value != target
    val z = when {
        isDragged -> 3000 + index
        moving -> 2000 + index
        else -> place.z
    }

    val faceShown = flip.value > 0.5f
    var modifier: Modifier = Modifier
        .zIndex(z.toFloat())
        .offset { IntOffset((pos.value.x * density).roundToInt(), (pos.value.y * density).roundToInt()) }
        .size(m.cardW.dp, m.cardH.dp)
        .graphicsLayer { scaleX = max(0.04f, abs(1f - 2f * flip.value) * 0.98f + 0.02f) }

    if (inColumn) {
        val resources = LocalContext.current.resources
        val total = s.columns[col].size
        val desc = when {
            !s.isFaceUp(col, index) -> stringResource(R.string.spd_card_down, col + 1)
            else -> {
                var d = stringResource(R.string.spd_card_up, namer.name(id), col + 1)
                if (canPick && index < total - 1) d += ", " + resources.getQuantityString(R.plurals.spd_run_of, total - index, total - index)
                if (isSelectedRun && index == selected!!.index) d += ", " + stringResource(R.string.spd_selected)
                if (hintSource && index == (hint as SpiderHint.Move).move.index) d += ", " + stringResource(R.string.spd_suggested)
                d
            }
        }
        val moveLabel = stringResource(R.string.spd_action_move_to)
        val actions = if (canPick) {
            (0 until SPIDER_COLUMNS).filter { SpiderRules.canDrop(s, col, index, it) }.map { dest ->
                CustomAccessibilityAction(String.format(moveLabel, dest + 1)) { game.move(col, index, dest); true }
            }
        } else emptyList()
        modifier = modifier
            .clickable(onClickLabel = stringResource(if (selected == null) R.string.spd_action_pick else R.string.spd_action_drop)) {
                game.tapCard(col, index)
            }
            .semantics {
                contentDescription = desc
                role = Role.Button
                customActions = actions
            }
        if (canPick) {
            modifier = modifier.pointerInput(col, index, s.moves, s.stock.size, place.x) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero; onDrag(SpiderDrag(col, index, Offset.Zero)) },
                    onDrag = { change, amount ->
                        change.consume()
                        total += Offset(amount.x / density, amount.y / density)
                        onDrag(SpiderDrag(col, index, total))
                    },
                    onDragEnd = {
                        val start = SpiderLayout.laneAt(place.x + m.cardW / 2f + total.x, m)
                        onDrag(null)
                        if (start != col) game.move(col, index, start)
                    },
                    onDragCancel = { onDrag(null) }
                )
            }
        }
    } else {
        modifier = modifier.clearAndSetSemantics { }
    }

    Box(modifier = modifier) {
        if (faceShown) SpiderFace(id, s.suits, m, colorblind) else SpiderBack(m)
        if (isSelectedRun || hintSource || hintTarget) {
            Box(
                Modifier
                    .size(m.cardW.dp, m.cardH.dp)
                    .border(if (isSelectedRun) 3.dp else 2.dp, if (isSelectedRun) SpiderSelect else SpiderHintRing, RoundedCornerShape(5.dp))
            )
        }
    }
}

@Composable
private fun fixedSp(dp: Float): TextUnit = with(LocalDensity.current) { dp.dp.toSp() }

@Composable
private fun SpiderFace(id: Int, suits: Int, m: SpiderMetrics, colorblind: Boolean) {
    val suit = SpiderRules.suitOf(id, suits)
    val rank = SpiderRules.rankOf(id)
    val ink = spiderSuitColor(suit, colorblind)
    val glyph = spiderSuitGlyph(suit)
    val big = m.cardW >= 46f
    Box(
        Modifier
            .size(m.cardW.dp, m.cardH.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.verticalGradient(listOf(SpiderCardFace, Color(0xFFF1EAD6))))
            .border(1.dp, SpiderCardEdge, RoundedCornerShape(5.dp))
    ) {
        Column(Modifier.padding(start = 2.dp, top = 1.dp)) {
            Text(spiderRankShort[rank - 1], color = ink, fontWeight = FontWeight.ExtraBold, fontSize = fixedSp(m.cardW * if (rank == 10) 0.30f else 0.36f), lineHeight = fixedSp(m.cardW * 0.36f))
            Text(glyph, color = ink, fontSize = fixedSp(m.cardW * 0.34f), lineHeight = fixedSp(m.cardW * 0.32f))
        }
        if (big) {
            Text(
                glyph, color = ink.copy(alpha = 0.16f), fontSize = fixedSp(m.cardW * 0.8f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp)
            )
        }
    }
}

@Composable
private fun SpiderBack(m: SpiderMetrics) {
    Canvas(
        Modifier
            .size(m.cardW.dp, m.cardH.dp)
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, Color(0xFF14163A), RoundedCornerShape(5.dp))
    ) {
        drawRect(Brush.verticalGradient(listOf(SpiderBackTop, SpiderBackBottom)))
        val inset = size.width * 0.1f
        drawRoundRect(
            Color(0xFFE9E3FF).copy(alpha = 0.55f), Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
            CornerRadius(4f, 4f), style = Stroke(width = 1.2f)
        )
        // A lattice of diamonds, then a small web-like rosette in the middle.
        val step = size.width / 3.2f
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Offset(x + size.height, size.height), 1f)
            drawLine(Color.White.copy(alpha = 0.16f), Offset(x + size.height, 0f), Offset(x, size.height), 1f)
            x += step
        }
        val c = Offset(size.width / 2f, size.height / 2f)
        for (k in 0 until 8) {
            rotate(k * 45f, c) {
                drawLine(Color(0xFFFFD98A).copy(alpha = 0.9f), c, Offset(c.x, c.y - size.width * 0.3f), 1.4f)
            }
        }
        drawCircle(Color(0xFFFFD98A).copy(alpha = 0.9f), size.width * 0.12f, c, style = Stroke(1.4f))
    }
}

// ---- controls and overlays --------------------------------------------------------------------------

@Composable
private fun SpiderControls(
    canUndo: Boolean,
    dealsLeft: Int,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onDeal: () -> Unit,
    onNew: () -> Unit
) {
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    // With a large system font four buttons no longer fit on one line: stack them in two rows of two.
    val twoRows = LocalDensity.current.fontScale > 1.3f
    val undo: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onUndo, enabled = canUndo, colors = colors, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.spd_undo), maxLines = 1)
        }
    }
    val hint: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onHint, colors = colors, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.spd_hint), maxLines = 1)
        }
    }
    val deal: @Composable RowScope.() -> Unit = {
        Button(
            onClick = onDeal, enabled = dealsLeft > 0,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC83D), contentColor = Color(0xFF241A08)),
            contentPadding = pad, modifier = Modifier.weight(1.3f).heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.spd_deal_row, dealsLeft), maxLines = 1)
        }
    }
    val newGame: @Composable RowScope.() -> Unit = {
        OutlinedButton(onClick = onNew, colors = colors, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.spd_new), maxLines = 1)
        }
    }
    val rowMod = Modifier.fillMaxWidth().padding(top = 6.dp)
    if (twoRows) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { undo(); hint() }
            Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { deal(); newGame() }
        }
    } else {
        Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { undo(); hint(); deal(); newGame() }
    }
}

@Composable
private fun SpiderStuckBanner(onUndo: () -> Unit, onNew: () -> Unit, canUndo: Boolean) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .padding(bottom = 72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF2A1C06).copy(alpha = 0.94f))
                .border(1.dp, SpiderSelect, RoundedCornerShape(14.dp))
                .padding(14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.spd_stuck_title), color = SpiderSelect, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.spd_stuck_body), color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.spd_undo)) }
                Button(onClick = onNew, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.spd_new)) }
            }
        }
    }
}

@Composable
private fun SpiderWinOverlay(s: SpiderState, reducedMotion: Boolean, onAgain: () -> Unit, onLeave: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center
    ) {
        SpiderConfetti(reducedMotion)
        Column(
            Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFFF6DC))
                .padding(22.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.spd_won_title), style = MaterialTheme.typography.headlineMedium, color = Color(0xFF083A27), fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.spd_won_stats, s.score, s.moves, spiderClock(s.elapsedSeconds)),
                color = Color(0xFF3A2E22), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.spd_play_again)) }
                OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.spd_leave)) }
            }
        }
    }
}

/** A burst of falling confetti; a still sprinkle when reduced motion is on. */
@Composable
private fun SpiderConfetti(reducedMotion: Boolean) {
    val progress: Float = if (reducedMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "spiderConfetti")
        val v by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "t")
        v
    }
    val colors = listOf(Color(0xFFFFC83D), Color(0xFFE5484D), Color(0xFF3DDCFF), Color(0xFF7DDC8B), Color(0xFFFFFFFF))
    Canvas(Modifier.fillMaxSize()) {
        for (i in 0 until 48) {
            val seed = (i * 7919) % 1000 / 1000f
            val x = ((i * 389) % 1000) / 1000f * size.width
            val fall = (progress + seed) % 1f
            val y = fall * (size.height + 40f) - 20f
            rotate(fall * 360f * (1 + seed), Offset(x, y)) {
                drawRect(colors[i % colors.size], Offset(x, y), Size(7f + seed * 6f, 11f + seed * 5f))
            }
        }
    }
}
