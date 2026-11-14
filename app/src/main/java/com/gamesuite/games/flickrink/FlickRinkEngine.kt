package com.gamesuite.games.flickrink

/**
 * The Flick Rink rules and physics, with no Compose and no Android. A fixed-step (120 Hz) deterministic simulation: the same
 * level, seed and list of [FlickInput]s always produce the same trajectory and state hash.
 *
 * Contract:
 *  - [flick] runs the WHOLE slide to rest in one call (at most [FlickRinkRules.MAX_STEPS] steps), applies the rules
 *    (pick-up, banking, splash, gate pads, relay hand-off, scoring), moves on to the next turn, and returns the outcome.
 *    The screen plays the recorded frames back at display rate; a Reduced-Motion player may skip the playback.
 *  - Inputs outside [FlickRinkRules.isInRange] are rejected (the outcome is [FlickOutcome.NONE], nothing changes).
 *  - [previewInto] is the aim helper: a short polyline of where the flick would go (no pal bumps), at most the seat's helper length.
 *  - [stateHash] covers positions (rounded to 1/16 unit), fish, gate and slider poses, scores, flick counts and the turn
 *    number, and ignores the recorded frames.
 *  - [probe] and [previewInto] run on a scratch copy and never change the match. They use one shared scratch simulation, so only
 *    the party whose turn it is may call them (the bot thinks while no human is aiming).
 *  - Nothing allocates inside the step loop.
 */
interface FlickRinkEngine {
    /**
     * Sets up a match. [seats] is 1 seat (solo) to 4. Returns false (and changes nothing) when the config does not fit the seats
     * or [levels] holds no rink (or board) of the kind the config asks for.
     */
    fun configure(config: FlickConfig, seats: List<FlickSeat>, levels: List<RinkLevel> = FlickRinkLevels.everything()): Boolean

    /** Starts the first rink. */
    fun start()

    fun state(): FlickRinkState

    /** The configuration of the current match (after [restore] it is the saved one). */
    fun config(): FlickConfig

    /** The rink or board being played, or null before [start]. */
    fun level(): RinkLevel?

    /** True when the engine would accept this flick right now (phase is AIMING and the input is in range). */
    fun canFlick(input: FlickInput): Boolean

    /** Plays one flick for the active seat. [record] false skips the frame buffer (bots, skipping the slide). */
    fun flick(input: FlickInput, record: Boolean = true): FlickOutcome

    /** What the flick would do, simulated on a scratch copy; null when it is not the time to flick. */
    fun probe(input: FlickInput): FlickProbe?

    /** The aim helper polyline for the active seat: x0, y0, x1, y1, ... Empty when the seat has no helper. */
    fun previewLine(input: FlickInput, maxLength: Int): FloatArray

    /** Same, written into [out] (no allocation); returns the number of floats written. */
    fun previewInto(input: FlickInput, maxLength: Int, out: FloatArray): Int

    /** The helper of a seat (what the Hub's "aim helper for" gave it). */
    fun seatAssist(seat: Int): AimAssist

    /** After RINK_DONE: moves to the next rink, or finishes the match (phase MATCH_DONE). False when it cannot move (not RINK_DONE, or a co-op rink that must be retried). */
    fun nextRink(): Boolean

    /** Co-op, after a failed rink: the same rink again with the retry bonus. False when the rink was not failed. */
    fun retryRink(): Boolean

    fun summary(): FlickMatchSummary?

    fun stateHash(): Long

    /** The resumable form, or null when there is nothing to save. */
    fun toSave(): FlickSave?

    /** Replays [save] from its seed. False (engine untouched) when it is invalid for these seats or this physics version. */
    fun restore(save: FlickSave): Boolean
}

/** The engine: owns the roster and the level pool and delegates a match to [FlickMatch]. */
class DefaultFlickRinkEngine : FlickRinkEngine {
    private var cfg = FlickConfig()
    private var roster: List<FlickSeat> = emptyList()
    private var pool: List<RinkLevel> = emptyList()
    private var match: FlickMatch? = null

    override fun configure(config: FlickConfig, seats: List<FlickSeat>, levels: List<RinkLevel>): Boolean {
        if (!FlickMatch.fits(config, seats.size, levels)) return false
        cfg = config
        roster = seats.toList()
        pool = levels
        match = FlickMatch(config, roster, levels)
        return true
    }

    override fun start() { match?.start() }
    override fun state(): FlickRinkState = match?.state() ?: FlickRinkState.EMPTY
    override fun config(): FlickConfig = cfg
    override fun level(): RinkLevel? = match?.currentLevel()
    override fun canFlick(input: FlickInput): Boolean = match?.canFlick(input) ?: false
    override fun flick(input: FlickInput, record: Boolean): FlickOutcome = match?.flick(input, record) ?: FlickOutcome.NONE
    override fun probe(input: FlickInput): FlickProbe? = match?.probe(input)
    override fun previewLine(input: FlickInput, maxLength: Int): FloatArray {
        val buf = FloatArray(PREVIEW_FLOATS)
        val n = previewInto(input, maxLength, buf)
        return buf.copyOf(n)
    }

    override fun previewInto(input: FlickInput, maxLength: Int, out: FloatArray): Int = match?.previewInto(input, maxLength, out) ?: 0
    override fun seatAssist(seat: Int): AimAssist = match?.seatAssist(seat) ?: AimAssist.OFF
    override fun nextRink(): Boolean = match?.nextRink() ?: false
    override fun retryRink(): Boolean = match?.retryRink() ?: false
    override fun summary(): FlickMatchSummary? = match?.summary()
    override fun stateHash(): Long = match?.stateHash() ?: 0L
    override fun toSave(): FlickSave? = match?.toSave()

    override fun restore(save: FlickSave): Boolean {
        if (roster.isEmpty() || pool.isEmpty()) return false
        if (!FlickSaveCodec.isValid(save, roster)) return false
        val restored = FlickSaveCodec.configOf(save)
        if (!FlickMatch.fits(restored, roster.size, pool)) return false
        // Replay into a fresh match; only a verified replay replaces the live one, so a bad save leaves the engine untouched.
        val fresh = try {
            FlickMatch(restored, roster, pool).also { it.start() }
        } catch (_: Exception) {
            return false
        }
        val ok = try {
            fresh.replay(save.log) && fresh.stateHash() == save.stateHash && fresh.retryCount() == save.coopRetries
        } catch (_: Exception) {
            false
        }
        if (!ok) return false
        cfg = restored
        match = fresh
        return true
    }

    companion object {
        /** Floats the aim helper polyline can need: a start point, at most two bounces and an end point. */
        const val PREVIEW_FLOATS = 10
    }
}

/**
 * One match: the rink sequence, the turn order, the rules and the bookkeeping around a [FlickSim]. See the design doc
 * (SCRIPT > Rules script) for every rule; each numbered rule is marked where it lives.
 */
internal class FlickMatch(val cfg: FlickConfig, val seats: List<FlickSeat>, pool: List<RinkLevel>) {
    private val n = seats.size
    private val bullseye = cfg.kind == FlickGameKind.BULLSEYE
    private val coop = cfg.style == FlickStyle.COOP
    private val solo = cfg.style == FlickStyle.SOLO
    private val assisted: Set<Int> = FlickRinkRules.assistSeats(cfg.assistFor, n)
    private val levels: List<RinkLevel> = levelsFor(cfg, pool)

    private var started = false
    var phase = FlickPhase.SETUP
        private set
    private var rinkIndex = 0
    private lateinit var level: RinkLevel
    private lateinit var sim: FlickSim
    private lateinit var scratch: FlickSim

    private val seatScore = IntArray(n)
    private val seatFlicks = IntArray(n)
    private val seatBulls = IntArray(n)
    private val seatBest = DoubleArray(n) { Double.MAX_VALUE }
    private val flicksBySeat = IntArray(n)
    private var flicksInRink = 0
    private var fishBanked = 0
    private var retries = 0
    private var turn = 0
    private var rinkCleared = false
    private val rinkStars = ArrayList<Int>()
    private val log = ArrayList<FlickLogEntry>()

    fun retryCount(): Int = retries
    fun currentLevel(): RinkLevel? = if (started) level else null
    fun seatAssist(seat: Int): AimAssist = if (seat in assisted) cfg.assist else AimAssist.OFF

    fun start() {
        started = true
        rinkIndex = 0
        retries = 0
        turn = 0
        seatScore.fill(0); seatFlicks.fill(0); seatBulls.fill(0); seatBest.fill(Double.MAX_VALUE)
        rinkStars.clear(); log.clear()
        beginRink()
    }

    private fun beginRink() {
        level = levels[rinkIndex]
        val bodies = if (bullseye) n * FlickRinkRules.DISCS_PER_PLAYER else n
        sim = FlickSim(level, bodies, !bullseye, cfg.relaxed, coop)
        scratch = FlickSim(level, bodies, !bullseye, cfg.relaxed, coop)
        sim.resetRink()
        if (!bullseye) for (i in 0 until n) sim.place(i, START_X[i], START_Y[i])
        flicksInRink = 0
        flicksBySeat.fill(0)
        fishBanked = 0
        rinkCleared = false
        phase = FlickPhase.AIMING
    }

    // ---- turn order and limits ----

    private fun startSeat(): Int = Math.floorMod(cfg.firstSeat + rinkIndex, n)
    private fun activeSeat(): Int = (startSeat() + flicksInRink) % n
    private fun seatOfDisc(k: Int): Int = (startSeat() + k) % n

    private fun budget(): Int = if (coop && !bullseye) FlickRinkRules.coopBudget(level.par, cfg.band, cfg.budget, FlickRinkRules.retryBonus(retries)) else 0

    /** Rule 16: the most flicks the rink allows (all seats together). Solo and Relaxed only have the safety valve. */
    private fun flickLimit(): Int = when {
        bullseye -> n * FlickRinkRules.DISCS_PER_PLAYER
        solo || cfg.relaxed -> FlickRinkRules.SAFETY_FLICKS_PER_SEAT * n
        coop -> budget()
        else -> FlickRinkRules.flickCapPerPlayer(n) * n
    }

    private fun flicksLeft(): Int = when {
        bullseye || solo || cfg.relaxed -> -1
        coop -> (budget() - flicksInRink).coerceAtLeast(0)
        else -> (FlickRinkRules.flickCapPerPlayer(n) - flicksBySeat[activeSeat()]).coerceAtLeast(0)
    }

    private fun bodyOfNextFlick(): Int = if (bullseye) flicksInRink else activeSeat()

    // ---- queries ----

    fun canFlick(input: FlickInput): Boolean = started && phase == FlickPhase.AIMING && FlickRinkRules.isInRange(input)

    fun state(): FlickRinkState {
        if (!started) return FlickRinkState.EMPTY.copy(style = cfg.style, kind = cfg.kind, rinkCount = levels.size)
        val tgt = level.target
        val pals = if (bullseye) emptyList() else List(n) { FlickPal(it, sim.x[it], sim.y[it], sim.carry[it]) }
        val discs = if (bullseye) {
            (0 until n * FlickRinkRules.DISCS_PER_PLAYER).filter { sim.alive[it] }.map {
                FlickDisc(seatOfDisc(it), sim.x[it], sim.y[it], true, if (tgt == null) 0 else sim.discPoints(it, tgt.x.toDouble(), tgt.y.toDouble(), cfg.band.bullScale))
            }
        } else emptyList()
        val fish = List(sim.nFish) { FlickFish(level.fish[it].x, level.fish[it].y, sim.fishState[it], sim.fishCarrier[it]) }
        val discsLeft = IntArray(n) { FlickRinkRules.DISCS_PER_PLAYER }
        if (bullseye) for (k in 0 until flicksInRink) discsLeft[seatOfDisc(k)]--
        return FlickRinkState(
            phase = phase, style = cfg.style, kind = cfg.kind, activeSeat = activeSeat(), rinkIndex = rinkIndex, rinkCount = levels.size,
            levelId = level.id, pals = pals, discs = discs, fish = fish, fishBanked = fishBanked, fishGoal = FlickRinkRules.FISH_PER_RINK,
            flicksUsed = flicksInRink, flicksLeft = flicksLeft(), gateOpen = sim.gateOpen.toList(), sliderPose = sim.sliderPose.toList(),
            seatScores = seatScore.toList(), seatFlicks = seatFlicks.toList(), discsLeft = if (bullseye) discsLeft.toList() else emptyList(),
            coopRetries = retries, budget = budget(), par = if (bullseye) 0 else level.par, rinkCleared = rinkCleared,
            rinkStars = rinkStars.toList(), matchStars = if (rinkStars.isEmpty()) 0 else rinkStars.min(), turn = turn
        )
    }

    // ---- a flick ----

    fun flick(input: FlickInput, record: Boolean): FlickOutcome {
        if (!canFlick(input)) return FlickOutcome.NONE
        val seat = activeSeat()
        val body = bodyOfNextFlick()
        val launchX = if (bullseye) input.launchX else FlickRinkRules.LAUNCH_DEFAULT_X
        log.add(FlickLogEntry(rinkIndex, seat, input.angleDeci, input.power, launchX))
        if (bullseye) sim.place(body, launchX.toDouble(), FlickRinkRules.LAUNCH_Y.toDouble())
        val speed = FlickRinkRules.speedForPower(input.power)
        sim.recordEvents = true
        val frames = sim.runFlick(body, FlickSim.dirX(input.angleDeci) * speed, FlickSim.dirY(input.angleDeci) * speed, record)
        val steps = sim.stepNo
        val events = ArrayList<FlickEvent>(sim.events)
        val bodies = (0 until sim.bodyCount).count { sim.alive[it] }

        for (e in events) if (e is FlickEvent.FishBanked) { seatScore[e.seat]++; fishBanked++ }
        flicksBySeat[seat]++; seatFlicks[seat]++; flicksInRink++; turn++

        var ended = false
        val tgt = level.target
        if (bullseye) {
            if (tgt != null) events.add(FlickEvent.DiscScored(steps, seat, sim.discPoints(body, tgt.x.toDouble(), tgt.y.toDouble(), cfg.band.bullScale)))
            if (flicksInRink >= n * FlickRinkRules.DISCS_PER_PLAYER) { endBoard(); ended = true }
        } else if (fishBanked >= FlickRinkRules.FISH_PER_RINK) {
            endRink(true); ended = true
        } else if (flicksInRink >= flickLimit()) {
            endRink(false); ended = true
        }
        // Rule 15: after the last seat of a round has flicked the sliding walls swap pose (Solo: after every flick).
        if (!ended && flicksInRink % n == 0 && sim.swapSliders()) events.add(FlickEvent.WallsMoved(steps))
        return FlickOutcome(steps, settled = steps < FlickRinkRules.MAX_STEPS, events = events, frames = frames, stateHash = stateHash(), bodyCount = bodies)
    }

    private fun endRink(cleared: Boolean) {
        rinkCleared = cleared
        if (cleared) {
            val stars = when {
                solo -> FlickRinkRules.soloStars(flicksInRink, level.par)
                coop -> FlickRinkRules.coopStars(flicksInRink, budget())
                else -> 0
            }
            if (stars > 0) rinkStars.add(stars)
        }
        phase = FlickPhase.RINK_DONE
    }

    /** Rules 23 to 25: every disc scores by where it lies; the board is added to the seats' totals. */
    private fun endBoard() {
        val t = level.target
        var soloPoints = 0
        for (k in 0 until flicksInRink) {
            if (t == null) break
            val seat = seatOfDisc(k)
            val dx = sim.x[k] - t.x; val dy = sim.y[k] - t.y
            val dist = StrictMath.sqrt(dx * dx + dy * dy)
            val pts = FlickRinkRules.bullseyePoints(dist, cfg.band.bullScale)
            seatScore[seat] += pts
            if (pts == 5) seatBulls[seat]++
            if (dist < seatBest[seat]) seatBest[seat] = dist
            soloPoints += pts
        }
        rinkCleared = true
        if (solo) rinkStars.add(FlickRinkRules.bullseyeSoloStars(soloPoints))
        phase = FlickPhase.RINK_DONE
    }

    fun nextRink(): Boolean {
        if (!started || phase != FlickPhase.RINK_DONE) return false
        if (coop && !bullseye && !rinkCleared) return false
        log.add(FlickLogEntry(rinkIndex, -1, ADVANCE_NEXT, 0, FlickRinkRules.LAUNCH_DEFAULT_X))
        if (rinkIndex + 1 < levels.size) {
            rinkIndex++
            retries = 0
            beginRink()
        } else {
            phase = FlickPhase.MATCH_DONE
        }
        return true
    }

    fun retryRink(): Boolean {
        if (!started || phase != FlickPhase.RINK_DONE || !coop || bullseye || rinkCleared) return false
        log.add(FlickLogEntry(rinkIndex, -1, ADVANCE_RETRY, 0, FlickRinkRules.LAUNCH_DEFAULT_X))
        retries = (retries + 1).coerceAtMost(FlickRinkRules.MAX_COOP_RETRIES)
        beginRink()
        return true
    }

    // ---- results ----

    fun summary(): FlickMatchSummary? {
        if (!started) return null
        val together = solo || coop
        val cleared = together && rinkStars.size == levels.size && phase == FlickPhase.MATCH_DONE
        val winners = when {
            together -> if (cleared) (0 until n).toList() else emptyList()
            else -> turnWinners()
        }
        return FlickMatchSummary(
            winners = winners, seatScores = seatScore.toList(), seatFlicks = seatFlicks.toList(), flicksUsed = seatFlicks.sum(),
            stars = if (cleared) rinkStars.min() else 0, cleared = cleared, rinkStars = rinkStars.toList()
        )
    }

    /**
     * Rule 17 and 24: most fish (or points) wins; ties go to the fewest flicks (fish run) or the most bullseyes and then the
     * closest single disc (bullseye); still tied: all of them win.
     */
    private fun turnWinners(): List<Int> {
        var best = (0 until n).filter { seatScore[it] == seatScore.max() }
        if (best.size > 1) {
            if (bullseye) {
                val bulls = best.maxOf { seatBulls[it] }
                best = best.filter { seatBulls[it] == bulls }
                if (best.size > 1) {
                    val closest = best.minOf { seatBest[it] }
                    best = best.filter { seatBest[it] == closest }
                }
            } else {
                val fewest = best.minOf { seatFlicks[it] }
                best = best.filter { seatFlicks[it] == fewest }
            }
        }
        return best
    }

    // ---- bots, helper ----

    fun probe(input: FlickInput): FlickProbe? {
        if (!canFlick(input)) return null
        val body = bodyOfNextFlick()
        val seat = activeSeat()
        val sc = scratch
        sc.copyFrom(sim)
        sc.recordEvents = true
        if (bullseye) sc.place(body, (if (bullseye) input.launchX else FlickRinkRules.LAUNCH_DEFAULT_X).toDouble(), FlickRinkRules.LAUNCH_Y.toDouble())
        val speed = FlickRinkRules.speedForPower(input.power)
        sc.runFlick(body, FlickSim.dirX(input.angleDeci) * speed, FlickSim.dirY(input.angleDeci) * speed, false)
        var picked = false; var banked = 0; var splashed = false; var flipped = 0; var bumps = 0
        for (e in sc.events) when (e) {
            is FlickEvent.FishCollected -> if (e.seat == body) picked = true
            is FlickEvent.RelayPass -> if (e.toSeat == body) picked = true
            is FlickEvent.FishBanked -> banked++
            is FlickEvent.Splash -> if (e.seat == body) splashed = true
            is FlickEvent.GateFlipped -> flipped++
            is FlickEvent.WallBump, is FlickEvent.PalBump -> bumps++
            else -> {}
        }
        val t = level.target
        var points = 0
        var moved = 0
        var margin = 0
        if (bullseye && t != null) {
            points = sc.discPoints(body, t.x.toDouble(), t.y.toDouble(), cfg.band.bullScale)
            for (k in 0 until sc.bodyCount) {
                if (k == body || !sim.alive[k]) continue
                val dx = sc.x[k] - sim.x[k]; val dy = sc.y[k] - sim.y[k]
                if (dx * dx + dy * dy > 400.0) moved++
            }
            val totals = IntArray(n)
            for (k in 0 until sc.bodyCount) if (sc.alive[k]) totals[seatOfDisc(k)] += sc.discPoints(k, t.x.toDouble(), t.y.toDouble(), cfg.band.bullScale)
            var rival = 0
            for (s in 0 until n) if (s != seat && totals[s] > rival) rival = totals[s]
            margin = totals[seat] - rival
        }
        return FlickProbe(
            steps = sc.stepNo, endX = sc.x[body], endY = sc.y[body], picked = picked, banked = banked, bankedTotal = sc.fishBankedCount(),
            carrying = sc.carry[body] >= 0, splashed = splashed, gatesFlipped = flipped, gatesOpen = sc.gatesOpenCount(),
            endsInDrift = sc.inDriftNow(body), bumps = bumps, points = points, othersMoved = moved, margin = margin, fishFree = sc.fishFreeCount()
        )
    }

    fun previewInto(input: FlickInput, maxLength: Int, out: FloatArray): Int {
        if (!canFlick(input)) return 0
        val len = minOf(maxLength, seatAssist(activeSeat()).lengthUnits)
        if (len <= 0) return 0
        val body = bodyOfNextFlick()
        val sc = scratch
        sc.copyFrom(sim)
        if (bullseye) sc.place(body, input.launchX.toDouble(), FlickRinkRules.LAUNCH_Y.toDouble())
        val speed = FlickRinkRules.speedForPower(input.power)
        return sc.trace(body, FlickSim.dirX(input.angleDeci) * speed, FlickSim.dirY(input.angleDeci) * speed, len.toDouble(), if (len >= FlickRinkRules.LONG_HELPER_UNITS) 1 else 0, out)
    }

    // ---- hash, save ----

    fun stateHash(): Long {
        if (!started) return 0L
        var h = FNV_OFFSET
        h = mix(h, FlickRinkRules.PHYSICS_VERSION.toLong())
        h = mix(h, phase.ordinal.toLong()); h = mix(h, rinkIndex.toLong()); h = mix(h, flicksInRink.toLong())
        h = mix(h, retries.toLong()); h = mix(h, turn.toLong()); h = mix(h, if (rinkCleared) 1L else 0L)
        for (i in 0 until sim.bodyCount) {
            h = mix(h, if (sim.alive[i]) 1L else 0L)
            h = mix(h, Math.round(sim.x[i] * 16.0)); h = mix(h, Math.round(sim.y[i] * 16.0)); h = mix(h, sim.carry[i].toLong())
        }
        for (f in 0 until sim.nFish) { h = mix(h, sim.fishState[f].toLong()); h = mix(h, sim.fishCarrier[f].toLong()) }
        for (g in 0 until sim.nGates) h = mix(h, if (sim.gateOpen[g]) 1L else 0L)
        for (s in sim.sliderPose) h = mix(h, s.toLong())
        for (i in 0 until n) { h = mix(h, seatScore[i].toLong()); h = mix(h, seatFlicks[i].toLong()); h = mix(h, seatBulls[i].toLong()) }
        for (s in rinkStars) h = mix(h, s.toLong())
        return h
    }

    private fun mix(h: Long, v: Long): Long {
        var x = h
        var b = v
        for (k in 0 until 8) {
            x = (x xor (b and 0xff)) * FNV_PRIME
            b = b ushr 8
        }
        return x
    }

    fun toSave(): FlickSave? {
        if (!started || phase == FlickPhase.MATCH_DONE || phase == FlickPhase.SETUP) return null
        return FlickSave(
            style = cfg.style.id, kind = cfg.kind.id, band = cfg.band.id, length = cfg.length.id, relaxed = cfg.relaxed,
            dragAnywhere = cfg.dragAnywhere, switchAiming = cfg.switchAiming, assistFor = cfg.assistFor, assist = cfg.assist.id,
            difficulty = cfg.difficulty.name, budget = cfg.budget, soloRink = cfg.soloRink, seed = cfg.seed, firstSeat = cfg.firstSeat,
            seats = seats.map { FlickSeatSave(it.playerId, it.isBot) }, coopRetries = retries, log = log.toList(), stateHash = stateHash()
        )
    }

    /** Applies a saved log to a freshly started match. False when any entry does not fit the match as it unfolds. */
    fun replay(entries: List<FlickLogEntry>): Boolean {
        for (e in entries) {
            if (e.seat == -1) {
                val ok = if (e.angleDeci == ADVANCE_NEXT) nextRink() else retryRink()
                if (!ok) return false
            } else {
                if (phase != FlickPhase.AIMING || e.rink != rinkIndex || e.seat != activeSeat()) return false
                val input = FlickInput(e.angleDeci, e.power, e.launchX)
                if (!canFlick(input)) return false
                flick(input, false)
            }
        }
        return true
    }

    companion object {
        const val ADVANCE_NEXT = 0
        const val ADVANCE_RETRY = 1
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        /** Start spots of the four seats (rink units), all inside the igloo ring. */
        val START_X = doubleArrayOf(240.0, 360.0, 240.0, 360.0)
        val START_Y = doubleArrayOf(810.0, 810.0, 890.0, 890.0)

        /** The levels of a match in play order. Callers check [fits] first. */
        fun levelsFor(cfg: FlickConfig, pool: List<RinkLevel>): List<RinkLevel> {
            val solo = cfg.style == FlickStyle.SOLO
            return if (cfg.kind == FlickGameKind.BULLSEYE) {
                val boards = pool.filter { it.isBoard }
                if (boards.isEmpty()) return emptyList()
                val numbers = if (solo) listOf(FlickRinkRules.soloBoardFor(cfg.soloRink)) else FlickRinkRules.boardsFor(cfg.band, cfg.length)
                numbers.map { boards[(it - 1).coerceIn(0, boards.lastIndex)] }
            } else {
                val rinks = pool.filter { !it.isBoard }
                if (rinks.isEmpty()) return emptyList()
                val numbers = if (solo) listOf(cfg.soloRink) else FlickRinkLevels.matchRinks(cfg.band, cfg.length)
                numbers.map { rinks[(it - 1).coerceIn(0, rinks.lastIndex)] }
            }
        }

        /** Whether [config] can be played by [seats] seats with [levels]: Turns needs 2 to 4, Co-op 2 to 4 (fish run only), Solo exactly 1. */
        fun fits(config: FlickConfig, seats: Int, levels: List<RinkLevel>): Boolean {
            if (seats !in 1..FlickRinkRules.MAX_SEATS) return false
            val ok = when (config.style) {
                FlickStyle.TURNS -> seats >= 2
                FlickStyle.COOP -> seats >= 2 && config.kind == FlickGameKind.FISH_RUN
                FlickStyle.SOLO -> seats == 1
            }
            return ok && levelsFor(config, levels).isNotEmpty()
        }
    }
}
