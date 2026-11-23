package com.gamesuite.stats

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCodecTest {

    @Test fun nullAndBlankAreEmptyNotCorrupt() {
        assertNull(decodeStatsBlob(null).corruptRaw)
        assertNull(decodeStatsBlob("  ").corruptRaw)
        assertTrue(decodeStatsBlob(null).value.isEmpty())
        assertNull(decodeProgressBlob("").corruptRaw)
    }

    @Test fun healthyStatsRoundTrip() {
        val map = mapOf("chess" to GameStats(gameId = "chess", displayName = "Chess", matchesPlayed = 3, wins = 2))
        val d = decodeStatsBlob(StatsJson.encodeToString(map))
        assertEquals(map, d.value)
        assertNull(d.corruptRaw)
    }

    @Test fun unknownFieldsFromNewerBuildStillDecode() {
        val d = decodeStatsBlob("""{"chess":{"gameId":"chess","displayName":"Chess","futureField":1}}""")
        assertNull(d.corruptRaw)
        assertEquals("chess", d.value["chess"]?.gameId)
    }

    @Test fun badStatsKeepRawAndReturnEmptySafeValue() {
        val bad = """{"chess":{"gameId":"chess","matchesPlayed":"not a number"}"""
        val d = decodeStatsBlob(bad)
        assertTrue(d.value.isEmpty())
        assertEquals(bad, d.corruptRaw)
    }

    @Test fun badProgressKeepsRaw() {
        val d = decodeProgressBlob("{truncated")
        assertEquals(ProgressState(), d.value)
        assertEquals("{truncated", d.corruptRaw)
    }

    @Test fun quarantineKeepsFirstCorruptCopyOnly() {
        assertEquals("a", quarantineValue(null, "a"))
        assertEquals("a", quarantineValue("", "a"))
        assertNull(quarantineValue("first", "second"))
        assertNull(quarantineValue(null, null))
        assertNull(quarantineValue("first", null))
    }
}
