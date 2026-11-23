package com.gamesuite.games.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingRecordsTest {
    @Test fun aFirstSolveIsABestInBothColumns() {
        val (rec, r) = MatchingRecords.improve(null, 14, 90_000L)
        assertEquals(MatchingRecord(14, 90_000L), rec)
        assertTrue(r.isNewBestMoves && r.isNewBestTime)
    }

    @Test fun movesAndTimeImproveIndependently() {
        val (rec, r) = MatchingRecords.improve(MatchingRecord(14, 90_000L), 12, 120_000L)
        assertEquals(MatchingRecord(12, 90_000L), rec)
        assertTrue(r.isNewBestMoves); assertFalse(r.isNewBestTime)
        val (rec2, r2) = MatchingRecords.improve(rec, 12, 80_000L) // equal moves is not a new best
        assertEquals(MatchingRecord(12, 80_000L), rec2)
        assertFalse(r2.isNewBestMoves); assertTrue(r2.isNewBestTime)
    }

    @Test fun recordsRoundTripAndABadBlobIsIgnored() {
        val map = mapOf("12" to MatchingRecord(7, 30_000L), "40" to MatchingRecord(null, 500_000L))
        assertEquals(map, MatchingRecords.decode(MatchingRecords.encode(map)))
        assertEquals(emptyMap<String, MatchingRecord>(), MatchingRecords.decode("{broken"))
        assertEquals(emptyMap<String, MatchingRecord>(), MatchingRecords.decode(null))
        assertEquals(emptyMap<String, MatchingRecord>(), MatchingRecords.decode(""))
    }
}
