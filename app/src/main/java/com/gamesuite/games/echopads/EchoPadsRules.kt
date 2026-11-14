package com.gamesuite.games.echopads

import kotlin.math.min

/** The three ways to play Echo Pads. [id] is stored in saves. */
enum class EchoMode(val id: String) {
    /** One player, an endless growing pattern, a personal best. */
    SOLO("solo"),

    /** 2 to 6 people share the device and take turns; a miss puts you out, the last one standing wins. */
    PASS_AND_PLAY("party"),

    /** Two people, same screen, each with their own half and pads, copying one shared pattern in turn. */
    DUEL("duel");

    companion object {
        fun fromId(id: String?): EchoMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How fast the pattern is shown: how long each pad stays lit and the gap before the next one, at the app's
 * Normal game speed. The app's game-speed setting scales these further (see [EchoRules.timing]).
 */
enum class EchoSpeed(val id: String, val litMs: Long, val gapMs: Long) {
    SLOW("slow", 900L, 420L),
    NORMAL("normal", 600L, 260L),
    FAST("fast", 360L, 140L);

    companion object {
        /** Tolerant parse for a stored or launched value: anything unknown is [NORMAL]. */
        fun fromId(id: String?): EchoSpeed = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/** What the board is doing. Taps only count in [WAITING]; showing the pattern and checking an answer never overlap them. */
enum class EchoPhase {
    /** Between turns: waiting for the next player to say they are ready (hand-off), or for a short lead-in. */
    READY,

    /** The pattern is playing back, pad by pad. Every tap is ignored. */
    SHOWING,

    /** The player copies the pattern. The only phase that accepts taps. */
    WAITING,

    /** The answer is in (right or wrong): a short feedback pause. Every tap is ignored. */
    CHECKING,

    /** The match is finished. */
    OVER
}

/** The result of one turn, shown during [EchoPhase.CHECKING]. */
enum class EchoOutcome { OK, RETRY, MISS }

/** What a tap did. */
enum class TapResult {
    /** Not accepted: wrong phase, or not a pad. */
    IGNORED,

    /** The right pad, with more of the pattern still to copy. */
    PROGRESS,

    /** The right pad and the last one: the pattern is copied. */
    SEQUENCE_DONE,

    /** The wrong pad, but a free try is available: the pattern will be shown again. */
    RETRY,

    /** The wrong pad. */
    MISS
}

/** How long one pad is lit, the gap after it, and the pauses around a turn, all in milliseconds. */
data class EchoTiming(val litMs: Long, val gapMs: Long, val leadInMs: Long, val feedbackMs: Long, val missFeedbackMs: Long) {
    /** The time one step of the pattern takes (lit plus gap). */
    val stepMs: Long get() = litMs + gapMs
}

/** The pad table (shape, colour, pitch) and the constants every part of the game agrees on. Pure Kotlin. */
object EchoPads {
    const val COUNT = 4

    /** Pad shapes by index: a distinct SHAPE for each pad, so colour is never the only cue. */
    const val CIRCLE = 0
    const val TRIANGLE = 1
    const val SQUARE = 2
    const val STAR = 3

    /**
     * Pad colours (Okabe-Ito, colour-blind safe): blue, vermillion, yellow, bluish green. Hue is never the only
     * cue: every pad also has its own shape and its own pitch.
     */
    val colours: LongArray = longArrayOf(0xFF0072B2L, 0xFFD55E00L, 0xFFF0E442L, 0xFF009E73L)

    /** Pad pitches in Hz, a rising G4 - C5 - E5 - A5 pentatonic run (every step more than a minor third, 1.2x), so the pitch also tells the pads apart. */
    val frequencies: DoubleArray = doubleArrayOf(392.00, 523.25, 659.25, 880.00)
}

/** The rules and numbers of Echo Pads that are not tied to one match. Pure Kotlin. */
object EchoRules {
    const val MIN_PLAYERS = 1
    const val MAX_PLAYERS = 6
    const val PARTY_MIN = 2
    const val PARTY_MAX = 6
    const val DUEL_PLAYERS = 2

    /** The longest pattern: a match that reaches it ends there (a tie among whoever is still in). */
    const val MAX_LENGTH = 40

    /** The pause before the first pad lights each time the pattern plays, so the board has settled. */
    const val SHOW_HEAD_MS = 350L

    /** The shortest a pad may stay lit / the shortest gap, however fast the game speed is set. */
    const val MIN_LIT_MS = 170L
    const val MIN_GAP_MS = 90L

    /** The seats a mode needs, as an inclusive range. */
    fun seatRange(mode: EchoMode): IntRange = when (mode) {
        EchoMode.SOLO -> 1..1
        EchoMode.PASS_AND_PLAY -> PARTY_MIN..PARTY_MAX
        EchoMode.DUEL -> DUEL_PLAYERS..DUEL_PLAYERS
    }

    /**
     * How much faster a long pattern plays than a short one: 1.0 up to length 5, then 3% quicker per step, never
     * more than 30% quicker. Forgiving (kids) play never speeds up.
     */
    fun pace(length: Int, forgiving: Boolean): Float {
        if (forgiving || length <= 5) return 1f
        return (1f - 0.03f * (length - 5)).coerceAtLeast(0.7f)
    }

    /**
     * The timing of a pattern of [length] steps at [speed], stretched by [delayScale] (the app's game-speed
     * setting: above 1 is slower, below 1 quicker). A positive scale never collapses a pad below the floors.
     */
    fun timing(speed: EchoSpeed, delayScale: Float, length: Int, forgiving: Boolean): EchoTiming {
        val scale = if (delayScale.isNaN()) 1f else delayScale.coerceIn(0.25f, 4f)
        val pace = pace(length, forgiving)
        val lit = (speed.litMs * scale * pace).toLong().coerceAtLeast(MIN_LIT_MS)
        val gap = (speed.gapMs * scale * pace).toLong().coerceAtLeast(MIN_GAP_MS)
        val feedback = (650L * scale).toLong().coerceIn(450L, 1500L)
        return EchoTiming(
            litMs = lit, gapMs = gap,
            leadInMs = (700L * scale).toLong().coerceIn(400L, 1800L),
            feedbackMs = feedback,
            missFeedbackMs = feedback + feedback / 2
        )
    }
}

/**
 * Where the four pads sit inside a pad area (a 2 x 2 grid: circle and triangle on top, square and star below),
 * shared by the drawing and the touch hit-test so they can never disagree. Pure Kotlin.
 */
object EchoPadGrid {
    /** The gap between pads, as a fraction of the smaller side of the area. */
    private const val GAP = 0.04f

    /** The smallest comfortable pad touch target, in dp. */
    const val MIN_TOUCH_DP = 72f

    /** A duel half shorter than this (dp) uses the compact chrome (see [duelChromeDp]). */
    const val COMPACT_ZONE_BELOW_DP = 220f

    /** Vertical dp a duel half spends on padding, header and spacer around its pads: compact is for short halves (landscape phones). */
    fun duelChromeDp(contentHeightDp: Float): Float = if (contentHeightDp < COMPACT_ZONE_BELOW_DP) 42f else 66f

    /** Horizontal dp a duel half spends on padding around its pads. */
    fun duelSideChromeDp(contentHeightDp: Float): Float = if (contentHeightDp < COMPACT_ZONE_BELOW_DP) 12f else 24f

    /** The pad side (dp) a duel half whose upright frame is [contentWidthDp] x [contentHeightDp] gives its pads. */
    fun duelPadSideDp(contentWidthDp: Float, contentHeightDp: Float): Float =
        bestPadSide(
            (contentWidthDp - duelSideChromeDp(contentHeightDp)).coerceAtLeast(0f),
            (contentHeightDp - duelChromeDp(contentHeightDp)).coerceAtLeast(0f)
        )

    /** The smaller side of one pad for [columns] (2: a 2 x 2 grid, 4: a single row of four) in an area of [w] x [h]. */
    private fun sideFor(w: Float, h: Float, columns: Int): Float {
        val rows = EchoPads.COUNT / columns
        val gap = min(w, h) * GAP
        return min((w - gap * (columns - 1)) / columns, (h - gap * (rows - 1)) / rows)
    }

    /** 4 (one row) when a wide, short area gives bigger pads that way than a 2 x 2 grid does, otherwise 2. */
    fun columnsFor(w: Float, h: Float): Int = if (sideFor(w, h, 4) > sideFor(w, h, 2) + 1f) 4 else 2

    /** The smaller side of one pad in the better of the two arrangements for an area of [w] x [h]. */
    fun bestPadSide(w: Float, h: Float): Float = sideFor(w, h, columnsFor(w, h))

    /**
     * The pad's rectangle as (left, top, right, bottom) inside an area of [w] x [h] whose corner is (0, 0).
     * [columns] is 2 (a 2 x 2 grid, the default) or 4 (a single row), see [columnsFor].
     */
    fun rect(index: Int, w: Float, h: Float, columns: Int = 2): FloatArray {
        val cols = if (columns == 4) 4 else 2
        val rows = EchoPads.COUNT / cols
        val gap = min(w, h) * GAP
        val cw = (w - gap * (cols - 1)) / cols
        val ch = (h - gap * (rows - 1)) / rows
        val col = index % cols
        val row = index / cols
        val l = col * (cw + gap)
        val t = row * (ch + gap)
        return floatArrayOf(l, t, l + cw, t + ch)
    }

    /** The smaller side of one pad in an area of [w] x [h]. */
    fun padSide(w: Float, h: Float): Float {
        val r = rect(0, w, h)
        return min(r[2] - r[0], r[3] - r[1])
    }

    /** The pad under ([x], [y]) in the area, or -1 for the gap or outside. */
    fun padAt(x: Float, y: Float, w: Float, h: Float, columns: Int = 2): Int {
        for (i in 0 until EchoPads.COUNT) {
            val r = rect(i, w, h, columns)
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return i
        }
        return -1
    }
}
