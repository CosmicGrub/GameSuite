package com.gamesuite.games.sevens

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
private data class SvSnapshot(val roster: List<String>, val state: SevensState, val difficulty: String)

private val svSnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val svWireJson = Json { ignoreUnknownKeys = true }

/**
 * Sevens (Fan Tan) for 3 to 6 seats. The seven of diamonds opens; every other card is built up or down
 * from the sevens of its suit; with nothing to play you pass (optionally for a penalty); the first hand
 * to empty wins the round and the others score the points left in their hands.
 *
 * Networking, undo and hints work exactly as in [com.gamesuite.games.president.PresidentGame]: seat 0 is
 * the authority, guests hold only their own hand, watchers hold none, undo and hints are offline only.
 * Rounds at one table keep a running total; leaving records the rounds won.
 */
class SevensGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "sevens"
    override val displayName = "Sevens"
    override val category = GameCategory.CARD
    override val minPlayers = 3
    override val maxPlayers = 6
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<SevensState?>(null)

    var rules: SevensRuleSet = SevensRuleSet()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    /** The card (or null = pass) the last hint suggested; [hintShown] tells "no hint" from "pass". */
    val hintCard = mutableStateOf<Int?>(null)
    val hintShown = mutableStateOf(false)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val history = SnapshotHistory()
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private val spectatorIds = LinkedHashSet<String>()
    private var roundsFinished = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    val isSpectator: Boolean get() = isNetworked && context.localPlayerIndex < 0
    val networked: Boolean get() = isNetworked
    val localSeat: Int get() = if (this::context.isInitialized) context.localPlayerIndex else 0

    override fun init(context: GameContext) {
        require(SevensRules.validSeatCount(context.players.size)) { "Sevens needs 3 to 6 seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        clearHint()
        history.clear()
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        roundsFinished = 0
        spectatorIds.clear()
        if (isNetworked) registerNetworkListeners()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    private fun clearHint() {
        hintCard.value = null
        hintShown.value = false
    }

    // ---- queries ------------------------------------------------------------------------------

    fun isMyTurn(s: SevensState): Boolean {
        if (matchOver.value || s.phase != SvPhase.PLAY || isSpectator) return false
        if (s.seats[s.currentSeat].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentSeat == context.localPlayerIndex else true
    }

    /** The seat whose hand this device shows: the local seat online, else the person on turn. */
    fun viewSeat(s: SevensState): Int {
        if (isNetworked) return context.localPlayerIndex
        if (s.phase == SvPhase.PLAY && !s.seats[s.currentSeat].isBot) return s.currentSeat
        return s.seats.indexOfFirst { !it.isBot }.coerceAtLeast(0)
    }

    fun humanSeatCount(): Int = if (this::context.isInitialized) context.players.count { !it.isBot } else 0

    fun explainPlay(cardId: Int): SevensReject {
        val s = state.value ?: return SevensReject.NOT_STARTED
        if (isSpectator) return SevensReject.SPECTATOR
        if (matchOver.value || s.phase != SvPhase.PLAY) return SevensReject.WRONG_PHASE
        if (!isMyTurn(s)) return SevensReject.NOT_YOUR_TURN
        return SevensRules.explainPlay(cardId, s.seats[s.currentSeat].hand, s.low, s.high)
    }

    /** Whether the person on turn has a card they can play (if so, passing is not allowed). */
    fun canPlayAnything(s: SevensState): Boolean =
        SevensRules.playableCards(s.seats[s.currentSeat].hand, s.low, s.high).isNotEmpty()

    fun explainPass(): SevensReject {
        val s = state.value ?: return SevensReject.NOT_STARTED
        if (isSpectator) return SevensReject.SPECTATOR
        if (matchOver.value || s.phase != SvPhase.PLAY) return SevensReject.WRONG_PHASE
        if (!isMyTurn(s)) return SevensReject.NOT_YOUR_TURN
        return if (canPlayAnything(s)) SevensReject.MUST_PLAY_IF_ABLE else SevensReject.NONE
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private fun roster(): List<SvSeat> = context.players.map { SvSeat(it.playerId, it.displayName, it.isBot) }

    override fun startMatch() {
        if (isNetworked && !isHost) return
        history.clear()
        clearHint()
        matchOver.value = false
        commit(SevensFlow.newTable(roster(), rules, random))
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Leaves the table: rounds finished so far are recorded (the player with most round wins takes it). */
    fun leaveSession() {
        if (matchOver.value) return
        if (roundsFinished == 0 && state.value?.roundOver != true) {
            abortMatch()
            return
        }
        val wins = state.value?.wins ?: emptyMap()
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        endMatch(GameResult(scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
        }))
    }

    // ---- actions of the local player ----------------------------------------------------------

    fun tryPlay(cardId: Int): SevensReject {
        val why = explainPlay(cardId)
        if (why != SevensReject.NONE) return why
        if (isNetworked && !isHost) {
            sendIntent(SevensIntent.Play(cardId))
            return SevensReject.NONE
        }
        val s = state.value ?: return SevensReject.NOT_STARTED
        doPlay(s.currentSeat, cardId, fromHuman = true)
        return SevensReject.NONE
    }

    fun tryPass(): SevensReject {
        val why = explainPass()
        if (why != SevensReject.NONE) return why
        if (isNetworked && !isHost) {
            sendIntent(SevensIntent.Pass)
            return SevensReject.NONE
        }
        val s = state.value ?: return SevensReject.NOT_STARTED
        doPass(s.currentSeat, fromHuman = true)
        return SevensReject.NONE
    }

    fun continueSession() {
        val s = state.value ?: return
        if (matchOver.value || isSpectator || !s.roundOver) return
        if (isNetworked && !isHost) {
            if (!hostLeft.value) sendIntent(SevensIntent.Continue)
            return
        }
        doContinue()
    }

    private fun doContinue() {
        val s = state.value ?: return
        val next = SevensFlow.nextRound(s, random) ?: return
        history.clear()
        clearHint()
        commit(next)
    }

    private fun doPlay(seat: Int, cardId: Int, fromHuman: Boolean) {
        val s = state.value ?: return
        val next = SevensFlow.play(s, seat, cardId) ?: return
        if (fromHuman && !isNetworked) captureSnapshot()?.let { history.push(it) }
        clearHint()
        commit(next)
    }

    private fun doPass(seat: Int, fromHuman: Boolean) {
        val s = state.value ?: return
        val next = SevensFlow.pass(s, seat) ?: return
        if (fromHuman && !isNetworked) captureSnapshot()?.let { history.push(it) }
        clearHint()
        commit(next)
    }

    // ---- computer players ---------------------------------------------------------------------

    fun botToMove(s: SevensState): Boolean {
        if (matchOver.value || s.phase != SvPhase.PLAY) return false
        if (isNetworked && !isHost) return false
        return s.seats[s.currentSeat].isBot
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val seat = s.currentSeat
        val choice = withContext(Dispatchers.Default) { SevensBot.choose(s, seat, difficulty, random) }
        if (state.value !== s || matchOver.value) return
        applyBot(seat, choice)
    }

    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        applyBot(s.currentSeat, SevensBot.choose(s, s.currentSeat, difficulty, random))
    }

    private fun applyBot(seat: Int, choice: Int?) {
        if (choice == null) doPass(seat, fromHuman = false) else doPlay(seat, choice, fromHuman = false)
    }

    // ---- undo / hint --------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decode(previous) ?: return
        state.value = snap.state
        clearHint()
    }

    private fun hintAllowed(s: SevensState) = !isNetworked && !matchOver.value && isMyTurn(s)

    suspend fun requestHint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val found = withContext(Dispatchers.Default) { SevensBot.hint(s, s.currentSeat) }
        if (state.value !== s || matchOver.value) return null
        hintCard.value = found
        hintShown.value = true
        return found
    }

    fun hintNow(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return SevensBot.hint(s, s.currentSeat).also { hintCard.value = it; hintShown.value = true }
    }

    // ---- resume -------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return try {
            svSnapshotJson.encodeToString(SvSnapshot.serializer(), SvSnapshot(context.players.map { it.playerId }, s, difficulty.name))
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(text: String): SvSnapshot? = try {
        val snap = svSnapshotJson.decodeFromString(SvSnapshot.serializer(), text)
        if (snap.roster == context.players.map { it.playerId } && SevensValidator.isValid(snap.state, context.players, allowHidden = false)) snap else null
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
        clearHint()
        history.clear()
        roundsFinished = snap.state.wins.values.sum()
        return true
    }

    // ---- networking ---------------------------------------------------------------------------

    private fun commit(newState: SevensState) {
        state.value = newState
        if (newState.phase == SvPhase.ROUND_OVER) roundsFinished += 1
        if (isNetworked && isHost) {
            stateVersion++
            broadcast()
        }
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(SevensNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcast() else sendToHost(SevensNetMessage.RequestState) }
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
        commit(s.copy(seats = s.seats.mapIndexed { i, st -> if (i == seat) st.copy(isBot = true) else st }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(SevensNetMessage.RequestState)
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

    private fun sendStateTo(recipientId: String, s: SevensState, hostId: String = context.players[0].playerId) {
        val seated = context.players.any { it.playerId == recipientId }
        val view = sevensRedactFor(s, if (seated) recipientId else null)
        val payload = svWireJson.encodeToString(SevensNetMessage.serializer(), SevensNetMessage.StateSync(stateVersion, view))
            .toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = recipientId, payload = payload)
    }

    private fun sendIntent(intent: SevensIntent) {
        if (isSpectator) return
        sendToHost(SevensNetMessage.Intent(intent))
    }

    private fun sendToHost(message: SevensNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = if (isSpectator) SV_SPECTATOR_SENDER else context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = svWireJson.encodeToString(SevensNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            svWireJson.decodeFromString(SevensNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        when (message) {
            is SevensNetMessage.StateSync ->
                if (!isHost && fromPlayerId == context.players[0].playerId) applyRemoteState(message.version, message.state)
            is SevensNetMessage.RequestState -> if (isHost) {
                val seated = context.players.any { it.playerId == fromPlayerId }
                if (!seated && (fromPlayerId in spectatorIds || spectatorIds.size < SV_MAX_SPECTATORS)) spectatorIds.add(fromPlayerId)
                if (seated || fromPlayerId in spectatorIds) state.value?.let { sendStateTo(fromPlayerId, it) }
            }
            is SevensNetMessage.Intent -> if (isHost) applyIntent(fromPlayerId, message.intent)
        }
    }

    private fun applyIntent(fromPlayerId: String, intent: SevensIntent) {
        val s = state.value ?: return
        val seat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        if (seat <= 0 || matchOver.value) return
        if (s.seats[seat].isBot) return
        when (intent) {
            is SevensIntent.Play -> if (seat == s.currentSeat) doPlay(seat, intent.cardId, fromHuman = false)
            is SevensIntent.Pass -> if (seat == s.currentSeat) doPass(seat, fromHuman = false)
            is SevensIntent.Continue -> if (s.roundOver) doContinue()
        }
    }

    private fun applyRemoteState(version: Int, remote: SevensState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!SevensValidator.isValid(remote, context.players, allowHidden = true)) return
        lastAppliedRemoteVersion = version
        state.value = remote
        clearHint()
    }
}
