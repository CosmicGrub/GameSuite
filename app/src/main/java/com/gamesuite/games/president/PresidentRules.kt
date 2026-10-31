package com.gamesuite.games.president

import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import kotlinx.serialization.Serializable

/** The 52 cards by id (see [Card.id]: `suit.ordinal * 13 + rank.ordinal`), built once. */
object PsDeck {
    val cards: List<Card> = Deck.fresh52()

    const val SIZE = 52

    /** The three of clubs, who leads the first round of a session. */
    val THREE_OF_CLUBS: Int = cards.first { it.suit == Suit.CLUBS && it.rank == Rank.THREE }.id

    fun card(id: Int): Card = cards[id]
}

/** Options chosen in the Hub. Carried in the state so a guest and a restored save play by the same rules. */
@Serializable
data class PresidentRuleSet(
    /** A four of a kind reverses the order of the ranks until another one is played (or the round ends). */
    val revolutions: Boolean = true,
    /** Playing the same rank as the last play is allowed and skips the next player. */
    val ties: Boolean = true,
    /** Between rounds the Scum and the President (and the Vice-Scum and Vice) swap cards. */
    val exchange: Boolean = true,
    /** Rounds in a session: 3, 5 or 10. */
    val rounds: Int = 5
) {
    companion object {
        val ROUND_CHOICES = listOf(3, 5, 10)
    }
}

enum class PresidentRole { PRESIDENT, VICE_PRESIDENT, NEUTRAL, VICE_SCUM, SCUM }

/** Why a play (or pass, or gift) was refused: the screen words each one. */
enum class PresidentReject {
    NONE, NOT_STARTED, NOT_YOUR_TURN, WRONG_PHASE, NOTHING_SELECTED, NOT_IN_HAND, MIXED_RANKS,
    WRONG_COUNT, TOO_LOW, TIE_NOT_ALLOWED, MUST_LEAD_THREE_OF_CLUBS, CANNOT_PASS_LEAD, WRONG_GIFT_COUNT, SPECTATOR
}

/** Pure rules of President: ranks, legality, roles, exchange pairs and scoring. No state, no randomness. */
object PresidentRules {
    const val TOP_POWER = 12
    const val MIN_SEATS = 3
    const val MAX_SEATS = 6

    /** 3 is the lowest (0) up to King (10), Ace (11) and the 2 (12), the highest card. */
    fun basePower(rank: Rank): Int = if (rank == Rank.TWO) 12 else rank.value - 3

    /** The strength of [rank] under the order in force: a revolution turns the order upside down. */
    fun power(rank: Rank, revolution: Boolean): Int = if (revolution) 12 - basePower(rank) else basePower(rank)

    fun power(cardId: Int, revolution: Boolean): Int = power(PsDeck.card(cardId).rank, revolution)

    fun rankOf(cardId: Int): Rank = PsDeck.card(cardId).rank

    fun validSeatCount(n: Int) = n in MIN_SEATS..MAX_SEATS

    /** Hand order: weakest first (3 ... A, 2), then by id so equal ranks are stable. */
    fun sortHand(ids: List<Int>): List<Int> =
        ids.sortedWith(compareBy({ basePower(rankOf(it)) }, { it }))

    /**
     * Whether [ids] may be played from [hand] onto [top] (the cards of the play to beat, or null when
     * leading). [mustInclude] is the card the very first play of a session has to contain.
     */
    fun explainPlay(
        ids: List<Int>,
        hand: List<Int>,
        top: List<Int>?,
        revolution: Boolean,
        ties: Boolean,
        mustInclude: Int?
    ): PresidentReject {
        if (ids.isEmpty()) return PresidentReject.NOTHING_SELECTED
        if (ids.toSet().size != ids.size || ids.any { it !in hand }) return PresidentReject.NOT_IN_HAND
        val rank = rankOf(ids[0])
        if (ids.any { rankOf(it) != rank }) return PresidentReject.MIXED_RANKS
        if (mustInclude != null && top == null && mustInclude !in ids) return PresidentReject.MUST_LEAD_THREE_OF_CLUBS
        if (top == null) return PresidentReject.NONE
        if (ids.size != top.size) return PresidentReject.WRONG_COUNT
        val mine = power(rank, revolution)
        val theirs = power(top[0], revolution)
        return when {
            mine > theirs -> PresidentReject.NONE
            mine == theirs -> if (ties) PresidentReject.NONE else PresidentReject.TIE_NOT_ALLOWED
            else -> PresidentReject.TOO_LOW
        }
    }

    /**
     * Every distinct legal play of [hand], one per (rank, count): the cards of a rank are interchangeable
     * for the rules, so a canonical subset stands for all of them (the one holding [mustInclude] when the
     * rank has it). The result is what the CPU chooses from and what "can I play at all?" asks.
     */
    fun candidatePlays(hand: List<Int>, top: List<Int>?, revolution: Boolean, ties: Boolean, mustInclude: Int?): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        for ((_, group) in hand.groupBy { rankOf(it) }) {
            val ordered = group.sorted().let { g ->
                if (mustInclude != null && mustInclude in g) listOf(mustInclude) + g.filter { it != mustInclude } else g
            }
            for (count in 1..ordered.size) {
                if (top != null && count != top.size) continue
                val play = ordered.take(count)
                if (explainPlay(play, hand, top, revolution, ties, mustInclude) == PresidentReject.NONE) out += play
            }
        }
        return out
    }

    /** A play of the strongest rank under the current order ends the trick at once. */
    fun isTrickKiller(cards: List<Int>, revolution: Boolean): Boolean =
        cards.isNotEmpty() && power(cards[0], revolution) == TOP_POWER

    fun isRevolutionPlay(cards: List<Int>): Boolean = cards.size == 4

    /** The title for finishing in place [position] (0 = first out) at a table of [seats]. */
    fun roleFor(position: Int, seats: Int): PresidentRole = when {
        position == 0 -> PresidentRole.PRESIDENT
        position == seats - 1 -> PresidentRole.SCUM
        seats >= 4 && position == 1 -> PresidentRole.VICE_PRESIDENT
        seats >= 4 && position == seats - 2 -> PresidentRole.VICE_SCUM
        else -> PresidentRole.NEUTRAL
    }

    /** Round points: the President takes seats-1, the Scum 0. */
    fun pointsFor(position: Int, seats: Int): Int = (seats - 1 - position).coerceAtLeast(0)

    /** One swap of the exchange: [high] receives [count] best cards of [low] and hands back [count] of its choice. */
    data class ExchangePair(val high: Int, val low: Int, val count: Int)

    /**
     * Who swaps with whom after a round that ended in [finishOrder] (seats, best first): the President
     * and the Scum swap two cards (one at a table of three), the Vice and the Vice-Scum one.
     */
    fun exchangePairs(finishOrder: List<Int>): List<ExchangePair> {
        val n = finishOrder.size
        if (!validSeatCount(n)) return emptyList()
        val out = ArrayList<ExchangePair>()
        out += ExchangePair(finishOrder[0], finishOrder[n - 1], if (n >= 4) 2 else 1)
        if (n >= 4) out += ExchangePair(finishOrder[1], finishOrder[n - 2], 1)
        return out
    }

    /** Light copies of cards, strongest first by base power then id: what a Scum hands over. */
    fun bestCards(hand: List<Int>, count: Int): List<Int> =
        hand.sortedWith(compareBy({ -basePower(rankOf(it)) }, { -it })).take(count)

    fun cardValueLabel(id: Int): String = PsDeck.card(id).label
}
