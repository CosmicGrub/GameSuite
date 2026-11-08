package com.gamesuite.games.minigolf

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
private data class GolfSnap(val state: GolfState, val difficulty: String, val startedAtMs: Long)

private val golfJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Mini Golf for 1 to 4 seats on nine original holes (or the first three). Players putt in turn, round and
 * round, skipping anyone who has finished the hole; a hole ends when the ball drops in the cup or the player
 * reaches the stroke limit, and the fewest total strokes wins (a tie is a draw). A putt is taken with [putt]:
 * the engine checks it, runs [GolfPhysics] to rest, scores it (a ball in water costs a penalty stroke and goes
 * back to where it was hit from) and publishes the new state with the putt attached so a screen can replay it.
 * Randomness is only the computer's aim error, from the injected [random]; [clock] only stamps the round.
 *
 * Undo (solo, against the computer, pass and play; never online) takes the course back to before the human's
 * last putt. In a networked game only the host holds the state and runs the physics.
 */
class GolfGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "mini-golf"
    override val displayName = "Mini Golf"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<GolfState?>(null)
    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)
    val hintShot = mutableStateOf<GolfShot?>(null)

    /** The Hub's course and limit options for the NEXT round; a restored save or a host sync carries its own. */
    var holeCount: Int = 9
    var limit: Int = 6
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    /** The seat that putts first on the first hole. */
    var firstSeat: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val history = SnapshotHistory()
    private var sessionWins = IntArray(0)
    private var startedAtMs = 0L
    var finishedAtMs = 0L
        private set
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    val elapsedMs: Long get() = (if (finishedAtMs > 0L) finishedAtMs else clock()) - startedAtMs

    override fun init(context: GameContext) {
        require(context.players.size in 1..4) { "Mini Golf needs 1 to 4 seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        hintShot.value = null
        history.clear()
        sessionWins = IntArray(context.players.size)
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        finishedAtMs = 0L
        if (isNetworked) registerNetworkListeners()
    }

    // --- Queries ---------------------------------------------------------------------------

    val currentHole: GolfHole? get() = state.value?.let { GolfCourse.holes[it.hole] }

    fun isMyTurn(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != GolfPhase.AIM || hostLeft.value) return false
        if (s.seats[s.current].isBot) return false
        return if (isNetworked) s.current == context.localPlayerIndex else true
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: GolfState): Boolean {
        if (matchOver.value || s.phase != GolfPhase.AIM) return false
        if (isNetworked && !isHost) return false
        return s.seats[s.current].isBot
    }

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    fun explainPutt(shot: GolfShot): GolfReject {
        val s = state.value ?: return GolfReject.OVER
        if (matchOver.value || s.phase == GolfPhase.OVER) return GolfReject.OVER
        if (!isMyTurn()) return GolfReject.NOT_YOUR_TURN
        return validate(shot)
    }

    private fun validate(shot: GolfShot): GolfReject {
        if (!shot.isFinite) return GolfReject.BAD_SHOT
        if (shot.power < MIN_POWER || shot.power > 1.0) return GolfReject.BAD_POWER
        return GolfReject.NONE
    }

    // --- Lifecycle ---------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        history.clear()
        newRound()
    }

    private fun newRound() {
        val n = context.players.size
        val h = if (holeCount == 3) 3 else 9
        val tee = GolfCourse.holes[0].tee
        val first = firstSeat.coerceIn(0, n - 1)
        startedAtMs = clock()
        finishedAtMs = 0L
        hintShot.value = null
        val seats = List(n) { i ->
            val p = context.players[i]
            val bot = state.value?.seats?.getOrNull(i)?.isBot ?: p.isBot
            GolfSeat(p.playerId, p.displayName, bot, sessionWins[i])
        }
        commit(
            GolfState(
                seats = seats, holeCount = h, limit = limit.coerceIn(3, 12), hole = 0,
                strokes = List(n) { List(h) { 0 } }, done = List(n) { List(h) { false } },
                ballX = List(n) { tee.x }, ballY = List(n) { tee.y },
                current = first, phase = GolfPhase.AIM, last = null, strokeNo = 0, serial = (state.value?.serial ?: -1) + 1
            )
        )
    }

    private fun commit(ns: GolfState) {
        state.value = ns
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

    /** Ends the session: the seat with the most rounds won takes it (equal is a draw; a solo round counts when it was a win). */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.toList()
        val best = wins.maxOrNull() ?: 0
        val leaders = wins.count { it == best }
        val scores = context.players.mapIndexed { i, p ->
            PlayerScore(p.playerId, wins[i], isWinner = best > 0 && wins[i] == best && (leaders == 1 || wins.size == 1))
        }
        endMatch(GameResult(scores = scores))
    }

    fun playAgain() {
        if (matchOver.value || hostLeft.value) return
        val s = state.value ?: return
        if (s.phase != GolfPhase.OVER) return
        if (isNetworked && !isHost) { sendToHost(GolfNetMessage.PlayAgainRequest); return }
        history.clear()
        newRound()
    }

    /** Moves on after a finished hole (or ends the round after the last one). */
    fun nextHole() {
        val s = state.value ?: return
        if (matchOver.value || hostLeft.value || s.phase != GolfPhase.HOLE_DONE) return
        if (isNetworked && !isHost) { sendToHost(GolfNetMessage.NextHoleRequest); return }
        applyNextHole(s)
    }

    private fun applyNextHole(s: GolfState) {
        if (s.phase != GolfPhase.HOLE_DONE) return
        if (!s.seats.all { it.isBot } && !isNetworked) captureSnapshot()?.let { history.push(it) }
        if (s.hole == s.holeCount - 1) {
            val totals = s.seats.indices.map { s.total(it) }
            val winners = GolfRules.winners(totals, s.parTotal, s.holeCount)
            if (winners.isNotEmpty() && s.seats.size > 1) sessionWins[winners[0]]++
            else if (winners.isNotEmpty()) sessionWins[0]++
            finishedAtMs = clock()
            hintShot.value = null
            commit(s.copy(seats = s.seats.mapIndexed { i, x -> x.copy(wins = sessionWins[i]) }, phase = GolfPhase.OVER, serial = s.serial + 1))
            return
        }
        val nh = s.hole + 1
        val tee = GolfCourse.holes[nh].tee
        val starter = GolfRules.honor(s.seats.indices.map { s.strokes[it][s.hole] })
        hintShot.value = null
        commit(
            s.copy(
                hole = nh, ballX = s.seats.map { tee.x }, ballY = s.seats.map { tee.y },
                current = starter, phase = GolfPhase.AIM, last = null, serial = s.serial + 1
            )
        )
    }

    // --- Playing -----------------------------------------------------------------------------

    /** The person at this device putts. True when accepted. */
    fun putt(shot: GolfShot): Boolean {
        if (explainPutt(shot) != GolfReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(GolfNetMessage.StrokeRequest(shot)); return true }
        val s = state.value ?: return false
        if (!s.seats[s.current].isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        return applyPutt(s, shot)
    }

    private fun applyPutt(s: GolfState, shot: GolfShot): Boolean {
        if (s.phase != GolfPhase.AIM || validate(shot) != GolfReject.NONE) return false
        val hole = GolfCourse.holes[s.hole]
        val seat = s.current
        val sx = s.ballX[seat]; val sy = s.ballY[seat]
        val sim = GolfPhysics.simulate(hole, sx, sy, shot)
        val penalty = if (sim.end == GolfEnd.WATER) 1 else 0
        val before = s.strokes[seat][s.hole]
        val after = GolfRules.addStroke(before, penalty, s.limit)
        val inCup = sim.end == GolfEnd.CUP
        val hitLimit = !inCup && after >= s.limit
        val finished = inCup || hitLimit
        val endX = if (sim.end == GolfEnd.WATER) sx else sim.x
        val endY = if (sim.end == GolfEnd.WATER) sy else sim.y

        val strokes = s.strokes.map { it.toMutableList() }
        strokes[seat][s.hole] = after
        val done = s.done.map { it.toMutableList() }
        if (finished) done[seat][s.hole] = true
        val bx = s.ballX.toMutableList(); val by = s.ballY.toMutableList()
        bx[seat] = endX; by[seat] = endY
        val doneNow = s.seats.indices.map { done[it][s.hole] }
        val next = if (doneNow.all { it }) -1 else GolfRules.nextToPutt(seat, doneNow)
        val record = StrokeRecord(seat, shot, sx, sy, endX, endY, sim.end, penalty, hitLimit)
        hintShot.value = null
        commit(
            s.copy(
                strokes = strokes, done = done, ballX = bx, ballY = by,
                current = if (next >= 0) next else seat,
                phase = if (next < 0) GolfPhase.HOLE_DONE else GolfPhase.AIM,
                last = record, strokeNo = s.strokeNo + 1, serial = s.serial + 1
            )
        )
        return true
    }

    // --- Computer seat and hint --------------------------------------------------------------

    private fun botShot(s: GolfState, level: CpuDifficulty, rnd: Random, exact: Boolean = false): GolfShot =
        GolfBot.chooseShot(GolfCourse.holes[s.hole], s.ballX[s.current], s.ballY[s.current], level, rnd, exact)

    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val level = difficulty
        val shot = withContext(Dispatchers.Default) { botShot(s, level, Random(s.serial * 31L + s.strokeNo + random.nextLong())) }
        if (state.value !== s || matchOver.value) return
        applyPutt(s, shot)
    }

    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        applyPutt(s, botShot(s, difficulty, random))
    }

    private fun hintAllowed(s: GolfState) =
        !isNetworked && !matchOver.value && s.phase == GolfPhase.AIM && !s.seats[s.current].isBot

    suspend fun requestHint(): GolfShot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val shot = withContext(Dispatchers.Default) { botShot(s, CpuDifficulty.HARD, Random(s.serial * 17L + 3), exact = true) }
        if (state.value !== s || matchOver.value) return null
        hintShot.value = shot
        return shot
    }

    fun hint(): GolfShot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return botShot(s, CpuDifficulty.HARD, Random(s.serial * 17L + 3), exact = true).also { hintShot.value = it }
    }

    // --- Resume and undo ---------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return golfJson.encodeToString(GolfSnap.serializer(), GolfSnap(s, difficulty.name, startedAtMs))
    }

    override fun snapshot(): String? {
        return try {
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        if (context.activeMode == com.gamesuite.core.PlayMode.LOCAL_AD_HOC || context.activeMode == com.gamesuite.core.PlayMode.ONLINE) return false // a networked table is the host's, never a saved game
        return try {
            val snap = golfJson.decodeFromString(GolfSnap.serializer(), snapshot)
            val s = snap.state
            if (!s.isValid(context.players)) return false
            if (s.seats.indices.any { s.seats[it].isBot != context.players[it].isBot }) return false
            matchOver.value = false
            holeCount = s.holeCount; limit = s.limit
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
            sessionWins = IntArray(s.seats.size) { s.seats[it].wins }
            startedAtMs = snap.startedAtMs
            finishedAtMs = 0L
            history.clear()
            hintShot.value = null
            state.value = s.copy(serial = maxOf(s.serial, (state.value?.serial ?: -1) + 1))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = try { golfJson.decodeFromString(GolfSnap.serializer(), previous) } catch (_: Exception) { return }
        val s = snap.state
        sessionWins = IntArray(s.seats.size) { s.seats[it].wins }
        startedAtMs = snap.startedAtMs
        finishedAtMs = 0L
        hintShot.value = null
        state.value = s.copy(serial = (state.value?.serial ?: s.serial) + 1, last = null)
    }

    // --- Networking --------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(GolfNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(GolfNetMessage.RequestState) }
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
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(GolfNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == seat) x.copy(isBot = false) else x }))
    }

    private fun encode(m: GolfNetMessage) = golfJson.encodeToString(GolfNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun sendStateTo(seat: Int) {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val to = context.players.getOrNull(seat)?.playerId ?: return
        context.transport.send(hostId, to, encode(GolfNetMessage.StateSync(stateVersion, s)))
    }

    private fun broadcastState() {
        context.players.indices.drop(1).filter { !context.players[it].isBot }.forEach { sendStateTo(it) }
    }

    private fun sendToHost(message: GolfNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(localId, hostId, encode(message))
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            golfJson.decodeFromString(GolfNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        val human = isHost && s != null && senderSeat > 0 && !s.seats[senderSeat].isBot && !matchOver.value
        when (message) {
            is GolfNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is GolfNetMessage.RequestState -> if (isHost && senderSeat > 0) sendStateTo(senderSeat)
            is GolfNetMessage.StrokeRequest ->
                if (human && s != null && s.phase == GolfPhase.AIM && s.current == senderSeat) applyPutt(s, message.shot)
            is GolfNetMessage.NextHoleRequest ->
                if (human && s != null && s.phase == GolfPhase.HOLE_DONE) applyNextHole(s)
            is GolfNetMessage.PlayAgainRequest ->
                if (human && s?.phase == GolfPhase.OVER) newRound()
        }
    }

    private fun applyRemoteState(version: Int, net: GolfState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!net.isValid(context.players)) return
        lastAppliedRemoteVersion = version
        holeCount = net.holeCount; limit = net.limit
        sessionWins = IntArray(net.seats.size) { net.seats[it].wins }
        hintShot.value = null
        state.value = net
    }

    companion object {
        const val MIN_POWER = 0.02
    }
}
