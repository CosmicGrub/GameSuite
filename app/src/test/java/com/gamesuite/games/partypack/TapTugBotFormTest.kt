package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TapTugBotFormTest {
    private fun share(base: CpuDifficulty, played: CpuDifficulty, n: Int = 20_000): Double {
        val r = Random(3)
        return (0 until n).count { TapTugBotForm.roundLevel(base, r) == played }.toDouble() / n
    }

    @Test fun aHardComputerMostlyPlaysHardButHasOffDays() {
        assertEquals(0.62, share(CpuDifficulty.HARD, CpuDifficulty.HARD), 0.02)
        assertEquals(0.30, share(CpuDifficulty.HARD, CpuDifficulty.MEDIUM), 0.02)
        assertEquals(0.08, share(CpuDifficulty.HARD, CpuDifficulty.EASY), 0.015)
    }

    @Test fun mediumAndEasyNeverPlayAboveOrFarBelowTheirLevel() {
        assertEquals(0.0, share(CpuDifficulty.MEDIUM, CpuDifficulty.HARD), 0.0)
        assertEquals(0.0, share(CpuDifficulty.EASY, CpuDifficulty.HARD), 0.0)
        assertEquals(0.15, share(CpuDifficulty.EASY, CpuDifficulty.MEDIUM), 0.02)
        assertTrue(share(CpuDifficulty.MEDIUM, CpuDifficulty.MEDIUM) > 0.8)
    }

    @Test fun theSameSeedReplaysTheSameForm() {
        val a = Random(11); val b = Random(11)
        repeat(100) { assertEquals(TapTugBotForm.roundLevel(CpuDifficulty.HARD, a), TapTugBotForm.roundLevel(CpuDifficulty.HARD, b)) }
    }
}
