package com.gamesuite.games.holdem

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

/**
 * Texas Hold'em, play chips only. A tournament: everyone starts with the same stack, blinds can rise, and the
 * last player holding chips wins. 2 to 6 seats: people (pass and play, Nearby, Online) with computer players
 * filling any seats.
 *
 * All rules live in [HoldemEngine]; this class owns the deck-holding engine, resume, undo, the bot driver and the
 * network. NETWORKED PLAY is host-authoritative (roster seat 0 is the host, by the lobby convention): only the host
 * shuffles, deals and validates; guests send an [HoldemNetMessage.Act] and render the redacted state they are
 * sent, so a guest never holds another seat's hole cards or the deck order.
 */
class HoldemGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "texas-holdem"
    override val displayName = "Texas Hold'em"
    override val category = GameCategory.CARD
    override val minPlayers = 2
    override val maxPlayers = 6
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<HoldemState?>(null)

    /** Set before [startMatch]: the Hub's starting chips and blind speed. A restored save or a host sync carries its own. */
    var config: HoldemConfig = HoldemConfig()

    /** Set before [startMatch]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** "balanced", "tight", "loose" or "mixed" (each CPU seat leans differently). */
    var cpuStyle: String = "balanced"

    /** The seat that receives the first dealer button, or -1 for a random seat. */
    var firstButtonSeat: Int = -1

    /** Overrides the Monte-Carlo iteration count of the computer players (tests use small numbers). */
    var equityIterationsOverride: Int? = null

    /** True on a guest whose host has gone. */
    val hostLeft = mutableStateOf(false)

    /** The most recent hint, cleared whenever the table changes. */
    val hint = mutableStateOf<HoldemHint?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var ended = false
    private val engine = HoldemEngine(random)
    private val history = SnapshotHistory(limit = 40)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    /** A device that watches a networked game without a seat: it sees the public table only and can never act. */
    val isSpectator: Boolean get() = isNetworked && context.localPlayerIndex < 0

    private val spectatorIds = LinkedHashSet<String>()
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun init(context: GameContext) {
        require(context.players.size in 2..6) { "Texas Hold'em needs 2 to 6 seats" }
        this.context = context
        ended = false
        history.clear()
        hint.value = null
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        spectatorIds.clear()
        if (isNetworked) registerNetworkListeners()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val button = if (firstButtonSeat in context.players.indices) firstButtonSeat else random.nextInt(context.players.size)
        history.clear()
        hint.value = null
        val fresh = engine.newMatch(context.players, config, button)
        commit(engine.startHand(fresh))
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        if (ended) return
        ended = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the match and scores it: each seat's score is its chip count (chips in front of it included), the last player standing wins. */
    fun leaveSession() {
        if (ended) return
        val s = state.value
        val scores = if (s == null) emptyList() else s.seats.map {
            PlayerScore(playerId = it.playerId, score = it.chips + it.committed, isWinner = it.playerId == s.winnerPlayerId)
        }
        endMatch(GameResult(scores = scores))
    }

    /** After the tournament: a new one with the same table. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        val s = state.value ?: return
        if (!s.matchOver || ended) return
        if (isNetworked) {
            if (hostLeft.value) return
            if (isHost) startMatch() else sendToHost(HoldemNetMessage.RequestNextHand)
            return
        }
        startMatch()
    }

    // ---- views for the screen -----------------------------------------------------------------

    /** What [seat] may do now, or null. Works on a guest too: bets and stacks are public. */
    fun options(seat: Int): HoldemOptions? {
        val s = state.value ?: return null
        return HoldemRules.options(s, seat)
    }

    fun explain(seat: Int, action: HoldemAction): HoldemReject {
        val s = state.value ?: return HoldemReject.NO_HAND
        return HoldemRules.validate(s, seat, action)
    }

    /** The seat this device plays, or -1 for a watcher. */
    val localSeat: Int get() = if (this::context.isInitialized) context.localPlayerIndex else -1

    // ---- acting -------------------------------------------------------------------------------

    /** A human's action for [seat]. On a guest it goes to the host and returns [HoldemReject.NONE] when it looks legal. */
    fun act(seat: Int, action: HoldemAction): HoldemReject {
        if (isSpectator) return HoldemReject.NOT_YOUR_TURN
        if (isNetworked && !isHost) {
            val s = state.value ?: return HoldemReject.NO_HAND
            if (seat != context.localPlayerIndex) return HoldemReject.NOT_YOUR_TURN
            val r = HoldemRules.validate(s, seat, action)
            if (r != HoldemReject.NONE) return r
            sendToHost(HoldemNetMessage.Act(action))
            return HoldemReject.NONE
        }
        return applyAction(seat, action, human = true)
    }

    private fun applyAction(seat: Int, action: HoldemAction, human: Boolean): HoldemReject {
        val s = state.value ?: return HoldemReject.NO_HAND
        val wantsUndo = human && undoAvailableFor(s, seat)
        val before = if (wantsUndo) captureSnapshot() else null
        val step = engine.act(s, seat, action)
        val next = step.state ?: return step.reject
        val clears = clearsHistory(s, next)
        commit(next)
        if (before != null && !clears) history.push(before)
        return HoldemReject.NONE
    }

    /** Deals the next hand (host / local), or asks the host to. Only between hands. */
    fun nextHand() {
        val s = state.value ?: return
        if (!s.handOver || s.matchOver || ended) return
        if (isNetworked && !isHost) {
            if (!hostLeft.value && !isSpectator) sendToHost(HoldemNetMessage.RequestNextHand)
            return
        }
        commit(engine.startHand(s))
    }

    /** While the board runs out, deals the next street (or settles the hand). Host / local only. */
    fun stepRunout() {
        if (isNetworked && !isHost) return
        val s = state.value ?: return
        if (!s.runout || s.handOver) return
        commit(engine.stepRunout(s))
    }

    // ---- computer players ---------------------------------------------------------------------

    fun botToAct(): Boolean = state.value?.let { botToAct(it) } ?: false

    fun botToAct(s: HoldemState): Boolean {
        if (ended || s.handOver || s.matchOver || s.runout || s.toAct !in s.seats.indices) return false
        if (isNetworked && !isHost) return false
        return s.seats[s.toAct].isBot
    }

    private fun levelFor(seat: Int): CpuDifficulty {
        // A person who dropped out of a networked game is played by a middling computer, whatever the table's level.
        val originallyHuman = context.players.getOrNull(seat)?.isBot == false
        return if (originallyHuman) CpuDifficulty.MEDIUM else difficulty
    }

    private fun decideBotAction(s: HoldemState, rng: Random): HoldemAction? {
        val seat = s.toAct
        val v = HoldemBot.view(s, seat) ?: return null
        val level = levelFor(seat)
        val iters = equityIterationsOverride ?: HoldemBot.equityIterations(level)
        val equity = if (iters > 0 && level != CpuDifficulty.EASY) {
            HoldemEquity.estimate(v.hole, v.board, v.opponents, iters, rng)
        } else null
        return HoldemBot.decide(v, level, BotStyle.forSeat(cpuStyle, seat), rng, equity)
    }

    /**
     * Idle-turn enforcement (host of a networked table only): the action played for a human seat that sat on
     * its turn past [com.gamesuite.core.IdleTurnPolicy.TURN_LIMIT_MS] is a check when that is free, else a fold.
     * Null when there is nothing to enforce (local game, guest, no human to act, hand over).
     */
    fun idleTimeoutAction(s: HoldemState): HoldemAction? {
        if (ended || !isNetworked || !isHost) return null
        if (s.handOver || s.matchOver || s.runout || s.toAct !in s.seats.indices || s.toAct == 0) return null
        if (s.seats[s.toAct].isBot) return null
        return if (HoldemRules.validate(s, s.toAct, HoldemAction.CHECK) == HoldemReject.NONE) HoldemAction.CHECK else HoldemAction.FOLD
    }

    /** Plays the idle-turn action for [expected] unless the table has moved on since the timer started. */
    fun applyIdleTimeout(expected: HoldemState): Boolean {
        if (state.value !== expected) return false
        val action = idleTimeoutAction(expected) ?: return false
        return applyAction(expected.toAct, action, human = false) == HoldemReject.NONE
    }

    /** A computer player's chosen action for the table [state] it was chosen against. */
    class BotDecision(val state: HoldemState, val seat: Int, val action: HoldemAction)

    /** Chooses the computer's action for the current turn; the (possibly heavy) equity simulation runs off the main thread. */
    suspend fun computeBotDecision(): BotDecision? {
        val s = state.value ?: return null
        if (!botToAct(s)) return null
        val rng = Random(random.nextLong())
        val action = withContext(Dispatchers.Default) { decideBotAction(s, rng) } ?: return null
        return BotDecision(s, s.toAct, action)
    }

    /** Plays [decision] unless the table has moved on since it was made (an undo, a newer state). */
    fun applyBotDecision(decision: BotDecision): Boolean {
        if (state.value !== decision.state || ended) return false
        return applyAction(decision.seat, decision.action, human = false) == HoldemReject.NONE
    }

    /** One computer action with no pause. */
    suspend fun playBotTurnAsync() {
        val decision = computeBotDecision() ?: return
        applyBotDecision(decision)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToAct(s)) return
        val seat = s.toAct
        val action = decideBotAction(s, random) ?: return
        applyAction(seat, action, human = false)
    }

    // ---- hint ---------------------------------------------------------------------------------

    private fun hintSeat(s: HoldemState): Int {
        if (isNetworked || ended || s.handOver || s.matchOver || s.runout) return -1
        val seat = s.toAct
        return if (seat in s.seats.indices && !s.seats[seat].isBot) seat else -1
    }

    val canHint: Boolean get() = state.value?.let { hintSeat(it) >= 0 } ?: false

    /** Equity and pot-odds advice for the person on turn (off the main thread). Not offered in networked play. */
    suspend fun requestHint(): HoldemHint? {
        val s = state.value ?: return null
        val seat = hintSeat(s)
        if (seat < 0) return null
        val v = HoldemBot.view(s, seat) ?: return null
        val rng = Random(random.nextLong())
        val hint = withContext(Dispatchers.Default) {
            val eq = HoldemEquity.estimate(v.hole, v.board, v.opponents, equityIterationsOverride ?: 1500, rng)
            HoldemBot.hint(v, eq)
        }
        if (state.value !== s) return null
        this.hint.value = hint
        return hint
    }

    // ---- undo ---------------------------------------------------------------------------------

    private fun undoAvailableFor(s: HoldemState, seat: Int): Boolean =
        this::context.isInitialized && context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT && !ended &&
            !s.handOver && !s.matchOver && seat in s.seats.indices && !s.seats[seat].isBot

    /** Never offered online, and never once the next card has been turned: taking back would reveal nothing new, but
     *  it would let a player see a card and then change a bet already made. */
    override val canUndo: Boolean
        get() = history.depth > 0 && !ended && state.value?.let { !it.handOver && !it.matchOver && !it.runout } == true &&
            this::context.isInitialized && context.activeMode == PlayMode.SINGLE_PLAYER_VS_BOT

    override fun undo() {
        if (!canUndo) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap)
    }

    private fun clearsHistory(from: HoldemState, to: HoldemState): Boolean =
        from.handNumber != to.handNumber || from.street != to.street || to.handOver || to.runout

    // ---- resume -------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return try {
            snapshotJson.encodeToString(
                HoldemSnapshot.serializer(),
                HoldemSnapshot(
                    roster = context.players.map { HoldemSnapshotSeat(it.playerId, it.isBot) },
                    state = s, deck = engine.deck, deckPos = engine.deckPos,
                    difficulty = difficulty.name, cpuStyle = cpuStyle
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Null when there is nothing to save: no hand dealt yet, the match over, or a networked mode. Carries the deck ORDER. */
    override fun snapshot(): String? {
        val s = state.value ?: return null
        if (ended || s.matchOver) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return captureSnapshot()
    }

    private fun decodeSnapshot(text: String): HoldemSnapshot? {
        return try {
            val snap = snapshotJson.decodeFromString(HoldemSnapshot.serializer(), text)
            val roster = context.players
            if (snap.roster.size != roster.size) return null
            if (!roster.indices.all { snap.roster[it].playerId == roster[it].playerId && snap.roster[it].isBot == roster[it].isBot }) return null
            if (snap.state.matchOver) return null
            if (!snap.state.seats.indices.all { snap.state.seats[it].isBot == roster[it].isBot }) return null
            if (!HoldemRules.isConsistent(snap.state, roster.map { it.playerId }, snap.deck, snap.deckPos)) return null
            snap
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: HoldemSnapshot) {
        config = snap.state.config
        CpuDifficulty.values().firstOrNull { it.name == snap.difficulty }?.let { difficulty = it }
        cpuStyle = snap.cpuStyle
        engine.restoreDeck(snap.deck, snap.deckPos)
        hint.value = null
        state.value = snap.state
    }

    /** All-or-nothing: every check runs before any field of this engine is touched. */
    override fun restore(snapshot: String): Boolean {
        if (isNetworked) return false
        val snap = decodeSnapshot(snapshot) ?: return false
        applySnapshot(snap)
        history.clear()
        return true
    }

    // ---- committing and the network -----------------------------------------------------------

    private fun commit(newState: HoldemState) {
        val prev = state.value
        if (prev == null || clearsHistory(prev, newState)) history.clear()
        hint.value = null
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(HoldemNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(HoldemNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (spectatorIds.remove(playerId)) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0) hostLeft.value = true
            return
        }
        val s = state.value ?: return
        if (seat == 0 || s.seats[seat].isBot) return
        // A seat that vanished is played by the computer so the table is never stuck waiting for it.
        commit(
            s.copy(
                seats = s.seats.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p },
                event = HoldemEvent.LEFT, eventSeat = seat, eventAmount = 0, eventSerial = s.eventSerial + 1
            )
        )
    }

    private fun handlePlayerJoined(playerId: String) {
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(HoldemNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(
            s.copy(
                seats = s.seats.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p },
                event = HoldemEvent.RETURNED, eventSeat = seat, eventAmount = 0, eventSerial = s.eventSerial + 1
            )
        )
    }

    private fun encode(message: HoldemNetMessage): ByteArray =
        wireJson.encodeToString(HoldemNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    private fun broadcastState() {
        val s = state.value ?: return
        val hostId = context.players[0].playerId
        context.players.forEachIndexed { i, p ->
            if (i != 0 && !p.isBot) {
                context.transport.send(hostId, p.playerId, encode(HoldemNetMessage.StateSync(stateVersion, holdemRedactFor(s, p.playerId))))
            }
        }
        spectatorIds.toList().forEach { sendSpectatorState(it, s) }
    }

    private fun sendSpectatorState(spectatorId: String, s: HoldemState) {
        context.transport.send(context.players[0].playerId, spectatorId, encode(HoldemNetMessage.StateSync(stateVersion, holdemRedactForSpectator(s))))
    }

    private fun sendStateTo(playerId: String) {
        val s = state.value ?: return
        val hostId = context.players[0].playerId
        if (context.players.any { it.playerId == playerId }) {
            context.transport.send(hostId, playerId, encode(HoldemNetMessage.StateSync(stateVersion, holdemRedactFor(s, playerId))))
        } else {
            sendSpectatorState(playerId, s)
        }
    }

    private fun sendToHost(message: HoldemNetMessage) {
        val hostId = context.players[0].playerId
        val localId = if (isSpectator) HOLDEM_SPECTATOR_SENDER else context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(localId, hostId, encode(message))
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            wireJson.decodeFromString(HoldemNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        when (message) {
            is HoldemNetMessage.StateSync ->
                if (!isHost && !ended && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is HoldemNetMessage.RequestState -> {
                if (!isHost) return
                if (senderSeat < 0 && (fromPlayerId in spectatorIds || spectatorIds.size < HOLDEM_MAX_SPECTATORS)) spectatorIds.add(fromPlayerId)
                if (state.value != null) sendStateTo(fromPlayerId)
            }
            is HoldemNetMessage.Act -> {
                val s = state.value ?: return
                if (ended || s.matchOver) return // stale handler after the table closed (review item 23)
                // The acting seat is whoever the transport says sent it; a seat that is being played by the computer ignores people.
                if (isHost && senderSeat > 0 && !s.seats[senderSeat].isBot && s.toAct == senderSeat) {
                    applyAction(senderSeat, message.action, human = true)
                }
            }
            is HoldemNetMessage.RequestNextHand -> {
                val s = state.value ?: return
                if (!isHost || senderSeat < 0 || ended) return
                if (s.matchOver) startMatch() else if (s.handOver) commit(engine.startHand(s))
            }
        }
    }

    private fun applyRemoteState(version: Int, remote: HoldemState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!HoldemRules.isConsistent(remote, context.players.map { it.playerId })) return
        lastAppliedRemoteVersion = version
        hint.value = null
        state.value = remote
    }
}

private val snapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class HoldemSnapshotSeat(val playerId: String, val isBot: Boolean)

/** Everything needed to put a local match back exactly as it was, deck order included. */
@Serializable
private data class HoldemSnapshot(
    val roster: List<HoldemSnapshotSeat>,
    val state: HoldemState,
    val deck: List<Int>,
    val deckPos: Int,
    val difficulty: String,
    val cpuStyle: String
)
