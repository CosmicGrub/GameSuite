package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.bridgebuilders.BridgeBand
import com.gamesuite.games.bridgebuilders.BridgeBuildersGame
import com.gamesuite.games.bridgebuilders.BridgeEvent
import com.gamesuite.games.bridgebuilders.BridgeHandicap
import com.gamesuite.games.bridgebuilders.BridgeLaneState
import com.gamesuite.games.bridgebuilders.BridgeLaneStatus
import com.gamesuite.games.bridgebuilders.BridgePhase
import com.gamesuite.games.bridgebuilders.BridgeRules
import com.gamesuite.games.bridgebuilders.BridgeState
import com.gamesuite.games.bridgebuilders.BridgeTapResult
import com.gamesuite.games.bridgebuilders.BridgeTier
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.games.partypack.TapTugPointerTracker
import com.gamesuite.games.partypack.TapTugZoneLayout
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bridge Builders on one screen: the cooperative game. Every builder has a lane in their own zone (the far row turned
 * half a turn) with a cart sliding along a bar and a green spot; the river and the shared bridge are in the middle band.
 * The input is the shared party-pack board (multi-touch, palm rejection, TalkBack double-tap = one tap).
 *
 * Colour-blind safe: every lane has a colour, a glyph AND a surface pattern; the green spot is a framed, striped box with
 * end markers and a diamond, so it never relies on green alone; "waiting" and "oops" are words. Reduced Motion: the water
 * rises as a flat line (no waves, no confetti, no spring). Game speed: Slow slows the cart. TalkBack hears only
 * milestones (the status line is the one live region). Same screen only: no Nearby, no Online.
 */
@Composable
fun BridgeBuildersScreen(
    sessionManager: GameSessionManager,
    game: BridgeBuildersGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    remember { CardSounds.get(androidContext) }
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current && CardSounds.soundEnabled
    rememberAmbientMusic(profile = MusicProfiles.MANCALA, enabled = musicEnabled)
    val state by game.state
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val gameSpeed = LocalGameSpeed.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val burst = rememberParticleBurst()
    var rotate by remember { mutableStateOf(true) }
    // True while a dialog / sheet is open or the window has lost focus (set below, where the coach card is known).
    var uiHold by remember { mutableStateOf(false) }
    val ownPause = remember { booleanArrayOf(false) }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        val extras = sessionManager.launchOptions.extras
        game.difficulty = sessionManager.cpuDifficultyFor(settings.defaultCpuDifficulty)
        game.band = BridgeBand.fromId(extras["band"])
        game.relaxed = extras["relaxed"] == "true"
        game.handicap = BridgeHandicap.fromExtras(extras["handicap"], extras["handicap3"], extras["handicap4"], ctx.players.size)
        game.pace = (1f / gameSpeed.delayScale).toDouble()
        rotate = extras["rotate"] != "false"
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (!sessionManager.tryResume(game)) game.startMatch()
    }

    LaunchedEffect(gameSpeed) { game.pace = (1f / gameSpeed.delayScale).toDouble() }

    LaunchedEffect(game) {
        while (true) {
            // Re-asserted every beat: an Activity resume (or the arena) must not un-pause the game behind an open dialog.
            if (uiHold) game.pause()
            game.tick()
            delay(25)
        }
    }

    // Pause while a dialog or sheet is up (the coach card, help, leave confirm, the corner menu, the notification
    // shade, split-screen focus loss). The engine freezes its clock while paused and resume() restarts it from "now",
    // so the water, cart and robots continue exactly where they stopped, with no catch-up burst.
    LaunchedEffect(uiHold) {
        if (uiHold) {
            game.pause()
            ownPause[0] = true
        } else if (ownPause[0]) {
            ownPause[0] = false
            if (game.arena?.paused != true) game.resume()
        }
    }

    val helpTitle = stringResource(R.string.bridge_builders_help_title)
    val helpText = stringResource(R.string.bridge_builders_help_text)
    val s = state ?: return
    val seatCount = s.lanes.size
    val band = if (seatCount >= 4) 0.40f else 0.34f
    val tracker = remember { TapTugPointerTracker() }

    LaunchedEffect(s.eventSerial) {
        if (s.eventSerial == 0) return@LaunchedEffect
        when (s.event) {
            BridgeEvent.GO -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION) }
            BridgeEvent.ROW -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            BridgeEvent.WATER_HIGH -> haptics(HapticSignal.ESCALATING)
            BridgeEvent.CROSS -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.SUCCESS) }
            BridgeEvent.WIN -> {
                playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.CELEBRATION)
                if (!reduced) burst.spawn(Offset(0.5f, 0.5f), 90, listOf(Color(0xFFF2B100), Color.White, TapTugSeatColors[0], TapTugSeatColors[1]), speedRange = 0.25f..0.9f, lifeRangeSeconds = 1.0f..2.0f, gravity = 0.9f)
            }
            BridgeEvent.LOSE -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }

    // The sound and haptic of a tap. Shared by a finger and by TalkBack's double-tap, so a screen-reader user hears it too.
    fun tapFeedback(result: BridgeTapResult) {
        when (result) {
            BridgeTapResult.PERFECT -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.STRONG_ACTION) }
            BridgeTapResult.GOOD -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.NORMAL_ACTION) }
            BridgeTapResult.WOBBLY -> { playSfx(SfxKind.SOLID_THUNK); haptics(HapticSignal.LIGHT_TICK) }
            BridgeTapResult.MISS, BridgeTapResult.WAITING, BridgeTapResult.STUMBLED -> { playSfx(SfxKind.LIGHT_TICK); haptics(HapticSignal.LIGHT_TICK) }
            else -> {}
        }
    }

    val status = bridgeStatusText(androidContext, s)
    GameChrome(
        helpTitle = helpTitle, helpText = helpText,
        matchInProgress = !s.over,
        onLeave = game::leaveSession,
        onAbort = { if (s.bridgesBuilt > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = s.bridgesBuilt == 0,
        buttonFill = MaterialTheme.colorScheme.background, buttonContent = MaterialTheme.colorScheme.onBackground
    ) {
        val (coachShown, openCoach) = rememberClCoach("bridge-builders", ready = true)
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, coachShown) { uiHold = !windowFocused || coachShown }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
                ClCoachButton("bridge-builders", openCoach)
                ClStatusLine(status, MaterialTheme.colorScheme.onBackground, Modifier.weight(1f), emphasized = true)
            }
            val bottomCount = TapTugZoneLayout.defaultBottomCount(seatCount)
            val zones = remember(seatCount, rotate, band) { TapTugZoneLayout.rows(bottomCount, seatCount - bottomCount, rotate, band) }
            val bandZone = remember(band) { TapTugZoneLayout.bandRect(band) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TapTugBoard(
                    zones = zones, band = bandZone, interactive = s.lanes.map { !it.isBot }, tracker = tracker,
                    a11y = { seat ->
                        val lane = s.lanes[seat]
                        val name = androidContext.seatLabel(lane.name)
                        val desc = when {
                            lane.isBot -> androidContext.getString(R.string.bridge_builders_zone_desc_robot, name, seat + 1, lane.planks, lane.spans)
                            lane.status == BridgeLaneStatus.DONE -> androidContext.getString(R.string.bridge_builders_zone_desc_done, name, seat + 1, lane.spans)
                            lane.status == BridgeLaneStatus.WAITING -> androidContext.getString(R.string.bridge_builders_zone_desc_waiting, name, seat + 1, lane.planks, lane.spans)
                            else -> androidContext.getString(R.string.bridge_builders_zone_desc, name, seat + 1, lane.planks, lane.spans)
                        }
                        TapTugZoneA11y(description = desc, onActivate = { if (!lane.isBot) tapFeedback(game.tap(seat)) })
                    },
                    onPress = { seat, _ -> tapFeedback(game.tap(seat)) },
                    onRelease = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    bandContent = { BridgeRiverBand(s, rotate, reduced, dark) },
                    zoneContent = { seat -> BridgeZone(seat, s, reduced, dark) },
                    overlay = {
                        Canvas(Modifier.fillMaxSize()) { drawBurst(burst) }
                        if (s.over) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                BridgeFinished(s, game, androidContext)
                            }
                        }
                    }
                )
            }
        }
    }
}

/** The one milestone sentence the live region reads. */
private fun bridgeStatusText(ctx: Context, s: BridgeState): String = when (s.event) {
    BridgeEvent.GO -> ctx.getString(R.string.bridge_builders_status_go)
    BridgeEvent.ROW -> ctx.getString(R.string.bridge_builders_status_row, s.eventArg, s.spans)
    BridgeEvent.WATER_HALF -> ctx.getString(R.string.bridge_builders_status_water_half)
    BridgeEvent.WATER_HIGH -> ctx.getString(R.string.bridge_builders_status_water_high)
    BridgeEvent.CROSS -> ctx.getString(R.string.bridge_builders_status_cross)
    BridgeEvent.WIN -> ctx.getString(R.string.bridge_builders_status_win)
    BridgeEvent.LOSE -> ctx.getString(R.string.bridge_builders_status_lose)
    else -> ctx.getString(R.string.bridge_builders_status_ready)
}

@Composable
private fun BridgeFinished(s: BridgeState, game: BridgeBuildersGame, ctx: Context) {
    val title = if (s.won) stringResource(R.string.bridge_builders_status_win) else stringResource(R.string.bridge_builders_status_lose)
    val total = s.lanes.sumOf { it.planks }
    val lines = buildList {
        if (s.won) add(ctx.getString(R.string.bridge_builders_final_strength, (s.strength * 100f).toInt()))
        else add(ctx.getString(R.string.bridge_builders_final_planks, total, s.lanes.size * s.spans))
        add(ctx.getString(R.string.bridge_builders_final_bridges, s.bridgesBuilt))
    }
    Box(Modifier.padding(16.dp)) {
        ClFinishedPanel(
            title = title, subtitle = null, lines = lines,
            canPlayAgain = true, onPlayAgain = { game.playAgain() }, onLeave = { game.leaveSession() },
            playAgainLabel = stringResource(R.string.bridge_builders_build_again)
        )
    }
}

private val WaterColor = Color(0xAA3B82C4)
private val SpotGreen = Color(0xFF1FA85A)
private val SpotEdge = Color(0xFF0B5D34)
private val RopeColor = Color(0xFFB4783A)
private val RopeTight = Color(0xFFD97706)
private val WoodColor = Color(0xFFB98B54)
private val WoodDark = Color(0xFF6E4A22)

/** The river band: the shared bridge (a row of plank slots per lane), the ropes between lanes, the friends, and the rising water. */
@Composable
private fun BridgeRiverBand(s: BridgeState, rotate: Boolean, reduced: Boolean, dark: Boolean) {
    val ink = MaterialTheme.colorScheme.onBackground
    val info = stringResource(R.string.bridge_builders_info_strength, (s.strength * 100f).toInt()) + "\n" +
        stringResource(R.string.bridge_builders_info_water, s.waterLeftSeconds)
    val density = LocalDensity.current.density
    val scratch = remember { RiverScratch() }
    val bigFont = LocalDensity.current.fontScale > 1.25f
    val sideW = if (bigFont) 1.0f else 0.6f
    val style = if (bigFont) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            info, Modifier.weight(sideW).graphicsLayer { rotationZ = if (rotate) 180f else 0f }, color = ink, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, style = style
        )
        Canvas(Modifier.weight(if (bigFont) 2.2f else 2.8f).fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            drawRiver(s, density, reduced, dark, ink, scratch)
        }
        Text(info, Modifier.weight(sideW), color = ink, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, style = style)
    }
}

/** The river's reusable [Path]s: reset with `rewind()` and refilled every draw, instead of ~400 native allocations a second. */
private class RiverScratch(val water: Path = Path(), val line: Path = Path(), val rope: Path = Path(), val knot: Path = Path())

private fun DrawScope.drawRiver(s: BridgeState, density: Float, reduced: Boolean, dark: Boolean, ink: Color, scratch: RiverScratch) {
    val w = size.width
    val h = size.height
    val n = s.lanes.size
    drawRect(if (dark) Color(0xFF26323D) else Color(0xFFDDEBF3))
    val bankW = w * 0.09f
    val x0 = bankW
    val x1 = w - bankW
    val bank = if (dark) Color(0xFF4B6B3C) else Color(0xFF8DBB6A)
    drawRect(bank, Offset.Zero, Size(bankW, h))
    drawRect(bank, Offset(x1, 0f), Size(bankW, h))
    val rowsTop = h * 0.07f
    val rowH = h * 0.80f / n
    val slot = (x1 - x0) / s.spans
    val thick = (rowH * 0.5f).coerceAtMost(h * 0.16f)
    fun yOf(i: Int) = rowsTop + rowH * (i + 0.5f)
    fun frontier(i: Int) = x0 + slot * s.lanes[i].planks
    // plank slots
    for (i in 0 until n) {
        val lane = s.lanes[i]
        val col = TapTugSeatColors[i % 4]
        for (k in 0 until s.spans) {
            val left = x0 + slot * k
            val top = yOf(i) - thick / 2f
            if (k < lane.planks) {
                drawRoundRect(WoodColor, Offset(left + 1.5f * density, top), Size(slot - 3f * density, thick), CornerRadius(3f * density))
                drawRoundRect(WoodDark, Offset(left + 1.5f * density, top), Size(slot - 3f * density, thick), CornerRadius(3f * density), style = Stroke(1.5f * density))
                drawLine(col, Offset(left + slot * 0.2f, yOf(i)), Offset(left + slot * 0.8f, yOf(i)), 3f * density, StrokeCap.Round)
            } else {
                drawRoundRect(ink.copy(alpha = 0.35f), Offset(left + 1.5f * density, top), Size(slot - 3f * density, thick), CornerRadius(3f * density), style = Stroke(1.2f * density))
            }
        }
    }
    // ropes between neighbouring lanes: slack when level, tight at the two-plank limit
    for (i in 0 until n - 1) {
        val diff = kotlin.math.abs(s.lanes[i].planks - s.lanes[i + 1].planks)
        val tension = (diff.toFloat() / BridgeRules.MAX_LEAD).coerceIn(0f, 1f)
        val a = Offset(frontier(i), yOf(i) + thick / 2f)
        val b = Offset(frontier(i + 1), yOf(i + 1) - thick / 2f)
        val sag = (1f - tension) * rowH * 0.7f
        val p = scratch.rope
        p.rewind()
        p.moveTo(a.x, a.y)
        p.quadraticBezierTo((a.x + b.x) / 2f + sag * 0.3f, (a.y + b.y) / 2f + sag, b.x, b.y)
        drawPath(p, if (tension >= 1f) RopeTight else RopeColor, style = Stroke((if (tension >= 1f) 3.5f else 2.2f) * density, cap = StrokeCap.Round))
        if (tension >= 1f) {
            // a tight rope also gets a diamond knot, so it reads without colour
            val m = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            val d = 4f * density
            val k = scratch.knot
            k.rewind()
            k.moveTo(m.x, m.y - d); k.lineTo(m.x + d, m.y); k.lineTo(m.x, m.y + d); k.lineTo(m.x - d, m.y); k.close()
            drawPath(k, RopeTight)
        }
    }
    // the friends: waiting on the near bank, walking over during the crossing
    for (i in 0 until n) {
        val lane = s.lanes[i]
        val cx = x0 - bankW * 0.5f + (x1 + bankW * 0.5f - (x0 - bankW * 0.5f)) * lane.walk
        drawMarble(Offset(cx, yOf(i) - thick * 0.2f), (rowH * 0.34f).coerceAtMost(h * 0.12f), TapTugSeatColors[i % 4], tapTugGlyph(i), glyph = 0.8f)
    }
    // the friendly troll on the far bank
    val tr = (h * 0.14f)
    val tc = Offset(x1 + bankW * 0.5f, h * 0.5f)
    val waving = if (reduced) 0f else sin(2.0 * PI * (s.elapsedMs % 1600L) / 1600.0).toFloat()
    drawCircle(Color(0xFF7C8F6B), tr, tc)
    drawCircle(Color(0xFF55664A), tr, tc, style = Stroke(2f * density))
    drawCircle(Color.White, tr * 0.22f, Offset(tc.x - tr * 0.35f, tc.y - tr * 0.2f))
    drawCircle(Color.White, tr * 0.22f, Offset(tc.x + tr * 0.35f, tc.y - tr * 0.2f))
    drawCircle(Color(0xFF222222), tr * 0.1f, Offset(tc.x - tr * 0.35f, tc.y - tr * 0.2f))
    drawCircle(Color(0xFF222222), tr * 0.1f, Offset(tc.x + tr * 0.35f, tc.y - tr * 0.2f))
    drawArc(Color(0xFF222222), 20f, 140f, false, Offset(tc.x - tr * 0.4f, tc.y - tr * 0.1f), Size(tr * 0.8f, tr * 0.6f), style = Stroke(2f * density, cap = StrokeCap.Round))
    drawLine(Color(0xFF55664A), Offset(tc.x - tr, tc.y), Offset(tc.x - tr * 1.35f, tc.y - tr * (0.5f + 0.3f * waving)), 3f * density, StrokeCap.Round)
    // the water: Reduced Motion draws a flat rect and a few plain lines (no paths at all); otherwise a gentle wave
    val level = s.water.coerceIn(0f, 1f)
    val baseY = h - level * h * 0.98f
    if (reduced) {
        drawRect(WaterColor, Offset(0f, baseY), Size(w, h - baseY))
        var ly = baseY + h * 0.08f
        while (ly < h) {
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, ly), Offset(w, ly), 1.5f * density)
            ly += h * 0.12f
        }
        return
    }
    val amp = h * 0.02f
    val shift = s.elapsedMs / 400.0
    val water = scratch.water
    water.rewind()
    water.moveTo(0f, h)
    var wx = 0f
    while (wx <= w) {
        water.lineTo(wx, baseY + amp * sin(wx / w * 2.0 * PI * 3.0 + shift).toFloat())
        wx += 6f * density
    }
    water.lineTo(w, h)
    water.close()
    drawPath(water, WaterColor)
    // wavy lines too, so the water is a pattern as well as a colour
    val line = scratch.line
    var ly = baseY + h * 0.08f
    while (ly < h) {
        line.rewind()
        line.moveTo(0f, ly)
        var x = 0f
        while (x <= w) {
            line.lineTo(x, ly + amp * 0.5f * sin(x / w * 2.0 * PI * 4.0 + shift).toFloat())
            x += 8f * density
        }
        drawPath(line, Color.White.copy(alpha = 0.35f), style = Stroke(1.5f * density))
        ly += h * 0.12f
    }
}

/** One builder's zone: the name and planks, the sliding cart with its green spot, and the planks laid so far. */
@Composable
private fun BridgeZone(seat: Int, s: BridgeState, reduced: Boolean, dark: Boolean) {
    val lane: BridgeLaneState = s.lanes[seat]
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val gameSpeed = LocalGameSpeed.current
    val color = TapTugSeatColors[seat % 4]
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val phase = s.phaseValue
    var tierShown by remember(seat) { mutableStateOf<String?>(null) }
    val scratch = remember { Path() }
    // TalkBack: where the cart is, as a polite state of the lane ("Cart in the green" / "Cart outside the green"), and
    // for a moment what a plank came out as. The live region is only switched on when touch exploration is, so
    // sighted players' services are not spoken to; the state description itself is always present.
    val touchExploration = remember(ctx) {
        (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    val inGreen = kotlin.math.abs(lane.marker - lane.windowCentre) <= lane.windowWidth / 2f
    val tierWords: String? = when (tierShown) {
        BridgeTier.PERFECT -> stringResource(R.string.bridge_builders_tier_perfect)
        BridgeTier.GOOD -> stringResource(R.string.bridge_builders_tier_good)
        BridgeTier.WOBBLY -> stringResource(R.string.bridge_builders_tier_wobbly)
        BridgeTier.MISS -> stringResource(R.string.bridge_builders_tier_miss)
        else -> null
    }
    val laneState: String = when {
        lane.isBot || lane.status == BridgeLaneStatus.DONE || phase != BridgePhase.BUILD -> ""
        tierWords != null -> tierWords
        lane.status == BridgeLaneStatus.MOVING ->
            stringResource(if (inGreen) R.string.bridge_builders_state_in_green else R.string.bridge_builders_state_outside)
        else -> ""
    }
    LaunchedEffect(lane.tapSerial) {
        if (lane.tapSerial > 0 && lane.lastTier != BridgeTier.NONE) {
            tierShown = lane.lastTier
            delay(gameSpeed.scale(700L))
            tierShown = null
        }
    }
    val waiting = lane.status == BridgeLaneStatus.WAITING
    val done = lane.status == BridgeLaneStatus.DONE
    Column(
        Modifier
            .semantics {
                if (laneState.isNotEmpty()) stateDescription = laneState
                if (touchExploration) liveRegion = LiveRegionMode.Polite
            }
            .fillMaxSize()
            .padding(3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface)
            .drawBehind {
                drawRect(color.copy(alpha = if (dark) 0.16f else 0.12f))
                drawTapTugPattern(seat, Offset.Zero, size, color.copy(alpha = if (dark) 0.22f else 0.16f))
            }
            .padding(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ClSeatBadge(color, tapTugGlyph(seat), 22)
            Text(
                ctx.seatLabel(lane.name) + if (lane.isBot) " " + stringResource(R.string.bridge_builders_robot_tag) else "",
                Modifier.weight(1f).padding(start = 6.dp), color = onSurface, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall
            )
            Text(stringResource(R.string.bridge_builders_planks, lane.planks, lane.spans), color = onSurface, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawLane(lane, seat, color, onSurface, density, waiting || done || phase != BridgePhase.BUILD, scratch) }
            val center: String? = when {
                phase == BridgePhase.COUNTDOWN -> kotlin.math.ceil(s.countdownMs / 1000f).toInt().coerceAtLeast(1).toString()
                s.event == BridgeEvent.GO && s.elapsedMs < 800L -> stringResource(R.string.bridge_builders_go)
                phase == BridgePhase.CROSS || (phase == BridgePhase.OVER && s.won) -> if (lane.walk >= 1f) stringResource(R.string.bridge_builders_zone_across) else null
                done -> stringResource(R.string.bridge_builders_zone_done)
                waiting -> stringResource(R.string.bridge_builders_waiting)
                tierShown == BridgeTier.PERFECT -> stringResource(R.string.bridge_builders_tier_perfect)
                tierShown == BridgeTier.GOOD -> stringResource(R.string.bridge_builders_tier_good)
                tierShown == BridgeTier.WOBBLY -> stringResource(R.string.bridge_builders_tier_wobbly)
                tierShown == BridgeTier.MISS -> stringResource(R.string.bridge_builders_tier_miss)
                else -> null
            }
            if (center != null) {
                Text(
                    center, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** The lane: bar, green spot (frame + stripes + end markers + diamond), the cart, and the plank slots. [dim] greys the cart. */
private fun DrawScope.drawLane(lane: BridgeLaneState, seat: Int, color: Color, ink: Color, density: Float, dim: Boolean, scratch: Path) {
    val w = size.width
    val h = size.height
    val barH = (h * 0.34f).coerceIn(28f * density, 64f * density).coerceAtMost(h * 0.6f)
    val barTop = h * 0.30f - barH / 2f + barH * 0.2f
    val left = barH * 0.5f
    val barW = (w - barH).coerceAtLeast(1f)
    drawRoundRect(ink.copy(alpha = 0.16f), Offset(left - barH * 0.5f, barTop), Size(barW + barH, barH), CornerRadius(barH / 2f))
    // the green spot
    val ww = lane.windowWidth * barW
    val wx = left + (lane.windowCentre - lane.windowWidth / 2f) * barW
    drawRect(SpotGreen.copy(alpha = 0.5f), Offset(wx, barTop), Size(ww, barH))
    drawTapTugPattern(0, Offset(wx, barTop), Size(ww, barH), Color.White.copy(alpha = 0.35f))
    drawRect(SpotEdge, Offset(wx, barTop), Size(ww, barH), style = Stroke(3f * density))
    val tri = 6f * density
    for (side in 0..1) {
        val ex = if (side == 0) wx else wx + ww
        scratch.rewind()
        scratch.moveTo(ex - tri, barTop - tri * 1.4f); scratch.lineTo(ex + tri, barTop - tri * 1.4f); scratch.lineTo(ex, barTop - 1f); scratch.close()
        drawPath(scratch, SpotEdge)
    }
    val cx = wx + ww / 2f
    val cy = barTop + barH / 2f
    val d = barH * 0.2f
    scratch.rewind()
    scratch.moveTo(cx, cy - d); scratch.lineTo(cx + d, cy); scratch.lineTo(cx, cy + d); scratch.lineTo(cx - d, cy); scratch.close()
    drawPath(scratch, SpotEdge.copy(alpha = 0.8f))
    if (lane.widened) drawCircle(SpotEdge, 3f * density, Offset(wx + ww / 2f, barTop + barH + 6f * density))
    // the cart
    val markerX = left + lane.marker * barW
    drawMarble(Offset(markerX, cy), barH * 0.62f, color, tapTugGlyph(seat), glyph = 0.9f, alpha = if (dim) 0.5f else 1f)
    // plank slots
    val slotsTop = h * 0.74f
    val slotH = (h * 0.16f).coerceAtLeast(8f * density)
    val slotW = (w - 8f * density) / lane.spans
    for (k in 0 until lane.spans) {
        val x = 4f * density + slotW * k
        if (k < lane.planks) {
            drawRoundRect(WoodColor, Offset(x + 1f * density, slotsTop), Size(slotW - 2f * density, slotH), CornerRadius(3f * density))
            drawRoundRect(WoodDark, Offset(x + 1f * density, slotsTop), Size(slotW - 2f * density, slotH), CornerRadius(3f * density), style = Stroke(1.5f * density))
        } else {
            drawRoundRect(ink.copy(alpha = 0.4f), Offset(x + 1f * density, slotsTop), Size(slotW - 2f * density, slotH), CornerRadius(3f * density), style = Stroke(1.2f * density))
        }
    }
}
