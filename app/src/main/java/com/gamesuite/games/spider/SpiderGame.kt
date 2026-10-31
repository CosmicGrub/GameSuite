package com.gamesuite.games.spider

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.SnapshotHistory
import com.gamesuite.core.UndoableGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Where the player has picked up a run: column and the index of its first card. */
data class SpiderSelection(val col: Int, val index: Int)

enum class SpiderReject { FACE_DOWN, NOT_A_RUN, BAD_TARGET, DEAL_EMPTY_COLUMN, NO_STOCK }

/**
 * What just happened, as data. The screen turns it into a localized sentence for the status line and
 * the TalkBack live region, so the engine holds no display text at all.
 */
sealed class SpiderEvent {
    data object None : SpiderEvent()
    data class NewDeal(val daily: Boolean, val provenWinnable: Boolean) : SpiderEvent()
    data class Moved(val count: Int, val card: Int, val to: Int, val flipped: Boolean, val completedSuit: Int?) : SpiderEvent()
    data class Dealt(val completedRuns: Int) : SpiderEvent()
    data class Selected(val card: Int, val count: Int, val col: Int) : SpiderEvent()
    data object Deselected : SpiderEvent()
    data class Rejected(val reason: SpiderReject) : SpiderEvent()
    data object Undone : SpiderEvent()
    data class Hinted(val hint: SpiderHint) : SpiderEvent()
    data object NoMoves : SpiderEvent()
    data object Won : SpiderEvent()
    data object Resumed : SpiderEvent()
}

enum class SpiderChangeKind { NEW_DEAL, MOVE, DEAL, UNDO }

/** Reported to the screen BEFORE the new state is published, with both tables, so it can animate the difference. */
class SpiderChange(
    val kind: SpiderChangeKind,
    val before: SpiderState?,
    val after: SpiderState,
    val move: SpiderMove? = null,
    val completedSuits: List<Int> = emptyList()
)

@Serializable
private data class SpiderSnapshot(
    val playerId: String,
    val suits: Int,
    val columns: List<List<Int>>,
    val faceDown: List<Int>,
    val stock: List<Int>,
    val completed: List<Int>,
    val moves: Int,
    val score: Int,
    val elapsedSeconds: Int,
    val seed: Long,
    val daily: Boolean,
    val winnable: Boolean,
    val proven: Boolean,
    val gamesWon: Int
)

private val spiderSnapshotJson = Json { ignoreUnknownKeys = true }

/** The Hub option ids Spider reads from `LaunchOptions.extras`. */
object SpiderOptions {
    const val SUITS = "suits"
    const val WINNABLE = "winnable"

    fun suitsFrom(extras: Map<String, String>): Int = extras[SUITS]?.toIntOrNull()?.takeIf { it in SPIDER_VALID_SUIT_COUNTS } ?: 1
    fun winnableFrom(extras: Map<String, String>): Boolean = extras[WINNABLE] == "true"
}

/**
 * Spider Solitaire for one player: 104 cards, ten columns, one, two or four suits. Build columns
 * downward in any suit, move whole same-suit runs, deal a fresh row from the stock when no column is
 * empty, and a finished King-to-Ace run in one suit leaves the table. Clear all eight to win.
 *
 * - The deal comes from the injected [random]. [startMatch] with a `dailySeed` is deterministic for
 *   that day and suit count, and the daily deal is always searched for a provably winnable one.
 * - [newDeal] (suspend) honours [winnable]: it searches seeded shuffles with [SpiderSolver] off the
 *   main thread and keeps the first deal it can prove winnable (see [SpiderDealer]).
 * - Undo is unlimited in practice (capped at [UNDO_LIMIT] snapshots) and restores the exact table.
 * - [snapshot] / [restore] validate the whole table, including card conservation, before accepting it.
 * - Scoring: 500 to start, minus one per move or row dealt (never below zero), plus 100 per run.
 */
class SpiderGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "spider-solitaire"
    override val displayName = "Spider Solitaire"
    override val category = GameCategory.CARD
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SpiderState?>(null)
    val selected = mutableStateOf<SpiderSelection?>(null)
    val hintMove = mutableStateOf<SpiderHint?>(null)
    val event = mutableStateOf<SpiderEvent>(SpiderEvent.None)

    /** Bumped on every event so the screen re-announces even when two consecutive events are equal. */
    val eventSeq = mutableStateOf(0)

    /** Bumped once per FRESH deal (never by [restore] or [undo]); the screen keys its deal-in animation on it. */
    val dealSeq = mutableStateOf(0)

    /** True when the table has no move and no row left but is not won. */
    val stuck = mutableStateOf(false)

    val gamesWon = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    /** The deal the screen shows: "Today's deal" and whether the winnable search proved it. */
    val isDaily = mutableStateOf(false)
    val provenWinnable = mutableStateOf(false)

    /** Hub option "Winnable deals": the next [newDeal] searches for a deal the solver can win. */
    var winnable: Boolean = false

    /** Suit count (1, 2 or 4) for the NEXT deal; a restored save overrides it with its own. */
    var suits: Int = 1
        set(value) {
            field = if (value in SPIDER_VALID_SUIT_COUNTS) value else 1
        }

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var onChange: ((SpiderChange) -> Unit)? = null
    private var seed: Long = 0L
    private var paused = false
    private val history = SnapshotHistory(UNDO_LIMIT)

    override val canUndo: Boolean
        get() = history.depth > 0 && !matchOver.value && state.value?.won != true

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    fun setOnChange(listener: (SpiderChange) -> Unit) {
        onChange = listener
    }

    override fun init(context: GameContext) {
        this.context = context
        gamesWon.value = 0
        matchOver.value = false
        history.clear()
        selected.value = null
        hintMove.value = null
        paused = false
    }

    private fun localPlayerId(): String =
        if (::context.isInitialized) context.players.getOrNull(context.localPlayerIndex)?.playerId.orEmpty() else ""

    private fun emit(e: SpiderEvent) {
        event.value = e
        eventSeq.value += 1
    }

    // ---- dealing --------------------------------------------------------------------------------

    /** Plain synchronous deal ([GameModule] entry point). */
    override fun startMatch() = startMatch(null)

    /** A plain deal; with [dailySeed] the shuffle is the shared daily one (same for every player and suit count). */
    fun startMatch(dailySeed: Long?) {
        val daily = dailySeed != null
        val state = if (dailySeed != null) SpiderDealer.plain(SpiderDealer.seededRandom(dailySeed, suits), suits)
        else SpiderDealer.plain(random, suits)
        commitNewDeal(state, dailySeed ?: 0L, daily, proven = false)
    }

    /**
     * The screen's deal entry point. Daily deals and the "winnable" option search for a winnable
     * deal on a background thread (never the main one); a plain deal is instant.
     */
    suspend fun newDeal(dailySeed: Long?) {
        val wantWinnable = winnable || dailySeed != null
        if (!wantWinnable) {
            startMatch(dailySeed)
            return
        }
        val chosenSeed = dailySeed ?: random.nextLong()
        val suitCount = suits
        val deal = withContext(Dispatchers.Default) { SpiderDealer.winnable(chosenSeed, suitCount) }
        if (matchOver.value) return
        commitNewDeal(deal.state, dailySeed ?: chosenSeed, dailySeed != null, deal.provenWinnable)
    }

    private fun commitNewDeal(s: SpiderState, seed: Long, daily: Boolean, proven: Boolean) {
        this.seed = seed
        history.clear()
        selected.value = null
        hintMove.value = null
        isDaily.value = daily
        provenWinnable.value = proven
        matchOver.value = false
        onChange?.invoke(SpiderChange(SpiderChangeKind.NEW_DEAL, null, s))
        state.value = s
        stuck.value = false
        dealSeq.value += 1
        emit(SpiderEvent.NewDeal(daily, proven))
    }

    // ---- moves ----------------------------------------------------------------------------------

    private fun live(): SpiderState? {
        val s = state.value ?: return null
        return if (matchOver.value || s.won) null else s
    }

    /** Picks up the run starting at [index] of [col]. False (with a [SpiderEvent.Rejected]) when it is not a movable run. */
    fun select(col: Int, index: Int): Boolean {
        val s = live() ?: return false
        hintMove.value = null
        if (col !in 0 until SPIDER_COLUMNS || index !in s.columns[col].indices) return false
        if (!s.isFaceUp(col, index)) {
            emit(SpiderEvent.Rejected(SpiderReject.FACE_DOWN))
            return false
        }
        if (!SpiderRules.canPickUp(s, col, index)) {
            emit(SpiderEvent.Rejected(SpiderReject.NOT_A_RUN))
            return false
        }
        selected.value = SpiderSelection(col, index)
        emit(SpiderEvent.Selected(s.columns[col][index], s.columns[col].size - index, col))
        return true
    }

    fun clearSelection() {
        if (selected.value != null) {
            selected.value = null
            emit(SpiderEvent.Deselected)
        }
    }

    /** Tap on a card: pick up a run, or, with a run in hand, try to drop it on this card's column. */
    fun tapCard(col: Int, index: Int) {
        val s = live() ?: return
        val sel = selected.value
        if (sel == null) {
            select(col, index)
            return
        }
        hintMove.value = null
        if (sel.col == col) {
            if (sel.index == index) clearSelection()
            else if (SpiderRules.canPickUp(s, col, index)) select(col, index)
            else {
                selected.value = null
                emit(SpiderEvent.Rejected(SpiderReject.BAD_TARGET))
            }
            return
        }
        if (move(sel.col, sel.index, col)) return
        if (SpiderRules.canPickUp(s, col, index)) select(col, index)
        else emit(SpiderEvent.Rejected(SpiderReject.BAD_TARGET))
    }

    /** Tap on a column's empty slot or backing: drop the held run here if it fits. */
    fun tapColumn(col: Int) {
        live() ?: return
        val sel = selected.value ?: return
        if (sel.col == col) {
            clearSelection()
            return
        }
        move(sel.col, sel.index, col)
    }

    /** Moves the run directly (drag and drop, tests). Returns true on success; a rejection announces why. */
    fun move(from: Int, index: Int, to: Int): Boolean {
        val s = live() ?: return false
        hintMove.value = null
        if (from !in 0 until SPIDER_COLUMNS || to !in 0 until SPIDER_COLUMNS) return false
        val outcome = SpiderRules.applyMove(s, SpiderMove(from, index, to))
        if (outcome == null) {
            if (index in s.columns.getOrNull(from).orEmpty().indices && !s.isFaceUp(from, index)) emit(SpiderEvent.Rejected(SpiderReject.FACE_DOWN))
            else if (!SpiderRules.canPickUp(s, from, index)) emit(SpiderEvent.Rejected(SpiderReject.NOT_A_RUN))
            else emit(SpiderEvent.Rejected(SpiderReject.BAD_TARGET))
            return false
        }
        recordHistory(s)
        val card = s.columns[from][index]
        val count = s.columns[from].size - index
        selected.value = null
        publish(s, outcome, SpiderChangeKind.MOVE, SpiderMove(from, index, to))
        val after = outcome.state
        emitAfterMove(after, SpiderEvent.Moved(count, card, to, outcome.flipped, outcome.completedSuits.firstOrNull()))
        return true
    }

    /** Deals the next row from the stock (needs a card in every column). */
    fun dealRow(): Boolean {
        val s = live() ?: return false
        hintMove.value = null
        if (s.stock.size < SPIDER_COLUMNS) {
            emit(SpiderEvent.Rejected(SpiderReject.NO_STOCK))
            return false
        }
        val outcome = SpiderRules.applyDeal(s)
        if (outcome == null) {
            emit(SpiderEvent.Rejected(SpiderReject.DEAL_EMPTY_COLUMN))
            return false
        }
        recordHistory(s)
        selected.value = null
        publish(s, outcome, SpiderChangeKind.DEAL, null)
        emitAfterMove(outcome.state, SpiderEvent.Dealt(outcome.completedSuits.size))
        return true
    }

    private fun publish(before: SpiderState, outcome: SpiderRules.Outcome, kind: SpiderChangeKind, move: SpiderMove?) {
        val after = outcome.state.copy(elapsedSeconds = before.elapsedSeconds)
        onChange?.invoke(SpiderChange(kind, before, after, move, outcome.completedSuits))
        state.value = after
    }

    private fun emitAfterMove(after: SpiderState, moved: SpiderEvent) {
        if (after.won) {
            gamesWon.value += 1
            stuck.value = false
            emit(SpiderEvent.Won)
            return
        }
        stuck.value = !SpiderRules.hasAnyMove(after)
        emit(if (stuck.value) SpiderEvent.NoMoves else moved)
    }

    // ---- undo / hint ----------------------------------------------------------------------------

    private fun recordHistory(s: SpiderState) {
        history.push(encode(s))
    }

    override fun undo() {
        if (matchOver.value || state.value?.won == true) return
        val previous = history.pop() ?: return
        val snap = decode(previous) ?: return
        val current = state.value
        val restored = snap.copy(elapsedSeconds = maxOf(snap.elapsedSeconds, current?.elapsedSeconds ?: 0))
        selected.value = null
        hintMove.value = null
        onChange?.invoke(SpiderChange(SpiderChangeKind.UNDO, current, restored))
        state.value = restored
        stuck.value = !SpiderRules.hasAnyMove(restored)
        emit(SpiderEvent.Undone)
    }

    /** Suggests a move or a deal; the screen rings it until the next action. */
    fun hint(): SpiderHint? {
        val s = live() ?: return null
        val h = SpiderHints.suggest(s)
        hintMove.value = h
        if (h == null) emit(SpiderEvent.NoMoves) else emit(SpiderEvent.Hinted(h))
        return h
    }

    /**
     * [hint] with a short budgeted search ([SpiderSolver]) on a background thread: the first step of a
     * line that wins, or of the line that finishes the most runs, falling back to the greedy [hint]
     * when the search finds no progress. Dropped if the table changed meanwhile.
     */
    suspend fun requestHint(): SpiderHint? {
        val s = live() ?: return null
        val step = withContext(Dispatchers.Default) { SpiderSolver(6_000, restarts = 4, seed = s.moves.toLong()).solve(s).firstStep }
        val now = state.value ?: return null
        if (matchOver.value || now.copy(elapsedSeconds = 0) != s.copy(elapsedSeconds = 0)) return null
        val h = step ?: SpiderHints.suggest(s)
        hintMove.value = h
        if (h == null) emit(SpiderEvent.NoMoves) else emit(SpiderEvent.Hinted(h))
        return h
    }

    // ---- clock ----------------------------------------------------------------------------------

    /** One second of play; the screen calls it from a ticker while it is on screen and the deal is live. */
    fun tick() {
        val s = state.value ?: return
        if (paused || matchOver.value || s.won) return
        state.value = s.copy(elapsedSeconds = s.elapsedSeconds + 1)
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    // ---- session --------------------------------------------------------------------------------

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Back to the menu: reports deals won this visit (a strict win once at least one was solved). */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        endMatch(
            GameResult(
                scores = if (player != null) listOf(PlayerScore(player.playerId, gamesWon.value, gamesWon.value > 0)) else emptyList()
            )
        )
    }

    // ---- resume ---------------------------------------------------------------------------------

    private fun encode(s: SpiderState): String = spiderSnapshotJson.encodeToString(
        SpiderSnapshot.serializer(),
        SpiderSnapshot(
            playerId = localPlayerId(), suits = s.suits, columns = s.columns, faceDown = s.faceDown, stock = s.stock,
            completed = s.completed, moves = s.moves, score = s.score, elapsedSeconds = s.elapsedSeconds,
            seed = seed, daily = isDaily.value, winnable = winnable, proven = provenWinnable.value, gamesWon = gamesWon.value
        )
    )

    private fun decode(text: String): SpiderState? = decodeFull(text)?.state

    private class Decoded(val state: SpiderState, val seed: Long, val daily: Boolean, val winnable: Boolean, val proven: Boolean, val gamesWon: Int)

    private fun decodeFull(text: String): Decoded? {
        val snap = try {
            spiderSnapshotJson.decodeFromString(SpiderSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (snap.playerId != localPlayerId() || snap.gamesWon < 0) return null
        val s = SpiderState(
            suits = snap.suits, columns = snap.columns, faceDown = snap.faceDown, stock = snap.stock,
            completed = snap.completed, moves = snap.moves, score = snap.score, elapsedSeconds = snap.elapsedSeconds
        )
        return try {
            if (!SpiderRules.isConsistent(s)) null else Decoded(s, snap.seed, snap.daily, snap.winnable, snap.proven, snap.gamesWon)
        } catch (_: Exception) {
            null
        }
    }

    override fun snapshot(): String? {
        val s = state.value ?: return null
        if (matchOver.value || s.won || !::context.isInitialized) return null
        return try {
            encode(s)
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val decoded = decodeFull(snapshot) ?: return false
        matchOver.value = false
        history.clear()
        selected.value = null
        hintMove.value = null
        seed = decoded.seed
        isDaily.value = decoded.daily
        provenWinnable.value = decoded.proven
        suits = decoded.state.suits
        gamesWon.value = decoded.gamesWon
        state.value = decoded.state
        stuck.value = !SpiderRules.hasAnyMove(decoded.state)
        emit(SpiderEvent.Resumed)
        return true
    }

    companion object {
        /** Undo depth: far more than a game ever needs, but bounded so memory cannot grow without limit. */
        const val UNDO_LIMIT = 10_000
    }
}
