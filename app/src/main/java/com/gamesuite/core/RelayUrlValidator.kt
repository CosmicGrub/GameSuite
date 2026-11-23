package com.gamesuite.core

/** Why a hand-typed relay address was rejected. */
enum class RelayUrlError { SCHEME, HOST_MISSING, WHITESPACE, CREDENTIALS, PORT, INSECURE_PUBLIC_HOST }

sealed class RelayUrlResult {
    /** Blank input: allowed, means "Online not configured". */
    object Empty : RelayUrlResult()

    /** [normalized] is the trimmed URL with a lower-cased scheme, safe to persist. */
    data class Valid(val normalized: String) : RelayUrlResult()

    data class Invalid(val error: RelayUrlError) : RelayUrlResult()
}

/**
 * Pure validator for the relay URL typed in Settings. Rules: trimmed; no whitespace or control
 * characters; scheme must be wss:// (TLS), or ws:// only when the host is localhost, a loopback
 * address, a private/link-local LAN IPv4 address or a `.local` name (same-network testing);
 * a host is required; no `user:pass@` credentials; an explicit port must be 1-65535.
 */
object RelayUrlValidator {

    private val hostChars = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
    private val ipv6Chars = Regex("^[0-9A-Fa-f:.]+$")

    fun validate(input: String): RelayUrlResult {
        val s = input.trim()
        if (s.isEmpty()) return RelayUrlResult.Empty
        if (s.any { it.isWhitespace() || it.isISOControl() }) return RelayUrlResult.Invalid(RelayUrlError.WHITESPACE)

        val lower = s.lowercase()
        val secure = when {
            lower.startsWith("wss://") -> true
            lower.startsWith("ws://") -> false
            else -> return RelayUrlResult.Invalid(RelayUrlError.SCHEME)
        }
        val rest = s.substring(if (secure) 6 else 5)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return RelayUrlResult.Invalid(RelayUrlError.CREDENTIALS)

        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return RelayUrlResult.Invalid(RelayUrlError.HOST_MISSING)
            host = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            portText = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return RelayUrlResult.Invalid(RelayUrlError.HOST_MISSING)
            }
            if (host.isEmpty() || !ipv6Chars.matches(host) || !host.contains(':')) {
                return RelayUrlResult.Invalid(RelayUrlError.HOST_MISSING)
            }
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon >= 0) authority.substring(0, colon) else authority
            portText = if (colon >= 0) authority.substring(colon + 1) else null
            if (host.isEmpty() || !hostChars.matches(host)) return RelayUrlResult.Invalid(RelayUrlError.HOST_MISSING)
        }
        if (portText != null) {
            val port = if (portText.isNotEmpty() && portText.length <= 5 && portText.all(Char::isDigit)) portText.toInt() else null
            if (port == null || port !in 1..65535) return RelayUrlResult.Invalid(RelayUrlError.PORT)
        }
        if (!secure && !isLocalOrPrivateHost(host)) {
            return RelayUrlResult.Invalid(RelayUrlError.INSECURE_PUBLIC_HOST)
        }
        return RelayUrlResult.Valid((if (secure) "wss://" else "ws://") + rest)
    }

    /** localhost, loopback, RFC1918 / link-local IPv4, `.local` mDNS names. */
    internal fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "::1") return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = ArrayList<Int>(4)
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false
            n.add(p.toInt())
        }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 ||
            n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168) ||
            (n[0] == 169 && n[1] == 254)
    }
}
