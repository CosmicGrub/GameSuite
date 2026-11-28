package com.gamesuite.games.bumprun

import kotlin.random.Random

/**
 * The rules of Bump and Run as a pure engine: no Compose, no clock, no global randomness. The only source of
 * chance is the seed given to [newBoard] (shuffle and reshuffles); every other call is a deterministic function of
 * the state, so a seeded board replays exactly.
 */
interface BumpRunEngine {
    /** A fresh board: all pawns in the Nest (a [helperSeats] seat starts with pawn 0 on its exit square and 3 do-overs). */
    fun newBoard(
        players: List<BumpRunPlayer>,
        rules: BumpRunRules,
        seed: Long,
        helperSeats: Set<Int>,
        starter: Int
    ): BumpRunState

    /** Why a draw would do nothing right now, or NONE. */
    fun explainDraw(state: BumpRunState): BumpRunReject

    /** The seat on turn draws the top card (reshuffling the discards first if the pile is empty). */
    fun draw(state: BumpRunState): BumpRunState

    /** Every legal play for the card in play (empty = no move). Pure and cheap (budget 0.2 ms). */
    fun legalMoves(state: BumpRunState): List<BumpMove>

    /** Why [move] is not legal right now, or NONE. */
    fun explainMove(state: BumpRunState, move: BumpMove): BumpRunReject

    /** Plays [move], including any pad hop, bump and win check, and passes the turn. An illegal move changes nothing. */
    fun play(state: BumpRunState, move: BumpMove): BumpRunState

    /** Spends a do-over: the card in play is discarded and the next one drawn. */
    fun doOver(state: BumpRunState): BumpRunState

    /** Every broken invariant of [state] (empty when consistent); used by tests, restore and the future network host. */
    fun validate(state: BumpRunState): List<String>
}

/** The real engine. Stateless: share one instance freely. */
class StandardBumpRunEngine : BumpRunEngine {

    // --- Set-up ----------------------------------------------------------------------------

    override fun newBoard(
        players: List<BumpRunPlayer>,
        rules: BumpRunRules,
        seed: Long,
        helperSeats: Set<Int>,
        starter: Int
    ): BumpRunState {
        val n = players.size
        val useTeams = rules.teams && n == 4
        val r = rules.copy(teams = useTeams)
        val per = r.pawnsPerSeat
        val pawns = MutableList(n * per) { BumpRunBoard.NEST }
        for (seat in helperSeats) if (seat in 0 until n) pawns[seat * per] = 0
        return BumpRunState(
            rules = r,
            players = players.mapIndexed { i, p -> p.copy(teamId = if (useTeams) i % 2 else -1) },
            sides = BumpRunBoard.sidesFor(n),
            pawns = pawns,
            shields = List(n * per) { false },
            doOvers = List(n) { if (it in helperSeats) HELPER_DO_OVERS else 0 },
            currentPlayerIndex = if (n > 0) starter.coerceIn(0, n - 1) else 0,
            phase = BumpPhase.AWAIT_DRAW,
            pile = shuffledDeck(seed),
            discard = emptyList(),
            bumpsMade = List(n) { 0 },
            bumpsTaken = List(n) { 0 },
            seed = seed
        )
    }

    private fun shuffledDeck(seed: Long): List<Int> = List(BumpRunDeck.SIZE) { it }.shuffled(Random(seed))

    // --- Geometry helpers --------------------------------------------------------------------

    private fun seatOf(s: BumpRunState, pawn: Int) = pawn / s.rules.pawnsPerSeat

    /** Allies share a key: the seat itself, or its team with team play. */
    private fun teamKey(s: BumpRunState, seat: Int) = if (s.rules.teams) seat % 2 else seat

    private fun allied(s: BumpRunState, seatA: Int, seatB: Int) = teamKey(s, seatA) == teamKey(s, seatB)

    /** Pawn index standing on each ring square (-1 empty). */
    private fun occupancy(s: BumpRunState): IntArray {
        val occ = IntArray(BumpRunBoard.LOOP) { -1 }
        for (p in s.pawns.indices) {
            val sq = BumpRunBoard.squareOf(s.sides[seatOf(s, p)], s.pawns[p]) ?: continue
            occ[sq] = p
        }
        return occ
    }

    /** The pawns the seat on turn may move: its own, plus its partner's with team play. */
    private fun controlled(s: BumpRunState): List<Int> {
        val key = teamKey(s, s.currentPlayerIndex)
        return s.pawns.indices.filter { teamKey(s, seatOf(s, it)) == key }
    }

    private fun isRival(s: BumpRunState, pawn: Int) = !allied(s, seatOf(s, pawn), s.currentPlayerIndex)

    /** Why [pawn] could not end on [target] progress, or NONE. */
    private fun landing(s: BumpRunState, occ: IntArray, pawn: Int, target: Int): BumpRunReject {
        if (target > BumpRunBoard.HOME) return BumpRunReject.EXACT_ROLL
        if (target == BumpRunBoard.HOME) return BumpRunReject.NONE
        val seat = seatOf(s, pawn)
        if (target >= BumpRunBoard.LANE_START) {
            val per = s.rules.pawnsPerSeat
            for (k in 0 until per) {
                val other = seat * per + k
                if (other != pawn && s.pawns[other] == target) return BumpRunReject.OWN_PAWN
            }
            return BumpRunReject.NONE
        }
        val sq = BumpRunBoard.squareOf(s.sides[seat], target) ?: return BumpRunReject.CANNOT_MOVE
        val o = occ[sq]
        if (o < 0 || o == pawn) return BumpRunReject.NONE
        if (allied(s, seatOf(s, o), seat)) return BumpRunReject.OWN_PAWN
        if (s.shields[o]) return BumpRunReject.SHIELDED
        return BumpRunReject.NONE
    }

    // --- Move generation ---------------------------------------------------------------------

    private fun hopMoves(s: BumpRunState, occ: IntArray, n: Int): List<BumpMove> {
        val out = ArrayList<BumpMove>()
        for (p in controlled(s)) {
            val g = s.pawns[p]
            if (g < 0 || g >= BumpRunBoard.HOME) continue
            if (landing(s, occ, p, g + n) == BumpRunReject.NONE) out += BumpMove(p, BumpMoveKind.STEP, g + n)
        }
        return out
    }

    private fun exitMoves(s: BumpRunState, occ: IntArray): List<BumpMove> {
        val out = ArrayList<BumpMove>()
        val per = s.rules.pawnsPerSeat
        val key = teamKey(s, s.currentPlayerIndex)
        for (seat in s.players.indices) {
            if (teamKey(s, seat) != key) continue
            val pawn = (0 until per).map { seat * per + it }.firstOrNull { s.pawns[it] == BumpRunBoard.NEST } ?: continue
            if (landing(s, occ, pawn, 0) == BumpRunReject.NONE) out += BumpMove(pawn, BumpMoveKind.EXIT, 0)
        }
        return out
    }

    private fun swapMoves(s: BumpRunState, occ: IntArray): List<BumpMove> {
        val out = ArrayList<BumpMove>()
        val mine = controlled(s).filter { s.pawns[it] in 0 until BumpRunBoard.LOOP }
        val rivals = s.pawns.indices.filter { isRival(s, it) && s.pawns[it] in 0 until BumpRunBoard.LOOP && !s.shields[it] }
        for (a in mine) for (b in rivals) out += BumpMove(a, BumpMoveKind.SWAP, 0, b)
        return out
    }

    private fun chaseMoves(s: BumpRunState, occ: IntArray): List<BumpMove> {
        val out = ArrayList<BumpMove>()
        for (p in controlled(s)) {
            val g = s.pawns[p]
            if (g !in 0 until BumpRunBoard.LOOP) continue
            val sq = BumpRunBoard.squareOf(s.sides[seatOf(s, p)], g) ?: continue
            for (d in 1..BumpRunBoard.CHASE_RANGE) {
                val o = occ[(sq + d) % BumpRunBoard.LOOP]
                if (o < 0 || !isRival(s, o)) continue
                // the nearest rival ahead: shielded or past the lane entry means no chase for this pawn
                if (!s.shields[o] && g + d < BumpRunBoard.LOOP) out += BumpMove(p, BumpMoveKind.CHASE, g + d, o)
                break
            }
        }
        return out
    }

    private fun shieldMoves(s: BumpRunState): List<BumpMove> =
        controlled(s).filter { s.pawns[it] in 0 until BumpRunBoard.LOOP }.map { BumpMove(it, BumpMoveKind.SHIELD) }

    /** The legal plays and the hop distance they use (0 when the card does not play as a hop). */
    private fun plan(s: BumpRunState): Pair<List<BumpMove>, Int> {
        val card = s.card?.let { BumpRunDeck.byId(it) } ?: return emptyList<BumpMove>() to 0
        if (s.phase != BumpPhase.AWAIT_CHOICE) return emptyList<BumpMove>() to 0
        val occ = occupancy(s)
        return when (card.kind) {
            BumpCardKind.HOP -> {
                val moves = hopMoves(s, occ, card.value) + (if (card.exits) exitMoves(s, occ) else emptyList())
                moves to card.value
            }
            BumpCardKind.SWAP -> swapMoves(s, occ) to 0
            BumpCardKind.CHASE -> chaseMoves(s, occ).let { if (it.isNotEmpty()) it to 0 else hopMoves(s, occ, 4) to 4 }
            BumpCardKind.FREE_EXIT -> exitMoves(s, occ).let { if (it.isNotEmpty()) it to 0 else hopMoves(s, occ, 3) to 3 }
            BumpCardKind.SHIELD -> shieldMoves(s).let { if (it.isNotEmpty()) it to 0 else hopMoves(s, occ, 2) to 2 }
            BumpCardKind.AGAIN -> emptyList<BumpMove>() to 0
        }
    }

    override fun legalMoves(state: BumpRunState): List<BumpMove> = plan(state).first

    override fun explainDraw(state: BumpRunState): BumpRunReject = when {
        state.over -> BumpRunReject.OVER
        state.phase == BumpPhase.AWAIT_CHOICE -> BumpRunReject.DECIDE_FIRST
        else -> BumpRunReject.NONE
    }

    override fun explainMove(state: BumpRunState, move: BumpMove): BumpRunReject {
        if (state.over) return BumpRunReject.OVER
        if (state.phase == BumpPhase.AWAIT_DRAW) return BumpRunReject.DRAW_FIRST
        val (legal, hop) = plan(state)
        if (move.kind == BumpMoveKind.PASS) return if (legal.isEmpty()) BumpRunReject.NONE else BumpRunReject.MOVES_AVAILABLE
        if (move in legal) return BumpRunReject.NONE
        if (move.kind == BumpMoveKind.STEP && hop > 0 && move.pawn in state.pawns.indices && move.pawn in controlled(state)) {
            val g = state.pawns[move.pawn]
            if (g in 0 until BumpRunBoard.HOME) {
                val r = landing(state, occupancy(state), move.pawn, g + hop)
                if (r != BumpRunReject.NONE) return r
            }
        }
        return BumpRunReject.CANNOT_MOVE
    }

    // --- Drawing -----------------------------------------------------------------------------

    override fun draw(state: BumpRunState): BumpRunState {
        if (explainDraw(state) != BumpRunReject.NONE) return state
        var pile = state.pile
        var discard = state.discard
        var reshuffles = state.reshuffles
        var reshuffled = false
        if (pile.isEmpty()) {
            if (discard.isEmpty()) return state
            reshuffles += 1
            pile = discard.shuffled(Random(state.seed xor (reshuffles.toLong() * GOLDEN)))
            discard = emptyList()
            reshuffled = true
        }
        val cardId = pile.last()
        pile = pile.dropLast(1)
        val seat = state.currentPlayerIndex
        val base = state.copy(
            pile = pile, discard = discard, reshuffles = reshuffles, eventReshuffled = reshuffled,
            eventSeat = seat, eventCard = cardId, eventPawn = -1, eventFrom = BumpRunBoard.NEST, eventTo = BumpRunBoard.NEST,
            eventVictim = -1, eventPadFrom = -1, turnSerial = state.turnSerial + 1
        )
        val card = BumpRunDeck.byId(cardId)!!
        if (card.kind == BumpCardKind.AGAIN) {
            return base.copy(event = BumpRunEvent.AGAIN, discard = discard + cardId, card = null, phase = BumpPhase.AWAIT_DRAW)
        }
        val held = base.copy(event = BumpRunEvent.DRAW, card = cardId, phase = BumpPhase.AWAIT_CHOICE)
        if (plan(held).first.isEmpty() && held.doOvers[seat] == 0) {
            return advance(held.copy(event = BumpRunEvent.NO_MOVE, discard = discard + cardId, card = null))
        }
        return held
    }

    override fun doOver(state: BumpRunState): BumpRunState {
        if (state.over || state.phase != BumpPhase.AWAIT_CHOICE) return state
        val seat = state.currentPlayerIndex
        val card = state.card ?: return state
        if (state.doOvers[seat] <= 0) return state
        val released = state.copy(
            doOvers = state.doOvers.mapIndexed { i, d -> if (i == seat) d - 1 else d },
            discard = state.discard + card, card = null, phase = BumpPhase.AWAIT_DRAW
        )
        val drawn = draw(released)
        return if (drawn.event == BumpRunEvent.DRAW) drawn.copy(event = BumpRunEvent.DO_OVER) else drawn
    }

    // --- Playing -----------------------------------------------------------------------------

    private fun passTurn(s: BumpRunState): BumpRunState {
        val card = s.card ?: return s
        return advance(
            s.copy(
                event = BumpRunEvent.NO_MOVE, eventSeat = s.currentPlayerIndex, eventCard = card, eventPawn = -1,
                eventVictim = -1, eventPadFrom = -1, eventFrom = BumpRunBoard.NEST, eventTo = BumpRunBoard.NEST,
                discard = s.discard + card, card = null
            )
        )
    }

    /** Hands the turn to the next seat (expiring that seat's shields) and counts the turn. */
    private fun advance(s: BumpRunState): BumpRunState {
        val n = s.players.size
        val next = (s.currentPlayerIndex + 1) % n
        val key = teamKey(s, next)
        val shields = s.shields.mapIndexed { p, on -> on && teamKey(s, seatOf(s, p)) != key }
        return s.copy(
            currentPlayerIndex = next, shields = shields, phase = BumpPhase.AWAIT_DRAW, card = null,
            turnsTaken = s.turnsTaken + 1, turnSerial = s.turnSerial + 1
        )
    }

    override fun play(state: BumpRunState, move: BumpMove): BumpRunState {
        if (state.over || state.phase != BumpPhase.AWAIT_CHOICE) return state
        val legal = plan(state).first
        if (move.kind == BumpMoveKind.PASS) return if (legal.isEmpty()) passTurn(state) else state
        if (move !in legal) return state

        val seat = state.currentPlayerIndex
        val pawns = state.pawns.toMutableList()
        val shields = state.shields.toMutableList()
        val made = state.bumpsMade.toMutableList()
        val taken = state.bumpsTaken.toMutableList()
        val occ = occupancy(state)
        val p = move.pawn
        val from = pawns[p]
        var victim = -1
        var padFrom = -1
        var kind = BumpRunEvent.MOVE

        fun bump(o: Int) {
            made[seat] = made[seat] + 1
            taken[seatOf(state, o)] = taken[seatOf(state, o)] + 1
            pawns[o] = BumpRunBoard.NEST
            shields[o] = false
            victim = o
        }

        fun squareHolder(side: Int, progress: Int): Int {
            val sq = BumpRunBoard.squareOf(side, progress) ?: return -1
            for (q in pawns.indices) {
                if (q == p) continue
                if (BumpRunBoard.squareOf(state.sides[seatOf(state, q)], pawns[q]) == sq) return q
            }
            return -1
        }

        when (move.kind) {
            BumpMoveKind.STEP, BumpMoveKind.CHASE, BumpMoveKind.EXIT -> {
                val side = state.sides[seatOf(state, p)]
                val to = move.to
                val o = if (to in 0 until BumpRunBoard.LOOP) occ[BumpRunBoard.squareOf(side, to)!!] else -1
                pawns[p] = to
                if (o >= 0 && o != p) bump(o)
                var end = to
                if (state.rules.pads && to in 0 until BumpRunBoard.LOOP && BumpRunBoard.squareOf(side, to) in BumpRunBoard.PADS) {
                    val hopped = to + BumpRunBoard.PAD_HOP
                    if (hopped < BumpRunBoard.LOOP) {
                        val h = squareHolder(side, hopped)
                        val free = h < 0
                        val canBump = h >= 0 && !allied(state, seatOf(state, h), seat) && !shields[h]
                        if (free || canBump) {
                            if (canBump) bump(h)
                            padFrom = to
                            end = hopped
                            pawns[p] = hopped
                        }
                    }
                }
                kind = when {
                    end == BumpRunBoard.HOME -> BumpRunEvent.HOME
                    victim >= 0 -> BumpRunEvent.BUMP
                    padFrom >= 0 -> BumpRunEvent.PAD
                    move.kind == BumpMoveKind.EXIT -> BumpRunEvent.EXIT
                    else -> BumpRunEvent.MOVE
                }
                // a bump on the way to Home cannot happen (Home holds everyone), so HOME never hides a bump
            }
            BumpMoveKind.SWAP -> {
                val b = move.target
                val sqa = BumpRunBoard.squareOf(state.sides[seatOf(state, p)], pawns[p])!!
                val sqb = BumpRunBoard.squareOf(state.sides[seatOf(state, b)], pawns[b])!!
                pawns[p] = BumpRunBoard.progressOn(state.sides[seatOf(state, p)], sqb)
                pawns[b] = BumpRunBoard.progressOn(state.sides[seatOf(state, b)], sqa)
                victim = b
                kind = BumpRunEvent.SWAP
            }
            BumpMoveKind.SHIELD -> {
                shields[p] = true
                kind = BumpRunEvent.SHIELD
            }
            BumpMoveKind.PASS -> return state
        }

        val cardId = state.card!!
        val moved = state.copy(
            pawns = pawns, shields = shields, bumpsMade = made, bumpsTaken = taken,
            event = kind, eventSeat = seat, eventPawn = p, eventFrom = from, eventTo = pawns[p],
            eventVictim = victim, eventPadFrom = padFrom, eventCard = cardId, eventReshuffled = false,
            discard = state.discard + cardId, card = null, turnSerial = state.turnSerial + 1
        )
        val winners = winners(moved)
        if (winners.isNotEmpty()) {
            return moved.copy(event = BumpRunEvent.WIN, phase = BumpPhase.OVER, winnerPlayerIds = winners, turnsTaken = moved.turnsTaken + 1)
        }
        return advance(moved)
    }

    /** Player ids of the seat (or team) whose pawns are all Home; empty when nobody has finished. */
    private fun winners(s: BumpRunState): List<String> {
        val per = s.rules.pawnsPerSeat
        val done = s.players.indices.filter { seat -> (0 until per).all { s.pawns[seat * per + it] == BumpRunBoard.HOME } }
        if (done.isEmpty()) return emptyList()
        if (!s.rules.teams) return listOf(s.players[done.first()].playerId)
        // team play: both seats of a team must be finished
        for (team in 0..1) {
            val seats = s.players.indices.filter { it % 2 == team }
            if (seats.all { it in done }) return seats.map { s.players[it].playerId }
        }
        return emptyList()
    }

    // --- Validation --------------------------------------------------------------------------

    override fun validate(state: BumpRunState): List<String> {
        val out = ArrayList<String>()
        val n = state.players.size
        val per = state.rules.pawnsPerSeat
        if (n !in 2..BumpRunBoard.MAX_SEATS) { out += "seat count"; return out }
        if (state.sides != BumpRunBoard.sidesFor(n)) out += "sides"
        if (state.pawns.size != n * per) { out += "pawn count"; return out }
        if (state.shields.size != n * per) { out += "shield count"; return out }
        if (state.pawns.any { it !in BumpRunBoard.NEST..BumpRunBoard.HOME }) { out += "pawn progress"; return out }
        if (state.rules.teams) {
            if (n != 4) out += "teams need four seats"
            if (state.players.indices.any { state.players[it].teamId != it % 2 }) out += "team ids"
        } else if (state.players.any { it.teamId != -1 }) out += "team ids"
        if (state.doOvers.size != n || state.doOvers.any { it !in 0..HELPER_DO_OVERS }) out += "do-overs"
        if (state.bumpsMade.size != n || state.bumpsTaken.size != n || state.bumpsMade.any { it < 0 } || state.bumpsTaken.any { it < 0 }) out += "stats"
        if (state.currentPlayerIndex !in 0 until n) out += "current seat"
        if (state.eventSeat != null && state.eventSeat !in 0 until n) out += "event seat"
        if (state.event !in BumpRunEvent.ALL) out += "event"
        if (state.eventPawn !in -1 until n * per || state.eventVictim !in -1 until n * per) out += "event pawn"
        if (state.eventCard !in -1 until BumpRunDeck.SIZE) out += "event card"
        if (state.turnSerial < 0 || state.turnsTaken < 0 || state.reshuffles < 0) out += "serials"
        // one pawn per ring square, one per own lane square, shields only on the ring
        val ring = HashSet<Int>()
        for (p in state.pawns.indices) {
            val seat = p / per
            val g = state.pawns[p]
            val sq = BumpRunBoard.squareOf(state.sides[seat.coerceIn(0, n - 1)], g)
            if (sq != null && !ring.add(sq)) out += "two pawns on ring square $sq"
            if (state.shields[p] && g !in 0 until BumpRunBoard.LOOP) out += "shield off the ring"
        }
        for (seat in 0 until n) {
            val lane = (0 until per).map { state.pawns[seat * per + it] }.filter { it in BumpRunBoard.LANE_START until BumpRunBoard.HOME }
            if (lane.size != lane.toSet().size) out += "two pawns on a lane square"
        }
        // cards: pile + discard + card in play are the 54 ids exactly once
        val cards = state.pile + state.discard + listOfNotNull(state.card)
        if (cards.size != BumpRunDeck.SIZE || cards.toSet() != (0 until BumpRunDeck.SIZE).toSet()) out += "cards"
        when (state.phase) {
            BumpPhase.AWAIT_CHOICE -> if (state.card == null) out += "choice needs a card"
            else -> if (state.card != null) out += "card without a choice"
        }
        if (state.card != null && BumpRunDeck.byId(state.card)?.kind == BumpCardKind.AGAIN) out += "again in play"
        val expected = winners(state)
        if (state.over != expected.isNotEmpty() || state.winnerPlayerIds.toSet() != expected.toSet()) out += "winner"
        return out
    }

    companion object {
        const val HELPER_DO_OVERS = 3
        private const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15
    }
}
