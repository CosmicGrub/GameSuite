package com.gamesuite.update

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The Android side of the hand-off (FileProvider + system package installer). Faked in JVM tests. */
interface InstallLauncher {
    fun canInstallUnknownApps(): Boolean
    fun openUnknownAppsSettings()
    /** @return false when no installer could be started. */
    fun launchInstaller(file: File): Boolean
}

sealed class UpdatePhase {
    object Idle : UpdatePhase()
    object Checking : UpdatePhase()
    data class Downloading(val tag: String, val done: Long, val total: Long) : UpdatePhase()
    data class Verifying(val tag: String) : UpdatePhase()
    /** The APK is downloaded and verified; the owner must allow "Install unknown apps" for GameSuite, then continue. */
    data class NeedsPermission(val tag: String) : UpdatePhase()
    /** The system installer is open; the owner confirms there. The file is deleted on next launch / next update. */
    data class HandedOff(val tag: String) : UpdatePhase()
    data class Failed(val failure: UpdateFailure, val tag: String?) : UpdatePhase()
}

data class UpdateUiState(
    val installed: InstalledVersion,
    val settings: UpdateSettings = UpdateSettings(),
    val catalog: ReleaseCatalog = ReleaseCatalog.EMPTY,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val checkFailure: UpdateFailure? = null,
    val checkedOnce: Boolean = false
) {
    val badgeCount: Int get() = catalog.badgeCount
    val showBanner: Boolean get() = catalog.newest?.versionCode?.let { it > settings.dismissedVersionCode } == true
}

/**
 * Orchestrates check -> download -> verify -> hand-off. All blocking work runs on [io]. One operation at a time.
 * `updateNewest()` is the single "Update" button: check, pick the newest release in the channel, download, verify
 * hash + package + version + signing certificate, then open the system installer.
 */
class UpdateController(
    private val installed: InstalledVersion,
    private val client: ReleasesClient,
    private val downloader: ApkDownloader,
    private val inspector: ApkInspector,
    private val installer: InstallLauncher,
    private val store: UpdateSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow(UpdateUiState(installed))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private val busy = AtomicBoolean(false)
    private var releases: List<ReleaseInfo> = emptyList()
    private var pending: Pair<ReleaseEntry, File>? = null
    @Volatile private var cancelled = false

    suspend fun start() {
        withContext(io) {
            downloader.cleanStale()
            client.cachedOnly()?.let { releases = it }
        }
        refreshSettings(); rebuild()
    }

    private suspend fun refreshSettings() { _state.value = _state.value.copy(settings = store.settings.first()) }

    private fun rebuild() {
        val s = _state.value.settings
        _state.value = _state.value.copy(catalog = ReleaseCatalog.build(releases, installed, s.channel, s.allowReinstall))
    }

    suspend fun setAutoCheck(v: Boolean) { store.update { it.copy(autoCheck = v) }; refreshSettings() }
    suspend fun setChannel(c: UpdateChannel) { store.update { it.copy(channel = c) }; refreshSettings(); rebuild() }
    suspend fun setAllowReinstall(v: Boolean) { store.update { it.copy(allowReinstall = v) }; refreshSettings(); rebuild() }
    suspend fun dismissBanner() {
        val code = _state.value.catalog.newest?.versionCode ?: return
        store.update { it.copy(dismissedVersionCode = code) }; refreshSettings()
    }
    suspend fun markPermissionGuideSeen() { store.update { it.copy(permissionGuideSeen = true) }; refreshSettings() }

    /** Called at app start / hub resume: honours the 6 h throttle and the auto-check switch. */
    suspend fun autoCheckIfDue() {
        refreshSettings()
        if (UpdateThrottle.shouldAutoCheck(clock(), _state.value.settings)) check(manual = false)
    }

    /** Lists every release (all pages). Manual checks ignore the 6 h rule but still respect a rate-limit window. */
    suspend fun check(manual: Boolean = true): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            refreshSettings()
            val now = clock()
            if (!UpdateThrottle.mayUseNetwork(now, _state.value.settings)) {
                _state.value = _state.value.copy(checkFailure = UpdateFailure.RateLimited(_state.value.settings.rateLimitedUntilMs / 1000))
                return false
            }
            _state.value = _state.value.copy(phase = UpdatePhase.Checking, checkFailure = null)
            return try {
                val r = withContext(io) { client.fetchAll() }
                releases = r.releases
                store.update { it.copy(lastCheckedMs = now) }
                refreshSettings(); rebuild()
                _state.value = _state.value.copy(phase = UpdatePhase.Idle, checkedOnce = true)
                true
            } catch (e: UpdateException) {
                if (e.failure is UpdateFailure.RateLimited) {
                    val until = (e.failure.resetEpochSeconds?.times(1000)) ?: (now + UpdateThrottle.AUTO_INTERVAL_MS)
                    store.update { it.copy(rateLimitedUntilMs = until, lastCheckedMs = now) }
                } else store.update { it.copy(lastCheckedMs = now) }
                refreshSettings()
                _state.value = _state.value.copy(phase = UpdatePhase.Idle, checkFailure = e.failure, checkedOnce = true)
                false
            }
        } finally { busy.set(false) }
    }

    /** The single "Update" button: check, then install the newest release of the followed channel. */
    suspend fun updateNewest() {
        if (_state.value.phase !is UpdatePhase.Idle && _state.value.phase !is UpdatePhase.Failed) return
        if (!check(manual = true) && _state.value.checkFailure != null) return
        val newest = _state.value.catalog.newest
        if (newest == null) return
        install(newest.release.tag)
    }

    /** Download + verify + hand off one specific release. */
    suspend fun install(tag: String) {
        val entry = _state.value.catalog.entries.firstOrNull { it.release.tag == tag }
        val apk = entry?.apk
        if (entry == null || apk == null || !entry.installable) {
            _state.value = _state.value.copy(phase = UpdatePhase.Failed(UpdateFailure.NoApkAsset, tag)); return
        }
        if (!busy.compareAndSet(false, true)) return
        cancelled = false
        var file: File? = null
        try {
            fun fail(f: UpdateFailure) {
                file?.delete(); pending = null
                _state.value = _state.value.copy(phase = UpdatePhase.Failed(f, tag))
            }
            _state.value = _state.value.copy(phase = UpdatePhase.Downloading(tag, 0, apk.size))
            try {
                file = withContext(io) {
                    val sha = downloader.expectedSha256(entry.release, apk)
                    downloader.cleanStale()
                    downloader.download(apk, sha, { d, t ->
                        _state.value = _state.value.copy(phase = UpdatePhase.Downloading(tag, d, t))
                    }, { cancelled })
                }
            } catch (e: UpdateException) { fail(e.failure); return }

            _state.value = _state.value.copy(phase = UpdatePhase.Verifying(tag))
            val reason = withContext(io) {
                val inst = inspector.installed()
                if (inst == null) UpdateFailure.ApkUnreadable
                else ApkPolicy.check(inst, inspector.archive(file!!.absolutePath), _state.value.settings.allowReinstall)
            }
            if (reason != null) { fail(reason); return }

            pending = entry to file!!
            if (!installer.canInstallUnknownApps()) {
                _state.value = _state.value.copy(phase = UpdatePhase.NeedsPermission(tag)); return
            }
            handOff(entry, file!!)
        } finally { busy.set(false) }
    }

    /** After the owner allowed "Install unknown apps" and came back. */
    fun continueAfterPermission() {
        val (entry, file) = pending ?: return
        if (!installer.canInstallUnknownApps()) { installer.openUnknownAppsSettings(); return }
        handOff(entry, file)
    }

    fun openPermissionSettings() = installer.openUnknownAppsSettings()

    private fun handOff(entry: ReleaseEntry, file: File) {
        if (installer.launchInstaller(file)) {
            pending = null
            _state.value = _state.value.copy(phase = UpdatePhase.HandedOff(entry.release.tag))
        } else {
            file.delete(); pending = null
            _state.value = _state.value.copy(phase = UpdatePhase.Failed(UpdateFailure.InstallerUnavailable, entry.release.tag))
        }
    }

    /** Cancels a running download, or discards a verified file waiting on the permission. */
    fun cancel() {
        cancelled = true
        pending?.second?.delete(); pending = null
        if (_state.value.phase is UpdatePhase.NeedsPermission || _state.value.phase is UpdatePhase.Failed) {
            _state.value = _state.value.copy(phase = UpdatePhase.Idle)
        }
    }

    fun clearFailure() { if (_state.value.phase is UpdatePhase.Failed) _state.value = _state.value.copy(phase = UpdatePhase.Idle) }
}
