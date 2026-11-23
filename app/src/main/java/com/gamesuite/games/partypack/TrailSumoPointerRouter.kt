package com.gamesuite.games.partypack

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * The multi-touch input model of the same-screen party games Light-Trail Duel and Sumo Push.
 *
 * Pure Kotlin (no Compose, no Android) so it is unit tested without a device: the screen's pointer loop feeds
 * it `down / move / up` for every finger and it decides which zone control each finger belongs to.
 *
 * THE RULES (what "clean multi-touch" means here):
 *  - A finger is bound at the moment it goes DOWN to exactly one region (the one under it) and stays bound to
 *    that region until it lifts or is cancelled, wherever it wanders. A finger that goes down outside every
 *    region is ignored for good: dragging into a control never "picks it up".
 *  - Lifting one finger never affects any other finger (each is tracked by its own pointer id).
 *  - A BUTTON can be held by several fingers (each press is reported); a STICK or DPAD is held by one finger
 *    at a time, the first one down. A second finger on a held stick is ignored, so two players' thumbs can never
 *    fight over one control.
 *  - [cancel], [cancelAll] and [setRegions] (a control that vanished) release the fingers they affect, so a
 *    stuck press cannot survive a rotation, a pause or the screen leaving composition.
 */

enum class TsKind { BUTTON, STICK, DPAD }

/** A touch region in screen pixels (left/top inclusive, right/bottom exclusive). */
data class TsRegion(
    val id: String,
    val seat: Int,
    val kind: TsKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

interface TsRouterListener {
    /** A finger went down on ([down] true) or lifted from ([down] false) a BUTTON. The router state is already updated. */
    fun onButton(region: TsRegion, down: Boolean)

    /** The vector of a STICK or DPAD changed. Screen axes (x right, y down), length 0..1; (0,0) when released. */
    fun onVector(region: TsRegion, vx: Float, vy: Float)
}

class TsPointerRouter(private val listener: TsRouterListener) {
    private var regions: List<TsRegion> = emptyList()
    private val bound = LinkedHashMap<Long, String>()
    private val ignored = HashSet<Long>()
    private val vectors = HashMap<String, Pair<Float, Float>>()

    fun setRegions(next: List<TsRegion>) {
        regions = next
        val ids = next.associateBy { it.id }
        for ((pointer, regionId) in bound.entries.toList()) {
            if (regionId !in ids) release(pointer)
        }
    }

    fun regionsNow(): List<TsRegion> = regions

    /** The region [pointer] is bound to, or null when it is unbound or ignored. */
    fun regionOf(pointer: Long): String? = bound[pointer]

    fun isPressed(regionId: String): Boolean = bound.containsValue(regionId)

    fun pressCount(regionId: String): Int = bound.values.count { it == regionId }

    fun vector(regionId: String): Pair<Float, Float> = vectors[regionId] ?: (0f to 0f)

    fun activePointers(): Int = bound.size

    /** A finger went down. Returns true when it was bound to a control. */
    fun down(pointer: Long, x: Float, y: Float): Boolean {
        if (pointer in bound) { move(pointer, x, y); return true }
        if (pointer in ignored) return false
        val region = regions.firstOrNull { it.contains(x, y) }
        if (region == null) { ignored += pointer; return false }
        if (region.kind != TsKind.BUTTON && region.id in bound.values) { ignored += pointer; return false }
        bound[pointer] = region.id
        when (region.kind) {
            TsKind.BUTTON -> listener.onButton(region, true)
            else -> updateVector(region, x, y)
        }
        return true
    }

    fun move(pointer: Long, x: Float, y: Float) {
        val id = bound[pointer] ?: return
        val region = regions.firstOrNull { it.id == id } ?: return
        if (region.kind != TsKind.BUTTON) updateVector(region, x, y)
    }

    fun up(pointer: Long) = release(pointer)

    fun cancel(pointer: Long) = release(pointer)

    fun cancelAll() {
        ignored.clear()
        for (pointer in bound.keys.toList()) release(pointer)
    }

    private fun release(pointer: Long) {
        ignored.remove(pointer)
        val id = bound.remove(pointer) ?: return
        val region = regions.firstOrNull { it.id == id }
        if (region == null) { vectors.remove(id); return }
        if (region.kind == TsKind.BUTTON) {
            listener.onButton(region, false)
        } else {
            vectors[id] = 0f to 0f
            listener.onVector(region, 0f, 0f)
        }
    }

    private fun updateVector(region: TsRegion, x: Float, y: Float) {
        val radius = (minOf(region.width, region.height) / 2f).coerceAtLeast(1f)
        val v = if (region.kind == TsKind.STICK) stickVector(x - region.centerX, y - region.centerY, radius)
        else dpadVector(x - region.centerX, y - region.centerY, radius)
        val old = vectors[region.id]
        if (old == null || abs(old.first - v.first) > 0.004f || abs(old.second - v.second) > 0.004f) {
            vectors[region.id] = v
            listener.onVector(region, v.first, v.second)
        }
    }

    companion object {
        const val STICK_DEAD_ZONE = 0.14f
        const val DPAD_DEAD_ZONE = 0.28f

        /** An analogue stick: length is the deflection (0..1) with a small dead zone, direction is exact. */
        fun stickVector(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
            val len = sqrt(dx * dx + dy * dy) / radius
            if (len < STICK_DEAD_ZONE) return 0f to 0f
            val mag = len.coerceAtMost(1f)
            val l = sqrt(dx * dx + dy * dy)
            return (dx / l * mag) to (dy / l * mag)
        }

        /** A direction pad: eight directions (the four arrows and their diagonals), always full length. */
        fun dpadVector(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
            val len = sqrt(dx * dx + dy * dy) / radius
            if (len < DPAD_DEAD_ZONE) return 0f to 0f
            val ax = abs(dx); val ay = abs(dy)
            val sx = if (dx >= 0) 1f else -1f
            val sy = if (dy >= 0) 1f else -1f
            // tan(22.5 deg) = 0.4142: inside that wedge of an axis the diagonal is dropped.
            return when {
                ay < ax * 0.4142f -> sx to 0f
                ax < ay * 0.4142f -> 0f to sy
                else -> (sx * 0.7071f) to (sy * 0.7071f)
            }
        }
    }
}
