package com.gamesuite.games.actitout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pictograms for the cards, as pure data in a unit square (0..1 both ways, y down), so they are JVM-testable and
 * the screen only has to draw them with one ink colour. No colour emoji anywhere: a shape flagged [cut] is painted
 * in the paper colour (an eye, a window), everything else in ink. Arcs use Android's convention: 0 degrees is
 * three o'clock and angles grow clockwise.
 */
sealed interface GlyphShape {
    val cut: Boolean
}

data class GlyphCircle(val cx: Float, val cy: Float, val r: Float, val fill: Boolean, val width: Float, override val cut: Boolean) : GlyphShape
data class GlyphRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val fill: Boolean, val width: Float, override val cut: Boolean) : GlyphShape
data class GlyphLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, override val cut: Boolean) : GlyphShape

/** A closed polygon; [points] is x0, y0, x1, y1 ... */
data class GlyphPoly(val points: List<Float>, val fill: Boolean, val width: Float, override val cut: Boolean) : GlyphShape
data class GlyphArc(val cx: Float, val cy: Float, val r: Float, val startDeg: Float, val sweepDeg: Float, val width: Float, override val cut: Boolean) : GlyphShape

private fun fc(cx: Double, cy: Double, r: Double, cut: Boolean = false): GlyphShape =
    GlyphCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), true, 0f, cut)

private fun oc(cx: Double, cy: Double, r: Double, w: Double = 0.05): GlyphShape =
    GlyphCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), false, w.toFloat(), false)

private fun fr(l: Double, t: Double, r: Double, b: Double, cut: Boolean = false): GlyphShape =
    GlyphRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), true, 0f, cut)

private fun orr(l: Double, t: Double, r: Double, b: Double, w: Double = 0.05): GlyphShape =
    GlyphRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), false, w.toFloat(), false)

private fun ln(x1: Double, y1: Double, x2: Double, y2: Double, w: Double = 0.05, cut: Boolean = false): GlyphShape =
    GlyphLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), w.toFloat(), cut)

private fun fp(vararg v: Double): GlyphShape = GlyphPoly(v.map { it.toFloat() }, true, 0f, false)

private fun cp(vararg v: Double): GlyphShape = GlyphPoly(v.map { it.toFloat() }, true, 0f, true)

private fun ar(cx: Double, cy: Double, r: Double, start: Double, sweep: Double, w: Double = 0.05, cut: Boolean = false): GlyphShape =
    GlyphArc(cx.toFloat(), cy.toFloat(), r.toFloat(), start.toFloat(), sweep.toFloat(), w.toFloat(), cut)

private fun polar(cx: Double, cy: Double, r: Double, deg: Double): Pair<Double, Double> =
    (cx + r * cos(deg * PI / 180.0)) to (cy + r * sin(deg * PI / 180.0))

private fun star(cx: Double, cy: Double, outer: Double, inner: Double): GlyphShape {
    val v = ArrayList<Double>()
    for (i in 0 until 10) {
        val (x, y) = polar(cx, cy, if (i % 2 == 0) outer else inner, -90.0 + 36.0 * i)
        v.add(x); v.add(y)
    }
    return fp(*v.toDoubleArray())
}

private fun rays(cx: Double, cy: Double, r1: Double, r2: Double, n: Int, w: Double = 0.05): List<GlyphShape> =
    List(n) { i ->
        val (x1, y1) = polar(cx, cy, r1, 360.0 * i / n)
        val (x2, y2) = polar(cx, cy, r2, 360.0 * i / n)
        ln(x1, y1, x2, y2, w)
    }

/** A stick figure: head at ([hx],[hy]), hip at ([px],[py]), arm and leg ends as pairs. */
private fun fig(
    hx: Double, hy: Double, px: Double, py: Double,
    arms: List<Pair<Double, Double>>, legs: List<Pair<Double, Double>>
): List<GlyphShape> {
    val dx = px - hx
    val dy = py - hy
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val ux = dx / len
    val uy = dy / len
    val sx = hx + ux * len * 0.3
    val sy = hy + uy * len * 0.3
    val out = ArrayList<GlyphShape>()
    out.add(fc(hx, hy, 0.085))
    out.add(ln(hx + ux * 0.1, hy + uy * 0.1, px, py, 0.065))
    arms.forEach { out.add(ln(sx, sy, it.first, it.second, 0.055)) }
    legs.forEach { out.add(ln(px, py, it.first, it.second, 0.06)) }
    return out
}

private fun face(extra: List<GlyphShape>): List<GlyphShape> = listOf<GlyphShape>(oc(0.5, 0.5, 0.42, 0.05)) + extra

private fun eyes(y: Double = 0.42, r: Double = 0.045): List<GlyphShape> = listOf(fc(0.35, y, r), fc(0.65, y, r))

private fun pentagon(cx: Double, cy: Double, r: Double): GlyphShape {
    val v = ArrayList<Double>()
    for (i in 0 until 5) {
        val (x, y) = polar(cx, cy, r, -90.0 + 72.0 * i)
        v.add(x); v.add(y)
    }
    return fp(*v.toDoubleArray())
}

/** The pictogram vocabulary. Every difficulty-1 card has a glyph of its own here (a test checks that). */
object ActItOutGlyphs {
    /** Emblems used by cards that are not picture-only (one per category). */
    val emblems: Map<ActItOutCategory, String> = mapOf(
        ActItOutCategory.ANIMALS to "e_animals",
        ActItOutCategory.ACTIONS to "e_actions",
        ActItOutCategory.EVERYDAY to "e_everyday",
        ActItOutCategory.SCENES to "e_scenes",
        ActItOutCategory.SPORTS to "e_sports",
        ActItOutCategory.FEELINGS to "e_feelings",
        ActItOutCategory.HARD to "e_hard"
    )

    val shapes: Map<String, List<GlyphShape>> = mapOf(
        // ---- animals ----
        "cat" to listOf(
            fc(0.5, 0.58, 0.3), fp(0.22, 0.45, 0.2, 0.12, 0.42, 0.3), fp(0.78, 0.45, 0.8, 0.12, 0.58, 0.3),
            fc(0.4, 0.52, 0.045, true), fc(0.6, 0.52, 0.045, true), cp(0.45, 0.62, 0.55, 0.62, 0.5, 0.68),
            ln(0.05, 0.56, 0.2, 0.6, 0.03), ln(0.05, 0.7, 0.2, 0.66, 0.03), ln(0.95, 0.56, 0.8, 0.6, 0.03), ln(0.95, 0.7, 0.8, 0.66, 0.03)
        ),
        "dog" to listOf(
            fc(0.5, 0.5, 0.27), fp(0.28, 0.3, 0.1, 0.34, 0.12, 0.75, 0.28, 0.62), fp(0.72, 0.3, 0.9, 0.34, 0.88, 0.75, 0.72, 0.62),
            ln(0.27, 0.3, 0.27, 0.62, 0.025, true), ln(0.73, 0.3, 0.73, 0.62, 0.025, true),
            fc(0.4, 0.44, 0.04, true), fc(0.6, 0.44, 0.04, true), fc(0.5, 0.62, 0.065, true), fc(0.5, 0.82, 0.08)
        ),
        "fish" to listOf(
            fp(0.08, 0.5, 0.3, 0.3, 0.6, 0.28, 0.76, 0.5, 0.6, 0.72, 0.3, 0.7), fp(0.74, 0.5, 0.96, 0.28, 0.96, 0.72),
            fc(0.27, 0.46, 0.04, true), ar(0.4, 0.5, 0.12, -60.0, 120.0, 0.03, true), fp(0.42, 0.3, 0.52, 0.14, 0.6, 0.3)
        ),
        "bird" to listOf(
            fc(0.45, 0.58, 0.25), fc(0.7, 0.38, 0.14), fp(0.82, 0.36, 0.97, 0.42, 0.82, 0.46), fp(0.24, 0.52, 0.05, 0.36, 0.1, 0.58),
            fc(0.73, 0.35, 0.025, true), ar(0.45, 0.58, 0.15, 190.0, 130.0, 0.04, true), ln(0.42, 0.8, 0.42, 0.95, 0.04), ln(0.54, 0.8, 0.54, 0.95, 0.04)
        ),
        "rabbit" to listOf(
            fc(0.5, 0.68, 0.22), fp(0.37, 0.55, 0.3, 0.08, 0.45, 0.08, 0.47, 0.55), fp(0.53, 0.55, 0.55, 0.08, 0.7, 0.08, 0.63, 0.55),
            fc(0.42, 0.64, 0.03, true), fc(0.58, 0.64, 0.03, true), fc(0.5, 0.73, 0.035, true)
        ),
        "elephant" to listOf(
            fc(0.5, 0.45, 0.2), fc(0.2, 0.42, 0.17), fc(0.8, 0.42, 0.17), fr(0.43, 0.5, 0.57, 0.86), fr(0.36, 0.78, 0.57, 0.9),
            fc(0.43, 0.4, 0.03, true), fc(0.57, 0.4, 0.03, true)
        ),
        "butterfly" to listOf(
            fc(0.3, 0.35, 0.2), fc(0.7, 0.35, 0.2), fc(0.33, 0.68, 0.15), fc(0.67, 0.68, 0.15), fr(0.47, 0.25, 0.53, 0.82),
            ln(0.5, 0.25, 0.38, 0.08, 0.03), ln(0.5, 0.25, 0.62, 0.08, 0.03), fc(0.3, 0.35, 0.06, true), fc(0.7, 0.35, 0.06, true)
        ),
        "turtle" to listOf(
            fc(0.48, 0.62, 0.32), fr(0.0, 0.64, 1.0, 1.0, true), fr(0.14, 0.64, 0.82, 0.72), fc(0.9, 0.6, 0.09),
            fr(0.25, 0.72, 0.35, 0.86), fr(0.6, 0.72, 0.7, 0.86), ln(0.48, 0.32, 0.48, 0.62, 0.03, true), ln(0.3, 0.47, 0.66, 0.47, 0.03, true)
        ),
        "pig" to listOf(
            fc(0.5, 0.55, 0.32), fp(0.25, 0.36, 0.22, 0.12, 0.42, 0.25), fp(0.75, 0.36, 0.78, 0.12, 0.58, 0.25),
            fc(0.5, 0.64, 0.13, true), fc(0.46, 0.64, 0.025), fc(0.54, 0.64, 0.025), fc(0.38, 0.48, 0.035, true), fc(0.62, 0.48, 0.035, true)
        ),
        "duck" to listOf(
            fc(0.45, 0.64, 0.28), fc(0.72, 0.36, 0.15), fp(0.85, 0.34, 0.98, 0.39, 0.85, 0.44), fp(0.22, 0.58, 0.05, 0.48, 0.2, 0.72),
            fc(0.75, 0.33, 0.025, true), ar(0.45, 0.64, 0.16, 190.0, 120.0, 0.04, true)
        ),
        "snail" to listOf(
            fc(0.45, 0.45, 0.27), ar(0.45, 0.45, 0.16, 0.0, 300.0, 0.04, true), ar(0.45, 0.45, 0.07, 30.0, 290.0, 0.04, true),
            fr(0.06, 0.74, 0.9, 0.84), fp(0.78, 0.74, 0.9, 0.74, 0.88, 0.5, 0.8, 0.5), ln(0.8, 0.5, 0.76, 0.36, 0.03), ln(0.88, 0.5, 0.92, 0.36, 0.03)
        ),

        // ---- actions (stick figures) ----
        "jump" to fig(0.5, 0.2, 0.5, 0.55, listOf(0.2 to 0.08, 0.8 to 0.08), listOf(0.28 to 0.78, 0.72 to 0.78)) + listOf(ln(0.3, 0.93, 0.7, 0.93, 0.04)),
        "run" to fig(0.55, 0.2, 0.48, 0.55, listOf(0.28 to 0.4, 0.75 to 0.35), listOf(0.25 to 0.8, 0.72 to 0.72)) +
            listOf(ln(0.05, 0.3, 0.2, 0.3, 0.035), ln(0.05, 0.45, 0.18, 0.45, 0.035)),
        "wave" to fig(0.5, 0.22, 0.5, 0.58, listOf(0.25 to 0.62, 0.8 to 0.15), listOf(0.35 to 0.92, 0.65 to 0.92)) +
            listOf(ar(0.88, 0.2, 0.1, -70.0, 140.0, 0.035)),
        "dance" to fig(0.45, 0.22, 0.5, 0.58, listOf(0.15 to 0.3, 0.78 to 0.12), listOf(0.3 to 0.9, 0.72 to 0.78)) +
            listOf(fc(0.86, 0.6, 0.05), ln(0.9, 0.6, 0.9, 0.34, 0.035)),
        "sleep" to fig(0.2, 0.64, 0.62, 0.7, listOf(0.38 to 0.8, 0.4 to 0.56), listOf(0.92 to 0.62, 0.92 to 0.78)) +
            listOf(ln(0.05, 0.88, 0.95, 0.88, 0.04), ln(0.6, 0.15, 0.78, 0.15, 0.04), ln(0.78, 0.15, 0.6, 0.32, 0.04), ln(0.6, 0.32, 0.78, 0.32, 0.04)),
        "clap" to fig(0.5, 0.22, 0.5, 0.6, listOf(0.42 to 0.46, 0.58 to 0.46), listOf(0.38 to 0.92, 0.62 to 0.92)) +
            listOf(ln(0.22, 0.42, 0.33, 0.46, 0.035), ln(0.78, 0.42, 0.67, 0.46, 0.035), ln(0.28, 0.3, 0.36, 0.38, 0.035), ln(0.72, 0.3, 0.64, 0.38, 0.035)),
        "swim" to listOf(
            fc(0.4, 0.46, 0.08), ln(0.46, 0.5, 0.88, 0.4, 0.06), ln(0.46, 0.5, 0.2, 0.36, 0.05),
            ar(0.2, 0.72, 0.1, 180.0, 180.0, 0.045), ar(0.4, 0.72, 0.1, 180.0, 180.0, 0.045), ar(0.6, 0.72, 0.1, 180.0, 180.0, 0.045), ar(0.8, 0.72, 0.1, 180.0, 180.0, 0.045)
        ),
        "stretch" to fig(0.5, 0.26, 0.5, 0.6, listOf(0.36 to 0.03, 0.64 to 0.03), listOf(0.4 to 0.92, 0.6 to 0.92)) + listOf(ln(0.3, 0.97, 0.7, 0.97, 0.03)),
        "crawl" to fig(0.78, 0.35, 0.3, 0.45, listOf(0.66 to 0.76, 0.84 to 0.74), listOf(0.18 to 0.76, 0.38 to 0.8)) + listOf(ln(0.05, 0.9, 0.95, 0.9, 0.03)),
        "spin" to fig(0.5, 0.25, 0.5, 0.6, listOf(0.22 to 0.45, 0.78 to 0.45), listOf(0.38 to 0.9, 0.62 to 0.9)) +
            listOf(ar(0.5, 0.5, 0.45, 200.0, 120.0, 0.04), ar(0.5, 0.5, 0.45, 20.0, 120.0, 0.04), fp(0.86, 0.08, 0.96, 0.26, 0.76, 0.24), fp(0.14, 0.92, 0.04, 0.74, 0.24, 0.76)),

        // ---- everyday things ----
        "cup" to listOf(fp(0.25, 0.3, 0.75, 0.3, 0.68, 0.86, 0.32, 0.86), ar(0.76, 0.5, 0.15, -90.0, 180.0, 0.06), ln(0.4, 0.1, 0.4, 0.22, 0.035), ln(0.56, 0.08, 0.56, 0.22, 0.035)),
        "chair" to listOf(fr(0.25, 0.08, 0.35, 0.56), fr(0.25, 0.5, 0.75, 0.6), fr(0.25, 0.6, 0.33, 0.93), fr(0.67, 0.6, 0.75, 0.93)),
        "bed" to listOf(fr(0.08, 0.3, 0.16, 0.86), fr(0.08, 0.56, 0.92, 0.68), fr(0.84, 0.68, 0.92, 0.86), oc(0.3, 0.46, 0.1, 0.045), fr(0.2, 0.68, 0.84, 0.72)),
        "clock" to listOf(oc(0.5, 0.5, 0.4, 0.06), ln(0.5, 0.5, 0.5, 0.22, 0.05), ln(0.5, 0.5, 0.7, 0.58, 0.05), fc(0.5, 0.17, 0.025), fc(0.5, 0.83, 0.025), fc(0.17, 0.5, 0.025), fc(0.83, 0.5, 0.025)),
        "umbrella" to listOf(fc(0.5, 0.45, 0.42), fr(0.0, 0.46, 1.0, 1.0, true), ln(0.5, 0.45, 0.5, 0.82, 0.05), ar(0.4, 0.82, 0.1, 0.0, 180.0, 0.05), ln(0.5, 0.05, 0.5, 0.45, 0.03, true), ln(0.28, 0.14, 0.3, 0.45, 0.03, true), ln(0.72, 0.14, 0.7, 0.45, 0.03, true)),
        "key" to listOf(oc(0.27, 0.5, 0.18, 0.07), fr(0.44, 0.47, 0.92, 0.53), fr(0.72, 0.53, 0.78, 0.7), fr(0.84, 0.53, 0.9, 0.66)),
        "ball" to listOf(fc(0.5, 0.5, 0.4), ar(-0.1, 0.5, 0.5, -50.0, 100.0, 0.04, true), ar(1.1, 0.5, 0.5, 130.0, 100.0, 0.04, true), ln(0.5, 0.1, 0.5, 0.9, 0.04, true)),
        "book" to listOf(fr(0.2, 0.12, 0.8, 0.88), ln(0.3, 0.12, 0.3, 0.88, 0.03, true), ln(0.42, 0.3, 0.7, 0.3, 0.04, true), ln(0.42, 0.42, 0.7, 0.42, 0.04, true)),
        "house" to listOf(fp(0.5, 0.1, 0.94, 0.48, 0.06, 0.48), fr(0.18, 0.48, 0.82, 0.9), fr(0.7, 0.18, 0.79, 0.36), fr(0.43, 0.62, 0.57, 0.9, true), fr(0.24, 0.55, 0.36, 0.67, true), fr(0.64, 0.55, 0.76, 0.67, true)),
        "tree" to listOf(fc(0.5, 0.33, 0.27), fc(0.32, 0.5, 0.2), fc(0.68, 0.5, 0.2), fr(0.44, 0.6, 0.56, 0.93)),
        "sun" to listOf(fc(0.5, 0.5, 0.2)) + rays(0.5, 0.5, 0.3, 0.44, 8),
        "moon" to listOf(fc(0.5, 0.5, 0.38), fc(0.66, 0.42, 0.32, true)),
        "star" to listOf(star(0.5, 0.52, 0.42, 0.18), fc(0.08, 0.12, 0.03), fc(0.93, 0.18, 0.025)),
        "flower" to listOf(fc(0.5, 0.2, 0.14), fc(0.67, 0.33, 0.14), fc(0.6, 0.52, 0.14), fc(0.4, 0.52, 0.14), fc(0.33, 0.33, 0.14), fc(0.5, 0.38, 0.08, true), ln(0.5, 0.55, 0.5, 0.93, 0.05), fp(0.5, 0.78, 0.75, 0.68, 0.64, 0.86)),
        "car" to listOf(fr(0.06, 0.5, 0.94, 0.73), fp(0.26, 0.5, 0.36, 0.28, 0.66, 0.28, 0.78, 0.5), cp(0.33, 0.48, 0.4, 0.33, 0.49, 0.33, 0.49, 0.48), cp(0.53, 0.48, 0.53, 0.33, 0.62, 0.33, 0.7, 0.48), fc(0.28, 0.75, 0.12), fc(0.72, 0.75, 0.12), fc(0.28, 0.75, 0.045, true), fc(0.72, 0.75, 0.045, true)),
        "boat" to listOf(fp(0.08, 0.64, 0.92, 0.64, 0.74, 0.84, 0.26, 0.84), ln(0.5, 0.1, 0.5, 0.64, 0.04), fp(0.55, 0.14, 0.55, 0.58, 0.86, 0.58), fp(0.45, 0.26, 0.45, 0.58, 0.2, 0.58)),
        "balloon" to listOf(fc(0.5, 0.36, 0.28), fp(0.45, 0.65, 0.55, 0.65, 0.5, 0.72), ln(0.5, 0.72, 0.56, 0.82, 0.025), ln(0.56, 0.82, 0.44, 0.9, 0.025), ln(0.44, 0.9, 0.5, 0.98, 0.025), ar(0.5, 0.36, 0.17, 200.0, 70.0, 0.04, true)),
        "hat" to listOf(fp(0.3, 0.66, 0.34, 0.22, 0.66, 0.22, 0.7, 0.66), fr(0.1, 0.66, 0.9, 0.76), fr(0.31, 0.54, 0.69, 0.62, true)),

        // ---- scenes ----
        "toothbrush" to listOf(ln(0.1, 0.84, 0.6, 0.48, 0.09), fp(0.58, 0.4, 0.9, 0.18, 0.97, 0.3, 0.66, 0.52), ln(0.64, 0.36, 0.62, 0.22, 0.03), ln(0.72, 0.3, 0.7, 0.16, 0.03), ln(0.8, 0.24, 0.78, 0.1, 0.03), ln(0.88, 0.18, 0.86, 0.05, 0.03), fc(0.2, 0.3, 0.04), fc(0.3, 0.18, 0.03)),
        "apple" to listOf(fc(0.4, 0.58, 0.27), fc(0.6, 0.58, 0.27), ln(0.5, 0.36, 0.52, 0.16, 0.05), fp(0.54, 0.2, 0.78, 0.1, 0.7, 0.3), fc(0.9, 0.5, 0.12, true)),
        "camera" to listOf(fr(0.08, 0.3, 0.92, 0.82), fr(0.35, 0.2, 0.65, 0.31), fc(0.5, 0.56, 0.21, true), fc(0.5, 0.56, 0.13), fc(0.45, 0.5, 0.035, true), fc(0.8, 0.4, 0.035, true)),
        "wateringcan" to listOf(fr(0.18, 0.4, 0.64, 0.86), ln(0.64, 0.56, 0.9, 0.3, 0.07), fp(0.86, 0.18, 0.98, 0.26, 0.9, 0.4), ar(0.4, 0.4, 0.18, 180.0, 180.0, 0.05), fc(0.92, 0.54, 0.025), fc(0.96, 0.68, 0.025), fc(0.86, 0.7, 0.025)),
        "guitar" to listOf(fc(0.36, 0.72, 0.22), fc(0.28, 0.56, 0.15), ln(0.4, 0.58, 0.86, 0.14, 0.07), fp(0.8, 0.08, 0.95, 0.2, 0.9, 0.3, 0.76, 0.16), fc(0.34, 0.68, 0.07, true)),
        "icecream" to listOf(fp(0.3, 0.5, 0.7, 0.5, 0.5, 0.94), fc(0.5, 0.38, 0.22), fc(0.5, 0.1, 0.06), ln(0.38, 0.58, 0.55, 0.82, 0.025, true), ln(0.62, 0.58, 0.45, 0.82, 0.025, true)),
        "kite" to listOf(fp(0.5, 0.04, 0.78, 0.38, 0.5, 0.7, 0.22, 0.38), ln(0.5, 0.04, 0.5, 0.7, 0.025, true), ln(0.22, 0.38, 0.78, 0.38, 0.025, true), ln(0.5, 0.7, 0.4, 0.8, 0.03), ln(0.4, 0.8, 0.55, 0.88, 0.03), ln(0.55, 0.88, 0.45, 0.97, 0.03)),
        "broom" to listOf(ln(0.84, 0.05, 0.4, 0.62, 0.06), fp(0.28, 0.56, 0.5, 0.66, 0.36, 0.95, 0.06, 0.82), ln(0.28, 0.7, 0.2, 0.88, 0.02, true), ln(0.38, 0.74, 0.3, 0.92, 0.02, true)),

        // ---- sports ----
        "soccer" to listOf(oc(0.5, 0.5, 0.4, 0.05), pentagon(0.5, 0.5, 0.15)) +
            List(5) { i -> val (x1, y1) = polar(0.5, 0.5, 0.15, -90.0 + 72.0 * i); val (x2, y2) = polar(0.5, 0.5, 0.4, -90.0 + 72.0 * i); ln(x1, y1, x2, y2, 0.035) },
        "basketball" to listOf(fc(0.5, 0.5, 0.4), ln(0.1, 0.5, 0.9, 0.5, 0.035, true), ln(0.5, 0.1, 0.5, 0.9, 0.035, true), ar(-0.1, 0.5, 0.42, -50.0, 100.0, 0.035, true), ar(1.1, 0.5, 0.42, 130.0, 100.0, 0.035, true)),
        "tennis" to listOf(oc(0.4, 0.38, 0.28, 0.06), ln(0.4, 0.12, 0.4, 0.64, 0.02), ln(0.16, 0.38, 0.64, 0.38, 0.02), ln(0.58, 0.58, 0.88, 0.9, 0.08), fc(0.83, 0.18, 0.08)),
        "bowling" to listOf(fc(0.3, 0.2, 0.08), fp(0.25, 0.3, 0.35, 0.3, 0.41, 0.55, 0.34, 0.9, 0.26, 0.9, 0.19, 0.55), fc(0.72, 0.7, 0.2), fc(0.68, 0.64, 0.025, true), fc(0.76, 0.62, 0.025, true), fc(0.72, 0.72, 0.025, true)),
        "bicycle" to listOf(oc(0.22, 0.68, 0.2, 0.05), oc(0.78, 0.68, 0.2, 0.05), ln(0.22, 0.68, 0.45, 0.4, 0.05), ln(0.45, 0.4, 0.72, 0.4, 0.05), ln(0.72, 0.4, 0.78, 0.68, 0.05), ln(0.45, 0.4, 0.55, 0.68, 0.05), ln(0.55, 0.68, 0.22, 0.68, 0.05), ln(0.72, 0.4, 0.68, 0.24, 0.05), ln(0.6, 0.24, 0.8, 0.24, 0.05), ln(0.36, 0.33, 0.52, 0.33, 0.06)),
        "rope" to fig(0.5, 0.22, 0.5, 0.58, listOf(0.3 to 0.5, 0.7 to 0.5), listOf(0.4 to 0.84, 0.6 to 0.84)) + listOf(ar(0.5, 0.5, 0.4, 20.0, 140.0, 0.035), fc(0.3, 0.5, 0.04), fc(0.7, 0.5, 0.04)),

        // ---- feelings ----
        "happy" to face(eyes() + ar(0.5, 0.5, 0.24, 20.0, 140.0, 0.05)),
        "sad" to face(eyes() + ar(0.5, 0.82, 0.2, 205.0, 130.0, 0.05) + fp(0.7, 0.5, 0.66, 0.6, 0.74, 0.6)),
        "surprised" to face(listOf(oc(0.35, 0.4, 0.075, 0.035), oc(0.65, 0.4, 0.075, 0.035), fc(0.35, 0.4, 0.03), fc(0.65, 0.4, 0.03), oc(0.5, 0.72, 0.08, 0.05), ar(0.35, 0.26, 0.1, 200.0, 140.0, 0.03), ar(0.65, 0.26, 0.1, 200.0, 140.0, 0.03))),
        "sleepy" to listOf(oc(0.42, 0.6, 0.34, 0.05), ar(0.32, 0.55, 0.07, 0.0, 180.0, 0.04), ar(0.52, 0.55, 0.07, 0.0, 180.0, 0.04), ln(0.35, 0.76, 0.5, 0.76, 0.04), ln(0.68, 0.1, 0.84, 0.1, 0.04), ln(0.84, 0.1, 0.68, 0.26, 0.04), ln(0.68, 0.26, 0.84, 0.26, 0.04)),
        "grumpy" to face(eyes(0.46) + listOf(ln(0.24, 0.28, 0.43, 0.37, 0.05), ln(0.76, 0.28, 0.57, 0.37, 0.05), ar(0.5, 0.8, 0.2, 205.0, 130.0, 0.05))),
        "laughing" to face(listOf(fc(0.5, 0.58, 0.2), fr(0.28, 0.35, 0.72, 0.58, true), ar(0.35, 0.42, 0.07, 180.0, 180.0, 0.045), ar(0.65, 0.42, 0.07, 180.0, 180.0, 0.045))),
        "excited" to face(listOf(star(0.35, 0.4, 0.1, 0.045), star(0.65, 0.4, 0.1, 0.045), ar(0.5, 0.52, 0.26, 20.0, 140.0, 0.05))),
        "bored" to face(listOf(ln(0.26, 0.4, 0.44, 0.4, 0.05), ln(0.56, 0.4, 0.74, 0.4, 0.05), fc(0.35, 0.46, 0.03), fc(0.65, 0.46, 0.03), ln(0.38, 0.7, 0.62, 0.7, 0.05))),

        // ---- category emblems ----
        "e_animals" to listOf(fc(0.5, 0.66, 0.2), fc(0.24, 0.42, 0.09), fc(0.42, 0.26, 0.09), fc(0.58, 0.26, 0.09), fc(0.76, 0.42, 0.09)),
        "e_actions" to listOf(fp(0.58, 0.05, 0.2, 0.55, 0.46, 0.55, 0.36, 0.95, 0.8, 0.4, 0.55, 0.4, 0.72, 0.05), oc(0.5, 0.5, 0.46, 0.04)),
        "e_everyday" to listOf(fr(0.15, 0.42, 0.85, 0.9), fr(0.1, 0.3, 0.9, 0.42), ln(0.5, 0.3, 0.5, 0.9, 0.05, true), oc(0.4, 0.2, 0.09, 0.04), oc(0.6, 0.2, 0.09, 0.04)),
        "e_scenes" to listOf(orr(0.1, 0.15, 0.9, 0.85, 0.06), fp(0.16, 0.8, 0.4, 0.45, 0.6, 0.7, 0.74, 0.55, 0.84, 0.8), fc(0.7, 0.32, 0.07)),
        "e_sports" to listOf(fp(0.25, 0.14, 0.75, 0.14, 0.68, 0.5, 0.32, 0.5), ar(0.22, 0.3, 0.12, 90.0, 180.0, 0.05), ar(0.78, 0.3, 0.12, 270.0, 180.0, 0.05), fr(0.45, 0.5, 0.55, 0.75), fr(0.3, 0.75, 0.7, 0.88)),
        "e_feelings" to listOf(fc(0.33, 0.35, 0.2), fc(0.67, 0.35, 0.2), fp(0.15, 0.45, 0.85, 0.45, 0.5, 0.88)),
        "e_hard" to listOf(ar(0.5, 0.32, 0.2, 150.0, 240.0, 0.09), ln(0.67, 0.42, 0.5, 0.58, 0.09), ln(0.5, 0.58, 0.5, 0.66, 0.09), fc(0.5, 0.85, 0.06))
    )

    /** The drawing for [glyph], or the emblem of [category] when the id is unknown (never null). */
    fun forCard(glyph: String, category: ActItOutCategory): List<GlyphShape> =
        shapes[glyph] ?: shapes.getValue(emblems.getValue(category))
}
