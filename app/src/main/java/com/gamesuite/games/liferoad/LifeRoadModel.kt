package com.gamesuite.games.liferoad

import com.gamesuite.settings.CpuDifficulty

/*
 * Life Road (id `life-road`): the data model. SCAFFOLD ONLY: nothing here was compiled or run by its author.
 * The rules these types describe live in docs/design/boardgames/life-road.md (section numbers in the KDoc
 * below point there). Everything is plain Kotlin (no Compose, no Android) so the engine is unit-testable.
 *
 * Original names only: this game never uses the names, wording, characters or layouts of any commercial
 * board game. "Coins" are play money; nothing here has a real-money feel.
 */

// ---------------------------------------------------------------- settings enums

/** Road length (section 3.1). [code] is the pattern column used by [LifeRoadBoardSpec]. */
enum class RoadSize(val id: String, val code: Char, val turnCapPerPlayer: Int, val approxMinutes: Int, val catchUpGap: Int) {
    QUICK("quick", 'Q', 14, 15, 6),
    STANDARD("standard", 'S', 26, 40, 12),
    LONG("long", 'L', 38, 60, 18);

    companion object {
        fun fromId(id: String?): RoadSize = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

/** Rule set (section 2.18). Junior is the gentle 6+ variant: no loans, no cover, no fresh starts. */
enum class RulesVariant(val id: String, val minAge: Int) {
    STANDARD("standard", 8),
    JUNIOR("junior", 6);

    companion object {
        fun fromId(id: String?): RulesVariant = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

/** The handicap row of setup (section 2.19). */
enum class HeadStart(val id: String) {
    NONE("none"), SMALL("small"), BIG("big");

    companion object {
        fun fromId(id: String?): HeadStart = values().firstOrNull { it.id == id } ?: NONE
    }
}

/** Everything the Hub passes in before [LifeRoadEngine.newGame]. */
data class LifeRoadConfig(
    val size: RoadSize = RoadSize.STANDARD,
    val variant: RulesVariant = RulesVariant.STANDARD,
    val headStart: HeadStart = HeadStart.NONE,
    /** 0-based seats that receive [headStart]. Empty = nobody. See [LifeRoadHandicap.parseSeats]. */
    val headStartSeats: Set<Int> = emptySet(),
    val catchUp: Boolean = true,
    /** Seeds every random stream (section 3.3). 0 is a legal seed. */
    val seed: Long = 0L,
    val difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
) {
    val junior: Boolean get() = variant == RulesVariant.JUNIOR
}

// ---------------------------------------------------------------- board

/** One square kind. [code] is the character used in the board patterns (section 2.16). */
enum class NodeKind(val code: Char, val key: String) {
    START('S', "start"), FORK('Y', "fork"), REST('.', "rest"), PAY('$', "pay"), EVENT('E', "event"),
    FAMILY('F', "family"), JOB_FAIR('J', "jobfair"), RAISE('R', "raise"), HOUSE_MARKET('H', "house"),
    MARKET_DAY('K', "marketday"), MILESTONE('M', "milestone"), COVER_SHOP('I', "cover"), BANK('B', "bank"),
    LUCKY_WHEEL('L', "wheel"), CAMPUS('C', "campus"), GRADUATION('G', "graduation"), FIRST_JOB('A', "firstjob"),
    RETIRE('X', "retire");

    /** String resource name of the display name. */
    val nameKey: String get() = "lr_node_$key"

    /** String resource name of the one-line help (long-press and TalkBack hint). */
    val helpKey: String get() = "lr_nodehelp_$key"

    companion object {
        fun fromCode(c: Char): NodeKind? = values().firstOrNull { it.code == c }
    }
}

/**
 * One square. [id] is stable and is what snapshots store ("TA.4", "F2", "RC"). [bold] marks the Cliff Path:
 * coins gained or lost on it count 1.5 times (section 2.4). [next] is empty only on retire nodes.
 */
data class PathNode(
    val id: String,
    val kind: NodeKind,
    val segment: String,
    val index: Int,
    val bold: Boolean,
    val next: List<String>
)

/** The whole board of one [RoadSize]. Immutable; rebuild with [LifeRoadBoard.build] (never saved). */
class RoadGraph(val size: RoadSize, val nodes: List<PathNode>) {
    private val byId: Map<String, PathNode> = nodes.associateBy { it.id }

    val startId: String get() = LifeRoadBoardSpec.START_ID

    fun node(id: String): PathNode? = byId[id]
    fun contains(id: String): Boolean = id in byId

    /**
     * Human-readable defects: unreachable nodes, dead ends, forks without two exits, cycles.
     * TODO(P1): implement the checks of LifeRoadBoardTest; the scaffold reports nothing.
     */
    fun problems(): List<String> = emptyList()
}

// ---------------------------------------------------------------- content definitions

enum class Career { CARE, BUILD, CREATE, TECH, OUTDOOR, SERVICE }

/** TIPS jobs add 2 x a spin to every pay (section 2.7). */
enum class PayRule { FIXED, TIPS }

/** A job (section 2.6). Tiers 4 and 5 need a degree. Names live in string `lr_job_<id>`. */
data class JobDef(
    val id: String,
    val tier: Int,
    val salary: Int,
    val career: Career,
    val payRule: PayRule,
    val needsDegree: Boolean
) {
    val nameKey: String get() = "lr_job_$id"
}

/** A home (section 2.8). Names live in string `lr_house_<id>`. */
data class HouseDef(val id: String, val price: Int) {
    val nameKey: String get() = "lr_house_$id"
}

/** A life milestone (section 2.9). Names live in string `lr_ms_<id>`. */
data class MilestoneDef(val id: String) {
    val nameKey: String get() = "lr_ms_$id"
}

/** The three insurance kinds (section 2.10). */
enum class CoverKind { HOME, CARE, INCOME;

    val nameKey: String get() = "lr_cover_${name.lowercase()}"
}

enum class EventTone { GOOD, SETBACK, FUN }

/** What a card can do (section 2.12). The effect DSL tokens in the doc map one to one onto these. */
enum class EffectKind {
    COINS, SPIN_COINS, EACH_OTHER_PAYS, PAY_EACH_OTHER, ALL_COLLECT, MOVE_FORWARD, MOVE_BACK,
    SKIP_NEXT_PAY, HALVE_NEXT_PAY, DOUBLE_NEXT_PAY, MEMORY, LUCKY_COIN, EXTRA_TURN, RAISE, JOB_FAIR, FAMILY_PEG
}

/** [amount] is signed for COINS and SPIN_COINS (a multiplier per spin point), unsigned for the rest. */
data class EventEffect(val kind: EffectKind, val amount: Int = 0)

/** An event card. [cover] is the insurance that cancels its loss (null = nothing cancels it). Text: `lr_event_<id>`. */
data class EventCardDef(
    val id: String,
    val tone: EventTone,
    val cover: CoverKind?,
    val effects: List<EventEffect>
) {
    val textKey: String get() = "lr_event_$id"
}

/** A family card. Text: `lr_family_<id>`. */
data class FamilyCardDef(val id: String, val effects: List<EventEffect>) {
    val textKey: String get() = "lr_family_$id"
}

// ---------------------------------------------------------------- players and table state

/** How the next pay stop is modified (a newer flag replaces an older one; cleared after one pay). */
enum class NextPay { NORMAL, SKIP, HALVE, DOUBLE }

/** Where a retired player chose to stop. LATE = the turn cap ended the game before they reached a lane. */
enum class RetireLane { COZY, GRAND, LATE }

/** A home a player owns. [soldFor] is set once the market roll has been made. */
data class HomeHolding(val houseId: String, val paid: Int, val soldFor: Int? = null)

/**
 * One traveller. Seat colour and shape are both derived from [seat] (the screen picks them by index) so
 * identity never rests on hue alone.
 */
data class PlayerState(
    val seat: Int,
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val nodeId: String,
    val cash: Int,
    val loans: Int = 0,
    val jobId: String? = null,
    val degree: Boolean = false,
    val raises: Int = 0,
    val homes: List<HomeHolding> = emptyList(),
    val familyPegs: Int = 0,
    val milestonesClaimed: List<String> = emptyList(),
    /** Memories from cards (the other source of memories). Total memories = claimed + this, capped. */
    val cardMemories: Int = 0,
    val covers: Set<CoverKind> = emptySet(),
    val luckyCoins: Int = 0,
    val nextPay: NextPay = NextPay.NORMAL,
    val freshStarts: Int = 0,
    val retired: Boolean = false,
    /** 1 = first to retire. 0 = not retired yet or retired late (no bonus). */
    val retireOrder: Int = 0,
    val lane: RetireLane? = null,
    val turnsTaken: Int = 0,
    /** Squares walked so far; the catch-up boost compares this across players. */
    val stepsMoved: Int = 0
) {
    val memories: Int get() = minOf(LifeRoadRules.MEMORY_CAP, milestonesClaimed.size + cardMemories)
}

/** The turn state machine (section 2.15). MOVING and TURN_END are transient and never saved. */
enum class Phase {
    START_FORK, AWAIT_FORK, AWAIT_SPIN, AWAIT_RESPIN, MOVING, AWAIT_JOB, AWAIT_HOUSE, AWAIT_COVER,
    AWAIT_BANK, AWAIT_MARKET, AWAIT_CARD, SETTLING, TURN_END, GAME_OVER
}

/**
 * The open question of the seat on turn. Which fields matter depends on the phase:
 *  - AWAIT_FORK: [forkNodeId], [stepsLeft] (0 = the piece stopped on the fork and chooses before the next spin).
 *  - [stepsLeft] > 0 in ANY phase means the move resumes after the decision (a fork, or a gate square such as the
 *    first job or graduation that the piece passed).
 *  - AWAIT_RESPIN: [spin] (the spin the player may keep or replace with a lucky coin).
 *  - AWAIT_JOB: [offerIds] (job ids), [mustPick] (first job and graduation cannot be declined).
 *  - AWAIT_HOUSE: [offerIds] (house ids). AWAIT_MARKET: nothing extra (the player's own homes are the offers).
 *  - AWAIT_CARD: [cardId] and [cardIsFamily]; [needsSpin] is true for cards and squares that need a spin first.
 *  - SETTLING: [settleIndex] = next home (index into PlayerState.homes) to sell.
 */
data class PendingState(
    val spin: Int = 0,
    val stepsLeft: Int = 0,
    val forkNodeId: String? = null,
    val offerIds: List<String> = emptyList(),
    val mustPick: Boolean = false,
    val cardId: String? = null,
    val cardIsFamily: Boolean = false,
    val needsSpin: Boolean = false,
    val settleIndex: Int = 0
)

/** The shuffled decks. Orders are host-only secrets in a networked game (section 3.7). */
data class DeckState(
    /** Index 0 = tier 1 ... index 4 = tier 5; each list is the draw pile, top first. */
    val jobs: List<List<String>>,
    val events: List<String>,
    val family: List<String>,
    /** Home ids still for sale. */
    val houseStock: List<String>
)

/** How many numbers each random stream has handed out (section 3.3). Replaying from the seed + these is exact. */
data class RngCursors(val spin: Long = 0L, val deck: Long = 0L, val bot: Long = 0L)

/** The whole table. Immutable: [LifeRoadEngine.apply] returns a new one. */
data class LifeRoadState(
    val config: LifeRoadConfig,
    val players: List<PlayerState>,
    val currentSeat: Int,
    val phase: Phase,
    val pending: PendingState,
    val decks: DeckState,
    val cursors: RngCursors,
    /** 1-based count of turns started since the board opened (all seats). */
    val turnNumber: Int = 0,
    val retiredCount: Int = 0,
    val luckyUsedThisTurn: Boolean = false,
    /** The seat's most recent spin (0 before the first) and a serial that only grows (keys the dice animation). */
    val lastSpin: Int = 0,
    val spinSerial: Int = 0,
    /** True while the seat on turn is taking an extra turn from a card (no catch-up boost on extra turns). */
    val extraTurn: Boolean = false,
    val over: Boolean = false,
    /** Seats that share the win (empty until [over]). More than one only after every tie-break ties. */
    val winnerSeats: List<Int> = emptyList()
)

// ---------------------------------------------------------------- actions, events, results

/** Every move a person or CPU can make. [LifeRoadEngine.legalActions] lists the ones allowed right now. */
sealed class LifeRoadAction {
    /** Spin the 1 to 10 spinner (movement, a card spin, the wheel, or one market roll when settling). */
    object Spin : LifeRoadAction()

    /** AWAIT_RESPIN: replace the spin using one lucky coin (once per turn). */
    object UseLuckyCoin : LifeRoadAction()

    /** AWAIT_RESPIN: keep the spin. */
    object KeepSpin : LifeRoadAction()

    /** START_FORK / AWAIT_FORK: take the branch whose first node is [nodeId] (F4 branches are the retirement lanes). */
    data class ChooseBranch(val nodeId: String) : LifeRoadAction()

    /** AWAIT_JOB: take [jobId]; null keeps the current job (only when not mustPick). */
    data class PickJob(val jobId: String?) : LifeRoadAction()

    data class BuyHouse(val houseId: String) : LifeRoadAction()
    object DeclineHouse : LifeRoadAction()

    /** AWAIT_MARKET: sell one of your homes now with a market roll. */
    data class SellHome(val houseId: String) : LifeRoadAction()
    object DeclineSale : LifeRoadAction()

    data class BuyCover(val kind: CoverKind) : LifeRoadAction()
    object DeclineCover : LifeRoadAction()

    /** AWAIT_BANK: borrow one block / repay one loan, repeatable until [Done]. */
    object TakeLoan : LifeRoadAction()
    object RepayLoan : LifeRoadAction()
    object Done : LifeRoadAction()

    /** AWAIT_CARD (no spin needed): dismiss the card and apply it. */
    object Acknowledge : LifeRoadAction()
}

/** Why an action was refused. Each maps to an `lr_why_*` string (the "why" message of the quality bar). */
enum class LifeRoadReject(val stringKey: String?) {
    NONE(null),
    NOT_YOUR_TURN("lr_why_not_turn"),
    WRONG_PHASE("lr_why_phase"),
    GAME_OVER("lr_why_over"),
    NOT_ENOUGH_COINS("lr_why_coins"),
    NO_LUCKY_COIN("lr_why_no_lucky"),
    LOAN_LIMIT("lr_why_loan_limit"),
    NOTHING_TO_REPAY("lr_why_no_loan"),
    HOUSE_LIMIT("lr_why_house_limit"),
    HOUSE_NOT_AVAILABLE("lr_why_house_gone"),
    NEED_HOUSE_FOR_GRAND("lr_why_need_house"),
    JUNIOR_NO_LOANS("lr_why_junior"),
    BAD_CHOICE("lr_why_phase"),

    /** The stub engine refuses everything with this until the engine is built. */
    NOT_IMPLEMENTED("lr_why_phase")
}

/** What happened, for animation, sound, haptics and announcements. Pure data; the screen words it. */
enum class LifeRoadEventKind {
    SPIN, CATCH_UP, LUCKY_COIN, MOVE, PASS_PAY, LAND, JOB, DEGREE, RAISE, HOUSE_BOUGHT, HOUSE_SOLD, COVER_BOUGHT,
    COVER_SAVED, LOAN, REPAY, CARD, MEMORY, FAMILY, WHEEL, FRESH_START, RETIRE, LATE_RETIRE, GAME_OVER
}

/** [a] and [b] carry the numbers (spin, coins, squares); [ref] the content id (job, house, card). */
data class LifeRoadEvent(
    val kind: LifeRoadEventKind,
    val seat: Int,
    val a: Int = 0,
    val b: Int = 0,
    val ref: String = ""
)

/** The result of one [LifeRoadEngine.apply]. On a refusal [state] is unchanged and [events] is empty. */
data class LifeRoadStep(
    val state: LifeRoadState,
    val events: List<LifeRoadEvent> = emptyList(),
    val reject: LifeRoadReject = LifeRoadReject.NONE
) {
    val accepted: Boolean get() = reject == LifeRoadReject.NONE
}

/** One line of a score card; [labelKey] is an `lr_sc_*` string. */
data class ScoreLine(val labelKey: String, val amount: Int)

/** One player's score card (section 2.14). [lines] always sum to [total]: there is no hidden term. */
data class ScoreCard(val seat: Int, val lines: List<ScoreLine>, val total: Int, val lifePoints: Int, val place: Int = 0)
