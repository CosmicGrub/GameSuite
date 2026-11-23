package com.gamesuite.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** In-memory network: url -> canned response. Records every requested URL and request headers. */
class FakeConnector : Connector {
    private val routes = HashMap<String, (Map<String, String>) -> RawResponse>()
    val requested = ArrayList<String>()
    val requestHeaders = ArrayList<Map<String, String>>()
    var offline = false

    fun route(url: String, f: (Map<String, String>) -> RawResponse) { routes[url] = f }

    fun bytes(url: String, data: ByteArray, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        route(url) { RawResponse(code, headers, ByteArrayInputStream(data)) {} }

    fun text(url: String, body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        bytes(url, body.toByteArray(), code, headers)

    fun redirect(url: String, to: String, code: Int = 302) =
        route(url) { RawResponse(code, mapOf("location" to to), null) {} }

    override fun open(url: String, headers: Map<String, String>): RawResponse {
        requested += url; requestHeaders += headers
        if (offline) throw IOException("offline")
        return (routes[url] ?: { RawResponse(404, emptyMap(), null) {} })(headers)
    }
}

object Fixtures {
    const val BASE = "https://github.com/CosmicGrub/GameSuite/releases/download"

    fun apkUrl(code: Int, sha: String = "abc1234") = "$BASE/wave3-$code-$sha/GameSuite-sideload-$code-$sha.apk"

    fun releaseJson(code: Int, prerelease: Boolean = true, size: Long = 10, sha: String = "abc1234", body: String = "notes $code", withSha: Boolean = true): String {
        val apk = """{"name":"GameSuite-sideload-$code-$sha.apk","size":$size,"browser_download_url":"${apkUrl(code, sha)}","content_type":"application/vnd.android.package-archive"}"""
        val shaAsset = """,{"name":"GameSuite-sideload-$code-$sha.apk.sha256","size":90,"browser_download_url":"${apkUrl(code, sha)}.sha256"}"""
        return """{"tag_name":"wave3-$code-$sha","name":"Build $code","body":"$body","prerelease":$prerelease,"draft":false,
            "published_at":"2026-11-2${code % 10}T10:00:00Z","html_url":"https://github.com/CosmicGrub/GameSuite/releases/tag/wave3-$code-$sha",
            "unknown_field":{"x":1},"assets":[$apk${if (withSha) shaAsset else ""}]}"""
    }

    fun list(vararg items: String) = "[" + items.joinToString(",") + "]"

    fun tempDir(): File = java.nio.file.Files.createTempDirectory("gs-upd").toFile().also { it.deleteOnExit() }
}
