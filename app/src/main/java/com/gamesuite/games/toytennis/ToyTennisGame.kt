package com.gamesuite.games.toytennis

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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Why a swing request was not taken at all (a swing that happens but misses is a whiff in the state). */
enum class TennisReject { NONE, NOT_PLAYING, NOT_YOUR_SEAT, BAD_INPUT, OPPONENT_LEFT }

data class TennisRejectInfo(val seat: Int, val reason: TennisReject, val serial: Int)

data class TennisPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** What this device shows: a copy of the court plus who plays. */
data class TennisView(
    val court: TennisState,
    val players: List<TennisPlayerState>,
    val assist: Boolean,
    val format: TennisFormat
)

/** A suggested swing: [dirX]/[power] to send, and where it would land (world metres). */
data class TennisHint(val seat: Int, val dirX: Double, val power: Double, val targetX: Double, val targetY: Double)

/** How a computer level plays tennis. */
data class TennisCpuProfile(
    val reactTicks: Int, val speed: Double, val timingSigma: Double, val aimSigma: Double,
    val powerLow: Double, val powerHigh: Double, val smart: Double
)

object TennisCpu {
    fun profile(level: CpuDifficulty): TennisCpuProfile = when (level) {
        CpuDifficulty.EASY -> TennisCpuProfile(31, 6.8, 0.075, 0.26, 0.44, 0.72, 0.55)
        CpuDifficulty.MEDIUM -> TennisCpuProfile(26, 7.0, 0.060, 0.22, 0.50, 0.75, 0.65)
        CpuDifficulty.HARD -> TennisCpuProfile(21, 7.3, 0.05, 0.18, 0.54, 0.80, 0.75)
    }
}

@Serializable
private data class TennisSnapshot(
    val players: List<TennisNetPlayer>,
    val court: TennisState,
    val assist: Boolean,
    val sessionWins: List<Int>,
    val difficulty: String,
    val formatId: String,
    val firstServer: Int
)

private val tennisJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val KNOWN_FORMATS = listOf(TennisFormat.POINTS7, TennisFormat.POINTS11, TennisFormat.SET4, TennisFormat.SET6, TennisFormat.BEST3)

/**
 * Toy Tennis: one against one on a small court, played with swipes. Your player runs to the ball by
 * itself; YOU choose the shot and its moment: swipe toward the net, to the left or the right of
 * straight ahead for the angle, and as fast as you like for the power, and let go as the ball reaches
 * the shrinking ring. The nearer the ring is to closing, the better the shot (see [TennisSim]).
 * Auto-run keeps the game to one finger and one decision, which is what a phone is good at.
 *
 * Serve (the ball is tossed for you, swing at the top), rally (one bounce, then hit), and scoring as
 * real games and sets or a short race to N points ([TennisFormat]).
 *
 * RELAXED TIMING ([assist]) is the accessibility option: the ball waits for your swing and the player
 * is always in place, so only the direction and power matter.
 *
 * TIME comes only from the injected [clock]; the computer's blur only from [random]. The host (seat
 * 0) runs the simulation; a guest sends tick-stamped swings and carries host snapshots forward (see
 * [TennisNetMessage]).
 */
class ToyTennisGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "toy-tennis"
    override val displayName = "Toy Tennis"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<TennisView?>(null)
    val sessionWins = mutableStateOf(listOf(0, 0))
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<TennisHint?>(null)
    val lastReject = mutableStateOf<TennisRejectInfo?>(null)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var assist: Boolean = false
    var formatId: String = "points7"
    var firstServer: Int = 0
    var cpuReactionScale: Double = 1.0
    internal val cpuLevels = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock = Any()

    private var format = TennisFormat.POINTS7
    private var cfg = TennisConfig()
    private var sim = TennisSim(cfg)
    private var ts = TennisState()
    private val history = ArrayDeque<TennisState>()
    private val cmdLog = HashMap<Int, MutableList<SwingCmd>>()
    private val consumed = HashMap<Int, List<SwingCmd>>()
    private var plannedHit = 0
    private var plannedToss = -1
    private var replaying = false
    private var wonCounted = false
    private var rejectSerial = 0

    private var lastMs = -1L
    private var acc = 0.0
    private var paused = false

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private var lastSyncTick = 0
    private var needSync = false
    private var lastSerials = IntArray(7)

    private fun buildConfig(): TennisConfig {
        val bot = BooleanArray(2) { context.players.getOrNull(it)?.isBot == true }
        val speed = DoubleArray(2) { 8.0 }
        val react = IntArray(2)
        for (i in 0..1) if (bot[i]) {
            val p = TennisCpu.profile(cpuLevels[i] ?: difficulty)
            speed[i] = p.speed
            react[i] = (p.reactTicks * cpuReactionScale).roundToInt().coerceAtLeast(1)
        }
        return TennisConfig(assist = assist, bot = bot, speed = speed, react = react)
    }

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = listOf(0, 0)
            hint.value = null
            lastReject.value = null
            state.value = null
            history.clear(); cmdLog.clear(); consumed.clear()
            cfg = buildConfig(); sim = TennisSim(cfg)
            stateVersion = 0; lastAppliedRemoteVersion = 0; lastSyncTick = 0; needSync = false
            lastMs = -1; acc = 0.0; paused = false
            ts = TennisState(phase = TPhase.OVER)
            if (isNetworked) registerNetworkListeners()
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        if (isNetworked && !isHost) return
        matchOver.value = false
        newMatch()
        publish()
        if (isNetworked) broadcast()
    }

    @Synchronized
    override fun pause() { paused = true }

    @Synchronized
    override fun resume() { paused = false; lastMs = -1 }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val w = sessionWins.value
        val scores = context.players.mapIndexed { seat, p ->
            PlayerScore(p.playerId, w[seat.coerceIn(0, 1)], isWinner = w[seat.coerceIn(0, 1)] > 0 && w[seat.coerceIn(0, 1)] > w[1 - seat.coerceIn(0, 1)])
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        val v = state.value
        if (v == null || v.court.phase != TPhase.OVER) return
        if (isNetworked) {
            if (opponentLeft.value) return
            if (isHost) startMatch() else sendToHost(TennisNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    private fun newMatch() {
        format = TennisFormat.parse(formatId)
        cfg = buildConfig()
        sim = TennisSim(cfg)
        ts = sim.newMatch(format, firstServer.coerceIn(0, 1))
        history.clear(); cmdLog.clear(); consumed.clear()
        plannedHit = 0; plannedToss = -1; wonCounted = false
        hint.value = null
        needSync = true
    }

    // ---- the clock -----------------------------------------------------------------------

    @Synchronized
    fun advance(): Boolean {
        if (state.value == null || matchOver.value) return false
        val now = clock()
        if (lastMs < 0) { lastMs = now; return false }
        val elapsed = (now - lastMs).coerceIn(0L, 250L)
        lastMs = now
        if (paused) return false
        acc += elapsed
        val stepMs = 1000.0 / TennisRules.STEPS
        val n = (acc / stepMs).toInt()
        if (n <= 0) return false
        acc -= n * stepMs
        runSteps(n)
        return true
    }

    @Synchronized
    internal fun stepTicks(n: Int) {
        if (state.value == null) return
        runSteps(n)
    }

    private fun runSteps(n: Int) {
        val guest = isNetworked && !isHost
        repeat(n) {
            if (guest) sim.step(ts, emptyList(), auth = false) else {
                hostStep()
                if (isNetworked) maybeBroadcast()
            }
        }
        publish()
    }

    private fun hostStep() {
        history.addLast(ts.copy())
        while (history.size > TennisRules.MAX_REWIND + 4) history.removeFirst()
        val cmds = cmdLog.remove(ts.tick) ?: emptyList()
        if (cmds.isNotEmpty()) consumed[ts.tick] = cmds
        if (ts.tick % 240 == 0) consumed.keys.removeAll { it < ts.tick - 4 * TennisRules.MAX_REWIND }
        sim.step(ts, cmds, auth = true)
        if (!replaying) afterStep()
        if (ts.phase == TPhase.OVER && !wonCounted && ts.score.over) {
            wonCounted = true
            val w = ts.score.winner
            sessionWins.value = sessionWins.value.toMutableList().also { it[w] = it[w] + 1 }
            needSync = true
        }
    }

    private fun serials() = intArrayOf(ts.hitSerial, ts.bounceSerial, ts.pointSerial, ts.faultSerial, ts.swingSerial, ts.netSerial, ts.phase.ordinal)

    private fun afterStep() {
        val cur = serials()
        if (!cur.contentEquals(lastSerials)) { needSync = true; lastSerials = cur }
        // Plan the computers' swings when the ball is hit or tossed.
        if (ts.phase == TPhase.TOSS && plannedToss != ts.cTick && cfg.bot[ts.server]) {
            plannedToss = ts.cTick
            planSwing(ts.server)
        }
        if (ts.hitSerial != plannedHit) {
            plannedHit = ts.hitSerial
            if (ts.phase == TPhase.RALLY && ts.lastHitter >= 0) {
                val rec = 1 - ts.lastHitter
                if (cfg.bot[rec] && ts.cTick >= 0) planSwing(rec)
            }
        }
        if (ts.phase == TPhase.POINT || ts.phase == TPhase.PREP) hint.value = null
    }

    private fun gauss(): Double = (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0

    private fun planSwing(side: Int) {
        val prof = TennisCpu.profile(cpuLevels[side] ?: difficulty)
        val timingTicks = (gauss() * prof.timingSigma * TennisRules.STEPS).roundToInt()
        val at = maxOf(ts.tick + 1, ts.cTick + timingTicks)
        val ss = if (side == 0) 1.0 else -1.0
        val serve = ts.phase == TPhase.TOSS
        val opp = ts.px(1 - side)
        var worldX = if (random.nextDouble() < prof.smart) {
            // Away from where the opponent stands.
            val dir = if (abs(opp) < 0.6) (if (random.nextBoolean()) 1.0 else -1.0) else -Math.signum(opp)
            dir * 0.78 * TennisRules.HW
        } else (random.nextDouble() * 2 - 1) * 0.6 * TennisRules.HW
        var dirX: Double
        if (serve) {
            // Serves aim inside the box: dirX is relative to the box centre.
            dirX = (if (random.nextDouble() < prof.smart) (if (random.nextBoolean()) 0.8 else -0.8) else (random.nextDouble() * 2 - 1) * 0.5)
        } else {
            dirX = worldX * ss / (TennisRules.HW - 0.45)
        }
        dirX = (dirX + gauss() * prof.aimSigma).coerceIn(-1.0, 1.0)
        val power = (prof.powerLow + random.nextDouble() * (prof.powerHigh - prof.powerLow)).coerceIn(0.0, 1.0)
        cmdLog.getOrPut(at) { mutableListOf() }.add(SwingCmd(side, dirX, power))
    }

    // ---- swinging ------------------------------------------------------------------------

    /**
     * Seat [seat] swings: [dirX] -1..1 (left..right from that player's view), [power] 0..1. Offline only
     * a human seat may swing; networked only this device's seat (a guest sends a tick-stamped request).
     * Whether the swing hits is decided by the simulation (see the state's whiff fields).
     */
    @Synchronized
    fun swing(seat: Int, dirX: Double, power: Double): TennisReject {
        val v = state.value ?: return TennisReject.NOT_PLAYING
        if (matchOver.value || v.court.phase == TPhase.OVER) return TennisReject.NOT_PLAYING
        if (!dirX.isFinite() || !power.isFinite()) return TennisReject.BAD_INPUT
        if (seat !in 0..1 || seat >= context.players.size) return TennisReject.NOT_YOUR_SEAT
        if (isNetworked) {
            if (seat != context.localPlayerIndex) return TennisReject.NOT_YOUR_SEAT
            if (opponentLeft.value) return note(seat, TennisReject.OPPONENT_LEFT)
            if (!isHost) {
                sendToHost(TennisNetMessage.SwingRequest(ts.tick, dirX.coerceIn(-1.0, 1.0), power.coerceIn(0.0, 1.0)))
                return TennisReject.NONE
            }
        } else if (context.players[seat].isBot) return TennisReject.NOT_YOUR_SEAT
        hint.value = null
        cmdLog.getOrPut(ts.tick) { mutableListOf() }.add(SwingCmd(seat, dirX.coerceIn(-1.0, 1.0), power.coerceIn(0.0, 1.0)))
        return TennisReject.NONE
    }

    private fun note(seat: Int, r: TennisReject): TennisReject {
        lastReject.value = TennisRejectInfo(seat, r, ++rejectSerial)
        return r
    }

    /** Host: a guest's swing stamped with the tick it saw; judged at that tick, then the court is replayed to now. */
    private fun hostSwingFromGuest(seat: Int, tick: Int, dirX: Double, power: Double) {
        if (state.value == null || ts.phase == TPhase.OVER) return
        val cmd = SwingCmd(seat, dirX.coerceIn(-1.0, 1.0), power.coerceIn(0.0, 1.0))
        val now = ts.tick
        val at = tick.coerceIn(now - TennisRules.MAX_REWIND, now)
        val snap = if (at < now && !cfg.assist) history.firstOrNull { it.tick == at } else null
        if (snap == null) {
            cmdLog.getOrPut(now) { mutableListOf() }.add(cmd)
            return
        }
        // Rewind to the tick the guest saw, put the swing there, and replay.
        while (history.isNotEmpty() && history.last().tick >= at) history.removeLast()
        ts = snap.copy()
        cmdLog.getOrPut(at) { mutableListOf() }.add(cmd)
        replaying = true
        try {
            // Commands of the replayed ticks that were already consumed must be re-applied: keep a log of them.
            while (ts.tick < now) hostStepReplay()
        } finally {
            replaying = false
        }
        needSync = true
    }

    private fun hostStepReplay() {
        val extra = consumed[ts.tick]
        if (extra != null) cmdLog.getOrPut(ts.tick) { mutableListOf() }.addAll(extra)
        hostStep()
    }

    // ---- hint ----------------------------------------------------------------------------

    /** Suggests a swing for [seat]: away from the opponent, firm but not all-out (offline only). */
    @Synchronized
    fun requestHint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceAtLeast(0) else 0): TennisHint? {
        if (isNetworked || state.value == null || seat !in 0..1) return null
        val c = ts
        if (c.phase != TPhase.TOSS && c.phase != TPhase.RALLY && c.phase != TPhase.PREP) return null
        val ss = if (seat == 0) 1.0 else -1.0
        val opp = c.px(1 - seat)
        val side = if (abs(opp) < 0.6) 1.0 else -Math.signum(opp)
        val dirX = (side * ss * 0.75).coerceIn(-1.0, 1.0)
        val power = 0.72
        val serve = c.phase != TPhase.RALLY
        val targetX = if (serve) {
            c.boxSign * TennisRules.HW / 2 + dirX * ss * (TennisRules.HW / 2 - 0.35)
        } else dirX * ss * (TennisRules.HW - 0.45)
        val depth = if (serve) 2.6 + (TennisRules.SERVICE - 0.35 - 2.6) * power else 3.2 + (TennisRules.HL - 0.5 - 3.2) * power
        val h = TennisHint(seat, dirX, power, targetX, -ss * depth)
        hint.value = h
        return h
    }

    // ---- state ---------------------------------------------------------------------------

    private fun publish() {
        state.value = TennisView(
            court = ts.copy(),
            players = context.players.map { TennisPlayerState(it.playerId, it.displayName, it.isBot) },
            assist = assist || cfg.assist, format = ts.score.format
        )
    }

    internal fun courtForTest(): TennisState = ts
    internal fun configForTest(): TennisConfig = cfg

    // ---- resume --------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null || ts.phase == TPhase.OVER || ts.score.over) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            tennisJson.encodeToString(
                TennisSnapshot.serializer(),
                TennisSnapshot(
                    players = context.players.map { TennisNetPlayer(it.playerId, it.displayName, it.isBot) },
                    court = ts.copy(), assist = assist, sessionWins = sessionWins.value,
                    difficulty = difficulty.name, formatId = formatId, firstServer = firstServer
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = try {
            tennisJson.decodeFromString(TennisSnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            return false
        }
        val roster = context.players
        val fmt = TennisFormat.parse(s.formatId)
        val ok = s.players.size == roster.size && roster.size == 2 &&
            s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
            validScore(s.court.score, fmt) && s.court.phase != TPhase.OVER && !s.court.score.over &&
            s.sessionWins.size == 2 && s.sessionWins.all { it in 0..999 } && s.firstServer in 0..1
        if (!ok) return false
        matchOver.value = false
        formatId = s.formatId; format = fmt
        assist = s.assist; firstServer = s.firstServer
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
        cfg = buildConfig(); sim = TennisSim(cfg)
        ts = TennisState(score = s.court.score)
        sim.placeForServe(ts)
        ts.phase = TPhase.PREP; ts.phaseTicks = TennisRules.PREP_TICKS + 40
        sessionWins.value = s.sessionWins
        history.clear(); cmdLog.clear(); consumed.clear()
        plannedHit = 0; plannedToss = -1; wonCounted = false
        lastMs = -1
        hint.value = null
        publish()
        return true
    }

    private fun validScore(sc: TennisScore, fmt: TennisFormat): Boolean =
        sc.format == fmt && fmt in KNOWN_FORMATS && sc.pointsA in 0..60 && sc.pointsB in 0..60 && sc.gamesA in 0..20 && sc.gamesB in 0..20 &&
            sc.setsA in 0..3 && sc.setsB in 0..3 && sc.firstServer in 0..1 && sc.gameServer in 0..1 &&
            sc.totalPoints in 0..2000 && sc.winner in -1..1 && sc.finishedSetsA.size == sc.finishedSetsB.size &&
            sc.finishedSetsA.size <= 5 && sc.finishedSetsA.all { it in 0..20 } && sc.finishedSetsB.all { it in 0..20 }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(TennisNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) broadcast() else sendToHost(TennisNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { if (context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId == id) opponentLeft.value = true } }
        transport.onPlayerJoined { id ->
            synchronized(lock) {
                if (context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId == id && opponentLeft.value) {
                    opponentLeft.value = false
                    if (isHost && state.value != null) broadcast()
                }
            }
        }
    }

    private fun maybeBroadcast() {
        if (state.value == null) return
        if (needSync || (ts.phase == TPhase.RALLY || ts.phase == TPhase.TOSS) && ts.tick - lastSyncTick >= 6) broadcast()
    }

    private fun broadcast() {
        if (state.value == null || !isHost || !this::context.isInitialized) return
        needSync = false
        lastSyncTick = ts.tick
        stateVersion++
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val net = TennisNetState(
            players = context.players.map { TennisNetPlayer(it.playerId, it.displayName, it.isBot) },
            court = ts.copy(), assist = assist, sessionWins = sessionWins.value
        )
        val payload = tennisJson.encodeToString(TennisNetMessage.serializer(), TennisNetMessage.StateSync(stateVersion, net))
            .toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: TennisNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = tennisJson.encodeToString(TennisNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun sendToGuest(message: TennisNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val payload = tennisJson.encodeToString(TennisNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            tennisJson.decodeFromString(TennisNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            val hostId = context.players.getOrNull(0)?.playerId
            val guestId = context.players.getOrNull(1)?.playerId
            when (message) {
                is TennisNetMessage.StateSync ->
                    if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
                is TennisNetMessage.SwingRejected ->
                    if (!isHost && fromPlayerId == hostId) {
                        TennisReject.entries.firstOrNull { it.name == message.reason }?.takeIf { it != TennisReject.NONE }
                            ?.let { note(context.localPlayerIndex, it) }
                    }
                is TennisNetMessage.RequestState ->
                    if (isHost && fromPlayerId == guestId && state.value != null) broadcast()
                is TennisNetMessage.SwingRequest ->
                    if (isHost && fromPlayerId == guestId && state.value != null && !matchOver.value && !opponentLeft.value) {
                        if (message.dirX.isFinite() && message.power.isFinite()) {
                            hostSwingFromGuest(1, message.tick, message.dirX, message.power)
                            publish()
                        } else sendToGuest(TennisNetMessage.SwingRejected(TennisReject.BAD_INPUT.name))
                    }
                is TennisNetMessage.RequestPlayAgain ->
                    if (isHost && fromPlayerId == guestId && state.value?.court?.phase == TPhase.OVER && !matchOver.value && !opponentLeft.value) startMatch()
            }
        }
    }

    private fun applyRemoteState(version: Int, net: TennisNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val c = net.court
        val fin = { d: Double -> d.isFinite() }
        val valid = roster.size == 2 && net.players.size == 2 &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            listOf(c.bx, c.by, c.bz, c.bvx, c.bvy, c.bvz, c.p0x, c.p0y, c.p1x, c.p1y, c.cX, c.cY, c.cZ).all(fin) &&
            abs(c.bx) < 100 && abs(c.by) < 100 && abs(c.bz) < 100 && abs(c.bvx) < 200 && abs(c.bvy) < 200 && abs(c.bvz) < 200 &&
            abs(c.p0x) < 40 && abs(c.p0y) < 40 && abs(c.p1x) < 40 && abs(c.p1y) < 40 &&
            c.tick >= 0 && c.phaseTicks in 0..2000 && c.bounces in 0..10 && c.lastHitter in -1..1 && c.server in 0..1 &&
            c.faults in 0..1 && c.cool0 in 0..200 && c.cool1 in 0..200 && c.pointWinner in -1..1 && c.lastSwingSide in -1..1 &&
            c.lastQuality in 0..4 && c.boxSign.let { it == -1 || it == 1 } && c.cTick >= -1 &&
            listOf(c.hitSerial, c.bounceSerial, c.pointSerial, c.faultSerial, c.swingSerial, c.netSerial, c.whiffSerial).all { it >= 0 } &&
            validScore(c.score, c.score.format) &&
            net.sessionWins.size == 2 && net.sessionWins.all { it in 0..999 }
        if (!valid) return
        lastAppliedRemoteVersion = version
        assist = net.assist
        if (cfg.assist != net.assist) { cfg = TennisConfig(net.assist, cfg.bot, cfg.speed, cfg.react); sim = TennisSim(cfg) }
        ts = c.copy()
        sessionWins.value = net.sessionWins
        hint.value = null
        publish()
    }
}
