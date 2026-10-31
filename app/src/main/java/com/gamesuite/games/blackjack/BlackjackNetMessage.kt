package com.gamesuite.games.blackjack

import kotlinx.serialization.Serializable

/**
 * Wire protocol for Blackjack over a networked MultiplayerTransport (Nearby / Online), 1 to 4 seats.
 * Host-authoritative: the host owns the shoe, the shuffle and the dealer. A guest can only ask to bet,
 * rebuy, sit out, take or decline insurance, or play an action for its OWN seat when that seat is on
 * turn. The host answers every change with a [StateSync]. The shoe order never leaves the host, and the
 * dealer's hole card travels as -1 until the host reveals it.
 */
@Serializable
sealed class BjNetMessage {
    @Serializable
    data class StateSync(val version: Int, val state: BjNetState) : BjNetMessage()

    @Serializable
    data class BetRequest(val amount: Int) : BjNetMessage()

    @Serializable
    data object RebuyRequest : BjNetMessage()

    @Serializable
    data object SitOutRequest : BjNetMessage()

    @Serializable
    data class InsuranceRequest(val take: Boolean) : BjNetMessage()

    /** [action] is a [BjAction] name (HIT, STAND, DOUBLE, SPLIT, SURRENDER). */
    @Serializable
    data class ActionRequest(val action: String) : BjNetMessage()

    @Serializable
    data object NextRoundRequest : BjNetMessage()

    @Serializable
    data object RequestState : BjNetMessage()
}

/** A card on the wire: [id] 0..51, or -1 for a face-down card the peer may not see. */
@Serializable
data class BjNetCard(val id: Int, val uid: Int)

@Serializable
data class BjNetHand(
    val cards: List<BjNetCard>, val bet: Int, val doubled: Boolean, val fromSplit: Boolean, val splitAces: Boolean,
    val surrendered: Boolean, val stood: Boolean, val outcome: String?, val credit: Int
)

@Serializable
data class BjNetSeat(
    val playerId: String, val displayName: String, val isBot: Boolean, val stack: Int, val rebuys: Int,
    val bet: Int, val betPlaced: Boolean, val sittingOut: Boolean, val hands: List<BjNetHand>,
    val insurance: Int, val insuranceDecided: Boolean, val insuranceCredit: Int, val roundNet: Int,
    val roundsPlayed: Int, val won: Int, val lost: Int, val pushed: Int, val blackjacks: Int
)

@Serializable
data class BjNetState(
    val seats: List<BjNetSeat>, val dealer: List<BjNetCard>, val holeHidden: Boolean, val phase: String,
    val activeSeat: Int, val activeHand: Int, val round: Int, val event: String, val eventSeat: Int,
    val eventHand: Int, val serial: Int, val nextUid: Int, val shoeLeft: Int, val reshuffled: Boolean,
    val roundsDone: Int, val config: BjConfig
)
