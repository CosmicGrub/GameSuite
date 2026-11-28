package com.gamesuite.games.stackzoo

import com.gamesuite.settings.CpuDifficulty

/** A helper's decision: which piece, and where and how turned to let go of it (quantised exactly like a person's release). */
data class ZooMove(val pieceId: Int, val xQ: Int, val angleQ: Int)

/**
 * The computer helper ("zoo keeper") of Stack Zoo, docs/design/kids-games/stack-zoo.md section 2.10. It uses only the
 * public API (`choose`, `release`): [plan] reads a copy of the engine rebuilt from a save, so it can run on a worker thread
 * while the real engine is untouched. It is a pure function of (save, seats, level): the same inputs give the same move.
 *
 * Levels: Easy picks any animal and a random spot (blunders one turn in five), Medium scores 11 x positions across
 * the flat angles of the animal with flatness, support, balance and overhang terms, Hard does the same with less noise and
 * then rolls out its best candidates on the scratch copy and keeps the steadiest one.
 */
object ZooHelper {
    /** The helper thinks at least this long before it starts to drag (the screen's job). */
    const val MIN_THINK_MS = ZooRules.BOT_MIN_THINK_MS

    /** The drag animation the screen plays between the shelf and the chosen spot. */
    const val DRAG_MS = 900L

    internal class Level(val noiseX: Double, val noiseDeg: Double, val blunder: Double, val rollouts: Int)

    internal fun level(d: CpuDifficulty): Level = when (d) {
        CpuDifficulty.EASY -> Level(0.60, 25.0, 0.20, 0)
        CpuDifficulty.MEDIUM -> Level(0.25, 8.0, 0.08, 0)
        CpuDifficulty.HARD -> Level(0.08, 2.0, 0.03, 4)
    }

    /** Rebuilds an engine from [save] (with [seats]) and decides the active seat's move. Null when the save is unusable or it is not a turn to choose. */
    fun plan(save: ZooSave, seats: List<ZooSeat>, difficulty: CpuDifficulty): ZooMove? {
        val e = scratchEngine(save, seats, difficulty) ?: return null
        return planOn(e, difficulty)
    }

    internal fun scratchEngine(save: ZooSave, seats: List<ZooSeat>, difficulty: CpuDifficulty): ZooEngine? {
        val e = ZooEngine()
        e.setup(
            ZooConfig(
                mode = ZooMode.fromId(save.mode), goal = ZooGoal.fromId(save.goal), length = ZooLength.fromId(save.length),
                assist = save.assist, relaxed = save.relaxed, oneSwitch = save.oneSwitch, difficulty = difficulty, seed = save.seed
            ),
            seats
        )
        return if (e.restore(save)) e else null
    }

    internal fun planOn(e: ZooEngine, difficulty: CpuDifficulty): ZooMove? {
        val st = e.state()
        if (st.phase != ZooPhase.CHOOSING && st.phase != ZooPhase.POSITIONING) return null
        if (st.offer.isEmpty()) return null
        val lv = level(difficulty)
        val rng = ZooRng(mix(e.seedValue, e.turnNumber.toLong(), e.activeSeat.toLong()))
        val tower = e.world.towerHeight()

        // 1. the piece
        val piece = pickPiece(st, tower, difficulty, rng)
        val animal = e.pieceAnimal[piece]
        val shape = ZooShapes.animals[animal]

        // 2. a blunder: a random spot and angle, as a tired child would drop it
        if (rng.nextDouble() < lv.blunder) {
            return ZooMove(piece, ZooPlacer.quantizeX((rng.nextDouble() * 2.0 - 1.0) * 3.5), ZooPlacer.quantizeAngle((rng.nextDouble() * 2.0 - 1.0) * Math.PI))
        }
        if (difficulty == CpuDifficulty.EASY) {
            val x = (rng.nextDouble() * 2.0 - 1.0) * 3.5
            return ZooMove(piece, ZooPlacer.quantizeX(x), ZooPlacer.quantizeAngle(noisyAngle(shape, rng, lv)))
        }

        // 3. candidates: 11 x positions across the animal's flat angles, scored
        val angles = if (shape.flatAngles.isEmpty()) doubleArrayOf(0.0) else shape.flatAngles
        data class Cand(val x: Double, val angle: Double, val score: Double)
        val cands = ArrayList<Cand>()
        for (a in angles) {
            for (i in 0..10) {
                val x = -2.5 + 0.5 * i
                cands += Cand(x, a, score(e, shape, x, a))
            }
        }
        cands.sortWith(compareByDescending<Cand> { it.score }.thenBy { Math.abs(it.x) }.thenBy { it.angle })
        var best = cands.first()
        if (lv.rollouts > 0 && cands.size > 1) {
            var bestRoll = Double.POSITIVE_INFINITY
            for (c in cands.take(lv.rollouts * 2)) {
                val r = rollout(e, difficulty, piece, c.x, c.angle)
                if (r < bestRoll - 1e-9) { bestRoll = r; best = c }
            }
        }
        // 4. noise, like a hand that is not quite steady
        val nx = best.x + gaussian(rng) * lv.noiseX
        val na = best.angle + Math.toRadians(gaussian(rng) * lv.noiseDeg)
        return ZooMove(piece, ZooPlacer.quantizeX(nx), ZooPlacer.quantizeAngle(na))
    }

    private fun noisyAngle(shape: ZooShape, rng: ZooRng, lv: Level): Double {
        val base = if (shape.flatAngles.isEmpty()) 0.0 else shape.flatAngles[rng.nextInt(shape.flatAngles.size)]
        return base + Math.toRadians(gaussian(rng) * lv.noiseDeg)
    }

    private fun pickPiece(st: ZooState, tower: Double, d: CpuDifficulty, rng: ZooRng): Int {
        val offer = st.offer
        val animals = st.offerAnimals
        return when (d) {
            CpuDifficulty.EASY -> offer[rng.nextInt(offer.size)]
            CpuDifficulty.MEDIUM -> {
                val pool = if (tower > 3.0) offer.indices.filter { ZooAnimals.all[animals[it]].tier == ZooTier.EASY }.ifEmpty { offer.indices.toList() } else offer.indices.toList()
                offer[pool[rng.nextInt(pool.size)]]
            }
            CpuDifficulty.HARD -> {
                var bestIdx = 0
                var bestKey = Double.NEGATIVE_INFINITY
                for (i in offer.indices) {
                    val sh = ZooShapes.animals[animals[i]]
                    val flat = sh.flatLengths.maxOrNull() ?: 0.0
                    val tier = ZooAnimals.all[animals[i]].tier.ordinal
                    val key = if (tower < 2.0) sh.mass * (sh.maxX - sh.minX) + (if (flat >= 1.2) 1.0 else 0.0)
                    else (if (flat >= 1.2) 10.0 else 0.0) - 3.0 * tier + flat
                    if (key > bestKey + 1e-9) { bestKey = key; bestIdx = i }
                }
                offer[bestIdx]
            }
        }
    }

    /** flatness + support + balance - overhang (section 2.10). Higher is better. */
    internal fun score(e: ZooEngine, shape: ZooShape, x: Double, angle: Double): Double {
        val w = e.world
        val width = (shape.maxX - shape.minX).coerceAtLeast(0.1)
        // flatness: the length of the hull edge that would lie flat at this angle, over the animal's width
        var flat = 0.0
        for (i in shape.flatAngles.indices) {
            if (Math.abs(ZooShape.wrapAngle(shape.flatAngles[i] - angle)) < 0.02) flat = shape.flatLengths[i] / width
        }
        if (flat > 1.0) flat = 1.0
        // support: overlap with the top of the highest standing animal (1 on an empty log)
        var topY = -1e30
        var topX = 0.0
        var topHalf = 0.0
        var sumMx = 0.0
        for (i in 0 until ZooPhysics.MAX_BODIES) {
            if (!w.live[i] || w.fallen[i]) continue
            sumMx += w.mass[i] * w.x[i]
            if (w.y[i] > topY) {
                topY = w.y[i]; topX = w.x[i]
                val sh = ZooShapes.animals[w.animal[i]]
                topHalf = (sh.maxX - sh.minX) * 0.5
            }
        }
        val support = if (topY < -1e29) 1.0 else {
            val lo = maxOf(x - width * 0.5, topX - topHalf)
            val hi = minOf(x + width * 0.5, topX + topHalf)
            ((hi - lo) / width).coerceIn(0.0, 1.0)
        }
        val torque = Math.abs(sumMx + shape.mass * x)
        val balance = 1.0 - (torque / 90.0).coerceIn(0.0, 1.0)
        val overhang = maxOf(0.0, Math.abs(x) + width * 0.5 - ZooPhysics.BASE_WIDTH * 0.5) / width
        return 3.0 * flat + 2.0 * support + 1.5 * balance - overhang
    }

    /** Places the candidate on a copy and lets it settle; returns how bad it was (a fall costs a lot, a big wobble a little). Lower is better. */
    private fun rollout(e: ZooEngine, d: CpuDifficulty, piece: Int, x: Double, angle: Double): Double {
        val save = e.toSave() ?: return 0.0
        val scratch = scratchEngine(save, e.seatList, d) ?: return 0.0
        if (!scratch.choose(piece)) return 0.0
        scratch.release(ZooPlacer.quantizeX(x), ZooPlacer.quantizeAngle(angle))
        var peak = 0.0
        var steps = 0
        while (scratch.phase == ZooPhase.SETTLING && steps < ROLLOUT_STEPS) {
            scratch.advance(5)
            steps += 5
            val a = Math.abs(scratch.world.a[ZooWorld.LOG])
            if (a > peak) peak = a
        }
        return scratch.state().fellLastTurn * 10.0 + (if (scratch.phase == ZooPhase.SETTLING) 1.0 else 0.0) + peak
    }

    private const val ROLLOUT_STEPS = 360

    private fun gaussian(rng: ZooRng): Double {
        // sum of three uniforms, centred and scaled to roughly unit variance: cheap and deterministic
        return (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * 2.0
    }

    private fun mix(a: Long, b: Long, c: Long): Long {
        var h = a xor (b * -7046029254386353131L) xor (c * -4658895280553007687L)
        h = (h xor (h ushr 31)) * -4658895280553007687L
        return if (h == 0L) 1L else h
    }
}
