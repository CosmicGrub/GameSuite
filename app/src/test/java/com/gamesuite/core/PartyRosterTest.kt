package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyRosterTest {

    @Test
    fun `sanitize cleans names, drops blanks and caps at twelve`() {
        val raw = listOf("  Ann   Lee ", "", "   ") + (1..20).map { "P$it" }
        val out = PartyRosterLogic.sanitize(raw)
        assertEquals(PARTY_ROSTER_MAX, out.size)
        assertEquals("Ann Lee", out[0])
        assertEquals("P1", out[1])
    }

    @Test
    fun `add ignores blanks and a full roster`() {
        assertEquals(listOf("Ann"), PartyRosterLogic.add(emptyList(), "  Ann "))
        assertEquals(listOf("Ann"), PartyRosterLogic.add(listOf("Ann"), "   "))
        val full = (1..PARTY_ROSTER_MAX).map { "P$it" }
        assertEquals(full, PartyRosterLogic.add(full, "Extra"))
    }

    @Test
    fun `duplicates are allowed in the list and suffixed for display`() {
        val names = PartyRosterLogic.add(listOf("Ann"), "ann")
        assertEquals(listOf("Ann", "ann"), names)
        assertEquals(listOf("Ann", "ann (2)"), PartyRosterLogic.displayNames(names))
        assertEquals(
            listOf("Bo", "Bo (2)", "Bo (3)"),
            PartyRosterLogic.displayNames(listOf("Bo", "Bo", "Bo"))
        )
    }

    @Test
    fun `display suffix skips a name that already exists`() {
        val shown = PartyRosterLogic.displayNames(listOf("Bo", "Bo (2)", "Bo"))
        assertEquals(3, shown.map { it.lowercase() }.toSet().size)
        assertEquals("Bo", shown[0])
        assertEquals("Bo (2)", shown[1])
    }

    @Test
    fun `remove rename and move behave and ignore bad indexes`() {
        val base = listOf("A", "B", "C")
        assertEquals(listOf("A", "C"), PartyRosterLogic.remove(base, 1))
        assertEquals(base, PartyRosterLogic.remove(base, 5))
        assertEquals(listOf("A", "Zed", "C"), PartyRosterLogic.rename(base, 1, "  Zed "))
        assertEquals(base, PartyRosterLogic.rename(base, 1, "   "))
        assertEquals(listOf("B", "A", "C"), PartyRosterLogic.move(base, 0, 1))
        assertEquals(listOf("C", "A", "B"), PartyRosterLogic.move(base, 2, -5))
        assertEquals(base, PartyRosterLogic.move(base, 0, -1))
    }

    @Test
    fun `summary shows the first three and the rest as a count`() {
        assertEquals("", PartyRosterLogic.summary(emptyList()))
        assertEquals("Ann, Bo", PartyRosterLogic.summary(listOf("Ann", "Bo")))
        assertEquals("Ann, Bo, Cy", PartyRosterLogic.summary(listOf("Ann", "Bo", "Cy")))
        assertEquals("Ann, Bo, Cy +2", PartyRosterLogic.summary(listOf("Ann", "Bo", "Cy", "Di", "Ed")))
    }

    @Test
    fun `effective falls back to Player n when the roster is too small`() {
        assertEquals(listOf("Player 1", "Player 2", "Player 3"), PartyRosterLogic.effective(emptyList(), 3))
        assertEquals(listOf("Player 1", "Player 2"), PartyRosterLogic.effective(listOf("Solo"), 2))
        assertEquals(PARTY_ROSTER_MAX, PartyRosterLogic.effective(emptyList(), 99).size)
        assertEquals(listOf("Jugador 1"), PartyRosterLogic.effective(emptyList(), 0, "Jugador"))
        assertEquals(listOf("Ann", "Bo"), PartyRosterLogic.effective(listOf("Ann", "Bo"), 5))
        assertFalse(PartyRosterLogic.isActive(listOf("Solo")))
        assertTrue(PartyRosterLogic.isActive(listOf("Ann", "Bo")))
    }

    @Test
    fun `encode and decode round trip`() {
        val names = listOf("Ann Lee", "Bo", "Bo")
        assertEquals(names, PartyRosterLogic.decode(PartyRosterLogic.encode(names)))
        assertEquals(emptyList<String>(), PartyRosterLogic.decode(null))
        assertEquals(emptyList<String>(), PartyRosterLogic.decode(""))
    }
}
