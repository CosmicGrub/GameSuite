package com.gamesuite.games.gemcascade

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a game started from: enough, with the move list, to reproduce it exactly. */
data class GemSetup(
    val mode: GemMode,
    val rows: Int = 8,
    val cols: Int = rows,
    val seed: Long = 1L,
    val kinds: Int? = null,
    val level: GemLevel? = null
) {
    fun newEngine(): GemEngine =
        if (mode == GemMode.QUEST && level != null) GemEngine.forLevel(level)
        else GemEngine.start(mode, rows, cols, seed, kinds)
}

object GemReplay {
    /** Runs [actions] from a fresh game. Null when any action is refused (a replay of an accepted log never is). */
    fun run(setup: GemSetup, actions: List<GemAction>): GemEngine? {
        val e = setup.newEngine()
        for (a in actions) if (!e.apply(a).accepted) return null
        return e
    }

    fun encode(actions: List<GemAction>): String = actions.joinToString(";") {
        when (it) {
            is GemAction.SwapAction -> "S${it.a.r},${it.a.c},${it.b.r},${it.b.c}"
            is GemAction.RotateAction -> "R${it.topLeft.r},${it.topLeft.c},${if (it.clockwise) 1 else 0}"
            is GemAction.BoosterAction -> "B${it.booster.ordinal},${it.at?.r ?: -1},${it.at?.c ?: -1},${it.other?.r ?: -1},${it.other?.c ?: -1}"
            is GemAction.Elapse -> "E${it.ms}"
        }
    }

    /** Inverse of [encode]; null when malformed. */
    fun decode(text: String): List<GemAction>? {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<GemAction>()
        for (tok in text.split(';')) {
            if (tok.length < 2) return null
            val body = tok.substring(1)
            if (tok[0] == 'E') {
                val ms = body.toLongOrNull() ?: return null
                if (ms <= 0) return null
                out += GemAction.Elapse(ms)
                continue
            }
            val n = body.split(',').map { it.toIntOrNull() ?: return null }
            when (tok[0]) {
                'S' -> { if (n.size != 4) return null; out += GemAction.SwapAction(Pos(n[0], n[1]), Pos(n[2], n[3])) }
                'R' -> { if (n.size != 3 || n[2] !in 0..1) return null; out += GemAction.RotateAction(Pos(n[0], n[1]), n[2] == 1) }
                'B' -> {
                    if (n.size != 5) return null
                    val kind = BoosterKind.values().getOrNull(n[0]) ?: return null
                    val at = if (n[1] < 0) null else Pos(n[1], n[2])
                    val other = if (n[3] < 0) null else Pos(n[3], n[4])
                    out += GemAction.BoosterAction(kind, at, other)
                }
                else -> return null
            }
        }
        return out
    }
}

/** Saves and restores a whole game with full validation. A snapshot that fails any check is refused and nothing is built. */
object GemSnapshot {
    const val VERSION = 1
    const val MAX_SERIAL = 1_000_000
    private const val MAX_COUNT = 10_000_000
    private const val MAX_TIME = 3_600_000L
    private const val MAX_ELAPSED = 100_000_000_000L
    private const val MAX_MOVES = 500
    private const val MAX_MOVES_LEFT = 5000

    @Serializable
    data class Data(
        val version: Int = VERSION,
        val mode: String,
        val rows: Int,
        val cols: Int,
        val kinds: Int,
        val seed: Long = 0,
        val levelId: Int = 0,
        val moveLimit: Int = -1,
        val board: String,
        val rng: Long,
        val score: Int,
        val movesMade: Int,
        val movesLeft: Int,
        val timeLeftMs: Long = 0,
        val elapsedMs: Long = 0,
        val speedMeter: Int = 0,
        val burstUntilMs: Long = -1,
        val lastMoveAtMs: Long = -1,
        val classicLevel: Int = 1,
        val chainBest: Int = 0,
        val serial: Int = 0,
        val stars: List<Int> = emptyList(),
        val goals: List<List<Int>> = emptyList(),
        val counters: List<Int>,
        val clearedByKind: List<Int>,
        val boosters: List<Int>,
        val actions: String = "",
        val logComplete: Boolean = true
    )

    private val json = Json { ignoreUnknownKeys = true }
    private const val COUNTERS = 11

    fun counters(e: GemEngine): List<Int> = listOf(
        e.gemsCleared, e.iceCleared, e.stonesBroken, e.locksBroken, e.treasuresCollected, e.butterfliesEscaped,
        e.bombsDefused, e.specialsMade, e.spidersSquashed, e.bombsSpawned, e.obstacleHits
    )

    /** The snapshot of a game in progress, or null when it is over. */
    fun encode(e: GemEngine, mode: GemMode, seed: Long): String? {
        if (e.status != GemStatus.PLAYING) return null
        val d = Data(
            mode = mode.id, rows = e.rows, cols = e.cols, kinds = e.kinds, seed = seed, levelId = e.levelId,
            moveLimit = e.rules.moveLimit, board = e.board.encode(), rng = e.rng.state, score = e.score,
            movesMade = e.movesMade, movesLeft = e.movesLeft, timeLeftMs = e.timeLeftMs, elapsedMs = e.elapsedMs,
            speedMeter = e.speedMeter, burstUntilMs = e.burstUntilMs, lastMoveAtMs = e.lastMoveAtMs,
            classicLevel = e.classicLevel, chainBest = e.chainBest, serial = e.serial, stars = e.starThresholds,
            goals = e.goals.map { listOf(it.type.ordinal, it.kind, it.target, it.current) },
            counters = counters(e), clearedByKind = e.clearedByKind.toList(), boosters = e.boosterUsed.toList(),
            actions = if (e.logComplete) GemReplay.encode(e.actionLog) else "", logComplete = e.logComplete
        )
        return json.encodeToString(Data.serializer(), d)
    }

    /** The engine and its mode, or null when the text is not a valid, consistent saved game. Never throws. */
    fun decode(text: String): Pair<GemMode, GemEngine>? = try {
        decodeOrNull(text)
    } catch (_: Exception) {
        null
    }

    private fun decodeOrNull(text: String): Pair<GemMode, GemEngine>? {
        val d = json.decodeFromString(Data.serializer(), text)
        if (d.version != VERSION) return null
        val mode = GemMode.fromId(d.mode) ?: return null
        if (d.rows !in 6..10 || d.cols !in 6..10 || d.kinds !in 3..GemKinds.COUNT) return null
        val quest = mode == GemMode.QUEST
        val baseRules = ModeRules.of(mode)
        val rules = if (quest) baseRules.copy(kinds = d.kinds, moveLimit = d.moveLimit) else baseRules.copy(kinds = d.kinds)
        if (!quest && (d.kinds > baseRules.kinds || d.moveLimit != baseRules.moveLimit)) return null
        if (quest && (d.moveLimit !in 3..MAX_MOVES || d.levelId !in 1..100_000)) return null
        if (!quest && d.levelId != 0) return null
        if (quest && (d.stars.size != 3 || d.stars.any { it <= 0 } || d.stars.zipWithNext().any { (a, b) -> a >= b })) return null
        if (!quest && d.stars.isNotEmpty()) return null
        val board = GemBoard.decode(d.board, d.rows, d.cols) ?: return null
        if (!boardValid(board, d.kinds, rules)) return null
        if (d.score !in 0..GemEngine.MAX_SCORE) return null
        if (d.serial !in 0..MAX_SERIAL || d.movesMade !in 0..d.serial) return null
        if (d.chainBest !in 0..GemEngine.MAX_CHAIN) return null
        if (rules.limitedMoves) {
            if (d.movesLeft !in 1..MAX_MOVES_LEFT) return null
        } else if (d.movesLeft != -1) return null
        if (rules.timed) {
            if (d.timeLeftMs !in 1..MAX_TIME) return null
        } else if (d.timeLeftMs != 0L) return null
        if (d.elapsedMs !in 0..MAX_ELAPSED) return null
        if (rules.speedWindowMs > 0) {
            if (d.speedMeter !in 0..99) return null
            if (d.burstUntilMs < -1 || d.burstUntilMs > d.elapsedMs + rules.speedBurstMs) return null
            if (d.lastMoveAtMs < -1 || d.lastMoveAtMs > d.elapsedMs) return null
        } else if (d.speedMeter != 0 || d.burstUntilMs != -1L || d.lastMoveAtMs != -1L) return null
        if (rules.classicLevels) {
            if (d.classicLevel !in 1..GemEngine.MAX_CLASSIC_LEVEL) return null
            if (d.classicLevel != expectedClassicLevel(d.score)) return null
        } else if (d.classicLevel != 1) return null
        if (d.counters.size != COUNTERS || d.counters.any { it !in 0..MAX_COUNT }) return null
        if (d.clearedByKind.size != GemKinds.COUNT || d.clearedByKind.any { it !in 0..MAX_COUNT }) return null
        if (d.clearedByKind.sum() != d.counters[0]) return null
        if (d.boosters.size != GemBoosters.defs.size) return null
        for (i in d.boosters.indices) if (d.boosters[i] !in 0..GemBoosters.defs[i].capPerLevel) return null
        val log = if (d.logComplete) GemReplay.decode(d.actions) ?: return null else emptyList()
        if (!d.logComplete && d.actions.isNotEmpty()) return null
        if (log.size > GemEngine.LOG_CAP) return null

        val goals = ArrayList<GoalState>()
        for (g in d.goals) {
            if (g.size != 4) return null
            val type = GoalType.values().getOrNull(g[0]) ?: return null
            if (g[1] !in -1..6 || (type != GoalType.CLEAR_GEMS && g[1] != -1)) return null
            if (g[2] !in 1..MAX_COUNT || g[3] !in 0..g[2]) return null
            goals += GoalState(type, g[1], g[2], g[3])
        }
        if (quest != goals.isNotEmpty()) return null
        if (goals.isNotEmpty() && goals.all { it.done }) return null

        val e = GemEngine(rules, d.levelId, d.stars, board, GemRng(d.rng), d.kinds)
        e.score = d.score; e.movesMade = d.movesMade; e.movesLeft = d.movesLeft; e.timeLeftMs = d.timeLeftMs
        e.elapsedMs = d.elapsedMs; e.speedMeter = d.speedMeter; e.burstUntilMs = d.burstUntilMs; e.lastMoveAtMs = d.lastMoveAtMs
        e.classicLevel = d.classicLevel; e.chainBest = d.chainBest; e.serial = d.serial; e.goals = goals
        val c = d.counters
        e.gemsCleared = c[0]; e.iceCleared = c[1]; e.stonesBroken = c[2]; e.locksBroken = c[3]; e.treasuresCollected = c[4]
        e.butterfliesEscaped = c[5]; e.bombsDefused = c[6]; e.specialsMade = c[7]; e.spidersSquashed = c[8]
        e.bombsSpawned = c[9]; e.obstacleHits = c[10]
        for (k in 0 until GemKinds.COUNT) e.clearedByKind[k] = d.clearedByKind[k]
        for (i in d.boosters.indices) e.boosterUsed[i] = d.boosters[i]
        e.actionLog.addAll(log)
        e.logComplete = d.logComplete
        // the goal counters must be what the engine's own counters give
        for (g in goals) {
            val expect = when (g.type) {
                GoalType.SCORE -> e.score
                GoalType.CLEAR_GEMS -> if (g.kind < 0) e.gemsCleared else e.clearedByKind[g.kind]
                GoalType.CLEAR_ICE -> e.iceCleared
                GoalType.BREAK_STONES -> e.stonesBroken
                GoalType.BREAK_LOCKS -> e.locksBroken
                GoalType.COLLECT_TREASURE -> e.treasuresCollected
                GoalType.ESCAPE_BUTTERFLIES -> e.butterfliesEscaped
                GoalType.DEFUSE_BOMBS -> e.bombsDefused
                GoalType.MAKE_SPECIALS -> e.specialsMade
                GoalType.SQUASH_SPIDERS -> e.spidersSquashed
            }.coerceAtMost(g.target)
            if (expect != g.current) return null
        }
        if (!GemMoves.hasLegal(board, rules)) return null
        return mode to e
    }

    private fun expectedClassicLevel(score: Int): Int {
        var l = 1
        while (score >= ModeRules.classicThreshold(l) && l < GemEngine.MAX_CLASSIC_LEVEL) l++
        return l
    }

    /** Board-level consistency: settled, no matches, every piece legal for its place. */
    fun boardValid(b: GemBoard, kinds: Int, rules: ModeRules): Boolean {
        for (r in 0 until b.rows) for (c in 0 until b.cols) {
            val cell = b[r, c]
            val ice = b.ice[b.idx(r, c)]
            if (ice !in 0..3) return false
            when (cell.type) {
                PieceType.EMPTY -> return false
                PieceType.GEM -> {
                    if (cell.kind !in 0 until kinds) return false
                    if (cell.hp != 0) return false
                    if (cell.timer !in 0..60) return false
                }
                PieceType.STONE -> {
                    if (cell.hp !in 1..3 || cell.kind != 0 || cell.special != SpecialKind.NONE || cell.locked || cell.timer != 0) return false
                    if (ice != 0) return false
                }
                PieceType.SPIDER -> {
                    if (cell.hp != 1 || cell.kind != 0 || cell.special != SpecialKind.NONE || cell.locked || cell.timer != 0) return false
                    if (r == 0 || ice != 0) return false
                }
                PieceType.BUTTERFLY -> {
                    if (cell.hp != 0 || cell.kind != 0 || cell.special != SpecialKind.NONE || cell.locked || cell.timer != 0) return false
                    if (r == 0 || ice != 0) return false
                }
                PieceType.TREASURE -> {
                    if (cell.hp != 0 || cell.kind != 0 || cell.special != SpecialKind.NONE || cell.locked || cell.timer != 0) return false
                    if (r == b.rows - 1 || ice != 0) return false
                }
            }
        }
        return !GemMatcher.hasAny(b)
    }
}
