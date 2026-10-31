package com.gamesuite.ui.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyScoreKeeperTest {

    private fun game(vararg moves: Triple<Int, Int, Boolean>, players: Int = 3): PartyScoreState {
        // Triple(player, delta, advanceRoundAfter)
        var s = PartyScoreKeeper.initial(players)
        for ((p, d, next) in moves) {
            s = PartyScoreKeeper.add(s, p, d)
            if (next) s = PartyScoreKeeper.nextRound(s)
        }
        return s
    }

    @Test
    fun `totals add up and can go negative`() {
        val s = game(Triple(0, 5, false), Triple(0, -8, false), Triple(1, 10, false))
        assertEquals(listOf(-3, 10, 0), PartyScoreKeeper.totals(s))
    }

    @Test
    fun `invalid player or zero delta is ignored`() {
        val s = PartyScoreKeeper.initial(2)
        assertEquals(s, PartyScoreKeeper.add(s, 2, 5))
        assertEquals(s, PartyScoreKeeper.add(s, -1, 5))
        assertEquals(s, PartyScoreKeeper.add(s, 0, 0))
    }

    @Test
    fun `leader is the highest total and ties share it`() {
        var s = PartyScoreKeeper.initial(3)
        assertTrue(PartyScoreKeeper.leaders(s).isEmpty())
        s = PartyScoreKeeper.add(s, 1, 4)
        assertEquals(setOf(1), PartyScoreKeeper.leaders(s))
        s = PartyScoreKeeper.add(s, 2, 4)
        assertEquals(setOf(1, 2), PartyScoreKeeper.leaders(s))
    }

    @Test
    fun `everyone level means no leader`() {
        var s = PartyScoreKeeper.initial(2)
        s = PartyScoreKeeper.add(s, 0, 3)
        s = PartyScoreKeeper.add(s, 1, 3)
        assertTrue(PartyScoreKeeper.leaders(s).isEmpty())
    }

    @Test
    fun `next round needs an entry in the current round`() {
        val s = PartyScoreKeeper.initial(2)
        assertFalse(PartyScoreKeeper.canAdvanceRound(s))
        assertEquals(1, PartyScoreKeeper.nextRound(s).round)
        val s2 = PartyScoreKeeper.nextRound(PartyScoreKeeper.add(s, 0, 1))
        assertEquals(2, s2.round)
        // Round 2 is empty, so another press does nothing.
        assertEquals(2, PartyScoreKeeper.nextRound(s2).round)
    }

    @Test
    fun `round table has a row per round with null for no entry`() {
        val s = game(Triple(0, 5, false), Triple(0, 2, true), Triple(1, 7, false))
        val table = PartyScoreKeeper.roundTable(s)
        assertEquals(2, table.size)
        assertEquals(listOf(7, null, null), table[0])
        assertEquals(listOf(null, 7, null), table[1])
    }

    @Test
    fun `undo removes the last entry and steps the round back when it was earlier`() {
        val s = game(Triple(0, 5, true), Triple(1, 3, false))
        assertEquals(2, s.round)
        val u1 = PartyScoreKeeper.undo(s)
        assertEquals(listOf(5, 0, 0), PartyScoreKeeper.totals(u1))
        assertEquals(2, u1.round)

        // Advance, then undo straight away: back to round 1 where the entry lives.
        val advanced = game(Triple(0, 5, true))
        assertEquals(2, advanced.round)
        val u2 = PartyScoreKeeper.undo(advanced)
        assertEquals(1, u2.round)
        assertTrue(u2.entries.isEmpty())
        assertEquals(u2, PartyScoreKeeper.undo(u2))
    }

    @Test
    fun `target reached flags players at or above it`() {
        var s = PartyScoreKeeper.setTarget(PartyScoreKeeper.initial(2), 10)
        assertTrue(PartyScoreKeeper.targetReached(s).isEmpty())
        s = PartyScoreKeeper.add(s, 0, 10)
        s = PartyScoreKeeper.add(s, 1, 9)
        assertEquals(setOf(0), PartyScoreKeeper.targetReached(s))
        assertTrue(PartyScoreKeeper.targetReached(PartyScoreKeeper.setTarget(s, 0)).isEmpty())
    }

    @Test
    fun `resize drops entries of removed players and reset keeps count and target`() {
        var s = PartyScoreKeeper.setTarget(PartyScoreKeeper.initial(3), 50)
        s = PartyScoreKeeper.add(s, 2, 9)
        s = PartyScoreKeeper.add(s, 0, 1)
        val smaller = PartyScoreKeeper.resize(s, 2)
        assertEquals(listOf(1, 0), PartyScoreKeeper.totals(smaller))
        val r = PartyScoreKeeper.reset(s)
        assertEquals(3, r.playerCount)
        assertEquals(50, r.target)
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.round)
    }

    @Test
    fun `parseAmount accepts plain positive numbers only`() {
        assertEquals(25, PartyScoreKeeper.parseAmount(" 25 "))
        assertNull(PartyScoreKeeper.parseAmount(""))
        assertNull(PartyScoreKeeper.parseAmount("0"))
        assertNull(PartyScoreKeeper.parseAmount("-5"))
        assertNull(PartyScoreKeeper.parseAmount("1.5"))
        assertNull(PartyScoreKeeper.parseAmount("1234567"))
    }

    @Test
    fun `encode and decode round trip and reject garbage`() {
        val s = game(Triple(0, 5, true), Triple(2, -3, false))
            .let { PartyScoreKeeper.setTarget(it, 100) }
        assertEquals(s, PartyScoreKeeper.decode(PartyScoreKeeper.encode(s)))
        val empty = PartyScoreKeeper.initial(4)
        assertEquals(empty, PartyScoreKeeper.decode(PartyScoreKeeper.encode(empty)))
        assertNull(PartyScoreKeeper.decode(null))
        assertNull(PartyScoreKeeper.decode("nonsense"))
        assertNull(PartyScoreKeeper.decode("v1;2;1;0;5:1:1"))
        assertNull(PartyScoreKeeper.decode("v1;2;1;0;0:1:9"))
        assertNull(PartyScoreKeeper.decode("v1;0;1;0;"))
    }

    @Test
    fun `format delta uses a real minus`() {
        assertEquals("+5", partyFormatDelta(5))
        assertEquals("−3", partyFormatDelta(-3))
    }
}
