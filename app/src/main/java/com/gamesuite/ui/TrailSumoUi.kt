package com.gamesuite.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.games.partypack.TsPointerRouter
import com.gamesuite.games.partypack.TsRect
import com.gamesuite.games.partypack.TsZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Shared drawing and input plumbing of the same-screen party games Light-Trail Duel and Sumo Push: seat colours
 * and shapes, the multi-touch pointer loop, rotated per-player zones and the pads inside them. Everything is
 * original vector art. A seat is ALWAYS colour plus a shape (circle, square, triangle, diamond) plus a pattern
 * (solid, dashed, dotted, hollow) plus its name, so no one has to tell hues apart.
 */

/** Okabe-Ito based seat colours that stay apart for the common kinds of colour blindness (sky, yellow, vermillion, pink). */
internal val TsSeatColors = listOf(Color(0xFF56B4E9), Color(0xFFF0E442), Color(0xFFFF7A33), Color(0xFFCC79A7))

internal fun tsOnColor(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF101014) else Color.White

@StringRes
internal fun tsStyleRes(seat: Int): Int = when (seat % 4) {
    0 -> R.string.tsp_style_solid
    1 -> R.string.tsp_style_dashed
    2 -> R.string.tsp_style_dotted
    else -> R.string.tsp_style_hollow
}

/** A frame clock that only runs while [active] (a screen with nothing animating stops asking for frames). */
@Composable
internal fun rememberTsFrameMs(active: Boolean): State<Long> {
    val t = remember { mutableStateOf(0L) }
    LaunchedEffect(active) {
        if (active) while (true) withFrameMillis { t.value = it }
    }
    return t
}

/**
 * The pointer loop: every finger that goes down is handed to the [router], which binds it to exactly one control
 * for its whole life. Positions are shifted by ([originX], [originY]) into the router's screen space. When the
 * loop leaves composition (or the screen pauses) every held control is released.
 */
internal fun Modifier.tsPointers(router: TsPointerRouter, originX: Float, originY: Float): Modifier =
    pointerInput(router, originX, originY) {
        awaitPointerEventScope {
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    for (c in event.changes) {
                        val id = c.id.value
                        if (c.pressed) {
                            if (!c.previousPressed) router.down(id, c.position.x + originX, c.position.y + originY)
                            else router.move(id, c.position.x + originX, c.position.y + originY)
                        } else if (c.previousPressed) {
                            router.up(id)
                        }
                    }
                }
            } finally {
                router.cancelAll()
            }
        }
    }

/** Places a zone's content in its own local frame, rotated onto the screen so it faces its player. */
@Composable
internal fun TsZoneBox(zone: TsZone, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val d = LocalDensity.current
    val w = with(d) { zone.localWidth.toDp() }
    val h = with(d) { zone.localHeight.toDp() }
    val ox = (zone.screen.centerX - zone.localWidth / 2f).roundToInt()
    val oy = (zone.screen.centerY - zone.localHeight / 2f).roundToInt()
    Box(
        modifier
            .offset { IntOffset(ox, oy) }
            .requiredSize(w, h)
            .graphicsLayer { rotationZ = zone.side.degrees },
        content = content
    )
}

/** A rectangle of the local frame (pixels) as a placed, sized Box. */
@Composable
internal fun TsLocalBox(rect: TsRect, modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.Center, content: @Composable BoxScope.() -> Unit) {
    val d = LocalDensity.current
    // Place and size FIRST, then apply the caller's modifier (clip, background, border, semantics): applied the other
    // way round the background is drawn at the zone's origin and only the content moves, so two pads piled up.
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .requiredSize(with(d) { rect.width.toDp() }, with(d) { rect.height.toDp() })
            .then(modifier),
        contentAlignment = contentAlignment,
        content = content
    )
}

/** The tinted strip behind a zone's controls: seat colour border so the owner is obvious at a glance. */
@Composable
internal fun TsZoneBackdrop(seatColor: Color, dim: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(seatColor.copy(alpha = if (dim) 0.07f else 0.16f))
            .border(2.dp, seatColor.copy(alpha = if (dim) 0.35f else 0.85f), RoundedCornerShape(18.dp))
    )
}

/** A thick arrow pointing along (dx, dy) (any length): stem plus head. */
internal fun DrawScope.tsArrow(dx: Float, dy: Float, center: Offset, length: Float, color: Color) {
    val l = sqrt(dx * dx + dy * dy)
    if (l < 1e-5f) return
    val ux = dx / l; val uy = dy / l
    val tail = Offset(center.x - ux * length * 0.5f, center.y - uy * length * 0.5f)
    val tip = Offset(center.x + ux * length * 0.5f, center.y + uy * length * 0.5f)
    val stem = Offset(tip.x - ux * length * 0.38f, tip.y - uy * length * 0.38f)
    drawLine(color, tail, stem, strokeWidth = length * 0.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val px = -uy; val py = ux
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(stem.x + px * length * 0.3f, stem.y + py * length * 0.3f)
        lineTo(stem.x - px * length * 0.3f, stem.y - py * length * 0.3f)
        close()
    }
    drawPath(head, color)
}

/** One pad: a rounded, seat-coloured button in the local frame. Sighted touch goes through the pointer router; TalkBack uses [onActivate]. */
@Composable
internal fun TsPad(
    rect: TsRect, seatColor: Color, pressed: Boolean, label: String?, desc: String, onActivate: () -> Unit,
    drawIcon: DrawScope.(Offset, Float, Color) -> Unit
) {
    val on = tsOnColor(seatColor)
    TsLocalBox(
        rect,
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(seatColor.copy(alpha = if (pressed) 1f else 0.72f))
            .border(if (pressed) 4.dp else 2.dp, on.copy(alpha = if (pressed) 0.95f else 0.55f), RoundedCornerShape(20.dp))
            .semantics {
                role = Role.Button
                contentDescription = desc
                onClick(label = desc) { onActivate(); true }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(34.dp)) { drawIcon(Offset(size.width / 2, size.height / 2), size.minDimension * 0.9f, on) }
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = on, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

/** The seat's identity in the header: shape, name, round-win pips, in/out. */
@Composable
internal fun TsSeatChip(
    seat: Int, name: String, bot: Boolean, wins: Int, winsNeeded: Int, alive: Boolean, textColor: Color, cardColor: Color, modifier: Modifier = Modifier
) {
    val color = TsSeatColors[seat % 4]
    val style = stringResource(tsStyleRes(seat))
    val state = stringResource(if (alive) R.string.tsp_seat_in else R.string.tsp_seat_out)
    val desc = stringResource(R.string.tsp_chip_desc, name, style, wins, winsNeeded, state) + if (bot) " (" + stringResource(R.string.ccl_cpu_tag) + ")" else ""
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cardColor)
            .border(2.dp, color.copy(alpha = if (alive) 1f else 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(min = 32.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(color.copy(alpha = if (alive) 1f else 0.4f), size.minDimension * 0.5f, c)
            drawSeatGlyph(seat, c, size.minDimension * 0.28f)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            name, color = textColor.copy(alpha = if (alive) 1f else 0.55f), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        for (i in 0 until winsNeeded) {
            Box(
                Modifier.padding(horizontal = 1.5.dp).size(10.dp).clip(CircleShape)
                    .background(if (i < wins) color else Color.Transparent)
                    .border(1.5.dp, color, CircleShape)
            )
        }
        if (!alive) {
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tsp_seat_out), color = textColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** The seat chips in rows of [perRow] (two on a narrow screen with three or four players) so every name stays readable. */
@Composable
internal fun TsChipGrid(count: Int, perRow: Int, chip: @Composable (Int, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var start = 0
        while (start < count) {
            val end = minOf(start + perRow, count)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in start until end) chip(i, Modifier.weight(1f))
                repeat(perRow - (end - start)) { Spacer(Modifier.weight(1f)) }
            }
            start = end
        }
    }
}

/** A computer seat's slim strip: shape, name and level, upright for the player on that side. */
@Composable
internal fun TsCpuZone(zone: TsZone, name: String, levelWord: String, textColor: Color) {
    val color = TsSeatColors[zone.seat % 4]
    TsZoneBox(zone) {
        TsZoneBackdrop(color, dim = true)
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(22.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(color, size.minDimension * 0.5f, c)
                drawSeatGlyph(zone.seat, c, size.minDimension * 0.28f)
            }
            Spacer(Modifier.width(8.dp))
            Text("$name · $levelWord", color = textColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A dim veil with one centred message and a button: the pause screen. */
@Composable
internal fun TsPauseOverlay(textColor: Color, card: Color, onResume: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(card).padding(24.dp).widthIn(max = 360.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.tsp_paused_title), color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.tsp_paused_body), color = textColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(16.dp))
            Button(onClick = onResume, modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth()) { Text(stringResource(R.string.tsp_resume)) }
        }
    }
}

/** Dashed circle outline (used for the Gap pickup and the Gap-active ring). */
internal fun DrawScope.tsDashedCircle(center: Offset, radius: Float, color: Color, stroke: Float, dashes: Int = 10) {
    val sweep = 360f / dashes
    for (i in 0 until dashes) {
        drawArc(
            color, startAngle = i * sweep, sweepAngle = sweep * 0.55f, useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

internal fun DrawScope.tsStar(center: Offset, outer: Float, inner: Float, color: Color) {
    val p = Path()
    for (i in 0 until 8) {
        val a = -Math.PI / 2 + i * Math.PI / 4
        val r = if (i % 2 == 0) outer else inner
        val x = center.x + (cos(a) * r).toFloat(); val y = center.y + (sin(a) * r).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}
