package com.gamesuite.games.propertytycoon

import kotlinx.serialization.Serializable

/*
 * Property Tycoon (id `property-tycoon`): data model SCAFFOLD. Nothing here was compiled or run by its
 * author. Every number and rule below is specified in docs/design/boardgames/property-tycoon.md (the SCRIPT
 * part); when the two disagree the document wins and this file is the bug.
 *
 * Money is PLAY MONEY ("pearls") only. There is no purchase, cash-out or real-currency path anywhere.
 */

/** Gates for features that ship after the MVP (SCOPE part of the design doc). Flip each to true when its phase lands. */
object PtRelease {
    const val AUCTIONS_ENABLED = false
    const val PLEDGING_ENABLED = false
    const val TRADES_ENABLED = false
}

/** The fixed numbers of the rules script. Section numbers refer to the design doc. */
object PtRules {
    const val TILE_COUNT = 40
    const val START_INDEX = 0
    const val TIMEOUT_INDEX = 10
    const val FREE_ANCHORAGE_INDEX = 20
    const val GO_TO_TIMEOUT_INDEX = 30
    const val MIN_SEATS = 2
    const val MAX_SEATS = 6
    const val SALARY = 160
    const val TIMEOUT_FINE = 100
    const val MAX_TIMEOUT_TURNS = 3
    const val MAX_DOUBLES = 3
    const val CABINS_PER_TILE = 4
    const val RESORT_LEVEL = 5
    const val CABIN_SUPPLY = 36
    const val RESORT_SUPPLY = 14
    const val MIN_BID_STEP = 10
    const val PLEDGE_INTEREST_PERCENT = 10
    const val MAX_COUNTER_DEPTH = 3
    const val ESCALATOR_PERCENT_PER_ROUND = 5
    const val ESCALATOR_CAP_PERCENT = 150
    const val HANDICAP_FIXED_STEP = 25
    val TRANSIT_RENTS: List<Int> = listOf(20, 45, 90, 180)
    val UTILITY_MULTIPLIERS: List<Int> = listOf(5, 12)
}

enum class TileType { START, PROPERTY, TRANSIT, UTILITY, TAX, TIDE_CARD, GUILD_CARD, TIMEOUT, FREE_ANCHORAGE, GO_TO_TIMEOUT }

/**
 * The eight colour groups. [shape] is the colour-blind-safe second cue (the screen draws it on every tile and
 * token badge and TalkBack reads it); [size] is how many tiles the group holds (checked against the board by tests).
 */
enum class ColorGroup(val shape: String, val size: Int) {
    CLAY("triangle", 2),
    MIST("circle", 2),
    REED("diamond", 2),
    LAGOON("square", 3),
    PLUM("star", 3),
    CORAL("cross", 3),
    GOLD("hexagon", 3),
    OPAL("drop", 4)
}

/**
 * One tile of the 40-tile ring. [nameKey] is a string resource name (`pt_tile_NN`). [rents] holds, for a
 * PROPERTY: base, 1 cabin, 2, 3, 4 cabins, resort (6 values); empty for every other type. [amount] is the
 * flat tax of a TAX tile. [price] is 0 for tiles that cannot be owned.
 */
data class Tile(
    val index: Int,
    val type: TileType,
    val nameKey: String,
    val group: ColorGroup? = null,
    val price: Int = 0,
    val buildCost: Int = 0,
    val rents: List<Int> = emptyList(),
    val amount: Int = 0
) {
    val ownable: Boolean get() = type == TileType.PROPERTY || type == TileType.TRANSIT || type == TileType.UTILITY
    val pledgeValue: Int get() = price / 2
}

enum class DeckKind { TIDE, GUILD }

/** What a card does. Resolved by the engine; the screen only shows [Card.textKey]. */
sealed class CardEffect {
    data class Gain(val amount: Int) : CardEffect()
    data class Pay(val amount: Int) : CardEffect()

    /** Move to [tile]; with [collectStipend] the stipend is paid if the move passes (or lands on) the Quay going forward. */
    data class MoveTo(val tile: Int, val collectStipend: Boolean) : CardEffect()

    /** Move [steps] tiles (negative = back, never collects the stipend) and resolve the new tile. */
    data class MoveBy(val steps: Int) : CardEffect()

    /**
     * Move forward to the nearest tile of [type]. If it is owned, the rent is multiplied by [rentMultiplier]
     * (transit) or, when [diceMultiplier] > 0, is that many times a fresh dice roll (utility).
     */
    data class MoveToNearest(val type: TileType, val rentMultiplier: Int, val diceMultiplier: Int) : CardEffect()

    object GoToTimeout : CardEffect()
    object GetOutFree : CardEffect()
    data class PayEachRival(val amount: Int) : CardEffect()
    data class CollectFromEachRival(val amount: Int) : CardEffect()
    data class Repairs(val perCabin: Int, val perResort: Int) : CardEffect()
}

data class Card(val id: String, val deck: DeckKind, val textKey: String, val effect: CardEffect)

/** Game length presets (design doc SCALE part). [escalatorStartRound] < 0 means rents never escalate. */
enum class GameLength(
    val startCash: Int,
    val rentPercent: Int,
    val roundCap: Int,
    val escalatorStartRound: Int,
    val lastStandingWins: Boolean
) {
    QUICK(startCash = 1200, rentPercent = 200, roundCap = 20, escalatorStartRound = -1, lastStandingWins = false),
    STANDARD(startCash = 1600, rentPercent = 100, roundCap = 200, escalatorStartRound = 80, lastStandingWins = true),
    MARATHON(startCash = 2500, rentPercent = 75, roundCap = 400, escalatorStartRound = 150, lastStandingWins = true)
}

/** The house-rule toggles (design doc SCRIPT, section on house rules). Auctions, pledging and trades are also gated by [PtRelease]. */
@Serializable
data class HouseRules(
    val auctions: Boolean = true,
    val jackpot: Boolean = false,
    val doubleStipendOnLanding: Boolean = false,
    val rentFreeTimeout: Boolean = false,
    val trades: Boolean = true,
    val pledging: Boolean = true
)

/**
 * Everything fixed when a game is set up. [startCash] is the per-seat base (already resolved from the length
 * unless the Hub chose an explicit amount). [handicapSeat] is 0-based or -1; [handicapPercent] is the extra
 * share of [startCash] that seat receives.
 */
@Serializable
data class TycoonConfig(
    val length: GameLength = GameLength.STANDARD,
    val startCash: Int = GameLength.STANDARD.startCash,
    val seed: Long = 1L,
    val houseRules: HouseRules = HouseRules(),
    val handicapSeat: Int = -1,
    val handicapPercent: Int = 0
)

@Serializable
data class PlayerState(
    val seat: Int,
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val cash: Int,
    val position: Int = 0,
    val inTimeout: Boolean = false,
    val timeoutTurns: Int = 0,
    val freePassCards: Int = 0,
    val doublesStreak: Int = 0,
    val bankrupt: Boolean = false,
    val bankruptRound: Int = 0,
    val rentCollected: Int = 0,
    val rentPaid: Int = 0
)

/** Ownership of one tile. [buildings] is 0..4 cabins, 5 = resort (only PROPERTY tiles ever have buildings). */
@Serializable
data class Holding(val tile: Int, val owner: Int, val buildings: Int = 0, val pledged: Boolean = false)

enum class DebtReason { RENT, TAX, CARD, FINE, REPAIRS, PLEDGE_INTEREST, TRADE }

/** A payment the debtor cannot cover from cash yet. [creditor] is a seat, or -1 for the bank. */
@Serializable
data class DebtState(val debtor: Int, val creditor: Int, val amount: Int, val reason: DebtReason)

/**
 * An open auction. [eligible] are the seats still in; [passed] are seats that dropped out (passing is final).
 * [actor] is the seat whose bid or pass is awaited. [highSeat] is -1 until the first bid.
 */
@Serializable
data class AuctionState(
    val tile: Int,
    val eligible: List<Int>,
    val passed: List<Int> = emptyList(),
    val highBid: Int = 0,
    val highSeat: Int = -1,
    val actor: Int
)

/** One side of a trade: pearls, tile indexes, free-pass cards. */
@Serializable
data class TradeSide(val cash: Int = 0, val tiles: List<Int> = emptyList(), val freePassCards: Int = 0) {
    val isEmpty: Boolean get() = cash == 0 && tiles.isEmpty() && freePassCards == 0
}

enum class TradeStatus { PROPOSED, ACCEPTED, DECLINED, COUNTERED, CANCELLED, EXPIRED }

/**
 * A trade offer. [give] leaves [fromSeat] for [toSeat]; [get] leaves [toSeat] for [fromSeat].
 * A counter offer is a NEW trade with roles swapped, [parentId] set and the parent marked COUNTERED;
 * the chain stops at [PtRules.MAX_COUNTER_DEPTH].
 */
@Serializable
data class Trade(
    val id: Int,
    val fromSeat: Int,
    val toSeat: Int,
    val give: TradeSide,
    val get: TradeSide,
    val status: TradeStatus = TradeStatus.PROPOSED,
    val parentId: Int = -1,
    val createdRound: Int = 0
)

/**
 * The phase the table is WAITING in. The engine resolves everything between two waits atomically and reports
 * it as [PtEvent]s for the screen to animate. AWAIT_DEBT, AWAIT_AUCTION and AWAIT_TRADE_REPLY are interrupts:
 * they push the interrupted phase on [TycoonState.phaseStack] and pop it when they finish.
 */
enum class Phase {
    AWAIT_ROLL,
    AWAIT_TIMEOUT_CHOICE,
    AWAIT_BUY,
    AWAIT_CARD_ACK,
    AWAIT_POST_ROLL,
    AWAIT_DEBT,
    AWAIT_AUCTION,
    AWAIT_TRADE_REPLY,
    GAME_OVER
}

/**
 * The whole table, immutable and fully serializable (the snapshot wraps it). Every random draw comes from
 * [rngState] (SplitMix64, see [PtRng]) so a restored game continues the exact same dice and shuffles.
 * [tideDraw] / [guildDraw] are card ids still to draw (top first); the discards recycle when a draw pile runs out.
 */
@Serializable
data class TycoonState(
    val config: TycoonConfig,
    val players: List<PlayerState>,
    val holdings: List<Holding> = emptyList(),
    val currentSeat: Int = 0,
    val round: Int = 1,
    val phase: Phase = Phase.AWAIT_ROLL,
    val phaseStack: List<Phase> = emptyList(),
    val dice1: Int = 0,
    val dice2: Int = 0,
    val extraRollPending: Boolean = false,
    val landedTile: Int = -1,
    val pendingCardId: String? = null,
    val tideDraw: List<String> = emptyList(),
    val tideDiscard: List<String> = emptyList(),
    val guildDraw: List<String> = emptyList(),
    val guildDiscard: List<String> = emptyList(),
    val cabinsLeft: Int = PtRules.CABIN_SUPPLY,
    val resortsLeft: Int = PtRules.RESORT_SUPPLY,
    val jackpot: Int = 0,
    val auction: AuctionState? = null,
    val auctionQueue: List<Int> = emptyList(),
    val debt: DebtState? = null,
    val trades: List<Trade> = emptyList(),
    val nextTradeId: Int = 1,
    val rngState: Long = 0L,
    val rollSerial: Int = 0,
    val eventSerial: Int = 0,
    val over: Boolean = false,
    val winnerSeat: Int = -1
) {
    fun holding(tile: Int): Holding? = holdings.firstOrNull { it.tile == tile }
    fun ownedBy(seat: Int): List<Holding> = holdings.filter { it.owner == seat }
    val activeSeats: List<Int> get() = players.filter { !it.bankrupt }.map { it.seat }
}

/** The things a seat can ask the engine to do. [PropertyTycoonEngine.legalCommands] lists the ones allowed right now. */
sealed class PtCommand {
    object Roll : PtCommand()
    object PayFine : PtCommand()
    object UseFreePassCard : PtCommand()
    object Buy : PtCommand()
    object DeclineBuy : PtCommand()
    object AckCard : PtCommand()
    data class Build(val tile: Int) : PtCommand()
    data class SellBuilding(val tile: Int) : PtCommand()
    data class Pledge(val tile: Int) : PtCommand()
    data class Redeem(val tile: Int) : PtCommand()
    object EndTurn : PtCommand()
    data class ProposeTrade(val give: TradeSide, val get: TradeSide, val toSeat: Int) : PtCommand()
    data class RespondTrade(val tradeId: Int, val accept: Boolean) : PtCommand()
    data class CounterTrade(val tradeId: Int, val give: TradeSide, val get: TradeSide) : PtCommand()
    data class CancelTrade(val tradeId: Int) : PtCommand()
    data class Bid(val amount: Int) : PtCommand()
    object PassAuction : PtCommand()
    object DeclareBankruptcy : PtCommand()
}

/** Why a command was refused; each value has a `pt_why_*` string where the player needs to see it. */
enum class PtReject {
    NONE,
    NOT_YOUR_TURN,
    WRONG_PHASE,
    CANNOT_AFFORD,
    SET_INCOMPLETE,
    UNEVEN_BUILD,
    UNEVEN_SELL,
    SUPPLY_EMPTY,
    HAS_BUILDINGS,
    PLEDGED,
    IN_DEBT,
    TRADE_INVALID,
    FEATURE_DISABLED,
    NOT_IMPLEMENTED
}

/** What happened, in order, for the screen to animate and announce. Carries seats and tile indexes, never text. */
sealed class PtEvent {
    data class Rolled(val seat: Int, val d1: Int, val d2: Int) : PtEvent()
    data class Moved(val seat: Int, val from: Int, val to: Int, val passedStart: Boolean) : PtEvent()
    data class StipendPaid(val seat: Int, val amount: Int) : PtEvent()
    data class Bought(val seat: Int, val tile: Int, val price: Int) : PtEvent()
    data class RentPaid(val from: Int, val to: Int, val tile: Int, val amount: Int) : PtEvent()
    data class TaxPaid(val seat: Int, val amount: Int) : PtEvent()
    data class CardDrawn(val seat: Int, val cardId: String) : PtEvent()
    data class Built(val seat: Int, val tile: Int, val level: Int) : PtEvent()
    data class BuildingSold(val seat: Int, val tile: Int, val level: Int) : PtEvent()
    data class Pledged(val seat: Int, val tile: Int, val amount: Int) : PtEvent()
    data class Redeemed(val seat: Int, val tile: Int, val amount: Int) : PtEvent()
    data class TimeoutEntered(val seat: Int) : PtEvent()
    data class TimeoutLeft(val seat: Int) : PtEvent()
    data class JackpotTaken(val seat: Int, val amount: Int) : PtEvent()
    data class AuctionOpened(val tile: Int, val minBid: Int) : PtEvent()
    data class AuctionWon(val seat: Int, val tile: Int, val price: Int) : PtEvent()
    data class AuctionUnsold(val tile: Int) : PtEvent()
    data class TradeProposed(val tradeId: Int) : PtEvent()
    data class TradeSettled(val tradeId: Int, val status: TradeStatus) : PtEvent()
    data class DebtOpened(val debtor: Int, val creditor: Int, val amount: Int) : PtEvent()
    data class Bankrupt(val seat: Int, val creditor: Int) : PtEvent()
    data class RisingTide(val percent: Int) : PtEvent()
    data class RoundCapReached(val round: Int) : PtEvent()
    data class Winner(val seat: Int) : PtEvent()
}

/** The result of asking the engine to apply a command. On a refusal [state] is the unchanged input and [events] is empty. */
data class PtResult(val state: TycoonState, val events: List<PtEvent> = emptyList(), val reject: PtReject = PtReject.NONE) {
    val accepted: Boolean get() = reject == PtReject.NONE
}
