package com.gamesuite.games.gemcascade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class GemStatus { PLAYING, WON, OVER }

/**
 * The Gem Cascade engine: one mutable game, fully deterministic from its [GemRng] and the actions applied.
 * Rendering agnostic: every operation returns a [MoveResult] with the typed step log ([GemEvent]).
 *
 * Operations: [swap], [rotate], [useBooster], [elapse] (Lightning's clock), all reachable through [apply].
 * Queries: [legalActions], [copy] (a deep clone including the RNG, so a simulated move is exactly the real one).
 */
class GemEngine internal constructor(
    val rules: ModeRules,
    val levelId: Int,
    val starThresholds: List<Int>,
    var board: GemBoard,
    var rng: GemRng,
    val kinds: Int
) {
    var status: GemStatus = GemStatus.PLAYING
        internal set
    var overReason: OverReason? = null
        internal set
    var score: Int = 0
        internal set
    var movesMade: Int = 0
        internal set
    var movesLeft: Int = -1
        internal set
    var timeLeftMs: Long = 0
        internal set
    var elapsedMs: Long = 0
        internal set
    var speedMeter: Int = 0
        internal set
    var burstUntilMs: Long = -1
        internal set
    var lastMoveAtMs: Long = -1
        internal set
    var classicLevel: Int = 1
        internal set
    var stars: Int = 0
        internal set
    var chainBest: Int = 0
        internal set
    var serial: Int = 0
        internal set
    var goals: List<GoalState> = emptyList()
        internal set

    // counters feeding the goals (all bounded by what a board can hold per move; see the snapshot validator)
    var gemsCleared: Int = 0; internal set
    val clearedByKind = IntArray(GemKinds.COUNT)
    var iceCleared: Int = 0; internal set
    var stonesBroken: Int = 0; internal set
    var locksBroken: Int = 0; internal set
    var treasuresCollected: Int = 0; internal set
    var butterfliesEscaped: Int = 0; internal set
    var bombsDefused: Int = 0; internal set
    var specialsMade: Int = 0; internal set
    var spidersSquashed: Int = 0; internal set
    var bombsSpawned: Int = 0; internal set
    var obstacleHits: Int = 0; internal set
    val boosterUsed = IntArray(GemBoosters.defs.size)

    /** Every accepted action in order (the replay). Stops growing at [LOG_CAP]; [logComplete] then turns false. */
    val actionLog = ArrayList<GemAction>()
    var logComplete: Boolean = true
        internal set

    val rows: Int get() = board.rows
    val cols: Int get() = board.cols

    // ------------------------------------------------------------------------------------------
    // Public operations
    // ------------------------------------------------------------------------------------------

    fun apply(action: GemAction): MoveResult = when (action) {
        is GemAction.SwapAction -> swap(action.a, action.b)
        is GemAction.RotateAction -> rotate(action.topLeft, action.clockwise)
        is GemAction.BoosterAction -> useBooster(action.booster, action.at, action.other)
        is GemAction.Elapse -> elapse(action.ms)
    }

    fun legalActions(): List<GemAction> = if (status != GemStatus.PLAYING) emptyList() else GemMoves.legal(board, rules)

    fun swap(a: Pos, b: Pos): MoveResult {
        preCheck()?.let { return MoveResult.rejected(it) }
        if (!rules.allowSwap) return MoveResult.rejected(RejectReason.NOT_ALLOWED)
        if (!board.inside(a) || !board.inside(b)) return MoveResult.rejected(RejectReason.OUT_OF_BOARD)
        if (abs(a.r - b.r) + abs(a.c - b.c) != 1) return MoveResult.rejected(RejectReason.NOT_ADJACENT)
        if (!board[a].isMovable || !board[b].isMovable) return MoveResult.rejected(RejectReason.BLOCKED)
        val ev = ArrayList<GemEvent>()
        ev += GemEvent.Swap(a, b)
        board.swapCells(a, b)
        val atB = board[b] // the piece the player moved
        val atA = board[a]
        var forced: Forced? = null
        var groups: List<MatchGroup> = emptyList()
        if (atA.special != SpecialKind.NONE && atB.special != SpecialKind.NONE) {
            forced = comboForced(a, atA, b, atB)
        } else if (atA.special == SpecialKind.PRISM || atB.special == SpecialKind.PRISM) {
            val prismAt = if (atA.special == SpecialKind.PRISM) a else b
            val other = if (prismAt == a) atB else atA
            forced = prismForced(prismAt, other)
        } else {
            groups = GemMatcher.groups(board)
            if (groups.isEmpty()) {
                board.swapCells(a, b)
                ev += GemEvent.SwapBack(a, b)
                return MoveResult.rejected(RejectReason.NO_MATCH, ev)
            }
        }
        record(GemAction.SwapAction(a, b))
        beginMove(ev)
        cascade(ev, groups, forced, listOf(b, a))
        endOfTurn(ev, consumedMove = true)
        return MoveResult.ok(ev)
    }

    fun rotate(topLeft: Pos, clockwise: Boolean): MoveResult {
        preCheck()?.let { return MoveResult.rejected(it) }
        if (!rules.allowRotate) return MoveResult.rejected(RejectReason.NOT_ALLOWED)
        if (topLeft.r < 0 || topLeft.c < 0 || topLeft.r + 1 >= board.rows || topLeft.c + 1 >= board.cols) {
            return MoveResult.rejected(RejectReason.OUT_OF_BOARD)
        }
        val block = listOf(topLeft, Pos(topLeft.r, topLeft.c + 1), Pos(topLeft.r + 1, topLeft.c), Pos(topLeft.r + 1, topLeft.c + 1))
        if (block.any { !board[it].isMovable }) return MoveResult.rejected(RejectReason.BLOCKED)
        val steps = GemMoves.rotationSteps(board, topLeft, clockwise)
        val ev = ArrayList<GemEvent>()
        if (steps == 0) {
            ev += GemEvent.RotateBack(topLeft, clockwise)
            return MoveResult.rejected(RejectReason.NO_MATCH, ev)
        }
        ev += GemEvent.Rotate(topLeft, clockwise, steps)
        repeat(steps) { GemRotation.rotate(board, topLeft, clockwise) }
        val groups = GemMatcher.groups(board)
        record(GemAction.RotateAction(topLeft, clockwise))
        beginMove(ev)
        cascade(ev, groups, null, block)
        endOfTurn(ev, consumedMove = true)
        return MoveResult.ok(ev)
    }

    /** Lightning's clock: [ms] of play time passed (the caller excludes pauses). */
    fun elapse(ms: Long): MoveResult {
        if (status != GemStatus.PLAYING) return MoveResult.rejected(RejectReason.GAME_OVER)
        if (ms <= 0) return MoveResult.ok(emptyList())
        elapsedMs += ms
        record(GemAction.Elapse(ms))
        val ev = ArrayList<GemEvent>()
        if (rules.timed) {
            timeLeftMs -= ms
            if (timeLeftMs <= 0) {
                timeLeftMs = 0
                over(OverReason.TIME_UP, ev)
            }
        }
        return MoveResult.ok(ev)
    }

    fun useBooster(kind: BoosterKind, at: Pos?, other: Pos?): MoveResult {
        preCheck(booster = true)?.let { return MoveResult.rejected(it) }
        val def = GemBoosters.def(kind)
        val slot = GemBoosters.defs.indexOfFirst { it.kind == kind }
        if (boosterUsed[slot] >= def.capPerLevel) return MoveResult.rejected(RejectReason.BOOSTER_CAP)
        val ev = ArrayList<GemEvent>()
        var forced: Forced? = null
        var groups: List<MatchGroup> = emptyList()
        var shuffleDone = false
        when (kind) {
            BoosterKind.HAMMER -> {
                if (at == null || !board.inside(at)) return MoveResult.rejected(RejectReason.BAD_TARGET)
                val t = board[at].type
                if (t != PieceType.GEM && t != PieceType.STONE && t != PieceType.SPIDER) return MoveResult.rejected(RejectReason.BAD_TARGET)
                used(slot, kind, at, null, ev)
                forced = Forced(setOf(at), emptyList(), emptyList(), ClearCause.BOOSTER, emptyList(), 0)
            }
            BoosterKind.COLOUR_BOMB -> {
                if (at == null || !board.inside(at) || board[at].matchKind < 0) return MoveResult.rejected(RejectReason.BAD_TARGET)
                used(slot, kind, at, null, ev)
                forced = Forced(GemSpecials.colourCells(board, board[at].matchKind, at), emptyList(), emptyList(), ClearCause.BOOSTER, emptyList(), 0)
            }
            BoosterKind.SWAP_ANY -> {
                if (!rules.allowSwap) return MoveResult.rejected(RejectReason.NOT_ALLOWED)
                if (at == null || other == null || !board.inside(at) || !board.inside(other)) return MoveResult.rejected(RejectReason.BAD_TARGET)
                if (abs(at.r - other.r) + abs(at.c - other.c) != 1) return MoveResult.rejected(RejectReason.NOT_ADJACENT)
                if (!board[at].isMovable || !board[other].isMovable) return MoveResult.rejected(RejectReason.BLOCKED)
                used(slot, kind, at, other, ev)
                ev += GemEvent.Swap(at, other)
                board.swapCells(at, other)
                groups = GemMatcher.groups(board)
            }
            BoosterKind.SHUFFLE -> {
                used(slot, kind, null, null, ev)
                if (!shuffleBoard(ev)) {
                    boosterUsed[slot]--
                    return MoveResult.rejected(RejectReason.BAD_TARGET)
                }
                shuffleDone = true
            }
            BoosterKind.EXTRA_MOVES -> {
                if (rules.limitedMoves) {
                    used(slot, kind, null, null, ev)
                    movesLeft += def.extraMoves
                    ev += GemEvent.MovesLeft(movesLeft)
                } else if (rules.timed) {
                    used(slot, kind, null, null, ev)
                    timeLeftMs += def.extraTimeMs
                    ev += GemEvent.TimeBonus(def.extraTimeMs, TimeReason.BOOSTER)
                } else {
                    return MoveResult.rejected(RejectReason.NOT_ALLOWED)
                }
            }
        }
        record(GemAction.BoosterAction(kind, at, other))
        serial++
        if (groups.isNotEmpty() || forced != null) cascade(ev, groups, forced, emptyList())
        if (shuffleDone && GemMatcher.hasAny(board)) cascade(ev, GemMatcher.groups(board), null, emptyList())
        endOfTurn(ev, consumedMove = false)
        return MoveResult.ok(ev)
    }

    /** Boosters left this level, by kind. */
    fun boosterLeft(kind: BoosterKind): Int {
        val slot = GemBoosters.defs.indexOfFirst { it.kind == kind }
        return GemBoosters.defs[slot].capPerLevel - boosterUsed[slot]
    }

    private fun used(slot: Int, kind: BoosterKind, at: Pos?, other: Pos?, ev: MutableList<GemEvent>) {
        boosterUsed[slot]++
        ev += GemEvent.BoosterUsed(kind, at, other, GemBoosters.defs[slot].capPerLevel - boosterUsed[slot])
    }

    fun copy(): GemEngine {
        val e = GemEngine(rules, levelId, starThresholds, board.copy(), rng.copy(), kinds)
        e.status = status; e.overReason = overReason; e.score = score; e.movesMade = movesMade; e.movesLeft = movesLeft
        e.timeLeftMs = timeLeftMs; e.elapsedMs = elapsedMs; e.speedMeter = speedMeter; e.burstUntilMs = burstUntilMs
        e.lastMoveAtMs = lastMoveAtMs; e.classicLevel = classicLevel; e.stars = stars; e.chainBest = chainBest
        e.serial = serial; e.goals = goals
        e.gemsCleared = gemsCleared; e.iceCleared = iceCleared; e.stonesBroken = stonesBroken; e.locksBroken = locksBroken
        e.treasuresCollected = treasuresCollected; e.butterfliesEscaped = butterfliesEscaped; e.bombsDefused = bombsDefused
        e.specialsMade = specialsMade; e.spidersSquashed = spidersSquashed; e.bombsSpawned = bombsSpawned; e.obstacleHits = obstacleHits
        System.arraycopy(clearedByKind, 0, e.clearedByKind, 0, clearedByKind.size)
        System.arraycopy(boosterUsed, 0, e.boosterUsed, 0, boosterUsed.size)
        e.logComplete = logComplete
        return e
    }

    /** Like [copy] but without the action log (cheap clones for search). */
    fun fork(): GemEngine = copy()

    private fun preCheck(booster: Boolean = false): RejectReason? = when {
        status != GemStatus.PLAYING -> RejectReason.GAME_OVER
        !booster && rules.limitedMoves && movesLeft <= 0 -> RejectReason.NO_MOVES_LEFT
        !booster && rules.timed && timeLeftMs <= 0 -> RejectReason.TIME_UP
        else -> null
    }

    private fun record(a: GemAction) {
        if (!logComplete) return
        if (a is GemAction.Elapse && actionLog.isNotEmpty()) {
            val last = actionLog.last()
            if (last is GemAction.Elapse) {
                actionLog[actionLog.size - 1] = GemAction.Elapse(last.ms + a.ms)
                return
            }
        }
        if (actionLog.size >= LOG_CAP) { logComplete = false; return }
        actionLog.add(a)
    }

    // ------------------------------------------------------------------------------------------
    // Move bookkeeping
    // ------------------------------------------------------------------------------------------

    private fun beginMove(ev: MutableList<GemEvent>) {
        movesMade++
        serial++
        if (rules.limitedMoves) {
            movesLeft--
            ev += GemEvent.MovesLeft(movesLeft)
        }
        if (rules.speedWindowMs > 0) {
            if (lastMoveAtMs >= 0) {
                speedMeter = if (elapsedMs - lastMoveAtMs <= rules.speedWindowMs) speedMeter + rules.speedStep
                else max(0, speedMeter - rules.speedStep)
            }
            lastMoveAtMs = elapsedMs
            if (speedMeter >= 100) {
                speedMeter = 0
                burstUntilMs = elapsedMs + rules.speedBurstMs
                timeLeftMs += rules.speedBonusMs
                ev += GemEvent.SpeedBonus(rules.speedBonusMs, rules.speedBurstMs)
            }
            ev += GemEvent.SpeedMeter(speedMeter)
        }
    }

    internal fun endOfTurn(ev: MutableList<GemEvent>, consumedMove: Boolean) {
        if (status != GemStatus.PLAYING) return
        refreshGoals(ev)
        if (checkWin(ev)) return
        if (consumedMove) {
            runHooks(ev)
            if (status != GemStatus.PLAYING) return
            refreshGoals(ev)
            if (checkWin(ev)) return
        }
        if (rules.limitedMoves && movesLeft <= 0) {
            over(OverReason.OUT_OF_MOVES, ev)
            return
        }
        if (!GemMoves.hasLegal(board, rules)) {
            if (rules.shuffleOnNoMoves && shuffleBoard(ev)) {
                if (GemMatcher.hasAny(board)) cascade(ev, GemMatcher.groups(board), null, emptyList())
                refreshGoals(ev)
                checkWin(ev)
            } else {
                over(OverReason.NO_MOVES, ev)
            }
        }
    }

    private fun over(reason: OverReason, ev: MutableList<GemEvent>) {
        if (status != GemStatus.PLAYING) return
        status = GemStatus.OVER
        overReason = reason
        ev += GemEvent.GameOver(reason, score)
    }

    private fun checkWin(ev: MutableList<GemEvent>): Boolean {
        if (goals.isEmpty() || !goals.all { it.done }) return false
        status = GemStatus.WON
        if (rules.limitedMoves && movesLeft > 0) {
            val bonus = movesLeft * 50
            score += bonus
            ev += GemEvent.ScoreDelta(bonus, score, ScoreReason.LEFTOVER_MOVES)
            refreshGoals(ev)
        }
        stars = starThresholds.count { score >= it }
        ev += GemEvent.LevelComplete(stars, score)
        return true
    }

    private fun refreshGoals(ev: MutableList<GemEvent>) {
        if (goals.isEmpty()) return
        goals = goals.map { g ->
            val cur = when (g.type) {
                GoalType.SCORE -> score
                GoalType.CLEAR_GEMS -> if (g.kind < 0) gemsCleared else clearedByKind[g.kind]
                GoalType.CLEAR_ICE -> iceCleared
                GoalType.BREAK_STONES -> stonesBroken
                GoalType.BREAK_LOCKS -> locksBroken
                GoalType.COLLECT_TREASURE -> treasuresCollected
                GoalType.ESCAPE_BUTTERFLIES -> butterfliesEscaped
                GoalType.DEFUSE_BOMBS -> bombsDefused
                GoalType.MAKE_SPECIALS -> specialsMade
                GoalType.SQUASH_SPIDERS -> spidersSquashed
            }.coerceAtMost(g.target)
            if (cur != g.current) {
                ev += GemEvent.LevelGoalProgress(g.type, g.kind, cur, g.target)
                g.copy(current = cur)
            } else g
        }
    }

    private fun addScore(points: Int, reason: ScoreReason, ev: MutableList<GemEvent>) {
        if (points <= 0) return
        score = min(score.toLong() + points, MAX_SCORE.toLong()).toInt()
        ev += GemEvent.ScoreDelta(points, score, reason)
        if (rules.classicLevels) {
            while (score >= ModeRules.classicThreshold(classicLevel) && classicLevel < MAX_CLASSIC_LEVEL) {
                classicLevel++
                ev += GemEvent.LevelUp(classicLevel)
            }
        }
    }

    private fun addTime(ms: Long, reason: TimeReason, ev: MutableList<GemEvent>) {
        if (!rules.timed || ms <= 0) return
        timeLeftMs += ms
        ev += GemEvent.TimeBonus(ms, reason)
    }

    // ------------------------------------------------------------------------------------------
    // Forced effects (combos, prism, boosters)
    // ------------------------------------------------------------------------------------------

    private class Forced(
        val affected: Set<Pos>,
        val consumed: List<Pos>,
        val seeds: List<Pos>,
        val cause: ClearCause,
        val preEvents: List<GemEvent>,
        val bonus: Int
    )

    private fun comboForced(a: Pos, ca: Cell, b: Pos, cb: Cell): Forced {
        val effect = GemSpecials.comboFor(ca.special, cb.special)!!
        val consumed = listOf(a, b)
        val pre = ArrayList<GemEvent>()
        var affected: Set<Pos>
        val seeds = ArrayList<Pos>()
        val colourOf: (Cell) -> Int = { it.kind }
        when (effect) {
            ComboEffect.CROSS -> affected = fatCross(b, 0)
            ComboEffect.FAT_CROSS_1 -> affected = fatCross(b, 1)
            ComboEffect.FAT_CROSS_2 -> affected = fatCross(b, 2)
            ComboEffect.AREA_2 -> affected = GemSpecials.area(board, b, 2)
            ComboEffect.AREA_3 -> affected = GemSpecials.area(board, b, 3)
            ComboEffect.BOARD -> affected = GemSpecials.allCells(board)
            ComboEffect.COLOUR_TO_FLARES, ComboEffect.COLOUR_TO_BURSTS, ComboEffect.COLOUR_TO_NOVAS, ComboEffect.COLOUR_CLEAR -> {
                val other = if (ca.special == SpecialKind.PRISM) cb else ca
                val colour = colourOf(other)
                affected = LinkedHashSet(GemSpecials.colourCells(board, colour)).apply { addAll(consumed) }
                // the two pieces are consumed first so they do not fire on their own
                consumed.forEach { board[it] = board[it].copy(special = SpecialKind.NONE) }
                var i = 0
                for (p in GemSpecials.colourCells(board, colour).sorted()) {
                    if (p in consumed || board[p].special != SpecialKind.NONE || board[p].locked) continue
                    val sp = when (effect) {
                        ComboEffect.COLOUR_TO_FLARES -> if ((i++ % 2) == 0) SpecialKind.FLARE_ROW else SpecialKind.FLARE_COL
                        ComboEffect.COLOUR_TO_BURSTS -> SpecialKind.BURST
                        else -> SpecialKind.NOVA
                    }
                    board[p] = board[p].copy(special = sp)
                    pre += GemEvent.SpecialCreated(p, sp, colour)
                    seeds += p
                }
                // already-special gems of that colour fire too (they are in `affected`, so the chain reaction finds them)
                pre.add(0, GemEvent.ComboActivated(ca.special, cb.special, b, effect, affected.sorted()))
                return Forced(affected, consumed, seeds, ClearCause.COMBO, pre, 200)
            }
        }
        consumed.forEach { board[it] = board[it].copy(special = SpecialKind.NONE) }
        pre += GemEvent.ComboActivated(ca.special, cb.special, b, effect, affected.sorted())
        return Forced(affected, consumed, seeds, ClearCause.COMBO, pre, 200)
    }

    private fun fatCross(p: Pos, width: Int): Set<Pos> = GemSpecials.fatCross(board, p, width)

    private fun prismForced(prismAt: Pos, other: Cell): Forced {
        val colour = other.matchKind.takeIf { it >= 0 } ?: GemSpecials.commonestColour(board)
        val affected = GemSpecials.colourCells(board, colour, prismAt)
        board[prismAt] = board[prismAt].copy(special = SpecialKind.NONE)
        val pre = listOf<GemEvent>(GemEvent.SpecialActivated(prismAt, SpecialKind.PRISM, affected.sorted()))
        return Forced(affected, listOf(prismAt), emptyList(), ClearCause.SPECIAL, pre, 100)
    }

    // ------------------------------------------------------------------------------------------
    // The cascade
    // ------------------------------------------------------------------------------------------

    private fun cascade(ev: MutableList<GemEvent>, firstGroups: List<MatchGroup>, forced: Forced?, pivots: List<Pos>) {
        var n = 1
        var groups = firstGroups
        var f = forced
        var pv = pivots
        while ((groups.isNotEmpty() || f != null) && n <= MAX_CHAIN) {
            step(ev, n, groups, f, pv)
            settle(ev)
            n++
            f = null
            pv = emptyList()
            groups = GemMatcher.groups(board)
        }
    }

    private fun multiplier(n: Int): Int = min(n, ModeRules.MAX_MULTIPLIER) * (if (elapsedMs < burstUntilMs) 2 else 1)

    private fun step(ev: MutableList<GemEvent>, n: Int, groups: List<MatchGroup>, forced: Forced?, pivots: List<Pos>) {
        val mult = multiplier(n)
        ev += GemEvent.ChainStep(n, mult)
        if (n > chainBest) chainBest = n
        if (forced != null) ev.addAll(forced.preEvents)

        var bonus = forced?.bonus ?: 0
        val cleared = LinkedHashSet<Pos>()
        val created = HashSet<Pos>()
        for (g in groups) {
            ev += GemEvent.Match(g.cells, g.shape, g.kind)
            cleared.addAll(g.cells)
            bonus += when (g.shape) {
                MatchShape.LINE_3 -> 0
                MatchShape.LINE_4 -> 20
                MatchShape.LINE_5 -> 60
                MatchShape.L, MatchShape.T, MatchShape.CROSS -> 40
                MatchShape.DOUBLE_4 -> 100
            }
            val sp = g.special
            if (sp != SpecialKind.NONE) {
                val pos = g.specialPos(board, pivots)
                if (pos != null && created.add(pos)) {
                    board[pos] = board[pos].copy(special = sp)
                    ev += GemEvent.SpecialCreated(pos, sp, g.kind)
                    specialsMade++
                    addTime(rules.timePerSpecialMs, TimeReason.SPECIAL, ev)
                }
            }
        }
        cleared.removeAll(created)
        if (forced != null) cleared.addAll(forced.affected)

        // chain reaction of specials
        val activated = HashSet<Pos>()
        val queue = ArrayDeque<Pos>()
        fun consider(p: Pos) {
            val cell = board[p]
            if (cell.type == PieceType.GEM && cell.special != SpecialKind.NONE && !cell.locked && activated.add(p)) queue.addLast(p)
        }
        cleared.toList().forEach { consider(it) }
        forced?.seeds?.forEach { consider(it) }
        var activations = 0
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val cell = board[p]
            val aff: Set<Pos> = if (cell.special == SpecialKind.PRISM) {
                GemSpecials.colourCells(board, GemSpecials.commonestColour(board), p)
            } else GemSpecials.affected(board, cell.special, p)
            ev += GemEvent.SpecialActivated(p, cell.special, aff.sorted())
            activations++
            cleared.addAll(aff)
            aff.forEach { consider(it) }
        }

        // what the clear does to gems and obstacles
        val gemCleared = ArrayList<Pos>()
        val lockBroken = ArrayList<Pos>()
        val blockerHit = java.util.TreeSet<Pos>()
        for (p in cleared.sorted()) {
            val cell = board[p]
            when (cell.type) {
                PieceType.GEM -> if (cell.locked) lockBroken += p else gemCleared += p
                PieceType.STONE, PieceType.SPIDER -> blockerHit += p
                else -> Unit
            }
        }
        for (p in gemCleared) for (d in DIRS) {
            val q = Pos(p.r + d.r, p.c + d.c)
            if (board.inside(q) && board[q].type.let { it == PieceType.STONE || it == PieceType.SPIDER }) blockerHit += q
        }
        var obstaclePts = 0
        for (p in lockBroken) {
            board[p] = board[p].copy(locked = false)
            ev += GemEvent.ObstacleDamaged(p, ObstacleKind.LOCK, 0)
            locksBroken++
            obstaclePts++
        }
        for (p in blockerHit) {
            val cell = board[p]
            val left = cell.hp - 1
            val kind = if (cell.type == PieceType.STONE) ObstacleKind.STONE else ObstacleKind.SPIDER
            if (left <= 0) {
                board[p] = Cell.EMPTY
                if (kind == ObstacleKind.STONE) stonesBroken++ else spidersSquashed++
            } else {
                board[p] = cell.copy(hp = left)
            }
            ev += GemEvent.ObstacleDamaged(p, kind, max(left, 0))
            obstaclePts++
        }
        for (p in gemCleared) {
            val i = board.idx(p)
            if (board.ice[i] > 0) {
                board.ice[i]--
                iceCleared++
                ev += GemEvent.ObstacleDamaged(p, ObstacleKind.ICE, board.ice[i])
                obstaclePts++
            }
        }
        for (p in gemCleared) {
            val cell = board[p]
            if (cell.timer > 0) {
                ev += GemEvent.BombDefused(p)
                bombsDefused++
            }
            if (cell.kind in 0 until GemKinds.COUNT) clearedByKind[cell.kind]++
            gemsCleared++
            board[p] = Cell.EMPTY
        }
        val cause = when {
            forced != null -> forced.cause
            activations > 0 -> ClearCause.SPECIAL
            else -> ClearCause.MATCH
        }
        if (gemCleared.isNotEmpty()) ev += GemEvent.Clear(gemCleared, cause, n)

        val base = gemCleared.size * ModeRules.SCORE_PER_GEM + bonus + activations * 20
        addScore(base * mult, when (cause) { ClearCause.COMBO -> ScoreReason.COMBO; ClearCause.MATCH -> ScoreReason.MATCH; else -> ScoreReason.SPECIAL }, ev)
        obstacleHits += obstaclePts
        addScore(obstaclePts * 25, ScoreReason.OBSTACLE, ev)
        if (n >= 2) addTime(rules.timePerChainMs * min(n - 1, 4), TimeReason.CHAIN, ev)
        refreshGoals(ev)
    }

    /** Gravity, collection of treasures at the bottom, and deterministic refill; repeats until stable. */
    private fun settle(ev: MutableList<GemEvent>) {
        var guard = 0
        while (guard++ < 30) {
            gravityAndSpawn(ev)
            if (!collectTreasures(ev)) break
        }
    }

    private fun gravityAndSpawn(ev: MutableList<GemEvent>) {
        val falls = ArrayList<GemEvent>()
        val spawns = ArrayList<GemEvent>()
        for (c in 0 until board.cols) {
            var r = board.rows - 1
            while (r >= 0) {
                if (board[r, c].type == PieceType.STONE) { r--; continue }
                val bottom = r
                var top = r
                while (top - 1 >= 0 && board[top - 1, c].type != PieceType.STONE) top--
                var w = bottom
                for (row in bottom downTo top) {
                    val cell = board[row, c]
                    if (cell.type == PieceType.EMPTY) continue
                    if (row != w) {
                        board[w, c] = cell
                        board[row, c] = Cell.EMPTY
                        falls += GemEvent.Fall(Pos(row, c), Pos(w, c))
                    }
                    w--
                }
                val empties = w - top + 1
                for (j in 0 until empties) {
                    val cell = Cell.gem(rng.nextInt(kinds))
                    board[top + j, c] = cell
                    spawns += GemEvent.Spawn(Pos(top + j, c), cell, top + j - empties)
                }
                r = top - 1
            }
        }
        ev.addAll(falls)
        ev.addAll(spawns)
    }

    private fun collectTreasures(ev: MutableList<GemEvent>): Boolean {
        var any = false
        val r = board.rows - 1
        for (c in 0 until board.cols) {
            if (board[r, c].type == PieceType.TREASURE) {
                ev += GemEvent.Escaped(Pos(r, c), PieceType.TREASURE)
                board[r, c] = Cell.EMPTY
                treasuresCollected++
                any = true
                if (rules.limitedMoves && rules.movesPerTreasure > 0) {
                    movesLeft += rules.movesPerTreasure
                    ev += GemEvent.MovesLeft(movesLeft)
                }
            }
        }
        return any
    }

    // ------------------------------------------------------------------------------------------
    // After-move rules (bombs, rising pieces, spawns)
    // ------------------------------------------------------------------------------------------

    internal fun runHooks(ev: MutableList<GemEvent>) {
        // 1. time bombs count down
        var exploded: Pos? = null
        for (p in board.allPositions()) {
            val cell = board[p]
            if (cell.type != PieceType.GEM || cell.timer <= 0) continue
            val t = cell.timer - 1
            if (t <= 0) {
                if (exploded == null) exploded = p
                ev += GemEvent.BombExploded(p)
            } else {
                board[p] = cell.copy(timer = t)
                ev += GemEvent.BombTick(p, t)
            }
        }
        if (exploded != null) {
            over(OverReason.BOMB, ev)
            return
        }
        // 2. butterflies (and in Butterflies mode spiders) rise one row
        var changed = false
        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            val cell = board[r, c]
            val rises = cell.type == PieceType.BUTTERFLY || (cell.type == PieceType.SPIDER && rules.spidersRise)
            if (!rises || r == 0) continue
            val above = board[r - 1, c]
            if (above.type != PieceType.GEM || above.locked) continue
            val from = Pos(r, c)
            val to = Pos(r - 1, c)
            ev += GemEvent.PieceMoved(from, to)
            board.swapCells(from, to)
            changed = true
            if (r - 1 == 0) {
                if (cell.type == PieceType.BUTTERFLY) {
                    ev += GemEvent.Escaped(to, cell.type)
                    board[to] = Cell.EMPTY
                    butterfliesEscaped++
                } else {
                    // the spider stays on the top row; the game is over
                    over(OverReason.SPIDER, ev)
                    return
                }
            }
        }
        if (changed) settle(ev)
        // 3. spawn rules
        if (rules.butterflyEvery > 0 && movesMade % rules.butterflyEvery == 0) {
            changed = spawnOnRow(ev, board.rows - 1, Cell(PieceType.BUTTERFLY)) || changed
        }
        if (rules.spiderEvery > 0 && movesMade % rules.spiderEvery == 0) {
            changed = spawnOnRow(ev, board.rows - 1, Cell(PieceType.SPIDER, hp = 1)) || changed
        }
        if (rules.treasureEvery > 0 && movesMade % rules.treasureEvery == 0) {
            changed = spawnOnRow(ev, 0, Cell(PieceType.TREASURE)) || changed
        }
        if (rules.bombEvery > 0 && movesMade % rules.bombEvery == 0) {
            val cands = board.allPositions().filter {
                val c = board[it]; c.type == PieceType.GEM && !c.locked && c.timer == 0 && c.special == SpecialKind.NONE
            }
            if (cands.isNotEmpty()) {
                val p = cands[rng.nextInt(cands.size)]
                val timer = max(rules.minBombTimer, rules.bombTimer - bombsSpawned / 3)
                bombsSpawned++
                val cell = board[p].copy(timer = timer)
                board[p] = cell
                ev += GemEvent.Spawn(p, cell, p.r)
                changed = true
            }
        }
        if (changed && GemMatcher.hasAny(board)) cascade(ev, GemMatcher.groups(board), null, emptyList())
    }

    private fun spawnOnRow(ev: MutableList<GemEvent>, row: Int, piece: Cell): Boolean {
        val cands = (0 until board.cols).map { Pos(row, it) }.filter {
            val c = board[it]; c.type == PieceType.GEM && !c.locked && c.special == SpecialKind.NONE && c.timer == 0
        }
        if (cands.isEmpty()) return false
        val p = cands[rng.nextInt(cands.size)]
        board[p] = piece
        ev += GemEvent.Spawn(p, piece, p.r)
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Shuffle
    // ------------------------------------------------------------------------------------------

    /** Reorders (or, failing that, redeals) the movable gems so there is no match and at least one legal move. */
    internal fun shuffleBoard(ev: MutableList<GemEvent>): Boolean {
        val movable = board.allPositions().filter { board[it].isMovable }
        if (movable.size >= 2) {
            for (attempt in 0 until 40) {
                val order = movable.toMutableList()
                for (i in order.size - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    val t = order[i]; order[i] = order[j]; order[j] = t
                }
                val trial = board.copy()
                for (i in movable.indices) trial[movable[i]] = board[order[i]]
                if (!GemMatcher.hasAny(trial) && GemMoves.hasLegal(trial, rules)) {
                    val moves = ArrayList<ShuffleMove>()
                    for (i in movable.indices) if (order[i] != movable[i]) moves += ShuffleMove(order[i], movable[i])
                    board = trial
                    ev += GemEvent.NoMovesShuffle(moves, false)
                    return true
                }
            }
        }
        // redeal the movable gems
        for (attempt in 0 until 60) {
            val trial = board.copy()
            for (p in movable) trial[p] = Cell.EMPTY
            GemDeal.fillEmpty(trial, rng, kinds)
            if (GemMatcher.hasAny(trial)) continue
            if (!GemMoves.hasLegal(trial, rules) && !GemDeal.forceMove(trial, rng, kinds, rules)) continue
            return adoptRedeal(trial, movable, ev)
        }
        return false
    }

    private fun adoptRedeal(trial: GemBoard, movable: List<Pos>, ev: MutableList<GemEvent>): Boolean {
        ev += GemEvent.NoMovesShuffle(emptyList(), true)
        for (p in movable) if (trial[p] != board[p]) ev += GemEvent.Spawn(p, trial[p], p.r)
        board = trial
        return true
    }

    companion object {
        const val LOG_CAP = 20_000
        const val MAX_CHAIN = 100
        const val MAX_SCORE = 2_000_000_000
        const val MAX_CLASSIC_LEVEL = 200
        private val DIRS = listOf(Pos(-1, 0), Pos(1, 0), Pos(0, -1), Pos(0, 1))

        /** A fresh game of a non-quest [mode]. [kindsOverride] limits the colours (3..7). */
        fun start(mode: GemMode, rows: Int = 8, cols: Int = rows, seed: Long, kindsOverride: Int? = null): GemEngine {
            require(rows in 6..10 && cols in 6..10) { "board size must be 6..10" }
            val rules = ModeRules.of(mode).let { if (kindsOverride != null) it.copy(kinds = kindsOverride.coerceIn(3, GemKinds.COUNT)) else it }
            val rng = GemRng(seed)
            val proto = GemBoard(rows, cols)
            if (mode == GemMode.MINE) mineLayout(proto, rng)
            GemDeal.deal(proto, rng, rules.kinds, rules)
            val e = GemEngine(rules, 0, emptyList(), proto, rng, rules.kinds)
            e.movesLeft = rules.moveLimit
            e.timeLeftMs = rules.timeLimitMs
            return e
        }

        private fun mineLayout(b: GemBoard, rng: GemRng) {
            val band = b.rows / 3
            for (r in band..band + 1) for (c in 0 until b.cols) b[r, c] = Cell(PieceType.STONE, hp = 1 + rng.nextInt(2))
            val first = rng.nextInt(b.cols)
            val second = (first + 2 + rng.nextInt(b.cols - 3)) % b.cols
            b[band - 1, first] = Cell(PieceType.TREASURE)
            b[band - 1, second] = Cell(PieceType.TREASURE)
        }

        /** A fresh quest level (or the daily level). */
        fun forLevel(level: GemLevel): GemEngine {
            val rules = ModeRules.of(GemMode.QUEST).copy(kinds = level.kinds, moveLimit = level.moves)
            val rng = GemRng(level.seed)
            val proto = GemBoard(level.rows, level.cols)
            val locks = ArrayList<Pos>()
            for (r in 0 until level.rows) for (c in 0 until level.cols) {
                when (level.pieces[r][c]) {
                    'S' -> proto[r, c] = Cell(PieceType.STONE, hp = 2)
                    's' -> proto[r, c] = Cell(PieceType.STONE, hp = 1)
                    '#' -> proto[r, c] = Cell(PieceType.STONE, hp = 3)
                    'b' -> proto[r, c] = Cell(PieceType.BUTTERFLY)
                    'T' -> proto[r, c] = Cell(PieceType.TREASURE)
                    'x' -> proto[r, c] = Cell(PieceType.SPIDER, hp = 1)
                    'L' -> locks += Pos(r, c)
                    else -> Unit
                }
                if (level.floor.isNotEmpty()) proto.ice[proto.idx(r, c)] = level.floor[r][c] - '0'
            }
            val gemCells = proto.allPositions().filter { proto[it].type == PieceType.EMPTY }
            for (attempt in 0 until 30) {
                for (p in gemCells) proto[p] = Cell.EMPTY
                GemDeal.deal(proto, rng, level.kinds, rules)
                for (p in locks) proto[p] = proto[p].copy(locked = true)
                if (GemMoves.hasLegal(proto, rules)) break
            }
            for (b in level.bombs) proto[Pos(b.r, b.c)] = proto[Pos(b.r, b.c)].copy(timer = b.timer)
            val e = GemEngine(rules, level.id, level.stars, proto, rng, level.kinds)
            e.movesLeft = level.moves
            val counts = GemLevels.countsOf(level)
            e.goals = level.goals.map { g ->
                val type = GemLevels.goalType(g.type)!!
                val target = if (g.target > 0) g.target else (counts[type] ?: 1)
                GoalState(type, if (type == GoalType.CLEAR_GEMS) g.kind else -1, target, 0)
            }
            return e
        }
    }
}
