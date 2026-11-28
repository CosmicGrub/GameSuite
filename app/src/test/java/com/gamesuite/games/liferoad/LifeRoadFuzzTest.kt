package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LifeRoadFuzzTest {
    @Test fun randomLegalPlayNeverThrows() {
        val rnd = Random(2024)
        repeat(400) { g ->
            val cfg = LRT.config(size = RoadSize.values()[g % 3], junior = g % 7 == 0, seed = g.toLong(), catchUp = g % 2 == 0)
            val (end, _) = LRT.playRandom(LRT.engine.newGame(cfg, LRT.roster(2 + g % 5)), rnd)
            assertTrue(end.over)
        }
    }

    @Test fun invariantsHoldAfterEveryStep() {
        val rnd = Random(9)
        repeat(60) { g ->
            LRT.playRandom(LRT.engine.newGame(LRT.config(seed = 1000L + g), LRT.roster(4)), rnd, check = LRT::checkInvariants)
        }
    }

    @Test fun snapshotRestoreInTheMiddleOfFuzzGames() {
        val rnd = Random(31)
        repeat(40) { g ->
            val roster = LRT.roster(3)
            var s = LRT.engine.newGame(LRT.config(seed = 500L + g, junior = g % 4 == 0), roster)
            var n = 0
            while (!s.over) {
                val legal = LRT.engine.legalActions(s, s.currentSeat)
                s = LRT.act(s, legal[rnd.nextInt(legal.size)])
                if (!s.over && n++ % 7 == 0) {
                    val text = LifeRoadSnapshots.encode(s, SessionExtras(emptyMap(), 0))
                    assertNotNull(text)
                    val back = LifeRoadSnapshots.decode(text!!, roster)
                    assertNotNull("restore failed in ${s.phase}", back)
                    assertEquals(s, back!!.first)
                }
            }
        }
    }
}
