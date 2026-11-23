package com.gamesuite

import android.app.Application
import com.gamesuite.profiles.ProfileStore

/**
 * Starts the profile bootstrap (migration, active-profile scope, parent controls) on a background thread as soon as the
 * process exists, so [MainActivity.onCreate] normally finds the result already there instead of waiting on DataStore
 * reads on the main thread (audit P12). If the Activity gets there first it waits for the same run, still bounded.
 */
class GameSuiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProfileStore.prewarm(this)
    }
}
