package com.gamesuite.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A reusable, physics-integrated particle burst — generalizing AirHockeyScreen's own
 * `GoalParticle` system (the richest of this app's independent hand-rolled particle effects)
 * into one shared primitive, the same reasoning [PremiumShaders]/[CameraShake] already
 * established: built once here instead of hand-rolled again by every new game that wants one.
 *
 * UNO's `ConfettiOverlay` and Solitaire's `CardBackFanFlourish` deliberately stay their own
 * bespoke effects, not migrated onto this — both are a genuinely different SHAPE (a fixed,
 * choreographed formula driven by one shared elapsed-time value, every piece's position a pure
 * function of that one clock) from a real velocity/gravity/fade burst, not the same primitive
 * wearing a different color. [ParticleBurst] generalizes the "real per-particle physics" shape
 * specifically — AirHockeyScreen's own goal-celebration burst, and any future game wanting the
 * same kind of effect (an enemy's death, a projectile's impact, a captured piece).
 *
 * Each [BurstParticle] carries real position/velocity/life, integrated every frame by [tick]
 * with real gravity and linear fade — the same simulation AirHockeyScreen's own goal burst
 * already runs, factored out so a new screen can spawn one without hand-rolling its own
 * `withFrameNanos` loop and particle data class. [particles] is a public `mutableStateOf`, the
 * same idiom every `GameModule`'s own `state` field in this app already uses, so reading it
 * from a Composable (e.g. inside a `Canvas` draw block) recomposes/redraws correctly whenever
 * [spawn] or [tick] change it.
 *
 * [random] is injectable purely for deterministic unit testing ([ParticleBurstTest]) — no game
 * in this app needs a *reproducible* burst the way a daily-seeded puzzle needs a reproducible
 * board, so every real call site should simply use the default.
 */
class ParticleBurst(private val random: Random = Random.Default) {

    val particles = mutableStateOf<List<BurstParticle>>(emptyList())

    /**
     * Spawns [count] new particles from [origin], each with an independently randomized
     * direction (within [angleRangeRadians], default a full circle) and speed (within
     * [speedRange]), living a randomized duration within [lifeRangeSeconds], colored by a
     * random pick from [colors] (a single-element list is fine for a uniform burst). Matches
     * AirHockeyGame's own goal-burst spawn shape (8-14 particles, randomized angle across a
     * full circle, gravity 1.4f in the same normalized-per-second units this app's physics
     * games already use for velocity).
     */
    fun spawn(
        origin: Offset,
        count: Int,
        colors: List<Color>,
        speedRange: ClosedFloatingPointRange<Float> = 0.35f..0.85f,
        lifeRangeSeconds: ClosedFloatingPointRange<Float> = 0.5f..0.75f,
        gravity: Float = 1.4f,
        angleRangeRadians: ClosedFloatingPointRange<Float> = 0f..(2f * Math.PI.toFloat())
    ) {
        require(colors.isNotEmpty()) { "spawn() needs at least one color" }
        if (count <= 0) return
        val newOnes = List(count) {
            val angle = randomInRange(angleRangeRadians)
            val speed = randomInRange(speedRange)
            val maxLife = randomInRange(lifeRangeSeconds)
            BurstParticle(
                pos = origin,
                vel = Offset(cos(angle) * speed, sin(angle) * speed),
                life = maxLife,
                maxLife = maxLife,
                gravity = gravity,
                color = colors[random.nextInt(colors.size)]
            )
        }
        particles.value = particles.value + newOnes
    }

    /**
     * Advances every live particle by [dtSeconds] of real elapsed time — gravity pulls `vel.y`
     * down, position integrates from velocity, life ticks down; anything reaching zero life is
     * dropped. Call this from a steady-state per-frame loop independent of whatever triggers
     * [spawn] (see [rememberParticleBurst]) — so an in-flight burst keeps animating smoothly
     * regardless of what else recomposes, the same independence AirHockeyScreen's own particle
     * loop already relies on. A no-op while there are no live particles, so an idle screen isn't
     * paying for a per-frame list rebuild it doesn't need.
     */
    fun tick(dtSeconds: Float) {
        if (particles.value.isEmpty() || dtSeconds <= 0f) return
        particles.value = particles.value.mapNotNull { p ->
            val newLife = p.life - dtSeconds
            if (newLife <= 0f) return@mapNotNull null
            val newVel = Offset(p.vel.x, p.vel.y + p.gravity * dtSeconds)
            p.copy(pos = Offset(p.pos.x + newVel.x * dtSeconds, p.pos.y + newVel.y * dtSeconds), vel = newVel, life = newLife)
        }
    }

    /** Immediately discards every live particle — e.g. when leaving a screen mid-burst. */
    fun clear() {
        particles.value = emptyList()
    }

    private fun randomInRange(range: ClosedFloatingPointRange<Float>): Float =
        range.start + random.nextFloat() * (range.endInclusive - range.start)
}

/**
 * One live particle — real position/velocity/life/gravity, integrated by [ParticleBurst.tick].
 * Rendering is deliberately left to the caller (a plain `drawCircle`/`drawRect` inside its own
 * `Canvas`, at whatever radius/coordinate-space convention that screen already uses — this
 * mirrors [CameraShake], which hands back a value/offset rather than drawing anything itself)
 * since a burst's visual shape (circle vs. spark vs. shard) is legitimately per-game, but
 * [lifeFraction] is provided since every consumer wants the same fade-alpha computation.
 */
data class BurstParticle(
    val pos: Offset,
    val vel: Offset,
    val life: Float,
    val maxLife: Float,
    val gravity: Float,
    val color: Color
) {
    /** 1f at spawn, decaying to 0f at death — the natural fade-alpha fraction every consumer
     *  wants for drawing (e.g. `color.copy(alpha = particle.lifeFraction)`). */
    val lifeFraction: Float get() = (life / maxLife).coerceIn(0f, 1f)
}

/**
 * Creates a [ParticleBurst] and drives its [ParticleBurst.tick] from a steady-state per-frame
 * loop for the lifetime of the composition — the same "independent of whatever triggers a
 * spawn" loop shape AirHockeyScreen's own particle system already uses. Callers still call
 * [ParticleBurst.spawn] themselves from whatever `LaunchedEffect` reacts to the real game event
 * (a kill, an impact); this only owns the physics-advancement loop.
 */
@Composable
fun rememberParticleBurst(): ParticleBurst {
    val burst = remember { ParticleBurst() }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) burst.tick((nanos - lastNanos) / 1_000_000_000f)
                lastNanos = nanos
            }
        }
    }
    return burst
}
