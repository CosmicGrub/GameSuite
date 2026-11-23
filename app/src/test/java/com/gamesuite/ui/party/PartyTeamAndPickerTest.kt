package com.gamesuite.ui.party

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyTeamAndPickerTest {

    private fun names(n: Int) = (1..n).map { "P$it" }

    @Test
    fun `split keeps everyone exactly once and sizes differ by at most one`() {
        for (n in 2..12) for (k in 2..4) {
            val teams = PartyTeamSplit.split(names(n), k, Random(n * 31 + k))
            assertEquals(minOf(k, n), teams.size)
            assertEquals(names(n).toSet(), teams.flatten().toSet())
            assertEquals(n, teams.flatten().size)
            assertTrue("n=$n k=$k", PartyTeamSplit.sizeSpread(teams) <= 1)
        }
    }

    @Test
    fun `split is deterministic for a seed and varies across seeds`() {
        val a = PartyTeamSplit.split(names(8), 2, Random(1))
        assertEquals(a, PartyTeamSplit.split(names(8), 2, Random(1)))
        val different = (2..40).any { PartyTeamSplit.split(names(8), 2, Random(it)) != a }
        assertTrue(different)
    }

    @Test
    fun `split clamps team count and handles empty and tiny rosters`() {
        assertTrue(PartyTeamSplit.split(emptyList(), 3).isEmpty())
        assertEquals(2, PartyTeamSplit.split(names(5), 1, Random(0)).size)
        assertEquals(4, PartyTeamSplit.split(names(9), 9, Random(0)).size)
        assertEquals(2, PartyTeamSplit.split(names(2), 4, Random(0)).size)
        assertEquals(2, PartyTeamSplit.maxTeamsFor(2))
        assertEquals(4, PartyTeamSplit.maxTeamsFor(12))
    }

    @Test
    fun `team text lists a labelled line per team`() {
        val text = PartyTeamSplit.asText(listOf(listOf("Ann", "Bo"), listOf("Cy")))
        assertEquals("Team A: Ann, Bo\nTeam B: Cy", text)
        assertEquals("Equipo A: Ann", PartyTeamSplit.asText(listOf(listOf("Ann")), "Equipo"))
    }

    @Test
    fun `pickIndex is in range and empty gives null`() {
        assertEquals(null, PartyPicker.pickIndex(emptyList(), Random(0)))
        val r = Random(7)
        repeat(200) {
            val i = PartyPicker.pickIndex(names(5), r)!!
            assertTrue(i in 0..4)
        }
    }

    @Test
    fun `pickIndex reaches every name over many draws`() {
        val r = Random(42)
        val seen = HashSet<Int>()
        repeat(500) { seen.add(PartyPicker.pickIndex(names(6), r)!!) }
        assertEquals(6, seen.size)
    }

    @Test
    fun `flash sequence ends on the winner and stays in range`() {
        for (size in 1..12) for (winner in 0 until size) {
            val seq = PartyPicker.flashSequence(size, winner, 18, Random(size * 13 + winner))
            assertEquals(18, seq.size)
            assertEquals(winner, seq.last())
            assertTrue(seq.all { it in 0 until size })
            if (size > 1) assertTrue(seq[seq.size - 2] != winner)
        }
        assertTrue(PartyPicker.flashSequence(0, 0, 5).isEmpty())
        assertTrue(PartyPicker.flashSequence(3, 7, 5).isEmpty())
        assertEquals(listOf(2), PartyPicker.flashSequence(3, 2, 1, Random(0)))
    }

    @Test
    fun `delay grows toward the end`() {
        val first = PartyPicker.delayMs(0, 20)
        val last = PartyPicker.delayMs(19, 20)
        assertTrue(last > first)
    }

    @Test
    fun `history is newest first and capped`() {
        var h = emptyList<String>()
        for (i in 1..12) h = PartyPicker.pushHistory(h, "P$i")
        assertEquals(PartyPicker.HISTORY_LIMIT, h.size)
        assertEquals("P12", h.first())
    }
}
