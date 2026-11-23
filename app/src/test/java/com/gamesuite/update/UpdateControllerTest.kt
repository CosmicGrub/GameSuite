package com.gamesuite.update

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateControllerTest {
    private val apkBytes = ByteArray(3000) { (it * 7).toByte() }
    private val sha = Checksums.sha256Hex(apkBytes)
    private val installed = InstalledVersion(10, "2.0.3")
    private val api1 = "https://api.github.com/repos/CosmicGrub/GameSuite/releases?per_page=100"

    private class FakeInstaller(var allowed: Boolean = true, var launchOk: Boolean = true) : InstallLauncher {
        val launched = ArrayList<File>(); var settingsOpened = 0
        override fun canInstallUnknownApps() = allowed
        override fun openUnknownAppsSettings() { settingsOpened++ }
        override fun launchInstaller(file: File): Boolean { if (launchOk) launched += file; return launchOk }
    }

    private class FakeInspector(val inst: ApkFacts?, var archive: ApkFacts?) : ApkInspector {
        override fun installed() = inst
        override fun archive(path: String) = archive
    }

    private class Rig(
        val net: FakeConnector, val dir: File, val installer: FakeInstaller, val inspector: FakeInspector,
        val store: InMemoryUpdateSettingsStore, val controller: UpdateController
    )

    private fun rig(
        archive: ApkFacts? = ApkFacts("com.gamesuite", 12, setOf("k")),
        installer: FakeInstaller = FakeInstaller(),
        settings: UpdateSettings = UpdateSettings(),
        now: () -> Long = { 1_000_000_000L }
    ): Rig {
        val net = FakeConnector()
        net.text(api1, Fixtures.list(Fixtures.releaseJson(12, size = apkBytes.size.toLong()), Fixtures.releaseJson(11, size = apkBytes.size.toLong()), Fixtures.releaseJson(10)),
            headers = mapOf("etag" to "\"e\""))
        net.bytes(Fixtures.apkUrl(12), apkBytes)
        net.text(Fixtures.apkUrl(12) + ".sha256", "$sha  GameSuite-sideload-12-abc1234.apk")
        net.bytes(Fixtures.apkUrl(11), apkBytes)
        net.text(Fixtures.apkUrl(11) + ".sha256", "$sha  GameSuite-sideload-11-abc1234.apk")
        val dir = Fixtures.tempDir()
        val http = SecureHttp(net)
        val inspector = FakeInspector(ApkFacts("com.gamesuite", 10, setOf("k")), archive)
        val store = InMemoryUpdateSettingsStore(settings)
        val c = UpdateController(
            installed, ReleasesClient(http, FileEtagCache(File(dir, "etag"))), ApkDownloader(http, dir),
            inspector, installer, store, now, Dispatchers.Unconfined
        )
        return Rig(net, dir, installer, inspector, store, c)
    }

    private fun apks(dir: File) = dir.listFiles { f -> f.isFile }!!.toList()

    @Test fun oneTapUpdateChecksDownloadsVerifiesAndOpensInstaller() = runBlocking {
        val r = rig()
        r.controller.updateNewest()
        val s = r.controller.state.value
        assertEquals(UpdatePhase.HandedOff("wave3-12-abc1234"), s.phase)
        assertEquals(2, s.badgeCount)
        assertEquals(1, r.installer.launched.size)
        assertTrue(r.installer.launched[0].name.contains("12"))
        assertTrue(r.store.current().lastCheckedMs > 0)
    }

    @Test fun ownerCanPickASpecificOlderButStillNewerRelease() = runBlocking {
        val r = rig(archive = ApkFacts("com.gamesuite", 11, setOf("k")))
        r.controller.check()
        r.controller.install("wave3-11-abc1234")
        assertEquals(UpdatePhase.HandedOff("wave3-11-abc1234"), r.controller.state.value.phase)
    }

    @Test fun installedOrOlderReleasesCannotBeInstalledWithoutOptIn() = runBlocking {
        val r = rig()
        r.controller.check()
        r.controller.install("wave3-10-abc1234")
        val p = r.controller.state.value.phase
        assertTrue(p is UpdatePhase.Failed)
        assertTrue(r.installer.launched.isEmpty())
        // After the explicit opt-in, the same-version build is offered and gets as far as the signature/version gate.
        r.controller.setAllowReinstall(true)
        r.controller.install("wave3-10-abc1234")
        assertTrue(r.net.requested.any { it.contains("-10-") })
    }

    @Test fun signatureMismatchIsRefusedExplainedAndTheFileDeleted() = runBlocking {
        val r = rig(archive = ApkFacts("com.gamesuite", 12, setOf("other-key")))
        r.controller.updateNewest()
        assertEquals(UpdatePhase.Failed(UpdateFailure.SignatureMismatch, "wave3-12-abc1234"), r.controller.state.value.phase)
        assertTrue(r.installer.launched.isEmpty())
        assertTrue(apks(r.dir).isEmpty())
    }

    @Test fun wrongPackageAndNotNewerAreRefused() = runBlocking {
        val wrong = rig(archive = ApkFacts("com.evil", 12, setOf("k")))
        wrong.controller.updateNewest()
        assertEquals(UpdateFailure.WrongPackage, (wrong.controller.state.value.phase as UpdatePhase.Failed).failure)
        val old = rig(archive = ApkFacts("com.gamesuite", 10, setOf("k")))
        old.controller.updateNewest()
        assertEquals(UpdateFailure.NotNewer, (old.controller.state.value.phase as UpdatePhase.Failed).failure)
        val garbage = rig(archive = null)
        garbage.controller.updateNewest()
        assertEquals(UpdateFailure.ApkUnreadable, (garbage.controller.state.value.phase as UpdatePhase.Failed).failure)
        assertTrue(apks(garbage.dir).isEmpty())
    }

    @Test fun hashMismatchFromTheReleaseStopsBeforeInstall() = runBlocking {
        val r = rig()
        r.net.text(Fixtures.apkUrl(12) + ".sha256", "${"0".repeat(64)}  GameSuite-sideload-12-abc1234.apk")
        r.controller.updateNewest()
        assertEquals(UpdateFailure.HashMismatch, (r.controller.state.value.phase as UpdatePhase.Failed).failure)
        assertTrue(r.installer.launched.isEmpty()); assertTrue(apks(r.dir).isEmpty())
    }

    @Test fun missingUnknownAppsPermissionPausesThenContinues() = runBlocking {
        val inst = FakeInstaller(allowed = false)
        val r = rig(installer = inst)
        r.controller.updateNewest()
        assertEquals(UpdatePhase.NeedsPermission("wave3-12-abc1234"), r.controller.state.value.phase)
        assertTrue(inst.launched.isEmpty())
        r.controller.continueAfterPermission()           // still not allowed: sends the owner to the settings screen
        assertEquals(1, inst.settingsOpened)
        inst.allowed = true
        r.controller.continueAfterPermission()
        assertEquals(UpdatePhase.HandedOff("wave3-12-abc1234"), r.controller.state.value.phase)
        assertEquals(1, inst.launched.size)
    }

    @Test fun cancelWhileWaitingForPermissionDeletesTheDownload() = runBlocking {
        val r = rig(installer = FakeInstaller(allowed = false))
        r.controller.updateNewest()
        assertTrue(apks(r.dir).isNotEmpty())
        r.controller.cancel()
        assertTrue(apks(r.dir).isEmpty())
        assertEquals(UpdatePhase.Idle, r.controller.state.value.phase)
    }

    @Test fun installerFailureDeletesTheFile() = runBlocking {
        val r = rig(installer = FakeInstaller(launchOk = false))
        r.controller.updateNewest()
        assertEquals(UpdateFailure.InstallerUnavailable, (r.controller.state.value.phase as UpdatePhase.Failed).failure)
        assertTrue(apks(r.dir).isEmpty())
    }

    @Test fun startupRemovesStaleDownloads() = runBlocking {
        val r = rig()
        File(r.dir, "leftover.apk").writeText("x")
        r.controller.start()
        assertTrue(apks(r.dir).isEmpty())
    }

    @Test fun offlineCheckReportsFailureAndKeepsEarlierCatalogFromCache() = runBlocking {
        val r = rig()
        r.controller.check()
        assertEquals(2, r.controller.state.value.badgeCount)
        r.net.offline = true
        assertFalse(r.controller.check())
        assertEquals(UpdateFailure.Offline, r.controller.state.value.checkFailure)
        assertEquals(2, r.controller.state.value.badgeCount)
    }

    @Test fun rateLimitBacksOffUntilTheResetTime() = runBlocking {
        var now = 1_000_000_000L
        val r = rig(now = { now })
        r.net.text(api1, "{}", code = 403, headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now / 1000 + 600}"))
        assertFalse(r.controller.check())
        assertTrue(r.controller.state.value.checkFailure is UpdateFailure.RateLimited)
        val before = r.net.requested.size
        assertFalse(r.controller.check())   // inside the window: no network
        assertEquals(before, r.net.requested.size)
        now += 601_000
        r.net.text(api1, Fixtures.list(Fixtures.releaseJson(12)))
        assertTrue(r.controller.check())
    }

    @Test fun autoCheckHonoursSwitchAndSixHourThrottle() = runBlocking {
        var now = 1_000_000_000L
        val off = rig(now = { now })
        off.controller.autoCheckIfDue()
        assertTrue(off.net.requested.isEmpty())
        val on = rig(settings = UpdateSettings(autoCheck = true), now = { now })
        on.controller.autoCheckIfDue()
        val n = on.net.requested.size
        assertTrue(n > 0)
        now += 3_600_000
        on.controller.autoCheckIfDue()
        assertEquals(n, on.net.requested.size)
        now += 6 * 3_600_000
        on.controller.autoCheckIfDue()
        assertTrue(on.net.requested.size > n)
    }

    @Test fun channelChangeRebuildsBadgeAndBannerDismissalSticks() = runBlocking {
        val r = rig()
        r.controller.check()
        assertEquals(2, r.controller.state.value.badgeCount)
        assertTrue(r.controller.state.value.showBanner)
        r.controller.setChannel(UpdateChannel.STABLE)
        assertEquals(0, r.controller.state.value.badgeCount)
        assertNull(r.controller.state.value.catalog.newest)
        r.controller.setChannel(UpdateChannel.PREVIEW)
        r.controller.dismissBanner()
        assertFalse(r.controller.state.value.showBanner)
        assertEquals(12L, r.store.current().dismissedVersionCode)
        assertNotNull(r.controller.state.value.catalog.newest) // still listed, only the banner is dismissed
    }
}
