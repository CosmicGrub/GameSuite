package com.gamesuite.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlin.properties.ReadOnlyProperty

/**
 * The single place every Preferences DataStore in the app is created, so they all share the
 * same corruption policy. If the underlying `.preferences_pb` file cannot be parsed, DataStore
 * would otherwise throw `CorruptionException` from the first collecting flow (a crash loop at
 * launch). With the handler it replaces the unreadable file with empty preferences instead, so
 * the app starts with defaults. Healthy files behave exactly as with plain `preferencesDataStore`.
 *
 * Usage: `private val Context.fooStore: DataStore<Preferences> by gameDataStore("foo")`.
 */
fun gameDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    preferencesDataStore(
        name = name,
        corruptionHandler = gameDataStoreCorruptionHandler()
    )

/** Exposed separately so it can be unit-tested and reused by non-delegate factories. */
fun gameDataStoreCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }
