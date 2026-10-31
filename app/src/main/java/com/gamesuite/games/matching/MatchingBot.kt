package com.gamesuite.games.matching

import kotlin.random.Random

/** Everything the computer's choice depends on, copied so the choice can be made off the main thread. */
class MatchingBotInput(
    val status: IntArray,
    /** Indices currently face up and unresolved (0 or 1 when the computer is choosing). */
    val flipped: List<Int>,
    /** The face of the single [flipped] card (the computer sees what it just turned), or -1. */
    val flippedFace: Int,
    /** What the computer remembers: card index to the face it saw there. */
    val memory: Map<Int, Int>
)

/**
 * The computer's memory model and choice for Matching. It remembers cards it saw turned up (per
 * [MatchingRules.BotProfile]), goes for a pair it knows, and otherwise explores a card it has no
 * memory of. Easy forgets often and is sloppy; Hard remembers every card it has ever seen.
 */
object MatchingBot {
    /** The next card to flip, or null when none is face down. Pure: only [rnd] is consumed. */
    fun choose(input: MatchingBotInput, profile: MatchingRules.BotProfile, rnd: Random): Int? {
        val down = input.status.indices.filter { input.status[it] == MatchingStatus.DOWN }
        if (down.isEmpty()) return null
        val downSet = down.toSet()
        val known = input.memory.filter { (index, _) -> index in downSet }

        if (input.flipped.isEmpty()) {
            // A pair it knows: two remembered face-down cards with the same face.
            val pair = known.entries.groupBy { it.value }.values.firstOrNull { it.size >= 2 }
            if (pair != null && rnd.nextDouble() < profile.useKnownPair) return pair[rnd.nextInt(2)].key
            return explore(down, known.keys, exclude = -1, sloppiness = profile.sloppiness, rnd = rnd)
        }
        val first = input.flipped[0]
        val partner = known.entries.firstOrNull { it.key != first && it.value == input.flippedFace }
        if (partner != null && rnd.nextDouble() < profile.useKnownPair) return partner.key
        return explore(down, known.keys, exclude = first, sloppiness = profile.sloppiness, rnd = rnd)
    }

    private fun explore(down: List<Int>, known: Set<Int>, exclude: Int, sloppiness: Double, rnd: Random): Int? {
        val open = down.filter { it != exclude }
        if (open.isEmpty()) return null
        val unknown = open.filter { it !in known }
        val sloppy = rnd.nextDouble() < sloppiness
        val pool = if (unknown.isEmpty() || sloppy) open else unknown
        return pool[rnd.nextInt(pool.size)]
    }
}
