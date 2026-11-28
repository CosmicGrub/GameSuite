package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadHandicapTest {
    private fun table(head: HeadStart, seats: Set<Int>) = LRT.start(3, LRT.config(head = head, seats = seats))

    @Test fun smallHeadStartGivesOneLuckyCoin() {
        val s = table(HeadStart.SMALL, setOf(1))
        assertEquals(listOf(0, 1, 0), s.players.map { it.luckyCoins })
        assertEquals(listOf(100, 100, 100), s.players.map { it.cash })
    }

    @Test fun bigHeadStartGivesTwoLuckyCoinsAndFiftyCoins() {
        val s = table(HeadStart.BIG, setOf(0, 2))
        assertEquals(listOf(2, 0, 2), s.players.map { it.luckyCoins })
        assertEquals(listOf(150, 100, 150), s.players.map { it.cash })
    }

    @Test fun headStartSeatParsingHandlesAllNoneAndNumbers() {
        assertEquals(setOf(0, 1, 2), LifeRoadHandicap.parseSeats("all", 3))
        assertEquals(emptySet<Int>(), LifeRoadHandicap.parseSeats("none", 3))
        assertEquals(emptySet<Int>(), LifeRoadHandicap.parseSeats(null, 3))
        assertEquals(setOf(1), LifeRoadHandicap.parseSeats("2", 3))
    }

    @Test fun headStartNeverAppliesToOutOfRangeSeats() {
        assertEquals(emptySet<Int>(), LifeRoadHandicap.parseSeats("6", 3))
        assertTrue(table(HeadStart.BIG, setOf(5)).players.all { it.luckyCoins == 0 })
    }
}
