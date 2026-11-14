package com.gamesuite.games.bridgebuilders

import com.gamesuite.settings.CpuDifficulty
import kotlin.math.floor
import kotlin.random.Random

/** How big the bridge job is: how many planks, how wide the green spot, how fast the cart and the water go. */
enum class BridgeBand(val id: String) {
    LITTLE("little"), MEDIUM("medium"), BIG("big");

    companion object {
        fun fromId(id: String?): BridgeBand = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/**
 * The numbers of one [BridgeBand].
 * [spans]: planks every lane needs to reach the far bank. [window]: width of the green spot as a fraction of the cart's
 * bar. [passMs]: how long the cart takes to slide from one end of its bar to the other. [waterSeconds]: how long the
 * water takes to rise from nothing to covering the bridge, if nobody places a plank. [stumbleMs]: how long a cart rests
 * after a missed tap. [crossMs]: how long the friends take to walk over a sturdy bridge.
 */
data class BridgeBandParams(
    val spans: Int,
    val window: Double,
    val passMs: Long,
    val waterSeconds: Double,
    val stumbleMs: Long,
    val crossMs: Long
)

/** What a tap did to a lane. [NONE] is no tap yet. */
object BridgeTier {
    const val NONE = ""
    const val PERFECT = "perfect"
    const val GOOD = "good"
    const val WOBBLY = "wobbly"
    const val MISS = "miss"
}

/** What a lane is doing right now. */
object BridgeLaneStatus {
    /** The cart is sliding: a tap can place a plank. */
    const val MOVING = "moving"

    /** Two planks ahead of the slowest lane: the cart waits for the friends (the rope is tight). */
    const val WAITING = "waiting"

    /** The cart is resting for a moment after a missed tap. */
    const val STUMBLED = "stumbled"

    /** Every plank is down. */
    const val DONE = "done"
}

/** What the last change to the shared bridge was. Only milestones: they are what a screen reader announces. */
object BridgeEvent {
    const val START = "start"
    const val GO = "go"

    /** Every lane now has at least `eventArg` planks. */
    const val ROW = "row"
    const val WATER_HALF = "water_half"
    const val WATER_HIGH = "water_high"
    const val CROSS = "cross"
    const val WIN = "win"
    const val LOSE = "lose"
}

/** What a tap on a lane did, for the screen's sound and haptics. */
enum class BridgeTapResult(val placed: Boolean) {
    PERFECT(true), GOOD(true), WOBBLY(true), MISS(false),
    NOT_BUILDING(false), WAITING(false), STUMBLED(false), DONE(false), BOT_SEAT(false), BAD_SEAT(false)
}

/** What a helper robot of a given level is like. All pure data. */
data class BridgeBotParams(val missChance: Double, val tolMin: Double, val tolMax: Double, val reactMs: Long)

/**
 * The rules of Bridge Builders as pure functions and constants (no clock, no random of their own), so every number
 * the game depends on is unit tested. The cart's position is a triangle wave of a "phase" that only grows, which lets
 * the engine know the exact position at the exact moment of a tap.
 */
object BridgeRules {
    const val MIN_LANES = 2
    const val MAX_LANES = 4

    /** The fixed simulation step, in milliseconds. */
    const val STEP_MS = 10L
    const val MAX_CATCHUP_STEPS = 100
    const val COUNTDOWN_MS = 3000L

    /** The cooperation rule: no lane may be more than this many planks ahead of the slowest lane. */
    const val MAX_LEAD = 2

    /** A tap within this fraction of the half-width of the green spot is a perfect plank. */
    const val PERFECT_FRACTION = 0.25

    /** The quality of a plank placed at the very edge of the green spot (and the least a relaxed tap gives). */
    const val EDGE_QUALITY = 0.4
    const val RELAXED_MIN_QUALITY = 0.4

    /** In relaxed mode the cart is slower and the water takes longer. */
    const val RELAXED_SPEED = 0.75
    const val RELAXED_WATER_FACTOR = 1.25

    /** A handicap widens the green spot by this fraction; the widest handicap a profile may ask for is [MAX_HANDICAP]. */
    const val HANDICAP_BONUS = 0.5
    const val MAX_HANDICAP = 1.0
    const val MAX_WINDOW = 0.6
    const val WINDOW_MARGIN = 0.04

    /** A plank of quality 1 pushes the water back by this fraction of the bridge height. */
    const val EBB_PER_QUALITY = 0.02

    /** Water levels that are announced once per try. */
    const val WATER_HALF_AT = 0.5
    const val WATER_HIGH_AT = 0.8

    /** The crossing takes this much of its base time with a weak bridge, and this much with the sturdiest. */
    const val CROSS_SLOW = 1.5
    const val CROSS_FAST = 0.8

    /** The speed factor game speed settings may apply to the cart (Slow = slower cart). */
    const val MIN_PACE = 0.4
    const val MAX_PACE = 1.8

    const val SAVE_VERSION = 1

    fun band(b: BridgeBand): BridgeBandParams = when (b) {
        BridgeBand.LITTLE -> BridgeBandParams(spans = 4, window = 0.34, passMs = 1800, waterSeconds = 110.0, stumbleMs = 300, crossMs = 4500)
        BridgeBand.MEDIUM -> BridgeBandParams(spans = 5, window = 0.24, passMs = 1300, waterSeconds = 85.0, stumbleMs = 450, crossMs = 4000)
        BridgeBand.BIG -> BridgeBandParams(spans = 6, window = 0.17, passMs = 950, waterSeconds = 70.0, stumbleMs = 600, crossMs = 3500)
    }

    fun bot(level: CpuDifficulty): BridgeBotParams = when (level) {
        CpuDifficulty.EASY -> BridgeBotParams(missChance = 0.25, tolMin = 0.75, tolMax = 1.0, reactMs = 700)
        CpuDifficulty.MEDIUM -> BridgeBotParams(missChance = 0.12, tolMin = 0.45, tolMax = 0.8, reactMs = 450)
        CpuDifficulty.HARD -> BridgeBotParams(missChance = 0.04, tolMin = 0.15, tolMax = 0.5, reactMs = 250)
    }

    /** 0 at one end of the bar, 1 at the other, back again: one full period per unit of [phase]. */
    fun triangle(phase: Double): Double {
        val p = phase - floor(phase)
        return if (p < 0.5) p * 2.0 else 2.0 - p * 2.0
    }

    /** How much the phase grows per millisecond: one period is two passes of the bar. [pace] is the game speed factor. */
    fun phasePerMs(band: BridgeBand, relaxed: Boolean, pace: Double): Double {
        val p = pace.coerceIn(MIN_PACE, MAX_PACE)
        return p * (if (relaxed) RELAXED_SPEED else 1.0) / (2.0 * band(band).passMs)
    }

    /** The water's rise in one millisecond, as a fraction of the bridge height. */
    fun waterPerMs(band: BridgeBand, relaxed: Boolean): Double =
        1.0 / (band(band).waterSeconds * (if (relaxed) RELAXED_WATER_FACTOR else 1.0) * 1000.0)

    /** The green spot's width for a lane with handicap [bonus] (0 = none). */
    fun windowWidth(band: BridgeBand, bonus: Double): Double =
        (band(band).window * (1.0 + bonus.coerceIn(0.0, MAX_HANDICAP))).coerceAtMost(MAX_WINDOW)

    /** A green spot's centre, anywhere that keeps the whole spot on the bar. */
    fun pickCentre(random: Random, width: Double): Double {
        val lo = width / 2.0 + WINDOW_MARGIN
        val hi = 1.0 - width / 2.0 - WINDOW_MARGIN
        if (hi <= lo) return 0.5
        return lo + random.nextDouble() * (hi - lo)
    }

    /** The sturdiness (0.4 at the edge, 1 in the middle) of a tap [dist] from the green spot's centre; 0 when outside it. */
    fun hitQuality(dist: Double, width: Double): Double {
        val half = width / 2.0
        if (half <= 0.0 || dist > half + 1e-9) return 0.0
        val r = dist / half
        if (r <= PERFECT_FRACTION) return 1.0
        return 1.0 - (1.0 - EDGE_QUALITY) * ((r - PERFECT_FRACTION) / (1.0 - PERFECT_FRACTION))
    }

    /** The sturdiness of a relaxed tap: never a miss, and the nearer the middle the sturdier (at least [RELAXED_MIN_QUALITY]). */
    fun relaxedQuality(dist: Double, width: Double): Double =
        maxOf(hitQuality(dist, width), (1.0 - dist).coerceIn(RELAXED_MIN_QUALITY, 1.0))

    fun tierOf(quality: Double): String = when {
        quality >= 0.999 -> BridgeTier.PERFECT
        quality >= 0.7 -> BridgeTier.GOOD
        else -> BridgeTier.WOBBLY
    }

    /** How long the friends take to cross: sturdier planks make it quicker. [strength] is 0..1. */
    fun crossMs(band: BridgeBand, strength: Double): Long {
        val f = CROSS_SLOW - (CROSS_SLOW - CROSS_FAST) * strength.coerceIn(0.0, 1.0)
        return (band(band).crossMs * f).toLong()
    }

    /** Whether lane planks [mine] is too far ahead of the slowest lane ([minPlanks]) to place another. */
    fun mustWait(mine: Int, minPlanks: Int, spans: Int): Boolean = mine < spans && mine - minPlanks >= MAX_LEAD
}

/** The per-lane green-spot bonuses of a "handicap" choice. */
object BridgeHandicap {
    /**
     * The handicap spec to use for a table of [seats] lanes from the Hub's three choices (one per table size, see
     * the Hub spec): [two] is the plain `handicap` extra (2 lanes), [three] / [four] the 3- and 4-lane ones. A seat
     * that does not exist is dropped by [parse]. The plain one is also the fallback for older saved launches.
     */
    fun fromExtras(two: String?, three: String?, four: String?, seats: Int): String =
        (when {
            seats <= 2 -> two
            seats == 3 -> three ?: two
            else -> four ?: two
        }) ?: "none"

    /**
     * "none" (or anything unknown): nobody. "p1".."p4": that lane gets the [BridgeRules.HANDICAP_BONUS]. A comma list with
     * one fraction per lane ("0,0.5,0,0.25") is what a player-profile screen may pass; each is clamped to 0..[BridgeRules.MAX_HANDICAP].
     */
    fun parse(spec: String?, seats: Int): List<Double> {
        val n = seats.coerceIn(BridgeRules.MIN_LANES, BridgeRules.MAX_LANES)
        val zero = List(n) { 0.0 }
        val s = spec?.trim()?.lowercase() ?: return zero
        if (s.isEmpty() || s == "none" || s.length > 40) return zero
        if (s.length == 2 && s[0] == 'p' && s[1] in '1'..'4') {
            val seat = s[1] - '1'
            return if (seat < n) List(n) { if (it == seat) BridgeRules.HANDICAP_BONUS else 0.0 } else zero
        }
        val parts = s.split(',')
        if (parts.size == n) {
            val values = parts.map { it.trim().toDoubleOrNull() }
            if (values.all { it != null && it.isFinite() }) return values.map { it!!.coerceIn(0.0, BridgeRules.MAX_HANDICAP) }
        }
        return zero
    }
}
