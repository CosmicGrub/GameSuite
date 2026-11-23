package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import android.content.res.Configuration
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import com.gamesuite.core.DailySeed
import com.gamesuite.games.towerdefence.TdDaily
import com.gamesuite.games.towerdefence.TdLayoutKind
import com.gamesuite.games.towerdefence.TdLayoutMath
import com.gamesuite.games.towerdefence.TdMode
import com.gamesuite.games.towerdefence.TdModifier
import com.gamesuite.games.towerdefence.TdStatsKeys
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.MusicProfiles
import com.gamesuite.audio.SfxKind
import com.gamesuite.audio.rememberAmbientMusic
import com.gamesuite.audio.rememberProceduralSfx
import com.gamesuite.core.GameSessionManager
import com.gamesuite.games.towerdefence.TdBoardMath
import com.gamesuite.games.towerdefence.TdBranch
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdGrid
import com.gamesuite.games.towerdefence.TdPlaceResult
import com.gamesuite.games.towerdefence.TdRole
import com.gamesuite.games.towerdefence.TdRules
import com.gamesuite.games.towerdefence.TdScan
import com.gamesuite.games.towerdefence.TdTarget
import com.gamesuite.games.towerdefence.TdWaves
import com.gamesuite.games.towerdefence.TowerDefenceGame
import com.gamesuite.games.towerdefence.TowerDefenceStatsStore
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.rememberHaptics
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalMusicEnabled
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.settings.SettingsViewModel
import com.gamesuite.ui.effects.cameraShake
import com.gamesuite.ui.effects.rememberCameraShake
import com.gamesuite.ui.effects.rememberParticleBurst
import com.gamesuite.ui.effects.victoryGlow
import com.gamesuite.uikit.GameChrome
import com.gamesuite.uikit.GameChromeEndInset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Tower Defence screen (docs/design/tower-defence/). Two kinds of map share it:
 *  - open "Castle Siege" maps: a 24 x 14 tile field, build anywhere buildable, flow-field enemies,
 *    four roles, branches, sell, move, targeting, a castle with health on the right edge;
 *  - the three Classic fixed-lane maps: the shipped path and 8 spots, themed.
 *
 * ONE CANVAS, NO PER-FRAME RECOMPOSITION. The previous screen read `game.state` in the body, so the
 * whole screen recomposed every frame. Here the body reads only [derived] values (gold, castle,
 * wave, paused ...) that change rarely; the board's single Canvas and the backdrop Canvas read
 * `game.state.value`, the ambient clock, the ghost and the particles inside their draw lambdas, so a
 * frame invalidates draw only. The frame clock steps the engine and advances the ambient clock.
 *
 * INTERACTION: pick a tower card, tap the field to see the ghost (green check or red cross, the
 * rule's reason in words and "route stays open +N tiles"), tap it again to build; or press, drag
 * (the ghost rides 56dp above the finger) and release. Tap a tower to select it (panel: targeting,
 * upgrade or branch, sell, move); long-press shows a radial menu. Building while paused is allowed.
 *
 * ACCESSIBILITY: 48dp+ targets, every control labelled, shapes (not hue) carry meaning, colour-blind
 * palette, Reduced Motion freezes ambience and shake. With TalkBack on, the field exposes a 14 x 8
 * grid of cells and each tower as a node with custom actions. A one-switch scan (column, row,
 * tower) is available from the setup dialog.
 *
 * THEMES are skins only ([TdSkin]); changing one is cosmetic and allowed between waves.
 */
@Composable
fun TowerDefenceScreen(
    sessionManager: GameSessionManager,
    game: TowerDefenceGame,
    settingsViewModel: SettingsViewModel,
    onMatchEnded: () -> Unit
) {
    val context by sessionManager.activeContext.collectAsState()
    val androidContext = LocalContext.current
    val haptics = rememberHaptics()
    val playSfx = rememberProceduralSfx()
    val musicEnabled = LocalMusicEnabled.current
    rememberAmbientMusic(profile = MusicProfiles.TOWER_DEFENCE, enabled = musicEnabled)
    val statsStore = remember { TowerDefenceStatsStore(androidContext) }
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val cameraShake = rememberCameraShake()
    val particleBurst = rememberParticleBurst()
    val density = LocalDensity.current
    val shakeMagnitudePx = with(density) { 8.dp.toPx() }
    val dragOffsetPx = with(density) { 56.dp.toPx() }
    val extras = sessionManager.launchOptions.extras
    val launchDifficulty = sessionManager.launchOptions.cpuDifficulty

    // ---- setup state ---------------------------------------------------------------------------
    var themeKey by rememberSaveable { mutableStateOf(extras["theme"] ?: TdThemeId.CASTLE.key) }
    val skin = TdSkins.of(TdThemeId.fromKey(themeKey))
    var showRoute by rememberSaveable { mutableStateOf(true) }
    var oneSwitch by rememberSaveable { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    // Snap off = free placement: the tower follows the pointer to the cell instead of to the tile.
    var freePlacement by rememberSaveable { mutableStateOf(false) }
    val snap = !freePlacement
    val motion = when {
        reducedMotion -> TdMotion.OFF
        !settings.enhancedAnimationsEnabled -> TdMotion.CALM
        else -> TdMotion.STANDARD
    }

    LaunchedEffect(context) {
        val ctx = context ?: return@LaunchedEffect
        game.difficulty = launchDifficulty ?: settings.defaultCpuDifficulty
        game.gentle = extras["gentle"] == "true"
        game.level = TowerDefenceGame.levelById(extras["map"] ?: "castle-pass") ?: TowerDefenceGame.OPEN_LEVELS.first()
        game.mode = when (extras["mode"]) { "endless" -> TdMode.ENDLESS; "daily" -> TdMode.DAILY; else -> TdMode.CAMPAIGN }
        game.themeCount = TdSkins.ALL.size
        game.dailyDay = DailySeed.todayEpochDay()
        game.init(ctx)
        game.setOnMatchEnd { result ->
            sessionManager.endActiveGame(result)
            onMatchEnded()
        }
        // Hub Resume: put the saved build phase back; otherwise (or if the save fails validation) start fresh.
        if (!sessionManager.tryResume(game)) game.startMatch()
        // A Daily siege picks its own theme from the seed.
        game.dailyPlan()?.let { themeKey = TdSkins.ALL[it.themeIndex.coerceIn(0, TdSkins.ALL.lastIndex)].id.key }
    }

    val matchOver by game.matchOver
    val finishedRuns by game.runsFinished
    if (matchOver) return

    // ---- derived (rarely changing) game values: the body never reads game.state directly ----------
    val gold by derived { game.state.value.gold }
    val castle by derived { game.state.value.lives }
    val castleMax by derived { game.state.value.castleMax }
    val wave by derived { game.state.value.waveNumber }
    val totalWaves by derived { game.state.value.totalWaves }
    val paused by derived { game.state.value.paused }
    val runOver by derived { game.state.value.runOver }
    val won by derived { game.state.value.runResult == TowerDefenceGame.TowerDefenceRunResult.WON }
    val runSeq by derived { game.state.value.runSeq }
    val isOpen by derived { game.state.value.level.isOpen }
    val levelId by derived { game.state.value.level.id }
    val difficulty by derived { game.state.value.difficulty }
    val gentle by derived { game.state.value.gentle }
    val mode by derived { game.state.value.mode }
    val endless = mode == TdMode.ENDLESS
    val dailyDay by derived { game.state.value.dailyDay }
    val dailyMod by derived { game.state.value.modifier }
    val waveInProgress by derived { game.state.value.waveInProgress }
    val countdown by derived { if (game.state.value.waveInProgress) 0 else Math.ceil(game.state.value.interWaveCooldown.toDouble()).toInt() }
    val enemyCount by derived { game.state.value.enemies.size }
    val deathSeq by derived { game.state.value.lastEnemyDeath?.seq }
    val hitSeq by derived { game.state.value.lastCastleHit?.seq }
    val nextPreview by derived { val s = game.state.value; if (s.runOver) emptyList() else game.nextWavePreview() }
    val nextIsBoss by derived { val s = game.state.value; TdWaves.hasBoss(if (s.waveInProgress) s.waveNumber + 1 else s.waveNumber) }

    val bestWaves by statsStore.bestWaves.collectAsState(initial = emptyMap())
    val statKey = TdStatsKeys.keyFor(mode, levelId, difficulty, gentle, dailyDay)
    val bestWave = bestWaves[statKey]
    var recordedForRunSeq by remember { mutableStateOf(-1) }
    var reportedNewBest by remember(runSeq) { mutableStateOf(false) }

    // ---- interaction state ---------------------------------------------------------------------------
    var armed by remember(runSeq) { mutableStateOf<TdRole?>(null) }
    var ghost by remember(runSeq) { mutableStateOf<TdGhost?>(null) }
    var selectedId by remember(runSeq) { mutableStateOf<Int?>(null) }
    var moving by remember(runSeq) { mutableStateOf(false) }
    var radialId by remember(runSeq) { mutableStateOf<Int?>(null) }
    var selectedZone by remember(runSeq) { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<TdScan?>(null) }
    var autoPaused by remember { mutableStateOf(false) }
    var pendingSetup by remember { mutableStateOf<(() -> Unit)?>(null) }
    val armedState = rememberUpdatedState(armed)
    val ghostState = rememberUpdatedState(ghost)
    val selectedState = rememberUpdatedState(selectedId)
    val showRouteState = rememberUpdatedState(showRoute)
    val scanState = rememberUpdatedState(scan)
    val zoneState = rememberUpdatedState(selectedZone)

    LaunchedEffect(toast) {
        if (toast != null) { delay(2600); toast = null }
    }

    fun autoPause() {
        val s = game.state.value
        if (game.matchOver.value || s.runOver) return
        if (!s.paused) autoPaused = true
        game.pause()
    }
    LaunchedEffect(windowFocused) { if (!windowFocused) autoPause() }
    LaunchedEffect(paused) { if (!paused) autoPaused = false }

    // The simulation and ambient clock. Both advance in this one callback; nothing here recomposes.
    val ambientClock = remember { mutableFloatStateOf(0f) }
    com.gamesuite.uikit.rememberGameFrameClock(running = !paused && !runOver) { dt ->
        val s = game.state.value
        if (dt > 0f && !game.matchOver.value && !s.paused && !s.runOver) {
            game.tick(dt)
            if (motion != TdMotion.OFF) ambientClock.floatValue += dt
        }
    }

    // ---- events: castle hit, kills, waves, results -------------------------------------------------------
    LaunchedEffect(hitSeq) {
        if (hitSeq == null) return@LaunchedEffect
        haptics(HapticSignal.FAILURE)
        playSfx(SfxKind.INVALID_BUZZ)
        if (!reducedMotion && !skin.kidFirst) cameraShake.trigger()
    }
    LaunchedEffect(deathSeq) {
        val death = game.state.value.lastEnemyDeath ?: return@LaunchedEffect
        if (reducedMotion) return@LaunchedEffect
        val level = game.state.value.level
        val origin = if (level.isOpen) Offset(death.position.x / TdGrid.TILES_X, death.position.y / TdGrid.TILES_Y) else death.position
        particleBurst.spawn(
            origin = origin, count = 8,
            colors = listOf(skin.accent, skin.projectile),
            speedRange = 0.04f..0.09f, lifeRangeSeconds = 0.3f..0.45f, gravity = 0.12f
        )
    }
    var previousWave by remember(runSeq) { mutableStateOf(wave) }
    LaunchedEffect(wave) {
        if (wave > previousWave) { haptics(HapticSignal.LIGHT_TICK); playSfx(SfxKind.SUCCESS_CHIME) }
        previousWave = wave
    }
    LaunchedEffect(runOver, runSeq) {
        if (runOver && recordedForRunSeq != runSeq) {
            recordedForRunSeq = runSeq
            val s = game.state.value
            val key = TdStatsKeys.keyFor(s.mode, s.level.id, s.difficulty, s.gentle, s.dailyDay)
            val value = if (s.mode == TdMode.DAILY) TdDaily.score(s.waveNumber, s.lives) else s.waveNumber
            reportedNewBest = statsStore.recordBest(key, value)
            if (won) { haptics(HapticSignal.CELEBRATION); playSfx(SfxKind.SUCCESS_CHIME) } else haptics(HapticSignal.FAILURE)
        }
    }

    val setupIsFree by derived { val s = game.state.value; s.runOver || (s.waveNumber == 1 && s.towers.isEmpty() && !s.waveInProgress) }
    fun requestSetup(apply: () -> Unit) {
        if (game.matchOver.value) return
        if (setupIsFree) apply() else pendingSetup = apply
    }

    // ---- placement actions -------------------------------------------------------------------------------------
    fun feedback(ok: Boolean) {
        if (ok) { haptics(HapticSignal.SUCCESS); playSfx(SfxKind.SOLID_THUNK) } else { haptics(HapticSignal.FAILURE); playSfx(SfxKind.INVALID_BUZZ) }
    }

    fun refreshGhost(cx: Int, cy: Int) {
        val role = armed ?: return
        val movingId = if (moving) selectedId else null
        val res = if (movingId != null) game.previewMove(movingId, cx, cy) else game.previewPlacement(cx, cy, role)
        ghost = TdGhost(role, cx, cy, res, moving)
    }

    var rejectText by remember { mutableStateOf<TdPlaceResult?>(null) }
    fun commitGhost() {
        val g = ghost ?: return
        val movingId = if (g.moving) selectedId else null
        val res = if (movingId != null) game.moveTower(movingId, g.cx, g.cy) else game.placeTower(g.cx, g.cy, g.role)
        if (res is TdPlaceResult.Ok) {
            feedback(true)
            ghost = null
            if (g.moving) { moving = false; armed = null }
        } else {
            feedback(false)
            rejectText = res
            ghost = g.copy(result = res)
        }
    }

    fun cancelPlacing() { armed = null; ghost = null; moving = false; rejectText = null }

    fun selectTower(id: Int?) {
        selectedId = id
        radialId = null
        if (id != null) { armed = null; ghost = null; moving = false }
    }

    fun startMove() {
        val id = selectedId ?: return
        val tw = game.state.value.towers.firstOrNull { it.id == id } ?: return
        if (moving) { cancelPlacing(); return }
        moving = true
        armed = tw.role
        ghost = null
        radialId = null
    }

    fun cycleTarget(id: Int) {
        val tw = game.state.value.towers.firstOrNull { it.id == id } ?: return
        val next = TdTarget.entries[(TdTarget.entries.indexOf(tw.target) + 1) % TdTarget.entries.size]
        game.setTargeting(id, next)
        haptics(HapticSignal.LIGHT_TICK)
    }

    fun upgrade(id: Int, branch: TdBranch) {
        val before = game.state.value.gold
        game.upgradeTower(id, branch)
        feedback(game.state.value.gold < before)
    }

    fun sell(id: Int) {
        val refund = game.sellTower(id)
        if (refund >= 0) {
            feedback(true)
            toast = androidContext.getString(R.string.td_sold, refund)
            selectedId = null
            radialId = null
            cancelPlacing()
        }
    }

    fun placeAtCell(col: Int, row: Int, role: TdRole) {
        val (ax, ay) = TdBoardMath.anchorOfA11yCell(col, row)
        val res = game.placeTower(ax, ay, role)
        if (res is TdPlaceResult.Ok) feedback(true) else { feedback(false); rejectText = res }
    }

    // One-switch scan: a timer steps the band; Select locks the column, then the row, then builds.
    LaunchedEffect(oneSwitch, paused, runOver) {
        if (!oneSwitch || runOver) { scan = null; return@LaunchedEffect }
        if (scan == null) scan = TdScan()
        while (true) {
            delay(800)
            scan = scan?.step()
        }
    }

    val a11yManager = remember { androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var a11yOn by remember { mutableStateOf(a11yManager?.isTouchExplorationEnabled == true) }
    DisposableEffect(a11yManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { a11yOn = it }
        a11yManager?.addTouchExplorationStateChangeListener(listener)
        onDispose { a11yManager?.removeTouchExplorationStateChangeListener(listener) }
    }

    // The selected tower as a lightweight view: changes only when something the panel shows changes.
    val selectedView by derived {
        val id = selectedId
        val s = game.state.value
        val tw = id?.let { i -> s.towers.firstOrNull { it.id == i } }
        if (tw == null || !s.level.isOpen) null else TdTowerView(
            id = tw.id, role = tw.role, level = tw.upgradeLevel, branch = tw.branch, target = tw.target,
            sellValue = game.sellValue(tw),
            upgradeCost = if (tw.upgradeLevel >= TdRules.MAX_LEVEL) 0 else TdRules.upgradeCost(tw.role, tw.upgradeLevel),
            needsBranch = TdRules.needsBranch(tw.upgradeLevel, tw.branch),
            moveFee = game.moveFee(tw)
        )
    }
    LaunchedEffect(selectedView == null) { if (selectedView == null && selectedId != null && !moving) selectedId = null }

    // Only read when TalkBack is on (derived values are lazy): towers as plain views, and the route cells.
    val towerViews by derived { game.state.value.towers.map { TdA11yTower(it.id, it.cx, it.cy, it.role, it.upgradeLevel, it.branch, it.target) } }
    val routeCells by derived {
        game.routePreview().flatten()
            .map { TdBoardMath.a11yCellOf(((it.x * 2f).toInt() - 1).coerceAtLeast(0), ((it.y * 2f).toInt() - 1).coerceAtLeast(0)) }.toSet()
    }

    // Classic spot: the selected zone and what is built there.
    val zoneLevel by derived { val z = selectedZone; if (z == null) null else game.state.value.towers.firstOrNull { it.zoneIndex == z }?.upgradeLevel }

    // ---- keyboard / D-pad -------------------------------------------------------------------------------------------
    val boardFocus = remember { FocusRequester() }
    var boardFocused by remember { mutableStateOf(false) }
    var cursorPos by remember(runSeq) { mutableStateOf((TdGrid.COLS / 2) to (TdGrid.ROWS / 2)) }
    val cursorDraw = remember { derivedStateOf { if (boardFocused) cursorPos else null } }
    val hardwareKeys = remember {
        val c = androidContext.resources.configuration
        c.keyboard != Configuration.KEYBOARD_NOKEYS || c.navigation == Configuration.NAVIGATION_DPAD ||
            c.navigation == Configuration.NAVIGATION_TRACKBALL || c.navigation == Configuration.NAVIGATION_WHEEL
    }
    LaunchedEffect(hardwareKeys, isOpen) { if (hardwareKeys && isOpen) runCatching { boardFocus.requestFocus() } }

    fun toggleArm(role: TdRole) {
        if (armed == role && !moving) { cancelPlacing(); return }
        selectedId = null; moving = false; ghost = null; rejectText = null; armed = role
        if (boardFocused) refreshGhost(cursorPos.first, cursorPos.second)
    }

    fun moveCursor(dx: Int, dy: Int) {
        val n = TdBoardMath.moveAnchor(cursorPos.first, cursorPos.second, dx, dy, snap)
        cursorPos = n
        if (armed != null && !runOver) refreshGhost(n.first, n.second)
        haptics(HapticSignal.LIGHT_TICK)
    }

    fun activateCursor() {
        if (runOver || !isOpen) return
        val (ax, ay) = cursorPos
        if (armed != null) {
            val g = ghost
            if (g != null && g.cx == ax && g.cy == ay && g.valid) commitGhost() else refreshGhost(ax, ay)
        } else {
            val hit = game.state.value.towers.firstOrNull { kotlin.math.abs(it.cx - ax) <= 1 && kotlin.math.abs(it.cy - ay) <= 1 }?.id
            selectTower(hit)
        }
    }

    fun onBoardKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when (e.key) {
            Key.DirectionLeft -> { moveCursor(-1, 0); true }
            Key.DirectionRight -> { moveCursor(1, 0); true }
            Key.DirectionUp -> { moveCursor(0, -1); true }
            Key.DirectionDown -> { moveCursor(0, 1); true }
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> { activateCursor(); true }
            else -> false
        }
    }

    fun onGlobalKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || game.matchOver.value) return false
        val sv = selectedView
        return when (e.key) {
            Key.One -> { if (isOpen && !runOver) toggleArm(TdRole.BOLT); true }
            Key.Two -> { if (isOpen && !runOver) toggleArm(TdRole.BLAST); true }
            Key.Three -> { if (isOpen && !runOver) toggleArm(TdRole.CONTROL); true }
            Key.Four -> { if (isOpen && !runOver) toggleArm(TdRole.SUPPORT); true }
            Key.Escape -> {
                if (armed != null || ghost != null || selectedId != null) { selectTower(null); cancelPlacing(); true } else false
            }
            Key.U -> if (sv != null) { upgrade(sv.id, if (sv.needsBranch) TdBranch.A else TdBranch.NONE); true } else false
            Key.I -> if (sv != null && sv.needsBranch) { upgrade(sv.id, TdBranch.B); true } else false
            Key.S -> if (sv != null) { sell(sv.id); true } else false
            Key.M -> if (sv != null) { startMove(); true } else false
            Key.T -> if (sv != null) { cycleTarget(sv.id); true } else false
            Key.P -> { game.togglePause(); true }
            Key.N -> { game.callNextWave(); true }
            else -> false
        }
    }

    // Drag from a tray card: the finger arrives in root coordinates.
    var viewRoot by remember { mutableStateOf(Offset.Zero) }
    var viewSizePx by remember { mutableStateOf(IntSize.Zero) }
    var contentRoot by remember { mutableStateOf(Offset.Zero) }
    var contentSizePx by remember { mutableStateOf(IntSize.Zero) }
    fun trayDrag(role: TdRole, root: Offset) {
        if (armed != role || moving) { selectedId = null; moving = false; rejectText = null; armed = role }
        val inView = root.x >= viewRoot.x && root.x <= viewRoot.x + viewSizePx.width && root.y >= viewRoot.y && root.y <= viewRoot.y + viewSizePx.height
        val a = if (!inView) null else TdBoardMath.anchorForDrag(
            root.x, root.y, contentRoot.x, contentRoot.y, contentSizePx.width.toFloat(), contentSizePx.height.toFloat(), dragOffsetPx, snap
        )
        if (a == null) {
            ghost = null
        } else {
            val g = ghost
            if (g == null || g.cx != a.first || g.cy != a.second) { refreshGhost(a.first, a.second); haptics(HapticSignal.LIGHT_TICK) }
        }
    }
    fun trayDrop() {
        val g = ghost ?: return
        if (g.valid) commitGhost() else { feedback(false); rejectText = g.result; ghost = null }
    }

    // ---- layout ------------------------------------------------------------------------------------------------------
    GameChrome(
        helpTitle = stringResource(R.string.td_help_title),
        helpText = stringResource(R.string.td_help_text),
        matchInProgress = !runOver,
        onLeave = game::leaveSession,
        onAbort = { if (finishedRuns > 0) game.leaveSession() else game.abortMatch() },
        leaveKeepsProgress = finishedRuns == 0,
        buttonFill = skin.panel,
        buttonContent = skin.panelText,
        leaveTitle = stringResource(R.string.td_leave_title),
        leaveBody = if (finishedRuns > 0) stringResource(R.string.td_leave_body_keep) else stringResource(R.string.td_leave_body),
        extraItems = { _ -> LaunchedEffect(Unit) { autoPause() } }
    ) {
        val plan = remember(skin.ambient, motion) { TdAmbient.plan(skin.ambient, motion) }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(skin.skyBottom).victoryGlow(trigger = won && !reducedMotion, tint = skin.accent)
                .onKeyEvent { onGlobalKey(it) }
        ) {
            // Hoisted: BoxWithConstraintsScope is a @LayoutScopeMarker, so maxWidth is not reachable from a nested Row/Column scope.
            val screenWidthDp = maxWidth.value
            val kind = TdLayoutMath.kindFor(screenWidthDp, maxHeight.value).let { if (!isOpen && it == TdLayoutKind.PHONE_PORTRAIT) TdLayoutKind.STACKED else it }

            // Backdrop: sky, far and mid layers, clouds, stars. One Canvas, driven by the ambient clock.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = ambientClock.floatValue.let { if (motion == TdMotion.CALM) kotlin.math.floor(it * 30f) / 30f else it }
                with(TdAmbient) { drawBackdrop(skin, plan, if (motion == TdMotion.OFF) 0f else t, motion) }
            }

            // ---- HUD: castle, gold, pause, setup; wave, countdown, next-wave card ------------------------------------------
            val hudBlock: @Composable ColumnScope.() -> Unit = {
                Row(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(end = GameChromeEndInset),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudPill(skin, Modifier.weight(1f)) {
                        val label = if (isOpen) stringResource(R.string.td_castle_spoken, castle, castleMax) else stringResource(R.string.td_lives_spoken, castle)
                        Row(
                            modifier = Modifier.clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Canvas(Modifier.size(18.dp)) { drawHeart(Color(0xFFFF6B6B)) }
                            // Health bar: length and two notches carry the value, not only colour.
                            Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x55000000))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(castle.toFloat() / castleMax.coerceAtLeast(1)).background(if (colorblind) Color(0xFFF5F0E6) else Color(0xFF6FD08C)))
                                Canvas(Modifier.fillMaxSize()) {
                                    drawLine(Color(0xAA000000), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), 1.5f)
                                    drawLine(Color(0xAA000000), Offset(size.width * 0.25f, 0f), Offset(size.width * 0.25f, size.height), 1.5f)
                                }
                            }
                            Text("$castle", color = skin.panelText, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    HudPill(skin) {
                        Row(
                            modifier = Modifier.clearAndSetSemantics { contentDescription = androidContext.getString(R.string.td_gold_spoken, gold) },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Canvas(Modifier.size(16.dp)) { drawCircle(Color(0xFFE0A83E)); drawCircle(Color(0xFF8A6A1E), style = Stroke(width = 1.5.dp.toPx())) }
                            Text("$gold", color = skin.panelText, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    RoundIconButton(skin, if (paused) stringResource(R.string.td_resume) else stringResource(R.string.td_pause), enabled = !runOver, filled = paused,
                        onClick = { game.togglePause() }) { c ->
                        Canvas(Modifier.size(18.dp)) {
                            if (paused) {
                                val tri = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.2f, size.height * 0.05f); lineTo(size.width * 0.95f, size.height * 0.5f); lineTo(size.width * 0.2f, size.height * 0.95f); close() }
                                drawPath(tri, c)
                            } else {
                                drawRect(c, Offset(size.width * 0.1f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height))
                                drawRect(c, Offset(size.width * 0.6f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height))
                            }
                        }
                    }
                    TdPillButton(stringResource(R.string.td_setup_button), skin, { showSetup = true })
                }
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudPill(skin) {
                        val waveText = if (endless) stringResource(R.string.td_wave_endless, wave) else stringResource(R.string.td_wave_short, wave, totalWaves)
                        val waveSpoken = if (endless) androidContext.getString(R.string.td_wave_spoken_endless, wave) else androidContext.getString(R.string.td_wave_spoken, wave, totalWaves)
                        Text(
                            waveText, color = skin.panelText, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.semantics { contentDescription = waveSpoken; liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    if (isOpen && !runOver) {
                        if (!waveInProgress && countdown > 0) {
                            TdPillButton(stringResource(R.string.td_start_wave), skin, { game.callNextWave() }, primary = true)
                        }
                        Text(
                            text = when {
                                paused -> stringResource(R.string.td_paused_note)
                                waveInProgress -> stringResource(R.string.td_wave_active)
                                else -> stringResource(R.string.td_next_wave_in, countdown.coerceAtLeast(1))
                            },
                            color = skin.panelText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(skin.panel.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (mode == TdMode.DAILY && dailyMod != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.td_daily_info, stringResource(tdMapNameRes(levelId)), stringResource(tdModifierRes(dailyMod!!))),
                        color = skin.panelText, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        modifier = Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(8.dp)).background(skin.panel.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (isOpen && !runOver) {
                    Spacer(Modifier.height(4.dp))
                    TdNextWaveCard(skin, nextPreview, nextIsBoss, colorblind, Modifier.widthIn(max = 760.dp))
                }
            }

            // ---- board: viewport (scrolls in portrait), content, overlays, minimap ---------------------------------------------
            val boardBlock: @Composable (Modifier) -> Unit = { boardMod ->
                BoxWithConstraints(modifier = boardMod, contentAlignment = Alignment.Center) {
                    val aspect = if (isOpen) TdLayoutMath.ASPECT else 1f
                    val wantsZoom = kind == TdLayoutKind.PHONE_PORTRAIT && isOpen
                    val minimapH = if (wantsZoom) 56.dp else 0.dp
                    val availH = (maxHeight - minimapH).coerceAtLeast(0.dp)
                    val viewW: Dp
                    val contentW: Dp
                    val contentH: Dp
                    if (wantsZoom) {
                        viewW = minOf(maxWidth, 900.dp)
                        contentH = minOf(viewW * TdLayoutMath.PORTRAIT_ZOOM / aspect, availH)
                        contentW = contentH * aspect
                    } else {
                        viewW = minOf(maxWidth, availH * aspect, 900.dp)
                        contentW = viewW
                        contentH = viewW / aspect
                    }
                    val scrolls = contentW > viewW + 1.dp
                    val viewH = contentH
                    val zoomEff = if (viewW.value > 0f) (contentW.value / viewW.value).coerceAtLeast(1f) else 1f
                    val viewWpx = with(density) { viewW.toPx() }
                    val wPx = with(density) { contentW.toPx() }
                    val hPx = with(density) { contentH.toPx() }
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(scrolls, viewWpx) {
                        if (scrolls) scroll.scrollTo(TdLayoutMath.castleScroll(viewWpx, zoomEff).roundToInt())
                    }
                    val castleHidden by derived { scrolls && !TdLayoutMath.castleVisible(scroll.value.toFloat(), viewWpx, zoomEff) }

                    val terrainBmp = remember(skin.id, levelId, wPx.roundToInt(), hPx.roundToInt()) {
                        val t = TowerDefenceGame.levelById(levelId)?.terrain
                        if (t == null) null else TdArt.bakeTerrain(skin, t, wPx.roundToInt(), hPx.roundToInt())
                    }
                    val routeCache = remember { TdRouteCache() }
                    val ctx = remember(skin, colorblind, motion, terrainBmp, plan) {
                        TdBoardCtx(
                            game = game, skin = skin, colorblind = colorblind, motion = motion, terrain = terrainBmp, plan = plan,
                            ambient = ambientClock, ghost = ghostState, selectedId = selectedState, armed = armedState,
                            showRoute = showRouteState, scan = scanState, particles = particleBurst.particles,
                            routeCache = routeCache, kidFirst = skin.kidFirst, cursor = cursorDraw
                        )
                    }
                    val boardDesc = stringResource(R.string.td_board_desc)
                    val stateDesc = if (endless) stringResource(R.string.td_board_state_endless, wave, castle, castleMax, gold)
                    else stringResource(R.string.td_board_state, wave, totalWaves, castle, castleMax, gold)
                    val haloTiles = with(density) { 12.dp.toPx() } / (wPx / TdGrid.TILES_X)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(viewW, viewH)
                                .cameraShake(cameraShake, magnitudePx = shakeMagnitudePx)
                                .onGloballyPositioned { viewRoot = it.positionInRoot(); viewSizePx = it.size }
                                .semantics { contentDescription = boardDesc; stateDescription = stateDesc }
                        ) {
                            Box(Modifier.size(viewW, viewH).horizontalScroll(scroll, enabled = false)) {
                                Box(
                                    modifier = Modifier
                                        .size(contentW, contentH)
                                        .onGloballyPositioned { contentRoot = it.positionInRoot(); contentSizePx = it.size }
                                        .onKeyEvent { onBoardKey(it) }
                                        .focusRequester(boardFocus)
                                        .onFocusChanged { boardFocused = it.isFocused }
                                        .focusable()
                                ) {
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .pointerInput(isOpen, runOver, wPx, snap) {
                                                if (runOver || !isOpen) return@pointerInput
                                                detectTapGestures(
                                                    onTap = { p ->
                                                        val (ax, ay) = TdBoardMath.anchorFor(p.x, p.y, size.width.toFloat(), size.height.toFloat(), snap)
                                                        val hitId = TdBoardMath.towerAt(game.state.value.towers, p.x, p.y, size.width.toFloat(), size.height.toFloat(), if (armed != null) 0f else haloTiles)
                                                        if (armed != null && hitId == null) {
                                                            val g = ghost
                                                            if (g != null && g.cx == ax && g.cy == ay && g.valid) commitGhost() else refreshGhost(ax, ay)
                                                        } else {
                                                            selectTower(hitId)
                                                            if (hitId == null) cancelPlacing()
                                                        }
                                                    },
                                                    onLongPress = { p ->
                                                        val id = TdBoardMath.towerAt(game.state.value.towers, p.x, p.y, size.width.toFloat(), size.height.toFloat(), haloTiles)
                                                        if (id != null) { selectTower(id); radialId = id; haptics(HapticSignal.LIGHT_TICK) }
                                                    }
                                                )
                                            }
                                            .pointerInput(isOpen, runOver, snap) {
                                                if (runOver || !isOpen) return@pointerInput
                                                detectDragGestures(
                                                    onDragStart = { p ->
                                                        if (armed != null) {
                                                            val (ax, ay) = TdBoardMath.anchorFor(p.x, p.y - dragOffsetPx, size.width.toFloat(), size.height.toFloat(), snap)
                                                            refreshGhost(ax, ay)
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        if (armed != null) {
                                                            val (ax, ay) = TdBoardMath.anchorFor(change.position.x, change.position.y - dragOffsetPx, size.width.toFloat(), size.height.toFloat(), snap)
                                                            val g = ghost
                                                            if (g == null || g.cx != ax || g.cy != ay) {
                                                                refreshGhost(ax, ay)
                                                                haptics(HapticSignal.LIGHT_TICK)
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = { if (ghost?.valid == true) commitGhost() },
                                                    onDragCancel = { ghost = null }
                                                )
                                            }
                                    ) {
                                        if (isOpen) drawOpenBoard(ctx) else drawClassicBoard(ctx, zoneState)
                                    }

                                    // Classic: one 48dp button per tower spot, each with its own spoken state and price.
                                    if (!isOpen && !runOver) {
                                        val level = TowerDefenceGame.levelById(levelId)
                                        level?.towerZones?.forEachIndexed { index, zone ->
                                            val spotDesc = classicSpotDescription(index, level.towerZones.size, selectedZone == index, levelOf(game, index))
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = contentW * zone.x - 24.dp, y = contentH * zone.y - 24.dp)
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .clickable(onClickLabel = stringResource(R.string.td_classic_pick), role = Role.Button) {
                                                        selectedZone = index
                                                        haptics(HapticSignal.LIGHT_TICK)
                                                    }
                                                    .semantics { contentDescription = spotDesc }
                                            )
                                        }
                                    }

                                    // TalkBack: the field as a 14 x 8 grid, and each tower as a node with custom actions.
                                    if (a11yOn && isOpen && !runOver) {
                                        val terrain = TowerDefenceGame.levelById(levelId)?.terrain
                                        if (terrain != null) AccessibleField(terrain, towerViews, routeCells, skin, contentW, contentH, armed, ::placeAtCell, ::selectTower, ::upgrade, ::sell, ::startMove, ::cycleTarget)
                                    }

                                    // Radial menu (long-press on a tower).
                                    val rid = radialId
                                    val rv = selectedView
                                    if (rid != null && rv != null && rv.id == rid) {
                                        val tw = game.state.value.towers.firstOrNull { it.id == rid }
                                        if (tw != null) {
                                            val c = TdGrid.towerCentre(tw.cx, tw.cy)
                                            TdRadialMenu(
                                                skin, contentW * (c.x / TdGrid.TILES_X), contentH * (c.y / TdGrid.TILES_Y), contentW, contentH,
                                                canUpgrade = rv.level < TdRules.MAX_LEVEL && !rv.needsBranch && gold >= rv.upgradeCost,
                                                onUpgrade = { upgrade(rid, TdBranch.NONE); radialId = null },
                                                onInfo = { radialId = null },
                                                onTarget = { cycleTarget(rid) },
                                                onMove = { startMove() },
                                                onSell = { sell(rid) },
                                                onDismiss = { radialId = null }
                                            )
                                        }
                                    }
                                }
                            }

                            // Overlays stay on the viewport, not the scrolled map: placement line, toast, pause scrim, result.
                            val g = ghost
                            val tip = when {
                                g != null -> tdPlacementText(g.result)
                                rejectText != null -> tdPlacementText(rejectText!!)
                                armed != null && moving -> stringResource(R.string.td_hint_move)
                                armed != null -> stringResource(R.string.td_hint_pick) + " " + stringResource(R.string.td_hint_drag)
                                else -> null
                            }
                            if (tip != null && !runOver) {
                                val okColor = g?.valid == true
                                Text(
                                    (if (g != null && g.valid) "✓ " else if (g != null) "✕ " else "") + tip + (if (g != null && g.valid) "  " + stringResource(R.string.td_hint_confirm) else ""),
                                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter).padding(top = 6.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (g == null) Color(0xCC120E1C) else if (okColor) Color(0xCC145A3C) else Color(0xCC6A1F25))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                            toast?.let { msg ->
                                Text(msg, color = Color.White, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC120E1C)).padding(horizontal = 10.dp, vertical = 5.dp).semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            if (castleHidden && !runOver) {
                                TdCastleChip(skin, castle, castleMax, { scope.launch { scroll.animateScrollTo(TdLayoutMath.castleScroll(viewWpx, zoomEff).roundToInt()) } }, Modifier.align(Alignment.CenterEnd))
                            }
                            if (paused && autoPaused && !runOver) {
                                TdPausedScrim(onResume = { if (game.state.value.paused) game.togglePause() }, modifier = Modifier.matchParentSize())
                            }
                            if (runOver) {
                                val s = game.state.value
                                TdResultOverlay(
                                    skin = skin, won = won, waveReached = s.waveNumber, totalWaves = s.totalWaves, castle = s.lives, castleMax = s.castleMax,
                                    bestWave = bestWave, isNewBest = reportedNewBest, reducedMotion = reducedMotion,
                                    onPlayAgain = { game.playAgain() }, onBackToMenu = { game.leaveSession() },
                                    modifier = Modifier.matchParentSize(),
                                    mode = mode, dailyScore = TdDaily.score(s.waveNumber, s.lives)
                                )
                            }
                        }
                        if (scrolls) {
                            Spacer(Modifier.height(6.dp))
                            TdMinimap(
                                skin, game, terrainBmp, scroll, viewWpx, zoomEff,
                                onScrollTo = { px -> scope.launch { scroll.scrollTo(px.roundToInt()) } },
                                modifier = Modifier.size(viewW, 44.dp)
                            )
                        }
                    }
                }
            }

            // ---- bottom: scan bar, tower panel, or tray ---------------------------------------------------------------------
            val trayBlock: @Composable () -> Unit = {
                val roles = TdRole.entries
                val cards: @Composable (List<TdRole>) -> Unit = { list ->
                    for (role in list) {
                        TdTowerCard(
                            skin, role, gold, selected = armed == role && !moving, colorblind = colorblind, highlighted = false,
                            onClick = { toggleArm(role) },
                            onDragMove = { trayDrag(role, it) }, onDragEnd = { trayDrop() }, onDragCancel = { ghost = null }
                        )
                    }
                }
                if (kind == TdLayoutKind.SPLIT_LANDSCAPE) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cards(roles.take(2)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cards(roles.drop(2)) }
                        if (armed != null) TdPillButton(stringResource(R.string.td_cancel), skin, { cancelPlacing() })
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        cards(roles)
                        if (armed != null) TdPillButton(stringResource(R.string.td_cancel), skin, { cancelPlacing() })
                    }
                }
            }
            val bottomBlock: @Composable () -> Unit = {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    val sv = selectedView
                    val currentScan = scan
                    when {
                        runOver -> {}
                        !isOpen -> ClassicTray(
                            skin, selectedZone, TowerDefenceGame.levelById(levelId)?.towerZones?.size ?: 0, zoneLevel, gold,
                            onCommit = {
                                val z = selectedZone
                                val s = game.state.value
                                if (z != null) {
                                    val tower = s.towers.firstOrNull { it.zoneIndex == z }
                                    if (tower == null) game.placeTower(z) else game.upgradeTower(tower.id)
                                    feedback(game.state.value.gold < s.gold)
                                }
                            }
                        )
                        oneSwitch && currentScan != null -> ScanBar(skin, currentScan, gold, colorblind) {
                            when (val o = currentScan.lock()) {
                                is TdScan.Outcome.Continue -> scan = o.scan
                                is TdScan.Outcome.Build -> { placeAtCell(o.col, o.row, o.role); scan = TdScan() }
                            }
                        }
                        sv != null -> TdTowerPanel(
                            skin, sv, gold, moving,
                            onUpgrade = { b -> upgrade(sv.id, b) },
                            onSell = { sell(sv.id) },
                            onMove = { startMove() },
                            onTarget = { game.setTargeting(sv.id, it) },
                            onClose = { selectTower(null); cancelPlacing() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        else -> trayBlock()
                    }
                }
            }

            if (kind == TdLayoutKind.SPLIT_LANDSCAPE) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    boardBlock(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier.width(TdLayoutMath.sideColumnWidth(screenWidthDp).dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        hudBlock()
                        Spacer(Modifier.height(10.dp))
                        bottomBlock()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    hudBlock()
                    Spacer(Modifier.height(6.dp))
                    boardBlock(Modifier.weight(1f, fill = false))
                    Spacer(Modifier.height(8.dp))
                    bottomBlock()
                }
            }
        }
    }

    if (showSetup) {
        val tierIndex = if (gentle) 0 else when (difficulty) { CpuDifficulty.EASY -> 1; CpuDifficulty.MEDIUM -> 2; CpuDifficulty.HARD -> 3 }
        TdSetupDialog(
            skin = skin, mapId = levelId, themeId = skin.id, tierIndex = tierIndex, themeLocked = waveInProgress,
            oneSwitch = oneSwitch, showRoute = showRoute,
            onMap = { id ->
                if (id != levelId) requestSetup { game.level = TowerDefenceGame.levelById(id) ?: game.level; game.startMatch() }
            },
            onTheme = { themeKey = it.key },
            onTier = { i ->
                requestSetup {
                    when (i) {
                        0 -> game.gentle = true
                        1 -> { game.gentle = false; game.difficulty = CpuDifficulty.EASY }
                        2 -> { game.gentle = false; game.difficulty = CpuDifficulty.MEDIUM }
                        else -> { game.gentle = false; game.difficulty = CpuDifficulty.HARD }
                    }
                    game.startMatch()
                }
            },
            onOneSwitch = { oneSwitch = it },
            onShowRoute = { showRoute = it },
            onDismiss = { showSetup = false },
            freePlacement = freePlacement, onFreePlacement = { freePlacement = it },
            modeLocked = mode == TdMode.DAILY
        )
    }

    pendingSetup?.let { apply ->
        AlertDialog(
            onDismissRequest = { pendingSetup = null },
            title = { Text(stringResource(R.string.td_restart_title)) },
            text = { Text(stringResource(R.string.td_restart_body)) },
            confirmButton = { TextButton(onClick = { pendingSetup = null; if (!game.matchOver.value) apply() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.td_restart)) } },
            dismissButton = { TextButton(onClick = { pendingSetup = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.td_keep_playing)) } }
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------------------

/** A derived value that is recalculated when the snapshot state it reads changes, but only recomposes when the RESULT changes. */
@Composable
private fun <T> derived(calc: () -> T): State<T> {
    val current = rememberUpdatedState(calc)
    return remember { derivedStateOf { current.value() } }
}

@Composable
private fun HudPill(skin: TdSkin, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(skin.panel.copy(alpha = 0.92f)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
private fun RoundIconButton(skin: TdSkin, description: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier.size(52.dp).clip(CircleShape).background(if (filled) skin.accent else skin.panel.copy(alpha = 0.92f))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { glyph(if (filled) skin.accentOn else skin.panelText) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeart(color: Color) {
    val w = size.width
    val h = size.height
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.5f, h * 0.95f)
        cubicTo(w * -0.35f, h * 0.60f, w * 0.05f, h * -0.10f, w * 0.5f, h * 0.25f)
        cubicTo(w * 0.95f, h * -0.10f, w * 1.35f, h * 0.60f, w * 0.5f, h * 0.95f)
        close()
    }
    drawPath(path, color)
}

private fun levelOf(game: TowerDefenceGame, zone: Int): Int? =
    game.state.value.towers.firstOrNull { it.zoneIndex == zone }?.upgradeLevel

@Composable
private fun classicSpotDescription(index: Int, total: Int, selected: Boolean, level: Int?): String {
    val base = if (level == null) stringResource(R.string.td_spot_empty, index + 1, total, TowerDefenceGame.BASE_TOWER_COST)
    else stringResource(R.string.td_spot_tower, index + 1, total, level)
    return if (selected) base + stringResource(R.string.td_spot_selected) else base
}

/** Classic maps: tell what the commit button does for the selected spot (build 50, upgrade 40/64/102/164, max). */
@Composable
private fun ClassicTray(skin: TdSkin, spot: Int?, total: Int, level: Int?, gold: Int, onCommit: () -> Unit) {
    val maxLevel = TowerDefenceGame.MAX_UPGRADE_LEVEL
    val cost: Int? = when {
        spot == null -> null
        level == null -> TowerDefenceGame.BASE_TOWER_COST
        level < maxLevel -> TowerDefenceGame.upgradeCost(level)
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(skin.panel.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (spot == null) stringResource(R.string.td_classic_pick) else stringResource(R.string.td_classic_spot_title, spot + 1, total),
                color = skin.panelText, fontWeight = FontWeight.Bold
            )
            if (cost != null && gold < cost) Text(stringResource(R.string.td_need_gold, cost - gold), color = skin.panelText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            else if (level != null && level >= maxLevel) Text(stringResource(R.string.td_max_level), color = skin.panelText.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
        TdPillButton(
            text = when { cost == null -> stringResource(R.string.td_max_level); level == null -> stringResource(R.string.td_classic_build, cost); else -> stringResource(R.string.td_upgrade, cost) },
            skin = skin, onClick = onCommit, enabled = cost != null && gold >= cost, primary = true
        )
    }
}

/** One-switch bar: what is being chosen right now, and the big Select button. */
@Composable
private fun ScanBar(skin: TdSkin, scan: TdScan, gold: Int, colorblind: Boolean, onSelect: () -> Unit) {
    val role = TdRole.entries[scan.index.coerceIn(0, TdRole.entries.size - 1)]
    val text = when (scan.phase) {
        TdScan.Phase.COLUMN -> stringResource(R.string.td_scan_column)
        TdScan.Phase.ROW -> stringResource(R.string.td_scan_row)
        TdScan.Phase.ROLE -> stringResource(R.string.td_scan_role, stringResource(skin.towerNames.getValue(role)) + ", " + TdRules.baseCost(role))
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(skin.panel.copy(alpha = 0.95f)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = skin.panelText, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        TdPillButton(stringResource(R.string.td_select), skin, onSelect, primary = true, modifier = Modifier.size(width = 140.dp, height = 64.dp))
    }
}

/** A tower reduced to what TalkBack needs (no cooldown or age, so it changes rarely). */
internal data class TdA11yTower(val id: Int, val cx: Int, val cy: Int, val role: TdRole, val level: Int, val branch: TdBranch, val target: TdTarget)

/**
 * The TalkBack view of the field: 14 x 8 cells (terrain and route facts, double tap places the armed
 * tower, or a Bolt if none is armed) and one node per tower with upgrade, sell, move and targeting
 * custom actions. Composed only while touch exploration is on.
 */
@Composable
private fun AccessibleField(
    terrain: com.gamesuite.games.towerdefence.TdTerrain, towers: List<TdA11yTower>, route: Set<Pair<Int, Int>>,
    skin: TdSkin, boardW: Dp, boardH: Dp, armed: TdRole?,
    place: (Int, Int, TdRole) -> Unit, select: (Int?) -> Unit, upgrade: (Int, TdBranch) -> Unit, sell: (Int) -> Unit,
    move: () -> Unit, target: (Int) -> Unit
) {
    val cw = boardW / TdBoardMath.GRID_COLS
    val ch = boardH / TdBoardMath.GRID_ROWS
    for (row in 0 until TdBoardMath.GRID_ROWS) for (col in 0 until TdBoardMath.GRID_COLS) {
        val (ax, ay) = TdBoardMath.anchorOfA11yCell(col, row)
        val fp = TdGrid.footprint(ax, ay)
        val buildable = fp != null && fp.all { terrain.buildable[it] }
        val tower = towers.firstOrNull { TdBoardMath.a11yCellOf(it.cx, it.cy) == (col to row) }
        val base = stringResource(R.string.td_cell_base, row + 1, TdBoardMath.columnLetter(col).toString())
        val fact = when {
            tower != null -> stringResource(R.string.td_cell_tower, stringResource(skin.towerNames.getValue(tower.role)), tower.level)
            !buildable -> stringResource(R.string.td_cell_blocked)
            else -> stringResource(R.string.td_cell_empty)
        }
        val routeNote = if ((col to row) in route) ". " + stringResource(R.string.td_cell_route) else ""
        val action = stringResource(R.string.td_cell_place)
        Box(
            modifier = Modifier.offset(cw * col, ch * row).size(cw, ch).semantics {
                contentDescription = "$base. $fact$routeNote"
                if (tower == null && buildable) onClick(label = action) { place(col, row, armed ?: TdRole.BOLT); true }
                else if (tower != null) onClick(label = null) { select(tower.id); true }
            }
        )
    }
    for (tw in towers) {
        val c = TdGrid.towerCentre(tw.cx, tw.cy)
        val label = stringResource(R.string.td_tower_a11y, stringResource(skin.towerNames.getValue(tw.role)), tw.level, stringResource(tdTargetRes(tw.target)))
        val aUp = stringResource(R.string.td_action_upgrade)
        val aSell = stringResource(R.string.td_action_sell)
        val aMove = stringResource(R.string.td_action_move)
        val aTarget = stringResource(R.string.td_action_target)
        Box(
            modifier = Modifier
                .offset(boardW * (c.x / TdGrid.TILES_X) - 24.dp, boardH * (c.y / TdGrid.TILES_Y) - 24.dp)
                .size(48.dp)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick(label = null) { select(tw.id); true }
                    customActions = listOf(
                        CustomAccessibilityAction(aUp) { upgrade(tw.id, if (TdRules.needsBranch(tw.level, tw.branch)) TdBranch.A else TdBranch.NONE); true },
                        CustomAccessibilityAction(aSell) { sell(tw.id); true },
                        CustomAccessibilityAction(aMove) { select(tw.id); move(); true },
                        CustomAccessibilityAction(aTarget) { target(tw.id); true }
                    )
                }
        )
    }
}

internal fun tdModifierRes(m: TdModifier): Int = when (m) {
    TdModifier.SWARM -> R.string.td_mod_swarm
    TdModifier.TOUGH -> R.string.td_mod_tough
    TdModifier.SWIFT -> R.string.td_mod_swift
    TdModifier.LEAN -> R.string.td_mod_lean
}
