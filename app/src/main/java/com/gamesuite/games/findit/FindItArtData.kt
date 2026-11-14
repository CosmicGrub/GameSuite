package com.gamesuite.games.findit

import com.gamesuite.games.findit.FindItInk.BLUE
import com.gamesuite.games.findit.FindItInk.BROWN
import com.gamesuite.games.findit.FindItInk.CREAM
import com.gamesuite.games.findit.FindItInk.DARK
import com.gamesuite.games.findit.FindItInk.GREEN
import com.gamesuite.games.findit.FindItInk.INK
import com.gamesuite.games.findit.FindItInk.LIGHT
import com.gamesuite.games.findit.FindItInk.MAIN
import com.gamesuite.games.findit.FindItInk.PINK
import com.gamesuite.games.findit.FindItInk.RED
import com.gamesuite.games.findit.FindItInk.YELLOW
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colour roles a picture is painted with: [MAIN] is the picture's own colour token, the rest are fixed accents. */
enum class FindItInk { MAIN, LIGHT, DARK, CREAM, INK, GREEN, BROWN, YELLOW, RED, BLUE, PINK }

/** How one primitive is drawn. FILL: filled with an outline. FLAT: filled, no outline. LINE: a round-capped stroke. GROUP: several paths outlined first, then filled (no seams). */
enum class FindItPrimKind { FILL, FLAT, LINE, GROUP }

/**
 * One drawing primitive. [d] is path data in the SVG subset M, L, C, Q, Z (absolute, space separated) on the unit square
 * -1..1 (y down), so the same data feeds the Compose canvas and an SVG preview. A GROUP joins its paths with '|'.
 */
data class FindItPrim(val kind: FindItPrimKind, val d: String, val ink: FindItInk, val width: Float = 0f)

/**
 * The original vector art of the 57 pictures as pure data (no Compose): each picture is a list of primitives built from
 * the recipe in [FindItSymbol.recipe]. Keeping it as data lets the tests check every picture (bounds, distinctness,
 * enough detail) and export an SVG contact sheet for a visual check; the screen only turns the paths into `Path`s.
 */
object FindItArtData {
    private class Builder {
        val prims = ArrayList<FindItPrim>()
        fun f(d: String, ink: FindItInk) { prims += FindItPrim(FindItPrimKind.FILL, d, ink) }
        fun n(d: String, ink: FindItInk) { prims += FindItPrim(FindItPrimKind.FLAT, d, ink) }
        fun l(d: String, ink: FindItInk, w: Float = 0.07f) { prims += FindItPrim(FindItPrimKind.LINE, d, ink, w) }
        fun g(ds: List<String>, ink: FindItInk) { prims += FindItPrim(FindItPrimKind.GROUP, ds.joinToString("|"), ink) }
    }

    private fun v(x: Float) = String.format(Locale.ROOT, "%.3f", x)
    private fun pt(x: Float, y: Float) = "${v(x)} ${v(y)}"

    /** Ellipse centred ([cx], [cy]) with radii [rx], [ry], turned [deg] degrees. */
    private fun ov(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float = 0f): String {
        val a = deg * PI.toFloat() / 180f
        val ca = cos(a)
        val sa = sin(a)
        fun p(x: Float, y: Float) = pt(cx + x * ca - y * sa, cy + x * sa + y * ca)
        val k = 0.5523f
        return "M ${p(rx, 0f)} C ${p(rx, k * ry)} ${p(k * rx, ry)} ${p(0f, ry)} C ${p(-k * rx, ry)} ${p(-rx, k * ry)} ${p(-rx, 0f)} " +
            "C ${p(-rx, -k * ry)} ${p(-k * rx, -ry)} ${p(0f, -ry)} C ${p(k * rx, -ry)} ${p(rx, -k * ry)} ${p(rx, 0f)} Z"
    }

    private fun ci(cx: Float, cy: Float, r: Float) = ov(cx, cy, r, r)

    /** Closed polygon from x, y pairs. */
    private fun pg(vararg c: Float): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < c.size) {
            sb.append(if (i == 0) "M " else " L ").append(pt(c[i], c[i + 1]))
            i += 2
        }
        return sb.append(" Z").toString()
    }

    /** Open polyline from x, y pairs. */
    private fun pl(vararg c: Float): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < c.size) {
            sb.append(if (i == 0) "M " else " L ").append(pt(c[i], c[i + 1]))
            i += 2
        }
        return sb.toString()
    }

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M ${pt(x + r, y)} L ${pt(x + w - r, y)} Q ${pt(x + w, y)} ${pt(x + w, y + r)} L ${pt(x + w, y + h - r)} " +
            "Q ${pt(x + w, y + h)} ${pt(x + w - r, y + h)} L ${pt(x + r, y + h)} Q ${pt(x, y + h)} ${pt(x, y + h - r)} " +
            "L ${pt(x, y + r)} Q ${pt(x, y)} ${pt(x + r, y)} Z"

    /** A cubic curve [x0 y0] then (c1, c2, end) triples, open. */
    private fun cv(x0: Float, y0: Float, vararg c: Float): String {
        val sb = StringBuilder("M ").append(pt(x0, y0))
        var i = 0
        while (i + 5 < c.size) {
            sb.append(" C ").append(pt(c[i], c[i + 1])).append(' ').append(pt(c[i + 2], c[i + 3])).append(' ').append(pt(c[i + 4], c[i + 5]))
            i += 6
        }
        return sb.toString()
    }

    /** A rectangle of half length [len] from ([cx], [cy]) along [deg], [w] wide, as a polygon. */
    private fun bar(cx: Float, cy: Float, len: Float, w: Float, deg: Float): String {
        val a = deg * PI.toFloat() / 180f
        val dx = cos(a)
        val dy = sin(a)
        val nx = -dy * w / 2
        val ny = dx * w / 2
        return pg(cx + nx, cy + ny, cx + dx * len + nx, cy + dy * len + ny, cx + dx * len - nx, cy + dy * len - ny, cx - nx, cy - ny)
    }

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int): String {
        val c = FloatArray(points * 4)
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / points
            c[i * 2] = cx + cos(a) * r
            c[i * 2 + 1] = cy + sin(a) * r
        }
        return pg(*c)
    }

    private fun arc(r: Float, y: Float) = "M ${pt(-r, y)} C ${pt(-r, y - 1.333f * r)} ${pt(r, y - 1.333f * r)} ${pt(r, y)}"

    private fun draw(i: Int, b: Builder) {
        when (i) {
            0 -> { // sunflower
                b.l(pl(0.05f, 0.4f, 0.1f, 0.95f), GREEN, 0.1f)
                b.f(ov(0.4f, 0.7f, 0.22f, 0.1f, -30f), GREEN)
                for (k in 0 until 10) {
                    val a = k * 2 * PI.toFloat() / 10
                    b.f(ov(cos(a) * 0.52f, sin(a) * 0.52f - 0.1f, 0.24f, 0.11f, k * 36f), MAIN)
                }
                b.f(ci(0f, -0.1f, 0.3f), BROWN)
                b.n(ci(-0.08f, -0.18f, 0.06f), LIGHT)
            }
            1 -> { // butterfly
                b.f(ov(-0.4f, -0.25f, 0.4f, 0.32f, -25f), MAIN)
                b.f(ov(0.4f, -0.25f, 0.4f, 0.32f, 25f), MAIN)
                b.f(ov(-0.3f, 0.35f, 0.28f, 0.24f, 20f), LIGHT)
                b.f(ov(0.3f, 0.35f, 0.28f, 0.24f, -20f), LIGHT)
                b.n(ci(-0.45f, -0.28f, 0.1f), CREAM); b.n(ci(0.45f, -0.28f, 0.1f), CREAM)
                b.l(pl(0f, -0.35f, -0.22f, -0.8f), INK, 0.05f); b.l(pl(0f, -0.35f, 0.22f, -0.8f), INK, 0.05f)
                b.f(ov(0f, 0.05f, 0.08f, 0.45f), INK)
            }
            2 -> { // goldfish
                b.f(pg(0.35f, 0f, 0.9f, -0.45f, 0.75f, 0f, 0.9f, 0.45f), LIGHT)
                b.f(ov(-0.1f, 0f, 0.55f, 0.4f), MAIN)
                b.f(ov(-0.05f, 0.3f, 0.22f, 0.12f, 25f), DARK)
                b.l(cv(-0.28f, -0.2f, -0.4f, -0.1f, -0.4f, 0.1f, -0.28f, 0.2f), DARK, 0.04f)
                b.f(ci(-0.4f, -0.1f, 0.1f), CREAM); b.n(ci(-0.42f, -0.1f, 0.045f), INK)
            }
            3 -> { // mushroom
                b.f(rr(-0.22f, 0.0f, 0.44f, 0.65f, 0.14f), CREAM)
                b.f("M -0.85 0.1 C -0.85 -0.8 0.85 -0.8 0.85 0.1 Z", MAIN)
                b.n(ci(-0.4f, -0.3f, 0.12f), CREAM); b.n(ci(0.05f, -0.5f, 0.1f), CREAM); b.n(ci(0.45f, -0.18f, 0.11f), CREAM)
                b.l(pl(-0.85f, 0.1f, 0.85f, 0.1f), DARK, 0.05f)
            }
            4 -> { // umbrella
                b.l("M 0.000 0.050 L 0.000 0.600 C 0.000 0.900 -0.300 0.900 -0.300 0.650", INK, 0.07f)
                b.f("M -0.85 0.05 C -0.85 -0.8 0.85 -0.8 0.85 0.05 Q 0.57 -0.12 0.28 0.05 Q 0 -0.12 -0.28 0.05 Q -0.57 -0.12 -0.85 0.05 Z", MAIN)
                b.l(pl(0f, -0.62f, -0.3f, 0.0f), DARK, 0.035f); b.l(pl(0f, -0.62f, 0.3f, 0.0f), DARK, 0.035f)
                b.l(pl(0f, -0.62f, 0f, 0.0f), DARK, 0.035f)
            }
            5 -> { // kite
                b.l(cv(0f, 0.45f, 0.25f, 0.55f, -0.25f, 0.7f, 0f, 0.8f), INK, 0.04f)
                b.f(pg(-0.1f, 0.58f, 0.0f, 0.5f, 0.1f, 0.58f, 0.0f, 0.66f), YELLOW)
                b.f(pg(-0.1f, 0.78f, 0f, 0.72f, 0.1f, 0.78f, 0f, 0.86f), PINK)
                b.f(pg(0f, -0.88f, 0.52f, -0.1f, 0f, 0.45f, -0.52f, -0.1f), MAIN)
                b.l(pl(0f, -0.88f, 0f, 0.45f), DARK, 0.04f); b.l(pl(-0.52f, -0.1f, 0.52f, -0.1f), DARK, 0.04f)
            }
            6 -> { // teapot
                b.l(cv(-0.5f, -0.05f, -0.98f, -0.1f, -0.98f, 0.55f, -0.5f, 0.42f), DARK, 0.11f)
                b.f("M 0.45 0.05 C 0.75 -0.05 0.8 -0.3 0.92 -0.42 L 0.99 -0.32 C 0.92 -0.1 0.82 0.12 0.5 0.3 Z", MAIN)
                b.f(ov(0f, 0.15f, 0.56f, 0.42f), MAIN)
                b.f(ov(0f, -0.25f, 0.32f, 0.1f), LIGHT)
                b.f(ci(0f, -0.4f, 0.08f), DARK)
                b.n(ov(-0.2f, 0.05f, 0.1f, 0.18f, 20f), LIGHT)
            }
            7 -> { // cupcake
                b.f(pg(-0.55f, 0.1f, 0.55f, 0.1f, 0.4f, 0.82f, -0.4f, 0.82f), MAIN)
                b.l(pl(-0.25f, 0.15f, -0.2f, 0.78f), DARK, 0.04f); b.l(pl(0f, 0.15f, 0f, 0.78f), DARK, 0.04f); b.l(pl(0.25f, 0.15f, 0.2f, 0.78f), DARK, 0.04f)
                b.f(ov(0f, 0.02f, 0.6f, 0.2f), CREAM)
                b.f(ov(0f, -0.25f, 0.42f, 0.18f), PINK)
                b.f(ov(0f, -0.48f, 0.26f, 0.14f), CREAM)
                b.f(ci(0f, -0.72f, 0.11f), RED)
            }
            8 -> { // sailboat
                b.f(pg(0f, -0.88f, 0f, 0.22f, 0.65f, 0.22f), CREAM)
                b.f(pg(-0.06f, -0.5f, -0.06f, 0.22f, -0.58f, 0.22f), MAIN)
                b.f("M -0.85 0.3 L 0.85 0.3 L 0.55 0.75 L -0.55 0.75 Z", DARK)
                b.l(pl(0f, -0.92f, 0f, 0.3f), INK, 0.05f)
                b.l(cv(-0.85f, 0.88f, -0.6f, 0.78f, -0.4f, 0.98f, -0.15f, 0.88f, 0.1f, 0.78f, 0.3f, 0.98f, 0.55f, 0.88f), MAIN, 0.05f)
            }
            9 -> { // rocket
                b.f(pg(-0.12f, 0.58f, 0f, 0.95f, 0.12f, 0.58f), YELLOW)
                b.f(pg(-0.27f, 0.1f, -0.64f, 0.62f, -0.27f, 0.5f), DARK)
                b.f(pg(0.27f, 0.1f, 0.64f, 0.62f, 0.27f, 0.5f), DARK)
                b.f(ov(0f, 0.0f, 0.28f, 0.62f), MAIN)
                b.f("M -0.28 -0.3 C -0.28 -0.7 0 -0.95 0 -0.95 C 0 -0.95 0.28 -0.7 0.28 -0.3 Z", RED)
                b.f(ci(0f, -0.02f, 0.15f), CREAM); b.n(ci(0f, -0.02f, 0.1f), BLUE)
            }
            10 -> { // acorn
                b.l(pl(0f, -0.6f, 0.08f, -0.88f), DARK, 0.09f)
                b.f(ov(0f, 0.22f, 0.42f, 0.52f), MAIN)
                b.f("M -0.52 -0.05 C -0.52 -0.72 0.52 -0.72 0.52 -0.05 Z", DARK)
                b.l(pl(-0.3f, -0.45f, 0.3f, -0.05f), LIGHT, 0.035f); b.l(pl(0.3f, -0.45f, -0.3f, -0.05f), LIGHT, 0.035f)
                b.n(ov(-0.16f, 0.25f, 0.07f, 0.2f, 10f), LIGHT)
            }
            11 -> { // cherries
                b.l(cv(-0.35f, 0.3f, -0.3f, -0.3f, 0f, -0.6f, 0.2f, -0.85f), GREEN, 0.05f)
                b.l(cv(0.4f, 0.35f, 0.35f, -0.2f, 0.3f, -0.6f, 0.2f, -0.85f), GREEN, 0.05f)
                b.f(ov(0.5f, -0.72f, 0.28f, 0.1f, -20f), GREEN)
                b.f(ci(-0.35f, 0.5f, 0.3f), MAIN); b.f(ci(0.4f, 0.55f, 0.3f), MAIN)
                b.n(ci(-0.45f, 0.4f, 0.07f), CREAM); b.n(ci(0.3f, 0.45f, 0.07f), CREAM)
            }
            12 -> { // bee
                b.f(ov(-0.15f, -0.5f, 0.2f, 0.32f, -30f), CREAM)
                b.f(ov(0.25f, -0.55f, 0.2f, 0.32f, 25f), CREAM)
                b.f(pg(-0.5f, 0.1f, -0.9f, 0.2f, -0.5f, 0.25f), INK)
                b.f(ov(0f, 0.12f, 0.58f, 0.42f), MAIN)
                b.n(ov(-0.2f, 0.12f, 0.07f, 0.38f), INK); b.n(ov(0.05f, 0.12f, 0.07f, 0.41f), INK); b.n(ov(0.3f, 0.12f, 0.06f, 0.33f), INK)
                b.f(ci(0.42f, 0.0f, 0.1f), CREAM); b.n(ci(0.44f, 0.0f, 0.045f), INK)
            }
            13 -> { // hot-air balloon
                b.l(pl(-0.3f, 0.3f, -0.2f, 0.58f), INK, 0.03f); b.l(pl(0.3f, 0.3f, 0.2f, 0.58f), INK, 0.03f)
                b.f(rr(-0.22f, 0.55f, 0.44f, 0.32f, 0.06f), BROWN)
                b.f("M 0 0.45 C -0.95 0.1 -0.9 -0.92 0 -0.92 C 0.9 -0.92 0.95 0.1 0 0.45 Z", MAIN)
                b.n(ov(0f, -0.22f, 0.3f, 0.64f), LIGHT)
                b.l(pl(0f, -0.9f, 0f, 0.44f), DARK, 0.035f)
            }
            14 -> { // ice-cream cone
                b.f(pg(-0.42f, 0f, 0.42f, 0f, 0f, 0.92f), BROWN)
                b.l(pl(-0.28f, 0.1f, 0.14f, 0.5f), DARK, 0.03f); b.l(pl(0.28f, 0.1f, -0.14f, 0.5f), DARK, 0.03f)
                b.f(ci(0f, -0.15f, 0.44f), MAIN)
                b.f(ci(0f, -0.55f, 0.3f), PINK)
                b.f(ci(0f, -0.86f, 0.07f), RED)
            }
            15 -> { // windmill
                b.f("M -0.3 0.92 L -0.18 -0.1 L 0.18 -0.1 L 0.3 0.92 Z", MAIN)
                b.f(pg(-0.24f, -0.1f, 0f, -0.34f, 0.24f, -0.1f), DARK)
                b.f("M -0.1 0.92 L -0.1 0.65 C -0.1 0.5 0.1 0.5 0.1 0.65 L 0.1 0.92 Z", INK)
                for (k in 0 until 4) b.f(bar(0f, -0.22f, 0.7f, 0.2f, 45f + 90f * k), CREAM)
                b.f(ci(0f, -0.22f, 0.08f), DARK)
            }
            16 -> { // crab
                for (s in listOf(-1f, 1f)) {
                    b.l(pl(s * 0.4f, 0.1f, s * 0.8f, 0.3f, s * 0.9f, 0.55f), DARK, 0.05f)
                    b.l(pl(s * 0.35f, 0.25f, s * 0.7f, 0.5f, s * 0.75f, 0.75f), DARK, 0.05f)
                    b.l(pl(s * 0.3f, 0.35f, s * 0.55f, 0.65f, s * 0.55f, 0.9f), DARK, 0.05f)
                    b.l(pl(s * 0.4f, -0.05f, s * 0.6f, -0.35f), MAIN, 0.09f)
                    b.f("M ${pt(s * 0.45f, -0.35f)} C ${pt(s * 0.45f, -0.9f)} ${pt(s * 0.95f, -0.9f)} ${pt(s * 0.9f, -0.5f)} L ${pt(s * 0.75f, -0.45f)} L ${pt(s * 0.62f, -0.6f)} Z", MAIN)
                    b.l(pl(s * 0.15f, -0.1f, s * 0.18f, -0.4f), DARK, 0.04f)
                    b.f(ci(s * 0.18f, -0.43f, 0.08f), CREAM); b.n(ci(s * 0.18f, -0.43f, 0.035f), INK)
                }
                b.f(ov(0f, 0.15f, 0.52f, 0.34f), MAIN)
                b.l(cv(-0.2f, 0.28f, -0.1f, 0.38f, 0.1f, 0.38f, 0.2f, 0.28f), DARK, 0.035f)
            }
            17 -> { // hedgehog
                for (k in 0 until 12) {
                    val a = (190f + k * 14f) * PI.toFloat() / 180f
                    val bx = cos(a) * 0.5f
                    val by = sin(a) * 0.45f + 0.25f
                    val tx = cos(a) * 0.9f
                    val ty = sin(a) * 0.8f + 0.25f
                    val px = -sin(a) * 0.1f
                    val py = cos(a) * 0.1f
                    b.f(pg(bx + px, by + py, tx, ty, bx - px, by - py), DARK)
                }
                b.f(ov(0f, 0.25f, 0.58f, 0.42f), MAIN)
                b.f(ov(0.42f, 0.35f, 0.34f, 0.28f), CREAM)
                b.f(ci(0.72f, 0.38f, 0.075f), INK)
                b.f(ci(0.5f, 0.22f, 0.06f), INK)
                b.f(ov(-0.2f, 0.7f, 0.12f, 0.08f), DARK); b.f(ov(0.25f, 0.72f, 0.12f, 0.08f), DARK)
            }
            18 -> { // rainbow
                b.f(ci(-0.66f, 0.42f, 0.2f), CREAM); b.f(ci(-0.5f, 0.5f, 0.16f), CREAM)
                b.l(arc(0.78f, 0.3f), RED, 0.14f); b.l(arc(0.62f, 0.3f), YELLOW, 0.14f)
                b.l(arc(0.46f, 0.3f), GREEN, 0.14f); b.l(arc(0.3f, 0.3f), BLUE, 0.14f)
                b.f(ci(0.66f, 0.42f, 0.2f), CREAM); b.f(ci(0.5f, 0.5f, 0.16f), CREAM)
            }
            19 -> { // lighthouse
                b.n(pg(0.22f, -0.38f, 0.92f, -0.6f, 0.92f, -0.16f), YELLOW)
                b.n(pg(-0.22f, -0.38f, -0.92f, -0.6f, -0.92f, -0.16f), YELLOW)
                b.f("M -0.32 0.92 L -0.2 -0.22 L 0.2 -0.22 L 0.32 0.92 Z", CREAM)
                b.f(pg(-0.285f, 0.5f, -0.255f, 0.15f, 0.255f, 0.15f, 0.285f, 0.5f), MAIN)
                b.f(pg(-0.235f, -0.05f, -0.215f, -0.22f, 0.215f, -0.22f, 0.235f, -0.05f), MAIN)
                b.f(rr(-0.24f, -0.48f, 0.48f, 0.26f, 0.04f), YELLOW)
                b.f(pg(-0.3f, -0.48f, 0f, -0.75f, 0.3f, -0.48f), MAIN)
            }
            20 -> { // bicycle
                b.l(ci(-0.5f, 0.3f, 0.34f), INK, 0.07f); b.l(ci(0.5f, 0.3f, 0.34f), INK, 0.07f)
                b.l(pl(-0.5f, 0.3f, -0.1f, -0.1f, 0.3f, -0.1f, 0.5f, 0.3f), MAIN, 0.08f)
                b.l(pl(-0.1f, -0.1f, 0f, 0.3f, -0.5f, 0.3f), MAIN, 0.08f)
                b.l(pl(0.3f, -0.1f, 0.4f, -0.42f, 0.62f, -0.42f), DARK, 0.07f)
                b.l(pl(-0.1f, -0.1f, -0.15f, -0.3f), DARK, 0.06f)
                b.f(ov(-0.18f, -0.34f, 0.18f, 0.06f), INK)
                b.n(ci(-0.5f, 0.3f, 0.06f), INK); b.n(ci(0.5f, 0.3f, 0.06f), INK)
            }
            21 -> { // seashell
                b.f(rr(-0.2f, 0.7f, 0.4f, 0.15f, 0.06f), DARK)
                b.f("M 0 0.8 C -0.95 0.5 -0.98 -0.4 -0.55 -0.68 Q -0.27 -0.88 0 -0.7 Q 0.27 -0.88 0.55 -0.68 C 0.98 -0.4 0.95 0.5 0 0.8 Z", MAIN)
                for (k in 0 until 5) {
                    val x = -0.62f + k * 0.31f
                    b.l(pl(0f, 0.75f, x, -0.6f), DARK, 0.04f)
                }
            }
            22 -> { // pear
                b.l(pl(0f, -0.55f, 0.08f, -0.88f), BROWN, 0.08f)
                b.f(ov(0.3f, -0.72f, 0.24f, 0.1f, -25f), DARK)
                b.g(listOf(ci(0f, 0.32f, 0.52f), ov(0f, -0.28f, 0.32f, 0.4f)), MAIN)
                b.n(ov(-0.2f, 0.25f, 0.08f, 0.22f, 15f), LIGHT)
            }
            23 -> { // drum
                b.l(pl(-0.2f, -0.3f, -0.62f, -0.88f), BROWN, 0.07f); b.l(pl(0.2f, -0.3f, 0.62f, -0.88f), BROWN, 0.07f)
                b.f(ci(-0.64f, -0.9f, 0.07f), BROWN); b.f(ci(0.64f, -0.9f, 0.07f), BROWN)
                b.f("M -0.72 -0.2 L -0.72 0.5 C -0.72 0.82 0.72 0.82 0.72 0.5 L 0.72 -0.2 Z", MAIN)
                b.l(pl(-0.72f, -0.05f, -0.36f, 0.62f, 0f, -0.05f, 0.36f, 0.62f, 0.72f, -0.05f), CREAM, 0.05f)
                b.f(ov(0f, -0.2f, 0.72f, 0.24f), CREAM)
            }
            24 -> { // octopus
                for (k in 0 until 8) {
                    val x0 = -0.42f + k * 0.12f
                    val dir = if (k % 2 == 0) -1f else 1f
                    b.l(cv(x0, 0.1f, x0 + dir * 0.05f, 0.4f, x0 - dir * 0.3f, 0.7f, x0 + dir * 0.1f + (k - 3.5f) * 0.07f, 0.88f), MAIN, 0.1f)
                }
                b.f(ov(0f, -0.25f, 0.52f, 0.55f), MAIN)
                b.f(ci(-0.2f, -0.25f, 0.12f), CREAM); b.f(ci(0.2f, -0.25f, 0.12f), CREAM)
                b.n(ci(-0.2f, -0.23f, 0.05f), INK); b.n(ci(0.2f, -0.23f, 0.05f), INK)
                b.l(cv(-0.15f, 0.0f, -0.05f, 0.08f, 0.05f, 0.08f, 0.15f, 0.0f), DARK, 0.04f)
            }
            25 -> { // paper lantern
                b.l(pl(0f, 0.62f, 0f, 0.85f), RED, 0.05f)
                b.f(pg(-0.08f, 0.85f, 0f, 0.7f, 0.08f, 0.85f), RED)
                b.l(cv(-0.1f, -0.9f, -0.2f, -0.8f, 0.2f, -0.8f, 0.1f, -0.9f), INK, 0.04f)
                b.f(ov(0f, 0f, 0.52f, 0.56f), MAIN)
                b.l(ov(0f, 0f, 0.26f, 0.56f), DARK, 0.04f); b.l(pl(0f, -0.56f, 0f, 0.56f), DARK, 0.04f)
                b.f(rr(-0.22f, -0.68f, 0.44f, 0.12f, 0.03f), INK); b.f(rr(-0.22f, 0.54f, 0.44f, 0.12f, 0.03f), INK)
            }
            26 -> { // pinecone
                b.f(pg(-0.1f, -0.5f, 0.1f, -0.5f, 0.06f, -0.82f, -0.06f, -0.82f), BROWN)
                b.f(ov(0f, 0.12f, 0.46f, 0.66f), MAIN)
                for (row in 0 until 6) for (col in 0 until 4) {
                    val y = -0.35f + row * 0.21f
                    val x = -0.3f + col * 0.2f + (if (row % 2 == 0) 0f else 0.1f)
                    val ex = x / 0.42f
                    val ey = (y - 0.12f) / 0.6f
                    if (ex * ex + ey * ey < 0.8f) b.f(ov(x, y, 0.12f, 0.1f), LIGHT)
                }
            }
            27 -> { // mitten
                b.f(ov(0.46f, 0.0f, 0.15f, 0.28f, -30f), MAIN)
                b.f("M -0.42 0.5 L -0.42 -0.3 C -0.42 -0.85 0.32 -0.85 0.32 -0.3 L 0.32 0.5 Z", MAIN)
                b.f(rr(-0.48f, 0.45f, 0.86f, 0.34f, 0.06f), CREAM)
                b.l(pl(-0.3f, 0.45f, -0.3f, 0.79f), MAIN, 0.06f); b.l(pl(-0.05f, 0.45f, -0.05f, 0.79f), MAIN, 0.06f); b.l(pl(0.2f, 0.45f, 0.2f, 0.79f), MAIN, 0.06f)
                b.n(ci(-0.05f, -0.3f, 0.1f), CREAM)
            }
            28 -> { // crown
                b.f("M -0.78 0.5 L -0.78 -0.4 L -0.4 0 L 0 -0.62 L 0.4 0 L 0.78 -0.4 L 0.78 0.5 Z", MAIN)
                b.f(rr(-0.78f, 0.33f, 1.56f, 0.27f, 0.04f), DARK)
                b.f(ci(-0.78f, -0.45f, 0.09f), RED); b.f(ci(0f, -0.68f, 0.09f), RED); b.f(ci(0.78f, -0.45f, 0.09f), RED)
                b.f(ci(-0.4f, 0.465f, 0.07f), BLUE); b.f(ci(0f, 0.465f, 0.07f), RED); b.f(ci(0.4f, 0.465f, 0.07f), BLUE)
            }
            29 -> { // cheese wedge
                b.f(pg(-0.88f, 0.5f, 0.88f, -0.4f, 0.55f, -0.62f, -0.7f, 0.15f), LIGHT)
                b.f(pg(-0.88f, 0.5f, 0.88f, 0.5f, 0.88f, -0.4f), MAIN)
                b.f(ci(0.5f, 0.3f, 0.1f), DARK); b.f(ci(0.68f, 0.05f, 0.07f), DARK); b.f(ci(0.12f, 0.38f, 0.06f), DARK)
                b.n(ci(-0.35f, 0.18f, 0.08f), DARK)
            }
            30 -> { // starfish
                b.f(star(0f, 0.05f, 0.92f, 0.42f, 5), MAIN)
                for (k in 0 until 5) {
                    val a = -PI.toFloat() / 2 + k * 2 * PI.toFloat() / 5
                    b.n(ci(cos(a) * 0.55f, 0.05f + sin(a) * 0.55f, 0.05f), LIGHT)
                    b.n(ci(cos(a) * 0.3f, 0.05f + sin(a) * 0.3f, 0.045f), LIGHT)
                }
            }
            31 -> { // trumpet
                b.f(pg(0.2f, -0.2f, 0.88f, -0.6f, 0.88f, 0.6f, 0.2f, 0.2f), MAIN)
                b.l("M -0.7 -0.05 L 0.3 -0.05", DARK, 0.12f)
                b.l(cv(-0.7f, -0.05f, -0.98f, -0.05f, -0.98f, 0.42f, -0.5f, 0.42f) + " L 0.2 0.42 C 0.32 0.42 0.3 0.1 0.25 0.1", DARK, 0.1f)
                for (x in listOf(-0.4f, -0.15f, 0.1f)) { b.l(pl(x, -0.05f, x, -0.32f), INK, 0.06f); b.f(ci(x, -0.36f, 0.07f), INK) }
            }
            32 -> { // volcano
                b.n(ci(0f, -0.6f, 0.11f), LIGHT); b.n(ci(0.13f, -0.76f, 0.09f), LIGHT); b.n(ci(-0.05f, -0.9f, 0.07f), LIGHT)
                b.f(pg(-0.9f, 0.82f, -0.26f, -0.38f, 0.26f, -0.38f, 0.9f, 0.82f), MAIN)
                b.f("M -0.22 -0.36 L -0.32 0.05 Q -0.26 0.18 -0.2 0.02 L -0.1 0.34 Q 0 0.44 0.05 0.22 L 0.15 -0.05 Q 0.26 0.06 0.26 -0.12 L 0.22 -0.36 Z", RED)
                b.f(ov(0f, -0.38f, 0.26f, 0.07f), DARK)
            }
            33 -> { // paintbrush
                b.l(pl(0.78f, -0.78f, -0.05f, 0.05f), MAIN, 0.15f)
                b.l(pl(-0.05f, 0.05f, -0.28f, 0.28f), DARK, 0.19f)
                b.f(pg(-0.36f, 0.2f, -0.2f, 0.36f, -0.78f, 0.84f), RED)
                b.f(ci(-0.78f, 0.84f, 0.06f), RED)
            }
            34 -> { // rain boot
                b.f("M -0.35 -0.7 L 0.12 -0.7 L 0.12 0.0 C 0.12 0.15 0.78 0.2 0.85 0.5 L 0.85 0.76 L -0.35 0.76 Z", MAIN)
                b.f(rr(-0.45f, 0.66f, 1.4f, 0.22f, 0.06f), INK)
                b.f(rr(-0.42f, -0.82f, 0.6f, 0.16f, 0.05f), LIGHT)
                b.f(pg(-0.35f, -0.55f, -0.55f, -0.45f, -0.35f, -0.35f), DARK)
                b.n(ov(-0.1f, 0.1f, 0.06f, 0.3f), LIGHT)
            }
            35 -> { // parrot
                b.f(pg(-0.3f, 0.5f, -0.62f, 0.95f, -0.38f, 0.98f), RED)
                b.f(pg(-0.18f, 0.55f, -0.32f, 0.98f, -0.05f, 0.95f), BLUE)
                b.f(ov(-0.08f, 0.1f, 0.4f, 0.56f, -12f), MAIN)
                b.f(ov(-0.18f, 0.18f, 0.2f, 0.36f, -12f), DARK)
                b.f(ci(0.12f, -0.45f, 0.3f), MAIN)
                b.f("M 0.32 -0.52 C 0.68 -0.58 0.66 -0.15 0.38 -0.2 Z", YELLOW)
                b.f(ci(0.2f, -0.54f, 0.08f), CREAM); b.n(ci(0.22f, -0.54f, 0.04f), INK)
            }
            36 -> { // pretzel
                val a = "M -0.55 0.72 C -0.98 0.0 -0.72 -0.72 -0.2 -0.62 C 0.2 -0.52 0.2 0.0 0.45 0.72"
                val c = "M 0.55 0.72 C 0.98 0.0 0.72 -0.72 0.2 -0.62 C -0.2 -0.52 -0.2 0.0 -0.45 0.72"
                b.l(a, DARK, 0.22f); b.l(c, DARK, 0.22f)
                b.l(a, MAIN, 0.15f); b.l(c, MAIN, 0.15f)
                for (p in listOf(-0.55f to -0.3f, 0.55f to -0.3f, -0.4f to 0.4f, 0.4f to 0.4f, 0f to -0.5f)) b.n(ci(p.first, p.second, 0.04f), CREAM)
            }
            37 -> { // compass
                b.f(ci(0f, 0f, 0.85f), MAIN)
                b.f(ci(0f, 0f, 0.66f), CREAM)
                for (k in 0 until 4) {
                    val ang = k * PI.toFloat() / 2
                    b.l(pl(cos(ang) * 0.52f, sin(ang) * 0.52f, cos(ang) * 0.66f, sin(ang) * 0.66f), DARK, 0.05f)
                }
                b.f(pg(0f, -0.58f, 0.14f, 0f, -0.14f, 0f), RED)
                b.f(pg(0f, 0.58f, 0.14f, 0f, -0.14f, 0f), DARK)
                b.f(ci(0f, 0f, 0.06f), INK)
            }
            38 -> { // hourglass
                b.f(pg(-0.46f, -0.73f, 0.46f, -0.73f, 0.06f, 0f, 0.46f, 0.73f, -0.46f, 0.73f, -0.06f, 0f), CREAM)
                b.n(pg(-0.32f, -0.6f, 0.32f, -0.6f, 0.04f, -0.08f), MAIN)
                b.n(pg(-0.34f, 0.7f, 0.34f, 0.7f, 0f, 0.32f), MAIN)
                b.l(pl(0f, -0.05f, 0f, 0.32f), MAIN, 0.035f)
                b.f(rr(-0.6f, -0.88f, 1.2f, 0.15f, 0.04f), BROWN); b.f(rr(-0.6f, 0.73f, 1.2f, 0.15f, 0.04f), BROWN)
            }
            39 -> { // gem
                b.f(pg(-0.5f, -0.6f, 0.5f, -0.6f, 0.88f, -0.15f, 0f, 0.88f, -0.88f, -0.15f), MAIN)
                b.n(pg(-0.5f, -0.6f, -0.25f, -0.15f, 0f, -0.6f), LIGHT)
                b.n(pg(0.5f, -0.6f, 0.25f, -0.15f, 0f, -0.6f), DARK)
                b.l(pl(-0.88f, -0.15f, 0.88f, -0.15f), CREAM, 0.03f)
                b.l(pl(-0.25f, -0.15f, 0f, 0.88f, 0.25f, -0.15f), CREAM, 0.03f)
            }
            40 -> { // bell
                b.l(ci(0f, -0.8f, 0.1f), DARK, 0.06f)
                b.f(ci(0f, 0.72f, 0.12f), INK)
                b.f("M -0.68 0.5 C -0.52 0.4 -0.46 0.0 -0.46 -0.22 C -0.46 -0.72 0.46 -0.72 0.46 -0.22 C 0.46 0.0 0.52 0.4 0.68 0.5 Z", MAIN)
                b.f(rr(-0.74f, 0.44f, 1.48f, 0.16f, 0.07f), DARK)
                b.n(ov(-0.2f, -0.2f, 0.07f, 0.28f, 10f), LIGHT)
            }
            41 -> { // castle
                b.f(rr(-0.66f, -0.1f, 1.32f, 0.88f, 0.02f), MAIN)
                b.f(rr(-0.84f, -0.5f, 0.4f, 1.28f, 0.02f), LIGHT)
                b.f(rr(0.44f, -0.5f, 0.4f, 1.28f, 0.02f), LIGHT)
                for (x in listOf(-0.84f, -0.64f)) b.f(rr(x, -0.62f, 0.14f, 0.14f, 0.01f), LIGHT)
                for (x in listOf(0.46f, 0.66f)) b.f(rr(x, -0.62f, 0.14f, 0.14f, 0.01f), LIGHT)
                b.l(pl(-0.64f, -0.62f, -0.64f, -0.9f), INK, 0.04f); b.f(pg(-0.64f, -0.9f, -0.4f, -0.82f, -0.64f, -0.74f), RED)
                b.f("M -0.17 0.78 L -0.17 0.4 C -0.17 0.12 0.17 0.12 0.17 0.4 L 0.17 0.78 Z", INK)
            }
            42 -> { // seahorse
                val body = "M 0.1 -0.55 C 0.55 -0.5 0.5 0.0 0.0 0.15 C -0.45 0.3 -0.3 0.72 0.15 0.66 C 0.42 0.62 0.4 0.36 0.22 0.4"
                b.l(body, DARK, 0.34f)
                b.l(body, MAIN, 0.26f)
                b.l(pl(0.1f, -0.64f, -0.4f, -0.56f), DARK, 0.15f)
                b.l(pl(0.1f, -0.64f, -0.4f, -0.56f), MAIN, 0.09f)
                b.f(ci(0.1f, -0.65f, 0.2f), MAIN)
                b.f(pg(0.35f, -0.2f, 0.7f, -0.05f, 0.4f, 0.1f), LIGHT)
                b.f(ci(0.14f, -0.7f, 0.06f), CREAM); b.n(ci(0.15f, -0.7f, 0.03f), INK)
                b.l(pl(0.2f, -0.1f, 0.05f, -0.05f), CREAM, 0.035f); b.l(pl(0.3f, 0.2f, 0.12f, 0.22f), CREAM, 0.035f)
            }
            43 -> { // caterpillar
                for (k in 0 until 4) { b.l(pl(-0.62f + k * 0.34f, 0.35f, -0.62f + k * 0.34f, 0.55f), DARK, 0.05f) }
                b.f(ci(-0.72f, 0.3f, 0.22f), MAIN)
                b.f(ci(-0.4f, 0.2f, 0.24f), LIGHT)
                b.f(ci(-0.06f, 0.14f, 0.26f), MAIN)
                b.f(ci(0.26f, 0.22f, 0.26f), LIGHT)
                b.l(pl(0.6f, -0.2f, 0.5f, -0.55f), INK, 0.04f); b.l(pl(0.72f, -0.2f, 0.88f, -0.48f), INK, 0.04f)
                b.f(ci(0.6f, 0.05f, 0.32f), MAIN)
                b.f(ci(0.52f, 0.0f, 0.07f), CREAM); b.f(ci(0.72f, 0.0f, 0.07f), CREAM)
                b.n(ci(0.53f, 0.0f, 0.03f), INK); b.n(ci(0.73f, 0.0f, 0.03f), INK)
                b.l(cv(0.5f, 0.15f, 0.56f, 0.24f, 0.68f, 0.24f, 0.74f, 0.15f), INK, 0.03f)
            }
            44 -> { // jellyfish
                for (k in 0 until 5) {
                    val x = -0.5f + k * 0.25f
                    b.l(cv(x, 0.1f, x - 0.14f, 0.3f, x + 0.14f, 0.55f, x, 0.9f), LIGHT, 0.07f)
                }
                b.f("M -0.72 0.12 C -0.72 -0.98 0.72 -0.98 0.72 0.12 Q 0.48 0.0 0.24 0.12 Q 0 0.0 -0.24 0.12 Q -0.48 0.0 -0.72 0.12 Z", MAIN)
                b.n(ci(-0.3f, -0.35f, 0.08f), CREAM); b.n(ci(0.1f, -0.52f, 0.07f), CREAM); b.n(ci(0.35f, -0.2f, 0.08f), CREAM)
            }
            45 -> { // feather
                b.f("M -0.55 0.88 C -0.98 -0.2 -0.3 -0.88 0.62 -0.92 C 0.72 -0.2 0.3 0.5 -0.55 0.88 Z", MAIN)
                b.l(pl(-0.62f, 0.94f, 0.58f, -0.88f), DARK, 0.06f)
                for (k in 0 until 4) {
                    val t = 0.2f + k * 0.2f
                    val qx = -0.6f + 1.18f * t
                    val qy = 0.92f - 1.8f * t
                    b.l(pl(qx, qy, qx - 0.28f, qy - 0.04f), DARK, 0.03f)
                    b.l(pl(qx, qy, qx + 0.06f, qy + 0.28f), DARK, 0.03f)
                }
            }
            46 -> { // banana
                b.f("M -0.82 -0.3 C -0.62 0.72 0.5 0.82 0.88 0.0 C 0.58 0.36 -0.25 0.32 -0.5 -0.48 Z", MAIN)
                b.f(ov(-0.68f, -0.42f, 0.09f, 0.12f, 20f), DARK)
                b.f(ov(0.86f, -0.02f, 0.1f, 0.07f, -40f), DARK)
                b.l(cv(-0.55f, -0.2f, -0.35f, 0.3f, 0.2f, 0.52f, 0.6f, 0.2f), LIGHT, 0.03f)
            }
            47 -> { // pizza slice
                b.f("M -0.64 -0.55 C -0.3 -0.88 0.3 -0.88 0.64 -0.55 L 0 0.92 Z", YELLOW)
                b.n("M -0.5 -0.45 C -0.25 -0.62 0.25 -0.62 0.5 -0.45 L 0 0.62 Z", MAIN)
                b.l("M -0.64 -0.55 C -0.3 -0.88 0.3 -0.88 0.64 -0.55", BROWN, 0.15f)
                b.f(ci(-0.15f, -0.25f, 0.11f), DARK); b.f(ci(0.2f, -0.22f, 0.11f), DARK); b.f(ci(0.02f, 0.18f, 0.1f), DARK)
            }
            48 -> { // lollipop
                b.l(pl(0f, 0.2f, 0f, 0.95f), INK, 0.08f)
                b.f(ci(0f, -0.3f, 0.58f), MAIN)
                b.l("M 0 -0.3 C 0.1 -0.3 0.14 -0.16 0 -0.14 C -0.2 -0.1 -0.28 -0.36 -0.06 -0.46 C 0.22 -0.58 0.4 -0.24 0.2 -0.02 C -0.04 0.22 -0.42 0.04 -0.4 -0.34", CREAM, 0.07f)
            }
            49 -> { // cloud
                b.g(listOf(ci(-0.42f, 0.12f, 0.3f), ci(-0.05f, -0.15f, 0.42f), ci(0.42f, 0.05f, 0.33f), rr(-0.72f, 0.1f, 1.46f, 0.32f, 0.16f)), MAIN)
                b.n(ov(-0.15f, -0.3f, 0.12f, 0.07f, -20f), LIGHT)
            }
            50 -> { // mountain
                b.f(pg(-0.92f, 0.78f, -0.2f, -0.5f, 0.55f, 0.78f), LIGHT)
                b.n(pg(-0.2f, -0.5f, -0.37f, -0.2f, -0.28f, -0.27f, -0.2f, -0.17f, -0.12f, -0.27f, -0.03f, -0.2f), CREAM)
                b.f(pg(-0.3f, 0.78f, 0.3f, -0.78f, 0.92f, 0.78f), MAIN)
                b.n(pg(0.3f, -0.78f, 0.16f, -0.42f, 0.24f, -0.48f, 0.3f, -0.36f, 0.37f, -0.48f, 0.45f, -0.42f), CREAM)
            }
            51 -> { // wave
                b.f("M -0.92 0.72 L -0.92 0.2 C -0.72 -0.3 -0.2 -0.7 0.3 -0.65 C 0.8 -0.6 0.95 -0.15 0.75 0.1 C 0.6 -0.15 0.35 -0.15 0.3 0.05 C 0.25 0.3 0.45 0.5 0.62 0.72 Z", MAIN)
                b.n(ci(0.7f, -0.32f, 0.07f), CREAM); b.n(ci(0.45f, -0.55f, 0.06f), CREAM); b.n(ci(0.85f, -0.06f, 0.05f), CREAM)
                b.l(cv(-0.7f, 0.35f, -0.4f, 0.0f, 0.0f, -0.3f, 0.3f, -0.35f), LIGHT, 0.05f)
            }
            52 -> { // spiral
                val sb = StringBuilder()
                val steps = 90
                for (k in 0..steps) {
                    val t = k / steps.toFloat()
                    val a = t * 2.6f * 2 * PI.toFloat()
                    val r = 0.06f + 0.78f * t
                    sb.append(if (k == 0) "M " else " L ").append(pt(cos(a) * r, sin(a) * r))
                }
                b.l(sb.toString(), DARK, 0.2f)
                b.l(sb.toString(), MAIN, 0.13f)
            }
            53 -> { // ladder
                b.l(pl(-0.36f, -0.9f, -0.4f, 0.9f), MAIN, 0.11f)
                b.l(pl(0.36f, -0.9f, 0.4f, 0.9f), MAIN, 0.11f)
                for (k in 0 until 5) { val y = -0.65f + k * 0.33f; b.l(pl(-0.38f, y, 0.38f, y), DARK, 0.09f) }
            }
            54 -> { // fried egg
                b.f("M -0.7 -0.1 C -0.8 -0.62 -0.2 -0.82 0.1 -0.62 C 0.5 -0.88 0.92 -0.42 0.76 0.0 C 0.96 0.4 0.5 0.82 0.1 0.62 C -0.2 0.86 -0.82 0.62 -0.7 -0.1 Z", CREAM)
                b.f(ci(0f, 0f, 0.3f), MAIN)
                b.n(ci(-0.1f, -0.1f, 0.07f), CREAM)
            }
            55 -> { // flamingo
                b.l(pl(-0.1f, 0.28f, -0.04f, 0.9f), INK, 0.05f); b.l(pl(-0.04f, 0.9f, 0.16f, 0.9f), INK, 0.05f)
                b.l("M 0.15 -0.1 C 0.55 -0.3 0.0 -0.55 0.35 -0.8", DARK, 0.17f)
                b.l("M 0.15 -0.1 C 0.55 -0.3 0.0 -0.55 0.35 -0.8", MAIN, 0.11f)
                b.f(ov(-0.1f, 0.0f, 0.42f, 0.3f), MAIN)
                b.f(ov(-0.35f, 0.0f, 0.22f, 0.12f, -10f), LIGHT)
                b.f(ci(0.38f, -0.82f, 0.11f), MAIN)
                b.f(pg(0.46f, -0.86f, 0.66f, -0.74f, 0.47f, -0.72f), INK)
                b.n(ci(0.4f, -0.85f, 0.025f), INK)
            }
            56 -> { // watering can
                b.l(cv(-0.3f, -0.25f, -0.3f, -0.9f, 0.2f, -0.9f, 0.2f, -0.25f), DARK, 0.08f)
                b.l(cv(-0.5f, -0.1f, -0.95f, -0.1f, -0.95f, 0.4f, -0.5f, 0.4f), DARK, 0.09f)
                b.l(pl(0.3f, 0.2f, 0.78f, -0.35f), MAIN, 0.1f)
                b.f(pg(0.66f, -0.56f, 0.95f, -0.38f, 0.86f, -0.24f, 0.62f, -0.34f), DARK)
                b.f(rr(-0.52f, -0.25f, 0.84f, 0.72f, 0.1f), MAIN)
                b.n(ci(0.95f, -0.1f, 0.04f), BLUE); b.n(ci(0.86f, 0.05f, 0.04f), BLUE)
                b.n(ov(-0.3f, 0.1f, 0.07f, 0.18f), LIGHT)
            }
            else -> { // never reached for 0..56: a plain disc so an unknown index still draws something
                b.f(ci(0f, 0f, 0.6f), MAIN)
            }
        }
    }

    /** The primitives of picture [index] (0..56); an out-of-range index gives an empty list. */
    fun prims(index: Int): List<FindItPrim> {
        if (index !in 0 until FindItSymbols.all.size) return emptyList()
        return cache[index]
    }

    private val cache: List<List<FindItPrim>> by lazy {
        List(FindItSymbols.all.size) { i -> Builder().also { draw(i, it) }.prims.toList() }
    }

    /** Every number of every path of picture [index] (for bounds tests). */
    fun numbers(index: Int): List<Float> =
        prims(index).flatMap { p -> p.d.split(' ', '|').mapNotNull { it.toFloatOrNull() } }
}
