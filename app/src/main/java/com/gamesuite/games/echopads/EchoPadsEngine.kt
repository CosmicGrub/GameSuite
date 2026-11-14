package com.gamesuite.games.echopads

import kotlin.random.Random

/**
 * One match's setup. [players] must fit the [mode] (see [EchoRules.seatRange]); [delayScale] is the app's game
 * speed (above 1 slower, below 1 quicker); [forgiving] gives every turn one free try (kids play: the first
 * wrong pad only shows the pattern again).
 */
data class EchoConfig(
    val mode: EchoMode,
    val players: Int,
    val speed: EchoSpeed = EchoSpeed.NORMAL,
    val delayScale: Float = 1f,
    val forgiving: Boolean = false
) {
    init {
        require(players in EchoRules.seatRange(mode)) { "$mode needs ${EchoRules.seatRange(mode)} players, not $players" }
    }

    /** Pass-and-play waits for each player to say they are ready, because the device was just handed over. */
    val handoff: Boolean get() = mode == EchoMode.PASS_AND_PLAY
}

/**
 * Everything needed to carry on a match from the start of a turn: the pattern, whose turn it is and how the round
 * stands. A resumed match always restarts the turn in progress (the pattern is shown again).
 */
data class EchoProgress(
    val sequence: List<Int>,
    /** The players of this round in turn order, and the position in it of the player whose turn it is. */
    val order: List<Int>,
    val orderPos: Int,
    /** Per player this round: -1 not yet played, 0 missed, 1 copied it. */
    val results: List<Int>,
    val out: List<Boolean>,
    /** Players in the order they went out. */
    val elimination: List<Int>,
    /** Per player: how many patterns they have copied. */
    val completed: List<Int>
) {
    /** True when this progress is internally consistent and fits [config]. A corrupt or foreign save is not. */
    fun isValidFor(config: EchoConfig): Boolean {
        val n = config.players
        val round = sequence.size
        if (round !in 1..EchoRules.MAX_LENGTH || sequence.any { it !in 0 until EchoPads.COUNT }) return false
        if (results.size != n || out.size != n || completed.size != n) return false
        if (order.isEmpty() || order.size > n || order.any { it !in 0 until n } || order.toSet().size != order.size) return false
        if (orderPos !in order.indices) return false
        if (results.any { it !in -1..1 }) return false
        if (completed.any { it !in 0..round }) return false
        if (elimination.any { it !in 0 until n } || elimination.toSet().size != elimination.size) return false
        if (elimination.any { !out[it] } || out.count { it } != elimination.size) return false
        // Who has played this round is exactly the players before the current position.
        order.forEachIndexed { i, p -> if ((results[p] == -1) != (i >= orderPos)) return false }
        for (p in 0 until n) if (p !in order && results[p] != -1) return false
        return when (config.mode) {
            EchoMode.SOLO -> order == listOf(0) && out.none { it } && completed[0] == round - 1
            EchoMode.PASS_AND_PLAY ->
                order.size >= 2 &&
                    (0 until n).all { p -> p in order || out[p] } &&
                    order.all { p -> (results[p] == 0) == out[p] }
            EchoMode.DUEL ->
                order == (if (round % 2 == 1) listOf(0, 1) else listOf(1, 0)) && out.none { it }
        }
    }
}

enum class EchoEventKind {
    /** A turn is about to begin (the screen shows whose it is). */
    TURN_READY,

    /** A pad of the pattern lit up (play its tone). */
    PAD_SHOWN,

    /** The pattern has played: the player may copy it now. */
    YOUR_TURN,

    /** The player tapped [EchoEvent.pad]. */
    PAD_TAPPED,

    /** The player copied the whole pattern. */
    SEQUENCE_OK,

    /** A wrong pad on a free try: the pattern plays again. */
    RETRY,

    /** A wrong pad. */
    MISS,

    /** The player is out. */
    OUT,

    /** The pattern grew by one for a new round. */
    ROUND_GROWN,

    /** The match is over. */
    MATCH_OVER
}

data class EchoEvent(val serial: Int, val kind: EchoEventKind, val player: Int = -1, val pad: Int = -1)

/**
 * The rules of Echo Pads with no Compose or Android in it, so a seeded [Random] and a hand-driven clock replay
 * a whole match exactly. Nothing runs by itself: the screen calls [tick] each frame and [tap] / [ready] on input,
 * all with the current time in milliseconds.
 *
 * THE STATE MACHINE. A turn runs READY -> SHOWING -> WAITING -> CHECKING:
 *  - READY: hand-off (pass and play waits for [ready]) or a short lead-in.
 *  - SHOWING: the pattern plays pad by pad; every [tap] is [TapResult.IGNORED].
 *  - WAITING: the only phase that accepts taps; the player copies the pattern.
 *  - CHECKING: the last tap decided the turn (copied, free retry, or miss): a feedback pause, taps ignored.
 * Then the turn resolves: the next player's turn, a new round (the pattern grows by one), or the end.
 *
 * MODES. Solo: one player, one pattern that grows each round, the first miss ends it; the score is the number of
 * patterns copied. Pass and play: every player still in copies the pattern in turn; players who miss are out at
 * the end of the round (if everybody left misses together they tie); the last one standing wins. Duel: two
 * players copy the same pattern each round (the first one alternates); if exactly one misses, the other wins,
 * if both miss it is a tie, if both copy it the pattern grows.
 */
class EchoPadsEngine(val config: EchoConfig, private val random: Random = Random.Default) {
    private val seq = ArrayList<Int>()
    private val out = BooleanArray(config.players)
    private val done = IntArray(config.players)
    private val elim = ArrayList<Int>()
    private val results = IntArray(config.players) { PENDING }
    private var order: List<Int> = emptyList()
    private var orderPos = 0
    private var showStart = 0L
    private var shown = 0
    private var deadline = NEVER
    private val eventLog = ArrayList<EchoEvent>()

    /** The pattern so far; its length is the round number. */
    val sequence: List<Int> get() = seq
    val round: Int get() = seq.size

    var phase: EchoPhase = EchoPhase.READY
        private set

    /** Whose turn it is (a player index), meaningful until the match is over. */
    var currentPlayer: Int = 0
        private set

    /** How many pads of the pattern the current player has copied so far this try. */
    var progress: Int = 0
        private set

    /** The outcome of the turn while it is in [EchoPhase.CHECKING]. */
    var outcome: EchoOutcome? = null
        private set

    /** True once the current turn's free try has been used. */
    var retryUsed: Boolean = false
        private set

    var timing: EchoTiming = EchoRules.timing(config.speed, config.delayScale, 1, config.forgiving)
        private set

    /** The winners once [phase] is OVER: one player, or every tied player ([isTie]); empty only for a solo game that copied nothing. */
    var winners: List<Int> = emptyList()
        private set
    var isTie: Boolean = false
        private set

    var eventSerial: Int = 0
        private set

    fun isOut(player: Int): Boolean = out.getOrElse(player) { false }
    fun completedBy(player: Int): Int = done.getOrElse(player) { 0 }
    val eliminationOrder: List<Int> get() = elim

    /** Players still in the match. */
    val alive: List<Int> get() = (0 until config.players).filter { !out[it] }

    /** What this player did in the current round: -1 not yet, 0 missed, 1 copied it. */
    fun roundResult(player: Int): Int = results.getOrElse(player) { PENDING }

    /** True when the screen should show a Ready button (pass and play hand-off). */
    val awaitingReady: Boolean get() = phase == EchoPhase.READY && deadline == NEVER

    /** Events since [serial] (exclusive), oldest first. */
    fun eventsSince(serial: Int): List<EchoEvent> = eventLog.filter { it.serial > serial }

    private fun emit(kind: EchoEventKind, player: Int = -1, pad: Int = -1) {
        eventSerial += 1
        eventLog += EchoEvent(eventSerial, kind, player, pad)
        if (eventLog.size > MAX_EVENTS) eventLog.removeAt(0)
    }

    // ---- starting ----

    /** Starts a fresh match at time [now]: a one-step pattern and the first turn. */
    fun startMatch(now: Long) {
        seq.clear()
        seq += random.nextInt(EchoPads.COUNT)
        out.fill(false)
        done.fill(0)
        elim.clear()
        winners = emptyList()
        isTie = false
        beginRound(now)
    }

    /** Carries on from [p] (a saved match) at time [now], restarting the turn in progress. False, leaving the engine untouched, when [p] is invalid. */
    fun resume(now: Long, p: EchoProgress): Boolean {
        if (!p.isValidFor(config)) return false
        seq.clear(); seq.addAll(p.sequence)
        order = p.order.toList(); orderPos = p.orderPos
        for (i in 0 until config.players) {
            results[i] = p.results[i]; out[i] = p.out[i]; done[i] = p.completed[i]
        }
        elim.clear(); elim.addAll(p.elimination)
        winners = emptyList(); isTie = false
        beginTurn(now)
        return true
    }

    /** The match as it stands, for saving (the turn in progress restarts when resumed). */
    fun progressSnapshot(): EchoProgress = EchoProgress(
        sequence = seq.toList(), order = order.toList(), orderPos = orderPos,
        results = results.toList(), out = out.toList(), elimination = elim.toList(), completed = done.toList()
    )

    private fun beginRound(now: Long) {
        order = when (config.mode) {
            EchoMode.SOLO -> listOf(0)
            EchoMode.PASS_AND_PLAY -> alive
            EchoMode.DUEL -> if (seq.size % 2 == 1) listOf(0, 1) else listOf(1, 0)
        }
        results.fill(PENDING)
        orderPos = 0
        beginTurn(now)
    }

    private fun beginTurn(now: Long) {
        currentPlayer = order[orderPos]
        progress = 0
        retryUsed = false
        outcome = null
        timing = EchoRules.timing(config.speed, config.delayScale, seq.size, config.forgiving)
        phase = EchoPhase.READY
        deadline = if (config.handoff) NEVER else now + timing.leadInMs
        emit(EchoEventKind.TURN_READY, currentPlayer)
    }

    /** The player taps Ready (hand-off). True when the pattern started. */
    fun ready(now: Long): Boolean {
        if (phase != EchoPhase.READY) return false
        startShowing(now)
        return true
    }

    private fun startShowing(now: Long) {
        phase = EchoPhase.SHOWING
        showStart = now
        shown = 0
        progress = 0
        outcome = null
        deadline = NEVER
    }

    // ---- time ----

    /** Advances to [now]. Safe to call as often as you like. */
    fun tick(now: Long) {
        if (phase == EchoPhase.READY && deadline != NEVER && now >= deadline) startShowing(now)
        if (phase == EchoPhase.SHOWING) {
            val n = seq.size
            while (shown < n && now >= stepStart(shown)) {
                emit(EchoEventKind.PAD_SHOWN, currentPlayer, seq[shown])
                shown += 1
            }
            if (now >= stepStart(n)) {
                phase = EchoPhase.WAITING
                progress = 0
                emit(EchoEventKind.YOUR_TURN, currentPlayer)
            }
        }
        if (phase == EchoPhase.CHECKING && deadline != NEVER && now >= deadline) resolveTurn(now)
    }

    private fun stepStart(step: Int): Long = showStart + EchoRules.SHOW_HEAD_MS + step * timing.stepMs

    /** The pad that is lit at [now] while the pattern plays, or -1 (between pads, or not showing). */
    fun litPad(now: Long): Int {
        val s = litStep(now)
        return if (s >= 0) seq[s] else -1
    }

    /** The index in the pattern of the pad that is lit at [now], or -1. */
    fun litStep(now: Long): Int {
        if (phase != EchoPhase.SHOWING) return -1
        val t = now - showStart - EchoRules.SHOW_HEAD_MS
        if (t < 0) return -1
        val step = (t / timing.stepMs).toInt()
        if (step !in seq.indices) return -1
        return if (t % timing.stepMs < timing.litMs) step else -1
    }

    /** The app was away for [byMs] (backgrounded): pushes every pending deadline back so nothing was missed. */
    fun shiftTime(byMs: Long) {
        if (byMs <= 0L) return
        showStart += byMs
        if (deadline != NEVER) deadline += byMs
    }

    /**
     * After the app was away while the pattern was playing: plays it again from its first pad at [now] (the player
     * did not see the lights that ran while away). Nothing else changes, so it never renews a free try. No effect in
     * any other phase.
     */
    fun restartShowing(now: Long) {
        if (phase == EchoPhase.SHOWING) startShowing(now)
    }

    // ---- input ----

    /** The current player taps [pad] at time [now]. Anything but a tap while copying is ignored. */
    fun tap(pad: Int, now: Long): TapResult {
        if (phase != EchoPhase.WAITING || pad !in 0 until EchoPads.COUNT) return TapResult.IGNORED
        // CHECKING for the length of the decision: nothing else can land in between.
        phase = EchoPhase.CHECKING
        emit(EchoEventKind.PAD_TAPPED, currentPlayer, pad)
        if (seq[progress] == pad) {
            progress += 1
            if (progress < seq.size) {
                phase = EchoPhase.WAITING
                return TapResult.PROGRESS
            }
            outcome = EchoOutcome.OK
            deadline = now + timing.feedbackMs
            emit(EchoEventKind.SEQUENCE_OK, currentPlayer)
            return TapResult.SEQUENCE_DONE
        }
        if (config.forgiving && !retryUsed) {
            retryUsed = true
            outcome = EchoOutcome.RETRY
            deadline = now + timing.feedbackMs
            emit(EchoEventKind.RETRY, currentPlayer)
            return TapResult.RETRY
        }
        outcome = EchoOutcome.MISS
        deadline = now + timing.missFeedbackMs
        emit(EchoEventKind.MISS, currentPlayer)
        return TapResult.MISS
    }

    // ---- resolving ----

    private fun resolveTurn(now: Long) {
        val p = currentPlayer
        when (outcome) {
            EchoOutcome.RETRY -> {
                startShowing(now)
                return
            }
            EchoOutcome.OK -> {
                results[p] = 1
                done[p] += 1
            }
            EchoOutcome.MISS -> {
                results[p] = 0
                if (config.mode == EchoMode.PASS_AND_PLAY) {
                    out[p] = true
                    elim += p
                    emit(EchoEventKind.OUT, p)
                }
            }
            null -> return
        }
        orderPos += 1
        if (orderPos < order.size) beginTurn(now) else endRound(now)
    }

    private fun endRound(now: Long) {
        val atCap = seq.size >= EchoRules.MAX_LENGTH
        when (config.mode) {
            EchoMode.SOLO -> {
                if (results[0] != 1) finish(emptyList(), tie = false)
                else if (atCap) finish(listOf(0), tie = false)
                else grow(now)
            }
            EchoMode.PASS_AND_PLAY -> {
                val left = alive
                when {
                    left.isEmpty() -> finish(order, tie = true)
                    left.size == 1 -> finish(left, tie = false)
                    atCap -> finish(left, tie = true)
                    else -> grow(now)
                }
            }
            EchoMode.DUEL -> {
                val a = results[0] == 1
                val b = results[1] == 1
                when {
                    a && b -> if (atCap) finish(listOf(0, 1), tie = true) else grow(now)
                    a -> { loseDuel(1); finish(listOf(0), tie = false) }
                    b -> { loseDuel(0); finish(listOf(1), tie = false) }
                    else -> finish(listOf(0, 1), tie = true)
                }
            }
        }
    }

    private fun loseDuel(player: Int) {
        out[player] = true
        elim += player
        emit(EchoEventKind.OUT, player)
    }

    private fun grow(now: Long) {
        seq += random.nextInt(EchoPads.COUNT)
        emit(EchoEventKind.ROUND_GROWN)
        beginRound(now)
    }

    private fun finish(who: List<Int>, tie: Boolean) {
        winners = who
        isTie = tie
        phase = EchoPhase.OVER
        outcome = null
        deadline = NEVER
        emit(EchoEventKind.MATCH_OVER, who.firstOrNull() ?: -1)
    }

    companion object {
        private const val PENDING = -1
        private const val NEVER = Long.MAX_VALUE
        private const val MAX_EVENTS = 160
    }
}
