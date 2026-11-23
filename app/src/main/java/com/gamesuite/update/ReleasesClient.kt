package com.gamesuite.update

import java.io.File
import java.security.MessageDigest

data class CachedPage(val etag: String, val nextLink: String?, val body: String)

interface EtagCache {
    fun get(url: String): CachedPage?
    fun put(url: String, page: CachedPage)
}

class MemoryEtagCache : EtagCache {
    private val map = HashMap<String, CachedPage>()
    override fun get(url: String) = map[url]
    override fun put(url: String, page: CachedPage) { map[url] = page }
}

/** Persists pages under cacheDir so a 304 still yields the full list after a restart. Corrupt files read as a miss. */
class FileEtagCache(private val dir: File) : EtagCache {
    private fun file(url: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return File(dir, "releases-$h.cache")
    }

    override fun get(url: String): CachedPage? = try {
        val f = file(url)
        if (!f.isFile) null else {
            val lines = f.readText().split("\n", limit = 3)
            if (lines.size < 3) null else CachedPage(lines[0], lines[1].ifEmpty { null }, lines[2])
        }
    } catch (e: Exception) { null }

    override fun put(url: String, page: CachedPage) {
        try {
            dir.mkdirs()
            file(url).writeText(page.etag.replace("\n", "") + "\n" + (page.nextLink ?: "") + "\n" + page.body)
        } catch (_: Exception) { /* cache is best-effort */ }
    }
}

object LinkHeader {
    private val next = Regex("""<([^>]+)>\s*;\s*rel="?next"?""")
    fun next(header: String?): String? = header?.let { next.find(it)?.groupValues?.get(1) }
}

data class FetchResult(val releases: List<ReleaseInfo>, val fromCacheOnly: Boolean)

/**
 * Lists EVERY release (all pages, both channels): never `/releases/latest`. Unauthenticated; ETag revalidation per page
 * so unchanged pages cost no rate limit; a 304 reuses the cached body.
 */
class ReleasesClient(
    private val http: SecureHttp,
    private val cache: EtagCache,
    private val baseUrl: String = UrlPolicy.RELEASES_URL,
    private val perPage: Int = 100,
    private val maxPages: Int = 5
) {
    fun fetchAll(): FetchResult {
        val all = ArrayList<ReleaseInfo>()
        var url: String? = "$baseUrl?per_page=$perPage"
        var pages = 0
        var anyNetworkBody = false
        while (pages < maxPages) {
            val cur: String = url ?: break
            pages++
            val cached = cache.get(cur)
            val extra = if (cached != null) mapOf("If-None-Match" to cached.etag) else emptyMap()
            val r = http.getText(cur, extra)
            val body: String
            val next: String?
            when {
                r.code == 304 && cached != null -> { body = cached.body; next = cached.nextLink }
                r.code == 200 -> {
                    body = r.body
                    next = LinkHeader.next(r.headers["link"])
                    anyNetworkBody = true
                    r.headers["etag"]?.let { cache.put(cur, CachedPage(it, next, body)) }
                }
                r.code == 403 || r.code == 429 -> throw rateLimitOrHttp(r)
                else -> throw UpdateException(UpdateFailure.HttpError(r.code))
            }
            all += ReleaseParser.parseList(body)
            url = next?.let { UrlPolicy.GITHUB.require(it) }
        }
        return FetchResult(all, !anyNetworkBody)
    }

    /** Whatever the cache holds, no network (for the badge on a cold start inside the throttle window). */
    fun cachedOnly(): List<ReleaseInfo>? {
        val all = ArrayList<ReleaseInfo>()
        var url: String? = "$baseUrl?per_page=$perPage"
        var pages = 0
        while (url != null && pages < maxPages) {
            pages++
            val c = cache.get(url) ?: return if (all.isEmpty()) null else all
            all += try { ReleaseParser.parseList(c.body) } catch (e: UpdateException) { return null }
            url = c.nextLink
        }
        return all
    }

    private fun rateLimitOrHttp(r: TextResponse): UpdateException {
        val remaining = r.headers["x-ratelimit-remaining"]
        val retryAfter = r.headers["retry-after"]?.toLongOrNull()
        return if (remaining == "0" || retryAfter != null || r.code == 429) {
            val reset = r.headers["x-ratelimit-reset"]?.toLongOrNull()
                ?: retryAfter?.let { System.currentTimeMillis() / 1000 + it }
            UpdateException(UpdateFailure.RateLimited(reset))
        } else UpdateException(UpdateFailure.HttpError(r.code))
    }
}
