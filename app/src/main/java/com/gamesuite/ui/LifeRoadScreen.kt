package com.gamesuite.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.liferoad.CoverKind
import com.gamesuite.games.liferoad.EffectKind
import com.gamesuite.games.liferoad.LifeRoadAction
import com.gamesuite.games.liferoad.LifeRoadContent
import com.gamesuite.games.liferoad.LifeRoadEvent
import com.gamesuite.games.liferoad.LifeRoadEventKind
import com.gamesuite.games.liferoad.LifeRoadGame
import com.gamesuite.games.liferoad.LifeRoadIndexView
import com.gamesuite.games.liferoad.LifeRoadLaunch
import com.gamesuite.games.liferoad.LifeRoadLayout
import com.gamesuite.games.liferoad.LifeRoadRules
import com.gamesuite.games.liferoad.LifeRoadState
import com.gamesuite.games.liferoad.NodeKind
import com.gamesuite.games.liferoad.PayRule
import com.gamesuite.games.liferoad.Phase
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
import androidx.compose.runtime.snapshotFlow

private val LrInk = Color(0xFF0B0D1A)
private val LrSurface = Color(0xFF141829)
private val LrRaised = Color(0xFF1E2340)
private val LrIvory = Color(0xFFF4EFE3)
private val LrDim = Color(0xFFB9B4A6)
private val LrSaffron = Color(0xFFFFB547)
private val LrEmber = Color(0xFFFF7A59)
private val LrMeadow = Color(0xFF6BCB9A)
private val LrLake = Color(0xFF6FB6FF)
private val LrSeatColors = listOf(LrSaffron, LrEmber, LrMeadow, LrLake, Color(0xFFC7A6FF), Color(0xFFFF8FB1))
private val LrShapeNames = listOf(
    R.string.lr_shape_circle, R.string.lr_shape_square, R.string.lr_shape_triangle,
    R.string.lr_shape_diamond, R.string.lr_shape_star, R.string.lr_shape_plus
)

/** Looks a Life Road string up by its resource name (job, home, card and square names are keyed by content id). */
private fun Context.lr(key: String): String {
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id == 0) key else getString(id)
}

private fun nodeGlyph(k: NodeKind): String = when (k) {
    NodeKind.START -> "⚑"
    NodeKind.FORK -> "⇅"
    NodeKind.REST -> "·"
    NodeKind.PAY -> "◎"
    NodeKind.EVENT -> "?"
    NodeKind.FAMILY -> "♥"
    NodeKind.JOB_FAIR -> "⚒"
    NodeKind.RAISE -> "▲"
    NodeKind.HOUSE_MARKET -> "⌂"
    NodeKind.MARKET_DAY -> "⇄"
    NodeKind.MILESTONE -> "★"
    NodeKind.COVER_SHOP -> "☂"
    NodeKind.BANK -> "⚖"
    NodeKind.LUCKY_WHEEL -> "✺"
    NodeKind.CAMPUS -> "✎"
    NodeKind.GRADUATION -> "❖"
    NodeKind.FIRST_JOB -> "⚒"
    NodeKind.RETIRE -> "⚑"
}

private fun nodeColor(k: NodeKind): Color = when (k) {
    NodeKind.PAY -> LrSaffron
    NodeKind.EVENT -> LrEmber
    NodeKind.FAMILY -> LrLake
    NodeKind.MILESTONE, NodeKind.LUCKY_WHEEL -> Color(0xFFC7A6FF)
    NodeKind.HOUSE_MARKET, NodeKind.MARKET_DAY -> LrMeadow
    NodeKind.FORK, NodeKind.START, NodeKind.RETIRE -> LrIvory
    NodeKind.BANK, NodeKind.COVER_SHOP -> Color(0xFF8FA3C7)
    else -> Color(0xFF3A4170)
}

private fun DrawScope.seatShape(seat: Int, c: Offset, r: Float, color: Color) {
    val outline = Color(0xFF0B0D1A)
    val p = Path()
    when (seat % 6) {
        0 -> { drawCircle(outline, r + 2f, c); drawCircle(color, r, c); return }
        1 -> { p.moveTo(c.x - r, c.y - r); p.lineTo(c.x + r, c.y - r); p.lineTo(c.x + r, c.y + r); p.lineTo(c.x - r, c.y + r); p.close() }
        2 -> { p.moveTo(c.x, c.y - r * 1.1f); p.lineTo(c.x + r * 1.1f, c.y + r); p.lineTo(c.x - r * 1.1f, c.y + r); p.close() }
        3 -> { p.moveTo(c.x, c.y - r * 1.2f); p.lineTo(c.x + r * 1.2f, c.y); p.lineTo(c.x, c.y + r * 1.2f); p.lineTo(c.x - r * 1.2f, c.y); p.close() }
        4 -> {
            for (i in 0 until 10) {
                val a = Math.PI / 5 * i - Math.PI / 2
                val rr = if (i % 2 == 0) r * 1.25f else r * 0.55f
                val x = c.x + (Math.cos(a) * rr).toFloat(); val y = c.y + (Math.sin(a) * rr).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        else -> {
            val t = r * 0.4f
            p.moveTo(c.x - t, c.y - r); p.lineTo(c.x + t, c.y - r); p.lineTo(c.x + t, c.y - t); p.lineTo(c.x + r, c.y - t)
            p.lineTo(c.x + r, c.y + t); p.lineTo(c.x + t, c.y + t); p.lineTo(c.x + t, c.y + r); p.lineTo(c.x - t, c.y + r)
            p.lineTo(c.x - t, c.y + t); p.lineTo(c.x - r, c.y + t); p.lineTo(c.x - r, c.y - t); p.lineTo(c.x - t, c.y - t); p.close()
        }
    }
    drawPath(p, outline, style = Stroke(width = 4f))
    drawPath(p, color)
}

private fun seatGlyph(seat: Int): String = listOf("●", "■", "▲", "◆", "★", "✚")[seat % 6]

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifeRoadScreen(
    sessionManager: GameSessionManager,
    game: LifeRoadGame,
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
        val extras = sessionManager.launchOptions.extras
        game.config = LifeRoadLaunch.configFrom(extras, c.players.size, sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty))
        game.firstOpenerSeat = sessionManager.launchOptions.firstMoverIndex
        game.init(c)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    // ---- animation: pieces hop square by square; everything waits for the hops (Reduced Motion shows results at once)
    val shown = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var handled by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val serial = game.eventSerial.value

    suspend fun awaitUnpaused() { if (game.paused.value) snapshotFlow { game.paused.value }.first { !it } }

    LaunchedEffect(serial) {
        val cur = game.state.value ?: return@LaunchedEffect
        if (handled < 0) {
            shown.value = cur.players.associate { it.seat to it.nodeId }
            handled = serial
            return@LaunchedEffect
        }
        busy = true
        try {
            val evs = game.lastEvents.value
            val names = cur.players.map { it.displayName }
            val out = ArrayList<String>()
            for (ev in evs) {
                awaitUnpaused()
                if (ev.kind == LifeRoadEventKind.MOVE && ev.a >= 0) {
                    shown.value = shown.value + (ev.seat to ev.ref)
                    if (!reduced) { playSfx(SfxKind.LIGHT_TICK); delay(speed.scale(130L)) }
                } else if (ev.kind == LifeRoadEventKind.MOVE) {
                    shown.value = shown.value + (ev.seat to ev.ref)
                } else {
                    lrAnnounce(ctx, ev, names)?.let { out += it }
                    when (ev.kind) {
                        LifeRoadEventKind.SPIN, LifeRoadEventKind.LUCKY_COIN -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.NORMAL_ACTION); if (!reduced) delay(speed.scale(250L)) }
                        LifeRoadEventKind.PASS_PAY, LifeRoadEventKind.WHEEL -> { playSfx(SfxKind.SUCCESS_CHIME); if (!reduced) delay(speed.scale(200L)) }
                        LifeRoadEventKind.COVER_SAVED, LifeRoadEventKind.RETIRE -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.SUCCESS) }
                        LifeRoadEventKind.LOAN, LifeRoadEventKind.FRESH_START -> { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE) }
                        LifeRoadEventKind.HOUSE_BOUGHT, LifeRoadEventKind.HOUSE_SOLD, LifeRoadEventKind.JOB -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
                        LifeRoadEventKind.GAME_OVER -> haptics(HapticSignal.CELEBRATION)
                        else -> {}
                    }
                }
            }
            if (out.isNotEmpty()) lines = out.takeLast(3)
        } finally {
            shown.value = cur.players.associate { it.seat to it.nodeId }
            handled = serial
            busy = false
        }
    }

    val gamePaused = game.paused.value
    LaunchedEffect(state, busy, handled, gamePaused) {
        val cur = state ?: return@LaunchedEffect
        if (busy || handled != game.eventSerial.value || gamePaused || !game.botToMove(cur)) return@LaunchedEffect
        delay(speed.scale(((LifeRoadRules.BOT_THINK_MIN_MS + LifeRoadRules.BOT_THINK_MAX_MS) / 2).toLong()))
        game.playBotStep()
    }

    val helpTitle = androidx.compose.ui.res.stringResource(R.string.lr_help_title)
    val helpText = androidx.compose.ui.res.stringResource(R.string.lr_help_text)
    val s = state
    if (s == null) {
        GameChrome(
            helpTitle = helpTitle, helpText = helpText, matchInProgress = true, onLeave = game::abortMatch, onAbort = { game.abortMatch() },
            leaveKeepsProgress = false, buttonFill = LrInk, buttonContent = LrIvory
        ) { Box(Modifier.fillMaxSize().background(LrInk)) }
        return
    }

    val finished = game.sessionWins.value.values.sum()
    val locked = busy || handled != serial
    val me = s.players[s.currentSeat]
    val myTurn = game.isMyTurn(s) && !locked
    val legal = if (myTurn) game.legalActions() else emptyList()
    val minTarget = if (s.config.junior) 56.dp else 48.dp

    fun go(a: LifeRoadAction) {
        val why = game.explain(a)
        if (!game.perform(a)) {
            playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.FAILURE)
            lines = listOf(ctx.lr(why.stringKey ?: "lr_why_phase"))
        }
    }

    GameChrome(
        helpTitle = helpTitle, helpText = helpText, matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (finished > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finished == 0,
        buttonFill = LrInk, buttonContent = LrIvory,
        leaveTitle = androidx.compose.ui.res.stringResource(R.string.ccl_leave_title),
        leaveBody = androidx.compose.ui.res.stringResource(if (finished > 0) R.string.ccl_leave_body_session else R.string.ccl_leave_body)
    ) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { if (!windowFocused) game.pause() else game.resume() }
        BoxWithConstraints(Modifier.fillMaxSize().background(LrInk)) {
            val landscape = maxWidth > maxHeight * 1.15f
            val strip: @Composable () -> Unit = {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = GameChromeEndInset, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (p in s.players) {
                        val current = p.seat == s.currentSeat && !s.over
                        val job = p.jobId?.let { ctx.lr("lr_job_$it") } ?: ctx.getString(R.string.lr_hud_nojob)
                        val desc = ctx.getString(R.string.lr_a11y_player_fmt, p.displayName, p.cash, job) + ", " + ctx.getString(LrShapeNames[p.seat % 6])
                        Row(
                            Modifier.semantics(mergeDescendants = true) { contentDescription = desc }
                                .background(if (current) LrRaised else LrSurface, RoundedCornerShape(10.dp))
                                .border(if (current) 2.dp else 1.dp, if (current) LrSaffron else Color(0xFF2B3158), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(seatGlyph(p.seat), color = LrSeatColors[p.seat % 6], fontSize = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(p.displayName, color = LrIvory, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(ctx.getString(R.string.lr_coins_fmt, p.cash) + " · " + job, color = LrDim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val board: @Composable (Modifier) -> Unit = { mod -> LifeRoadBoardView(s, shown.value, mod) }
            val panel: @Composable () -> Unit = {
                LifeRoadPanel(ctx, s, game, legal, lines, myTurn, locked, minTarget, me.seat, ::go, onAgain = { game.playAgain() }, onLeave = { game.leaveSession() })
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.2f)) { strip(); board(Modifier.weight(1f).fillMaxWidth()) }
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
private fun LifeRoadBoardView(s: LifeRoadState, shown: Map<Int, String>, modifier: Modifier) {
    val layout = remember(s.config.size) { LifeRoadLayout.of(s.config.size) }
    val graph = remember(s.config.size) { com.gamesuite.games.liferoad.LifeRoadBoard.of(s.config.size) }
    val ctx = LocalContext.current
    val desc = remember(shown, s.players) {
        ctx.getString(R.string.lr_a11y_board) + ". " + s.players.joinToString(". ") { p ->
            val node = graph.node(shown[p.seat] ?: p.nodeId)
            val kind = node?.let { ctx.lr(it.kind.nameKey) } ?: ""
            ctx.getString(R.string.lr_a11y_piece_fmt, p.displayName, kind)
        }
    }
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(modifier.padding(6.dp).semantics { contentDescription = desc }) {
        val unit = minOf(size.width / layout.width, size.height / layout.height)
        val ox = (size.width - unit * layout.width) / 2f
        val oy = (size.height - unit * layout.height) / 2f
        fun at(id: String): Offset { val p = layout.points.getValue(id); return Offset(ox + p.x * unit, oy + p.y * unit) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(unit * 0.12f, unit * 0.1f))
        for (n in graph.nodes) for (nx in n.next) {
            val bold = graph.node(nx)!!.bold
            drawLine(if (bold) LrEmber else Color(0xFF2B3158), at(n.id), at(nx), strokeWidth = unit * 0.1f, pathEffect = if (bold) dash else null)
        }
        val r = unit * 0.3f
        paint.textSize = unit * 0.34f
        for (n in graph.nodes) {
            val c = at(n.id)
            drawCircle(nodeColor(n.kind), r, c)
            drawCircle(if (n.bold) LrEmber else Color(0xFF0B0D1A), r, c, style = Stroke(width = unit * 0.05f))
            paint.color = if (nodeColor(n.kind) == Color(0xFF3A4170)) 0xFFF4EFE3.toInt() else 0xFF0B0D1A.toInt()
            drawIntoCanvas { it.nativeCanvas.drawText(nodeGlyph(n.kind), c.x, c.y + unit * 0.12f, paint) }
        }
        for (p in s.players) {
            if (p.retired && p.nodeId.startsWith("R") && shown[p.seat] == null) continue
            val c = at(shown[p.seat] ?: p.nodeId)
            val off = Offset((p.seat % 3 - 1) * unit * 0.2f, (p.seat / 3 - 0.5f) * unit * 0.3f - unit * 0.1f)
            seatShape(p.seat, c + off, unit * 0.17f, LrSeatColors[p.seat % 6])
        }
    }
}

@Composable
private fun LrButton(text: String, enabled: Boolean, minH: androidx.compose.ui.unit.Dp, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = minH),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) LrSaffron else LrRaised, contentColor = if (primary) LrInk else LrIvory,
            disabledContainerColor = LrSurface, disabledContentColor = LrDim
        ),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = if (primary) 18.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LifeRoadPanel(
    ctx: Context, s: LifeRoadState, game: LifeRoadGame, legal: List<LifeRoadAction>, lines: List<String>, myTurn: Boolean, locked: Boolean,
    minH: androidx.compose.ui.unit.Dp, seat: Int, act: (LifeRoadAction) -> Unit, onAgain: () -> Unit, onLeave: () -> Unit
) {
    val p = s.players[seat]
    val cpu = p.isBot
    val prompt = when {
        s.over -> ctx.getString(R.string.lr_ann_game_over)
        cpu || locked && !myTurn && game.botToMove(s) -> ctx.getString(R.string.lr_prompt_cpu)
        else -> when (s.phase) {
            Phase.START_FORK -> ctx.getString(R.string.lr_start_prompt_fmt, p.displayName)
            Phase.AWAIT_FORK -> ctx.getString(R.string.lr_prompt_fork)
            Phase.AWAIT_SPIN -> ctx.getString(R.string.lr_prompt_spin)
            Phase.AWAIT_RESPIN -> ctx.getString(R.string.lr_prompt_respin)
            Phase.AWAIT_JOB -> ctx.getString(R.string.lr_prompt_job)
            Phase.AWAIT_HOUSE -> ctx.getString(R.string.lr_prompt_house)
            Phase.AWAIT_COVER -> ctx.getString(R.string.lr_prompt_cover)
            Phase.AWAIT_BANK -> ctx.getString(R.string.lr_prompt_bank)
            Phase.AWAIT_MARKET -> ctx.getString(R.string.lr_prompt_market)
            Phase.AWAIT_CARD -> ctx.getString(if (s.pending.needsSpin) R.string.lr_prompt_card_spin else R.string.lr_prompt_card)
            Phase.SETTLING -> ctx.getString(R.string.lr_prompt_settle)
            else -> ""
        }
    }
    val header = if (s.over) "" else ctx.getString(R.string.lr_turn_fmt, p.displayName)
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header.isNotEmpty()) Text(header, color = LrSeatColors[seat % 6], fontWeight = FontWeight.Bold, fontSize = 16.sp)
        for (l in lines) Text(l, color = LrDim, fontSize = 13.sp)
        Text(prompt, color = LrIvory, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.padding(top = 6.dp))
    if (s.over) {
        PostGame(ctx, s, game, minH, onAgain, onLeave)
        return
    }
    val on = myTurn
    fun has(a: LifeRoadAction) = a in legal
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (s.phase) {
            Phase.START_FORK, Phase.AWAIT_FORK -> {
                val node = com.gamesuite.games.liferoad.LifeRoadBoard.of(s.config.size).node(if (s.phase == Phase.START_FORK) "S0" else s.pending.forkNodeId ?: "S0")
                for (to in node?.next ?: emptyList()) {
                    val (title, desc) = forkText(ctx, to)
                    val a = LifeRoadAction.ChooseBranch(to)
                    val why = if (on && !has(a)) game.explain(a).stringKey?.let { ctx.lr(it) } else null
                    LrButton(title + " - " + desc + (why?.let { " ($it)" } ?: ""), on && has(a), minH, modifier = Modifier.fillMaxWidth()) { act(a) }
                }
                LrButton(ctx.getString(R.string.lr_fork_pick_for_me), on, minH, modifier = Modifier.fillMaxWidth()) {
                    val pick = legal.firstOrNull { it is LifeRoadAction.ChooseBranch }
                    if (pick != null) act(pick)
                }
            }
            Phase.AWAIT_SPIN, Phase.SETTLING -> {
                val spinText = if (s.lastSpin > 0) ctx.getString(R.string.lr_a11y_spinner_result_fmt, s.lastSpin) else ctx.getString(R.string.lr_a11y_spinner)
                LrButton(ctx.getString(R.string.lr_btn_spin), on, 64.dp, primary = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = spinText }) { act(LifeRoadAction.Spin) }
                if (s.lastSpin > 0) Text(spinText, color = LrDim, fontSize = 13.sp)
            }
            Phase.AWAIT_RESPIN -> {
                Text(ctx.getString(R.string.lr_a11y_spinner_result_fmt, s.pending.spin), color = LrSaffron, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                LrButton(ctx.getString(R.string.lr_btn_keep_spin), on, minH, primary = true, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.KeepSpin) }
                LrButton(ctx.getString(R.string.lr_btn_lucky_fmt, p.luckyCoins), on && has(LifeRoadAction.UseLuckyCoin), minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.UseLuckyCoin) }
            }
            Phase.AWAIT_JOB -> {
                for (id in s.pending.offerIds) {
                    val j = LifeRoadIndexView.job(id) ?: continue
                    val tips = if (j.payRule == PayRule.TIPS) " (" + ctx.getString(R.string.lr_hud_pay_tips) + ")" else ""
                    val label = ctx.lr("lr_job_$id") + " - " + ctx.getString(R.string.lr_hud_tier_fmt, j.tier) + ", " + ctx.getString(R.string.lr_per_pay_fmt, j.salary) + tips
                    LrButton(label, on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.PickJob(id)) }
                }
                if (!s.pending.mustPick) LrButton(ctx.getString(R.string.lr_btn_keep_job), on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.PickJob(null)) }
            }
            Phase.AWAIT_HOUSE -> {
                for (id in s.pending.offerIds) {
                    val price = LifeRoadIndexView.house(id)?.price ?: 0
                    val a = LifeRoadAction.BuyHouse(id)
                    val loan = if (price > p.cash && !s.config.junior) " - " + ctx.getString(R.string.lr_hud_needs_loan) else ""
                    LrButton(ctx.getString(R.string.lr_btn_buy) + " " + ctx.lr("lr_house_$id") + " - " + ctx.getString(R.string.lr_hud_price_fmt, price) + loan, on && has(a), minH, modifier = Modifier.fillMaxWidth()) { act(a) }
                }
                LrButton(ctx.getString(R.string.lr_btn_no_thanks), on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.DeclineHouse) }
            }
            Phase.AWAIT_COVER -> {
                for (k in CoverKind.values()) if (k !in p.covers) {
                    val a = LifeRoadAction.BuyCover(k)
                    LrButton(ctx.getString(R.string.lr_btn_cover_fmt, ctx.lr(k.nameKey)), on && has(a), minH, modifier = Modifier.fillMaxWidth()) { act(a) }
                }
                LrButton(ctx.getString(R.string.lr_btn_done), on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.DeclineCover) }
            }
            Phase.AWAIT_BANK -> {
                LrButton(ctx.getString(R.string.lr_btn_borrow), on && has(LifeRoadAction.TakeLoan), minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.TakeLoan) }
                LrButton(ctx.getString(R.string.lr_btn_repay), on && has(LifeRoadAction.RepayLoan), minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.RepayLoan) }
                LrButton(ctx.getString(R.string.lr_btn_done), on, minH, primary = true, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.Done) }
            }
            Phase.AWAIT_MARKET -> {
                for (h in p.homes) LrButton(ctx.getString(R.string.lr_btn_sell_now) + " - " + ctx.lr("lr_house_${h.houseId}"), on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.SellHome(h.houseId)) }
                LrButton(ctx.getString(R.string.lr_btn_hold), on, minH, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.DeclineSale) }
            }
            Phase.AWAIT_CARD -> {
                val id = s.pending.cardId ?: ""
                val (title, text) = cardText(ctx, id, s.pending.cardIsFamily)
                Column(Modifier.fillMaxWidth().background(LrRaised, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(title, color = LrSaffron, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(text, color = LrIvory, fontSize = 17.sp)
                }
                if (s.pending.needsSpin) LrButton(ctx.getString(R.string.lr_btn_spin), on, 64.dp, primary = true, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.Spin) }
                else LrButton(ctx.getString(R.string.lr_btn_continue), on, minH, primary = true, modifier = Modifier.fillMaxWidth()) { act(LifeRoadAction.Acknowledge) }
            }
            else -> {}
        }
    }
    Spacer(Modifier.padding(top = 6.dp))
    MyPanel(ctx, s, p)
}

private fun forkText(ctx: Context, to: String): Pair<String, String> = when (to) {
    "LRN.0" -> ctx.getString(R.string.lr_fork_learn) to ctx.getString(R.string.lr_fork_learn_desc)
    "WRK.0" -> ctx.getString(R.string.lr_fork_work) to ctx.getString(R.string.lr_fork_work_desc)
    "RISK.0" -> ctx.getString(R.string.lr_fork_cliff) to ctx.getString(R.string.lr_fork_cliff_desc)
    "SAFE.0" -> ctx.getString(R.string.lr_fork_meadow) to ctx.getString(R.string.lr_fork_meadow_desc)
    "FAM.0" -> ctx.getString(R.string.lr_fork_family) to ctx.getString(R.string.lr_fork_family_desc)
    "FREE.0" -> ctx.getString(R.string.lr_fork_free) to ctx.getString(R.string.lr_fork_free_desc)
    "COZY.0" -> ctx.getString(R.string.lr_lane_cozy) to ctx.getString(R.string.lr_lane_cozy_desc)
    else -> ctx.getString(R.string.lr_lane_grand) to ctx.getString(R.string.lr_lane_grand_desc)
}

private fun cardText(ctx: Context, id: String, family: Boolean): Pair<String, String> = when {
    id == "wheel" -> ctx.getString(R.string.lr_wheel_title) to ctx.getString(R.string.lr_nodehelp_wheel)
    id.startsWith("ms_") -> ctx.getString(R.string.lr_card_milestone) to ctx.lr("lr_ms_" + id.removePrefix("ms_"))
    family -> ctx.getString(R.string.lr_card_family) to ctx.lr("lr_family_$id")
    else -> {
        val tone = LifeRoadIndexView.event(id)?.tone
        val label = when (tone) {
            com.gamesuite.games.liferoad.EventTone.GOOD -> R.string.lr_tone_good
            com.gamesuite.games.liferoad.EventTone.SETBACK -> R.string.lr_tone_bad
            else -> R.string.lr_tone_fun
        }
        (ctx.getString(R.string.lr_card_event) + " - " + ctx.getString(label)) to ctx.lr("lr_event_$id")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyPanel(ctx: Context, s: LifeRoadState, p: com.gamesuite.games.liferoad.PlayerState) {
    val job = p.jobId?.let { ctx.lr("lr_job_$it") } ?: ctx.getString(R.string.lr_hud_nojob)
    val covers = p.covers.joinToString(", ") { ctx.lr(it.nameKey) }
    val parts = listOf(
        ctx.getString(R.string.lr_hud_job) + ": " + job + if (p.degree) " (" + ctx.getString(R.string.lr_hud_degree) + ")" else "",
        ctx.getString(R.string.lr_hud_homes) + ": " + p.homes.size,
        ctx.getString(R.string.lr_hud_family) + ": " + p.familyPegs,
        ctx.getString(R.string.lr_hud_memories) + ": " + p.memories,
        ctx.getString(R.string.lr_hud_lucky) + ": " + p.luckyCoins
    ) + (if (s.config.junior) emptyList() else listOf(
        ctx.getString(R.string.lr_hud_loans) + ": " + p.loans,
        ctx.getString(R.string.lr_hud_cover) + ": " + covers
    ))
    FlowRow(
        Modifier.fillMaxWidth().background(LrSurface, RoundedCornerShape(10.dp)).padding(8.dp)
            .semantics(mergeDescendants = true) { contentDescription = parts.joinToString(". ") },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { for (t in parts) Text(t, color = LrDim, fontSize = 13.sp) }
}

@Composable
private fun PostGame(ctx: Context, s: LifeRoadState, game: LifeRoadGame, minH: androidx.compose.ui.unit.Dp, onAgain: () -> Unit, onLeave: () -> Unit) {
    val cards = game.scoreCards()
    val winners = s.winnerSeats.map { s.players[it].displayName }
    var math by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ctx.getString(R.string.lr_post_title), color = LrSaffron, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (winners.size == 1) ctx.getString(R.string.lr_post_winner_fmt, winners[0]) else ctx.getString(R.string.lr_post_tie_fmt, winners.joinToString(", ")),
            color = LrIvory, fontSize = 17.sp
        )
        LrButton(ctx.getString(if (math) R.string.lr_sc_hide_math else R.string.lr_sc_show_math), true, minH, modifier = Modifier.fillMaxWidth()) { math = !math }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in cards.sortedBy { it.place }) {
                val p = s.players[c.seat]
                Column(Modifier.width(150.dp).background(LrSurface, RoundedCornerShape(10.dp)).padding(8.dp)) {
                    Text(seatGlyph(c.seat) + " " + p.displayName, color = LrSeatColors[c.seat % 6], fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(ctx.getString(R.string.lr_sc_place_fmt, c.place), color = LrIvory, fontSize = 13.sp)
                    if (math) for (l in c.lines) if (!(s.config.junior && l.amount < 0)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ctx.lr(l.labelKey), color = LrDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${l.amount}", color = LrDim, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ctx.getString(R.string.lr_sc_total), color = LrIvory, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${c.total}", color = LrIvory, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
        LrButton(ctx.getString(R.string.lr_post_play_again), true, minH, primary = true, modifier = Modifier.fillMaxWidth()) { onAgain() }
        LrButton(ctx.getString(R.string.lr_post_leave), true, minH, modifier = Modifier.fillMaxWidth()) { onLeave() }
    }
}

private fun lrAnnounce(ctx: Context, ev: LifeRoadEvent, names: List<String>): String? {
    val who = names.getOrElse(ev.seat) { "" }
    return when (ev.kind) {
        LifeRoadEventKind.SPIN -> ctx.getString(R.string.lr_ann_spin_fmt, who, ev.a)
        LifeRoadEventKind.CATCH_UP -> ctx.getString(R.string.lr_ann_catchup_fmt, who)
        LifeRoadEventKind.LUCKY_COIN -> ctx.getString(R.string.lr_ann_lucky_fmt, who, ev.a)
        LifeRoadEventKind.PASS_PAY -> ctx.getString(R.string.lr_ann_pay_fmt, who, ev.a)
        LifeRoadEventKind.JOB -> ctx.getString(R.string.lr_ann_job_fmt, who, ctx.lr("lr_job_${ev.ref}"))
        LifeRoadEventKind.DEGREE -> ctx.getString(R.string.lr_ann_degree_fmt, who)
        LifeRoadEventKind.RAISE -> ctx.getString(R.string.lr_ann_raise_fmt, who)
        LifeRoadEventKind.HOUSE_BOUGHT -> ctx.getString(R.string.lr_ann_house_fmt, who, ctx.lr("lr_house_${ev.ref}"), ev.a)
        LifeRoadEventKind.HOUSE_SOLD ->
            if (ev.b > 0) ctx.getString(R.string.lr_ann_market_fmt, ev.b, ctx.lr("lr_house_${ev.ref}"), ev.a)
            else ctx.getString(R.string.lr_ann_sold_fmt, who, ctx.lr("lr_house_${ev.ref}"), ev.a)
        LifeRoadEventKind.COVER_BOUGHT -> ctx.getString(R.string.lr_ann_cover_fmt, who, ctx.lr("lr_cover_${ev.ref.lowercase()}"))
        LifeRoadEventKind.COVER_SAVED -> ctx.getString(R.string.lr_ann_cover_saved_fmt, who)
        LifeRoadEventKind.LOAN -> ctx.getString(R.string.lr_ann_loan_fmt, who)
        LifeRoadEventKind.REPAY -> ctx.getString(R.string.lr_ann_repay_fmt, who)
        LifeRoadEventKind.CARD -> {
            val family = LifeRoadIndexView.family(ev.ref) != null
            ctx.getString(R.string.lr_ann_card_fmt, who, ctx.lr((if (family) "lr_family_" else "lr_event_") + ev.ref))
        }
        LifeRoadEventKind.MEMORY -> if (ev.ref.isNotEmpty()) ctx.getString(R.string.lr_ann_memory_fmt, who, ctx.lr("lr_ms_${ev.ref}")) else null
        LifeRoadEventKind.FAMILY -> ctx.getString(R.string.lr_ann_family_fmt, who, ev.a)
        LifeRoadEventKind.WHEEL -> ctx.getString(R.string.lr_ann_wheel_fmt, ev.a, ev.b)
        LifeRoadEventKind.FRESH_START -> ctx.getString(R.string.lr_ann_fresh_fmt, who)
        LifeRoadEventKind.RETIRE -> ctx.getString(R.string.lr_ann_retire_fmt, who, ev.a)
        LifeRoadEventKind.LATE_RETIRE -> ctx.getString(R.string.lr_ann_late_fmt, who)
        LifeRoadEventKind.GAME_OVER -> ctx.getString(R.string.lr_ann_game_over)
        else -> null
    }
}
