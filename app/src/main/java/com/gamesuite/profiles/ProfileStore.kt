package com.gamesuite.profiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.core.ClubProfileStore
import com.gamesuite.core.ProfileScope
import com.gamesuite.core.gameDataStore
import com.gamesuite.stats.quarantineValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val Context.profilesDataStore: DataStore<Preferences> by gameDataStore("profiles")

/** What the app shows from the profiles store: [state] null = nothing usable; [corrupt] = a stored value exists but is unreadable. */
data class ProfilesLoad(val state: ProfilesState?, val corrupt: Boolean)

/** Thrown by a [ProfileStore] write when the stored list is unreadable and has no usable last-good copy: nothing was written. */
class ProfilesUnreadableException : Exception("Stored profiles are unreadable; the raw value was quarantined and kept.")

/**
 * DataStore ("profiles", device level) holding the profile list and the active profile id as one JSON blob.
 * Every mutation is a pure [ProfilesLogic] transform applied to the value read INSIDE one atomic edit (never to an
 * earlier snapshot), so two quick adds, or a delete racing a switch, cannot overwrite each other.
 *
 * A stored value that cannot be decoded is NEVER replaced by defaults: the first unreadable value is parked in a
 * quarantine key, a copy of the last good state (written with every successful write) is used to recover, and when
 * even that is missing the write is refused.
 */
class ProfileStore(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private object Keys {
        val STATE = stringPreferencesKey("state_json")
        val LAST_GOOD = stringPreferencesKey("state_last_good")
        val QUARANTINE = stringPreferencesKey("state_json_quarantine")
    }

    /** Null until the first run has migrated the single user into the default profile (also null when unreadable, see [load]). */
    val state: Flow<ProfilesState?> = context.profilesDataStore.data.map { loadOf(it).state }

    /** [state] plus whether the stored value was unreadable (the app then fails closed instead of showing an adult default). */
    val load: Flow<ProfilesLoad> = context.profilesDataStore.data.map { loadOf(it) }

    private fun loadOf(prefs: Preferences): ProfilesLoad =
        when (val r = ProfilesLogic.read(prefs[Keys.STATE], prefs[Keys.LAST_GOOD])) {
            ProfilesRead.Missing -> ProfilesLoad(null, corrupt = false)
            is ProfilesRead.Ok -> ProfilesLoad(r.state, corrupt = false)
            is ProfilesRead.Corrupt -> ProfilesLoad(r.recovered, corrupt = r.recovered == null)
        }

    /**
     * Applies [transform] to the CURRENT stored state inside one edit. [transform] returns the state to store, or
     * null to leave the store untouched. Throws [ProfilesUnreadableException] (nothing written) when the stored
     * value is unreadable and cannot be recovered from the last-good copy.
     */
    private suspend fun mutate(transform: (ProfilesState) -> ProfilesState?) {
        var blocked = false
        context.profilesDataStore.edit { prefs ->
            val cur: ProfilesState = when (val r = ProfilesLogic.read(prefs[Keys.STATE], prefs[Keys.LAST_GOOD])) {
                ProfilesRead.Missing -> ProfilesLogic.sanitize(null, clock())
                is ProfilesRead.Ok -> ProfilesLogic.sanitize(r.state, clock())
                is ProfilesRead.Corrupt -> {
                    quarantineValue(prefs[Keys.QUARANTINE], r.raw)?.let { prefs[Keys.QUARANTINE] = it }
                    if (r.recovered == null) { blocked = true; return@edit }
                    ProfilesLogic.sanitize(r.recovered, clock())
                }
            }
            val next = transform(cur) ?: return@edit
            val encoded = ProfilesLogic.encode(next)
            prefs[Keys.STATE] = encoded
            prefs[Keys.LAST_GOOD] = encoded
        }
        if (blocked) throw ProfilesUnreadableException()
    }

    /**
     * The migration: if no profile list exists yet, wrap the existing Club profile (read from the default,
     * unsuffixed stores) into the default adult profile. Must run while [ProfileScope] is still on the default
     * profile (app start). Returns the (possibly new) state, or null when the stored list is unreadable and
     * cannot be recovered (the caller then fails closed; nothing was overwritten).
     */
    suspend fun ensureInitialized(): ProfilesState? {
        val prefs = context.profilesDataStore.data.first()
        return when (val r = ProfilesLogic.read(prefs[Keys.STATE], prefs[Keys.LAST_GOOD])) {
            is ProfilesRead.Ok -> ProfilesLogic.sanitize(r.state, clock())
            is ProfilesRead.Corrupt -> {
                context.profilesDataStore.edit { p ->
                    quarantineValue(p[Keys.QUARANTINE], r.raw)?.let { p[Keys.QUARANTINE] = it }
                }
                r.recovered?.let { ProfilesLogic.sanitize(it, clock()) }
            }
            ProfilesRead.Missing -> {
                val club = runCatching { ClubProfileStore(context).profile.first() }.getOrNull()
                val fresh = ProfilesLogic.initial(club?.displayName ?: "", club?.avatar?.encode(), clock())
                var written: ProfilesState = fresh
                // Inside the edit: if another writer created the list meanwhile, keep theirs.
                mutateFirstRun(fresh) { written = it }
                written
            }
        }
    }

    private suspend fun mutateFirstRun(fresh: ProfilesState, onResult: (ProfilesState) -> Unit) {
        context.profilesDataStore.edit { prefs ->
            when (val r = ProfilesLogic.read(prefs[Keys.STATE], prefs[Keys.LAST_GOOD])) {
                ProfilesRead.Missing -> {
                    val encoded = ProfilesLogic.encode(fresh)
                    prefs[Keys.STATE] = encoded
                    prefs[Keys.LAST_GOOD] = encoded
                    onResult(fresh)
                }
                is ProfilesRead.Ok -> onResult(ProfilesLogic.sanitize(r.state, clock()))
                is ProfilesRead.Corrupt -> {
                    quarantineValue(prefs[Keys.QUARANTINE], r.raw)?.let { prefs[Keys.QUARANTINE] = it }
                    onResult(r.recovered?.let { ProfilesLogic.sanitize(it, clock()) } ?: fresh)
                }
            }
        }
    }

    suspend fun add(rawName: String, kind: ProfileKind, ageBand: AgeBand?, avatar: String): AddResult {
        var result: AddResult = AddResult.BadName(NameResult.Empty)
        mutate { cur ->
            val r = ProfilesLogic.add(cur, rawName, kind, ageBand, avatar, clock(), ProfilesLogic.newUniqueId(cur))
            result = r
            (r as? AddResult.Added)?.state
        }
        return result
    }

    suspend fun rename(id: String, rawName: String): NameResult {
        var result: NameResult = NameResult.Empty
        mutate { cur ->
            val (next, r) = ProfilesLogic.rename(cur, id, rawName)
            result = r
            if (r is NameResult.Ok) next else null
        }
        return result
    }

    /** Deletes the profile record and its files. Refusals are described by [DeleteResult]. */
    suspend fun delete(id: String): DeleteResult {
        var result: DeleteResult = DeleteResult.NotFound
        mutate { cur ->
            val r = ProfilesLogic.delete(cur, id)
            result = r
            (r as? DeleteResult.Deleted)?.state
        }
        if (result is DeleteResult.Deleted) {
            // File deletes (and a recursive delete of saved games) are blocking IO: never on the main thread.
            withContext(Dispatchers.IO) { ProfileScope.deleteProfileData(context, id) }
        }
        return result
    }

    suspend fun switchTo(id: String) = mutate { ProfilesLogic.switch(it, id) }

    /** What [bootstrapBlocking] produced; every field is null / false-safe so a failure can be handled closed. */
    class Bootstrap(
        /** The active-profile list, or null when it could not be read (unreadable or an IO error): fail closed. */
        val profiles: ProfilesState?,
        /** True when a stored list exists but is unreadable and unrecoverable. */
        val profilesUnreadable: Boolean,
        /** The parent controls, or null when they could not be read. */
        val controls: ControlsLoad?
    ) {
        /** True once both profiles and controls were read: the app may leave the fail-closed state. */
        val resolved: Boolean get() = profiles != null && controls != null && !controls.corrupt
    }

    companion object {
        private const val BOOTSTRAP_TIMEOUT_MILLIS = 2_500L

        @Volatile private var bootstrapResult: Bootstrap? = null
        private val bootstrapLock = Any()

        /**
         * App-start bootstrap (call from Activity.onCreate before setContent): runs the migration, points
         * [ProfileScope] at the stored active profile so every scoped store opens the right file from the first
         * read, reads the parent controls once and hydrates the parent gate's persisted lock state.
         *
         * Runs ONCE per process (an Activity re-creation gets the cached result). It blocks the main thread for the
         * few DataStore reads it needs, capped by [BOOTSTRAP_TIMEOUT_MILLIS], and NEVER throws: on an IO error or a
         * timeout it returns a result with nulls and the caller stays fail-closed.
         */
        fun bootstrapBlocking(context: Context): Bootstrap {
            bootstrapResult?.let { return it }
            return synchronized(bootstrapLock) {
                bootstrapResult ?: runBootstrap(context.applicationContext).also { bootstrapResult = it }
            }
        }

        private fun runBootstrap(app: Context): Bootstrap {
            var profiles: ProfilesState? = null
            var unreadable = false
            var controls: ControlsLoad? = null
            try {
                runBlocking {
                    withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) {
                        val store = ProfileStore(app)
                        val st = store.ensureInitialized()
                        if (st != null) {
                            ProfileScope.setActive(st.active.id)
                            profiles = st
                        } else {
                            unreadable = true
                        }
                        val cs = ParentControlsStore(app)
                        controls = runCatching { cs.loadOnce() }.getOrNull()
                        runCatching { ProcessGateStateStore.instance.hydrate(cs.loadGateOnce()) }
                    }
                }
            } catch (e: Exception) {
                // IOException, a timeout, ...: whatever was not read stays null and the app fails closed.
            }
            installGatePersistence(ParentControlsStore(app))
            return Bootstrap(profiles, unreadable, controls)
        }

        /** Gate changes are written by one consumer, newest value wins, so order can never be reversed. */
        private fun installGatePersistence(controlsStore: ParentControlsStore) {
            val channel = Channel<GateLockState>(Channel.CONFLATED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                for (s in channel) {
                    try { controlsStore.saveGate(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                }
            }
            ProcessGateStateStore.instance.persist = { s -> channel.trySend(s) }
        }
    }
}
