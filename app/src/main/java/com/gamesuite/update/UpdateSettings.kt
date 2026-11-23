package com.gamesuite.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Persisted updater preferences. Auto-check is OFF by default; PREVIEW is the default channel for the sideload build. */
data class UpdateSettings(
    val autoCheck: Boolean = false,
    val channel: UpdateChannel = UpdateChannel.PREVIEW,
    val lastCheckedMs: Long = 0L,
    /** The newest versionCode the owner dismissed the hub banner for. */
    val dismissedVersionCode: Long = 0L,
    /** Owner opt-in: also offer same/older builds (Android will usually refuse a downgrade; the UI explains). */
    val allowReinstall: Boolean = false,
    /** Do not touch the network before this time (GitHub rate limit window). */
    val rateLimitedUntilMs: Long = 0L,
    /** The one-time "Install unknown apps" guide has been shown. */
    val permissionGuideSeen: Boolean = false
)

interface UpdateSettingsStore {
    val settings: Flow<UpdateSettings>
    suspend fun update(transform: (UpdateSettings) -> UpdateSettings)
}

suspend fun UpdateSettingsStore.current(): UpdateSettings = settings.first()

class InMemoryUpdateSettingsStore(initial: UpdateSettings = UpdateSettings()) : UpdateSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<UpdateSettings> get() = state
    override suspend fun update(transform: (UpdateSettings) -> UpdateSettings) { state.value = transform(state.value) }
}

object UpdateThrottle {
    const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Automatic checks: only when enabled, at most once per 6 h, never inside a rate-limit window. Manual checks bypass the 6 h rule. */
    fun shouldAutoCheck(nowMs: Long, s: UpdateSettings): Boolean =
        s.autoCheck && nowMs >= s.rateLimitedUntilMs &&
            (s.lastCheckedMs > nowMs /* clock moved back */ || nowMs - s.lastCheckedMs >= AUTO_INTERVAL_MS)

    fun mayUseNetwork(nowMs: Long, s: UpdateSettings): Boolean = nowMs >= s.rateLimitedUntilMs
}
