package com.gamesuite.update

/** What the installer would see: package, version and the SHA-256 of each signing certificate. */
data class ApkFacts(val packageName: String?, val versionCode: Long?, val signerSha256: Set<String>)

/** Reads facts from a downloaded file / the installed app (PackageManager on Android; fakes in tests). */
interface ApkInspector {
    fun installed(): ApkFacts?
    fun archive(path: String): ApkFacts?
}

/** The pre-install gate: same package, newer versionCode, same signing certificate. Never auto-uninstalls anything. */
object ApkPolicy {
    /** @return null when the APK may be handed to the installer, otherwise the reason. */
    fun check(installed: ApkFacts, candidate: ApkFacts?, allowNotNewer: Boolean = false): UpdateFailure? {
        if (candidate == null || candidate.packageName == null || candidate.versionCode == null) return UpdateFailure.ApkUnreadable
        if (candidate.packageName != installed.packageName) return UpdateFailure.WrongPackage
        if (installed.signerSha256.isEmpty() || candidate.signerSha256.isEmpty() ||
            installed.signerSha256 != candidate.signerSha256
        ) return UpdateFailure.SignatureMismatch
        val inst = installed.versionCode ?: return UpdateFailure.ApkUnreadable
        if (!allowNotNewer && candidate.versionCode <= inst) return UpdateFailure.NotNewer
        return null
    }
}
