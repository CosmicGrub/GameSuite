package com.gamesuite.games.holdem

/** Card id helpers (shared standard-deck numbering, see HoldemModels.kt). */
object HoldemCards {
    const val DECK_SIZE = 52
    const val SPADES = 3
    const val HEARTS = 2
    const val DIAMONDS = 1
    const val CLUBS = 0

    /** 2..14 (ace high). */
    fun rank(card: Int): Int = 2 + card % 13

    /** 0 clubs, 1 diamonds, 2 hearts, 3 spades (the order of games/cards Suit). */
    fun suit(card: Int): Int = card / 13

    fun make(rank: Int, suit: Int): Int = suit * 13 + (rank - 2)

    fun isValid(card: Int): Boolean = card in 0 until DECK_SIZE
}

enum class HandCategory(val strength: Int) {
    HIGH_CARD(0), PAIR(1), TWO_PAIR(2), TRIPS(3), STRAIGHT(4), FLUSH(5), FULL_HOUSE(6), QUADS(7), STRAIGHT_FLUSH(8);

    companion object {
        fun of(score: Int): HandCategory = values()[score shr 20]
    }
}

/**
 * Best-five-of-seven poker hand evaluation. A hand's value is one Int: the category in the top bits and five
 * rank nibbles below it (primary rank first), so comparing two Ints compares two hands, kickers and all, and
 * equal Ints are exact ties. The ace plays high or low in a straight (the "wheel", A-2-3-4-5, is the lowest).
 */
object HandEvaluator {
    private const val CAT_SHIFT = 20

    private fun pack(category: HandCategory, a: Int = 0, b: Int = 0, c: Int = 0, d: Int = 0, e: Int = 0): Int =
        (category.strength shl CAT_SHIFT) or (a shl 16) or (b shl 12) or (c shl 8) or (d shl 4) or e

    /** The high card of the best straight inside a rank bitmask (bit r set for rank r, 2..14), or 0. */
    private fun straightHigh(mask: Int): Int {
        val m = if (mask and (1 shl 14) != 0) mask or 2 else mask // bit 1 = ace playing low
        for (high in 14 downTo 5) {
            if (((m shr (high - 4)) and 31) == 31) return high
        }
        return 0
    }

    /** The top [count] ranks set in [mask], highest first, skipping [skip1] and [skip2]. */
    private fun topRanks(mask: Int, count: Int, skip1: Int = 0, skip2: Int = 0): IntArray {
        val out = IntArray(count)
        var n = 0
        var r = 14
        while (r >= 2 && n < count) {
            if (mask and (1 shl r) != 0 && r != skip1 && r != skip2) out[n++] = r
            r--
        }
        return out
    }

    /** Value of the best five-card hand among [n] (5..7) cards. */
    fun evaluate(cards: IntArray, n: Int = cards.size): Int {
        require(n in 5..7) { "need 5 to 7 cards, got $n" }
        val rankCount = IntArray(15)
        val suitMask = IntArray(4)
        val suitCount = IntArray(4)
        var rankMask = 0
        for (i in 0 until n) {
            val c = cards[i]
            val r = 2 + c % 13
            val s = c / 13
            rankCount[r]++
            suitMask[s] = suitMask[s] or (1 shl r)
            suitCount[s]++
            rankMask = rankMask or (1 shl r)
        }
        var flushSuit = -1
        for (s in 0 until 4) if (suitCount[s] >= 5) flushSuit = s
        if (flushSuit >= 0) {
            val sf = straightHigh(suitMask[flushSuit])
            if (sf > 0) return pack(HandCategory.STRAIGHT_FLUSH, sf)
        }
        var quad = 0
        var trips1 = 0
        var trips2 = 0
        var pair1 = 0
        var pair2 = 0
        var pair3 = 0
        for (r in 14 downTo 2) {
            when (rankCount[r]) {
                4 -> if (quad == 0) quad = r
                3 -> if (trips1 == 0) trips1 = r else if (trips2 == 0) trips2 = r
                2 -> if (pair1 == 0) pair1 = r else if (pair2 == 0) pair2 = r else if (pair3 == 0) pair3 = r
            }
        }
        if (quad > 0) {
            val k = topRanks(rankMask, 1, skip1 = quad)[0]
            return pack(HandCategory.QUADS, quad, k)
        }
        if (trips1 > 0 && (trips2 > 0 || pair1 > 0)) {
            val p = maxOf(trips2, pair1)
            return pack(HandCategory.FULL_HOUSE, trips1, p)
        }
        if (flushSuit >= 0) {
            val t = topRanks(suitMask[flushSuit], 5)
            return pack(HandCategory.FLUSH, t[0], t[1], t[2], t[3], t[4])
        }
        val st = straightHigh(rankMask)
        if (st > 0) return pack(HandCategory.STRAIGHT, st)
        if (trips1 > 0) {
            val k = topRanks(rankMask, 2, skip1 = trips1)
            return pack(HandCategory.TRIPS, trips1, k[0], k[1])
        }
        if (pair2 > 0) {
            val k = topRanks(rankMask, 1, skip1 = pair1, skip2 = pair2)[0]
            return pack(HandCategory.TWO_PAIR, pair1, pair2, k)
        }
        if (pair1 > 0) {
            val k = topRanks(rankMask, 3, skip1 = pair1)
            return pack(HandCategory.PAIR, pair1, k[0], k[1], k[2])
        }
        val t = topRanks(rankMask, 5)
        return pack(HandCategory.HIGH_CARD, t[0], t[1], t[2], t[3], t[4])
    }

    fun evaluate(cards: List<Int>): Int = evaluate(cards.toIntArray())

    fun category(score: Int): HandCategory = HandCategory.of(score)

    /** The rank nibble [index] (0 = primary) of a score. */
    fun rankAt(score: Int, index: Int): Int = (score shr (16 - 4 * index)) and 15

    /** Whether a score is the royal flush (ace-high straight flush). */
    fun isRoyal(score: Int): Boolean = category(score) == HandCategory.STRAIGHT_FLUSH && rankAt(score, 0) == 14

    /**
     * The five cards that make the best hand of [cards] (5..7 of them), in play order for display
     * (best straight flush, then the grouped ranks), found by trying every five-card subset.
     */
    fun bestFive(cards: List<Int>): List<Int> {
        require(cards.size in 5..7)
        if (cards.size == 5) return sortForDisplay(cards)
        var best = -1
        var bestSet: List<Int> = emptyList()
        val n = cards.size
        val buf = IntArray(5)
        for (a in 0 until n - 4) for (b in a + 1 until n - 3) for (c in b + 1 until n - 2)
            for (d in c + 1 until n - 1) for (e in d + 1 until n) {
                buf[0] = cards[a]; buf[1] = cards[b]; buf[2] = cards[c]; buf[3] = cards[d]; buf[4] = cards[e]
                val v = evaluate(buf, 5)
                if (v > best) { best = v; bestSet = listOf(cards[a], cards[b], cards[c], cards[d], cards[e]) }
            }
        return sortForDisplay(bestSet)
    }

    private fun sortForDisplay(five: List<Int>): List<Int> {
        val score = evaluate(five.toIntArray(), 5)
        val cat = category(score)
        if ((cat == HandCategory.STRAIGHT || cat == HandCategory.STRAIGHT_FLUSH) && rankAt(score, 0) == 5) {
            // The wheel reads 5-4-3-2-A: the ace plays low.
            return five.sortedByDescending { if (HoldemCards.rank(it) == 14) 1 else HoldemCards.rank(it) }
        }
        val counts = IntArray(15)
        five.forEach { counts[HoldemCards.rank(it)]++ }
        return five.sortedWith(
            compareByDescending<Int> { counts[HoldemCards.rank(it)] }.thenByDescending { HoldemCards.rank(it) }.thenBy { HoldemCards.suit(it) }
        )
    }
}
