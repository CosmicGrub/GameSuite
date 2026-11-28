package com.gamesuite.games.liferoad

import com.gamesuite.settings.CpuDifficulty

/**
 * The CPU players (docs section 2.17). Every choice is a pure function of the state, so a game is replayable and a
 * "think" never moves the spinner: the little randomness Easy needs comes from [LifeRoadRng]'s BOT stream keyed on
 * the state (turn number, phase, cash), never from a shared cursor. The result is always one of the legal actions.
 */
internal object LifeRoadBot {
    fun choose(engine: LifeRoadEngine, state: LifeRoadState, level: CpuDifficulty): LifeRoadAction {
        val legal = engine.legalActions(state, state.currentSeat)
        if (legal.isEmpty()) return LifeRoadAction.Acknowledge
        val pick = decide(engine, state, level, legal)
        return if (pick != null && pick in legal) pick else legal.first()
    }

    private fun roll(state: LifeRoadState, salt: Int, bound: Int): Int {
        val p = state.players[state.currentSeat]
        val key = state.turnNumber.toLong() * 977L + state.phase.ordinal * 31L + p.cash * 7L + salt * 13L + p.stepsMoved
        return LifeRoadRng.below(state.config.seed, RngStream.BOT, key, bound)
    }

    private fun decide(engine: LifeRoadEngine, state: LifeRoadState, levelIn: CpuDifficulty, legal: List<LifeRoadAction>): LifeRoadAction? {
        val me = state.players[state.currentSeat]
        val level = if (state.config.junior) CpuDifficulty.EASY else levelIn
        val easy = level == CpuDifficulty.EASY
        val hard = level == CpuDifficulty.HARD
        val junior = state.config.junior
        val totals = LifeRoadScoring.cards(state).map { it.total }
        val myTotal = totals[me.seat]
        val sorted = totals.sorted()
        val median = sorted[sorted.size / 2]
        val leading = myTotal >= totals.max()
        val trailing = myTotal < median
        val quick = state.config.size == RoadSize.QUICK
        return when (state.phase) {
            Phase.START_FORK, Phase.AWAIT_FORK -> {
                val branches = legal.filterIsInstance<LifeRoadAction.ChooseBranch>()
                fun first(prefix: String) = branches.firstOrNull { it.nodeId.startsWith(prefix) }
                val fork = if (state.phase == Phase.START_FORK) "S0" else state.pending.forkNodeId
                val other = { branches[roll(state, 1, branches.size)] }
                when (fork) {
                    "S0" -> when {
                        easy -> other()
                        quick -> first("WRK") ?: branches.first()
                        else -> first("LRN") ?: branches.first()
                    }
                    "F2" -> when {
                        junior -> first("SAFE")
                        easy -> other()
                        trailing || (hard && !leading && me.cash > 120) -> first("RISK")
                        else -> first("SAFE")
                    }
                    "F3" -> when {
                        easy -> other()
                        hard -> first("FAM")
                        me.familyPegs < 2 -> first("FAM")
                        else -> first("FREE")
                    }
                    else -> {
                        val grand = first("GRAND")
                        val homeValue = me.homes.sumOf { LifeRoadIndex.house.getValue(it.houseId).price }
                        val wantGrand = when {
                            grand == null -> false
                            easy -> me.cash >= 100 && roll(state, 2, 10) < 3
                            hard -> homeValue * 17 / 100 > LifeRoadRules.GRAND_FEE || (trailing && me.homes.isNotEmpty())
                            else -> me.cash >= 60
                        }
                        if (wantGrand) grand else first("COZY")
                    }
                }
            }
            Phase.AWAIT_RESPIN -> {
                val limit = if (hard) 4 else 3
                val use = LifeRoadAction.UseLuckyCoin in legal && !junior && state.pending.spin <= limit &&
                    (!easy || roll(state, 3, 4) == 0)
                if (use) LifeRoadAction.UseLuckyCoin else LifeRoadAction.KeepSpin
            }
            Phase.AWAIT_JOB -> {
                val offers = state.pending.offerIds.mapNotNull { LifeRoadIndex.job[it] }
                if (easy) {
                    val k = roll(state, 4, legal.size)
                    legal[k]
                } else {
                    fun value(j: JobDef) = j.salary + if (j.payRule == PayRule.TIPS) 11 else 0
                    val best = offers.maxByOrNull { value(it) }
                    val cur = me.jobId?.let { LifeRoadIndex.job[it] }
                    if (best == null) LifeRoadAction.PickJob(null)
                    else if (state.pending.mustPick || cur == null || value(best) >= value(cur) + 10) LifeRoadAction.PickJob(best.id)
                    else LifeRoadAction.PickJob(null)
                }
            }
            Phase.AWAIT_HOUSE -> {
                val buys = legal.filterIsInstance<LifeRoadAction.BuyHouse>().sortedBy { LifeRoadIndex.house.getValue(it.houseId).price }
                val owned = me.homes.size
                val choice: LifeRoadAction.BuyHouse? = when {
                    buys.isEmpty() -> null
                    easy -> if (roll(state, 5, 2) == 0) buys[roll(state, 6, buys.size)] else null
                    hard -> buys.filter { LifeRoadIndex.house.getValue(it.houseId).price <= me.cash - 20 + LifeRoadRules.LOAN_BLOCK }
                        .maxByOrNull { LifeRoadIndex.house.getValue(it.houseId).price }
                    else -> {
                        val after = if (owned == 0) 40 else 100
                        buys.firstOrNull {
                            val price = LifeRoadIndex.house.getValue(it.houseId).price
                            price >= 100 && me.cash - price >= after || (owned == 0 && price >= 100 && price - me.cash <= 50)
                        }
                    }
                }
                choice ?: LifeRoadAction.DeclineHouse
            }
            Phase.AWAIT_COVER -> {
                val want: CoverKind? = when {
                    easy -> if (roll(state, 7, 10) < 3) CoverKind.values().firstOrNull { it !in me.covers } else null
                    me.cash < 60 -> null
                    hard -> if (me.homes.isNotEmpty() && CoverKind.HOME !in me.covers) CoverKind.HOME else null
                    me.homes.isNotEmpty() && CoverKind.HOME !in me.covers -> CoverKind.HOME
                    me.familyPegs > 0 && CoverKind.CARE !in me.covers -> CoverKind.CARE
                    (me.jobId?.let { LifeRoadIndex.job[it]?.tier } ?: 0) >= 3 && CoverKind.INCOME !in me.covers -> CoverKind.INCOME
                    else -> null
                }
                val act = want?.let { LifeRoadAction.BuyCover(it) }
                if (act != null && act in legal) act else LifeRoadAction.DeclineCover
            }
            Phase.AWAIT_BANK -> {
                val threshold = if (hard) 140 else 160
                val repay = LifeRoadAction.RepayLoan in legal && me.cash >= threshold && (!easy || roll(state, 8, 2) == 0)
                if (repay) LifeRoadAction.RepayLoan else LifeRoadAction.Done
            }
            Phase.AWAIT_MARKET -> {
                val lead = myTotal - (totals.filterIndexed { i, _ -> i != me.seat }.maxOrNull() ?: 0)
                if (hard && lead >= 150) legal.firstOrNull { it is LifeRoadAction.SellHome } else LifeRoadAction.DeclineSale
            }
            else -> legal.first()
        }
    }
}
