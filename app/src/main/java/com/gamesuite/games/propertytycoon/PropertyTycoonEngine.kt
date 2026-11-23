package com.gamesuite.games.propertytycoon

import com.gamesuite.core.PlayerInfo

/**
 * A tiny deterministic generator (SplitMix64) whose whole state is one Long, so it lives inside
 * [TycoonState.rngState] and a restored game continues the exact same dice and shuffles. Dice, deck shuffles
 * and bot tie-breaks all draw from the state's stream, in the order the rules script defines.
 */
class PtRng(var state: Long) {
    fun nextLong(): Long {
        state += -7046029254386353131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** A value in 0 until [bound] (the tiny modulo bias is irrelevant at these bounds). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound.toLong()).toInt()
    }

    /** One six-sided die, 1..6. */
    fun rollDie(): Int = nextInt(6) + 1

    /** [items] in a seeded random order (Fisher-Yates). */
    fun <T> shuffled(items: List<T>): List<T> {
        val a = items.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }
}

/**
 * The rules engine contract. Pure and deterministic: no Compose, no Android, no wall clock. Every method
 * takes the state and returns a new one, so the same code serves the screen, the bots, the simulation harness
 * and (later) a host-authoritative network game.
 */
interface PropertyTycoonEngine {
    /** A fresh table for [roster] (2..6 seats). Seats are the roster indexes. */
    fun newGame(config: TycoonConfig, roster: List<PlayerInfo>): TycoonState

    /** Applies [command] for [seat]. A refusal returns the unchanged state with a [PtReject] and no events. */
    fun apply(state: TycoonState, seat: Int, command: PtCommand): PtResult

    /** Why [command] would be refused right now, or [PtReject.NONE] when it is legal. Backs the screen's "why" messages. */
    fun explain(state: TycoonState, seat: Int, command: PtCommand): PtReject

    /** Every command [seat] may issue right now. Never empty while the game is running and [seat] is the one to act. */
    fun legalCommands(state: TycoonState, seat: Int): List<PtCommand>

    /** The seat whose input the table is waiting for (the debtor, the bidder, the trade recipient, or the player on turn). */
    fun seatToAct(state: TycoonState): Int

    /** Rent a visitor would pay at [tile] given [diceTotal] (utilities), including the mode's rent percent and any escalator. 0 if none is due. */
    fun rentFor(state: TycoonState, tile: Int, diceTotal: Int): Int

    /** Cash + tiles at price (pledged at pledge value) + buildings at cost. Defines the winner of capped games. */
    fun netWorth(state: TycoonState, seat: Int): Int

    /** Cash that [seat] could raise right now by selling every building and pledging every tile. */
    fun liquidationValue(state: TycoonState, seat: Int): Int

    /** The winner's seat once [TycoonState.over], else -1. */
    fun winner(state: TycoonState): Int
}

/**
 * A safe stand-in so the module compiles and the screen can be wired before the rules exist. It deals a valid
 * opening table, accepts nothing ([PtReject.NOT_IMPLEMENTED]) and reports zeros. TODO(phase P0): replace with
 * the real engine; keep this class for the screen's previews.
 */
object StubPropertyTycoonEngine : PropertyTycoonEngine {
    override fun newGame(config: TycoonConfig, roster: List<PlayerInfo>): TycoonState {
        require(roster.size in PtRules.MIN_SEATS..PtRules.MAX_SEATS) { "Property Tycoon needs 2 to 6 seats" }
        val rng = PtRng(config.seed)
        val tide = rng.shuffled(PtCards.tide.map { it.id })
        val guild = rng.shuffled(PtCards.guild.map { it.id })
        val first = rng.nextInt(roster.size)
        val players = roster.mapIndexed { seat, p ->
            val bonus = if (seat == config.handicapSeat) config.startCash * config.handicapPercent / 100 else 0
            PlayerState(seat, p.playerId, p.displayName, p.isBot, cash = config.startCash + bonus)
        }
        return TycoonState(
            config = config, players = players, currentSeat = first,
            tideDraw = tide, guildDraw = guild, rngState = rng.state
        )
    }

    override fun apply(state: TycoonState, seat: Int, command: PtCommand): PtResult =
        PtResult(state, emptyList(), PtReject.NOT_IMPLEMENTED)

    override fun explain(state: TycoonState, seat: Int, command: PtCommand): PtReject = PtReject.NOT_IMPLEMENTED

    override fun legalCommands(state: TycoonState, seat: Int): List<PtCommand> = emptyList()

    override fun seatToAct(state: TycoonState): Int = state.currentSeat

    override fun rentFor(state: TycoonState, tile: Int, diceTotal: Int): Int = 0

    override fun netWorth(state: TycoonState, seat: Int): Int {
        val cash = state.players.getOrNull(seat)?.cash ?: return 0
        val assets = state.ownedBy(seat).sumOf { h ->
            val t = PtBoard.tile(h.tile)
            (if (h.pledged) t.pledgeValue else t.price) + h.buildings * t.buildCost
        }
        return cash + assets
    }

    override fun liquidationValue(state: TycoonState, seat: Int): Int {
        val cash = state.players.getOrNull(seat)?.cash ?: return 0
        val assets = state.ownedBy(seat).sumOf { h ->
            val t = PtBoard.tile(h.tile)
            (if (h.pledged) 0 else t.pledgeValue) + h.buildings * (t.buildCost / 2)
        }
        return cash + assets
    }

    override fun winner(state: TycoonState): Int = if (state.over) state.winnerSeat else -1
}
