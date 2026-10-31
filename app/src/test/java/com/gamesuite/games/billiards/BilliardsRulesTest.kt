package com.gamesuite.games.billiards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Known-value rule checks for 8-ball and 9-ball: every foul, ball in hand, group assignment, winning and losing the 8 and the 9. */
class BilliardsRulesTest {
    private val all8 = ((1..15)).toSet()
    private fun ctx8(
        shooter: Int = 0, groups: List<Int> = listOf(0, 0), onTable: Set<Int> = all8, isBreak: Boolean = false, streak: Int = 0
    ) = RuleContext(BilliardsVariant.EIGHT, shooter, groups, onTable, isBreak, streak)

    private fun ctx9(onTable: Set<Int> = (1..9).toSet(), isBreak: Boolean = false, streak: Int = 0, shooter: Int = 0) =
        RuleContext(BilliardsVariant.NINE, shooter, listOf(0, 0), onTable, isBreak, streak)

    private fun out(first: Int, pocketed: List<Int> = emptyList(), rail: Boolean = true, rails: Int = 0) =
        ShotOutcome(first, pocketed, rail, rails)

    // ---- racks ------------------------------------------------------------------------------------

    @Test fun theEightBallRackHasTheEightInTheMiddleAndMixedBackCorners() {
        for (seed in 1..60) {
            val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(seed))
            assertEquals(16, on.count { it })
            val eight = Pair(x[8], y[8])
            assertEquals(BilliardsTable.FOOT_X + 2 * Math.sqrt(3.0) * (1.5 + 0.01), eight.first, 1e-6)
            assertEquals(25.0, eight.second, 1e-6)
            val back = (1..15).filter { Math.abs(x[it] - (BilliardsTable.FOOT_X + 4 * Math.sqrt(3.0) * 1.51)) < 1e-6 }
            assertEquals(5, back.size)
            val corners = back.filter { y[it] == back.minOf { b -> y[b] } || y[it] == back.maxOf { b -> y[b] } }
            assertEquals(2, corners.size)
            assertEquals(setOf(1, 2), corners.map { BilliardsTable.groupOf(it) }.toSet())
            for (i in 1..15) for (j in i + 1..15) assertTrue(Math.hypot(x[i] - x[j], y[i] - y[j]) >= 3.0)
        }
    }

    @Test fun theNineBallDiamondHasTheOneOnTheApexAndTheNineInTheMiddle() {
        for (seed in 1..30) {
            val (x, y, on) = BilliardsRules.rack(BilliardsVariant.NINE, Random(seed))
            assertEquals(10, on.count { it })
            assertTrue((10..15).none { on[it] })
            assertEquals(BilliardsTable.FOOT_X, x[1], 1e-9)
            assertEquals(BilliardsTable.FOOT_X + 2 * Math.sqrt(3.0) * 1.51, x[9], 1e-6)
            assertEquals(25.0, y[9], 1e-6)
        }
    }

    @Test fun theSameSeedRacksTheSameWayAndDifferentSeedsDiffer() {
        val a = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(9)).first.toList()
        assertEquals(a, BilliardsRules.rack(BilliardsVariant.EIGHT, Random(9)).first.toList())
        assertFalse(a == BilliardsRules.rack(BilliardsVariant.EIGHT, Random(10)).first.toList())
    }

    // ---- 8-ball: the break ------------------------------------------------------------------------

    @Test fun aLegalBreakWithAPocketedBallKeepsTheTurnAndTheTableStaysOpen() {
        val v = BilliardsRules.judge(ctx8(isBreak = true), out(1, listOf(5), rails = 2))
        assertFalse(v.foul); assertEquals(0, v.nextShooter); assertEquals(0, v.assignedGroup); assertEquals(listOf(5), v.pocketed)
    }

    @Test fun aBreakWithFourBallsToARailAndNoPocketPassesTheTurnWithoutAFoul() {
        val v = BilliardsRules.judge(ctx8(isBreak = true), out(1, emptyList(), rails = 4))
        assertFalse(v.foul); assertEquals(1, v.nextShooter); assertFalse(v.ballInHand)
    }

    @Test fun aBreakThatDoesNotReachFourRailsOrPocketAnythingIsAFoulWithBallInHand() {
        val v = BilliardsRules.judge(ctx8(isBreak = true), out(1, emptyList(), rails = 3))
        assertEquals(listOf(FoulKind.BAD_BREAK), v.fouls)
        assertEquals(1, v.nextShooter); assertTrue(v.ballInHand); assertFalse(v.behindHead)
    }

    @Test fun aScratchOnTheBreakGivesBallInHandBehindTheHeadString() {
        val v = BilliardsRules.judge(ctx8(isBreak = true), out(1, listOf(0), rails = 5))
        assertTrue(FoulKind.SCRATCH in v.fouls)
        assertTrue(v.ballInHand); assertTrue(v.behindHead); assertEquals(1, v.nextShooter)
    }

    @Test fun anEightSunkOnTheBreakIsRespottedAndTheShooterContinues() {
        val v = BilliardsRules.judge(ctx8(isBreak = true), out(1, listOf(8), rails = 2))
        assertEquals(listOf(8), v.respot); assertEquals(-1, v.winner); assertFalse(v.foul); assertEquals(0, v.nextShooter)
        assertEquals(emptyList<Int>(), v.pocketed)
    }

    // ---- 8-ball: open table and groups ------------------------------------------------------------

    @Test fun sinkingASolidOnAnOpenTableGivesTheShooterSolids() {
        val v = BilliardsRules.judge(ctx8(), out(3, listOf(3)))
        assertEquals(1, v.assignedGroup); assertEquals(0, v.nextShooter); assertFalse(v.foul)
    }

    @Test fun sinkingAStripeAssignsStripes() {
        assertEquals(2, BilliardsRules.judge(ctx8(shooter = 1), out(12, listOf(12))).assignedGroup)
    }

    @Test fun missingEverythingOnAnOpenTablePassesTheTurnWithoutAGroup() {
        val v = BilliardsRules.judge(ctx8(), out(3, emptyList(), rail = true))
        assertFalse(v.foul); assertEquals(1, v.nextShooter); assertEquals(0, v.assignedGroup)
    }

    @Test fun hittingTheEightFirstOnAnOpenTableIsAFoul() {
        val v = BilliardsRules.judge(ctx8(), out(8, emptyList()))
        assertEquals(listOf(FoulKind.WRONG_BALL), v.fouls); assertTrue(v.ballInHand); assertEquals(1, v.nextShooter)
    }

    @Test fun afterGroupsTheFirstContactMustBeYourOwn() {
        val groups = listOf(1, 2)
        val bad = BilliardsRules.judge(ctx8(groups = groups), out(10, emptyList()))
        assertEquals(listOf(FoulKind.WRONG_BALL), bad.fouls)
        val good = BilliardsRules.judge(ctx8(groups = groups), out(4, emptyList()))
        assertFalse(good.foul)
    }

    @Test fun sinkingYourOwnBallKeepsTheTurnAndSinkingOnlyTheOpponentsEndsIt() {
        val groups = listOf(1, 2)
        assertEquals(0, BilliardsRules.judge(ctx8(groups = groups), out(4, listOf(4))).nextShooter)
        val v = BilliardsRules.judge(ctx8(groups = groups), out(4, listOf(11)))
        assertFalse(v.foul); assertEquals(1, v.nextShooter); assertEquals(listOf(11), v.pocketed)
    }

    @Test fun missingTheCueBallEntirelyIsAFoul() {
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2)), out(-1, emptyList(), rail = false))
        assertEquals(listOf(FoulKind.NO_CONTACT), v.fouls)
    }

    @Test fun noBallReachingARailOrPocketAfterContactIsAFoul() {
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2)), out(4, emptyList(), rail = false))
        assertEquals(listOf(FoulKind.NO_RAIL), v.fouls)
        // a pocketed ball satisfies the rail rule
        assertFalse(BilliardsRules.judge(ctx8(groups = listOf(1, 2)), out(4, listOf(4), rail = false)).foul)
    }

    @Test fun aScratchIsAFoulAndTheOpponentGetsBallInHandAnywhere() {
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2)), out(4, listOf(0, 4)))
        assertTrue(FoulKind.SCRATCH in v.fouls); assertTrue(v.ballInHand); assertFalse(v.behindHead); assertEquals(1, v.nextShooter)
        assertEquals(listOf(4), v.pocketed)
    }

    // ---- 8-ball: the eight -------------------------------------------------------------------------

    private val onlyEightAndStripes = (9..15).toSet() + 8

    @Test fun sinkingTheEightAfterClearingYourGroupWins() {
        val onTable = setOf(8) + (9..15)
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2), onTable = onTable), out(8, listOf(8)))
        assertEquals(0, v.winner); assertEquals(WinReason.CLEARED, v.winReason)
    }

    @Test fun sinkingTheEightTooSoonLoses() {
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2)), out(4, listOf(8)))
        assertEquals(1, v.winner); assertEquals(WinReason.EIGHT_EARLY, v.winReason)
    }

    @Test fun sinkingTheEightOnAnOpenTableLoses() {
        val v = BilliardsRules.judge(ctx8(), out(3, listOf(8)))
        assertEquals(1, v.winner)
    }

    @Test fun sinkingTheEightWhileScratchingLosesEvenWithAClearedGroup() {
        val onTable = setOf(8) + (9..15)
        val v = BilliardsRules.judge(ctx8(groups = listOf(1, 2), onTable = onTable), out(8, listOf(0, 8)))
        assertEquals(1, v.winner); assertEquals(WinReason.EIGHT_FOUL, v.winReason)
    }

    @Test fun onceTheGroupIsClearedTheEightIsTheOnlyLegalFirstBall() {
        val onTable = setOf(8) + (9..15)
        assertEquals(setOf(8), BilliardsRules.legalTargets(BilliardsVariant.EIGHT, listOf(1, 2), 0, onTable, false))
        assertEquals((9..15).toSet(), BilliardsRules.legalTargets(BilliardsVariant.EIGHT, listOf(1, 2), 1, onTable, false))
        val bad = BilliardsRules.judge(ctx8(groups = listOf(1, 2), onTable = onTable), out(12, emptyList()))
        assertEquals(listOf(FoulKind.WRONG_BALL), bad.fouls)
        assertTrue(onlyEightAndStripes.size == 8)
    }

    // ---- 9-ball ------------------------------------------------------------------------------------

    @Test fun nineBallDemandsTheLowestBallFirst() {
        val bad = BilliardsRules.judge(ctx9(onTable = setOf(3, 4, 9)), out(4, emptyList()))
        assertEquals(listOf(FoulKind.WRONG_BALL), bad.fouls); assertTrue(bad.ballInHand); assertEquals(1, bad.nextShooter)
        assertFalse(BilliardsRules.judge(ctx9(onTable = setOf(3, 4, 9)), out(3, emptyList())).foul)
    }

    @Test fun sinkingTheNineLegallyWinsEvenOnACombination() {
        val v = BilliardsRules.judge(ctx9(onTable = setOf(2, 9)), out(2, listOf(9)))
        assertEquals(0, v.winner); assertEquals(WinReason.CLEARED, v.winReason)
    }

    @Test fun theNineOnTheBreakWins() {
        val v = BilliardsRules.judge(ctx9(isBreak = true), out(1, listOf(9), rails = 2))
        assertEquals(0, v.winner)
    }

    @Test fun theNineSunkOnAFoulIsRespottedAndDoesNotWin() {
        val v = BilliardsRules.judge(ctx9(onTable = setOf(2, 5, 9)), out(5, listOf(9)))
        assertEquals(-1, v.winner); assertEquals(listOf(9), v.respot); assertTrue(v.ballInHand); assertEquals(1, v.nextShooter)
        assertEquals(emptyList<Int>(), v.pocketed)
    }

    @Test fun sinkingAnyBallLegallyKeepsTheTurnAndOtherwiseItPasses() {
        assertEquals(0, BilliardsRules.judge(ctx9(onTable = setOf(2, 5, 9)), out(2, listOf(5))).nextShooter)
        assertEquals(1, BilliardsRules.judge(ctx9(onTable = setOf(2, 5, 9)), out(2, emptyList())).nextShooter)
    }

    @Test fun threeFoulsInARowLoseTheRack() {
        val v = BilliardsRules.judge(ctx9(onTable = setOf(2, 9), streak = 2), out(9, emptyList()))
        assertEquals(1, v.winner); assertEquals(WinReason.THREE_FOULS, v.winReason)
        val two = BilliardsRules.judge(ctx9(onTable = setOf(2, 9), streak = 1), out(9, emptyList()))
        assertEquals(-1, two.winner)
    }

    // ---- placement ---------------------------------------------------------------------------------

    @Test fun theCueBallCannotBePlacedOnABallOutsideTheClothInAPocketOrPastTheHeadString() {
        val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(1))
        assertTrue(BilliardsRules.canPlaceCue(10.0, 10.0, x, y, on, false))
        assertFalse(BilliardsRules.canPlaceCue(x[3], y[3], x, y, on, false))
        assertFalse(BilliardsRules.canPlaceCue(0.5, 25.0, x, y, on, false))
        assertFalse(BilliardsRules.canPlaceCue(1.5, 1.5, x, y, on, false))
        assertFalse(BilliardsRules.canPlaceCue(30.0, 25.0, x, y, on, true))
        assertTrue(BilliardsRules.canPlaceCue(20.0, 25.0, x, y, on, true))
        assertFalse(BilliardsRules.canPlaceCue(Double.NaN, 5.0, x, y, on, false))
    }

    @Test fun freeSpotFindsTheNearestLegalSpotToABlockedOne() {
        val (x, y, on) = BilliardsRules.rack(BilliardsVariant.EIGHT, Random(1))
        val (sx, sy) = BilliardsRules.freeSpot(x[8], y[8], x, y, on)
        assertTrue(BilliardsRules.canPlaceCue(sx, sy, x, y, on, false))
    }
}
