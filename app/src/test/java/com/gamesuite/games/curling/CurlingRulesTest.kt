package com.gamesuite.games.curling

import org.junit.Assert.*
import org.junit.Test

class CurlingRulesTest {
    private val tee = CurlingSheet.TEE_Y
    private fun st(id: Int, x: Double, dy: Double = 0.0) = CurlingStone(id, id / 8, x, tee + dy)

    private val two = listOf(CurlingPlayerState("a", "A", false, 0), CurlingPlayerState("b", "B", false, 1))
    private val four = listOf(
        CurlingPlayerState("a", "A", false, 0), CurlingPlayerState("b", "B", false, 1),
        CurlingPlayerState("c", "C", false, 0), CurlingPlayerState("d", "D", false, 1)
    )

    @Test fun theClosestStoneScoresForEachStoneNearerThanTheRivalsBest() {
        // red 10 from the button, yellow 30, red 50: red scores one
        assertEquals(0 to 1, CurlingRules.scoreEnd(listOf(st(0, 10.0), st(8, 30.0), st(1, 50.0))))
        // red 10 and 20, yellow 40: red scores two
        assertEquals(0 to 2, CurlingRules.scoreEnd(listOf(st(0, 10.0), st(1, 20.0), st(8, 40.0))))
        // yellow closest alone
        assertEquals(1 to 1, CurlingRules.scoreEnd(listOf(st(0, 60.0), st(8, 5.0))))
        // three yellow nearer than red's best
        assertEquals(1 to 3, CurlingRules.scoreEnd(listOf(st(0, 90.0), st(8, 5.0), st(9, 15.0), st(10, 25.0))))
    }

    @Test fun stonesOutsideTheHouseDoNotScoreAndAnEmptyHouseIsBlank() {
        assertEquals(-1 to 0, CurlingRules.scoreEnd(emptyList()))
        assertEquals(-1 to 0, CurlingRules.scoreEnd(listOf(st(0, 0.0, -300.0), st(8, 0.0, 300.0))))
        // a stone just touching the outer ring counts, one just clear of it does not
        assertEquals(0 to 1, CurlingRules.scoreEnd(listOf(st(0, CurlingSheet.HOUSE_R + CurlingSheet.STONE_R - 1))))
        assertEquals(-1 to 0, CurlingRules.scoreEnd(listOf(st(0, CurlingSheet.HOUSE_R + CurlingSheet.STONE_R + 1))))
    }

    @Test fun anExactTieAtTheButtonIsBlank() {
        assertEquals(-1 to 0, CurlingRules.scoreEnd(listOf(st(0, 20.0), st(8, -20.0))))
    }

    @Test fun theNonHammerTeamThrowsFirstAndTheHammerThrowsLast() {
        val s = CurlingRules.newMatch(two, 4, 2, firstThrowerTeam = 0, millis = 0)
        assertEquals(1, s.hammer)
        val order = ArrayList<Int>()
        var cur = s
        repeat(4) {
            order += cur.teamToThrow
            cur = cur.copy(thrown = cur.thrown.toMutableList().also { it[cur.teamToThrow]++ })
        }
        assertEquals(listOf(0, 1, 0, 1), order)
        assertTrue(cur.endComplete)
    }

    @Test fun aTeamOfTwoShareItsStonesInPairs() {
        var s = CurlingRules.newMatch(four, 4, 4, firstThrowerTeam = 0, millis = 0)
        val seats = ArrayList<Int>()
        repeat(8) {
            seats += s.seatToThrow
            s = s.copy(thrown = s.thrown.toMutableList().also { it[s.teamToThrow]++ })
        }
        // team 0 = seats 0,2: stones 1-2 by seat 0, 3-4 by seat 2; team 1 = seats 1,3 likewise (interleaved by team)
        assertEquals(listOf(0, 1, 0, 1, 2, 3, 2, 3), seats)
    }

    @Test fun playingAFullEndScoresItAndPassesTheHammerToTheTeamThatDidNotScore() {
        var s = CurlingRules.newMatch(two, 4, 1, firstThrowerTeam = 0, millis = 0)
        val draw = CurlingPhysics.solveDraw(0.0, tee, 1)!!
        val far = CurlingPhysics.solveDraw(0.0, tee - 120, 1)!!
        // team 0 draws to the button, team 1 (hammer) draws a little short of it
        s = CurlingRules.play(s, draw, 1).state
        assertEquals(CurlingPhase.THROWING, s.phase)
        s = CurlingRules.play(s, far, 2).state
        assertEquals(CurlingPhase.END_SCORED, s.phase)
        assertEquals(0, s.endScorer)
        assertEquals(listOf(1, 0), s.scores)
        assertEquals(listOf(1, 0), s.endScores)
        val next = CurlingRules.nextEnd(s)
        assertEquals(2, next.end)
        assertEquals(1, next.hammer) // scorer (team 0) gave up the hammer; team 1 already had it, so it keeps it
        assertTrue(next.stones.isEmpty())
        assertEquals(listOf(0, 0), next.thrown)
    }

    @Test fun aBlankEndKeepsTheHammerWhereItWas() {
        val s = CurlingRules.newMatch(two, 4, 1, 0, 0).copy(thrown = listOf(1, 1), phase = CurlingPhase.END_SCORED, endScorer = -1, hammer = 0)
        assertEquals(0, CurlingRules.nextEnd(s).hammer)
        val scored = s.copy(endScorer = 0, hammer = 0)
        assertEquals(1, CurlingRules.nextEnd(scored).hammer)
    }

    @Test fun theMatchEndsAfterTheLastEndAndATieGoesToExtraEnds() {
        val base = CurlingRules.newMatch(two, 2, 1, 0, 0).copy(end = 2, thrown = listOf(0, 1), hammer = 0)
        // team 0 draws to the button last and wins the match 1-0
        val won = CurlingRules.play(base.copy(scores = listOf(0, 0), stones = emptyList()), CurlingPhysics.solveDraw(0.0, tee, 1)!!, 0).state
        assertEquals(CurlingPhase.MATCH_OVER, won.phase)
        assertEquals(0, won.winnerTeam)
        // a blank last end with the scores level goes to an extra end
        val tied = CurlingRules.play(base.copy(scores = listOf(1, 1)), CurlingThrow(0, 100, 1), 0).state
        assertEquals(CurlingPhase.END_SCORED, tied.phase)
        assertEquals(-1, tied.winnerTeam)
        val extra = CurlingRules.nextEnd(tied)
        assertEquals(3, extra.end)
        assertEquals(1, extra.extraEnds)
        // after three extra ends a tie is a draw
        val last = extra.copy(end = 5, extraEnds = 3, thrown = listOf(0, 1), scores = listOf(1, 1), stones = emptyList(), hammer = 0)
        val draw = CurlingRules.play(last, CurlingThrow(0, 100, 1), 0).state
        assertEquals(CurlingPhase.MATCH_OVER, draw.phase)
        assertEquals(-1, draw.winnerTeam)
    }

    @Test fun explainRejectsOutOfTurnAndBadThrows() {
        val s = CurlingRules.newMatch(two, 4, 4, 0, 0)
        assertEquals(CurlingReject.NONE, CurlingRules.explain(s, 0, CurlingThrow(0, 500, 1)))
        assertEquals(CurlingReject.NOT_YOUR_TURN, CurlingRules.explain(s, 1, CurlingThrow(0, 500, 1)))
        assertEquals(CurlingReject.BAD_THROW, CurlingRules.explain(s, 0, CurlingThrow(0, 5000, 1)))
        assertEquals(CurlingReject.NOT_THROWING, CurlingRules.explain(s.copy(phase = CurlingPhase.END_SCORED), 0, CurlingThrow(0, 500, 1)))
    }
}
