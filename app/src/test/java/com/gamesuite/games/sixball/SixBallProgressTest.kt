package com.gamesuite.games.sixball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SixBallProgressTest {
    @Test fun aSolveKeepsTheFewestTiltsAndTheMostStarsSeparately() {
        val first = SixBallProgress.merge(null, 12, 2)
        assertEquals(SixBallRecord(12, 2), first)
        assertEquals(SixBallRecord(9, 2), SixBallProgress.merge(first, 9, 1))
        assertEquals(SixBallRecord(12, 3), SixBallProgress.merge(first, 15, 3))
        assertTrue(SixBallProgress.isNewBest(null, 20)); assertTrue(SixBallProgress.isNewBest(first, 11))
        assertFalse(SixBallProgress.isNewBest(first, 12))
    }

    @Test fun onlyTheNewestDailiesAreKept() {
        val records = (1..80).associate { SixBallLevels.dailyId(20_000L + it) to SixBallRecord(5, 1) } +
            (1..48).associate { SixBallLevels.campaignId(it) to SixBallRecord(5, 3) }
        val pruned = SixBallProgress.prune(records)
        assertEquals(SixBallProgress.MAX_DAILY_KEPT + 48, pruned.size)
        assertTrue(SixBallLevels.dailyId(20_080) in pruned)
        assertFalse(SixBallLevels.dailyId(20_001) in pruned)
        assertEquals(48 * 3, SixBallProgress.campaignStars(pruned))
    }

    @Test fun theNextUnsolvedLevelIsTheFirstWithoutStars() {
        assertEquals(1, SixBallProgress.nextUnsolved(emptyMap()))
        val r = mapOf("c1" to SixBallRecord(3, 3), "c2" to SixBallRecord(4, 1), "c4" to SixBallRecord(5, 2))
        assertEquals(3, SixBallProgress.nextUnsolved(r))
        val all = (1..SixBallLevels.CAMPAIGN_COUNT).associate { SixBallLevels.campaignId(it) to SixBallRecord(9, 1) }
        assertEquals(SixBallLevels.CAMPAIGN_COUNT, SixBallProgress.nextUnsolved(all))
    }

    @Test fun storedDataThatIsCorruptOrFromNowhereIsDroppedNotTrusted() {
        assertEquals(emptyMap<String, SixBallRecord>(), SixBallProgress.decode(null))
        assertEquals(emptyMap<String, SixBallRecord>(), SixBallProgress.decode("{{{"))
        val decoded = SixBallProgress.decode(
            "{\"c1\":{\"bestMoves\":4,\"stars\":3},\"c99\":{\"bestMoves\":4,\"stars\":3},\"c2\":{\"bestMoves\":4,\"stars\":9}," +
                "\"d20000\":{\"bestMoves\":7,\"stars\":2},\"x\":{\"bestMoves\":1,\"stars\":1},\"c3\":{\"bestMoves\":-1,\"stars\":1}}"
        )
        assertEquals(setOf("c1", "d20000"), decoded.keys)
        assertNull(decoded["c99"])
    }
}
