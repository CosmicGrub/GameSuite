package com.gamesuite.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gamesuite.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The whole updater exists only when this is true (debug + sideload builds; the Play release has it false). */
object UpdateFeature {
    val enabled: Boolean get() = BuildConfig.SIDELOAD_UPDATES
}

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore("update_settings")

/** Device-level (not per-profile) persistence of [UpdateSettings]. */
class DataStoreUpdateSettingsStore(private val context: Context) : UpdateSettingsStore {
    private object K {
        val AUTO = booleanPreferencesKey("auto_check")
        val CHANNEL = stringPreferencesKey("channel")
        val LAST = longPreferencesKey("last_checked_ms")
        val DISMISSED = longPreferencesKey("dismissed_version_code")
        val REINSTALL = booleanPreferencesKey("allow_reinstall")
        val RATE = longPreferencesKey("rate_limited_until_ms")
        val GUIDE = booleanPreferencesKey("permission_guide_seen")
    }

    override val settings: Flow<UpdateSettings> = context.updateDataStore.data.map { p ->
        UpdateSettings(
            autoCheck = p[K.AUTO] ?: false,
            channel = p[K.CHANNEL]?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() } ?: UpdateChannel.PREVIEW,
            lastCheckedMs = p[K.LAST] ?: 0L,
            dismissedVersionCode = p[K.DISMISSED] ?: 0L,
            allowReinstall = p[K.REINSTALL] ?: false,
            rateLimitedUntilMs = p[K.RATE] ?: 0L,
            permissionGuideSeen = p[K.GUIDE] ?: false
        )
    }

    override suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
        context.updateDataStore.edit { p ->
            val cur = UpdateSettings(
                autoCheck = p[K.AUTO] ?: false,
                channel = p[K.CHANNEL]?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() } ?: UpdateChannel.PREVIEW,
                lastCheckedMs = p[K.LAST] ?: 0L,
                dismissedVersionCode = p[K.DISMISSED] ?: 0L,
                allowReinstall = p[K.REINSTALL] ?: false,
                rateLimitedUntilMs = p[K.RATE] ?: 0L,
                permissionGuideSeen = p[K.GUIDE] ?: false
            )
            val n = transform(cur)
            p[K.AUTO] = n.autoCheck; p[K.CHANNEL] = n.channel.name; p[K.LAST] = n.lastCheckedMs
            p[K.DISMISSED] = n.dismissedVersionCode; p[K.REINSTALL] = n.allowReinstall
            p[K.RATE] = n.rateLimitedUntilMs; p[K.GUIDE] = n.permissionGuideSeen
        }
    }
}

/** Reads package name / versionCode / signing-certificate digests through PackageManager. */
class AndroidApkInspector(private val context: Context) : ApkInspector {
    override fun installed(): ApkFacts? = try {
        facts(context.packageManager.getPackageInfo(context.packageName, flags()))
    } catch (e: Exception) { null }

    override fun archive(path: String): ApkFacts? = try {
        facts(context.packageManager.getPackageArchiveInfo(path, flags()))
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo?): ApkFacts? {
        info ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val digests = sigs.orEmpty().map { Checksums.sha256Hex(it.toByteArray()) }.toSet()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return ApkFacts(info.packageName, code, digests)
    }
}

/** FileProvider + system package installer hand-off. The owner taps the installer's own confirmation. */
class AndroidInstallLauncher(private val context: Context) : InstallLauncher {
    override fun canInstallUnknownApps(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openUnknownAppsSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { /* no settings screen: the UI text still explains the manual path */ }
    }

    override fun launchInstaller(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) { false }
}

/** App-wide holder so the Settings section and the hub banner share one controller. */
object UpdateServices {
    private var controller: UpdateController? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Synchronized
    fun get(context: Context): UpdateController {
        controller?.let { return it }
        val app = context.applicationContext
        val inspector = AndroidApkInspector(app)
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val installed = InstalledVersion(code, info.versionName.orEmpty())
        val updatesDir = File(app.cacheDir, "updates")
        val http = SecureHttp()
        val c = UpdateController(
            installed = installed,
            client = ReleasesClient(http, FileEtagCache(File(updatesDir, "etag"))),
            downloader = ApkDownloader(http, updatesDir, freeSpace = { StatFs(app.cacheDir.path).availableBytes }),
            inspector = inspector,
            installer = AndroidInstallLauncher(app),
            store = DataStoreUpdateSettingsStore(app)
        )
        controller = c
        return c
    }
}
