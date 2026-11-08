package com.gamesuite.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.clubProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "club_profile")

/**
 * DataStore-backed [ClubProfile]. Reads are lenient (a corrupt value falls back to the default part).
 * The Club name is a display string only; [setDisplayName] can also mirror it into [PlayerNameStore]
 * so the Nearby/Online lobby uses the same name. Never read or write from a @Composable body: collect
 * [profile] as state and call the setters from event handlers.
 */
class ClubProfileStore(private val context: Context) {
    private object Keys {
        val NAME = stringPreferencesKey("display_name")
        val AVATAR = stringPreferencesKey("avatar")
        val FAVORITE = stringPreferencesKey("favorite_game")
        val COACH_SEEN = stringSetPreferencesKey("coach_seen")
    }

    val profile: Flow<ClubProfile> = context.clubProfileDataStore.data.map { decode(it) }

    suspend fun setDisplayName(raw: String, playerNames: PlayerNameStore? = null) {
        val clean = cleanPlayerName(raw, "")
        context.clubProfileDataStore.edit { it[Keys.NAME] = clean }
        if (clean.isNotEmpty()) playerNames?.hostName = clean
    }

    suspend fun setAvatar(avatar: Avatar) {
        context.clubProfileDataStore.edit { it[Keys.AVATAR] = avatar.encode() }
    }

    /** [known] = ids of real games (GameHubSpecs.all.map { it.gameId }.toSet()). */
    suspend fun setFavorite(gameId: String?, known: Set<String>) {
        context.clubProfileDataStore.edit { prefs ->
            val ok = gameId?.takeIf { it in known }
            if (ok == null) prefs.remove(Keys.FAVORITE) else prefs[Keys.FAVORITE] = ok
        }
    }

    suspend fun markCoachSeen(gameId: String) {
        context.clubProfileDataStore.edit { prefs ->
            prefs[Keys.COACH_SEEN] = (prefs[Keys.COACH_SEEN] ?: emptySet()) + gameId
        }
    }

    /** Replace everything at once (the AvatarPicker's Save). */
    suspend fun save(profile: ClubProfile, known: Set<String>, playerNames: PlayerNameStore? = null) {
        // One atomic edit: three separate ones left a half-saved profile if the process died between them.
        val clean = cleanPlayerName(profile.displayName, "")
        val favorite = profile.favoriteGameId?.takeIf { it in known }
        context.clubProfileDataStore.edit { prefs ->
            prefs[Keys.NAME] = clean
            prefs[Keys.AVATAR] = profile.avatar.encode()
            if (favorite == null) prefs.remove(Keys.FAVORITE) else prefs[Keys.FAVORITE] = favorite
        }
        if (clean.isNotEmpty()) playerNames?.hostName = clean
    }

    internal companion object {
        fun decode(prefs: Preferences): ClubProfile = ClubProfile(
            displayName = cleanPlayerName(prefs[Keys.NAME] ?: "", ""),
            avatar = Avatar.decode(prefs[Keys.AVATAR]),
            favoriteGameId = prefs[Keys.FAVORITE],
            coachSeen = prefs[Keys.COACH_SEEN] ?: emptySet()
        )
    }
}
