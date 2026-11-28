package com.gamesuite.games.territorywars

import kotlin.random.Random

/** The real battle rule (design doc rules 8 to 10): pairwise highest-first, higher die wins, ties go to the defender. */
class StandardBattleResolver : BattleResolver {
    override fun resolve(attackerDice: Int, defenderDice: Int, dice: DiceSource): BattleOutcome {
        require(attackerDice in 1..TerritoryWarsRules.MAX_ATTACK_DICE) { "attacker dice 1..3" }
        require(defenderDice in 1..TerritoryWarsRules.MAX_DEFEND_DICE) { "defender dice 1..2" }
        val a = List(attackerDice) { dice.nextDie() }.sortedDescending()
        val d = List(defenderDice) { dice.nextDie() }.sortedDescending()
        var aLoss = 0
        var dLoss = 0
        for (i in 0 until minOf(a.size, d.size)) if (a[i] > d[i]) dLoss++ else aLoss++
        return BattleOutcome(a, d, aLoss, dLoss)
    }
}

/**
 * The Territory Wars rules engine. Pure functions over [TerritoryWarsState]; battle dice come only from [dice], the deal,
 * deck shuffles and automatic placement from streams derived from the config seed.
 */
class StandardTerritoryWarsEngine(
    private val resolver: BattleResolver = StandardBattleResolver(),
    private val dice: DiceSource = SeededDice(0L)
) : TerritoryWarsEngine {

    private fun mapOf(state: TerritoryWarsState): WarMap = TerritoryWarsMaps.byId(state.mapId) ?: TerritoryWarsMaps.STANDARD

    // ------------------------------------------------------------------------------ new game

    override fun newGame(players: List<TerritoryWarsPlayer>, config: TerritoryWarsConfig): TerritoryWarsState {
        require(players.size in TerritoryWarsRules.MIN_PLAYERS..TerritoryWarsRules.MAX_PLAYERS) { "Territory Wars needs 2 to 6 seats" }
        val map = TerritoryWarsMaps.byId(config.mapId) ?: TerritoryWarsMaps.STANDARD
        val n = players.size
        val deal = Random(config.seed xor DEAL_SALT)
        val order = (0 until map.size).toMutableList().also { it.shuffle(deal) }
        val dealer = deal.nextInt(n)
        val owners = MutableList(map.size) { -1 }
        for ((k, t) in order.withIndex()) owners[t] = (dealer + k) % n
        val total = TerritoryWarsRules.startingArmies(map.size, n)
        val pools = List(n) { seat ->
            val held = owners.count { it == seat }
            maxOf(0, total - held + (config.handicapArmies.getOrNull(seat) ?: 0))
        }
        val deck = TerritoryWarsCards.buildDeck(map).toMutableList().also { it.shuffle(Random(config.seed xor DECK_SALT)) }
        val first = config.firstSeat.coerceIn(0, n - 1)
        var state = TerritoryWarsState.blank(map, config, players).copy(
            owners = owners, armies = List(map.size) { 1 }, deck = deck, currentSeat = first, phase = TurnPhase.PLACE_INITIAL,
            initialPools = pools, event = TerritoryWarsEvent(TerritoryWarsEventKind.DEALT, first), eventSerial = 1
        )
        state = if (config.autoPlace) autoPlaceAll(state) else beginPlacementVisit(state, first)
        return state
    }

    private fun autoPlaceAll(s0: TerritoryWarsState): TerritoryWarsState {
        var s = s0
        val armies = s.armies.toMutableList()
        for (seat in 0 until s.seats) {
            val own = s.owners.indices.filter { s.owners[it] == seat }
            val rnd = Random(s.config.seed xor PLACE_SALT xor (seat * 7919L))
            repeat(s.initialPools[seat]) { if (own.isNotEmpty()) armies[own[rnd.nextInt(own.size)]]++ }
        }
        s = s.copy(armies = armies, initialPools = s.players.map { 0 })
        return startFirstTurn(s)
    }

    /** The seat is about to place its next batch of up to 3 armies; skip seats whose pool is empty. */
    private fun beginPlacementVisit(s: TerritoryWarsState, seat: Int): TerritoryWarsState {
        var cur = seat
        for (i in 0 until s.seats) {
            if (s.initialPools[cur] > 0) {
                return s.copy(currentSeat = cur, phase = TurnPhase.PLACE_INITIAL, armiesToPlace = minOf(TerritoryWarsRules.INITIAL_BATCH, s.initialPools[cur]))
            }
            cur = (cur + 1) % s.seats
        }
        return startFirstTurn(s)
    }

    private fun startFirstTurn(s: TerritoryWarsState): TerritoryWarsState =
        beginTurn(s.copy(currentSeat = s.config.firstSeat.coerceIn(0, s.seats - 1), round = 1), emptyList()).first

    // ------------------------------------------------------------------------------ queries

    override fun reinforcement(state: TerritoryWarsState, seat: Int): Reinforcement {
        val map = mapOf(state)
        val held = state.territoriesOf(seat)
        var regions = 0
        for (r in map.regions) if (r.territoryIds.all { map.indexOf(it).let { i -> i >= 0 && state.owners[i] == seat } }) regions += r.bonus
        return Reinforcement(TerritoryWarsRules.baseReinforcement(held), regions, 0)
    }

    override fun winnerSeats(state: TerritoryWarsState): List<Int> = state.winnerSeats

    private fun hasPath(s: TerritoryWarsState, map: WarMap, from: Int, to: Int, seat: Int): Boolean {
        if (s.config.fortifyReach == FortifyReach.ADJACENT) return to in map.neighbourIndices(from)
        val seen = HashSet<Int>(); val stack = ArrayDeque<Int>()
        stack.addLast(from); seen += from
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c == to) return true
            for (n in map.neighbourIndices(c)) if (s.owners[n] == seat && seen.add(n)) stack.addLast(n)
        }
        return false
    }

    private fun sets(hand: List<WarCard>): List<List<WarCard>> {
        val out = ArrayList<List<WarCard>>()
        for (a in hand.indices) for (b in a + 1 until hand.size) for (c in b + 1 until hand.size) {
            val t = listOf(hand[a], hand[b], hand[c])
            if (TerritoryWarsCards.isSet(t)) out += t
        }
        return out
    }

    override fun legalActions(state: TerritoryWarsState): List<TerritoryWarsAction> {
        if (state.phase == TurnPhase.GAME_OVER) return emptyList()
        val map = mapOf(state)
        val seat = state.currentSeat
        val own = state.owners.indices.filter { state.owners[it] == seat }
        val out = ArrayList<TerritoryWarsAction>()
        when (state.phase) {
            TurnPhase.PLACE_INITIAL -> { own.forEach { out += TerritoryWarsAction.PlaceArmies(it, 1) }; out += TerritoryWarsAction.AutoPlaceRemaining }
            TurnPhase.CARDS_FORCED -> sets(state.hands[seat]).forEach { out += TerritoryWarsAction.TurnInCards(it.map { c -> c.id }) }
            TurnPhase.REINFORCE -> {
                sets(state.hands[seat]).forEach { out += TerritoryWarsAction.TurnInCards(it.map { c -> c.id }) }
                if (state.armiesToPlace > 0) { own.forEach { out += TerritoryWarsAction.PlaceArmies(it, 1) }; out += TerritoryWarsAction.AutoPlaceRemaining }
                else { attacks(state, map, out); out += TerritoryWarsAction.EndAttack }
            }
            TurnPhase.ATTACK -> { attacks(state, map, out); out += TerritoryWarsAction.EndAttack }
            TurnPhase.MOVE_IN -> state.pendingMoveIn?.let { m ->
                out += TerritoryWarsAction.MoveIn(m.minMove)
                if (m.maxMove != m.minMove) out += TerritoryWarsAction.MoveIn(m.maxMove)
            }
            TurnPhase.FORTIFY -> {
                for (f in own) if (state.armies[f] >= 2) for (t in own) if (t != f && hasPath(state, map, f, t, seat)) out += TerritoryWarsAction.Fortify(f, t, state.armies[f] - 1)
                out += TerritoryWarsAction.SkipFortify
            }
            else -> {}
        }
        return out
    }

    private fun attacks(state: TerritoryWarsState, map: WarMap, out: MutableList<TerritoryWarsAction>) {
        val seat = state.currentSeat
        for (f in state.owners.indices) if (state.owners[f] == seat && state.armies[f] >= 2) {
            for (t in map.neighbourIndices(f)) if (state.owners[t] != seat) {
                out += TerritoryWarsAction.Attack(f, t, minOf(TerritoryWarsRules.MAX_ATTACK_DICE, state.armies[f] - 1))
                out += TerritoryWarsAction.AttackUntilDone(f, t)
            }
        }
    }

    // ------------------------------------------------------------------------------ apply

    override fun apply(state: TerritoryWarsState, action: TerritoryWarsAction): ActionResult {
        if (state.phase == TurnPhase.GAME_OVER) return ActionResult.Rejected(RejectReason.GAME_OVER)
        val map = mapOf(state)
        val seat = state.currentSeat
        return when (action) {
            is TerritoryWarsAction.PlaceArmies -> place(state, map, action.territory, action.count, false)
            is TerritoryWarsAction.AutoPlaceRemaining -> place(state, map, -1, 0, true)
            is TerritoryWarsAction.TurnInCards -> turnIn(state, action.cardIds)
            is TerritoryWarsAction.Attack -> attack(state, map, action.from, action.to, action.dice, false)
            is TerritoryWarsAction.AttackUntilDone -> attack(state, map, action.from, action.to, 3, true)
            is TerritoryWarsAction.MoveIn -> moveIn(state, action.count)
            is TerritoryWarsAction.EndAttack -> {
                val ok = state.phase == TurnPhase.ATTACK || (state.phase == TurnPhase.REINFORCE && state.armiesToPlace == 0)
                if (!ok) return reject(state, RejectReason.PLACE_FIRST)
                accept(state.copy(phase = TurnPhase.FORTIFY), emptyList())
            }
            is TerritoryWarsAction.Fortify -> fortify(state, map, seat, action)
            is TerritoryWarsAction.SkipFortify -> {
                if (state.phase != TurnPhase.FORTIFY) return reject(state, RejectReason.WRONG_PHASE)
                endTurn(state, emptyList())
            }
        }
    }

    private fun reject(s: TerritoryWarsState, r: RejectReason): ActionResult = ActionResult.Rejected(if (s.phase == TurnPhase.GAME_OVER) RejectReason.GAME_OVER else r)

    private fun accept(s: TerritoryWarsState, events: List<TerritoryWarsEvent>): ActionResult {
        val last = events.lastOrNull()
        return ActionResult.Accepted(s.copy(event = last ?: s.event, eventSerial = s.eventSerial + 1), events)
    }

    // ------------------------------------------------------------------------------ placement and cards

    private fun place(s: TerritoryWarsState, map: WarMap, territory: Int, count: Int, auto: Boolean): ActionResult {
        val seat = s.currentSeat
        when (s.phase) {
            TurnPhase.PLACE_INITIAL, TurnPhase.REINFORCE -> {}
            TurnPhase.CARDS_FORCED -> return reject(s, RejectReason.TURN_IN_FIRST)
            else -> return reject(s, RejectReason.WRONG_PHASE)
        }
        val pool = if (s.phase == TurnPhase.PLACE_INITIAL) s.armiesToPlace else s.armiesToPlace
        if (auto) {
            val own = s.owners.indices.filter { s.owners[it] == seat }
            val armies = s.armies.toMutableList()
            val rnd = Random(s.config.seed xor PLACE_SALT xor (s.eventSerial * 131L) xor (seat * 7919L))
            val total = if (s.phase == TurnPhase.PLACE_INITIAL) s.initialPools[seat] else pool
            if (total <= 0) return reject(s, RejectReason.NONE_LEFT)
            repeat(total) { armies[own[rnd.nextInt(own.size)]]++ }
            val placed = TerritoryWarsEvent(TerritoryWarsEventKind.PLACED, seat, -1, own.first(), total)
            return afterPlacement(s.copy(armies = armies), total, listOf(placed), true)
        }
        if (territory !in s.owners.indices || s.owners[territory] != seat) return reject(s, RejectReason.NOT_YOURS)
        if (pool <= 0) return reject(s, RejectReason.NONE_LEFT)
        if (count < 1 || count > pool) return reject(s, RejectReason.BAD_COUNT)
        val armies = s.armies.toMutableList()
        armies[territory] += count
        val ev = TerritoryWarsEvent(TerritoryWarsEventKind.PLACED, seat, -1, territory, count)
        return afterPlacement(s.copy(armies = armies), count, listOf(ev), false)
    }

    private fun afterPlacement(s: TerritoryWarsState, placed: Int, events: List<TerritoryWarsEvent>, all: Boolean): ActionResult {
        val seat = s.currentSeat
        if (s.phase == TurnPhase.REINFORCE) {
            return accept(s.copy(armiesToPlace = s.armiesToPlace - placed), events)
        }
        val pools = s.initialPools.toMutableList()
        pools[seat] -= placed
        var next = s.copy(initialPools = pools, armiesToPlace = if (all) 0 else s.armiesToPlace - placed)
        if (next.armiesToPlace > 0 && pools[seat] > 0) return accept(next, events)
        // this seat's visit is over: the next seat with armies left places, else the war begins
        next = beginPlacementVisit(next.copy(armiesToPlace = 0), (seat + 1) % s.seats)
        return accept(next, events)
    }

    private fun turnIn(s: TerritoryWarsState, ids: List<String>): ActionResult {
        if (s.phase != TurnPhase.CARDS_FORCED && s.phase != TurnPhase.REINFORCE) return reject(s, RejectReason.WRONG_PHASE)
        val seat = s.currentSeat
        val hand = s.hands[seat]
        val chosen = ids.map { id -> hand.firstOrNull { it.id == id } ?: return reject(s, RejectReason.NOT_A_SET) }
        if (!TerritoryWarsCards.isSet(chosen)) return reject(s, RejectReason.NOT_A_SET)
        val value = TerritoryWarsRules.setValue(s.setsTurnedIn)
        val newHand = hand.filter { it.id !in ids }
        val hands = s.hands.toMutableList().also { it[seat] = newHand }
        var next = s.copy(hands = hands, discard = s.discard + chosen, setsTurnedIn = s.setsTurnedIn + 1, armiesToPlace = s.armiesToPlace + value)
        if (s.phase == TurnPhase.CARDS_FORCED && newHand.size < TerritoryWarsRules.FORCED_TURN_IN_HAND) {
            next = next.copy(phase = TurnPhase.REINFORCE)
        }
        return accept(next, listOf(TerritoryWarsEvent(TerritoryWarsEventKind.SET_TURNED_IN, seat, amount = value, extra = s.setsTurnedIn + 1)))
    }

    // ------------------------------------------------------------------------------ battle

    private fun attack(s: TerritoryWarsState, map: WarMap, from: Int, to: Int, diceWanted: Int, untilDone: Boolean): ActionResult {
        val seat = s.currentSeat
        when {
            s.phase == TurnPhase.MOVE_IN -> return reject(s, RejectReason.MOVE_IN_FIRST)
            s.phase == TurnPhase.CARDS_FORCED -> return reject(s, RejectReason.TURN_IN_FIRST)
            s.phase == TurnPhase.REINFORCE && s.armiesToPlace > 0 -> return reject(s, RejectReason.PLACE_FIRST)
            s.phase != TurnPhase.ATTACK && s.phase != TurnPhase.REINFORCE -> return reject(s, RejectReason.WRONG_PHASE)
        }
        if (from !in s.owners.indices || to !in s.owners.indices || s.owners[from] != seat) return reject(s, RejectReason.NOT_YOURS)
        if (s.owners[to] == seat) return reject(s, RejectReason.OWN_TARGET)
        if (to !in map.neighbourIndices(from)) return reject(s, RejectReason.NOT_ADJACENT)
        if (s.armies[from] < 2) return reject(s, RejectReason.NEED_TWO_ARMIES)
        val maxDice = minOf(TerritoryWarsRules.MAX_ATTACK_DICE, s.armies[from] - 1)
        if (!untilDone && diceWanted !in 1..maxDice) return reject(s, RejectReason.BAD_DICE)
        var cur = if (s.phase == TurnPhase.REINFORCE) s.copy(phase = TurnPhase.ATTACK) else s
        val events = ArrayList<TerritoryWarsEvent>()
        do {
            val useDice = if (untilDone) minOf(TerritoryWarsRules.MAX_ATTACK_DICE, cur.armies[from] - 1) else diceWanted
            val def = minOf(TerritoryWarsRules.MAX_DEFEND_DICE, cur.armies[to])
            val out = resolver.resolve(useDice, def, dice)
            val armies = cur.armies.toMutableList()
            armies[from] -= out.attackerLosses
            armies[to] -= out.defenderLosses
            cur = cur.copy(armies = armies, battles = cur.battles + 1, lastBattleDice = useDice)
            events += TerritoryWarsEvent(TerritoryWarsEventKind.BATTLE, seat, from, to, out.attackerLosses, out.defenderLosses)
            if (armies[to] <= 0) {
                cur = conquer(cur, map, from, to, useDice, events)
                break
            }
        } while (untilDone && cur.armies[from] >= 2)
        return accept(cur, events)
    }

    private fun conquer(s0: TerritoryWarsState, map: WarMap, from: Int, to: Int, diceRolled: Int, events: MutableList<TerritoryWarsEvent>): TerritoryWarsState {
        val seat = s0.currentSeat
        val victim = s0.owners[to]
        val owners = s0.owners.toMutableList().also { it[to] = seat }
        var s = s0.copy(owners = owners, conquests = s0.conquests + 1, conquestsThisRound = s0.conquestsThisRound + 1, conqueredThisTurn = true)
        events += TerritoryWarsEvent(TerritoryWarsEventKind.CONQUERED, seat, from, to, diceRolled)
        // a completed region
        val region = map.regions.firstOrNull { r -> map.territories[to].id in r.territoryIds }
        if (region != null && region.territoryIds.all { map.indexOf(it).let { i -> s.owners[i] == seat } }) {
            events += TerritoryWarsEvent(TerritoryWarsEventKind.REGION_COMPLETED, seat, extra = region.bonus)
        }
        // elimination
        var hands = s.hands
        var eliminated = s.eliminated
        if (s.owners.none { it == victim }) {
            eliminated = eliminated.toMutableList().also { it[victim] = true }
            val taken = hands[victim]
            hands = hands.toMutableList().also { it[seat] = it[seat] + taken; it[victim] = emptyList() }
            events += TerritoryWarsEvent(TerritoryWarsEventKind.ELIMINATED, seat, to = to, amount = victim)
            s = s.copy(hands = hands, eliminated = eliminated)
        }
        val minMove = minOf(diceRolled, s.armies[from] - 1).coerceAtLeast(1)
        val maxMove = (s.armies[from] - 1).coerceAtLeast(minMove)
        // wins that end the war at once
        val living = (0 until s.seats).filter { !s.eliminated[it] }
        val capture = s.config.winRule == WinRule.CAPTURE_TARGET && s.territoriesOf(seat) >= s.config.captureTarget
        if (living.size == 1 || capture) {
            val armies = s.armies.toMutableList()
            armies[from] -= minMove; armies[to] = minMove
            events += TerritoryWarsEvent(TerritoryWarsEventKind.MOVED_IN, seat, from, to, minMove)
            val over = s.copy(armies = armies, phase = TurnPhase.GAME_OVER, pendingMoveIn = null, winnerSeats = listOf(seat),
                winReason = if (living.size == 1) WinReason.DOMINATION else WinReason.CAPTURE_TARGET)
            events += TerritoryWarsEvent(TerritoryWarsEventKind.WAR_WON, seat)
            return over
        }
        val armies = s.armies.toMutableList().also { it[to] = 0 }
        return s.copy(armies = armies, phase = TurnPhase.MOVE_IN, pendingMoveIn = PendingMoveIn(from, to, minMove, maxMove))
    }

    private fun moveIn(s: TerritoryWarsState, count: Int): ActionResult {
        val m = s.pendingMoveIn
        if (s.phase != TurnPhase.MOVE_IN || m == null) return reject(s, RejectReason.WRONG_PHASE)
        if (count !in m.minMove..m.maxMove) return reject(s, RejectReason.BAD_COUNT)
        val armies = s.armies.toMutableList()
        armies[m.from] -= count
        armies[m.to] = count
        val seat = s.currentSeat
        var next = s.copy(armies = armies, pendingMoveIn = null, phase = TurnPhase.ATTACK)
        // a set forced by the cards of an eliminated seat
        if (next.hands[seat].size >= TerritoryWarsRules.FORCED_TURN_IN_HAND) next = next.copy(phase = TurnPhase.CARDS_FORCED)
        return accept(next, listOf(TerritoryWarsEvent(TerritoryWarsEventKind.MOVED_IN, seat, m.from, m.to, count)))
    }

    // ------------------------------------------------------------------------------ fortify and turn flow

    private fun fortify(s: TerritoryWarsState, map: WarMap, seat: Int, a: TerritoryWarsAction.Fortify): ActionResult {
        if (s.phase != TurnPhase.FORTIFY) return reject(s, RejectReason.WRONG_PHASE)
        if (s.fortifiedThisTurn) return reject(s, RejectReason.ALREADY_FORTIFIED)
        if (a.from !in s.owners.indices || a.to !in s.owners.indices || s.owners[a.from] != seat || s.owners[a.to] != seat || a.from == a.to) return reject(s, RejectReason.NOT_YOURS)
        if (a.count < 1) return reject(s, RejectReason.BAD_COUNT)
        if (a.count > s.armies[a.from] - 1) return reject(s, RejectReason.LEAVE_ONE)
        if (!hasPath(s, map, a.from, a.to, seat)) return reject(s, RejectReason.NO_PATH)
        val armies = s.armies.toMutableList()
        armies[a.from] -= a.count; armies[a.to] += a.count
        val ev = TerritoryWarsEvent(TerritoryWarsEventKind.FORTIFIED, seat, a.from, a.to, a.count)
        return endTurn(s.copy(armies = armies, fortifiedThisTurn = true), listOf(ev))
    }

    private fun endTurn(s0: TerritoryWarsState, events0: List<TerritoryWarsEvent>): ActionResult {
        val events = events0.toMutableList()
        var s = s0
        val seat = s.currentSeat
        if (s.conqueredThisTurn) {
            var deck = s.deck
            var discard = s.discard
            if (deck.isEmpty() && discard.isNotEmpty()) {
                deck = discard.shuffled(Random(s.config.seed xor DECK_SALT xor (s.eventSerial * 104729L + s.setsTurnedIn)))
                discard = emptyList()
            }
            if (deck.isNotEmpty()) {
                val card = deck.first()
                s = s.copy(deck = deck.drop(1), discard = discard, hands = s.hands.toMutableList().also { it[seat] = it[seat] + card })
                events += TerritoryWarsEvent(TerritoryWarsEventKind.CARD_DRAWN, seat)
            }
        }
        // next living seat; a wrap closes the round
        var next = seat
        do { next = (next + 1) % s.seats } while (s.eliminated[next])
        var round = s.round
        if (next <= seat) {
            val calm = if (s.conquestsThisRound == 0) s.roundsWithoutConquest + 1 else 0
            s = s.copy(roundsWithoutConquest = calm, conquestsThisRound = 0)
            val cap = s.config.roundCap
            if (cap > 0 && round >= cap) return finishOnPoints(s, events, WinReason.ROUND_CAP)
            if (calm >= TerritoryWarsRules.STALEMATE_ROUNDS) return finishOnPoints(s, events, WinReason.STALEMATE)
            round++
        }
        val (turn, startEvents) = beginTurn(s.copy(currentSeat = next, round = round), events)
        return accept(turn, startEvents)
    }

    private fun finishOnPoints(s: TerritoryWarsState, events: MutableList<TerritoryWarsEvent>, reason: WinReason): ActionResult {
        val living = (0 until s.seats).filter { !s.eliminated[it] }
        fun key(seat: Int) = listOf(s.territoriesOf(seat), s.armiesOf(seat), s.hands[seat].size)
        val best = living.maxWithOrNull { a, b ->
            val ka = key(a); val kb = key(b)
            var c = 0
            for (i in ka.indices) { c = ka[i].compareTo(kb[i]); if (c != 0) break }
            c
        }!!
        val winners = living.filter { key(it) == key(best) }
        events += TerritoryWarsEvent(TerritoryWarsEventKind.WAR_WON, winners.first())
        return accept(s.copy(phase = TurnPhase.GAME_OVER, winnerSeats = winners, winReason = reason, pendingMoveIn = null), events)
    }

    /** Starts [s]'s seat on turn: reinforcement pool, forced turn-in when holding 5+ cards. */
    private fun beginTurn(s: TerritoryWarsState, events: List<TerritoryWarsEvent>): Pair<TerritoryWarsState, List<TerritoryWarsEvent>> {
        val seat = s.currentSeat
        val r = reinforcement(s, seat)
        val forced = s.hands[seat].size >= TerritoryWarsRules.FORCED_TURN_IN_HAND
        val next = s.copy(
            phase = if (forced) TurnPhase.CARDS_FORCED else TurnPhase.REINFORCE, armiesToPlace = r.total,
            conqueredThisTurn = false, fortifiedThisTurn = false, pendingMoveIn = null, initialPools = s.players.map { 0 }
        )
        return next to (events + TerritoryWarsEvent(TerritoryWarsEventKind.TURN_STARTED, seat, amount = r.total))
    }

    companion object {
        const val DEAL_SALT: Long = 0x1D3A5B7L
        const val DECK_SALT: Long = 0x2E4C6D8L
        const val PLACE_SALT: Long = 0x3F5E7A9L
    }
}
