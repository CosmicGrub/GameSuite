package com.gamesuite.games.flickrink

import com.gamesuite.settings.CpuDifficulty

/**
 * The computer pal. Picks the flick for the active seat. Noise is injected from the seed so a replay plays the same match.
 *
 * Behaviour per level (docs: SCRIPT > Bot behaviour): EASY aims straight at the target with 9 degrees of angle noise and 14
 * points of power noise and never plans bank shots; MEDIUM tries the direct line and 24 bounce candidates with 4 degrees and 7
 * points of noise; HARD searches 160 candidates with 1.5 degrees and 3 points of noise. Candidates are scored with the real
 * simulation ([FlickRinkEngine.probe]). Budget: at most 150 000 sim steps per decision (HARD), 40 000 (MEDIUM), none (EASY).
 * The bot sees the true state: this game has no hidden information.
 */
interface FlickRinkBot {
    fun choose(engine: FlickRinkEngine, difficulty: CpuDifficulty, seed: Long): FlickInput
}

/** Per-level numbers of the bot table. */
object FlickRinkBotLevels {
    fun angleSigmaDegrees(d: CpuDifficulty): Double = when (d) { CpuDifficulty.EASY -> 9.0; CpuDifficulty.MEDIUM -> 4.0; CpuDifficulty.HARD -> 1.5 }
    fun powerSigma(d: CpuDifficulty): Double = when (d) { CpuDifficulty.EASY -> 14.0; CpuDifficulty.MEDIUM -> 7.0; CpuDifficulty.HARD -> 3.0 }
    fun stepBudget(d: CpuDifficulty): Int = when (d) { CpuDifficulty.EASY -> 0; CpuDifficulty.MEDIUM -> 40_000; CpuDifficulty.HARD -> 150_000 }
    fun maxCandidates(d: CpuDifficulty): Int = when (d) { CpuDifficulty.EASY -> 1; CpuDifficulty.MEDIUM -> 25; CpuDifficulty.HARD -> 160 }

    /** The least time the "thinking" pose shows, in milliseconds. */
    fun thinkMillis(d: CpuDifficulty): Long = when (d) { CpuDifficulty.EASY -> 800L; CpuDifficulty.MEDIUM -> 1200L; CpuDifficulty.HARD -> 1600L }

    /** Easy flicks toward a random point of the target's half-plane this often. */
    const val EASY_RANDOM_SHARE = 0.15
}

/** The open-ice slide table (distance a lone pal slides for each power), measured with the real simulation once. */
object FlickRinkOpenIce {
    private val empty = RinkLevel("open", "open", FlickBand.LITTLE, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** Distance in rink units for power 0..100. */
    val distance: DoubleArray by lazy {
        val sim = FlickSim(empty, 1, true, false, false)
        DoubleArray(101) { p ->
            sim.resetRink()
            sim.place(0, 300.0, 919.0)
            sim.recordEvents = false
            sim.runFlick(0, 0.0, -FlickRinkRules.speedForPower(p), false)
            919.0 - sim.y[0]
        }
    }

    /** The smallest power whose open-ice slide reaches [d] units (100 when none does). */
    fun powerFor(d: Double): Int {
        val t = distance
        for (p in 0..100) if (t[p] >= d) return p
        return 100
    }
}

/**
 * Shared candidate generator and scorer: the bots use it and so does the level solver test.
 * Angles are tenths of a degree, powers 0..100.
 */
object FlickRinkPlanner {
    /** Angle in deci-degrees (0..3599) from ([fx], [fy]) to ([tx], [ty]). */
    fun angleTo(fx: Double, fy: Double, tx: Double, ty: Double): Int {
        val a = StrictMath.toDegrees(StrictMath.atan2(ty - fy, tx - fx))
        return Math.floorMod(Math.round(a * 10.0).toInt(), 3600)
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx; val dy = ay - by
        return StrictMath.sqrt(dx * dx + dy * dy)
    }

    /** One goal of the active body: where to go and how far past it is fine. */
    class Goal(val x: Double, val y: Double, val overshoot: Double)

    /** The goals of the active seat, most wanted first. Fish run: home when carrying, else the free fish nearest first, closed-gate pads, a carrier to bump (co-op). */
    fun goals(state: FlickRinkState, level: RinkLevel, coop: Boolean): List<Goal> {
        val out = ArrayList<Goal>()
        if (level.isBoard) {
            val t = level.target ?: return out
            out.add(Goal(t.x.toDouble(), t.y.toDouble(), 0.0))
            return out
        }
        val me = state.pals.getOrNull(state.activeSeat) ?: return out
        if (me.carrying) {
            out.add(Goal(FlickRinkRules.HOME_X.toDouble(), FlickRinkRules.HOME_Y.toDouble(), 0.0))
        } else {
            state.fish.filter { it.state == 0 }.sortedBy { dist(me.x, me.y, it.x.toDouble(), it.y.toDouble()) }
                .forEach { out.add(Goal(it.x.toDouble(), it.y.toDouble(), 40.0)) }
            if (coop) {
                val carrier = state.pals.firstOrNull { it.carrying && it.seat != state.activeSeat }
                if (carrier != null) {
                    val home = FlickRinkRules.HOME_Y.toDouble()
                    if (dist(carrier.x, carrier.y, FlickRinkRules.HOME_X.toDouble(), home) > dist(me.x, me.y, FlickRinkRules.HOME_X.toDouble(), home)) {
                        out.add(0, Goal(carrier.x, carrier.y, 20.0))
                    }
                }
            }
        }
        level.gates.forEachIndexed { i, g -> if (!state.gateOpen.getOrElse(i) { false }) out.add(Goal(g.pad.x.toDouble(), g.pad.y.toDouble(), 30.0)) }
        return out
    }

    /** The straight-line input toward a goal from where the active body stands. */
    fun direct(fx: Double, fy: Double, goal: Goal, launchX: Int = FlickRinkRules.LAUNCH_DEFAULT_X): FlickInput {
        val d = dist(fx, fy, goal.x, goal.y) + goal.overshoot
        return FlickInput(angleTo(fx, fy, goal.x, goal.y), FlickRinkOpenIce.powerFor(d), launchX)
    }

    /** Where the active body will start: its pal, or the launch point of the next disc. */
    private fun origin(state: FlickRinkState, launchX: Int): Pair<Double, Double> =
        if (state.kind == FlickGameKind.BULLSEYE) launchX.toDouble() to FlickRinkRules.LAUNCH_Y.toDouble()
        else state.pals[state.activeSeat].let { it.x to it.y }

    /**
     * Candidate flicks for the active seat, in priority order (the direct line to the first goal first).
     * [wide] adds the full ring of angles and powers (Hard and the solver); otherwise only the directed ones plus bounce offsets.
     */
    fun candidates(state: FlickRinkState, level: RinkLevel, coop: Boolean, wide: Boolean, launchXs: IntArray = intArrayOf(FlickRinkRules.LAUNCH_DEFAULT_X)): List<FlickInput> {
        val out = ArrayList<FlickInput>()
        val seen = HashSet<Long>()
        fun add(angle: Int, power: Int, lx: Int) {
            val a = Math.floorMod(angle, 3600)
            val p = power.coerceIn(0, 100)
            if (seen.add(a.toLong() * 100000 + p * 1000 + lx)) out.add(FlickInput(a, p, lx))
        }
        val goals = goals(state, level, coop)
        for (lx in launchXs) {
            val (ox, oy) = origin(state, lx)
            for (g in goals) {
                val base = direct(ox, oy, g, lx)
                val offsets = if (wide) intArrayOf(0, 30, -30, 80, -80, 150, -150) else intArrayOf(0)
                val powers = if (wide) intArrayOf(0, 12, -12) else intArrayOf(0)
                for (o in offsets) for (dp in powers) add(base.angleDeci + o, base.power + dp, lx)
            }
        }
        // bounce candidates around the direct line
        if (goals.isNotEmpty()) for (lx in launchXs) {
            val (ox, oy) = origin(state, lx)
            val base = direct(ox, oy, goals[0], lx)
            for (deg in intArrayOf(10, 20, 30, 45, 60, 90)) for (side in intArrayOf(1, -1)) for (pw in intArrayOf(65, 100)) add(base.angleDeci + side * deg * 10, pw, lx)
        }
        if (wide) {
            for (lx in launchXs) for (a in 0 until 36) for (pw in intArrayOf(30, 55, 80, 100)) add(a * 100, pw, lx)
        }
        return out
    }

    /** How good a probed flick is (higher is better). Mirrors the design doc: bank, pick-up, pad, splash, drift, distance to the goal. */
    fun value(p: FlickProbe, state: FlickRinkState, level: RinkLevel): Double {
        if (level.isBoard) {
            val t = level.target ?: return 0.0
            return p.margin * 100.0 + p.points * 10.0 - dist(p.endX, p.endY, t.x.toDouble(), t.y.toDouble()) * 0.1 +
                (if (p.othersMoved > 0 && p.margin > 0) 5.0 else 0.0)
        }
        var v = 1000.0 * p.banked
        if (p.picked) v += 400.0
        val openBefore = state.gateOpen.count { it }
        if (p.gatesOpen > openBefore) v += 300.0 else if (p.gatesOpen < openBefore) v -= 300.0
        if (p.splashed) v -= 200.0
        if (p.endsInDrift) v -= 60.0
        val gx: Double
        val gy: Double
        if (p.carrying) {
            gx = FlickRinkRules.HOME_X.toDouble(); gy = FlickRinkRules.HOME_Y.toDouble()
        } else {
            val free = state.fish.filter { it.state == 0 }
            if (free.isEmpty()) { gx = FlickRinkRules.HOME_X.toDouble(); gy = FlickRinkRules.HOME_Y.toDouble() } else {
                val n = free.minByOrNull { dist(p.endX, p.endY, it.x.toDouble(), it.y.toDouble()) }!!
                gx = n.x.toDouble(); gy = n.y.toDouble()
            }
        }
        return v - dist(p.endX, p.endY, gx, gy)
    }
}

/** The bot: plans with the real simulation, then adds the level's aim and power noise. */
class DefaultFlickRinkBot : FlickRinkBot {
    /** Sim steps the last [choose] spent (for tests and the budget guard). */
    var lastStepsUsed: Int = 0
        private set

    override fun choose(engine: FlickRinkEngine, difficulty: CpuDifficulty, seed: Long): FlickInput {
        lastStepsUsed = 0
        val state = engine.state()
        val level = engine.level()
        if (level == null || state.phase != FlickPhase.AIMING) return FlickInput(2700, 50)
        val rng = SplitMix64.forTurn(seed, state.turn)
        val coop = state.style == FlickStyle.COOP
        val bull = level.isBoard
        val launchXs = if (bull && difficulty != CpuDifficulty.EASY) intArrayOf(120, 300, 480) else intArrayOf(FlickRinkRules.LAUNCH_DEFAULT_X)

        val goals = FlickRinkPlanner.goals(state, level, coop)
        var chosen: FlickInput
        if (difficulty == CpuDifficulty.EASY || goals.isEmpty()) {
            val me = if (bull) FlickRinkRules.LAUNCH_DEFAULT_X.toDouble() to FlickRinkRules.LAUNCH_Y.toDouble() else state.pals[state.activeSeat].let { it.x to it.y }
            val g = goals.firstOrNull()
            chosen = if (g == null) FlickInput(2700, 50) else FlickRinkPlanner.direct(me.first, me.second, g)
            if (!bull && g != null && rng.nextDouble() < FlickRinkBotLevels.EASY_RANDOM_SHARE) {
                // A wobbly turn: somewhere in the half-plane that faces the fish.
                val spread = ((rng.nextDouble() - 0.5) * 1800.0).toInt()
                chosen = FlickInput(Math.floorMod(chosen.angleDeci + spread, 3600), 30 + (rng.nextDouble() * 60.0).toInt(), chosen.launchX)
            }
        } else {
            chosen = search(engine, state, level, coop, difficulty, launchXs)
        }
        return withNoise(chosen, difficulty, rng)
    }

    private fun search(engine: FlickRinkEngine, state: FlickRinkState, level: RinkLevel, coop: Boolean, d: CpuDifficulty, launchXs: IntArray): FlickInput {
        val budget = FlickRinkBotLevels.stepBudget(d)
        val cap = FlickRinkBotLevels.maxCandidates(d)
        val wide = d == CpuDifficulty.HARD
        val all = FlickRinkPlanner.candidates(state, level, coop, wide, launchXs)
        // Hard: 144 coarse + the directed ones, then 16 refinements around the best; Medium: direct + 24 bounces.
        val phase1 = all.take(if (wide) cap - 16 else cap)
        var best: FlickInput? = null
        var bestV = -Double.MAX_VALUE
        fun tryOne(c: FlickInput): Boolean {
            if (lastStepsUsed + FlickRinkRules.MAX_STEPS > budget) return false
            val p = engine.probe(c) ?: return true
            lastStepsUsed += p.steps
            val v = FlickRinkPlanner.value(p, state, level)
            if (v > bestV) { bestV = v; best = c }
            return true
        }
        for (c in phase1) if (!tryOne(c)) break
        if (wide && best != null) {
            val b = best!!
            val refine = ArrayList<FlickInput>()
            for (da in intArrayOf(-40, -20, -10, -5, 5, 10, 20, 40)) refine.add(FlickInput(Math.floorMod(b.angleDeci + da, 3600), b.power, b.launchX))
            for (dp in intArrayOf(-8, -4, 4, 8)) refine.add(FlickInput(b.angleDeci, (b.power + dp).coerceIn(0, 100), b.launchX))
            for (da in intArrayOf(-15, 15)) refine.add(FlickInput(Math.floorMod(b.angleDeci + da, 3600), (b.power + 3).coerceIn(0, 100), b.launchX))
            for (c in refine) if (!tryOne(c)) break
        }
        return best ?: all.firstOrNull() ?: FlickInput(2700, 50)
    }

    private fun withNoise(c: FlickInput, d: CpuDifficulty, rng: SplitMix64): FlickInput {
        val angle = c.angleDeci + Math.round(rng.nextGaussian() * FlickRinkBotLevels.angleSigmaDegrees(d) * 10.0).toInt()
        val power = c.power + Math.round(rng.nextGaussian() * FlickRinkBotLevels.powerSigma(d)).toInt()
        return FlickInput(Math.floorMod(angle, 3600), power.coerceIn(0, 100), c.launchX.coerceIn(FlickRinkRules.LAUNCH_MIN_X, FlickRinkRules.LAUNCH_MAX_X))
    }
}
