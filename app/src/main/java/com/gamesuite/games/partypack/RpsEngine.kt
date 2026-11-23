package com.gamesuite.games.partypack

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/*
 * Rock-Paper-Showdown: the rules of a match for 2 to 4 seats on one screen, pure Kotlin.
 *
 * SECRECY. The picks live in a private array. Everything the outside world can read is [RpsView], whose
 * `picks` are null until the round is revealed; before that a seat shows only "locked". The computer
 * seats choose through [RpsBrain], which is handed no current pick (see RpsBrainContext).
 *
 * TIME. The engine has no clock: [advance] is told how many milliseconds passed.
 */

enum class RpsPhase { PICK, COUNTDOWN, RESULT, OVER }

enum class RpsEventKind { LOCKED, UNLOCKED, COUNT, REVEAL, AUTO_PICK, ELIMINATED, ROUND_START, WIN }

data class RpsEvent(val kind: RpsEventKind, val seat: Int = -1, val a: Int = 0)

/** Why a command did nothing. NONE means it worked. */
enum class RpsReject { NONE, WRONG_PHASE, NOT_A_SEAT, OUT_OF_PLAY, BOT_SEAT, BAD_MOVE, NOT_LOCKED }

data class RpsSeat(val bot: Boolean, val level: CpuDifficulty = CpuDifficulty.MEDIUM)

data class RpsConfig(
    val seats: List<RpsSeat>,
    val variant: RpsVariant = RpsVariant.CLASSIC,
    val format: RpsFormat = RpsFormat.BEST_OF_3,
    /** 0 = no limit; otherwise milliseconds a round waits for slow players before picking for them. */
    val pickTimerMs: Int = 0,
    /** The Relaxed assist: a doubled timer, a slower countdown and reveal, and no auto-advance past the result. */
    val relaxed: Boolean = false,
    val seed: Long = 1L
) {
    init { require(seats.size in PpSeatLayout.MIN_SEATS..PpSeatLayout.MAX_SEATS) { "2..4 seats" } }
}

/** What a device may show. [picks] is all null until the round is revealed. */
data class RpsView(
    val phase: RpsPhase,
    val round: Int,
    val wins: List<Int>,
    val active: List<Boolean>,
    val locked: List<Boolean>,
    val picks: List<RpsMove?>,
    val countdown: Int,
    val outcome: RpsOutcome?,
    val tieStreak: Int,
    val winners: List<Int>,
    val timerLeftMs: Int
)

class RpsEngine(
    val config: RpsConfig,
    private val brainFor: (seat: Int, rng: Random) -> RpsBrain = { seat, rng ->
        RpsCpuBrain(config.seats[seat].level, rng, config.variant)
    }
) {
    val n: Int = config.seats.size
    private val rng = Random(config.seed)
    private val brains: Array<RpsBrain?> = Array(n) { s -> if (config.seats[s].bot) brainFor(s, Random(config.seed * 31 + s)) else null }

    private val picks = arrayOfNulls<RpsMove>(n)
    private val locked = BooleanArray(n)
    private val active = BooleanArray(n) { true }
    private val wins = IntArray(n)
    private val cpuDelay = IntArray(n)
    private var phase = RpsPhase.PICK
    private var round = 0
    private var phaseMs = 0
    private var countdown = 0
    private var countMs = 0
    private var tieStreak = 0
    private var outcome: RpsOutcome? = null
    private var winners: List<Int> = emptyList()
    private val events = ArrayDeque<RpsEvent>()
    private val revealed = ArrayDeque<RpsRoundRecord>()

    init { startRound() }

    // ---- timing constants ------------------------------------------------------------------

    private val countStepMs: Int get() = if (config.relaxed) 1300 else 800
    private val timerMs: Int get() = if (config.pickTimerMs <= 0) 0 else if (config.relaxed) config.pickTimerMs * 2 else config.pickTimerMs

    /** After a result the next round starts by itself this long afterwards; 0 means the players tap Next. */
    val autoAdvanceMs: Int get() = if (config.relaxed) 0 else 4200

    // ---- reading ---------------------------------------------------------------------------

    fun view(): RpsView {
        val revealedNow = phase == RpsPhase.RESULT || phase == RpsPhase.OVER
        return RpsView(
            phase = phase, round = round, wins = wins.toList(), active = active.toList(), locked = locked.toList(),
            picks = if (revealedNow) outcome?.picks ?: List(n) { null } else List(n) { null },
            countdown = if (phase == RpsPhase.COUNTDOWN) countdown else 0,
            outcome = if (revealedNow) outcome else null,
            tieStreak = tieStreak, winners = winners,
            timerLeftMs = if (phase == RpsPhase.PICK && timerMs > 0) (timerMs - phaseMs).coerceAtLeast(0) else 0
        )
    }

    fun drainEvents(): List<RpsEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList(); events.clear(); return out
    }

    private fun emit(e: RpsEvent) { events.addLast(e); while (events.size > 100) events.removeFirst() }

    val activeCount: Int get() = active.count { it }
    val isOver: Boolean get() = phase == RpsPhase.OVER

    // ---- commands --------------------------------------------------------------------------

    /** A person locks [move] in. The pick stays secret until the reveal. */
    fun lock(seat: Int, move: RpsMove): RpsReject {
        val r = checkHuman(seat); if (r != RpsReject.NONE) return r
        if (phase != RpsPhase.PICK) return RpsReject.WRONG_PHASE
        if (move !in config.variant.moves) return RpsReject.BAD_MOVE
        picks[seat] = move
        locked[seat] = true
        emit(RpsEvent(RpsEventKind.LOCKED, seat))
        maybeStartCountdown()
        return RpsReject.NONE
    }

    /** A person takes their lock back, while the others are still choosing. */
    fun unlock(seat: Int): RpsReject {
        val r = checkHuman(seat); if (r != RpsReject.NONE) return r
        if (phase != RpsPhase.PICK) return RpsReject.WRONG_PHASE
        if (!locked[seat]) return RpsReject.NOT_LOCKED
        picks[seat] = null
        locked[seat] = false
        emit(RpsEvent(RpsEventKind.UNLOCKED, seat))
        return RpsReject.NONE
    }

    private fun checkHuman(seat: Int): RpsReject = when {
        seat !in 0 until n -> RpsReject.NOT_A_SEAT
        config.seats[seat].bot -> RpsReject.BOT_SEAT
        !active[seat] -> RpsReject.OUT_OF_PLAY
        else -> RpsReject.NONE
    }

    /** Starts the next round after a result (no-op otherwise). */
    fun next(): Boolean {
        if (phase != RpsPhase.RESULT) return false
        startRound()
        return true
    }

    // ---- time ------------------------------------------------------------------------------

    fun advance(ms: Int) {
        if (ms <= 0) return
        var left = ms.coerceAtMost(5000)
        // run in slices so a long frame cannot skip a countdown beat
        while (left > 0) {
            val slice = left.coerceAtMost(50)
            left -= slice
            stepMs(slice)
        }
    }

    private fun stepMs(ms: Int) {
        when (phase) {
            RpsPhase.PICK -> {
                phaseMs += ms
                for (s in 0 until n) {
                    if (active[s] && !locked[s] && config.seats[s].bot && phaseMs >= cpuDelay[s]) cpuLock(s)
                }
                if (phase == RpsPhase.PICK && timerMs > 0 && phaseMs >= timerMs) autoPick()
                maybeStartCountdown()
            }
            RpsPhase.COUNTDOWN -> {
                countMs += ms
                if (countMs >= countStepMs) {
                    countMs -= countStepMs
                    countdown--
                    if (countdown <= 0) reveal() else emit(RpsEvent(RpsEventKind.COUNT, a = countdown))
                }
            }
            RpsPhase.RESULT -> {
                phaseMs += ms
                if (autoAdvanceMs > 0 && phaseMs >= autoAdvanceMs) next()
            }
            RpsPhase.OVER -> {}
        }
    }

    private fun cpuLock(s: Int) {
        val brain = brains[s] ?: return
        val opponents = (0 until n).filter { it != s && active[it] }
        picks[s] = brain.choose(RpsBrainContext(config.variant, s, opponents))
        locked[s] = true
        emit(RpsEvent(RpsEventKind.LOCKED, s))
    }

    private fun autoPick() {
        for (s in 0 until n) if (active[s] && !locked[s]) {
            val moves = config.variant.moves
            picks[s] = moves[rng.nextInt(moves.size)]
            locked[s] = true
            emit(RpsEvent(RpsEventKind.AUTO_PICK, s))
        }
    }

    private fun maybeStartCountdown() {
        if (phase != RpsPhase.PICK) return
        if ((0 until n).any { active[it] && !locked[it] }) return
        phase = RpsPhase.COUNTDOWN
        countdown = 3
        countMs = 0
        emit(RpsEvent(RpsEventKind.COUNT, a = 3))
    }

    private fun startRound() {
        round++
        phase = RpsPhase.PICK
        phaseMs = 0
        outcome = null
        for (s in 0 until n) {
            picks[s] = null
            locked[s] = false
            // a computer thinks for a natural while: never under 0.45 s, a little longer on Easy
            val base = when (config.seats[s].level) { CpuDifficulty.EASY -> 1300; CpuDifficulty.MEDIUM -> 1000; CpuDifficulty.HARD -> 800 }
            cpuDelay[s] = 450 + rng.nextInt(base)
        }
        emit(RpsEvent(RpsEventKind.ROUND_START, a = round))
    }

    private fun reveal() {
        val live = List(n) { if (active[it]) picks[it] else null }
        val o = RpsRules.resolve(config.variant, live)
        outcome = o
        val results = List(n) { RpsRules.resultFor(o, it) }
        val record = RpsRoundRecord(live, results)
        revealed.addLast(record)
        while (revealed.size > HISTORY_KEPT) revealed.removeFirst()
        brains.forEach { it?.observe(record) }
        phase = RpsPhase.RESULT
        phaseMs = 0
        emit(RpsEvent(RpsEventKind.REVEAL))
        if (o.tie) { tieStreak++; return }
        tieStreak = 0
        for (w in o.winners) wins[w]++
        if (config.format.elimination) {
            for (l in o.losers) { active[l] = false; emit(RpsEvent(RpsEventKind.ELIMINATED, l)) }
            if (activeCount <= 1) finish(listOf(active.indexOfFirst { it }))
        } else {
            val top = wins.max()
            val leaders = wins.indices.filter { wins[it] == top }
            if (top >= config.format.winsNeeded && leaders.size == 1) finish(leaders)
        }
    }

    private fun finish(w: List<Int>) {
        winners = w
        phase = RpsPhase.OVER
        emit(RpsEvent(RpsEventKind.WIN, w.firstOrNull() ?: -1))
    }

    // ---- snapshots -------------------------------------------------------------------------

    /** The revealed rounds so far (public information), newest last, bounded. */
    fun history(): List<RpsRoundRecord> = revealed.toList()

    /** Puts a validated mid-match state back: scores, who is still in, the round number and the revealed history. A round in progress restarts. */
    fun restoreState(wins: List<Int>, active: List<Boolean>, round: Int, history: List<RpsRoundRecord>): Boolean {
        if (wins.size != n || active.size != n || round < 1 || round > 100_000) return false
        val needed = config.format.winsNeeded
        if (wins.any { it < 0 || it > 100_000 }) return false
        if (!config.format.elimination && wins.any { it > needed }) return false
        if (active.count { it } < 2) return false
        if (!config.format.elimination && active.any { !it }) return false
        if (history.size > HISTORY_KEPT) return false
        for (h in history) {
            if (h.picks.size != n || h.results.size != n) return false
            if (h.picks.any { it != null && it !in config.variant.moves }) return false
            if (h.results.any { it !in -1..1 }) return false
        }
        if (!config.format.elimination) {
            val top = wins.max()
            if (top >= needed && wins.count { it == top } == 1) return false // that match was already decided
        }
        for (i in 0 until n) { this.wins[i] = wins[i]; this.active[i] = active[i] }
        this.round = round - 1
        tieStreak = 0
        revealed.clear()
        history.forEach { revealed.addLast(it); brains.forEach { b -> b?.observe(it) } }
        winners = emptyList()
        startRound()
        return true
    }

    companion object { const val HISTORY_KEPT = 40 }
}
