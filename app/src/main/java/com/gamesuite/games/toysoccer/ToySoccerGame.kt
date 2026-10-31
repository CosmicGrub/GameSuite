package com.gamesuite.games.toysoccer

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
import kotlin.random.Random

enum class SoccerPhase { COUNTDOWN, PLAYING, GOAL, OVER }

object SoccerEvent {
    const val START = "start"
    const val GO = "go"
    const val FLICK = "flick"
    const val GOAL = "goal"
    const val OVERTIME = "overtime"
    const val TURN = "turn"
    const val WIN = "win"
}

/** Why a flick did not happen. */
enum class SoccerReject {
    NONE, NOT_PLAYING, NOT_STARTED, NOT_YOUR_FIGURE, NOT_YOUR_TURN, STILL_MOVING, TOO_WEAK, BAD_INPUT, OPPONENT_LEFT
}

data class SoccerRejectInfo(val seat: Int, val reason: SoccerReject, val figure: Int, val serial: Int)

data class SoccerPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** One circle on the table. [kind]: 0 ball, 1 keeper, 2 field figure. */
data class SoccerBody(
    val kind: Int, val team: Int, val figure: Int,
    val x: Double, val y: Double, val vx: Double, val vy: Double, val r: Double, val ready: Boolean
)

/** A suggested flick for the hint arrow. */
data class SoccerHint(val seat: Int, val team: Int, val plan: SoccerFlickPlan)

/**
 * What the table looks like to this device. The serials only ever grow and key the screen's
 * sounds, haptics and animations: [flickSerial], [goalSerial], and the three impact kinds
 * ([kickSerial] ball against figure, [wallSerial] ball against wall or post, [bumpSerial] figure
 * against figure) with [hitStrength] 0..1 and [hitX] (world x, for stereo pan) of the latest.
 */
data class SoccerState(
    val players: List<SoccerPlayerState>,
    val figuresPerTeam: Int,
    val bodies: List<SoccerBody>,
    val phase: SoccerPhase,
    val phaseTicks: Int,
    val score: List<Int>,
    val target: Int,
    val ticksLeft: Int,
    val flicksLeft: Int,
    val goldenGoal: Boolean,
    val assist: Boolean,
    val turnTeam: Int,
    val turnFlicked: Boolean,
    val event: String,
    val eventTeam: Int?,
    val flickSerial: Int,
    val goalSerial: Int,
    val kickSerial: Int,
    val wallSerial: Int,
    val bumpSerial: Int,
    val hitStrength: Double,
    val hitX: Double,
    val winnerTeam: Int?
) {
    val matchOver: Boolean get() = phase == SoccerPhase.OVER
}

/** "first3" / "first5" (race to goals) or "time2" / "time4" (minutes); anything else is first to 3. */
data class SoccerMatch(val goals: Int, val seconds: Int) {
    companion object {
        fun parse(spec: String?): SoccerMatch {
            val s = spec ?: return SoccerMatch(3, 0)
            if (s.startsWith("first")) s.removePrefix("first").toIntOrNull()?.let { if (it in 1..15) return SoccerMatch(it, 0) }
            if (s.startsWith("time")) s.removePrefix("time").toIntOrNull()?.let { if (it in 1..15) return SoccerMatch(0, it * 60) }
            return SoccerMatch(3, 0)
        }
    }
}

@Serializable
private data class SoccerSnapshot(
    val players: List<SoccerSnapshotPlayer>,
    val bodies: List<Double>,
    val score: List<Int>,
    val target: Int,
    val ticksLeft: Int,
    val flicksLeft: Int,
    val goldenGoal: Boolean,
    val assist: Boolean,
    val turnTeam: Int,
    val sessionWins: List<Int>,
    val difficulty: String,
    val matchSpec: String,
    val firstTeam: Int,
    val flickSerial: Int = 0,
    val goalSerial: Int = 0
)

@Serializable
private data class SoccerSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val soccerJson = Json { ignoreUnknownKeys = true }

/**
 * Toy Soccer: a tabletop soccer game played by FLICKING toy figures at a ball, in real time.
 *
 * CONTROLS. Pull back from one of your figures (slingshot) and let go: it shoots the way you pulled,
 * harder the longer the pull. A figure can be flicked again once it has come to rest. This beats
 * dragging foosball rods on a phone: one finger and one gesture per kick, the finger never covers
 * the target, the aim and power are read straight off the pull, two people can play at once on one
 * device, and it turns into a turn-based game with no change of controls ([assist]).
 *
 * DETERMINISM. All motion is [SoccerWorld], a fixed 120 Hz step with no clock and no random numbers.
 * Time comes only from the injected [clock] (milliseconds, consumed in [advance]) and randomness
 * (the computer's blur) only from [random].
 *
 * MATCHES: first to N goals, or timed (a tie goes to sudden death). TURN-BASED ASSIST: no clock, the
 * teams take turns, one flick each, and the table settles before the next turn; a timed match then
 * counts flicks instead of seconds.
 *
 * NETWORK: the host (seat 0) runs the only simulation and the computers; guests send [flick]
 * intents and receive versioned snapshots (see [SoccerNetMessage]).
 *
 * TEAMS: seat % 2. With 3 or 4 seats each team has two players and four figures.
 */
class ToySoccerGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "toy-soccer"
    override val displayName = "Toy Soccer"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<SoccerState?>(null)
    val sessionWins = mutableStateOf(listOf(0, 0))
    val matchOver = mutableStateOf(false)
    val opponentLeft = mutableStateOf(false)
    val hint = mutableStateOf<SoccerHint?>(null)
    val lastReject = mutableStateOf<SoccerRejectInfo?>(null)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var assist: Boolean = false
    var matchSpec: String = "first3"
    var firstTeam: Int = 0

    /** Scales the computer's reaction time (the player's game speed: slower setting, slower computer). */
    var cpuReactionScale: Double = 1.0

    /** Per-seat computer level override (tests pit levels against each other). */
    internal val cpuLevels = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock = Any()

    private var world = SoccerWorld(3)
    private var phase = SoccerPhase.OVER
    private var phaseTicks = 0
    private val score = IntArray(2)
    private var target = 3
    private var ticksLeft = -1
    private var flicksLeft = -1
    private var goldenGoal = false
    private var turnTeam = 0
    private var turnFlicked = false
    private var settleTicks = 0
    private var turnTicks = 0
    private var event = SoccerEvent.START
    private var eventTeam: Int? = null
    private var flickSerial = 0
    private var goalSerial = 0
    private var kickSerial = 0
    private var wallSerial = 0
    private var bumpSerial = 0
    private var hitStrength = 0.0
    private var hitX = SoccerRules.CX
    private var winnerTeam: Int? = null
    private var rejectSerial = 0
    internal var tick = 0L; private set

    private var lastMs = -1L
    private var acc = 0.0
    private var paused = false

    private class Pending(val plan: SoccerFlickPlan, val at: Long)

    private val pending = HashMap<Int, Pending>()
    private val nextThink = HashMap<Int, Long>()
    private val gone = HashSet<Int>()

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private var lastSyncTick = 0L
    private var needSync = false

    private val seats: Int get() = context.players.size

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            matchOver.value = false
            opponentLeft.value = false
            sessionWins.value = listOf(0, 0)
            hint.value = null
            lastReject.value = null
            state.value = null
            world = SoccerWorld(SoccerRules.figuresFor(context.players.size))
            phase = SoccerPhase.OVER
            winnerTeam = null
            gone.clear(); pending.clear(); nextThink.clear()
            stateVersion = 0; lastAppliedRemoteVersion = 0; lastSyncTick = 0; needSync = false
            lastMs = -1; acc = 0.0; paused = false
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

    /** Ends the session, scoring each player by their team's matches won; only a strict leader wins. */
    fun leaveSession() {
        if (matchOver.value) return
        val w = sessionWins.value
        val scores = context.players.mapIndexed { seat, p ->
            val t = SoccerRules.teamOfSeat(seat)
            PlayerScore(p.playerId, w[t], isWinner = w[t] > 0 && w[t] > w[1 - t])
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        val s = state.value
        if (s == null || !s.matchOver) return
        if (isNetworked) {
            if (opponentLeft.value) return
            if (isHost) startMatch() else sendToHost(SoccerNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    private fun newMatch() {
        val m = SoccerMatch.parse(matchSpec)
        world = SoccerWorld(SoccerRules.figuresFor(seats))
        score[0] = 0; score[1] = 0
        target = m.goals
        ticksLeft = if (m.seconds > 0 && !assist) m.seconds * SoccerRules.STEPS_PER_SECOND else -1
        flicksLeft = if (m.seconds > 0 && assist) m.seconds / 4 else -1
        goldenGoal = false
        turnTeam = firstTeam.coerceIn(0, 1)
        turnFlicked = false; settleTicks = 0; turnTicks = 0
        winnerTeam = null
        event = SoccerEvent.START; eventTeam = null
        hint.value = null
        pending.clear(); nextThink.clear()
        if (assist) { phase = SoccerPhase.PLAYING; phaseTicks = 0 } else { phase = SoccerPhase.COUNTDOWN; phaseTicks = SoccerRules.COUNTDOWN_TICKS }
        needSync = true
    }

    // ---- the clock -----------------------------------------------------------------------

    /**
     * Moves the table on by the time since the last call (at most a quarter of a second at once).
     * Call it every frame. A guest carries the last snapshot forward; the host also runs the computers,
     * the timer and the rules. Returns true when the picture changed.
     */
    @Synchronized
    fun advance(): Boolean {
        if (state.value == null || matchOver.value) return false
        val now = clock()
        if (lastMs < 0) { lastMs = now; return false }
        val elapsed = (now - lastMs).coerceIn(0L, 250L)
        lastMs = now
        if (paused) return false
        acc += elapsed
        val n = (acc / SoccerRules.STEP_MS).toInt()
        if (n <= 0) return false
        acc -= n * SoccerRules.STEP_MS
        return runSteps(n)
    }

    /** Test hook: exactly [n] fixed steps, no clock. */
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

    private fun guestStep() {
        tick++
        when (phase) {
            SoccerPhase.COUNTDOWN -> world.glideToKickoff()
            SoccerPhase.PLAYING -> world.step(detectGoal = false)
            SoccerPhase.GOAL -> world.step(detectGoal = false)
            SoccerPhase.OVER -> {}
        }
    }

    private fun hostStep() {
        tick++
        when (phase) {
            SoccerPhase.COUNTDOWN -> {
                val arrived = world.glideToKickoff()
                phaseTicks--
                if (phaseTicks <= 0 && arrived) {
                    phase = SoccerPhase.PLAYING
                    event = SoccerEvent.GO; eventTeam = null
                    needSync = true
                }
            }
            SoccerPhase.PLAYING -> playingStep()
            SoccerPhase.GOAL -> {
                world.step(detectGoal = false)
                phaseTicks--
                if (phaseTicks <= 0) afterGoal()
            }
            SoccerPhase.OVER -> {}
        }
    }

    private fun playingStep() {
        runComputers()
        val scorer = world.step()
        recordImpacts()
        if (scorer >= 0) { onGoal(scorer); return }
        if (assist) {
            turnTicks++
            if (turnFlicked) {
                settleTicks = if (world.allSettled()) settleTicks + 1 else 0
                if (settleTicks >= SoccerRules.SETTLE_TICKS || turnTicks >= SoccerRules.TURN_TIMEOUT_TICKS) nextTurn()
            }
        } else if (ticksLeft >= 0) {
            ticksLeft--
            if (ticksLeft <= 0) timeUp()
        }
    }

    private fun recordImpacts() {
        val k = world.ballFigureImpact
        val wl = world.ballWallImpact
        val b = world.figureFigureImpact
        if (k > 90.0) { kickSerial++; hitStrength = (k / 1300.0).coerceIn(0.0, 1.0); hitX = world.impactX; needSync = true }
        else if (wl > 90.0) { wallSerial++; hitStrength = (wl / 1300.0).coerceIn(0.0, 1.0); hitX = world.impactX; needSync = true }
        if (b > 90.0) bumpSerial++
    }

    private fun nextTurn() {
        turnTeam = 1 - turnTeam
        turnFlicked = false; settleTicks = 0; turnTicks = 0
        event = SoccerEvent.TURN; eventTeam = turnTeam
        needSync = true
        if (flicksLeft == 0) timeUp()
    }

    private fun timeUp() {
        if (score[0] != score[1]) finish(if (score[0] > score[1]) 0 else 1)
        else {
            goldenGoal = true
            flicksLeft = -1; ticksLeft = -1
            event = SoccerEvent.OVERTIME; eventTeam = null
            needSync = true
        }
    }

    private fun onGoal(team: Int) {
        score[team]++
        goalSerial++
        event = SoccerEvent.GOAL; eventTeam = team
        phase = SoccerPhase.GOAL; phaseTicks = SoccerRules.GOAL_TICKS
        hint.value = null
        pending.clear()
        if ((target > 0 && score[team] >= target) || goldenGoal) winnerTeam = team
        turnTeam = 1 - team; turnFlicked = false; settleTicks = 0; turnTicks = 0
        needSync = true
    }

    private fun afterGoal() {
        val w = winnerTeam
        if (w != null) { finish(w); return }
        phase = SoccerPhase.COUNTDOWN
        phaseTicks = if (assist) SoccerRules.RESET_TICKS else SoccerRules.COUNTDOWN_TICKS - 60
        for (i in 0 until world.count) { world.vx[i] = 0.0; world.vy[i] = 0.0 }
        needSync = true
    }

    private fun finish(team: Int) {
        if (phase == SoccerPhase.OVER) return
        winnerTeam = team
        phase = SoccerPhase.OVER
        event = SoccerEvent.WIN; eventTeam = team
        sessionWins.value = sessionWins.value.toMutableList().also { it[team] = it[team] + 1 }
        hint.value = null
        pending.clear()
        needSync = true
    }

    // ---- computers -----------------------------------------------------------------------

    private fun runComputers() {
        for (seat in context.players.indices) {
            if (!context.players[seat].isBot || seat in gone) continue
            val team = SoccerRules.teamOfSeat(seat)
            if (assist && (turnTeam != team || turnFlicked)) { pending.remove(seat); continue }
            val level = cpuLevels[seat] ?: difficulty
            val prof = SoccerCpu.profile(level)
            val pl = pending[seat]
            if (pl == null) {
                if (tick >= (nextThink[seat] ?: 0L)) {
                    val figs = SoccerRules.figuresOfSeat(seat, seats, gone)
                    val plan = SoccerCpu.decide(world, team, figs, prof, random)
                    if (plan == null) nextThink[seat] = tick + 12
                    else {
                        var delay = prof.reactionTicks * (1.0 + (random.nextDouble() * 2 - 1) * prof.jitter)
                        if (assist) delay = maxOf(delay, 72.0)
                        pending[seat] = Pending(plan, tick + (delay * cpuReactionScale).toLong().coerceAtLeast(1L))
                    }
                }
            } else if (tick >= pl.at) {
                pending.remove(seat)
                nextThink[seat] = tick + maxOf(6, prof.reactionTicks / 2)
                applyFlick(seat, pl.plan.figure, pl.plan.pullX, pl.plan.pullY)
            }
        }
    }

    // ---- flicking ------------------------------------------------------------------------

    /**
     * Seat [seat] flicks its figure [figure] (0 is the keeper) by the pull ([pullX], [pullY]).
     * Offline only a human seat may act; networked only this device's own seat (a guest sends a
     * request and waits for the host's snapshot). Returns [SoccerReject.NONE] when it happened or was sent.
     */
    @Synchronized
    fun flick(seat: Int, figure: Int, pullX: Double, pullY: Double): SoccerReject {
        val s = state.value ?: return SoccerReject.NOT_PLAYING
        if (matchOver.value || s.matchOver || s.phase == SoccerPhase.GOAL) return SoccerReject.NOT_PLAYING
        if (!pullX.isFinite() || !pullY.isFinite() || figure !in 0 until s.figuresPerTeam) return SoccerReject.BAD_INPUT
        if (seat !in context.players.indices) return SoccerReject.NOT_YOUR_FIGURE
        if (isNetworked) {
            if (seat != context.localPlayerIndex) return SoccerReject.NOT_YOUR_FIGURE
            if (opponentLeft.value) return reject(seat, SoccerReject.OPPONENT_LEFT, figure)
            if (!isHost) {
                if (s.phase == SoccerPhase.COUNTDOWN) return reject(seat, SoccerReject.NOT_STARTED, figure)
                sendToHost(SoccerNetMessage.FlickRequest(figure, pullX, pullY))
                return SoccerReject.NONE
            }
        } else if (context.players[seat].isBot) return SoccerReject.NOT_YOUR_FIGURE
        val r = applyFlick(seat, figure, pullX, pullY)
        if (r == SoccerReject.NONE) { publish(); if (isNetworked) maybeBroadcast() } else reject(seat, r, figure)
        return r
    }

    private fun reject(seat: Int, reason: SoccerReject, figure: Int): SoccerReject {
        lastReject.value = SoccerRejectInfo(seat, reason, figure, ++rejectSerial)
        return reason
    }

    private fun applyFlick(seat: Int, figure: Int, px: Double, py: Double): SoccerReject {
        if (phase == SoccerPhase.COUNTDOWN) return SoccerReject.NOT_STARTED
        if (phase != SoccerPhase.PLAYING) return SoccerReject.NOT_PLAYING
        if (seat !in context.players.indices || figure !in 0 until world.figuresPerTeam) return SoccerReject.BAD_INPUT
        val team = SoccerRules.teamOfSeat(seat)
        if (SoccerRules.controllerSeat(team, figure, seats, gone) != seat) return SoccerReject.NOT_YOUR_FIGURE
        if (assist && (turnTeam != team || turnFlicked)) return SoccerReject.NOT_YOUR_TURN
        val idx = world.index(team, figure)
        if (!px.isFinite() || !py.isFinite()) return SoccerReject.BAD_INPUT
        if (!world.isReady(idx)) return SoccerReject.STILL_MOVING
        if (!world.flick(idx, px, py)) return SoccerReject.TOO_WEAK
        flickSerial++
        event = SoccerEvent.FLICK; eventTeam = team
        hint.value = null
        if (assist) {
            turnFlicked = true; settleTicks = 0; turnTicks = 0
            if (flicksLeft > 0) flicksLeft--
        }
        needSync = true
        return SoccerReject.NONE
    }

    /** Suggests a flick for [seat] (offline only): the computer's best play with no blur. */
    @Synchronized
    fun requestHint(seat: Int = if (this::context.isInitialized) context.localPlayerIndex.coerceAtLeast(0) else 0): SoccerHint? {
        if (isNetworked || state.value == null || phase != SoccerPhase.PLAYING) return null
        val team = SoccerRules.teamOfSeat(seat)
        if (assist && turnTeam != team) return null
        val figs = SoccerRules.figuresOfSeat(seat, seats, gone)
        val plan = SoccerCpu.decide(world, team, figs, SoccerCpu.PERFECT, Random(7)) ?: return null
        val h = SoccerHint(seat, team, plan)
        hint.value = h
        return h
    }

    // ---- state ---------------------------------------------------------------------------

    private fun publish() {
        state.value = buildState()
    }

    private fun buildState(): SoccerState {
        val bodies = List(world.count) { i ->
            SoccerBody(
                kind = if (i == 0) 0 else if (world.isKeeper(i)) 1 else 2,
                team = world.team(i), figure = world.figure(i),
                x = world.x[i], y = world.y[i], vx = world.vx[i], vy = world.vy[i],
                r = world.radius(i), ready = world.isReady(i)
            )
        }
        return SoccerState(
            players = context.players.map { SoccerPlayerState(it.playerId, it.displayName, it.isBot) },
            figuresPerTeam = world.figuresPerTeam, bodies = bodies, phase = phase, phaseTicks = phaseTicks,
            score = score.toList(), target = target, ticksLeft = ticksLeft, flicksLeft = flicksLeft,
            goldenGoal = goldenGoal, assist = assist, turnTeam = turnTeam, turnFlicked = turnFlicked,
            event = event, eventTeam = eventTeam,
            flickSerial = flickSerial, goalSerial = goalSerial, kickSerial = kickSerial, wallSerial = wallSerial,
            bumpSerial = bumpSerial, hitStrength = hitStrength, hitX = hitX, winnerTeam = winnerTeam
        )
    }

    /** Test hook: the world's fingerprint. */
    internal fun fingerprint(): Long = synchronized(lock) { world.fingerprint() }
    internal fun worldForTest(): SoccerWorld = world

    // ---- resume --------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) { captureSnapshot() }

    private fun captureSnapshot(): String? {
        return try {
            if (matchOver.value || state.value == null || phase == SoccerPhase.OVER || winnerTeam != null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            val flat = ArrayList<Double>()
            for (i in 0 until world.count) { flat += world.x[i]; flat += world.y[i] }
            soccerJson.encodeToString(
                SoccerSnapshot.serializer(),
                SoccerSnapshot(
                    players = context.players.map { SoccerSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                    bodies = flat, score = score.toList(), target = target, ticksLeft = ticksLeft, flicksLeft = flicksLeft,
                    goldenGoal = goldenGoal, assist = assist, turnTeam = turnTeam, sessionWins = sessionWins.value,
                    difficulty = difficulty.name, matchSpec = matchSpec, firstTeam = firstTeam,
                    flickSerial = flickSerial, goalSerial = goalSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): SoccerSnapshot? {
        return try {
            val s = soccerJson.decodeFromString(SoccerSnapshot.serializer(), text)
            val roster = context.players
            val n = 1 + 2 * SoccerRules.figuresFor(roster.size)
            val ok = s.players.size == roster.size &&
                s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
                s.bodies.size == 2 * n && s.bodies.all { it.isFinite() } &&
                (0 until n).all { i ->
                    s.bodies[2 * i] in -50.0..(SoccerRules.W + 50.0) && s.bodies[2 * i + 1] in -SoccerRules.GOAL_DEPTH..(SoccerRules.L + SoccerRules.GOAL_DEPTH)
                } &&
                s.score.size == 2 && s.score.all { it in 0..99 } &&
                s.target in 0..15 && (s.target == 0 || s.score.all { it < s.target }) &&
                s.ticksLeft >= -1 && s.flicksLeft >= -1 && s.turnTeam in 0..1 && s.firstTeam in 0..1 &&
                s.sessionWins.size == 2 && s.sessionWins.all { it >= 0 } &&
                s.flickSerial >= 0 && s.goalSerial >= 0
            if (ok) s else null
        } catch (_: Exception) {
            null
        }
    }

    /** Puts the match back as saved: figures where they were (at rest), a fresh countdown. */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decodeSnapshot(snapshot) ?: return false
        world = SoccerWorld(SoccerRules.figuresFor(context.players.size))
        for (i in 0 until world.count) { world.x[i] = s.bodies[2 * i]; world.y[i] = s.bodies[2 * i + 1] }
        matchOver.value = false
        score[0] = s.score[0]; score[1] = s.score[1]
        target = s.target; ticksLeft = s.ticksLeft; flicksLeft = s.flicksLeft
        goldenGoal = s.goldenGoal; assist = s.assist; turnTeam = s.turnTeam; firstTeam = s.firstTeam
        turnFlicked = false; settleTicks = 0; turnTicks = 0
        sessionWins.value = s.sessionWins
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
        matchSpec = s.matchSpec
        flickSerial = s.flickSerial; goalSerial = s.goalSerial
        winnerTeam = null
        event = SoccerEvent.START; eventTeam = null
        hint.value = null
        pending.clear(); nextThink.clear()
        if (assist) { phase = SoccerPhase.PLAYING; phaseTicks = 0 } else { phase = SoccerPhase.COUNTDOWN; phaseTicks = SoccerRules.RESET_TICKS + 40 }
        lastMs = -1
        publish()
        return true
    }

    // ---- networking ----------------------------------------------------------------------

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(SoccerNetMessage.RequestState)
        transport.onReconnected { synchronized(lock) { if (isHost) { needSync = true; broadcast() } else sendToHost(SoccerNetMessage.RequestState) } }
        transport.onPlayerLeft { id -> synchronized(lock) { handlePlayerLeft(id) } }
        transport.onPlayerJoined { id -> synchronized(lock) { handlePlayerJoined(id) } }
    }

    private fun recomputeOpponentLeft() {
        opponentLeft.value = (0..1).any { t ->
            val members = SoccerRules.membersOf(t, seats)
            members.isEmpty() || members.all { it in gone }
        }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0 || seat == context.localPlayerIndex) return
        gone += seat
        if (!isHost && seat == 0) opponentLeft.value = true else recomputeOpponentLeft()
        pending.remove(seat)
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        gone -= seat
        recomputeOpponentLeft()
        if (isHost && state.value != null) { needSync = true; broadcast() }
    }

    private fun toNetState(): SoccerNetState {
        val flat = ArrayList<Double>(world.count * 4)
        for (i in 0 until world.count) { flat += world.x[i]; flat += world.y[i]; flat += world.vx[i]; flat += world.vy[i] }
        return SoccerNetState(
            players = context.players.map { SoccerNetPlayer(it.playerId, it.displayName, it.isBot) },
            figuresPerTeam = world.figuresPerTeam, bodies = flat, cooldowns = world.cooldown.toList(),
            phase = phase.name, phaseTicks = phaseTicks, score = score.toList(), target = target,
            ticksLeft = ticksLeft, flicksLeft = flicksLeft, goldenGoal = goldenGoal, assist = assist,
            turnTeam = turnTeam, turnFlicked = turnFlicked, event = event, eventTeam = eventTeam,
            flickSerial = flickSerial, goalSerial = goalSerial, kickSerial = kickSerial, wallSerial = wallSerial,
            bumpSerial = bumpSerial, hitStrength = hitStrength, hitX = hitX, winnerTeam = winnerTeam,
            sessionWins = sessionWins.value, gone = gone.toList()
        )
    }

    private fun maybeBroadcast() {
        if (state.value == null) return
        val moving = !world.allSettled()
        if (needSync || (moving && tick - lastSyncTick >= 8)) broadcast()
    }

    private fun broadcast() {
        if (state.value == null || !isHost) return
        needSync = false
        lastSyncTick = tick
        stateVersion++
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = soccerJson.encodeToString(
            SoccerNetMessage.serializer(), SoccerNetMessage.StateSync(stateVersion, toNetState())
        ).toByteArray(Charsets.UTF_8)
        for ((seat, p) in context.players.withIndex()) {
            if (seat == 0 || p.isBot || seat in gone) continue
            context.transport.send(fromPlayerId = hostId, toPlayerId = p.playerId, payload = payload)
        }
    }

    private fun sendTo(playerId: String, message: SoccerNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = soccerJson.encodeToString(SoccerNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = playerId, payload = payload)
    }

    private fun sendToHost(message: SoccerNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = soccerJson.encodeToString(SoccerNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            soccerJson.decodeFromString(SoccerNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
            when (message) {
                is SoccerNetMessage.StateSync ->
                    if (!isHost && senderSeat == 0) applyRemoteState(message.version, message.state)
                is SoccerNetMessage.FlickRejected ->
                    if (!isHost && senderSeat == 0) {
                        val reason = SoccerReject.entries.firstOrNull { it.name == message.reason }
                        if (reason != null && reason != SoccerReject.NONE) reject(context.localPlayerIndex, reason, message.figure)
                    }
                is SoccerNetMessage.RequestState ->
                    if (isHost && senderSeat > 0 && state.value != null) { needSync = true; broadcast() }
                is SoccerNetMessage.FlickRequest ->
                    if (isHost && senderSeat > 0 && state.value != null && !matchOver.value && !context.players[senderSeat].isBot) {
                        val ok = message.pullX.isFinite() && message.pullY.isFinite() && message.figure in 0 until world.figuresPerTeam
                        val r = if (ok) applyFlick(senderSeat, message.figure, message.pullX, message.pullY) else SoccerReject.BAD_INPUT
                        if (r == SoccerReject.NONE) { publish(); maybeBroadcast() }
                        else sendTo(fromPlayerId, SoccerNetMessage.FlickRejected(r.name, message.figure))
                    }
                is SoccerNetMessage.RequestPlayAgain ->
                    if (isHost && senderSeat > 0 && state.value?.matchOver == true && !matchOver.value && !opponentLeft.value) startMatch()
            }
        }
    }

    /** Guest: adopts a strictly newer, well-formed host snapshot. */
    private fun applyRemoteState(version: Int, net: SoccerNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val fpt = SoccerRules.figuresFor(roster.size)
        val n = 1 + 2 * fpt
        val ph = SoccerPhase.entries.firstOrNull { it.name == net.phase }
        val valid = ph != null && net.figuresPerTeam == fpt &&
            net.players.size == roster.size && net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.bodies.size == 4 * n && net.bodies.all { it.isFinite() } &&
            (0 until n).all { i ->
                net.bodies[4 * i] in -300.0..(SoccerRules.W + 300.0) &&
                    net.bodies[4 * i + 1] in -300.0..(SoccerRules.L + 300.0) &&
                    abs(net.bodies[4 * i + 2]) <= SoccerRules.MAX_SPEED * 1.5 && abs(net.bodies[4 * i + 3]) <= SoccerRules.MAX_SPEED * 1.5
            } &&
            net.cooldowns.size == n && net.cooldowns.all { it in 0..SoccerRules.COOLDOWN_TICKS } &&
            net.phaseTicks in 0..2000 &&
            net.score.size == 2 && net.score.all { it in 0..99 } && net.target in 0..15 &&
            net.ticksLeft in -1..(15 * 60 * SoccerRules.STEPS_PER_SECOND) && net.flicksLeft in -1..200 &&
            net.turnTeam in 0..1 && (net.eventTeam == null || net.eventTeam in 0..1) && (net.winnerTeam == null || net.winnerTeam in 0..1) &&
            net.sessionWins.size == 2 && net.sessionWins.all { it in 0..999 } &&
            net.hitStrength in 0.0..1.0 && net.hitX.isFinite() &&
            net.flickSerial >= 0 && net.goalSerial >= 0 && net.kickSerial >= 0 && net.wallSerial >= 0 && net.bumpSerial >= 0 &&
            net.gone.all { it in roster.indices }
        if (!valid) return
        lastAppliedRemoteVersion = version
        for (i in 0 until n) {
            world.x[i] = net.bodies[4 * i]; world.y[i] = net.bodies[4 * i + 1]
            world.vx[i] = net.bodies[4 * i + 2]; world.vy[i] = net.bodies[4 * i + 3]
            world.cooldown[i] = net.cooldowns[i]
        }
        phase = ph!!
        phaseTicks = net.phaseTicks
        score[0] = net.score[0]; score[1] = net.score[1]
        target = net.target; ticksLeft = net.ticksLeft; flicksLeft = net.flicksLeft
        goldenGoal = net.goldenGoal; assist = net.assist
        turnTeam = net.turnTeam; turnFlicked = net.turnFlicked
        event = net.event; eventTeam = net.eventTeam
        flickSerial = net.flickSerial; goalSerial = net.goalSerial; kickSerial = net.kickSerial
        wallSerial = net.wallSerial; bumpSerial = net.bumpSerial
        hitStrength = net.hitStrength; hitX = net.hitX
        winnerTeam = net.winnerTeam
        sessionWins.value = net.sessionWins
        gone.clear(); gone.addAll(net.gone)
        recomputeOpponentLeftGuest()
        hint.value = null
        publish()
    }

    private fun recomputeOpponentLeftGuest() {
        if (opponentLeft.value && gone.isEmpty()) return
        recomputeOpponentLeft()
    }
}
