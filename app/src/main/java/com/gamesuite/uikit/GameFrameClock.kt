package com.gamesuite.uikit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Turns raw frame timestamps into simulation steps. The first frame after a (re)start yields 0 so a
 * pause, a backgrounding or a slow first composition can never feed one huge dt into a physics
 * step, and every later dt is clamped to [maxDtSeconds].
 */
class FrameStepper(private val maxDtSeconds: Float = 0.1f) {
    private var last = -1L

    /** Seconds since the previous frame; 0 for the first frame after construction or [reset]. */
    fun step(frameNanos: Long): Float {
        val prev = last
        last = frameNanos
        if (prev < 0L) return 0f
        val dt = (frameNanos - prev) / 1_000_000_000f
        return dt.coerceIn(0f, maxDtSeconds)
    }

    fun reset() { last = -1L }
}

/** Where frames come from. The real one is Compose's choreographer; tests feed a fixed list. */
fun interface FrameSource {
    /** The next frame's time in nanoseconds, or null when the source is exhausted. */
    suspend fun nextFrame(): Long?
}

/** Compose's own frame clock as a [FrameSource]. */
object ComposeFrameSource : FrameSource {
    override suspend fun nextFrame(): Long? = withFrameNanos { it }
}

/**
 * The loop body shared by every adopter and testable without Compose: for each frame, while
 * [running] stays true, call [onFrame] with the clamped dt.
 */
suspend fun runFrameLoop(
    source: FrameSource,
    stepper: FrameStepper,
    running: () -> Boolean = { true },
    onFrame: (dtSeconds: Float, frameNanos: Long) -> Unit
) {
    stepper.reset()
    while (running()) {
        val nanos = source.nextFrame() ?: return
        if (!running()) return
        onFrame(stepper.step(nanos), nanos)
    }
}

/**
 * The shared game frame clock, callback form. [onFrame] runs once per display frame with the
 * clamped dt in seconds, only while [running] is true and the host lifecycle is at least STARTED
 * (so it stops on ON_STOP and restarts, with a fresh dt baseline, on return). It never changes any
 * Compose state itself, so a game that mutates its own engine state in [onFrame] and reads that
 * state only inside draw / graphicsLayer lambdas recomposes nothing per frame.
 */
@Composable
fun rememberGameFrameClock(
    running: Boolean,
    maxDtSeconds: Float = 0.1f,
    onFrame: (dtSeconds: Float) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame = rememberUpdatedState(onFrame)
    LaunchedEffect(lifecycleOwner, running, maxDtSeconds) {
        if (!running) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runFrameLoop(ComposeFrameSource, FrameStepper(maxDtSeconds)) { dt, _ -> currentOnFrame.value(dt) }
        }
    }
}

/**
 * State form: a frame counter that advances once per display frame while [running]. Read it ONLY
 * inside a draw or graphicsLayer lambda (`Canvas { frame.value; ... }`); reading it in the body
 * would recompose the whole screen every frame, which is exactly what this clock exists to avoid.
 */
@Composable
fun rememberGameFrameTick(running: Boolean): State<Long> {
    val tick: MutableState<Long> = remember { mutableStateOf(0L) }
    rememberGameFrameClock(running) { tick.value = tick.value + 1 }
    return tick
}
