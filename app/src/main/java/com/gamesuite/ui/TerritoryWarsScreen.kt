package com.gamesuite.ui

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.territorywars.ActionResult
import com.gamesuite.games.territorywars.CardSymbol
import com.gamesuite.games.territorywars.OwnerPattern
import com.gamesuite.games.territorywars.RejectReason
import com.gamesuite.games.territorywars.TerritoryWarsAction
import com.gamesuite.games.territorywars.TerritoryWarsCards
import com.gamesuite.games.territorywars.TerritoryWarsEvent
import com.gamesuite.games.territorywars.TerritoryWarsEventKind
import com.gamesuite.games.territorywars.TerritoryWarsGame
import com.gamesuite.games.territorywars.TerritoryWarsMaps
import com.gamesuite.games.territorywars.TerritoryWarsOptions
import com.gamesuite.games.territorywars.TerritoryWarsPalette
import com.gamesuite.games.territorywars.TerritoryWarsRules
import com.gamesuite.games.territorywars.TerritoryWarsState
import com.gamesuite.games.territorywars.TurnPhase
import com.gamesuite.games.territorywars.WarMap
import com.gamesuite.games.territorywars.WinReason
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay

private val TwInk = Color(TerritoryWarsPalette.INK)
private val TwRaised = Color(TerritoryWarsPalette.INK_RAISED)
private val TwIvory = Color(TerritoryWarsPalette.IVORY)
private val TwDim = Color(0xFFA9A99B)
private val TwSaffron = Color(TerritoryWarsPalette.SAFFRON)
private val TwAccent = Color(TerritoryWarsPalette.ACCENT_DARK)

private fun seatColor(seat: Int) = Color(TerritoryWarsPalette.colorOf(seat))
private val TwSeatGlyphs = listOf("●", "■", "▲", "◆", "★", "✚")

private fun Context.tw(key: String): String {
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id == 0) key else getString(id)
}

private fun Context.terrName(id: String) = tw("tw_terr_" + id.replace('-', '_'))
private fun Context.regionName(id: String) = tw("tw_region_$id")

private fun whyRes(r: RejectReason): String? = r.whyKey

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerritoryWarsScreen(
    sessionManager: GameSessionManager,
    game: TerritoryWarsGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val ctx = LocalContext.current
    remember { CardSounds.get(ctx) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    rememberAmbientMusic(profile = MusicProfiles.REVERSI, enabled = LocalMusicEnabled.current && CardSounds.soundEnabled)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val speed = LocalGameSpeed.current
    val reduced = LocalReducedMotion.current
    val state by game.state

    val dealtFor = remember { arrayOfNulls<com.gamesuite.core.GameContext>(1) }
    LaunchedEffect(context) {
        val c = context ?: return@LaunchedEffect
        val last = dealtFor[0]
        if (last != null && last.activeMode == c.activeMode && last.players == c.players) return@LaunchedEffect
        dealtFor[0] = c
        game.options = TerritoryWarsOptions.fromExtras(sessionManager.launchOptions.extras)
        game.cpuLevel = com.gamesuite.core.CpuLevel.values().firstOrNull { it.difficulty == sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty) } ?: com.gamesuite.core.CpuLevel.MEDIUM
        game.firstSeat = sessionManager.launchOptions.firstMoverIndex.coerceAtLeast(0)
        game.init(c)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    var selA by remember { mutableIntStateOf(-1) }
    var selB by remember { mutableIntStateOf(-1) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var showList by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var readyKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val s = state

    // announce what the last action did
    val events = game.lastEvents.value
    LaunchedEffect(events) {
        val cur = game.state.value ?: return@LaunchedEffect
        if (events.isEmpty()) return@LaunchedEffect
        val out = events.mapNotNull { twAnnounce(ctx, it, cur) }
        if (out.isNotEmpty()) lines = out.takeLast(4)
        for (e in events) when (e.kind) {
            TerritoryWarsEventKind.BATTLE -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            TerritoryWarsEventKind.CONQUERED -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
            TerritoryWarsEventKind.ELIMINATED -> haptics(HapticSignal.STRONG_ACTION)
            TerritoryWarsEventKind.WAR_WON -> haptics(HapticSignal.CELEBRATION)
            else -> {}
        }
        selA = -1; selB = -1; picked = emptySet()
    }

    val gamePaused = game.paused.value
    LaunchedEffect(s, gamePaused) {
        val cur = s ?: return@LaunchedEffect
        if (gamePaused || !game.botToMove(cur)) return@LaunchedEffect
        delay(speed.scale(com.gamesuite.games.territorywars.ThinkBudgets.forLevel(game.cpuLevel).minVisibleMillis).coerceAtLeast(300L))
        if (!game.playBotStepAsync()) {
            // the bot had nothing to do: advance the phase ourselves so a computer seat never stalls the table
            val skip = game.legalActions().firstOrNull { it is TerritoryWarsAction.EndAttack || it is TerritoryWarsAction.SkipFortify } ?: game.legalActions().firstOrNull()
            if (skip != null) game.apply(skip)
        }
    }

    val helpTitle = stringResource(R.string.tw_title)
    val helpText = stringResource(R.string.tw_tut_01) + " " + stringResource(R.string.tw_tut_03) + " " + stringResource(R.string.tw_tut_04)
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true, onLeave = game::abortMatch, onAbort = { game.abortMatch() },
            leaveKeepsProgress = false, buttonFill = TwInk, buttonContent = TwIvory
        ) { Box(Modifier.fillMaxSize().background(TwInk)) }
        return
    }

    val map = remember(s.mapId) { TerritoryWarsMaps.byId(s.mapId) ?: TerritoryWarsMaps.STANDARD }
    val finished = game.sessionWins.value.values.sum()
    val humans = s.players.count { !it.isBot }
    val me = s.players[s.currentSeat]
    val myTurn = !me.isBot && s.phase != TurnPhase.GAME_OVER
    val needCurtain = humans >= 2 && myTurn && readyKey != (s.round to s.currentSeat) && (s.phase == TurnPhase.REINFORCE || s.phase == TurnPhase.CARDS_FORCED) && s.armiesToPlace > 0
    val legal = if (myTurn) game.legalActions() else emptyList()

    fun go(a: TerritoryWarsAction): Boolean {
        val r = game.apply(a)
        if (r is ActionResult.Rejected) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
            lines = listOf(whyRes(r.reason)?.let { ctx.tw(it) } ?: ctx.tw("tw_why_over"))
            return false
        }
        return true
    }

    fun tap(i: Int) {
        if (!myTurn || needCurtain) return
        when (s.phase) {
            TurnPhase.PLACE_INITIAL, TurnPhase.REINFORCE -> if (s.armiesToPlace > 0) selA = i else { selA = i; selB = -1 }
            TurnPhase.ATTACK -> if (s.owners[i] == s.currentSeat) { selA = i; selB = -1 } else if (selA >= 0) selB = i
            TurnPhase.FORTIFY -> if (selA < 0 || s.owners[i] != s.currentSeat) selA = i.takeIf { s.owners[i] == s.currentSeat } ?: selA else selB = i
            else -> {}
        }
        if (s.phase == TurnPhase.REINFORCE && s.armiesToPlace == 0) {
            if (s.owners[i] == s.currentSeat) { selA = i; selB = -1 } else if (selA >= 0) selB = i
        }
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText, matchInProgress = s.phase != TurnPhase.GAME_OVER,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        buttonFill = TwInk, buttonContent = TwIvory,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finished > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { if (!windowFocused) game.pause() else game.resume() }
        BoxWithConstraints(Modifier.fillMaxSize().background(TwInk)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val strip: @Composable () -> Unit = {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = GameChromeEndInset, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (p in s.players) {
                        val current = p.seat == s.currentSeat && s.phase != TurnPhase.GAME_OVER
                        val pattern = ctx.tw(TerritoryWarsPalette.patternOf(p.seat).labelKey)
                        Row(
                            Modifier.semantics(mergeDescendants = true) { contentDescription = ctx.getString(R.string.tw_a11y_owner_pattern_fmt, p.displayName, pattern) + ", " + ctx.getString(R.string.tw_score_row_fmt, p.displayName, s.territoriesOf(p.seat), s.armiesOf(p.seat)) }
                                .background(if (current) TwRaised else TwInk, RoundedCornerShape(10.dp))
                                .border(if (current) 2.dp else 1.dp, if (current) TwSaffron else Color(0xFF2B3158), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(TwSeatGlyphs[p.seat % 6], color = seatColor(p.seat), fontSize = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(p.displayName, color = if (s.eliminated[p.seat]) TwDim else TwIvory, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("${s.territoriesOf(p.seat)} / ${s.armiesOf(p.seat)}", color = TwDim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val board: @Composable (Modifier) -> Unit = { mod -> TwMapView(s, map, selA, selB, mod, ::tap) }
            val panel: @Composable () -> Unit = {
                if (needCurtain) {
                    Text(ctx.getString(R.string.tw_handover_fmt, me.displayName), color = TwSaffron, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(ctx.getString(R.string.tw_handover_hint), color = TwDim, fontSize = 13.sp)
                    TwButton(ctx.getString(R.string.tw_btn_ready), true, true, Modifier.fillMaxWidth()) { readyKey = s.round to s.currentSeat }
                } else {
                    TwPanel(ctx, s, map, game, legal, lines, myTurn, selA, selB, picked, { picked = it }, showList, { showList = it }, ::go, ::tap, { game.playAgain() }, { game.leaveSession() })
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.3f)) { strip(); board(Modifier.weight(1f).fillMaxWidth()) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) { panel() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    strip()
                    board(Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.heightIn(max = maxHeight * 0.5f).verticalScroll(rememberScrollState()).padding(8.dp)) { panel() }
                }
            }
        }
    }
}

@Composable
private fun TwMapView(s: TerritoryWarsState, map: WarMap, selA: Int, selB: Int, modifier: Modifier, onTap: (Int) -> Unit) {
    val ctx = LocalContext.current
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true } }
    val desc = ctx.getString(R.string.tw_a11y_map_fmt, map.size)
    // the map's bounding box fits the canvas; territories are hit-tested against their polygons
    Canvas(
        modifier.padding(4.dp).semantics { contentDescription = desc }.pointerInput(map, s.mapId) {
            detectTapGestures { pos ->
                val unit = minOf(size.width / map.width, size.height / map.height)
                val ox = (size.width - unit * map.width) / 2f
                val oy = (size.height - unit * map.height) / 2f
                val mx = (pos.x - ox) / unit
                val my = (pos.y - oy) / unit
                var best = -1
                var bestD = Float.MAX_VALUE
                for ((i, t) in map.territories.withIndex()) {
                    if (pointInPolygon(mx, my, t.polygon.map { it.x }, t.polygon.map { it.y })) { best = i; break }
                    val d = (t.centroid.x - mx) * (t.centroid.x - mx) + (t.centroid.y - my) * (t.centroid.y - my)
                    if (d < bestD) { bestD = d; best = i }
                }
                if (best >= 0) onTap(best)
            }
        }
    ) {
        val unit = minOf(size.width / map.width, size.height / map.height)
        val ox = (size.width - unit * map.width) / 2f
        val oy = (size.height - unit * map.height) / 2f
        fun pt(x: Float, y: Float) = Offset(ox + x * unit, oy + y * unit)
        val dash = PathEffect.dashPathEffect(floatArrayOf(unit * 14f, unit * 10f))
        for (l in map.seaLinks) {
            val a = map.territory(l.a)!!.centroid; val b = map.territory(l.b)!!.centroid
            drawLine(TwDim, pt(a.x, a.y), pt(b.x, b.y), strokeWidth = unit * 5f, pathEffect = dash)
        }
        paint.textSize = unit * 34f
        for ((i, t) in map.territories.withIndex()) {
            val owner = s.owners[i]
            val path = Path().apply {
                t.polygon.forEachIndexed { k, p -> val o = pt(p.x, p.y); if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                close()
            }
            val base = if (owner >= 0) seatColor(owner) else Color(0xFF3A3F55)
            drawPath(path, base.copy(alpha = if (i == selA || i == selB) 1f else 0.78f))
            if (owner >= 0) {
                clipPath(path) {
                    val pat = TerritoryWarsPalette.patternOf(owner)
                    val c0 = pt(t.centroid.x, t.centroid.y)
                    val span = unit * 160f
                    val ink = Color(0x55000000)
                    when (pat) {
                        OwnerPattern.STRIPES -> for (k in -6..6) drawLine(ink, Offset(c0.x + k * unit * 24f - span, c0.y - span), Offset(c0.x + k * unit * 24f + span, c0.y + span), unit * 6f)
                        OwnerPattern.DOTS -> for (a in -4..4) for (b in -4..4) drawCircle(ink, unit * 5f, Offset(c0.x + a * unit * 26f, c0.y + b * unit * 26f))
                        OwnerPattern.CROSSHATCH -> for (k in -6..6) { drawLine(ink, Offset(c0.x + k * unit * 24f - span, c0.y - span), Offset(c0.x + k * unit * 24f + span, c0.y + span), unit * 4f); drawLine(ink, Offset(c0.x + k * unit * 24f + span, c0.y - span), Offset(c0.x + k * unit * 24f - span, c0.y + span), unit * 4f) }
                        OwnerPattern.BARS -> for (k in -6..6) drawLine(ink, Offset(c0.x - span, c0.y + k * unit * 24f), Offset(c0.x + span, c0.y + k * unit * 24f), unit * 8f)
                        OwnerPattern.CHECKS -> for (a in -4..4) for (b in -4..4) if ((a + b) % 2 == 0) drawRect(ink, Offset(c0.x + a * unit * 26f, c0.y + b * unit * 26f), androidx.compose.ui.geometry.Size(unit * 24f, unit * 24f))
                        OwnerPattern.SOLID -> {}
                    }
                }
            }
            drawPath(path, if (i == selA) TwSaffron else if (i == selB) Color.White else Color(0xFF0B0D1A), style = Stroke(width = if (i == selA || i == selB) unit * 8f else unit * 3f))
            val c = pt(t.centroid.x, t.centroid.y)
            drawCircle(Color(0xFF0B0D1A), unit * 26f, c)
            paint.color = 0xFFF2EEDF.toInt()
            drawIntoCanvas { it.nativeCanvas.drawText("${s.armies[i]}", c.x, c.y + unit * 12f, paint) }
        }
    }
}

private fun pointInPolygon(px: Float, py: Float, xs: List<Float>, ys: List<Float>): Boolean {
    var inside = false
    var j = xs.size - 1
    for (i in xs.indices) {
        if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
        j = i
    }
    return inside
}

@Composable
private fun TwButton(text: String, enabled: Boolean, primary: Boolean = false, modifier: Modifier = Modifier, minH: Dp = 48.dp, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = minH),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) TwSaffron else TwRaised, contentColor = if (primary) TwInk else TwIvory,
            disabledContainerColor = Color(0xFF10131F), disabledContentColor = TwDim
        ),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

private fun symbolName(ctx: Context, c: com.gamesuite.games.territorywars.WarCard): String = when (c.symbol) {
    CardSymbol.TORCH -> ctx.getString(R.string.tw_card_torch)
    CardSymbol.COMPASS -> ctx.getString(R.string.tw_card_compass)
    CardSymbol.ANCHOR -> ctx.getString(R.string.tw_card_anchor)
    CardSymbol.WILD -> ctx.getString(R.string.tw_card_wild)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwPanel(
    ctx: Context, s: TerritoryWarsState, map: WarMap, game: TerritoryWarsGame, legal: List<TerritoryWarsAction>, lines: List<String>, myTurn: Boolean,
    selA: Int, selB: Int, picked: Set<String>, setPicked: (Set<String>) -> Unit, showList: Boolean, setShowList: (Boolean) -> Unit,
    go: (TerritoryWarsAction) -> Boolean, tap: (Int) -> Unit, onAgain: () -> Unit, onLeave: () -> Unit
) {
    val me = s.players[s.currentSeat]
    val phaseTitle = when (s.phase) {
        TurnPhase.DEAL -> R.string.tw_phase_deal
        TurnPhase.PLACE_INITIAL -> R.string.tw_phase_place
        TurnPhase.CARDS_FORCED -> R.string.tw_phase_cards
        TurnPhase.REINFORCE -> if (s.armiesToPlace > 0) R.string.tw_phase_reinforce else R.string.tw_phase_attack
        TurnPhase.ATTACK -> R.string.tw_phase_attack
        TurnPhase.MOVE_IN -> R.string.tw_phase_move_in
        TurnPhase.FORTIFY -> R.string.tw_phase_fortify
        TurnPhase.GAME_OVER -> R.string.tw_phase_gameover
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (myTurn) ctx.getString(R.string.tw_turn_you) else ctx.getString(R.string.tw_turn_other_fmt, me.displayName)) + " - " +
                (if (s.config.roundCap > 0) ctx.getString(R.string.tw_round_cap_fmt, s.round, s.config.roundCap) else ctx.getString(R.string.tw_round_nocap_fmt, s.round)),
            color = seatColor(s.currentSeat), fontWeight = FontWeight.Bold, fontSize = 15.sp
        )
        for (l in lines) Text(l, color = TwDim, fontSize = 13.sp)
        Text(ctx.getString(phaseTitle), color = TwIvory, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.padding(top = 6.dp))
    if (s.phase == TurnPhase.GAME_OVER) { TwPostGame(ctx, s, onAgain, onLeave); return }
    val on = myTurn
    fun name(i: Int) = ctx.terrName(map.territories[i].id)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val hand = s.hands[s.currentSeat]
        if (on && hand.isNotEmpty() && (s.phase == TurnPhase.CARDS_FORCED || s.phase == TurnPhase.REINFORCE)) {
            Text(ctx.getString(R.string.tw_cards_title) + " (" + ctx.getString(R.string.tw_cards_count_fmt, hand.size) + ")", color = TwDim, fontSize = 13.sp)
            if (s.phase == TurnPhase.CARDS_FORCED) Text(ctx.getString(R.string.tw_cards_forced), color = TwSaffron, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in hand) {
                    val chosen = c.id in picked
                    TwButton((if (chosen) "✓ " else "") + symbolName(ctx, c) + (c.territoryId?.let { " - " + ctx.terrName(it) } ?: ""), true, chosen) {
                        setPicked(if (chosen) picked - c.id else if (picked.size < 3) picked + c.id else picked)
                    }
                }
            }
            val isSet = picked.size == 3 && TerritoryWarsCards.isSet(hand.filter { it.id in picked })
            Text(ctx.getString(R.string.tw_cards_set_value_fmt, TerritoryWarsRules.setValue(s.setsTurnedIn)), color = TwDim, fontSize = 13.sp)
            TwButton(ctx.getString(R.string.tw_btn_turn_in), isSet, true, Modifier.fillMaxWidth()) { if (go(TerritoryWarsAction.TurnInCards(picked.toList()))) setPicked(emptySet()) }
        }
        when {
            s.phase == TurnPhase.CARDS_FORCED -> {}
            (s.phase == TurnPhase.PLACE_INITIAL || s.phase == TurnPhase.REINFORCE) && s.armiesToPlace > 0 -> {
                Text(ctx.getString(R.string.tw_armies_to_place_fmt, s.armiesToPlace), color = TwSaffron, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (selA >= 0) name(selA) else ctx.getString(R.string.tw_prompt_place), color = TwIvory, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(1, 3, s.armiesToPlace).distinct().filter { it in 1..s.armiesToPlace }) {
                        TwButton("+$n", on && selA >= 0, n == s.armiesToPlace) { go(TerritoryWarsAction.PlaceArmies(selA, n)) }
                    }
                }
            }
            s.phase == TurnPhase.MOVE_IN -> {
                val m = s.pendingMoveIn
                if (m != null) {
                    Text(ctx.getString(R.string.tw_prompt_move_in_fmt, name(m.to), m.minMove), color = TwIvory, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in listOf(m.minMove, (m.minMove + m.maxMove) / 2, m.maxMove).distinct()) TwButton("$n", on, n == m.maxMove) { go(TerritoryWarsAction.MoveIn(n)) }
                    }
                }
            }
            s.phase == TurnPhase.FORTIFY -> {
                Text(if (selA < 0) ctx.getString(R.string.tw_prompt_fortify_from) else if (selB < 0) ctx.getString(R.string.tw_prompt_fortify_to) else ctx.getString(R.string.tw_prompt_fortify_amount_fmt, s.armies[selA] - 1), color = TwIvory, fontSize = 14.sp)
                if (selA >= 0 && selB >= 0 && s.armies[selA] >= 2) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in listOf(1, (s.armies[selA] - 1) / 2, s.armies[selA] - 1).distinct().filter { it >= 1 }) TwButton(ctx.getString(R.string.tw_prompt_fortify_amount_fmt, n), on, n == s.armies[selA] - 1) { go(TerritoryWarsAction.Fortify(selA, selB, n)) }
                    }
                }
                TwButton(ctx.getString(R.string.tw_btn_skip_fortify), on, false, Modifier.fillMaxWidth()) { go(TerritoryWarsAction.SkipFortify) }
            }
            else -> {
                Text(if (selA < 0) ctx.getString(R.string.tw_prompt_attack_from) else if (selB < 0) ctx.getString(R.string.tw_prompt_attack_to) else name(selA) + " → " + name(selB), color = TwIvory, fontSize = 14.sp)
                if (selA >= 0 && selB >= 0 && s.owners[selA] == s.currentSeat && s.owners[selB] != s.currentSeat && s.armies[selA] >= 2) {
                    val maxDice = minOf(TerritoryWarsRules.MAX_ATTACK_DICE, s.armies[selA] - 1)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in 1..maxDice) TwButton(if (d == 1) ctx.getString(R.string.tw_btn_attack_one) else ctx.getString(R.string.tw_btn_attack_dice_fmt, d), on, d == maxDice) { go(TerritoryWarsAction.Attack(selA, selB, d)) }
                        TwButton(ctx.getString(R.string.tw_btn_keep_attacking), on) { go(TerritoryWarsAction.AttackUntilDone(selA, selB)) }
                    }
                }
                TwButton(ctx.getString(R.string.tw_btn_end_attack), on, false, Modifier.fillMaxWidth()) { go(TerritoryWarsAction.EndAttack) }
            }
        }
    }
    Spacer(Modifier.padding(top = 6.dp))
    TwButton(ctx.getString(R.string.tw_btn_territory_list), true, false, Modifier.fillMaxWidth()) { setShowList(!showList) }
    if (showList) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((i, t) in map.territories.withIndex()) {
                val owner = s.players[s.owners[i]].displayName
                val desc = ctx.getString(R.string.tw_a11y_territory_fmt, ctx.terrName(t.id), ctx.regionName(t.regionId), owner, s.armies[i]) + if (i == selA || i == selB) ", " + ctx.getString(R.string.tw_a11y_selected) else ""
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = desc }
                        .background(if (i == selA || i == selB) TwRaised else TwInk, RoundedCornerShape(8.dp))
                        .clickable { tap(i) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(TwSeatGlyphs[s.owners[i] % 6] + " " + ctx.terrName(t.id), color = seatColor(s.owners[i]), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${s.armies[i]}", color = TwIvory, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TwPostGame(ctx: Context, s: TerritoryWarsState, onAgain: () -> Unit, onLeave: () -> Unit) {
    val winners = s.winnerSeats.map { s.players[it].displayName }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ctx.getString(R.string.tw_win_title_fmt, winners.joinToString(", ")), color = TwSaffron, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            when (s.winReason) {
                WinReason.DOMINATION -> ctx.getString(R.string.tw_win_domination)
                WinReason.CAPTURE_TARGET -> ctx.getString(R.string.tw_win_target)
                WinReason.STALEMATE -> ctx.getString(R.string.tw_event_stalemate)
                else -> ctx.getString(R.string.tw_win_round_cap)
            }, color = TwIvory, fontSize = 15.sp
        )
        Text(ctx.getString(R.string.tw_score_title), color = TwDim, fontSize = 13.sp)
        for (p in s.players) Text(ctx.getString(R.string.tw_score_row_fmt, p.displayName, s.territoriesOf(p.seat), s.armiesOf(p.seat)), color = seatColor(p.seat), fontSize = 14.sp)
        Text(ctx.getString(R.string.tw_score_rounds_fmt, s.round), color = TwDim, fontSize = 13.sp)
        Text(ctx.getString(R.string.tw_score_battles_fmt, s.battles), color = TwDim, fontSize = 13.sp)
        Text(ctx.getString(R.string.tw_score_conquests_fmt, s.conquests), color = TwDim, fontSize = 13.sp)
        TwButton(ctx.getString(R.string.tw_btn_play_again), true, true, Modifier.fillMaxWidth()) { onAgain() }
        TwButton(ctx.getString(R.string.tw_btn_leave), true, false, Modifier.fillMaxWidth()) { onLeave() }
    }
}

private fun twAnnounce(ctx: Context, e: TerritoryWarsEvent, s: TerritoryWarsState): String? {
    val map = TerritoryWarsMaps.byId(s.mapId) ?: TerritoryWarsMaps.STANDARD
    fun who(i: Int) = s.players.getOrNull(i)?.displayName ?: ""
    fun terr(i: Int) = map.territories.getOrNull(i)?.let { ctx.terrName(it.id) } ?: ""
    return when (e.kind) {
        TerritoryWarsEventKind.BATTLE -> ctx.getString(R.string.tw_battle_result_fmt, e.amount, e.extra)
        TerritoryWarsEventKind.CONQUERED -> ctx.getString(R.string.tw_battle_conquered_fmt, who(e.seat), terr(e.to))
        TerritoryWarsEventKind.ELIMINATED -> ctx.getString(R.string.tw_event_eliminated_fmt, who(e.amount))
        TerritoryWarsEventKind.TURN_STARTED -> ctx.getString(R.string.tw_event_reinforce_fmt, who(e.seat), e.amount)
        TerritoryWarsEventKind.CARD_DRAWN -> null
        TerritoryWarsEventKind.WAR_WON -> when (s.winReason) {
            WinReason.CAPTURE_TARGET -> ctx.getString(R.string.tw_event_target_fmt, who(e.seat))
            WinReason.ROUND_CAP -> ctx.getString(R.string.tw_event_round_cap)
            WinReason.STALEMATE -> ctx.getString(R.string.tw_event_stalemate)
            else -> ctx.getString(R.string.tw_win_title_fmt, who(e.seat))
        }
        else -> null
    }
}
