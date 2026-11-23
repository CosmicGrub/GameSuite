package com.gamesuite.ui.club

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.core.CoachMotif
import com.gamesuite.core.GameCoach
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubFamily
import com.gamesuite.settings.LocalReducedMotion
import com.gamesuite.ui.gameAccent
import com.gamesuite.ui.hubText
import com.gamesuite.ui.onGameAccent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The first-run "How to play" tutorial for [gameId] as a bottom sheet: an animated diagram of the
 * game's core idea, numbered steps, and a "Did you know?" history card. Text comes from
 * [GameCoach] (EN + ES). Wire it to a (?) button with [CoachHelpButton], and auto-open it the first
 * time with ClubProfile.shouldAutoCoach. [onDontShowAgain], when given, adds that button.
 *
 * Accessible: every step is one TalkBack stop ("Step 2 of 4: ..."), buttons are 48dp, the diagram is
 * decorative (hidden from TalkBack, the steps carry the content), the body scrolls for large fonts and
 * short landscape screens, and the diagram holds still under Reduced Motion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachCard(
    gameId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDontShowAgain: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        CoachCardContent(gameId, onDismiss, onDontShowAgain)
    }
}

/** The sheet's body, separate so it can also be embedded in a dialog or a screen. */
@Composable
fun CoachCardContent(gameId: String, onDone: () -> Unit, onDontShowAgain: (() -> Unit)? = null) {
    val entry = remember(gameId) { GameCoach.forGame(gameId) }
    val spec = remember(gameId) { GameHubSpecs.byId(gameId) }
    val title = spec?.title?.let { hubText(it) } ?: gameId.replace('-', ' ').replaceFirstChar { it.uppercase() }
    val accent = gameAccent(gameId, spec?.family ?: HubFamily.BOARD)
    val onAccent = onGameAccent(accent)

    // The steps scroll; "Got it" is pinned below them, so it is on screen at large fonts and in landscape.
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(6.dp, 36.dp).background(accent, RoundedCornerShape(3.dp)))
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.club_coach_how_to_play), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (entry == null) {
            Text(stringResource(R.string.club_coach_missing), style = MaterialTheme.typography.bodyLarge)
        } else {
            CoachMotifArt(
                entry.motif, accent,
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                    .clearAndSetSemantics { }
            )
            entry.steps.forEachIndexed { index, res ->
                val text = stringResource(res)
                val spoken = stringResource(R.string.club_coach_step_cd, index + 1, entry.steps.size, text)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = spoken },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier.size(32.dp).background(accent, CircleShape).clearAndSetSemantics { },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", color = onAccent, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 4.dp))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.club_coach_did_you_know), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(entry.fact), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.club_coach_got_it))
    }
    if (onDontShowAgain != null) {
        TextButton(onClick = onDontShowAgain, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.club_coach_dont_show))
        }
    }
    }
}

/** A 48dp (?) button that opens the coach card: drop it in a game's top bar or Hub header. */
@Composable
fun CoachHelpButton(gameId: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spec = remember(gameId) { GameHubSpecs.byId(gameId) }
    val title = spec?.title?.let { hubText(it) } ?: gameId
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        val label = stringResource(R.string.club_coach_open, title)
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

// ---- the diagrams --------------------------------------------------------------------------------

/** A looping 0..1 phase, or a fixed "finished" frame when the player prefers reduced motion. */
@Composable
private fun rememberPhase(): Float {
    if (LocalReducedMotion.current) return 0.85f
    return rememberInfiniteTransition(label = "coach-art").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "coach-phase"
    ).value
}

private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Composable
internal fun CoachMotifArt(motif: CoachMotif, accent: Color, modifier: Modifier = Modifier) {
    val t = rememberPhase()
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Canvas(modifier) {
        when (motif) {
            CoachMotif.BOARD_MOVE -> drawBoardMove(t, accent, ink, soft)
            CoachMotif.LINE_UP -> drawLineUp(t, accent, ink, soft)
            CoachMotif.SOW -> drawSow(t, accent, soft)
            CoachMotif.CARDS -> drawCards(t, accent, ink)
            CoachMotif.LETTERS -> drawLetters(t, accent, soft)
            CoachMotif.NUMBERS -> drawNumbers(t, accent, soft)
            CoachMotif.DICE -> drawDice(t, accent, ink)
            CoachMotif.TILES -> drawTiles(t, accent, soft)
            CoachMotif.BALL -> drawBall(t, accent, ink, soft)
            CoachMotif.PATH -> drawPathArt(t, accent, soft)
        }
    }
}

private fun DrawScope.drawBoardMove(t: Float, accent: Color, ink: Color, soft: Color) {
    val cell = size.height * 0.2f
    val x0 = (size.width - cell * 4) / 2f
    val y0 = (size.height - cell * 4) / 2f
    for (r in 0 until 4) for (c in 0 until 4) {
        if ((r + c) % 2 == 1) drawRect(soft, Offset(x0 + c * cell, y0 + r * cell), Size(cell, cell))
    }
    // target ring pulses, piece hops from (col 0,row 3) to (col 2,row 1)
    val p = ease((t - 0.15f) / 0.5f)
    val fx = x0 + 0.5f * cell; val fy = y0 + 3.5f * cell
    val tx = x0 + 2.5f * cell; val ty = y0 + 1.5f * cell
    drawCircle(accent.copy(alpha = 0.35f + 0.3f * abs(sin(t * 2f * PI.toFloat()))), cell * 0.42f, Offset(tx, ty), style = Stroke(cell * 0.08f))
    val hop = sin(p * PI.toFloat()) * cell * 0.5f
    val cx = fx + (tx - fx) * p; val cy = fy + (ty - fy) * p - hop
    drawCircle(ink.copy(alpha = 0.25f), cell * 0.36f, Offset(cx, cy + hop + cell * 0.06f))
    drawCircle(accent, cell * 0.36f, Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.35f), cell * 0.12f, Offset(cx - cell * 0.1f, cy - cell * 0.1f))
}

private fun DrawScope.drawLineUp(t: Float, accent: Color, ink: Color, soft: Color) {
    val cell = size.height * 0.27f
    val x0 = (size.width - cell * 3) / 2f
    val y0 = (size.height - cell * 3) / 2f
    val grid = Stroke(cell * 0.04f, cap = StrokeCap.Round)
    for (i in 1..2) {
        drawLine(soft, Offset(x0 + i * cell, y0), Offset(x0 + i * cell, y0 + cell * 3), cell * 0.04f, StrokeCap.Round)
        drawLine(soft, Offset(x0, y0 + i * cell), Offset(x0 + cell * 3, y0 + i * cell), cell * 0.04f, StrokeCap.Round)
    }
    val shown = (t * 5f).toInt().coerceAtMost(4)
    // Alternating marks: a decoy ring and three accent dots along the diagonal.
    val diag = listOf(0, 4, 8)
    val order = listOf(0, 1, 4, 2, 8)
    for (k in 0 until shown.coerceAtMost(order.size)) {
        val idx = order[k]
        val c = Offset(x0 + (idx % 3 + 0.5f) * cell, y0 + (idx / 3 + 0.5f) * cell)
        if (idx in diag) drawCircle(accent, cell * 0.28f, c)
        else drawCircle(ink.copy(alpha = 0.5f), cell * 0.26f, c, style = grid)
    }
    if (shown >= 4) {
        drawLine(accent, Offset(x0 + cell * 0.2f, y0 + cell * 0.2f), Offset(x0 + cell * 2.8f, y0 + cell * 2.8f), cell * 0.1f, StrokeCap.Round)
    }
}

private fun DrawScope.drawSow(t: Float, accent: Color, soft: Color) {
    val n = 5
    val gap = size.width / (n + 1)
    val y = size.height * 0.55f
    val r = gap * 0.36f
    for (i in 0 until n) drawCircle(soft, r, Offset(gap * (i + 1), y))
    val seg = (t * (n - 1)).coerceAtMost(n - 1.001f)
    val i = seg.toInt()
    val f = ease(seg - i)
    val ax = gap * (i + 1); val bx = gap * (i + 2)
    val x = ax + (bx - ax) * f
    val hop = sin(f * PI.toFloat()) * r * 1.4f
    drawCircle(accent, r * 0.38f, Offset(x, y - hop))
    for (k in 0..i) drawCircle(accent.copy(alpha = 0.8f), r * 0.3f, Offset(gap * (k + 1), y + r * 0.1f))
}

private fun DrawScope.drawCards(t: Float, accent: Color, ink: Color) {
    val w = size.height * 0.4f
    val h = w * 1.4f
    val lift = ease(abs(sin(t * PI.toFloat()))) * h * 0.18f
    val cx = size.width / 2f
    val cy = size.height / 2f + h * 0.05f
    listOf(-18f, 0f, 18f).forEachIndexed { i, ang ->
        rotate(ang, Offset(cx, cy + h * 0.4f)) {
            val up = if (i == 1) lift else 0f
            val tl = Offset(cx - w / 2f, cy - h / 2f - up)
            drawRoundRect(Color.White, tl, Size(w, h), CornerRadius(w * 0.1f))
            drawRoundRect(ink.copy(alpha = 0.35f), tl, Size(w, h), CornerRadius(w * 0.1f), style = Stroke(2f))
            drawCircle(if (i == 1) accent else ink.copy(alpha = 0.45f), w * 0.2f, Offset(cx, cy - up))
        }
    }
}

private fun DrawScope.drawLetters(t: Float, accent: Color, soft: Color) {
    val n = 4
    val tile = size.height * 0.3f
    val gap = tile * 0.18f
    val total = n * tile + (n - 1) * gap
    val x0 = (size.width - total) / 2f
    val y = size.height / 2f - tile / 2f
    for (i in 0 until n) {
        val p = ease((t * 1.4f - i * 0.15f) / 0.35f)
        val dy = (1f - p) * -size.height * 0.5f
        val tl = Offset(x0 + i * (tile + gap), y + dy)
        drawRoundRect(soft, Offset(x0 + i * (tile + gap), y), Size(tile, tile), CornerRadius(tile * 0.2f), style = Stroke(2f))
        drawRoundRect(accent.copy(alpha = 0.25f + 0.75f * p), tl, Size(tile, tile), CornerRadius(tile * 0.2f))
        // a simple stroke glyph per tile so tiles read as letters without text
        val g = Path().apply {
            when (i % 4) {
                0 -> { moveTo(tl.x + tile * 0.25f, tl.y + tile * 0.78f); lineTo(tl.x + tile * 0.5f, tl.y + tile * 0.22f); lineTo(tl.x + tile * 0.75f, tl.y + tile * 0.78f) }
                1 -> { moveTo(tl.x + tile * 0.3f, tl.y + tile * 0.22f); lineTo(tl.x + tile * 0.3f, tl.y + tile * 0.78f); lineTo(tl.x + tile * 0.72f, tl.y + tile * 0.78f) }
                2 -> { addOval(androidx.compose.ui.geometry.Rect(tl.x + tile * 0.28f, tl.y + tile * 0.24f, tl.x + tile * 0.72f, tl.y + tile * 0.76f)) }
                else -> { moveTo(tl.x + tile * 0.28f, tl.y + tile * 0.25f); lineTo(tl.x + tile * 0.72f, tl.y + tile * 0.25f); moveTo(tl.x + tile * 0.5f, tl.y + tile * 0.25f); lineTo(tl.x + tile * 0.5f, tl.y + tile * 0.78f) }
            }
        }
        drawPath(g, Color.White, style = Stroke(tile * 0.09f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawNumbers(t: Float, accent: Color, soft: Color) {
    val cell = size.height * 0.24f
    val x0 = (size.width - cell * 3) / 2f
    val y0 = (size.height - cell * 3) / 2f
    val filled = (t * 10f).toInt().coerceAtMost(9)
    for (i in 0 until 9) {
        val tl = Offset(x0 + (i % 3) * cell + 3f, y0 + (i / 3) * cell + 3f)
        val sz = Size(cell - 6f, cell - 6f)
        drawRoundRect(soft, tl, sz, CornerRadius(cell * 0.14f))
        if (i < filled) {
            drawRoundRect(accent.copy(alpha = 0.85f), tl, sz, CornerRadius(cell * 0.14f))
            val pips = (i % 4) + 1
            for (k in 0 until pips) {
                val a = (2.0 * PI * k / pips).toFloat()
                val c = if (pips == 1) tl + Offset(sz.width / 2, sz.height / 2) else tl + Offset(sz.width / 2 + sin(a) * sz.width * 0.22f, sz.height / 2 - kotlin.math.cos(a) * sz.height * 0.22f)
                drawCircle(Color.White, cell * 0.06f, c)
            }
        }
    }
}

private fun DrawScope.drawDice(t: Float, accent: Color, ink: Color) {
    val side = size.height * 0.5f
    val face = (t * 6f).toInt().coerceIn(0, 5) + 1
    val wiggle = if (t < 0.85f) sin(t * 24f) * 12f * (1f - t) else 0f
    val c = Offset(size.width / 2f, size.height / 2f)
    rotate(wiggle, c) {
        val tl = Offset(c.x - side / 2f, c.y - side / 2f)
        drawRoundRect(Color.White, tl, Size(side, side), CornerRadius(side * 0.18f))
        drawRoundRect(accent, tl, Size(side, side), CornerRadius(side * 0.18f), style = Stroke(side * 0.06f))
        val pos = mapOf(
            1 to listOf(5), 2 to listOf(1, 9), 3 to listOf(1, 5, 9), 4 to listOf(1, 3, 7, 9),
            5 to listOf(1, 3, 5, 7, 9), 6 to listOf(1, 3, 4, 6, 7, 9)
        ).getValue(face)
        for (cellNo in pos) {
            val col = (cellNo - 1) % 3; val row = (cellNo - 1) / 3
            drawCircle(ink, side * 0.07f, Offset(tl.x + side * (0.25f + 0.25f * col), tl.y + side * (0.25f + 0.25f * row)))
        }
    }
}

private fun DrawScope.drawTiles(t: Float, accent: Color, soft: Color) {
    val tile = size.height * 0.34f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val p = ease((t - 0.2f) / 0.45f)
    val gapFar = tile * 1.7f
    val off = gapFar * (1f - p) + tile * 0.52f * p
    val fade = if (t > 0.85f) (1f - (t - 0.85f) / 0.15f) else 1f
    for (dir in listOf(-1f, 1f)) {
        val tl = Offset(cx + dir * off - tile / 2f, cy - tile / 2f)
        drawRoundRect(soft, tl + Offset(0f, 5f), Size(tile, tile), CornerRadius(tile * 0.2f))
        drawRoundRect(Color.White.copy(alpha = fade), tl, Size(tile, tile), CornerRadius(tile * 0.2f))
        val star = Path()
        for (k in 0 until 10) {
            val a = (PI * k / 5.0 - PI / 2.0).toFloat()
            val rr = if (k % 2 == 0) tile * 0.3f else tile * 0.13f
            val x = tl.x + tile / 2f + rr * kotlin.math.cos(a); val y = tl.y + tile / 2f + rr * sin(a)
            if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, accent.copy(alpha = fade))
    }
}

private fun DrawScope.drawBall(t: Float, accent: Color, ink: Color, soft: Color) {
    val w = size.width
    val h = size.height
    for (i in 0 until 5) drawRoundRect(accent.copy(alpha = 0.3f + 0.14f * i), Offset(w * 0.2f + i * w * 0.12f, h * 0.12f), Size(w * 0.1f, h * 0.1f), CornerRadius(6f))
    val px = w / 2f + sin(t * 2f * PI.toFloat()) * w * 0.25f
    drawRoundRect(ink, Offset(px - w * 0.1f, h * 0.84f), Size(w * 0.2f, h * 0.06f), CornerRadius(8f))
    val bx = w / 2f + sin(t * 2f * PI.toFloat() + 0.8f) * w * 0.3f
    val by = h * 0.28f + abs(sin(t * 2f * PI.toFloat() * 1.5f)) * h * 0.5f
    drawCircle(soft, h * 0.05f, Offset(bx, by + 6f))
    drawCircle(accent, h * 0.05f, Offset(bx, by))
}

private fun DrawScope.drawPathArt(t: Float, accent: Color, soft: Color) {
    val pts = listOf(
        Offset(size.width * 0.18f, size.height * 0.72f), Offset(size.width * 0.18f, size.height * 0.28f),
        Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.72f),
        Offset(size.width * 0.82f, size.height * 0.72f)
    )
    pts.forEach { drawCircle(soft, size.height * 0.07f, it) }
    val total = (pts.size - 1) * ease(t / 0.85f).coerceIn(0f, 1f)
    for (i in 0 until pts.size - 1) {
        val f = (total - i).coerceIn(0f, 1f)
        if (f <= 0f) break
        val end = pts[i] + (pts[i + 1] - pts[i]) * f
        drawLine(accent, pts[i], end, size.height * 0.06f, StrokeCap.Round)
    }
    drawCircle(accent, size.height * 0.07f, pts.first())
    if (total >= pts.size - 1.01f) drawCircle(accent, size.height * 0.07f, pts.last())
}
