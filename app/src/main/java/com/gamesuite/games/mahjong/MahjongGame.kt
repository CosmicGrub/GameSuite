package com.gamesuite.games.mahjong

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a Mahjong Solitaire session back exactly as it was. One shape serves
 * Resume (disk) and Undo (memory). The clock is stored as ACTIVE elapsed milliseconds, never as an
 * absolute reading, so a resumed game does not count the time away.
 */
@Serializable
private data class MahjongSnapshot(
    val playerIds: List<String>,
    val layout: String,
    /** Tile type per layout slot; -1 = removed. */
    val types: List<Int>,
    val selected: Int?,
    val moves: Int,
    val hintsUsed: Int,
    val undoCount: Int,
    val shuffleCount: Int,
    val gamesSolved: Int,
    val timerStarted: Boolean,
    val elapsedMillis: Long,
    val finishedMillis: Long?
)

private val mahjongSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * The board: [types] holds the tile type on each slot of [layout] (-1 once removed). Pairs removed
 * so far are [moves]; the board is [won] when no tile is left.
 */
data class MahjongState(
    val layout: MahjongLayoutId,
    val types: List<Int>,
    val selected: Int? = null,
    val moves: Int = 0,
    val won: Boolean = false
) {
    val remaining: Int get() = types.count { it >= 0 }
    val isOver: Boolean get() = won
}

/** What a tap on a tile did. */
enum class MahjongTap { IGNORED, BLOCKED, SELECTED, DESELECTED, MISMATCH, MATCHED }

/**
 * Mahjong Solitaire (tile matching): remove free matching pairs from a layered layout.
 *
 *  - FREE tile: nothing on top and an open left OR right side ([MahjongGeometry]).
 *  - MATCH: same suit and number, wind, dragon; any flower with any flower; any season with any season.
 *  - DEAL: always solvable ([MahjongDealer]); deterministic for a seed / injected [Random].
 *  - STUCK: with no free pair, Shuffle re-deals the remaining tiles (free, but marks the game "shuffled").
 *  - HINT shows a free pair and marks the game assisted. UNDO is unlimited and marks nothing.
 *  - RECORD: a best time per layout only counts for solves that used no hint and no shuffle.
 */
class MahjongGame(
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val randomProvider: () -> Random = { Random.Default }
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "mahjong-solitaire"
    override val displayName = "Mahjong Solitaire"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<MahjongState?>(null)

    /** Boards cleared this session. */
    val gamesSolved = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    /** The layout the next [startMatch] deals. The Hub's `layout` choice sets it. */
    var layout: MahjongLayoutId = MahjongLayoutId.TURTLE

    val hintsUsed = mutableStateOf(0)
    val undoCount = mutableStateOf(0)
    val shuffleCount = mutableStateOf(0)

    /** The pair the last hint pointed at; cleared by the next tile action. */
    val hintPair = mutableStateOf<Pair<Int, Int>?>(null)

    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private val history = SnapshotHistory()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var pausedAtElapsedRealtime: Long? = null
    private var totalPausedMillis: Long = 0L

    /** True when this board was never helped: no hint and no shuffle. Only such solves set records. */
    val isUnassisted: Boolean get() = hintsUsed.value == 0 && shuffleCount.value == 0

    val geometry: MahjongGeometry get() = MahjongLayouts.geometry(state.value?.layout ?: layout)

    override fun init(context: GameContext) {
        this.context = context
        gamesSolved.value = 0
        matchOver.value = false
        resetBoardCounters()
        history.clear()
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    private fun resetBoardCounters() {
        hintsUsed.value = 0
        undoCount.value = 0
        shuffleCount.value = 0
        hintPair.value = null
        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
    }

    // --- Dealing ----------------------------------------------------------------------------

    override fun startMatch() = startMatchWith(layout, null)

    /** Deals a fresh solvable board of [layoutId]; a non-null [seed] makes the deal reproducible. */
    fun startMatchWith(layoutId: MahjongLayoutId, seed: Long?) {
        val random = seed?.let { Random(it) } ?: randomProvider()
        layout = layoutId
        val geo = MahjongLayouts.geometry(layoutId)
        val deal = MahjongDealer.newDeal(geo, random)
        history.clear()
        resetBoardCounters()
        matchOver.value = false
        state.value = MahjongState(layout = layoutId, types = deal.types.toList())
    }

    /** The slot pairs that clear a fresh deal, for tests: the proof the deal is winnable. */
    internal fun dealSolution(layoutId: MahjongLayoutId, seed: Long): Pair<IntArray, List<Pair<Int, Int>>> {
        val deal = MahjongDealer.newDeal(MahjongLayouts.geometry(layoutId), Random(seed))
        return deal.types to deal.removalOrder
    }

    /** Test hook: puts an arbitrary board on the table (no validation, fresh counters and clock). */
    internal fun setBoardForTest(layoutId: MahjongLayoutId, types: List<Int>, moves: Int = 0) {
        layout = layoutId
        history.clear()
        resetBoardCounters()
        matchOver.value = false
        if (moves > 0) timerStartElapsedRealtime.value = nowMillis()
        state.value = MahjongState(layout = layoutId, types = types, moves = moves)
    }

    // --- Rules ------------------------------------------------------------------------------

    private fun aliveMask(s: MahjongState) = BooleanArray(s.types.size) { s.types[it] >= 0 }

    /** Whether slot [index] is a free tile right now. */
    fun isFree(index: Int): Boolean {
        val s = state.value ?: return false
        if (index !in s.types.indices) return false
        return geometry.isFree(index, aliveMask(s))
    }

    /** Every free slot of the board as a mask, for rendering. */
    fun freeMask(s: MahjongState): BooleanArray {
        val alive = aliveMask(s)
        val geo = MahjongLayouts.geometry(s.layout)
        return BooleanArray(s.types.size) { geo.isFree(it, alive) }
    }

    /** Every free matching pair, best first. */
    fun availablePairs(): List<Pair<Int, Int>> {
        val s = state.value ?: return emptyList()
        return MahjongSolver.availablePairs(MahjongLayouts.geometry(s.layout), s.types.toIntArray())
    }

    val hasMoves: Boolean get() = availablePairs().isNotEmpty()

    /** True when tiles remain but no free pair does. */
    val isStuck: Boolean
        get() {
            val s = state.value ?: return false
            return !s.won && s.remaining > 0 && !hasMoves
        }

    /** Tap a tile: select it, deselect it, or remove it together with the selected matching tile. */
    fun tapTile(index: Int): MahjongTap {
        val s = state.value ?: return MahjongTap.IGNORED
        if (matchOver.value || s.won) return MahjongTap.IGNORED
        if (index !in s.types.indices || s.types[index] < 0) return MahjongTap.IGNORED
        val alive = aliveMask(s)
        val geo = MahjongLayouts.geometry(s.layout)
        if (!geo.isFree(index, alive)) return MahjongTap.BLOCKED

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()
        hintPair.value = null

        val sel = s.selected
        if (sel == null || sel !in s.types.indices || !geo.isFree(sel, alive)) {
            state.value = s.copy(selected = index)
            return MahjongTap.SELECTED
        }
        if (sel == index) {
            state.value = s.copy(selected = null)
            return MahjongTap.DESELECTED
        }
        if (MahjongTiles.matches(s.types[sel], s.types[index])) {
            removePair(s, sel, index)
            return MahjongTap.MATCHED
        }
        state.value = s.copy(selected = index)
        return MahjongTap.MISMATCH
    }

    /** Removes slots [a] and [b] if both are free and match; used by tap and by tests. */
    fun tryMatch(a: Int, b: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.won || a == b) return false
        if (a !in s.types.indices || b !in s.types.indices) return false
        if (!MahjongTiles.matches(s.types[a], s.types[b])) return false
        val alive = aliveMask(s)
        val geo = MahjongLayouts.geometry(s.layout)
        if (!geo.isFree(a, alive) || !geo.isFree(b, alive)) return false
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()
        hintPair.value = null
        removePair(s, a, b)
        return true
    }

    private fun removePair(s: MahjongState, a: Int, b: Int) {
        pushUndo()
        val types = s.types.toMutableList()
        types[a] = -1
        types[b] = -1
        val won = types.none { it >= 0 }
        state.value = s.copy(types = types, selected = null, moves = s.moves + 1, won = won)
        if (won) {
            gamesSolved.value += 1
            freezeTimer()
        }
    }

    // --- Shuffle ----------------------------------------------------------------------------

    /** Shuffling is offered only when the board is stuck. */
    val canShuffle: Boolean get() = !matchOver.value && isStuck

    /**
     * Re-deals the remaining tiles (the same multiset) when no move is left. Free, but marks the
     * game "shuffled" so it can no longer set a record. False when nothing could be re-dealt.
     */
    fun shuffle(): Boolean {
        val s = state.value ?: return false
        if (!canShuffle) return false
        val geo = MahjongLayouts.geometry(s.layout)
        val fresh = MahjongDealer.reshuffle(geo, s.types.toIntArray(), randomProvider()) ?: return false
        pushUndo()
        shuffleCount.value += 1
        hintPair.value = null
        state.value = s.copy(types = fresh.toList(), selected = null)
        return true
    }

    // --- Hint -------------------------------------------------------------------------------

    /** Points at a free matching pair (one that keeps the board winnable when the search can tell). Marks the game assisted. */
    fun hint(): Pair<Int, Int>? {
        val s = state.value ?: return null
        if (matchOver.value || s.won) return null
        val pairs = availablePairs()
        if (pairs.isEmpty()) return null
        val geo = MahjongLayouts.geometry(s.layout)
        val solved = MahjongSolver.solve(geo, s.types.toIntArray(), HINT_SEARCH_NODES)
        val pick = solved.line.firstOrNull() ?: pairs.first()
        hintsUsed.value += 1
        hintPair.value = pick
        state.value = s.copy(selected = null)
        return pick
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && state.value?.won != true

    override fun undo() {
        val s = state.value ?: return
        if (matchOver.value || s.won) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        if (MahjongLayoutId.fromKey(snap.layout) != s.layout) return
        undoCount.value += 1
        hintPair.value = null
        state.value = s.copy(types = snap.types, selected = null, moves = snap.moves, won = false)
    }

    private fun pushUndo() {
        captureSnapshot()?.let { history.push(it) }
    }

    // --- Clock ------------------------------------------------------------------------------

    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.won != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    override fun resume() {
        pausedAtElapsedRealtime?.let {
            totalPausedMillis += nowMillis() - it
            pausedAtElapsedRealtime = null
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = ((nowMillis() - start) - totalPausedMillis).coerceAtLeast(0L)
    }

    /** Active play time in milliseconds (frozen while paused and after the solve); null before the first tap. */
    fun activeElapsedMillis(): Long? {
        val start = timerStartElapsedRealtime.value ?: return null
        finishedElapsedMillis.value?.let { return it }
        val upTo = pausedAtElapsedRealtime ?: nowMillis()
        return (upTo - start - totalPausedMillis).coerceAtLeast(0L)
    }

    // --- Session ----------------------------------------------------------------------------

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Starts another board of the current layout (the session continues). */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        startMatchWith(layout, null)
    }

    /** Ends the session, scoring the boards cleared so far. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = gamesSolved.value, isWinner = gamesSolved.value > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        val roster = if (::context.isInitialized) context.players.map { it.playerId } else emptyList()
        return mahjongSnapshotJson.encodeToString(
            MahjongSnapshot.serializer(),
            MahjongSnapshot(
                playerIds = roster,
                layout = s.layout.key,
                types = s.types,
                selected = s.selected,
                moves = s.moves,
                hintsUsed = hintsUsed.value,
                undoCount = undoCount.value,
                shuffleCount = shuffleCount.value,
                gamesSolved = gamesSolved.value,
                timerStarted = timerStartElapsedRealtime.value != null,
                elapsedMillis = activeElapsedMillis() ?: 0L,
                finishedMillis = finishedElapsedMillis.value
            )
        )
    }

    /** Strict, all-or-nothing validation of a snapshot; null for anything that is not a coherent board. */
    private fun decodeSnapshot(text: String): MahjongSnapshot? {
        val snap = try {
            mahjongSnapshotJson.decodeFromString(MahjongSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (!::context.isInitialized) return null
        if (snap.playerIds != context.players.map { it.playerId }) return null
        val id = MahjongLayoutId.values().firstOrNull { it.key == snap.layout } ?: return null
        val geo = MahjongLayouts.geometry(id)
        if (snap.types.size != geo.size) return null
        val counts = IntArray(MahjongTiles.TYPE_COUNT)
        val groupCounts = IntArray(MahjongTiles.GROUP_COUNT)
        var remaining = 0
        for (t in snap.types) {
            if (t < -1 || t >= MahjongTiles.TYPE_COUNT) return null
            if (t >= 0) {
                counts[t]++
                groupCounts[MahjongTiles.group(t)]++
                remaining++
            }
        }
        if (!MahjongTiles.withinSet(counts)) return null
        if (groupCounts.any { it % 2 != 0 }) return null
        if (remaining % 2 != 0) return null
        if (snap.moves < 0 || remaining + 2 * snap.moves != geo.size) return null
        if (snap.hintsUsed < 0 || snap.undoCount < 0 || snap.shuffleCount < 0 || snap.gamesSolved < 0) return null
        if (snap.elapsedMillis < 0L) return null
        if (snap.finishedMillis != null && snap.finishedMillis < 0L) return null
        if (snap.selected != null && (snap.selected !in snap.types.indices || snap.types[snap.selected] < 0)) return null
        // A removed tile can never have a tile still lying on it.
        for (i in snap.types.indices) {
            if (snap.types[i] >= 0) continue
            for (j in geo.coveredBy(i)) if (snap.types[j] >= 0) return null
        }
        if (snap.moves > 0 && !snap.timerStarted) return null
        if (remaining == 0 && !snap.timerStarted) return null
        return snap
    }

    override fun snapshot(): String? {
        return try {
            val s = state.value ?: return null
            if (matchOver.value || s.won || !::context.isInitialized) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = decodeSnapshot(snapshot) ?: return false
            val id = MahjongLayoutId.fromKey(snap.layout)
            val won = snap.types.none { it >= 0 }
            // Everything validated: assign.
            matchOver.value = false
            layout = id
            gamesSolved.value = snap.gamesSolved
            hintsUsed.value = snap.hintsUsed
            undoCount.value = snap.undoCount
            shuffleCount.value = snap.shuffleCount
            hintPair.value = null
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            timerStartElapsedRealtime.value = if (snap.timerStarted) nowMillis() - snap.elapsedMillis else null
            finishedElapsedMillis.value = if (won && snap.timerStarted) (snap.finishedMillis ?: snap.elapsedMillis) else null
            state.value = MahjongState(
                layout = id,
                types = snap.types,
                selected = snap.selected,
                moves = snap.moves,
                won = won
            )
            history.clear()
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val HINT_SEARCH_NODES = 3_000
    }
}
