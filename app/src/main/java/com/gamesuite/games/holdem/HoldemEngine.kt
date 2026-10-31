package com.gamesuite.games.holdem

import com.gamesuite.core.PlayerInfo
import kotlin.random.Random

/** The outcome of one engine call: the new state, or why the action was refused. */
class HoldemStep(val state: HoldemState?, val reject: HoldemReject) {
    val ok: Boolean get() = state != null
}

/**
 * The rules of no-limit Texas Hold'em as a pure state machine. Every method takes a [HoldemState] and
 * returns the next one; the only hidden state is the shuffled [deck] (and how far it has been dealt), which
 * lives here, on the host or local device only, and is never part of a state that is sent to anyone.
 *
 * Rules implemented: dealer button rotation over live seats, small and big blind (heads-up the button posts the
 * small blind and acts first before the flop, last after it), burn cards, betting rounds with the full-raise rule
 * (a short all-in raise does not reopen the betting for players who already acted), minimum raise equal to the
 * last full raise, side pots for any number of all-ins, uncalled bets returned, split pots with the odd chip going
 * to the first winner left of the button, blinds that rise on a schedule, and a tournament that ends when one seat
 * holds every chip.
 */
class HoldemEngine(private val random: Random = Random.Default) {
    var deck: List<Int> = emptyList()
        private set
    var deckPos: Int = 0
        private set

    fun restoreDeck(order: List<Int>, position: Int) {
        deck = order.toList()
        deckPos = position
    }

    private fun draw(): Int = deck[deckPos++]

    /** A fresh tournament: every seat on [config]'s stack, no hand dealt yet. The first hand's button goes to [firstButton]. */
    fun newMatch(players: List<PlayerInfo>, config: HoldemConfig, firstButton: Int = random.nextInt(players.size)): HoldemState {
        require(players.size in 2..6) { "Hold'em needs 2 to 6 seats" }
        require(config.startingChips in HoldemConfig.MIN_START..HoldemConfig.MAX_START)
        val n = players.size
        deck = emptyList()
        deckPos = 0
        val bb = HoldemBlinds.bigBlindAt(config.startingChips, 0)
        return HoldemState(
            seats = players.map { HoldemSeat(it.playerId, it.displayName, it.isBot, chips = config.startingChips) },
            config = config,
            button = ((firstButton % n) + n - 1) % n, // startHand moves the button on to firstButton
            smallBlind = bb / 2,
            bigBlind = bb,
            minRaise = bb
        )
    }

    private inline fun nextSeat(from: Int, n: Int, pred: (Int) -> Boolean): Int {
        for (i in 1..n) {
            val c = (from + i) % n
            if (pred(c)) return c
        }
        return -1
    }

    /**
     * Deals the next hand. Returns [s] unchanged if a hand is still being played, the match is over or fewer than
     * two seats have chips. [deckOverride] (tests) is a full 52-card order to deal from instead of a shuffle.
     */
    fun startHand(s: HoldemState, deckOverride: List<Int>? = null): HoldemState {
        if (!s.handOver || s.matchOver) return s
        val n = s.seats.size
        val liveSeats = s.seats.indices.filter { !s.seats[it].busted && s.seats[it].chips > 0 }
        if (liveSeats.size < 2) return s
        val handNumber = s.handNumber + 1
        val level = HoldemBlinds.levelForHand(s.config.speed, handNumber)
        val bb = HoldemBlinds.bigBlindAt(s.config.startingChips, level)
        val sb = bb / 2
        val button = nextSeat(s.button, n) { it in liveSeats }
        val headsUp = liveSeats.size == 2
        val sbSeat = if (headsUp) button else nextSeat(button, n) { it in liveSeats }
        val bbSeat = nextSeat(sbSeat, n) { it in liveSeats }

        if (deckOverride != null) require(deckOverride.size == HoldemCards.DECK_SIZE && deckOverride.toSet().size == HoldemCards.DECK_SIZE)
        deck = deckOverride ?: (0 until HoldemCards.DECK_SIZE).toList().shuffled(random)
        deckPos = 0
        val holes = HashMap<Int, MutableList<Int>>()
        liveSeats.forEach { holes[it] = ArrayList(2) }
        val dealOrder = ArrayList<Int>()
        var c = button
        repeat(n) { c = (c + 1) % n; if (c in liveSeats) dealOrder += c }
        repeat(2) { dealOrder.forEach { holes[it]!!.add(draw()) } }

        val seats = s.seats.mapIndexed { i, p ->
            val inHand = i in liveSeats
            p.copy(
                bet = 0, committed = 0, folded = false, allIn = false, inHand = inHand,
                hole = if (inHand) holes[i]!!.toList() else emptyList(),
                revealed = false, actedAt = -1, lastAction = "", lastAmount = 0, won = 0
            )
        }.toMutableList()
        fun post(seat: Int, amount: Int, tag: String) {
            val p = seats[seat]
            val pay = minOf(amount, p.chips)
            seats[seat] = p.copy(
                chips = p.chips - pay, bet = pay, committed = pay, allIn = p.chips - pay == 0,
                lastAction = tag, lastAmount = pay
            )
        }
        post(sbSeat, sb, "sb")
        post(bbSeat, bb, "bb")

        val dealt = s.copy(
            seats = seats, button = button, smallBlindSeat = sbSeat, bigBlindSeat = bbSeat,
            street = HoldemStreet.PREFLOP, board = emptyList(), toAct = -1,
            currentBet = bb, minRaise = bb, raiseSerial = 0, pot = 0,
            smallBlind = sb, bigBlind = bb, blindLevel = level, handNumber = handNumber,
            handOver = false, runout = false, showdown = false, results = emptyList(), seatResults = emptyList(),
            event = HoldemEvent.HAND, eventSeat = button, eventAmount = 0
        )
        return bump(s, settle(dealt, bbSeat))
    }

    /** Applies [action] for [seat], or refuses it. */
    fun act(s: HoldemState, seat: Int, action: HoldemAction): HoldemStep {
        val reject = HoldemRules.validate(s, seat, action)
        if (reject != HoldemReject.NONE) return HoldemStep(null, reject)
        val seats = s.seats.toMutableList()
        val p = seats[seat]
        val toCall = (s.currentBet - p.bet).coerceAtLeast(0)
        var currentBet = s.currentBet
        var minRaise = s.minRaise
        var serial = s.raiseSerial
        val event: String
        val amount: Int

        fun callOrAllInCall() {
            val pay = minOf(toCall, p.chips)
            val remaining = p.chips - pay
            seats[seat] = p.copy(
                chips = remaining, bet = p.bet + pay, committed = p.committed + pay, allIn = remaining == 0,
                actedAt = serial, lastAction = if (remaining == 0) "allin" else "call", lastAmount = p.bet + pay
            )
        }

        when (action.kind) {
            HoldemActionKind.FOLD -> {
                seats[seat] = p.copy(folded = true, actedAt = serial, lastAction = "fold", lastAmount = 0)
                event = HoldemEvent.FOLD; amount = 0
            }
            HoldemActionKind.CHECK -> {
                seats[seat] = p.copy(actedAt = serial, lastAction = "check", lastAmount = 0)
                event = HoldemEvent.CHECK; amount = 0
            }
            HoldemActionKind.CALL -> {
                callOrAllInCall()
                event = if (seats[seat].allIn) HoldemEvent.ALL_IN else HoldemEvent.CALL
                amount = minOf(toCall, p.chips)
            }
            HoldemActionKind.RAISE_TO, HoldemActionKind.ALL_IN -> {
                val target = if (action.kind == HoldemActionKind.ALL_IN) p.bet + p.chips else action.amount
                if (target <= currentBet) {
                    callOrAllInCall()
                    event = if (seats[seat].allIn) HoldemEvent.ALL_IN else HoldemEvent.CALL
                    amount = minOf(toCall, p.chips)
                } else {
                    val pay = target - p.bet
                    val increment = target - currentBet
                    val opening = currentBet == 0
                    if (increment >= minRaise) {
                        minRaise = increment
                        serial += 1
                    }
                    currentBet = target
                    val remaining = p.chips - pay
                    seats[seat] = p.copy(
                        chips = remaining, bet = target, committed = p.committed + pay, allIn = remaining == 0,
                        actedAt = serial,
                        lastAction = if (remaining == 0) "allin" else if (opening) "bet" else "raise",
                        lastAmount = target
                    )
                    event = if (remaining == 0) HoldemEvent.ALL_IN else if (opening) HoldemEvent.BET else HoldemEvent.RAISE
                    amount = target
                }
            }
        }
        val next = s.copy(
            seats = seats, currentBet = currentBet, minRaise = minRaise, raiseSerial = serial,
            event = event, eventSeat = seat, eventAmount = amount
        )
        return HoldemStep(bump(s, settle(next, seat)), HoldemReject.NONE)
    }

    /** While the board is running out (everyone left is all-in), deals the next street, or settles the hand after the river. */
    fun stepRunout(s: HoldemState): HoldemState {
        if (!s.runout || s.handOver) return s
        val next = if (s.street == HoldemStreet.RIVER) showdown(s) else dealStreet(s, runoutMode = true)
        return bump(s, next)
    }

    private fun bump(previous: HoldemState, next: HoldemState): HoldemState = next.copy(eventSerial = previous.eventSerial + 1)

    // ---- betting flow ---------------------------------------------------------------------------

    /** After a seat acted (or blinds went in): hand the turn on, or close the betting round. */
    private fun settle(s: HoldemState, from: Int): HoldemState {
        val n = s.seats.size
        val live = s.seats.indices.filter { s.seats[it].live }
        if (live.size <= 1) return finishUncontested(s)
        val active = live.filter { s.seats[it].active }
        val done = when {
            active.isEmpty() -> true
            active.size == 1 -> s.seats[active[0]].bet >= s.currentBet
            else -> active.all { s.seats[it].bet == s.currentBet && s.seats[it].actedAt >= 0 }
        }
        if (!done) {
            val next = nextSeat(from, n) { it in active && (s.seats[it].bet < s.currentBet || s.seats[it].actedAt < 0) }
            return s.copy(toAct = next)
        }
        return endStreet(s)
    }

    private fun endStreet(s: HoldemState): HoldemState {
        val collected = s.pot + s.seats.sumOf { it.bet }
        val cleared = s.seats.map {
            it.copy(
                bet = 0, actedAt = -1,
                lastAction = if (it.folded) "fold" else if (it.allIn) "allin" else "",
                lastAmount = 0
            )
        }
        val base = s.copy(seats = cleared, pot = collected, currentBet = 0, minRaise = s.bigBlind, raiseSerial = 0, toAct = -1)
        if (s.street == HoldemStreet.RIVER) return showdown(base)
        val activeCount = cleared.count { it.active }
        if (activeCount >= 2) return dealStreet(base, runoutMode = false)
        // No more betting is possible: show the cards and run the board out one street at a time.
        return base.copy(seats = cleared.map { if (it.live) it.copy(revealed = true) else it }, runout = true)
    }

    private fun dealStreet(s: HoldemState, runoutMode: Boolean): HoldemState {
        draw() // burn
        val board = s.board.toMutableList()
        val street: HoldemStreet
        when (s.street) {
            HoldemStreet.PREFLOP -> { repeat(3) { board += draw() }; street = HoldemStreet.FLOP }
            HoldemStreet.FLOP -> { board += draw(); street = HoldemStreet.TURN }
            else -> { board += draw(); street = HoldemStreet.RIVER }
        }
        val dealt = s.copy(board = board, street = street)
        return if (runoutMode) dealt.copy(toAct = -1, runout = true) else settle(dealt, s.button)
    }

    // ---- hand end -------------------------------------------------------------------------------

    private fun finishUncontested(s: HoldemState): HoldemState {
        val live = s.seats.map { it.live }
        val pots = HoldemRules.buildPots(s.seats.map { it.committed }, live)
        return payout(s, pots, scores = null, showdown = false)
    }

    private fun showdown(s: HoldemState): HoldemState {
        val live = s.seats.map { it.live }
        val scores = HashMap<Int, Int>()
        val results = ArrayList<HoldemSeatResult>()
        s.seats.forEachIndexed { i, p ->
            if (p.live) {
                val seven = p.hole + s.board
                scores[i] = HandEvaluator.evaluate(seven.toIntArray())
                results += HoldemSeatResult(i, scores[i]!!, HandEvaluator.bestFive(seven))
            }
        }
        val pots = HoldemRules.buildPots(s.seats.map { it.committed }, live)
        val revealed = s.copy(seats = s.seats.map { if (it.live) it.copy(revealed = true) else it })
        return payout(revealed, pots, scores, showdown = true).copy(seatResults = results)
    }

    private fun payout(s: HoldemState, pots: List<RawPot>, scores: Map<Int, Int>?, showdown: Boolean): HoldemState {
        val n = s.seats.size
        val won = IntArray(n)
        val potResults = ArrayList<HoldemPotResult>()
        for (rp in pots) {
            val winners: List<Int> = if (rp.eligible.size == 1 || scores == null) {
                rp.eligible.take(if (scores == null) 1 else rp.eligible.size)
            } else {
                val best = rp.eligible.maxOf { scores.getValue(it) }
                rp.eligible.filter { scores.getValue(it) == best }
            }
            // The odd chip goes to the first winner clockwise from the seat left of the button.
            val ordered = winners.sortedBy { ((it - (s.button + 1)) % n + n) % n }
            val share = rp.amount / ordered.size
            val odd = rp.amount % ordered.size
            ordered.forEachIndexed { idx, w -> won[w] += share + if (idx < odd) 1 else 0 }
            potResults += HoldemPotResult(rp.amount, rp.eligible, ordered, rp.refund)
        }
        val seats = s.seats.mapIndexed { i, p ->
            val chips = p.chips + won[i]
            p.copy(
                chips = chips, bet = 0, committed = 0, allIn = false, won = won[i],
                busted = chips == 0, lastAction = if (p.folded) "fold" else p.lastAction.takeIf { it == "allin" } ?: ""
            )
        }
        val remaining = seats.indices.filter { !seats[it].busted }
        val humansOut = s.config.endWhenHumansOut && seats.any { !it.isBot } && seats.none { !it.isBot && !it.busted }
        val matchOver = remaining.size <= 1 || humansOut
        val winnerSeat = if (matchOver) seats.indices.filter { !seats[it].busted }.maxByOrNull { seats[it].chips } else null
        // The headline winner: the first winner of the first real pot.
        val headline = potResults.firstOrNull { !it.refund }?.winners?.firstOrNull() ?: potResults.firstOrNull()?.winners?.firstOrNull() ?: -1
        val event = when {
            matchOver -> HoldemEvent.MATCH_OVER
            !showdown -> HoldemEvent.UNCONTESTED
            else -> HoldemEvent.WIN
        }
        val eventSeat = if (matchOver && winnerSeat != null) winnerSeat else headline
        return s.copy(
            seats = seats, pot = 0, currentBet = 0, toAct = -1, runout = false, handOver = true, showdown = showdown,
            results = potResults, seatResults = emptyList(),
            matchOver = matchOver, winnerPlayerId = winnerSeat?.let { seats[it].playerId },
            event = event, eventSeat = eventSeat, eventAmount = if (eventSeat >= 0) won[eventSeat] else 0
        )
    }
}
