package com.gamesuite.games.shootinggallery

import kotlinx.serialization.Serializable
import kotlin.random.Random

/*
 * Shooting Gallery rules: a seeded range of moving targets and a replayable shot log.
 *
 * A turn is played in real time on the shooter's own device, but what COUNTS is computed from the
 * shot log alone: [RangeRun] replays the taps against the targets of the range (a pure function of
 * the match seed, the round number and the pace), applying the magazine, the reload and the combo.
 * The live screen drives the very same [RangeRun], so what you see on the range is exactly what the
 * host will score. Trigonometry uses StrictMath, so a target is in the same place on every device.
 *
 * Range coordinates: x 0..1000 left to right, y 0..600 top to bottom.
 */
object GalleryWorld {
    const val W = 1000
    const val H = 600
    const val MAG = 6
    const val RELOAD_MS = 1500
    const val RELOAD_MS_RELAXED = 900
    const val DURATION_MS = 30_000
    const val DURATION_MS_RELAXED = 40_000
    const val MAX_SHOTS = 240
    const val RELOAD_X = -1
    const val STAR_POINTS = 50
    const val DECOY_PENALTY = 30
    const val MAX_ROUNDS = 9
}

object TargetKind {
    const val DUCK = "duck"
    const val RING = "ring"
    const val STAR = "star"
    const val DECOY = "decoy"
    val ALL = setOf(DUCK, RING, STAR, DECOY)
}

/** One target: where it enters, how it moves and how big it is (the hit box already includes the pace's size boost). */
data class GalleryTarget(
    val id: Int,
    val kind: String,
    val spawnMs: Int,
    val dir: Int,
    val speed: Double,
    val y0: Double,
    val bob: Double,
    val bobHz: Double,
    val phase: Double,
    val rx: Double,
    val ry: Double,
    /** Base score (ducks 10 or 25, star 50); rings score by zone, decoys cost points. */
    val points: Int
) {
    val exitMs: Int get() = spawnMs + (((GalleryWorld.W + 2 * rx) / speed) * 1000.0).toInt()

    fun aliveAt(t: Int) = t >= spawnMs && t <= exitMs

    fun xAt(t: Int): Double {
        val dt = (t - spawnMs) / 1000.0
        val start = if (dir > 0) -rx else GalleryWorld.W + rx
        return start + dir * speed * dt
    }

    fun yAt(t: Int): Double {
        val dt = (t - spawnMs) / 1000.0
        return y0 + bob * StrictMath.sin(2.0 * StrictMath.PI * bobHz * dt + phase)
    }

    /** 0 at the centre, 1 at the edge of the hit ellipse, more outside. */
    fun normDist(t: Int, x: Double, y: Double): Double {
        val dx = (x - xAt(t)) / rx
        val dy = (y - yAt(t)) / ry
        return StrictMath.sqrt(dx * dx + dy * dy)
    }
}

/** Which range: the same seed and round give every player the same targets. */
data class RangeConfig(val seed: Long, val round: Int, val relaxed: Boolean) {
    val durationMs: Int get() = if (relaxed) GalleryWorld.DURATION_MS_RELAXED else GalleryWorld.DURATION_MS
    val reloadMs: Int get() = if (relaxed) GalleryWorld.RELOAD_MS_RELAXED else GalleryWorld.RELOAD_MS
}

object GalleryRange {
    private val laneY = doubleArrayOf(150.0, 300.0, 450.0)

    /** The targets of a range, sorted by spawn time. Pure and deterministic. */
    fun targets(c: RangeConfig): List<GalleryTarget> {
        val rng = Random(c.seed * 1_000_003L + c.round.toLong() * 7_919L + 11L)
        val speedMult = (1.0 + 0.12 * c.round).coerceAtMost(1.6) * (if (c.relaxed) 0.6 else 1.0)
        val size = if (c.relaxed) 1.35 else 1.0
        val decoyChance = (18 + 3 * c.round).coerceAtMost(34)
        val out = ArrayList<GalleryTarget>()
        var t = 400
        var id = 0
        while (t < c.durationMs - 2_500) {
            val lane = rng.nextInt(3)
            val dir = if (rng.nextBoolean()) 1 else -1
            val roll = rng.nextInt(100)
            val kind = when {
                id < 3 -> if (roll < 70) TargetKind.DUCK else TargetKind.RING
                roll < decoyChance -> TargetKind.DECOY
                roll < decoyChance + 12 -> TargetKind.STAR
                roll < decoyChance + 36 -> TargetKind.RING
                else -> TargetKind.DUCK
            }
            val small = kind == TargetKind.DUCK && rng.nextInt(4) == 0
            val target = when (kind) {
                TargetKind.RING -> GalleryTarget(id, kind, t, dir, (90 + rng.nextInt(50)) * speedMult, laneY[lane],
                    40.0, 0.2 + rng.nextInt(30) / 100.0, rng.nextInt(628) / 100.0, 52.0 * size, 52.0 * size, 25)
                TargetKind.STAR -> GalleryTarget(id, kind, t, dir, (300 + rng.nextInt(100)) * speedMult, laneY[lane],
                    60.0, 0.4 + rng.nextInt(40) / 100.0, rng.nextInt(628) / 100.0, 28.0 * size, 28.0 * size, GalleryWorld.STAR_POINTS)
                TargetKind.DECOY -> GalleryTarget(id, kind, t, dir, (130 + rng.nextInt(90)) * speedMult, laneY[lane],
                    15.0, 0.3 + rng.nextInt(30) / 100.0, rng.nextInt(628) / 100.0, 32.0 * size, 32.0 * size, -GalleryWorld.DECOY_PENALTY)
                else -> GalleryTarget(id, kind, t, dir, (150 + rng.nextInt(110)) * speedMult * (if (small) 1.25 else 1.0), laneY[lane],
                    22.0, 0.3 + rng.nextInt(50) / 100.0, rng.nextInt(628) / 100.0,
                    (if (small) 24.0 else 36.0) * size, (if (small) 20.0 else 30.0) * size, if (small) 25 else 10)
            }
            out += target
            id++
            t += ((600 + rng.nextInt(700)) / (1.0 + 0.08 * c.round)).toInt().coerceAtLeast(250)
        }
        return out
    }

    /** Points for hitting [target] at normalised distance [d] from its centre (rings are scored by zone). */
    fun basePoints(target: GalleryTarget, d: Double): Int = when (target.kind) {
        TargetKind.RING -> if (d < 0.25) 50 else if (d < 0.6) 25 else 10
        else -> target.points
    }

    fun multiplierFor(streak: Int): Int = 1 + minOf(3, streak / 3)

    /** Structural check of a submitted log: sizes, bounds and non-decreasing times. */
    fun validLog(c: RangeConfig, shots: List<GalleryShot>): Boolean {
        if (shots.size > GalleryWorld.MAX_SHOTS) return false
        var last = 0
        for (s in shots) {
            if (s.t < last || s.t > c.durationMs) return false
            if (s.x != GalleryWorld.RELOAD_X && s.x !in 0..GalleryWorld.W) return false
            if (s.y !in 0..GalleryWorld.H) return false
            last = s.t
        }
        return true
    }

    /** The authoritative score of a log. Returns null for a malformed log. */
    fun play(c: RangeConfig, shots: List<GalleryShot>): TurnResult? {
        if (!validLog(c, shots)) return null
        val run = RangeRun(c)
        shots.forEach { run.act(it.t, it.x, it.y) }
        return run.result()
    }
}

@Serializable
data class GalleryShot(val t: Int, val x: Int, val y: Int)

@Serializable
data class TurnResult(
    val score: Int,
    val hits: Int,
    /** Shots that left the barrel (dry clicks while reloading are not counted). */
    val fired: Int,
    val bestStreak: Int,
    val stars: Int,
    val decoys: Int
)

enum class ShotKind { HIT, MISS, DRY, RELOAD, IGNORED }

class ShotOutcome(val kind: ShotKind, val targetId: Int = -1, val points: Int = 0, val multiplier: Int = 1, val zone: Int = 0)

/** The magazine, the reload, the combo and the score of one turn on one range, advanced shot by shot. */
class RangeRun(val config: RangeConfig) {
    val targets: List<GalleryTarget> = GalleryRange.targets(config)
    var ammo = GalleryWorld.MAG
        private set
    var reloadDoneAt = -1
        private set
    var streak = 0
        private set
    var bestStreak = 0
        private set
    var score = 0
        private set
    var hits = 0
        private set
    var fired = 0
        private set
    var stars = 0
        private set
    var decoys = 0
        private set
    private val gone = HashSet<Int>()

    fun isGone(id: Int) = id in gone

    /** True while the magazine is being refilled at time [t]. */
    fun reloading(t: Int) = reloadDoneAt >= 0 && t < reloadDoneAt

    private fun settleReload(t: Int) {
        if (reloadDoneAt in 0..t) { ammo = GalleryWorld.MAG; reloadDoneAt = -1 }
    }

    /** The ammo showing at time [t]. */
    fun ammoAt(t: Int): Int = if (reloadDoneAt in 0..t) GalleryWorld.MAG else ammo

    fun reload(t: Int): ShotOutcome = act(t, GalleryWorld.RELOAD_X, 0)

    fun shoot(t: Int, x: Int, y: Int): ShotOutcome = act(t, x, y)

    fun act(t: Int, x: Int, y: Int): ShotOutcome {
        if (t < 0 || t > config.durationMs) return ShotOutcome(ShotKind.IGNORED)
        settleReload(t)
        if (x == GalleryWorld.RELOAD_X) {
            if (ammo < GalleryWorld.MAG && reloadDoneAt < 0) {
                reloadDoneAt = t + config.reloadMs
                return ShotOutcome(ShotKind.RELOAD)
            }
            return ShotOutcome(ShotKind.IGNORED)
        }
        if (reloadDoneAt >= 0) return ShotOutcome(ShotKind.DRY)
        ammo--
        fired++
        if (ammo == 0) reloadDoneAt = t + config.reloadMs
        var best: GalleryTarget? = null
        var bestD = Double.MAX_VALUE
        for (tg in targets) {
            if (tg.spawnMs > t) break
            if (tg.id in gone || !tg.aliveAt(t)) continue
            val d = tg.normDist(t, x.toDouble(), y.toDouble())
            if (d <= 1.0 && d < bestD) { best = tg; bestD = d }
        }
        if (best == null) {
            streak = 0
            return ShotOutcome(ShotKind.MISS)
        }
        gone += best.id
        if (best.kind == TargetKind.DECOY) {
            decoys++
            streak = 0
            score = (score - GalleryWorld.DECOY_PENALTY).coerceAtLeast(0)
            return ShotOutcome(ShotKind.HIT, best.id, -GalleryWorld.DECOY_PENALTY, 1)
        }
        val mult = GalleryRange.multiplierFor(streak)
        val base = GalleryRange.basePoints(best, bestD)
        val pts = base * mult
        score += pts
        hits++
        streak++
        if (streak > bestStreak) bestStreak = streak
        if (best.kind == TargetKind.STAR) {
            stars++
            ammo = GalleryWorld.MAG
            reloadDoneAt = -1
        }
        val zone = if (best.kind == TargetKind.RING) (if (bestD < 0.25) 3 else if (bestD < 0.6) 2 else 1) else 0
        return ShotOutcome(ShotKind.HIT, best.id, pts, mult, zone)
    }

    fun result() = TurnResult(score, hits, fired, bestStreak, stars, decoys)
}
