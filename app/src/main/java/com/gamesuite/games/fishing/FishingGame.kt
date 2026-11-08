package com.gamesuite.games.fishing

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

enum class FishingReject { NONE, NOT_YOUR_TURN, NOT_NOW, OVER, OPPONENT_LEFT, BAD_INPUT }

enum class FishHintKind { CAST_HERE, WAIT, HOOK_NOW, REEL_IN, EASE_OFF, GET_READY, HOLD_THE_BUTTON }

data class FishingHint(val kind: FishHintKind, val column: Int, val serial: Int)

data class FishingPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

/** What the contest looks like to this device. */
data class FishingState(
    val players: List<FishingPlayer>,
    val lake: String,
    val rounds: Int,
    val relaxed: Boolean,
    val phase: FishPhase,
    val round: Int,
    val turnIndex: Int,
    /** The seat that is fishing now. */
    val seat: Int,
    val phaseTicks: Int,
    /** Fraction 0..1 of the casting time still left. */
    val aimTimeLeft: Double,
    val meter: Double,
    val aimCol: Int,
    val band: Int,
    val power: Double,
    val tension: Double,
    val distance: Double,
    val startDistance: Double,
    val sweetLo: Double,
    val sweetHi: Double,
    val rushing: Boolean,
    val rushPower: Int,
    val warning: Boolean,
    val nibbling: Boolean,
    val hold: Boolean,
    val outcome: FishOutcome,
    /** Species of the fish on the line / just lost or caught; null while it is still a secret. */
    val fishId: String?,
    val fishGrams: Int,
    val grams: List<Int>,
    val casts: List<Int>,
    val catches: List<List<FishCatch>>,
    val castSerial: Int, val nibbleSerial: Int, val biteSerial: Int, val hookSerial: Int, val resultSerial: Int, val rushSerial: Int,
    val ranking: List<Int>
) {
    val over: Boolean get() = phase == FishPhase.OVER
    /** True when the fish just brought in is the heaviest of the day so far (for the cheer). */
    fun biggestOf(s: Int): Int = catches[s].maxOfOrNull { it.grams } ?: 0
}

@Serializable
private data class FishSnapshot(
    val players: List<FishNetPlayer>, val lake: String, val rounds: Int, val relaxed: Boolean, val difficulty: String,
    val round: Int, val turnIndex: Int, val casts: List<Int>, val grams: List<Int>, val catches: List<List<FishNetCatch>>,
    val sessionWins: List<Int>
)

private val fishJson = Json { ignoreUnknownKeys = true }

/**
 * Fishing: a casual contest, 1 to 4 anglers taking turns for a set number of rounds, one cast each
 * per round. Cast with a hold-and-release swing (the meter rises and falls; the farther out, the
 * bigger and rarer the fish), wait for a bite, hook it with a press inside the bite window, then reel
 * with tension control: hold to reel, let go to ease the line. The score is the total weight.
 *
 * The whole control is one button and an aim, so it can be played with a switch, and the relaxed
 * assist widens every window. Host-authoritative over the network: the angler's [setHold] / [setAim]
 * intents go to the host; the host streams snapshots.
 */
class FishingGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "fishing"
    override val displayName = "Fishing"
    override val category = GameCategory.ARCADE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE
    )

    val state = mutableStateOf<FishingState?>(null)
    val sessionWins = mutableStateOf<List<Int>>(emptyList())
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<FishingHint?>(null)
    val lastReject = mutableStateOf<Pair<FishingReject, Int>?>(null)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var lake: String = "pond"
    var rounds: Int = 3
    var relaxed: Boolean = false
    var cpuReactionScale: Double = 1.0
    internal val cpuLevels = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this // one monitor for @Synchronized entry points and network callbacks alike

    private var engine = FishingEngine("pond", 2, 3, false, random)
    private var bots = HashMap<Int, FishingBot>()
    private var humanHold = false
    private var started = false
    private var finished = false
    private var rejectSerial = 0
    private var hintSerial = 0
    private var boundary: String? = null
    private var lastMs = -1L
    private var acc = 0.0
    private var paused = false
    private val gone = HashSet<Int>()

    private val isNetworked: Boolean
        get() = this::context.isInitialized && (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private var needSync = false
    private var syncClock = 0
    private var lastSyncClock = 0
    private val seats: Int get() = context.players.size

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false; opponentLeft.value = false
            sessionWins.value = List(seats) { 0 }
            hint.value = null; lastReject.value = null; state.value = null
            normalizeConfig()
            buildEngine()
            started = false; finished = false; boundary = null; humanHold = false
            gone.clear()
            stateVersion = com.gamesuite.core.SyncEpoch.base(); lastAppliedRemoteVersion = 0; needSync = false; syncClock = 0; lastSyncClock = 0
            lastMs = -1; acc = 0.0; paused = false
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun normalizeConfig() {
        lake = FishingLakes.lakeId(lake)
        if (rounds !in FishingRules.ROUND_CHOICES) rounds = 3
    }

    private fun buildEngine() {
        engine = FishingEngine(lake, seats.coerceIn(1, FishingRules.MAX_SEATS), rounds, relaxed, random)
        bots = HashMap()
        humanHold = false
    }

    private fun botFor(seat: Int): FishingBot = bots.getOrPut(seat) { FishingBot({ cpuLevels[seat] ?: difficulty }, random, { cpuReactionScale }) }

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        normalizeConfig()
        buildEngine()
        started = true; finished = false; hint.value = null
        needSync = true
        markBoundary()
        publish()
        if (isNetworked) broadcast()
    }

    @Synchronized override fun pause() { paused = true }
    @Synchronized override fun resume() { paused = false; lastMs = -1 }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session: each seat scored by total grams of the last contest; the strict leader of the session wins. */
    fun leaveSession() {
        if (matchOver.value) return
        val w = sessionWins.value
        val best = w.maxOrNull() ?: 0
        val leaders = w.count { it == best }
        val scores = context.players.mapIndexed { seat, pl ->
            PlayerScore(pl.playerId, engine.grams.getOrElse(seat) { 0 }, isWinner = best > 0 && leaders == 1 && w.getOrElse(seat) { 0 } == best)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        val s = state.value
        if (s == null || !s.over) return
        if (isNetworked) {
            if (opponentLeft.value) return
            if (isHost) startMatch() else sendToHost(FishingNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // ---- the clock -----------------------------------------------------------------------

    @Synchronized
    fun advance(): Boolean {
        if (state.value == null || matchOver.value || !started) return false
        val now = clock()
        if (lastMs < 0) { lastMs = now; return false }
        val elapsed = (now - lastMs).coerceIn(0L, 250L)
        lastMs = now
        if (paused) return false
        acc += elapsed
        val n = (acc / FishingRules.STEP_MS).toInt()
        if (n <= 0) return false
        acc -= n * FishingRules.STEP_MS
        return runSteps(n)
    }

    /** Test hook: exactly [n] fixed steps. */
    @Synchronized
    internal fun stepTicks(n: Int) {
        if (state.value == null) return
        runSteps(n)
    }

    private fun runSteps(n: Int): Boolean {
        val guest = isNetworked && !isHost
        repeat(n) {
            if (guest) guestStep() else {
                hostStep()
                if (isNetworked) maybeBroadcast()
            }
        }
        publish()
        return true
    }

    private fun turnId(): Int = engine.round * 10 + engine.turnIndex

    private fun hostStep() {
        if (engine.phase == FishPhase.OVER) return
        val seat = engine.seat
        val active = engine.phase != FishPhase.RESULT
        val h = when {
            !active -> false
            context.players[seat].isBot && seat !in gone -> botFor(seat).hold(engine, turnId())
            seat in gone -> false
            else -> humanHold
        }
        val before = engine.phase
        val s0 = engine.castSerial + engine.nibbleSerial + engine.biteSerial + engine.hookSerial + engine.resultSerial + engine.rushSerial
        val turn0 = turnId()
        engine.step(h)
        val s1 = engine.castSerial + engine.nibbleSerial + engine.biteSerial + engine.hookSerial + engine.resultSerial + engine.rushSerial
        if (before != engine.phase || s0 != s1) needSync = true
        if (turnId() != turn0 || (before == FishPhase.RESULT && engine.phase != FishPhase.RESULT)) { humanHold = false; hint.value = null; markBoundary() }
        if (engine.phase == FishPhase.OVER) finish()
    }

    private fun guestStep() {
        if (engine.phase == FishPhase.OVER) return
        val own = engine.seat == context.localPlayerIndex
        engine.stepLocal(if (own) humanHold else engine.hold)
    }

    private fun finish() {
        if (finished) return
        finished = true
        val top = engine.ranking().first()
        val w = sessionWins.value.toMutableList()
        for (s in 0 until engine.seats) if (engine.tied(s, top)) w[s] = w[s] + 1
        sessionWins.value = w
        boundary = null
        hint.value = null
        needSync = true
    }

    // ---- intents -------------------------------------------------------------------------

    private fun humanGate(seat: Int): FishingReject {
        val s = state.value ?: return FishingReject.OVER
        if (matchOver.value || s.over) return FishingReject.OVER
        if (seat !in 0 until engine.seats) return FishingReject.BAD_INPUT
        if (isNetworked) {
            if (seat != context.localPlayerIndex) return FishingReject.NOT_YOUR_TURN
            if (opponentLeft.value) return rejectWith(FishingReject.OPPONENT_LEFT)
        } else if (context.players[seat].isBot) return FishingReject.NOT_YOUR_TURN
        if (seat != engine.seat) return FishingReject.NOT_YOUR_TURN
        return FishingReject.NONE
    }

    /** The angler at [seat] presses ([down]) or releases the button. */
    @Synchronized
    fun setHold(seat: Int, down: Boolean): FishingReject {
        val g = humanGate(seat)
        if (g != FishingReject.NONE) return g
        if (engine.phase == FishPhase.RESULT) return FishingReject.NOT_NOW
        humanHold = down
        if (isNetworked && !isHost) { sendToHost(FishingNetMessage.Hold(down)); publish(); return FishingReject.NONE }
        needSync = true
        publish()
        return FishingReject.NONE
    }

    /** Aim at column [column] (0..4) before casting. */
    @Synchronized
    fun setAim(seat: Int, column: Int): FishingReject {
        val g = humanGate(seat)
        if (g != FishingReject.NONE) return g
        if (column !in 0 until FishingRules.COLUMNS) return FishingReject.BAD_INPUT
        if (engine.phase != FishPhase.AIM) return FishingReject.NOT_NOW
        if (isNetworked && !isHost) { engine.aimCol = column; sendToHost(FishingNetMessage.Aim(column)); publish(); return FishingReject.NONE }
        engine.aimCol = column
        needSync = true
        publish()
        if (isNetworked) maybeBroadcast()
        return FishingReject.NONE
    }

    private fun rejectWith(r: FishingReject): FishingReject {
        lastReject.value = r to ++rejectSerial
        return r
    }

    /** Advice for the angler on turn (offline). */
    @Synchronized
    fun requestHint(): FishingHint? {
        if (state.value == null || engine.phase == FishPhase.OVER) return null
        val e = engine
        val kind = when (e.phase) {
            FishPhase.AIM -> FishHintKind.CAST_HERE
            FishPhase.CHARGING -> FishHintKind.HOLD_THE_BUTTON
            FishPhase.CASTING, FishPhase.WAITING -> FishHintKind.WAIT
            FishPhase.BITE -> FishHintKind.HOOK_NOW
            FishPhase.REELING -> if (e.rush != null || e.warning || e.tension > e.sweetHi) FishHintKind.EASE_OFF else FishHintKind.REEL_IN
            FishPhase.RESULT -> FishHintKind.GET_READY
            FishPhase.OVER -> FishHintKind.GET_READY
        }
        val col = FishingCpu.bestSpot(e.lake).first
        val h = FishingHint(kind, col, ++hintSerial)
        hint.value = h
        return h
    }

    // ---- state ---------------------------------------------------------------------------

    private fun publish() { state.value = buildState() }

    private fun buildState(): FishingState {
        val e = engine
        val r = e.roll
        val reveal = r != null && (e.phase == FishPhase.REELING || e.phase == FishPhase.RESULT)
        val rushNow = e.rush
        return FishingState(
            players = context.players.map { FishingPlayer(it.playerId, it.displayName, it.isBot) },
            lake = e.lake, rounds = e.rounds, relaxed = e.relaxed, phase = e.phase, round = minOf(e.round, e.rounds - 1),
            turnIndex = e.turnIndex, seat = e.seat, phaseTicks = e.phaseTicks,
            aimTimeLeft = if (e.phase == FishPhase.AIM || e.phase == FishPhase.CHARGING) (1.0 - e.turnTicks.toDouble() / e.aimTimeout).coerceIn(0.0, 1.0) else 1.0,
            meter = e.meter, aimCol = e.aimCol, band = e.band, power = e.power, tension = e.tension, distance = e.distance,
            startDistance = FishingRules.distanceOf(e.band), sweetLo = e.sweetLo, sweetHi = e.sweetHi,
            rushing = rushNow != null, rushPower = rushNow?.power ?: 0, warning = e.warning, nibbling = e.isNibbling,
            hold = e.hold || (e.seat == context.localPlayerIndex && humanHold), outcome = e.outcome,
            fishId = if (reveal) r!!.species.id else null, fishGrams = if (reveal) r!!.grams else 0,
            grams = e.grams.toList(), casts = e.casts.toList(), catches = e.catches.map { it.toList() },
            castSerial = e.castSerial, nibbleSerial = e.nibbleSerial, biteSerial = e.biteSerial, hookSerial = e.hookSerial,
            resultSerial = e.resultSerial, rushSerial = e.rushSerial, ranking = e.ranking()
        )
    }

    internal fun engineForTest(): FishingEngine = engine
    internal fun fingerprint(): Long = synchronized(lock) { engine.fingerprint() }

    // ---- resume --------------------------------------------------------------------------

    private fun markBoundary() {
        boundary = try {
            if (!isNetworked && engine.phase != FishPhase.OVER && this::context.isInitialized) captureBoundary() else null
        } catch (_: Exception) { null }
    }

    private fun captureBoundary(): String = fishJson.encodeToString(
        FishSnapshot.serializer(),
        FishSnapshot(
            players = context.players.map { FishNetPlayer(it.playerId, it.displayName, it.isBot) },
            lake = lake, rounds = rounds, relaxed = relaxed, difficulty = difficulty.name,
            round = engine.round, turnIndex = engine.turnIndex, casts = engine.casts.toList(), grams = engine.grams.toList(),
            catches = engine.catches.map { l -> l.map { FishNetCatch(it.speciesId, it.grams) } }, sessionWins = sessionWins.value
        )
    )

    /** The start of the turn in progress (a cast in the water is not worth saving), or null. */
    override fun snapshot(): String? = synchronized(lock) {
        if (matchOver.value || !started || finished || state.value == null) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else boundary
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        return try {
            val s = fishJson.decodeFromString(FishSnapshot.serializer(), snapshot)
            val roster = context.players
            val n = roster.size.coerceIn(1, FishingRules.MAX_SEATS)
            val ok = s.lake in FishingLakes.lakeIds && s.rounds in FishingRules.ROUND_CHOICES &&
                s.players.size == roster.size &&
                s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
                s.round in 0 until s.rounds && s.turnIndex in 0 until n &&
                s.casts.size == n && s.casts.all { it in 0..s.rounds } && s.grams.size == n && s.grams.all { it in 0..2_000_000 } &&
                s.catches.size == n && s.catches.all { l -> l.size <= s.rounds && l.all { c -> catchValid(c) } } &&
                s.grams.indices.all { i -> s.grams[i] == s.catches[i].sumOf { it.grams } } &&
                s.sessionWins.size == roster.size && s.sessionWins.all { it >= 0 }
            if (!ok) return false
            lake = s.lake; rounds = s.rounds; relaxed = s.relaxed
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
            buildEngine()
            engine.round = s.round; engine.turnIndex = s.turnIndex
            for (i in 0 until n) {
                engine.casts[i] = s.casts[i]; engine.grams[i] = s.grams[i]
                s.catches[i].forEach { engine.catches[i].add(FishCatch(it.species, it.grams)) }
            }
            sessionWins.value = s.sessionWins
            matchOver.value = false; started = true; finished = false
            lastMs = -1; hint.value = null
            markBoundary()
            publish()
            true
        } catch (_: Exception) { false }
    }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(FishingNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) { needSync = true; broadcast() } else sendToHost(FishingNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun recomputeOpponentLeft() {
        val others = (0 until seats).filter { it != context.localPlayerIndex && !context.players[it].isBot }
        opponentLeft.value = others.isNotEmpty() && others.all { it in gone }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0 || seat == context.localPlayerIndex) return
        gone += seat
        if (!isHost && seat == 0) opponentLeft.value = true else recomputeOpponentLeft()
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        gone -= seat
        recomputeOpponentLeft()
        if (isHost && state.value != null) { needSync = true; broadcast() }
    }

    private fun toNetState(): FishNetState {
        val e = engine
        val r = e.roll
        val reveal = r != null && (e.phase == FishPhase.REELING || e.phase == FishPhase.RESULT)
        return FishNetState(
            players = context.players.map { FishNetPlayer(it.playerId, it.displayName, it.isBot) },
            lake = e.lake, rounds = e.rounds, relaxed = e.relaxed, phase = e.phase.name, round = e.round, turnIndex = e.turnIndex,
            phaseTicks = e.phaseTicks, turnTicks = e.turnTicks, chargeTicks = e.chargeTicks, aimCol = e.aimCol, band = e.band,
            power = e.power, tension = e.tension, distance = e.distance, reelTicks = e.reelTicks, slackTicks = e.slackTicks,
            hold = e.hold, outcome = e.outcome.name, casts = e.casts.toList(), grams = e.grams.toList(),
            catches = e.catches.map { l -> l.map { FishNetCatch(it.speciesId, it.grams) } },
            hooked = if (reveal) FishNetCatch(r!!.species.id, r.grams) else null,
            rushes = if (reveal) r!!.rushes.map { FishNetRush(it.start, it.len, it.power) } else emptyList(),
            castSerial = e.castSerial, nibbleSerial = e.nibbleSerial, biteSerial = e.biteSerial, hookSerial = e.hookSerial,
            resultSerial = e.resultSerial, rushSerial = e.rushSerial, sessionWins = sessionWins.value, gone = gone.toList()
        )
    }

    private fun maybeBroadcast() {
        if (state.value == null) return
        syncClock++
        val fast = engine.phase == FishPhase.REELING || engine.phase == FishPhase.CHARGING
        val every = if (fast) 3 else 12
        if (needSync || syncClock - lastSyncClock >= every) broadcast()
    }

    private fun broadcast() {
        if (state.value == null || !isHost) return
        needSync = false
        lastSyncClock = syncClock
        stateVersion++
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = fishJson.encodeToString(
            FishingNetMessage.serializer(), FishingNetMessage.StateSync(stateVersion, toNetState())
        ).toByteArray(Charsets.UTF_8)
        for ((seat, p) in context.players.withIndex()) {
            if (seat == 0 || p.isBot || seat in gone) continue
            context.transport.send(fromPlayerId = hostId, toPlayerId = p.playerId, payload = payload)
        }
    }

    private fun sendToHost(message: FishingNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = fishJson.encodeToString(FishingNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            fishJson.decodeFromString(FishingNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) { return }
        synchronized(lock) {
            val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
            when (message) {
                is FishingNetMessage.StateSync -> if (!isHost && senderSeat == 0) applyRemoteState(message.version, message.state)
                is FishingNetMessage.RequestState -> if (isHost && senderSeat > 0 && state.value != null) { needSync = true; broadcast() }
                is FishingNetMessage.Hold ->
                    if (isHost && senderSeat > 0 && state.value != null && !matchOver.value && !context.players[senderSeat].isBot &&
                        senderSeat == engine.seat && engine.phase != FishPhase.OVER) {
                        humanHold = message.down
                    }
                is FishingNetMessage.Aim ->
                    if (isHost && senderSeat > 0 && state.value != null && !matchOver.value && !context.players[senderSeat].isBot &&
                        senderSeat == engine.seat && engine.phase == FishPhase.AIM && message.column in 0 until FishingRules.COLUMNS) {
                        engine.aimCol = message.column; needSync = true; publish(); maybeBroadcast()
                    }
                is FishingNetMessage.RequestPlayAgain ->
                    if (isHost && senderSeat > 0 && state.value?.over == true && !matchOver.value && !opponentLeft.value) startMatch()
            }
        }
    }

    private fun catchValid(c: FishNetCatch): Boolean = FishingLakes.speciesById(c.species)?.let { c.grams in it.minG..it.maxG } == true

    /** Guest: adopts a strictly newer, well-formed host snapshot. */
    private fun applyRemoteState(version: Int, net: FishNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size.coerceIn(1, FishingRules.MAX_SEATS)
        val ph = FishPhase.entries.firstOrNull { it.name == net.phase }
        val out = FishOutcome.entries.firstOrNull { it.name == net.outcome }
        val maxTicks = 60 * 60 * 10
        val species = net.hooked?.let { FishingLakes.speciesById(it.species) }
        val valid = ph != null && out != null && net.lake in FishingLakes.lakeIds && net.rounds in FishingRules.ROUND_CHOICES &&
            net.players.size == roster.size && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.round in 0..net.rounds && net.turnIndex in 0 until n && (ph == FishPhase.OVER || net.round < net.rounds) &&
            net.phaseTicks in 0..maxTicks && net.turnTicks in 0..maxTicks && net.chargeTicks in 0..maxTicks &&
            net.aimCol in 0 until FishingRules.COLUMNS && net.band in 0 until FishingRules.BANDS &&
            net.power.isFinite() && net.power in 0.0..1.0 && net.tension.isFinite() && net.tension in 0.0..1.1 &&
            net.distance.isFinite() && net.distance in 0.0..70.0 && net.reelTicks in 0..maxTicks && net.slackTicks in 0..maxTicks &&
            net.casts.size == n && net.casts.all { it in 0..net.rounds } && net.grams.size == n && net.grams.all { it in 0..2_000_000 } &&
            net.catches.size == n && net.catches.all { l -> l.size <= net.rounds && l.all { c -> catchValid(c) } } &&
            net.grams.indices.all { i -> net.grams[i] == net.catches[i].sumOf { it.grams } } &&
            (net.hooked == null || (species != null && net.hooked.grams in species.minG..species.maxG)) &&
            net.rushes.size <= 12 && net.rushes.all { it.start in 0..maxTicks && it.len in 1..400 && it.power in 0..100 } &&
            net.castSerial >= 0 && net.nibbleSerial >= 0 && net.biteSerial >= 0 && net.hookSerial >= 0 && net.resultSerial >= 0 && net.rushSerial >= 0 &&
            net.sessionWins.size == roster.size && net.sessionWins.all { it in 0..999 } && net.gone.all { it in roster.indices }
        if (!valid) return
        lastAppliedRemoteVersion = version
        val rebuild = engine.lake != net.lake || engine.rounds != net.rounds || engine.seats != n || engine.relaxed != net.relaxed ||
            (engine.phase == FishPhase.OVER && ph != FishPhase.OVER)
        lake = net.lake; rounds = net.rounds; relaxed = net.relaxed
        if (rebuild) buildEngine()
        val e = engine
        e.phase = ph!!; e.round = net.round; e.turnIndex = net.turnIndex; e.phaseTicks = net.phaseTicks; e.turnTicks = net.turnTicks
        e.chargeTicks = net.chargeTicks; e.aimCol = net.aimCol; e.band = net.band; e.power = net.power; e.tension = net.tension
        e.distance = net.distance; e.reelTicks = net.reelTicks; e.slackTicks = net.slackTicks; e.hold = net.hold; e.outcome = out!!
        for (i in 0 until n) {
            e.casts[i] = net.casts[i]; e.grams[i] = net.grams[i]
            e.catches[i].clear(); net.catches[i].forEach { e.catches[i].add(FishCatch(it.species, it.grams)) }
        }
        val idx = species?.let { sp -> FishingLakes.species.indexOf(sp) }
        e.roll = if (idx != null) FishRoll(idx, net.hooked!!.grams, 0, emptyList(), net.rushes.map { FishRush(it.start, it.len, it.power) }) else null
        e.catchOfTurn = null
        e.castSerial = net.castSerial; e.nibbleSerial = net.nibbleSerial; e.biteSerial = net.biteSerial
        e.hookSerial = net.hookSerial; e.resultSerial = net.resultSerial; e.rushSerial = net.rushSerial
        if (e.seat != context.localPlayerIndex) humanHold = false
        sessionWins.value = net.sessionWins
        gone.clear(); gone.addAll(net.gone)
        started = true
        finished = ph == FishPhase.OVER
        if (!(opponentLeft.value && gone.isEmpty())) recomputeOpponentLeft()
        hint.value = null
        publish()
    }
}
