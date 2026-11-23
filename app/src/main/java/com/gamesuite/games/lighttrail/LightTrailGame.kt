package com.gamesuite.games.lighttrail

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

enum class LtPhase { COUNTDOWN, RUNNING, ROUND_OVER, MATCH_OVER }

/** One rider as the screen draws it. */
data class LtRiderView(val id: Int, val x: Int, val y: Int, val dir: LtDir, val alive: Boolean, val gapLeft: Int, val boostLeft: Int, val cause: LtCause?)

/** The arena, replaced after every step. [cells] is a private copy: 0 empty, otherwise rider id + 1. */
class LtBoard(val size: Int, val cells: IntArray, val riders: List<LtRiderView>, val items: List<LtItem>, val step: Int)

/** Everything else the screen shows. Equal values do not recompose anything. */
data class LtInfo(
    val phase: LtPhase = LtPhase.COUNTDOWN,
    val countdown: Int = 0,
    val round: Int = 1,
    val rounds: Int = 3,
    val winsNeeded: Int = 2,
    val wins: List<Int> = emptyList(),
    val alive: List<Boolean> = emptyList(),
    val roundWinner: Int = -1,
    val matchWinner: Int = -1,
    val paused: Boolean = false,
    val armed: LtTurn = LtTurn.RIGHT,
    val eventSerial: Int = 0
)

object LtLog {
    const val COUNT = "count"
    const val GO = "go"
    const val CRASH = "crash"
    const val PICKUP = "pickup"
    const val AUTO_AVOID = "auto_avoid"
    const val ROUND_WIN = "round_win"
    const val ROUND_DRAW = "round_draw"
    const val MATCH_WIN = "match_win"
    const val MATCH_DRAW = "match_draw"
}

/** An entry of the event log the screen turns into sound, haptics, particles and TalkBack announcements. */
data class LtLogged(val id: Int, val kind: String, val seat: Int, val value: Int, val x: Int = 0, val y: Int = 0)

@Serializable
private data class LtSeatSnap(val playerId: String, val isBot: Boolean)

@Serializable
private data class LtSnapshot(
    val seats: List<LtSeatSnap>,
    val arena: String,
    val rounds: Int,
    val powerUps: Boolean,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val wins: List<Int>,
    val survival: List<Int>,
    val roundsPlayed: Int,
    val difficulty: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
)

private val ltJson = Json { ignoreUnknownKeys = true }

/**
 * Light-Trail Duel: 2 to 4 riders on one screen, each leaving a wall behind; hit any wall or the edge and you
 * are out, the last rider standing takes the round, best of 3 or 5.
 *
 * REAL TIME BY SIMULATION: the screen calls [tick] every frame; the game turns the injected [clock] into
 * fixed simulation steps ([LightTrailEngine.step]) so a fake clock and a seeded Random replay a match exactly,
 * and tests drive it with [advance] without any sleeping. Computer riders decide once per step through
 * [LightTrailCpu] and act [LightTrailCpu.reactionSteps] steps later.
 *
 * Same-screen only in this version: [PlayMode.SINGLE_DEVICE_PASS_AND_PLAY] and [PlayMode.SINGLE_PLAYER_VS_BOT]
 * (computers fill the empty seats). There is no Nearby or Online mode yet.
 *
 * RESUME: [snapshot] captures the match between rounds (scores, settings, roster); [restore] always starts the
 * current round afresh with a new countdown, so a round that was running when the app was killed restarts.
 */
class LightTrailGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "light-trail"
    override val displayName = "Light-Trail Duel"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val board = mutableStateOf<LtBoard?>(null)
    val info = mutableStateOf(LtInfo())
    val matchOver = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** Pre-set by the screen from the Hub before [init]. */
    var config: LightTrailConfig = LightTrailConfig()
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** The player's game speed (Settings): above 1 slows the riders, below 1 speeds them up. */
    var speedScale: Double = 1.0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var engine: LightTrailEngine? = null
    private var phase = LtPhase.COUNTDOWN
    private var paused = false
    private var wins = IntArray(0)
    private var survival = IntArray(0)
    private var roundsPlayed = 0
    private var matchWinner = -1
    private var roundWinner = -1
    private var phaseElapsed = 0L
    private var lastBeat = 0
    private var acc = 0L
    private var lastClock = 0L
    private val delayed = Array(4) { ArrayDeque<LtTurn?>() }

    /** After a computer rider decides to turn, further decisions wait until that turn has been applied (they would see the stale heading). */
    private val hold = IntArray(4)
    private val log = ArrayDeque<LtLogged>()
    private var logSerial = 0

    override fun init(context: GameContext) {
        this.context = context
        engine = null
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        board.value = null
        info.value = LtInfo()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private val seats: Int get() = context.players.size.coerceIn(LightTrailRules.MIN_PLAYERS, LightTrailRules.MAX_PLAYERS)
    fun isBotSeat(seat: Int): Boolean = this::context.isInitialized && context.players.getOrNull(seat)?.isBot == true
    val effectiveConfig: LightTrailConfig get() = config.copy(players = seats).sane()

    // ---- lifecycle -------------------------------------------------------------------------------------------

    override fun startMatch() {
        beginMatch(restoredRoundsPlayed = 0, w = IntArray(seats), surv = IntArray(seats))
    }

    private fun beginMatch(restoredRoundsPlayed: Int, w: IntArray, surv: IntArray) {
        matchOver.value = false
        wins = w; survival = surv
        roundsPlayed = restoredRoundsPlayed
        matchWinner = -1; roundWinner = -1
        engine = LightTrailEngine(effectiveConfig, random)
        beginCountdown()
        lastClock = clock()
    }

    private fun beginCountdown() {
        val e = engine ?: return
        e.reset()
        for (q in delayed) q.clear()
        hold.fill(0)
        phase = LtPhase.COUNTDOWN
        phaseElapsed = 0L; lastBeat = 0; acc = 0L; roundWinner = -1
        pushEvent(LtLog.COUNT, -1, LightTrailRules.COUNTDOWN_BEATS)
        publish()
    }

    override fun pause() { if (engine != null && !matchOver.value) { paused = true; publishInfo() } }
    override fun resume() {}

    /** The player's own "carry on" after a pause. */
    fun resumePlay() {
        if (!paused) return
        paused = false
        lastClock = clock()
        publishInfo()
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, reporting matches won (only a strict leader wins). */
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

    /** "Play again" on the result panel: a fresh match, session tally kept. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value || phase != LtPhase.MATCH_OVER) return
        startMatch()
    }

    // ---- input -----------------------------------------------------------------------------------------------

    /** A local seat steers. Returns false when nothing was queued (a computer seat, an eliminated rider, a full queue). */
    fun turn(seat: Int, cmd: LtTurn): Boolean {
        val e = engine ?: return false
        if (paused || matchOver.value || isBotSeat(seat)) return false
        if (phase != LtPhase.RUNNING && phase != LtPhase.COUNTDOWN) return false
        return e.turn(seat, cmd)
    }

    // ---- the clock -------------------------------------------------------------------------------------------

    /** Called every frame by the screen. */
    fun tick() {
        val now = clock()
        val dt = (now - lastClock).coerceIn(0L, 250L)
        lastClock = now
        if (paused) return
        advance(dt)
    }

    /** Advances the simulation by [dtMs] milliseconds of game time (tests call this directly). */
    fun advance(dtMs: Long) {
        val e = engine ?: return
        if (paused || matchOver.value || dtMs <= 0L) return
        when (phase) {
            LtPhase.COUNTDOWN -> {
                phaseElapsed += dtMs
                val total = LightTrailRules.COUNTDOWN_BEATS * LightTrailRules.COUNTDOWN_BEAT_MS
                if (phaseElapsed >= total) {
                    phase = LtPhase.RUNNING
                    acc = 0L
                    pushEvent(LtLog.GO, -1, 0)
                    publish()
                } else {
                    val beat = LightTrailRules.COUNTDOWN_BEATS - (phaseElapsed / LightTrailRules.COUNTDOWN_BEAT_MS).toInt()
                    if (beat != lastBeat && beat < LightTrailRules.COUNTDOWN_BEATS) {
                        lastBeat = beat
                        pushEvent(LtLog.COUNT, -1, beat)
                        publishInfo()
                    }
                }
            }
            LtPhase.RUNNING -> {
                acc += dtMs
                val stepMs = stepMillis()
                var guard = 0
                while (acc >= stepMs && phase == LtPhase.RUNNING && guard++ < 8) {
                    acc -= stepMs
                    stepOnce(e)
                }
            }
            LtPhase.ROUND_OVER -> {
                phaseElapsed += dtMs
                if (phaseElapsed >= LightTrailRules.ROUND_BREAK_MS) beginCountdown()
            }
            LtPhase.MATCH_OVER -> {}
        }
    }

    fun stepMillis(): Long = (effectiveConfig.stepMs * speedScale.coerceIn(0.5, 3.0)).toLong().coerceAtLeast(40L)

    /** Tests may give the computer seats different levels. */
    internal val seatLevelOverride = arrayOfNulls<CpuDifficulty>(4)

    private fun level(seat: Int): CpuDifficulty = seatLevelOverride[seat] ?: difficulty

    private fun stepOnce(e: LightTrailEngine) {
        for (r in e.riders) {
            if (!r.alive || !isBotSeat(r.id)) continue
            val delay = LightTrailCpu.reactionSteps(level(r.id))
            val q = delayed[r.id]
            val decision = if (hold[r.id] > 0) { hold[r.id]--; null } else LightTrailCpu.decide(e, r.id, level(r.id), random)
            if (decision != null) hold[r.id] = delay
            q.addLast(decision)
            while (q.size > delay + 1) q.removeFirst()
            if (q.size == delay + 1) q.removeFirst()?.let { e.turn(r.id, it) }
        }
        val events = e.step()
        for (ev in events) when (ev) {
            is LtEvent.Crash -> pushEvent(LtLog.CRASH, ev.rider, ev.cause.ordinal, ev.x, ev.y)
            is LtEvent.Pickup -> pushEvent(LtLog.PICKUP, ev.rider, ev.kind.ordinal)
            is LtEvent.AutoAvoid -> pushEvent(LtLog.AUTO_AVOID, ev.rider, 0)
        }
        if (e.over) endRound(e) else publish()
    }

    private fun endRound(e: LightTrailEngine) {
        roundsPlayed++
        for (r in e.riders) survival[r.id] += if (r.diedAtStep >= 0) r.diedAtStep else e.step
        roundWinner = e.winner
        if (roundWinner >= 0) { wins[roundWinner]++; pushEvent(LtLog.ROUND_WIN, roundWinner, wins[roundWinner]) }
        else pushEvent(LtLog.ROUND_DRAW, -1, 0)
        val need = LightTrailRules.winsNeeded(effectiveConfig.rounds)
        val decided = wins.any { it >= need } || roundsPlayed >= LightTrailRules.maxRounds(effectiveConfig.rounds)
        if (decided) {
            matchWinner = decideMatch()
            phase = LtPhase.MATCH_OVER
            val ctx = context
            if (matchWinner >= 0) {
                val id = ctx.players[matchWinner].playerId
                sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
                pushEvent(LtLog.MATCH_WIN, matchWinner, wins[matchWinner])
            } else {
                sessionDraws.value = sessionDraws.value + 1
                pushEvent(LtLog.MATCH_DRAW, -1, 0)
            }
        } else {
            phase = LtPhase.ROUND_OVER
            phaseElapsed = 0L
        }
        publish()
    }

    /** Most round wins; ties go to the rider who survived longest in total; still tied is a draw (-1). */
    fun decideMatch(): Int {
        val best = wins.maxOrNull() ?: return -1
        val top = wins.indices.filter { wins[it] == best }
        if (top.size == 1) return top[0]
        val bestSurv = top.maxOf { survival[it] }
        val t2 = top.filter { survival[it] == bestSurv }
        return if (t2.size == 1) t2[0] else -1
    }

    // ---- publishing ------------------------------------------------------------------------------------------

    private fun pushEvent(kind: String, seat: Int, value: Int, x: Int = 0, y: Int = 0) {
        log.addLast(LtLogged(++logSerial, kind, seat, value, x, y))
        while (log.size > 64) log.removeFirst()
    }

    /** Events newer than [id], oldest first. */
    fun eventsSince(id: Int): List<LtLogged> = log.filter { it.id > id }

    private fun publish() {
        val e = engine ?: return
        board.value = LtBoard(
            e.size, e.cells.copyOf(),
            e.riders.map { LtRiderView(it.id, it.x, it.y, it.dir, it.alive, it.gapLeft, it.boostLeft, it.cause) },
            e.items.toList(), e.step
        )
        publishInfo()
    }

    private fun publishInfo() {
        val e = engine ?: return
        info.value = LtInfo(
            phase = phase,
            countdown = if (phase == LtPhase.COUNTDOWN) (LightTrailRules.COUNTDOWN_BEATS - (phaseElapsed / LightTrailRules.COUNTDOWN_BEAT_MS).toInt()).coerceIn(1, LightTrailRules.COUNTDOWN_BEATS) else 0,
            round = (roundsPlayed + if (phase == LtPhase.RUNNING || phase == LtPhase.COUNTDOWN) 1 else 0).coerceAtLeast(1),
            rounds = effectiveConfig.rounds,
            winsNeeded = LightTrailRules.winsNeeded(effectiveConfig.rounds),
            wins = wins.toList(),
            alive = e.riders.map { it.alive },
            roundWinner = roundWinner,
            matchWinner = matchWinner,
            paused = paused,
            armed = if (effectiveConfig.oneSwitch) e.armedSide() else LtTurn.RIGHT,
            eventSerial = logSerial
        )
    }

    // ---- resume ----------------------------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        if (engine == null || matchOver.value || phase == LtPhase.MATCH_OVER || !this::context.isInitialized) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) null
        else {
            val c = effectiveConfig
            ltJson.encodeToString(
                LtSnapshot.serializer(),
                LtSnapshot(
                    seats = context.players.map { LtSeatSnap(it.playerId, it.isBot) },
                    arena = c.arena.name, rounds = c.rounds, powerUps = c.powerUps, relaxed = c.relaxed, oneSwitch = c.oneSwitch,
                    wins = wins.toList(), survival = survival.toList(), roundsPlayed = roundsPlayed, difficulty = difficulty.name,
                    sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
                )
            )
        }
    } catch (_: Exception) { null }

    override fun restore(snapshot: String): Boolean {
        val s = try { ltJson.decodeFromString(LtSnapshot.serializer(), snapshot) } catch (_: Exception) { return false }
        val roster = context.players
        val n = roster.size
        val valid = n in 2..4 && s.seats.size == n && s.wins.size == n && s.survival.size == n &&
            s.seats.indices.all { s.seats[it].playerId == roster[it].playerId && s.seats[it].isBot == roster[it].isBot } &&
            LtArena.entries.any { it.name == s.arena } && CpuDifficulty.entries.any { it.name == s.difficulty } && (s.rounds == 3 || s.rounds == 5) &&
            s.roundsPlayed in 0 until LightTrailRules.maxRounds(s.rounds) &&
            s.wins.all { it in 0..s.roundsPlayed } && s.wins.sum() <= s.roundsPlayed &&
            s.wins.all { it < LightTrailRules.winsNeeded(s.rounds) } &&
            s.survival.all { it in 0..1_000_000 } && s.sessionDraws >= 0 &&
            s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } }
        if (!valid) return false
        config = LightTrailConfig(n, LtArena.fromId(s.arena), s.rounds, s.powerUps, s.relaxed, s.oneSwitch).sane()
        difficulty = CpuDifficulty.entries.firstOrNull { it.name == s.difficulty } ?: difficulty
        sessionWins.value = s.sessionWins
        sessionDraws.value = s.sessionDraws
        beginMatch(s.roundsPlayed, s.wins.toIntArray(), s.survival.toIntArray())
        return true
    }
}
