package com.gamesuite.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.ClubProfileStore
import com.gamesuite.ui.club.CoachCard
import com.gamesuite.ui.club.CoachHelpButton
import com.gamesuite.ui.effects.ParticleBurst
import com.gamesuite.uikit.ChangeSetupButton
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Shared drawing and chrome for the Chinese Checkers and Ludo screens. Everything is original
 * vector art drawn on a Canvas: glossy glass marbles / enamel pawns with a soft drop shadow, a
 * specular highlight and a rim, so the pieces read as physical objects. Seat identity is ALWAYS
 * colour plus shape (an Okabe-Ito colour-blind-safe palette, and a different glyph set into each
 * piece), so nobody has to tell two hues apart.
 */

/** Okabe-Ito seat colours for Chinese Checkers (six seats). */
internal val ChineseSeatColors = listOf(
    Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFE8D22A),
    Color(0xFF009E73), Color(0xFFCC79A7), Color(0xFF56B4E9)
)

/** Traditional Ludo order (red, green, yellow, blue) in colour-blind-safe tones, by board colour 0..3. */
internal val LudoSeatColors = listOf(
    Color(0xFFD55E00), Color(0xFF009E73), Color(0xFFE5B800), Color(0xFF0072B2)
)

internal fun clShade(c: Color, f: Float): Color = if (f >= 0f) lerp(c, Color.White, f) else lerp(c, Color.Black, -f)

/** Corners of the seat glyph #[shape]: 0 circle, 1 square, 2 triangle, 3 diamond, 4 star, 5 plus. */
private fun glyphPath(shape: Int, c: Offset, r: Float): Path? {
    val p = Path()
    when (shape % 6) {
        0 -> return null
        1 -> { p.addRect(androidx.compose.ui.geometry.Rect(c.x - r * 0.85f, c.y - r * 0.85f, c.x + r * 0.85f, c.y + r * 0.85f)) }
        2 -> {
            p.moveTo(c.x, c.y - r); p.lineTo(c.x + r * 0.95f, c.y + r * 0.8f); p.lineTo(c.x - r * 0.95f, c.y + r * 0.8f); p.close()
        }
        3 -> {
            p.moveTo(c.x, c.y - r * 1.05f); p.lineTo(c.x + r * 0.85f, c.y); p.lineTo(c.x, c.y + r * 1.05f)
            p.lineTo(c.x - r * 0.85f, c.y); p.close()
        }
        4 -> {
            for (i in 0 until 10) {
                val a = -PI / 2 + i * PI / 5
                val rr = if (i % 2 == 0) r * 1.1f else r * 0.48f
                val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        else -> {
            val t = r * 0.34f
            p.moveTo(c.x - t, c.y - r); p.lineTo(c.x + t, c.y - r); p.lineTo(c.x + t, c.y - t); p.lineTo(c.x + r, c.y - t)
            p.lineTo(c.x + r, c.y + t); p.lineTo(c.x + t, c.y + t); p.lineTo(c.x + t, c.y + r); p.lineTo(c.x - t, c.y + r)
            p.lineTo(c.x - t, c.y + t); p.lineTo(c.x - r, c.y + t); p.lineTo(c.x - r, c.y - t); p.lineTo(c.x - t, c.y - t); p.close()
        }
    }
    return p
}

/** A white glyph with a dark outline: readable on every seat colour. */
internal fun DrawScope.drawSeatGlyph(shape: Int, center: Offset, r: Float) {
    val path = glyphPath(shape, center, r)
    val outline = Color(0xCC101010)
    if (path == null) {
        drawCircle(outline, r * 1.0f, center, style = Stroke(r * 0.5f))
        drawCircle(Color.White, r * 0.9f, center, style = Stroke(r * 0.3f))
    } else {
        drawPath(path, outline, style = Stroke(r * 0.5f))
        drawPath(path, Color.White)
    }
}

/**
 * A glossy marble: contact shadow, radial body gradient lit from the top-left, rim and a specular
 * highlight, with the seat glyph set into it. [lift] raises it off the board (a hop), [scale]
 * squashes/overshoots on landing.
 */
internal fun DrawScope.drawMarble(
    center: Offset, radius: Float, base: Color, shape: Int, glyph: Float = 0.5f,
    lift: Float = 0f, scale: Float = 1f, alpha: Float = 1f
) {
    val r = radius * scale
    if (r < 0.5f) return // a zero radius makes the gradient shader throw; nothing to draw at scale 0
    val c = Offset(center.x, center.y - lift)
    drawCircle(Color(0x44000000).copy(alpha = 0.27f * alpha), r * (1f - 0.12f * (lift / (radius + 0.01f)).coerceAtMost(1f)),
        Offset(center.x + r * 0.08f, center.y + r * 0.2f))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(clShade(base, 0.5f), base, clShade(base, -0.38f)),
            center = Offset(c.x - r * 0.32f, c.y - r * 0.38f), radius = r * 1.6f
        ),
        radius = r, center = c, alpha = alpha
    )
    drawCircle(clShade(base, -0.55f).copy(alpha = 0.8f * alpha), r, c, style = Stroke(r * 0.09f))
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), r * 0.2f, Offset(c.x - r * 0.4f, c.y - r * 0.45f))
    if (glyph > 0f) drawSeatGlyph(shape, Offset(c.x, c.y + r * 0.04f), r * glyph * 0.55f)
}

/** Confetti physics from the shared [ParticleBurst], drawn over the whole box. */
internal fun DrawScope.drawBurst(burst: ParticleBurst) {
    burst.particles.value.forEach { p ->
        drawCircle(
            p.color.copy(alpha = p.lifeFraction),
            size.minDimension * 0.012f + 3f,
            Offset(p.pos.x * size.width, p.pos.y * size.height)
        )
    }
}

/** True while a touch-exploration screen reader (TalkBack) is on. */
@Composable
internal fun rememberClTouchExploration(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        try {
            (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * The shared "How to play" sheet (core/GameCoach strings, EN+ES) with a first-run auto-open. Returns
 * a state pair: whether the sheet is showing and a function that opens it (for the (?) button).
 */
@Composable
internal fun rememberClCoach(gameId: String, ready: Boolean): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    val store = remember(ctx) { ClubProfileStore(ctx.applicationContext) }
    val profile by remember(store) { store.profile }.collectAsState(initial = null)
    var shown by remember(gameId) { mutableStateOf(false) }
    var autoDone by remember(gameId) { mutableStateOf(false) }
    LaunchedEffect(profile, ready) {
        val p = profile
        if (ready && p != null && !autoDone) {
            autoDone = true
            if (p.shouldAutoCoach(gameId)) shown = true
        }
    }
    val scope = rememberCoroutineScope()
    if (shown) {
        CoachCard(gameId = gameId, onDismiss = {
            shown = false
            scope.launch { try { store.markCoachSeen(gameId) } catch (_: Exception) { } }
        })
    }
    return shown to { shown = true }
}

@Composable
internal fun ClCoachButton(gameId: String, open: () -> Unit) = CoachHelpButton(gameId = gameId, onClick = open)

/** A polite live-region line: TalkBack reads each new value, sighted players read the status. */
@Composable
internal fun ClStatusLine(text: String, color: Color, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Text(
        text, color = color, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        textAlign = TextAlign.Center,
        fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium
    )
}

/** The "why can't I do that" line; assertive so a blocked tap is spoken at once. */
@Composable
internal fun ClWhyLine(text: String?, color: Color, container: Color) {
    if (text == null) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(container, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Small on-screen marker of a seat: the coloured marble with its glyph (and nothing but shapes, no text). */
@Composable
internal fun ClSeatBadge(color: Color, shape: Int, sizeDp: Int = 22, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp.dp)) {
        drawMarble(Offset(size.width / 2, size.height / 2), size.minDimension * 0.46f, color, shape, glyph = 0.62f)
    }
}

/** The end-of-board card shared by both games. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClFinishedPanel(
    title: String,
    subtitle: String?,
    lines: List<String>,
    canPlayAgain: Boolean,
    onPlayAgain: () -> Unit,
    onLeave: () -> Unit,
    playAgainLabel: String = stringResource(R.string.ccl_play_again),
    backLabel: String = stringResource(R.string.ccl_back_to_menu)
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPlayAgain, enabled = canPlayAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text(playAgainLabel) }
                ChangeSetupButton(onLeave = onLeave)
                OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) { Text(backLabel) }
            }
        }
    }
}

@Composable
internal fun ClUndoHintRow(
    canUndo: Boolean, canHint: Boolean, onUndo: () -> Unit, onHint: () -> Unit, contentColor: Color
) {
    val colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onUndo, enabled = canUndo, colors = colors, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ccl_undo))
        }
        OutlinedButton(onClick = onHint, enabled = canHint, colors = colors, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ccl_hint))
        }
    }
}

/** True when a seat name is the local "You" label (English literal or the localised hub string). */
fun Context.isYouName(name: String): Boolean = name == "You" || name == getString(R.string.hub_you)

/** What to show for a seat name: the engine's literal "You" reads as the localised word ("Tú"). Logic keeps the raw name. */
fun Context.seatLabel(name: String): String = if (name == "You") getString(R.string.hub_you) else name

/** [other] formatted with the name, or the second-person [you] string when the seat is "You". */
fun Context.youAware(name: String, other: Int, you: Int, vararg extra: Any): String =
    if (isYouName(name)) getString(you) else getString(other, name, *extra)

@Composable
fun youAware(name: String, other: Int, you: Int, vararg extra: Any): String =
    LocalContext.current.youAware(name, other, you, *extra)
