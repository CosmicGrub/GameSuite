package com.gamesuite.games.blackjack

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class SnapCard(val id: Int, val uid: Int)

@Serializable
private data class SnapHand(
    val cards: List<SnapCard>, val bet: Int, val doubled: Boolean, val fromSplit: Boolean, val splitAces: Boolean,
    val surrendered: Boolean, val stood: Boolean, val outcome: String? = null, val credit: Int = 0
)

@Serializable
private data class SnapSeat(
    val playerId: String, val displayName: String, val isBot: Boolean, val stack: Int, val rebuys: Int,
    val bet: Int, val betPlaced: Boolean, val sittingOut: Boolean, val hands: List<SnapHand>,
    val insurance: Int, val insuranceDecided: Boolean, val insuranceCredit: Int, val roundNet: Int,
    val roundsPlayed: Int, val won: Int, val lost: Int, val pushed: Int, val blackjacks: Int
)

@Serializable
private data class Snap(
    val seats: List<SnapSeat>, val dealer: List<SnapCard>, val holeHidden: Boolean, val phase: String,
    val activeSeat: Int, val activeHand: Int, val round: Int, val event: String, val eventSeat: Int,
    val eventHand: Int, val serial: Int, val nextUid: Int, val reshuffled: Boolean, val roundsDone: Int,
    val config: BjConfig, val shoe: List<Int>, val discard: List<Int>, val difficulty: String
)

private val snapJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Blackjack for 1 to 4 seats against the dealer, with PLAY CHIPS only (no money, no cash-out).
 *
 * A round is: every seat bets in turn, everyone is dealt two cards (the dealer one up, one down), an Ace
 * upcard offers insurance, the dealer peeks for a natural, then each seat plays each of its hands (hit,
 * stand, double, split, late surrender), then the dealer reveals and draws by the fixed table rule
 * (stands on every 17, or hits soft 17 when [BjConfig.hitSoft17]). A seat that cannot cover the minimum
 * bet may rebuy to the starting stack (counted) or sit the round out.
 *
 * THE SHOE: built from the injected [random] only; the order is private to this engine (never in
 * [BjState], never sent to a guest). Reshuffled at the start of a round once under a quarter remains.
 * In a networked game only the host deals; a guest sends requests for its own seat. The dealer's hole
 * card is sent to guests as a face-down placeholder until the host reveals it.
 *
 * There is deliberately no Undo: taking back a hit or a bet would let a player see the next card and
 * then change their mind, which turns the shoe into an open book. [hint] (basic strategy, which uses
 * only the dealer's upcard) is offered instead.
 */
class BlackjackGame(private val random: Random = Random.Default) : GameModule, ResumableGame {
    override val gameId = "blackjack"
    override val displayName = "Blackjack"
    override val category = GameCategory.CARD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<BjState?>(null)

    /** Rules for the session (the Hub's options); a restored save or a host sync carries its own. */
    var config: BjConfig = BjConfig()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    /** The action the last hint suggested (null when none). */
    val hintAction = mutableStateOf<BjAction?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val shoe = ArrayDeque<Card>()
    private val discard = ArrayList<Card>()
    private var uid = 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    override fun init(context: GameContext) {
        require(context.players.size in 1..4) { "Blackjack needs 1 to 4 seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        hintAction.value = null
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        shoe.clear(); discard.clear(); uid = 0
        if (isNetworked) registerNetworkListeners()
    }

    // --- Shoe ------------------------------------------------------------------------------

    private fun freshShoe(): List<Card> {
        val all = ArrayList<Card>()
        repeat(config.decks) { all += Deck.fresh52() }
        return all.shuffled(random)
    }

    private fun draw(): BjCard {
        if (shoe.isEmpty()) {
            if (discard.isNotEmpty()) {
                shoe.addAll(discard.shuffled(random)); discard.clear()
            } else {
                shoe.addAll(freshShoe())
            }
        }
        return BjCard(shoe.removeFirst(), uid++)
    }

    /** Test hook: the next cards dealt, top first. Replaces the shoe; the discard is emptied. */
    internal fun stackShoe(cards: List<Card>) {
        shoe.clear(); shoe.addAll(cards); discard.clear()
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: BjState): Boolean {
        if (matchOver.value || s.phase == BjPhase.ROUND_OVER || s.phase == BjPhase.DEALER) return false
        val seat = s.seats.getOrNull(s.activeSeat) ?: return false
        if (seat.isBot) return false
        return if (isNetworked) !hostLeft.value && s.activeSeat == context.localPlayerIndex else true
    }

    fun legalActions(): Set<BjAction> = state.value?.let { legalActions(it) } ?: emptySet()

    fun legalActions(s: BjState): Set<BjAction> {
        if (s.phase != BjPhase.PLAYING) return emptySet()
        val seat = s.seats.getOrNull(s.activeSeat) ?: return emptySet()
        val hand = seat.hands.getOrNull(s.activeHand) ?: return emptySet()
        if (hand.done) return emptySet()
        val out = linkedSetOf(BjAction.HIT, BjAction.STAND)
        val two = hand.cards.size == 2
        if (two && seat.stack >= hand.bet) out += BjAction.DOUBLE
        if (two && BjRules.isPair(hand.plain) && seat.hands.size < BjRules.MAX_HANDS && seat.stack >= hand.bet) out += BjAction.SPLIT
        if (s.config.surrender && two && seat.hands.size == 1 && !hand.fromSplit) out += BjAction.SURRENDER
        return out
    }

    fun explainAction(action: BjAction): BjReject {
        val s = state.value ?: return BjReject.OVER
        if (matchOver.value) return BjReject.OVER
        if (!isMyTurn(s)) return BjReject.NOT_YOUR_TURN
        if (s.phase != BjPhase.PLAYING) return BjReject.WRONG_PHASE
        if (action in legalActions(s)) return BjReject.NONE
        val seat = s.seats[s.activeSeat]
        val hand = seat.hands[s.activeHand]
        return when (action) {
            BjAction.DOUBLE -> if (hand.cards.size != 2) BjReject.NOT_FIRST_TWO else BjReject.CANT_AFFORD
            BjAction.SPLIT -> when {
                hand.cards.size != 2 || !BjRules.isPair(hand.plain) -> BjReject.NOT_PAIR
                seat.hands.size >= BjRules.MAX_HANDS -> BjReject.MAX_HANDS
                else -> BjReject.CANT_AFFORD
            }
            BjAction.SURRENDER -> if (!s.config.surrender) BjReject.SURRENDER_OFF else BjReject.NOT_FIRST_TWO
            else -> BjReject.WRONG_PHASE
        }
    }

    fun explainBet(amount: Int): BjReject {
        val s = state.value ?: return BjReject.OVER
        if (matchOver.value) return BjReject.OVER
        if (s.phase != BjPhase.BETTING) return BjReject.WRONG_PHASE
        if (!isMyTurn(s)) return BjReject.NOT_YOUR_TURN
        val seat = s.seats[s.activeSeat]
        return when {
            amount < BjRules.MIN_BET || amount % BjRules.MIN_BET != 0 -> BjReject.BAD_BET
            amount > seat.stack -> BjReject.CANT_AFFORD
            else -> BjReject.NONE
        }
    }

    fun canRebuy(): Boolean {
        val s = state.value ?: return false
        return s.phase == BjPhase.BETTING && isMyTurn(s) && s.seats[s.activeSeat].stack < BjRules.MIN_BET
    }

    /** Chips a seat is worth against what it put in (starting stack plus rebuys). */
    fun netOf(s: BjState, seatIndex: Int): Int {
        val seat = s.seats[seatIndex]
        return seat.stack - s.config.startingChips * (1 + seat.rebuys)
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        shoe.clear(); discard.clear()
        shoe.addAll(freshShoe())
        val seats = context.players.map {
            BjSeat(it.playerId, it.displayName, it.isBot, config.startingChips)
        }
        uid = 0
        state.value = null
        commit(
            BjState(
                seats = seats, dealer = emptyList(), holeHidden = true, phase = BjPhase.BETTING, activeSeat = 0,
                config = config
            )
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** Ends the session: the seat with the most chips won beyond its stake wins. Nothing played counts as a draw. */
    fun leaveSession() {
        if (matchOver.value) return
        val s = state.value
        val scores = if (s == null) {
            context.players.map { PlayerScore(it.playerId, 0, false) }
        } else {
            val nets = s.seats.indices.map { netOf(s, it) }
            val best = nets.maxOrNull() ?: 0
            val leaders = nets.count { it == best }
            s.seats.mapIndexed { i, seat ->
                PlayerScore(seat.playerId, nets[i], isWinner = best > 0 && nets[i] == best && leaders == 1)
            }
        }
        endMatch(GameResult(scores = scores))
    }

    // --- Commit / state helpers ------------------------------------------------------------

    private fun commit(ns: BjState) {
        state.value = ns.copy(nextUid = uid, shoeLeft = shoe.size, serial = (state.value?.serial ?: -1) + 1)
        hintAction.value = null
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun BjState.withSeat(i: Int, f: (BjSeat) -> BjSeat) =
        copy(seats = seats.mapIndexed { k, seat -> if (k == i) f(seat) else seat })

    private fun BjState.withHand(si: Int, hi: Int, f: (BjHand) -> BjHand) = withSeat(si) { seat ->
        seat.copy(hands = seat.hands.mapIndexed { k, h -> if (k == hi) f(h) else h })
    }

    private fun enterBetting(prev: BjState, round: Int, event: String) {
        prev.seats.forEach { seat -> seat.hands.forEach { h -> discard += h.plain } }
        discard += prev.dealer.map { it.card }.filter { it.id >= 0 }
        var reshuffled = false
        if (shoe.size < config.decks * 52 / 4) {
            shoe.clear()
            discard.clear()
            shoe.addAll(freshShoe())
            reshuffled = true
        }
        uid = 0
        commit(
            prev.copy(
                seats = prev.seats.map {
                    it.copy(
                        bet = 0, betPlaced = false, sittingOut = false, hands = emptyList(), insurance = 0,
                        insuranceDecided = false, insuranceCredit = 0, roundNet = 0
                    )
                },
                dealer = emptyList(), holeHidden = true, phase = BjPhase.BETTING, activeSeat = 0, activeHand = 0,
                round = round, event = event, eventSeat = -1, eventHand = 0, reshuffled = reshuffled
            )
        )
    }

    // --- Local entry points ----------------------------------------------------------------

    fun bet(amount: Int): Boolean {
        if (explainBet(amount) != BjReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.BetRequest(amount)); return true }
        return doBet(amount)
    }

    fun rebuy(): Boolean {
        if (!canRebuy()) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.RebuyRequest); return true }
        return doRebuy()
    }

    fun sitOut(): Boolean {
        if (!canRebuy()) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.SitOutRequest); return true }
        return doSitOut()
    }

    fun insurance(take: Boolean): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != BjPhase.INSURANCE || !isMyTurn(s)) return false
        if (take && !canInsure(s)) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.InsuranceRequest(take)); return true }
        return doInsurance(take)
    }

    fun act(action: BjAction): Boolean {
        if (explainAction(action) != BjReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.ActionRequest(action.name)); return true }
        return doAct(action)
    }

    fun canInsure(s: BjState): Boolean {
        val seat = s.seats.getOrNull(s.activeSeat) ?: return false
        return s.phase == BjPhase.INSURANCE && seat.betPlaced && seat.stack >= seat.bet / 2
    }

    fun nextRound(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != BjPhase.ROUND_OVER || hostLeft.value) return false
        if (isNetworked && !isHost) { sendToHost(BjNetMessage.NextRoundRequest); return true }
        return doNextRound()
    }

    // --- Engine: betting -------------------------------------------------------------------

    private fun doBet(amount: Int): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.BETTING) return false
        val i = s.activeSeat
        val seat = s.seats.getOrNull(i) ?: return false
        if (amount < BjRules.MIN_BET || amount % BjRules.MIN_BET != 0 || amount > seat.stack) return false
        val ns = s.withSeat(i) { it.copy(stack = it.stack - amount, bet = amount, betPlaced = true) }
            .copy(event = BjEvent.BET, eventSeat = i)
        advanceBetting(ns)
        return true
    }

    private fun doRebuy(): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.BETTING) return false
        val i = s.activeSeat
        val seat = s.seats.getOrNull(i) ?: return false
        if (seat.stack >= BjRules.MIN_BET) return false
        commit(
            s.withSeat(i) { it.copy(stack = it.stack + s.config.startingChips, rebuys = it.rebuys + 1) }
                .copy(event = BjEvent.REBUY, eventSeat = i)
        )
        return true
    }

    private fun doSitOut(): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.BETTING) return false
        val i = s.activeSeat
        val seat = s.seats.getOrNull(i) ?: return false
        if (seat.stack >= BjRules.MIN_BET) return false
        advanceBetting(s.withSeat(i) { it.copy(sittingOut = true) }.copy(event = BjEvent.SIT_OUT, eventSeat = i))
        return true
    }

    private fun advanceBetting(s: BjState) {
        val next = s.activeSeat + 1
        if (next < s.seats.size) {
            commit(s.copy(activeSeat = next))
            return
        }
        if (s.seats.none { it.betPlaced }) {
            enterBetting(s, s.round, BjEvent.NO_BETS)
            return
        }
        deal(s)
    }

    // --- Engine: the deal ------------------------------------------------------------------

    private fun deal(s0: BjState) {
        val seats = s0.seats
        val first = HashMap<Int, BjCard>()
        val second = HashMap<Int, BjCard>()
        seats.forEachIndexed { i, seat -> if (seat.betPlaced) first[i] = draw() }
        val up = draw()
        seats.forEachIndexed { i, seat -> if (seat.betPlaced) second[i] = draw() }
        val hole = draw()
        val dealt = s0.copy(
            seats = seats.mapIndexed { i, seat ->
                if (!seat.betPlaced) seat
                else {
                    val h = BjHand(listOf(first.getValue(i), second.getValue(i)), seat.bet)
                    seat.copy(hands = listOf(if (h.natural) h.copy(stood = true) else h))
                }
            },
            dealer = listOf(up, hole), holeHidden = true, event = BjEvent.DEAL, eventSeat = -1
        )
        if (up.card.rank == Rank.ACE && s0.config.insurance) {
            val firstEligible = dealt.seats.indexOfFirst { it.betPlaced && it.stack >= it.bet / 2 }
            if (firstEligible >= 0) {
                commit(dealt.copy(phase = BjPhase.INSURANCE, activeSeat = firstEligible, activeHand = 0))
                return
            }
        }
        peek(dealt)
    }

    private fun doInsurance(take: Boolean): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.INSURANCE) return false
        val i = s.activeSeat
        val seat = s.seats.getOrNull(i) ?: return false
        val cost = seat.bet / 2
        if (take && (!seat.betPlaced || seat.stack < cost)) return false
        val updated = s.withSeat(i) {
            if (take) it.copy(stack = it.stack - cost, insurance = cost, insuranceDecided = true)
            else it.copy(insuranceDecided = true)
        }.copy(event = if (take) BjEvent.INSURANCE_TAKEN else BjEvent.INSURANCE_DECLINED, eventSeat = i)
        val next = (i + 1 until updated.seats.size).firstOrNull {
            val o = updated.seats[it]
            o.betPlaced && o.stack >= o.bet / 2
        }
        if (next != null) commit(updated.copy(activeSeat = next)) else peek(updated)
        return true
    }

    /** The dealer looks for a natural when showing an Ace or a ten-value card; insurance is settled here. */
    private fun peek(s: BjState) {
        val up = BjRules.cardValue(s.dealer[0].card)
        if (up < 10) { startPlaying(s); return }
        if (BjRules.isBlackjack(s.dealer.map { it.card })) {
            settle(s.copy(holeHidden = false), BjEvent.DEALER_BLACKJACK)
        } else {
            startPlaying(s.copy(event = BjEvent.PEEK_CLEAR))
        }
    }

    // --- Engine: the player's actions ------------------------------------------------------

    private fun startPlaying(s: BjState) {
        val p = s.copy(phase = BjPhase.PLAYING)
        for (si in p.seats.indices) for (hi in p.seats[si].hands.indices) {
            if (!p.seats[si].hands[hi].done) { commit(p.copy(activeSeat = si, activeHand = hi)); return }
        }
        commit(p.copy(phase = BjPhase.DEALER, activeSeat = -1, activeHand = 0))
    }

    private fun advance(s: BjState) {
        val si = s.activeSeat
        val cur = s.seats[si].hands[s.activeHand]
        if (!cur.done) { commit(s); return }
        for (seatIdx in si until s.seats.size) {
            val start = if (seatIdx == si) s.activeHand + 1 else 0
            for (hi in start until s.seats[seatIdx].hands.size) {
                if (!s.seats[seatIdx].hands[hi].done) { commit(s.copy(activeSeat = seatIdx, activeHand = hi)); return }
            }
        }
        commit(s.copy(phase = BjPhase.DEALER, activeSeat = -1, activeHand = 0))
    }

    private fun doAct(action: BjAction): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.PLAYING || action !in legalActions(s)) return false
        val si = s.activeSeat
        val hi = s.activeHand
        val hand = s.seats[si].hands[hi]
        when (action) {
            BjAction.HIT -> {
                val h = hand.copy(cards = hand.cards + draw())
                advance(
                    s.withHand(si, hi) { h }
                        .copy(event = if (h.total.bust) BjEvent.BUST else BjEvent.HIT, eventSeat = si, eventHand = hi)
                )
            }
            BjAction.STAND ->
                advance(s.withHand(si, hi) { it.copy(stood = true) }.copy(event = BjEvent.STAND, eventSeat = si, eventHand = hi))
            BjAction.DOUBLE -> {
                val h = hand.copy(cards = hand.cards + draw(), bet = hand.bet * 2, doubled = true, stood = true)
                val ns = s.withSeat(si) { seat ->
                    seat.copy(stack = seat.stack - hand.bet, hands = seat.hands.mapIndexed { k, x -> if (k == hi) h else x })
                }.copy(event = if (h.total.bust) BjEvent.BUST else BjEvent.DOUBLE, eventSeat = si, eventHand = hi)
                advance(ns)
            }
            BjAction.SPLIT -> {
                val aces = hand.cards[0].card.rank == Rank.ACE
                val a = BjHand(listOf(hand.cards[0], draw()), hand.bet, fromSplit = true, splitAces = aces)
                val b = BjHand(listOf(hand.cards[1], draw()), hand.bet, fromSplit = true, splitAces = aces)
                val a2 = if (aces) a.copy(stood = true) else a
                val b2 = if (aces) b.copy(stood = true) else b
                val ns = s.withSeat(si) { seat ->
                    val list = seat.hands.toMutableList()
                    list[hi] = a2
                    list.add(hi + 1, b2)
                    seat.copy(stack = seat.stack - hand.bet, hands = list)
                }.copy(event = BjEvent.SPLIT, eventSeat = si, eventHand = hi)
                advance(ns)
            }
            BjAction.SURRENDER ->
                advance(
                    s.withHand(si, hi) { it.copy(surrendered = true, stood = true) }
                        .copy(event = BjEvent.SURRENDER, eventSeat = si, eventHand = hi)
                )
            else -> return false
        }
        return true
    }

    // --- Engine: the dealer and the settlement ---------------------------------------------

    fun dealerToMove(s: BjState): Boolean =
        !matchOver.value && s.phase == BjPhase.DEALER && (!isNetworked || isHost)

    private fun anyLiveHand(s: BjState) = s.seats.any { seat ->
        seat.hands.any { !it.total.bust && !it.surrendered && !it.natural }
    }

    /** One dealer action: reveal the hole card, then draw or finish. Returns false when it is not the dealer's turn. */
    fun dealerStep(): Boolean {
        val s = state.value ?: return false
        if (!dealerToMove(s)) return false
        if (s.holeHidden) {
            commit(s.copy(holeHidden = false, event = BjEvent.REVEAL, eventSeat = -1))
            return true
        }
        val dealerCards = s.dealer.map { it.card }
        if (anyLiveHand(s) && BjRules.dealerMustHit(dealerCards, s.config.hitSoft17)) {
            commit(s.copy(dealer = s.dealer + draw(), event = BjEvent.DEALER_HIT, eventSeat = -1))
        } else {
            settle(s, BjEvent.ROUND_OVER)
        }
        return true
    }

    private fun settle(s: BjState, event: String) {
        val dealerCards = s.dealer.map { it.card }
        val dealerNatural = BjRules.isBlackjack(dealerCards)
        val payout = s.config.payoutRule
        val seats = s.seats.map { seat ->
            if (seat.hands.isEmpty()) return@map seat.copy(roundNet = 0)
            var credit = 0
            var wagered = 0
            var won = 0
            var lost = 0
            var pushed = 0
            var bjs = 0
            val hands = seat.hands.map { h ->
                val out = BjRules.outcome(h.plain, h.natural, h.surrendered, dealerCards)
                val c = BjRules.credit(out, h.bet, payout)
                credit += c
                wagered += h.bet
                when (out) {
                    BjOutcome.BLACKJACK -> { won++; bjs++ }
                    BjOutcome.WIN -> won++
                    BjOutcome.PUSH -> pushed++
                    else -> lost++
                }
                h.copy(outcome = out, credit = c)
            }
            val insCredit = if (seat.insurance > 0 && dealerNatural) seat.insurance * 3 else 0
            wagered += seat.insurance
            credit += insCredit
            seat.copy(
                hands = hands, stack = seat.stack + credit, insuranceCredit = insCredit, roundNet = credit - wagered,
                roundsPlayed = seat.roundsPlayed + 1, won = seat.won + won, lost = seat.lost + lost,
                pushed = seat.pushed + pushed, blackjacks = seat.blackjacks + bjs
            )
        }
        commit(
            s.copy(
                seats = seats, phase = BjPhase.ROUND_OVER, activeSeat = -1, activeHand = 0, event = event,
                eventSeat = -1, roundsDone = s.roundsDone + 1
            )
        )
    }

    private fun doNextRound(): Boolean {
        val s = state.value ?: return false
        if (s.phase != BjPhase.ROUND_OVER) return false
        enterBetting(s, s.round + 1, BjEvent.START)
        return true
    }

    // --- Hint ------------------------------------------------------------------------------

    /** Basic strategy for the hand on turn (uses only the dealer's upcard, so it never shows hidden information). */
    fun hint(): BjAction? {
        val s = state.value ?: return null
        if (matchOver.value || !isMyTurn(s)) return null
        val best = when (s.phase) {
            BjPhase.INSURANCE -> BjAction.DECLINE_INSURANCE
            BjPhase.PLAYING -> {
                val legal = legalActions(s)
                val hand = s.seats[s.activeSeat].hands[s.activeHand]
                val up = s.upcard ?: return null
                BjStrategy.best(
                    hand.plain, up, BjAction.DOUBLE in legal, BjAction.SPLIT in legal, BjAction.SURRENDER in legal,
                    s.config.hitSoft17
                )
            }
            else -> return null
        }
        hintAction.value = best
        return best
    }

    // --- Computer seats --------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: BjState): Boolean {
        if (matchOver.value) return false
        if (isNetworked && !isHost) return false
        if (s.phase != BjPhase.BETTING && s.phase != BjPhase.INSURANCE && s.phase != BjPhase.PLAYING) return false
        return s.seats.getOrNull(s.activeSeat)?.isBot == true
    }

    private sealed interface BotPlan {
        data object Rebuy : BotPlan
        data class Bet(val amount: Int) : BotPlan
        data class Insure(val take: Boolean) : BotPlan
        data class Act(val action: BjAction) : BotPlan
    }

    private fun planBot(s: BjState): BotPlan? {
        val seat = s.seats.getOrNull(s.activeSeat) ?: return null
        return when (s.phase) {
            BjPhase.BETTING ->
                if (seat.stack < BjRules.MIN_BET) BotPlan.Rebuy else BotPlan.Bet(BjBot.chooseBet(difficulty, seat.stack, random))
            BjPhase.INSURANCE -> BotPlan.Insure(canInsure(s) && BjBot.takeInsurance(difficulty, random))
            BjPhase.PLAYING -> {
                val hand = seat.hands[s.activeHand]
                BotPlan.Act(
                    BjBot.chooseAction(difficulty, hand.plain, s.upcard ?: return null, legalActions(s), s.config.hitSoft17, random)
                )
            }
            else -> null
        }
    }

    private fun applyPlan(plan: BotPlan) {
        when (plan) {
            BotPlan.Rebuy -> doRebuy()
            is BotPlan.Bet -> doBet(plan.amount)
            is BotPlan.Insure -> doInsurance(plan.take)
            is BotPlan.Act -> doAct(plan.action)
        }
    }

    /** One computer action, with the choice made off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val plan = withContext(Dispatchers.Default) { planBot(s) } ?: return
        if (state.value !== s || matchOver.value) return
        applyPlan(plan)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        planBot(s)?.let { applyPlan(it) }
    }

    // --- Resume ----------------------------------------------------------------------------

    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            fun sc(c: BjCard) = SnapCard(c.card.id, c.uid)
            snapJson.encodeToString(
                Snap.serializer(),
                Snap(
                    seats = s.seats.map { seat ->
                        SnapSeat(
                            seat.playerId, seat.displayName, seat.isBot, seat.stack, seat.rebuys, seat.bet, seat.betPlaced,
                            seat.sittingOut,
                            seat.hands.map { h ->
                                SnapHand(
                                    h.cards.map(::sc), h.bet, h.doubled, h.fromSplit, h.splitAces, h.surrendered,
                                    h.stood, h.outcome, h.credit
                                )
                            },
                            seat.insurance, seat.insuranceDecided, seat.insuranceCredit, seat.roundNet, seat.roundsPlayed,
                            seat.won, seat.lost, seat.pushed, seat.blackjacks
                        )
                    },
                    dealer = s.dealer.map(::sc), holeHidden = s.holeHidden, phase = s.phase, activeSeat = s.activeSeat,
                    activeHand = s.activeHand, round = s.round, event = s.event, eventSeat = s.eventSeat,
                    eventHand = s.eventHand, serial = s.serial, nextUid = uid, reshuffled = s.reshuffled,
                    roundsDone = s.roundsDone, config = s.config, shoe = shoe.map { it.id },
                    discard = discard.map { it.id }, difficulty = difficulty.name
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = snapJson.decodeFromString(Snap.serializer(), snapshot)
            val built = buildFromSnapshot(snap) ?: return false
            matchOver.value = false
            config = snap.config
            shoe.clear(); shoe.addAll(snap.shoe.map { cardById(it) })
            discard.clear(); discard.addAll(snap.discard.map { cardById(it) })
            uid = snap.nextUid
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
            state.value = built
            hintAction.value = null
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildFromSnapshot(snap: Snap): BjState? {
        val roster = context.players
        val n = roster.size
        if (snap.seats.size != n || !snap.config.isValid) return null
        if (!snap.seats.indices.all { snap.seats[it].playerId == roster[it].playerId && snap.seats[it].isBot == roster[it].isBot }) return null
        if (snap.phase !in BjPhase.ALL || snap.event !in BjEvent.ALL) return null
        if (snap.activeSeat !in -1 until n || snap.activeHand < 0 || snap.round < 1 || snap.roundsDone < 0) return null
        val validCard = { c: SnapCard -> c.id in 0..51 && c.uid >= 0 }
        val every = snap.dealer + snap.seats.flatMap { it.hands.flatMap { h -> h.cards } }
        if (!every.all(validCard) || !snap.shoe.all { it in 0..51 } || !snap.discard.all { it in 0..51 }) return null
        if (every.map { it.uid }.toSet().size != every.size) return null
        // Every physical card exists exactly once per deck, wherever it is.
        val counts = IntArray(52)
        every.forEach { counts[it.id]++ }
        snap.shoe.forEach { counts[it]++ }
        snap.discard.forEach { counts[it]++ }
        if (counts.any { it != snap.config.decks }) return null
        for (seat in snap.seats) {
            if (seat.stack < 0 || seat.rebuys < 0 || seat.bet < 0 || seat.insurance < 0 || seat.hands.size > BjRules.MAX_HANDS) return null
            if (seat.hands.any { it.cards.isEmpty() || it.bet <= 0 || (it.outcome != null && it.outcome !in BjOutcome.ALL) }) return null
        }
        when (snap.phase) {
            BjPhase.BETTING -> if (snap.dealer.isNotEmpty() || snap.activeSeat !in 0 until n) return null
            BjPhase.INSURANCE, BjPhase.PLAYING -> {
                if (snap.dealer.size != 2 || !snap.holeHidden || snap.activeSeat !in 0 until n) return null
                if (snap.phase == BjPhase.PLAYING && snap.activeHand !in snap.seats[snap.activeSeat].hands.indices) return null
            }
            BjPhase.DEALER -> if (snap.dealer.size < 2 || snap.activeSeat != -1) return null
            BjPhase.ROUND_OVER ->
                if (snap.dealer.size < 2 || snap.holeHidden || snap.seats.any { s -> s.hands.any { it.outcome == null } }) return null
        }
        if (snap.phase != BjPhase.BETTING && snap.phase != BjPhase.ROUND_OVER && snap.seats.all { it.hands.isEmpty() }) return null
        fun bc(c: SnapCard) = BjCard(cardById(c.id), c.uid)
        return BjState(
            seats = snap.seats.map { seat ->
                BjSeat(
                    seat.playerId, seat.displayName, seat.isBot, seat.stack, seat.rebuys, seat.bet, seat.betPlaced,
                    seat.sittingOut,
                    seat.hands.map { h ->
                        BjHand(h.cards.map(::bc), h.bet, h.doubled, h.fromSplit, h.splitAces, h.surrendered, h.stood, h.outcome, h.credit)
                    },
                    seat.insurance, seat.insuranceDecided, seat.insuranceCredit, seat.roundNet, seat.roundsPlayed,
                    seat.won, seat.lost, seat.pushed, seat.blackjacks
                )
            },
            dealer = snap.dealer.map(::bc), holeHidden = snap.holeHidden, phase = snap.phase, activeSeat = snap.activeSeat,
            activeHand = snap.activeHand, round = snap.round, event = snap.event, eventSeat = snap.eventSeat,
            eventHand = snap.eventHand, serial = snap.serial, nextUid = snap.nextUid, shoeLeft = snap.shoe.size,
            reshuffled = snap.reshuffled, roundsDone = snap.roundsDone, config = snap.config
        )
    }

    // --- Networking ------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(BjNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(BjNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) { if (seat == 0) hostLeft.value = true; return }
        val s = state.value ?: return
        if (seat == 0 || s.seats[seat].isBot) return
        commit(s.withSeat(seat) { it.copy(isBot = true) })
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(BjNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(s.withSeat(seat) { it.copy(isBot = false) })
    }

    /** The wire form of [s]: the hole card is -1 while it is face down, and nothing about the shoe is included. */
    internal fun toNet(s: BjState): BjNetState {
        fun nc(c: BjCard, hide: Boolean = false) = BjNetCard(if (hide) -1 else c.card.id, c.uid)
        return BjNetState(
            seats = s.seats.map { seat ->
                BjNetSeat(
                    seat.playerId, seat.displayName, seat.isBot, seat.stack, seat.rebuys, seat.bet, seat.betPlaced,
                    seat.sittingOut,
                    seat.hands.map { h ->
                        BjNetHand(h.cards.map { nc(it) }, h.bet, h.doubled, h.fromSplit, h.splitAces, h.surrendered, h.stood, h.outcome, h.credit)
                    },
                    seat.insurance, seat.insuranceDecided, seat.insuranceCredit, seat.roundNet, seat.roundsPlayed,
                    seat.won, seat.lost, seat.pushed, seat.blackjacks
                )
            },
            dealer = s.dealer.mapIndexed { i, c -> nc(c, hide = s.holeHidden && i == 1) },
            holeHidden = s.holeHidden, phase = s.phase, activeSeat = s.activeSeat, activeHand = s.activeHand,
            round = s.round, event = s.event, eventSeat = s.eventSeat, eventHand = s.eventHand, serial = s.serial,
            nextUid = s.nextUid, shoeLeft = s.shoeLeft, reshuffled = s.reshuffled, roundsDone = s.roundsDone,
            config = s.config
        )
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(BjNetMessage.serializer(), BjNetMessage.StateSync(stateVersion, toNet(s)))
            .toByteArray(Charsets.UTF_8)
    }

    private fun broadcastState() {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.players.drop(1).filter { !it.isBot }.forEach {
            context.transport.send(fromPlayerId = hostId, toPlayerId = it.playerId, payload = payload)
        }
    }

    private fun sendStateTo(playerId: String) {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(fromPlayerId = hostId, toPlayerId = playerId, payload = payload)
    }

    private fun sendToHost(message: BjNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(BjNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(BjNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        // A request is honoured only from the seat whose turn it is, as established by the transport.
        val onTurn = isHost && s != null && senderSeat > 0 && s.activeSeat == senderSeat &&
            !s.seats[senderSeat].isBot && !matchOver.value
        when (message) {
            is BjNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is BjNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is BjNetMessage.BetRequest -> if (onTurn) doBet(message.amount)
            is BjNetMessage.RebuyRequest -> if (onTurn) doRebuy()
            is BjNetMessage.SitOutRequest -> if (onTurn) doSitOut()
            is BjNetMessage.InsuranceRequest -> if (onTurn) doInsurance(message.take)
            is BjNetMessage.ActionRequest -> if (onTurn) {
                val a = BjAction.entries.firstOrNull { it.name == message.action }
                if (a != null) doAct(a)
            }
            is BjNetMessage.NextRoundRequest ->
                if (isHost && senderSeat > 0 && s?.phase == BjPhase.ROUND_OVER && !matchOver.value) doNextRound()
        }
    }

    private fun applyRemoteState(version: Int, net: BjNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size
        if (net.seats.size != n || !net.config.isValid) return
        if (!net.seats.indices.all { net.seats[it].playerId == roster[it].playerId }) return
        if (net.phase !in BjPhase.ALL || net.event !in BjEvent.ALL) return
        if (net.activeSeat !in -1 until n || net.activeHand < 0 || net.round < 1) return
        val handCards = net.seats.flatMap { it.hands.flatMap { h -> h.cards } }
        if (!handCards.all { it.id in 0..51 }) return
        if (net.dealer.withIndex().any { (i, c) -> c.id !in 0..51 && !(c.id == -1 && i == 1 && net.holeHidden) }) return
        if (net.holeHidden && net.dealer.size > 2) return
        for (seat in net.seats) {
            if (seat.stack < 0 || seat.bet < 0 || seat.insurance < 0 || seat.hands.size > BjRules.MAX_HANDS) return
            if (seat.hands.any { it.outcome != null && it.outcome !in BjOutcome.ALL }) return
        }
        lastAppliedRemoteVersion = version
        fun bc(c: BjNetCard) = BjCard(if (c.id < 0) BJ_HIDDEN_CARD else cardById(c.id), c.uid)
        state.value = BjState(
            seats = net.seats.map { seat ->
                BjSeat(
                    seat.playerId, seat.displayName, seat.isBot, seat.stack, seat.rebuys, seat.bet, seat.betPlaced,
                    seat.sittingOut,
                    seat.hands.map { h ->
                        BjHand(h.cards.map(::bc), h.bet, h.doubled, h.fromSplit, h.splitAces, h.surrendered, h.stood, h.outcome, h.credit)
                    },
                    seat.insurance, seat.insuranceDecided, seat.insuranceCredit, seat.roundNet, seat.roundsPlayed,
                    seat.won, seat.lost, seat.pushed, seat.blackjacks
                )
            },
            dealer = net.dealer.map(::bc), holeHidden = net.holeHidden, phase = net.phase, activeSeat = net.activeSeat,
            activeHand = net.activeHand, round = net.round, event = net.event, eventSeat = net.eventSeat,
            eventHand = net.eventHand, serial = net.serial, nextUid = net.nextUid, shoeLeft = net.shoeLeft,
            reshuffled = net.reshuffled, roundsDone = net.roundsDone, config = net.config
        )
        config = net.config
        hintAction.value = null
    }
}
