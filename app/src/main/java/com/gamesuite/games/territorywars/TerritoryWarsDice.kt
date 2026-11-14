package com.gamesuite.games.territorywars

import kotlin.random.Random

/**
 * The only source of chance in a battle. The engine never calls a Random for dice: it is handed a [DiceSource], so a
 * seeded source replays a war exactly, a scripted source makes tests exact, and a network host can own the stream.
 */
fun interface DiceSource {
    /** The next die, 1..6. */
    fun nextDie(): Int
}

/** Seeded dice; [rolled] counts the dice drawn so a saved game can fast-forward a fresh stream to the same place. */
class SeededDice(seed: Long) : DiceSource {
    private val random = Random(seed)

    var rolled: Int = 0
        private set

    override fun nextDie(): Int {
        rolled++
        return random.nextInt(1, 7)
    }
}

/** Test dice: returns [values] in order, then throws (a test that rolls more than it scripted is wrong). */
class ScriptedDice(vararg values: Int) : DiceSource {
    private val queue: List<Int> = values.toList()
    private var next = 0

    override fun nextDie(): Int {
        check(next < queue.size) { "scripted dice exhausted after ${queue.size} rolls" }
        return queue[next++]
    }
}

/** One battle round. Rolls are sorted high to low; losses are armies removed from each side. */
data class BattleOutcome(
    val attackerRolls: List<Int>,
    val defenderRolls: List<Int>,
    val attackerLosses: Int,
    val defenderLosses: Int
)

/**
 * Resolves one battle round. Rule (design doc, rules 8 to 10): roll [attackerDice] (1..3) and [defenderDice] (1..2),
 * sort each high to low, compare pairwise up to min(attackerDice, defenderDice) pairs; the higher die wins the pair and
 * the loser loses one army; a TIE goes to the DEFENDER. Dice are drawn attacker first, then defender, from [dice].
 */
interface BattleResolver {
    fun resolve(attackerDice: Int, defenderDice: Int, dice: DiceSource): BattleOutcome
}

/** SCAFFOLD stub: rolls nothing and removes nothing (a safe no-op). Replace with the real pairwise comparison. */
class StubBattleResolver : BattleResolver {
    override fun resolve(attackerDice: Int, defenderDice: Int, dice: DiceSource): BattleOutcome =
        BattleOutcome(emptyList(), emptyList(), 0, 0)
}
