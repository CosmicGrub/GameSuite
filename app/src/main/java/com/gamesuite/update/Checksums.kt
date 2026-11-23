package com.gamesuite.update

import java.security.MessageDigest

object Checksums {
    private val hex64 = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])""")

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Hex(bytes: ByteArray): String = hex(newDigest().digest(bytes))

    fun isValidHex(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Constant-time, case-insensitive comparison of two hex digests. */
    fun matches(a: String, b: String): Boolean {
        val x = a.trim().lowercase().toByteArray(); val y = b.trim().lowercase().toByteArray()
        return MessageDigest.isEqual(x, y)
    }

    /** `sha256sum` output (`<hex>  name` or `<hex> *name`) or a bare hex digest. */
    fun parseChecksumFile(text: String, assetName: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // Prefer a line that names our asset; otherwise a lone digest.
        lines.firstOrNull { it.contains(assetName, ignoreCase = true) }?.let { l -> hex64.find(l)?.let { return it.value.lowercase() } }
        if (lines.size == 1) hex64.find(lines[0])?.let { return it.value.lowercase() }
        return null
    }

    /** A release-body line such as `sha256: <hex>` or `<hex>  <asset>`. */
    fun fromReleaseBody(body: String, assetName: String): String? {
        for (line in body.lineSequence()) {
            val m = hex64.find(line) ?: continue
            if (line.contains("sha256", ignoreCase = true) || line.contains(assetName, ignoreCase = true)) return m.value.lowercase()
        }
        return null
    }
}
