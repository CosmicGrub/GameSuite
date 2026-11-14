package com.gamesuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.partypack.PpEdge

/*
 * Shared UI pieces of the same-screen party pack (Paddle Duel, Rock-Paper-Showdown).
 * Everything here is original vector drawing and plain layout; no assets.
 */

/** The four seat colours (Okabe-Ito, colour-blind safe). A seat is ALSO told apart by a shape and its name, never by colour alone. */
internal val PpSeatColors: List<Color> = ChineseSeatColors.take(4)

/** Which glyph shape (see drawSeatGlyph) marks seat [i]: circle, square, triangle, diamond. */
internal fun ppSeatShape(i: Int): Int = i % 4

/**
 * Lays [this] out as if the window were turned [quarterTurns] clockwise quarter turns: it measures its
 * child with the width and height limits swapped (for 1 and 3), reports the swapped size, and draws the child
 * rotated about the middle. Touches are mapped through the same rotation, so buttons inside still work.
 */
internal fun Modifier.ppRotateLayout(quarterTurns: Int): Modifier {
    val q = ((quarterTurns % 4) + 4) % 4
    if (q == 0) return this
    return this.layout { measurable, c ->
        val swap = q % 2 == 1
        val inner = if (swap) Constraints(minWidth = c.minHeight, maxWidth = c.maxHeight, minHeight = c.minWidth, maxHeight = c.maxWidth) else c
        val p = measurable.measure(inner)
        val w = if (swap) p.height else p.width
        val h = if (swap) p.width else p.height
        layout(w, h) {
            p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = q * 90f }
        }
    }
}

/**
 * A button that reports being HELD (down to up) for steering, and acts as an ordinary click for TalkBack and
 * switch access ([onActivate]). 48 dp or more.
 */
@Composable
internal fun PpHoldButton(
    label: String,
    onHold: (Boolean) -> Unit,
    onActivate: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    /** True for a plain tap button (the one switch): a finger that lifts inside also calls [onActivate]. */
    activateOnTap: Boolean = false,
    glyph: @Composable () -> Unit
) {
    val hold by rememberUpdatedState(onHold)
    val activate by rememberUpdatedState(onActivate)
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(background.copy(alpha = if (down) 1f else 0.82f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true
                    hold(true)
                    try {
                        val up = waitForUpOrCancellation()
                        if (up != null && activateOnTap) activate()
                    } finally {
                        down = false
                        hold(false)
                    }
                }
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { onActivate(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        glyph()
    }
}

/** A filled triangle pointing along [dx], [dy] (unit screen vector) inside a square of [side] px. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPpArrow(dx: Int, dy: Int, color: Color, side: Float = size.minDimension) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = side * 0.26f
    val fx = dx.toFloat(); val fy = dy.toFloat()
    val px = -fy; val py = fx
    val path = Path().apply {
        moveTo(c.x + fx * r, c.y + fy * r)
        lineTo(c.x - fx * r * 0.8f + px * r, c.y - fy * r * 0.8f + py * r)
        lineTo(c.x - fx * r * 0.8f - px * r, c.y - fy * r * 0.8f - py * r)
        close()
    }
    drawPath(path, color)
}

/** Rotates a screen vector (dx, dy), y down, by [quarterTurns] clockwise quarter turns. */
internal fun ppRotateVec(dx: Int, dy: Int, quarterTurns: Int): Pair<Int, Int> {
    var x = dx; var y = dy
    repeat(((quarterTurns % 4) + 4) % 4) { val nx = -y; val ny = x; x = nx; y = ny }
    return x to y
}

internal fun PpEdge.alignment(): Alignment = when (this) {
    PpEdge.BOTTOM -> Alignment.BottomCenter
    PpEdge.TOP -> Alignment.TopCenter
    PpEdge.LEFT -> Alignment.CenterStart
    PpEdge.RIGHT -> Alignment.CenterEnd
}
