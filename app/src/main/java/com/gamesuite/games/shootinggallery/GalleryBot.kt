package com.gamesuite.games.shootinggallery

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer shooter: it "plays" a range by producing a shot log, the same thing a person's taps
 * produce, so it is scored by exactly the same rules. The levels scale how fast it notices a target
 * (reaction), how far behind a moving target it aims (lag), how wide its aim scatters (a share of the
 * target's size), how quickly it can fire again, and whether it shoots decoys by mistake.
 */
object GalleryBot {
    private class Skill(
        val reactionMs: Int, val lagMs: Int, val scatter: Double, val gapMs: Int, val gapJitter: Int, val decoyMistake: Double
    )

    private fun skill(level: CpuDifficulty) = when (level) {
        CpuDifficulty.EASY -> Skill(850, 190, 1.5, 760, 340, 0.35)
        CpuDifficulty.MEDIUM -> Skill(480, 100, 0.9, 480, 200, 0.12)
        CpuDifficulty.HARD -> Skill(220, 40, 0.55, 300, 120, 0.0)
    }

    private fun priority(t: GalleryTarget) = when (t.kind) {
        TargetKind.STAR -> 3
        TargetKind.RING -> 2
        else -> 1
    }

    fun log(config: RangeConfig, level: CpuDifficulty, rng: Random): List<GalleryShot> {
        val sk = skill(level)
        val run = RangeRun(config)
        val out = ArrayList<GalleryShot>()
        var next = 300 + rng.nextInt(400)
        var t = 0
        // a relaxed range is slow and forgiving; the bot takes its time there too
        val slow = if (config.relaxed) 1.4 else 1.0
        while (t <= config.durationMs && out.size < GalleryWorld.MAX_SHOTS - 2) {
            if (t >= next) {
                val ammo = run.ammoAt(t)
                val visible = run.targets.filter { tg ->
                    tg.spawnMs <= t && tg.aliveAt(t) && !run.isGone(tg.id) &&
                        t - tg.spawnMs >= sk.reactionMs * slow && tg.xAt(t) in tg.rx * 0.4..GalleryWorld.W - tg.rx * 0.4 &&
                        (tg.kind != TargetKind.DECOY || rng.nextDouble() < sk.decoyMistake)
                }
                val pick = visible.maxWithOrNull(compareBy<GalleryTarget>({ priority(it) }, { -it.exitMs }))
                if (pick == null) {
                    if (ammo <= 2 && !run.reloading(t) && ammo < GalleryWorld.MAG) {
                        run.reload(t); out += GalleryShot(t, GalleryWorld.RELOAD_X, 0)
                        next = run.reloadDoneAt
                    } else next = t + 60
                } else {
                    val lagT = (t - (sk.lagMs * slow).toInt()).coerceAtLeast(pick.spawnMs)
                    val nx = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * 2.0
                    val ny = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * 2.0
                    val ax = (pick.xAt(lagT) + nx * sk.scatter * pick.rx).toInt().coerceIn(0, GalleryWorld.W)
                    val ay = (pick.yAt(lagT) + ny * sk.scatter * pick.ry).toInt().coerceIn(0, GalleryWorld.H)
                    val o = run.shoot(t, ax, ay)
                    if (o.kind != ShotKind.DRY && o.kind != ShotKind.IGNORED) out += GalleryShot(t, ax, ay)
                    next = t + ((sk.gapMs + rng.nextInt(sk.gapJitter + 1)) * slow).toInt()
                    if (run.reloadDoneAt > next) next = run.reloadDoneAt
                }
            }
            t += 40
        }
        return out
    }
}
