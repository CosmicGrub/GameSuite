package com.gamesuite.uikit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The pure state machine behind "an overlay is open, so the game must be paused".
 *
 * Several overlays can be open at once (menu dropdown, then help sheet; a coach card over a leave
 * dialog), each identified by a string key. The game is paused when the FIRST overlay opens and
 * resumed when the LAST one closes. Two guarantees:
 *
 *  - **Idempotent per key**: opening the same key twice, or closing one that is not open, does
 *    nothing, so recomposition and double-taps cannot unbalance the count.
 *  - **No double pause**: if [alreadyPaused] reports the game was paused before the first overlay
 *    opened (its own Pause button, a backgrounded app), the controller neither pauses nor later
 *    resumes it; the player stays in control of that pause.
 */
class OverlayPauseController(
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val alreadyPaused: () -> Boolean = { false }
) {
    private val open = LinkedHashSet<String>()
    private var weHoldPause = false

    val openCount: Int get() = open.size
    val isAnyOpen: Boolean get() = open.isNotEmpty()
    /** True while this controller is the one holding the game paused. */
    val holdsPause: Boolean get() = weHoldPause

    fun setOpen(key: String, isOpen: Boolean) {
        if (isOpen) {
            if (!open.add(key)) return
            if (open.size == 1 && !alreadyPaused()) {
                weHoldPause = true
                pause()
            }
        } else {
            if (!open.remove(key)) return
            if (open.isEmpty() && weHoldPause) {
                weHoldPause = false
                resume()
            }
        }
    }

    /**
     * Forget every open overlay WITHOUT resuming: the match is being torn down (leave confirmed),
     * so waking its timers again would be wrong.
     */
    fun abandon() {
        open.clear()
        weHoldPause = false
    }
}

/**
 * What the shell offers the chrome for the active game: pause/resume its module and say whether it
 * is already paused. MainActivity provides one backed by the session manager; a screen previewed on
 * its own gets null and the chrome pauses nothing.
 */
class OverlayPauseHost(
    val pause: () -> Unit,
    val resume: () -> Unit,
    val isPaused: () -> Boolean = { false }
)

val LocalOverlayPauseHost = staticCompositionLocalOf<OverlayPauseHost?> { null }

/**
 * A screen's own pause for state the module cannot see (a local `paused` flag, a frame clock). The
 * chrome calls it in addition to the shell host.
 */
class OverlayPauseHook(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val isPaused: () -> Boolean = { false }
)

/**
 * Provided by [GameChrome] to its content. A screen with its own dialog reports it with
 * [OverlayPauseEffect] so that dialog pauses the game exactly like the chrome's overlays do.
 */
val LocalOverlayPause = compositionLocalOf<OverlayPauseController?> { null }

/** While [open] is true (and this composable is in the composition) the game is paused. */
@Composable
fun OverlayPauseEffect(key: String, open: Boolean) {
    val controller = LocalOverlayPause.current
    DisposableEffect(controller, key, open) {
        if (open) controller?.setOpen(key, true)
        onDispose { if (open) controller?.setOpen(key, false) }
    }
}

@Composable
internal fun rememberOverlayPauseController(hook: OverlayPauseHook?): OverlayPauseController {
    val host = LocalOverlayPauseHost.current
    return remember(host, hook) {
        OverlayPauseController(
            pause = { host?.pause?.invoke(); hook?.onPause?.invoke() },
            resume = { hook?.onResume?.invoke(); host?.resume?.invoke() },
            alreadyPaused = { (host?.isPaused?.invoke() == true) || (hook?.isPaused?.invoke() == true) }
        )
    }
}
