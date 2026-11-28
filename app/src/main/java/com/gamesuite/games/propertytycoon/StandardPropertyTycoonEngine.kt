package com.gamesuite.games.propertytycoon

import com.gamesuite.core.PlayerInfo

/**
 * The Property Tycoon rules engine (design doc, "Rules, numbered"; MVP scope: auctions, pledging and trades are gated off
 * by [PtRelease] and answer [PtReject.FEATURE_DISABLED]). Pure and deterministic: every command copies the state into a
 * [PtTable], plays it and builds a new state; dice and shuffles come only from [TycoonState.rngState].
 */
object StandardPropertyTycoonEngine : PropertyTycoonEngine {

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
            config = config, players = players, currentSeat = first, tideDraw = tide, guildDraw = guild, rngState = rng.state,
            phase = Phase.AWAIT_ROLL
        )
    }

    override fun seatToAct(state: TycoonState): Int = state.debt?.debtor?.takeIf { state.phase == Phase.AWAIT_DEBT } ?: state.currentSeat

    // ------------------------------------------------------------------------------ queries

    override fun rentFor(state: TycoonState, tile: Int, diceTotal: Int): Int {
        val h = state.holding(tile) ?: return 0
        if (h.pledged) return 0
        val t = PtBoard.tile(tile)
        val base: Int = when (t.type) {
            TileType.PROPERTY -> {
                var r = t.rents[h.buildings]
                if (h.buildings == 0) {
                    val group = PtBoard.tilesOf(t.group!!)
                    if (group.all { g -> state.holding(g.index)?.let { it.owner == h.owner && !it.pledged } == true }) r *= 2
                }
                r
            }
            TileType.TRANSIT -> {
                val n = PtBoard.indexesOf(TileType.TRANSIT).count { i -> state.holding(i)?.let { it.owner == h.owner && !it.pledged } == true }
                PtRules.TRANSIT_RENTS[(n - 1).coerceIn(0, 3)]
            }
            TileType.UTILITY -> {
                val n = PtBoard.indexesOf(TileType.UTILITY).count { i -> state.holding(i)?.let { it.owner == h.owner && !it.pledged } == true }
                PtRules.UTILITY_MULTIPLIERS[(n - 1).coerceIn(0, 1)] * diceTotal
            }
            else -> return 0
        }
        return scaled(state, base)
    }

    /** Rent after the mode's percent and the Rising Tide factor, rounded half up, at least 1. */
    internal fun scaled(state: TycoonState, base: Int): Int {
        val pct = state.config.length.rentPercent.toLong()
        val tide = 100L + tidePercent(state)
        val r = (base * pct * tide + 5000L) / 10000L
        return maxOf(1L, r).toInt()
    }

    internal fun tidePercent(state: TycoonState): Int {
        val start = state.config.length.escalatorStartRound
        if (start < 0 || state.round < start + 1) return 0
        return minOf(PtRules.ESCALATOR_CAP_PERCENT, (state.round - start) * PtRules.ESCALATOR_PERCENT_PER_ROUND)
    }

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
            val pledge = if (PtRelease.PLEDGING_ENABLED && !h.pledged && h.buildings == 0) t.pledgeValue else 0
            pledge + h.buildings * (t.buildCost / 2)
        }
        return cash + assets
    }

    override fun winner(state: TycoonState): Int = if (state.over) state.winnerSeat else -1

    // ------------------------------------------------------------------------------ legality

    private fun managementPhase(p: Phase) = p == Phase.AWAIT_ROLL || p == Phase.AWAIT_TIMEOUT_CHOICE || p == Phase.AWAIT_POST_ROLL

    override fun explain(state: TycoonState, seat: Int, command: PtCommand): PtReject {
        if (state.over || state.phase == Phase.GAME_OVER) return PtReject.WRONG_PHASE
        if (seat != seatToAct(state)) return PtReject.NOT_YOUR_TURN
        val p = state.players[seat]
        return when (command) {
            is PtCommand.Roll -> if (state.phase == Phase.AWAIT_ROLL || state.phase == Phase.AWAIT_TIMEOUT_CHOICE) PtReject.NONE else wrong(state)
            is PtCommand.PayFine -> when {
                state.phase != Phase.AWAIT_TIMEOUT_CHOICE -> wrong(state)
                p.cash < PtRules.TIMEOUT_FINE -> PtReject.CANNOT_AFFORD
                else -> PtReject.NONE
            }
            is PtCommand.UseFreePassCard -> when {
                state.phase != Phase.AWAIT_TIMEOUT_CHOICE -> wrong(state)
                p.passCardIds.isEmpty() -> PtReject.WRONG_PHASE
                else -> PtReject.NONE
            }
            is PtCommand.Buy -> when {
                state.phase != Phase.AWAIT_BUY -> wrong(state)
                p.cash < PtBoard.tile(state.landedTile).price -> PtReject.CANNOT_AFFORD
                else -> PtReject.NONE
            }
            is PtCommand.DeclineBuy -> if (state.phase == Phase.AWAIT_BUY) PtReject.NONE else wrong(state)
            is PtCommand.AckCard -> if (state.phase == Phase.AWAIT_CARD_ACK) PtReject.NONE else wrong(state)
            is PtCommand.EndTurn -> if (state.phase == Phase.AWAIT_POST_ROLL) PtReject.NONE else wrong(state)
            is PtCommand.Build -> when {
                !managementPhase(state.phase) -> if (state.phase == Phase.AWAIT_DEBT) PtReject.IN_DEBT else wrong(state)
                else -> checkBuild(state, seat, command.tile)
            }
            is PtCommand.SellBuilding -> when {
                !managementPhase(state.phase) && state.phase != Phase.AWAIT_DEBT -> wrong(state)
                else -> checkSell(state, seat, command.tile)
            }
            is PtCommand.DeclareBankruptcy -> if (state.phase == Phase.AWAIT_DEBT) PtReject.NONE else wrong(state)
            is PtCommand.Pledge, is PtCommand.Redeem, is PtCommand.Bid, is PtCommand.PassAuction,
            is PtCommand.ProposeTrade, is PtCommand.RespondTrade, is PtCommand.CounterTrade, is PtCommand.CancelTrade -> PtReject.FEATURE_DISABLED
        }
    }

    private fun wrong(state: TycoonState) = PtReject.WRONG_PHASE

    private fun groupLevels(state: TycoonState, group: ColorGroup): List<Int> = PtBoard.tilesOf(group).map { state.holding(it.index)?.buildings ?: 0 }

    private fun checkBuild(state: TycoonState, seat: Int, tile: Int): PtReject {
        val t = PtBoard.tiles.getOrNull(tile) ?: return PtReject.WRONG_PHASE
        val h = state.holding(tile)
        if (t.type != TileType.PROPERTY || h == null || h.owner != seat) return PtReject.WRONG_PHASE
        val group = PtBoard.tilesOf(t.group!!)
        val all = group.all { g -> state.holding(g.index)?.let { it.owner == seat && !it.pledged } == true }
        if (!all) return PtReject.SET_INCOMPLETE
        if (h.buildings >= PtRules.RESORT_LEVEL) return PtReject.UNEVEN_BUILD
        val levels = groupLevels(state, t.group)
        if (h.buildings != levels.min()) return PtReject.UNEVEN_BUILD
        if (h.buildings == PtRules.CABINS_PER_TILE) {
            if (state.resortsLeft <= 0) return PtReject.SUPPLY_EMPTY
        } else if (state.cabinsLeft <= 0) return PtReject.SUPPLY_EMPTY
        if (state.players[seat].cash < t.buildCost) return PtReject.CANNOT_AFFORD
        return PtReject.NONE
    }

    private fun checkSell(state: TycoonState, seat: Int, tile: Int): PtReject {
        val t = PtBoard.tiles.getOrNull(tile) ?: return PtReject.WRONG_PHASE
        val h = state.holding(tile)
        if (t.type != TileType.PROPERTY || h == null || h.owner != seat || h.buildings == 0) return PtReject.WRONG_PHASE
        if (h.buildings != groupLevels(state, t.group!!).max()) return PtReject.UNEVEN_SELL
        return PtReject.NONE
    }

    override fun legalCommands(state: TycoonState, seat: Int): List<PtCommand> {
        if (state.over || seat != seatToAct(state)) return emptyList()
        val out = ArrayList<PtCommand>()
        fun add(c: PtCommand) { if (explain(state, seat, c) == PtReject.NONE) out += c }
        val tiles = state.ownedBy(seat).map { it.tile }
        when (state.phase) {
            Phase.AWAIT_ROLL -> { add(PtCommand.Roll); tiles.forEach { add(PtCommand.Build(it)); add(PtCommand.SellBuilding(it)) } }
            Phase.AWAIT_TIMEOUT_CHOICE -> {
                add(PtCommand.Roll); add(PtCommand.PayFine); add(PtCommand.UseFreePassCard)
                tiles.forEach { add(PtCommand.Build(it)); add(PtCommand.SellBuilding(it)) }
            }
            Phase.AWAIT_BUY -> { add(PtCommand.Buy); add(PtCommand.DeclineBuy) }
            Phase.AWAIT_CARD_ACK -> add(PtCommand.AckCard)
            Phase.AWAIT_POST_ROLL -> { add(PtCommand.EndTurn); tiles.forEach { add(PtCommand.Build(it)); add(PtCommand.SellBuilding(it)) } }
            Phase.AWAIT_DEBT -> {
                val d = state.debt
                if (d != null && liquidationValue(state, seat) >= d.amount) tiles.forEach { add(PtCommand.SellBuilding(it)) }
                add(PtCommand.DeclareBankruptcy)
            }
            else -> {}
        }
        return out
    }

    override fun apply(state: TycoonState, seat: Int, command: PtCommand): PtResult {
        val why = explain(state, seat, command)
        if (why != PtReject.NONE) return PtResult(state, emptyList(), why)
        val t = PtTable(state)
        t.run(command)
        return PtResult(t.build(), t.events)
    }
}

/** One mutable working copy of a [TycoonState] for a single command. */
internal class PtTable(private val s: TycoonState) {
    private val cfg = s.config
    private val rules = cfg.houseRules
    val players = s.players.toMutableList()
    val holdings = s.holdings.associateBy { it.tile }.toMutableMap()
    val rng = PtRng(s.rngState)
    val events = ArrayList<PtEvent>()
    var currentSeat = s.currentSeat
    var round = s.round
    var phase = s.phase
    var stack = s.phaseStack
    var d1 = s.dice1
    var d2 = s.dice2
    var extraRoll = s.extraRollPending
    var noExtra = s.noExtraRoll
    var pendingMove = s.pendingMove
    var landed = s.landedTile
    var cardId = s.pendingCardId
    val tideDraw = s.tideDraw.toMutableList()
    val tideDiscard = s.tideDiscard.toMutableList()
    val guildDraw = s.guildDraw.toMutableList()
    val guildDiscard = s.guildDiscard.toMutableList()
    var cabinsLeft = s.cabinsLeft
    var resortsLeft = s.resortsLeft
    var jackpot = s.jackpot
    var debt = s.debt
    var rollSerial = s.rollSerial
    var over = s.over
    var winner = s.winnerSeat

    fun build(): TycoonState = s.copy(
        players = players.toList(), holdings = holdings.values.sortedBy { it.tile }, currentSeat = currentSeat, round = round, phase = phase,
        phaseStack = stack, dice1 = d1, dice2 = d2, extraRollPending = extraRoll, noExtraRoll = noExtra, pendingMove = pendingMove,
        landedTile = landed, pendingCardId = cardId, tideDraw = tideDraw, tideDiscard = tideDiscard, guildDraw = guildDraw,
        guildDiscard = guildDiscard, cabinsLeft = cabinsLeft, resortsLeft = resortsLeft, jackpot = jackpot, debt = debt,
        rngState = rng.state, rollSerial = rollSerial, eventSerial = s.eventSerial + 1, over = over, winnerSeat = winner
    )

    private fun upd(seat: Int, f: (PlayerState) -> PlayerState) { players[seat] = f(players[seat]) }
    private fun ev(e: PtEvent) { events += e }
    private fun active() = players.filter { !it.bankrupt }.map { it.seat }

    private fun view(): TycoonState = build()
    private fun rent(tile: Int, dice: Int): Int = StandardPropertyTycoonEngine.rentFor(view(), tile, dice)
    private fun scaled(base: Int): Int = StandardPropertyTycoonEngine.scaled(view(), base)

    // ------------------------------------------------------------------------------ commands

    fun run(c: PtCommand) {
        val seat = StandardPropertyTycoonEngine.seatToAct(s)
        when (c) {
            is PtCommand.Roll -> roll(seat)
            is PtCommand.PayFine -> {
                upd(seat) { it.copy(cash = it.cash - PtRules.TIMEOUT_FINE, inTimeout = false, timeoutTurns = 0) }
                toJackpotOrBank(PtRules.TIMEOUT_FINE)
                ev(PtEvent.TimeoutLeft(seat))
                noExtra = true
                phase = Phase.AWAIT_ROLL
            }
            is PtCommand.UseFreePassCard -> {
                val id = players[seat].passCardIds.first()
                upd(seat) { it.copy(passCardIds = it.passCardIds.drop(1), freePassCards = it.passCardIds.size - 1, inTimeout = false, timeoutTurns = 0) }
                if (id.startsWith("t")) tideDraw += id else guildDraw += id
                ev(PtEvent.TimeoutLeft(seat))
                noExtra = true
                phase = Phase.AWAIT_ROLL
            }
            is PtCommand.Buy -> {
                val t = PtBoard.tile(landed)
                upd(seat) { it.copy(cash = it.cash - t.price) }
                holdings[landed] = Holding(landed, seat)
                ev(PtEvent.Bought(seat, landed, t.price))
                settle()
            }
            is PtCommand.DeclineBuy -> settle()
            is PtCommand.AckCard -> applyCard(seat)
            is PtCommand.Build -> build(seat, c.tile)
            is PtCommand.SellBuilding -> sellBuilding(seat, c.tile)
            is PtCommand.EndTurn -> endTurn()
            is PtCommand.DeclareBankruptcy -> bankrupt(seat)
            else -> {}
        }
    }

    // ------------------------------------------------------------------------------ dice and movement

    private fun roll(seat: Int) {
        val a = rng.rollDie(); val b = rng.rollDie()
        d1 = a; d2 = b; rollSerial++
        ev(PtEvent.Rolled(seat, a, b))
        val doubles = a == b
        val p = players[seat]
        if (p.inTimeout) {
            if (doubles) {
                upd(seat) { it.copy(inTimeout = false, timeoutTurns = 0, doublesStreak = 0) }
                ev(PtEvent.TimeoutLeft(seat))
                extraRoll = false
                move(seat, a + b, true)
                return
            }
            val failed = p.timeoutTurns + 1
            if (failed >= PtRules.MAX_TIMEOUT_TURNS) {
                upd(seat) { it.copy(inTimeout = false, timeoutTurns = 0) }
                ev(PtEvent.TimeoutLeft(seat))
                extraRoll = false
                pendingMove = a + b
                if (!charge(seat, -1, PtRules.TIMEOUT_FINE, DebtReason.FINE, toJackpot = true)) {
                    phase = Phase.AWAIT_DEBT; stack = listOf(Phase.AWAIT_POST_ROLL)
                    return
                }
                val steps = pendingMove; pendingMove = 0
                move(seat, steps, true)
            } else {
                upd(seat) { it.copy(timeoutTurns = failed) }
                endTurn()
            }
            return
        }
        val streak = if (doubles && !noExtra) p.doublesStreak + 1 else 0
        if (streak >= PtRules.MAX_DOUBLES) {
            upd(seat) { it.copy(doublesStreak = 0) }
            goToTimeout(seat)
            endTurn()
            return
        }
        upd(seat) { it.copy(doublesStreak = streak) }
        extraRoll = doubles && !noExtra
        move(seat, a + b, true)
    }

    /** Walks [steps] forward from the token, pays the stipend for passing the Quay, then resolves the tile. */
    private fun move(seat: Int, steps: Int, resolve: Boolean, collect: Boolean = true) {
        val from = players[seat].position
        val total = from + steps
        val to = Math.floorMod(total, PtRules.TILE_COUNT)
        val passed = collect && steps > 0 && total >= PtRules.TILE_COUNT
        upd(seat) { it.copy(position = to) }
        ev(PtEvent.Moved(seat, from, to, passed))
        if (passed) {
            val amount = if (rules.doubleStipendOnLanding && to == 0) PtRules.SALARY * 2 else PtRules.SALARY
            upd(seat) { it.copy(cash = it.cash + amount) }
            ev(PtEvent.StipendPaid(seat, amount))
        }
        landed = to
        if (resolve) land(seat, to, d1 + d2, 1)
    }

    private fun goToTimeout(seat: Int) {
        val from = players[seat].position
        upd(seat) { it.copy(position = PtRules.TIMEOUT_INDEX, inTimeout = true, timeoutTurns = 0, doublesStreak = 0) }
        ev(PtEvent.Moved(seat, from, PtRules.TIMEOUT_INDEX, false))
        ev(PtEvent.TimeoutEntered(seat))
        extraRoll = false
    }

    // ------------------------------------------------------------------------------ landing

    private fun land(seat: Int, tile: Int, dice: Int, rentMult: Int, diceMult: Int = 0) {
        val t = PtBoard.tile(tile)
        landed = tile
        when (t.type) {
            TileType.PROPERTY, TileType.TRANSIT, TileType.UTILITY -> {
                val h = holdings[tile]
                when {
                    h == null -> { phase = Phase.AWAIT_BUY; return }
                    h.owner == seat || h.pledged -> {}
                    players[h.owner].inTimeout && rules.rentFreeTimeout -> {}
                    else -> {
                        var amount = rent(tile, dice)
                        if (t.type == TileType.UTILITY && diceMult > 0) amount = scaled(diceMult * dice)
                        else if (rentMult > 1) amount = amount * rentMult
                        ev(PtEvent.RentPaid(seat, h.owner, tile, amount))
                        upd(seat) { it.copy(rentPaid = it.rentPaid + amount) }
                        upd(h.owner) { it.copy(rentCollected = it.rentCollected + amount) }
                        charge(seat, h.owner, amount, DebtReason.RENT)
                    }
                }
            }
            TileType.TAX -> { ev(PtEvent.TaxPaid(seat, t.amount)); charge(seat, -1, t.amount, DebtReason.TAX, toJackpot = true) }
            TileType.TIDE_CARD, TileType.GUILD_CARD -> {
                val tide = t.type == TileType.TIDE_CARD
                val draw = if (tide) tideDraw else guildDraw
                val discard = if (tide) tideDiscard else guildDiscard
                if (draw.isEmpty()) { draw += rng.shuffled(discard.toList()); discard.clear() }
                if (draw.isEmpty()) { settle(); return }
                cardId = draw.removeAt(0)
                ev(PtEvent.CardDrawn(seat, cardId!!))
                phase = Phase.AWAIT_CARD_ACK
                return
            }
            TileType.GO_TO_TIMEOUT -> { goToTimeout(seat); endTurn(); return }
            TileType.FREE_ANCHORAGE -> if (rules.jackpot && jackpot > 0) {
                upd(seat) { it.copy(cash = it.cash + jackpot) }
                ev(PtEvent.JackpotTaken(seat, jackpot))
                jackpot = 0
            }
            else -> {}
        }
        settle()
    }

    private fun toJackpotOrBank(amount: Int) { if (rules.jackpot) jackpot += amount }

    /**
     * Pays [amount] from [seat] to [creditor] (-1 = the bank). Returns true when paid. When the cash is short a DEBT
     * interrupt is opened (the caller must stop and let [settle] sort the phase).
     */
    private fun charge(seat: Int, creditor: Int, amount: Int, reason: DebtReason, toJackpot: Boolean = false): Boolean {
        if (amount <= 0) return true
        if (players[seat].cash >= amount) {
            upd(seat) { it.copy(cash = it.cash - amount) }
            if (creditor >= 0) upd(creditor) { it.copy(cash = it.cash + amount) } else if (toJackpot) toJackpotOrBank(amount)
            return true
        }
        debt = DebtState(seat, creditor, amount, reason)
        ev(PtEvent.DebtOpened(seat, creditor, amount))
        return false
    }

    /** After a resolution: open the debt phase if one is pending, otherwise wait for the next roll or the end of the turn. */
    private fun settle() {
        val cont = if (extraRoll) Phase.AWAIT_ROLL else Phase.AWAIT_POST_ROLL
        if (debt != null) { stack = listOf(cont); phase = Phase.AWAIT_DEBT } else { stack = emptyList(); phase = cont }
        if (phase == Phase.AWAIT_ROLL && players[currentSeat].inTimeout) phase = Phase.AWAIT_TIMEOUT_CHOICE
    }

    // ------------------------------------------------------------------------------ cards

    private fun applyCard(seat: Int) {
        val id = cardId ?: run { settle(); return }
        val card = PtCards.byId.getValue(id)
        cardId = null
        val discard = if (card.deck == DeckKind.TIDE) tideDiscard else guildDiscard
        val e = card.effect
        if (e !is CardEffect.GetOutFree) discard += id
        when (e) {
            is CardEffect.Gain -> { upd(seat) { it.copy(cash = it.cash + e.amount) }; settle() }
            is CardEffect.Pay -> { charge(seat, -1, e.amount, DebtReason.CARD, toJackpot = true); settle() }
            is CardEffect.GetOutFree -> {
                upd(seat) { it.copy(passCardIds = it.passCardIds + id, freePassCards = it.passCardIds.size + 1) }
                settle()
            }
            is CardEffect.GoToTimeout -> { goToTimeout(seat); endTurn() }
            is CardEffect.MoveTo -> {
                val from = players[seat].position
                var steps = Math.floorMod(e.tile - from, PtRules.TILE_COUNT)
                if (steps == 0) steps = PtRules.TILE_COUNT
                move(seat, steps, true, e.collectStipend)
            }
            is CardEffect.MoveBy -> {
                val from = players[seat].position
                val to = Math.floorMod(from + e.steps, PtRules.TILE_COUNT)
                if (e.steps < 0) {
                    upd(seat) { it.copy(position = to) }
                    ev(PtEvent.Moved(seat, from, to, false))
                    land(seat, to, d1 + d2, 1)
                } else move(seat, e.steps, true)
            }
            is CardEffect.MoveToNearest -> {
                val to = PtBoard.nearestAhead(players[seat].position, e.type)
                val from = players[seat].position
                val steps = Math.floorMod(to - from, PtRules.TILE_COUNT)
                move(seat, steps, false)
                if (e.type == TileType.UTILITY) {
                    val a = rng.rollDie(); val b = rng.rollDie(); d1 = a; d2 = b; rollSerial++
                    ev(PtEvent.Rolled(seat, a, b))
                    land(seat, to, a + b, 1, e.diceMultiplier)
                } else land(seat, to, d1 + d2, e.rentMultiplier)
            }
            is CardEffect.PayEachRival -> {
                val rivals = active().filter { it != seat }
                val total = e.amount * rivals.size
                if (players[seat].cash >= total) {
                    upd(seat) { it.copy(cash = it.cash - total) }
                    for (r in rivals) upd(r) { it.copy(cash = it.cash + e.amount) }
                } else {
                    debt = DebtState(seat, -1, total, DebtReason.RIVALS)
                    ev(PtEvent.DebtOpened(seat, -1, total))
                }
                settle()
            }
            is CardEffect.CollectFromEachRival -> {
                for (r in active().filter { it != seat }) {
                    val x = minOf(players[r].cash, e.amount)
                    upd(r) { it.copy(cash = it.cash - x) }
                    upd(seat) { it.copy(cash = it.cash + x) }
                }
                settle()
            }
            is CardEffect.Repairs -> {
                var total = 0
                for (h in holdings.values.filter { it.owner == seat }) {
                    total += if (h.buildings == PtRules.RESORT_LEVEL) e.perResort else h.buildings * e.perCabin
                }
                charge(seat, -1, total, DebtReason.REPAIRS, toJackpot = true)
                settle()
            }
        }
    }

    // ------------------------------------------------------------------------------ buildings

    private fun build(seat: Int, tile: Int) {
        val t = PtBoard.tile(tile)
        val h = holdings.getValue(tile)
        upd(seat) { it.copy(cash = it.cash - t.buildCost) }
        if (h.buildings == PtRules.CABINS_PER_TILE) { resortsLeft--; cabinsLeft += PtRules.CABINS_PER_TILE } else cabinsLeft--
        holdings[tile] = h.copy(buildings = h.buildings + 1)
        ev(PtEvent.Built(seat, tile, h.buildings + 1))
    }

    private fun sellBuilding(seat: Int, tile: Int) {
        val t = PtBoard.tile(tile)
        val h = holdings.getValue(tile)
        if (h.buildings == PtRules.RESORT_LEVEL) {
            val k = minOf(PtRules.CABINS_PER_TILE, cabinsLeft)
            cabinsLeft -= k; resortsLeft++
            val gain = (PtRules.RESORT_LEVEL - k) * t.buildCost / 2
            upd(seat) { it.copy(cash = it.cash + gain) }
            holdings[tile] = h.copy(buildings = k)
            ev(PtEvent.BuildingSold(seat, tile, k))
        } else {
            cabinsLeft++
            upd(seat) { it.copy(cash = it.cash + t.buildCost / 2) }
            holdings[tile] = h.copy(buildings = h.buildings - 1)
            ev(PtEvent.BuildingSold(seat, tile, h.buildings - 1))
        }
        val d = debt
        if (phase == Phase.AWAIT_DEBT && d != null && players[seat].cash >= d.amount) settleDebt(d)
    }

    private fun settleDebt(d: DebtState) {
        upd(d.debtor) { it.copy(cash = it.cash - d.amount) }
        when {
            d.reason == DebtReason.RIVALS -> {
                val rivals = active().filter { it != d.debtor }
                for (r in rivals) upd(r) { it.copy(cash = it.cash + d.amount / rivals.size) }
            }
            d.creditor >= 0 -> upd(d.creditor) { it.copy(cash = it.cash + d.amount) }
            d.reason == DebtReason.TAX || d.reason == DebtReason.FINE || d.reason == DebtReason.REPAIRS || d.reason == DebtReason.CARD -> toJackpotOrBank(d.amount)
        }
        debt = null
        val back = stack.firstOrNull() ?: Phase.AWAIT_POST_ROLL
        stack = emptyList()
        phase = back
        if (pendingMove > 0) {
            val steps = pendingMove; pendingMove = 0
            move(d.debtor, steps, true)
            return
        }
        if (phase == Phase.AWAIT_ROLL && players[currentSeat].inTimeout) phase = Phase.AWAIT_TIMEOUT_CHOICE
    }

    // ------------------------------------------------------------------------------ bankruptcy and turns

    private fun bankrupt(seat: Int) {
        val d = debt
        val creditor = if (d != null && d.creditor >= 0 && d.reason != DebtReason.RIVALS) d.creditor else -1
        // buildings go back to supply; a player creditor receives their half value in cash
        var cash = players[seat].cash
        for (h in holdings.values.filter { it.owner == seat }) {
            if (h.buildings > 0) {
                val t = PtBoard.tile(h.tile)
                if (h.buildings == PtRules.RESORT_LEVEL) { resortsLeft++ } else cabinsLeft += h.buildings
                cash += h.buildings * (t.buildCost / 2)
            }
            if (creditor >= 0) holdings[h.tile] = h.copy(owner = creditor, buildings = 0) else holdings.remove(h.tile)
        }
        if (creditor >= 0) {
            upd(creditor) { it.copy(cash = it.cash + cash, passCardIds = it.passCardIds + players[seat].passCardIds, freePassCards = it.passCardIds.size + players[seat].passCardIds.size) }
        } else {
            for (id in players[seat].passCardIds) if (id.startsWith("t")) tideDraw += id else guildDraw += id
        }
        upd(seat) { it.copy(cash = 0, bankrupt = true, bankruptRound = round, passCardIds = emptyList(), freePassCards = 0, inTimeout = false) }
        ev(PtEvent.Bankrupt(seat, creditor))
        debt = null; pendingMove = 0; stack = emptyList(); extraRoll = false
        val left = active()
        if (left.size <= 1) { finish(left.firstOrNull() ?: creditor); return }
        if (seat == currentSeat) endTurn() else phase = Phase.AWAIT_POST_ROLL
    }

    private fun endTurn() {
        extraRoll = false; noExtra = false; landed = -1; cardId = null; debt = null; stack = emptyList(); pendingMove = 0
        upd(currentSeat) { it.copy(doublesStreak = 0) }
        val left = active()
        if (left.size <= 1) { finish(left.firstOrNull() ?: currentSeat); return }
        var next = currentSeat
        do { next = (next + 1) % players.size } while (players[next].bankrupt)
        if (next <= currentSeat) {
            if (round >= cfg.length.roundCap) {
                ev(PtEvent.RoundCapReached(round))
                finishByNetWorth()
                return
            }
            round++
            val st = view()
            val pct = StandardPropertyTycoonEngine.tidePercent(st)
            if (pct > 0 && pct != StandardPropertyTycoonEngine.tidePercent(st.copy(round = round - 1))) ev(PtEvent.RisingTide(pct))
        }
        currentSeat = next
        phase = if (players[next].inTimeout) Phase.AWAIT_TIMEOUT_CHOICE else Phase.AWAIT_ROLL
    }

    private fun finishByNetWorth() {
        val st = view()
        val order = active().sortedWith(
            compareByDescending<Int> { StandardPropertyTycoonEngine.netWorth(st, it) }
                .thenByDescending { players[it].cash }
                .thenByDescending { holdings.values.count { h -> h.owner == it } }
                .thenBy { it }
        )
        finish(order.first())
    }

    private fun finish(seat: Int) {
        over = true; winner = seat; phase = Phase.GAME_OVER; stack = emptyList(); debt = null
        ev(PtEvent.Winner(seat))
    }
}
