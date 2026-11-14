package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.gamesuite.games.piedetective.PieSuspect
import com.gamesuite.games.piedetective.PieTrait

/*
 * Original vector art of Pie Detective, drawn in code (no bitmaps, no fonts). Every animal is drawn in a 100 x 120
 * unit box so the six accessory layers share fixed anchors: head top y 18, eyes y 40, neck y 60, body 66..110, feet 110.
 * Every animal has its own silhouette (ears, bill, horns, tail) so the species reads from the outline alone, and every
 * accessory is a distinct SHAPE (hat, ring glasses, hanging scarf, bag with strap, tall boots, two-triangle bow tie),
 * never only a colour.
 */

private val Ink = Color(0xFF3B2A1E)
private val Cream = Color(0xFFFFF6E5)
private val Pink = Color(0xFFF4A3B5)
private val Orange = Color(0xFFF08C2E)

/** A small helper that draws in the 100 x 120 unit box placed at ([ox], [oy]) with [k] pixels per unit. */
private class U(val d: DrawScope, val ox: Float, val oy: Float, val k: Float) {
    fun o(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)

    fun circle(x: Float, y: Float, r: Float, c: Color, stroke: Float = 0f) {
        if (stroke <= 0f) d.drawCircle(c, r * k, o(x, y)) else d.drawCircle(c, r * k, o(x, y), style = Stroke(stroke * k))
    }

    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, c: Color, stroke: Float = 0f) {
        d.drawOval(c, Offset(ox + (cx - rx) * k, oy + (cy - ry) * k), Size(2f * rx * k, 2f * ry * k), style = if (stroke <= 0f) Fill else Stroke(stroke * k))
    }

    fun rrect(x: Float, y: Float, w: Float, h: Float, rad: Float, c: Color, stroke: Float = 0f) {
        d.drawRoundRect(c, o(x, y), Size(w * k, h * k), CornerRadius(rad * k), style = if (stroke <= 0f) Fill else Stroke(stroke * k))
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, w: Float) {
        d.drawLine(c, o(x1, y1), o(x2, y2), w * k, StrokeCap.Round)
    }

    fun poly(c: Color, vararg p: Float, stroke: Float = 0f) {
        val path = Path()
        path.moveTo(ox + p[0] * k, oy + p[1] * k)
        var i = 2
        while (i < p.size) { path.lineTo(ox + p[i] * k, oy + p[i + 1] * k); i += 2 }
        path.close()
        d.drawPath(path, c, style = if (stroke <= 0f) Fill else Stroke(stroke * k, join = StrokeJoin.Round))
    }

    /** A stroked quadratic curve from (x1,y1) to (x2,y2) bent towards (cx,cy). */
    fun curve(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, c: Color, w: Float) {
        val path = Path()
        path.moveTo(ox + x1 * k, oy + y1 * k)
        path.quadraticBezierTo(ox + cx * k, oy + cy * k, ox + x2 * k, oy + y2 * k)
        d.drawPath(path, c, style = Stroke(w * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, c: Color, w: Float) {
        d.drawArc(c, start, sweep, false, Offset(ox + (cx - r) * k, oy + (cy - r) * k), Size(2f * r * k, 2f * r * k), style = Stroke(w * k, cap = StrokeCap.Round))
    }
}

private fun shade(c: Color, amount: Float): Color = if (amount >= 0f) lerp(c, Color.White, amount) else lerp(c, Color.Black, -amount)

/**
 * Draws [suspect] into the square area of side [sizePx] whose top-left is [topLeft]. Only the accessories in [visible]
 * are drawn (the band's traits), so a Little case never shows boots nobody can ask about. [dim] darkens the fur.
 */
internal fun DrawScope.drawPieAnimal(suspect: PieSuspect, visible: List<PieTrait>, topLeft: Offset, sizePx: Float, dim: Float = 0f) {
    val k = sizePx / 120f
    val u = U(this, topLeft.x + (sizePx - 100f * k) / 2f, topLeft.y, k)
    val fur = shade(Color(suspect.furColour), -dim)
    drawAnimalBody(u, suspect.speciesId, fur)
    for (t in PieTrait.entries) {
        if (t in visible && suspect.has(t)) drawAccessory(u, suspect.speciesId, t)
    }
}

private fun drawAnimalBody(u: U, species: String, fur: Color) {
    val belly = shade(fur, 0.45f)
    val dark = shade(fur, -0.3f)
    // behind: tails, wool, horns, ears
    when (species) {
        "mouse" -> {
            u.curve(70f, 104f, 92f, 104f, 92f, 84f, shade(Pink, -0.1f), 3.2f)
            u.circle(30f, 22f, 12f, fur); u.circle(70f, 22f, 12f, fur)
            u.circle(30f, 22f, 7f, Pink); u.circle(70f, 22f, 7f, Pink)
        }
        "frog" -> { /* eyes are drawn on top of the head */ }
        "otter" -> {
            u.oval(82f, 102f, 15f, 6f, dark)
            u.circle(30f, 26f, 6f, fur); u.circle(70f, 26f, 6f, fur)
        }
        "pig" -> {
            u.poly(dark, 28f, 30f, 36f, 14f, 44f, 26f)
            u.poly(dark, 72f, 30f, 64f, 14f, 56f, 26f)
            u.curve(72f, 98f, 86f, 96f, 80f, 88f, shade(fur, -0.2f), 3f)
            u.curve(80f, 88f, 74f, 84f, 78f, 80f, shade(fur, -0.2f), 3f)
        }
        "sheep" -> {
            for ((x, y, r) in listOf(Triple(32f, 86f, 14f), Triple(50f, 78f, 16f), Triple(68f, 86f, 14f), Triple(36f, 102f, 12f), Triple(64f, 102f, 12f))) u.circle(x, y, r, fur)
            for ((x, y, r) in listOf(Triple(33f, 24f, 11f), Triple(50f, 17f, 12f), Triple(67f, 24f, 11f), Triple(27f, 40f, 9f), Triple(73f, 40f, 9f))) u.circle(x, y, r, fur)
            u.oval(28f, 46f, 8f, 4.5f, Color(0xFF4A3B38)); u.oval(72f, 46f, 8f, 4.5f, Color(0xFF4A3B38))
        }
        "cat" -> {
            u.curve(72f, 104f, 94f, 98f, 86f, 62f, fur, 6.5f)
            u.circle(86f, 62f, 3.4f, dark)
            u.poly(fur, 28f, 34f, 30f, 8f, 46f, 22f)
            u.poly(fur, 72f, 34f, 70f, 8f, 54f, 22f)
            u.poly(Pink, 33f, 28f, 34f, 15f, 42f, 23f)
            u.poly(Pink, 67f, 28f, 66f, 15f, 58f, 23f)
        }
        "badger" -> {
            u.curve(72f, 104f, 86f, 104f, 86f, 94f, dark, 5f)
            u.circle(31f, 24f, 8f, Color.White); u.circle(31f, 24f, 5.5f, fur)
            u.circle(69f, 24f, 8f, Color.White); u.circle(69f, 24f, 5.5f, fur)
        }
        "rabbit" -> {
            u.oval(37f, 8f, 8f, 21f, fur); u.oval(63f, 8f, 8f, 21f, fur)
            u.oval(37f, 9f, 4.2f, 15f, Pink); u.oval(63f, 9f, 4.2f, 15f, Pink)
            u.circle(76f, 102f, 8f, Color.White)
        }
        "penguin" -> {
            u.poly(shade(fur, -0.15f), 28f, 72f, 18f, 102f, 34f, 94f)
            u.poly(shade(fur, -0.15f), 72f, 72f, 82f, 102f, 66f, 94f)
        }
        "goat" -> {
            u.curve(38f, 24f, 24f, 18f, 30f, 2f, Color(0xFFEFE3C8), 5f)
            u.curve(62f, 24f, 76f, 18f, 70f, 2f, Color(0xFFEFE3C8), 5f)
            u.oval(26f, 40f, 9f, 4.5f, dark); u.oval(74f, 40f, 9f, 4.5f, dark)
        }
        "duck" -> {
            u.oval(24f, 90f, 8f, 15f, shade(fur, -0.18f)); u.oval(76f, 90f, 8f, 15f, shade(fur, -0.18f))
        }
        "squirrel" -> {
            u.oval(80f, 80f, 15f, 29f, dark)
            u.oval(82f, 78f, 8f, 21f, shade(fur, 0.25f))
            u.poly(fur, 30f, 30f, 32f, 12f, 44f, 22f)
            u.poly(fur, 70f, 30f, 68f, 12f, 56f, 22f)
            u.line(32f, 12f, 30f, 6f, dark, 2.4f); u.line(68f, 12f, 70f, 6f, dark, 2.4f)
        }
    }
    // body and belly
    u.oval(50f, 88f, 24f, 22f, fur)
    when (species) {
        "penguin" -> u.oval(50f, 92f, 15f, 18f, Color.White)
        "badger" -> u.oval(50f, 92f, 13f, 15f, shade(fur, 0.35f))
        "sheep" -> {}
        else -> u.oval(50f, 93f, 14f, 14f, belly)
    }
    // feet (boots draw over them)
    val foot = when (species) { "penguin", "duck" -> Orange; else -> dark }
    u.oval(38f, 109f, 9f, 4.2f, foot); u.oval(62f, 109f, 9f, 4.2f, foot)
    // head
    when (species) {
        "frog" -> {
            u.oval(50f, 46f, 26f, 19f, fur)
            u.circle(36f, 26f, 10f, fur); u.circle(64f, 26f, 10f, fur)
            u.circle(36f, 26f, 7f, Color.White); u.circle(64f, 26f, 7f, Color.White)
            u.circle(37f, 27f, 3.6f, Ink); u.circle(63f, 27f, 3.6f, Ink)
            u.arc(50f, 48f, 13f, 20f, 140f, Ink, 1.8f)
            u.circle(46f, 42f, 1.1f, dark); u.circle(54f, 42f, 1.1f, dark)
        }
        "sheep" -> {
            u.oval(50f, 46f, 15f, 17f, Color(0xFF4A3B38))
            u.circle(44f, 42f, 3f, Color.White); u.circle(56f, 42f, 3f, Color.White)
            u.circle(44f, 42.5f, 1.5f, Ink); u.circle(56f, 42.5f, 1.5f, Ink)
            u.oval(50f, 53f, 4f, 2.6f, Pink)
        }
        else -> {
            u.oval(50f, 42f, 24f, 22f, fur)
            when (species) {
                "badger" -> {
                    u.oval(50f, 44f, 9f, 21f, Color.White)
                    u.oval(37f, 42f, 7f, 10f, shade(fur, -0.2f)); u.oval(63f, 42f, 7f, 10f, shade(fur, -0.2f))
                }
                "penguin" -> { u.circle(41f, 44f, 9f, Color.White); u.circle(59f, 44f, 9f, Color.White) }
                "otter" -> u.oval(50f, 52f, 13f, 9f, belly)
                "goat" -> u.oval(50f, 52f, 11f, 8f, belly)
                "squirrel" -> u.oval(50f, 51f, 10f, 7f, belly)
            }
            val eyeColor = Ink
            u.circle(42f, 38f, 3.2f, eyeColor); u.circle(58f, 38f, 3.2f, eyeColor)
            u.circle(43f, 37f, 1f, Color.White); u.circle(59f, 37f, 1f, Color.White)
            when (species) {
                "mouse" -> {
                    u.circle(50f, 48f, 3.2f, Pink)
                    u.line(40f, 49f, 24f, 45f, Ink, 1f); u.line(40f, 51f, 24f, 52f, Ink, 1f)
                    u.line(60f, 49f, 76f, 45f, Ink, 1f); u.line(60f, 51f, 76f, 52f, Ink, 1f)
                }
                "otter" -> { u.oval(50f, 47f, 4f, 3f, Ink); u.circle(44f, 53f, 1f, Ink); u.circle(56f, 53f, 1f, Ink) }
                "pig" -> {
                    u.oval(50f, 50f, 10f, 7f, shade(fur, -0.18f))
                    u.circle(46.5f, 50f, 1.6f, Ink); u.circle(53.5f, 50f, 1.6f, Ink)
                }
                "cat" -> {
                    u.poly(Pink, 47f, 46f, 53f, 46f, 50f, 50f)
                    u.line(40f, 50f, 24f, 46f, Ink, 1f); u.line(40f, 52f, 24f, 53f, Ink, 1f)
                    u.line(60f, 50f, 76f, 46f, Ink, 1f); u.line(60f, 52f, 76f, 53f, Ink, 1f)
                }
                "badger" -> u.oval(50f, 52f, 4f, 3f, Ink)
                "rabbit" -> {
                    u.poly(Pink, 47f, 47f, 53f, 47f, 50f, 51f)
                    u.line(40f, 51f, 26f, 49f, Ink, 1f); u.line(60f, 51f, 74f, 49f, Ink, 1f)
                }
                "penguin" -> u.poly(Orange, 44f, 48f, 56f, 48f, 50f, 58f)
                "goat" -> {
                    u.circle(46f, 52f, 1.2f, Ink); u.circle(54f, 52f, 1.2f, Ink)
                    u.poly(belly, 45f, 60f, 55f, 60f, 50f, 74f)
                }
                "duck" -> {
                    u.oval(50f, 50f, 14f, 6f, Orange)
                    u.line(37f, 50f, 63f, 50f, shade(Orange, -0.3f), 1.2f)
                    u.curve(45f, 20f, 50f, 8f, 56f, 18f, fur, 3f)
                }
                "squirrel" -> { u.oval(50f, 48f, 3.6f, 2.8f, Ink); u.circle(44f, 52f, 1f, dark); u.circle(56f, 52f, 1f, dark) }
            }
        }
    }
}

private fun hatStyle(species: String): Color = when (species) {
    "badger" -> Color(0xFF2B2B33)
    "rabbit" -> Color(0xFFE6C36A)
    "penguin" -> Color(0xFFD64545)
    "goat" -> Color(0xFF6B7A8F)
    "duck" -> Color(0xFFE88FB0)
    "squirrel" -> Color(0xFF3C8DAD)
    else -> Color(0xFF7A5CC2)
}

private fun drawAccessory(u: U, species: String, trait: PieTrait) {
    when (trait) {
        PieTrait.HAT -> {
            val c = hatStyle(species)
            when (species) {
                "badger" -> { // top hat: tall crown, flat brim
                    u.rrect(40f, -2f, 20f, 20f, 2f, c)
                    u.rrect(32f, 16f, 36f, 5f, 2.5f, c)
                    u.rect(40f, 12f, 20f, 4f, Color(0xFFD64545))
                }
                "rabbit" -> { // sun hat: wide brim, round crown, ribbon
                    u.oval(50f, 21f, 29f, 5.5f, c)
                    u.oval(50f, 15f, 15f, 10f, c)
                    u.rect(36f, 17f, 28f, 3.5f, Color(0xFFD64545))
                }
                "penguin" -> { // bobble hat: dome, folded band, pompom
                    u.oval(50f, 22f, 22f, 15f, c)
                    u.rrect(28f, 22f, 44f, 7f, 3.5f, Color.White)
                    u.circle(50f, 5f, 5.5f, Color.White)
                }
                "goat" -> { // flat cap: low wide dome with a small peak
                    u.oval(48f, 20f, 23f, 8f, c)
                    u.oval(66f, 25f, 12f, 3.2f, shade(c, -0.25f))
                }
                "duck" -> { // bonnet: dome with ribbon ties
                    u.oval(50f, 22f, 22f, 13f, c)
                    u.line(30f, 28f, 28f, 56f, Color(0xFFD64545), 2.4f)
                    u.line(70f, 28f, 72f, 56f, Color(0xFFD64545), 2.4f)
                }
                "squirrel" -> { // baseball cap: dome, button, peak
                    u.oval(50f, 22f, 22f, 12f, c)
                    u.circle(50f, 11f, 2.6f, shade(c, -0.3f))
                    u.rrect(46f, 24f, 34f, 4.5f, 2.2f, shade(c, -0.25f))
                }
                else -> u.oval(50f, 20f, 22f, 10f, c)
            }
        }
        PieTrait.GLASSES -> {
            val frame = Color(0xFF2B2B33)
            when (species) {
                "sheep" -> { // square frames
                    u.rrect(32f, 32f, 15f, 13f, 2.2f, frame, stroke = 2.2f)
                    u.rrect(53f, 32f, 15f, 13f, 2.2f, frame, stroke = 2.2f)
                    u.line(47f, 37f, 53f, 37f, frame, 2f)
                }
                "cat" -> { // cat-eye frames with upswept corners
                    u.oval(41f, 39f, 8.5f, 6.5f, frame, stroke = 2.2f)
                    u.oval(59f, 39f, 8.5f, 6.5f, frame, stroke = 2.2f)
                    u.line(33f, 36f, 29f, 31f, frame, 2.4f); u.line(67f, 36f, 71f, 31f, frame, 2.4f)
                    u.line(49f, 39f, 51f, 39f, frame, 2f)
                }
                "squirrel" -> { // goggles: thick rings and a strap
                    u.line(18f, 40f, 82f, 40f, Color(0xFFF08C2E), 3.4f)
                    u.circle(41f, 40f, 9f, frame, stroke = 3.4f); u.circle(59f, 40f, 9f, frame, stroke = 3.4f)
                    u.circle(41f, 40f, 6f, Color(0x6690C8F0)); u.circle(59f, 40f, 6f, Color(0x6690C8F0))
                }
                else -> { // round spectacles
                    val r = if (species == "duck") 9.5f else 8.5f
                    u.circle(41f, 39f, r, frame, stroke = 2.2f)
                    u.circle(59f, 39f, r, frame, stroke = 2.2f)
                    u.line(41f + r, 39f, 59f - r, 39f, frame, 2f)
                }
            }
        }
        PieTrait.SCARF -> {
            val a = when (species) { "frog" -> Color(0xFFD64545); "otter" -> Color(0xFF3C8DAD); "cat" -> Color(0xFF7A5CC2); "penguin" -> Color(0xFF3C9D5B); else -> Color(0xFFE88A2E) }
            val b = Color(0xFFFFF2D0)
            u.rrect(28f, 56f, 44f, 10f, 5f, a)
            if (species == "frog" || species == "goat") {
                for (i in 0 until 4) u.rrect(33f + i * 10f, 56f, 4.5f, 10f, 1.5f, b)
            }
            if (species == "otter") {
                for (i in 0 until 5) u.circle(34f + i * 8f, 61f, 1.5f, b)
            }
            // the hanging end, longer for the cat
            val len = if (species == "cat") 34f else 22f
            u.rrect(58f, 62f, 11f, len, 3f, a)
            if (species == "frog" || species == "goat") u.rect(58f, 62f + len * 0.45f, 11f, 4f, b)
            u.line(60f, 62f + len, 68f, 62f + len, shade(a, -0.3f), 1.6f)
        }
        PieTrait.BAG -> {
            when (species) {
                "mouse" -> { // satchel with a strap
                    u.line(34f, 66f, 66f, 86f, Color(0xFF8A5A2B), 2.4f)
                    u.rrect(58f, 80f, 20f, 16f, 3f, Color(0xFFB97B3F))
                    u.rect(58f, 80f, 20f, 5f, Color(0xFF8A5A2B))
                    u.circle(68f, 86f, 1.6f, Cream)
                }
                "otter" -> { // woven basket with a handle
                    u.arc(68f, 84f, 10f, 180f, 180f, Color(0xFF8A5A2B), 2.2f)
                    u.poly(Color(0xFFD9A55A), 56f, 84f, 80f, 84f, 76f, 100f, 60f, 100f)
                    u.line(58f, 90f, 78f, 90f, Color(0xFF8A5A2B), 1.2f); u.line(59f, 95f, 77f, 95f, Color(0xFF8A5A2B), 1.2f)
                }
                "sheep" -> { // shopping bag with two handles
                    u.arc(66f, 80f, 6f, 180f, 180f, Color(0xFF2B6B3F), 2f)
                    u.rrect(56f, 82f, 22f, 20f, 2.5f, Color(0xFF3C9D5B))
                    u.rect(56f, 82f, 22f, 4f, Color(0xFF2B6B3F))
                }
                "rabbit" -> { // carrot bag: a carrot with leaves, on a strap
                    u.line(36f, 66f, 62f, 82f, Color(0xFF8A5A2B), 2f)
                    u.poly(Orange, 56f, 82f, 80f, 86f, 66f, 108f)
                    u.line(58f, 83f, 56f, 76f, Color(0xFF3C9D5B), 2.2f); u.line(62f, 83f, 64f, 75f, Color(0xFF3C9D5B), 2.2f)
                    u.line(63f, 90f, 71f, 91f, shade(Orange, -0.3f), 1.2f)
                }
                "goat" -> { // backpack: bulge on the side with two straps
                    u.rrect(20f, 74f, 16f, 26f, 6f, Color(0xFF3C8DAD))
                    u.line(38f, 70f, 38f, 96f, Color(0xFF28677F), 2.4f)
                    u.line(44f, 70f, 44f, 96f, Color(0xFF28677F), 2.4f)
                    u.rrect(22f, 82f, 12f, 9f, 2f, Color(0xFF28677F))
                }
                "squirrel" -> { // acorn bag on a strap
                    u.line(36f, 66f, 64f, 84f, Color(0xFF8A5A2B), 2f)
                    u.oval(68f, 94f, 9f, 11f, Color(0xFFC98B3D))
                    u.oval(68f, 86f, 10f, 5f, Color(0xFF6E4A22))
                    u.line(68f, 81f, 68f, 77f, Color(0xFF6E4A22), 2f)
                }
                else -> {
                    u.rrect(58f, 80f, 20f, 18f, 3f, Color(0xFFB97B3F))
                }
            }
        }
        PieTrait.BOOTS -> {
            val c = when (species) { "mouse" -> Color(0xFFD64545); "otter" -> Color(0xFFF2C14E); "sheep" -> Color(0xFF3C9D5B); "badger" -> Color(0xFF2B2B33); "penguin" -> Color(0xFF3C8DAD); "duck" -> Color(0xFF7A5CC2); else -> Color(0xFFD64545) }
            for (x in listOf(30f, 55f)) {
                u.rrect(x + 2f, 96f, 13f, 14f, 3f, c)
                u.rrect(x - 1f, 106f, 20f, 7f, 3.5f, c)
                u.rect(x + 2f, 96f, 13f, 3.2f, shade(c, 0.35f))
                u.rect(x - 1f, 111f, 20f, 2.2f, shade(c, -0.4f))
            }
        }
        PieTrait.BOW -> {
            val c = when (species) { "frog" -> Color(0xFFD64545); "otter" -> Color(0xFFE88A2E); "cat" -> Color(0xFF2B6B9E); "badger" -> Color(0xFFD64545); "goat" -> Color(0xFF7A5CC2); else -> Color(0xFFD64545) }
            u.poly(c, 50f, 71f, 35f, 63f, 35f, 79f)
            u.poly(c, 50f, 71f, 65f, 63f, 65f, 79f)
            u.circle(50f, 71f, 4.2f, shade(c, -0.3f))
            if (species == "frog") { u.circle(41f, 71f, 1.6f, Color.White); u.circle(59f, 71f, 1.6f, Color.White) }
        }
    }
}

private fun U.rect(x: Float, y: Float, w: Float, h: Float, c: Color) {
    d.drawRect(c, o(x, y), Size(w * k, h * k))
}

/** A neutral icon of [trait] in a unit square of side [sizePx] at [topLeft]: the picture on the trait buttons and the notebook. */
internal fun DrawScope.drawPieTraitIcon(trait: PieTrait, topLeft: Offset, sizePx: Float, ink: Color, fill: Color) {
    val k = sizePx / 100f
    val u = U(this, topLeft.x, topLeft.y, k)
    val sw = 5f
    when (trait) {
        PieTrait.HAT -> {
            u.rrect(30f, 14f, 40f, 46f, 4f, fill); u.rrect(30f, 14f, 40f, 46f, 4f, ink, sw)
            u.rrect(12f, 58f, 76f, 14f, 6f, fill); u.rrect(12f, 58f, 76f, 14f, 6f, ink, sw)
            u.line(30f, 46f, 70f, 46f, ink, sw)
        }
        PieTrait.GLASSES -> {
            u.circle(30f, 52f, 20f, fill); u.circle(30f, 52f, 20f, ink, sw)
            u.circle(70f, 52f, 20f, fill); u.circle(70f, 52f, 20f, ink, sw)
            u.line(50f, 50f, 50f, 50f, ink, sw)
            u.line(48f, 46f, 52f, 46f, ink, sw)
            u.line(10f, 46f, 2f, 38f, ink, sw); u.line(90f, 46f, 98f, 38f, ink, sw)
        }
        PieTrait.SCARF -> {
            u.rrect(10f, 26f, 80f, 24f, 12f, fill); u.rrect(10f, 26f, 80f, 24f, 12f, ink, sw)
            u.rrect(58f, 46f, 22f, 44f, 6f, fill); u.rrect(58f, 46f, 22f, 44f, 6f, ink, sw)
            u.line(58f, 68f, 80f, 68f, ink, sw)
        }
        PieTrait.BAG -> {
            u.arc(50f, 36f, 22f, 180f, 180f, ink, sw)
            u.rrect(16f, 38f, 68f, 54f, 10f, fill); u.rrect(16f, 38f, 68f, 54f, 10f, ink, sw)
            u.line(16f, 54f, 84f, 54f, ink, sw)
        }
        PieTrait.BOOTS -> {
            u.poly(fill, 28f, 10f, 56f, 10f, 56f, 56f, 92f, 70f, 92f, 90f, 28f, 90f)
            u.poly(ink, 28f, 10f, 56f, 10f, 56f, 56f, 92f, 70f, 92f, 90f, 28f, 90f, stroke = sw)
            u.line(28f, 24f, 56f, 24f, ink, sw)
        }
        PieTrait.BOW -> {
            u.poly(fill, 50f, 50f, 8f, 24f, 8f, 76f)
            u.poly(fill, 50f, 50f, 92f, 24f, 92f, 76f)
            u.poly(ink, 50f, 50f, 8f, 24f, 8f, 76f, stroke = sw)
            u.poly(ink, 50f, 50f, 92f, 24f, 92f, 76f, stroke = sw)
            u.circle(50f, 50f, 9f, ink)
        }
    }
}

/** The Yes stamp (circle with a tick) or the No stamp (rounded square with a cross) in a box of side [sizePx] at [topLeft]. */
internal fun DrawScope.drawPieStamp(yes: Boolean, topLeft: Offset, sizePx: Float, fill: Color, mark: Color) {
    val k = sizePx / 100f
    val u = U(this, topLeft.x, topLeft.y, k)
    if (yes) {
        u.circle(50f, 50f, 46f, fill)
        val p = Path()
        p.moveTo(topLeft.x + 28f * k, topLeft.y + 52f * k)
        p.lineTo(topLeft.x + 44f * k, topLeft.y + 68f * k)
        p.lineTo(topLeft.x + 74f * k, topLeft.y + 34f * k)
        drawPath(p, mark, style = Stroke(11f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        u.rrect(6f, 6f, 88f, 88f, 18f, fill)
        u.line(30f, 30f, 70f, 70f, mark, 11f)
        u.line(70f, 30f, 30f, 70f, mark, 11f)
    }
}

/** The magic lens: a thick ring around a glass disc with a handle, centred at [center] with outer radius [r]. */
internal fun DrawScope.drawPieLens(center: Offset, r: Float, ring: Color, glass: Color) {
    drawLine(ring, Offset(center.x + r * 0.7f, center.y + r * 0.7f), Offset(center.x + r * 1.25f, center.y + r * 1.25f), r * 0.32f, StrokeCap.Round)
    drawCircle(glass, r, center)
    drawCircle(ring, r, center, style = Stroke(r * 0.14f))
    drawCircle(Color.White.copy(alpha = 0.55f), r * 0.14f, Offset(center.x - r * 0.5f, center.y - r * 0.5f))
}

/** The back of a card: also what a cleared animal shows. A pie with a lattice top on a berry-coloured card. */
internal fun DrawScope.drawPieCardBack(topLeft: Offset, size: Size, back: Color, ink: Color) {
    drawRoundRect(back, topLeft, size, CornerRadius(size.minDimension * 0.12f))
    val c = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
    val r = size.minDimension * 0.3f
    drawCircle(Color(0xFFF2C14E), r, c)
    drawCircle(ink.copy(alpha = 0.55f), r, c, style = Stroke(r * 0.12f))
    for (i in -1..1) {
        drawLine(ink.copy(alpha = 0.5f), Offset(c.x + i * r * 0.5f, c.y - r * 0.8f), Offset(c.x + i * r * 0.5f, c.y + r * 0.8f), r * 0.07f)
        drawLine(ink.copy(alpha = 0.5f), Offset(c.x - r * 0.8f, c.y + i * r * 0.5f), Offset(c.x + r * 0.8f, c.y + i * r * 0.5f), r * 0.07f)
    }
}

/** The thief's token on the lane: a round shadow with a mask band and two eyes. [pie] adds the stolen pie. */
internal fun DrawScope.drawPieThief(center: Offset, r: Float, pie: Boolean) {
    drawCircle(Color(0x33000000), r, Offset(center.x + r * 0.1f, center.y + r * 0.2f))
    drawCircle(Color(0xFF2B2B33), r, center)
    drawRoundRect(Color(0xFF5A5A66), Offset(center.x - r * 0.8f, center.y - r * 0.3f), Size(r * 1.6f, r * 0.5f), CornerRadius(r * 0.25f))
    drawCircle(Color.White, r * 0.2f, Offset(center.x - r * 0.34f, center.y - r * 0.05f))
    drawCircle(Color.White, r * 0.2f, Offset(center.x + r * 0.34f, center.y - r * 0.05f))
    drawCircle(Ink, r * 0.09f, Offset(center.x - r * 0.3f, center.y - r * 0.05f))
    drawCircle(Ink, r * 0.09f, Offset(center.x + r * 0.38f, center.y - r * 0.05f))
    if (pie) {
        drawCircle(Color(0xFFF2C14E), r * 0.42f, Offset(center.x + r * 0.7f, center.y + r * 0.62f))
        drawCircle(Ink.copy(alpha = 0.6f), r * 0.42f, Offset(center.x + r * 0.7f, center.y + r * 0.62f), style = Stroke(r * 0.08f))
    }
}

/** A tiny pie (the stolen one) at [center] with radius [r]. */
internal fun DrawScope.drawPie(center: Offset, r: Float) {
    drawCircle(Color(0xFFF2C14E), r, center)
    drawCircle(Ink.copy(alpha = 0.6f), r, center, style = Stroke(r * 0.14f))
    drawLine(Ink.copy(alpha = 0.5f), Offset(center.x - r * 0.5f, center.y), Offset(center.x + r * 0.5f, center.y), r * 0.1f)
    drawLine(Ink.copy(alpha = 0.5f), Offset(center.x, center.y - r * 0.5f), Offset(center.x, center.y + r * 0.5f), r * 0.1f)
}

/** One of the four lane places at [center] (box of side [s]): 0 stall, 1 fountain, 2 bridge, 3 gate. */
internal fun DrawScope.drawPiePlace(place: Int, center: Offset, s: Float, ink: Color, soft: Color) {
    val x0 = center.x - s / 2f
    val y0 = center.y - s / 2f
    val u = U(this, x0, y0, s / 100f)
    when (place) {
        0 -> { // pie stall: striped awning over a counter with a pie
            u.rrect(14f, 52f, 72f, 40f, 4f, soft); u.rrect(14f, 52f, 72f, 40f, 4f, ink, 4f)
            u.poly(Color(0xFFD64545), 8f, 50f, 92f, 50f, 84f, 20f, 16f, 20f)
            for (i in 0 until 4) u.poly(Color.White, 16f + i * 18f + 8f, 20f, 16f + i * 18f + 17f, 20f, 8f + i * 21f + 20f, 50f, 8f + i * 21f + 11f, 50f)
            u.poly(ink, 8f, 50f, 92f, 50f, 84f, 20f, 16f, 20f, stroke = 4f)
            u.circle(50f, 62f, 11f, Color(0xFFF2C14E)); u.circle(50f, 62f, 11f, ink, 3f)
        }
        1 -> { // fountain: basin, column, spray
            u.oval(50f, 80f, 40f, 12f, soft); u.oval(50f, 80f, 40f, 12f, ink, 4f)
            u.rrect(46f, 40f, 8f, 40f, 3f, soft); u.rrect(46f, 40f, 8f, 40f, 3f, ink, 3f)
            u.oval(50f, 40f, 18f, 6f, soft); u.oval(50f, 40f, 18f, 6f, ink, 3f)
            u.curve(50f, 36f, 36f, 6f, 24f, 40f, Color(0xFF3C8DAD), 4f)
            u.curve(50f, 36f, 64f, 6f, 76f, 40f, Color(0xFF3C8DAD), 4f)
            u.curve(50f, 36f, 50f, 8f, 50f, 20f, Color(0xFF3C8DAD), 4f)
        }
        2 -> { // bridge: an arch over water
            u.rrect(6f, 38f, 88f, 12f, 5f, soft); u.rrect(6f, 38f, 88f, 12f, 5f, ink, 4f)
            u.rect(14f, 50f, 72f, 36f, soft)
            u.arc(50f, 86f, 22f, 180f, 180f, ink, 4f)
            u.line(14f, 50f, 14f, 86f, ink, 4f); u.line(86f, 50f, 86f, 86f, ink, 4f)
            u.line(8f, 90f, 92f, 90f, Color(0xFF3C8DAD), 6f)
        }
        else -> { // back gate: two posts and bars
            u.rrect(10f, 20f, 14f, 70f, 3f, soft); u.rrect(10f, 20f, 14f, 70f, 3f, ink, 4f)
            u.rrect(76f, 20f, 14f, 70f, 3f, soft); u.rrect(76f, 20f, 14f, 70f, 3f, ink, 4f)
            for (i in 0 until 4) u.line(32f + i * 12f, 36f, 32f + i * 12f, 88f, ink, 4f)
            u.line(24f, 40f, 76f, 40f, ink, 4f)
            u.poly(ink, 10f, 20f, 24f, 20f, 17f, 8f)
            u.poly(ink, 76f, 20f, 90f, 20f, 83f, 8f)
        }
    }
}

/** A five-point star centred at [center] with outer radius [r]. */
internal fun DrawScope.drawPieStar(center: Offset, r: Float, fill: Color, edge: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val ang = Math.PI / 5.0 * i - Math.PI / 2.0
        val rad = if (i % 2 == 0) r else r * 0.45f
        val x = center.x + (Math.cos(ang) * rad).toFloat()
        val y = center.y + (Math.sin(ang) * rad).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, fill)
    drawPath(p, edge, style = Stroke(r * 0.1f, join = StrokeJoin.Round))
}
