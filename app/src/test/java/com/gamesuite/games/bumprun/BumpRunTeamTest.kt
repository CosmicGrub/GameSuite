package com.gamesuite.games.bumprun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Four seats, teams: seats 0 and 2 against 1 and 3. Pawns per seat 2: seat k owns pawns 2k and 2k+1. */
class BumpRunTeamTest {
    private val base = brBoard(seats = 4, teams = true)
    private fun hop(n: Int) = brCard(BumpCardKind.HOP, n)

    @Test fun teamsOnlyExistWithFourSeats() {
        assertTrue(base.rules.teams)
        assertFalse(brBoard(seats = 3, teams = true).rules.teams)
        assertEquals(listOf(0, 1, 0, 1), base.players.map { it.teamId })
    }

    @Test fun aSeatMayMoveAnyPawnOfItsTeam() {
        val s = brHold(base, hop(3), mapOf(0 to 10, 4 to 10))
        val m = bumpEngine.legalMoves(s)
        assertTrue(BumpMove(0, BumpMoveKind.STEP, 13) in m)
        assertTrue("seat 2 is the partner", BumpMove(4, BumpMoveKind.STEP, 13) in m)
        // an opponent pawn is not movable
        val opp = brHold(base, hop(3), mapOf(2 to 10))
        assertTrue(bumpEngine.legalMoves(opp).isEmpty())
    }

    @Test fun landingOnAPartnersPawnIsIllegal() {
        // seat 2 (side 2, exit 23): put its pawn on the square seat 0's pawn would hop to
        val target = brSquare(4, 0, 13)
        val s = brHold(base, hop(3), mapOf(0 to 10, 4 to brProgressAt(4, 2, target)))
        assertFalse(BumpMove(0, BumpMoveKind.STEP, 13) in bumpEngine.legalMoves(s))
        assertEquals(BumpRunReject.OWN_PAWN, bumpEngine.explainMove(s, BumpMove(0, BumpMoveKind.STEP, 13)))
    }

    @Test fun bumpAndSwapNeverTargetAPartner() {
        val partner = brProgressAt(4, 2, brSquare(4, 0, 20))
        val sw = brHold(base, brCard(BumpCardKind.SWAP), mapOf(0 to 10, 4 to partner))
        assertTrue(bumpEngine.legalMoves(sw).isEmpty())
        val enemy = brProgressAt(4, 1, brSquare(4, 0, 20))
        val sw2 = brHold(base, brCard(BumpCardKind.SWAP), mapOf(0 to 10, 4 to partner, 2 to enemy))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.SWAP, 0, 2)), bumpEngine.legalMoves(sw2).filter { it.pawn == 0 })
        assertTrue(bumpEngine.legalMoves(sw2).all { it.target == 2 })
    }

    @Test fun shieldMayCoverAPartnersPawnAndItExpiresOnTheTeamsNextTurn() {
        val s = brHold(base, brCard(BumpCardKind.SHIELD), mapOf(4 to 10))
        val m = bumpEngine.legalMoves(s)
        assertEquals(listOf(BumpMove(4, BumpMoveKind.SHIELD)), m)
        val after = bumpEngine.play(s, m[0])
        assertTrue(after.shields[4])
        // seat 1 then seat 2 (the partner's turn) starts: the team's shields expire
        val seat1Done = brStep(brHold(after, hop(1), mapOf(2 to 0), shields = setOf(4), current = 1), 2, 1)
        assertEquals(2, seat1Done.currentPlayerIndex)
        assertFalse(seat1Done.shields[4])
    }

    @Test fun freeExitBringsOutAnyTeamPawnFromItsOwnNest() {
        val s = brHold(base, brCard(BumpCardKind.FREE_EXIT))
        val exits = bumpEngine.legalMoves(s)
        assertEquals(setOf(0, 4), exits.map { it.pawn }.toSet())
        val after = bumpEngine.play(s, BumpMove(4, BumpMoveKind.EXIT, 0))
        assertEquals(0, after.pawns[4])
    }

    @Test fun teamWinsWhenAllItsPawnsAreHomeAndBothMatesAreWinners() {
        val home = mapOf(0 to 39, 1 to 39, 4 to 39, 5 to 38)
        val s = brHold(base, hop(1), home)
        val after = brStep(s, 5, 39)
        assertTrue(after.over)
        assertEquals(setOf("p1", "p3"), after.winnerPlayerIds.toSet())
        assertEquals(emptyList<String>(), bumpEngine.validate(after))
    }

    @Test fun oneMateFinishedIsNotAWin() {
        val s = brHold(base, hop(1), mapOf(0 to 38, 1 to 39, 4 to 10))
        val after = brStep(s, 0, 39)
        assertFalse(after.over)
    }
}
