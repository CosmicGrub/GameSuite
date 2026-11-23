package com.gamesuite.games.propertytycoon

import com.gamesuite.settings.CpuDifficulty

/**
 * The computer player contract (design doc, "Bot behaviour"). A bot only ever picks from
 * [PropertyTycoonEngine.legalCommands], so it can never break a rule; it must return a legal command in every
 * phase, so a computer seat can never stall the table.
 *
 * Level summary: EASY buys cheaply and never trades; MEDIUM values tiles by set progress, keeps a cash reserve,
 * builds evenly for return and accepts clearly favourable trades; HARD adds blocking and set-completion
 * valuation and proposes set-completing swaps.
 */
interface PropertyTycoonBot {
    /** The next command for [seat], chosen from the engine's legal commands. */
    fun decide(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine): PtCommand

    /** What [tile] is worth to [seat] in pearls under the level's valuation heuristic. */
    fun tileValue(state: TycoonState, seat: Int, tile: Int, level: CpuDifficulty): Int

    /** Net pearl value to [seat] of accepting [trade] (positive = good for [seat]). Used by MEDIUM and HARD only. */
    fun tradeScore(state: TycoonState, seat: Int, trade: Trade, level: CpuDifficulty): Int
}

/**
 * Safe placeholder: takes the first legal command (or ends the turn if the engine lists none), values every
 * tile at its price and scores every trade 0 (so nothing is ever accepted). TODO(phase P2): the real policies.
 */
object StubPropertyTycoonBot : PropertyTycoonBot {
    override fun decide(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine): PtCommand =
        engine.legalCommands(state, seat).firstOrNull() ?: PtCommand.EndTurn

    override fun tileValue(state: TycoonState, seat: Int, tile: Int, level: CpuDifficulty): Int = PtBoard.tile(tile).price

    override fun tradeScore(state: TycoonState, seat: Int, trade: Trade, level: CpuDifficulty): Int = 0
}
