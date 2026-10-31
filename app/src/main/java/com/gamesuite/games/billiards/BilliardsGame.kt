package com.gamesuite.games.billiards

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
private data class BilliardsSnap(
    val state: BilliardsState, val difficulty: String, val nextBreaker: Int, val startedAtMs: Long
)

private val billiardsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Pool for two: 8-ball (solids and stripes, then the 8) or 9-ball (lowest ball first, sink the 9), with
 * the rules in [BilliardsRules] and the physics in [BilliardsPhysics]. A stroke is taken with [shoot]: the
 * engine checks it, runs the physics to rest, lets the rules judge it, and publishes the new table with
 * the stroke attached so a screen can replay it. Randomness (the rack order, the computer's aim error)
 * comes only from the injected [random]; [clock] only stamps the rack's start and end.
 *
 * Undo (against the computer and in pass and play, never online): takes the table back to before the
 * human's last stroke. In a networked game only the host holds the table and runs the physics.
 */
class BilliardsGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "billiards"
    override val displayName = "Billiards"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<BilliardsState?>(null)
    val hostLeft = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    /** The stroke the last hint suggested (null until asked, and cleared by the next stroke). */
    val hintShot = mutableStateOf<Shot?>(null)

    var variant: String = BilliardsVariant.EIGHT
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    /** The seat that breaks the next rack (the Hub's "who moves first"). */
    var firstBreaker: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val history = SnapshotHistory()
    private var sessionWins = IntArray(2)
    private var nextBreaker = 0
    private var startedAtMs = 0L
    var finishedAtMs = 0L
        private set
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    val elapsedMs: Long get() = (if (finishedAtMs > 0L) finishedAtMs else clock()) - startedAtMs

    override fun init(context: GameContext) {
        require(context.players.size == 2) { "Billiards needs exactly two seats" }
        this.context = context
        matchOver.value = false
        hostLeft.value = false
        hintShot.value = null
        history.clear()
        sessionWins = IntArray(2)
        nextBreaker = firstBreaker.coerceIn(0, 1)
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        finishedAtMs = 0L
        if (isNetworked) registerNetworkListeners()
    }

    // --- Queries ---------------------------------------------------------------------------

    /** Whether this device's person is the one to shoot now. */
    fun isMyTurn(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != BilliardsPhase.AIM || hostLeft.value) return false
        if (s.seats[s.shooter].isBot) return false
        return if (isNetworked) s.shooter == context.localPlayerIndex else true
    }

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: BilliardsState): Boolean {
        if (matchOver.value || s.phase != BilliardsPhase.AIM) return false
        if (isNetworked && !isHost) return false
        return s.seats[s.shooter].isBot
    }

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    /** Why [shot] would not be accepted right now. */
    fun explainShot(shot: Shot): ShotReject {
        val s = state.value ?: return ShotReject.OVER
        if (matchOver.value || s.phase == BilliardsPhase.OVER) return ShotReject.OVER
        if (!isMyTurn()) return ShotReject.NOT_YOUR_TURN
        return validate(s, shot)
    }

    private fun validate(s: BilliardsState, shot: Shot): ShotReject {
        if (!shot.isFinite) return ShotReject.BAD_SHOT
        if (shot.power < MIN_POWER || shot.power > 1.0) return ShotReject.BAD_POWER
        if (kotlin.math.abs(shot.spinX) > 1.0 || kotlin.math.abs(shot.spinY) > 1.0) return ShotReject.BAD_SHOT
        if (s.ballInHand) {
            if (!BilliardsRules.canPlaceCue(shot.cueX, shot.cueY, s.xs(), s.ys(), s.ons(), s.behindHead)) return ShotReject.BAD_PLACEMENT
        } else if (shot.cueX != s.x[0] || shot.cueY != s.y[0]) {
            return ShotReject.BAD_PLACEMENT
        }
        return ShotReject.NONE
    }

    // --- Lifecycle ---------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        history.clear()
        newRack()
    }

    private fun newRack() {
        val (x, y, on) = BilliardsRules.rack(variant, random)
        val breaker = nextBreaker
        nextBreaker = 1 - nextBreaker
        startedAtMs = clock()
        finishedAtMs = 0L
        hintShot.value = null
        val seats = List(2) { i ->
            val p = context.players[i]
            val bot = state.value?.seats?.getOrNull(i)?.isBot ?: p.isBot
            BilliardsSeat(p.playerId, p.displayName, bot, sessionWins[i])
        }
        commit(
            BilliardsState(
                variant = variant, seats = seats, x = x.toList(), y = y.toList(), onTable = on.toList(),
                shooter = breaker, groups = listOf(0, 0), ballInHand = false, behindHead = false, isBreak = true,
                phase = BilliardsPhase.AIM, winner = -1, winReason = WinReason.NONE, foulStreak = listOf(0, 0),
                lastFouls = emptyList(), lastShot = null, shotNo = 0, serial = (state.value?.serial ?: -1) + 1
            )
        )
    }

    private fun commit(ns: BilliardsState) {
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

    /** Ends the session: the seat with more racks won takes it (equal racks is a draw). */
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
        if (s.phase != BilliardsPhase.OVER) return
        if (isNetworked && !isHost) { sendToHost(BilliardsNetMessage.PlayAgainRequest); return }
        history.clear()
        newRack()
    }

    // --- Playing -----------------------------------------------------------------------------

    /** The person at this device takes [shot]. True when accepted. */
    fun shoot(shot: Shot): Boolean {
        if (explainShot(shot) != ShotReject.NONE) return false
        if (isNetworked && !isHost) { sendToHost(BilliardsNetMessage.ShotRequest(shot)); return true }
        val s = state.value ?: return false
        if (!s.seats[s.shooter].isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        return applyShot(s, shot)
    }

    private fun applyShot(s: BilliardsState, shot: Shot): Boolean {
        if (s.phase != BilliardsPhase.AIM || validate(s, shot) != ShotReject.NONE) return false
        val startX = s.x; val startY = s.y; val startOn = s.onTable
        val sim = BilliardsPhysics.simulate(s.xs(), s.ys(), s.ons(), shot)
        val ctx = RuleContext(s.variant, s.shooter, s.groups, s.ballsOnTable(), s.isBreak, s.foulStreak[s.shooter])
        val verdict = BilliardsRules.judge(ctx, ShotOutcome.of(sim))

        val x = sim.x.copyOf(); val y = sim.y.copyOf(); val on = sim.onTable.copyOf()
        // balls that go back on the foot spot (an 8 sunk on the break, a 9 sunk on a foul)
        for (b in verdict.respot) {
            val (rx, ry) = BilliardsRules.freeSpot(BilliardsTable.FOOT_X, BilliardsTable.MID_Y, x, y, on)
            x[b] = rx; y[b] = ry; on[b] = true
        }
        val groups = s.groups.toMutableList()
        if (verdict.assignedGroup != 0) { groups[s.shooter] = verdict.assignedGroup; groups[1 - s.shooter] = 3 - verdict.assignedGroup }
        val streak = s.foulStreak.toMutableList()
        streak[s.shooter] = if (verdict.foul) minOf(3, streak[s.shooter] + 1) else 0

        // the cue ball: back on the table (ball in hand) when it was pocketed
        var inHand = verdict.ballInHand
        if (inHand && !on[0]) {
            val spot = BilliardsRules.freeSpot(BilliardsTable.HEAD_X, BilliardsTable.MID_Y, x, y, on, verdict.behindHead)
            x[0] = spot.first; y[0] = spot.second; on[0] = true
        } else if (inHand && !BilliardsRules.canPlaceCue(x[0], y[0], x, y, on, verdict.behindHead)) {
            // a fouled cue ball that rests on a spot another ball needs (or too far past the head string) is moved to a legal one
            val spot = BilliardsRules.freeSpot(x[0], y[0], x, y, on, verdict.behindHead)
            x[0] = spot.first; y[0] = spot.second
        }
        on[0] = true

        val record = ShotRecord(
            s.shooter, shot, startX, startY, startOn, verdict.pocketed, sim.firstContact, verdict.fouls.filter { it in FoulKind.ALL }
        )
        val over = verdict.winner >= 0
        if (over) { sessionWins[verdict.winner]++; finishedAtMs = clock() }
        hintShot.value = null
        val seats = s.seats.mapIndexed { i, seat -> seat.copy(wins = sessionWins[i]) }
        commit(
            s.copy(
                seats = seats, x = x.toList(), y = y.toList(), onTable = on.toList(),
                shooter = if (over) s.shooter else verdict.nextShooter, groups = groups,
                ballInHand = inHand && !over, behindHead = verdict.behindHead && !over,
                isBreak = false, phase = if (over) BilliardsPhase.OVER else BilliardsPhase.AIM,
                winner = verdict.winner, winReason = verdict.winReason, foulStreak = streak,
                lastFouls = verdict.fouls.filter { it in FoulKind.ALL }, lastShot = record, shotNo = s.shotNo + 1, serial = s.serial + 1
            )
        )
        return true
    }

    // --- Computer seat and hint ---------------------------------------------------------------

    private fun botShot(s: BilliardsState, level: CpuDifficulty, rnd: Random, exact: Boolean = false): Shot =
        BilliardsBot.chooseShot(BilliardsBot.viewOf(s), level, rnd, exact)

    /** One computer stroke, chosen off the main thread, then applied. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val level = difficulty
        val seed = s.serial * 31L + s.shotNo
        val shot = withContext(Dispatchers.Default) { botShot(s, level, Random(seed + random.nextLong())) }
        if (state.value !== s || matchOver.value) return
        applyShot(s, shot)
    }

    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        applyShot(s, botShot(s, difficulty, random))
    }

    private fun hintAllowed(s: BilliardsState) =
        !isNetworked && !matchOver.value && s.phase == BilliardsPhase.AIM && !s.seats[s.shooter].isBot

    /** A suggested stroke, found by the Hard computer's search off the main thread. */
    suspend fun requestHint(): Shot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val shot = withContext(Dispatchers.Default) { botShot(s, CpuDifficulty.HARD, Random(s.serial * 17L + 5), exact = true) }
        if (state.value !== s || matchOver.value) return null
        hintShot.value = shot
        return shot
    }

    /** Same as [requestHint] but on the calling thread (tests). */
    fun hint(): Shot? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return botShot(s, CpuDifficulty.HARD, Random(s.serial * 17L + 5), exact = true).also { hintShot.value = it }
    }

    // --- Resume and undo ---------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return billiardsJson.encodeToString(BilliardsSnap.serializer(), BilliardsSnap(s, difficulty.name, nextBreaker, startedAtMs))
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
        return try {
            val snap = billiardsJson.decodeFromString(BilliardsSnap.serializer(), snapshot)
            val s = snap.state
            if (!s.isValid(context.players) || snap.nextBreaker !in 0..1) return false
            if (s.seats.indices.any { s.seats[it].isBot != context.players[it].isBot }) return false
            matchOver.value = false
            variant = s.variant
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == snap.difficulty } ?: difficulty
            sessionWins = IntArray(2) { s.seats[it].wins }
            nextBreaker = snap.nextBreaker
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
        val snap = try { billiardsJson.decodeFromString(BilliardsSnap.serializer(), previous) } catch (_: Exception) { return }
        val s = snap.state
        sessionWins = IntArray(2) { s.seats[it].wins }
        nextBreaker = snap.nextBreaker
        startedAtMs = snap.startedAtMs
        finishedAtMs = 0L
        hintShot.value = null
        state.value = s.copy(serial = (state.value?.serial ?: s.serial) + 1, lastShot = null)
    }

    // --- Networking --------------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(BilliardsNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(BilliardsNetMessage.RequestState) }
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
            if (seat == 0 && hostLeft.value) { hostLeft.value = false; sendToHost(BilliardsNetMessage.RequestState) }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.seats[seat].isBot) return
        commit(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == seat) x.copy(isBot = false) else x }))
    }

    private fun encode(m: BilliardsNetMessage) =
        billiardsJson.encodeToString(BilliardsNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun sendStateTo(seat: Int) {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val to = context.players.getOrNull(seat)?.playerId ?: return
        context.transport.send(hostId, to, encode(BilliardsNetMessage.StateSync(stateVersion, s)))
    }

    private fun broadcastState() {
        if (!context.players[1].isBot) sendStateTo(1)
    }

    private fun sendToHost(message: BilliardsNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(localId, hostId, encode(message))
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            billiardsJson.decodeFromString(BilliardsNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        val human = isHost && s != null && senderSeat == 1 && !s.seats[senderSeat].isBot && !matchOver.value
        when (message) {
            is BilliardsNetMessage.StateSync -> if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is BilliardsNetMessage.RequestState -> if (isHost && senderSeat == 1) sendStateTo(senderSeat)
            is BilliardsNetMessage.ShotRequest ->
                if (human && s != null && s.phase == BilliardsPhase.AIM && s.shooter == senderSeat) applyShot(s, message.shot)
            is BilliardsNetMessage.PlayAgainRequest ->
                if (human && s?.phase == BilliardsPhase.OVER) newRack()
        }
    }

    private fun applyRemoteState(version: Int, net: BilliardsState) {
        if (version <= lastAppliedRemoteVersion) return
        if (!net.isValid(context.players)) return
        lastAppliedRemoteVersion = version
        variant = net.variant
        sessionWins = IntArray(2) { net.seats[it].wins }
        hintShot.value = null
        state.value = net
    }

    companion object {
        const val MIN_POWER = 0.02
    }
}
