package com.gamesuite.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class RawResponse(
    val code: Int,
    /** Header names lower-cased; first value wins. */
    val headers: Map<String, String>,
    val body: InputStream?,
    val close: () -> Unit
)

/** One HTTP round trip with NO automatic redirects. Swappable so redirect/size rules are testable without a network. */
fun interface Connector {
    fun open(url: String, headers: Map<String, String>): RawResponse
}

/** Production connector: plain HttpURLConnection, system trust store, strict timeouts, never follows redirects itself. */
class UrlConnectionConnector(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : Connector {
    override fun open(url: String, headers: Map<String, String>): RawResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val h = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && !v.isNullOrEmpty()) h.putIfAbsent(k.lowercase(), v.first())
        val stream = try { if (code >= 400) c.errorStream else c.inputStream } catch (e: IOException) { null }
        return RawResponse(code, h, stream) { try { stream?.close() } catch (_: IOException) {}; c.disconnect() }
    }
}

class TextResponse(val code: Int, val headers: Map<String, String>, val body: String)

/**
 * The only door the updater uses to reach the network. Every URL (first hop and every redirect) must pass [policy];
 * redirects are capped; bodies are size-capped; I/O failures map to [UpdateFailure.Offline].
 * No Authorization header is ever sent and no caller-supplied identity is added beyond a generic User-Agent.
 */
class SecureHttp(
    private val connector: Connector = UrlConnectionConnector(),
    private val policy: UrlPolicy = UrlPolicy.GITHUB
) {
    /** Follows redirects under the policy; caller must close the result. */
    fun open(url: String, extra: Map<String, String> = emptyMap()): RawResponse {
        var current = policy.require(url)
        val headers = BASE_HEADERS + extra
        var hops = 0
        while (true) {
            val r = try { connector.open(current, headers) } catch (e: UpdateException) { throw e } catch (e: IOException) {
                throw UpdateException(UpdateFailure.Offline, e.javaClass.simpleName)
            }
            if (r.code in REDIRECT_CODES) {
                r.close()
                if (++hops > UrlPolicy.MAX_REDIRECTS) throw UpdateException(UpdateFailure.TooManyRedirects)
                current = policy.resolveRedirect(current, r.headers["location"])
                continue
            }
            return r
        }
    }

    fun getText(url: String, extra: Map<String, String> = emptyMap(), maxBytes: Int = MAX_TEXT_BYTES): TextResponse {
        val r = open(url, extra)
        try {
            val body = r.body?.let { readCapped(it, maxBytes) } ?: ""
            return TextResponse(r.code, r.headers, body)
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.Offline, e.javaClass.simpleName)
        } finally {
            r.close()
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw UpdateException(UpdateFailure.TooLarge)
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        /** Generic client identity, no device or user data. */
        val BASE_HEADERS = mapOf(
            "User-Agent" to "GameSuite-Updater",
            "Accept" to "application/vnd.github+json, application/octet-stream, text/plain, */*",
            "X-GitHub-Api-Version" to "2022-11-28"
        )
    }
}
