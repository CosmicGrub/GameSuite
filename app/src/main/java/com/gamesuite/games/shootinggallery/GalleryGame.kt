package com.gamesuite.games.shootinggallery

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

object GalleryEvent {
    const val START = "start"
    const val TURN = "turn"
    const val ROUND_END = "round_end"
    const val WIN = "win"
    const val DRAW = "draw"
    val ALL = setOf(START, TURN, ROUND_END, WIN, DRAW)
}

enum class GalleryReject { NONE, OVER, NOT_YOUR_TURN, ALREADY_DONE, BAD_LOG }

@Serializable
data class GalleryPlayer(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val total: Int = 0,
    val hits: Int = 0,
    val fired: Int = 0,
    val bestStreak: Int = 0,
    val stars: Int = 0,
    /** One score per finished round. */
    val roundScores: List<Int> = emptyList(),
    /** True once this seat has played its turn of the current round. */
    val done: Boolean = false
)

/** A finished turn: whose, which round, the tap log and the score the host computed from it. */
@Serializable
data class GalleryTurn(val seat: Int, val round: Int, val shots: List<GalleryShot>, val result: TurnResult)

@Serializable
data class GalleryState(
    val seed: Long,
    /** "turns" (one after another) or "race" (two players at once, one device). */
    val format: String,
    val relaxed: Boolean,
    val rounds: Int,
    val round: Int,
    val firstSeat: Int,
    /** The seat on turn (turns format); in a race it is only the first seat not yet done. */
    val current: Int,
    val players: List<GalleryPlayer>,
    val phase: String = GalleryGame.PHASE_PLAY,
    val event: String = GalleryEvent.START,
    val turnSerial: Int = 0,
    val lastTurn: GalleryTurn? = null,
    val winnerPlayerId: String? = null
) {
    val config: RangeConfig get() = RangeConfig(seed, round.coerceAtMost(rounds - 1), relaxed)

    /** Seats in the order they shoot within a round. */
    fun order(): List<Int> = List(players.size) { (firstSeat + it) % players.size }
}

@Serializable
private data class GallerySnapshot(
    val state: GalleryState,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val activeMs: Long = 0
)

private val galleryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Shooting Gallery for 1 to 4 shooters. Every player gets the SAME range each round (its targets are a
 * function of the match seed, the round and the pace), so the contest is skill, not luck. Players
 * take turns, or two players race side by side on one device. After the last round the highest total
 * wins (ties go to more hits, then it is a draw).
 *
 * Why there is no Undo: taking a turn back would let a player replay a range they have already seen.
 * Relaxed mode (slower, bigger targets, a longer turn and a faster reload) is the non-timing
 * alternative for players who cannot tap quickly.
 */
class GalleryGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : GameModule, ResumableGame {
    override val gameId = "shooting-gallery"
    override val displayName = "Shooting Gallery"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<GalleryState?>(null)

    /** Rules for the NEXT match (the Hub's options); a restored save or a host sync carries its own. */
    var format: String = FORMAT_TURNS
    var relaxed: Boolean = false
    var rounds: Int = 3
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)
    val durationMs = mutableStateOf(0L)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var startedAt = 0L
    private var carriedMs = 0L

    override fun init(context: GameContext) {
        require(context.players.size in 1..4) { "Shooting Gallery needs 1 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(GalleryNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(GalleryNetMessage.RequestState) }
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
                sendToHost(GalleryNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    // --- Queries ---------------------------------------------------------------------------

    val isRace: Boolean get() = state.value?.format == FORMAT_RACE

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    /** True when the local person may start shooting now (turns format; in a race use [raceSeatsDue]). */
    fun isMyTurn(s: GalleryState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER || s.format != FORMAT_TURNS) return false
        if (s.players[s.current].isBot) return false
        return if (isNetworked) !hostLeft.value && s.current == context.localPlayerIndex else true
    }

    /** Race: the human seats that have not shot this round. */
    fun raceSeatsDue(s: GalleryState): List<Int> =
        if (s.format != FORMAT_RACE || s.phase != PHASE_PLAY || matchOver.value) emptyList()
        else s.players.indices.filter { !s.players[it].done && !s.players[it].isBot }

    fun explainSubmit(seat: Int? = null): GalleryReject {
        val s = state.value ?: return GalleryReject.OVER
        return when {
            matchOver.value || s.phase == PHASE_OVER -> GalleryReject.OVER
            s.format == FORMAT_RACE -> if (seat != null && s.players.getOrNull(seat)?.done == false) GalleryReject.NONE else GalleryReject.ALREADY_DONE
            !isMyTurn(s) -> GalleryReject.NOT_YOUR_TURN
            else -> GalleryReject.NONE
        }
    }

    fun rangeFor(s: GalleryState): RangeConfig = s.config

    // --- Resume ----------------------------------------------------------------------------

    private fun activeMs(): Long = carriedMs + (if (startedAt > 0) (clock() - startedAt).coerceAtLeast(0) else 0)

    override fun snapshot(): String? {
        return try {
            val s = state.value
            if (matchOver.value || s == null || s.phase == PHASE_OVER) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            galleryJson.encodeToString(
                GallerySnapshot.serializer(),
                GallerySnapshot(s, sessionWins.value, nextStartingPlayerIndex, difficulty.name, activeMs())
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        if (context.activeMode == com.gamesuite.core.PlayMode.LOCAL_AD_HOC || context.activeMode == com.gamesuite.core.PlayMode.ONLINE) return false // a networked table is the host's, never a saved game
        val snap = try {
            galleryJson.decodeFromString(GallerySnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            return false
        }
        val roster = context.players
        val valid = validState(snap.state, roster.map { it.playerId }, roster.map { it.isBot }) &&
            snap.nextStartingPlayerIndex in roster.indices &&
            snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            snap.activeMs >= 0 && snap.state.phase != PHASE_OVER
        if (!valid) return false
        matchOver.value = false
        state.value = snap.state
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        carriedMs = snap.activeMs
        startedAt = clock()
        format = snap.state.format
        relaxed = snap.state.relaxed
        rounds = snap.state.rounds
        return true
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val n = context.players.size
        val fmt = if (format == FORMAT_RACE && n == 2 && !isNetworked) FORMAT_RACE else FORMAT_TURNS
        val players = context.players.map { GalleryPlayer(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        carriedMs = 0
        startedAt = clock()
        durationMs.value = 0
        val first = nextStartingPlayerIndex % n
        commit(
            GalleryState(
                seed = random.nextLong(), format = fmt, relaxed = relaxed, rounds = rounds.coerceIn(1, GalleryWorld.MAX_ROUNDS),
                round = 0, firstSeat = first, current = first, players = players
            )
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % n
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
            if (isHost) startMatch() else sendToHost(GalleryNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Playing a turn --------------------------------------------------------------------

    /**
     * Hands in the tap log of a finished turn. In the turns format [seat] must be the seat on turn (a
     * guest sends the log to the host); in a race any seat that has not yet shot may submit. True when accepted.
     */
    fun submitTurn(seat: Int, shots: List<GalleryShot>): Boolean {
        val s = state.value ?: return false
        if (explainSubmit(seat) != GalleryReject.NONE) return false
        if (s.format == FORMAT_TURNS && seat != s.current) return false
        if (!GalleryRange.validLog(s.config, shots)) return false
        if (isNetworked && !isHost) {
            sendToHost(GalleryNetMessage.ShotsRequest(shots))
            return true
        }
        return doSubmit(seat, shots)
    }

    private fun doSubmit(seat: Int, shots: List<GalleryShot>): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != PHASE_PLAY) return false
        if (seat !in s.players.indices || s.players[seat].done) return false
        if (s.format == FORMAT_TURNS && seat != s.current) return false
        val result = GalleryRange.play(s.config, shots) ?: return false
        var players = s.players.mapIndexed { i, p ->
            if (i != seat) p else p.copy(
                total = p.total + result.score, hits = p.hits + result.hits, fired = p.fired + result.fired,
                bestStreak = maxOf(p.bestStreak, result.bestStreak), stars = p.stars + result.stars,
                roundScores = p.roundScores + result.score, done = true
            )
        }
        val turn = GalleryTurn(seat, s.round, shots, result)
        var round = s.round
        var phase = PHASE_PLAY
        var event = GalleryEvent.TURN
        var winner: String? = null
        var current = s.current
        if (players.all { it.done }) {
            if (s.round + 1 >= s.rounds) {
                phase = PHASE_OVER
                winner = winnerOf(players)
                event = if (winner != null) GalleryEvent.WIN else GalleryEvent.DRAW
                current = s.firstSeat
            } else {
                round = s.round + 1
                players = players.map { it.copy(done = false) }
                event = GalleryEvent.ROUND_END
                current = s.firstSeat
            }
        } else if (s.format == FORMAT_TURNS) {
            current = s.order().first { !players[it].done }
        } else {
            current = players.indexOfFirst { !it.done }
        }
        if (winner != null) {
            sessionWins.value = sessionWins.value + (winner to ((sessionWins.value[winner] ?: 0) + 1))
        }
        if (phase == PHASE_OVER) durationMs.value = activeMs()
        commit(
            s.copy(
                players = players, round = round, current = current, phase = phase, event = event,
                turnSerial = s.turnSerial + 1, lastTurn = turn, winnerPlayerId = winner
            )
        )
        return true
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: GalleryState): Boolean {
        if (matchOver.value || s.phase == PHASE_OVER || s.format != FORMAT_TURNS) return false
        if (isNetworked && !isHost) return false
        return s.players[s.current].isBot
    }

    /** The tap log the computer in [seat] would produce on this round's range. Deterministic from the seed. */
    fun botLogFor(s: GalleryState, seat: Int): List<GalleryShot> {
        val rng = Random(s.seed xor (s.round.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor ((seat + 1).toLong() * 0x5DEECE66DL))
        return GalleryBot.log(s.config, difficulty, rng)
    }

    /** One computer turn (the log is built off the main thread). */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val log = withContext(Dispatchers.Default) { botLogFor(s, s.current) }
        if (state.value !== s || matchOver.value) return
        doSubmit(s.current, log)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        doSubmit(s.current, botLogFor(s, s.current))
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: GalleryState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(
            GalleryNetMessage.serializer(), GalleryNetMessage.StateSync(stateVersion, s, sessionWins.value)
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

    private fun sendToHost(message: GalleryNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(GalleryNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(GalleryNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is GalleryNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message)
            is GalleryNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is GalleryNetMessage.ShotsRequest ->
                if (isHost && s != null && senderSeat > 0 && s.format == FORMAT_TURNS && s.current == senderSeat &&
                    !s.players[senderSeat].isBot && s.phase == PHASE_PLAY
                ) doSubmit(senderSeat, message.shots)
            is GalleryNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.phase == PHASE_OVER && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(msg: GalleryNetMessage.StateSync) {
        if (msg.version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (!validState(msg.state, roster.map { it.playerId }, null)) return
        if (!msg.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }) return
        lastAppliedRemoteVersion = msg.version
        state.value = msg.state
        sessionWins.value = msg.sessionWins
    }

    companion object {
        const val FORMAT_TURNS = "turns"
        const val FORMAT_RACE = "race"
        const val PHASE_PLAY = "play"
        const val PHASE_OVER = "over"

        /** Highest total; a tie goes to more hits; still tied is a draw (null). */
        fun winnerOf(players: List<GalleryPlayer>): String? {
            val top = players.maxOf { it.total }
            val tied = players.filter { it.total == top }
            if (tied.size == 1) return tied[0].playerId
            val topHits = tied.maxOf { it.hits }
            val still = tied.filter { it.hits == topHits }
            return if (still.size == 1) still[0].playerId else null
        }

        /** The nearest-to-leaving worthwhile target to suggest at time [t]: star, then ring, then duck; never a decoy. */
        fun suggest(run: RangeRun, t: Int): GalleryTarget? =
            run.targets.filter { it.spawnMs <= t && it.aliveAt(t) && !run.isGone(it.id) && it.kind != TargetKind.DECOY && it.xAt(t) in 0.0..GalleryWorld.W.toDouble() }
                .maxWithOrNull(compareBy<GalleryTarget>({ if (it.kind == TargetKind.STAR) 3 else if (it.kind == TargetKind.RING) 2 else 1 }, { -it.exitMs }))

        /**
         * Whether [s] could be a real match for a table of [ids] (and, when known, [bots]). Used for saves
         * and for syncs from the host: anything impossible is refused.
         */
        fun validState(s: GalleryState, ids: List<String>, bots: List<Boolean>?): Boolean {
            val n = ids.size
            if (n !in 1..4 || s.players.size != n || s.current !in 0 until n || s.firstSeat !in 0 until n) return false
            if (s.players.indices.any { s.players[it].playerId != ids[it] }) return false
            if (bots != null && s.players.indices.any { s.players[it].isBot != bots[it] }) return false
            if (s.format != FORMAT_TURNS && s.format != FORMAT_RACE) return false
            if (s.format == FORMAT_RACE && n != 2) return false
            if (s.phase != PHASE_PLAY && s.phase != PHASE_OVER) return false
            if (s.event !in GalleryEvent.ALL || s.turnSerial < 0) return false
            if (s.rounds !in 1..GalleryWorld.MAX_ROUNDS) return false
            val over = s.phase == PHASE_OVER
            if (if (over) s.round != s.rounds - 1 else s.round !in 0 until s.rounds) return false
            for (p in s.players) {
                if (p.total < 0 || p.hits < 0 || p.fired < 0 || p.bestStreak < 0 || p.stars < 0) return false
                if (p.roundScores.any { it < 0 || it > 20_000 } || p.roundScores.sum() != p.total) return false
                val expected = s.round + (if (p.done || over) 1 else 0)
                if (p.roundScores.size != expected) return false
                if (over && !p.done) return false
            }
            if (over) {
                if (s.winnerPlayerId != winnerOf(s.players)) return false
            } else {
                if (s.winnerPlayerId != null) return false
                if (s.format == FORMAT_TURNS) {
                    val due = s.order().firstOrNull { !s.players[it].done } ?: return false
                    if (s.current != due) return false
                } else if (s.players.all { it.done }) return false
            }
            val t = s.lastTurn
            if (t != null) {
                if (t.seat !in 0 until n || t.round !in 0 until s.rounds) return false
                val replay = GalleryRange.play(RangeConfig(s.seed, t.round, s.relaxed), t.shots) ?: return false
                if (replay != t.result) return false
            } else if (s.turnSerial != 0) return false
            return true
        }
    }
}
