package com.gamesuite.games.stackzoo

import com.gamesuite.settings.CpuDifficulty

/**
 * The full Stack Zoo engine: the bag, the three modes, turns, the assist, scoring, the replay log and the resume
 * snapshot, on top of the deterministic [ZooWorld]. See docs/design/kids-games/stack-zoo.md for the rules (section 2.7).
 * Not thread safe: the wrapper locks.
 */
class ZooEngine : StackZooEngine {
    private var config = ZooConfig()
    private var seats: List<ZooSeat> = emptyList()
    private var seed = 1L
    internal val world = ZooWorld()
    private var rng = ZooRng(1L)

    internal var phase = ZooPhase.READY
        private set
    internal var pieceAnimal = IntArray(0)
        private set
    internal val bag = ArrayList<Int>()
    internal val hands = ArrayList<ArrayList<Int>>()
    private val pieceOwner = IntArray(ZooPhysics.MAX_BODIES) { -1 }
    private var usedCount = 0
    private var bagTotal = 0
    internal var activeSeat = 0
        private set
    internal var turnNumber = 0
        private set
    private var lives = ZooRules.SOLO_LIVES
    private var holdSteps = 0
    private var settleSteps = 0
    private var resultSteps = 0
    private var chosen = -1
    private var fellThisTurn = 0
    private var fellLastTurn = 0
    private val pendingFallen = ArrayList<Int>()
    private var placements = IntArray(4)
    private var clean = IntArray(4)
    private var tallestSeat = -1
    private var tallestHeight = 0.0
    private var pendingWinner = -1
    private var outcome = ZooOutcome.NONE
    private var winnerMask = 0
    private var stars = 0
    private var paused = false
    private var towerH = 0.0
    private var event = ZooEvent.NONE
    private var eventArg = 0
    private var eventSerial = 0
    private val releases = ArrayList<ZooReleaseEvent>()

    private var version = 0
    private var cachedVersion = -1
    private var cached = ZooState.EMPTY

    internal val cfg: ZooConfig get() = config
    internal val seatList: List<ZooSeat> get() = seats
    internal val seedValue: Long get() = seed
    internal val stepIndex: Long get() = world.stepIndex
    internal val chosenPiece: Int get() = chosen

    private val n: Int get() = if (seats.isEmpty()) 1 else seats.size
    private val handSize: Int get() = config.length.handSize

    // ---- setup ------------------------------------------------------------------------------------------------

    override fun setup(config: ZooConfig, seats: List<ZooSeat>) {
        require(seats.size in 1..4) { "Stack Zoo seats 1 to 4" }
        this.config = config
        this.seats = seats
        seed = if (config.seed == 0L) 1L else config.seed
        resetGame()
    }

    private fun resetGame() {
        world.reset()
        world.tuning = if (config.relaxed) ZooTuning.relaxed() else ZooTuning.normal()
        phase = ZooPhase.READY
        bag.clear()
        hands.clear()
        for (i in 0 until n) hands.add(ArrayList())
        for (i in pieceOwner.indices) pieceOwner[i] = -1
        pieceAnimal = IntArray(0)
        usedCount = 0
        bagTotal = 0
        activeSeat = 0
        turnNumber = 0
        lives = ZooRules.SOLO_LIVES
        holdSteps = 0
        settleSteps = 0
        resultSteps = 0
        chosen = -1
        fellThisTurn = 0
        fellLastTurn = 0
        pendingFallen.clear()
        placements = IntArray(n)
        clean = IntArray(n)
        tallestSeat = -1
        tallestHeight = 0.0
        pendingWinner = -1
        outcome = ZooOutcome.NONE
        winnerMask = 0
        stars = 0
        paused = false
        towerH = 0.0
        releases.clear()
        event = ZooEvent.NONE
        eventArg = 0
        touch()
    }

    override fun start() {
        resetGame()
        deal()
        phase = ZooPhase.READY
        touch()
    }

    private fun deal() {
        rng = ZooRng(seed)
        val types = ArrayList<Int>()
        if (config.mode == ZooMode.SOLO_BALANCE) {
            for (t in 0 until ANIMALS) types += t
        } else {
            for (c in 0 until ZooRules.BAG_COPIES) for (t in 0 until ANIMALS) types += t
        }
        rng.shuffle(types)
        pieceAnimal = IntArray(types.size) { types[it] }
        when (config.mode) {
            ZooMode.SOLO_BALANCE -> {
                usedCount = ANIMALS
                bagTotal = ANIMALS
                for (id in 0 until usedCount) bag += id
            }
            ZooMode.COOP_TOWER -> {
                usedCount = config.goal.bag
                bagTotal = usedCount
                for (id in 0 until usedCount) bag += id
            }
            ZooMode.TAKE_TURNS -> {
                usedCount = n * handSize
                bagTotal = usedCount
                var id = 0
                for (r in 0 until handSize) for (s in 0 until n) hands[s].add(id++)
            }
        }
    }

    override fun begin() {
        if (phase != ZooPhase.READY || pieceAnimal.isEmpty()) return
        startTurn()
        setEvent(ZooEvent.BEGAN, 0)
    }

    private fun startTurn() {
        phase = ZooPhase.CHOOSING
        chosen = -1
        fellThisTurn = 0
        touch()
    }

    private fun setEvent(e: ZooEvent, arg: Int) {
        event = e
        eventArg = arg
        eventSerial++
        touch()
    }

    private fun touch() { version++ }

    // ---- offers and choosing ----------------------------------------------------------------------------------

    private fun offerList(): List<Int> = when (config.mode) {
        ZooMode.TAKE_TURNS -> hands.getOrNull(activeSeat)?.toList() ?: emptyList()
        else -> bag.take(ZooRules.OFFER_SIZE)
    }

    override fun choose(pieceId: Int): Boolean {
        if (phase != ZooPhase.CHOOSING) return false
        if (pieceId !in offerList()) return false
        chosen = pieceId
        phase = ZooPhase.POSITIONING
        setEvent(ZooEvent.CHOSE, pieceId)
        return true
    }

    override fun unchoose(): Boolean {
        if (phase != ZooPhase.POSITIONING) return false
        chosen = -1
        phase = ZooPhase.CHOOSING
        touch()
        return true
    }

    // ---- placement --------------------------------------------------------------------------------------------

    internal fun assistLevel(seat: Int): ZooAssist {
        val a = config.assist
        val on = a == "all" || (a.length == 2 && a[0] == 'p' && a[1] - '1' == seat)
        if (on) return ZooAssist.LARGE
        if (config.oneSwitch && seats.getOrNull(seat)?.isBot == false) return ZooAssist.LARGE
        return ZooAssist.NONE
    }

    private fun frictionFor(animalIndex: Int, seat: Int): Double {
        var f = ZooAnimals.all[animalIndex].friction + world.tuning.frictionBonus
        if (assistLevel(seat) == ZooAssist.LARGE) f += ZooRules.ASSIST_LARGE_FRICTION_BONUS
        return if (f > 1.0) 1.0 else f
    }

    override fun assisted(xQ: Int, angleQ: Int): ZooPlacement {
        if (chosen < 0) return ZooPlacement(ZooPlacer.clampX(xQ), ZooPlacer.clampAngle(angleQ))
        return ZooPlacer.assisted(world, pieceAnimal[chosen], xQ, angleQ, assistLevel(activeSeat))
    }

    override fun release(xQ: Int, angleQ: Int): ZooReleaseResult {
        if (phase != ZooPhase.POSITIONING || chosen < 0) return ZooReleaseResult.NOT_POSITIONING
        val piece = chosen
        val animal = pieceAnimal[piece]
        val rawX = ZooPlacer.clampX(xQ)
        val rawA = ZooPlacer.clampAngle(angleQ)
        val p = ZooPlacer.assisted(world, animal, rawX, rawA, assistLevel(activeSeat))
        val px = p.xQ / ZooPhysics.QUANT
        val ang = p.angleQ / ZooPhysics.QUANT
        val py = world.sweepDown(animal, px, ang)
        world.addBody(piece, animal, activeSeat, px, py, ang, frictionFor(animal, activeSeat))
        pieceOwner[piece] = activeSeat
        towerDirty = true
        if (config.mode == ZooMode.TAKE_TURNS) {
            val h = hands[activeSeat]
            val idx = h.indexOf(piece)
            if (idx >= 0) h.removeAt(idx)
        } else {
            val idx = bag.indexOf(piece)
            if (idx >= 0) bag.removeAt(idx)
        }
        if (releases.size < MAX_RELEASES) releases.add(ZooReleaseEvent(world.stepIndex, piece, rawX, rawA))
        world.clearFallEvents()
        pendingFallen.clear()
        fellThisTurn = 0
        holdSteps = 0
        settleSteps = 0
        placements[activeSeat]++
        chosen = -1
        phase = ZooPhase.SETTLING
        setEvent(ZooEvent.PLACED, piece)
        return ZooReleaseResult.PLACED
    }

    // ---- time -------------------------------------------------------------------------------------------------

    override fun advance(steps: Int) {
        if (paused) return
        for (k in 0 until steps) {
            world.step()
            afterStep()
        }
    }

    private fun afterStep() {
        if (world.fellCount > 0) {
            for (e in 0 until world.fellCount) {
                val piece = world.fellEvents[e]
                if (piece !in pendingFallen) pendingFallen.add(piece)
                fellThisTurn++
            }
            world.clearFallEvents()
        }
        when (phase) {
            ZooPhase.SETTLING -> {
                settleSteps++
                updateHold()
                if (world.restingAll || settleSteps >= ZooPhysics.SETTLE_MAX_STEPS) onSettled()
            }
            ZooPhase.TURN_RESULT -> {
                resultSteps++
                updateHold()
                if (config.mode == ZooMode.COOP_TOWER && holdSteps >= ZooRules.COOP_HOLD_STEPS) {
                    finish(ZooOutcome.COOP_WON)
                } else if (resultSteps >= ZooPhysics.HOLD_STEPS_PER_TURN_RESULT && !waitingForHold()) {
                    endTurn()
                }
            }
            else -> {}
        }
    }

    private fun waitingForHold(): Boolean =
        config.mode == ZooMode.COOP_TOWER && towerH >= config.goal.height && holdSteps < ZooRules.COOP_HOLD_STEPS

    private fun updateHold() {
        if (config.mode != ZooMode.COOP_TOWER) return
        if (!world.asleep || towerDirty) {
            towerH = world.towerHeight()
            towerDirty = false
        }
        val before = holdSteps
        if (towerH >= config.goal.height) holdSteps++ else holdSteps = 0
        if (before == 0 && holdSteps == 1) setEvent(ZooEvent.HOLDING, 0)
        else if (before > 0 && holdSteps == 0) touch()
    }

    private var towerDirty = true

    private fun onSettled() {
        towerH = world.towerHeight()
        towerDirty = false
        val fell = pendingFallen.size
        fellLastTurn = fell
        val seat = activeSeat
        when (config.mode) {
            ZooMode.COOP_TOWER -> if (config.relaxed) bag.addAll(pendingFallen)
            ZooMode.SOLO_BALANCE -> if (fell > 0) {
                if (config.relaxed) bag.addAll(pendingFallen) else lives--
            }
            ZooMode.TAKE_TURNS -> if (config.relaxed) {
                for (p in pendingFallen) hands[pieceOwner[p].coerceIn(0, n - 1)].add(p)
            } else {
                var taken = 0
                for (p in pendingFallen) {
                    if (taken >= ZooRules.TAKE_BACK_CAP) break
                    hands[seat].add(p)
                    taken++
                }
            }
        }
        pendingFallen.clear()
        if (fell == 0) clean[seat]++
        if (config.mode == ZooMode.COOP_TOWER && towerH > tallestHeight + 1e-9) {
            tallestHeight = towerH
            tallestSeat = seat
        }
        if (config.mode == ZooMode.TAKE_TURNS && hands[seat].isEmpty()) pendingWinner = seat
        phase = ZooPhase.TURN_RESULT
        resultSteps = 0
        setEvent(if (fell == 0) ZooEvent.STEADY else ZooEvent.FELL, fell)
    }

    private fun endTurn() {
        when (config.mode) {
            ZooMode.COOP_TOWER -> {
                if (holdSteps >= ZooRules.COOP_HOLD_STEPS) { finish(ZooOutcome.COOP_WON); return }
                if (bag.isEmpty()) { finish(ZooOutcome.COOP_LOST); return }
            }
            ZooMode.SOLO_BALANCE -> {
                if ((!config.relaxed && lives <= 0) || bag.isEmpty()) { finish(ZooOutcome.SOLO_DONE); return }
            }
            ZooMode.TAKE_TURNS -> {
                if (pendingWinner >= 0) { finish(ZooOutcome.WINNER); return }
                if (turnNumber + 1 >= ZooRules.ROUND_CAP_FACTOR * handSize * n) { finish(ZooOutcome.TIE); return }
            }
        }
        activeSeat = (activeSeat + 1) % n
        turnNumber++
        startTurn()
    }

    private fun standingOf(seat: Int): Int {
        var c = 0
        for (i in 0 until ZooPhysics.MAX_BODIES) if (world.live[i] && !world.fallen[i] && pieceOwner[i] == seat) c++
        return c
    }

    private fun standingTotal(): Int = world.liveCount()

    private fun finish(result: ZooOutcome) {
        var o = result
        var mask = 0
        when (result) {
            ZooOutcome.COOP_WON -> {
                mask = (1 shl n) - 1
                val frac = if (bagTotal == 0) 0.0 else bag.size.toDouble() / bagTotal
                stars = if (frac >= ZooRules.COOP_STAR3_FRACTION) 3 else if (frac >= ZooRules.COOP_STAR2_FRACTION) 2 else 1
            }
            ZooOutcome.COOP_LOST -> mask = if (standingTotal() > 0) (1 shl n) - 1 else 0
            ZooOutcome.SOLO_DONE -> mask = if (standingTotal() >= 1) 1 else 0
            ZooOutcome.WINNER, ZooOutcome.TIE -> {
                if (pendingWinner >= 0) {
                    o = ZooOutcome.WINNER
                    mask = 1 shl pendingWinner
                } else {
                    var best = Int.MAX_VALUE
                    for (s in 0 until n) if (hands[s].size < best) best = hands[s].size
                    for (s in 0 until n) if (hands[s].size == best) mask = mask or (1 shl s)
                    o = if (Integer.bitCount(mask) == 1) ZooOutcome.WINNER else ZooOutcome.TIE
                }
            }
            ZooOutcome.NONE -> {}
        }
        outcome = o
        winnerMask = mask
        phase = ZooPhase.OVER
        setEvent(
            when (o) {
                ZooOutcome.COOP_WON -> ZooEvent.WON
                ZooOutcome.COOP_LOST -> ZooEvent.LOST
                else -> ZooEvent.FINISHED
            }, stars
        )
    }

    override fun pause() {
        if (!paused) { paused = true; touch() }
    }

    override fun resume() {
        if (paused) { paused = false; touch() }
    }

    override fun playAgain() {
        if (seats.isEmpty()) return
        val next = ZooRng(seed).nextLong()
        seed = if (next == 0L) 1L else next
        start()
    }

    // ---- views ------------------------------------------------------------------------------------------------

    override fun state(): ZooState {
        if (cachedVersion == version) return cached
        val offer = if (phase == ZooPhase.CHOOSING || phase == ZooPhase.POSITIONING) offerList() else emptyList()
        val seatStates = List(n) { s ->
            ZooSeatState(
                name = seats.getOrNull(s)?.name ?: "", isBot = seats.getOrNull(s)?.isBot ?: false,
                handCount = if (config.mode == ZooMode.TAKE_TURNS) hands[s].size else 0,
                stacked = standingOf(s), placements = placements[s], clean = clean[s]
            )
        }
        cached = ZooState(
            phase = phase, mode = config.mode, activeSeat = activeSeat, seats = seatStates,
            offer = offer, offerAnimals = offer.map { pieceAnimal[it] },
            chosen = chosen, chosenAnimal = if (chosen >= 0) pieceAnimal[chosen] else -1,
            bagLeft = bag.size, bagTotal = bagTotal,
            goalHeight = if (config.mode == ZooMode.COOP_TOWER) config.goal.height else 0.0,
            towerHeight = towerH,
            livesLeft = if (config.relaxed) -1 else lives, lives = ZooRules.SOLO_LIVES,
            fellLastTurn = fellLastTurn, standing = standingTotal(), stars = stars,
            holding = holdSteps > 0, outcome = outcome, winnerMask = winnerMask,
            handSize = handSize, turnNumber = turnNumber,
            activeAssisted = assistLevel(activeSeat) == ZooAssist.LARGE,
            paused = paused, event = event, eventArg = eventArg, eventSerial = eventSerial
        )
        cachedVersion = version
        return cached
    }

    override fun fillBodies(out: ZooBodyBuffer) {
        var c = 0
        for (i in 0 until ZooPhysics.MAX_BODIES) {
            if (!world.live[i]) continue
            out.pieceId[c] = i
            out.animalIndex[c] = world.animal[i]
            out.owner[c] = world.owner[i]
            out.x[c] = world.x[i]
            out.y[c] = world.y[i]
            out.angle[c] = world.a[i]
            out.fallen[c] = world.fallen[i]
            out.asleep[c] = world.rest[i] >= ZooPhysics.SLEEP_STEPS
            c++
        }
        out.count = c
        out.baseAngle = world.a[ZooWorld.LOG]
        out.baseOmega = world.w[ZooWorld.LOG]
    }

    override fun stateHash(): Long = world.stateHash()

    override fun replay(): ZooReplay = ZooReplay(
        physicsVersion = ZooPhysics.PHYSICS_VERSION, seed = seed, mode = modeId(config.mode), goal = goalId(config.goal),
        length = lengthId(config.length), assist = config.assist, relaxed = config.relaxed, oneSwitch = config.oneSwitch,
        seats = n, releases = releases.toList()
    )

    // ---- save and restore -------------------------------------------------------------------------------------

    override fun toSave(): ZooSave? {
        if (phase == ZooPhase.READY || phase == ZooPhase.OVER || pieceAnimal.isEmpty()) return null
        val bodies = ArrayList<ZooBodySave>()
        for (i in 0 until ZooPhysics.MAX_BODIES) {
            if (!world.live[i]) continue
            bodies += ZooBodySave(
                i, world.animal[i], world.owner[i], world.x[i], world.y[i], world.a[i],
                world.vx[i], world.vy[i], world.w[i], world.rest[i], world.fallen[i]
            )
        }
        val savedPhase = if (phase == ZooPhase.POSITIONING) ZooPhase.CHOOSING else phase
        return ZooSave(
            physicsVersion = ZooPhysics.PHYSICS_VERSION, mode = modeId(config.mode), goal = goalId(config.goal),
            length = lengthId(config.length), assist = config.assist, relaxed = config.relaxed, oneSwitch = config.oneSwitch,
            difficulty = config.difficulty.name, seed = seed, rngCalls = rng.calls, stepIndex = world.stepIndex,
            baseAngle = world.a[ZooWorld.LOG], baseOmega = world.w[ZooWorld.LOG], baseRest = world.logRest,
            bodies = bodies, pieceAnimals = pieceAnimal.toList(), hands = hands.map { it.toList() }, bag = bag.toList(),
            bagTotal = bagTotal, activeSeat = activeSeat, turnNumber = turnNumber, lives = lives, holdSteps = holdSteps,
            phase = savedPhase.name, settleSteps = settleSteps, resultSteps = resultSteps, fellThisTurn = fellThisTurn,
            fellLastTurn = fellLastTurn, pendingFallen = pendingFallen.toList(), placements = placements.toList(),
            clean = clean.toList(), tallestSeat = tallestSeat, tallestHeight = tallestHeight, pendingWinner = pendingWinner,
            releases = releases.toList(), stateHash = world.stateHash(), playerIds = seats.map { it.playerId }
        )
    }

    override fun restore(save: ZooSave): Boolean {
        try {
            if (seats.isEmpty()) return false
            if (!ZooSaveValidator.validate(save, seats.size)) return false
            if (save.playerIds != seats.map { it.playerId }) return false
            val newConfig = ZooConfig(
                mode = ZooMode.fromId(save.mode), goal = ZooGoal.fromId(save.goal), length = ZooLength.fromId(save.length),
                assist = save.assist, relaxed = save.relaxed, oneSwitch = save.oneSwitch,
                difficulty = CpuDifficulty.valueOf(save.difficulty), seed = save.seed
            )
            val tmp = ZooWorld(if (newConfig.relaxed) ZooTuning.relaxed() else ZooTuning.normal())
            val saveConfig = config
            config = newConfig // frictionFor reads the config
            val tuningBonus = tmp.tuning.frictionBonus
            for (b in save.bodies) {
                var f = ZooAnimals.all[b.animalIndex].friction + tuningBonus
                if (assistLevel(b.owner) == ZooAssist.LARGE) f += ZooRules.ASSIST_LARGE_FRICTION_BONUS
                tmp.setBody(b.pieceId, b.animalIndex, b.owner, b.x, b.y, b.angle, b.vx, b.vy, b.omega, b.restSteps, b.fallen, if (f > 1.0) 1.0 else f)
            }
            tmp.setLog(save.baseAngle, save.baseOmega, save.baseRest)
            tmp.stepIndex = save.stepIndex
            if (tmp.stateHash() != save.stateHash) {
                config = saveConfig
                return false
            }
            // commit
            seed = save.seed
            world.copyFrom(tmp)
            world.stepIndex = save.stepIndex
            rng = ZooRng(seed)
            pieceAnimal = save.pieceAnimals.toIntArray()
            usedCount = if (newConfig.mode == ZooMode.SOLO_BALANCE) ANIMALS else if (newConfig.mode == ZooMode.COOP_TOWER) newConfig.goal.bag else seats.size * newConfig.length.handSize
            bagTotal = save.bagTotal
            bag.clear(); bag.addAll(save.bag)
            hands.clear()
            for (h in save.hands) hands.add(ArrayList(h))
            for (i in pieceOwner.indices) pieceOwner[i] = -1
            for (b in save.bodies) pieceOwner[b.pieceId] = b.owner
            activeSeat = save.activeSeat
            turnNumber = save.turnNumber
            lives = save.lives
            holdSteps = save.holdSteps
            phase = ZooPhase.valueOf(save.phase)
            settleSteps = save.settleSteps
            resultSteps = save.resultSteps
            chosen = -1
            fellThisTurn = save.fellThisTurn
            fellLastTurn = save.fellLastTurn
            pendingFallen.clear(); pendingFallen.addAll(save.pendingFallen)
            placements = save.placements.toIntArray()
            clean = save.clean.toIntArray()
            tallestSeat = save.tallestSeat
            tallestHeight = save.tallestHeight
            pendingWinner = save.pendingWinner
            outcome = ZooOutcome.NONE
            winnerMask = 0
            stars = 0
            paused = false
            releases.clear(); releases.addAll(save.releases)
            towerDirty = true
            towerH = world.towerHeight()
            towerDirty = false
            world.clearFallEvents()
            event = ZooEvent.NONE
            eventArg = 0
            touch()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    internal fun ownerOfPiece(piece: Int): Int = pieceOwner.getOrElse(piece) { -1 }

    companion object {
        const val ANIMALS = 20
        const val MAX_RELEASES = 1000

        fun modeId(m: ZooMode) = when (m) { ZooMode.COOP_TOWER -> "coop"; ZooMode.TAKE_TURNS -> "turns"; ZooMode.SOLO_BALANCE -> "solo" }
        fun goalId(g: ZooGoal) = when (g) { ZooGoal.LITTLE -> "little"; ZooGoal.MEDIUM -> "medium"; ZooGoal.TALL -> "tall" }
        fun lengthId(l: ZooLength) = when (l) { ZooLength.QUICK -> "quick"; ZooLength.STANDARD -> "standard"; ZooLength.LONG -> "long" }
    }
}

/** Rebuilds a game from its replay log: the proof that a seed plus the releases reproduces every pose. */
object ZooReplayRunner {
    /** Returns the engine after replaying every release, or null when the log is not usable. */
    fun run(replay: ZooReplay, seats: List<ZooSeat>, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): ZooEngine? {
        if (replay.version != 1 || replay.physicsVersion != ZooPhysics.PHYSICS_VERSION) return null
        if (seats.size != replay.seats) return null
        val e = ZooEngine()
        e.setup(
            ZooConfig(
                mode = ZooMode.fromId(replay.mode), goal = ZooGoal.fromId(replay.goal), length = ZooLength.fromId(replay.length),
                assist = replay.assist, relaxed = replay.relaxed, oneSwitch = replay.oneSwitch, difficulty = difficulty, seed = replay.seed
            ),
            seats
        )
        e.start()
        e.begin()
        for (r in replay.releases) {
            val now = e.stepIndex
            if (r.stepIndex < now || r.stepIndex - now > 10_000_000L) return null
            var left = r.stepIndex - now
            while (left > 0) {
                val chunk = if (left > 100_000L) 100_000 else left.toInt()
                e.advance(chunk)
                left -= chunk
            }
            if (!e.choose(r.pieceId)) return null
            if (e.release(r.xQ, r.angleQ) != ZooReleaseResult.PLACED) return null
        }
        return e
    }
}
