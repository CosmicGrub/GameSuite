package com.gamesuite.audio.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gamesuite.settings.AppSettings
import com.gamesuite.settings.MusicSource
import com.gamesuite.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Short, localizable problem codes shown on the music settings screen. */
enum class MusicMessage {
    FOLDER_ACCESS_LOST, SCAN_FAILED, NO_TRACKS_FOUND, PLAYBACK_FAILED, FOCUS_DENIED, SAVE_FAILED
}

data class MusicUiState(
    val library: MusicLibraryData = MusicLibraryData(),
    val libraryLoaded: Boolean = false,
    val scanning: Boolean = false,
    val scanCount: Int = 0,
    val message: MusicMessage? = null,
    /** True when the library source is active and the playing context has no enabled tracks. */
    val emptyPlaylist: Boolean = false,
    val activeContext: MusicContext = MusicContext.MENU,
    val nowPlaying: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val libraryActive: Boolean = false
)

/**
 * App-wide music coordinator for the user-library source. Obtain with [get]. It reads the music
 * settings itself (own [SettingsRepository] over the shared DataStore), so callers only report
 * the current nav route and app foreground/background.
 *
 * Sources: AMBIENCE/OFF = this class plays nothing (the procedural engine is gated elsewhere);
 * LIBRARY = [LibraryPlayer] with audio focus; OTHER_APPS = nothing is played and audio focus is
 * never requested, so another app's playback is left alone.
 */
class MusicManager private constructor(context: Context) : LibraryPlayer.Callbacks {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = MusicLibraryStore(app)
    private val scanner = MusicScanner(app)
    private val saveMutex = Mutex()
    private val player = LibraryPlayer(app, this)
    private val queue = PlaybackQueue()
    /** Queue position as of the last track that actually started; advances are undone to this. */
    private var committed = queue.snapshot()

    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private var settings = AppSettings()
    private var currentContext = MusicContext.MENU
    private var backgrounded = false
    private var userPaused = false
    private var libraryActive = false
    private var consecutiveFailures = 0
    private var scanJob: Job? = null

    init {
        pendingRoute?.let { currentContext = contextForRoute(it) }
        _state.update { it.copy(activeContext = currentContext) }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(library = loaded, libraryLoaded = true) }
            checkFolderPermission(loaded)
            refreshQueue(contextChanged = false)
        }
        scope.launch {
            try {
                SettingsRepository(app).settings.collect { applySettings(it) }
            } catch (_: Exception) {
            }
        }
    }

    // ---- public API used by the shell -----------------------------------------------------

    fun setContextForRoute(route: String) = onMain {
        val ctx = contextForRoute(route)
        if (ctx == currentContext) return@onMain
        currentContext = ctx
        _state.update { it.copy(activeContext = ctx) }
        refreshQueue(contextChanged = true)
    }

    fun onAppBackgrounded() = onMain {
        backgrounded = true
        if (libraryActive) player.pause()
    }

    fun onAppForegrounded() = onMain {
        backgrounded = false
        if (libraryActive && !userPaused) resumeOrStart()
    }

    // ---- transport (Now Playing controls) -------------------------------------------------

    fun togglePlayPause() = onMain {
        if (!libraryActive) return@onMain
        if (player.isPlaying) {
            userPaused = true
            player.pause()
        } else {
            userPaused = false
            resumeOrStart()
        }
    }

    fun skipNext() = onMain {
        if (!libraryActive) return@onMain
        userPaused = false
        val id = queue.next(settings.musicShuffle)
        if (id == null) player.fadeOutAndStop(FADE_STOP_MS) else playById(id, crossfadeMsFor(id))
    }

    fun skipPrevious() = onMain {
        if (!libraryActive) return@onMain
        userPaused = false
        val id = queue.previous() ?: queue.current
        if (id == null) player.fadeOutAndStop(FADE_STOP_MS) else playById(id, crossfadeMsFor(id))
    }

    // ---- folder + library -----------------------------------------------------------------

    /** Take a persistable read grant for [treeUri], remember it, and scan it. */
    fun setFolder(treeUri: Uri) = onMain {
        try {
            app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some providers refuse persistence; scanning may still work this session.
        }
        val old = _state.value.library.folderUri
        if (old != null && old != treeUri.toString()) releaseGrant(old)
        _state.update { it.copy(library = it.library.copy(folderUri = treeUri.toString())) }
        persist()
        rescan()
    }

    fun forgetFolder() = onMain {
        scanJob?.cancel()
        _state.value.library.folderUri?.let { releaseGrant(it) }
        player.stop()
        queue.setList(emptyList())
        _state.update {
            it.copy(
                library = MusicLibraryData(),
                scanning = false,
                message = null,
                nowPlaying = null,
                isPlaying = false,
                emptyPlaylist = libraryActive
            )
        }
        persist()
    }

    fun rescan() = onMain {
        val folder = _state.value.library.folderUri ?: return@onMain
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, scanCount = 0, message = null) }
        scanJob = scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    scanner.scan(
                        Uri.parse(folder),
                        isCancelled = { !isActive },
                        onProgress = { n -> _state.update { s -> s.copy(scanCount = n) } }
                    )
                }
                val lib = _state.value.library
                val ids = tracks.map { it.id }
                val reconciled = lib.playlists.mapValues { (_, list) -> Playlists.reconcile(list, ids) }
                _state.update {
                    it.copy(
                        library = lib.copy(tracks = tracks, playlists = reconciled),
                        scanning = false,
                        message = if (tracks.isEmpty()) MusicMessage.NO_TRACKS_FOUND else null
                    )
                }
                persist()
                refreshQueue(contextChanged = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                _state.update { it.copy(scanning = false, message = MusicMessage.FOLDER_ACCESS_LOST) }
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, message = MusicMessage.SCAN_FAILED) }
            }
        }
    }

    private fun releaseGrant(uriString: String) {
        try {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun checkFolderPermission(lib: MusicLibraryData) {
        val folder = lib.folderUri ?: return
        val ok = try {
            app.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission }
        } catch (_: Exception) {
            true
        }
        if (!ok) _state.update { it.copy(message = MusicMessage.FOLDER_ACCESS_LOST) }
    }

    // ---- playlist editing -----------------------------------------------------------------

    fun playlistFor(context: MusicContext): List<PlaylistEntry> = _state.value.library.playlistFor(context)

    fun setTrackEnabled(context: MusicContext, trackId: String, enabled: Boolean) =
        editPlaylist(context) { Playlists.toggle(it, trackId, enabled) }

    fun moveTrack(context: MusicContext, index: Int, delta: Int) =
        editPlaylist(context) { Playlists.move(it, index, delta) }

    fun useAllTracks(context: MusicContext) = editPlaylist(context) { Playlists.enableAll(it) }

    private fun editPlaylist(context: MusicContext, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = onMain {
        val lib = _state.value.library
        val updated = edit(lib.playlistFor(context))
        _state.update { it.copy(library = lib.withPlaylist(context, updated)) }
        persist()
        if (context == currentContext) refreshQueue(contextChanged = false)
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                val ok = store.save(_state.value.library)
                if (!ok) _state.update { it.copy(message = MusicMessage.SAVE_FAILED) }
            }
        }
    }

    // ---- settings -------------------------------------------------------------------------

    private fun applySettings(s: AppSettings) {
        settings = s
        player.volume = s.musicVolume
        player.crossfadeSeconds = s.musicCrossfadeSeconds
        val wantLibrary = s.musicSource == MusicSource.LIBRARY && s.soundEnabled
        if (wantLibrary == libraryActive) return
        libraryActive = wantLibrary
        _state.update { it.copy(libraryActive = wantLibrary) }
        if (wantLibrary) {
            userPaused = false
            consecutiveFailures = 0
            refreshQueue(contextChanged = false)
        } else {
            // Off / ambience / other apps: stop and release focus so nothing of ours interferes.
            player.stop()
            _state.update { it.copy(nowPlaying = null, isPlaying = false, emptyPlaylist = false) }
        }
    }

    // ---- playback core --------------------------------------------------------------------

    /** Rebuild the queue from the playing context's enabled tracks and react to the change. */
    @Suppress("UNUSED_PARAMETER")
    private fun refreshQueue(contextChanged: Boolean) {
        val lib = _state.value.library
        val ids = Playlists.enabledIds(lib.playlistFor(currentContext))
        queue.setList(ids)
        _state.update { it.copy(emptyPlaylist = libraryActive && ids.isEmpty()) }
        if (!libraryActive) return
        if (ids.isEmpty()) {
            player.fadeOutAndStop(FADE_STOP_MS)
            _state.update { it.copy(nowPlaying = null) }
            return
        }
        if (backgrounded || userPaused) return // resumeOrStart() picks up the new list later
        val cur = queue.current
        val curOk = cur != null && queue.contains(cur)
        if (curOk && player.hasActiveTrack) return // still enabled in this context: keep playing it
        consecutiveFailures = 0
        val id = if (curOk) cur else queue.next(settings.musicShuffle)
        if (id != null) playById(id, crossfadeMsFor(id))
    }

    private fun resumeOrStart() {
        if (!libraryActive) return
        val cur = queue.current
        val curOk = cur != null && queue.contains(cur)
        if (curOk && player.resume()) return
        val id = if (curOk) cur else queue.next(settings.musicShuffle)
        if (id == null) {
            _state.update { it.copy(emptyPlaylist = true) }
            return
        }
        playById(id, crossfadeMsFor(id))
    }

    private fun crossfadeMsFor(trackId: String): Long {
        val dur = _state.value.library.trackById(trackId)?.durationMs ?: 0L
        return Crossfade.effectiveMs(settings.musicCrossfadeSeconds, dur)
    }

    private fun playById(id: String, fadeMs: Long, nearEnd: Boolean = false) {
        val track = _state.value.library.trackById(id)
        if (track == null) {
            onTrackFailed(id)
            return
        }
        queue.markCurrent(id)
        player.play(track, fadeMs, nearEnd)
    }

    // ---- LibraryPlayer.Callbacks (main thread) --------------------------------------------

    override fun onTrackStarted(trackId: String, durationMs: Long) {
        consecutiveFailures = 0
        if (queue.current == trackId) committed = queue.snapshot()
        val track = _state.value.library.trackById(trackId)
        _state.update { it.copy(nowPlaying = track) }
    }

    override fun onTrackFailed(trackId: String) {
        if (!libraryActive || backgrounded || userPaused) return
        consecutiveFailures++
        val limit = maxOf(1, Playlists.enabledIds(_state.value.library.playlistFor(currentContext)).size)
        if (consecutiveFailures >= limit) {
            player.stop()
            _state.update { it.copy(message = MusicMessage.PLAYBACK_FAILED, nowPlaying = null, isPlaying = false) }
            return
        }
        advanceAfterEnd(fadeMs = 0L)
    }

    override fun onWantsNext(nearEnd: Boolean) {
        if (!libraryActive || backgrounded || userPaused) return
        advanceAfterEnd(if (nearEnd) -1L else 0L)
    }

    /** [fadeMs] < 0 means "use the track's crossfade". */
    private fun advanceAfterEnd(fadeMs: Long) {
        val outgoingId = queue.current
        val id = queue.next(settings.musicShuffle)
        if (id == null) {
            player.stop()
            _state.update { it.copy(nowPlaying = null, emptyPlaylist = true) }
            return
        }
        // Only the natural near-end path (fadeMs < 0) may hold the hand-over for its fade window.
        playById(id, if (fadeMs < 0L) overlapMsFor(outgoingId, id) else fadeMs, nearEnd = fadeMs < 0L)
    }

    /** Near-end hand-over: capped by both the outgoing and the incoming track. */
    private fun overlapMsFor(outgoingId: String?, incomingId: String): Long {
        val lib = _state.value.library
        val outDur = outgoingId?.let { lib.trackById(it)?.durationMs } ?: 0L
        val inDur = lib.trackById(incomingId)?.durationMs ?: 0L
        return Crossfade.overlapMs(settings.musicCrossfadeSeconds, outDur, inDur)
    }

    /** A queued advance never became audible: put the queue back on the track that is. */
    override fun onPendingCancelled() {
        queue.restore(committed)
    }

    override fun onPlayingChanged() {
        _state.update { it.copy(isPlaying = player.isPlaying) }
    }

    override fun onFocusDenied() {
        queue.restore(committed)
        _state.update { it.copy(message = MusicMessage.FOCUS_DENIED, isPlaying = false) }
    }

    override fun onFocusLostPermanently() {
        // Another music app took over for good: don't snatch it back when we return to the foreground.
        userPaused = true
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        val run = Runnable {
            try { block() } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    companion object {
        private const val FADE_STOP_MS = 600L

        @Volatile private var instance: MusicManager? = null
        @Volatile private var pendingRoute: String? = null

        /** Application-context singleton. */
        fun get(context: Context): MusicManager =
            instance ?: synchronized(this) {
                instance ?: MusicManager(context.applicationContext).also { instance = it }
            }

        // Static forwarders so callers may also write MusicManager.setContextForRoute(route).
        fun setContextForRoute(route: String) {
            pendingRoute = route
            instance?.setContextForRoute(route)
        }

        fun onAppBackgrounded() {
            instance?.onAppBackgrounded()
        }

        fun onAppForegrounded() {
            instance?.onAppForegrounded()
        }
    }
}
