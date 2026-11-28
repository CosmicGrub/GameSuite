package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadMovementTest {
    private val e = LRT.engine

    private fun table(vararg spins: Int, catchUp: Boolean = false, n: Int = 2) =
        LRT.start(n, LRT.config(seed = LRT.seedFor(*spins), catchUp = catchUp))

    @Test fun spinMovesExactlyThatManySquares() {
        val s = LRT.at(table(3), 0, "LRN.0")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals("LRN.3", r.players[0].nodeId)
        assertEquals(3, r.players[0].stepsMoved - s.players[0].stepsMoved)
        assertEquals(s.players[0].cash + LifeRoadRules.REST_COIN, r.players[0].cash)
        assertEquals(1, r.currentSeat)
    }

    @Test fun passingAPayStopCollectsOnce() {
        val s = LRT.at(table(3), 0, "TA.6")
        val r = LRT.act(s, LifeRoadAction.Spin)
        // passes TA.8 ($: odd jobs 10) then lands on TA.9 (rest 5)
        assertEquals(s.players[0].cash + 10 + 5, r.players[0].cash)
    }

    @Test fun landingOnAPayStopAddsTheBonus() {
        val s = LRT.at(table(2), 0, "TA.6")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals("TA.8", r.players[0].nodeId)
        assertEquals(s.players[0].cash + 10 + LifeRoadRules.PAY_LANDING_BONUS, r.players[0].cash)
    }

    @Test fun passingAForkMidMoveAsksForAChoice() {
        val s = LRT.at(table(3), 0, "TA.11")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(Phase.AWAIT_FORK, r.phase)
        assertEquals("F2", r.pending.forkNodeId)
        assertEquals(2, r.pending.stepsLeft)
        val after = LRT.act(r, LifeRoadAction.ChooseBranch("RISK.0"))
        assertEquals("RISK.1", after.players[0].nodeId)
        // RISK.1 is a bold pay stop: odd jobs 10 and the landing bonus 10, each scaled by 1.5
        assertEquals(s.players[0].cash + LifeRoadRules.boldCoins(10) * 2, after.players[0].cash)
    }

    @Test fun landingOnAForkDefersTheChoiceToNextTurn() {
        val s = LRT.at(table(2, 1), 0, "TA.10")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals("F2", r.players[0].nodeId)
        val next = LRT.act(LRT.at(r, 1, "LRN.0"), LifeRoadAction.Spin)
        assertEquals(0, next.currentSeat)
        assertEquals(Phase.AWAIT_FORK, next.phase)
        assertEquals(0, next.pending.stepsLeft)
        val spin = LRT.act(next, LifeRoadAction.ChooseBranch("SAFE.0"))
        assertEquals(Phase.AWAIT_SPIN, spin.phase)
    }

    @Test fun enteringARetireNodeStopsMovement() {
        val s = LRT.at(table(5), 0, "COZY.4")
        val r = LRT.act(s, LifeRoadAction.Spin)
        val p = r.players[0]
        assertTrue(p.retired)
        assertEquals("RC", p.nodeId)
        assertEquals(1, p.retireOrder)
        assertEquals(RetireLane.COZY, p.lane)
        assertEquals(1, p.cardMemories)
        assertEquals(1, r.currentSeat)
    }

    @Test fun luckyCoinRespinsOnlyOncePerTurn() {
        var s = LRT.edit(table(2, 9), 0) { it.copy(luckyCoins = 2) }
        s = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(Phase.AWAIT_RESPIN, s.phase)
        assertEquals(2, s.pending.spin)
        val used = s.copy(luckyUsedThisTurn = true)
        assertEquals(LifeRoadReject.NO_LUCKY_COIN, e.explain(used, 0, LifeRoadAction.UseLuckyCoin))
        val r = LRT.act(s, LifeRoadAction.UseLuckyCoin)
        assertEquals(1, r.players[0].luckyCoins)
        assertEquals(9, r.lastSpin)
    }

    @Test fun luckyCoinOnlyBeforeTheMove() {
        val s = LRT.edit(table(2), 0) { it.copy(luckyCoins = 1) }
        assertEquals(LifeRoadReject.WRONG_PHASE, e.explain(s, 0, LifeRoadAction.UseLuckyCoin))
        val kept = LRT.act(LRT.act(s, LifeRoadAction.Spin), LifeRoadAction.KeepSpin)
        assertEquals(1, kept.players[0].luckyCoins)
        assertEquals(1, kept.currentSeat)
    }

    @Test fun catchUpSpinOnlyWhenFarBehind() {
        val base = table(2, catchUp = true, n = 3)
        val gap = RoadSize.STANDARD.catchUpGap
        val behind = LRT.edit(base, 1) { it.copy(stepsMoved = gap) }
        // seat 0 is behind seat 1 by exactly the gap: it spins twice and keeps the higher
        val ev = LRT.step(behind, LifeRoadAction.Spin).events
        assertTrue(ev.any { it.kind == LifeRoadEventKind.CATCH_UP })
        val near = LRT.edit(base, 1) { it.copy(stepsMoved = gap - 1) }
        assertTrue(LRT.step(near, LifeRoadAction.Spin).events.none { it.kind == LifeRoadEventKind.CATCH_UP })
    }

    @Test fun cardMovementNeverTriggersASecondCard() {
        // tailwind moves forward 3 from LRN.2 onto LRN.5 (the lucky wheel): it must not open the wheel
        val s = LRT.at(LRT.card(table(1), "tailwind"), 0, "LRN.2")
        val r = LRT.act(s, LifeRoadAction.Acknowledge)
        assertEquals("LRN.5", r.players[0].nodeId)
        assertEquals(1, r.currentSeat)
        assertFalse(r.phase == Phase.AWAIT_CARD)
    }

    @Test fun moveBackStopsAtTheStartOfTheCurrentSegment() {
        val s = LRT.at(LRT.card(table(1), "thick_fog"), 0, "LRN.1")
        val r = LRT.act(s, LifeRoadAction.Acknowledge)
        assertEquals("LRN.0", r.players[0].nodeId)
    }

    @Test fun extraTurnCardGrantsAnotherSpin() {
        val s = LRT.card(table(1), "extra_spin")
        val r = LRT.act(s, LifeRoadAction.Acknowledge)
        assertEquals(0, r.currentSeat)
        assertEquals(Phase.AWAIT_SPIN, r.phase)
        assertTrue(r.extraTurn)
    }
}
