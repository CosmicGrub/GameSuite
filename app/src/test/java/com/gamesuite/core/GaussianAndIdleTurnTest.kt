package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GaussianAndIdleTurnTest {
    @Test fun sum4MatchesTheOldBilliardsAndGolfCopy() {
        val a = Random(42); val b = Random(42)
        repeat(200) {
            var s = 0.0
            repeat(4) { s += b.nextDouble() - 0.5 }
            assertEquals(s * sqrt(3.0), Gaussian.sum4(a), 0.0)
        }
    }

    @Test fun sum3MatchesTheOldSoccerAndTennisCopy() {
        val a = Random(7); val b = Random(7)
        repeat(200) {
            val old = (b.nextDouble() + b.nextDouble() + b.nextDouble() - 1.5) * 2.0
            assertEquals(old, Gaussian.sum3(a), 0.0)
        }
    }

    @Test fun boxMullerMatchesTheOldDartsCopyAndIsClamped() {
        val a = Random(99); val b = Random(99)
        repeat(500) {
            val u1 = 1.0 - b.nextDouble(); val u2 = b.nextDouble()
            val m = sqrt(-2.0 * ln(u1))
            val x = (m * cos(2 * PI * u2)).coerceIn(-3.2, 3.2)
            val y = (m * sin(2 * PI * u2)).coerceIn(-3.2, 3.2)
            val (gx, gy) = Gaussian.boxMuller(a)
            assertEquals(x, gx, 0.0); assertEquals(y, gy, 0.0)
            assertTrue(gx in -3.2..3.2 && gy in -3.2..3.2)
        }
    }

    @Test fun idleTurnExpiresAtTheLimitNotBefore() {
        assertFalse(IdleTurnPolicy.expired(1_000, 1_000 + IdleTurnPolicy.TURN_LIMIT_MS - 1))
        assertTrue(IdleTurnPolicy.expired(1_000, 1_000 + IdleTurnPolicy.TURN_LIMIT_MS))
    }

    @Test fun aClockThatWentBackwardsNeverExpires() {
        assertFalse(IdleTurnPolicy.expired(10_000, 5_000))
    }
}
