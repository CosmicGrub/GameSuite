package com.gamesuite.games.partypack

/** An axis-aligned rectangle in the pixels of the board that owns the touch input. */
data class TapTugRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    fun inflate(by: Float) = TapTugRect(left - by, top - by, right + by, bottom + by)

    companion object {
        /** A rectangle nothing is inside of: the zone of a seat nobody touches (a computer). */
        val NONE = TapTugRect(0f, 0f, 0f, 0f)
    }
}

/** Why a new contact was or was not counted as a tap. */
enum class TapTugContact {
    /** Bound to a zone and counted. */
    COUNTED,

    /** Bound to a zone (so its lift is still tracked) but too fast for one finger: not counted. */
    TOO_FAST,

    /** Not inside any live zone, or the pointer is already tracked: ignored altogether. */
    IGNORED,

    /** A palm-size contact: ignored altogether, and its later moves and lift too. */
    PALM
}

/** The result of [TapTugPointerTracker.down]. [zone] is -1 unless the contact was bound. */
data class TapTugDown(val contact: TapTugContact, val zone: Int)

/** A finger that stopped touching its zone: [zone] and the pointer that was bound to it. */
data class TapTugRelease(val pointerId: Long, val zone: Int)

/**
 * The multi-touch input model of the party games, as a pure class (no Compose, no Android) so the rules
 * are unit tested with plain numbers. The screen feeds it the raw per-pointer events of ONE board and the
 * model decides what each finger is:
 *  - every finger is bound to exactly ONE zone, the one it first landed in, until it lifts, is cancelled, or
 *    slides out of that zone (then it is released, exactly like a lift, and never re-bound by sliding in
 *    another zone: only a fresh touch counts);
 *  - one finger lifting never disturbs another: pointers are tracked by id and nothing else;
 *  - a contact bigger than [maxContactPx] (a palm, a wrist) is ignored for good;
 *  - ANTI-CHEESE: a pointer id may not count a new tap sooner than [minPointerGapMs] after its previous one
 *    (a finger cannot tap faster than about 11 times a second) and a whole zone may not count more than one
 *    tap per [minZoneGapMs] (two or three fingers together cannot exceed about 25 a second). A contact that
 *    is too fast still binds, so its lift is tracked and a long hold still reads as a hold.
 * Time is passed in (`nowMs`) so tests need no clock.
 */
class TapTugPointerTracker(
    var zones: List<TapTugRect> = emptyList(),
    var maxContactPx: Float = Float.MAX_VALUE,
    val minPointerGapMs: Long = DEFAULT_POINTER_GAP_MS,
    val minZoneGapMs: Long = DEFAULT_ZONE_GAP_MS,
    val leaveSlopPx: Float = DEFAULT_SLOP_PX
) {
    private val bound = LinkedHashMap<Long, Int>()
    private val palms = HashSet<Long>()
    private val lastPointerTap = HashMap<Long, Long>()
    private val lastZoneTap = HashMap<Int, Long>()

    /** The zone [pointerId] is bound to, or null. */
    fun zoneOf(pointerId: Long): Int? = bound[pointerId]

    /** How many fingers are bound to [zone] right now. */
    fun fingersIn(zone: Int): Int = bound.values.count { it == zone }

    val activeCount: Int get() = bound.size

    fun zoneAt(x: Float, y: Float): Int = zones.indexOfFirst { it.contains(x, y) }

    /** A finger (or a pen, a mouse) went down at ([x], [y]) with a contact [contactPx] across. */
    fun down(pointerId: Long, x: Float, y: Float, contactPx: Float, nowMs: Long): TapTugDown {
        if (contactPx > maxContactPx) {
            palms += pointerId
            return TapTugDown(TapTugContact.PALM, -1)
        }
        palms -= pointerId
        if (pointerId in bound) return TapTugDown(TapTugContact.IGNORED, -1)
        val zone = zoneAt(x, y)
        if (zone < 0) return TapTugDown(TapTugContact.IGNORED, -1)
        bound[pointerId] = zone
        val pointerOk = lastPointerTap[pointerId]?.let { nowMs - it >= minPointerGapMs } ?: true
        val zoneOk = lastZoneTap[zone]?.let { nowMs - it >= minZoneGapMs } ?: true
        if (!pointerOk || !zoneOk) return TapTugDown(TapTugContact.TOO_FAST, zone)
        lastPointerTap[pointerId] = nowMs
        lastZoneTap[zone] = nowMs
        return TapTugDown(TapTugContact.COUNTED, zone)
    }

    /** A bound finger moved. Returns its release when it left its zone by more than the slop, else null. */
    fun move(pointerId: Long, x: Float, y: Float): TapTugRelease? {
        val zone = bound[pointerId] ?: return null
        val rect = zones.getOrNull(zone) ?: return release(pointerId)
        if (rect.inflate(leaveSlopPx).contains(x, y)) return null
        return release(pointerId)
    }

    /** A finger lifted. Returns the zone it was bound to, or null for an untracked or palm contact. */
    fun up(pointerId: Long): TapTugRelease? {
        palms -= pointerId
        return release(pointerId)
    }

    /** The system cancelled a finger's gesture: same as a lift for tracking purposes. */
    fun cancel(pointerId: Long): TapTugRelease? = up(pointerId)

    /** Every finger is gone (the window lost focus, the screen turned off, the board left composition). */
    fun cancelAll(): List<TapTugRelease> {
        val all = bound.map { TapTugRelease(it.key, it.value) }
        bound.clear()
        palms.clear()
        return all
    }

    /** Keep only the pointers in [alive]; return the releases of the bound pointers that are no longer there. */
    fun reconcile(alive: Set<Long>): List<TapTugRelease> {
        val gone = bound.filterKeys { it !in alive }.map { TapTugRelease(it.key, it.value) }
        gone.forEach { bound.remove(it.pointerId) }
        palms.retainAll(alive)
        return gone
    }

    private fun release(pointerId: Long): TapTugRelease? {
        val zone = bound.remove(pointerId) ?: return null
        return TapTugRelease(pointerId, zone)
    }

    companion object {
        const val DEFAULT_POINTER_GAP_MS = 90L
        const val DEFAULT_ZONE_GAP_MS = 40L
        const val DEFAULT_SLOP_PX = 24f

        /** The widest contact still treated as a finger: 22 mm in pixels at [densityDpi]. */
        fun maxFingerContactPx(densityDpi: Float): Float = 22f / 25.4f * densityDpi
    }
}
