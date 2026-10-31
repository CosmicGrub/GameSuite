package com.gamesuite.games.war

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

object WarPhase {
    const val FLIP = "flip"
    const val PICK = "pick"
    const val OVER = "over"
    val ALL = setOf(FLIP, PICK, OVER)
}

enum class WarReject { NONE, OVER, WRONG_PHASE, NOT_YOUR_TURN, BAD_CHOICE, ALREADY_PICKED }

data class WarSeat(
    val playerId: String, val displayName: String, val isBot: Boolean,
    /** Cards in the pile (the cards themselves are private to the host). */
    val count: Int,
    /** True once this seat has secretly chosen (choose-3 only). */
    val picked: Boolean = false,
    /** Games won in this session. */
    val wins: Int = 0
)

/**
 * [choices] holds, per seat, the (up to three) cards that seat may still choose from. On the host and in
 * a pass-and-play game it holds every unpicked seat's candidates (the screen only ever shows the seat
 * whose turn it is to pick); on a guest it holds ONLY the guest's own seat.
 * [lastRound] is the round just resolved (everything in it is public once resolved); [round] counts them.
 */
data class WarState(
    val seats: List<WarSeat>,
    val phase: String,
    val round: Int,
    val lastRound: WarRound?,
    val choices: List<List<Card>>,
    val winnerSeat: Int,
    val draw: Boolean,
    val config: WarConfig,
    val serial: Int
)

@Serializable
private data class WarSnap(
    val seats: List<WarSnapSeat>, val piles: List<List<Int>>, val picks: List<Int>, val phase: String,
    val round: Int, val winnerSeat: Int, val draw: Boolean, val config: WarConfig, val serial: Int,
    val difficulty: String
)

@Serializable
private data class WarSnapSeat(val playerId: String, val displayName: String, val isBot: Boolean, val wins: Int)

private val warSnapJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val ALL_CARDS: List<Card> = Deck.fresh52()

/**
 * War for 2 to 4 seats. The deck is dealt out; every round all seats flip (or, in the Choose-3 variant,
 * secretly pick one of their top three cards); the highest card takes the pot. Equal top cards go to
 * war: each tied seat puts down [WarConfig.warDown] cards face down and flips a deciding card.
 * The game ends when one seat holds every card, or after [WarConfig.maxRounds] rounds, when the larger
 * pile wins (equal piles draw). THE SHUFFLES come only from the injected [random] (the deal, and the
 * order a won pot returns to the bottom of a pile), so a seeded Random replays a game exactly.
 *
 * No Undo: a round is resolved the instant everyone has flipped or picked, and in Choose-3 taking back a
 * pick would reveal the opponent's card. In a networked game only the host holds the piles.
 */
class WarGame(private val random: Random = Random.Default) : GameModule, ResumableGame {
    override val gameId = "war"
    override val displayName = "War"
    override val category = GameCategory.CARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<WarState?>(null)
    var config: WarConfig = WarConfig()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)
    val hintIndex = mutableStateOf<Int?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var piles: List<ArrayDeque<Card>> = emptyList()
    private var picks = IntArray(0)
    private var sessionWins = IntArray(0)
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    override fun init(context: GameContext) {
        require(WarRules.seatCountOk(context.players.size)) { "War needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        hintIndex.value = null
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        piles = emptyList()
        picks = IntArray(context.players.size) { -1 }
        sessionWins = IntArray(context.players.size)
        if (isNetworked) registerNetworkListeners()
    }

    // --- Queries ---------------------------------------------------------------------------

    private fun startPhase() = if (config.variant == WarVariant.CHOOSE3) WarPhase.PICK else WarPhase.FLIP

    /** The seat that must pick right now on THIS device, or null. In pass and play it is the first person without a pick. */
    fun pickerSeat(): Int? {
        val s = state.value ?: return null
        if (matchOver.value || s.phase != WarPhase.PICK) return null
        if (isNetworked) {
            if (hostLeft.value) return null
            val me = context.localPlayerIndex
            return if (s.choices.getOrNull(me)?.isNotEmpty() == true) me else null
        }
        return s.seats.indices.firstOrNull { !s.seats[it].isBot && s.choices[it].isNotEmpty() }
    }

    /** The candidates the person at this device may choose from right now. */
    fun myChoices(): List<Card> = pickerSeat()?.let { state.value?.choices?.getOrNull(it) } ?: emptyList()

    fun canFlip(): Boolean {
        val s = state.value ?: return false
        return !matchOver.value && s.phase == WarPhase.FLIP && !hostLeft.value
    }

    fun explainFlip(): WarReject {
        val s = state.value ?: return WarReject.OVER
        return when {
            matchOver.value || s.phase == WarPhase.OVER -> WarReject.OVER
            s.phase != WarPhase.FLIP -> WarReject.WRONG_PHASE
            hostLeft.value -> WarReject.NOT_YOUR_TURN
            else -> WarReject.NONE
        }
    }

    fun explainPick(index: Int): WarReject {
        val s = state.value ?: return WarReject.OVER
        if (matchOver.value || s.phase == WarPhase.OVER) return WarReject.OVER
        if (s.phase != WarPhase.PICK) return WarReject.WRONG_PHASE
        val seat = pickerSeat() ?: return WarReject.NOT_YOUR_TURN
        return if (index !in s.choices[seat].indices) WarReject.BAD_CHOICE else WarReject.NONE
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        newDeal(round = 0)
    }

    private fun newDeal(round: Int) {
        val n = context.players.size
        piles = WarRules.deal(Deck.fresh52().shuffled(random), n)
        picks = IntArray(n) { -1 }
        hintIndex.value = null
        publish(startPhase(), round, null, -1, false)
    }

    private fun publish(phase: String, round: Int, last: WarRound?, winner: Int, draw: Boolean) {
        val n = context.players.size
        val prev = state.value
        val seats = List(n) { i ->
            val p = context.players[i]
            val bot = prev?.seats?.getOrNull(i)?.isBot ?: p.isBot
            WarSeat(p.playerId, p.displayName, bot, piles[i].size, picked = picks[i] >= 0, wins = sessionWins[i])
        }
        val choices = List(n) { i ->
            if (phase == WarPhase.PICK && picks[i] < 0) piles[i].take(WarRules.CHOICES) else emptyList()
        }
        commit(WarState(seats, phase, round, last, choices, winner, draw, config, (prev?.serial ?: -1) + 1))
    }

    private fun commit(ns: WarState) {
        state.value = ns
        hintIndex.value = null
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
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

    /** Ends the session: most game wins takes it (a tie for the most is a draw). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.toList()
        val best = wins.maxOrNull() ?: 0
        val leaders = wins.count { it == best }
        val scores = context.players.mapIndexed { i, p ->
            PlayerScore(p.playerId, wins[i], isWinner = best > 0 && wins[i] == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    fun playAgain() {
        if (matchOver.value || hostLeft.value) return
        val s = state.value ?: return
        if (s.phase != WarPhase.OVER) return
        if (isNetworked && !isHost) { sendToHost(WarNetMessage.PlayAgainRequest); return }
        newDeal(round = 0)
    }

    // --- Playing ---------------------------------------------------------------------------

    /** Classic: flip for the table. True when accepted. */
    fun flip(): Boolean {
        if (explainFlip() != WarReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(WarNetMessage.FlipRequest); return true }
        return doFlip()
    }

    private fun doFlip(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != WarPhase.FLIP) return false
        val firsts = HashMap<Int, Card>()
        piles.forEachIndexed { i, p -> if (p.isNotEmpty()) firsts[i] = p.removeFirst() }
        finishRound(s, firsts)
        return true
    }

    /** The person at this device picks the candidate at [index]. True when accepted. */
    fun pick(index: Int): Boolean {
        if (explainPick(index) != WarReject.NONE) return false
        val seat = pickerSeat() ?: return false
        if (isNetworked && !isHost) { sendToHost(WarNetMessage.PickRequest(index)); return true }
        return doPick(seat, index)
    }

    private fun doPick(seat: Int, index: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != WarPhase.PICK) return false
        if (seat !in piles.indices || picks[seat] >= 0) return false
        if (index !in 0 until minOf(WarRules.CHOICES, piles[seat].size)) return false
        picks[seat] = index
        val waiting = piles.indices.any { piles[it].isNotEmpty() && picks[it] < 0 }
        if (waiting) {
            publish(WarPhase.PICK, s.round, s.lastRound, -1, false)
            return true
        }
        val firsts = HashMap<Int, Card>()
        piles.indices.forEach { i -> if (piles[i].isNotEmpty() && picks[i] >= 0) firsts[i] = piles[i].removeAt(picks[i]) }
        picks = IntArray(picks.size) { -1 }
        finishRound(s, firsts)
        return true
    }

    private fun finishRound(s: WarState, firsts: Map<Int, Card>) {
        val round = WarRules.resolve(piles, firsts, config.warDown, random)
        val rounds = s.round + 1
        val alive = piles.indices.filter { piles[it].isNotEmpty() }
        var phase = startPhase()
        var winner = -1
        var draw = false
        if (alive.size == 1) {
            phase = WarPhase.OVER; winner = alive[0]
        } else if (rounds >= config.maxRounds) {
            phase = WarPhase.OVER
            val best = piles.maxOf { it.size }
            val top = piles.indices.filter { piles[it].size == best }
            if (top.size == 1) winner = top[0] else draw = true
        }
        if (winner >= 0) sessionWins[winner]++
        publish(phase, rounds, round, winner, draw)
    }

    // --- Hint and computer seats -----------------------------------------------------------

    /** The candidate index with the best expected card swing, for the seat picking now. */
    fun hint(): Int? {
        val choices = myChoices()
        if (choices.isEmpty()) return null
        return WarRules.bestChoice(choices).also { hintIndex.value = it }
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: WarState): Boolean {
        if (matchOver.value || s.phase != WarPhase.PICK) return false
        if (isNetworked && !isHost) return false
        return s.seats.indices.any { s.seats[it].isBot && s.choices[it].isNotEmpty() }
    }

    private fun chooseBot(candidates: List<Card>): Int {
        val best = WarRules.bestChoice(candidates)
        return when (difficulty) {
            CpuDifficulty.HARD -> best
            CpuDifficulty.MEDIUM -> if (random.nextDouble() < 0.35) random.nextInt(candidates.size) else best
            CpuDifficulty.EASY -> random.nextInt(candidates.size)
        }
    }

    /** One computer pick, chosen off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val seat = s.seats.indices.first { s.seats[it].isBot && s.choices[it].isNotEmpty() }
        val choice = withContext(Dispatchers.Default) { chooseBot(s.choices[seat]) }
        if (state.value !== s || matchOver.value) return
        doPick(seat, choice)
    }

    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val seat = s.seats.indices.first { s.seats[it].isBot && s.choices[it].isNotEmpty() }
        doPick(seat, chooseBot(s.choices[seat]))
    }

    // --- Resume ----------------------------------------------------------------------------

    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            warSnapJson.encodeToString(
                WarSnap.serializer(),
                WarSnap(
                    seats = s.seats.mapIndexed { i, seat -> WarSnapSeat(seat.playerId, seat.displayName, seat.isBot, sessionWins[i]) },
                    piles = piles.map { p -> p.map { it.id } }, picks = picks.toList(), phase = s.phase, round = s.round,
                    winnerSeat = s.winnerSeat, draw = s.draw, config = config, serial = s.serial, difficulty = difficulty.name
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = warSnapJson.decodeFromString(WarSnap.serializer(), snapshot)
            val roster = context.players
            val n = roster.size
            val ok = snap.seats.size == n && snap.piles.size == n && snap.picks.size == n &&
                snap.seats.indices.all { snap.seats[it].playerId == roster[it].playerId && snap.seats[it].isBot == roster[it].isBot } &&
                snap.config.isValid && snap.phase in WarPhase.ALL && snap.round >= 0 &&
                snap.seats.all { it.wins >= 0 } &&
                snap.winnerSeat in -1 until n && snap.serial >= 0 &&
                snap.piles.flatten().sorted() == (0 until 52).toList() &&
                (snap.phase == WarPhase.PICK) == (snap.config.variant == WarVariant.CHOOSE3 && snap.phase != WarPhase.OVER) &&
                snap.picks.indices.all { i -> snap.picks[i] == -1 || (snap.phase == WarPhase.PICK && snap.picks[i] in 0 until minOf(3, snap.piles[i].size)) } &&
                (snap.phase != WarPhase.OVER || snap.winnerSeat >= 0 || snap.draw) &&
                (snap.phase == WarPhase.OVER || (snap.winnerSeat == -1 && !snap.draw))
            if (!ok) return false
            matchOver.value = false
            config = snap.config
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
            piles = snap.piles.map { ids -> ArrayDeque(ids.map { ALL_CARDS[it] }) }
            picks = snap.picks.toIntArray()
            sessionWins = IntArray(n) { snap.seats[it].wins }
            val prev = state.value
            val seats = List(n) { i ->
                WarSeat(roster[i].playerId, roster[i].displayName, roster[i].isBot, piles[i].size, picks[i] >= 0, sessionWins[i])
            }
            val choices = List(n) { i ->
                if (snap.phase == WarPhase.PICK && picks[i] < 0) piles[i].take(WarRules.CHOICES) else emptyList()
            }
            state.value = WarState(seats, snap.phase, snap.round, null, choices, snap.winnerSeat, snap.draw, config, maxOf(snap.serial, (prev?.serial ?: -1) + 1))
            hintIndex.value = null
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Networking ------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(WarNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(WarNetMessage.RequestState) }
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
        commit(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == seat) x.copy(isBot = true) else x }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(WarNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == seat) x.copy(isBot = false) else x }))
    }

    /** The wire form of [s] for the seat [forSeat]: only that seat's own candidates; no pile contents at all. */
    internal fun toNet(s: WarState, forSeat: Int): WarNetState = WarNetState(
        seats = s.seats.map { WarNetSeat(it.playerId, it.displayName, it.isBot, it.count, it.picked, it.wins) },
        phase = s.phase, round = s.round,
        lastRound = s.lastRound?.let { r ->
            WarNetRound(r.steps.map { st -> WarNetStep(st.plays.map { WarNetPlay(it.seat, it.down, it.up.id) }) }, r.winnerSeat, r.potSize)
        },
        choices = s.choices.mapIndexed { i, c -> if (i == forSeat) c.map { it.id } else emptyList() },
        winnerSeat = s.winnerSeat, draw = s.draw, config = s.config, serial = s.serial
    )

    private fun encode(m: WarNetMessage) = Json.encodeToString(WarNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun sendStateTo(seat: Int) {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val to = context.players.getOrNull(seat)?.playerId ?: return
        context.transport.send(hostId, to, encode(WarNetMessage.StateSync(stateVersion, toNet(s, seat))))
    }

    private fun broadcastState() {
        context.players.indices.drop(1).filter { !context.players[it].isBot }.forEach { sendStateTo(it) }
    }

    private fun sendToHost(message: WarNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(localId, hostId, encode(message))
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(WarNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        val human = isHost && s != null && senderSeat > 0 && !s.seats[senderSeat].isBot && !matchOver.value
        when (message) {
            is WarNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is WarNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(senderSeat)
            is WarNetMessage.FlipRequest -> if (human) doFlip()
            is WarNetMessage.PickRequest -> if (human) doPick(senderSeat, message.index)
            is WarNetMessage.PlayAgainRequest ->
                if (human && s?.phase == WarPhase.OVER) newDeal(round = 0)
        }
    }

    private fun applyRemoteState(version: Int, net: WarNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size
        val valid = net.seats.size == n && net.choices.size == n &&
            net.seats.indices.all { net.seats[it].playerId == roster[it].playerId && net.seats[it].count in 0..52 } &&
            net.seats.sumOf { it.count } in 0..52 && net.phase in WarPhase.ALL && net.config.isValid &&
            net.round >= 0 && net.winnerSeat in -1 until n &&
            net.choices.all { c -> c.size <= WarRules.CHOICES && c.all { it in 0..51 } } &&
            (net.phase == WarPhase.PICK || net.choices.all { it.isEmpty() }) &&
            (net.lastRound == null || net.lastRound.let { r ->
                r.winnerSeat in -1 until n && r.potSize in 0..52 && r.steps.isNotEmpty() && r.steps.size <= 30 &&
                    r.steps.all { st -> st.plays.isNotEmpty() && st.plays.all { it.seat in 0 until n && it.up in 0..51 && it.down in 0..3 } }
            })
        if (!valid) return
        lastAppliedRemoteVersion = version
        config = net.config
        sessionWins = IntArray(n) { net.seats[it].wins }
        state.value = WarState(
            seats = net.seats.map { WarSeat(it.playerId, it.displayName, it.isBot, it.count, it.picked, it.wins) },
            phase = net.phase, round = net.round,
            lastRound = net.lastRound?.let { r ->
                WarRound(r.steps.map { st -> WarStep(st.plays.map { WarPlay(it.seat, it.down, ALL_CARDS[it.up]) }) }, r.winnerSeat, r.potSize)
            },
            choices = net.choices.map { c -> c.map { ALL_CARDS[it] } },
            winnerSeat = net.winnerSeat, draw = net.draw, config = net.config, serial = net.serial
        )
        hintIndex.value = null
    }
}
