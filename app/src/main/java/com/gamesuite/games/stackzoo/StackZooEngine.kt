package com.gamesuite.games.stackzoo

/**
 * The rules and the physics of Stack Zoo with no Compose, no Android and no clock. Time only moves through [advance]
 * in whole fixed steps ([ZooPhysics.DT]), so a seed plus a release log replays bit for bit. The game wrapper
 * ([StackZooGame]) turns frame time into step counts and owns locking, helper timing and the shell lifecycle.
 *
 * Lifecycle: [setup] -> [start] (READY) -> [begin] -> [choose] -> [release] -> [advance] until settled -> next turn ... OVER.
 */
interface StackZooEngine {
    /** Seats are people first, then helpers. Resets everything; [ZooConfig.seed] (never 0 from the wrapper) fixes the bag and every bot choice. */
    fun setup(config: ZooConfig, seats: List<ZooSeat>)

    /** Moves to READY with the bag dealt. */
    fun start()

    /** The "Ready?" tap: the first turn starts (CHOOSING). */
    fun begin()

    /** The active seat picks a piece from [ZooState.offer]. Returns false when it is not on offer or not CHOOSING. */
    fun choose(pieceId: Int): Boolean

    /** Drag back to the shelf: POSITIONING returns to CHOOSING and no turn is used. */
    fun unchoose(): Boolean

    /** The placement the assist would make for a drag at ([xQ], [angleQ]) for the active seat (for the halo preview). */
    fun assisted(xQ: Int, angleQ: Int): ZooPlacement

    /** Releases the chosen piece at a quantised placement; assist, lift-out and sweep-down happen inside. */
    fun release(xQ: Int, angleQ: Int): ZooReleaseResult

    /** Advances the physics by [steps] fixed steps (at most [ZooPhysics.MAX_STEPS_PER_FRAME] per frame from the wrapper). No-op while paused. */
    fun advance(steps: Int)

    fun pause()
    fun resume()

    /** Starts another game with the same seats; the next seed is derived from the previous one (SplitMix64), so it stays reproducible. */
    fun playAgain()

    fun state(): ZooState

    /** Copies the current poses into [out] without allocating. */
    fun fillBodies(out: ZooBodyBuffer)

    /** FNV-1a over the quantised pose of every body and the base, for desync detection and the golden test. */
    fun stateHash(): Long

    fun replay(): ZooReplay

    /** Null when there is nothing worth saving (not started or over); a drag in progress is saved as the turn start. */
    fun toSave(): ZooSave?

    /** True when the engine is fully back in the saved state; false (and unchanged) when the save is unusable. */
    fun restore(save: ZooSave): Boolean
}

/** Placement helpers shared by the engine and the helpers: clamping, flat snap and balance pull. */
internal object ZooPlacer {
    fun clampX(xQ: Int): Int = if (xQ > ZooPhysics.X_LIMIT_Q) ZooPhysics.X_LIMIT_Q else if (xQ < -ZooPhysics.X_LIMIT_Q) -ZooPhysics.X_LIMIT_Q else xQ
    fun clampAngle(aQ: Int): Int = if (aQ > ZooPhysics.ANGLE_LIMIT_Q) ZooPhysics.ANGLE_LIMIT_Q else if (aQ < -ZooPhysics.ANGLE_LIMIT_Q) -ZooPhysics.ANGLE_LIMIT_Q else aQ

    fun quantizeAngle(a: Double): Int = clampAngle(StrictMath.floor(ZooShape.wrapAngle(a) * ZooPhysics.QUANT + 0.5).toInt())
    fun quantizeX(x: Double): Int = clampX(StrictMath.floor(x * ZooPhysics.QUANT + 0.5).toInt())

    /** The assisted placement for a drag at the raw quantised ([xQ], [angleQ]). */
    fun assisted(world: ZooWorld, animalIndex: Int, xQ: Int, angleQ: Int, level: ZooAssist): ZooPlacement {
        var x = clampX(xQ) / ZooPhysics.QUANT
        var ang = clampAngle(angleQ) / ZooPhysics.QUANT
        if (level == ZooAssist.NONE) return ZooPlacement(clampX(xQ), clampAngle(angleQ))
        val snapRange = (if (level == ZooAssist.LARGE) ZooPhysics.FLAT_SNAP_LARGE_DEG else ZooPhysics.FLAT_SNAP_SMALL_DEG) * Math.PI / 180.0
        val radius = if (level == ZooAssist.LARGE) ZooRules.ASSIST_LARGE_RADIUS else ZooRules.ASSIST_SMALL_RADIUS
        val pull = if (level == ZooAssist.LARGE) ZooRules.ASSIST_LARGE_PULL else ZooRules.ASSIST_SMALL_PULL
        val shape = ZooShapes.animals[animalIndex]
        // 1. flat snap to the nearest flat-edge angle within range
        var best = Double.NaN
        var bestD = snapRange
        for (f in shape.flatAngles) {
            val d = Math.abs(ZooShape.wrapAngle(f - ang))
            if (d <= bestD) {
                bestD = d
                best = f
            }
        }
        if (best == best) ang = best
        // 2. balance pull toward the spot that evens the log out
        var topY = -1e30
        var xTop = 0.0
        var sum = 0.0
        for (i in 0 until ZooPhysics.MAX_BODIES) {
            if (!world.live[i] || world.fallen[i]) continue
            sum += world.mass[i] * world.x[i]
            if (world.y[i] > topY) {
                topY = world.y[i]
                xTop = world.x[i]
            }
        }
        var xCounter = -sum / shape.mass
        if (xCounter > 2.5) xCounter = 2.5 else if (xCounter < -2.5) xCounter = -2.5
        val target = 0.5 * xTop + 0.5 * xCounter
        if (Math.abs(x - target) <= radius) x += pull * (target - x)
        return ZooPlacement(quantizeX(x), quantizeAngle(ang))
    }
}
