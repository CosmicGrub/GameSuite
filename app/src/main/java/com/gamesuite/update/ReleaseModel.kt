package com.gamesuite.update

/** Which stream of builds the owner follows. STABLE = non-prerelease only; PREVIEW = everything (prereleases from wave-3 plus stable). */
enum class UpdateChannel { STABLE, PREVIEW }

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val contentType: String = ""
)

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: String?,
    val htmlUrl: String?,
    val assets: List<ReleaseAsset>
) {
    val channel: UpdateChannel get() = if (prerelease) UpdateChannel.PREVIEW else UpdateChannel.STABLE

    /** The installable APK: first `.apk` asset whose download URL passes the trusted-URL policy. */
    val apk: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && UrlPolicy.GITHUB.isTrustedAssetUrl(it.downloadUrl) }

    /** `<apk>.sha256` companion asset, if the release has one. */
    val checksumAsset: ReleaseAsset?
        get() {
            val a = apk ?: return null
            return assets.firstOrNull {
                it.name.equals(a.name + ".sha256", ignoreCase = true) && UrlPolicy.GITHUB.isTrustedAssetUrl(it.downloadUrl)
            }
        }
}

/** Why an operation failed. Rendered to a user-facing string by the UI layer (never raw exception text). */
sealed class UpdateFailure {
    object Offline : UpdateFailure()
    data class RateLimited(val resetEpochSeconds: Long?) : UpdateFailure()
    data class HttpError(val code: Int) : UpdateFailure()
    data class BlockedUrl(val url: String) : UpdateFailure()
    object TooManyRedirects : UpdateFailure()
    object BadResponse : UpdateFailure()
    object TooLarge : UpdateFailure()
    object HashMissing : UpdateFailure()
    object HashMismatch : UpdateFailure()
    object NoApkAsset : UpdateFailure()
    object NotEnoughSpace : UpdateFailure()
    object Cancelled : UpdateFailure()
    object ApkUnreadable : UpdateFailure()
    object WrongPackage : UpdateFailure()
    object NotNewer : UpdateFailure()
    object SignatureMismatch : UpdateFailure()
    object InstallerUnavailable : UpdateFailure()
}

class UpdateException(val failure: UpdateFailure, message: String? = null) : Exception(message ?: failure.toString())
