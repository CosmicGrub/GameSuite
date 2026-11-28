package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LifeRoadStateMachineTest {
    private val e = LRT.engine

    @Test fun everyPhaseTransitionFollowsTheTable() {
        val allowed = mapOf(
            Phase.START_FORK to setOf(Phase.START_FORK, Phase.AWAIT_SPIN, Phase.AWAIT_FORK),
            Phase.AWAIT_FORK to setOf(Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.AWAIT_JOB, Phase.AWAIT_HOUSE, Phase.AWAIT_COVER, Phase.AWAIT_BANK,
                Phase.AWAIT_MARKET, Phase.AWAIT_CARD, Phase.SETTLING, Phase.GAME_OVER),
            Phase.AWAIT_SPIN to Phase.values().toSet() - Phase.START_FORK - Phase.MOVING - Phase.TURN_END,
            Phase.AWAIT_RESPIN to Phase.values().toSet() - Phase.START_FORK - Phase.MOVING - Phase.TURN_END,
            Phase.AWAIT_JOB to Phase.values().toSet() - Phase.START_FORK - Phase.MOVING - Phase.TURN_END,
            Phase.AWAIT_HOUSE to setOf(Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.GAME_OVER),
            Phase.AWAIT_COVER to setOf(Phase.AWAIT_COVER, Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.GAME_OVER),
            Phase.AWAIT_BANK to setOf(Phase.AWAIT_BANK, Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.GAME_OVER),
            Phase.AWAIT_MARKET to setOf(Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.GAME_OVER),
            Phase.AWAIT_CARD to Phase.values().toSet() - Phase.START_FORK - Phase.MOVING - Phase.TURN_END,
            Phase.SETTLING to setOf(Phase.SETTLING, Phase.AWAIT_SPIN, Phase.AWAIT_FORK, Phase.GAME_OVER)
        )
        val rnd = Random(3)
        var s = e.newGame(LRT.config(seed = 3), LRT.roster(3))
        var prev = s.phase
        var n = 0
        while (!s.over && n++ < 5000) {
            val legal = e.legalActions(s, s.currentSeat)
            s = LRT.act(s, legal[rnd.nextInt(legal.size)])
            assertTrue("$prev -> ${s.phase}", s.phase in (allowed[prev] ?: error("unexpected phase $prev")))
            prev = s.phase
        }
        assertTrue(s.over)
    }

    @Test fun illegalActionsAreRejectedWithAReason() {
        val s = LRT.start(2, LRT.config())
        assertEquals(LifeRoadReject.WRONG_PHASE, e.explain(s, 0, LifeRoadAction.TakeLoan))
        assertEquals(LifeRoadReject.WRONG_PHASE, e.explain(s, 0, LifeRoadAction.PickJob("teacher")))
        val r = LRT.step(s, LifeRoadAction.Done)
        assertFalse(r.accepted)
        assertEquals(s, r.state)
        assertTrue(r.events.isEmpty())
    }

    @Test fun onlyTheSeatOnTurnMayAct() {
        val s = LRT.start(3, LRT.config())
        assertEquals(LifeRoadReject.NOT_YOUR_TURN, e.explain(s, 1, LifeRoadAction.Spin))
        assertTrue(e.legalActions(s, 2).isEmpty())
        assertEquals(listOf(LifeRoadAction.Spin), e.legalActions(s, 0))
    }

    @Test fun retiredSeatsAreSkipped() {
        val s = LRT.edit(LRT.start(3, LRT.config(seed = LRT.seedFor(1))), 1) { it.copy(retired = true, lane = RetireLane.LATE) }
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(2, r.currentSeat)
    }

    @Test fun turnCapEndsTheGame() {
        val cap = RoadSize.QUICK.turnCapPerPlayer
        val s = LRT.start(2, LRT.config(size = RoadSize.QUICK, seed = LRT.seedFor(1)))
        val ready = s.copy(players = s.players.map { it.copy(turnsTaken = cap) }).let { LRT.at(it, 0, "LRN.0") }
        // seat 1 (the last of the round) finishing its turn fires last light
        val r = LRT.act(ready.copy(currentSeat = 1).let { LRT.at(it, 1, "LRN.0") }, LifeRoadAction.Spin)
        assertTrue(r.over)
        assertTrue(r.players.all { it.retired && it.lane == RetireLane.LATE })
    }

    @Test fun gameOverIsTerminal() {
        val (end, _) = LRT.playRandom(LRT.start(2, LRT.config(size = RoadSize.QUICK, seed = 8)), Random(8))
        assertTrue(end.over)
        assertEquals(Phase.GAME_OVER, end.phase)
        for (seat in end.players.indices) assertTrue(e.legalActions(end, seat).isEmpty())
        assertEquals(LifeRoadReject.GAME_OVER, e.explain(end, 0, LifeRoadAction.Spin))
    }

    @Test fun everyPhaseHasAtLeastOneLegalAction() {
        val rnd = Random(77)
        repeat(30) { g ->
            var s = e.newGame(LRT.config(size = RoadSize.values()[g % 3], seed = g.toLong(), junior = g % 5 == 0), LRT.roster(2 + g % 5))
            while (!s.over) {
                val legal = e.legalActions(s, s.currentSeat)
                assertTrue("${s.phase}", legal.isNotEmpty())
                s = LRT.act(s, legal[rnd.nextInt(legal.size)])
            }
        }
    }

    @Test fun legalActionsMatchWhatApplyAccepts() {
        val rnd = Random(5)
        var s = e.newGame(LRT.config(seed = 5), LRT.roster(3))
        var n = 0
        val probes = listOf(
            LifeRoadAction.Spin, LifeRoadAction.UseLuckyCoin, LifeRoadAction.KeepSpin, LifeRoadAction.DeclineHouse, LifeRoadAction.DeclineCover,
            LifeRoadAction.TakeLoan, LifeRoadAction.RepayLoan, LifeRoadAction.Done, LifeRoadAction.Acknowledge, LifeRoadAction.DeclineSale,
            LifeRoadAction.PickJob(null), LifeRoadAction.ChooseBranch("LRN.0"), LifeRoadAction.BuyCover(CoverKind.CARE)
        )
        while (!s.over && n++ < 600) {
            val legal = e.legalActions(s, s.currentSeat)
            for (a in probes) assertEquals("$a in ${s.phase}", a in legal, e.apply(s, s.currentSeat, a).accepted)
            s = LRT.act(s, legal[rnd.nextInt(legal.size)])
        }
    }

    @Test fun startForkIsAskedInSeatOrder() {
        var s = e.newGame(LRT.config(), LRT.roster(3), opener = 1)
        val order = ArrayList<Int>()
        while (s.phase == Phase.START_FORK) {
            order += s.currentSeat
            assertEquals(2, e.legalActions(s, s.currentSeat).size)
            s = LRT.act(s, LifeRoadAction.ChooseBranch("WRK.0"))
        }
        assertEquals(listOf(1, 2, 0), order)
        assertEquals(1, s.currentSeat)
        assertEquals(Phase.AWAIT_SPIN, s.phase)
    }
}
