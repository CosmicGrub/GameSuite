package com.gamesuite.games.sevens

import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.games.president.PsDeck
import kotlinx.serialization.Serializable

/** Options chosen in the Hub, carried in the state so a guest and a restored save play by the same rules. */
@Serializable
data class SevensRuleSet(
    /** Every pass (only allowed when nothing can be played) costs [SevensRules.PASS_PENALTY] points. */
    val passPenalty: Boolean = false
)

enum class SevensReject { NONE, NOT_STARTED, NOT_YOUR_TURN, WRONG_PHASE, NOT_IN_HAND, NOT_PLAYABLE, MUST_START_WITH_SEVEN_OF_DIAMONDS, MUST_PLAY_IF_ABLE, SPECTATOR }

/** Pure rules of Sevens (Fan Tan): where a card may go, what a pass is, how a hand scores. */
object SevensRules {
    const val MIN_SEATS = 3
    const val MAX_SEATS = 6
    const val SEVEN = 7
    const val PASS_PENALTY = 5

    fun validSeatCount(n: Int) = n in MIN_SEATS..MAX_SEATS

    /** Ace is 1 and King 13, because a row runs from the ace up to the king. */
    fun rankIndex(rank: Rank): Int = if (rank == Rank.ACE) 1 else rank.value

    fun rankIndex(cardId: Int): Int = rankIndex(PsDeck.card(cardId).rank)

    fun suitIndex(cardId: Int): Int = PsDeck.card(cardId).suit.ordinal

    fun cardId(suit: Int, rankIndex: Int): Int {
        val rank = if (rankIndex == 1) Rank.ACE else Rank.values().first { it.value == rankIndex }
        return suit * 13 + rank.ordinal
    }

    val SEVEN_OF_DIAMONDS: Int = SevensRules.cardId(Suit.DIAMONDS.ordinal, SEVEN)

    /** Points a card counts against its holder when the round ends: A = 1, 2 to 10 face value, J 11, Q 12, K 13. */
    fun points(cardId: Int): Int = rankIndex(cardId)

    /**
     * Whether [cardId] may be played on a table whose rows run from [low] to [high] per suit (0 = that suit
     * has not been started). The round opens with the seven of diamonds; after that any seven starts its
     * suit, and every other card must sit next to the end of its suit's row.
     */
    fun isPlayable(cardId: Int, low: List<Int>, high: List<Int>): Boolean {
        val suit = suitIndex(cardId)
        val r = rankIndex(cardId)
        if (low[Suit.DIAMONDS.ordinal] == 0) return cardId == SEVEN_OF_DIAMONDS
        if (low[suit] == 0) return r == SEVEN
        return r == low[suit] - 1 || r == high[suit] + 1
    }

    fun playableCards(hand: List<Int>, low: List<Int>, high: List<Int>): List<Int> =
        hand.filter { isPlayable(it, low, high) }

    fun explainPlay(cardId: Int, hand: List<Int>, low: List<Int>, high: List<Int>): SevensReject = when {
        cardId !in hand -> SevensReject.NOT_IN_HAND
        isPlayable(cardId, low, high) -> SevensReject.NONE
        low[Suit.DIAMONDS.ordinal] == 0 -> SevensReject.MUST_START_WITH_SEVEN_OF_DIAMONDS
        else -> SevensReject.NOT_PLAYABLE
    }

    /** Hand order for display: by suit, then rank. */
    fun sortHand(ids: List<Int>): List<Int> = ids.sortedWith(compareBy({ suitIndex(it) }, { rankIndex(it) }))

    /** The cards a row of [suit] shows from [low] to [high] (empty when not started). */
    fun rowCards(suit: Int, low: List<Int>, high: List<Int>): List<Int> =
        if (low[suit] == 0) emptyList() else (low[suit]..high[suit]).map { cardId(suit, it) }

    /** The two ids that could extend [suit]'s row, in (lower, higher) order; 0 = nothing there. */
    fun openEnds(suit: Int, low: List<Int>, high: List<Int>): Pair<Int, Int> {
        if (low[suit] == 0) return 0 to 0
        val lo = if (low[suit] > 1) cardId(suit, low[suit] - 1) else 0
        val hi = if (high[suit] < 13) cardId(suit, high[suit] + 1) else 0
        return lo to hi
    }
}
