package com.gamesuite.core

import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.OnlinePlayer
import com.gamesuite.transport.SeatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Online host lobby's decisions: seating, computers, ready check, removal, Start gating (wave 5 S4). */
class OnlineHostLobbyStateTest {

    private val president = LobbySeatLimits(3, 6)
    private fun g(n: Int) = OnlinePlayer("g$n", "Guest $n")
    private fun state(limits: LobbySeatLimits = president, at: Long = 0L) = OnlineHostLobbyState(limits, changedAtMs = at)
    private fun name(n: Int) = "Computer $n"

    @Test
    fun `an empty table shows the host and empty seats, exactly the games seat count`() {
        val views = state().seatViews("h", "Host", emptyList(), ::name)
        assertEquals(6, views.size)
        assertEquals(SeatKind.HOST, views[0].kind)
        assertTrue(views.drop(1).all { it.kind == SeatKind.EMPTY })
    }

    @Test
    fun `humans then computers then empty seats`() {
        val s = state().withCpuAdded(1).withCpuAdded(1)
        val views = s.seatViews("h", "Host", listOf(g(1)), ::name)
        assertEquals(
            listOf(SeatKind.HOST, SeatKind.HUMAN, SeatKind.CPU, SeatKind.CPU, SeatKind.EMPTY, SeatKind.EMPTY),
            views.map { it.kind }
        )
        assertEquals(listOf("cpu-1", "cpu-2"), views.filter { it.kind == SeatKind.CPU }.map { it.playerId })
        assertTrue(views.first { it.kind == SeatKind.CPU }.ready)
    }

    @Test
    fun `a friend who joins a table full of computers takes a computers seat`() {
        val s = state().withAllFilled(0) // 5 computers
        assertEquals(5, s.cpus(0))
        assertEquals(4, s.cpus(1)) // one human joined: 1 + 1 + 4 = 6
        assertEquals(0, s.cpus(5))
        assertEquals(6, s.seatViews("h", "H", listOf(g(1)), ::name).size)
    }

    @Test
    fun `adding and removing computers stays within what fits`() {
        var s = state()
        repeat(10) { s = s.withCpuAdded(2) }
        assertEquals(3, s.cpus(2))
        repeat(10) { s = s.withCpuRemoved(2) }
        assertEquals(0, s.cpus(2))
        assertEquals(0, state().withAllFilled(0).withNoCpus().cpus(0))
    }

    @Test
    fun `canStart needs a human guest and enough seats counting computers`() {
        assertFalse(state().withAllFilled(0).canStart(0))
        assertTrue(state().withCpuAdded(1).canStart(1))
        assertFalse(state().canStart(1)) // 2 of the 3 President needs
    }

    @Test
    fun `ready is only believed for a seated guest and only that guest`() {
        val seated = listOf(g(1), g(2))
        var s = state().withReady("stranger", true, seated)
        assertTrue(s.ready.isEmpty())
        s = s.withReady("g1", true, seated)
        assertEquals(setOf("g1"), s.ready)
        assertFalse(s.allReady(seated))
        s = s.withReady("g2", true, seated)
        assertTrue(s.allReady(seated))
        s = s.withReady("g1", false, seated)
        assertEquals(1, s.notReadyCount(seated))
    }

    @Test
    fun `start waits for everyone to be ready, then unlocks after the delay`() {
        val seated = listOf(g(1), g(2))
        val s = state(at = 1_000L).observe(seated, 1_000L)
        assertFalse("nobody ready yet", s.canStartNow(seated, 1_000L))
        assertFalse(s.canStartNow(seated, 1_000L + OnlineHostLobbyState.START_ANYWAY_AFTER_MS - 1))
        assertTrue("start anyway after the delay", s.canStartNow(seated, 1_000L + OnlineHostLobbyState.START_ANYWAY_AFTER_MS))
        val ready = s.withReady("g1", true, seated).withReady("g2", true, seated)
        assertTrue("everyone ready starts at once", ready.canStartNow(seated, 1_000L))
    }

    @Test
    fun `a change at the table restarts the delay and drops marks of anyone who left`() {
        val two = listOf(g(1), g(2))
        var s = state(at = 0L).observe(two, 0L).withReady("g1", true, two)
        val three = two + g(3)
        s = s.observe(three, 20_000L)
        assertEquals("the new arrival restarted the timer", 20_000L, s.changedAtMs)
        assertFalse(s.canStartNow(three, 20_000L + OnlineHostLobbyState.START_ANYWAY_AFTER_MS - 1))
        val afterLeave = s.observe(listOf(g(2), g(3)), 25_000L)
        assertFalse("g1 left, its ready mark is gone", "g1" in afterLeave.ready)
        // Observing the same table again is a no-op (the timer does not creep forward).
        assertEquals(afterLeave, afterLeave.observe(listOf(g(2), g(3)), 99_000L))
    }

    @Test
    fun `a removed guest is dropped from the table for good and loses its ready mark`() {
        val s = state().withReady("g2", true, listOf(g(1), g(2))).withKicked("g2")
        val seating = s.seating(listOf(g(1), g(2), g(3)))
        assertEquals(listOf("g1", "g3"), seating.seated.map { it.playerId })
        assertTrue("g2" !in s.ready)
        // Still out later, even though the relay still lists them until they leave.
        assertEquals(listOf("g1", "g3"), s.seating(listOf(g(1), g(2), g(3))).seated.map { it.playerId })
    }

    @Test
    fun `a guest claiming a computer id is never seated`() {
        val impostor = OnlinePlayer("cpu-1", "Not a computer")
        val seating = state().withCpuAdded(1).seating(listOf(g(1), impostor))
        assertEquals(listOf("g1"), seating.seated.map { it.playerId })
        assertEquals(listOf("cpu-1"), seating.overflow.map { it.playerId })
    }

    @Test
    fun `humans beyond the games seats are overflow, not seated`() {
        val two = LobbySeatLimits(2, 2)
        val seating = state(two).seating(listOf(g(1), g(2)))
        assertEquals(1, seating.seated.size)
        assertEquals(1, seating.overflow.size)
        assertEquals(2, state(two).seatViews("h", "H", seating.seated, ::name).size)
    }
}
