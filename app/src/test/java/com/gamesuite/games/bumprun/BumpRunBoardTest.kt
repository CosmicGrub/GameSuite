package com.gamesuite.games.bumprun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BumpRunBoardTest {
    @Test fun ringHasThirtySixSquaresAndFourExits() {
        assertEquals(36, BumpRunBoard.LOOP)
        assertEquals(4, BumpRunBoard.EXIT_SQUARES.toSet().size)
        assertTrue(BumpRunBoard.EXIT_SQUARES.all { it in 0 until 36 })
    }

    @Test fun exitAndLaneEntrySquaresAreAdjacent() {
        for (i in 0..3) assertEquals((BumpRunBoard.LANE_ENTRY_SQUARES[i] + 1) % 36, BumpRunBoard.EXIT_SQUARES[i])
    }

    @Test fun padsNeverCoincideWithExitsOrLaneEntries() {
        assertTrue(BumpRunBoard.PADS.none { it in BumpRunBoard.EXIT_SQUARES || it in BumpRunBoard.LANE_ENTRY_SQUARES })
    }

    @Test fun squareOfAndProgressOnAreInverse() {
        for (side in 0..3) for (p in 0 until 36) assertEquals(p, BumpRunBoard.progressOn(side, BumpRunBoard.squareOf(side, p)!!))
    }

    @Test fun progressAfterAFullLapEndsOnTheLaneEntry() {
        for (side in 0..3) assertEquals(BumpRunBoard.LANE_ENTRY_SQUARES[side], BumpRunBoard.squareOf(side, 35))
        for (p in listOf(-1, 36, 37, 38, 39)) assertNull(BumpRunBoard.squareOf(0, p))
    }

    @Test fun padHopNeverCrossesTheLaneEntry() {
        for (side in 0..3) for (p in 0 until 36) {
            if (BumpRunBoard.squareOf(side, p) in BumpRunBoard.PADS) assertTrue("side $side progress $p", p + BumpRunBoard.PAD_HOP <= 35)
        }
    }

    @Test fun sidesForTwoSeatsAreOpposite() {
        assertEquals(listOf(0, 2), BumpRunBoard.sidesFor(2))
    }

    @Test fun sidesForThreeAndFourSeats() {
        assertEquals(listOf(0, 1, 2), BumpRunBoard.sidesFor(3))
        assertEquals(listOf(0, 1, 2, 3), BumpRunBoard.sidesFor(4))
    }

    @Test fun teamsOnlyExistWithFourSeatsAndPartnersSitOpposite() {
        val s = brBoard(seats = 4, teams = true)
        assertEquals(listOf(0, 1, 0, 1), s.players.map { it.teamId })
        assertFalse(brBoard(seats = 2, teams = true).rules.teams)
    }

    @Test fun lengthsAndUnknownIdsFallBackToQuick() {
        assertEquals(listOf(2, 3, 4), BumpLength.values().map { it.pawns })
        assertEquals(BumpLength.QUICK, BumpLength.fromId("nonsense"))
        assertEquals(BumpLength.QUICK, BumpLength.fromId(null))
    }
}
