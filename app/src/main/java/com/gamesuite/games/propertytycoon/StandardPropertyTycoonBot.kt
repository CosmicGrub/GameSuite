package com.gamesuite.games.propertytycoon

import com.gamesuite.settings.CpuDifficulty

/**
 * The computer players of Property Tycoon (design doc, "Bot behaviour scripts"). A bot only picks from
 * [PropertyTycoonEngine.legalCommands]; whatever it computes is checked against that list, and the fallback is the
 * safest progress command (roll, end the turn, decline, acknowledge), so a computer seat can never stall the table.
 * Randomness comes from a pure hash of the state (never from the dice stream), so a bot's thinking never moves the dice.
 */
object StandardPropertyTycoonBot : PropertyTycoonBot {

    private fun hash(state: TycoonState, seat: Int, salt: Int): Int {
        var z = state.rngState xor (state.round * 7919L) xor (seat * 104729L) xor (salt * 15485863L) xor state.holdings.size.toLong()
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return ((z ushr 1) % 100L).toInt()
    }

    override fun decide(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine): PtCommand {
        val legal = engine.legalCommands(state, seat)
        if (legal.isEmpty()) return PtCommand.EndTurn
        val pick = try { choose(state, seat, level, engine, legal) } catch (_: Exception) { null }
        if (pick != null && pick in legal) return pick
        return fallback(legal)
    }

    private fun fallback(legal: List<PtCommand>): PtCommand {
        for (c in listOf(PtCommand.Roll, PtCommand.EndTurn, PtCommand.DeclineBuy, PtCommand.AckCard, PtCommand.DeclareBankruptcy)) if (c in legal) return c
        return legal.first()
    }

    private fun ownsAll(state: TycoonState, seat: Int, group: ColorGroup) =
        PtBoard.tilesOf(group).all { t -> state.holding(t.index)?.owner == seat }

    /** Largest rent the next 12 tiles could ask of [seat] given today's buildings. */
    private fun exposure(state: TycoonState, seat: Int, engine: PropertyTycoonEngine): Int {
        val from = state.players[seat].position
        var worst = 0
        for (step in 1..12) {
            val i = (from + step) % PtRules.TILE_COUNT
            val h = state.holding(i) ?: continue
            if (h.owner != seat) worst = maxOf(worst, engine.rentFor(state, i, 7))
        }
        return worst
    }

    private fun reserve(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine): Int = when (level) {
        CpuDifficulty.EASY -> 50
        CpuDifficulty.MEDIUM -> 150 + exposure(state, seat, engine) / 10
        else -> maxOf(100, exposure(state, seat, engine) * 6 / 10)
    }

    private fun choose(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine, legal: List<PtCommand>): PtCommand? {
        val p = state.players[seat]
        return when (state.phase) {
            Phase.AWAIT_BUY -> {
                val tile = state.landedTile
                val price = PtBoard.tile(tile).price
                if (PtCommand.Buy !in legal) return PtCommand.DeclineBuy
                val r = reserve(state, seat, level, engine)
                val completes = PtBoard.tile(tile).group?.let { g ->
                    PtBoard.tilesOf(g).all { t -> t.index == tile || state.holding(t.index)?.owner == seat }
                } == true
                val buy = when (level) {
                    CpuDifficulty.EASY -> p.cash - price >= 50
                    else -> (tileValue(state, seat, tile, level) >= price && p.cash - price >= r) || completes
                }
                if (buy) PtCommand.Buy else PtCommand.DeclineBuy
            }
            Phase.AWAIT_CARD_ACK -> PtCommand.AckCard
            Phase.AWAIT_TIMEOUT_CHOICE -> {
                if (PtCommand.UseFreePassCard in legal) return PtCommand.UseFreePassCard
                val unowned = PtBoard.tiles.count { it.ownable && state.holding(it.index) == null }
                val payNow = when (level) {
                    CpuDifficulty.EASY -> p.timeoutTurns >= 1
                    CpuDifficulty.MEDIUM -> unowned >= 6
                    else -> {
                        val rivalBuilt = state.holdings.any { it.owner != seat && it.buildings > 0 }
                        val myBuilt = state.ownedBy(seat).any { it.buildings > 0 }
                        if (unowned >= 6) true else if (rivalBuilt && !myBuilt) false else myBuilt
                    }
                }
                if (payNow && PtCommand.PayFine in legal) PtCommand.PayFine else buildOrNull(state, seat, level, engine, legal) ?: PtCommand.Roll
            }
            Phase.AWAIT_ROLL -> buildOrNull(state, seat, level, engine, legal) ?: PtCommand.Roll
            Phase.AWAIT_POST_ROLL -> buildOrNull(state, seat, level, engine, legal) ?: PtCommand.EndTurn
            Phase.AWAIT_DEBT -> {
                val sells = legal.filterIsInstance<PtCommand.SellBuilding>()
                if (sells.isEmpty()) return PtCommand.DeclareBankruptcy
                if (level == CpuDifficulty.EASY) sells.maxByOrNull { PtBoard.tile(it.tile).buildCost } else sells.minByOrNull { lossPerPearl(state, it.tile) }
            }
            else -> null
        }
    }

    /** Rent lost per pearl raised when the top building of [tile] is sold (smaller is a cheaper sale). */
    private fun lossPerPearl(state: TycoonState, tile: Int): Double {
        val t = PtBoard.tile(tile)
        val lvl = state.holding(tile)?.buildings ?: return 0.0
        val lost = (t.rents[lvl] - t.rents[lvl - 1]).toDouble()
        return lost / (t.buildCost / 2.0).coerceAtLeast(1.0)
    }

    private fun buildOrNull(state: TycoonState, seat: Int, level: CpuDifficulty, engine: PropertyTycoonEngine, legal: List<PtCommand>): PtCommand? {
        val builds = legal.filterIsInstance<PtCommand.Build>()
        if (builds.isEmpty()) return null
        if (level == CpuDifficulty.EASY && hash(state, seat, 1) >= 30) return null
        val cash = state.players[seat].cash
        val r = reserve(state, seat, level, engine)
        val scored = builds.map { b ->
            val t = PtBoard.tile(b.tile)
            val lvl = state.holding(b.tile)?.buildings ?: 0
            val gain = (t.rents[lvl + 1] - t.rents[lvl]).toDouble() / t.buildCost
            // prefer reaching three cabins on a colour before four or a resort
            val bonus = if (lvl < 3) 0.5 else 0.0
            b to gain + bonus
        }.filter { (b, _) -> cash - PtBoard.tile(b.tile).buildCost >= (if (level == CpuDifficulty.EASY) 0 else r) }
        return scored.maxByOrNull { it.second }?.first
    }

    override fun tileValue(state: TycoonState, seat: Int, tile: Int, level: CpuDifficulty): Int {
        val t = PtBoard.tile(tile)
        if (level == CpuDifficulty.EASY) return t.price
        val owned = { i: Int -> state.holding(i)?.owner == seat }
        return when (t.type) {
            TileType.PROPERTY -> {
                val group = PtBoard.tilesOf(t.group!!)
                val others = group.filter { it.index != tile }
                val mine = others.count { owned(it.index) }
                var bonus = 0.15 * mine
                if (mine == others.size) bonus += 0.60
                if (level == CpuDifficulty.HARD && mine == 0 && others.all { state.holding(it.index) == null }) bonus += 0.20 * 0
                val rivals = others.mapNotNull { state.holding(it.index)?.owner }.filter { it != seat }.toSet()
                if (rivals.size == 1 && others.all { state.holding(it.index)?.owner == rivals.first() }) bonus += if (level == CpuDifficulty.HARD) 0.50 else 0.25
                if (level == CpuDifficulty.HARD) bonus += 0.20 * others.count { state.holding(it.index) == null && mine > 0 }
                (t.price * (1.0 + bonus)).toInt()
            }
            TileType.TRANSIT -> {
                val n = PtBoard.indexesOf(TileType.TRANSIT).count { owned(it) }
                (t.price * (1.0 + 0.25 * n)).toInt()
            }
            TileType.UTILITY -> {
                val other = PtBoard.indexesOf(TileType.UTILITY).any { it != tile && owned(it) }
                (t.price * if (other) 1.1 else 0.8).toInt()
            }
            else -> 0
        }
    }

    override fun tradeScore(state: TycoonState, seat: Int, trade: Trade, level: CpuDifficulty): Int {
        val (received, given) = if (trade.toSeat == seat) trade.give to trade.get else trade.get to trade.give
        val value = { side: TradeSide -> side.tiles.sumOf { tileValue(state, seat, it, level) } + side.cash }
        return value(received) - value(given)
    }
}
