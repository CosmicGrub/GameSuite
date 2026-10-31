package com.gamesuite.games.speed

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

enum class SpeedPhase { COUNTDOWN, PLAYING, OVER }

/** What the last change to the table was; the screen words, animates and announces it. */
object SpeedEvent {
    const val START = "start"
    const val PLAY = "play"
    const val FLIP = "flip"
    const val RESHUFFLE = "reshuffle"
    const val PASS = "pass"
    const val WIN = "win"
    const val DRAW = "draw"
}

/** Why a play or a flip did not happen. [NONE] means it did. */
enum class SpeedReject {
    NONE, NOT_PLAYING, NOT_STARTED, NOT_YOUR_TURN, NOT_YOUR_SEAT, NO_SUCH_CARD, ILLEGAL, LOST_RACE, CAN_STILL_PLAY
}

data class SpeedPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** A card to play and the centre pile (0 left, 1 right) to put it on. */
data class SpeedPlay(val cardId: Int, val pile: Int)

/** The most recent refusal of a local player's play, for the screen's "why" line. [serial] only grows. */
data class SpeedRejectInfo(val seat: Int, val reason: SpeedReject, val cardId: Int, val serial: Int)

object SpeedCards {
    /** The 52 cards by id; ids are stable (see [Deck.fresh52]). */
    val all: List<Card> = Deck.fresh52()

    /** Stands in for a card the receiver may not see (id -1, never a real card). */
    val HIDDEN: Card = Card(Rank.TWO, Suit.CLUBS, -1)

    fun byId(id: Int): Card? = all.getOrNull(id)
}

/**
 * What the table looks like to THIS device. [hands] holds [SpeedCards.HIDDEN] for any card this
 * device may not see (a networked guest never sees the host's hand); the screen decides whether to
 * show the other seat's hand face down (vs the computer, online) or face up (pass and play).
 * [pileTops] / [pileSizes] describe the two centre piles; [drawCounts] and [reserveCounts] are the
 * face-down piles. [playSerial] / [flipSerial] / [dealSerial] only ever grow and key the animations.
 */
data class SpeedState(
    val players: List<SpeedPlayerState>,
    val hands: List<List<Card?>>,
    val drawCounts: List<Int>,
    val reserveCounts: List<Int>,
    val pileTops: List<Card?>,
    val pileSizes: List<Int>,
    val phase: SpeedPhase,
    val assist: Boolean,
    val assistTurn: Int,
    val flipReady: List<Boolean>,
    val event: String = SpeedEvent.START,
    val eventSeat: Int? = null,
    val eventPile: Int? = null,
    val eventCardId: Int? = null,
    val playSerial: Int = 0,
    val flipSerial: Int = 0,
    val dealSerial: Int = 0,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null
) {
    /** Cards seat [seat] still has to play: its hand plus its draw pile. */
    fun cardsLeft(seat: Int): Int = hands[seat].count { it != null } + drawCounts[seat]
}

@Serializable
private data class SpeedSnapshot(
    val players: List<SpeedSnapshotPlayer>,
    val hands: List<List<Int>>,
    val draws: List<List<Int>>,
    val reserves: List<List<Int>>,
    val piles: List<List<Int>>,
    val assist: Boolean,
    val assistTurn: Int,
    val deadlock: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextOpener: Int,
    val difficulty: String,
    val playSerial: Int = 0,
    val flipSerial: Int = 0,
    val dealSerial: Int = 0
)

@Serializable
private data class SpeedSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val speedJson = Json { ignoreUnknownKeys = true }

/**
 * Speed (Spit): two players, one deck, no turns. See [SpeedRules] for the rules.
 *
 * REAL TIME, ORDERED BY ARRIVAL. Every play is applied the moment it is processed, in the order it
 * arrived at the one engine that owns the table: the local engine offline, the host's engine in a
 * networked game (guests only send requests). A play that is no longer legal because the other seat
 * got there first is refused with [SpeedReject.LOST_RACE] when it arrives within
 * [SpeedRules.GRACE_MS] of that play and would have been legal on the pile as it was (a photo
 * finish: nobody is penalised and the card stays in hand), and with [SpeedReject.ILLEGAL] otherwise.
 *
 * TIME comes only from the injected [clock] (milliseconds) and DICE/SHUFFLES only from [random], so
 * a fake clock and a seeded Random replay a game exactly. Nothing runs by itself: the screen calls
 * [tick] about 25 times a second (it moves the countdown on, lets the computer act and flips the
 * centre cards when both seats have been stuck for a while).
 *
 * THE COMPUTER reacts like a person: it notices an opportunity, waits a reaction time that depends
 * on the level (Easy about 1.8 s, Medium 1 s, Hard 0.45 s, each randomly varied by 20 percent), then
 * re-checks that the play is still legal before making it. Easy sometimes overlooks a play for a
 * while longer and often picks a poor card; Hard always picks the card that leaves it the most
 * follow-up plays. The decision itself is a handful of comparisons, so it is made inline in [tick].
 *
 * TURN-BASED ASSIST ([assist]) is an accessibility option for players who cannot play at speed: no
 * countdown, no racing. The seats take turns, one card each; a seat with no play passes
 * ([requestFlip]). When both are stuck the centre cards flip as usual.
 *
 * HIDDEN INFORMATION: a networked guest receives only its own hand and pile COUNTS, never the
 * host's cards or the order of any pile.
 */
class SpeedGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "speed"
    override val displayName = "Speed"
    override val category = GameCategory.CARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<SpeedState?>(null)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)

    /** The play the last [hint] suggested; cleared by the next change to the table. */
    val hintPlay = mutableStateOf<SpeedPlay?>(null)

    /** The last refused play of a local seat (null before any). */
    val lastReject = mutableStateOf<SpeedRejectInfo?>(null)

    /** Pre-set by the screen from the Hub's level before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Turn-based assist for the NEXT board (the Hub's option); a restored save or a host sync carries its own. */
    var assist: Boolean = false

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock = Any()

    // ---- the table (host / offline only; a guest has only [state]) ----
    private class Table {
        val hands = List(2) { arrayOfNulls<Card>(SpeedRules.HAND_SIZE) }
        val draws = List(2) { ArrayList<Card>() }
        val reserves = List(2) { ArrayList<Card>() }
        val piles = List(2) { ArrayList<Card>() }
    }

    private var table = Table()
    private var phase = SpeedPhase.OVER
    private var assistTurn = 0
    private val flipReady = BooleanArray(2)
    private var event = SpeedEvent.START
    private var eventSeat: Int? = null
    private var eventPile: Int? = null
    private var eventCardId: Int? = null
    private var playSerial = 0
    private var flipSerial = 0
    private var dealSerial = 0
    private var boardOver = false
    private var winnerPlayerId: String? = null
    private var deadlock = 0
    private var goAt = 0L
    private var stuckSince: Long? = null
    private var nextOpener = 0
    private var rejectSerial = 0

    private class PlayRec(val seat: Int, val at: Long, val prevTop: Card?)

    private val lastPlay = arrayOfNulls<PlayRec>(2)

    private class BotPlan(val flip: Boolean, val readyAt: Long, val cardId: Int, val pile: Int)

    private val botPlans = arrayOfNulls<BotPlan>(2)
    private val botLastPlayAt = LongArray(2) { Long.MIN_VALUE / 2 }

    // ---- networking bookkeeping ----
    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = context.players.associate { it.playerId to 0 }
            sessionDraws.value = 0
            hintPlay.value = null
            lastReject.value = null
            state.value = null
            table = Table()
            phase = SpeedPhase.OVER
            boardOver = false
            winnerPlayerId = null
            nextOpener = 0
            stateVersion = 0
            lastAppliedRemoteVersion = 0
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return // the guest waits for the host's StateSync
        matchOver.value = false
        dealBoard()
        commit()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the whole session, reporting boards won (only a strict leader is the winner). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    /** A fresh board in the same session (after a finished one). */
    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(SpeedNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    private fun dealBoard() {
        val deals = SpeedRules.deal(random)
        val t = Table()
        for (seat in 0..1) {
            val d = deals[seat]
            d.hand.forEachIndexed { i, c -> t.hands[seat][i] = c }
            t.draws[seat].addAll(d.draw)
            t.reserves[seat].addAll(d.reserve)
        }
        table = t
        boardOver = false
        winnerPlayerId = null
        deadlock = 0
        stuckSince = null
        flipReady.fill(false)
        lastPlay.fill(null)
        botPlans.fill(null)
        hintPlay.value = null
        // The opening flip: each seat turns its top replacement card onto its own side's pile.
        for (seat in 0..1) t.piles[seat].add(t.reserves[seat].removeAt(t.reserves[seat].size - 1))
        dealSerial++
        event = SpeedEvent.START
        eventSeat = null; eventPile = null; eventCardId = null
        assistTurn = nextOpener % 2
        nextOpener = (nextOpener + 1) % 2
        if (assist) {
            phase = SpeedPhase.PLAYING
        } else {
            phase = SpeedPhase.COUNTDOWN
            goAt = clock() + SpeedRules.COUNTDOWN_MS
        }
    }

    // ---- queries -------------------------------------------------------------------------

    private fun legalPlaysOnTable(seat: Int): List<SpeedPlay> {
        val out = ArrayList<SpeedPlay>(4)
        for (card in table.hands[seat]) {
            if (card == null) continue
            for (pile in 0..1) if (SpeedRules.canPlay(card, table.piles[pile].lastOrNull())) out += SpeedPlay(card.id, pile)
        }
        return out
    }

    private fun cardsLeftOnTable(seat: Int): Int = table.hands[seat].count { it != null } + table.draws[seat].size

    /** The card's position in a hand, or -1. */
    private fun slotOf(seat: Int, cardId: Int): Int = table.hands[seat].indexOfFirst { it?.id == cardId }

    // ---- playing -------------------------------------------------------------------------

    /**
     * Seat [seat] puts card [cardId] on centre pile [pile]. Offline the seat is whichever side was
     * tapped; networked only this device's own seat may act (a guest sends a request and waits for
     * the host's state). Returns [SpeedReject.NONE] when the play happened (or, for a guest, was sent).
     */
    @Synchronized
    fun play(seat: Int, cardId: Int, pile: Int): SpeedReject {
        val s = state.value ?: return SpeedReject.NOT_PLAYING
        if (matchOver.value || s.boardOver) return SpeedReject.NOT_PLAYING
        if (isNetworked) {
            if (seat != context.localPlayerIndex || opponentLeft.value) return SpeedReject.NOT_YOUR_SEAT
            if (!isHost) {
                if (s.phase == SpeedPhase.COUNTDOWN) return noteReject(seat, SpeedReject.NOT_STARTED, cardId)
                if (s.hands[seat].none { it?.id == cardId } || pile !in 0..1) return noteReject(seat, SpeedReject.NO_SUCH_CARD, cardId)
                sendToHost(SpeedNetMessage.PlayRequest(cardId, pile))
                return SpeedReject.NONE
            }
        }
        val r = applyPlay(seat, cardId, pile)
        if (r == SpeedReject.NONE) commit() else noteReject(seat, r, cardId)
        return r
    }

    private fun noteReject(seat: Int, reason: SpeedReject, cardId: Int): SpeedReject {
        lastReject.value = SpeedRejectInfo(seat, reason, cardId, ++rejectSerial)
        return reason
    }

    /** The engine step: validates and applies one play. Never commits. */
    private fun applyPlay(seat: Int, cardId: Int, pile: Int): SpeedReject {
        if (phase == SpeedPhase.OVER) return SpeedReject.NOT_PLAYING
        if (phase == SpeedPhase.COUNTDOWN) return SpeedReject.NOT_STARTED
        if (seat !in 0..1) return SpeedReject.NOT_YOUR_SEAT
        if (pile !in 0..1) return SpeedReject.ILLEGAL
        if (assist && assistTurn != seat) return SpeedReject.NOT_YOUR_TURN
        val slot = slotOf(seat, cardId)
        if (slot < 0) return SpeedReject.NO_SUCH_CARD
        val card = table.hands[seat][slot]!!
        val top = table.piles[pile].lastOrNull()
        val now = clock()
        if (!SpeedRules.canPlay(card, top)) {
            val rec = lastPlay[pile]
            if (rec != null && rec.seat != seat && now - rec.at <= SpeedRules.GRACE_MS && SpeedRules.canPlay(card, rec.prevTop)) {
                return SpeedReject.LOST_RACE
            }
            return SpeedReject.ILLEGAL
        }
        table.piles[pile].add(card)
        table.hands[seat][slot] = if (table.draws[seat].isEmpty()) null else table.draws[seat].removeAt(table.draws[seat].size - 1)
        lastPlay[pile] = PlayRec(seat, now, top)
        playSerial++
        event = SpeedEvent.PLAY
        eventSeat = seat; eventPile = pile; eventCardId = card.id
        deadlock = 0
        stuckSince = null
        flipReady.fill(false)
        hintPlay.value = null
        if (assist) assistTurn = 1 - seat
        if (table.hands[seat].all { it == null }) finish(seat)
        return SpeedReject.NONE
    }

    /**
     * Seat [seat] says it is stuck. In real time that flags the seat; when both seats are flagged
     * (or both have been stuck long enough, see [tick]) the centre cards flip. In turn-based assist
     * it is a pass. Refused while the seat still has a play.
     */
    @Synchronized
    fun requestFlip(seat: Int): SpeedReject {
        val s = state.value ?: return SpeedReject.NOT_PLAYING
        if (matchOver.value || s.boardOver) return SpeedReject.NOT_PLAYING
        if (isNetworked) {
            if (seat != context.localPlayerIndex || opponentLeft.value) return SpeedReject.NOT_YOUR_SEAT
            if (!isHost) {
                if (s.phase == SpeedPhase.COUNTDOWN) return SpeedReject.NOT_STARTED
                sendToHost(SpeedNetMessage.FlipRequest)
                return SpeedReject.NONE
            }
        }
        val r = applyFlipRequest(seat)
        if (r == SpeedReject.NONE) commit()
        return r
    }

    private fun applyFlipRequest(seat: Int): SpeedReject {
        if (phase == SpeedPhase.OVER) return SpeedReject.NOT_PLAYING
        if (phase == SpeedPhase.COUNTDOWN) return SpeedReject.NOT_STARTED
        if (seat !in 0..1) return SpeedReject.NOT_YOUR_SEAT
        if (assist && assistTurn != seat) return SpeedReject.NOT_YOUR_TURN
        if (legalPlaysOnTable(seat).isNotEmpty()) return SpeedReject.CAN_STILL_PLAY
        hintPlay.value = null
        if (assist) {
            event = SpeedEvent.PASS
            eventSeat = seat; eventPile = null; eventCardId = null
            if (legalPlaysOnTable(1 - seat).isEmpty()) doFlipRound() else assistTurn = 1 - seat
            return SpeedReject.NONE
        }
        flipReady[seat] = true
        if (flipReady[0] && flipReady[1]) doFlipRound() else {
            event = SpeedEvent.PASS
            eventSeat = seat; eventPile = null; eventCardId = null
        }
        return SpeedReject.NONE
    }

    /** Both seats are stuck: each turns its top replacement card onto its own side's pile. */
    private fun doFlipRound() {
        deadlock++
        if (deadlock > SpeedRules.MAX_DEADLOCK_FLIPS) {
            val a = cardsLeftOnTable(0)
            val b = cardsLeftOnTable(1)
            finish(if (a < b) 0 else if (b < a) 1 else null)
            return
        }
        var reshuffled = false
        if (table.reserves.any { it.isEmpty() }) {
            val all = ArrayList<Card>()
            for (seat in 0..1) { all.addAll(table.piles[seat]); table.piles[seat].clear(); all.addAll(table.reserves[seat]); table.reserves[seat].clear() }
            all.shuffle(random)
            val half = (all.size + 1) / 2
            table.reserves[0].addAll(all.subList(0, half))
            table.reserves[1].addAll(all.subList(half, all.size))
            reshuffled = true
        }
        for (seat in 0..1) table.piles[seat].add(table.reserves[seat].removeAt(table.reserves[seat].size - 1))
        flipSerial++
        event = if (reshuffled) SpeedEvent.RESHUFFLE else SpeedEvent.FLIP
        eventSeat = null; eventPile = null; eventCardId = null
        flipReady.fill(false)
        lastPlay.fill(null)
        stuckSince = null
        botPlans.fill(null)
        hintPlay.value = null
    }

    private fun finish(winnerSeat: Int?) {
        phase = SpeedPhase.OVER
        boardOver = true
        winnerPlayerId = winnerSeat?.let { context.players[it].playerId }
        event = if (winnerSeat != null) SpeedEvent.WIN else SpeedEvent.DRAW
        eventSeat = winnerSeat
        eventPile = null
        eventCardId = null
        botPlans.fill(null)
        if (winnerSeat != null) {
            val id = context.players[winnerSeat].playerId
            sessionWins.value = sessionWins.value + (id to (sessionWins.value[id] ?: 0) + 1)
        } else {
            sessionDraws.value += 1
        }
    }

    // ---- the clock -----------------------------------------------------------------------

    /**
     * Moves time on: ends the countdown, lets the computer act, and flips the centre cards when
     * both seats have been stuck for [SpeedRules.AUTO_FLIP_MS]. Call it often (about every 40 ms).
     * A networked guest does nothing here (the host runs the clock). Returns true if the table changed.
     */
    @Synchronized
    fun tick(): Boolean {
        if (isNetworked && !isHost) return false
        if (state.value == null || matchOver.value) return false
        val now = clock()
        var changed = false
        if (phase == SpeedPhase.COUNTDOWN && now >= goAt) {
            phase = SpeedPhase.PLAYING
            stuckSince = null
            changed = true
        }
        if (phase == SpeedPhase.PLAYING) {
            if (legalPlaysOnTable(0).isEmpty() && legalPlaysOnTable(1).isEmpty()) {
                val since = stuckSince
                if (since == null) stuckSince = now
                else if (now - since >= SpeedRules.AUTO_FLIP_MS) {
                    doFlipRound()
                    changed = true
                }
            } else {
                stuckSince = null
            }
        }
        if (phase == SpeedPhase.PLAYING) {
            for (seat in 0..1) {
                if (phase != SpeedPhase.PLAYING) break
                if (context.players[seat].isBot && botTick(seat, now)) changed = true
            }
        }
        if (changed) commit()
        return changed
    }

    /** The wait before a computer seat acts on something it just noticed. */
    private fun reactionDelay(seat: Int, now: Long): Long {
        val p = SpeedRules.profile(difficulty)
        if (assist) {
            val think = (p.reactionMs / 2).coerceAtLeast(500L)
            return (think * (SpeedRules.JITTER_LOW + random.nextDouble() * (SpeedRules.JITTER_HIGH - SpeedRules.JITTER_LOW))).toLong()
        }
        var base = p.reactionMs * (SpeedRules.JITTER_LOW + random.nextDouble() * (SpeedRules.JITTER_HIGH - SpeedRules.JITTER_LOW))
        if (now - botLastPlayAt[seat] <= SpeedRules.CHAIN_WINDOW_MS) base *= SpeedRules.CHAIN_FACTOR
        if (p.missChance > 0.0 && random.nextDouble() < p.missChance) base += p.reactionMs * 0.6
        return base.toLong()
    }

    /** Score of playing [play] for [seat]: how many of its other hand cards could then follow it. */
    private fun followUps(seat: Int, play: SpeedPlay): Int {
        val card = SpeedCards.all[play.cardId]
        return table.hands[seat].count { it != null && it.id != card.id && SpeedRules.adjacent(it.rank, card.rank) }
    }

    private fun pickPlay(seat: Int, plays: List<SpeedPlay>): SpeedPlay {
        val p = SpeedRules.profile(difficulty)
        if (random.nextDouble() >= p.smartness) return plays[random.nextInt(plays.size)]
        return plays.shuffled(random).maxByOrNull { followUps(seat, it) }!!
    }

    /** One look at the table for a computer seat. Returns true if it played or flagged a flip. */
    private fun botTick(seat: Int, now: Long): Boolean {
        if (assist && assistTurn != seat) {
            botPlans[seat] = null
            return false
        }
        val plays = legalPlaysOnTable(seat)
        val plan = botPlans[seat]
        if (plays.isEmpty()) {
            if (plan == null || !plan.flip) {
                botPlans[seat] = BotPlan(true, now + reactionDelay(seat, now), -1, -1)
                return false
            }
            if (now < plan.readyAt) return false
            botPlans[seat] = null
            return applyFlipRequest(seat) == SpeedReject.NONE
        }
        if (plan == null || plan.flip) {
            val pick = pickPlay(seat, plays)
            botPlans[seat] = BotPlan(false, now + reactionDelay(seat, now), pick.cardId, pick.pile)
            return false
        }
        if (now < plan.readyAt) return false
        botPlans[seat] = null
        // Re-check: the other seat may have changed the pile while the computer was reacting.
        if (plays.none { it.cardId == plan.cardId && it.pile == plan.pile }) return false
        val r = applyPlay(seat, plan.cardId, plan.pile)
        if (r == SpeedReject.NONE) botLastPlayAt[seat] = now
        return r == SpeedReject.NONE
    }

    // ---- hint ----------------------------------------------------------------------------

    /**
     * Suggests a play for [seat] (default: this device's seat): the card that leaves the most
     * follow-up plays. Highlights it ([hintPlay]); null when there is nothing to play (then Flip is
     * the move) or a hint makes no sense (networked, not live).
     */
    @Synchronized
    fun hint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceIn(0, 1) else 0): SpeedPlay? {
        if (isNetworked) return null
        val s = state.value ?: return null
        if (matchOver.value || s.boardOver || phase != SpeedPhase.PLAYING) return null
        if (assist && assistTurn != seat) return null
        val best = legalPlaysOnTable(seat).shuffled(random).maxByOrNull { followUps(seat, it) }
        hintPlay.value = best
        return best
    }

    // ---- state building ------------------------------------------------------------------

    private fun commit() {
        state.value = buildState()
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
        if (boardOver && phase == SpeedPhase.OVER) hintPlay.value = null
    }

    private fun buildState(): SpeedState = SpeedState(
        players = context.players.map { SpeedPlayerState(it.playerId, it.displayName, it.isBot) },
        hands = List(2) { seat -> table.hands[seat].toList() },
        drawCounts = List(2) { table.draws[it].size },
        reserveCounts = List(2) { table.reserves[it].size },
        pileTops = List(2) { table.piles[it].lastOrNull() },
        pileSizes = List(2) { table.piles[it].size },
        phase = phase,
        assist = assist,
        assistTurn = assistTurn,
        flipReady = flipReady.toList(),
        event = event, eventSeat = eventSeat, eventPile = eventPile, eventCardId = eventCardId,
        playSerial = playSerial, flipSerial = flipSerial, dealSerial = dealSerial,
        boardOver = boardOver, winnerPlayerId = winnerPlayerId
    )

    /** Every card on the table in a stable order; for conservation checks in tests. */
    internal fun allCardIds(): List<Int> = synchronized(lock) {
        val out = ArrayList<Int>()
        for (seat in 0..1) {
            table.hands[seat].forEach { if (it != null) out += it.id }
            table.draws[seat].forEach { out += it.id }
            table.reserves[seat].forEach { out += it.id }
            table.piles[seat].forEach { out += it.id }
        }
        out.sorted()
    }

    // ---- resume --------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) { captureSnapshot() }

    private fun captureSnapshot(): String? {
        return try {
            if (matchOver.value || state.value == null || boardOver) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            speedJson.encodeToString(
                SpeedSnapshot.serializer(),
                SpeedSnapshot(
                    players = context.players.map { SpeedSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                    hands = table.hands.map { h -> h.map { it?.id ?: -1 } },
                    draws = table.draws.map { d -> d.map { it.id } },
                    reserves = table.reserves.map { d -> d.map { it.id } },
                    piles = table.piles.map { d -> d.map { it.id } },
                    assist = assist, assistTurn = assistTurn, deadlock = deadlock,
                    boardOver = boardOver, winnerPlayerId = winnerPlayerId,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value,
                    nextOpener = nextOpener, difficulty = difficulty.name,
                    playSerial = playSerial, flipSerial = flipSerial, dealSerial = dealSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): SpeedSnapshot? {
        return try {
            val snap = speedJson.decodeFromString(SpeedSnapshot.serializer(), text)
            val roster = context.players
            val everyCard = (snap.hands + snap.draws + snap.reserves + snap.piles).flatten().filter { it != -1 }
            val valid = snap.players.size == 2 && roster.size == 2 &&
                snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot } &&
                snap.hands.size == 2 && snap.draws.size == 2 && snap.reserves.size == 2 && snap.piles.size == 2 &&
                snap.hands.all { it.size == SpeedRules.HAND_SIZE } &&
                everyCard.size == SpeedRules.DECK_SIZE && everyCard.toSet().size == SpeedRules.DECK_SIZE &&
                everyCard.all { it in 0 until SpeedRules.DECK_SIZE } &&
                snap.piles.all { it.isNotEmpty() } &&
                (0..1).all { seat -> snap.hands[seat].none { it == -1 } || snap.draws[seat].isEmpty() } &&
                snap.draws.all { it.size <= SpeedRules.DRAW_SIZE } &&
                snap.assistTurn in 0..1 && snap.deadlock in 0..SpeedRules.MAX_DEADLOCK_FLIPS &&
                snap.nextOpener in 0..1 && snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                !snap.boardOver &&
                snap.hands.none { h -> h.all { it == -1 } } // a board with an empty hand is already won
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    /** Puts the table back as saved, into a fresh countdown (turn-based assist resumes straight away). */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        val t = Table()
        for (seat in 0..1) {
            snap.hands[seat].forEachIndexed { i, id -> t.hands[seat][i] = if (id == -1) null else SpeedCards.all[id] }
            snap.draws[seat].forEach { t.draws[seat].add(SpeedCards.all[it]) }
            snap.reserves[seat].forEach { t.reserves[seat].add(SpeedCards.all[it]) }
            snap.piles[seat].forEach { t.piles[seat].add(SpeedCards.all[it]) }
        }
        table = t
        matchOver.value = false
        assist = snap.assist
        assistTurn = snap.assistTurn
        deadlock = snap.deadlock
        boardOver = false
        winnerPlayerId = null
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextOpener = snap.nextOpener
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
        playSerial = snap.playSerial; flipSerial = snap.flipSerial; dealSerial = snap.dealSerial + 1
        flipReady.fill(false)
        lastPlay.fill(null)
        botPlans.fill(null)
        stuckSince = null
        hintPlay.value = null
        event = SpeedEvent.START
        eventSeat = null; eventPile = null; eventCardId = null
        if (snap.assist) {
            phase = SpeedPhase.PLAYING
        } else {
            phase = SpeedPhase.COUNTDOWN
            goAt = clock() + SpeedRules.COUNTDOWN_MS
        }
        state.value = buildState()
        return true
    }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(SpeedNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) broadcastState() else sendToHost(SpeedNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
        botPlans.fill(null)
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        if (isHost && state.value != null) {
            // A seat that came back may have missed any number of changes; the host resends the table.
            stateVersion++
            broadcastState()
        }
    }

    /** The table as seat [forSeat] may see it: the other seat's hand cards are [SpeedNetState.HIDDEN]. */
    private fun toNetState(forSeat: Int): SpeedNetState {
        val s = state.value!!
        return SpeedNetState(
            players = s.players.map { SpeedNetPlayer(it.playerId, it.displayName) },
            hands = s.hands.mapIndexed { seat, slots ->
                slots.map { c -> if (c == null) SpeedNetState.EMPTY else if (seat == forSeat) c.id else SpeedNetState.HIDDEN }
            },
            drawCounts = s.drawCounts, reserveCounts = s.reserveCounts,
            pileTops = s.pileTops.map { it?.id ?: -1 }, pileSizes = s.pileSizes,
            phase = s.phase.name, assist = s.assist, assistTurn = s.assistTurn, flipReady = s.flipReady,
            event = s.event, eventSeat = s.eventSeat, eventPile = s.eventPile, eventCardId = s.eventCardId,
            playSerial = s.playSerial, flipSerial = s.flipSerial, dealSerial = s.dealSerial,
            boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId,
            sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
        )
    }

    private fun broadcastState() {
        if (state.value == null) return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = speedJson.encodeToString(SpeedNetMessage.serializer(), SpeedNetMessage.StateSync(stateVersion, toNetState(1)))
            .toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: SpeedNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = speedJson.encodeToString(SpeedNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun sendToGuest(message: SpeedNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = speedJson.encodeToString(SpeedNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            speedJson.decodeFromString(SpeedNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return // malformed or foreign payload: ignore rather than crash the match
        }
        synchronized(lock) {
            val hostId = context.players.getOrNull(0)?.playerId
            val guestId = context.players.getOrNull(1)?.playerId
            when (message) {
                // Only the host is authoritative: a StateSync from any other sender is a forgery.
                is SpeedNetMessage.StateSync ->
                    if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
                is SpeedNetMessage.PlayRejected ->
                    if (!isHost && fromPlayerId == hostId) {
                        val reason = SpeedReject.entries.firstOrNull { it.name == message.reason }
                        if (reason != null && reason != SpeedReject.NONE) noteReject(context.localPlayerIndex, reason, message.cardId)
                    }
                is SpeedNetMessage.RequestState ->
                    if (isHost && fromPlayerId == guestId) {
                        if (state.value != null) {
                            stateVersion++
                            broadcastState()
                        }
                    }
                is SpeedNetMessage.PlayRequest ->
                    if (isHost && fromPlayerId == guestId) hostHandlePlay(message.cardId, message.pile)
                is SpeedNetMessage.FlipRequest ->
                    if (isHost && fromPlayerId == guestId && state.value != null && !opponentLeft.value && !matchOver.value) {
                        if (applyFlipRequest(1) == SpeedReject.NONE) commit()
                    }
                is SpeedNetMessage.RequestPlayAgain ->
                    if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.boardOver == true && !matchOver.value) {
                        startMatch()
                    }
            }
        }
    }

    /** Host: a play request from the (already identity-checked) guest seat, ordered by arrival. */
    private fun hostHandlePlay(cardId: Int, pile: Int) {
        if (state.value == null || matchOver.value || opponentLeft.value) return
        val r = applyPlay(1, cardId, pile)
        if (r == SpeedReject.NONE) {
            commit()
        } else {
            sendToGuest(SpeedNetMessage.PlayRejected(r.name, cardId))
        }
    }

    /** Guest: adopts a strictly newer, well-formed host state. */
    private fun applyRemoteState(version: Int, net: SpeedNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val me = context.localPlayerIndex
        val phaseValue = SpeedPhase.entries.firstOrNull { it.name == net.phase }
        val valid = phaseValue != null && roster.size == 2 && me in 0..1 &&
            net.players.size == 2 && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.hands.size == 2 && net.hands.all { it.size == SpeedRules.HAND_SIZE } &&
            net.hands.all { h -> h.all { it == SpeedNetState.EMPTY || it == SpeedNetState.HIDDEN || it in 0 until SpeedRules.DECK_SIZE } } &&
            net.hands[me].none { it == SpeedNetState.HIDDEN } &&
            net.hands[1 - me].none { it >= 0 } &&
            net.drawCounts.size == 2 && net.reserveCounts.size == 2 && net.pileSizes.size == 2 && net.pileTops.size == 2 &&
            (net.drawCounts + net.reserveCounts + net.pileSizes).all { it in 0..SpeedRules.DECK_SIZE } &&
            net.pileTops.all { it == -1 || it in 0 until SpeedRules.DECK_SIZE } &&
            net.assistTurn in 0..1 && net.flipReady.size == 2 &&
            (net.winnerPlayerId == null || net.players.any { it.playerId == net.winnerPlayerId }) &&
            (net.eventSeat == null || net.eventSeat in 0..1) && (net.eventPile == null || net.eventPile in 0..1)
        if (!valid) return
        lastAppliedRemoteVersion = version
        assist = net.assist
        state.value = SpeedState(
            players = net.players.mapIndexed { i, p -> SpeedPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            hands = net.hands.map { slots ->
                slots.map { id -> if (id == SpeedNetState.EMPTY) null else if (id == SpeedNetState.HIDDEN) SpeedCards.HIDDEN else SpeedCards.all[id] }
            },
            drawCounts = net.drawCounts, reserveCounts = net.reserveCounts,
            pileTops = net.pileTops.map { if (it < 0) null else SpeedCards.all[it] }, pileSizes = net.pileSizes,
            phase = phaseValue!!, assist = net.assist, assistTurn = net.assistTurn, flipReady = net.flipReady,
            event = net.event, eventSeat = net.eventSeat, eventPile = net.eventPile, eventCardId = net.eventCardId,
            playSerial = net.playSerial, flipSerial = net.flipSerial, dealSerial = net.dealSerial,
            boardOver = net.boardOver, winnerPlayerId = net.winnerPlayerId
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintPlay.value = null
    }

    companion object {
        /** The plays open to [seat] in [s]: each hand card that fits a centre pile, once per pile. Works for any device's view. */
        fun legalPlaysIn(s: SpeedState, seat: Int): List<SpeedPlay> {
            if (s.phase != SpeedPhase.PLAYING || s.boardOver) return emptyList()
            if (s.assist && s.assistTurn != seat) return emptyList()
            val out = ArrayList<SpeedPlay>(4)
            for (card in s.hands[seat]) {
                if (card == null || card.id < 0) continue
                for (pile in 0..1) if (SpeedRules.canPlay(card, s.pileTops[pile])) out += SpeedPlay(card.id, pile)
            }
            return out
        }

        /** Whether seat [seat] has no play at all (ignoring whose turn it is in assist mode). */
        fun isStuck(s: SpeedState, seat: Int): Boolean {
            for (card in s.hands[seat]) {
                if (card == null || card.id < 0) continue
                for (pile in 0..1) if (SpeedRules.canPlay(card, s.pileTops[pile])) return false
            }
            return true
        }
    }
}
