package com.gamesuite.games.pigdice

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** What the last change to the table was; the screen words, animates and announces it. */
object PigEvent {
    const val START = "start"
    const val ROLL = "roll"
    const val BUST = "bust"
    const val SNAKE_EYES = "snake_eyes"
    const val DOUBLES = "doubles"
    const val EXACT = "exact"
    const val HOG = "hog"
    const val HOG_BUST = "hog_bust"
    const val HOLD = "hold"
    const val WIN = "win"
    val ALL = setOf(START, ROLL, BUST, SNAKE_EYES, DOUBLES, EXACT, HOG, HOG_BUST, HOLD, WIN)
}

enum class PigReject { NONE, OVER, NOT_YOUR_TURN, NOTHING_TO_HOLD, HOG_NO_HOLD, BAD_COUNT }

data class PigPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * The whole table. [dice] is the most recent roll (empty before the first); [eventPoints] is what the last
 * event was worth (the points of a roll, the turn total lost on a bust, the amount banked by a hold).
 * [rollSerial] / [turnSerial] only ever grow and key the screen's dice and turn animations.
 */
data class PigState(
    val variant: PigVariant,
    val target: Int,
    val players: List<PigPlayerState>,
    val scores: List<Int>,
    val currentPlayerIndex: Int,
    val turnTotal: Int = 0,
    val turnRolls: Int = 0,
    val dice: List<Int> = emptyList(),
    val event: String = PigEvent.START,
    val eventSeat: Int = 0,
    val eventPoints: Int = 0,
    val rollSerial: Int = 0,
    val turnSerial: Int = 0,
    val over: Boolean = false,
    val winnerPlayerId: String? = null
)

@Serializable
private data class PigSnapshot(
    val variant: String,
    val target: Int,
    val players: List<PigNetPlayer>,
    val scores: List<Int>,
    val currentPlayerIndex: Int,
    val turnTotal: Int,
    val turnRolls: Int,
    val dice: List<Int>,
    val event: String,
    val eventSeat: Int,
    val eventPoints: Int,
    val rollSerial: Int,
    val turnSerial: Int,
    val over: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val rollLog: List<Int> = emptyList()
)

private val pigJson = Json { ignoreUnknownKeys = true }

/** What a hint suggests: the move and the chance of winning it gives. */
data class PigHint(val move: PigMove, val winChance: Double)

/**
 * Pig for 2 to 6 seats (see [PigRules] for the variants). Roll or hold; a bust ends the turn.
 *
 * THE DICE come only from the [random] passed to the constructor, one `nextInt(1, 7)` per die, and every die is
 * appended to [rollLog] and saved, so a seeded Random (or the log) replays a game exactly. Over a network only
 * the host rolls; a guest asks. There is deliberately NO undo: taking back a roll would let a player re-roll.
 * The computer players choose with their own generator (never the dice), off the main thread.
 */
class PigGame(private val random: Random = Random.Default) : GameModule, ResumableGame {
    override val gameId = "pig-dice"
    override val displayName = "Pig"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 6
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<PigState?>(null)
    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val hint = mutableStateOf<PigHint?>(null)

    /** Rules for the NEXT game (the Hub's options); a restored save or a host sync carries its own. */
    var variant: PigVariant = PigVariant.CLASSIC
    var target: Int = PigRules.DEFAULT_TARGET
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private val dieLog = ArrayList<Int>()

    /** Every die rolled in the current game, in order. */
    val rollLog: List<Int> get() = dieLog.toList()

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    override fun init(context: GameContext) {
        require(context.players.size in 2..6) { "Pig needs 2 to 6 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex = if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        hint.value = null; hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base(); lastAppliedRemoteVersion = 0
        dieLog.clear()
        if (isNetworked) registerNetworkListeners()
    }

    // ---- queries -----------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: PigState): Boolean {
        if (matchOver.value || s.over) return false
        if (s.players[s.currentPlayerIndex].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentPlayerIndex == context.localPlayerIndex else true
    }

    fun explainRoll(count: Int = 1): PigReject {
        val s = state.value ?: return PigReject.OVER
        if (matchOver.value || s.over) return PigReject.OVER
        if (!isMyTurn(s)) return PigReject.NOT_YOUR_TURN
        return if (PigRules.legalDiceCount(s.variant, count)) PigReject.NONE else PigReject.BAD_COUNT
    }

    fun explainHold(): PigReject {
        val s = state.value ?: return PigReject.OVER
        if (matchOver.value || s.over) return PigReject.OVER
        if (!isMyTurn(s)) return PigReject.NOT_YOUR_TURN
        if (s.variant.singleRoll) return PigReject.HOG_NO_HOLD
        return if (s.turnTotal <= 0) PigReject.NOTHING_TO_HOLD else PigReject.NONE
    }

    // ---- validation shared by resume and the network ------------------------------------------

    private fun validState(
        roster: List<PlayerInfo>, variantId: String, target: Int, players: List<PigNetPlayer>, scores: List<Int>,
        current: Int, turnTotal: Int, turnRolls: Int, dice: List<Int>, event: String, eventSeat: Int,
        eventPoints: Int, rollSerial: Int, turnSerial: Int, over: Boolean, winner: String?
    ): Boolean {
        val n = roster.size
        val v = PigVariant.values().firstOrNull { it.id == variantId } ?: return false
        if (target !in 20..200) return false
        if (players.size != n || scores.size != n) return false
        if (!players.indices.all { players[it].playerId == roster[it].playerId }) return false
        if (current !in 0 until n || eventSeat !in 0 until n) return false
        if (event !in PigEvent.ALL) return false
        if (turnTotal < 0 || turnRolls < 0 || eventPoints < 0 || rollSerial < 0 || turnSerial < 0) return false
        if (scores.any { it < 0 || it > target + 400 }) return false
        if (dice.size > PigRules.HOG_MAX_DICE || dice.any { it !in 1..6 }) return false
        if (dice.isNotEmpty() && !PigRules.legalDiceCount(v, dice.size)) return false
        if (turnTotal > 0 && v.singleRoll) return false
        if (over) {
            val w = roster.indexOfFirst { it.playerId == winner }
            if (w < 0 || scores[w] < target) return false
        } else {
            if (winner != null || scores.any { it >= target } || scores[current] + turnTotal >= target) return false
        }
        return true
    }

    private fun toNet(s: PigState) = PigNetState(
        s.variant.id, s.target, s.players.map { PigNetPlayer(it.playerId, it.displayName, it.isBot) }, s.scores,
        s.currentPlayerIndex, s.turnTotal, s.turnRolls, s.dice, s.event, s.eventSeat, s.eventPoints,
        s.rollSerial, s.turnSerial, s.over, s.winnerPlayerId, sessionWins.value
    )

    private fun fromNet(n: PigNetState) = PigState(
        PigVariant.fromId(n.variant), n.target, n.players.map { PigPlayerState(it.playerId, it.displayName, it.isBot) },
        n.scores, n.currentPlayerIndex, n.turnTotal, n.turnRolls, n.dice, n.event, n.eventSeat, n.eventPoints,
        n.rollSerial, n.turnSerial, n.over, n.winnerPlayerId
    )

    private fun validNet(n: PigNetState): Boolean =
        validState(
            context.players, n.variant, n.target, n.players, n.scores, n.currentPlayerIndex, n.turnTotal, n.turnRolls,
            n.dice, n.event, n.eventSeat, n.eventPoints, n.rollSerial, n.turnSerial, n.over, n.winnerPlayerId
        ) && n.sessionWins.all { (id, w) -> w >= 0 && context.players.any { it.playerId == id } }

    // ---- resume ------------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        val s = state.value
        if (s == null || matchOver.value) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else {
            val n = toNet(s)
            pigJson.encodeToString(
                PigSnapshot.serializer(),
                PigSnapshot(
                    n.variant, n.target, n.players, n.scores, n.currentPlayerIndex, n.turnTotal, n.turnRolls, n.dice,
                    n.event, n.eventSeat, n.eventPoints, n.rollSerial, n.turnSerial, n.over, n.winnerPlayerId,
                    sessionWins.value, nextStartingPlayerIndex, difficulty.name, dieLog.toList()
                )
            )
        }
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean = try {
        val p = pigJson.decodeFromString(PigSnapshot.serializer(), snapshot)
        val roster = context.players
        val ok = validState(
            roster, p.variant, p.target, p.players, p.scores, p.currentPlayerIndex, p.turnTotal, p.turnRolls, p.dice,
            p.event, p.eventSeat, p.eventPoints, p.rollSerial, p.turnSerial, p.over, p.winnerPlayerId
        ) && p.players.indices.all { p.players[it].isBot == roster[it].isBot } &&
            p.nextStartingPlayerIndex in roster.indices &&
            p.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            p.rollLog.all { it in 1..6 } &&
            context.activeMode != PlayMode.LOCAL_AD_HOC && context.activeMode != PlayMode.ONLINE
        if (!ok) false else {
            matchOver.value = false
            state.value = fromNet(
                PigNetState(
                    p.variant, p.target, p.players, p.scores, p.currentPlayerIndex, p.turnTotal, p.turnRolls, p.dice,
                    p.event, p.eventSeat, p.eventPoints, p.rollSerial, p.turnSerial, p.over, p.winnerPlayerId, p.sessionWins
                )
            )
            sessionWins.value = p.sessionWins
            nextStartingPlayerIndex = p.nextStartingPlayerIndex
            dieLog.clear(); dieLog.addAll(p.rollLog)
            variant = PigVariant.fromId(p.variant); target = p.target
            difficulty = CpuDifficulty.values().firstOrNull { it.name == p.difficulty } ?: difficulty
            hint.value = null
            true
        }
    } catch (_: Exception) {
        false
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val players = context.players.map { PigPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        hint.value = null
        dieLog.clear()
        val starter = nextStartingPlayerIndex % players.size
        commit(
            PigState(
                variant = variant, target = target, players = players, scores = List(players.size) { 0 },
                currentPlayerIndex = starter, eventSeat = starter
            )
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        endMatch(
            GameResult(
                scores = context.players.map {
                    val w = wins[it.playerId] ?: 0
                    PlayerScore(it.playerId, w, isWinner = best > 0 && w == best && leaders == 1)
                }
            )
        )
    }

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.over != true) return
            if (isHost) startMatch() else sendToHost(PigNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // ---- rolling and holding -----------------------------------------------------------------

    /** The local player's roll of [count] dice (the count only matters in Hog). True when accepted. */
    fun roll(count: Int = state.value?.variant?.diceCount ?: 1): Boolean {
        if (explainRoll(count) != PigReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(PigNetMessage.RollRequest(count)); return true }
        return doRoll(count)
    }

    fun hold(): Boolean {
        if (explainHold() != PigReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(PigNetMessage.HoldRequest); return true }
        return doHold()
    }

    private fun nextSeat(s: PigState) = (s.currentPlayerIndex + 1) % s.players.size

    private fun doRoll(count: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || !PigRules.legalDiceCount(s.variant, count)) return false
        val dice = List(count) { random.nextInt(1, 7) }
        dieLog += dice
        hint.value = null
        val seat = s.currentPlayerIndex
        val r = PigRules.evaluate(s.variant, dice, s.turnTotal)
        val base = s.copy(dice = dice, rollSerial = s.rollSerial + 1, eventSeat = seat, turnRolls = s.turnRolls + 1)
        val next: PigState = if (r.bust) {
            base.copy(
                event = if (r.note == PigRules.Note.HOG_BUST) PigEvent.HOG_BUST else PigEvent.BUST,
                eventPoints = s.turnTotal, turnTotal = 0, turnRolls = 0,
                currentPlayerIndex = nextSeat(s), turnSerial = s.turnSerial + 1
            )
        } else {
            val total = s.turnTotal + r.points
            val banked = s.scores[seat] + total
            val rollEvent = when (r.note) {
                PigRules.Note.SNAKE_EYES -> PigEvent.SNAKE_EYES
                PigRules.Note.DOUBLES -> PigEvent.DOUBLES
                PigRules.Note.EXACT -> PigEvent.EXACT
                PigRules.Note.HOG_SAFE -> PigEvent.HOG
                else -> PigEvent.ROLL
            }
            when {
                banked >= s.target -> {
                    sessionWins.value = sessionWins.value + (s.players[seat].playerId to (sessionWins.value[s.players[seat].playerId] ?: 0) + 1)
                    base.copy(
                        scores = s.scores.mapIndexed { i, v -> if (i == seat) banked else v },
                        turnTotal = 0, event = PigEvent.WIN, eventPoints = total, over = true,
                        winnerPlayerId = s.players[seat].playerId
                    )
                }
                r.autoBank -> base.copy(
                    scores = s.scores.mapIndexed { i, v -> if (i == seat) banked else v },
                    turnTotal = 0, turnRolls = 0, event = rollEvent, eventPoints = total,
                    currentPlayerIndex = nextSeat(s), turnSerial = s.turnSerial + 1
                )
                else -> base.copy(turnTotal = total, event = rollEvent, eventPoints = r.points)
            }
        }
        commit(next)
        return true
    }

    private fun doHold(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.over || s.variant.singleRoll || s.turnTotal <= 0) return false
        val seat = s.currentPlayerIndex
        hint.value = null
        commit(
            s.copy(
                scores = s.scores.mapIndexed { i, v -> if (i == seat) v + s.turnTotal else v },
                turnTotal = 0, turnRolls = 0, event = PigEvent.HOLD, eventSeat = seat, eventPoints = s.turnTotal,
                currentPlayerIndex = nextSeat(s), turnSerial = s.turnSerial + 1
            )
        )
        return true
    }

    // ---- hints -------------------------------------------------------------------------------

    private fun hintAllowed(s: PigState) = !isNetworked && !matchOver.value && !s.over && !s.players[s.currentPlayerIndex].isBot

    /** The optimal move and its win chance, computed off the main thread. Not offered over a network. */
    suspend fun requestHint(): PigHint? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val (move, chance) = withContext(Dispatchers.Default) {
            PigBot.hint(s.variant, s.target, s.scores, s.currentPlayerIndex, s.turnTotal)
        }
        if (state.value !== s || matchOver.value) return null
        return PigHint(move, chance).also { hint.value = it }
    }

    // ---- computer players --------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: PigState): Boolean {
        if (matchOver.value || s.over) return false
        if (isNetworked && !isHost) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    private fun chooseBot(s: PigState): PigMove = PigBot.choose(
        difficulty, s.variant, s.target, s.scores, s.currentPlayerIndex, s.turnTotal,
        Random(s.rollSerial.toLong() * 7919L + s.turnSerial.toLong() * 31L + s.currentPlayerIndex)
    )

    private fun apply(move: PigMove) {
        when (move) {
            is PigMove.Roll -> doRoll(move.count)
            PigMove.Hold -> doHold()
        }
    }

    /** One computer action (a roll or a hold); the choice is made off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val move = withContext(Dispatchers.Default) { chooseBot(s) }
        if (state.value !== s || matchOver.value) return
        apply(move)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        apply(chooseBot(s))
    }

    // ---- networking --------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(PigNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(PigNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) { if (seat == 0) hostLeft.value = true; return }
        val s = state.value ?: return
        if (seat == 0 || s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(PigNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    private fun commit(newState: PigState) {
        state.value = newState
        if (isNetworked && isHost) { stateVersion++; broadcastState() }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(PigNetMessage.serializer(), PigNetMessage.StateSync(stateVersion, toNet(s))).toByteArray(Charsets.UTF_8)
    }

    private fun broadcastState() {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.players.drop(1).filter { !it.isBot }.forEach { context.transport.send(hostId, it.playerId, payload) }
    }

    private fun sendStateTo(playerId: String) {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(hostId, playerId, payload)
    }

    private fun sendToHost(message: PigNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(PigNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(localId, hostId, payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(PigNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is PigNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemote(message.version, message.state)
            is PigNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is PigNetMessage.RollRequest ->
                if (isHost && s != null && senderSeat > 0 && s.currentPlayerIndex == senderSeat && !s.players[senderSeat].isBot) doRoll(message.count)
            is PigNetMessage.HoldRequest ->
                if (isHost && s != null && senderSeat > 0 && s.currentPlayerIndex == senderSeat && !s.players[senderSeat].isBot) doHold()
            is PigNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.over == true && !matchOver.value) startMatch()
        }
    }

    private fun applyRemote(version: Int, net: PigNetState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!validNet(net)) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, 0, context.players)) return
        lastAppliedRemoteVersion = version
        state.value = fromNet(net)
        sessionWins.value = net.sessionWins
        hint.value = null
    }
}
