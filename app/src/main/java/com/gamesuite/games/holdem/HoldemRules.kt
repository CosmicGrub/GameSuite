package com.gamesuite.games.holdem

/** The blind schedule: the big blind starts at 2% of the starting stack and climbs through fixed multiples. */
object HoldemBlinds {
    val LEVEL_MULTIPLIERS: IntArray = intArrayOf(1, 2, 3, 5, 8, 12, 20, 30, 50, 80, 120, 200)
    val MAX_LEVEL: Int = LEVEL_MULTIPLIERS.size - 1

    fun baseBigBlind(startingChips: Int): Int = maxOf(2, (startingChips / 50) / 2 * 2)

    fun bigBlindAt(startingChips: Int, level: Int): Int =
        baseBigBlind(startingChips) * LEVEL_MULTIPLIERS[level.coerceIn(0, MAX_LEVEL)]

    fun smallBlindAt(startingChips: Int, level: Int): Int = bigBlindAt(startingChips, level) / 2

    /** The level in force for 1-based hand [handNumber]. */
    fun levelForHand(speed: BlindSpeed, handNumber: Int): Int =
        if (speed.handsPerLevel <= 0) 0 else ((handNumber - 1).coerceAtLeast(0) / speed.handsPerLevel).coerceAtMost(MAX_LEVEL)

    /** Hands left (counting [handNumber]) before the blinds rise, or null if they never do or are at the top. */
    fun handsUntilRise(speed: BlindSpeed, handNumber: Int): Int? {
        if (speed.handsPerLevel <= 0) return null
        val level = levelForHand(speed, handNumber)
        if (level >= MAX_LEVEL) return null
        return (level + 1) * speed.handsPerLevel - (handNumber - 1)
    }
}

/** A pot before anyone has won it. */
data class RawPot(val amount: Int, val eligible: List<Int>, val refund: Boolean)

object HoldemRules {

    /** What the seat on turn may do, or null when [seat] is not the seat on turn. */
    fun options(s: HoldemState, seat: Int): HoldemOptions? {
        if (s.handOver || s.matchOver || seat !in s.seats.indices || s.toAct != seat) return null
        val p = s.seats[seat]
        if (!p.active) return null
        val toCall = (s.currentBet - p.bet).coerceAtLeast(0)
        val allInTo = p.bet + p.chips
        val reopened = p.actedAt < s.raiseSerial
        val canRaise = reopened && p.chips > toCall
        val minRaiseTo = minOf(s.currentBet + maxOf(s.minRaise, 1), allInTo)
        return HoldemOptions(
            canCheck = toCall == 0,
            canCall = toCall > 0,
            callAmount = minOf(toCall, p.chips),
            canRaise = canRaise,
            minRaiseTo = minRaiseTo,
            maxRaiseTo = allInTo,
            canAllIn = p.chips > 0 && (canRaise || p.chips <= toCall),
            allInTo = allInTo,
            toCall = toCall
        )
    }

    /** [HoldemReject.NONE] when [action] is legal for [seat] right now. */
    fun validate(s: HoldemState, seat: Int, action: HoldemAction): HoldemReject {
        if (s.matchOver) return HoldemReject.MATCH_OVER
        if (s.seats.isEmpty() || s.handNumber == 0) return HoldemReject.NO_HAND
        if (s.handOver) return HoldemReject.HAND_OVER
        if (seat !in s.seats.indices) return HoldemReject.BAD_SEAT
        if (s.toAct != seat) return HoldemReject.NOT_YOUR_TURN
        val o = options(s, seat) ?: return HoldemReject.NOT_YOUR_TURN
        val p = s.seats[seat]
        return when (action.kind) {
            HoldemActionKind.FOLD -> HoldemReject.NONE
            HoldemActionKind.CHECK -> if (o.canCheck) HoldemReject.NONE else HoldemReject.MUST_CALL
            HoldemActionKind.CALL -> if (o.canCall) HoldemReject.NONE else HoldemReject.NOTHING_TO_CALL
            HoldemActionKind.ALL_IN -> when {
                p.chips <= 0 -> HoldemReject.NO_CHIPS
                !o.canAllIn -> HoldemReject.CANNOT_RAISE
                else -> HoldemReject.NONE
            }
            HoldemActionKind.RAISE_TO -> when {
                p.actedAt >= s.raiseSerial && p.chips > o.toCall -> HoldemReject.CANNOT_RAISE
                !o.canRaise -> HoldemReject.ABOVE_STACK
                action.amount > o.maxRaiseTo -> HoldemReject.ABOVE_STACK
                action.amount <= s.currentBet -> HoldemReject.BELOW_MIN_RAISE
                action.amount < o.minRaiseTo -> HoldemReject.BELOW_MIN_RAISE
                else -> HoldemReject.NONE
            }
        }
    }

    /**
     * Splits what every seat put in ([committed], whole hand) into the main pot and side pots. A layer is
     * contested only by live seats that put in at least that much; folded seats leave their chips behind. A
     * top layer only one seat can win is that seat's uncalled bet coming back ([RawPot.refund]).
     */
    fun buildPots(committed: List<Int>, live: List<Boolean>): List<RawPot> {
        val levels = committed.indices.filter { live[it] && committed[it] > 0 }.map { committed[it] }.distinct().sorted()
        val pots = ArrayList<RawPot>()
        var prev = 0
        for (level in levels) {
            if (level <= prev) continue
            var amount = 0
            var others = 0
            val eligible = ArrayList<Int>()
            for (i in committed.indices) {
                val part = (minOf(committed[i], level) - minOf(committed[i], prev)).coerceAtLeast(0)
                amount += part
                if (live[i] && committed[i] >= level) eligible += i else others += part
            }
            if (amount > 0 && eligible.isNotEmpty()) {
                val last = pots.lastOrNull()
                if (last != null && last.eligible == eligible) {
                    pots[pots.lastIndex] = RawPot(last.amount + amount, eligible, last.refund && others == 0)
                } else {
                    pots += RawPot(amount, eligible, refund = eligible.size == 1 && others == 0 && pots.isNotEmpty())
                }
            }
            prev = level
        }
        val accounted = pots.sumOf { it.amount }
        val total = committed.sum()
        if (total > accounted) {
            if (pots.isEmpty()) {
                // Nobody live put anything in: cannot happen in a real hand, keep the chips anyway.
                val first = live.indices.firstOrNull { live[it] }
                if (first != null) pots += RawPot(total - accounted, listOf(first), false)
            } else {
                val last = pots.last()
                pots[pots.lastIndex] = RawPot(last.amount + (total - accounted), last.eligible, false)
            }
        }
        return pots
    }

    /**
     * Whether [s] is a state the engine could have produced. [deck] (with [deckPos]) is only checked by the
     * host or a local game, which are the only places that hold it; a guest passes null. [rosterIds] is the
     * seating the match was launched with.
     */
    fun isConsistent(s: HoldemState, rosterIds: List<String>? = null, deck: List<Int>? = null, deckPos: Int = 0): Boolean {
        val n = s.seats.size
        if (n !in 2..6) return false
        if (rosterIds != null && (rosterIds.size != n || rosterIds.indices.any { rosterIds[it] != s.seats[it].playerId })) return false
        val cfg = s.config
        if (cfg.startingChips !in HoldemConfig.MIN_START..HoldemConfig.MAX_START) return false
        if (s.chipsInPlay != n * cfg.startingChips) return false
        if (s.seats.any { it.chips < 0 || it.bet < 0 || it.committed < it.bet || it.won < 0 }) return false
        if (s.pot < 0 || s.pot != s.seats.sumOf { it.committed } - s.seats.sumOf { it.bet }) return false
        if (s.button !in 0 until n) return false
        if (s.smallBlindSeat !in -1 until n || s.bigBlindSeat !in -1 until n) return false
        if (s.handNumber < 0) return false
        if (s.blindLevel !in 0..HoldemBlinds.MAX_LEVEL) return false
        if (s.bigBlind != HoldemBlinds.bigBlindAt(cfg.startingChips, s.blindLevel) ||
            s.smallBlind != HoldemBlinds.smallBlindAt(cfg.startingChips, s.blindLevel)
        ) return false
        if (s.event !in HoldemEvent.ALL || s.eventSerial < 0) return false
        if (s.eventSeat !in -1 until n) return false
        // busted seats hold nothing; a finished hand leaves no chips committed
        if (s.seats.any { it.busted && it.chips != 0 }) return false
        if (s.seats.none { !it.busted }) return false
        if (s.handOver && (s.pot != 0 || s.seats.any { it.bet != 0 || it.committed != 0 })) return false
        if (s.matchOver != (s.winnerPlayerId != null)) return false
        if (s.matchOver && !s.handOver) return false
        if (s.winnerPlayerId != null && s.seats.none { it.playerId == s.winnerPlayerId }) return false
        // board
        if (s.board.size > 5 || s.board.any { !HoldemCards.isValid(it) } || s.board.toSet().size != s.board.size) return false
        if (!s.handOver || s.showdown) {
            val expected = when (s.street) {
                HoldemStreet.PREFLOP -> 0
                HoldemStreet.FLOP -> 3
                HoldemStreet.TURN -> 4
                HoldemStreet.RIVER -> 5
            }
            if (s.board.size != expected) return false
        }
        // hole cards
        val known = ArrayList<Int>(s.board)
        for (p in s.seats) {
            if (!p.inHand) {
                if (p.hole.isNotEmpty()) return false
                continue
            }
            if (s.handNumber == 0 || p.hole.size != 2) return false
            for (c in p.hole) {
                if (c == HIDDEN_CARD) {
                    if (deck != null) return false
                } else if (!HoldemCards.isValid(c)) {
                    return false
                } else {
                    known += c
                }
            }
        }
        if (known.toSet().size != known.size) return false
        // turn
        if (s.handOver || s.runout) {
            if (s.toAct != -1) return false
        } else {
            val a = s.toAct
            if (a !in 0 until n || !s.seats[a].active) return false
            if (s.currentBet < s.seats.maxOf { it.bet } || s.minRaise < 1) return false
        }
        if (s.runout && (s.handOver || s.seats.count { it.live } < 2)) return false
        if (!s.handOver && s.seats.count { it.live } < 2) return false
        // results
        for (r in s.results) {
            if (r.amount < 0 || r.eligible.any { it !in 0 until n } || r.winners.any { it !in 0 until n }) return false
        }
        if (s.results.isNotEmpty() && !s.handOver) return false
        for (sr in s.seatResults) {
            if (sr.seat !in 0 until n || sr.best.size != 5 || sr.best.any { !HoldemCards.isValid(it) }) return false
        }
        if (deck != null) {
            if (deck.size != HoldemCards.DECK_SIZE || deck.toSet().size != HoldemCards.DECK_SIZE || deck.any { !HoldemCards.isValid(it) }) return false
            if (deckPos !in 0..HoldemCards.DECK_SIZE) return false
            if (!deck.take(deckPos).toSet().containsAll(known)) return false
        }
        return true
    }
}
