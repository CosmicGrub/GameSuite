package com.gamesuite.style

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StylePrefsStoreTest {
    private fun withStore(block: suspend (StylePrefsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val file = File.createTempFile("style_prefs", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
            runBlocking { block(StylePrefsStore(ds), ds) }
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun defaultsWhenNothingIsStored() = withStore { s, _ -> assertEquals(StylePrefs(), s.prefs.first()) }

    @Test fun roundTripsEveryAxisAndOverrides() = withStore { s, _ ->
        s.setArt(ArtTier.FULL_3D); s.setMotion(MotionLevel.CALM); s.setQuality(QualityLevel.BALANCED)
        s.setOverride("gem-cascade", ArtTier.FLAT); s.setOverride("chess", ArtTier.SOFT_25D)
        val p = s.prefs.first()
        assertEquals(ArtTier.FULL_3D, p.art); assertEquals(MotionLevel.CALM, p.motion); assertEquals(QualityLevel.BALANCED, p.quality)
        assertEquals(ArtTier.FLAT, p.artFor("gem-cascade")); assertEquals(ArtTier.SOFT_25D, p.artFor("chess")); assertEquals(ArtTier.FULL_3D, p.artFor("other"))
        s.setOverride("gem-cascade", null)
        assertEquals(ArtTier.FULL_3D, s.prefs.first().artFor("gem-cascade"))
    }

    @Test fun resetRestoresDefaults() = withStore { s, _ ->
        s.setArt(ArtTier.FLAT); s.setOverride("x", ArtTier.FLAT); s.reset()
        assertEquals(StylePrefs(), s.prefs.first())
    }

    @Test fun unknownStoredValuesFallBackSilently() = withStore { s, ds ->
        ds.edit { it[stringPreferencesKey("style_art")] = "HOLOGRAM"; it[stringPreferencesKey("style_override_g")] = "nope" }
        val p = s.prefs.first()
        assertEquals(StyleLimits.DEFAULT_ART, p.art); assertTrue(p.overrides.isEmpty())
    }
}
