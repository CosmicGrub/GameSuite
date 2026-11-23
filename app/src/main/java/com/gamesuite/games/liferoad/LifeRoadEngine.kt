package com.gamesuite.games.liferoad

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty

/**
 * The rules engine contract (docs section 2). Pure and deterministic: [apply] never mutates, never reads a
 * clock, and draws random numbers only through [LifeRoadRng] with the cursors held in the state.
 */
interface LifeRoadEngine {
    /** The board of [size]. */
    fun graph(size: RoadSize): RoadGraph

    /** A fresh table in phase START_FORK with every seat on the Start Gate. [players].size must be 2..6. */
    fun newGame(config: LifeRoadConfig, players: List<PlayerInfo>): LifeRoadState

    /** Every action [seat] may take right now (empty when it is not their turn or the game is over). */
    fun legalActions(state: LifeRoadState, seat: Int): List<LifeRoadAction>

    /** Why [action] would be refused ([LifeRoadReject.NONE] when it is legal). Must agree with [legalActions]. */
    fun explain(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadReject

    /** Applies [action]. A refusal returns the unchanged state with the reject reason. */
    fun apply(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadStep

    /** The CPU's next action for the seat on turn (section 2.17). Must always be legal; must never stall. */
    fun botAction(state: LifeRoadState, level: CpuDifficulty): LifeRoadAction

    /** Score cards for every seat, with places and tie-breaks applied (section 2.14). */
    fun scoreCards(state: LifeRoadState): List<ScoreCard>
}

/** The score formula of section 2.14 as plain arithmetic, shared by the stub and the future engine. */
object LifeRoadScoring {
    fun card(state: LifeRoadState, seat: Int): ScoreCard {
        val p = state.players[seat]
        val junior = state.config.junior
        val homesValue = p.homes.sumOf { it.soldFor ?: it.paid }
        val bonus = if (p.retireOrder in 1..LifeRoadRules.RETIRE_BONUS.size) LifeRoadRules.RETIRE_BONUS[p.retireOrder - 1] else 0
        val lines = listOf(
            ScoreLine("lr_sc_cash", p.cash),
            ScoreLine("lr_sc_homes", homesValue),
            ScoreLine("lr_sc_retire", bonus),
            ScoreLine("lr_sc_memories", p.memories * LifeRoadRules.MEMORY_VALUE),
            ScoreLine("lr_sc_family", p.familyPegs * LifeRoadRules.FAMILY_VALUE),
            ScoreLine("lr_sc_comfort", p.homes.size * LifeRoadRules.COMFORT_VALUE),
            ScoreLine("lr_sc_loans", if (junior) 0 else -p.loans * LifeRoadRules.LOAN_OWED),
            ScoreLine("lr_sc_fresh", if (junior) 0 else -p.freshStarts * LifeRoadRules.FRESH_PENALTY)
        )
        val life = p.memories * LifeRoadRules.MEMORY_VALUE + p.familyPegs * LifeRoadRules.FAMILY_VALUE
        return ScoreCard(seat, lines, lines.sumOf { it.amount }, life)
    }
}

/** The head-start seat option of the Hub: "none" / "" / unknown for nobody, "all", or "1".."6" (1-based). */
object LifeRoadHandicap {
    fun parseSeats(value: String?, seats: Int): Set<Int> {
        val v = value?.trim()?.lowercase() ?: return emptySet()
        if (v.isEmpty() || v == "none") return emptySet()
        if (v == "all") return (0 until seats).toSet()
        return v.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }.filter { it in 0 until seats }.toSet()
    }
}

/**
 * The scaffold engine: compiles, returns safe values, implements nothing. Every [apply] is refused with
 * [LifeRoadReject.NOT_IMPLEMENTED] so a screen wired to it cannot corrupt a game. Replace in phase P1.
 */
class StubLifeRoadEngine : LifeRoadEngine {
    override fun graph(size: RoadSize): RoadGraph = LifeRoadBoard.build(size)

    override fun newGame(config: LifeRoadConfig, players: List<PlayerInfo>): LifeRoadState {
        require(players.size in 2..6) { "Life Road needs 2 to 6 seats" }
        val startCash = if (config.junior) LifeRoadRules.JUNIOR_START_CASH else LifeRoadRules.START_CASH
        val seats = players.mapIndexed { i, info ->
            val head = i in config.headStartSeats
            val lucky = (if (config.junior) 1 else 0) +
                when {
                    !head -> 0
                    config.headStart == HeadStart.SMALL -> LifeRoadRules.SMALL_HEAD_LUCKY
                    config.headStart == HeadStart.BIG -> LifeRoadRules.BIG_HEAD_LUCKY
                    else -> 0
                }
            PlayerState(
                seat = i, playerId = info.playerId, displayName = info.displayName, isBot = info.isBot,
                nodeId = LifeRoadBoardSpec.START_ID,
                cash = startCash + if (head && config.headStart == HeadStart.BIG) LifeRoadRules.BIG_HEAD_COINS else 0,
                luckyCoins = lucky
            )
        }
        return LifeRoadState(
            config = config, players = seats, currentSeat = 0, phase = Phase.START_FORK, pending = PendingState(),
            // TODO(P2): shuffle the decks from RngStream.DECK; the stub leaves them in table order.
            decks = DeckState(
                jobs = List(5) { tier -> LifeRoadContent.jobs.filter { it.tier == tier + 1 }.map { it.id } },
                events = LifeRoadContent.events.map { it.id },
                family = LifeRoadContent.familyCards.map { it.id },
                houseStock = LifeRoadContent.houses.map { it.id }
            ),
            cursors = RngCursors()
        )
    }

    override fun legalActions(state: LifeRoadState, seat: Int): List<LifeRoadAction> = emptyList()

    override fun explain(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadReject =
        when {
            state.over -> LifeRoadReject.GAME_OVER
            seat != state.currentSeat -> LifeRoadReject.NOT_YOUR_TURN
            else -> LifeRoadReject.NOT_IMPLEMENTED
        }

    override fun apply(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadStep =
        LifeRoadStep(state, emptyList(), explain(state, seat, action))

    override fun botAction(state: LifeRoadState, level: CpuDifficulty): LifeRoadAction = LifeRoadAction.Acknowledge

    override fun scoreCards(state: LifeRoadState): List<ScoreCard> {
        val cards = state.players.indices.map { LifeRoadScoring.card(state, it) }
        // TODO(P1): section 2.14 tie-breaks. The stub ranks by total only.
        val ranked = cards.sortedByDescending { it.total }
        return cards.map { c -> c.copy(place = ranked.indexOfFirst { it.total == c.total } + 1) }
    }
}
