package com.gamesuite.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.gamesuite.games.propertytycoon.ColorGroup
import com.gamesuite.games.propertytycoon.DebtReason
import com.gamesuite.games.propertytycoon.Phase
import com.gamesuite.games.propertytycoon.PropertyTycoonGame
import com.gamesuite.games.propertytycoon.PropertyTycoonLaunch
import com.gamesuite.games.propertytycoon.PtBoard
import com.gamesuite.games.propertytycoon.PtCards
import com.gamesuite.games.propertytycoon.PtCommand
import com.gamesuite.games.propertytycoon.PtEvent
import com.gamesuite.games.propertytycoon.PtReject
import com.gamesuite.games.propertytycoon.PtRules
import com.gamesuite.games.propertytycoon.TileType
import com.gamesuite.games.propertytycoon.TycoonState
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val PtInk = Color(0xFF0C1814)
private val PtSurface = Color(0xFF14261F)
private val PtRaised = Color(0xFF1E3A30)
private val PtText = Color(0xFFEAF5EF)
private val PtDim = Color(0xFF9FB8AC)
private val PtAccent = Color(0xFF4FD6A0)
private val PtWarn = Color(0xFFFF8A65)
private val PtSeatColors = listOf(Color(0xFFFFC857), Color(0xFFFF7A59), Color(0xFF6BCB9A), Color(0xFF6FB6FF), Color(0xFFC7A6FF), Color(0xFFFF8FB1))
private val PtSeatGlyphs = listOf("●", "■", "▲", "◆", "★", "✚")

private fun groupColor(g: ColorGroup): Color = when (g) {
    ColorGroup.CLAY -> Color(0xFFB5651D)
    ColorGroup.MIST -> Color(0xFF9DB4C0)
    ColorGroup.REED -> Color(0xFFA7C957)
    ColorGroup.LAGOON -> Color(0xFF2EC4B6)
    ColorGroup.PLUM -> Color(0xFF8E5AA8)
    ColorGroup.CORAL -> Color(0xFFFF6F61)
    ColorGroup.GOLD -> Color(0xFFE9B949)
    ColorGroup.OPAL -> Color(0xFF6C8EF5)
}

private fun groupGlyph(g: ColorGroup): String = when (g) {
    ColorGroup.CLAY -> "▲"
    ColorGroup.MIST -> "●"
    ColorGroup.REED -> "◆"
    ColorGroup.LAGOON -> "■"
    ColorGroup.PLUM -> "★"
    ColorGroup.CORAL -> "✚"
    ColorGroup.GOLD -> "⬢"
    ColorGroup.OPAL -> "▼"
}

private fun typeGlyph(t: TileType): String = when (t) {
    TileType.START -> "⚑"
    TileType.TRANSIT -> "≈"
    TileType.UTILITY -> "⚙"
    TileType.TAX -> "◈"
    TileType.TIDE_CARD -> "?"
    TileType.GUILD_CARD -> "⚖"
    TileType.TIMEOUT -> "⚓"
    TileType.FREE_ANCHORAGE -> "✦"
    TileType.GO_TO_TIMEOUT -> "➤"
    TileType.PROPERTY -> ""
}

/** Ring cell (column, row) of tile [i] on the 11 x 11 grid, clockwise from the bottom-right corner. */
private fun cellOf(i: Int): Pair<Int, Int> = when (i) {
    in 0..10 -> (10 - i) to 10
    in 11..19 -> 0 to (20 - i)
    in 20..30 -> (i - 20) to 0
    else -> 10 to (i - 30)
}

private fun Context.pt(key: String): String {
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id == 0) key else getString(id)
}

private fun Context.tileName(i: Int) = pt("pt_tile_%02d".format(i))

private fun Context.cardText(id: String): String {
    val n = id.drop(1)
    return pt((if (id.startsWith("t")) "pt_card_tide_" else "pt_card_guild_") + n)
}

private fun Context.groupName(g: ColorGroup) = pt("pt_group_" + g.name.lowercase())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertyTycoonScreen(
    sessionManager: GameSessionManager,
    game: PropertyTycoonGame,
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
        PropertyTycoonLaunch.apply(game, sessionManager.launchOptions.extras, c.players.size)
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.firstSeat = sessionManager.launchOptions.firstMoverIndex.takeIf { it >= 0 }
        game.init(c)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    val shownPos = remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var handled by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTile by remember { mutableIntStateOf(-1) }
    val serial = game.changeSerial.value

    suspend fun awaitUnpaused() { if (game.paused.value) snapshotFlow { game.paused.value }.first { !it } }

    LaunchedEffect(serial) {
        val cur = game.state.value ?: return@LaunchedEffect
        if (handled < 0) {
            shownPos.value = cur.players.associate { it.seat to it.position }
            handled = serial
            return@LaunchedEffect
        }
        busy = true
        try {
            val names = cur.players.map { it.displayName }
            val out = ArrayList<String>()
            for (ev in game.lastEvents.value) {
                awaitUnpaused()
                if (ev is PtEvent.Moved) {
                    val steps = Math.floorMod(ev.to - ev.from, PtRules.TILE_COUNT)
                    if (!reduced && steps in 1..14 && !(ev.to == PtRules.TIMEOUT_INDEX && ev.from > ev.to && steps > 14)) {
                        for (k in 1..steps) {
                            shownPos.value = shownPos.value + (ev.seat to (ev.from + k) % PtRules.TILE_COUNT)
                            playSfx(SfxKind.LIGHT_TICK)
                            delay(speed.scale(90L))
                        }
                    }
                    shownPos.value = shownPos.value + (ev.seat to ev.to)
                } else {
                    ptAnnounce(ctx, ev, names)?.let { out += it }
                    when (ev) {
                        is PtEvent.Rolled -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.NORMAL_ACTION); if (!reduced) delay(speed.scale(300L)) }
                        is PtEvent.Bought, is PtEvent.Built -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
                        is PtEvent.RentPaid, is PtEvent.TaxPaid, is PtEvent.DebtOpened -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                        is PtEvent.StipendPaid -> playSfx(SfxKind.SUCCESS_CHIME)
                        is PtEvent.Winner -> haptics(HapticSignal.CELEBRATION)
                        else -> {}
                    }
                }
            }
            if (out.isNotEmpty()) lines = out.takeLast(4)
        } finally {
            shownPos.value = cur.players.associate { it.seat to it.position }
            handled = serial
            busy = false
        }
    }

    val gamePaused = game.paused.value
    LaunchedEffect(state, busy, handled, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (busy || handled != game.changeSerial.value || gamePaused || !game.botToAct(cur)) return@LaunchedEffect
        delay(speed.scale(650L).coerceAtLeast(400L))
        game.playBotStep()
    }

    val helpTitle = stringResource(R.string.pt_title)
    val helpText = stringResource(R.string.pt_tut_01) + " " + stringResource(R.string.pt_tut_03) + " " + stringResource(R.string.pt_tut_04)
    val s = state
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true, onLeave = game::abortMatch, onAbort = { game.abortMatch() },
            leaveKeepsProgress = false, buttonFill = PtInk, buttonContent = PtText
        ) { Box(Modifier.fillMaxSize().background(PtInk)) }
        return
    }

    val finished = game.sessionWins.value.values.sum()
    val locked = busy || handled != serial
    val actor = game.seatToAct(s)
    val myTurn = game.isHumanToAct(s) && !locked
    val legal = if (myTurn) game.legalCommands(actor) else emptyList()

    fun go(c: PtCommand) {
        val why = game.explain(actor, c)
        if (!game.command(actor, c)) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
            lines = listOf(ctx.getString(whyRes(why)))
        }
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText, matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        buttonFill = PtInk, buttonContent = PtText,
        leaveTitle = stringResource(R.string.ccl_leave_title),
        leaveBody = stringResource(if (finished > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { if (!windowFocused) game.pause() else game.resume() }
        BoxWithConstraints(Modifier.fillMaxSize().background(PtInk)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val strip: @Composable () -> Unit = {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = GameChromeEndInset, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (p in s.players) {
                        val current = p.seat == s.currentSeat && !s.over
                        Row(
                            Modifier.semantics(mergeDescendants = true) { contentDescription = p.displayName + ", " + ctx.getString(R.string.pt_pearls, p.cash) }
                                .background(if (current) PtRaised else PtSurface, RoundedCornerShape(10.dp))
                                .border(if (current) 2.dp else 1.dp, if (current) PtAccent else Color(0xFF28463A), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(PtSeatGlyphs[p.seat % 6], color = PtSeatColors[p.seat % 6], fontSize = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(p.displayName, color = if (p.bankrupt) PtDim else PtText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(if (p.bankrupt) ctx.getString(R.string.pt_score_bankrupt_round, p.bankruptRound) else ctx.getString(R.string.pt_pearls, p.cash), color = PtDim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val board: @Composable (Modifier) -> Unit = { mod ->
                Box(mod, contentAlignment = Alignment.Center) {
                    PtBoardView(s, shownPos.value, selectedTile, Modifier.aspectRatio(1f)) { selectedTile = if (selectedTile == it) -1 else it }
                }
            }
            val panel: @Composable () -> Unit = {
                PtPanel(ctx, s, game, legal, lines, actor, myTurn, locked, selectedTile, ::go, { game.playAgain() }, { game.leaveSession() })
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.1f)) { strip(); board(Modifier.weight(1f).fillMaxWidth()) }
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

private fun whyRes(r: PtReject): Int = when (r) {
    PtReject.NOT_YOUR_TURN -> R.string.pt_why_not_your_turn
    PtReject.CANNOT_AFFORD -> R.string.pt_why_cannot_afford
    PtReject.SET_INCOMPLETE -> R.string.pt_why_set_incomplete
    PtReject.UNEVEN_BUILD -> R.string.pt_why_even_build
    PtReject.UNEVEN_SELL -> R.string.pt_why_even_sell
    PtReject.SUPPLY_EMPTY -> R.string.pt_why_supply_empty
    PtReject.HAS_BUILDINGS -> R.string.pt_why_has_buildings
    PtReject.PLEDGED -> R.string.pt_why_pledged
    PtReject.IN_DEBT -> R.string.pt_why_in_debt
    PtReject.TRADE_INVALID, PtReject.FEATURE_DISABLED -> R.string.pt_why_trade_invalid
    else -> R.string.pt_why_need_roll
}

@Composable
private fun PtBoardView(s: TycoonState, shown: Map<Int, Int>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val ctx = LocalContext.current
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER } }
    val desc = remember(shown, s.players) {
        ctx.getString(R.string.pt_a11y_ring) + ". " + s.players.joinToString(". ") { p ->
            ctx.getString(R.string.pt_a11y_token, p.displayName, ctx.tileName(shown[p.seat] ?: p.position))
        }
    }
    Canvas(
        modifier.semantics { contentDescription = desc }.pointerInput(Unit) {
            detectTapGestures { pos ->
                val cell = minOf(size.width, size.height) / 11f
                val col = (pos.x / cell).toInt().coerceIn(0, 10)
                val row = (pos.y / cell).toInt().coerceIn(0, 10)
                for (i in 0 until PtRules.TILE_COUNT) if (cellOf(i) == col to row) onSelect(i)
            }
        }
    ) {
        val cell = size.minDimension / 11f
        for (i in 0 until PtRules.TILE_COUNT) {
            val (c, r) = cellOf(i)
            val tl = Offset(c * cell, r * cell)
            val t = PtBoard.tile(i)
            val h = s.holding(i)
            val base = t.group?.let { groupColor(it) } ?: Color(0xFF28463A)
            drawRect(Color(0xFF14261F), tl, Size(cell, cell))
            drawRect(base, tl, Size(cell, cell * 0.28f).let { if (t.group != null) it else Size(cell, cell) }, alpha = if (t.group != null) 1f else 0.55f)
            drawRect(if (i == selected) PtAccent else Color(0xFF0C1814), tl, Size(cell, cell), style = Stroke(if (i == selected) cell * 0.08f else cell * 0.03f))
            paint.textSize = cell * 0.42f
            paint.color = 0xFFEAF5EF.toInt()
            val glyph = t.group?.let { groupGlyph(it) } ?: typeGlyph(t.type)
            drawIntoCanvas { it.nativeCanvas.drawText(glyph, tl.x + cell / 2, tl.y + cell * 0.72f, paint) }
            if (h != null) {
                drawRect(PtSeatColors[h.owner % 6], Offset(tl.x, tl.y + cell * 0.88f), Size(cell, cell * 0.12f))
                if (h.buildings > 0) {
                    val dots = if (h.buildings == 5) 1 else h.buildings
                    for (k in 0 until dots) {
                        val rad = if (h.buildings == 5) cell * 0.12f else cell * 0.06f
                        drawCircle(if (h.buildings == 5) Color(0xFFFF6F61) else Color.White, rad, Offset(tl.x + cell * (0.2f + 0.2f * k), tl.y + cell * 0.38f))
                    }
                }
            }
        }
        for (p in s.players) {
            if (p.bankrupt) continue
            val (c, r) = cellOf(shown[p.seat] ?: p.position)
            val ctr = Offset(c * cell + cell * (0.25f + 0.25f * (p.seat % 3)), r * cell + cell * (0.55f + 0.2f * (p.seat / 3)))
            drawCircle(Color(0xFF0C1814), cell * 0.17f, ctr)
            drawCircle(PtSeatColors[p.seat % 6], cell * 0.13f, ctr)
            paint.textSize = cell * 0.2f
            paint.color = 0xFF0C1814.toInt()
            drawIntoCanvas { it.nativeCanvas.drawText("${p.seat + 1}", ctr.x, ctr.y + cell * 0.07f, paint) }
        }
    }
}

@Composable
private fun PtButton(text: String, enabled: Boolean, primary: Boolean = false, minH: Dp = 48.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = minH),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) PtAccent else PtRaised, contentColor = if (primary) PtInk else PtText,
            disabledContainerColor = PtSurface, disabledContentColor = PtDim
        ),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = if (primary) 17.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PtPanel(
    ctx: Context, s: TycoonState, game: PropertyTycoonGame, legal: List<PtCommand>, lines: List<String>, actor: Int, myTurn: Boolean,
    locked: Boolean, selected: Int, go: (PtCommand) -> Unit, onAgain: () -> Unit, onLeave: () -> Unit
) {
    val p = s.players[actor]
    val on = myTurn
    fun has(c: PtCommand) = c in legal
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!s.over) {
            Text(ctx.getString(R.string.pt_turn_of, p.displayName) + " - " + ctx.getString(R.string.pt_round_of, s.round, s.config.length.roundCap), color = PtAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        for (l in lines) Text(l, color = PtDim, fontSize = 13.sp)
        if (s.dice1 > 0) Text(ctx.getString(R.string.pt_a11y_dice, s.dice1, s.dice2), color = PtText, fontSize = 14.sp)
    }
    Spacer(Modifier.padding(top = 6.dp))
    if (s.over) { PtPostGame(ctx, s, game, onAgain, onLeave); return }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (s.phase) {
            Phase.AWAIT_ROLL, Phase.AWAIT_TIMEOUT_CHOICE -> {
                if (s.phase == Phase.AWAIT_TIMEOUT_CHOICE) {
                    PtButton(ctx.getString(R.string.pt_btn_pay_fine, PtRules.TIMEOUT_FINE), on && has(PtCommand.PayFine), modifier = Modifier.fillMaxWidth()) { go(PtCommand.PayFine) }
                    if (p.passCardIds.isNotEmpty()) PtButton(ctx.getString(R.string.pt_btn_use_card), on && has(PtCommand.UseFreePassCard), modifier = Modifier.fillMaxWidth()) { go(PtCommand.UseFreePassCard) }
                }
                PtButton(ctx.getString(R.string.pt_btn_roll), on, primary = true, minH = 60.dp, modifier = Modifier.fillMaxWidth()) { go(PtCommand.Roll) }
            }
            Phase.AWAIT_BUY -> {
                val t = PtBoard.tile(s.landedTile)
                PtTileCard(ctx, s, s.landedTile)
                PtButton(ctx.getString(R.string.pt_btn_buy, t.price), on && has(PtCommand.Buy), primary = true, modifier = Modifier.fillMaxWidth()) { go(PtCommand.Buy) }
                PtButton(ctx.getString(R.string.pt_btn_decline), on, modifier = Modifier.fillMaxWidth()) { go(PtCommand.DeclineBuy) }
            }
            Phase.AWAIT_CARD_ACK -> {
                val id = s.pendingCardId ?: ""
                if (id.isNotEmpty() && id in PtCards.byId) {
                    Column(Modifier.fillMaxWidth().background(PtRaised, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text(ctx.cardText(id), color = PtText, fontSize = 17.sp)
                    }
                }
                PtButton(ctx.getString(R.string.pt_btn_card_ok), on, primary = true, modifier = Modifier.fillMaxWidth()) { go(PtCommand.AckCard) }
            }
            Phase.AWAIT_POST_ROLL -> {
                if (s.landedTile >= 0) PtTileCard(ctx, s, s.landedTile)
                PtButton(ctx.getString(R.string.pt_btn_end_turn), on, primary = true, modifier = Modifier.fillMaxWidth()) { go(PtCommand.EndTurn) }
            }
            Phase.AWAIT_DEBT -> {
                val d = s.debt
                if (d != null) {
                    Text(ctx.getString(R.string.pt_debt_title, d.amount), color = PtWarn, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(ctx.getString(R.string.pt_debt_hint, p.cash), color = PtDim, fontSize = 13.sp)
                    Text(
                        if (d.creditor >= 0 && d.reason != DebtReason.RIVALS) ctx.getString(R.string.pt_debt_creditor, s.players[d.creditor].displayName) else ctx.getString(R.string.pt_debt_bank),
                        color = PtDim, fontSize = 13.sp
                    )
                }
                for (c in legal.filterIsInstance<PtCommand.SellBuilding>()) {
                    PtButton(ctx.getString(R.string.pt_btn_sell_building) + " - " + ctx.tileName(c.tile), on, modifier = Modifier.fillMaxWidth()) { go(c) }
                }
                PtButton(ctx.getString(R.string.pt_btn_declare_bankrupt), on, modifier = Modifier.fillMaxWidth()) { go(PtCommand.DeclareBankruptcy) }
            }
            else -> {}
        }
        // building management while the player is free to act
        if (s.phase == Phase.AWAIT_ROLL || s.phase == Phase.AWAIT_POST_ROLL || s.phase == Phase.AWAIT_TIMEOUT_CHOICE) {
            val builds = legal.filterIsInstance<PtCommand.Build>()
            val sells = legal.filterIsInstance<PtCommand.SellBuilding>()
            for (c in builds) {
                val h = s.holding(c.tile)
                val label = if (h != null && h.buildings == PtRules.CABINS_PER_TILE) R.string.pt_btn_build_resort else R.string.pt_btn_build
                PtButton(ctx.getString(label) + " - " + ctx.tileName(c.tile) + " (" + PtBoard.tile(c.tile).buildCost + ")", on, modifier = Modifier.fillMaxWidth()) { go(c) }
            }
            for (c in sells) PtButton(ctx.getString(R.string.pt_btn_sell_building) + " - " + ctx.tileName(c.tile), on, modifier = Modifier.fillMaxWidth()) { go(c) }
        }
    }
    if (selected >= 0 && s.phase != Phase.AWAIT_BUY) {
        Spacer(Modifier.padding(top = 6.dp))
        PtTileCard(ctx, s, selected)
    }
    Spacer(Modifier.padding(top = 6.dp))
    val owned = s.ownedBy(actor)
    if (owned.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().background(PtSurface, RoundedCornerShape(10.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (h in owned) {
                val t = PtBoard.tile(h.tile)
                Text((t.group?.let { groupGlyph(it) } ?: typeGlyph(t.type)) + " " + ctx.tileName(h.tile) + if (h.buildings > 0) " x${h.buildings}" else "", color = t.group?.let { groupColor(it) } ?: PtDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PtTileCard(ctx: Context, s: TycoonState, tile: Int) {
    val t = PtBoard.tile(tile)
    val h = s.holding(tile)
    val owner = h?.let { ctx.getString(R.string.pt_a11y_tile_owned, s.players[it.owner].displayName) } ?: if (t.ownable) ctx.getString(R.string.pt_a11y_tile_free) else ""
    val detail = buildString {
        if (t.price > 0) append(ctx.getString(R.string.pt_pearls, t.price)).append(". ")
        if (t.type == TileType.PROPERTY) append(t.rents.joinToString(" / ")).append(". ")
        append(owner)
    }
    Column(
        Modifier.fillMaxWidth().background(PtRaised, RoundedCornerShape(12.dp)).padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = ctx.getString(R.string.pt_a11y_current_tile) + ": " + ctx.tileName(tile) + ". " + detail }
    ) {
        Text((t.group?.let { groupGlyph(it) + " " + ctx.groupName(it) + ": " } ?: "") + ctx.tileName(tile), color = t.group?.let { groupColor(it) } ?: PtText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (detail.isNotBlank()) Text(detail, color = PtDim, fontSize = 13.sp)
    }
}

@Composable
private fun PtPostGame(ctx: Context, s: TycoonState, game: PropertyTycoonGame, onAgain: () -> Unit, onLeave: () -> Unit) {
    val ranked = s.players.sortedByDescending { game.netWorth(it.seat) }
    val winner = s.players.getOrNull(s.winnerSeat)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ctx.getString(R.string.pt_post_title), color = PtAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (winner != null) {
            val standing = s.players.count { !it.bankrupt } == 1
            Text(
                if (standing) ctx.getString(R.string.pt_post_last_standing, winner.displayName) else ctx.getString(R.string.pt_post_winner, winner.displayName, game.netWorth(winner.seat)),
                color = PtText, fontSize = 16.sp
            )
        }
        for ((i, p) in ranked.withIndex()) {
            Text(
                "${i + 1}. " + PtSeatGlyphs[p.seat % 6] + " " + p.displayName + " - " + ctx.getString(R.string.pt_score_net_worth) + ": " + game.netWorth(p.seat) +
                    if (p.bankrupt) " (" + ctx.getString(R.string.pt_score_bankrupt_round, p.bankruptRound) + ")" else "",
                color = PtSeatColors[p.seat % 6], fontSize = 14.sp
            )
        }
        PtButton(ctx.getString(R.string.pt_post_play_again), true, primary = true, modifier = Modifier.fillMaxWidth()) { onAgain() }
        PtButton(ctx.getString(R.string.pt_post_back), true, modifier = Modifier.fillMaxWidth()) { onLeave() }
    }
}

private fun ptAnnounce(ctx: Context, ev: PtEvent, names: List<String>): String? {
    fun n(i: Int) = names.getOrElse(i) { "" }
    return when (ev) {
        is PtEvent.Rolled -> ctx.getString(R.string.pt_log_rolled, n(ev.seat), ev.d1, ev.d2)
        is PtEvent.StipendPaid -> ctx.getString(R.string.pt_log_stipend, n(ev.seat), ev.amount)
        is PtEvent.Bought -> ctx.getString(R.string.pt_log_bought, n(ev.seat), ctx.tileName(ev.tile), ev.price)
        is PtEvent.RentPaid -> ctx.getString(R.string.pt_log_rent, n(ev.from), ev.amount, n(ev.to))
        is PtEvent.TaxPaid -> ctx.getString(R.string.pt_log_tax, n(ev.seat), ev.amount)
        is PtEvent.CardDrawn -> ctx.getString(R.string.pt_log_card, n(ev.seat), ctx.cardText(ev.cardId))
        is PtEvent.Built -> ctx.getString(R.string.pt_log_built, n(ev.seat), ctx.tileName(ev.tile))
        is PtEvent.BuildingSold -> ctx.getString(R.string.pt_log_sold_building, n(ev.seat), ctx.tileName(ev.tile))
        is PtEvent.TimeoutEntered -> ctx.getString(R.string.pt_log_timeout_in, n(ev.seat))
        is PtEvent.TimeoutLeft -> ctx.getString(R.string.pt_log_timeout_out, n(ev.seat))
        is PtEvent.JackpotTaken -> ctx.getString(R.string.pt_log_jackpot, n(ev.seat), ev.amount)
        is PtEvent.DebtOpened -> ctx.getString(R.string.pt_log_debt, n(ev.debtor), ev.amount)
        is PtEvent.Bankrupt -> ctx.getString(R.string.pt_log_bankrupt, n(ev.seat))
        is PtEvent.RisingTide -> ctx.getString(R.string.pt_log_rising_tide, ev.percent)
        is PtEvent.RoundCapReached -> ctx.getString(R.string.pt_log_round_cap)
        is PtEvent.Winner -> ctx.getString(R.string.pt_log_winner, n(ev.seat))
        else -> null
    }
}
