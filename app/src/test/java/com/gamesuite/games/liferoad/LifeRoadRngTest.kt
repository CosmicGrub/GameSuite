package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadRngTest {
    @Test fun sameSeedProducesSameSpinSequence() {
        assertEquals((0L until 50L).map { LifeRoadRng.spin(7, it) }, (0L until 50L).map { LifeRoadRng.spin(7, it) })
    }

    @Test fun differentSeedsProduceDifferentSequences() {
        assertNotEquals((0L until 20L).map { LifeRoadRng.spin(7, it) }, (0L until 20L).map { LifeRoadRng.spin(8, it) })
    }

    @Test fun spinsAreAlwaysBetweenOneAndTen() {
        for (i in 0L until 100_000L) assertTrue(LifeRoadRng.spin(3, i) in 1..10)
    }

    @Test fun spinDistributionIsRoughlyUniform() {
        val counts = IntArray(11)
        val n = 200_000
        for (i in 0 until n) counts[LifeRoadRng.spin(11, i.toLong())]++
        for (f in 1..10) assertTrue("face $f ${counts[f]}", Math.abs(counts[f] / n.toDouble() - 0.1) < 0.01)
    }

    @Test fun streamsAreIndependent() {
        val a = LifeRoadRng.spin(5, 3)
        LifeRoadRng.below(5, RngStream.DECK, 3, 100); LifeRoadRng.below(5, RngStream.BOT, 3, 100)
        assertEquals(a, LifeRoadRng.spin(5, 3))
        assertNotEquals(LifeRoadRng.raw(5, RngStream.SPIN, 0), LifeRoadRng.raw(5, RngStream.DECK, 0))
    }

    @Test fun restoreFromCursorsContinuesTheSequence() {
        val s = LRT.act(LRT.start(3, LRT.config(seed = 21)), LifeRoadAction.Spin)
        val text = LifeRoadSnapshots.encode(s, SessionExtras(emptyMap(), 0))!!
        val (b, _) = LifeRoadSnapshots.decode(text, LRT.roster(3))!!
        assertEquals(s.cursors, b.cursors)
        // the next spin of the restored table equals the next spin of the original
        val next = LRT.engine.legalActions(s, s.currentSeat).firstOrNull()
        assertTrue(next != null)
        assertEquals(LifeRoadRng.spin(21, s.cursors.spin), LifeRoadRng.spin(21, b.cursors.spin))
    }
}
