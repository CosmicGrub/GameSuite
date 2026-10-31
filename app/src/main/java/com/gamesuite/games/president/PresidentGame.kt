package com.gamesuite.games.president

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
private data class PsSnapshot(val roster: List<String>, val state: PresidentState, val difficulty: String)

private val psSnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val psWireJson = Json { ignoreUnknownKeys = true }

/**
 * President (Scum, Asshole) for 3 to 6 seats: shed your hand with singles, pairs, triples or fours that
 * beat the last play in the same count (3 low, 2 high). See [PresidentFlow] for the rules in motion,
 * [PresidentBot] for the computer players and [PresidentNetMessage] for the wire format.
 *
 * HOST-AUTHORITATIVE NETWORKING (Nearby and Online): seat 0 shuffles and validates; every other device
 * renders the last [PresidentNetMessage.StateSync] it was sent (with every hand but its own replaced by
 * placeholders) and turns its taps into intents. A watcher without a seat gets a hands-hidden view.
 * Undo and hints exist offline only: online a played card is already known to everyone.
 */
class PresidentGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "president"
    override val displayName = "President"
    override val category = GameCategory.CARD
    override val minPlayers = 3
    override val maxPlayers = 6
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<PresidentState?>(null)

    /** The Hub's options for the NEXT session; a restored save or a host sync carries its own. */
    var rules: PresidentRuleSet = PresidentRuleSet()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    /** The cards (or pass) the last hint suggested. */
    val hint = mutableStateOf<PsHint?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val history = SnapshotHistory()
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private val spectatorIds = LinkedHashSet<String>()
    private var sessionsFinished = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    /** True on a device that watches a networked game without a seat. */
    val isSpectator: Boolean get() = isNetworked && context.localPlayerIndex < 0

    /** The seat this device plays: the local seat online; offline the screen decides (the seat on turn). */
    val localSeat: Int get() = if (this::context.isInitialized) context.localPlayerIndex else 0

    val networked: Boolean get() = isNetworked

    override fun init(context: GameContext) {
        require(PresidentRules.validSeatCount(context.players.size)) { "President needs 3 to 6 seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        hint.value = null
        history.clear()
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        sessionsFinished = 0
        spectatorIds.clear()
        if (isNetworked) registerNetworkListeners()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // ---- queries ------------------------------------------------------------------------------

    /** Whether the seat on turn belongs to a person at THIS device. */
    fun isMyTurn(s: PresidentState): Boolean {
        if (matchOver.value || s.phase != PsPhase.PLAY || isSpectator) return false
        if (s.seats[s.currentSeat].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentSeat == context.localPlayerIndex else true
    }

    /** The seat whose person must choose cards to give now (exchange phase), or null. */
    fun giverSeat(s: PresidentState): Int? {
        if (matchOver.value || s.phase != PsPhase.EXCHANGE || isSpectator) return null
        val candidates = s.pendingExchanges.map { it.from }.filter { !s.seats[it].isBot }
        return if (isNetworked) candidates.firstOrNull { it == context.localPlayerIndex && !hostLeft.value } else candidates.firstOrNull()
    }

    /** The seat whose hand this device shows: the local seat online, else the person acting. */
    fun viewSeat(s: PresidentState): Int {
        if (isNetworked) return context.localPlayerIndex
        giverSeat(s)?.let { return it }
        if (s.phase == PsPhase.PLAY && !s.seats[s.currentSeat].isBot) return s.currentSeat
        return s.seats.indexOfFirst { !it.isBot }.coerceAtLeast(0)
    }

    fun humanSeatCount(): Int = if (this::context.isInitialized) context.players.count { !it.isBot } else 0

    /** Why [ids] would not be accepted as a play right now (NONE = it would). */
    fun explainPlay(ids: List<Int>): PresidentReject {
        val s = state.value ?: return PresidentReject.NOT_STARTED
        if (isSpectator) return PresidentReject.SPECTATOR
        if (matchOver.value || s.phase != PsPhase.PLAY) return PresidentReject.WRONG_PHASE
        if (!isMyTurn(s)) return PresidentReject.NOT_YOUR_TURN
        return PresidentRules.explainPlay(ids, s.seats[s.currentSeat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude)
    }

    fun explainPass(): PresidentReject {
        val s = state.value ?: return PresidentReject.NOT_STARTED
        if (isSpectator) return PresidentReject.SPECTATOR
        if (matchOver.value || s.phase != PsPhase.PLAY) return PresidentReject.WRONG_PHASE
        if (!isMyTurn(s)) return PresidentReject.NOT_YOUR_TURN
        return if (s.top == null) PresidentReject.CANNOT_PASS_LEAD else PresidentReject.NONE
    }

    fun explainGive(ids: List<Int>): PresidentReject {
        val s = state.value ?: return PresidentReject.NOT_STARTED
        if (isSpectator) return PresidentReject.SPECTATOR
        if (matchOver.value || s.phase != PsPhase.EXCHANGE) return PresidentReject.WRONG_PHASE
        val seat = giverSeat(s) ?: return PresidentReject.NOT_YOUR_TURN
        val task = s.pendingExchanges.first { it.from == seat }
        val hand = s.seats[seat].hand
        if (ids.any { it !in hand } || ids.toSet().size != ids.size) return PresidentReject.NOT_IN_HAND
        return if (ids.size == task.count) PresidentReject.NONE else PresidentReject.WRONG_GIFT_COUNT
    }

    /** True when the seat on turn has at least one legal play (the screen can skip a hopeless wait). */
    fun hasLegalPlay(): Boolean = state.value?.let { hasLegalPlay(it) } ?: false

    fun hasLegalPlay(s: PresidentState): Boolean {
        if (s.phase != PsPhase.PLAY) return false
        return PresidentRules.candidatePlays(s.seats[s.currentSeat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude).isNotEmpty()
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private fun roster(): List<PsSeat> = context.players.map { PsSeat(it.playerId, it.displayName, it.isBot) }

    override fun startMatch() {
        if (isNetworked && !isHost) return
        history.clear()
        hint.value = null
        matchOver.value = false
        commit(PresidentFlow.newSession(roster(), rules, random, difficulty))
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** The player leaves the table for good: the sessions finished so far (if any) are recorded. */
    fun leaveSession() {
        if (matchOver.value) return
        if (sessionsFinished == 0 && state.value?.sessionOver != true) {
            abortMatch()
            return
        }
        val s = state.value
        val wins = s?.sessionWins ?: emptyMap()
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    /** The result of a session that just ended: the winner is the seat with most points (a shared top is no winner). */
    private fun noteSessionFinished(s: PresidentState) {
        if (s.phase == PsPhase.SESSION_OVER) sessionsFinished += 1
    }

    // ---- actions of the local player -----------------------------------------------------------

    /** Plays [ids] for the seat on turn at this device. NONE when accepted (applied here, or asked of the host). */
    fun tryPlay(ids: List<Int>): PresidentReject {
        val why = explainPlay(ids)
        if (why != PresidentReject.NONE) return why
        if (isNetworked && !isHost) {
            sendIntent(PresidentIntent.Play(ids))
            return PresidentReject.NONE
        }
        val s = state.value ?: return PresidentReject.NOT_STARTED
        doPlay(s.currentSeat, ids, fromHuman = true)
        return PresidentReject.NONE
    }

    fun tryPass(): PresidentReject {
        val why = explainPass()
        if (why != PresidentReject.NONE) return why
        if (isNetworked && !isHost) {
            sendIntent(PresidentIntent.Pass)
            return PresidentReject.NONE
        }
        val s = state.value ?: return PresidentReject.NOT_STARTED
        doPass(s.currentSeat, fromHuman = true)
        return PresidentReject.NONE
    }

    fun tryGive(ids: List<Int>): PresidentReject {
        val why = explainGive(ids)
        if (why != PresidentReject.NONE) return why
        if (isNetworked && !isHost) {
            sendIntent(PresidentIntent.Give(ids))
            return PresidentReject.NONE
        }
        val s = state.value ?: return PresidentReject.NOT_STARTED
        doGive(giverSeat(s) ?: return PresidentReject.NOT_YOUR_TURN, ids)
        return PresidentReject.NONE
    }

    /** Next round once a round is over; a fresh session once the session is over. */
    fun continueSession() {
        val s = state.value ?: return
        if (matchOver.value || isSpectator || !s.roundOver) return
        if (isNetworked && !isHost) {
            if (!hostLeft.value) sendIntent(PresidentIntent.Continue)
            return
        }
        doContinue()
    }

    private fun doContinue() {
        val s = state.value ?: return
        val next = when (s.phase) {
            PsPhase.ROUND_OVER -> PresidentFlow.nextRound(s, random, difficulty)
            PsPhase.SESSION_OVER -> PresidentFlow.restartSession(s, random, difficulty)
            else -> null
        } ?: return
        history.clear()
        hint.value = null
        commit(next)
    }

    // ---- applying moves (host or offline) ------------------------------------------------------

    private fun doPlay(seat: Int, ids: List<Int>, fromHuman: Boolean) {
        val s = state.value ?: return
        val next = PresidentFlow.play(s, seat, ids) ?: return
        if (fromHuman && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hint.value = null
        commit(next)
    }

    private fun doPass(seat: Int, fromHuman: Boolean) {
        val s = state.value ?: return
        val next = PresidentFlow.pass(s, seat) ?: return
        if (fromHuman && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hint.value = null
        commit(next)
    }

    private fun doGive(seat: Int, ids: List<Int>) {
        val s = state.value ?: return
        val given = PresidentFlow.give(s, seat, ids) ?: return
        if (!isNetworked) captureSnapshot()?.let { history.push(it) }
        hint.value = null
        commit(PresidentFlow.resolveBotGifts(given, difficulty))
    }

    // ---- computer players ---------------------------------------------------------------------

    /** True when the host (or an offline game) has a computer on turn to move. */
    fun botToMove(s: PresidentState): Boolean {
        if (matchOver.value || s.phase != PsPhase.PLAY) return false
        if (isNetworked && !isHost) return false
        return s.seats[s.currentSeat].isBot
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    /** One computer move; the choice is made off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val seat = s.currentSeat
        val choice = withContext(Dispatchers.Default) { PresidentBot.choose(s, seat, difficulty, random) }
        if (state.value !== s || matchOver.value) return
        applyBot(s, seat, choice)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        applyBot(s, s.currentSeat, PresidentBot.choose(s, s.currentSeat, difficulty, random))
    }

    private fun applyBot(s: PresidentState, seat: Int, choice: List<Int>?) {
        if (choice == null) {
            if (s.top != null) doPass(seat, fromHuman = false)
            else PresidentBot.choose(s, seat, CpuDifficulty.EASY, random)?.let { doPlay(seat, it, fromHuman = false) }
        } else {
            doPlay(seat, choice, fromHuman = false)
        }
    }

    // ---- undo / hint --------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decode(previous) ?: return
        state.value = snap.state
        hint.value = null
    }

    private fun hintAllowed(s: PresidentState): Boolean =
        !isNetworked && !matchOver.value && ((s.phase == PsPhase.PLAY && isMyTurn(s)) || giverSeat(s) != null)

    suspend fun requestHint(): PsHint? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val seat = if (s.phase == PsPhase.EXCHANGE) giverSeat(s)!! else s.currentSeat
        val found = withContext(Dispatchers.Default) { PresidentBot.hint(s, seat) }
        if (state.value !== s || matchOver.value) return null
        hint.value = found
        return found
    }

    fun hintNow(): PsHint? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val seat = if (s.phase == PsPhase.EXCHANGE) giverSeat(s)!! else s.currentSeat
        return PresidentBot.hint(s, seat).also { hint.value = it }
    }

    // ---- resume -------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return try {
            psSnapshotJson.encodeToString(PsSnapshot.serializer(), PsSnapshot(context.players.map { it.playerId }, s, difficulty.name))
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(text: String): PsSnapshot? = try {
        val snap = psSnapshotJson.decodeFromString(PsSnapshot.serializer(), text)
        val ok = snap.roster == context.players.map { it.playerId } &&
            PresidentValidator.isValid(snap.state, context.players, allowHidden = false)
        if (ok) snap else null
    } catch (_: Exception) {
        null
    }

    override fun snapshot(): String? {
        if (matchOver.value || state.value == null || !this::context.isInitialized) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return captureSnapshot()
    }

    override fun restore(snapshot: String): Boolean {
        if (isNetworked) return false
        val snap = decode(snapshot) ?: return false
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        rules = snap.state.rules
        state.value = snap.state
        matchOver.value = false
        hint.value = null
        history.clear()
        sessionsFinished = snap.state.sessionWins.values.sum()
        return true
    }

    // ---- networking ---------------------------------------------------------------------------

    private fun commit(newState: PresidentState) {
        state.value = newState
        noteSessionFinished(newState)
        if (isNetworked && isHost) {
            stateVersion++
            broadcast()
        }
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost && !isSpectator) sendToHost(PresidentNetMessage.RequestState)
        if (isSpectator) sendToHost(PresidentNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcast() else sendToHost(PresidentNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        if (isHost && spectatorIds.remove(playerId)) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0) hostLeft.value = true
            return
        }
        val s = state.value ?: return
        if (seat == 0 || s.seats[seat].isBot) return
        // a computer takes the seat so the table keeps playing
        val taken = s.copy(seats = s.seats.mapIndexed { i, st -> if (i == seat) st.copy(isBot = true) else st })
        commit(PresidentFlow.resolveBotGifts(taken, difficulty))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(PresidentNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(s.copy(seats = s.seats.mapIndexed { i, st -> if (i == seat) st.copy(isBot = false) else st }))
    }

    private fun broadcast() {
        val s = state.value ?: return
        val hostId = context.players[0].playerId
        context.players.drop(1).filter { !it.isBot }.forEach { sendStateTo(it.playerId, s, hostId) }
        spectatorIds.toList().forEach { sendStateTo(it, s, hostId) }
    }

    private fun sendStateTo(recipientId: String, s: PresidentState, hostId: String = context.players[0].playerId) {
        val seated = context.players.any { it.playerId == recipientId }
        val view = presidentRedactFor(s, if (seated) recipientId else null)
        val payload = psWireJson.encodeToString(PresidentNetMessage.serializer(), PresidentNetMessage.StateSync(stateVersion, view))
            .toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = recipientId, payload = payload)
    }

    private fun sendIntent(intent: PresidentIntent) {
        if (isSpectator) return
        sendToHost(PresidentNetMessage.Intent(intent))
    }

    private fun sendToHost(message: PresidentNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = if (isSpectator) PS_SPECTATOR_SENDER else context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = psWireJson.encodeToString(PresidentNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            psWireJson.decodeFromString(PresidentNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        when (message) {
            is PresidentNetMessage.StateSync ->
                if (!isHost && fromPlayerId == context.players[0].playerId) applyRemoteState(message.version, message.state)
            is PresidentNetMessage.RequestState -> if (isHost) {
                val seated = context.players.any { it.playerId == fromPlayerId }
                if (!seated && (fromPlayerId in spectatorIds || spectatorIds.size < PS_MAX_SPECTATORS)) spectatorIds.add(fromPlayerId)
                if (seated || fromPlayerId in spectatorIds) state.value?.let { sendStateTo(fromPlayerId, it) }
            }
            is PresidentNetMessage.Intent -> if (isHost) applyIntent(fromPlayerId, message.intent)
        }
    }

    private fun applyIntent(fromPlayerId: String, intent: PresidentIntent) {
        val s = state.value ?: return
        val seat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        if (seat <= 0 || matchOver.value) return // a watcher, an unknown id, or the host's own seat: never via the wire
        if (s.seats[seat].isBot) return
        when (intent) {
            is PresidentIntent.Play -> if (seat == s.currentSeat) doPlay(seat, intent.cardIds, fromHuman = false)
            is PresidentIntent.Pass -> if (seat == s.currentSeat) doPass(seat, fromHuman = false)
            is PresidentIntent.Give -> if (s.pendingExchanges.any { it.from == seat }) {
                PresidentFlow.give(s, seat, intent.cardIds)?.let { commit(PresidentFlow.resolveBotGifts(it, difficulty)) }
            }
            is PresidentIntent.Continue -> if (s.roundOver) doContinue()
        }
    }

    private fun applyRemoteState(version: Int, remote: PresidentState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!PresidentValidator.isValid(remote, context.players, allowHidden = true)) return
        lastAppliedRemoteVersion = version
        state.value = remote
        hint.value = null
    }
}
