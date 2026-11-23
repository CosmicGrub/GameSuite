package com.gamesuite.profiles

import com.gamesuite.core.ProfileScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesLogicTest {
    private val now = 1_000L

    // ---- name rules ----
    @Test fun `name is trimmed and spaces collapsed`() {
        assertEquals(NameResult.Ok("Ana Maria"), validateProfileName("  Ana    Maria "))
    }

    @Test fun `empty and blank names are rejected`() {
        assertEquals(NameResult.Empty, validateProfileName(""))
        assertEquals(NameResult.Empty, validateProfileName("   "))
    }

    @Test fun `sixteen characters ok seventeen too long`() {
        assertEquals(NameResult.Ok("a".repeat(16)), validateProfileName("a".repeat(16)))
        assertEquals(NameResult.TooLong, validateProfileName("a".repeat(17)))
    }

    @Test fun `only letters digits and spaces`() {
        assertEquals(NameResult.Ok("Zoe 7"), validateProfileName("Zoe 7"))
        assertEquals(NameResult.Ok("José"), validateProfileName("José"))
        assertEquals(NameResult.InvalidChars, validateProfileName("Zoe!"))
        assertEquals(NameResult.InvalidChars, validateProfileName("a@b"))
    }

    @Test fun `link like names are rejected`() {
        assertEquals(NameResult.LooksLikeLink, validateProfileName("http foo"))
        assertEquals(NameResult.LooksLikeLink, validateProfileName("www bad"))
        assertEquals(NameResult.LooksLikeLink, validateProfileName("a.com/x http"))
        assertEquals(NameResult.LooksLikeLink, validateProfileName("w w w x"))
    }

    @Test fun `duplicate names ignore case`() {
        assertEquals(NameResult.Duplicate, validateProfileName("ana", listOf("Ana")))
    }

    // ---- migration ----
    @Test fun `migration wraps the existing user into the default adult profile`() {
        val st = ProfilesLogic.initial("Sam", "bolt|green|hexagon", now)
        assertEquals(1, st.profiles.size)
        val p = st.active
        assertEquals(ProfileScope.DEFAULT_PROFILE_ID, p.id)
        assertEquals("Sam", p.name)
        assertEquals("bolt|green|hexagon", p.avatar)
        assertEquals(ProfileKind.ADULT, p.kind)
        assertFalse(st.needsPicker)
    }

    @Test fun `migration with blank or invalid legacy name falls back to Player`() {
        assertEquals("Player", ProfilesLogic.initial("", null, now).active.name)
        assertEquals("Player", ProfilesLogic.initial("a@@b", null, now).active.name)
    }

    @Test fun `default profile maps to the existing store names with no suffix`() {
        assertEquals("", ProfileScope.suffixOf(ProfileScope.DEFAULT_PROFILE_ID))
        assertEquals("game_stats", ProfileScope.scopedName("game_stats", ProfileScope.DEFAULT_PROFILE_ID))
        assertEquals("game_stats.k1", ProfileScope.scopedName("game_stats", "k1"))
        // an unsafe id can never produce a path: it falls back to the default names
        assertEquals("game_stats", ProfileScope.scopedName("game_stats", "../x"))
    }

    // ---- add / rename / delete / switch ----
    private fun base() = ProfilesLogic.initial("Sam", null, now)

    @Test fun `add kid defaults an age band and adult has none`() {
        val a = ProfilesLogic.add(base(), "Mia", ProfileKind.KID, null, "pip|sky|circle", now, "k1") as AddResult.Added
        assertEquals(AgeBand.AGE_7_9, a.profile.ageBand)
        val b = ProfilesLogic.add(a.state, "Dad", ProfileKind.ADULT, AgeBand.AGE_4_6, "pip|sky|circle", now, "k2") as AddResult.Added
        assertNull(b.profile.ageBand)
        assertTrue(b.state.needsPicker)
    }

    @Test fun `add rejects bad and duplicate names and the seventh profile`() {
        assertTrue(ProfilesLogic.add(base(), "", ProfileKind.KID, null, "", now, "k1") is AddResult.BadName)
        assertTrue(ProfilesLogic.add(base(), "sam", ProfileKind.KID, null, "", now, "k1") is AddResult.BadName)
        var st = base()
        for (i in 1 until MAX_PROFILES) {
            st = (ProfilesLogic.add(st, "P$i", ProfileKind.ADULT, null, "", now, "k$i") as AddResult.Added).state
        }
        assertEquals(MAX_PROFILES, st.profiles.size)
        assertTrue(ProfilesLogic.add(st, "Extra", ProfileKind.ADULT, null, "", now, "kx") is AddResult.TooMany)
    }

    @Test fun `rename validates against the other profiles only`() {
        val st = (ProfilesLogic.add(base(), "Mia", ProfileKind.KID, null, "", now, "k1") as AddResult.Added).state
        assertEquals(NameResult.Duplicate, ProfilesLogic.rename(st, "k1", "Sam").second)
        val (next, r) = ProfilesLogic.rename(st, "k1", "Mia")
        assertTrue(r is NameResult.Ok)
        assertEquals("Mia", next.byId("k1")!!.name)
    }

    @Test fun `delete refuses default active and unknown`() {
        val st0 = base()
        assertEquals(DeleteResult.IsDefault, ProfilesLogic.delete(st0, ProfileScope.DEFAULT_PROFILE_ID))
        val st = (ProfilesLogic.add(st0, "Mia", ProfileKind.KID, null, "", now, "k1") as AddResult.Added).state
        assertTrue(ProfilesLogic.delete(st, "k1") is DeleteResult.Deleted)
        assertEquals(DeleteResult.IsActive, ProfilesLogic.delete(ProfilesLogic.switch(st, "k1"), "k1"))
        assertEquals(DeleteResult.NotFound, ProfilesLogic.delete(st, "nope"))
    }

    @Test fun `the last adult cannot be deleted`() {
        val adult = Profile("k9", "Pat", kind = ProfileKind.ADULT)
        val kid = Profile("k8", "Kid", kind = ProfileKind.KID, ageBand = AgeBand.AGE_7_9)
        val st = ProfilesState(listOf(kid, adult), "k8")
        assertEquals(DeleteResult.LastAdult, ProfilesLogic.delete(st, "k9"))
    }

    @Test fun `switch ignores unknown ids`() {
        val st = base()
        assertEquals(st, ProfilesLogic.switch(st, "ghost"))
    }

    @Test fun `sanitize repairs active id duplicates and missing adult`() {
        val kid = Profile("k1", "Kid", kind = ProfileKind.KID, ageBand = AgeBand.AGE_4_6)
        val fixed = ProfilesLogic.sanitize(ProfilesState(listOf(kid, kid), "ghost"), now)
        assertTrue(fixed.profiles.any { it.kind == ProfileKind.ADULT })
        assertEquals(1, fixed.profiles.count { it.id == "k1" })
        assertTrue(fixed.byId(fixed.activeId) != null)
        assertEquals(ProfileScope.DEFAULT_PROFILE_ID, ProfilesLogic.sanitize(null, now).activeId)
    }

    @Test fun `state round trips through json and garbage decodes to null`() {
        val st = (ProfilesLogic.add(base(), "Mia", ProfileKind.KID, AgeBand.AGE_10_12, "bun|pink|square", now, "k1") as AddResult.Added).state
        assertEquals(st, ProfilesLogic.decode(ProfilesLogic.encode(st)))
        assertNull(ProfilesLogic.decode("{not json"))
        assertNull(ProfilesLogic.decode(null))
    }

    @Test fun `new ids are safe for file names`() {
        repeat(50) { assertTrue(ProfileScope.isSafeId(ProfilesLogic.newId())) }
        assertFalse(ProfileScope.isSafeId("../x"))
        assertFalse(ProfileScope.isSafeId(""))
    }

    // ---- controls codec ----
    @Test fun `controls codec round trips and is lenient`() {
        val c = ControlsState(
            false,
            mapOf("k1" to KidControls(dailyMinutes = 30, hiddenGameIds = setOf("snake"), dailyHistory = mapOf(5L to 600_000L)))
        )
        assertEquals(c, ControlsCodec.decode(ControlsCodec.encode(c)))
        assertEquals(ControlsState(), ControlsCodec.decode("garbage"))
        assertTrue(ControlsCodec.decode(null).onlinePlayEnabled)
    }
}
