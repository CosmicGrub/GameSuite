package com.gamesuite.games.bumprun

enum class BumpCardKind { HOP, SWAP, CHASE, FREE_EXIT, AGAIN, SHIELD }

/**
 * One card. [id] is dense (0 until [BumpRunDeck.SIZE]) in composition order (see [BumpRunDeck.COMPOSITION]).
 * [value] is the hop distance for HOP cards (0 otherwise). [exits] is true for the starred cards (Hop 1, Hop 6,
 * Free Exit) that may bring a pawn out of the Nest.
 */
data class BumpCard(val id: Int, val kind: BumpCardKind, val value: Int = 0, val exits: Boolean = false)

/** The deck: 54 cards, one fixed composition for every length. The shuffled order is hidden information. */
object BumpRunDeck {
    const val SIZE = 54

    /** (kind, hop value, copies). Order defines the card ids: Hop 1 = 0..5, Hop 2 = 6..11, ... Shield = 51..53. */
    val COMPOSITION: List<Triple<BumpCardKind, Int, Int>> = listOf(
        Triple(BumpCardKind.HOP, 1, 6),
        Triple(BumpCardKind.HOP, 2, 6),
        Triple(BumpCardKind.HOP, 3, 5),
        Triple(BumpCardKind.HOP, 4, 5),
        Triple(BumpCardKind.HOP, 5, 5),
        Triple(BumpCardKind.HOP, 6, 4),
        Triple(BumpCardKind.HOP, 8, 3),
        Triple(BumpCardKind.HOP, 10, 3),
        Triple(BumpCardKind.SWAP, 0, 4),
        Triple(BumpCardKind.CHASE, 0, 3),
        Triple(BumpCardKind.FREE_EXIT, 0, 4),
        Triple(BumpCardKind.AGAIN, 0, 3),
        Triple(BumpCardKind.SHIELD, 0, 3)
    )

    /** Every card, in id order (index == id). */
    fun cards(): List<BumpCard> {
        val out = ArrayList<BumpCard>(SIZE)
        for ((kind, value, copies) in COMPOSITION) {
            val exits = kind == BumpCardKind.FREE_EXIT || (kind == BumpCardKind.HOP && (value == 1 || value == 6))
            repeat(copies) { out += BumpCard(out.size, kind, value, exits) }
        }
        return out
    }

    fun byId(id: Int): BumpCard? = cards().getOrNull(id)
}
