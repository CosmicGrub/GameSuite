package com.gamesuite.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * PER-PROFILE DATA SCOPING (multiple profiles + kids mode).
 *
 * Mechanism: a process-wide "active profile id" ([activeId]) and a store-name SUFFIX derived from it.
 *   - The DEFAULT profile (id [DEFAULT_PROFILE_ID], the one existing installs migrate into) maps to the
 *     EXISTING store names with NO suffix, so all pre-profiles data stays exactly where it already is and
 *     no file is copied or moved. Any other profile "k3f9a" uses "<name>.k3f9a".
 *   - Preferences DataStores: [gameDataStore] (core/GameDataStore.kt) now returns a [ProfileScopedDataStore]
 *     wrapper for every store except the device-level ones in [deviceLevelStores]. The wrapper re-targets
 *     `data` (flatMapLatest on [activeId]) and `updateData` to the active profile's file, so existing
 *     repositories (stats, club profile, per-game stats/prefs, ...) become profile-aware with NO code change
 *     and their Flows re-emit when the profile switches.
 *   - SharedPreferences stores that opt in (FavoritesStore, PrefsKeyValues for Hub choices) call
 *     [prefsName] on every access so the file name follows the active profile.
 *   - Saved games: [savedGamesDir] gives the per-profile directory; MainActivity re-points
 *     `sessionManager.savedGames` when the profile changes (see ProfileShellObserver).
 *
 * Scoped (per profile): game_stats (stats + progress + streaks + achievements), club_profile (name, avatar,
 *   favourite game, coach-seen), every per-game *_stats / *_prefs DataStore created through gameDataStore,
 *   party_toolkit, favorites, game_hub (remembered Hub choices), saved_games, player_names (lobby host name and
 *   pass-and-play seat names: a kid Club name mirrored there must not become the adult host name).
 * NOT scoped (device level, intentionally or not yet): app_settings (theme, sound, relay URL), profiles,
 *   profile_controls, PartyRoster (party_roster),
 *   party_score_keeper, GameTips (tip/coach flags), gomoku_hex_coach, gs_coach_cards, shogi_prefs,
 *   Yahtzee prefs, the music library settings. Each is a one-line change (use [prefsName]) if wanted later.
 *
 * Known small race: a write that is already in flight while the profile switches lands in whichever profile
 * was active when updateData was called. The shell switches only from the picker, never mid-write.
 */
object ProfileScope {
    const val DEFAULT_PROFILE_ID = "default"

    /** DataStore names that must stay shared by every profile (settings, the profile list itself, parent controls). */
    val deviceLevelStores: Set<String> = setOf("app_settings", "profiles", "profile_controls")

    private val _activeId = MutableStateFlow(DEFAULT_PROFILE_ID)

    /** The active profile id. Collected by [ProfileScopedDataStore]; set once at startup and on every switch. */
    val activeId: StateFlow<String> = _activeId

    fun setActive(id: String) {
        _activeId.value = if (isSafeId(id)) id else DEFAULT_PROFILE_ID
    }

    fun current(): String = _activeId.value

    /** Ids become part of file names, so only a conservative alphabet is accepted. */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 24 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** "" for the default profile, ".<id>" otherwise. */
    fun suffixOf(id: String): String = if (id == DEFAULT_PROFILE_ID || !isSafeId(id)) "" else ".$id"

    fun scopedName(baseName: String, id: String): String = baseName + suffixOf(id)

    /** A SharedPreferences / file name for [baseName] under the CURRENT profile. */
    fun prefsName(baseName: String): String = scopedName(baseName, current())

    /** The saved-games directory for [id] under [filesDir] (default profile keeps the pre-existing "saved_games"). */
    fun savedGamesDir(filesDir: File, id: String = current()): File =
        File(filesDir, scopedName("saved_games", id))

    /**
     * Removes every file a deleted profile owned: its DataStore files, SharedPreferences files and saved
     * games. Never touches the default profile (its data is the unsuffixed legacy data). Best effort.
     */
    fun deleteProfileData(context: Context, id: String) {
        if (id == DEFAULT_PROFILE_ID || !isSafeId(id)) return
        val suffix = suffixOf(id)
        val files = context.applicationContext.filesDir
        File(files, "datastore").listFiles()?.forEach { f ->
            if (f.name.endsWith("$suffix.preferences_pb") || f.name.contains("$suffix.preferences_pb")) f.delete()
        }
        File(files.parentFile, "shared_prefs").listFiles()?.forEach { f ->
            if (f.name.endsWith("$suffix.xml")) f.delete()
        }
        savedGamesDir(files, id).deleteRecursively()
    }
}
