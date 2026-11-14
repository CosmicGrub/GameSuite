package com.gamesuite.games.territorywars

/**
 * Why an action was refused. [whyKey] is the string resource name of the "why" line the screen shows (null for
 * reasons that never reach a player).
 */
enum class RejectReason(val whyKey: String?) {
    NOT_IMPLEMENTED(null),
    NOT_YOUR_TURN("tw_why_not_your_turn"),
    WRONG_PHASE(null),
    NOT_YOURS("tw_why_not_yours"),
    OWN_TARGET("tw_why_own_target"),
    NOT_ADJACENT("tw_why_not_adjacent"),
    NEED_TWO_ARMIES("tw_why_need_two"),
    NONE_LEFT("tw_why_none_left"),
    PLACE_FIRST("tw_why_place_first"),
    TURN_IN_FIRST("tw_why_turn_in_first"),
    NOT_A_SET("tw_cards_not_a_set"),
    NO_PATH("tw_why_no_path"),
    LEAVE_ONE("tw_why_leave_one"),
    ALREADY_FORTIFIED("tw_why_already_fortified"),
    MOVE_IN_FIRST("tw_why_move_in_first"),
    BAD_COUNT(null),
    BAD_DICE(null),
    GAME_OVER("tw_why_over")
}

sealed class ActionResult {
    /** The action was legal: [state] is the new table and [events] what happened, in order. */
    data class Accepted(val state: TerritoryWarsState, val events: List<TerritoryWarsEvent>) : ActionResult()

    /** The action was refused and the state is unchanged. */
    data class Rejected(val reason: RejectReason) : ActionResult()
}

/** The three parts of a seat's reinforcement (cards are the set value of a turn-in, 0 when none this turn). */
data class Reinforcement(val territories: Int, val regions: Int, val cards: Int) {
    val total: Int get() = territories + regions + cards
}

/**
 * The rules engine: pure functions over [TerritoryWarsState], no Compose, no threads, no clocks. Chance comes only
 * from the [DiceSource] (battles) and the config seed (deal and deck shuffle), so a war is replayable.
 */
interface TerritoryWarsEngine {
    /** A new war: deal territories, build and shuffle the deck, set the opening armies. Never throws for valid seats (2..6). */
    fun newGame(players: List<TerritoryWarsPlayer>, config: TerritoryWarsConfig): TerritoryWarsState

    /**
     * Every legal action for the seat on turn, for hints and bots. Placement is summarised as one
     * PlaceArmies(territory, 1) per own territory; attacks list every (from, to) with the maximum dice.
     */
    fun legalActions(state: TerritoryWarsState): List<TerritoryWarsAction>

    fun apply(state: TerritoryWarsState, action: TerritoryWarsAction): ActionResult

    /** What [seat] would receive at the start of its reinforcement right now (territories + regions; cards 0). */
    fun reinforcement(state: TerritoryWarsState, seat: Int): Reinforcement

    /** The winning seat(s) if the war is over (several only for a tie at the cap), else empty. */
    fun winnerSeats(state: TerritoryWarsState): List<Int>
}

/**
 * SCAFFOLD stub, safe values only: [newGame] returns an undealt blank table, nothing is legal, every action is
 * rejected with [RejectReason.NOT_IMPLEMENTED], reinforcement is zero and nobody has won.
 */
class StubTerritoryWarsEngine(
    @Suppress("unused") private val resolver: BattleResolver = StubBattleResolver(),
    @Suppress("unused") private val dice: DiceSource = SeededDice(0L)
) : TerritoryWarsEngine {

    override fun newGame(players: List<TerritoryWarsPlayer>, config: TerritoryWarsConfig): TerritoryWarsState {
        val map = TerritoryWarsMaps.byId(config.mapId) ?: TerritoryWarsMaps.STANDARD
        return TerritoryWarsState.blank(map, config, players)
    }

    override fun legalActions(state: TerritoryWarsState): List<TerritoryWarsAction> = emptyList()

    override fun apply(state: TerritoryWarsState, action: TerritoryWarsAction): ActionResult =
        ActionResult.Rejected(RejectReason.NOT_IMPLEMENTED)

    override fun reinforcement(state: TerritoryWarsState, seat: Int): Reinforcement = Reinforcement(0, 0, 0)

    override fun winnerSeats(state: TerritoryWarsState): List<Int> = emptyList()
}
