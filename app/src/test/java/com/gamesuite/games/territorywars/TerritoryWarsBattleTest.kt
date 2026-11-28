package com.gamesuite.games.territorywars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerritoryWarsBattleTest {
    private val resolver = StandardBattleResolver()

    private fun enumerate(a: Int, d: Int): Map<Pair<Int, Int>, Int> {
        val counts = HashMap<Pair<Int, Int>, Int>()
        val total = a + d
        var combos = 1
        repeat(total) { combos *= 6 }
        for (code in 0 until combos) {
            var c = code
            val faces = IntArray(total) { val f = c % 6 + 1; c /= 6; f }
            val out = resolver.resolve(a, d, ScriptedDice(*faces))
            val key = out.attackerLosses to out.defenderLosses
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts
    }

    @Test fun threeDiceVsTwoDiceMatchesExactProbabilities() {
        val c = enumerate(3, 2)
        assertEquals(2890, c[0 to 2]); assertEquals(2611, c[1 to 1]); assertEquals(2275, c[2 to 0])
    }

    @Test fun twoDiceVsTwoDiceMatchesExactProbabilities() {
        val c = enumerate(2, 2)
        assertEquals(295, c[0 to 2]); assertEquals(420, c[1 to 1]); assertEquals(581, c[2 to 0])
    }

    @Test fun oneDieVsTwoDiceMatchesExactProbabilities() {
        val c = enumerate(1, 2)
        assertEquals(55, c[0 to 1]); assertEquals(161, c[1 to 0])
    }

    @Test fun threeDiceVsOneDieMatchesExactProbabilities() {
        val c = enumerate(3, 1)
        assertEquals(855, c[0 to 1]); assertEquals(441, c[1 to 0])
    }

    @Test fun tieGoesToTheDefender() {
        val o = resolver.resolve(1, 1, ScriptedDice(4, 4))
        assertEquals(1, o.attackerLosses); assertEquals(0, o.defenderLosses)
    }

    @Test fun highestDiceAreComparedFirstThenSecondHighest() {
        val o = resolver.resolve(2, 2, ScriptedDice(6, 2, 5, 3))
        assertEquals(listOf(6, 2), o.attackerRolls)
        assertEquals(listOf(5, 3), o.defenderRolls)
        assertEquals(1, o.attackerLosses); assertEquals(1, o.defenderLosses)
    }

    @Test fun dicePairsEqualMinOfBothSidesAndLossesSumToThePairs() {
        for ((a, d) in listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2, 3 to 1, 3 to 2)) {
            val o = resolver.resolve(a, d, SeededDice(a * 10L + d))
            assertEquals(minOf(a, d), o.attackerLosses + o.defenderLosses)
        }
    }

    @Test fun diceAreDrawnAttackerFirstThenDefender() {
        val o = resolver.resolve(2, 1, ScriptedDice(1, 6, 3))
        assertEquals(listOf(6, 1), o.attackerRolls)
        assertEquals(listOf(3), o.defenderRolls)
        assertEquals(0, o.attackerLosses); assertEquals(1, o.defenderLosses)
    }

    @Test fun seededDiceReplayTheSameSequence() {
        val a = SeededDice(9); val b = SeededDice(9)
        assertEquals(List(50) { a.nextDie() }, List(50) { b.nextDie() })
        assertEquals(50, a.rolled)
        assertTrue(List(2000) { SeededDice(3).also { }.nextDie() }.all { it in 1..6 })
    }

    @Test fun diceAreFairOverOneMillionRolls() {
        val d = SeededDice(17)
        val counts = IntArray(7)
        repeat(1_000_000) { counts[d.nextDie()]++ }
        for (f in 1..6) assertTrue("face $f ${counts[f]}", Math.abs(counts[f] / 1e6 - 1.0 / 6) < 0.003)
    }

    @Test fun badDiceCountsAreRefused() {
        var failed = 0
        for ((a, d) in listOf(0 to 1, 4 to 1, 1 to 0, 1 to 3)) try { resolver.resolve(a, d, SeededDice(1)) } catch (_: IllegalArgumentException) { failed++ }
        assertEquals(4, failed)
    }
}
