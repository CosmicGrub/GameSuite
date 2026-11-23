package com.gamesuite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotBastionSectorArtTest {

    @Test fun reducedMotionFreezesTheAmbientClock() {
        assertEquals(bbAmbientPhase(0.0, BbAmbientMotion.FROZEN), bbAmbientPhase(123.4, BbAmbientMotion.FROZEN), 0.0)
        assertEquals(10.0, bbAmbientPhase(10.0, BbAmbientMotion.STANDARD), 1e-9)
        assertEquals(5.0, bbAmbientPhase(10.0, BbAmbientMotion.CALM), 1e-9)
    }

    @Test fun aFrozenMoteNeverMoves() {
        for (m in bbMotes(3)) {
            val a = bbWrap(m.y, 0.02 * m.speed, bbAmbientPhase(0.0, BbAmbientMotion.FROZEN))
            val b = bbWrap(m.y, 0.02 * m.speed, bbAmbientPhase(500.0, BbAmbientMotion.FROZEN))
            assertEquals(a, b, 0f)
        }
    }

    @Test fun wrapStaysInTheUnitRangeBothWays() {
        for (t in listOf(-50.0, -0.3, 0.0, 0.7, 1.0, 99.9)) {
            val v = bbWrap(0.4f, 0.02, t)
            assertTrue("$t -> $v", v >= 0f && v < 1f)
        }
    }

    @Test fun motesAreDeterministicAndDifferPerSector() {
        assertEquals(26, bbMotes(1).size)
        assertEquals(bbMotes(2).map { it.x }, bbMotes(2).map { it.x })
        assertNotEquals(bbMotes(1).map { it.x }, bbMotes(2).map { it.x })
        assertTrue(bbMotes(4).all { it.x in 0f..1f && it.y in 0f..1f && it.size > 0f })
    }

    @Test fun eachSectorHasItsOwnLook() {
        assertEquals(4, (1..4).map { BbSectors.look(it).base }.toSet().size)
        assertEquals(4, (1..4).map { BbSectors.look(it).accent }.toSet().size)
    }
}
