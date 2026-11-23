package com.gamesuite.games.president

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The pure state transitions of President: deal, play, pass, give (exchange) and next round. Each
 * returns the new state, or null when the move is not allowed (nothing changes). [PresidentGame] adds
 * undo, networking, the bots' timing and the session bookkeeping on top; the tests drive this directly.
 */
object PresidentFlow {

    /** A fresh session's first round for [roster] (hands are dealt here). */
    fun newSession(roster: List<PsSeat>, rules: PresidentRuleSet, random: Random, level: CpuDifficulty): PresidentState =
        deal(
            base = roster.map { it.copy(hand = emptyList(), outPosition = -1, role = "", roundPoints = 0, score = 0) },
            rules = rules, roundNumber = 1, prevOrder = emptyList(), sessionWins = emptyMap(), random = random, level = level,
            serial = 0
        )

    fun deal(
        base: List<PsSeat>,
        rules: PresidentRuleSet,
        roundNumber: Int,
        prevOrder: List<Int>,
        sessionWins: Map<String, Int>,
        random: Random,
        level: CpuDifficulty,
        serial: Int
    ): PresidentState {
        val n = base.size
        require(PresidentRules.validSeatCount(n)) { "President needs 3 to 6 seats" }
        val deck = (0 until PsDeck.SIZE).toMutableList().also { it.shuffle(random) }
        val hands = List(n) { mutableListOf<Int>() }
        deck.forEachIndexed { i, id -> hands[i % n] += id }
        val haveOrder = prevOrder.size == n && prevOrder.toSet().size == n
        val roles = Array(n) { "" }
        if (haveOrder) prevOrder.forEachIndexed { pos, seat -> roles[seat] = PresidentRules.roleFor(pos, n).name }
        var seats = base.mapIndexed { i, s ->
            s.copy(hand = PresidentRules.sortHand(hands[i]), outPosition = -1, role = roles[i], roundPoints = 0)
        }
        val start = if (haveOrder) prevOrder[0] else seats.indexOfFirst { PsDeck.THREE_OF_CLUBS in it.hand }.coerceAtLeast(0)
        var records = emptyList<PsExchangeRecord>()
        var tasks = emptyList<PsExchangeTask>()
        if (haveOrder && rules.exchange) {
            val recs = ArrayList<PsExchangeRecord>()
            val pending = ArrayList<PsExchangeTask>()
            for (pair in PresidentRules.exchangePairs(prevOrder)) {
                val taken = PresidentRules.bestCards(seats[pair.low].hand, pair.count)
                seats = seats.mapIndexed { i, s ->
                    when (i) {
                        pair.low -> s.copy(hand = s.hand - taken.toSet())
                        pair.high -> s.copy(hand = PresidentRules.sortHand(s.hand + taken))
                        else -> s
                    }
                }
                recs += PsExchangeRecord(from = pair.low, to = pair.high, cards = taken, auto = true)
                pending += PsExchangeTask(from = pair.high, to = pair.low, count = pair.count)
            }
            records = recs
            tasks = pending
        }
        var state = PresidentState(
            seats = seats, rules = rules,
            phase = if (tasks.isEmpty()) PsPhase.PLAY else PsPhase.EXCHANGE,
            currentSeat = if (tasks.isEmpty()) start else tasks.first().from,
            roundNumber = roundNumber,
            mustInclude = if (haveOrder) null else PsDeck.THREE_OF_CLUBS,
            pendingExchanges = tasks, exchanges = records, startSeat = start,
            sessionWins = sessionWins,
            event = if (tasks.isEmpty()) PsEvent.DEAL else PsEvent.EXCHANGE,
            eventSerial = serial + 1
        )
        state = resolveBotGifts(state, level)
        return state
    }

    /** Every pending gift whose giver is a computer is made at once; the table then waits only on people. */
    fun resolveBotGifts(s: PresidentState, level: CpuDifficulty): PresidentState {
        var cur = s
        while (cur.phase == PsPhase.EXCHANGE) {
            val task = cur.pendingExchanges.firstOrNull { cur.seats[it.from].isBot } ?: break
            val gift = PresidentBot.chooseGive(cur.seats[task.from].hand, task.count, level)
            cur = give(cur, task.from, gift) ?: break
        }
        return cur
    }

    /** Hands [ids] from [seat] to the seat it owes (exchange phase). */
    fun give(s: PresidentState, seat: Int, ids: List<Int>): PresidentState? {
        if (s.phase != PsPhase.EXCHANGE) return null
        val task = s.pendingExchanges.firstOrNull { it.from == seat } ?: return null
        val hand = s.seats[seat].hand
        if (ids.size != task.count || ids.toSet().size != ids.size || ids.any { it !in hand }) return null
        val rest = s.pendingExchanges - task
        val seats = s.seats.mapIndexed { i, st ->
            when (i) {
                seat -> st.copy(hand = hand - ids.toSet())
                task.to -> st.copy(hand = PresidentRules.sortHand(st.hand + ids))
                else -> st
            }
        }
        val done = rest.isEmpty()
        return s.copy(
            seats = seats,
            pendingExchanges = rest,
            exchanges = s.exchanges + PsExchangeRecord(from = seat, to = task.to, cards = ids.sorted(), auto = false),
            phase = if (done) PsPhase.PLAY else PsPhase.EXCHANGE,
            currentSeat = if (done) s.startSeat else rest.first().from,
            event = PsEvent.EXCHANGE, eventSeat = seat, eventCards = ids.sorted(),
            eventRevolution = false, eventCleared = false, eventSkipped = null, eventOut = false,
            eventSerial = s.eventSerial + 1
        )
    }

    /** The next seat after [from] (turn order) that still holds cards and is not in [excluded]. */
    private fun nextActive(seats: List<PsSeat>, from: Int, excluded: Collection<Int> = emptyList()): Int? {
        val n = seats.size
        for (step in 1..n) {
            val i = (from + step) % n
            if (seats[i].hand.isNotEmpty() && i !in excluded) return i
        }
        return null
    }

    fun play(s: PresidentState, seat: Int, ids: List<Int>): PresidentState? {
        if (s.phase != PsPhase.PLAY || seat != s.currentSeat) return null
        val me = s.seats[seat]
        val prev = s.top
        if (PresidentRules.explainPlay(ids, me.hand, prev?.cards, s.revolution, s.rules.ties, s.mustInclude) != PresidentReject.NONE) return null
        val sorted = ids.sorted()
        val killer = PresidentRules.isTrickKiller(sorted, s.revolution)
        val flips = s.rules.revolutions && PresidentRules.isRevolutionPlay(sorted)
        val tie = prev != null && s.rules.ties && PresidentRules.power(sorted[0], s.revolution) == PresidentRules.power(prev.cards[0], s.revolution)
        val newHand = me.hand - sorted.toSet()
        val wentOut = newHand.isEmpty()
        var order = s.finishOrder
        var seats = s.seats.mapIndexed { i, st ->
            if (i != seat) st else st.copy(hand = newHand, outPosition = if (wentOut) order.size else -1)
        }
        if (wentOut) order = order + seat
        val trick = s.trick + PsTrickPlay(seat, sorted)
        val played = s.playedCards + sorted
        val revolution = if (flips) !s.revolution else s.revolution
        val active = seats.indices.filter { seats[it].hand.isNotEmpty() }
        val base = s.copy(
            seats = seats, trick = trick, playedCards = played, revolution = revolution, finishOrder = order,
            mustInclude = null, passed = emptyList(),
            event = PsEvent.PLAY, eventSeat = seat, eventCards = sorted, eventRevolution = flips,
            eventCleared = false, eventSkipped = null, eventOut = wentOut, eventSerial = s.eventSerial + 1
        )
        if (active.size <= 1) return endRound(base, active)

        var skipped: Int? = null
        var passed = emptyList<Int>()
        var over = killer
        if (!over) {
            if (tie) {
                skipped = nextActive(seats, seat)?.takeIf { it != seat }
                if (skipped != null) passed = listOf(skipped)
            }
            over = (active - seat).all { it in passed }
        }
        return if (over) {
            val leader = if (wentOut) nextActive(seats, seat)!! else seat
            base.copy(trick = emptyList(), passed = emptyList(), currentSeat = leader, eventCleared = true, eventSkipped = skipped)
        } else {
            val next = nextActive(seats, seat, passed)!!
            base.copy(passed = passed, currentSeat = next, eventSkipped = skipped)
        }
    }

    fun pass(s: PresidentState, seat: Int): PresidentState? {
        if (s.phase != PsPhase.PLAY || seat != s.currentSeat) return null
        val last = s.top ?: return null // the leader cannot pass
        val passed = s.passed + seat
        val active = s.activeSeats()
        val base = s.copy(
            passed = passed, event = PsEvent.PASS, eventSeat = seat, eventCards = emptyList(),
            eventRevolution = false, eventCleared = false, eventSkipped = null, eventOut = false,
            eventSerial = s.eventSerial + 1
        )
        return if ((active - last.seat).all { it in passed }) {
            val leader = if (s.seats[last.seat].hand.isNotEmpty()) last.seat else nextActive(s.seats, last.seat)!!
            base.copy(trick = emptyList(), passed = emptyList(), currentSeat = leader, eventCleared = true)
        } else {
            base.copy(currentSeat = nextActive(s.seats, seat, passed)!!)
        }
    }

    /** The round is decided: the last seat holding cards finishes last, points and the session are tallied. */
    private fun endRound(s: PresidentState, stillIn: List<Int>): PresidentState {
        val n = s.seats.size
        var order = s.finishOrder
        var seats = s.seats
        for (seat in stillIn) {
            seats = seats.mapIndexed { i, st -> if (i == seat) st.copy(outPosition = order.size) else st }
            order = order + seat
        }
        seats = seats.mapIndexed { i, st ->
            val pts = PresidentRules.pointsFor(st.outPosition, n)
            st.copy(roundPoints = pts, score = st.score + pts)
        }
        val sessionDone = s.roundNumber >= s.rules.rounds
        var wins = s.sessionWins
        if (sessionDone) {
            val best = seats.maxOf { it.score }
            val leaders = seats.filter { it.score == best }
            // a tie at the top shares the title; nobody is credited for a session nobody won outright
            if (leaders.size == 1) wins = wins + (leaders[0].playerId to ((wins[leaders[0].playerId] ?: 0) + 1))
        }
        return s.copy(
            seats = seats, finishOrder = order, sessionWins = wins,
            phase = if (sessionDone) PsPhase.SESSION_OVER else PsPhase.ROUND_OVER,
            event = if (sessionDone) PsEvent.SESSION_END else PsEvent.ROUND_END,
            eventSerial = s.eventSerial + 1
        )
    }

    /** Deals the next round of an unfinished session. */
    fun nextRound(s: PresidentState, random: Random, level: CpuDifficulty): PresidentState? {
        if (s.phase != PsPhase.ROUND_OVER) return null
        return deal(
            base = s.seats, rules = s.rules, roundNumber = s.roundNumber + 1, prevOrder = s.finishOrder,
            sessionWins = s.sessionWins, random = random, level = level, serial = s.eventSerial
        )
    }

    /** A new session at the same table; keeps the sessions-won tally. */
    fun restartSession(s: PresidentState, random: Random, level: CpuDifficulty): PresidentState? {
        if (s.phase != PsPhase.SESSION_OVER) return null
        return deal(
            base = s.seats.map { it.copy(score = 0, roundPoints = 0, role = "", outPosition = -1) },
            rules = s.rules, roundNumber = 1, prevOrder = emptyList(), sessionWins = s.sessionWins,
            random = random, level = level, serial = s.eventSerial
        )
    }

    /** The seats' ids and counts must add up: every one of the 52 cards is in exactly one place. */
    fun conserved(s: PresidentState): Boolean {
        val all = s.seats.flatMap { it.hand } + s.playedCards
        return all.size == PsDeck.SIZE && all.toSet() == (0 until PsDeck.SIZE).toSet()
    }
}
