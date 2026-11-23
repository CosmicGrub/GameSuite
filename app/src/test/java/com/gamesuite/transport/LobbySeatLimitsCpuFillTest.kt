package com.gamesuite.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure arithmetic of filling empty lobby seats with computers (wave 5 S4). */
class LobbySeatLimitsCpuFillTest {

    private val president = LobbySeatLimits(3, 6)
    private val soccer = LobbySeatLimits(4, 4)
    private val twoSeat = LobbySeatLimits(2, 2)

    @Test
    fun `computers count toward the minimum but a human guest is always required`() {
        // President needs 3 players: host + 1 guest + 1 computer starts, host + 2 computers does not (no second human).
        assertTrue(president.canStart(guestCount = 1, cpuCount = 1))
        assertFalse(president.canStart(guestCount = 1, cpuCount = 0))
        assertFalse(president.canStart(guestCount = 0, cpuCount = 2))
        assertFalse(president.canStart(guestCount = 0, cpuCount = 5))
    }

    @Test
    fun `the total of guests and computers may not exceed the game's seats`() {
        assertTrue(president.canStart(guestCount = 2, cpuCount = 3)) // 1 + 5 = 6
        assertFalse(president.canStart(guestCount = 2, cpuCount = 4)) // 1 + 6 = 7
        assertFalse(twoSeat.canStart(guestCount = 1, cpuCount = 1))
    }

    @Test
    fun `Toy Soccer starts with two friends and two computers`() {
        assertFalse("one friend alone is not enough", soccer.canStart(1, 0))
        assertFalse(soccer.canStart(1, 1))
        assertTrue(soccer.canStart(1, 2)) // host + 1 guest + 2 computers = 4 seats
        assertTrue(soccer.canStart(2, 1))
        assertTrue(soccer.canStart(3, 0))
        assertFalse(soccer.canStart(1, 3))
    }

    @Test
    fun `canStart without computers is unchanged`() {
        for (g in 0..8) assertEquals("guests=$g", president.canStart(g), president.canStart(g, 0))
        for (g in 0..8) assertEquals("guests=$g", twoSeat.canStart(g), twoSeat.canStart(g, 0))
    }

    @Test
    fun `maxCpus is what still fits beside the guests`() {
        assertEquals(5, president.maxCpus(0))
        assertEquals(3, president.maxCpus(2))
        assertEquals(0, president.maxCpus(5))
        assertEquals(0, president.maxCpus(9))
        assertEquals(0, twoSeat.maxCpus(1))
    }

    @Test
    fun `seatsStillNeeded counts computers as filled and never goes below zero`() {
        assertEquals(2, president.seatsStillNeeded(0, 0))
        assertEquals(1, president.seatsStillNeeded(1, 0))
        assertEquals(0, president.seatsStillNeeded(1, 1))
        assertEquals(0, president.seatsStillNeeded(3, 2))
        assertEquals(2, soccer.seatsStillNeeded(1, 0))
        assertEquals(0, soccer.seatsStillNeeded(1, 2))
    }

    @Test
    fun `computer seat ids are recognised in any casing and never accepted as a guest id`() {
        assertTrue(isCpuPlayerId("cpu-1"))
        assertTrue(isCpuPlayerId("CPU-7"))
        assertFalse(isCpuPlayerId("p-123"))
        assertFalse(isCpuPlayerId("host"))
        assertEquals("cpu-3", cpuPlayerId(3))
    }

    @Test
    fun `cpuSeatsFor builds labelled bot seats after the humans`() {
        val seats = cpuSeatsFor(2) { "Computer $it" }
        assertEquals(listOf("cpu-1", "cpu-2"), seats.map { it.playerId })
        assertEquals(listOf("Computer 1", "Computer 2"), seats.map { it.displayName })
        assertTrue(seats.all { it.isBot })
        assertTrue(cpuSeatsFor(0) { "x" }.isEmpty())
    }
}
