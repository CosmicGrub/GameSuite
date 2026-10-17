package com.gamesuite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotHistoryTest {

    @Test
    fun `pop returns snapshots most recent first and depth tracks the stack`() {
        val h = SnapshotHistory()
        assertTrue(h.isEmpty)
        h.push("a")
        h.push("b")
        h.push("c")
        assertEquals(3, h.depth)
        assertEquals("c", h.pop())
        assertEquals("b", h.pop())
        assertEquals(1, h.depth)
        assertEquals("a", h.pop())
        assertTrue(h.isEmpty)
    }

    @Test
    fun `pop on an empty history is null and leaves depth at zero`() {
        val h = SnapshotHistory()
        assertNull(h.pop())
        assertEquals(0, h.depth)
    }

    @Test
    fun `an identical consecutive snapshot is not stacked twice`() {
        val h = SnapshotHistory()
        h.push("same")
        h.push("same")
        assertEquals(1, h.depth)
        h.push("other")
        h.push("same") // not consecutive with the earlier "same", so it counts
        assertEquals(3, h.depth)
    }

    @Test
    fun `the oldest snapshots fall off once the limit is exceeded`() {
        val h = SnapshotHistory(limit = 3)
        for (i in 1..5) h.push("s$i")
        assertEquals(3, h.depth)
        assertEquals("s5", h.pop())
        assertEquals("s4", h.pop())
        assertEquals("s3", h.pop())
        assertNull(h.pop())
    }

    @Test
    fun `clear empties the history`() {
        val h = SnapshotHistory()
        h.push("a")
        h.push("b")
        h.clear()
        assertTrue(h.isEmpty)
        assertEquals(0, h.depth)
        assertNull(h.pop())
    }
}
