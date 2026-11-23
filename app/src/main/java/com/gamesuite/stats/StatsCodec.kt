package com.gamesuite.stats

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Result of decoding a stored blob. [corruptRaw] is non-null only when a non-blank stored
 * value existed but could not be decoded; [value] is then an empty-safe default that is fine
 * for display, but callers that WRITE must first preserve [corruptRaw] (see [quarantineValue])
 * so a failed read can never silently destroy the stored data.
 */
internal data class Decoded<T>(val value: T, val corruptRaw: String? = null)

/** Tolerates fields a newer build wrote; missing fields take their defaults. */
internal val StatsJson = Json { ignoreUnknownKeys = true }

internal fun decodeStatsBlob(raw: String?): Decoded<Map<String, GameStats>> {
    if (raw.isNullOrBlank()) return Decoded(emptyMap())
    return runCatching { StatsJson.decodeFromString<Map<String, GameStats>>(raw) }
        .fold(onSuccess = { Decoded(it) }, onFailure = { Decoded(emptyMap(), corruptRaw = raw) })
}

internal fun decodeProgressBlob(raw: String?): Decoded<ProgressState> {
    if (raw.isNullOrBlank()) return Decoded(ProgressState())
    return runCatching { StatsJson.decodeFromString<ProgressState>(raw) }
        .fold(onSuccess = { Decoded(it) }, onFailure = { Decoded(ProgressState(), corruptRaw = raw) })
}

/**
 * What to store in the quarantine slot: the first unreadable blob is kept (never replaced by
 * a later one), so the original is always recoverable. Returns null when nothing should be
 * written (no corruption, or the slot already holds a preserved copy).
 */
internal fun quarantineValue(existingQuarantine: String?, corruptRaw: String?): String? =
    if (corruptRaw != null && existingQuarantine.isNullOrEmpty()) corruptRaw else null
