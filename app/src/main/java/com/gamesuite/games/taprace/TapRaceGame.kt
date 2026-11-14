package com.gamesuite.games.taprace

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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class RacePhase { COUNTDOWN, RACE, ROUND_END, OVER }

/** One racer as the screen sees it. [judgeSerial] only ever grows and keys the zone's feedback animation and sound. */
data class RaceSeatState(
    val playerId: String,
    val name: String,
    val isBot: Boolean,
    val progress: Int,
    val roundWins: Int,
    val lockedMs: Int,
    val judge: String,
    val judgeSerial: Int,
    val held: Boolean
)

/** Everything a device needs to draw the race. [eventSerial] only ever grows. */
data class TapRaceState(
    val seats: List<RaceSeatState>,
    val phase: String,
    val round: Int,
    val rounds: Int,
    val variant: String,
    val target: Int,
    val relaxed: Boolean,
    val holdToPlay: Boolean,
    val countdownMs: Int,
    val light: String,
    val lightLeftMs: Int,
    val beatIndex: Int,
    val beatPhase: Float,
    val beatMs: Int,
    val event: String,
    val eventSeat: Int,
    val eventSerial: Int,
    val roundWinnerSeat: Int,
    val tiebreak: Boolean,
    val over: Boolean,
    val winnerSeats: List<Int>,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int
) {
    val phaseValue: RacePhase get() = RacePhase.entries.firstOrNull { it.name == phase } ?: RacePhase.OVER
    val lightValue: RaceLight get() = RaceLight.entries.firstOrNull { it.name == light } ?: RaceLight.NONE
}

@Serializable
data class RacePlayer(val playerId: String, val displayName: String, val isBot: Boolean)

@Serializable
private data class RaceSnapshot(
    val players: List<RacePlayer>,
    val wins: List<Int>,
    val played: Int,
    val rounds: Int,
    val variant: String,
    val relaxed: Boolean,
    val hold: Boolean,
    val difficulty: String,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val eventSerial: Int = 0
)

private val raceJson = Json { ignoreUnknownKeys = true }

/**
 * Tap Race: two to four people race on ONE screen, each with a big pad in their own zone, all at once. See
 * [TapRaceRules] for the numbers and [RaceVariant] for the three ways to race.
 *
 * REAL TIME BY SIMULATION. Nothing runs by itself: the screen calls [tick] about 40 times a second and the engine
 * advances a fixed-step simulation up to the injected [clock]. Input ([press], [release], [tap]) first catches
 * the simulation up to "now" and then applies, so a fake clock and a seeded [Random] replay a whole race
 * exactly. Computer racers are driven from the same steps; they can only tap as fast and as accurately as
 * their level's [RaceBotProfile], which is human-plausible at every level.
 *
 * ANTI-CHEESE lives at two layers: the pointer tracker in the UI ignores palm-size contacts and limits one
 * finger to about 11 taps a second, and this engine refuses a seat's tap that comes less than
 * [TapRaceRules.MIN_TAP_GAP_MS] after its last. Rhythm punishes off-beat and extra taps, so spamming loses.
 *
 * SAME SCREEN ONLY in this version: there is no Nearby or Online mode (the engine has no host and no
 * message type). Computer racers fill any seat without a person.
 *
 * RESUME is between rounds: [snapshot] stores the completed rounds; [restore] puts them back and RESTARTS the
 * current round with a fresh countdown (a half-played round is never reconstructed).
 */
class TapRaceGame(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : GameModule, ResumableGame {
    override val gameId = "tap-race"
    override val displayName = "Tap Race"
    override val category = GameCategory.ARCADE
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<TapRaceState?>(null)
    val matchOver = mutableStateOf(false)

    /** Pre-set by the screen from the Hub before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var variant: RaceVariant = RaceVariant.SPRINT
    var rounds: Int = TapRaceRules.DEFAULT_ROUNDS
    var relaxed: Boolean = false
    var holdToPlay: Boolean = false

    /** Tests may give each computer seat its own level. */
    internal val seatLevelOverride = HashMap<Int, CpuDifficulty>()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private val lock: Any = this

    private val sessionWins = HashMap<String, Int>()
    private var sessionDraws = 0
    private var tally = TapTugMatchTally(2, 1)

    private var phase = RacePhase.OVER
    private var simT = 0L
    private var lastSim = -1L
    private var phaseEnd = 0L
    private var goT = 0L
    private var round = 1

    private var seatCount = 2
    private var progress = IntArray(0)
    private var lockedUntil = LongArray(0)
    private var lastTapAt = LongArray(0)
    private var lastBeat = IntArray(0)
    private var judge = Array(0) { RaceJudge.NONE }
    private var judgeSerial = IntArray(0)
    private var redPenalised = BooleanArray(0)
    private var nextAuto = LongArray(0)
    private var held = Array(0) { HashMap<Long, Long>() }

    private var light = RaceLight.NONE
    private var lightEnd = 0L
    private var redStart = 0L
    private var firstGreen = true

    private var event = RaceEvent.START
    private var eventSeat = -1
    private var eventSerial = 0
    private var roundWinnerSeat = -1
    private var over = false
    private var winnerSeats: List<Int> = emptyList()

    private class Bot {
        var reactAt = 0L
        var nextTap = 0L
        var halted = false
        var stopAt = Long.MAX_VALUE
        var resumeAt = 0L
        var form = 1.0
        var beatK = 1
        var tapAt = -1L
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
            phase = RacePhase.OVER
            over = false
            winnerSeats = emptyList()
            tally = TapTugMatchTally(seatCount, rounds.coerceIn(1, 9))
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) { onMatchEnd = listener }

    private fun levelOf(seat: Int): CpuDifficulty = seatLevelOverride[seat] ?: difficulty
    private fun isBotSeat(seat: Int): Boolean = context.players.getOrNull(seat)?.isBot == true

    // ---- lifecycle -----------------------------------------------------------------------

    @Synchronized
    override fun startMatch() {
        matchOver.value = false
        over = false
        winnerSeats = emptyList()
        rounds = rounds.coerceIn(1, 9)
        tally = TapTugMatchTally(seatCount, rounds)
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

    /** Ends the session, reporting matches won (only a strict leader wins). */
    fun leaveSession() {
        if (matchOver.value) return
        val best = sessionWins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (sessionWins[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val w = sessionWins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = best > 0 && w == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    @Synchronized
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        startMatch()
    }

    private fun resetSeatArrays() {
        progress = IntArray(seatCount)
        lockedUntil = LongArray(seatCount)
        lastTapAt = LongArray(seatCount) { -100_000L }
        lastBeat = IntArray(seatCount) { -1 }
        if (judge.size != seatCount) { judge = Array(seatCount) { RaceJudge.NONE }; judgeSerial = IntArray(seatCount) }
        else judge.fill(RaceJudge.NONE)
        redPenalised = BooleanArray(seatCount)
        nextAuto = LongArray(seatCount)
        held = Array(seatCount) { HashMap<Long, Long>() }
    }

    private fun beginRound() {
        resetSeatArrays()
        roundWinnerSeat = -1
        phase = RacePhase.COUNTDOWN
        phaseEnd = simT + TapRaceRules.COUNTDOWN_MS
        goT = phaseEnd
        light = RaceLight.NONE
        firstGreen = true
        roundLevels = Array(seatCount) { seat -> if (isBotSeat(seat)) TapTugBotForm.roundLevel(levelOf(seat), random) else levelOf(seat) }
        bots = Array(seatCount) { seat ->
            if (isBotSeat(seat)) Bot().also { b ->
                val p = TapRaceRules.bot(roundLevels[seat])
                b.form = 0.85 + random.nextDouble() * 0.25 + if (roundLevels[seat] == CpuDifficulty.EASY) random.nextDouble() * 0.3 else 0.0
                b.reactAt = goT + jit(p.reactionMs.toLong(), 0.25)
                b.nextTap = b.reactAt
            } else null
        }
        setEvent(RaceEvent.START, -1)
    }

    private fun setEvent(kind: String, seat: Int) {
        event = kind; eventSeat = seat; eventSerial++
    }

    private fun jit(base: Long, frac: Double): Long = (base * (1.0 - frac + random.nextDouble() * 2 * frac)).toLong().coerceAtLeast(0L)
    private fun gauss(): Double = (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5) * 2.0

    // ---- the clock ---------------------------------------------------------------------------

    /** Advances the race to the injected clock. Returns true when the screen should redraw. */
    @Synchronized
    fun tick(): Boolean {
        if (state.value == null || matchOver.value) return false
        advanceTo(clock())
        commit()
        return true
    }

    private fun advanceTo(now: Long) {
        if (phase == RacePhase.OVER) return
        if (lastSim < 0) lastSim = now
        var steps = 0
        while (lastSim + TapRaceRules.STEP_MS <= now && steps < TapRaceRules.MAX_CATCHUP_STEPS && phase != RacePhase.OVER) {
            lastSim += TapRaceRules.STEP_MS
            simT += TapRaceRules.STEP_MS
            step()
            steps++
        }
        if (lastSim + TapRaceRules.STEP_MS <= now) lastSim = now // a long gap (app paused): drop it instead of fast-forwarding a race
    }

    /** The simulated time of an input arriving now: the last step plus the part of the next one that has passed. */
    private fun inputT(): Long = simT + (clock() - lastSim).coerceIn(0L, TapRaceRules.STEP_MS - 1)

    private fun step() {
        when (phase) {
            RacePhase.COUNTDOWN -> if (simT >= phaseEnd) startRace()
            RacePhase.RACE -> raceStep()
            RacePhase.ROUND_END -> if (simT >= phaseEnd) { round++; beginRound() }
            RacePhase.OVER -> {}
        }
    }

    private fun startRace() {
        phase = RacePhase.RACE
        if (variant == RaceVariant.BRAKE) {
            light = RaceLight.GREEN
            lightEnd = goT + randomBetween(TapRaceRules.GREEN_MIN_MS, TapRaceRules.GREEN_MAX_MS) + TapRaceRules.FIRST_GREEN_EXTRA_MS
        }
        setEvent(RaceEvent.GO, -1)
    }

    private fun randomBetween(lo: Int, hi: Int): Long = (lo + random.nextInt(hi - lo + 1)).toLong()

    private fun raceStep() {
        val t = simT
        if (variant == RaceVariant.BRAKE) lightStep(t)
        if (phase != RacePhase.RACE) return
        if (t - goT >= TapRaceRules.ROUND_LIMIT_MS) { timeUp(); return }
        // Hold to play: a held finger keeps tapping for its player.
        if (holdToPlay && variant != RaceVariant.RHYTHM) {
            for (seat in 0 until seatCount) {
                if (isBotSeat(seat)) continue
                val since = held[seat].values.minOrNull() ?: continue
                if (t - since >= TapRaceRules.HOLD_START_MS && t >= nextAuto[seat]) {
                    nextAuto[seat] = t + TapRaceRules.HOLD_INTERVAL_MS
                    tryTap(seat, t, auto = true)
                    if (phase != RacePhase.RACE) return
                }
            }
            // A finger still resting on the pad after red has had time to react: it costs ground once per red light.
            if (variant == RaceVariant.BRAKE && light == RaceLight.RED && t - redStart >= TapRaceRules.redGraceMs(relaxed) + 200) {
                for (seat in 0 until seatCount) {
                    if (!redPenalised[seat] && held[seat].isNotEmpty()) redPenalty(seat, t)
                }
            }
        }
        for (seat in 0 until seatCount) {
            val bot = bots.getOrNull(seat) ?: continue
            botStep(seat, bot, t)
            if (phase != RacePhase.RACE) return
        }
    }

    private fun lightStep(t: Long) {
        while (t >= lightEnd && phase == RacePhase.RACE) {
            when (light) {
                RaceLight.GREEN -> {
                    light = RaceLight.AMBER
                    lightEnd += TapRaceRules.amberMs(relaxed)
                    setEvent(RaceEvent.AMBER, -1)
                    onAmber(lightEnd - TapRaceRules.amberMs(relaxed))
                }
                RaceLight.AMBER -> {
                    light = RaceLight.RED
                    redStart = lightEnd
                    lightEnd += randomBetween(TapRaceRules.RED_MIN_MS, TapRaceRules.RED_MAX_MS)
                    redPenalised.fill(false)
                    setEvent(RaceEvent.RED, -1)
                    onRed(redStart)
                }
                else -> {
                    light = RaceLight.GREEN
                    lightEnd += randomBetween(TapRaceRules.GREEN_MIN_MS, TapRaceRules.GREEN_MAX_MS)
                    setEvent(RaceEvent.GREEN, -1)
                    onGreen(lightEnd)
                }
            }
        }
    }

    // ---- tapping -----------------------------------------------------------------------------

    /** A finger [pointerId] went down on [seat]'s pad. Counts one tap when the race allows it. */
    @Synchronized
    fun press(seat: Int, pointerId: Long = 0L): TapReject {
        if (state.value == null || matchOver.value || phase == RacePhase.OVER) return TapReject.NOT_RACING
        if (seat !in 0 until seatCount) return TapReject.BAD_SEAT
        if (isBotSeat(seat)) return TapReject.BOT_SEAT
        advanceTo(clock())
        val t = inputT()
        val result = when (phase) {
            RacePhase.COUNTDOWN -> {
                lockedUntil[seat] = goT + TapRaceRules.FALSE_START_LOCK_MS
                mark(seat, RaceJudge.FALSE_START)
                TapReject.FALSE_START
            }
            RacePhase.RACE -> {
                held[seat][pointerId] = t
                tryTap(seat, t, auto = false)
            }
            else -> TapReject.NOT_RACING
        }
        commit()
        return result
    }

    /** The finger [pointerId] left [seat]'s pad (lifted, cancelled, or slid off). */
    @Synchronized
    fun release(seat: Int, pointerId: Long = 0L) {
        if (seat !in 0 until seatCount) return
        held[seat].remove(pointerId)
        if (state.value != null) commit()
    }

    /** One complete tap (a press and its release): what a switch, a TalkBack double-tap or a keyboard produces. */
    @Synchronized
    fun tap(seat: Int): TapReject {
        val r = press(seat, TAP_POINTER_ID)
        release(seat, TAP_POINTER_ID)
        return r
    }

    private fun mark(seat: Int, j: String) {
        judge[seat] = j
        judgeSerial[seat]++
    }

    /** Counts a tap if the rules allow; used by people and computers alike. */
    private fun tryTap(seat: Int, t: Long, auto: Boolean): TapReject {
        if (t < lockedUntil[seat]) return TapReject.LOCKED
        if (!auto && t - lastTapAt[seat] < TapRaceRules.MIN_TAP_GAP_MS) return TapReject.TOO_FAST
        lastTapAt[seat] = t
        when (variant) {
            RaceVariant.SPRINT -> progress[seat]++
            RaceVariant.BRAKE -> {
                if (light == RaceLight.RED && t - redStart >= TapRaceRules.redGraceMs(relaxed)) {
                    redPenalty(seat, t)
                    return TapReject.NONE
                }
                progress[seat]++
            }
            RaceVariant.RHYTHM -> rhythmTap(seat, t)
        }
        if (progress[seat] >= TapRaceRules.target(variant, relaxed)) finishRound(seat)
        return TapReject.NONE
    }

    private fun redPenalty(seat: Int, t: Long) {
        redPenalised[seat] = true
        progress[seat] = (progress[seat] - TapRaceRules.redPenalty(relaxed)).coerceAtLeast(0)
        lockedUntil[seat] = t + TapRaceRules.redLockMs(relaxed)
        mark(seat, RaceJudge.RED)
    }

    private fun rhythmTap(seat: Int, t: Long) {
        val beat = TapRaceRules.beatMs(relaxed)
        val rel = t - goT
        val k = (rel.toDouble() / beat).roundToInt().coerceAtLeast(0)
        val off = rel - k * beat
        val perfect = TapRaceRules.perfectWindowMs(relaxed)
        val good = TapRaceRules.goodWindowMs(relaxed)
        val onBeat = abs(off) <= good
        if (onBeat && lastBeat[seat] == k) {
            progress[seat] = (progress[seat] + TapRaceRules.DOUBLE_POINTS).coerceAtLeast(0)
            mark(seat, RaceJudge.DOUBLE)
            return
        }
        when {
            abs(off) <= perfect -> { lastBeat[seat] = k; progress[seat] += TapRaceRules.PERFECT_POINTS; mark(seat, RaceJudge.PERFECT) }
            abs(off) <= good -> { lastBeat[seat] = k; progress[seat] += TapRaceRules.GOOD_POINTS; mark(seat, RaceJudge.GOOD) }
            else -> {
                progress[seat] = (progress[seat] + TapRaceRules.OFF_BEAT_POINTS).coerceAtLeast(0)
                mark(seat, if (off < 0) RaceJudge.EARLY else RaceJudge.LATE)
            }
        }
    }

    // ---- round and match ---------------------------------------------------------------------

    private fun timeUp() {
        val best = progress.max()
        val leaders = progress.indices.filter { progress[it] == best }
        finishRound(if (leaders.size == 1 && best > 0) leaders[0] else -1)
    }

    private fun finishRound(winner: Int) {
        roundWinnerSeat = winner
        phase = RacePhase.ROUND_END
        phaseEnd = simT + TapRaceRules.BETWEEN_MS
        held.forEach { it.clear() }
        when (val outcome = tally.record(winner)) {
            is TapTugMatchTally.Outcome.Won -> finishMatch(outcome.side)
            TapTugMatchTally.Outcome.Drawn -> finishMatch(-1)
            TapTugMatchTally.Outcome.Continue ->
                if (winner >= 0) setEvent(RaceEvent.ROUND_WIN, winner) else setEvent(RaceEvent.ROUND_DRAW, -1)
        }
    }

    private fun finishMatch(winnerSeat: Int) {
        over = true
        phase = RacePhase.OVER
        winnerSeats = if (winnerSeat >= 0) listOf(winnerSeat) else emptyList()
        if (winnerSeat >= 0) {
            val id = context.players[winnerSeat].playerId
            sessionWins[id] = (sessionWins[id] ?: 0) + 1
            setEvent(RaceEvent.MATCH_WIN, winnerSeat)
        } else {
            sessionDraws++
            setEvent(RaceEvent.MATCH_DRAW, -1)
        }
    }

    // ---- computer racers ---------------------------------------------------------------------

    private fun profile(seat: Int) = TapRaceRules.bot(roundLevels.getOrNull(seat) ?: levelOf(seat))

    private fun onAmber(amberStart: Long) {
        for (seat in 0 until seatCount) {
            val b = bots[seat] ?: continue
            val p = profile(seat)
            b.stopAt = if (random.nextDouble() < p.missAmberChance) Long.MAX_VALUE else amberStart + jit(p.reactionMs.toLong(), 0.25)
        }
    }

    private fun onRed(start: Long) {
        for (seat in 0 until seatCount) {
            val b = bots[seat] ?: continue
            val p = profile(seat)
            b.stopAt = minOf(b.stopAt, start + jit(p.reactionMs.toLong(), 0.25))
        }
    }

    private fun onGreen(start: Long) {
        for (seat in 0 until seatCount) {
            val b = bots[seat] ?: continue
            b.resumeAt = start + jit(profile(seat).reactionMs.toLong(), 0.25)
        }
    }

    private fun botStep(seat: Int, b: Bot, t: Long) {
        val p = profile(seat)
        if (variant == RaceVariant.RHYTHM) {
            if (b.tapAt < 0) {
                var k = b.beatK
                while (random.nextDouble() < p.beatSkipChance) k++
                b.tapAt = (goT + k * TapRaceRules.beatMs(relaxed) + (gauss() * p.beatSpreadMs).toLong()).coerceAtLeast(goT + 1)
                b.beatK = k + 1
            }
            if (t >= b.tapAt) {
                b.tapAt = -1
                tryTap(seat, t, auto = false)
            }
            return
        }
        if (t < b.reactAt) return
        if (variant == RaceVariant.BRAKE) {
            if (!b.halted && t >= b.stopAt) b.halted = true
            if (b.halted && t >= b.resumeAt && light == RaceLight.GREEN) { b.halted = false; b.stopAt = Long.MAX_VALUE }
            if (b.halted) return
        }
        if (t >= b.nextTap) {
            tryTap(seat, t, auto = false)
            val gap = (1000.0 / (p.tapsPerSecond * b.form)) * (0.75 + random.nextDouble() * 0.5)
            b.nextTap = t + gap.toLong().coerceAtLeast(TapRaceRules.MIN_TAP_GAP_MS)
            if (random.nextDouble() < p.stumbleChance) b.nextTap += 250 + random.nextInt(350)
        }
    }

    // ---- the screen's view ------------------------------------------------------------------

    private fun commit() {
        state.value = buildState()
    }

    private fun buildState(): TapRaceState {
        val beat = TapRaceRules.beatMs(relaxed)
        var beatIndex = 0
        var beatPhase = 0f
        if (variant == RaceVariant.RHYTHM && phase == RacePhase.RACE) {
            val rel = (simT - goT).coerceAtLeast(0L)
            beatIndex = (rel / beat).toInt()
            beatPhase = (rel % beat).toFloat() / beat
        }
        val now = simT
        return TapRaceState(
            seats = List(seatCount) { s ->
                val pl = context.players[s]
                RaceSeatState(
                    playerId = pl.playerId, name = pl.displayName, isBot = pl.isBot,
                    progress = progress.getOrElse(s) { 0 }, roundWins = tally.wins[s],
                    lockedMs = (lockedUntil.getOrElse(s) { 0L } - now).coerceAtLeast(0L).toInt(),
                    judge = judge.getOrElse(s) { RaceJudge.NONE }, judgeSerial = judgeSerial.getOrElse(s) { 0 },
                    held = held.getOrNull(s)?.isNotEmpty() == true
                )
            },
            phase = phase.name, round = round, rounds = rounds, variant = variant.id,
            target = TapRaceRules.target(variant, relaxed), relaxed = relaxed, holdToPlay = holdToPlay,
            countdownMs = if (phase == RacePhase.COUNTDOWN) (phaseEnd - simT).coerceAtLeast(0L).toInt() else 0,
            light = light.name, lightLeftMs = if (light == RaceLight.NONE) 0 else (lightEnd - simT).coerceAtLeast(0L).toInt(),
            beatIndex = beatIndex, beatPhase = beatPhase, beatMs = beat.toInt(),
            event = event, eventSeat = eventSeat, eventSerial = eventSerial, roundWinnerSeat = roundWinnerSeat,
            tiebreak = round > rounds,
            over = over, winnerSeats = winnerSeats, sessionWins = sessionWins.toMap(), sessionDraws = sessionDraws
        )
    }

    // ---- resume --------------------------------------------------------------------------------

    override fun snapshot(): String? = synchronized(lock) {
        try {
            if (matchOver.value || state.value == null || over) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            raceJson.encodeToString(
                RaceSnapshot.serializer(),
                RaceSnapshot(
                    players = context.players.map { RacePlayer(it.playerId, it.displayName, it.isBot) },
                    wins = tally.wins.toList(), played = tally.played, rounds = rounds, variant = variant.id,
                    relaxed = relaxed, hold = holdToPlay, difficulty = difficulty.name,
                    sessionWins = sessionWins.toMap(), sessionDraws = sessionDraws, eventSerial = eventSerial
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshot(text: String): RaceSnapshot? = try {
        val s = raceJson.decodeFromString(RaceSnapshot.serializer(), text)
        val roster = context.players
        val check = TapTugMatchTally(roster.size.coerceIn(2, 4), s.rounds, s.wins, s.played)
        val valid = roster.size in 2..4 && s.players.size == roster.size && s.wins.size == roster.size &&
            s.players.indices.all { s.players[it].playerId == roster[it].playerId && s.players[it].isBot == roster[it].isBot } &&
            s.rounds in TapRaceRules.ROUND_CHOICES && RaceVariant.entries.any { it.id == s.variant } &&
            CpuDifficulty.entries.any { it.name == s.difficulty } && s.sessionDraws >= 0 && s.eventSerial >= 0 &&
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
        variant = RaceVariant.fromId(s.variant)
        relaxed = s.relaxed
        holdToPlay = s.hold
        difficulty = CpuDifficulty.entries.first { it.name == s.difficulty }
        tally = TapTugMatchTally(seatCount, rounds, s.wins, s.played)
        sessionWins.clear(); sessionWins.putAll(s.sessionWins)
        context.players.forEach { sessionWins.putIfAbsent(it.playerId, 0) }
        sessionDraws = s.sessionDraws
        eventSerial = s.eventSerial
        matchOver.value = false
        over = false
        winnerSeats = emptyList()
        round = s.played + 1
        lastSim = clock()
        beginRound()
        commit()
        return true
    }

    companion object {
        /** The pointer id of a complete tap that has no finger (a switch, TalkBack, a keyboard). */
        const val TAP_POINTER_ID = -1L
    }
}
