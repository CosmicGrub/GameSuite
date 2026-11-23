package com.gamesuite.uikit

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * The touch size of every finger of the latest raw MotionEvent, looked up by POSITION. Compose gives
 * the pointer model its own ids, which are NOT the Android pointer ids of the MotionEvent, so a
 * contact is matched to the nearest recorded one within [MATCH_PX]; with no match the size is
 * unknown (0) and the contact is accepted.
 */
class ContactSizes {
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
 * Pure palm filter. A finger whose touch size exceeds [maxContactPx] at DOWN is a palm: it is
 * remembered by pointer id so its later moves and ups are ignored too, and it can never become a
 * counted press. An unknown size (0) is accepted, and so is everything when [maxContactPx] <= 0.
 */
class PalmGate(var maxContactPx: Float) {
    private val palms = HashSet<Long>()

    /** True when the finger going down with [sizePx] should count; false (and remembered) for a palm. */
    fun acceptDown(id: Long, sizePx: Float): Boolean {
        if (maxContactPx > 0f && sizePx > maxContactPx) { palms += id; return false }
        palms -= id
        return true
    }

    fun isRejected(id: Long): Boolean = id in palms

    /** Forget a finger that lifted or was cancelled. */
    fun release(id: Long) { palms -= id }

    /** Forget every palm whose id is no longer in [live]. */
    fun retain(live: Collection<Long>) { palms.retainAll(live.toSet()) }

    fun reset() = palms.clear()

    companion object {
        /** The widest contact still treated as a finger: 22 mm in pixels at [densityDpi]. */
        fun maxFingerContactPx(densityDpi: Float): Float = 22f / 25.4f * densityDpi
    }
}

/**
 * Measures touch sizes from the raw MotionEvent without consuming anything (touch size is not on
 * Compose's pointer change). Returns false so the Compose pointer model still sees every event; the
 * platform may then show this filter only the first finger of a gesture, and an unmeasured finger is
 * simply accepted, so the worst case is a missed palm, never a blocked finger.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.measuringContacts(contacts: ContactSizes): Modifier =
    pointerInteropFilter { contacts.record(it); false }

/** A gate with this device's finger-size limit, plus the contact sizes it measures against. */
class PalmGuard(val gate: PalmGate, val contacts: ContactSizes) {
    /** Whether a down at ([x], [y]) with pointer [id] should count. */
    fun acceptDown(id: Long, x: Float, y: Float): Boolean = gate.acceptDown(id, contacts.sizeAt(x, y))
}

@Composable
fun rememberPalmGuard(): PalmGuard {
    val dpi = LocalContext.current.resources.displayMetrics.densityDpi.toFloat()
    return remember(dpi) { PalmGuard(PalmGate(PalmGate.maxFingerContactPx(dpi)), ContactSizes()) }
}

/**
 * Consumes palm-sized touch-downs (and the rest of those fingers' gestures) in the Initial pass, so a
 * descendant `clickable` / tap handler never sees a resting palm. Does not affect real fingers.
 */
@Composable
fun Modifier.palmRejecting(): Modifier {
    val guard = rememberPalmGuard()
    return this.measuringContacts(guard.contacts).pointerInput(guard) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                guard.gate.retain(event.changes.filter { it.pressed }.map { it.id.value })
                for (c in event.changes) {
                    val id = c.id.value
                    if (c.changedToDownIgnoreConsumed()) guard.acceptDown(id, c.position.x, c.position.y)
                    if (guard.gate.isRejected(id)) c.consume()
                }
            }
        }
    }
}
