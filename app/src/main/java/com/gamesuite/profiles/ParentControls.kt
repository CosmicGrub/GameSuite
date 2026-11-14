package com.gamesuite.profiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.core.gameDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-kid limits, per-kid hidden games and the play-time usage that the [PlayTimeLimiter] accumulates.
 * Minutes-of-day are 0..1439. [dailyMinutes] 0 means no daily limit. The quiet window may cross midnight.
 */
@Serializable
data class KidControls(
    val dailyMinutes: Int = 60,
    val quietEnabled: Boolean = false,
    val quietStartMinute: Int = 20 * 60,
    val quietEndMinute: Int = 7 * 60,
    val hiddenGameIds: Set<String> = emptySet(),
    /**
     * Local epoch day [usageMillis] belongs to; -1 = none yet. It doubles as the HIGH-WATER mark: the limiter only
     * ever rolls it forward, so moving the clock back never resets (or deletes) a day's usage.
     */
    val usageEpochDay: Long = -1L,
    val usageMillis: Long = 0L,
    /**
     * Trusted-time anchor written with every accrual (see [PlayTimeLimiter]): the trusted wall time, the monotonic
     * clock reading and the boot counter at that moment. They let the limiter keep time when the wall clock is
     * moved; 0 = no anchor yet.
     */
    val anchorWall: Long = 0L,
    val anchorElapsed: Long = 0L,
    val anchorBoot: Long = 0L,
    /** Foreground play per local epoch day (last [HISTORY_DAYS] days), for the weekly bars. */
    val dailyHistory: Map<Long, Long> = emptyMap()
) {
    companion object {
        const val HISTORY_DAYS = 14
        /** Choices offered in the dashboard (0 = no limit). */
        val DAILY_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 0)
    }
}

/** Everything the parent configures. [onlinePlayEnabled] is the adult "Online play" master switch (default on). */
@Serializable
data class ControlsState(
    val onlinePlayEnabled: Boolean = true,
    val kids: Map<String, KidControls> = emptyMap(),
    /** The parent's chosen default unlock method; null = automatic (see [GateMethods.defaultFor]). */
    val gateMethod: GateMethod? = null,
    /** Hex salt and PBKDF2 hash of the parent PIN (see [PinHasher]); null = no PIN set. Never the PIN itself. */
    val pinSalt: String? = null,
    val pinHash: String? = null
) {
    val hasPin: Boolean get() = !pinSalt.isNullOrEmpty() && !pinHash.isNullOrEmpty()
    fun forKid(profileId: String): KidControls = kids[profileId] ?: KidControls()
    fun withKid(profileId: String, c: KidControls): ControlsState = copy(kids = kids + (profileId to c))
}

/** Outcome of reading the stored controls JSON. */
sealed interface ControlsRead {
    object Missing : ControlsRead
    data class Ok(val state: ControlsState) : ControlsRead
    /** A non-blank value that does not decode. [raw] must be preserved (quarantine) and never overwritten. */
    data class Corrupt(val raw: String) : ControlsRead
}

/** What the app shows from the controls store: [state] is always usable, [corrupt] says the stored value was not. */
data class ControlsLoad(val state: ControlsState, val corrupt: Boolean)

object ControlsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(s: ControlsState): String = json.encodeToString(ControlsState.serializer(), s)

    /** Lenient display decode: anything unreadable decodes as the defaults. NEVER use the result to write back; use [read]. */
    fun decode(raw: String?): ControlsState =
        if (raw.isNullOrBlank()) ControlsState()
        else runCatching { json.decodeFromString(ControlsState.serializer(), raw) }.getOrDefault(ControlsState())

    /** Distinguishes "nothing stored" from "stored but unreadable" so a failed read can never become a write. */
    fun read(raw: String?): ControlsRead {
        if (raw.isNullOrBlank()) return ControlsRead.Missing
        return runCatching { json.decodeFromString(ControlsState.serializer(), raw) }
            .fold(onSuccess = { ControlsRead.Ok(it) }, onFailure = { ControlsRead.Corrupt(raw) })
    }

    fun load(raw: String?): ControlsLoad = when (val r = read(raw)) {
        ControlsRead.Missing -> ControlsLoad(ControlsState(), corrupt = false)
        is ControlsRead.Ok -> ControlsLoad(r.state, corrupt = false)
        is ControlsRead.Corrupt -> ControlsLoad(ControlsState(), corrupt = true)
    }
}

/** Thrown by [ParentControlsStore.update] when the stored controls are unreadable: nothing was written. */
class ControlsUnreadableException : Exception("Stored parent controls are unreadable; the raw value was quarantined and kept.")

private val Context.controlsDataStore: DataStore<Preferences> by gameDataStore("profile_controls")

/** Device-level persistence ("profile_controls") of [ControlsState] (plus the parent gate's lock state). */
class ParentControlsStore(private val context: Context) {
    private object Keys {
        val STATE = stringPreferencesKey("controls_json")
        /** The first unreadable value is parked here and never replaced (see StatsCodec's quarantine). */
        val QUARANTINE = stringPreferencesKey("controls_json_quarantine")
        val GATE = stringPreferencesKey("gate_json")
    }

    /** The usable state plus whether the stored value was unreadable. */
    val load: Flow<ControlsLoad> = context.controlsDataStore.data.map { ControlsCodec.load(it[Keys.STATE]) }

    val state: Flow<ControlsState> = load.map { it.state }

    /** One read (bootstrap). */
    suspend fun loadOnce(): ControlsLoad = ControlsCodec.load(context.controlsDataStore.data.first()[Keys.STATE])

    suspend fun loadGateOnce(): GateLockState = GateStateCodec.decode(context.controlsDataStore.data.first()[Keys.GATE])

    suspend fun saveGate(s: GateLockState) {
        context.controlsDataStore.edit { it[Keys.GATE] = GateStateCodec.encode(s) }
    }

    /**
     * Applies [transform] to the CURRENT stored value inside one atomic edit. When the stored value exists but is
     * unreadable nothing is written (the raw value is quarantined once) and [ControlsUnreadableException] is thrown,
     * so one bad read can no longer wipe limits, hidden games and the Online switch on the next 15 s tick.
     */
    suspend fun update(transform: (ControlsState) -> ControlsState) {
        var unreadable = false
        context.controlsDataStore.edit { prefs ->
            when (val r = ControlsCodec.read(prefs[Keys.STATE])) {
                is ControlsRead.Corrupt -> {
                    com.gamesuite.stats.quarantineValue(prefs[Keys.QUARANTINE], r.raw)?.let { prefs[Keys.QUARANTINE] = it }
                    unreadable = true
                }
                ControlsRead.Missing -> prefs[Keys.STATE] = ControlsCodec.encode(transform(ControlsState()))
                is ControlsRead.Ok -> prefs[Keys.STATE] = ControlsCodec.encode(transform(r.state))
            }
        }
        if (unreadable) throw ControlsUnreadableException()
    }

    /**
     * The explicit parent action "start the controls over": replaces an unreadable value with defaults. The raw
     * value stays in the quarantine key. Only called after a parent confirmed it.
     */
    suspend fun resetUnreadable() {
        context.controlsDataStore.edit { prefs ->
            val raw = prefs[Keys.STATE]
            if (ControlsCodec.read(raw) is ControlsRead.Corrupt) {
                com.gamesuite.stats.quarantineValue(prefs[Keys.QUARANTINE], raw)?.let { prefs[Keys.QUARANTINE] = it }
                prefs[Keys.STATE] = ControlsCodec.encode(ControlsState())
            }
        }
    }
}
