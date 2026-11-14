package com.gamesuite.ui.profiles

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import com.gamesuite.profiles.DeviceCredentialFallback
import kotlinx.coroutines.CompletableDeferred

/**
 * The device's own screen lock (PIN / pattern / password / fingerprint) through the system confirm-credential
 * screen. No new dependency: [KeyguardManager.createConfirmDeviceCredentialIntent] is launched by the screen's
 * activity-result launcher ([launcher]) and the result is handed back with [onResult].
 */
class AndroidDeviceCredential(context: Context) : DeviceCredentialFallback {
    private val keyguard: KeyguardManager? = context.applicationContext.getSystemService(KeyguardManager::class.java)

    /** Set by the screen: starts the confirm-credential activity for result. */
    var launcher: ((Intent) -> Unit)? = null

    private var pending: CompletableDeferred<Boolean>? = null

    @Suppress("DEPRECATION") // still the supported no-dependency route on minSdk 26
    private fun intent(): Intent? =
        if (keyguard?.isDeviceSecure == true) keyguard.createConfirmDeviceCredentialIntent(null, null) else null

    /** True only when the device has a screen lock and the system hands out the confirm intent. */
    override fun isAvailable(): Boolean = intent() != null

    /** Shows the system prompt; true only when the adult confirmed. A cancelled, failed or unavailable prompt is false. */
    override suspend fun authenticate(): Boolean {
        val launch = launcher ?: return false
        val i = intent() ?: return false
        pending?.complete(false) // a stale prompt never counts
        val d = CompletableDeferred<Boolean>()
        pending = d
        return try {
            launch(i)
            d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            if (pending === d) pending = null
        }
    }

    /** Called by the activity-result launcher. */
    fun onResult(ok: Boolean) {
        pending?.complete(ok)
    }
}
