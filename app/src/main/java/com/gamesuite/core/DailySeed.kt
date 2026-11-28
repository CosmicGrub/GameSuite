package com.gamesuite.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The one place a "daily puzzle" seed comes from.
 *
 * Why it exists: dailies used to seed from `LocalDate.now().toEpochDay()`, the DEVICE's local date.
 * Two players in different time zones therefore saw different "today" puzzles, a player who crossed
 * midnight locally got a new one mid-evening, and every daily-capable game used the same number so
 * they shared their random streams. The seed now depends only on the shared UTC calendar day, the
 * game id and the tier index, and never on the player's difficulty setting or local zone.
 *
 * [tier] is the daily slot within one game (0 for a game with a single daily; a tier ordinal for a
 * game that offers one daily per difficulty). Different tiers of the same day give different seeds.
 */
object DailySeed {
    /** Today's calendar day number in [zone] (UTC by default) at [nowMillis]. */
    fun epochDay(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneOffset.UTC): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()

    /** Today's shared (UTC) day number; use where a game derives its own seed from a day, e.g. Flow Lines. */
    fun todayEpochDay(): Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()

    /** The daily seed for [gameId] and [tier] on the calendar day containing [nowMillis] in [zone]. */
    fun forToday(
        gameId: String,
        tier: Int = 0,
        zone: ZoneId = ZoneOffset.UTC,
        nowMillis: Long = System.currentTimeMillis()
    ): Long = forDay(gameId, tier, epochDay(nowMillis, zone))

    /**
     * Gives each difficulty tier of a daily its own seed. [seed] is the day's seed as handed to a
     * game's `startMatch(dailySeed)` (the same for every tier); the result differs per [tier] (a
     * difficulty ordinal, or any stable per-configuration number) and stays the same for the same
     * day, game and tier on every device. A null [seed] (a normal, non-daily board) stays null.
     */
    fun forTier(seed: Long?, tier: Int): Long? =
        if (seed == null) null else mix(seed xor ((tier + 1).toLong() * 0x2545F4914F6CDD1DL))

    /** Pure form: the seed for an explicit day number. */
    fun forDay(gameId: String, tier: Int, epochDay: Long): Long {
        var h = fnv1a(gameId)
        h = mix(h xor epochDay)
        h = mix(h xor (tier.toLong() * -0x61c8864680b583ebL))
        return h
    }

    private fun fnv1a(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /** splitmix64 finalizer: spreads adjacent day numbers over the whole Long range. */
    private fun mix(z0: Long): Long {
        var z = z0 + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
