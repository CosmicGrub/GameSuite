package com.gamesuite.games.darts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class DartsRulesTest {
    private fun hit(x: Double, y: Double) = DartsBoard.resolve(x, y)

    // ---- the board ---------------------------------------------------------------------------

    @Test fun boardResolvesKnownPoints() {
        assertEquals(DartHit(Ring.BULL, 25), hit(0.0, 0.0))
        assertEquals(50, hit(3.0, 3.0).score)
        assertEquals(DartHit(Ring.OUTER_BULL, 25), hit(0.0, -10.0))
        assertEquals(DartHit(Ring.TREBLE, 20), hit(0.0, -103.0))
        assertEquals(60, hit(0.0, -103.0).score)
        assertEquals(DartHit(Ring.DOUBLE, 20), hit(0.0, -166.0))
        assertEquals(DartHit(Ring.SINGLE, 20), hit(0.0, -57.0))
        assertEquals(DartHit(Ring.SINGLE, 20), hit(0.0, -134.0))
        assertEquals(DartHit(Ring.MISS, 0), hit(0.0, -171.0))
        assertEquals(0, hit(300.0, 300.0).score)
        // clockwise from the top: 1 at 18 degrees, 6 at 90 degrees, 3 at the bottom, 11 at 270 degrees
        assertEquals(1, hit(103.0 * Math.sin(Math.toRadians(18.0)), -103.0 * Math.cos(Math.toRadians(18.0))).number)
        assertEquals(6, hit(80.0, 0.0).number)
        assertEquals(3, hit(0.0, 80.0).number)
        assertEquals(11, hit(-80.0, 0.0).number)
    }

    @Test fun everySectorCentreResolvesToItsOwnNumberAndRing() {
        for (n in 1..20) for (ring in listOf(Ring.SINGLE, Ring.DOUBLE, Ring.TREBLE)) {
            val (x, y) = DartsBoard.center(ring, n)
            assertEquals(DartHit(ring, n), hit(x, y))
        }
        for (n in 1..20) {
            val (x, y) = DartsBoard.outerSingle(n)
            assertEquals(DartHit(Ring.SINGLE, n), hit(x, y))
        }
    }

    @Test fun sectorOrderIsTheStandardBoard() {
        assertEquals(20, DartsBoard.SECTORS.size)
        assertEquals((1..20).toSet(), DartsBoard.SECTORS.toSet())
        assertEquals(listOf(20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5), DartsBoard.SECTORS.toList())
    }

    @Test fun hitCodesAndScores() {
        assertEquals("T20", DartHit(Ring.TREBLE, 20).code)
        assertEquals("D16", DartHit(Ring.DOUBLE, 16).code)
        assertEquals("S5", DartHit(Ring.SINGLE, 5).code)
        assertEquals("25", DartHit(Ring.OUTER_BULL, 25).code)
        assertEquals("50", DartHit(Ring.BULL, 25).code)
        assertTrue(DartHit(Ring.BULL, 25).isDouble)
        assertFalse(DartHit(Ring.OUTER_BULL, 25).isDouble)
        assertEquals(0, DartHit(Ring.MISS, 0).score)
    }

    // ---- X01 ---------------------------------------------------------------------------------

    @Test fun x01CountsDownAndFinishesOnADouble() {
        val o = X01.apply(40, true, DartHit(Ring.DOUBLE, 20), doubleOut = true, doubleIn = false)
        assertTrue(o.finished); assertEquals(0, o.remaining); assertFalse(o.bust)
        val single = X01.apply(20, true, DartHit(Ring.SINGLE, 20), true, false)
        assertTrue("a single cannot finish with double out", single.bust)
        assertEquals(20, single.remaining)
        val straight = X01.apply(20, true, DartHit(Ring.SINGLE, 20), false, false)
        assertTrue(straight.finished)
        val bull = X01.apply(50, true, DartHit(Ring.BULL, 25), true, false)
        assertTrue(bull.finished)
        val outer = X01.apply(25, true, DartHit(Ring.OUTER_BULL, 25), true, false)
        assertTrue("25 cannot finish on the outer bull with double out", outer.bust)
    }

    @Test fun x01BustsOnOverAndOnOneRemaining() {
        assertTrue(X01.apply(10, true, DartHit(Ring.TREBLE, 20), true, false).bust)
        assertTrue(X01.apply(10, true, DartHit(Ring.TREBLE, 20), false, false).bust)
        val leavesOne = X01.apply(21, true, DartHit(Ring.SINGLE, 20), true, false)
        assertTrue(leavesOne.bust)
        assertFalse(X01.apply(21, true, DartHit(Ring.SINGLE, 20), false, false).bust)
    }

    @Test fun doubleInNeedsADoubleFirst() {
        val miss = X01.apply(501, false, DartHit(Ring.TREBLE, 20), doubleOut = true, doubleIn = true)
        assertEquals(501, miss.remaining); assertFalse(miss.started); assertEquals(0, miss.counted)
        val start = X01.apply(501, false, DartHit(Ring.DOUBLE, 10), true, true)
        assertEquals(481, start.remaining); assertTrue(start.started)
        val bull = X01.apply(501, false, DartHit(Ring.BULL, 25), true, true)
        assertEquals(451, bull.remaining)
        // without double-in a player is always started
        assertEquals(441, X01.apply(501, false, DartHit(Ring.TREBLE, 20), true, false).remaining)
    }

    // ---- Cricket -----------------------------------------------------------------------------

    @Test fun cricketMarksAndPoints() {
        val zero = List(7) { 0 }
        val (m1, p1) = Cricket.apply(zero, listOf(zero), DartHit(Ring.TREBLE, 20))
        assertEquals(3, m1[0]); assertEquals(0, p1)
        // a treble on an already closed number scores 3 x 20 while the opponent has it open
        val (m2, p2) = Cricket.apply(m1, listOf(zero), DartHit(Ring.TREBLE, 20))
        assertEquals(3, m2[0]); assertEquals(60, p2)
        // ... and nothing once the opponent has closed it
        val closedOpp = zero.toMutableList().also { it[0] = 3 }
        assertEquals(0, Cricket.apply(m1, listOf(closedOpp), DartHit(Ring.TREBLE, 20)).second)
        // a double with two marks on the number: closes with one, scores one extra mark
        val two = zero.toMutableList().also { it[1] = 2 }
        val (m3, p3) = Cricket.apply(two, listOf(zero), DartHit(Ring.DOUBLE, 19))
        assertEquals(3, m3[1]); assertEquals(19, p3)
        // bull: outer 1 mark, bull 2 marks, extra marks score 25 each
        val (m4, _) = Cricket.apply(zero, listOf(zero), DartHit(Ring.BULL, 25))
        assertEquals(2, m4[6])
        val (m5, p5) = Cricket.apply(m4, listOf(zero), DartHit(Ring.BULL, 25))
        assertEquals(3, m5[6]); assertEquals(25, p5)
        // numbers not in Cricket and misses do nothing
        assertEquals(zero to 0, Cricket.apply(zero, listOf(zero), DartHit(Ring.TREBLE, 14)))
        assertEquals(zero to 0, Cricket.apply(zero, listOf(zero), DartHit(Ring.MISS, 0)))
    }

    @Test fun cricketWinNeedsEverythingClosedAndAtLeastTheMostPoints() {
        val closed = List(7) { 3 }
        assertTrue(Cricket.hasWon(closed, 50, listOf(50)))
        assertTrue(Cricket.hasWon(closed, 60, listOf(50, 10)))
        assertFalse(Cricket.hasWon(closed, 40, listOf(50)))
        assertFalse(Cricket.hasWon(closed.toMutableList().also { it[3] = 2 }, 99, listOf(0)))
        assertTrue(Cricket.hasWon(closed, 0, emptyList()))
    }

    // ---- checkout ----------------------------------------------------------------------------

    @Test fun checkoutKnownRoutes() {
        fun codes(n: Int) = DartsCheckout.route(n)!!.map { it.code }
        assertEquals(listOf("D20"), codes(40))
        assertEquals(listOf("D16"), codes(32))
        assertEquals(listOf("50"), codes(50))
        assertEquals(listOf("T20", "T20", "50"), codes(170))
        assertEquals(listOf("T20", "T19", "50"), codes(167))
        assertEquals(listOf("T20", "T18", "50"), codes(164))
        assertEquals(listOf("T20", "T17", "50"), codes(161))
        assertEquals(listOf("T20", "T20", "D20"), codes(160))
        assertEquals(listOf("T20", "T20", "D19"), codes(158))
        assertEquals(listOf("T20", "D20"), codes(100))
    }

    @Test fun checkoutImpossibleScores() {
        for (n in listOf(169, 168, 166, 165, 163, 162, 159)) assertNull("$n", DartsCheckout.route(n))
        assertNull(DartsCheckout.route(171)); assertNull(DartsCheckout.route(1)); assertNull(DartsCheckout.route(0))
        // every other score from 2 to 170 has a three-dart finish
        val impossible = setOf(169, 168, 166, 165, 163, 162, 159)
        for (n in 2..170) if (n !in impossible) assertNotNull("$n", DartsCheckout.route(n))
    }

    @Test fun everyCheckoutRouteAddsUpEndsOnADoubleAndIsShort() {
        for (n in 2..170) {
            val r = DartsCheckout.route(n) ?: continue
            assertTrue(r.size in 1..3)
            assertEquals(n, r.sumOf { it.score })
            assertTrue("$n must end on a double", r.last().isDouble)
            // and the fewest darts possible: a shorter budget never beats it
            val shorter = DartsCheckout.route(n, r.size - 1)
            if (r.size > 1) assertNull(shorter)
        }
    }

    @Test fun checkoutWithoutDoubleOutFinishesOnAnything() {
        assertEquals(listOf("S1"), DartsCheckout.route(1, 3, doubleOut = false)!!.map { it.code })
        assertEquals(listOf("T20"), DartsCheckout.route(60, 3, doubleOut = false)!!.map { it.code })
        assertNull(DartsCheckout.route(181, 3, doubleOut = false))
        assertEquals(180, DartsCheckout.route(180, 3, doubleOut = false)!!.sumOf { it.score })
    }

    @Test fun checkoutRespectsTheDartBudget() {
        assertNull(DartsCheckout.route(100, 1))
        assertEquals(2, DartsCheckout.route(100, 2)!!.size)
        assertEquals(1, DartsCheckout.route(40, 1)!!.size)
    }

    // ---- the aim-wobble model ---------------------------------------------------------------

    @Test fun landingIsDeterministicForTheSameSeedAndSerial() {
        val a = DartsAim.land(0.0, -103.0, 0.68, 12.0, DartsAim.throwRandom(42L, 5))
        val b = DartsAim.land(0.0, -103.0, 0.68, 12.0, DartsAim.throwRandom(42L, 5))
        assertEquals(a, b)
        assertTrue(a != DartsAim.land(0.0, -103.0, 0.68, 12.0, DartsAim.throwRandom(42L, 6)))
        assertTrue(a != DartsAim.land(0.0, -103.0, 0.68, 12.0, DartsAim.throwRandom(43L, 5)))
    }

    private fun rms(sigma: Double, power: Double, n: Int = 4000): Pair<Double, Double> {
        val rng = Random(7)
        var sx = 0.0; var sy = 0.0
        repeat(n) {
            val (x, y) = DartsAim.land(0.0, 0.0, power, sigma, rng)
            sx += x * x; sy += y * y
        }
        return sqrt(sx / n) to sqrt(sy / n)
    }

    @Test fun scatterGrowsWithSigmaAndIsTallerThanWide() {
        val (x7, y7) = rms(7.0, 0.68)
        val (x19, y19) = rms(19.0, 0.68)
        assertTrue(x19 > 2.2 * x7)
        assertEquals(7.0, x7, 0.6)
        assertEquals(19.0, x19, 1.2)
        assertTrue("vertical wobble is larger", y7 > x7 * 1.1)
    }

    @Test fun softAndHardFlicksAreWorse() {
        val (xi, yi) = rms(10.0, 0.68)
        val (xh, _) = rms(10.0, 1.0)
        val (xs, _) = rms(10.0, 0.2)
        assertTrue(xh > xi * 1.3)
        assertTrue(xs > xi * 1.5)
        // a soft throw also sags below the aim point
        val rng = Random(3)
        val ys = (0 until 500).map { DartsAim.land(0.0, 0.0, 0.1, 1.0, rng).second }.average()
        assertTrue("soft flicks land low: $ys", ys > 25.0)
        assertTrue(abs(yi) > 0.0)
    }

    @Test fun landingStaysInsideTheClampAndOnTenthsOfAMillimetre() {
        val rng = Random(1)
        repeat(500) {
            val (x, y) = DartsAim.land(390.0, -390.0, 0.0, 40.0, rng)
            assertTrue(abs(x) <= DartsBoard.MAX_AIM && abs(y) <= DartsBoard.MAX_AIM)
            assertEquals(x, Math.round(x * 10.0) / 10.0, 1e-9)
        }
    }

    @Test fun oddsAreADistributionAndAimingAtATrebleFavoursIt() {
        val d = DartsOdds.distribution(0.0, -103.0, 9.0)
        assertEquals(1.0, d.values.sum(), 1e-9)
        val t20 = d[DartHit(Ring.TREBLE, 20)] ?: 0.0
        assertTrue("T20 odds $t20", t20 > 0.2)
        val sloppy = DartsOdds.distribution(0.0, -103.0, 30.0)[DartHit(Ring.TREBLE, 20)] ?: 0.0
        assertTrue(sloppy < t20)
        assertEquals(1.0, DartsOdds.distribution(0.0, 0.0, 0.01).getValue(DartHit(Ring.BULL, 25)), 1e-9)
    }
}
