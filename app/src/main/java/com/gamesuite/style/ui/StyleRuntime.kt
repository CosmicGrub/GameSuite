package com.gamesuite.style.ui

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamesuite.core.ProfileScope
import com.gamesuite.core.gameDataStore
import com.gamesuite.settings.LocalColorblindMode
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.style.ArtTier
import com.gamesuite.style.ASSET_GEM_ATLAS
import com.gamesuite.style.AtlasBudget
import com.gamesuite.style.AtlasManifest
import com.gamesuite.style.BudgetLru
import com.gamesuite.style.DeviceCaps
import com.gamesuite.style.MotionLevel
import com.gamesuite.style.QualityLevel
import com.gamesuite.style.RamClass
import com.gamesuite.style.RawDeviceFacts
import com.gamesuite.style.ResolvedStyle
import com.gamesuite.style.StyleContext
import com.gamesuite.style.StyleHealth
import com.gamesuite.style.StylePrefs
import com.gamesuite.style.StylePrefsStore
import com.gamesuite.style.StyleResolver
import com.gamesuite.style.gem.GemAtlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** What games read. Defaults to Classic so previews and unmigrated screens are always safe. */
val LocalStylePack = staticCompositionLocalOf { ResolvedStyle.CLASSIC }

/** Per-PROFILE store (not in ProfileScope.deviceLevelStores), so each adult and kid keeps its own look. */
private val Context.stylePrefsDataStore: DataStore<Preferences> by gameDataStore("style_prefs")

/** Holds the persisted look. Created per screen with `viewModel()`; every instance reads the same DataStore file. */
class StyleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StylePrefsStore(application.stylePrefsDataStore)

    /**
     * The first frame must already show the saved look (no 2.5D flash for a player who chose Flat), so the initial value
     * is read synchronously. It is a tiny file; a failure falls back to defaults.
     */
    val prefs: StateFlow<StylePrefs> = store.prefs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        try { runBlocking { store.prefs.first() } } catch (_: Throwable) { StylePrefs() }
    )

    fun setArt(t: ArtTier) { viewModelScope.launch { store.setArt(t) } }
    fun setMotion(m: MotionLevel) { viewModelScope.launch { store.setMotion(m) } }
    fun setQuality(q: QualityLevel) { viewModelScope.launch { store.setQuality(q) } }
    fun setOverride(gameId: String, t: ArtTier?) { viewModelScope.launch { store.setOverride(gameId, t) } }
    fun reset() { viewModelScope.launch { store.reset() } }
}

// ------------------------------------------------------------------------------------------------ device caps
object DeviceCapsProbe {
    fun read(ctx: Context): DeviceCaps {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: 0 else 0
        val scale = try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Throwable) { 1f }
        return DeviceCaps.detect(
            RawDeviceFacts(Build.VERSION.SDK_INT, mem.totalMem, am?.isLowRamDevice ?: false, pm?.isPowerSaveMode ?: false, thermal, scale)
        )
    }
}

/** Device caps that follow battery saver and thermal changes while the composable is alive. */
@Composable
fun rememberDeviceCaps(): DeviceCaps {
    val ctx = LocalContext.current
    var caps by remember { mutableStateOf(DeviceCapsProbe.read(ctx)) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { caps = DeviceCapsProbe.read(ctx) }
        }
        try { ctx.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) } catch (_: Throwable) { }
        var listener: PowerManager.OnThermalStatusChangedListener? = null
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= 29 && pm != null) {
            listener = PowerManager.OnThermalStatusChangedListener { caps = DeviceCapsProbe.read(ctx) }
            try { pm.addThermalStatusListener(ContextCompat.getMainExecutor(ctx), listener) } catch (_: Throwable) { listener = null }
        }
        onDispose {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
            if (Build.VERSION.SDK_INT >= 29 && pm != null && listener != null) try { pm.removeThermalStatusListener(listener) } catch (_: Throwable) { }
        }
    }
    return caps
}

// ------------------------------------------------------------------------------------------------ atlas
/** Decoded atlases, bounded by the RAM-class budget. Cleared on trim. */
object GemAtlasCache {
    private val lru = BudgetLru<Int, GemAtlas>(AtlasBudget.maxDecodedBytes(RamClass.HIGH)) { it.manifest.decodedBytes }

    fun load(ctx: Context, ram: RamClass): GemAtlas? {
        val size = AtlasBudget.atlasSizeFor(ram) ?: return null
        lru[size]?.let { return it }
        return try {
            val dir = "styles/gem-cascade"
            val manifest = ctx.assets.open("$dir/atlas_$size.txt").use { AtlasManifest.parse(it.reader().readText()) } ?: return null
            if (manifest.decodedBytes > AtlasBudget.maxDecodedBytes(ram)) return null
            val bmp = ctx.assets.open("$dir/atlas_$size.png").use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
            if (bmp.width != manifest.atlasWidth || bmp.height != manifest.atlasHeight) return null
            GemAtlas(manifest, bmp.asImageBitmap()).also { lru.put(size, it) }
        } catch (_: Throwable) { null }
    }

    fun trim() = lru.clear()
}

/**
 * The 3D atlas when [wanted], decoded off the main thread. Null while loading, when not wanted, or when the pack cannot
 * be used (the style then resolves to 2.5D with an ASSETS_MISSING reason: never a blank board).
 */
@Composable
fun rememberGemAtlas(wanted: Boolean, ram: RamClass): GemAtlas? {
    val ctx = LocalContext.current.applicationContext
    val state = produceState<GemAtlas?>(null, wanted, ram) {
        value = if (wanted) withContext(Dispatchers.IO) { GemAtlasCache.load(ctx, ram) } else null
    }
    return state.value
}

// ------------------------------------------------------------------------------------------------ resolution
class GameStyle(val style: ResolvedStyle, val prefs: StylePrefs, val atlas: GemAtlas?, val caps: DeviceCaps, val viewModel: StyleViewModel, val health: StyleHealth, val demote: () -> Unit)

/**
 * Resolves the look for one game from the saved preferences, the device and the accessibility settings. The Reduced
 * Motion and colour-blind locals still come from the existing settings providers.
 */
@Composable
fun rememberGameStyle(gameId: String): GameStyle {
    val vm: StyleViewModel = viewModel()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val caps = rememberDeviceCaps()
    val reduced = LocalReducedMotion.current
    val colorblind = LocalColorblindMode.current
    val health = remember { StyleHealth() }
    var healthEpoch by remember { mutableIntStateOf(0) }
    val wants3D = prefs.artFor(gameId) == ArtTier.FULL_3D && caps.ramClass != RamClass.LOW && !caps.batterySaver
    val atlas = rememberGemAtlas(wants3D, caps.ramClass)
    val isKid = ProfileScope.isActiveKid
    val style = remember(prefs, caps, reduced, colorblind, atlas, healthEpoch, isKid, gameId) {
        StyleResolver.resolve(
            prefs, gameId,
            StyleContext(caps, isKid = isKid, reducedMotion = reduced, colorblind = colorblind,
                assetsReady = if (atlas != null) setOf(ASSET_GEM_ATLAS) else emptySet(), health = health)
        )
    }
    val demote = remember { { health.demote(com.gamesuite.style.Surface.PIECE, ArtTier.FULL_3D); healthEpoch++; Unit } }
    return remember(style, prefs, atlas, caps, vm) { GameStyle(style, prefs, atlas, caps, vm, health, demote) }
}
