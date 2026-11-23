package com.gamesuite.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadAndPolicyTest {
    private val data = ByteArray(5000) { (it % 251).toByte() }
    private val sha = Checksums.sha256Hex(data)
    private val url = Fixtures.apkUrl(12)
    private fun asset(size: Long = data.size.toLong()) = ReleaseAsset("GameSuite-sideload-12-abc1234.apk", size, url)

    private fun failure(block: () -> Unit): UpdateFailure {
        try { block() } catch (e: UpdateException) { return e.failure }
        fail("expected failure"); throw IllegalStateException()
    }

    @Test fun checksumFileFormats() {
        val h = "a".repeat(64)
        assertEquals(h, Checksums.parseChecksumFile("$h  GameSuite-sideload-12-abc1234.apk\n", "GameSuite-sideload-12-abc1234.apk"))
        assertEquals(h, Checksums.parseChecksumFile("${h.uppercase()} *GameSuite-sideload-12-abc1234.apk", "GameSuite-sideload-12-abc1234.apk"))
        assertEquals(h, Checksums.parseChecksumFile("$h\n", "x.apk"))
        assertNull(Checksums.parseChecksumFile("$h other.apk\n${"b".repeat(64)} another.apk", "x.apk"))
        assertNull(Checksums.parseChecksumFile("garbage", "x.apk"))
        assertEquals(h, Checksums.fromReleaseBody("Notes\nsha256: $h\n", "x.apk"))
        assertEquals(h, Checksums.fromReleaseBody("$h  x.apk", "x.apk"))
        assertNull(Checksums.fromReleaseBody("a hash $h with no label", "x.apk"))
        assertTrue(Checksums.matches(h, h.uppercase()))
        assertFalse(Checksums.matches(h, "b".repeat(64)))
    }

    @Test fun goodDownloadIsVerifiedAndProgressReported() {
        val c = FakeConnector(); c.bytes(url, data, headers = mapOf("content-length" to "${data.size}"))
        val dir = Fixtures.tempDir()
        var last = 0L
        val f = ApkDownloader(SecureHttp(c), dir).download(asset(), sha, { d, _ -> last = d })
        assertTrue(f.isFile); assertEquals(data.size.toLong(), f.length()); assertEquals(data.size.toLong(), last)
        assertEquals(listOf(f.name), dir.list()!!.toList())
    }

    @Test fun hashMismatchDeletesTheFile() {
        val c = FakeConnector(); c.bytes(url, data)
        val dir = Fixtures.tempDir()
        assertEquals(UpdateFailure.HashMismatch, failure { ApkDownloader(SecureHttp(c), dir).download(asset(), "0".repeat(64)) })
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun invalidExpectedHashIsRefused() {
        val c = FakeConnector(); c.bytes(url, data)
        assertEquals(UpdateFailure.HashMissing, failure { ApkDownloader(SecureHttp(c), Fixtures.tempDir()).download(asset(), "nope") })
        assertTrue(c.requested.isEmpty())
    }

    @Test fun declaredSizeOverCapIsRefusedWithoutDownloading() {
        val c = FakeConnector(); c.bytes(url, data)
        assertEquals(UpdateFailure.TooLarge, failure {
            ApkDownloader(SecureHttp(c), Fixtures.tempDir(), maxBytes = 1000).download(asset(size = 2000), sha)
        })
        assertTrue(c.requested.isEmpty())
    }

    @Test fun contentLengthOverCapIsRefused() {
        val c = FakeConnector(); c.bytes(url, data, headers = mapOf("content-length" to "999999999"))
        assertEquals(UpdateFailure.TooLarge, failure { ApkDownloader(SecureHttp(c), Fixtures.tempDir()).download(asset(), sha) })
    }

    @Test fun lyingServerStreamingPastTheCapIsStoppedAndCleaned() {
        val c = FakeConnector(); c.bytes(url, data) // no content-length, asset size says small
        val dir = Fixtures.tempDir()
        assertEquals(UpdateFailure.TooLarge, failure { ApkDownloader(SecureHttp(c), dir, maxBytes = 1000).download(asset(size = 900), sha) })
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun truncatedDownloadIsRejected() {
        val c = FakeConnector(); c.bytes(url, data.copyOf(100))
        val dir = Fixtures.tempDir()
        assertEquals(UpdateFailure.BadResponse, failure { ApkDownloader(SecureHttp(c), dir).download(asset(), Checksums.sha256Hex(data.copyOf(100))) })
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun cancellationAndLowSpaceAndHttpErrors() {
        val c = FakeConnector(); c.bytes(url, data)
        val dir = Fixtures.tempDir()
        assertEquals(UpdateFailure.Cancelled, failure { ApkDownloader(SecureHttp(c), dir).download(asset(), sha, isCancelled = { true }) })
        assertEquals(0, dir.list()!!.size)
        assertEquals(UpdateFailure.NotEnoughSpace, failure { ApkDownloader(SecureHttp(c), dir, freeSpace = { 10 }).download(asset(), sha) })
        c.bytes(url, data, code = 404)
        assertEquals(UpdateFailure.HttpError(404), failure { ApkDownloader(SecureHttp(c), dir).download(asset(), sha) })
        c.offline = true
        assertEquals(UpdateFailure.Offline, failure { ApkDownloader(SecureHttp(c), dir).download(asset(), sha) })
    }

    @Test fun fileNameIsSanitisedAndStaleFilesRemoved() {
        val c = FakeConnector()
        val evil = ReleaseAsset("../../x/..\\evil.apk", data.size.toLong(), url)
        c.bytes(url, data)
        val dir = Fixtures.tempDir()
        val f = ApkDownloader(SecureHttp(c), dir).download(evil, sha)
        assertEquals(dir.canonicalPath, f.canonicalFile.parentFile.canonicalPath)
        File(dir, "old.apk").writeText("x"); File(dir, "half.part").writeText("x"); File(dir, "keep.txt").writeText("x")
        ApkDownloader(SecureHttp(c), dir).cleanStale()
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
    }

    @Test fun expectedHashComesFromSidecarThenReleaseBodyElseRefused() {
        val c = FakeConnector()
        val rel = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12, body = "sha256: ${"c".repeat(64)}")))[0]
        c.text(Fixtures.apkUrl(12) + ".sha256", "$sha  GameSuite-sideload-12-abc1234.apk\n")
        val dl = ApkDownloader(SecureHttp(c), Fixtures.tempDir())
        assertEquals(sha, dl.expectedSha256(rel, rel.apk!!))
        val noSidecar = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12, withSha = false, body = "sha256: ${"c".repeat(64)}")))[0]
        assertEquals("c".repeat(64), dl.expectedSha256(noSidecar, noSidecar.apk!!))
        val none = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12, withSha = false)))[0]
        assertEquals(UpdateFailure.HashMissing, failure { dl.expectedSha256(none, none.apk!!) })
    }

    private val ours = ApkFacts("com.gamesuite", 10, setOf("aa"))

    @Test fun apkPolicyRequiresPackageSignatureAndNewerVersion() {
        assertNull(ApkPolicy.check(ours, ApkFacts("com.gamesuite", 11, setOf("aa"))))
        assertEquals(UpdateFailure.WrongPackage, ApkPolicy.check(ours, ApkFacts("com.evil", 11, setOf("aa"))))
        assertEquals(UpdateFailure.SignatureMismatch, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 11, setOf("bb"))))
        assertEquals(UpdateFailure.SignatureMismatch, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 11, setOf("aa", "bb"))))
        assertEquals(UpdateFailure.SignatureMismatch, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 11, emptySet())))
        assertEquals(UpdateFailure.NotNewer, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 10, setOf("aa"))))
        assertEquals(UpdateFailure.NotNewer, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 3, setOf("aa"))))
        assertNull(ApkPolicy.check(ours, ApkFacts("com.gamesuite", 3, setOf("aa")), allowNotNewer = true))
        assertEquals(UpdateFailure.ApkUnreadable, ApkPolicy.check(ours, null))
        assertEquals(UpdateFailure.ApkUnreadable, ApkPolicy.check(ours, ApkFacts(null, null, emptySet())))
        // Even with the downgrade opt-in a foreign signature is refused.
        assertEquals(UpdateFailure.SignatureMismatch, ApkPolicy.check(ours, ApkFacts("com.gamesuite", 3, setOf("bb")), allowNotNewer = true))
    }

    @Test fun throttleAllowsAutoChecksAtMostEverySixHours() {
        val now = 100L * 3_600_000
        val on = UpdateSettings(autoCheck = true)
        assertTrue(UpdateThrottle.shouldAutoCheck(now, on))
        assertFalse(UpdateThrottle.shouldAutoCheck(now, UpdateSettings(autoCheck = false)))
        assertFalse(UpdateThrottle.shouldAutoCheck(now, on.copy(lastCheckedMs = now - 5 * 3_600_000)))
        assertTrue(UpdateThrottle.shouldAutoCheck(now, on.copy(lastCheckedMs = now - 6 * 3_600_000)))
        assertFalse(UpdateThrottle.shouldAutoCheck(now, on.copy(rateLimitedUntilMs = now + 1)))
        assertTrue(UpdateThrottle.shouldAutoCheck(now, on.copy(lastCheckedMs = now + 1000))) // clock went back
        assertFalse(UpdateSettings().autoCheck)
        assertEquals(UpdateChannel.PREVIEW, UpdateSettings().channel)
    }
}
