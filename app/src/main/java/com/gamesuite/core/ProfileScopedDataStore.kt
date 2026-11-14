package com.gamesuite.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * A [DataStore] that always talks to the file of the ACTIVE profile ([ProfileScope]). One real DataStore is
 * created lazily per profile suffix (guarded by a lock: two DataStores on one file would throw), and the
 * default profile's file is `<baseName>.preferences_pb`, identical to what plain `preferencesDataStore`
 * used before profiles existed, so existing data is picked up untouched.
 */
internal class ProfileScopedDataStore(
    private val appContext: Context,
    private val baseName: String
) : DataStore<Preferences> {
    private val lock = Any()
    private val stores = HashMap<String, DataStore<Preferences>>()

    private fun storeFor(profileId: String): DataStore<Preferences> = synchronized(lock) {
        stores.getOrPut(ProfileScope.suffixOf(profileId)) {
            PreferenceDataStoreFactory.create(
                corruptionHandler = gameDataStoreCorruptionHandler(),
                produceFile = { appContext.preferencesDataStoreFile(ProfileScope.scopedName(baseName, profileId)) }
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<Preferences> = ProfileScope.activeId.flatMapLatest { storeFor(it).data }

    /** Read-only view of another profile's file (the parent dashboard's weekly report). Same instance the active use gets. */
    fun dataFor(profileId: String): Flow<Preferences> =
        if (ProfileScope.isSafeId(profileId)) storeFor(profileId).data else kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        storeFor(ProfileScope.current()).updateData(transform)
}

/** The property delegate behind `private val Context.xStore by gameDataStore("x")` for a profile-scoped store. */
internal class ProfileScopedDelegate(private val baseName: String) :
    ReadOnlyProperty<Context, DataStore<Preferences>> {
    private val lock = Any()
    @Volatile private var instance: DataStore<Preferences>? = null

    override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> =
        instance ?: synchronized(lock) {
            instance ?: ProfileScopedDataStore(thisRef.applicationContext, baseName).also { instance = it }
        }
}
