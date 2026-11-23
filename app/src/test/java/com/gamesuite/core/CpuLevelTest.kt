package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuLevelTest {
    @Test
    fun levelsMapOntoTheEngineDifficulties() {
        assertEquals(CpuDifficulty.EASY, CpuLevel.EASY.difficulty)
        assertEquals(CpuDifficulty.MEDIUM, CpuLevel.MEDIUM.difficulty)
        assertEquals(CpuDifficulty.HARD, CpuLevel.HARD.difficulty)
        assertEquals(CpuDifficulty.HARD, CpuLevel.EXPERT.difficulty)
        assertEquals(4, CpuLevel.values().size)
    }

    @Test
    fun applyToThenFromRoundTripsEveryLevel() {
        CpuLevel.values().forEach { level ->
            val o = level.applyTo(LaunchOptions())
            assertEquals(level.difficulty, o.cpuDifficulty)
            assertEquals(level, CpuLevel.from(o))
        }
    }

    @Test
    fun expertIsDistinguishedOnlyByTheExtraMarker() {
        val expert = CpuLevel.EXPERT.applyTo(LaunchOptions())
        assertEquals("expert", expert.extras[CpuLevel.EXTRA_KEY])
        val hard = CpuLevel.HARD.applyTo(expert)
        assertNull(hard.extras[CpuLevel.EXTRA_KEY])
        assertEquals(CpuLevel.HARD, CpuLevel.from(hard))
        assertTrue(CpuLevel.EXPERT.isExpert)
        assertFalse(CpuLevel.HARD.isExpert)
    }

    @Test
    fun otherLaunchOptionsAreKept() {
        val base = LaunchOptions(firstMoverIndex = 1, extras = mapOf("variant" to "misere"))
        val o = CpuLevel.EXPERT.applyTo(base)
        assertEquals(1, o.firstMoverIndex)
        assertEquals("misere", o.extras["variant"])
        assertEquals("misere", CpuLevel.EASY.applyTo(o).extras["variant"])
    }

    @Test
    fun noOverrideUsesTheSettingsDefault() {
        assertEquals(CpuLevel.MEDIUM, CpuLevel.from(LaunchOptions()))
        assertEquals(CpuLevel.HARD, CpuLevel.from(LaunchOptions(), CpuDifficulty.HARD))
        assertEquals(CpuLevel.EASY, CpuLevel.from(LaunchOptions(), CpuDifficulty.EASY))
    }

    @Test
    fun thinkTimeGrowsWithLevelAndNeverBelow400ms() {
        val t = CpuLevel.values().map { it.minThinkMillis }
        assertEquals(t.sorted(), t)
        assertTrue(t.all { it >= 400L })
    }

    @Test
    fun idsParse() {
        CpuLevel.values().forEach { assertEquals(it, CpuLevel.fromId(it.id)) }
        assertNull(CpuLevel.fromId("nope"))
        assertEquals(CpuLevel.HARD, CpuLevel.fromDifficulty(CpuDifficulty.HARD))
    }
}
