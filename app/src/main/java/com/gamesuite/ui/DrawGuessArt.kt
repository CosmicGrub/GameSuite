@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.drawguess.DrawGuessGame
import com.gamesuite.games.drawguess.DrawGuessGlyphs
import com.gamesuite.games.drawguess.DrawLimits
import com.gamesuite.games.drawguess.DrawPalette
import com.gamesuite.games.drawguess.DrawStroke
import com.gamesuite.games.drawguess.DrawWord
import com.gamesuite.games.drawguess.StrokeLayerPlanner
import com.gamesuite.games.drawguess.GLYPH_COLORS
import com.gamesuite.games.drawguess.GlyphOp
import com.gamesuite.games.drawguess.WordLang
import com.gamesuite.games.partypack.TapTugPointerTracker

/** The drawer's current pen: palette index, brush index, or the eraser. */
data class DrawTool(val color: Int = 0, val size: Int = 1, val eraser: Boolean = false)

private val GlyphOutline = Color(0xFF2B2B2B).copy(alpha = 0.8f)
private const val OUTLINE_W = 1.6f

/** The prepared polygon paths of a glyph (unit coordinates), so drawing a card never rebuilds them. */
private fun glyphPaths(ops: List<GlyphOp>): Array<Path?> = Array(ops.size) { i ->
    val op = ops[i]
    if (op is GlyphOp.Poly) Path().apply {
        moveTo(op.xy[0], op.xy[1])
        var k = 2
        while (k + 1 < op.xy.size) { lineTo(op.xy[k], op.xy[k + 1]); k += 2 }
        close()
    } else null
}

private fun glyphColor(c: Char): Color = Color(GLYPH_COLORS[c] ?: 0xFF2B2B2BL)

private fun DrawScope.drawGlyph(ops: List<GlyphOp>, paths: Array<Path?>, sizePx: Float) {
    val f = sizePx / 100f
    withTransform({ scale(f, f, Offset.Zero) }) {
        for (i in ops.indices) {
            when (val op = ops[i]) {
                is GlyphOp.Ellipse -> {
                    val tl = Offset(op.cx - op.rx, op.cy - op.ry)
                    val sz = Size(op.rx * 2, op.ry * 2)
                    drawOval(glyphColor(op.color), tl, sz)
                    drawOval(GlyphOutline, tl, sz, style = Stroke(OUTLINE_W))
                }
                is GlyphOp.Rect -> {
                    drawRect(glyphColor(op.color), Offset(op.x, op.y), Size(op.w, op.h))
                    drawRect(GlyphOutline, Offset(op.x, op.y), Size(op.w, op.h), style = Stroke(OUTLINE_W))
                }
                is GlyphOp.Poly -> paths[i]?.let {
                    drawPath(it, glyphColor(op.color))
                    drawPath(it, GlyphOutline, style = Stroke(OUTLINE_W, join = StrokeJoin.Round))
                }
                is GlyphOp.Line -> drawLine(glyphColor(op.color), Offset(op.x1, op.y1), Offset(op.x2, op.y2), strokeWidth = op.w, cap = StrokeCap.Round)
                is GlyphOp.Arc -> drawArc(
                    glyphColor(op.color), op.start, op.sweep, false,
                    Offset(op.cx - op.r, op.cy - op.r), Size(op.r * 2, op.r * 2), style = Stroke(op.w, cap = StrokeCap.Round)
                )
                is GlyphOp.Ring -> drawOval(glyphColor(op.color), Offset(op.cx - op.rx, op.cy - op.ry), Size(op.rx * 2, op.ry * 2), style = Stroke(op.w))
            }
        }
    }
}

/** A word's picture on a white tile (white behind it in every theme, so the colours always read the same). Decorative: the card carries the spoken label. */
@Composable
fun GlyphTile(key: String, size: Dp, modifier: Modifier = Modifier) {
    val ops = remember(key) { DrawGuessGlyphs.ops(key) }
    val paths = remember(ops) { glyphPaths(ops) }
    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clearAndSetSemantics { }
    ) { drawGlyph(ops, paths, this.size.minDimension) }
}

/** Three dots, [level] of them filled: how tricky a word is, without colour or text. */
@Composable
fun DifficultyDots(level: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(width = 44.dp, height = 12.dp).clearAndSetSemantics { }) {
        val r = size.height / 2f
        for (i in 0 until 3) {
            val c = Offset(r + i * (size.width - 2 * r) / 2f, r)
            if (i < level) drawCircle(ink, r, c) else drawCircle(ink, r - 1f, c, style = Stroke(2f))
        }
    }
}

/**
 * A word as a picture AND text, at least 56dp tall. [crossedOut] dims it and strikes it through (a card somebody already
 * tried), never by colour alone. [description] is what TalkBack reads; null means the plain word.
 */
@Composable
fun WordCard(
    word: DrawWord,
    lang: WordLang,
    glyphSize: Dp,
    modifier: Modifier = Modifier,
    crossedOut: Boolean = false,
    showDifficulty: Boolean = false,
    description: String? = null,
    onClick: (() -> Unit)? = null
) {
    val name = word.text(lang)
    val shape = RoundedCornerShape(16.dp)
    var m = modifier
        .heightIn(min = 56.dp)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(2.dp, MaterialTheme.colorScheme.outline, shape)
    if (onClick != null && !crossedOut) m = m.clickable(role = Role.Button, onClick = onClick)
    m = m.semantics(mergeDescendants = true) { contentDescription = description ?: name }
    Box(m, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(8.dp).alpha(if (crossedOut) 0.45f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlyphTile(word.key, glyphSize)
            Text(name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (showDifficulty) DifficultyDots(word.difficulty)
        }
        if (crossedOut) {
            val ink = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.matchParentSize()) {
                drawLine(ink, Offset(8f, size.height - 8f), Offset(size.width - 8f, 8f), strokeWidth = 5f, cap = StrokeCap.Round)
            }
        }
    }
}

// ---- the drawing canvas -------------------------------------------------------------------------

/** Committed strokes as paths in canvas units (1000 wide), kept between frames and updated only when the stroke list changes. */
private class StrokePathCache {
    val owners = ArrayList<DrawStroke>()
    val paths = ArrayList<Path>()

    fun sync(strokes: List<DrawStroke>) {
        var common = 0
        while (common < owners.size && common < strokes.size && owners[common] === strokes[common]) common++
        while (owners.size > common) { owners.removeAt(owners.lastIndex); paths.removeAt(paths.lastIndex) }
        for (i in common until strokes.size) { owners += strokes[i]; paths += buildStrokePath(strokes[i]) }
    }
}

/**
 * A bitmap of every finished stroke, so a frame costs one image draw plus the live stroke instead of redrawing up to 300
 * paths. New strokes are painted on top; undo, clear or a new paper size rebuild it (see [StrokeLayerPlanner]).
 */
private class CommittedLayer {
    private var bitmap: ImageBitmap? = null
    private var canvas: androidx.compose.ui.graphics.Canvas? = null
    private val rendered = ArrayList<DrawStroke>()
    private var w = 0
    private var h = 0
    private val scope = CanvasDrawScope()

    fun draw(
        target: DrawScope, owners: List<DrawStroke>, paths: List<Path>,
        inkColors: Array<Color>, paper: Color, brushes: Array<Stroke>
    ) {
        val wi = target.size.width.toInt()
        val hi = target.size.height.toInt()
        if (wi <= 0 || hi <= 0) return
        val sizeChanged = bitmap == null || wi != w || hi != h
        if (sizeChanged) {
            val b = ImageBitmap(wi, hi)
            bitmap = b; canvas = androidx.compose.ui.graphics.Canvas(b); w = wi; h = hi
        }
        val plan = StrokeLayerPlanner.plan(rendered, owners, sizeChanged)
        val bmp = bitmap ?: return
        val cv = canvas ?: return
        if (plan.hasWork) {
            if (plan.reset) {
                cv.drawRect(0f, 0f, wi.toFloat(), hi.toFloat(), androidx.compose.ui.graphics.Paint().apply { blendMode = androidx.compose.ui.graphics.BlendMode.Clear })
                rendered.clear()
            }
            val f = wi.toFloat() / DrawLimits.COORD_MAX
            scope.draw(target, target.layoutDirection, cv, Size(wi.toFloat(), hi.toFloat())) {
                withTransform({ scale(f, f, Offset.Zero) }) {
                    for (i in plan.from until owners.size) {
                        val s = owners[i]
                        drawPath(paths[i], if (s.eraser) paper else inkColors[s.color], style = brushes[s.size])
                        rendered += s
                    }
                }
            }
        }
        target.drawImage(bmp)
    }
}

private fun buildStrokePath(s: DrawStroke): Path {
    val p = Path()
    val xy = s.xy
    p.moveTo(xy[0].toFloat(), xy[1].toFloat())
    if (s.pointCount == 1) { p.lineTo(xy[0] + 0.1f, xy[1].toFloat()); return p }
    var i = 1
    while (i < s.pointCount) {
        val px = xy[2 * (i - 1)].toFloat(); val py = xy[2 * (i - 1) + 1].toFloat()
        val x = xy[2 * i].toFloat(); val y = xy[2 * i + 1].toFloat()
        p.quadraticBezierTo(px, py, (px + x) / 2f, (py + y) / 2f)
        i++
    }
    p.lineTo(xy[2 * (s.pointCount - 1)].toFloat(), xy[2 * (s.pointCount - 1) + 1].toFloat())
    return p
}

/**
 * The 4:3 paper. Strokes are cached [Path]s in canvas units, so a frame only redraws them; the stroke under the finger
 * is one [Path] that grows in place (no list copies per point). [interactive] false shows the drawing read-only
 * (guessing, reveal, final screen). A finger-down on a full canvas calls [onFull].
 */
@Composable
fun DrawingCanvas(
    game: DrawGuessGame,
    tool: DrawTool,
    interactive: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    onFull: () -> Unit = {},
    onRoom: () -> Unit = {}
) {
    val board = game.board
    val cache = remember { StrokePathCache() }
    val layer = remember { CommittedLayer() }
    val brushes = remember { Array(DrawLimits.BRUSH_COUNT) { Stroke(DrawPalette.brushFractions[it] * DrawLimits.COORD_MAX, cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val activePath = remember { Path() }
    var tick by remember { mutableIntStateOf(0) }
    val toolNow by rememberUpdatedState(tool)
    val interactiveNow by rememberUpdatedState(interactive)
    val onFullNow by rememberUpdatedState(onFull)
    val onRoomNow by rememberUpdatedState(onRoom)
    val inkColors = remember { Array(DrawLimits.PALETTE_SIZE) { Color(DrawPalette.colors[it]) } }
    val paper = remember { Color(DrawPalette.PAPER) }
    val contacts = remember { ContactSizes() }
    val ctx = LocalContext.current
    // A contact bigger than a fingertip (a resting palm or wrist) never starts a stroke.
    val maxContact = remember(ctx) { TapTugPointerTracker.maxFingerContactPx(ctx.resources.displayMetrics.densityDpi.toFloat()) }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(paper)
            .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            // Touch sizes are only on the raw MotionEvent. Accept the gesture only while drawing is on, so a read-only
            // canvas never blocks the page from scrolling; the pointer model below reads pressed/previousPressed itself.
            .pointerInteropFilter { e -> if (interactiveNow) { contacts.record(e); true } else false }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                // One finger owns a stroke, found by its id. Palms are skipped, extra fingers are ignored while a stroke is
                // live, and once that finger lifts the next finger-down starts a new stroke (no waiting for every finger to lift).
                var activeId = -1L
                fun finishStroke() {
                    if (board.activePointCount == 1) activePath.lineTo(board.activeX(0) + 0.1f, board.activeY(0).toFloat())
                    game.endStroke()
                    activePath.reset()
                    tick++
                    activeId = -1L
                }
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val w = size.width.toFloat()
                            if (activeId < 0L) {
                                val down = event.changes.firstOrNull {
                                    it.pressed && !it.previousPressed && contacts.sizeAt(it.position.x, it.position.y) <= maxContact
                                }
                                if (down != null) {
                                    down.consume()
                                    val t = toolNow
                                    val started = game.beginStroke(t.color, t.size, t.eraser, DrawLimits.normalise(down.position.x, w), DrawLimits.normalise(down.position.y, w))
                                    if (started) {
                                        onRoomNow()
                                        activeId = down.id.value
                                        activePath.reset()
                                        activePath.moveTo(board.activeX(0).toFloat(), board.activeY(0).toFloat())
                                        tick++
                                    } else if (board.capHit) onFullNow()
                                }
                            } else {
                                val c = event.changes.firstOrNull { it.id.value == activeId }
                                if (c != null && c.pressed) {
                                    for (h in c.historical) addPoint(game, activePath, h.position.x, h.position.y, w)
                                    addPoint(game, activePath, c.position.x, c.position.y, w)
                                    // Say so as soon as a cap cuts the line, not only on the next finger-down.
                                    if (board.capHit) onFullNow()
                                    c.consume()
                                    tick++
                                } else finishStroke()
                            }
                        }
                    }
                } finally {
                    // Drawing stopped being interactive (pause, buzz, time up) mid-stroke: close it rather than leave it open.
                    if (activeId >= 0L) finishStroke()
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE") val redraw = tick
        // Read the board version INSIDE the draw scope. Undo and Clear only bump it; with strong skipping this lambda is
        // memoised (it captures only remembered instances), so a read made in composition alone would never invalidate
        // the draw and the paper would keep showing undone strokes until the next touch.
        @Suppress("UNUSED_VARIABLE") val version = game.boardVersion.value
        cache.sync(board.strokes)
        // Finished strokes come from one cached bitmap; only the stroke under the finger is drawn path by path.
        layer.draw(this, cache.owners, cache.paths, inkColors, paper, brushes)
        if (board.drawing) {
            val f = size.width / DrawLimits.COORD_MAX
            withTransform({ scale(f, f, Offset.Zero) }) {
                val color = if (board.activeEraser) paper else inkColors[board.activeColor]
                drawPath(activePath, color, style = brushes[board.activeSize])
            }
        }
    }
}

/** Adds a point to the board and, if it was kept, extends the live path with a smoothed segment. No allocation per point. */
private fun addPoint(game: DrawGuessGame, path: Path, px: Float, py: Float, w: Float) {
    val before = game.board.activePointCount
    if (!game.extendStroke(DrawLimits.normalise(px, w), DrawLimits.normalise(py, w))) return
    val b = game.board
    val n = b.activePointCount
    if (n <= before || n < 2) return
    val lx = b.activeX(n - 2).toFloat(); val ly = b.activeY(n - 2).toFloat()
    val x = b.activeX(n - 1).toFloat(); val y = b.activeY(n - 1).toFloat()
    path.quadraticBezierTo(lx, ly, (lx + x) / 2f, (ly + y) / 2f)
}

// ---- the pen tools ------------------------------------------------------------------------------

/** A colour swatch, 56dp. The selected one gets a thick ring AND a check; each colour also carries its own mark so none relies on hue alone. */
@Composable
fun ColorSwatch(index: Int, selected: Boolean, name: String, selectedWord: String, onClick: () -> Unit) {
    val ink = Color(DrawPalette.colors[index])
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ink)
            .border(if (selected) 5.dp else 1.dp, if (selected) ring else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // The radio state is real semantics now (TalkBack says "selected" itself), so the name stays plain.
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = Color.White
            val s = size.minDimension
            when (index) {
                0 -> drawCircle(c, s * 0.4f)
                1 -> drawRect(c, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f))
                2 -> drawPath(Path().apply { moveTo(s / 2, 0f); lineTo(s, s); lineTo(0f, s); close() }, c)
                3 -> drawPath(Path().apply { moveTo(s / 2, 0f); lineTo(s, s / 2); lineTo(s / 2, s); lineTo(0f, s / 2); close() }, c)
                4 -> { drawLine(c, Offset(s / 2, 0f), Offset(s / 2, s), 5f); drawLine(c, Offset(0f, s / 2), Offset(s, s / 2), 5f) }
                else -> { drawLine(c, Offset(s * 0.25f, 0f), Offset(s * 0.25f, s), 5f); drawLine(c, Offset(s * 0.75f, 0f), Offset(s * 0.75f, s), 5f) }
            }
        }
    }
}

/** A brush-size button, 56dp: a dot as big as the brush, plus a ring when chosen. */
@Composable
fun BrushButton(level: Int, selected: Boolean, name: String, selectedWord: String, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 4.dp else 1.dp, if (selected) ink else MaterialTheme.colorScheme.outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // The radio state is real semantics now (TalkBack says "selected" itself), so the name stays plain.
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center
    ) {
        val d = when (level) { 0 -> 8.dp; 1 -> 16.dp; else -> 28.dp }
        Box(Modifier.size(d).clip(RoundedCornerShape(50)).background(ink))
    }
}

/** A labelled text button, at least 56dp tall, for Undo, Clear, Eraser and similar. */
@Composable
fun ToolTextButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** True for an on/off tool (the Eraser): TalkBack then announces its state. */
    toggle: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 4.dp else 1.dp, if (selected) ink else MaterialTheme.colorScheme.outline, shape)
            .let {
                if (toggle) it.toggleable(value = selected, enabled = enabled, role = Role.Switch, onValueChange = { _ -> onClick() })
                else it.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center)
    }
}
