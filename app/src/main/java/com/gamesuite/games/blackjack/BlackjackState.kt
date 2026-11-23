package com.gamesuite.games.blackjack

import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit

object BjPhase {
    const val BETTING = "betting"
    const val INSURANCE = "insurance"
    const val PLAYING = "playing"
    const val DEALER = "dealer"
    const val ROUND_OVER = "round_over"
    val ALL = setOf(BETTING, INSURANCE, PLAYING, DEALER, ROUND_OVER)
}

/** What the last change to the table was; the screen words, animates and announces it. */
object BjEvent {
    const val START = "start"
    const val BET = "bet"
    const val REBUY = "rebuy"
    const val SIT_OUT = "sit_out"
    const val NO_BETS = "no_bets"
    const val DEAL = "deal"
    const val INSURANCE_TAKEN = "insurance_taken"
    const val INSURANCE_DECLINED = "insurance_declined"
    const val DEALER_BLACKJACK = "dealer_blackjack"
    const val PEEK_CLEAR = "peek_clear"
    const val HIT = "hit"
    const val STAND = "stand"
    const val DOUBLE = "double"
    const val SPLIT = "split"
    const val SURRENDER = "surrender"
    const val BUST = "bust"
    const val DEALER_TURN = "dealer_turn"
    const val REVEAL = "reveal"
    const val DEALER_HIT = "dealer_hit"
    const val ROUND_OVER = "round_over"
    val ALL = setOf(
        START, BET, REBUY, SIT_OUT, NO_BETS, DEAL, INSURANCE_TAKEN, INSURANCE_DECLINED, DEALER_BLACKJACK,
        PEEK_CLEAR, HIT, STAND, DOUBLE, SPLIT, SURRENDER, BUST, DEALER_TURN, REVEAL, DEALER_HIT, ROUND_OVER
    )
}

enum class BjReject {
    NONE, OVER, NOT_YOUR_TURN, WRONG_PHASE, BAD_BET, CANT_AFFORD, NOT_BROKE,
    NOT_FIRST_TWO, NOT_PAIR, MAX_HANDS, SPLIT_ACES, SURRENDER_OFF, INSURANCE_OFF
}

/** A card with a deal number that is unique within a round: a stable animation key even when a split moves it. */
data class BjCard(val card: Card, val uid: Int)

/** The stand-in the network and the table use for the dealer's hole card while it is face down. */
val BJ_HIDDEN_CARD: Card = Card(Rank.TWO, Suit.CLUBS, -1)

data class BjHand(
    val cards: List<BjCard>,
    val bet: Int,
    val doubled: Boolean = false,
    val fromSplit: Boolean = false,
    val splitAces: Boolean = false,
    val surrendered: Boolean = false,
    val stood: Boolean = false,
    val outcome: String? = null,
    val credit: Int = 0
) {
    val plain: List<Card> get() = cards.map { it.card }
    val total: BjRules.Total get() = BjRules.total(plain)
    val natural: Boolean get() = !fromSplit && BjRules.isBlackjack(plain)
    val done: Boolean get() = stood || surrendered || total.value >= 21
}

data class BjSeat(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val stack: Int,
    val rebuys: Int = 0,
    val bet: Int = 0,
    val betPlaced: Boolean = false,
    val sittingOut: Boolean = false,
    val hands: List<BjHand> = emptyList(),
    val insurance: Int = 0,
    val insuranceDecided: Boolean = false,
    val insuranceCredit: Int = 0,
    val roundNet: Int = 0,
    val roundsPlayed: Int = 0,
    val won: Int = 0,
    val lost: Int = 0,
    val pushed: Int = 0,
    val blackjacks: Int = 0
)

data class BjState(
    val seats: List<BjSeat>,
    /** The dealer's cards; while [holeHidden] the second one is [BJ_HIDDEN_CARD] on every peer that is not the host's own table. */
    val dealer: List<BjCard>,
    val holeHidden: Boolean,
    val phase: String,
    /** Whose turn: the seat betting, deciding insurance or playing; -1 while the dealer plays or the round is over. */
    val activeSeat: Int,
    val activeHand: Int = 0,
    val round: Int = 1,
    val event: String = BjEvent.START,
    val eventSeat: Int = -1,
    val eventHand: Int = 0,
    /** Grows with every change; keys the screen's animations. */
    val serial: Int = 0,
    val nextUid: Int = 0,
    val shoeLeft: Int = 0,
    val reshuffled: Boolean = false,
    val roundsDone: Int = 0,
    val config: BjConfig = BjConfig()
) {
    val upcard: Card? get() = dealer.firstOrNull()?.card
    val dealerTotal: BjRules.Total? get() = if (holeHidden || dealer.isEmpty()) null else BjRules.total(dealer.map { it.card })
}

private val ALL_CARDS: List<Card> = Deck.fresh52()

internal fun cardById(id: Int): Card = ALL_CARDS[id]
