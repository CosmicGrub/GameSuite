package com.gamesuite.games.gemcascade

/**
 * THE STEP LOG: the central deliverable of the engine. Every public engine operation returns a [MoveResult] whose
 * [MoveResult.events] is an ordered list of these typed events. Renderers (2D, 2.5D, 3D), sound and haptics animate
 * ONLY from this list. [GemEventApplier] proves the log is complete: applying the board-affecting events, in order,
 * to the board as it was before the operation yields exactly the board after it.
 *
 * Per cascade step the order is: ChainStep, Match*, SpecialCreated*, SpecialActivated* (or ComboActivated), the
 * ObstacleDamaged* events, Clear, ScoreDelta, goal / time events, then Fall*, Spawn* (and Escaped*) of the settle.
 * Full description with an example: docs/design/gem-cascade/EVENTS.md.
 */
sealed interface GemEvent {
    /** A fresh board was dealt (start of a game or a quest level). Not produced by moves. */
    data class Deal(val rows: Int, val cols: Int) : GemEvent

    /** The two cells exchanged places (always emitted first for a swap; [SwapBack] follows if there was no match). */
    data class Swap(val a: Pos, val b: Pos) : GemEvent

    /** The classic "swap back": the swap made no match, the pieces return. Net effect of Swap + SwapBack is none. */
    data class SwapBack(val a: Pos, val b: Pos) : GemEvent

    /** Twist: the 2 x 2 block with top-left [topLeft] turned [steps] quarter turns before it formed a match. */
    data class Rotate(val topLeft: Pos, val clockwise: Boolean, val steps: Int) : GemEvent

    /** Twist: no quarter turn made a match; the block ends as it started (no board change). */
    data class RotateBack(val topLeft: Pos, val clockwise: Boolean) : GemEvent

    /** A group of matched gems of one [kind]. [cells] sorted row-major. */
    data class Match(val cells: List<Pos>, val shape: MatchShape, val kind: Int) : GemEvent

    /** A special gem now sits at [at] (the gem there stays and is not cleared). */
    data class SpecialCreated(val at: Pos, val special: SpecialKind, val kind: Int) : GemEvent

    /** A special gem fired; [affected] are the cells its effect covers (sorted). The gem itself is among them. */
    data class SpecialActivated(val at: Pos, val special: SpecialKind, val affected: List<Pos>) : GemEvent

    /** Two specials were swapped together: [effect] covers [affected] (sorted). The two pieces are consumed. */
    data class ComboActivated(val a: SpecialKind, val b: SpecialKind, val at: Pos, val effect: ComboEffect, val affected: List<Pos>) : GemEvent

    /** Gems leave the board (sorted). Stones and spiders are not listed here, see [ObstacleDamaged]. */
    data class Clear(val cells: List<Pos>, val cause: ClearCause, val chain: Int) : GemEvent

    /** The piece at [from] falls to [to] (same column, lower row). */
    data class Fall(val from: Pos, val to: Pos) : GemEvent

    /** A new [cell] appears at [at], entering from row [fromRow] (negative = above the board; below a stone it enters at the stone). */
    data class Spawn(val at: Pos, val cell: Cell, val fromRow: Int) : GemEvent

    /** The Nth link of the chain (1 = the player's own match) and its score [multiplier]. */
    data class ChainStep(val n: Int, val multiplier: Int) : GemEvent

    /** Points added. [total] is the score after adding. */
    data class ScoreDelta(val points: Int, val total: Int, val reason: ScoreReason) : GemEvent

    /** An obstacle layer was hit. [hpLeft] 0 means that layer of obstacle is gone (ice melted, cage broken, stone / spider destroyed). */
    data class ObstacleDamaged(val at: Pos, val obstacle: ObstacleKind, val hpLeft: Int) : GemEvent

    /** A piece moved by a rule (a butterfly or spider rising, the gem it swapped with dropping). */
    data class PieceMoved(val from: Pos, val to: Pos) : GemEvent

    /** A butterfly escaped through the top or a treasure was collected at the bottom (the piece is gone). A spider reaching the top is [GameOver] with [OverReason.SPIDER] instead. */
    data class Escaped(val at: Pos, val piece: PieceType) : GemEvent

    /** A time bomb's counter went down; [remaining] is what is left. */
    data class BombTick(val at: Pos, val remaining: Int) : GemEvent

    /** A time bomb reached 0: the game is over. */
    data class BombExploded(val at: Pos) : GemEvent

    /** A time bomb was cleared in time. */
    data class BombDefused(val at: Pos) : GemEvent

    /** A goal's counter changed. */
    data class LevelGoalProgress(val goal: GoalType, val kind: Int, val current: Int, val target: Int) : GemEvent

    /** Moves are limited and this many remain after the event. */
    data class MovesLeft(val remaining: Int) : GemEvent

    /** Lightning: time was added. */
    data class TimeBonus(val ms: Long, val reason: TimeReason) : GemEvent

    /** Lightning: the speed meter (0..100) changed. */
    data class SpeedMeter(val value: Int) : GemEvent

    /** Lightning: the meter filled; scoring doubles for [burstMs] and [bonusMs] of time were added. */
    data class SpeedBonus(val bonusMs: Long, val burstMs: Long) : GemEvent

    /** No legal move was left. [moves] is the permutation (from -> to); [regenerated] means the gems were redealt (Spawn events follow). */
    data class NoMovesShuffle(val moves: List<ShuffleMove>, val regenerated: Boolean) : GemEvent

    /** A booster was used; [remaining] uses are left this level. */
    data class BoosterUsed(val booster: BoosterKind, val at: Pos?, val other: Pos?, val remaining: Int) : GemEvent

    /** Classic: the score passed a threshold. */
    data class LevelUp(val level: Int) : GemEvent

    /** Quest: every goal is met. [stars] 1..3. */
    data class LevelComplete(val stars: Int, val score: Int) : GemEvent

    /** The game ended without a win (or after a score mode ran out). */
    data class GameOver(val reason: OverReason, val score: Int) : GemEvent
}

data class ShuffleMove(val from: Pos, val to: Pos)

enum class MatchShape { LINE_3, LINE_4, LINE_5, L, T, CROSS, DOUBLE_4 }
enum class ClearCause { MATCH, SPECIAL, COMBO, BOOSTER }
enum class ScoreReason { MATCH, SPECIAL, COMBO, OBSTACLE, LEFTOVER_MOVES }
enum class ObstacleKind { ICE, LOCK, STONE, SPIDER }
enum class TimeReason { SPECIAL, CHAIN, SPEED, BOOSTER }
enum class OverReason { NO_MOVES, OUT_OF_MOVES, TIME_UP, BOMB, SPIDER }
enum class ComboEffect { CROSS, FAT_CROSS_1, FAT_CROSS_2, AREA_2, AREA_3, BOARD, COLOUR_TO_FLARES, COLOUR_TO_BURSTS, COLOUR_TO_NOVAS, COLOUR_CLEAR }
enum class RejectReason { GAME_OVER, NOT_ALLOWED, OUT_OF_BOARD, NOT_ADJACENT, BLOCKED, NO_MATCH, NO_MOVES_LEFT, TIME_UP, BOOSTER_CAP, BAD_TARGET }

/** What a move attempt returned. A rejected attempt may still carry events (Swap + SwapBack, RotateBack) to animate. */
data class MoveResult(val accepted: Boolean, val reason: RejectReason?, val events: List<GemEvent>) {
    companion object {
        fun rejected(reason: RejectReason, events: List<GemEvent> = emptyList()) = MoveResult(false, reason, events)
        fun ok(events: List<GemEvent>) = MoveResult(true, null, events)
    }
}

/** A player action the engine can take; also the unit of replays and of the bot's choices. */
sealed interface GemAction {
    data class SwapAction(val a: Pos, val b: Pos) : GemAction
    data class RotateAction(val topLeft: Pos, val clockwise: Boolean) : GemAction
    data class BoosterAction(val booster: BoosterKind, val at: Pos?, val other: Pos?) : GemAction
    data class Elapse(val ms: Long) : GemAction
}

enum class BoosterKind { HAMMER, SWAP_ANY, SHUFFLE, EXTRA_MOVES, COLOUR_BOMB }

enum class GoalType { SCORE, CLEAR_GEMS, CLEAR_ICE, BREAK_STONES, BREAK_LOCKS, COLLECT_TREASURE, ESCAPE_BUTTERFLIES, DEFUSE_BOMBS, MAKE_SPECIALS, SQUASH_SPIDERS }

/** One goal's live counter. [kind] is a gem colour for CLEAR_GEMS (-1 = any colour), otherwise -1. */
data class GoalState(val type: GoalType, val kind: Int, val target: Int, val current: Int) {
    val done: Boolean get() = current >= target
}

/** Applies the board-affecting events to a board (the proof that the log is complete, and the core of a simple renderer). */
object GemEventApplier {
    fun apply(board: GemBoard, e: GemEvent) {
        when (e) {
            is GemEvent.Swap -> board.swapCells(e.a, e.b)
            is GemEvent.SwapBack -> board.swapCells(e.a, e.b)
            is GemEvent.Rotate -> repeat(e.steps) { GemRotation.rotate(board, e.topLeft, e.clockwise) }
            is GemEvent.SpecialCreated -> board[e.at] = board[e.at].copy(special = e.special)
            is GemEvent.Clear -> e.cells.forEach { board[it] = Cell.EMPTY }
            is GemEvent.Fall -> {
                board[e.to] = board[e.from]
                board[e.from] = Cell.EMPTY
            }
            is GemEvent.Spawn -> board[e.at] = e.cell
            is GemEvent.PieceMoved -> board.swapCells(e.from, e.to)
            is GemEvent.Escaped -> board[e.at] = Cell.EMPTY
            is GemEvent.BombTick -> board[e.at] = board[e.at].copy(timer = e.remaining)
            is GemEvent.ObstacleDamaged -> {
                val cell = board[e.at]
                when (e.obstacle) {
                    ObstacleKind.ICE -> board.ice[board.idx(e.at)] = e.hpLeft
                    ObstacleKind.LOCK -> board[e.at] = cell.copy(locked = false)
                    ObstacleKind.STONE, ObstacleKind.SPIDER ->
                        board[e.at] = if (e.hpLeft <= 0) Cell.EMPTY else cell.copy(hp = e.hpLeft)
                }
            }
            is GemEvent.NoMovesShuffle -> {
                val moved = e.moves.map { it.to to board[it.from] }
                e.moves.forEach { board[it.from] = Cell.EMPTY }
                moved.forEach { (p, c) -> board[p] = c }
            }
            is GemEvent.BombDefused -> Unit
            else -> Unit
        }
    }

    fun applyAll(board: GemBoard, events: List<GemEvent>) = events.forEach { apply(board, it) }
}

/** 2 x 2 block rotation shared by the engine and the applier. */
object GemRotation {
    fun rotate(board: GemBoard, tl: Pos, clockwise: Boolean) {
        val a = tl
        val b = Pos(tl.r, tl.c + 1)
        val c = Pos(tl.r + 1, tl.c + 1)
        val d = Pos(tl.r + 1, tl.c)
        val ca = board[a]; val cb = board[b]; val cc = board[c]; val cd = board[d]
        if (clockwise) {
            board[b] = ca; board[c] = cb; board[d] = cc; board[a] = cd
        } else {
            board[a] = cb; board[b] = cc; board[c] = cd; board[d] = ca
        }
    }
}
