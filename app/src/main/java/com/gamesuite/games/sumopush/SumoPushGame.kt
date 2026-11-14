package com.gamesuite.games.sumopush

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

enum class SuPhase { COUNTDOWN, RUNNING, ROUND_OVER, MATCH_OVER }

/** One sumo as the screen draws it (world units, y down). */
data class SuView(
    val id: Int, val x: Double, val y: Double, val facingX: Double, val facingY: Double, val alive: Boolean,
    val charge: Float, val cooldown: Float, val dashing: Boolean, val heavy: Boolean, val sprint: Boolean, val fallMs: Int
)

/** The ring, replaced every step (and every frame while a round-over fall plays out). */
class SuBoard(val sumos: List<SuView>, val items: List<SumoItem>, val ringRadius: Double, val ringStart: Double, val timeMs: Long)

data class SuInfo(
    val phase: SuPhase = SuPhase.COUNTDOWN,
    val countdown: Int = 0,
    val round: Int = 1,
    val rounds: Int = 3,
    val winsNeeded: Int = 2,
    val wins: List<Int> = emptyList(),
    val alive: List<Boolean> = emptyList(),
    val roundWinner: Int = -1,
    val matchWinner: Int = -1,
    val paused: Boolean = false,
    /** True once the ring has started to close. */
    val shrinking: Boolean = false,
    val eventSerial: Int = 0
)

object SuLog {
    const val COUNT = "count"
    const val GO = "go"
    const val HIT = "hit"
    const val DASH = "dash"
    const val FELL = "fell"
    const val PICKUP = "pickup"
    const val ROUND_WIN = "round_win"
    const val ROUND_DRAW = "round_draw"
    const val MATCH_WIN = "match_win"
    const val MATCH_DRAW = "match_draw"
}

/** [x] and [y] are world positions in thousandths of a unit (a fall or a hit shows where it happened). */
data class SuLogged(val id: Int, val kind: String, val seat: Int, val value: Int, val x: Int = 0, val y: Int = 0)

@Serializable
private data class SuSeatSnap(val playerId: String, val isBot: Boolean)

@Serializable
private data class SuSnapshot(
    val seats: List<SuSeatSnap>,
    val arena: String,
    val rounds: Int,
    val powerUps: Boolean,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val wins: List<Int>,
    val roundsPlayed: Int,
    val difficulty: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)

private val suJson = Json { ignoreUnknownKeys = true }

/**
 * Sumo Push: 2 to 4 round toy sumos in a ring, each steered by its player's thumb on their own side of the
 * screen. Push the others out; the ring shrinks over time; hold the dash button to charge and release for a
 * heavy shove (then a cooldown). Last sumo in the ring wins the round, best of 3 or 5.
 *
 * Like [com.gamesuite.games.lighttrail.LightTrailGame] it is real time by simulation: [tick] turns the injected
 * clock into fixed 16 ms physics steps, tests call [advance] with no sleeping, and a seeded Random replays a
 * match. Computer seats are driven by [SumoCpu]. Same-screen only (no Nearby or Online in this version).
 * [snapshot] is taken between rounds; [restore] restarts the current round with a fresh countdown.
 */
class SumoPushGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "sumo-push"
    override val displayName = "Sumo Push"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val board = mutableStateOf<SuBoard?>(null)
    val info = mutableStateOf(SuInfo())
    val matchOver = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    var config: SumoConfig = SumoConfig()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** The player's game speed (Settings): above 1 slows the whole ring down, below 1 speeds it up. */
    var speedScale: Double = 1.0

    /** Tests may give the computer seats different levels. */
    internal val seatLevelOverride = arrayOfNulls<CpuDifficulty>(4)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var engine: SumoEngine? = null
    private var phase = SuPhase.COUNTDOWN
    private var paused = false
    private var wins = IntArray(0)
    private var roundsPlayed = 0
    private var matchWinner = -1
    private var roundWinner = -1
    private var phaseElapsed = 0L
    private var lastBeat = 0
    private var acc = 0.0
    private var afterOverMs = 0L
    private var lastClock = 0L
    private var cpus: Array<SumoCpu?> = emptyArray()
    private val inX = DoubleArray(4)
    private val inY = DoubleArray(4)
    private val held = BooleanArray(4)
    private val downEdge = BooleanArray(4)
    private val log = ArrayDeque<SuLogged>()
    private var logSerial = 0

    override fun init(context: GameContext) {
        this.context = context
        engine = null
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        board.value = null
        info.value = SuInfo()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private val seats: Int get() = context.players.size.coerceIn(SumoRules.MIN_PLAYERS, SumoRules.MAX_PLAYERS)
    fun isBotSeat(seat: Int): Boolean = this::context.isInitialized && context.players.getOrNull(seat)?.isBot == true
    val effectiveConfig: SumoConfig get() = config.copy(players = seats).sane()

    // ---- lifecycle -------------------------------------------------------------------------------------------

    override fun startMatch() = beginMatch(0, IntArray(seats))

    private fun beginMatch(restoredRoundsPlayed: Int, w: IntArray) {
        matchOver.value = false
        wins = w
        roundsPlayed = restoredRoundsPlayed
        matchWinner = -1; roundWinner = -1
        val e = SumoEngine(effectiveConfig, random)
        engine = e
        cpus = Array(seats) { if (isBotSeat(it)) SumoCpu(seatLevelOverride[it] ?: difficulty, random) else null }
        for (s in e.sumos) s.autoSteer = effectiveConfig.oneSwitch && !isBotSeat(s.id)
        inX.fill(0.0); inY.fill(0.0); held.fill(false); downEdge.fill(false)
        beginCountdown()
        lastClock = clock()
    }

    private fun beginCountdown() {
        val e = engine ?: return
        e.reset()
        for (s in e.sumos) s.autoSteer = effectiveConfig.oneSwitch && !isBotSeat(s.id)
        for (c in cpus) c?.reset()
        phase = SuPhase.COUNTDOWN
        phaseElapsed = 0L; lastBeat = 0; acc = 0.0; afterOverMs = 0L; roundWinner = -1
        pushEvent(SuLog.COUNT, -1, SumoRules.COUNTDOWN_BEATS)
        publish()
    }

    override fun pause() { if (engine != null && !matchOver.value) { paused = true; publishInfo() } }
    override fun resume() {}

    fun resumePlay() {
        if (!paused) return
        paused = false
        lastClock = clock()
        held.fill(false); downEdge.fill(false)
        publishInfo()
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    fun leaveSession() {
        if (matchOver.value) return
        val w = sessionWins.value
        val best = w.values.maxOrNull() ?: 0
        val leaders = context.players.count { (w[it.playerId] ?: 0) == best }
        endMatch(GameResult(scores = context.players.map {
            val s = w[it.playerId] ?: 0
            PlayerScore(it.playerId, s, isWinner = best > 0 && s == best && leaders == 1)
        }))
    }

    fun playAgain() {
        if (matchOver.value || phase != SuPhase.MATCH_OVER) return
        startMatch()
    }

    // ---- input -----------------------------------------------------------------------------------------------

    /** The stick or pad of a local seat (screen axes, length up to 1). Ignored for computer seats. */
    fun setInput(seat: Int, x: Double, y: Double): Boolean {
        if (seat !in 0 until 4 || seat >= seats || isBotSeat(seat) || engine == null) return false
        inX[seat] = x; inY[seat] = y
        return true
    }

    /** The dash button of a local seat went down or up. */
    fun setDash(seat: Int, down: Boolean): Boolean {
        if (seat !in 0 until 4 || seat >= seats || isBotSeat(seat) || engine == null) return false
        if (down) { downEdge[seat] = true; held[seat] = true } else held[seat] = false
        return true
    }

    // ---- the clock -------------------------------------------------------------------------------------------

    fun tick() {
        val now = clock()
        val dt = (now - lastClock).coerceIn(0L, 250L)
        lastClock = now
        if (paused) return
        advance(dt)
    }

    /** Advances [dtMs] milliseconds of real time (tests call this directly). */
    fun advance(dtMs: Long) {
        val e = engine ?: return
        if (paused || matchOver.value || dtMs <= 0L) return
        when (phase) {
            SuPhase.COUNTDOWN -> {
                phaseElapsed += dtMs
                val total = SumoRules.COUNTDOWN_BEATS * SumoRules.COUNTDOWN_BEAT_MS
                if (phaseElapsed >= total) {
                    phase = SuPhase.RUNNING
                    acc = 0.0
                    pushEvent(SuLog.GO, -1, 0)
                    publish()
                } else {
                    val beat = SumoRules.COUNTDOWN_BEATS - (phaseElapsed / SumoRules.COUNTDOWN_BEAT_MS).toInt()
                    if (beat != lastBeat && beat < SumoRules.COUNTDOWN_BEATS) {
                        lastBeat = beat
                        pushEvent(SuLog.COUNT, -1, beat)
                        publishInfo()
                    }
                }
            }
            SuPhase.RUNNING -> {
                acc += dtMs / speedScale.coerceIn(0.5, 3.0)
                var guard = 0
                while (acc >= SumoRules.STEP_MS && phase == SuPhase.RUNNING && guard++ < 24) {
                    acc -= SumoRules.STEP_MS
                    stepOnce(e)
                }
                if (phase == SuPhase.RUNNING) publish()
            }
            SuPhase.ROUND_OVER -> {
                phaseElapsed += dtMs
                afterOverMs += dtMs
                if (phaseElapsed >= SumoRules.ROUND_BREAK_MS) beginCountdown() else publish()
            }
            SuPhase.MATCH_OVER -> { afterOverMs += dtMs; publish() }
        }
    }

    private fun stepOnce(e: SumoEngine) {
        for (s in e.sumos) {
            val cpu = cpus.getOrNull(s.id)
            if (cpu != null) { cpu.update(e, s.id); continue }
            if (!s.autoSteer) e.setInput(s.id, inX[s.id], inY[s.id])
            if (downEdge[s.id]) { e.setCharging(s.id, true); downEdge[s.id] = false }
            if (!held[s.id]) e.setCharging(s.id, false)
        }
        val events = e.step()
        for (ev in events) when (ev) {
            is SumoEvent.Hit -> if (ev.strength > 0.25) pushEvent(SuLog.HIT, ev.a, (ev.strength * 100).toInt(), milli(e.sumos[ev.a].x), milli(e.sumos[ev.a].y))
            is SumoEvent.Dash -> pushEvent(SuLog.DASH, ev.seat, (ev.charge * 100).toInt())
            is SumoEvent.Fell -> pushEvent(SuLog.FELL, ev.seat, 0, milli(e.sumos[ev.seat].x), milli(e.sumos[ev.seat].y))
            is SumoEvent.Pickup -> pushEvent(SuLog.PICKUP, ev.seat, ev.kind.ordinal)
        }
        if (e.over) endRound(e)
    }

    private fun milli(v: Double) = (v * 1000).toInt()

    private fun endRound(e: SumoEngine) {
        roundsPlayed++
        roundWinner = e.winner
        if (roundWinner >= 0) { wins[roundWinner]++; pushEvent(SuLog.ROUND_WIN, roundWinner, wins[roundWinner]) }
        else pushEvent(SuLog.ROUND_DRAW, -1, 0)
        val need = SumoRules.winsNeeded(effectiveConfig.rounds)
        val decided = wins.any { it >= need } || roundsPlayed >= SumoRules.maxRounds(effectiveConfig.rounds)
        afterOverMs = 0L
        phaseElapsed = 0L
        if (decided) {
            matchWinner = decideMatch()
            phase = SuPhase.MATCH_OVER
            if (matchWinner >= 0) {
                val id = context.players[matchWinner].playerId
                sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
                pushEvent(SuLog.MATCH_WIN, matchWinner, wins[matchWinner])
            } else {
                sessionDraws.value = sessionDraws.value + 1
                pushEvent(SuLog.MATCH_DRAW, -1, 0)
            }
        } else {
            phase = SuPhase.ROUND_OVER
        }
        publish()
    }

    /** Most round wins; a tie is a draw (-1), since a round can be decided by nothing finer than winning it. */
    fun decideMatch(): Int {
        val best = wins.maxOrNull() ?: return -1
        val top = wins.indices.filter { wins[it] == best }
        return if (top.size == 1) top[0] else -1
    }

    // ---- publishing ------------------------------------------------------------------------------------------

    private fun pushEvent(kind: String, seat: Int, value: Int, x: Int = 0, y: Int = 0) {
        log.addLast(SuLogged(++logSerial, kind, seat, value, x, y))
        while (log.size > 64) log.removeFirst()
    }

    fun eventsSince(id: Int): List<SuLogged> = log.filter { it.id > id }

    private fun publish() {
        val e = engine ?: return
        val views = e.sumos.map { s ->
            SuView(
                s.id, s.x, s.y, s.facingX, s.facingY, s.alive,
                charge = (s.chargeMs.toFloat() / SumoRules.CHARGE_MAX_MS).coerceIn(0f, 1f),
                cooldown = (s.cooldownMs.toFloat() / effectiveConfig.cooldownMs).coerceIn(0f, 1f),
                dashing = s.dashMs > 0, heavy = s.heavyMs > 0, sprint = s.sprintMs > 0,
                fallMs = if (s.alive) 0 else (e.timeMs - s.fellAtMs + afterOverMs).toInt().coerceIn(0, SumoRules.FALL_MS)
            )
        }
        board.value = SuBoard(views, e.items.toList(), e.ringRadius(), effectiveConfig.ringStart, e.timeMs)
        publishInfo()
    }

    private fun publishInfo() {
        val e = engine ?: return
        info.value = SuInfo(
            phase = phase,
            countdown = if (phase == SuPhase.COUNTDOWN) (SumoRules.COUNTDOWN_BEATS - (phaseElapsed / SumoRules.COUNTDOWN_BEAT_MS).toInt()).coerceIn(1, SumoRules.COUNTDOWN_BEATS) else 0,
            round = (roundsPlayed + if (phase == SuPhase.RUNNING || phase == SuPhase.COUNTDOWN) 1 else 0).coerceAtLeast(1),
            rounds = effectiveConfig.rounds,
            winsNeeded = SumoRules.winsNeeded(effectiveConfig.rounds),
            wins = wins.toList(),
            alive = e.sumos.map { it.alive },
            roundWinner = roundWinner,
            matchWinner = matchWinner,
            paused = paused,
            shrinking = phase == SuPhase.RUNNING && e.ringRadius() < effectiveConfig.ringStart - 1e-6,
            eventSerial = logSerial
        )
    }

    // ---- resume ----------------------------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        if (engine == null || matchOver.value || phase == SuPhase.MATCH_OVER || !this::context.isInitialized) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else {
            val c = effectiveConfig
            suJson.encodeToString(
                SuSnapshot.serializer(),
                SuSnapshot(
                    seats = context.players.map { SuSeatSnap(it.playerId, it.isBot) },
                    arena = c.arena.name, rounds = c.rounds, powerUps = c.powerUps, relaxed = c.relaxed, oneSwitch = c.oneSwitch,
                    wins = wins.toList(), roundsPlayed = roundsPlayed, difficulty = difficulty.name,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
                )
            )
        }
    } catch (_: Exception) { null }

    override fun restore(snapshot: String): Boolean {
        val s = try { suJson.decodeFromString(SuSnapshot.serializer(), snapshot) } catch (_: Exception) { return false }
        val roster = context.players
        val n = roster.size
        val valid = n in 2..4 && s.seats.size == n && s.wins.size == n &&
            s.seats.indices.all { s.seats[it].playerId == roster[it].playerId && s.seats[it].isBot == roster[it].isBot } &&
            SumoArena.entries.any { it.name == s.arena } && CpuDifficulty.entries.any { it.name == s.difficulty } && (s.rounds == 3 || s.rounds == 5) &&
            s.roundsPlayed in 0 until SumoRules.maxRounds(s.rounds) &&
            s.wins.all { it in 0..s.roundsPlayed } && s.wins.sum() <= s.roundsPlayed &&
            s.wins.all { it < SumoRules.winsNeeded(s.rounds) } && s.sessionDraws >= 0 &&
            s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
        if (!valid) return false
        config = SumoConfig(n, SumoArena.fromId(s.arena), s.rounds, s.powerUps, s.relaxed, s.oneSwitch).sane()
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
        sessionWins.value = s.sessionWins
        sessionDraws.value = s.sessionDraws
        beginMatch(s.roundsPlayed, s.wins.toIntArray())
        return true
    }
}
