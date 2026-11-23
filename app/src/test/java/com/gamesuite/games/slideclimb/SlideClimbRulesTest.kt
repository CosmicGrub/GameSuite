package com.gamesuite.games.slideclimb

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlideClimbRulesTest {
    private val layout = SlideClimbLayout.DEFAULT

    private fun step(from: Int, roll: Int, exact: Boolean = false) = SlideClimbRules.step(from, roll, layout, exact)

    @Test fun aPlainRollWalksThatManySquares() {
        val s = step(0, 3)
        assertEquals(3, s.landed); assertEquals(3, s.to)
        assertEquals(JumpKind.NONE, s.jump)
        assertFalse(s.bounced); assertFalse(s.stuck); assertFalse(s.won)
    }

    @Test fun landingOnTheFootOfARopeClimbsIt() {
        val s = step(0, 5)
        assertEquals(5, s.landed); assertEquals(16, s.to); assertEquals(JumpKind.CLIMB, s.jump)
        assertEquals(EVENT_CLIMB, SlideClimbEvent.of(s))
    }

    @Test fun landingOnTheHeadOfASlideSlidesDown() {
        val s = step(14, 4)
        assertEquals(18, s.landed); assertEquals(6, s.to); assertEquals(JumpKind.SLIDE, s.jump)
        assertEquals(SlideClimbEvent.SLIDE, SlideClimbEvent.of(s))
    }

    @Test fun passingOverAJumpDoesNothing() {
        // 4 -> 10 walks over the rope foot on 5
        val s = step(4, 6)
        assertEquals(10, s.to); assertEquals(JumpKind.NONE, s.jump)
    }

    @Test fun aJumpIsTakenOnlyOnce() {
        for (roll in 1..6) for (from in 0..99) {
            if (from in layout.jumps) continue
            val s = step(from, roll)
            assertEquals(s.to, layout.destination(s.to))
        }
    }

    @Test fun exactlyOneHundredWinsInBothModes() {
        for (exact in listOf(false, true)) {
            val s = step(97, 3, exact)
            assertEquals(100, s.to); assertTrue(s.won)
            assertEquals(SlideClimbEvent.WIN, SlideClimbEvent.of(s))
        }
    }

    @Test fun overshootingBouncesBackByDefault() {
        val s = step(98, 5)  // 98 + 5 = 103: up to 100, back 3 to 97
        assertTrue(s.bounced); assertFalse(s.stuck)
        assertEquals(97, s.landed); assertEquals(97, s.to)
        assertEquals(SlideClimbEvent.BOUNCE, SlideClimbEvent.of(s))
        assertEquals(95, step(99, 6).landed)
        assertEquals(99, step(95, 6).landed)
    }

    @Test fun aBounceCanLandOnASlide() {
        val s = step(99, 5)  // lands on 96, the head of a slide to 73
        assertTrue(s.bounced)
        assertEquals(96, s.landed); assertEquals(73, s.to); assertEquals(JumpKind.SLIDE, s.jump)
        assertEquals(SlideClimbEvent.SLIDE, SlideClimbEvent.of(s))
    }

    @Test fun anExactFinishKeepsTheOvershootingPieceStill() {
        val s = step(98, 5, exact = true)
        assertTrue(s.stuck); assertFalse(s.bounced)
        assertEquals(98, s.to)
        assertEquals(SlideClimbEvent.STUCK, SlideClimbEvent.of(s))
        assertEquals(100, step(98, 2, exact = true).to)
        assertEquals(99, step(98, 1, exact = true).to)
    }

    @Test fun aRollThatJustReachesTheFinishIsNotAnOvershoot() {
        assertFalse(step(94, 6).bounced)
        assertEquals(100, step(94, 6).to)
    }

    @Test fun everyLegalStepEndsOnTheBoard() {
        for (exact in listOf(false, true)) for (from in 0..99) for (roll in 1..6) {
            val s = step(from, roll, exact)
            assertTrue(s.to in 0..100 && s.landed in 0..100)
        }
    }

    @Test fun theHopPathWalksSquareBySquareToTheLandingSquare() {
        assertEquals(listOf(1, 2, 3), SlideClimbRules.hopPath(0, 3, false))
        assertEquals(listOf(15, 16, 17, 18), SlideClimbRules.hopPath(14, 4, false))
        for (exact in listOf(false, true)) for (from in 0..99) for (roll in 1..6) {
            val path = SlideClimbRules.hopPath(from, roll, exact)
            val s = step(from, roll, exact)
            if (s.stuck) assertTrue(path.isEmpty()) else assertEquals(s.landed, path.last())
            var at = from
            for (sq in path) { assertEquals(1, kotlin.math.abs(sq - at)); at = sq }
        }
    }

    @Test fun theHopPathTurnsAroundAtOneHundred() {
        assertEquals(listOf(99, 100, 99, 98, 97), SlideClimbRules.hopPath(98, 5, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aDieHasSixFaces() {
        step(0, 7)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPieceOnTheFinishHasNothingLeftToRoll() {
        step(100, 1)
    }

    // ---- the computer's spare-roll decision ----

    private val coin = Random(3)

    private fun wants(pos: Int, roll: Int, level: CpuDifficulty, exact: Boolean = false) =
        SlideClimbBot.wantsSpareRoll(pos, roll, layout, exact, level, coin)

    @Test fun mediumSpendsTheSpareOnlyToAvoidASlideOrStandingStill() {
        assertTrue(wants(14, 4, CpuDifficulty.MEDIUM))         // would slide 18 -> 6
        assertTrue(wants(98, 5, CpuDifficulty.MEDIUM, true))   // stuck
        assertFalse(wants(14, 1, CpuDifficulty.MEDIUM))        // a plain step forward
        assertFalse(wants(0, 5, CpuDifficulty.MEDIUM))         // a rope
    }

    @Test fun hardAlsoRerollsAWeakRoll() {
        assertTrue(wants(14, 1, CpuDifficulty.HARD))
        assertFalse(wants(14, 2, CpuDifficulty.HARD))
        assertFalse(wants(0, 5, CpuDifficulty.HARD))
    }

    @Test fun neitherMediumNorHardThrowsAwayAWin() {
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertFalse(wants(99, 1, level))
            assertFalse(wants(97, 3, level, true))
        }
    }

    @Test fun mediumAndHardNeverTouchTheCoin() {
        val untouched = SlideClimbScriptedRandom(1)
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            SlideClimbBot.wantsSpareRoll(14, 4, layout, false, level, untouched)
        }
        assertEquals(0, untouched.calls)
    }

    private companion object {
        const val EVENT_CLIMB = SlideClimbEvent.CLIMB
    }
}
