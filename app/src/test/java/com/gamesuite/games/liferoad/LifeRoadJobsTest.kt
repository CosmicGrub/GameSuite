package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadJobsTest {
    private fun table(vararg spins: Int, work: Set<Int> = emptySet()) =
        LRT.start(2, LRT.config(seed = LRT.seedFor(*spins)), work = work)

    @Test fun firstJobOffersTwoStarterJobs() {
        val s = table(1, work = setOf(0))
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals("WRK.0", r.players[0].nodeId)
        assertEquals(Phase.AWAIT_JOB, r.phase)
        assertTrue(r.pending.mustPick)
        assertEquals(listOf(1, 2), r.pending.offerIds.map { LifeRoadIndex.job.getValue(it).tier })
        assertEquals(LifeRoadReject.BAD_CHOICE, LRT.engine.explain(r, 0, LifeRoadAction.PickJob(null)))
        val pick = LRT.act(r, LifeRoadAction.PickJob(r.pending.offerIds[1]))
        assertEquals(r.pending.offerIds[1], pick.players[0].jobId)
        assertEquals(1, pick.currentSeat)
        // the unchosen offer went back to the bottom of its pile
        assertEquals(r.pending.offerIds[0], pick.decks.jobs[0].last())
    }

    @Test fun graduationOffersThreeDegreeJobs() {
        val s = LRT.at(table(1), 0, "LRN.6")
        val r = LRT.act(s, LifeRoadAction.Spin)
        assertEquals("LRN.7", r.players[0].nodeId)
        assertTrue(r.players[0].degree)
        assertEquals(s.players[0].cash - LifeRoadRules.TUITION, r.players[0].cash)
        assertEquals(3, r.pending.offerIds.size)
        assertTrue(r.pending.offerIds.all { LifeRoadIndex.job.getValue(it).tier == 4 })
    }

    @Test fun jobFairRespectsTheDegreeCap() {
        val s = LRT.edit(table(1), 0) { it.copy(jobId = "beekeeper", nodeId = "TA.3") }
        val r = LRT.act(s, LifeRoadAction.Spin) // TA.4 is the job fair
        assertEquals(Phase.AWAIT_JOB, r.phase)
        assertFalse(r.pending.mustPick)
        assertTrue(r.pending.offerIds.all { LifeRoadIndex.job.getValue(it).tier == 3 })
        val keep = LRT.act(r, LifeRoadAction.PickJob(null))
        assertEquals("beekeeper", keep.players[0].jobId)
        val withDegree = LRT.edit(s, 0) { it.copy(degree = true) }
        val r2 = LRT.act(withDegree, LifeRoadAction.Spin)
        assertEquals(listOf(3, 4), r2.pending.offerIds.map { LifeRoadIndex.job.getValue(it).tier })
    }

    @Test fun unchosenOffersReturnToTheDeck() {
        val s = table(1, work = setOf(0))
        val before = s.decks.jobs.sumOf { it.size }
        val offered = LRT.act(s, LifeRoadAction.Spin)
        val r = LRT.act(offered, LifeRoadAction.PickJob(offered.pending.offerIds[0]))
        val ids = r.decks.jobs.flatten() + r.players.mapNotNull { it.jobId }
        assertEquals(before, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun raiseIsCappedAtThree() {
        // RISK.4? use the Work Road raise square (WRK.3 in Standard "A.$ER.") from WRK.2
        var s = LRT.edit(table(1), 0) { it.copy(nodeId = "WRK.3", raises = 2) }
        s = LRT.act(s, LifeRoadAction.Spin)
        assertEquals(3, s.players[0].raises)
        var t = LRT.edit(table(1), 0) { it.copy(nodeId = "WRK.3", raises = 3) }
        t = LRT.act(t, LifeRoadAction.Spin)
        assertEquals(3, t.players[0].raises)
    }

    private fun payOf(player: (PlayerState) -> PlayerState, spins: IntArray = intArrayOf(2)): Pair<Int, Int> {
        val s = LRT.edit(table(*spins), 0, player).let { LRT.at(it, 0, "TA.6") }
        val r = LRT.act(s, LifeRoadAction.Spin)
        return s.players[0].cash to r.players[0].cash
    }

    @Test fun payStopPaysSalaryPlusRaise() {
        val (a, b) = payOf({ it.copy(jobId = "carpenter", raises = 2) })
        assertEquals(36 + 20 + 10, b - a)
    }

    @Test fun tipsJobsAddTwoPerSpinPoint() {
        val seed = LRT.seedFor(2)
        val s = LRT.at(LRT.edit(LRT.start(2, LRT.config(seed = seed)), 0) { it.copy(jobId = "chef") }, 0, "TA.6")
        val r = LRT.act(s, LifeRoadAction.Spin)
        val tip = LifeRoadRng.spin(seed, 1)
        assertEquals(50 + 2 * tip + 10, r.players[0].cash - s.players[0].cash)
    }

    @Test fun noJobPaysOddJobsAllowance() {
        val (a, b) = payOf({ it })
        assertEquals(10 + 10, b - a)
    }

    @Test fun skipHalveAndDoubleNextPayApplyInOrder() {
        for ((flag, expect) in listOf(NextPay.SKIP to 0, NextPay.HALVE to 18, NextPay.DOUBLE to 72)) {
            val (a, b) = payOf({ it.copy(jobId = "carpenter", nextPay = flag) })
            assertEquals(flag.name, expect + 10, b - a)
        }
        val s = LRT.at(LRT.edit(table(2), 0) { it.copy(nextPay = NextPay.HALVE) }, 0, "TA.6")
        assertEquals(NextPay.NORMAL, LRT.act(s, LifeRoadAction.Spin).players[0].nextPay)
    }

    @Test fun everyJobHasPositiveSalaryInItsTierBand() {
        val bands = mapOf(1 to 20..26, 2 to 30..40, 3 to 45..58, 4 to 70..88, 5 to 90..115)
        for (j in LifeRoadContent.jobs) assertTrue(j.id, j.salary in bands.getValue(j.tier))
    }
}
