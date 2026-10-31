package com.gamesuite.games.bowling

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

object BowlingEvent {
    const val START = "start"
    const val ROLL = "roll"
    const val STRIKE = "strike"
    const val SPARE = "spare"
    const val GUTTER = "gutter"
    const val MISS = "miss"
    const val FINISH = "finish"
    val ALL = setOf(START, ROLL, STRIKE, SPARE, GUTTER, MISS, FINISH)
}

enum class BowlingReject { NONE, OVER, NOT_YOUR_TURN, BAD_SHOT }

@Serializable
data class BowlingPlayerState(
    val playerId: String, val displayName: String, val isBot: Boolean,
    /** Pins knocked down by each ball so far, in order. */
    val rolls: List<Int> = emptyList()
)

/**
 * The lane. [standing] is the pin mask (bit 0 = pin 1) the NEXT ball faces: a fresh rack once a frame
 * is over. The last ball's story is kept for the screen to play back: [before] is the mask it faced,
 * [knocked] the pins it brought down, [lastShot] the roll as it actually left the hand (after the
 * scatter) and [gutter] whether it went in the gutter. [rollSerial] only ever grows.
 */
@Serializable
data class BowlingState(
    val players: List<BowlingPlayerState>,
    val current: Int,
    val standing: Int = BowlingLane.ALL_PINS,
    val before: Int = BowlingLane.ALL_PINS,
    val knocked: Int = 0,
    val lastShot: BowlShot? = null,
    val gutter: Boolean = false,
    val bumpers: Boolean = false,
    val event: String = BowlingEvent.START,
    val rollSerial: Int = 0,
    /** The seat that bowled the last ball. */
    val roller: Int = 0,
    val phase: String = BowlingGame.PHASE_THROW,
    val winnerPlayerId: String? = null,
    val tie: Boolean = false
) {
    /** The frame (0..9) and ball (0..2) the seat on turn bowls next. */
    val position: Pair<Int, Int>? get() = BowlingRules.next(players[current].rolls)
    fun total(seat: Int): Int = BowlingRules.total(players[seat].rolls)
}

@Serializable
private data class BowlingSnapshot(
    val state: BowlingState,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val assist: String,
    val seed: Long,
    val activeMs: Long = 0
)

private val bowlingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Ten-pin bowling for 1 to 4 players with official scoring. Each ball is a [BowlShot]; the engine
 * scatters it by the roller's steadiness, runs the deterministic lane physics ([BowlingPhysics]) and
 * scores the pins that fell.
 *
 * RANDOMNESS: the scatter of every ball comes from a stream derived only from the match seed (one
 * `nextLong()` of the injected [random] at the start of the match, saved in the snapshot) and the ball's
 * serial, so a seeded match replays exactly and resuming a save can never be used to re-roll. For the
 * same reason there is no Undo. In a networked match only the host rolls the dice, so to speak.
 */
class BowlingGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : GameModule, ResumableGame {
    override val gameId = "bowling"
    override val displayName = "Bowling"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<BowlingState?>(null)

    /** Rules for the NEXT game (the Hub's options); a restored save or a host sync carries its own. */
    var bumpers: Boolean = false

    /** How steady a person's hand is: "steady", "normal" or "shaky". */
    var assist: String = ASSIST_NORMAL
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val hintShot = mutableStateOf<BowlShot?>(null)
    val durationMs = mutableStateOf(0L)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var seed = 0L
    private var startedAt = 0L
    private var carriedMs = 0L

    override fun init(context: GameContext) {
        require(context.players.size in 1..4) { "Bowling needs 1 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        hintShot.value = null
        hostLeft.value = false
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(BowlingNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(BowlingNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0) hostLeft.value = true
            return
        }
        val s = state.value ?: return
        if (seat == 0 || s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(BowlingNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: BowlingState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (s.players[s.current].isBot) return false
        return if (isNetworked) !hostLeft.value && s.current == context.localPlayerIndex else true
    }

    private fun scatterFor(s: BowlingState, seat: Int): BowlingScatter =
        if (s.players[seat].isBot) BowlingBot.scatterFor(difficulty) else assistScatter(assist)

    fun explainRoll(shot: BowlShot? = null): BowlingReject {
        val s = state.value ?: return BowlingReject.OVER
        return when {
            matchOver.value || s.phase == PHASE_OVER -> BowlingReject.OVER
            !isMyTurn(s) -> BowlingReject.NOT_YOUR_TURN
            shot != null && !shot.isValid() -> BowlingReject.BAD_SHOT
            else -> BowlingReject.NONE
        }
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun activeMs(): Long = carriedMs + (if (startedAt > 0) (clock() - startedAt).coerceAtLeast(0) else 0)

    override fun snapshot(): String? {
        return try {
            val s = state.value
            if (matchOver.value || s == null || s.phase == PHASE_OVER) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            bowlingJson.encodeToString(
                BowlingSnapshot.serializer(),
                BowlingSnapshot(s, sessionWins.value, nextStartingPlayerIndex, difficulty.name, assist, seed, activeMs())
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val snap = try {
            bowlingJson.decodeFromString(BowlingSnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            return false
        }
        val roster = context.players
        val valid = validState(snap.state, roster.map { it.playerId }, roster.map { it.isBot }) &&
            snap.state.phase != PHASE_OVER &&
            snap.nextStartingPlayerIndex in roster.indices &&
            snap.assist in ASSISTS &&
            snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            snap.activeMs >= 0
        if (!valid) return false
        matchOver.value = false
        state.value = snap.state
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        assist = snap.assist
        seed = snap.seed
        carriedMs = snap.activeMs
        startedAt = clock()
        bumpers = snap.state.bumpers
        hintShot.value = null
        return true
    }

    // --- Hint ------------------------------------------------------------------------------

    private fun hintAllowed(s: BowlingState) =
        !isNetworked && !matchOver.value && s.phase == PHASE_THROW && !s.players[s.current].isBot

    /** The best shot for the pins standing, found off the main thread. */
    suspend fun requestHint(): BowlShot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = withContext(Dispatchers.Default) { BowlingBot.plan(s.standing, s.bumpers) }
        if (state.value !== s || matchOver.value) return null
        hintShot.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val players = context.players.map { BowlingPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        hintShot.value = null
        seed = random.nextLong()
        carriedMs = 0
        startedAt = clock()
        durationMs.value = 0
        val starter = nextStartingPlayerIndex % players.size
        commit(BowlingState(players = players, current = starter, bumpers = bumpers))
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    override fun pause() {
        if (startedAt > 0) { carriedMs = activeMs(); startedAt = 0 }
    }

    override fun resume() {
        if (startedAt == 0L && state.value != null) startedAt = clock()
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == bestWins }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.phase != PHASE_OVER) return
            if (isHost) startMatch() else sendToHost(BowlingNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Rolling ---------------------------------------------------------------------------

    /** The local player's roll. True when accepted (rolled here, or asked of the host). */
    fun roll(shot: BowlShot): Boolean {
        if (explainRoll(shot) != BowlingReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(BowlingNetMessage.RollRequest(shot.clamped()))
            return true
        }
        return doRoll(shot)
    }

    private fun doRoll(intended: BowlShot): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != PHASE_THROW || !intended.isValid()) return false
        val thrown = scatterFor(s, s.current).apply(intended.clamped(), bowlingRollRandom(seed, s.rollSerial))
        val res = BowlingPhysics.simulate(thrown, s.standing, s.bumpers)
        hintShot.value = null
        return applyOutcome(s, thrown, res.standing, res.gutter)
    }

    /**
     * Scores a ball that left the hand as [thrown] and left [standingAfter] pins standing. Separated
     * from the physics so the scoring and frame flow are testable on their own.
     */
    internal fun applyOutcome(s: BowlingState, thrown: BowlShot, standingAfter: Int, gutter: Boolean): Boolean {
        val seat = s.current
        val p = s.players[seat]
        val (frame, ball) = BowlingRules.next(p.rolls) ?: return false
        val after = standingAfter and s.standing // a ball can only knock down pins that were standing
        val pins = BowlingLane.pinCount(s.standing) - BowlingLane.pinCount(after)
        val rolls = p.rolls + pins
        val freshRack = s.standing == BowlingLane.ALL_PINS
        val event = when {
            after == 0 && freshRack -> BowlingEvent.STRIKE
            after == 0 -> BowlingEvent.SPARE
            pins == 0 && gutter -> BowlingEvent.GUTTER
            pins == 0 -> BowlingEvent.MISS
            else -> BowlingEvent.ROLL
        }
        val players = s.players.mapIndexed { i, q -> if (i == seat) q.copy(rolls = rolls) else q }
        val nx = BowlingRules.next(rolls)
        // the same seat keeps bowling while its frame (or the tenth) is unfinished
        val sameSeat = nx != null && nx.first == frame
        val nextStanding = if (sameSeat && BowlingRules.standingCountFor(rolls) < BowlingRules.PINS) after else BowlingLane.ALL_PINS
        val finished = players.all { BowlingRules.isComplete(it.rolls) }
        var next = s.copy(
            players = players, before = s.standing, knocked = s.standing and after.inv(), standing = nextStanding,
            lastShot = thrown, gutter = gutter && pins == 0, event = event, rollSerial = s.rollSerial + 1, roller = seat
        )
        next = when {
            finished -> {
                val totals = players.map { BowlingRules.total(it.rolls) }
                val top = totals.maxOrNull() ?: 0
                val leaders = totals.count { it == top }
                val winner = if (leaders == 1 && players.size > 1) players[totals.indexOf(top)].playerId
                else if (players.size == 1) players[0].playerId else null
                durationMs.value = activeMs()
                if (winner != null) sessionWins.value = sessionWins.value + (winner to ((sessionWins.value[winner] ?: 0) + 1))
                next.copy(phase = PHASE_OVER, event = BowlingEvent.FINISH, winnerPlayerId = winner, tie = winner == null && players.size > 1)
            }
            sameSeat -> next
            else -> next.copy(current = (seat + 1) % players.size, standing = BowlingLane.ALL_PINS)
        }
        commit(next)
        return true
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: BowlingState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (isNetworked && !isHost) return false
        return s.players[s.current].isBot
    }

    private fun botShot(s: BowlingState): BowlShot {
        val rng = Random(seed xor (s.rollSerial.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor 0x2545F491L)
        return BowlingBot.intended(s.standing, s.bumpers, difficulty, rng)
    }

    /** One bot roll; the planning (many lane simulations) runs off the main thread. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val shot = withContext(Dispatchers.Default) { botShot(s) }
        if (state.value !== s || matchOver.value) return
        doRoll(shot)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        doRoll(botShot(s))
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: BowlingState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(
            BowlingNetMessage.serializer(), BowlingNetMessage.StateSync(stateVersion, s, sessionWins.value)
        ).toByteArray(Charsets.UTF_8)
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

    private fun sendToHost(message: BowlingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(BowlingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(BowlingNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is BowlingNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message)
            is BowlingNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is BowlingNetMessage.RollRequest ->
                if (isHost && s != null && senderSeat > 0 && s.current == senderSeat &&
                    !s.players[senderSeat].isBot && s.phase == PHASE_THROW
                ) doRoll(message.shot)
            is BowlingNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.phase == PHASE_OVER && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(msg: BowlingNetMessage.StateSync) {
        if (msg.version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (!validState(msg.state, roster.map { it.playerId }, null)) return
        if (!msg.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }) return
        lastAppliedRemoteVersion = msg.version
        state.value = msg.state
        sessionWins.value = msg.sessionWins
        hintShot.value = null
    }

    companion object {
        const val PHASE_THROW = "throw"
        const val PHASE_OVER = "over"

        const val ASSIST_STEADY = "steady"
        const val ASSIST_NORMAL = "normal"
        const val ASSIST_SHAKY = "shaky"
        val ASSISTS = setOf(ASSIST_STEADY, ASSIST_NORMAL, ASSIST_SHAKY)

        fun assistScatter(assist: String): BowlingScatter = when (assist) {
            ASSIST_STEADY -> BowlingScatter.STEADY
            ASSIST_SHAKY -> BowlingScatter.SHAKY
            else -> BowlingScatter.NORMAL
        }

        /** Whether [s] could be a real lane for a table of [ids] (and, when known, [bots]). */
        fun validState(s: BowlingState, ids: List<String>, bots: List<Boolean>?): Boolean {
            val n = ids.size
            if (s.players.size != n || s.current !in 0 until n) return false
            if (s.players.indices.any { s.players[it].playerId != ids[it] }) return false
            if (bots != null && s.players.indices.any { s.players[it].isBot != bots[it] }) return false
            if (s.phase != PHASE_THROW && s.phase != PHASE_OVER) return false
            if (s.event !in BowlingEvent.ALL || s.rollSerial < 0 || s.roller !in 0 until n) return false
            if (s.standing !in 0..BowlingLane.ALL_PINS || s.before !in 0..BowlingLane.ALL_PINS) return false
            if (s.knocked !in 0..BowlingLane.ALL_PINS || (s.knocked and s.before.inv()) != 0) return false
            if (s.lastShot != null && !s.lastShot.isValid()) return false
            if (s.players.any { !BowlingRules.isValid(it.rolls) }) return false
            if ((s.phase == PHASE_OVER) != s.players.all { BowlingRules.isComplete(it.rolls) }) return false
            if (s.phase == PHASE_THROW && (s.winnerPlayerId != null || s.tie)) return false
            if (s.phase == PHASE_OVER && n > 1 && (s.winnerPlayerId != null) == s.tie) return false
            if (s.phase == PHASE_OVER && n == 1 && s.winnerPlayerId == null) return false
            if (s.winnerPlayerId != null && ids.none { it == s.winnerPlayerId }) return false
            if (s.phase == PHASE_THROW) {
                val cur = s.players[s.current].rolls
                if (BowlingRules.isComplete(cur)) return false
                if (BowlingLane.pinCount(s.standing) != BowlingRules.standingCountFor(cur)) return false
                // seats bowl in turn, so nobody is more than a frame away from the seat on turn
                fun fc(r: List<Int>) = BowlingRules.next(r)?.first ?: BowlingRules.FRAMES
                val mine = fc(cur)
                if (s.players.any { kotlin.math.abs(fc(it.rolls) - mine) > 1 }) return false
            }
            return true
        }
    }
}
