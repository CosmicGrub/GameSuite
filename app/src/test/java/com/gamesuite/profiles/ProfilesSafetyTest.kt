package com.gamesuite.profiles

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubStart
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.visibleFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Kid-safety verification tests (profiles audit P2, P3, P6, P9, P11, P14). */
class ProfilesSafetyTest {
    private val now = 1_700_000_000_000L
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @After fun cleanup() {
        LaunchPolicy.resetToFailClosed()
        job.cancel()
    }

    /** In-memory DataStore with the real contract (serialised updateData); a file DataStore cannot rename over its file on Windows JVMs. */
    private class MemStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private fun newStore(@Suppress("UNUSED_PARAMETER") name: String): DataStore<Preferences> = MemStore()

    private val goodJson: String
        get() = ProfilesLogic.encode(
            (ProfilesLogic.add(ProfilesLogic.initial("Sam", null, now), "Mia", ProfileKind.KID, AgeBand.AGE_7_9, "pip|sky|circle", now, "k1") as AddResult.Added).state
        )

    // ---- P3: a decode failure never resets ----
    @Test fun `read distinguishes missing ok and corrupt`() {
        assertEquals(ProfilesRead.Missing, ProfilesLogic.read(null))
        assertEquals(ProfilesRead.Missing, ProfilesLogic.read("   "))
        assertTrue(ProfilesLogic.read(goodJson) is ProfilesRead.Ok)
        val c = ProfilesLogic.read("{broken") as ProfilesRead.Corrupt
        assertEquals("{broken", c.raw)
        assertNull(c.recovered)
    }

    @Test fun `an unknown enum value is corrupt not a silent adult profile and the last good copy recovers`() {
        val future = goodJson.replace("\"KID\"", "\"TEEN\"")
        val c = ProfilesLogic.read(future, goodJson) as ProfilesRead.Corrupt
        assertEquals(future, c.raw)
        assertEquals(2, c.recovered!!.profiles.size)
        assertTrue(c.recovered!!.byId("k1")!!.isKid)
    }

    @Test fun `controls read distinguishes missing ok and corrupt and the display decode stays usable`() {
        assertEquals(ControlsRead.Missing, ControlsCodec.read(null))
        val ok = ControlsCodec.read(ControlsCodec.encode(ControlsState(onlinePlayEnabled = false)))
        assertFalse((ok as ControlsRead.Ok).state.onlinePlayEnabled)
        assertEquals(ControlsRead.Corrupt("nope"), ControlsCodec.read("nope"))
        val load = ControlsCodec.load("nope")
        assertTrue(load.corrupt)
        assertEquals(ControlsState(), load.state)
    }

    @Test fun `profile store refuses to overwrite an unreadable list and keeps the raw value`() = runBlocking {
        val ds = newStore("p1")
        val key = stringPreferencesKey("state_json")
        ds.edit { it[key] = "{garbage" }
        val store = ProfileStore(ds, { now }, { null }, { })
        try {
            store.add("Mia", ProfileKind.KID, AgeBand.AGE_7_9, "a")
            fail("the write must be refused")
        } catch (_: ProfilesUnreadableException) {
        }
        assertEquals("{garbage", ds.data.first()[key])
        assertNotNull(ds.data.first()[stringPreferencesKey("state_json_quarantine")])
        assertTrue(store.load.first().corrupt)
        assertNull(store.ensureInitialized())
    }

    @Test fun `profile store recovers from the last good copy`() = runBlocking {
        val ds = newStore("p2")
        ds.edit {
            it[stringPreferencesKey("state_json")] = "{garbage"
            it[stringPreferencesKey("state_last_good")] = goodJson
        }
        val store = ProfileStore(ds, { now }, { null }, { })
        assertEquals(2, store.ensureInitialized()!!.profiles.size)
        assertTrue(store.add("Zed", ProfileKind.ADULT, null, "a") is AddResult.Added)
        assertEquals(3, store.load.first().state!!.profiles.size)
    }

    // ---- P11: transactional updates ----
    @Test fun `concurrent adds all land and the profile cap holds`() = runBlocking {
        val store = ProfileStore(newStore("p3"), { now }, { null }, { })
        store.ensureInitialized()
        val results = (1..8).map { i ->
            async(Dispatchers.IO) { store.add("Kid$i", ProfileKind.KID, AgeBand.AGE_4_6, "a") }
        }.awaitAll()
        val st = store.load.first().state!!
        assertEquals(MAX_PROFILES, st.profiles.size)
        assertEquals(MAX_PROFILES - 1, results.count { it is AddResult.Added })
        assertEquals(st.profiles.size, st.profiles.map { it.id }.toSet().size)
    }

    @Test fun `a switch racing a rename keeps both`() = runBlocking {
        val store = ProfileStore(newStore("p4"), { now }, { null }, { })
        store.ensureInitialized()
        val id = (store.add("Mia", ProfileKind.KID, AgeBand.AGE_7_9, "a") as AddResult.Added).profile.id
        listOf(async(Dispatchers.IO) { store.switchTo(id) }, async(Dispatchers.IO) { store.rename(id, "Mimi") }).awaitAll()
        val st = store.load.first().state!!
        assertEquals(id, st.activeId)
        assertEquals("Mimi", st.byId(id)!!.name)
    }

    @Test fun `controls store never overwrites an unreadable value`() = runBlocking {
        val ds = newStore("c1")
        val key = stringPreferencesKey("controls_json")
        ds.edit { it[key] = "oops" }
        val store = ParentControlsStore(ds)
        try {
            store.update { it.copy(onlinePlayEnabled = false) }
            fail("the write must be refused")
        } catch (_: ControlsUnreadableException) {
        }
        assertEquals("oops", ds.data.first()[key])
        assertEquals("oops", ds.data.first()[stringPreferencesKey("controls_json_quarantine")])
        store.resetUnreadable() // the explicit parent action
        assertTrue(store.loadOnce().state.onlinePlayEnabled)
        assertFalse(store.loadOnce().corrupt)
    }

    // ---- P2: fail closed ----
    private fun spec(id: String, audience: GameAudience, modes: List<HubModeKind>) = GameHubSpec(
        id, id, "", HubFamily.BOARD,
        modes.map { HubMode(it.name, it, it.name, "", id, HubStart.IMMEDIATE) },
        audience = audience, minAge = 0
    )

    @Test fun `until profiles and controls are read access is restricted kid safe with no network`() {
        val specs = listOf(
            spec("tic-tac-toe", GameAudience.FAMILY, listOf(HubModeKind.VS_CPU, HubModeKind.ONLINE)),
            spec("chess", GameAudience.ADULTS, listOf(HubModeKind.VS_CPU))
        )
        val adult = ProfilesLogic.initial("Sam", null, now)
        val noProfiles = ProfileAccess.resolve(null, ControlsState(), true, false, false, specs)
        val notLoaded = ProfileAccess.resolve(adult, ControlsState(), false, false, false, specs)
        for (a in listOf(noProfiles, notLoaded)) {
            assertFalse(a.networkAllowed)
            assertTrue(a.profile.isKid)
            assertTrue(a.visibleSpecs.none { it.gameId == "chess" })
            assertTrue(a.visibleSpecs.all { s -> s.modes.none { it.kind == HubModeKind.ONLINE || it.kind == HubModeKind.NEARBY } })
        }
        val ready = ProfileAccess.resolve(adult, ControlsState(), true, false, false, specs)
        assertTrue(ready.networkAllowed)
        assertEquals(2, ready.visibleSpecs.size)
    }

    @Test fun `a kid with unreadable controls stays restricted`() {
        val st = (ProfilesLogic.add(ProfilesLogic.initial("Sam", null, now), "Mia", ProfileKind.KID, AgeBand.AGE_10_12, "a", now, "k1") as AddResult.Added).state
            .let { ProfilesLogic.switch(it, "k1") }
        val a = ProfileAccess.resolve(st, ControlsState(), true, true, false, emptyList())
        assertEquals(AgeBand.AGE_4_6, a.profile.ageBand)
        assertFalse(a.networkAllowed)
        assertEquals("k1", a.profile.id)
    }

    // ---- P9: network-only spec ----
    @Test fun `a spec with only network modes is dropped for a kid and for an adult with online off`() {
        val onlyNet = spec("net-only", GameAudience.KIDS, listOf(HubModeKind.NEARBY, HubModeKind.ONLINE))
        val kid = Profile("k1", "Kid", kind = ProfileKind.KID, ageBand = AgeBand.AGE_10_12)
        assertTrue(visibleFor(kid, listOf(onlyNet)).isEmpty())
        assertTrue(visibleFor(Profile("default", "Sam"), listOf(onlyNet), onlineEnabled = false).isEmpty())
        assertEquals(1, visibleFor(Profile("default", "Sam"), listOf(onlyNet)).size)
    }

    // ---- P14: names ----
    @Test fun `names allow combining marks hyphens apostrophes and count code points`() {
        assertEquals(NameResult.Ok("Mary-Jane"), validateProfileName("Mary-Jane"))
        assertEquals(NameResult.Ok("O'Brien"), validateProfileName("O'Brien"))
        assertTrue(validateProfileName("José") is NameResult.Ok)
        assertTrue(validateProfileName("किताब") is NameResult.Ok)
        val astral = "𝐀" // one code point, two UTF-16 chars
        assertTrue(validateProfileName(astral.repeat(16)) is NameResult.Ok)
        assertEquals(NameResult.TooLong, validateProfileName(astral.repeat(17)))
    }

    @Test fun `names reject emoji bidi zero width control and stacked marks`() {
        for (bad in listOf("Zoe😀", "a‮b", "a​b", "a\u0007b", "á̂̃̄̅̆b", "́a", "A_B", "x️")) {
            assertEquals(bad, NameResult.InvalidChars, validateProfileName(bad))
        }
    }

    // ---- P6: the in-place restart gate ----
    @Test fun `requestNewRound is open normally and refused and counted when a kid is out of time`() {
        LaunchPolicy.resetToFailClosed()
        val before = LaunchPolicy.blockedStarts.value
        assertTrue(LaunchPolicy.requestNewRound())
        assertEquals(before, LaunchPolicy.blockedStarts.value)
        LaunchPolicy.timeUp = true
        assertFalse(LaunchPolicy.requestNewRound())
        assertFalse(LaunchPolicy.requestNewRound())
        assertEquals(before + 2, LaunchPolicy.blockedStarts.value)
        LaunchPolicy.timeUp = false
        assertTrue(LaunchPolicy.requestNewRound())
    }
}
