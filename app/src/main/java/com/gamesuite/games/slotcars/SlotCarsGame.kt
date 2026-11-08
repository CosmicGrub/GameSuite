package com.gamesuite.games.slotcars

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

enum class SlotCarsReject { NONE, NOT_YOUR_CAR, NOT_STEP_MODE, RACE_OVER, OPPONENT_LEFT, BAD_INPUT }

/** What one car looks like to this device. */
data class SlotCarView(
    val seat: Int, val s: Double, val v: Double, val throttle: Boolean, val off: Int, val offS: Double, val offV: Double,
    val lapsDone: Int, val lastLap: Double, val bestLap: Double, val finishTime: Double, val deslots: Int,
    val grip: Double, val place: Int, val ready: Boolean
)

data class SlotHint(val seat: Int, val brake: Boolean, val serial: Int)

data class SlotCarsPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

data class SlotCarsState(
    val players: List<SlotCarsPlayer>,
    val track: String,
    val laps: Int,
    val assist: String,
    val phase: SlotPhase,
    val tick: Int,
    /** 0 outside a countdown, else the ticks left of it (also used for the short pause after a resume). */
    val countdownTicks: Int,
    val cars: List<SlotCarView>,
    val order: List<Int>,
    val stepRemaining: Int,
    val deslotSerial: Int, val lastDeslotCar: Int,
    val lapSerial: Int, val lastLapCar: Int,
    val finishSerial: Int, val lastFinishCar: Int,
    val winner: Int?
) {
    val raceOver: Boolean get() = phase == SlotPhase.OVER
}

@Serializable
private data class SlotSnapshot(
    val players: List<SlotNetPlayer>, val track: String, val laps: Int, val assist: String, val difficulty: String,
    val tick: Int, val cars: List<SlotNetCar>, val plan: List<Boolean>, val sessionWins: List<Int>, val points: List<Int>,
    val deslotSerial: Int = 0, val lapSerial: Int = 0, val finishSerial: Int = 0
)

private val slotJson = Json { ignoreUnknownKeys = true }

/**
 * Slot Cars: top-down racing on an original slot-car track. Each driver controls only the throttle;
 * the skill is braking before a bend, because a car that is too fast in a bend leaves the slot and
 * loses time being put back. 2 to 4 cars, 3 / 5 / 10 laps, three tracks.
 *
 * DETERMINISM. All motion is [SlotCarsSim], a fixed 60 Hz step with no clock and no random numbers.
 * Time comes only from the injected [clock] (consumed in [advance]) and randomness (the computers'
 * misjudgement) only from [random]. Every seat has its own pad, so pass and play is simultaneous.
 *
 * ASSISTS. "slow" runs the whole race at half speed. "step" turns it into a turn-based game: each
 * human sets their throttle plan for the next half second and presses Ready; when all are ready
 * the cars run that step and wait again. A projection shows where each plan ends up.
 *
 * NETWORK: the host (seat 0) runs the only simulation and the computers; guests send [setThrottle]
 * / [setReady] intents and receive versioned snapshots (see [SlotCarsNetMessage]).
 */
class SlotCarsGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "slot-cars"
    override val displayName = "Slot Cars"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE
    )

    val state = mutableStateOf<SlotCarsState?>(null)
    val sessionWins = mutableStateOf<List<Int>>(emptyList())
    val sessionPoints = mutableStateOf<List<Int>>(emptyList())
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<SlotHint?>(null)
    val lastReject = mutableStateOf<Pair<SlotCarsReject, Int>?>(null)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var laps: Int = 3
    var trackId: String = "meadow"
    /** "off", "slow" or "step". */
    var assist: String = "off"
    var cpuReactionScale: Double = 1.0
    internal val cpuLevels = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this // one monitor for @Synchronized entry points and network callbacks alike

    private var sim = SlotCarsSim(SlotTrack.byId("meadow"), 3, 2)
    private var driver = SlotCpu.Driver({ difficulty }, random, 2)
    private var plan = BooleanArray(2)
    private var ready = BooleanArray(2)
    private var stepRemaining = 0
    private var freezeTicks = 0
    private var started = false
    private var winner: Int? = null
    private var rejectSerial = 0
    private var hintSerial = 0
    private var lastMs = -1L
    private var acc = 0.0
    private var paused = false
    private val gone = HashSet<Int>()

    private val isNetworked: Boolean
        get() = this::context.isInitialized && (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private var lastSyncTick = 0
    private var needSync = false
    private var syncClock = 0
    private val seats: Int get() = context.players.size

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false; opponentLeft.value = false
            sessionWins.value = List(seats) { 0 }; sessionPoints.value = List(seats) { 0 }
            hint.value = null; lastReject.value = null; state.value = null
            normalizeConfig()
            buildSim()
            started = false; winner = null
            gone.clear()
            stateVersion = com.gamesuite.core.SyncEpoch.base(); lastAppliedRemoteVersion = 0; lastSyncTick = 0; needSync = false; syncClock = 0
            lastMs = -1; acc = 0.0; paused = false
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun normalizeConfig() {
        if (laps !in SlotCarsRules.LAP_CHOICES) laps = 3
        if (trackId !in SlotTrack.ids) trackId = SlotTrack.ids[0]
        if (assist != "off" && assist != "slow" && assist != "step") assist = "off"
    }

    private fun buildSim() {
        val n = seats.coerceIn(1, SlotCarsRules.MAX_CARS)
        sim = SlotCarsSim(SlotTrack.byId(trackId), laps, n)
        driver = SlotCpu.Driver({ cpuLevels[it] ?: difficulty }, random, n)
        plan = BooleanArray(n); ready = BooleanArray(n)
        stepRemaining = 0; freezeTicks = 0
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        normalizeConfig()
        buildSim()
        started = true; winner = null; hint.value = null
        needSync = true
        publish()
        if (isNetworked) broadcast()
    }

    @Synchronized override fun pause() { paused = true }
    @Synchronized override fun resume() { paused = false; lastMs = -1 }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session: each seat scored by its points; the strict leader in race wins is the winner. */
    fun leaveSession() {
        if (matchOver.value) return
        val w = sessionWins.value
        val p = sessionPoints.value
        val best = w.maxOrNull() ?: 0
        val leaders = w.count { it == best }
        val scores = context.players.mapIndexed { seat, pl ->
            PlayerScore(pl.playerId, p.getOrElse(seat) { 0 }, isWinner = best > 0 && leaders == 1 && w.getOrElse(seat) { 0 } == best)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        val s = state.value
        if (s == null || !s.raceOver) return
        if (isNetworked) {
            if (opponentLeft.value) return
            if (isHost) startMatch() else sendToHost(SlotCarsNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // ---- the clock -----------------------------------------------------------------------

    /** Moves the race on by the real time since the last call (at most a quarter of a second at once). */
    @Synchronized
    fun advance(): Boolean {
        if (state.value == null || matchOver.value || !started) return false
        val now = clock()
        if (lastMs < 0) { lastMs = now; return false }
        val elapsed = (now - lastMs).coerceIn(0L, 250L)
        lastMs = now
        if (paused) return false
        acc += elapsed * (if (assist == "slow") 0.5 else 1.0)
        val n = (acc / SlotCarsRules.STEP_MS).toInt()
        if (n <= 0) return false
        acc -= n * SlotCarsRules.STEP_MS
        return runSteps(n)
    }

    /** Test hook: exactly [n] fixed steps, no clock (a step-assist pause still holds the cars). */
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

    private fun humanSeats(): List<Int> = (0 until sim.cars).filter { !context.players[it].isBot && it !in gone }

    /** True when the sim may run this tick (the stepwise assist holds the cars until everyone is ready). */
    private fun stepGate(): Boolean {
        if (sim.phase != SlotPhase.RACING || assist != "step") return true
        if (stepRemaining > 0) return true
        val humans = humanSeats()
        if (humans.isNotEmpty() && humans.all { ready[it] }) {
            stepRemaining = SlotCarsRules.STEP_TICKS
            for (h in humans) sim.throttle[h] = plan[h]
            needSync = true
            return true
        }
        return false
    }

    private fun hostStep() {
        if (sim.phase == SlotPhase.OVER) return
        if (freezeTicks > 0) { freezeTicks--; return }
        if (!stepGate()) return
        val before = sim.phase
        if (before == SlotPhase.RACING) {
            for (i in 0 until sim.cars) {
                if (context.players[i].isBot && i !in gone) driver.drive(sim, i, cpuReactionScale)
                else if (assist != "step") sim.throttle[i] = plan[i] && sim.off[i] == 0
            }
        }
        val dSer = sim.deslotSerial; val lSer = sim.lapSerial; val fSer = sim.finishSerial
        sim.step()
        if (assist == "step" && before == SlotPhase.RACING && stepRemaining > 0) {
            stepRemaining--
            if (stepRemaining == 0) { ready.fill(false); needSync = true }
        }
        if (before != sim.phase || dSer != sim.deslotSerial || lSer != sim.lapSerial || fSer != sim.finishSerial) needSync = true
        if (sim.phase == SlotPhase.OVER) finish()
    }

    private fun guestStep() {
        if (sim.phase == SlotPhase.OVER || freezeTicks > 0) return
        if (sim.phase == SlotPhase.RACING && assist == "step") {
            if (stepRemaining <= 0) return
            stepRemaining--
        }
        sim.step(authoritative = false)
    }

    private fun finish() {
        if (winner != null) return
        val order = sim.order()
        winner = order.first()
        val w = sessionWins.value.toMutableList(); w[order.first()] = w[order.first()] + 1; sessionWins.value = w
        val p = sessionPoints.value.toMutableList()
        order.forEachIndexed { place, car -> p[car] = p[car] + SlotCarsRules.points(place, sim.cars) }
        sessionPoints.value = p
        hint.value = null
        needSync = true
    }

    // ---- intents -------------------------------------------------------------------------

    /** Seat [seat] holds ([on]) or releases its throttle. Offline only a human seat may act; networked only this device's seat. */
    @Synchronized
    fun setThrottle(seat: Int, on: Boolean): SlotCarsReject {
        val s = state.value ?: return SlotCarsReject.RACE_OVER
        if (matchOver.value || s.raceOver) return SlotCarsReject.RACE_OVER
        if (seat !in 0 until sim.cars) return SlotCarsReject.BAD_INPUT
        if (isNetworked) {
            if (seat != context.localPlayerIndex) return SlotCarsReject.NOT_YOUR_CAR
            if (opponentLeft.value) return rejectWith(SlotCarsReject.OPPONENT_LEFT)
            if (!isHost) {
                plan[seat] = on
                sendToHost(SlotCarsNetMessage.Throttle(on))
                publish()
                return SlotCarsReject.NONE
            }
        } else if (context.players[seat].isBot) return SlotCarsReject.NOT_YOUR_CAR
        applyThrottle(seat, on)
        publish()
        if (isNetworked) maybeBroadcast()
        return SlotCarsReject.NONE
    }

    private fun applyThrottle(seat: Int, on: Boolean) {
        plan[seat] = on
        if (assist != "step") sim.throttle[seat] = on && sim.off[seat] == 0
        needSync = true
    }

    /** Stepwise assist: this seat's plan is final. */
    @Synchronized
    fun setReady(seat: Int, isReady: Boolean): SlotCarsReject {
        val s = state.value ?: return SlotCarsReject.RACE_OVER
        if (assist != "step") return SlotCarsReject.NOT_STEP_MODE
        if (matchOver.value || s.raceOver) return SlotCarsReject.RACE_OVER
        if (seat !in 0 until sim.cars) return SlotCarsReject.BAD_INPUT
        if (isNetworked) {
            if (seat != context.localPlayerIndex) return SlotCarsReject.NOT_YOUR_CAR
            if (!isHost) {
                ready[seat] = isReady
                sendToHost(SlotCarsNetMessage.Ready(isReady))
                publish()
                return SlotCarsReject.NONE
            }
        } else if (context.players[seat].isBot) return SlotCarsReject.NOT_YOUR_CAR
        ready[seat] = isReady
        needSync = true
        publish()
        if (isNetworked) maybeBroadcast()
        return SlotCarsReject.NONE
    }

    private fun rejectWith(r: SlotCarsReject): SlotCarsReject {
        lastReject.value = r to ++rejectSerial
        return r
    }

    /** Advice for [seat]: should it ease off right now? */
    @Synchronized
    fun requestHint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceAtLeast(0) else 0): SlotHint? {
        if (state.value == null || sim.phase != SlotPhase.RACING || seat !in 0 until sim.cars) return null
        val go = SlotCpu.wantsThrottle(sim.track, sim.s[seat], sim.v[seat], SlotCpu.PERFECT, 0.0)
        val h = SlotHint(seat, brake = !go, serial = ++hintSerial)
        hint.value = h
        return h
    }

    /** Where seat [seat] would be after the next step with the throttle [on]: (distance, speed, leaves the slot). */
    @Synchronized
    fun projectStep(seat: Int, on: Boolean): Triple<Double, Double, Boolean>? =
        if (state.value == null || seat !in 0 until sim.cars) null else sim.project(seat, on, SlotCarsRules.STEP_TICKS)

    fun planOf(seat: Int): Boolean = synchronized(lock) { plan.getOrElse(seat) { false } }

    // ---- state ---------------------------------------------------------------------------

    private fun publish() { state.value = buildState() }

    private fun buildState(): SlotCarsState {
        val order = sim.order()
        val cars = List(sim.cars) { i ->
            SlotCarView(
                seat = i, s = sim.s[i], v = sim.v[i], throttle = sim.throttle[i] || (assist == "step" && plan[i]),
                off = sim.off[i], offS = sim.offS[i], offV = sim.offV[i],
                lapsDone = sim.lapsDone[i], lastLap = sim.lastLap[i], bestLap = sim.bestLap[i], finishTime = sim.finishTime[i],
                deslots = sim.deslots[i], grip = sim.gripUse(i), place = order.indexOf(i), ready = ready.getOrElse(i) { false }
            )
        }
        val cd = when {
            sim.phase == SlotPhase.COUNTDOWN -> SlotCarsRules.COUNTDOWN_TICKS - sim.tick
            freezeTicks > 0 -> freezeTicks
            else -> 0
        }
        return SlotCarsState(
            players = context.players.map { SlotCarsPlayer(it.playerId, it.displayName, it.isBot) },
            track = trackId, laps = laps, assist = assist, phase = sim.phase, tick = sim.tick, countdownTicks = cd,
            cars = cars, order = order, stepRemaining = stepRemaining,
            deslotSerial = sim.deslotSerial, lastDeslotCar = sim.lastDeslotCar, lapSerial = sim.lapSerial, lastLapCar = sim.lastLapCar,
            finishSerial = sim.finishSerial, lastFinishCar = sim.lastFinishCar, winner = winner
        )
    }

    internal fun simForTest(): SlotCarsSim = sim
    internal fun fingerprint(): Long = synchronized(lock) { sim.fingerprint() }

    // ---- resume --------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) { captureSnapshot() }

    private fun netCars(): List<SlotNetCar> = List(sim.cars) { i ->
        SlotNetCar(sim.s[i], sim.v[i], sim.throttle[i], sim.off[i], sim.offS[i], sim.offV[i], sim.lapsDone[i], sim.lapStart[i],
            sim.lastLap[i], sim.bestLap[i], sim.finishTime[i], sim.deslots[i])
    }

    private fun captureSnapshot(): String? = try {
        if (matchOver.value || state.value == null || !started || sim.phase != SlotPhase.RACING || winner != null) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else slotJson.encodeToString(
            SlotSnapshot.serializer(),
            SlotSnapshot(
                players = context.players.map { SlotNetPlayer(it.playerId, it.displayName, it.isBot) },
                track = trackId, laps = laps, assist = assist, difficulty = difficulty.name, tick = sim.tick,
                cars = netCars(), plan = plan.toList(), sessionWins = sessionWins.value, points = sessionPoints.value,
                deslotSerial = sim.deslotSerial, lapSerial = sim.lapSerial, finishSerial = sim.finishSerial
            )
        )
    } catch (_: Exception) { null }

    private fun carsValid(cars: List<SlotNetCar>, n: Int, trackLen: Double, lapCount: Int): Boolean =
        cars.size == n && cars.all { c ->
            c.s.isFinite() && c.s in 0.0..(trackLen * lapCount + 2000.0) && c.v.isFinite() && c.v in 0.0..(SlotCarsRules.VMAX * 1.2) &&
                c.off in 0..SlotCarsRules.RESLOT_TICKS && c.offS.isFinite() && c.offV.isFinite() && c.offV in 0.0..(SlotCarsRules.VMAX * 1.5) &&
                c.lapsDone in 0..lapCount && c.lapStart.isFinite() && c.lastLap.isFinite() && c.bestLap.isFinite() &&
                c.finishTime.isFinite() && c.deslots in 0..100000
        }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        return try {
            val s = slotJson.decodeFromString(SlotSnapshot.serializer(), snapshot)
            val roster = context.players
            val n = roster.size.coerceIn(1, SlotCarsRules.MAX_CARS)
            val track = SlotTrack.byId(s.track)
            val ok = s.track in SlotTrack.ids && s.laps in SlotCarsRules.LAP_CHOICES &&
                (s.assist == "off" || s.assist == "slow" || s.assist == "step") &&
                s.players.size == roster.size &&
                s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
                carsValid(s.cars, n, track.length, s.laps) && s.plan.size == n && s.sessionWins.size == roster.size &&
                s.sessionWins.all { it >= 0 } && s.points.size == roster.size && s.points.all { it >= 0 } &&
                s.tick in 0..SlotCarsRules.MAX_RACE_TICKS && s.cars.none { it.finishTime >= 0 }
            if (!ok) return false
            trackId = s.track; laps = s.laps; assist = s.assist
            difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
            buildSim()
            for (i in 0 until n) {
                val c = s.cars[i]
                sim.s[i] = c.s; sim.v[i] = c.v; sim.throttle[i] = false; sim.off[i] = c.off; sim.offS[i] = c.offS; sim.offV[i] = c.offV
                sim.lapsDone[i] = c.lapsDone; sim.lapStart[i] = c.lapStart; sim.lastLap[i] = c.lastLap; sim.bestLap[i] = c.bestLap
                sim.finishTime[i] = c.finishTime; sim.deslots[i] = c.deslots
            }
            sim.phase = SlotPhase.RACING; sim.tick = s.tick
            sim.deslotSerial = s.deslotSerial; sim.lapSerial = s.lapSerial; sim.finishSerial = s.finishSerial
            plan.fill(false)
            sessionWins.value = s.sessionWins; sessionPoints.value = s.points
            matchOver.value = false; started = true; winner = null
            freezeTicks = 150
            lastMs = -1; hint.value = null
            publish()
            true
        } catch (_: Exception) { false }
    }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(SlotCarsNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) { needSync = true; broadcast() } else sendToHost(SlotCarsNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun recomputeOpponentLeft() {
        // The race is off when every other human is gone.
        val others = (0 until seats).filter { it != context.localPlayerIndex && !context.players[it].isBot }
        opponentLeft.value = others.isNotEmpty() && others.all { it in gone }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0 || seat == context.localPlayerIndex) return
        gone += seat
        if (!isHost && seat == 0) opponentLeft.value = true else recomputeOpponentLeft()
        if (seat in plan.indices) { plan[seat] = false; sim.throttle[seat] = false }
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        gone -= seat
        recomputeOpponentLeft()
        if (isHost && state.value != null) { needSync = true; broadcast() }
    }

    private fun toNetState() = SlotNetState(
        players = context.players.map { SlotNetPlayer(it.playerId, it.displayName, it.isBot) },
        track = trackId, laps = laps, assist = assist, phase = sim.phase.name, tick = sim.tick, firstFinishTick = sim.firstFinishTick,
        cars = netCars(), ready = ready.toList(), stepRemaining = stepRemaining,
        deslotSerial = sim.deslotSerial, lastDeslotCar = sim.lastDeslotCar, lapSerial = sim.lapSerial, lastLapCar = sim.lastLapCar,
        finishSerial = sim.finishSerial, lastFinishCar = sim.lastFinishCar, sessionWins = sessionWins.value, sessionPoints = sessionPoints.value, gone = gone.toList()
    )

    private fun maybeBroadcast() {
        if (state.value == null) return
        syncClock++
        if (needSync || (sim.phase == SlotPhase.RACING && syncClock - lastSyncTick >= 6)) broadcast()
    }

    private fun broadcast() {
        if (state.value == null || !isHost) return
        needSync = false
        lastSyncTick = syncClock
        stateVersion++
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = slotJson.encodeToString(
            SlotCarsNetMessage.serializer(), SlotCarsNetMessage.StateSync(stateVersion, toNetState())
        ).toByteArray(Charsets.UTF_8)
        for ((seat, p) in context.players.withIndex()) {
            if (seat == 0 || p.isBot || seat in gone) continue
            context.transport.send(fromPlayerId = hostId, toPlayerId = p.playerId, payload = payload)
        }
    }

    private fun sendToHost(message: SlotCarsNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = slotJson.encodeToString(SlotCarsNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            slotJson.decodeFromString(SlotCarsNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) { return }
        synchronized(lock) {
            val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
            when (message) {
                is SlotCarsNetMessage.StateSync -> if (!isHost && senderSeat == 0) applyRemoteState(message.version, message.state)
                is SlotCarsNetMessage.RequestState -> if (isHost && senderSeat > 0 && state.value != null) { needSync = true; broadcast() }
                is SlotCarsNetMessage.Throttle ->
                    if (isHost && senderSeat > 0 && senderSeat < sim.cars && state.value != null && !matchOver.value &&
                        !context.players[senderSeat].isBot && sim.phase != SlotPhase.OVER) {
                        applyThrottle(senderSeat, message.on); publish(); maybeBroadcast()
                    }
                is SlotCarsNetMessage.Ready ->
                    if (isHost && senderSeat > 0 && senderSeat < sim.cars && assist == "step" && state.value != null && !matchOver.value &&
                        !context.players[senderSeat].isBot && sim.phase != SlotPhase.OVER) {
                        ready[senderSeat] = message.ready; needSync = true; publish(); maybeBroadcast()
                    }
                is SlotCarsNetMessage.RequestPlayAgain ->
                    if (isHost && senderSeat > 0 && state.value?.raceOver == true && !matchOver.value && !opponentLeft.value) startMatch()
            }
        }
    }

    /** Guest: adopts a strictly newer, well-formed host snapshot. */
    private fun applyRemoteState(version: Int, net: SlotNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size.coerceIn(1, SlotCarsRules.MAX_CARS)
        val ph = SlotPhase.entries.firstOrNull { it.name == net.phase }
        val valid = ph != null && net.track in SlotTrack.ids && net.laps in SlotCarsRules.LAP_CHOICES &&
            (net.assist == "off" || net.assist == "slow" || net.assist == "step") &&
            net.players.size == roster.size && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            carsValid(net.cars, n, SlotTrack.byId(net.track).length, net.laps) && net.ready.size == n &&
            net.tick in 0..SlotCarsRules.MAX_RACE_TICKS && net.firstFinishTick in -1..SlotCarsRules.MAX_RACE_TICKS &&
            net.stepRemaining in 0..SlotCarsRules.STEP_TICKS &&
            net.deslotSerial >= 0 && net.lapSerial >= 0 && net.finishSerial >= 0 &&
            net.lastDeslotCar in -1 until n && net.lastLapCar in -1 until n && net.lastFinishCar in -1 until n &&
            net.sessionWins.size == roster.size && net.sessionWins.all { it in 0..999 } && (net.sessionPoints.isEmpty() || (net.sessionPoints.size == roster.size && net.sessionPoints.all { it in 0..9999 })) && net.gone.all { it in roster.indices }
        if (!valid) return
        lastAppliedRemoteVersion = version
        val rebuild = trackId != net.track || laps != net.laps || sim.cars != n || (sim.phase == SlotPhase.OVER && ph != SlotPhase.OVER)
        trackId = net.track; laps = net.laps; assist = net.assist
        if (rebuild) buildSim()
        for (i in 0 until n) {
            val c = net.cars[i]
            sim.s[i] = c.s; sim.v[i] = c.v; sim.throttle[i] = c.throttle; sim.off[i] = c.off; sim.offS[i] = c.offS; sim.offV[i] = c.offV
            sim.lapsDone[i] = c.lapsDone; sim.lapStart[i] = c.lapStart; sim.lastLap[i] = c.lastLap; sim.bestLap[i] = c.bestLap
            sim.finishTime[i] = c.finishTime; sim.deslots[i] = c.deslots
            ready[i] = net.ready[i]
        }
        sim.phase = ph!!; sim.tick = net.tick; sim.firstFinishTick = net.firstFinishTick
        stepRemaining = net.stepRemaining
        sim.deslotSerial = net.deslotSerial; sim.lastDeslotCar = net.lastDeslotCar
        sim.lapSerial = net.lapSerial; sim.lastLapCar = net.lastLapCar
        sim.finishSerial = net.finishSerial; sim.lastFinishCar = net.lastFinishCar
        sessionWins.value = net.sessionWins
        if (net.sessionPoints.isNotEmpty()) sessionPoints.value = net.sessionPoints
        gone.clear(); gone.addAll(net.gone)
        started = true
        winner = if (ph == SlotPhase.OVER) sim.order().first() else null
        if (!(opponentLeft.value && gone.isEmpty())) recomputeOpponentLeft()
        hint.value = null
        publish()
    }
}
