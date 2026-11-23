package com.gamesuite.haptics

import kotlinx.coroutines.delay

/**
 * One scheduled vibration: play [signal] [delayMs] milliseconds after the START of its
 * [HapticSequence] (an absolute offset, not relative to the previous cue).
 */
data class HapticCue(val signal: HapticSignal, val delayMs: Long) {
    init { require(delayMs >= 0) { "delayMs must be >= 0" } }
}

/**
 * An ordered list of [HapticCue]s forming one gesture ("place a disc, then ripple the flips").
 * Pure data: per-game cue maps return these and the unit tests assert on them.
 *
 * [leadInMs] is the part of the cue offsets spent waiting for an on-screen animation to reach its
 * moment (a Connect Four disc still falling). It does not count against the "gesture is at most
 * [MAX_SPAN_MS] long" budget, because that budget is about how long the motor keeps buzzing once the
 * moment arrives, not about how long the animation takes.
 */
class HapticSequence(cues: List<HapticCue>, val leadInMs: Long = 0L) {
    val cues: List<HapticCue> = cues.sortedBy { it.delayMs }

    val isEmpty: Boolean get() = this.cues.isEmpty()

    /** Moment (from sequence start) the last vibration finishes. */
    val endMs: Long
        get() = this.cues.maxOfOrNull { it.delayMs + HapticPatterns.patternFor(it.signal).totalMs } ?: 0L

    /** [endMs] minus the animation lead-in: how long the gesture really occupies the motor. */
    val spanMs: Long get() = (endMs - leadInMs).coerceAtLeast(0L)

    val hasCelebration: Boolean get() = this.cues.any { it.signal == HapticSignal.CELEBRATION }

    val signals: List<HapticSignal> get() = this.cues.map { it.signal }

    /** The same gesture started [ms] later (used to run an end-of-game cue after a move cue). */
    fun shifted(ms: Long): HapticSequence =
        HapticSequence(this.cues.map { it.copy(delayMs = it.delayMs + ms) }, leadInMs + ms)

    /** This gesture, then [next] starting [gapMs] after this one finishes. */
    fun then(next: HapticSequence, gapMs: Long = 40L): HapticSequence {
        if (next.isEmpty) return this
        if (isEmpty) return next
        return HapticSequence(this.cues + next.shifted(endMs + gapMs).cues, leadInMs)
    }

    /**
     * Plays every cue through [fire], waiting between cues with [wait] (kotlinx `delay` by default;
     * tests inject a recording stand-in). Cancelling the calling coroutine stops the rest.
     */
    suspend fun play(wait: suspend (Long) -> Unit = { delay(it) }, fire: (HapticSignal) -> Unit) {
        var elapsed = 0L
        for (cue in cues) {
            val gap = cue.delayMs - elapsed
            if (gap > 0) wait(gap)
            elapsed = cue.delayMs
            fire(cue.signal)
        }
    }

    override fun toString(): String =
        cues.joinToString(prefix = "HapticSequence[", postfix = "]") { "${it.signal}@${it.delayMs}" }

    companion object {
        /** No vibration at all. */
        val EMPTY = HapticSequence(emptyList())

        /** One signal, right now. */
        fun single(signal: HapticSignal) = HapticSequence(listOf(HapticCue(signal, 0L)))

        fun of(vararg cues: HapticCue) = HapticSequence(cues.toList())

        /** Longest a non-celebration gesture may keep the motor busy once its moment arrives. */
        const val MAX_SPAN_MS = 450L
    }
}

/** How a finished board looks to the person holding the phone. */
enum class HapticOutcome { WIN, LOSS, DRAW }

/** Shared decisions every per-game cue map needs, kept pure so they are tested once. */
object HapticRules {
    /**
     * Haptics play only when a person is actually playing: never during a Computer Arena series
     * ([arenaActive]) and never at a table of nothing but bots.
     */
    fun shouldPlay(arenaActive: Boolean, isBot: List<Boolean>): Boolean =
        !arenaActive && isBot.any { !it }

    /**
     * True when [mover]'s action should get the lighter "somebody else moved" cue: a bot, or (in a
     * networked game, [localSeat] non-null) the remote seat. Two humans on one phone (no bots,
     * [localSeat] null) are both "you".
     */
    fun isLighterMover(mover: Int, isBot: List<Boolean>, localSeat: Int?): Boolean =
        isBot.getOrElse(mover) { false } || (localSeat != null && mover != localSeat)

    /**
     * The result as the person holding the phone sees it. [winnerIndex] null or negative = draw.
     * Networked: only the local seat's win is a win. Otherwise any human's win is (two humans
     * sharing a phone), a bot's win is a loss.
     */
    fun outcomeFor(winnerIndex: Int?, isBot: List<Boolean>, localSeat: Int?): HapticOutcome {
        if (winnerIndex == null || winnerIndex < 0) return HapticOutcome.DRAW
        val won = if (localSeat != null) winnerIndex == localSeat else !isBot.getOrElse(winnerIndex) { false }
        return if (won) HapticOutcome.WIN else HapticOutcome.LOSS
    }
}

/**
 * Remembers the previously seen value of something so a screen can react to a CHANGE only. The
 * first value ever given is the baseline (a restored or resumed game, the first frame): [update]
 * returns null for it, so nothing fires on restore.
 */
class ChangeTracker<T> {
    private var has = false
    private var last: T? = null

    /** The previous value, or null when [value] is the first one seen. */
    fun update(value: T): T? {
        val previous = if (has) last else null
        has = true
        last = value
        return previous
    }
}
