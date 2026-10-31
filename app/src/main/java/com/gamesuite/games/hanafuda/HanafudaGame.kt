package com.gamesuite.games.hanafuda

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.SnapshotHistory
import com.gamesuite.core.UndoableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
data class HanafudaPlayer(val playerId: String, val displayName: String, val isBot: Boolean = false)

/**
 * The table as one seat sees it. [round] is already redacted for the viewer (see
 * [KoiRound.redactedFor]); a networked guest receives exactly this, so nothing hidden is in it.
 * [totals] and [roundScores] are public.
 */
@Serializable
data class HanafudaState(
    val round: KoiRound,
    val players: List<HanafudaPlayer>,
    val roundNumber: Int,
    val totalRounds: Int,
    val totals: List<Int>,
    val roundScores: List<List<Int>>,
    val finished: Boolean,
    val winnerSeat: Int
)

enum class HanafudaReject { NOT_YOUR_TURN, NOT_IN_HAND, PICK_A_FIELD_CARD, WRONG_FIELD_CARD, WRONG_PHASE }

/** What just happened, as data; the screen turns it into a localized sentence (status line and live region). */
sealed class HanafudaEvent {
    data object None : HanafudaEvent()
    data class RoundStarted(val round: Int, val parent: Int, val special: KoiEnd) : HanafudaEvent()
    data class Played(val seat: Int, val card: Int, val taken: List<Int>) : HanafudaEvent()
    data class Drew(val seat: Int, val card: Int, val taken: List<Int>) : HanafudaEvent()
    data class NeedsChoice(val seat: Int, val card: Int) : HanafudaEvent()
    data class Decided(val seat: Int, val koiKoi: Boolean) : HanafudaEvent()
    data class Rejected(val reason: HanafudaReject) : HanafudaEvent()
    data object Undone : HanafudaEvent()
    data class Hinted(val hint: KoiHint) : HanafudaEvent()
    data object Resumed : HanafudaEvent()
    data object OpponentLeft : HanafudaEvent()
    data object OpponentBack : HanafudaEvent()
}

sealed class KoiHint {
    data class Play(val play: KoiPlay) : KoiHint()
    data object Draw : KoiHint()
    data class Match(val fieldCard: Int) : KoiHint()
    data class Decide(val koiKoi: Boolean) : KoiHint()
}

@Serializable
private data class HanafudaSnapshot(
    val players: List<HanafudaPlayer>,
    val round: KoiRound,
    val roundNumber: Int,
    val totalRounds: Int,
    val totals: List<Int>,
    val roundScores: List<List<Int>>,
    val difficulty: String,
    val nextParent: Int
)

private val hanafudaJson = Json { ignoreUnknownKeys = true }

/** The Hub option ids Hanafuda reads from `LaunchOptions.extras`. */
object HanafudaOptions {
    const val ROUNDS = "rounds"
    const val YAKU_SET = "yakuSet"
    val VALID_ROUNDS: List<Int> = listOf(3, 6, 12)

    fun roundsFrom(extras: Map<String, String>): Int = extras[ROUNDS]?.toIntOrNull()?.takeIf { it in VALID_ROUNDS } ?: 3
    fun yakuSetFrom(extras: Map<String, String>): YakuSet = YakuSet.fromId(extras[YAKU_SET])
}

/**
 * Hanafuda / Koi-Koi for two players: you against the computer, two people on one device, or two
 * devices (host-authoritative, see [HanafudaNetMessage]). A match is 3, 6 or 12 rounds ("months");
 * the highest total wins. All rules are in [KoiRules]; this class holds the match, the views, undo,
 * resume and the network.
 *
 *  - Hidden information: [state] is always the viewer's redacted view. The host keeps the only full
 *    table and the shuffle; a guest never receives the host's hand or the deck order.
 *  - Undo reaches only back to the moment before the deck card is turned up in the SAME turn (taking
 *    back a mistaken hand play); turning up the deck clears it, so undo can never be used to peek at
 *    or re-roll the deck. There is no undo in networked play.
 *  - The computer works from [KoiRound.redactedFor] its own seat, off the main thread
 *    ([playBotStepAsync]); the screen supplies the think-time delays.
 */
class HanafudaGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "hanafuda"
    override val displayName = "Hanafuda"
    override val category = GameCategory.CARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<HanafudaState?>(null)
    val event = mutableStateOf<HanafudaEvent>(HanafudaEvent.None)
    val eventSeq = mutableStateOf(0)
    val hint = mutableStateOf<KoiHint?>(null)
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)

    /** Set before [init]: rounds in the match (3, 6 or 12) and the scoring set. A restored save or a host sync overrides them. */
    var totalRounds: Int = 3
        set(value) {
            field = if (value in HanafudaOptions.VALID_ROUNDS) value else 3
        }
    var yakuSet: YakuSet = YakuSet.BASIC
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Seat that is parent (moves first) in round one; set before [init]. */
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** The authoritative table (host, vs CPU, pass and play) or the last redacted sync (guest). */
    private var full: KoiRound? = null
    private var roundNumber = 1
    private var nextParent = 0
    private var totals = listOf(0, 0)
    private var roundScores: List<List<Int>> = emptyList()
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private val history = SnapshotHistory(8)

    private val isNetworked: Boolean
        get() = this::context.isInitialized && (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private val isGuest: Boolean get() = isNetworked && !isHost
    private val localSeat: Int get() = context.localPlayerIndex.coerceIn(0, 1)
    private val twoHumans: Boolean get() = this::context.isInitialized && context.players.none { it.isBot }

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        opponentLeft.value = false
        hint.value = null
        history.clear()
        roundNumber = 1
        totals = listOf(0, 0)
        roundScores = emptyList()
        nextParent = if (!isNetworked && firstOpenerIndex in 0..1) firstOpenerIndex else 0
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        full = null
        if (isNetworked) registerNetworkListeners()
    }

    private fun emit(e: HanafudaEvent) {
        event.value = e
        eventSeq.value += 1
    }

    private fun players(): List<HanafudaPlayer> = context.players.take(2).map { HanafudaPlayer(it.playerId, it.displayName, it.isBot) }

    /** The seat whose cards the screen may show: the viewer. In pass and play that is the seat on turn. */
    fun viewerSeat(): Int {
        val r = full
        return if (!isNetworked && twoHumans && r != null) r.turn else localSeat
    }

    // ---- publishing -----------------------------------------------------------------------------

    private fun publish() {
        val r = full ?: return
        val view = if (r.isRedacted) r else r.redactedFor(viewerSeat())
        val finished = r.over && roundNumber >= totalRounds
        state.value = HanafudaState(
            round = view,
            players = players(),
            roundNumber = roundNumber,
            totalRounds = totalRounds,
            totals = totals,
            roundScores = roundScores,
            finished = finished,
            winnerSeat = if (finished) leaderOf(totals) else -1
        )
    }

    private fun leaderOf(t: List<Int>): Int = when {
        t[0] > t[1] -> 0
        t[1] > t[0] -> 1
        else -> -1
    }

    private fun tallyRound(r: KoiRound) {
        val pts = List(2) { if (r.winner == it) r.points else 0 }
        roundScores = roundScores + listOf(pts)
        totals = listOf(totals[0] + pts[0], totals[1] + pts[1])
        if (r.winner >= 0) nextParent = r.winner else nextParent = r.parent
    }

    /** Every table change funnels through here: set, tally, publish, and (host) broadcast. */
    private fun commit(next: KoiRound, e: HanafudaEvent) {
        val wasOver = full?.over ?: false
        full = next
        if (next.over && !wasOver) tallyRound(next)
        publish()
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
        emit(e)
    }

    // ---- dealing --------------------------------------------------------------------------------

    override fun startMatch() {
        if (isGuest) return // the guest waits for the host's sync
        roundNumber = 1
        totals = listOf(0, 0)
        roundScores = emptyList()
        matchOver.value = false
        history.clear()
        dealRound(nextParentFor = nextParent)
    }

    private fun dealRound(nextParentFor: Int) {
        val r = KoiRules.deal(random, nextParentFor, yakuSet)
        hint.value = null
        history.clear()
        full = null
        commit(r, HanafudaEvent.RoundStarted(roundNumber, nextParentFor, r.end))
    }

    /** Moves on to the next round once this one is over (either seat may ask; a guest asks the host). */
    fun nextRound(): Boolean {
        val r = full ?: return false
        if (matchOver.value || !r.over || roundNumber >= totalRounds) return false
        if (isNetworked) {
            if (opponentLeft.value) return false
            if (isGuest) {
                sendToHost(HanafudaNetMessage.NextRound)
                return true
            }
        }
        roundNumber += 1
        dealRound(nextParent)
        return true
    }

    /** A fresh match once every round has been played. */
    fun playAgain(): Boolean {
        val r = full ?: return false
        if (matchOver.value || !(r.over && roundNumber >= totalRounds)) return false
        if (isNetworked) {
            if (opponentLeft.value) return false
            if (isGuest) {
                sendToHost(HanafudaNetMessage.RequestPlayAgain)
                return true
            }
        }
        nextParent = r.winner.takeIf { it >= 0 } ?: r.parent
        startMatch()
        return true
    }

    // ---- human actions --------------------------------------------------------------------------

    private fun reject(reason: HanafudaReject): Boolean {
        emit(HanafudaEvent.Rejected(reason))
        return false
    }

    /** The seat a human action at this device acts for, or null (a bot's turn, a networked seat that is not ours). */
    private fun actingSeat(r: KoiRound): Int? {
        if (matchOver.value) return null
        if (isNetworked) return if (r.turn == localSeat && !opponentLeft.value) localSeat else null
        return if (!context.players[r.turn].isBot) r.turn else null
    }

    /** Play [handCard]; [fieldCard] is required (and must be one of the two) when two field cards match. */
    fun playHand(handCard: Int, fieldCard: Int = -1): Boolean {
        val r = full ?: return false
        if (r.over) return false
        val seat = actingSeat(r) ?: return reject(HanafudaReject.NOT_YOUR_TURN)
        if (r.phase != KoiPhase.PLAY_HAND) return reject(HanafudaReject.WRONG_PHASE)
        if (handCard !in r.hands[seat]) return reject(HanafudaReject.NOT_IN_HAND)
        val matches = r.matchesOnField(handCard)
        if (matches.size == 2 && fieldCard == -1) return reject(HanafudaReject.PICK_A_FIELD_CARD)
        if (KoiRules.playHand(r, KoiPlay(handCard, fieldCard)) == null) return reject(HanafudaReject.WRONG_FIELD_CARD)
        if (isGuest) {
            sendToHost(HanafudaNetMessage.PlayHand(handCard, fieldCard))
            return true
        }
        if (!isNetworked) pushHistory()
        return applyPlay(KoiPlay(handCard, fieldCard))
    }

    fun drawFromDeck(): Boolean {
        val r = full ?: return false
        if (r.over) return false
        actingSeat(r) ?: return reject(HanafudaReject.NOT_YOUR_TURN)
        if (r.phase != KoiPhase.DRAW_READY) return reject(HanafudaReject.WRONG_PHASE)
        if (isGuest) {
            sendToHost(HanafudaNetMessage.DrawCard)
            return true
        }
        return applyDraw()
    }

    fun chooseMatch(fieldCard: Int): Boolean {
        val r = full ?: return false
        if (r.over) return false
        actingSeat(r) ?: return reject(HanafudaReject.NOT_YOUR_TURN)
        if (r.phase != KoiPhase.CHOOSE_DRAW_MATCH) return reject(HanafudaReject.WRONG_PHASE)
        if (fieldCard !in KoiRules.drawChoices(r)) return reject(HanafudaReject.WRONG_FIELD_CARD)
        if (isGuest) {
            sendToHost(HanafudaNetMessage.ChooseMatch(fieldCard))
            return true
        }
        return applyMatch(fieldCard)
    }

    fun decide(koiKoi: Boolean): Boolean {
        val r = full ?: return false
        if (r.over) return false
        actingSeat(r) ?: return reject(HanafudaReject.NOT_YOUR_TURN)
        if (r.phase != KoiPhase.DECIDE_AFTER_HAND && r.phase != KoiPhase.DECIDE_AFTER_DRAW) return reject(HanafudaReject.WRONG_PHASE)
        if (isGuest) {
            sendToHost(HanafudaNetMessage.Decide(koiKoi))
            return true
        }
        if (!isNetworked && r.phase == KoiPhase.DECIDE_AFTER_HAND) pushHistory()
        return applyDecide(koiKoi)
    }

    // ---- engine steps (host / local) ------------------------------------------------------------

    private fun applyPlay(play: KoiPlay): Boolean {
        val r = full ?: return false
        val next = KoiRules.playHand(r, play) ?: return false
        hint.value = null
        val gained = next.captured[r.turn].drop(r.captured[r.turn].size)
        commit(next, HanafudaEvent.Played(r.turn, play.handCard, gained))
        return true
    }

    private fun applyDraw(): Boolean {
        val r = full ?: return false
        val card = r.deck.lastOrNull() ?: return false
        val next = KoiRules.draw(r) ?: return false
        hint.value = null
        history.clear() // a card has been revealed: nothing before this point may be taken back
        if (next.phase == KoiPhase.CHOOSE_DRAW_MATCH) {
            commit(next, HanafudaEvent.NeedsChoice(r.turn, card))
        } else {
            commit(next, HanafudaEvent.Drew(r.turn, card, next.captured[r.turn].drop(r.captured[r.turn].size)))
        }
        return true
    }

    private fun applyMatch(fieldCard: Int): Boolean {
        val r = full ?: return false
        val next = KoiRules.chooseDrawMatch(r, fieldCard) ?: return false
        hint.value = null
        commit(next, HanafudaEvent.Drew(r.turn, r.drawn, next.captured[r.turn].drop(r.captured[r.turn].size)))
        return true
    }

    private fun applyDecide(koiKoi: Boolean): Boolean {
        val r = full ?: return false
        val next = KoiRules.decide(r, koiKoi) ?: return false
        hint.value = null
        if (next.phase == KoiPhase.ROUND_OVER || r.phase == KoiPhase.DECIDE_AFTER_DRAW) history.clear()
        commit(next, HanafudaEvent.Decided(r.turn, koiKoi))
        return true
    }

    // ---- computer opponent ----------------------------------------------------------------------

    private sealed class BotAction {
        data class Play(val play: KoiPlay) : BotAction()
        data object Draw : BotAction()
        data class Match(val field: Int) : BotAction()
        data class Decide(val koiKoi: Boolean) : BotAction()
    }

    /** True when the seat on turn is a computer and the round is live. */
    fun botToMove(): Boolean {
        val r = full ?: return false
        return !matchOver.value && !r.over && !isNetworked && context.players[r.turn].isBot
    }

    /**
     * One computer action (a hand play, turning up the deck, a field choice, or a koi-koi call), the
     * thinking on [Dispatchers.Default] from the bot's own redacted view. Dropped if the table changed
     * meanwhile (undo, restart, leave). The screen calls it repeatedly with delays between.
     */
    suspend fun playBotStepAsync() {
        val r = full ?: return
        if (!botToMove()) return
        val seat = r.turn
        val view = r.redactedFor(seat)
        val level = difficulty
        val seed = random.nextLong()
        val action: BotAction = withContext(Dispatchers.Default) {
            val bot = KoiBot(level, Random(seed))
            when (view.phase) {
                KoiPhase.PLAY_HAND -> BotAction.Play(bot.choosePlay(view, seat))
                KoiPhase.DRAW_READY -> BotAction.Draw
                KoiPhase.CHOOSE_DRAW_MATCH -> BotAction.Match(bot.chooseDrawMatch(view, seat))
                KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> BotAction.Decide(bot.decide(view, seat))
                KoiPhase.ROUND_OVER -> BotAction.Draw
            }
        }
        if (full !== r || matchOver.value) return
        when (action) {
            is BotAction.Play -> applyPlay(action.play)
            BotAction.Draw -> applyDraw()
            is BotAction.Match -> applyMatch(action.field)
            is BotAction.Decide -> applyDecide(action.koiKoi)
        }
    }

    /** Synchronous variant for tests: one computer action with an explicit view. */
    fun playBotStep() {
        val r = full ?: return
        if (!botToMove()) return
        val seat = r.turn
        val view = r.redactedFor(seat)
        val bot = KoiBot(difficulty, Random(random.nextLong()))
        when (view.phase) {
            KoiPhase.PLAY_HAND -> applyPlay(bot.choosePlay(view, seat))
            KoiPhase.DRAW_READY -> applyDraw()
            KoiPhase.CHOOSE_DRAW_MATCH -> applyMatch(bot.chooseDrawMatch(view, seat))
            KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> applyDecide(bot.decide(view, seat))
            KoiPhase.ROUND_OVER -> Unit
        }
    }

    // ---- hint -----------------------------------------------------------------------------------

    /** Suggests the next step for the player on turn, from that player's own view only. */
    fun requestHint(): KoiHint? {
        val r = full ?: return null
        val seat = actingSeat(r) ?: return null
        if (r.over) return null
        val view = r.redactedFor(seat)
        val h: KoiHint = when (r.phase) {
            KoiPhase.PLAY_HAND -> KoiHint.Play(KoiBot.hint(view, seat))
            KoiPhase.DRAW_READY -> KoiHint.Draw
            KoiPhase.CHOOSE_DRAW_MATCH -> KoiHint.Match(KoiBot(CpuDifficulty.HARD, Random(3)).chooseDrawMatch(view, seat))
            KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> KoiHint.Decide(KoiBot(CpuDifficulty.HARD, Random(3)).decide(view, seat))
            KoiPhase.ROUND_OVER -> return null
        }
        hint.value = h
        emit(HanafudaEvent.Hinted(h))
        return h
    }

    // ---- undo -----------------------------------------------------------------------------------

    private fun pushHistory() {
        captureSnapshot()?.let { history.push(it) }
    }

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
        emit(HanafudaEvent.Undone)
    }

    // ---- resume ---------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val r = full ?: return null
        if (r.isRedacted) return null
        return hanafudaJson.encodeToString(
            HanafudaSnapshot.serializer(),
            HanafudaSnapshot(
                players = players(), round = r, roundNumber = roundNumber, totalRounds = totalRounds,
                totals = totals, roundScores = roundScores, difficulty = difficulty.name, nextParent = nextParent
            )
        )
    }

    private fun decodeSnapshot(text: String): HanafudaSnapshot? {
        val snap = try {
            hanafudaJson.decodeFromString(HanafudaSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        return try {
            validate(snap)
        } catch (_: Exception) {
            null
        }
    }

    private fun validate(snap: HanafudaSnapshot): HanafudaSnapshot? {
        val roster = context.players
        if (snap.players.size != 2 || roster.size != 2) return null
        if (!snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }) return null
        if (snap.totalRounds !in HanafudaOptions.VALID_ROUNDS) return null
        if (snap.roundNumber !in 1..snap.totalRounds) return null
        if (!KoiRules.isConsistent(snap.round) || snap.round.isRedacted) return null
        val expectedScores = snap.roundNumber - 1 + (if (snap.round.over) 1 else 0)
        if (snap.roundScores.size != expectedScores || snap.roundScores.any { it.size != 2 || it.any { p -> p < 0 } }) return null
        if (snap.totals.size != 2 || snap.totals != listOf(snap.roundScores.sumOf { it[0] }, snap.roundScores.sumOf { it[1] })) return null
        if (snap.nextParent !in 0..1) return null
        return snap
    }

    private fun applySnapshot(snap: HanafudaSnapshot, restoreDifficulty: Boolean) {
        totalRounds = snap.totalRounds
        yakuSet = snap.round.yakuSet
        roundNumber = snap.roundNumber
        totals = snap.totals
        roundScores = snap.roundScores
        nextParent = snap.nextParent
        if (restoreDifficulty) difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
        full = snap.round
        hint.value = null
        publish()
    }

    override fun snapshot(): String? {
        if (matchOver.value || full == null || !::context.isInitialized) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return try {
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        emit(HanafudaEvent.Resumed)
        return true
    }

    // ---- session --------------------------------------------------------------------------------

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Back to the menu: the running totals decide the result (a strict leader wins; level totals are no winner). */
    fun leaveSession() {
        if (matchOver.value) return
        val best = totals.max()
        val leaders = totals.count { it == best }
        val scores = context.players.take(2).mapIndexed { i, p ->
            PlayerScore(p.playerId, totals[i], isWinner = best > 0 && totals[i] == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    // ---- networking -----------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(HanafudaNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(HanafudaNetMessage.RequestState) }
        transport.onPlayerLeft { id ->
            if (id == otherSeatId()) {
                opponentLeft.value = true
                emit(HanafudaEvent.OpponentLeft)
            }
        }
        transport.onPlayerJoined { id ->
            if (id == otherSeatId() && opponentLeft.value) {
                opponentLeft.value = false
                emit(HanafudaEvent.OpponentBack)
                if (isHost) broadcastState()
            }
        }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - localSeat)?.playerId

    private fun send(message: HanafudaNetMessage, toPlayerId: String?) {
        val localId = context.players.getOrNull(localSeat)?.playerId ?: return
        val payload = Json.encodeToString(HanafudaNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = toPlayerId, payload = payload)
    }

    private fun sendToHost(message: HanafudaNetMessage) = send(message, context.players.getOrNull(0)?.playerId)

    private fun broadcastState() {
        val r = full ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val view = HanafudaState(
            round = r.redactedFor(1), players = players(), roundNumber = roundNumber, totalRounds = totalRounds,
            totals = totals, roundScores = roundScores,
            finished = r.over && roundNumber >= totalRounds,
            winnerSeat = if (r.over && roundNumber >= totalRounds) leaderOf(totals) else -1
        )
        send(HanafudaNetMessage.StateSync(stateVersion, view), guestId)
    }

    private fun handleNetworkMessage(from: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(HanafudaNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        if (message is HanafudaNetMessage.StateSync) {
            if (!isHost && from == hostId) {
                try {
                    applyRemoteState(message.version, message.state)
                } catch (_: Exception) {
                    // A malformed table from the wire never crashes the match; the next valid sync repairs it.
                }
            }
            return
        }
        // Everything else is a request, honoured only by the host and only from the guest seat.
        if (!isHost || from != guestId) return
        val r = full
        when (message) {
            is HanafudaNetMessage.RequestState -> broadcastState()
            is HanafudaNetMessage.RequestPlayAgain -> playAgain()
            is HanafudaNetMessage.NextRound -> nextRound()
            is HanafudaNetMessage.PlayHand ->
                if (r != null && !matchOver.value && !opponentLeft.value && r.turn == 1 && r.phase == KoiPhase.PLAY_HAND) {
                    applyPlay(KoiPlay(message.handCard, message.fieldCard))
                }
            is HanafudaNetMessage.DrawCard ->
                if (r != null && !matchOver.value && !opponentLeft.value && r.turn == 1 && r.phase == KoiPhase.DRAW_READY) applyDraw()
            is HanafudaNetMessage.ChooseMatch ->
                if (r != null && !matchOver.value && !opponentLeft.value && r.turn == 1) applyMatch(message.fieldCard)
            is HanafudaNetMessage.Decide ->
                if (r != null && !matchOver.value && !opponentLeft.value && r.turn == 1) applyDecide(message.koiKoi)
            is HanafudaNetMessage.StateSync -> Unit
        }
    }

    /** Guest: adopt a strictly newer, well-formed host table. */
    private fun applyRemoteState(version: Int, net: HanafudaState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val r = net.round
        val hidden = r.hands.getOrNull(0)?.size ?: -1
        val valid = net.players.size == 2 && roster.size == 2 &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            r.hands.size == 2 && r.captured.size == 2 && r.koiCalls.size == 2 && r.decisionBase.size == 2 &&
            r.turn in 0..1 && r.parent in 0..1 && hidden in 0..KoiRules.HAND_SIZE && r.hands[1].size in 0..KoiRules.HAND_SIZE &&
            r.hands[localSeat].all { HanafudaCards.isReal(it) } && r.field.all { HanafudaCards.isReal(it) } &&
            r.captured.flatten().all { HanafudaCards.isReal(it) } &&
            net.totalRounds in HanafudaOptions.VALID_ROUNDS && net.roundNumber in 1..net.totalRounds &&
            net.totals.size == 2 && net.roundScores.all { it.size == 2 } && net.winnerSeat in -1..1
        if (!valid) return
        lastAppliedRemoteVersion = version
        val previous = full
        val newRound = net.roundNumber != roundNumber
        totalRounds = net.totalRounds
        yakuSet = r.yakuSet
        roundNumber = net.roundNumber
        totals = net.totals
        roundScores = net.roundScores
        full = r
        hint.value = null
        publish()
        emit(if (newRound) HanafudaEvent.RoundStarted(roundNumber, r.parent, r.end) else HanafudaEvents.derive(previous, r))
    }
}

/** Works out what a guest should announce from two consecutive redacted tables (the guest never sees the actions themselves). */
object HanafudaEvents {
    fun derive(old: KoiRound?, new: KoiRound): HanafudaEvent {
        if (old == null) return HanafudaEvent.None
        if (old.over && !new.over) return HanafudaEvent.RoundStarted(0, new.parent, new.end)
        val seat = old.turn
        fun publicCards(r: KoiRound) = r.field + r.captured.flatten() + (if (r.drawn >= 0) listOf(r.drawn) else emptyList())
        val appeared = publicCards(new) - publicCards(old).toSet()
        val gained = new.captured[seat].drop(old.captured[seat].size)
        return when (old.phase) {
            KoiPhase.PLAY_HAND -> appeared.firstOrNull()?.let { HanafudaEvent.Played(seat, it, gained) } ?: HanafudaEvent.None
            KoiPhase.DRAW_READY ->
                if (new.phase == KoiPhase.CHOOSE_DRAW_MATCH) HanafudaEvent.NeedsChoice(seat, new.drawn)
                else appeared.firstOrNull()?.let { HanafudaEvent.Drew(seat, it, gained) } ?: HanafudaEvent.None
            KoiPhase.CHOOSE_DRAW_MATCH -> HanafudaEvent.Drew(seat, old.drawn, gained)
            KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> HanafudaEvent.Decided(seat, new.koiCalls[seat] > old.koiCalls[seat])
            KoiPhase.ROUND_OVER -> HanafudaEvent.None
        }
    }
}
