package com.gamesuite.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.gamesuite.games.partypack.ReactionWhackLayout
import com.gamesuite.games.partypack.ReactionWhackPointerMap
import com.gamesuite.games.partypack.ReactionWhackZone
import com.gamesuite.games.partypack.ReactionWhackZones
import kotlin.math.roundToInt

/*
 * Shared plumbing of the two same-screen party-pack games: a stage that tracks every finger separately and hands
 * each touch to the zone it landed in, and a box that draws a zone's content turned to face its player.
 */

/** Seat colours (Okabe-Ito, colour-blind safe) and the glyph set into each seat's badge: circle, square, triangle, diamond. */
internal val RwSeatColors = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFE8D22A), Color(0xFF009E73))

/** Text colour that reads on seat colour [seat]. */
internal fun rwOnSeat(seat: Int): Color = if (seat == 2) Color(0xFF1A1A1A) else Color.White

/**
 * The invisible surface the zones sit on. Every finger is bound (by [ReactionWhackPointerMap]) to the zone it went
 * down in; a touch-down is reported once, to that zone only, as [onPress] (seat, x and y in that zone's own
 * upright content frame in pixels, how many milliseconds ago the finger touched). Fingers that lift or are
 * cancelled are forgotten one by one, so no finger is ever lost because another lifted.
 */
@Composable
internal fun RwTouchSurface(
    layout: ReactionWhackLayout,
    onPress: (seat: Int, contentX: Float, contentY: Float, ageMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val press by rememberUpdatedState(onPress)
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(layout) {
                val map = ReactionWhackPointerMap { x, y -> ReactionWhackZones.zoneAt(layout.zones, x, y) }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val nowUptime = SystemClock.uptimeMillis()
                        // A finger the system no longer reports (a cancelled gesture) must not stay bound forever.
                        map.reconcile(event.changes.map { it.id.value })
                        for (c in event.changes) {
                            val id = c.id.value
                            if (c.changedToDownIgnoreConsumed()) {
                                val seat = map.down(id, c.position.x, c.position.y)
                                val zone = layout.zones.firstOrNull { it.seat == seat }
                                if (zone != null) {
                                    val (cx, cy) = ReactionWhackZones.toContent(zone, c.position.x, c.position.y)
                                    press(seat, cx, cy, (nowUptime - c.uptimeMillis).coerceAtLeast(0L))
                                }
                            } else if (c.changedToUpIgnoreConsumed()) {
                                map.up(id)
                            }
                        }
                    }
                }
            },
        content = content
    )
}

/** A zone's content, laid out in its own upright frame and turned clockwise by the zone's rotation to face its player. */
@Composable
internal fun RwZoneBox(zone: ReactionWhackZone, content: @Composable BoxScope.() -> Unit) {
    val d = LocalDensity.current
    val w = with(d) { zone.width.toDp() }
    val h = with(d) { zone.height.toDp() }
    val cw = with(d) { zone.contentWidth.toDp() }
    val ch = with(d) { zone.contentHeight.toDp() }
    Box(
        Modifier
            .offset { IntOffset(zone.left.roundToInt(), zone.top.roundToInt()) }
            .size(w, h)
            .clipToBounds()
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(cw, ch)
                .graphicsLayer { rotationZ = zone.rotation.toFloat() },
            content = content
        )
    }
}
