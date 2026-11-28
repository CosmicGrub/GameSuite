package com.gamesuite.games.bumprun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seats: 2 (pawns 0,1 are seat 0 on side 0, exit square 5; pawns 2,3 are seat 1 on side 2, exit square 23). */
class BumpRunRulesTest {
    private val base = brBoard()
    private fun hop(n: Int) = brCard(BumpCardKind.HOP, n)
    private fun rival(onSquareOfSeat0Progress: Int, progressOffset: Int = 0) =
        brProgressAt(2, 1, brSquare(2, 0, onSquareOfSeat0Progress) + progressOffset)
    private fun moves(s: BumpRunState) = bumpEngine.legalMoves(s)
    private fun steps(s: BumpRunState) = moves(s).filter { it.kind == BumpMoveKind.STEP }

    @Test fun hopMovesOnePawnTheCardValue() {
        val s = brHold(base, hop(3), mapOf(0 to 10))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 13)), moves(s))
        val after = brStep(s, 0, 13)
        assertEquals(13, after.pawns[0])
        assertEquals(1, after.currentPlayerIndex)
        assertEquals(BumpPhase.AWAIT_DRAW, after.phase)
        assertEquals(BumpRunEvent.MOVE, after.event)
    }

    @Test fun hopOverPawnsIsAllowed() {
        val s = brHold(base, hop(3), mapOf(0 to 10, 1 to 11))
        assertTrue(BumpMove(0, BumpMoveKind.STEP, 13) in moves(s))
    }

    @Test fun hopLandingOnAnOwnPawnIsIllegal() {
        val s = brHold(base, hop(3), mapOf(0 to 10, 1 to 13))
        assertFalse(BumpMove(0, BumpMoveKind.STEP, 13) in moves(s))
        assertEquals(BumpRunReject.OWN_PAWN, bumpEngine.explainMove(s, BumpMove(0, BumpMoveKind.STEP, 13)))
        assertEquals(s, brStep(s, 0, 13))
    }

    @Test fun hopLandingOnARivalBumpsItToTheNest() {
        val s = brHold(base, hop(3), mapOf(0 to 10, 2 to rival(13)))
        val after = brStep(s, 0, 13)
        assertEquals(BumpRunBoard.NEST, after.pawns[2])
        assertEquals(13, after.pawns[0])
        assertEquals(1, after.bumpsMade[0])
        assertEquals(1, after.bumpsTaken[1])
        assertEquals(BumpRunEvent.BUMP, after.event)
        assertEquals(2, after.eventVictim)
    }

    @Test fun hopLandingOnAShieldedRivalIsIllegal() {
        val s = brHold(base, hop(3), mapOf(0 to 10, 2 to rival(13)), shields = setOf(2))
        assertTrue(steps(s).isEmpty())
        assertEquals(BumpRunReject.SHIELDED, bumpEngine.explainMove(s, BumpMove(0, BumpMoveKind.STEP, 13)))
    }

    @Test fun hopPastHomeIsIllegalExactCountRequired() {
        val s = brHold(base, hop(3), mapOf(0 to 37))
        assertTrue(steps(s).isEmpty())
        assertEquals(BumpRunReject.EXACT_ROLL, bumpEngine.explainMove(s, BumpMove(0, BumpMoveKind.STEP, 40)))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 39)), steps(brHold(base, hop(2), mapOf(0 to 37))))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 38)), steps(brHold(base, hop(1), mapOf(0 to 37))))
    }

    @Test fun hopOntoHomeExactlyIsLegalForManyPawns() {
        val b = brBoard(length = BumpLength.LONG)
        // both pawns two from Home: Home holds any number
        val s = brHold(b, hop(2), mapOf(0 to 37, 1 to 37))
        val to = steps(s)
        assertEquals(2, to.size)
        val a = brStep(s, 0, 39)
        assertEquals(39, a.pawns[0])
    }

    @Test fun lanePawnsAreNeverBumped() {
        val s = brHold(base, hop(3), mapOf(0 to 34, 2 to 37))
        val after = brStep(s, 0, 37)
        assertEquals(37, after.pawns[0])
        assertEquals(37, after.pawns[2])
        assertEquals(0, after.bumpsMade[0])
    }

    @Test fun twoOwnPawnsCannotShareALaneSquare() {
        val s = brHold(base, hop(3), mapOf(0 to 34, 1 to 37))
        assertFalse(BumpMove(0, BumpMoveKind.STEP, 37) in moves(s))
        assertEquals(BumpRunReject.OWN_PAWN, bumpEngine.explainMove(s, BumpMove(0, BumpMoveKind.STEP, 37)))
    }

    @Test fun starCardsMayBringAPawnOutOfTheNest() {
        for (n in listOf(1, 6)) assertTrue(BumpMove(0, BumpMoveKind.EXIT, 0) in moves(brHold(base, hop(n))))
        assertTrue(moves(brHold(base, hop(2))).isEmpty())
        val after = bumpEngine.play(brHold(base, hop(1)), BumpMove(0, BumpMoveKind.EXIT, 0))
        assertEquals(0, after.pawns[0])
        assertEquals(BumpRunEvent.EXIT, after.event)
    }

    @Test fun exitBumpsARivalStandingOnTheExitSquare() {
        val s = brHold(base, hop(1), mapOf(2 to rival(0)))
        val after = bumpEngine.play(s, BumpMove(0, BumpMoveKind.EXIT, 0))
        assertEquals(0, after.pawns[0])
        assertEquals(BumpRunBoard.NEST, after.pawns[2])
        assertEquals(1, after.bumpsMade[0])
    }

    @Test fun exitIsBlockedByAnOwnPawnOnTheExitSquare() {
        val s = brHold(base, hop(1), mapOf(1 to 0))
        assertTrue(moves(s).none { it.kind == BumpMoveKind.EXIT })
    }

    @Test fun exitIsBlockedByAShieldedRivalOnTheExitSquare() {
        val s = brHold(base, hop(1), mapOf(2 to rival(0)), shields = setOf(2))
        assertTrue(moves(s).none { it.kind == BumpMoveKind.EXIT })
    }

    @Test fun freeExitBringsOutAPawnAndWithNoPawnInTheNestPlaysAsHopThree() {
        val fe = brCard(BumpCardKind.FREE_EXIT)
        assertEquals(listOf(BumpMoveKind.EXIT), moves(brHold(base, fe)).map { it.kind })
        val s = brHold(base, fe, mapOf(0 to 10, 1 to 20))
        assertEquals(setOf(BumpMove(0, BumpMoveKind.STEP, 13), BumpMove(1, BumpMoveKind.STEP, 23)), moves(s).toSet())
    }

    @Test fun freeExitWithABlockedExitPlaysAsHopThree() {
        val s = brHold(base, brCard(BumpCardKind.FREE_EXIT), mapOf(1 to 0, 0 to -1))
        // pawn 0 in the Nest, exit blocked by own pawn 1: the card plays as Hop 3 for pawn 1
        assertEquals(listOf(BumpMove(1, BumpMoveKind.STEP, 3)), moves(s))
    }

    @Test fun swapExchangesSquaresWithARivalOnTheRingAndRecomputesProgress() {
        val s = brHold(base, brCard(BumpCardKind.SWAP), mapOf(0 to 10, 2 to rival(20)))
        val m = moves(s)
        assertEquals(listOf(BumpMove(0, BumpMoveKind.SWAP, 0, 2)), m)
        val after = bumpEngine.play(s, m[0])
        assertEquals(brSquare(2, 1, s.pawns[2]), brSquare(2, 0, after.pawns[0]))
        assertEquals(brSquare(2, 0, 10), brSquare(2, 1, after.pawns[2]))
        assertEquals(0, after.bumpsMade[0])
        assertEquals(BumpRunEvent.SWAP, after.event)
    }

    @Test fun swapNeverTargetsAShieldedPawnOrOneOffTheRing() {
        val sw = brCard(BumpCardKind.SWAP)
        assertTrue(moves(brHold(base, sw, mapOf(0 to 10, 2 to rival(20)), shields = setOf(2))).isEmpty())
        assertTrue(moves(brHold(base, sw, mapOf(0 to 10, 2 to 37))).isEmpty())
        assertTrue(moves(brHold(base, sw, mapOf(2 to rival(20)))).isEmpty())
    }

    @Test fun swapWithNoPairIsANoMoveAndPasses() {
        val s = brTop(base, brCard(BumpCardKind.SWAP))
        val d = bumpEngine.draw(s)
        assertEquals(BumpRunEvent.NO_MOVE, d.event)
        assertEquals(1, d.currentPlayerIndex)
        assertTrue(brCard(BumpCardKind.SWAP) in d.discard)
    }

    @Test fun chaseLeapsToTheNearestRivalAheadAndBumpsIt() {
        val s = brHold(base, brCard(BumpCardKind.CHASE), mapOf(0 to 10, 1 to 12, 2 to rival(10, 5)))
        val m = moves(s)
        assertEquals(listOf(BumpMove(0, BumpMoveKind.CHASE, 15, 2)), m)
        val after = bumpEngine.play(s, m[0])
        assertEquals(15, after.pawns[0])
        assertEquals(-1, after.pawns[2])
    }

    @Test fun chaseWithNoRivalAheadOrAShieldedOnePlaysAsHopFour() {
        val ch = brCard(BumpCardKind.CHASE)
        val none = brHold(base, ch, mapOf(0 to 10))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 14)), moves(none))
        val shielded = brHold(base, ch, mapOf(0 to 10, 2 to rival(10, 5)), shields = setOf(2))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 14)), moves(shielded))
    }

    @Test fun chaseNeverReachesPastTheLaneEntry() {
        // pawn at 33, rival 4 squares ahead lies beyond the lane entry for this side
        val s = brHold(base, brCard(BumpCardKind.CHASE), mapOf(0 to 33, 2 to rival(33, 4)))
        assertTrue(moves(s).none { it.kind == BumpMoveKind.CHASE })
    }

    @Test fun againDrawsAnotherCardForTheSameSeat() {
        val ag = brCard(BumpCardKind.AGAIN)
        val d = bumpEngine.draw(brTop(base, ag))
        assertEquals(BumpRunEvent.AGAIN, d.event)
        assertEquals(0, d.currentPlayerIndex)
        assertEquals(BumpPhase.AWAIT_DRAW, d.phase)
        assertTrue(ag in d.discard)
        assertEquals(emptyList<String>(), bumpEngine.validate(d))
    }

    @Test fun shieldProtectsUntilTheOwnersNextTurn() {
        val s = brHold(base, brCard(BumpCardKind.SHIELD), mapOf(0 to 10))
        val after = bumpEngine.play(s, BumpMove(0, BumpMoveKind.SHIELD))
        assertTrue(after.shields[0])
        // seat 1 plays and the turn comes back: the shield has expired at the START of seat 0's turn
        val seat1 = brHold(after, hop(1), mapOf(2 to 5), shields = setOf(0), current = 1)
        val back = brStep(seat1, 2, 6)
        assertEquals(0, back.currentPlayerIndex)
        assertFalse(back.shields[0])
        // and while it holds, seat 1 cannot land on it
        val blocked = brHold(after, hop(3), mapOf(2 to brProgressAt(2, 1, brSquare(2, 0, 10) - 3)), shields = setOf(0), current = 1)
        assertTrue(steps(blocked).isEmpty())
    }

    @Test fun shieldWithNoRingPawnPlaysAsHopTwo() {
        val s = brHold(base, brCard(BumpCardKind.SHIELD), mapOf(0 to 37))
        assertEquals(listOf(BumpMove(0, BumpMoveKind.STEP, 39)), moves(s))
    }

    @Test fun springPadHopsThreeMoreAndIsReported() {
        val b = brBoard(pads = true)
        val s = brHold(b, hop(3), mapOf(0 to 2))
        val after = brStep(s, 0, 5)
        assertEquals(8, after.pawns[0])
        assertEquals(BumpRunEvent.PAD, after.event)
        assertEquals(5, after.eventPadFrom)
        // off: no hop
        assertEquals(5, brStep(brHold(base, hop(3), mapOf(0 to 2)), 0, 5).pawns[0])
    }

    @Test fun springPadStaysPutOnAnOwnPawnAndBumpsARival() {
        val b = brBoard(pads = true)
        val own = brStep(brHold(b, hop(3), mapOf(0 to 2, 1 to 8)), 0, 5)
        assertEquals(5, own.pawns[0])
        val bump = brStep(brHold(b, hop(3), mapOf(0 to 2, 2 to rival(8))), 0, 5)
        assertEquals(8, bump.pawns[0])
        assertEquals(-1, bump.pawns[2])
        assertEquals(1, bump.bumpsMade[0])
    }

    @Test fun springPadDoesNotFireAfterASwap() {
        val b = brBoard(pads = true)
        // seat 0's pawn swaps onto ring square 10 (a pad square) and stays there
        val s = brHold(b, brCard(BumpCardKind.SWAP), mapOf(0 to 30, 2 to brProgressAt(2, 1, 10)))
        val after = bumpEngine.play(s, bumpEngine.legalMoves(s).first())
        assertEquals(brProgressAt(2, 0, 10), after.pawns[0])
    }

    @Test fun theFirstSeatWithAllPawnsHomeWinsAtOnce() {
        val s = brHold(base, hop(1), mapOf(0 to 39, 1 to 38))
        val after = brStep(s, 1, 39)
        assertTrue(after.over)
        assertEquals(listOf("p1"), after.winnerPlayerIds)
        assertEquals(BumpRunEvent.WIN, after.event)
        assertEquals(emptyList<String>(), bumpEngine.validate(after))
    }

    @Test fun passIsLegalOnlyWithNoMove() {
        val s = brHold(base, hop(3))
        assertEquals(BumpRunReject.NONE, bumpEngine.explainMove(s, BumpMove(-1, BumpMoveKind.PASS)))
        val live = brHold(base, hop(3), mapOf(0 to 10))
        assertEquals(BumpRunReject.MOVES_AVAILABLE, bumpEngine.explainMove(live, BumpMove(-1, BumpMoveKind.PASS)))
        assertEquals(live, bumpEngine.play(live, BumpMove(-1, BumpMoveKind.PASS)))
    }

    @Test fun aDoOverIsOfferedInsteadOfAnAutomaticPass() {
        val b = brBoard(helpers = setOf(0))
        assertEquals(3, b.doOvers[0])
        assertEquals(0, b.pawns[0])
        // a Hop 2 for a lone pawn never blocks; use a card with no move: swap with nothing to swap
        val d = bumpEngine.draw(brTop(b, brCard(BumpCardKind.SWAP)))
        assertEquals(BumpPhase.AWAIT_CHOICE, d.phase)
        assertTrue(bumpEngine.legalMoves(d).isEmpty())
        val re = bumpEngine.doOver(d)
        assertEquals(2, re.doOvers[0])
        assertTrue(brCard(BumpCardKind.SWAP) in re.discard)
        assertEquals(BumpRunEvent.DO_OVER, re.event)
        assertNotNull(re.card)
        assertEquals(emptyList<String>(), bumpEngine.validate(re))
    }

    @Test fun explainDrawAndMoveGuards() {
        assertEquals(BumpRunReject.NONE, bumpEngine.explainDraw(base))
        assertEquals(BumpRunReject.DRAW_FIRST, bumpEngine.explainMove(base, BumpMove(0, BumpMoveKind.STEP, 1)))
        val held = brHold(base, hop(3), mapOf(0 to 10))
        assertEquals(BumpRunReject.DECIDE_FIRST, bumpEngine.explainDraw(held))
        assertEquals(held, bumpEngine.draw(held))
        assertEquals(BumpRunReject.CANNOT_MOVE, bumpEngine.explainMove(held, BumpMove(1, BumpMoveKind.STEP, 3)))
    }
}
