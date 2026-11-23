package com.gamesuite.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleasesClientTest {
    private val page1 = "https://api.github.com/repos/CosmicGrub/GameSuite/releases?per_page=100"
    private val page2 = "https://api.github.com/repos/CosmicGrub/GameSuite/releases?per_page=100&page=2"

    @Test fun followsLinkHeaderAcrossPagesAndNeverUsesLatest() {
        val c = FakeConnector()
        c.text(page1, Fixtures.list(Fixtures.releaseJson(12), Fixtures.releaseJson(11)),
            headers = mapOf("etag" to "\"e1\"", "link" to "<$page2>; rel=\"next\", <$page2>; rel=\"last\""))
        c.text(page2, Fixtures.list(Fixtures.releaseJson(10)), headers = mapOf("etag" to "\"e2\""))
        val r = ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll()
        assertEquals(listOf("wave3-12-abc1234", "wave3-11-abc1234", "wave3-10-abc1234"), r.releases.map { it.tag })
        assertTrue(c.requested.none { it.contains("/latest") })
        assertEquals(listOf(page1, page2), c.requested)
    }

    @Test fun nextLinkOffHostIsRefused() {
        val c = FakeConnector()
        c.text(page1, "[]", headers = mapOf("link" to "<https://evil.example/x>; rel=\"next\""))
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertTrue(e.failure is UpdateFailure.BlockedUrl)
        }
    }

    @Test fun pageCountIsCapped() {
        val c = FakeConnector()
        val loop = "<$page1>; rel=\"next\""
        c.text(page1, "[]", headers = mapOf("link" to loop))
        ReleasesClient(SecureHttp(c), MemoryEtagCache(), maxPages = 3).fetchAll()
        assertEquals(3, c.requested.size)
    }

    @Test fun etagRevalidationReusesCachedBodyOn304() {
        val c = FakeConnector()
        val cache = MemoryEtagCache()
        c.text(page1, Fixtures.list(Fixtures.releaseJson(12)), headers = mapOf("etag" to "\"e1\""))
        val client = ReleasesClient(SecureHttp(c), cache)
        assertEquals(1, client.fetchAll().releases.size)
        c.route(page1) { h -> if (h["If-None-Match"] == "\"e1\"") RawResponse(304, emptyMap(), null) {} else RawResponse(200, emptyMap(), null) {} }
        val second = client.fetchAll()
        assertEquals(1, second.releases.size)
        assertTrue(second.fromCacheOnly)
        assertEquals("\"e1\"", c.requestHeaders.last()["If-None-Match"])
    }

    @Test fun fileCacheSurvivesAndCachedOnlyNeedsNoNetwork() {
        val dir = Fixtures.tempDir()
        val c = FakeConnector()
        c.text(page1, Fixtures.list(Fixtures.releaseJson(12)), headers = mapOf("etag" to "\"e1\"", "link" to "<$page2>; rel=\"next\""))
        c.text(page2, Fixtures.list(Fixtures.releaseJson(11)), headers = mapOf("etag" to "\"e2\""))
        ReleasesClient(SecureHttp(c), FileEtagCache(dir)).fetchAll()
        val offline = FakeConnector().apply { offline = true }
        val cached = ReleasesClient(SecureHttp(offline), FileEtagCache(dir)).cachedOnly()
        assertEquals(2, cached?.size)
        assertTrue(offline.requested.isEmpty())
        assertNull(ReleasesClient(SecureHttp(offline), FileEtagCache(Fixtures.tempDir())).cachedOnly())
    }

    @Test fun rateLimitIsDetectedFromHeaders() {
        val c = FakeConnector()
        c.text(page1, "{}", code = 403, headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1800000000"))
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertEquals(UpdateFailure.RateLimited(1800000000L), e.failure)
        }
        c.text(page1, "{}", code = 403)
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertEquals(UpdateFailure.HttpError(403), e.failure)
        }
        c.text(page1, "{}", code = 429, headers = mapOf("retry-after" to "60"))
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertTrue(e.failure is UpdateFailure.RateLimited)
        }
    }

    @Test fun serverErrorsAndBadBodiesAreFailuresNotCrashes() {
        val c = FakeConnector()
        c.text(page1, "oops", code = 500)
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertEquals(UpdateFailure.HttpError(500), e.failure)
        }
        c.text(page1, "<html>")
        try { ReleasesClient(SecureHttp(c), MemoryEtagCache()).fetchAll(); fail() } catch (e: UpdateException) {
            assertEquals(UpdateFailure.BadResponse, e.failure)
        }
    }

    @Test fun linkHeaderParsing() {
        assertEquals("https://a/b?page=2", LinkHeader.next("<https://a/b?page=2>; rel=\"next\", <https://a/b?page=9>; rel=\"last\""))
        assertNull(LinkHeader.next("<https://a/b?page=1>; rel=\"prev\""))
        assertNull(LinkHeader.next(null))
    }
}
