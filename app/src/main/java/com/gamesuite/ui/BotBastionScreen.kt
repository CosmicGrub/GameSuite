package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.R
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.botbastion.BbArena
import com.gamesuite.games.botbastion.BbEngine
import com.gamesuite.games.botbastion.BbEntry
import com.gamesuite.games.botbastion.BbEvent
import com.gamesuite.games.botbastion.BbHud
import com.gamesuite.games.botbastion.BbMapStore
import com.gamesuite.games.botbastion.BbProfile
import com.gamesuite.games.botbastion.BbResult
import com.gamesuite.games.botbastion.BbRules
import com.gamesuite.games.botbastion.BbStore
import com.gamesuite.games.botbastion.BotBastionGame
import com.gamesuite.games.botbastion.BotStats
import com.gamesuite.games.botbastion.BotType
import com.gamesuite.games.botbastion.EnemyType
import com.gamesuite.games.botbastion.MapDef
import com.gamesuite.games.botbastion.Phase
import com.gamesuite.games.botbastion.SpecialKind
import com.gamesuite.games.botbastion.TargetPriority
import com.gamesuite.games.botbastion.Tier
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import com.gamesuite.uikit.rememberGameFrameClock
import kotlin.math.min
import kotlinx.coroutines.launch

private val MIN_TARGET = 48.dp

private fun mapNameRes(id: String): Int? = when (id) {
    "hearth" -> R.string.bot_bastion_map_hearth
    "twin" -> R.string.bot_bastion_map_twin
    "pebble" -> R.string.bot_bastion_map_pebble
    "cross" -> R.string.bot_bastion_map_cross
    "wake" -> R.string.bot_bastion_map_wake
    "rift" -> R.string.bot_bastion_map_rift
    "pinwheel" -> R.string.bot_bastion_map_pinwheel
    "hourglass" -> R.string.bot_bastion_map_hourglass
    "tidal" -> R.string.bot_bastion_map_tidal
    "lantern" -> R.string.bot_bastion_map_lantern
    "last" -> R.string.bot_bastion_map_last
    else -> null
}

private fun sectorRes(n: Int): Int = when (n) {
    1 -> R.string.bot_bastion_sector_1
    2 -> R.string.bot_bastion_sector_2
    3 -> R.string.bot_bastion_sector_3
    else -> R.string.bot_bastion_sector_4
}

private fun tierRes(t: Tier): Int = when (t) {
    Tier.CADET -> R.string.bot_bastion_tier_cadet
    Tier.CAPTAIN -> R.string.bot_bastion_tier_captain
    Tier.HERO -> R.string.bot_bastion_tier_hero
    Tier.INSANITY -> R.string.bot_bastion_tier_insanity
    Tier.ENDLESS -> R.string.bot_bastion_tier_endless
}

private fun botNameRes(t: BotType): Int = when (t) {
    BotType.PICKET -> R.string.bot_bastion_bot_picket
    BotType.LOBBER -> R.string.bot_bastion_bot_lobber
    BotType.HAZE -> R.string.bot_bastion_bot_haze
    BotType.SKIFF -> R.string.bot_bastion_bot_skiff
    BotType.TENDER -> R.string.bot_bastion_bot_tender
}

private fun botDescRes(t: BotType): Int = when (t) {
    BotType.PICKET -> R.string.bot_bastion_desc_picket
    BotType.LOBBER -> R.string.bot_bastion_desc_lobber
    BotType.HAZE -> R.string.bot_bastion_desc_haze
    BotType.SKIFF -> R.string.bot_bastion_desc_skiff
    BotType.TENDER -> R.string.bot_bastion_desc_tender
}

private fun specialRes(k: SpecialKind): Int = when (k) {
    SpecialKind.SCRAP_SURGE -> R.string.bot_bastion_special_surge
    SpecialKind.SCORE_BOOST -> R.string.bot_bastion_special_boost
    SpecialKind.NOVA_PULSE -> R.string.bot_bastion_special_nova
    SpecialKind.REPAIR_KIT -> R.string.bot_bastion_special_repair
}

private fun prioRes(p: TargetPriority): Int = when (p) {
    TargetPriority.FIRST -> R.string.bot_bastion_prio_first
    TargetPriority.LAST -> R.string.bot_bastion_prio_last
    TargetPriority.WEAKEST -> R.string.bot_bastion_prio_weakest
    TargetPriority.STRONGEST -> R.string.bot_bastion_prio_strongest
    TargetPriority.CLOSEST -> R.string.bot_bastion_prio_closest
}

private fun leakTipRes(t: EnemyType?): Int = when (t) {
    EnemyType.DRIFTER -> R.string.bot_bastion_tip_drifter
    EnemyType.DARTER -> R.string.bot_bastion_tip_darter
    EnemyType.PLATER -> R.string.bot_bastion_tip_plater
    EnemyType.SPLITTER, EnemyType.BIT -> R.string.bot_bastion_tip_splitter
    EnemyType.COURIER -> R.string.bot_bastion_tip_courier
    EnemyType.BEHEMOTH -> R.string.bot_bastion_tip_behemoth
    null -> R.string.bot_bastion_tip_drifter
}

private fun fmt(d: Double): String = if (d >= 100) "%.0f".format(d) else "%.1f".format(d)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotBastionScreen(
    sessionManager: GameSessionManager,
    game: BotBastionGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val reduced = LocalReducedMotion.current
    val maxCrew = if (LocalConfiguration.current.screenWidthDp < 600) 2 else BbRules.MAX_PLAYERS

    val maps = remember { BbMapStore.load(androidContext) }
    val store = remember { BbStore(androidContext) }
    val profile by store.profile.collectAsState(initial = BbProfile())

    var inMenu by remember { mutableStateOf(true) }
    var menuMapId by remember { mutableStateOf(maps.firstOrNull()?.id ?: "hearth") }
    var menuTier by remember { mutableStateOf(Tier.CAPTAIN) }
    var menuCrew by remember { mutableIntStateOf(1) }
    var acting by remember { mutableIntStateOf(0) }
    var selNode by remember { mutableStateOf<Int?>(null) }
    var selBotId by remember { mutableStateOf<Int?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var lastLeak by remember { mutableStateOf<EnemyType?>(null) }
    var announce by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    val fx = remember { ArrayList<BbFx>() }
    val stars = remember { bbStars() }
    val dealtFor = remember { arrayOfNulls<Any>(1) }

    // ---- start / resume ----------------------------------------------------------------------
    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        if (dealtFor[0] == ctx) return@LaunchedEffect
        dealtFor[0] = ctx
        val extras = sessionManager.launchOptions.extras
        game.configure(extras, maps)
        menuTier = game.tier
        menuCrew = game.playerCount.coerceAtMost(maxCrew)
        if (maps.any { it.id == extras["map"] }) menuMapId = game.mapId
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        if (sessionManager.tryResume(game)) inMenu = false
    }

    val engine = game.engine
    val hud = game.hud.value
    val paused = game.paused.value
    val outcome = game.outcome.value

    // ---- turning engine events into effects, sound and haptics -------------------------------------
    fun pump() {
        val e = game.engine ?: return
        for (ev in e.drainEvents()) {
            when (ev) {
                is BbEvent.Shot -> fx += BbFx(BbFx.Kind.SHOT, ev.fx.toFloat(), ev.fy.toFloat(), ev.tx.toFloat(), ev.ty.toFloat(), ev.t, 0.12, BbPalette.bot(ev.type))
                is BbEvent.Blast -> fx += BbFx(if (ev.r > BbArena.W / 2) BbFx.Kind.NOVA else BbFx.Kind.BLAST, ev.x.toFloat(), ev.y.toFloat(), 0f, ev.r.toFloat(), ev.t, 0.35, BbPalette.bot(BotType.LOBBER))
                is BbEvent.Freeze -> fx += BbFx(BbFx.Kind.FREEZE, ev.x.toFloat(), ev.y.toFloat(), 0f, 0f, ev.t, 0.4, Color.White)
                is BbEvent.Kill -> fx += BbFx(BbFx.Kind.KILL, ev.x.toFloat(), ev.y.toFloat(), 0f, 0f, ev.t, 0.4, BbPalette.enemy(ev.type))
                is BbEvent.Leak -> {
                    lastLeak = ev.type
                    fx += BbFx(BbFx.Kind.CORE_HIT, e.map.core.x.toFloat(), e.map.core.y.toFloat(), 0f, 0f, ev.t, 0.5, BbPalette.danger)
                    haptics(HapticSignal.FAILURE); playSfx(SfxKind.INVALID_BUZZ)
                    announce = androidContext.getString(R.string.bot_bastion_announce_core_hit, e.coreHp)
                }
                is BbEvent.CourierEscaped -> lastLeak = EnemyType.COURIER
                is BbEvent.Placed -> { haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.SOLID_THUNK) }
                is BbEvent.Upgraded -> { haptics(HapticSignal.NORMAL_ACTION); playSfx(SfxKind.SUCCESS_CHIME) }
                is BbEvent.Sold -> haptics(HapticSignal.LIGHT_TICK)
                is BbEvent.WaveStart -> {
                    haptics(HapticSignal.LIGHT_TICK)
                    announce = androidContext.getString(R.string.bot_bastion_announce_wave, ev.wave)
                }
                is BbEvent.WaveClear -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.NORMAL_ACTION) }
                is BbEvent.SpecialGained -> { playSfx(SfxKind.SUCCESS_CHIME); haptics(HapticSignal.LIGHT_TICK) }
                is BbEvent.SpecialUsed -> { playSfx(SfxKind.WHOOSH); haptics(HapticSignal.STRONG_ACTION) }
                is BbEvent.Ended -> haptics(if (ev.won) HapticSignal.CELEBRATION else HapticSignal.FAILURE)
                else -> {}
            }
        }
        val now = e.time
        fx.removeAll { now - it.born > it.life + 0.05 || it.born > now + 1.0 }
        while (fx.size > 220) fx.removeAt(0)
    }

    /** Runs a player action, then shows its effects and refreshes the panel at once (works while paused). */
    fun act(block: () -> BbResult?) {
        val r = block()
        pump()
        rev++
        message = when (r) {
            BbResult.NO_SCRAP -> R.string.bot_bastion_why_scrap
            BbResult.CAP -> R.string.bot_bastion_why_cap
            BbResult.TAKEN -> R.string.bot_bastion_why_taken
            else -> null
        }
        if (r != null && r != BbResult.OK) { playSfx(SfxKind.INVALID_BUZZ); haptics(HapticSignal.LIGHT_TICK) }
    }

    // The frame clock. It mutates the engine and draws; it never changes Compose state per frame except the draw counter.
    rememberGameFrameClock(running = !inMenu && engine != null && !paused && outcome == null) { dt ->
        game.tick(dt)
        pump()
    }

    // The finished run goes into the best-of record exactly once.
    LaunchedEffect(outcome) {
        val o = outcome ?: return@LaunchedEffect
        store.update { it.record(game.mapId, game.tier, o, null) }
    }

    val chromeTitle = stringResource(R.string.bot_bastion_help_title)
    val chromeHelp = stringResource(R.string.bot_bastion_help_text)
    GameChrome(
        helpTitle = chromeTitle, helpText = chromeHelp,
        matchInProgress = !inMenu && engine != null && !engine.over,
        onLeave = game::finish,
        onAbort = { game.abortMatch() },
        leaveKeepsProgress = true
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val landscape = this.maxWidth > this.maxHeight
            if (inMenu || engine == null || hud == null) {
                MenuUi(
                    maps = maps, profile = profile, mapId = menuMapId, tier = menuTier, crew = menuCrew, maxCrew = maxCrew,
                    onMap = { menuMapId = it }, onTier = { menuTier = it }, onCrew = { menuCrew = it },
                    onStart = {
                        game.mapId = menuMapId; game.tier = menuTier; game.playerCount = menuCrew
                        game.startMatch()
                        fx.clear(); selNode = null; selBotId = null; acting = 0; lastLeak = null; message = null
                        inMenu = false
                    }
                )
            } else {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    HudRow(hud, engine, game, paused, acting, onAct = { act(it) }, onActing = { acting = it }, rev = rev)
                    if (landscape) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                                val w = min(maxWidth.value, maxHeight.value * 16f / 9f).dp
                                Arena(game, engine, fx, stars, selNode, selBotId, reduced, announce, acting, w,
                                    onNode = { n -> selNode = n; selBotId = engine.botAtNode(n)?.id; rev++ })
                            }
                            Panel(game, engine, hud, selNode, selBotId, acting, rev, Modifier.width(260.dp).fillMaxHeight(),
                                onSelBot = { selBotId = it; selNode = null; rev++ }, onAct = { act(it) }, message = message)
                        }
                    } else {
                        Arena(game, engine, fx, stars, selNode, selBotId, reduced, announce, acting, this@BoxWithConstraints.maxWidth - 16.dp,
                            onNode = { n -> selNode = n; selBotId = engine.botAtNode(n)?.id; rev++ })
                        Panel(game, engine, hud, selNode, selBotId, acting, rev, Modifier.weight(1f).fillMaxWidth(),
                            onSelBot = { selBotId = it; selNode = null; rev++ }, onAct = { act(it) }, message = message)
                    }
                }
                if (outcome != null) {
                    ResultCard(
                        outcome = outcome, game = game, profile = profile, lastLeak = lastLeak, maps = maps,
                        onSave = { initials ->
                            store.update { it.record(game.mapId, game.tier, outcome, BbEntry(initials, outcome.score, outcome.wave, outcome.timeSeconds, outcome.coreLeft, System.currentTimeMillis() / 86_400_000L, outcome.players)) }
                        },
                        onRetry = { fx.clear(); selNode = null; selBotId = null; game.playAgain() },
                        onNext = { id -> game.mapId = id; fx.clear(); selNode = null; selBotId = null; game.playAgain() },
                        onMenu = { game.toMenu(); inMenu = true; menuMapId = game.mapId },
                        onLeave = game::finish
                    )
                }
            }
        }
    }
}

// ================================================================================================
// menu
// ================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuUi(
    maps: List<MapDef>, profile: BbProfile, mapId: String, tier: Tier, crew: Int, maxCrew: Int,
    onMap: (String) -> Unit, onTier: (Tier) -> Unit, onCrew: (Int) -> Unit, onStart: () -> Unit
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = GameChromeEndInset), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bot_bastion_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.bot_bastion_total_stars, profile.totalStars(maps)), fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.bot_bastion_tier_label), fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Tier.values()) FilterChip(selected = tier == t, onClick = { onTier(t) }, label = { Text(stringResource(tierRes(t))) }, modifier = Modifier.heightIn(min = MIN_TARGET))
        }
        Text(stringResource(R.string.bot_bastion_crew_label), fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..maxCrew) FilterChip(selected = crew == n, onClick = { onCrew(n) }, label = { Text("$n") }, modifier = Modifier.heightIn(min = MIN_TARGET))
        }
        Text(stringResource(R.string.bot_bastion_choose_map), fontWeight = FontWeight.Bold)
        for (m in maps) {
            val open = profile.isUnlocked(m, maps)
            val name = mapNameRes(m.id)?.let { stringResource(it) } ?: m.id
            val best = profile.best(m.id, tier)
            val sel = m.id == mapId
            val locked = stringResource(R.string.bot_bastion_map_locked, m.unlockStars)
            val starText = stringResource(R.string.bot_bastion_map_stars, profile.mapStars(m.id))
            val desc = name + ", " + stringResource(sectorRes(m.sector)) + ", " + (if (open) starText else locked)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clickable(enabled = open, role = Role.Button) { onMap(m.id) }
                    .semantics { contentDescription = desc }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(stringResource(sectorRes(m.sector)) + " - " + stringResource(R.string.bot_bastion_map_info, m.lanes.size, m.nodes.size), fontSize = 12.sp)
                    if (open && best.bestWave > 0) Text(stringResource(R.string.bot_bastion_best, best.bestWave, best.bestScore), fontSize = 12.sp)
                }
                Text(if (open) starText else locked, fontSize = 12.sp, modifier = Modifier.widthIn(max = 130.dp))
            }
        }
        val board = profile.board(mapId, tier)
        Text(stringResource(R.string.bot_bastion_boards), fontWeight = FontWeight.Bold)
        if (board.isEmpty()) Text(stringResource(R.string.bot_bastion_no_scores), fontSize = 13.sp)
        board.take(5).forEachIndexed { i, e -> Text(stringResource(R.string.bot_bastion_board_line, i + 1, e.initials, e.score, e.wave), fontSize = 13.sp) }
        Button(onClick = onStart, enabled = maps.isNotEmpty() && maps.firstOrNull { it.id == mapId }?.let { profile.isUnlocked(it, maps) } == true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.bot_bastion_start), fontSize = 18.sp) }
        if (maps.isEmpty()) Text(ctx.getString(R.string.bot_bastion_no_maps))
    }
}

// ================================================================================================
// HUD
// ================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudRow(
    hud: BbHud, engine: BbEngine, game: BotBastionGame, paused: Boolean, acting: Int, rev: Int,
    onAct: (() -> BbResult?) -> Unit, onActing: (Int) -> Unit
) {
    val players = hud.scrap.size
    Column(Modifier.fillMaxWidth().padding(end = GameChromeEndInset)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (hud.goal > 0) stringResource(R.string.bot_bastion_wave, hud.wave, hud.goal) else stringResource(R.string.bot_bastion_wave_endless, hud.wave), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bot_bastion_scrap, hud.scrap.getOrElse(acting) { 0 }), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bot_bastion_score, hud.score) + if (hud.scoreMult > 1) " x${hud.scoreMult}" else "")
            val shield = if (hud.shield > 0) " + " + stringResource(R.string.bot_bastion_shield, hud.shield) else ""
            Text(stringResource(R.string.bot_bastion_core, hud.coreHp) + shield, color = if (hud.coreHp <= 25) BbPalette.danger else MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            if (hud.scrapMult > 1) Text(stringResource(R.string.bot_bastion_scrap_mult, hud.scrapMult))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Pause acts on the very next frame: it only flips a flag, nothing animates or waits.
            OutlinedButton(onClick = { game.togglePause() }, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                Text(stringResource(if (paused) R.string.bot_bastion_resume else R.string.bot_bastion_pause))
            }
            OutlinedButton(onClick = { game.speed.value = if (game.speed.value >= 3) 1 else game.speed.value + 1 }, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                Text(stringResource(R.string.bot_bastion_speed, game.speed.value))
            }
            if (hud.phase == Phase.BUILD) {
                Button(onClick = { onAct { engine.callEarly(acting) } }, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                    Text(if (hud.wave == 0) stringResource(R.string.bot_bastion_start_wave) else stringResource(R.string.bot_bastion_call_early, BbRules.CALL_EARLY_BONUS))
                }
                Text(stringResource(R.string.bot_bastion_next_in, hud.buildSeconds), fontSize = 13.sp)
            }
            if (players > 1) for (p in 0 until players) {
                FilterChip(selected = acting == p, onClick = { onActing(p) }, label = { Text(stringResource(R.string.bot_bastion_player_chip, p + 1, hud.scrap[p])) }, modifier = Modifier.heightIn(min = MIN_TARGET))
            }
        }
        val pv = hud.preview
        Text(
            stringResource(R.string.bot_bastion_next_card, pv.wave, pv.total) +
                (if (pv.boss) " - " + stringResource(R.string.bot_bastion_boss_warning) else "") +
                (if (pv.swarmAfter) " - " + stringResource(R.string.bot_bastion_swarm_warning) else ""),
            fontSize = 12.sp
        )
        if (paused) Text(stringResource(R.string.bot_bastion_paused_banner), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

// ================================================================================================
// arena: one Canvas driven by the frame counter, plus invisible 48 dp node buttons for touch and TalkBack
// ================================================================================================

@Composable
private fun Arena(
    game: BotBastionGame, engine: BbEngine, fx: List<BbFx>, stars: List<Triple<Float, Float, Float>>,
    selNode: Int?, selBotId: Int?, reduced: Boolean, announce: String, acting: Int, width: androidx.compose.ui.unit.Dp,
    onNode: (Int) -> Unit
) {
    val height = width * 9f / 16f
    val hud = game.hud.value
    val summary = stringResource(R.string.bot_bastion_a11y_summary, hud?.wave ?: 0, hud?.coreHp ?: 0, hud?.scrap?.firstOrNull() ?: 0)
    val arenaLabel = stringResource(R.string.bot_bastion_arena)
    Box(
        Modifier.size(width, height)
            .semantics { contentDescription = "$arenaLabel. $summary. $announce"; liveRegion = LiveRegionMode.Polite }
            .pointerInput(acting, engine) {
                // dragging steers the acting pilot (players 2 to 4); a plain tap is handled by the node buttons
                if (acting > 0) detectDragGestures(
                    onDragStart = { o -> engine.setPilotTarget(acting, o.x / size.width * BbArena.W, o.y / size.height * BbArena.H) },
                    onDrag = { ch, _ -> engine.setPilotTarget(acting, ch.position.x / size.width * BbArena.W, ch.position.y / size.height * BbArena.H) }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            game.frame.longValue // the only per-frame read: invalidates this draw, nothing else
            val scale = size.width / BbArena.W.toFloat()
            drawBbBackdrop(stars)
            drawBbMap(engine.map, scale)
            for ((i, n) in engine.map.nodes.withIndex()) drawBbNode(Offset(n.x.toFloat() * scale, n.y.toFloat() * scale), scale, selNode == i, engine.botAtNode(i) != null)
            val now = engine.time
            val flash = (1f - ((now - (fx.lastOrNull { it.kind == BbFx.Kind.CORE_HIT }?.born ?: -9.0)) / 0.5).toFloat()).coerceIn(0f, 1f)
            drawBbCore(engine, scale, now, if (reduced) 0f else flash)
            for (b in engine.bots) drawBbBot(b, scale, selBotId == b.id, now)
            for (e in engine.enemies) if (!e.dead) drawBbEnemy(e, scale)
            for (p in engine.pilots) if (!p.down) {
                val c = Offset(p.x.toFloat() * scale, p.y.toFloat() * scale)
                drawCircle(BbPalette.players[p.pid.coerceIn(0, 3)], 0.3f * scale, c)
                drawCircle(BbPalette.space, 0.12f * scale, c)
            }
            drawBbFx(fx, now, scale, reduced)
        }
        val box = bbNodeTapBoxDp(width.value, BbArena.W).dp
        val nodes = engine.map.nodes
        for ((i, n) in nodes.withIndex()) {
            val bot = engine.botAtNode(i)
            val label = if (bot == null) stringResource(R.string.bot_bastion_node_empty, i + 1, nodes.size)
            else stringResource(R.string.bot_bastion_node_bot, i + 1, nodes.size, stringResource(botNameRes(bot.type)), bot.level)
            val cx = (n.x / BbArena.W * width.value).dp
            val cy = (n.y / BbArena.H * height.value).dp
            Box(
                Modifier.offset { IntOffset((cx - box / 2).roundToPx(), (cy - box / 2).roundToPx()) }
                    .size(box)
                    .clickable(role = Role.Button, onClickLabel = label) { onNode(i) }
                    .semantics { contentDescription = label }
            )
        }
    }
}

// ================================================================================================
// side panel: build cards, upgrade panel, Aegis, Skiffs, specials
// ================================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panel(
    game: BotBastionGame, engine: BbEngine, hud: BbHud, selNode: Int?, selBotId: Int?, acting: Int, rev: Int, modifier: Modifier,
    onSelBot: (Int) -> Unit, onAct: (() -> BbResult?) -> Unit, message: Int?
) {
    rev.let { }
    val scrap = hud.scrap.getOrElse(acting) { 0 }
    val bot = selBotId?.let { engine.bot(it) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message != null) Text(stringResource(message), color = BbPalette.danger, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (bot != null) {
            UpgradePanel(engine, bot, acting, scrap, onAct, game)
        } else if (selNode != null) {
            Text(stringResource(R.string.bot_bastion_build_here), fontWeight = FontWeight.Bold)
            for (t in listOf(BotType.PICKET, BotType.LOBBER, BotType.HAZE, BotType.TENDER)) {
                val price = engine.price(t)
                val name = stringResource(botNameRes(t))
                val buyDesc = stringResource(R.string.bot_bastion_buy_desc, price)
                Button(
                    onClick = { onAct { engine.place(acting, t, selNode) } },
                    enabled = scrap >= price,
                    colors = ButtonDefaults.buttonColors(containerColor = BbPalette.bot(t).copy(alpha = 0.85f), contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .semantics { contentDescription = "$name, $buyDesc" }
                ) {
                    Column {
                        Text("$name  -  $price", fontWeight = FontWeight.Bold)
                        Text(stringResource(botDescRes(t)), fontSize = 11.sp)
                    }
                }
            }
        } else {
            Text(stringResource(R.string.bot_bastion_tap_node), fontSize = 13.sp)
        }

        Text(stringResource(R.string.bot_bastion_core_section), fontWeight = FontWeight.Bold)
        val aegis = hud.aegisPrice
        Button(onClick = { onAct { engine.buyAegis(acting) } }, enabled = scrap >= aegis && engine.aegisBought < BbRules.AEGIS_MAX_BUYS,
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_aegis, BbRules.SHIELD_PER_AEGIS, aegis)) }
        val skiffPrice = engine.price(BotType.SKIFF)
        Button(onClick = { onAct { engine.buySkiff(acting) } }, enabled = scrap >= skiffPrice,
            colors = ButtonDefaults.buttonColors(containerColor = BbPalette.bot(BotType.SKIFF).copy(alpha = 0.85f), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_buy_skiff, skiffPrice)) }
        val skiffs = engine.bots.filter { it.type == BotType.SKIFF }
        if (skiffs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, s) in skiffs.withIndex()) FilterChip(selected = selBotId == s.id, onClick = { onSelBot(s.id) },
                label = { Text(stringResource(R.string.bot_bastion_skiff_chip, i + 1, s.level)) }, modifier = Modifier.heightIn(min = MIN_TARGET))
        }

        Text(stringResource(R.string.bot_bastion_specials), fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in SpecialKind.values()) {
                val n = hud.specials[k.ordinal]
                OutlinedButton(onClick = { onAct { engine.useSpecial(acting, k) } }, enabled = n > 0, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                    Text(stringResource(R.string.bot_bastion_special_count, stringResource(specialRes(k)), n))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpgradePanel(
    engine: BbEngine, bot: com.gamesuite.games.botbastion.BbBot, acting: Int, scrap: Int, onAct: (() -> BbResult?) -> Unit, game: BotBastionGame
) {
    val t = bot.type
    Text(stringResource(botNameRes(t)) + " - " + stringResource(R.string.bot_bastion_level, bot.level), fontWeight = FontWeight.Bold, color = BbPalette.bot(t))
    val l = bot.level
    val n = (l + 1).coerceAtMost(BotStats.MAX_LEVEL)
    if (t == BotType.TENDER) {
        Text(stringResource(R.string.bot_bastion_stat_slow, "${(BotStats.tenderSlow(l) * 100).toInt()}%", "${(BotStats.tenderSlow(n) * 100).toInt()}%"), fontSize = 13.sp)
        Text(stringResource(R.string.bot_bastion_stat_freeze, fmt(BotStats.tenderFreezeEvery(l)), fmt(BotStats.tenderFreezeEvery(n))), fontSize = 13.sp)
    } else {
        Text(stringResource(R.string.bot_bastion_stat_power, fmt(BotStats.power(t, l)), fmt(BotStats.power(t, n))), fontSize = 13.sp)
        Text(stringResource(R.string.bot_bastion_stat_rate, fmt(BotStats.rate(t, l)), fmt(BotStats.rate(t, n))), fontSize = 13.sp)
    }
    Text(stringResource(R.string.bot_bastion_stat_range, fmt(BotStats.rangeU(t, l)), fmt(BotStats.rangeU(t, n))), fontSize = 13.sp)
    val price = engine.upgradePrice(bot)
    if (price == null) Text(stringResource(R.string.bot_bastion_max_level), fontWeight = FontWeight.Bold)
    else Button(onClick = { onAct { engine.upgrade(acting, bot.id) } }, enabled = scrap >= price, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) {
        Text(stringResource(R.string.bot_bastion_upgrade, l + 1, price))
    }
    if (bot.owner == acting) OutlinedButton(onClick = { onAct { engine.sell(acting, bot.id) } }, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) {
        Text(stringResource(R.string.bot_bastion_sell, engine.sellValue(bot)))
    }
    if (t.deals) {
        Text(stringResource(R.string.bot_bastion_prio_label), fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in TargetPriority.values()) FilterChip(selected = bot.priority == p, onClick = { onAct { game.setPriority(bot.id, p) } },
                label = { Text(stringResource(prioRes(p)), fontSize = 12.sp) }, modifier = Modifier.heightIn(min = MIN_TARGET))
        }
    }
}

// ================================================================================================
// result card
// ================================================================================================

@Composable
private fun ResultCard(
    outcome: com.gamesuite.games.botbastion.BbOutcome, game: BotBastionGame, profile: BbProfile, lastLeak: EnemyType?, maps: List<MapDef>,
    onSave: suspend (String) -> Unit, onRetry: () -> Unit, onNext: (String) -> Unit, onMenu: () -> Unit, onLeave: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var initials by remember(outcome) { mutableStateOf("") }
    var saved by remember(outcome) { mutableStateOf(false) }
    val tier = game.tier
    val wantsBoard = outcome.won || tier.endless
    val candidate = BbEntry("AAA", outcome.score, outcome.wave, outcome.timeSeconds, outcome.coreLeft, 0, outcome.players)
    val qualifies = wantsBoard && profile.qualifies(game.mapId, tier, candidate)
    val idx = maps.indexOfFirst { it.id == game.mapId }
    val next = maps.getOrNull(idx + 1)?.takeIf { outcome.won && profile.isUnlocked(it, maps) }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(16.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(18.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(if (outcome.won) R.string.bot_bastion_victory else R.string.bot_bastion_defeat), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (outcome.won) {
                val label = stringResource(R.string.bot_bastion_stars_earned, outcome.stars)
                Text("*".repeat(outcome.stars) + "-".repeat(3 - outcome.stars), fontSize = 28.sp, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(stringResource(R.string.bot_bastion_result_wave, outcome.wave))
            Text(stringResource(R.string.bot_bastion_score, outcome.score))
            Text(stringResource(R.string.bot_bastion_result_core, outcome.coreLeft))
            if (!outcome.won) Text(stringResource(leakTipRes(lastLeak)), fontSize = 13.sp)
            if (qualifies && !saved) {
                OutlinedTextField(value = initials, onValueChange = { initials = it.filter { c -> c.isLetter() }.take(3).uppercase() },
                    label = { Text(stringResource(R.string.bot_bastion_initials_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { saved = true; scope.launch { onSave(initials) } }, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_save_score)) }
            } else if (saved) Text(stringResource(R.string.bot_bastion_saved))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_retry)) }
            if (next != null) OutlinedButton(onClick = { onNext(next.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_next_map)) }
            OutlinedButton(onClick = onMenu, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_back_to_maps)) }
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.bot_bastion_leave)) }
        }
    }
}
