package com.gamesuite.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureHttpTest {
    private fun failure(block: () -> Unit): UpdateFailure {
        try { block() } catch (e: UpdateException) { return e.failure }
        fail("expected UpdateException"); throw IllegalStateException()
    }

    @Test fun policyAllowsOnlyHttpsOnTheFourHosts() {
        val p = UrlPolicy.GITHUB
        assertTrue(p.isAllowed("https://api.github.com/repos/CosmicGrub/GameSuite/releases"))
        assertTrue(p.isAllowed("https://github.com/x"))
        assertTrue(p.isAllowed("https://objects.githubusercontent.com/a"))
        assertTrue(p.isAllowed("https://release-assets.githubusercontent.com/a"))
        assertFalse(p.isAllowed("http://api.github.com/x"))
        assertFalse(p.isAllowed("https://evil.com/x"))
        assertFalse(p.isAllowed("https://api.github.com.evil.com/x"))
        assertFalse(p.isAllowed("https://evil.com@api.github.com/x"))
        assertFalse(p.isAllowed("https://api.github.com:8443/x"))
        assertFalse(p.isAllowed("ftp://github.com/x"))
        assertFalse(p.isAllowed("//github.com/x"))
        assertFalse(p.isAllowed("not a url"))
        assertFalse(p.isAllowed("https://raw.githubusercontent.com/x"))
    }

    @Test fun trustedAssetUrlMustPointIntoThisRepo() {
        val p = UrlPolicy.GITHUB
        assertTrue(p.isTrustedAssetUrl("https://github.com/CosmicGrub/GameSuite/releases/download/t/a.apk"))
        assertTrue(p.isTrustedAssetUrl("https://api.github.com/repos/CosmicGrub/GameSuite/releases/assets/5"))
        assertFalse(p.isTrustedAssetUrl("https://github.com/Other/GameSuite/releases/download/t/a.apk"))
        assertFalse(p.isTrustedAssetUrl("https://github.com/CosmicGrub/GameSuite/archive/main.zip"))
        assertFalse(p.isTrustedAssetUrl("https://objects.githubusercontent.com/anything"))
    }

    @Test fun redirectsToTheAllowedCdnAreFollowed() {
        val c = FakeConnector()
        val start = Fixtures.apkUrl(5)
        c.redirect(start, "https://release-assets.githubusercontent.com/blob?x=1")
        c.text("https://release-assets.githubusercontent.com/blob?x=1", "payload")
        val r = SecureHttp(c).getText(start)
        assertEquals("payload", r.body)
        assertEquals(2, c.requested.size)
    }

    @Test fun relativeRedirectStaysOnHost() {
        val c = FakeConnector()
        c.redirect("https://api.github.com/a", "/b", 301)
        c.text("https://api.github.com/b", "ok")
        assertEquals("ok", SecureHttp(c).getText("https://api.github.com/a").body)
    }

    @Test fun redirectToHttpIsRejected() {
        val c = FakeConnector()
        c.redirect("https://github.com/a", "http://github.com/b")
        assertTrue(failure { SecureHttp(c).getText("https://github.com/a") } is UpdateFailure.BlockedUrl)
    }

    @Test fun redirectToForeignHostIsRejectedBeforeAnyRequestToIt() {
        val c = FakeConnector()
        c.redirect("https://github.com/a", "https://evil.example/steal")
        assertTrue(failure { SecureHttp(c).getText("https://github.com/a") } is UpdateFailure.BlockedUrl)
        assertEquals(listOf("https://github.com/a"), c.requested)
    }

    @Test fun redirectLoopIsCapped() {
        val c = FakeConnector()
        c.redirect("https://github.com/a", "https://github.com/b")
        c.redirect("https://github.com/b", "https://github.com/a")
        assertEquals(UpdateFailure.TooManyRedirects, failure { SecureHttp(c).getText("https://github.com/a") })
        assertEquals(UrlPolicy.MAX_REDIRECTS + 1, c.requested.size)
    }

    @Test fun redirectWithoutLocationIsBadResponse() {
        val c = FakeConnector()
        c.route("https://github.com/a") { RawResponse(302, emptyMap(), null) {} }
        assertEquals(UpdateFailure.BadResponse, failure { SecureHttp(c).getText("https://github.com/a") })
    }

    @Test fun firstHopIsPolicyChecked() {
        val c = FakeConnector()
        assertTrue(failure { SecureHttp(c).getText("http://github.com/a") } is UpdateFailure.BlockedUrl)
        assertTrue(c.requested.isEmpty())
    }

    @Test fun textBodiesAreSizeCapped() {
        val c = FakeConnector()
        c.text("https://github.com/big", "x".repeat(2000))
        assertEquals(UpdateFailure.TooLarge, failure { SecureHttp(c).getText("https://github.com/big", maxBytes = 1000) })
    }

    @Test fun ioFailureMapsToOfflineAndNoAuthHeaderIsEverSent() {
        val c = FakeConnector()
        c.offline = true
        assertEquals(UpdateFailure.Offline, failure { SecureHttp(c).getText("https://github.com/a") })
        c.offline = false
        c.text("https://github.com/a", "ok")
        SecureHttp(c).getText("https://github.com/a")
        val h = c.requestHeaders.last()
        assertFalse(h.keys.any { it.equals("authorization", true) || it.equals("cookie", true) })
        assertEquals("GameSuite-Updater", h["User-Agent"])
    }
}
