package com.gamesuite.update

import java.net.URI

/**
 * What the updater may talk to: HTTPS only, a fixed host allow-list, default port only, no user-info.
 * Redirects are re-checked hop by hop (see [SecureHttp]).
 */
class UrlPolicy(
    val allowedHosts: Set<String>,
    private val repoOwner: String,
    private val repoName: String,
    private val requireHttps: Boolean = true
) {
    fun isAllowed(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        if (requireHttps && !"https".equals(u.scheme, ignoreCase = true)) return false
        if (u.userInfo != null) return false
        val host = u.host?.lowercase() ?: return false
        if (host !in allowedHosts) return false
        if (u.port != -1 && u.port != 443) return false
        return true
    }

    /** @throws UpdateException(BlockedUrl) */
    fun require(url: String): String {
        if (!isAllowed(url)) throw UpdateException(UpdateFailure.BlockedUrl(url.take(120)))
        return url
    }

    /** Resolves a (possibly relative) Location header against [from] and checks it. */
    fun resolveRedirect(from: String, location: String?): String {
        if (location.isNullOrBlank()) throw UpdateException(UpdateFailure.BadResponse, "redirect without Location")
        val target = try { URI(from).resolve(location).toString() } catch (e: Exception) {
            throw UpdateException(UpdateFailure.BlockedUrl(location.take(120)))
        }
        return require(target)
    }

    /** An asset link must be on the allow-list AND point into this repository's releases. */
    fun isTrustedAssetUrl(url: String): Boolean {
        if (!isAllowed(url)) return false
        val u = URI(url)
        val path = u.path ?: return false
        return when (u.host.lowercase()) {
            "github.com" -> path.startsWith("/$repoOwner/$repoName/releases/download/", ignoreCase = true)
            "api.github.com" -> path.startsWith("/repos/$repoOwner/$repoName/releases/assets/", ignoreCase = true)
            else -> false
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val OWNER = "CosmicGrub"
        const val REPO = "GameSuite"
        const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases"

        val GITHUB = UrlPolicy(
            setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"),
            OWNER, REPO
        )
    }
}
