package com.gamesuite.games.gemcascade

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame

/** An immutable picture of the game for the screen (the engine itself is never read from composition). */
data class GemView(
    val serial: Int,
    val mode: GemMode,
    val board: GemBoard,
    val score: Int,
    val movesLeft: Int,
    val timeLeftMs: Long,
    val status: GemStatus,
    val overReason: OverReason?,
    val goals: List<GoalState>,
    val classicLevel: Int,
    val speedMeter: Int,
    val boosters: Map<BoosterKind, Int>,
    val levelId: Int,
    val stars: Int,
    val chainBest: Int
) {
    val rows: Int get() = board.rows
    val cols: Int get() = board.cols
}

/** One finished operation: the events to animate and the board as it was before them. */
data class GemBatch(val serial: Int, val events: List<GemEvent>, val before: GemBoard, val accepted: Boolean)

/**
 * Gem Cascade as a [GameModule]: one person, one engine. The screen calls [swap], [rotate], [useBooster] and
 * [tick]; it reads [view] and [batch] (immutable) and animates the batch's events. Resumable.
 *
 * Launch options (see [GemCascadeHubSpecs]): `type` (a [GemMode] id), `level` (Quest, 1..), `size` (6..10),
 * `daily` ("true" for today's challenge). [holdInputUntilSettled] makes the game answer null (refuse) to a second
 * action until the screen calls [settled], so a double tap can never fire two moves.
 */
class GemCascadeGame(
    private val seedSource: () -> Long = { System.nanoTime() xor 0x5DEECE66DL }
) : GameModule, ResumableGame {
    override val gameId = "gem-cascade"
    override val displayName = "Gem Cascade"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val view = mutableStateOf<GemView?>(null)
    val batch = mutableStateOf<GemBatch?>(null)
    val paused = mutableStateOf(false)
    val matchOver = mutableStateOf(false)

    // options for the NEXT game
    var mode: GemMode = GemMode.CLASSIC
    var size: Int = 8
    var questLevel: Int = 1
    var levels: List<GemLevel> = emptyList()
    var dailyEpochDay: Long? = null
    var holdInputUntilSettled: Boolean = false

    private var engine: GemEngine? = null
    private var seed: Long = 0
    private var context: GameContext? = null
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var inFlight = false
    private var serial = 0

    /** Reads the launch extras. Unknown or out-of-range values fall back to the defaults. */
    fun configure(extras: Map<String, String>, availableLevels: List<GemLevel>) {
        levels = availableLevels
        mode = GemMode.fromId(extras["type"]) ?: GemMode.CLASSIC
        size = (extras["size"]?.toIntOrNull() ?: 8).coerceIn(6, 10)
        questLevel = (extras["level"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        dailyEpochDay = if (extras["daily"] == "true") UtcDate.epochDay(System.currentTimeMillis()) else null
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
    }

    override fun startMatch() {
        matchOver.value = false
        inFlight = false
        val day = dailyEpochDay
        val e: GemEngine
        if (day != null) {
            mode = GemMode.QUEST
            seed = UtcDate.dailySeed(day)
            e = GemEngine.forLevel(GemLevels.daily(day))
        } else if (mode == GemMode.QUEST) {
            val lv = levels.firstOrNull { it.id == questLevel } ?: levels.firstOrNull() ?: GemLevels.daily(0)
            seed = lv.seed
            e = GemEngine.forLevel(lv)
        } else {
            seed = seedSource()
            e = GemEngine.start(mode, size, size, seed)
        }
        engine = e
        publish(listOf(GemEvent.Deal(e.rows, e.cols)), e.board.copy(), true)
    }

    /** Quest: the level after the one just played (or the same level again when it was the last). */
    fun nextLevel() {
        val e = engine ?: return
        if (mode != GemMode.QUEST || dailyEpochDay != null) return
        val i = levels.indexOfFirst { it.id == e.levelId }
        questLevel = levels.getOrNull(i + 1)?.id ?: e.levelId
        startMatch()
    }

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        startMatch()
    }

    override fun pause() {
        paused.value = true
    }

    override fun resume() {
        paused.value = false
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** The player leaves (or the game is over and they are done): records the score. */
    fun finish() {
        val e = engine ?: return endMatch(GameResult(wasAborted = true))
        val id = context?.players?.firstOrNull()?.playerId ?: "p1"
        endMatch(GameResult(scores = listOf(PlayerScore(id, e.score, isWinner = e.status == GemStatus.WON))))
    }

    /** The screen finished animating the last accepted action. */
    fun settled() {
        inFlight = false
    }

    val isBusy: Boolean get() = inFlight

    // ---- player actions ---------------------------------------------------------------------

    private fun guard(): GemEngine? {
        val e = engine ?: return null
        if (matchOver.value || paused.value || e.status != GemStatus.PLAYING) return null
        if (holdInputUntilSettled && inFlight) return null
        return e
    }

    /** Null when the action was not even considered (paused, busy, over). */
    fun swap(a: Pos, b: Pos): MoveResult? = act(GemAction.SwapAction(a, b))

    fun rotate(topLeft: Pos, clockwise: Boolean): MoveResult? = act(GemAction.RotateAction(topLeft, clockwise))

    fun useBooster(kind: BoosterKind, at: Pos?, other: Pos? = null): MoveResult? = act(GemAction.BoosterAction(kind, at, other))

    private fun act(action: GemAction): MoveResult? {
        val e = guard() ?: return null
        val before = e.board.copy()
        val r = e.apply(action)
        if (r.events.isNotEmpty() || r.accepted) {
            if (r.accepted) inFlight = true
            publish(r.events, before, r.accepted)
        }
        return r
    }

    private var tickAccumMs = 0L

    /**
     * Lightning's clock; [deltaMs] of un-paused time. Small steps are gathered and applied every 200 ms, and the
     * view is only republished when the shown second changes or something happened, so a 60 fps caller does not
     * make the screen recompose 60 times a second.
     */
    fun tick(deltaMs: Long) {
        val e = engine ?: return
        if (paused.value || matchOver.value || e.status != GemStatus.PLAYING || !e.rules.timed || deltaMs <= 0) return
        tickAccumMs += deltaMs.coerceAtMost(1000)
        if (tickAccumMs < TICK_STEP_MS) return
        val step = tickAccumMs
        tickAccumMs = 0
        val before = e.board.copy()
        val secBefore = e.timeLeftMs / 1000
        val r = e.elapse(step)
        if (r.events.isNotEmpty()) publish(r.events, before, true)
        else if (e.timeLeftMs / 1000 != secBefore) refreshView()
    }

    /** The hint button: the best move right now, or null. */
    fun hint(): GemAction? = engine?.let { if (it.status == GemStatus.PLAYING) GemSolver.hint(it) else null }

    private fun publish(events: List<GemEvent>, before: GemBoard, accepted: Boolean) {
        serial++
        refreshView()
        batch.value = GemBatch(serial, events, before, accepted)
    }

    private fun refreshView() {
        val e = engine ?: return
        view.value = GemView(
            serial = serial, mode = mode, board = e.board.copy(), score = e.score, movesLeft = e.movesLeft,
            timeLeftMs = e.timeLeftMs, status = e.status, overReason = e.overReason, goals = e.goals,
            classicLevel = e.classicLevel, speedMeter = e.speedMeter,
            boosters = BoosterKind.values().associateWith { e.boosterLeft(it) },
            levelId = e.levelId, stars = e.stars, chainBest = e.chainBest
        )
    }

    // ---- resume -----------------------------------------------------------------------------

    override fun snapshot(): String? = try {
        val e = engine
        if (e == null || matchOver.value) null else GemSnapshot.encode(e, mode, seed)
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean {
        val (m, e) = GemSnapshot.decode(snapshot) ?: return false
        mode = m
        size = e.rows
        if (m == GemMode.QUEST) questLevel = e.levelId
        engine = e
        matchOver.value = false
        inFlight = false
        publish(listOf(GemEvent.Deal(e.rows, e.cols)), e.board.copy(), true)
        return true
    }
}

private const val TICK_STEP_MS = 200L
