package com.gamesuite.games.liferoad

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty

/** Id lookups over [LifeRoadContent], built once. */
internal object LifeRoadIndex {
    val job: Map<String, JobDef> by lazy { LifeRoadContent.jobs.associateBy { it.id } }
    val house: Map<String, HouseDef> by lazy { LifeRoadContent.houses.associateBy { it.id } }
    val event: Map<String, EventCardDef> by lazy { LifeRoadContent.events.associateBy { it.id } }
    val family: Map<String, FamilyCardDef> by lazy { LifeRoadContent.familyCards.associateBy { it.id } }
    val milestone: Set<String> by lazy { LifeRoadContent.milestones.map { it.id }.toSet() }

    const val WHEEL_CARD = "wheel"
    const val MS_PREFIX = "ms_"
}

/**
 * The Life Road rules engine (docs section 2). Pure: [apply] copies the state into a [Table], plays one action and
 * builds a new state. Random numbers come only from [LifeRoadRng] with the cursors held in the state.
 */
class StandardLifeRoadEngine : LifeRoadEngine {
    override fun graph(size: RoadSize): RoadGraph = LifeRoadBoard.of(size)

    override fun newGame(config: LifeRoadConfig, players: List<PlayerInfo>, opener: Int): LifeRoadState {
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
        val open = if (opener in players.indices) opener else 0
        var cursor = 0L
        fun shuffled(list: List<String>): List<String> {
            val a = list.toMutableList()
            for (i in a.size - 1 downTo 1) {
                val j = LifeRoadRng.below(config.seed, RngStream.DECK, cursor++, i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
            return a
        }
        val jobs = List(5) { tier -> shuffled(LifeRoadContent.jobs.filter { it.tier == tier + 1 }.map { it.id }) }
        val events = shuffled(LifeRoadContent.events.map { it.id })
        val family = shuffled(LifeRoadContent.familyCards.map { it.id })
        val houses = shuffled(LifeRoadContent.houses.map { it.id })
        return LifeRoadState(
            config = config, players = seats, currentSeat = open, phase = Phase.START_FORK, pending = PendingState(),
            decks = DeckState(jobs, events, family, houses), cursors = RngCursors(0L, cursor, 0L), openerSeat = open
        )
    }

    // ------------------------------------------------------------------------------ legality

    private fun forkNodeOf(state: LifeRoadState): String? = when (state.phase) {
        Phase.START_FORK -> LifeRoadBoardSpec.START_ID
        Phase.AWAIT_FORK -> state.pending.forkNodeId
        else -> null
    }

    /** The superset of actions the phase could take; [explain] then filters them. */
    private fun candidates(state: LifeRoadState): List<LifeRoadAction> {
        val p = state.players[state.currentSeat]
        val g = graph(state.config.size)
        return when (state.phase) {
            Phase.START_FORK, Phase.AWAIT_FORK ->
                (forkNodeId(state)?.let { g.node(it) }?.next ?: emptyList()).map { LifeRoadAction.ChooseBranch(it) }
            Phase.AWAIT_SPIN, Phase.SETTLING -> listOf(LifeRoadAction.Spin)
            Phase.AWAIT_RESPIN -> listOf(LifeRoadAction.UseLuckyCoin, LifeRoadAction.KeepSpin)
            Phase.AWAIT_JOB -> state.pending.offerIds.map { LifeRoadAction.PickJob(it) } + LifeRoadAction.PickJob(null)
            Phase.AWAIT_HOUSE -> state.pending.offerIds.map { LifeRoadAction.BuyHouse(it) } + LifeRoadAction.DeclineHouse
            Phase.AWAIT_COVER -> CoverKind.values().map { LifeRoadAction.BuyCover(it) } + LifeRoadAction.DeclineCover
            Phase.AWAIT_BANK -> listOf(LifeRoadAction.TakeLoan, LifeRoadAction.RepayLoan, LifeRoadAction.Done)
            Phase.AWAIT_MARKET -> p.homes.map { LifeRoadAction.SellHome(it.houseId) } + LifeRoadAction.DeclineSale
            Phase.AWAIT_CARD -> if (state.pending.needsSpin) listOf(LifeRoadAction.Spin) else listOf(LifeRoadAction.Acknowledge)
            else -> emptyList()
        }
    }

    private fun forkNodeId(state: LifeRoadState) = forkNodeOf(state)

    override fun legalActions(state: LifeRoadState, seat: Int): List<LifeRoadAction> {
        if (state.over || seat != state.currentSeat) return emptyList()
        return candidates(state).filter { explain(state, seat, it) == LifeRoadReject.NONE }
    }

    override fun explain(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadReject {
        if (state.over || state.phase == Phase.GAME_OVER) return LifeRoadReject.GAME_OVER
        if (seat != state.currentSeat) return LifeRoadReject.NOT_YOUR_TURN
        val p = state.players[seat]
        val junior = state.config.junior
        val inPhase = candidates(state).any { sameKind(it, action) }
        if (!inPhase) return LifeRoadReject.WRONG_PHASE
        return when (action) {
            is LifeRoadAction.ChooseBranch -> {
                val g = graph(state.config.size)
                val fork = forkNodeOf(state)?.let { g.node(it) } ?: return LifeRoadReject.WRONG_PHASE
                when {
                    action.nodeId !in fork.next -> LifeRoadReject.BAD_CHOICE
                    action.nodeId == "GRAND.0" && p.homes.isEmpty() -> LifeRoadReject.NEED_HOUSE_FOR_GRAND
                    else -> LifeRoadReject.NONE
                }
            }
            is LifeRoadAction.UseLuckyCoin -> if (p.luckyCoins <= 0 || state.luckyUsedThisTurn) LifeRoadReject.NO_LUCKY_COIN else LifeRoadReject.NONE
            is LifeRoadAction.PickJob ->
                if (action.jobId == null) (if (state.pending.mustPick) LifeRoadReject.BAD_CHOICE else LifeRoadReject.NONE)
                else if (action.jobId !in state.pending.offerIds) LifeRoadReject.BAD_CHOICE else LifeRoadReject.NONE
            is LifeRoadAction.BuyHouse -> {
                val h = LifeRoadIndex.house[action.houseId]
                when {
                    h == null || action.houseId !in state.pending.offerIds -> LifeRoadReject.HOUSE_NOT_AVAILABLE
                    p.homes.size >= LifeRoadRules.HOUSE_CAP -> LifeRoadReject.HOUSE_LIMIT
                    !canAfford(p, h.price, junior) -> LifeRoadReject.NOT_ENOUGH_COINS
                    else -> LifeRoadReject.NONE
                }
            }
            is LifeRoadAction.SellHome -> if (p.homes.none { it.houseId == action.houseId }) LifeRoadReject.BAD_CHOICE else LifeRoadReject.NONE
            is LifeRoadAction.BuyCover -> when {
                junior -> LifeRoadReject.JUNIOR_NO_LOANS
                action.kind in p.covers -> LifeRoadReject.BAD_CHOICE
                p.cash < LifeRoadRules.COVER_PRICE -> LifeRoadReject.NOT_ENOUGH_COINS
                else -> LifeRoadReject.NONE
            }
            is LifeRoadAction.TakeLoan -> when {
                junior -> LifeRoadReject.JUNIOR_NO_LOANS
                p.loans >= LifeRoadRules.MAX_LOANS -> LifeRoadReject.LOAN_LIMIT
                else -> LifeRoadReject.NONE
            }
            is LifeRoadAction.RepayLoan -> when {
                p.loans <= 0 -> LifeRoadReject.NOTHING_TO_REPAY
                p.cash < LifeRoadRules.LOAN_OWED -> LifeRoadReject.NOT_ENOUGH_COINS
                else -> LifeRoadReject.NONE
            }
            else -> LifeRoadReject.NONE
        }
    }

    private fun sameKind(a: LifeRoadAction, b: LifeRoadAction): Boolean = a::class == b::class

    private fun canAfford(p: PlayerState, price: Int, junior: Boolean): Boolean =
        if (junior) p.cash >= price else p.cash + (LifeRoadRules.MAX_LOANS - p.loans) * LifeRoadRules.LOAN_BLOCK >= price

    // ------------------------------------------------------------------------------ apply

    override fun apply(state: LifeRoadState, seat: Int, action: LifeRoadAction): LifeRoadStep {
        val why = explain(state, seat, action)
        if (why != LifeRoadReject.NONE) return LifeRoadStep(state, emptyList(), why)
        val t = Table(state, graph(state.config.size))
        t.act(action)
        return LifeRoadStep(t.build(), t.log)
    }

    override fun scoreCards(state: LifeRoadState): List<ScoreCard> = LifeRoadScoring.cards(state)

    override fun botAction(state: LifeRoadState, level: CpuDifficulty): LifeRoadAction = LifeRoadBot.choose(this, state, level)
}

/** One mutable working copy of a state for the duration of a single action. */
private class Table(val s: LifeRoadState, val g: RoadGraph) {
    val cfg = s.config
    val junior = cfg.junior
    val players = s.players.toMutableList()
    val jobs = s.decks.jobs.map { it.toMutableList() }
    val events = s.decks.events.toMutableList()
    val family = s.decks.family.toMutableList()
    val stock = s.decks.houseStock.toMutableList()
    var cSpin = s.cursors.spin
    var cDeck = s.cursors.deck
    val cBot = s.cursors.bot
    var phase = s.phase
    var pending = s.pending
    var currentSeat = s.currentSeat
    var turnNumber = s.turnNumber
    var retiredCount = s.retiredCount
    var luckyUsed = s.luckyUsedThisTurn
    var lastSpin = s.lastSpin
    var spinSerial = s.spinSerial
    var extraTurn = s.extraTurn
    var extraQueued = s.extraQueued
    var over = s.over
    val log = ArrayList<LifeRoadEvent>()

    fun build(): LifeRoadState {
        val st = LifeRoadState(
            config = cfg, players = players.toList(), currentSeat = currentSeat, phase = phase, pending = pending,
            decks = DeckState(jobs.map { it.toList() }, events.toList(), family.toList(), stock.toList()),
            cursors = RngCursors(cSpin, cDeck, cBot), turnNumber = turnNumber, retiredCount = retiredCount,
            luckyUsedThisTurn = luckyUsed, lastSpin = lastSpin, spinSerial = spinSerial, extraTurn = extraTurn,
            extraQueued = extraQueued, openerSeat = s.openerSeat, over = over
        )
        if (!over) return st
        val cards = LifeRoadScoring.cards(st)
        return st.copy(winnerSeats = cards.filter { it.place == 1 }.map { it.seat })
    }

    private fun spin(): Int = LifeRoadRng.spin(cfg.seed, cSpin++)
    private fun deckBelow(bound: Int): Int = LifeRoadRng.below(cfg.seed, RngStream.DECK, cDeck++, bound)
    private fun node(seat: Int): PathNode = g.node(players[seat].nodeId)!!
    private fun upd(seat: Int, f: (PlayerState) -> PlayerState) { players[seat] = f(players[seat]) }
    private fun emit(kind: LifeRoadEventKind, seat: Int, a: Int = 0, b: Int = 0, ref: String = "") { log += LifeRoadEvent(kind, seat, a, b, ref) }
    private fun scale(seat: Int, amount: Int) = if (node(seat).bold) LifeRoadRules.boldCoins(amount) else amount
    private fun active() = players.filter { !it.retired }

    // ------------------------------------------------------------------------------ actions

    fun act(a: LifeRoadAction) {
        val seat = currentSeat
        when (a) {
            is LifeRoadAction.Spin -> when (phase) {
                Phase.AWAIT_SPIN -> doSpin(seat)
                Phase.AWAIT_CARD -> resolveCard(seat, spin())
                Phase.SETTLING -> settleOne(seat)
                else -> {}
            }
            is LifeRoadAction.UseLuckyCoin -> {
                upd(seat) { it.copy(luckyCoins = it.luckyCoins - 1) }
                luckyUsed = true
                val n = spin()
                lastSpin = n; spinSerial++
                emit(LifeRoadEventKind.LUCKY_COIN, seat, n)
                startMove(seat, n)
            }
            is LifeRoadAction.KeepSpin -> startMove(seat, pending.spin)
            is LifeRoadAction.ChooseBranch -> choose(seat, a.nodeId)
            is LifeRoadAction.PickJob -> pickJob(seat, a.jobId)
            is LifeRoadAction.BuyHouse -> buyHouse(seat, a.houseId)
            is LifeRoadAction.DeclineHouse -> { stock.addAll(pending.offerIds); pending = PendingState(); finishTurn() }
            is LifeRoadAction.SellHome -> sellNow(seat, a.houseId)
            is LifeRoadAction.DeclineSale -> finishTurn()
            is LifeRoadAction.BuyCover -> {
                upd(seat) { it.copy(cash = it.cash - LifeRoadRules.COVER_PRICE, covers = it.covers + a.kind) }
                emit(LifeRoadEventKind.COVER_BOUGHT, seat, LifeRoadRules.COVER_PRICE, 0, a.kind.name)
                if (players[seat].covers.size >= CoverKind.values().size) finishTurn()
            }
            is LifeRoadAction.DeclineCover -> finishTurn()
            is LifeRoadAction.TakeLoan -> {
                upd(seat) { it.copy(cash = it.cash + LifeRoadRules.LOAN_BLOCK, loans = it.loans + 1) }
                emit(LifeRoadEventKind.LOAN, seat, 1)
            }
            is LifeRoadAction.RepayLoan -> {
                upd(seat) { it.copy(cash = it.cash - LifeRoadRules.LOAN_OWED, loans = it.loans - 1) }
                emit(LifeRoadEventKind.REPAY, seat, 1)
            }
            is LifeRoadAction.Done -> finishTurn()
            is LifeRoadAction.Acknowledge -> resolveCard(seat, 0)
        }
    }

    private fun doSpin(seat: Int) {
        var n = spin()
        val me = players[seat]
        if (cfg.catchUp && !extraTurn) {
            val lead = active().maxOf { it.stepsMoved }
            if (lead - me.stepsMoved >= cfg.size.catchUpGap) {
                n = maxOf(n, spin())
                emit(LifeRoadEventKind.CATCH_UP, seat)
            }
        }
        lastSpin = n; spinSerial++
        emit(LifeRoadEventKind.SPIN, seat, n)
        if (me.luckyCoins > 0 && !luckyUsed) {
            phase = Phase.AWAIT_RESPIN
            pending = PendingState(spin = n)
        } else startMove(seat, n)
    }

    private fun startMove(seat: Int, n: Int) {
        phase = Phase.MOVING
        pending = PendingState()
        advance(seat, n, true)
    }

    private fun choose(seat: Int, nodeId: String) {
        if (phase == Phase.START_FORK) {
            upd(seat) { it.copy(branch = nodeId) }
            if (players.all { it.branch != null }) {
                currentSeat = s.openerSeat
                beginTurn(currentSeat, true)
            } else {
                currentSeat = (seat + 1) % players.size
                pending = PendingState()
            }
            return
        }
        if (nodeId == "GRAND.0" && !junior) pay(seat, LifeRoadRules.GRAND_FEE)
        upd(seat) { it.copy(branch = nodeId) }
        val steps = pending.stepsLeft
        val resolve = !pending.noLanding
        pending = PendingState()
        if (steps > 0) {
            phase = Phase.MOVING
            advance(seat, steps, resolve)
        } else {
            phase = Phase.AWAIT_SPIN
        }
    }

    // ------------------------------------------------------------------------------ movement

    private fun advance(seat: Int, stepsIn: Int, resolve: Boolean) {
        var steps = stepsIn
        while (steps > 0) {
            val p = players[seat]
            val here = g.node(p.nodeId)!!
            if (here.next.isEmpty()) break
            val nextId: String
            if (here.next.size > 1) {
                val b = p.branch
                if (b == null || b !in here.next) {
                    phase = Phase.AWAIT_FORK
                    pending = PendingState(forkNodeId = here.id, stepsLeft = steps, noLanding = !resolve)
                    return
                }
                nextId = b
            } else nextId = here.next[0]
            steps--
            upd(seat) { it.copy(nodeId = nextId, stepsMoved = it.stepsMoved + 1, branch = null) }
            emit(LifeRoadEventKind.MOVE, seat, steps, 0, nextId)
            val n = g.node(nextId)!!
            val landed = steps == 0
            when (n.kind) {
                NodeKind.RETIRE -> { retire(seat, if (n.id == LifeRoadBoardSpec.GRAND_RETIRE_ID) RetireLane.GRAND else RetireLane.COZY); return }
                NodeKind.PAY -> payStop(seat, landed && resolve)
                NodeKind.FIRST_JOB -> if (steps > 0 || resolve) { firstJob(seat, steps, resolve); return }
                NodeKind.GRADUATION -> if (steps > 0 || resolve) { graduate(seat, steps, resolve); return }
                else -> {}
            }
        }
        if (resolve) land(seat, node(seat)) else { phase = Phase.MOVING; finishTurn() }
    }

    private fun resume(seat: Int) {
        val steps = pending.stepsLeft
        val resolve = !pending.noLanding
        pending = PendingState()
        if (steps > 0) { phase = Phase.MOVING; advance(seat, steps, resolve) } else finishTurn()
    }

    private fun moveBack(seat: Int, n: Int) {
        val here = node(seat)
        val idx = maxOf(0, here.index - n)
        if (idx == here.index || here.segment == here.id) return
        val id = "${here.segment}.$idx"
        if (!g.contains(id)) return
        upd(seat) { it.copy(nodeId = id, stepsMoved = maxOf(0, it.stepsMoved - (here.index - idx))) }
        emit(LifeRoadEventKind.MOVE, seat, -(here.index - idx), 0, id)
    }

    private fun payStop(seat: Int, landed: Boolean) {
        val p = players[seat]
        val job = p.jobId?.let { LifeRoadIndex.job[it] }
        var amt = (job?.salary ?: LifeRoadRules.ODD_JOBS) + LifeRoadRules.RAISE_STEP * p.raises
        if (job?.payRule == PayRule.TIPS) amt += LifeRoadRules.TIPS_PER_SPIN * spin()
        amt = when (p.nextPay) {
            NextPay.SKIP -> 0
            NextPay.HALVE -> amt / 2
            NextPay.DOUBLE -> amt * 2
            NextPay.NORMAL -> amt
        }
        amt = scale(seat, amt)
        if (landed) amt += scale(seat, LifeRoadRules.PAY_LANDING_BONUS)
        upd(seat) { it.copy(cash = it.cash + amt, nextPay = NextPay.NORMAL) }
        emit(LifeRoadEventKind.PASS_PAY, seat, amt, if (landed) 1 else 0)
    }

    // ------------------------------------------------------------------------------ jobs

    private fun tierOf(id: String): Int = LifeRoadIndex.job.getValue(id).tier

    private fun takeTop(tier: Int): String? = jobs[tier - 1].let { if (it.isEmpty()) null else it.removeAt(0) }

    private fun offerJobs(seat: Int, steps: Int, resolve: Boolean, mustPick: Boolean, tiers: List<Int>) {
        val offers = ArrayList<String>()
        for (t in tiers) takeTop(t)?.let { offers += it }
        if (offers.isEmpty()) {
            pending = PendingState(stepsLeft = steps, noLanding = !resolve)
            resume(seat)
            return
        }
        phase = Phase.AWAIT_JOB
        pending = PendingState(offerIds = offers, mustPick = mustPick, stepsLeft = steps, noLanding = !resolve)
    }

    private fun firstJob(seat: Int, steps: Int, resolve: Boolean) = offerJobs(seat, steps, resolve, true, listOf(1, 2))

    private fun graduate(seat: Int, steps: Int, resolve: Boolean) {
        if (!junior) pay(seat, LifeRoadRules.TUITION)
        upd(seat) { it.copy(degree = true) }
        emit(LifeRoadEventKind.DEGREE, seat)
        val tiers = if (jobs[3].size >= 3) listOf(4, 4, 4) else if (jobs[3].isNotEmpty()) List(jobs[3].size) { 4 } + listOf(5) else listOf(5, 5, 5)
        offerJobs(seat, steps, resolve, true, tiers)
    }

    private fun jobFair(seat: Int, steps: Int, resolve: Boolean) {
        val p = players[seat]
        val t = p.jobId?.let { tierOf(it) } ?: 1
        val cap = if (p.degree) 5 else 3
        offerJobs(seat, steps, resolve, false, listOf(t, minOf(t + 1, cap)))
    }

    private fun pickJob(seat: Int, id: String?) {
        val offers = pending.offerIds
        for (o in offers) if (o != id) jobs[tierOf(o) - 1].add(o)
        if (id != null) {
            val old = players[seat].jobId
            if (old != null) jobs[tierOf(old) - 1].add(old)
            upd(seat) { it.copy(jobId = id) }
            emit(LifeRoadEventKind.JOB, seat, tierOf(id), 0, id)
        }
        resume(seat)
    }

    // ------------------------------------------------------------------------------ money

    /** Pays [amount] by the payment rules (section 2.11); returns the coins actually paid. */
    fun pay(seat: Int, amount: Int): Int {
        if (amount <= 0) return 0
        if (junior) {
            val paid = minOf(players[seat].cash, amount)
            upd(seat) { it.copy(cash = it.cash - paid) }
            return paid
        }
        while (true) {
            val p = players[seat]
            if (p.cash >= amount) { upd(seat) { it.copy(cash = it.cash - amount) }; return amount }
            val room = LifeRoadRules.MAX_LOANS - p.loans
            if (room > 0) {
                val need = amount - p.cash
                val blocks = minOf(room, (need + LifeRoadRules.LOAN_BLOCK - 1) / LifeRoadRules.LOAN_BLOCK)
                upd(seat) { it.copy(cash = it.cash + blocks * LifeRoadRules.LOAN_BLOCK, loans = it.loans + blocks) }
                emit(LifeRoadEventKind.LOAN, seat, blocks)
                continue
            }
            if (p.homes.isNotEmpty()) {
                val h = p.homes.maxByOrNull { LifeRoadIndex.house.getValue(it.houseId).price }!!
                val price = LifeRoadIndex.house.getValue(h.houseId).price * LifeRoadRules.QUICK_SALE_PCT / 100
                upd(seat) { it.copy(cash = it.cash + price, homes = it.homes - h) }
                stock.add(h.houseId)
                emit(LifeRoadEventKind.HOUSE_SOLD, seat, price, 0, h.houseId)
                continue
            }
            val paid = p.cash
            upd(seat) { it.copy(cash = LifeRoadRules.FRESH_HELP, loans = 0, freshStarts = it.freshStarts + 1) }
            emit(LifeRoadEventKind.FRESH_START, seat)
            return paid
        }
    }

    private fun gain(seat: Int, amount: Int, scaled: Boolean = true) {
        val a = if (scaled) scale(seat, amount) else amount
        upd(seat) { it.copy(cash = it.cash + a) }
    }

    // ------------------------------------------------------------------------------ landing

    private fun land(seat: Int, n: PathNode) {
        emit(LifeRoadEventKind.LAND, seat, 0, 0, n.kind.key)
        phase = Phase.MOVING
        val p = players[seat]
        when (n.kind) {
            NodeKind.REST -> { gain(seat, LifeRoadRules.REST_COIN); finishTurn() }
            NodeKind.CAMPUS -> { gain(seat, LifeRoadRules.CAMPUS_COIN); finishTurn() }
            NodeKind.RAISE -> {
                if (p.raises < LifeRoadRules.MAX_RAISES) { upd(seat) { it.copy(raises = it.raises + 1) }; emit(LifeRoadEventKind.RAISE, seat) }
                finishTurn()
            }
            NodeKind.EVENT -> drawEvent(seat)
            NodeKind.FAMILY -> drawFamily(seat)
            NodeKind.JOB_FAIR -> jobFair(seat, 0, true)
            NodeKind.HOUSE_MARKET -> {
                if (p.homes.size >= LifeRoadRules.HOUSE_CAP || stock.isEmpty()) { finishTurn(); return }
                val offers = ArrayList<String>()
                repeat(minOf(3, stock.size)) { offers += stock.removeAt(0) }
                phase = Phase.AWAIT_HOUSE
                pending = PendingState(offerIds = offers)
            }
            NodeKind.MARKET_DAY -> if (p.homes.isEmpty()) finishTurn() else phase = Phase.AWAIT_MARKET
            NodeKind.MILESTONE -> milestone(seat)
            NodeKind.COVER_SHOP -> {
                if (junior) { gain(seat, 10, false); finishTurn() }
                else if (p.covers.size >= CoverKind.values().size) finishTurn()
                else phase = Phase.AWAIT_COVER
            }
            NodeKind.BANK -> if (junior) { gain(seat, 10, false); finishTurn() } else phase = Phase.AWAIT_BANK
            NodeKind.LUCKY_WHEEL -> { phase = Phase.AWAIT_CARD; pending = PendingState(cardId = LifeRoadIndex.WHEEL_CARD, needsSpin = true) }
            else -> finishTurn()
        }
    }

    private fun milestone(seat: Int) {
        val p = players[seat]
        val open = LifeRoadContent.milestones.map { it.id }.filter { it !in p.milestonesClaimed }
        if (open.isEmpty()) { gain(seat, LifeRoadRules.MILESTONE_BONUS); finishTurn(); return }
        val pick = open[deckBelow(open.size)]
        upd(seat) { it.copy(milestonesClaimed = it.milestonesClaimed + pick) }
        emit(LifeRoadEventKind.MEMORY, seat, 0, 0, pick)
        phase = Phase.AWAIT_CARD
        pending = PendingState(cardId = LifeRoadIndex.MS_PREFIX + pick, needsSpin = true)
    }

    private fun drawEvent(seat: Int) {
        val id = events.removeAt(0)
        events.add(id)
        val card = LifeRoadIndex.event.getValue(id)
        emit(LifeRoadEventKind.CARD, seat, 0, 0, id)
        phase = Phase.AWAIT_CARD
        pending = PendingState(cardId = id, cardIsFamily = false, needsSpin = card.effects.any { it.kind == EffectKind.SPIN_COINS })
    }

    private fun drawFamily(seat: Int) {
        val pegs = players[seat].familyPegs
        var tries = 0
        while (tries < family.size) {
            val id = family.removeAt(0)
            family.add(id)
            val card = LifeRoadIndex.family.getValue(id)
            if (card.effects.any { it.kind == EffectKind.FAMILY_PEG } && pegs >= LifeRoadRules.FAMILY_CAP) { tries++; continue }
            emit(LifeRoadEventKind.CARD, seat, 0, 0, id)
            phase = Phase.AWAIT_CARD
            pending = PendingState(cardId = id, cardIsFamily = true, needsSpin = card.effects.any { it.kind == EffectKind.SPIN_COINS })
            return
        }
        finishTurn()
    }

    // ------------------------------------------------------------------------------ cards

    private fun resolveCard(seat: Int, spin: Int) {
        val id = pending.cardId ?: run { finishTurn(); return }
        pending = PendingState()
        phase = Phase.MOVING
        if (id == LifeRoadIndex.WHEEL_CARD) {
            val coins = scale(seat, LifeRoadRules.wheelCoins(spin))
            upd(seat) { it.copy(cash = it.cash + coins) }
            lastSpin = spin; spinSerial++
            emit(LifeRoadEventKind.WHEEL, seat, spin, coins)
            finishTurn()
            return
        }
        if (id.startsWith(LifeRoadIndex.MS_PREFIX)) {
            lastSpin = spin; spinSerial++
            if (spin >= LifeRoadRules.MILESTONE_SPIN_GOAL) gain(seat, LifeRoadRules.MILESTONE_BONUS)
            finishTurn()
            return
        }
        val ev = LifeRoadIndex.event[id]
        val fam = LifeRoadIndex.family[id]
        val effects = ev?.effects ?: fam?.effects ?: emptyList()
        val cover = ev?.cover
        if (spin > 0) { lastSpin = spin; spinSerial++ }
        applyEffects(seat, effects, spin, cover)
    }

    private fun softLoss(amount: Int): Int {
        val half = (amount + 1) / 2
        return maxOf(5, (half + 4) / 5 * 5)
    }

    private fun applyEffects(seat: Int, effects: List<EventEffect>, spin: Int, cover: CoverKind?) {
        val saved = cover != null && cover in players[seat].covers
        var savedLogged = false
        fun saveNote() { if (!savedLogged) { savedLogged = true; emit(LifeRoadEventKind.COVER_SAVED, seat) } }
        var forward = 0
        var back = 0
        var fair = false
        for (e in effects) {
            when (e.kind) {
                EffectKind.COINS, EffectKind.SPIN_COINS -> {
                    val amt = if (e.kind == EffectKind.SPIN_COINS) e.amount * spin else e.amount
                    if (amt >= 0) gain(seat, amt)
                    else if (saved) saveNote()
                    else {
                        val loss = if (junior) softLoss(-amt) else -amt
                        pay(seat, scale(seat, loss))
                    }
                }
                EffectKind.EACH_OTHER_PAYS -> for (o in players.indices) if (o != seat && !players[o].retired) {
                    val x = minOf(players[o].cash, e.amount)
                    upd(o) { it.copy(cash = it.cash - x) }
                    upd(seat) { it.copy(cash = it.cash + x) }
                }
                EffectKind.PAY_EACH_OTHER -> {
                    val others = players.indices.filter { it != seat && !players[it].retired }
                    if (others.isNotEmpty()) {
                        val paid = pay(seat, e.amount * others.size)
                        for (o in others) upd(o) { it.copy(cash = it.cash + paid / others.size) }
                    }
                }
                EffectKind.ALL_COLLECT -> for (o in players.indices) if (!players[o].retired) upd(o) { it.copy(cash = it.cash + e.amount) }
                EffectKind.MOVE_FORWARD -> forward += e.amount
                EffectKind.MOVE_BACK -> back += e.amount
                EffectKind.SKIP_NEXT_PAY, EffectKind.HALVE_NEXT_PAY ->
                    if (saved) saveNote()
                    else if (junior) pay(seat, 10)
                    else upd(seat) { it.copy(nextPay = if (e.kind == EffectKind.SKIP_NEXT_PAY) NextPay.SKIP else NextPay.HALVE) }
                EffectKind.DOUBLE_NEXT_PAY -> upd(seat) { it.copy(nextPay = NextPay.DOUBLE) }
                EffectKind.MEMORY -> { upd(seat) { it.copy(cardMemories = it.cardMemories + e.amount) }; emit(LifeRoadEventKind.MEMORY, seat, e.amount) }
                EffectKind.LUCKY_COIN -> upd(seat) { it.copy(luckyCoins = minOf(9, it.luckyCoins + e.amount)) }
                EffectKind.EXTRA_TURN -> extraQueued = true
                EffectKind.RAISE -> if (players[seat].raises < LifeRoadRules.MAX_RAISES) {
                    upd(seat) { it.copy(raises = it.raises + 1) }; emit(LifeRoadEventKind.RAISE, seat)
                }
                EffectKind.JOB_FAIR -> fair = true
                EffectKind.FAMILY_PEG -> if (players[seat].familyPegs < LifeRoadRules.FAMILY_CAP) {
                    upd(seat) { it.copy(familyPegs = it.familyPegs + 1) }
                    emit(LifeRoadEventKind.FAMILY, seat, players[seat].familyPegs)
                }
            }
        }
        if (fair) { jobFair(seat, 0, true); return }
        if (back > 0) moveBack(seat, back)
        if (forward > 0) { advance(seat, forward, false); return }
        finishTurn()
    }

    // ------------------------------------------------------------------------------ houses and retirement

    private fun buyHouse(seat: Int, id: String) {
        val price = LifeRoadIndex.house.getValue(id).price
        val p = players[seat]
        if (p.cash < price) {
            val blocks = (price - p.cash + LifeRoadRules.LOAN_BLOCK - 1) / LifeRoadRules.LOAN_BLOCK
            upd(seat) { it.copy(cash = it.cash + blocks * LifeRoadRules.LOAN_BLOCK, loans = it.loans + blocks) }
            emit(LifeRoadEventKind.LOAN, seat, blocks)
        }
        upd(seat) { it.copy(cash = it.cash - price, homes = it.homes + HomeHolding(id, price)) }
        emit(LifeRoadEventKind.HOUSE_BOUGHT, seat, price, 0, id)
        for (o in pending.offerIds) if (o != id) stock.add(o)
        pending = PendingState()
        finishTurn()
    }

    private fun sellNow(seat: Int, id: String) {
        val p = players[seat]
        val h = p.homes.first { it.houseId == id }
        val roll = spin()
        val price = LifeRoadRules.marketValue(LifeRoadIndex.house.getValue(id).price, roll, junior)
        upd(seat) { it.copy(cash = it.cash + price, homes = it.homes - h) }
        stock.add(id)
        lastSpin = roll; spinSerial++
        emit(LifeRoadEventKind.HOUSE_SOLD, seat, price, roll, id)
        finishTurn()
    }

    private fun settleOne(seat: Int) {
        val i = pending.settleIndex
        val roll = spin()
        sellAtRetire(seat, i, roll)
        lastSpin = roll; spinSerial++
        if (i + 1 >= players[seat].homes.size) { pending = PendingState(); finishTurn() }
        else pending = PendingState(settleIndex = i + 1)
    }

    private fun sellAtRetire(seat: Int, index: Int, roll: Int) {
        val p = players[seat]
        val h = p.homes[index]
        val shift = if (p.lane == RetireLane.GRAND) LifeRoadRules.GRAND_SHIFT else 0
        val price = LifeRoadRules.marketValue(LifeRoadIndex.house.getValue(h.houseId).price, minOf(LifeRoadRules.SPIN_MAX, roll + shift), junior)
        val homes = p.homes.toMutableList()
        homes[index] = h.copy(soldFor = price)
        upd(seat) { it.copy(homes = homes) }
        emit(LifeRoadEventKind.HOUSE_SOLD, seat, price, roll, h.houseId)
    }

    private fun retire(seat: Int, lane: RetireLane) {
        val order = players.count { it.retireOrder > 0 } + 1
        upd(seat) {
            it.copy(
                retired = true, lane = lane, retireOrder = order, branch = null,
                cardMemories = it.cardMemories + if (lane == RetireLane.COZY) 1 else 0
            )
        }
        retiredCount++
        extraQueued = false
        pending = PendingState()
        emit(LifeRoadEventKind.RETIRE, seat, order)
        if (players[seat].homes.isNotEmpty()) phase = Phase.SETTLING else finishTurn()
    }

    private fun lastLight() {
        for (seat in players.indices) {
            if (players[seat].retired) continue
            upd(seat) { it.copy(retired = true, lane = RetireLane.LATE, retireOrder = 0, branch = null) }
            retiredCount++
            emit(LifeRoadEventKind.LATE_RETIRE, seat)
            for (i in players[seat].homes.indices) sellAtRetire(seat, i, spin())
        }
    }

    // ------------------------------------------------------------------------------ turn flow

    private fun beginTurn(seat: Int, counts: Boolean) {
        turnNumber++
        if (counts) upd(seat) { it.copy(turnsTaken = it.turnsTaken + 1) }
        val here = node(seat)
        if (here.next.size > 1 && players[seat].branch == null) {
            phase = Phase.AWAIT_FORK
            pending = PendingState(forkNodeId = here.id)
        } else {
            phase = Phase.AWAIT_SPIN
            pending = PendingState()
        }
    }

    private fun finishTurn() {
        luckyUsed = false
        pending = PendingState()
        if (players.all { it.retired }) { endGame(); return }
        if (extraQueued && !players[currentSeat].retired) {
            extraQueued = false
            extraTurn = true
            beginTurn(currentSeat, false)
            return
        }
        extraQueued = false
        extraTurn = false
        if (active().all { it.turnsTaken >= cfg.size.turnCapPerPlayer }) { lastLight(); endGame(); return }
        var next = currentSeat
        do { next = (next + 1) % players.size } while (players[next].retired)
        currentSeat = next
        beginTurn(next, true)
    }

    private fun endGame() {
        over = true
        phase = Phase.GAME_OVER
        pending = PendingState()
        emit(LifeRoadEventKind.GAME_OVER, currentSeat)
    }
}
