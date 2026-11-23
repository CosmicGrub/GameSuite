package com.gamesuite.style.gem

import com.gamesuite.games.gemcascade.Pos
import com.gamesuite.style.ArtTier
import kotlin.math.cos
import kotlin.math.sin

/** The look of a particle follows the art tier: squares (flat), soft blobs (2.5D), shards (3D). */
enum class ParticleShape { SQUARE, BLOB, SHARD }

fun particleShapeFor(tier: ArtTier): ParticleShape = when (tier) {
    ArtTier.FLAT -> ParticleShape.SQUARE; ArtTier.SOFT_25D -> ParticleShape.BLOB; ArtTier.FULL_3D -> ParticleShape.SHARD
}

/** One particle at time t (0..1 of its life), in CELL units relative to the burst centre. Pure and deterministic. */
data class ParticleState(val dx: Float, val dy: Float, val size: Float, val alpha: Float, val rotation: Float)

/** A burst the screen keeps alive for [lifeMs]; positions are computed analytically from (seed, index, t), so nothing is mutated per frame. */
class ActiveBurst(
    val col: Float,
    val row: Float,
    val argb: Long,
    val count: Int,
    val startMs: Long,
    val lifeMs: Int,
    val shape: ParticleShape,
    val seed: Int,
    /** How far (in cells) the fastest particle travels. */
    val reach: Float
)

object GemParticles {
    private fun mix(a: Int, b: Int): Int {
        var h = a * -0x61c88647 + b * 0x27d4eb2f
        h = h xor (h ushr 15); h *= -0x7a143595; h = h xor (h ushr 13); h *= -0x3d4d51cb; h = h xor (h ushr 16)
        return h
    }

    private fun unit(seed: Int, i: Int, salt: Int): Float = (mix(mix(seed, i), salt) ushr 8) / 16777216f

    fun state(seed: Int, i: Int, t: Float, reach: Float): ParticleState {
        val tt = t.coerceIn(0f, 1f)
        val ang = unit(seed, i, 1) * 6.2831855f
        val speed = (0.35f + 0.65f * unit(seed, i, 2)) * reach
        val ease = 1f - (1f - tt) * (1f - tt)           // fast out, slow in
        val dx = cos(ang) * speed * ease
        val dy = sin(ang) * speed * ease + 0.45f * tt * tt   // a little gravity
        val size = (0.10f + 0.10f * unit(seed, i, 3)) * (1f - 0.5f * tt)
        val alpha = (1f - tt * tt).coerceIn(0f, 1f)
        return ParticleState(dx, dy, size, alpha, unit(seed, i, 4) * 360f + 180f * tt)
    }

    /** Bursts for a clear of [cells]: (cell, count) pairs; the total never exceeds [budget] and is 0 when the budget is 0. */
    fun plan(cells: List<Pos>, budget: Int): List<Pair<Pos, Int>> {
        if (budget <= 0 || cells.isEmpty()) return emptyList()
        val per = (budget / cells.size).coerceAtMost(12)
        if (per == 0) return cells.take(budget).map { it to 1 }
        return cells.map { it to per }
    }
}
