package com.gamesuite.ui.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A reusable "camera"/screen shake for reacting to a real game event — a capture, a goal, a
 * big hit — generalizing the FIVE independent hand-rolled shake implementations already
 * shipped across this app (AirHockeyScreen's goal-celebration shake, CheckersScreen's capture
 * hit-stop shake, MancalaScreen's `runCameraShake`, UnoScreen's `jitterShake`, SolitaireScreen's
 * finale `SHAKE_STEPS`) into one shared primitive — the same reasoning [PremiumShaders] already
 * established for the specular-sweep shader, so a sixth game never needs to hand-roll a sixth
 * copy.
 *
 * On inspection, those five already converged on exactly two shapes:
 *
 * - **Continuous decay**: a single decaying value drives a multi-axis sine/cosine jitter
 *   formula at the read site — CheckersScreen's own `cameraShake` (read as
 *   `shake * sin(shake * 47f)`). [CameraShake]/[cameraShakeOffsetFor] generalize this shape
 *   directly; it needs no separate frame loop, it's purely a function of the [Animatable]'s
 *   own decay curve. (AirHockeyScreen's own goal-shake uses a similar dual-axis sine/cosine
 *   wobble but drives it from real elapsed wall-clock time in a manual loop rather than from
 *   the decaying value itself — CheckersScreen's value-driven formula is the simpler of the
 *   two for an identical visual result, so it's the one generalized here.)
 * - **Scripted impulse list**: a fixed, hand-tuned sequence of discrete offsets played back
 *   step by step — Mancala's `runCameraShake`, UNO's `jitterShake`, and Solitaire's
 *   `SHAKE_STEPS` are, on inspection, the IDENTICAL pattern (snap to zero, then animate through
 *   a fixed list of values) with only the magnitude/step-duration/pattern constants differing.
 *   [Animatable.shakeSteps] generalizes this shape as an extension on the exact [Animatable]
 *   type every one of those three already used.
 *
 * Apply either via [Modifier.cameraShake] on whatever container the whole board/table should
 * visually jolt — a pure compositing translation via `graphicsLayer`, the same
 * non-interference guarantee [specularSweep] already gives: never touches layout or draw-scope
 * math, only the final composited position.
 */
class CameraShake {
    private val progress = Animatable(0f)

    /** The raw decaying value: 1f at the instant [trigger] is called, decaying linearly to 0f
     *  over that call's own `durationMs`. Read this directly if a call site wants its own
     *  jitter formula rather than [offset]'s default one. */
    val value: Float get() = progress.value

    /** Starts (or restarts) a decaying shake. Calling this again before a prior shake has
     *  finished simply retargets the same [Animatable] — its own built-in interruption
     *  semantics mean the shake smoothly redirects from wherever it currently is, rather than
     *  stacking or glitching. [easing] defaults to [LinearEasing] (a genuinely linear decay,
     *  the shape AirHockeyScreen's own pre-migration goal-shake used); pass
     *  `androidx.compose.animation.core.FastOutSlowInEasing` for a decay that starts fast and
     *  eases out toward rest instead — `tween()`'s own default, and the shape
     *  CheckersScreen's pre-migration capture shake used (via a plain `tween(durationMs)` with
     *  no explicit easing). Different sites legitimately want different decay feels; this
     *  isn't one "correct" curve. */
    suspend fun trigger(durationMs: Int = 300, easing: Easing = LinearEasing) {
        progress.snapTo(1f)
        progress.animateTo(0f, animationSpec = tween(durationMs, easing = easing))
    }

    /** The default dual-axis jitter offset for the current [value] — see [cameraShakeOffsetFor]. */
    fun offset(magnitudePx: Float, frequencyX: Float = 47f, frequencyY: Float = 39f): Offset =
        cameraShakeOffsetFor(value, magnitudePx, frequencyX, frequencyY)
}

/**
 * The pure math behind [CameraShake.offset] — factored out as a standalone function so it's
 * directly unit-testable without Compose's animation clock (this project has no Robolectric/
 * Compose-test-rule setup, same reasoning every other pure-logic extraction in this codebase
 * follows). [shake] is the raw decaying value (1f = peak, 0f = at rest, same range as
 * [CameraShake.value]); two independent frequencies keep the two axes from ever moving in
 * lockstep, which would read as a 1D wobble rather than a real shake — the exact formula
 * CheckersScreen's own capture hit-stop shake already shipped, generalized here rather than
 * copied a sixth time.
 */
fun cameraShakeOffsetFor(shake: Float, magnitudePx: Float, frequencyX: Float = 47f, frequencyY: Float = 39f): Offset =
    Offset(magnitudePx * shake * sin(shake * frequencyX), magnitudePx * shake * cos(shake * frequencyY))

@Composable
fun rememberCameraShake(): CameraShake = remember { CameraShake() }

/** Applies [shake]'s current [CameraShake.offset] as a pure compositing translation on this
 *  modifier's layer — never touches layout or draw-scope math, only the final composited
 *  position, the same non-interference guarantee [specularSweep] already gives. */
fun Modifier.cameraShake(shake: CameraShake, magnitudePx: Float, frequencyX: Float = 47f, frequencyY: Float = 39f): Modifier =
    this.graphicsLayer {
        val o = shake.offset(magnitudePx, frequencyX, frequencyY)
        translationX = o.x
        translationY = o.y
    }

/** A default step pattern (fractions of a peak magnitude, each held briefly before the next)
 *  for [Animatable.shakeSteps] — UNO's own `jitterShake` shape: a fast jolt that overshoots
 *  once in the opposite direction, at a shrinking magnitude, before settling at zero. Verified
 *  ([CameraShakeTest]) to actually decay: the pattern's own magnitude never increases from one
 *  step to the next and always ends at exactly zero, so no caller-supplied `pattern` can
 *  accidentally leave a residual offset stuck in place. */
val DEFAULT_SHAKE_STEP_PATTERN = listOf(1f, -0.7f, 0.4f, -0.2f, 0f)

/**
 * Plays a fixed, discrete decaying sequence on this [Animatable] — generalizing
 * Mancala/UNO/Solitaire's own `runCameraShake`/`jitterShake`, which on inspection turned out to
 * be the identical pattern with only the magnitude/step-duration/pattern constants differing.
 * [pattern] is a sequence of FRACTIONS of [magnitudePx] (default [DEFAULT_SHAKE_STEP_PATTERN]);
 * pass `magnitudePx = 1f` with an absolute-value [pattern] to reproduce a hand-authored
 * sequence expressed directly in pixels, like Solitaire's own `SHAKE_STEPS`
 * (`listOf(16f, -12f, 8f, -5f, 2f, 0f)`).
 */
suspend fun Animatable<Float, AnimationVector1D>.shakeSteps(
    magnitudePx: Float,
    stepDurationMs: Int = 35,
    pattern: List<Float> = DEFAULT_SHAKE_STEP_PATTERN
) {
    snapTo(0f)
    for (fraction in pattern) animateTo(magnitudePx * fraction, animationSpec = tween(stepDurationMs))
}
