package com.gamesuite.style.gem

import com.gamesuite.games.gemcascade.GemEvent
import com.gamesuite.style.MotionLevel

/**
 * Turns an engine step log into an ordered animation timeline. PURE: the timeline depends only on the events and the
 * motion level, never on the art style (flat, 2.5D, 3D replay the same steps; they only draw them differently) and never
 * on the sim. Reduced Motion gives zero durations and no bursts. Game speed is applied by the player at playback.
 */
enum class StepKind {
    DEAL, SWAP, SWAP_BACK, ROTATE, ROTATE_BACK, MATCH, SPECIAL_CREATED, SPECIAL_FIRE, COMBO, OBSTACLE, CLEAR,
    CHAIN, SCORE, SETTLE, PIECE_MOVED, SHUFFLE, BOMB_TICK, LEVEL_COMPLETE, GAME_OVER
}

enum class GemSfxCue { NONE, INVALID, WHOOSH, THUNK, SUCCESS }
enum class GemHapticCue { NONE, LIGHT_TICK, FAILURE, STRONG, NORMAL, ESCALATING, CELEBRATION }
enum class BurstKind { NONE, CLEAR, SPECIAL_CREATE, SPECIAL_FIRE, CHAIN, LEVEL_COMPLETE }

/** [firstIndex] until [endIndex] are the events this step covers (a settle covers a run of Fall/Spawn/Escaped). */
data class GemStep(
    val kind: StepKind,
    val firstIndex: Int,
    val endIndex: Int,
    val durationMs: Long,
    val sfx: GemSfxCue = GemSfxCue.NONE,
    val haptic: GemHapticCue = GemHapticCue.NONE,
    val burst: BurstKind = BurstKind.NONE,
    /** Chain length for CHAIN steps (the multiplier glow level), else 0. */
    val chain: Int = 0
)

/** How motion level changes the timeline. */
data class MotionSpec(val durationScale: Float, val bursts: Boolean) {
    companion object {
        /** Reduced Motion (accessibility) wins over everything: snap, no bursts. */
        fun of(motion: MotionLevel, reducedMotion: Boolean): MotionSpec = when {
            reducedMotion -> MotionSpec(0f, false)
            motion == MotionLevel.CALM -> MotionSpec(0.6f, true)   // short, small bursts (budget scaled elsewhere)
            else -> MotionSpec(1f, true)
        }
    }
}

object GemTimeline {
    // Base durations in ms; the same numbers the 2D baseline used.
    const val SWAP = 150L; const val ROTATE = 200L; const val MATCH = 130L; const val SPECIAL_CREATED = 160L
    const val SPECIAL_FIRE = 170L; const val COMBO = 240L; const val CLEAR = 170L; const val SETTLE = 280L
    const val PIECE_MOVED = 200L; const val SHUFFLE = 320L

    fun build(events: List<GemEvent>, spec: MotionSpec): List<GemStep> {
        fun d(ms: Long): Long = (ms * spec.durationScale).toLong()
        fun b(k: BurstKind): BurstKind = if (spec.bursts) k else BurstKind.NONE
        val out = ArrayList<GemStep>()
        var i = 0
        while (i < events.size) {
            when (val e = events[i]) {
                is GemEvent.Deal -> out += GemStep(StepKind.DEAL, i, i + 1, 0)
                is GemEvent.Swap -> out += GemStep(StepKind.SWAP, i, i + 1, d(SWAP), haptic = GemHapticCue.LIGHT_TICK)
                is GemEvent.SwapBack -> out += GemStep(StepKind.SWAP_BACK, i, i + 1, d(SWAP), sfx = GemSfxCue.INVALID, haptic = GemHapticCue.FAILURE)
                is GemEvent.Rotate -> out += GemStep(StepKind.ROTATE, i, i + 1, d(ROTATE), haptic = GemHapticCue.LIGHT_TICK)
                is GemEvent.RotateBack -> out += GemStep(StepKind.ROTATE_BACK, i, i + 1, 0, sfx = GemSfxCue.INVALID, haptic = GemHapticCue.FAILURE)
                is GemEvent.Match -> out += GemStep(StepKind.MATCH, i, i + 1, d(MATCH))
                is GemEvent.SpecialCreated -> out += GemStep(StepKind.SPECIAL_CREATED, i, i + 1, d(SPECIAL_CREATED), burst = b(BurstKind.SPECIAL_CREATE))
                is GemEvent.SpecialActivated -> out += GemStep(StepKind.SPECIAL_FIRE, i, i + 1, d(SPECIAL_FIRE), GemSfxCue.WHOOSH, GemHapticCue.STRONG, b(BurstKind.SPECIAL_FIRE))
                is GemEvent.ComboActivated -> out += GemStep(StepKind.COMBO, i, i + 1, d(COMBO), GemSfxCue.WHOOSH, GemHapticCue.STRONG, b(BurstKind.SPECIAL_FIRE))
                is GemEvent.ObstacleDamaged -> out += GemStep(StepKind.OBSTACLE, i, i + 1, 0)
                is GemEvent.Clear -> out += GemStep(StepKind.CLEAR, i, i + 1, d(CLEAR), GemSfxCue.THUNK, GemHapticCue.NORMAL, b(BurstKind.CLEAR))
                is GemEvent.ChainStep -> out += GemStep(
                    StepKind.CHAIN, i, i + 1, 0,
                    haptic = if (e.n >= 3) GemHapticCue.ESCALATING else GemHapticCue.NONE,
                    burst = if (e.n >= 3) b(BurstKind.CHAIN) else BurstKind.NONE,
                    chain = e.n
                )
                is GemEvent.ScoreDelta -> out += GemStep(StepKind.SCORE, i, i + 1, 0)
                is GemEvent.Fall, is GemEvent.Spawn, is GemEvent.Escaped -> {
                    var j = i
                    while (j < events.size && (events[j] is GemEvent.Fall || events[j] is GemEvent.Spawn || events[j] is GemEvent.Escaped)) j++
                    out += GemStep(StepKind.SETTLE, i, j, d(SETTLE))
                    i = j
                    continue
                }
                is GemEvent.PieceMoved -> out += GemStep(StepKind.PIECE_MOVED, i, i + 1, d(PIECE_MOVED))
                is GemEvent.NoMovesShuffle -> out += GemStep(StepKind.SHUFFLE, i, i + 1, d(SHUFFLE))
                is GemEvent.BombTick -> out += GemStep(StepKind.BOMB_TICK, i, i + 1, 0)
                is GemEvent.LevelComplete -> out += GemStep(StepKind.LEVEL_COMPLETE, i, i + 1, 0, GemSfxCue.SUCCESS, GemHapticCue.CELEBRATION, b(BurstKind.LEVEL_COMPLETE))
                is GemEvent.GameOver -> out += GemStep(StepKind.GAME_OVER, i, i + 1, 0, haptic = GemHapticCue.LIGHT_TICK)
                else -> Unit
            }
            i++
        }
        return out
    }

    fun totalMs(steps: List<GemStep>): Long = steps.sumOf { it.durationMs }
}
