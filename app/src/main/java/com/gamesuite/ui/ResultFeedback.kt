package com.gamesuite.ui

import android.content.Context
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.MatchOutcome
import com.gamesuite.games.cards.CardSounds
import com.gamesuite.haptics.HapticSignal
import com.gamesuite.haptics.performHaptic
import kotlinx.coroutines.delay

/** One pitched note of the result cue: the existing card_place clip at [rate], [delayMs] after the cue starts. */
data class ResultNote(val rate: Float, val delayMs: Long)

/** What to play when a match ends. Pure data so the decision is unit-testable. */
data class ResultFeedbackPlan(val haptic: HapticSignal?, val notes: List<ResultNote>) {
    val isSilent: Boolean get() = haptic == null && notes.isEmpty()
}

/**
 * Chooses the end-of-match cue from the existing vocabulary only (no new assets): the pitch-shifted
 * card_place clip for sound and [HapticSignal] for the vibration.
 *  - win: a rising three-note figure + CELEBRATION; loss: one low note + FAILURE; draw: one level note + NORMAL_ACTION;
 *  - [soundEnabled] / [hapticsEnabled] are the Settings switches; either off silences just that channel;
 *  - [reducedMotion] collapses each cue to a single note and the gentler SUCCESS buzz (no multi-beat sequence);
 *  - an aborted match, an unscored one (spectator) or no outcome at all plays nothing.
 */
fun resultFeedbackPlan(
    outcome: LocalOutcome?,
    aborted: Boolean,
    soundEnabled: Boolean,
    hapticsEnabled: Boolean,
    reducedMotion: Boolean
): ResultFeedbackPlan {
    if (aborted || outcome == null) return ResultFeedbackPlan(null, emptyList())
    val notes = when (outcome) {
        LocalOutcome.WIN ->
            if (reducedMotion) listOf(ResultNote(1.5f, 0L))
            else listOf(ResultNote(1.0f, 0L), ResultNote(1.26f, 110L), ResultNote(1.5f, 220L))
        LocalOutcome.LOSS -> listOf(ResultNote(0.6f, 0L))
        LocalOutcome.DRAW -> listOf(ResultNote(1.0f, 0L))
    }
    val haptic = when (outcome) {
        LocalOutcome.WIN -> if (reducedMotion) HapticSignal.SUCCESS else HapticSignal.CELEBRATION
        LocalOutcome.LOSS -> HapticSignal.FAILURE
        LocalOutcome.DRAW -> HapticSignal.NORMAL_ACTION
    }
    return ResultFeedbackPlan(
        haptic = if (hapticsEnabled) haptic else null,
        notes = if (soundEnabled) notes else emptyList()
    )
}

/** The plan for a published [MatchOutcome] (aborted results are never published, but the flag is honoured anyway). */
fun resultFeedbackPlanFor(
    outcome: MatchOutcome,
    soundEnabled: Boolean,
    hapticsEnabled: Boolean,
    reducedMotion: Boolean
): ResultFeedbackPlan = resultFeedbackPlan(
    outcome = outcome.localOutcome,
    aborted = outcome.result.wasAborted,
    soundEnabled = soundEnabled,
    hapticsEnabled = hapticsEnabled,
    reducedMotion = reducedMotion
)

/**
 * Remembers which outcome object has already been given its cue, so a re-delivered value (a
 * recomposition, an Activity recreation that replays the observer) never plays twice. Identity, not
 * equality: two consecutive matches with equal results are two separate outcomes.
 */
class ResultFeedbackGate {
    private var last: Any? = null

    /** True the first time [outcome] is seen, false for every repeat of the same instance. */
    @Synchronized
    fun firstTime(outcome: Any): Boolean {
        if (outcome === last) return false
        last = outcome
        return true
    }
}

/** Plays [plan]: haptic immediately, the notes at their offsets. Call from a scope that outlives the outcome observer. */
suspend fun playResultFeedback(context: Context, plan: ResultFeedbackPlan) {
    if (plan.isSilent) return
    plan.haptic?.let { performHaptic(context, it) }
    if (plan.notes.isEmpty()) return
    val sounds = CardSounds.get(context)
    var elapsed = 0L
    for (note in plan.notes) {
        if (note.delayMs > elapsed) {
            delay(note.delayMs - elapsed)
            elapsed = note.delayMs
        }
        sounds.playPlace(note.rate)
    }
}
