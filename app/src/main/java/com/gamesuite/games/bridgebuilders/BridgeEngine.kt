package com.gamesuite.games.bridgebuilders

import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.random.Random

enum class BridgePhase { COUNTDOWN, BUILD, CROSS, OVER }

/** One seat of the table as the engine needs it. */
data class BridgeSeat(val playerId: String, val name: String, val isBot: Boolean)

/** One lane as the screen sees it. [tapSerial] only ever grows and keys the lane's animation and sound. */
data class BridgeLaneState(
    val playerId: String,
    val name: String,
    val isBot: Boolean,
    val planks: Int,
    val spans: Int,
    val status: String,
    val marker: Float,
    val windowCentre: Float,
    val windowWidth: Float,
    val widened: Boolean,
    val lastTier: String,
    val lastQuality: Float,
    val tapSerial: Int,
    val walk: Float,
    val lead: Int
)

/** Everything a device needs to draw the bridge. [eventSerial] only ever grows. */
data class BridgeState(
    val lanes: List<BridgeLaneState>,
    val phase: String,
    val band: String,
    val spans: Int,
    val water: Float,
    val strength: Float,
    val sturdiness: Float,
    val minPlanks: Int,
    val countdownMs: Int,
    val waterLeftSeconds: Int,
    val relaxed: Boolean,
    val event: String,
    val eventArg: Int,
    val eventSerial: Int,
    val over: Boolean,
    val won: Boolean,
    val elapsedMs: Long,
    val bridgesBuilt: Int,
    val tries: Int,
    val misses: Int
) {
    val phaseValue: BridgePhase get() = BridgePhase.entries.firstOrNull { it.name == phase } ?: BridgePhase.OVER
}

@Serializable
data class BridgePlayerSave(val playerId: String, val isBot: Boolean)

@Serializable
data class BridgeLaneSave(val planks: Int, val quality: Double)

/** The in-progress try, written down. The marker and green spots are not saved: a resumed try starts again from a countdown. */
@Serializable
data class BridgeSave(
    val version: Int = BridgeRules.SAVE_VERSION,
    val players: List<BridgePlayerSave>,
    val band: String,
    val relaxed: Boolean,
    val handicap: String,
    val difficulty: String,
    val lanes: List<BridgeLaneSave>,
    val water: Double,
    val bridges: Int,
    val tries: Int,
    val eventSerial: Int = 0
)

/** Encodes, decodes and VALIDATES saves. Pure; a save that does not fit the roster or the rules is rejected, never repaired. */
object BridgeSaveCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(save: BridgeSave): String = json.encodeToString(BridgeSave.serializer(), save)

    fun decode(text: String, seats: List<BridgeSeat>): BridgeSave? = try {
        val s = json.decodeFromString(BridgeSave.serializer(), text)
        if (isValid(s, seats)) s else null
    } catch (_: Exception) {
        null
    }

    fun isValid(s: BridgeSave, seats: List<BridgeSeat>): Boolean {
        if (s.version != BridgeRules.SAVE_VERSION) return false
        if (seats.size !in BridgeRules.MIN_LANES..BridgeRules.MAX_LANES) return false
        if (s.players.size != seats.size || s.lanes.size != seats.size) return false
        if (!seats.indices.all { s.players[it].playerId == seats[it].playerId && s.players[it].isBot == seats[it].isBot }) return false
        val band = BridgeBand.entries.firstOrNull { it.id == s.band } ?: return false
        if (CpuDifficulty.entries.none { it.name == s.difficulty }) return false
        if (s.handicap.length > 40) return false
        val spans = BridgeRules.band(band).spans
        for (l in s.lanes) {
            if (l.planks !in 0..spans) return false
            if (!l.quality.isFinite()) return false
            if (l.quality < l.planks * BridgeRules.EDGE_QUALITY - 1e-6 || l.quality > l.planks + 1e-6) return false
        }
        val planks = s.lanes.map { it.planks }
        if (planks.max() - planks.min() > BridgeRules.MAX_LEAD) return false
        if (!s.water.isFinite() || s.water < 0.0 || s.water >= 1.0) return false
        if (s.bridges < 0 || s.tries < 0 || s.eventSerial < 0) return false
        return true
    }
}

/**
 * The Bridge Builders simulation, with no Compose and no Android: a fixed-step engine on an injected clock and an
 * injected [Random], so tests step it by hand and the helper robots replay exactly.
 *
 * Every lane has a cart sliding back and forth along a bar and a green spot somewhere on it. A tap while the cart is
 * inside the spot places a plank; the nearer the middle, the sturdier. A miss only makes the cart rest a moment.
 * The water rises all the time; sturdy planks push it back a little. The COOPERATION RULE: a lane may not get more than
 * [BridgeRules.MAX_LEAD] planks ahead of the slowest lane, it waits instead. When every lane is across the planks, the
 * friends walk over (sturdier planks make that quicker). The water only matters while the bridge is being BUILT: if it
 * covers the bridge before the last plank is down it is a "try again together"; once the last plank is in, the water
 * waits while the friends cross, so a finished bridge is always a win. No lane, player or tap ever "loses" alone.
 */
class BridgeEngine(
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    var band: BridgeBand = BridgeBand.MEDIUM
    var relaxed: Boolean = false
    var handicapSpec: String = "none"
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** The game-speed factor for the cart (Slow < 1). Not saved: it is a setting, not part of a try. */
    var pace: Double = 1.0
    val seatLevelOverride = HashMap<Int, CpuDifficulty>()

    private var seats: List<BridgeSeat> = emptyList()
    private var n = 0
    private var spans = 5
    private var bonus: List<Double> = emptyList()

    private var phase = BridgePhase.OVER
    private var simT = 0L
    private var lastSim = -1L
    private var paused = false
    private var phaseEnd = 0L
    private var crossStart = 0L
    private var crossDur = 1L
    private var buildStart = 0L
    private var elapsedMs = 0L
    private var water = 0.0

    private var planks = IntArray(0)
    private var quality = DoubleArray(0)
    private var cartPhase = DoubleArray(0)
    private var centre = DoubleArray(0)
    private var width = DoubleArray(0)
    private var stumbleUntil = LongArray(0)
    private var lastTier = Array(0) { BridgeTier.NONE }
    private var lastQuality = DoubleArray(0)
    private var tapSerial = IntArray(0)

    private var misses = 0
    private var bridges = 0
    private var tries = 0
    private var announcedRow = 0
    private var halfAnnounced = false
    private var highAnnounced = false

    private var event = BridgeEvent.START
    private var eventArg = 0
    private var eventSerial = 0
    private var eventPriority = -1
    private var pausedAt = -1L
    private var won = false

    private class Bot {
        var armedKey = -1
        var reactAt = 0L
        var willMiss = false
        var tol = 0.5
    }

    private var bots = emptyArray<Bot?>()

    val isOver: Boolean get() = phase == BridgePhase.OVER
    val currentPhase: BridgePhase get() = phase

    // ---- set up ------------------------------------------------------------------------------

    /**
     * A new table: forgets the session tally. Call [start] (or [restore]) next. The roster must be 2 to 4 seats: the
     * shell pads a lone builder with a helper robot, and anything else is a bug that fails here, loudly, instead of
     * indexing past the roster or silently dropping seats (which would also make every save unrestorable).
     */
    fun setup(roster: List<BridgeSeat>) {
        require(roster.size in BridgeRules.MIN_LANES..BridgeRules.MAX_LANES) {
            "Bridge Builders needs ${BridgeRules.MIN_LANES} to ${BridgeRules.MAX_LANES} lanes, got ${roster.size}"
        }
        seats = roster
        n = roster.size
        bridges = 0
        tries = 0
        eventSerial = 0
        phase = BridgePhase.OVER
        won = false
        applyConfig()
    }

    private fun applyConfig() {
        spans = BridgeRules.band(band).spans
        bonus = BridgeHandicap.parse(handicapSpec, n)
    }

    private fun levelOf(seat: Int): CpuDifficulty = seatLevelOverride[seat] ?: difficulty

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Starts a try on the same table (the session tally stays). */
    fun start() {
        applyConfig()
        planks = IntArray(n)
        quality = DoubleArray(n)
        water = 0.0
        beginTry()
    }

    private fun beginTry() {
        won = false
        misses = 0
        announcedRow = 0
        halfAnnounced = false
        highAnnounced = false
        elapsedMs = 0
        cartPhase = DoubleArray(n) { random.nextDouble() }
        width = DoubleArray(n) { BridgeRules.windowWidth(band, bonus[it]) }
        centre = DoubleArray(n) { BridgeRules.pickCentre(random, width[it]) }
        stumbleUntil = LongArray(n) { -1L }
        lastTier = Array(n) { BridgeTier.NONE }
        lastQuality = DoubleArray(n)
        if (tapSerial.size != n) tapSerial = IntArray(n)
        bots = Array(n) { if (seats[it].isBot) Bot() else null }
        tries++
        phase = BridgePhase.COUNTDOWN
        phaseEnd = simT + BridgeRules.COUNTDOWN_MS
        paused = false
        pausedAt = -1L
        lastSim = clock()
        eventPriority = -1
        setEvent(BridgeEvent.START, 0)
    }

    /** Freezes the clock. Calling it again while already paused keeps the moment the pause began. */
    fun pause() {
        if (!paused) pausedAt = clock()
        paused = true
    }

    /**
     * Unfreezes from "now". After a pause of [BridgeRules.RESUME_GRACE_AFTER_MS] or more in the middle of building, the
     * friends get a short [BridgeRules.RESUME_COUNTDOWN_MS] "3-2-1" first (the water and carts stay frozen in it), so
     * a kid coming back from a phone call does not find the cart already sliding.
     */
    fun resume() {
        val now = clock()
        if (paused && phase == BridgePhase.BUILD && pausedAt >= 0 && BridgeRules.needsResumeCountdown(now - pausedAt)) {
            phase = BridgePhase.COUNTDOWN
            phaseEnd = simT + BridgeRules.RESUME_COUNTDOWN_MS
            eventPriority = -1
            setEvent(BridgeEvent.START, 0)
        }
        lastSim = now
        paused = false
        pausedAt = -1L
    }

    /**
     * Records a milestone. Several can happen inside one screen beat (one [tick] or [tap]); the screen only sees the last
     * one, so within a beat the most important wins and a less important later one is dropped (a WATER_HIGH then LOSE
     * reads LOSE; a ROW then CROSS reads CROSS).
     */
    private fun setEvent(kind: String, arg: Int) {
        val pr = BridgeRules.eventPriority(kind)
        if (pr < eventPriority) return
        eventPriority = pr
        event = kind; eventArg = arg; eventSerial++
    }

    // ---- the clock ---------------------------------------------------------------------------

    /** Advances the simulation to the injected clock's now. Returns false when nothing is running. */
    fun tick(): Boolean {
        if (phase == BridgePhase.OVER) return false
        eventPriority = -1
        advanceTo(clock())
        return true
    }

    private fun advanceTo(now: Long) {
        if (phase == BridgePhase.OVER) return
        if (paused) { lastSim = now; return }
        if (lastSim < 0) lastSim = now
        var steps = 0
        while (lastSim + BridgeRules.STEP_MS <= now && steps < BridgeRules.MAX_CATCHUP_STEPS && phase != BridgePhase.OVER) {
            lastSim += BridgeRules.STEP_MS
            simT += BridgeRules.STEP_MS
            step()
            steps++
        }
        if (lastSim + BridgeRules.STEP_MS <= now) lastSim = now
    }

    /**
     * The moment a tap is scored at: the last simulated step, which is exactly what [state] (and so the screen) shows.
     * It used to extrapolate up to 9 ms ahead, which made the scored marker lead the drawn one.
     */
    private fun inputT(): Long = simT

    private fun step() {
        when (phase) {
            BridgePhase.COUNTDOWN -> if (simT >= phaseEnd) {
                phase = BridgePhase.BUILD
                buildStart = simT
                setEvent(BridgeEvent.GO, 0)
            }
            BridgePhase.BUILD -> buildStep()
            BridgePhase.CROSS -> crossStep()
            BridgePhase.OVER -> {}
        }
    }

    private fun speed(): Double = BridgeRules.phasePerMs(band, relaxed, pace)

    private fun buildStep() {
        val dt = BridgeRules.STEP_MS
        elapsedMs += dt
        if (riseWater(dt)) return
        if (minPlanks() >= spans) { startCross(); return }
        val v = speed() * dt
        for (l in 0 until n) if (slides(l, simT)) cartPhase[l] += v
        for (l in 0 until n) {
            botStep(l, simT)
            if (phase != BridgePhase.BUILD) return
        }
    }

    /** The water is frozen while the friends cross: finishing the last plank is what the try was about. */
    private fun crossStep() {
        elapsedMs += BridgeRules.STEP_MS
        if (simT >= crossStart + crossDur) finish(true)
    }

    /** Raises the water; returns true when it covered the bridge (the try is over). */
    private fun riseWater(dtMs: Long): Boolean {
        water += BridgeRules.waterPerMs(band, relaxed) * dtMs
        if (water >= 1.0) {
            water = 1.0
            finish(false)
            return true
        }
        if (!halfAnnounced && water >= BridgeRules.WATER_HALF_AT) { halfAnnounced = true; setEvent(BridgeEvent.WATER_HALF, 0) }
        if (!highAnnounced && water >= BridgeRules.WATER_HIGH_AT) { highAnnounced = true; setEvent(BridgeEvent.WATER_HIGH, 0) }
        return false
    }

    // ---- lanes -------------------------------------------------------------------------------

    private fun minPlanks(): Int = if (planks.isEmpty()) 0 else planks.min()

    private fun status(l: Int, t: Long): String = when {
        planks[l] >= spans -> BridgeLaneStatus.DONE
        BridgeRules.mustWait(planks[l], minPlanks(), spans) -> BridgeLaneStatus.WAITING
        t < stumbleUntil[l] -> BridgeLaneStatus.STUMBLED
        else -> BridgeLaneStatus.MOVING
    }

    /**
     * Does the cart of lane [l] slide? A lane that is WAITING for friends keeps sliding (it only cannot be tapped), so
     * its cart is never frozen inside the green spot for a free perfect plank. A resting (stumbled) or finished lane is still.
     */
    private fun slides(l: Int, t: Long): Boolean {
        val st = status(l, t)
        return st == BridgeLaneStatus.MOVING || st == BridgeLaneStatus.WAITING
    }

    private fun markerAt(l: Int, t: Long): Double {
        val moving = slides(l, simT)
        val ph = cartPhase[l] + if (moving) speed() * (t - simT).coerceAtLeast(0L) else 0.0
        return BridgeRules.triangle(ph)
    }

    // ---- tapping -----------------------------------------------------------------------------

    /** A person tapped lane [seat]. */
    fun tap(seat: Int): BridgeTapResult {
        if (phase == BridgePhase.OVER || paused) return BridgeTapResult.NOT_BUILDING
        eventPriority = -1
        if (seat !in 0 until n) return BridgeTapResult.BAD_SEAT
        if (seats[seat].isBot) return BridgeTapResult.BOT_SEAT
        advanceTo(clock())
        if (phase != BridgePhase.BUILD) return BridgeTapResult.NOT_BUILDING
        return tapInternal(seat, inputT())
    }

    private fun tapInternal(seat: Int, t: Long): BridgeTapResult {
        when (status(seat, t)) {
            BridgeLaneStatus.DONE -> return BridgeTapResult.DONE
            BridgeLaneStatus.WAITING -> return BridgeTapResult.WAITING
            BridgeLaneStatus.STUMBLED -> return BridgeTapResult.STUMBLED
        }
        val dist = abs(markerAt(seat, t) - centre[seat])
        val half = width[seat] / 2.0
        if (!relaxed && dist > half) {
            stumbleUntil[seat] = t + BridgeRules.band(band).stumbleMs
            lastTier[seat] = BridgeTier.MISS
            lastQuality[seat] = 0.0
            tapSerial[seat]++
            misses++
            return BridgeTapResult.MISS
        }
        val q = if (relaxed) BridgeRules.relaxedQuality(dist, width[seat]) else BridgeRules.hitQuality(dist, width[seat])
        planks[seat]++
        quality[seat] += q
        water = (water - BridgeRules.EBB_PER_QUALITY * q).coerceAtLeast(0.0)
        val tier = BridgeRules.tierOf(q)
        lastTier[seat] = tier
        lastQuality[seat] = q
        tapSerial[seat]++
        if (planks[seat] < spans) {
            width[seat] = BridgeRules.windowWidth(band, bonus[seat])
            centre[seat] = BridgeRules.pickCentre(random, width[seat])
        }
        val m = minPlanks()
        if (m >= spans) {
            startCross()
        } else if (m > announcedRow) {
            announcedRow = m
            setEvent(BridgeEvent.ROW, m)
        }
        return when (tier) {
            BridgeTier.PERFECT -> BridgeTapResult.PERFECT
            BridgeTier.GOOD -> BridgeTapResult.GOOD
            else -> BridgeTapResult.WOBBLY
        }
    }

    private fun startCross() {
        phase = BridgePhase.CROSS
        crossStart = simT
        crossDur = BridgeRules.crossMs(band, strength()).coerceAtLeast(1L)
        setEvent(BridgeEvent.CROSS, 0)
    }

    private fun finish(success: Boolean) {
        phase = BridgePhase.OVER
        won = success
        if (success) bridges++
        setEvent(if (success) BridgeEvent.WIN else BridgeEvent.LOSE, 0)
    }

    /** Shared bridge strength: all planks' sturdiness over all the planks the bridge needs, 0..1. */
    private fun strength(): Double = if (n == 0) 0.0 else (quality.sum() / (n * spans)).coerceIn(0.0, 1.0)

    private fun sturdiness(): Double {
        val placed = planks.sum()
        return if (placed == 0) 0.0 else (quality.sum() / placed).coerceIn(0.0, 1.0)
    }

    // ---- helper robots -------------------------------------------------------------------------

    private fun botStep(l: Int, t: Long) {
        val b = bots.getOrNull(l) ?: return
        if (status(l, t) != BridgeLaneStatus.MOVING) { b.armedKey = -1; return }
        val p = BridgeRules.bot(levelOf(l))
        if (b.armedKey != tapSerial[l]) {
            b.armedKey = tapSerial[l]
            b.reactAt = t + (p.reactMs * (0.75 + random.nextDouble() * 0.5)).toLong()
            b.willMiss = !relaxed && random.nextDouble() < p.missChance
            b.tol = p.tolMin + random.nextDouble() * (p.tolMax - p.tolMin)
        }
        if (t < b.reactAt) return
        val dist = abs(BridgeRules.triangle(cartPhase[l]) - centre[l])
        val half = width[l] / 2.0
        val farthest = maxOf(centre[l], 1.0 - centre[l])
        val missAt = minOf(half * 1.4, farthest * 0.9)
        val go = if (b.willMiss && missAt > half) dist >= missAt else dist <= half * b.tol
        if (go) tapInternal(l, t)
    }

    // ---- the screen's view ---------------------------------------------------------------------

    fun state(): BridgeState {
        val m = minPlanks()
        val lanes = List(n) { l ->
            val t = simT
            val walk = if (phase == BridgePhase.CROSS) ((t - crossStart).toDouble() / crossDur).coerceIn(0.0, 1.0)
            else if (phase == BridgePhase.OVER && won) 1.0 else 0.0
            BridgeLaneState(
                playerId = seats[l].playerId, name = seats[l].name, isBot = seats[l].isBot,
                planks = planks.getOrElse(l) { 0 }, spans = spans, status = if (planks.isEmpty()) BridgeLaneStatus.MOVING else status(l, t),
                marker = if (cartPhase.isEmpty()) 0f else BridgeRules.triangle(cartPhase[l]).toFloat(),
                windowCentre = centre.getOrElse(l) { 0.5 }.toFloat(), windowWidth = width.getOrElse(l) { 0.3 }.toFloat(),
                widened = bonus.getOrElse(l) { 0.0 } > 0.0,
                lastTier = lastTier.getOrElse(l) { BridgeTier.NONE }, lastQuality = lastQuality.getOrElse(l) { 0.0 }.toFloat(),
                tapSerial = tapSerial.getOrElse(l) { 0 }, walk = walk.toFloat(),
                lead = planks.getOrElse(l) { 0 } - m
            )
        }
        val left = if (phase == BridgePhase.OVER && !won) 0
        else ((1.0 - water) / (BridgeRules.waterPerMs(band, relaxed) * 1000.0)).toInt().coerceAtLeast(0)
        return BridgeState(
            lanes = lanes, phase = phase.name, band = band.id, spans = spans, water = water.toFloat(),
            strength = if (planks.isEmpty()) 0f else strength().toFloat(), sturdiness = if (planks.isEmpty()) 0f else sturdiness().toFloat(),
            minPlanks = m,
            countdownMs = if (phase == BridgePhase.COUNTDOWN) (phaseEnd - simT).coerceAtLeast(0L).toInt() else 0,
            waterLeftSeconds = left, relaxed = relaxed, event = event, eventArg = eventArg, eventSerial = eventSerial,
            over = phase == BridgePhase.OVER, won = won, elapsedMs = elapsedMs, bridgesBuilt = bridges, tries = tries, misses = misses
        )
    }

    // ---- resume ---------------------------------------------------------------------------------

    /** The try so far, or null when there is nothing to save (not started, or already decided). */
    fun toSave(): BridgeSave? {
        if (phase == BridgePhase.OVER || planks.size != n || n == 0) return null
        return BridgeSave(
            players = seats.map { BridgePlayerSave(it.playerId, it.isBot) }, band = band.id, relaxed = relaxed,
            handicap = handicapSpec, difficulty = difficulty.name,
            lanes = List(n) { BridgeLaneSave(planks[it], quality[it]) }, water = water.coerceAtMost(0.999),
            bridges = bridges, tries = tries, eventSerial = eventSerial
        )
    }

    /** Puts the planks and water back as saved and starts again from the countdown. False (and nothing touched) when invalid. */
    fun restore(save: BridgeSave): Boolean {
        if (!BridgeSaveCodec.isValid(save, seats)) return false
        band = BridgeBand.entries.first { it.id == save.band }
        relaxed = save.relaxed
        handicapSpec = save.handicap
        difficulty = CpuDifficulty.entries.first { it.name == save.difficulty }
        applyConfig()
        planks = IntArray(n) { save.lanes[it].planks }
        quality = DoubleArray(n) { save.lanes[it].quality }
        water = save.water
        bridges = save.bridges
        eventSerial = save.eventSerial
        // The try being resumed was already counted when it began.
        tries = (save.tries - 1).coerceAtLeast(0)
        beginTry()
        announcedRow = minPlanks()
        halfAnnounced = water >= BridgeRules.WATER_HALF_AT
        highAnnounced = water >= BridgeRules.WATER_HIGH_AT
        return true
    }
}
