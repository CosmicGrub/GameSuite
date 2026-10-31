package com.gamesuite.games.darts

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

/** What the last change to the board was; the screen words, animates and announces it. */
object DartsEvent {
    const val START = "start"
    const val THROW = "throw"
    const val BUST = "bust"
    const val ONE_EIGHTY = "one_eighty"
    const val WIN = "win"
    const val COLLECT = "collect"
    val ALL = setOf(START, THROW, BUST, ONE_EIGHTY, WIN, COLLECT)
}

enum class DartsReject { NONE, OVER, NOT_YOUR_TURN, COLLECT_FIRST, THROW_FIRST, BAD_AIM }

@Serializable
data class DartsThrow(
    val x: Double, val y: Double, val ring: Ring, val number: Int,
    /** Face value of the dart. */
    val score: Int,
    /** What it added to the player's total (0 for a bust, a dart before double-in, or Cricket). */
    val counted: Int = 0,
    val bust: Boolean = false
) {
    val hit: DartHit get() = DartHit(ring, number)
}

@Serializable
data class DartsPlayerState(
    val playerId: String, val displayName: String, val isBot: Boolean,
    /** X01 points left (0 in Cricket). */
    val remaining: Int,
    /** False until a double has been hit with double-in on. */
    val started: Boolean = true,
    /** Cricket marks (0..3) for 20, 19, 18, 17, 16, 15, bull. */
    val marks: List<Int> = List(Cricket.NUMBERS.size) { 0 },
    val points: Int = 0,
    val darts: Int = 0,
    val totalScored: Int = 0,
    val visits: Int = 0,
    val bestVisit: Int = 0
)

/**
 * Everything on the board. [visit] holds the darts of the visit in progress, or of the visit just
 * finished while [phase] is [DartsGame.PHASE_COLLECT] (the darts stay in the board until collected, so
 * the score can be read). [throwSerial] / [visitSerial] only ever grow and key the screen's animations.
 */
@Serializable
data class DartsState(
    val game: String,
    val doubleOut: Boolean,
    val doubleIn: Boolean,
    val players: List<DartsPlayerState>,
    val current: Int,
    val visit: List<DartsThrow> = emptyList(),
    val visitStart: Int = 0,
    val visitScore: Int = 0,
    val phase: String = DartsGame.PHASE_THROW,
    val event: String = DartsEvent.START,
    val throwSerial: Int = 0,
    val visitSerial: Int = 0,
    val winnerPlayerId: String? = null
) {
    val round: Int get() = visitSerial / players.size.coerceAtLeast(1) + 1
}

@Serializable
private data class DartsSnapshot(
    val state: DartsState,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val assist: String,
    val seed: Long,
    val activeMs: Long = 0
)

private val dartsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Darts for 1 to 4 players: 501 or 301 (count down to exactly zero, optional double-in and
 * double-out) and Cricket (close 20-15 and the bull). Three darts a visit, then the darts are
 * collected and the next player steps up.
 *
 * RANDOMNESS: the scatter of every dart comes from a stream derived only from the match seed (one
 * `nextLong()` of the injected [random] at the start of the match, saved in the snapshot) and the
 * dart's serial number. A seeded Random therefore replays a whole match exactly, and resuming a
 * saved match can never be used to re-throw a dart. For the same reason there is no Undo: taking
 * back a dart would show you where it landed. In a networked match only the host computes landings.
 *
 * Time: [clock] stamps the match so the result card can show how long it lasted (active time only).
 */
class DartsGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : GameModule, ResumableGame {
    override val gameId = "darts"
    override val displayName = "Darts"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<DartsState?>(null)

    /** Rules for the NEXT match (the Hub's options); a restored save or a host sync carries its own. */
    var gameType: String = G501
    var doubleOut: Boolean = true
    var doubleIn: Boolean = false

    /** How steady a person's hand is: "steady", "normal" or "shaky". */
    var assist: String = ASSIST_NORMAL
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    /** The aim point the last hint suggested (millimetres), and its route as scoreboard codes. */
    val hintAim = mutableStateOf<DartsAimPoint?>(null)
    val hintRoute = mutableStateOf<List<String>>(emptyList())

    /** Milliseconds the finished match took (active time). */
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
        require(context.players.size in 1..4) { "Darts needs 1 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        hintAim.value = null
        hintRoute.value = emptyList()
        hostLeft.value = false
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(DartsNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(DartsNetMessage.RequestState) }
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
                sendToHost(DartsNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    // --- Queries ---------------------------------------------------------------------------

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: DartsState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (s.players[s.current].isBot) return false
        return if (isNetworked) !hostLeft.value && s.current == context.localPlayerIndex else true
    }

    /** The scatter (mm) of the hand of the seat [seat]. */
    fun sigmaFor(seat: Int): Double {
        val s = state.value
        val bot = s?.players?.getOrNull(seat)?.isBot ?: false
        return if (bot) DartsBot.sigmaFor(difficulty) else assistSigma(assist)
    }

    fun explainThrow(): DartsReject {
        val s = state.value ?: return DartsReject.OVER
        return when {
            matchOver.value || s.phase == PHASE_OVER -> DartsReject.OVER
            !isMyTurn(s) -> DartsReject.NOT_YOUR_TURN
            s.phase == PHASE_COLLECT -> DartsReject.COLLECT_FIRST
            else -> DartsReject.NONE
        }
    }

    fun explainCollect(): DartsReject {
        val s = state.value ?: return DartsReject.OVER
        return when {
            matchOver.value || s.phase == PHASE_OVER -> DartsReject.OVER
            !isMyTurn(s) -> DartsReject.NOT_YOUR_TURN
            s.phase != PHASE_COLLECT -> DartsReject.THROW_FIRST
            else -> DartsReject.NONE
        }
    }

    /** Checkout route (codes) for the seat on turn, or empty. */
    fun checkoutFor(s: DartsState): List<String> {
        if (s.game == CRICKET || s.phase != PHASE_THROW) return emptyList()
        val p = s.players[s.current]
        if (!p.started) return emptyList()
        return DartsCheckout.route(p.remaining, 3 - s.visit.size, s.doubleOut)?.map { it.code } ?: emptyList()
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
            dartsJson.encodeToString(
                DartsSnapshot.serializer(),
                DartsSnapshot(s, sessionWins.value, nextStartingPlayerIndex, difficulty.name, assist, seed, activeMs())
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val snap = try {
            dartsJson.decodeFromString(DartsSnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            return false
        }
        val roster = context.players
        val valid = validState(snap.state, roster.map { it.playerId }, roster.map { it.isBot }) &&
            snap.nextStartingPlayerIndex in roster.indices &&
            snap.assist in ASSISTS &&
            snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            snap.activeMs >= 0 && snap.state.phase != PHASE_OVER
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
        gameType = snap.state.game
        doubleOut = snap.state.doubleOut
        doubleIn = snap.state.doubleIn
        hintAim.value = null
        hintRoute.value = emptyList()
        return true
    }

    // --- Hint ------------------------------------------------------------------------------

    private fun hintAllowed(s: DartsState) =
        !isNetworked && !matchOver.value && s.phase == PHASE_THROW && !s.players[s.current].isBot

    suspend fun requestHint(): DartsHint? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val sigma = assistSigma(assist)
        val h = withContext(Dispatchers.Default) { DartsBot.hint(s, s.current, sigma) }
        if (state.value !== s || matchOver.value) return null
        hintAim.value = h.aim
        hintRoute.value = h.route
        return h
    }

    fun hint(): DartsHint? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return DartsBot.hint(s, s.current, assistSigma(assist)).also {
            hintAim.value = it.aim
            hintRoute.value = it.route
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val type = if (gameType in GAMES) gameType else G501
        val start = startScore(type)
        val players = context.players.map {
            DartsPlayerState(
                it.playerId, it.displayName, it.isBot, remaining = start,
                started = !(doubleIn && type != CRICKET)
            )
        }
        matchOver.value = false
        hintAim.value = null
        hintRoute.value = emptyList()
        seed = random.nextLong()
        carriedMs = 0
        startedAt = clock()
        durationMs.value = 0
        val starter = nextStartingPlayerIndex % players.size
        commit(
            DartsState(
                game = type, doubleOut = doubleOut, doubleIn = doubleIn && type != CRICKET,
                players = players, current = starter, visitStart = start
            )
        )
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
            if (isHost) startMatch() else sendToHost(DartsNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Throwing --------------------------------------------------------------------------

    /** The local player's throw: aim in millimetres from the bull, flick [power] 0..1. True when accepted. */
    fun throwDart(aimX: Double, aimY: Double, power: Double): Boolean {
        if (!validAim(aimX, aimY, power)) return false
        if (explainThrow() != DartsReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(DartsNetMessage.ThrowRequest(aimX, aimY, power))
            return true
        }
        return doThrow(aimX, aimY, power)
    }

    private fun doThrow(aimX: Double, aimY: Double, power: Double): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != PHASE_THROW) return false
        if (!validAim(aimX, aimY, power)) return false
        val seat = s.current
        val rng = DartsAim.throwRandom(seed, s.throwSerial)
        val (lx, ly) = DartsAim.land(aimX, aimY, power, sigmaFor(seat), rng)
        hintAim.value = null
        hintRoute.value = emptyList()
        return applyHit(s, lx, ly)
    }

    /** Applies a dart that landed at ([lx], [ly]). Separated from the scatter so the rules are testable on their own. */
    internal fun applyHit(s: DartsState, lx: Double, ly: Double): Boolean {
        val seat = s.current
        val hit = DartsBoard.resolve(lx, ly)
        val me = s.players[seat]
        var dart = DartsThrow(lx, ly, hit.ring, hit.number, hit.score)
        var player = me.copy(darts = me.darts + 1)
        var visitScore = s.visitScore
        var event = DartsEvent.THROW
        var phase = PHASE_THROW
        var winner: String? = null
        var players = s.players

        if (s.game == CRICKET) {
            val others = s.players.filterIndexed { i, _ -> i != seat }.map { it.marks }
            val (marks, pts) = Cricket.apply(me.marks, others, hit)
            player = player.copy(marks = marks, points = me.points + pts)
            dart = dart.copy(counted = pts)
            visitScore += pts
            players = players.mapIndexed { i, p -> if (i == seat) player else p }
            val win = players.indices.firstOrNull { i ->
                Cricket.hasWon(players[i].marks, players[i].points, players.filterIndexed { k, _ -> k != i }.map { it.points })
            }
            if (win != null) { winner = players[win].playerId; event = DartsEvent.WIN; phase = PHASE_OVER }
        } else {
            val o = X01.apply(me.remaining, me.started, hit, s.doubleOut, s.doubleIn)
            when {
                o.bust -> {
                    player = player.copy(remaining = s.visitStart, started = o.started)
                    dart = dart.copy(bust = true)
                    visitScore = 0
                    event = DartsEvent.BUST
                    phase = PHASE_COLLECT
                }
                else -> {
                    player = player.copy(remaining = o.remaining, started = o.started)
                    dart = dart.copy(counted = o.counted)
                    visitScore += o.counted
                    if (o.finished) { winner = player.playerId; event = DartsEvent.WIN; phase = PHASE_OVER }
                }
            }
            players = players.mapIndexed { i, p -> if (i == seat) player else p }
        }
        val visit = s.visit + dart
        if (phase == PHASE_THROW && visit.size >= 3) {
            phase = PHASE_COLLECT
            if (s.game != CRICKET && visitScore == X01.MAX_VISIT) event = DartsEvent.ONE_EIGHTY
        }
        if (phase != PHASE_THROW) {
            // the visit is over: bank it in the thrower's stats (a bust banks nothing)
            val banked = if (dart.bust) 0 else visitScore
            val p = players[seat]
            players = players.mapIndexed { i, q ->
                if (i == seat) p.copy(
                    visits = p.visits + 1,
                    totalScored = p.totalScored + (if (s.game == CRICKET) 0 else banked),
                    bestVisit = maxOf(p.bestVisit, if (s.game == CRICKET) visitScore else banked)
                ) else q
            }
        }
        if (winner != null) {
            sessionWins.value = sessionWins.value + (winner to ((sessionWins.value[winner] ?: 0) + 1))
            durationMs.value = activeMs()
        }
        commit(
            s.copy(
                players = players, visit = visit, visitScore = visitScore, phase = phase, event = event,
                throwSerial = s.throwSerial + 1, winnerPlayerId = winner
            )
        )
        return true
    }

    /** The local player pulls the darts and passes the turn. */
    fun collect(): Boolean {
        if (explainCollect() != DartsReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(DartsNetMessage.CollectRequest)
            return true
        }
        return doCollect()
    }

    private fun doCollect(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != PHASE_COLLECT) return false
        val next = (s.current + 1) % s.players.size
        hintAim.value = null
        hintRoute.value = emptyList()
        commit(
            s.copy(
                current = next, visit = emptyList(), visitScore = 0, phase = PHASE_THROW,
                visitStart = s.players[next].remaining, event = DartsEvent.COLLECT, visitSerial = s.visitSerial + 1
            )
        )
        return true
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: DartsState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER) return false
        if (isNetworked && !isHost) return false
        return s.players[s.current].isBot
    }

    private fun botAim(s: DartsState): DartsAimPoint {
        val rng = Random(seed xor (s.throwSerial.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor 0x5DEECE66DL)
        return DartsBot.aim(s, s.current, difficulty, rng)
    }

    /** One bot action: throw, or collect when its visit is done (the planning runs off the main thread). */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (s.phase == PHASE_COLLECT) { doCollect(); return }
        val a = withContext(Dispatchers.Default) { botAim(s) }
        if (state.value !== s || matchOver.value) return
        doThrow(a.x, a.y, a.power)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (s.phase == PHASE_COLLECT) { doCollect(); return }
        val a = botAim(s)
        doThrow(a.x, a.y, a.power)
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: DartsState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(
            DartsNetMessage.serializer(), DartsNetMessage.StateSync(stateVersion, s, sessionWins.value)
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

    private fun sendToHost(message: DartsNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(DartsNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(DartsNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is DartsNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message)
            is DartsNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is DartsNetMessage.ThrowRequest ->
                if (isHost && s != null && senderSeat > 0 && s.current == senderSeat &&
                    !s.players[senderSeat].isBot && s.phase == PHASE_THROW
                ) doThrow(message.aimX, message.aimY, message.power)
            is DartsNetMessage.CollectRequest ->
                if (isHost && s != null && senderSeat > 0 && s.current == senderSeat &&
                    !s.players[senderSeat].isBot && s.phase == PHASE_COLLECT
                ) doCollect()
            is DartsNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.phase == PHASE_OVER && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(msg: DartsNetMessage.StateSync) {
        if (msg.version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (!validState(msg.state, roster.map { it.playerId }, null)) return
        if (!msg.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }) return
        lastAppliedRemoteVersion = msg.version
        state.value = msg.state
        sessionWins.value = msg.sessionWins
        hintAim.value = null
    }

    companion object {
        const val G501 = "501"
        const val G301 = "301"
        const val CRICKET = "cricket"
        val GAMES = setOf(G501, G301, CRICKET)

        const val PHASE_THROW = "throw"
        const val PHASE_COLLECT = "collect"
        const val PHASE_OVER = "over"

        const val ASSIST_STEADY = "steady"
        const val ASSIST_NORMAL = "normal"
        const val ASSIST_SHAKY = "shaky"
        val ASSISTS = setOf(ASSIST_STEADY, ASSIST_NORMAL, ASSIST_SHAKY)

        fun startScore(game: String): Int = when (game) { G301 -> 301; G501 -> 501; else -> 0 }

        fun assistSigma(assist: String): Double = when (assist) {
            ASSIST_STEADY -> DartsAim.SIGMA_STEADY
            ASSIST_SHAKY -> DartsAim.SIGMA_SHAKY
            else -> DartsAim.SIGMA_NORMAL
        }

        private fun validAim(x: Double, y: Double, power: Double): Boolean =
            x.isFinite() && y.isFinite() && power.isFinite() &&
                x in -DartsBoard.MAX_AIM..DartsBoard.MAX_AIM && y in -DartsBoard.MAX_AIM..DartsBoard.MAX_AIM &&
                power in 0.0..1.0

        /**
         * Whether [s] could be a real board for a table of [ids] (and, when known, [bots]). Used for
         * saves and for syncs from the host: anything impossible is refused.
         */
        fun validState(s: DartsState, ids: List<String>, bots: List<Boolean>?): Boolean {
            val n = ids.size
            if (s.game !in GAMES || s.players.size != n || s.current !in 0 until n) return false
            if (s.players.indices.any { s.players[it].playerId != ids[it] }) return false
            if (bots != null && s.players.indices.any { s.players[it].isBot != bots[it] }) return false
            if (s.phase != PHASE_THROW && s.phase != PHASE_COLLECT && s.phase != PHASE_OVER) return false
            if (s.event !in DartsEvent.ALL) return false
            if (s.throwSerial < 0 || s.visitSerial < 0) return false
            if (s.visit.size > 3 || s.visit.any { !it.x.isFinite() || !it.y.isFinite() || it.score < 0 || it.score > 60 }) return false
            val start = startScore(s.game)
            val x01 = s.game != CRICKET
            if (!x01 && (s.doubleIn)) return false
            if (s.visitStart !in 0..start || s.visitScore !in 0..(if (x01) 180 else 10_000)) return false
            for (p in s.players) {
                if (p.marks.size != Cricket.NUMBERS.size || p.marks.any { it !in 0..3 }) return false
                if (p.remaining !in 0..start || (!x01 && p.remaining != 0)) return false
                if (p.points < 0 || p.darts < 0 || p.totalScored < 0 || p.visits < 0 || p.bestVisit < 0) return false
                if (x01 && p.marks.any { it != 0 }) return false
            }
            if ((s.phase == PHASE_OVER) != (s.winnerPlayerId != null)) return false
            if (s.winnerPlayerId != null && ids.none { it == s.winnerPlayerId }) return false
            if (s.phase == PHASE_THROW && s.visit.size >= 3) return false
            if (s.phase == PHASE_COLLECT && s.visit.isEmpty()) return false
            if (s.phase == PHASE_OVER && s.visit.isEmpty()) return false
            if (s.phase == PHASE_OVER) {
                val w = s.players.firstOrNull { it.playerId == s.winnerPlayerId } ?: return false
                if (x01 && w.remaining != 0) return false
                if (!x01 && !Cricket.allClosed(w.marks)) return false
            }
            return true
        }
    }
}
