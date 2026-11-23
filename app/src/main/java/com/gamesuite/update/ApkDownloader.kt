package com.gamesuite.update

import java.io.File
import java.io.IOException

/**
 * Streams the APK into [dir] (an app-private cache folder), capped at [maxBytes], hashing as it goes, and only renames
 * the file into place once the SHA-256 matches. Any failure deletes the partial file.
 */
class ApkDownloader(
    private val http: SecureHttp,
    private val dir: File,
    private val maxBytes: Long = MAX_APK_BYTES,
    private val freeSpace: () -> Long = { Long.MAX_VALUE }
) {
    /** Resolves the expected digest: the `.sha256` companion asset first, then a line in the release body. */
    fun expectedSha256(release: ReleaseInfo, apk: ReleaseAsset): String {
        release.checksumAsset?.let { a ->
            val r = http.getText(a.downloadUrl, maxBytes = 8 * 1024)
            if (r.code != 200) throw UpdateException(UpdateFailure.HttpError(r.code))
            Checksums.parseChecksumFile(r.body, apk.name)?.let { return it }
        }
        Checksums.fromReleaseBody(release.body, apk.name)?.let { return it }
        throw UpdateException(UpdateFailure.HashMissing)
    }

    fun download(
        apk: ReleaseAsset,
        expectedSha256: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        if (!Checksums.isValidHex(expectedSha256)) throw UpdateException(UpdateFailure.HashMissing)
        if (apk.size > maxBytes) throw UpdateException(UpdateFailure.TooLarge)
        if (apk.size > 0 && freeSpace() < apk.size * 2) throw UpdateException(UpdateFailure.NotEnoughSpace)
        dir.mkdirs()
        val safe = apk.name.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.takeLast(80).ifEmpty { "update.apk" }
        val part = File(dir, "$safe.part")
        val done = File(dir, safe)
        part.delete(); done.delete()
        try {
            val r = http.open(apk.downloadUrl)
            try {
                if (r.code != 200) throw UpdateException(UpdateFailure.HttpError(r.code))
                val declared = r.headers["content-length"]?.toLongOrNull() ?: -1L
                if (declared > maxBytes) throw UpdateException(UpdateFailure.TooLarge)
                val total = if (declared > 0) declared else apk.size
                val input = r.body ?: throw UpdateException(UpdateFailure.BadResponse)
                val digest = Checksums.newDigest()
                var count = 0L
                part.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (isCancelled()) throw UpdateException(UpdateFailure.Cancelled)
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        if (count > maxBytes) throw UpdateException(UpdateFailure.TooLarge)
                        digest.update(buf, 0, n); out.write(buf, 0, n)
                        onProgress(count, total)
                    }
                }
                if (apk.size > 0 && count != apk.size) throw UpdateException(UpdateFailure.BadResponse, "size mismatch")
                if (!Checksums.matches(Checksums.hex(digest.digest()), expectedSha256)) throw UpdateException(UpdateFailure.HashMismatch)
            } finally {
                r.close()
            }
            if (!part.renameTo(done)) throw UpdateException(UpdateFailure.BadResponse, "rename failed")
            return done
        } catch (e: IOException) {
            part.delete()
            throw UpdateException(UpdateFailure.Offline, e.javaClass.simpleName)
        } catch (e: UpdateException) {
            part.delete(); done.delete()
            throw e
        }
    }

    /** Removes every leftover APK / partial download except [keep]. */
    fun cleanStale(keep: File? = null) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".apk", true) || f.name.endsWith(".part")) && f != keep) f.delete()
        }
    }

    companion object {
        const val MAX_APK_BYTES = 250L * 1024 * 1024
    }
}
