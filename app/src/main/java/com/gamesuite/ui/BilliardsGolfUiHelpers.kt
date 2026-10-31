package com.gamesuite.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.sqrt

/*
 * Small helpers shared by the Billiards and Mini Golf screens: a frame-clock replay loop and colour shading.
 * Everything else is drawn by the screens themselves, in original vector art.
 */

/**
 * Steps through [count] recorded frames at [fps] real-time frames per second, calling [onFrame] with the
 * frame index to show, until the last frame has been shown. [speed] 1f is real time.
 */
internal suspend fun bgPlayFrames(count: Int, fps: Float, speed: Float = 1f, onFrame: (Int) -> Unit) {
    if (count <= 0) return
    val start = withFrameNanos { it }
    var idx = 0
    onFrame(0)
    while (idx < count - 1) {
        val now = withFrameNanos { it }
        idx = (((now - start) / 1_000_000_000.0) * fps * speed).toInt().coerceIn(0, count - 1)
        onFrame(idx)
    }
}

internal fun bgShade(c: Color, f: Float): Color = if (f >= 0f) lerp(c, Color.White, f) else lerp(c, Color.Black, -f)

internal fun bgDist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** [other] formatted with the seat name, or the second-person [you] string when the seat is "You" ("You win!", not "You wins!"). */
@androidx.compose.runtime.Composable
internal fun sayYou(name: String, other: Int, you: Int, vararg args: Any): String =
    androidx.compose.ui.platform.LocalContext.current.sayFor(name, other, you, *args)
