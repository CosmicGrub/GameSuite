package com.gamesuite.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamesuite.games.partypack.TapTugContact
import com.gamesuite.games.partypack.TapTugPointerTracker
import com.gamesuite.games.partypack.TapTugRect
import com.gamesuite.games.partypack.TapTugZone
import com.gamesuite.games.taprace.RaceLight
import kotlin.math.PI
import kotlin.math.sin

/*
 * Drawing and input shared by the same-screen party games Tap Race and Tug of War. Everything is original vector
 * art on a Canvas: glossy marbles for the pads (the same material as Chinese Checkers), a distinct seat colour,
 * glyph (circle, square, triangle, diamond) AND surface pattern for every player, so no one has to tell two hues
 * apart. Names are prefixed PartyTapTug / tapTug so sibling party-pack games can add their own helpers.
 */

/** Okabe-Ito seat colours: orange, blue, yellow, green. */
internal val TapTugSeatColors: List<Color> = ChineseSeatColors.take(4)

/** The seat glyph numbers of [drawSeatGlyph] used for the four seats: circle, square, triangle, diamond. */
internal fun tapTugGlyph(index: Int): Int = index % 4

/** A faint pattern per seat (stripes, dots, checks, waves) so zones differ by texture as well as by colour. */
internal fun DrawScope.drawTapTugPattern(index: Int, topLeft: Offset, size: Size, color: Color) {
    val step = (size.minDimension / 9f).coerceIn(14f, 34f)
    val stroke = (step * 0.16f).coerceAtLeast(1.5f)
    when (index % 4) {
        0 -> { // diagonal stripes
            var x = -size.height
            while (x < size.width) {
                drawLine(color, Offset(topLeft.x + x, topLeft.y + size.height), Offset(topLeft.x + x + size.height, topLeft.y), stroke)
                x += step
            }
        }
        1 -> { // dots
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(color, stroke * 1.1f, Offset(topLeft.x + x, topLeft.y + y)); x += step }
                y += step
            }
        }
        2 -> { // checks
            var row = 0
            var y = 0f
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else step
                while (x < size.width) {
                    drawRect(color, Offset(topLeft.x + x, topLeft.y + y), Size(minOf(step, size.width - x), minOf(step, size.height - y)))
                    x += step * 2
                }
                y += step; row++
            }
        }
        else -> { // waves
            var y = step / 2
            while (y < size.height) {
                val p = Path()
                var x = 0f
                p.moveTo(topLeft.x, topLeft.y + y)
                while (x < size.width) {
                    x += step / 2
                    p.lineTo(topLeft.x + x, topLeft.y + y + (if ((x / (step / 2)).toInt() % 2 == 0) -1 else 1) * step * 0.18f)
                }
                drawPath(p, color, style = Stroke(stroke))
                y += step
            }
        }
    }
}

/** A big glossy pad: a marble with the seat glyph, a rim ring, and a squash when pressed. */
internal fun DrawScope.drawTapTugPad(center: Offset, radius: Float, base: Color, index: Int, pressed: Boolean, enabled: Boolean, ring: Color) {
    val r = radius.coerceAtLeast(1f)
    drawCircle(ring.copy(alpha = if (enabled) 0.55f else 0.25f), r * 1.08f, center, style = Stroke(r * 0.07f))
    drawMarble(
        center, r, if (enabled) base else clShade(base, 0.35f), tapTugGlyph(index), glyph = 0.9f,
        lift = if (pressed) 0f else r * 0.07f, scale = if (pressed) 0.94f else 1f, alpha = if (enabled) 1f else 0.6f
    )
}

/** The traffic light of Mash and Brake: green is a triangle, amber a bar, red a square, so it reads without colour. */
internal fun DrawScope.drawTapTugLight(topLeft: Offset, size: Size, light: RaceLight, horizontal: Boolean) {
    val lamps = listOf(
        RaceLight.GREEN to Color(0xFF1FA85A),
        RaceLight.AMBER to Color(0xFFF2B100),
        RaceLight.RED to Color(0xFFD7263D)
    )
    drawRoundRect(Color(0xFF22252B), topLeft, size, CornerRadius(size.minDimension * 0.25f))
    val d = if (horizontal) minOf(size.height * 0.72f, size.width / 3.4f) else minOf(size.width * 0.72f, size.height / 3.4f)
    lamps.forEachIndexed { i, (kind, col) ->
        val c = if (horizontal) Offset(topLeft.x + size.width * (i + 0.5f) / 3f, topLeft.y + size.height / 2f)
        else Offset(topLeft.x + size.width / 2f, topLeft.y + size.height * (i + 0.5f) / 3f)
        val on = kind == light
        drawCircle(if (on) col else col.copy(alpha = 0.22f), d / 2f, c)
        if (on) {
            drawCircle(Color.White.copy(alpha = 0.35f), d * 0.12f, Offset(c.x - d * 0.16f, c.y - d * 0.18f))
            val g = d * 0.2f
            val white = Color.White
            when (kind) {
                RaceLight.GREEN -> {
                    val p = Path().apply { moveTo(c.x - g * 0.7f, c.y - g); lineTo(c.x + g, c.y); lineTo(c.x - g * 0.7f, c.y + g); close() }
                    drawPath(p, white)
                }
                RaceLight.AMBER -> {
                    drawRect(white, Offset(c.x - g * 0.25f, c.y - g), Size(g * 0.5f, g * 1.2f))
                    drawCircle(white, g * 0.28f, Offset(c.x, c.y + g * 0.95f))
                }
                else -> drawRect(white, Offset(c.x - g, c.y - g), Size(g * 2, g * 2))
            }
        }
    }
}

/** A checkered finish flag on a pole whose foot is at [base] and which is [height] tall. */
internal fun DrawScope.drawTapTugFlag(base: Offset, height: Float, dark: Boolean) {
    val pole = if (dark) Color(0xFFEDEDED) else Color(0xFF3A3A3A)
    drawLine(pole, base, Offset(base.x, base.y - height), height * 0.07f)
    val w = height * 0.62f
    val h = height * 0.46f
    val cells = 3
    for (i in 0 until cells) for (j in 0 until cells) {
        val col = if ((i + j) % 2 == 0) Color(0xFF111111) else Color.White
        drawRect(col, Offset(base.x + i * w / cells, base.y - height + j * h / cells), Size(w / cells, h / cells))
    }
    drawRect(Color(0xFF111111), Offset(base.x, base.y - height), Size(w, h), style = Stroke(1.5f))
}

/**
 * The touch size of every finger of the latest raw MotionEvent, looked up by POSITION. Compose hands the pointer
 * model its own ids (they count up with every new touch), which are NOT the Android pointer ids of the MotionEvent, so
 * an id-keyed lookup mixed fingers up (a stale palm size from a reused Android id could reject a later finger, and a
 * real palm got no size at all). A contact's position is the same in both views, so a down is matched to the
 * nearest recorded contact within [MATCH_PX]; with no match the size is unknown (0) and the contact is accepted.
 */
internal class ContactSizes {
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var sizes = FloatArray(0)

    fun record(m: MotionEvent) {
        try {
            val n = m.pointerCount
            set(FloatArray(n) { m.getX(it) }, FloatArray(n) { m.getY(it) }, FloatArray(n) { maxOf(m.getTouchMajor(it), m.getTouchMinor(it)) })
        } catch (_: Exception) {
        }
    }

    /** Replaces the recorded contacts (the pure part of [record], unit tested). */
    fun set(x: FloatArray, y: FloatArray, size: FloatArray) {
        xs = x; ys = y; sizes = size
    }

    /** The touch size of the contact nearest ([x], [y]), or 0 when none is within [MATCH_PX]. */
    fun sizeAt(x: Float, y: Float): Float {
        var best = -1
        var bestD = MATCH_PX * MATCH_PX
        for (i in xs.indices) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = i }
        }
        return if (best >= 0) sizes[best] else 0f
    }

    companion object {
        const val MATCH_PX = 8f
    }
}

/**
 * Feeds the raw per-pointer events of the whole board into [tracker] and reports each counted press and each release
 * as ([zone], pointer id). Every finger is tracked by its own id, so a lifting finger never disturbs another; when
 * every finger is gone, or the board leaves composition, everything still held is released.
 */
internal fun Modifier.tapTugPointers(
    tracker: TapTugPointerTracker,
    contacts: ContactSizes,
    onPress: (zone: Int, pointerId: Long) -> Unit,
    onRelease: (zone: Int, pointerId: Long) -> Unit
): Modifier = pointerInput(tracker) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) {
                    val id = change.id.value
                    when {
                        (change.pressed && !change.previousPressed) -> {
                            val d = tracker.down(id, change.position.x, change.position.y, contacts.sizeAt(change.position.x, change.position.y), change.uptimeMillis)
                            if (d.contact == TapTugContact.COUNTED) onPress(d.zone, id)
                            if (d.zone >= 0) change.consume()
                        }
                        (!change.pressed && change.previousPressed) -> tracker.up(id)?.let { onRelease(it.zone, it.pointerId) }
                        change.pressed -> tracker.move(id, change.position.x, change.position.y)?.let { onRelease(it.zone, it.pointerId) }
                        else -> tracker.up(id)?.let { onRelease(it.zone, it.pointerId) }
                    }
                }
                if (event.changes.none { it.pressed }) tracker.cancelAll().forEach { onRelease(it.zone, it.pointerId) }
            }
        }
    } finally {
        tracker.cancelAll().forEach { onRelease(it.zone, it.pointerId) }
    }
}

/** What TalkBack and a switch see for one zone: its description, and what a double-tap does (plus an optional second action). */
internal class TapTugZoneA11y(
    val description: String,
    val onActivate: () -> Unit,
    val extraLabel: String? = null,
    val onExtra: (() -> Unit)? = null
)

/**
 * The shared board: the zones, turned to face their players, the band between the rows, one pointer overlay for all
 * the fingers, and the accessibility actions of every zone (a double-tap = one tap; no gesture is needed).
 * [interactive] says which seats are people: a computer's zone ignores touches.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TapTugBoard(
    zones: List<TapTugZone>,
    band: TapTugZone,
    interactive: List<Boolean>,
    tracker: TapTugPointerTracker,
    a11y: (seat: Int) -> TapTugZoneA11y,
    onPress: (seat: Int, pointerId: Long) -> Unit,
    onRelease: (seat: Int, pointerId: Long) -> Unit,
    modifier: Modifier = Modifier,
    bandContent: @Composable BoxScope.() -> Unit,
    zoneContent: @Composable BoxScope.(seat: Int) -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val maxContact = remember(ctx) { TapTugPointerTracker.maxFingerContactPx(ctx.resources.displayMetrics.densityDpi.toFloat()) }
    BoxWithConstraints(modifier) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Rebuilt on every composition: cheap, and always matches what is drawn.
        tracker.maxContactPx = maxContact
        tracker.zones = List(zones.size) { i -> if (interactive.getOrElse(i) { false }) zones[i].toRect(wPx, hPx) else TapTugRect.NONE }
        val wDp = maxWidth
        val hDp = maxHeight
        val contacts = remember { ContactSizes() }
        Box(
            Modifier
                .fillMaxSize()
                // Touch sizes are only on the raw MotionEvent. The handler must ACCEPT the gesture (return true): a filter
                // that declines the first down is never shown the 2nd..4th finger, which would then go unmeasured.
                // The interop filter consumes the changes, so the pointer model below reads pressed/previousPressed itself
                // (changedToDown() would be false for a consumed change).
                .pointerInteropFilter { e -> contacts.record(e); true }
                .tapTugPointers(tracker, contacts, onPress = { z, pid -> onPress(z, pid) }, onRelease = { z, pid -> onRelease(z, pid) })
        ) {
            for (z in zones) {
                val a = a11y(z.seat)
                // A computer's zone ignores touches, so it must not be announced as a button that does nothing.
                val tappable = interactive.getOrElse(z.seat) { false }
                Box(
                    Modifier
                        .offset { IntOffset((z.left * wPx).toInt(), (z.top * hPx).toInt()) }
                        .width(wDp * z.width)
                        .height(hDp * z.height)
                        .graphicsLayer { rotationZ = if (z.rotated) 180f else 0f }
                        .semantics(mergeDescendants = true) {
                            contentDescription = a.description
                            if (tappable) {
                                role = Role.Button
                                onClick(label = a.description) { a.onActivate(); true }
                            }
                            val extra = a.extraLabel
                            val action = a.onExtra
                            if (extra != null && action != null) customActions = listOf(CustomAccessibilityAction(extra) { action(); true })
                        }
                ) { zoneContent(z.seat) }
            }
            Box(
                Modifier
                    .offset { IntOffset((band.left * wPx).toInt(), (band.top * hPx).toInt()) }
                    .width(wDp * band.width)
                    .height(hDp * band.height)
            ) { bandContent() }
        }
        // Above the touch layer, not inside it: the result dialog's buttons must receive their own taps.
        overlay()
    }
}

/** A gentle 0..1 pulse for idle motion; constant 0 when [reduced]. */
internal fun tapTugPulse(timeMs: Long, periodMs: Long, reduced: Boolean): Float =
    if (reduced) 0f else (0.5f + 0.5f * sin(2.0 * PI * (timeMs % periodMs) / periodMs).toFloat())

