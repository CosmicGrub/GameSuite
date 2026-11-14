package com.gamesuite.profiles

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamesuite.core.Avatar
import com.gamesuite.core.ClubProfileStore
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.ProfileScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The shared profile state for the nav graph: the profile list + active profile, the parent controls, and
 * whether a parent has passed the gate in this visit. Activity.onCreate must call
 * [ProfileStore.bootstrapBlocking] BEFORE this ViewModel is first used (so [ProfileScope] already points at
 * the stored active profile) and hand the result to [seed].
 *
 * FAIL-CLOSED: until the profile list AND the parent controls have been read ([profiles] non-null and
 * [controlsLoaded]) the app runs in the restricted state ([ProfileAccess.resolve]): kid-safe games only, no
 * Nearby / Online. A profile list that exists but is unreadable never turns into an adult default.
 */
class ProfilesViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val controlsStore = ParentControlsStore(app)
    private val club = ClubProfileStore(app)

    /** Judges "today" on a trusted clock; the boot counter tells it when the monotonic reference is gone (reboot). */
    val limiter = PlayTimeLimiter(
        bootId = {
            try { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong() } catch (_: Exception) { 0L }
        }
    )

    private val _profiles = MutableStateFlow<ProfilesState?>(null)
    /** Null until the profile list has been read (and while it is unreadable): the app is restricted meanwhile. */
    val profiles: StateFlow<ProfilesState?> = _profiles

    private val _profilesUnreadable = MutableStateFlow(false)
    /** True when a stored profile list exists but cannot be read (it was quarantined, nothing was overwritten). */
    val profilesUnreadable: StateFlow<Boolean> = _profilesUnreadable

    private val _controls = MutableStateFlow(ControlsState())
    val controls: StateFlow<ControlsState> = _controls

    private val _controlsLoaded = MutableStateFlow(false)
    /** False until the parent controls have been read once; the app is restricted until then. */
    val controlsLoaded: StateFlow<Boolean> = _controlsLoaded

    private val _controlsUnreadable = MutableStateFlow(false)
    /** True when the stored controls cannot be read: kid profiles then run restricted (see [ProfileAccess.resolve]). */
    val controlsUnreadable: StateFlow<Boolean> = _controlsUnreadable

    private val _parentUnlocked = MutableStateFlow(false)
    /** True after the gate was passed; cleared when the kid returns to Kids home or a profile is switched. */
    val parentUnlocked: StateFlow<Boolean> = _parentUnlocked

    /** The launch picker is offered once per process; this remembers it was handled. */
    var pickerHandled: Boolean = false

    init {
        viewModelScope.launch {
            collectWithRetry {
                store.load.collect { applyProfilesLoad(it) }
            }
        }
        viewModelScope.launch {
            collectWithRetry {
                controlsStore.load.collect { applyControlsLoad(it) }
            }
        }
    }

    /** A read error must not crash the app or leave it waiting forever: retry, and stay fail-closed meanwhile. */
    private suspend fun collectWithRetry(block: suspend () -> Unit) {
        while (true) {
            try {
                block()
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(RETRY_MILLIS)
            }
        }
    }

    private fun applyProfilesLoad(l: ProfilesLoad) {
        _profilesUnreadable.value = l.corrupt
        val st = l.state
        when {
            st != null -> _profiles.value = ProfilesLogic.sanitize(st, System.currentTimeMillis())
            // Nothing stored at all (first run, normally already written by the bootstrap): the default adult profile.
            !l.corrupt -> _profiles.value = ProfilesLogic.sanitize(null, System.currentTimeMillis())
            // Unreadable and unrecoverable: keep what we have (null = restricted). NEVER default to an adult.
        }
        syncNetworkPolicy()
    }

    private fun applyControlsLoad(l: ControlsLoad) {
        _controls.value = l.state
        _controlsUnreadable.value = l.corrupt
        _controlsLoaded.value = true
        syncNetworkPolicy()
    }

    /**
     * Feeds the result of the main-thread bootstrap in before the first frame, so a normal start never shows the
     * restricted state; a failed bootstrap (nulls) simply leaves the VM restricted until the collectors deliver.
     */
    fun seed(boot: ProfileStore.Bootstrap) {
        if (_profiles.value == null) boot.profiles?.let { _profiles.value = ProfilesLogic.sanitize(it, System.currentTimeMillis()) }
        if (boot.profilesUnreadable) _profilesUnreadable.value = true
        val c = boot.controls
        if (c != null && !_controlsLoaded.value) {
            _controls.value = c.state
            _controlsUnreadable.value = c.corrupt
            _controlsLoaded.value = true
        }
        syncNetworkPolicy()
    }

    /** Keeps the process-wide network switch fail-closed: true only for a resolved adult with Online play on. */
    private fun syncNetworkPolicy() {
        LaunchPolicy.networkAllowed = currentAccess().networkAllowed
    }

    private fun currentAccess(): ProfileAccess = ProfileAccess.resolve(
        profiles = _profiles.value,
        controls = _controls.value,
        controlsLoaded = _controlsLoaded.value,
        controlsUnreadable = _controlsUnreadable.value,
        parentUnlocked = _parentUnlocked.value,
        allSpecs = GameHubSpecs.all
    )

    fun setParentUnlocked(unlocked: Boolean) { _parentUnlocked.value = unlocked }

    fun switchTo(id: String) {
        val target = _profiles.value?.byId(id) ?: return
        _parentUnlocked.value = false
        ProfileScope.setActive(id)
        // Optimistic: access rules must follow the switch at once, not after the DataStore round-trip.
        _profiles.value = _profiles.value?.let { ProfilesLogic.switch(it, id) }
        syncNetworkPolicy()
        viewModelScope.launch {
            try {
                store.switchTo(id)
                seedClub(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    /** A fresh profile's Club record is empty: give it the picker name and avatar once. */
    private suspend fun seedClub(p: Profile) {
        // A second switch within milliseconds must not write A's name into B's blank Club profile.
        if (ProfileScope.current() != p.id) return
        val cur = club.profile.first()
        if (ProfileScope.current() != p.id) return
        if (cur.displayName.isBlank()) club.setDisplayName(p.name)
        if (cur.avatar == Avatar.DEFAULT && p.avatar != Avatar.DEFAULT.encode()) club.setAvatar(Avatar.decode(p.avatar))
    }

    fun addProfile(name: String, kind: ProfileKind, band: AgeBand?, avatar: Avatar, onResult: (AddResult) -> Unit) {
        viewModelScope.launch {
            val r = try { store.add(name, kind, band, avatar.encode()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { AddResult.BadName(NameResult.Empty) }
            onResult(r)
        }
    }

    fun rename(id: String, name: String, onResult: (NameResult) -> Unit) {
        viewModelScope.launch {
            val r = try { store.rename(id, name) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { NameResult.Empty }
            onResult(r)
        }
    }

    /** Callers must have shown a confirmation dialog first. */
    fun delete(id: String, onResult: (DeleteResult) -> Unit) {
        viewModelScope.launch {
            val r = try { store.delete(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { DeleteResult.NotFound }
            if (r is DeleteResult.Deleted) {
                try { controlsStore.update { it.copy(kids = it.kids - id) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
            onResult(r)
        }
    }

    fun setOnlinePlayEnabled(enabled: Boolean) {
        viewModelScope.launch { try { controlsStore.update { it.copy(onlinePlayEnabled = enabled) } } catch (_: Exception) { } }
    }

    fun updateKid(profileId: String, transform: (KidControls) -> KidControls) {
        viewModelScope.launch {
            try { controlsStore.update { it.withKid(profileId, transform(it.forKid(profileId))) } } catch (_: Exception) { }
        }
    }

    /**
     * Gate settings (unlock method, PIN) may only change when a parent passed the gate in this visit, or the active
     * profile is an adult. A kid profile (or the restricted fail-closed state) can never reach them otherwise.
     */
    private fun mayChangeGateSettings(): Boolean {
        val a = currentAccess()
        return a.parentUnlocked || !a.profile.isKid
    }

    /** Saves the default unlock method; null = automatic. */
    fun setGateMethod(method: GateMethod?) {
        if (!mayChangeGateSettings()) return
        viewModelScope.launch { try { controlsStore.update { it.copy(gateMethod = method) } } catch (_: Exception) { } }
    }

    /** Sets or changes the 4-digit PIN (stored as a salted hash only). [onDone] gets false for a malformed PIN or a failed write. */
    fun setPin(pin: String, selectAsDefault: Boolean, onDone: (Boolean) -> Unit = {}) {
        if (!mayChangeGateSettings() || !PinHasher.isValidPin(pin)) { onDone(false); return }
        viewModelScope.launch {
            val ok = try {
                val stored = withContext(Dispatchers.Default) { PinHasher.create(pin) } ?: throw IllegalStateException()
                controlsStore.update {
                    it.copy(
                        pinSalt = stored.salt,
                        pinHash = stored.hash,
                        gateMethod = if (selectAsDefault) GateMethod.PIN else it.gateMethod
                    )
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            onDone(ok)
        }
    }

    /** Removes the PIN; a default of PIN goes back to automatic. */
    fun clearPin() {
        if (!mayChangeGateSettings()) return
        viewModelScope.launch {
            try {
                controlsStore.update {
                    it.copy(pinSalt = null, pinHash = null, gateMethod = if (it.gateMethod == GateMethod.PIN) null else it.gateMethod)
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * The explicit parent action "start the controls over" after the stored controls turned out to be unreadable.
     * The unreadable value stays in a quarantine key.
     */
    fun resetUnreadableControls() {
        viewModelScope.launch { try { controlsStore.resetUnreadable() } catch (_: Exception) { } }
    }

    /**
     * Foreground play time for a KID profile; a no-op for adults. [forProfileId] pins the profile the time was
     * played under (a flush right after a switch must not credit the NEW active profile); null = the active one.
     */
    fun accrue(deltaMillis: Long, forProfileId: String? = null) {
        val st = _profiles.value ?: return
        val profile = (if (forProfileId != null) st.byId(forProfileId) else st.active) ?: return
        if (!profile.isKid) return
        updateKid(profile.id) { limiter.accrue(it, deltaMillis) }
    }

    /** Keeps the process-wide network switch in step with the active profile and the adult "Online play" setting. */
    fun applyNetworkPolicy(access: ProfileAccess) {
        LaunchPolicy.networkAllowed = access.networkAllowed
    }

    private companion object {
        const val RETRY_MILLIS = 5_000L
    }
}
