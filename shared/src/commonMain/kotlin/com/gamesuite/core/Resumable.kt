package com.gamesuite.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A game whose in-progress match can be written down and picked back up: after Android kills the
 * app in the background, after the player leaves mid-match and chooses Resume from the menu, and
 * (through [SnapshotHistory]) for Undo. The shell never looks inside the snapshot; it is each
 * engine's own JSON string, so the shell stays free of game rules (see [GameModule]).
 *
 * Contract:
 *  - [snapshot] returns the current unit of play as a self-contained string, or null when there
 *    is nothing worth saving: not started, match already over, or a mode that cannot be resumed
 *    (online / nearby, whose state lives with the other players). It must be cheap, read-only and
 *    safe to call at any moment, including while a bot's turn is pending.
 *  - [restore] is called after [GameModule.init] and INSTEAD of [GameModule.startMatch]. It
 *    returns true when the engine is fully back in the saved state, and false when the snapshot
 *    is stale, corrupt, or for a different roster/variant; on false the engine must be left
 *    untouched so the screen can fall back to a fresh [GameModule.startMatch]. Never throw.
 *
 * A snapshot has to carry everything needed to continue honestly: the board, whose turn it is,
 * the session tally (wins, puzzles solved), the difficulty/variant, the daily seed, and the
 * ACTIVE (pause-adjusted) elapsed time so a resumed clock continues rather than restarting or
 * counting the time the app was gone.
 */
interface ResumableGame {
    fun snapshot(): String?
    fun restore(snapshot: String): Boolean
}

/** Optional, for engines that offer "take back my last move". Backed by [SnapshotHistory]. */
interface UndoableGame {
    /** True while there is a move to take back. Read from composition: back it with Compose state. */
    val canUndo: Boolean

    /** Takes back the last human move (and, in a vs-CPU game, the CPU reply that followed it). */
    fun undo()
}

/**
 * A bounded stack of [ResumableGame] snapshots: push BEFORE a human move is applied, pop to undo.
 * [depth] is Compose state, so `canUndo = history.depth > 0` recomposes a button on its own.
 *
 * Identical consecutive snapshots collapse into one (a rejected move that changed nothing must
 * not leave a no-op undo step behind), and the oldest entries fall off past [limit] so a long
 * puzzle session cannot grow without bound.
 */
class SnapshotHistory(private val limit: Int = 200) {
    private val stack = ArrayDeque<String>()

    /** Number of undo steps available. */
    var depth by mutableStateOf(0)
        private set

    val isEmpty: Boolean get() = depth == 0

    fun push(snapshot: String) {
        if (stack.lastOrNull() == snapshot) return
        stack.addLast(snapshot)
        while (stack.size > limit) stack.removeFirst()
        depth = stack.size
    }

    /** The most recent snapshot, removed; null when there is nothing to undo. */
    fun pop(): String? {
        val top = stack.removeLastOrNull()
        depth = stack.size
        return top
    }

    fun clear() {
        stack.clear()
        depth = 0
    }
}
