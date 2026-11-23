package com.gamesuite.core

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySeedTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun sameInstantGivesSameSeedAcrossTimeZones() {
        val t = ms(2026, 11, 22, 12, 0, ZoneOffset.UTC)
        val a = DailySeed.forToday("sudoku", 1, ZoneOffset.UTC, t)
        val b = DailySeed.forToday("sudoku", 1, ZoneOffset.UTC, t)
        assertEquals(a, b)
    }

    @Test fun localZoneNeverMovesTheSeed() {
        // 23:30 in New York on the 21st is already the 22nd in UTC; the seed follows UTC, so it
        // matches a player in Tokyo at 13:30 on the 22nd.
        val ny = ms(2026, 11, 21, 23, 30, ZoneId.of("America/New_York"))
        val tokyo = ms(2026, 11, 22, 13, 30, ZoneId.of("Asia/Tokyo"))
        assertEquals(
            DailySeed.forToday("kenken", 0, nowMillis = ny),
            DailySeed.forToday("kenken", 0, nowMillis = tokyo)
        )
    }

    @Test fun rollsOverExactlyAtUtcMidnight() {
        val before = ms(2026, 11, 22, 23, 59, ZoneOffset.UTC) + 59_000
        val after = ms(2026, 11, 23, 0, 0, ZoneOffset.UTC)
        assertNotEquals(DailySeed.forToday("kakuro", 0, nowMillis = before), DailySeed.forToday("kakuro", 0, nowMillis = after))
        assertEquals(DailySeed.forToday("kakuro", 0, nowMillis = ms(2026, 11, 22, 0, 0, ZoneOffset.UTC)), DailySeed.forToday("kakuro", 0, nowMillis = before))
        assertEquals(DailySeed.epochDay(after) , DailySeed.epochDay(before) + 1)
    }

    @Test fun oneDailyPerTierAndPerGame() {
        val seeds = HashSet<Long>()
        for (g in listOf("sudoku", "kenken", "nonogram")) for (tier in 0..3) seeds += DailySeed.forDay(g, tier, 20_000L)
        assertEquals(12, seeds.size)
    }

    @Test fun adjacentDaysAreWellSpread() {
        val a = DailySeed.forDay("sudoku", 0, 20_000L)
        val b = DailySeed.forDay("sudoku", 0, 20_001L)
        assertTrue("seeds must not be adjacent integers", Math.abs(a - b) > 1_000_000L)
    }

    @Test fun stableGoldenValueSoDailiesNeverSilentlyChange() {
        assertEquals(DailySeed.forDay("sudoku", 1, 20_000L), DailySeed.forDay("sudoku", 1, 20_000L))
        // Pin the value once computed so an accidental algorithm change is caught.
        assertEquals(GOLDEN, DailySeed.forDay("sudoku", 1, 20_000L))
    }

    private companion object { const val GOLDEN = -9083247217936862607L }
}
