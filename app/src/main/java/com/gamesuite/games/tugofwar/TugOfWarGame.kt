package com.gamesuite.games.tugofwar

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.games.partypack.TapTugBotForm
import com.gamesuite.games.partypack.TapTugMatchTally
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

enum class TugPhase { COUNTDOWN, PULL, ROUND_END, OVER }

/** What a pull was: a plain tap, a charged heave on release, or an automatic hold-to-play pull. */
object TugPull {
    const val NONE = ""
    const val TAP = "tap"
    const val HEAVE = "heave"
    const val AUTO = "auto"
}

/** What the last change to the rope was. */
object TugEvent {
    const val START = "start"
    const val GO = "go"
    const val ROUND_WIN = "round_win"
    const val ROUND_DRAW = "round_draw"
    const val MATCH_WIN = "match_win"
    const val MATCH_DRAW = "match_draw"
}

/** Why a pull did nothing. [NONE] means it counted. */
enum class TugReject { NONE, NOT_PULLING, TOO_FAST, BOT_SEAT, BAD_SEAT }

/** One puller as the screen sees it. [pullSerial] only ever grows and keys the pad's animation and sound. */
data class TugSeatState(
    val playerId: String,
    val name: String,
    val isBot: Boolean,
    val side: Int,
    val stamina: Int,
    val held: Boolean,
    val braced: Boolean,
    val charge: Float,
    val tired: Boolean,
    val pullSerial: Int,
    val pullKind: String,
    val pullPower: Float
)

/** Everything a device needs to draw the rope. [eventSerial] only ever grows. */
data class TugState(
    val seats: List<TugSeatState>,
    val sides: Int,
    val layout: String,
    val dirX: List<Float>,
    val dirY: List<Float>,
    val markerX: Float,
    val markerY: Float,
    val advance: List<Float>,
    val phase: String,
    val round: Int,
    val rounds: Int,
    val countdownMs: Int,
    val roundLeftMs: Int,
    val relaxed: Boolean,
    val holdToPlay: Boolean,
    val event: String,
    val eventSide: Int,
    val eventSerial: Int,
    val roundWinnerSide: Int,
    val tiebreak: Boolean,
    val over: Boolean,
    val winnerSide: Int,
    val winnerSeats: List<Int>,
    val sideWins: List<Int>,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    val phaseValue: TugPhase get() = TugPhase.entries.firstOrNull { it.name == phase } ?: TugPhase.OVER
}

@Serializable
data class TugPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

@Serializable
private data class TugSnapshot(
    val players: List<TugPlayer>,
    val layout: String,
    val wins: List<Int>,
    val played: Int,
    val rounds: Int,
    val relaxed: Boolean,
    val hold: Boolean,
    val difficulty: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val eventSerial: Int = 0
)

private val tugJson = Json { ignoreUnknownKeys = true }

/**
 * Tug of War: two to four people pull ONE marker on ONE screen, each with a pad in their own zone, all at once.
 * See [TugRules] for the numbers: pulls cost stamina, so mashing runs dry, and the pull that wins is a held
 * finger released (a HEAVE). Teams (1v1, 1v2, 2v2, 1v3) pull along a rope; in a free-for-all the marker is pulled
 * across an arena toward each player's corner. The first to bring the marker over their line takes the round.
 *
 * Like Tap Race this is a fixed-step simulation on an injected clock (tests need no sleeping) with the
 * computer pullers driven from the same steps. SAME SCREEN ONLY in this version: no Nearby, no Online.
 * Resume is between rounds: [restore] starts the current round again.
 */
class TugOfWarGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "tug-of-war"
    override val displayName = "Tug of War"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<TugState?>(null)
    val matchOver = mutableStateOf(false)

    /** Pre-set by the screen from the Hub before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var layout: TugLayout = TugLayout.TEAMS
    var rounds: Int = TugRules.DEFAULT_ROUNDS
    var relaxed: Boolean = false
    var holdToPlay: Boolean = false

    internal val seatLevelOverride = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private val sessionWins = HashMap<String, Int>()
    private var sessionDraws = 0
    private var seatCount = 2
    private var sideOf = intArrayOf(0, 1)
    private var sideCount = 2
    private var dirs: List<Pair<Double, Double>> = emptyList()
    private var boosts: List<Double> = emptyList()
    private var tally = TapTugMatchTally(2, 1)

    private var phase = TugPhase.OVER
    private var simT = 0L
    private var lastSim = -1L
    private var phaseEnd = 0L
    private var goT = 0L
    private var round = 1
    private var mx = 0.0
    private var my = 0.0

    private var stamina = DoubleArray(0)
    private var lastPressAt = LongArray(0)
    private var lastHeaveAt = LongArray(0)
    private var held = Array(0) { HashMap<Long, Long>() }
    private var nextAuto = LongArray(0)
    private var pullSerial = IntArray(0)
    private var pullKind = Array(0) { TugPull.NONE }
    private var pullPower = DoubleArray(0)

    private var event = TugEvent.START
    private var eventSide = -1
    private var eventSerial = 0
    private var roundWinnerSide = -1
    private var over = false
    private var winnerSide = -1

    private class Bot {
        var reactAt = 0L
        var nextAt = 0L
        var holdUntil = -1L
        var burst = 0
    }

    private var bots = emptyArray<Bot?>()
    private var roundLevels = emptyArray<CpuDifficulty>()

    override fun init(context: GameContext) {
        synchronized(lock) {
            this.context = context
            seatCount = context.players.size.coerceIn(2, 4)
            matchOver.value = false
            sessionWins.clear()
            context.players.forEach { sessionWins[it.playerId] = 0 }
            sessionDraws = 0
            state.value = null
            phase = TugPhase.OVER
            over = false
            winnerSide = -1
            setupSides()
            tally = TapTugMatchTally(sideCount, rounds.coerceIn(1, 9))
        }
    }

    private fun setupSides() {
        sideOf = TugSides.sideOf(layout, seatCount)
        sideCount = sideOf.max() + 1
        dirs = TugSides.directions(layout, seatCount)
        boosts = TugSides.boosts(layout, seatCount)
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun levelOf(seat: Int): CpuDifficulty = seatLevelOverride[seat] ?: difficulty
    private fun isBotSeat(seat: Int): Boolean = context.players.getOrNull(seat)?.isBot == true

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        over = false
        winnerSide = -1
        rounds = rounds.coerceIn(1, 9)
        setupSides()
        tally = TapTugMatchTally(sideCount, rounds)
        round = 1
        lastSim = clock()
        beginRound()
        commit()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the session, reporting matches won (a side that leads outright wins). */
    fun leaveSession() {
        if (matchOver.value) return
        val best = sessionWins.values.maxOrNull() ?: 0
        val leaderSeats = context.players.indices.filter { (sessionWins[context.players[it].playerId] ?: 0) == best }
        val oneSide = leaderSeats.map { sideOf.getOrElse(it) { it } }.toSet().size == 1
        val scores = context.players.mapIndexed { i, p ->
            val w = sessionWins[p.playerId] ?: 0
            PlayerScore(playerId = p.playerId, score = w, isWinner = best > 0 && w == best && oneSide && i in leaderSeats)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    private fun beginRound() {
        mx = 0.0; my = 0.0
        stamina = DoubleArray(seatCount) { TugRules.MAX_STAMINA }
        lastPressAt = LongArray(seatCount) { -100_000L }
        lastHeaveAt = LongArray(seatCount) { -100_000L }
        held = Array(seatCount) { HashMap<Long, Long>() }
        nextAuto = LongArray(seatCount)
        if (pullSerial.size != seatCount) {
            pullSerial = IntArray(seatCount); pullKind = Array(seatCount) { TugPull.NONE }; pullPower = DoubleArray(seatCount)
        } else {
            pullKind.fill(TugPull.NONE); pullPower.fill(0.0)
        }
        roundWinnerSide = -1
        phase = TugPhase.COUNTDOWN
        phaseEnd = simT + TugRules.COUNTDOWN_MS
        goT = phaseEnd
        roundLevels = Array(seatCount) { seat -> if (isBotSeat(seat)) TapTugBotForm.roundLevel(levelOf(seat), random) else levelOf(seat) }
        bots = Array(seatCount) { seat ->
            if (isBotSeat(seat)) Bot().also {
                it.reactAt = goT + jit(TugRules.bot(roundLevels[seat]).reactionMs.toLong(), 0.25)
                it.nextAt = it.reactAt
            } else null
        }
        setEvent(TugEvent.START, -1)
    }

    private fun setEvent(kind: String, side: Int) {
        event = kind; eventSide = side; eventSerial++
    }

    private fun jit(base: Long, frac: Double): Long = (base * (1.0 - frac + random.nextDouble() * 2 * frac)).toLong().coerceAtLeast(0L)

    // ---- the clock ---------------------------------------------------------------------------

    @Synchronized
    fun tick(): Boolean {
        if (state.value == null || matchOver.value) return false
        advanceTo(clock())
        commit()
        return true
    }

    private fun advanceTo(now: Long) {
        if (phase == TugPhase.OVER) return
        if (lastSim < 0) lastSim = now
        var steps = 0
        while (lastSim + TugRules.STEP_MS <= now && steps < TugRules.MAX_CATCHUP_STEPS && phase != TugPhase.OVER) {
            lastSim += TugRules.STEP_MS
            simT += TugRules.STEP_MS
            step()
            steps++
        }
        if (lastSim + TugRules.STEP_MS <= now) lastSim = now
    }

    private fun inputT(): Long = simT + (clock() - lastSim).coerceIn(0L, TugRules.STEP_MS - 1)

    private fun step() {
        when (phase) {
            TugPhase.COUNTDOWN -> if (simT >= phaseEnd) { phase = TugPhase.PULL; setEvent(TugEvent.GO, -1) }
            TugPhase.PULL -> pullStep()
            TugPhase.ROUND_END -> if (simT >= phaseEnd) { round++; beginRound() }
            TugPhase.OVER -> {}
        }
    }

    private fun pullStep() {
        val t = simT
        val dt = TugRules.STEP_MS / 1000.0
        for (seat in 0 until seatCount) {
            val since = held[seat].values.minOrNull()
            val braced = since != null && t - since >= TugRules.BRACE_AFTER_MS
            if (braced) stamina[seat] = (stamina[seat] + TugRules.BRACE_REGEN_PER_S * dt).coerceAtMost(TugRules.MAX_STAMINA)
            else if (since == null && t - lastPressAt[seat] >= TugRules.REST_DELAY_MS)
                stamina[seat] = (stamina[seat] + TugRules.REST_REGEN_PER_S * dt).coerceAtMost(TugRules.MAX_STAMINA)
            if (holdToPlay && since != null && t - since >= TugRules.HOLD_START_MS && t >= nextAuto[seat]) {
                nextAuto[seat] = t + TugRules.HOLD_INTERVAL_MS
                applyPull(seat, TugRules.HOLD_POWER, TugPull.AUTO)
                if (phase != TugPhase.PULL) return
            }
        }
        for (seat in 0 until seatCount) {
            val bot = bots.getOrNull(seat) ?: continue
            botStep(seat, bot, t)
            if (phase != TugPhase.PULL) return
        }
        if (t - goT >= TugRules.roundLimitMs(relaxed)) timeUp()
    }

    // ---- pulling -----------------------------------------------------------------------------

    /** A finger [pointerId] went down on [seat]'s pad: a pull now, and the start of a hold. */
    @Synchronized
    fun press(seat: Int, pointerId: Long = 0L): TugReject {
        if (state.value == null || matchOver.value || phase == TugPhase.OVER) return TugReject.NOT_PULLING
        if (seat !in 0 until seatCount) return TugReject.BAD_SEAT
        if (isBotSeat(seat)) return TugReject.BOT_SEAT
        advanceTo(clock())
        val r = if (phase == TugPhase.PULL) pressInternal(seat, pointerId, inputT()) else TugReject.NOT_PULLING
        commit()
        return r
    }

    private fun pressInternal(seat: Int, pointerId: Long, t: Long): TugReject {
        held[seat][pointerId] = t
        if (t - lastPressAt[seat] < TugRules.MIN_TAP_GAP_MS) return TugReject.TOO_FAST
        lastPressAt[seat] = t
        val power = TugRules.pullPower(stamina[seat], relaxed)
        stamina[seat] = (stamina[seat] - TugRules.costOf(relaxed)).coerceAtLeast(0.0)
        applyPull(seat, power, TugPull.TAP)
        return TugReject.NONE
    }

    /** The finger [pointerId] left [seat]'s pad. A hold of at least [TugRules.BRACE_AFTER_MS] releases as a charged HEAVE. */
    @Synchronized
    fun release(seat: Int, pointerId: Long = 0L) {
        if (seat !in 0 until seatCount) return
        if (state.value == null) return
        advanceTo(clock())
        releaseInternal(seat, pointerId, inputT())
        commit()
    }

    private fun releaseInternal(seat: Int, pointerId: Long, t: Long) {
        val since = held[seat].remove(pointerId) ?: return
        if (phase != TugPhase.PULL || holdToPlay) return
        val charge = TugRules.charge(t - since, relaxed)
        if (charge <= 0.0) return
        doHeave(seat, charge, t)
    }

    private fun doHeave(seat: Int, charge: Double, t: Long) {
        val power = TugRules.pullPower(stamina[seat], relaxed) * (1.0 + TugRules.HEAVE_BONUS * charge)
        stamina[seat] = (stamina[seat] - TugRules.costOf(relaxed)).coerceAtLeast(0.0)
        lastPressAt[seat] = t
        lastHeaveAt[seat] = t
        applyPull(seat, power, TugPull.HEAVE)
    }

    /** One complete tap with no finger (a switch, TalkBack, a keyboard): a plain pull. */
    @Synchronized
    fun tap(seat: Int): TugReject {
        val r = press(seat, TAP_POINTER_ID)
        release(seat, TAP_POINTER_ID)
        return r
    }

    /**
     * A charged pull with no hold, for players who cannot hold a finger down (TalkBack's "Heave" action). It needs the
     * same rest a full charge would take, so it is no easier to spam than a held heave.
     */
    @Synchronized
    fun heave(seat: Int): TugReject {
        if (state.value == null || matchOver.value || phase == TugPhase.OVER) return TugReject.NOT_PULLING
        if (seat !in 0 until seatCount) return TugReject.BAD_SEAT
        if (isBotSeat(seat)) return TugReject.BOT_SEAT
        advanceTo(clock())
        if (phase != TugPhase.PULL) return TugReject.NOT_PULLING
        val t = inputT()
        if (t - lastHeaveAt[seat] < TugRules.chargeFullMs(relaxed)) return TugReject.TOO_FAST
        doHeave(seat, 1.0, t)
        commit()
        return TugReject.NONE
    }

    private fun applyPull(seat: Int, power: Double, kind: String) {
        val side = sideOf[seat]
        val k = TugRules.UNIT * power * boosts[side]
        mx += dirs[side].first * k
        my += dirs[side].second * k
        val r = Math.hypot(mx, my)
        if (r > TugRules.MAX_RADIUS) { mx *= TugRules.MAX_RADIUS / r; my *= TugRules.MAX_RADIUS / r }
        pullSerial[seat]++
        pullKind[seat] = kind
        pullPower[seat] = power
        val winner = (0 until sideCount).filter { advance(it) >= TugRules.GOAL }.maxByOrNull { advance(it) }
        if (winner != null) finishRound(winner)
    }

    private fun advance(side: Int): Double = mx * dirs[side].first + my * dirs[side].second

    // ---- round and match ---------------------------------------------------------------------

    private fun timeUp() {
        val adv = (0 until sideCount).map { advance(it) }
        val best = adv.max()
        val leaders = adv.indices.filter { best - adv[it] < TugRules.DRAW_EPSILON }
        finishRound(if (leaders.size == 1) leaders[0] else -1)
    }

    private fun finishRound(winner: Int) {
        roundWinnerSide = winner
        phase = TugPhase.ROUND_END
        phaseEnd = simT + TugRules.BETWEEN_MS
        held.forEach { it.clear() }
        when (val outcome = tally.record(winner)) {
            is TapTugMatchTally.Outcome.Won -> finishMatch(outcome.side)
            TapTugMatchTally.Outcome.Drawn -> finishMatch(-1)
            TapTugMatchTally.Outcome.Continue ->
                if (winner >= 0) setEvent(TugEvent.ROUND_WIN, winner) else setEvent(TugEvent.ROUND_DRAW, -1)
        }
    }

    private fun finishMatch(side: Int) {
        over = true
        phase = TugPhase.OVER
        winnerSide = side
        if (side >= 0) {
            context.players.forEachIndexed { i, p ->
                if (sideOf[i] == side) sessionWins[p.playerId] = (sessionWins[p.playerId] ?: 0) + 1
            }
            setEvent(TugEvent.MATCH_WIN, side)
        } else {
            sessionDraws++
            setEvent(TugEvent.MATCH_DRAW, -1)
        }
    }

    // ---- computer pullers --------------------------------------------------------------------

    private fun botStep(seat: Int, b: Bot, t: Long) {
        if (t < b.reactAt) return
        val level = roundLevels.getOrNull(seat) ?: levelOf(seat)
        val p = TugRules.bot(level)
        val pid = BOT_POINTER_BASE - seat
        if (b.holdUntil >= 0) {
            if (t < b.holdUntil) return
            b.holdUntil = -1
            releaseInternal(seat, pid, t)
            b.nextAt = t + jit(120, 0.3)
            if (phase != TugPhase.PULL) return
        }
        if (t < b.nextAt) return
        fun quickTap() {
            pressInternal(seat, pid, t)
            held[seat].remove(pid) // released inside the brace time: a plain tap, no heave
        }
        when (level) {
            CpuDifficulty.EASY -> {
                quickTap()
                b.nextAt = t + (1000.0 / p.tapsPerSecond * (0.7 + random.nextDouble() * 0.6)).toLong().coerceAtLeast(TugRules.MIN_TAP_GAP_MS)
            }
            CpuDifficulty.MEDIUM -> {
                if (stamina[seat] < p.tiredBelow) {
                    pressInternal(seat, pid, t)
                    b.holdUntil = t + jit(p.holdMs.toLong(), 0.3)
                } else {
                    quickTap()
                    b.nextAt = t + (1000.0 / p.tapsPerSecond * (0.7 + random.nextDouble() * 0.6)).toLong().coerceAtLeast(TugRules.MIN_TAP_GAP_MS)
                }
            }
            CpuDifficulty.HARD -> {
                if (b.burst > 0) {
                    b.burst--
                    quickTap()
                    b.nextAt = t + jit(170, 0.3)
                    return
                }
                if (random.nextDouble() < p.lapseChance) { b.nextAt = t + 400 + random.nextInt(400); b.holdUntil = -1; return }
                if (random.nextDouble() < p.panicChance) { b.burst = 3; return }
                pressInternal(seat, pid, t)
                b.holdUntil = t + jit(p.holdMs.toLong(), 0.35)
            }
        }
    }

    // ---- the screen's view ------------------------------------------------------------------

    private fun commit() {
        state.value = buildState()
    }

    private fun buildState(): TugState {
        val now = simT
        return TugState(
            seats = List(seatCount) { s ->
                val pl = context.players[s]
                val since = held.getOrNull(s)?.values?.minOrNull()
                val heldMs = if (since == null) 0L else now - since
                TugSeatState(
                    playerId = pl.playerId, name = pl.displayName, isBot = pl.isBot, side = sideOf[s],
                    stamina = stamina.getOrElse(s) { TugRules.MAX_STAMINA }.toInt(), held = since != null,
                    braced = since != null && heldMs >= TugRules.BRACE_AFTER_MS,
                    charge = TugRules.charge(heldMs, relaxed).toFloat(),
                    tired = stamina.getOrElse(s) { TugRules.MAX_STAMINA } < TugRules.costOf(relaxed),
                    pullSerial = pullSerial.getOrElse(s) { 0 }, pullKind = pullKind.getOrElse(s) { TugPull.NONE },
                    pullPower = pullPower.getOrElse(s) { 0.0 }.toFloat()
                )
            },
            sides = sideCount, layout = TugSides.label(layout, seatCount),
            dirX = dirs.map { it.first.toFloat() }, dirY = dirs.map { it.second.toFloat() },
            markerX = mx.toFloat(), markerY = my.toFloat(), advance = List(sideCount) { advance(it).toFloat() },
            phase = phase.name, round = round, rounds = rounds,
            countdownMs = if (phase == TugPhase.COUNTDOWN) (phaseEnd - simT).coerceAtLeast(0L).toInt() else 0,
            roundLeftMs = if (phase == TugPhase.PULL) (TugRules.roundLimitMs(relaxed) - (simT - goT)).coerceAtLeast(0L).toInt() else 0,
            relaxed = relaxed, holdToPlay = holdToPlay,
            event = event, eventSide = eventSide, eventSerial = eventSerial, roundWinnerSide = roundWinnerSide,
            tiebreak = round > rounds, over = over, winnerSide = winnerSide,
            winnerSeats = if (winnerSide >= 0) sideOf.indices.filter { sideOf[it] == winnerSide } else emptyList(),
            sideWins = tally.wins.toList(), sessionWins = sessionWins.toMap(), sessionDraws = sessionDraws
        )
    }

    // ---- resume --------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null || over) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            tugJson.encodeToString(
                TugSnapshot.serializer(),
                TugSnapshot(
                    players = context.players.map { TugPlayer(it.playerId, it.displayName, it.isBot) },
                    layout = layout.id, wins = tally.wins.toList(), played = tally.played, rounds = rounds,
                    relaxed = relaxed, hold = holdToPlay, difficulty = difficulty.name,
                    sessionWins = sessionWins.toMap(), sessionDraws = sessionDraws, eventSerial = eventSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): TugSnapshot? = try {
        val s = tugJson.decodeFromString(TugSnapshot.serializer(), text)
        val roster = context.players
        val lay = TugLayout.entries.firstOrNull { it.id == s.layout }
        val sides = if (lay != null && roster.size in 2..4) TugSides.sideCount(lay, roster.size) else 2
        val check = TapTugMatchTally(sides, s.rounds, s.wins, s.played)
        val valid = lay != null && roster.size in 2..4 && s.players.size == roster.size && s.wins.size == sides &&
            s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
            s.rounds in TugRules.ROUND_CHOICES && CpuDifficulty.entries.any { it.name == s.difficulty } &&
            s.sessionDraws >= 0 && s.eventSerial >= 0 &&
            s.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } && check.isValidUnfinished()
        if (valid) s else null
    } catch (_: Exception) {
        null
    }

    /** Puts the completed rounds back as saved and starts the current round again from its countdown. */
    @Synchronized
    override fun restore(snapshot: String): Boolean {
        val s = decodeSnapshot(snapshot) ?: return false
        seatCount = context.players.size
        rounds = s.rounds
        layout = TugLayout.fromId(s.layout)
        relaxed = s.relaxed
        holdToPlay = s.hold
        difficulty = CpuDifficulty.entries.first { it.name == s.difficulty }
        setupSides()
        tally = TapTugMatchTally(sideCount, rounds, s.wins, s.played)
        sessionWins.clear(); sessionWins.putAll(s.sessionWins)
        context.players.forEach { sessionWins.putIfAbsent(it.playerId, 0) }
        sessionDraws = s.sessionDraws
        eventSerial = s.eventSerial
        matchOver.value = false
        over = false
        winnerSide = -1
        round = s.played + 1
        lastSim = clock()
        beginRound()
        commit()
        return true
    }

    companion object {
        const val TAP_POINTER_ID = -1L
        private const val BOT_POINTER_BASE = -1000L
    }
}
