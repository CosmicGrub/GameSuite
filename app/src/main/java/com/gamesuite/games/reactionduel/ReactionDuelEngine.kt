package com.gamesuite.games.reactionduel

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

enum class DuelPhase { COUNTDOWN, LIVE, RESULT, OVER }

enum class SeatStatus { ARMED, LOCKED, DONE }

enum class RoundEnd { WIN, DEFAULT_WIN, DEAD_HEAT, TOO_SLOW }

enum class PressResult { IGNORED, EARLY_IGNORED, FALSE_START, WRONG, PROGRESS, DONE }

object DuelEventKind {
    const val COUNTDOWN = "countdown"
    const val GO = "go"
    const val FALSE_START = "false_start"
    const val WRONG = "wrong"
    const val ROUND_WIN = "round_win"
    const val DEFAULT_WIN = "default_win"
    const val DEAD_HEAT = "dead_heat"
    const val TOO_SLOW = "too_slow"
    const val MATCH_OVER = "match_over"
}

/** One thing that happened. [serial] only ever grows; [ms] is a reaction time for wins and 0 otherwise; [seat] is -1 when it concerns nobody in particular. */
data class DuelEvent(val serial: Int, val kind: String, val seat: Int, val ms: Int)

data class DuelConfig(
    val seats: Int,
    val variant: DuelVariant,
    val bestOf: Int,
    val relaxed: Boolean,
    val oneSwitch: Boolean,
    val seed: Long,
    val cpuSeats: Set<Int>,
    val level: CpuDifficulty
) {
    init {
        require(seats in DuelRules.MIN_SEATS..DuelRules.MAX_SEATS) { "seats" }
        require(bestOf in DuelRules.BEST_OF_CHOICES) { "bestOf" }
    }
}

/** How a finished round went. [reactionMs] is -1 for a seat that never produced a valid answer. */
data class RoundOutcome(val end: RoundEnd, val winner: Int, val reactionMs: List<Int>, val fouled: List<Boolean>)

enum class DisplayKind { GET_READY, WAIT, DECOY, TARGET, ECHO_LIT, ECHO_REST, GO, YOUR_TURN, RESULT }

/** What every zone shows at a moment in the round (the same in every zone: that is the fairness). */
data class DuelDisplay(val kind: DisplayKind, val symbol: DuelSymbol? = null, val pad: Int = -1)

/** What is carried between rounds (and into a saved game). */
class DuelProgress(
    val completedAttempts: Int,
    val roundsPlayed: Int,
    val wins: List<Int>,
    val falseStarts: List<Int>,
    val bestMs: List<Int>
)

/**
 * The pure rules of Reaction Duel: no clock of its own (every method is told "now"), no Compose, no Android.
 *
 * FAIRNESS. A round is decided by EVENT times, not by the order the engine happened to see the presses in. The
 * signal moment ([signalAt]) is fixed when the round is built from the seeded stream; a press carries the moment
 * the finger touched (clamped to "now"); anything with an event time before the signal is a false start even if
 * it reaches the engine after the signal has passed. After the first valid press the engine waits
 * [DuelRules.GATHER_MS] for presses with earlier event times still in flight, then ranks by event time. The two
 * fastest within [DuelRules.TIE_MS] are a dead heat and the round is replayed. Every zone sees the identical
 * stimulus (one seeded stream per round), and the computer's timings come from a separate stream so changing the
 * level never changes the signal.
 */
class ReactionDuelEngine(val config: DuelConfig) {
    var phase: DuelPhase = DuelPhase.OVER
        private set

    /** Rounds started (a replayed void round counts again). */
    var attempts = 0
        private set
    var roundsPlayed = 0
        private set
    val wins = IntArray(config.seats)
    val falseStarts = IntArray(config.seats)
    val bestMs = IntArray(config.seats) { -1 }

    val seatStatus = Array(config.seats) { SeatStatus.ARMED }
    private val doneAt = LongArray(config.seats)
    val reactionMs = IntArray(config.seats) { -1 }
    val echoProgress = IntArray(config.seats)
    var lastOutcome: RoundOutcome? = null
        private set

    var matchWinner: Int = -1
        private set
    var overPending = false
        private set

    // ---- the current round ----
    var liveStartAt = 0L
        private set
    var signalAt = 0L
        private set
    var deadlineAt = 0L
        private set
    private var countdownEndAt = 0L
    private var resultEndAt = 0L
    private var resolveAt = -1L
    private var goFired = false
    private var streamStart = 0L
    private var dwell = DuelRules.COLOUR_DWELL_MS
    var target: DuelSymbol? = null
        private set
    var stream: List<DuelSymbol> = emptyList()
        private set
    var sequence: List<Int> = emptyList()
        private set
    private var showStart = 0L
    private var showMs = DuelRules.ECHO_SHOW_MS

    private class Act(val seat: Int, val at: Long, val pad: Int)

    private val plans = ArrayList<Act>()
    private var clockNow = 0L

    private val log = ArrayDeque<DuelEvent>()
    var eventSerial = 0
        private set

    val targetWins: Int get() = DuelRules.target(config.bestOf)

    /** Events with a serial above [afterSerial], oldest first (the log keeps the latest 40). */
    fun eventsSince(afterSerial: Int): List<DuelEvent> = log.filter { it.serial > afterSerial }

    private fun emit(kind: String, seat: Int, ms: Int = 0) {
        eventSerial++
        log.addLast(DuelEvent(eventSerial, kind, seat, ms))
        while (log.size > 40) log.removeFirst()
    }

    // ---- lifecycle ----

    fun startMatch(now: Long) {
        wins.fill(0); falseStarts.fill(0); bestMs.fill(-1)
        attempts = 0; roundsPlayed = 0
        matchWinner = -1; overPending = false; lastOutcome = null
        clockNow = now
        beginRound(now, first = true)
    }

    /** Where a saved match stands (valid in any phase except OVER; a round in flight is replayed from its countdown). */
    fun progress(): DuelProgress = DuelProgress(
        completedAttempts = (if (phase == DuelPhase.RESULT) attempts else attempts - 1).coerceAtLeast(0),
        roundsPlayed = roundsPlayed, wins = wins.toList(), falseStarts = falseStarts.toList(), bestMs = bestMs.toList()
    )

    /** Puts a saved match back: a fresh countdown into the round after the last completed one. */
    fun resume(now: Long, p: DuelProgress) {
        for (i in 0 until config.seats) { wins[i] = p.wins[i]; falseStarts[i] = p.falseStarts[i]; bestMs[i] = p.bestMs[i] }
        attempts = p.completedAttempts; roundsPlayed = p.roundsPlayed
        matchWinner = -1; overPending = false; lastOutcome = null
        clockNow = now
        beginRound(now, first = true)
    }

    private fun beginRound(at: Long, first: Boolean) {
        attempts++
        val rnd = DuelRules.stream(config.seed, attempts, 1)
        phase = DuelPhase.COUNTDOWN
        countdownEndAt = at + if (first) DuelRules.FIRST_COUNTDOWN_MS else DuelRules.COUNTDOWN_MS
        liveStartAt = countdownEndAt
        resolveAt = -1L; goFired = false
        for (i in 0 until config.seats) {
            seatStatus[i] = SeatStatus.ARMED; reactionMs[i] = -1; echoProgress[i] = 0; doneAt[i] = 0L
        }
        plans.clear()
        target = null; stream = emptyList(); sequence = emptyList()
        when (config.variant) {
            DuelVariant.CLASSIC -> {
                signalAt = liveStartAt + rnd.nextInt(DuelRules.WAIT_MIN_MS, DuelRules.WAIT_MAX_MS + 1)
                deadlineAt = signalAt + if (config.relaxed) DuelRules.TIMEOUT_RELAXED_MS else DuelRules.TIMEOUT_MS
            }
            DuelVariant.COLOUR -> {
                dwell = if (config.relaxed) DuelRules.COLOUR_DWELL_RELAXED_MS else DuelRules.COLOUR_DWELL_MS
                val t = DuelSymbol(rnd.nextInt(DuelSymbol.SHAPES), rnd.nextInt(DuelSymbol.COLOURS))
                target = t
                val maxDecoys = if (config.relaxed) DuelRules.COLOUR_DECOYS_MAX_RELAXED else DuelRules.COLOUR_DECOYS_MAX
                val k = rnd.nextInt(DuelRules.COLOUR_DECOYS_MIN, maxDecoys + 1)
                val list = ArrayList<DuelSymbol>()
                var prevShape = -1
                repeat(k) {
                    var shape: Int
                    do { shape = rnd.nextInt(DuelSymbol.SHAPES) } while (shape == t.shape || shape == prevShape)
                    prevShape = shape
                    list += DuelSymbol(shape, rnd.nextInt(DuelSymbol.COLOURS))
                }
                stream = list
                streamStart = liveStartAt + rnd.nextInt(DuelRules.COLOUR_LEAD_MIN_MS, DuelRules.COLOUR_LEAD_MAX_MS + 1)
                signalAt = streamStart + k.toLong() * dwell
                deadlineAt = signalAt + if (config.relaxed) DuelRules.TIMEOUT_RELAXED_MS else DuelRules.TIMEOUT_MS
            }
            DuelVariant.ECHO -> {
                showMs = if (config.relaxed || config.oneSwitch) DuelRules.ECHO_SHOW_RELAXED_MS else DuelRules.ECHO_SHOW_MS
                val len = DuelRules.echoLength(roundsPlayed, config.relaxed, config.oneSwitch)
                sequence = List(len) { rnd.nextInt(DuelSymbol.ECHO_PADS) }
                showStart = liveStartAt + DuelRules.ECHO_LEAD_MS
                signalAt = showStart + len.toLong() * (showMs + DuelRules.ECHO_GAP_MS) + DuelRules.ECHO_YOUR_TURN_DELAY_MS
                deadlineAt = signalAt + DuelRules.echoTimeoutMs(len, config.relaxed, config.oneSwitch)
            }
        }
        for (seat in config.cpuSeats) if (seat in 0 until config.seats) planCpu(seat)
        plans.sortBy { it.at }
        emit(DuelEventKind.COUNTDOWN, -1, attempts)
    }

    private fun reaction(r: Random, p: DuelRules.CpuProfile): Long =
        DuelRules.gauss(r, p.meanMs.toDouble(), p.sdMs.toDouble()).coerceAtLeast(p.floorMs.toDouble()).toLong()

    /** A computer seat's whole round, decided up front from its own stream (so the level never alters the signal). */
    private fun planCpu(seat: Int) {
        val p = DuelRules.profile(config.level)
        val r = DuelRules.stream(config.seed, attempts, 100 + seat)
        fun jumpTheGun(): Boolean {
            if (r.nextDouble() >= p.falseStart) return false
            val at = maxOf(liveStartAt + 300, signalAt - 80 - r.nextInt(500))
            plans += Act(seat, at, -1)
            return true
        }
        when (config.variant) {
            DuelVariant.CLASSIC -> {
                if (jumpTheGun()) return
                plans += Act(seat, signalAt + reaction(r, p), -1)
            }
            DuelVariant.COLOUR -> {
                for (i in stream.indices) {
                    if (r.nextDouble() < p.wrong) {
                        plans += Act(seat, streamStart + i.toLong() * dwell + 350, -1)
                        return
                    }
                }
                if (jumpTheGun()) return
                plans += Act(seat, signalAt + reaction(r, p) + 60, -1)
            }
            DuelVariant.ECHO -> {
                var t = signalAt + reaction(r, p) + 150
                for (j in sequence.indices) {
                    val wrongHere = r.nextDouble() < p.wrong
                    val pad = if (wrongHere) (sequence[j] + 1 + r.nextInt(DuelSymbol.ECHO_PADS - 1)) % DuelSymbol.ECHO_PADS else sequence[j]
                    plans += Act(seat, t, pad)
                    if (wrongHere) return
                    t += (p.echoStepMs * (0.8 + 0.4 * r.nextDouble())).toLong()
                }
            }
        }
    }

    // ---- time ----

    private enum class Due { GO, CPU, RESOLVE, DEADLINE, PHASE_END }

    private fun nextDue(): Pair<Long, Due>? {
        var best: Pair<Long, Due>? = null
        fun offer(at: Long, d: Due) { if (best == null || at < best!!.first) best = at to d }
        when (phase) {
            DuelPhase.COUNTDOWN -> offer(countdownEndAt, Due.PHASE_END)
            DuelPhase.LIVE -> {
                if (!goFired) offer(signalAt, Due.GO)
                if (plans.isNotEmpty()) offer(plans[0].at, Due.CPU)
                if (resolveAt >= 0) offer(resolveAt, Due.RESOLVE)
                offer(deadlineAt, Due.DEADLINE)
            }
            DuelPhase.RESULT -> offer(resultEndAt, Due.PHASE_END)
            DuelPhase.OVER -> {}
        }
        return best
    }

    /** Advances the round to [now], processing everything due in time order. */
    fun tick(now: Long) {
        var guard = 0
        while (guard++ < 100_000) {
            val (at, what) = nextDue() ?: break
            if (at > now) break
            if (at > clockNow) clockNow = at
            when (what) {
                Due.PHASE_END -> if (phase == DuelPhase.COUNTDOWN) phase = DuelPhase.LIVE else endResult()
                Due.GO -> { goFired = true; emit(DuelEventKind.GO, -1) }
                Due.CPU -> { val a = plans.removeAt(0); register(a.seat, a.at, a.pad) }
                Due.RESOLVE -> resolve()
                Due.DEADLINE -> finishRound(RoundEnd.TOO_SLOW, -1)
            }
        }
        if (now > clockNow) clockNow = now
    }

    private fun endResult() {
        if (overPending) {
            phase = DuelPhase.OVER
            emit(DuelEventKind.MATCH_OVER, matchWinner)
        } else {
            beginRound(clockNow.coerceAtLeast(resultEndAt), first = false)
        }
    }

    // ---- input ----

    /**
     * Seat [seat] pressed (pad [pad] in echo, ignored elsewhere) at [eventTime] (the moment the finger touched, on
     * the same clock as [now]). Returns what it did.
     */
    fun press(seat: Int, eventTime: Long, pad: Int, now: Long): PressResult {
        tick(now)
        if (seat !in 0 until config.seats) return PressResult.IGNORED
        val r = register(seat, minOf(eventTime, now), pad)
        tick(now)
        return r
    }

    private fun register(seat: Int, t: Long, pad: Int): PressResult {
        if (phase != DuelPhase.LIVE || seatStatus[seat] != SeatStatus.ARMED) return PressResult.IGNORED
        if (t < liveStartAt) return PressResult.IGNORED
        when (config.variant) {
            DuelVariant.CLASSIC, DuelVariant.COLOUR -> {
                if (t < signalAt) {
                    if (config.relaxed) return PressResult.EARLY_IGNORED
                    return lock(seat, DuelEventKind.FALSE_START, PressResult.FALSE_START)
                }
                return markDone(seat, t)
            }
            DuelVariant.ECHO -> {
                if (t < signalAt) return PressResult.EARLY_IGNORED
                val want = sequence[echoProgress[seat]]
                val got = if (config.oneSwitch) DuelRules.scanPad(t - signalAt) else pad
                if (got == want) {
                    echoProgress[seat]++
                    return if (echoProgress[seat] >= sequence.size) markDone(seat, t) else PressResult.PROGRESS
                }
                if (config.relaxed) return PressResult.IGNORED
                return lock(seat, DuelEventKind.WRONG, PressResult.WRONG)
            }
        }
    }

    private fun armedCount() = seatStatus.count { it == SeatStatus.ARMED }

    private fun markDone(seat: Int, t: Long): PressResult {
        seatStatus[seat] = SeatStatus.DONE
        doneAt[seat] = t
        reactionMs[seat] = (t - signalAt).toInt()
        if (resolveAt < 0) resolveAt = clockNow + DuelRules.GATHER_MS
        if (armedCount() == 0) resolveAt = clockNow
        return PressResult.DONE
    }

    private fun lock(seat: Int, kind: String, result: PressResult): PressResult {
        seatStatus[seat] = SeatStatus.LOCKED
        falseStarts[seat]++
        emit(kind, seat)
        val anyDone = seatStatus.any { it == SeatStatus.DONE }
        if (!anyDone && resolveAt < 0) {
            // The last seat still standing wins at once. (Every earlier foul left at least two seats armed or ended
            // the round, so "nobody left" cannot happen here.)
            if (armedCount() == 1) finishRound(RoundEnd.DEFAULT_WIN, seatStatus.indexOfFirst { it == SeatStatus.ARMED })
        }
        return result
    }

    private fun resolve() {
        val done = (0 until config.seats).filter { seatStatus[it] == SeatStatus.DONE }.sortedWith(compareBy({ doneAt[it] }, { it }))
        if (done.isEmpty()) { finishRound(RoundEnd.TOO_SLOW, -1); return }
        val first = done[0]
        if (done.size > 1 && doneAt[done[1]] - doneAt[first] <= DuelRules.TIE_MS) finishRound(RoundEnd.DEAD_HEAT, -1)
        else finishRound(RoundEnd.WIN, first)
    }

    private fun finishRound(end: RoundEnd, winner: Int) {
        if (phase != DuelPhase.LIVE) return
        val ms = if (winner >= 0) reactionMs[winner].coerceAtLeast(0) else 0
        lastOutcome = RoundOutcome(end, winner, reactionMs.toList(), seatStatus.map { it == SeatStatus.LOCKED })
        plans.clear(); resolveAt = -1L
        if (end == RoundEnd.WIN || end == RoundEnd.DEFAULT_WIN) {
            wins[winner]++; roundsPlayed++
            if (end == RoundEnd.WIN && (bestMs[winner] < 0 || ms < bestMs[winner])) bestMs[winner] = ms
        }
        phase = DuelPhase.RESULT
        resultEndAt = clockNow + DuelRules.RESULT_MS
        emit(
            when (end) {
                RoundEnd.WIN -> DuelEventKind.ROUND_WIN
                RoundEnd.DEFAULT_WIN -> DuelEventKind.DEFAULT_WIN
                RoundEnd.DEAD_HEAT -> DuelEventKind.DEAD_HEAT
                RoundEnd.TOO_SLOW -> DuelEventKind.TOO_SLOW
            },
            winner, ms
        )
        val top = wins.max()
        if (top >= targetWins || attempts >= DuelRules.attemptCap(config.bestOf)) {
            overPending = true
            val leaders = wins.count { it == top }
            matchWinner = if (top > 0 && leaders == 1) wins.indexOf(top) else -1
        }
    }

    // ---- what to draw ----

    /** What every zone shows at time [t] (any time: it is a pure function of the round). */
    fun displayAt(t: Long): DuelDisplay = when (phase) {
        DuelPhase.COUNTDOWN -> DuelDisplay(DisplayKind.GET_READY)
        DuelPhase.RESULT, DuelPhase.OVER -> DuelDisplay(DisplayKind.RESULT)
        DuelPhase.LIVE -> when (config.variant) {
            DuelVariant.CLASSIC -> if (t >= signalAt) DuelDisplay(DisplayKind.GO) else DuelDisplay(DisplayKind.WAIT)
            DuelVariant.COLOUR -> when {
                t >= signalAt -> DuelDisplay(DisplayKind.TARGET, target)
                t < streamStart -> DuelDisplay(DisplayKind.WAIT)
                else -> {
                    val i = ((t - streamStart) / dwell).toInt().coerceIn(0, stream.size - 1)
                    val inSlot = ((t - streamStart) % dwell).toInt()
                    if (inSlot < dwell - DuelRules.COLOUR_GAP_MS) DuelDisplay(DisplayKind.DECOY, stream[i]) else DuelDisplay(DisplayKind.WAIT)
                }
            }
            DuelVariant.ECHO -> when {
                t >= signalAt -> DuelDisplay(DisplayKind.YOUR_TURN)
                t < showStart -> DuelDisplay(DisplayKind.WAIT)
                else -> {
                    val slot = showMs + DuelRules.ECHO_GAP_MS
                    val i = ((t - showStart) / slot).toInt()
                    if (i >= sequence.size) DuelDisplay(DisplayKind.WAIT)
                    else if (((t - showStart) % slot).toInt() < showMs) DuelDisplay(DisplayKind.ECHO_LIT, pad = sequence[i])
                    else DuelDisplay(DisplayKind.ECHO_REST)
                }
            }
        }
    }

    /** The echo slot (index into [sequence]) lit at [t], or -1 when none is lit (used to play one tone per lit pad). */
    fun echoSlotAt(t: Long): Int {
        if (config.variant != DuelVariant.ECHO || phase != DuelPhase.LIVE || t < showStart || t >= signalAt) return -1
        val slot = showMs + DuelRules.ECHO_GAP_MS
        val i = ((t - showStart) / slot).toInt()
        return if (i < sequence.size && ((t - showStart) % slot).toInt() < showMs) i else -1
    }

    /** True once the signal has gone (a press now counts). */
    val signalLive: Boolean get() = phase == DuelPhase.LIVE && goFired

    /** The pad the one-switch cursor is on at [t] (echo input only). */
    fun scanPadAt(t: Long): Int = DuelRules.scanPad(t - signalAt)
}
