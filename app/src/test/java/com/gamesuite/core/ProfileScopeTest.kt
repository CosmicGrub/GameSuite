package com.gamesuite.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileScopeTest {
    @After fun reset() = ProfileScope.setActive(ProfileScope.DEFAULT_PROFILE_ID)

    @Test fun `default profile keeps every existing store and directory name`() {
        assertEquals("club_profile", ProfileScope.scopedName("club_profile", "default"))
        assertEquals("favorites", ProfileScope.prefsName("favorites"))
        assertEquals(File("files", "saved_games"), ProfileScope.savedGamesDir(File("files"), "default"))
    }

    @Test fun `another profile gets suffixed names`() {
        ProfileScope.setActive("k1a2b3c4")
        assertEquals("favorites.k1a2b3c4", ProfileScope.prefsName("favorites"))
        assertEquals(File("files", "saved_games.k1a2b3c4"), ProfileScope.savedGamesDir(File("files")))
        assertEquals("k1a2b3c4", ProfileScope.activeId.value)
    }

    @Test fun `unsafe ids fall back to the default profile`() {
        ProfileScope.setActive("../etc")
        assertEquals("default", ProfileScope.current())
        assertFalse(ProfileScope.isSafeId("a b"))
        assertFalse(ProfileScope.isSafeId("A"))
        assertTrue(ProfileScope.isSafeId("k0f"))
    }

    @Test fun `device level stores stay shared`() {
        assertTrue("app_settings" in ProfileScope.deviceLevelStores)
        assertTrue("profiles" in ProfileScope.deviceLevelStores)
        assertTrue("profile_controls" in ProfileScope.deviceLevelStores)
        assertFalse("game_stats" in ProfileScope.deviceLevelStores)
        assertFalse("club_profile" in ProfileScope.deviceLevelStores)
    }
}
